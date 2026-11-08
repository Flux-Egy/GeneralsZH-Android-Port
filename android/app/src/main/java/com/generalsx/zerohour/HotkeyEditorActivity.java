// GeneralsX @feature Flux Build 03/11/2026 In-app editor for the game's command keys.
//
// What it can change, and why it is exactly this: the engine's MetaMap is built from
// Data\<language>\CommandMap.ini (96 commands: Stop, Select All, control groups, camera, beacons,
// chat, screenshots...), then Data\INI\CommandMap.ini is loaded over it, entry by entry
// (GameEngine.cpp, MetaMap::parseMetaMap -> getMetaMapRec reuses the existing record). So the editor
// writes only the commands the player changed, as a loose Data/INI/CommandMap.ini, and every other
// key stays the game's. The defaults it shows are read from the game's own language archive.
//
// What it cannot change: Attack Move, Guard, Scatter, Deploy, build and General power buttons. Those
// are control-bar buttons whose key is the letter after '&' in the button's label (generals.csf);
// the editor says so and points to hotkey packs. CommandMap.ini is not part of the INI checksum
// (TheMetaMap is initialised without an Xfer), so edits never affect online matches.
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
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class HotkeyEditorActivity extends Activity {
    private static final String TAG = "HotkeyEditor";

    /** The commands offered, grouped as the screen shows them: engine name, label, group. */
    private static final Object[][] COMMANDS = {
        { "STOP", R.string.hk_cmd_stop, R.string.hk_group_units },
        { "CREATE_FORMATION", R.string.hk_cmd_create_formation, R.string.hk_group_units },
        { "ALL_CHEER", R.string.hk_cmd_cheer, R.string.hk_group_units },
        { "PLACE_BEACON", R.string.hk_cmd_place_beacon, R.string.hk_group_units },
        { "DELETE_BEACON", R.string.hk_cmd_delete_beacon, R.string.hk_group_units },
        { "SELECT_ALL", R.string.hk_cmd_select_all, R.string.hk_group_selection },
        { "SELECT_MATCHING_UNITS", R.string.hk_cmd_select_matching, R.string.hk_group_selection },
        { "SELECT_NEXT_UNIT", R.string.hk_cmd_next_unit, R.string.hk_group_selection },
        { "SELECT_PREV_UNIT", R.string.hk_cmd_prev_unit, R.string.hk_group_selection },
        { "SELECT_NEXT_WORKER", R.string.hk_cmd_next_worker, R.string.hk_group_selection },
        { "SELECT_PREV_WORKER", R.string.hk_cmd_prev_worker, R.string.hk_group_selection },
        { "SELECT_HERO", R.string.hk_cmd_select_hero, R.string.hk_group_selection },
        { "VIEW_COMMAND_CENTER", R.string.hk_cmd_view_command_center, R.string.hk_group_selection },
        { "VIEW_LAST_RADAR_EVENT", R.string.hk_cmd_last_radar_event, R.string.hk_group_interface },
        { "DIPLOMACY", R.string.hk_cmd_diplomacy, R.string.hk_group_interface },
        { "CHAT_ALLIES", R.string.hk_cmd_chat_allies, R.string.hk_group_interface },
        { "CHAT_EVERYONE", R.string.hk_cmd_chat_everyone, R.string.hk_group_interface },
        { "OPTIONS", R.string.hk_cmd_options, R.string.hk_group_interface },
        { "TOGGLE_CONTROL_BAR", R.string.hk_cmd_toggle_control_bar, R.string.hk_group_interface },
        { "TAKE_SCREENSHOT", R.string.hk_cmd_screenshot, R.string.hk_group_interface },
        { "TOGGLE_FAST_FORWARD_REPLAY", R.string.hk_cmd_replay_fast_forward, R.string.hk_group_interface },
        { "CAMERA_RESET", R.string.hk_cmd_camera_reset, R.string.hk_group_camera },
        { "BEGIN_CAMERA_ROTATE_LEFT", R.string.hk_cmd_camera_rotate_left, R.string.hk_group_camera },
        { "BEGIN_CAMERA_ROTATE_RIGHT", R.string.hk_cmd_camera_rotate_right, R.string.hk_group_camera },
        { "BEGIN_CAMERA_ZOOM_IN", R.string.hk_cmd_camera_zoom_in, R.string.hk_group_camera },
        { "BEGIN_CAMERA_ZOOM_OUT", R.string.hk_cmd_camera_zoom_out, R.string.hk_group_camera },
    };

    /** Keys the picker offers, in the engine's names (MetaEvent.h KeyNames). */
    private static final String[] KEYS = {
        "KEY_A", "KEY_B", "KEY_C", "KEY_D", "KEY_E", "KEY_F", "KEY_G", "KEY_H", "KEY_I", "KEY_J",
        "KEY_K", "KEY_L", "KEY_M", "KEY_N", "KEY_O", "KEY_P", "KEY_Q", "KEY_R", "KEY_S", "KEY_T",
        "KEY_U", "KEY_V", "KEY_W", "KEY_X", "KEY_Y", "KEY_Z",
        "KEY_1", "KEY_2", "KEY_3", "KEY_4", "KEY_5", "KEY_6", "KEY_7", "KEY_8", "KEY_9", "KEY_0",
        "KEY_F1", "KEY_F2", "KEY_F3", "KEY_F4", "KEY_F5", "KEY_F6", "KEY_F7", "KEY_F8", "KEY_F9",
        "KEY_F10", "KEY_F11", "KEY_F12",
        "KEY_SPACE", "KEY_TAB", "KEY_ENTER", "KEY_BACKSPACE", "KEY_ESC", "KEY_DEL", "KEY_INS",
        "KEY_HOME", "KEY_END", "KEY_PGUP", "KEY_PGDN", "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT",
        "KEY_MINUS", "KEY_EQUAL", "KEY_LBRACKET", "KEY_RBRACKET", "KEY_SEMICOLON", "KEY_APOSTROPHE",
        "KEY_TICK", "KEY_BACKSLASH", "KEY_COMMA", "KEY_PERIOD", "KEY_SLASH",
        "KEY_KP0", "KEY_KP1", "KEY_KP2", "KEY_KP3", "KEY_KP4", "KEY_KP5", "KEY_KP6", "KEY_KP7",
        "KEY_KP8", "KEY_KP9", "KEY_KPPLUS", "KEY_KPMINUS", "KEY_KPSTAR", "KEY_KPSLASH",
        "KEY_KPENTER", "KEY_KPDEL",
    };

    /** One CommandMap entry; a field the file did not set stays null (it keeps the earlier value). */
    static final class Rec {
        final String name;
        String key;
        String modifiers;
        String transition;
        String useableIn;
        String category;
        String description;
        String displayName;

        Rec(String name) {
            this.name = name;
        }

        Rec copy() {
            Rec r = new Rec(name);
            r.key = key;
            r.modifiers = modifiers;
            r.transition = transition;
            r.useableIn = useableIn;
            r.category = category;
            r.description = description;
            r.displayName = displayName;
            return r;
        }

        /** Applies the fields another file set, as the engine's INI_LOAD_OVERWRITE does. */
        void overlay(Rec o) {
            if (o.key != null) key = o.key;
            if (o.modifiers != null) modifiers = o.modifiers;
            if (o.transition != null) transition = o.transition;
            if (o.useableIn != null) useableIn = o.useableIn;
            if (o.category != null) category = o.category;
            if (o.description != null) description = o.description;
            if (o.displayName != null) displayName = o.displayName;
        }

        String key() { return key != null ? key : "KEY_NONE"; }
        String modifiers() { return modifiers != null ? modifiers : "NONE"; }
        String transition() { return transition != null ? transition : "DOWN"; }

        boolean sameBinding(Rec o) {
            return key().equals(o.key()) && modifiers().equals(o.modifiers());
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private TextView statusView;
    private MaterialButton saveButton;

    private File gameRoot;
    private String sourceLabel;
    private final Map<String, Rec> defaults = new LinkedHashMap<>();
    private final Map<String, Rec> current = new LinkedHashMap<>();
    private final Map<String, UiKit.Row> rows = new HashMap<>();

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.hotkey_editor_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        UiKit.appBar(root, getString(R.string.setup_window_title), getString(R.string.hotkey_editor_title),
            0, null, null);

        FrameLayout host = new FrameLayout(this);
        root.addView(host, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        page = UiKit.scrollingPage(host);
        statusView = UiKit.supporting(page, getString(R.string.hotkey_editor_loading));

        setContentView(root);
        InsetUtil.applySafeInsets(root);
        load();
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        new Thread(() -> {
            String error = null;
            Loaded loaded = null;
            try {
                loaded = loadModel();
            } catch (IOException e) {
                Log.w(TAG, "could not load the command map", e);
                error = e.getMessage();
            }
            final String failure = error;
            final Loaded result = loaded;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failure != null) {
                    statusView.setText(failure);
                    return;
                }
                // Swapped in on the UI thread, which is the only one that reads them.
                gameRoot = result.gameRoot;
                sourceLabel = result.source;
                defaults.clear();
                defaults.putAll(result.defaults);
                current.clear();
                current.putAll(result.current);
                buildUi();
            });
        }, "HotkeyEditorLoad").start();
    }

    private static final class Loaded {
        File gameRoot;
        String source;
        final Map<String, Rec> defaults = new LinkedHashMap<>();
        final Map<String, Rec> current = new LinkedHashMap<>();
    }

    private Loaded loadModel() throws IOException {
        Loaded out = new Loaded();
        File gameRoot;
        try {
            gameRoot = HotkeyPackInstaller.gameRoot(this);
        } catch (HotkeyPackInstaller.ImportException e) {
            throw new IOException(getString(e.messageRes));
        }
        out.gameRoot = gameRoot;
        String language = HotkeyPackInstaller.languageDir(this, gameRoot);
        String[] source = new String[1];
        String text = readDefaultCommandMap(gameRoot, language, source);
        if (text == null && !language.equalsIgnoreCase("english")) {
            text = readDefaultCommandMap(gameRoot, "English", source);
        }
        if (text == null) {
            throw new IOException(getString(R.string.hotkey_editor_no_defaults, language));
        }
        out.source = source[0];
        Map<String, Rec> defaults = out.defaults;
        Map<String, Rec> current = out.current;
        defaults.putAll(parse(text));
        for (Rec r : defaults.values()) {
            current.put(r.name, r.copy());
        }
        // The player's earlier edits (or a pack's command map): applied over the defaults, as the
        // engine applies them.
        File loose = new File(gameRoot, HotkeyPackInstaller.resolveExisting(gameRoot, HotkeyPackInstaller.COMMAND_MAP));
        if (loose.isFile()) {
            for (Rec o : parse(new String(Files.readAllBytes(loose.toPath()), StandardCharsets.ISO_8859_1)).values()) {
                Rec r = current.get(o.name);
                if (r == null) {
                    current.put(o.name, o.copy());
                } else {
                    r.overlay(o);
                }
            }
        }
        return out;
    }

    /**
     * Data/<language>/CommandMap.ini as the engine would find it: a loose file first, else the
     * archives of the game folder in name order (the first one that has it wins, as on a PC).
     */
    private static String readDefaultCommandMap(File gameRoot, String language, String[] sourceLabel)
            throws IOException {
        String rel = "Data/" + language + "/CommandMap.ini";
        File loose = new File(gameRoot, HotkeyPackInstaller.resolveExisting(gameRoot, rel));
        if (loose.isFile()) {
            sourceLabel[0] = rel;
            return new String(Files.readAllBytes(loose.toPath()), StandardCharsets.ISO_8859_1);
        }
        // GeneralsX @feature Flux Build 05/11/2026 Every archive the engine mounts, named .big or not.
        File[] archives = gameRoot.listFiles(BigArchive::isMountedArchive);
        if (archives == null) {
            return null;
        }
        Arrays.sort(archives, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        String inArchive = "Data\\" + language + "\\CommandMap.ini";
        for (File archive : archives) {
            try {
                List<BigArchive.Entry> entries = BigArchive.list(archive);
                BigArchive.Entry e = BigArchive.find(entries, inArchive);
                if (e != null) {
                    sourceLabel[0] = archive.getName() + " (" + inArchive + ")";
                    return new String(BigArchive.read(archive, e), StandardCharsets.ISO_8859_1);
                }
            } catch (IOException e) {
                Log.w(TAG, "skipping unreadable archive " + archive.getName(), e);
            }
        }
        return null;
    }

    static Map<String, Rec> parse(String text) {
        Map<String, Rec> out = new LinkedHashMap<>();
        Rec cur = null;
        for (String raw : text.split("\r?\n")) {
            int comment = raw.indexOf(';');
            String line = (comment >= 0 ? raw.substring(0, comment) : raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] words = line.split("\\s+");
            if (words.length >= 2 && words[0].equalsIgnoreCase("CommandMap")) {
                cur = new Rec(words[1].toUpperCase(Locale.ROOT));
                continue;
            }
            if (cur == null) {
                continue;
            }
            if (line.equalsIgnoreCase("End")) {
                out.put(cur.name, cur);
                cur = null;
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String field = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (field.toLowerCase(Locale.ROOT)) {
                case "key": cur.key = value.toUpperCase(Locale.ROOT); break;
                case "modifiers": cur.modifiers = value.toUpperCase(Locale.ROOT); break;
                case "transition": cur.transition = value.toUpperCase(Locale.ROOT); break;
                case "useablein": cur.useableIn = value; break;
                case "category": cur.category = value; break;
                case "description": cur.description = value; break;
                case "displayname": cur.displayName = value; break;
                default: break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ screen

    private void buildUi() {
        page.removeAllViews();

        // GeneralsX @feature Flux Build 05/11/2026 This screen is the command map only; the keys of units,
        // buildings and the Dozers' build menus are in Unit & Building Hotkeys, one tap away.
        LinearLayout unitsLink = UiKit.card(page);
        UiKit.listRow(unitsLink, R.drawable.ic_gzh_sliders, getString(R.string.unit_hotkeys_btn),
            getString(R.string.hotkey_editor_units_link_desc),
            () -> startActivity(new android.content.Intent(this, UnitHotkeyEditorActivity.class)));

        LinearLayout intro = UiKit.card(page);
        UiKit.sectionHeader(intro, R.drawable.ic_gzh_sliders, getString(R.string.hotkey_editor_title), false);
        UiKit.helpText(intro, getString(R.string.hotkey_editor_help));
        UiKit.helpText(intro, getString(R.string.hotkey_editor_buttons_note));
        UiKit.helpText(intro, getString(R.string.hotkey_editor_touch_note));
        statusView = UiKit.supporting(intro, "");

        LinearLayout actions = UiKit.buttonRow(intro);
        saveButton = UiKit.button(actions, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_save,
            getString(R.string.hotkey_editor_save), this::save);
        UiKit.share(saveButton, true);
        UiKit.share(UiKit.button(actions, UiKit.BTN_OUTLINE, R.drawable.ic_gzh_refresh,
            getString(R.string.hotkey_editor_reset), this::confirmReset), false);

        rows.clear();
        LinearLayout card = null;
        int group = 0;
        for (Object[] cmd : COMMANDS) {
            String name = (String) cmd[0];
            if (!current.containsKey(name)) {
                continue; // a mod's command map without this command
            }
            if ((int) cmd[2] != group || card == null) {
                group = (int) cmd[2];
                card = UiKit.card(page);
                UiKit.sectionHeader(card, R.drawable.ic_gzh_chip, getString(group), false);
            }
            final int label = (int) cmd[1];
            rows.put(name, UiKit.listRow(card, 0, getString(label), "", () -> openPicker(name, label)));
        }
        refresh();
    }

    private void refresh() {
        int changed = 0;
        for (Rec r : current.values()) {
            Rec d = defaults.get(r.name);
            if (d == null || !d.sameBinding(r)) {
                changed++;
            }
        }
        statusView.setText(getString(R.string.hotkey_editor_status, sourceLabel, changed));
        for (Map.Entry<String, UiKit.Row> e : rows.entrySet()) {
            Rec r = current.get(e.getKey());
            Rec d = defaults.get(e.getKey());
            StringBuilder line = new StringBuilder(describe(r));
            if (d != null && !d.sameBinding(r)) {
                line.append("  ·  ").append(getString(R.string.hotkey_editor_changed, describe(d)));
            }
            List<String> clashes = conflicts(r);
            if (!clashes.isEmpty()) {
                line.append('\n').append(getString(R.string.hotkey_editor_conflict, String.join(", ", clashes)));
            }
            e.getValue().supporting.setText(line);
            e.getValue().supporting.setTextColor(UiKit.color(this,
                clashes.isEmpty() ? R.color.gzh_on_surface_variant : R.color.gzh_status_warn));
        }
    }

    /** Other commands the game would fire on the same key press. */
    private List<String> conflicts(Rec r) {
        List<String> out = new ArrayList<>();
        if ("KEY_NONE".equals(r.key()) || !"DOWN".equals(r.transition())) {
            return out;
        }
        for (Rec o : current.values()) {
            if (o != r && o.sameBinding(r) && "DOWN".equals(o.transition())
                    && usableTogether(r, o)) {
                out.add(labelFor(o.name));
            }
        }
        return out;
    }

    private static boolean usableTogether(Rec a, Rec b) {
        String ua = a.useableIn == null ? "" : a.useableIn.toUpperCase(Locale.ROOT);
        String ub = b.useableIn == null ? "" : b.useableIn.toUpperCase(Locale.ROOT);
        for (String scope : new String[] { "GAME", "SHELL", "OBSERVER", "EVERYWHERE" }) {
            if (ua.contains(scope) && ub.contains(scope)) {
                return true;
            }
        }
        return ua.contains("EVERYWHERE") || ub.contains("EVERYWHERE");
    }

    private String labelFor(String name) {
        for (Object[] cmd : COMMANDS) {
            if (cmd[0].equals(name)) {
                return getString((int) cmd[1]);
            }
        }
        return name;
    }

    private String describe(Rec r) {
        if ("KEY_NONE".equals(r.key())) {
            return getString(R.string.hotkey_editor_unassigned);
        }
        StringBuilder s = new StringBuilder();
        String mods = r.modifiers();
        if (mods.contains("CTRL")) s.append(getString(R.string.touch_modifier_ctrl)).append('+');
        if (mods.contains("ALT")) s.append(getString(R.string.touch_modifier_alt)).append('+');
        if (mods.contains("SHIFT")) s.append(getString(R.string.touch_modifier_shift)).append('+');
        return s.append(keyLabel(r.key())).toString();
    }

    static String keyLabel(String key) {
        String k = key.startsWith("KEY_") ? key.substring(4) : key;
        if (k.startsWith("KP") && k.length() > 2) {
            String rest = k.substring(2);
            switch (rest) {
                case "PLUS": return "Num +";
                case "MINUS": return "Num -";
                case "STAR": return "Num *";
                case "SLASH": return "Num /";
                case "ENTER": return "Num Enter";
                case "DEL": return "Num Del";
                default: return "Num " + rest;
            }
        }
        switch (k) {
            case "ESC": return "Esc";
            case "BACKSPACE": return "Backspace";
            case "ENTER": return "Enter";
            case "SPACE": return "Space";
            case "TAB": return "Tab";
            case "MINUS": return "-";
            case "EQUAL": return "=";
            case "LBRACKET": return "[";
            case "RBRACKET": return "]";
            case "SEMICOLON": return ";";
            case "APOSTROPHE": return "'";
            case "TICK": return "`";
            case "BACKSLASH": return "\\";
            case "COMMA": return ",";
            case "PERIOD": return ".";
            case "SLASH": return "/";
            case "UP": return "↑";
            case "DOWN": return "↓";
            case "LEFT": return "←";
            case "RIGHT": return "→";
            case "HOME": return "Home";
            case "END": return "End";
            case "PGUP": return "PgUp";
            case "PGDN": return "PgDn";
            case "INS": return "Ins";
            case "DEL": return "Del";
            default: return k;
        }
    }

    // ------------------------------------------------------------------ picker

    private void openPicker(String name, int label) {
        Rec r = current.get(name);
        if (r == null) {
            return;
        }
        final String[] key = { r.key() };
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dim(this, R.dimen.gzh_item_gap);
        content.setPadding(pad, pad / 2, pad, 0);

        TextView hint = new TextView(this);
        hint.setText(R.string.hotkey_editor_picker_hint);
        hint.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        content.addView(hint);

        LinearLayout modRow = new LinearLayout(this);
        modRow.setOrientation(LinearLayout.HORIZONTAL);
        CheckBox ctrl = modifierBox(modRow, R.string.touch_modifier_ctrl, r.modifiers().contains("CTRL"));
        CheckBox alt = modifierBox(modRow, R.string.touch_modifier_alt, r.modifiers().contains("ALT"));
        CheckBox shift = modifierBox(modRow, R.string.touch_modifier_shift, r.modifiers().contains("SHIFT"));
        content.addView(modRow);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(10);
        Map<String, TextView> cells = new HashMap<>();
        Runnable paint = () -> {
            for (Map.Entry<String, TextView> c : cells.entrySet()) {
                boolean on = c.getKey().equals(key[0]);
                GradientDrawable bg = (GradientDrawable) c.getValue().getBackground();
                bg.setColor(UiKit.color(this, on ? R.color.gzh_primary : R.color.gzh_surface_container_high));
                c.getValue().setTextColor(UiKit.color(this, on ? R.color.gzh_on_primary : R.color.gzh_on_surface));
            }
        };
        int cellMin = UiKit.dp(this, 44);
        for (String k : KEYS) {
            TextView cell = new TextView(this);
            cell.setText(keyLabel(k));
            cell.setGravity(Gravity.CENTER);
            cell.setTypeface(Typeface.DEFAULT_BOLD);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            cell.setMinWidth(cellMin);
            cell.setMinHeight(UiKit.dp(this, 38));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiKit.dp(this, 8));
            cell.setBackground(bg);
            cell.setOnClickListener(v -> {
                key[0] = k;
                paint.run();
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            int m = UiKit.dp(this, 3);
            lp.setMargins(m, m, m, m);
            grid.addView(cell, lp);
            cells.put(k, cell);
        }
        paint.run();
        android.widget.HorizontalScrollView hscroll = new android.widget.HorizontalScrollView(this);
        hscroll.addView(grid);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(hscroll);
        content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(getString(label))
            .setView(content)
            .setPositiveButton(R.string.hotkey_editor_apply, (d, w) ->
                apply(name, key[0], modifierName(ctrl.isChecked(), alt.isChecked(), shift.isChecked())))
            .setNeutralButton(R.string.hotkey_editor_default, (d, w) -> {
                Rec def = defaults.get(name);
                if (def != null) {
                    apply(name, def.key(), def.modifiers());
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        // A hardware keyboard picks the key (and the modifiers held with it) directly.
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            String picked = engineKeyFor(keyCode);
            if (picked == null) {
                return false;
            }
            key[0] = picked;
            ctrl.setChecked(event.isCtrlPressed());
            alt.setChecked(event.isAltPressed());
            shift.setChecked(event.isShiftPressed());
            paint.run();
            return true;
        });
        dialog.show();
    }

    private CheckBox modifierBox(LinearLayout row, int label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setChecked(checked);
        box.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(UiKit.dp(this, 16));
        row.addView(box, lp);
        return box;
    }

    /** The engine's modifier names (MetaEvent.h ModifierNames). */
    static String modifierName(boolean ctrl, boolean alt, boolean shift) {
        if (ctrl && alt && shift) return "SHIFT_ALT_CTRL";
        if (ctrl && alt) return "CTRL_ALT";
        if (shift && ctrl) return "SHIFT_CTRL";
        if (shift && alt) return "SHIFT_ALT";
        if (ctrl) return "CTRL";
        if (alt) return "ALT";
        if (shift) return "SHIFT";
        return "NONE";
    }

    private static String engineKeyFor(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return "KEY_" + (char) ('A' + keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return "KEY_" + (char) ('0' + keyCode - KeyEvent.KEYCODE_0);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "KEY_F" + (1 + keyCode - KeyEvent.KEYCODE_F1);
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return "KEY_KP" + (keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_SPACE: return "KEY_SPACE";
            case KeyEvent.KEYCODE_TAB: return "KEY_TAB";
            case KeyEvent.KEYCODE_ENTER: return "KEY_ENTER";
            case KeyEvent.KEYCODE_DEL: return "KEY_BACKSPACE";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "KEY_DEL";
            case KeyEvent.KEYCODE_ESCAPE: return "KEY_ESC";
            case KeyEvent.KEYCODE_INSERT: return "KEY_INS";
            case KeyEvent.KEYCODE_MOVE_HOME: return "KEY_HOME";
            case KeyEvent.KEYCODE_MOVE_END: return "KEY_END";
            case KeyEvent.KEYCODE_PAGE_UP: return "KEY_PGUP";
            case KeyEvent.KEYCODE_PAGE_DOWN: return "KEY_PGDN";
            case KeyEvent.KEYCODE_DPAD_UP: return "KEY_UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "KEY_DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "KEY_LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "KEY_RIGHT";
            case KeyEvent.KEYCODE_MINUS: return "KEY_MINUS";
            case KeyEvent.KEYCODE_EQUALS: return "KEY_EQUAL";
            case KeyEvent.KEYCODE_LEFT_BRACKET: return "KEY_LBRACKET";
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return "KEY_RBRACKET";
            case KeyEvent.KEYCODE_SEMICOLON: return "KEY_SEMICOLON";
            case KeyEvent.KEYCODE_APOSTROPHE: return "KEY_APOSTROPHE";
            case KeyEvent.KEYCODE_GRAVE: return "KEY_TICK";
            case KeyEvent.KEYCODE_BACKSLASH: return "KEY_BACKSLASH";
            case KeyEvent.KEYCODE_COMMA: return "KEY_COMMA";
            case KeyEvent.KEYCODE_PERIOD: return "KEY_PERIOD";
            case KeyEvent.KEYCODE_SLASH: return "KEY_SLASH";
            case KeyEvent.KEYCODE_NUMPAD_ADD: return "KEY_KPPLUS";
            case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: return "KEY_KPMINUS";
            case KeyEvent.KEYCODE_NUMPAD_MULTIPLY: return "KEY_KPSTAR";
            case KeyEvent.KEYCODE_NUMPAD_DIVIDE: return "KEY_KPSLASH";
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return "KEY_KPENTER";
            case KeyEvent.KEYCODE_NUMPAD_DOT: return "KEY_KPDEL";
            default: return null;
        }
    }

    /** Sets a binding; a BEGIN_ command's END_ partner (key release) follows it. */
    private void apply(String name, String key, String modifiers) {
        Rec r = current.get(name);
        if (r == null) {
            return;
        }
        r.key = key;
        r.modifiers = modifiers;
        if (name.startsWith("BEGIN_")) {
            Rec end = current.get("END_" + name.substring(6));
            if (end != null) {
                end.key = key;
                end.modifiers = modifiers;
            }
        }
        refresh();
    }

    // ------------------------------------------------------------------ save / reset

    /** The changed commands only, each written in full so the record does not depend on order. */
    private String generate() {
        StringBuilder out = new StringBuilder();
        out.append(";//////////////////////////////////////////////////////////////////////////////\n");
        out.append("; CommandMap.ini -- written by the Flux launcher's hotkey editor.\n");
        out.append("; Loaded over the game's Data\\<language>\\CommandMap.ini; only changed commands are here.\n");
        out.append(";//////////////////////////////////////////////////////////////////////////////\n\n");
        int count = 0;
        for (Rec r : current.values()) {
            Rec d = defaults.get(r.name);
            if (d != null && d.sameBinding(r)) {
                continue;
            }
            count++;
            out.append("CommandMap ").append(r.name).append('\n');
            out.append("  Key = ").append(r.key()).append('\n');
            out.append("  Transition = ").append(r.transition()).append('\n');
            out.append("  Modifiers = ").append(r.modifiers()).append('\n');
            if (r.useableIn != null) out.append("  UseableIn = ").append(r.useableIn).append('\n');
            if (r.category != null) out.append("  Category = ").append(r.category).append('\n');
            if (r.description != null) out.append("  Description = ").append(r.description).append('\n');
            if (r.displayName != null) out.append("  DisplayName = ").append(r.displayName).append('\n');
            out.append("End\n\n");
        }
        return count == 0 ? null : out.toString();
    }

    private void save() {
        String text = generate();
        saveButton.setEnabled(false);
        new Thread(() -> {
            int message;
            try {
                if (text == null) {
                    HotkeyPackInstaller.restoreOne(this, HotkeyPackInstaller.COMMAND_MAP);
                    message = R.string.hotkey_editor_reset_done;
                } else {
                    HotkeyPackInstaller.writeManaged(this, gameRoot, HotkeyPackInstaller.COMMAND_MAP,
                        text.getBytes(StandardCharsets.ISO_8859_1));
                    message = R.string.hotkey_editor_saved;
                }
            } catch (IOException e) {
                Log.e(TAG, "could not save the command map", e);
                message = R.string.hotkey_editor_save_failed;
            }
            final int result = message;
            handler.post(() -> {
                saveButton.setEnabled(true);
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
            });
        }, "HotkeyEditorSave").start();
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.hotkey_editor_reset)
            .setMessage(R.string.hotkey_editor_reset_confirm)
            .setPositiveButton(R.string.hotkey_editor_reset, (d, w) -> reset())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void reset() {
        new Thread(() -> {
            int message = R.string.hotkey_editor_reset_done;
            try {
                HotkeyPackInstaller.restoreOne(this, HotkeyPackInstaller.COMMAND_MAP);
            } catch (IOException e) {
                Log.e(TAG, "could not reset the command map", e);
                message = R.string.hotkey_editor_save_failed;
            }
            final int result = message;
            handler.post(() -> {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                load();
            });
        }, "HotkeyEditorReset").start();
    }
}
