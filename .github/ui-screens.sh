#!/usr/bin/env bash
# Temporary review helper: drives the debug APK on an emulator and saves screenshots to ui-screens/.
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

adb shell cmd uimode night no
launch; shot 01-idle-light 4
adb shell cmd uimode night yes
launch; shot 02-idle-dark 4

adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell input swipe 540 1900 540 400 300
shot 03-app-drawer 3

launch; sleep 3
tap startButton
shot 04-scan-starting-dark 6
shot 05-scanning-dark 45

# The start button is enabled again once the scan has finished or failed.
for i in $(seq 1 80); do
  flags=$(adb shell dumpsys activity top | grep 'id/startButton' | head -1 | awk '{print $2}')
  case "$flags" in ??E*) echo "scan finished after about $((i * 15)) more seconds"; break ;; esac
  sleep 15
done

shot 06-results-dark 3
adb shell input swipe 540 1700 540 500 400
shot 07-results-scrolled-dark 2
adb shell input swipe 540 600 540 1900 400
sleep 1
adb shell cmd uimode night no
shot 08-results-light 5
tap sortRvol
shot 09-sorted-rvol-light 3
