#!/usr/bin/env bash
# Temporary review helper: scans on an emulator and saves the filter sheet and a stock's detail to ui-screens/.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
center() {
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break
    sleep 2
  done
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
  python3 - "$1" <<'PY'
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
m = re.search(r'resource-id="[^"]*:id/%s"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"' % sys.argv[1], xml)
if not m:
    sys.exit(1)
x1, y1, x2, y2 = map(int, m.groups())
print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}
tap() { local xy; xy=$(center "$1") && adb shell input tap $xy || echo "could not find $1"; }
up() { adb shell input swipe 540 1900 540 900 500; }

adb shell cmd uimode night yes
launch; sleep 4
tap startButton
shot 01-scanning 20

# The start button is enabled again once the scan has finished or failed.
for i in $(seq 1 100); do
  flags=$(adb shell dumpsys activity top | grep 'id/startButton' | head -1 | awk '{print $2}')
  case "$flags" in ??E*) echo "scan finished after about $((i * 15)) more seconds"; break ;; esac
  sleep 15
done
shot 02-results 4

# Filters: the sheet, then down to the RSI and MACD sliders.
tap filterChip
shot 03-filters-top 3
for n in 04 05 06 07; do up; shot "$n-filters" 2; done
adb shell input keyevent KEYCODE_BACK
sleep 2

# The top card's detail, top to bottom; RSI and MACD sit with the key numbers near the end.
tap symbol
shot 08-detail 4
for n in 09 10 11 12 13 14 15 16 17; do up; shot "$n-detail" 2; done
