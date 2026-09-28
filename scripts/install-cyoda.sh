#!/usr/bin/env bash
# Installs the cyoda binary pinned in src/main/resources/cyoda/CYODA_VERSION (spec §7.4).
#   scripts/install-cyoda.sh                       released pin: download; -dev pin: build pinned commit from GitHub
#   scripts/install-cyoda.sh --from-src [<ref>]    build from GitHub at <ref> (default: the pinned commit)
#   scripts/install-cyoda.sh --src-dir <checkout>  build an existing local checkout (its HEAD must be the pinned commit)
#   --dest <dir>                                   output directory (default: .cyoda/bin, git-ignored)
#   --archive <file>                               released pin only: install this local release archive instead
#                                                  of downloading it (still verified against CYODA_SHA256SUMS)
# A released pin's archive must match the SHA-256 committed in src/main/resources/cyoda/CYODA_SHA256SUMS
# (written by scripts/sync-cyoda-contract.sh), not only the release's own SHA256SUMS; without that file the
# install fails. A -dev pin is built from source at the pinned commit and needs no checksum file.
# The default location survives `./gradlew clean`, and the tests find the binary there without configuration.
# Prints the binary path on stdout (use it as CYODA_BIN for a --dest elsewhere). Never runs `cyoda init`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="$ROOT/src/main/resources/cyoda/CYODA_VERSION"
PINNED_SUMS="$ROOT/src/main/resources/cyoda/CYODA_SHA256SUMS"
VERSION="$(sed -n 1p "$PIN")"
COMMIT="$(sed -n 2p "$PIN" | sed 's/^commit=//')"
DEST="$ROOT/.cyoda/bin"
MODE=""
REF=""
SRC_DIR=""
ARCHIVE=""

fail() { echo "install-cyoda: $*" >&2; exit 1; }
info() { echo "install-cyoda: $*" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-src)
      MODE=github
      if [ $# -gt 1 ] && [[ "$2" != --* ]]; then REF="$2"; shift; fi
      shift ;;
    --src-dir) [ $# -ge 2 ] || fail "--src-dir needs a value"; MODE=local; SRC_DIR="$2"; shift 2 ;;
    --dest) [ $# -ge 2 ] || fail "--dest needs a value"; DEST="$2"; shift 2 ;;
    --archive) [ $# -ge 2 ] || fail "--archive needs a value"; ARCHIVE="$2"; shift 2 ;;
    *) fail "unknown argument $1" ;;
  esac
done
if [ -z "$MODE" ]; then
  if [[ "$VERSION" == *-dev ]]; then MODE=github; else MODE=release; fi
fi
if [ -n "$ARCHIVE" ] && [ "$MODE" != release ]; then
  fail "--archive installs a released pin's archive; the pin $VERSION is built from source"
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

# Echoes the X.Y.Z version cyoda-go's go.mod asks for: its `go` directive (e.g. "go 1.26.7"), the
# real minimum the module needs, else its `toolchain` directive if present (e.g. "toolchain
# go1.26.7"), which only names the version `go build` will fetch and use and can overstate the
# requirement. Echoes nothing if <checkout dir>/go.mod is missing or has neither.
required_go_version() { # <checkout dir>
  local gomod="$1/go.mod" v
  [ -f "$gomod" ] || return 0
  v="$(sed -n 's/^[[:space:]]*go[[:space:]][[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$gomod" | head -n1)"
  if [ -z "$v" ]; then
    v="$(sed -n 's/^[[:space:]]*toolchain[[:space:]][[:space:]]*go\([0-9][0-9.]*\).*/\1/p' "$gomod" | head -n1)"
  fi
  echo "$v"
}

# Strips a trailing pre-release marker (rc/beta, e.g. Go's own "go1.26rc1") from a dot-separated
# version, leaving only its numeric prefix ("1.26"). Without this, version_lt's `-eq`/`-lt` numeric
# comparisons choke on a component like "26rc1" with a noisy "integer expression expected".
numeric_prefix() { # <version>
  printf '%s' "$1" | sed -E 's/^([0-9]+(\.[0-9]+)*).*/\1/'
}

# True (0) if version A is older than version B. Compares up to three dot-separated numeric
# components; a missing component counts as 0. Portable to bash 3.2 (macOS) and Linux bash.
version_lt() { # <A> <B>
  local a1 a2 a3 b1 b2 b3
  IFS=. read -r a1 a2 a3 <<<"$1"
  IFS=. read -r b1 b2 b3 <<<"$2"
  a1=${a1:-0}; a2=${a2:-0}; a3=${a3:-0}
  b1=${b1:-0}; b2=${b2:-0}; b3=${b3:-0}
  [ "$a1" -eq "$b1" ] || { [ "$a1" -lt "$b1" ]; return; }
  [ "$a2" -eq "$b2" ] || { [ "$a2" -lt "$b2" ]; return; }
  [ "$a3" -lt "$b3" ]
}

