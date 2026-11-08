# PLAN-031: Mod installers without Windows, and GenPatcher-style patches (research, no code)

Status: research, 06/11/2026. Owner's Phase 5. Nothing here is implemented.

Two questions: (1) can the launcher take a mod that ships as a Windows `.exe` and install it
without running any Windows code, and (2) can it do what GenPatcher does to a game folder?

## What is on the owner's phone (read-only survey, 06/11/2026)

| File | What it is |
|---|---|
| `New Folder555/GenPatcher.exe` (11.7 MB) | A program. No data after the PE sections, and no 7z/RAR/ZIP/CAB/NSIS/Inno signature anywhere in it: nothing to unpack. |
| `ROTR/SWRNetSetup.exe` (1.8 MB) | 1.69 MB of data after the PE sections in no format probed (7z, RAR4/5, ZIP, CAB, NSIS, Inno) -- an installer whose payload is not a standard archive. |
| `ROTR/!Rotr_INI.gib`, `!Rotr_AI.gib` | Plain BIG archives (`BIGF` magic) under another extension. ROTR's launcher renames `.gib` <-> `.big` to switch the mod on and off. |
| Game folder: `340_ControlBarProZH.big`, `340_ControlBarPro1080ZH.big` | GenPatcher's Control Bar Pro: 186 art/window files, and 22 `Data\<Language>\HeaderTemplate.ini` / `Language.ini` (fonts, sizes). |
| Game folder: `340_ControlBarProHideIpZH.big.bak`, `...HideMailZH.big.bak` | Options switched OFF by renaming to `.bak` (two `.wnd` menu layouts each). |
| Game folder: `!HotkeysLeikezeZH`, `!HotkeysLeikezeIndicatorsZH` | Hotkey packs, BIG archives without an extension (mounted natively since test1). |

Probe used: `sfxprobe.py` (scratchpad) -- reads the PE header, finds where the last section ends
(the "overlay", where self-extractors keep their archive), and looks for archive signatures.

## Part 1: mod installers that are `.exe` files

### The kinds that exist, and what each takes

| Kind | How to recognise it | Can it be unpacked without Windows? | Library (licence, fits GPLv3?) |
|---|---|---|---|
| 7-Zip SFX | `7z\xBC\xAF\x27\x1C` at the overlay | Yes -- a normal 7z after a stub | libarchive (BSD-2, yes); Apache Commons Compress (Apache-2.0, yes) in Java |
| WinRAR SFX | `Rar!\x1A\x07\x00` (RAR4) / `Rar!\x1A\x07\x01\x00` (RAR5) | Yes | libarchive reads RAR4 and RAR5 (independent decoder, BSD). Not unRAR: its licence is not GPL-compatible. Java's junrar reads RAR4 only |
| ZIP SFX | `PK\x03\x04` after the stub, end-of-central-directory at the end | Yes; most ZIP readers accept the prefix | java.util.zip already in the launcher; libarchive |
| Cabinet (MS) | `MSCF` | Yes | libarchive |
| NSIS | `\xEF\xBE\xAD\xDE` + `NullsoftInst` | Files yes (zlib/bzip2/LZMA blocks); the install SCRIPT (where files go, registry) only partly | 7-Zip's NSIS handler (LGPL-2.1+, compatible); libarchive has no NSIS support |
| Inno Setup | `Inno Setup Setup Data` / `zlb\x1A` blocks | Files and their target folders, yes | innoextract (zlib licence, C++, needs Boost + liblzma: heavy for the APK) |
| InstallShield / MSI / custom (e.g. SWRNetSetup) | none of the above | Usually no | -- refuse with a clear message |

Most Generals mods are distributed as ZIP/7z/RAR archives or as `.big`/`.gib` sets (ROTR's folder is
exactly that). Installers that are executables are mostly 7-Zip or WinRAR self-extractors (easy)
and NSIS/Inno installers (possible, heavier). The launcher should never try to run any of them.

### Recommended design

1. **Detect, never execute.** Read the PE header, take the overlay offset (end of the last
   section's raw data), check the signatures there; if none match, scan the first and last few MB
   for them (some stubs pad). Classify as above; anything unrecognised gets "This installer is not
   a supported archive -- extract it on a PC, or ask the mod's author for a ZIP".
2. **One native reader: libarchive** (NDK build, ~1 MB in the APK, BSD-2). It reads 7z, RAR4,
   RAR5, ZIP, CAB from an offset (archive_read_open with a custom read callback that starts at the
   overlay). Java alternative without native code: Commons Compress (7z, ZIP) + junrar (RAR4 only)
   -- no RAR5, which is now the default WinRAR format, so the native route is the complete one.
3. **NSIS and Inno later, separately**: NSIS through a port of 7-Zip's NSIS handler (extract the
   files; ignore the script except `SetOutPath` targets, which tell where files go); Inno through
   innoextract's format code. Each is a sizeable port; worth it only if real Generals mods ship
   that way -- collect samples first.
