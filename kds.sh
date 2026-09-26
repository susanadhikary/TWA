#!/usr/bin/env bash
# =============================================================================
#  TapTill KDS - configure and build in one step (macOS / Linux / Git Bash)
#
#  Interactive (asks for everything, shows current values as defaults):
#      ./kds.sh
#
#  Non-interactive examples:
#      ./kds.sh --url https://pos.example.com/kds
#      ./kds.sh --url https://pos.example.com/kds --scope https://pos.example.com/ --name "TapTill KDS"
#      ./kds.sh --url https://pos.example.com/kds --keystore ~/keys/taptill-kds-release.jks
#      ./kds.sh --show
#
#  Options:
#      --url URL            Page the app opens (https:// only)
#      --scope URL          App scope; pages under it get notifications, camera, ...
#                           Default: the whole site of --url (https://host/)
#      --name NAME          App name on the TV home screen
#      --version-name X     Version shown to people (default: bump last number, 1.1.0 -> 1.1.1)
#      --no-bump            Keep VERSION_CODE / VERSION_NAME as they are
#      --build TYPE         release (default), debug, or none (only update kds.properties)
#      --keystore FILE      Sign the release with this keystore (or set KDS_KEYSTORE_PATH)
#      --save-release       Also copy the signed APK into releases/ and update SHA256SUMS
#      --show               Print the current configuration and exit
#      -y, --yes            Don't ask for confirmation
#      -h, --help           Show this help
#
#  Full guide: docs/CHANGE-URL-AND-REBUILD.md
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$ROOT/kds.properties"
cd "$ROOT"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
ok()   { printf '\033[32m✔ %s\033[0m\n' "$*"; }
warn() { printf '\033[33m! %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31m✘ %s\033[0m\n' "$*" >&2; exit 1; }

usage() { sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0; }

# ---------------------------------------------------------------- kds.properties

get_prop() {
  [ -f "$PROPS" ] || die "kds.properties not found in $ROOT"
  awk -F= -v k="$1" '$1==k { sub(/^[^=]*=/, ""); print; exit }' "$PROPS" | sed 's/[[:space:]]*$//'
}

set_prop() {
  local key="$1" value="$2" tmp
  tmp="$(mktemp)"
  KEY="$key" VALUE="$value" awk '
    BEGIN { k = ENVIRON["KEY"]; v = ENVIRON["VALUE"]; done = 0 }
    index($0, k "=") == 1 { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' "$PROPS" > "$tmp"
  mv "$tmp" "$PROPS"
}

show_config() {
  bold "Current configuration (kds.properties)"
  info "URL          : $(get_prop KDS_URL)"
  info "Scope        : $(get_prop KDS_SCOPE)"
  info "App name     : $(get_prop APP_NAME)"
  info "Version code : $(get_prop VERSION_CODE)"
  info "Version name : $(get_prop VERSION_NAME)"
}

# ---------------------------------------------------------------- URL checks

# Prints "origin<TAB>path" for an https URL, or fails.
parse_https() {
  local url="$1"
  [[ "$url" =~ ^[Hh][Tt][Tt][Pp][Ss]://([^/?#]+)([^?#]*) ]] || return 1
  local host="${BASH_REMATCH[1]}" path="${BASH_REMATCH[2]}"
  [ -n "$host" ] || return 1
  [ -n "$path" ] || path="/"
  printf 'https://%s\t%s\n' "$(printf '%s' "$host" | tr '[:upper:]' '[:lower:]')" "$path"
}

# check_url_scope URL SCOPE: returns 0 if valid, else prints the reason and returns 1.
check_url_scope() {
  local url="$1" scope="$2" u s
  u="$(parse_https "$url")" || { echo "URL must be a full https:// address (got: $url)"; return 1; }
  s="$(parse_https "$scope")" || { echo "Scope must be a full https:// address (got: $scope)"; return 1; }
  local uo="${u%%$'\t'*}" up="${u#*$'\t'}" so="${s%%$'\t'*}" sp="${s#*$'\t'}"
  [ "$uo" = "$so" ] || { echo "URL ($url) and scope ($scope) must be on the same https host"; return 1; }
  [[ "$up" == "$sp"* ]] || { echo "URL path ($up) must start with the scope path ($sp)"; return 1; }
}

validate() {
  local reason
  reason="$(check_url_scope "$1" "$2")" || die "$reason"
}

default_scope() {
  local p
  p="$(parse_https "$1")" || return 1
  printf '%s/\n' "${p%%$'\t'*}"
}

bump_version_name() {
  local v="$1"
  if [[ "$v" =~ ^(.*[^0-9])?([0-9]+)$ ]]; then
    printf '%s%d\n' "${BASH_REMATCH[1]}" "$((BASH_REMATCH[2] + 1))"
  else
    printf '%s\n' "$v"
  fi
}

ask() { # ask "Question" "default" -> echoes answer
  local reply
  read -r -p "$1 [$2]: " reply </dev/tty || true
  printf '%s\n' "${reply:-$2}"
}

# ---------------------------------------------------------------- build helpers

find_sdk() {
  if [ -f "$ROOT/local.properties" ] && grep -q '^sdk.dir=' "$ROOT/local.properties"; then return 0; fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ]; then
    for guess in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$HOME/Android/sdk"; do
      [ -d "$guess" ] && sdk="$guess" && break
    done
  fi
  [ -n "$sdk" ] && [ -d "$sdk" ] || return 1
  export ANDROID_HOME="$sdk"
}

# ---------------------------------------------------------------- arguments

URL="" SCOPE="" NAME="" VNAME="" BUMP=1 BUILD="release" KEYSTORE="${KDS_KEYSTORE_PATH:-}"
SAVE=0 YES=0 INTERACTIVE=1

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="${2:?--url needs a value}"; INTERACTIVE=0; shift 2 ;;
    --scope) SCOPE="${2:?--scope needs a value}"; INTERACTIVE=0; shift 2 ;;
    --name) NAME="${2:?--name needs a value}"; INTERACTIVE=0; shift 2 ;;
    --version-name) VNAME="${2:?--version-name needs a value}"; shift 2 ;;
    --no-bump) BUMP=0; shift ;;
    --build) BUILD="${2:?--build needs release, debug or none}"; shift 2 ;;
    --keystore) KEYSTORE="${2:?--keystore needs a file}"; shift 2 ;;
    --save-release) SAVE=1; shift ;;
    --show) show_config; exit 0 ;;
    -y|--yes) YES=1; shift ;;
    -h|--help) usage ;;
    *) die "Unknown option: $1 (see ./kds.sh --help)" ;;
  esac
