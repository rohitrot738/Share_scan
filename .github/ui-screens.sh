#!/usr/bin/env bash
# Temporary review helper: the stock detail's खबरें tab — opened from a card's 📰 mark, a busy and a quiet stock (at
# least ten items), and opened from a news alert.
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
# The centre of the first view whose resource id is $2 (mode "id"), whose text contains $2 (mode "text") or whose
# text is exactly $2 (mode "exact").
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
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and want in text) \
            or (mode == 'exact' and text.strip() == want):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        if y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
# The texts of every view whose resource id is $1, in screen order.
texts() {
  dump
  python3 - "$1" <<'PY'
import html, re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for node in re.finditer(r'<node [^>]*>', xml):
    n = node.group(0)
    rid = re.search(r'resource-id="([^"]*)"', n)
    if rid and rid.group(1).endswith(':id/' + sys.argv[1]):
        print(html.unescape(re.search(r' text="([^"]*)"', n).group(1)))
PY
}
shade() { adb shell cmd statusbar expand-notifications; sleep 2; }
tap() { local xy; xy=$(center id "$1") && adb shell input tap $xy || echo "could not find id $1" | tee -a "$LOG"; }
tap_text() { local xy; xy=$(center text "$1") && adb shell input tap $xy || echo "could not find text $1" | tee -a "$LOG"; }
tap_exact() { local xy; xy=$(center exact "$1") && adb shell input tap $xy || echo "could not find exact $1" | tee -a "$LOG"; }
news() { adb shell am broadcast -f 0x20 -n "$PKG/.NewsTestReceiver" --es kind "$1" --es symbols "${2:--}" | tail -1 >> "$LOG"; }
swipe_up() { adb shell input swipe 540 1900 540 1000 500; sleep 1; }
# A slow drag that moves the page about 600 px without a fling.
drag_up() { adb shell input swipe 540 1700 540 1100 1500; sleep 1; }
# Drags up until the view whose text is exactly $1 sits in the upper half of the screen (at most 30 drags).
scroll_to() {
  for i in $(seq 1 30); do
    local xy; xy=$(center exact "$1") && [ "${xy#* }" -lt 1100 ] && return 0
    drag_up
  done
  echo "could not scroll to $1" | tee -a "$LOG"
}
# The texts of the detail sheet's tabs.
tab_labels() {
  dump
  python3 - <<'PY'
import html, re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
texts = [html.unescape(m.group(1)) for m in re.finditer(r' text="([^"]*)"', xml)]
print(' | '.join(t for t in texts if t.startswith('खबरें') or t == 'जानकारी'))
PY
}
# Drags down the news tab, counting the distinct titles seen; a screenshot ($1) after two drags.
count_items() {
  : > /tmp/titles.txt
  texts newsTitle >> /tmp/titles.txt
  for i in $(seq 1 8); do
    drag_up
    texts newsTitle >> /tmp/titles.txt
    [ "$i" = 2 ] && shot "$1" 1
  done
  echo "items seen in the tab: $(sort -u /tmp/titles.txt | grep -c .)" | tee -a "$LOG"
  echo "more/routine lines: $(dump; grep -oE 'और [0-9]+ ख़बरें दिखाएँ|\+[0-9]+ सामान्य सूचनाएँ' /tmp/ui.xml | tr '\n' '|')" | tee -a "$LOG"
}
news_notes() {
  adb shell dumpsys notification --noredact | grep -E "android\.(title|text|textLines|subText)=" | grep -iE "📰|🗞|ख़बर|·" >> "$LOG"
}

adb shell cmd uimode night yes
adb logcat -c
launch; sleep 6

# 0. How each news source answers the app on this device.
news probe
for i in $(seq 1 30); do
  sleep 4
  adb logcat -d -s NewsTest:* | grep -q "probe repo articles" && break
done
adb logcat -d -s NewsTest:* | grep probe | tee -a "$LOG"

# 1. A scan, then the list as it comes out of it.
tap startButton
for i in $(seq 1 150); do
  sleep 5
  center text "दोबारा स्कैन करें" > /dev/null && break
done
echo "scan done after ~$((i * 5))s" | tee -a "$LOG"
shot 01-list-top 8
echo "marks on the first screen: $(texts newsBadge | tr '\n' '|')" | tee -a "$LOG"

