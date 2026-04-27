#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST="$ROOT_DIR/app/src/test/resources/web-lookup-fixtures/manifest.json"
MANIFEST="${MANIFEST_PATH:-$MANIFEST}"
MODEL="${OLLAMA_MODEL:-gemma4:latest}"
HOST="${OLLAMA_HOST:-https://html.duckduckgo.com/html/}"
BRAVE_SEARCH_API_KEY="${BRAVE_SEARCH_API_KEY:-}"

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

search_duckduckgo_candidates() {
  local query="$1"
  local html
  html="$(curl -L -s --max-time 20 "$HOST?q=$(printf '%s' "$query" | jq -sRr @uri)")"
  printf '%s' "$html" | perl -MURI::Escape=uri_unescape -MHTML::Entities=decode_entities -0ne '
    my $rank = 0;
    while (/<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)<\/a>/gis) {
      my ($url, $title) = ($1, $2);
      $url =~ s/&amp;/&/g;
      if ($url =~ /[?&]uddg=([^&]+)/) {
        $url = uri_unescape($1);
      } elsif ($url =~ m{^//}) {
        $url = "https:" . $url;
      } elsif ($url !~ m{^https?://}) {
        $url = "https://duckduckgo.com" . $url;
      }
      $title =~ s/<[^>]+>/ /g;
      $title = decode_entities($title);
      $title =~ s/\s+/ /g;
      $title =~ s/^\s+|\s+$//g;
      print join("\t", $rank, $url, $title) . "\n";
      last if ++$rank >= 8;
    }
  '
}

search_brave_candidates() {
  local query="$1"
  if [[ -z "$BRAVE_SEARCH_API_KEY" ]]; then
    return
  fi
  curl -L -s --max-time 20 \
    -H "Accept: application/json" \
    -H "X-Subscription-Token: $BRAVE_SEARCH_API_KEY" \
    "https://api.search.brave.com/res/v1/web/search?q=$(printf '%s' "$query" | jq -sRr @uri)&count=8" \
    | jq -r '
      .web.results // []
      | to_entries[]
      | [.key, (.value.url // ""), (.value.title // ""), (.value.description // "")]
      | @tsv
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

build_app_query() {
  local case_json="$1"
  local title location description date_hint ocr_hint
  title="$(jq -r '.sourceTitle // .pageTitle // ""' <<<"$case_json")"
  location="$(jq -r '
    (.evidenceText // "")
    | split(" ")
    | if index("Élysée") != null then "Élysée Montmartre"
      elif index("Kilowatt") != null then "Le Kilowatt"
      else "" end
  ' <<<"$case_json")"
  description="$(jq -r '.pageDescription // ""' <<<"$case_json" \
    | tr '[:upper:]' '[:lower:]' \
    | tr -cs '[:alnum:]' '\n' \
    | grep -vE '^(a|an|and|as|at|by|for|from|in|is|of|on|or|the|to|with|via|this|that|event|flyer|poster|schedule|series|les|des|une|pour|avec|dans|sur|par|aux|est|sont|soirée|concert)$' \
    | sed '/^$/d' \
    | head -n 6 \
    | tr '\n' ' ' \
    | sed 's/[[:space:]]\+$//' || true)"
  date_hint="$(grep -Eo '[0-9]{4}' <<<"$(jq -r '.evidenceText + " " + .pageTitle' <<<"$case_json")" | head -n 1 || true)"
  ocr_hint="$(jq -r '.evidenceText // ""' <<<"$case_json" | sed -E 's/[[:space:]]+/ /g' | cut -d' ' -f1-8)"
  printf '%s %s %s %s %s\n' "$title" "$location" "$description" "$date_hint" "$ocr_hint" \
    | sed -E 's/[[:space:]]+/ /g; s/^[[:space:]]+//; s/[[:space:]]+$//'
}

normalize_url() {
  printf '%s' "$1" \
    | sed -E 's#^https?://##; s#^www\.##; s#[?#].*$##; s#/$##' \
    | tr '[:upper:]' '[:lower:]'
}

score_url_match() {
  local selected_url="$1"
  local expected_url="$2"
  if [[ -z "$selected_url" || -z "$expected_url" ]]; then
    printf '0\n'
    return
  fi
  local lhs rhs
  lhs="$(normalize_url "$selected_url")"
  rhs="$(normalize_url "$expected_url")"
  if [[ "$lhs" == "$rhs" ]]; then
    printf '1\n'
    return
  fi
  printf '0\n'
}

score_ranked_candidate() {
  local query="$1"
  local rank="$2"
  local url="$3"
  local title="$4"
  local query_tokens candidate_tokens overlap event_bonus pdf_penalty phrase_bonus
  query_tokens="$(normalize_words "$query")"
  candidate_tokens="$(normalize_words "$title $url")"
  overlap="$(comm -12 <(printf '%s\n' $query_tokens | sort -u) <(printf '%s\n' $candidate_tokens | sort -u) | wc -l | tr -d ' ')"
  event_bonus=0
  if grep -Eiq '/(event|events|agenda|programmation)/' <<<"$url"; then
    event_bonus=4
  fi
  pdf_penalty=0
  if grep -Eiq '\.pdf($|[?#])' <<<"$url"; then
    pdf_penalty=6
  fi
  phrase_bonus=0
  local likely_title
  likely_title="$(sed -E 's/[0-9]{4}.*$//' <<<"$query" | sed -E 's/[[:space:]]+/ /g; s/^[[:space:]]+//; s/[[:space:]]+$//' | tr '[:upper:]' '[:lower:]')"
  if [[ -n "$likely_title" ]] && grep -Fq "$likely_title" <<<"$(tr '[:upper:]' '[:lower:]' <<<"$title")"; then
    phrase_bonus=12
  fi
  printf '%s\n' $((overlap * 4 + event_bonus + phrase_bonus - pdf_penalty - rank))
}

ranked_result_url() {
  local query="$1"
  local best_score=-9999
  local best_url=""
  local line
  while IFS=$'\t' read -r rank url title; do
    [[ -n "${url:-}" ]] || continue
    local score
    score="$(score_ranked_candidate "$query" "$rank" "$url" "$title")"
    if (( score > best_score )); then
      best_score="$score"
      best_url="$url"
    fi
  done < <(search_duckduckgo_candidates "$query")
  printf '%s\n' "$best_url"
}

brave_ranked_result_url() {
  local query="$1"
  local best_score=-9999
  local best_url=""
  while IFS=$'\t' read -r rank url title description; do
    [[ -n "${url:-}" ]] || continue
    local score
    score="$(score_ranked_candidate "$query" "$rank" "$url" "$title $description")"
    if (( score > best_score )); then
      best_score="$score"
      best_url="$url"
    fi
  done < <(search_brave_candidates "$query")
  printf '%s\n' "$best_url"
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
  local total heuristic_hits agentic_hits app_ranked_hits heuristic_ranked_hits brave_ranked_hits
  total="$(jq '.cases | length' "$MANIFEST")"
  heuristic_hits=0
  agentic_hits=0
  app_ranked_hits=0
  heuristic_ranked_hits=0
  brave_ranked_hits=0

  while IFS= read -r case_json; do
    local expected_title expected_url heuristic_query agentic_query app_query heuristic_result agentic_result app_ranked_url heuristic_ranked_url brave_ranked_url
    expected_title="$(jq -r '.pageTitle // .sourceTitle' <<<"$case_json")"
    expected_url="$(jq -r '.sourceUrl' <<<"$case_json")"
    heuristic_query="$(build_heuristic_query "$case_json")"
    agentic_query="$(build_agentic_query "$case_json")"
    app_query="$(build_app_query "$case_json")"
    heuristic_result="$(search_duckduckgo "$heuristic_query")"
    agentic_result="$(search_duckduckgo "$agentic_query")"
    heuristic_ranked_url="$(ranked_result_url "$heuristic_query")"
    app_ranked_url="$(ranked_result_url "$app_query")"
    brave_ranked_url="$(brave_ranked_result_url "$app_query")"

    heuristic_hits=$((heuristic_hits + $(score_title_match "$heuristic_result" "$expected_title")))
    agentic_hits=$((agentic_hits + $(score_title_match "$agentic_result" "$expected_title")))
    heuristic_ranked_hits=$((heuristic_ranked_hits + $(score_url_match "$heuristic_ranked_url" "$expected_url")))
    app_ranked_hits=$((app_ranked_hits + $(score_url_match "$app_ranked_url" "$expected_url")))
    if [[ -n "$BRAVE_SEARCH_API_KEY" ]]; then
      brave_ranked_hits=$((brave_ranked_hits + $(score_url_match "$brave_ranked_url" "$expected_url")))
    fi
  done < <(jq -c '.cases[]' "$MANIFEST")

  awk -v total="$total" \
    -v heuristic="$heuristic_hits" \
    -v agentic="$agentic_hits" \
    -v heuristic_ranked="$heuristic_ranked_hits" \
    -v app_ranked="$app_ranked_hits" \
    -v brave_ranked="$brave_ranked_hits" \
    -v brave_enabled="$([[ -n "$BRAVE_SEARCH_API_KEY" ]] && printf 1 || printf 0)" '
    BEGIN {
      printf("cases=%d\n", total)
      printf("heuristic_first_title_top1=%.1f%%\n", (100.0 * heuristic / total))
      printf("agentic_first_title_top1=%.1f%%\n", (100.0 * agentic / total))
      printf("heuristic_ranked_url_top8=%.1f%%\n", (100.0 * heuristic_ranked / total))
      printf("app_ranked_url_top8=%.1f%%\n", (100.0 * app_ranked / total))
      if (brave_enabled) {
        printf("brave_app_ranked_url_top8=%.1f%%\n", (100.0 * brave_ranked / total))
      } else {
        printf("brave_app_ranked_url_top8=skipped_no_api_key\n")
      }
      printf("app_vs_heuristic_first_delta=%.1f pts\n", (100.0 * app_ranked / total) - (100.0 * heuristic / total))
    }
  '
}

main "$@"
