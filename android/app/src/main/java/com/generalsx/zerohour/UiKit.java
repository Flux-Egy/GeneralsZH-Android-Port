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

// GeneralsX @feature Android port launcher-ui-2026 08/09/2026
//
// One place that knows what the launcher looks like.
//
// Every launcher screen is still built in code rather than inflated from
// layout XML -- that predates this file and is deliberate: the screens are
// conditional (the Vulkan-only cards, the base-Generals button, six
// diagnostic toggles generated from an array) and a layout file per
// permutation would be more machinery, not less. What was actually wrong
// was that each screen re-derived its own paddings, radii, text sizes and
// colours from scratch, so "16dp here, 12dp there, 22sp title, 0.7 alpha"
// drifted independently in four files and read as four different apps.
//
// So the widgets stay in code and the STYLE moves here: a card is whatever
// card() says it is, a primary action is whatever button(BTN_PRIMARY) says
// it is, and the numbers all come from res/values/dimens.xml + the M3
// colour roles in res/values/colors.xml. Nothing below is screen-specific.
//
// Everything here is API 24-safe (see the minSdk note in build.gradle) and
// start/end-relative rather than left/right, so the Arabic and Farsi
// translations mirror correctly.

package com.generalsx.zerohour;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

final class UiKit {

    private UiKit() {}

    // ---------------------------------------------------------------- tokens

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static int dim(Context c, int dimenRes) {
        return c.getResources().getDimensionPixelSize(dimenRes);
    }

    // GeneralsX @feature Android port 18/10/2026 Under the palette chosen in Appearance (ThemeHelper).
    // GeneralsX @bugfix Flux Build v1.1.2 23/10/2026 mapColor() returns resolved ARGB now; passing
    // its result back to getColor() is what crashed on the first custom swatch.
    static int color(Context c, int colorRes) {
        return ThemeHelper.mapColor(c, colorRes);
    }

    /** Conversational shorthand for "this colour at this alpha". */
    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    static ColorStateList tint(Context c, int colorRes) {
        return ColorStateList.valueOf(color(c, colorRes));
    }

    /** enabled/disabled pair, the shape every Material tint slot wants. */
    static ColorStateList tint(Context c, int enabledRes, int disabledRes) {
        return new ColorStateList(
            new int[][] { new int[] { -android.R.attr.state_enabled }, new int[0] },
            new int[] { color(c, disabledRes), color(c, enabledRes) });
    }

    /** checked/unchecked pair, for segmented buttons. */
    private static ColorStateList checkedTint(Context c, int checkedRes, int uncheckedRes) {
        return new ColorStateList(
            new int[][] { new int[] { android.R.attr.state_checked }, new int[0] },
            new int[] { color(c, checkedRes), color(c, uncheckedRes) });
    }

    // ------------------------------------------------------------ page shell

