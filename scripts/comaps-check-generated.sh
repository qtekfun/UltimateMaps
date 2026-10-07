#!/usr/bin/env bash
#
# Fails if the committed generated CoMaps files are stale or were edited by hand:
#   1. every file listed in native-comaps/generated/MANIFEST must exist with the recorded SHA-256;
#   2. the submodule commit recorded in the MANIFEST must equal the one pinned by the superproject
#      (a submodule bump without re-running scripts/comaps-generate.sh fails here).
# Needs neither the submodule checkout, nor network, nor PyPI. Exit 0 = consistent.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
M=native-comaps/generated/MANIFEST
[ -f "$M" ] || { echo "ERROR: $M is missing: run scripts/comaps-generate.sh" >&2; exit 1; }

want="$(awk '$1=="comaps-commit"{print $2}' "$M")"
have="$(git ls-files -s third_party/comaps | awk '{print $2}')"
if [ "$want" != "$have" ]; then
  echo "ERROR: generated files are from CoMaps $want but the submodule pins $have: run scripts/comaps-generate.sh and commit" >&2
  exit 1
fi
grep -v '^#' "$M" | grep -v '^comaps-commit' | sha256sum -c --quiet - || {
  echo "ERROR: a generated file differs from the MANIFEST: run scripts/comaps-generate.sh and commit" >&2
  exit 1
}
echo "OK: generated CoMaps files match the MANIFEST and CoMaps $have"
