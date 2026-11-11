// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 11/11/2026 The fixed buttons drawn over the game (InGameHud): "i" (Info) and
// "G" (Clear Group). Always on (owner), shown even when the custom touch-button panel is off, moved by a
// long press in the game or in the launcher's touch editor. Ctrl / Shift / Alt are ordinary keys of the
// touch-button panel (TouchControlConfig), not fixed buttons. One place for their ids, labels, positions
// and defaults, so the game and the editor agree on where a button is.
package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

final class HudButtons {
    private HudButtons() {}

    static final int INFO = 0;
    static final int CLEAR_GROUP = 1;
    static final int COUNT = 2;

    static final String[] LABELS = { "i", "G" };

    // "i" keeps its position preferences from before (gx_info_x / gx_info_y).
    private static final String[] PREF_X = { "gx_info_x", "gx_hud_clear_group_x" };
    private static final String[] PREF_Y = { "gx_info_y", "gx_hud_clear_group_y" };

    static final int SIZE_DP = 44;

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Saved top-left position as a fraction of the screen, or null when the default applies. */
    static float[] savedPosition(Context ctx, int id) {
        SharedPreferences p = prefs(ctx);
        float fx = p.getFloat(PREF_X[id], -1f), fy = p.getFloat(PREF_Y[id], -1f);
        return fx >= 0f && fy >= 0f ? new float[] { fx, fy } : null;
    }

    static void savePosition(Context ctx, int id, float fx, float fy) {
        prefs(ctx).edit().putFloat(PREF_X[id], clamp01(fx)).putFloat(PREF_Y[id], clamp01(fy)).apply();
    }

    static void clearPosition(Context ctx, int id) {
        prefs(ctx).edit().remove(PREF_X[id]).remove(PREF_Y[id]).apply();
    }

    /**
     * Default top-left, in pixels of a screen w x h: a column under the touch editor's gear (whose centre is
     * 40 dp from the right, 32 dp from the top) -- "i" first, "G" under it -- clear of the gear, the chat
     * button and the command bar at the bottom.
     */
    static float[] defaultPosition(int id, float w, float h, float density, float insetRight, float insetTop) {
        float size = SIZE_DP * density, step = (SIZE_DP + 8) * density;
        float col = w - insetRight - 40f * density - size * 0.5f;
        float x = Math.max(0f, Math.min(col, w - size));
        float y = Math.max(0f, Math.min(insetTop + 56f * density + id * step, h - size));
        return new float[] { x, y };
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
