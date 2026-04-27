#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/image-fixtures/manifest.json"
MODEL="${OLLAMA_MODEL:-gemma4:latest}"

require_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required." >&2
    exit 1
  fi
}

require_tool jq
require_tool ollama
require_tool awk
require_tool sort
require_tool tr

normalize_text() {
  cat \
    | tr '[:upper:]' '[:lower:]' \
    | tr -cs '[:alnum:]' ' '
}

tokenize() {
  normalize_text \
    | tr ' ' '\n' \
    | sed '/^$/d' \
    | grep -vE '^(a|an|and|as|at|by|for|from|in|is|of|on|or|the|to|with|via|this|that|event|flyer|poster|schedule|series)$'
}

case_ocr_text() {
  jq -r '
    [
      .flyerText.title,
      .flyerText.date,
      .flyerText.time,
      .flyerText.location,
      .flyerText.description,
      (.scheduleRows[]? | .date, .title, .time)
    ] | map(select(. != null and . != "")) | .[]
  ' <<<"$1"
}

build_heuristic_query() {
  local case_json="$1"
  local ocr_text
  ocr_text="$(case_ocr_text "$case_json")"

  {
    jq -r '
      [
        .flyerText.title,
        .flyerText.location,
        .flyerText.description
      ] | map(select(. != null and . != "")) | .[]
    ' <<<"$case_json"
    printf '%s\n' "$ocr_text" | sed -E 's/[[:space:]]+/ /g' | sed '/^$/d' | head -n 3
  } | normalize_text \
    | tr ' ' '\n' \
    | sed '/^$/d' \
    | sort -u \
    | tr '\n' ' ' \
    | sed 's/[[:space:]]\+$//'
}

build_agentic_query() {
  local case_json="$1"
  local ocr_text
  ocr_text="$(case_ocr_text "$case_json")"
  local prompt
  prompt="$(cat <<EOF
Return only a concise public web search query for an event flyer.
Use the most distinctive title, location, date, and one extra detail if needed.
Avoid generic words like flyer, event, poster, schedule, or series unless necessary.
Keep it to one line and roughly 6 to 12 words.

Extracted flyer evidence:
$(jq -r '[.flyerText.title, .flyerText.location, .flyerText.description] | map(select(. != null and . != "")) | join(" | ")' <<<"$case_json")

OCR hints:
$ocr_text
EOF
)"
  ollama run "$MODEL" "$prompt" 2>/dev/null \
    | head -n 1 \
    | tr -d '\r' \
    | sed 's/^"//; s/"$//' \
    | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

score_query_against_case() {
  local query="$1"
  local candidate_json="$2"

  local query_tokens candidate_tokens score
  query_tokens="$(tokenize "$query" | sort -u)"
  candidate_tokens="$(
    jq -r '
      [
        .sourceTitle,
        .sourceUrl
      ] | map(select(. != null and . != "")) | .[]
    ' <<<"$candidate_json" | tokenize | sort -u
  )"

  score="$(comm -12 <(printf '%s\n' "$query_tokens") <(printf '%s\n' "$candidate_tokens") | wc -l | tr -d ' ')"
  printf '%s\n' "$score"
}

best_match_title() {
  local query="$1"
  local corpus_json="$2"
  local best_score=-1
  local best_title=""
  local candidate

  while IFS= read -r candidate; do
    local score title
    score="$(score_query_against_case "$query" "$candidate")"
    title="$(jq -r '.sourceTitle' <<<"$candidate")"
    if (( score > best_score )); then
      best_score="$score"
      best_title="$title"
    fi
  done < <(jq -c '.cases[]' <<<"$corpus_json")

  printf '%s\n' "$best_title"
}

main() {
  local corpus_json heuristic_correct agentic_correct total
  corpus_json="$(cat "$MANIFEST")"
  total="$(jq '.cases | length' <<<"$corpus_json")"
  heuristic_correct=0
  agentic_correct=0

  while IFS= read -r case_json; do
    local expected_title heuristic_query agentic_query heuristic_best agentic_best
    expected_title="$(jq -r '.sourceTitle' <<<"$case_json")"
    heuristic_query="$(build_heuristic_query "$case_json")"
    agentic_query="$(build_agentic_query "$case_json")"
    heuristic_best="$(best_match_title "$heuristic_query" "$corpus_json")"
    agentic_best="$(best_match_title "$agentic_query" "$corpus_json")"

    if [[ "$heuristic_best" == "$expected_title" ]]; then
      heuristic_correct=$((heuristic_correct + 1))
    fi
    if [[ "$agentic_best" == "$expected_title" ]]; then
      agentic_correct=$((agentic_correct + 1))
    fi
  done < <(jq -c '.cases[]' <<<"$corpus_json")

  awk -v total="$total" -v heuristic="$heuristic_correct" -v agentic="$agentic_correct" '
    BEGIN {
      printf("cases=%d\n", total)
      printf("heuristic_top1=%.1f%%\n", (100.0 * heuristic / total))
      printf("agentic_top1=%.1f%%\n", (100.0 * agentic / total))
      printf("delta=%.1f pts\n", (100.0 * agentic / total) - (100.0 * heuristic / total))
    }
  '
}

main "$@"
