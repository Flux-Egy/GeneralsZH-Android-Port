// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 The performance HUD, drawn by hand so it can look like a
// tactical readout (owner's request for test10): FPS, hottest temperature and battery, each shown or
// hidden in the launcher, and a 30-second sparkline of FPS (theme accent) and temperature (amber).
//
// Placement and look are the player's, in the game itself:
//  - tap: shrink to a dot and back;
//  - long press (0.5 s): edit -- drag to move it anywhere, pinch to resize, and a small bar under it:
//    style (transparent, solid, rounded, tactical), smaller, bigger, done. Saved on "done".
// Four styles, the accent from the launcher's theme (ThemeHelper.accentColor), a soft glow on the
// lines. It redraws once a second (InGameHud feeds it) -- nothing per frame -- and only takes the
// touches that land on it.
package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;

final class PerfHudView extends View {
    static final String PREF_X = "gx_hud_x";
    static final String PREF_Y = "gx_hud_y";
    static final String PREF_SCALE = "gx_hud_scale";
    static final String PREF_STYLE = "gx_hud_style";
    static final String PREF_SHOW_FPS = "gx_hud_show_fps";
    static final String PREF_SHOW_TEMP = "gx_hud_show_temp";
    static final String PREF_SHOW_BATTERY = "gx_hud_show_battery";
    static final String PREF_SHOW_GRAPH = "gx_hud_show_graph";
    static final String PREF_COLLAPSED = "gx_perf_hud_collapsed";

    static final int STYLE_TRANSPARENT = 0;
    static final int STYLE_SOLID = 1;
    static final int STYLE_ROUNDED = 2;
    static final int STYLE_TACTICAL = 3;
    private static final int STYLE_COUNT = 4;

    private static final int HISTORY = 30;
    private static final float MIN_SCALE = 0.6f;
    private static final float MAX_SCALE = 2.2f;
    private static final int AMBER = Color.rgb(255, 179, 0);
    private static final int RED = Color.rgb(255, 82, 82);

    private final SharedPreferences prefs;
    private final float density;
    private final int accent;

    private final float[] fpsHistory = new float[HISTORY];
    private final int[] tempHistory = new int[HISTORY];
    private int samples;
    private int head;
    private float fps = -1f;
    private int temp = -1;
    private int battery = -1;
    private boolean charging;

    private float scale;
    private int style;
    private boolean showFps, showTemp, showBattery, showGraph, collapsed;
    private boolean editing;

    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    // Edit bar buttons, in view coordinates (filled while drawing).
    private final RectF[] editButtons = { new RectF(), new RectF(), new RectF(), new RectF() };
    private static final String[] EDIT_GLYPHS = { "◐", "−", "+", "✓" };

    private final ScaleGestureDetector pinch;
    private float downRawX, downRawY, startX, startY;
    private boolean moved;
    // After a pinch or a second finger, moves wait for a fresh first finger (no jump to it).
    private boolean ignoreMoves;
    // The lift that ends the long press is not a tap on the edit bar.
    private boolean swallowUp;
    // A drag while the touch editor is open: moves at once, saved on release.
    private boolean draggedInEditor;
    private Runnable onExpanded;
    private long lastSpokenMs;
    private final Runnable longPress = () -> {
        swallowUp = true;
        startEditing();
    };

