#!/usr/bin/env bash
set -euo pipefail

SRC_DIR="${1:?usage: spike-build-openai-tunnel-android.sh <tunnel-client-src> <out-dir>}"
OUT_DIR="${2:?usage: spike-build-openai-tunnel-android.sh <tunnel-client-src> <out-dir>}"

EXPECTED_COMMIT="a390c168ff1b2d14e73a95991c186c6aba3ff5a0"
EXPECTED_MODULE="github.com/openai/tunnel-client"

test -f "${SRC_DIR}/go.mod"
actual_module="$(cd "${SRC_DIR}" && go list -m -f '{{.Path}}')"
test "${actual_module}" = "${EXPECTED_MODULE}" || {
  echo "Unexpected module: ${actual_module}" >&2
  exit 1
}

actual_commit="$(git -C "${SRC_DIR}" rev-parse HEAD)"
test "${actual_commit}" = "${EXPECTED_COMMIT}" || {
  echo "Unexpected tunnel-client commit: ${actual_commit}" >&2
  exit 1
}

mkdir -p "${OUT_DIR}"
pushd "${SRC_DIR}" >/dev/null
GOOS=android GOARCH=arm64 CGO_ENABLED=0 make tunnel-client-runtime
BIN="bin/android_arm64/tunnel-client-runtime"
test -f "${BIN}"
popd >/dev/null

cp "${SRC_DIR}/${BIN}" "${OUT_DIR}/tunnel-client-runtime"
chmod +x "${OUT_DIR}/tunnel-client-runtime"

file "${OUT_DIR}/tunnel-client-runtime" | tee "${OUT_DIR}/file.txt"
go version -m "${OUT_DIR}/tunnel-client-runtime" | tee "${OUT_DIR}/go-version-m.txt"
readelf -h "${OUT_DIR}/tunnel-client-runtime" | tee "${OUT_DIR}/elf-header.txt"
sha256sum "${OUT_DIR}/tunnel-client-runtime" | tee "${OUT_DIR}/SHA256SUMS.txt"

cat > "${OUT_DIR}/BUILD-METADATA.txt" <<EOF
source=https://github.com/openai/tunnel-client
release=v0.0.15
commit=${EXPECTED_COMMIT}
goos=android
goarch=arm64
cgo_enabled=0
target=tunnel-client-runtime
EOF

echo "Android tunnel-client runtime build spike: PASS"
