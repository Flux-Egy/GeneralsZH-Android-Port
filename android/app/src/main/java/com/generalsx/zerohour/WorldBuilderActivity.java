// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the native World Builder.
//
// An SDLActivity in its own process (":wb", AndroidManifest.xml) that loads SDL3 and libfluxwb.so --
// never the game's engine library -- so the editor shares nothing with a running game: not its
// memory, not its GL context, not TouchInput. The 3D view is EA's World Builder view drawn by the
// game's renderer (FluxWBView.cpp); maps are read and written by EA's code only (FluxWBDoc.cpp).
//
// This class is the Java side: the tool rail over the SDL surface, the open/save dialogs, and the
// bridge (nativeCommand -> FluxWBBridge.h's queue; onNativeEvent <- the editor's events).
//
// Saving follows the old HTML editor's rule: a map goes to <user data>/Maps/<name>/<name>.map, and a
// folder is only ever written when the editor created it (EDITOR_MARKER), so no downloaded or
// official map is replaced.
package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldBuilderActivity extends SDLActivity {
    private static final String TAG = "WorldBuilder";
    /** A map to open: a real path, or an archive path such as "maps\\alpine assault\\alpine assault.map". */
    static final String EXTRA_MAP = "com.generalsx.zerohour.WB_MAP";
    /** Left in each map folder the editor creates, so it may save over its own maps and no other. */
    private static final String EDITOR_MARKER = ".flux-world-builder";
    private static final int MAX_NAME = 64;

    private static volatile WorldBuilderActivity sActive;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private WbEditorUi mUi;
    /** The open map has changes that are not saved (the editor's "history" event). */
    private boolean mModified;
    /** The open map's name (its folder under Maps), or null for a new map. Set on the UI thread, and
     *  by getArguments() (SDL's thread) before SDL_main starts. */
    private volatile String mMapName;
    /** The user Maps folder the open map came from (absolute), or null (archive map, new map). */
    private volatile String mOpenedUserFolder;
    // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 review: the folder of the map being opened, taken
    // over by mOpenedUserFolder only when the editor reports "opened" (a failed open keeps the old one,
    // so Save never writes the still-open map into the folder of a map that did not load).
    private volatile String mOpeningFolder;
    /** What an unsaved-changes "Save" goes on to do once the map is saved. */
    private Runnable mAfterSave;
    /** The name a save in progress will give the map, applied when the editor reports "saved". */
    private String mPendingName;
    // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 Start positions: every save asks the editor
    // first ("starts check"); a map without Player_1_Start, or with a start in the border, opens on a
    // black screen in the game (owner report: a new map). The save waits here for the answer.
    private Runnable mSaveAfterCheck;
    /** The answer is the one after "starts fix": the save goes ahead whatever it says. */
    private boolean mStartsFixed;
    // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 The player's own map: the first Save after
    // opening it asks once whether to overwrite it or save a copy (the folder it was confirmed for).
    private String mOverwriteConfirmed;

    // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.1 round 4 UI audit: one toast at a time; a new one
    // (an EA message box right after "saved", say) replaces the one still showing instead of stacking.
    private Toast mLastToast;

    private Toast gxToast(Context c, CharSequence text, int duration) {
        return trackToast(Toast.makeText(c, text, duration));
    }

    private Toast gxToast(Context c, int resId, int duration) {
        return trackToast(Toast.makeText(c, resId, duration));
    }

    private Toast trackToast(Toast t) {
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = t;
        return t;
    }

    /** Queued for the editor's SDL thread (FluxWBJni.cpp). */
    static native void nativeCommand(String verb, String arg);

    /** World Builder is in this APK (an engine-only OTA or an older build may not have it). */
    static boolean isAvailable(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, "libfluxwb.so").isFile();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "fluxwb" };
    }

    @Override
    protected String[] getArguments() {
        List<String> args = new ArrayList<>();
        String zh = SetupActivity.getSavedGamePath(this, SetupActivity.GAME_ZERO_HOUR);
        if (zh != null) {
            args.add("-game");
            args.add(zh);
        }
        args.add("-user");
        args.add(DataPackInstaller.userDataDir().getAbsolutePath());
        args.add("-momentum");
        args.add(prefMomentum(this) ? "1" : "0");
        String map = getIntent() != null ? getIntent().getStringExtra(EXTRA_MAP) : null;
        if (map != null && !map.isEmpty()) {
            args.add("-map");
            args.add(map);
            mMapName = folderNameOf(map);
            mOpeningFolder = userFolderOf(map);
        }
        return args.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActive = this;
        hideSystemBars();
        buildOverlay();
        setActionsEnabled(false);
    }

    /** singleTask: a map opened from the launcher while the editor runs arrives here. */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final String map = intent != null ? intent.getStringExtra(EXTRA_MAP) : null;
        if (map != null && !map.isEmpty()) {
            guardUnsaved(() -> {
                WbScriptEditor.cancelIfOpen();
                mUi.setTitle(getString(R.string.wb_loading));
                mOpeningFolder = userFolderOf(map);
                nativeCommand("open", map);
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (sActive == this) {
            sActive = null;
        }
        mIo.shutdownNow();
        WbScriptEditor.closeIfOpen();
        boolean finishing = isFinishing();
        super.onDestroy(); // stops SDL_main and waits for it
        // SDLActivity refuses a second onCreate in a process whose SDL_main has finished (it calls
        // System.exit there, so the next World Builder launch would only flash). ":wb" hosts this
        // activity alone, so it ends with it, and each launch starts a fresh process.
        if (finishing) {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }

    private void hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ------------------------------------------------------------------ the overlay

    private static final String PREF_MOMENTUM = "wb_momentum";
    private static final String PREF_SNAP = "wb_snap";

    static boolean prefMomentum(Context c) {
        return c.getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_MOMENTUM, true);
    }

    static boolean prefSnap(Context c) {
        return c.getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SNAP, false);
    }

    static void setPrefSnap(Context c, boolean on) {
        c.getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SNAP, on).apply();
    }

    private void buildOverlay() {
        mUi = new WbEditorUi(this, new WbEditorUi.Host() {
            @Override public void onExit() { confirmExit(); }
            @Override public void onSave() { WorldBuilderActivity.this.onSave(); }
            @Override public void onSaveAs() { WorldBuilderActivity.this.onSaveAs(); }
            @Override public void onMenu(View anchor) { showMenu(anchor); }
            @Override public void onScripts() { openScripts(); }
            @Override public void onHelp() { WbGuide.show(WorldBuilderActivity.this); }
        });
        mUi.build();
    }

    private void setActionsEnabled(boolean enabled) {
        if (mUi != null) mUi.setReady(enabled);
    }

    private void showStatus() {
        CharSequence name = mMapName != null ? mMapName : getString(R.string.wb_untitled);
        mUi.setTitle(mModified ? "\u2022 " + name : name);
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        // GeneralsX @tweak Flux Build 08/11/2026 v1.4.1 round 4 UI audit: only what the bars do not have.
        // Save as, Scripts and Help are buttons in the top bar, Trigger areas a tool on the left rail;
        // repeated here they made the menu longer and their order was not fixed.
        menu.getMenu().add(0, 1, 0, R.string.wb_open);
        menu.getMenu().add(0, 2, 1, R.string.wb_new);
        menu.getMenu().add(0, 3, 2, R.string.wb_home);
        menu.getMenu().add(0, 4, 3, R.string.wb_settings);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: guardUnsaved(this::onOpen); return true;
                case 2: guardUnsaved(this::onNew); return true;
                case 3: nativeCommand("camera", "reset"); return true;
                case 4: showSettings(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(this, 20);
        box.setPadding(pad, UiKit.dp(this, 8), pad, 0);
        com.google.android.material.materialswitch.MaterialSwitch momentum = UiKit.switchRow(box,
            getString(R.string.wb_set_momentum), getString(R.string.wb_set_momentum_desc));
        momentum.setChecked(prefMomentum(this));
        momentum.setOnCheckedChangeListener((b, on) -> {
            getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_MOMENTUM, on).apply();
            nativeCommand("momentum", on ? "1" : "0");
        });
        com.google.android.material.materialswitch.MaterialSwitch snap = UiKit.switchRow(box,
            getString(R.string.wb_opt_snap), getString(R.string.wb_set_snap_desc));
        snap.setChecked(prefSnap(this));
        snap.setOnCheckedChangeListener((b, on) -> {
            setPrefSnap(this, on);
            nativeCommand("set", "snap " + (on ? 1 : 0));
        });
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wb_settings)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** Runs next, after asking when the open map has changes that are not saved. */
    private void guardUnsaved(Runnable next) {
        if (!mModified) {
            next.run();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wb_unsaved_title)
            .setMessage(R.string.wb_unsaved_message)
            .setPositiveButton(R.string.wb_save, (d, w) -> { mAfterSave = next; onSave(); })
            .setNegativeButton(R.string.wb_discard, (d, w) -> next.run())
            .setNeutralButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmExit() {
        guardUnsaved(this::finish);
    }

    @Override
    public void onBackPressed() {
        confirmExit();
    }

    private void openScripts() {
        WbScriptEditor.open(this);
    }

    // ------------------------------------------------------------------ events from the editor

    /** Called by the editor (FluxWBJni.cpp) on its own thread. */
    @SuppressWarnings("unused")
    static void onNativeEvent(String kind, String text) {
        final WorldBuilderActivity a = sActive;
        if (a == null) {
            return;
        }
        a.mMain.post(() -> a.handleEvent(kind, text != null ? text : ""));
    }

    private void handleEvent(String kind, String text) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (WbScriptEditor.onEvent(kind, text)) {
            return;
        }
        switch (kind) {
            case "ready":
                setActionsEnabled(true);
                showStatus();
                nativeCommand("set", "snap " + (prefSnap(this) ? 1 : 0));
                WbGuide.maybeShowFirstTime(this);
                break;
            case "tool":
                mUi.setTool(text);
                break;
            case "history":
                mUi.setHistory(text.length() > 0 && text.charAt(0) == '1', text.length() > 1 && text.charAt(1) == '1',
                    text.length() > 2 && text.charAt(2) == '1');
                mModified = text.length() > 2 && text.charAt(2) == '1';
                showStatus();
                break;
            case "objects":
                mUi.setObjects(text);
                break;
            case "teams":
                mUi.setTeams(text);
                break;
            case "object":
            case "waypoint":
            case "area":
                mUi.setSelection(kind, text);
                break;
            case "opened":
                mMapName = text.isEmpty() ? null : stripMapExtension(text);
                // A new map (empty name) belongs to no folder: Save must ask for a name, never write
                // over the map that was open before File > New.
                mOpenedUserFolder = text.isEmpty() ? null : mOpeningFolder;
                mOpeningFolder = null;
                mOverwriteConfirmed = null;
                mSaveAfterCheck = null;
                if (mStartsDialog != null) mStartsDialog.dismiss();
                showStatus();
                break;
            case "starts": {
                final Runnable save = mSaveAfterCheck;
                if (save == null) break;
                if (text.isEmpty() || mStartsFixed) {
                    mSaveAfterCheck = null;
                    mStartsFixed = false;
                    save.run();
                } else {
                    showStartsProblem(text);
                }
                break;
            }
            case "saved":
                if (mPendingName != null) {
                    mOpenedUserFolder = new File(new File(DataPackInstaller.userDataDir(), "Maps"), mPendingName).getAbsolutePath();
                    mMapName = mPendingName;
                    mPendingName = null;
                }
                // A map saved here is the player's own now; saving it again needs no question.
                mOverwriteConfirmed = mOpenedUserFolder;
                showStatus();
                gxToast(this, getString(R.string.wb_saved, text), Toast.LENGTH_LONG).show();
                if (mAfterSave != null) {
                    Runnable next = mAfterSave;
                    mAfterSave = null;
                    next.run();
                }
                break;
            case "error":
                if (text.startsWith("missing-textures ")) {
                    new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.wb_missing_textures_title)
                        .setMessage(getString(R.string.wb_missing_textures, text.substring(17)))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                } else {
                    gxToast(this, getString(R.string.wb_error, text), Toast.LENGTH_LONG).show();
                }
                mPendingName = null;
                mSaveAfterCheck = null;
                mAfterSave = null; // a save that failed does not go on to exit or open another map
                showStatus(); // an open, new or save that failed leaves the previous map in place
                break;
            case "message":
                gxToast(this, text, Toast.LENGTH_LONG).show();
                break;
            default:
                Log.i(TAG, kind + ": " + text);
                break;
        }
    }

    // ------------------------------------------------------------------ open / new / save

    private void onOpen() {
        CharSequence[] where = { getString(R.string.wb_open_mine), getString(R.string.wb_open_official) };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wb_open)
            .setItems(where, (d, which) -> mIo.execute(() -> {
                List<String[]> maps = which == 0 ? myMaps() : officialMaps();
                mMain.post(() -> showMapList(maps));
            }))
            .show();
    }

    private void showMapList(List<String[]> maps) {
        // (chooser of maps; the status shows "Loading" until the editor answers)
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (maps.isEmpty()) {
            gxToast(this, R.string.wb_no_maps, Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[maps.size()];
        for (int i = 0; i < maps.size(); i++) {
            labels[i] = maps.get(i)[0];
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wb_open)
            .setItems(labels, (d, which) -> {
                mUi.setTitle(getString(R.string.wb_loading));
                mOpeningFolder = userFolderOf(maps.get(which)[1]);
                nativeCommand("open", maps.get(which)[1]);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** {label, path} of the maps in <user data>/Maps/<name>/<name>.map, by name. */
    private static List<String[]> myMaps() {
        List<String[]> out = new ArrayList<>();
        File[] folders = new File(DataPackInstaller.userDataDir(), "Maps").listFiles();
        if (folders != null) {
            for (File folder : folders) {
                File map = mapFileIn(folder);
                if (folder.isDirectory() && map != null) {
                    out.add(new String[] { folder.getName(), map.getAbsolutePath() });
                }
            }
        }
        Collections.sort(out, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return out;
    }

    /** {label, archive path} of the maps in the game's MapsZH.big. */
    private List<String[]> officialMaps() {
        List<String[]> out = new ArrayList<>();
        String zh = SetupActivity.getSavedGamePath(this, SetupActivity.GAME_ZERO_HOUR);
        if (zh == null) {
            return out;
        }
        File archive = new File(zh, "MapsZH.big");
        try {
            for (BigArchive.Entry e : BigArchive.list(archive)) {
                String lower = e.path.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".map") && lower.startsWith("maps\\")) {
                    out.add(new String[] { folderNameOf(e.path), e.path });
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot list " + archive, e);
        }
        Collections.sort(out, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return out;
    }

    private void onNew() {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wb_new)
            .setMessage(R.string.wb_new_message)
            .setPositiveButton(R.string.wb_new, (d, w) -> {
                mUi.setTitle(getString(R.string.wb_loading));
                nativeCommand("new", "100 100 16 30"); // EA's File > New defaults
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Save. The player's own map (opened from <user data>/Maps) is saved back to its own .map file, as
     * File > Save does on the PC. A new map, or a map from the game's archives, asks for a name (Save as).
     */
    private void onSave() {
        final String folder = mOpenedUserFolder;
        if (folder == null) {
            onSaveAs();
            return;
        }
        if (!folder.equals(mOverwriteConfirmed)) {
            // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 Once per opened map: overwrite it,
            // or keep it as it is and save a copy.
            new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.wb_save)
                .setMessage(getString(R.string.wb_overwrite_message, mMapName != null ? mMapName : new File(folder).getName()))
                .setPositiveButton(R.string.wb_overwrite, (d, w) -> {
                    mOverwriteConfirmed = folder;
                    onSave();
                })
                .setNegativeButton(R.string.wb_save_copy, (d, w) -> onSaveAs())
                .setNeutralButton(android.R.string.cancel, (d, w) -> mAfterSave = null)
                .setOnCancelListener(d -> mAfterSave = null)
                .show();
            return;
        }
        mIo.execute(() -> {
            final File target = mapFileIn(new File(folder));
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (target == null) {
                    onSaveAs(); // the map file is gone (deleted outside): ask for a name
                    return;
                }
                mPendingName = new File(folder).getName();
                saveChecked(target.getAbsolutePath());
            });
        });
    }

    /** The save, after the editor's start-position check (the "starts" event runs it). */
    private void saveChecked(final String path) {
        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 review: one save at a time (a double tap
        // opened two start dialogs).
        if (mSaveAfterCheck != null) return;
        mSaveAfterCheck = () -> nativeCommand("save", path);
        mStartsFixed = false;
        nativeCommand("starts", "check");
    }

    /** "missing=1,2;outside=3": what is wrong, what it does in the game, and the fix. */
    private void showStartsProblem(String problems) {
        StringBuilder msg = new StringBuilder();
        for (String part : problems.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String players = part.substring(eq + 1).replace(",", ", ");
            if (part.startsWith("missing")) {
                msg.append(getString(R.string.wb_starts_missing, players)).append('\n');
            } else if (part.startsWith("outside")) {
                msg.append(getString(R.string.wb_starts_outside, players)).append('\n');
            }
        }
        msg.append('\n').append(getString(R.string.wb_starts_black));
        if (mStartsDialog != null) mStartsDialog.dismiss();
        mStartsDialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.wb_starts_title)
            .setMessage(msg.toString())
            .setPositiveButton(R.string.wb_starts_fix, (d, w) -> {
                if (mSaveAfterCheck == null) return; // the map changed under the dialog
                mStartsFixed = true;
                nativeCommand("starts", "fix");
            })
            .setNegativeButton(R.string.wb_starts_anyway, (d, w) -> {
                Runnable save = mSaveAfterCheck;
                mSaveAfterCheck = null;
                if (save != null) save.run();
            })
            .setNeutralButton(android.R.string.cancel, (d, w) -> cancelPendingSave())
            .setOnCancelListener(d -> cancelPendingSave())
            .show();
        mStartsDialog.setOnDismissListener(d -> mStartsDialog = null);
    }

    /** The start-position dialog while it is open (dismissed when another map opens). */
    private androidx.appcompat.app.AlertDialog mStartsDialog;

    private void cancelPendingSave() {
        mSaveAfterCheck = null;
        mPendingName = null;
        mAfterSave = null;
    }

    private void onSaveAs() {
        final EditText name = new EditText(this);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setSingleLine(true);
        // A name the editor could only pass on with "?" in place of non-English letters is not offered.
        String current = mMapName;
        final boolean official = current != null && mOpenedUserFolder == null;
        if (official) {
            // A map from the game's archives is never saved under its own name (that name belongs to the
            // official map); a free one is offered.
            current = current + " edited";
        }
        name.setText(current != null && isValidName(current) && current.indexOf('?') < 0 ? current : "");
        name.setHint(R.string.wb_save_hint);
        int pad = UiKit.dp(this, 20);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, UiKit.dp(this, 8), pad, 0);
        box.addView(name);
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 Save As says what it does: a copy under
        // a new name; an official map stays as it is in the game's archives.
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
            .setTitle(mMapName != null ? R.string.wb_save_as : R.string.wb_save)
            .setView(box)
            .setPositiveButton(R.string.wb_save, (d, w) -> saveAs(name.getText().toString().trim()))
            .setNegativeButton(android.R.string.cancel, (d, w) -> mAfterSave = null)
            .setOnCancelListener(d -> mAfterSave = null);
        if (official) {
            b.setMessage(R.string.wb_save_copy_official);
        } else if (mMapName != null) {
            b.setMessage(R.string.wb_save_copy_own);
        }
        b.show();
    }

    private void saveAs(String requested) {
        if (!isValidName(requested)) {
            gxToast(this, R.string.wb_bad_name, Toast.LENGTH_LONG).show();
            mAfterSave = null; // nothing was saved: an exit or open waiting for the save does not run later
            return;
        }
        final String confirmed = mOverwriteConfirmed;
        mIo.execute(() -> {
            File folder = new File(new File(DataPackInstaller.userDataDir(), "Maps"), requested);
            File marker = new File(folder, EDITOR_MARKER);
            // An official map's name would put a second map of that name in the game's map list.
            boolean official = false;
            for (String[] m : officialMaps()) {
                if (m[0].equalsIgnoreCase(requested)) {
                    official = true;
                    break;
                }
            }
            boolean ok;
            if (official) {
                ok = false;
            } else if (folder.exists()) {
                // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 review: Save As makes a copy, so it
                // never writes over a map silently -- only over the open map once the player chose
                // Overwrite for it; any other existing name is taken.
                ok = folder.isDirectory() && confirmed != null && folder.getAbsolutePath().equals(confirmed);
            } else {
                ok = folder.mkdirs() && createMarker(marker);
            }
            final boolean allowed = ok;
            // Into an existing folder: its own .map, whatever its case, so no second copy appears.
            final File existing = allowed ? mapFileIn(folder) : null;
            final File target = existing != null ? existing : new File(folder, requested + ".map");
            mMain.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (!allowed) {
                    gxToast(this, getString(R.string.wb_name_taken, requested), Toast.LENGTH_LONG).show();
                    mAfterSave = null;
                    return;
                }
                mPendingName = requested;
                saveChecked(target.getAbsolutePath());
            });
        });
    }

    private static boolean createMarker(File marker) {
        try {
            return marker.createNewFile() || marker.isFile();
        } catch (IOException e) {
            return false;
        }
    }

    /** A folder name the game and Windows both accept: no separators, no reserved characters. */
    static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME || name.startsWith(".")
                || name.endsWith(".") || name.endsWith(" ")
                // EA's preview writer turns every ".map" in the path into ".tga" (MapPreview.cpp).
                || name.toLowerCase(Locale.ROOT).contains(".map")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c > 0x7e || "\\/:*?\"<>|".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** "maps\\alpine assault\\alpine assault.map" or ".../Maps/X/X.map" -> its folder name. */
    /**
     * The map file of a map folder: <name>.map, or a .map that differs from the folder's name only in
     * case (maps made on Windows often do); null if there is none.
     */
    static File mapFileIn(File folder) {
        File exact = new File(folder, folder.getName() + ".map");
        if (exact.isFile()) return exact;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().equalsIgnoreCase(folder.getName() + ".map")) return f;
            }
        }
        return null;
    }

    /** The folder of a map inside <user data>/Maps (absolute), or null for any other path. */
    static String userFolderOf(String mapPath) {
        if (mapPath == null || !mapPath.startsWith("/")) return null;
        File parent = new File(mapPath).getParentFile();
        File maps = new File(DataPackInstaller.userDataDir(), "Maps");
        return parent != null && maps.getAbsolutePath().equals(parent.getParent()) ? parent.getAbsolutePath() : null;
    }

    static String folderNameOf(String path) {
        String p = path.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String file = slash >= 0 ? p.substring(slash + 1) : p;
        return stripMapExtension(file);
    }

    private static String stripMapExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".map") ? name.substring(0, name.length() - 4) : name;
    }
}
