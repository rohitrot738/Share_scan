#!/usr/bin/env bash
# Temporary: the branch's debug build on an emulator, ended three ways (a native crash, a Java crash, stuck until
# Android closes it), each followed by a fresh start to see the report window. Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=exit-check
mkdir -p "$OUT"
LOG="$OUT/log.txt"
: > "$LOG"
adb root > /dev/null 2>&1; sleep 3
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000

texts() {
  adb shell rm -f /sdcard/ui.xml
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
  python3 - <<'PY'
import html, re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r' text="([^"]*)"', xml):
    t = html.unescape(m.group(1)).strip()
    if t:
        print("  | " + t.replace("\n", "\n  | "))
PY
}
tap_text() {
  local xy
  xy=$(python3 - "$1" <<'PY'
import html, re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for n in re.finditer(r'<node [^>]*>', xml):
    node = n.group(0)
    t = re.search(r' text="([^"]*)"', node)
    if t and sys.argv[1] in html.unescape(t.group(1)):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  [ -n "$xy" ] && adb shell input tap $xy && echo "tapped $1" >> "$LOG"
}
shot() { sleep 2; adb exec-out screencap -p > "$OUT/$1.png"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep "${1:-12}"; }
step() { echo "=== $1" | tee -a "$LOG"; }
exits() { adb shell dumpsys activity exit-info "$PKG" | grep -E "reason=|description=|timestamp=" | head -12 >> "$LOG"; }

adb install -r app-debug.apk | tee -a "$LOG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
step "fresh start: no window expected"
launch 15
texts >> "$LOG"; shot 01-fresh

step "native crash (SIGSEGV), then a start: the window expected"
pid=$(adb shell pidof "$PKG" | tr -d '\r'); echo "pid $pid" >> "$LOG"
adb shell kill -11 "$pid"; sleep 8
adb logcat -b crash -d | grep -E "signal|Cmdline|pid:" | head -5 >> "$LOG"
exits
launch 12
texts >> "$LOG"; shot 02-after-native
adb shell input keyevent KEYCODE_BACK; sleep 2
step "another start: no window expected (already reported)"
launch 12
texts >> "$LOG"; shot 03-again

step "Java crash (am crash), then a start: the app's own error window expected, once"
adb shell am crash "$PKG"; sleep 6
launch 12
texts >> "$LOG"; shot 04-after-java
adb shell input keyevent KEYCODE_BACK; sleep 2
launch 12
texts >> "$LOG"; shot 05-again

step "stuck (process stopped, taps unanswered) until Android closes it, then a start: the window expected"
pid=$(adb shell pidof "$PKG" | tr -d '\r'); echo "pid $pid" >> "$LOG"
adb shell kill -STOP "$pid"
for i in 1 2 3 4; do adb shell input tap 540 1200; sleep 3; done
for i in $(seq 1 20); do
  sleep 3
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
  if grep -q "t responding" /tmp/ui.xml; then echo "ANR dialog after $((i * 3))s" >> "$LOG"; shot 06-anr-dialog; tap_text "Close app"; break; fi
done
sleep 6
adb shell pidof "$PKG" > /dev/null && { echo "still running; killed" >> "$LOG"; adb shell kill -CONT "$pid"; }
exits
launch 15
texts >> "$LOG"; shot 07-after-anr
echo "done" | tee -a "$LOG"
