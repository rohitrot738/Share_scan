#!/usr/bin/env bash
# Temporary review helper: screenshots the chip glass on the stock cards and the filter sheet into ui-screens/
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
  # Never read a stale dump: a failed dump would otherwise leave the previous screen's file in place.
  adb shell rm -f /sdcard/ui.xml
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

# Settings are written straight into the app's preferences (the debug build allows run-as); base64 keeps the XML
# safe from the two shells in between.
set_prefs() {
  adb shell am force-stop "$PKG"
  local xml="<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>$1</map>"
  local b64; b64=$(printf '%s' "$xml" | base64 -w0)
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && echo $b64 | base64 -d > shared_prefs/scan.xml'"
}

swipe_id() {
  # Drag inside the node with this id from one fraction of its width to another.
  dump
  local box; box=$(python3 - "$1" <<'PY'
import re, sys
xml = open("/tmp/ui.xml", encoding="utf-8").read()
for node in re.findall(r"<node [^>]*>", xml):
    if re.search(r"resource-id=\"[^\"]*:id/%s\"" % sys.argv[1], node):
        print(*re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
        sys.exit(0)
sys.exit(1)
PY
) || { echo "could not find $1"; return; }
  set -- $box "$2" "$3"
  local y=$(( ($2 + $4) / 2 ))
  adb shell input swipe $(( $1 + ($3 - $1) * $5 / 100 )) $y $(( $1 + ($3 - $1) * $6 / 100 )) $y 600
}

# Waits (up to 7 minutes) for a finished scan's stock cards; uiautomator cannot read the screen while a scan runs.
wait_cards() {
  for i in $(seq 1 42); do
    if find_node id rank > /dev/null 2>&1; then echo "cards after ${i}0s"; return; fi
    sleep 10
  done
  echo "no cards"
}
# Scroll the board away so the cards fill the screen.
to_cards() { adb shell input swipe 540 1900 540 500 600; sleep 2; }
filters() {
  tap filterChip
  shot "filters-$1" 3
}
# Picks a glass mode in Settings; the activity restarts but keeps the scan's results.
glass_mode() {
  menu
  tap_text "सेटिंग्स"
  sleep 2
  local tries=0
  until find_node text "$1" > /dev/null 2>&1 || [ $tries -ge 4 ]; do
    adb shell input swipe 540 1800 540 900 500; sleep 1; tries=$((tries + 1))
  done
  tap_text "$1"
  sleep 4
}

focus() { adb shell dumpsys window | grep -m1 mCurrentFocus; }
snap() { adb exec-out screencap -p > "$OUT/$1.png"; echo "snap $1"; }
touched() { adb shell dumpsys input | grep -iE "TouchedWindow|touchedWindows|windowName=.*share_scan" | head -6; }
adb logcat -b events -v time > "$OUT/events.txt" 2>&1 &
slider_box() {
  dump
  python3 - "$1" <<'PY'
import re, sys
xml = open("/tmp/ui.xml", encoding="utf-8").read()
found = [re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()
         for n in re.findall(r"<node [^>]*>", xml) if re.search(r'resource-id="[^"]*:id/metricSlider"', n)]
print(*found[int(sys.argv[1])]) if len(found) > int(sys.argv[1]) else sys.exit(1)
PY
}
# Drags each end bead from where it sits by the screen's edge (where the back gesture lives) to the middle.
edge_test() {
  tap filterChip
  sleep 4
  echo "sheet:"; focus
  box=$(slider_box 0) || box=""
  echo "market cap slider: $box"
  set -- $box
  y=$(( ($2 + $4) / 2 ))
  adb shell input swipe $(( $1 + 50 )) $y $(( ($1 + $3) / 2 - 100 )) $y 800
  sleep 2
  snap edge-1-left
  echo "after left drag:"; focus
  box=$(slider_box 1) || box=""
  echo "price slider: $box"
  set -- $box
  y=$(( ($2 + $4) / 2 ))
  adb shell input swipe $(( $3 - 50 )) $y $(( ($1 + $3) / 2 + 100 )) $y 800
  sleep 2
  snap edge-2-right
  echo "after right drag:"; focus
}
set_prefs "<string name=\"theme\">galaxy</string><string name=\"glass_style\">frosted</string>"
launch
sleep 8
edge_test
kill %1 %2 2>/dev/null
grep -E "share_scan" "$OUT/events.txt" | grep -E "wm_on_(create|destroy)_called|input_focus|wm_finish" | tail -20 > "$OUT/lifecycle.txt" || true
cat "$OUT/lifecycle.txt"
