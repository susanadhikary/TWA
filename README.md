# TapTill KDS – Android TV app

An Android TV app that runs the **TapTill Kitchen Display System (KDS)** full
screen, like a native app. It loads the TapTill PWA:

> `https://pos.narayanipauroti.com.np/kds`

That address, and everything else you'd normally change, is set in one file,
[`kds.properties`](kds.properties). A helper script changes it and rebuilds
the app in one step. See [Change the URL, scope or name](#change-the-url-scope-or-name).

| | |
|---|---|
| **App name** | TapTill KDS |
| **App ID** | `np.com.narayanipauroti.kds` (never change it, see [Rules](#rules-you-must-not-break)) |
| **Current version** | 1.2.0 (version code 3) |
| **Runs on** | Android TV / Google TV 5.0+ (API 21+). Also installs on Android phones, tablets and Fire TV. |
| **Latest signed APK** | [`releases/TapTill-KDS-1.2.0-release.apk`](releases/) |
| **Step-by-step rebuild guide** | [`docs/CHANGE-URL-AND-REBUILD.md`](docs/CHANGE-URL-AND-REBUILD.md) |

---

## Contents

- [Quick start](#quick-start)
- [What the app does](#what-the-app-does)
  - [Works without Chrome or any other browser](#works-without-chrome-or-any-other-browser)
  - [Looks like an app, not a website](#looks-like-an-app-not-a-website)
  - [Browser features included](#browser-features-included)
  - [Permissions](#permissions)
  - [Screen size and resolution](#screen-size-and-resolution)
  - [Remote control, mouse and keyboard](#remote-control-mouse-and-keyboard)
  - [Start on boot](#start-on-boot)
  - [Always-on reliability](#always-on-reliability)
- [Change the URL, scope or name](#change-the-url-scope-or-name)
  - [The helper script](#the-helper-script)
  - [Editing kds.properties by hand](#editing-kdsproperties-by-hand)
  - [What "scope" means](#what-scope-means)
- [Building the APK](#building-the-apk)
  - [On GitHub (no tools needed)](#on-github-no-tools-needed)
  - [On your own computer](#on-your-own-computer)
- [Signing](#signing)
- [Installing and updating on TVs](#installing-and-updating-on-tvs)
- [First-run setup on each TV](#first-run-setup-on-each-tv)
- [Project structure](#project-structure)
- [How it works (technical)](#how-it-works-technical)
- [Security model](#security-model)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Rules you must not break](#rules-you-must-not-break)
- [FAQ](#faq)

---

## Quick start

**Install the current version on a TV:**

1. Download [`releases/TapTill-KDS-1.2.0-release.apk`](releases/).
2. On the TV: *Settings → Device Preferences → About*, click **Build** 7 times.
   Then turn on **USB debugging** or **Network debugging** in *Developer options*.
3. From a computer on the same network:
   ```sh
   adb connect <tv-ip-address>
   adb install -r TapTill-KDS-1.2.0-release.apk
   ```
4. Open **TapTill KDS** from the TV home screen and allow the permissions it asks for.

**Point the app at a new URL and rebuild:**

```sh
./kds.sh          # macOS / Linux / Git Bash
.\kds.ps1         # Windows PowerShell, or double-click kds.cmd
```

The script asks for the new URL, scope and name, raises the version, and builds
the APK into `dist/`. No build tools? Run it with `--build none` (Windows:
`-Build none`), push, and download the APK from GitHub Actions.

---

## What the app does

### Works without Chrome or any other browser

The app shows the KDS with **Android System WebView**. WebView is built into
every Android TV and Google TV and is updated by the system, so **no browser
needs to be installed on the TV**.

This is also why the app is not a "Trusted Web Activity" (TWA). A TWA needs
Chrome installed, and most Android TVs don't have it.

If WebView is disabled on a TV, the app shows how to turn it back on. If it's
very old (older than version 90), the app shows a one-time message asking you
to update it.

### Looks like an app, not a website

- **No address bar.** The web address is never shown to staff.
- **Branded splash screen** with the TapTill logo while the KDS loads.
- **Own error screens.** "Cannot reach the kitchen display" and "Secure
  connection failed" screens replace the browser's error page, which would
  show the URL.
- **Dialogs titled "TapTill KDS".** JavaScript `alert`, `confirm` and `prompt`
  normally say *"The page at https://… says"*. Here they show the app name.
- **No long-press menus.** No text selection or "search the web" pop-ups.
- **Links stay inside the app.** Other link types (`intent:`, `market:`,
  `tel:`…) are blocked, so the KDS never hands off to another app or a browser.
- **Full screen and landscape**, with system bars hidden, and the **screen is
  kept on** permanently.
- **TapTill icon, adaptive icon and TV banner**, generated from the icons in
  the PWA's `manifest.webmanifest`.

### Browser features included

WebView lacks several browser features. The app adds them itself:

| Web feature | How it works in the app |
|---|---|
| **Notifications** (`new Notification()`, `registration.showNotification()`, `Notification.requestPermission()`) | Shown as native Android notifications on the **Kitchen alerts** channel (high priority, with sound). Clicking one brings the app to the front and fires the page's `click` event. |
| **Location** (`navigator.geolocation`) | Uses Android's location permission. |
| **Camera / microphone** (`getUserMedia`) | Uses the camera and microphone permissions, e.g. a USB webcam for scanning. |
| **Alert / confirm / prompt dialogs** | Native dialogs titled with the app name. |
| **File upload** (`<input type="file">`) | Opens the system file picker, with multiple selection supported. |
| **Downloads** (normal links, `blob:` and `data:` URLs, `<a download>`) | Saved to the TV's **Downloads** folder with the page's file name. |
| **Printing** (`window.print()`) | Android print dialog. Needs a print service on the TV. |
| **Sharing** (`navigator.share()`) | Android share sheet. |
| **Pop-ups** (`window.open`, `target="_blank"`) | Open as a full-screen layer inside the app. BACK closes it. |
| **Fullscreen video / Fullscreen API** | Supported. |
| **Screen Wake Lock** (`navigator.wakeLock`) | Always granted, since the app keeps the screen on anyway. |
| **Autoplaying sound** | Allowed, so new-order alerts play without anyone tapping first. |
| **Cookies, localStorage, IndexedDB, service worker cache** | Kept between restarts, so staff stay logged in. |

These features only work for pages **inside the app's scope** (see
[What "scope" means](#what-scope-means)). Any other page that opens inside the
app gets none of them.

### Permissions

On the **first launch**, the app asks once for everything the KDS may need, so
service isn't interrupted later:

| Android permission | Used for | Needed? |
|---|---|---|
| Notifications (Android 13+) | Order alerts from the KDS | Recommended |
| Location (precise and approximate) | `navigator.geolocation` | Only if the KDS uses location |
| Camera | `getUserMedia` video, e.g. barcode scanning | Only if the KDS uses it |
| Microphone | `getUserMedia` audio | Only if the KDS uses it |
| Display over other apps (Android 10+) | Start on boot (see below) | Needed for auto-start |
| Storage (Android 9 and older only) | Saving downloads | Asked when first needed |

If staff deny something, the app keeps working. Only that one web feature is
refused. You can change permissions later in *Settings → Apps → TapTill KDS →
Permissions*.

None of this hardware is *required*, so the app installs on TVs without a
camera, microphone or GPS.

### Screen size and resolution

TVs come in 720p, 1080p and 4K, with different pixel densities. Left alone,
a WebView on most TVs reports a **960 × 540** screen to the page, whatever the
TV's real resolution. Many responsive web UIs then switch to their tablet
layout.

The app fixes this with **`VIEWPORT_WIDTH`** in `kds.properties`:

| `VIEWPORT_WIDTH` | What the KDS page sees | Use when |
|---|---|---|
| **`1470`** (default) | A **1470 × 827** screen on every TV (720p, 1080p, 4K), scaled to fill it exactly. The same width as a 13" MacBook Air browser window: 5 KOT tickets per row. | You want the TV to look like the KDS on a laptop. |
| `1920` | A 1920 × 1080 screen: a Full-HD desktop browser. More tickets per row, smaller text. | Large TVs, or you want more tickets visible. |
| `1280` | A 1280 × 720 screen: everything looks bigger. | Text is too small for the kitchen's viewing distance. |
| `2560`, `3840`, … | A bigger canvas: everything looks smaller and more tickets fit. | Large 4K screens viewed up close. |
| `auto` | Whatever the page's own `<meta name="viewport">` decides (usually 960 × 540 on TVs). | The KDS already adapts itself to TV WebViews. |

The fixed width is applied to pages inside the app's scope, including
single-page apps that add or change their viewport tag later. Zooming by staff
is disabled. The app ignores the TV's system font-size setting (`textZoom` is
fixed at 100%), so text sizes stay exactly as designed.

### Remote control, mouse and keyboard

The KDS web UI is built for TV-remote navigation, so by default the app
**passes the remote's keys straight to the page**. It doesn't intercept them.

| Input | What it does (default, `REMOTE_POINTER=false`) |
|---|---|
| **D-pad** (arrows) | Sent to the page as arrow keys. The KDS moves its own focus/selection. |
| **OK / Select / Enter** | Sent to the page as Enter. |
| **MENU** (or F5 on a keyboard) | Reloads the KDS. |
| **BACK** | Leaves fullscreen video, closes a pop-up, or goes back a page. On the first page, press **BACK twice** to exit, so the KDS isn't closed by accident. |
| **Mouse / touch** | Work normally, at the same time as the remote. |
| **Keyboard** | Goes to the page. |

**Pointer mode (optional).** Set `REMOTE_POINTER=true` in `kds.properties` for
sites that are *not* remote-friendly. The D-pad then moves an on-screen
pointer (hold to accelerate), **OK** clicks, pushing the pointer against a
screen edge scrolls, and **CH+/CH−** scroll a page. The pointer hides after
6 seconds without use.

### Start on boot

The app opens itself when the TV is switched on or restarted.

On **Android 10 and newer**, Android only lets an app start itself if it may
**Display over other apps**. On first launch the app explains this and opens
the right settings screen. Some TVs don't have that screen. On those, run once
from a computer:

```sh
adb shell appops set np.com.narayanipauroti.kds SYSTEM_ALERT_WINDOW allow
```

### Always-on reliability

- **Network loss:** the app shows its own offline screen and **retries every 10
  seconds** on its own. **Retry now** retries immediately.
- **Web page crash:** if the WebView rendering process crashes or is killed for
  memory, the app rebuilds the page instead of closing.
- **Login kept:** cookies are saved to disk regularly, so a power cut doesn't
  log staff out.

---

## Change the URL, scope or name

Everything you'd normally change is in **[`kds.properties`](kds.properties)**:

```properties
KDS_URL=https://pos.narayanipauroti.com.np/kds   # page the app opens (https only)
KDS_SCOPE=https://pos.narayanipauroti.com.np/    # pages that get notifications, camera, location…
VIEWPORT_WIDTH=1470                               # page layout width: 1470 = 13" MacBook Air look (5 tickets/row)
REMOTE_POINTER=false                              # false = remote keys go to the KDS page
APP_NAME=TapTill KDS                              # name on the TV home screen
VERSION_CODE=3                                    # raise for every release: 3, 4, 5…
VERSION_NAME=1.2.0                                # version shown to people
```

### The helper script

| Your computer | Run |
|---|---|
| **Windows** | double-click **`kds.cmd`**, or `.\kds.ps1` in PowerShell |
| **macOS / Linux** | `./kds.sh` |

With no arguments, the script is **interactive**. It shows the current values,
asks for new ones (Enter keeps a value), checks them, raises the version, saves
`kds.properties`, and builds the APK into `dist/`.

Common commands:

| Task | macOS / Linux | Windows |
|---|---|---|
| Show current settings | `./kds.sh --show` | `.\kds.ps1 -Show` |
| New URL, signed APK | `./kds.sh --url https://new.example.com/kds --keystore ~/keys/taptill-kds-release.jks` | `.\kds.ps1 -Url https://new.example.com/kds -Keystore C:\keys\taptill-kds-release.jks` |
| New URL and scope | `./kds.sh --url https://x.com/pos/kds --scope https://x.com/pos/` | `.\kds.ps1 -Url https://x.com/pos/kds -Scope https://x.com/pos/` |
| Only save settings (build on GitHub) | `./kds.sh --url https://x.com/kds --build none` | `.\kds.ps1 -Url https://x.com/kds -Build none` |
| Rename the app | `./kds.sh --name "My Kitchen"` | `.\kds.ps1 -Name "My Kitchen"` |
| Choose the version name | add `--version-name 2.0.0` | add `-VersionName 2.0.0` |
| Rebuild, same version | add `--no-bump` | add `-NoBump` |
| Test (debug) build | add `--build debug` | add `-Build debug` |
| Also store the APK in `releases/` | add `--save-release` | add `-SaveRelease` |
| Don't ask to confirm | add `-y` | add `-Yes` |

The script refuses invalid settings with a clear message: a URL that isn't
`https://`, a URL outside the scope, or a scope on another host. After a
successful run, commit `kds.properties`. The script prints the exact command.

### Editing kds.properties by hand

You can also edit the file directly, even in the GitHub web editor:

1. Change `KDS_URL` (and `KDS_SCOPE` if the host changes, or leave it empty).
2. **Raise `VERSION_CODE`** (e.g. `2` → `3`) and update `VERSION_NAME`.
3. Commit and push. GitHub Actions builds the new APK.

The build checks the same rules as the script and stops with a clear message if
something is wrong.

### What "scope" means

`KDS_SCOPE` works like `scope` in a PWA manifest. It marks the part of the site
that **is the app**:

- Pages **inside** the scope get the native features: notifications, location,
  camera/microphone, printing, sharing and downloads.
- Pages **outside** it (another site, or another path on the same host) still
  open inside the app, but get none of those features.
- MENU (reload) returns to `KDS_URL` if the current page is outside the scope.

Rules: the scope must be `https://`, on the **same host** as `KDS_URL`, and
`KDS_URL` must start with it. Leave it empty to use the whole site
(`https://host/`), which is the usual choice.

| Situation | `KDS_SCOPE` |
|---|---|
| The whole site is the POS (usual) | `https://pos.example.com/` or empty |
| The POS lives under one path of a shared host | `https://shared.example.com/pos/` |
| Match the PWA exactly | the `scope` from the site's `manifest.webmanifest` |

---

## Building the APK

### On GitHub (no tools needed)

Every push runs the **Build Android TV APK** workflow
([`.github/workflows/android.yml`](.github/workflows/android.yml)):

1. Open the **Actions** tab, then the latest **Build Android TV APK** run.
2. When it's green, download the **`taptill-kds-apk`** artifact (a zip).
3. Inside:
   - `release/TapTill-KDS-release.apk`: **install this one**. It's signed when the [signing secrets](#signing) are set.
   - `release/TapTill-KDS-release-unsigned.apk`: appears instead when the secrets are missing. Sign it before installing.
   - `debug/TapTill-KDS-debug.apk`: for testing only. It can't update a release install.

**One-off build for another URL** (e.g. a staging server) without changing any
file: *Actions → Build Android TV APK → Run workflow*, then fill in `kds_url`
and optionally `kds_scope`.

### On your own computer

Requirements: **JDK 17+** and the **Android SDK** (platform 35 and build-tools).
Installing [Android Studio](https://developer.android.com/studio) is the easiest
way to get them. Point the build at the SDK with `ANDROID_HOME`, or create a
`local.properties` file containing `sdk.dir=/path/to/Android/sdk`. On Windows,
use forward slashes: `sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`.

Easiest is the helper script (`./kds.sh` or `.\kds.ps1`). Or run Gradle
directly:

```sh
# Signed release
export KDS_KEYSTORE_PATH=/secure/taptill-kds-release.jks
export KDS_KEYSTORE_PASSWORD='…' KDS_KEY_ALIAS=taptill-kds KDS_KEY_PASSWORD='…'
./gradlew assembleRelease
# → app/build/outputs/apk/release/TapTill-KDS-release.apk

# Debug build
./gradlew assembleDebug
# → app/build/outputs/apk/debug/TapTill-KDS-debug.apk

# Override any setting for one build
./gradlew assembleRelease -PKDS_URL=https://staging.example.com/kds -PKDS_SCOPE=
```

Windows: use `gradlew.bat`, and set variables with `$env:NAME="value"` in PowerShell.

---

## Signing

Android only installs **signed** APKs, and it only installs an **update** if
it's signed with the **same key** as the installed app.

- Key file: `taptill-kds-release.jks` (alias `taptill-kds`), with passwords in
  `SIGNING-SECRETS.txt`. These were handed over separately and are **not** in
  this repo. Keep them in a password manager with a backup.
- Certificate SHA-256:
  `96:BD:AE:0C:AC:B1:A2:7F:E2:2A:82:F7:F7:4B:26:7E:39:FA:80:B3:BC:6D:AA:13:D2:15:FF:8C:96:47:05:0F`

**Sign automatically on GitHub (recommended).** Add four repository secrets
under *Settings → Secrets and variables → Actions*:

| Secret | Value |
|---|---|
| `KDS_KEYSTORE_BASE64` | the key as one line of text (`keystore.base64.txt`, or `base64 -w0 taptill-kds-release.jks`) |
| `KDS_KEYSTORE_PASSWORD` | keystore password |
| `KDS_KEY_ALIAS` | `taptill-kds` |
| `KDS_KEY_PASSWORD` | key password (same as the keystore password) |

**Sign an unsigned APK yourself.** This needs the Android SDK build-tools:

```sh
scripts/sign-apk.sh TapTill-KDS-release-unsigned.apk /secure/taptill-kds-release.jks
```

The script aligns, signs and verifies the APK and prints the signer certificate.
It must match the SHA-256 above.

---

## Installing and updating on TVs

**With adb** (fastest, and works for many TVs):

```sh
adb connect 192.168.1.50
adb install -r TapTill-KDS-release.apk      # -r = update, keeps login and settings
```

Several TVs at once:

```sh
for tv in 192.168.1.50 192.168.1.51 192.168.1.52; do
  adb connect "$tv" && adb -s "$tv:5555" install -r TapTill-KDS-release.apk
done
```

**Without a computer:** copy the APK to a USB drive, or use the *Downloader* or
*Send files to TV* apps, then open it on the TV and confirm **Install/Update**.

An update keeps the staff login, settings and permissions, **as long as** it
has the same app ID, the same signing key and a higher `VERSION_CODE`.

---

## First-run setup on each TV

After the first install (not needed for updates):

1. Open **TapTill KDS**.
2. Allow **notifications**, and location/camera/microphone if your KDS uses them.
3. Android 10+: allow **Display over other apps** when asked, so the KDS starts on boot.
   If the TV has no such screen, use the `adb shell appops …` command from [Start on boot](#start-on-boot).
4. Log in to the KDS.
5. Restart the TV once to check it opens the KDS automatically.
6. Optional: in *Settings → Apps → TapTill KDS → Notifications*, make sure **Kitchen alerts** is on.

---

## Project structure

```
.
├── kds.properties                ← URL, scope, app name, version (edit this)
├── kds.sh                        ← configure + build helper (macOS/Linux/Git Bash)
├── kds.ps1 / kds.cmd             ← configure + build helper (Windows)
├── docs/
│   └── CHANGE-URL-AND-REBUILD.md ← detailed step-by-step guide
├── releases/                     ← signed release APKs + SHA256SUMS
├── scripts/
│   └── sign-apk.sh               ← sign an unsigned APK
├── .github/workflows/android.yml ← CI: builds debug + release APKs on every push
├── app/
│   ├── build.gradle.kts          ← reads kds.properties, validates it, signing config
│   └── src/main/
│       ├── AndroidManifest.xml   ← permissions, TV launcher entry, boot receiver
│       ├── assets/kds_bridge.js  ← injected JS: Notification, print, share, downloads…
│       ├── java/np/com/narayanipauroti/kds/
│       │   ├── MainActivity.kt   ← WebView host, permissions, dialogs, pop-ups, errors
│       │   ├── JsBridge.kt       ← handles messages from kds_bridge.js
│       │   ├── KdsNotifier.kt    ← native notifications
│       │   ├── CursorController.kt ← remote-control pointer
│       │   ├── DownloadHelper.kt ← downloads to the Downloads folder
│       │   └── BootReceiver.kt   ← start on boot
│       └── res/                  ← icons, TV banner, splash logo, layouts, strings
├── build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*
└── README.md
```

---

## How it works (technical)

- **Single activity** (`MainActivity`) hosting a full-screen `WebView`. It's
  landscape, immersive (system bars hidden), `keepScreenOn`, and
  `singleTask`, so relaunching or clicking a notification returns to the same
  screen.
- **Configuration.** `app/build.gradle.kts` reads `kds.properties` (UTF-8),
  validates it, and exposes `BuildConfig.START_URL` and `BuildConfig.SCOPE`
  plus the `app_name` string. Any value can be overridden with `-PKEY=value`.
- **JavaScript bridge.** `assets/kds_bridge.js` is injected at document start
  (`WebViewCompat.addDocumentStartJavaScript`) for the scope's origin only.
  It defines `window.Notification`,
  `ServiceWorkerRegistration.prototype.showNotification`, `window.print`,
  `navigator.share`, `navigator.wakeLock` and blob-download handling, and
  talks to the app through `window.KdsBridge`
  (`WebViewCompat.addWebMessageListener`, origin-restricted). Older WebViews
  without these APIs fall back to `addJavascriptInterface` plus injection on
  page load. Every message is checked against the scope again on the native
  side.
- **Permissions.** Web permission requests (geolocation, camera, microphone)
  are mapped to Android runtime permissions. System permission dialogs are
  queued one at a time.
- **Screen size.** With `VIEWPORT_WIDTH` set, `assets/kds_viewport.js` is
  injected at document start for the scope's origin. It rewrites (or adds)
  `<meta name="viewport" content="width=N">` and keeps it that way with a
  `MutationObserver`. With `useWideViewPort` and `loadWithOverviewMode`, the
  WebView scales that layout to fill the screen.
- **Remote.** By default, key events go to the WebView unchanged, so the page's
  own keyboard/remote navigation handles them (DPAD_CENTER arrives as Enter).
  With `REMOTE_POINTER=true`, `CursorController` turns D-pad presses into a drawn
  pointer. OK sends touch events at the pointer, movement sends mouse hover
  events (so `:hover` styles work), and edge-push sends scroll-wheel events.
- **Resilience.** Main-frame load errors show the offline screen and schedule a
  retry. `onRenderProcessGone` recreates the WebView. Cookies are flushed on
  page load and on pause.
- **Start on boot.** `BootReceiver` listens for `BOOT_COMPLETED` (plus vendor
  quick-boot actions) and launches the activity.
- **Build.** AGP 8.7, Kotlin 2.0, compile/target SDK 35, min SDK 21. Release
  builds are minified with R8 (APK about 0.3 MB). Dependencies:
  `androidx.core`, `androidx.webkit`.

---

## Security model

- **HTTPS only.** The network security config blocks plain `http://`. The
  build refuses a non-https `KDS_URL`.
- **Scoped privileges.** Native features (notifications, location, camera,
  microphone, print, share, downloads) are only available to main-frame pages
  inside `KDS_SCOPE`. Pages from other origins or paths get none of them.
- **No hand-off to other apps.** Only `http(s)` navigation is allowed. Custom
  schemes are blocked.
- **No local file access** from web content (`allowFileAccess = false`).
- **SSL errors are never bypassed.** The load is cancelled and the error screen
  is shown.
- **User-installed CAs are trusted** (for venues with SSL-inspecting
  firewalls). Remove `<certificates src="user" />` from
  `app/src/main/res/xml/network_security_config.xml` if you don't want this.
- The signing key is never stored in the repo. CI decodes it into a temporary
  file only for the build and deletes it afterwards.

---

## Known limitations

- **No Web Push while the app is closed.** WebView doesn't support the Push
  API. Notifications work while the app is running, which on a KDS TV is all
  the time.
- **Notification pop-ups vary by TV.** Many Android TV launchers don't show
  pop-up notifications. They still arrive and play their sound, but may only be
  visible in the TV's notification panel. The in-page KDS alert and sound are
  the primary signal.
- **Location on TVs.** Most TVs have no GPS. Location comes from the network,
  if the TV provides a location service at all.
- **Pop-up windows** don't get the native features. They're meant for things
  like login or payment pages.
- **File picker / printing / sharing** need a matching app or service on the
  TV. The app shows a message if none exists.
- **Start on boot** needs *Display over other apps* on Android 10+ (see above).

---

## Troubleshooting

| Problem | Fix |
|---|---|
| "App not installed" / "package conflicts" | The APK is signed with a different key (e.g. the debug APK). Install the **release** APK signed with `taptill-kds-release.jks`. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Raise `VERSION_CODE` and rebuild. |
| "Cannot reach the kitchen display" | Check the TV's network. Open the URL in a browser on the same network. The app retries every 10 s. |
| "Secure connection failed" | Set the TV's date and time correctly. Check the server certificate. |
| Notifications / camera / location don't work | Check the permissions in *Settings → Apps → TapTill KDS*. Make sure the page is inside `KDS_SCOPE`. |
| Doesn't open after a restart | Allow *Display over other apps*, or run the `adb shell appops …` command. |
| Old page shown after an update | Press **MENU** to reload, or *Settings → Apps → TapTill KDS → Clear cache*. |
| Build fails with a `kds.properties:` message | Fix the value it names (https URL, URL inside scope, numeric `VERSION_CODE`). |
| `kds.ps1` blocked by Windows | Use `kds.cmd`, or `powershell -ExecutionPolicy Bypass -File .\kds.ps1`. |

More in [`docs/CHANGE-URL-AND-REBUILD.md`](docs/CHANGE-URL-AND-REBUILD.md#8-troubleshooting).

---

## Rules you must not break

1. **Never change the app ID** `np.com.narayanipauroti.kds`. A new ID makes a
   separate app: a second icon, a lost login, and permissions and auto-start
   to set up again on every TV.
2. **Always sign releases with `taptill-kds-release.jks`.** No other key can
   update the installed app. If the key is lost, every TV needs an uninstall
   and a reinstall.
3. **Always raise `VERSION_CODE`** for a release that installs over an older one.
4. **Never commit the keystore or its passwords.** (`.gitignore` blocks `*.jks`
   and `*.keystore`.)

---

## FAQ

**Do I need to rebuild the app when the KDS website changes?**
No. The app always loads the live site, so website updates show up
immediately (press MENU to reload). Rebuild only when the **address**, scope,
name or icons change.

**The KDS moved to a new path on the same domain. Do I need a new APK?**
Not necessarily. If the old path redirects to the new one, the app follows the
redirect. Rebuild when you want the app to open the new path directly, or when
the **domain** changes.

**Can I run a different KDS URL on some TVs?**
Yes. Build with a different `KDS_URL` (e.g. *Run workflow* with `kds_url`). But
it's the same app ID, so each TV can only have one of them installed at a time.

**Can staff exit the app?**
Only by pressing BACK twice on the first page, or with the HOME button. The app
reopens automatically at the next boot.

**How big is the app?**
About 0.3 MB. It uses the TV's built-in WebView, so there's no bundled browser.
