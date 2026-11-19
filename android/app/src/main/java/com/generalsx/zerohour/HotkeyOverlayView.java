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
        if (!config.enabled || config.buttons.isEmpty()) {
            android.util.Log.w("GeneralsZH", "HotkeyOverlayView not attached: enabled=" + config.enabled + ", buttons=" + config.buttons.size());
            return;
        }
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
        // Strictly release all active modifiers (CTRL, SHIFT, ALT) to native engine
        sendModifierKeyUp(TouchControlConfig.MOD_CTRL | TouchControlConfig.MOD_SHIFT | TouchControlConfig.MOD_ALT, 0);
        this.activeToggledModifiers = 0;
        this.toggledButtonIndices.clear();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
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

        // 1. Draw In-Game Edit Mode Grid
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

        // 2. Draw Floating Hotkey Buttons (using individual scale & opacity)
        for (int i = 0; i < this.buttons.size(); i++) {
            RectF rect = this.hitRects.get(i);
            TouchControlConfig.ButtonSpec spec = this.buttons.get(i);
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
            if (spec.actionType == TouchControlConfig.ACTION_TOGGLE) {
                this.toolbarTextPaint.setTextSize(dp(8.0f));
                this.toolbarTextPaint.setColor(isToggled ? 0xFF00E5FF : 0xBBFFFFFF);
                canvas.drawText("T", rect.right - dp(7.0f), rect.top + dp(10.0f), this.toolbarTextPaint);
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
        if (this.selectedButton >= 0 && this.selectedButton < this.buttons.size()) {
            TouchControlConfig.ButtonSpec sel = this.buttons.get(this.selectedButton);
            sizeText = String.format("%s Size %d%%", sel.label, Math.round(sel.scale * 100));
            alphaText = String.format("%s Alpha %d%%", sel.label, Math.round(sel.opacity * 100));
        } else {
            sizeText = "Tap button";
            alphaText = "to resize";
        }
        canvas.drawText(sizeText, sMidX, sMidY, this.toolbarTextPaint);

        // Opacity Control
        drawToolbarButton(canvas, this.alphaMinusRect, "-", 0xFF37474F);
        drawToolbarButton(canvas, this.alphaPlusRect, "+", 0xFF37474F);
        float aMidX = (this.alphaMinusRect.right + this.alphaPlusRect.left) * 0.5f;
        canvas.drawText(alphaText, aMidX, sMidY, this.toolbarTextPaint);

        // Cancel (X) & Save (✔)
        drawToolbarButton(canvas, this.cancelRect, "✕", 0xFFC62828); // Red
        drawToolbarButton(canvas, this.saveRect, "✔", 0xFF2E7D32);   // Green
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
        if (this.hitRects.size() != this.buttons.size()) {
            rebuildHitRects();
        }
        for (int i = this.hitRects.size() - 1; i >= 0; i--) {
            if (this.hitRects.get(i).contains(x, y)) {
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
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                sendKeyWithModifiers(KeyEvent.KEYCODE_ENTER, 0);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                performClick();
            }
            return true;
        }

        // 1. Gear Button Interception (Always active)
        if (this.gearRect.contains(x, y)) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (!this.isEditMode) {
                    enterEditMode();
                } else {
                    cancelEditMode();
                }
                performClick();
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
                if (this.saveRect.contains(x, y)) {
                    saveEditMode();
                    return true;
                }

                // Check Button Selection for Dragging
                int hit = findButton(x, y);
                this.selectedButton = hit;
                this.dragPointer = (hit >= 0) ? event.getPointerId(0) : -1;
                invalidate();
                return true; // Consume event completely in Edit Mode

            case MotionEvent.ACTION_MOVE:
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
                this.dragPointer = -1;
                return true;

            default:
                return true;
        }
    }

    private void enterEditMode() {
        this.snapshotConfig = this.config.createSnapshot();
        this.isEditMode = true;
        this.selectedButton = -1;
        this.activeButton = -1;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private void cancelEditMode() {
        if (this.snapshotConfig != null) {
            this.config.restoreFromSnapshot(this.snapshotConfig);
            this.buttons.clear();
            this.buttons.addAll(TouchControlConfig.copyButtons(this.config.buttons));
        }
        this.isEditMode = false;
        this.selectedButton = -1;
        this.activeButton = -1;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        rebuildHitRects();
        invalidate();
    }

    private void saveEditMode() {
        this.config.buttons.clear();
        this.config.buttons.addAll(TouchControlConfig.copyButtons(this.buttons));
        this.config.save(getContext());

        this.isEditMode = false;
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

        if (spec.actionType == TouchControlConfig.ACTION_TOGGLE) {
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
        } else {
            // Normal Click: send key with any currently active toggled modifiers combined
            int combinedMods = spec.modifiers | this.activeToggledModifiers;
            sendKeyWithModifiers(spec.keyCode, combinedMods);
            // Persistent latching: do NOT auto-reset modifiers, keep them active across clicks
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

    private void sendKeyWithModifiers(int keyCode, int modifiersMask) {
        final int[] modifiers = modifierKeyCodes(modifiersMask);
        for (int modifier : modifiers) {
            SDLActivity.onNativeKeyDown(modifier);
        }
        SDLActivity.onNativeKeyDown(keyCode);
        SDLActivity.onNativeKeyUp(keyCode);
        for (int i = modifiers.length - 1; i >= 0; i--) {
            int modCode = modifiers[i];
            int mask = modifierCodeToMask(modCode);
            // Release modifier only if it's not currently latched by a toggle button
            if ((this.activeToggledModifiers & mask) == 0) {
                SDLActivity.onNativeKeyUp(modCode);
            }
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