done

case "$BUILD" in release|debug|none) ;; *) die "--build must be release, debug or none" ;; esac

CUR_URL="$(get_prop KDS_URL)"
CUR_SCOPE="$(get_prop KDS_SCOPE)"
CUR_NAME="$(get_prop APP_NAME)"
CUR_CODE="$(get_prop VERSION_CODE)"
CUR_VNAME="$(get_prop VERSION_NAME)"
[[ "$CUR_CODE" =~ ^[0-9]+$ ]] || die "VERSION_CODE in kds.properties is not a number: $CUR_CODE"

echo
bold "TapTill KDS – configure & build"
show_config
echo

# ---------------------------------------------------------------- collect values

if [ "$INTERACTIVE" = 1 ]; then
  URL="$(ask "URL the app should open" "$CUR_URL")"
  # Keep the current scope if the URL stays on it; otherwise suggest the new site root.
  if [ -n "$CUR_SCOPE" ] && check_url_scope "$URL" "$CUR_SCOPE" >/dev/null; then
    suggested="$CUR_SCOPE"
  else
    suggested="$(default_scope "$URL" || true)"
  fi
  SCOPE="$(ask "Scope (pages that get notifications/camera/location)" "$suggested")"
  NAME="$(ask "App name" "$CUR_NAME")"
else
  URL="${URL:-$CUR_URL}"
  if [ -z "$SCOPE" ]; then
    if [ -n "$CUR_SCOPE" ] && check_url_scope "$URL" "$CUR_SCOPE" >/dev/null; then
      SCOPE="$CUR_SCOPE"
    else
      SCOPE="$(default_scope "$URL" || true)"
    fi
  fi
  NAME="${NAME:-$CUR_NAME}"
fi

validate "$URL" "$SCOPE"
[ -n "$NAME" ] || die "App name cannot be empty"

if [ "$BUMP" = 1 ]; then
  NEW_CODE=$((CUR_CODE + 1))
  NEW_VNAME="${VNAME:-$(bump_version_name "$CUR_VNAME")}"
  if [ "$INTERACTIVE" = 1 ] && [ -z "$VNAME" ]; then
    NEW_VNAME="$(ask "Version name" "$NEW_VNAME")"
  fi
else
  NEW_CODE="$CUR_CODE"
  NEW_VNAME="${VNAME:-$CUR_VNAME}"
fi

