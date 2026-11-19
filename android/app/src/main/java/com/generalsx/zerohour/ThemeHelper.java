package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Context;
import android.view.Window;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * GeneralsX @feature Flux Build 26/10/2026
 * The launcher is always the true-dark palette: #000000 ground, charcoal surfaces,
 * #6FB3BD muted cyan and #C7A45C muted gold. The old navy and day presets stay in the
 * resource files for the floating-overlay editor, but the launcher itself never selects
 * them.
 */
final class ThemeHelper {
    static final int STANDARD = 0;
    static final int NAVY = 1;
    static final int DAY = 2;
    static final int SYSTEM = 3;

    private ThemeHelper() {}

    static int getMode(Context context) {
        return STANDARD;
    }

    static void setMode(Context context, int mode) {}

    static int palette(Context context) {
        return STANDARD;
    }

    static int mapColor(Context context, int colorRes) {
        return context.getColor(colorRes);
    }

    static void apply(Activity activity) {
        activity.setTheme(R.style.Theme_GeneralsZHSettings);
    }

    static void applyBars(Activity activity) {
        Window window = activity.getWindow();
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
            controller.setAppearanceLightNavigationBars(false);
        }
    }

    static int accentColor(Context ctx) {
        return 0xFF6FB3BD; // Muted tactical cyan
    }
}
