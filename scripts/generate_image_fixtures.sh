#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/app/src/test/resources/image-fixtures"
MANIFEST="$OUT_DIR/manifest.json"
FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

render_flyer() {
  local out_path="$1"
  local title="$2"
  local date="$3"
  local time="$4"
  local location="$5"
  local description="$6"
  local accent="$7"

  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' RETURN

  printf '%s' "$title" > "$tmpdir/title.txt"
  printf '%s' "$date" > "$tmpdir/date.txt"
  printf '%s' "$time" > "$tmpdir/time.txt"
  printf '%s' "$location" > "$tmpdir/location.txt"
  printf '%s' "$description" > "$tmpdir/description.txt"

  ffmpeg -hide_banner -y \
    -f lavfi -i "color=c=0xF8FAFC:s=1200x1600:d=1" \
    -frames:v 1 \
    -vf "
      drawbox=x=0:y=0:w=1200:h=220:color=${accent}@0.95:t=fill,
      drawtext=fontfile=${FONT}:textfile=${tmpdir}/title.txt:fontcolor=white:fontsize=46:x=60:y=86:line_spacing=10,
      drawtext=fontfile=${FONT}:textfile=${tmpdir}/date.txt:fontcolor=0x0F172A:fontsize=42:x=60:y=300:line_spacing=10,
      drawtext=fontfile=${FONT}:textfile=${tmpdir}/time.txt:fontcolor=0x1D4ED8:fontsize=44:x=60:y=380:line_spacing=10,
      drawtext=fontfile=${FONT}:textfile=${tmpdir}/location.txt:fontcolor=0x111827:fontsize=38:x=60:y=490:line_spacing=10,
      drawtext=fontfile=${FONT}:textfile=${tmpdir}/description.txt:fontcolor=0x374151:fontsize=26:x=60:y=640:line_spacing=12,
      drawtext=fontfile=${FONT}:text='Event flyer':fontcolor=0x94A3B8:fontsize=24:x=60:y=1480
    " \
    "$out_path" >/dev/null 2>&1

  rm -rf "$tmpdir"
  trap - RETURN
}

render_schedule_flyer() {
  local out_path="$1"
  local title="$2"
  local location="$3"
  local description="$4"
  local accent="$5"
  local rows_json="$6"

  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' RETURN

  printf '%s' "$title" > "$tmpdir/title.txt"
  printf '%s' "$location" > "$tmpdir/location.txt"
  printf '%s' "$description" > "$tmpdir/description.txt"

  local vf_filters
  vf_filters="drawbox=x=0:y=0:w=1200:h=220:color=${accent}@0.95:t=fill,"
  vf_filters+="drawtext=fontfile=${FONT}:textfile=${tmpdir}/title.txt:fontcolor=white:fontsize=46:x=60:y=86:line_spacing=10,"

  local row_index=0
  while IFS= read -r row; do
    local row_base row_date row_title row_time
    row_base=$((300 + row_index * 185))
    row_date="$(jq -r '.date' <<<"$row")"
    row_title="$(jq -r '.title' <<<"$row")"
    row_time="$(jq -r '.time' <<<"$row")"

    printf '%s' "$row_date" > "$tmpdir/date_${row_index}.txt"
    printf '%s' "$row_title" > "$tmpdir/rowtitle_${row_index}.txt"
    printf '%s' "$row_time" > "$tmpdir/time_${row_index}.txt"

    vf_filters+="drawbox=x=40:y=$((row_base - 18)):w=1120:h=150:color=0xCBD5E1@0.95:t=3,"
    vf_filters+="drawtext=fontfile=${FONT}:textfile=${tmpdir}/date_${row_index}.txt:fontcolor=0x0F172A:fontsize=32:x=60:y=${row_base}:line_spacing=8,"
    vf_filters+="drawtext=fontfile=${FONT}:textfile=${tmpdir}/rowtitle_${row_index}.txt:fontcolor=0x111827:fontsize=36:x=60:y=$((row_base + 44)):line_spacing=8,"
    vf_filters+="drawtext=fontfile=${FONT}:textfile=${tmpdir}/time_${row_index}.txt:fontcolor=0x1D4ED8:fontsize=32:x=60:y=$((row_base + 92)):line_spacing=8,"
    row_index=$((row_index + 1))
  done < <(jq -c '.[]' <<<"$rows_json")

  vf_filters+="drawtext=fontfile=${FONT}:textfile=${tmpdir}/location.txt:fontcolor=0x111827:fontsize=30:x=60:y=$((300 + row_index * 185 + 30)):line_spacing=8,"
  vf_filters+="drawtext=fontfile=${FONT}:textfile=${tmpdir}/description.txt:fontcolor=0x374151:fontsize=24:x=60:y=$((300 + row_index * 185 + 120)):line_spacing=12,"
  vf_filters+="drawtext=fontfile=${FONT}:text='Event flyer':fontcolor=0x94A3B8:fontsize=24:x=60:y=1740"

  ffmpeg -hide_banner -y \
    -f lavfi -i "color=c=0xF8FAFC:s=1200x1800:d=1" \
    -frames:v 1 \
    -vf "$vf_filters" \
    "$out_path" >/dev/null 2>&1

  rm -rf "$tmpdir"
  trap - RETURN
}

case_count="$(jq '.cases | length' "$MANIFEST")"
for ((i = 0; i < case_count; i++)); do
  case_json="$(jq -c ".cases[$i]" "$MANIFEST")"
  id="$(jq -r '.id' <<<"$case_json")"
  image_file="$(jq -r '.imageFile' <<<"$case_json")"
  title="$(jq -r '.flyerText.title' <<<"$case_json")"
  date="$(jq -r '.flyerText.date' <<<"$case_json")"
  time="$(jq -r '.flyerText.time' <<<"$case_json")"
  location="$(jq -r '.flyerText.location' <<<"$case_json")"
  description="$(jq -r '.flyerText.description' <<<"$case_json")"
  schedule_rows="$(jq -c '.scheduleRows // empty' <<<"$case_json")"

  mkdir -p "$OUT_DIR/$(dirname "$image_file")"
  if [[ -n "$schedule_rows" ]]; then
    render_schedule_flyer \
      "$OUT_DIR/$image_file" \
      "$title" \
      "$location" \
      "$description" \
      "$(case $((i % 5)) in
        0) echo 0x7C3AED ;;
        1) echo 0x0F766E ;;
        2) echo 0xB91C1C ;;
        3) echo 0x1D4ED8 ;;
        *) echo 0xB45309 ;;
      esac)" \
      "$schedule_rows"
  else
    render_flyer \
      "$OUT_DIR/$image_file" \
      "$title" \
      "$date" \
      "$time" \
      "$location" \
      "$description" \
      "$(case $((i % 5)) in
        0) echo 0x7C3AED ;;
        1) echo 0x0F766E ;;
        2) echo 0xB91C1C ;;
        3) echo 0x1D4ED8 ;;
        *) echo 0xB45309 ;;
      esac)"
  fi
  echo "Generated $id -> $OUT_DIR/$image_file"
done
