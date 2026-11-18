#!/bin/bash
# Emulator smoke test: start, search, stream a song, background playback, every tab, monkey.
PKG=com.ibrata2.ibradl
ACT=$PKG/.MainActivity
mkdir -p shots
shot() { adb exec-out screencap -p > "shots/$1.png"; }
tap_text() {   # tap the first on-screen element whose text contains $1
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb shell cat /sdcard/u.xml > /tmp/u.xml
  b=$(python3 - "$1" <<'PY'
import re,sys
x=open('/tmp/u.xml',encoding='utf-8').read()
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    if sys.argv[1] in m.group(1):
        a,b,c,d=map(int,m.groups()[1:]); print((a+c)//2,(b+d)//2); break
PY
)
  echo "tap '$1' -> $b"
  [ -n "$b" ] && adb shell input tap $b
}
adb install -r -g app-x86_64-release.apk
adb logcat -G 16M || true
adb logcat -c
adb shell am start -n $ACT
sleep 45
shot 01-home-search
read W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')
echo "screen $W x $H"
tap_text "·"      # first search result (its sub-line has "artist · duration")
sleep 40
shot 02-playing
adb shell am start -n $ACT --ez openPlayer true
sleep 4
shot 03-full-player
adb shell dumpsys media_session | grep -A12 "com.ibrata2.ibradl" | grep -E "state=|description=" | head -6 > shots/session-before.txt
adb shell input keyevent KEYCODE_HOME
sleep 15
adb shell dumpsys media_session | grep -A12 "com.ibrata2.ibradl" | grep -E "state=|description=" | head -6 > shots/session-background.txt
adb shell cmd statusbar expand-notifications
sleep 3
shot 04-notification
adb shell cmd statusbar collapse
adb shell am start -n $ACT
sleep 3
for t in "مكتبتي" "القوائم" "التحميلات" "حول"; do
  tap_text "$t"; sleep 4; shot "05-tab-$t"
done
tap_text "القوائم"; sleep 2
tap_text "قائمة تشغيل جديدة"; sleep 2; shot 06-new-playlist-dialog
adb shell input keyevent KEYCODE_BACK
adb shell monkey -p $PKG --pct-syskeys 0 --throttle 200 -s 42 300 || true
sleep 3
shot 07-after-monkey
adb logcat -d > shots/logcat.txt
grep -E "IBRADL|ibradl|ExoPlayerImplInternal|youtubedl|AndroidRuntime" shots/logcat.txt | tail -300 > shots/app-log.txt
grep -n -A40 "FATAL EXCEPTION" shots/logcat.txt > shots/crash.txt || true
grep -n -B2 -A30 "ANR in com.ibrata2" shots/logcat.txt >> shots/crash.txt || true
echo "===== SESSION ====="; cat shots/session-*.txt
echo "===== CRASHES ====="; cat shots/crash.txt
exit 0
