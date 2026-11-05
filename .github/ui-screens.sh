#!/usr/bin/env bash
# Temporary review helper: switches, chips and toggles in the flat look on Space Glass (Settings, Scan, Paper Trading, the trade
# window, the stock detail, Big Money) and glass on Galaxy; the long-term line and the R/S calculation; a paper
# position's full detail (from a seeded account: 10 RELIANCE bought at ₹1,300); the guide's new parts.
# Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/log.txt
LOG="$OUT/log.txt"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS  # no permission dialog over the screens

shot() { sleep "${2:-3}"; unstall; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" | tee -a "$LOG"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
# Brings the app back to the front should a Back have left it.
front() {
  adb shell dumpsys activity activities | grep -qE "ResumedActivity.*$PKG" && return
  echo "app was not in front" | tee -a "$LOG"
  adb shell am start -n "$PKG/.MainActivity" > /dev/null
  sleep 4
}
# A failed dump leaves an empty file, never the last screen's.
dump() {
  adb shell rm -f /sdcard/ui.xml
  : > /tmp/ui.xml
  for attempt in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; then
      adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
      return 0
    fi
    sleep 2
  done
  echo "ui dump failed" | tee -a "$LOG"
}
# What the emulator is busy with, and why anything stopped responding.
diag() {
  echo "--- $1" >> "$LOG"
  adb shell top -b -n 1 -m 8 >> "$LOG" 2>&1
  adb logcat -d -s ActivityManager:E | grep -A 14 "ANR in" | head -60 >> "$LOG"
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
    row = rid and rid.group(1).split(':id/')[-1] in ('rowSymbol', 'symbol', 'accSymbol', 'bentoSymbol', 'swipeSymbol')
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and want in text) \
            or (mode == 'exact' and text == want) or (mode == 'row' and row and text == want):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        if y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
all_texts() {
  dump
  python3 - <<'PY'
import html, re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r' text="([^"]*)"', xml):
    t = html.unescape(m.group(1)).strip()
    if t:
        print(t)
PY
}
# The emulator's own apps can stall ("Pixel Launcher isn't responding"); close them so the dialog leaves the screen.
unstall() {
  dump
  grep -q "t responding" /tmp/ui.xml || return 0
  echo "closed a not-responding app" | tee -a "$LOG"
  local xy; xy=$(center text "Close app") && adb shell input tap $xy
  sleep 3
}
# Long-presses the lines at the given places (0 = the first line on screen), which puts them on the watchlist.
star_rows() {
  dump
  python3 - "$@" <<'PY' | while read -r x y; do adb shell input swipe "$x" "$y" "$x" "$y" 900 < /dev/null; sleep 1; done
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
want = {int(a) for a in sys.argv[1:]}
rows = re.findall(r'<node [^>]*resource-id="[^"]*:id/rowSymbol"[^>]*>', xml)
for i, node in enumerate(rows):
    if i in want:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}
tap_exact() { unstall; local xy; xy=$(center exact "$1") && adb shell input tap $xy || echo "could not find exactly $1" | tee -a "$LOG"; }
tap() { unstall; local xy; xy=$(center id "$1") && adb shell input tap $xy || echo "could not find id $1" | tee -a "$LOG"; }
tap_text() { unstall; local xy; xy=$(center text "$1") && adb shell input tap $xy || echo "could not find text $1" | tee -a "$LOG"; }

# Changes one preference with the app stopped: setpref NAME string|boolean VALUE ("-" removes it).
setpref() {
  adb shell am force-stop "$PKG"
  adb exec-out run-as "$PKG" cat shared_prefs/scan.xml > /tmp/p.xml
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys
name, kind, value = sys.argv[1:]
xml = open('/tmp/p.xml', encoding='utf-8').read()
xml = re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)' % re.escape(name), '', xml)
if value != '-':
    line = f'<string name="{name}">{value}</string>' if kind == 'string' else f'<{kind} name="{name}" value="{value}" />'
    xml = xml.replace('</map>', f'    {line}\n</map>')
open('/tmp/p.xml', 'w', encoding='utf-8').write(xml)
PY
  adb push /tmp/p.xml /data/local/tmp/p.xml > /dev/null
  adb shell run-as "$PKG" cp /data/local/tmp/p.xml shared_prefs/scan.xml
  echo "pref $1=$3" | tee -a "$LOG"
}
relaunch() { launch; sleep "${1:-8}"; }
# Opens the search and types $1, then opens the line of the stock $1.
open_stock() {
  front
  center id searchClose > /dev/null && tap searchClose && sleep 2  # a search left open from the stock before
  tap "$SEARCH"
  sleep 2
  adb shell input text "$1"
  sleep 3
  unstall
  local xy; xy=$(center row "$1") && adb shell input tap $xy || echo "could not find the line $1" | tee -a "$LOG"
}

