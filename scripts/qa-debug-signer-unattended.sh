#!/usr/bin/env bash
# QA-only unattended owner bootstrap, invoked inside trusted WSL via the
# pre-paired ClosePaw SSH route. No credentials in Git, stdout or arguments.
# Running again reuses exactly the same local signer; never rotates silently.
set -euo pipefail
set +x
umask 077

repo='al-hub/closepaw'
signer_alias='closepaw-qa-debug'
dir="${HOME}/.local/share/closepaw/qa-debug-signing"
keystore="${dir}/closepaw-qa-debug.p12"
password_file="${dir}/.keystore-password"
for binary in gh keytool openssl base64 sed tr; do
  command -v "$binary" >/dev/null || { echo "Missing required local binary: $binary" >&2; exit 1; }
done
gh auth status -h github.com >/dev/null 2>&1 || { echo 'GitHub CLI authentication required' >&2; exit 1; }
mkdir -p "$dir"; chmod 700 "$dir"

# Never overwrite an existing credential or signer unless its local material
# is available for exactly the same signing identity.
if [[ -e "$keystore" && ! -f "$password_file" || -e "$password_file" && ! -f "$keystore" ]]; then
  echo 'Partial local QA signer exists: refusing silent key rotation.' >&2
  exit 1
fi
if [[ ! -e "$keystore" ]]; then
  for name in CLOSEPAW_QA_DEBUG_KEYSTORE_BASE64 CLOSEPAW_QA_DEBUG_KEYSTORE_PASSWORD \
              CLOSEPAW_QA_DEBUG_KEY_ALIAS CLOSEPAW_QA_DEBUG_KEY_PASSWORD; do
    if gh api "repos/${repo}/actions/secrets/${name}" >/dev/null 2>&1; then
      echo 'Remote QA signing identity exists without local signer: refusing key replacement.' >&2
      exit 1
    fi
  done
  if gh api "repos/${repo}/actions/variables/CLOSEPAW_QA_DEBUG_CERT_SHA256" >/dev/null 2>&1; then
    echo 'Remote QA signing certificate pin exists without local signer: refusing key replacement.' >&2
    exit 1
  fi
  openssl rand -hex 32 > "$password_file"
  chmod 600 "$password_file"
  IFS= read -r QA_SIGN_PASS < "$password_file"
  export QA_SIGN_PASS
  keytool -genkeypair -noprompt -storetype PKCS12 \
    -keystore "$keystore" -alias "$signer_alias" -keyalg RSA -keysize 3072 \
    -validity 3650 -dname 'CN=ClosePaw QA Debug,OU=Android QA,O=ClosePaw,C=KR' \
    -storepass:env QA_SIGN_PASS -keypass:env QA_SIGN_PASS >/dev/null
  chmod 600 "$keystore"
  echo 'Dedicated long-lived QA signer created in private WSL storage.'
else
  IFS= read -r QA_SIGN_PASS < "$password_file"
  export QA_SIGN_PASS
  echo 'Reusing existing dedicated QA signer (no rotation).'
fi

fingerprint="$(LC_ALL=C keytool -list -v -keystore "$keystore" \
  -alias "$signer_alias" -storepass:env QA_SIGN_PASS \
  | sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*([0-9A-Fa-f:]+).*$/\1/p' \
  | tr -d ':' | tr '[:upper:]' '[:lower:]')"
if [[ ! "$fingerprint" =~ ^[0-9a-f]{64}$ ]]; then
  echo 'Invalid QA signer certificate fingerprint: no GitHub secret changes.' >&2
  exit 1
fi
# Public certificate pin only; never output the key/password.
base64 -w0 "$keystore" | gh secret set CLOSEPAW_QA_DEBUG_KEYSTORE_BASE64 --repo "$repo"
printf '%s' "$QA_SIGN_PASS" | gh secret set CLOSEPAW_QA_DEBUG_KEYSTORE_PASSWORD --repo "$repo"
printf '%s' "$signer_alias" | gh secret set CLOSEPAW_QA_DEBUG_KEY_ALIAS --repo "$repo"
printf '%s' "$QA_SIGN_PASS" | gh secret set CLOSEPAW_QA_DEBUG_KEY_PASSWORD --repo "$repo"
gh variable set CLOSEPAW_QA_DEBUG_CERT_SHA256 --body "$fingerprint" --repo "$repo"
unset QA_SIGN_PASS
echo 'Four QA-only Actions secrets and pinned public cert SHA-256 registered.'
echo 'Local keystore/password are private; make an offline protected backup.'
echo 'DO NOT uninstall or overwrite a different-signer installed Debug package.'
