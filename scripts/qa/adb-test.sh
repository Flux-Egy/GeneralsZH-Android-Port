#!/usr/bin/env bash
#
# GeneralsX @build Flux Build 05/11/2026 Test loop on the phone this workspace runs on (adb is
# connected as emulator-5554): install a test APK, start it, and bring back what it produced.
#
#   adb-test.sh install <apk>   replace the installed app, keeping its data (adb install -r)
#   adb-test.sh launch          start the launcher (SetupActivity)
#   adb-test.sh logs            pull the game logs and the crash buffer into logs/device-<time>/
#   adb-test.sh screenshot      save a PNG of the screen into logs/device-<time>/
#   adb-test.sh record [secs]   record the screen (default 15 s, max 180) into logs/device-<time>/
#   adb-test.sh info            installed version of the package, device model and Android version
#
# Only the Personal build (com.generalsx.flux.dev) is touched unless GX_PACKAGE says otherwise; the
# phone also has com.generalsx.zerohour, flux.community and replayviewer installed. Recordings are
# made in /data/local/tmp, never on shared storage, and deleted from the phone once pulled. Installing
# replaces the app on the owner's phone: ask before running `install`.
set -euo pipefail

PKG="${GX_PACKAGE:-com.generalsx.flux.dev}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="${REPO}/logs/device-$(date +%Y%m%d-%H%M%S)"

die() { echo "adb-test: $*" >&2; exit 1; }
need_device() {
  adb get-state >/dev/null 2>&1 || die "no device: adb devices shows nothing connected."
}

cmd="${1:-}"
shift || true
case "${cmd}" in
  install)
    apk="${1:-}"
    [ -f "${apk}" ] || die "usage: adb-test.sh install <apk>"
    need_device
    adb install -r "${apk}"
    adb shell dumpsys package "${PKG}" | grep -m2 -E "versionName|versionCode" || true
    ;;
  launch)
    need_device
    adb shell am start -n "${PKG}/com.generalsx.zerohour.SetupActivity" >/dev/null
    echo "started ${PKG}"
    ;;
  logs)
    need_device
    mkdir -p "${OUT}"
    dir="/sdcard/Android/data/${PKG}/files"
    for f in generals-stderr.log generals-stderr-prev.log crash.log crash-prev.log generals-network.log; do
      adb pull "${dir}/${f}" "${OUT}/${f}" >/dev/null 2>&1 && echo "pulled ${f}" || true
    done
    adb logcat -d -b crash > "${OUT}/logcat-crash.txt" 2>/dev/null || true
    adb logcat -d -t 2000 --pid="$(adb shell pidof "${PKG}" 2>/dev/null | tr -d '\r')" > "${OUT}/logcat-app.txt" 2>/dev/null || true
    echo "logs in ${OUT}"
    ;;
  screenshot)
    need_device
    mkdir -p "${OUT}"
    adb exec-out screencap -p > "${OUT}/screen.png"
    echo "screenshot ${OUT}/screen.png"
    ;;
  record)
    secs="${1:-15}"
    [ "${secs}" -ge 1 ] && [ "${secs}" -le 180 ] || die "record length must be 1-180 seconds"
    need_device
    mkdir -p "${OUT}"
    remote="/data/local/tmp/gx-record-$$.mp4"
    adb shell screenrecord --time-limit "${secs}" "${remote}"
    adb pull "${remote}" "${OUT}/record.mp4" >/dev/null
    adb shell rm -f "${remote}"
    echo "recording ${OUT}/record.mp4"
    ;;
  info)
    need_device
    echo "device: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
    adb shell dumpsys package "${PKG}" | grep -m2 -E "versionName|versionCode" || echo "${PKG} not installed"
    ;;
  *)
    sed -n '4,17p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