cat > /tmp/seed.json <<'JSON'
{"next":2,"charges":15.420938,"at":1793763000000,"v":1,"start":1000000,"trades":[],"orders":[{"st":"FILLED","side":"BUY","d":"2026-11-04","src":"MANUAL","ch":15.420938,"trg":false,"fp":1300,"type":"MARKET","done":1793766720000,"seen":false,"p":"CNC","q":10,"s":"RELIANCE","at":1793766720000,"t":1450,"bse":false,"tk":"RELIANCE.NS","sl":1180,"id":1}],"positions":[{"d":"2026-11-04","src":"MANUAL","m":0,"wf":1793766900,"p":"CNC","q":10,"s":"RELIANCE","avg":1300,"at":1793766720000,"t":1450,"bse":false,"tk":"RELIANCE.NS","sl":1180,"ec":15.420938000000001}],"cash":986984.579062,"equity":[],"quotes":{"RELIANCE":[1350,1793786100]}}
JSON
python3 - <<'PY'
from xml.sax.saxutils import escape
book = open('/tmp/seed.json', encoding='utf-8').read().strip()
open('/tmp/scan.xml', 'w', encoding='utf-8').write(
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
    '    <string name="theme">galaxy</string>\n'
    '    <string name="background">space_glass</string>\n'
    f'    <string name="paper_book">{escape(book, {chr(34): "&quot;"})}</string>\n'
    "</map>\n")
PY
adb shell run-as "$PKG" mkdir -p shared_prefs
adb push /tmp/scan.xml /data/local/tmp/scan.xml
adb shell run-as "$PKG" cp /data/local/tmp/scan.xml shared_prefs/scan.xml

adb shell cmd uimode night yes
adb shell input keyevent KEYCODE_HOME
sleep 20
launch; sleep 10
echo "IST now: $(TZ=Asia/Kolkata date '+%F %H:%M')" | tee -a "$LOG"

shot 01-list 6

SEARCH=compactSearch
# Inside a sheet only upward drags: a downward one at the top would close the sheet.
swipe_up() { adb shell input swipe 540 1700 540 800 450; sleep 1; }
# Back closes the keyboard only while it shows; otherwise it would close the sheet under it.
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q 'mInputShown=true'; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
}
# Scrolls up until the view with id $1 is on screen, then taps it.
tap_scroll() {
  for k in 1 2 3 4 5 6 7 8 9 10 11 12; do
    if center id "$1" > /dev/null; then tap "$1"; return 0; fi
    swipe_up
  done
  echo "never reached id $1" | tee -a "$LOG"
}
show_scroll() {
  for k in 1 2 3 4 5 6 7 8 9 10 11 12; do
    center id "$1" > /dev/null && return 0
    swipe_up
  done
  echo "never reached id $1" | tee -a "$LOG"
}
# Back to the home list: closes sheets and the search, the app relaunched if Back left it.
home() {
  for k in 1 2 3 4; do
    center id statusPill > /dev/null && center id compactMenu > /dev/null && ! center id sheetClose > /dev/null && break
    adb shell input keyevent KEYCODE_BACK
    sleep 2
    front
  done
  center id searchClose > /dev/null && tap searchClose && sleep 2
  hide_keyboard
}



# Scrolls up until text $1 is on screen.
find_text() {
  for k in 1 2 3 4 5 6 7 8 9 10 11 12 13 14; do
    center text "$1" > /dev/null && return 0
    swipe_up
  done
  echo "never reached text $1" | tee -a "$LOG"
  return 1
}

# Fills the order form's stock: typing the whole symbol picks it; a tap on the title closes the suggestions.
order_stock() {
  tap orderStock
  sleep 1
  adb shell input text "$1"
  sleep 3
  hide_keyboard
  tap orderTitle
  sleep 8
}

# The switches on Space Glass: Settings, Scan and Filters; then Settings on Galaxy, which keeps the glass.
home
tap compactMenu
sleep 2
tap_exact "सेटिंग्स"
shot 20-settings 3
swipe_up
shot 21-settings-more 1
home
tap compactMenu
sleep 2
tap_exact "स्कैन"
shot 22-scan 3
swipe_up
shot 23-scan-more 1
home
tap filterChip
sleep 2
find_text "quality companies"
shot 24-filters-switch 1
home
setpref background string galaxy
relaunch 12
tap menuButton
sleep 2
tap_exact "सेटिंग्स"
shot 30-galaxy-settings 3
echo "done" | tee -a "$LOG"
