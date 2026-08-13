#!/usr/bin/env bash
#
# Fail-closed verifier for the model asset digests/sizes pinned in
# LiteRtModelCatalog.kt.
#
# For every catalog entry it re-derives repo/revision/path from the pinned
# HuggingFace download URL and confirms that BOTH the LFS size and the LFS
# sha256 reported by the HuggingFace API match the catalog exactly.
#
# Fails (non-zero exit) on: missing API fields, non-LFS assets,
# unpinned/moving URLs, unparseable entries, or any entry that could not be
# verified. No silent skips. The exact model/path that failed is reported.
#
# Auth: gated repos (e.g. google/gemma-3n-*) hide lfs.oid as "****" without an
# access token, which fails-closed here (masked oid never matches a real
# digest). Provide HF_TOKEN / HUGGING_FACE_HUB_TOKEN; otherwise the standard
# huggingface-cli cache token (~/.cache/huggingface/token) is used as a
# fallback so the checker runs locally as well as in CI.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_SRC="$ROOT_DIR/app/src/main/java/com/calendaradd/service/LiteRtModelCatalog.kt"

print_usage() {
  cat >&2 <<EOF
Usage: $0 [--source PATH]
  --source PATH   Catalog .kt file to verify (default: the worktree catalog).
                  Env override: CHECK_CATALOG_SRC
  -h, --help      Show this help.
EOF
}

SRC=""
while [ $# -gt 0 ]; do
  case "$1" in
    --source) SRC="${2:?--source requires a path}"; shift 2 ;;
    -h|--help) print_usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; print_usage; exit 2 ;;
  esac
done

# Source override: flag > env > default worktree catalog.
SRC="${SRC:-${CHECK_CATALOG_SRC:-$DEFAULT_SRC}}"
if [ ! -f "$SRC" ]; then
  echo "error: catalog source not found: $SRC" >&2
  exit 2
fi

# Resolve a HuggingFace access token (needed to read gated repos). The cache
# fallback mirrors huggingface_hub so the script works without extra setup.
if [ -z "${HF_TOKEN:-${HUGGING_FACE_HUB_TOKEN:-}}" ]; then
  CACHE_TOKEN="$HOME/.cache/huggingface/token"
  if [ -f "$CACHE_TOKEN" ]; then
    HF_TOKEN="$(tr -d '[:space:]' < "$CACHE_TOKEN")"
  else
    HF_TOKEN=""
  fi
fi
export HF_TOKEN

# The strict parse + API verification + fail-closed accounting live in one
# python3 pass so JSON handling and error reporting stay coherent. bash owns
# the CLI/env contract and the fail-fast shebang guarantees.
HF_TOKEN="$HF_TOKEN" python3 - "$SRC" <<'PY'
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

SRC = sys.argv[1]
TOKEN = os.environ.get("HF_TOKEN") or os.environ.get("HUGGING_FACE_HUB_TOKEN") or ""


