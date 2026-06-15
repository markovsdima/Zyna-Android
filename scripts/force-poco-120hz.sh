#!/usr/bin/env sh
set -eu

MODE="${1:-recover}"
REFRESH_RATE="${REFRESH_RATE:-120}"
PREFERRED_REFRESH_RATE="${PREFERRED_REFRESH_RATE:-120.00001}"
DISPLAY_WIDTH="${DISPLAY_WIDTH:-1080}"
DISPLAY_HEIGHT="${DISPLAY_HEIGHT:-2392}"

if [ "${ADB:-}" != "" ]; then
  ADB_BIN="$ADB"
elif command -v adb >/dev/null 2>&1; then
  ADB_BIN="adb"
elif [ -x "$HOME/Library/Android/sdk/platform-tools/adb" ]; then
  ADB_BIN="$HOME/Library/Android/sdk/platform-tools/adb"
else
  echo "adb was not found. Set ADB=/path/to/adb or add adb to PATH." >&2
  exit 1
fi

run_adb() {
  echo "+ $ADB_BIN $*"
  "$ADB_BIN" "$@"
}

usage() {
  cat <<EOF
Usage:
  $0 [recover|--full|full]

Modes:
  recover  Restore miui_refresh_rate after HyperOS falls back to 60 Hz.
  --full   Apply the full POCO/HyperOS 120 Hz test setup first.

Optional environment:
  ADB=/path/to/adb
  REFRESH_RATE=120
  PREFERRED_REFRESH_RATE=120.00001
  DISPLAY_WIDTH=1080
  DISPLAY_HEIGHT=2392
EOF
}

case "$MODE" in
  recover)
    ;;
  --full|full)
    run_adb wait-for-device
    run_adb shell settings put system min_refresh_rate "$REFRESH_RATE"
    run_adb shell settings put system peak_refresh_rate "$REFRESH_RATE"
    run_adb shell settings put global user_preferred_refresh_rate "$PREFERRED_REFRESH_RATE"
    run_adb shell settings put global user_preferred_resolution_width "$DISPLAY_WIDTH"
    run_adb shell settings put global user_preferred_resolution_height "$DISPLAY_HEIGHT"
    run_adb shell device_config put display_manager peak_refresh_rate_default "$REFRESH_RATE"
    ;;
  -h|--help|help)
    usage
    exit 0
    ;;
  *)
    usage >&2
    exit 1
    ;;
esac

run_adb wait-for-device
run_adb shell am force-stop com.miui.powerkeeper
run_adb shell am force-stop com.xiaomi.joyose
run_adb shell settings put secure miui_refresh_rate "$REFRESH_RATE"

echo
echo "miui_refresh_rate:"
run_adb shell settings get secure miui_refresh_rate

echo
echo "Display refresh-rate fields:"
run_adb shell cmd display get-displays | tr ',' '\n' | grep -E 'mode [0-9]|renderFrameRate|refreshRateOverride' || true
