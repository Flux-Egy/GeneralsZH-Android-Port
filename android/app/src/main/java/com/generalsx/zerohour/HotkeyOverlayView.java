/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;

import org.libsdl.app.SDLActivity;

import java.util.ArrayList;

final class HotkeyOverlayView extends View {
    // GeneralsX @feature Flux Build 22/10/2026 The one overlay instance, for the hardware
    // mouse/keyboard auto-hide orchestrator (GeneralsZHActivity) to reach.
    private static HotkeyOverlayView sAttached;

    // GeneralsX @feature Flux Build 22/10/2026 True while a hardware mouse or keyboard is
    // connected: real pointing hardware owns the screen, so the whole overlay is hidden.
    private boolean hardwareInputHidden = false;


    private final TouchControlConfig config;
    private TouchControlConfig snapshotConfig;

    private final int accentColor;
    private int activeButton;
    private int activePointer;

    private boolean isEditMode = false;
    // GeneralsX @feature Flux Build 07/11/2026 GameHub-style resize: the selected button's bottom-right
    // handle, dragged to change width and height independently (the top-left corner stays put).
    private final RectF resizeHandleRect = new RectF();
    private boolean resizing = false;
    private boolean gearPressed = false;
    private static boolean sResizeHintShown = false;
    private float resizeStartX, resizeStartY, resizeStartW, resizeStartH, resizeLeft, resizeTop;
    private int selectedButton = -1;
    private int dragPointer = -1;

    private int activeToggledModifiers = 0;
    private final java.util.Set<Integer> toggledButtonIndices = new java.util.HashSet<>();

    private final Paint borderPaint;
    private final Paint editBorderPaint;
    private final Paint selectedBorderPaint;
    private final Paint toggleBorderPaint;
    private final Paint fillPaint;
    private final Paint textPaint;
    private final Paint toolbarPaint;
    private final Paint toolbarTextPaint;
    private final Paint gridPaint;

    private final Handler handler;
    private final ArrayList<TouchControlConfig.ButtonSpec> buttons;
    private final ArrayList<RectF> hitRects;

    // In-game Edit Mode Controls
    private final RectF gearRect = new RectF();
    // GeneralsX @feature Flux Build v1.2.7 28/10/2026 Fixed touch opener for
    // the in-game chat. It sends Return, which the engine maps to
    // MSG_META_CHAT_EVERYONE while no text entry has focus.
    private final RectF chatRect = new RectF();
    private final RectF toolbarRect = new RectF();
    private final RectF scaleMinusRect = new RectF();
    private final RectF scalePlusRect = new RectF();
    private final RectF alphaMinusRect = new RectF();
    private final RectF alphaPlusRect = new RectF();
    private final RectF cancelRect = new RectF();
    private final RectF saveRect = new RectF();
    // GeneralsX @feature Flux Build 05/11/2026 Second toolbar row: colour of the selected button, and
    // the player's own default layout (TouchControlConfig.saveAsDefaultLayout / loadSavedLayout).
    private final RectF layoutBarRect = new RectF();
    private final RectF colorRect = new RectF();
    private final RectF saveDefaultRect = new RectF();
    private final RectF loadSavedRect = new RectF();
    private final RectF resetRect = new RectF();
    // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 Buttons in the black bars (Windowed display mode).
    private final RectF barsRect = new RectF();
    // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 Touch editor row 2: the key of the selected button
    // (numbers, F-keys, Ctrl/Alt/Shift), adding and removing buttons, and dimming the game behind.
    private final RectF editRowRect = new RectF();
    private final RectF keyRect = new RectF();
    private final RectF addRect = new RectF();
    private final RectF deleteRect = new RectF();
    private final RectF dimTrackRect = new RectF();
    private int dimPercent = 35;
    private boolean draggingDim = false;
    /** The game's resolution and display mode, read once off the UI thread (0 = not known yet). */
    private volatile int gameResW = 0, gameResH = 0;
    private volatile boolean stretchMode = false;
    /** The black-bars display mode (DX8Wrapper "keep"); back in v1.4.2. */
    private static final boolean BARS_ENABLED = true;
    // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.1 round 5 The configured keys (E, Q, W ... SPACE, ESC)
    // are for battles: while a game menu is on screen they are not drawn and do not take touches, so
    // they no longer cover the menu's controls (owner screenshot: Options' network settings). The
    // chat and gear buttons stay. Polled from the engine (GeneralsZHActivity.isMenuUp); a change
    // waits until no key is held, so no key is left down. The touch editor always shows them.
    private boolean menuUp = false;
    private boolean menuPollMissing = false;
    private final Runnable menuPoll = new Runnable() {
        @Override
        public void run() {
            if (menuPollMissing) return;
            boolean up;
            try {
                up = GeneralsZHActivity.isMenuUp();
            } catch (UnsatisfiedLinkError e) {
                menuPollMissing = true; // an older engine: the keys always show, as before
                return;
            }
            boolean loading = false;
            if (!loadingPollMissing) {
                try {
                    loading = GeneralsZHActivity.isMatchLoading();
                } catch (UnsatisfiedLinkError e) {
                    loadingPollMissing = true; // an older engine: loading counts as a menu, as before
                }
            }
            if ((up != menuUp || loading != matchLoading) && activeButton < 0) {
                if (up && !menuUp) {
                    // GeneralsX @bugfix Flux Build 11/11/2026 review: the keys hide under a menu, so a latched
                    // toggle could not be tapped off there -- release it.
                    flushPending(0);
                    releaseToggles();
                }
                menuUp = up;
                matchLoading = loading;
                invalidate();
            }
            handler.postDelayed(this, 300);
        }
    };

    // GeneralsX @feature Flux Build 11/11/2026 The panel switched off in the launcher: the overlay is still
    // there for the chat button and the gear (owner: always on), but draws and takes no keys.
    private boolean keysHidden() {
        return (this.menuUp || !this.config.enabled) && !this.isEditMode;
    }

    // GeneralsX @feature Flux Build 11/11/2026 A Ctrl / Shift / Alt key (TouchControlConfig key list) always
    // works as a one-shot toggle: a plain click of a lone modifier would do nothing in the game.
    private static boolean isToggle(TouchControlConfig.ButtonSpec spec) {
        return spec.actionType == TouchControlConfig.ACTION_TOGGLE || TouchControlConfig.isModifierKey(spec.keyCode);
    }

    // GeneralsX @feature Flux Build 11/11/2026 On a match's loading screen the keys show and work (the
    // engine queues them for the first frame, as a PC does), except ESC: a queued ESC opens the quit menu
    // the moment the match starts. The chat button does nothing there either (a queued Return opens chat).
    private boolean matchLoading = false;
    private boolean loadingPollMissing = false;

    private boolean keyOff(TouchControlConfig.ButtonSpec spec) {
        return this.matchLoading && !this.isEditMode && spec.keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    // GeneralsX @bugfix Flux Build 11/11/2026 Modifiers are released a few frames AFTER the key, not with it:
    // Keyboard::updateKeys stamps the modifier state at the END of a frame on every key of that frame, so
    // "Ctrl down, 1 down, 1 up, Ctrl up" in one burst reached the game as a plain 1 (owner's 12185 test:
    // Ctrl+1 selected group 1 instead of assigning it). The keys still to release, and when.
    private static final int MODIFIER_RELEASE_MS = 150;
    private int pendingUpMods = 0;
    private final java.util.ArrayList<Integer> pendingUpKeys = new java.util.ArrayList<>();
    private final Runnable releasePending = () -> flushPending(0);
    private final android.graphics.Paint barZonePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private static final int[] FILL_PALETTE = {
        0xFF161F2C, 0xFF1B3A5C, 0xFF5C1B1B, 0xFF5C4A12, 0xFF1F4D2B, 0xFF3D2B5C, 0xFF2B2B2B, 0xFFE0E0E0,
    };

    // Safe area insets (edge-to-edge protection against status bar, navigation bar, and camera cutout)
    private int safeLeft = 0;
    private int safeTop = 0;
    private int safeRight = 0;
    private int safeBottom = 0;

    private HotkeyOverlayView(GeneralsZHActivity activity, TouchControlConfig config) {
        super(activity);
        this.config = config;
        this.buttons = new ArrayList<>();
        this.hitRects = new ArrayList<>();

        this.fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.editBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.selectedBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.toggleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.toolbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.toolbarTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        this.handler = new Handler(Looper.getMainLooper());
        this.activeButton = -1;
        this.activePointer = -1;

        this.buttons.addAll(TouchControlConfig.copyButtons(config.buttons));
        this.accentColor = ThemeHelper.accentColor(activity);
        if (BARS_ENABLED) readDisplayModeAsync();

        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars()
                | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            this.safeLeft = insets.left;
            this.safeTop = insets.top;
            this.safeRight = insets.right;
            this.safeBottom = insets.bottom;
            rebuildHitRects();
            rebuildToolbarLayout();
            invalidate();
            return windowInsets;
        });

        this.borderPaint.setStyle(Paint.Style.STROKE);
        this.borderPaint.setStrokeWidth(dp(1.5f));
        this.borderPaint.setColor(this.accentColor);

