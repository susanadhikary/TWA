#!/usr/bin/env bash
# Sign an unsigned release APK with the TapTill KDS key.
#
# Usage:
#   scripts/sign-apk.sh <unsigned.apk> <keystore.jks> [output.apk]
#
# Needs the Android SDK build-tools (zipalign + apksigner). Set ANDROID_HOME
# (or ANDROID_SDK_ROOT) or put build-tools on your PATH. The keystore password
# is asked for interactively (or taken from KDS_KEYSTORE_PASSWORD).
set -euo pipefail

if [ $# -lt 2 ]; then
  sed -n '2,9p' "$0"
  exit 1
fi

IN="$1"
KEYSTORE="$2"
OUT="${3:-${IN%-unsigned.apk}.apk}"
[ "$OUT" = "$IN" ] && OUT="${IN%.apk}-signed.apk"
ALIAS="${KDS_KEY_ALIAS:-taptill-kds}"

find_tool() {
  if command -v "$1" >/dev/null 2>&1; then command -v "$1"; return; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$sdk" ] && [ -d "$sdk/build-tools" ]; then
    local latest
    latest="$(ls "$sdk/build-tools" | sort -V | tail -1)"
    if [ -x "$sdk/build-tools/$latest/$1" ]; then echo "$sdk/build-tools/$latest/$1"; return; fi
  fi
  echo "Cannot find '$1'. Install Android SDK build-tools and set ANDROID_HOME." >&2
  exit 1
}

ZIPALIGN="$(find_tool zipalign)"
APKSIGNER="$(find_tool apksigner)"
TMP="$(mktemp "${TMPDIR:-/tmp}/kds-aligned-XXXXXX.apk")"
trap 'rm -f "$TMP"' EXIT

"$ZIPALIGN" -f -p 4 "$IN" "$TMP"

PASS_ARGS=()
if [ -n "${KDS_KEYSTORE_PASSWORD:-}" ]; then
  PASS_ARGS=(--ks-pass env:KDS_KEYSTORE_PASSWORD --key-pass env:KDS_KEYSTORE_PASSWORD)
fi

"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" ${PASS_ARGS[@]+"${PASS_ARGS[@]}"} --out "$OUT" "$TMP"
"$APKSIGNER" verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)"

echo "Signed: $OUT"
sha256sum "$OUT" 2>/dev/null || shasum -a 256 "$OUT"
