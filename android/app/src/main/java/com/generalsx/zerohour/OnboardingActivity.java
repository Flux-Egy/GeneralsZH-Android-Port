// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 13/11/2026 v1.4.5 12195 The first-launch tour: six short slides (ViewPager2), a big
// icon each, one or two lines, Skip on every slide, Next / "Let's play!". Shown once on a fresh install (no game
// folder, nothing seen before) instead of "What's new"; again from Help -> "Take the tour again". Built in code,
// like the rest of the launcher (UiKit); the icons are the launcher's own vector drawables.
package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

public class OnboardingActivity extends Activity {
    static final String PREF_DONE = "gx_onboarding_done";

    /** Each slide: icon, title, body. */
    private static final int[][] SLIDES = {
        { R.drawable.ic_gzh_play, R.string.onboarding_1_title, R.string.onboarding_1_body },
        { R.drawable.ic_gzh_home, R.string.onboarding_2_title, R.string.onboarding_2_body },
        { R.drawable.ic_gzh_sliders, R.string.onboarding_3_title, R.string.onboarding_3_body },
        { R.drawable.ic_gzh_display, R.string.onboarding_4_title, R.string.onboarding_4_body },
        { R.drawable.ic_gzh_folder, R.string.onboarding_5_title, R.string.onboarding_5_body },
        { R.drawable.ic_gzh_check, R.string.onboarding_6_title, R.string.onboarding_6_body },
    };

