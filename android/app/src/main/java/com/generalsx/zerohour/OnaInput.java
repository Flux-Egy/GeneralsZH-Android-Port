/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Adapted from ONA Launcher 2 "ona-controls" (view/OverlayElementView.kt), GPL-3.0: the D-pad's eight
 * 45-degree sectors, the stick's polar maths with the rescaled deadzone and the button-row cells.
 */
package com.generalsx.zerohour;

import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.List;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 What a finger on an ONA control sends, apart from where the
 * keys go: the game (OnaOverlayView: SDL) or the launcher editor's Test mode (a log). Per slot a chord (keys pressed
 * in order, let go in reverse) or a macro (run once per press, one at a time per control). Buttons hold, tap
 * (a 60 ms press whatever the finger does) or toggle. Macros run only where the sink allows them -- never in a LAN
 * or online match (owner); chords always. Every key is counted, so two controls sharing a key let go of it only
 * when both have. Main thread only.
 */
final class OnaInput {

    interface Sink {
        void keyDown(int code);

        void keyUp(int code);

        /** False in a LAN or online match (and when the engine cannot say). */
        boolean macrosAllowed();

        /** A macro was pressed where macros are off. */
        void macroBlocked();

        /** ESC is held back while a match loads (a queued ESC opens the quit menu at once). */
        boolean keyAllowed(int code);

        void haptic();

        /** Something to redraw. */
        void changed();
    }

    /** A TAP press lasts this long; a macro's TAP the same, with the same gap after it. */
    static final int TAP_MS = 60;
    static final int MACRO_TAP_MS = 40;

    private final Sink sink;
    private final OnaRenderer renderer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OnaProfile profile;
    private int viewW;
    private int viewH;

    private final class Play {
        final OnaRenderer.State look = new OnaRenderer.State();
        /** Per slot: the key codes it holds now, in press order. */
        final List<List<Integer>> held = new ArrayList<>();
        /** BUTTON_ROW: pointer id -> cell. */
        final SparseIntArray rowPointers = new SparseIntArray();
        boolean toggled;
        /** TAP behaviour: the pending let-go per slot. */
        final Runnable[] tapRelease = new Runnable[OnaProfile.MAX_SLOTS];
        MacroRun macro;

        List<Integer> heldIn(int slot) {
            while (held.size() <= slot) held.add(new ArrayList<>());
            return held.get(slot);
        }
    }

    private final List<Play> plays = new ArrayList<>();
    /** How many presses hold each key down (two controls may share one). */
    private final SparseIntArray keyDownCount = new SparseIntArray();

    private final RectF box = new RectF();
    private final float[] local = new float[2];

    OnaInput(Sink sink, OnaRenderer renderer) {
        this.sink = sink;
        this.renderer = renderer;
    }

    void setProfile(OnaProfile p) {
        releaseAll();
        profile = p;
        plays.clear();
        for (int i = 0; i < p.elements.size(); i++) plays.add(new Play());
    }

    void setViewSize(int w, int h) {
        viewW = w;
        viewH = h;
    }

    OnaRenderer.State look(int el) {
        return el >= 0 && el < plays.size() ? plays.get(el).look : null;
    }

    int hit(float x, float y) {
        return profile == null ? -1 : renderer.hit(profile, viewW, viewH, x, y, 0f);
    }

    // =============================================================== keys

    private void keyDown(int code) {
        if (code == 0) return;
        int n = keyDownCount.get(code, 0);
        if (n == 0) sink.keyDown(code);
        keyDownCount.put(code, n + 1);
    }

    private void keyUp(int code) {
        if (code == 0) return;
        int n = keyDownCount.get(code, 0);
        if (n <= 0) return;
        if (n == 1) {
            keyDownCount.delete(code);
            sink.keyUp(code);
        } else {
            keyDownCount.put(code, n - 1);
        }
    }

    /** A chord's keys down in order (each once per slot), or up in reverse. */
    private void chord(Play p, int slot, OnaProfile.Action a, boolean down) {
        List<Integer> held = p.heldIn(slot);
        if (down) {
            for (OnaProfile.Key k : a.keys) {
                if (k.code == 0 || held.contains(k.code) || !sink.keyAllowed(k.code)) continue;
                held.add(k.code);
                keyDown(k.code);
            }
        } else {
            for (int i = held.size() - 1; i >= 0; i--) keyUp(held.get(i));
            held.clear();
        }
    }

