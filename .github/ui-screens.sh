#!/usr/bin/env bash
# Temporary review helper: drives the debug APK through the header menu, its sheets and every theme,
# saving screenshots to ui-screens/.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
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
pick_theme() { menu; tap_text "थीम"; sleep 2; tap_text "$1"; sleep 3; }

launch
shot 01-home-menu-button 4
menu
shot 02-menu 1

tap_text "सेटिंग्स"
shot 03-settings 2
tap_text "Chartink"
adb shell input swipe 540 1900 540 900 400
shot 04-settings-bottom 2
tap settingResetFilters
shot 05-settings-confirm 2
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input keyevent KEYCODE_BACK
sleep 2

menu
tap_text "थीम"
shot 06-theme-picker 2
tap_text "Nebula"
shot 07-theme-nebula 4
pick_theme "Aurora"
shot 08-theme-aurora 2
pick_theme "Midnight"
shot 09-theme-midnight 2
menu
shot 10-menu-midnight 1
adb shell input keyevent KEYCODE_BACK
sleep 2

menu
tap_text "Score कैसे बनता है"
shot 11-help 2
adb shell input swipe 540 1900 540 700 400
shot 12-help-scrolled 2
adb shell input keyevent KEYCODE_BACK
sleep 2

menu
tap_text "App के बारे में"
shot 13-about 2
adb shell input keyevent KEYCODE_BACK
sleep 2

menu
tap_text "Update देखें"
shot 14-update-check 1

# A scan in the Aurora theme, then the detail sheet with the chart button set to Chartink.
pick_theme "Aurora"
launch
sleep 3
tap startButton
shot 15-aurora-scanning 30
for i in $(seq 1 80); do
  flags=$(adb shell dumpsys activity top | grep 'id/startButton' | head -1 | awk '{print $2}')
  case "$flags" in ??E*) echo "scan finished after about $((i * 15)) more seconds"; break ;; esac
  sleep 15
done
shot 16-aurora-results 3
tap symbol
shot 17-aurora-detail 4
adb shell input swipe 540 1900 540 600 400
adb shell input swipe 540 1900 540 600 400
adb shell input swipe 540 1900 540 600 400
shot 18-aurora-detail-bottom 2
adb shell input keyevent KEYCODE_BACK
sleep 2
pick_theme "Galaxy"
shot 19-back-to-galaxy 2
adb logcat -d | grep -E "AndroidRuntime|FATAL" | tail -60 > "$OUT/crash-log.txt"
