// GeneralsX @feature Flux Build 03/11/2026 Hotkey and control-bar packs, installed as loose files
// where the engine actually reads them, with every replaced file backed up so "Restore" undoes
// exactly what was installed.
//
// Why each file goes where it goes:
//   - .csf  -> Data/<game language>/  Zero Hour's unit, building and ability hotkeys are the letter
//             after '&' in each button's label (HotKeyManager::searchHotKey); the labels live in
//             generals.csf. A loose copy counts as a mod's table (GameText.cpp), so its changed
//             labels win over the game's.
//   - .ini  -> Data/INI/ (keeping any path under Data/INI). The command map is loaded from
//             Data/<language>/CommandMap.ini and then Data/INI/CommandMap.ini over it, entry by entry.
//             INI files that define units or rules are refused: they change the INI checksum (no
//             online match) and, as loose files, make the engine treat the install as modded.
//   - .wnd  -> Window/ (keeping any path under Window/), where the engine loads layouts from.
//   - .big  -> the game folder with a leading '!', as a control-bar pack expects to be mounted.
//   - an archive named without ".big" ("!HotkeysLeikezeZH", "*.zh") -> the game folder under its
//             own name, unchanged: the engine mounts it as it is (BigArchive.isRenamedArchiveName).
// Anything else in a pack (readmes, screenshots, unknown extensions) is skipped and counted.
//
// The previous importer flattened every path into Data/INI, kept no backup for ZIPs, ran on the UI
// thread, and its "restore" deleted any game-folder file with "ControlBar" in its name.
package com.generalsx.zerohour;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class HotkeyPackInstaller {
    private HotkeyPackInstaller() {}

    private static final String TAG = "HotkeyPackInstaller";

    /** The command map the in-app editor writes (relative to the game folder). */
    static final String COMMAND_MAP = "Data/INI/CommandMap.ini";

    private static final String NO_BACKUP = "-";
    private static final long MAX_PACK_BYTES = 256L << 20;
    private static final int MAX_PACK_ENTRIES = 10_000;

    /**
     * The INI files the engine counts as unit or rule data (ArchiveFileSystem.cpp gxIsGameplayIni);
     * kept in step with it so the launcher refuses exactly what would turn the install into a mod.
     */
    // GeneralsX @bugfix Flux Build 05/11/2026 Each in both forms, as the engine: the single file
    // (weapon.ini) and the community patch's split directory (weapon/*.ini).
    private static final String[] GAMEPLAY_INI = {
        "data/ini/object", "data/ini/weapon", "data/ini/commandset",
        "data/ini/commandbutton", "data/ini/playertemplate", "data/ini/upgrade",
        "data/ini/science", "data/ini/specialpower", "data/ini/locomotor",
        "data/ini/armor", "data/ini/gamedata", "data/ini/aidata",
    };

    /** What an install did, for the message the player sees. */
    static final class Result {
        int installed;
        int skipped;
        int refusedGameplay;
        final List<String> files = new ArrayList<>();
        /** Installed archives the engine counts as gameplay mods (BigArchive.isGameplayArchive). */
        final List<String> gameplayArchives = new ArrayList<>();
    }

    /** An import problem with a message for the player (a string resource, with optional detail). */
    static final class ImportException extends IOException {
        final int messageRes;

        ImportException(int messageRes, String detail) {
            super(detail);
            this.messageRes = messageRes;
        }
    }

    // ------------------------------------------------------------------ routing

    /** Where a pack file goes, relative to the game folder; null to skip it; REFUSED for gameplay INI. */
    private static final String REFUSED = "\0refused";

    private static String route(String relativePath, String languageDir) {
        String[] parts = relativePath.replace('\\', '/').split("/");
        List<String> segments = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty() && !p.equals(".")) {
                segments.add(p);
            }
        }
        if (segments.isEmpty()) {
            return null;
        }
        String leaf = segments.get(segments.size() - 1);
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csf")) {
            return "Data/" + languageDir + "/" + leaf;
        }
        if (lower.endsWith(".ini")) {
            String sub = tailAfter(segments, "data", "ini");
            String rel = "Data/INI/" + (sub != null ? sub : leaf);
            return isGameplayIni(rel) ? REFUSED : rel;
        }
        if (lower.endsWith(".wnd")) {
            String sub = tailAfter(segments, "window", null);
            return "Window/" + (sub != null ? sub : leaf);
        }
        if (lower.endsWith(".big") || lower.endsWith(".gib")) {
            String name = leaf.substring(0, leaf.length() - 4) + ".big";
            return name.startsWith("!") ? name : "!" + name;
        }
        return null;
    }

    /** The path after the first "first[/second]" folder pair (any case), or null if there is none. */
    private static String tailAfter(List<String> segments, String first, String second) {
        int width = second == null ? 1 : 2;
        for (int i = 0; i + width < segments.size(); i++) {
            if (segments.get(i).equalsIgnoreCase(first)
                    && (second == null || segments.get(i + 1).equalsIgnoreCase(second))) {
                return String.join("/", segments.subList(i + width, segments.size()));
            }
        }
        return null;
    }

    static boolean isGameplayIni(String relativePath) {
        String lower = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (String rule : GAMEPLAY_INI) {
            if (lower.startsWith(rule)) {
                String rest = lower.substring(rule.length());
                if (rest.equals(".ini") || rest.startsWith("/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether an unpacked ZIP is a hotkey or interface pack rather than a mod: no archives, nothing
     * that defines units or rules, no art or maps, and at least one hotkey file (a label table, a
     * window layout or a command map). The Mods screen asks this before packing loose files.
     */
    static boolean isHotkeyPack(File root) {
        boolean[] hotkeyFile = { false };
        boolean[] modFile = { false };
        walk(root, "", (file, rel) -> {
            String lower = rel.toLowerCase(Locale.ROOT);
            String leaf = file.getName().toLowerCase(Locale.ROOT);
            if (leaf.endsWith(".big") || leaf.endsWith(".gib")
                    || (BigArchive.isRenamedArchiveName(leaf) && BigArchive.hasSignature(file))
                    || lower.startsWith("art/")
                    || lower.contains("/art/") || lower.startsWith("maps/") || lower.contains("/maps/")) {
                modFile[0] = true;
            } else if (leaf.endsWith(".csf") || leaf.endsWith(".wnd") || leaf.startsWith("commandmap")) {
                hotkeyFile[0] = true;
            } else if (leaf.endsWith(".ini")) {
                String sub = tailAfter(splitPath(rel), "data", "ini");
                if (isGameplayIni("Data/INI/" + (sub != null ? sub : file.getName()))) {
                    modFile[0] = true;
                }
            }
        });
        return hotkeyFile[0] && !modFile[0];
    }

    private static List<String> splitPath(String rel) {
        List<String> out = new ArrayList<>();
        for (String p : rel.split("/")) {
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ install

    /** Installs a picked document (.zip, .ini, .csf, .wnd or .big). Call off the UI thread. */
    static Result installUri(Context ctx, Uri uri, String displayName) throws IOException {
        File gameRoot = gameRoot(ctx);
        String name = displayName == null || displayName.trim().isEmpty() ? "hotkeys.zip" : displayName.trim();
        String lower = name.toLowerCase(Locale.ROOT);
        File staging = new File(ctx.getCacheDir(), "hotkey_import_" + System.currentTimeMillis());
        try {
            if (!staging.mkdirs()) {
                throw new IOException("cannot create " + staging);
            }
            if (lower.endsWith(".zip")) {
                extractZip(ctx, uri, staging);
            } else if (route(name, "English") != null || BigArchive.isRenamedArchiveName(name)) {
                String leaf = new File(name).getName();
                File copied = new File(staging, leaf);
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("cannot open " + name);
                    }
                    copy(in, copied);
                }
                // GeneralsX @feature Flux Build 05/11/2026 No extension or ".zh": an archive only if
                // it is one; anything else is still not a hotkey file.
                if (route(name, "English") == null && !BigArchive.hasSignature(copied)) {
                    throw new ImportException(R.string.hotkeys_import_unsupported, name);
                }
            } else {
                throw new ImportException(R.string.hotkeys_import_unsupported, name);
            }
            Result result = installTree(ctx, gameRoot, staging);
            if (result.installed == 0 && result.refusedGameplay == 0) {
                throw new ImportException(R.string.hotkeys_import_nothing, name);
            }
            return result;
        } finally {
            deleteRecursively(staging);
        }
    }

    // ------------------------------------------------------------------ control bars

    /** Prefix of every archive a control bar package is installed as. */
    static final String CONTROL_BAR_PREFIX = "00CB_";

    /**
     * GeneralsX @feature Flux Build 03/11/2026 A control bar package (.zip or .big). Archives in it
     * are installed as 00CB_<name>.big; its loose files (Art, Data, Window) are packed into one
     * 00CB_<package>.big. "00" sorts ahead of every retail archive and of the GeneralsOnline patch
     * (500_900_...), so the control bar wins over them, but -- unlike a '!' name -- it is not taken
     * for a gameplay mod (ArchiveFileSystem.cpp gxIsDataModArchive), so it does not keep the player
     * out of online lobbies. Unit and rule INIs are refused. Call off the UI thread.
     */
    static Result installControlBar(Context ctx, Uri uri, String displayName) throws IOException {
        File gameRoot = gameRoot(ctx);
        String name = displayName == null || displayName.trim().isEmpty() ? "ControlBar.zip" : new File(displayName.trim()).getName();
        String lower = name.toLowerCase(Locale.ROOT);
        File staging = new File(ctx.getCacheDir(), "controlbar_import_" + System.currentTimeMillis());
        try {
            if (!staging.mkdirs()) {
                throw new IOException("cannot create " + staging);
            }
            if (lower.endsWith(".zip")) {
                extractZip(ctx, uri, staging);
            } else if (lower.endsWith(".big") || lower.endsWith(".gib") || BigArchive.isRenamedArchiveName(name)) {
                File copied = new File(staging, name);
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) {
                        throw new IOException("cannot open " + name);
                    }
                    copy(in, copied);
                }
                if (!BigArchive.hasSignature(copied)) {
                    throw new ImportException(R.string.controlbar_pick_big, name);
                }
            } else {
                throw new ImportException(R.string.controlbar_pick_big, name);
            }
            String languageDir = languageDir(ctx, gameRoot);
            Result result = new Result();
            List<File> archives = new ArrayList<>();
            List<File> looseFiles = new ArrayList<>();
            List<String> loosePaths = new ArrayList<>();
            walk(staging, "", (file, rel) -> {
                String leafLower = file.getName().toLowerCase(Locale.ROOT);
                if (leafLower.endsWith(".big") || leafLower.endsWith(".gib")
                        || (BigArchive.isRenamedArchiveName(leafLower) && BigArchive.hasSignature(file))) {
                    archives.add(file);
                    return;
                }
                String inArchive = controlBarPath(rel, languageDir);
                if (inArchive == null) {
                    result.skipped++;
                } else if (isGameplayIni(inArchive)) {
                    result.refusedGameplay++;
                } else {
                    looseFiles.add(file);
                    loosePaths.add(inArchive.replace('/', '\\'));
                }
            });
            for (File a : archives) {
                String leaf = a.getName();
                String leafLower = leaf.toLowerCase(Locale.ROOT);
                // GeneralsX @feature Flux Build 05/11/2026 An archive named without ".big" keeps its name.
                String dest = leafLower.endsWith(".big") || leafLower.endsWith(".gib")
                    ? CONTROL_BAR_PREFIX + leaf.substring(0, leaf.length() - 4) + ".big"
                    : CONTROL_BAR_PREFIX + leaf;
                String actual = writeManaged(ctx, gameRoot, dest, a);
                result.files.add(actual);
                result.installed++;
                noteGameplayArchive(result, actual, a);
            }
            if (!looseFiles.isEmpty()) {
                String base = name.replaceAll("(?i)\\.(zip|big|gib)$", "").replaceAll("[^A-Za-z0-9_.-]", "_");
                File packed = new File(staging, "packed.big");
                ModPacker.write(packed, looseFiles, loosePaths);
                result.files.add(writeManaged(ctx, gameRoot, CONTROL_BAR_PREFIX + base + ".big", packed));
                result.installed++;
            }
            if (result.installed == 0) {
                throw new ImportException(R.string.controlbar_import_none, name);
            }
            Log.i(TAG, "control bar " + name + ": " + result.files + ", skipped " + result.skipped
                + ", refused gameplay " + result.refusedGameplay);
            return result;
        } finally {
            deleteRecursively(staging);
        }
    }

    /**
     * GeneralsX @feature Flux Build 05/11/2026 Records an installed archive the engine will count as a
     * gameplay mod (a pack carrying Data\INI\CommandSet.ini or Object\ files, for one; in a '!' pack,
     * CommandButton.ini counts only when its buttons differ from the community patch's in a field the
     * game logic reads; images and labels do not), so the player is
     * told the community patch goes off and online matches need the same pack on every side.
     */
    private static void noteGameplayArchive(Result result, String installedName, File archive) {
        String lower = installedName.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".big") || BigArchive.isRenamedArchiveName(lower)) || !BigArchive.hasSignature(archive)) {
            return;
        }
        try {
            if (BigArchive.isGameplayArchive(archive, installedName, BigArchive.list(archive, true))) {
                result.gameplayArchives.add(new File(installedName).getName());
            }
        } catch (IOException e) {
            Log.w(TAG, "could not list " + archive + ": " + e.getMessage());
        }
    }

    /**
     * Where a control bar package's loose file goes inside the packed archive: its path from the
     * game's own top folder (Art, Data, Window) when it has one, else by kind -- textures to
     * Art/Textures, models to Art/W3D, layouts to Window, INI to Data/INI, string tables to the game
     * language. Null for anything else (readmes, screenshots).
     */
    private static String controlBarPath(String relativePath, String languageDir) {
        List<String> segments = splitPath(relativePath.replace('\\', '/'));
        for (int i = 0; i < segments.size() - 1; i++) {
            String s = segments.get(i).toLowerCase(Locale.ROOT);
            if (s.equals("art") || s.equals("data") || s.equals("window")) {
                return String.join("/", segments.subList(i, segments.size()));
            }
        }
        String leaf = segments.isEmpty() ? "" : segments.get(segments.size() - 1);
        String lower = leaf.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tga") || lower.endsWith(".dds")) return "Art/Textures/" + leaf;
        if (lower.endsWith(".w3d")) return "Art/W3D/" + leaf;
        if (lower.endsWith(".wnd")) return "Window/" + leaf;
        if (lower.endsWith(".ini")) return "Data/INI/" + leaf;
        if (lower.endsWith(".csf")) return "Data/" + languageDir + "/" + leaf;
        return null;
    }

    /** Installs every routable file under root into the game folder. */
    static Result installTree(Context ctx, File gameRoot, File root) throws IOException {
        String languageDir = languageDir(ctx, gameRoot);
        // Keyed by the destination in lower case: two pack files that land on the same game file
        // (Data/English/CommandMap.ini and Data/INI/CommandMap.ini) install once, the later one.
        Map<String, String[]> plan = new LinkedHashMap<>();
        Map<String, File> sources = new LinkedHashMap<>();
        Result result = new Result();
        walk(root, "", (file, rel) -> {
            String dest = route(rel, languageDir);
            if (dest == null && BigArchive.isRenamedArchiveName(file.getName()) && BigArchive.hasSignature(file)) {
                // GeneralsX @feature Flux Build 05/11/2026 Kept under its own name (see the header).
                dest = file.getName();
            }
            if (dest == null) {
                result.skipped++;
            } else if (REFUSED.equals(dest)) {
                result.refusedGameplay++;
            } else {
                String key = dest.toLowerCase(Locale.ROOT);
                if (sources.put(key, file) != null) {
                    Log.w(TAG, "two files of the pack go to " + dest + "; the later one is kept");
                    result.skipped++;
                }
                plan.put(key, new String[] { dest });
            }
        });
        for (Map.Entry<String, String[]> e : plan.entrySet()) {
            File source = sources.get(e.getKey());
            String actual = writeManaged(ctx, gameRoot, e.getValue()[0], source);
            result.files.add(actual);
            result.installed++;
            noteGameplayArchive(result, actual, source);
        }
        Log.i(TAG, "installed " + result.installed + ", skipped " + result.skipped
            + ", refused gameplay " + result.refusedGameplay + " into " + gameRoot);
        return result;
    }

    /** Writes bytes to a game-folder path the same way (backup first). Returns the path used. */
    static String writeManaged(Context ctx, File gameRoot, String relativePath, byte[] data) throws IOException {
        File temp = File.createTempFile("gxhk", ".tmp", ctx.getCacheDir());
        try {
            try (OutputStream out = new FileOutputStream(temp)) {
                out.write(data);
            }
            return writeManaged(ctx, gameRoot, relativePath, temp);
        } finally {
            temp.delete();
        }
    }

    /**
     * Copies source over the game-folder path, after backing up what was there the first time the
     * launcher touches that path. Folder and file names are matched case-insensitively against what
     * already exists, so "data/ini/commandmap.ini" replaces "Data/INI/CommandMap.ini" instead of
     * sitting next to it. Returns the relative path actually written.
     */
    static synchronized String writeManaged(Context ctx, File gameRoot, String relativePath, File source)
            throws IOException {
        String actual = resolveExisting(gameRoot, relativePath);
        File target = new File(gameRoot, actual);
        Map<String, String> manifest = readManifest(ctx, gameRoot);
        if (!manifest.containsKey(actual)) {
            String backup = NO_BACKUP;
            if (target.isFile()) {
                File dir = backupDir(ctx, gameRoot);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("cannot create " + dir);
                }
                int n = manifest.size() + 1;
                while (new File(dir, n + "_" + target.getName()).exists()) {
                    n++;
                }
                backup = n + "_" + target.getName();
                try (InputStream in = new FileInputStream(target)) {
                    copy(in, new File(dir, backup));
                }
            }
            manifest.put(actual, backup);
            writeManifest(ctx, gameRoot, manifest);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        File temp = new File(parent, "." + target.getName() + ".gxtmp");
        try (InputStream in = new FileInputStream(source)) {
            copy(in, temp);
        }
        if (!temp.renameTo(target)) {
            target.delete();
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IOException("cannot replace " + target);
            }
        }
        return actual;
    }

    // ------------------------------------------------------------------ restore

    /**
     * Puts back everything the launcher installed (and the .bak files the previous importer left).
     * Returns how many files were restored or removed.
     */
    static synchronized int restoreAll(Context ctx) throws IOException {
        File gameRoot = gameRoot(ctx);
        Map<String, String> manifest = readManifest(ctx, gameRoot);
        List<String> paths = new ArrayList<>(manifest.keySet());
        int count = 0;
        for (int i = paths.size() - 1; i >= 0; i--) {
            if (restoreEntry(ctx, gameRoot, paths.get(i), manifest.get(paths.get(i)))) {
                count++;
            }
        }
        deleteRecursively(stateDir(ctx, gameRoot));
        count += restoreLegacyBackups(gameRoot);
        return count;
    }

    /**
     * Undoes one path: the original comes back if the launcher replaced one, else the file goes.
     * A file the launcher never installed is moved into the backup folder, not deleted.
     */
    static synchronized boolean restoreOne(Context ctx, String relativePath) throws IOException {
        File gameRoot = gameRoot(ctx);
        String actual = resolveExisting(gameRoot, relativePath);
        Map<String, String> manifest = readManifest(ctx, gameRoot);
        if (manifest.containsKey(actual)) {
            boolean changed = restoreEntry(ctx, gameRoot, actual, manifest.remove(actual));
            writeManifest(ctx, gameRoot, manifest);
            return changed;
        }
        File target = new File(gameRoot, actual);
        if (!target.isFile()) {
            return false;
        }
        File dir = backupDir(ctx, gameRoot);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (InputStream in = new FileInputStream(target)) {
            copy(in, new File(dir, "unmanaged_" + System.currentTimeMillis() + "_" + target.getName()));
        }
        return target.delete();
    }

    private static boolean restoreEntry(Context ctx, File gameRoot, String path, String backup) throws IOException {
        File target = new File(gameRoot, path);
        if (NO_BACKUP.equals(backup)) {
            return target.delete();
        }
        File saved = new File(backupDir(ctx, gameRoot), backup);
        if (!saved.isFile()) {
            Log.w(TAG, "backup of " + path + " is missing; leaving the current file");
            return false;
        }
        try (InputStream in = new FileInputStream(saved)) {
            copy(in, target);
        }
        return true;
    }

    /** The previous importer's backups: Data/INI/<name>.bak and <game folder>/<name>.big.bak. */
    private static int restoreLegacyBackups(File gameRoot) {
        int count = 0;
        File iniDir = new File(gameRoot, resolveExisting(gameRoot, "Data/INI"));
        for (File dir : new File[] { iniDir, gameRoot }) {
            File[] baks = dir.listFiles((d, n) -> n.endsWith(".bak")
                && (d.equals(iniDir) || n.toLowerCase(Locale.ROOT).endsWith(".big.bak")));
            if (baks == null) {
                continue;
            }
            for (File bak : baks) {
                String originalName = bak.getName().substring(0, bak.getName().length() - 4);
                // Never bring back a unit or rule file as a loose override: that would turn the
                // install into a mod (and change the INI checksum) behind the player's back.
                if (dir.equals(iniDir) && isGameplayIni("Data/INI/" + originalName)) {
                    continue;
                }
                File original = new File(dir, originalName);
                original.delete();
                if (bak.renameTo(original)) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ state

    static File gameRoot(Context ctx) throws ImportException {
        String path = SetupActivity.getSavedGamePath(ctx);
        File root = path == null || path.trim().isEmpty() ? null : new File(path);
        if (root == null || !root.isDirectory()) {
            throw new ImportException(R.string.setup_langpack_no_folder, String.valueOf(path));
        }
        return root;
    }

    /**
     * The folder name of the game's text language under Data/ -- the token the engine reads
     * (game_language.cfg, else "english"), spelled as the game folder already spells it.
     */
    static String languageDir(Context ctx, File gameRoot) {
        String token = "english";
        File cfg = new File(ctx.getFilesDir(), "game_language.cfg");
        if (cfg.isFile()) {
            try (BufferedReader r = new BufferedReader(new FileReader(cfg))) {
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    token = line.trim();
                }
            } catch (IOException e) {
                Log.w(TAG, "game_language.cfg unreadable; using english", e);
            }
        }
        String existing = resolveExisting(gameRoot, "Data/" + token);
        String leaf = existing.substring(existing.lastIndexOf('/') + 1);
        if (new File(gameRoot, existing).isDirectory()) {
            return leaf;
        }
        return Character.toUpperCase(token.charAt(0)) + token.substring(1);
    }

    /** The relative path with each part spelled as an existing file or folder spells it. */
    static String resolveExisting(File root, String relativePath) {
        StringBuilder out = new StringBuilder();
        File dir = root;
        for (String part : relativePath.replace('\\', '/').split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            String name = part;
            String[] children = dir != null ? dir.list() : null;
            if (children != null) {
                for (String child : children) {
                    if (child.equalsIgnoreCase(part)) {
                        name = child;
                        break;
                    }
                }
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(name);
            dir = dir != null ? new File(dir, name) : null;
        }
        return out.toString();
    }

    private static File stateDir(Context ctx, File gameRoot) {
        String game = SetupActivity.isGeneralsSelected(ctx) ? "generals" : "zh";
        return new File(new File(ctx.getFilesDir(), "hotkey_backups"),
            game + "_" + Integer.toHexString(gameRoot.getAbsolutePath().hashCode()));
    }

    private static File backupDir(Context ctx, File gameRoot) {
        return new File(stateDir(ctx, gameRoot), "files");
    }

    /**
     * GeneralsX @feature Flux Build 10/11/2026 What the Hotkeys & Control Bar card shows: [control bar
     * names, hotkey pack names], each null when none. From this installer's own record (what Restore
     * defaults undoes), plus archives copied into the game folder by hand.
     */
    static String[] installedSummary(Context ctx, File gameRoot) {
        java.util.LinkedHashSet<String> bars = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (gameRoot != null) {
            try {
                for (String path : readManifest(ctx, gameRoot).keySet()) {
                    String leaf = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
                    addHudName(leaf, bars, keys);
                }
            } catch (IOException e) {
                Log.w(TAG, "could not read the install record", e);
            }
            String[] names = gameRoot.list();
            if (names != null) {
                for (String n : names) {
                    String lower = n.toLowerCase(java.util.Locale.ROOT);
                    boolean archive = lower.endsWith(".big") || BigArchive.isRenamedArchiveName(lower);
                    if (!archive) continue; // never .bak backups
                    if (lower.startsWith(CONTROL_BAR_PREFIX.toLowerCase(java.util.Locale.ROOT))
                            || (lower.startsWith("!") && lower.contains("hotkey"))) {
                        if (new File(gameRoot, n).isFile()) addHudName(n, bars, keys);
                    }
                }
            }
        }
        return new String[] {
            bars.isEmpty() ? null : android.text.TextUtils.join(", ", bars),
            keys.isEmpty() ? null : android.text.TextUtils.join(", ", keys)
        };
    }

    /** File names (leaves) this installer recorded for the folder; empty when none (HudBundle). */
    static java.util.Set<String> recordedLeaves(Context ctx, File gameRoot) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        try {
            for (String path : readManifest(ctx, gameRoot).keySet()) {
                out.add(path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1));
            }
        } catch (IOException e) {
            Log.w(TAG, "could not read the install record", e);
        }
        return out;
    }

    private static void addHudName(String leaf, java.util.Set<String> bars, java.util.Set<String> keys) {
        String pretty = leaf.replaceFirst("(?i)\\.big$", "").replaceFirst("(?i)\\.zh$", "").replaceFirst("(?i)zh$", "");
        boolean bar = leaf.toLowerCase(java.util.Locale.ROOT).startsWith(CONTROL_BAR_PREFIX.toLowerCase(java.util.Locale.ROOT));
        if (bar) {
            // "00CB_340_ControlBarPro1080ZH.big" and "...ProZH.big" are one pack: "340 ControlBarPro".
            pretty = pretty.substring(CONTROL_BAR_PREFIX.length()).replaceFirst("^!+", "").replaceFirst("\\d{3,4}$", "");
        } else {
            pretty = pretty.replaceFirst("^!+", "");
        }
        pretty = pretty.replace('_', ' ').trim();
        if (pretty.isEmpty()) pretty = leaf;
        (bar ? bars : keys).add(pretty);
    }

    private static Map<String, String> readManifest(Context ctx, File gameRoot) throws IOException {
        Map<String, String> manifest = new LinkedHashMap<>();
        File file = new File(stateDir(ctx, gameRoot), "manifest.txt");
        if (!file.isFile()) {
            return manifest;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    manifest.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return manifest;
    }

    private static void writeManifest(Context ctx, File gameRoot, Map<String, String> manifest) throws IOException {
        File dir = stateDir(ctx, gameRoot);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        File temp = new File(dir, "manifest.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                w.write(e.getKey() + "\t" + e.getValue() + "\n");
            }
        }
        File file = new File(dir, "manifest.txt");
        if (!temp.renameTo(file)) {
            file.delete();
            if (!temp.renameTo(file)) {
                throw new IOException("cannot write " + file);
            }
        }
    }

    // ------------------------------------------------------------------ files

    private interface FileVisitor {
        void visit(File file, String relativePath) throws IOException;
    }

    private static void walk(File dir, String prefix, FileVisitor visitor) {
        try {
            walkChecked(dir, prefix, visitor, 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void walkChecked(File dir, String prefix, FileVisitor visitor, int depth) throws IOException {
        File[] children = dir.listFiles();
        if (children == null || depth > 16) {
            return;
        }
        for (File c : children) {
            if (java.nio.file.Files.isSymbolicLink(c.toPath())) {
                continue;
            }
            String rel = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
            if (c.isDirectory()) {
                walkChecked(c, rel, visitor, depth + 1);
            } else {
                visitor.visit(c, rel);
            }
        }
    }

    private static void extractZip(Context ctx, Uri uri, File staging) throws IOException {
        String stagingPath = staging.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (InputStream raw = ctx.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("cannot open the archive");
            }
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry entry;
                byte[] buf = new byte[64 * 1024];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_PACK_ENTRIES) {
                        throw new IOException("too many files in the archive");
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    File out = new File(staging, entry.getName().replace('\\', '/')).getCanonicalFile();
                    if (!out.getPath().startsWith(stagingPath)) {
                        throw new IOException("archive entry escapes its folder: " + entry.getName());
                    }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create " + parent);
                    }
                    try (OutputStream o = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_PACK_BYTES) {
                                throw new IOException("archive too large for a hotkey pack");
                            }
                            o.write(buf, 0, n);
                        }
                    }
                }
            }
        }
    }

    private static void copy(InputStream in, File target) throws IOException {
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath()) ? f.listFiles() : null;
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        if (!f.delete()) {
            Log.w(TAG, "could not delete " + f);
        }
    }
}
