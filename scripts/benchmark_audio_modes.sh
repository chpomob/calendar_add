#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/audio-fixtures/manifest.json"
MODEL="${OLLAMA_MODEL:-gemma4:latest}"
HOST="${OLLAMA_HOST:-http://localhost:11434}"

require_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required." >&2
    exit 1
  fi
}

require_tool jq
require_tool curl

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

classic_prompt() {
  local datetime="$1"
  local timezone="$2"
  local language="$3"
  local transcript="$4"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: ${language}
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Input type: audio
Extract the intended calendar event from this audio only when the speaker clearly proposes, confirms, schedules, or reschedules a concrete calendar item.
Ignore incidental mentions of time, generic future statements, or status updates that are not actual calendar commitments.
If the audio only mentions a time or date without naming an event, do not invent a generic title like Meeting.
The audio may contain filler words, background noise, repeated fragments, and ASR mistakes.
Use the intended meaning of the speech, not the noisy transcription artifacts.
If the speaker says relative dates or times, resolve them using the reference local datetime above.
Use only input evidence; leave unknown fields empty.
Use a specific input title; if no named event or clear commitment exists, return no event instead of generic Meeting/Event/Concert/Reminder.
If the input contains multiple fragments about the same event, merge them into one event.
If the input contains multiple distinct events, return them all.
Fill endTime only with explicit end, duration, or range.
Attendees must be explicitly named participants or invitees.
Preserve proper nouns, accents, and input language.
Return ONLY valid JSON in this exact shape: { "events": [ { "title": "", "description": "", "startTime": "ISO-8601 with timezone offset", "endTime": "ISO-8601 with timezone offset", "location": "", "attendees": [] } ] }
If there is only one event, still return it inside the events array.
If there are no events, return { "events": [] }.
Audio transcript proxy:
${transcript}
EOF
}

heavy_observation_prompt() {
  local datetime="$1"
  local timezone="$2"
  local language="$3"
  local transcript="$4"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: ${language}
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Heavy mode stage 1/3: multimodal audio observations
Listen to the audio conservatively and capture raw event evidence before normalization.
Only capture actual calendar commitments; ignore incidental future mentions and general availability statements.
Do not invent a generic meeting when the audio only mentions a time or date without naming an event.
The audio may contain filler words, background noise, repeated fragments, and ASR mistakes.
Keep the intended event, not the transcription artifacts.
Return ONLY JSON in this exact shape:
{ "events": [ { "titleCandidates": [], "descriptionCandidates": [], "locationCandidates": [], "dateCandidates": [], "timeCandidates": [], "quotedPhrases": [], "notes": [] } ], "globalNotes": [] }
Keep multiple candidate dates or times if the speaker is ambiguous.
Do not output final ISO timestamps yet.
Audio transcript proxy:
${transcript}
EOF
}

heavy_temporal_prompt() {
  local datetime="$1"
  local timezone="$2"
  local language="$3"
  local observations="$4"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: ${language}
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Heavy mode stage 2/3: temporal normalization
You are resolving temporal information for a heavy audio extraction pass.
Use the observation JSON below and focus only on dates, times, durations, and event boundaries.
Return ONLY JSON in this exact shape:
{ "events": [ { "resolvedStartTime": "ISO-8601 or empty", "resolvedEndTime": "ISO-8601 or empty", "dateReasoning": "", "remainingAmbiguity": "" } ] }
Preserve the observation event order and return one temporal object per observation event.
If you cannot safely resolve a time, leave it empty instead of guessing.
Observation JSON:
${observations}
EOF
}

