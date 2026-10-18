#!/usr/bin/env bash
# Temporary review helper: screenshots every theme's moving sky and glass into ui-screens/
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

# Two raw frames a little apart; different pixels mean the sky is moving.
moving() {
  adb exec-out screencap > /tmp/a.raw; sleep 0.5; adb exec-out screencap > /tmp/b.raw
  if cmp -s /tmp/a.raw /tmp/b.raw; then echo "$1: sky STILL"; else echo "$1: sky moving"; fi
}

# Every theme has its own Milky Way. Pick each one by writing the theme setting straight into the app's
# preferences (the debug build allows run-as), then relaunch and check its sky moves.
set_theme() {
  adb shell am force-stop "$PKG"
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && printf \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><string name=\\\"theme\\\">$1</string></map>\" > shared_prefs/scan.xml'"
}
n=1
for theme in galaxy nebula aurora midnight sunset ocean emerald mars royal_gold frost; do
  set_theme "$theme"
  launch
  # Give the sky time to bake and fade in.
  sleep 5
  moving "$theme" | tee -a "$OUT/moving.txt"
  shot "$(printf %02d $n)-$theme-home" 0
  n=$((n + 1))
done
menu
tap_text "सेटिंग्स"
sleep 2
shot 11-frost-settings 1
adb shell input keyevent KEYCODE_BACK
sleep 1
kill %1 2>/dev/null
