#!/usr/bin/env bash
# Temporary review helper: checks live mode on an emulator during market hours. Remove before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
# A freshly booted emulator's launcher often hangs for a while; hide its ANR dialog and let it settle.
adb shell settings put global hide_error_dialogs 1
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Keep crash, ANR and process-death lines for the whole run, and enough of the rest for the worker's lines.
adb logcat -G 16M
adb logcat -c
adb logcat -v time AndroidRuntime:E ActivityManager:I ActivityTaskManager:I lowmemorykiller:I libprocessgroup:I \
  System.err:W ShareScan:V '*:S' > "$OUT/logcat.txt" 2>&1 &

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
dump() {
  # Never read a stale dump: a failed dump would otherwise leave the previous screen's file in place.
  adb shell rm -f /sdcard/ui.xml
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break
    sleep 2
  done
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
}
# Centre of the first node whose resource id or exact text (or text start, with kind "prefix") matches.
find_node() {
  dump
  python3 - "$1" "$2" <<'PY'
import re, sys
kind, value = sys.argv[1], sys.argv[2]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
if kind == 'id':
    attr = r'resource-id="[^"]*:id/%s"' % value
elif kind == 'prefix':
    attr = r'text="%s' % re.escape(value)
else:
    attr = r'text="%s"' % re.escape(value)
for node in re.findall(r'<node [^>]*>', xml):
    if re.search(attr, node):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}
tap() { local xy; xy=$(find_node id "$1") && adb shell input tap $xy || echo "could not find $1"; }
tap_text() { local xy; xy=$(find_node text "$1") && adb shell input tap $xy || echo "could not find text $1"; }
tap_prefix() { local xy; xy=$(find_node prefix "$1") && adb shell input tap $xy || echo "could not find text starting $1"; }
menu() { tap menuButton; sleep 2; }
snap() { adb exec-out screencap -p > "$OUT/$1.png"; echo "snap $1"; }
set_prefs() {
  adb shell am force-stop "$PKG"
  local xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>$1</map>"
  local b64; b64=$(printf '%s' "$xml" | base64 -w0)
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && echo $b64 | base64 -d > shared_prefs/scan.xml'"
}
home() {
  for i in 1 2 3; do
    find_node id design_bottom_sheet > /dev/null 2>&1 || return
    adb shell input keyevent BACK; sleep 2
  done
}
to_top() { for i in 1 2; do adb shell input swipe 540 700 540 2000 400; sleep 1; done; sleep 1; }
to_cards() { adb shell input swipe 540 1900 540 500 600; sleep 2; }
# Waits (up to [1] seconds) for the stock cards; uiautomator cannot read the screen while a scan runs.
wait_cards() {
  local limit=${1:-720} waited=0
  while [ $waited -lt $limit ]; do
    if find_node id rank > /dev/null 2>&1; then echo "cards after ${waited}s"; return 0; fi
    sleep 15; waited=$((waited + 15))
  done
  echo "no cards after ${limit}s"; return 1
}
# Our notifications as the system lists them.
notes() { adb shell dumpsys notification --noredact | grep -A12 "pkg=$PKG" | grep -E "android.title=|android.text=|android.bigText=" ; }
# WorkManager's job for live mode, run now instead of at its next 15-minute slot. Its job id grows with every
# reschedule, so it is read from the job list.
run_live_job() {
  local ids
  ids=$(adb shell dumpsys jobscheduler | grep -E "JOB #u0a[0-9]+/" | grep "$PKG/androidx.work" \
    | sed -E 's|.*JOB #u0a[0-9]+/([^:]*:)?([0-9]+): .*|\2|' | sort -u)
  echo "live job ids: ${ids:-none}"
  for id in $ids; do adb shell cmd jobscheduler run -f "$PKG" "$id" 2>&1 | head -1; done
}

# Live mode needs the market open: wait for 9:17 IST when the emulator is up earlier.
start=$(TZ=Asia/Kolkata date -d "09:17" +%s)
now=$(date +%s)
if [ "$now" -lt "$start" ]; then echo "waiting $(( (start - now) / 60 )) min for the market"; sleep $((start - now)); fi
echo "IST now: $(TZ=Asia/Kolkata date)"
WATCH='<set name="watchlist"><string>RELIANCE</string><string>TCS</string><string>HDFCBANK</string><string>INFY</string></set>'
# The sky stays still so uiautomator can read the screen between scans; live mode keeps its defaults (on, 30 min).
set_prefs "<string name=\"theme\">galaxy</string><boolean name=\"sky_animation\" value=\"false\" />$WATCH"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS

# 1) First launch in market hours: live mode's job starts a scan in the background straight away.
launch
shot 1-auto-starting 20
shot 1-auto-running 60
wait_cards 900
snap 2-live-home
to_cards
snap 2-live-cards