heavy_final_prompt() {
  local datetime="$1"
  local timezone="$2"
  local language="$3"
  local observations="$4"
  local temporal="$5"
  cat <<EOF
Reference local datetime: ${datetime}
Reference timezone: ${timezone}
Reference day of week: $(date -d "${datetime}" '+%A' 2>/dev/null || true)
User language: ${language}
Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, next Friday, this weekend, and in two days against the reference local datetime.
Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.
Heavy mode stage 3/3: final event composition
You are composing final events for a heavy audio extraction pass.
Use the observation JSON for titles, descriptions, locations, and attendees.
Use the temporal-resolution JSON for startTime and endTime when available.
Match observation events to temporal-resolution events by array index; do not collapse separate rows in the final pass.
Return ONLY valid JSON in this exact shape: { "events": [ { "title": "", "description": "", "startTime": "ISO-8601", "endTime": "ISO-8601", "location": "", "attendees": [] } ] }
Keep multiple distinct events if the earlier stages found them.
If a date cannot be resolved safely, leave startTime and endTime empty rather than inventing one.
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

score_case() {
  local mode="$1"
  local expected_events="$2"
  local parsed="$3"
  local timezone="$4"
  local parse_failed="$5"
  local expected_count actual_count limit
  local title_hits=0
  local start_hits=0
  local location_hits=0
  local description_hits=0
  local full_event_hits=0
  local case_exact=0

  expected_count="$(jq 'length' <<<"$expected_events")"
  actual_count="$(jq '.events | length' <<<"$parsed")"
  limit="$expected_count"
  if (( actual_count < limit )); then
    limit="$actual_count"
  fi

  if (( limit > 0 )); then
    for idx in $(seq 0 $((limit - 1))); do
      local expected_title expected_start expected_location expected_description expected_end
      local actual_title actual_start actual_location actual_description actual_end

      expected_title="$(jq -r ".[$idx].title // empty" <<<"$expected_events")"
      expected_start="$(jq -r ".[$idx].startTime // empty" <<<"$expected_events")"
      expected_location="$(jq -r ".[$idx].location // empty" <<<"$expected_events")"
      expected_description="$(jq -r ".[$idx].description // empty" <<<"$expected_events")"
      expected_end="$(jq -r ".[$idx].endTime // empty" <<<"$expected_events")"

      actual_title="$(jq -r ".events[$idx].title // empty" <<<"$parsed")"
      actual_start="$(normalize_iso "$(jq -r ".events[$idx].startTime // empty" <<<"$parsed")" "$timezone")"
      actual_location="$(jq -r ".events[$idx].location // empty" <<<"$parsed")"
      actual_description="$(jq -r ".events[$idx].description // empty" <<<"$parsed")"
      actual_end="$(normalize_iso "$(jq -r ".events[$idx].endTime // empty" <<<"$parsed")" "$timezone")"

      expected_start="$(normalize_iso "$expected_start" "$timezone")"
      expected_end="$(normalize_iso "$expected_end" "$timezone")"

      if [[ "$(normalize_words "$actual_title")" == "$(normalize_words "$expected_title")" ]]; then
        title_hits=$((title_hits + 1))
      fi
      if [[ "$actual_start" == "$expected_start" ]]; then
        start_hits=$((start_hits + 1))
      fi
      if [[ "$(normalize_words "$actual_location")" == "$(normalize_words "$expected_location")" ]]; then
        location_hits=$((location_hits + 1))
      fi
      if [[ "$(normalize_words "$actual_description")" == "$(normalize_words "$expected_description")" ]]; then
        description_hits=$((description_hits + 1))
      fi
      if [[ "$(normalize_words "$actual_title")" == "$(normalize_words "$expected_title")" &&
            "$actual_start" == "$expected_start" &&
            "$actual_end" == "$expected_end" &&
            "$(normalize_words "$actual_location")" == "$(normalize_words "$expected_location")" &&
            "$(normalize_words "$actual_description")" == "$(normalize_words "$expected_description")" ]]; then
        full_event_hits=$((full_event_hits + 1))
      fi
    done
  fi

  if [[ "$expected_count" == "$actual_count" && "$full_event_hits" == "$expected_count" && "$parse_failed" == "0" ]]; then
    case_exact=1
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$mode" \
    "$expected_count" \
    "$actual_count" \
    "$title_hits" \
    "$start_hits" \
    "$location_hits" \
    "$description_hits" \
    "$full_event_hits" \
    "$case_exact" \
    "$parse_failed"
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
results_file="$tmpdir/results.tsv"
: > "$results_file"

jq -c '.cases[]' "$MANIFEST" | while read -r case_json; do
  id="$(jq -r '.id' <<<"$case_json")"
  datetime="$(jq -r '.context.datetime' <<<"$case_json")"
  timezone="$(jq -r '.context.timezone' <<<"$case_json")"
  language="$(jq -r '.context.language' <<<"$case_json")"
  transcript="$(jq -r '.transcript' <<<"$case_json")"
  expected_events="$(jq -c 'if .expectedEvents != null then .expectedEvents else [.expectedEvent] end' <<<"$case_json")"

  classic_payload_file="$tmpdir/${id}.classic.json"
  heavy_obs_payload_file="$tmpdir/${id}.heavy.obs.json"
  heavy_temp_payload_file="$tmpdir/${id}.heavy.temp.json"
  heavy_final_payload_file="$tmpdir/${id}.heavy.final.json"

  classic_prompt_text="$(classic_prompt "$datetime" "$timezone" "$language" "$transcript")"
  jq -n --arg model "$MODEL" --arg prompt "$classic_prompt_text" '{
    model: $model,
    messages: [{role: "user", content: $prompt}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$classic_payload_file"
  classic_response="$(run_ollama "$classic_payload_file")"
  classic_content="$(jq -r '.message.content // empty' <<<"$classic_response")"
  if parsed_classic="$(jq -c '.' <<<"$classic_content" 2>/dev/null)"; then
    parse_failed_classic=0
  else
    parsed_classic='{"events":[]}'
    parse_failed_classic=1
  fi
  score_case "classic" "$expected_events" "$parsed_classic" "$timezone" "$parse_failed_classic" >> "$results_file"

  heavy_obs_prompt_text="$(heavy_observation_prompt "$datetime" "$timezone" "$language" "$transcript")"
  jq -n --arg model "$MODEL" --arg prompt "$heavy_obs_prompt_text" '{
    model: $model,
    messages: [{role: "user", content: $prompt}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$heavy_obs_payload_file"
  heavy_obs_response="$(run_ollama "$heavy_obs_payload_file")"
  heavy_obs_content="$(jq -r '.message.content // empty' <<<"$heavy_obs_response")"
  if parsed_observations="$(jq -c '.' <<<"$heavy_obs_content" 2>/dev/null)"; then
    parse_failed_obs=0
  else
    parsed_observations='{"events":[]}'
    parse_failed_obs=1
  fi

  heavy_temp_prompt_text="$(heavy_temporal_prompt "$datetime" "$timezone" "$language" "$parsed_observations")"
  jq -n --arg model "$MODEL" --arg prompt "$heavy_temp_prompt_text" '{
    model: $model,
    messages: [{role: "user", content: $prompt}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$heavy_temp_payload_file"
  heavy_temp_response="$(run_ollama "$heavy_temp_payload_file")"
  heavy_temp_content="$(jq -r '.message.content // empty' <<<"$heavy_temp_response")"
  if parsed_temporal="$(jq -c '.' <<<"$heavy_temp_content" 2>/dev/null)"; then
    parse_failed_temp=0
  else
    parsed_temporal='{"events":[]}'
    parse_failed_temp=1
  fi

  heavy_final_prompt_text="$(heavy_final_prompt "$datetime" "$timezone" "$language" "$parsed_observations" "$parsed_temporal")"
  jq -n --arg model "$MODEL" --arg prompt "$heavy_final_prompt_text" '{
    model: $model,
    messages: [{role: "user", content: $prompt}],
    stream: false,
    format: "json",
    options: {temperature: 0, top_p: 0.1, top_k: 1}
  }' > "$heavy_final_payload_file"
  heavy_final_response="$(run_ollama "$heavy_final_payload_file")"
  heavy_final_content="$(jq -r '.message.content // empty' <<<"$heavy_final_response")"
  if parsed_heavy="$(jq -c '.' <<<"$heavy_final_content" 2>/dev/null)"; then
    parse_failed_heavy=0
  else
    parsed_heavy='{"events":[]}'
    parse_failed_heavy=1
  fi
  score_case "heavy" "$expected_events" "$parsed_heavy" "$timezone" "$parse_failed_heavy" >> "$results_file"
done

awk -F'\t' '
function pct(num, den) {
  if (den == 0) return "0.0"
  return sprintf("%.1f", (100.0 * num) / den)
}
{
  mode = $1
  cases[mode]++
  expected_events[mode] += $2
  actual_events[mode] += $3
  title_hits[mode] += $4
  start_hits[mode] += $5
  location_hits[mode] += $6
  description_hits[mode] += $7
  full_hits[mode] += $8
  case_exact_hits[mode] += $9
  parse_failures[mode] += $10
}
END {
  for (i = 1; i <= 2; i++) {
    mode = (i == 1 ? "classic" : "heavy")
    if (!(mode in cases)) continue
    printf("[%s] cases=%d expected_events=%d actual_events=%d parse_failures=%d title=%s%% start=%s%% location=%s%% description=%s%% full=%s%% case_exact=%s%%\n", \
      mode, cases[mode], expected_events[mode], actual_events[mode], parse_failures[mode], \
      pct(title_hits[mode], expected_events[mode]), pct(start_hits[mode], expected_events[mode]), pct(location_hits[mode], expected_events[mode]), pct(description_hits[mode], expected_events[mode]), pct(full_hits[mode], expected_events[mode]), pct(case_exact_hits[mode], cases[mode]))
  }
  if (cases["classic"] > 0 && cases["heavy"] > 0) {
    printf("[delta] title=%+.1fpts start=%+.1fpts location=%+.1fpts description=%+.1fpts full=%+.1fpts case_exact=%+.1fpts\n", \
      (100.0 * title_hits["heavy"] / expected_events["heavy"]) - (100.0 * title_hits["classic"] / expected_events["classic"]), \
      (100.0 * start_hits["heavy"] / expected_events["heavy"]) - (100.0 * start_hits["classic"] / expected_events["classic"]), \
      (100.0 * location_hits["heavy"] / expected_events["heavy"]) - (100.0 * location_hits["classic"] / expected_events["classic"]), \
      (100.0 * description_hits["heavy"] / expected_events["heavy"]) - (100.0 * description_hits["classic"] / expected_events["classic"]), \
      (100.0 * full_hits["heavy"] / expected_events["heavy"]) - (100.0 * full_hits["classic"] / expected_events["classic"]), \
      (100.0 * case_exact_hits["heavy"] / cases["heavy"]) - (100.0 * case_exact_hits["classic"] / cases["classic"]))
  }
}
' "$results_file"
