#!/usr/bin/env bash
# Temporary review helper (run 4, no stars): the Space Glass theme and the compact list, on a fresh scan. Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/*.txt
LOG="$OUT/log.txt"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS  # no permission dialog over the screens

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" | tee -a "$LOG"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
dump() {
  for attempt in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && break
    sleep 2
  done
  adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
}
# The centre of the first view whose resource id is $2 (mode "id") or whose text contains $2 (mode "text").
center() {
  dump
  python3 - "$1" "$2" <<'PY'
import html, re, sys
mode, want = sys.argv[1], sys.argv[2]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    rid = re.search(r'resource-id="([^"]*)"', n)
    text = html.unescape(re.search(r' text="([^"]*)"', n).group(1)) if re.search(r' text="([^"]*)"', n) else ''
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and want in text):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        if y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
all_texts() {
  dump
  python3 - <<'PY'
import html, re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r' text="([^"]*)"', xml):
    t = html.unescape(m.group(1)).strip()
    if t:
        print(t)
PY
}
tap() { local xy; xy=$(center id "$1") && adb shell input tap $xy || echo "could not find id $1" | tee -a "$LOG"; }
tap_text() { local xy; xy=$(center text "$1") && adb shell input tap $xy || echo "could not find text $1" | tee -a "$LOG"; }

# The Space Glass theme with the compact list, as picking the theme leaves them.
cat > /tmp/scan.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="theme">space_glass</string>
    <boolean name="compact_list" value="true" />
</map>
XML
adb push /tmp/scan.xml /data/local/tmp/scan.xml
# One command each: adb shell joins its arguments, so a quoted "sh -c '...'" would lose its quotes on the device.
adb shell run-as "$PKG" mkdir -p shared_prefs
adb shell run-as "$PKG" cp /data/local/tmp/scan.xml shared_prefs/scan.xml
adb shell run-as "$PKG" cat shared_prefs/scan.xml | tee -a "$LOG"

adb shell cmd uimode night yes
launch; sleep 8
shot 01-space-idle 2

# The status pill opens the scan panel; SCAN from there.
tap statusPill
shot 02-scan-panel 3
tap startButton
shot 03-scanning 12
adb shell input keyevent KEYCODE_BACK
for i in $(seq 1 150); do
  sleep 5
  center text "100%" > /dev/null && break
done
echo "scan done after ~$((i * 5 + 12))s" | tee -a "$LOG"
adb shell am start -n "$PKG/.MainActivity" > /dev/null  # back to the front, should Back have left it
shot 04-compact-list 6
all_texts | head -60 >> "$LOG"
adb shell input swipe 540 1600 540 1000 800
shot 05-list-scrolled 2

# A line opens the stock's detail, in the new theme.
tap rowSymbol
shot 06-detail 7
adb shell input keyevent KEYCODE_BACK
sleep 2

# The theme picker with Space Glass in it.
tap compactMenu
sleep 2
tap_text "थीम"
sleep 2
for i in 1 2 3; do adb shell input swipe 540 1800 540 900 600; sleep 1; done
shot 07-theme-picker 2
adb shell input keyevent KEYCODE_BACK
sleep 2

# Space Glass with the cards (Settings → Compact list off).
tap compactMenu
sleep 2
tap_text "सेटिंग्स"
sleep 2
tap_text "Compact list"
shot 08-space-cards 8
