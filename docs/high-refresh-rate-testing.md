# High refresh rate testing

This note documents the 120 Hz test setup used on a POCO / HyperOS device.
It is a device-side workaround for local profiling, not production app behavior.

## App behavior

Zyna requests the fastest display mode from `MainActivity` by setting the
window's preferred display mode and refresh rate on startup and resume.

Some OEM builds can still override the app request. On the tested POCO device,
HyperOS `com.miui.powerkeeper` rewrote `miui_refresh_rate` to `60`, so the app
kept rendering at 60 Hz until the device-side override below was applied.

## OEM whitelist observation

On the tested HyperOS build, the system setting `rt_pkg_white_list` contained
popular package names such as WhatsApp, YouTube, Chrome, Instagram, Gmail,
Snapchat, Telegram, X/Twitter, and Xiaomi launcher/search packages. Adding
`com.zyna.app` to that list did not make Zyna render at 120 Hz by itself.

This suggests HyperOS may combine private package allowlists with power/display
policy, but the exact behavior is OEM-specific. Treat it as a diagnostic clue,
not as a production mechanism.

## Prerequisites

- Enable Developer options.
- Enable USB debugging.
- Enable USB debugging (Security settings/options) on Xiaomi/POCO devices.
- Connect the device over USB and verify it is visible:

```sh
adb devices
```

## Force 120 Hz for testing

Run these commands from the development machine:

```sh
adb shell settings put system min_refresh_rate 120
adb shell settings put system peak_refresh_rate 120

adb shell settings put global user_preferred_refresh_rate 120.00001
adb shell settings put global user_preferred_resolution_width 1080
adb shell settings put global user_preferred_resolution_height 2392

adb shell device_config put display_manager peak_refresh_rate_default 120

adb shell am force-stop com.miui.powerkeeper
adb shell am force-stop com.xiaomi.joyose
adb shell settings put secure miui_refresh_rate 120
```

The width and height above match the tested POCO device. If the device has a
different panel resolution, get the current display details first:

```sh
adb shell cmd display get-displays
```

## Verify

```sh
adb shell settings get secure miui_refresh_rate
adb shell cmd display get-displays
```

Useful signs in `cmd display get-displays`:

- `renderFrameRate 120`
- `refreshRateOverride 120`
- the active mode is a 120 Hz mode

The system FPS overlay should also show 120 while Zyna is open.

## Recover after HyperOS reset

If the device falls back to 60 Hz while the other overrides are still set, it is
usually enough to stop the HyperOS power services and restore
`miui_refresh_rate`:

```sh
adb shell am force-stop com.miui.powerkeeper
adb shell am force-stop com.xiaomi.joyose
adb shell settings put secure miui_refresh_rate 120
```

This was observed when `miui_refresh_rate` returned to `60` while
`min_refresh_rate`, `peak_refresh_rate`, `user_preferred_refresh_rate`, and
`peak_refresh_rate_default` were still `120`.

## Caveats

- This can reset after reboot or after MIUI/HyperOS power services restart.
- It is only for profiling and local smoothness testing.
- Do not rely on these commands for production behavior.
- Do not commit manifest hacks such as `android:appCategory="game"` for this.

## Rollback

Either switch the display refresh setting back in system settings, or remove the
ADB overrides:

```sh
adb shell settings delete system min_refresh_rate
adb shell settings delete system peak_refresh_rate

adb shell settings delete global user_preferred_refresh_rate
adb shell settings delete global user_preferred_resolution_width
adb shell settings delete global user_preferred_resolution_height

adb shell device_config delete display_manager peak_refresh_rate_default
```

If HyperOS still reports a forced value, reboot the device or choose the desired
refresh-rate mode in system settings.
