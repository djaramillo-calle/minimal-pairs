#!/usr/bin/env bash
# Create the release signing keystore for Minimal Pairs and print the GitHub
# Secrets that the release workflow (.github/workflows/release.yml) expects.
#
# Usage:
#   scripts/make-keystore.sh [output.jks]          default: release.jks in the current directory
#
# Environment (all optional; prompted for when unset, never echoed):
#   KEYSTORE_PASSWORD   keystore password (at least 6 characters, keytool's minimum).
#                       A PKCS12 keystore has a single password: the key uses the
#                       same one, so ANDROID_KEY_PASSWORD must equal
#                       ANDROID_KEYSTORE_PASSWORD (keytool ignores -keypass here).
#   KEY_ALIAS           key alias, default "minimalpairs"
#   KEY_DNAME           X.500 name, default "CN=Minimal Pairs, O=djaramillo-calle"
#
# Keep the .jks file and the passwords out of git (*.jks is in .gitignore).
# Losing the keystore means future builds cannot update the installed app.
set -euo pipefail

OUT="${1:-release.jks}"
KEY_ALIAS="${KEY_ALIAS:-minimalpairs}"
KEY_DNAME="${KEY_DNAME:-CN=Minimal Pairs, O=djaramillo-calle}"

if ! command -v keytool >/dev/null 2>&1; then
    echo "error: keytool not found; install a JDK (17 or newer)" >&2
    exit 1
fi
if [ -e "$OUT" ]; then
    echo "error: $OUT already exists; refusing to overwrite a keystore" >&2
    exit 1
fi

if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    read -r -s -p "Keystore password (min 6 chars): " KEYSTORE_PASSWORD; echo
    read -r -s -p "Repeat keystore password: " again; echo
    [ "$KEYSTORE_PASSWORD" = "$again" ] || { echo "error: passwords do not match" >&2; exit 1; }
fi
if [ "${#KEYSTORE_PASSWORD}" -lt 6 ]; then
    echo "error: keystore password must be at least 6 characters" >&2
    exit 1
fi
# PKCS12 keystores cannot hold a key password different from the store
# password (keytool silently ignores -keypass), so a separate KEY_PASSWORD would
# only produce a secret the CI build cannot open the key with.
if [ -n "${KEY_PASSWORD:-}" ] && [ "$KEY_PASSWORD" != "$KEYSTORE_PASSWORD" ]; then
    echo "error: KEY_PASSWORD is set and differs from KEYSTORE_PASSWORD; a PKCS12 keystore has one password" >&2
    exit 1
fi

# The password goes to keytool through an environment variable (":env" prefix),
# so it never appears on the command line or in the process list.
export KEYSTORE_PASSWORD
keytool -genkeypair \
    -keystore "$OUT" -storetype PKCS12 \
    -alias "$KEY_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "$KEY_DNAME" \
    -storepass:env KEYSTORE_PASSWORD

chmod 600 "$OUT"

case "$(uname -s)" in
    Darwin) B64="base64 -i \"$OUT\"" ;;
    *)      B64="base64 -w0 \"$OUT\"" ;;
esac

cat <<MSG

Created $OUT (RSA 2048, alias "$KEY_ALIAS", valid 10000 days).

Add these four repository secrets on GitHub
(Settings -> Secrets and variables -> Actions -> New repository secret):

  ANDROID_KEYSTORE_B64        the file, base64 on one line:  $B64
  ANDROID_KEYSTORE_PASSWORD   the keystore password you just entered
  ANDROID_KEY_ALIAS           $KEY_ALIAS
  ANDROID_KEY_PASSWORD        the same value as ANDROID_KEYSTORE_PASSWORD (a PKCS12 keystore has one password)

Then every push to main builds a release-signed APK.

Keep $OUT and the passwords out of git and back them up somewhere safe:
without them a future build cannot update the app already installed on the phone.
MSG