# 2. Which stocks carry a mark: star up to five of them, kinds told apart first.
news badges
sleep 20
adb logcat -d -s NewsTest:I > "$OUT/badges.txt"
echo "stocks with a mark: $(grep -c ' badge ' "$OUT/badges.txt")" | tee -a "$LOG"
PICK=$(python3 - "$OUT/badges.txt" <<'PY'
import re, sys
order = ['RESULTS', 'ORDER', 'DIVIDEND', 'FUNDRAISE', 'DEAL', 'EXPANSION', 'MANAGEMENT', 'RATING', 'LEGAL', 'MEETING', 'OTHER']
rows = [m.groups() for m in re.finditer(r' badge (\S+) (\S+) (\d+)', open(sys.argv[1], encoding='utf-8').read())]
rows.sort(key=lambda r: (order.index(r[1]) if r[1] in order else 99, -int(r[2])))
picked, kinds = [], set()
for symbol, kind, _ in rows:
    if kind not in kinds and len(picked) < 5:
        picked.append(symbol); kinds.add(kind)
for symbol, kind, _ in rows:
    if symbol not in picked and len(picked) < 5:
        picked.append(symbol)
print(','.join(picked))
PY
)
# Without marks (the market feed did not answer), the first stocks of the list and two big names instead.
if [ -z "$PICK" ]; then
  launch; sleep 10
  PICK=$( (texts symbol; echo RELIANCE; echo TCS) | head -5 | paste -sd, -)
fi
echo "starred: $PICK" | tee -a "$LOG"
news star "$PICK"
sleep 3
launch; sleep 12
tap watchlistChip
shot 02-watchlist-marks 10
echo "marks: $(texts newsBadge | tr '\n' '|')" | tee -a "$LOG"

drag_up; drag_up
shot 03-watchlist-marks-more 2
echo "marks: $(texts newsBadge | tr '\n' '|')" | tee -a "$LOG"

# 3. The first card's 📰 mark opens its stock on the खबरें tab; down its list, then over to जानकारी.
FIRST=$(texts symbol | head -1)
echo "opening $FIRST from its mark" | tee -a "$LOG"
tap newsBadge
sleep 12
shot 04-news-tab 0
echo "tab labels: $(tab_labels)" | tee -a "$LOG"
echo "status: $(texts detailNewsStatus)" | tee -a "$LOG"
count_items 05-news-tab-more
tap_text "जानकारी"
sleep 3
shot 06-overview-tab 2
adb shell input keyevent KEYCODE_BACK; sleep 2

# 4. A quiet stock (few news): its tab reaches back a year to show at least ten.
news quiet
for i in $(seq 1 60); do
  sleep 5
  adb logcat -d -s NewsTest:* | grep -q " quiet [A-Z0-9&-]*$" && break
done
adb logcat -d -s NewsTest:* | grep "quiet" | tee -a "$LOG"
QUIET=$(adb logcat -d -s NewsTest:* | grep -oE " quiet [A-Z0-9&-]+$" | tail -1 | awk '{print $2}')
echo "quiet stock: $QUIET" | tee -a "$LOG"
if [ -n "$QUIET" ]; then
  # On the watchlist alone, so that the list shows it whatever the filters.
  news star "$QUIET"
  sleep 3
  launch; sleep 12
  tap watchlistChip
  sleep 4
  xy=$(center exact "$QUIET") && adb shell input tap $xy || tap symbol
  sleep 4
  tap_text "खबरें"
  sleep 10
  shot 07-quiet-news-tab 0
  echo "quiet tab labels: $(tab_labels)" | tee -a "$LOG"
  echo "quiet status: $(texts detailNewsStatus)" | tee -a "$LOG"
  count_items 08-quiet-news-more
  adb shell input keyevent KEYCODE_BACK; sleep 2
fi

# 5. A big filing's alert opens its stock on the खबरें tab (the marked stocks starred again, so that it is listed).
news star "$PICK"
sleep 3
launch; sleep 10
adb shell input keyevent KEYCODE_HOME; sleep 3
news alert "$FIRST"
sleep 15
shade
shot 09-alert-shade 1
news_notes
tap_text "📰"
sleep 10
shot 10-alert-opened 0
echo "after the alert: $(tab_labels) | status: $(texts detailNewsStatus)" | tee -a "$LOG"
adb logcat -d -s NewsTest:* >> "$LOG"
