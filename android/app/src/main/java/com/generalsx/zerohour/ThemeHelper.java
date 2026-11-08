package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.util.SparseIntArray;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * GeneralsX @feature Flux Build 26/10/2026
 * The launcher's default is the true-dark palette: #000000 ground, charcoal surfaces,
 * #6FB3BD muted cyan and #C7A45C muted gold.
 *
 * GeneralsX @feature Flux Build 05/11/2026 Launcher themes. The player picks one of five
 * (Interface tab). Every launcher colour goes through mapColor() (UiKit.color/tint), which swaps a
 * gzh_<role> resource for the theme's gzh_<theme>_<role> (colors_flux_themes.xml, generated from a
 * few base colours per theme), and apply() selects the matching XML theme (themes_flux.xml) so
 * dialogs, switches and the window background follow too. The game itself is not affected.
 */
final class ThemeHelper {
    static final int TACTICAL = 0;
    static final int GDI = 1;
    static final int NOD = 2;
    static final int GLA = 3;
    static final int DESERT = 4;
    static final int GRAY = 5;
    static final int AMOLED = 6;
    static final int CAMO = 7;
    static final int CYBER = 8;
    static final int GREEN = 9;
    static final int COUNT = 10;
    // GeneralsX @feature Flux Build 05/11/2026 Five more (Gray, AMOLED Black -- pure #000000 for OLED
    // screens --, Desert Camo, Cyberpunk, Classic Green); the order here is the order in the list.

    private static final String PREFS = "flux_launcher_theme";
    private static final String KEY_THEME = "theme";
    private static final String[] RESOURCE_KEYS = { null, "gdi", "nod", "gla", "desert", "gray", "amoled", "camo", "cyber", "green" };
    private static final int[] STYLES = {
        R.style.Theme_GeneralsZHSettings, R.style.Theme_GeneralsZHSettingsGdi, R.style.Theme_GeneralsZHSettingsNod,
        R.style.Theme_GeneralsZHSettingsGla, R.style.Theme_GeneralsZHSettingsDesert,
        R.style.Theme_GeneralsZHSettingsGray, R.style.Theme_GeneralsZHSettingsAmoled, R.style.Theme_GeneralsZHSettingsCamo,
        R.style.Theme_GeneralsZHSettingsCyber, R.style.Theme_GeneralsZHSettingsGreen,
    };

    // Resolved colours of the current theme, by gzh_* resource id. Cleared when the theme changes.
    private static final SparseIntArray sCache = new SparseIntArray();
    private static int sCacheMode = -1;

    private ThemeHelper() {}

    static int getMode(Context context) {
        int mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_THEME, TACTICAL);
        return mode >= 0 && mode < COUNT ? mode : TACTICAL;
    }

    static void setMode(Context context, int mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_THEME, mode >= 0 && mode < COUNT ? mode : TACTICAL).apply();
        synchronized (sCache) {
            sCache.clear();
            sCacheMode = -1;
        }
    }

    static boolean isLight(Context context) {
        return getMode(context) == DESERT;
    }

    static int mapColor(Context context, int colorRes) {
        int mode = getMode(context);
        if (mode == TACTICAL) {
            return context.getColor(colorRes);
        }
        synchronized (sCache) {
            if (sCacheMode != mode) {
                sCache.clear();
                sCacheMode = mode;
            }
            int index = sCache.indexOfKey(colorRes);
            if (index >= 0) {
                return sCache.valueAt(index);
            }
            int color = context.getColor(colorRes);
            try {
                String name = context.getResources().getResourceEntryName(colorRes);
                if (name.startsWith("gzh_")) {
                    int themed = context.getResources().getIdentifier(
                        "gzh_" + RESOURCE_KEYS[mode] + "_" + name.substring(4), "color", context.getPackageName());
                    if (themed != 0) {
                        color = context.getColor(themed);
                    }
                }
            } catch (android.content.res.Resources.NotFoundException ignored) {
                // Not a named colour resource: keep its own value.
            }
            sCache.put(colorRes, color);
            return color;
        }
    }

    static void apply(Activity activity) {
        activity.setTheme(STYLES[getMode(activity)]);
    }

    static void applyBars(Activity activity) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            boolean light = isLight(activity);
            controller.setAppearanceLightStatusBars(light);
            controller.setAppearanceLightNavigationBars(light);
        }
    }

    static int accentColor(Context ctx) {
        return mapColor(ctx, R.color.gzh_accent);
    }
}
