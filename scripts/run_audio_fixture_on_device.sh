#!/usr/bin/env bash
set -euo pipefail

if [ "${1:-}" = "" ]; then
  echo "Usage: $0 <fixture-id>" >&2
  exit 1
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/audio-fixtures/manifest.json"
FIXTURE_ROOT="$ROOT_DIR/app/src/test/resources/audio-fixtures"
FIXTURE_ID="$1"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to look up audio fixture metadata." >&2
  exit 1
fi

CASE_JSON="$(jq -c --arg id "$FIXTURE_ID" '.cases[] | select(.id == $id)' "$MANIFEST")"
if [ -z "$CASE_JSON" ]; then
  echo "Unknown fixture id: $FIXTURE_ID" >&2
  exit 1
fi

AUDIO_FILE="$(jq -r '.audioFile' <<<"$CASE_JSON")"
SOURCE_AUDIO="$FIXTURE_ROOT/$AUDIO_FILE"
DEVICE_AUDIO="/sdcard/Android/data/com.calendaradd/files/Download/calendar_add_${FIXTURE_ID}.wav"

adb shell mkdir -p /sdcard/Android/data/com.calendaradd/files/Download
adb push "$SOURCE_AUDIO" "$DEVICE_AUDIO"
adb shell am start \
  -a android.intent.action.SEND \
  -t audio/wav \
  -n com.calendaradd/.ShareImportActivity \
  --eu android.intent.extra.STREAM "file://$DEVICE_AUDIO"