    /**
     * The scrolling body of a page: a vertical column of cards, gutter-padded,
     * inside a ScrollView. Returns the column to add cards to; the ScrollView
     * is already attached to {@code host}.
     */
    static LinearLayout scrollingPage(ViewGroup host) {
        Context c = host.getContext();
        ScrollView scroll = new ScrollView(c);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        int gutter = dim(c, R.dimen.gzh_gutter);
        column.setPadding(gutter, dp(c, 4), gutter, dim(c, R.dimen.gzh_card_gap));
        scroll.addView(column, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.addView(scroll, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return column;
    }

    /**
     * GeneralsX @feature Flux Build 26/10/2026 Material 3 section transition: a short fade
     * combined with a 10dp slide from the direction of travel. It is deliberately quieter than
     * a full shared-axis transition -- this is a settings page, not a hero screen -- and it
     * honours the system "remove animations" setting and the RTL layout direction.
     */
    static void animatePageIn(View page, boolean forward) {
        if (page == null) {
            return;
        }
        boolean reduced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !ValueAnimator.areAnimatorsEnabled();
        if (!reduced && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            reduced = Settings.Global.getFloat(page.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        }
        if (reduced) {
            page.setAlpha(1f);
            page.setTranslationX(0f);
            return;
        }
        float direction = forward ? 1f : -1f;
        if (page.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            direction = -direction;
        }
        page.setAlpha(0f);
        page.setTranslationX(dp(page.getContext(), 10) * direction);
        page.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(220L)
            .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f))
            .start();
        // GeneralsX @tweak Flux Build 07/11/2026 UI polish: the first cards follow one after another
        // (a 30 ms step, the first eight), so a new tab settles in rather than appearing at once.
        ViewGroup cards = firstColumn(page);
        if (cards != null) {
            final int n = Math.min(cards.getChildCount(), 8);
            for (int i = 0; i < n; i++) {
                View card = cards.getChildAt(i);
                card.setAlpha(0f);
                card.setTranslationY(dp(page.getContext(), 6));
                card.animate().alpha(1f).translationY(0f)
                    .setStartDelay(40L + 30L * i).setDuration(200L)
                    .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            }
        }
    }

    /**
     * GeneralsX @tweak Flux Build 07/11/2026 v1.4.1 polish: a pressed row sinks a little (2%) and comes
     * back on release -- with the ripple, a tap is felt at once. Skipped when animations are off.
     */
    static void pressFeedback(final View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90L).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                    break;
                default:
                    break;
            }
            return false; // the click, ripple and accessibility stay the view's own
        });
    }

    /** GeneralsX @tweak Flux Build 07/11/2026 v1.4.1 polish: a title that changes fades over. */
    static void crossfadeText(final TextView t, final CharSequence text) {
        if (t == null) return;
        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 review: a quick second switch must win; the
        // pending end action of the first checks that it is still the latest text before applying.
        t.setTag(R.id.gzh_crossfade_text, text);
        if (text.toString().contentEquals(t.getText())
                || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())) {
            t.animate().cancel();
            t.setAlpha(1f);
            t.setText(text);
            return;
        }
        t.animate().cancel();
        t.animate().alpha(0f).setDuration(90L).withEndAction(() -> {
            if (t.getTag(R.id.gzh_crossfade_text) != text) return;
            t.setText(text);
            t.animate().alpha(1f).setDuration(160L).start();
        }).start();
    }

    /** The vertical list of cards inside a page (the page itself, or the column inside its scroll view). */
    private static ViewGroup firstColumn(View page) {
        View v = page;
        for (int depth = 0; depth < 3 && v instanceof ViewGroup; depth++) {
            ViewGroup g = (ViewGroup) v;
            if (g instanceof LinearLayout && ((LinearLayout) g).getOrientation() == LinearLayout.VERTICAL
                    && g.getChildCount() > 1) {
                return g;
            }
            if (g.getChildCount() != 1) {
                return null;
            }
            v = g.getChildAt(0);
        }
        return null;
    }

    /**
     * Top app bar: a small muted overline, a large bold title beneath it, and
     * an optional icon button pinned to the end edge. Returns the title view,
     * which the caller retitles as the user moves between sections.
     */
    static TextView appBar(ViewGroup parent, CharSequence overline, CharSequence title,
                           int trailingIconRes, CharSequence trailingDescription,
                           Runnable trailingAction) {
        return appBar(parent, overline, title, null, trailingIconRes, trailingDescription, trailingAction);
    }

    /**
     * Same bar, with a quiet line under the title -- the build stamp on the
     * launcher. Kept as an overload so every other screen (Log Viewer, Replay
     * Check, Online) keeps the two-line bar it already has.
     */
    static TextView appBar(ViewGroup parent, CharSequence overline, CharSequence title,
                           CharSequence subtitle, int trailingIconRes,
                           CharSequence trailingDescription, Runnable trailingAction) {
        return appBarWithLeading(parent, overline, title, subtitle,
            0, null, null, trailingIconRes, trailingDescription, trailingAction);
    }

    /**
     * GeneralsX @feature Flux Build 23/10/2026 The launcher's app bar with a leading navigation
     * icon (the hamburger that opens the drawer). Kept separate from the old overloads so every
     * other screen keeps exactly the bar it had.
     */
    static TextView appBarWithLeading(ViewGroup parent, CharSequence overline, CharSequence title,
                                      CharSequence subtitle,
                                      int leadingIconRes, CharSequence leadingDescription,
                                      Runnable leadingAction,
                                      int trailingIconRes, CharSequence trailingDescription,
                                      Runnable trailingAction) {
        Context c = parent.getContext();
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int gutter = dim(c, R.dimen.gzh_gutter);
        bar.setPadding(gutter, dp(c, 14), gutter, dp(c, 10));

        if (leadingIconRes != 0 && leadingAction != null) {
            View leading = navIconButton(c, leadingIconRes, leadingDescription, leadingAction);
            LinearLayout.LayoutParams leadingLp = new LinearLayout.LayoutParams(
                dp(c, 48), dp(c, 48));
            leadingLp.setMarginEnd(dp(c, 6));
            bar.addView(leading, leadingLp);
        }

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        bar.addView(text, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (overline != null) {
            TextView over = new TextView(c);
            over.setText(overline);
            over.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_overline));
            over.setTextColor(color(c, R.color.gzh_on_surface_faint));
            over.setAllCaps(true);
            over.setLetterSpacing(0.09f);
            over.setMaxLines(1);
            over.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.addView(over);
        }

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_display));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setLineSpacing(0f, 1.05f);
        if (leadingIconRes != 0) {
            // The hamburger and the trailing action leave less width than the old bar; keep the
            // product title on one line instead of wrapping into a taller bar on a 320 dp phone.
            titleView.setMaxLines(1);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        text.addView(titleView);

        // GeneralsX @feature Flux Build 23/10/2026 "FLUX BUILD v1.1.1" is a subtitle, not an
        // overline: it belongs to the product name, and the eye should read
        // "Generals ZH: Flux" first.
        if (subtitle != null) {
            TextView sub = new TextView(c);
            sub.setText(subtitle);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_overline));
            sub.setTextColor(color(c, R.color.gzh_primary));
            sub.setAllCaps(true);
            sub.setLetterSpacing(0.14f);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(c, 2);
            text.addView(sub, subLp);
        }

        if (trailingIconRes != 0 && trailingAction != null) {
            bar.addView(iconButton(c, trailingIconRes, trailingDescription, trailingAction));
        }

        parent.addView(bar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return titleView;
    }

    /**
     * GeneralsX @feature Flux Build 23/10/2026 A borderless 48dp icon button for the leading
     * edge of the app bar. The circular tonal style of {@link #iconButton} is right for the
     * trailing "logs" action but too heavy for a navigation drawer toggle.
     */
    static View navIconButton(Context c, int iconRes, CharSequence description, Runnable action) {
        ImageView button = new ImageView(c);
        button.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
        button.setImageTintList(tint(c, R.color.gzh_on_surface_variant));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        button.setContentDescription(description);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            tint(c, R.color.gzh_ripple_primary), null, null));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** Round, tonal icon-only button — used for the app bar's trailing action. */
    static View iconButton(Context c, int iconRes, CharSequence description, Runnable action) {
        int size = dim(c, R.dimen.gzh_icon_button);
        ImageView button = new ImageView(c);
        button.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
        button.setImageTintList(tint(c, R.color.gzh_on_surface_variant));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(c, 11);
        button.setPadding(pad, pad, pad, pad);
        button.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color(c, R.color.gzh_surface_container_high));
        button.setBackground(bg);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginStart(dim(c, R.dimen.gzh_item_gap));
        button.setLayoutParams(lp);
        return button;
    }

    // ----------------------------------------------------------------- cards

    /**
     * A rounded surface-container card appended to {@code parent}. Returns the
     * card's inner vertical content holder — callers just add views to it.
     */
    static LinearLayout card(LinearLayout parent) {
        Context c = parent.getContext();
        MaterialCardView card = new MaterialCardView(c);
        // GeneralsX @tweak Flux Build 07/11/2026 UI polish: one card radius (the dimen, which the code
        // used to bypass with a hard-coded 16 dp), a flat card held by its theme border.
        card.setRadius(dim(c, R.dimen.gzh_radius_card));
        card.setCardElevation(dp(c, 1));
        card.setCardBackgroundColor(color(c, R.color.gzh_surface_container));
        card.setStrokeWidth(dp(c, 1));
        // GeneralsX @feature Flux Build 23/10/2026 Follow the accent the player chose; the old
        // hard-coded cyan border made every custom accent look wrong around cards.
        card.setStrokeColor(withAlpha(color(c, R.color.gzh_primary), 0x40));
        card.setUseCompatPadding(false);
        card.setPreventCornerOverlap(false);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dim(c, R.dimen.gzh_card_padding);
        body.setPadding(pad, pad, pad, pad);
        card.addView(body, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_card_gap);
        parent.addView(card, lp);
        return body;
    }

    /**
     * Card header: an accent icon, the section name, and (optionally) a value
     * pinned to the end edge in the accent colour. Returns that value view, or
     * null when {@code withValue} is false.
     */
    static TextView sectionHeader(LinearLayout body, int iconRes, CharSequence title, boolean withValue) {
        Context c = body.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);

        if (iconRes != 0) {
            // GeneralsX @tweak Flux Build 07/11/2026 UI polish: the header icon in a soft accent disc.
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
            icon.setImageTintList(tint(c, R.color.gzh_primary));
            int s = dim(c, R.dimen.gzh_icon);
            int chip = s + dp(c, 12);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(withAlpha(color(c, R.color.gzh_primary), 0x24));
            icon.setBackground(disc);
            int inset = (chip - s) / 2;
            icon.setPadding(inset, inset, inset, inset);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(chip, chip);
            ilp.setMarginEnd(dp(c, 12));
            row.addView(icon, ilp);
        }

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_title));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        // Semi-bold (600), not the 700 of BOLD; no extra tracking (it can disturb Arabic joining).
        titleView.setTypeface(Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 600, false));
        row.addView(titleView, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = null;
        if (withValue) {
            value = new TextView(c);
            value.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_title));
            value.setTextColor(color(c, R.color.gzh_primary));
            value.setTypeface(Typeface.DEFAULT_BOLD);
            value.setGravity(Gravity.END);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.setMarginStart(dim(c, R.dimen.gzh_item_gap));
            row.addView(value, vlp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dim(c, R.dimen.gzh_item_gap);
        body.addView(row, lp);
        return value;
    }

    // ------------------------------------------------------------------ text

    /** Ordinary paragraph text inside a card. */
    static TextView body(LinearLayout parent, CharSequence text) {
        return text(parent, text, R.dimen.gzh_text_body, R.color.gzh_on_surface, 0);
    }

    /** Secondary/explanatory text — the grey supporting line. */
    static TextView supporting(LinearLayout parent, CharSequence text) {
        return text(parent, text, R.dimen.gzh_text_body, R.color.gzh_on_surface_variant, 0);
    }

    /** The smallest tier: help paragraphs and "when to turn this on" copy. */
    static TextView caption(LinearLayout parent, CharSequence text) {
        return text(parent, text, R.dimen.gzh_text_caption, R.color.gzh_on_surface_faint, 0);
    }

    private static TextView text(LinearLayout parent, CharSequence text,
                                 int sizeDimen, int colorRes, int topGapDp) {
        Context c = parent.getContext();
        TextView view = new TextView(c);
        if (text != null) {
            view.setText(text);
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, sizeDimen));
        view.setTextColor(color(c, colorRes));
        view.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topGapDp > 0 ? dp(c, topGapDp) : dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(view, lp);
        return view;
    }

    /**
     * Long help copy folded away behind a tappable "?" affordance would need a
     * new string; instead help paragraphs get a quieter treatment and a hairline
     * above them, so a card reads as "controls, then explanation".
     */
    static TextView helpText(LinearLayout parent, CharSequence text) {
        divider(parent);
        TextView view = caption(parent, text);
        ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin =
            dim(parent.getContext(), R.dimen.gzh_item_gap);
        return view;
    }

    static View divider(LinearLayout parent) {
        Context c = parent.getContext();
        View line = new View(c);
        line.setBackgroundColor(color(c, R.color.gzh_outline_variant));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.topMargin = dim(c, R.dimen.gzh_item_gap);
        parent.addView(line, lp);
        return line;
    }

    // --------------------------------------------------------------- buttons

    /** Filled accent action — one per card at most. */
    static final int BTN_PRIMARY = 0;
    /** Quiet filled action on a container tint — the everyday button. */
    static final int BTN_TONAL = 1;
    /** Outlined action, for a secondary choice next to a tonal one. */
    static final int BTN_OUTLINE = 2;
    /** Destructive/reset action, in the muted pastel. */
    static final int BTN_DANGER = 3;

    static MaterialButton button(LinearLayout parent, int kind, int iconRes,
                                 CharSequence label, Runnable onClick) {
        Context c = parent.getContext();
        MaterialButton b = new MaterialButton(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0f);
        b.setCornerRadius(dp(c, 26));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dim(c, R.dimen.gzh_button_height));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        b.setGravity(Gravity.CENTER);
        b.setElevation(0f);
        b.setStateListAnimator(null);
        b.setStrokeWidth(0);

        if (iconRes != 0) {
            b.setIcon(ContextCompat.getDrawable(c, iconRes));
            b.setIconSize(dim(c, R.dimen.gzh_icon));
            b.setIconPadding(dp(c, 10));
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        }

        switch (kind) {
            case BTN_PRIMARY:
                b.setBackgroundTintList(tint(c, R.color.gzh_primary, R.color.gzh_container_disabled));
                b.setTextColor(tint(c, R.color.gzh_on_primary, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_on_primary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_light));
                break;
            case BTN_OUTLINE:
                b.setBackgroundTintList(tint(c, android.R.color.transparent));
                b.setStrokeWidth(Math.max(1, dp(c, 1)));
                b.setStrokeColor(tint(c, R.color.gzh_outline, R.color.gzh_outline_variant));
                b.setTextColor(tint(c, R.color.gzh_on_surface, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_primary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_primary));
                break;
            case BTN_DANGER:
                b.setBackgroundTintList(tint(c, android.R.color.transparent));
                b.setStrokeWidth(Math.max(1, dp(c, 1)));
                b.setStrokeColor(tint(c, R.color.gzh_tertiary_container, R.color.gzh_outline_variant));
                b.setTextColor(tint(c, R.color.gzh_tertiary, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_tertiary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_light));
                break;
            case BTN_TONAL:
            default:
                b.setBackgroundTintList(tint(c, R.color.gzh_surface_container_high, R.color.gzh_container_disabled));
                b.setTextColor(tint(c, R.color.gzh_on_surface, R.color.gzh_on_surface_disabled));
                b.setIconTint(tint(c, R.color.gzh_primary, R.color.gzh_on_surface_disabled));
                b.setRippleColor(tint(c, R.color.gzh_ripple_light));
                break;
        }

        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(b, lp);
        return b;
    }

    /**
     * GeneralsX @feature Flux Build 23/10/2026 The one action a screen exists
     * for. A tall gold pill with navy lettering: gold is unused elsewhere in
     * the launcher, so Play cannot be mistaken for navigation (cyan) or for a
     * destructive action (the muted pastel).
     */
    static MaterialButton heroButton(LinearLayout parent, int iconRes,
                                     CharSequence label, Runnable onClick) {
        Context c = parent.getContext();
        MaterialButton b = new MaterialButton(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setLetterSpacing(0.06f);
        b.setCornerRadius(dp(c, 40));
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMinHeight(dp(c, 78));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(c, 24), dp(c, 18), dp(c, 24), dp(c, 18));
        b.setGravity(Gravity.CENTER);
        b.setElevation(dp(c, 6));
        b.setStateListAnimator(null);
        b.setStrokeWidth(0);
        b.setBackgroundTintList(ColorStateList.valueOf(color(c, R.color.gzh_gold)));
        b.setTextColor(ColorStateList.valueOf(color(c, R.color.gzh_primary_dark)));
        b.setRippleColor(tint(c, R.color.gzh_ripple_light));
        if (iconRes != 0) {
            b.setIcon(ContextCompat.getDrawable(c, iconRes));
            b.setIconSize(dp(c, 30));
            b.setIconPadding(dp(c, 14));
            b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
            b.setIconTint(ColorStateList.valueOf(color(c, R.color.gzh_primary_dark)));
        }
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_card_gap);
        parent.addView(b, lp);
        return b;
    }

    /** A horizontal strip that lays its children out at equal width. */
    static LinearLayout buttonRow(LinearLayout parent) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(row, lp);
        return row;
    }

    /**
     * Re-weights a button already added to a {@link #buttonRow} so the row's
     * buttons split its width evenly.
     *
     * MATCH_PARENT height rather than WRAP_CONTENT, deliberately: a
     * translation that wraps to two lines (Ukrainian "Очистити логи" is the
     * one that first caught this) would otherwise make that one button taller
     * than its siblings and break the row's bottom edge. Horizontal padding
     * also comes down, since a third of a phone's width has to hold an icon
     * and a word.
     */
    static void share(MaterialButton button, boolean firstInRow) {
        Context c = button.getContext();
        button.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 10));
        button.setMaxLines(2);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
        lp.width = 0;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.weight = 1f;
        lp.topMargin = 0;
        if (!firstInRow) {
            lp.setMarginStart(dim(c, R.dimen.gzh_item_gap_tight));
        }
        button.setLayoutParams(lp);
    }

    // ------------------------------------------------------------- segmented

    /**
     * A Material 3 segmented button row: N choices side by side, the selected
     * one filled in the accent. Returns the group; {@code onSelect} fires with
     * the index the user picked (never for the programmatic initial state).
     */
    static MaterialButtonToggleGroup segmented(LinearLayout parent, CharSequence[] labels,
                                               int checkedIndex,
                                               java.util.function.IntConsumer onSelect) {
        Context c = parent.getContext();
        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(c);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);

        final int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            MaterialButton b = new MaterialButton(c);
            b.setId(View.generateViewId());
            ids[i] = b.getId();
            b.setText(labels[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setMaxLines(1);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setCornerRadius(dp(c, 22));
            b.setInsetTop(0);
            b.setInsetBottom(0);
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setMinHeight(dp(c, 46));
            b.setPadding(dp(c, 6), dp(c, 10), dp(c, 6), dp(c, 10));
            b.setGravity(Gravity.CENTER);
            b.setElevation(0f);
            b.setStateListAnimator(null);
            b.setStrokeWidth(Math.max(1, dp(c, 1)));
            b.setStrokeColor(checkedTint(c, R.color.gzh_primary, R.color.gzh_outline));
            b.setBackgroundTintList(checkedTint(c, R.color.gzh_primary, android.R.color.transparent));
            b.setTextColor(checkedTint(c, R.color.gzh_on_primary, R.color.gzh_on_surface_variant));
            b.setRippleColor(tint(c, R.color.gzh_ripple_primary));
            group.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        if (checkedIndex >= 0 && checkedIndex < ids.length) {
            group.check(ids[checkedIndex]);
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == checkedId) {
                    onSelect.accept(i);
                    return;
                }
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(group, lp);
        return group;
    }

    // GeneralsX @feature Flux Build 06/11/2026 A row of independent on/off buttons in the segmented
    // style (the performance HUD's FPS / temperature / battery / graph). onToggle(index, checked).
    static MaterialButtonToggleGroup multiToggle(LinearLayout parent, CharSequence[] labels,
                                                 boolean[] checked,
                                                 java.util.function.BiConsumer<Integer, Boolean> onToggle) {
        MaterialButtonToggleGroup group = segmented(parent, labels, -1, index -> { });
        group.setSingleSelection(false);
        group.setSelectionRequired(false);
        group.clearOnButtonCheckedListeners();
        final int n = group.getChildCount();
        for (int i = 0; i < n && i < checked.length; i++) {
            if (checked[i]) {
                group.check(group.getChildAt(i).getId());
            }
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i).getId() == checkedId) {
                    onToggle.accept(i, isChecked);
                    return;
                }
            }
        });
        return group;
    }

    // ------------------------------------------------------------------ rows

    /** A title + supporting description + trailing Material switch. */
    static MaterialSwitch switchRow(LinearLayout parent, CharSequence title, CharSequence description) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);

        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_body));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        textColumn.addView(titleView);

        TextView descView = new TextView(c);
        descView.setText(description);
        descView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        descView.setTextColor(color(c, R.color.gzh_on_surface_faint));
        descView.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(c, 3);
        textColumn.addView(descView, dlp);

        MaterialSwitch toggle = new MaterialSwitch(c);
        // GeneralsX @feature Flux Build 23/10/2026 Keep switches on the same accent as the
        // rest of the launcher when custom colours are active.
        toggle.setThumbTintList(checkedTint(c, R.color.gzh_primary, R.color.gzh_on_surface_variant));
        toggle.setTrackTintList(checkedTint(c, R.color.gzh_primary_container,
            R.color.gzh_surface_container_highest));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(dim(c, R.dimen.gzh_item_gap));
        row.addView(toggle, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap);
        parent.addView(row, lp);
        return toggle;
    }

    /**
     * A tappable list row: leading icon, title, supporting line, trailing
     * chevron. {@link Row#supporting} is exposed because most callers need to
     * rewrite that line when the underlying state changes.
     */
    static final class Row {
        final LinearLayout root;
        final TextView title;
        final TextView supporting;

        private Row(LinearLayout root, TextView title, TextView supporting) {
            this.root = root;
            this.title = title;
            this.supporting = supporting;
        }
    }

    static Row listRow(LinearLayout parent, int iconRes, CharSequence title,
                       CharSequence supporting, Runnable onClick) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        int padH = dim(c, R.dimen.gzh_item_gap);
        int padV = dp(c, 14);
        row.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dim(c, R.dimen.gzh_radius_row));
        bg.setColor(color(c, R.color.gzh_surface_container_high));
        row.setBackground(bg);
        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> onClick.run());
            pressFeedback(row);
            // GeneralsX @bugfix Android port 03/10/2026 A tap on a row showed nothing until its
            // action had visible results -- for "copy this address" that is never. The same ripple
            // as the buttons, clipped to the row's rounded corners.
            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.RECTANGLE);
            mask.setCornerRadius(dim(c, R.dimen.gzh_radius_row));
            mask.setColor(0xFFFFFFFF);
            row.setForeground(new android.graphics.drawable.RippleDrawable(
                tint(c, R.color.gzh_ripple_primary), null, mask));
        }

        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(ContextCompat.getDrawable(c, iconRes));
            icon.setImageTintList(tint(c, R.color.gzh_primary));
            int s = dim(c, R.dimen.gzh_icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.setMarginEnd(dim(c, R.dimen.gzh_item_gap));
            row.addView(icon, ilp);
        }

        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(c);
        titleView.setText(title);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_body));
        titleView.setTextColor(color(c, R.color.gzh_on_surface));
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        textColumn.addView(titleView);

        TextView supportingView = new TextView(c);
        supportingView.setText(supporting);
        supportingView.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        supportingView.setTextColor(color(c, R.color.gzh_on_surface_variant));
        supportingView.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(c, 3);
        textColumn.addView(supportingView, slp);

        if (onClick != null) {
            ImageView chevron = new ImageView(c);
            chevron.setImageDrawable(ContextCompat.getDrawable(c, R.drawable.ic_gzh_chevron));
            chevron.setImageTintList(tint(c, R.color.gzh_on_surface_faint));
            int s = dp(c, 16);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, s);
            clp.setMarginStart(dim(c, R.dimen.gzh_item_gap_tight));
            row.addView(chevron, clp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(row, lp);
        return new Row(row, titleView, supportingView);
    }

    /**
     * A compact "chip": a rounded, tinted, non-interactive label. Used for the
     * one-word verdicts (GPU name, active driver) that used to be full
     * sentences of body text.
     */
    static TextView chip(LinearLayout parent, int iconRes, CharSequence label, int textColorRes,
                         int backgroundColorRes) {
        Context c = parent.getContext();
        TextView chip = new TextView(c);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, dim(c, R.dimen.gzh_text_caption));
        chip.setTextColor(color(c, textColorRes));
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(c, 12), dp(c, 7), dp(c, 12), dp(c, 7));
        chip.setMaxLines(2);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (iconRes != 0) {
            android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(c, iconRes);
            if (icon != null) {
                int s = dp(c, 15);
                icon.setBounds(0, 0, s, s);
                icon.setTint(color(c, textColorRes));
                chip.setCompoundDrawablesRelative(icon, null, null, null);
                chip.setCompoundDrawablePadding(dp(c, 7));
            }
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(c, 999));
        bg.setColor(color(c, backgroundColorRes));
        chip.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dim(c, R.dimen.gzh_item_gap_tight);
        parent.addView(chip, lp);
        return chip;
    }

    /** Updates a switch without firing its OnCheckedChangeListener. */
    static void setSwitchCheckedSilently(android.widget.CompoundButton sw,
                                         android.widget.CompoundButton.OnCheckedChangeListener listener,
                                         boolean checked) {
        if (sw == null || sw.isChecked() == checked) {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
    }
}
