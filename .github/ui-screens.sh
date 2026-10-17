#!/usr/bin/env bash
# Temporary review helper: scans with NSE, SME and BSE, then with NSE off, checking exchange badges,
# saving screenshots to ui-screens/.
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
# Keep crash, ANR and process-death lines for the whole run.
adb logcat -c
adb logcat -v time AndroidRuntime:E ActivityManager:I ActivityTaskManager:I lowmemorykiller:I libprocessgroup:I \
  System.err:W ShareScan:V '*:S' > "$OUT/logcat.txt" 2>&1 &

shot() {
  sleep "${2:-3}"
  # Tap "Wait" on any system "isn't responding" dialog that still got through.
  if find_node text "Wait" > /dev/null 2>&1; then tap_text "Wait"; sleep 2; fi
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "shot $1"
}
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
dump() {
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break
    sleep 2
  done
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
}
# Centre of the first node whose resource id or exact text matches.
find_node() {
  dump
  python3 - "$1" "$2" <<'PY'
import re, sys
kind, value = sys.argv[1], sys.argv[2]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
attr = r'resource-id="[^"]*:id/%s"' % value if kind == 'id' else r'text="%s"' % re.escape(value)
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
menu() { tap menuButton; sleep 2; }
type_query() { adb shell input text "$1"; sleep 2; }
clear_query() { for i in $(seq 1 20); do adb shell input keyevent KEYCODE_DEL; done; sleep 1; }

# Waits up to 15 minutes, with a progress screenshot every 2 minutes; $1 names them.
wait_scan() {
  for i in $(seq 1 60); do
    flags=$(adb shell dumpsys activity top | grep 'id/startButton' | head -1 | awk '{print $2}')
    case "$flags" in ??E*) echo "scan finished after about $((i * 15)) seconds"; return 0 ;; esac
    if [ $((i % 8)) -eq 0 ]; then adb exec-out screencap -p > "$OUT/$1-progress-$i.png"; echo "progress $1 $i: $(adb shell pidof com.rohit.share_scan)"; fi
    sleep 15
  done
  echo "scan $1 did not finish"
}
search() { tap searchButton; sleep 1; clear_query; type_query "$1"; }

launch
sleep 3
# Scan 1: NSE (default on) plus SME and BSE.
menu
tap_text "सेटिंग्स"
sleep 2
tap settingSme
tap settingBse
shot 01-settings-all-markets 2
adb shell input keyevent KEYCODE_BACK
sleep 2
tap startButton
sleep 20
wait_scan scan1
shot 02-results-with-badges 3
search "globus"
shot 03-search-globus-nse-bse 2
clear_query
type_query "amiable"
shot 04-search-sme 2
adb shell input keyevent KEYCODE_BACK
sleep 1
tap searchClose
sleep 2

# Scan 2: NSE off, SME off, BSE on; the last switch cannot be turned off.
menu
tap_text "सेटिंग्स"
sleep 2
tap settingNse
tap settingSme
tap settingBse
shot 05-settings-nse-off-guard 1
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input swipe 540 600 540 1900 400
sleep 1
tap startButton
sleep 20
wait_scan scan2
shot 06-results-bse-only 3
search "reliance"
shot 07-search-reliance-on-bse 2
adb shell input keyevent KEYCODE_BACK
sleep 1
tap symbol
shot 08-detail-reliance-bse 4
sleep 2
kill %1 2>/dev/null
