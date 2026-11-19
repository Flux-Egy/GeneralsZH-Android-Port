/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Adapted from ONA Launcher 2 "ona-controls" (view/OverlayElementView.kt, view/ElementRenderer.kt,
 * view/OverlayEditorView.kt), GPL-3.0: the controls' maths, look and editor, ported to Java (now in
 * OnaInput, OnaRenderer and OnaEditorView) and to one view inside the game's own window.
 */
package com.generalsx.zerohour;

import android.graphics.Canvas;
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

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12201 ONA-style controls (owner), drawn INSIDE the game's
 * window: a plain View over the SDL surface, like HotkeyOverlayView (which it replaces when the launcher's
 * "Control style" is ONA). Keys go straight to the game with SDLActivity.onNativeKeyDown / onNativeKeyUp; a
 * finger that does not start on a control goes to SDL untouched (returned unhandled, or forwarded with
 * SDLActivity.onNativeTouch while another finger holds a control), so the touch layer (TouchInput.cpp) sees
 * exactly what it sees today. No Shizuku, no Accessibility service, no system overlay window, no permission.
 *
 * The analog stick and the D-pad send keys (the arrow keys by default: the camera pans; WASD are Generals'
 * command hotkeys). ONA's mouse, gamepad and touch outputs are not sent: a mouse event made in Java would make
 * the engine treat the phone as having a hardware mouse (SDL3Mouse), so mouse output needs an engine hook --
 * Phase 2. Every held key is released when the controls hide (a menu, a hardware mouse / keyboard, the app paused).
 *
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 Fully customizable controls (Phase 1): what a control
 * sends is OnaInput's (chords, macros, hold / tap / toggle), its look OnaRenderer's (rotation, icons, colours);
 * the gear opens OnaEditorView over the battlefield, the same editor as the launcher's. Multi-step macros never
 * run in a LAN or online match (owner); GeneralsZHActivity.isNetworkGame says which, and an engine too old to say
 * counts as online.
 */
final class OnaOverlayView extends View {

    private static OnaOverlayView sAttached;

    private final GeneralsZHActivity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OnaRenderer renderer;
    private final OnaInput input;
    private OnaProfile profile;

    /** Pointer id -> index of the control it started on. */
    private final SparseIntArray owned = new SparseIntArray();
    /** Pointers passed on to SDL while this view holds the gesture: id -> { device id, last x, last y }
     * (review 12201: a release must name the finger's own device, or SDL drops it and the finger stays down). */
    private final SparseArray<float[]> forwarded = new SparseArray<>();

    private boolean hardwareInputHidden;
    private boolean menuUp;
    private boolean matchLoading;
    private boolean menuPollMissing;
    private boolean loadingPollMissing;
    private boolean networkPollMissing;
    private long lastMacroBlockedToast;

    private OnaEditorView editor;

