#!/usr/bin/env bash
# Temporary crash hunt in a live market: the released v1.2.148 on an emulator while NSE trades, live mode on (the
# scan, details, the trade window, today's intraday, the watchlist's live alerts and the background live scan);
# Android's crash buffer is read after each step. Removed before merging.
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
  # Live mode may have started a scan of its own on launch ("SCAN 40% • 1,050/2,630"): then it is only waited for.
  if all_texts | grep -q -E "SCAN [0-9]+% • [0-9,]+/"; then
    echo "a scan was already running" | tee -a "$LOG"
  else
    tap statusPill
    sleep 3
    tap startButton
    sleep 10
    adb shell input keyevent KEYCODE_BACK
  fi
  for i in $(seq 1 200); do
    sleep 5
    unstall
    center text "STOCKS" > /dev/null && break
  done
  echo "scan done after ~$((i * 5 + 10))s" | tee -a "$LOG"
}

# Long-presses the line of the stock $1 (on the compact table), which stars it.
star_row() { local xy; xy=$(center row "$1") && adb shell input swipe $xy $xy 900 || echo "could not find the line $1" | tee -a "$LOG"; sleep 2; }
# Opens the Menu entry titled $1, scrolling to it, and looks at what it did.
menu_entry() {
  home
  tap compactMenu
  sleep 2
  find_text "$1" > /dev/null && tap_text "$1"
  sleep "${2:-6}"
  shot "m-$(echo "$1" | tr -cd 'A-Za-z')" 1
  crashes "Menu: $1"
  home
}

# Taps the line at place $1 (0 = the first on screen) of the compact table.
tap_row_at() {
  dump
  local xy; xy=$(python3 - "$1" <<'PY'
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
rows = re.findall(r'<node [^>]*resource-id="[^"]*:id/rowSymbol"[^>]*>', xml)
i = int(sys.argv[1])
if i < len(rows):
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', rows[i]).groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)
  [ -n "$xy" ] && adb shell input tap $xy || echo "no line at $1" | tee -a "$LOG"
}
# The in-app crash window (CrashLog), when the app comes back after a crash.
crash_window() {
  relaunch 10
  all_texts | sed -n '1,30p' | sed 's/^/  window: /' >> "$LOG"
  shot "crash-window-$1" 1
  adb shell input keyevent KEYCODE_BACK
}
look() {
  local before; before=$(grep -c "=== CRASH" "$LOG")
  crashes "$1"
  if [ "$(grep -c "=== CRASH" "$LOG")" -gt "$before" ]; then crash_window "$2"; fi
}

echo "IST: $(TZ=Asia/Kolkata date '+%F %H:%M %A')" | tee -a "$LOG"
adb install -r new.apk | tee -a "$LOG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
launch; sleep 8
setpref background string space_glass
setpref live_scan boolean true
setpref live_interval int 15
setpref watch_alerts boolean true
setpref live_alerts boolean true
relaunch 12
look "launch in a live market" a
scan_now
shot 01-live-list 3
look "live scan" b
all_texts | sed -n '1,25p' >> "$LOG"

# The first three stocks: detail, scrolled, then the trade window (today's intraday targets live).
for i in 0 1 2; do
  home
  tap_row_at "$i"
  sleep 7
  shot "02-detail-$i" 1
  look "detail $i" "c$i"
  tap detailOpenTrade
  sleep 10
  shot "03-trade-$i" 1
  look "trade window $i" "d$i"
  for k in 1 2 3 4; do swipe_up; done
  shot "04-trade-$i-more" 1
  look "trade window $i scrolled" "e$i"
  home
done

# Five stars, then the watchlist with live prices and alerts.
home
star_rows 0 1 2 3 4
look "starring five" f
tap_text "Watchlist"
sleep 8
shot 05-watchlist 1
look "watchlist" g
home

for entry in "Today's intraday" "Paper Trading" "Big Money" "Sector strength" "Track record"; do
  home
  tap compactMenu
  sleep 2
  find_text "$entry" > /dev/null && tap_text "$entry"
  sleep 30
  shot "m-$(echo "$entry" | tr -cd 'A-Za-z')" 1
  look "Menu: $entry (live)" "m"
  for k in 1 2 3; do swipe_up; done
  look "Menu: $entry scrolled" "n"
  home
done

# The background live scan: every scheduled job run now, the app left open, then closed.
for j in $(seq 0 40); do adb shell cmd jobscheduler run -f "$PKG" "$j" > /dev/null 2>&1; done
sleep 150
shot 06-after-background 1
look "background live scan (app open)" h
adb shell input keyevent KEYCODE_HOME
for j in $(seq 0 40); do adb shell cmd jobscheduler run -f "$PKG" "$j" > /dev/null 2>&1; done
sleep 240
look "background live scan (app closed)" i
relaunch 12
shot 07-back 1
look "back to the app" j
# The app left open through a few market refreshes.
sleep 180
look "three minutes open" k
adb logcat -d | grep -E "LiveScanWorker|WM-|ScanEngine|share_scan" | tail -60 >> "$LOG"
echo "done" | tee -a "$LOG"
