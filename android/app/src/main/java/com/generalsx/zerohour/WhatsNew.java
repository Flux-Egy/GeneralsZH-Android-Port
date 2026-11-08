// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 "What's new": the highlights of a release line, once after
// the update (owner's request), and again from Help -> What's new. Shown per release line
// (RELEASE_LINE), not per test build, so testers see it once for 1.3.9 rather than after every test
// APK. When the highlights change, change RELEASE_LINE with them and every whats_new_* text in the
// 13 locales.
package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class WhatsNew {
    private WhatsNew() {}

    static final String RELEASE_LINE = "1.4.1";
    private static final String PREF_SEEN = "gx_whats_new_seen";

    /** Each highlight: icon, title, body. */
    private static final int[][] ITEMS = {
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1: World Builder and the new Maps & Mods section.
        { R.drawable.ic_gzh_wrench, R.string.whats_new_wb_title, R.string.whats_new_wb_body },
        { R.drawable.ic_gzh_globe, R.string.whats_new_online_title, R.string.whats_new_online_body },
        { R.drawable.ic_gzh_play, R.string.whats_new_overlay_title, R.string.whats_new_overlay_body },
        { R.drawable.ic_gzh_chip, R.string.whats_new_smooth_title, R.string.whats_new_smooth_body },
        { R.drawable.ic_gzh_info, R.string.whats_new_info_title, R.string.whats_new_info_body },
        { R.drawable.ic_gzh_shield, R.string.whats_new_guard_title, R.string.whats_new_guard_body },
        { R.drawable.ic_gzh_sliders, R.string.whats_new_more_title, R.string.whats_new_more_body },
    };

    /** Once per release line; returns whether it was shown. */
    static boolean maybeShow(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (RELEASE_LINE.equals(prefs.getString(PREF_SEEN, null))) {
            return false;
        }
        show(activity);
        return true;
    }

    static void show(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final SharedPreferences prefs = activity.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(UiKit.color(activity, R.color.gzh_background));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(activity, 20);
        content.setPadding(pad, UiKit.dp(activity, 8), pad, UiKit.dp(activity, 8));
        scroll.addView(content);

        UiKit.supporting(content, activity.getString(R.string.whats_new_intro));
        for (int[] item : ITEMS) {
            addItem(activity, content, item[0], item[1], item[2]);
        }

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.whats_new_title, RELEASE_LINE))
            .setIcon(R.drawable.ic_gzh_heart)
            .setView(scroll)
            .setPositiveButton(R.string.whats_new_button, null)
            .setOnDismissListener(d -> prefs.edit().putString(PREF_SEEN, RELEASE_LINE).apply())
            .show();
    }

    private static void addItem(Context ctx, LinearLayout parent, int icon, int title, int body) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = UiKit.dp(ctx, 14);
        parent.addView(row, rowLp);

        ImageView image = new ImageView(ctx);
        image.setImageResource(icon);
        image.setImageTintList(UiKit.tint(ctx, R.color.gzh_primary));
        int size = UiKit.dp(ctx, 28);
        LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(size, size);
        imageLp.setMarginEnd(UiKit.dp(ctx, 14));
        imageLp.topMargin = UiKit.dp(ctx, 2);
        row.addView(image, imageLp);

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(UiKit.color(ctx, R.color.gzh_on_surface));
        text.addView(t);

        TextView b = new TextView(ctx);
        b.setText(body);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(UiKit.color(ctx, R.color.gzh_on_surface_variant));
        b.setLineSpacing(0, 1.2f);
        text.addView(b);
    }
}