        this.toggleBorderPaint.setStyle(Paint.Style.STROKE);
        this.toggleBorderPaint.setStrokeWidth(dp(3.0f));
        this.toggleBorderPaint.setColor(0xFF00E5FF); // Glowing Cyan border for toggled state

        this.editBorderPaint.setStyle(Paint.Style.STROKE);
        this.editBorderPaint.setStrokeWidth(dp(2.0f));
        this.editBorderPaint.setColor(0xFF29B6F6); // Vibrant Blue
        this.editBorderPaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));

        this.selectedBorderPaint.setStyle(Paint.Style.STROKE);
        this.selectedBorderPaint.setStrokeWidth(dp(3.5f));
        this.selectedBorderPaint.setColor(0xFFFFD54F); // Gold highlight

        this.textPaint.setColor(0xFFFFFFFF);
        this.textPaint.setTextAlign(Paint.Align.CENTER);
        this.textPaint.setFakeBoldText(true);
        this.textPaint.setShadowLayer(dp(2.0f), 0.0f, dp(1.0f), ViewCompat.MEASURED_STATE_MASK);

        this.toolbarTextPaint.setTextAlign(Paint.Align.CENTER);
        this.toolbarTextPaint.setFakeBoldText(true);
        this.toolbarTextPaint.setColor(0xFFFFFFFF);

        this.gridPaint.setStyle(Paint.Style.STROKE);
        this.gridPaint.setStrokeWidth(1.0f);
        this.gridPaint.setColor(Color.argb(35, 255, 255, 255));
    }

    static void attach(GeneralsZHActivity activity) {
        TouchControlConfig config = TouchControlConfig.load(activity);
        // GeneralsX @feature Flux Build 11/11/2026 Attached also with the panel off or empty: the chat button
        // and the gear are always on (owner); keysHidden() leaves the keys out then.
        HotkeyOverlayView overlay = new HotkeyOverlayView(activity, config);
        activity.addContentView(overlay, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.bringToFront();
        overlay.setElevation(1000.0f);
        sAttached = overlay;
        android.util.Log.i("GeneralsZH", "HotkeyOverlayView attached successfully with " + config.buttons.size() + " buttons");
    }

    static HotkeyOverlayView getAttached() {
        return sAttached;
    }

    /**
     * GeneralsX @feature Flux Build 07/11/2026 Whether the in-game touch editor is open (the gear): the
     * "i" Info button may be moved only then (InGameHud), and the HUD drags at once.
     */
    static boolean isEditModeActive() {
        HotkeyOverlayView v = sAttached;
        return v != null && v.isEditMode;
    }

    /**
     * GeneralsX @feature Flux Build 23/10/2026 An incoming call or the app switcher can take
     * the window between ACTION_DOWN and ACTION_UP, so the overlay never sees the release.
     * Called from GeneralsZHActivity.onPause(): drop any injected mouse button and any toggled
     * modifier before SDL pauses the native thread, or the engine resumes with a button held.
     */
    static void releaseActivePress() {
        HotkeyOverlayView view = sAttached;
        if (view == null) {
            return;
        }
        view.post(view::cancelActivePress);
    }

    private void cancelActivePress() {
        this.activeButton = -1;
        this.activePointer = -1;
        flushPending(0);
        releaseToggles();
        // Strictly release all active modifiers (CTRL, SHIFT, ALT) to native engine
        sendModifierKeyUp(TouchControlConfig.MOD_CTRL | TouchControlConfig.MOD_SHIFT | TouchControlConfig.MOD_ALT, 0);
        this.activeToggledModifiers = 0;
        this.toggledButtonIndices.clear();
        invalidate();
    }

    // GeneralsX @bugfix Flux Build 11/11/2026 review: a toggle presses its own key too (sendModifierKeyDown),
    // so releasing it must send that key's up as well -- not only the modifiers (a Toggle on "A" left A held).
    private void releaseToggles() {
        java.util.ArrayList<Integer> keys = new java.util.ArrayList<>();
        int mask = takeToggles(keys);
        for (int key : keys) {
            SDLActivity.onNativeKeyUp(key);
        }
        if (mask != 0) {
            sendModifierKeyUp(mask, 0);
        }
    }

    /**
     * The latched toggles, used up: their modifier mask, with each toggle's own key added to {@code ownKeys}
     * (a toggle presses its own key too, sendModifierKeyDown). The toggles go dark; nothing is sent here.
     */
    private int takeToggles(java.util.List<Integer> ownKeys) {
        for (Integer index : new java.util.ArrayList<>(this.toggledButtonIndices)) {
            if (index == null || index < 0 || index >= this.buttons.size()) {
                continue;
            }
            TouchControlConfig.ButtonSpec spec = this.buttons.get(index);
            if (isToggle(spec) && spec.keyCode != 0) {
                ownKeys.add(spec.keyCode);
            }
        }
        int mask = this.activeToggledModifiers;
        this.activeToggledModifiers = 0;
        this.toggledButtonIndices.clear();
        return mask;
    }

    /** Releases what an earlier key left held, except the modifiers in {@code keepMods} (still needed). */
    private void flushPending(int keepMods) {
        this.handler.removeCallbacks(this.releasePending);
        for (int key : this.pendingUpKeys) {
            SDLActivity.onNativeKeyUp(key);
        }
        this.pendingUpKeys.clear();
        int release = this.pendingUpMods & ~keepMods;
        if (release != 0) {
            sendModifierKeyUp(release, 0);
        }
        this.pendingUpMods &= keepMods;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        this.handler.removeCallbacks(this.menuPoll);
        this.handler.post(this.menuPoll);
    }

    @Override
    protected void onDetachedFromWindow() {
        this.handler.removeCallbacks(this.menuPoll);
        cancelActivePress();
        if (sAttached == this) {
            sAttached = null;
        }
        super.onDetachedFromWindow();
    }

    /**
     * GeneralsX @feature Flux Build 22/10/2026 Hardware mouse/keyboard auto-hide.
     *
     * With a real pointing device connected the floating buttons sit exactly under a
     * mouse-driven cursor. They swallow clicks meant for the battlefield, and vice versa a stray
     * click on the battlefield can land on a button while the player's hand is on the mouse.
     * While such hardware is present the whole overlay steps aside; the moment it is gone the
     * buttons come back. Runs safely on the UI thread.
     */
    public static void setHardwareInputActive(boolean active) {
        HotkeyOverlayView view = sAttached;
        if (view == null) {
            return;
        }
        view.post(() -> {
            if (view.hardwareInputHidden == active) {
                return;
            }
            view.hardwareInputHidden = active;
            if (active) {
                // GeneralsX @bugfix Flux Build 11/11/2026 review: a latched Ctrl / Shift / Alt key must not stay
                // held under a hardware mouse (every click would be a Ctrl-click).
                view.cancelActivePress();
            }
            view.setVisibility(active ? View.GONE : View.VISIBLE);
            if (!active) {
                view.bringToFront();
                view.requestLayout();
                view.invalidate();
            }
            android.util.Log.i("GeneralsZH", "HotkeyOverlayView visibility updated: " + (active ? "GONE (hardware input active)" : "VISIBLE"));
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        rebuildHitRects();
        rebuildToolbarLayout();

        // 0. In the editor, the black bars (Windowed display mode) show as free space for buttons.
        if (this.isEditMode) {
            RectF pic = gamePictureRect();
            if (pic != null) {
                this.barZonePaint.setStyle(android.graphics.Paint.Style.FILL);
                this.barZonePaint.setColor((this.accentColor & 0x00FFFFFF) | 0x22000000);
                if (pic.left > 0) {
                    canvas.drawRect(0, 0, pic.left, getHeight(), this.barZonePaint);
                    canvas.drawRect(pic.right, 0, getWidth(), getHeight(), this.barZonePaint);
                } else {
                    canvas.drawRect(0, 0, getWidth(), pic.top, this.barZonePaint);
                    canvas.drawRect(0, pic.bottom, getWidth(), getHeight(), this.barZonePaint);
                }
            }
        }

        // 1. Draw In-Game Edit Mode Grid, over the game dimmed as the player set it (v1.4.2)
        if (this.isEditMode && this.dimPercent > 0) {
            canvas.drawColor(Color.argb(this.dimPercent * 255 / 100, 0, 0, 0));
        }
        if (this.isEditMode) {
            for (int i = 1; i < 10; i++) {
                float gx = (getWidth() * i) / 10.0f;
                canvas.drawLine(gx, 0.0f, gx, getHeight(), this.gridPaint);
            }
            for (int j = 1; j < 6; j++) {
                float gy = (getHeight() * j) / 6.0f;
                canvas.drawLine(0.0f, gy, getWidth(), gy, this.gridPaint);
            }
        }

        // 2. Draw Floating Hotkey Buttons (using individual scale & opacity); none over a game menu.
        for (int i = 0; i < this.buttons.size() && !keysHidden(); i++) {
            RectF rect = this.hitRects.get(i);
            TouchControlConfig.ButtonSpec spec = this.buttons.get(i);
            if (keyOff(spec)) {
                continue;
            }
            boolean pressed = (i == this.activeButton);
            boolean selected = this.isEditMode && (i == this.selectedButton);
            boolean isToggled = this.toggledButtonIndices.contains(i);

            int baseAlpha = Math.round(spec.opacity * 255.0f);
            int color = spec.fillColor;
            if (isToggled) {
                color = 0xFF0277BD; // Active toggle cyan
            } else if (pressed || selected) {
                color = lighten(color, 0.20f);
            }
            this.fillPaint.setColor(color);
            this.fillPaint.setAlpha(isToggled ? 255 : (pressed || selected) ? Math.min(255, baseAlpha + 55) : baseAlpha);

            drawShape(canvas, rect, spec, this.fillPaint);

            // Outlines: Selected Gold / Toggled Cyan / Edit Mode Dashed / Normal Accent
            if (selected) {
                drawShape(canvas, rect, spec, this.selectedBorderPaint);
            } else if (isToggled) {
                drawShape(canvas, rect, spec, this.toggleBorderPaint);
            } else if (this.isEditMode) {
                drawShape(canvas, rect, spec, this.editBorderPaint);
            } else {
                this.borderPaint.setColor(this.accentColor);
                this.borderPaint.setAlpha(pressed ? 255 : Math.max(120, baseAlpha));
                drawShape(canvas, rect, spec, this.borderPaint);
            }

            // Button Label (sized by individual spec.scale)
            this.textPaint.setColor(spec.textColor);
            float textSize = dp(spec.label.length() > 5 ? 12.0f : 15.0f) * spec.scale;
            this.textPaint.setTextSize(textSize);
            Paint.FontMetrics metrics = this.textPaint.getFontMetrics();
            float baseline = rect.centerY() - ((metrics.ascent + metrics.descent) * 0.5f);
            canvas.drawText(spec.label, rect.centerX(), baseline, this.textPaint);

            // Toggle Badge: small 'T' in upper-right corner
            if (isToggle(spec)) {
                this.toolbarTextPaint.setTextSize(dp(8.0f));
                this.toolbarTextPaint.setColor(isToggled ? 0xFF00E5FF : 0xBBFFFFFF);
                canvas.drawText("T", rect.right - dp(7.0f), rect.top + dp(10.0f), this.toolbarTextPaint);
            }

            // In Edit Mode: the selected button's resize handle (bottom-right) and a size tag.
            if (selected) {
                drawResizeHandle(canvas, rect, spec);
            }

            // In Edit Mode: show position tag above selected button
            if (selected) {
                this.toolbarTextPaint.setTextSize(dp(10.0f));
                this.toolbarTextPaint.setColor(0xFFFFD54F);
                String posTag = String.format("X:%d%% Y:%d%%", Math.round(spec.x * 100), Math.round(spec.y * 100));
                canvas.drawText(posTag, rect.centerX(), rect.top - dp(6.0f), this.toolbarTextPaint);
            }
        }

        // 3. Draw Gear Toggle Button (Top-Right edge, respecting safe area)
        float gx = getWidth() - safeRight - dp(40.0f);
        float gy = safeTop + dp(32.0f);
        float gr = dp(16.0f);
        this.gearRect.set(gx - gr, gy - gr, gx + gr, gy + gr);

        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(this.isEditMode ? 0xDD0288D1 : 0x88101A24);
        canvas.drawCircle(gx, gy, gr, this.toolbarPaint);

        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.5f));
        this.toolbarPaint.setColor(this.isEditMode ? 0xFFFFFFFF : this.accentColor);
        canvas.drawCircle(gx, gy, gr, this.toolbarPaint);

        this.toolbarTextPaint.setTextSize(dp(14.0f));
        this.toolbarTextPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics gMetrics = this.toolbarTextPaint.getFontMetrics();
        float gBaseline = gy - ((gMetrics.ascent + gMetrics.descent) * 0.5f);
        canvas.drawText("⚙", gx, gBaseline, this.toolbarTextPaint);

        // 3b. Fixed in-game chat opener, left of the gear. Return is the key the
        // engine already maps to MSG_META_CHAT_EVERYONE when nothing is focused.
        float chatR = dp(17.0f);
        float chatCx = gx - gr - dp(10.0f) - chatR;
        float chatCy = gy;
        this.chatRect.set(chatCx - chatR, chatCy - chatR, chatCx + chatR, chatCy + chatR);
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(this.isEditMode ? 0x66101A24 : 0xCC1B5E20);
        canvas.drawCircle(chatCx, chatCy, chatR, this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.5f));
        this.toolbarPaint.setColor(this.isEditMode ? 0x88FFFFFF : this.accentColor);
        canvas.drawCircle(chatCx, chatCy, chatR, this.toolbarPaint);
        this.toolbarTextPaint.setTextSize(dp(10.0f));
        this.toolbarTextPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics cMetrics = this.toolbarTextPaint.getFontMetrics();
        float cBaseline = chatCy - ((cMetrics.ascent + cMetrics.descent) * 0.5f);
        canvas.drawText(getContext().getString(R.string.touch_chat_button), chatCx, cBaseline, this.toolbarTextPaint);

        // 4. Draw In-Game Edit Mode Toolbar (Bottom panel)
        if (this.isEditMode) {
            drawEditToolbar(canvas);
        }
    }

    private void drawResizeHandle(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec) {
        float r = dp(9.0f);
        float cx = rect.right;
        float cy = rect.bottom;
        this.resizeHandleRect.set(cx - r, cy - r, cx + r, cy + r);
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(0xFFFFD54F);
        canvas.drawCircle(cx, cy, r, this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(2.0f));
        this.toolbarPaint.setColor(0xFF0B121A);
        canvas.drawCircle(cx, cy, r, this.toolbarPaint);
        // Two short diagonal strokes: the usual "drag to resize" mark.
        canvas.drawLine(cx - r * 0.45f, cy + r * 0.15f, cx + r * 0.15f, cy - r * 0.45f, this.toolbarPaint);
        canvas.drawLine(cx - r * 0.05f, cy + r * 0.45f, cx + r * 0.45f, cy - r * 0.05f, this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        float rawW = spec.width > 0 ? spec.width : spec.widthDp;
        float rawH = spec.height > 0 ? spec.height : spec.heightDp;
        this.toolbarTextPaint.setTextSize(dp(10.0f));
        this.toolbarTextPaint.setColor(0xFFFFD54F);
        canvas.drawText(Math.round(rawW) + "\u00D7" + Math.round(rawH), rect.centerX(), rect.bottom + dp(14.0f),
            this.toolbarTextPaint);
    }

    /** Whether a touch at (x, y) grabs the selected button's resize handle (a finger-sized target). */
    private boolean hitsResizeHandle(float x, float y) {
        if (this.selectedButton < 0 || this.selectedButton >= this.buttons.size() || this.resizeHandleRect.isEmpty()) {
            return false;
        }
        // Outside the button the target is finger-sized; inside it only the handle itself, so a small
        // button can still be dragged by its body.
        RectF body = this.hitRects.get(this.selectedButton);
        float slack = body.contains(x, y) ? dp(4.0f) : dp(12.0f);
        return x >= this.resizeHandleRect.left - slack && x <= this.resizeHandleRect.right + slack
            && y >= this.resizeHandleRect.top - slack && y <= this.resizeHandleRect.bottom + slack;
    }

    private void applyResize(float x, float y) {
        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
        float scale = spec.scale > 0f ? spec.scale : 1f;
        float minPx = dp(28.0f) * scale;
        float maxPx = dp(220.0f) * scale;
        // No further than the safe area's right and bottom edges: past them rebuildHitRects would pin
        // that edge and grow the button the other way, away from the finger.
        float maxW = Math.max(minPx, Math.min(maxPx, getWidth() - safeRight - this.resizeLeft));
        float maxH = Math.max(minPx, Math.min(maxPx, getHeight() - safeBottom - this.resizeTop));
        float w = TouchControlConfig.clamp(this.resizeStartW + (x - this.resizeStartX), minPx, maxW);
        float h = TouchControlConfig.clamp(this.resizeStartH + (y - this.resizeStartY), minPx, maxH);
        if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
            float side = Math.min(Math.max(w, h), Math.min(maxW, maxH)); // these shapes stay square
            w = side;
            h = side;
        }
        float density = getResources().getDisplayMetrics().density;
        spec.width = spec.widthDp = w / (density * scale);
        spec.height = spec.heightDp = h / (density * scale);
        // Keep the top-left corner where it was: the spec's position is the button's centre.
        float availW = Math.max(1, getWidth() - safeLeft - safeRight);
        float availH = Math.max(1, getHeight() - safeTop - safeBottom);
        spec.x = TouchControlConfig.clamp((this.resizeLeft + w * 0.5f - safeLeft) / availW, 0.02f, 0.98f);
        spec.y = TouchControlConfig.clamp((this.resizeTop + h * 0.5f - safeTop) / availH, 0.02f, 0.98f);
        rebuildHitRects();
        invalidate();
    }

    private void drawEditToolbar(Canvas canvas) {
        // Toolbar background container
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(0xEE0B121A);
        canvas.drawRoundRect(this.toolbarRect, dp(14.0f), dp(14.0f), this.toolbarPaint);

        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.5f));
        this.toolbarPaint.setColor(this.accentColor);
        canvas.drawRoundRect(this.toolbarRect, dp(14.0f), dp(14.0f), this.toolbarPaint);

        // Size Control
        drawToolbarButton(canvas, this.scaleMinusRect, "-", 0xFF37474F);
        drawToolbarButton(canvas, this.scalePlusRect, "+", 0xFF37474F);
        this.toolbarTextPaint.setTextSize(dp(11.0f));
        this.toolbarTextPaint.setColor(0xFFFFFFFF);
        float sMidX = (this.scaleMinusRect.right + this.scalePlusRect.left) * 0.5f;
        float sMidY = this.toolbarRect.centerY() + dp(4.0f);

        String sizeText;
        String alphaText;
        // GeneralsX @bugfix Flux Build 05/11/2026 The launcher's translated strings, not fixed English.
        if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
            TouchControlConfig.ButtonSpec sel = this.buttons.get(this.selectedButton);
            sizeText = getContext().getString(R.string.touch_editor_scale_label, sel.label, Math.round(sel.scale * 100));
            alphaText = getContext().getString(R.string.touch_editor_alpha_label, sel.label, Math.round(sel.opacity * 100));
        } else {
            sizeText = getContext().getString(R.string.touch_editor_tap_button);
            alphaText = getContext().getString(R.string.touch_editor_to_resize);
        }
        drawFittedText(canvas, sizeText, sMidX, sMidY,
            this.scalePlusRect.left - this.scaleMinusRect.right - dp(4.0f), dp(11.0f));

        // Opacity Control
        drawToolbarButton(canvas, this.alphaMinusRect, "-", 0xFF37474F);
        drawToolbarButton(canvas, this.alphaPlusRect, "+", 0xFF37474F);
        float aMidX = (this.alphaMinusRect.right + this.alphaPlusRect.left) * 0.5f;
        drawFittedText(canvas, alphaText, aMidX, sMidY,
            this.alphaPlusRect.left - this.alphaMinusRect.right - dp(4.0f), dp(11.0f));

        // Cancel (X) & Save (✔)
        drawToolbarButton(canvas, this.cancelRect, "✕", 0xFFC62828); // Red
        drawToolbarButton(canvas, this.saveRect, "✔", 0xFF2E7D32);   // Green

        // Layout row
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(0xEE0B121A);
        canvas.drawRoundRect(this.layoutBarRect, dp(14.0f), dp(14.0f), this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.5f));
        this.toolbarPaint.setColor(this.accentColor);
        canvas.drawRoundRect(this.layoutBarRect, dp(14.0f), dp(14.0f), this.toolbarPaint);
        int swatch = 0xFF37474F;
        if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
            swatch = this.buttons.get(this.selectedButton).fillColor | 0xFF000000;
        }
        drawToolbarTextButton(canvas, this.colorRect, getContext().getString(R.string.touch_edit_color), swatch);
        drawToolbarTextButton(canvas, this.saveDefaultRect, getContext().getString(R.string.touch_save_default), 0xFF37474F);
        drawToolbarTextButton(canvas, this.loadSavedRect, getContext().getString(R.string.touch_load_saved), 0xFF37474F);
        drawToolbarTextButton(canvas, this.resetRect, getContext().getString(R.string.touch_reset_button), 0xFF37474F);
        if (!this.barsRect.isEmpty()) {
            drawToolbarTextButton(canvas, this.barsRect, getContext().getString(R.string.touch_bars_button), 0xFF37474F);
        }

        // Row 2: Key, Add, Delete, Dim.
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(0xEE0B121A);
        canvas.drawRoundRect(this.editRowRect, dp(14.0f), dp(14.0f), this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.5f));
        this.toolbarPaint.setColor(this.accentColor);
        canvas.drawRoundRect(this.editRowRect, dp(14.0f), dp(14.0f), this.toolbarPaint);
        drawToolbarTextButton(canvas, this.keyRect, getContext().getString(R.string.touch_key_button), 0xFF37474F);
        drawToolbarTextButton(canvas, this.addRect, getContext().getString(R.string.touch_add_button), 0xFF2E7D32);
        drawToolbarTextButton(canvas, this.deleteRect, getContext().getString(R.string.touch_delete_button), 0xFFC62828);
        RectF t = this.dimTrackRect;
        String dimText = getContext().getString(R.string.touch_dim_label, this.dimPercent);
        this.toolbarTextPaint.setTextSize(dp(10.0f));
        this.toolbarTextPaint.setColor(0xFFFFFFFF);
        canvas.drawText(dimText, t.centerX(), t.top + dp(10.0f), this.toolbarTextPaint);
        float lineY = t.bottom - dp(9.0f);
        float left = t.left + dp(8.0f), right = t.right - dp(8.0f);
        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(3.0f));
        this.toolbarPaint.setColor(0x66FFFFFF);
        canvas.drawLine(left, lineY, right, lineY, this.toolbarPaint);
        float knobX = left + (right - left) * this.dimPercent / (float) TouchControlConfig.EDITOR_DIM_MAX;
        this.toolbarPaint.setColor(this.accentColor);
        canvas.drawLine(left, lineY, knobX, lineY, this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(knobX, lineY, dp(7.0f), this.toolbarPaint);
    }

    /** The dimming slider's value at a touch x (0..EDITOR_DIM_MAX, in steps of 5). */
    private void setDimFromX(float x) {
        float left = this.dimTrackRect.left + dp(8.0f), right = this.dimTrackRect.right - dp(8.0f);
        float f = Math.max(0f, Math.min(1f, (x - left) / Math.max(1f, right - left)));
        int v = Math.round(f * TouchControlConfig.EDITOR_DIM_MAX / 5f) * 5;
        if (v != this.dimPercent) {
            this.dimPercent = v;
            invalidate();
        }
    }

    // GeneralsX @bugfix Flux Build 05/11/2026 Translated labels are longer than the fixed gaps between
    // the toolbar buttons: shrink the text (to 7 dp) and then shorten it until it fits.
    private void drawFittedText(Canvas canvas, String text, float cx, float baseline, float maxW, float sizePx) {
        this.toolbarTextPaint.setColor(0xFFFFFFFF);
        float size = sizePx;
        this.toolbarTextPaint.setTextSize(size);
        while (size > dp(7.0f) && this.toolbarTextPaint.measureText(text) > maxW) {
            size -= dp(0.5f);
            this.toolbarTextPaint.setTextSize(size);
        }
        String shown = text;
        while (shown.length() > 1 && this.toolbarTextPaint.measureText(shown) > maxW) {
            shown = shown.substring(0, shown.length() - 1);
        }
        canvas.drawText(shown, cx, baseline, this.toolbarTextPaint);
    }

    private void drawToolbarTextButton(Canvas canvas, RectF r, String label, int bgColor) {
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(bgColor);
        canvas.drawRoundRect(r, dp(8.0f), dp(8.0f), this.toolbarPaint);
        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.0f));
        this.toolbarPaint.setColor(0x88FFFFFF);
        canvas.drawRoundRect(r, dp(8.0f), dp(8.0f), this.toolbarPaint);
        this.toolbarTextPaint.setTextSize(dp(11.0f));
        // Dark text on a light swatch.
        int luminance = (Color.red(bgColor) * 299 + Color.green(bgColor) * 587 + Color.blue(bgColor) * 114) / 1000;
        this.toolbarTextPaint.setColor(luminance > 150 ? 0xFF000000 : 0xFFFFFFFF);
        String text = label;
        float maxW = r.width() - dp(6.0f);
        while (text.length() > 1 && this.toolbarTextPaint.measureText(text) > maxW) {
            text = text.substring(0, text.length() - 1);
        }
        Paint.FontMetrics fm = this.toolbarTextPaint.getFontMetrics();
        float bl = r.centerY() - ((fm.ascent + fm.descent) * 0.5f);
        canvas.drawText(text, r.centerX(), bl, this.toolbarTextPaint);
    }

    private void drawToolbarButton(Canvas canvas, RectF r, String label, int bgColor) {
        this.toolbarPaint.setStyle(Paint.Style.FILL);
        this.toolbarPaint.setColor(bgColor);
        canvas.drawRoundRect(r, dp(8.0f), dp(8.0f), this.toolbarPaint);

        this.toolbarPaint.setStyle(Paint.Style.STROKE);
        this.toolbarPaint.setStrokeWidth(dp(1.0f));
        this.toolbarPaint.setColor(0x88FFFFFF);
        canvas.drawRoundRect(r, dp(8.0f), dp(8.0f), this.toolbarPaint);

        this.toolbarTextPaint.setTextSize(dp(14.0f));
        this.toolbarTextPaint.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = this.toolbarTextPaint.getFontMetrics();
        float bl = r.centerY() - ((fm.ascent + fm.descent) * 0.5f);
        canvas.drawText(label, r.centerX(), bl, this.toolbarTextPaint);
    }

    private void rebuildToolbarLayout() {
        float barW = dp(410.0f);
        float barH = dp(46.0f);
        float barLeft = (getWidth() - barW) * 0.5f;
        float barBottom = getHeight() - safeBottom - dp(10.0f);
        float barTop = barBottom - barH;
        float barRight = barLeft + barW;
        this.toolbarRect.set(barLeft, barTop, barRight, barBottom);

        float pad = dp(6.0f);
        float btnW = dp(32.0f);
        float btnH = barH - (pad * 2.0f);
        float y1 = barTop + pad;
        float y2 = y1 + btnH;

        float x = barLeft + dp(10.0f);
        this.scaleMinusRect.set(x, y1, x + btnW, y2);
        x += btnW + dp(58.0f); // gap for text
        this.scalePlusRect.set(x, y1, x + btnW, y2);
        x += btnW + dp(16.0f);

        this.alphaMinusRect.set(x, y1, x + btnW, y2);
        x += btnW + dp(62.0f); // gap for text
        this.alphaPlusRect.set(x, y1, x + btnW, y2);
        x += btnW + dp(18.0f);

        this.cancelRect.set(x, y1, x + btnW, y2);
        x += btnW + dp(8.0f);
        this.saveRect.set(x, y1, x + btnW, y2);

        // At the top centre, away from the button area (the default buttons sit at 67% height) and
        // from the gear in the top-right corner.
        float rowTop = safeTop + dp(10.0f);
        float rowBottom = rowTop + barH;
        this.layoutBarRect.set(barLeft, rowTop, barRight, rowBottom);
        float gap = dp(6.0f);
        final boolean bars = gamePictureRect() != null;
        final int cells = bars ? 5 : 4;
        float cellW = (barW - pad * 2.0f - gap * (cells - 1)) / cells;
        float ry1 = rowTop + pad;
        float ry2 = ry1 + btnH;
        float cx = barLeft + pad;
        this.colorRect.set(cx, ry1, cx + cellW, ry2);
        cx += cellW + gap;
        this.saveDefaultRect.set(cx, ry1, cx + cellW, ry2);
        cx += cellW + gap;
        this.loadSavedRect.set(cx, ry1, cx + cellW, ry2);
        cx += cellW + gap;
        this.resetRect.set(cx, ry1, cx + cellW, ry2);
        if (bars) {
            cx += cellW + gap;
            this.barsRect.set(cx, ry1, cx + cellW, ry2);
        } else {
            this.barsRect.setEmpty();
        }

        // Row 2 (v1.4.2): Key, Add, Delete, and the dimming slider.
        float r2Top = rowBottom + dp(6.0f);
        this.editRowRect.set(barLeft, r2Top, barRight, r2Top + barH);
        float smallW = (barW - pad * 2.0f - gap * 3) * 0.2f;
        float ey1 = r2Top + pad;
        float ey2 = ey1 + btnH;
        float ex = barLeft + pad;
        this.keyRect.set(ex, ey1, ex + smallW, ey2);
        ex += smallW + gap;
        this.addRect.set(ex, ey1, ex + smallW, ey2);
        ex += smallW + gap;
        this.deleteRect.set(ex, ey1, ex + smallW, ey2);
        ex += smallW + gap;
        this.dimTrackRect.set(ex, ey1, barRight - pad, ey2);
    }

    /** Reads Options.ini's Resolution and GXDisplayMode (the same values the engine uses) in the background. */
    private void readDisplayModeAsync() {
        new Thread(() -> {
            int w = 0, h = 0;
            boolean stretch = true; // GeneralsX v1.4.2: Fullscreen unless GXDisplayMode = keep (as the engine)
            java.io.File ini = new java.io.File(DataPackInstaller.userDataDir(), "Options.ini");
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(ini))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    int eq = t.indexOf('=');
                    if (eq <= 0) continue;
                    String key = t.substring(0, eq).trim();
                    if (key.equals("Resolution")) {
                        String[] parts = t.substring(eq + 1).trim().split("\\s+");
                        if (parts.length >= 2) {
                            try { w = Integer.parseInt(parts[0]); h = Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) { }
                        }
                    } else if (key.equals("GXDisplayMode")) {
                        stretch = !t.substring(eq + 1).trim().equals("keep");
                    }
                }
            } catch (java.io.IOException ignored) {
                // no Options.ini yet: no bars known, nothing to offer
            }
            this.gameResW = w;
            this.gameResH = h;
            this.stretchMode = stretch;
            this.handler.post(this::invalidate);
        }, "gx-overlay-display").start();
    }

    /**
     * Where the game picture is on screen (DX8Wrapper's pillarbox math: the game's aspect, centred), or
     * null when it fills the screen (stretched, same aspect, or not known) or the bars are too narrow to
     * hold a button.
     */
    private RectF gamePictureRect() {
        int vw = getWidth(), vh = getHeight();
        // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 Bars only in Windowed (GXDisplayMode keep).
        if (!BARS_ENABLED || this.stretchMode || this.gameResW <= 0 || this.gameResH <= 0 || vw <= 0 || vh <= 0) return null;
        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 review: the size the engine really draws, after
        // its [GX-RES] rule (landscape, at least 800x600, at most the window), not the raw saved value.
        int[] res = SetupActivity.sanitizeResolution(this.gameResW, this.gameResH, vw, vh);
        if (res[0] <= 0 || res[1] <= 0) return null;
        float gameAspect = (float) res[0] / (float) res[1];
        float viewAspect = (float) vw / (float) vh;
        RectF r;
        if (viewAspect > gameAspect) {
            float w = vh * gameAspect;
            r = new RectF((vw - w) / 2f, 0, (vw + w) / 2f, vh);
            if (r.left < dp(48.0f)) return null;
        } else {
            float h = vw / gameAspect;
            r = new RectF(0, (vh - h) / 2f, vw, (vh + h) / 2f);
            if (r.top < dp(48.0f)) return null;
        }
        return r;
    }

    /** Moves every button over the game picture into the nearest bar, stacked evenly (editor only). */
    private void moveButtonsToBars() {
        RectF pic = gamePictureRect();
        if (pic == null) {
            toast(getContext().getString(R.string.touch_bars_none));
            return;
        }
        rebuildHitRects();
        int availW = Math.max(1, getWidth() - safeLeft - safeRight);
        int availH = Math.max(1, getHeight() - safeTop - safeBottom);
        boolean sideBars = pic.left > 0;
        java.util.List<Integer> first = new ArrayList<>(), second = new ArrayList<>();
        for (int i = 0; i < this.buttons.size() && i < this.hitRects.size(); i++) {
            RectF hr = this.hitRects.get(i);
            if (!RectF.intersects(hr, pic)) continue; // already in a bar
            boolean firstSide = sideBars ? hr.centerX() < getWidth() / 2f : hr.centerY() < getHeight() / 2f;
            (firstSide ? first : second).add(i);
        }
        if (first.isEmpty() && second.isEmpty()) {
            toast(getContext().getString(R.string.touch_bars_done));
            return;
        }
        for (int pass = 0; pass < 2; pass++) {
            java.util.List<Integer> list = pass == 0 ? first : second;
            for (int k = 0; k < list.size(); k++) {
                TouchControlConfig.ButtonSpec spec = this.buttons.get(list.get(k));
                float slot = (k + 1f) / (list.size() + 1f);
                float cx, cy;
                if (sideBars) {
                    cx = pass == 0 ? pic.left / 2f : (pic.right + getWidth()) / 2f;
                    cy = getHeight() * slot;
                } else {
                    cx = getWidth() * slot;
                    cy = pass == 0 ? pic.top / 2f : (pic.bottom + getHeight()) / 2f;
                }
                spec.x = TouchControlConfig.clamp((cx - safeLeft) / availW, 0.02f, 0.98f);
                spec.y = TouchControlConfig.clamp((cy - safeTop) / availH, 0.02f, 0.98f);
            }
        }
        rebuildHitRects();
        invalidate();
        toast(getContext().getString(R.string.touch_bars_moved));
    }

    private void rebuildHitRects() {
        this.hitRects.clear();
        int availW = Math.max(1, getWidth() - safeLeft - safeRight);
        int availH = Math.max(1, getHeight() - safeTop - safeBottom);
        for (TouchControlConfig.ButtonSpec spec : this.buttons) {
            float rawW = spec.width > 0 ? spec.width : spec.widthDp;
            float rawH = spec.height > 0 ? spec.height : spec.heightDp;
            float width = dp(rawW) * spec.scale;
            float height = dp(rawH) * spec.scale;
            if (spec.shape == TouchControlConfig.SHAPE_SQUARE || spec.shape == TouchControlConfig.SHAPE_CIRCLE) {
                float side = Math.min(width, height);
                width = side;
                height = side;
            }
            float cx = safeLeft + (spec.x * availW);
            float cy = safeTop + (spec.y * availH);
            float halfW = width * 0.5f;
            float halfH = height * 0.5f;
            // Clamp strictly inside safe area bounds so buttons are never covered by status bar, cutout, or gesture pill
            float left = Math.max(safeLeft, Math.min(safeLeft + availW - width, cx - halfW));
            float top = Math.max(safeTop, Math.min(safeTop + availH - height, cy - halfH));
            this.hitRects.add(new RectF(
                left,
                top,
                left + width,
                top + height
            ));
        }
    }

    private int findButton(float x, float y) {
        if (keysHidden()) {
            return -1;
        }
        if (this.hitRects.size() != this.buttons.size()) {
            rebuildHitRects();
        }
        for (int i = this.hitRects.size() - 1; i >= 0; i--) {
            if (this.hitRects.get(i).contains(x, y) && !keyOff(this.buttons.get(i))) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        // 0. Fixed chat button: Return opens the in-game chat entry (normal
        // gameplay only; in edit mode the toolbar owns the screen).
        if (!this.isEditMode && this.chatRect.contains(x, y)) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && !this.matchLoading) {
                // GeneralsX @bugfix Flux Build 11/11/2026 review: plain Return, so a latched Ctrl/Shift/Alt is
                // neither added to it nor used up by the chat button.
                SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ENTER);
                SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ENTER);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                performClick();
            }
            return true;
        }

        // 1. Gear Button Interception (Always active)
        // GeneralsX @bugfix Flux Build 07/11/2026 Only for a touch that starts on the gear: a move or
        // resize dragged across it used to stop there, and lifting on it cancelled every edit.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            this.gearPressed = this.gearRect.contains(x, y);
        }
        if (this.gearPressed) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                this.gearPressed = false;
                if (action == MotionEvent.ACTION_UP && this.gearRect.contains(x, y)) {
                    if (!this.isEditMode) {
                        enterEditMode();
                    } else {
                        cancelEditMode();
                    }
                    performClick();
                }
            }
            return true;
        }

        // 2. IN-GAME EDIT MODE: Bypass key injection, handle drag-and-drop & toolbar
        if (this.isEditMode) {
            return handleEditModeTouch(event);
        }

        // 3. NORMAL GAMEPLAY MODE: Pass-through touches, inject key presses to SDLActivity
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                int hit = findButton(x, y);
                if (hit < 0) {
                    return false; // Drops down to SDLSurfaceView (battlefield touch)
                }
                this.activeButton = hit;
                this.activePointer = event.getPointerId(0);
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN: {
                int pIdx = event.getActionIndex();
                int hit2 = findButton(event.getX(pIdx), event.getY(pIdx));
                if (hit2 >= 0) {
                    return true;
                }
                forwardTouchToSDL(event, pIdx, MotionEvent.ACTION_DOWN);
                return true;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int upIdx = event.getActionIndex();
                int upId = event.getPointerId(upIdx);
                if (upId == this.activePointer) {
                    finishActivePress();
                } else {
                    forwardTouchToSDL(event, upIdx, MotionEvent.ACTION_UP);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                if (this.activeButton >= 0) {
                    finishActivePress();
                }
                this.activeButton = -1;
                this.activePointer = -1;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                int pCount = event.getPointerCount();
                for (int p = 0; p < pCount; p++) {
                    int id = event.getPointerId(p);
                    if (id == this.activePointer) {
                        if (this.activeButton >= 0 && this.activeButton < this.hitRects.size()) {
                            float slop = dp(24.0f);
                            RectF rect = this.hitRects.get(this.activeButton);
                            float fx = event.getX(p);
                            float fy = event.getY(p);
                            if (fx < rect.left - slop || fx > rect.right + slop
                                    || fy < rect.top - slop || fy > rect.bottom + slop) {
                                this.activeButton = -1;
                                invalidate();
                            }
                        }
                    } else {
                        forwardTouchToSDL(event, p, MotionEvent.ACTION_MOVE);
                    }
                }
                return this.activeButton >= 0;
            }

            case MotionEvent.ACTION_CANCEL:
                this.activeButton = -1;
                this.activePointer = -1;
                invalidate();
                return true;

            default:
                return this.activeButton >= 0;
        }
    }

    private boolean handleEditModeTouch(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Check Toolbar Controls (Size -/+, Alpha -/+, Cancel, Save)
                if (this.scaleMinusRect.contains(x, y)) {
                    if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
                        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
                        spec.scale = TouchControlConfig.clamp(spec.scale - 0.05f, 0.5f, 2.5f);
                        rebuildHitRects();
                        invalidate();
                    }
                    return true;
                }
                if (this.scalePlusRect.contains(x, y)) {
                    if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
                        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
                        spec.scale = TouchControlConfig.clamp(spec.scale + 0.05f, 0.5f, 2.5f);
                        rebuildHitRects();
                        invalidate();
                    }
                    return true;
                }
                if (this.alphaMinusRect.contains(x, y)) {
                    if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
                        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
                        spec.opacity = TouchControlConfig.clamp(spec.opacity - 0.05f, 0.15f, 1.0f);
                        invalidate();
                    }
                    return true;
                }
                if (this.alphaPlusRect.contains(x, y)) {
                    if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
                        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
                        spec.opacity = TouchControlConfig.clamp(spec.opacity + 0.05f, 0.15f, 1.0f);
                        invalidate();
                    }
                    return true;
                }
                if (this.cancelRect.contains(x, y)) {
                    cancelEditMode();
                    return true;
                }
                if (this.colorRect.contains(x, y)) {
                    cycleSelectedColor();
                    return true;
                }
                if (this.saveDefaultRect.contains(x, y)) {
                    saveLayoutAsDefault();
                    return true;
                }
                if (this.loadSavedRect.contains(x, y)) {
                    loadSavedLayout();
                    return true;
                }
                if (!this.barsRect.isEmpty() && this.barsRect.contains(x, y)) {
                    moveButtonsToBars();
                    return true;
                }
                if (this.resetRect.contains(x, y)) {
                    replaceEditButtons(TouchControlConfig.defaults().buttons);
                    toast(getContext().getString(R.string.touch_layout_reset));
                    return true;
                }
                if (this.saveRect.contains(x, y)) {
                    saveEditMode();
                    return true;
                }
                if (this.keyRect.contains(x, y)) {
                    if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
                        showKeySheet(this.selectedButton, false);
                    } else {
                        toast(getContext().getString(R.string.touch_select_button_first));
                    }
                    return true;
                }
                if (this.addRect.contains(x, y)) {
                    TouchControlConfig.ButtonSpec spec = new TouchControlConfig.ButtonSpec("A", KeyEvent.KEYCODE_A, 0, 0.5f, 0.45f);
                    this.buttons.add(spec);
                    this.selectedButton = this.buttons.size() - 1;
                    rebuildHitRects();
                    invalidate();
                    showKeySheet(this.selectedButton, true);
                    return true;
                }
                if (this.deleteRect.contains(x, y)) {
                    // Review: with no buttons left the overlay is not attached at the next start, and
                    // with it the gear -- the way back into this editor.
                    if (this.buttons.size() <= 1) {
                        toast(getContext().getString(R.string.touch_keep_one_button));
                        return true;
                    }
                    if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
                        this.buttons.remove(this.selectedButton);
                        this.selectedButton = -1;
                        this.resizeHandleRect.setEmpty();
                        rebuildHitRects();
                        invalidate();
                        toast(getContext().getString(R.string.touch_button_deleted));
                    } else {
                        toast(getContext().getString(R.string.touch_select_button_first));
                    }
                    return true;
                }
                if (this.editRowRect.contains(x, y) && x >= this.dimTrackRect.left - dp(6.0f)) {
                    this.draggingDim = true;
                    setDimFromX(x);
                    return true;
                }
                // A tap on a toolbar row's background (between its buttons) is the toolbar's, not a
                // game button's hidden under it (review).
                if (this.editRowRect.contains(x, y) || this.layoutBarRect.contains(x, y) || this.toolbarRect.contains(x, y)) {
                    return true;
                }

                // The selected button's resize handle comes before selecting another button.
                if (hitsResizeHandle(x, y)) {
                    RectF r = this.hitRects.get(this.selectedButton);
                    this.resizing = true;
                    this.resizeStartX = x;
                    this.resizeStartY = y;
                    this.resizeStartW = r.width();
                    this.resizeStartH = r.height();
                    this.resizeLeft = r.left;
                    this.resizeTop = r.top;
                    this.dragPointer = event.getPointerId(0);
                    return true;
                }

                // Check Button Selection for Dragging
                int hit = findButton(x, y);
                this.selectedButton = hit;
                this.dragPointer = (hit >= 0) ? event.getPointerId(0) : -1;
                invalidate();
                return true; // Consume event completely in Edit Mode

            case MotionEvent.ACTION_MOVE:
                if (this.draggingDim) {
                    setDimFromX(x);
                    return true;
                }
                if (this.resizing && this.selectedButton >= 0 && this.dragPointer >= 0) {
                    int rIndex = event.findPointerIndex(this.dragPointer);
                    if (rIndex >= 0) {
                        applyResize(event.getX(rIndex), event.getY(rIndex));
                    }
                    return true;
                }
                if (this.selectedButton >= 0 && this.dragPointer >= 0) {
                    int pIndex = event.findPointerIndex(this.dragPointer);
                    if (pIndex >= 0) {
                        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
                        float availW = Math.max(1, getWidth() - safeLeft - safeRight);
                        float availH = Math.max(1, getHeight() - safeTop - safeBottom);
                        spec.x = TouchControlConfig.clamp((event.getX(pIndex) - safeLeft) / availW, 0.02f, 0.98f);
                        spec.y = TouchControlConfig.clamp((event.getY(pIndex) - safeTop) / availH, 0.02f, 0.98f);
                        rebuildHitRects();
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (this.draggingDim) {
                    this.draggingDim = false;
                    TouchControlConfig.setEditorDimPercent(getContext(), this.dimPercent);
                }
                this.dragPointer = -1;
                this.resizing = false;
                return true;

            default:
                return true;
        }
    }

    // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 The key sheet: what a button sends. Every key of the
    // launcher's editor (A-Z, 0-9, F1-F12, Space, Tab, Esc, Enter, Delete, arrows) as a grid of chips,
    // Ctrl / Alt / Shift as toggles, and the label (it follows the choice until the player types one).
    // The game keeps running behind it. A new button (Add) that is cancelled is removed again.
    private void showKeySheet(final int index, final boolean isNew) {
        if (index < 0 || index >= this.buttons.size()) {
            return;
        }
        final TouchControlConfig.ButtonSpec spec = this.buttons.get(index);
        if (getContext() instanceof android.app.Activity) {
            android.app.Activity act = (android.app.Activity) getContext();
            if (act.isFinishing() || act.isDestroyed()) {
                dropIfNew(spec, isNew);
                return;
            }
        }
        final Context ctx = new android.view.ContextThemeWrapper(getContext(),
            android.R.style.Theme_DeviceDefault_Dialog_Alert);
        final int[] codes = TouchControlConfig.keyCodes();
        final int[] chosen = { spec.keyCode };
        final int[] mods = { spec.modifiers };
        final boolean[] labelTouched = { !isNew && !spec.label.equals(TouchControlConfig.labelFor(spec.keyCode, spec.modifiers)) };
        // The sheet's own label updates are not the player typing (review: focus alone said so).
        final boolean[] settingLabel = { false };

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) dp(16.0f);
        root.setPadding(pad, (int) dp(8.0f), pad, 0);

        final android.widget.EditText label = new android.widget.EditText(ctx);
        label.setSingleLine(true);
        label.setHint(R.string.touch_key_label_hint);
        settingLabel[0] = true;
        label.setText(spec.label);
        settingLabel[0] = false;
        label.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(16) });

        android.widget.LinearLayout modRow = new android.widget.LinearLayout(ctx);
        modRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        final int[] modBits = { TouchControlConfig.MOD_CTRL, TouchControlConfig.MOD_ALT, TouchControlConfig.MOD_SHIFT };
        final int[] modNames = { R.string.touch_modifier_ctrl, R.string.touch_modifier_alt, R.string.touch_modifier_shift };
        final Runnable[] relabel = new Runnable[1];
        for (int i = 0; i < modBits.length; i++) {
            final int bit = modBits[i];
            android.widget.CheckBox box = new android.widget.CheckBox(ctx);
            box.setText(modNames[i]);
            box.setChecked((mods[0] & bit) != 0);
            box.setOnCheckedChangeListener((b, on) -> {
                mods[0] = on ? (mods[0] | bit) : (mods[0] & ~bit);
                relabel[0].run();
            });
            modRow.addView(box, new android.widget.LinearLayout.LayoutParams(0, -2, 1f));
        }
        root.addView(modRow);

        final android.widget.GridLayout grid = new android.widget.GridLayout(ctx);
        grid.setColumnCount(8);
        final java.util.List<android.widget.TextView> chips = new ArrayList<>();
        final Runnable[] paintChips = new Runnable[1];
        int chipSize = (int) dp(40.0f);
        for (int i = 0; i < codes.length; i++) {
            final int code = codes[i];
            android.widget.TextView chip = new android.widget.TextView(ctx);
            chip.setText(TouchControlConfig.displayNameForKey(code));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTextSize(12);
            chip.setSingleLine(true);
            chip.setOnClickListener(v -> {
                chosen[0] = code;
                paintChips[0].run();
                relabel[0].run();
            });
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = chipSize;
            lp.height = chipSize;
            lp.setMargins(2, 2, 2, 2);
            grid.addView(chip, lp);
            chips.add(chip);
        }
        paintChips[0] = () -> {
            for (int i = 0; i < chips.size(); i++) {
                boolean on = codes[i] == chosen[0];
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setCornerRadius(dp(6.0f));
                bg.setColor(on ? this.accentColor : 0xFF263238);
                chips.get(i).setBackground(bg);
                chips.get(i).setTextColor(on ? 0xFF000000 : 0xFFFFFFFF);
            }
        };
        relabel[0] = () -> {
            if (!labelTouched[0]) {
                settingLabel[0] = true;
                label.setText(TouchControlConfig.labelFor(chosen[0], mods[0]));
                settingLabel[0] = false;
            }
        };
        paintChips[0].run();
        android.widget.HorizontalScrollView gridScroll = new android.widget.HorizontalScrollView(ctx);
        gridScroll.addView(grid);
        android.widget.ScrollView vScroll = new android.widget.ScrollView(ctx);
        vScroll.addView(gridScroll);
        root.addView(vScroll, new android.widget.LinearLayout.LayoutParams(-1, (int) dp(180.0f)));
        root.addView(label);
        label.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) {
                if (!settingLabel[0]) labelTouched[0] = true;
            }
        });

        new android.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.touch_key_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                spec.keyCode = chosen[0];
                spec.modifiers = mods[0];
                String text = label.getText().toString().trim();
                spec.label = text.isEmpty() ? TouchControlConfig.labelFor(chosen[0], mods[0]) : text;
                rebuildHitRects();
                invalidate();
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> dropIfNew(spec, isNew))
            .setOnCancelListener(d -> dropIfNew(spec, isNew))
            .show();
    }

    /** A button added with Add whose key sheet was cancelled goes away again. */
    private void dropIfNew(TouchControlConfig.ButtonSpec spec, boolean isNew) {
        if (!isNew) return;
        int i = this.buttons.indexOf(spec);
        if (i >= 0) {
            this.buttons.remove(i);
            this.selectedButton = -1;
            this.resizeHandleRect.setEmpty();
            rebuildHitRects();
            invalidate();
        }
    }

    // GeneralsX @feature Flux Build 05/11/2026 Next fill colour of the palette for the selected button.
    private void cycleSelectedColor() {
        if (this.selectedButton < 0 || this.selectedButton >= this.buttons.size()) {
            toast(getContext().getString(R.string.touch_select_button_first));
            return;
        }
        TouchControlConfig.ButtonSpec spec = this.buttons.get(this.selectedButton);
        int current = spec.fillColor | 0xFF000000;
        int next = 0;
        for (int i = 0; i < FILL_PALETTE.length; i++) {
            if (FILL_PALETTE[i] == current) {
                next = (i + 1) % FILL_PALETTE.length;
                break;
            }
        }
        spec.fillColor = FILL_PALETTE[next];
        int luminance = (Color.red(spec.fillColor) * 299 + Color.green(spec.fillColor) * 587
            + Color.blue(spec.fillColor) * 114) / 1000;
        spec.textColor = luminance > 150 ? 0xFF000000 : 0xFFFFFFFF;
        invalidate();
    }

    /** Puts the layout being edited into the saved layout file (off the UI thread). */
    private void saveLayoutAsDefault() {
        TouchControlConfig toSave = this.config.createSnapshot();
        toSave.buttons.clear();
        toSave.buttons.addAll(TouchControlConfig.copyButtons(this.buttons));
        Context context = getContext().getApplicationContext();
        new Thread(() -> {
            String message;
            try {
                toSave.saveAsDefaultLayout(context);
                message = context.getString(R.string.touch_layout_saved_default);
            } catch (Exception e) {
                message = context.getString(R.string.touch_transfer_failed, String.valueOf(e.getMessage()));
            }
            final String text = message;
            post(() -> toast(text));
        }, "TouchLayoutSave").start();
    }

    /** Replaces the buttons being edited with the saved layout; ✔ then applies it. */
    private void loadSavedLayout() {
        Context context = getContext().getApplicationContext();
        new Thread(() -> {
            TouchControlConfig saved = null;
            String error = null;
            try {
                saved = TouchControlConfig.loadSavedLayout(context);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            final TouchControlConfig result = saved;
            final String failure = error;
            post(() -> {
                if (!this.isEditMode) {
                    return;
                }
                if (failure != null) {
                    toast(getContext().getString(R.string.touch_transfer_failed, failure));
                } else if (result == null) {
                    toast(getContext().getString(R.string.touch_layout_none));
                } else {
                    replaceEditButtons(result.buttons);
                    toast(getContext().getString(R.string.touch_layout_loaded));
                }
            });
        }, "TouchLayoutLoad").start();
    }

    private void replaceEditButtons(java.util.List<TouchControlConfig.ButtonSpec> source) {
        this.buttons.clear();
        this.buttons.addAll(TouchControlConfig.copyButtons(source));
        this.selectedButton = -1;
        rebuildHitRects();
        invalidate();
    }

    private void toast(String text) {
        android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void enterEditMode() {
        this.snapshotConfig = this.config.createSnapshot();
        this.dimPercent = TouchControlConfig.editorDimPercent(getContext());
        // GeneralsX @bugfix Flux Build 11/11/2026 review: no toggle carried into the editor (buttons may be
        // added or removed there, so the highlighted indices would go stale).
        flushPending(0);
        releaseToggles();
        this.isEditMode = true;
        this.selectedButton = -1;
        this.activeButton = -1;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // GeneralsX @feature Flux Build 07/11/2026 How the corner handle works, once a session.
        if (!sResizeHintShown) {
            sResizeHintShown = true;
            toast(getContext().getString(R.string.touch_editor_resize_help));
        }
        invalidate();
    }

    private void cancelEditMode() {
        if (this.snapshotConfig != null) {
            this.config.restoreFromSnapshot(this.snapshotConfig);
            this.buttons.clear();
            this.buttons.addAll(TouchControlConfig.copyButtons(this.config.buttons));
        }
        this.isEditMode = false;
        this.resizing = false;
        this.resizeHandleRect.setEmpty();
        this.selectedButton = -1;
        this.activeButton = -1;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        rebuildHitRects();
        invalidate();
    }

    private void saveEditMode() {
        this.config.buttons.clear();
        this.config.buttons.addAll(TouchControlConfig.copyButtons(this.buttons));
        // GeneralsX @bugfix Flux Build 11/11/2026 review: keys placed in the game's editor with the panel off
        // would vanish on Save -- saving keys there switches the panel on.
        if (!this.config.buttons.isEmpty()) {
            this.config.enabled = true;
        }
        this.config.save(getContext());

        this.isEditMode = false;
        this.resizing = false;
        this.resizeHandleRect.setEmpty();
        this.selectedButton = -1;
        this.activeButton = -1;
        this.snapshotConfig = null;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        rebuildHitRects();
        invalidate();
    }

    private void finishActivePress() {
        int hitIndex = this.activeButton;
        this.activeButton = -1;
        this.activePointer = -1;
        if (hitIndex < 0 || hitIndex >= this.buttons.size()) {
            return;
        }
        TouchControlConfig.ButtonSpec spec = this.buttons.get(hitIndex);

        if (isToggle(spec)) {
            flushPending(0); // an earlier key's timed release must not undo this toggle's hold
            int modMask = getButtonModifierMask(spec);
            if ((this.activeToggledModifiers & modMask) != 0) {
                // Turn toggle OFF
                this.activeToggledModifiers &= ~modMask;
                this.toggledButtonIndices.remove(hitIndex);
                sendModifierKeyUp(modMask, spec.keyCode);
            } else {
                // Turn toggle ON
                this.activeToggledModifiers |= modMask;
                this.toggledButtonIndices.add(hitIndex);
                sendModifierKeyDown(modMask, spec.keyCode);
            }
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else if (InGameHud.isClearGroupArmed() && spec.keyCode >= KeyEvent.KEYCODE_0 && spec.keyCode <= KeyEvent.KEYCODE_9) {
            // GeneralsX @feature Flux Build 11/11/2026 "G" (InGameHud) + a number: that group is emptied in the
            // engine (the group panel's clear); the number key is not sent, the selection stays. A latched
            // modifier is used up too (nothing was sent with it, so it is released at once).
            InGameHud.disarmClearGroup();
            try {
                GeneralsZHActivity.clearGroup(spec.keyCode - KeyEvent.KEYCODE_0);
            } catch (UnsatisfiedLinkError ignored) {
                // an older engine has no clear: nothing happens
            }
            releaseToggles();
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            // Normal Click: the key with its own modifiers and the latched toggles.
            // GeneralsX @bugfix Flux Build 11/11/2026 Toggles are one-shot: this key uses them up (owner: Ctrl
            // stayed stuck); sendKeyWithModifiers releases them a few frames later.
            sendKeyWithModifiers(spec.keyCode, spec.modifiers);
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
        invalidate();
        performClick();
    }

    private void forwardTouchToSDL(MotionEvent event, int index, int action) {
        int touchDevId = event.getDeviceId();
        int pointerId = event.getPointerId(index);
        float w = Math.max(1, getWidth() - 1);
        float h = Math.max(1, getHeight() - 1);
        float normX = Math.max(0.0f, Math.min(1.0f, event.getX(index) / w));
        float normY = Math.max(0.0f, Math.min(1.0f, event.getY(index) / h));
        float pressure = Math.min(1.0f, event.getPressure(index));
        SDLActivity.onNativeTouch(touchDevId, pointerId, action, normX, normY, pressure);
    }

    private void sendModifierKeyDown(int modMask, int keyCode) {
        if (keyCode != 0) {
            SDLActivity.onNativeKeyDown(keyCode);
        }
        if ((modMask & TouchControlConfig.MOD_CTRL) != 0 && keyCode != KeyEvent.KEYCODE_CTRL_LEFT) {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_CTRL_LEFT);
        }
        if ((modMask & TouchControlConfig.MOD_SHIFT) != 0 && keyCode != KeyEvent.KEYCODE_SHIFT_LEFT) {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_SHIFT_LEFT);
        }
        if ((modMask & TouchControlConfig.MOD_ALT) != 0 && keyCode != KeyEvent.KEYCODE_ALT_LEFT) {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ALT_LEFT);
        }
    }

    private void sendModifierKeyUp(int modMask, int keyCode) {
        if (keyCode != 0) {
            SDLActivity.onNativeKeyUp(keyCode);
        }
        if ((modMask & TouchControlConfig.MOD_CTRL) != 0) {
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_CTRL_LEFT);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_CTRL_RIGHT);
        }
        if ((modMask & TouchControlConfig.MOD_SHIFT) != 0) {
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_SHIFT_RIGHT);
        }
        if ((modMask & TouchControlConfig.MOD_ALT) != 0) {
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ALT_LEFT);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ALT_RIGHT);
        }
    }

    private int modifierCodeToMask(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return TouchControlConfig.MOD_CTRL;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return TouchControlConfig.MOD_SHIFT;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return TouchControlConfig.MOD_ALT;
            default:
                return 0;
        }
    }

    // GeneralsX @bugfix Flux Build 11/11/2026 The key goes down and up at once; its modifiers (its own and the
    // latched toggles) are held until MODIFIER_RELEASE_MS later, so the frame that reads
    // the key ends with them still down (see pendingUpMods). A key that comes before then releases what it
    // does not need first and keeps the rest held.
    private void sendKeyWithModifiers(int keyCode, int modifiersMask) {
        java.util.ArrayList<Integer> toggleKeys = new java.util.ArrayList<>();
        int needed = (modifiersMask | takeToggles(toggleKeys)) & 7;
        flushPending(needed);
        for (int modifier : modifierKeyCodes(needed)) {
            SDLActivity.onNativeKeyDown(modifier);
        }
        SDLActivity.onNativeKeyDown(keyCode);
        SDLActivity.onNativeKeyUp(keyCode);
        if (needed != 0 || !toggleKeys.isEmpty()) {
            this.pendingUpMods |= needed;
            this.pendingUpKeys.addAll(toggleKeys);
            this.handler.postDelayed(this.releasePending, MODIFIER_RELEASE_MS);
            android.util.Log.i("GeneralsZH", "[GX-KEYS] key " + keyCode + " mods " + needed
                + " (Ctrl 1, Shift 2, Alt 4), released in " + MODIFIER_RELEASE_MS + " ms");
        }
    }

    private int getButtonModifierMask(TouchControlConfig.ButtonSpec spec) {
        if (spec.modifiers != 0) {
            return spec.modifiers;
        }
        switch (spec.keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return TouchControlConfig.MOD_CTRL;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return TouchControlConfig.MOD_SHIFT;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return TouchControlConfig.MOD_ALT;
            default:
                return TouchControlConfig.MOD_CTRL;
        }
    }

    private int[] modifierKeyCodes(int mask) {
        int count = Integer.bitCount(mask & 7);
        int[] result = new int[count];
        int i = 0;
        if ((mask & TouchControlConfig.MOD_CTRL) != 0) {
            result[i++] = KeyEvent.KEYCODE_CTRL_LEFT;
        }
        if ((mask & TouchControlConfig.MOD_SHIFT) != 0) {
            result[i++] = KeyEvent.KEYCODE_SHIFT_LEFT;
        }
        if ((mask & TouchControlConfig.MOD_ALT) != 0) {
            result[i++] = KeyEvent.KEYCODE_ALT_LEFT;
        }
        return result;
    }

    private void drawShape(Canvas canvas, RectF rect, TouchControlConfig.ButtonSpec spec, Paint paint) {
        switch (spec.shape) {
            case TouchControlConfig.SHAPE_RECTANGLE:
                canvas.drawRect(rect, paint);
                break;

            case TouchControlConfig.SHAPE_ROUNDED:
            default:
                float radius = Math.min(dp(12.0f) * spec.scale, Math.min(rect.width(), rect.height()) * 0.25f);
                canvas.drawRoundRect(rect, radius, radius, paint);
                break;

            case TouchControlConfig.SHAPE_SQUARE:
                float side = Math.min(rect.width(), rect.height());
                RectF square = new RectF(
                    rect.centerX() - (side * 0.5f),
                    rect.centerY() - (side * 0.5f),
                    rect.centerX() + (side * 0.5f),
                    rect.centerY() + (side * 0.5f)
                );
                canvas.drawRect(square, paint);
                break;

            case TouchControlConfig.SHAPE_CIRCLE:
                canvas.drawCircle(rect.centerX(), rect.centerY(), Math.min(rect.width(), rect.height()) * 0.5f, paint);
                break;

            case TouchControlConfig.SHAPE_OVAL:
                canvas.drawOval(rect, paint);
                break;
        }
    }

    private int lighten(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.argb(
            Color.alpha(color),
            r + Math.round((255 - r) * amount),
            g + Math.round((255 - g) * amount),
            b + Math.round((255 - b) * amount)
        );
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private float dp(float value) {
        return getResources().getDisplayMetrics().density * value;
    }
}
