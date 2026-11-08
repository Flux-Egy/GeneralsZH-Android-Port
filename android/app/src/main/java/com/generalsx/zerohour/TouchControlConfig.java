/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class TouchControlConfig {

    static final String PREFS_NAME = "generalszh_touch_controls";
    static final String PREF_ENABLED = "enabled";
    static final String PREF_PAN_SENSITIVITY = "pan_sensitivity";
    static final String PREF_BUTTON_SCALE = "button_scale";
    static final String PREF_BUTTON_OPACITY = "button_opacity";
    static final String PREF_BUTTONS = "buttons";

    static final int MOD_CTRL = 1;
    static final int MOD_SHIFT = 2;
    static final int MOD_ALT = 4;

    static final int ACTION_CLICK = 0;
    static final int ACTION_TOGGLE = 1;
    // GeneralsX @feature Flux Build v1.1.4 26/10/2026 Profiles written by 1.1.3 can
    // contain floating LMB/RMB buttons (actionType 2, no key). The one-finger gesture
    // scheme does not use them, so load/import skips those entries instead of
    // resurrecting a dead button.
    private static final int LEGACY_ACTION_MOUSE_CLICK = 2;

    static final int MAX_BUTTONS = 50;

    static final int SHAPE_RECTANGLE = 0;
    static final int SHAPE_ROUNDED = 1;
    static final int SHAPE_SQUARE = 2;
    static final int SHAPE_CIRCLE = 3;
    static final int SHAPE_OVAL = 4;

    private static final int[] KEY_CODES = buildKeyCodes();
    private static final String[] KEY_NAMES = buildKeyNames();

    boolean enabled = true;
    float panSensitivity = 1.0f;
    float buttonScale = 1.0f;
    float buttonOpacity = 0.78f;
    final ArrayList<ButtonSpec> buttons = new ArrayList<>();

    TouchControlConfig() {}

    static final class ButtonSpec {
        String label;
        int keyCode;
        int modifiers;
        float x;
        float y;
        float widthDp;
        float heightDp;
        float width;
        float height;
        int shape;
        int fillColor;
        int borderColor;
        int textColor;
        float scale;
        float opacity;
        int actionType;

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y) {
            this(label, keyCode, modifiers, x, y, 52.0f, 52.0f, SHAPE_ROUNDED,
                Color.rgb(22, 33, 44), Color.rgb(220, 181, 86), -1, 1.0f, 0.78f, ACTION_CLICK);
        }

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y,
                   float widthDp, float heightDp, int shape,
                   int fillColor, int borderColor, int textColor) {
            this(label, keyCode, modifiers, x, y, widthDp, heightDp, shape,
                fillColor, borderColor, textColor, 1.0f, 0.78f, ACTION_CLICK);
        }

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y,
                   float widthDp, float heightDp, int shape,
                   int fillColor, int borderColor, int textColor,
                   float scale, float opacity) {
            this(label, keyCode, modifiers, x, y, widthDp, heightDp, shape,
                fillColor, borderColor, textColor, scale, opacity, ACTION_CLICK);
        }

        ButtonSpec(String label, int keyCode, int modifiers, float x, float y,
                   float widthDp, float heightDp, int shape,
                   int fillColor, int borderColor, int textColor,
                   float scale, float opacity, int actionType) {
            this.label = label;
            this.keyCode = keyCode;
            this.modifiers = modifiers;
            this.x = x;
            this.y = y;
            this.widthDp = clamp(widthDp, 28.0f, 220.0f);
            this.heightDp = clamp(heightDp, 28.0f, 220.0f);
            this.width = this.widthDp;
            this.height = this.heightDp;
            this.shape = shape;
            this.fillColor = fillColor;
            this.borderColor = borderColor;
            this.textColor = textColor;
            this.scale = clamp(scale, 0.5f, 2.5f);
            this.opacity = clamp(opacity, 0.15f, 1.0f);
            this.actionType = (actionType == ACTION_TOGGLE) ? ACTION_TOGGLE : ACTION_CLICK;
        }

        ButtonSpec copy() {
            ButtonSpec b = new ButtonSpec(label, keyCode, modifiers, x, y,
                widthDp, heightDp, shape, fillColor, borderColor, textColor, scale, opacity, actionType);
            b.width = this.width;
            b.height = this.height;
            return b;
        }
    }

    TouchControlConfig createSnapshot() {
        TouchControlConfig snapshot = new TouchControlConfig();
        snapshot.enabled = this.enabled;
        snapshot.panSensitivity = this.panSensitivity;
        snapshot.buttonScale = this.buttonScale;
        snapshot.buttonOpacity = this.buttonOpacity;
        for (ButtonSpec b : this.buttons) {
            snapshot.buttons.add(b.copy());
        }
        return snapshot;
    }

    void restoreFromSnapshot(TouchControlConfig snapshot) {
        if (snapshot == null) {
            return;
        }
        this.enabled = snapshot.enabled;
        this.panSensitivity = snapshot.panSensitivity;
        this.buttonScale = snapshot.buttonScale;
        this.buttonOpacity = snapshot.buttonOpacity;
        this.buttons.clear();
        for (ButtonSpec b : snapshot.buttons) {
            this.buttons.add(b.copy());
        }
    }

    private static int[] buildKeyCodes() {
        ArrayList<Integer> codes = new ArrayList<>();
        for (int code = KeyEvent.KEYCODE_A; code <= KeyEvent.KEYCODE_Z; code++) {
            codes.add(code);
        }
        for (int code = KeyEvent.KEYCODE_0; code <= KeyEvent.KEYCODE_9; code++) {
            codes.add(code);
        }
        codes.add(KeyEvent.KEYCODE_SPACE);
        codes.add(KeyEvent.KEYCODE_TAB);
        codes.add(KeyEvent.KEYCODE_ESCAPE);
        codes.add(KeyEvent.KEYCODE_ENTER);
        codes.add(KeyEvent.KEYCODE_DEL);
        codes.add(KeyEvent.KEYCODE_DPAD_UP);
        codes.add(KeyEvent.KEYCODE_DPAD_DOWN);
        codes.add(KeyEvent.KEYCODE_DPAD_LEFT);
        codes.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        for (int code = KeyEvent.KEYCODE_F1; code <= KeyEvent.KEYCODE_F12; code++) {
            codes.add(code);
        }
        int[] result = new int[codes.size()];
        for (int i = 0; i < codes.size(); i++) {
            result[i] = codes.get(i);
        }
        return result;
    }

    private static String[] buildKeyNames() {
        String[] result = new String[KEY_CODES.length];
        for (int i = 0; i < KEY_CODES.length; i++) {
            result[i] = displayNameForKey(KEY_CODES[i]);
        }
        return result;
    }

    static int[] keyCodes() {
        return KEY_CODES.clone();
    }

    static String[] keyNames() {
        return KEY_NAMES.clone();
    }

    static String displayNameForKey(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ((keyCode + 'A') - KeyEvent.KEYCODE_A));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ((keyCode + '0') - KeyEvent.KEYCODE_0));
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return "F" + ((keyCode - KeyEvent.KEYCODE_F1) + 1);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return "RIGHT";
            case KeyEvent.KEYCODE_TAB:
                return "TAB";
            case KeyEvent.KEYCODE_SPACE:
                return "SPACE";
            case KeyEvent.KEYCODE_ENTER:
                return "ENTER";
            case KeyEvent.KEYCODE_DEL:
                return "BACK";
            case KeyEvent.KEYCODE_ESCAPE:
                return "ESC";
            default:
                return "KEY " + keyCode;
        }
    }

    static TouchControlConfig defaults() {
        TouchControlConfig config = new TouchControlConfig();
        String[] labels = {"E", "Q", "W", "R", "A", "S", "D", "F", "SPACE", "ESC"};
        int[] keys = {
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ESCAPE
        };
        for (int i = 0; i < labels.length; i++) {
            float x = (i * 0.096f) + 0.065f;
            config.buttons.add(new ButtonSpec(labels[i], keys[i], 0, x, 0.675f));
        }
        return config;
    }

    static TouchControlConfig load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        TouchControlConfig config = new TouchControlConfig();
        // GeneralsX @bugfix Flux Build 23/10/2026 Read the switch before anything else. The old
        // method treated enabled=false as corruption and wrote the default E/Q/W/R layout back
        // with enabled=true, which is exactly why the panel reappeared with factory sizes after
        // the player had switched it off.
        config.enabled = prefs.getBoolean(PREF_ENABLED, true);
        config.panSensitivity = clamp(prefs.getFloat(PREF_PAN_SENSITIVITY, 1.0f), 0.35f, 2.5f);
        config.buttonScale = clamp(prefs.getFloat(PREF_BUTTON_SCALE, 1.0f), 0.5f, 2.5f); // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 review: the per-button and slider ranges
        config.buttonOpacity = clamp(prefs.getFloat(PREF_BUTTON_OPACITY, 0.78f), 0.15f, 1.0f);

        // No saved profile at all is the only case that gets defaults. Keep the saved enabled
        // flag even here: a player may switch the panel off before ever opening the editor.
        if (!prefs.contains(PREF_BUTTONS)) {
            TouchControlConfig defaults = defaults();
            defaults.enabled = config.enabled;
            defaults.panSensitivity = config.panSensitivity;
            defaults.buttonScale = config.buttonScale;
            defaults.buttonOpacity = config.buttonOpacity;
            return defaults;
        }

        try {
            JSONArray array = new JSONArray(prefs.getString(PREF_BUTTONS, "[]"));
            for (int i = 0; i < array.length() && i < MAX_BUTTONS; i++) {
                JSONObject item = array.getJSONObject(i);
                String label = item.optString("label", "?");
                int keyCode = item.optInt("key", 0);
                int modifiers = item.optInt("modifiers", 0) & 7;
                float x = clamp((float) item.optDouble("x", 0.5d), 0.02f, 0.98f);
                float y = clamp((float) item.optDouble("y", 0.5d), 0.04f, 0.96f);
                // widthDp/heightDp are the canonical per-button values written by the editor.
                // "width"/"height" are the older keys and are only a fallback.
                float widthDp = clamp((float) item.optDouble("widthDp", item.optDouble("width", 52.0d)), 28.0f, 220.0f);
                float heightDp = clamp((float) item.optDouble("heightDp", item.optDouble("height", 52.0d)), 28.0f, 220.0f);
                int shape = item.optInt("shape", SHAPE_ROUNDED);
                if (shape < 0 || shape > 4) {
                    shape = SHAPE_ROUNDED;
                }
                if (shape == SHAPE_SQUARE || shape == SHAPE_CIRCLE) {
                    float side = Math.min(widthDp, heightDp);
                    widthDp = side;
                    heightDp = side;
                }
                int fillColor = parseColor(item.optString("fillColor", "#161F2C"), Color.rgb(22, 33, 44));
                int borderColor = parseColor(item.optString("borderColor", "#DCB556"), Color.rgb(220, 181, 86));
                int textColor = parseColor(item.optString("textColor", "#FFFFFF"), -1);
                float btnScale = clamp((float) item.optDouble("scale", config.buttonScale), 0.5f, 2.5f);
                float btnOpacity = clamp((float) item.optDouble("opacity", config.buttonOpacity), 0.15f, 1.0f); // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 review: the per-button and slider ranges
                int actionType = item.optInt("actionType", ACTION_CLICK);
                if (actionType == LEGACY_ACTION_MOUSE_CLICK) {
                    // The floating LMB/RMB buttons were removed in 1.1.4. A saved
                    // profile must not bring them back as keyless dead buttons.
                    continue;
                }
                if (actionType != ACTION_TOGGLE) {
                    actionType = ACTION_CLICK;
                }
                if (keyCode != 0) {
                    ButtonSpec spec = new ButtonSpec(label, keyCode, modifiers, x, y,
                        widthDp, heightDp, shape, fillColor, borderColor, textColor, btnScale, btnOpacity, actionType);
                    config.buttons.add(spec);
                }
            }
            // An empty array is a valid saved profile (the player removed every button), and a
            // disabled profile is valid too. Neither is a reason to overwrite the file.
            if (config.buttons.isEmpty()) {
                android.util.Log.i("GeneralsZH", "TouchControlConfig loaded an empty profile; keeping it");
            }
            return config;
        } catch (Exception e) {
            // Do not save defaults over a profile that failed to parse: that destroys the only
            // copy of the player's layout. Keep the enabled switch and whatever was read.
            android.util.Log.e("GeneralsZH", "TouchControlConfig parse failed; keeping existing profile untouched", e);
            return config;
        }
    }

    void save(Context context) {
        JSONArray array = new JSONArray();
        try {
            for (ButtonSpec button : this.buttons) {
                JSONObject item = new JSONObject();
                item.put("label", button.label);
                item.put("key", button.keyCode);
                item.put("modifiers", button.modifiers);
                item.put("x", (double) button.x);
                item.put("y", (double) button.y);
                item.put("widthDp", (double) button.widthDp);
                item.put("heightDp", (double) button.heightDp);
                item.put("width", (double) (button.width > 0 ? button.width : button.widthDp));
                item.put("height", (double) (button.height > 0 ? button.height : button.heightDp));
                item.put("shape", button.shape);
                item.put("fillColor", colorToString(button.fillColor));
                item.put("borderColor", colorToString(button.borderColor));
                item.put("textColor", colorToString(button.textColor));
                item.put("scale", (double) button.scale);
                item.put("opacity", (double) button.opacity);
                item.put("actionType", button.actionType);
                array.put(item);
            }
        } catch (Exception ignored) {
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_ENABLED, this.enabled)
            .putFloat(PREF_PAN_SENSITIVITY, clamp(this.panSensitivity, 0.35f, 2.5f))
            .putFloat(PREF_BUTTON_SCALE, clamp(this.buttonScale, 0.5f, 2.5f))
            .putFloat(PREF_BUTTON_OPACITY, clamp(this.buttonOpacity, 0.15f, 1.0f))
            .putString(PREF_BUTTONS, array.toString())
            .apply();
    }

    // GeneralsX @refactor Flux Build 05/11/2026 The profile JSON, shared by export and the saved
    // layout (saveAsDefaultLayout).
    String toJson() throws Exception {
        JSONObject json = new JSONObject();
        json.put("format", "GeneralsZHTouchControls");
        json.put("version", 2);
        json.put(PREF_ENABLED, this.enabled);
        json.put("panSensitivity", (double) this.panSensitivity);
        json.put("buttonScale", (double) this.buttonScale);
        json.put("buttonOpacity", (double) this.buttonOpacity);
        JSONArray array = new JSONArray();
        for (ButtonSpec button : this.buttons) {
            JSONObject item = new JSONObject();
            item.put("label", button.label);
            item.put("key", button.keyCode);
            item.put("keyName", displayNameForKey(button.keyCode));
            item.put("modifiers", button.modifiers);
            item.put("x", (double) button.x);
            item.put("y", (double) button.y);
            item.put("widthDp", (double) button.widthDp);
            item.put("heightDp", (double) button.heightDp);
            item.put("shape", button.shape);
            item.put("fillColor", colorToString(button.fillColor));
            item.put("borderColor", colorToString(button.borderColor));
            item.put("textColor", colorToString(button.textColor));
            item.put("scale", (double) button.scale);
            item.put("opacity", (double) button.opacity);
            item.put("actionType", button.actionType);
            array.put(item);
        }
        json.put(PREF_BUTTONS, array);
        return json.toString(2);
    }

    void exportTo(Context context, Uri uri) throws Exception {
        String json = toJson();
        try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new Exception("Unable to open export file");
            }
            output.write(json.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    static TouchControlConfig importFrom(Context context, Uri uri) throws Exception {
        StringBuilder json = new StringBuilder();
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new Exception("Unable to open import file");
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                json.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                if (json.length() > 1048576) {
                    throw new Exception("Import file is too large");
                }
            }
        }
        return fromJson(json.toString());
    }

    // GeneralsX @refactor Flux Build 05/11/2026 Profile parsing, shared by import and the saved layout.
    static TouchControlConfig fromJson(String jsonText) throws Exception {
        JSONObject root = new JSONObject(jsonText);
        JSONArray array = root.optJSONArray(PREF_BUTTONS);
        if (array == null) {
            throw new Exception("No buttons mapping found");
        }
        if (array.length() > MAX_BUTTONS) {
            throw new Exception("The profile contains more than " + MAX_BUTTONS + " buttons");
        }
        TouchControlConfig config = new TouchControlConfig();
        config.enabled = root.optBoolean(PREF_ENABLED, true);
        config.panSensitivity = clamp((float) root.optDouble("panSensitivity", 1.0d), 0.35f, 2.5f);
        config.buttonScale = clamp((float) root.optDouble("buttonScale", 1.0d), 0.5f, 2.5f); // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 review: the per-button and slider ranges
        config.buttonOpacity = clamp((float) root.optDouble("buttonOpacity", 0.78d), 0.15f, 1.0f);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            int keyCode = item.optInt("key", 0);
            int actionType = item.optInt("actionType", ACTION_CLICK);
            if (actionType == LEGACY_ACTION_MOUSE_CLICK) {
                // 1.1.3 floating LMB/RMB buttons are not part of this scheme.
                continue;
            }
            if (actionType != ACTION_TOGGLE) {
                actionType = ACTION_CLICK;
            }
            if (keyCode != 0) {
                String label = item.optString("label", displayNameForKey(keyCode));
                int modifiers = item.optInt("modifiers", 0) & 7;
                float x = clamp((float) item.optDouble("x", 0.5d), 0.02f, 0.98f);
                float y = clamp((float) item.optDouble("y", 0.5d), 0.04f, 0.96f);
                float widthDp = clamp((float) item.optDouble("widthDp", item.optDouble("width", 52.0d)), 28.0f, 220.0f);
                float heightDp = clamp((float) item.optDouble("heightDp", item.optDouble("height", 52.0d)), 28.0f, 220.0f);
                int shape = item.optInt("shape", SHAPE_ROUNDED);
                if (shape < 0 || shape > 4) {
                    shape = SHAPE_ROUNDED;
                }
                if (shape == SHAPE_SQUARE || shape == SHAPE_CIRCLE) {
                    float side = Math.min(widthDp, heightDp);
                    widthDp = side;
                    heightDp = side;
                }
                int fillColor = parseColor(item.optString("fillColor", "#161F2C"), Color.rgb(22, 33, 44));
                int borderColor = parseColor(item.optString("borderColor", "#DCB556"), Color.rgb(220, 181, 86));
                int textColor = parseColor(item.optString("textColor", "#FFFFFF"), -1);
                float btnScale = clamp((float) item.optDouble("scale", config.buttonScale), 0.5f, 2.5f);
                float btnOpacity = clamp((float) item.optDouble("opacity", config.buttonOpacity), 0.15f, 1.0f);
                ButtonSpec spec = new ButtonSpec(label, keyCode, modifiers, x, y,
                    widthDp, heightDp, shape, fillColor, borderColor, textColor, btnScale, btnOpacity, actionType);
                config.buttons.add(spec);
            }
        }
        if (config.buttons.isEmpty()) {
            throw new Exception("The profile contains no valid buttons");
        }
        return config;
    }

    // GeneralsX @feature Flux Build 05/11/2026 The player's own default layout ("Save as Default" /
    // "Load Saved Layout" in the in-game editor and the editor screen), kept in the app's private
    // files -- never in the game folder.
    static final String SAVED_LAYOUT_FILE = "GeneralsZH-Controls.json";

    static File savedLayoutFile(Context context) {
        return new File(context.getFilesDir(), SAVED_LAYOUT_FILE);
    }

    void saveAsDefaultLayout(Context context) throws Exception {
        File target = savedLayoutFile(context);
        File temp = new File(target.getPath() + ".tmp");
        try (FileWriter writer = new FileWriter(temp, false)) {
            writer.write(toJson());
        }
        if (!temp.renameTo(target)) {
            throw new Exception("Unable to write " + target);
        }
    }

    /** The saved layout, or null when none was saved. */
    static TouchControlConfig loadSavedLayout(Context context) throws Exception {
        File source = savedLayoutFile(context);
        if (!source.isFile()) {
            return null;
        }
        if (source.length() > 1048576) {
            throw new Exception("Saved layout is too large");
        }
        return fromJson(new String(java.nio.file.Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8));
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    static void prepareForLaunch(Context context, File gameFolder) {
        if (gameFolder == null) {
            return;
        }
        TouchControlConfig config = load(context);
        File nativeConfig = new File(gameFolder, "GeneralsXTouch.ini");
        try (FileWriter writer = new FileWriter(nativeConfig, false)) {
            writer.write("PanSensitivity=" + config.panSensitivity + "\n");
        } catch (Exception ignored) {
        }

        File groupPanel = new File(new File(gameFolder, "Window"), "GroupPanel.wnd");
        // Only an overlay with at least one button actually replaces the in-game group panel.
        // A saved-but-empty profile (or a disabled one) gets the stock panel back.
        boolean overlayActive = config.enabled && !config.buttons.isEmpty();
        if (overlayActive) {
            if (groupPanel.isFile()) {
                groupPanel.delete();
            }
        } else {
            File bundledRoot = context.getExternalFilesDir(null);
            if (bundledRoot != null) {
                SetupActivity.copyBundledRuntimeIfMissing(bundledRoot, gameFolder.getAbsolutePath());
            }
        }
    }

    private static int parseColor(String value, int fallback) {
        try {
            return Color.parseColor(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    static String colorToString(int color) {
        return String.format("#%08X", color);
    }

    static List<ButtonSpec> copyButtons(List<ButtonSpec> source) {
        ArrayList<ButtonSpec> copy = new ArrayList<>();
        for (ButtonSpec button : source) {
            copy.add(button.copy());
        }
        return copy;
    }

    // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 Background dimming while a layout is edited (0-80%),
    // shared by the in-game editor (over the live game) and the launcher's editor (over the last frame).
    static final String PREF_EDITOR_DIM = "editor_dim_percent";
    static final int EDITOR_DIM_MAX = 80;

    static int editorDimPercent(Context context) {
        int v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(PREF_EDITOR_DIM, 35);
        return Math.max(0, Math.min(EDITOR_DIM_MAX, v));
    }

    static void setEditorDimPercent(Context context, int percent) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PREF_EDITOR_DIM, Math.max(0, Math.min(EDITOR_DIM_MAX, percent))).apply();
    }


    /** "Ctrl+Shift+1": the modifiers and the key, as a button label. */
    static String labelFor(int keyCode, int modifiers) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & MOD_CTRL) != 0) sb.append("Ctrl+");
        if ((modifiers & MOD_ALT) != 0) sb.append("Alt+");
        if ((modifiers & MOD_SHIFT) != 0) sb.append("Shift+");
        sb.append(displayNameForKey(keyCode));
        return sb.toString();
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
