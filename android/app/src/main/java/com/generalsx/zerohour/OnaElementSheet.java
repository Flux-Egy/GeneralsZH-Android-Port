/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 A control's settings, as a panel on the right of the editor
 * (game and launcher alike): what each slot sends (a key, a chord or a macro), how a button behaves, its look (label,
 * built-in icon, shape, colours, opacity) and exact size, position and rotation. Works on a copy; OK applies it.
 * Plain framework dialogs, so it opens over the game's window as well as over the launcher.
 */
final class OnaElementSheet {

    interface Done {
        void apply(OnaProfile.Element edited);
    }

    private OnaElementSheet() {
    }

    static Context dialogContext(Context c) {
        return new ContextThemeWrapper(c, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private static boolean gone(Context c) {
        while (c instanceof ContextWrapper && !(c instanceof Activity)) c = ((ContextWrapper) c).getBaseContext();
        return c instanceof Activity && (((Activity) c).isFinishing() || ((Activity) c).isDestroyed());
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // =============================================================== small builders

    private static TextView heading(LinearLayout root, String text) {
        Context c = root.getContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF90CAF9);
        t.setPadding(0, dp(c, 14), 0, dp(c, 4));
        root.addView(t);
        return t;
    }

    private static TextView note(LinearLayout root, String text) {
        Context c = root.getContext();
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setAlpha(0.75f);
        root.addView(t);
        return t;
    }

    private static Button button(Context c, String text, View.OnClickListener l) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private static LinearLayout hrow(LinearLayout root) {
        LinearLayout r = new LinearLayout(root.getContext());
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(r);
        return r;
    }

    private static String actionText(Context c, OnaProfile.Action a) {
        if (a.isEmpty()) return c.getString(R.string.ona_key_none);
        if (a.isMacro()) return c.getString(R.string.ona_action_macro_short, a.macro.size()) + "  " + a.label();
        return a.label();
    }

    // =============================================================== the panel

    static void show(final Context host, final OnaProfile.Element work, final Done done) {
        if (gone(host)) return;
        final Context ctx = dialogContext(host);
        final int pad = dp(ctx, 16);
        ScrollView scroll = new ScrollView(ctx);
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(ctx, 4), pad, pad);
        scroll.addView(root);
        work.normalizeSlots();
        final boolean button = work.type == OnaProfile.TYPE_BUTTON;
        final boolean row = work.type == OnaProfile.TYPE_BUTTON_ROW;

        // ---- actions
        heading(root, ctx.getString(R.string.ona_sheet_actions));
        final LinearLayout slots = new LinearLayout(ctx);
        slots.setOrientation(LinearLayout.VERTICAL);
        root.addView(slots);
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            slots.removeAllViews();
            String[] dirNames = { "↑", "→", "↓", "←" };
            for (int i = 0; i < work.actions.size(); i++) {
                final int slot = i;
                String prefix = work.directional() ? dirNames[i] + "  " : row ? (i + 1) + ":  " : "";
                Button b = button(ctx, prefix + actionText(ctx, work.actions.get(i)), v ->
                    editAction(ctx, work.actions.get(slot), !work.directional(), rebuild[0]));
                b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                slots.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (row) {
                LinearLayout r = new LinearLayout(ctx);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView count = new TextView(ctx);
                count.setText(ctx.getString(R.string.ona_row_cells, work.actions.size()));
                r.addView(count);
                r.addView(button(ctx, "+", v -> {
                    if (work.actions.size() < OnaProfile.MAX_ROW_CELLS) work.actions.add(new OnaProfile.Action());
                    rebuild[0].run();
                }));
                r.addView(button(ctx, "−", v -> {
                    if (work.actions.size() > 1) work.actions.remove(work.actions.size() - 1);
                    rebuild[0].run();
                }));
                slots.addView(r);
            }
        };
        rebuild[0].run();
        if (!work.directional()) note(root, ctx.getString(R.string.ona_macro_online_note));

        // ---- behaviour
        final RadioGroup behavior = new RadioGroup(ctx);
        final CheckBox eight = new CheckBox(ctx);
        final SeekBar deadzone = new SeekBar(ctx);
        if (button || row) {
            heading(root, ctx.getString(R.string.ona_sheet_behavior));
            behavior.setOrientation(RadioGroup.HORIZONTAL);
            int[] names = { R.string.ona_behavior_hold, R.string.ona_behavior_tap, R.string.ona_behavior_toggle };
            int count = button ? 3 : 2;
            for (int i = 0; i < count; i++) {
                RadioButton rb = new RadioButton(ctx);
                rb.setId(View.generateViewId());
                rb.setTag(i);
                rb.setText(names[i]);
                behavior.addView(rb);
                if (work.behavior == i || (row && i == 0 && work.behavior == OnaProfile.BEHAVIOR_TOGGLE)) rb.setChecked(true);
            }
            root.addView(behavior);
        } else if (work.type == OnaProfile.TYPE_DPAD) {
            heading(root, ctx.getString(R.string.ona_sheet_behavior));
            eight.setText(R.string.ona_el_eight_way);
            eight.setChecked(work.eightWay);
            root.addView(eight);
        } else if (work.type == OnaProfile.TYPE_STICK) {
            heading(root, ctx.getString(R.string.ona_sheet_behavior));
            final TextView dzLabel = new TextView(ctx);
            root.addView(dzLabel);
            deadzone.setMax(80);
            deadzone.setProgress(Math.round(work.deadzone * 100f));
            dzLabel.setText(ctx.getString(R.string.ona_el_deadzone, deadzone.getProgress()));
            deadzone.setOnSeekBarChangeListener(new SimpleSeek() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    dzLabel.setText(ctx.getString(R.string.ona_el_deadzone, p));
                }
            });
            root.addView(deadzone);
        }

