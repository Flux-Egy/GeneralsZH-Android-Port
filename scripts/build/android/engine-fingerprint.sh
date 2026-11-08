#!/usr/bin/env bash
#
# GeneralsX @build Flux Build 05/11/2026 Fingerprint of everything that goes into the native engines:
# the engine sources, the CMake setup, the native dependencies' manifests and the build scripts.
# The Java launcher, its resources, docs and APKs are not part of it -- a launcher-only change leaves
# the fingerprint unchanged, which is what lets repackage-launcher.sh reuse the engines safely.
#
# It covers the working tree as it is, not only commits: tracked and untracked (not ignored) files
# under those paths, by content. Prints one sha256.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "${REPO}"

ENGINE_PATHS=(
  Core Generals GeneralsMD Dependencies cmake triplets
  CMakeLists.txt CMakePresets.json vcpkg.json vcpkg-lock.json
  scripts/build/android/build-local-sandboxed.sh
  scripts/build/android/build-dual-hz.sh
  scripts/build/android/vcpkg-sandbox-patches
)
EXISTING=()
for p in "${ENGINE_PATHS[@]}"; do
  [ -e "$p" ] && EXISTING+=("$p")
done

# GeneralsX @bugfix Flux Build 05/11/2026 Content only. The first version hashed the index plus the
# working-tree diff, so committing the very files an engine was built from changed the fingerprint and
# repackage-launcher.sh refused a launcher-only build after every commit. Now the paths are added to a
# private copy of the index (untracked files included, ignored ones not) and written as a git tree,
# whose object ids depend on the file contents alone.
GIT_DIR_PATH="$(git rev-parse --absolute-git-dir)"
TMP_INDEX="${GIT_DIR_PATH}/gx-fingerprint-index.$$"
trap 'rm -f "${TMP_INDEX}"' EXIT
cp "${GIT_DIR_PATH}/index" "${TMP_INDEX}"
GIT_INDEX_FILE="${TMP_INDEX}" git add -A -- "${EXISTING[@]}"
TREE="$(GIT_INDEX_FILE="${TMP_INDEX}" git write-tree)"
git ls-tree "${TREE}" -- "${EXISTING[@]}" | sha256sum | cut -c1-64
