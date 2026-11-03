#!/usr/bin/env bash
# Temporary review helper: the targets per horizon at the top of the stock detail and the app's own intraday picks
# (Menu -> aaj ke Intraday). Removed before merging.
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

cat > /tmp/scan.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="theme">galaxy</string>
    <string name="background">space_glass</string>
</map>
XML
adb shell run-as "$PKG" mkdir -p shared_prefs
adb push /tmp/scan.xml /data/local/tmp/scan.xml
adb shell run-as "$PKG" cp /data/local/tmp/scan.xml shared_prefs/scan.xml

adb shell cmd uimode night yes
adb shell input keyevent KEYCODE_HOME
sleep 20
launch; sleep 10
echo "IST now: $(TZ=Asia/Kolkata date '+%F %H:%M')" | tee -a "$LOG"

# A scan, from the pill's panel.
tap statusPill
sleep 3
tap startButton
sleep 10
adb shell input keyevent KEYCODE_BACK
for i in $(seq 1 150); do
  sleep 5
  unstall
  center text "100%" > /dev/null && break
done
echo "scan done after ~$((i * 5 + 10))s" | tee -a "$LOG"
adb shell am start -n "$PKG/.MainActivity" > /dev/null
shot 01-list 6

SEARCH=compactSearch
# Menu -> today's intraday first: the app's own BUY and SELL picks among the most traded stocks, and after 14:45 the
# day's calls with why each is no longer to be taken.
front
tap compactMenu
sleep 3
shot 10-menu 1
tap_text "आज के Intraday"
for i in $(seq 1 60); do
  sleep 3
  unstall
  center text "फिर से जाँचें" > /dev/null && break
done
echo "picks done after ~$((i * 3))s at IST $(TZ=Asia/Kolkata date +%H:%M)" | tee -a "$LOG"
shot 11-intraday-top 1
all_texts >> "$LOG"
adb shell input swipe 540 1900 540 700 500
shot 12-intraday-scrolled 2
adb shell input swipe 540 1900 540 700 500
shot 13-intraday-more 2
adb shell input swipe 540 1900 540 700 500
shot 14-intraday-end 2
all_texts >> "$LOG"
# A pick opens its stock: back to the menu, the list again (each stock's answer is kept a minute), tap the first call.
adb shell input keyevent KEYCODE_BACK
sleep 3
tap_text "आज के Intraday"
for i in $(seq 1 60); do
  sleep 2
  center text "फिर से जाँचें" > /dev/null && break
done
dump
python3 - > /tmp/xy <<'PY2'
import re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for n in re.finditer(r'<node [^>]*>', xml):
    s = n.group(0)
    if re.search(r'resource-id="[^"]*:id/helpTitle"', s) and ('BUY ₹' in s or 'SELL ₹' in s):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY2
if [ -s /tmp/xy ]; then adb shell input tap $(cat /tmp/xy); shot 15-pick-opens-detail 8; else echo "no pick to open" | tee -a "$LOG"; fi
adb shell input keyevent KEYCODE_BACK
sleep 2

# The targets for each horizon at the top of the detail: the intraday call comes in as today's candles are read.
n=2
for sym in RELIANCE HDFCBANK TATASTEEL INFY ASIANPAINT; do
  open_stock "$sym"
  shot "$(printf %02d $n)-targets-$sym" 8
  n=$((n + 1))
  adb shell input keyevent KEYCODE_BACK
  sleep 2
done

# In English.
adb shell cmd locale set-app-locales "$PKG" --locales en
relaunch 10
open_stock RELIANCE
shot 20-targets-english 8
adb shell input keyevent KEYCODE_BACK
sleep 2
front
tap compactMenu
sleep 3
tap_text "Today's intraday"
for i in $(seq 1 40); do
  sleep 3
  center text "Check again" > /dev/null && break
done
shot 21-intraday-english 1
exit 0
