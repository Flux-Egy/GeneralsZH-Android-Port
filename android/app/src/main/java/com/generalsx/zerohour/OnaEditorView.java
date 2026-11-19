/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Adapted from ONA Launcher 2 "ona-controls" (view/OverlayEditorView.kt), GPL-3.0: drag to move and pinch to
 * resize, ported to Java and extended.
 */
package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.SparseIntArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 The ONA layout editor, one implementation for the game (the
 * gear: over the real battlefield, OnaOverlayView) and the launcher (OnaEditorActivity: over a sketch of the
 * Generals HUD, with a Test mode that shows what each control sends). Drag a control to move it (grid snap and
 * alignment guides), drag its corner handle to change width and height, its top handle to rotate it (15-degree
 * steps), pinch to scale it; double-tap or Edit opens its settings (OnaElementSheet). Duplicate, delete, bring to
 * front, lock, undo / redo (30 steps). Nothing is written until Save.
 */
final class OnaEditorView extends FrameLayout {

    interface Host {
        void onSave(OnaProfile edited);

        void onCancel();
    }

    private static final int UNDO_DEPTH = 30;

    private final boolean inGame;
    private final Host host;
    private final float density;
    private final OnaRenderer renderer;
    private OnaProfile profile;
    private int selected = -1;
    private boolean snapGrid = true;
    private boolean barAtBottom;
    private boolean testing;
    private final ArrayDeque<OnaProfile> undo = new ArrayDeque<>();
    private final ArrayDeque<OnaProfile> redo = new ArrayDeque<>();

    private final Board board;
    private final HorizontalScrollView barScroll;
    private final LinearLayout bar;
    private final TextView testLog;
    private final List<String> logLines = new ArrayList<>();
    private OnaInput testInput;

    // Toolbar buttons that change state.
    private TextView btnEdit;
    private TextView btnCopy;
    private TextView btnDelete;
    private TextView btnFront;
    private TextView btnLock;
    private TextView btnUndo;
    private TextView btnRedo;
    private TextView btnGrid;
    private TextView btnTest;
    private TextView btnMove;

    OnaEditorView(Context ctx, OnaProfile profile, boolean inGame, Host host) {
        super(ctx);
        this.inGame = inGame;
        this.host = host;
        this.density = ctx.getResources().getDisplayMetrics().density;
        this.renderer = new OnaRenderer(density);
        this.profile = profile.copy();
        for (OnaProfile.Element e : this.profile.elements) e.normalizeSlots();

        board = new Board(ctx);
        addView(board, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        bar.setPadding(pad, pad, pad, pad);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.argb(235, 18, 18, 20));
        barBg.setCornerRadius(dp(14));
        bar.setBackground(barBg);
        barScroll = new HorizontalScrollView(ctx);
        barScroll.setHorizontalScrollBarEnabled(false);
        barScroll.addView(bar);
        addView(barScroll, barParams());
        buildBar();

        testLog = new TextView(ctx);
        testLog.setTextColor(Color.WHITE);
        testLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        testLog.setTypeface(Typeface.MONOSPACE);
        testLog.setPadding(dp(10), dp(6), dp(10), dp(6));
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(Color.argb(220, 10, 30, 40));
        logBg.setCornerRadius(dp(10));
        testLog.setBackground(logBg);
        testLog.setVisibility(GONE);
        addView(testLog, logParams());
        refreshBar();
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    private LayoutParams barParams() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL | (barAtBottom ? Gravity.BOTTOM : Gravity.TOP);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        return lp;
    }

    private LayoutParams logParams() {
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL | (barAtBottom ? Gravity.TOP : Gravity.BOTTOM);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        return lp;
    }

    OnaProfile profile() {
        return profile;
    }

    /** Whether anything was changed since the editor opened. */
    boolean dirty() {
        return !undo.isEmpty();
    }

    // =============================================================== toolbar

