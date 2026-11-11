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

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;

public class TouchControlsActivity extends Activity {

    private static final int REQUEST_EXPORT_CONTROLS = 7101;
    private static final int REQUEST_IMPORT_CONTROLS = 7102;

    private TouchControlConfig config;
    private EditorCanvas editor;
    private TextView selectedText;

    private SeekBar panSeek;
    private TextView panValue;
    private SeekBar sizeSeek;
    private TextView sizeValue;
    private SeekBar opacitySeek;
    private TextView opacityValue;

    private int selectedIndex = -1;
    // GeneralsX @feature Flux Build 11/11/2026 The fixed buttons ("i", "G"; HudButtons) are placed here too:
    // drawn where they will be in the game and dragged like the others; Save keeps their places, Defaults
    // puts them back under the gear. Edit and Delete leave them alone (they are always on).
    private final float[][] fixedPos = new float[HudButtons.COUNT][];
    private final boolean[] fixedOn = new boolean[HudButtons.COUNT];
    private boolean fixedChanged = false;
    private int selectedFixed = -1;

    private interface SliderValueListener {
        void onValue(int val);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        ThemeHelper.apply(this);
        setTitle(R.string.touch_editor_title);
        this.config = TouchControlConfig.load(this);
        for (int id = 0; id < HudButtons.COUNT; id++) {
            this.fixedOn[id] = true; // always on (owner); kept per button for a future switch
            this.fixedPos[id] = HudButtons.savedPosition(this, id);
        }
        buildUi();
    }

    /** A fixed button's top-left as a fraction of the screen: where it was left, or its default. */
    private float[] fixedFraction(int id) {
        if (this.fixedPos[id] != null) {
            return this.fixedPos[id];
        }
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        float w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        if (w <= 0 || h <= 0) {
            return new float[] { 0.9f, 0.15f };
        }
        float[] d = HudButtons.defaultPosition(id, w, h, dm.density, this.safeFrac[2] * w, this.safeFrac[1] * h);
        return new float[] { d[0] / w, d[1] / h };
    }

    /** The fixed buttons' size in the editor: their real size, scaled like the screen-shaped area. */
    private float fixedSizePx(RectF area) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        float w = Math.max(dm.widthPixels, dm.heightPixels);
        return HudButtons.SIZE_DP * dm.density * (w > 0 ? area.width() / w : 1f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(6), dp(8), dp(6));
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        setContentView(root);
        InsetUtil.applySafeInsets(root);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(16);
        this.selectedText = new TextView(this);
        this.selectedText.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        this.selectedText.setPadding(dp(12), 0, dp(8), 0);
        statusRow.addView(this.selectedText, new LinearLayout.LayoutParams(0, -2, 1.0f));
        root.addView(statusRow);

        LinearLayout actionsRow = new LinearLayout(this);
        actionsRow.setGravity(16);
        addToolbarButton(actionsRow, R.string.touch_add_button, this::addNewButton);
        addToolbarButton(actionsRow, R.string.touch_edit_button, this::editSelectedButton);
        addToolbarButton(actionsRow, R.string.touch_delete_button, this::deleteSelectedButton);
        addToolbarButton(actionsRow, R.string.touch_reset_button, this::resetDefaults);
        // GeneralsX @feature Flux Build 05/11/2026 The player's own default layout, shared with the
        // in-game editor (TouchControlConfig.saveAsDefaultLayout / loadSavedLayout).
        addToolbarButton(actionsRow, R.string.touch_save_default, this::saveLayoutAsDefault);
        addToolbarButton(actionsRow, R.string.touch_load_saved, this::loadSavedLayout);
        addToolbarButton(actionsRow, R.string.touch_save_button, this::saveAndFinish);
        addToolbarButton(actionsRow, R.string.touch_export_button, this::exportControls);
        addToolbarButton(actionsRow, R.string.touch_import_button, this::importControls);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        actionsScroll.addView(actionsRow, new FrameLayout.LayoutParams(-2, -2));
        root.addView(actionsScroll);

        this.editor = new EditorCanvas();
        root.addView(this.editor, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        LinearLayout sliders = new LinearLayout(this);
        sliders.setOrientation(LinearLayout.HORIZONTAL);
        sliders.setPadding(0, dp(4), 0, 0);

        this.panValue = addSlider(sliders, R.string.touch_pan_speed, 35, 250,
            Math.round(this.config.panSensitivity * 100.0f),
            value -> this.config.panSensitivity = value / 100.0f);
        this.panSeek = (SeekBar) this.panValue.getTag();

        // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 Size and Opacity change the selected button,
        // or every button when none is selected -- the button's own scale and opacity, which the game
        // draws (HotkeyOverlayView: dp(width) * spec.scale, spec.opacity). They used to change only the
        // layout-wide values, which the game reads just as the default for a button without its own, so
        // the sliders seemed to do nothing in the game. The ranges are the game's (0.5-2.5, 0.15-1).
        this.sizeValue = addSlider(sliders, R.string.touch_button_size, 50, 250,
            Math.round(this.config.buttonScale * 100.0f),
            value -> applyToButtons(value / 100.0f, true));
        this.sizeSeek = (SeekBar) this.sizeValue.getTag();

        this.opacityValue = addSlider(sliders, R.string.touch_button_opacity, 15, 100,
            Math.round(this.config.buttonOpacity * 100.0f),
            value -> applyToButtons(value / 100.0f, false));
        this.opacitySeek = (SeekBar) this.opacityValue.getTag();

        // GeneralsX @tweak Flux Build 08/11/2026 v1.4.2 round 3 The game screenshot behind the buttons is
        // parked (owner): a plain background, so the dim slider is gone here; the in-game editor keeps its.
        new Thread(() -> new java.io.File(getFilesDir(), "touch_preview.jpg").delete(), "gx-touch-bg").start();

        root.addView(sliders);
        refreshLabels();
    }

