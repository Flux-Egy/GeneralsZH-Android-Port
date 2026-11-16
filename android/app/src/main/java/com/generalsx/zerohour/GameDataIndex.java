// GeneralsX @feature Flux Build 03/11/2026 The game's data files as the engine finds them, read-only,
// for the launcher's hotkey editors.
//
// Lookup order, highest first, matching the engine (ArchiveFileSystem.cpp, StdBIGFileSystem.cpp):
//   1. a loose file in the game folder (the engine's file system opens local files first);
//   2. the enabled mods of the launcher's Mods screen, highest priority first; inside one mod the
//      pack of its loose files, then its archives with the first file name winning;
//   3. the game folder's archives and those one folder below it (never mods/), first path winning.
// Only entries under Data\ are indexed: the editors read INI and string tables, nothing else.
package com.generalsx.zerohour;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

final class GameDataIndex {
    private static final String TAG = "GameDataIndex";

    private static final class Layer {
        final File archive;
        final boolean mod;
        final Map<String, BigArchive.Entry> entries = new LinkedHashMap<>();

        Layer(File archive, boolean mod) {
            this.archive = archive;
            this.mod = mod;
        }
    }

    private final File gameRoot;
    private final List<Layer> layers = new ArrayList<>();

    GameDataIndex(File gameRoot, List<File> enabledModsHighestFirst) {
        this.gameRoot = gameRoot;
        for (File mod : enabledModsHighestFirst) {
            List<File> archives = new ArrayList<>();
            collectBig(mod, archives, 0, 8);
            File loosePack = null;
            for (File f : archives) {
                if (f.getName().toLowerCase(Locale.ROOT).startsWith("!!loose_files")) {
                    loosePack = f;
                }
            }
            if (loosePack != null) {
                archives.remove(loosePack);
                addLayer(loosePack, true);
            }
            archives.sort((a, b) -> {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getPath().compareToIgnoreCase(b.getPath());
            });
            for (File f : archives) {
                addLayer(f, true);
            }
        }
        List<String> rootArchives = new ArrayList<>();
        File[] top = gameRoot.listFiles();
        if (top != null) {
            for (File f : top) {
                if (f.isFile() && isBig(f)) {
                    rootArchives.add(f.getName());
                } else if (f.isDirectory() && !f.getName().equalsIgnoreCase("mods")) {
                    File[] sub = f.listFiles();
                    if (sub != null) {
                        for (File s : sub) {
                            // GeneralsX @feature Flux Build 05/11/2026 One folder down the engine mounts
                            // *.big only (renamed archives come from the game folder itself).
                            if (s.isFile() && s.getName().toLowerCase(Locale.ROOT).endsWith(".big")) {
                                rootArchives.add(f.getName() + "/" + s.getName());
                            }
                        }
                    }
                }
            }
        }
        rootArchives.sort(String::compareToIgnoreCase);
        for (String rel : rootArchives) {
            // A '!' archive in the game folder is a mod's, as GameText.cpp gxIsModArchiveName counts it.
            File archive = new File(gameRoot, rel);
            addLayer(archive, archive.getName().startsWith("!"));
        }
    }

    private static boolean isBig(File f) {
        // GeneralsX @feature Flux Build 05/11/2026 Every archive the engine mounts, named .big or not.
        return BigArchive.isMountedArchive(f);
    }

    private static void collectBig(File dir, List<File> out, int depth, int maxDepth) {
        File[] children = dir.listFiles();
        if (children == null || depth > maxDepth) {
            return;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                collectBig(c, out, depth + 1, maxDepth);
            } else if (isBig(c)) {
                out.add(c);
            }
        }
    }

    private void addLayer(File archive, boolean mod) {
        try {
            Layer layer = new Layer(archive, mod);
            for (BigArchive.Entry e : BigArchive.list(archive)) {
                String key = key(e.path);
                if (key.startsWith("data\\") && !layer.entries.containsKey(key)) {
                    layer.entries.put(key, e);
                }
            }
            if (!layer.entries.isEmpty()) {
                layers.add(layer);
            }
        } catch (IOException e) {
            Log.w(TAG, "skipping unreadable archive " + archive, e);
        }
    }

    static String key(String path) {
        return path.replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    /** Where read() found a path: "loose" or the archive's file name; null if nowhere. */
    String sourceOf(String path) {
        if (looseFile(path) != null) {
            return "loose";
        }
        Layer l = layerOf(key(path));
        return l == null ? null : l.archive.getName();
    }

    /** The file's contents as the engine would read them, or null. */
    byte[] read(String path) throws IOException {
        File loose = looseFile(path);
        if (loose != null) {
            return Files.readAllBytes(loose.toPath());
        }
        return readFromArchives(path);
    }

    /** The copy in the archives only, ignoring a loose file (what the game shows without it). */
    byte[] readFromArchives(String path) throws IOException {
        String k = key(path);
        Layer l = layerOf(k);
        return l == null ? null : BigArchive.read(l.archive, l.entries.get(k));
    }

    private Layer layerOf(String key) {
        for (Layer l : layers) {
            if (l.entries.containsKey(key)) {
                return l;
            }
        }
        return null;
    }

    File looseFile(String path) {
        File f = new File(gameRoot, HotkeyPackInstaller.resolveExisting(gameRoot, path.replace('\\', '/')));
        return f.isFile() ? f : null;
    }

    /**
     * The string tables mods keep elsewhere than Data\<language>\generals.csf (Shockwave's
     * data\generals.str), highest priority first: what GameText.cpp joins to the English tables as
     * mod tables. Each element is { archive name, file contents }.
     */
    List<Object[]> modStringFiles(String mainTable) throws IOException {
        String skip = key(mainTable);
        List<Object[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Layer l : layers) {
            if (!l.mod) {
                continue;
            }
            for (Map.Entry<String, BigArchive.Entry> e : l.entries.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("data\\maps\\") || k.equals(skip) || !seen.add(k)
                        || !(k.endsWith("\\generals.str") || k.endsWith("\\generals.csf"))) {
                    continue;
                }
                out.add(new Object[] { l.archive.getName() + ":" + k, BigArchive.read(l.archive, e.getValue()) });
            }
        }
        return out;
    }

    /** Every path (lower case, backslashes) under a directory prefix, from all archives and loose files. */
    List<String> list(String directoryPrefix, String extension) {
        String prefix = key(directoryPrefix);
        String ext = extension.toLowerCase(Locale.ROOT);
        TreeSet<String> out = new TreeSet<>();
        for (Layer l : layers) {
            for (String k : l.entries.keySet()) {
                if (k.startsWith(prefix) && k.endsWith(ext)) {
                    out.add(k);
                }
            }
        }
        File dir = new File(gameRoot, HotkeyPackInstaller.resolveExisting(gameRoot, directoryPrefix.replace('\\', '/')));
        addLoose(dir, prefix, ext, out, 0);
        return new ArrayList<>(out);
    }

    private static void addLoose(File dir, String prefix, String ext, TreeSet<String> out, int depth) {
        File[] children = dir.listFiles();
        if (children == null || depth > 8) {
            return;
        }
        for (File c : children) {
            String k = prefix + c.getName().toLowerCase(Locale.ROOT);
            if (c.isDirectory()) {
                addLoose(c, k + "\\", ext, out, depth + 1);
            } else if (k.endsWith(ext)) {
                out.add(k);
            }
        }
    }
}
