/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.generalsx.zerohour;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 The built-in icons a control can show (owner: built-in only,
 * no images in a profile). Each is path data in a 24 x 24 box (the SVG path syntax, read by the small parser below,
 * plus "O cx cy r" for a circle), drawn filled or stroked. A profile names an icon; an unknown name shows none.
 */
final class OnaIcons {

    private static final class Icon {
        final String data;
        final boolean stroke;
        Path path;

        Icon(String data, boolean stroke) {
            this.data = data;
            this.stroke = stroke;
        }
    }

    private static final Map<String, Icon> ICONS = new LinkedHashMap<>();

    private static void fill(String name, String data) {
        ICONS.put(name, new Icon(data, false));
    }

    private static void line(String name, String data) {
        ICONS.put(name, new Icon(data, true));
    }

    static {
        fill("stop", "M6 6h12v12H6z");
        line("attack", "O12 12 6.5 M12 2v6M12 16v6M2 12h6M16 12h6");
        fill("guard", "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z");
        line("move", "M12 3v18M3 12h18M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3");
        line("scatter", "M10 10L4 4M4 9V4h5M14 10l6-6M15 4h5v5M10 14l-6 6M4 15v5h5M14 14l6 6M20 15v5h-5");
        line("select", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5");
        line("select_all", "M4 9V4h5M15 4h5v5M20 15v5h-5M9 20H4v-5M8 8h8v8H8z");
        fill("group", "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z");
        fill("person", "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z");
        fill("home", "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z");
        fill("star", "M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.81 8.63 2 9.24l5.46 4.73L5.82 21z");
        fill("bolt", "M7 2v11h3v9l7-12h-4l4-8z");
        fill("flag", "M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z");
        fill("wrench", "M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z");
        line("money", "M17 7c0-1.9-2.2-3-5-3S7 5.1 7 7.5s2.2 3.2 5 4 5 1.8 5 4.5-2.2 4-5 4-5-1.2-5-3M12 2v20");
        line("upgrade", "M6 13l6-6 6 6M6 19l6-6 6 6");
        fill("layers", "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z");
        fill("map", "M20.5 3l-.16.03L15 5.1 9 3 3.36 4.9c-.21.07-.36.25-.36.48V20.5c0 .28.22.5.5.5l.16-.03L9 18.9l6 2.1 5.64-1.9c.21-.07.36-.25.36-.48V3.5c0-.28-.22-.5-.5-.5zM15 19l-6-2.11V5l6 2.11V19z");
        fill("camera", "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5z");
        fill("eye", "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z");
        line("zoom_in", "O10 10 6 M14.5 14.5L21 21M10 7v6M7 10h6");
        line("zoom_out", "O10 10 6 M14.5 14.5L21 21M7 10h6");
        fill("chat", "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z");
        line("keyboard", "M3 6h18v12H3zM7 10h.01M11 10h.01M15 10h.01M7 14h10");
        fill("pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z");
        fill("play", "M8 5v14l11-7z");
        fill("menu", "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
        fill("close", "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z");
        fill("plus", "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z");
        fill("minus", "M19 13H5v-2h14v2z");
        fill("arrow_up", "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z");
        fill("arrow_down", "M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z");
        fill("arrow_left", "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z");
        fill("arrow_right", "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z");
        fill("lock", "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z");
    }

    static boolean has(String name) {
        return name != null && ICONS.containsKey(name);
    }

    static List<String> names() {
        return new ArrayList<>(ICONS.keySet());
    }

    private static Path pathOf(Icon icon) {
        if (icon.path == null) {
            Path p;
            try {
                p = parse(icon.data);
            } catch (RuntimeException e) { // a typo in the table above must not take the game down
                android.util.Log.w("GeneralsZH", "[ONA] icon path error", e);
                p = new Path();
            }
            icon.path = p;
        }
        return icon.path;
    }

    private static final Matrix MATRIX = new Matrix();
    private static final Path SCRATCH = new Path();

    /**
     * Draws the icon into {@code box} (kept square, centred) with the paint's colour. Main thread only (shared
     * scratch objects).
     */
    static void draw(Canvas c, String name, RectF box, Paint paint) {
        Icon icon = name == null ? null : ICONS.get(name);
        if (icon == null || box.width() <= 0f || box.height() <= 0f) return;
        float size = Math.min(box.width(), box.height());
        float k = size / 24f;
        MATRIX.reset();
        MATRIX.setScale(k, k);
        MATRIX.postTranslate(box.centerX() - size / 2f, box.centerY() - size / 2f);
        pathOf(icon).transform(MATRIX, SCRATCH);
        Paint.Style style = paint.getStyle();
        float width = paint.getStrokeWidth();
        Paint.Cap cap = paint.getStrokeCap();
        Paint.Join join = paint.getStrokeJoin();
        if (icon.stroke) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 2f * k));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        } else {
            paint.setStyle(Paint.Style.FILL);
        }
        c.drawPath(SCRATCH, paint);
        paint.setStyle(style);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(cap);
        paint.setStrokeJoin(join);
    }

    // ------------------------------------------------------------------ SVG path data (M L H V C S Q T Z, any case)

    private static final class Reader {
        final String s;
        int i;

        Reader(String s) {
            this.s = s;
        }

        void skip() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\n' || c == '\t') i++; else break;
            }
        }

        boolean atNumber() {
            skip();
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
        }

        /** One number; ".5.5" is 0.5 then 0.5 and "1-2" is 1 then -2, as in SVG. */
        float number() {
            skip();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            boolean dot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && i + 1 < s.length()) {
                    i++;
                    if (s.charAt(i) == '-' || s.charAt(i) == '+') i++;
                } else {
                    break;
                }
            }
            if (start == i) throw new IllegalArgumentException("number expected at " + i + " in " + s);
            return Float.parseFloat(s.substring(start, i));
        }
    }

    static Path parse(String data) {
        Path p = new Path();
        Reader r = new Reader(data);
        float x = 0f, y = 0f, startX = 0f, startY = 0f;
        float lastCx = 0f, lastCy = 0f; // the previous control point, for S and T
        char last = ' ';
        char cmd = ' ';
        while (true) {
            r.skip();
            if (r.i >= data.length()) break;
            char c = data.charAt(r.i);
            if (Character.isLetter(c)) {
                cmd = c;
                r.i++;
            } else if (cmd == ' ') {
                throw new IllegalArgumentException("path must start with a command");
            } else if (cmd == 'M') {
                cmd = 'L'; // more pairs after a move are lines
            } else if (cmd == 'm') {
                cmd = 'l';
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0f;
            float oy = rel ? y : 0f;
            switch (Character.toUpperCase(cmd)) {
                case 'M':
                    x = ox + r.number();
                    y = oy + r.number();
                    p.moveTo(x, y);
                    startX = x;
                    startY = y;
                    break;
                case 'L':
                    x = ox + r.number();
                    y = oy + r.number();
                    p.lineTo(x, y);
                    break;
                case 'H':
                    x = ox + r.number();
                    p.lineTo(x, y);
                    break;
                case 'V':
                    y = oy + r.number();
                    p.lineTo(x, y);
                    break;
                case 'C': {
                    float x1 = ox + r.number(), y1 = oy + r.number();
                    float x2 = ox + r.number(), y2 = oy + r.number();
                    x = ox + r.number();
                    y = oy + r.number();
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lastCx = x2;
                    lastCy = y2;
                    break;
                }
                case 'S': {
                    char lu = Character.toUpperCase(last);
                    float x1 = lu == 'C' || lu == 'S' ? 2 * x - lastCx : x;
                    float y1 = lu == 'C' || lu == 'S' ? 2 * y - lastCy : y;
                    float x2 = ox + r.number(), y2 = oy + r.number();
                    x = ox + r.number();
                    y = oy + r.number();
                    p.cubicTo(x1, y1, x2, y2, x, y);
                    lastCx = x2;
                    lastCy = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + r.number(), y1 = oy + r.number();
                    x = ox + r.number();
                    y = oy + r.number();
                    p.quadTo(x1, y1, x, y);
                    lastCx = x1;
                    lastCy = y1;
                    break;
                }
                case 'T': {
                    char lu = Character.toUpperCase(last);
                    float x1 = lu == 'Q' || lu == 'T' ? 2 * x - lastCx : x;
                    float y1 = lu == 'Q' || lu == 'T' ? 2 * y - lastCy : y;
                    x = ox + r.number();
                    y = oy + r.number();
                    p.quadTo(x1, y1, x, y);
                    lastCx = x1;
                    lastCy = y1;
                    break;
                }
                case 'Z':
                    p.close();
                    x = startX;
                    y = startY;
                    break;
                case 'O': { // not SVG: a full circle, cx cy r (absolute)
                    float cx = r.number(), cy = r.number(), rad = r.number();
                    p.addCircle(cx, cy, rad, Path.Direction.CW);
                    break;
                }
                default:
                    throw new IllegalArgumentException("unsupported path command " + cmd);
            }
            last = cmd;
            if (Character.toUpperCase(cmd) == 'Z' || Character.toUpperCase(cmd) == 'O') {
                // Z takes no numbers; a number right after it would be an error, so wait for a letter.
                if (r.atNumber() && Character.toUpperCase(cmd) == 'Z') {
                    throw new IllegalArgumentException("number after Z");
                }
            }
        }
        return p;
    }

    private OnaIcons() {
    }
}
