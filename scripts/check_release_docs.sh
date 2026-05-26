#!/bin/bash

set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"

version_name="$(
    sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts |
        head -n 1
)"

if [ -z "$version_name" ]; then
    echo "Unable to read versionName from app/build.gradle.kts" >&2
    exit 1
fi

if ! grep -q "github.com/chpomob/calendar_add/releases/download/v${version_name}/" README.md; then
    echo "README.md does not contain a download URL for versionName ${version_name}." >&2
    echo "Expected a URL matching: github.com/chpomob/calendar_add/releases/download/v${version_name}/..." >&2
    exit 1
fi

unexpected_urls="$(
    grep -Eo 'https://github\.com/chpomob/calendar_add/releases/download/v[^[:space:])`]+' README.md |
        grep -v "v${version_name}/" || true
)"

if [ -n "$unexpected_urls" ]; then
    echo "README.md contains stale direct release download URL(s):" >&2
    echo "$unexpected_urls" >&2
    exit 1
fi

echo "Release docs match versionName ${version_name}."
