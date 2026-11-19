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

package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.Color;

/**
 * GeneralsX @feature Flux Build 23/10/2026 Custom launcher colours (Interface -> App Theme Colors).
 *
 * The three choices the player makes are stored in the same SharedPreferences file as the
 * language and the Appearance preset. Each role has its own "set" flag: changing the accent does
 * not throw away the selected Navy/Day surfaces, and changing the background does not overwrite a
 * chosen text colour. When no role has been customized this helper is inert and ThemeHelper keeps
 * using the selected Flux/Navy/Day palette, so the tactical dark palette remains the fallback.
 *
 * Only the launcher, its touch editor and the floating overlay read this class. The game's own
 * rendering is never affected.
 */
final class ThemeColors {
    static final String PREF_CUSTOM = "theme_colors_custom";
    static final String PREF_ACCENT = "theme_color_accent";
    static final String PREF_BACKGROUND = "theme_color_background";
    static final String PREF_TEXT = "theme_color_text";
    private static final String PREF_ACCENT_SET = "theme_color_accent_set";
    private static final String PREF_BACKGROUND_SET = "theme_color_background_set";
    private static final String PREF_TEXT_SET = "theme_color_text_set";

    static final int DEFAULT_ACCENT = 0xFF6FB3BD;
    static final int DEFAULT_BACKGROUND = 0xFF000000;
    static final int DEFAULT_TEXT = 0xFFE7ECEC;
    static final int DEFAULT_BUTTON_FILL = 0xFF121515;
    static final int LEGACY_BUTTON_FILL = 0xFF161F2C;

    private ThemeColors() {}

    private static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(LocaleHelper.PREFS_NAME, Context.MODE_PRIVATE);
    }

    static boolean isCustomized(Context context) {
        return false;
    }

    static boolean hasAccent(Context context) {
        return false;
    }

    static boolean hasBackground(Context context) {
        return false;
    }

    static boolean hasText(Context context) {
        return false;
    }

    static int accent(Context context) {
        return DEFAULT_ACCENT;
    }

    static int background(Context context) {
        return DEFAULT_BACKGROUND;
    }

    static int text(Context context) {
        return DEFAULT_TEXT;
    }

    static void setAccent(Context context, int color) {
        prefs(context).edit()
            .putInt(PREF_ACCENT, color)
            .putBoolean(PREF_ACCENT_SET, true)
            .putBoolean(PREF_CUSTOM, true)
            .apply();
    }

    static void setBackground(Context context, int color) {
        prefs(context).edit()
            .putInt(PREF_BACKGROUND, color)
            .putBoolean(PREF_BACKGROUND_SET, true)
            .putBoolean(PREF_CUSTOM, true)
            .apply();
    }

    static void setText(Context context, int color) {
        prefs(context).edit()
            .putInt(PREF_TEXT, color)
            .putBoolean(PREF_TEXT_SET, true)
            .putBoolean(PREF_CUSTOM, true)
            .apply();
    }

    /** Removes every override without touching the selected Appearance preset. */
    static void clearCustom(Context context) {
        prefs(context).edit()
            .remove(PREF_ACCENT)
            .remove(PREF_BACKGROUND)
            .remove(PREF_TEXT)
            .putBoolean(PREF_ACCENT_SET, false)
            .putBoolean(PREF_BACKGROUND_SET, false)
            .putBoolean(PREF_TEXT_SET, false)
            .putBoolean(PREF_CUSTOM, false)
            .apply();
    }

    static boolean isLightBackground(Context context) {
        return hasBackground(context) && Color.luminance(background(context)) > 0.5f;
    }

    /**
     * A floating button that still wears the factory fill follows the custom Background Base;
     * a fill the player chose in the touch editor is left alone. Both dark factory shades are
     * recognised because the constructor and the JSON fallback used slightly different values.
     */
    static int overlayFill(Context context, int fillColor) {
        if (!hasBackground(context)) {
            return fillColor;
        }
        if (fillColor == DEFAULT_BUTTON_FILL || fillColor == LEGACY_BUTTON_FILL) {
            return background(context);
        }
        return fillColor;
    }

    /**
     * Maps a standard gzh_* colour resource to the player's custom role. Called after the
     * Appearance palette has already produced a fallback, so roles the player has not changed
     * keep exactly the palette colour.
     */
    static int resolve(Context context, String resourceName, int fallback) {
        if (resourceName == null) {
            return fallback;
        }
        boolean customAccent = hasAccent(context);
        boolean customBackground = hasBackground(context);
        boolean customText = hasText(context);
        int accent = accent(context);
        int background = background(context);
        int text = text(context);
        switch (resourceName) {
            case "gzh_primary":
            case "gzh_accent":
            case "gzh_secondary":
                return customAccent ? accent : fallback;

            case "gzh_on_primary":
            case "gzh_on_secondary":
                return customAccent ? contrastOn(accent) : fallback;

            case "gzh_primary_container":
            case "gzh_secondary_container":
                return customAccent ? withAlpha(accent, 0x33) : fallback;

            case "gzh_on_primary_container":
            case "gzh_on_secondary_container":
                return customAccent
                    ? blend(accent, customText ? text : 0xFFFFFFFF, 0.45f) : fallback;

            case "gzh_primary_dark":
                return customBackground ? background : fallback;

            case "gzh_ripple_primary":
            case "gzh_outline_variant":
                return customAccent ? withAlpha(accent, 0x33) : fallback;

            case "gzh_ripple_light":
                return customAccent ? withAlpha(accent, 0x1F) : fallback;

            case "gzh_background":
                return customBackground ? background : fallback;

            case "gzh_surface":
                return customBackground ? shade(background, context, 0.035f) : fallback;

            case "gzh_surface_container_low":
                return customBackground ? shade(background, context, 0.050f) : fallback;

            case "gzh_surface_container":
                return customBackground ? shade(background, context, 0.090f) : fallback;

            case "gzh_surface_container_high":
                return customBackground ? shade(background, context, 0.130f) : fallback;

            case "gzh_surface_container_highest":
                return customBackground ? shade(background, context, 0.180f) : fallback;

            case "gzh_outline":
                return customBackground ? shade(background, context, 0.250f) : fallback;

            case "gzh_on_surface":
                return customText ? text : fallback;

            // Alpha variants keep the chosen text hue readable on any surface. They do not
            // depend on the background role, which may still be the palette's.
            case "gzh_on_surface_variant":
                return customText ? withAlpha(text, 0xC2) : fallback;

            case "gzh_on_surface_faint":
                return customText ? withAlpha(text, 0x7E) : fallback;

            case "gzh_on_surface_disabled":
                return customText ? withAlpha(text, 0x61) : fallback;

            case "gzh_container_disabled":
                return customText ? withAlpha(text, 0x1F) : fallback;

            default:
                return fallback;
        }
    }

    /** A surface step: lighter on dark grounds, darker on light ones. */
    private static int shade(int background, Context context, float amount) {
        return isLightBackground(context)
            ? blend(background, 0xFF000000, amount)
            : blend(background, 0xFFFFFFFF, amount);
    }

    /** Dark ink on a bright accent, white on a dark one. */
    static int contrastOn(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFE7ECEC;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    static int blend(int from, int to, float ratio) {
        float f = Math.max(0.0f, Math.min(1.0f, ratio));
        int a = Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f);
        int r = Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f);
        int g = Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f);
        int b = Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f);
        return Color.argb(a, r, g, b);
    }
}
