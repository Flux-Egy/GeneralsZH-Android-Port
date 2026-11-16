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
 */
final class OnaProfile {

    static final String FORMAT = "ona-controls-profile-1";

    static final String PREFS = "ona_controls";
    static final String PREF_STYLE = "control_style";      // "flux" (default) or "ona"
    static final String PREF_ACTIVE = "active_profile";
    static final String STYLE_FLUX = "flux";
    static final String STYLE_ONA = "ona";
    private static final String DIR = "ona_profiles";

    static final String BUILTIN_RTS = "flux_rts";
    static final String BUILTIN_MINIMAL = "flux_minimal";

    // ------------------------------------------------------------------ element types

    static final int TYPE_BUTTON = 0;
    static final int TYPE_BUTTON_ROW = 1;
    static final int TYPE_DPAD = 2;
    static final int TYPE_STICK = 3;
    static final int TYPE_TRACKPAD = 4; // kept from imports; draws, sends nothing (no mouse in v1)
    private static final String[] TYPE_NAMES = { "BUTTON", "BUTTON_ROW", "DPAD", "STICK", "TRACKPAD" };

    static final int SHAPE_CIRCLE = 0;
    static final int SHAPE_SQUARE = 1;
    static final int SHAPE_RECT = 2;
    static final int SHAPE_PILL = 3;
    private static final String[] SHAPE_NAMES = { "CIRCLE", "SQUARE", "RECT", "PILL" };

    // ------------------------------------------------------------------ keys

    /** One bindable key: its ONA name (JSON), the label drawn on the control, the Android key code (0 = none). */
    static final class Key {
        final String name;
        final String label;
        final int code;

        Key(String name, String label, int code) {
            this.name = name;
            this.label = label;
            this.code = code;
        }
    }

    static final Key NONE = new Key("NONE", "", 0);
    private static final Map<String, Key> KEYS = new LinkedHashMap<>();
    /** The keys offered in the editor's key picker, in this order. */
    static final List<Key> PICKER = new ArrayList<>();

    private static void key(String name, String label, int code) {
        Key k = new Key(name, label, code);
        KEYS.put(name, k);
        PICKER.add(k);
    }

