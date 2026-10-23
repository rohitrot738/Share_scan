#!/usr/bin/env bash
# Temporary review helper: what tapping the app's alerts opens, with the app on screen, in the background and closed.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/*.txt
LOG="$OUT/log.txt"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS  # no permission dialog over the screens

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" | tee -a "$LOG"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
dump() {
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break
    sleep 2
  done
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
}
# The centre of the first view whose resource id is $2 (mode "id") or whose text contains $2 (mode "text").
center() {
  dump
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
# The texts of every view whose resource id is $1, in screen order.
texts() {
  dump
  python3 - "$1" <<'PY'
import html, re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    rid = re.search(r'resource-id="([^"]*)"', n)
    if rid and rid.group(1).endswith(':id/' + sys.argv[1]):
        print(html.unescape(re.search(r' text="([^"]*)"', n).group(1)))
PY
}
tap() { local xy; xy=$(center id "$1") && adb shell input tap $xy || echo "could not find id $1" | tee -a "$LOG"; }
tap_text() { local xy; xy=$(center text "$1") && adb shell input tap $xy || echo "could not find text $1" | tee -a "$LOG"; }
notify() { adb shell am broadcast -n "$PKG/.NotifyTestReceiver" --es kind "$1" --es symbols "$2" > /dev/null; sleep 3; }
state() {
  echo "--- $1: pid $(adb shell pidof $PKG || echo none)" >> "$LOG"
  adb shell dumpsys activity activities | grep -E "topResumedActivity|ResumedActivity" | head -3 >> "$LOG"
}
# Opens the notification shade and taps the alert whose text contains $1.
tap_alert() {
  adb shell cmd statusbar expand-notifications
  sleep 2
  shot "$2-shade" 1
  tap_text "$1"
  sleep 6
  state "after tapping $1"
  shot "$2" 1
  adb shell cmd statusbar collapse
}

adb shell cmd uimode night yes
launch; sleep 6
tap startButton
sleep 30
for i in $(seq 1 120); do
  center text "दोबारा स्कैन करें" > /dev/null && { echo "scan finished after about $((i * 15)) seconds" | tee -a "$LOG"; break; }
  sleep 15
done
shot 01-results 4
mapfile -t SYMS < <(texts symbol)
echo "symbols on screen: ${SYMS[*]}" | tee -a "$LOG"
A=${SYMS[0]:-RELIANCE}; B=${SYMS[1]:-TCS}; C=${SYMS[2]:-INFY}

# 1. App on screen: a watchlist alert for the first stock.
notify watch "$A"
tap_alert "$A stop-loss" 02-onscreen-watch
adb shell input keyevent KEYCODE_BACK; sleep 2

# 2. App in the background (Home): a watchlist alert for the second stock.
adb shell input keyevent KEYCODE_HOME; sleep 3
notify watch "$B"
state "home, before tapping"
tap_alert "$B stop-loss" 03-background-watch
adb shell input keyevent KEYCODE_BACK; sleep 2

# 3. App closed (its process killed after the alert came): the third stock.
adb shell input keyevent KEYCODE_HOME; sleep 3
notify watch "$C"
sleep 5
adb shell am kill "$PKG"; sleep 3
state "killed, before tapping"
tap_alert "$C stop-loss" 04-closed-watch
adb shell input keyevent KEYCODE_BACK; sleep 2

# 4. A live scan's finds, three stocks, with the app in the background.
adb shell input keyevent KEYCODE_HOME; sleep 3
notify live "$A,$B,$C"
tap_alert "नए STRONG" 05-live-three

# 5. A live scan's find, one stock, app in the background.
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 3
notify live "$B"
tap_alert "नए STRONG" 06-live-one

adb logcat -d -s AndroidRuntime:E ActivityTaskManager:W ActivityTaskManager:I | grep -iE "share_scan|FATAL|Exception" | tail -40 >> "$LOG"
