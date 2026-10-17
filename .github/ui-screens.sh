#!/usr/bin/env bash
# Temporary review helper: installs a low-numbered release build, lets the banner update it to the
# latest GitHub release and saves screenshots plus the before/after versions to ui-screens/.
set -uo pipefail
PKG=com.rohit.share_scan
OUT=ui-screens
mkdir -p "$OUT"

adb shell svc power stayon true
adb shell settings put system screen_off_timeout 1800000
adb install -r app/build/outputs/apk/release/app-release.apk

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
launch() { adb shell am force-stop "$PKG"; adb shell am start -W -n "$PKG/.MainActivity" > /dev/null; }
version() { adb shell dumpsys package "$PKG" | grep -E 'versionCode|versionName' | head -2; }
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
# Waits up to $2 seconds for a node with id or text $1 to appear.
wait_for() {
  for i in $(seq 1 "$2"); do
    find_node id "$1" > /dev/null 2>&1 && return 0
    find_node text "$1" > /dev/null 2>&1 && return 0
    sleep 1
  done
  echo "timed out waiting for $1"
  return 1
}

{ echo "before:"; version; } > "$OUT/versions.txt"

launch
wait_for updateBanner 30
shot 01-banner 2

tap updateAction
shot 02-downloading 1

# The installer opens by itself once the download is checked; without the permission it asks for it first.
sleep 12
shot 03-installer-permission 2
adb shell input keyevent KEYCODE_BACK
sleep 3
shot 04-banner-ready 2

adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
tap updateAction
wait_for Update 20
shot 05-installer-confirm 2
tap_text Update
sleep 25
shot 06-installed 2

{ echo "after:"; version; } >> "$OUT/versions.txt"
adb logcat -d | grep -i -E "share_scan|PackageInstaller|AndroidRuntime" | tail -150 > "$OUT/log.txt"
cat "$OUT/versions.txt"
