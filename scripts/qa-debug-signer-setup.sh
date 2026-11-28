#!/usr/bin/env bash
# One-time trusted-owner WSL/Termux setup. Never run in GitHub Actions, never
# commit a private signer, and never reuse the production or Android default key.
set -euo pipefail
set +x
umask 077
repo='al-hub/closepaw'
alias_name='closepaw-qa-debug'
store_dir="${HOME}/.local/share/closepaw/qa-debug-signing"
keystore="${store_dir}/closepaw-qa-debug.p12"
for tool in gh keytool base64 sed tr; do
  command -v "$tool" >/dev/null || { printf 'Missing local tool: %s\n' "$tool" >&2; exit 1; }
done
gh auth status -h github.com >/dev/null || {
  echo 'Use your own authorized gh auth login before configuring repository secrets.' >&2
  exit 1
}
if [[ ! -t 0 ]]; then
  echo 'Run from an interactive trusted terminal; never paste passwords into chat.' >&2
  exit 1
fi
mkdir -p "$store_dir"
chmod 700 "$store_dir"
printf 'ClosePaw QA signer: %s\n' "$keystore"
if [[ -f "$keystore" ]]; then
  echo 'Existing dedicated QA signer will be reused; no key rotation.'
  read -r -s -p 'Existing QA keystore password: ' QA_SIGN_PASS
  echo
else
  echo 'Creating new dedicated 10-year QA-only signing identity.'
  read -r -s -p 'Choose a new QA keystore password (min 16 chars): ' QA_SIGN_PASS
  echo
  if (( ${#QA_SIGN_PASS} < 16 )); then echo 'Password too short' >&2; exit 1; fi
  read -r -s -p 'Repeat QA password: ' confirmation
  echo
  if [[ "$QA_SIGN_PASS" != "$confirmation" ]]; then
    echo 'Password confirmation mismatch' >&2
    exit 1
  fi
  unset confirmation
  export QA_SIGN_PASS
  keytool -genkeypair -noprompt \
    -keystore "$keystore" -storetype PKCS12 \
    -alias "$alias_name" -keyalg RSA -keysize 3072 -validity 3650 \
    -dname 'CN=ClosePaw QA Debug,OU=Android QA,O=ClosePaw,C=KR' \
    -storepass:env QA_SIGN_PASS -keypass:env QA_SIGN_PASS
  chmod 600 "$keystore"
fi
export QA_SIGN_PASS
fingerprint="$(LC_ALL=C keytool -list -v -keystore "$keystore" \
    -alias "$alias_name" -storepass:env QA_SIGN_PASS |
    sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*([0-9A-Fa-f:]+).*$/\1/p' |
    tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$fingerprint" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'Signer fingerprint could not be verified; nothing uploaded.' >&2
  exit 1
fi
printf 'Verified public QA certificate SHA-256: %s\n' "$fingerprint"
echo 'Uploading protected secrets through authenticated GitHub CLI; values are not printed.'
base64 -w0 "$keystore" | gh secret set CLOSEPAW_QA_DEBUG_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$QA_SIGN_PASS" | gh secret set CLOSEPAW_QA_DEBUG_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$alias_name" | gh secret set CLOSEPAW_QA_DEBUG_KEY_ALIAS --repo "$repo"
printf '%s' "$QA_SIGN_PASS" | gh secret set CLOSEPAW_QA_DEBUG_KEY_PASSWORD --repo "$repo"
# Some owner WSL hosts run gh releases without the "variable" subcommand.
# The standard REST repository Actions variable API is supported there.
if gh api "repos/${repo}/actions/variables/CLOSEPAW_QA_DEBUG_CERT_SHA256" >/dev/null 2>&1; then
  gh api -X PATCH "repos/${repo}/actions/variables/CLOSEPAW_QA_DEBUG_CERT_SHA256" \
    -f name=CLOSEPAW_QA_DEBUG_CERT_SHA256 -f value="$fingerprint" >/dev/null
else
  gh api -X POST "repos/${repo}/actions/variables" \
    -f name=CLOSEPAW_QA_DEBUG_CERT_SHA256 -f value="$fingerprint" >/dev/null
fi
unset QA_SIGN_PASS
printf '\nFixed signer configured. Back up the local private keystore OFF the repo securely.\n'
printf 'Compare installed ai.closepaw.capturecandidate signer before any update.\n'
printf 'The user executing this command authorizes candidate-only CI signing.\n'
printf 'Triggering candidate workflow for reviewed QA branch; no handset install.\n'
gh workflow run premium-ux-ui.yml --repo "$repo" \
  --ref 'test/147-orange-debug-candidate-20261122' \
  --field build_capture_candidate=true
printf 'Workflow: https://github.com/al-hub/closepaw/actions/workflows/premium-ux-ui.yml\n'
printf 'Candidate artifact must pass exact-source Security/UI, identity and signer checks before install.\n'
