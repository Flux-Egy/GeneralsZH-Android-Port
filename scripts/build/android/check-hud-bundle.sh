#!/usr/bin/env bash
# GeneralsX @feature Flux Build 10/11/2026 Build gate for the bundled HUD packs (references/hud/).
# Runs the launcher's own data-mod rule (BigArchive.isGameplayArchive, the Java twin of the engine's
# ArchiveFileSystem gxArchiveIsDataMod) on each bundled archive, under the name it is installed as, against
# the GeneralsOnline community patch. Any archive that would count as a data mod (keeps the patch out,
# changes the INI checksum, blocks online joins) fails the build. Also writes the asset manifest.
# Usage: check-hud-bundle.sh <hud dir> <out manifest>   (GX_HUD_PATCH = community patch .big)
set -euo pipefail
HUD="$1"; OUT="$2"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$REPO/android/app/src/main/java/com/generalsx/zerohour"
PATCH="${GX_HUD_PATCH:-/sdcard/Generals/Command and Conquer Generals Zero Hour Data/GeneralsOnlineGameData/500_900_CommunityPatch_CoreINI.big}"
[ -f "$PATCH" ] || { echo "check-hud-bundle: community patch not found ($PATCH); set GX_HUD_PATCH" >&2; exit 1; }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
P="$WORK/src/com/generalsx/zerohour"; mkdir -p "$P"
cp "$SRC/BigArchive.java" "$P/"
GAMEPLAY="$(awk '/private static final String\[\] GAMEPLAY_INI = \{/,/\};/' "$SRC/HotkeyPackInstaller.java")"
ISGP="$(awk '/static boolean isGameplayIni\(String relativePath\) \{/,/^    \}$/' "$SRC/HotkeyPackInstaller.java")"
[ -n "$GAMEPLAY" ] && [ -n "$ISGP" ] || { echo "check-hud-bundle: could not extract isGameplayIni" >&2; exit 1; }
cat > "$P/HotkeyPackInstaller.java" <<J
package com.generalsx.zerohour;
import java.util.Locale;
final class HotkeyPackInstaller {
$GAMEPLAY
$ISGP
}
J
cat > "$P/DataPackInstaller.java" <<J
package com.generalsx.zerohour;
import java.io.File;
final class DataPackInstaller {
    static File communityPatchFile() { return new File(System.getenv("GX_HUD_PATCH_RESOLVED")); }
}
J
cat > "$P/HudCheck.java" <<'J'
package com.generalsx.zerohour;
import java.io.File;
public final class HudCheck {
    public static void main(String[] a) throws Exception {
        int bad = 0;
        for (String path : a) {
            File f = new File(path);
            // As the app and the engine read it: truncated entries kept; a renamed archive must be BIGF.
            boolean dataMod = (BigArchive.isRenamedArchiveName(f.getName()) && !BigArchive.hasSignature(f))
                || BigArchive.isGameplayArchive(f, f.getName(), BigArchive.list(f, true));
            System.out.println((dataMod ? "DATA MOD (blocks online) " : "client-only ok ") + f.getName());
            if (dataMod) bad++;
        }
        System.exit(bad == 0 ? 0 : 2);
    }
}
J
javac -nowarn -d "$WORK/cls" $(find "$WORK/src" -name '*.java') > "$WORK/javac.log" 2>&1 || { cat "$WORK/javac.log" >&2; exit 1; }
shopt -s nullglob
FILES=("$HUD"/*)
[ ${#FILES[@]} -gt 0 ] || { echo "check-hud-bundle: no files in $HUD" >&2; exit 1; }
GX_HUD_PATCH_RESOLVED="$PATCH" java -cp "$WORK/cls" com.generalsx.zerohour.HudCheck "${FILES[@]}"
mkdir -p "$(dirname "$OUT")"
: > "$OUT"
printf '#patch\t%s\t%s\n' "$(stat -c %s "$PATCH")" "$(sha256sum "$PATCH" | cut -d' ' -f1)" >> "$OUT"
for f in "${FILES[@]}"; do
    printf '%s\t%s\t%s\n' "$(basename "$f")" "$(stat -c %s "$f")" "$(sha256sum "$f" | cut -d' ' -f1)" >> "$OUT"
done
echo "check-hud-bundle: ${#FILES[@]} archive(s) are client-only; manifest $OUT"
