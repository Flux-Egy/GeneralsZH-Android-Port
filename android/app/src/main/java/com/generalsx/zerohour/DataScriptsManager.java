/*
**  Command & Conquer Generals Zero Hour(tm)
**  Copyright 2025 Electronic Arts Inc.
**
**  This program is free software: you can redistribute it and/or modify
**  it under the terms of the GNU General Public License as published by
**  the Free Software Foundation, either version 3 of the License, or
**  (at your option) any later version.
*/

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * GeneralsX @feature Flux Build 08/11/2026 Safe DataScripts management (Mods screen toggle).
 *
 * A mod that ships Data/Scripts normally cannot win: the engine asks the local file system
 * first, and the game's own loose Data/Scripts answers before any mounted BIG. The switch lets
 * the launcher put the mod's scripts in place for the duration of one game:
 *
 *   Data/Scripts  ->  Data/scriptsbackup   (one atomic rename, same file system)
 *   new Data/Scripts = a copy of the backup, then the enabled mods' scripts overlaid
 *
 * On return from the game the new folder is removed and the backup renamed back. A session
 * marker and a marker inside the backup make a crash recoverable on the next launcher start:
 * if scriptsbackup is found, it is restored before a game can run. The backup is never deleted
 * until its rename back succeeds.
 *
 * Only the scripts folder is touched. The mod's loose Data/Scripts and any data/scripts entry
 * inside its BIGs are overlaid, lowest-priority mod first, so the top mod wins exactly as it
 * does for archives. The whole operation is skipped when the game has no loose Data/Scripts:
 * with no loose file shadowing them, the mounted mod archives already win.
 */
final class DataScriptsManager {
    private static final String TAG = "DataScripts";

    static final String PREF_ENABLED = "gx_datascripts_management";
    private static final String PREF_SESSION_ACTIVE = "gx_datascripts_session_active";
    private static final String PREF_SESSION_GAME = "gx_datascripts_session_game";
    private static final String PREF_SESSION_STARTED = "gx_datascripts_session_started";

    private static final String BACKUP_NAME = "scriptsbackup";
    private static final String MARKER_NAME = ".generalsx_datascripts";
    private static final String RESTORED_NAME = ".generalsx_datascripts_restored"; // GeneralsX 09/11/2026
    private static final long MIN_FREE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_BIG_SCRIPT_BYTES = 16L * 1024L * 1024L;

    private DataScriptsManager() {
    }

    // ------------------------------------------------------------------ preference

