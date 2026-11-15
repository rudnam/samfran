#!/usr/bin/env bash
# Builds the Android app for release, as RudWallet's does: the web app
# (bundled into the app), then a signed app bundle for the Play Store and an
# APK to install directly.
#
#   ./android/build.sh                -> android/samfran.aab and android/samfran.apk
#   ./android/build.sh install        also installs the APK on the phone plugged in (adb)
#   ./android/build.sh sign app.apk   signs an unsigned APK, e.g. CI's android-apk artifact,
#                                     for a machine without the memory to run Gradle
#
# Needs a JDK 21 and the Android SDK (JAVA_HOME and ANDROID_HOME, or the paths
# in ~/.bubblewrap/config.json), Node, and keystore/samfran.jks with its
# passwords in keystore/keystore.properties, next to this script. The version
# comes from version.sh, or SAMFRAN_VERSION_CODE and SAMFRAN_VERSION_NAME if
# they're set. CI's Android release workflow (.github/workflows/android-release.yml)
# runs this too.
set -euo pipefail
cd "$(dirname "$0")"

if [[ -f ~/.bubblewrap/config.json ]]; then
  : "${JAVA_HOME:=$(sed -n 's/.*"jdkPath": *"\([^"]*\)".*/\1/p' ~/.bubblewrap/config.json)}"
  : "${ANDROID_HOME:=$(sed -n 's/.*"androidSdkPath": *"\([^"]*\)".*/\1/p' ~/.bubblewrap/config.json)}"
fi
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}" "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
export JAVA_HOME ANDROID_HOME PATH="$JAVA_HOME/bin:$PATH"

[[ -f keystore/samfran.jks && -f keystore/keystore.properties ]] || { echo "keystore/samfran.jks and keystore/keystore.properties must be in android/" >&2; exit 1; }
SAMFRAN_KEYSTORE_PASSWORD=$(sed -n 's/^storePassword=//p' keystore/keystore.properties)
SAMFRAN_KEY_PASSWORD=$(sed -n 's/^keyPassword=//p' keystore/keystore.properties)
export SAMFRAN_KEYSTORE_PASSWORD SAMFRAN_KEY_PASSWORD
tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)

fingerprint() {
  keytool -list -v -keystore keystore/samfran.jks -storepass "$SAMFRAN_KEYSTORE_PASSWORD" | sed -n 's/^.*SHA256: //p'
}

if [[ ${1:-} == sign ]]; then
  [[ -f ${2:-} ]] || { echo "usage: ./android/build.sh sign <unsigned.apk>" >&2; exit 1; }
  "$tools/zipalign" -f 4 "$2" aligned.apk
  "$tools/apksigner" sign --ks keystore/samfran.jks --ks-key-alias samfran \
    --ks-pass env:SAMFRAN_KEYSTORE_PASSWORD --key-pass env:SAMFRAN_KEY_PASSWORD --out samfran.apk aligned.apk
  rm -f aligned.apk samfran.apk.idsig
  echo "Signed android/samfran.apk (key SHA-256: $(fingerprint))"
  exit 0
fi

# The version: one above what the site offers (version.sh), unless given.
: "${SAMFRAN_VERSION_CODE:=$(./version.sh)}"
: "${SAMFRAN_VERSION_NAME:=$(sed -n "s/.*SAMFRAN_VERSION_NAME') ?: \"\(.*\)\".*/\1/p" app/build.gradle).$SAMFRAN_VERSION_CODE}"
export SAMFRAN_VERSION_CODE SAMFRAN_VERSION_NAME

# The web app, built for the app (web/.env.android) and copied in.
(cd ../web && { [[ -d node_modules ]] || npm ci --no-audit --no-fund; } && npm run android)

./gradlew --quiet testReleaseUnitTest bundleRelease assembleRelease
cp app/build/outputs/bundle/release/app-release.aab samfran.aab
cp app/build/outputs/apk/release/app-release.apk samfran.apk
# The local stack offers it too (ANDROID_APK, docker-compose.yml).
cp samfran.apk ../downloads/samfran.apk
echo "Built android/samfran.aab (Play Store) and android/samfran.apk: version $SAMFRAN_VERSION_NAME (versionCode $SAMFRAN_VERSION_CODE), key SHA-256: $(fingerprint)"

if [[ ${1:-} == install ]]; then
  "$ANDROID_HOME/platform-tools/adb" install -r samfran.apk
fi