# Warns, before building, when the local Go is older than what the checkout's go.mod asks for:
# `go build` will otherwise try to download the newer toolchain itself (network-dependent, and a
# no-op under GOTOOLCHAIN=local), and a plain "go build failed" would not explain why.
warn_if_go_too_old() { # <checkout dir>
  local required local_ver
  required="$(required_go_version "$1")"
  [ -n "$required" ] || return 0
  # `|| true`: a bare (non-`local`) assignment's exit status is not masked the way `local x=$(...)`
  # masks it, so a `go env GOVERSION` failure here would otherwise propagate and kill the whole
  # script under `set -e`, silently (stderr is already suppressed above).
  local_ver="$(go env GOVERSION 2>/dev/null || true)"
  local_ver="${local_ver#go}"
  local_ver="$(numeric_prefix "$local_ver")"
  [ -n "$local_ver" ] || return 0
  if version_lt "$local_ver" "$required"; then
    info "local Go is $local_ver; cyoda-go's go.mod asks for Go $required. Go will try to download and use toolchain go$required automatically unless GOTOOLCHAIN=local is set or the network is unavailable."
  fi
}

build_checkout() { # <checkout dir>
  require_go
  local required
  required="$(required_go_version "$1")"
  warn_if_go_too_old "$1"
  local sha date
  sha="$(git -C "$1" rev-parse HEAD)"
  date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  ( cd "$1" && CGO_ENABLED=0 go build \
      -ldflags "-X main.version=$VERSION -X main.commit=$sha -X main.buildDate=$date" \
      -o "$DEST/cyoda" ./cmd/cyoda ) \
    || fail "go build failed in $1 (it needs Go >= ${required:-1.26.7} and, to fetch modules, network access), $OVERRIDE_HINT"
}

case "$MODE" in
  release)
    os="$(uname -s | tr '[:upper:]' '[:lower:]')"
    arch="$(uname -m)"
    case "$arch" in x86_64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; esac
    asset="cyoda_${VERSION}_${os}_${arch}.tar.gz"
    base="https://github.com/Cyoda/cyoda-go/releases/download/v${VERSION}"
    download_hint="a released pin is downloaded, which needs network access to github.com and a published release v$VERSION with an asset for $os/$arch; $OVERRIDE_HINT"
    # Fail closed: the committed checksums are what make the download trustworthy.
    [ -f "$PINNED_SUMS" ] || fail "the pin $VERSION is a release, but src/main/resources/cyoda/CYODA_SHA256SUMS is missing; re-run scripts/sync-cyoda-contract.sh for $VERSION to record its checksums, $OVERRIDE_HINT"
    expected="$(awk -v a="$asset" '$2 == a || $2 == "*" a { print $1; exit }' "$PINNED_SUMS")"
    [ -n "$expected" ] || fail "src/main/resources/cyoda/CYODA_SHA256SUMS has no checksum for $asset, $OVERRIDE_HINT"
    if command -v sha256sum >/dev/null; then
      checksum_tool=(sha256sum -c -)
      digest_tool=(sha256sum)
    elif command -v shasum >/dev/null; then
      checksum_tool=(shasum -a 256 -c -)
      digest_tool=(shasum -a 256)
    else
      fail "need sha256sum or shasum"
    fi
    if [ -n "$ARCHIVE" ]; then
      [ -f "$ARCHIVE" ] || fail "--archive $ARCHIVE does not exist"
      cp "$ARCHIVE" "$WORK/$asset"
    else
      curl -fsSL "$base/$asset" -o "$WORK/$asset" || fail "cannot download $base/$asset: $download_hint"
      curl -fsSL "$base/SHA256SUMS" -o "$WORK/SHA256SUMS" || fail "cannot download $base/SHA256SUMS: $download_hint"
      ( cd "$WORK" && grep " $asset\$" SHA256SUMS | "${checksum_tool[@]}" >/dev/null ) || fail "checksum mismatch for $asset against the release's SHA256SUMS"
    fi
    actual="$("${digest_tool[@]}" "$WORK/$asset" | awk '{ print $1 }')"
    [ "$actual" = "$expected" ] || fail "checksum mismatch for $asset: it is $actual, but CYODA_SHA256SUMS pins $expected"
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
