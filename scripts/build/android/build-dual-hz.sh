#!/usr/bin/env bash
#
# Build an APK carrying both engines, so the launcher's Simulation Rate setting
# has something to switch between.
#
# The tick rate cannot be a runtime option: WWSyncPerSecond is an enum constant,
# baked into every static_assert, array bound and derived timing constant, and
# GameLogic declares m_frameLegacy behind the same macro -- two translation units
# that disagreed about it would disagree about the class layout. So we build the
# engine twice and let GeneralsZHActivity.getLibraries() load one of them.
#
# Pass 1 builds 60 Hz and sets the results aside; pass 2 builds 30 Hz and packages
# all four (Zero Hour's libmain/libmain60, the base game's libgenerals/libgenerals60). The 30 Hz build goes last because it produces libmain.so, the default the
# launcher falls back to.
#
# The APK roughly doubles in size, since libmain.so is by far the largest thing in
# it. That is the price of the setting.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
STASH="${REPO}/build/libmain60.so"
STASH_BASE="${REPO}/build/libgenerals60.so"

# GeneralsX @build Flux Build 05/11/2026 Both engines must come from the same tree (AGENTS.md): the
# engine sources are fingerprinted now and again after pass 2, and a change in between fails the
# build. The fingerprint is then recorded with the engines' hashes in build/engines.stamp, which
# repackage-launcher.sh checks before reusing them for a launcher-only change.
FINGERPRINT_START="$("${SCRIPT_DIR}/engine-fingerprint.sh")"

# GeneralsX @build Flux Build 05/11/2026 Every test build keeps the engines' symbol tables, so a
# tester's crash report (tombstone, "[GX-NET] math site" addresses) can be symbolized with
# llvm-symbolizer / ndk-stack. Only the two newest builds are kept (about 50 MB per engine).
VERSION_NAME="${GX_ANDROID_VERSION_NAME:-$(sed -n "s/.*versionName.*: '\([^']*\)').*/\1/p" "${REPO}/android/app/build.gradle" | head -1)}"
export GX_KEEP_SYMBOLS_DIR="${GX_KEEP_SYMBOLS_DIR:-${REPO}/build/symbols/${VERSION_NAME:-unversioned}}"
echo "Symbol tables go to ${GX_KEEP_SYMBOLS_DIR}"

echo "=== pass 1 of 2: 60 Hz engine ==="
GX_EXTRA_CMAKE="-DSAGE_HIGH_FPS_SIM=ON" "${SCRIPT_DIR}/build-local-sandboxed.sh"

BUILT="${REPO}/build/android-vulkan/GeneralsMD/Code/Main/libmain.so"
[ -f "${BUILT}" ] || { echo "60 Hz libmain.so missing at ${BUILT}"; exit 1; }
mkdir -p "$(dirname "${STASH}")"
cp "${BUILT}" "${STASH}"
echo "60 Hz engine set aside: ${STASH} ($(du -h "${STASH}" | cut -f1))"
# GeneralsX @build Android port 04/10/2026 The base game's 60 Hz engine, from the same pass.
BUILT_BASE="${REPO}/build/android-vulkan/Generals/Code/Main/libgenerals.so"
[ -f "${BUILT_BASE}" ] || { echo "60 Hz libgenerals.so missing at ${BUILT_BASE}"; exit 1; }
cp "${BUILT_BASE}" "${STASH_BASE}"
echo "60 Hz base-game engine set aside: ${STASH_BASE} ($(du -h "${STASH_BASE}" | cut -f1))"

echo "=== pass 2 of 2: 30 Hz engines, then package all four ==="
GX_EXTRA_CMAKE="-DSAGE_HIGH_FPS_SIM=OFF" GX_SECOND_GAME_LIB="${STASH}" GX_SECOND_BASE_GAME_LIB="${STASH_BASE}" \
    "${SCRIPT_DIR}/build-local-sandboxed.sh"

FINGERPRINT_END="$("${SCRIPT_DIR}/engine-fingerprint.sh")"
if [ "${FINGERPRINT_START}" != "${FINGERPRINT_END}" ]; then
  echo "ERROR: the engine sources changed during the build; the two passes may differ in more than"
  echo "the tick rate. Rebuild with the tree left alone. No engines.stamp written."
  exit 1
fi
STAMP="${REPO}/build/engines.stamp"
{
  echo "fingerprint=${FINGERPRINT_END}"
  echo "head=$(git -C "${REPO}" rev-parse HEAD)"
  echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "version=${VERSION_NAME}"
  echo "games=${GX_BUILD_GAMES:-all}"
  for lib in "build/android-vulkan/GeneralsMD/Code/Main/libmain.so" "build/libmain60.so" \
             "build/android-vulkan/Generals/Code/Main/libgenerals.so" "build/libgenerals60.so" \
             "build/android-vulkan/GeneralsMD/Code/Tools/FluxWB/libfluxwb.so"; do
    # libfluxwb.so is optional (a base-game-only build whose previous APK had none).
    case "${lib}" in *libfluxwb.so) [ -f "${REPO}/${lib}" ] || continue ;; esac
    echo "sha256 $(sha256sum "${REPO}/${lib}" | cut -c1-64) ${lib}"
  done
} > "${STAMP}"
echo "Engines recorded in ${STAMP}"

# Keep the symbol tables of the two newest builds only.
if [ -d "${REPO}/build/symbols" ]; then
  ls -1dt "${REPO}/build/symbols"/*/ 2>/dev/null | tail -n +3 | while read -r old; do
    rm -rf "${old}"
    echo "Removed old symbol tables: ${old}"
  done
fi

echo "=== done: the APK carries libmain.so / libmain60.so (Zero Hour) and libgenerals.so / libgenerals60.so (Generals) ==="
