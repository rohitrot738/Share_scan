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

snap() { adb exec-out screencap -p > "$OUT/$1.png"; echo "snap $1"; }
raw() { adb exec-out screencap > "$1"; }
# Bytes that differ between two raw screen grabs inside one box (x0 y0 x1 y1), 4 bytes a pixel after a 16-byte header.
differ() {
  python3 - "$1" "$2" "$3" "$4" "$5" "$6" <<'PY'
import sys
a = open(sys.argv[1], "rb").read(); b = open(sys.argv[2], "rb").read()
x0, y0, x1, y1 = map(int, sys.argv[3:7])
w = int.from_bytes(a[0:4], "little"); off = 16 if len(a) % 4 == 0 else 12
n = 0
for y in range(y0, y1):
    s = off + (y * w + x0) * 4; e = off + (y * w + x1) * 4
    n += sum(1 for p, q in zip(a[s:e], b[s:e]) if p != q)
print(n)
PY
}

# Sky only, clear of every text and control: the band under the empty-state text, above the navigation handle.
band() { differ "$1" "$2" 0 2080 1080 2320; }

# Where the menu, its Settings row and the sky switch are, learnt with the sky still: uiautomator only reads a
# settled screen. The layout is the same with the sky moving.
set_prefs "<string name=\"theme\">galaxy</string><boolean name=\"sky_animation\" value=\"false\" />"
launch
sleep 8
xy_menu=$(find_node id menuButton); tap menuButton; sleep 2
xy_settings=$(find_node text "सेटिंग्स"); tap_text "सेटिंग्स"; sleep 3
xy_sky=$(find_node id settingSky)
echo "menu=$xy_menu settings=$xy_settings sky switch=$xy_sky"
snap 0-settings

# 1) As on the phone: the sky moving, then switched off in Settings, then a scan redrawing the screen.
set_prefs "<string name=\"theme\">galaxy</string>"
launch
sleep 8
raw /tmp/a1; sleep 3; raw /tmp/a2
echo "1a sky on: changed bytes in 3 s = $(band /tmp/a1 /tmp/a2)"
adb shell input tap $xy_menu; sleep 3
adb shell input tap $xy_settings; sleep 4
adb shell input tap $xy_sky; sleep 2
snap 1-switch-off
adb shell input keyevent BACK; sleep 3
raw /tmp/b1; sleep 3; raw /tmp/b2
echo "1b switched off, idle: changed bytes in 3 s = $(band /tmp/b1 /tmp/b2)"
tap startButton
sleep 6
raw /tmp/c1; sleep 4; raw /tmp/c2
echo "1c switched off, scanning: changed bytes in 4 s = $(band /tmp/c1 /tmp/c2)"
snap 1-still-scanning

# 2) The glass switch, as on the phone: off, then back on.
menu; tap_text "सेटिंग्स"; sleep 3
for i in 1 2 3; do find_node id settingGlassOn > /dev/null 2>&1 && break; adb shell input swipe 540 1800 540 1100 500; sleep 1; done
tap settingGlassOn
sleep 6
snap 2-glass-off
tap filterChip; sleep 4
snap 2-glass-off-filters
adb shell input keyevent BACK; sleep 2
menu; tap_text "सेटिंग्स"; sleep 3
for i in 1 2 3; do find_node id settingGlassOn > /dev/null 2>&1 && break; adb shell input swipe 540 1800 540 1100 500; sleep 1; done
snap 2-settings-glass-off
tap settingGlassOn
sleep 6
snap 2-glass-on-again

# 3) The sky switched back on moves again.
menu; tap_text "सेटिंग्स"; sleep 3
tap settingSky; sleep 2
adb shell input keyevent BACK; sleep 3
raw /tmp/d1; sleep 3; raw /tmp/d2
echo "3 switched on again: changed bytes in 3 s = $(band /tmp/d1 /tmp/d2)"
kill %1 2>/dev/null
