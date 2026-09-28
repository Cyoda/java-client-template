#!/usr/bin/env bash
# Vendors cyoda-go's contract files into src/main/resources/cyoda/, unmodified.
# Spec: docs/superpowers/specs/2026-09-26-cyoda-go-alignment-design.md §3.2
set -euo pipefail

# For a released version (not -dev) it also records the release's per-platform archive checksums in
# src/main/resources/cyoda/CYODA_SHA256SUMS, which scripts/install-cyoda.sh verifies downloads against. They
# come from the release's SHA256SUMS on GitHub, or from --sha256sums <file> (e.g. when offline).

usage() {
  echo "usage: $0 --from-src <cyoda-go checkout> --version <x.y.z[-dev]> [--sha256sums <file>]" >&2
  exit 2
}

SRC=""
VERSION=""
SUMS_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from-src) [ $# -ge 2 ] || usage; SRC="$2"; shift 2 ;;
    --version)  [ $# -ge 2 ] || usage; VERSION="$2"; shift 2 ;;
    --sha256sums) [ $# -ge 2 ] || usage; SUMS_FILE="$2"; shift 2 ;;
    *) usage ;;
  esac
done
[ -n "$SRC" ] && [ -n "$VERSION" ] || usage
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-dev)?$ ]] || { echo "invalid --version '$VERSION'" >&2; exit 2; }
for f in api/openapi.yaml proto/cyoda/cyoda-cloud-api.proto proto/cloudevents/cloudevents.proto docs/cyoda/schema; do
  [ -e "$SRC/$f" ] || { echo "$SRC is not a cyoda-go checkout (missing $f)" >&2; exit 1; }
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/src/main/resources/cyoda"
COMMIT="$(git -C "$SRC" rev-parse HEAD)"

# A release pin's archive checksums, read before anything is changed so a failure leaves the tree as it was.
PINNED_SUMS=""
if [[ "$VERSION" != *-dev ]]; then
  if [ -z "$SUMS_FILE" ]; then
    SUMS_FILE="$(mktemp)"
    trap 'rm -f "$SUMS_FILE"' EXIT
    url="https://github.com/Cyoda/cyoda-go/releases/download/v${VERSION}/SHA256SUMS"
    curl -fsSL "$url" -o "$SUMS_FILE" || { echo "cannot download $url (or pass --sha256sums <file>)" >&2; exit 1; }
  fi
  [ -f "$SUMS_FILE" ] || { echo "--sha256sums $SUMS_FILE does not exist" >&2; exit 1; }
  PINNED_SUMS="$(grep -E "^[0-9a-f]{64} [ *]cyoda_${VERSION//./\\.}_[a-z0-9]+_[a-z0-9]+\\.tar\\.gz$" "$SUMS_FILE" || true)"
  [ -n "$PINNED_SUMS" ] || { echo "no cyoda_${VERSION}_<os>_<arch>.tar.gz checksums in $SUMS_FILE" >&2; exit 1; }
fi

rm -rf "$DEST/proto" "$DEST/schema" "$DEST/openapi"
mkdir -p "$DEST/proto/cyoda" "$DEST/proto/cloudevents" "$DEST/schema" "$DEST/openapi"
cp "$SRC/proto/cyoda/cyoda-cloud-api.proto" "$DEST/proto/cyoda/"
cp "$SRC/proto/cloudevents/cloudevents.proto" "$DEST/proto/cloudevents/"
(
  cd "$SRC/docs/cyoda/schema"
  find . -name '*.json' -print0 | while IFS= read -r -d '' f; do
    mkdir -p "$DEST/schema/$(dirname "$f")"
    cp "$f" "$DEST/schema/$f"
  done
)
cp "$SRC/api/openapi.yaml" "$DEST/openapi/openapi.yaml"
printf '%s\ncommit=%s\n' "$VERSION" "$COMMIT" > "$DEST/CYODA_VERSION"
if [ -n "$PINNED_SUMS" ]; then
  printf '%s\n' "$PINNED_SUMS" > "$DEST/CYODA_SHA256SUMS"
else
  # A -dev pin is built from source at the pinned commit; it has no release archive to check.
  rm -f "$DEST/CYODA_SHA256SUMS"
fi
echo "synced cyoda-go $VERSION ($COMMIT) into $DEST"
