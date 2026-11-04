#!/usr/bin/env bash
# Temporary review helper: Paper Trading (the header's button, the account, new orders of each kind, the tabs, the trade
# window's paper button and the menu entry). Works with the market open (orders fill) or closed (they wait, AMO).
# Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"
rm -f "$OUT"/*.png "$OUT"/log.txt
LOG="$OUT/log.txt"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS  # no permission dialog over the screens

shot() { sleep "${2:-3}"; unstall; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" | tee -a "$LOG"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
# Brings the app back to the front should a Back have left it.
front() {
  adb shell dumpsys activity activities | grep -qE "ResumedActivity.*$PKG" && return
  echo "app was not in front" | tee -a "$LOG"
  adb shell am start -n "$PKG/.MainActivity" > /dev/null
  sleep 4
}
# A failed dump leaves an empty file, never the last screen's.
dump() {
  adb shell rm -f /sdcard/ui.xml
  : > /tmp/ui.xml
  for attempt in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; then
      adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
      return 0
    fi
    sleep 2
  done
  echo "ui dump failed" | tee -a "$LOG"
}
# What the emulator is busy with, and why anything stopped responding.
diag() {
  echo "--- $1" >> "$LOG"
  adb shell top -b -n 1 -m 8 >> "$LOG" 2>&1
  adb logcat -d -s ActivityManager:E | grep -A 14 "ANR in" | head -60 >> "$LOG"
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
    row = rid and rid.group(1).split(':id/')[-1] in ('rowSymbol', 'symbol', 'accSymbol', 'bentoSymbol', 'swipeSymbol')
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and want in text) \
            or (mode == 'exact' and text == want) or (mode == 'row' and row and text == want):
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
# The emulator's own apps can stall ("Pixel Launcher isn't responding"); close them so the dialog leaves the screen.
unstall() {
  dump
  grep -q "t responding" /tmp/ui.xml || return 0
  echo "closed a not-responding app" | tee -a "$LOG"
  local xy; xy=$(center text "Close app") && adb shell input tap $xy
  sleep 3
}
# Long-presses the lines at the given places (0 = the first line on screen), which puts them on the watchlist.
star_rows() {
  dump
  python3 - "$@" <<'PY' | while read -r x y; do adb shell input swipe "$x" "$y" "$x" "$y" 900 < /dev/null; sleep 1; done
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8').read()
want = {int(a) for a in sys.argv[1:]}
rows = re.findall(r'<node [^>]*resource-id="[^"]*:id/rowSymbol"[^>]*>', xml)
for i, node in enumerate(rows):
    if i in want:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
PY
}
tap_exact() { unstall; local xy; xy=$(center exact "$1") && adb shell input tap $xy || echo "could not find exactly $1" | tee -a "$LOG"; }
tap() { unstall; local xy; xy=$(center id "$1") && adb shell input tap $xy || echo "could not find id $1" | tee -a "$LOG"; }
tap_text() { unstall; local xy; xy=$(center text "$1") && adb shell input tap $xy || echo "could not find text $1" | tee -a "$LOG"; }

# Changes one preference with the app stopped: setpref NAME string|boolean VALUE ("-" removes it).
setpref() {
  adb shell am force-stop "$PKG"
  adb exec-out run-as "$PKG" cat shared_prefs/scan.xml > /tmp/p.xml
  python3 - "$1" "$2" "$3" <<'PY'
import re, sys
name, kind, value = sys.argv[1:]
xml = open('/tmp/p.xml', encoding='utf-8').read()
xml = re.sub(r'\s*<[a-z]+ name="%s"[^>]*?(/>|>[^<]*</[a-z]+>)' % re.escape(name), '', xml)
if value != '-':
    line = f'<string name="{name}">{value}</string>' if kind == 'string' else f'<{kind} name="{name}" value="{value}" />'
    xml = xml.replace('</map>', f'    {line}\n</map>')
open('/tmp/p.xml', 'w', encoding='utf-8').write(xml)
PY
  adb push /tmp/p.xml /data/local/tmp/p.xml > /dev/null
  adb shell run-as "$PKG" cp /data/local/tmp/p.xml shared_prefs/scan.xml
  echo "pref $1=$3" | tee -a "$LOG"
}
relaunch() { launch; sleep "${1:-8}"; }
# Opens the search and types $1, then opens the line of the stock $1.
open_stock() {
  front
  center id searchClose > /dev/null && tap searchClose && sleep 2  # a search left open from the stock before
  tap "$SEARCH"
  sleep 2
  adb shell input text "$1"
  sleep 3
  unstall
  local xy; xy=$(center row "$1") && adb shell input tap $xy || echo "could not find the line $1" | tee -a "$LOG"
}

cat > /tmp/scan.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="theme">galaxy</string>
    <string name="background">space_glass</string>
</map>
XML
adb shell run-as "$PKG" mkdir -p shared_prefs
adb push /tmp/scan.xml /data/local/tmp/scan.xml
adb shell run-as "$PKG" cp /data/local/tmp/scan.xml shared_prefs/scan.xml

adb shell cmd uimode night yes
adb shell input keyevent KEYCODE_HOME
sleep 20
launch; sleep 10
echo "IST now: $(TZ=Asia/Kolkata date '+%F %H:%M')" | tee -a "$LOG"

# A scan, from the pill's panel.
tap statusPill
sleep 3
tap startButton
sleep 10
adb shell input keyevent KEYCODE_BACK
for i in $(seq 1 150); do
  sleep 5
  unstall
  center text "100%" > /dev/null && break
done
echo "scan done after ~$((i * 5 + 10))s" | tee -a "$LOG"
adb shell am start -n "$PKG/.MainActivity" > /dev/null
shot 01-list 6

SEARCH=compactSearch
# Inside a sheet only upward drags: a downward one at the top would close the sheet.
swipe_up() { adb shell input swipe 540 1700 540 800 450; sleep 1; }
# Back closes the keyboard only while it shows; otherwise it would close the sheet under it.
hide_keyboard() {
  if adb shell dumpsys input_method | grep -q 'mInputShown=true'; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi
}
# Scrolls up until the view with id $1 is on screen, then taps it.
tap_scroll() {
  for k in 1 2 3 4 5 6 7 8 9 10 11 12; do
    if center id "$1" > /dev/null; then tap "$1"; return 0; fi
    swipe_up
  done
  echo "never reached id $1" | tee -a "$LOG"
}
show_scroll() {
  for k in 1 2 3 4 5 6 7 8 9 10 11 12; do
    center id "$1" > /dev/null && return 0
    swipe_up
  done
  echo "never reached id $1" | tee -a "$LOG"
}
# Back to the home list: closes sheets and the search, the app relaunched if Back left it.
home() {
  for k in 1 2 3 4; do
    center id statusPill > /dev/null && center id compactMenu > /dev/null && ! center id sheetClose > /dev/null && break
    adb shell input keyevent KEYCODE_BACK
    sleep 2
    front
  done
  center id searchClose > /dev/null && tap searchClose && sleep 2
  hide_keyboard
}


# The price the order form shows ("अभी का भाव ₹1,300.00 (…)"), without commas.
ltp() {
  all_texts | python3 -c "import re,sys; m=re.search(r'अभी का भाव ₹([0-9,]+\.[0-9]+)', sys.stdin.read()); print(m.group(1).replace(',','') if m else '')"
}
times() { python3 -c "print(f'{$1*$2:.2f}')"; }
# Fills the order form's stock: typing the whole symbol picks it; a tap on the title closes the suggestions.
order_stock() {
  tap orderStock
  sleep 1
  adb shell input text "$1"
  sleep 3
  hide_keyboard
  tap orderTitle
  sleep 8
}
field() { tap "$1"; sleep 1; adb shell input text "$2"; hide_keyboard; }

# Paper Trading from the header's button: a new account of ₹10 lakh.
front
tap compactPaper
shot 30-paper-new 8
all_texts >> "$LOG"

# 1) Delivery BUY, market order: fills now while the market is open, else waits for the next open (AMO).
tap paperNewOrder
sleep 3
order_stock RELIANCE
P=$(ltp); echo "RELIANCE price ${P:-none}" | tee -a "$LOG"
tap orderCnc
tap orderBuy
field orderQty 10
if [ -n "$P" ]; then field orderStop "$(times "$P" 0.95)"; field orderTarget "$(times "$P" 1.08)"; fi
shot 31-order-cnc-market 2
all_texts | grep -E '₹|लगेगा|AMO|charges' >> "$LOG"
tap orderPlace
shot 32-placed-cnc 2

# 2) Intraday SELL (short), market order with a stop above and a target below: refused outside 9:15–15:15.
tap paperNewOrder
sleep 3
order_stock TCS
Q=$(ltp); echo "TCS price ${Q:-none}" | tee -a "$LOG"
tap orderSell
tap orderMis
field orderQty 20
if [ -n "$Q" ]; then field orderStop "$(times "$Q" 1.01)"; field orderTarget "$(times "$Q" 0.98)"; fi
shot 33-order-mis-sell 2
tap orderPlace
sleep 3
if center id orderPlace > /dev/null; then
  shot 34-mis-refused 1
  all_texts | grep -E '9:15|15:15' >> "$LOG"
  hide_keyboard
  adb shell input keyevent KEYCODE_BACK
  sleep 2
else
  shot 34-placed-mis 1
fi

# 3) Delivery BUY, limit order 5% under the price: waits in Orders.
tap paperNewOrder
sleep 3
order_stock INFY
R=$(ltp); echo "INFY price ${R:-none}" | tee -a "$LOG"
tap orderCnc
tap orderBuy
tap_exact "Limit"
field orderQty 15
[ -n "$R" ] && field orderPrice "$(times "$R" 0.95)"
shot 35-order-limit 2
tap orderPlace
shot 36-placed-limit 2

# The account and its tabs.
front
shot 37-paper-intraday 3
tap_exact "Delivery"
shot 38-paper-delivery 3
tap_exact "Orders"
shot 39-paper-orders 3
swipe_up
shot 40-paper-orders-more 1
all_texts >> "$LOG"
tap_exact "हिसाब"
shot 41-paper-reckoning 3
swipe_up
shot 42-paper-reckoning-more 1
# A waiting order: a tap offers to cancel it.
tap_exact "Orders"
sleep 2
tap_text "दबाएँ तो रद्द"
shot 43-cancel-ask 2
tap_exact "रहने दें"
sleep 1
# A position, when one is open: its actions.
tap_exact "Delivery"
sleep 2
if center text "दबाएँ: बंद करें" > /dev/null; then tap_text "दबाएँ: बंद करें"; shot 44-position-actions 2; adb shell input keyevent KEYCODE_BACK; sleep 1; fi
home

# The trade window's paper button: an order filled in from the plan.
open_stock RELIANCE
sleep 6
tap detailChartCard
sleep 6
tap_scroll sizePaper
sleep 8
shot 45-trade-window-paper 2
all_texts | grep -E 'plan|₹' >> "$LOG"
home

# The menu's entry and the help.
tap compactMenu
shot 46-menu 3
tap_text "Paper Trading"
shot 47-paper-from-menu 6
home

# Menu → शुरुआती गाइड: the bundled guide in the app.
tap compactMenu
sleep 3
tap_text "शुरुआती गाइड"
shot 48-guide-top 6
for k in 1 2 3; do swipe_up; done
shot 49-guide-scrolled 2
for k in 1 2 3 4 5 6 7 8; do swipe_up; done
shot 50-guide-paper 2
for k in 1 2 3; do swipe_up; done
shot 50b-guide-picture 2
# The contents list's links jump within the page.
tap_text "विषय-सूची"
sleep 2
tap_text "📝 Paper Trading"
shot 50c-guide-jump 3
adb shell input keyevent KEYCODE_BACK
sleep 2
shot 51-guide-back-to-menu 2
home
exit 0
