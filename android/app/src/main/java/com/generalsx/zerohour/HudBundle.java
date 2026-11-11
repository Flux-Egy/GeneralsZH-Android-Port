package com.generalsx.zerohour;

// GeneralsX @feature Flux Build 10/11/2026 Bundled HUD packs. 12174 dropped the hotkey packs and 12180 dropped
// ControlBar Pro (owner): the default HUD is the game's own, and players import their own packs. With
// nothing bundled this class only removes the files of ours that 12173-12179 installed (stamped names);
// a player's own or imported files are never touched. The machinery stays for a future bundle. They ship in assets/hud/<name>.hud with assets/hud/manifest.txt
// (name, size, SHA-256), staged by app/build.gradle stageHudAssets after the client-only gate
// (scripts/build/android/check-hud-bundle.sh: the launcher's data-mod rule against the community patch).
//
// They are the Zero Hour default, each part on its own (CONTROL_BAR = the 00CB_ archives, HOTKEYS = the
// '!' archives), unless the player turned the part off on the Hotkeys & Control Bar card or has a pack of
// that kind of their own (an archive imported through the card, or one copied in by hand). Theirs is
// never overwritten or removed; ours is taken out when theirs arrives, so two packs never load together.
//
// Ours: a file this class wrote into this folder (stamp: folder|name -> size:mtime:sha) whose size still
// matches and whose time matches, or whose content hashes to a bundled version (hashed only when the size
// matches; the stamp is then renewed). A file the importer recorded is never ours.
//
// Online safety at run time: a '!' archive with CommandButton INI is client-only only while it equals the
// community patch's buttons (BigArchive.isGameplayArchive, the engine's rule). The device's own patch can
// differ from the one the build was gated against (or be absent), so every '!' item is checked against it
// before it goes in and again whenever the patch changes; a data-mod verdict keeps it out (INCOMPATIBLE).
//
// Copies go to a ".tmp" name (never mounted: not .big, has an extension, not .zh), are verified by size and
// SHA-256, synced, then renamed over the target (atomic). All work holds HotkeyPackInstaller's lock.

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class HudBundle {

    private static final String TAG = "HudBundle";
    static final int CONTROL_BAR = 0;
    static final int HOTKEYS = 1;

    private static final String PREF_OFF = "hud_bundle_off_";        // + part
    private static final String PREF_STAMP = "hud_bundle_stamp|";    // + folder|name -> size:mtime:sha
    private static final String PREF_VERDICT = "hud_bundle_verdict|"; // + name|sha|patchKey -> ok/datamod
    private static final String PREF_FAILED = "hud_bundle_failed";   // bundle key of a failed automatic run
    private static final String TMP_NAME = "fluxhud-copy.tmp";
    private static final long SPACE_MARGIN = 64L << 20;

    private static volatile boolean sBusy;
    // Review: counted from the moment a run is scheduled (SetupActivity.runHudBundle), so Play cannot slip
    // in between the guard and the copy taking the lock.
    private static final java.util.concurrent.atomic.AtomicInteger sPending = new java.util.concurrent.atomic.AtomicInteger();

    static void beginPending() { sPending.incrementAndGet(); }
    static void endPending() { sPending.decrementAndGet(); }

    /** No space left for a copy; the card says so in the player's language. */
    static final class NoSpaceException extends IOException {
        NoSpaceException() { super("no space"); }
    }

    /** One bundled archive. */
    static final class Item {
        final String name;
        final long size;
        final String sha;
        final int part;

        Item(String name, long size, String sha) {
            this.name = name;
            this.size = size;
            this.sha = sha;
            this.part = isBarName(name) ? CONTROL_BAR : HOTKEYS;
        }
    }

    /** What the card shows for one part. */
    enum State { NOT_BUNDLED, NO_FOLDER, INSTALLED, NOT_INSTALLED, OFF, PLAYERS_OWN, INCOMPATIBLE }

    private static List<Item> sItems;

    private HudBundle() {}

    private static boolean hasItems(Context ctx, int part) {
        for (Item it : items(ctx)) if (it.part == part) return true;
        return false;
    }

    static boolean isBusy() {
        return sBusy || sPending.get() > 0;
    }

    private static boolean isBarName(String name) {
        return name.toLowerCase(Locale.ROOT).startsWith(HotkeyPackInstaller.CONTROL_BAR_PREFIX.toLowerCase(Locale.ROOT));
    }

    /** An archive of this part's kind (as the engine mounts it): 00CB_ for the bar, "!...hotkey..." for keys. */
    private static boolean isKind(String name, int part) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".big") || BigArchive.isRenamedArchiveName(lower))) {
            return false;
        }
        return part == CONTROL_BAR ? isBarName(name) : (lower.startsWith("!") && lower.contains("hotkey"));
    }

    static synchronized List<Item> items(Context ctx) {
        if (sItems != null) {
            return sItems;
        }
        List<Item> items = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ctx.getAssets().open("hud/manifest.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t");
                if (f.length == 3 && !f[0].isEmpty() && !f[0].startsWith("#")) {
                    items.add(new Item(f[0], Long.parseLong(f[1]), f[2].toLowerCase(Locale.ROOT)));
                }
            }
        } catch (IOException | NumberFormatException e) {
            // No bundled HUD in this build (a clone without references/hud/).
        }
        sItems = items;
        return items;
    }

    /** The bundle and the folder a failed automatic run was for (a new folder or bundle retries). */
    private static String failKey(Context ctx, File root) {
        StringBuilder b = new StringBuilder();
        for (Item it : items(ctx)) b.append(it.sha).append(',');
        return b.append('|').append(root.getAbsolutePath()).toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The Zero Hour folder, or null. The packs are Zero Hour packs; Generals is never touched. */
    private static File zhRoot(Context ctx) {
        String path = SetupActivity.getSavedGamePath(ctx, SetupActivity.GAME_ZERO_HOUR);
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File root = new File(path);
        return root.isDirectory() ? root : null;
    }

    private static String stampKey(File f) {
        String root;
        try {
            root = f.getParentFile().getCanonicalPath();
        } catch (IOException e) {
            root = f.getParentFile().getAbsolutePath();
        }
        return PREF_STAMP + root + "|" + f.getName();
    }

    static boolean isOff(Context ctx, int part) {
        return prefs(ctx).getBoolean(PREF_OFF + part, false);
    }

    private static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    /** The bundled SHA-256 of an item name in this build, or null. */
    private static String bundledSha(Context ctx, String name) {
        for (Item it : items(ctx)) if (it.name.equals(name)) return it.sha;
        return null;
    }

    /**
     * Whether this file is ours (see the header). Returns its content sha when ours, else null.
     * May hash the file (only when its size is a bundled size and its time changed).
     */
    private static String ours(Context ctx, File f, Set<String> recorded) {
        return ours(ctx, f, recorded, true);
    }

    /** {@code mayHash} false: the stamp alone (the UI thread never hashes 38 MB). */
    private static String ours(Context ctx, File f, Set<String> recorded, boolean mayHash) {
        if (!f.isFile() || recorded.contains(f.getName())) {
            return null;
        }
        String key = stampKey(f);
        String stamp = prefs(ctx).getString(key, null);
        String[] s = stamp != null ? stamp.split(":") : new String[0];
        if (s.length == 3 && Long.toString(f.length()).equals(s[0])) {
            if (Long.toString(f.lastModified()).equals(s[1])) {
                return s[2];
            }
        }
        // No stamp (a lost commit), a new time (FAT cards, a copy back) or another folder: content decides.
        if (!mayHash) {
            return null;
        }
        String bundled = bundledSha(ctx, f.getName());
        boolean sizeFits = false;
        for (Item it : items(ctx)) if (it.name.equals(f.getName()) && it.size == f.length()) sizeFits = true;
        if (s.length == 3 && Long.toString(f.length()).equals(s[0])) sizeFits = true;
        if (!sizeFits || (bundled == null && s.length != 3)) {
            return null;
        }
        try {
            String sha = sha256(f);
            if (sha.equals(bundled) || (s.length == 3 && sha.equals(s[2]))) {
                prefs(ctx).edit().putString(key, f.length() + ":" + f.lastModified() + ":" + sha).commit();
                return sha;
            }
        } catch (IOException e) {
            Log.w(TAG, "could not hash " + f, e);
        }
        return null;
    }

    /** The archives the importer recorded for this folder (never ours). */
    private static Set<String> recorded(Context ctx, File root) {
        return HotkeyPackInstaller.recordedLeaves(ctx, root);
    }

    /** Whether the folder holds a pack of this kind that is the player's own. */
    private static boolean playersOwn(Context ctx, File root, int part, Set<String> recorded) {
        return playersOwn(ctx, root, part, recorded, true);
    }

    private static boolean playersOwn(Context ctx, File root, int part, Set<String> recorded, boolean mayHash) {
        for (String leaf : recorded) {
            if (isKind(leaf, part)) return true; // imported through the card: Restore defaults owns it
        }
        String[] names = root.list();
        if (names == null) return false;
        for (String n : names) {
            if (isKind(n, part) && ours(ctx, new File(root, n), recorded, mayHash) == null) return true;
        }
        return false;
    }

    /** The community patch as this device has it, as a cache key ("none" when absent). */
    private static String patchKey() {
        File p = DataPackInstaller.communityPatchFile();
        // The launcher version too: a stricter rule in a later APK must not reuse an old "ok".
        return BuildConfig.VERSION_CODE + ":" + (p.isFile() ? (p.length() + ":" + p.lastModified()) : "none");
    }

    private static boolean knownDataMod(Context ctx, Item it) {
        return !isBarName(it.name)
            && "datamod".equals(prefs(ctx).getString(PREF_VERDICT + it.name + "|" + it.sha + "|" + patchKey(), null));
    }

    /** Whether a '!' archive is client-only against this device's patch (cached per item sha and patch). */
    private static boolean clientOnly(Context ctx, File archive, String name, String sha) {
        if (isBarName(name)) {
            return true; // 00CB_ archives are not '!' archives; the gate holds them to unit/rule INI only
        }
        String key = PREF_VERDICT + name + "|" + sha + "|" + patchKey();
        String cached = prefs(ctx).getString(key, null);
        if (cached != null) {
            return "ok".equals(cached);
        }
        boolean ok;
        try {
            ok = !(BigArchive.isRenamedArchiveName(name) && !BigArchive.hasSignature(archive))
                && !BigArchive.isGameplayArchive(archive, name, BigArchive.list(archive, true));
        } catch (IOException e) {
            Log.w(TAG, "could not read " + archive, e);
            return false; // fail closed, but a transient read error is not remembered
        }
        prefs(ctx).edit().putString(key, ok ? "ok" : "datamod").commit();
        if (!ok) Log.w(TAG, name + " would count as a data mod against this device's community patch; kept out");
        return ok;
    }

    static State state(Context ctx, int part) {
        List<Item> mine = new ArrayList<>();
        for (Item it : items(ctx)) if (it.part == part) mine.add(it);
        if (mine.isEmpty()) return State.NOT_BUNDLED;
        File root = zhRoot(ctx);
        if (root == null) return State.NO_FOLDER;
        Set<String> rec = recorded(ctx, root);
        if (playersOwn(ctx, root, part, rec, false)) return State.PLAYERS_OWN;
        boolean all = true;
        for (Item it : mine) {
            String sha = ours(ctx, new File(root, it.name), rec, false);
            if (!it.sha.equals(sha)) all = false;
        }
        if (all) return State.INSTALLED;
        if (isOff(ctx, part)) return State.OFF;
        for (Item it : mine) {
            String v = prefs(ctx).getString(PREF_VERDICT + it.name + "|" + it.sha + "|" + patchKey(), null);
            if ("datamod".equals(v)) return State.INCOMPATIBLE;
        }
        return State.NOT_INSTALLED;
    }

    /**
     * Brings the folder in line: each part that is on and not the player's own gets the current bundled
     * files; a part that became the player's own, or a '!' file that would be a data mod against this
     * device's patch, loses ours. Off the UI thread. Returns the number of files written.
     * {@code automatic}: a launcher resume -- skipped after a failure with the same bundle, and quietly
     * when space is short.
     */
    static int ensure(Context ctx, boolean automatic) throws IOException {
        synchronized (HotkeyPackInstaller.class) {
            File root = zhRoot(ctx);
            if (root == null) return 0;
            if (DataScriptsManager.isGameRunning(ctx)) return 0; // re-checked under the lock
            // Review: parts no longer bundled are cleaned first, so a failing copy of another part (or a
            // remembered failure) can never leave 12173's files behind; this also frees space.
            Set<String> recBefore = recorded(ctx, root);
            for (int p = CONTROL_BAR; p <= HOTKEYS; p++) {
                if (!hasItems(ctx, p)) removeOurs(ctx, root, p, recBefore);
            }
            // 12180: nothing is bundled any more; the cleanup above is all that runs then.
            if (items(ctx).isEmpty()) return 0;
            if (automatic && failKey(ctx, root).equals(prefs(ctx).getString(PREF_FAILED, null))) return 0;
            sBusy = true;
            try {
                File tmp = new File(root, TMP_NAME);
                if (tmp.exists()) tmp.delete();
                int written = 0;
                Set<String> rec = recorded(ctx, root);
                for (int part = CONTROL_BAR; part <= HOTKEYS; part++) {
                    if (!hasItems(ctx, part)) continue; // cleaned above
                    if (playersOwn(ctx, root, part, rec)) {
                        removeOurs(ctx, root, part, rec); // theirs wins; never two packs at once
                        continue;
                    }
                    if (isOff(ctx, part)) continue;
                    removeDropped(ctx, root, part, rec); // ours from an older bundle that this one renamed
                    for (Item it : items(ctx)) {
                        if (it.part != part) continue;
                        File target = new File(root, it.name);
                        String have = ours(ctx, target, rec);
                        if (target.exists() && have == null) continue; // not ours: never touched
                        if (it.sha.equals(have)) {
                            // Current. Re-check a '!' file whenever the device's patch changed.
                            if (!clientOnly(ctx, target, it.name, it.sha)) remove(ctx, target);
                            continue;
                        }
                        if (knownDataMod(ctx, it)) continue; // kept out until the patch or the bundle changes
                        if (copy(ctx, root, it, target, rec)) written++;
                    }
                }
                prefs(ctx).edit().remove(PREF_FAILED).apply();
                return written;
            } catch (NoSpaceException e) {
                if (automatic) return 0; // quietly; the button says so
                throw e;
            } catch (IOException e) {
                prefs(ctx).edit().putString(PREF_FAILED, failKey(ctx, root)).apply();
                throw e;
            } catch (RuntimeException e) {
                prefs(ctx).edit().putString(PREF_FAILED, failKey(ctx, root)).apply();
                throw new IOException(e.toString(), e);
            } finally {
                sBusy = false;
            }
        }
    }

    /** Writes one item; false when it was kept out (a data mod here, or the target stopped being ours). */
    private static boolean copy(Context ctx, File root, Item it, File target, Set<String> rec) throws IOException {
        if (root.getUsableSpace() < it.size + SPACE_MARGIN) {
            throw new NoSpaceException();
        }
        File tmp = new File(root, TMP_NAME);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        long size = 0;
        try (InputStream in = ctx.getAssets().open("hud/" + it.name + ".hud");
             FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                size += n;
            }
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        if (size != it.size || !hex(md.digest()).equals(it.sha)) {
            tmp.delete();
            throw new IOException("verification failed for " + it.name);
        }
        if (!clientOnly(ctx, tmp, it.name, it.sha)) {
            tmp.delete();
            return false; // would block online play with this device's patch
        }
        // Review: the copy took seconds -- the target may have appeared (or changed hands) meanwhile.
        if (target.exists() && ours(ctx, target, recorded(ctx, root)) == null) {
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(target)) {   // replaces our older copy atomically
            if (!(target.exists() && target.delete() && tmp.renameTo(target))) {
                tmp.delete();
                throw new IOException("cannot rename to " + target);
            }
        }
        prefs(ctx).edit().putString(stampKey(target),
            target.length() + ":" + target.lastModified() + ":" + it.sha).commit();
        Log.i(TAG, "installed " + target);
        return true;
    }

    private static void remove(Context ctx, File f) {
        if (f.delete()) {
            prefs(ctx).edit().remove(stampKey(f)).commit();
            Log.i(TAG, "removed " + f);
        }
    }

    /** Deletes our files of a part in this folder, including names a newer bundle no longer has. */
    private static void removeOurs(Context ctx, File root, int part, Set<String> rec) {
        removeOurs(ctx, root, part, rec, false);
    }

    private static void removeDropped(Context ctx, File root, int part, Set<String> rec) {
        removeOurs(ctx, root, part, rec, true);
    }

    private static void removeOurs(Context ctx, File root, int part, Set<String> rec, boolean droppedOnly) {
        String prefix;
        try {
            prefix = PREF_STAMP + root.getCanonicalPath() + "|";
        } catch (IOException e) {
            prefix = PREF_STAMP + root.getAbsolutePath() + "|";
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        Set<String> current = new java.util.HashSet<>();
        for (Item it : items(ctx)) if (it.part == part) current.add(it.name);
        if (!droppedOnly) names.addAll(current);
        for (Map.Entry<String, ?> e : prefs(ctx).getAll().entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                String n = e.getKey().substring(prefix.length());
                if ((part == CONTROL_BAR) == isBarName(n) && !(droppedOnly && current.contains(n))) names.add(n);
            }
        }
        for (String n : names) {
            File f = new File(root, n);
            if (ours(ctx, f, rec) != null) remove(ctx, f);
        }
    }

    /** The card's "use the standard ..." button: removes our files of this part and keeps it off. */
    static void turnOff(Context ctx, int part) {
        synchronized (HotkeyPackInstaller.class) {
            prefs(ctx).edit().putBoolean(PREF_OFF + part, true).commit();
            File root = zhRoot(ctx);
            if (root != null) removeOurs(ctx, root, part, recorded(ctx, root));
        }
    }

    /** The card's "use the Flux default" button: on again (and a failed run may retry); then ensure(). */
    static void turnOn(Context ctx, int part) {
        prefs(ctx).edit().putBoolean(PREF_OFF + part, false).remove(PREF_FAILED).commit();
    }
}
