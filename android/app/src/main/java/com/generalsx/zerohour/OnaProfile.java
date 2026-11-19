/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Adapted from ONA Launcher 2 "ona-controls" (model/OverlayProfile.kt, model/OverlayKey.kt,
 * profile/DefaultProfiles.kt), GPL-3.0: the profile model, its JSON format ("ona-controls-profile-1")
 * and the key names, ported to Java. Only the keyboard keys are carried over: the game reads them
 * straight from SDL (OnaOverlayView). ONA's Shizuku, Accessibility, root, TCP and floating-window
 * backends are not part of this port.
 */
package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12201 ONA-style controls (owner): a Winlator-like layout of
 * buttons, button rows, D-pads and analog sticks drawn inside the game's own window (OnaOverlayView), chosen in
 * the launcher instead of the Flux touch-button panel. This class is the data: profiles, their ONA-compatible
 * JSON, the built-in profiles and where they are kept (app-private files, never shared storage).
 *
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 Fully customizable controls (owner, Phase 1): format 2
 * ("flux-controls-profile-2") adds per control an action per slot (a key, a chord such as Ctrl+1, or a macro of up
 * to 32 steps), a behaviour (hold, tap, toggle), rotation, its own opacity, a label colour and a built-in icon.
 * A format-2 file still carries the format-1 fields, and a format-1 (ONA) file reads as before; "Export for ONA"
 * writes format 1 only. Built-in icons only (no images in a profile, owner).
 */
final class OnaProfile {

    static final String FORMAT = "flux-controls-profile-2";
    static final String FORMAT_ONA = "ona-controls-profile-1";

    static final String PREFS = "ona_controls";
    static final String PREF_STYLE = "control_style";      // "flux" (default) or "ona"
    static final String PREF_ACTIVE = "active_profile";
    static final String STYLE_FLUX = "flux";
    static final String STYLE_ONA = "ona";
    private static final String DIR = "ona_profiles";

    static final String BUILTIN_RTS = "flux_rts";
    static final String BUILTIN_MINIMAL = "flux_minimal";

    // Limits for what a file may hold (a hand-edited or shared file is read with these).
    static final int MAX_ELEMENTS = 200;
    static final int MAX_SLOTS = 16;
    static final int MAX_ROW_CELLS = 12;
    static final int MAX_CHORD = 8;
    static final int MAX_STEPS = 32;
    static final int MAX_WAIT_MS = 2000;
    static final int MAX_TEXT = 24;

    // ------------------------------------------------------------------ element types

    static final int TYPE_BUTTON = 0;
    static final int TYPE_BUTTON_ROW = 1;
    static final int TYPE_DPAD = 2;
    static final int TYPE_STICK = 3;
    static final int TYPE_TRACKPAD = 4; // kept from imports; draws, sends nothing (mouse output is Phase 2)
    private static final String[] TYPE_NAMES = { "BUTTON", "BUTTON_ROW", "DPAD", "STICK", "TRACKPAD" };

    static final int SHAPE_CIRCLE = 0;
    static final int SHAPE_SQUARE = 1;
    static final int SHAPE_RECT = 2;
    static final int SHAPE_PILL = 3;
    private static final String[] SHAPE_NAMES = { "CIRCLE", "SQUARE", "RECT", "PILL" };

    /** How a button sends its action: held while the finger is down, one short press, or on / off per tap. */
    static final int BEHAVIOR_HOLD = 0;
    static final int BEHAVIOR_TAP = 1;
    static final int BEHAVIOR_TOGGLE = 2;
    private static final String[] BEHAVIOR_NAMES = { "HOLD", "TAP", "TOGGLE" };

    // ------------------------------------------------------------------ keys

    /** One bindable key: its ONA name (JSON), the label drawn on the control, the Android key code (0 = none). */
    static final class Key {
        final String name;
        final String label;
        final int code;
        final int group;

        Key(String name, String label, int code, int group) {
            this.name = name;
            this.label = label;
            this.code = code;
            this.group = group;
        }
    }

    // Key picker groups (OnaElementSheet draws one grid per group, in this order).
    static final int GROUP_COMMON = 0;
    static final int GROUP_NUMBERS = 1;
    static final int GROUP_LETTERS = 2;
    static final int GROUP_FKEYS = 3;
    static final int GROUP_NAV = 4;
    static final int GROUP_NUMPAD = 5;
    static final int GROUP_SYMBOLS = 6;
    static final int GROUP_COUNT = 7;

    static final Key NONE = new Key("NONE", "", 0, -1);
    private static final Map<String, Key> KEYS = new LinkedHashMap<>();
    /** The keys offered in the editor's key picker, in this order. */
    static final List<Key> PICKER = new ArrayList<>();

