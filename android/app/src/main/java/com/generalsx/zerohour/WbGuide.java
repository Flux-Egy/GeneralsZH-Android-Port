// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 v1.4.1: the World Builder guide. Short pages, one topic each,
// with the tool bar icon the page is about; Back/Next and dots, a slide between pages. Opened from
// the "?" button, and once by itself the first time World Builder starts.
package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

final class WbGuide {
    private WbGuide() {}

    private static final String PREF_SEEN = "wb_guide_seen";

    /** Each page: icon, title, text. */
    private static final int[][] PAGES = {
        { R.drawable.ic_wb_hand, R.string.wb_guide_1_title, R.string.wb_guide_1_body },
        { R.drawable.ic_wb_raise, R.string.wb_guide_2_title, R.string.wb_guide_2_body },
        { R.drawable.ic_wb_object, R.string.wb_guide_3_title, R.string.wb_guide_3_body },
        { R.drawable.ic_wb_waypoint, R.string.wb_guide_4_title, R.string.wb_guide_4_body },
        { R.drawable.ic_wb_area, R.string.wb_guide_5_title, R.string.wb_guide_5_body },
        { R.drawable.ic_wb_script, R.string.wb_guide_6_title, R.string.wb_guide_6_body },
        { R.drawable.ic_gzh_save, R.string.wb_guide_7_title, R.string.wb_guide_7_body },
    };

    static void maybeShowFirstTime(Activity a) {
        if (a.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_SEEN, false)) {
            return;
        }
        a.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_SEEN, true).apply();
        show(a);
    }

    static void show(Activity a) {
        if (a.isFinishing() || a.isDestroyed()) return;
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(a, 22);
        root.setPadding(pad, UiKit.dp(a, 18), pad, UiKit.dp(a, 8));

        final ImageView icon = new ImageView(a);
        icon.setImageTintList(UiKit.tint(a, R.color.gzh_primary));
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor(UiKit.withAlpha(UiKit.color(a, R.color.gzh_primary), 0x26));
        icon.setBackground(halo);
        int ip = UiKit.dp(a, 14);
        icon.setPadding(ip, ip, ip, ip);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(UiKit.dp(a, 64), UiKit.dp(a, 64));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(icon, ilp);

        final TextView title = new TextView(a);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(a, R.color.gzh_on_surface));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, UiKit.dp(a, 12), 0, UiKit.dp(a, 6));
        root.addView(title);

        final TextView body = new TextView(a);
        body.setTextSize(14);
        body.setLineSpacing(0, 1.25f);
        body.setTextColor(UiKit.color(a, R.color.gzh_on_surface_variant));
        body.setMinHeight(UiKit.dp(a, 120));
        root.addView(body);

        final LinearLayout dots = new LinearLayout(a);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        dots.setPadding(0, UiKit.dp(a, 10), 0, UiKit.dp(a, 6));
        for (int i = 0; i < PAGES.length; i++) {
            View d = new View(a);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(UiKit.dp(a, 8), UiKit.dp(a, 8));
            dlp.setMargins(UiKit.dp(a, 3), 0, UiKit.dp(a, 3), 0);
            dots.addView(d, dlp);
        }
        root.addView(dots);

        LinearLayout buttons = new LinearLayout(a);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final MaterialButton back = new MaterialButton(a, null, com.google.android.material.R.attr.borderlessButtonStyle);
        back.setText(R.string.wb_guide_back);
        final MaterialButton next = new MaterialButton(a);
        View spacer = new View(a);
        buttons.addView(back);
        buttons.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        buttons.addView(next);
        root.addView(buttons);

        final AlertDialog dialog = new MaterialAlertDialogBuilder(a).setView(root).create();
        final int[] page = { 0 };
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            int[] p = PAGES[page[0]];
            icon.setImageResource(p[0]);
            title.setText(p[1]);
            body.setText(p[2]);
            for (int i = 0; i < dots.getChildCount(); i++) {
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(i == page[0] ? UiKit.color(a, R.color.gzh_primary)
                    : UiKit.withAlpha(UiKit.color(a, R.color.gzh_on_surface_variant), 0x55));
                dots.getChildAt(i).setBackground(g);
            }
            back.setVisibility(page[0] == 0 ? View.INVISIBLE : View.VISIBLE);
            next.setText(page[0] == PAGES.length - 1 ? R.string.wb_guide_done : R.string.wb_guide_next);
        };
        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 review: one page turn at a time; two quick taps
        // both passed the bounds check before the delayed turn ran and went past the last page.
        final boolean[] turning = { false };
        final java.util.function.IntConsumer go = dir -> {
            if (turning[0]) return;
            final int target = page[0] + dir;
            if (target < 0 || target >= PAGES.length) return;
            turning[0] = true;
            float dx = UiKit.dp(a, 24) * dir;
            View[] moving = { icon, title, body };
            for (View v : moving) v.animate().alpha(0f).translationX(-dx).setDuration(110).start();
            body.postDelayed(() -> {
                page[0] = target;
                turning[0] = false;
                render[0].run();
                for (View v : moving) {
                    v.setTranslationX(dx);
                    v.animate().alpha(1f).translationX(0f).setDuration(170).start();
                }
            }, 115);
        };
        back.setOnClickListener(v -> { if (page[0] > 0) go.accept(-1); });
        next.setOnClickListener(v -> {
            if (page[0] < PAGES.length - 1) go.accept(1); else dialog.dismiss();
        });
        render[0].run();
        dialog.show();
    }
}
