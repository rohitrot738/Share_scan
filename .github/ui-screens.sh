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
now() { date +%s.%N; }
since() { python3 -c "print(round($(now) - $1, 1))"; }
# The running scan's notification text as Android holds it (not the shade's drawing of it).
scan_text() { adb shell dumpsys notification --noredact | grep -ioE "android.text=String \([^)]*stocks[^)]*\)" | head -1; }

# 1. The user's scan with the app sent Home right after SCAN; its notification's text every 5 seconds until it ends.
tap startButton
sleep 4
adb shell input keyevent KEYCODE_HOME
T=$(now)
for i in $(seq 1 120); do
  sleep 5
  echo "+$(since "$T")s $(scan_text)" >> "$LOG"
  adb shell dumpsys notification --noredact | grep -q "top candidates" && break
done
echo "scan ended out of sight after $(since "$T")s" | tee -a "$LOG"
shade; shot 01-scan-done-shade 1
adb shell cmd statusbar collapse
adb shell run-as "$PKG" ls -l files >> "$LOG" 2>&1

# 2. A fresh start (process killed): what shows while the kept scan is read, and how long it takes.
adb shell am kill "$PKG"; sleep 2
echo "pid after kill: $(adb shell pidof $PKG || echo none)" >> "$LOG"
adb shell am start -n "$PKG/.MainActivity" > /dev/null
T=$(now)
shot 02-fresh-start-2s 2
for i in $(seq 1 60); do
  center text "दोबारा स्कैन करें" > /dev/null && break
  sleep 1
done
echo "fresh start: list in after $(since "$T")s" | tee -a "$LOG"
shot 03-fresh-start-list 0
mapfile -t SYMS < <(texts symbol)
A=${SYMS[1]:-RELIANCE}
echo "second stock: $A" | tee -a "$LOG"

# 3. A watchlist alert tapped with the app's process killed: how long until its stock opens.
adb shell input keyevent KEYCODE_HOME; sleep 3
notify watch "$A"
sleep 4
adb shell am kill "$PKG"; sleep 3
echo "pid after kill: $(adb shell pidof $PKG || echo none)" >> "$LOG"
shade
tap_text "$A stop-loss"
T=$(now)
for i in $(seq 1 60); do
  center id detailSymbol > /dev/null && break
  sleep 1
done
echo "closed-app alert: $A opened after $(since "$T")s" | tee -a "$LOG"
shot 04-closed-tap-opened 0
adb shell input keyevent KEYCODE_BACK; sleep 3
shot 05-closed-tap-list 0