    private OnaOverlayView(GeneralsZHActivity activity, OnaProfile profile) {
        super(activity);
        this.activity = activity;
        this.renderer = new OnaRenderer(activity.getResources().getDisplayMetrics().density);
        this.input = new OnaInput(new OnaInput.Sink() {
            @Override
            public void keyDown(int code) {
                SDLActivity.onNativeKeyDown(code);
            }

            @Override
            public void keyUp(int code) {
                SDLActivity.onNativeKeyUp(code);
            }

            @Override
            public boolean macrosAllowed() {
                if (networkPollMissing) return false;
                try {
                    return !GeneralsZHActivity.isNetworkGame();
                } catch (UnsatisfiedLinkError e) { // an older engine cannot say: treat it as online
                    networkPollMissing = true;
                    return false;
                }
            }

            @Override
            public void macroBlocked() {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastMacroBlockedToast < 4000) return;
                lastMacroBlockedToast = now;
                android.widget.Toast.makeText(activity, R.string.ona_macro_blocked, android.widget.Toast.LENGTH_SHORT).show();
            }

            /** A queued ESC on a match's loading screen opens the quit menu the moment it starts (as HotkeyOverlayView). */
            @Override
            public boolean keyAllowed(int code) {
                return !(matchLoading && code == KeyEvent.KEYCODE_ESCAPE);
            }

            @Override
            public void haptic() {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }

            @Override
            public void changed() {
                invalidate();
            }
        }, renderer);
        setProfile(profile);
        setFocusable(false);
    }

    private void setProfile(OnaProfile p) {
        this.profile = p;
        input.setProfile(p);
        input.setViewSize(getWidth(), getHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        input.setViewSize(w, h);
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
        v.closeEditor();
        v.releaseEverything();
        v.handler.removeCallbacksAndMessages(null);
        sAttached = null;
    }

    static boolean isActive() {
        return sAttached != null;
    }

    static boolean isEditing() {
        OnaOverlayView v = sAttached;
        return v != null && v.editor != null;
    }

    /** The gear (InGameHud, through HotkeyOverlayView): opens the editor, or closes it without saving. */
    static void toggleEdit() {
        final OnaOverlayView v = sAttached;
        if (v == null) return;
        v.post(() -> {
            if (v.editor != null) v.cancelEdit(); else v.enterEdit();
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
            if (v.editor != null) v.editor.bringToFront();
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
        return menuUp || editor != null;
    }

    // =============================================================== release

    /** Lets go of every key and pointer (toggles and macros included). */
    private void releaseEverything() {
        input.releaseAll();
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

    // =============================================================== touch: playing

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            InGameHud.noteTouch(e.getX(i) + (e.getRawX() - e.getX()), e.getY(i) + (e.getRawY() - e.getY()));
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                int el = controlsHidden() ? -1 : input.hit(e.getX(), e.getY());
                if (el < 0) {
                    return false; // the battlefield's: the whole gesture goes to SDL as before
                }
                owned.clear();
                cancelForwarded();
                owned.put(e.getPointerId(0), el);
                input.down(el, e.getPointerId(0), e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int el = controlsHidden() ? -1 : input.hit(e.getX(i), e.getY(i));
                if (el >= 0) {
                    owned.put(e.getPointerId(i), el);
                    input.down(el, e.getPointerId(i), e.getX(i), e.getY(i));
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
                        input.move(el, id, e.getX(i), e.getY(i));
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
            input.up(el, id);
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

    // =============================================================== drawing

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (controlsHidden()) return;
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < profile.elements.size(); i++) {
            renderer.draw(canvas, profile, profile.elements.get(i), w, h, input.look(i), 0f);
        }
    }

    // =============================================================== editor (OnaEditorView over the battlefield)

    private void enterEdit() {
        if (editor != null || activity.isFinishing() || activity.isDestroyed()) return;
        releaseEverything();
        editor = new OnaEditorView(activity, profile, true, new OnaEditorView.Host() {
            @Override
            public void onSave(OnaProfile edited) {
                boolean ok = OnaProfile.save(activity, edited);
                OnaProfile.setActive(activity, edited.id);
                setProfile(edited); // fresh play state for the new list of controls
                closeEditor();
                android.widget.Toast.makeText(activity, ok ? R.string.ona_edit_saved : R.string.ona_edit_save_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancel() {
                cancelEdit();
            }
        });
        activity.addContentView(editor, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editor.setElevation(1002.0f);
        editor.bringToFront();
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        android.widget.Toast.makeText(activity, R.string.ona_edit_hint, android.widget.Toast.LENGTH_LONG).show();
        invalidate();
        InGameHud.refreshGear();
    }

    private void cancelEdit() {
        closeEditor();
    }

    private void closeEditor() {
        OnaEditorView e = editor;
        if (e == null) return;
        editor = null;
        ViewGroup parent = (ViewGroup) e.getParent();
        if (parent != null) parent.removeView(e);
        invalidate();
        InGameHud.refreshGear();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDetachedFromWindow() {
        closeEditor();
        releaseEverything();
        handler.removeCallbacksAndMessages(null);
        if (sAttached == this) sAttached = null;
        super.onDetachedFromWindow();
    }
}
