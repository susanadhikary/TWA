# TapTill KDS – Android TV app

An Android TV app that shows the TapTill kitchen display
(`https://pos.narayanipauroti.com.np/kds`) full screen.

## Nothing else to install

The app uses **Android System WebView**, which is built into every Android TV and
Google TV. You don't need to install Chrome or any other browser.

- The web address is never shown. There is no address bar, and pop-up dialogs are titled "TapTill KDS" instead of showing the URL. Error screens are the app's own screens.
- Links always open inside the app. Other link types (`intent:`, `market:`, and so on) are blocked, so the KDS never hands off to a browser.
- Long-pressing does nothing: no text selection or "search the web" menu.
- A branded splash screen shows while the KDS loads.
- If WebView is disabled on a TV, the app shows how to turn it back on. If WebView is very old, the app asks you to update it.

## Browser features included

| Web feature | How it works in the app |
|---|---|
| Notifications (`Notification`, `registration.showNotification`) | Shown as native Android notifications on the "Kitchen alerts" channel. Tapping one opens the app and sends a `click` event to the page. |
| Location (`navigator.geolocation`) | Uses Android's location permission. |
| Camera / microphone (`getUserMedia`) | Uses the camera and microphone permissions. |
| Alert, confirm and prompt dialogs | Native dialogs titled "TapTill KDS". |
| File upload (`<input type=file>`) | Opens the system file picker. |
| Downloads (http, `blob:`, `data:`) | Saved to the Downloads folder. |
| `window.print()` | Android print dialog. |
| `navigator.share()` | Android share sheet. |
| `window.open` / `target=_blank` | Opens as a full-screen layer inside the app. BACK closes it. |
| Fullscreen video / Fullscreen API | Supported. |
| Screen Wake Lock | The screen always stays on. |
| Cookies, localStorage, IndexedDB, service worker cache | Kept between restarts, so staff stay logged in. |
| Autoplaying sounds | Allowed, so order alerts can play without a tap. |

Only the KDS site gets these features. Any other site opened inside the app is refused.

On first launch, the app asks once for the location, camera, microphone and
notification permissions.

## Remote control and mouse

- **Remote:** the D-pad moves an on-screen pointer, which speeds up while held. **OK** clicks. Holding the pointer against a screen edge scrolls. **CH+/CH−** (or Page Up/Down) scroll by a page. The pointer hides after 6 seconds without use.
- **Mouse or touch:** works normally, and the remote pointer hides.
- **Keyboard:** arrow keys go straight to the page.
- **MENU** reloads the page. **BACK** goes back or closes a pop-up. Press **BACK** twice to exit.

## Start on boot

The app opens itself when the TV starts up. On Android 10 and later, Android only
allows this after you turn on **Display over other apps** for TapTill KDS. The app
asks for this on first launch. If the TV has no screen for that setting, run:

```sh
adb shell appops set np.com.narayanipauroti.kds SYSTEM_ALERT_WINDOW allow
```

## Offline handling

If the network drops, the app shows its own "Cannot reach the kitchen display"
screen and retries every 10 seconds. If the web page's rendering process crashes,
the app rebuilds the page automatically.

## Getting the APK

The latest signed release is committed in [`releases/`](releases/):
`releases/TapTill-KDS-1.1.0-release.apk` (version 1.1.0, build 2). Its SHA-256 checksum is in `releases/SHA256SUMS`.

Every push runs the **Build Android TV APK** GitHub Actions workflow. Open the run
in the *Actions* tab and download the `taptill-kds-apk` artifact. It contains:

- `debug/TapTill-KDS-debug.apk`, which is always built.
- `release/TapTill-KDS-release.apk`, a signed build that is only made when the signing secrets are set.

### Release signing

Add these repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
| --- | --- |
| `KDS_KEYSTORE_BASE64` | `base64 -w0 taptill-kds-release.jks` |
| `KDS_KEYSTORE_PASSWORD` | keystore password |
| `KDS_KEY_ALIAS` | `taptill-kds` |
| `KDS_KEY_PASSWORD` | key password |

Never commit the keystore. Keep a backup: without it, you can't publish updates to
installed copies of the app.

To build a signed release locally:

```sh
KDS_KEYSTORE_PATH=/path/to/taptill-kds-release.jks \
KDS_KEYSTORE_PASSWORD=... KDS_KEY_ALIAS=taptill-kds KDS_KEY_PASSWORD=... \
./gradlew assembleRelease
```

## Installing on the TV

1. On the TV, go to *Settings → Device Preferences → About* and click *Build* 7 times. This turns on Developer options.
2. In *Developer options*, turn on **USB debugging** or **Network debugging**.
3. From your computer, run:
   ```sh
   adb connect <tv-ip-address>
   adb install -r TapTill-KDS-release.apk
   ```

You can also sideload the APK with a USB drive or the *Downloader* app.

## Configuration

- **URL:** `START_URL` in `app/build.gradle.kts`.
- **Name:** `app_name` in `app/src/main/res/values/strings.xml`.
- **Icons:** generated from the icons in the PWA manifest (`https://pos.narayanipauroti.com.np/manifest.webmanifest`). They live in `res/mipmap-*` (launcher), `res/drawable-*/ic_launcher_foreground.png` (adaptive), `res/drawable-xhdpi/banner.png` (TV banner) and `res/drawable-nodpi/app_logo.png` (splash).
