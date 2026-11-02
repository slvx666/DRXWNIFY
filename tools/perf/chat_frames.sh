#!/usr/bin/env bash
# Frame times of the chat screen, scenario by scenario (adb dumpsys gfxinfo).
# Usage: tools/perf/chat_frames.sh <package> <scenario...>
#   scenarios: suggest (type "hm" → the "hmm" sticker hint, then erase it), open (back and into the chat)
# The chat must already be open with the field at FIELD_X/FIELD_Y (screen px).
set -u
A="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1
PKG="$1"; shift
FIELD_X="${FIELD_X:-311}"; FIELD_Y="${FIELD_Y:-2087}"
PEER="${PEER:-e114d6d2c422913fbb0c5b525cf927015aaecdfbea72fadd5c1dd2693f1c7f74}"
report() {
  "$A" shell dumpsys gfxinfo "$PKG" | grep -E "Total frames rendered|Janky frames:|50th percentile|90th percentile|95th percentile|99th percentile" | sed "s/^/  [$1] /" | grep -v legacy
}
for s in "$@"; do
  case "$s" in
    suggest)
      "$A" shell input tap "$FIELD_X" "$FIELD_Y"; sleep 2
      "$A" shell dumpsys gfxinfo "$PKG" reset >/dev/null
      for i in 1 2 3 4 5; do
        "$A" shell input text hm; sleep 1.2
        "$A" shell input keyevent 67 67; sleep 1.2
      done
      report suggest ;;
    open)
      "$A" shell input keyevent 111; sleep 1
      "$A" shell dumpsys gfxinfo "$PKG" reset >/dev/null
      for i in 1 2 3; do
        "$A" shell input keyevent 4; sleep 1.5
        "$A" shell am start -n "$PKG/com.metrolist.music.MainActivity" --es meld_route "chat/$PEER" >/dev/null; sleep 2.5
      done
      report open ;;
  esac
done
