#!/usr/bin/env bash
# Temporary review helper: runs a scan on the debug APK, then checks the search bar and name search,
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

launch
sleep 3
tap startButton
for i in $(seq 1 80); do
  flags=$(adb shell dumpsys activity top | grep 'id/startButton' | head -1 | awk '{print $2}')
  case "$flags" in ??E*) echo "scan finished after about $((i * 15)) seconds"; break ;; esac
  sleep 15
done
shot 01-results-with-names 3

tap searchButton
shot 02-search-open-at-top 2
type_query "reliance"
shot 03-search-reliance 2
clear_query
type_query "tata%smotors"
shot 04-search-tata-motors 2
clear_query
type_query "bank"
shot 05-search-bank 2
adb shell input keyevent KEYCODE_BACK
sleep 1
tap searchClose
sleep 2
tap symbol
shot 06-detail-with-name 4
adb shell input keyevent KEYCODE_BACK
sleep 2
menu
tap_text "सेटिंग्स"
shot 07-settings-sme 2
adb logcat -d | grep -E "AndroidRuntime|FATAL" | tail -60 > "$OUT/crash-log.txt"