    private static void key(String name, String label, int code, int group) {
        Key k = new Key(name, label, code, group);
        KEYS.put(name, k);
        PICKER.add(k);
    }

    static {
        KEYS.put(NONE.name, NONE);
        key("L_CTRL", "CTRL", KeyEvent.KEYCODE_CTRL_LEFT, GROUP_COMMON);
        key("L_SHIFT", "SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT, GROUP_COMMON);
        key("L_ALT", "ALT", KeyEvent.KEYCODE_ALT_LEFT, GROUP_COMMON);
        key("ESC", "ESC", KeyEvent.KEYCODE_ESCAPE, GROUP_COMMON);
        key("ENTER", "ENTER", KeyEvent.KEYCODE_ENTER, GROUP_COMMON);
        key("SPACE", "SPACE", KeyEvent.KEYCODE_SPACE, GROUP_COMMON);
        key("TAB", "TAB", KeyEvent.KEYCODE_TAB, GROUP_COMMON);
        key("BKSP", "BKSP", KeyEvent.KEYCODE_DEL, GROUP_COMMON);
        key("DEL", "DEL", KeyEvent.KEYCODE_FORWARD_DEL, GROUP_COMMON);
        key("R_CTRL", "R CTRL", KeyEvent.KEYCODE_CTRL_RIGHT, GROUP_COMMON);
        key("R_SHIFT", "R SHIFT", KeyEvent.KEYCODE_SHIFT_RIGHT, GROUP_COMMON);
        key("R_ALT", "R ALT", KeyEvent.KEYCODE_ALT_RIGHT, GROUP_COMMON);
        key("CAPS", "CAPS", KeyEvent.KEYCODE_CAPS_LOCK, GROUP_COMMON);
        for (int i = 1; i <= 10; i++) {
            int n = i % 10;
            key("K" + n, Integer.toString(n), KeyEvent.KEYCODE_0 + n, GROUP_NUMBERS);
        }
        for (char c : "QWERTYUIOPASDFGHJKLZXCVBNM".toCharArray()) {
            key(String.valueOf(c), String.valueOf(c), KeyEvent.KEYCODE_A + (c - 'A'), GROUP_LETTERS);
        }
        for (int i = 1; i <= 12; i++) {
            key("F" + i, "F" + i, KeyEvent.KEYCODE_F1 + i - 1, GROUP_FKEYS);
        }
        key("UP", "↑", KeyEvent.KEYCODE_DPAD_UP, GROUP_NAV);
        key("DOWN", "↓", KeyEvent.KEYCODE_DPAD_DOWN, GROUP_NAV);
        key("LEFT", "←", KeyEvent.KEYCODE_DPAD_LEFT, GROUP_NAV);
        key("RIGHT", "→", KeyEvent.KEYCODE_DPAD_RIGHT, GROUP_NAV);
        key("INSERT", "INS", KeyEvent.KEYCODE_INSERT, GROUP_NAV);
        key("HOME", "HOME", KeyEvent.KEYCODE_MOVE_HOME, GROUP_NAV);
        key("END", "END", KeyEvent.KEYCODE_MOVE_END, GROUP_NAV);
        key("PAGE_UP", "PGUP", KeyEvent.KEYCODE_PAGE_UP, GROUP_NAV);
        key("PAGE_DOWN", "PGDN", KeyEvent.KEYCODE_PAGE_DOWN, GROUP_NAV);
        key("PAUSE", "PAUSE", KeyEvent.KEYCODE_BREAK, GROUP_NAV);
        key("SCROLL_LOCK", "SCRLK", KeyEvent.KEYCODE_SCROLL_LOCK, GROUP_NAV);
        for (int i = 0; i <= 9; i++) {
            key("NUM_" + i, "NUM " + i, KeyEvent.KEYCODE_NUMPAD_0 + i, GROUP_NUMPAD);
        }
        key("NUM_PLUS", "NUM +", KeyEvent.KEYCODE_NUMPAD_ADD, GROUP_NUMPAD);
        key("NUM_MINUS", "NUM -", KeyEvent.KEYCODE_NUMPAD_SUBTRACT, GROUP_NUMPAD);
        key("NUM_STAR", "NUM *", KeyEvent.KEYCODE_NUMPAD_MULTIPLY, GROUP_NUMPAD);
        key("NUM_SLASH", "NUM /", KeyEvent.KEYCODE_NUMPAD_DIVIDE, GROUP_NUMPAD);
        key("NUM_DOT", "NUM .", KeyEvent.KEYCODE_NUMPAD_DOT, GROUP_NUMPAD);
        key("NUM_ENTER", "NUM ENT", KeyEvent.KEYCODE_NUMPAD_ENTER, GROUP_NUMPAD);
        key("TILDE", "~", KeyEvent.KEYCODE_GRAVE, GROUP_SYMBOLS);
        key("MINUS", "-", KeyEvent.KEYCODE_MINUS, GROUP_SYMBOLS);
        key("EQUALS", "=", KeyEvent.KEYCODE_EQUALS, GROUP_SYMBOLS);
        key("L_BRACKET", "[", KeyEvent.KEYCODE_LEFT_BRACKET, GROUP_SYMBOLS);
        key("R_BRACKET", "]", KeyEvent.KEYCODE_RIGHT_BRACKET, GROUP_SYMBOLS);
        key("BACKSLASH", "\\", KeyEvent.KEYCODE_BACKSLASH, GROUP_SYMBOLS);
        key("SEMICOLON", ";", KeyEvent.KEYCODE_SEMICOLON, GROUP_SYMBOLS);
        key("APOSTROPHE", "'", KeyEvent.KEYCODE_APOSTROPHE, GROUP_SYMBOLS);
        key("COMMA", ",", KeyEvent.KEYCODE_COMMA, GROUP_SYMBOLS);
        key("PERIOD", ".", KeyEvent.KEYCODE_PERIOD, GROUP_SYMBOLS);
        key("SLASH", "/", KeyEvent.KEYCODE_SLASH, GROUP_SYMBOLS);
    }

    /** Lenient, as ONA's OverlayKey.of: mouse, gamepad and touch outputs (and unknown names) become NONE. */
    static Key keyOf(String name) {
        Key k = name == null ? null : KEYS.get(name);
        return k != null ? k : NONE;
    }

    /** Whether an imported name is something this port cannot send (mouse, gamepad, touch). */
    static boolean unsupportedKeyName(String name) {
        return name != null && !name.isEmpty() && !"NONE".equals(name) && !KEYS.containsKey(name);
    }

    static boolean isModifier(int code) {
        return code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT
            || code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT
            || code == KeyEvent.KEYCODE_ALT_LEFT || code == KeyEvent.KEYCODE_ALT_RIGHT;
    }

    // ------------------------------------------------------------------ actions

    static final int OP_TAP = 0;   // press, then let go a moment later
    static final int OP_DOWN = 1;  // press and keep
    static final int OP_UP = 2;    // let go
    static final int OP_WAIT = 3;  // wait ms
    private static final String[] OP_NAMES = { "TAP", "DOWN", "UP", "WAIT" };

    /** One macro step. */
    static final class Step {
        int op;
        Key key;
        int ms;

        Step(int op, Key key, int ms) {
            this.op = op;
            this.key = key != null ? key : NONE;
            this.ms = ms;
        }

        Step copy() {
            return new Step(op, key, ms);
        }
    }

    /**
     * What one slot sends: a chord (its keys pressed in order and let go in reverse; one key is just a key), or,
     * when {@link #macro} is not empty, a macro run once per press.
     */
    static final class Action {
        final List<Key> keys = new ArrayList<>();
        final List<Step> macro = new ArrayList<>();

        static Action of(Key... ks) {
            Action a = new Action();
            for (Key k : ks) {
                if (k != null && k.code != 0) a.keys.add(k);
            }
            return a;
        }

        boolean isMacro() {
            return !macro.isEmpty();
        }

        boolean isEmpty() {
            return keys.isEmpty() && macro.isEmpty();
        }

        /** The first key (a direction or a format-1 export of a cell), NONE when there is none. */
        Key firstKey() {
            if (!keys.isEmpty()) return keys.get(0);
            for (Step s : macro) {
                if (s.key.code != 0) return s.key;
            }
            return NONE;
        }

        Action copy() {
            Action a = new Action();
            a.keys.addAll(keys);
            for (Step s : macro) a.macro.add(s.copy());
            return a;
        }

        /** The text drawn on a control without a label: CTRL+1, or the macro's keys with "›" between. */
        String label() {
            StringBuilder b = new StringBuilder();
            if (isMacro()) {
                for (Step s : macro) {
                    if (s.op == OP_WAIT || s.op == OP_UP || s.key.code == 0) continue;
                    if (b.length() > 0) b.append('›');
                    b.append(s.key.label);
                    if (b.length() > 18) break;
                }
                return b.length() == 0 ? "M" : b.toString();
            }
            for (Key k : keys) {
                if (b.length() > 0) b.append('+');
                b.append(k.label);
            }
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ model

    /** One control. x / y = its centre as a fraction of the screen; size = the type's base size (dp) * scale. */
    static final class Element {
        String id;
        int type = TYPE_BUTTON;
        float x = 0.5f;
        float y = 0.5f;
        float scale = 1f;
        int shape = SHAPE_CIRCLE;
        /** BUTTON: slot 0. BUTTON_ROW: one per cell. DPAD / STICK: up, right, down, left (chords only). */
        final List<Action> actions = new ArrayList<>();
        int behavior = BEHAVIOR_HOLD;
        String text = "";
        /** ONA's stickOutput; only KEYS is acted on here, the others are kept so an export round-trips. */
        String stickOutput = "KEYS";
        float deadzone = 0.15f;
        boolean eightWay = true;
        float width = 1f;
        float height = 1f;
        int color;
        /** Degrees clockwise, 0..359. */
        float rotation;
        /** Its own opacity, or a negative value for the profile's. */
        float alpha = -1f;
        int labelColor;
        /** A built-in icon name (OnaIcons), "" for none. */
        String icon = "";
        /** Editor only: a locked control cannot be dragged, resized or rotated. */
        boolean locked;
        // kept for a faithful export (ONA's touch outputs), unused here
        float tapX = 0.5f;
        float tapY = 0.5f;
        float dragRadius = 0.12f;

        Element copy() {
            Element e = new Element();
            e.id = id;
            e.type = type;
            e.x = x;
            e.y = y;
            e.scale = scale;
            e.shape = shape;
            for (Action a : actions) e.actions.add(a.copy());
            e.behavior = behavior;
            e.text = text;
            e.stickOutput = stickOutput;
            e.deadzone = deadzone;
            e.eightWay = eightWay;
            e.width = width;
            e.height = height;
            e.color = color;
            e.rotation = rotation;
            e.alpha = alpha;
            e.labelColor = labelColor;
            e.icon = icon;
            e.locked = locked;
            e.tapX = tapX;
            e.tapY = tapY;
            e.dragRadius = dragRadius;
            return e;
        }

        boolean directional() {
            return type == TYPE_DPAD || type == TYPE_STICK;
        }

        /** The action in a slot (an empty one past the end). */
        Action action(int slot) {
            return slot >= 0 && slot < actions.size() ? actions.get(slot) : new Action();
        }

        /** How many slots the type has: 1 for a button, 4 for a D-pad or stick, one per cell for a row. */
        int slotCount() {
            if (directional()) return 4;
            if (type == TYPE_BUTTON_ROW) return Math.max(1, Math.min(MAX_ROW_CELLS, actions.size()));
            if (type == TYPE_BUTTON) return 1;
            return 0;
        }

        /** Makes the list exactly as long as the type needs. */
        void normalizeSlots() {
            int n = slotCount();
            while (actions.size() < n) actions.add(new Action());
            while (actions.size() > n) actions.remove(actions.size() - 1);
            if (directional()) {
                for (Action a : actions) a.macro.clear(); // directions send keys only
            }
        }

        /** Base size in dp, as ONA's ElementGeometry.sizeDp. */
        float[] sizeDp() {
            float w;
            float h;
            switch (type) {
                case TYPE_BUTTON_ROW:
                    w = 40f * Math.max(1, actions.size());
                    h = 40f;
                    break;
                case TYPE_DPAD:
                    w = 136f;
                    h = 136f;
                    break;
                case TYPE_STICK:
                    w = 130f;
                    h = 130f;
                    break;
                case TYPE_TRACKPAD:
                    w = 220f;
                    h = 140f;
                    break;
                default:
                    switch (shape) {
                        case SHAPE_SQUARE: w = 52f; h = 52f; break;
                        case SHAPE_RECT: w = 72f; h = 38f; break;
                        case SHAPE_PILL: w = 66f; h = 32f; break;
                        default: w = 56f; h = 56f; break;
                    }
                    break;
            }
            return new float[] { w * scale * clamp(width, 0.3f, 4f), h * scale * clamp(height, 0.3f, 4f) };
        }

        /** Whether the stick sends keys (ONA's MOUSE / GAMEPAD / TOUCH_DRAG outputs send nothing in this port). */
        boolean stickSendsKeys() {
            return "KEYS".equals(stickOutput);
        }

        /** The text a control without a label shows. */
        String autoLabel() {
            if (type == TYPE_BUTTON) {
                String s = action(0).label();
                return s.isEmpty() ? "NONE" : s;
            }
            return "";
        }
    }

    String id;
    String name;
    float cursorSpeed = 1f;
    float opacity = 0.4f;
    boolean disableMouse;
    final List<Element> elements = new ArrayList<>();

    OnaProfile copy() {
        OnaProfile p = new OnaProfile();
        p.id = id;
        p.name = name;
        p.cursorSpeed = cursorSpeed;
        p.opacity = opacity;
        p.disableMouse = disableMouse;
        for (Element e : elements) p.elements.add(e.copy());
        return p;
    }

    static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Profiles are laid out for a 915 x 412 dp landscape phone; smaller screens shrink every control evenly. */
    static float fit(int screenW, int screenH, float density) {
        if (screenW <= 0 || screenH <= 0) return 1f;
        return Math.min(1f, Math.min(screenW / density / 915f, screenH / density / 412f));
    }

    static String newElementId() {
        return "e" + Long.toString(System.nanoTime(), 36);
    }

    // ------------------------------------------------------------------ JSON

    private static int indexOf(String[] names, String name, int def) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return def;
    }

    static String colorHex(int c) {
        return String.format(java.util.Locale.ROOT, "#%08X", c);
    }

    /** "#AARRGGBB", "#RRGGBB" or a number (format 1); 0 when it is none of these. */
    static int parseColor(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        if (!(v instanceof String)) return 0;
        String s = ((String) v).trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() != 6 && s.length() != 8) return 0;
        try {
            long n = Long.parseLong(s, 16);
            if (s.length() == 6) n |= 0xFF000000L;
            return (int) n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Format 1's "bindings": a button's chord (format 1 holds them together), else each slot's first key. */
    private static JSONArray bindingsJson(Element e) {
        JSONArray b = new JSONArray();
        if (e.type == TYPE_BUTTON) {
            Action a = e.action(0);
            if (a.isMacro()) {
                b.put(a.firstKey().name);
            } else {
                for (Key k : a.keys) b.put(k.name);
            }
        } else {
            for (Action a : e.actions) b.put(a.firstKey().name);
        }
        return b;
    }

    private static JSONObject actionJson(Action a) throws JSONException {
        JSONObject j = new JSONObject();
        if (a.isMacro()) {
            JSONArray steps = new JSONArray();
            for (Step s : a.macro) {
                JSONObject o = new JSONObject();
                o.put("op", OP_NAMES[s.op]);
                if (s.op == OP_WAIT) o.put("ms", s.ms); else o.put("key", s.key.name);
                steps.put(o);
            }
            j.put("macro", steps);
        } else {
            JSONArray keys = new JSONArray();
            for (Key k : a.keys) keys.put(k.name);
            j.put("keys", keys);
        }
        return j;
    }

    /** The profile as JSON: format 2 (everything), or format 1 for ONA (only the fields ONA knows). */
    JSONObject toJson(boolean onaOnly) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("format", onaOnly ? FORMAT_ONA : FORMAT);
        o.put("id", id);
        o.put("name", name);
        o.put("cursorSpeed", (double) cursorSpeed);
        o.put("opacity", (double) opacity);
        o.put("disableMouse", disableMouse);
        JSONArray arr = new JSONArray();
        for (Element e : elements) {
            JSONObject j = new JSONObject();
            j.put("id", e.id);
            j.put("type", TYPE_NAMES[e.type]);
            j.put("x", (double) e.x);
            j.put("y", (double) e.y);
            j.put("scale", (double) e.scale);
            j.put("shape", SHAPE_NAMES[e.shape]);
            j.put("bindings", bindingsJson(e));
            j.put("toggle", e.behavior == BEHAVIOR_TOGGLE);
            j.put("text", e.text);
            j.put("stickOutput", e.stickOutput);
            j.put("deadzone", (double) e.deadzone);
            j.put("eightWay", e.eightWay);
            j.put("tapX", (double) e.tapX);
            j.put("tapY", (double) e.tapY);
            j.put("dragRadius", (double) e.dragRadius);
            j.put("width", (double) e.width);
            j.put("height", (double) e.height);
            j.put("color", e.color);
            if (!onaOnly) {
                JSONArray acts = new JSONArray();
                for (Action a : e.actions) acts.put(actionJson(a));
                j.put("actions", acts);
                j.put("behavior", BEHAVIOR_NAMES[e.behavior]);
                j.put("rotation", (double) e.rotation);
                if (e.alpha >= 0f) j.put("alpha", (double) e.alpha);
                if (e.labelColor != 0) j.put("labelColor", colorHex(e.labelColor));
                if (!e.icon.isEmpty()) j.put("icon", e.icon);
                if (e.locked) j.put("locked", true);
            }
            arr.put(j);
        }
        o.put("elements", arr);
        return o;
    }

    /** How many things a format-1 (ONA) export leaves out: macros, chords on cells, Flux-only looks and behaviour. */
    int onaLosses() {
        int n = 0;
        for (Element e : elements) {
            for (int i = 0; i < e.actions.size(); i++) {
                Action a = e.actions.get(i);
                if (a.isMacro()) n++;
                else if (e.type != TYPE_BUTTON && a.keys.size() > 1) n++;
            }
            if (e.behavior == BEHAVIOR_TAP) n++;
            if (e.rotation != 0f) n++;
            if (e.alpha >= 0f) n++;
            if (e.labelColor != 0) n++;
            if (!e.icon.isEmpty()) n++;
        }
        return n;
    }

    /** The result of reading a profile: the profile, and how many bindings it had that this port cannot send. */
    static final class Parsed {
        final OnaProfile profile;
        final int unsupported;

        Parsed(OnaProfile profile, int unsupported) {
            this.profile = profile;
            this.unsupported = unsupported;
        }
    }

    private static Action actionFromJson(JSONObject j, int[] unsupported) {
        Action a = new Action();
        if (j == null) return a;
        JSONArray steps = j.optJSONArray("macro");
        if (steps != null && steps.length() > 0) {
            for (int i = 0; i < Math.min(steps.length(), MAX_STEPS); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s == null) continue;
                int op = indexOf(OP_NAMES, s.optString("op"), -1);
                if (op < 0) continue;
                if (op == OP_WAIT) {
                    a.macro.add(new Step(OP_WAIT, NONE, clampInt(s.optInt("ms", 0), 0, MAX_WAIT_MS)));
                } else {
                    String n = s.optString("key");
                    if (unsupportedKeyName(n)) unsupported[0]++;
                    Key k = keyOf(n);
                    if (k.code != 0) a.macro.add(new Step(op, k, 0));
                }
            }
            if (steps.length() > MAX_STEPS) unsupported[0]++;
            return a;
        }
        JSONArray keys = j.optJSONArray("keys");
        if (keys != null) {
            for (int i = 0; i < keys.length() && a.keys.size() < MAX_CHORD; i++) {
                String n = keys.optString(i);
                if (unsupportedKeyName(n)) unsupported[0]++;
                Key k = keyOf(n);
                if (k.code != 0 && !a.keys.contains(k)) a.keys.add(k);
            }
        }
        return a;
    }

    /** Throws when the text is not a profile (no "elements" array), as ONA's profileFromJson. */
    static Parsed fromJson(String text) throws JSONException {
        JSONObject o = new JSONObject(text);
        JSONArray arr = o.getJSONArray("elements");
        OnaProfile p = new OnaProfile();
        p.id = o.optString("id", "p" + System.nanoTime());
        p.name = o.optString("name", "Profile");
        if (p.name.length() > 40) p.name = p.name.substring(0, 40);
        p.cursorSpeed = clamp((float) o.optDouble("cursorSpeed", 1.0), 0.1f, 10f);
        p.opacity = clamp((float) o.optDouble("opacity", 0.4), 0.05f, 1f);
        p.disableMouse = o.optBoolean("disableMouse", false);
        int[] unsupported = { 0 };
        int count = Math.min(arr.length(), MAX_ELEMENTS); // a sane cap for a hand-edited file
        for (int i = 0; i < count; i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            Element e = new Element();
            e.id = j.optString("id", "e" + System.nanoTime() + "_" + i);
            if (e.id.length() > 64) e.id = e.id.substring(0, 64);
            e.type = indexOf(TYPE_NAMES, j.optString("type"), TYPE_BUTTON);
            e.x = clamp((float) j.optDouble("x", 0.5), 0f, 1f);
            e.y = clamp((float) j.optDouble("y", 0.5), 0f, 1f);
            e.scale = clamp((float) j.optDouble("scale", 1.0), 0.3f, 4f);
            e.shape = indexOf(SHAPE_NAMES, j.optString("shape"), SHAPE_CIRCLE);
            JSONArray acts = j.optJSONArray("actions");
            if (acts != null) {
                for (int k = 0; k < Math.min(acts.length(), MAX_SLOTS); k++) {
                    e.actions.add(actionFromJson(acts.optJSONObject(k), unsupported));
                }
            } else {
                // Format 1: a button holds all its bindings together (a chord); the other types one key per slot.
                JSONArray b = j.optJSONArray("bindings");
                List<Key> keys = new ArrayList<>();
                if (b != null) {
                    for (int k = 0; k < Math.min(b.length(), MAX_SLOTS); k++) {
                        String n = b.optString(k);
                        if (unsupportedKeyName(n)) unsupported[0]++;
                        keys.add(keyOf(n));
                    }
                }
                if (e.type == TYPE_BUTTON) {
                    Action a = new Action();
                    for (Key k : keys) {
                        if (k.code != 0 && !a.keys.contains(k) && a.keys.size() < MAX_CHORD) a.keys.add(k);
                    }
                    e.actions.add(a);
                } else {
                    for (Key k : keys) e.actions.add(Action.of(k));
                }
            }
            String behavior = j.optString("behavior", "");
            if (!behavior.isEmpty()) {
                e.behavior = indexOf(BEHAVIOR_NAMES, behavior, BEHAVIOR_HOLD);
            } else {
                e.behavior = j.optBoolean("toggle", false) ? BEHAVIOR_TOGGLE : BEHAVIOR_HOLD;
            }
            e.text = j.optString("text", "");
            if (e.text.length() > MAX_TEXT) e.text = e.text.substring(0, MAX_TEXT);
            e.stickOutput = j.optString("stickOutput", "KEYS");
            if (e.stickOutput.length() > 24) e.stickOutput = "KEYS";
            if (e.type == TYPE_STICK && !e.stickSendsKeys()) unsupported[0]++;
            if (e.type == TYPE_TRACKPAD) unsupported[0]++;
            e.deadzone = clamp((float) j.optDouble("deadzone", 0.15), 0f, 0.9f);
            e.eightWay = j.optBoolean("eightWay", true);
            e.tapX = clamp((float) j.optDouble("tapX", 0.5), 0f, 1f);
            e.tapY = clamp((float) j.optDouble("tapY", 0.5), 0f, 1f);
            e.dragRadius = clamp((float) j.optDouble("dragRadius", 0.12), 0f, 1f);
            e.width = clamp((float) j.optDouble("width", 1.0), 0.3f, 4f);
            e.height = clamp((float) j.optDouble("height", 1.0), 0.3f, 4f);
            e.color = parseColor(j.opt("color"));
            double rot = j.optDouble("rotation", 0.0);
            e.rotation = Double.isNaN(rot) || Double.isInfinite(rot) ? 0f : (float) (((rot % 360.0) + 360.0) % 360.0);
            double alpha = j.optDouble("alpha", -1.0);
            e.alpha = Double.isNaN(alpha) || alpha < 0 ? -1f : clamp((float) alpha, 0.05f, 1f);
            e.labelColor = parseColor(j.opt("labelColor"));
            String icon = j.optString("icon", "");
            e.icon = OnaIcons.has(icon) ? icon : "";
            e.locked = j.optBoolean("locked", false);
            if (e.type == TYPE_BUTTON_ROW && e.actions.isEmpty()) e.actions.add(new Action());
            e.normalizeSlots();
            p.elements.add(e);
        }
        return new Parsed(p, unsupported[0]);
    }

    // ------------------------------------------------------------------ built-in profiles

    private static Element el(String id, int type, float x, float y, String... keys) {
        Element e = new Element();
        e.id = id;
        e.type = type;
        e.x = x;
        e.y = y;
        if (type == TYPE_BUTTON) {
            Action a = new Action();
            for (String k : keys) a.keys.add(keyOf(k));
            e.actions.add(a);
        } else {
            for (String k : keys) e.actions.add(Action.of(keyOf(k)));
        }
        return e;
    }

    /**
     * Built for Generals: the stick and D-pad pan the camera with the arrow keys (WASD are the game's command
     * hotkeys, not movement). Placed above the control bar and clear of the radar and the minimap.
     */
    static OnaProfile builtin(String id, Context ctx) {
        OnaProfile p = new OnaProfile();
        p.id = id;
        p.opacity = 0.45f;
        if (BUILTIN_MINIMAL.equals(id)) {
            p.name = ctx.getString(R.string.ona_profile_minimal);
            p.elements.add(el("dpad", TYPE_DPAD, 0.10f, 0.42f, "UP", "RIGHT", "DOWN", "LEFT"));
            Element ctrl = el("ctrl", TYPE_BUTTON, 0.94f, 0.30f, "L_CTRL");
            ctrl.behavior = BEHAVIOR_TOGGLE;
            p.elements.add(ctrl);
            p.elements.add(el("esc", TYPE_BUTTON, 0.94f, 0.08f, "ESC"));
            return p;
        }
        p.id = BUILTIN_RTS;
        p.name = ctx.getString(R.string.ona_profile_rts);
        Element stick = el("stick", TYPE_STICK, 0.10f, 0.45f, "UP", "RIGHT", "DOWN", "LEFT");
        stick.scale = 0.9f;
        p.elements.add(stick);
        Element row = el("groups", TYPE_BUTTON_ROW, 0.50f, 0.07f, "K1", "K2", "K3", "K4", "K5", "K6");
        p.elements.add(row);
        Element ctrl = el("ctrl", TYPE_BUTTON, 0.94f, 0.28f, "L_CTRL");
        ctrl.behavior = BEHAVIOR_TOGGLE;
        ctrl.shape = SHAPE_PILL;
        p.elements.add(ctrl);
        Element shift = el("shift", TYPE_BUTTON, 0.94f, 0.40f, "L_SHIFT");
        shift.behavior = BEHAVIOR_TOGGLE;
        shift.shape = SHAPE_PILL;
        p.elements.add(shift);
        Element space = el("space", TYPE_BUTTON, 0.85f, 0.40f, "SPACE");
        space.shape = SHAPE_PILL;
        p.elements.add(space);
        Element del = el("del", TYPE_BUTTON, 0.85f, 0.28f, "DEL");
        del.shape = SHAPE_PILL;
        p.elements.add(del);
        Element esc = el("esc", TYPE_BUTTON, 0.94f, 0.08f, "ESC");
        esc.shape = SHAPE_SQUARE;
        p.elements.add(esc);
        return p;
    }

    static boolean isBuiltin(String id) {
        return BUILTIN_RTS.equals(id) || BUILTIN_MINIMAL.equals(id);
    }

    // ------------------------------------------------------------------ storage (app-private)

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean onaStyleSelected(Context ctx) {
        return STYLE_ONA.equals(prefs(ctx).getString(PREF_STYLE, STYLE_FLUX));
    }

    static void setOnaStyle(Context ctx, boolean ona) {
        prefs(ctx).edit().putString(PREF_STYLE, ona ? STYLE_ONA : STYLE_FLUX).apply();
    }

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), DIR);
    }

    /** A file name for a profile id: only safe characters, so an imported id can never leave the folder. */
    private static File fileFor(Context ctx, String id) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < id.length() && b.length() < 64; i++) {
            char c = id.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        if (b.length() == 0) b.append("profile");
        return new File(dir(ctx), b + ".json");
    }

    static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > 1024 * 1024) throw new IOException("profile larger than 1 MB");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The saved copy, else the built-in one, else null. */
    static OnaProfile load(Context ctx, String id) {
        if (id == null) return null;
        File f = fileFor(ctx, id);
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                OnaProfile p = fromJson(readAll(in)).profile;
                p.id = id;
                return p;
            } catch (Throwable e) { // a damaged file (or deeply nested JSON: StackOverflowError) falls back
                android.util.Log.w("GeneralsZH", "[ONA] could not read profile " + f, e);
            }
        }
        return isBuiltin(id) ? builtin(id, ctx) : null;
    }

    static boolean save(Context ctx, OnaProfile p) {
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) return false;
        File f = fileFor(ctx, p.id);
        File tmp = new File(d, f.getName() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(p.toJson(false).toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    /** Deletes a saved profile; a built-in one goes back to its original layout. */
    static void delete(Context ctx, String id) {
        fileFor(ctx, id).delete();
    }

    /** The active profile (the built-in RTS profile when none was chosen or it is gone). */
    static OnaProfile active(Context ctx) {
        OnaProfile p = load(ctx, prefs(ctx).getString(PREF_ACTIVE, BUILTIN_RTS));
        return p != null ? p : builtin(BUILTIN_RTS, ctx);
    }

    static void setActive(Context ctx, String id) {
        prefs(ctx).edit().putString(PREF_ACTIVE, id).apply();
    }

    /** Built-ins first, then the saved ones, each once: { id, name }. */
    static List<String[]> list(Context ctx) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[] { BUILTIN_RTS, nameOf(ctx, BUILTIN_RTS) });
        out.add(new String[] { BUILTIN_MINIMAL, nameOf(ctx, BUILTIN_MINIMAL) });
        File[] files = dir(ctx).listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String n = f.getName();
                if (!n.endsWith(".json")) continue;
                String id = n.substring(0, n.length() - 5);
                if (isBuiltin(id)) continue;
                OnaProfile p = load(ctx, id);
                if (p != null) out.add(new String[] { id, p.name });
            }
        }
        return out;
    }

    private static String nameOf(Context ctx, String id) {
        OnaProfile p = load(ctx, id);
        return p != null ? p.name : id;
    }

    /** A fresh id that no saved profile uses. */
    static String newId(String prefix) {
        return prefix + "_" + Long.toString(System.currentTimeMillis(), 36);
    }

    /** A copy of a profile under a new id and name, saved and made active (null when it could not be saved). */
    static OnaProfile duplicate(Context ctx, OnaProfile src, String name) {
        OnaProfile p = src.copy();
        p.id = newId("user");
        p.name = name == null || name.trim().isEmpty() ? src.name : name.trim();
        if (p.name.length() > 40) p.name = p.name.substring(0, 40);
        if (!save(ctx, p)) return null;
        setActive(ctx, p.id);
        return p;
    }

    /** Imports a profile under a fresh id (never overwrites another), and makes it the active one. */
    static Parsed importJson(Context ctx, String text) throws JSONException {
        Parsed parsed = fromJson(text);
        OnaProfile p = parsed.profile;
        p.id = newId("import");
        if (p.name == null || p.name.trim().isEmpty()) p.name = "Profile";
        if (!save(ctx, p)) throw new JSONException("could not save the profile");
        setActive(ctx, p.id);
        return parsed;
    }
}
