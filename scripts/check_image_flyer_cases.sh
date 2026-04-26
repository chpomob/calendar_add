#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/image-fixtures/manifest.json"
MODEL="${OLLAMA_MODEL:-gemma4:latest}"
HOST="${OLLAMA_HOST:-http://localhost:11434}"

prompt_for_case() {
  local datetime="$1"
  local timezone="$2"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: en
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Input type: image
Extract calendar events from this flyer, poster, screenshot, or event notice.
Use the exact visible event title, date, time, and location when they are present.
Do not guess missing details.
If the image contains a schedule table or a flyer series with multiple explicit date/time rows, return one event per row when the rows clearly describe separate occurrences.
If the image contains relative date or time phrases, resolve them using the reference local datetime above.
If the input contains multiple fragments about the same event, merge them into one event.
If the input contains multiple distinct events, return them all.
Return ONLY valid JSON in this exact shape: { "events": [ { "title": "", "description": "", "startTime": "ISO-8601", "endTime": "ISO-8601", "location": "", "attendees": [] } ] }
If there is only one event, still return it inside the events array.
If there are no events, return { "events": [] }.
EOF
}

normalize_iso() {
  local value="$1"
  local timezone="$2"
  if [[ "$value" =~ [Zz]$ || "$value" =~ [+-][0-9]{2}:[0-9]{2}$ ]]; then
    printf '%s\n' "$value"
  else
    TZ="$timezone" date -d "$value" +%Y-%m-%dT%H:%M:%S%:z
  fi
}

normalize_words() {
  printf '%s' "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | tr -cs '[:alnum:]' '\n' \
    | sed '/^$/d' \
    | sort \
    | tr '\n' ' '
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

jq -c '.cases[]' "$MANIFEST" | while read -r case_json; do
  id="$(jq -r '.id' <<<"$case_json")"
  image_file="$(jq -r '.imageFile' <<<"$case_json")"
  datetime="$(jq -r '.context.datetime' <<<"$case_json")"
  timezone="$(jq -r '.context.timezone' <<<"$case_json")"
  expected_events="$(jq -c 'if .expectedEvents != null then .expectedEvents else [.expectedEvent] end' <<<"$case_json")"

  img_b64_file="$tmpdir/${id}.b64"
  payload_file="$tmpdir/${id}.json"
  base64 < "$ROOT_DIR/app/src/test/resources/image-fixtures/$image_file" | tr -d '\n' > "$img_b64_file"
  prompt="$(prompt_for_case "$datetime" "$timezone")"

  jq -n --arg model "$MODEL" --arg prompt "$prompt" --rawfile img "$img_b64_file" '{
    model: $model,
    messages: [{role: "user", content: $prompt, images: [$img]}],
    stream: false,
    format: "json"
  }' > "$payload_file"

  response_json="$(curl -s "$HOST/api/chat" \
    -H 'Content-Type: application/json' \
    -d @"$payload_file")"

  content="$(jq -r '.message.content' <<<"$response_json")"
  parsed="$(jq -c '.' <<<"$content")"

  expected_count="$(jq 'length' <<<"$expected_events")"
  actual_count="$(jq '.events | length' <<<"$parsed")"
  printf '%s\n' "[$id] events=$actual_count"

  if [[ "$actual_count" != "$expected_count" ]]; then
    printf '%s\n' "[$id] expected $expected_count events but got $actual_count" >&2
    exit 1
  fi

  if [[ "$expected_count" == "0" ]]; then
    continue
  fi

  for idx in $(seq 0 $((expected_count - 1))); do
    expected_title="$(jq -r ".[$idx].title // empty" <<<"$expected_events")"
    expected_start="$(jq -r ".[$idx].startTime // empty" <<<"$expected_events")"
    expected_location="$(jq -r ".[$idx].location // empty" <<<"$expected_events")"
    actual_title="$(jq -r ".events[$idx].title // empty" <<<"$parsed")"
    actual_start="$(normalize_iso "$(jq -r ".events[$idx].startTime // empty" <<<"$parsed")" "$timezone")"
    actual_location="$(jq -r ".events[$idx].location // empty" <<<"$parsed")"
    expected_start="$(normalize_iso "$expected_start" "$timezone")"

    printf '%s\n' "[$id][$idx] title=$actual_title start=$actual_start location=$actual_location"

    if [[ "$(normalize_words "$actual_title")" != "$(normalize_words "$expected_title")" || "$actual_start" != "$expected_start" || "$(normalize_words "$actual_location")" != "$(normalize_words "$expected_location")" ]]; then
      printf '%s\n' "[$id][$idx] expected title=$expected_title start=$expected_start location=$expected_location" >&2
      exit 1
    fi
  done
done
