#!/usr/bin/env bash
# Signed, directly installable APK; run only on a trusted signing host.
set -euo pipefail
fail() { echo "release-apk: $*" >&2; exit 1; }
for name in KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  [[ -n "${!name-}" ]] || fail "$name is required"
done
[[ -r "$KEYSTORE_PATH" ]] || fail "keystore file not readable"
cd "$(dirname "$0")/.."
./gradlew :app:assembleRelease --stacktrace
apk="app/build/outputs/apk/release/app-release.apk"
[[ -s "$apk" ]] || fail "signed APK is missing"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$sdk_root/build-tools" ]] || fail "Android build-tools not found"
tools="$(find "$sdk_root/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -n 1)"
[[ -x "$tools/apksigner" && -x "$tools/zipalign" ]] || fail "apksigner/zipalign unavailable"
"$tools/zipalign" -c -p 4 "$apk"
"$tools/apksigner" verify --verbose --print-certs "$apk"
sha256sum "$apk"
echo "release-apk: VERIFIED signed APK at $apk"