# 2) A starred stock's detail sheet: live RVOL note, sector and its alert line.
to_top
tap searchButton; sleep 1
adb shell input text RELIANCE; sleep 2
adb shell input keyevent BACK; sleep 1
tap symbol; sleep 3
snap 3-detail
adb shell input swipe 540 1800 540 700 500; sleep 1
adb shell input swipe 540 1800 540 700 500; sleep 2
snap 3-detail-plan
home
tap searchClose; sleep 2

# 3) Sector filter in the filter sheet, the sector sheet, and a sector picked from it.
tap filterChip; sleep 3
snap 4-filters
home
tap sectorChip; sleep 3
snap 5-sectors
tap sectorName; sleep 3
snap 5-sector-filtered
tap sectorChip; sleep 3
tap helpTitle; sleep 2
snap 5-sector-cleared

# 4) Track record and live settings.
to_top
menu; tap_text "Track record"; sleep 3
snap 6-track-record
adb shell input swipe 540 1900 540 600 500; sleep 2
snap 6-track-record-more
home
menu; tap_text "सेटिंग्स"; sleep 3
snap 7-settings-live
home

# 4b) Menu → स्कैन: live mode, exchanges and their industries; NSE narrowed to IT only, then a scan.
menu; tap_text "स्कैन"; sleep 3
snap 7-scan-sheet
adb shell input swipe 540 1900 540 600 500; sleep 2
snap 7-scan-markets
tap marketExpand; sleep 2
snap 7-scan-nse-sectors
tap marketNone; sleep 1
tap_prefix "Information Technology"; sleep 1
snap 7-scan-it-only
home
to_top
tap startButton
wait_cards 600
snap 7-it-scan
to_cards
snap 7-it-scan-cards
to_top
menu; tap_text "स्कैन"; sleep 3
adb shell input swipe 540 1900 540 600 500; sleep 2
tap marketExpand; sleep 1
tap marketAll; sleep 1
snap 7-scan-all-again
home

# 5) A fresh start shows the kept scan at once.
launch
shot 8-restart 3
shot 8-restart-later 6

# 6) Alerts: the kept scan is made old and all-WEAK, and RELIANCE is set to wait under a ₹2 trigger, so live mode's
# next scan finds new STRONG stocks and a RELIANCE breakout.
adb shell am force-stop "$PKG"
adb exec-out run-as "$PKG" cat files/last_scan.gz > /tmp/scan.gz
python3 - <<'PY'
import gzip, json, time
lines = gzip.open('/tmp/scan.gz', 'rt', encoding='utf-8').read().splitlines()
head = json.loads(lines[0]); head['at'] = int(time.time() * 1000) - 2 * 3600 * 1000
# The kept scan was of IT only; mark it as a scan of every NSE stock, like the next one, so it is the "before".
head['scope'] = 'NSE'
rows = [json.loads(l) for l in lines[1:] if l.strip()]
for row in rows: row['sc'] = 10.0
with gzip.open('/tmp/scan2.gz', 'wt', encoding='utf-8') as out:
    out.write(json.dumps(head) + '\n')
    for row in rows: out.write(json.dumps(row) + '\n')
print('kept scan rewritten:', len(rows), 'stocks')
PY
adb push /tmp/scan2.gz /data/local/tmp/scan2.gz > /dev/null
adb shell chmod 644 /data/local/tmp/scan2.gz
adb shell "run-as $PKG cp /data/local/tmp/scan2.gz files/last_scan.gz"
STATE='{&quot;RELIANCE&quot;:{&quot;ph&quot;:&quot;WAITING&quot;,&quot;p&quot;:1.0,&quot;e&quot;:2.0,&quot;s&quot;:1.5,&quot;t&quot;:[1.0E9,2.0E9,3.0E9],&quot;h&quot;:0,&quot;since&quot;:0}}'
set_prefs "<string name=\"theme\">galaxy</string><boolean name=\"sky_animation\" value=\"false\" />$WATCH<string name=\"watch_states\">$STATE</string>"
adb logcat -c
# WorkManager skips a periodic job forced before its 15 minutes are up, so forget the schedule instead: on the next
# start the app enqueues live mode afresh and its first run starts at once, as when the user turns live mode on.
adb shell "run-as $PKG sh -c 'rm -f databases/androidx.work.workdb* no_backup/androidx.work.workdb*'"
launch
sleep 8
adb shell input keyevent HOME
sleep 3
run_live_job
for i in $(seq 1 40); do
  sleep 20
  if notes | grep -q "breakout"; then echo "alerts after $((i * 20))s"; break; fi
done
notes | tee "$OUT/notifications.txt"
adb shell dumpsys jobscheduler | grep -B2 -A12 "$PKG/androidx.work" > "$OUT/jobs.txt" 2>&1
adb shell cmd statusbar expand-notifications; sleep 3
snap 9-notifications
adb shell cmd statusbar collapse
# The app as the background scan left it, opened without a restart.
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null
shot 9-after-job 4
adb logcat -d -s WM-WorkerWrapper:I WM-SystemJobService:I ShareScan:V AndroidRuntime:E > "$OUT/worker-log.txt" 2>&1
kill %1 2>/dev/null
