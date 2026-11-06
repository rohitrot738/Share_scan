#!/usr/bin/env bash
# Temporary crash hunt: the released APKs on an emulator, v1.2.145 updated to v1.2.147 the way a phone updates, then
# the main screens and every list layout; Android's crash buffer is read after each step. Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=crash-hunt
mkdir -p "$OUT"
LOG="$OUT/log.txt"
: > "$LOG"

adb root > /dev/null 2>&1; sleep 3
adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb shell cmd uimode night yes

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



relaunch() { launch; sleep "${1:-10}"; }
# What Android recorded as crashes since the last look, marked with the step before it.
crashes() {
  local c; c=$(adb logcat -b crash -d 2>&1)
  if [ -n "$(echo "$c" | grep -v '^--------- beginning')" ]; then
    echo "=== CRASH after: $1" | tee -a "$LOG"
    echo "$c" >> "$LOG"
    adb logcat -d -t 400 | grep -E "AndroidRuntime|FATAL|Exception|at com.rohit" | tail -80 >> "$LOG"
  else
    echo "ok after: $1" | tee -a "$LOG"
  fi
  adb logcat -b crash -c
}
# One preference, with root (the release build is not debuggable): setpref NAME string|boolean VALUE.
setpref() {
  adb shell am force-stop "$PKG"
  local f=/data/data/$PKG/shared_prefs/scan.xml
  adb shell "mkdir -p /data/data/$PKG/shared_prefs; [ -f $f ] || echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?><map></map>' > $f"
  adb shell cat "$f" > /tmp/p.xml
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys
name, kind, value = sys.argv[1:]
xml = open('/tmp/p.xml', encoding='utf-8').read()
xml = re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)' % re.escape(name), '', xml)
line = f'<string name="{name}">{value}</string>' if kind == 'string' else f'<{kind} name="{name}" value="{value}" />'
xml = xml.replace('</map>', f'    {line}\n</map>')
open('/tmp/p.xml', 'w', encoding='utf-8').write(xml)
PY
  adb push /tmp/p.xml "$f" > /dev/null
  local uid; uid=$(adb shell stat -c %u "/data/data/$PKG" | tr -d '\r')
  adb shell chown "$uid:$uid" "/data/data/$PKG/shared_prefs" "$f"
  adb shell chmod 660 "$f"
  adb shell restorecon -R "/data/data/$PKG/shared_prefs"
  echo "pref $1=$3" | tee -a "$LOG"
}
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


search_for() {
  center id searchClose > /dev/null && tap searchClose && sleep 2
  tap "$2"
  sleep 2
  adb shell input text "$1"
  sleep 3
  hide_keyboard
}
scan_now() {
  home
  tap statusPill
  sleep 3
  tap startButton
  sleep 10
  adb shell input keyevent KEYCODE_BACK
  for i in $(seq 1 200); do
    sleep 5
    unstall
    center text "STOCKS" > /dev/null && break
  done
  echo "scan done after ~$((i * 5 + 10))s" | tee -a "$LOG"
}

# v1.2.145, as the phone had it, with a scan kept.
adb install -r old.apk | tee -a "$LOG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
launch; sleep 8
setpref background string space_glass
setpref live_scan boolean false
relaunch 12
crashes "v1.2.145 first launch"
scan_now
crashes "v1.2.145 scan"
shot 01-old-list 3

# The update: v1.2.147 over it, data kept.
adb install -r new.apk | tee -a "$LOG"
relaunch 15
shot 02-new-first 2
crashes "v1.2.147 first launch after the update"
all_texts | head -15 >> "$LOG"
for k in 1 2 3 4 5; do adb shell input swipe 540 1500 540 600 300; sleep 1; done
crashes "scroll"
scan_now
shot 03-new-scan 3
crashes "v1.2.147 scan"
search_for Jewel compactSearch
swipe_up
shot 04-jewel 2
crashes "search with no-chart rows"
xy=$(center row KHAZANCHI) && adb shell input tap $xy
sleep 6
shot 05-khazanchi 2
crashes "no-chart sheet"
home
search_for ITC compactSearch
xy=$(center row ITC) && adb shell input tap $xy
sleep 6
shot 06-itc 2
crashes "ITC detail"
find_text "brands देखें"
tap detailOpenBrands
sleep 4
shot 07-brands 2
crashes "brands window"
for k in 1 2 3 4 5 6; do swipe_up; done
crashes "brands window scrolled"
home
tap compactMenu; sleep 2
tap_text "बड़े पैसे की चाल"; sleep 10
shot 08-bigmoney 2
crashes "Big Money"
home
tap compactMenu; sleep 2
tap_text "Paper Trading"; sleep 6
crashes "Paper Trading"
home

# Each layout on Galaxy, with the no-chart rows on screen.
for layout in auto accordion bento swipe; do
  setpref background string galaxy
  setpref list_layout string "$layout"
  relaunch 14
  shot "10-$layout" 2
  crashes "Galaxy $layout list"
  search_for Jewel searchButton
  for k in 1 2 3 4; do adb shell input swipe 540 1500 540 600 300; sleep 1; done
  shot "11-$layout-jewel" 2
  crashes "Galaxy $layout no-chart rows"
done
# Hindi, then English.
for lang in hi en; do
  setpref language string "$lang"
  relaunch 14
  shot "20-$lang" 2
  crashes "language $lang"
done
echo "done" | tee -a "$LOG"
