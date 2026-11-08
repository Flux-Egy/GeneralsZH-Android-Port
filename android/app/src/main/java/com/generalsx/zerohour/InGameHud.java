// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 Two small things drawn over the game, top right, each
// switched on in the launcher (Graphics -> Performance):
//  - the "i" Info button: while it is on, a tap on the battlefield shows what is under the finger
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
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


final class InGameHud {
    private InGameHud() {}

    static final String PREF_INFO_BUTTON = "gx_info_button";
    static final String PREF_PERF_HUD = "gx_perf_hud";
    private static final String TAG = "GeneralsZH";

    private static GeneralsZHActivity sOwner;
    private static LinearLayout sRow;
    private static PerfHudView sHud;
    private static TextView sInfoButton;
    private static boolean sInfoOn;
    private static boolean sHardwareInput;
    private static HandlerThread sThread;
    private static volatile boolean sHudCollapsed;

    static boolean infoButtonEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_INFO_BUTTON, true);
    }

    static boolean perfHudEnabled(Context ctx) {
        return prefs(ctx).getBoolean(PREF_PERF_HUD, false);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** After super.onCreate (the engine library is loaded by then). */
    static void attach(GeneralsZHActivity activity) {
        // A new game screen replaces any earlier one; the engine's mode is process-wide, so it
        // starts off with the button (or stays off without it).
        stopThread();
        sInfoOn = false;
        sInfoButton = null;
        sRow = null;
        sHud = null;
        sHudCollapsed = false;
        sOwner = activity;
        setInfoModeNative(false);
        final boolean info = infoButtonEnabled(activity);
        final boolean hud = perfHudEnabled(activity);
        if (!info && !hud) {
            return;
        }
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        // Always the top right, in every language: the corner the game's own interface leaves free.
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(activity, 6);
        row.setPadding(pad, pad, pad, pad);

        if (info) {
            sInfoButton = buildInfoButton(activity);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44));
            lp.leftMargin = dp(activity, 8);
            row.addView(sInfoButton, lp);
            sInfoButton.setVisibility(sHardwareInput ? View.GONE : View.VISIBLE);
        }

        // GeneralsX @feature Flux Build 07/11/2026 Movable like the HUD (owner's request): placed with
        // setX/setY, a drag moves it, a tap toggles Info mode; where it was left is remembered.
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.LEFT);
        if (info) {
            activity.addContentView(row, params);
            row.setElevation(1001.0f); // above the touch-button overlay (1000)
            sRow = row;
            makeDraggable(activity, row, sInfoButton);
            row.post(() -> placeInfoRow(activity, row));
            // Kept on screen when it changes size (the button shown again after a hardware mouse or
            // keyboard is unplugged widens the row in place).
            row.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol || b - t != ob - ot) {
                    v.post(() -> moveRow(row, row.getX(), row.getY()));
                }
            });
        }
        if (hud) {
            attachHud(activity);
        }
    }

    private static final String PREF_INFO_X = "gx_info_x";
    private static final String PREF_INFO_Y = "gx_info_y";

    /** A drag (past a small slop) moves the row; a tap is an ordinary click on the button. */
    @SuppressWarnings("ClickableViewAccessibility")
    private static void makeDraggable(final GeneralsZHActivity activity, final LinearLayout row, final View button) {
        final float slop = dp(activity, 10);
        final float[] down = new float[4];
        final boolean[] dragging = { false };
        button.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    down[2] = row.getX();
                    down[3] = row.getY();
                    dragging[0] = false;
                    v.setPressed(true);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - down[0];
                    float dy = e.getRawY() - down[1];
                    // GeneralsX @tweak Flux Build 07/11/2026 Moved only while the touch editor (the gear)
                    // is open, like the touch buttons; otherwise it is a plain tap button.
                    if (!dragging[0] && HotkeyOverlayView.isEditModeActive() && Math.abs(dx) + Math.abs(dy) > slop) {
                        dragging[0] = true;
                        v.setPressed(false);
                    }
                    if (dragging[0]) {
                        moveRow(row, down[2] + dx, down[3] + dy);
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (dragging[0]) {
                        saveRow(activity, row);
                    } else {
                        v.performClick();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (dragging[0]) {
                        saveRow(activity, row);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private static void moveRow(LinearLayout row, float x, float y) {
        android.view.ViewGroup parent = (android.view.ViewGroup) row.getParent();
        if (parent == null) {
            return;
        }
        row.setX(Math.max(0f, Math.min(x, parent.getWidth() - row.getWidth())));
        row.setY(Math.max(0f, Math.min(y, parent.getHeight() - row.getHeight())));
    }

    private static void saveRow(Context ctx, LinearLayout row) {
        android.view.ViewGroup parent = (android.view.ViewGroup) row.getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) {
            return;
        }
        prefs(ctx).edit().putFloat(PREF_INFO_X, row.getX() / parent.getWidth())
            .putFloat(PREF_INFO_Y, row.getY() / parent.getHeight()).apply();
    }

    /**
     * Where it was left, or by default under the touch editor's gear (HotkeyOverlayView: centre 40 dp from
     * the right, 32 dp from the top, 16 dp radius) so it covers neither the gear nor the chat button left of
     * it; again on a resize.
     */
    private static void placeInfoRow(final Context ctx, final LinearLayout row) {
        final android.view.ViewGroup parent = (android.view.ViewGroup) row.getParent();
        if (parent == null) {
            return;
        }
        final Runnable place = () -> {
            float fx = prefs(ctx).getFloat(PREF_INFO_X, -1f);
            float fy = prefs(ctx).getFloat(PREF_INFO_Y, -1f);
            if (fx >= 0f && fy >= 0f) {
                moveRow(row, fx * parent.getWidth(), fy * parent.getHeight());
                return;
            }
            int right = 0, top = 0;
            android.view.WindowInsets insets = row.getRootWindowInsets();
            if (insets != null && insets.getDisplayCutout() != null) {
                right = insets.getDisplayCutout().getSafeInsetRight();
                top = insets.getDisplayCutout().getSafeInsetTop();
            }
            float gearCentreX = parent.getWidth() - right - dp(ctx, 40);
            moveRow(row, gearCentreX - row.getWidth() * 0.5f, top + dp(ctx, 56));
        };
        place.run();
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                row.post(place);
            }
        });
    }

    private static TextView buildInfoButton(final GeneralsZHActivity activity) {
        final TextView b = new TextView(activity);
        b.setText("i");
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setContentDescription(activity.getString(R.string.hud_info_button));
        paintInfoButton(activity, b);
        b.setOnClickListener(v -> {
            boolean next = !sInfoOn;
            if (!setInfoModeNative(next)) {
                Toast.makeText(activity, R.string.hud_needs_new_engine, Toast.LENGTH_LONG).show();
                return;
            }
            sInfoOn = next;
            paintInfoButton(activity, b);
            Toast.makeText(activity, next ? R.string.hud_info_on : R.string.hud_info_off, Toast.LENGTH_SHORT).show();
        });
        return b;
    }

    private static void paintInfoButton(Context ctx, TextView b) {
        b.setSelected(sInfoOn);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            b.setStateDescription(ctx.getString(sInfoOn ? R.string.hud_state_on : R.string.hud_state_off));
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(sInfoOn ? Color.rgb(220, 181, 86) : Color.argb(170, 22, 33, 44));
        bg.setStroke(dp(ctx, 2), Color.rgb(220, 181, 86));
        b.setBackground(bg);
        b.setTextColor(sInfoOn ? Color.rgb(22, 33, 44) : Color.rgb(240, 230, 200));
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

    /** Hardware mouse or keyboard connected: the Info button steps aside with the touch buttons. */
    static void setHardwareInputActive(boolean active) {
        sHardwareInput = active;
        final TextView b = sInfoButton;
        if (b == null) {
            return;
        }
        b.post(() -> {
            b.setVisibility(active ? View.GONE : View.VISIBLE);
            if (active && sInfoOn && setInfoModeNative(false)) {
                sInfoOn = false;
                paintInfoButton(b.getContext(), b);
            }
        });
    }

    static void bringToFront() {
        final LinearLayout row = sRow;
        if (row != null) {
            row.post(row::bringToFront);
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
        sOwner = null;
        sRow = null;
        sHud = null;
        sInfoButton = null;
        sInfoOn = false;
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