4. **Feed the existing pipeline.** Whatever comes out goes into a staging folder and then through
   the importer that already exists (ModImportService / ModPacker / DataPackInstaller): `.big`,
   `.gib` and extensionless BIG archives are recognised there, loose `Data\`, `Art\`, `Window\`,
   `Maps\` files are packed. The data-mod rule (ArchiveFileSystem.cpp gxArchiveIsDataMod) then
   decides, as for any mod, whether the community patch stays mounted.
5. **Safety, all mandatory:** staging under the app's own storage; reject absolute paths and `..`
   (zip-slip); cap total size and file count (archive bombs); keep only game-relevant files
   (`.big .gib .ini .wnd .tga .dds .w3d .str .csf .map .scb .wav .mp3 .bik`), drop `.exe .dll .bat
   .vbs .ps1 .lnk .scr` and other executables silently with a count in the summary; work on a
   background thread with a cancel button; never write into the game folder except through the
   existing managed-mods path; log `[mod-import]` lines for the owner.
6. **Launchers that rename** (ROTR's `.gib`): no unpacking needed -- the importer should treat
   `.gib` as a BIG archive and let the Mods screen switch it, which is what the rename did.

## Part 2: what GenPatcher does, and doing it on Android

GenPatcher is a Windows program (above: nothing to unpack from it). What it leaves in a game folder
is visible on the owner's phone, and that is the part worth mirroring: it does not edit the game's
INI text in place -- it adds **override archives** and switches them by **renaming**:

- an option is a `.big` with a priority prefix (`340_...`): BIG archives load in name order, so the
  prefix decides what overrides what (`!` before digits before letters);
- an option switched off is the same file renamed `.big.bak`;
- the archives carry interface files (textures, `.wnd` layouts, `HeaderTemplate.ini`,
  `Language.ini`), so they never touch the INI checksum.

The engine side already exists in this port: the VFS mounts `.big`, extensionless and `.zh`
archives by name order; managed mods are mounted ahead of the community patch; the data-mod rule
says which archives change game data.

### Recommended design ("Patches" screen in the launcher)

1. **Patch = one override archive plus a manifest**: `{ id, title, description, archive name with
   its priority prefix, kind: interface | gameplay, game: zh | generals, conflicts: [...] }`.
   Switching on copies (or un-`.bak`s) the archive into the managed location; off renames it to
   `.bak` -- the same convention as GenPatcher, so a folder prepared on a PC with GenPatcher keeps
   working and the launcher can show those options as switches (detect `340_*.big(.bak)` pairs).
2. **Text-level INI patches, when an option needs one value changed** (a camera height, a font
   size) rather than a whole file: the engine already has an override load type
   (INI.h `INI_LOAD_CREATE_OVERRIDES`, used for map.ini overrides). A patch can ship a small INI
   with only the changed blocks/fields, loaded as an override after the base set. Rules:
   - interface INI (HeaderTemplate, Language, InGameUI, ControlBarScheme, MappedImages, CommandMap)
     may be patched freely;
   - anything the INI checksum covers (GameData, Object, Weapon, ...) makes the install a data mod
     -- it must go through the data-mod rule, keep the community patch out, and be labelled
     "changes gameplay; online needs the same patch on every side". Never patched silently.
   - CommandButton is the special case already handled: allowed only when it equals the patch's
     logic fields (gxButtonsMatchPatch).
3. **Options.ini settings** GenPatcher-like tools also set (resolution, detail): the launcher already
   writes these through its own settings; no patch format needed.
4. **No executable patches.** Whatever a Windows tool does to `game.dat` / `generals.exe` (not
   verified here for GenPatcher) has no meaning on Android: the engine is built from source, and its
   fixes go into that source.

## Order of work (suggested)

1. `.gib` and `.big.bak` handling in the importer and Mods screen (smallest, real samples on the
   phone).
2. SFX detection + libarchive for 7z/RAR/ZIP/CAB, with the safety rules above.
3. Patches screen with GenPatcher-compatible `prefix + .bak` switching, interface patches only.
4. INI override patches (interface first), then NSIS/Inno if samples justify the porting cost.

## Open questions for the owner

- Which mods do players actually bring as `.exe`? (A few file names or links decide whether NSIS/
  Inno support is worth it.)
- Should GenPatcher's own options (Control Bar Pro variants, Hide IP, Hide Mail) appear as named
  switches, or generically as "override archive found: on/off"?
