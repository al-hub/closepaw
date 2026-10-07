#!/usr/bin/env bash
set -euo pipefail

CLOUDFLARED_REF="${CLOUDFLARED_REF:-2026.10.0}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
ANDROID_HOME="${ANDROID_HOME:?ANDROID_HOME is required}"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/$NDK_VERSION}}"

if [[ ! -d "$NDK_ROOT" ]]; then
  echo "Android NDK not found: $NDK_ROOT" >&2
  exit 2
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) PREBUILT="linux-x86_64" ;;
  Darwin-arm64|Darwin-aarch64) PREBUILT="darwin-x86_64" ;;
  Darwin-x86_64) PREBUILT="darwin-x86_64" ;;
  *) echo "Unsupported build host" >&2; exit 2 ;;
esac

CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/$PREBUILT/bin/aarch64-linux-android31-clang"
if [[ ! -x "$CLANG" ]]; then
  echo "Android clang not found: $CLANG" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone --quiet --depth 1 --branch "$CLOUDFLARED_REF" https://github.com/cloudflare/cloudflared.git "$WORK/cloudflared"
mkdir -p "$(dirname "$OUT")"

(
  cd "$WORK/cloudflared"
  CGO_ENABLED=1 \
  GOOS=android \
  GOARCH=arm64 \
  CC="$CLANG" \
  go build -trimpath \
    -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
    -o "$OUT" \
    ./cmd/cloudflared
)

test -s "$OUT"
echo "Built $OUT from cloudflared $CLOUDFLARED_REF"
