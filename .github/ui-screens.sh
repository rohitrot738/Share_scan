#!/usr/bin/env bash
# Temporary review helper: checks the ← back and ✕ close buttons of the menu sheets, saving screenshots to ui-screens/
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
# Which sheet is showing, by a view only that sheet has.
which_sheet() {
  dump
  for id in settingBse themeGrid helpSections aboutVersion menuItems; do
    grep -q ":id/$id\"" /tmp/ui.xml && { echo "$id"; return; }
  done
  grep -q ":id/startButton\"" /tmp/ui.xml && echo "main screen" || echo "nothing known"
}

launch
sleep 3
menu
shot 01-menu-with-close 1
echo "after opening menu: $(which_sheet)"
tap_text "सेटिंग्स"
sleep 2
shot 02-settings-with-back 1
echo "after Settings: $(which_sheet)"
tap sheetBack
sleep 2
shot 03-back-to-menu 1
echo "after ← in Settings: $(which_sheet)"
tap_text "Score कैसे बनता है"
sleep 2
echo "after Help: $(which_sheet)"
adb shell input keyevent KEYCODE_BACK
sleep 2
shot 04-system-back-to-menu 1
echo "after system back in Help: $(which_sheet)"
tap sheetClose
sleep 2
shot 05-menu-closed 1
echo "after ✕ in menu: $(which_sheet)"
menu
tap_text "थीम"
sleep 2
shot 06-theme-with-back 1
echo "after Theme: $(which_sheet)"
tap sheetClose
sleep 2
echo "after ✕ in Theme: $(which_sheet)"
kill %1 2>/dev/null
