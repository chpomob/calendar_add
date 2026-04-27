#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/image-fixtures/manifest.json"
MODEL="${OLLAMA_MODEL:-gemma4:latest}"
HOST="${OLLAMA_HOST:-http://localhost:11434}"

normalize_iso() {
  local value="$1"
  local timezone="$2"
  if [[ -z "$value" ]]; then
    printf '%s\n' ""
  elif [[ "$value" =~ [Zz]$ || "$value" =~ [+-][0-9]{2}:[0-9]{2}$ ]]; then
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

heavy_observation_prompt() {
  local datetime="$1"
  local timezone="$2"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: en
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Prompt must preserve spaces and punctuation in visible text whenever possible.
Heavy mode stage 1/3: multimodal image observations
Inspect the image conservatively and capture raw event evidence before normalization.
Treat the image as a flyer, poster, screenshot, or event notice.
Prefer exact visible event title, date, time, and location text.
Treat visible virtual-location text such as Online, Virtual, Zoom, or Teams as a real location value.
Do not invent details that are not visible.
If the image shows a schedule table or recurring series with multiple explicit date/time rows, keep one candidate per row when the rows clearly describe separate occurrences.
Do not merge distinct schedule rows into one generic observation just because they share the same date, venue, or series title.
For schedule rows, keep the row's own visible title separate from the flyer or series title.
The flyer banner title is not the event title for individual schedule rows.
Copy the row title exactly as visible and do not add extra words, adjectives, or paraphrases.
When copying locations, preserve spaces, punctuation, and parentheses as visible instead of compressing them.
Return ONLY JSON in this exact shape:
{ "events": [ { "titleCandidates": [], "descriptionCandidates": [], "locationCandidates": [], "dateCandidates": [], "timeCandidates": [], "supportingText": [], "notes": [] } ], "globalNotes": [] }
Keep multiple candidate dates or times if the image is ambiguous.
Copy visible phrases as they appear when useful. Do not output final ISO timestamps yet.
EOF
}

heavy_temporal_prompt() {
  local datetime="$1"
  local timezone="$2"
  local observations="$3"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: en
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Prompt must preserve spaces and punctuation in visible text whenever possible.
Heavy mode stage 2/3: temporal normalization
You are resolving temporal information for a heavy image extraction pass.
Use the observation JSON below and focus only on dates, times, durations, and event boundaries.
Return ONLY JSON in this exact shape:
{ "events": [ { "resolvedStartTime": "ISO-8601 or empty", "resolvedEndTime": "ISO-8601 or empty", "dateReasoning": "", "remainingAmbiguity": "" } ] }
If you cannot safely resolve a time, leave it empty instead of guessing.
Observation JSON:
${observations}
EOF
}

heavy_final_prompt() {
  local datetime="$1"
  local timezone="$2"
  local observations="$3"
  local temporal="$4"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: en
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Prompt must preserve spaces and punctuation in visible text whenever possible.
Heavy mode stage 3/3: final event composition
You are composing final events for a heavy image extraction pass.
Use the observation JSON for titles, descriptions, locations, and attendees.
Use the temporal-resolution JSON for startTime and endTime when available.
Return ONLY valid JSON in this exact shape: { "events": [ { "title": "", "description": "", "startTime": "ISO-8601", "endTime": "ISO-8601", "location": "", "attendees": [] } ] }
Keep multiple distinct events if the earlier stages found them.
If a date cannot be resolved safely, leave startTime and endTime empty rather than inventing one.
For schedule rows, use the row's own visible title as the event title and do not prefix it with the flyer or series title.
The flyer banner title is not the event title for individual schedule rows.
Copy the row title exactly as visible and do not add extra words, adjectives, or paraphrases.
When copying locations, preserve spaces, punctuation, and parentheses as visible instead of compressing them.
Observation JSON:
${observations}

Temporal-resolution JSON:
${temporal}
EOF
}

run_ollama() {
  local payload_file="$1"
  curl -s "$HOST/api/chat" \
    -H 'Content-Type: application/json' \
    -d @"$payload_file"
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
  obs_payload_file="$tmpdir/${id}.obs.json"
  temp_payload_file="$tmpdir/${id}.temp.json"
  final_payload_file="$tmpdir/${id}.final.json"

  base64 < "$ROOT_DIR/app/src/test/resources/image-fixtures/$image_file" | tr -d '\n' > "$img_b64_file"

  observation_prompt="$(heavy_observation_prompt "$datetime" "$timezone")"
  jq -n --arg model "$MODEL" --arg prompt "$observation_prompt" --rawfile img "$img_b64_file" '{
    model: $model,
    messages: [{role: "user", content: $prompt, images: [$img]}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$obs_payload_file"
  observations_json="$(run_ollama "$obs_payload_file")"
  observations="$(jq -r '.message.content' <<<"$observations_json")"
  observations_parsed="$(jq -c '.' <<<"$observations")"

  temporal_prompt="$(heavy_temporal_prompt "$datetime" "$timezone" "$observations_parsed")"
  jq -n --arg model "$MODEL" --arg prompt "$temporal_prompt" '{
    model: $model,
    messages: [{role: "user", content: $prompt}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$temp_payload_file"
  temporal_json="$(run_ollama "$temp_payload_file")"
  temporal="$(jq -r '.message.content' <<<"$temporal_json")"
  temporal_parsed="$(jq -c '.' <<<"$temporal")"

  final_prompt="$(heavy_final_prompt "$datetime" "$timezone" "$observations_parsed" "$temporal_parsed")"
  jq -n --arg model "$MODEL" --arg prompt "$final_prompt" '{
    model: $model,
    messages: [{role: "user", content: $prompt}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$final_payload_file"
  final_json="$(run_ollama "$final_payload_file")"
  final_content="$(jq -r '.message.content' <<<"$final_json")"
  parsed="$(jq -c '.' <<<"$final_content")"

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