    /** One slot pressed or let go: a chord follows the finger; a macro starts on the press. */
    private void slot(OnaProfile.Element c, Play p, int slot, boolean down) {
        OnaProfile.Action a = c.action(slot);
        if (a.isMacro() && !c.directional()) {
            if (down) startMacro(p, a);
            return;
        }
        chord(p, slot, a, down);
    }

    private void cancelTap(Play p, int slot) {
        Runnable r = p.tapRelease[slot];
        if (r != null) {
            handler.removeCallbacks(r);
            p.tapRelease[slot] = null;
            r.run();
        }
    }

    /** TAP behaviour: press now, let go TAP_MS later whatever the finger does. */
    private void tap(final OnaProfile.Element c, final Play p, final int slot) {
        cancelTap(p, slot);
        slot(c, p, slot, true);
        if (c.action(slot).isMacro()) return;
        p.look.pressed[slot] = true;
        final Runnable[] self = new Runnable[1];
        self[0] = () -> {
            if (p.tapRelease[slot] == self[0]) p.tapRelease[slot] = null;
            p.look.pressed[slot] = false;
            slot(c, p, slot, false);
            sink.changed();
        };
        p.tapRelease[slot] = self[0];
        handler.postDelayed(self[0], TAP_MS);
    }

    // =============================================================== macros

    /** A macro flattened to key events, each after a wait. */
    private final class MacroRun implements Runnable {
        final Play play;
        final List<int[]> events = new ArrayList<>(); // { waitMs, code, down 1/0 }
        final List<Integer> down = new ArrayList<>();
        int at;
        boolean waited;

        MacroRun(Play play, OnaProfile.Action a) {
            this.play = play;
            int wait = 0;
            for (OnaProfile.Step s : a.macro) {
                int code = s.key.code;
                switch (s.op) {
                    case OnaProfile.OP_WAIT:
                        wait += OnaProfile.clampInt(s.ms, 0, OnaProfile.MAX_WAIT_MS);
                        break;
                    case OnaProfile.OP_DOWN:
                        if (code != 0) { events.add(new int[] { wait, code, 1 }); wait = 0; }
                        break;
                    case OnaProfile.OP_UP:
                        if (code != 0) { events.add(new int[] { wait, code, 0 }); wait = 0; }
                        break;
                    default: // TAP
                        if (code != 0) {
                            events.add(new int[] { wait, code, 1 });
                            events.add(new int[] { MACRO_TAP_MS, code, 0 });
                            wait = MACRO_TAP_MS;
                        }
                        break;
                }
            }
        }

        @Override
        public void run() {
            if (play.macro != this) return;
            while (at < events.size()) {
                int[] ev = events.get(at);
                if (ev[0] > 0 && !waited) {
                    waited = true;
                    handler.postDelayed(this, ev[0]);
                    return;
                }
                waited = false;
                at++;
                if (ev[2] == 1) {
                    if (!down.contains(ev[1]) && sink.keyAllowed(ev[1])) {
                        down.add(ev[1]);
                        keyDown(ev[1]);
                    }
                } else if (down.remove(Integer.valueOf(ev[1]))) {
                    keyUp(ev[1]);
                }
            }
            finish();
        }

        /** Lets go of whatever the macro still holds (a DOWN without an UP, or a cancel). */
        void finish() {
            handler.removeCallbacks(this);
            for (int i = down.size() - 1; i >= 0; i--) keyUp(down.get(i));
            down.clear();
            if (play.macro == this) {
                play.macro = null;
                play.look.latched = play.toggled;
                sink.changed();
            }
        }
    }

    private void startMacro(Play p, OnaProfile.Action a) {
        if (p.macro != null) return; // one run at a time per control: no stacking, no turbo
        if (!sink.macrosAllowed()) {
            sink.macroBlocked();
            return;
        }
        MacroRun run = new MacroRun(p, a);
        p.macro = run;
        p.look.latched = true;
        sink.changed();
        run.run();
    }

    // =============================================================== touches (already known to be on control el)

