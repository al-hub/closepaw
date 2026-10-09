#!/usr/bin/env bash
# Publish an updater-compatible signed APK only after Security CI succeeds.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${KEYSTORE_PATH:?Set KEYSTORE_PATH to existing signing keystore}"
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
: "${KEY_ALIAS:?Set KEY_ALIAS}"
: "${KEY_PASSWORD:?Set KEY_PASSWORD}"
command -v gh >/dev/null || { echo "gh CLI required" >&2; exit 1; }
command -v apksigner >/dev/null || { echo "Android apksigner required on PATH" >&2; exit 1; }
[[ -f "$KEYSTORE_PATH" ]] || { echo "Keystore file missing" >&2; exit 1; }
version=$(sed -n 's/^VERSION_NAME=//p' gradle.properties)
code=$(sed -n 's/^VERSION_CODE=//p' gradle.properties)
[[ "$version" == "0.1.34" && "$code" == "35" ]] || { echo "Unexpected release version" >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree must be clean" >&2; exit 1; }
tag="v$version"
if gh release view "$tag" >/dev/null 2>&1; then echo "Release already exists: $tag" >&2; exit 1; fi
./gradlew :app:assembleRelease --stacktrace
apk="app/build/outputs/apk/release/app-release.apk"
[[ -s "$apk" ]] || { echo "Signed APK missing: $apk" >&2; exit 1; }
apksigner verify --verbose "$apk"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
cp "$apk" "$out/closepaw-aa-bridge.apk"
(cd "$out" && sha256sum closepaw-aa-bridge.apk > closepaw-aa-bridge.apk.sha256)
echo "Inspect SHA-256, signer certificate and release tag before publishing."
apksigner verify --print-certs "$out/closepaw-aa-bridge.apk"
cat "$out/closepaw-aa-bridge.apk.sha256"
if [[ "${CONFIRM_PUBLISH:-}" != "YES" ]]; then
  echo "Dry-run only. Re-run with CONFIRM_PUBLISH=YES once signing identity matches v0.1.33." >&2
  exit 0
fi
gh release create "$tag" "$out/closepaw-aa-bridge.apk" "$out/closepaw-aa-bridge.apk.sha256" \
  --title "ClosePaw $tag" --notes "Diagnostic MCP and browser read observability update."
echo "Published updater-compatible $tag. Verify GitHub latest release API before device update."
