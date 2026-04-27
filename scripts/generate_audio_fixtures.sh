#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/audio-fixtures/manifest.json"
FIXTURE_ROOT="$ROOT_DIR/app/src/test/resources/audio-fixtures"

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg is required to generate audio fixtures." >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to read the audio fixture manifest." >&2
  exit 1
fi

generate_case() {
  local case_json="$1"
  local id transcript spoken_text audio_file case_dir transcript_file spoken_file expected_file audio_path
  id="$(jq -r '.id' <<<"$case_json")"
  transcript="$(jq -r '.transcript' <<<"$case_json")"
  spoken_text="$(jq -r '.spokenText // .transcript' <<<"$case_json")"
  audio_file="$(jq -r '.audioFile' <<<"$case_json")"

  case_dir="$FIXTURE_ROOT/$(dirname "$audio_file")"
  transcript_file="$case_dir/transcript.txt"
  spoken_file="$case_dir/spoken.txt"
  expected_file="$case_dir/expected-event.json"
  audio_path="$FIXTURE_ROOT/$audio_file"

  mkdir -p "$case_dir"
  printf '%s\n' "$transcript" >"$transcript_file"
  printf '%s\n' "$spoken_text" >"$spoken_file"
  if jq -e '.expectedEvents != null' >/dev/null <<<"$case_json"; then
    jq '{events: .expectedEvents}' <<<"$case_json" >"$expected_file"
  else
    jq '{events: [.expectedEvent]}' <<<"$case_json" >"$expected_file"
  fi
  ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i "flite=textfile='$spoken_file':voice=slt" \
    -ar 16000 -ac 1 "$audio_path"
  echo "Generated $id -> $audio_path"
}

case_count="$(jq '.cases | length' "$MANIFEST")"
for ((i = 0; i < case_count; i++)); do
  generate_case "$(jq -c ".cases[$i]" "$MANIFEST")"
done