    void down(int el, int pointerId, float x, float y) {
        if (profile == null || el < 0 || el >= profile.elements.size()) return;
        OnaProfile.Element c = profile.elements.get(el);
        Play p = plays.get(el);
        renderer.boundsOf(c, viewW, viewH, box);
        OnaRenderer.toLocal(c, box, x, y, local);
        switch (c.type) {
            case OnaProfile.TYPE_BUTTON:
                if (c.action(0).isMacro()) {
                    p.look.pressed[0] = true;
                    slot(c, p, 0, true);
                } else if (c.behavior == OnaProfile.BEHAVIOR_TOGGLE) {
                    p.toggled = !p.toggled;
                    p.look.latched = p.toggled;
                    slot(c, p, 0, p.toggled);
                } else if (c.behavior == OnaProfile.BEHAVIOR_TAP) {
                    tap(c, p, 0);
                } else {
                    p.look.pressed[0] = true;
                    slot(c, p, 0, true);
                }
                sink.haptic();
                break;
            case OnaProfile.TYPE_BUTTON_ROW: {
                int cell = cellAt(c, local[0]);
                p.rowPointers.put(pointerId, cell);
                cellChange(c, p, cell, true);
                break;
            }
            case OnaProfile.TYPE_DPAD:
                applyDirs(c, p, dpadDirs(c, local[0], local[1]));
                break;
            case OnaProfile.TYPE_STICK:
                stickMove(c, p, local[0], local[1]);
                break;
            default:
                break; // TRACKPAD: shown, sends nothing until Phase 2 (mouse output)
        }
        sink.changed();
    }

    void move(int el, int pointerId, float x, float y) {
        if (profile == null || el < 0 || el >= profile.elements.size()) return;
        OnaProfile.Element c = profile.elements.get(el);
        Play p = plays.get(el);
        renderer.boundsOf(c, viewW, viewH, box);
        OnaRenderer.toLocal(c, box, x, y, local);
        switch (c.type) {
            case OnaProfile.TYPE_BUTTON_ROW: {
                int old = p.rowPointers.get(pointerId, -1);
                int cell = cellAt(c, local[0]);
                if (old >= 0 && cell != old) { // slide across the cells like a keyboard row
                    p.rowPointers.put(pointerId, cell);
                    cellChange(c, p, old, false);
                    cellChange(c, p, cell, true);
                    sink.changed();
                }
                break;
            }
            case OnaProfile.TYPE_DPAD:
                applyDirs(c, p, dpadDirs(c, local[0], local[1]));
                sink.changed();
                break;
            case OnaProfile.TYPE_STICK:
                stickMove(c, p, local[0], local[1]);
                sink.changed();
                break;
            default:
                break;
        }
    }

    void up(int el, int pointerId) {
        if (profile == null || el < 0 || el >= profile.elements.size()) return;
        OnaProfile.Element c = profile.elements.get(el);
        Play p = plays.get(el);
        switch (c.type) {
            case OnaProfile.TYPE_BUTTON:
                if (c.action(0).isMacro()) {
                    p.look.pressed[0] = false;
                } else if (c.behavior == OnaProfile.BEHAVIOR_HOLD) {
                    p.look.pressed[0] = false;
                    slot(c, p, 0, false);
                }
                break;
            case OnaProfile.TYPE_BUTTON_ROW: {
                int cell = p.rowPointers.get(pointerId, -1);
                p.rowPointers.delete(pointerId);
                if (cell >= 0) cellChange(c, p, cell, false);
                break;
            }
            case OnaProfile.TYPE_DPAD:
                applyDirs(c, p, new boolean[4]);
                break;
            case OnaProfile.TYPE_STICK:
                p.look.stickX = 0f;
                p.look.stickY = 0f;
                applyDirs(c, p, new boolean[4]);
                break;
            default:
                break;
        }
        sink.changed();
    }

    // --------------------------------------------------------------- button row

    private int cellAt(OnaProfile.Element c, float lx) {
        int n = Math.max(1, c.actions.size());
        int cell = (int) Math.floor(lx / (box.width() / n));
        return Math.max(0, Math.min(n - 1, cell));
    }

    /** A finger entered or left a cell: the cell presses with its first finger and lets go with its last. */
    private void cellChange(OnaProfile.Element c, Play p, int cell, boolean entered) {
        if (cell < 0 || cell >= c.actions.size() || cell >= p.look.pressed.length) return;
        int fingers = 0;
        for (int i = 0; i < p.rowPointers.size(); i++) {
            if (p.rowPointers.valueAt(i) == cell) fingers++;
        }
        if (entered) {
            sink.haptic();
            if (fingers != 1) return; // another finger already holds it
            if (c.behavior == OnaProfile.BEHAVIOR_TAP) {
                tap(c, p, cell);
                return;
            }
            p.look.pressed[cell] = true;
            slot(c, p, cell, true);
        } else if (fingers == 0) {
            if (c.behavior == OnaProfile.BEHAVIOR_TAP) return; // the tap lets go by itself
            p.look.pressed[cell] = false;
            slot(c, p, cell, false);
        }
    }