    PerfHudView(Context ctx) {
        super(ctx);
        prefs = ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        density = ctx.getResources().getDisplayMetrics().density;
        accent = ThemeHelper.accentColor(ctx);
        scale = clamp(prefs.getFloat(PREF_SCALE, 1.0f), MIN_SCALE, MAX_SCALE);
        style = prefs.getInt(PREF_STYLE, STYLE_TACTICAL);
        if (style < 0 || style >= STYLE_COUNT) {
            style = STYLE_TACTICAL;
        }
        showFps = prefs.getBoolean(PREF_SHOW_FPS, true);
        showTemp = prefs.getBoolean(PREF_SHOW_TEMP, true);
        showBattery = prefs.getBoolean(PREF_SHOW_BATTERY, true);
        showGraph = prefs.getBoolean(PREF_SHOW_GRAPH, true);
        if (!showFps && !showTemp && !showBattery && !showGraph) {
            showFps = true; // an empty HUD would be an invisible one
        }
        collapsed = prefs.getBoolean(PREF_COLLAPSED, false);
        setClickable(true);
        setLongClickable(true);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        // The glow (setShadowLayer on strokes) is drawn in software; the view is small and redraws
        // once a second.
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setContentDescription(ctx.getString(R.string.hud_perf_description));
        pinch = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setScale(scale * d.getScaleFactor());
                return true;
            }
        });
    }

    boolean isCollapsed() {
        return collapsed;
    }

    /** Called when the dot is opened again, so the figures can be read at once. */
    void setOnExpanded(Runnable r) {
        onExpanded = r;
    }

    /** One sample a second, on the UI thread. */
    void update(float fpsNow, int tempNow, int batteryNow, boolean chargingNow) {
        fps = fpsNow;
        temp = tempNow;
        battery = batteryNow;
        charging = chargingNow;
        fpsHistory[head] = fpsNow;
        tempHistory[head] = tempNow;
        head = (head + 1) % HISTORY;
        if (samples < HISTORY) {
            samples++;
        }
        // TalkBack: the figures, refreshed every few seconds rather than every one.
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSpokenMs >= 5000) {
            lastSpokenMs = now;
            setContentDescription(getContext().getString(R.string.hud_perf_description) + ": " + summary());
        }
        requestLayout();
        invalidate();
    }

    private String summary() {
        StringBuilder b = new StringBuilder();
        if (showFps) {
            b.append(fps >= 0f ? String.format(Locale.ROOT, "%.0f", fps) : "--").append(" FPS ");
        }
        if (showTemp) {
            b.append(temp >= 0 ? temp : "--").append("°C ");
        }
        if (showBattery) {
            b.append(battery >= 0 ? battery : "--").append(charging ? "%⚡" : "%");
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ size

    private float px(float dp) {
        return dp * density * scale;
    }

    private float textSize() {
        return px(12f);
    }

    private float pad() {
        return px(style == STYLE_TRANSPARENT ? 2f : 7f);
    }

    private float graphHeight() {
        return px(30f);
    }

    private float editBarHeight() {
        return editing ? Math.max(px(30f), 40f * density) : 0f;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        text.setTextSize(textSize());
        float w, h;
        if (collapsed && !editing) {
            w = h = Math.max(px(18f), 28f * density); // still a target a finger can hit
        } else {
            float textW = 0f;
            float lineH = 0f;
            String s = summaryForDrawing();
            if (!s.isEmpty()) {
                textW = text.measureText(s) + px(14f); // legend dots
                lineH = textSize() * 1.25f;
            }
            float graphW = showGraph ? px(132f) : 0f;
            w = Math.max(textW, graphW) + pad() * 2;
            h = pad() * 2 + lineH + (showGraph ? graphHeight() + (lineH > 0 ? px(4f) : 0f) : 0f);
            if (editing) {
                w = Math.max(w, 4 * Math.max(px(30f), 40f * density) + px(12f));
            }
        }
        setMeasuredDimension((int) Math.ceil(w), (int) Math.ceil(h + editBarHeight() + (editing ? px(4f) : 0f)));
    }

    private String summaryForDrawing() {
        StringBuilder b = new StringBuilder();
        if (showFps) {
            b.append(fps >= 0f ? String.format(Locale.ROOT, "%3.0f", fps) : " --").append(" FPS");
        }
        if (showTemp) {
            if (b.length() > 0) b.append("  ");
            b.append(temp >= 0 ? String.format(Locale.ROOT, "%2d", temp) : "--").append("°C");
        }
        if (showBattery) {
            if (b.length() > 0) b.append("  ");
            b.append(battery >= 0 ? battery : "--").append(charging ? "%⚡" : "%");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float bodyH = getHeight() - editBarHeight() - (editing ? px(4f) : 0f);
        text.setTextSize(textSize());

        if (collapsed && !editing) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(Color.argb(170, 8, 14, 20));
            canvas.drawCircle(w / 2f, bodyH / 2f, w / 2f - 1f, fill);
            fill.setColor(accent);
            fill.setShadowLayer(px(4f), 0, 0, accent);
            canvas.drawCircle(w / 2f, bodyH / 2f, w / 5f, fill);
            fill.clearShadowLayer();
            return;
        }

        drawBackground(canvas, w, bodyH);
        float y = pad();
        String s = summaryForDrawing();
        if (!s.isEmpty()) {
            float baseline = y + textSize();
            float x = pad();
            // Legend dots: accent = FPS line, amber = temperature line.
            if (showGraph && showFps) {
                fill.setStyle(Paint.Style.FILL);
                fill.setColor(accent);
                canvas.drawCircle(x + px(3f), baseline - textSize() * 0.35f, px(2.5f), fill);
            }
            if (showGraph && showTemp) {
                fill.setColor(AMBER);
                canvas.drawCircle(x + px(9f), baseline - textSize() * 0.35f, px(2.5f), fill);
            }
            x += px(14f);
            text.setColor(fpsColor());
            text.setShadowLayer(style == STYLE_TRANSPARENT ? px(2f) : px(3f), 0, px(0.5f),
                style == STYLE_TRANSPARENT ? Color.BLACK : withAlpha(accent, 160));
            canvas.drawText(s, x, baseline, text);
            text.clearShadowLayer();
            y = baseline + textSize() * 0.25f + px(4f);
        }
        if (showGraph) {
            drawGraph(canvas, pad(), y, w - pad(), y + graphHeight());
        }
        if (editing) {
            drawEditBar(canvas, w, bodyH + px(4f));
        }
    }

    private int fpsColor() {
        if (fps < 0f || fps >= 50f) {
            return Color.rgb(235, 245, 250);
        }
        return fps >= 40f ? AMBER : RED;
    }

    private void drawBackground(Canvas canvas, float w, float h) {
        fill.setStyle(Paint.Style.FILL);
        line.setStrokeWidth(Math.max(1f, px(1f)));
        line.setColor(withAlpha(accent, 200));
        line.clearShadowLayer();
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);
        switch (style) {
            case STYLE_TRANSPARENT:
                break;
            case STYLE_SOLID:
                fill.setColor(Color.argb(235, 8, 14, 20));
                canvas.drawRect(rect, fill);
                canvas.drawRect(rect, line);
                break;
            case STYLE_ROUNDED: {
                float r = px(10f);
                fill.setColor(Color.argb(165, 8, 14, 20));
                canvas.drawRoundRect(rect, r, r, fill);
                canvas.drawRoundRect(rect, r, r, line);
                break;
            }
            default: { // tactical: dark glass, neon corner brackets, a faint scan line
                fill.setColor(Color.argb(150, 6, 12, 18));
                canvas.drawRect(rect, fill);
                line.setColor(withAlpha(accent, 70));
                canvas.drawRect(rect, line);
                float c = Math.min(px(9f), Math.min(w, h) / 3f);
                line.setStrokeWidth(Math.max(1.5f, px(1.6f)));
                line.setColor(accent);
                line.setShadowLayer(px(4f), 0, 0, accent);
                path.reset();
                path.moveTo(rect.left, rect.top + c); path.lineTo(rect.left, rect.top); path.lineTo(rect.left + c, rect.top);
                path.moveTo(rect.right - c, rect.top); path.lineTo(rect.right, rect.top); path.lineTo(rect.right, rect.top + c);
                path.moveTo(rect.right, rect.bottom - c); path.lineTo(rect.right, rect.bottom); path.lineTo(rect.right - c, rect.bottom);
                path.moveTo(rect.left + c, rect.bottom); path.lineTo(rect.left, rect.bottom); path.lineTo(rect.left, rect.bottom - c);
                canvas.drawPath(path, line);
                line.clearShadowLayer();
                fill.setColor(withAlpha(accent, 22));
                canvas.drawRect(rect.left + 1, rect.top + h * 0.5f, rect.right - 1, rect.top + h * 0.5f + Math.max(1f, px(0.8f)), fill);
                break;
            }
        }
    }

    private void drawGraph(Canvas canvas, float left, float top, float right, float bottom) {
        // Grid: three faint lines.
        line.setStrokeWidth(Math.max(1f, px(0.6f)));
        line.setColor(withAlpha(accent, 40));
        line.clearShadowLayer();
        for (int i = 0; i <= 2; i++) {
            float gy = top + (bottom - top) * i / 2f;
            canvas.drawLine(left, gy, right, gy, line);
        }
        if (samples < 2) {
            return;
        }
        float maxFps = 60f;
        for (int i = 0; i < samples; i++) {
            maxFps = Math.max(maxFps, fpsHistory[(head - samples + i + HISTORY) % HISTORY]);
        }
        if (showFps || !showTemp) {
            plot(canvas, left, top, right, bottom, true, maxFps, accent);
        }
        if (showTemp) {
            plot(canvas, left, top, right, bottom, false, 0f, AMBER);
        }
    }

    /** FPS on 0..maxFps, temperature on 30..100 C; the newest sample at the right edge. */
    private void plot(Canvas canvas, float left, float top, float right, float bottom, boolean fpsLine, float maxFps, int color) {
        path.reset();
        boolean started = false;
        final float step = (right - left) / (HISTORY - 1);
        for (int i = 0; i < samples; i++) {
            int idx = (head - samples + i + HISTORY) % HISTORY;
            float v;
            if (fpsLine) {
                if (fpsHistory[idx] < 0f) continue;
                v = clamp(fpsHistory[idx] / maxFps, 0f, 1f);
            } else {
                if (tempHistory[idx] < 0) continue;
                v = clamp((tempHistory[idx] - 30f) / 70f, 0f, 1f);
            }
            float x = right - (samples - 1 - i) * step;
            float y = bottom - v * (bottom - top);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        if (!started) {
            return;
        }
        line.setStrokeWidth(Math.max(1.2f, px(1.4f)));
        line.setColor(color);
        line.setShadowLayer(px(3f), 0, 0, color);
        canvas.drawPath(path, line);
        line.clearShadowLayer();
    }

    private void drawEditBar(Canvas canvas, float w, float top) {
        float size = editBarHeight();
        float gap = px(4f);
        float x = 0f;
        text.setTextSize(size * 0.55f);
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < editButtons.length; i++) {
            editButtons[i].set(x, top, x + size, top + size);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(i == 3 ? accent : Color.argb(220, 10, 18, 26));
            canvas.drawRoundRect(editButtons[i], size / 4f, size / 4f, fill);
            line.setStrokeWidth(Math.max(1f, px(1f)));
            line.setColor(accent);
            canvas.drawRoundRect(editButtons[i], size / 4f, size / 4f, line);
            text.setColor(i == 3 ? Color.rgb(8, 14, 20) : accent);
            canvas.drawText(EDIT_GLYPHS[i], editButtons[i].centerX(), editButtons[i].centerY() + size * 0.2f, text);
            x += size + gap;
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) {
            pinch.onTouchEvent(e);
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startX = getX();
                startY = getY();
                moved = false;
                ignoreMoves = false;
                draggedInEditor = false;
                // With the touch editor open the HUD is only moved: no hold-to-edit on top of it.
                if (!editing && !HotkeyOverlayView.isEditModeActive()) {
                    postDelayed(longPress, 500);
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                ignoreMoves = true;
                moved = true;
                removeCallbacks(longPress);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (Math.abs(dx) + Math.abs(dy) > 12f * density) {
                    moved = true;
                    removeCallbacks(longPress);
                }
                if (editing && !ignoreMoves && e.getPointerCount() == 1 && !pinch.isInProgress()) {
                    moveTo(startX + dx, startY + dy);
                } else if (!editing && moved && !ignoreMoves && HotkeyOverlayView.isEditModeActive()) {
                    // GeneralsX @tweak Flux Build 07/11/2026 With the touch editor open the HUD is laid out
                    // like the buttons: a drag moves it straight away, anywhere on the screen.
                    draggedInEditor = true;
                    moveTo(startX + dx, startY + dy);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (draggedInEditor) {
                    draggedInEditor = false;
                    save();
                } else if (swallowUp) {
                    swallowUp = false;
                } else if (editing) {
                    if (!moved) {
                        handleEditTap(e.getX(), e.getY());
                    }
                } else if (!moved && !HotkeyOverlayView.isEditModeActive()) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (draggedInEditor) {
                    draggedInEditor = false;
                    save();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (editing) {
            return true;
        }
        collapsed = !collapsed;
        prefs.edit().putBoolean(PREF_COLLAPSED, collapsed).apply();
        if (!collapsed && onExpanded != null) {
            onExpanded.run();
        }
        requestLayout();
        invalidate();
        return true;
    }

    /** TalkBack's long press edits too; its double tap on "done" is the same as the bar's. */
    @Override
    public boolean performLongClick() {
        super.performLongClick();
        if (editing) {
            editing = false;
            save();
            requestLayout();
            invalidate();
        } else {
            startEditing();
        }
        return true;
    }

    private void startEditing() {
        editing = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        requestLayout();
        invalidate();
    }

    private void handleEditTap(float x, float y) {
        if (editButtons[0].contains(x, y)) {
            style = (style + 1) % STYLE_COUNT;
        } else if (editButtons[1].contains(x, y)) {
            setScale(scale / 1.15f);
        } else if (editButtons[2].contains(x, y)) {
            setScale(scale * 1.15f);
        } else if (editButtons[3].contains(x, y)) {
            editing = false;
            save();
        }
        requestLayout();
        invalidate();
    }

    private void setScale(float s) {
        scale = clamp(s, MIN_SCALE, MAX_SCALE);
        requestLayout();
        invalidate();
    }

    private void moveTo(float x, float y) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) {
            return;
        }
        setX(clamp(x, 0f, Math.max(0f, parent.getWidth() - getWidth())));
        setY(clamp(y, 0f, Math.max(0f, parent.getHeight() - getHeight())));
    }

    private void save() {
        ViewGroup parent = (ViewGroup) getParent();
        SharedPreferences.Editor ed = prefs.edit()
            .putFloat(PREF_SCALE, scale)
            .putInt(PREF_STYLE, style);
        if (parent != null && parent.getWidth() > 0 && parent.getHeight() > 0) {
            ed.putFloat(PREF_X, getX() / parent.getWidth()).putFloat(PREF_Y, getY() / parent.getHeight());
        }
        ed.apply();
    }

    /** Where it was left (fractions of the screen), or under the top-right corner's Info button. */
    void placeInitially(final float defaultRightInsetPx) {
        final ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || parent.getWidth() == 0) {
            return;
        }
        place(parent, defaultRightInsetPx);
        // The screen changing size (a fold, a split window): the saved fractions again.
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                post(() -> place(parent, defaultRightInsetPx));
            }
        });
    }

    private void place(ViewGroup parent, float defaultRightInsetPx) {
        float fx = prefs.getFloat(PREF_X, -1f);
        float fy = prefs.getFloat(PREF_Y, -1f);
        if (fx < 0f || fy < 0f) {
            // Clear of a camera cutout, left of the Info button.
            int cutRight = 0, cutTop = 0;
            android.view.WindowInsets insets = getRootWindowInsets();
            if (insets != null && insets.getDisplayCutout() != null) {
                cutRight = insets.getDisplayCutout().getSafeInsetRight();
                cutTop = insets.getDisplayCutout().getSafeInsetTop();
            }
            // Below the touch editor's gear-and-chat row (which ends 49 dp down) and its top layout bar,
            // beside the Info button that sits under the gear.
            moveTo(parent.getWidth() - getWidth() - defaultRightInsetPx - cutRight, 56f * density + cutTop);
        } else {
            moveTo(fx * parent.getWidth(), fy * parent.getHeight());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Keep it on screen when it grows at an edge.
        post(() -> moveTo(getX(), getY()));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
