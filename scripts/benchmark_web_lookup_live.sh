#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/web-lookup-fixtures/manifest.json"
MANIFEST="${MANIFEST_PATH:-$MANIFEST}"
MODEL="${OLLAMA_MODEL:-gemma4:latest}"
HOST="${OLLAMA_HOST:-https://html.duckduckgo.com/html/}"

require_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required." >&2
    exit 1
  fi
}

require_tool jq
require_tool curl
require_tool ollama
require_tool perl
require_tool awk

normalize_words() {
  printf '%s' "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | tr -cs '[:alnum:]' '\n' \
    | sed '/^$/d' \
    | sort -u \
    | tr '\n' ' '
}

search_duckduckgo() {
  local query="$1"
  local html
  html="$(curl -L -s --max-time 20 "$HOST?q=$(printf '%s' "$query" | jq -sRr @uri)")"
  printf '%s' "$html" | perl -0ne '
    if (/<a[^>]+class="[^"]*result__a[^"]*"[^>]*>(.*?)<\/a>/is) {
      my $t = $1;
      $t =~ s/<[^>]+>/ /g;
      $t =~ s/\s+/ /g;
      print $t;
    }
  '
}

build_heuristic_query() {
  local case_json="$1"
  jq -r '
    [
      .pageTitle,
      .pageDescription,
      .evidenceText
    ] | map(select(. != null and . != "")) | .[]
  ' <<<"$case_json" \
    | sed -E 's/[[:space:]]+/ /g' \
    | tr '[:upper:]' '[:lower:]' \
    | tr -cs '[:alnum:]' '\n' \
    | grep -vE '^(a|an|and|as|at|by|for|from|in|is|of|on|or|the|to|with|via|this|that|event|flyer|poster|schedule|series)$' || true \
    | head -n 12 \
    | tr '\n' ' ' \
    | sed 's/[[:space:]]\+$//'
}

build_agentic_query() {
  local case_json="$1"
  local prompt
  prompt="$(cat <<EOF
Return only one concise public web search query for a real event page.
Prefer the most distinctive title, location, date, and one extra clue if needed.
Avoid generic words like flyer, event, poster, schedule, or series unless necessary.
Keep it short enough to search directly.

Page metadata:
$(jq -r '[.pageTitle, .pageDescription] | map(select(. != null and . != "")) | join(" | ")' <<<"$case_json")

Local evidence:
$(jq -r '.evidenceText' <<<"$case_json")
EOF
)"
  local raw
  raw="$(ollama run "$MODEL" "$prompt" 2>/dev/null || true)"
  printf '%s' "$raw" \
    | tr -d '\r' \
    | sed -n '1p' \
    | sed 's/^"//; s/"$//' \
    | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

score_title_match() {
  local result_title="$1"
  local expected_title="$2"
  local lhs rhs
  lhs="$(normalize_words "$result_title")"
  rhs="$(normalize_words "$expected_title")"
  if [[ "$lhs" == "$rhs" ]]; then
    printf '1\n'
    return
  fi
  if [[ "$lhs" == *"$rhs"* || "$rhs" == *"$lhs"* ]]; then
    printf '1\n'
    return
  fi
  printf '0\n'
}

main() {
  local total heuristic_hits agentic_hits
  total="$(jq '.cases | length' "$MANIFEST")"
  heuristic_hits=0
  agentic_hits=0

  while IFS= read -r case_json; do
    local expected_title heuristic_query agentic_query heuristic_result agentic_result
    expected_title="$(jq -r '.pageTitle // .sourceTitle' <<<"$case_json")"
    heuristic_query="$(build_heuristic_query "$case_json")"
    agentic_query="$(build_agentic_query "$case_json")"
    heuristic_result="$(search_duckduckgo "$heuristic_query")"
    agentic_result="$(search_duckduckgo "$agentic_query")"

    heuristic_hits=$((heuristic_hits + $(score_title_match "$heuristic_result" "$expected_title")))
    agentic_hits=$((agentic_hits + $(score_title_match "$agentic_result" "$expected_title")))
  done < <(jq -c '.cases[]' "$MANIFEST")

  awk -v total="$total" -v heuristic="$heuristic_hits" -v agentic="$agentic_hits" '
    BEGIN {
      printf("cases=%d\n", total)
      printf("heuristic_top1=%.1f%%\n", (100.0 * heuristic / total))
      printf("agentic_top1=%.1f%%\n", (100.0 * agentic / total))
      printf("delta=%.1f pts\n", (100.0 * agentic / total) - (100.0 * heuristic / total))
    }
  '
}

main "$@"
