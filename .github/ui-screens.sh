#!/usr/bin/env bash
# Temporary review helper: the main screen's header (app icon, version) and the menu, then the scan board's Clear scan button.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS  # no permission dialog over the screens

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
rm -f "$OUT"/*.png
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
# The centre of the first view whose resource id is $2 (mode "id") or whose text contains $2 (mode "text").
center() {
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break
    sleep 2
  done
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
  python3 - "$1" "$2" <<'PY'
import html, re, sys
mode, want = sys.argv[1], sys.argv[2]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    rid = re.search(r'resource-id="([^"]*)"', n)
    text = html.unescape(re.search(r' text="([^"]*)"', n).group(1)) if re.search(r' text="([^"]*)"', n) else ''
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and want in text):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        if y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
tap() { local xy; xy=$(center id "$1") && adb shell input tap $xy || echo "could not find id $1"; }
tap_text() { local xy; xy=$(center text "$1") && adb shell input tap $xy || echo "could not find text $1"; }
up() { adb shell input swipe 540 1900 540 1000 500; }
# Scrolls down (at most $2 swipes) until a view with text $1 is on screen.
scroll_to() {
  for i in $(seq 1 "${2:-12}"); do
    center text "$1" > /dev/null && return 0
    up; sleep 1
  done
  return 1
}

adb shell cmd uimode night yes
launch; sleep 6
shot 01-header 2
tap menuButton
shot 02-menu 3
adb shell input keyevent KEYCODE_BACK
sleep 2

tap startButton
# The scan board's button reads "दोबारा स्कैन करें" once the scan has finished.
sleep 30
for i in $(seq 1 120); do
  center text "दोबारा स्कैन करें" > /dev/null && { echo "scan finished after about $((i * 15)) seconds"; break; }
  sleep 15
done
shot 03-results 4

# The scan board's new "Clear scan" button: its question, the screen once cleared, and the app opened again afterwards.
tap clearScanButton
shot 04-clear-ask 2
tap button1
shot 05-cleared 3
launch; sleep 6
shot 06-reopened 2
