package np.com.narayanipauroti.kds

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * On-screen pointer driven by the TV remote's D-pad, so every button on the web page
 * can be reached without a mouse. D-pad moves the pointer (accelerating while held),
 * OK/Enter clicks, and pushing against a screen edge scrolls. A real mouse or touch
 * hides the pointer and works as usual.
 */
class CursorController(
    private val root: ViewGroup,
    private val targetProvider: () -> View?
) {
    private val density = root.resources.displayMetrics.density
    val view = CursorView(root.context)

    private var x = -1f
    private var y = -1f
    private val heldDirections = mutableSetOf<Int>()
    private var holdStart = 0L
    private var lastFrame = 0L
    private var lastEdgeScroll = 0L
    private var clickDownTime = 0L
    private var loopRunning = false

    private val hideRunnable = Runnable { view.visibility = View.GONE }

    private val frame = object : Runnable {
        override fun run() {
            if (heldDirections.isEmpty()) {
                loopRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(1L, 50L) / 1000f
            lastFrame = now
            val heldSeconds = (now - holdStart) / 1000f
            val speed = (BASE_SPEED_DP + ACCELERATION_DP * heldSeconds).coerceAtMost(MAX_SPEED_DP) * density
            val (dx, dy) = direction()
            moveBy(dx * speed * dt, dy * speed * dt, dx, dy)
            root.postOnAnimation(this)
        }
    }

    /** Returns true when the key was consumed as a pointer action. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        // Arrow keys on a real keyboard go to the page (text fields, shortcuts).
        if (event.device?.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false
        // Always release a held direction, even if the page went away meanwhile.
        if (event.action == KeyEvent.ACTION_UP && heldDirections.remove(event.keyCode)) {
            scheduleHide()
            return true
        }
        val target = targetProvider()
        if (target == null) {
            reset()
            return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    show()
                    if (heldDirections.isEmpty()) holdStart = SystemClock.uptimeMillis()
                    heldDirections.add(event.keyCode)
                    // Small immediate nudge so a quick tap always moves the pointer.
                    val (dx, dy) = direction()
                    moveBy(dx * NUDGE_DP * density, dy * NUDGE_DP * density, dx, dy)
                    startLoop()
                } else if (event.action == KeyEvent.ACTION_UP) {
                    heldDirections.remove(event.keyCode)
                    scheduleHide()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    show()
                    clickDownTime = SystemClock.uptimeMillis()
                    dispatchTouch(target, MotionEvent.ACTION_DOWN)
                } else if (event.action == KeyEvent.ACTION_UP && clickDownTime != 0L) {
                    dispatchTouch(target, MotionEvent.ACTION_UP)
                    clickDownTime = 0L
                    scheduleHide()
                }
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) scroll(target, 0f, PAGE_SCROLL_STEPS)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) scroll(target, 0f, -PAGE_SCROLL_STEPS)
                return true
            }
        }
        return false
    }

    /** Called for real mouse / touch input: hide the virtual pointer and follow the real one. */
    fun onPointerInput(rawX: Float, rawY: Float) {
        x = rawX
        y = rawY
        view.visibility = View.GONE
    }

    fun reset() {
        heldDirections.clear()
        if (clickDownTime != 0L) {
            targetProvider()?.let { dispatchTouch(it, MotionEvent.ACTION_CANCEL) }
            clickDownTime = 0L
        }
    }

    private fun direction(): Pair<Float, Float> {
        var dx = 0f
        var dy = 0f
        if (KeyEvent.KEYCODE_DPAD_LEFT in heldDirections) dx -= 1f
        if (KeyEvent.KEYCODE_DPAD_RIGHT in heldDirections) dx += 1f
        if (KeyEvent.KEYCODE_DPAD_UP in heldDirections) dy -= 1f
        if (KeyEvent.KEYCODE_DPAD_DOWN in heldDirections) dy += 1f
        return dx to dy
    }

    private fun startLoop() {
        if (loopRunning) return
        loopRunning = true
        lastFrame = SystemClock.uptimeMillis()
        root.postOnAnimation(frame)
    }

    private fun show() {
        root.removeCallbacks(hideRunnable)
        if (x < 0f || y < 0f) {
            x = root.width / 2f
            y = root.height / 2f
        }
        view.moveTo(x, y)
        view.visibility = View.VISIBLE
    }

    private fun scheduleHide() {
        root.removeCallbacks(hideRunnable)
        root.postDelayed(hideRunnable, HIDE_AFTER_MS)
    }

    private fun moveBy(mx: Float, my: Float, dirX: Float, dirY: Float) {
        val maxX = (root.width - 1).toFloat().coerceAtLeast(0f)
        val maxY = (root.height - 1).toFloat().coerceAtLeast(0f)
        x = (x + mx).coerceIn(0f, maxX)
        y = (y + my).coerceIn(0f, maxY)
        view.moveTo(x, y)

        val target = targetProvider() ?: return
        dispatchHover(target)

        // Pushing against an edge scrolls whatever is under the pointer.
        val now = SystemClock.uptimeMillis()
        if (now - lastEdgeScroll < EDGE_SCROLL_INTERVAL_MS) return
        val atEdge = EDGE_DP * density
        val vScroll = when {
            dirY > 0 && y >= maxY - atEdge -> -1f
            dirY < 0 && y <= atEdge -> 1f
            else -> 0f
        }
        val hScroll = when {
            dirX > 0 && x >= maxX - atEdge -> 1f
            dirX < 0 && x <= atEdge -> -1f
            else -> 0f
        }
        if (vScroll != 0f || hScroll != 0f) {
            lastEdgeScroll = now
            scroll(target, hScroll, vScroll)
        }
    }

    private fun targetPoint(target: View): Pair<Float, Float> {
        val rootLoc = IntArray(2).also { root.getLocationInWindow(it) }
        val targetLoc = IntArray(2).also { target.getLocationInWindow(it) }
        return (x + rootLoc[0] - targetLoc[0]) to (y + rootLoc[1] - targetLoc[1])
    }

    private fun dispatchTouch(target: View, action: Int) {
        val (tx, ty) = targetPoint(target)
        val now = SystemClock.uptimeMillis()
        val downTime = if (clickDownTime != 0L) clickDownTime else now
        val event = MotionEvent.obtain(downTime, now, action, tx, ty, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun dispatchHover(target: View) {
        val event = mouseEvent(target, MotionEvent.ACTION_HOVER_MOVE) {}
        target.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private fun scroll(target: View, h: Float, v: Float) {
        if (x < 0f || y < 0f) {
            x = root.width / 2f
            y = root.height / 2f
        }
        val event = mouseEvent(target, MotionEvent.ACTION_SCROLL) {
            it.setAxisValue(MotionEvent.AXIS_HSCROLL, h)
            it.setAxisValue(MotionEvent.AXIS_VSCROLL, v)
        }
        target.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    private inline fun mouseEvent(
        target: View,
        action: Int,
        axes: (MotionEvent.PointerCoords) -> Unit
    ): MotionEvent {
        val (tx, ty) = targetPoint(target)
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = tx
            this.y = ty
            axes(this)
        }
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(
            now, now, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
    }

    /** Draws the arrow pointer; never takes input itself. */
    class CursorView(context: Context) : View(context) {
        private val d = resources.displayMetrics.density
        private var px = 0f
        private var py = 0f
        private val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(0f, 24 * d)
            lineTo(6.5f * d, 18 * d)
            lineTo(11 * d, 27.5f * d)
            lineTo(15 * d, 25.5f * d)
            lineTo(10.5f * d, 16.5f * d)
            lineTo(19 * d, 16.5f * d)
            close()
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
            strokeJoin = Paint.Join.ROUND
        }

        init {
            isClickable = false
            isFocusable = false
            visibility = GONE
        }

        fun moveTo(x: Float, y: Float) {
            px = x
            py = y
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(px, py)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
            canvas.restore()
        }
    }

    private companion object {
        const val BASE_SPEED_DP = 220f
        const val ACCELERATION_DP = 700f
        const val MAX_SPEED_DP = 1400f
        const val NUDGE_DP = 6f
        const val EDGE_DP = 4f
        const val EDGE_SCROLL_INTERVAL_MS = 60L
        const val PAGE_SCROLL_STEPS = 8f
        const val HIDE_AFTER_MS = 6000L
    }
}