def fail(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


# --- strict catalog parser ---------------------------------------------------
# Every LiteRtModelConfig(...) block must expose id, downloadUrl, fileName,
# sizeBytes and sha256. Anything we cannot parse verbatim is a hard failure.
def parse_catalog(path):
    text = open(path, encoding="utf-8").read()
    # A config block ends with a closing paren on its own line; the trailing
    # comma is optional (the last entry in listOf has none). The negative
    # lookbehind skips the `data class LiteRtModelConfig(` declaration.
    block_re = r"(?<!data class )LiteRtModelConfig\(\s*(.*?)\n\s*\),?"
    blocks = re.findall(block_re, text, re.DOTALL)
    if not blocks:
        fail(f"no LiteRtModelConfig entries parsed from {path}")
    # Fail-closed against silent skips: every parsed block must carry exactly
    # one id field, so the counts must agree.
    n_ids = len(re.findall(r'\bid\s*=\s*"', text))
    if len(blocks) != n_ids:
        fail(f"parsed {len(blocks)} config blocks but found {n_ids} id fields "
             f"in {path} (silent skip detected)")
    entries = []
    for block in blocks:
        def grab(name):
            m = re.search(r'\b' + name + r'\s*=\s*"([^"]*)"', block)
            return m.group(1) if m else None
        mid = grab("id")
        url = grab("downloadUrl")
        fname = grab("fileName")
        sha = grab("sha256")
        sm = re.search(r"\bsizeBytes\s*=\s*([0-9_]+)L", block)
        size = int(sm.group(1).replace("_", "")) if sm else None
        values = {"id": mid, "downloadUrl": url, "fileName": fname,
                  "sizeBytes": size, "sha256": sha}
        missing = [k for k, v in values.items() if v in (None, "")]
        if missing:
            fail(f"unparseable catalog entry (missing {missing}):\n{block.strip()[:240]}")
        entries.append((mid, url, fname, size, sha))
    return entries


# --- pinned-URL parser -------------------------------------------------------
URL_RE = re.compile(
    r"^https://huggingface\.co/(.+?)/resolve/([^/]+)/(.+?)(?:\?download=true)?$"
)


def split_url(url, mid):
    m = URL_RE.match(url)
    if not m:
        fail(f"{mid}: download URL is not a pinned HuggingFace resolve URL: {url}")
    repo, rev, path = m.group(1), m.group(2), m.group(3)
    # A moving ref (branch/tag) is not an immutable pin.
    if not re.fullmatch(r"[0-9a-f]{40}", rev):
        fail(f"{mid}: revision is not a 40-hex commit pin: {rev}")
    return repo, rev, path


# --- HuggingFace API query: single retry with backoff -----------------------
def fetch_tree(repo, rev):
    url = f"https://huggingface.co/api/models/{repo}/tree/{rev}"
    last_err = None
    for attempt in (1, 2):
        req = urllib.request.Request(url)
        if TOKEN:
            req.add_header("Authorization", f"Bearer {TOKEN}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                return json.load(resp), None
        except urllib.error.HTTPError as e:
            last_err = f"HTTP {e.code}"
            # Retry once on rate-limit or transient server error; otherwise stop.
            if attempt == 1 and e.code in (429, 500, 502, 503, 504):
                time.sleep(5)
                continue
            return None, last_err
        except urllib.error.URLError as e:
            last_err = f"network: {e.reason}"
            if attempt == 1:
                time.sleep(5)
                continue
            return None, last_err
    return None, last_err or "network-error"


# --- main --------------------------------------------------------------------
entries = parse_catalog(SRC)
print(f"Verifying {len(entries)} catalog entries against HuggingFace API...")
failures = []

for mid, url, fname, size, sha in entries:
    repo, rev, path = split_url(url, mid)
    tree, err = fetch_tree(repo, rev)
    if err:
        # Could not query the API (even after retry): fail-closed, never skip.
        failures.append(f"{mid} ({repo}@{rev}): API query failed ({err})")
        continue
    hits = [e for e in tree if e.get("path") == path]
    if not hits:
        failures.append(f"{mid}: path '{path}' not found in {repo}@{rev}")
        continue
    entry = hits[0]
    lfs = entry.get("lfs")
    api_sha = (lfs or {}).get("oid")
    api_size = (lfs or {}).get("size")
    if not lfs or api_sha is None or api_size is None:
        failures.append(f"{mid} ({path}): not an LFS asset or missing lfs fields")
        continue
    # A masked oid (HF hides it without gate access) never matches a real
    # digest, so verification fails-closed automatically for unauthenticated
    # access to gated repos.
    reasons = []
    if api_sha != sha:
        reasons.append(f"sha256: catalog={sha} api={api_sha}")
    if api_size != size:
        reasons.append(f"size: catalog={size} api={api_size}")
    if reasons:
        failures.append(f"{mid} ({repo}@{rev} {path}): " + "; ".join(reasons))
    else:
        print(f"  OK   {mid}: {repo}@{rev[:8]} {path} ({size} bytes)")

if failures:
    print("\nModel catalog hash verification FAILED:", file=sys.stderr)
    for f in failures:
        print(f"  - {f}", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(entries)} entries verified: sha256 + size match HuggingFace LFS.")
PY
