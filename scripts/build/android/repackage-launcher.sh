#!/usr/bin/env bash
#
# GeneralsX @build Flux Build 05/11/2026 Package an APK for a launcher-only change (Java, resources,
# strings) around the engines the last build-dual-hz.sh produced -- about half a minute instead of
# two native passes.
#
# Safe only while the engines match the tree, so it refuses unless:
#   - build/engines.stamp exists (written by build-dual-hz.sh after a clean two-pass build),
#   - the engine sources' fingerprint (engine-fingerprint.sh) still equals the stamp's,
#   - the four engine files still have the stamp's hashes,
# and after packaging it checks that the APK carries exactly those four engines.
#
# Passes GX_UPDATE_CHANNEL, GX_ANDROID_VERSION_CODE and GX_ANDROID_VERSION_NAME through, as the full
# build does. Output: GeneralsXZH-android-local.apk in the repository root.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${REPO}"
STAMP="build/engines.stamp"
NDK_VERSION="27.2.12479018"

fail() { echo "repackage-launcher: $*" >&2; exit 1; }

[ -f "${STAMP}" ] || fail "no ${STAMP}: run ./scripts/build/android/build-dual-hz.sh first."
want="$(sed -n 's/^fingerprint=//p' "${STAMP}")"
have="$("${SCRIPT_DIR}/engine-fingerprint.sh")"
if [ "${want}" != "${have}" ]; then
  fail "the engine sources changed since the engines were built (stamp ${want:0:12}, tree ${have:0:12}).
A native change needs ./scripts/build/android/build-dual-hz.sh (GX_BUILD_GAMES=zh|generals for one game)."
fi
while read -r _ sha path; do
  [ -f "${path}" ] || fail "engine ${path} is missing."
  [ "$(sha256sum "${path}" | cut -c1-64)" = "${sha}" ] || fail "engine ${path} differs from the stamp."
done < <(grep '^sha256 ' "${STAMP}")
echo "Engines match ${STAMP} ($(sed -n 's/^version=//p' "${STAMP}"), built $(sed -n 's/^built=//p' "${STAMP}"))"

export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_NDK_HOME="/opt/android-sdk/ndk/${NDK_VERSION}"
export GX_ANDROID_STAGING="${REPO}/android-staging"
export GX_SECOND_GAME_LIB="${REPO}/build/libmain60.so"
export GX_SECOND_BASE_GAME_LIB="${REPO}/build/libgenerals60.so"
export GX_ANDROID_VERSION_CODE="${GX_ANDROID_VERSION_CODE:-}"
export GX_ANDROID_VERSION_NAME="${GX_ANDROID_VERSION_NAME:-}"
if [ -x /opt/gradle/bin/gradle ]; then
  export PATH="/opt/gradle/bin:$PATH"
fi

# As build-local-sandboxed.sh: always from a clean Gradle build dir (AGP's incremental packager can
# leave superseded entries in the zip).
rm -rf android/app/build
./scripts/build/android/package-android-zh.sh

APK="android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "${APK}" ] || fail "packaging produced no APK."
while read -r _ sha path; do
  name="$(basename "${path}")"
  case "${path}" in
    build/libmain60.so) name=libmain60.so ;;
    build/libgenerals60.so) name=libgenerals60.so ;;
  esac
  got="$(unzip -p "${APK}" "lib/arm64-v8a/${name}" | sha256sum | cut -c1-64)"
  [ "${got}" = "${sha}" ] || fail "the APK's ${name} is not the stamped engine."
done < <(grep '^sha256 ' "${STAMP}")
cp "${APK}" "${REPO}/GeneralsXZH-android-local.apk"
echo "=== REPACKAGED: ${REPO}/GeneralsXZH-android-local.apk ($(du -h "${APK}" | cut -f1)), engines verified ==="
