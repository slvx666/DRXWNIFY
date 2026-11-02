#!/usr/bin/env bash
# Frame times of the chat screen, scenario by scenario (adb dumpsys gfxinfo).
# Usage: tools/perf/chat_frames.sh <package> <scenario...>
#   suggest  type "hm" → the "hmm" sticker hint, then erase it
#   open     back to the chat list and into the chat again (by a tap on its first row)
#   scroll   flings up through the history and back down
#   keyboard the keyboard up and down
#   menu     a tap on the newest message (the menu with reactions), then away
#   stickers the sticker panel open and closed
#   music    the music panel open and closed
#   send     three short messages typed and sent
#   swipe    a swipe-to-reply on the newest message (the reply is left set)
# The chat must already be open; the coordinates are screen px of a 1080x2436 phone (TECNO CM7),
# override them by the variables below.
set -u
A="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1
PKG="$1"; shift
FIELD_X="${FIELD_X:-311}"; FIELD_Y="${FIELD_Y:-2087}"
EMOJI_X="${EMOJI_X:-77}"; MUSIC_X="${MUSIC_X:-1003}"
MSG_X="${MSG_X:-860}"; MSG_Y="${MSG_Y:-1820}"
ROW_Y="${ROW_Y:-542}"
SEND_Y="${SEND_Y:-1523}"  # the send button with the keyboard up
# Totals, and from the histogram the frames a person sees as a hitch (the screen runs at 120 Hz, so
# a frame over 8 ms is already late; over 17 ms is a dropped 60 Hz frame).
report() {
  "$A" shell dumpsys gfxinfo "$PKG" > "${TMPDIR:-/tmp}/gfx.txt"
  grep -E "Total frames rendered|Janky frames:|90th percentile|99th percentile" "${TMPDIR:-/tmp}/gfx.txt" | grep -v legacy | sed "s/^/  [$1] /"
  grep -m1 "^HISTOGRAM:" "${TMPDIR:-/tmp}/gfx.txt" | tr ' ' '\n' | awk -F'[=m]' -v n="$1" '
    $1 ~ /^[0-9]+$/ { ms = $1; c = $3; if (ms >= 17) a += c; if (ms >= 25) b += c; if (ms >= 33) d += c; if (c > 0) max = ms }
    END { printf "  [%s] >=17ms: %d  >=25ms: %d  >=33ms: %d  max: %dms\n", n, a, b, d, max }'
}
reset() { "$A" shell dumpsys gfxinfo "$PKG" reset >/dev/null; }
key() { "$A" shell input keyevent "$@"; }
tap() { "$A" shell input tap "$1" "$2"; }
# Back closes the keyboard (Escape doesn't with every keyboard); only when it's up, else back leaves.
hide_ime() { "$A" shell dumpsys input_method | grep -q "mInputShown=true" && key 4; sleep 1; }
for s in "$@"; do
  case "$s" in
    suggest)
      tap "$FIELD_X" "$FIELD_Y"; sleep 2
      reset
      for i in 1 2 3 4 5; do
        "$A" shell input text hm; sleep 1.2
        key 67 67; sleep 1.2
      done
      report suggest; hide_ime ;;
    open)
      reset
      for i in 1 2 3; do
        key 4; sleep 1.5
        tap 500 "$ROW_Y"; sleep 2.5
      done
      report open ;;
    scroll)
      reset
      for i in 1 2 3; do
        "$A" shell input swipe 540 700 540 1700 120; sleep 1.2
      done
      for i in 1 2 3; do
        "$A" shell input swipe 540 1700 540 700 120; sleep 1.2
      done
      report scroll ;;
    keyboard)
      reset
      for i in 1 2 3; do
        tap "$FIELD_X" "$FIELD_Y"; sleep 1.5
        hide_ime; sleep 0.5
      done
      report keyboard ;;
    menu)
      reset
      for i in 1 2 3; do
        tap "$MSG_X" "$MSG_Y"; sleep 1.2
        key 4; sleep 1.2
      done
      report menu ;;
    stickers)
      reset
      for i in 1 2 3; do
        tap "$EMOJI_X" "$FIELD_Y"; sleep 1.5
        key 4; sleep 1.5
      done
      report stickers ;;
    music)
      reset
      for i in 1 2 3; do
        tap "$MUSIC_X" "$FIELD_Y"; sleep 2
        key 4; sleep 1.5
      done
      report music ;;
    send)
      tap "$FIELD_X" "$FIELD_Y"; sleep 1.5
      reset
      for i in 1 2 3; do
        "$A" shell input text "perf$i"; sleep 0.6
        tap "$MUSIC_X" "$SEND_Y"; sleep 1.5
      done
      report send; hide_ime ;;
    swipe)
      reset
      for i in 1 2 3; do
        "$A" shell input swipe 900 "$MSG_Y" 500 "$MSG_Y" 250; sleep 1.5
        hide_ime
      done
      report swipe ;;
  esac
done