    private TextView barButton(String label, int color, final Runnable action) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(dp(40));
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        lp.setMargins(dp(2), 0, dp(2), 0);
        bar.addView(b, lp);
        return b;
    }

    private String str(int id) {
        return getContext().getString(id);
    }

    private void buildBar() {
        final int tool = 0xFF263238;
        barButton(str(R.string.ona_edit_add), tool, this::showAddDialog);
        btnEdit = barButton(str(R.string.ona_edit_edit), tool, () -> openSheet(selected));
        btnCopy = barButton(str(R.string.ona_edit_duplicate), tool, this::duplicateSelected);
        btnDelete = barButton(str(R.string.ona_edit_delete), 0xFF4A2A2A, this::deleteSelected);
        btnFront = barButton(str(R.string.ona_edit_front), tool, this::bringSelectedToFront);
        btnLock = barButton(str(R.string.ona_edit_lock), tool, this::toggleLock);
        btnUndo = barButton("↶", tool, this::undo);
        btnRedo = barButton("↷", tool, this::redo);
        btnGrid = barButton(str(R.string.ona_edit_grid), tool, () -> {
            snapGrid = !snapGrid;
            refreshBar();
            board.invalidate();
        });
        if (!inGame) btnTest = barButton(str(R.string.ona_edit_test), 0xFF0D3B4A, this::toggleTest);
        btnMove = barButton("↓", tool, () -> {
            barAtBottom = !barAtBottom;
            barScroll.setLayoutParams(barParams());
            testLog.setLayoutParams(logParams());
            refreshBar();
        });
        barButton(str(R.string.ona_edit_save), 0xFF1B5E20, () -> {
            stopTest();
            host.onSave(profile.copy());
        });
        barButton(str(R.string.ona_edit_cancel), 0xFF5C1B1B, () -> {
            stopTest();
            host.onCancel();
        });
    }

    private static void enable(TextView b, boolean on) {
        if (b == null) return;
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.35f);
    }

    private void refreshBar() {
        boolean sel = selected >= 0 && selected < profile.elements.size() && !testing;
        enable(btnEdit, sel);
        enable(btnCopy, sel && profile.elements.size() < OnaProfile.MAX_ELEMENTS);
        enable(btnDelete, sel);
        enable(btnFront, sel && selected < profile.elements.size() - 1);
        enable(btnLock, sel);
        if (btnLock != null) {
            btnLock.setText(str(sel && profile.elements.get(selected).locked ? R.string.ona_edit_unlock : R.string.ona_edit_lock));
        }
        enable(btnUndo, !undo.isEmpty() && !testing);
        enable(btnRedo, !redo.isEmpty() && !testing);
        if (btnGrid != null) btnGrid.setText(str(R.string.ona_edit_grid) + (snapGrid ? " ✓" : ""));
        if (btnTest != null) btnTest.setText(str(R.string.ona_edit_test) + (testing ? " ✓" : ""));
        if (btnMove != null) btnMove.setText(barAtBottom ? "↑" : "↓");
    }

    // =============================================================== edits (each one undoable)

    private void pushUndo() {
        undo.push(profile.copy());
        while (undo.size() > UNDO_DEPTH) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(profile.copy());
        profile = undo.pop();
        afterProfileSwap();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(profile.copy());
        profile = redo.pop();
        afterProfileSwap();
    }

    private void afterProfileSwap() {
        if (selected >= profile.elements.size()) selected = -1;
        refreshBar();
        board.invalidate();
    }

    private void duplicateSelected() {
        if (selected < 0 || selected >= profile.elements.size() || profile.elements.size() >= OnaProfile.MAX_ELEMENTS) return;
        pushUndo();
        OnaProfile.Element e = profile.elements.get(selected).copy();
        e.id = OnaProfile.newElementId();
        e.locked = false;
        e.x = OnaProfile.clamp(e.x + 0.04f, 0f, 1f);
        e.y = OnaProfile.clamp(e.y + 0.06f, 0f, 1f);
        profile.elements.add(e);
        selected = profile.elements.size() - 1;
        refreshBar();
        board.invalidate();
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= profile.elements.size()) return;
        pushUndo();
        profile.elements.remove(selected);
        selected = -1;
        refreshBar();
        board.invalidate();
    }

    private void bringSelectedToFront() {
        if (selected < 0 || selected >= profile.elements.size() - 1) return;
        pushUndo();
        OnaProfile.Element e = profile.elements.remove(selected);
        profile.elements.add(e);
        selected = profile.elements.size() - 1;
        refreshBar();
        board.invalidate();
    }

    private void toggleLock() {
        if (selected < 0 || selected >= profile.elements.size()) return;
        pushUndo();
        OnaProfile.Element e = profile.elements.get(selected);
        e.locked = !e.locked;
        refreshBar();
        board.invalidate();
    }

    private void showAddDialog() {
        if (testing || profile.elements.size() >= OnaProfile.MAX_ELEMENTS) return;
        CharSequence[] items = {
            str(R.string.ona_type_button), str(R.string.ona_type_row), str(R.string.ona_type_dpad), str(R.string.ona_type_stick),
        };
        new android.app.AlertDialog.Builder(OnaElementSheet.dialogContext(getContext()))
            .setTitle(R.string.ona_edit_add)
            .setItems(items, (d, which) -> {
                pushUndo();
                OnaProfile.Element e = new OnaProfile.Element();
                e.id = OnaProfile.newElementId();
                e.x = 0.5f;
                e.y = 0.5f;
                switch (which) {
                    case 1:
                        e.type = OnaProfile.TYPE_BUTTON_ROW;
                        for (int i = 1; i <= 4; i++) e.actions.add(OnaProfile.Action.of(OnaProfile.keyOf("K" + i)));
                        break;
                    case 2:
                    case 3:
                        e.type = which == 2 ? OnaProfile.TYPE_DPAD : OnaProfile.TYPE_STICK;
                        for (String k : new String[] { "UP", "RIGHT", "DOWN", "LEFT" }) {
                            e.actions.add(OnaProfile.Action.of(OnaProfile.keyOf(k)));
                        }
                        break;
                    default:
                        e.type = OnaProfile.TYPE_BUTTON;
                        e.actions.add(OnaProfile.Action.of(OnaProfile.keyOf("SPACE")));
                        break;
                }
                e.normalizeSlots();
                profile.elements.add(e);
                selected = profile.elements.size() - 1;
                refreshBar();
                board.invalidate();
                if (e.type == OnaProfile.TYPE_BUTTON || e.type == OnaProfile.TYPE_BUTTON_ROW) openSheet(selected);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void openSheet(final int index) {
        if (testing || index < 0 || index >= profile.elements.size()) return;
        final OnaProfile.Element target = profile.elements.get(index);
        OnaElementSheet.show(getContext(), target.copy(), edited -> {
            int at = profile.elements.indexOf(target);
            if (at < 0) return; // undone or deleted meanwhile
            pushUndo();
            edited.normalizeSlots();
            profile.elements.set(at, edited);
            selected = at;
            refreshBar();
            board.invalidate();
        });
    }

    // =============================================================== test mode (launcher)

    private void toggleTest() {
        if (testing) stopTest(); else startTest();
    }

    private void startTest() {
        testing = true;
        logLines.clear();
        testLog.setText(str(R.string.ona_test_hint));
        testLog.setVisibility(VISIBLE);
        testInput = new OnaInput(new OnaInput.Sink() {
            @Override public void keyDown(int code) { log(code, true); }
            @Override public void keyUp(int code) { log(code, false); }
            @Override public boolean macrosAllowed() { return true; }
            @Override public void macroBlocked() { }
            @Override public boolean keyAllowed(int code) { return true; }
            @Override public void haptic() { board.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
            @Override public void changed() { board.invalidate(); }
        }, renderer);
        testInput.setProfile(profile);
        testInput.setViewSize(board.getWidth(), board.getHeight());
        refreshBar();
        board.invalidate();
    }

    private void stopTest() {
        if (!testing) return;
        testing = false;
        if (testInput != null) testInput.releaseAll();
        testInput = null;
        board.testPointers.clear();
        testLog.setVisibility(GONE);
        refreshBar();
        board.invalidate();
    }

    private String keyLabel(int code) {
        for (OnaProfile.Key k : OnaProfile.PICKER) {
            if (k.code == code) return k.label;
        }
        return "#" + code;
    }

    private void log(int code, boolean down) {
        logLines.add(keyLabel(code) + (down ? "↓" : "↑"));
        while (logLines.size() > 14) logLines.remove(0);
        StringBuilder b = new StringBuilder();
        for (String s : logLines) {
            if (b.length() > 0) b.append("  ");
            b.append(s);
        }
        testLog.setText(b.toString());
    }

    // =============================================================== the board

    private final class Board extends View {
        private final Paint select = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint();
        private final Paint guide = new Paint();
        private final Paint hud = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hudLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hudText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final RectF tmp = new RectF();
        private final float[] pt = new float[2];
        final SparseIntArray testPointers = new SparseIntArray();

        // gesture
        private static final int MODE_NONE = 0;
        private static final int MODE_MOVE = 1;
        private static final int MODE_RESIZE = 2;
        private static final int MODE_ROTATE = 3;
        private static final int MODE_PINCH = 4;
        private int mode;
        private OnaProfile before;
        private boolean changed;
        private float offX;
        private float offY;
        private float pinchStart;
        private float scaleStart;
        private float guideX = -1f;
        private float guideY = -1f;
        private long lastUpTime;
        private int lastUpElement = -1;
        private float downX;
        private float downY;

        Board(Context ctx) {
            super(ctx);
            select.setStyle(Paint.Style.STROKE);
            select.setStrokeWidth(2.5f * density);
            select.setColor(Color.rgb(33, 150, 243));
            select.setPathEffect(new DashPathEffect(new float[] { 8f * density, 5f * density }, 0f));
            handle.setColor(Color.rgb(33, 150, 243));
            handleRing.setStyle(Paint.Style.STROKE);
            handleRing.setStrokeWidth(2f * density);
            handleRing.setColor(Color.WHITE);
            grid.setColor(Color.argb(40, 255, 255, 255));
            grid.setStrokeWidth(1f);
            guide.setColor(Color.rgb(0, 229, 255));
            guide.setStrokeWidth(1.5f * density);
            hud.setStyle(Paint.Style.FILL);
            hudLine.setStyle(Paint.Style.STROKE);
            hudLine.setStrokeWidth(1.5f * density);
            hudLine.setColor(Color.argb(120, 200, 200, 160));
            hudText.setColor(Color.argb(150, 220, 220, 190));
            hudText.setTextAlign(Paint.Align.CENTER);
            hudText.setTextSize(13f * density);
            lockPaint.setColor(Color.rgb(255, 193, 7));
            lockPaint.setShadowLayer(2f * density, 0f, 0f, Color.BLACK);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (testInput != null) testInput.setViewSize(w, h);
        }

        private float gridStep() {
            return 8f * density;
        }

        // ------------------------------------------------------------ drawing

        private void drawHud(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            hud.setShader(new LinearGradient(0, 0, 0, h, 0xFF39402F, 0xFF1C2018, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, hud);
            hud.setShader(null);
            // The Zero Hour control bar along the bottom with the radar in its left end, as a placement guide.
            float barH = h * 0.26f;
            float top = h - barH;
            hud.setColor(Color.argb(200, 30, 34, 38));
            c.drawRect(0, top, w, h, hud);
            c.drawLine(0, top, w, top, hudLine);
            tmp.set(dp(6), top + dp(6), dp(6) + barH - dp(12), h - dp(6));
            hud.setColor(Color.argb(220, 12, 40, 20));
            c.drawRect(tmp, hud);
            c.drawRect(tmp, hudLine);
            Paint.FontMetrics fm = hudText.getFontMetrics();
            c.drawText(str(R.string.ona_editor_hud_radar), tmp.centerX(), tmp.centerY() - (fm.ascent + fm.descent) / 2f, hudText);
            c.drawText(str(R.string.ona_editor_hud_bar), (tmp.right + w) / 2f, top + barH / 2f - (fm.ascent + fm.descent) / 2f, hudText);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth();
            int h = getHeight();
            if (inGame) {
                c.drawColor(Color.argb(testing ? 40 : 120, 0, 0, 0));
            } else {
                drawHud(c);
            }
            if (snapGrid && !testing) {
                float step = gridStep() * 2f;
                for (float x = 0; x < w; x += step) c.drawLine(x, 0, x, h, grid);
                for (float y = 0; y < h; y += step) c.drawLine(0, y, w, y, grid);
            }
            for (int i = 0; i < profile.elements.size(); i++) {
                OnaProfile.Element e = profile.elements.get(i);
                OnaRenderer.State st = testing && testInput != null ? testInput.look(i) : null;
                renderer.draw(c, profile, e, w, h, st, testing ? 0f : 0.75f);
                if (!testing && e.locked) {
                    renderer.boundsOf(e, w, h, box);
                    tmp.set(box.right - dp(14), box.top - dp(4), box.right + dp(2), box.top + dp(12));
                    OnaIcons.draw(c, "lock", tmp, lockPaint);
                }
            }
            if (testing) return;
            if (selected >= 0 && selected < profile.elements.size()) {
                OnaProfile.Element e = profile.elements.get(selected);
                renderer.boundsOf(e, w, h, box);
                int save = c.save();
                c.rotate(e.rotation, box.centerX(), box.centerY());
                c.drawRect(box, select);
                if (!e.locked) {
                    c.drawLine(box.centerX(), box.top, box.centerX(), box.top - dp(24), select);
                }
                c.restoreToCount(save);
                if (!e.locked) {
                    handlePos(e, box, true, pt);
                    c.drawCircle(pt[0], pt[1], dp(9), handle);
                    c.drawCircle(pt[0], pt[1], dp(9), handleRing);
                    handlePos(e, box, false, pt);
                    c.drawCircle(pt[0], pt[1], dp(9), handle);
                    c.drawCircle(pt[0], pt[1], dp(9), handleRing);
                }
            }
            if (mode == MODE_MOVE) {
                if (guideX >= 0f) c.drawLine(guideX, 0, guideX, h, guide);
                if (guideY >= 0f) c.drawLine(0, guideY, w, guideY, guide);
            }
        }

        /** The rotate handle (above the top edge) or the resize handle (bottom-right corner), in view pixels. */
        private void handlePos(OnaProfile.Element e, RectF b, boolean rotate, float[] out) {
            if (rotate) {
                OnaRenderer.toView(e, b, b.width() / 2f, -dp(24), out);
            } else {
                OnaRenderer.toView(e, b, b.width(), b.height(), out);
            }
        }

        private boolean near(float x, float y, float[] p) {
            return Math.hypot(x - p[0], y - p[1]) <= dp(22);
        }

        // ------------------------------------------------------------ touches

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (testing) return onTestTouch(e);
            int w = getWidth();
            int h = getHeight();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    before = profile.copy();
                    changed = false;
                    mode = MODE_NONE;
                    downX = e.getX();
                    downY = e.getY();
                    if (selected >= 0 && selected < profile.elements.size() && !profile.elements.get(selected).locked) {
                        OnaProfile.Element s = profile.elements.get(selected);
                        renderer.boundsOf(s, w, h, box);
                        handlePos(s, box, true, pt);
                        if (near(downX, downY, pt)) {
                            mode = MODE_ROTATE;
                            return true;
                        }
                        handlePos(s, box, false, pt);
                        if (near(downX, downY, pt)) {
                            mode = MODE_RESIZE;
                            return true;
                        }
                    }
                    int hit = renderer.hit(profile, w, h, downX, downY, dp(4));
                    long now = e.getEventTime();
                    if (hit >= 0 && hit == lastUpElement && now - lastUpTime < 320) {
                        lastUpElement = -1;
                        selected = hit;
                        refreshBar();
                        invalidate();
                        openSheet(hit);
                        return true;
                    }
                    selected = hit;
                    if (hit >= 0 && !profile.elements.get(hit).locked) {
                        OnaProfile.Element s = profile.elements.get(hit);
                        mode = MODE_MOVE;
                        offX = downX - s.x * w;
                        offY = downY - s.y * h;
                    }
                    refreshBar();
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (e.getPointerCount() == 2 && selected >= 0 && selected < profile.elements.size()
                        && !profile.elements.get(selected).locked) {
                        mode = MODE_PINCH;
                        pinchStart = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                        scaleStart = profile.elements.get(selected).scale;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (selected < 0 || selected >= profile.elements.size()) return true;
                    OnaProfile.Element s = profile.elements.get(selected);
                    switch (mode) {
                        case MODE_MOVE:
                            moveTo(s, e.getX() - offX, e.getY() - offY);
                            break;
                        case MODE_RESIZE:
                            resizeTo(s, e.getX(), e.getY());
                            break;
                        case MODE_ROTATE:
                            rotateTo(s, e.getX(), e.getY());
                            break;
                        case MODE_PINCH:
                            if (e.getPointerCount() >= 2 && pinchStart > 0f) {
                                float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                                float sc = OnaProfile.clamp(Math.round(scaleStart * d / pinchStart * 20f) / 20f, 0.3f, 4f);
                                if (sc != s.scale) {
                                    s.scale = sc;
                                    changed = true;
                                }
                            }
                            break;
                        default:
                            break;
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (mode == MODE_PINCH) mode = MODE_NONE;
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (changed && before != null) {
                        undo.push(before);
                        while (undo.size() > UNDO_DEPTH) undo.removeLast();
                        redo.clear();
                    }
                    boolean tap = Math.hypot(e.getX() - downX, e.getY() - downY) < dp(8) && !changed;
                    lastUpElement = tap && mode != MODE_ROTATE && mode != MODE_RESIZE ? selected : -1;
                    lastUpTime = e.getEventTime();
                    before = null;
                    changed = false;
                    mode = MODE_NONE;
                    guideX = -1f;
                    guideY = -1f;
                    refreshBar();
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        /** Moves a control's centre to (cx, cy): to the grid, or onto another control's centre line (a guide). */
        private void moveTo(OnaProfile.Element s, float cx, float cy) {
            int w = Math.max(1, getWidth());
            int h = Math.max(1, getHeight());
            guideX = -1f;
            guideY = -1f;
            float tol = dp(6);
            float bestX = tol;
            float bestY = tol;
            float snapX = Float.NaN;
            float snapY = Float.NaN;
            float[] xs = new float[profile.elements.size() + 1];
            float[] ys = new float[profile.elements.size() + 1];
            int n = 0;
            xs[n] = w / 2f;
            ys[n] = h / 2f;
            n++;
            for (OnaProfile.Element o : profile.elements) {
                if (o == s) continue;
                xs[n] = o.x * w;
                ys[n] = o.y * h;
                n++;
            }
            for (int i = 0; i < n; i++) {
                float dx = Math.abs(cx - xs[i]);
                if (dx < bestX) { bestX = dx; snapX = xs[i]; }
                float dy = Math.abs(cy - ys[i]);
                if (dy < bestY) { bestY = dy; snapY = ys[i]; }
            }
            if (!Float.isNaN(snapX)) {
                cx = snapX;
                guideX = snapX;
            } else if (snapGrid) {
                cx = Math.round(cx / gridStep()) * gridStep();
            }
            if (!Float.isNaN(snapY)) {
                cy = snapY;
                guideY = snapY;
            } else if (snapGrid) {
                cy = Math.round(cy / gridStep()) * gridStep();
            }
            float nx = OnaProfile.clamp(cx / w, 0f, 1f);
            float ny = OnaProfile.clamp(cy / h, 0f, 1f);
            if (nx != s.x || ny != s.y) {
                s.x = nx;
                s.y = ny;
                changed = true;
            }
        }

        /** The corner handle: width and height follow the finger in the control's own (rotated) frame. */
        private void resizeTo(OnaProfile.Element s, float x, float y) {
            int w = getWidth();
            int h = getHeight();
            renderer.boundsOf(s, w, h, box);
            OnaRenderer.toLocal(s, box, x, y, pt);
            float halfW = Math.abs(pt[0] - box.width() / 2f);
            float halfH = Math.abs(pt[1] - box.height() / 2f);
            float k = density * OnaProfile.fit(w, h, density);
            float[] dp = s.sizeDp();
            float baseW = dp[0] / OnaProfile.clamp(s.width, 0.3f, 4f) * k;
            float baseH = dp[1] / OnaProfile.clamp(s.height, 0.3f, 4f) * k;
            if (baseW <= 0f || baseH <= 0f) return;
            float nw = OnaProfile.clamp(Math.round(2f * halfW / baseW * 20f) / 20f, 0.3f, 4f);
            float nh = OnaProfile.clamp(Math.round(2f * halfH / baseH * 20f) / 20f, 0.3f, 4f);
            if (s.directional()) { // a round control stays round
                float m = Math.max(nw, nh);
                nw = m;
                nh = m;
            }
            if (nw != s.width || nh != s.height) {
                s.width = nw;
                s.height = nh;
                changed = true;
            }
        }

        /** The top handle: the control turns to face the finger, in 15-degree steps. */
        private void rotateTo(OnaProfile.Element s, float x, float y) {
            renderer.boundsOf(s, getWidth(), getHeight(), box);
            double deg = Math.toDegrees(Math.atan2(y - box.centerY(), x - box.centerX())) + 90.0;
            float snapped = (float) (Math.round(deg / 15.0) * 15.0);
            snapped = ((snapped % 360f) + 360f) % 360f;
            if (snapped != s.rotation) {
                s.rotation = snapped;
                changed = true;
            }
        }

        // ------------------------------------------------------------ test mode

        private boolean onTestTouch(MotionEvent e) {
            if (testInput == null) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    int i = e.getActionIndex();
                    int el = testInput.hit(e.getX(i), e.getY(i));
                    if (el >= 0) {
                        testPointers.put(e.getPointerId(i), el);
                        testInput.down(el, e.getPointerId(i), e.getX(i), e.getY(i));
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    for (int i = 0; i < e.getPointerCount(); i++) {
                        int el = testPointers.get(e.getPointerId(i), -1);
                        if (el >= 0) testInput.move(el, e.getPointerId(i), e.getX(i), e.getY(i));
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_UP: {
                    int i = e.getActionIndex();
                    int id = e.getPointerId(i);
                    int el = testPointers.get(id, -1);
                    testPointers.delete(id);
                    if (el >= 0) testInput.up(el, id);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    for (int i = 0; i < testPointers.size(); i++) testInput.up(testPointers.valueAt(i), testPointers.keyAt(i));
                    testPointers.clear();
                    return true;
                default:
                    return true;
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTest();
        super.onDetachedFromWindow();
    }
}