    // --------------------------------------------------------------- d-pad and stick (ONA's maths)

    /** Four flags: up, right, down, left. Eight 45-degree sectors, the diagonals press two; the centre nothing. */
    private static boolean[] sectorDirs(double angleRad) {
        boolean[] out = new boolean[4];
        int sector = (int) (((Math.toDegrees(angleRad) + 360.0 + 22.5) % 360.0) / 45.0); // 0 = right, clockwise
        switch (sector) {
            case 0: out[1] = true; break;
            case 1: out[1] = true; out[2] = true; break;
            case 2: out[2] = true; break;
            case 3: out[2] = true; out[3] = true; break;
            case 4: out[3] = true; break;
            case 5: out[3] = true; out[0] = true; break;
            case 6: out[0] = true; break;
            default: out[0] = true; out[1] = true; break;
        }
        return out;
    }

    private boolean[] dpadDirs(OnaProfile.Element c, float lx, float ly) {
        float cx = box.width() / 2f;
        float cy = box.height() / 2f;
        float dx = (lx - cx) / cx;
        float dy = (ly - cy) / cy;
        if (Math.hypot(dx, dy) < 0.2) return new boolean[4];
        if (c.eightWay) return sectorDirs(Math.atan2(dy, dx));
        boolean[] out = new boolean[4];
        if (Math.abs(dx) > Math.abs(dy)) {
            out[dx > 0 ? 1 : 3] = true;
        } else {
            out[dy > 0 ? 2 : 0] = true;
        }
        return out;
    }

    /** Polar maths: angle and clamped radius; the deadzone is rescaled so the output starts at 0 right after it. */
    private void stickMove(OnaProfile.Element c, Play p, float lx, float ly) {
        float radius = Math.min(box.width(), box.height()) / 2f * 0.6f;
        float dx = lx - box.width() / 2f;
        float dy = ly - box.height() / 2f;
        double angle = Math.atan2(dy, dx);
        float rr = (float) Math.min(Math.hypot(dx, dy) / Math.max(1f, radius), 1.0);
        p.look.stickX = (float) Math.cos(angle) * rr;
        p.look.stickY = (float) Math.sin(angle) * rr;
        float dz = OnaProfile.clamp(c.deadzone, 0f, 0.9f);
        float m = rr <= dz ? 0f : (rr - dz) / (1f - dz);
        if (!c.stickSendsKeys()) return; // ONA's mouse / gamepad outputs: drawn, not sent (Phase 2)
        applyDirs(c, p, m > 0f ? sectorDirs(angle) : new boolean[4]);
    }

    private void applyDirs(OnaProfile.Element c, Play p, boolean[] dirs) {
        for (int d = 0; d < 4; d++) {
            if (p.look.pressed[d] != dirs[d]) {
                p.look.pressed[d] = dirs[d];
                if (dirs[d] && c.type == OnaProfile.TYPE_DPAD) sink.haptic();
                chord(p, d, c.action(d), dirs[d]);
            }
        }
    }

    // =============================================================== release

    private void releasePlay(Play p) {
        for (int s = 0; s < p.tapRelease.length; s++) {
            if (p.tapRelease[s] != null) {
                handler.removeCallbacks(p.tapRelease[s]);
                p.tapRelease[s] = null;
            }
        }
        if (p.macro != null) {
            MacroRun m = p.macro;
            p.macro = null;
            m.finish();
        }
        for (List<Integer> held : p.held) {
            for (int i = held.size() - 1; i >= 0; i--) keyUp(held.get(i));
            held.clear();
        }
        java.util.Arrays.fill(p.look.pressed, false);
        p.look.stickX = 0f;
        p.look.stickY = 0f;
        p.look.latched = false;
        p.toggled = false;
        p.rowPointers.clear();
    }

    /** Lets go of every key (toggles, taps and macros included). */
    void releaseAll() {
        for (Play p : plays) releasePlay(p);
        for (int i = 0; i < keyDownCount.size(); i++) sink.keyUp(keyDownCount.keyAt(i));
        keyDownCount.clear();
        handler.removeCallbacksAndMessages(null);
        sink.changed();
    }
}
