#!/usr/bin/env bash
# Temporary review helper: stock news — the list's 📰 marks, the detail sheet's खबरें, the watchlist's morning
# round-up and big-filing alert, and Menu → स्कैन's switch.
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
news() { adb shell am broadcast -f 0x20 -n "$PKG/.NewsTestReceiver" --es kind "$1" --es symbols "${2:-}" > /dev/null; }
swipe_up() { adb shell input swipe 540 1900 540 1000 500; sleep 1; }
# Swipes up until the view with id $1 sits in the upper part of the screen (at most 20 swipes).
scroll_to() {
  for i in $(seq 1 20); do
    local xy; xy=$(center id "$1") && [ "${xy#* }" -lt 1300 ] && return 0
    swipe_up
  done
  echo "could not scroll to $1" | tee -a "$LOG"
}
news_notes() {
  adb shell dumpsys notification --noredact | grep -E "android\.(title|text|textLines|subText)=" | grep -iE "📰|🗞|ख़बर|·" >> "$LOG"
}

adb shell cmd uimode night yes
adb logcat -c
launch; sleep 6

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
echo "starred: $PICK" | tee -a "$LOG"
news star "$PICK"
sleep 3
launch; sleep 12
tap watchlistChip
shot 02-watchlist-marks 10
echo "marks: $(texts newsBadge | tr '\n' '|')" | tee -a "$LOG"

# 3. The first starred stock's detail, down to its खबरें.
mapfile -t SYMS < <(texts symbol)
FIRST=${SYMS[0]:-${PICK%%,*}}
echo "opening $FIRST" | tee -a "$LOG"
tap symbol
sleep 10
shot 03-detail-top 0
scroll_to detailNewsSection
shot 04-detail-news 6
echo "titles: $(texts newsTitle | head -12 | tr '\n' '|')" | tee -a "$LOG"
echo "kinds: $(texts newsKind | tr '\n' '|') timing: $(texts newsTiming | tr '\n' '|')" | tee -a "$LOG"
swipe_up
shot 05-detail-news-more 2
swipe_up
shot 06-detail-news-end 2
adb shell input keyevent KEYCODE_BACK; sleep 2

# 4. The morning round-up and a big filing's alert, with the app out of sight.
adb shell input keyevent KEYCODE_HOME; sleep 3
news digest
sleep 40
shade
shot 07-digest-shade 1
news_notes
adb shell cmd statusbar collapse; sleep 2
news alert "$FIRST"
sleep 15
shade
shot 08-alert-shade 1
news_notes
tap_text "📰"
sleep 8
shot 09-alert-opened 0
adb shell input keyevent KEYCODE_BACK; sleep 2

# 5. Menu → स्कैन: the switch.
tap menuButton
sleep 2
tap_exact "स्कैन"
sleep 3
for i in $(seq 1 12); do
  center text "Watchlist की ख़बरें" > /dev/null && break
  swipe_up
done
swipe_up
shot 10-scan-sheet-switch 2
adb logcat -d -s NewsTest:* >> "$LOG"
