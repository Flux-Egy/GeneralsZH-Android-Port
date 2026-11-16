/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Adapted from ONA Launcher 2 "ona-controls" (view/OverlayElementView.kt, view/ElementRenderer.kt,
 * view/OverlayEditorView.kt), GPL-3.0: the D-pad's eight 45-degree sectors, the stick's polar maths with
 * the rescaled deadzone, the button-row cells, the look of the controls and the drag / pinch editor,
 * ported to Java and to one view inside the game's own window.
 */
package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12201 ONA-style controls (owner), drawn INSIDE the game's
 * window: a plain View over the SDL surface, like HotkeyOverlayView (which it replaces when the launcher's
 * "Control style" is ONA). Keys go straight to the game with SDLActivity.onNativeKeyDown / onNativeKeyUp; a
 * finger that does not start on a control goes to SDL untouched (returned unhandled, or forwarded with
 * SDLActivity.onNativeTouch while another finger holds a control), so the touch layer (TouchInput.cpp) sees
 * exactly what it sees today. No Shizuku, no Accessibility service, no system overlay window, no permission.
 *
 * The analog stick and the D-pad send the arrow keys (the camera pans; WASD are Generals' command hotkeys).
 * ONA's mouse, gamepad and touch outputs are not sent: a mouse event made in Java would make the engine
 * treat the phone as having a hardware mouse (SDL3Mouse), so mouse output needs an engine hook -- later.
 * Every held key is released when the controls hide (a menu, a hardware mouse / keyboard, the app paused).
 */
final class OnaOverlayView extends View {

    private static OnaOverlayView sAttached;

    private final GeneralsZHActivity activity;
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private OnaProfile profile;

    // --------------------------------------------------------------- per-control state while playing

    private static final class State {
        boolean[] pressed = new boolean[16];
        float stickX;
        float stickY;
        /** Keys this control holds down now (Android codes), so a release lets go of exactly those. */
        final Set<Integer> held = new HashSet<>();
        /** BUTTON_ROW: pointer id -> cell. */
        final SparseIntArray rowPointers = new SparseIntArray();
    }

    private final List<State> states = new ArrayList<>();
    /** Pointer id -> index of the control it started on. */
    private final SparseIntArray owned = new SparseIntArray();
    /** Pointers passed on to SDL while this view holds the gesture: id -> { device id, last x, last y }
     * (review 12201: a release must name the finger's own device, or SDL drops it and the finger stays down). */
    private final SparseArray<float[]> forwarded = new SparseArray<>();
    /** How many controls hold each key down (two controls may share one). */
    private final SparseIntArray keyDownCount = new SparseIntArray();

    private boolean hardwareInputHidden;
    private boolean menuUp;
    private boolean matchLoading;
    private boolean menuPollMissing;
    private boolean loadingPollMissing;

    // --------------------------------------------------------------- editor state

    private boolean editing;
    private OnaProfile snapshot;
    private int selected = -1;
    private int dragging = -1;
    private float dragOffX;
    private float dragOffY;
    private float pinchStart;
    private float scaleStart;
    private boolean barAtBottom;
    private final RectF barRect = new RectF();
    private final RectF[] barButtons = new RectF[6];
    private static final int BAR_ADD = 0;
    private static final int BAR_EDIT = 1;
    private static final int BAR_DELETE = 2;
    private static final int BAR_MOVE = 3;
    private static final int BAR_SAVE = 4;
    private static final int BAR_CANCEL = 5;

    // --------------------------------------------------------------- drawing (ONA's ElementRenderer)

    static final int DEFAULT_COLOR = 0xFFE6E6E6;
    static final int[] PALETTE = {
        0, 0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3, 0xFF9C27B0, 0xFFE91E63,
        0xFF795548, 0xFF9E9E9E, 0xFF000000,
    };
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint select = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final RectF tmp = new RectF();
    private final RectF bounds = new RectF();

    private OnaOverlayView(GeneralsZHActivity activity, OnaProfile profile) {
        super(activity);
        this.activity = activity;
        this.density = activity.getResources().getDisplayMetrics().density;
        setProfile(profile);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.5f * density);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        back.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setShadowLayer(2.5f * density, 0f, 0f, Color.argb(230, 0, 0, 0));
        select.setStyle(Paint.Style.STROKE);
        select.setStrokeWidth(2.5f * density);
        select.setColor(Color.rgb(33, 150, 243));
        select.setPathEffect(new DashPathEffect(new float[] { 8f * density, 5f * density }, 0f));
        grid.setColor(Color.argb(45, 255, 255, 255));
        grid.setStrokeWidth(1f);
        barText.setTextAlign(Paint.Align.CENTER);
        barText.setTypeface(Typeface.DEFAULT_BOLD);
        barText.setColor(Color.WHITE);
        for (int i = 0; i < barButtons.length; i++) barButtons[i] = new RectF();
        setFocusable(false);
    }

    private void setProfile(OnaProfile p) {
        this.profile = p;
        states.clear();
        for (int i = 0; i < p.elements.size(); i++) states.add(new State());
    }

    // =============================================================== attach / lifecycle (GeneralsZHActivity)

    static void attach(GeneralsZHActivity activity) {
        OnaOverlayView view = new OnaOverlayView(activity, OnaProfile.active(activity));
        activity.addContentView(view, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.bringToFront();
        view.setElevation(1000.0f);
        sAttached = view;
        view.handler.post(view.menuPoll);
        android.util.Log.i("GeneralsZH", "[ONA] controls attached: profile '" + view.profile.name + "', "
            + view.profile.elements.size() + " controls");
    }

    /** Only the activity that attached the view detaches it (a finishing one's onDestroy may come after the next). */
    static void detach(GeneralsZHActivity owner) {
        OnaOverlayView v = sAttached;
        if (v == null || v.activity != owner) return;
        v.releaseEverything();
        v.handler.removeCallbacksAndMessages(null);
        sAttached = null;
    }

    static boolean isActive() {
        return sAttached != null;
    }

    static boolean isEditing() {
        OnaOverlayView v = sAttached;
        return v != null && v.editing;
    }

    /** The gear (InGameHud, through HotkeyOverlayView): opens the editor, or closes it without saving. */
    static void toggleEdit() {
        final OnaOverlayView v = sAttached;
        if (v == null) return;
        v.post(() -> {
            if (v.editing) v.cancelEdit(); else v.enterEdit();
        });
    }

    /** onPause: nothing stays held while the engine is paused. */
    static void releaseAll() {
        final OnaOverlayView v = sAttached;
        if (v == null) return;
        v.post(v::releaseEverything);
    }

    static void bringToFrontIfAttached() {
        final OnaOverlayView v = sAttached;
        if (v == null) return;
        v.post(() -> {
            v.bringToFront();
            v.invalidate();
        });
    }

    static void setHardwareInputActive(final boolean active) {
        final OnaOverlayView v = sAttached;
        if (v == null) return;
        v.post(() -> {
            if (v.hardwareInputHidden == active) return;
            v.hardwareInputHidden = active;
            if (active) v.releaseEverything();
            v.setVisibility(active ? View.GONE : View.VISIBLE);
            if (!active) v.bringToFront();
            android.util.Log.i("GeneralsZH", "[ONA] controls " + (active ? "hidden (hardware input)" : "shown"));
        });
    }

    // As HotkeyOverlayView: battle controls hide while a game menu is up (they would cover its buttons), and the
    // change waits until no control is held. Polled from the engine; an older engine without the call shows them.
    private final Runnable menuPoll = new Runnable() {
        @Override
        public void run() {
            if (menuPollMissing) return;
            boolean up;
            try {
                up = GeneralsZHActivity.isMenuUp();
            } catch (UnsatisfiedLinkError e) {
                menuPollMissing = true;
                return;
            }
            boolean loading = false;
            if (!loadingPollMissing) {
                try {
                    loading = GeneralsZHActivity.isMatchLoading();
                } catch (UnsatisfiedLinkError e) {
                    loadingPollMissing = true;
                }
            }
            if ((up != menuUp || loading != matchLoading) && owned.size() == 0 && forwarded.size() == 0) {
                if (up && !menuUp) releaseEverything(); // latched toggles too: they cannot be tapped off there
                menuUp = up;
                matchLoading = loading;
                invalidate();
            }
            handler.postDelayed(this, 300);
        }
    };

    private boolean controlsHidden() {
        return menuUp && !editing;
    }

    // =============================================================== keys

    private void keyDown(int code) {
        if (code == 0) return;
        int n = keyDownCount.get(code, 0);
        if (n == 0) SDLActivity.onNativeKeyDown(code);
        keyDownCount.put(code, n + 1);
    }

    private void keyUp(int code) {
        if (code == 0) return;
        int n = keyDownCount.get(code, 0);
        if (n <= 0) return;
        if (n == 1) {
            keyDownCount.delete(code);
            SDLActivity.onNativeKeyUp(code);
        } else {
            keyDownCount.put(code, n - 1);
        }
    }

    /** A queued ESC on a match's loading screen opens the quit menu the moment it starts (as HotkeyOverlayView). */
    private boolean keyAllowed(int code) {
        return !(matchLoading && code == KeyEvent.KEYCODE_ESCAPE);
    }

    private void hold(State s, OnaProfile.Key k, boolean down) {
        if (k == null || k.code == 0) return;
        if (down) {
            if (!keyAllowed(k.code) || !s.held.add(k.code)) return;
            keyDown(k.code);
        } else if (s.held.remove(k.code)) {
            keyUp(k.code);
        }
    }

    private void releaseState(State s) {
        for (Integer code : new ArrayList<>(s.held)) keyUp(code);
        s.held.clear();
        java.util.Arrays.fill(s.pressed, false);
        s.stickX = 0f;
        s.stickY = 0f;
        s.rowPointers.clear();
    }

    /** Lets go of every key and pointer (toggles included). */
    private void releaseEverything() {
        for (State s : states) releaseState(s);
        for (int i = 0; i < keyDownCount.size(); i++) SDLActivity.onNativeKeyUp(keyDownCount.keyAt(i));
        keyDownCount.clear();
        owned.clear();
        cancelForwarded();
        invalidate();
    }

    /** Ends every finger handed to SDL as CANCELED (no click), on its own device, where it last was. */
    private void cancelForwarded() {
        for (int i = 0; i < forwarded.size(); i++) {
            float[] f = forwarded.valueAt(i);
            SDLActivity.onNativeTouch((int) f[0], forwarded.keyAt(i), MotionEvent.ACTION_CANCEL, f[1], f[2], 0f);
        }
        forwarded.clear();
    }

    private void buzz() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // =============================================================== geometry

    private void boundsOf(OnaProfile.Element e, RectF out) {
        float[] dp = e.sizeDp();
        float k = density * OnaProfile.fit(getWidth(), getHeight(), density);
        float w = Math.max(8f, dp[0] * k);
        float h = Math.max(8f, dp[1] * k);
        float cx = e.x * getWidth();
        float cy = e.y * getHeight();
        out.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    }

    private int hit(float x, float y) {
        for (int i = profile.elements.size() - 1; i >= 0; i--) {
            boundsOf(profile.elements.get(i), tmp);
            if (tmp.contains(x, y)) return i;
        }
        return -1;
    }

    // =============================================================== touch: playing

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            InGameHud.noteTouch(e.getX(i) + (e.getRawX() - e.getX()), e.getY(i) + (e.getRawY() - e.getY()));
        }
        if (editing) {
            return onEditTouch(e);
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int el = controlsHidden() ? -1 : hit(e.getX(), e.getY());
                if (el < 0) {
                    return false; // the battlefield's: the whole gesture goes to SDL as before
                }
                owned.clear();
                cancelForwarded();
                owned.put(e.getPointerId(0), el);
                controlDown(el, e, 0);
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int el = controlsHidden() ? -1 : hit(e.getX(i), e.getY(i));
                if (el >= 0) {
                    owned.put(e.getPointerId(i), el);
                    controlDown(el, e, i);
                } else {
                    forwarded.put(e.getPointerId(i), new float[] { e.getDeviceId(), 0f, 0f });
                    forward(e, i, MotionEvent.ACTION_DOWN);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int el = owned.get(id, -1);
                    if (el >= 0) {
                        controlMove(el, e, i);
                    } else if (forwarded.get(id) != null) {
                        forward(e, i, MotionEvent.ACTION_MOVE);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP: {
                int i = e.getActionIndex();
                pointerUp(e, i, MotionEvent.ACTION_UP);
                if (action == MotionEvent.ACTION_UP) {
                    owned.clear();
                    cancelForwarded(); // none left normally; never leave one down in SDL
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                // Review 12201: a system cancel (gesture navigation, the shade, a call) is a CANCEL for SDL too --
                // an UP would be a battlefield click / command.
                for (int i = 0; i < e.getPointerCount(); i++) pointerUp(e, i, MotionEvent.ACTION_CANCEL);
                owned.clear();
                cancelForwarded();
                return true;
            }
            default:
                return true;
        }
    }

    private void pointerUp(MotionEvent e, int i, int sdlAction) {
        int id = e.getPointerId(i);
        int el = owned.get(id, -1);
        if (el >= 0) {
            owned.delete(id);
            controlUp(el, id);
        } else if (forwarded.get(id) != null) {
            forward(e, i, sdlAction);
            forwarded.remove(id);
        }
    }

    private void forward(MotionEvent event, int index, int action) {
        float w = Math.max(1, getWidth() - 1);
        float h = Math.max(1, getHeight() - 1);
        float nx = Math.max(0f, Math.min(1f, event.getX(index) / w));
        float ny = Math.max(0f, Math.min(1f, event.getY(index) / h));
        float[] f = forwarded.get(event.getPointerId(index));
        if (f != null) {
            f[1] = nx;
            f[2] = ny;
        }
        SDLActivity.onNativeTouch(f != null ? (int) f[0] : event.getDeviceId(), event.getPointerId(index), action,
            nx, ny, Math.min(1f, event.getPressure(index)));
    }

    private void controlDown(int el, MotionEvent e, int i) {
        if (el < 0 || el >= profile.elements.size()) return;
        OnaProfile.Element c = profile.elements.get(el);
        State s = states.get(el);
        boundsOf(c, bounds);
        float lx = e.getX(i) - bounds.left;
        float ly = e.getY(i) - bounds.top;
        switch (c.type) {
            case OnaProfile.TYPE_BUTTON:
                if (c.toggle) {
                    boolean on = !s.pressed[0];
                    s.pressed[0] = on;
                    for (OnaProfile.Key k : c.bindings) hold(s, k, on);
                } else {
                    s.pressed[0] = true;
                    for (OnaProfile.Key k : c.bindings) hold(s, k, true);
                }
                buzz();
                break;
            case OnaProfile.TYPE_BUTTON_ROW: {
                int cell = cellAt(c, lx);
                s.rowPointers.put(e.getPointerId(i), cell);
                pressCell(c, s, cell, true);
                break;
            }
            case OnaProfile.TYPE_DPAD:
                applyDirs(c, s, dpadDirs(c, lx, ly));
                break;
            case OnaProfile.TYPE_STICK:
                stickMove(c, s, lx, ly);
                break;
            default:
                break; // TRACKPAD: shown, sends nothing in this port
        }
        invalidate();
    }

    private void controlMove(int el, MotionEvent e, int i) {
        if (el < 0 || el >= profile.elements.size()) return;
        OnaProfile.Element c = profile.elements.get(el);
        State s = states.get(el);
        boundsOf(c, bounds);
        float lx = e.getX(i) - bounds.left;
        float ly = e.getY(i) - bounds.top;
        switch (c.type) {
            case OnaProfile.TYPE_BUTTON_ROW: {
                int id = e.getPointerId(i);
                int old = s.rowPointers.get(id, -1);
                int cell = cellAt(c, lx);
                if (old >= 0 && cell != old) { // slide across the cells like a keyboard row
                    s.rowPointers.put(id, cell);
                    pressCell(c, s, old, false);
                    pressCell(c, s, cell, true);
                    invalidate();
                }
                break;
            }
            case OnaProfile.TYPE_DPAD:
                applyDirs(c, s, dpadDirs(c, lx, ly));
                invalidate();
                break;
            case OnaProfile.TYPE_STICK:
                stickMove(c, s, lx, ly);
                invalidate();
                break;
            default:
                break;
        }
    }

    private void controlUp(int el, int pointerId) {
        if (el < 0 || el >= profile.elements.size()) return;
        OnaProfile.Element c = profile.elements.get(el);
        State s = states.get(el);
        switch (c.type) {
            case OnaProfile.TYPE_BUTTON:
                if (!c.toggle) {
                    s.pressed[0] = false;
                    for (OnaProfile.Key k : c.bindings) hold(s, k, false);
                }
                break;
            case OnaProfile.TYPE_BUTTON_ROW: {
                int cell = s.rowPointers.get(pointerId, -1);
                s.rowPointers.delete(pointerId);
                if (cell >= 0) pressCell(c, s, cell, false);
                break;
            }
            case OnaProfile.TYPE_DPAD:
                applyDirs(c, s, new boolean[4]);
                break;
            case OnaProfile.TYPE_STICK:
                s.stickX = 0f;
                s.stickY = 0f;
                applyDirs(c, s, new boolean[4]);
                break;
            default:
                break;
        }
        invalidate();
    }

    // --------------------------------------------------------------- button row

    private int cellAt(OnaProfile.Element c, float lx) {
        int n = Math.max(1, c.bindings.size());
        int cell = (int) Math.floor(lx / (bounds.width() / n));
        return Math.max(0, Math.min(n - 1, cell));
    }

    private void pressCell(OnaProfile.Element c, State s, int cell, boolean down) {
        if (cell < 0 || cell >= c.bindings.size() || cell >= s.pressed.length) return;
        boolean stillHeld = false;
        for (int i = 0; i < s.rowPointers.size(); i++) {
            if (s.rowPointers.valueAt(i) == cell) stillHeld = true;
        }
        s.pressed[cell] = down || stillHeld; // drawing only
        OnaProfile.Key k = c.bindings.get(cell);
        // One keyDown / keyUp per finger: keyDownCount does the sharing (review 12201: skipping the up while another
        // finger held the cell left the key down for good).
        if (down) {
            buzz();
            if (k.code != 0 && keyAllowed(k.code)) keyDown(k.code);
        } else if (k.code != 0 && keyAllowed(k.code)) {
            keyUp(k.code);
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
        float cx = bounds.width() / 2f;
        float cy = bounds.height() / 2f;
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
    private void stickMove(OnaProfile.Element c, State s, float lx, float ly) {
        float radius = Math.min(bounds.width(), bounds.height()) / 2f * 0.6f;
        float dx = lx - bounds.width() / 2f;
        float dy = ly - bounds.height() / 2f;
        double angle = Math.atan2(dy, dx);
        float rr = (float) Math.min(Math.hypot(dx, dy) / Math.max(1f, radius), 1.0);
        s.stickX = (float) Math.cos(angle) * rr;
        s.stickY = (float) Math.sin(angle) * rr;
        float dz = OnaProfile.clamp(c.deadzone, 0f, 0.9f);
        float m = rr <= dz ? 0f : (rr - dz) / (1f - dz);
        if (!c.stickSendsKeys()) return; // ONA's mouse / gamepad outputs: drawn, not sent (see the class note)
        applyDirs(c, s, m > 0f ? sectorDirs(angle) : new boolean[4]);
    }

    private void applyDirs(OnaProfile.Element c, State s, boolean[] dirs) {
        for (int d = 0; d < 4; d++) {
            if (s.pressed[d] != dirs[d]) {
                s.pressed[d] = dirs[d];
                OnaProfile.Key k = d < c.bindings.size() ? c.bindings.get(d) : OnaProfile.NONE;
                if (dirs[d] && c.type == OnaProfile.TYPE_DPAD) buzz();
                hold(s, k, dirs[d]);
            }
        }
    }

    // =============================================================== drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (editing) {
            canvas.drawColor(Color.argb(120, 0, 0, 0));
            float step = 16f * density;
            for (float x = 0; x < getWidth(); x += step) canvas.drawLine(x, 0, x, getHeight(), grid);
            for (float y = 0; y < getHeight(); y += step) canvas.drawLine(0, y, getWidth(), y, grid);
        } else if (controlsHidden()) {
            return;
        }
        float opacity = editing ? Math.max(0.75f, profile.opacity) : profile.opacity;
        for (int i = 0; i < profile.elements.size(); i++) {
            OnaProfile.Element e = profile.elements.get(i);
            boundsOf(e, bounds);
            drawElement(canvas, e, bounds, editing ? null : states.get(i), opacity);
            if (editing && i == selected) canvas.drawRect(bounds, select);
        }
        if (editing) drawBar(canvas);
    }

    private void drawElement(Canvas c, OnaProfile.Element el, RectF b, State st, float opacity) {
        float o = OnaProfile.clamp(opacity, 0.05f, 1f);
        int base = el.color == 0 ? DEFAULT_COLOR : el.color;
        int a = (int) (o * 255);
        stroke.setColor(Color.argb(a, Color.red(base), Color.green(base), Color.blue(base)));
        back.setColor(Color.argb(Math.max(40, Math.min(170, (int) (o * 0.55f * 255))), 0, 0, 0));
        fill.setColor(Color.argb((int) (Math.min(0.8f, 0.30f + o * 0.45f) * 255), Color.red(base), Color.green(base), Color.blue(base)));
        text.setColor(Color.argb((int) (OnaProfile.clamp(o * 1.6f, 0.8f, 1f) * 255), 255, 255, 255));
        float inset = stroke.getStrokeWidth();
        r.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
        switch (el.type) {
            case OnaProfile.TYPE_BUTTON:
                drawButton(c, el, st != null && st.pressed[0]);
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

    private static String bindingText(OnaProfile.Element el) {
        StringBuilder b = new StringBuilder();
        for (OnaProfile.Key k : el.bindings) {
            if (k.code == 0) continue;
            if (b.length() > 0) b.append('+');
            b.append(k.label);
        }
        return b.length() == 0 ? "NONE" : b.toString();
    }

    private void drawButton(Canvas c, OnaProfile.Element el, boolean pressed) {
        shapeRect(c, el.shape, r, pressed);
        String t = el.text.isEmpty() ? bindingText(el) : el.text;
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

    private void drawRow(Canvas c, OnaProfile.Element el, State st) {
        int n = Math.max(1, el.bindings.size());
        float cell = r.width() / n;
        float rad = 8f * density;
        c.drawRoundRect(r, rad, rad, back);
        for (int i = 0; i < n; i++) {
            if (st != null && i < st.pressed.length && st.pressed[i]) {
                tmp.set(r.left + i * cell, r.top, r.left + (i + 1) * cell, r.bottom);
                c.drawRect(tmp, fill);
            }
            if (i > 0) c.drawLine(r.left + i * cell, r.top, r.left + i * cell, r.bottom, stroke);
            OnaProfile.Key k = i < el.bindings.size() ? el.bindings.get(i) : OnaProfile.NONE;
            label(c, k.label, r.left + (i + 0.5f) * cell, r.centerY(), cell * 0.84f, r.height() * 0.46f);
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

    // =============================================================== editor (ONA's OverlayEditorView, in place)

    private void enterEdit() {
        releaseEverything();
        snapshot = profile.copy();
        editing = true;
        selected = -1;
        dragging = -1;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        android.widget.Toast.makeText(activity, R.string.ona_edit_hint, android.widget.Toast.LENGTH_LONG).show();
        invalidate();
        InGameHud.refreshGear();
    }

    private void cancelEdit() {
        if (snapshot != null) setProfile(snapshot);
        snapshot = null;
        editing = false;
        selected = -1;
        dragging = -1;
        invalidate();
        InGameHud.refreshGear();
    }

    private void saveEdit() {
        boolean ok = OnaProfile.save(activity, profile);
        OnaProfile.setActive(activity, profile.id);
        snapshot = null;
        editing = false;
        selected = -1;
        dragging = -1;
        setProfile(profile); // fresh play state for the new list of controls
        android.widget.Toast.makeText(activity, ok ? R.string.ona_edit_saved : R.string.ona_edit_save_failed,
            android.widget.Toast.LENGTH_SHORT).show();
        invalidate();
        InGameHud.refreshGear();
    }

    private void layoutBar() {
        String[] labels = barLabels();
        barText.setTextSize(13f * density);
        float pad = 10f * density;
        float h = 40f * density;
        float total = 0f;
        float[] widths = new float[labels.length];
        for (int i = 0; i < labels.length; i++) {
            widths[i] = barText.measureText(labels[i]) + 2 * pad;
            total += widths[i];
        }
        float gap = 4f * density;
        total += gap * (labels.length - 1);
        float maxW = getWidth() - 16f * density;
        float k = total > maxW ? maxW / total : 1f;
        float left = (getWidth() - total * k) / 2f;
        float top = barAtBottom ? getHeight() - h - 8f * density : 8f * density;
        barRect.set(left - 6f * density, top - 4f * density, left + total * k + 6f * density, top + h + 4f * density);
        float x = left;
        for (int i = 0; i < labels.length; i++) {
            barButtons[i].set(x, top, x + widths[i] * k, top + h);
            x += (widths[i] + gap) * k;
        }
    }

    private String[] barLabels() {
        return new String[] {
            activity.getString(R.string.ona_edit_add), activity.getString(R.string.ona_edit_edit),
            activity.getString(R.string.ona_edit_delete), barAtBottom ? "↑" : "↓",
            activity.getString(R.string.ona_edit_save), activity.getString(R.string.ona_edit_cancel),
        };
    }

    private void drawBar(Canvas c) {
        layoutBar();
        String[] labels = barLabels();
        barPaint.setColor(Color.argb(235, 18, 18, 20));
        c.drawRoundRect(barRect, 14f * density, 14f * density, barPaint);
        for (int i = 0; i < labels.length; i++) {
            boolean enabled = (i != BAR_EDIT && i != BAR_DELETE) || selected >= 0;
            int color = i == BAR_SAVE ? 0xFF1B5E20 : i == BAR_CANCEL ? 0xFF5C1B1B : i == BAR_DELETE ? 0xFF4A2A2A : 0xFF263238;
            barPaint.setColor(enabled ? color : 0xFF1C1C1C);
            c.drawRoundRect(barButtons[i], 8f * density, 8f * density, barPaint);
            barText.setColor(enabled ? Color.WHITE : 0xFF707070);
            float w = barText.measureText(labels[i]);
            float maxW = barButtons[i].width() - 6f * density;
            float size = barText.getTextSize();
            if (w > maxW && w > 0f) barText.setTextSize(size * maxW / w);
            Paint.FontMetrics fm = barText.getFontMetrics();
            c.drawText(labels[i], barButtons[i].centerX(), barButtons[i].centerY() - (fm.ascent + fm.descent) / 2f, barText);
            barText.setTextSize(size);
        }
    }

    private boolean onEditTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                layoutBar();
                if (barRect.contains(e.getX(), e.getY())) {
                    for (int i = 0; i < barButtons.length; i++) {
                        if (barButtons[i].contains(e.getX(), e.getY())) {
                            onBar(i);
                            break;
                        }
                    }
                    dragging = -1;
                    return true;
                }
                selected = hit(e.getX(), e.getY());
                dragging = selected;
                if (selected >= 0) {
                    OnaProfile.Element el = profile.elements.get(selected);
                    dragOffX = e.getX() - el.x * getWidth();
                    dragOffY = e.getY() - el.y * getHeight();
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2 && selected >= 0) {
                    dragging = -1;
                    pinchStart = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    scaleStart = profile.elements.get(selected).scale;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2 && selected >= 0 && pinchStart > 0f) {
                    float d = (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
                    OnaProfile.Element el = profile.elements.get(selected);
                    el.scale = OnaProfile.clamp(Math.round(scaleStart * d / pinchStart * 20f) / 20f, 0.4f, 3f);
                    invalidate();
                } else if (dragging >= 0 && dragging < profile.elements.size()) {
                    float snap = 8f * density;
                    float cx = Math.round((e.getX() - dragOffX) / snap) * snap;
                    float cy = Math.round((e.getY() - dragOffY) / snap) * snap;
                    OnaProfile.Element el = profile.elements.get(dragging);
                    el.x = OnaProfile.clamp(cx / Math.max(1, getWidth()), 0f, 1f);
                    el.y = OnaProfile.clamp(cy / Math.max(1, getHeight()), 0f, 1f);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                pinchStart = 0f;
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = -1;
                pinchStart = 0f;
                return true;
            default:
                return true;
        }
    }

    private void onBar(int which) {
        buzz();
        switch (which) {
            case BAR_ADD:
                showAddDialog();
                break;
            case BAR_EDIT:
                if (selected >= 0) showElementDialog(selected);
                break;
            case BAR_DELETE:
                if (selected >= 0 && selected < profile.elements.size()) {
                    profile.elements.remove(selected);
                    states.remove(selected);
                    selected = -1;
                }
                break;
            case BAR_MOVE:
                barAtBottom = !barAtBottom;
                break;
            case BAR_SAVE:
                saveEdit();
                return;
            case BAR_CANCEL:
                cancelEdit();
                return;
            default:
                break;
        }
        invalidate();
    }

    private Context dialogContext() {
        return new android.view.ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private boolean activityGone() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    private void showAddDialog() {
        if (activityGone()) return;
        CharSequence[] items = {
            activity.getString(R.string.ona_type_button), activity.getString(R.string.ona_type_row),
            activity.getString(R.string.ona_type_dpad), activity.getString(R.string.ona_type_stick),
        };
        new android.app.AlertDialog.Builder(dialogContext())
            .setTitle(R.string.ona_edit_add)
            .setItems(items, (d, which) -> {
                OnaProfile.Element e = new OnaProfile.Element();
                e.id = "e" + Long.toString(System.currentTimeMillis(), 36);
                e.x = 0.5f;
                e.y = 0.5f;
                switch (which) {
                    case 1:
                        e.type = OnaProfile.TYPE_BUTTON_ROW;
                        for (int i = 1; i <= 4; i++) e.bindings.add(OnaProfile.keyOf("K" + i));
                        break;
                    case 2:
                    case 3:
                        e.type = which == 2 ? OnaProfile.TYPE_DPAD : OnaProfile.TYPE_STICK;
                        for (String k : new String[] { "UP", "RIGHT", "DOWN", "LEFT" }) e.bindings.add(OnaProfile.keyOf(k));
                        break;
                    default:
                        e.type = OnaProfile.TYPE_BUTTON;
                        e.bindings.add(OnaProfile.keyOf("SPACE"));
                        break;
                }
                profile.elements.add(e);
                states.add(new State());
                selected = profile.elements.size() - 1;
                invalidate();
                if (e.type == OnaProfile.TYPE_BUTTON || e.type == OnaProfile.TYPE_BUTTON_ROW) showElementDialog(selected);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** The selected control's settings: its keys, label, toggle, shape, colour and size; applied on OK. */
    private void showElementDialog(final int index) {
        if (activityGone() || index < 0 || index >= profile.elements.size()) return;
        final OnaProfile.Element target = profile.elements.get(index);
        final OnaProfile.Element work = target.copy();
        final Context ctx = dialogContext();
        final int pad = (int) (16 * density);

        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        final android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (8 * density), pad, 0);
        scroll.addView(root);

        // Keys: one button per binding slot; a tap picks the key.
        android.widget.TextView keysTitle = new android.widget.TextView(ctx);
        keysTitle.setText(R.string.ona_el_keys);
        root.addView(keysTitle);
        final android.widget.LinearLayout keys = new android.widget.LinearLayout(ctx);
        keys.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView keysScroll = new android.widget.HorizontalScrollView(ctx);
        keysScroll.addView(keys);
        root.addView(keysScroll);
        final Runnable[] rebuildKeys = new Runnable[1];
        rebuildKeys[0] = () -> {
            keys.removeAllViews();
            boolean dir = work.type == OnaProfile.TYPE_DPAD || work.type == OnaProfile.TYPE_STICK;
            int slots = dir ? 4 : work.type == OnaProfile.TYPE_BUTTON ? 2 : work.bindings.size();
            while (work.bindings.size() < slots) work.bindings.add(OnaProfile.NONE);
            String[] dirNames = { "↑", "→", "↓", "←" };
            for (int i = 0; i < slots; i++) {
                final int slot = i;
                android.widget.Button b = new android.widget.Button(ctx);
                b.setAllCaps(false);
                OnaProfile.Key k = work.bindings.get(i);
                String name = k.code == 0 ? activity.getString(R.string.ona_key_none) : k.label;
                b.setText(dir ? dirNames[i] + " " + name : name);
                b.setOnClickListener(v -> pickKey(ctx, work.bindings.get(slot), key -> {
                    work.bindings.set(slot, key);
                    rebuildKeys[0].run();
                }));
                keys.addView(b);
            }
            if (work.type == OnaProfile.TYPE_BUTTON_ROW) {
                android.widget.Button add = new android.widget.Button(ctx);
                add.setText("+");
                add.setOnClickListener(v -> {
                    if (work.bindings.size() < 12) work.bindings.add(OnaProfile.NONE);
                    rebuildKeys[0].run();
                });
                keys.addView(add);
                android.widget.Button remove = new android.widget.Button(ctx);
                remove.setText("−");
                remove.setOnClickListener(v -> {
                    if (work.bindings.size() > 1) work.bindings.remove(work.bindings.size() - 1);
                    rebuildKeys[0].run();
                });
                keys.addView(remove);
            }
        };
        rebuildKeys[0].run();

        final android.widget.EditText label = new android.widget.EditText(ctx);
        label.setSingleLine(true);
        label.setHint(R.string.ona_el_text_hint);
        label.setText(work.text);
        label.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(16) });
        if (work.type == OnaProfile.TYPE_BUTTON) root.addView(label);

        final android.widget.CheckBox toggle = new android.widget.CheckBox(ctx);
        toggle.setText(R.string.ona_el_toggle);
        toggle.setChecked(work.toggle);
        if (work.type == OnaProfile.TYPE_BUTTON) root.addView(toggle);

        final android.widget.CheckBox eight = new android.widget.CheckBox(ctx);
        eight.setText(R.string.ona_el_eight_way);
        eight.setChecked(work.eightWay);
        if (work.type == OnaProfile.TYPE_DPAD) root.addView(eight);

        android.widget.LinearLayout looks = new android.widget.LinearLayout(ctx);
        looks.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final String[] shapeIcons = { "●", "■", "▬", "⬭" };
        final android.widget.Button shape = new android.widget.Button(ctx);
        shape.setText(shapeIcons[work.shape]);
        shape.setOnClickListener(v -> {
            work.shape = (work.shape + 1) % shapeIcons.length;
            shape.setText(shapeIcons[work.shape]);
        });
        if (work.type == OnaProfile.TYPE_BUTTON) looks.addView(shape);
        final android.widget.Button color = new android.widget.Button(ctx);
        color.setAllCaps(false);
        final Runnable paintColor = () -> {
            color.setText(activity.getString(R.string.ona_el_color) + " ■");
            color.setTextColor(work.color == 0 ? DEFAULT_COLOR : work.color);
        };
        paintColor.run();
        color.setOnClickListener(v -> {
            int at = 0;
            for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == work.color) at = i;
            work.color = PALETTE[(at + 1) % PALETTE.length];
            paintColor.run();
        });
        looks.addView(color);
        root.addView(looks);

        final android.widget.TextView sizeLabel = new android.widget.TextView(ctx);
        root.addView(sizeLabel);
        final android.widget.SeekBar size = new android.widget.SeekBar(ctx);
        size.setMax(260); // 40% .. 300%
        size.setProgress(Math.round(work.scale * 100f) - 40);
        sizeLabel.setText(activity.getString(R.string.ona_el_size, Math.round(work.scale * 100f)));
        size.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean user) {
                work.scale = (p + 40) / 100f;
                sizeLabel.setText(activity.getString(R.string.ona_el_size, p + 40));
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) { }
        });
        root.addView(size);

        new android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.ona_el_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                int at = profile.elements.indexOf(target);
                if (at < 0) return; // deleted meanwhile
                work.text = label.getText().toString().trim();
                work.toggle = toggle.isChecked();
                work.eightWay = eight.isChecked();
                profile.elements.set(at, work);
                invalidate();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private interface KeyPicked {
        void picked(OnaProfile.Key key);
    }

    private void pickKey(Context ctx, OnaProfile.Key current, final KeyPicked done) {
        final List<OnaProfile.Key> keys = new ArrayList<>();
        keys.add(OnaProfile.NONE);
        keys.addAll(OnaProfile.PICKER);
        CharSequence[] names = new CharSequence[keys.size()];
        int checked = 0;
        for (int i = 0; i < keys.size(); i++) {
            OnaProfile.Key k = keys.get(i);
            names[i] = k.code == 0 ? activity.getString(R.string.ona_key_none) : (k.label.equals(k.name) ? k.label : k.label + "  (" + k.name + ")");
            if (k == current) checked = i;
        }
        new android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.ona_el_keys)
            .setSingleChoiceItems(names, checked, (d, which) -> {
                done.picked(keys.get(which));
                d.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseEverything();
        handler.removeCallbacksAndMessages(null);
        if (sAttached == this) sAttached = null;
        super.onDetachedFromWindow();
    }
}
