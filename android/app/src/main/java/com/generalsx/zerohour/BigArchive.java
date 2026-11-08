// GeneralsX @feature Flux Build 03/11/2026 Read-only access to a game .big archive, for the
// launcher's hotkey editor: it shows the game's own key map, which lives in the language archive
// (Data\English\CommandMap.ini in EnglishZH.big), not in a loose file.
//
// BIGF layout, as the engine reads it (StdBIGFileSystem::openArchiveFile): "BIGF" (or "BIG4"), the
// archive size (little-endian, unused here), the entry count and the header length (big-endian),
// then per entry its offset and size (big-endian) and a zero-terminated path.
package com.generalsx.zerohour;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BigArchive {
    private BigArchive() {}

    /** Entries beyond this, or a path longer than this, mean a corrupt header, not a real archive. */
    private static final int MAX_ENTRIES = 1_000_000;
    private static final int MAX_PATH = 1024;
    /** The only files read through this are INI text; a larger "entry" is a corrupt size field. */
    private static final long MAX_READ = 16L << 20;

    // GeneralsX @feature Flux Build 05/11/2026 Archives named without ".big". Hotkey and interface
    // packs are passed around as "!HotkeysLeikezeZH" (no extension) or "*.zh"; the engine mounts them
    // under those names (ArchiveFileSystem::addRenamedBigArchives), so the launcher installs and reads
    // them without renaming. Same rule as the engine: no extension (as std::filesystem sees it) or
    // ".zh", and the file starts with the BIGF signature.

    /** Whether a file name is one the engine checks for a renamed archive: no extension, or ".zh". */
    static boolean isRenamedArchiveName(String name) {
        String leaf = new File(name).getName();
        int dot = leaf.lastIndexOf('.');
        return dot <= 0 || leaf.substring(dot).equalsIgnoreCase(".zh");
    }

    /** Whether the file starts with the BIGF signature (the engine's loader opens no other). */
    static boolean hasSignature(File file) {
        if (!file.isFile() || file.length() < 4) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && magic[0] == 'B' && magic[1] == 'I' && magic[2] == 'G'
                && magic[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether the engine mounts this file as an archive: a .big, or a renamed archive. */
    static boolean isMountedArchive(File file) {
        String lower = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (lower.endsWith(".big") || (isRenamedArchiveName(lower) && hasSignature(file)));
    }

    /**
     * GeneralsX @bugfix Flux Build 05/11/2026 The engine's data-mod rule for interface packs
     * (ArchiveFileSystem.cpp gxArchiveIsDataMod, gxButtonsMatchPatch), kept in step with it so the
     * launcher says what the engine will do. MappedImages, ControlBarScheme, InGameUI and CommandMap
     * are read by the client only and pass when they hold no other block type. CommandButton is read
     * by the game logic too (Object::canProduceUpgrade), and a '!' archive's CommandButton.ini
     * replaces the community patch's whole set, so it passes only when it is the patch's buttons with
     * other pictures: every patch button present, every logic field read the same, nothing else in
     * the files. Read as INI.cpp reads them; anything not fully understood counts as a mod.
     */
    private static boolean isFileOrDirectory(String lowerPath, String stem) {
        String p = lowerPath.replace('/', '\\');
        if (!p.startsWith(stem)) {
            return false;
        }
        String rest = p.substring(stem.length());
        return rest.equals(".ini") || rest.startsWith("\\");
    }

    /** The block type a client-only INI may hold (gxClientOnlyBlock), or null when it is not one. */
    static String clientOnlyBlock(String lowerPath) {
        String p = lowerPath.replace('/', '\\');
        if (p.startsWith("data\\ini\\mappedimages\\")) {
            return "MappedImage";
        }
        if (isFileOrDirectory(p, "data\\ini\\controlbarscheme")) {
            return "ControlBarScheme";
        }
        if (isFileOrDirectory(p, "data\\ini\\ingameui")) {
            return "InGameUI";
        }
        if (isFileOrDirectory(p, "data\\ini\\commandmap")) {
            return "CommandMap";
        }
        return null;
    }

    static boolean isCommandButtonIni(String lowerPath) {
        return isFileOrDirectory(lowerPath, "data\\ini\\commandbutton");
    }

    /**
     * Whether the engine counts this archive as a data mod, which keeps the community patch out and
     * changes the INI checksum. anyIni: a '!' archive or a mod from the Mods screen (any Data\INI file
     * counts but clean interface INI, as above); otherwise only unit and rule INI
     * (HotkeyPackInstaller.isGameplayIni). Reads the archives; call it off the UI thread.
     */
    static boolean isDataModArchive(File archive, List<Entry> entries, boolean anyIni) {
        entries = engineOrder(entries);
        boolean buttons = false;
        for (Entry e : entries) {
            String lower = e.path.replace('/', '\\').toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".ini")) {
                continue;
            }
            if (anyIni) {
                String own = clientOnlyBlock(lower);
                if (own != null) {
                    if (clientOnlyIsClean(archive, e, own)) {
                        continue;
                    }
                    return true;
                }
                if (isCommandButtonIni(lower)) {
                    buttons = true;
                    continue;
                }
                // Data\<Language>\ INI go through INI::load too, outside the checksum.
                if (lower.startsWith("data\\") && !lower.startsWith("data\\ini\\")) {
                    if (clientOnlyIsClean(archive, e, "CommandMap", "HeaderTemplate", "Language")) {
                        continue;
                    }
                    return true;
                }
            }
            if (anyIni ? lower.startsWith("data\\ini\\") : HotkeyPackInstaller.isGameplayIni(lower)) {
                return true;
            }
        }
        return buttons && !buttonsMatchPatch(archive, entries);
    }

    /**
     * The entries as the engine walks them (getFileListInDirectory: a set ordered without regard to
     * case, each path once, a repeated path as its last entry), so "a button defined twice, the later
     * wins" picks the same one.
     */
    private static List<Entry> engineOrder(List<Entry> entries) {
        java.util.TreeMap<String, Entry> byPath = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Entry e : entries) {
            byPath.put(e.path.replace('/', '\\'), e);
        }
        return new ArrayList<>(byPath.values());
    }

    /** isDataModArchive for a file in the game folder: '!' names use the anyIni rule. */
    static boolean isGameplayArchive(File archive, String installedName, List<Entry> entries) {
        String name = new File(installedName).getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!name.startsWith("!") && (lower.equals("inizh.big") || lower.equals("ini.big"))) {
            return false;
        }
        return isDataModArchive(archive, entries, name.startsWith("!"));
    }

    /** INI.cpp's block types (theTypeTable). */
    private static final String[] BLOCKS = {
        "AIData", "Animation", "Armor", "AudioEvent", "AudioSettings", "BenchProfile", "Bridge", "Campaign",
        "ChallengeGenerals", "CommandButton", "CommandMap", "CommandSet", "ControlBarResizer", "ControlBarScheme",
        "CrateData", "Credits", "DamageFX", "DialogEvent", "DrawGroupInfo", "DynamicGameLOD", "EvaEvent", "FXList",
        "GameData", "HeaderTemplate", "InGameUI", "LODPreset", "Language", "Locomotor", "MapCache", "MapData",
        "MappedImage", "MiscAudio", "Mouse", "MouseCursor", "MultiplayerColor", "MultiplayerSettings",
        "MultiplayerStartingMoneyChoice", "MusicTrack", "Object", "ObjectCreationList", "ObjectReskin",
        "OnlineChatColors", "ParticleSystem", "PlayerTemplate", "Rank", "ReallyLowMHz", "Road", "Science",
        "ScriptAction", "ScriptCondition", "ShellMenuScheme", "SpecialPower", "StaticGameLOD", "Terrain", "Upgrade",
        "Video", "WaterSet", "WaterTransparency", "Weapon", "Weather", "WebpageURL", "WindowTransition",
    };
    private static final String[] DISPLAY_FIELDS = {
        "TextLabel", "DescriptLabel", "PurchasedLabel", "ConflictingLabel", "ButtonImage", "CursorName",
        "InvalidCursorName", "ButtonBorderType", "RadiusCursorType", "UnitSpecificSound",
    };

    /**
     * The lines of an INI text, or null for text INI.cpp would read differently from a plain line
     * split (gxForEachIniLine): a line of 1028 characters or more (INI_MAX_CHARS_PER_LINE; readLine
     * reads the rest as a new line, even after a ';') or a NUL byte (it ends readLine's string).
     */
    private static List<String> lines(String text) {
        if (text.indexOf('\0') >= 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.length() > 1027) {
                return null;
            }
            out.add(line);
        }
        return out;
    }

    /** One line as INI::readLine and strtok(getSeps()) see it (gxIniTokens). */
    private static List<String> tokens(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ';') {
                break;
            }
            if (c > 0 && c < 32) {
                c = ' ';
            }
            if (c == ' ' || c == '=') {
                if (token.length() > 0) {
                    out.add(token.toString());
                }
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        if (token.length() > 0) {
            out.add(token.toString());
        }
        return out;
    }

    private static boolean clientOnlyIsClean(File archive, Entry e, String... allowed) {
        try {
            List<String> lines = lines(new String(read(archive, e), StandardCharsets.ISO_8859_1));
            if (lines == null) {
                return false;
            }
            for (String line : lines) {
                List<String> t = tokens(line);
                if (t.isEmpty()) {
                    continue;
                }
                for (String block : BLOCKS) {
                    if (block.equals(t.get(0)) && !java.util.Arrays.asList(allowed).contains(block)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    private static String sPatchKey;
    private static Map<String, Map<String, String>> sPatchButtons;

    private static boolean buttonsMatchPatch(File archive, List<Entry> entries) {
        try {
            Map<String, Map<String, String>> theirs = patchButtons();
            if (theirs == null || theirs.isEmpty()) {
                return false;
            }
            Map<String, Map<String, String>> mine = readButtons(archive, entries);
            if (mine == null) {
                return false;
            }
            for (Map.Entry<String, Map<String, String>> b : theirs.entrySet()) {
                Map<String, String> m = mine.get(b.getKey());
                if (m == null) {
                    return false;
                }
                java.util.Set<String> fields = new java.util.HashSet<>(b.getValue().keySet());
                fields.addAll(m.keySet());
                for (String field : fields) {
                    String a = b.getValue().get(field);
                    String c = m.get(field);
                    if (!(a == null ? "" : a).equals(c == null ? "" : c)) {
                        return false;
                    }
                }
            }
            // Buttons the patch does not have: map scripts can name them (gxButtonsMatchPatch).
            return mine.size() == theirs.size();
        } catch (IOException e) {
            return false;
        }
    }

    /** The community patch's buttons, read once per version of the file; null when absent or unreadable. */
    private static synchronized Map<String, Map<String, String>> patchButtons() throws IOException {
        File patch = DataPackInstaller.communityPatchFile();
        if (!patch.isFile()) {
            return null;
        }
        String key = patch.getAbsolutePath() + "|" + patch.length() + "|" + patch.lastModified();
        if (!key.equals(sPatchKey)) {
            sPatchButtons = readButtons(patch, engineOrder(list(patch, true)));
            sPatchKey = key;
        }
        return sPatchButtons;
    }

    /** The CommandButton INI of one archive (gxReadButtonLogic); null when any of it cannot be vouched for. */
    private static Map<String, Map<String, String>> readButtons(File archive, List<Entry> entries) throws IOException {
        Map<String, Map<String, String>> buttons = new HashMap<>();
        for (Entry e : entries) {
            String lower = e.path.replace('/', '\\').toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".ini") || !isCommandButtonIni(lower)) {
                continue;
            }
            // A subfolder loads after its folder's own files (INI::loadDirectory): not vouched for.
            String stem = "data\\ini\\commandbutton\\";
            if (lower.startsWith(stem) && lower.indexOf('\\', stem.length()) >= 0) {
                return null;
            }
            if (!parseButtons(new String(read(archive, e), StandardCharsets.ISO_8859_1), buttons)) {
                return null;
            }
        }
        return buttons;
    }

    /** gxParseButtonLogic: false for anything but CommandButton blocks with known fields, all closed. */
    private static boolean parseButtons(String text, Map<String, Map<String, String>> buttons) {
        Map<String, String> current = null;
        List<String> lines = lines(text);
        if (lines == null) {
            return false;
        }
        for (String line : lines) {
            List<String> t = tokens(line);
            if (t.isEmpty()) {
                continue;
            }
            String first = t.get(0);
            if (current == null) {
                if (!first.equals("CommandButton") || t.size() < 2) {
                    return false;
                }
                current = buttons.computeIfAbsent(t.get(1), k -> new HashMap<>());
                continue;
            }
            if (first.equalsIgnoreCase("End")) {
                current = null;
                continue;
            }
            boolean display = false;
            for (String f : DISPLAY_FIELDS) {
                if (f.equals(first)) {
                    display = true;
                    break;
                }
            }
            if (display) {
                continue;
            }
            String value;
            String token = t.size() > 1 ? t.get(1) : "";
            if (first.equals("Options") && t.size() < 2) {
                return false; // parseBitString32 with no names keeps the earlier bits
            }
            if (first.equals("Options") || first.equals("Science")) {
                value = String.join(" ", t.subList(1, t.size()));
                if (first.equals("Options")) {
                    value = asciiUpper(value);
                }
            } else if (first.equals("Command") || first.equals("WeaponSlot") || first.equals("MaxShotsToFire")) {
                value = asciiUpper(token);
            } else if (first.equals("Object") || first.equals("Upgrade") || first.equals("SpecialPower")) {
                value = token.equalsIgnoreCase("None") ? "" : token;
            } else {
                return false;
            }
            current.put(first, value);
        }
        return current == null;
    }

    /** C toupper: ASCII letters only. */
    private static String asciiUpper(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return b.toString();
    }

    static final class Entry {
        final String path;
        final long offset;
        final long size;

        Entry(String path, long offset, long size) {
            this.path = path;
            this.offset = offset;
            this.size = size;
        }
    }

    static List<Entry> list(File archive) throws IOException {
        return list(archive, false);
    }

    /**
     * keepTruncated: also the entries that run past the end of the file, which the engine still
     * lists (StdBIGFileSystem::openArchiveFile) -- for the data-mod verdict, where reading one fails
     * and counts as a mod, as the engine's read does.
     */
    static List<Entry> list(File archive, boolean keepTruncated) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(archive), 64 * 1024))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'B' || magic[1] != 'I' || magic[2] != 'G' || (magic[3] != 'F' && magic[3] != '4')) {
                throw new IOException("not a BIG archive: " + archive.getName());
            }
            in.readInt(); // archive size, little-endian; not needed
            int count = in.readInt();
            in.readInt(); // header length
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IOException("corrupt BIG entry count " + count + ": " + archive.getName());
            }
            long length = archive.length();
            byte[] name = new byte[MAX_PATH];
            for (int i = 0; i < count; i++) {
                long offset = in.readInt() & 0xffffffffL;
                long size = in.readInt() & 0xffffffffL;
                int n = 0;
                int b;
                while ((b = in.read()) > 0) {
                    if (n == MAX_PATH) {
                        throw new IOException("corrupt BIG path in " + archive.getName());
                    }
                    name[n++] = (byte) b;
                }
                if (b < 0) {
                    throw new IOException("BIG header ends early: " + archive.getName());
                }
                if (keepTruncated || offset + size <= length) {
                    entries.add(new Entry(new String(name, 0, n, StandardCharsets.ISO_8859_1), offset, size));
                }
            }
        }
        return entries;
    }

    /** The entry with this path (either slash, any case), or null. */
    static Entry find(List<Entry> entries, String path) {
        String want = normalize(path);
        for (Entry e : entries) {
            if (normalize(e.path).equals(want)) {
                return e;
            }
        }
        return null;
    }

    static byte[] read(File archive, Entry entry) throws IOException {
        if (entry.size > MAX_READ) {
            throw new IOException("entry too large: " + entry.path);
        }
        byte[] data = new byte[(int) entry.size];
        try (RandomAccessFile raf = new RandomAccessFile(archive, "r")) {
            raf.seek(entry.offset);
            raf.readFully(data);
        }
        return data;
    }

    private static String normalize(String path) {
        return path.replace('/', '\\').toLowerCase(Locale.ROOT);
    }
}
