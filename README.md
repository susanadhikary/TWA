# Narayani KDS – Android TV app

An Android TV app that shows the kitchen display PWA at
**https://pos.narayanipauroti.com.np/kds** in full screen.

Android TV devices usually don't have Chrome installed, and a Trusted Web
Activity (TWA) needs Chrome to run. So this app uses the built-in Android
System WebView instead.

## Features

- Shows up on the Android TV home screen (Leanback launcher + banner). It also installs on phones, tablets and Fire TV.
- Full screen, landscape, and the screen stays on.
- Sound alerts can play without a tap (`mediaPlaybackRequiresUserGesture = false`).
- Keeps cookies and localStorage, so the KDS login stays saved.
- Shows an offline screen and retries automatically every 10 seconds when the network drops.
- Rebuilds the WebView if its renderer crashes, so the display keeps running.
- Remote keys: **MENU** reloads the page. **BACK** goes back in page history, and pressing BACK twice exits the app.

## Getting the APK

Every push runs the **Build Android TV APK** GitHub Actions workflow.
Open the run in the *Actions* tab and download the `narayani-kds-apk` artifact.

### Signed release build (optional)

To also get a signed release APK, add these repository secrets:

| Secret | Value |
| --- | --- |
| `KDS_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KDS_KEYSTORE_PASSWORD` | keystore password |
| `KDS_KEY_ALIAS` | key alias |
| `KDS_KEY_PASSWORD` | key password |

Create a keystore with:

```sh
keytool -genkeypair -v -keystore release.jks -alias kds -keyalg RSA -keysize 2048 -validity 10000
```

## Building locally

This needs JDK 17+ and the Android SDK (compile SDK 35).

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Installing on the TV

1. On the TV, go to *Settings → Device Preferences → About* and click *Build* 7 times. This turns on Developer options.
2. In *Developer options*, turn on **ADB debugging** (network debugging on newer TVs).
3. From your computer, run:
   ```sh
   adb connect <tv-ip-address>
   adb install -r app-debug.apk
   ```

You can also sideload the APK with a USB drive or the *Downloader* / *Send Files to TV* apps.

## Changing the URL

Edit `START_URL` in `app/build.gradle.kts`.