    /** True while the sliders are moved to show a selection, so they do not change any button. */
    private boolean syncingSliders;

    private void applyToButtons(float value, boolean size) {
        // GeneralsX @bugfix Flux Build 11/11/2026 review: a fixed button (i, G, Ctrl, ...) is selected -- the
        // sliders are for the touch buttons, so they do nothing rather than resize the whole panel.
        if (this.selectedFixed >= 0) {
            return;
        }
        if (this.syncingSliders) return;
        final boolean one = this.selectedIndex >= 0 && this.selectedIndex < this.config.buttons.size();
        for (int i = 0; i < this.config.buttons.size(); i++) {
            if (one && i != this.selectedIndex) continue;
            TouchControlConfig.ButtonSpec spec = this.config.buttons.get(i);
            if (size) spec.scale = TouchControlConfig.clamp(value, 0.5f, 2.5f);
            else spec.opacity = TouchControlConfig.clamp(value, 0.15f, 1.0f);
        }
        if (!one) {
            // The layout-wide values: what the sliders show with nothing selected, saved with the layout.
            if (size) this.config.buttonScale = value;
            else this.config.buttonOpacity = value;
        }
        this.editor.invalidate();
        refreshSliderLabels();
    }

    /** The sliders show the selected button's values, or the layout-wide ones. */
    private void syncSliders() {
        if (this.sizeSeek == null) return;
        float scale = this.config.buttonScale, opacity = this.config.buttonOpacity;
        if (this.selectedIndex >= 0 && this.selectedIndex < this.config.buttons.size()) {
            TouchControlConfig.ButtonSpec spec = this.config.buttons.get(this.selectedIndex);
            scale = spec.scale;
            opacity = spec.opacity;
        }
        this.syncingSliders = true;
        this.sizeSeek.setProgress(Math.round(scale * 100.0f));
        this.opacitySeek.setProgress(Math.round(opacity * 100.0f));
        this.syncingSliders = false;
        refreshSliderLabels();
    }

    private void refreshSliderLabels() {
        final int size = this.sizeSeek.getProgress(), opacity = this.opacitySeek.getProgress();
        if (this.selectedIndex >= 0 && this.selectedIndex < this.config.buttons.size()) {
            String label = this.config.buttons.get(this.selectedIndex).label;
            this.sizeValue.setText(getString(R.string.touch_editor_scale_label, label, size));
            this.opacityValue.setText(getString(R.string.touch_editor_alpha_label, label, opacity));
        } else {
            this.sizeValue.setText(getString(R.string.touch_button_size_all, size));
            this.opacityValue.setText(getString(R.string.touch_button_opacity_all, opacity));
        }
    }

    // The screen's shape (long side / short side) and the display cutout's share of each edge in
    // landscape: the in-game overlay lays buttons out on the screen minus those insets
    // (HotkeyOverlayView, safeLeft/availW; the game is immersive, so the cutout is all there is).
    // Measured once (review: not on every draw).
    private float screenAspect = 0f;
    private final float[] safeFrac = new float[4]; // left, top, right, bottom

