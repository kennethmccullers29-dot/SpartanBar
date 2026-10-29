# SpartanBar

A **Windows 10 Mobile status bar and Action Center for Android**, part of the Spartan W10M / WP8.1 recreation (pairs with [Spartan Launcher](https://github.com/kennethmccullers29-dot/SpartanLauncher)).

> Fan project. Not affiliated with, endorsed by, or sponsored by Microsoft. Windows and Windows 10 Mobile are trademarks of Microsoft Corporation.

## Features

- **W10M status bar overlay** with MDL2-style icons for signal, Wi-Fi, Bluetooth, battery, clock and more. Its color adapts per app and is transparent on the home screen.
- **Action Center**: pull down from the bar to open it.
  - It follows your finger, responds to flick speed and settles with W10M easing.
  - It shows notifications, a now-playing music card and an expandable quick actions grid.
- **Toggleable quick actions** that light up in your accent color when on and stay in sync with the system:
  - Toggle directly: Flashlight, Quiet hours (Do Not Disturb), Vibrate, Rotation lock, Brightness (25 / 50 / 75 / 100% / Auto), Location.
  - Open the right system panel: Wi-Fi, Bluetooth, Cellular data, Airplane mode, Battery saver, Mobile hotspot, Connect, Project and Camera.
  - Long-press any tile to open its settings.
- **W10M volume panel**: compact and expanded (Ringer + Notifications, Media + Apps, Vibrate), with MDL2 icons and animated levels.
- **Metro notification toasts** and live activity chips (music, timers, calls).
- **Smooth animations**: shared W10M curves, fling-aware panels, color crossfades and hardware layers.

## Install

1. Go to **[Releases](https://github.com/kennethmccullers29-dot/SpartanBar/releases)** and download `SpartanBar-v1.1.apk`.
2. Install it, then open **SpartanBar** and follow the setup.

Requires Android 8.0 (API 26) or newer.

**Package name:** `com.ken1dot.spartanbar`. v1.1 moved from `com.example.*` to this package, so it installs as a new app. Uninstall v1.0 first so you don't end up with two copies.

## Permissions and setup

**Required:**

- **Accessibility service**: Settings → Accessibility → Installed apps → **SpartanBar** → On. This draws the bar, Action Center and volume panel, and detects the foreground app.
- **Notification access**: notifications in the Action Center and toasts.

**Recommended for quick actions:**

- **Modify system settings**, for Rotation lock and Brightness. The app prompts for it, or grant it with adb:
  ```bash
  adb shell appops set com.ken1dot.spartanbar WRITE_SETTINGS allow
  ```
- **Do Not Disturb access**, for Quiet hours and Vibrate. The app prompts for it, or grant it with adb:
  ```bash
  adb shell cmd notification allow_dnd com.ken1dot.spartanbar
  ```
- **Write secure settings**, for the Location toggle. This one is adb-only:
  ```bash
  adb shell pm grant com.ken1dot.spartanbar android.permission.WRITE_SECURE_SETTINGS
  ```

**Optional:**

- Phone state and location permissions, for the carrier name and Wi-Fi network name.

## Build from source

```bash
git clone https://github.com/kennethmccullers29-dot/SpartanBar.git
cd SpartanBar
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You need JDK 17+ and the Android SDK, or open the project in Android Studio.

## Release signing

To build a signed release, create `../keys/keystore.properties` next to the repo folder. It holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Then run `./gradlew assembleRelease`. Without that file, release builds are unsigned.

## Credits

- Icons: MDL2-style vector icons by Scott Dorman (MIT); see `app/src/main/assets/mdl2-icons-LICENSE.txt`.
- Fonts: [Selawik](https://github.com/microsoft/Selawik) (SIL OFL).
- Inspired by Windows 10 Mobile.
