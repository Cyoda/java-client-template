#!/usr/bin/env bash
# Vendors cyoda-go's contract files into src/main/resources/cyoda/, unmodified.
# Spec: docs/superpowers/specs/2026-09-26-cyoda-go-alignment-design.md §3.2
set -euo pipefail

usage() {
  echo "usage: $0 --from-src <cyoda-go checkout> --version <x.y.z[-dev]>" >&2
  exit 2
}

SRC=""
VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --from-src) SRC="${2:-}"; shift 2 ;;
    --version)  VERSION="${2:-}"; shift 2 ;;
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
echo "synced cyoda-go $VERSION ($COMMIT) into $DEST"