echo
bold "New configuration"
info "URL          : $URL"
info "Scope        : $SCOPE"
info "App name     : $NAME"
info "Version      : $NEW_VNAME (code $NEW_CODE)"
info "Build        : $BUILD"
echo

if [ "$YES" != 1 ] && [ -t 0 ]; then
  confirm="$(ask "Save and continue? (y/n)" "y")"
  [[ "$confirm" =~ ^[Yy] ]] || die "Cancelled - nothing changed."
fi

set_prop KDS_URL "$URL"
set_prop KDS_SCOPE "$SCOPE"
set_prop APP_NAME "$NAME"
set_prop VERSION_CODE "$NEW_CODE"
set_prop VERSION_NAME "$NEW_VNAME"
ok "kds.properties updated"

if [ "$BUILD" = none ]; then
  echo
  info "Next: commit and push to let GitHub Actions build the APK:"
  info "  git commit -am \"KDS: $URL, version $NEW_VNAME\" && git push"
  exit 0
fi

# ---------------------------------------------------------------- build

command -v java >/dev/null 2>&1 || die "Java (JDK 17+) not found. Install it, or use --build none and let GitHub Actions build."
find_sdk || die "Android SDK not found. Install Android Studio (or the SDK), then set ANDROID_HOME
   or create local.properties with: sdk.dir=/path/to/Android/sdk
   Or run with --build none, push, and download the APK from GitHub Actions."
chmod +x "$ROOT/gradlew"

mkdir -p "$ROOT/dist"
SAFE_NAME="TapTill-KDS-$NEW_VNAME"

if [ "$BUILD" = debug ]; then
  bold "Building debug APK…"
  ./gradlew --quiet assembleDebug
  OUT="$ROOT/dist/$SAFE_NAME-debug.apk"
  cp app/build/outputs/apk/debug/TapTill-KDS-debug.apk "$OUT"
  ok "Debug APK: $OUT"
  warn "Debug APKs are for testing only and cannot update a release install."
  exit 0
fi

if [ -n "$KEYSTORE" ]; then
  [ -f "$KEYSTORE" ] || die "Keystore not found: $KEYSTORE"
  export KDS_KEYSTORE_PATH="$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")"
  export KDS_KEY_ALIAS="${KDS_KEY_ALIAS:-taptill-kds}"
  if [ -z "${KDS_KEYSTORE_PASSWORD:-}" ]; then
    read -r -s -p "Keystore password: " KDS_KEYSTORE_PASSWORD </dev/tty; echo
    export KDS_KEYSTORE_PASSWORD
  fi
  export KDS_KEY_PASSWORD="${KDS_KEY_PASSWORD:-$KDS_KEYSTORE_PASSWORD}"
  bold "Building signed release APK…"
  ./gradlew --quiet assembleRelease
  OUT="$ROOT/dist/$SAFE_NAME-release.apk"
  cp app/build/outputs/apk/release/TapTill-KDS-release.apk "$OUT"
  ok "Signed release APK: $OUT"
else
  bold "Building release APK (unsigned – no keystore given)…"
  ./gradlew --quiet assembleRelease
  OUT="$ROOT/dist/$SAFE_NAME-release-unsigned.apk"
  cp app/build/outputs/apk/release/TapTill-KDS-release-unsigned.apk "$OUT"
  ok "Unsigned release APK: $OUT"
  warn "Sign it before installing:  scripts/sign-apk.sh \"$OUT\" /path/to/taptill-kds-release.jks"
  warn "(or re-run with --keystore /path/to/taptill-kds-release.jks)"
fi

if command -v sha256sum >/dev/null 2>&1; then SUM="$(sha256sum "$OUT" | cut -d' ' -f1)"
else SUM="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"; fi
info "SHA-256: $SUM"

if [ "$SAVE" = 1 ]; then
  if [ -z "$KEYSTORE" ]; then
    warn "--save-release skipped: only signed releases are saved to releases/"
  else
    mkdir -p "$ROOT/releases"
    cp "$OUT" "$ROOT/releases/"
    (cd "$ROOT/releases" && { command -v sha256sum >/dev/null 2>&1 && sha256sum "$(basename "$OUT")" || shasum -a 256 "$(basename "$OUT")"; } >> SHA256SUMS)
    ok "Saved to releases/$(basename "$OUT")"
  fi
fi

echo
bold "Install / update on a TV:"
info "adb connect <tv-ip-address>"
info "adb install -r \"$OUT\""
echo
info "Don't forget to commit kds.properties:"
info "  git commit -am \"KDS: $URL, version $NEW_VNAME\" && git push"