    static boolean isEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return prefs(ctx).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context ctx, boolean enabled) {
        if (ctx == null) {
            return;
        }
        prefs(ctx).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    // ------------------------------------------------------------------ detection

    /** True when the mod folder ships Data/Scripts loose or inside one of its BIGs. */
    static boolean modHasScripts(File modDir) {
        if (modDir == null || !modDir.isDirectory()) {
            return false;
        }
        if (findScriptsRoot(modDir, 0) != null) {
            return true;
        }
        List<File> bigs = ModAnalysis.bigFiles(modDir);
        if (bigs != null) {
            for (File big : bigs) {
                if (big == null || !big.isFile()) {
                    continue;
                }
                List<String> table = ModAnalysis.table(big);
                if (table != null) {
                    for (String path : table) {
                        if (path != null && path.startsWith("data\\scripts\\") && !path.endsWith("\\")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean containsScriptFiles(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 4 || Files.isSymbolicLink(dir.toPath())) {
            return false;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (child.isDirectory()) {
                if (containsScriptFiles(child, depth + 1)) {
                    return true;
                }
            } else if (child.getName().toLowerCase(Locale.US).endsWith(".scb")) {
                return true;
            }
        }
        return false;
    }

    private static File findScriptsRoot(File dir, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 3 || Files.isSymbolicLink(dir.toPath())) {
            return null;
        }
        if (dir.getName().equalsIgnoreCase("data")) {
            File scripts = findChild(dir, "Scripts");
            if (scripts != null && scripts.isDirectory()) {
                return scripts;
            }
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        Arrays.sort(children, (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : children) {
            if (child == null || !child.isDirectory() || child.getName().startsWith(".")
                    || child.getName().equalsIgnoreCase("screenshots")) {
                continue;
            }
            File found = findScriptsRoot(child, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ apply / restore

    /**
     * Applies the enabled mods' Data/Scripts right before the game starts. Returns quietly when
     * there is nothing to do. Any failure rolls the whole swap back and is thrown to the caller,
     * which must not start the game.
     */
    static void applyForLaunch(Context ctx) throws IOException {
        if (ctx == null || !isEnabled(ctx)) {
            return;
        }
        String gamePath = SetupActivity.getSavedGamePath(ctx);
        if (gamePath == null || gamePath.trim().isEmpty()) {
            return;
        }
        File gameRoot = new File(gamePath);
        if (!gameRoot.isDirectory()) {
            return;
        }
        File data = findChild(gameRoot, "Data");
        if (data == null || !data.isDirectory()) {
            return;
        }
        File scripts = findChild(data, "Scripts");
        if (scripts == null || !scripts.isDirectory()) {
            // No loose folder shadows the archives; the engine already sees the mod's scripts.
            return;
        }

        List<ModManager.Mod> allMods = ModManager.listMods(ctx);
        if (allMods == null || allMods.isEmpty()) {
            return;
        }
        List<ModManager.Mod> mods = new ArrayList<>();
        for (ModManager.Mod mod : allMods) {
            if (mod != null && mod.enabled && mod.dir != null && modHasScripts(mod.dir)) {
                mods.add(mod);
            }
        }
        if (mods.isEmpty()) {
            return;
        }

        // A previous run that did not return cleanly (or another game's stale backup) must be
        // restored before a new swap. recoverIfNeeded() is a no-op when there is no backup.
        recoverIfNeeded(ctx);

        // GeneralsX @bugfix Flux Build 09/11/2026 review: no second "auto-recovery" here. recoverIfNeeded()
        // above already restores every backup that is ours (marker, session or the game's own .scb files)
        // while no game runs; restoring anything else would delete the real Data/Scripts and put a
        // folder of the player's in its place. A backup still present now is reported, not overwritten.
        File backup = findChild(data, BACKUP_NAME);
        scripts = findChild(data, "Scripts");
        if (scripts == null || !scripts.isDirectory()) {
            return;
        }
        if (backup != null && backup.exists()) {
            throw new IOException("backup still present: " + backup.getAbsolutePath());
        }
        // GeneralsX @bugfix Flux Build 09/11/2026 The rename target when there is no backup yet (the
        // normal case). a9d30ce6 left it as findChild()'s null, so every launch with the switch on threw a
        // NullPointerException at renameTo(null) and the game never started.
        if (backup == null) {
            backup = new File(data, BACKUP_NAME);
        }
        if (scripts.getUsableSpace() < MIN_FREE_BYTES) {
            throw new IOException("not enough free storage");
        }

        saveSession(ctx, gamePath);
        if (!scripts.renameTo(backup)) {
            clearSession(ctx);
            throw new IOException("could not rename " + scripts.getAbsolutePath());
        }

        try {
            File marker = new File(backup, MARKER_NAME);
            if (!marker.createNewFile() && !marker.isFile()) {
                throw new IOException("could not write the backup marker");
            }
            if (!scripts.mkdirs() && !scripts.isDirectory()) {
                throw new IOException("could not recreate " + scripts.getAbsolutePath());
            }
            copyTree(backup, scripts, marker);
            // Lowest-priority mod first, highest last: the top mod wins, as it does for archives.
            for (int i = mods.size() - 1; i >= 0; i--) {
                overlayMod(mods.get(i).dir, scripts);
            }
            Log.i(TAG, "applied " + mods.size() + " DataScripts mod(s); backup " + backup);
        } catch (Exception e) {
            rollback(ctx, scripts, backup, e);
            throw (e instanceof IOException) ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    /**
     * Checks whether the game engine process (:game) is actively running. Because GeneralsZHActivity
     * runs in android:process=":game" while SetupActivity runs in the launcher process, in-memory
     * flags are not shared across processes. getRunningAppProcesses() probes the :game process for
     * the app's own UID, but only counts active states (foreground/visible/service), ignoring dead
     * or cached background processes (IMPORTANCE_CACHED) left in Zygote memory after the game finishes.
     */
    // GeneralsX @bugfix Flux Build 10/11/2026 Testers: "the next launch throws a Scripts error". A closed
    // game's process stays cached, and 12167 counted a cached process as running, so the backup was never
    // restored and the next launch found Data/scriptsbackup. The game now holds a file lock while its
    // activity lives (holdGameLock/releaseGameLock, GeneralsZHActivity); the OS drops it if the process
    // dies. Free lock = no game = safe to restore. The process check is only the fallback.
    private static final String GAME_LOCK_NAME = "gx_game_running.lock";
    private static java.io.RandomAccessFile sGameLockFile;
    private static java.nio.channels.FileLock sGameLock;

    /** In the game process, when its activity is created. */
    static synchronized void holdGameLock(Context ctx) {
        if (ctx == null || sGameLock != null) {
            return;
        }
        try {
            sGameLockFile = new java.io.RandomAccessFile(new File(ctx.getFilesDir(), GAME_LOCK_NAME), "rw");
            // Review: the launcher's probe may hold it for a few microseconds; retry briefly.
            for (int attempt = 0; attempt < 10 && sGameLock == null; attempt++) {
                sGameLock = sGameLockFile.getChannel().tryLock();
                if (sGameLock == null) {
                    Thread.sleep(20);
                }
            }
            if (sGameLock == null) {
                Log.w(TAG, "could not take the game lock (held elsewhere)");
                releaseGameLock();
                return;
            }
            // Touch the file: its time tells the launcher this game has started (see isGameRunning).
            sGameLockFile.setLength(0);
            sGameLockFile.write(Long.toString(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (Exception e) {
            Log.w(TAG, "could not take the game lock", e);
            releaseGameLock();
        }
    }

    /** In the game process, as the game ends (finish, before the launcher resumes) and in onDestroy. */
    static synchronized void releaseGameLock() {
        try {
            if (sGameLock != null) {
                sGameLock.release();
            }
        } catch (Exception ignored) {
            // closing the file below releases it as well
        }
        sGameLock = null;
        try {
            if (sGameLockFile != null) {
                sGameLockFile.close();
            }
        } catch (Exception ignored) {
            // nothing left to release
        }
        sGameLockFile = null;
    }

    /**
     * True: a game holds the lock. False: it does not. Null: the lock could not be checked.
     * Launcher process only: these are POSIX record locks, so probing from the :game process would
     * drop that process's own lock when the probe's file closes.
     */
    private static Boolean gameLockHeld(Context ctx) {
        File f = new File(ctx.getFilesDir(), GAME_LOCK_NAME);
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
            java.nio.channels.FileLock probe = raf.getChannel().tryLock();
            if (probe == null) {
                return Boolean.TRUE;
            }
            probe.release();
            return Boolean.FALSE;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            return Boolean.TRUE; // held inside this very process
        } catch (Exception e) {
            Log.w(TAG, "could not check the game lock", e);
            return null;
        }
    }

    static boolean isGameRunning(Context ctx) {
        if (ctx == null) {
            return false;
        }
        Boolean held = gameLockHeld(ctx);
        if (held != null) {
            if (held) {
                return true;
            }
            // Review: the game is launched in its own task, so the launcher gets RESULT_CANCELED (and a
            // restore attempt) before the :game process even starts. Between a fresh apply and the
            // game taking the lock, count the game as starting: the session is under a minute old and
            // the lock file has not been touched since it began.
            long started = prefs(ctx).getLong(PREF_SESSION_STARTED, 0L);
            long now = System.currentTimeMillis();
            if (isSessionActive(ctx) && started > 0 && now - started >= 0 && now - started < 60_000L
                    && new File(ctx.getFilesDir(), GAME_LOCK_NAME).lastModified() < started) {
                return true;
            }
            return false;
        }
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
        if (list != null) {
            String gameProc = ctx.getPackageName() + ":game";
            for (android.app.ActivityManager.RunningAppProcessInfo pi : list) {
                if (pi != null && gameProc.equals(pi.processName)) {
                    // GeneralsX @bugfix Flux Build 10/11/2026 Alive is alive: a backgrounded game can be ranked
                    // cached and still load a script on its next map.
                    return pi.importance < android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
                }
            }
        }
        return false;
    }

    /**
     * GeneralsX @bugfix Flux Build 09/11/2026 Whether the game's own Data/Scripts is in place and no
     * backup is left over: then a game can start safely even when applying the mods' scripts failed.
     */
    static boolean scriptsIntact(Context ctx) {
        String gamePath = ctx != null ? SetupActivity.getSavedGamePath(ctx) : null;
        if (gamePath == null || gamePath.trim().isEmpty()) {
            return true;
        }
        File data = findChild(new File(gamePath), "Data");
        if (data == null || !data.isDirectory()) {
            return true;
        }
        File backup = findChild(data, BACKUP_NAME);
        if (backup != null && backup.exists()) {
            return false;
        }
        return true;
    }

    /**
     * Restores a backup if the game is not running. Safe to call on every launcher resume and
     * when the toggle is switched off: it does nothing when there is no session.
     *
     * @return the backup that could not be restored, or null.
     */
    static File restoreIfIdle(Context ctx) {
        if (ctx == null || isGameRunning(ctx)) {
            return null;
        }
        return recoverIfNeeded(ctx);
    }

    /**
     * Startup crash recovery. Any backup this launcher created is renamed back to Data/Scripts
     * before a game can start. Returns the first backup that could not be restored, or null.
     */
    static File recoverIfNeeded(Context ctx) {
        if (ctx == null || isGameRunning(ctx)) {
            return null;
        }
        File failed = null;
        boolean anyBackup = false;
        List<String> paths = gamePaths(ctx);
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        for (String gamePath : paths) {
            if (gamePath == null || gamePath.trim().isEmpty()) {
                continue;
            }
            File root = new File(gamePath);
            File data = findChild(root, "Data");
            if (data == null || !data.isDirectory()) {
                continue;
            }
            File backup = findChild(data, BACKUP_NAME);
            if (backup == null || !backup.isDirectory()) {
                continue;
            }
            anyBackup = true;
            if (new File(backup, RESTORED_NAME).isFile()) {
                // Already copied back; only its deletion was left unfinished.
                try {
                    deleteTree(backup);
                } catch (Exception e) {
                    Log.w(TAG, "could not delete the restored backup " + backup, e);
                }
                continue;
            }
            File scripts = findChild(data, "Scripts");
            // The marker/prefs cover a swap interrupted at any point. containsScriptFiles()
            // also covers a crash before either was written: a scriptsbackup holding the game's
            // own .scb files is ours, and leaving it there would leave the game without scripts.
            boolean ours = new File(backup, MARKER_NAME).isFile()
                    || (isSessionActive(ctx) && gamePath.equals(sessionGame(ctx)))
                    || containsScriptFiles(backup, 0);
            if (!ours) {
                // A folder the player created and that holds no game scripts; never touch it.
                Log.w(TAG, "leaving unrecognised " + backup);
                continue;
            }
            try {
                restore(scripts, backup);
                clearSession(ctx);
                Log.i(TAG, "restored " + (scripts == null ? backup.getParentFile() : scripts));
            } catch (Exception e) {
                Log.e(TAG, "could not restore " + backup, e);
                if (failed == null) {
                    failed = backup;
                }
            }
        }
        if (!anyBackup && isSessionActive(ctx)) {
            // The backup disappeared, or the swap never happened; do not leave a stale session.
            clearSession(ctx);
        }
        return failed;
    }

    private static void restore(File scripts, File backup) throws IOException {
        if (backup == null || !backup.exists()) {
            return;
        }
        File target = scripts != null ? scripts : new File(backup.getParentFile(), "Scripts");
        if (target.exists()) {
            try {
                deleteTree(target);
            } catch (Exception e) {
                Log.w(TAG, "could not fully delete target before restore: " + e.getMessage());
            }
        }
        // The marker belongs to the backup, not to the restored game folder. Best effort delete.
        File marker = new File(backup, MARKER_NAME);
        if (marker.exists()) {
            marker.delete();
        }
        if (!backup.renameTo(target)) {
            // Android FUSE fallback: if renameTo fails, copy all files back and delete backup.
            Log.w(TAG, "renameTo failed (" + backup + " -> " + target + "), falling back to copy-restore");
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("could not create target directory " + target.getAbsolutePath());
            }
            copyTree(backup, target, marker);
            retireBackup(backup);
        }
    }

    /**
     * GeneralsX @bugfix Flux Build 09/11/2026 review: a backup whose files were copied back is renamed out
     * of the way before it is deleted, so a delete that stops half-way (FUSE) cannot leave a partial
     * "scriptsbackup" that a later recovery would put over the restored folder. When even the rename
     * fails, a RESTORED marker tells recovery to delete it, never to restore it.
     */
    private static void retireBackup(File backup) throws IOException {
        File trash = new File(backup.getParentFile(), ".scriptsbackup-done-" + System.currentTimeMillis());
        if (backup.renameTo(trash)) {
            deleteTree(trash);
            return;
        }
        new File(backup, RESTORED_NAME).createNewFile();
        deleteTree(backup);
    }

    private static void rollback(Context ctx, File scripts, File backup, Exception cause) {
        if (scripts != null && scripts.exists()) {
            try {
                deleteTree(scripts);
            } catch (Exception e) {
                // GeneralsX @bugfix Flux Build 09/11/2026 review: the backup is kept (with its marker), so the
                // launch is blocked and the next recovery puts the original back over the leftovers --
                // instead of copying the originals over a folder that still holds the mod's extra files.
                Log.e(TAG, "could not remove the half-applied scripts folder; backup kept at " + backup, e);
                return;
            }
        }
        if (backup != null && backup.exists()) {
            File marker = new File(backup, MARKER_NAME);
            if (marker.exists()) {
                marker.delete();
            }
            if (!backup.renameTo(scripts)) {
                try {
                    if (!scripts.isDirectory() && !scripts.mkdirs()) {
                        throw new IOException("could not create scripts dir " + scripts);
                    }
                    copyTree(backup, scripts, marker);
                    retireBackup(backup);
                    Log.i(TAG, "copy-restore rollback succeeded for " + scripts);
                } catch (Exception e) {
                    Log.e(TAG, "could not rename or copy-restore the backup back; it is kept at " + backup, e);
                }
            }
        }
        if (ctx != null) {
            clearSession(ctx);
        }
        Log.w(TAG, "DataScripts rollback after " + cause);
    }

    // ------------------------------------------------------------------ overlay

    private static void overlayMod(File modDir, File scripts) throws IOException {
        if (modDir == null || !modDir.isDirectory() || scripts == null || !scripts.isDirectory()) {
            return;
        }
        // Keep the packed loose archive current before reading it. This is the same pack the
        // engine does at launch; a failure here only means the loose path is used directly.
        try {
            ModPacker.ensurePacked(modDir);
        } catch (Exception e) {
            Log.w(TAG, "could not pack loose files for " + modDir, e);
        }

        List<File> bigs = ModAnalysis.bigFiles(modDir);
        if (bigs != null && !bigs.isEmpty()) {
            // The engine's rule inside one mod: the alphabetically first archive wins, so it is
            // mounted last. Sort descending and let later writes overwrite earlier ones.
            bigs.sort((a, b) -> {
                if (a == null && b == null) return 0;
                if (a == null) return 1;
                if (b == null) return -1;
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? -byName : -a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            });
            File loosePack = null;
            for (File big : new ArrayList<>(bigs)) {
                if (big != null && big.getName().toLowerCase(Locale.US).startsWith("!!loose_files")) {
                    bigs.remove(big);
                    loosePack = big;
                }
            }
            if (loosePack != null) {
                // Loose files beat every archive in a retail folder; the engine mounts this last.
                bigs.add(loosePack);
            }
            for (File big : bigs) {
                if (big != null && big.isFile()) {
                    overlayBig(big, scripts);
                }
            }
        }
        File loose = findScriptsRoot(modDir, 0);
        if (loose != null && loose.isDirectory()) {
            // Loose Data/Scripts is the last word inside the mod, as it is for the engine.
            copyTree(loose, scripts, null);
        }
    }

    private static void overlayBig(File big, File scripts) throws IOException {
        if (big == null || !big.isFile() || scripts == null) {
            return;
        }
        List<BigArchive.Entry> entries = BigArchive.list(big);
        if (entries == null) {
            return;
        }
        for (BigArchive.Entry entry : entries) {
            if (entry == null || entry.path == null) {
                continue;
            }
            String path = entry.path.replace('/', '\\').toLowerCase(Locale.US);
            if (!path.startsWith("data\\scripts\\") || path.endsWith("\\")) {
                continue;
            }
            if (entry.size == 0) {
                continue;
            }
            if (entry.size < 0 || entry.size > MAX_BIG_SCRIPT_BYTES) {
                Log.w(TAG, "skipping oversized script entry in " + big.getName() + ": " + entry.path);
                continue;
            }
            String relative = path.substring("data\\scripts\\".length());
            if (relative.isEmpty() || relative.contains("..") || relative.startsWith("\\")) {
                continue;
            }
            File out = new File(scripts, relative.replace('\\', File.separatorChar));
            ensureInside(scripts, out);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("could not create " + parent.getAbsolutePath());
            }
            byte[] data = BigArchive.read(big, entry);
            if (data != null) {
                try (OutputStream stream = new FileOutputStream(out)) {
                    stream.write(data);
                }
            }
        }
    }

    // ------------------------------------------------------------------ files

    private static void copyTree(File from, File to, File skip) throws IOException {
        if (from == null || !from.isDirectory() || to == null) {
            return;
        }
        File[] children = from.listFiles();
        if (children == null) {
            return;
        }
        Arrays.sort(children, (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return 1;
            if (b == null) return -1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File child : children) {
            if (child == null) {
                continue;
            }
            if (skip != null && child.equals(skip)) {
                continue;
            }
            if (Files.isSymbolicLink(child.toPath())) {
                continue;
            }
            File dest = new File(to, child.getName());
            if (child.isDirectory()) {
                if (!dest.isDirectory() && !dest.mkdirs()) {
                    throw new IOException("could not create " + dest.getAbsolutePath());
                }
                copyTree(child, dest, null);
            } else {
                ensureInside(to, dest);
                copyFile(child, dest);
            }
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        if (from == null || to == null) {
            return;
        }
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent.getAbsolutePath());
        }
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        if (Files.isSymbolicLink(file.toPath())) {
            if (!file.delete() && file.exists()) {
                throw new IOException("could not delete " + file.getAbsolutePath());
            }
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child != null) {
                        deleteTree(child);
                    }
                }
            }
        }
        if (!file.delete() && file.exists()) {
            throw new IOException("could not delete " + file.getAbsolutePath());
        }
    }

    private static void ensureInside(File root, File file) throws IOException {
        if (root == null || file == null) {
            return;
        }
        String rootPath = root.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("path escapes Data/Scripts: " + file);
        }
    }

    private static File findChild(File parent, String name) {
        if (parent == null || !parent.isDirectory() || name == null) {
            return null;
        }
        File[] children = parent.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child != null && child.getName().equalsIgnoreCase(name)) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ session

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void saveSession(Context ctx, String gamePath) {
        // commit(), not apply(): the marker must be on disk before the rename starts.
        prefs(ctx).edit()
                .putBoolean(PREF_SESSION_ACTIVE, true)
                .putString(PREF_SESSION_GAME, gamePath)
                .putLong(PREF_SESSION_STARTED, System.currentTimeMillis())
                .commit();
    }

    private static void clearSession(Context ctx) {
        prefs(ctx).edit()
                .putBoolean(PREF_SESSION_ACTIVE, false)
                .remove(PREF_SESSION_GAME)
                .remove(PREF_SESSION_STARTED)
                .apply();
    }

    private static boolean isSessionActive(Context ctx) {
        return prefs(ctx).getBoolean(PREF_SESSION_ACTIVE, false);
    }

    private static String sessionGame(Context ctx) {
        return prefs(ctx).getString(PREF_SESSION_GAME, null);
    }

    private static List<String> gamePaths(Context ctx) {
        List<String> paths = new ArrayList<>();
        String zh = SetupActivity.getSavedGamePath(ctx, SetupActivity.GAME_ZERO_HOUR);
        String generals = SetupActivity.getSavedGamePath(ctx, SetupActivity.GAME_GENERALS);
        if (zh != null && !zh.trim().isEmpty()) {
            paths.add(zh);
        }
        if (generals != null && !generals.trim().isEmpty() && !generals.equals(zh)) {
            paths.add(generals);
        }
        return paths;
    }
}
