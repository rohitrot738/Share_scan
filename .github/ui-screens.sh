#!/usr/bin/env bash
# Temporary review helper: records the animated Galaxy sky and screenshots glass toggles and press effects into ui-screens/
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

# The emulator runner turns animations off; the sky and the press effects need them on.
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1

# Holds a finger on a view for $2 ms and screenshots it while pressed.
hold_shot() {
  local xy; xy=$(find_node "$1" "$2") || { echo "could not find $2"; return; }
  adb shell input swipe $xy $xy 1500 &
  sleep 0.8
  adb exec-out screencap -p > "$OUT/$3.png"
  wait %2 2>/dev/null
  echo "shot $3 (pressed)"
}

launch
sleep 4
# The emulator's screenrecord drops nearly every frame, so stitch a burst of raw screenshots into a video.
burst=$(mktemp -d)
started=$(date +%s.%N)
for n in $(seq -w 1 90); do adb exec-out screencap > "$burst/$n.raw"; done
ended=$(date +%s.%N)
python3 .github/stitch-burst.py "$burst" "$started" "$ended" "$OUT/galaxy-sky.mp4" && echo "recorded galaxy-sky.mp4"
rm -rf "$burst"
shot 01-galaxy-sky 0
shot 02-galaxy-sky-later 1
hold_shot id filterChip 03-filters-chip-pressed
adb shell input keyevent KEYCODE_BACK
sleep 2
menu
hold_shot text "सेटिंग्स" 04-menu-row-pressed
sleep 2
shot 05-glass-switches 1
tap settingBse
shot 06-glass-switch-on 1
tap settingBse
adb shell input keyevent KEYCODE_BACK
sleep 1
kill %1 2>/dev/null
