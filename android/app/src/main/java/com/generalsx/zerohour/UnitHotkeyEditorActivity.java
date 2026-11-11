// GeneralsX @feature Flux Build 03/11/2026 Unit, building and power hotkeys, GenHotkeys-style: pick a
// faction, a unit or building, a button, a key. See UnitHotkeyModel for what is read and written.
package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class UnitHotkeyEditorActivity extends Activity {
    private static final String TAG = "UnitHotkeyEditor";
    private static final String KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private TextView statusView;
    private MaterialButton saveButton;
    private UiKit.Row factionRow;
    private LinearLayout unitsHost;

    private UnitHotkeyModel model;
    private String languageDir;
    private int sideIndex;
    private boolean onlyConflicts;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.unit_hotkeys_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.unit_hotkeys_title), 0, null, null);
        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        page = UiKit.scrollingPage(host);
        statusView = UiKit.supporting(page, getString(R.string.unit_hotkeys_loading));
        setContentView(root);
        InsetUtil.applySafeInsets(root);
        load();
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        new Thread(() -> {
            UnitHotkeyModel loaded = null;
            String lang = null;
            String error = null;
            try {
                File gameRoot = HotkeyPackInstaller.gameRoot(this);
                List<File> mods = new ArrayList<>();
                for (ModManager.Mod mod : ModManager.listMods(this)) {
                    if (mod.enabled) {
                        mods.add(mod.dir);
                    }
                }
                lang = HotkeyPackInstaller.languageDir(this, gameRoot);
                loaded = UnitHotkeyModel.load(new GameDataIndex(gameRoot, mods), lang);
            } catch (HotkeyPackInstaller.ImportException e) {
                error = getString(e.messageRes);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "could not read the game's units", e);
                error = getString(R.string.unit_hotkeys_load_failed, String.valueOf(e.getMessage()));
            }
            final UnitHotkeyModel result = loaded;
            final String language = lang;
            final String failure = error;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failure != null) {
                    statusView.setText(failure);
                    return;
                }
                model = result;
                languageDir = language;
                sideIndex = 0;
                buildUi();
            });
        }, "UnitHotkeyLoad").start();
    }

    private String tablePath() {
        return "Data/" + languageDir + "/generals.csf";
    }

    // ------------------------------------------------------------------ screen

    private void buildUi() {
        page.removeAllViews();
        LinearLayout intro = UiKit.card(page);
        UiKit.sectionHeader(intro, R.drawable.ic_gzh_sliders, getString(R.string.unit_hotkeys_title), false);
        UiKit.helpText(intro, getString(R.string.unit_hotkeys_help));
        UiKit.helpText(intro, getString(R.string.unit_hotkeys_mods_note));
        statusView = UiKit.supporting(intro, "");
        LinearLayout actions = UiKit.buttonRow(intro);
        saveButton = UiKit.button(actions, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_save,
            getString(R.string.hotkey_editor_save), this::save);
        UiKit.share(saveButton, true);
        UiKit.share(UiKit.button(actions, UiKit.BTN_OUTLINE, R.drawable.ic_gzh_refresh,
            getString(R.string.hotkey_editor_reset), this::confirmReset), false);

        if (model.sides.isEmpty()) {
            UiKit.helpText(intro, getString(R.string.unit_hotkeys_empty));
            refreshStatus();
            return;
        }
        LinearLayout pickers = UiKit.card(page);
        factionRow = UiKit.listRow(pickers, R.drawable.ic_gzh_globe, getString(R.string.unit_hotkeys_faction), "",
            this::pickSide);
        com.google.android.material.materialswitch.MaterialSwitch filter = UiKit.switchRow(pickers,
            getString(R.string.unit_hotkeys_only_conflicts), getString(R.string.unit_hotkeys_only_conflicts_desc));
        filter.setOnCheckedChangeListener((b, checked) -> {
            onlyConflicts = checked;
            buildUnits();
        });
        unitsHost = new LinearLayout(this);
        unitsHost.setOrientation(LinearLayout.VERTICAL);
        page.addView(unitsHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        buildUnits();
    }

    private void refreshStatus() {
        statusView.setText(getString(R.string.unit_hotkeys_status, model.textSource, model.edits.size(),
            model.unitsWithConflicts()));
    }

    private void pickSide() {
        CharSequence[] names = new CharSequence[model.sides.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = model.sides.get(i).title;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.unit_hotkeys_faction)
            .setSingleChoiceItems(names, sideIndex, (d, which) -> {
                sideIndex = which;
                d.dismiss();
                buildUnits();
            })
            .show();
    }

    private void buildUnits() {
        UnitHotkeyModel.Side side = model.sides.get(sideIndex);
        factionRow.supporting.setText(side.title);
        unitsHost.removeAllViews();
        LinearLayout card = null;
        String group = null;
        int shown = 0;
        for (UnitHotkeyModel.Unit unit : side.units) {
            Set<String> clashes = model.conflicts(unit);
            if (onlyConflicts && clashes.isEmpty()) {
                continue;
            }
            if (!unit.sorting.equals(group)) {
                group = unit.sorting;
                card = UiKit.card(unitsHost);
                UiKit.sectionHeader(card, R.drawable.ic_gzh_chip, getString(groupTitle(group)), false);
            }
            UiKit.Row row = UiKit.listRow(card, 0, unit.title, summary(unit), () -> openUnit(unit));
            row.supporting.setTextColor(UiKit.color(this,
                clashes.isEmpty() ? R.color.gzh_on_surface_variant : R.color.gzh_status_warn));
            shown++;
        }
        if (shown == 0) {
            UiKit.helpText(UiKit.card(unitsHost), getString(onlyConflicts
                ? R.string.unit_hotkeys_no_conflicts : R.string.unit_hotkeys_empty));
        }
        refreshStatus();
    }

    private static int groupTitle(String sorting) {
        switch (sorting) {
            case "BUILDER": return R.string.unit_hotkeys_group_builders;
            case "STRUCTURE": return R.string.unit_hotkeys_group_structure;
            case "INFANTRY": return R.string.unit_hotkeys_group_infantry;
            default: return R.string.unit_hotkeys_group_vehicle;
        }
    }

    /** "C Crusader · T Tomahawk · Sell" -- each button's key, then its name. */
    private String summary(UnitHotkeyModel.Unit unit) {
        StringBuilder s = new StringBuilder();
        for (UnitHotkeyModel.Slot slot : unit.slots) {
            if (s.length() > 0) {
                s.append("  ·  ");
            }
            String text = model.text(slot.label);
            char key = UnitHotkeyModel.letterOf(text);
            if (key != 0) {
                s.append(key).append(' ');
            }
            s.append(UnitHotkeyModel.plain(text));
        }
        return s.toString();
    }

    // ------------------------------------------------------------------ editing

    private void openUnit(UnitHotkeyModel.Unit unit) {
        Set<String> clashes = model.conflicts(unit);
        CharSequence[] items = new CharSequence[unit.slots.size()];
        for (int i = 0; i < items.length; i++) {
            UnitHotkeyModel.Slot slot = unit.slots.get(i);
            String text = model.text(slot.label);
            char key = UnitHotkeyModel.letterOf(text);
            StringBuilder line = new StringBuilder();
            line.append(key != 0 ? "[" + key + "]  " : "[ ]  ").append(UnitHotkeyModel.plain(text));
            Integer users = model.labelUnits.get(slot.label);
            if (users != null && users > 1) {
                line.append("  —  ").append(getString(R.string.unit_hotkeys_shared, users));
            }
            if (clashes.contains(slot.label)) {
                line.append("  ⚠");
            }
            items[i] = line;
        }
        new AlertDialog.Builder(this)
            .setTitle(unit.title)
            .setItems(items, (d, which) -> pickKey(unit, unit.slots.get(which)))
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private void pickKey(UnitHotkeyModel.Unit unit, UnitHotkeyModel.Slot slot) {
        String name = UnitHotkeyModel.plain(model.text(slot.label));
        char current = UnitHotkeyModel.letterOf(model.text(slot.label));
        java.util.Set<Character> taken = new java.util.HashSet<>();
        for (UnitHotkeyModel.Slot other : unit.slots) {
            if (!other.label.equals(slot.label)) {
                char c = UnitHotkeyModel.letterOf(model.text(other.label));
                if (c != 0) {
                    taken.add(c);
                }
            }
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dim(this, R.dimen.gzh_item_gap);
        content.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        StringBuilder commandKeys = new StringBuilder();
        for (Map.Entry<Character, String> e : model.commandKeys.entrySet()) {
            if (Character.isLetter(e.getKey())) {
                if (commandKeys.length() > 0) {
                    commandKeys.append(", ");
                }
                commandKeys.append(e.getKey());
            }
        }
        hint.setText(getString(R.string.unit_hotkeys_pick_hint, commandKeys.toString()));
        hint.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        content.addView(hint);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(9);
        final AlertDialog[] dialog = new AlertDialog[1];
        for (int i = 0; i < KEYS.length(); i++) {
            char key = KEYS.charAt(i);
            TextView cell = new TextView(this);
            cell.setText(String.valueOf(key));
            cell.setGravity(Gravity.CENTER);
            cell.setTypeface(Typeface.DEFAULT_BOLD);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cell.setMinWidth(UiKit.dp(this, 40));
            cell.setMinHeight(UiKit.dp(this, 40));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiKit.dp(this, 8));
            int fill = key == current ? R.color.gzh_primary
                : taken.contains(key) ? R.color.gzh_error_container : R.color.gzh_surface_container_high;
            bg.setColor(UiKit.color(this, fill));
            cell.setBackground(bg);
            cell.setTextColor(UiKit.color(this, key == current ? R.color.gzh_on_primary
                : taken.contains(key) ? R.color.gzh_on_error_container : R.color.gzh_on_surface));
            cell.setOnClickListener(v -> {
                model.setLetter(slot.label, key);
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                changed(unit);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            int m = UiKit.dp(this, 3);
            lp.setMargins(m, m, m, m);
            grid.addView(cell, lp);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(grid);
        content.addView(scroll);

        dialog[0] = new AlertDialog.Builder(this)
            .setTitle(getString(R.string.unit_hotkeys_pick_title, name))
            .setView(content)
            .setNeutralButton(R.string.hotkey_editor_default, (d, w) -> {
                model.revert(slot.label);
                changed(unit);
            })
            .setPositiveButton(R.string.unit_hotkeys_remove, (d, w) -> {
                model.setLetter(slot.label, (char) 0);
                changed(unit);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> openUnit(unit))
            .create();
        dialog[0].show();
    }

    /** After a key changed: the list shows it, and the unit's buttons come back for the next one. */
    private void changed(UnitHotkeyModel.Unit unit) {
        buildUnits();
        openUnit(unit);
    }

    // ------------------------------------------------------------------ save / reset

    private void save() {
        if (model == null) {
            return;
        }
        byte[] table = model.toCsf();
        boolean ours = model.looseIsOurs;
        saveButton.setEnabled(false);
        new Thread(() -> {
            int message = R.string.hotkey_editor_saved;
            try {
                if (table != null) {
                    HotkeyPackInstaller.writeManaged(this, HotkeyPackInstaller.gameRoot(this), tablePath(), table);
                } else if (ours) {
                    // Nothing of the editor's left: the table goes, and a pack's table comes back.
                    HotkeyPackInstaller.restoreOne(this, tablePath());
                    message = R.string.hotkey_editor_reset_done;
                }
            } catch (IOException e) {
                Log.e(TAG, "could not save the hotkeys", e);
                message = R.string.hotkey_editor_save_failed;
            }
            final int result = message;
            handler.post(() -> {
                saveButton.setEnabled(true);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                if (table != null) {
                    model.looseIsOurs = true;
                }
            });
        }, "UnitHotkeySave").start();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.hotkey_editor_reset)
            .setMessage(R.string.unit_hotkeys_reset_confirm)
            .setPositiveButton(R.string.hotkey_editor_reset, (d, w) -> reset())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void reset() {
        boolean ours = model != null && model.looseIsOurs;
        new Thread(() -> {
            int message = R.string.hotkey_editor_reset_done;
            try {
                if (ours) {
                    HotkeyPackInstaller.restoreOne(this, tablePath());
                }
            } catch (IOException e) {
                Log.e(TAG, "could not reset the hotkeys", e);
                message = R.string.hotkey_editor_save_failed;
            }
            final int result = message;
            handler.post(() -> {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                statusView.setText(R.string.unit_hotkeys_loading);
                load();
            });
        }, "UnitHotkeyReset").start();
    }
}
