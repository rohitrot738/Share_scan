#!/usr/bin/env bash
# Temporary review helper: each part of a stock's score opened in its detail sheet, with the figures behind it.
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
# Every text on the screen, a line each.
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
# A slow drag of about 400 px, without a fling.
drag_up() { adb shell input swipe 540 1700 540 1300 1200; sleep 1; }
# Drags up until the view whose text contains $1 sits just under the sheet's fixed header (at most 40 drags).
scroll_to() {
  for i in $(seq 1 40); do
    local xy; xy=$(center text "$1") && [ "${xy#* }" -lt 1350 ] && return 0
    drag_up
  done
  echo "could not scroll to $1" | tee -a "$LOG"
}

adb shell cmd uimode night yes
launch; sleep 6

# 1. A fresh scan, so that every stock keeps the figures behind its score.
tap startButton
for i in $(seq 1 150); do
  sleep 5
  center text "दोबारा स्कैन करें" > /dev/null && break
done
echo "scan done after ~$((i * 5))s" | tee -a "$LOG"
shot 01-list 6

# 2. The top stock's detail, down to "Score कैसे बना"; then each part opened in turn, going down (each stays open).
tap symbol
sleep 8
scroll_to "Score कैसे बना"
shot 02-score-parts 2
n=3
for part in "Breakout के क़रीब" "Trend (रुझान)" "Volume उछाल" "Liquidity (तरलता)" "Momentum (तेज़ी)" "Support bonus"; do
  scroll_to "$part"
  tap_text "$part"
  sleep 2
  shot "$(printf '%02d' $n)-$(echo "$part" | cut -d' ' -f1 | tr 'A-Z' 'a-z')" 1
  all_texts | grep "^•" >> "$LOG"
  drag_up; drag_up; drag_up
  shot "$(printf '%02d' $n)-$(echo "$part" | cut -d' ' -f1 | tr 'A-Z' 'a-z')-more" 1
  all_texts | grep -E "^(दिन|[0-9]+ [A-Z][a-z]{2})" | head -3 >> "$LOG"
  n=$((n + 1))
done