        // ---- look
        heading(root, ctx.getString(R.string.ona_sheet_look));
        final EditText label = new EditText(ctx);
        label.setSingleLine(true);
        label.setHint(R.string.ona_el_text_hint);
        label.setText(work.text);
        label.setFilters(new InputFilter[] { new InputFilter.LengthFilter(OnaProfile.MAX_TEXT) });
        if (button) root.addView(label);

        if (button) {
            LinearLayout looks = hrow(root);
            final String[] shapeIcons = { "●", "■", "▬", "⬭" };
            final Button shape = button(ctx, ctx.getString(R.string.ona_el_shape) + "  " + shapeIcons[work.shape], null);
            shape.setOnClickListener(v -> {
                work.shape = (work.shape + 1) % shapeIcons.length;
                shape.setText(ctx.getString(R.string.ona_el_shape) + "  " + shapeIcons[work.shape]);
            });
            looks.addView(shape);
            final Button icon = button(ctx, "", null);
            final Runnable paintIcon = () -> icon.setText(ctx.getString(R.string.ona_el_icon) + ":  "
                + (work.icon.isEmpty() ? ctx.getString(R.string.ona_key_none) : work.icon));
            paintIcon.run();
            icon.setOnClickListener(v -> pickIcon(ctx, work.icon, name -> {
                work.icon = name;
                paintIcon.run();
            }));
            looks.addView(icon);
        }

        TextView colorTitle = new TextView(ctx);
        colorTitle.setText(R.string.ona_el_color);
        root.addView(colorTitle);
        final int[] color = { work.color };
        final EditText hex = new EditText(ctx);
        swatches(root, color, OnaRenderer.DEFAULT_COLOR, () -> hex.setText(color[0] == 0 ? "" : OnaProfile.colorHex(color[0])));
        hex.setSingleLine(true);
        hex.setHint("#RRGGBB");
        hex.setText(work.color == 0 ? "" : OnaProfile.colorHex(work.color));
        hex.setFilters(new InputFilter[] { new InputFilter.LengthFilter(9) });
        root.addView(hex);

        TextView labelColorTitle = new TextView(ctx);
        labelColorTitle.setText(R.string.ona_el_label_color);
        root.addView(labelColorTitle);
        final int[] labelColor = { work.labelColor };
        swatches(root, labelColor, Color.WHITE, null);

