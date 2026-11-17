#!/usr/bin/env bash
# Temporary: the branch's debug build on an emulator, for the guide's pictures of Menu → सूचनाएँ and a check of its
# taps: a real scan, sample alerts in the app's own words, a real crash, then the Menu, the list, a card opening its
# share, the crash card, and "सब हटाएँ". Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=inbox-shots
mkdir -p "$OUT"
LOG="$OUT/log.txt"
: > "$LOG"
adb root > /dev/null 2>&1; sleep 3
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000

dump() {
  adb shell rm -f /sdcard/ui.xml
  : > /tmp/ui.xml
  for attempt in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; then adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml; return 0; fi
    sleep 2
  done
}
# The centre of the first (or with "last", the last) view whose id is $2 (mode id) or whose text contains $2 (mode text).
center() {
  dump
  python3 - "$1" "$2" "${3:-first}" <<'PY'
import html, re, sys
mode, want, which = sys.argv[1], sys.argv[2], sys.argv[3]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
found = []
for n in re.finditer(r'<node [^>]*>', xml):
    node = n.group(0)
    rid = re.search(r'resource-id="([^"]*)"', node)
    t = re.search(r' text="([^"]*)"', node)
    text = html.unescape(t.group(1)) if t else ''
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and want in text):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        if y2 > y1:
            found.append(((x1 + x2) // 2, (y1 + y2) // 2))
if found:
    x, y = found[-1] if which == 'last' else found[0]
    print(x, y)
else:
    sys.exit(1)
PY
}
tap() { local xy; xy=$(center "$@") && adb shell input tap $xy && echo "tapped $2" >> "$LOG" || echo "could not find $2" | tee -a "$LOG"; }
texts() {
  dump
  python3 - <<'PY'
import html, re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r' text="([^"]*)"', xml):
    t = html.unescape(m.group(1)).strip()
    if t:
        print("  | " + t.replace("\n", "\n  | "))
PY
}
shot() { sleep 2; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$LOG"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep "${1:-12}"; }
step() { echo "=== $1" | tee -a "$LOG"; }
crashes() { local c; c=$(adb logcat -b crash -d 2>&1 | grep -v '^--------- beginning'); [ -n "$c" ] && { echo "CRASH BUFFER:" >> "$LOG"; echo "$c" | head -40 >> "$LOG"; }; adb logcat -b crash -c; }
menu() { tap id menuButton > /dev/null 2>&1 || tap id compactMenu; sleep 3; }
inbox() { menu; tap text "सूचनाएँ"; sleep 4; }
back_home() { for k in 1 2 3; do adb shell input keyevent KEYCODE_BACK; sleep 2; done; adb shell am start -n "$PKG/.MainActivity" > /dev/null; sleep 3; }

adb install -r app-debug.apk | tee -a "$LOG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
launch 15

step "a real scan, so a card can open its share"
tap id startButton
for i in $(seq 1 120); do
  sleep 10
  if center text "दोबारा स्कैन करें" > /dev/null; then echo "scan done after ~$((i * 10))s" >> "$LOG"; break; fi
done
adb logcat -b crash -c

step "sample alerts in the app's own words, three since the list was last opened"
adb shell am force-stop "$PKG"
python3 - <<'PY'
import json, datetime
from zoneinfo import ZoneInfo
ist = ZoneInfo("Asia/Kolkata")
today = datetime.datetime.now(ist).date()
def at(day, hh, mm):
    return int(datetime.datetime(day.year, day.month, day.day, hh, mm, tzinfo=ist).timestamp() * 1000)
yesterday = today - datetime.timedelta(days=1)
entries = [
    {"at": at(today, 14, 58), "k": "LIVE", "t": "🚀 1 stocks का breakout", "x": "TANLA · score 91.6 · ₹698.80 · breakout • volume 4.67× ✓", "s": "TANLA", "o": "STOCK"},
    {"at": at(today, 13, 42), "k": "WATCH", "t": "🎯 PNB target 1 पूरा", "x": "भाव ₹140.20, target ₹139.85 पर पहुँचा • entry से +4.6%", "s": "PNB", "o": "STOCK"},
    {"at": at(today, 9, 5), "k": "NEWS", "t": "📰 SOUTHBANK: सामान्य सूचना", "x": "Intimation under Regulation 30 of SEBI (LODR)", "s": "SOUTHBANK", "o": "NEWS"},
    {"at": at(yesterday, 19, 31), "k": "BIG_MONEY", "t": "💰 " + yesterday.strftime("%d").lstrip("0") + " नव शाम: 14 शेयरों में चुपचाप खरीद", "x": "सबसे ज़्यादा: TANLA, PNB, APOLLO · Menu → बड़े पैसे की चाल", "o": "BIG_MONEY"},
    {"at": at(yesterday, 10, 20), "k": "PAPER", "t": "📝 Paper Trading", "x": "BUY 10 TANLA भरा ₹690.00 पर", "o": "PAPER"},
]
open("/tmp/notifications.json", "w", encoding="utf-8").write(json.dumps(entries, ensure_ascii=False))
open("/tmp/seen_at", "w").write(str(at(today, 12, 0)))
PY
uid=$(adb shell stat -c %u "/data/data/$PKG" | tr -d '\r')
adb push /tmp/notifications.json "/data/data/$PKG/files/notifications.json" > /dev/null
adb shell chown "$uid:$uid" "/data/data/$PKG/files/notifications.json"
adb shell chmod 600 "/data/data/$PKG/files/notifications.json"
f=/data/data/$PKG/shared_prefs/scan.xml
adb shell cat "$f" > /tmp/p.xml
python3 - "$(cat /tmp/seen_at)" <<'PY'
import re, sys
xml = open('/tmp/p.xml', encoding='utf-8').read()
xml = re.sub(r'\s*<long name="notifications_seen_at"[^>]*/>', '', xml)
xml = xml.replace('</map>', f'    <long name="notifications_seen_at" value="{sys.argv[1]}" />\n</map>')
open('/tmp/p.xml', 'w', encoding='utf-8').write(xml)
PY
adb push /tmp/p.xml "$f" > /dev/null
adb shell chown "$uid:$uid" "$f"; adb shell chmod 660 "$f"
adb shell restorecon -R "/data/data/$PKG/files" "/data/data/$PKG/shared_prefs"

step "a real crash, then a start: the window, kept in सूचनाएँ too"
launch 12
adb shell am crash "$PKG"; sleep 6
adb logcat -b crash -c
launch 12
texts | head -12 >> "$LOG"; shot 01-crash-window
adb shell input keyevent KEYCODE_BACK; sleep 2

step "the Menu, with the count of new ones"
menu
texts | head -14 >> "$LOG"; shot 02-menu

step "Menu → सूचनाएँ"
tap text "सूचनाएँ"; sleep 4
texts >> "$LOG"; shot 03-inbox
adb shell input swipe 540 1800 540 700 500; sleep 2
shot 04-inbox-more
crashes

step "a card opens its share"
adb shell input swipe 540 700 540 1800 500; sleep 2
tap text "TANLA · score"; sleep 8
texts | head -16 >> "$LOG"; shot 05-card-opens-share
crashes
back_home

step "the Menu again: nothing new now"
menu
texts | head -14 >> "$LOG"; shot 06-menu-seen
adb shell input keyevent KEYCODE_BACK; sleep 2

step "the crash card opens the report again"
inbox
tap text "पिछली बार app बंद हो गया था" last; sleep 3
texts | head -12 >> "$LOG"; shot 07-crash-again
adb shell input keyevent KEYCODE_BACK; sleep 2
crashes
back_home

step "सब हटाएँ, asked first"
inbox
for k in 1 2 3; do adb shell input swipe 540 1800 540 500 400; sleep 1; done
tap text "सब हटाएँ"; sleep 3
shot 08-clear-ask
tap text "सब हटाएँ" last; sleep 3
texts >> "$LOG"; shot 09-empty
crashes
echo "done" | tee -a "$LOG"
