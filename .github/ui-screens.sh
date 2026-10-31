#!/usr/bin/env bash
# Temporary review helper: the products section with the pictures stored in the app (logo, website picture, product
# photos), its filter and the cards' product pictures. Removed before merging.
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

# Well-known companies: some with their website's picture or a product photo, some with only the logo.
PHOTO_STOCKS=$(python3 - <<'PY2'
import json
stocks = json.load(open('app/src/main/assets/product-photos.json', encoding='utf-8'))['stocks']
made = json.load(open('app/src/main/assets/products.json', encoding='utf-8'))['stocks']
want = ['ASIANPAINT', 'MARUTI', 'MRF', 'BALKRISIND', 'HEROMOTOCO', 'HAVELLS', 'ESCORTS', 'TVSMOTOR', 'SAFARI', 'BPCL',
        'COALINDIA', 'TITAN', 'TCS', 'HDFCBANK']
print(' '.join(s for s in want if s in stocks and s in made))
PY2
)
echo "stocks: $PHOTO_STOCKS" | tee -a "$LOG"

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
shot 01-list-table 6

# Each company's products section: its logo, its website's picture and the products' photos or drawn pictures.
SEARCH=compactSearch
n=2
for sym in $PHOTO_STOCKS; do
  open_stock "$sym"
  shot "$(printf %02d $n)-detail-$sym" 6
  n=$((n + 1))
  adb shell input keyevent KEYCODE_BACK
  sleep 2
done

# A product photo's credit (long press on Hero's bike photo), then the list of every company making tyres.
open_stock HEROMOTOCO
sleep 5
xy=$(center id productImage) && adb shell input swipe ${xy% *} ${xy#* } ${xy% *} ${xy#* } 900
shot 30-photo-credit 1
adb shell input keyevent KEYCODE_BACK
sleep 2
open_stock MRF
sleep 5
tap_text "टायर"
sleep 4
shot 31-list-tyre-makers 2
adb shell input keyevent KEYCODE_BACK
sleep 2

# The Galaxy background's cards, with the products' pictures before the names.
setpref background string galaxy
relaunch 10
shot 32-galaxy-cards 2

# In English.
adb shell cmd locale set-app-locales "$PKG" --locales en
setpref background string space_glass
relaunch 10
open_stock ASIANPAINT
shot 33-detail-english 6
exit 0
