#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE_MANIFEST="$ROOT_DIR/app/src/test/resources/image-fixtures/manifest.json"
SEED_MANIFEST="$ROOT_DIR/app/src/test/resources/web-lookup-seeds.json"
TARGET_DIR="$ROOT_DIR/app/src/test/resources/web-lookup-fixtures"
TARGET_MANIFEST="$TARGET_DIR/manifest.json"

require_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "$tool is required." >&2
    exit 1
  fi
}

require_tool jq
require_tool curl
require_tool perl
require_tool mkdir

mkdir -p "$TARGET_DIR"

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

extract_title() {
  perl -0ne '
    if (/<meta[^>]+property=["'"'"'"'"'"']og:title["'"'"'"'"'"'][^>]+content=["'"'"'"'"'"']([^"'"'"'"'"'"'<>]+)["'"'"'"'"'"']/i) { print $1; exit }
    if (/<meta[^>]+name=["'"'"'"'"'"']twitter:title["'"'"'"'"'"'][^>]+content=["'"'"'"'"'"']([^"'"'"'"'"'"'<>]+)["'"'"'"'"'"']/i) { print $1; exit }
    if (/<title[^>]*>(.*?)<\/title>/is) {
      my $t = $1;
      $t =~ s/\s+/ /g;
      print $t;
      exit;
    }
  '
}

extract_description() {
  perl -0ne '
    if (/<meta[^>]+property=["'"'"'"'"'"']og:description["'"'"'"'"'"'][^>]+content=["'"'"'"'"'"']([^"'"'"'"'"'"'<>]+)["'"'"'"'"'"']/i) { print $1; exit }
    if (/<meta[^>]+name=["'"'"'"'"'"']description["'"'"'"'"'"'][^>]+content=["'"'"'"'"'"']([^"'"'"'"'"'"'<>]+)["'"'"'"'"'"']/i) { print $1; exit }
    if (/<meta[^>]+name=["'"'"'"'"'"']twitter:description["'"'"'"'"'"'][^>]+content=["'"'"'"'"'"']([^"'"'"'"'"'"'<>]+)["'"'"'"'"'"']/i) { print $1; exit }
  '
}

extract_canonical() {
  perl -0ne '
    if (/<link[^>]+rel=["'"'"'"'"'"']canonical["'"'"'"'"'"'][^>]+href=["'"'"'"'"'"']([^"'"'"'"'"'"'<>]+)["'"'"'"'"'"']/i) { print $1; exit }
  '
}

fetch_page_snapshot() {
  local url="$1"
  local html
  html="$(curl -L -s --max-time 20 "$url" || true)"
  if [[ -z "$html" ]]; then
    jq -n --arg url "$url" '{title: "", description: "", canonicalUrl: $url}'
    return
  fi

  local title description canonical
  title="$(printf '%s' "$html" | extract_title || true)"
  description="$(printf '%s' "$html" | extract_description || true)"
  canonical="$(printf '%s' "$html" | extract_canonical || true)"
  jq -n \
    --arg title "${title:-}" \
    --arg description "${description:-}" \
    --arg canonical "${canonical:-}" \
    '{title: $title, description: $description, canonicalUrl: $canonical}'
}

main() {
  {
    jq -c '.cases[]' "$SOURCE_MANIFEST"
    if [[ -f "$SEED_MANIFEST" ]]; then
      jq -c '.cases[]' "$SEED_MANIFEST"
    fi
  } | while IFS= read -r case_json; do
    local id source_title source_url evidence_text snapshot page_title page_description page_canonical
    id="$(jq -r '.id' <<<"$case_json")"
    source_title="$(jq -r '.sourceTitle' <<<"$case_json")"
    source_url="$(jq -r '.sourceUrl' <<<"$case_json")"
    evidence_text="$(case_ocr_text "$case_json" | paste -sd ' ' -)"
    snapshot="$(fetch_page_snapshot "$source_url")"
    page_title="$(jq -r '.title' <<<"$snapshot")"
    page_description="$(jq -r '.description' <<<"$snapshot")"
    page_canonical="$(jq -r '.canonicalUrl' <<<"$snapshot")"

    jq -n \
      --arg id "$id" \
      --arg sourceTitle "$source_title" \
      --arg sourceUrl "$source_url" \
      --arg pageTitle "${page_title:-$source_title}" \
      --arg pageDescription "${page_description:-}" \
      --arg canonicalUrl "${page_canonical:-$source_url}" \
      --arg evidenceText "$evidence_text" \
      '{
        id: $id,
        sourceTitle: $sourceTitle,
        sourceUrl: $sourceUrl,
        pageTitle: $pageTitle,
        pageDescription: $pageDescription,
        canonicalUrl: $canonicalUrl,
        evidenceText: $evidenceText
      }'
  done | jq -s '
    {cases: ([.[]] | unique_by(.id))}
  ' > "$TARGET_MANIFEST"
}

main "$@"
