// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 Small things drawn over the game, top right (the performance
// HUD switched on in the launcher; since 11/11/2026 the "i" and "G" buttons always):
//  - the fixed buttons (HudButtons, 11/11/2026: "i" and "G" Clear Group, always on).
//    The "i" Info button: while it is on, a tap on the battlefield shows what is under the finger
//    (the game's own mouse-over hint: name, owner, health, a supply pile's amount) instead of
//    selecting or ordering (engine: GXMouseInject.h GXSetUnitInfoMode). Hidden while a hardware
//    mouse or keyboard is connected, like the touch buttons.
//  - the performance HUD (PerfHudView): frames per second and the hottest thermal zone from the
//    engine (GXDeviceStats.h), battery from the system, once a second, read off the UI thread, with
//    a 30-second graph. The player moves, resizes and restyles it in the game (long press), and
//    picks what it shows in the launcher.
// The view is only as big as its contents, so touches anywhere else still reach the game. An engine
// that predates these natives (an over-the-air engine older than the launcher) just leaves them off.
package com.generalsx.zerohour;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;


final class InGameHud {
    private InGameHud() {}

    static final String PREF_PERF_HUD = "gx_perf_hud";
    private static final String TAG = "GeneralsZH";

    private static GeneralsZHActivity sOwner;
    private static PerfHudView sHud;
    private static final TextView[] sButtons = new TextView[HudButtons.COUNT];
    private static boolean sInfoOn;
    private static boolean sClearArmed;
    private static boolean sHardwareInput;
    private static HandlerThread sThread;
    private static volatile boolean sHudCollapsed;

    static boolean perfHudEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_PERF_HUD, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** After super.onCreate (the engine library is loaded by then). */
    static void attach(GeneralsZHActivity activity) {
        // A new game screen replaces any earlier one; the engine's modes are process-wide, so they
        // start off with the buttons (or stay off without them).
        stopThread();
        sInfoOn = false;
        sClearArmed = false;
        java.util.Arrays.fill(sButtons, null);
        sHud = null;
        sHudCollapsed = false;
        sOwner = activity;
        setInfoModeNative(false);
        setClearArmedNative(false);
        // GeneralsX @feature Flux Build 11/11/2026 One view per fixed button ("i", "G"), always on (owner),
        // placed where the player left it (HudButtons). Before, "i" had a launcher switch and sat in a row
        // that moved only while the touch editor (the gear) was open.
        for (int id = 0; id < HudButtons.COUNT; id++) {
            attachButton(activity, id);
        }
        if (perfHudEnabled(activity)) {
            attachHud(activity);
        }
    }

