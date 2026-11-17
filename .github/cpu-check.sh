#!/usr/bin/env bash
# Temporary: live mode's background scan, its job stopped by Android mid-scan, and the app's CPU in the minutes after;
# the released v1.2.149 against the branch's build. Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=cpu-check
mkdir -p "$OUT"
LOG="$OUT/log.txt"
: > "$LOG"
adb root > /dev/null 2>&1; sleep 3
adb shell svc power stayon true
# Market hours: Wednesday 18 Nov 2026, 11:00 IST (05:30 UTC).
adb shell settings put global auto_time 0
adb shell date 111805302026.00 > /dev/null
echo "device time: $(adb shell date -u)" | tee -a "$LOG"

setpref() {
  local f=/data/data/$PKG/shared_prefs/scan.xml
  adb shell cat "$f" > /tmp/p.xml 2>/dev/null || echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' > /tmp/p.xml
  grep -q "<map" /tmp/p.xml || echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map></map>' > /tmp/p.xml
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys
name, kind, value = sys.argv[1:]
xml = open('/tmp/p.xml', encoding='utf-8').read()
xml = re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)' % re.escape(name), '', xml)
xml = xml.replace('<map />', '<map></map>')
xml = xml.replace('</map>', f'    <{kind} name="{name}" value="{value}" />\n</map>')
open('/tmp/p.xml', 'w', encoding='utf-8').write(xml)
PY
  adb shell mkdir -p "/data/data/$PKG/shared_prefs"
  adb push /tmp/p.xml "$f" > /dev/null
  local uid; uid=$(adb shell stat -c %u "/data/data/$PKG" | tr -d '\r')
  adb shell chown "$uid:$uid" "/data/data/$PKG/shared_prefs" "$f"
  adb shell chmod 660 "$f"
  adb shell restorecon -R "/data/data/$PKG/shared_prefs"
}
ticks() { adb shell cat "/proc/$1/stat" 2>/dev/null | awk '{print $14 + $15}'; }
# CPU seconds the app's process used over $2 seconds, and the share of that time.
measure() {
  local pid; pid=$(adb shell pidof "$PKG" | tr -d '\r')
  if [ -z "$pid" ]; then echo "$1: process not running" | tee -a "$LOG"; return; fi
  local a; a=$(ticks "$pid"); sleep "$2"; local b; b=$(ticks "$pid")
  if [ -z "$b" ]; then echo "$1: process ended during the window" | tee -a "$LOG"; return; fi
  python3 -c "d=($b-$a)/100; print(f'$1: {d:.1f} s CPU in $2 s = {d*100/$2:.1f}% (pid $pid)')" | tee -a "$LOG"
}
state() { adb shell dumpsys activity processes | grep -E "ProcessRecord\{.*$PKG|procState|curProcState|setProcState" | grep -A2 "$PKG" | head -4 | sed 's/^/  /' >> "$LOG"; }

phase() {
  local label=$1 apk=$2
  echo "=== $label" | tee -a "$LOG"
  adb uninstall "$PKG" > /dev/null 2>&1
  adb install -r "$apk" | tail -1 | tee -a "$LOG"
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
  adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 12
  adb shell am force-stop "$PKG"
  setpref live_scan boolean true
  setpref live_interval int 15
  # Started again so live mode's work is scheduled, then left for the home screen.
  adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 10
  adb shell input keyevent KEYCODE_HOME; sleep 5
  adb shell dumpsys jobscheduler | grep -E "JOB #.*$PKG" | head -5 | sed 's/^/  job: /' >> "$LOG"
  local id; id=$(adb shell dumpsys jobscheduler | grep -oE "JOB #u0a[0-9]+/[0-9]+: [0-9a-f]+ $PKG" | head -1 | sed -E 's#.*/([0-9]+):.*#\1#')
  echo "  live job id: $id" >> "$LOG"
  adb shell cmd jobscheduler run -f "$PKG" "$id" > /dev/null 2>&1
  # Waits until the scan is scoring stocks (the CPU-heavy part), not still fetching its lists.
  local pid busy=0
  for i in $(seq 1 40); do
    pid=$(adb shell pidof "$PKG" | tr -d '\r')
    [ -z "$pid" ] && { sleep 5; continue; }
    local a b; a=$(ticks "$pid"); sleep 10; b=$(ticks "$pid")
    [ -n "$a" ] && [ -n "$b" ] && echo "  sample $i: $(( b - a )) ticks in 10 s" >> "$LOG"
    if [ -n "$a" ] && [ -n "$b" ] && [ $(( b - a )) -ge 150 ]; then busy=1; break; fi
  done
  echo "  scan busy: $busy" >> "$LOG"
  measure "$label: scan running inside the job" 20
  adb shell cmd jobscheduler timeout -u 0 "$PKG" 2>&1 | head -3 | sed 's/^/  timeout: /' >> "$LOG"
  echo "  job stopped by Android (timeout)" >> "$LOG"
  sleep 5
  state
  measure "$label: first minute after the job stopped" 60
  measure "$label: second minute after the job stopped" 60
  adb logcat -d | grep -E "WM-WorkerWrapper.*LiveScanWorker|WM-.*Stopping|excessive" | tail -6 | sed 's/^/  log: /' >> "$LOG"
  adb logcat -c
}

phase "v1.2.149 (released)" old.apk
phase "branch build (the fix)" new.apk
echo "done" | tee -a "$LOG"
