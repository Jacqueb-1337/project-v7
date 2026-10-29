#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"

echo "Preparing release v${VERSION}..."

# 1. Update the version in app/gradle.properties
sed -i "s/^version\s*=.*/version = ${VERSION}/" app/gradle.properties
echo "Updated app/gradle.properties to version ${VERSION}"

# 2. Build the release APK with the correct version baked in.
#    Stop any existing daemon so a fresh one picks up the updated gradle.properties.
chmod +x ./gradlew
./gradlew --stop || true
./gradlew assembleRelease --stacktrace

# 3. Find the built APK and rename it to the expected release name
RELEASE_DIR="app/build/outputs/apk/release"
APK_SRC=$(find "${RELEASE_DIR}" -name '*-release.apk' | head -1)

if [ -z "${APK_SRC}" ]; then
  echo "ERROR: No *-release.apk found in ${RELEASE_DIR}"
  ls -la "${RELEASE_DIR}" || true
  exit 1
fi

APK_DST="${RELEASE_DIR}/project-v7-patcher-${VERSION}.apk"
mv "${APK_SRC}" "${APK_DST}"
echo "Renamed APK to ${APK_DST}"

# 4. GPG-sign the APK
# The initial PV7 OpenPGP key was generated via a Windows CRLF input stream, so
# its password includes one literal carriage-return byte. Normalize a trailing
# CR if Actions preserved it and add exactly one CR before the line terminator.
# Scope this compatibility to PV7's current signing identity; future keys should
# be generated from an LF-only stream, not by piping passwords in PowerShell.
PV7_GPG_INPUT="${GPG_PASSPHRASE%
gpg --verify "${APK_DST}.asc" "${APK_DST}"
echo "Signed ${APK_DST}"

echo "Release v${VERSION} prepared successfully."
\r'}"
printf '%s\r\n' "${PV7_GPG_INPUT:?}" | gpg --batch --yes --pinentry-mode loopback --passphrase-fd 0 --local-user "${GPG_FINGERPRINT:?}" --armor --detach-sign "${APK_DST}"
gpg --verify "${APK_DST}.asc" "${APK_DST}"
echo "Signed ${APK_DST}"

echo "Release v${VERSION} prepared successfully."