    static {
        KEYS.put(NONE.name, NONE);
        key("UP", "↑", KeyEvent.KEYCODE_DPAD_UP);
        key("DOWN", "↓", KeyEvent.KEYCODE_DPAD_DOWN);
        key("LEFT", "←", KeyEvent.KEYCODE_DPAD_LEFT);
        key("RIGHT", "→", KeyEvent.KEYCODE_DPAD_RIGHT);
        key("ESC", "ESC", KeyEvent.KEYCODE_ESCAPE);
        key("ENTER", "ENTER", KeyEvent.KEYCODE_ENTER);
        key("SPACE", "SPACE", KeyEvent.KEYCODE_SPACE);
        key("TAB", "TAB", KeyEvent.KEYCODE_TAB);
        key("BKSP", "BKSP", KeyEvent.KEYCODE_DEL);
        key("DEL", "DEL", KeyEvent.KEYCODE_FORWARD_DEL);
        key("INSERT", "INS", KeyEvent.KEYCODE_INSERT);
        key("HOME", "HOME", KeyEvent.KEYCODE_MOVE_HOME);
        key("END", "END", KeyEvent.KEYCODE_MOVE_END);
        key("PAGE_UP", "PGUP", KeyEvent.KEYCODE_PAGE_UP);
        key("PAGE_DOWN", "PGDN", KeyEvent.KEYCODE_PAGE_DOWN);
        key("L_CTRL", "CTRL", KeyEvent.KEYCODE_CTRL_LEFT);
        key("L_SHIFT", "SHIFT", KeyEvent.KEYCODE_SHIFT_LEFT);
        key("L_ALT", "ALT", KeyEvent.KEYCODE_ALT_LEFT);
        key("R_CTRL", "R CTRL", KeyEvent.KEYCODE_CTRL_RIGHT);
        key("R_SHIFT", "R SHIFT", KeyEvent.KEYCODE_SHIFT_RIGHT);
        key("R_ALT", "R ALT", KeyEvent.KEYCODE_ALT_RIGHT);
        for (int i = 0; i <= 9; i++) {
            key("K" + i, Integer.toString(i), KeyEvent.KEYCODE_0 + i);
        }
        for (char c = 'A'; c <= 'Z'; c++) {
            key(String.valueOf(c), String.valueOf(c), KeyEvent.KEYCODE_A + (c - 'A'));
        }
        for (int i = 1; i <= 12; i++) {
            key("F" + i, "F" + i, KeyEvent.KEYCODE_F1 + i - 1);
        }
        key("TILDE", "~", KeyEvent.KEYCODE_GRAVE);
        key("MINUS", "-", KeyEvent.KEYCODE_MINUS);
        key("EQUALS", "=", KeyEvent.KEYCODE_EQUALS);
        key("COMMA", ",", KeyEvent.KEYCODE_COMMA);
        key("PERIOD", ".", KeyEvent.KEYCODE_PERIOD);
        key("SLASH", "/", KeyEvent.KEYCODE_SLASH);
        key("CAPS", "CAPS", KeyEvent.KEYCODE_CAPS_LOCK);
        key("NUM_2", "NUM 2", KeyEvent.KEYCODE_NUMPAD_2);
        key("NUM_4", "NUM 4", KeyEvent.KEYCODE_NUMPAD_4);
        key("NUM_5", "NUM 5", KeyEvent.KEYCODE_NUMPAD_5);
        key("NUM_6", "NUM 6", KeyEvent.KEYCODE_NUMPAD_6);
        key("NUM_8", "NUM 8", KeyEvent.KEYCODE_NUMPAD_8);
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

    // ------------------------------------------------------------------ model

    /** One control. x / y = its centre as a fraction of the screen; size = the type's base size (dp) * scale. */
    static final class Element {
        String id;
        int type = TYPE_BUTTON;
        float x = 0.5f;
        float y = 0.5f;
        float scale = 1f;
        int shape = SHAPE_CIRCLE;
        final List<Key> bindings = new ArrayList<>();
        boolean toggle;
        String text = "";
        /** ONA's stickOutput; only KEYS is acted on here, the others are kept so an export round-trips. */
        String stickOutput = "KEYS";
        float deadzone = 0.15f;
        boolean eightWay = true;
        float width = 1f;
        float height = 1f;
        int color;
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
            e.bindings.addAll(bindings);
            e.toggle = toggle;
            e.text = text;
            e.stickOutput = stickOutput;
            e.deadzone = deadzone;
            e.eightWay = eightWay;
            e.width = width;
            e.height = height;
            e.color = color;
            e.tapX = tapX;
            e.tapY = tapY;
            e.dragRadius = dragRadius;
            return e;
        }

        /** Base size in dp, as ONA's ElementGeometry.sizeDp. */
        float[] sizeDp() {
            float w;
            float h;
            switch (type) {
                case TYPE_BUTTON_ROW:
                    w = 40f * Math.max(1, bindings.size());
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
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Profiles are laid out for a 915 x 412 dp landscape phone; smaller screens shrink every control evenly. */
    static float fit(int screenW, int screenH, float density) {
        if (screenW <= 0 || screenH <= 0) return 1f;
        return Math.min(1f, Math.min(screenW / density / 915f, screenH / density / 412f));
    }

    // ------------------------------------------------------------------ JSON (ONA format)

    private static int indexOf(String[] names, String name, int def) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return def;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("format", FORMAT);
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
            JSONArray b = new JSONArray();
            for (Key k : e.bindings) b.put(k.name);
            j.put("bindings", b);
            j.put("toggle", e.toggle);
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
            arr.put(j);
        }
        o.put("elements", arr);
        return o;
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

    /** Throws when the text is not a profile (no "elements" array), as ONA's profileFromJson. */
    static Parsed fromJson(String text) throws JSONException {
        JSONObject o = new JSONObject(text);
        JSONArray arr = o.getJSONArray("elements");
        OnaProfile p = new OnaProfile();
        p.id = o.optString("id", "p" + System.nanoTime());
        p.name = o.optString("name", "Profile");
        p.cursorSpeed = (float) o.optDouble("cursorSpeed", 1.0);
        p.opacity = clamp((float) o.optDouble("opacity", 0.4), 0.05f, 1f);
        p.disableMouse = o.optBoolean("disableMouse", false);
        int unsupported = 0;
        int count = Math.min(arr.length(), 200); // a sane cap for a hand-edited file
        for (int i = 0; i < count; i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;
            Element e = new Element();
            e.id = j.optString("id", "e" + System.nanoTime() + "_" + i);
            e.type = indexOf(TYPE_NAMES, j.optString("type"), TYPE_BUTTON);
            e.x = clamp((float) j.optDouble("x", 0.5), 0f, 1f);
            e.y = clamp((float) j.optDouble("y", 0.5), 0f, 1f);
            e.scale = clamp((float) j.optDouble("scale", 1.0), 0.3f, 4f);
            e.shape = indexOf(SHAPE_NAMES, j.optString("shape"), SHAPE_CIRCLE);
            JSONArray b = j.optJSONArray("bindings");
            if (b != null) {
                for (int k = 0; k < Math.min(b.length(), 16); k++) {
                    String n = b.optString(k);
                    if (unsupportedKeyName(n)) unsupported++;
                    e.bindings.add(keyOf(n));
                }
            }
            e.toggle = j.optBoolean("toggle", false);
            e.text = j.optString("text", "");
            if (e.text.length() > 24) e.text = e.text.substring(0, 24);
            e.stickOutput = j.optString("stickOutput", "KEYS");
            if (e.type == TYPE_STICK && !e.stickSendsKeys()) unsupported++;
            if (e.type == TYPE_TRACKPAD) unsupported++;
            e.deadzone = clamp((float) j.optDouble("deadzone", 0.15), 0f, 0.9f);
            e.eightWay = j.optBoolean("eightWay", true);
            e.tapX = (float) j.optDouble("tapX", 0.5);
            e.tapY = (float) j.optDouble("tapY", 0.5);
            e.dragRadius = (float) j.optDouble("dragRadius", 0.12);
            e.width = clamp((float) j.optDouble("width", 1.0), 0.3f, 4f);
            e.height = clamp((float) j.optDouble("height", 1.0), 0.3f, 4f);
            e.color = j.optInt("color", 0);
            if (e.type == TYPE_DPAD || e.type == TYPE_STICK) {
                while (e.bindings.size() < 4) e.bindings.add(NONE);
            }
            p.elements.add(e);
        }
        return new Parsed(p, unsupported);
    }

    // ------------------------------------------------------------------ built-in profiles

    private static Element el(String id, int type, float x, float y, String... keys) {
        Element e = new Element();
        e.id = id;
        e.type = type;
        e.x = x;
        e.y = y;
        for (String k : keys) e.bindings.add(keyOf(k));
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
            ctrl.toggle = true;
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
        ctrl.toggle = true;
        ctrl.shape = SHAPE_PILL;
        p.elements.add(ctrl);
        Element shift = el("shift", TYPE_BUTTON, 0.94f, 0.40f, "L_SHIFT");
        shift.toggle = true;
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
            } catch (IOException | JSONException e) {
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
            out.write(p.toJson().toString(1).getBytes(StandardCharsets.UTF_8));
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

    /** Imports a profile under a fresh id (never overwrites another), and makes it the active one. */
    static Parsed importJson(Context ctx, String text) throws JSONException {
        Parsed parsed = fromJson(text);
        OnaProfile p = parsed.profile;
        String base = "import_" + Long.toString(System.currentTimeMillis(), 36);
        p.id = base;
        if (p.name == null || p.name.trim().isEmpty()) p.name = "Profile";
        if (!save(ctx, p)) throw new JSONException("could not save the profile");
        setActive(ctx, p.id);
        return parsed;
    }
}
