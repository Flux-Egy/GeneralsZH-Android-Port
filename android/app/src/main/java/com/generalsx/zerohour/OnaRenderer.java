/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Adapted from ONA Launcher 2 "ona-controls" (view/ElementRenderer.kt), GPL-3.0: the look of the controls
 * (the D-pad's pentagon arrows, the stick, the button-row cells), ported to Java.
 */
package com.generalsx.zerohour;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 Draws ONA controls and does their geometry, the same for the
 * game (OnaOverlayView) and both editors (OnaEditorView in the game and in the launcher), so a layout looks the same
 * where it is made and where it is played. A control is a box centred on (x, y), sized by its type, scale and
 * width / height, turned by its rotation about its centre; touches are turned back into the box's own frame.
 */
final class OnaRenderer {

    static final int DEFAULT_COLOR = 0xFFE6E6E6;
    static final int[] PALETTE = {
        0, 0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63,
        0xFF795548, 0xFF9E9E9E, 0xFF000000, 0xFFFFFFFF, 0xFF8BC34A, 0xFF3F51B5, 0xFFFF5722,
    };

    /** Per-control play state the renderer shows (pressed slots, the stick's thumb). Owned by OnaInput. */
    static final class State {
        final boolean[] pressed = new boolean[OnaProfile.MAX_SLOTS];
        float stickX;
        float stickY;
        /** A toggle that is on, or a macro that is running: drawn pressed. */
        boolean latched;
    }

    private final float density;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final RectF tmp = new RectF();

    OnaRenderer(float density) {
        this.density = density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.5f * density);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        back.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setShadowLayer(2.5f * density, 0f, 0f, Color.argb(230, 0, 0, 0));
        iconPaint.setShadowLayer(2f * density, 0f, 0f, Color.argb(200, 0, 0, 0));
    }

    float density() {
        return density;
    }

    // =============================================================== geometry

    /** The control's unrotated box in view pixels. */
    void boundsOf(OnaProfile.Element e, int viewW, int viewH, RectF out) {
        float[] dp = e.sizeDp();
        float k = density * OnaProfile.fit(viewW, viewH, density);
        float w = Math.max(8f, dp[0] * k);
        float h = Math.max(8f, dp[1] * k);
        float cx = e.x * viewW;
        float cy = e.y * viewH;
        out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    /**
     * A view point in the control's own frame: (0, 0) its top-left corner before rotation. {@code box} is its
     * unrotated box (boundsOf).
     */
    static void toLocal(OnaProfile.Element e, RectF box, float x, float y, float[] out) {
        float dx = x - box.centerX();
        float dy = y - box.centerY();
        if (e.rotation != 0f) {
            double a = Math.toRadians(-e.rotation);
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            float rx = dx * c - dy * s;
            float ry = dx * s + dy * c;
            dx = rx;
            dy = ry;
        }
        out[0] = dx + box.width() / 2f;
        out[1] = dy + box.height() / 2f;
    }

    /** A point in the control's own frame back to view pixels. */
    static void toView(OnaProfile.Element e, RectF box, float lx, float ly, float[] out) {
        float dx = lx - box.width() / 2f;
        float dy = ly - box.height() / 2f;
        if (e.rotation != 0f) {
            double a = Math.toRadians(e.rotation);
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            float rx = dx * c - dy * s;
            float ry = dx * s + dy * c;
            dx = rx;
            dy = ry;
        }
        out[0] = box.centerX() + dx;
        out[1] = box.centerY() + dy;
    }

    private final float[] local = new float[2];

    /** The topmost control under a point (rotation included), or -1. {@code slop} widens every box (editor). */
    int hit(OnaProfile profile, int viewW, int viewH, float x, float y, float slop) {
        for (int i = profile.elements.size() - 1; i >= 0; i--) {
            OnaProfile.Element e = profile.elements.get(i);
            boundsOf(e, viewW, viewH, tmp);
            toLocal(e, tmp, x, y, local);
            if (local[0] >= -slop && local[1] >= -slop && local[0] <= tmp.width() + slop && local[1] <= tmp.height() + slop) {
                return i;
            }
        }
        return -1;
    }

    // =============================================================== drawing

    /** Draws one control; {@code st} null = at rest (the editor). */
    void draw(Canvas c, OnaProfile profile, OnaProfile.Element el, int viewW, int viewH, State st, float minOpacity) {
        RectF b = tmp;
        boundsOf(el, viewW, viewH, b);
        float o = el.alpha >= 0f ? el.alpha : profile.opacity;
        o = OnaProfile.clamp(Math.max(o, minOpacity), 0.05f, 1f);
        int save = c.save();
        if (el.rotation != 0f) c.rotate(el.rotation, b.centerX(), b.centerY());
        int base = el.color == 0 ? DEFAULT_COLOR : el.color;
        int a = (int) (o * 255);
        stroke.setColor(Color.argb(a, Color.red(base), Color.green(base), Color.blue(base)));
        back.setColor(Color.argb(Math.max(40, Math.min(170, (int) (o * 0.55f * 255))), 0, 0, 0));
        fill.setColor(Color.argb((int) (Math.min(0.8f, 0.30f + o * 0.45f) * 255), Color.red(base), Color.green(base), Color.blue(base)));
        int lc = el.labelColor == 0 ? Color.WHITE : el.labelColor;
        int la = (int) (OnaProfile.clamp(o * 1.6f, 0.8f, 1f) * 255);
        text.setColor(Color.argb(la, Color.red(lc), Color.green(lc), Color.blue(lc)));
        iconPaint.setColor(Color.argb(la, Color.red(lc), Color.green(lc), Color.blue(lc)));
        float inset = stroke.getStrokeWidth();
        r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        boolean latched = st != null && st.latched;
        switch (el.type) {
            case OnaProfile.TYPE_BUTTON:
                drawButton(c, el, st != null && (st.pressed[0] || latched));
                break;
            case OnaProfile.TYPE_BUTTON_ROW:
                drawRow(c, el, st);
                break;
            case OnaProfile.TYPE_DPAD:
                drawDpad(c, st);
                break;
            case OnaProfile.TYPE_STICK:
                drawStick(c, st);
                break;
            default: {
                float rad = 12f * density;
                c.drawRoundRect(r, rad, rad, back);
                c.drawRoundRect(r, rad, rad, stroke);
                label(c, el.text.isEmpty() ? "TOUCHPAD" : el.text, r.centerX(), r.centerY(), r.width() * 0.8f, r.height() * 0.2f);
                break;
            }
        }
        c.restoreToCount(save);
    }

    private void shapeRect(Canvas c, int shape, RectF rect, boolean pressed) {
        if (shape == OnaProfile.SHAPE_CIRCLE) {
            c.drawOval(rect, back);
            if (pressed) c.drawOval(rect, fill);
            c.drawOval(rect, stroke);
            return;
        }
        float radius = shape == OnaProfile.SHAPE_PILL ? Math.min(rect.width(), rect.height()) / 2f
            : shape == OnaProfile.SHAPE_SQUARE ? 10f * density : 3f * density;
        c.drawRoundRect(rect, radius, radius, back);
        if (pressed) c.drawRoundRect(rect, radius, radius, fill);
        c.drawRoundRect(rect, radius, radius, stroke);
    }

    private final RectF iconBox = new RectF();

    private void drawButton(Canvas c, OnaProfile.Element el, boolean pressed) {
        shapeRect(c, el.shape, r, pressed);
        boolean icon = !el.icon.isEmpty() && OnaIcons.has(el.icon);
        if (icon) {
            // An icon fills the middle; a label, if any, goes small under it.
            float side = Math.min(r.width(), r.height());
            boolean withText = !el.text.isEmpty();
            float iconSize = side * (withText ? 0.46f : 0.58f);
            float cy = withText ? r.centerY() - side * 0.10f : r.centerY();
            iconBox.set(r.centerX() - iconSize / 2f, cy - iconSize / 2f, r.centerX() + iconSize / 2f, cy + iconSize / 2f);
            OnaIcons.draw(c, el.icon, iconBox, iconPaint);
            if (withText) {
                label(c, el.text, r.centerX(), iconBox.bottom + side * 0.14f, r.width() * 0.84f, side * 0.20f);
            }
            return;
        }
        String t = el.text.isEmpty() ? el.autoLabel() : el.text;
        String[] lines = t.split("\n");
        if (lines.length == 1) {
            label(c, t, r.centerX(), r.centerY(), r.width() * 0.86f, Math.min(r.height() * 0.40f, 20f * density));
            return;
        }
        float usable = r.height() * 0.80f;
        float first = usable * 0.55f;
        float rest = (usable - first) / (lines.length - 1);
        float y = r.centerY() - usable / 2f;
        for (int i = 0; i < lines.length; i++) {
            float h = i == 0 ? first : rest;
            label(c, lines[i], r.centerX(), y + h / 2f, r.width() * 0.88f, h * 0.76f);
            y += h;
        }
    }

    private final RectF cellBox = new RectF();

    private void drawRow(Canvas c, OnaProfile.Element el, State st) {
        int n = Math.max(1, el.actions.size());
        float cell = r.width() / n;
        float rad = 8f * density;
        c.drawRoundRect(r, rad, rad, back);
        for (int i = 0; i < n; i++) {
            if (st != null && i < st.pressed.length && st.pressed[i]) {
                cellBox.set(r.left + i * cell, r.top, r.left + (i + 1) * cell, r.bottom);
                c.drawRect(cellBox, fill);
            }
            if (i > 0) c.drawLine(r.left + i * cell, r.top, r.left + i * cell, r.bottom, stroke);
            label(c, el.action(i).label(), r.left + (i + 0.5f) * cell, r.centerY(), cell * 0.84f, r.height() * 0.46f);
        }
        c.drawRoundRect(r, rad, rad, stroke);
    }

    /** Winlator-style D-pad: four pentagon arrows around the centre. Order: up, right, down, left. */
    private void drawDpad(Canvas c, State st) {
        float cx = r.centerX();
        float cy = r.centerY();
        float u = Math.min(r.width(), r.height()) / 2f;
        float[] pts = { -0.28f, -1f, 0.28f, -1f, 0.28f, -0.42f, 0f, -0.14f, -0.28f, -0.42f };
        for (int dir = 0; dir < 4; dir++) {
            path.reset();
            for (int i = 0; i < 5; i++) {
                float px = pts[i * 2];
                float py = pts[i * 2 + 1];
                float rx;
                float ry;
                switch (dir) {
                    case 0: rx = px; ry = py; break;
                    case 1: rx = -py; ry = px; break;
                    case 2: rx = -px; ry = -py; break;
                    default: rx = py; ry = -px; break;
                }
                if (i == 0) path.moveTo(cx + rx * u, cy + ry * u); else path.lineTo(cx + rx * u, cy + ry * u);
            }
            path.close();
            c.drawPath(path, back);
            if (st != null && st.pressed[dir]) c.drawPath(path, fill);
            c.drawPath(path, stroke);
        }
    }

    private void drawStick(Canvas c, State st) {
        float cx = r.centerX();
        float cy = r.centerY();
        float rad = Math.min(r.width(), r.height()) / 2f;
        c.drawCircle(cx, cy, rad, back);
        c.drawCircle(cx, cy, rad, stroke);
        float travel = rad * 0.6f;
        float tx = cx + (st != null ? st.stickX : 0f) * travel;
        float ty = cy + (st != null ? st.stickY : 0f) * travel;
        float thumb = rad * 0.4f;
        c.drawCircle(tx, ty, thumb, fill);
        c.drawCircle(tx, ty, thumb, stroke);
    }

    private void label(Canvas c, String s, float x, float y, float maxW, float size) {
        if (s == null || s.isEmpty()) return;
        text.setTextSize(Math.max(1f, size));
        float w = text.measureText(s);
        if (w > maxW && w > 0f) text.setTextSize(Math.max(1f, size * maxW / w));
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, y - (fm.ascent + fm.descent) / 2f, text);
    }
}
