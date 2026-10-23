#!/usr/bin/env bash
# Temporary review helper: the scan and an update's download going on in the background, what tapping the app's
# alerts opens (app on screen, in the background, closed), and Menu → स्कैन's background permission.
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
# The centre of the first Button whose text is exactly $1 (a notification's action).
button() {
  dump
  python3 - "$1" <<'PY'
import html, re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    text = html.unescape(re.search(r' text="([^"]*)"', n).group(1)) if re.search(r' text="([^"]*)"', n) else ''
    if 'Button' in n and text.strip().upper() == sys.argv[1].upper():
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}
shade() { adb shell cmd statusbar expand-notifications; sleep 2; }
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

# 1. The user's scan, with the app sent Home right after SCAN: it should go on, shown in a notification.
tap startButton
sleep 4
adb shell input keyevent KEYCODE_HOME
sleep 20
adb shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground|foregroundId" >> "$LOG"
shade; shot 01-scan-progress 1
dump; grep -o 'text="[^"]*stocks • [0-9]*%"' /tmp/ui.xml | head -2 >> "$LOG"
adb shell cmd statusbar collapse
done_seen=no
for i in $(seq 1 60); do
  sleep 10
  shade
  if center text "स्कैन पूरा" > /dev/null; then done_seen=yes; break; fi
  dump; grep -o 'text="[^"]*stocks • [0-9]*%"' /tmp/ui.xml | head -1 >> "$LOG"
  adb shell cmd statusbar collapse
done
echo "scan done notification seen: $done_seen after about $((20 + i * 10)) seconds out of sight" | tee -a "$LOG"
shot 02-scan-done-shade 1
tap_text "स्कैन पूरा"
sleep 5
state "after tapping the scan's result"
shot 03-scan-done-opened 1
mapfile -t SYMS < <(texts symbol)
echo "symbols on screen: ${SYMS[*]}" | tee -a "$LOG"
A=${SYMS[0]:-RELIANCE}; B=${SYMS[1]:-TCS}

# 2. Alerts: app on screen, in the background, closed (process killed), a live scan's finds, a stock not in the list.
notify watch "$A"
tap_alert "$A stop-loss" 04-onscreen-watch
adb shell input keyevent KEYCODE_BACK; sleep 2

adb shell input keyevent KEYCODE_HOME; sleep 3
notify watch "$B"
tap_alert "$B stop-loss" 05-background-watch
adb shell input keyevent KEYCODE_BACK; sleep 2

adb shell input keyevent KEYCODE_HOME; sleep 3
notify watch "$A"
sleep 5
adb shell am kill "$PKG"; sleep 3
state "killed, before tapping"
tap_alert "$A stop-loss" 06-closed-watch
adb shell input keyevent KEYCODE_BACK; sleep 2

adb shell input keyevent KEYCODE_HOME; sleep 3
notify live "$A,$B"
tap_alert "नए STRONG" 07-live-two-tap
adb shell input keyevent KEYCODE_BACK; sleep 2

adb shell input keyevent KEYCODE_HOME; sleep 3
notify live "$A,$B"
shade; shot 08-live-two-buttons-shade 1
xy=$(button "$B") && adb shell input tap $xy || echo "no button $B" | tee -a "$LOG"
sleep 6; state "after the $B button"; shot 08-live-two-button-b 1
adb shell cmd statusbar collapse
adb shell input keyevent KEYCODE_BACK; sleep 2

adb shell input keyevent KEYCODE_HOME; sleep 3
notify watch "NOTREAL"
adb shell cmd statusbar expand-notifications; sleep 2
tap_text "NOTREAL stop-loss"
sleep 3; shot 09-missing-stock 0

# 3. Menu → स्कैन: the background permission line, Android's dialog, and the line once allowed.
tap menuButton; sleep 2
tap_text "live auto-scan"; sleep 3
shot 10-scan-sheet 1
tap_text "Background में चलने दें"; sleep 3
shot 11-battery-dialog 1
tap_text "Allow"; sleep 3
shot 12-scan-sheet-allowed 1
adb shell dumpsys deviceidle whitelist | grep -i share_scan >> "$LOG"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1

# 4. An update's download with the app out of sight: Android's download manager, then "install करने के लिए दबाएँ".
adb shell input keyevent KEYCODE_HOME; sleep 3
adb shell am broadcast -n "$PKG/.NotifyTestReceiver" --es kind update > /dev/null
sleep 6
shade; shot 13-update-downloading 1
adb shell cmd statusbar collapse
ready_seen=no
for i in $(seq 1 24); do
  sleep 5
  shade
  if center text "तैयार" > /dev/null; then ready_seen=yes; break; fi
  adb shell cmd statusbar collapse
done
echo "update ready notification seen: $ready_seen" | tee -a "$LOG"
shot 14-update-ready 1
adb shell ls -l "/data/data/$PKG/cache/updates/" >> "$LOG" 2>&1 || true
adb shell cmd statusbar collapse

adb logcat -d -s AndroidRuntime:E ActivityTaskManager:W ActivityTaskManager:I | grep -iE "share_scan|FATAL|Exception" | tail -40 >> "$LOG"