    /**
     * A fresh install: the tour was never finished, no game folder was ever chosen and no "What's new" was ever
     * seen (an update from an older build has those, and gets "What's new" instead).
     */
    static boolean shouldShow(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean(PREF_DONE, false)) return false;
        // Review 12195: an older install that plays from the app's own GameData folder is not fresh.
        if (SetupActivity.isGameFolderReady(ctx)) return false;
        if (prefs.getString(WhatsNew.PREF_SEEN, null) != null) return false;
        return SetupActivity.getSavedGamePath(ctx, SetupActivity.GAME_ZERO_HOUR) == null
            && SetupActivity.getSavedGamePath(ctx, SetupActivity.GAME_GENERALS) == null;
    }

    private ViewPager2 pager;
    private LinearLayout dots;
    private com.google.android.material.button.MaterialButton next;

    @Override
    protected void attachBaseContext(Context newBase) {
        // The launcher's own language (review 12195), as every launcher screen.
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        int pad = UiKit.dp(this, 20);
        root.setPadding(pad, pad, pad, pad);

        pager = new ViewPager2(this);
        pager.setId(R.id.gzh_onboarding_pager); // a fixed id: the current slide survives a recreate
        pager.setAdapter(new SlideAdapter());
        root.addView(pager, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < SLIDES.length; i++) {
            View dot = new View(this);
            int d = UiKit.dp(this, 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d, d);
            lp.setMargins(UiKit.dp(this, 4), 0, UiKit.dp(this, 4), 0);
            dots.addView(dot, lp);
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.bottomMargin = UiKit.dp(this, 16);
        root.addView(dots, dotsLp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        com.google.android.material.button.MaterialButton skip = new com.google.android.material.button.MaterialButton(
            this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        skip.setText(R.string.onboarding_skip);
        skip.setAllCaps(false);
        skip.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        skip.setOnClickListener(v -> finishTour());
        bar.addView(skip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        next = new com.google.android.material.button.MaterialButton(this);
        next.setAllCaps(false);
        next.setTypeface(Typeface.DEFAULT_BOLD);
        next.setBackgroundTintList(UiKit.tint(this, R.color.gzh_primary));
        next.setTextColor(UiKit.color(this, R.color.gzh_on_primary));
        next.setCornerRadius(UiKit.dp(this, 22));
        next.setOnClickListener(v -> {
            int at = pager.getCurrentItem();
            if (at >= SLIDES.length - 1) {
                finishTour();
            } else {
                pager.setCurrentItem(at + 1, true);
            }
        });
        bar.addView(next, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 48)));
        root.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                update(position);
            }
        });
        setContentView(root);
        InsetUtil.applySafeInsets(root); // edge to edge on Android 15: clear the bars (review 12195)
        if (getActionBar() != null) getActionBar().hide();
        update(0);
    }

    private void update(int position) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(UiKit.color(this, i == position ? R.color.gzh_primary : R.color.gzh_outline));
            dots.getChildAt(i).setBackground(g);
        }
        next.setText(position >= SLIDES.length - 1 ? R.string.onboarding_lets_play : R.string.onboarding_next);
    }

    /** Skip, "Let's play!" and Back all end the tour for good; "What's new" for this release counts as seen. */
    private void finishTour() {
        getSharedPreferences(SetupActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_DONE, true)
            .putString(WhatsNew.PREF_SEEN, WhatsNew.RELEASE_LINE)
            .apply();
        finish();
    }

    @Override
    public void onBackPressed() {
        finishTour();
    }

    private final class SlideAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context c = parent.getContext();
            // Review 12195: scrollable, so a landscape phone or a large font never cuts a slide off.
            android.widget.ScrollView scroll = new android.widget.ScrollView(c);
            scroll.setFillViewport(true);
            scroll.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout page = new LinearLayout(c);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setGravity(Gravity.CENTER);
            scroll.addView(page, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            int side = UiKit.dp(c, 12);
            page.setPadding(side, 0, side, 0);

            // A big icon in a gold-ringed circle.
            FrameLayout circle = new FrameLayout(c);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(UiKit.color(c, R.color.gzh_surface_container_high));
            ring.setStroke(UiKit.dp(c, 3), UiKit.color(c, R.color.gzh_primary));
            circle.setBackground(ring);
            ImageView icon = new ImageView(c);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // decoration; the title says it
            icon.setImageTintList(UiKit.tint(c, R.color.gzh_primary));
            int iconSize = UiKit.dp(c, 56);
            circle.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
            int size = UiKit.dp(c, 120);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(size, size);
            clp.bottomMargin = UiKit.dp(c, 28);
            page.addView(circle, clp);

            TextView title = new TextView(c);
            title.setGravity(Gravity.CENTER);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(UiKit.color(c, R.color.gzh_on_surface));
            page.addView(title);

            TextView body = new TextView(c);
            body.setGravity(Gravity.CENTER);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            body.setTextColor(UiKit.color(c, R.color.gzh_on_surface_variant));
            body.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = UiKit.dp(c, 12);
            page.addView(body, blp);

            // A small "swipe on" cue (a chevron), hidden on the last slide.
            ImageView cue = new ImageView(c);
            cue.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cue.setImageResource(R.drawable.ic_gzh_chevron);
            cue.setImageTintList(UiKit.tint(c, R.color.gzh_on_surface_variant));
            cue.setAlpha(0.6f);
            if (c.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                cue.setScaleX(-1f);
            }
            int cueSize = UiKit.dp(c, 28);
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(cueSize, cueSize);
            qlp.topMargin = UiKit.dp(c, 24);
            page.addView(cue, qlp);

            return new RecyclerView.ViewHolder(scroll) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            LinearLayout page = (LinearLayout) ((android.widget.ScrollView) holder.itemView).getChildAt(0);
            Context c = page.getContext();
            int[] slide = SLIDES[position];
            ImageView icon = (ImageView) ((FrameLayout) page.getChildAt(0)).getChildAt(0);
            icon.setImageResource(slide[0]);
            ((TextView) page.getChildAt(1)).setText(slide[1]);
            String body = c.getString(slide[2]);
            if (position == SLIDES.length - 1 && SetupActivity.isGameFolderReady(c)) {
                body = c.getString(R.string.onboarding_6_body_ready);
            }
            ((TextView) page.getChildAt(2)).setText(body);
            page.getChildAt(3).setVisibility(position == SLIDES.length - 1 ? View.INVISIBLE : View.VISIBLE);
        }

        @Override
        public int getItemCount() {
            return SLIDES.length;
        }
    }
}
