#!/usr/bin/env bash
# Temporary: the branch's debug build on an emulator with the app set to Bengali (as a user of a removed language
# would be), then a start: Hinglish expected; Menu → भाषा और Font with three languages; हिन्दी and English picked.
# Removed before merging.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=lang-check
mkdir -p "$OUT"
LOG="$OUT/log.txt"
: > "$LOG"
adb root > /dev/null 2>&1; sleep 3
adb shell svc power stayon true

dump() {
  adb shell rm -f /sdcard/ui.xml
  : > /tmp/ui.xml
  for attempt in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; then adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml; return 0; fi
    sleep 2
  done
}
center() {
  dump
  python3 - "$1" "$2" <<'PY'
import html, re, sys
mode, want = sys.argv[1], sys.argv[2]
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for n in re.finditer(r'<node [^>]*>', xml):
    node = n.group(0)
    rid = re.search(r'resource-id="([^"]*)"', node)
    t = re.search(r' text="([^"]*)"', node)
    text = html.unescape(t.group(1)) if t else ''
    if (mode == 'id' and rid and rid.group(1).endswith(':id/' + want)) or (mode == 'text' and text == want):
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
        if y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(1)
PY
}
unstall() {
  for k in 1 2 3; do
    dump
    grep -q "t responding" /tmp/ui.xml || return 0
    grep -q "Share Scan isn" /tmp/ui.xml && echo "FINDING: Share Scan isn't responding" | tee -a "$LOG"
    local xy; xy=$(center text "Close app") && adb shell input tap $xy
    sleep 3
  done
}
tap() { unstall; local xy; xy=$(center "$@") && adb shell input tap $xy && echo "tapped $2" >> "$LOG" || echo "could not find $2" | tee -a "$LOG"; }
texts() { unstall; dump; python3 - <<'PY'
import html, re
xml = open('/tmp/ui.xml', encoding='utf-8').read()
for m in re.finditer(r' text="([^"]*)"', xml):
    t = html.unescape(m.group(1)).strip()
    if t:
        print("  | " + t.replace("\n", " ")[:120])
PY
}
shot() { sleep 2; unstall; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1" >> "$LOG"; }
step() { echo "=== $1" | tee -a "$LOG"; }
locales() { echo "  app locales: $(adb shell cmd locale get-app-locales "$PKG" 2>&1 | tr -d '\r')" >> "$LOG"; }
crashes() { local c; c=$(adb logcat -b crash -d 2>&1 | grep -v '^--------- beginning'); [ -n "$c" ] && { echo "CRASH:" >> "$LOG"; echo "$c" | head -30 >> "$LOG"; }; adb logcat -b crash -c; }
# Back at the top of the list (the header with the Menu button in view), then the Menu.
menu() {
  for k in 1 2 3; do adb shell input swipe 540 700 540 1900 300; sleep 1; done
  if center id compactMenu > /dev/null; then tap id compactMenu; else tap id menuButton; fi
  sleep 3
}
language_sheet() {
  menu
  for k in 1 2 3 4 5 6; do
    center text "$1" > /dev/null && break
    adb shell input swipe 540 1800 540 900 400; sleep 1
  done
  tap text "$1"; sleep 3
}

adb install -r app-debug.apk | tee -a "$LOG"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
sleep 30
unstall

step "first start"
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 12
locales

step "the app set to Bengali, as a user of a removed language is"
adb shell am force-stop "$PKG"
f=/data/data/$PKG/shared_prefs/scan.xml
adb shell cat "$f" > /tmp/p.xml
python3 - <<'PY'
import re
xml = open('/tmp/p.xml', encoding='utf-8').read()
xml = re.sub(r'\s*<string name="language">[^<]*</string>', '', xml)
xml = xml.replace('</map>', '    <string name="language">bn</string>\n</map>')
open('/tmp/p.xml', 'w', encoding='utf-8').write(xml)
PY
uid=$(adb shell stat -c %u "/data/data/$PKG" | tr -d '\r')
adb push /tmp/p.xml "$f" > /dev/null
adb shell chown "$uid:$uid" "$f"; adb shell chmod 660 "$f"; adb shell restorecon "$f"
adb shell cmd locale set-app-locales "$PKG" --locales bn
locales

step "a start after the update: Hinglish expected"
adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; sleep 15
locales
adb shell grep language "$f" | sed 's/^/  pref: /' >> "$LOG"
texts | head -12 >> "$LOG"; shot 01-after-update
crashes

step "Menu → भाषा और Font: three languages"
language_sheet "भाषा और Font"
texts | head -24 >> "$LOG"; shot 02-languages

step "हिन्दी picked"
tap text "हिन्दी"; sleep 8
locales
texts | head -10 >> "$LOG"; shot 03-hindi
crashes

step "English picked"
language_sheet "भाषा और फ़ॉन्ट"
tap text "English"; sleep 8
locales
texts | head -10 >> "$LOG"; shot 04-english
crashes

step "back to Hinglish"
language_sheet "Language and font"
tap text "Hinglish"; sleep 8
locales
texts | head -6 >> "$LOG"; shot 05-hinglish
crashes
echo "done" | tee -a "$LOG"