        final CheckBox ownOpacity = new CheckBox(ctx);
        ownOpacity.setText(R.string.ona_el_opacity_own);
        ownOpacity.setChecked(work.alpha >= 0f);
        root.addView(ownOpacity);
        final TextView opacityLabel = new TextView(ctx);
        root.addView(opacityLabel);
        final SeekBar opacity = new SeekBar(ctx);
        opacity.setMax(95); // 5% .. 100%
        opacity.setProgress(Math.round((work.alpha >= 0f ? work.alpha : 0.45f) * 100f) - 5);
        opacityLabel.setText(ctx.getString(R.string.ona_opacity_label, opacity.getProgress() + 5));
        opacity.setEnabled(ownOpacity.isChecked());
        opacity.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                opacityLabel.setText(ctx.getString(R.string.ona_opacity_label, p + 5));
            }
        });
        ownOpacity.setOnCheckedChangeListener((b, on) -> opacity.setEnabled(on));
        root.addView(opacity);

        // ---- size and position
        heading(root, ctx.getString(R.string.ona_sheet_geometry));
        GridLayout geo = new GridLayout(ctx);
        geo.setColumnCount(4);
        root.addView(geo);
        final EditText fx = numberField(geo, ctx.getString(R.string.ona_el_x), Math.round(work.x * 100f));
        final EditText fy = numberField(geo, ctx.getString(R.string.ona_el_y), Math.round(work.y * 100f));
        final EditText fscale = numberField(geo, ctx.getString(R.string.ona_el_scale), Math.round(work.scale * 100f));
        final EditText frot = numberField(geo, ctx.getString(R.string.ona_el_rotation), Math.round(work.rotation));
        final EditText fw = numberField(geo, ctx.getString(R.string.ona_el_width), Math.round(work.width * 100f));
        final EditText fh = numberField(geo, ctx.getString(R.string.ona_el_height), Math.round(work.height * 100f));

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setTitle(R.string.ona_el_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                if (button) {
                    work.text = label.getText().toString().trim();
                }
                if (button || row) {
                    int id = behavior.getCheckedRadioButtonId();
                    View rb = id != View.NO_ID ? behavior.findViewById(id) : null;
                    work.behavior = rb != null && rb.getTag() instanceof Integer ? (Integer) rb.getTag() : OnaProfile.BEHAVIOR_HOLD;
                }
                if (work.type == OnaProfile.TYPE_DPAD) work.eightWay = eight.isChecked();
                if (work.type == OnaProfile.TYPE_STICK) work.deadzone = deadzone.getProgress() / 100f;
                String h = hex.getText().toString().trim();
                int parsed = h.isEmpty() ? 0 : OnaProfile.parseColor(h);
                work.color = h.isEmpty() ? 0 : (parsed != 0 ? parsed : color[0]);
                work.labelColor = labelColor[0];
                work.alpha = ownOpacity.isChecked() ? (opacity.getProgress() + 5) / 100f : -1f;
                work.x = OnaProfile.clamp(readInt(fx, Math.round(work.x * 100f)) / 100f, 0f, 1f);
                work.y = OnaProfile.clamp(readInt(fy, Math.round(work.y * 100f)) / 100f, 0f, 1f);
                work.scale = OnaProfile.clamp(readInt(fscale, Math.round(work.scale * 100f)) / 100f, 0.3f, 4f);
                int rot = readInt(frot, Math.round(work.rotation));
                work.rotation = ((rot % 360) + 360) % 360;
                work.width = OnaProfile.clamp(readInt(fw, Math.round(work.width * 100f)) / 100f, 0.3f, 4f);
                work.height = OnaProfile.clamp(readInt(fh, Math.round(work.height * 100f)) / 100f, 0.3f, 4f);
                if (work.directional()) work.height = work.width; // a round control stays round
                work.normalizeSlots();
                done.apply(work);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        asSidePanel(dialog, host);
    }

    /** The dialog as a panel down the right side, so the layout stays in view on the left. */
    private static void asSidePanel(AlertDialog dialog, Context host) {
        Window w = dialog.getWindow();
        if (w == null) return;
        int screenW = host.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(host, 460), Math.max(dp(host, 320), (int) (screenW * 0.55f)));
        w.setGravity(Gravity.END | Gravity.FILL_VERTICAL);
        w.setLayout(Math.min(width, screenW), ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static int readInt(EditText f, int def) {
        try {
            return Integer.parseInt(f.getText().toString().trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static EditText numberField(GridLayout grid, String label, int value) {
        Context c = grid.getContext();
        TextView t = new TextView(c);
        t.setText(label);
        t.setPadding(0, 0, dp(c, 6), 0);
        grid.addView(t);
        EditText f = new EditText(c);
        f.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        f.setText(String.valueOf(value));
        f.setMinWidth(dp(c, 64));
        f.setSelectAllOnFocus(true);
        grid.addView(f);
        return f;
    }

    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar s) { }
        @Override public void onStopTrackingTouch(SeekBar s) { }
    }

    /** A row of colour swatches (the first is "default"); the chosen one has a white ring. */
    private static void swatches(LinearLayout root, final int[] value, final int defaultShown, final Runnable changed) {
        final Context c = root.getContext();
        HorizontalScrollView hs = new HorizontalScrollView(c);
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        root.addView(hs);
        final int size = dp(c, 30);
        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            for (int i = 0; i < row.getChildCount(); i++) {
                View v = row.getChildAt(i);
                int col = (Integer) v.getTag();
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(col == 0 ? defaultShown : col);
                boolean on = col == value[0];
                g.setStroke(dp(c, on ? 3 : 1), on ? Color.WHITE : Color.argb(120, 255, 255, 255));
                v.setBackground(g);
            }
        };
        for (int col : OnaRenderer.PALETTE) {
            View v = new View(c);
            v.setTag(col);
            v.setOnClickListener(x -> {
                value[0] = (Integer) x.getTag();
                paint[0].run();
                if (changed != null) changed.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(c, 3), dp(c, 4), dp(c, 3), dp(c, 4));
            row.addView(v, lp);
        }
        paint[0].run();
    }

    // =============================================================== one slot's action

    /** Edits {@code target} in place on OK: a chord (keys together) or, when allowed, a macro (steps in order). */
    static void editAction(final Context ctx, final OnaProfile.Action target, final boolean allowMacro, final Runnable after) {
        if (gone(ctx)) return;
        final OnaProfile.Action work = target.copy();
        final int pad = dp(ctx, 16);
        ScrollView scroll = new ScrollView(ctx);
        final LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(ctx, 4), pad, pad);
        scroll.addView(root);

        final boolean[] macroMode = { allowMacro && work.isMacro() };
        final RadioGroup mode = new RadioGroup(ctx);
        mode.setOrientation(RadioGroup.VERTICAL);
        final RadioButton chordBtn = new RadioButton(ctx);
        chordBtn.setId(View.generateViewId());
        chordBtn.setText(R.string.ona_action_chord);
        final RadioButton macroBtn = new RadioButton(ctx);
        macroBtn.setId(View.generateViewId());
        macroBtn.setText(R.string.ona_action_macro);
        mode.addView(chordBtn);
        if (allowMacro) mode.addView(macroBtn);
        root.addView(mode);
        if (allowMacro) note(root, ctx.getString(R.string.ona_macro_online_note));

        final LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body);

        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            body.removeAllViews();
            if (!macroMode[0]) {
                HorizontalScrollView hs = new HorizontalScrollView(ctx);
                LinearLayout keys = new LinearLayout(ctx);
                keys.setOrientation(LinearLayout.HORIZONTAL);
                hs.addView(keys);
                body.addView(hs);
                for (int i = 0; i < work.keys.size(); i++) {
                    final int at = i;
                    keys.addView(button(ctx, work.keys.get(i).label + "  ×", v -> {
                        work.keys.remove(at);
                        rebuild[0].run();
                    }));
                }
                if (work.keys.size() < OnaProfile.MAX_CHORD) {
                    body.addView(button(ctx, ctx.getString(R.string.ona_action_add_key), v -> pickKey(ctx, k -> {
                        if (k.code != 0 && !work.keys.contains(k) && work.keys.size() < OnaProfile.MAX_CHORD) work.keys.add(k);
                        rebuild[0].run();
                    })));
                }
                return;
            }
            final String[] ops = {
                ctx.getString(R.string.ona_step_tap), ctx.getString(R.string.ona_step_down),
                ctx.getString(R.string.ona_step_up), ctx.getString(R.string.ona_step_wait),
            };
            for (int i = 0; i < work.macro.size(); i++) {
                final int at = i;
                final OnaProfile.Step s = work.macro.get(i);
                LinearLayout r = new LinearLayout(ctx);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView num = new TextView(ctx);
                num.setText((i + 1) + ".");
                num.setMinWidth(dp(ctx, 24));
                r.addView(num);
                r.addView(button(ctx, ops[s.op], v -> {
                    s.op = (s.op + 1) % ops.length;
                    if (s.op == OnaProfile.OP_WAIT && s.ms <= 0) s.ms = 100;
                    rebuild[0].run();
                }));
                if (s.op == OnaProfile.OP_WAIT) {
                    final EditText ms = new EditText(ctx);
                    ms.setInputType(InputType.TYPE_CLASS_NUMBER);
                    ms.setText(String.valueOf(s.ms));
                    ms.setMinWidth(dp(ctx, 72));
                    ms.setSelectAllOnFocus(true);
                    ms.addTextChangedListener(new android.text.TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) { }
                        @Override public void onTextChanged(CharSequence t, int a, int b, int c) { }
                        @Override public void afterTextChanged(android.text.Editable e) {
                            s.ms = OnaProfile.clampInt(readInt(ms, s.ms), 0, OnaProfile.MAX_WAIT_MS);
                        }
                    });
                    r.addView(ms);
                    TextView unit = new TextView(ctx);
                    unit.setText(R.string.ona_step_ms);
                    r.addView(unit);
                } else {
                    r.addView(button(ctx, s.key.code == 0 ? ctx.getString(R.string.ona_key_none) : s.key.label,
                        v -> pickKey(ctx, k -> {
                            s.key = k;
                            rebuild[0].run();
                        })));
                }
                r.addView(button(ctx, "▲", v -> {
                    if (at > 0) {
                        work.macro.add(at - 1, work.macro.remove(at));
                        rebuild[0].run();
                    }
                }));
                r.addView(button(ctx, "▼", v -> {
                    if (at < work.macro.size() - 1) {
                        work.macro.add(at + 1, work.macro.remove(at));
                        rebuild[0].run();
                    }
                }));
                r.addView(button(ctx, "×", v -> {
                    work.macro.remove(at);
                    rebuild[0].run();
                }));
                HorizontalScrollView hs = new HorizontalScrollView(ctx);
                hs.addView(r);
                body.addView(hs);
            }
            if (work.macro.size() < OnaProfile.MAX_STEPS) {
                body.addView(button(ctx, ctx.getString(R.string.ona_action_add_step), v -> pickKey(ctx, k -> {
                    if (work.macro.size() < OnaProfile.MAX_STEPS) work.macro.add(new OnaProfile.Step(OnaProfile.OP_TAP, k, 0));
                    rebuild[0].run();
                })));
            }
        };
        (macroMode[0] ? macroBtn : chordBtn).setChecked(true);
        mode.setOnCheckedChangeListener((g, id) -> {
            boolean m = id == macroBtn.getId();
            if (m == macroMode[0]) return;
            macroMode[0] = m;
            if (m && work.macro.isEmpty() && !work.keys.isEmpty()) {
                // Start the macro from the chord: modifiers held around a tap of the others (Ctrl+1 -> CTRL down, 1, CTRL up).
                for (OnaProfile.Key k : work.keys) {
                    if (OnaProfile.isModifier(k.code)) work.macro.add(new OnaProfile.Step(OnaProfile.OP_DOWN, k, 0));
                }
                for (OnaProfile.Key k : work.keys) {
                    if (!OnaProfile.isModifier(k.code)) work.macro.add(new OnaProfile.Step(OnaProfile.OP_TAP, k, 0));
                }
                for (int i = work.keys.size() - 1; i >= 0; i--) {
                    OnaProfile.Key k = work.keys.get(i);
                    if (OnaProfile.isModifier(k.code)) work.macro.add(new OnaProfile.Step(OnaProfile.OP_UP, k, 0));
                }
            }
            rebuild[0].run();
        });
        rebuild[0].run();

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setTitle(R.string.ona_sheet_actions)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                target.keys.clear();
                target.macro.clear();
                if (macroMode[0]) {
                    for (OnaProfile.Step s : work.macro) {
                        if (s.op == OnaProfile.OP_WAIT || s.key.code != 0) target.macro.add(s.copy());
                    }
                    // A macro of waits only does nothing: keep it empty rather than a silent control.
                    boolean anyKey = false;
                    for (OnaProfile.Step s : target.macro) anyKey |= s.op != OnaProfile.OP_WAIT;
                    if (!anyKey) target.macro.clear();
                } else {
                    target.keys.addAll(work.keys);
                }
                if (after != null) after.run();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        asSidePanel(dialog, ctx);
    }

    // =============================================================== pickers

    interface KeyPicked {
        void picked(OnaProfile.Key key);
    }

    /** Every key as a keyboard-like grid, group by group. */
    static void pickKey(final Context ctx, final KeyPicked done) {
        if (gone(ctx)) return;
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 10);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        final AlertDialog[] dialog = new AlertDialog[1];
        List<OnaProfile.Key> keys = OnaProfile.PICKER;
        for (int g = 0; g < OnaProfile.GROUP_COUNT; g++) {
            GridLayout grid = new GridLayout(ctx);
            grid.setColumnCount(g == OnaProfile.GROUP_LETTERS || g == OnaProfile.GROUP_NUMBERS ? 10 : 6);
            for (final OnaProfile.Key k : keys) {
                if (k.group != g) continue;
                TextView b = new TextView(ctx);
                b.setText(k.label);
                b.setGravity(Gravity.CENTER);
                b.setTextColor(Color.WHITE);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.label.length() > 3 ? 11 : 14);
                b.setMinWidth(dp(ctx, 36));
                b.setMinHeight(dp(ctx, 38));
                b.setPadding(dp(ctx, 4), 0, dp(ctx, 4), 0);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xFF37474F);
                bg.setCornerRadius(dp(ctx, 6));
                b.setBackground(bg);
                b.setOnClickListener(v -> {
                    if (dialog[0] != null) dialog[0].dismiss();
                    done.picked(k);
                });
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                lp.setMargins(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2));
                grid.addView(b, lp);
            }
            root.addView(grid);
            View gap = new View(ctx);
            root.addView(gap, new LinearLayout.LayoutParams(1, dp(ctx, 10)));
        }
        dialog[0] = new AlertDialog.Builder(ctx)
            .setTitle(R.string.ona_el_keys)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    interface IconPicked {
        void picked(String name);
    }

    /** A small view that draws one built-in icon (or "None" as text). */
    private static final class IconCell extends View {
        private final String name;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        IconCell(Context c, String name, boolean selected) {
            super(c);
            this.name = name;
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(11f * c.getResources().getDisplayMetrics().density);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(selected ? 0xFF1565C0 : 0xFF37474F);
            bg.setCornerRadius(6f * c.getResources().getDisplayMetrics().density);
            setBackground(bg);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            float inset = getWidth() * 0.2f;
            box.set(inset, inset, getWidth() - inset, getHeight() - inset);
            if (name.isEmpty()) {
                Paint.FontMetrics fm = paint.getFontMetrics();
                c.drawText(getContext().getString(R.string.ona_key_none), getWidth() / 2f,
                    getHeight() / 2f - (fm.ascent + fm.descent) / 2f, paint);
            } else {
                OnaIcons.draw(c, name, box, paint);
            }
        }
    }

    static void pickIcon(final Context ctx, String current, final IconPicked done) {
        if (gone(ctx)) return;
        ScrollView scroll = new ScrollView(ctx);
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(6);
        int pad = dp(ctx, 10);
        grid.setPadding(pad, pad, pad, pad);
        scroll.addView(grid);
        final AlertDialog[] dialog = new AlertDialog[1];
        java.util.List<String> names = OnaIcons.names();
        names.remove("lock"); // the editor's own mark
        names.add(0, "");
        int size = dp(ctx, 46);
        for (final String n : names) {
            IconCell cell = new IconCell(ctx, n, n.equals(current));
            cell.setContentDescription(n.isEmpty() ? ctx.getString(R.string.ona_key_none) : n.toLowerCase(Locale.ROOT));
            cell.setOnClickListener(v -> {
                if (dialog[0] != null) dialog[0].dismiss();
                done.picked(n);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3));
            grid.addView(cell, lp);
        }
        dialog[0] = new AlertDialog.Builder(ctx)
            .setTitle(R.string.ona_el_icon)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }
}