    private static void attachButton(final GeneralsZHActivity activity, final int id) {
        final TextView b = new TextView(activity);
        b.setText(HudButtons.LABELS[id]);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        if (id == HudButtons.INFO) {
            b.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        } else {
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        }
        b.setContentDescription(activity.getString(id == HudButtons.INFO
            ? R.string.hud_info_button : R.string.hud_clear_group_button));
        sButtons[id] = b;
        paint(activity, id);
        b.setOnClickListener(v -> onTap(activity, id));
        int size = dp(activity, HudButtons.SIZE_DP);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.LEFT);
        activity.addContentView(b, params);
        b.setElevation(1001.0f); // above the touch-button overlay (1000)
        b.setVisibility(sHardwareInput ? View.GONE : View.VISIBLE);
        makeMovable(activity, id, b);
        b.post(() -> place(activity, id, b));
    }

    private static void onTap(GeneralsZHActivity activity, int id) {
        switch (id) {
            case HudButtons.INFO: {
                boolean next = !sInfoOn;
                if (!setInfoModeNative(next)) {
                    Toast.makeText(activity, R.string.hud_needs_new_engine, Toast.LENGTH_LONG).show();
                    return;
                }
                sInfoOn = next;
                paint(activity, id);
                Toast.makeText(activity, next ? R.string.hud_info_on : R.string.hud_info_off, Toast.LENGTH_SHORT).show();
                return;
            }
            case HudButtons.CLEAR_GROUP: {
                // GeneralsX @feature Flux Build 11/11/2026 Like "i": on until used or tapped again. While on,
                // the next group chosen -- a number on the touch buttons or a group button of the game's own
                // group panel -- is emptied (engine: GroupPanel.cpp GXClearGroupArmed). Selection untouched.
                boolean next = !sClearArmed;
                if (!setClearArmedNative(next)) {
                    Toast.makeText(activity, R.string.hud_needs_new_engine, Toast.LENGTH_LONG).show();
                    return;
                }
                sClearArmed = next;
                paint(activity, id);
                if (next) {
                    watchClearArmed();
                }
                Toast.makeText(activity, next ? R.string.hud_clear_group_on : R.string.hud_clear_group_off, Toast.LENGTH_SHORT).show();
                return;
            }
            default:
                break;
        }
    }

    private static boolean isOn(int id) {
        switch (id) {
            case HudButtons.INFO: return sInfoOn;
            case HudButtons.CLEAR_GROUP: return sClearArmed;
            default: return false;
        }
    }

    private static void paint(Context ctx, int id) {
        final TextView b = sButtons[id];
        if (b == null) {
            return;
        }
        final boolean on = isOn(id);
        b.setSelected(on);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            b.setStateDescription(ctx.getString(on ? R.string.hud_state_on : R.string.hud_state_off));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(on ? Color.rgb(220, 181, 86) : Color.argb(170, 22, 33, 44));
        bg.setStroke(dp(ctx, 2), Color.rgb(220, 181, 86));
        b.setBackground(bg);
        b.setTextColor(on ? Color.rgb(22, 33, 44) : Color.rgb(240, 230, 200));
    }

    /** For HotkeyOverlayView: a number key while "G" is on clears that group instead. */
    static boolean isClearGroupArmed() {
        if (!sClearArmed) {
            return false;
        }
        // Review: the game's group panel may have used it since the last poll -- ask the engine.
        try {
            if (!GeneralsZHActivity.isClearGroupArmed()) {
                sClearArmed = false;
                GeneralsZHActivity owner = sOwner;
                if (owner != null) {
                    paint(owner, HudButtons.CLEAR_GROUP);
                }
                return false;
            }
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
        return true;
    }

    static void disarmClearGroup() {
        if (!sClearArmed) {
            return;
        }
        sClearArmed = false;
        setClearArmedNative(false);
        GeneralsZHActivity owner = sOwner;
        if (owner != null) {
            paint(owner, HudButtons.CLEAR_GROUP);
        }
    }

    /** While "G" is on, notice when the game's group panel has used it (the engine turns it off). */
    private static void watchClearArmed() {
        final View b = sButtons[HudButtons.CLEAR_GROUP];
        if (b == null) {
            return;
        }
        b.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sClearArmed || sButtons[HudButtons.CLEAR_GROUP] != b) {
                    return;
                }
                boolean armed;
                try {
                    armed = GeneralsZHActivity.isClearGroupArmed();
                } catch (UnsatisfiedLinkError e) {
                    return;
                }
                if (!armed) {
                    sClearArmed = false;
                    paint(b.getContext(), HudButtons.CLEAR_GROUP);
                    return;
                }
                b.postDelayed(this, 250);
            }
        }, 250);
    }

    private static boolean setClearArmedNative(boolean on) {
        try {
            GeneralsZHActivity.setClearGroupArmed(on);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "engine without nativeSetClearGroupArmed: " + e.getMessage());
            return false;
        }
    }

    /**
     * GeneralsX @feature Flux Build 11/11/2026 A long press picks the button up, then a drag moves it (also
     * with the touch-button panel off); a tap is an ordinary click. While the touch editor (the gear) is
     * open a drag moves it at once, as before. Where it was left is remembered (HudButtons).
     */
    @SuppressWarnings("ClickableViewAccessibility")
    private static void makeMovable(final GeneralsZHActivity activity, final int id, final View button) {
        final float slop = dp(activity, 10);
        final float[] down = new float[4];
        final boolean[] state = { false, false }; // dragging, moved before the long press
        final Runnable pickUp = () -> {
            state[0] = true;
            button.setPressed(false);
            button.setScaleX(1.15f);
            button.setScaleY(1.15f);
            button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        };
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    down[2] = v.getX();
                    down[3] = v.getY();
                    state[0] = false;
                    state[1] = false;
                    v.setPressed(true);
                    v.postDelayed(pickUp, android.view.ViewConfiguration.getLongPressTimeout());
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0];
                    float dy = e.getRawY() - down[1];
                    if (!state[0] && Math.abs(dx) + Math.abs(dy) > slop) {
                        if (HotkeyOverlayView.isEditModeActive()) {
                            v.removeCallbacks(pickUp);
                            state[0] = true;
                            v.setPressed(false);
                        } else if (!state[1]) {
                            // Slid off before the long press: neither a move nor a tap.
                            state[1] = true;
                            v.removeCallbacks(pickUp);
                            v.setPressed(false);
                        }
                    }
                    if (state[0]) {
                        moveTo(v, down[2] + dx, down[3] + dy);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.removeCallbacks(pickUp);
                    v.setPressed(false);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    if (state[0]) {
                        save(activity, id, v);
                    } else if (!state[1] && e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        v.performClick();
                    }
                    state[0] = false;
                    return true;
                default:
                    return true;
            }
        });
    }

    private static void moveTo(View v, float x, float y) {
        android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
        if (parent == null) {
            return;
        }
        v.setX(Math.max(0f, Math.min(x, parent.getWidth() - v.getWidth())));
        v.setY(Math.max(0f, Math.min(y, parent.getHeight() - v.getHeight())));
    }

    private static void save(Context ctx, int id, View v) {
        android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            return;
        }
        HudButtons.savePosition(ctx, id, v.getX() / parent.getWidth(), v.getY() / parent.getHeight());
    }

    /** Where it was left, or its default under the touch editor's gear (HudButtons); again on a resize. */
    private static void place(final Context ctx, final int id, final View v) {
        final android.view.ViewGroup parent = (android.view.ViewGroup) v.getParent();
        if (parent == null) {
            return;
        }
        final Runnable place = () -> {
            float[] f = HudButtons.savedPosition(ctx, id);
            if (f != null) {
                moveTo(v, f[0] * parent.getWidth(), f[1] * parent.getHeight());
                return;
            }
            int right = 0, top = 0;
            android.view.WindowInsets insets = v.getRootWindowInsets();
            if (insets != null && insets.getDisplayCutout() != null) {
                right = insets.getDisplayCutout().getSafeInsetRight();
                top = insets.getDisplayCutout().getSafeInsetTop();
            }
            float[] d = HudButtons.defaultPosition(id, parent.getWidth(), parent.getHeight(),
                ctx.getResources().getDisplayMetrics().density, right, top);
            moveTo(v, d[0], d[1]);
        };
        place.run();
        parent.addOnLayoutChangeListener((p, l, t, r, bt, ol, ot, or, ob) -> {
            if (r - l != or - ol || bt - t != ob - ot) {
                v.post(place);
            }
        });
    }

    private static boolean setInfoModeNative(boolean on) {
        try {
            GeneralsZHActivity.setUnitInfoMode(on);
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "engine without nativeSetUnitInfoMode: " + e.getMessage());
            return false;
        }
    }

    // GeneralsX @feature Flux Build 06/11/2026 The HUD is its own view now (PerfHudView), placed
    // anywhere by the player; the Info button keeps the top-right corner.
    private static void attachHud(final GeneralsZHActivity activity) {
        final PerfHudView hud = new PerfHudView(activity);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.LEFT);
        activity.addContentView(hud, params);
        hud.setElevation(1001.0f);
        sHud = hud;
        // Left of the Info button's column under the gear (that row spans 12-68 dp from the right),
        // with a gap.
        final float rightInset = dp(activity, 80);
        hud.post(() -> hud.placeInitially(rightInset));

        sThread = new HandlerThread("gx-hud");
        sThread.start();
        final Handler worker = new Handler(sThread.getLooper());
        final Handler ui = new Handler(Looper.getMainLooper());
        final Runnable[] task = new Runnable[1];
        hud.setOnExpanded(() -> {
            sHudCollapsed = false;
            if (task[0] != null) {
                worker.removeCallbacks(task[0]);
                worker.post(task[0]);
            }
        });
        task[0] = new Runnable() {
            private boolean nativeMissing;

            @Override
            public void run() {
                if (sHudCollapsed) {
                    // Only a dot on screen: read nothing, just notice when it is opened again.
                    ui.post(() -> {
                        if (sHud == hud) {
                            sHudCollapsed = hud.isCollapsed();
                        }
                    });
                    worker.postDelayed(this, 1000);
                    return;
                }
                float fps = -1f;
                int temp = -1;
                if (!nativeMissing) {
                    try {
                        float[] stats = GeneralsZHActivity.hudStats();
                        if (stats != null && stats.length >= 2) {
                            fps = stats[0];
                            temp = (int) stats[1];
                        }
                    } catch (UnsatisfiedLinkError e) {
                        nativeMissing = true;
                        Log.w(TAG, "engine without nativeGetHudStats: " + e.getMessage());
                    }
                }
                Intent battery = activity.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                final int level = batteryPercent(battery);
                final boolean charging = battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
                final float shownFps = fps;
                final int shownTemp = temp;
                ui.post(() -> {
                    if (sHud == hud) {
                        hud.update(shownFps, shownTemp, level, charging);
                        sHudCollapsed = hud.isCollapsed();
                    }
                });
                worker.postDelayed(this, 1000);
            }
        };
        worker.post(task[0]);
    }

    private static int batteryPercent(Intent battery) {
        if (battery == null) {
            return -1;
        }
        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        return level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
    }

    /** Hardware mouse or keyboard connected: the fixed buttons step aside with the touch buttons. */
    static void setHardwareInputActive(boolean active) {
        sHardwareInput = active;
        final GeneralsZHActivity owner = sOwner;
        if (owner == null) {
            return;
        }
        owner.runOnUiThread(() -> {
            for (int id = 0; id < HudButtons.COUNT; id++) {
                if (sButtons[id] != null) {
                    sButtons[id].setVisibility(active ? View.GONE : View.VISIBLE);
                }
            }
            if (active) {
                if (sInfoOn && setInfoModeNative(false)) {
                    sInfoOn = false;
                    paint(owner, HudButtons.INFO);
                }
                disarmClearGroup();
            }
        });
    }

    static void bringToFront() {
        for (final View b : sButtons) {
            if (b != null) {
                b.post(b::bringToFront);
            }
        }
        final PerfHudView hud = sHud;
        if (hud != null) {
            hud.post(hud::bringToFront);
        }
    }

    /** From the game screen's onDestroy; a later screen that has already attached is left alone. */
    static void detach(GeneralsZHActivity activity) {
        if (sOwner != activity) {
            return;
        }
        stopThread();
        if (sInfoOn) {
            setInfoModeNative(false);
        }
        if (sClearArmed) {
            setClearArmedNative(false);
        }
        sOwner = null;
        sHud = null;
        java.util.Arrays.fill(sButtons, null);
        sInfoOn = false;
        sClearArmed = false;
    }

    private static void stopThread() {
        if (sThread != null) {
            sThread.quitSafely();
            sThread = null;
        }
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
