#!/usr/bin/env bash
# Installs the cyoda binary pinned in src/main/resources/cyoda/CYODA_VERSION (spec §7.4).
#   scripts/install-cyoda.sh                       released pin: download; -dev pin: build pinned commit from GitHub
#   scripts/install-cyoda.sh --from-src [<ref>]    build from GitHub at <ref> (default: the pinned commit)
#   scripts/install-cyoda.sh --src-dir <checkout>  build an existing local checkout (its HEAD must be the pinned commit)
#   --dest <dir>                                   output directory (default: .cyoda/bin, git-ignored)
# The default location survives `./gradlew clean`, and the tests find the binary there without configuration.
# Prints the binary path on stdout (use it as CYODA_BIN for a --dest elsewhere). Never runs `cyoda init`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="$ROOT/src/main/resources/cyoda/CYODA_VERSION"
VERSION="$(sed -n 1p "$PIN")"
COMMIT="$(sed -n 2p "$PIN" | sed 's/^commit=//')"
DEST="$ROOT/.cyoda/bin"
MODE=""
REF=""
SRC_DIR=""

fail() { echo "install-cyoda: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-src)
      MODE=github
      if [ $# -gt 1 ] && [[ "$2" != --* ]]; then REF="$2"; shift; fi
      shift ;;
    --src-dir) [ $# -ge 2 ] || fail "--src-dir needs a value"; MODE=local; SRC_DIR="$2"; shift 2 ;;
    --dest) [ $# -ge 2 ] || fail "--dest needs a value"; DEST="$2"; shift 2 ;;
    *) fail "unknown argument $1" ;;
  esac
done
if [ -z "$MODE" ]; then
  if [[ "$VERSION" == *-dev ]]; then MODE=github; else MODE=release; fi
fi
mkdir -p "$DEST"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

OVERRIDE_HINT="or install cyoda yourself and point CYODA_BIN (or -Dcyoda.bin) at it"

require_go() {
  local why=""
  if [[ "$VERSION" == *-dev ]]; then why=" (the pin $VERSION is a -dev version, which has no published release to download)"; fi
  command -v go >/dev/null || fail "Go >= 1.26.7 is required to build cyoda from source$why. Install Go, $OVERRIDE_HINT"
}

build_checkout() { # <checkout dir>
  require_go
  local sha date
  sha="$(git -C "$1" rev-parse HEAD)"
  date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  ( cd "$1" && CGO_ENABLED=0 go build \
      -ldflags "-X main.version=$VERSION -X main.commit=$sha -X main.buildDate=$date" \
      -o "$DEST/cyoda" ./cmd/cyoda ) \
    || fail "go build failed in $1 (it needs Go >= 1.26.7 and, to fetch modules, network access), $OVERRIDE_HINT"
}

case "$MODE" in
  release)
    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    case "$arch" in x86_64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; esac
    asset="cyoda_${VERSION}_${os}_${arch}.tar.gz"
    base="https://github.com/Cyoda/cyoda-go/releases/download/v${VERSION}"
    download_hint="a released pin is downloaded, which needs network access to github.com and a published release v$VERSION with an asset for $os/$arch; $OVERRIDE_HINT"
    curl -fsSL "$base/$asset" -o "$WORK/$asset" || fail "cannot download $base/$asset: $download_hint"
    curl -fsSL "$base/SHA256SUMS" -o "$WORK/SHA256SUMS" || fail "cannot download $base/SHA256SUMS: $download_hint"
    if command -v sha256sum >/dev/null; then
      checksum_tool=(sha256sum -c -)
    elif command -v shasum >/dev/null; then
      checksum_tool=(shasum -a 256 -c -)
    else
      fail "need sha256sum or shasum"
    fi
    ( cd "$WORK" && grep " $asset\$" SHA256SUMS | "${checksum_tool[@]}" >/dev/null ) || fail "checksum mismatch for $asset"
    tar -xzf "$WORK/$asset" -C "$WORK" cyoda
    mv "$WORK/cyoda" "$DEST/cyoda"
    ;;
  github)
    [ -n "$REF" ] || REF="$COMMIT"
    require_go
    git clone --quiet https://github.com/Cyoda/cyoda-go.git "$WORK/cyoda-go" \
      || fail "cannot clone https://github.com/Cyoda/cyoda-go.git: a source build needs network access to github.com, $OVERRIDE_HINT"
    git -C "$WORK/cyoda-go" checkout --quiet "$REF" || fail "cannot check out $REF in cyoda-go"
    build_checkout "$WORK/cyoda-go"
    ;;
  local)
    [ -d "$SRC_DIR/cmd/cyoda" ] || fail "$SRC_DIR is not a cyoda-go checkout"
    head="$(git -C "$SRC_DIR" rev-parse HEAD)"
    [ "$head" = "$COMMIT" ] || fail "$SRC_DIR is at $head but the pin is $COMMIT (sync the pin or check out the pinned commit)"
    build_checkout "$SRC_DIR"
    ;;
esac

chmod +x "$DEST/cyoda"
"$DEST/cyoda" --version >&2
echo "$DEST/cyoda"
