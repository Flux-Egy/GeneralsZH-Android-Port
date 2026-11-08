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
        buildUi();
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

        this.sizeValue = addSlider(sliders, R.string.touch_button_size, 65, 175,
            Math.round(this.config.buttonScale * 100.0f),
            value -> {
                this.config.buttonScale = value / 100.0f;
                this.editor.invalidate();
            });
        this.sizeSeek = (SeekBar) this.sizeValue.getTag();

        this.opacityValue = addSlider(sliders, R.string.touch_button_opacity, 25, 100,
            Math.round(this.config.buttonOpacity * 100.0f),
            value -> {
                this.config.buttonOpacity = value / 100.0f;
                this.editor.invalidate();
            });
        this.opacitySeek = (SeekBar) this.opacityValue.getTag();

        root.addView(sliders);
        refreshLabels();
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
                listener.onValue(value);
                label.setText(getString(titleRes, value));
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
                if (trim.length() > 8) {
                    trim = trim.substring(0, 8);
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
        this.panSeek.setProgress(Math.round(this.config.panSensitivity * 100.0f));
        this.sizeSeek.setProgress(Math.round(this.config.buttonScale * 100.0f));
        this.opacitySeek.setProgress(Math.round(this.config.buttonOpacity * 100.0f));
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
                    this.panSeek.setProgress(Math.round(this.config.panSensitivity * 100.0f));
                    this.sizeSeek.setProgress(Math.round(this.config.buttonScale * 100.0f));
                    this.opacitySeek.setProgress(Math.round(this.config.buttonOpacity * 100.0f));
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
                this.panSeek.setProgress(Math.round(this.config.panSensitivity * 100.0f));
                this.sizeSeek.setProgress(Math.round(this.config.buttonScale * 100.0f));
                this.opacitySeek.setProgress(Math.round(this.config.buttonOpacity * 100.0f));
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
        } else {
            this.selectedText.setText(R.string.touch_drag_help_short);
        }
        this.panValue.setText(getString(R.string.touch_pan_speed, Math.round(this.config.panSensitivity * 100.0f)));
        this.sizeValue.setText(getString(R.string.touch_button_size, Math.round(this.config.buttonScale * 100.0f)));
        this.opacityValue.setText(getString(R.string.touch_button_opacity, Math.round(this.config.buttonOpacity * 100.0f)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class EditorCanvas extends View {
        private int dragPointer = -1;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArrayList<RectF> rects = new ArrayList<>();

        EditorCanvas() {
            super(TouchControlsActivity.this);
            this.text.setTextAlign(Paint.Align.CENTER);
            this.text.setFakeBoldText(true);
            setBackgroundColor(UiKit.color(TouchControlsActivity.this, R.color.gzh_surface_container_low));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // Alignment grid lines (follows the custom text/outline role, so it is visible
            // on the Day palette too).
            this.paint.setStyle(Paint.Style.STROKE);
            this.paint.setStrokeWidth(1.0f);
            this.paint.setColor(ThemeColors.withAlpha(
                UiKit.color(TouchControlsActivity.this, R.color.gzh_outline_variant), 0x66));
            for (int i = 1; i < 10; i++) {
                float x = (getWidth() * i) / 10.0f;
                canvas.drawLine(x, 0.0f, x, getHeight(), this.paint);
            }
            for (int i = 1; i < 6; i++) {
                float y = (getHeight() * i) / 6.0f;
                canvas.drawLine(0.0f, y, getWidth(), y, this.paint);
            }

            // Command panel guide
            this.paint.setStyle(Paint.Style.FILL);
            this.paint.setColor(Color.rgb(9, 13, 18));
            canvas.drawRect(0.0f, getHeight() * 0.82f, getWidth(), getHeight(), this.paint);
            this.text.setColor(Color.argb(170, 255, 255, 255));
            this.text.setTextSize(dp(12));
            canvas.drawText(getString(R.string.touch_editor_panel_hint),
                getWidth() * 0.5f, (getHeight() * 0.82f) + dp(17), this.text);

            this.rects.clear();
            int alpha = Math.round(TouchControlsActivity.this.config.buttonOpacity * 255.0f);

            for (int i = 0; i < TouchControlsActivity.this.config.buttons.size(); i++) {
                TouchControlConfig.ButtonSpec spec = TouchControlsActivity.this.config.buttons.get(i);
                float density = getResources().getDisplayMetrics().density;
                float width = spec.widthDp * TouchControlsActivity.this.config.buttonScale * density;
                float height = spec.heightDp * TouchControlsActivity.this.config.buttonScale * density;

                if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                    float side = Math.min(width, height);
                    height = side;
                    width = side;
                }

                RectF rect = new RectF(
                    (spec.x * getWidth()) - (width / 2.0f),
                    (spec.y * getHeight()) - (height / 2.0f),
                    (spec.x * getWidth()) + (width / 2.0f),
                    (spec.y * getHeight()) + (height / 2.0f)
                );
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
                this.text.setTextSize(dp(spec.label.length() > 5 ? 12 : 15) * TouchControlsActivity.this.config.buttonScale);
                Paint.FontMetrics metrics = this.text.getFontMetrics();
                canvas.drawText(spec.label, rect.centerX(), rect.centerY() - ((metrics.ascent + metrics.descent) / 2.0f), this.text);

                if (spec.actionType == TouchControlConfig.ACTION_TOGGLE) {
                    this.text.setTextSize(dp(9));
                    this.text.setColor(0xFFFFD54F);
                    canvas.drawText("T", rect.right - dp(8), rect.top + dp(10), this.text);
                }
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

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    TouchControlsActivity.this.selectedIndex = hit(event.getX(), event.getY());
                    this.dragPointer = event.getPointerId(0);
                    TouchControlsActivity.this.refreshLabels();
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    this.dragPointer = -1;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (TouchControlsActivity.this.selectedIndex >= 0
                            && TouchControlsActivity.this.selectedIndex < TouchControlsActivity.this.config.buttons.size()) {
                        int index = event.findPointerIndex(this.dragPointer);
                        if (index >= 0) {
                            TouchControlConfig.ButtonSpec spec = TouchControlsActivity.this.config.buttons.get(TouchControlsActivity.this.selectedIndex);
                            spec.x = TouchControlConfig.clamp(event.getX(index) / getWidth(), 0.025f, 0.975f);
                            spec.y = TouchControlConfig.clamp(event.getY(index) / getHeight(), 0.06f, 0.94f);
                            invalidate();
                        }
                    }
                    return true;

                default:
                    return true;
            }
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
