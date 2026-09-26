# Changing the URL and rebuilding TapTill KDS

This guide covers changing the web address, name or version of the TapTill KDS
Android TV app, building a new signed APK, and updating TVs that already have
the app installed.

**Short version:** edit `kds.properties`, raise `VERSION_CODE`, push, and
download the signed APK from GitHub Actions.

---

## Contents

1. [What you can change, and where](#1-what-you-can-change-and-where)
2. [Before you start (one-time setup)](#2-before-you-start-one-time-setup)
3. [Change the URL: step by step](#3-change-the-url-step-by-step)
4. [Build the APK](#4-build-the-apk)
   - [Option A: GitHub Actions (recommended, nothing to install)](#option-a-github-actions-recommended-nothing-to-install)
   - [Option B: One-off build for another URL without editing files](#option-b-one-off-build-for-another-url-without-editing-files)
   - [Option C: Build on your own computer](#option-c-build-on-your-own-computer)
5. [Install or update the app on the TVs](#5-install-or-update-the-app-on-the-tvs)
6. [Save the release in the repo (optional)](#6-save-the-release-in-the-repo-optional)
7. [Checklist](#7-checklist)
8. [Troubleshooting](#8-troubleshooting)
9. [Rules you must not break](#9-rules-you-must-not-break)

---

## 1. What you can change, and where

Everything you normally need is in **one file at the root of the repo:
`kds.properties`**.

```properties
KDS_URL=https://pos.narayanipauroti.com.np/kds
APP_NAME=TapTill KDS
VERSION_CODE=2
VERSION_NAME=1.1.0
```

| Setting | What it does | Rules |
|---|---|---|
| `KDS_URL` | The page the app opens on start, after a reload (MENU key), and when it recovers from being offline. | Must be a full `https://` address. `http://` is refused by the build. |
| `APP_NAME` | Name under the icon on the TV home screen, in *Settings → Apps*, in notifications and in dialog titles. | Any text. Apostrophes and quotes are fine. |
| `VERSION_CODE` | Internal version number Android compares when updating. | Whole number. **Must be higher than the version installed on the TVs**, or the update is refused. |
| `VERSION_NAME` | Version shown to people (e.g. in *Settings → Apps*). | Any text, e.g. `1.2.0`. |

### What changes automatically with the URL

Everything below follows `KDS_URL`, so you never need to touch code:

- The start page, the MENU reload, and the automatic retry after the network drops.
- The **trusted site**. Notifications, location, camera, microphone, printing,
  sharing and downloads only work for pages on the **same host** as `KDS_URL`.
  Moving the KDS to a new domain moves these permissions with it.
- The rule that pages on other hosts can still open inside the app but get none
  of those extra features.

### What does *not* change with the URL

| Item | Where it lives | Notes |
|---|---|---|
| App icon, TV banner, splash logo | `app/src/main/res/mipmap-*`, `drawable-*/ic_launcher_foreground.png`, `drawable-xhdpi/banner.png`, `drawable-nodpi/app_logo.png` | Only change these if the brand changes. See [Changing the icons](#changing-the-icons). |
| Brand colours | `app/src/main/res/values/colors.xml` | `brand` = accent colour, `icon_background` = adaptive-icon background. |
| App ID `np.com.narayanipauroti.kds` | `app/build.gradle.kts` | **Never change it** (see [section 9](#9-rules-you-must-not-break)). |
| Signing key | `taptill-kds-release.jks` (kept outside the repo) | **Always use the same key.** |

---

## 2. Before you start (one-time setup)

### 2.1 Keep the signing key safe

You received these files when the app was first set up:

- `taptill-kds-release.jks`: the signing key
- `SIGNING-SECRETS.txt`: its passwords and alias (`taptill-kds`)
- `keystore.base64.txt`: the same key as text, for GitHub

Store them in a password manager or another secure place with a backup.
**If the key is lost, installed TVs can never be updated.** You would have to
uninstall the app from every TV, which also loses the saved login, and install
a new one.

The key must **never** be committed to the repo. `.gitignore` already blocks
`*.jks` and `*.keystore` files.

### 2.2 Add the signing key to GitHub (recommended)

This makes every GitHub build produce a ready-to-install **signed** APK.

1. Open the repository on GitHub: **susanadhikary/TWA**.
2. Go to **Settings → Secrets and variables → Actions**.
3. Click **New repository secret** and add these four:

   | Name | Value |
   |---|---|
   | `KDS_KEYSTORE_BASE64` | the whole single line from `keystore.base64.txt` |
   | `KDS_KEYSTORE_PASSWORD` | from `SIGNING-SECRETS.txt` |
   | `KDS_KEY_ALIAS` | `taptill-kds` |
   | `KDS_KEY_PASSWORD` | from `SIGNING-SECRETS.txt` (same as the keystore password) |

You only do this once. Without these secrets, GitHub still builds, but the
release APK is **unsigned**. You'd then have to sign it yourself
([Option C, step 4](#step-4--sign-if-you-built-unsigned)).

---

## 3. Change the URL: step by step

### Step 1: Check the new address

Open the new URL in a browser on a computer:

- It loads the KDS correctly.
- It uses **https** with a valid certificate (no browser warning).
- Staff can log in.

### Step 2: Edit `kds.properties`

**On GitHub, no tools needed:**

1. Open `kds.properties` in the repo and click the pencil (Edit) icon.
2. Change `KDS_URL`, for example:
   ```properties
   KDS_URL=https://kds.newdomain.com/kitchen
   ```
3. Raise the version, for example from:
   ```properties
   VERSION_CODE=2
   VERSION_NAME=1.1.0
   ```
   to:
   ```properties
   VERSION_CODE=3
   VERSION_NAME=1.2.0
   ```
4. Click **Commit changes** and commit to the branch you build from.

**Or on your computer:**

```sh
git pull
# edit kds.properties with any text editor
git commit -am "Point KDS at https://kds.newdomain.com/kitchen, version 1.2.0"
git push
```

### Step 3: Build

Pushing the change starts a GitHub build automatically. See [section 4](#4-build-the-apk).

### Step 4: Update the TVs

See [section 5](#5-install-or-update-the-app-on-the-tvs).

> **Tip:** if the KDS just moves to a different *path* on the same site (for
> example `/kds` → `/kitchen`), you can also leave the app alone and have the
> website redirect the old path to the new one. Redirects are followed
> automatically and nothing needs rebuilding. Changing the *domain* always
> needs a rebuild, because the trusted-site permissions are tied to it.

---

## 4. Build the APK

### Option A: GitHub Actions (recommended, nothing to install)

1. Push your change (or open **Actions → Build Android TV APK → Run workflow**).
2. Open the **Actions** tab on GitHub and click the newest **Build Android TV APK** run.
3. Wait for the green tick (about 2–3 minutes).
4. At the bottom of the run page, under **Artifacts**, download **`taptill-kds-apk`** (a zip).
5. Unzip it. Inside:
   - `release/TapTill-KDS-release.apk`: **the file to install** (signed, if you set up the secrets).
   - `release/TapTill-KDS-release-unsigned.apk`: appears instead if the secrets are missing. Sign it first (see Option C, step 4).
   - `debug/TapTill-KDS-debug.apk`: for testing only. It's signed with a throwaway debug key and **cannot** update a release install.

If the run is red, click it and open the failed step. Configuration mistakes
show a clear message such as
`kds.properties: KDS_URL must be a full https:// address`.

### Option B: One-off build for another URL without editing files

Useful for trying a staging server, or for a second restaurant, without
changing `kds.properties`:

1. Open **Actions → Build Android TV APK**.
2. Click **Run workflow**.
3. Fill **kds_url** with the address, e.g. `https://staging.example.com/kds`.
4. Click **Run workflow** and download the artifact as in Option A.

This build has the same app ID and version as `kds.properties`. Installing it
on a TV **replaces** the normal app on that TV. It is not a second app side by
side.

### Option C: Build on your own computer

#### Step 1: Install the tools (once)

- **JDK 17 or newer**, e.g. [Temurin](https://adoptium.net).
- **Android SDK** with *Platform 35* and *Build-Tools*. Installing
  [Android Studio](https://developer.android.com/studio) is the simplest route.
  Then set the SDK location:
  - create `local.properties` in the repo root containing
    `sdk.dir=/path/to/Android/sdk`, **or**
  - set the environment variable `ANDROID_HOME=/path/to/Android/sdk`.

#### Step 2: Get the code

```sh
git clone https://github.com/susanadhikary/TWA.git
cd TWA
```

#### Step 3: Build

**Signed release (recommended):**

macOS / Linux:
```sh
export KDS_KEYSTORE_PATH=/secure/place/taptill-kds-release.jks
export KDS_KEYSTORE_PASSWORD='password-from-SIGNING-SECRETS'
export KDS_KEY_ALIAS=taptill-kds
export KDS_KEY_PASSWORD='password-from-SIGNING-SECRETS'
./gradlew assembleRelease
```

Windows (PowerShell):
```powershell
$env:KDS_KEYSTORE_PATH="C:\secure\taptill-kds-release.jks"
$env:KDS_KEYSTORE_PASSWORD="password-from-SIGNING-SECRETS"
$env:KDS_KEY_ALIAS="taptill-kds"
$env:KDS_KEY_PASSWORD="password-from-SIGNING-SECRETS"
.\gradlew.bat assembleRelease
```

Result: `app/build/outputs/apk/release/TapTill-KDS-release.apk`

**Build for a different URL without editing `kds.properties`:**
```sh
./gradlew assembleRelease -PKDS_URL=https://staging.example.com/kds
```
Any setting can be overridden the same way: `-PAPP_NAME=...`, `-PVERSION_CODE=...`, `-PVERSION_NAME=...`.

**Quick test build (debug):**
```sh
./gradlew assembleDebug
# app/build/outputs/apk/debug/TapTill-KDS-debug.apk
```

#### Step 4: Sign (if you built unsigned)

If you built without the `KDS_KEYSTORE_*` variables, or downloaded
`TapTill-KDS-release-unsigned.apk` from GitHub, sign it with the helper script.
It needs the Android SDK build-tools:

```sh
scripts/sign-apk.sh TapTill-KDS-release-unsigned.apk /secure/place/taptill-kds-release.jks
```

It asks for the keystore password, then aligns, signs and verifies the APK and
prints its SHA-256. Check that the certificate line shows:

```
Signer #1 certificate SHA-256 digest: 96bdae0cacb1a27fe22a82f7f74b267e39fa80b3bc6daa13d215ff8c9647050f
```

If the digest is different, the APK was signed with the wrong key and **won't
update** existing installs.

---

## 5. Install or update the app on the TVs

Updating keeps the staff login, settings and permissions, **as long as** the
new APK has the same app ID, the same signing key, and a higher `VERSION_CODE`.

### With adb (fastest for several TVs)

One-time, on each TV: *Settings → Device Preferences → About*, click **Build**
7 times. Then in *Developer options*, turn on **USB debugging** or **Network
debugging**.

```sh
adb connect 192.168.1.50            # the TV's IP address
adb install -r TapTill-KDS-release.apk
```

`-r` means "replace", which updates while keeping the app's data. To update
several TVs:

```sh
for tv in 192.168.1.50 192.168.1.51 192.168.1.52; do
  adb connect "$tv" && adb -s "$tv:5555" install -r TapTill-KDS-release.apk
done
```

### Without a computer

Copy the APK to a USB drive, or use the *Downloader* or *Send files to TV*
apps. Then open it on the TV with a file manager and confirm **Update**.
Android asks once to allow installs from that app.

### After installing

- Open **TapTill KDS** and check the new page loads.
- A brand-new install asks for permissions (location, camera, microphone,
  notifications) and on Android 10+ for **Display over other apps**, which is
  needed to start on boot. Updates keep the permissions already granted.
- Restart the TV once to confirm it opens automatically.

---

## 6. Save the release in the repo (optional)

The repo keeps signed releases in `releases/`:

```sh
cp app/build/outputs/apk/release/TapTill-KDS-release.apk releases/TapTill-KDS-1.2.0-release.apk
cd releases
sha256sum TapTill-KDS-1.2.0-release.apk >> SHA256SUMS   # macOS: shasum -a 256 ... >> SHA256SUMS
cd ..
git add releases
git commit -m "Add signed TapTill KDS 1.2.0 release APK"
git push
```

Also update the "latest signed release" line in `README.md`. APKs are small
(about 0.3 MB), but every committed APK stays in git history forever. Only
commit real releases.

---

## 7. Checklist

- [ ] New URL opens and works in a normal browser, over **https**.
- [ ] `KDS_URL` updated in `kds.properties`.
- [ ] `VERSION_CODE` **increased** (e.g. 2 → 3) and `VERSION_NAME` updated.
- [ ] Change committed and pushed.
- [ ] GitHub Actions run is green.
- [ ] Downloaded `release/TapTill-KDS-release.apk` (not the debug or unsigned one).
- [ ] Installed with `adb install -r` (or as an update) on one TV and tested:
      page loads, login works, order alerts sound, remote pointer works.
- [ ] Rolled out to the remaining TVs.
- [ ] (Optional) Saved the APK in `releases/` and updated `SHA256SUMS`.

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Build fails: `KDS_URL must be a full https:// address` | URL is `http://`, has a typo, or is missing `https://`. | Use a full `https://...` address. The server needs a valid TLS certificate. |
| Build fails: `VERSION_CODE must be a positive whole number` | Letters or dots in `VERSION_CODE`. | Use a plain number like `3`. Put `1.2.0` in `VERSION_NAME` instead. |
| TV says **"App not installed"** or **"package conflicts with an existing package"** | APK signed with a different key (e.g. the debug APK, or a new key). | Install the **release** APK signed with `taptill-kds-release.jks`. Check the signer digest (section 4, Option C, step 4). |
| `adb` says `INSTALL_FAILED_VERSION_DOWNGRADE` | `VERSION_CODE` is not higher than the installed one. | Raise `VERSION_CODE` and rebuild. |
| `adb` says `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signing key differs from the installed app. | Use the original key. If truly lost: `adb uninstall np.com.narayanipauroti.kds` then install. This loses the saved login. |
| App shows **"Cannot reach the kitchen display"** | TV can't reach the URL, or the URL is wrong. | Open the URL in a browser on the same network. The app retries every 10 s. Press **Retry now** after fixing. |
| App shows **"Secure connection failed"** | Certificate problem, or the TV's date/time is wrong. | Fix the TV's date and time. Make sure the server's certificate is valid and complete. |
| Notifications / camera / location stopped working after a domain change | The page now comes from a host that isn't `KDS_URL`'s host (e.g. the site redirects to another domain). | Set `KDS_URL` to the final address the site ends up on, then rebuild. |
| App doesn't open after the TV restarts | *Display over other apps* not allowed (Android 10+). | Allow it in *Settings → Apps → Special app access*, or run `adb shell appops set np.com.narayanipauroti.kds SYSTEM_ALERT_WINDOW allow`. |
| GitHub run only has `release/TapTill-KDS-release-unsigned.apk` | Signing secrets not set. | Add the secrets (section 2.2), or sign it yourself (Option C, step 4). |
| Old page still shows after update | The site's cached service worker. | Press **MENU** on the remote to reload. If needed: *Settings → Apps → TapTill KDS → Clear cache*. Staff stay logged in. |

---

## 9. Rules you must not break

1. **Never change the app ID** (`np.com.narayanipauroti.kds` in
   `app/build.gradle.kts`). A new ID is a *different app*: TVs would get a
   second icon, lose the login, and need the permissions and auto-start set up
   again.
2. **Always sign releases with `taptill-kds-release.jks`.** A different key
   can't update the installed app.
3. **Always raise `VERSION_CODE`** for every release you install over an older one.
4. **Never commit the keystore or its passwords** to the repo.

---

## Changing the icons

The icons were generated from the site's `manifest.webmanifest` icons. If the
brand changes:

| File | Size | Purpose |
|---|---|---|
| `app/src/main/res/mipmap-mdpi/ic_launcher.png` … `mipmap-xxxhdpi/` | 48, 72, 96, 144, 192 px | Launcher icon for Android 7 and older |
| `app/src/main/res/drawable-mdpi/ic_launcher_foreground.png` … `xxxhdpi/` | 108, 162, 216, 324, 432 px | Adaptive icon foreground (Android 8+). Keep the logo inside the centre ~60%. Transparent background. |
| `app/src/main/res/values/colors.xml` → `icon_background` | colour | Adaptive icon background |
| `app/src/main/res/drawable-xhdpi/banner.png` | 640 × 360 px | Android TV home-screen banner |
| `app/src/main/res/drawable-nodpi/app_logo.png` | 320 × 320 px | Splash screen logo |
| `app/src/main/res/drawable/ic_notification.xml` | vector | Small white notification icon |

Replace the files with the same names and sizes, raise the version, and rebuild.