    private void measureScreen() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int w = Math.max(dm.widthPixels, dm.heightPixels), h = Math.min(dm.widthPixels, dm.heightPixels);
        this.screenAspect = h > 0 ? (float) w / (float) h : 16f / 9f;
        java.util.Arrays.fill(this.safeFrac, 0f);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && w > 0 && h > 0) {
            android.view.DisplayCutout cutout = getWindowManager().getDefaultDisplay().getCutout();
            if (cutout != null) {
                // This editor is landscape like the game, so the cutout's insets are landscape too.
                this.safeFrac[0] = cutout.getSafeInsetLeft() / (float) w;
                this.safeFrac[1] = cutout.getSafeInsetTop() / (float) h;
                this.safeFrac[2] = cutout.getSafeInsetRight() / (float) w;
                this.safeFrac[3] = cutout.getSafeInsetBottom() / (float) h;
            }
        }
    }

    private float screenAspect() {
        if (this.screenAspect <= 0f) measureScreen();
        return this.screenAspect;
    }

    private TextView addSlider(LinearLayout container, final int titleRes, int min, int max,
                               int initial, final SliderValueListener listener) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), 0, dp(8), 0);

        final TextView label = new TextView(this);
        label.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        col.addView(label);

        SeekBar seek = new SeekBar(this);
        seek.setMin(min);
        seek.setMax(max);
        seek.setProgress(initial);
        int accent = ThemeHelper.accentColor(this);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 review: the generic text first, so a
                // listener that names the button (Size/Opacity) has the last word.
                label.setText(getString(titleRes, value));
                listener.onValue(value);
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        col.addView(seek);
        container.addView(col, new LinearLayout.LayoutParams(0, -2, 1.0f));

        label.setTag(seek);
        label.setText(getString(titleRes, initial));
        return label;
    }

    private void addToolbarButton(LinearLayout row, int textRes, final Runnable action) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        button.setTextSize(12.0f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        // A platform Button keeps the Day preset's light background even when the player has
        // selected a dark custom base and white text. Give it the same tonal surface as the rest
        // of the custom theme so its label stays readable in every combination.
        android.graphics.drawable.GradientDrawable shape =
            new android.graphics.drawable.GradientDrawable();
        shape.setColor(UiKit.color(this, R.color.gzh_surface_container_high));
        shape.setCornerRadius(dp(9));
        shape.setStroke(dp(1), UiKit.withAlpha(UiKit.color(this, R.color.gzh_primary), 0x55));
        button.setBackground(shape);
        button.setOnClickListener(v -> action.run());
        row.addView(button);
    }

    private void addNewButton() {
        if (this.config.buttons.size() >= TouchControlConfig.MAX_BUTTONS) {
            Toast.makeText(this, R.string.touch_too_many_buttons, Toast.LENGTH_SHORT).show();
            return;
        }
        this.config.buttons.add(new TouchControlConfig.ButtonSpec("E", 33, 0, 0.5f, 0.5f));
        this.selectedIndex = this.config.buttons.size() - 1;
        this.selectedFixed = -1;
        this.editor.invalidate();
        refreshLabels();
        editSelectedButton();
    }

    private void deleteSelectedButton() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.config.buttons.size()) {
            return;
        }
        this.config.buttons.remove(this.selectedIndex);
        this.selectedIndex = this.config.buttons.isEmpty() ? -1 : Math.min(this.selectedIndex, this.config.buttons.size() - 1);
        this.editor.invalidate();
        refreshLabels();
    }

    private void editSelectedButton() {
        if (this.selectedIndex < 0 || this.selectedIndex >= this.config.buttons.size()) {
            return;
        }
        final TouchControlConfig.ButtonSpec spec = this.config.buttons.get(this.selectedIndex);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        final EditText label = new EditText(this);
        label.setHint(R.string.touch_button_label_hint);
        label.setText(spec.label);
        label.setSelectAllOnFocus(true);
        form.addView(label);

        final Spinner keySpinner = new Spinner(this);
        final String[] names = TouchControlConfig.keyNames();
        final int[] codes = TouchControlConfig.keyCodes();
        keySpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));

        int selectedKey = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == spec.keyCode) {
                selectedKey = i;
                break;
            }
        }
        keySpinner.setSelection(selectedKey);
        form.addView(keySpinner);

        final TextView actionTypeLabel = new TextView(this);
        actionTypeLabel.setText(R.string.touch_action_type);
        actionTypeLabel.setPadding(0, dp(8), 0, dp(4));
        form.addView(actionTypeLabel);

        final Spinner actionTypeSpinner = new Spinner(this);
        String[] actionTypeNames = {
            getString(R.string.touch_action_click),
            getString(R.string.touch_action_toggle)
        };
        actionTypeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, actionTypeNames));
        actionTypeSpinner.setSelection(spec.actionType == TouchControlConfig.ACTION_TOGGLE ? 1 : 0);
        form.addView(actionTypeSpinner);

        final TextView actionTypeHelp = new TextView(this);
        actionTypeHelp.setTextSize(11f);
        actionTypeHelp.setTextColor(UiKit.withAlpha(
            UiKit.color(this, R.color.gzh_on_surface), 0xAA));
        actionTypeHelp.setPadding(0, dp(2), 0, dp(8));
        actionTypeHelp.setText(R.string.touch_action_help);
        form.addView(actionTypeHelp);

        LinearLayout modifiers = new LinearLayout(this);
        final CheckBox ctrl = new CheckBox(this);
        ctrl.setText(R.string.touch_modifier_ctrl);
        ctrl.setChecked((spec.modifiers & TouchControlConfig.MOD_CTRL) != 0);
        modifiers.addView(ctrl);

        final CheckBox shift = new CheckBox(this);
        shift.setText(R.string.touch_modifier_shift);
        shift.setChecked((spec.modifiers & TouchControlConfig.MOD_SHIFT) != 0);
        modifiers.addView(shift);

        final CheckBox alt = new CheckBox(this);
        alt.setText(R.string.touch_modifier_alt);
        alt.setChecked((spec.modifiers & TouchControlConfig.MOD_ALT) != 0);
        modifiers.addView(alt);

        form.addView(modifiers);

        final TextView widthLabel = new TextView(this);
        widthLabel.setText(getString(R.string.touch_width_dp, Math.round(spec.widthDp)));
        form.addView(widthLabel);

        final SeekBar widthSeek = new SeekBar(this);
        widthSeek.setMax(192); // 28dp to 220dp
        widthSeek.setProgress(Math.round(spec.widthDp - 28.0f));
        form.addView(widthSeek);

        final TextView heightLabel = new TextView(this);
        heightLabel.setText(getString(R.string.touch_height_dp, Math.round(spec.heightDp)));
        form.addView(heightLabel);

        final SeekBar heightSeek = new SeekBar(this);
        heightSeek.setMax(192);
        heightSeek.setProgress(Math.round(spec.heightDp - 28.0f));
        form.addView(heightSeek);

        String[] shapeNames = {
            getString(R.string.touch_shape_rectangle),
            getString(R.string.touch_shape_rounded),
            getString(R.string.touch_shape_square),
            getString(R.string.touch_shape_circle),
            getString(R.string.touch_shape_oval)
        };
        final Spinner shapeSpinner = new Spinner(this);
        shapeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, shapeNames));
        shapeSpinner.setSelection(spec.shape);
        form.addView(shapeSpinner);

        shapeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean square = (position == TouchControlConfig.SHAPE_SQUARE || position == TouchControlConfig.SHAPE_CIRCLE);
                heightSeek.setEnabled(!square);
                if (square) {
                    heightSeek.setProgress(widthSeek.getProgress());
                    heightLabel.setText(getString(R.string.touch_height_dp, widthSeek.getProgress() + 28));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        final int[] selectedFillColor = { spec.fillColor };
        final int[] selectedTextColor = { spec.textColor };

        LinearLayout textColorRow = new LinearLayout(this);
        textColorRow.setGravity(16);
        TextView textColorLabel = new TextView(this);
        textColorLabel.setText(R.string.touch_text_color);
        textColorLabel.setPadding(0, dp(8), dp(12), dp(8));
        textColorRow.addView(textColorLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        ImageButton textColorPicker = new ImageButton(this);
        textColorPicker.setContentDescription(getString(R.string.touch_text_color_picker));
        textColorPicker.setImageResource(android.R.drawable.ic_menu_edit);
        textColorPicker.setPadding(dp(8), dp(8), dp(8), dp(8));
        updateColorPickerIcon(textColorPicker, selectedTextColor[0]);
        textColorPicker.setOnClickListener(v -> showColorPicker(textColorPicker, selectedTextColor));
        textColorRow.addView(textColorPicker, new LinearLayout.LayoutParams(dp(52), dp(52)));
        form.addView(textColorRow);

        LinearLayout fillColorRow = new LinearLayout(this);
        fillColorRow.setGravity(16);
        TextView fillColorLabel = new TextView(this);
        fillColorLabel.setText(R.string.touch_fill_color);
        fillColorLabel.setPadding(0, dp(8), dp(12), dp(8));
        fillColorRow.addView(fillColorLabel, new LinearLayout.LayoutParams(0, -2, 1.0f));

        ImageButton fillColorPicker = new ImageButton(this);
        fillColorPicker.setContentDescription(getString(R.string.touch_fill_color_picker));
        fillColorPicker.setImageResource(android.R.drawable.ic_menu_edit);
        fillColorPicker.setPadding(dp(8), dp(8), dp(8), dp(8));
        updateColorPickerIcon(fillColorPicker, selectedFillColor[0]);
        fillColorPicker.setOnClickListener(v -> showColorPicker(fillColorPicker, selectedFillColor));
        fillColorRow.addView(fillColorPicker, new LinearLayout.LayoutParams(dp(52), dp(52)));
        form.addView(fillColorRow);

        SeekBar.OnSeekBarChangeListener sizeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                widthLabel.setText(getString(R.string.touch_width_dp, widthSeek.getProgress() + 28));
                heightLabel.setText(getString(R.string.touch_height_dp, heightSeek.getProgress() + 28));
                int shapePos = shapeSpinner.getSelectedItemPosition();
                if (shapePos == TouchControlConfig.SHAPE_SQUARE || shapePos == TouchControlConfig.SHAPE_CIRCLE) {
                    heightSeek.setProgress(widthSeek.getProgress());
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
        widthSeek.setOnSeekBarChangeListener(sizeListener);
        heightSeek.setOnSeekBarChangeListener(sizeListener);

        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form);

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.touch_edit_dialog_title)
            .setView(formScroll)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.touch_apply_button, (dialog, which) -> {
                int selectedAction = actionTypeSpinner.getSelectedItemPosition();
                String trim = label.getText().toString().trim();
                if (trim.isEmpty()) {
                    trim = names[keySpinner.getSelectedItemPosition()];
                }
                // GeneralsX @tweak Flux Build 08/11/2026 v1.4.2 Up to 16, as the in-game key sheet ("Ctrl+Shift+F1").
                if (trim.length() > 16) {
                    trim = trim.substring(0, 16);
                }
                spec.label = trim;
                spec.actionType = selectedAction == 1
                    ? TouchControlConfig.ACTION_TOGGLE : TouchControlConfig.ACTION_CLICK;
                spec.keyCode = codes[keySpinner.getSelectedItemPosition()];
                spec.modifiers = (ctrl.isChecked() ? TouchControlConfig.MOD_CTRL : 0)
                    | (shift.isChecked() ? TouchControlConfig.MOD_SHIFT : 0)
                    | (alt.isChecked() ? TouchControlConfig.MOD_ALT : 0);
                spec.widthDp = widthSeek.getProgress() + 28.0f;
                spec.heightDp = heightSeek.getProgress() + 28.0f;
                spec.width = spec.widthDp;
                spec.height = spec.heightDp;
                spec.shape = shapeSpinner.getSelectedItemPosition();
                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    spec.heightDp = spec.widthDp;
                    spec.height = spec.width;
                }
                spec.fillColor = selectedFillColor[0];
                spec.borderColor = ThemeHelper.accentColor(this);
                spec.textColor = selectedTextColor[0];
                this.editor.invalidate();
                refreshLabels();
            })
            .show();
    }

    private void updateColorPickerIcon(ImageButton button, int color) {
        GradientDrawable swatch = new GradientDrawable();
        swatch.setShape(GradientDrawable.OVAL);
        swatch.setColor(color);
        swatch.setStroke(dp(2), ThemeHelper.accentColor(this));
        button.setBackground(swatch);
    }

    private void showColorPicker(final ImageButton button, final int[] selectedColor) {
        int[] palette = {
            0xFFFFFFFF, 0xFF000000, 0xFFCCCCCC, 0xFF444444,
            0xFFFF0000, Color.rgb(255, 152, 0), 0xFFFFFF00, 0xFF00FF00,
            0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, ThemeHelper.accentColor(this)
        };
        final int[] pending = { selectedColor[0] };

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setPadding(dp(16), dp(12), dp(16), dp(4));

        for (final int color : palette) {
            ImageButton swatch = new ImageButton(this);
            swatch.setContentDescription(String.format("#%06X", 0xFFFFFF & color));
            swatch.setPadding(dp(6), dp(6), dp(6), dp(6));
            updateColorPickerIcon(swatch, color);
            swatch.setOnClickListener(v -> {
                pending[0] = color;
                button.setBackground(v.getBackground());
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(52);
            lp.height = dp(52);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            grid.addView(swatch, lp);
        }

        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.touch_color_picker_title)
            .setView(grid)
            .setNegativeButton(R.string.common_cancel, null)
            .setPositiveButton(R.string.touch_apply_button, (dialog, which) -> {
                selectedColor[0] = pending[0];
                updateColorPickerIcon(button, pending[0]);
            })
            .create()
            .show();
    }

    private void resetDefaults() {
        boolean wasEnabled = this.config.enabled;
        this.config = TouchControlConfig.defaults();
        this.config.enabled = wasEnabled;
        this.selectedIndex = -1;
        this.selectedFixed = -1;
        java.util.Arrays.fill(this.fixedPos, null);
        this.fixedChanged = true;
        this.panSeek.setProgress(Math.round(this.config.panSensitivity * 100.0f));
        this.editor.invalidate();
        refreshLabels();
    }

    private void saveLayoutAsDefault() {
        TouchControlConfig toSave = this.config.createSnapshot();
        new Thread(() -> {
            String message;
            try {
                toSave.saveAsDefaultLayout(getApplicationContext());
                message = getString(R.string.touch_layout_saved_default);
            } catch (Exception e) {
                message = getString(R.string.touch_transfer_failed, String.valueOf(e.getMessage()));
            }
            final String text = message;
            runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
        }, "TouchLayoutSave").start();
    }

    private void loadSavedLayout() {
        new Thread(() -> {
            TouchControlConfig saved = null;
            String error = null;
            try {
                saved = TouchControlConfig.loadSavedLayout(getApplicationContext());
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final TouchControlConfig result = saved;
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (failure != null) {
                    Toast.makeText(this, getString(R.string.touch_transfer_failed, failure), Toast.LENGTH_LONG).show();
                } else if (result == null) {
                    Toast.makeText(this, R.string.touch_layout_none, Toast.LENGTH_LONG).show();
                } else {
                    boolean wasEnabled = this.config.enabled;
                    this.config = result;
                    this.config.enabled = wasEnabled;
                    this.selectedIndex = -1;
                    this.selectedFixed = -1;
                    this.panSeek.setProgress(Math.round(this.config.panSensitivity * 100.0f));
                    this.editor.invalidate();
                    refreshLabels();
                    Toast.makeText(this, R.string.touch_layout_loaded_editor, Toast.LENGTH_SHORT).show();
                }
            });
        }, "TouchLayoutLoad").start();
    }

    private void exportControls() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "GeneralsZH-Controls.json");
        startActivityForResult(intent, REQUEST_EXPORT_CONTROLS);
    }

    private void importControls() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_CONTROLS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            if (requestCode == REQUEST_EXPORT_CONTROLS) {
                this.config.exportTo(this, uri);
                Toast.makeText(this, R.string.touch_exported, Toast.LENGTH_SHORT).show();
                return;
            }
            if (requestCode == REQUEST_IMPORT_CONTROLS) {
                TouchControlConfig imported = TouchControlConfig.importFrom(this, uri);
                this.config = imported;
                this.selectedIndex = -1;
                this.selectedFixed = -1;
                this.panSeek.setProgress(Math.round(this.config.panSensitivity * 100.0f));
                this.config.save(this);
                String gamePath = SetupActivity.getSavedGamePath(this);
                if (gamePath != null) {
                    TouchControlConfig.prepareForLaunch(this, new File(gamePath));
                }
                this.editor.invalidate();
                refreshLabels();
                Toast.makeText(this, R.string.touch_imported, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "Invalid file" : e.getMessage();
            Toast.makeText(this, getString(R.string.touch_transfer_failed, msg), Toast.LENGTH_LONG).show();
        }
    }

    private void saveAndFinish() {
        this.config.save(this);
        if (this.fixedChanged) {
            for (int id = 0; id < HudButtons.COUNT; id++) {
                if (this.fixedPos[id] == null) {
                    HudButtons.clearPosition(this, id);
                } else {
                    HudButtons.savePosition(this, id, this.fixedPos[id][0], this.fixedPos[id][1]);
                }
            }
        }
        String gamePath = SetupActivity.getSavedGamePath(this);
        if (gamePath != null) {
            TouchControlConfig.prepareForLaunch(this, new File(gamePath));
        }
        Toast.makeText(this, R.string.touch_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void refreshLabels() {
        if (this.selectedIndex >= 0 && this.selectedIndex < this.config.buttons.size()) {
            TouchControlConfig.ButtonSpec spec = this.config.buttons.get(this.selectedIndex);
            this.selectedText.setText(getString(R.string.touch_selected_button, spec.label));
        } else if (this.selectedFixed >= 0) {
            this.selectedText.setText(getString(R.string.touch_selected_button, HudButtons.LABELS[this.selectedFixed]));
        } else {
            this.selectedText.setText(R.string.touch_drag_help_short);
        }
        this.panValue.setText(getString(R.string.touch_pan_speed, Math.round(this.config.panSensitivity * 100.0f)));
        syncSliders();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class EditorCanvas extends View {
        private int dragPointer = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<RectF> rects = new ArrayList<>();
        private final ArrayList<RectF> fixedRects = new ArrayList<>();
        private final float[] grab = new float[2];

        EditorCanvas() {
            super(TouchControlsActivity.this);
            this.text.setTextAlign(Paint.Align.CENTER);
            this.text.setFakeBoldText(true);
            setBackgroundColor(UiKit.color(TouchControlsActivity.this, R.color.gzh_surface_container_low));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // The screen-shaped area the buttons are placed on (plain background since v1.4.2 round 3).
            final RectF area = area();
            final RectF safe = safeArea(area);

            // Alignment grid lines (follows the custom text/outline role, so it is visible
            // on the Day palette too).
            this.paint.setStyle(Paint.Style.STROKE);
            this.paint.setStrokeWidth(1.0f);
            this.paint.setColor(ThemeColors.withAlpha(
                UiKit.color(TouchControlsActivity.this, R.color.gzh_outline_variant), 0x66));
            // GeneralsX v1.4.2 review: in the screen-shaped area the buttons are placed on.
            for (int i = 1; i < 10; i++) {
                float x = area.left + (area.width() * i) / 10.0f;
                canvas.drawLine(x, area.top, x, area.bottom, this.paint);
            }
            for (int i = 1; i < 6; i++) {
                float y = area.top + (area.height() * i) / 6.0f;
                canvas.drawLine(area.left, y, area.right, y, this.paint);
            }

            // Command panel guide
            this.paint.setStyle(Paint.Style.FILL);
            this.paint.setColor(Color.rgb(9, 13, 18));
            float panelTop = area.top + area.height() * 0.82f;
            canvas.drawRect(area.left, panelTop, area.right, area.bottom, this.paint);
            this.text.setColor(Color.argb(170, 255, 255, 255));
            this.text.setTextSize(dp(12));
            canvas.drawText(getString(R.string.touch_editor_panel_hint),
                area.centerX(), panelTop + dp(17), this.text);

            this.rects.clear();

            for (int i = 0; i < TouchControlsActivity.this.config.buttons.size(); i++) {
                TouchControlConfig.ButtonSpec spec = TouchControlsActivity.this.config.buttons.get(i);
                float density = getResources().getDisplayMetrics().density;
                // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 As the game sizes it
                // (HotkeyOverlayView.rebuildHitRects): the button's own size and scale.
                float rawW = spec.width > 0 ? spec.width : spec.widthDp;
                float rawH = spec.height > 0 ? spec.height : spec.heightDp;
                float width = rawW * spec.scale * density;
                float height = rawH * spec.scale * density;
                int alpha = Math.round(spec.opacity * 255.0f);

                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    float side = Math.min(width, height);
                    height = side;
                    width = side;
                }

                // As the game places it (HotkeyOverlayView.rebuildHitRects): in the safe area, kept inside.
                float bx = safe.left + (spec.x * safe.width()) - (width / 2.0f);
                float by = safe.top + (spec.y * safe.height()) - (height / 2.0f);
                bx = Math.max(safe.left, Math.min(safe.right - width, bx));
                by = Math.max(safe.top, Math.min(safe.bottom - height, by));
                RectF rect = new RectF(bx, by, bx + width, by + height);
                this.rects.add(rect);

                boolean selected = (i == TouchControlsActivity.this.selectedIndex);
                this.paint.setStyle(Paint.Style.FILL);
                int fill = ThemeColors.overlayFill(TouchControlsActivity.this, spec.fillColor);
                this.paint.setColor(selected ? lighten(fill, 0.16f) : fill);
                this.paint.setAlpha(selected ? Math.min(255, alpha + 45) : alpha);
                drawShape(canvas, rect, spec, this.paint);

                this.paint.setStyle(Paint.Style.STROKE);
                this.paint.setStrokeWidth(dp(selected ? 3 : 1));
                this.paint.setColor(ThemeHelper.accentColor(TouchControlsActivity.this));
                this.paint.setAlpha(255);
                drawShape(canvas, rect, spec, this.paint);

                this.text.setColor(defaultPreviewTextColor(spec.textColor));
                this.text.setTextSize(dp(spec.label.length() > 5 ? 12 : 15) * spec.scale);
                Paint.FontMetrics metrics = this.text.getFontMetrics();
                canvas.drawText(spec.label, rect.centerX(), rect.centerY() - ((metrics.ascent + metrics.descent) / 2.0f), this.text);

                if (spec.actionType == TouchControlConfig.ACTION_TOGGLE || TouchControlConfig.isModifierKey(spec.keyCode)) {
                    this.text.setTextSize(dp(9));
                    this.text.setColor(0xFFFFD54F);
                    canvas.drawText("T", rect.right - dp(8), rect.top + dp(10), this.text);
                }
            }

            // The fixed buttons that are switched on, as round gold-ringed buttons like in the game.
            final float size = TouchControlsActivity.this.fixedSizePx(area);
            this.fixedRects.clear();
            for (int id = 0; id < HudButtons.COUNT; id++) {
                if (!TouchControlsActivity.this.fixedOn[id]) {
                    this.fixedRects.add(null);
                    continue;
                }
                float[] f = TouchControlsActivity.this.fixedFraction(id);
                float fx = area.left + f[0] * area.width(), fy = area.top + f[1] * area.height();
                fx = Math.max(area.left, Math.min(area.right - size, fx));
                fy = Math.max(area.top, Math.min(area.bottom - size, fy));
                RectF r = new RectF(fx, fy, fx + size, fy + size);
                this.fixedRects.add(r);
                boolean sel = id == TouchControlsActivity.this.selectedFixed;
                this.paint.setStyle(Paint.Style.FILL);
                this.paint.setColor(sel ? Color.rgb(220, 181, 86) : Color.argb(170, 22, 33, 44));
                canvas.drawCircle(r.centerX(), r.centerY(), size * 0.5f, this.paint);
                this.paint.setStyle(Paint.Style.STROKE);
                this.paint.setStrokeWidth(dp(2));
                this.paint.setColor(Color.rgb(220, 181, 86));
                canvas.drawCircle(r.centerX(), r.centerY(), size * 0.5f - dp(1), this.paint);
                this.text.setColor(sel ? Color.rgb(22, 33, 44) : Color.rgb(240, 230, 200));
                this.text.setTextSize(HudButtons.LABELS[id].length() > 1 ? size * 0.26f : size * 0.42f);
                Paint.FontMetrics fm = this.text.getFontMetrics();
                canvas.drawText(HudButtons.LABELS[id], r.centerX(), r.centerY() - ((fm.ascent + fm.descent) / 2.0f), this.text);
            }
        }

        private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec, Paint p) {
            switch (spec.shape) {
                case TouchControlConfig.SHAPE_RECTANGLE:
                    canvas.drawRect(rect, p);
                    break;
                case TouchControlConfig.SHAPE_ROUNDED:
                default:
                    float radius = Math.min(dp(12), Math.min(rect.width(), rect.height()) * 0.25f);
                    canvas.drawRoundRect(rect, radius, radius, p);
                    break;
                case TouchControlConfig.SHAPE_SQUARE:
                    float side = Math.min(rect.width(), rect.height());
                    canvas.drawRect(rect.centerX() - (side / 2.0f), rect.centerY() - (side / 2.0f),
                        rect.centerX() + (side / 2.0f), rect.centerY() + (side / 2.0f), p);
                    break;
                case TouchControlConfig.SHAPE_CIRCLE:
                    canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.5f, p);
                    break;
                case TouchControlConfig.SHAPE_OVAL:
                    canvas.drawOval(rect, p);
                    break;
            }
        }

        /** Mirrors HotkeyOverlayView.defaultTextColor() so the preview shows the themed ink. */
        private int defaultPreviewTextColor(int specTextColor) {
            return (specTextColor == -1 || specTextColor == Color.WHITE)
                && ThemeColors.hasText(TouchControlsActivity.this)
                ? ThemeColors.text(TouchControlsActivity.this) : specTextColor;
        }

        private int lighten(int color, float amount) {
            int r = Color.red(color);
            int g = Color.green(color);
            int b = Color.blue(color);
            return Color.argb(
                Color.alpha(color),
                r + Math.round((255 - r) * amount),
                g + Math.round((255 - g) * amount),
                b + Math.round((255 - b) * amount)
            );
        }

        /**
         * GeneralsX @feature Flux Build 08/11/2026 v1.4.2 The screen-shaped area (the game's touch layout
         * is in fractions of the screen), fit-centred in the editor, so a button placed over the
         * picture lands where it will be in the game.
         */
        RectF area() {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return new RectF(0, 0, Math.max(w, 0), Math.max(h, 0));
            float aspect = TouchControlsActivity.this.screenAspect();
            float aw = w, ah = w / aspect;
            if (ah > h) { ah = h; aw = h * aspect; }
            return new RectF((w - aw) / 2f, (h - ah) / 2f, (w + aw) / 2f, (h + ah) / 2f);
        }

        /** The part of the area the game places buttons in: the screen minus the cutout's insets. */
        RectF safeArea(RectF area) {
            float[] f = TouchControlsActivity.this.safeFrac;
            return new RectF(area.left + area.width() * f[0], area.top + area.height() * f[1],
                area.right - area.width() * f[2], area.bottom - area.height() * f[3]);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    // The fixed buttons are drawn on top, so they are hit first.
                    int fixed = hitFixed(event.getX(), event.getY());
                    TouchControlsActivity.this.selectedFixed = fixed;
                    if (fixed >= 0) {
                        RectF r = this.fixedRects.get(fixed);
                        this.grab[0] = event.getX() - r.left;
                        this.grab[1] = event.getY() - r.top;
                    }
                    TouchControlsActivity.this.selectedIndex = fixed >= 0 ? -1 : hit(event.getX(), event.getY());
                    this.dragPointer = event.getPointerId(0);
                    TouchControlsActivity.this.refreshLabels();
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    this.dragPointer = -1;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (TouchControlsActivity.this.selectedFixed >= 0) {
                        int index = event.findPointerIndex(this.dragPointer);
                        if (index >= 0) {
                            RectF a = area();
                            float size = TouchControlsActivity.this.fixedSizePx(a);
                            float x = Math.max(a.left, Math.min(a.right - size, event.getX(index) - this.grab[0]));
                            float y = Math.max(a.top, Math.min(a.bottom - size, event.getY(index) - this.grab[1]));
                            TouchControlsActivity.this.fixedPos[TouchControlsActivity.this.selectedFixed] = new float[] {
                                (x - a.left) / Math.max(1f, a.width()), (y - a.top) / Math.max(1f, a.height()) };
                            TouchControlsActivity.this.fixedChanged = true;
                            invalidate();
                        }
                        return true;
                    }
                    if (TouchControlsActivity.this.selectedIndex >= 0
                            && TouchControlsActivity.this.selectedIndex < TouchControlsActivity.this.config.buttons.size()) {
                        int index = event.findPointerIndex(this.dragPointer);
                        if (index >= 0) {
                            TouchControlConfig.ButtonSpec spec = TouchControlsActivity.this.config.buttons.get(TouchControlsActivity.this.selectedIndex);
                            RectF a = safeArea(area());
                            spec.x = TouchControlConfig.clamp((event.getX(index) - a.left) / Math.max(1f, a.width()), 0.025f, 0.975f);
                            spec.y = TouchControlConfig.clamp((event.getY(index) - a.top) / Math.max(1f, a.height()), 0.06f, 0.94f);
                            invalidate();
                        }
                    }
                    return true;

                default:
                    return true;
            }
        }

        private int hitFixed(float x, float y) {
            for (int id = this.fixedRects.size() - 1; id >= 0; id--) {
                RectF r = this.fixedRects.get(id);
                if (r != null && r.contains(x, y)) {
                    return id;
                }
            }
            return -1;
        }

        private int hit(float x, float y) {
            for (int i = this.rects.size() - 1; i >= 0; i--) {
                if (this.rects.get(i).contains(x, y)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
