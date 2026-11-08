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

// GeneralsX @build Android port 07/07/2026
//
// A standalone launcher icon ("GeneralsZH Setup"), separate from the game
// itself, so configuring the game folder or reading a crash log never
// depends on the game having launched successfully first — and never
// requires adb. This is the practical answer to "there's no launcher": a
// full mod-manager-style launcher (à la GenLauncher) is future scope, but
// picking where the game lives and seeing why it crashed are needed on
// every single install, so they live here now.

package com.generalsx.zerohour;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

public class SetupActivity extends Activity {

    static final String PREFS_NAME = "generalszh_setup";
    static final String PREF_SKIP_INTRO = "skip_intro";

    static boolean isSkipIntroEnabled(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_SKIP_INTRO, false);
    }

    // GeneralsX @feature Flux Build 23/10/2026 The Graphics tab's "Disable menu shellmap".
    // Read by GeneralsZHActivity.getArguments(), which turns it into the engine's own
    // -noshellmap flag; GameEngine::init() then skips the 3D shellmap load and leaves the
    // menu on its static background. No second switch is invented in C++.
    static final String PREF_NO_SHELLMAP = "no_shellmap";

    static boolean isShellMapDisabled(android.content.Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_NO_SHELLMAP, false);
    }
    static final String PREF_GAME_PATH = "game_path";

    // GeneralsX @feature Android port 04/10/2026 Two games behind one icon: Command & Conquer
    // Generals and its expansion Zero Hour. The launcher shows one of them at a time; Play, the
    // game folder and its checks follow the selected game, everything on the other pages is
    // shared. Each game has its own folder (PREF_GAME_PATH stays Zero Hour's, so existing
    // installs keep theirs), its own user-data folder (saves, maps, Options.ini) and its own
    // engine libraries, libmain*.so for Zero Hour and libgenerals*.so for Generals.
    static final String PREF_GAME = "game";
    static final String GAME_ZERO_HOUR = "zh";
    static final String GAME_GENERALS = "generals";
    static final String PREF_GENERALS_GAME_PATH = "game_path_generals";

    static String getSelectedGame(android.content.Context ctx) {
        String game = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GAME, GAME_ZERO_HOUR);
        return GAME_GENERALS.equals(game) ? GAME_GENERALS : GAME_ZERO_HOUR;
    }

    static boolean isGeneralsSelected(android.content.Context ctx) {
        return GAME_GENERALS.equals(getSelectedGame(ctx));
    }

    static void setSelectedGame(android.content.Context ctx, String game) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_GAME, GAME_GENERALS.equals(game) ? GAME_GENERALS : GAME_ZERO_HOUR)
            .apply();
    }

    private static String gamePathPref(String game) {
        return GAME_GENERALS.equals(game) ? PREF_GENERALS_GAME_PATH : PREF_GAME_PATH;
    }

    // The selected game's user-data folder, laid out as on Windows (Documents): the leaf names
    // SDL3Main.cpp gives the engine through GENERALSX_USERDATA_DIR.
    static File userDataDir(android.content.Context ctx) {
        if (isGeneralsSelected(ctx)) {
            return new File(Environment.getExternalStorageDirectory(),
                "Generals/Command and Conquer Generals Data");
        }
        return DataPackInstaller.userDataDir();
    }

    // The engine library base names (System.loadLibrary) of the selected game: 30 Hz first, 60 Hz second.
    static String[] engineLibraries(android.content.Context ctx) {
        return isGeneralsSelected(ctx)
            ? new String[] { "generals", "generals60" }
            : new String[] { "main", "main60" };
    }

    // GeneralsX @feature Android port 15/09/2026 Simulation tick rate.
    //
    // This is not an ordinary setting: the tick rate is fixed when the engine is
    // compiled, because it decides the value of an enum the whole engine reads
    // (WWSyncPerSecond) and, through it, which fields GameLogic even has. So the APK
    // ships two copies of the engine and this preference chooses which one to load --
    // see GeneralsZHActivity.getLibraries().
    //
    // 30 Hz is what the port has always run and what Android-to-Android play is proven
    // on. 60 Hz is what the GeneralsOnline Windows client runs, so it is the only mode
    // that can stay in lockstep with a PC -- at the cost of twice the logic work per
    // second on the device.
    static final String PREF_SIM_HZ = "sim_hz";
    static final int SIM_HZ_RETAIL = 30;
    static final int SIM_HZ_CROSSPLAY = 60;

    static int getSimHz(android.content.Context ctx) {
        int hz = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getInt(PREF_SIM_HZ, SIM_HZ_RETAIL);
        return hz == SIM_HZ_CROSSPLAY ? SIM_HZ_CROSSPLAY : SIM_HZ_RETAIL;
    }

    static void setSimHz(android.content.Context ctx, int hz) {
        ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_SIM_HZ, hz == SIM_HZ_CROSSPLAY ? SIM_HZ_CROSSPLAY : SIM_HZ_RETAIL)
            .apply();
    }
    // GeneralsX @feature Android port 06/09/2026 Optional folder holding the
    // BASE Generals archives, for copies that keep them somewhere the engine
    // will not find on its own.
    static final String PREF_BASE_GENERALS_PATH = "base_generals_path";

    // TheSuperHackers @bugfix Android port 07/07/2026 SharedPreferences and
    // getFilesDir() both live under /data/data/<pkg>/ and are wiped the
    // moment the app is uninstalled -- which is exactly what a sideloaded
    // APK update often requires if the installer treats it as a fresh
    // install rather than an in-place update. Mirror the chosen path into a
    // small marker file on shared external storage (survives uninstall,
    // since it's outside the app's private/package-scoped directories) so a
    // fresh install can recover it automatically instead of re-prompting.
    private static final String EXTERNAL_MARKER_NAME = ".generalszh_gamepath.txt";
    private static final String GENERALS_EXTERNAL_MARKER_NAME = ".generals_gamepath.txt";

    // Marker files SDL3Main.cpp / GeneralsZHActivity check for on launch —
    // must match GameEngine/CMake's GeneralsMD/Code/Main/SDL3Main.cpp exactly.
    private static final String[] REQUIRED_GAME_FILES = { "INIZH.big", "INI.big" };
    // GeneralsX @feature Android port 04/10/2026 The original game: its own INI.big, and no
    // INIZH.big -- the engine mounts every archive in the folder, so a Zero Hour folder would
    // turn the base game into a broken mix of the two.
    private static final String GENERALS_REQUIRED_FILE = "INI.big";
    private static final String ZERO_HOUR_ONLY_FILE = "INIZH.big";

    private TextView statusText;
    private TextView gamePathText;

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // GeneralsX @bugfix Flux Build v1.2.7 28/10/2026 This screen is not
        // forced to landscape; it follows the device like every other non-game
        // screen. GeneralsZHActivity is sensorLandscape, so the OS applies
        // landscape before the game window is measured and there is no
        // Setup -> Launch rotation race to wait for.
        // GeneralsX @feature Android port 18/10/2026 The launcher's appearance (ThemeHelper).
        ThemeHelper.apply(this);
        super.onCreate(savedInstanceState);
        ThemeHelper.applyBars(this);
        setTitle(R.string.setup_window_title);

        // GeneralsX @feature Android port launcher-ui-2026 08/09/2026 Which
        // drawer section to open on. Survives the recreate() the
        // language picker performs, so changing the launcher language leaves
        // you looking at the section you changed it from rather than being
        // dropped back on Home.
        if (savedInstanceState != null) {
            currentTab = savedInstanceState.getInt(STATE_TAB, TAB_HOME);
        }

        // GeneralsX @bugfix Android port 08/07/2026 This screen is the ONLY
        // way to reach "View Logs" without adb, so it must never be the thing
        // that crashes. Any future Material/theme incompatibility falls back
        // to a bare-bones plain-widget UI (same actions, no styling) instead
        // of taking the whole Settings app down with it.
        try {
            buildUi();
        } catch (Throwable t) {
            buildFallbackUi(t);
        }
        moveOffVulkanOnce();
        maybeShowSupportPopup();
        maybeShowPerformanceDisclaimerDialog();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    // GeneralsX @bugfix Android port launcher-ui-2026 08/09/2026 The fallback
    // exists precisely for the case where a Material widget or theme attribute
    // is what blew up, so it must not itself construct one -- it used to build
    // MaterialButtons, which would have failed again for exactly the reason it
    // was reached. Plain framework widgets only, from here down.
    private void buildFallbackUi(Throwable failure) {
        clearPageReferences();
        // No tabs in the fallback: showTab() must become a no-op if anything
        // still calls it (onActivityResult does).
        contentHost = null;
        appBarTitle = null;
        drawerLayout = null;
        navigationView = null;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        InsetUtil.applySafeInsets(scroll);

        TextView warning = new TextView(this);
        warning.setText(getString(R.string.setup_fallback_warning, String.valueOf(failure)));
        warning.setPadding(0, 0, 0, dp(16));
        root.addView(warning);

        statusText = new TextView(this);
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, 0, 0, dp(24));
        root.addView(statusText);

        addPlainButton(root, getString(R.string.setup_button_select_game_folder), this::onSelectGameFolder);
        addPlainButton(root, getString(R.string.setup_button_view_logs), this::onViewLogs);
        addPlainButton(root, getString(R.string.setup_button_launch_game), this::onLaunchGame);
        addPlainButton(root, getString(R.string.setup_button_clear_game_folder), this::onClearGameFolder);
    }

    private void addPlainButton(LinearLayout root, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(b, lp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // GeneralsX @bugfix Flux Build v1.2.7 28/10/2026 Setup is not locked
        // to landscape any more; GeneralsZHActivity is sensorLandscape and
        // applies that itself. Reset any orientation request left on this
        // Activity instance so returning here from the game (or from a child
        // screen) keeps Setup's normal unspecified behaviour.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        refreshStatus();
        refreshGeneralsOnlineStatus();
        loadDxvkConfigIntoEditor();
        refreshDiagnosticsSwitches();
        // The first launch can run before the all-files permission is granted (the folder picker
        // is what asks for it). Retry once the player comes back with access; the guarded method
        // is a no-op after the profile was written.
        maybeRunAutoConfiguration();
        // Updates disabled in Flux Build
    }

    // GeneralsX @feature Android port launcher-ui-2026 08/09/2026 The launcher
    // was one endless scroll: eleven stacked cards, every one of them visible
    // whether or not it had anything to do with what you came here for, and
    // the two controls people actually open this app for (pick a folder,
    // launch) sat in the middle of it. It is now five sections behind a side
    // navigation drawer -- Home, Graphics, Interface, Tools, Help -- each a
    // short page you can take in at a glance.
    //
    // Nothing was dropped in the process: every card, button, switch, slider
    // and status line that existed before still exists, and every string
    // resource is still used. See showTab() for where each one landed.
    private static final String STATE_TAB = "gzh_tab";
    private static final int TAB_HOME = 1;
    private static final int TAB_GRAPHICS = 2;
    private static final int TAB_INTERFACE = 3;
    private static final int TAB_TOOLS = 4;
    private static final int TAB_HELP = 5;
    // GeneralsX @feature Flux Build 07/11/2026 v1.4.1: custom content in one place (maps, World Builder,
    // mods). Listed second, after Home.
    private static final int TAB_CONTENT = 6;
    /** Navigation style (Interface page): the side menu, or tabs always on screen. */
    static final String PREF_NAV_STYLE = "gx_nav_style";
    static final String NAV_STYLE_DRAWER = "drawer";
    static final String NAV_STYLE_TABS = "tabs";
    /** Tab order, for the visible tabs and the side menu alike. */
    private static final int[] TAB_ORDER = { TAB_HOME, TAB_CONTENT, TAB_GRAPHICS, TAB_INTERFACE, TAB_TOOLS, TAB_HELP };

    private int currentTab = TAB_HOME;
    private FrameLayout contentHost;
    private TextView appBarTitle;
    // GeneralsX @feature Flux Build 23/10/2026 Side navigation replaces the bottom bar.
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private com.google.android.material.tabs.TabLayout navigationTabs;

    static boolean useVisibleTabs(Context ctx) {
        return NAV_STYLE_TABS.equals(ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_NAV_STYLE, NAV_STYLE_DRAWER));
    }

    private void buildUi() {
        clearPageReferences();

        // GeneralsX @feature Flux Build 26/10/2026 True dark mode: the ground is pure black.
        // The blurred launcher-icon cover and the navy scrim that used to sit behind the
        // content are gone; the page and its cards carry every colour now.
        FrameLayout rootContainer = new FrameLayout(this);
        rootContainer.setBackgroundColor(UiKit.color(this, R.color.gzh_background));

        // DrawerLayout: the old shell is the content pane, the side menu is the drawer.
        // GeneralsX @feature Flux Build 23/10/2026 Bottom navigation replaced by a standard
        // side drawer with a hamburger in the app bar.
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1: the player chooses the navigation style
        // (Interface page). Tabs: every section always on screen under the app bar, no drawer.
        if (useVisibleTabs(this)) {
            drawerLayout = null;
            navigationView = null;
            LinearLayout shell = new LinearLayout(this);
            shell.setOrientation(LinearLayout.VERTICAL);
            appBarTitle = UiKit.appBar(shell, null, "Generals ZH: Flux",
                getString(R.string.app_header_subtitle, BuildConfig.VERSION_NAME),
                R.drawable.ic_gzh_doc, getString(R.string.setup_button_view_logs), this::onViewLogs);
            navigationTabs = new com.google.android.material.tabs.TabLayout(this);
            navigationTabs.setTabMode(com.google.android.material.tabs.TabLayout.MODE_SCROLLABLE);
            navigationTabs.setBackgroundColor(UiKit.color(this, R.color.gzh_surface));
            navigationTabs.setSelectedTabIndicatorColor(UiKit.color(this, R.color.gzh_primary));
            navigationTabs.setTabTextColors(UiKit.color(this, R.color.gzh_on_surface_variant),
                UiKit.color(this, R.color.gzh_primary));
            for (int tab : TAB_ORDER) {
                navigationTabs.addTab(navigationTabs.newTab().setText(tabTitle(tab)).setTag(tab), tab == currentTab);
            }
            navigationTabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab t) {
                    Object id = t.getTag();
                    if (id instanceof Integer && (Integer) id != currentTab) showTab((Integer) id);
                }
                @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab t) { }
                @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab t) { }
            });
            shell.addView(navigationTabs);
            contentHost = new FrameLayout(this);
            contentHost.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
            shell.addView(contentHost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            rootContainer.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(rootContainer);
            InsetUtil.applySafeInsets(rootContainer);
            showTab(currentTab);
            return;
        }
        navigationTabs = null;

        drawerLayout = new DrawerLayout(this);
        drawerLayout.setScrimColor(0x66000000);
        drawerLayout.setDrawerElevation(UiKit.dp(this, 12));

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        drawerLayout.addView(shell, new DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        appBarTitle = UiKit.appBarWithLeading(shell, null,
            "Generals ZH: Flux",
            getString(R.string.app_header_subtitle, BuildConfig.VERSION_NAME),
            R.drawable.ic_gzh_menu, getString(R.string.nav_open_menu),
            () -> drawerLayout.openDrawer(GravityCompat.START),
            R.drawable.ic_gzh_doc, getString(R.string.setup_button_view_logs), this::onViewLogs);

        contentHost = new FrameLayout(this);
        contentHost.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        shell.addView(contentHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        navigationView = buildNavigationDrawer();
        int drawerWidth = Math.min(UiKit.dp(this, 320),
            Math.round(getResources().getDisplayMetrics().widthPixels * 0.86f));
        drawerLayout.addView(navigationView, new DrawerLayout.LayoutParams(
            drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT, GravityCompat.START));

        rootContainer.addView(drawerLayout, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootContainer);
        InsetUtil.applySafeInsets(rootContainer);

        if (contentHost.getChildCount() == 0) {
            showTab(currentTab);
        }
    }

    /**
     * GeneralsX @feature Flux Build 23/10/2026 The side menu. Navy-Slate with cyan selection by
     * default; when the player customizes App Theme Colors it follows the same roles.
     */
    private NavigationView buildNavigationDrawer() {
        NavigationView nav = new NavigationView(this);
        nav.setBackgroundColor(UiKit.color(this, R.color.gzh_surface));
        nav.setFitsSystemWindows(false);

        int accent = UiKit.color(this, R.color.gzh_primary);
        android.content.res.ColorStateList itemTint = new android.content.res.ColorStateList(
            new int[][] { new int[] { android.R.attr.state_checked }, new int[0] },
            new int[] { accent, UiKit.color(this, R.color.gzh_on_surface_variant) });
        nav.setItemIconTintList(itemTint);
        nav.setItemTextColor(itemTint);

        android.graphics.drawable.GradientDrawable checked = new android.graphics.drawable.GradientDrawable();
        checked.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        checked.setCornerRadius(UiKit.dp(this, 24));
        checked.setColor(UiKit.withAlpha(accent, 0x33));
        android.graphics.drawable.StateListDrawable itemBackground =
            new android.graphics.drawable.StateListDrawable();
        itemBackground.addState(new int[] { android.R.attr.state_checked }, checked);
        itemBackground.addState(new int[0], new android.graphics.drawable.ColorDrawable(
            android.graphics.Color.TRANSPARENT));
        nav.setItemBackground(itemBackground);

        nav.addHeaderView(buildDrawerHeader());

        Menu menu = nav.getMenu();
        menu.add(Menu.NONE, TAB_HOME, 0, R.string.nav_tab_home)
            .setIcon(R.drawable.ic_gzh_home).setCheckable(true);
        menu.add(Menu.NONE, TAB_CONTENT, 1, R.string.nav_tab_content)
            .setIcon(R.drawable.ic_gzh_folder).setCheckable(true);
        menu.add(Menu.NONE, TAB_GRAPHICS, 2, R.string.nav_tab_graphics)
            .setIcon(R.drawable.ic_gzh_display).setCheckable(true);
        menu.add(Menu.NONE, TAB_INTERFACE, 3, R.string.nav_tab_interface)
            .setIcon(R.drawable.ic_gzh_globe).setCheckable(true);
        menu.add(Menu.NONE, TAB_TOOLS, 4, R.string.nav_tab_tools)
            .setIcon(R.drawable.ic_gzh_wrench).setCheckable(true);
        menu.add(Menu.NONE, TAB_HELP, 5, R.string.nav_tab_help)
            .setIcon(R.drawable.ic_gzh_info).setCheckable(true);

        nav.setCheckedItem(currentTab);
        nav.setNavigationItemSelectedListener(item -> {
            showTab(item.getItemId());
            if (drawerLayout != null) {
                drawerLayout.closeDrawers();
            }
            return true;
        });
        return nav;
    }

    private View buildDrawerHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        int padH = UiKit.dp(this, 20);
        header.setPadding(padH, UiKit.dp(this, 26), padH, UiKit.dp(this, 18));

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.mipmap.ic_launcher);
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
            UiKit.dp(this, 52), UiKit.dp(this, 52));
        markLp.bottomMargin = UiKit.dp(this, 14);
        header.addView(mark, markLp);

        TextView title = new TextView(this);
        title.setText("Generals ZH: Flux");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(getString(R.string.app_header_subtitle, BuildConfig.VERSION_NAME));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        subtitle.setAllCaps(true);
        subtitle.setLetterSpacing(0.14f);
        subtitle.setTextColor(UiKit.color(this, R.color.gzh_primary));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = UiKit.dp(this, 3);
        header.addView(subtitle, subLp);

        return header;
    }

    @Override
    public void onBackPressed() {
        // GeneralsX @feature Flux Build 23/10/2026 Back closes the drawer before it leaves the
        // launcher, which is what every Android navigation drawer does.
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    private int tabTitle(int tab) {
        switch (tab) {
            case TAB_GRAPHICS:  return R.string.nav_tab_graphics;
            case TAB_INTERFACE: return R.string.nav_tab_interface;
            case TAB_TOOLS:     return R.string.nav_tab_tools;
            case TAB_HELP:      return R.string.nav_tab_help;
            case TAB_CONTENT:   return R.string.nav_tab_content;
            default:            return R.string.nav_tab_home;
        }
    }

    /**
     * Swaps the page inside the shell. Every view a refresh* method touches is
     * page-scoped, so the references are dropped first and the refreshers are
     * re-run at the end against whatever the new page actually built -- they
     * all null-check, so a page that has no status line simply doesn't get one
     * updated.
     */
    private void showTab(int tab) {
        final int previousTab = currentTab;
        final boolean animate = contentHost != null && contentHost.getChildCount() > 0
            && tab != previousTab;
        currentTab = tab;
        if (navigationView != null) {
            navigationView.setCheckedItem(tab);
        }
        if (navigationTabs != null) {
            for (int i = 0; i < navigationTabs.getTabCount(); i++) {
                com.google.android.material.tabs.TabLayout.Tab t = navigationTabs.getTabAt(i);
                if (t != null && Integer.valueOf(tab).equals(t.getTag()) && !t.isSelected()) t.select();
            }
        }
        if (contentHost == null) {
            return;  // the plain-widget fallback UI is up; there are no tabs
        }
        clearPageReferences();
        contentHost.removeAllViews();
        if (appBarTitle != null) {
            UiKit.crossfadeText(appBarTitle, getString(tabTitle(tab)));
        }

        LinearLayout page = UiKit.scrollingPage(contentHost);
        switch (tab) {
            case TAB_GRAPHICS:
                buildAutoConfigSection(page);
                buildSimRateSection(page);
                buildInternalResolutionSection(page);
                // GeneralsX @tweak Android port 02/10/2026 The upscaler lives in the GLES translator
                // (plain GLES and GLES on ANGLE); under Vulkan it does nothing, so it is not offered.
                // GeneralsX @bugfix Flux Build 03/11/2026 Render scale on every GPU again: v1.3.7-hotfix
                // hid the whole card on Mali, Xclipse and PowerVR -- their main performance setting --
                // and rewrote Options.ini (GXUpscale = off) each time this page opened. Only the GSR
                // sharpening filter is Adreno-only now; elsewhere the scene is scaled with bilinear
                // filtering, and Options.ini is written only when that choice actually changes.
                if (!RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice())) {
                    if (!isAdrenoGpu()) {
                        ensureUpscalerFilter(UPSCALER_BILINEAR);
                    }
                    buildRenderScaleSection(page);
                }
                buildRenderBackendSection(page);
                // GeneralsX @tweak Flux Build 08/11/2026 v1.4.1 round 4 UI audit: a start-up option, after
                // the display settings instead of between them.
                buildNoShellMapSection(page);
                // Custom Vulkan driver / dxvk.conf only matter when Vulkan is
                // the selected backend -- the GLES/GLES+ANGLE paths never
                // touch DXVK at all, see
                // Core/Libraries/Source/d3d8gles/CMakeLists.txt.
                if (RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice())) {
                    applyRecommendedDriverIfNeeded();
                    buildCustomDriverSection(page);
                    buildDxvkConfigSection(page);
                }
                break;
            case TAB_INTERFACE:
                buildThemeSection(page);
                buildLanguageSection(page);
                buildUiScaleSection(page);
                buildInterfaceScaleSection(page);
                buildControlSchemeSection(page);
                // GeneralsX @tweak Flux Build 07/11/2026 Camera & Selection Mode (with the "i" Info
                // switch) lives with the other control settings here, no longer on Home.
                buildSelectionModeSection(page);
                buildHotkeysAndControlBarSection(page);
                buildNavigationStyleSection(page);
                break;
            case TAB_CONTENT:
                // GeneralsX @tweak Flux Build 07/11/2026 v1.4.1 round 3 The editors, map import, mods and
                // replays first; the player's map list, which grows long, last (owner request).
                buildMapsSection(page);
                buildModsSection(page);
                buildMapToolsSection(page); // replays (v1.4.1: custom content in one place)
                buildMyMapsSection(page);
                break;
            case TAB_TOOLS:
                buildLogsSection(page);
                buildDiagnosticsSection(page);
                break;
            case TAB_HELP:
                buildHelpSection(page);
                break;
            case TAB_HOME:
            default:
                buildHomeSection(page);
                break;
        }

        // Material 3 fade + short slide, in the direction of travel (RTL-aware). The old page
        // is replaced at once rather than cross-faded: a drawer selection should feel like one
        // surface moving, not two screens stacked on top of each other.
        if (animate) {
            UiKit.animatePageIn(contentHost.getChildAt(0), tab > previousTab);
        }

        refreshStatus();
        refreshGeneralsOnlineStatus();
        loadDxvkConfigIntoEditor();
        refreshDiagnosticsSwitches();
        refreshUpdatesStatus();
    }

    /** Forgets every page-scoped view so a stale one is never written to. */
    private void clearPageReferences() {
        statusText = null;
        gamePathText = null;
        onlineStatusView = null;
        updatesStatusView = null;
        gameLanguageStatusView = null;
        renderBackendStatusView = null;
        customDriverStatusView = null;
        diagnosticsNoFolderHint = null;
        dxvkConfigEdit = null;
        uiScaleSlider = null;
        uiScaleLabel = null;
        upscaleStatus = null;
        interfaceScaleSlider = null;
        interfaceScaleLabel = null;
        java.util.Arrays.fill(diagnosticSwitches, null);
    }

    // ------------------------------------------------------------ Home page

    private void buildHomeSection(LinearLayout page) {
        // GeneralsX @feature Android port 04/10/2026 Which game this page is about. Play, the game
        // folder and its checks below follow it; the other pages are shared by both games.
        final boolean generals = isGeneralsSelected(this);

        // GeneralsX @feature Flux Build 23/10/2026 Play is the first thing on the page and the
        // only gold element in the launcher. Everything that used to sit above it (three
        // full-width folder buttons) moved into the one compact directory card below.
        UiKit.heroButton(page, R.drawable.ic_gzh_play,
            getString(R.string.setup_button_launch_game), this::onLaunchGame);
        String gameName = getString(generals ? R.string.setup_game_generals : R.string.setup_game_zero_hour);
        String simRate = getString(getSimHz(this) == SIM_HZ_CROSSPLAY
            ? R.string.setup_sim_rate_60_short : R.string.setup_sim_rate_30_short);
        UiKit.caption(page, getString(R.string.setup_play_subtitle, gameName, simRate));
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.0-community: PC cross-play is off, said on Home.
        GeneralsOnlineActivity.addCrossplayNotice(page);

        // ------------------------------------------------------------ game card
        LinearLayout game = UiKit.card(page);
        UiKit.sectionHeader(game, R.drawable.ic_gzh_play, getString(R.string.setup_card_game), false);
        CharSequence[] games = new CharSequence[] {
            getString(R.string.setup_game_generals),
            getString(R.string.setup_game_zero_hour)
        };
        UiKit.segmented(game, games, generals ? 0 : 1, index -> {
            String picked = index == 0 ? GAME_GENERALS : GAME_ZERO_HOUR;
            if (picked.equals(getSelectedGame(this))) {
                return;
            }
            setSelectedGame(this, picked);
            // The app bar names the game, so rebuild the whole shell rather than only this page.
            buildUi();
        });
        UiKit.supporting(game, getString(R.string.setup_game_desc));

        // GeneralsX @feature Android port 05/10/2026 Skip the logos and intro movies (owner's
        // request): the engine's own -nologo, passed by GeneralsZHActivity.getArguments(), for
        // both games. Whatever plays in their place -- EA logo, intro, sizzle, or a mod's or a
        // repack's replacement of those movies -- is skipped, straight to loading.
        SwitchCompat skipIntro = UiKit.switchRow(game,
            getString(R.string.setup_switch_skip_intro), getString(R.string.setup_switch_skip_intro_desc));
        skipIntro.setChecked(isSkipIntroEnabled(this));
        skipIntro.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_SKIP_INTRO, checked).apply());

        // GeneralsX @feature Android port 18/10/2026 The Mods screen (ModManagerActivity): which mods
        // the next game starts with, and in what order.
        // GeneralsX @tweak Android port 19/10/2026 Installed but all off is not "No mods" (owner read
        // it as the mods being gone after an update).
        java.util.List<ModManager.Mod> installedMods = ModManager.listMods(this);
        int modsOn = 0;
        for (ModManager.Mod mod : installedMods) {
            if (mod.enabled) {
                modsOn++;
            }
        }
        UiKit.listRow(game, R.drawable.ic_gzh_folder, getString(R.string.mods_title),
            installedMods.isEmpty() ? getString(R.string.mods_home_none)
                : modsOn == 0 ? getString(R.string.mods_home_all_off, installedMods.size())
                : getString(R.string.mods_home_enabled, modsOn),
            () -> startActivityForResult(new Intent(this, ModManagerActivity.class), REQUEST_MODS));

        // ------------------------------------------------------ directory card
        // GeneralsX @feature Flux Build 23/10/2026 One card, one folder icon: the path and its
        // verdict are the information, not three buttons. Import Map lives on Tools now.
        LinearLayout folder = UiKit.card(page);
        UiKit.sectionHeader(folder, R.drawable.ic_gzh_folder,
            getString(R.string.setup_card_game_directory), false);
        UiKit.caption(folder, getString(R.string.setup_game_directory_hint));

        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pathRow.setPadding(0, UiKit.dp(this, 8), 0, 0);

        LinearLayout pathColumn = new LinearLayout(this);
        pathColumn.setOrientation(LinearLayout.VERTICAL);
        gamePathText = UiKit.caption(pathColumn, null);
        gamePathText.setTextIsSelectable(true);
        gamePathText.setMaxLines(2);
        gamePathText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        statusText = UiKit.caption(pathColumn, null);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        pathRow.addView(pathColumn, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        pathRow.addView(UiKit.iconButton(this, R.drawable.ic_gzh_folder,
            getString(R.string.setup_button_change_folder), this::onSelectGameFolder));
        folder.addView(pathRow);

        if (!generals) {
            // Zero Hour only: the base game's archives are where its missing art comes from.
            // A compact row with its own folder button, not a second full-width primary button.
            String base = getBaseGeneralsPath();
            LinearLayout baseRow = new LinearLayout(this);
            baseRow.setOrientation(LinearLayout.HORIZONTAL);
            baseRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            baseRow.setPadding(0, UiKit.dp(this, 10), 0, 0);
            LinearLayout baseColumn = new LinearLayout(this);
            baseColumn.setOrientation(LinearLayout.VERTICAL);
            TextView baseTitle = UiKit.body(baseColumn, getString(R.string.setup_game_directory_base));
            baseTitle.setTypeface(Typeface.DEFAULT_BOLD);
            TextView basePath = UiKit.caption(baseColumn, base == null || base.isEmpty()
                ? getString(R.string.setup_game_directory_base_missing) : base);
            basePath.setMaxLines(1);
            basePath.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            baseRow.addView(baseColumn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            baseRow.addView(UiKit.iconButton(this, R.drawable.ic_gzh_folder,
                getString(R.string.setup_button_change_base_folder), this::onSelectBaseGeneralsFolder));
            folder.addView(baseRow);
        } else {
            // Base Generals borrows the pictures it lacks from Zero Hour's TexturesZH.big.
            buildDataSourcesRow(folder, true);
        }

        UiKit.button(folder, UiKit.BTN_DANGER, R.drawable.ic_gzh_broom,
            getString(R.string.setup_button_clear_game_folder), this::onClearGameFolder);
        if (!generals && getBaseGeneralsPath() != null) {
            UiKit.button(folder, UiKit.BTN_DANGER, R.drawable.ic_gzh_broom,
                getString(R.string.setup_button_clear_base_generals), this::onClearBaseGeneralsFolder);
        }

        buildTouchControlsSection(page);

        // GeneralsX @bugfix Android port 01/08/2026 kept above the advanced
        // settings -- signing into GeneralsOnline is a primary action most
        // people want right after picking their game folder, not something to
        // bury under settings most players never touch.
        buildGeneralsOnlineSection(page);
        // buildUpdatesSection(page); removed for Flux Build
    }

    private void buildTouchControlsSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_sliders, getString(R.string.setup_card_touch_controls), false);
        // GeneralsX @feature Flux Build 03/11/2026 What the touch gestures do (force-select, hover
        // details, Screen Glide), described here only -- the gestures themselves are unchanged.
        UiKit.helpText(content, getString(R.string.setup_touch_gestures_help));
        UiKit.helpText(content, getString(R.string.setup_touch_controls_help));
        SwitchCompat hotkey = UiKit.switchRow(content,
            getString(R.string.setup_touch_hotkey_row), getString(R.string.setup_touch_hotkey_row_desc));
        hotkey.setChecked(TouchControlConfig.load(this).enabled);
        hotkey.setOnCheckedChangeListener((button, checked) -> {
            TouchControlConfig cfg = TouchControlConfig.load(this);
            cfg.enabled = checked;
            cfg.save(this);
        });
        UiKit.button(content, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_button_touch_controls), this::onTouchControls);
    }

    // GeneralsX @feature Flux Build 05/11/2026 Camera & Selection Mode. GXSelectionMode in Options.ini
    // (1 = classic: one finger pans, hold then drag for a box; 2 = one finger draws a box at once and
    // two fingers move the camera), read by SDL3Main.cpp at startup and used by SDL3GameEngine.cpp.
    private void buildSelectionModeSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_sliders, getString(R.string.setup_card_selection_mode), false);
        CharSequence[] labels = {
            getString(R.string.setup_selection_mode_classic), getString(R.string.setup_selection_mode_modern) };
        int current = readSelectionMode() == 2 ? 1 : 0;
        TextView[] help = new TextView[1];
        UiKit.segmented(content, labels, current, index -> {
            writeSelectionMode(index == 1 ? 2 : 1);
            if (help[0] != null) {
                help[0].setText(index == 1 ? R.string.setup_selection_mode_help_modern
                    : R.string.setup_selection_mode_help_classic);
            }
        });
        help[0] = UiKit.helpText(content, getString(current == 1
            ? R.string.setup_selection_mode_help_modern : R.string.setup_selection_mode_help_classic));

        // GeneralsX @tweak Flux Build 07/11/2026 The "i" Info button belongs with how a tap works, under
        // the selection mode (owner's request), not with the performance options.
        SwitchCompat info = UiKit.switchRow(content,
            getString(R.string.setup_switch_info_button), getString(R.string.setup_switch_info_button_desc));
        info.setChecked(InGameHud.infoButtonEnabled(this));
        info.setOnCheckedChangeListener((button, checked) ->
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(InGameHud.PREF_INFO_BUTTON, checked).apply());

        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 Inertial panning (GXInertialPan; on when absent).
        SwitchCompat inertia = UiKit.switchRow(content,
            getString(R.string.setup_switch_inertial_pan), getString(R.string.setup_switch_inertial_pan_desc));
        inertia.setChecked(!"0".equals(readOptionsKey("GXInertialPan")));
        inertia.setOnCheckedChangeListener((button, checked) -> writeOptionsKey("GXInertialPan", checked ? "1" : "0"));

        // GeneralsX @feature Flux Build 08/11/2026 v1.4.1 round 4 Extreme zoom-out (GXExtremeZoom, off
        // when absent; W3DView::gxCapZoomOut).
        SwitchCompat zoom = UiKit.switchRow(content,
            getString(R.string.setup_switch_extreme_zoom), getString(R.string.setup_switch_extreme_zoom_desc));
        zoom.setChecked("1".equals(readOptionsKey("GXExtremeZoom")));
        zoom.setOnCheckedChangeListener((button, checked) -> writeOptionsKey("GXExtremeZoom", checked ? "1" : "0"));
    }

    private int readSelectionMode() {
        String val = readKeyValueFile(optionsIniFile()).get("GXSelectionMode");
        return val != null && val.trim().equals("2") ? 2 : 1;
    }

    private void writeSelectionMode(int mode) {
        File file = optionsIniFile();
        // Seeded from DefaultOptions.ini when new, for the same reason as writeUiScalePercent().
        java.util.LinkedHashMap<String, String> prefs;
        if (!file.isFile()) {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        } else {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        }
        prefs.put("GXSelectionMode", String.valueOf(mode == 2 ? 2 : 1));
        writeKeyValueFile(file, prefs);
    }

    private void onTouchControls() {
        startActivity(new Intent(this, TouchControlsActivity.class));
    }

    // GeneralsX @feature Android port 18/10/2026 PLAN-030: where each game takes what its own folder
    // lacks, in one place, with a check that it is there. Generals borrows the pictures it lacks
    // from Zero Hour's TexturesZH.big (GeneralsZHActivity.writeZeroHourArtPath); Zero Hour reads the
    // original Generals archives (the base folder, or ZH_Generals / next to the game folder).
    private void buildDataSourcesRow(LinearLayout card, boolean generals) {
        if (generals) {
            String zh = getSavedGamePath(this, GAME_ZERO_HOUR);
            if (zh == null || zh.isEmpty()) {
                UiKit.chip(card, R.drawable.ic_gzh_info, getString(R.string.setup_sources_zh_art_no_folder),
                    R.color.gzh_status_warn, R.color.gzh_surface_container_high);
            } else if (findFileIgnoreCase(new File(zh), "TexturesZH.big")) {
                UiKit.chip(card, R.drawable.ic_gzh_check, getString(R.string.setup_sources_zh_art_ok, zh),
                    R.color.gzh_status_ok, R.color.gzh_surface_container_high);
            } else {
                UiKit.chip(card, R.drawable.ic_gzh_info, getString(R.string.setup_sources_zh_art_missing, zh),
                    R.color.gzh_status_warn, R.color.gzh_surface_container_high);
            }
            return;
        }
        String base = getBaseGeneralsPath();
        if (base != null && !base.isEmpty()) {
            UiKit.chip(card, R.drawable.ic_gzh_check, getString(R.string.setup_sources_base_ok, base),
                R.color.gzh_status_ok, R.color.gzh_surface_container_high);
        } else {
            UiKit.supporting(card, getString(R.string.setup_sources_base_auto));
        }
    }

    private static boolean findFileIgnoreCase(File dir, String name) {
        String[] names = dir.list();
        if (names == null) {
            return false;
        }
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ Updates

    // GeneralsX @feature Android port 27/09/2026 Signed updates from the repository without a new
    // APK: a newer engine, when one is published. The network settings from the same signed
    // manifest are applied by the same check but shown on the multiplayer screen. See UpdateManager.
    private TextView updatesStatusView;
    private View updatesOpenOnlineButton;
    private boolean updateCheckRunning;
    private static boolean sAutoUpdateCheckedThisProcess;

    private void buildUpdatesSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_refresh,
            getString(R.string.setup_card_updates), false);
        UiKit.supporting(content, getString(R.string.setup_updates_help));
        updatesStatusView = UiKit.body(content, null);
        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_download,
            getString(R.string.setup_button_check_updates), () -> runUpdateCheck(true));
        // The community data patch is updated on the multiplayer screen; this card only says a
        // newer one is out and takes the player there.
        updatesOpenOnlineButton = UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_globe,
            getString(R.string.setup_button_open_online_data), () ->
                startActivity(new Intent(this, GeneralsOnlineActivity.class)));
        SwitchCompat auto = UiKit.switchRow(content,
            getString(R.string.setup_switch_auto_updates), getString(R.string.setup_switch_auto_updates_desc));
        auto.setChecked(UpdateManager.isAutoCheckEnabled(this));
        auto.setOnCheckedChangeListener((button, checked) -> UpdateManager.setAutoCheckEnabled(this, checked));
        refreshUpdatesStatus();
    }

    private void refreshUpdatesStatus() {
        if (updatesStatusView == null) {
            return;
        }
        int active = UpdateManager.activeEngineSeq(this);
        String engine = active > 0
            ? getString(R.string.setup_updates_engine_updated, active)
            : getString(R.string.setup_updates_engine_bundled, UpdateManager.bundledEngineSeq(this));
        long last = UpdateManager.lastCheckMillis(this);
        String when = last > 0
            ? android.text.format.DateFormat.getDateFormat(this).format(new java.util.Date(last)) + " "
              + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(last))
            : getString(R.string.setup_updates_never);
        String status = getString(R.string.setup_updates_status, engine, when);
        final boolean newerData = UpdateManager.datapackNewerAvailable(this);
        if (newerData) {
            status += "\n" + getString(R.string.setup_updates_datapack_line_new,
                UpdateManager.datapackLatestSeen(this));
        }
        updatesStatusView.setText(status);
        if (updatesOpenOnlineButton != null) {
            updatesOpenOnlineButton.setVisibility(newerData ? View.VISIBLE : View.GONE);
        }
    }

    /** @param userAsked true for the button (always report), false for the silent start-up check. */
    private void runUpdateCheck(boolean userAsked) {
        if (updateCheckRunning) {
            return;
        }
        updateCheckRunning = true;
        if (userAsked) {
            toast(getString(R.string.setup_updates_checking));
        }
        new Thread(() -> {
            final android.content.Context app = getApplicationContext();
            final UpdateManager.Result r = UpdateManager.check(app, true);
            runOnUiThread(() -> {
                updateCheckRunning = false;
                refreshUpdatesStatus();
                // The support card is read from the support.json this check may just have replaced.
                if (r.supportUpdated && currentTab == TAB_HELP && contentHost != null) {
                    showTab(TAB_HELP);
                }
                // A new announcement may have just arrived with it.
                maybeShowSupportPopup();
                if (!r.ok) {
                    if (userAsked) {
                        toast(r.offline
                            ? getString(R.string.setup_updates_offline)
                            : r.error != null && r.error.startsWith("HTTP 404")
                            ? getString(R.string.setup_updates_not_published)
                            : getString(R.string.setup_updates_failed, r.error));
                    }
                    return;
                }
                if (r.datapackAvailable != null) {
                    toast(getString(R.string.setup_updates_datapack_available, r.datapackAvailable));
                }
                if (r.engineDownloaded) {
                    toast(getString(R.string.setup_updates_engine_ready, r.engineSeq));
                } else if (r.engineIncompatible) {
                    toast(getString(R.string.setup_updates_engine_needs_apk, r.engineSeq));
                } else if (userAsked) {
                    toast(getString(R.string.setup_updates_none));
                }
            });
        }, "gx-update-check").start();
    }

    // ------------------------------------------------------------ Help page

    private void buildHelpSection(LinearLayout page) {
        buildCreditsSection(page);
        buildAcademySection(page);
        LinearLayout about = UiKit.card(page);
        UiKit.sectionHeader(about, R.drawable.ic_gzh_info,
            getString(R.string.setup_window_title), false);
        UiKit.supporting(about, getString(R.string.setup_subtitle));
        UiKit.chip(about, R.drawable.ic_gzh_check, BuildConfig.VERSION_NAME,
            R.color.gzh_primary, R.color.gzh_surface_container_high);
        UiKit.divider(about);
        UiKit.body(about, getString(R.string.about_flux_credits, BuildConfig.VERSION_NAME));
        // GeneralsX @feature Flux Build 06/11/2026 The release highlights again (WhatsNew).
        UiKit.listRow(about, R.drawable.ic_gzh_heart, getString(R.string.whats_new_row),
            getString(R.string.whats_new_row_desc), () -> WhatsNew.show(this));
        // GeneralsX @feature Flux Build 07/11/2026 The Flux Build Telegram channel (owner's request).
        UiKit.listRow(about, R.drawable.ic_gzh_telegram, getString(R.string.help_telegram_title),
            getString(R.string.help_telegram_desc), this::openTelegram);

        LinearLayout legal = UiKit.card(page);
        UiKit.sectionHeader(legal, R.drawable.ic_gzh_doc,
            getString(R.string.legal_credits_title), false);
        UiKit.supporting(legal, getString(R.string.legal_credits_desc));
        UiKit.button(legal, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_doc,
            getString(R.string.legal_credits_button), this::showLegalCreditsDialog);

        LinearLayout help = UiKit.card(page);
        UiKit.sectionHeader(help, R.drawable.ic_gzh_doc,
            getString(R.string.setup_card_how_it_works), false);
        UiKit.supporting(help, getString(R.string.setup_how_it_works_body));
    }

    private void showLegalCreditsDialog() {
        ScrollView scroll = new ScrollView(this);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setBackgroundColor(UiKit.color(this, R.color.gzh_background));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(UiKit.color(this, R.color.gzh_background));

        TextView enTitle = new TextView(this);
        enTitle.setText(R.string.legal_credits_title);
        enTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        enTitle.setTypeface(Typeface.DEFAULT_BOLD);
        enTitle.setTextColor(UiKit.color(this, R.color.gzh_primary));
        content.addView(enTitle);

        TextView enBody = new TextView(this);
        enBody.setText(LegalCredits.ENGLISH);
        enBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        enBody.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        enBody.setLineSpacing(0, 1.25f);
        enBody.setTextIsSelectable(true);
        enBody.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        enBody.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        LinearLayout.LayoutParams enLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        enLp.topMargin = dp(8);
        enLp.bottomMargin = dp(24);
        content.addView(enBody, enLp);

        TextView arTitle = new TextView(this);
        arTitle.setText("إشعار قانوني");
        arTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        arTitle.setTypeface(Typeface.DEFAULT_BOLD);
        arTitle.setTextColor(UiKit.color(this, R.color.gzh_primary));
        arTitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        content.addView(arTitle);

        TextView arBody = new TextView(this);
        arBody.setText(LegalCredits.ARABIC);
        arBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        arBody.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        arBody.setLineSpacing(0, 1.25f);
        arBody.setTextIsSelectable(true);
        arBody.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        arBody.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        arBody.setTextDirection(View.TEXT_DIRECTION_RTL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = dp(8);
        content.addView(arBody, arLp);

        scroll.addView(content);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.legal_credits_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    // GeneralsX @feature Android port 03/10/2026 README "Support the project", in the launcher.
    // On the Help page, next to the version, rather than on Home: Home is for getting into the game,
    // and nothing here should stand between a player and the Play button. Text, languages and
    // entries all come from the published support.json (SupportLinks), none from this APK.
    // GeneralsX @feature Flux Build 05/11/2026 Who makes Flux Build.
    static final String TELEGRAM_URL = "https://t.me/FluxBuild";

    private void openTelegram() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(TELEGRAM_URL)));
        } catch (android.content.ActivityNotFoundException e) {
            gxToast(this, getString(R.string.help_telegram_no_app, TELEGRAM_URL), Toast.LENGTH_LONG).show();
        }
    }

    private void buildCreditsSection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_heart, getString(R.string.credits_title), false);
        creditRow(card, getString(R.string.credits_role_lead), "ONA | FLUX");
        creditRow(card, getString(R.string.credits_role_codev), "ONA | HASHEM");

        // GeneralsX @feature Flux Build 06/11/2026 Pioneers: the work this build stands on (owner's
        // request). Names as their authors publish them; what each did, translated.
        LinearLayout pioneers = UiKit.card(page);
        UiKit.sectionHeader(pioneers, R.drawable.ic_gzh_globe, getString(R.string.credits_pioneers_title), false);
        UiKit.supporting(pioneers, getString(R.string.credits_pioneers_intro));
        creditRow(pioneers, getString(R.string.credits_role_mysorez), "MYSOREZ");
        creditRow(pioneers, getString(R.string.credits_role_fbraz3), "fbraz3");
        creditRow(pioneers, getString(R.string.credits_role_fighter19), "fighter19");
        creditRow(pioneers, getString(R.string.credits_role_superhackers), "TheSuperHackers");
        creditRow(pioneers, getString(R.string.credits_role_jmarshall), "jmarshall");
        creditRow(pioneers, getString(R.string.credits_role_generalsonline), "GeneralsOnline");
    }

    private void creditRow(LinearLayout card, String role, String name) {
        TextView who = UiKit.body(card, name);
        who.setTypeface(Typeface.DEFAULT_BOLD);
        who.setTextColor(UiKit.color(this, R.color.gzh_primary));
        UiKit.caption(card, role);
    }

    // GeneralsX @feature Flux Build 05/11/2026 A short beginner's guide: one collapsible card per topic.
    private void buildAcademySection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_info, getString(R.string.academy_title), false);
        UiKit.supporting(card, getString(R.string.academy_intro));
        int[][] topics = {
            { R.string.academy_basics_title, R.string.academy_basics_body },
            { R.string.academy_usa_title, R.string.academy_usa_body },
            { R.string.academy_china_title, R.string.academy_china_body },
            { R.string.academy_gla_title, R.string.academy_gla_body },
            { R.string.academy_pro_title, R.string.academy_pro_body },
        };
        for (int[] topic : topics) {
            academyTopic(card, topic[0], topic[1]);
        }

        // GeneralsX @feature Flux Build 05/11/2026 The community's match rules (Pro Rules / No Rules).
        UiKit.divider(card);
        TextView rulesHeader = UiKit.body(card, getString(R.string.academy_rules_header));
        rulesHeader.setTypeface(Typeface.DEFAULT_BOLD);
        rulesHeader.setTextColor(UiKit.color(this, R.color.gzh_primary));
        rulesHeader.setPadding(0, UiKit.dp(this, 8), 0, 0);
        int[][] rules = {
            { R.string.academy_rules_pro_title, R.string.academy_rules_pro_body },
            { R.string.academy_rules_norules_title, R.string.academy_rules_norules_body },
            { R.string.academy_rules_respect_title, R.string.academy_rules_respect_body },
        };
        for (int[] topic : rules) {
            academyTopic(card, topic[0], topic[1]);
        }
    }

    /** One collapsible Academy topic: a title row that opens and closes its formatted text. */
    private void academyTopic(LinearLayout card, int titleRes, int bodyRes) {
        UiKit.divider(card);
        TextView title = UiKit.body(card, "\u25B8  " + getString(titleRes));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMinHeight(UiKit.dp(this, 44));
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView body = UiKit.supporting(card, academyText(getString(bodyRes)));
        body.setLineSpacing(UiKit.dp(this, 3), 1f);
        body.setVisibility(View.GONE);
        title.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            title.setText((open ? "\u25BE  " : "\u25B8  ") + getString(titleRes));
        });
    }

    /**
     * Academy text markup, kept simple so translations keep it: "## " starts a bold sub-heading in the
     * accent colour, "- " a bullet, "  - " a bullet one level in; other lines are plain paragraphs.
     */
    private CharSequence academyText(String raw) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        int accent = UiKit.color(this, R.color.gzh_primary);
        int gap = UiKit.dp(this, 8);
        int flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
        for (String line : raw.split("\n")) {
            if (out.length() > 0) {
                out.append('\n');
            }
            int start = out.length();
            if (line.startsWith("## ")) {
                out.append(line.substring(3));
                out.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), start, out.length(), flags);
                out.setSpan(new android.text.style.ForegroundColorSpan(accent), start, out.length(), flags);
            } else if (line.matches("^\\s+- .*")) {
                // Indented bullet. aapt2 collapses runs of spaces in string resources, so any leading
                // whitespace counts.
                out.append("\u25E6 ").append(line.trim().substring(2));
                out.setSpan(new android.text.style.LeadingMarginSpan.Standard(gap * 3, gap * 4), start, out.length(), flags);
            } else if (line.startsWith("- ")) {
                out.append("\u2022 ").append(line.substring(2));
                out.setSpan(new android.text.style.LeadingMarginSpan.Standard(gap, gap * 2), start, out.length(), flags);
            } else {
                out.append(line);
            }
        }
        return out;
    }

    private void buildSupportSection(LinearLayout page) {
        SupportLinks support = SupportLinks.load(this);
        if (support == null) {
            return;
        }
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_heart, support.title, false);
        // GeneralsX @feature Android port 18/10/2026 The card as blocks when the file has them
        // (SupportLinks "card"): supporters, goals, buttons -- all added online.
        if (support.cardBlocks != null) {
            renderSupportBlocks(card, support.cardBlocks, support);
            return;
        }
        if (!support.body.isEmpty()) {
            UiKit.supporting(card, support.body);
        }
        for (SupportLinks.Entry e : support.entries) {
            if (e.isLink()) {
                UiKit.listRow(card, R.drawable.ic_gzh_globe, e.label,
                    e.value, () -> openSupportLink(e.value));
            } else {
                final CharSequence idle = support.copyHint.isEmpty()
                    ? e.value : e.value + "\n" + support.copyHint;
                final UiKit.Row[] row = new UiKit.Row[1];
                row[0] = UiKit.listRow(card, R.drawable.ic_gzh_copy, e.label, idle,
                    () -> copySupportAddress(row[0], idle, e.label, e.value, support.copied));
            }
        }
        if (!support.warning.isEmpty()) {
            UiKit.helpText(card, support.warning);
        }
    }

    /** Draws SupportLinks blocks into a card or a dialog's body. */
    private void renderSupportBlocks(LinearLayout parent, java.util.List<SupportLinks.Block> blocks,
                                     SupportLinks support) {
        for (SupportLinks.Block b : blocks) {
            switch (b.type) {
                case "heading": {
                    TextView t = UiKit.body(parent, b.text);
                    t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
                    break;
                }
                case "text":
                    UiKit.supporting(parent, b.text);
                    break;
                case "note":
                    UiKit.helpText(parent, b.text);
                    break;
                case "link":
                    UiKit.listRow(parent, R.drawable.ic_gzh_globe, b.text, b.value,
                        () -> openSupportLink(b.value));
                    break;
                case "button":
                    UiKit.button(parent, UiKit.BTN_TONAL, R.drawable.ic_gzh_heart, b.text,
                        () -> openSupportLink(b.value));
                    break;
                case "copy": {
                    final CharSequence idle = support.copyHint.isEmpty()
                        ? b.value : b.value + "\n" + support.copyHint;
                    final UiKit.Row[] row = new UiKit.Row[1];
                    row[0] = UiKit.listRow(parent, R.drawable.ic_gzh_copy, b.text, idle,
                        () -> copySupportAddress(row[0], idle, b.text, b.value, support.copied));
                    break;
                }
                case "rows":
                    if (!b.text.isEmpty()) {
                        TextView t = UiKit.body(parent, b.text);
                        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
                    }
                    for (String[] r : b.rows) {
                        UiKit.listRow(parent, R.drawable.ic_gzh_heart, r[0], r[1], null);
                    }
                    break;
                case "progress": {
                    UiKit.supporting(parent, b.text.isEmpty()
                        ? String.format(java.util.Locale.ROOT, "%.0f / %.0f", b.progress, b.max)
                        : b.text);
                    android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                        android.R.attr.progressBarStyleHorizontal);
                    bar.setMax(1000);
                    bar.setProgress((int) Math.min(1000, Math.round(b.progress * 1000 / b.max)));
                    bar.setProgressTintList(UiKit.tint(this, R.color.gzh_primary));
                    parent.addView(bar, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, UiKit.dp(this, 12)));
                    break;
                }
                case "divider":
                    UiKit.divider(parent);
                    break;
                default:
                    break;
            }
        }
    }

    private static final String PREF_SUPPORT_POPUP_SEEN = "support_popup_seen";

    /**
     * GeneralsX @feature Android port 18/10/2026 The window described by support.json's "popup",
     * shown once per id: the first time the launcher has it, and again only when a new id is
     * published (an announcement, a donation drive). Never over the game, never twice.
     */
    private void maybeShowSupportPopup() {
        // Disabled: Clean exclusive Flux Build without external donation popups
        return;
    }

    // GeneralsX @bugfix Android port 03/10/2026 The copy was silent: Android 13+ was trusted to
    // confirm it, and several vendor builds show nothing. The row itself now says so for a moment
    // (with a tick of haptics), and the toast is shown on every version.
    private void copySupportAddress(UiKit.Row row, CharSequence idle, String label, String value,
                                    String copiedText) {
        android.content.ClipboardManager clipboard =
            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value));
        String done = copiedText.isEmpty() ? value : copiedText.replace("%s", label);
        row.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        row.supporting.setText(value + "\n\u2713 " + done);
        row.supporting.setTextColor(UiKit.color(this, R.color.gzh_primary));
        row.root.removeCallbacks((Runnable) row.root.getTag());
        Runnable restore = () -> {
            row.supporting.setText(idle);
            row.supporting.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        };
        row.root.setTag(restore);
        row.root.postDelayed(restore, 2500);
        toast(done);
    }

    private void openSupportLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException e) {
            toast(url);
        }
    }

    // The build's own version, as the manifest carries it -- no new string
    // resource needed, and it is the first thing any bug report needs.
    private String versionLabel() {
        return BuildConfig.VERSION_NAME;
    }

    // ------------------------------------------------------------ Logs entry

    // GeneralsX @feature Flux Build 23/10/2026 Import Map moved off the Home page: it is a
    // maintenance action, not something done before every launch. The Tools tab is where the
    // other one-off actions (logs, diagnostics, replay check) already live.
    // GeneralsX @tweak Flux Build 07/11/2026 v1.4.1: Import Map and World Builder moved to Maps & Mods;
    // the replays received from other players stay with the tools.
    private void buildMapToolsSection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_play, getString(R.string.content_replays_title), false);
        // GeneralsX @feature Flux Build 05/11/2026 Replays received from other players.
        UiKit.listRow(card, R.drawable.ic_gzh_folder,
            getString(R.string.setup_tools_import_replay),
            getString(R.string.setup_tools_import_replay_desc),
            this::onImportReplay);
    }

    /**
     * GeneralsX @feature Flux Build 07/11/2026 v1.4.1 Maps & Mods: World Builder, map import, and the
     * player's own maps (<user data>/Maps/<name>/<name>.map) -- open one in World Builder or delete it.
     * Only that folder is ever touched; official maps live in the game's archives and are not listed.
     */
    private void buildMapsSection(LinearLayout page) {
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1: two map editors, side by side. World Builder
        // (EA's, native) is the default; the HTML editor stays for weak phones and simple 2D edits.
        LinearLayout editors = UiKit.card(page);
        UiKit.sectionHeader(editors, R.drawable.ic_gzh_wrench, getString(R.string.content_editors_title), false);
        UiKit.caption(editors, getString(R.string.content_editors_hint));
        if (WorldBuilderActivity.isAvailable(this)) {
            UiKit.listRow(editors, R.drawable.ic_wb_object,
                getString(R.string.content_editor_wb_title),
                getString(R.string.content_editor_wb_desc),
                () -> openWorldBuilder(null));
        }
        UiKit.listRow(editors, R.drawable.ic_gzh_doc,
            getString(R.string.content_editor_html_title),
            getString(R.string.content_editor_html_desc),
            () -> startActivity(new Intent(this, MapEditorActivity.class)));

        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_folder, getString(R.string.content_maps_title), false);
        UiKit.listRow(card, R.drawable.ic_gzh_download,
            getString(R.string.setup_tools_import_map),
            getString(R.string.setup_tools_import_map_desc),
            this::onImportMap);
    }

    /** The player's own maps (<user data>/Maps), listed off the UI thread. */
    private void buildMyMapsSection(LinearLayout page) {
        LinearLayout mine = UiKit.card(page);
        UiKit.sectionHeader(mine, R.drawable.ic_gzh_folder, getString(R.string.content_my_maps), false);
        final TextView loading = UiKit.caption(mine, getString(R.string.content_maps_loading));
        final java.lang.ref.WeakReference<LinearLayout> cardRef = new java.lang.ref.WeakReference<>(mine);
        new Thread(() -> {
            final List<File> maps = listUserMaps();
            runOnUiThread(() -> {
                LinearLayout c = cardRef.get();
                if (c == null || isFinishing() || isDestroyed() || c.getParent() == null) return;
                c.removeView(loading);
                if (maps.isEmpty()) {
                    UiKit.caption(c, getString(R.string.content_maps_none));
                    return;
                }
                for (final File folder : maps) {
                    UiKit.listRow(c, R.drawable.ic_gzh_doc, folder.getName(),
                        getString(R.string.content_map_row_desc), () -> onUserMapTapped(folder));
                }
            });
        }, "gx-list-maps").start();
    }

    /** Folders under <user data>/Maps that hold <name>.map, by name. Off the UI thread. */
    private static List<File> listUserMaps() {
        List<File> out = new ArrayList<>();
        File[] folders = new File(DataPackInstaller.userDataDir(), "Maps").listFiles();
        if (folders != null) {
            for (File f : folders) {
                if (f.isDirectory() && WorldBuilderActivity.mapFileIn(f) != null) out.add(f);
            }
        }
        java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private void onUserMapTapped(final File folder) {
        List<CharSequence> items = new ArrayList<>();
        final boolean canEdit = WorldBuilderActivity.isAvailable(this);
        if (canEdit) items.add(getString(R.string.content_map_open_wb));
        items.add(getString(R.string.content_map_delete));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(folder.getName())
            .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                if (canEdit && which == 0) {
                    File mapFile = WorldBuilderActivity.mapFileIn(folder);
                    if (mapFile != null) openWorldBuilder(mapFile.getAbsolutePath());
                } else {
                    confirmDeleteMap(folder);
                }
            })
            .show();
    }

    private void confirmDeleteMap(final File folder) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.content_map_delete)
            .setMessage(getString(R.string.content_map_delete_message, folder.getName()))
            .setPositiveButton(R.string.content_map_delete, (d, w) -> new Thread(() -> {
                boolean ok = deleteUserMapFolder(folder);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    gxToast(this, ok ? getString(R.string.content_map_deleted, folder.getName())
                        : getString(R.string.content_map_delete_failed), Toast.LENGTH_LONG).show();
                    if (currentTab == TAB_CONTENT) showTab(TAB_CONTENT);
                });
            }, "gx-delete-map").start())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Deletes one map folder, only when it is directly inside <user data>/Maps (its files, then it). */
    private static boolean deleteUserMapFolder(File folder) {
        try {
            File maps = new File(DataPackInstaller.userDataDir(), "Maps").getCanonicalFile();
            File target = folder.getCanonicalFile();
            if (!maps.equals(target.getParentFile()) || !target.isDirectory()) return false;
            File[] files = target.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) return false; // a map folder holds files only; refuse anything else
                }
                // The .map last: a deletion that fails part-way leaves the map listed, not a hidden half.
                File mapFile = WorldBuilderActivity.mapFileIn(target);
                if (mapFile == null) mapFile = new File(target, target.getName() + ".map");
                for (File f : files) {
                    if (!f.equals(mapFile) && !f.delete()) return false;
                }
                if (mapFile.exists() && !mapFile.delete()) return false;
            }
            return target.delete();
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private void openWorldBuilder(String mapPath) {
        if (getSavedGamePath(this, GAME_ZERO_HOUR) == null) {
            gxToast(this, R.string.wb_no_game_folder, Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, WorldBuilderActivity.class);
        if (mapPath != null) i.putExtra(WorldBuilderActivity.EXTRA_MAP, mapPath);
        startActivity(i);
    }

    /** Maps & Mods: the Mods screen, as on Home. */
    private void buildModsSection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_sliders, getString(R.string.content_mods_title), false);
        java.util.List<ModManager.Mod> installedMods = ModManager.listMods(this);
        int modsOn = 0;
        for (ModManager.Mod mod : installedMods) {
            if (mod.enabled) modsOn++;
        }
        UiKit.listRow(card, R.drawable.ic_gzh_folder, getString(R.string.mods_title),
            installedMods.isEmpty() ? getString(R.string.mods_home_none)
                : modsOn == 0 ? getString(R.string.mods_home_all_off, installedMods.size())
                : getString(R.string.mods_home_enabled, modsOn),
            () -> startActivityForResult(new Intent(this, ModManagerActivity.class), REQUEST_MODS));
        UiKit.caption(card, getString(R.string.content_mods_hint));
    }

    /** Interface page: side menu or visible tabs. */
    private void buildNavigationStyleSection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_menu, getString(R.string.nav_style_title), false);
        UiKit.caption(card, getString(R.string.nav_style_desc));
        CharSequence[] labels = { getString(R.string.nav_style_drawer), getString(R.string.nav_style_tabs) };
        UiKit.segmented(card, labels, useVisibleTabs(this) ? 1 : 0, index -> {
            String style = index == 1 ? NAV_STYLE_TABS : NAV_STYLE_DRAWER;
            if (style.equals(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_NAV_STYLE, NAV_STYLE_DRAWER))) return;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_NAV_STYLE, style).apply();
            // Rebuild the shell with the other navigation, staying on this page.
            contentHost.post(this::buildUi);
        });
    }

    private void onImportReplay() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQUEST_IMPORT_REPLAY);
        } catch (android.content.ActivityNotFoundException e) {
            gxToast(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * GeneralsX @feature Flux Build 05/11/2026 Copies picked replays into <user data>/Replays, where
     * the game's Load Replay screen lists them. A file must start with the engine's "GENREP" header
     * (Recorder.cpp); an existing replay of the same name is kept and the copy gets " (2)".
     */
    private void importReplayDocuments(java.util.List<Uri> uris) {
        File replays = new File(userDataDir(this), "Replays");
        new Thread(() -> {
            int imported = 0;
            int skipped = 0;
            int failed = 0;
            String lastError = null;
            if (!replays.isDirectory() && !replays.mkdirs()) {
                failed = uris.size();
                lastError = "cannot create " + replays;
            } else {
                for (Uri uri : uris) {
                    try {
                        if (importOneReplay(uri, replays)) {
                            imported++;
                        } else {
                            skipped++;
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        failed++;
                        lastError = String.valueOf(e.getMessage());
                    }
                }
            }
            final int done = imported;
            final int notReplays = skipped;
            final int errors = failed;
            final String error = lastError;
            runOnUiThread(() -> {
                StringBuilder text = new StringBuilder(getString(R.string.setup_toast_replays_imported, done));
                if (notReplays > 0) {
                    text.append("\n").append(getString(R.string.setup_toast_replays_skipped, notReplays));
                }
                if (errors > 0) {
                    text.append("\n").append(getString(R.string.setup_toast_replay_import_failed, error));
                }
                gxToast(this, text.toString(), Toast.LENGTH_LONG).show();
            });
        }, "ReplayImport").start();
    }

    /** One picked file: false when it is not a replay or no free name is left; never overwrites. */
    private boolean importOneReplay(Uri uri, File replays) throws java.io.IOException {
        String name = queryMapDisplayName(uri);
        name = name == null || name.trim().isEmpty() ? "imported.rep" : new File(name.trim()).getName();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".rep")) {
            // Messengers and cloud apps may hand over a file without its extension.
            name = name + ".rep";
        }
        String stem = name.substring(0, name.length() - 4);
        File target = new File(replays, name);
        for (int i = 2; target.exists() && i < 100; i++) {
            target = new File(replays, stem + " (" + i + ").rep");
        }
        if (target.exists()) {
            return false;
        }
        File temp = new File(replays, ".import-" + System.nanoTime() + ".tmp");
        try {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.OutputStream out = new java.io.FileOutputStream(temp)) {
                if (in == null) {
                    throw new java.io.IOException("cannot open " + name);
                }
                byte[] header = new byte[6];
                int got = 0;
                while (got < header.length) {
                    int n = in.read(header, got, header.length - got);
                    if (n < 0) {
                        break;
                    }
                    got += n;
                }
                if (got < header.length
                        || !new String(header, java.nio.charset.StandardCharsets.ISO_8859_1).equals("GENREP")) {
                    return false;
                }
                out.write(header);
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            if (!temp.renameTo(target)) {
                throw new java.io.IOException("cannot write " + target.getName());
            }
            return true;
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private void buildHotkeysAndControlBarSection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_interface_hotkeys_ui_title), false);
        UiKit.helpText(card, getString(R.string.setup_interface_hotkeys_ui_help));

        // GeneralsX @feature Flux Build 03/11/2026 The in-app editors: unit, building and power
        // buttons (labels in generals.csf), then the game's command keys (CommandMap.ini).
        UiKit.button(card, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_sliders,
            getString(R.string.unit_hotkeys_btn),
            () -> startActivity(new Intent(this, UnitHotkeyEditorActivity.class)));
        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_interface_hotkey_editor_btn),
            () -> startActivity(new Intent(this, HotkeyEditorActivity.class)));

        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_doc,
            getString(R.string.setup_interface_import_hotkeys_btn), this::onImportHotkeys);

        UiKit.button(card, UiKit.BTN_TONAL, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_interface_import_controlbar_btn), this::onImportControlBar);

        UiKit.button(card, UiKit.BTN_OUTLINE, R.drawable.ic_gzh_refresh,
            getString(R.string.setup_interface_restore_hotkeys_btn), this::onRestoreDefaultHotkeys);
    }

    // GeneralsX @feature Flux Build 23/10/2026 Graphics tab: skip the 3D menu shellmap. The
    // launcher only stores the choice; GeneralsZHActivity turns it into the engine's existing
    // -noshellmap flag, and GameEngine::init() then never mounts ShellMapMD.
    private void buildNoShellMapSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_display,
            getString(R.string.setup_card_performance), false);
        SwitchCompat noShell = UiKit.switchRow(content,
            getString(R.string.setup_switch_no_shellmap), getString(R.string.setup_switch_no_shellmap_desc));
        noShell.setChecked(isShellMapDisabled(this));
        noShell.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_NO_SHELLMAP, checked).apply();
            gxToast(this, R.string.setup_toast_no_shellmap_saved, Toast.LENGTH_SHORT).show();
        });

        // GeneralsX @feature Flux Build 06/11/2026 The two things InGameHud draws over the game.
        SwitchCompat hud = UiKit.switchRow(content,
            getString(R.string.setup_switch_perf_hud), getString(R.string.setup_switch_perf_hud_desc));
        hud.setChecked(InGameHud.perfHudEnabled(this));
        // GeneralsX @feature Flux Build 06/11/2026 What the HUD shows and how it looks; where it sits
        // and its size are set in the game (long press on it). Shown only while the HUD is on.
        final LinearLayout hudOptions = new LinearLayout(this);
        hudOptions.setOrientation(LinearLayout.VERTICAL);
        content.addView(hudOptions);
        final android.content.SharedPreferences hudPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        UiKit.caption(hudOptions, getString(R.string.setup_hud_show_label));
        final String[] showKeys = { PerfHudView.PREF_SHOW_FPS, PerfHudView.PREF_SHOW_TEMP,
            PerfHudView.PREF_SHOW_BATTERY, PerfHudView.PREF_SHOW_GRAPH };
        boolean[] shown = new boolean[showKeys.length];
        for (int i = 0; i < showKeys.length; i++) {
            shown[i] = hudPrefs.getBoolean(showKeys[i], true);
        }
        final com.google.android.material.button.MaterialButtonToggleGroup[] showGroup = { null };
        showGroup[0] = UiKit.multiToggle(hudOptions, new CharSequence[] {
                getString(R.string.setup_hud_show_fps), getString(R.string.setup_hud_show_temp),
                getString(R.string.setup_hud_show_battery), getString(R.string.setup_hud_show_graph) },
            shown, (index, checked) -> {
                hudPrefs.edit().putBoolean(showKeys[index], checked).apply();
                // Never all four off: an empty HUD would be an invisible one (the game shows FPS
                // then), so the last one stays on, and says so.
                boolean any = false;
                for (String k : showKeys) {
                    any |= hudPrefs.getBoolean(k, true);
                }
                if (!any && showGroup[0] != null) {
                    showGroup[0].check(showGroup[0].getChildAt(index).getId());
                }
            });
        UiKit.caption(hudOptions, getString(R.string.setup_hud_style_label));
        int style = hudPrefs.getInt(PerfHudView.PREF_STYLE, PerfHudView.STYLE_TACTICAL);
        UiKit.segmented(hudOptions, new CharSequence[] {
                getString(R.string.setup_hud_style_transparent), getString(R.string.setup_hud_style_solid),
                getString(R.string.setup_hud_style_rounded), getString(R.string.setup_hud_style_tactical) },
            style >= 0 && style <= 3 ? style : PerfHudView.STYLE_TACTICAL,
            index -> hudPrefs.edit().putInt(PerfHudView.PREF_STYLE, index).apply());
        UiKit.helpText(hudOptions, getString(R.string.setup_hud_edit_help));
        hudOptions.setVisibility(hud.isChecked() ? View.VISIBLE : View.GONE);
        hud.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(InGameHud.PREF_PERF_HUD, checked).apply();
            hudOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
    }

    private void buildLogsSection(LinearLayout page) {        LinearLayout card = UiKit.card(page);
        UiKit.listRow(card, R.drawable.ic_gzh_doc,
            getString(R.string.setup_button_view_logs),
            getString(R.string.setup_status_logs_note),
            this::onViewLogs);
        UiKit.divider(card);
        // GeneralsX @feature Android port 13/09/2026 Sits with the logs
        // rather than with the GeneralsOnline account screen on purpose: by
        // the time this is worth opening, the account screen is the thing
        // that is not working.
        UiKit.listRow(card, R.drawable.ic_gzh_wrench,
            getString(R.string.netdiag_title),
            getString(R.string.netdiag_row_note),
            this::onNetworkDiagnostics);
        // GeneralsX @tweak Flux Build 08/11/2026 v1.4.1 round 4 UI audit: the Replay check row is hidden.
        // Replay playback is disabled (ReplayCheckActivity.launch only says so), so every control on
        // that screen did nothing. It comes back with playback; replays are imported on Maps & Mods.
    }

    // GeneralsX @feature Android port 05/10/2026 Issue #39: a pointer instead of native touch,
    // off by default. A marker file in the app's own files dir, read natively at start
    // (GXMouseMode.h), so it holds before any game folder is chosen and for both games.
    static final String MOUSE_MODE_MARKER = "mouse_mode";

    // GeneralsX @refactor Android port 18/10/2026 Rows inside the control scheme's card, no longer a
    // card of their own: owner, a29 -- two "controls" cards read as two answers to one question. The
    // scheme says what an order does; this says whether a finger or a cursor gives it.
    private void buildMouseModeRows(LinearLayout content) {
        SwitchCompat mouse = UiKit.switchRow(content,
            getString(R.string.setup_switch_mouse_mode), getString(R.string.setup_switch_mouse_mode_desc));

        TextView warning = new TextView(this);
        warning.setText(R.string.setup_mouse_mode_warning);
        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setTextColor(0xFFFFC400); // Prominent amber/gold warning
        warning.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 4), UiKit.dp(this, 12), UiKit.dp(this, 6));
        content.addView(warning);

        final File marker = new File(getFilesDir(), MOUSE_MODE_MARKER);
        mouse.setChecked(marker.isFile());

        // GeneralsX @feature Android port 05/10/2026 Cursor size for the touchpad pointer, 50-200%,
        // shown only while the mode is on. Kept as the marker's content (GXMouseCursorPercent).
        final LinearLayout cursorBox = new LinearLayout(this);
        cursorBox.setOrientation(LinearLayout.VERTICAL);
        final TextView cursorLabel = UiKit.body(cursorBox, null);
        final Slider cursorSlider = new Slider(this);
        cursorSlider.setValueFrom(50f);
        cursorSlider.setValueTo(200f);
        cursorSlider.setStepSize(10f);
        cursorSlider.setValue(readMouseCursorPercent(marker));
        cursorSlider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        cursorSlider.setTrackActiveTintList(UiKit.tint(this, R.color.gzh_primary));
        cursorSlider.setTrackInactiveTintList(UiKit.tint(this, R.color.gzh_surface_container_highest));
        cursorSlider.setThumbTintList(UiKit.tint(this, R.color.gzh_primary));
        cursorSlider.setHaloTintList(UiKit.tint(this, R.color.gzh_ripple_primary));
        cursorLabel.setText(getString(R.string.setup_cursor_size_label, (int) cursorSlider.getValue()));
        cursorSlider.addOnChangeListener((slider, value, fromUser) -> {
            cursorLabel.setText(getString(R.string.setup_cursor_size_label, (int) value));
            if (fromUser) {
                writeMouseMode(marker, true, (int) value);
            }
        });
        cursorBox.addView(cursorSlider, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(cursorBox, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        cursorBox.setVisibility(marker.isFile() ? android.view.View.VISIBLE : android.view.View.GONE);

        mouse.setOnCheckedChangeListener((button, checked) -> {
            writeMouseMode(marker, checked, (int) cursorSlider.getValue());
            cursorBox.setVisibility(checked ? android.view.View.VISIBLE : android.view.View.GONE);
        });
        UiKit.helpText(content, getString(R.string.setup_mouse_mode_help));
    }

    private static int readMouseCursorPercent(File marker) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker))) {
            String line = r.readLine();
            int value = line == null ? 100 : Integer.parseInt(line.trim());
            return Math.max(50, Math.min(200, Math.round(value / 10f) * 10));
        } catch (java.io.IOException | NumberFormatException e) {
            return 100;
        }
    }

    private static void writeMouseMode(File marker, boolean enabled, int cursorPercent) {
        if (!enabled) {
            marker.delete();
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(Integer.toString(cursorPercent));
        } catch (java.io.IOException e) {
            android.util.Log.w("SetupActivity", "could not write the mouse mode marker", e);
        }
    }

    // GeneralsX @feature Android port 18/10/2026 The control scheme: Classic (retail) or Tactical
    // (ported from realkot's GeneralsX fork with the author's permission), or a custom mix -- every
    // behaviour also has its own switch, so there is always a choice. Written as key=value lines to
    // a file in the app's own files dir and read natively at start (GXControlScheme.h); no file is
    // the retail game.
    static final String CONTROL_SCHEME_FILE = "control_scheme";
    private static final String[] CONTROL_SCHEME_KEYS = { "attack_move", "army_buttons", "camera_high" };
    private boolean controlSchemeUpdating;

    private void buildControlSchemeSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        TextView status = UiKit.sectionHeader(content, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_card_control_scheme), true);
        final File file = new File(getFilesDir(), CONTROL_SCHEME_FILE);
        final boolean[] state = readControlScheme(file);

        CharSequence[] labels = new CharSequence[] {
            getString(R.string.setup_control_scheme_classic),
            getString(R.string.setup_control_scheme_tactical),
            getString(R.string.setup_control_scheme_custom)
        };
        final int[] descs = new int[] {
            R.string.setup_control_scheme_classic_desc,
            R.string.setup_control_scheme_tactical_desc,
            R.string.setup_control_scheme_custom_desc
        };
        final int current = controlSchemeIndex(state);
        status.setText(labels[current]);
        final com.google.android.material.button.MaterialButtonToggleGroup[] group =
            new com.google.android.material.button.MaterialButtonToggleGroup[1];
        final TextView[] desc = new TextView[1];
        final SwitchCompat[] switches = new SwitchCompat[CONTROL_SCHEME_KEYS.length];

        group[0] = UiKit.segmented(content, labels, current, index -> {
            if (controlSchemeUpdating) {
                return;
            }
            status.setText(labels[index]);
            desc[0].setText(descs[index]);
            if (index == 2) {
                return;
            }
            controlSchemeUpdating = true;
            for (int i = 0; i < switches.length; i++) {
                state[i] = index == 1;
                switches[i].setChecked(state[i]);
            }
            controlSchemeUpdating = false;
            writeControlScheme(file, state);
        });
        desc[0] = UiKit.supporting(content, getString(descs[current]));

        final int[][] texts = new int[][] {
            { R.string.setup_switch_attack_move, R.string.setup_switch_attack_move_desc },
            { R.string.setup_switch_army_buttons, R.string.setup_switch_army_buttons_desc },
            { R.string.setup_switch_camera_high, R.string.setup_switch_camera_high_desc }
        };
        for (int i = 0; i < switches.length; i++) {
            final int option = i;
            switches[i] = UiKit.switchRow(content, getString(texts[i][0]), getString(texts[i][1]));
            switches[i].setChecked(state[i]);
            switches[i].setOnCheckedChangeListener((button, checked) -> {
                if (controlSchemeUpdating) {
                    return;
                }
                state[option] = checked;
                writeControlScheme(file, state);
                int index = controlSchemeIndex(state);
                controlSchemeUpdating = true;
                group[0].check(group[0].getChildAt(index).getId());
                controlSchemeUpdating = false;
                status.setText(labels[index]);
                desc[0].setText(descs[index]);
            });
        }
        UiKit.helpText(content, getString(R.string.setup_control_scheme_help));
        UiKit.divider(content);
        buildMouseModeRows(content);
    }

    private static int controlSchemeIndex(boolean[] state) {
        boolean all = true;
        boolean none = true;
        for (boolean on : state) {
            all &= on;
            none &= !on;
        }
        return none ? 0 : (all ? 1 : 2);
    }

    private static boolean[] readControlScheme(File file) {
        boolean[] state = new boolean[CONTROL_SCHEME_KEYS.length];
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                for (int i = 0; i < CONTROL_SCHEME_KEYS.length; i++) {
                    if (CONTROL_SCHEME_KEYS[i].equals(key)) {
                        state[i] = "1".equals(line.substring(eq + 1).trim());
                    }
                }
            }
        } catch (java.io.IOException e) {
            // No file yet: the Classic scheme.
            return state;
        }
        return state;
    }

    private static void writeControlScheme(File file, boolean[] state) {
        if (controlSchemeIndex(state) == 0) {
            file.delete();
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(file, false)) {
            for (int i = 0; i < CONTROL_SCHEME_KEYS.length; i++) {
                w.write(CONTROL_SCHEME_KEYS[i] + "=" + (state[i] ? "1" : "0") + "\n");
            }
        } catch (java.io.IOException e) {
            android.util.Log.w("SetupActivity", "could not write the control scheme", e);
        }
    }

    // GeneralsX @feature Android port 13/07/2026 GitHub issue #4: in-app
    // language override for this launcher (Setup/Log Viewer/folder browser)
    // -- see LocaleHelper for why it's a manual attachBaseContext() wrap
    // rather than androidx.appcompat's per-app language API, and why
    // "System Default" needs no explicit handling.
    // GeneralsX @feature Android port 18/10/2026 The launcher's appearance (ThemeHelper): the
    // standard look, navy and gold, day, or following the phone. The game itself is not affected.
    // GeneralsX @feature Flux Build 05/11/2026 Launcher themes (ThemeHelper): five palettes, each shown
    // with its own background and accent; choosing one rebuilds the screen in it.
    private void buildThemeSection(LinearLayout page) {
        LinearLayout card = UiKit.card(page);
        UiKit.sectionHeader(card, R.drawable.ic_gzh_display, getString(R.string.setup_theme_title), false);
        int[] names = { R.string.setup_theme_tactical, R.string.setup_theme_gdi, R.string.setup_theme_nod,
            R.string.setup_theme_gla, R.string.setup_theme_desert, R.string.setup_theme_gray,
            R.string.setup_theme_amoled, R.string.setup_theme_camo, R.string.setup_theme_cyber,
            R.string.setup_theme_green };
        // Background / accent of each theme for its swatch (colors.xml, colors_flux_themes.xml).
        int[][] swatches = {
            { R.color.gzh_background, R.color.gzh_primary },
            { R.color.gzh_gdi_background, R.color.gzh_gdi_primary },
            { R.color.gzh_nod_background, R.color.gzh_nod_primary },
            { R.color.gzh_gla_background, R.color.gzh_gla_primary },
            { R.color.gzh_desert_background, R.color.gzh_desert_primary },
            { R.color.gzh_gray_background, R.color.gzh_gray_primary },
            { R.color.gzh_amoled_background, R.color.gzh_amoled_primary },
            { R.color.gzh_camo_background, R.color.gzh_camo_primary },
            { R.color.gzh_cyber_background, R.color.gzh_cyber_primary },
            { R.color.gzh_green_background, R.color.gzh_green_primary },
        };
        android.widget.RadioGroup group = new android.widget.RadioGroup(this);
        group.setOrientation(android.widget.RadioGroup.VERTICAL);
        int current = ThemeHelper.getMode(this);
        for (int i = 0; i < ThemeHelper.COUNT; i++) {
            android.widget.RadioButton option = new android.widget.RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(names[i]);
            option.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
            option.setButtonTintList(UiKit.tint(this, R.color.gzh_primary));
            option.setMinHeight(UiKit.dp(this, 44));
            android.graphics.drawable.GradientDrawable swatch = new android.graphics.drawable.GradientDrawable();
            swatch.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            swatch.setColor(getColor(swatches[i][0]));
            swatch.setStroke(UiKit.dp(this, 4), getColor(swatches[i][1]));
            int size = UiKit.dp(this, 22);
            swatch.setSize(size, size);
            option.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, swatch, null);
            option.setCompoundDrawablePadding(UiKit.dp(this, 12));
            final int mode = i;
            option.setOnClickListener(v -> {
                if (ThemeHelper.getMode(this) != mode) {
                    ThemeHelper.setMode(this, mode);
                    // onSaveInstanceState keeps the open tab across the restart.
                    recreate();
                }
            });
            group.addView(option, new android.widget.RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i == current) {
                group.check(option.getId());
            }
        }
        card.addView(group);
        UiKit.helpText(card, getString(R.string.setup_theme_help));
    }

    private void buildLanguageSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        // The current language is the value the header carries, so the card
        // answers "what is it set to" before you read a word of it.
        TextView value = UiKit.sectionHeader(content, R.drawable.ic_gzh_globe,
            getString(R.string.setup_card_language), true);
        value.setText(LocaleHelper.displayNameFor(this, LocaleHelper.getSavedLanguageTag(this)));
        // The full "Language: X" sentence is still what a screen reader hears.
        content.setContentDescription(getString(R.string.setup_language_status,
            LocaleHelper.displayNameFor(this, LocaleHelper.getSavedLanguageTag(this))));

        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_globe,
            getString(R.string.setup_button_change_language), this::onChangeLanguage);

        migrateGameTextTokenFromMarker();
        gameLanguageStatusView = UiKit.supporting(content, null);
        updateGameLanguageStatusView();

        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_globe,
            getString(R.string.setup_button_game_text_language), this::onChangeGameTextLanguage);

        languagePackButton = UiKit.button(content, UiKit.BTN_OUTLINE, R.drawable.ic_gzh_download,
            getString(R.string.setup_button_download_langpack), this::onDownloadLanguagePack);
        // GeneralsX @feature Android port 05/10/2026 The packs are made from Zero Hour's text, which
        // holds all but 20 of the base game's 2806 labels (the engine takes those 20 from the
        // game's own table), so they serve Generals too.

        UiKit.helpText(content, getString(R.string.setup_language_help));
    }

    // GeneralsX @feature Android port 13/07/2026 GitHub issue #4 follow-up:
    // status line showing whether the launcher's chosen language is also
    // being applied to the game's own text (see applyGameLanguageOverride()).
    private TextView gameLanguageStatusView;

    private void updateGameLanguageStatusView() {
        if (gameLanguageStatusView == null) {
            return;
        }
        String token = LocaleHelper.getGameTextToken(this);
        if (token == null || token.isEmpty()) {
            gameLanguageStatusView.setText(R.string.setup_game_text_status_default);
        } else {
            gameLanguageStatusView.setText(getString(R.string.setup_game_text_status,
                gameTextName(token)));
        }
    }

    // GeneralsX @feature Android port 09/09/2026 Which languages are actually installed.
    //
    // Not a fixed list of what the game might have shipped with: what is really in this
    // player's game folder right now, loose or inside a .big, as text or as the compiled
    // table. That is the list worth offering, and it is the one that answers "how do I get
    // back to English" -- English is simply one of the entries.
    // GeneralsX @feature Android port 27/09/2026 A language names itself. A pack carries its
    // name, in its own language, as the label GX:LanguageName near the top of generals.str, so
    // a pack contributed to the repository shows up as "العربية" or "فارسی" rather than as its
    // folder name, with nothing in this app to update. Packs without the label (older ones,
    // the game's own .csf) fall back to LocaleHelper's list and then to the folder name.
    private String gameTextName(String token) {
        String gamePath = getSavedGamePath();
        if (gamePath != null && token != null && !token.isEmpty()) {
            File pack = new File(new File(new File(gamePath, "data"), token), "generals.str");
            if (pack.isFile()) {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                         new java.io.InputStreamReader(new java.io.FileInputStream(pack), "UTF-8"))) {
                    boolean wanted = false;
                    String line;
                    // The label sits right after the header comment; do not read 6000 entries.
                    for (int i = 0; i < 200 && (line = r.readLine()) != null; i++) {
                        line = line.trim();
                        if (wanted) {
                            if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
                                String name = line.substring(1, line.length() - 1).trim();
                                if (!name.isEmpty()) {
                                    return name;
                                }
                            }
                            break;
                        }
                        wanted = line.equalsIgnoreCase("GX:LanguageName");
                    }
                } catch (java.io.IOException e) {
                    // Unreadable pack: the fallback name below is still right.
                }
            }
        }
        return LocaleHelper.gameTextDisplayName(token);
    }

    private java.util.List<String> installedGameTextTokens() {
        java.util.TreeSet<String> found = new java.util.TreeSet<>();
        String gamePath = getSavedGamePath();
        if (gamePath == null) {
            return new java.util.ArrayList<>(found);
        }
        File root = new File(gamePath);

        // Loose folders: data/<language>/generals.str or .csf.
        File dataDir = new File(root, "data");
        File[] languageDirs = dataDir.listFiles();
        if (languageDirs != null) {
            for (File dir : languageDirs) {
                if (dir.isDirectory()
                    && (new File(dir, "generals.str").isFile() || new File(dir, "generals.csf").isFile())) {
                    found.add(dir.getName().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }

        // And the archives, which is where the game's own language lives.
        collectArchiveLanguages(root, found);
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    collectArchiveLanguages(child, found);
                }
            }
        }
        return new java.util.ArrayList<>(found);
    }

    // GeneralsX @feature Android port 09/09/2026 Read the language names out of the archives
    // instead of checking a list of names this file happens to know.
    //
    // A hardcoded list is wrong by construction here: the first language contributed after it
    // was written -- Ukrainian -- was not in it, so it could be neither offered nor detected.
    // Whatever is filed as data/<something>/generals.str or .csf is a language, whether anyone
    // anticipated it or not.
    private static void collectArchiveLanguages(File dir, java.util.Set<String> out) {
        File[] bigs = dir.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".big"));
        if (bigs == null) {
            return;
        }
        for (File big : bigs) {
            forEachBigEntry(big, name -> {
                // data/<language>/generals.str|csf, and nothing deeper.
                if (!name.startsWith("data/") || !(name.endsWith("/generals.str") || name.endsWith("/generals.csf"))) {
                    return;
                }
                String middle = name.substring("data/".length(), name.lastIndexOf('/'));
                if (!middle.isEmpty() && middle.indexOf('/') < 0) {
                    out.add(middle);
                }
            });
        }
    }

    private void onChangeGameTextLanguage() {
        final java.util.List<String> tokens = installedGameTextTokens();
        if (tokens.isEmpty()) {
            toast(getString(R.string.setup_game_text_none_found));
            return;
        }
        // Entry 0 is always "leave the game alone", so there is a way back out of every
        // choice, including out of a pack that turned out to be a bad fit.
        final String[] labels = new String[tokens.size() + 1];
        labels[0] = getString(R.string.setup_game_text_default);
        for (int i = 0; i < tokens.size(); i++) {
            labels[i + 1] = gameTextName(tokens.get(i));
        }
        String current = LocaleHelper.getGameTextToken(this);
        int checked = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).equals(current)) {
                checked = i + 1;
                break;
            }
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setup_game_text_dialog_title)
            .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                LocaleHelper.setGameTextToken(this, which == 0 ? "" : tokens.get(which - 1));
                applyGameLanguageOverride();
                updateGameLanguageStatusView();
                dialog.dismiss();
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }

    private void onChangeLanguage() {
        String[] tags = LocaleHelper.SUPPORTED_TAGS;
        String[] labels = new String[tags.length];
        for (int i = 0; i < tags.length; i++) {
            labels[i] = LocaleHelper.displayNameFor(this, tags[i]);
        }
        String currentTag = LocaleHelper.getSavedLanguageTag(this);
        int currentIndex = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(currentTag)) {
                currentIndex = i;
                break;
            }
        }
        new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setup_language_dialog_title)
            .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> {
                LocaleHelper.setSavedLanguageTag(this, tags[which]);
                applyGameLanguageOverride();
                dialog.dismiss();
                recreate();
            })
            .setNegativeButton(R.string.common_cancel, null)
            .show();
    }

    // GeneralsX @feature Android port 13/07/2026 GitHub issue #4 follow-up:
    // if the launcher's chosen UI language has a corresponding engine
    // language-folder token (LocaleHelper.gameDataLanguageFor()) AND the
    // user's own selected game folder actually has that data
    // (data/<token>/generals.csf -- lowercase "data", matching g_csfFile
    // in SDL3Main.cpp and the Linux case-sensitivity fix it documents), we
    // write <filesDir>/game_language.cfg so SDL3Main.cpp exports
    // CNC_ZH_LANGUAGE before any engine subsystem reads it
    // (GetRegistryLanguage() in registry.cpp checks that env var first,
    // ahead of registry.ini and the BIG-file auto-detect). If the data
    // isn't present -- most commonly Russian, which Zero Hour never
    // shipped officially and only exists via the user's own fan/licensed
    // copy -- we deliberately leave the game on its own default/auto-detect
    // rather than force a language with no text to show
    // (GameTextManager::init() in GameText.cpp fails gracefully, blank UI
    // text, if the CSF is missing -- silently wrong is worse than
    // untouched). Called both when the language changes and when the game
    // folder changes, since either one can flip the answer.
    // GeneralsX @feature Android port 09/09/2026 Adopt a marker written by an older build.
    //
    // Before the game's text language became a setting of its own it was derived from the
    // launcher's UI language and written straight to game_language.cfg. Someone upgrading has
    // that file and no preference, and without this they would silently be moved to English
    // on first launch of the new version. Read the old answer once and keep it.
    private void migrateGameTextTokenFromMarker() {
        if (LocaleHelper.hasGameTextToken(this)) {
            return;
        }
        File marker = new File(getFilesDir(), "game_language.cfg");
        if (!marker.isFile()) {
            return;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker))) {
            String line = r.readLine();
            if (line != null && !line.trim().isEmpty()) {
                LocaleHelper.setGameTextToken(this, line.trim());
            }
        } catch (java.io.IOException e) {
            // Nothing to do: the player picks a language and the question answers itself.
        }
    }

    private void applyGameLanguageOverride() {
        File marker = new File(getFilesDir(), "game_language.cfg");
        // GeneralsX @feature Android port 09/09/2026 Driven by the player's explicit choice,
        // not by the launcher's UI language.
        String engineToken = LocaleHelper.getGameTextToken(this);
        boolean defaultedToEnglish = false;
        if (engineToken == null || engineToken.isEmpty()) {
            // GeneralsX @bugfix Android port 09/09/2026 Default means ENGLISH, spelled out --
            // not "say nothing and let the engine work it out".
            //
            // With no marker the engine falls back to its own auto-detect, which reads the
            // .big files present and answers with whichever language it recognises. That is
            // not a default, it is a lottery: it reported Russian on a machine whose game is
            // English, purely because a Russian archive was in the folder. Naming english
            // explicitly makes the default the same everywhere.
            //
            // It also costs nothing that anyone wants to keep. A translation .big that
            // replaces Data\English\generals.csf still wins outright -- archives mount in
            // name order, the first one wins, and that is exactly why these packs are named
            // 00Something. Asking for English is asking for "whatever is filed as English",
            // which is what a mod archive makes itself.
            engineToken = "english";
            defaultedToEnglish = true;
        }
        String gamePath = getSavedGamePath();
        if (gamePath == null && !defaultedToEnglish) {
            marker.delete();
            return;
        }
        // A language the player CHOSE has to be there, or the game would come up with no text
        // at all. English is not checked: it is the answer of last resort, and writing it is
        // how the engine is kept off its own auto-detect.
        if (!defaultedToEnglish && !gameHasTextFor(gamePath, engineToken)) {
            marker.delete();
            return;
        }
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(engineToken);
            w.write("\n");
        } catch (java.io.IOException e) {
            // Not fatal: worst case the game just keeps its own default
            // language, same as before this feature existed.
        }
    }

    // TheSuperHackers @feature Android port 07/07/2026 Menu text size scaling
    // (GlobalLanguage::adjustFontSize()) has no in-game slider yet -- the
    // Options screen lives in the user's own game data (.wnd layout), not in
    // this engine source tree, so a real in-game control can't be added from
    // here. Expose the same "ResolutionFontAdjustment" percentage here
    // instead, writing straight into the Options.ini this Android build
    // actually reads (the shared user-data dir, see optionsIniFile()) -- no need
    // to wait for the game to visit its own Options menu first.
    private Slider uiScaleSlider;
    private TextView uiScaleLabel;

    // GeneralsX @bugfix Android port 08/07/2026 A prior "Interface Size (whole
    // UI)" option here rendered at a reduced internal resolution and let the
    // pillarbox blit upscale to the full panel, expecting buttons/controlbar
    // to grow along with text. On real devices only text actually changed
    // size — widget layout geometry is itself computed as a ratio of the
    // current resolution, so shrinking that resolution and then stretching
    // the result back up is a no-op for anything but text (whose *requested
    // font point size* is a genuinely bigger asset, not just a stretched
    // rect). Removed rather than ship a slider that visibly does nothing;
    // "Menu Text Size" below is the one scaling option that actually works.

    private void buildUiScaleSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        // The live percentage reads out of the header, right-aligned in the
        // accent, instead of a separate line above the track.
        uiScaleLabel = UiKit.sectionHeader(content, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_card_text_size), true);

        int startPercent = readUiScalePercent();
        uiScaleSlider = new Slider(this);
        uiScaleSlider.setValueFrom(0f);
        uiScaleSlider.setValueTo(150f);
        uiScaleSlider.setStepSize(1f);
        uiScaleSlider.setValue(startPercent);
        // The floating bubble would show a bare untranslated number on top of
        // the value the header already spells out properly.
        uiScaleSlider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        uiScaleSlider.setTrackActiveTintList(UiKit.tint(this, R.color.gzh_primary));
        uiScaleSlider.setTrackInactiveTintList(UiKit.tint(this, R.color.gzh_surface_container_highest));
        uiScaleSlider.setThumbTintList(UiKit.tint(this, R.color.gzh_primary));
        uiScaleSlider.setHaloTintList(UiKit.tint(this, R.color.gzh_ripple_primary));
        updateUiScaleLabel(startPercent);
        uiScaleSlider.addOnChangeListener((slider, value, fromUser) -> updateUiScaleLabel((int) value));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderLp.topMargin = UiKit.dim(this, R.dimen.gzh_item_gap_tight);
        content.addView(uiScaleSlider, sliderLp);

        UiKit.button(content, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_check,
            getString(R.string.setup_button_apply_text_size), () -> {
                writeUiScalePercent((int) uiScaleSlider.getValue());
                gxToast(this, R.string.setup_toast_text_size_saved, Toast.LENGTH_LONG).show();
            });

        UiKit.helpText(content, getString(R.string.setup_text_size_help));
    }

    // GeneralsX @feature Android port 01/10/2026 Interface size. The game stretches every layout over
    // the whole screen, so on a wide phone the control bar is as wide as the screen but low, and its
    // buttons are small to touch; a lower game resolution does not change that (see the removed
    // "Interface Size" note above). This one is applied by the engine to the layouts themselves
    // (GXUiScale.h): the control bar with the radar grows up from the bottom, the generals' power
    // bar, chat, the event messages, the player list, the in-game menu and its dialogs, and the main
    // menu's buttons grow from where they sit, each only as far as the screen allows. Saved as
    // GXUiScale (percent) in Options.ini, read by SDL3Main.cpp at startup.
    private Slider interfaceScaleSlider;
    private TextView interfaceScaleLabel;

    private void buildInterfaceScaleSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        interfaceScaleLabel = UiKit.sectionHeader(content, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_card_interface_scale), true);

        int startPercent = readInterfaceScalePercent();
        interfaceScaleSlider = new Slider(this);
        interfaceScaleSlider.setValueFrom(100f);
        interfaceScaleSlider.setValueTo(200f);
        interfaceScaleSlider.setStepSize(10f);
        interfaceScaleSlider.setValue(startPercent);
        interfaceScaleSlider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        interfaceScaleSlider.setTrackActiveTintList(UiKit.tint(this, R.color.gzh_primary));
        interfaceScaleSlider.setTrackInactiveTintList(UiKit.tint(this, R.color.gzh_surface_container_highest));
        interfaceScaleSlider.setThumbTintList(UiKit.tint(this, R.color.gzh_primary));
        interfaceScaleSlider.setHaloTintList(UiKit.tint(this, R.color.gzh_ripple_primary));
        updateInterfaceScaleLabel(startPercent);
        interfaceScaleSlider.addOnChangeListener((slider, value, fromUser) -> updateInterfaceScaleLabel((int) value));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sliderLp.topMargin = UiKit.dim(this, R.dimen.gzh_item_gap_tight);
        content.addView(interfaceScaleSlider, sliderLp);

        UiKit.button(content, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_check,
            getString(R.string.setup_button_apply_interface_scale), () -> {
                writeInterfaceScalePercent((int) interfaceScaleSlider.getValue());
                gxToast(this, R.string.setup_toast_render_scale_saved, Toast.LENGTH_LONG).show();
            });

        UiKit.helpText(content, getString(R.string.setup_interface_scale_help));
    }

    private void updateInterfaceScaleLabel(int percent) {
        if (interfaceScaleLabel != null) {
            interfaceScaleLabel.setText(getString(R.string.setup_interface_scale_label, percent));
        }
    }

    private int readInterfaceScalePercent() {
        String val = readKeyValueFile(optionsIniFile()).get("GXUiScale");
        if (val != null) {
            try {
                return Math.max(100, Math.min(200, Integer.parseInt(val.trim())));
            } catch (NumberFormatException ignored) {
                // Fall through to the unscaled interface.
            }
        }
        return 100;
    }

    private void writeInterfaceScalePercent(int percent) {
        File file = optionsIniFile();
        // Seeded from DefaultOptions.ini when new, for the same reason as writeUiScalePercent().
        java.util.LinkedHashMap<String, String> prefs;
        if (!file.isFile()) {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        } else {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        }
        prefs.put("GXUiScale", String.valueOf(percent));
        writeKeyValueFile(file, prefs);
    }

    // GeneralsX @feature Android port 01/10/2026 Upscaling the way a PC game offers FSR: the game keeps
    // the screen's own resolution (and the game's Options keep showing it), and a mode picks how much
    // smaller the GPU actually renders before Snapdragon GSR 1 stretches the frame back to the
    // screen. The modes and their scales are FSR 1's. On the old Mali test phone the GPU sets the
    // frame time in heavy battles (logs-42), and its work scales with the pixel count; 75% with GSR
    // took a battle from 44 to 57 fps (logs-47). Saved as GXUpscale in Options.ini, read by
    // SDL3Main.cpp at startup and applied by the native GLES backend (DX8Wrapper::Pillarbox_Setup).
    // The first version was a percentage slider that lowered the game's Resolution itself -- the
    // same thing as picking a smaller resolution in the game's Options -- and is migrated here.
    private static final String[] UPSCALE_MODES = { "off", "ultra", "quality", "balanced", "performance" };
    private static final int[] UPSCALE_PERCENT = { 100, 77, 67, 59, 50 };
    private TextView upscaleStatus;

    private void buildRenderScaleSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_display, getString(R.string.setup_card_upscale), false);

        int current = readUpscaleMode();
        CharSequence[] labels = {
            getString(R.string.setup_upscale_off_short),
            getString(R.string.setup_upscale_ultra_short),
            getString(R.string.setup_upscale_quality_short),
            getString(R.string.setup_upscale_balanced_short),
            getString(R.string.setup_upscale_performance_short),
        };
        UiKit.segmented(content, labels, current, index -> {
            writeUpscaleMode(index);
            updateUpscaleStatus(index);
            if (mResInUse != null) refreshResolutionInUse(mResInUse); // v1.4.2 round 4: scaling method
            gxToast(this, R.string.setup_toast_render_scale_saved, Toast.LENGTH_LONG).show();
        });
        upscaleStatus = UiKit.supporting(content, "");
        updateUpscaleStatus(current);

        // GeneralsX @feature Flux Build 03/11/2026 The sharpening filter, as its own choice: Snapdragon
        // GSR on Adreno (default), plain bilinear scaling elsewhere (GXUpscaler in Options.ini, read
        // by SDL3Main.cpp and DX8Wrapper::Pillarbox_Setup).
        final boolean adreno = isAdrenoGpu();
        if (adreno) {
            com.google.android.material.materialswitch.MaterialSwitch gsr = UiKit.switchRow(content,
                getString(R.string.setup_upscale_gsr_title), getString(R.string.setup_upscale_gsr_desc));
            gsr.setChecked(!UPSCALER_BILINEAR.equals(readUpscalerFilter()));
            gsr.setOnCheckedChangeListener((button, checked) -> {
                ensureUpscalerFilter(checked ? null : UPSCALER_BILINEAR);
                if (mResInUse != null) refreshResolutionInUse(mResInUse); // v1.4.2 round 4
                gxToast(this, R.string.setup_toast_render_scale_saved, Toast.LENGTH_LONG).show();
            });
        } else {
            // GeneralsX @tweak Flux Build 08/11/2026 v1.4.1 round 4 UI audit: a switch that can never be
            // turned on is a line of text saying why (GSR needs an Adreno GPU; bilinear is used).
            UiKit.caption(content, getString(R.string.setup_upscale_gsr_requires_adreno));
        }

        UiKit.helpText(content, getString(R.string.setup_upscale_help));
    }

    private static final String UPSCALER_BILINEAR = "bilinear";

    /** GXUpscaler from Options.ini: "bilinear", or null for the default (Snapdragon GSR). */
    private String readUpscalerFilter() {
        String val = readKeyValueFile(optionsIniFile()).get("GXUpscaler");
        return val == null || val.trim().isEmpty() ? null : val.trim();
    }

    /** Sets GXUpscaler (null removes it), writing Options.ini only when the value changes. */
    private void ensureUpscalerFilter(String value) {
        String now = readUpscalerFilter();
        if (value == null ? now == null : value.equals(now)) {
            return;
        }
        File file = optionsIniFile();
        java.util.LinkedHashMap<String, String> prefs = new java.util.LinkedHashMap<>(
            readKeyValueFile(file.isFile() ? file : defaultOptionsIniFile()));
        if (value == null) {
            prefs.remove("GXUpscaler");
        } else {
            prefs.put("GXUpscaler", value);
        }
        writeKeyValueFile(file, prefs);
    }

    // The screen's real (landscape) pixel size: the game's resolution with the upscaler on.
    private int[] screenSize() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return new int[] { Math.max(dm.widthPixels, dm.heightPixels) & ~1, Math.min(dm.widthPixels, dm.heightPixels) };
    }

    private void updateUpscaleStatus(int index) {
        if (upscaleStatus == null) {
            return;
        }
        int[] full = screenSize();
        if (index <= 0) {
            upscaleStatus.setText(getString(R.string.setup_upscale_status_off, full[0], full[1]));
            return;
        }
        int[] names = { 0, R.string.setup_upscale_ultra, R.string.setup_upscale_quality,
            R.string.setup_upscale_balanced, R.string.setup_upscale_performance };
        int percent = UPSCALE_PERCENT[index];
        upscaleStatus.setText(getString(R.string.setup_upscale_status, getString(names[index]),
            (full[0] * percent / 100) & ~1, (full[1] * percent / 100) & ~1, full[0], full[1]));
    }

    private int readUpscaleMode() {
        String val = readKeyValueFile(optionsIniFile()).get("GXUpscale");
        if (val != null) {
            for (int i = 0; i < UPSCALE_MODES.length; i++) {
                if (UPSCALE_MODES[i].equals(val.trim())) {
                    return i;
                }
            }
        }
        return 0;
    }

    private void writeUpscaleMode(int index) {
        File file = optionsIniFile();
        // Seeded from DefaultOptions.ini when new, for the same reason as writeUiScalePercent().
        java.util.LinkedHashMap<String, String> prefs;
        if (!file.isFile()) {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        } else {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        }
        prefs.put("GXUpscale", UPSCALE_MODES[Math.max(0, Math.min(UPSCALE_MODES.length - 1, index))]);
        // The previous slider's keys. It lowered Resolution itself; that goes back to the screen's
        // size (from the screen's full size, not the window's: Android hands the game a window
        // without the display cutout first, logs-44), so the game runs at the screen's resolution
        // again and only the upscaler renders below it.
        if (prefs.remove("GXRenderScale") != null) {
            int[] full = screenSize();
            prefs.put("Resolution", full[0] + " " + full[1]);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_RES_WRITTEN, full[0] + "x" + full[1]).apply();
        }
        // GeneralsX @bugfix Flux Build 03/11/2026 The filter is its own setting now (the GSR switch
        // below the modes); a mode change keeps it, and non-Adreno GPUs keep bilinear.
        if (!isAdrenoGpu()) {
            prefs.put("GXUpscaler", UPSCALER_BILINEAR);
        }
        writeKeyValueFile(file, prefs);
    }

    private void updateUiScaleLabel(int percent) {
        if (uiScaleLabel != null) {
            uiScaleLabel.setText(getString(R.string.setup_text_size_label, percent));
        }
    }

    // GeneralsX @feature Android port render-backend picker 07/09/2026 -
    // three render backends this branch supports, replacing the previous
    // adb-only GENERALSX_RENDER_BACKEND/GENERALSX_GLES_ANGLE env vars with a
    // real in-app choice (most users have no adb at all). Read/written the
    // same way as CUSTOM_DRIVER_CFG_NAME above: a plain-text marker file in
    // getFilesDir(), one line, read natively by SDL3Main.cpp's
    // UseVulkanBackend()/UseANGLE() (and duplicated in gles_pipeline.cpp's
    // initContext() -- see its comment for why) before the game's video
    // subsystem starts.
    private static final String RENDER_BACKEND_CFG_NAME = "render_backend.cfg";
    private static final String RENDER_BACKEND_VULKAN = "vulkan";
    private static final String RENDER_BACKEND_GLES = "gles";
    private static final String RENDER_BACKEND_GLES_ANGLE = "gles_angle";

    private TextView renderBackendStatusView;

    // No config file yet (fresh install) means "whatever UseVulkanBackend()/
    // UseANGLE() default to today when their env vars are unset" -- GLES,
    // per SDL3Main.cpp's own comment on why this branch defaults there.
    // Deliberately NOT auto-detecting "the best backend for this device"
    // here: preserves today's actual behavior for existing installs instead
    // of silently switching anyone's renderer the next time Setup runs.
    private String getRenderBackendChoice() {
        File cfg = new File(getFilesDir(), RENDER_BACKEND_CFG_NAME);
        if (!cfg.isFile()) {
            return RENDER_BACKEND_GLES;
        }
        String value = readFirstLine(cfg);
        if (RENDER_BACKEND_VULKAN.equals(value) || RENDER_BACKEND_GLES_ANGLE.equals(value)) {
            return value;
        }
        return RENDER_BACKEND_GLES;
    }

    // GeneralsX @feature Android port 06/09/2026 The GPU's own name, as its driver
    // reports it. This is the one piece of information the render-backend choice
    // actually turns on -- every Vulkan corruption report on this project so far has
    // been a driver family, not a device model (PowerVR BXM, Samsung Xclipse) -- and
    // until now the launcher asked the user to choose without showing it to them.
    //
    // Deliberately shown rather than acted on. Auto-switching the renderer from a
    // string match would be guesswork wearing a confident face: the same family can
    // ship a fixed driver in a later Android release, and silently moving someone off
    // a backend that works for them is worse than letting them read the name and
    // decide. See getRenderBackendChoice()'s comment on the same principle.
    private static String cachedGpuName;

    private boolean isAdrenoGpu() {
        String gpu = detectGpuName();
        return gpu != null && gpu.toLowerCase(Locale.US).contains("adreno");
    }

    private String detectGpuName() {
        if (cachedGpuName != null) {
            return cachedGpuName;
        }
        cachedGpuName = "";
        android.opengl.EGLDisplay display = android.opengl.EGL14.EGL_NO_DISPLAY;
        android.opengl.EGLContext context = android.opengl.EGL14.EGL_NO_CONTEXT;
        android.opengl.EGLSurface surface = android.opengl.EGL14.EGL_NO_SURFACE;
        try {
            display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
            if (display == android.opengl.EGL14.EGL_NO_DISPLAY) {
                return cachedGpuName;
            }
            int[] version = new int[2];
            if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) {
                return cachedGpuName;
            }
            int[] cfgAttribs = {
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] == 0) {
                return cachedGpuName;
            }
            int[] ctxAttribs = { android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE };
            context = android.opengl.EGL14.eglCreateContext(display, configs[0],
                android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (context == android.opengl.EGL14.EGL_NO_CONTEXT) {
                return cachedGpuName;
            }
            int[] surfAttribs = {
                android.opengl.EGL14.EGL_WIDTH, 1,
                android.opengl.EGL14.EGL_HEIGHT, 1,
                android.opengl.EGL14.EGL_NONE
            };
            surface = android.opengl.EGL14.eglCreatePbufferSurface(display, configs[0], surfAttribs, 0);
            if (surface == android.opengl.EGL14.EGL_NO_SURFACE) {
                return cachedGpuName;
            }
            if (!android.opengl.EGL14.eglMakeCurrent(display, surface, surface, context)) {
                return cachedGpuName;
            }
            String renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            if (renderer != null && !renderer.isEmpty()) {
                cachedGpuName = renderer;
            }
        } catch (Throwable t) {
            // A launcher must never fail to open because a driver misbehaved while
            // being asked its own name.
            cachedGpuName = "";
        } finally {
            try {
                if (display != android.opengl.EGL14.EGL_NO_DISPLAY) {
                    android.opengl.EGL14.eglMakeCurrent(display, android.opengl.EGL14.EGL_NO_SURFACE,
                        android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                    if (surface != android.opengl.EGL14.EGL_NO_SURFACE) {
                        android.opengl.EGL14.eglDestroySurface(display, surface);
                    }
                    if (context != android.opengl.EGL14.EGL_NO_CONTEXT) {
                        android.opengl.EGL14.eglDestroyContext(display, context);
                    }
                    android.opengl.EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) {
                // nothing useful to do here
            }
        }
        return cachedGpuName;
    }

    // Short, segment-sized versions of the same three names. All three are
    // brand names, so every locale carries the same text -- they exist as
    // string resources rather than literals so a locale that needs to
    // transliterate them (Arabic, Farsi, Korean) can.
    private String shortRenderBackendLabel(String choice) {
        switch (choice) {
            case RENDER_BACKEND_VULKAN:
                return getString(R.string.setup_render_backend_vulkan_short);
            case RENDER_BACKEND_GLES_ANGLE:
                return getString(R.string.setup_render_backend_gles_angle_short);
            default:
                return getString(R.string.setup_render_backend_gles_short);
        }
    }

    private String renderBackendLabel(String choice) {
        switch (choice) {
            case RENDER_BACKEND_VULKAN:
                return getString(R.string.setup_render_backend_vulkan);
            case RENDER_BACKEND_GLES_ANGLE:
                return getString(R.string.setup_render_backend_gles_angle);
            default:
                return getString(R.string.setup_render_backend_gles);
        }
    }

    // Default first: the order is a recommendation in itself, and Vulkan has
    // not been the default since this branch started shipping GLES builds.
    private static final String[] RENDER_BACKEND_CHOICES = {
        RENDER_BACKEND_GLES, RENDER_BACKEND_GLES_ANGLE, RENDER_BACKEND_VULKAN
    };

    // GeneralsX @tweak Android port 18/10/2026 Vulkan (DXVK) renders black textures since the GLES
    // work of 1.4.0, so it is hidden: the picker offers OpenGL ES and ANGLE, and Vulkan comes back
    // only through Tools -> Diagnostics -> "Experimental Vulkan renderer". DXVK stays in the APK
    // (its libraries are part of requires_libs, and Vulkan is to return once fixed).
    private static final String[] RENDER_BACKEND_CHOICES_NO_VULKAN = {
        RENDER_BACKEND_GLES, RENDER_BACKEND_GLES_ANGLE
    };
    private static final String PREF_VULKAN_UNLOCKED = "vulkan_unlocked";
    private static final String PREF_VULKAN_MOVED = "vulkan_moved_to_gles";

    private boolean isVulkanUnlocked() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_VULKAN_UNLOCKED, false);
    }

    private String[] renderBackendChoices() {
        return isVulkanUnlocked() ? RENDER_BACKEND_CHOICES : RENDER_BACKEND_CHOICES_NO_VULKAN;
    }

    /**
     * Once per install: a player still on Vulkan is moved to OpenGL ES and told why, rather than
     * left looking at black textures without knowing that the renderer is the cause.
     */
    private void moveOffVulkanOnce() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_VULKAN_MOVED, false)) {
            return;
        }
        prefs.edit().putBoolean(PREF_VULKAN_MOVED, true).apply();
        if (!RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice()) || isVulkanUnlocked()) {
            return;
        }
        if (!writeRenderBackend(RENDER_BACKEND_GLES)) {
            return;
        }
        if (contentHost != null) {
            showTab(currentTab);
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_card_render_backend)
            .setMessage(getString(R.string.setup_vulkan_moved_message, getString(R.string.nav_tab_tools),
                getString(R.string.setup_card_diagnostics), getString(R.string.setup_switch_vulkan_experimental)))
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private boolean writeRenderBackend(String choice) {
        File cfg = new File(getFilesDir(), RENDER_BACKEND_CFG_NAME);
        try (java.io.FileWriter w = new java.io.FileWriter(cfg, false)) {
            w.write(choice);
            w.write("\n");
            return true;
        } catch (java.io.IOException e) {
            gxToast(this, getString(R.string.setup_toast_render_backend_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    // GeneralsX @feature Android port launcher-ui-2026 08/09/2026 The three
    // backends were behind a "Change Render Backend" button that opened a
    // single-choice dialog -- two taps and a modal to see what you already
    // had selected. They are three fixed, mutually exclusive choices, which
    // is exactly what a segmented button row is for: the current one is
    // visible without touching anything, and switching is one tap.
    //
    // The dialog's own strings (setup_button_change_render_backend,
    // setup_render_backend_dialog_title) are deliberately left in the string
    // resources: they are still translated in every locale and the dialog is
    // one commit away if this ever needs to go back.
    // GeneralsX @feature Flux Build 23/10/2026 Hardware profile. The first launch writes it once;
    // this card shows what was detected and offers a deliberate re-run.
    private void buildAutoConfigSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_sliders,
            getString(R.string.setup_auto_config_title), false);

        DeviceAutoConfig.Result profile = DeviceAutoConfig.detect(this);
        String device = getString(R.string.setup_auto_config_device, profile.ramGigabytes,
            profile.cpuCores, profile.screenWidth, profile.screenHeight);
        UiKit.supporting(content, device);
        UiKit.supporting(content, getString(R.string.setup_auto_config_desc,
            profile.targetWidth, profile.targetHeight, lodLabel(profile.lod)));

        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_refresh,
            getString(R.string.setup_auto_config_button), () -> {
                DeviceAutoConfig.Result applied = applyAutoConfiguration(true);
                if (applied == null) {
                    return;
                }
                gxToast(this, getString(R.string.setup_auto_config_toast,
                    applied.targetWidth, applied.targetHeight, lodLabel(applied.lod)),
                    Toast.LENGTH_LONG).show();
                if (contentHost != null) {
                    showTab(currentTab);
                }
            });
        UiKit.helpText(content, getString(R.string.setup_auto_config_help));
    }

    private String lodLabel(String lod) {
        if ("High".equals(lod)) {
            return getString(R.string.setup_auto_config_lod_high);
        }
        if ("Medium".equals(lod)) {
            return getString(R.string.setup_auto_config_lod_medium);
        }
        return getString(R.string.setup_auto_config_lod_low);
    }

    private void buildSimRateSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        TextView status = UiKit.sectionHeader(content, R.drawable.ic_gzh_chip,
            getString(R.string.setup_card_sim_rate), true);

        final int current = getSimHz(this);
        status.setText(getString(current == SIM_HZ_CROSSPLAY
            ? R.string.setup_sim_rate_60_short
            : R.string.setup_sim_rate_30_short));

        CharSequence[] labels = new CharSequence[] {
            getString(R.string.setup_sim_rate_30_short),
            getString(R.string.setup_sim_rate_60_short)
        };
        UiKit.segmented(content, labels, current == SIM_HZ_CROSSPLAY ? 1 : 0, index -> {
            int picked = index == 1 ? SIM_HZ_CROSSPLAY : SIM_HZ_RETAIL;
            if (picked == getSimHz(this)) {
                return;
            }
            setSimHz(this, picked);
            status.setText(getString(picked == SIM_HZ_CROSSPLAY
                ? R.string.setup_sim_rate_60_short
                : R.string.setup_sim_rate_30_short));
            gxToast(this, R.string.setup_toast_sim_rate_saved, Toast.LENGTH_LONG).show();
        });

        UiKit.supporting(content, getString(current == SIM_HZ_CROSSPLAY
            ? (isGeneralsSelected(this) ? R.string.setup_sim_rate_60_desc_generals : R.string.setup_sim_rate_60_desc)
            : (isGeneralsSelected(this) ? R.string.setup_sim_rate_30_desc_generals : R.string.setup_sim_rate_30_desc)));
        UiKit.helpText(content, getString(R.string.setup_sim_rate_help));
    }

    private void buildRenderBackendSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        renderBackendStatusView = UiKit.sectionHeader(content, R.drawable.ic_gzh_display,
            getString(R.string.setup_card_render_backend), true);
        renderBackendStatusView.setText(shortRenderBackendLabel(getRenderBackendChoice()));
        renderBackendStatusView.setContentDescription(getString(R.string.setup_render_backend_status,
            renderBackendLabel(getRenderBackendChoice())));

        String current = getRenderBackendChoice();
        final String[] choices = renderBackendChoices();
        int currentIndex = 0;
        CharSequence[] labels = new CharSequence[choices.length];
        for (int i = 0; i < choices.length; i++) {
            labels[i] = shortRenderBackendLabel(choices[i]);
            if (choices[i].equals(current)) {
                currentIndex = i;
            }
        }
        final String initial = current;
        com.google.android.material.button.MaterialButtonToggleGroup group =
            UiKit.segmented(content, labels, currentIndex, index -> {
            String picked = choices[index];
            if (picked.equals(initial)) {
                return;  // programmatic/no-op selection: nothing to save
            }
            onPickRenderBackend(picked);
        });
        // Keeps the picker's own (translated) prompt as what a screen reader
        // announces for the row of three brand names.
        group.setContentDescription(getString(R.string.setup_render_backend_dialog_title));

        // The full sentence stays available under the segments: the short
        // segment labels are brand names, this is the one that says which is
        // the default and what "Current:" means.
        UiKit.supporting(content, getString(R.string.setup_render_backend_status,
            renderBackendLabel(current)));

        String gpu = detectGpuName();
        if (!gpu.isEmpty()) {
            UiKit.chip(content, R.drawable.ic_gzh_chip,
                getString(R.string.setup_render_backend_gpu, gpu),
                R.color.gzh_on_surface, R.color.gzh_surface_container_high);
        }

        // GeneralsX @tweak Android port 18/10/2026 The Vulkan paragraphs only while Vulkan is offered
        // (RENDER_BACKEND_CHOICES_NO_VULKAN): explaining a choice that is not on screen confuses.
        UiKit.helpText(content, isVulkanUnlocked()
            ? getString(R.string.setup_render_backend_help) + "\n\n" + getString(R.string.setup_render_backend_help_vulkan)
            : getString(R.string.setup_render_backend_help));
    }

    private void onPickRenderBackend(String choice) {
        if (!writeRenderBackend(choice)) {
            return;
        }
        gxToast(this, R.string.setup_toast_render_backend_saved, Toast.LENGTH_LONG).show();
        // The Custom Vulkan Driver / DXVK Config cards are only relevant for
        // the Vulkan backend -- rebuilding this one page shows/hides them
        // immediately, without the full recreate() (and the jump back to
        // Home) the old dialog needed.
        showTab(TAB_GRAPHICS);
    }

    // GeneralsX @feature Android port 10/07/2026 Optional custom Vulkan
    // driver (e.g. Mesa Turnip for Adreno GPUs), loaded natively via
    // libadrenotools -- see TryLoadCustomVulkanDriver() in SDL3Main.cpp.
    // Package format matches the convention Winlator/AetherSX2/PPSSPP all
    // use: a .zip containing meta.json (schemaVersion/name/description/
    // author/packageVersion/vendor/driverVersion/minApi/libraryName) plus
    // the driver .so (and any dependency .so's) alongside it. We never
    // bundle a driver ourselves -- the user supplies one (e.g. from
    // K11MCH1/AdrenoToolsDrivers or The412Banner/Banners-Turnip on GitHub),
    // matching every other app that uses this technique.
    private static final String CUSTOM_DRIVER_DIR_NAME = "custom_driver";
    private static final String CUSTOM_DRIVER_CFG_NAME = "custom_driver.cfg";
    // GeneralsX @feature Android port 13/07/2026 Marks that custom_driver.cfg
    // was populated by applyRecommendedDriverIfNeeded() rather than by the
    // user importing their own .zip -- lets the status text and the
    // "reset" button distinguish "we picked this for you" from "you chose
    // this", without changing anything on the native loading side (both
    // cases are the same custom_driver.cfg/custom_driver/ that
    // TryLoadCustomVulkanDriver() in SDL3Main.cpp already reads).
    private static final String CUSTOM_DRIVER_AUTO_MARKER_NAME = "custom_driver.auto";
    // Bundled fallback driver (staged by scripts/build/android/fetch-turnip.sh
    // into this asset folder at build time) -- see applyRecommendedDriverIfNeeded().
    private static final String DEFAULT_DRIVER_ASSET_DIR = "default_driver";
    private static final int REQUEST_IMPORT_DRIVER = 1002;
    private static final int REQUEST_MODS = 1004;
    private static final int REQUEST_PICK_BASE_GENERALS = 1003;
    private static final int REQUEST_IMPORT_MAP = 1005;
    private static final int REQUEST_IMPORT_HOTKEYS = 1006;
    private static final int REQUEST_IMPORT_CONTROLBAR = 1007;
    private static final int REQUEST_IMPORT_REPLAY = 1008;

    private TextView customDriverStatusView;

    private void buildCustomDriverSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_chip,
            getString(R.string.setup_card_vulkan_driver), false);

        customDriverStatusView = UiKit.supporting(content, customDriverStatusText());

        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_download,
            getString(R.string.setup_button_import_driver), this::onImportCustomDriver);
        UiKit.button(content, UiKit.BTN_DANGER, R.drawable.ic_gzh_refresh,
            getString(R.string.setup_button_reset_driver), this::onClearCustomDriver);

        UiKit.helpText(content, getString(R.string.setup_driver_help));
    }

    private String customDriverStatusText() {
        File cfg = new File(getFilesDir(), CUSTOM_DRIVER_CFG_NAME);
        if (!cfg.isFile()) {
            return getString(R.string.setup_driver_status_none);
        }
        String driverName = readFirstLine(cfg);
        boolean isAuto = new File(getFilesDir(), CUSTOM_DRIVER_AUTO_MARKER_NAME).isFile();
        String name = driverName != null ? driverName : getString(R.string.setup_driver_unknown);
        return getString(isAuto ? R.string.setup_driver_status_auto : R.string.setup_driver_status_manual, name);
    }

    // GeneralsX @feature Android port 13/07/2026 Auto-applies the bundled
    // Turnip driver on Adreno phones whose stock driver reports less than
    // Vulkan 1.3, without touching anything if the user already imported
    // their own driver or the phone doesn't need help. Called on every
    // Setup launch (cheap no-op once satisfied) and again from
    // onClearCustomDriver() so "reset" actually restores the recommended
    // state instead of just going blank.
    private void applyRecommendedDriverIfNeeded() {
        try {
            if (new File(getFilesDir(), CUSTOM_DRIVER_CFG_NAME).isFile()) {
                return;  // already configured (auto or user) -- leave it alone
            }
            if (deviceReportsVulkan13()) {
                return;  // stock driver already handles what DXVK needs
            }
            File destDir = new File(getFilesDir(), CUSTOM_DRIVER_DIR_NAME);
            deleteRecursive(destDir);
            if (!copyDriverAssetTree(DEFAULT_DRIVER_ASSET_DIR, destDir)) {
                return;  // no bundled driver in this build -- nothing to apply
            }
            File metaFile = new File(destDir, "meta.json");
            if (!metaFile.isFile()) {
                deleteRecursive(destDir);
                return;
            }
            String libraryName;
            try {
                org.json.JSONObject meta = new org.json.JSONObject(readWholeFile(metaFile));
                libraryName = meta.optString("libraryName", "");
            } catch (Exception e) {
                libraryName = "";
            }
            if (libraryName.isEmpty() || !new File(destDir, libraryName).isFile()) {
                deleteRecursive(destDir);
                return;
            }
            File cfg = new File(getFilesDir(), CUSTOM_DRIVER_CFG_NAME);
            try (java.io.FileWriter w = new java.io.FileWriter(cfg, false)) {
                w.write(libraryName);
                w.write("\n");
            }
            new File(getFilesDir(), CUSTOM_DRIVER_AUTO_MARKER_NAME).createNewFile();
        } catch (Exception e) {
            // Never let driver auto-selection take Setup down with it --
            // worst case the phone's stock driver loads, same as before
            // this feature existed.
        }
    }

    // FEATURE_VULKAN_HARDWARE_VERSION's reported "version" is a Vulkan
    // version int using the same VK_MAKE_API_VERSION encoding as the C API;
    // VK_API_VERSION_1_3 is (1<<22)|(3<<12) = 0x00403000. No feature entry
    // at all (some devices/emulators) is treated as "can't confirm 1.3" so
    // the recommended driver still gets a chance to help.
    private boolean deviceReportsVulkan13() {
        PackageManager pm = getPackageManager();
        FeatureInfo[] features = pm.getSystemAvailableFeatures();
        if (features == null) {
            return false;
        }
        for (FeatureInfo fi : features) {
            if (fi.name != null && fi.name.equals(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) {
                return fi.version >= 0x00403000;
            }
        }
        return false;
    }

    // Recursively copies an assets/ subtree (raw files, not a zip) into
    // destDir. Returns false if the source asset folder doesn't exist/is
    // empty -- lets callers tell "not bundled in this build" apart from a
    // real I/O failure without throwing.
    private boolean copyDriverAssetTree(String assetDir, File destDir) {
        AssetManager assets = getAssets();
        String[] children;
        try {
            children = assets.list(assetDir);
        } catch (java.io.IOException e) {
            return false;
        }
        if (children == null || children.length == 0) {
            return false;
        }
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            return false;
        }
        for (String child : children) {
            String childAssetPath = assetDir + "/" + child;
            File childDest = new File(destDir, child);
            try {
                String[] grandchildren = assets.list(childAssetPath);
                if (grandchildren != null && grandchildren.length > 0) {
                    if (!copyDriverAssetTree(childAssetPath, childDest)) {
                        return false;
                    }
                    continue;
                }
            } catch (java.io.IOException e) {
                return false;
            }
            try (java.io.InputStream in = assets.open(childAssetPath);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(childDest)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } catch (java.io.IOException e) {
                return false;
            }
        }
        return true;
    }

    private void refreshCustomDriverStatus() {
        if (customDriverStatusView != null) {
            customDriverStatusView.setText(customDriverStatusText());
        }
    }

    private void onImportCustomDriver() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip", "application/x-zip-compressed", "application/octet-stream"});
        try {
            startActivityForResult(intent, REQUEST_IMPORT_DRIVER);
        } catch (Exception e) {
            gxToast(this, getString(R.string.setup_toast_no_file_picker, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void onClearCustomDriver() {
        new File(getFilesDir(), CUSTOM_DRIVER_CFG_NAME).delete();
        new File(getFilesDir(), CUSTOM_DRIVER_AUTO_MARKER_NAME).delete();
        deleteRecursive(new File(getFilesDir(), CUSTOM_DRIVER_DIR_NAME));
        applyRecommendedDriverIfNeeded();
        refreshCustomDriverStatus();
        boolean autoApplied = new File(getFilesDir(), CUSTOM_DRIVER_AUTO_MARKER_NAME).isFile();
        gxToast(this, autoApplied
            ? R.string.setup_toast_reset_auto
            : R.string.setup_toast_reset_stock,
            Toast.LENGTH_SHORT).show();
    }

    // customDriverDir passed to adrenotools_open_libvulkan() MUST NOT be on
    // sdcard/external storage (dlopen refuses world-writable paths) -- unzip
    // straight into getFilesDir() (app-private internal storage), the same
    // directory SDL_GetAndroidInternalStoragePath() resolves to in native code.
    private void importCustomDriver(Uri uri) {
        File destDir = new File(getFilesDir(), CUSTOM_DRIVER_DIR_NAME);
        File tmpDir = new File(getFilesDir(), CUSTOM_DRIVER_DIR_NAME + ".tmp");
        deleteRecursive(tmpDir);
        if (!tmpDir.mkdirs()) {
            gxToast(this, R.string.setup_toast_driver_import_no_tmp, Toast.LENGTH_LONG).show();
            return;
        }

        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(in)) {
            java.util.zip.ZipEntry entry;
            byte[] buf = new byte[8192];
            String tmpCanonical = tmpDir.getCanonicalPath();
            while ((entry = zip.getNextEntry()) != null) {
                File out = new File(tmpDir, entry.getName()).getCanonicalFile();
                // Zip-slip guard: never let an archive entry write outside tmpDir.
                if (!out.getPath().equals(tmpCanonical) && !out.getPath().startsWith(tmpCanonical + File.separator)) {
                    throw new java.io.IOException("zip entry escapes target folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                    int n;
                    while ((n = zip.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                    }
                }
            }
        } catch (Exception e) {
            deleteRecursive(tmpDir);
            gxToast(this, getString(R.string.setup_toast_driver_import_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        File metaFile = findFileByName(tmpDir, "meta.json");
        if (metaFile == null) {
            deleteRecursive(tmpDir);
            gxToast(this, R.string.setup_toast_driver_import_no_meta, Toast.LENGTH_LONG).show();
            return;
        }

        String libraryName;
        try {
            org.json.JSONObject meta = new org.json.JSONObject(readWholeFile(metaFile));
            libraryName = meta.optString("libraryName", "");
            if (libraryName.isEmpty()) {
                throw new org.json.JSONException("meta.json has no libraryName");
            }
            if (!new File(metaFile.getParentFile(), libraryName).isFile()) {
                throw new org.json.JSONException("meta.json names '" + libraryName + "' but that file isn't in the package");
            }
        } catch (Exception e) {
            deleteRecursive(tmpDir);
            gxToast(this, getString(R.string.setup_toast_driver_import_bad_meta, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        // The driver .so + meta.json might be nested inside a subfolder of
        // the zip -- move THAT folder into place as custom_driver/ (not
        // tmpDir itself), so the native side's customDriverDir points at
        // exactly the folder containing libraryName.
        File driverSourceDir = metaFile.getParentFile();
        deleteRecursive(destDir);
        boolean moved = driverSourceDir.renameTo(destDir);
        deleteRecursive(tmpDir);  // no-op if driverSourceDir WAS tmpDir (already moved away)
        if (!moved) {
            gxToast(this, R.string.setup_toast_driver_import_no_finalize, Toast.LENGTH_LONG).show();
            return;
        }

        File cfg = new File(getFilesDir(), CUSTOM_DRIVER_CFG_NAME);
        try (java.io.FileWriter w = new java.io.FileWriter(cfg, false)) {
            w.write(libraryName);
            w.write("\n");
        } catch (java.io.IOException e) {
            gxToast(this, getString(R.string.setup_toast_driver_config_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        // This is an explicit user choice, not the auto-selected default --
        // see applyRecommendedDriverIfNeeded() / CUSTOM_DRIVER_AUTO_MARKER_NAME.
        new File(getFilesDir(), CUSTOM_DRIVER_AUTO_MARKER_NAME).delete();

        refreshCustomDriverStatus();
        gxToast(this, getString(R.string.setup_toast_driver_imported, libraryName), Toast.LENGTH_LONG).show();
    }

    private void onImportMap() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_MAP);
        } catch (android.content.ActivityNotFoundException e) {
            gxToast(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void importMapDocument(Uri uri) {
        if (uri == null) return;
        try {
            String name = queryMapDisplayName(uri);
            if (name == null || name.isEmpty()) {
                name = "custom_map.map";
            }
            File mapsDir = new File(DataPackInstaller.userDataDir(), "Maps");
            if (!mapsDir.isDirectory() && !mapsDir.mkdirs()) {
                gxToast(this, R.string.setup_toast_maps_dir_failed, Toast.LENGTH_LONG).show();
                return;
            }

            String lower = name.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".map")) {
                String baseName = name.substring(0, name.length() - 4);
                File targetDir = new File(mapsDir, baseName);
                if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
                    gxToast(this, R.string.setup_toast_map_folder_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                File targetFile = new File(targetDir, name);
                try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(targetFile)) {
                    if (in == null) throw new java.io.IOException("Cannot open input stream");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                gxToast(this, getString(R.string.setup_toast_map_imported, baseName), Toast.LENGTH_LONG).show();
                // GeneralsX @tweak Flux Build 07/11/2026 v1.4.1: Import Map lives on Maps & Mods now; show the new map there.
                if (currentTab == TAB_CONTENT) showTab(TAB_CONTENT);
            } else if (lower.endsWith(".zip")) {
                int count = extractMapsFromZip(uri, mapsDir);
                gxToast(this, getString(R.string.setup_toast_maps_imported_count, count), Toast.LENGTH_LONG).show();
                if (currentTab == TAB_CONTENT) showTab(TAB_CONTENT);
            } else if (lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
                int count = extractMapsFromTar(uri, mapsDir, lower.endsWith(".gz") || lower.endsWith(".tgz"));
                gxToast(this, getString(R.string.setup_toast_maps_imported_count, count), Toast.LENGTH_LONG).show();
                if (currentTab == TAB_CONTENT) showTab(TAB_CONTENT);
            } else if (lower.endsWith(".rar")) {
                int count = extractMapsFromRar(uri, mapsDir);
                gxToast(this, getString(R.string.setup_toast_maps_imported_count, count), Toast.LENGTH_LONG).show();
                if (currentTab == TAB_CONTENT) showTab(TAB_CONTENT);
            } else {
                gxToast(this, getString(R.string.setup_toast_pick_archive), Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            gxToast(this, getString(R.string.setup_toast_map_import_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
        }
    }

    private int extractMapsFromZip(Uri uri, File mapsDir) throws java.io.IOException {
        int count = 0;
        try (java.io.InputStream raw = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                 new java.io.BufferedInputStream(raw, 65536))) {
            if (zip == null) return 0;
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                String fileName = new File(entryName).getName();
                String lower = fileName.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".map")) {
                    String mapBaseName = fileName.substring(0, fileName.length() - 4);
                    File leafFolder = new File(mapsDir, mapBaseName);
                    leafFolder.mkdirs();
                    File destFile = new File(leafFolder, fileName);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = zip.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    count++;
                } else if (lower.endsWith(".tga") || lower.endsWith(".str") || lower.endsWith(".ini")) {
                    int dot = fileName.lastIndexOf('.');
                    String mapBaseName = dot > 0 ? fileName.substring(0, dot) : fileName;
                    File leafFolder = new File(mapsDir, mapBaseName);
                    leafFolder.mkdirs();
                    File destFile = new File(leafFolder, fileName);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = zip.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
        return count;
    }

    private String queryMapDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[] { android.provider.OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int col = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (col >= 0) {
                    return cursor.getString(col);
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private int extractMapsFromTar(Uri uri, File mapsDir, boolean isGzip) throws java.io.IOException {
        int count = 0;
        try (java.io.InputStream raw = getContentResolver().openInputStream(uri);
             java.io.BufferedInputStream bin = new java.io.BufferedInputStream(raw, 65536);
             java.io.InputStream stream = isGzip ? new java.util.zip.GZIPInputStream(bin) : bin;
             org.apache.commons.compress.archivers.tar.TarArchiveInputStream tar =
                 new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(stream)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName().replace('\\', '/');
                String fileName = new File(entryName).getName();
                String lower = fileName.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".map")) {
                    String mapBaseName = fileName.substring(0, fileName.length() - 4);
                    File leafFolder = new File(mapsDir, mapBaseName);
                    leafFolder.mkdirs();
                    File destFile = new File(leafFolder, fileName);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = tar.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    count++;
                } else if (lower.endsWith(".tga") || lower.endsWith(".str") || lower.endsWith(".ini") || lower.endsWith(".dds")) {
                    int dot = fileName.lastIndexOf('.');
                    String mapBaseName = dot > 0 ? fileName.substring(0, dot) : fileName;
                    File leafFolder = new File(mapsDir, mapBaseName);
                    leafFolder.mkdirs();
                    File destFile = new File(leafFolder, fileName);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = tar.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                }
            }
        }
        return count;
    }

    private int extractMapsFromRar(Uri uri, File mapsDir) throws java.io.IOException {
        File tmpRar = new File(getCacheDir(), "import_temp_" + System.currentTimeMillis() + ".rar");
        try {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmpRar)) {
                if (in == null) return 0;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            int count = 0;
            try (com.github.junrar.Archive archive = new com.github.junrar.Archive(tmpRar)) {
                com.github.junrar.rarfile.FileHeader fh;
                while ((fh = archive.nextFileHeader()) != null) {
                    if (fh.isDirectory()) continue;
                    String entryName = fh.getFileName().replace('\\', '/');
                    String fileName = new File(entryName).getName();
                    String lower = fileName.toLowerCase(java.util.Locale.US);
                    if (lower.endsWith(".map")) {
                        String mapBaseName = fileName.substring(0, fileName.length() - 4);
                        File leafFolder = new File(mapsDir, mapBaseName);
                        leafFolder.mkdirs();
                        File destFile = new File(leafFolder, fileName);
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                            archive.extractFile(fh, out);
                        }
                        count++;
                    } else if (lower.endsWith(".tga") || lower.endsWith(".str") || lower.endsWith(".ini") || lower.endsWith(".dds")) {
                        int dot = fileName.lastIndexOf('.');
                        String mapBaseName = dot > 0 ? fileName.substring(0, dot) : fileName;
                        File leafFolder = new File(mapsDir, mapBaseName);
                        leafFolder.mkdirs();
                        File destFile = new File(leafFolder, fileName);
                        try (java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {
                            archive.extractFile(fh, out);
                        }
                    }
                }
            } catch (com.github.junrar.exception.RarException e) {
                throw new java.io.IOException("RAR extraction error: " + e.getMessage(), e);
            }
            return count;
        } finally {
            tmpRar.delete();
        }
    }

    private void onImportHotkeys() {
        String gamePath = getSavedGamePath();
        if (gamePath == null) {
            gxToast(this, R.string.setup_langpack_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_HOTKEYS);
        } catch (android.content.ActivityNotFoundException e) {
            gxToast(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    private void onImportControlBar() {
        String gamePath = getSavedGamePath();
        if (gamePath == null) {
            gxToast(this, R.string.setup_langpack_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQUEST_IMPORT_CONTROLBAR);
        } catch (android.content.ActivityNotFoundException e) {
            gxToast(this, R.string.mods_import_picker_missing, Toast.LENGTH_LONG).show();
        }
    }

    // GeneralsX @feature Flux Build 03/11/2026 Hotkey and control-bar packs go through
    // HotkeyPackInstaller: routed to where the engine reads them, every replaced file backed up, off
    // the UI thread. Restore undoes exactly what the launcher installed (and the .bak files the
    // previous importer left) -- it no longer deletes every game-folder file named "*ControlBar*".
    private void onRestoreDefaultHotkeys() {
        if (getSavedGamePath() == null) {
            gxToast(this, R.string.setup_langpack_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(() -> {
            String message;
            try {
                int restored = HotkeyPackInstaller.restoreAll(this);
                message = restored > 0 ? getString(R.string.hotkeys_restore_done, restored)
                    : getString(R.string.hotkeys_restore_nothing);
            } catch (HotkeyPackInstaller.ImportException e) {
                message = getString(e.messageRes);
            } catch (java.io.IOException e) {
                android.util.Log.e("GeneralsZH", "could not restore hotkeys", e);
                message = getString(R.string.hotkeys_import_failed, String.valueOf(e.getMessage()));
            }
            final String text = message;
            runOnUiThread(() -> gxToast(this, text, Toast.LENGTH_LONG).show());
        }, "HotkeyRestore").start();
    }

    private void importHotkeyDocument(Uri uri) {
        installPackDocument(uri, false);
    }

    private void importControlBarDocument(Uri uri) {
        installPackDocument(uri, true);
    }

    private void installPackDocument(Uri uri, boolean controlBar) {
        if (uri == null) {
            return;
        }
        String displayName = queryMapDisplayName(uri);
        // GeneralsX @feature Flux Build 03/11/2026 A control bar arrives as a .zip too.
        String lowerName = displayName == null ? "" : displayName.toLowerCase(Locale.US);
        // GeneralsX @feature Flux Build 05/11/2026 Or an archive named without ".big" (checked on install).
        if (controlBar && !(lowerName.endsWith(".big") || lowerName.endsWith(".gib") || lowerName.endsWith(".zip")
                || BigArchive.isRenamedArchiveName(lowerName))) {
            gxToast(this, R.string.controlbar_pick_big, Toast.LENGTH_LONG).show();
            return;
        }
        gxToast(this, R.string.hotkeys_import_running, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                HotkeyPackInstaller.Result r = controlBar
                    ? HotkeyPackInstaller.installControlBar(this, uri, displayName)
                    : HotkeyPackInstaller.installUri(this, uri, displayName);
                if (controlBar) {
                    message = getString(R.string.controlbar_import_done, String.join(", ", r.files));
                } else {
                    message = getString(R.string.hotkeys_import_done, r.installed, r.skipped);
                }
                if (r.refusedGameplay > 0) {
                    message += "\n" + getString(R.string.hotkeys_import_refused_gameplay, r.refusedGameplay);
                }
                if (!r.gameplayArchives.isEmpty()) {
                    message += "\n" + getString(R.string.hotkeys_import_gameplay_archive,
                        String.join(", ", r.gameplayArchives));
                }
            } catch (HotkeyPackInstaller.ImportException e) {
                message = getString(e.messageRes);
            } catch (java.io.IOException e) {
                android.util.Log.e("GeneralsZH", "hotkey pack import failed", e);
                message = getString(R.string.hotkeys_import_failed, String.valueOf(e.getMessage()));
            }
            final String text = message;
            runOnUiThread(() -> gxToast(this, text, Toast.LENGTH_LONG).show());
        }, "HotkeyImport").start();
    }

    private boolean checkBigHeader(File file) {
        if (file == null || !file.isFile() || file.length() < 4) return false;
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            byte[] header = new byte[4];
            int read = in.read(header);
            return read == 4 && header[0] == 'B' && header[1] == 'I' && header[2] == 'G'
                && (header[3] == 'F' || header[3] == '4');
        } catch (Exception ignored) {
            return false;
        }
    }

    private int extractModZipToGame(File zipFile, File gameDir) throws java.io.IOException {
        int count = 0;
        String canonicalGame = gameDir.getCanonicalPath();
        File dataIniDir = new File(gameDir, "Data/INI");
        dataIniDir.mkdirs();

        try (java.io.InputStream raw = new java.io.FileInputStream(zipFile);
             java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                 new java.io.BufferedInputStream(raw, 65536))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                String fileName = new File(entryName).getName();
                String lowerName = fileName.toLowerCase(java.util.Locale.US);

                File outFile;
                if (lowerName.endsWith(".big") || fileName.startsWith("!")) {
                    // SAGE Rule: Route all .big files directly to Game Root!
                    outFile = new File(gameDir, fileName);
                } else if (lowerName.endsWith(".ini")) {
                    // SAGE Rule: Route all .ini files to Data/INI/
                    outFile = new File(dataIniDir, fileName);
                } else {
                    // Other assets (Data/..., Window/..., Art/...): extract preserving structure in gameDir
                    outFile = new File(gameDir, entryName);
                }

                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalGame + File.separator) && !canonicalOut.equals(canonicalGame)) {
                    // Prevent Zip Slip traversal vulnerability
                    zip.closeEntry();
                    continue;
                }

                outFile.getParentFile().mkdirs();
                backupFileIfExists(outFile);

                try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = zip.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }

                // Header validation for extracted files without .big extension
                if (!lowerName.endsWith(".big") && checkBigHeader(outFile)) {
                    File bigTarget = new File(gameDir, fileName + ".big");
                    outFile.renameTo(bigTarget);
                }

                count++;
                zip.closeEntry();
            }

            // Option A: If any extracted .big contains CommandMap.ini, extract it directly to Data/INI/
            File[] extractedBigs = gameDir.listFiles((dir, name) -> name.endsWith(".big") || name.startsWith("!"));
            if (extractedBigs != null) {
                for (File eb : extractedBigs) {
                    extractCommandMapFromBig(eb, dataIniDir);
                }
            }
        }
        return count;
    }

    private boolean extractCommandMapFromBig(File bigFile, File dataIniDir) {
        if (bigFile == null || !bigFile.isFile() || bigFile.length() < 16) {
            return false;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(bigFile, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (!(magic[0] == 'B' && magic[1] == 'I' && magic[2] == 'G' && (magic[3] == 'F' || magic[3] == '4'))) {
                return false;
            }
            raf.skipBytes(4); // archive size
            int numFiles = raf.readInt(); // big-endian
            int headerSize = raf.readInt(); // big-endian

            if (numFiles <= 0 || numFiles > 50000) {
                return false;
            }

            for (int i = 0; i < numFiles; i++) {
                int fileOffset = raf.readInt();
                int fileSize = raf.readInt();

                java.io.ByteArrayOutputStream pathBytes = new java.io.ByteArrayOutputStream();
                int b;
                while ((b = raf.read()) > 0) {
                    pathBytes.write(b);
                }
                String entryPath = pathBytes.toString("UTF-8").replace('\\', '/').toLowerCase(java.util.Locale.US);

                if (entryPath.endsWith("commandmap.ini")) {
                    raf.seek(fileOffset);
                    byte[] data = new byte[fileSize];
                    raf.readFully(data);

                    dataIniDir.mkdirs();
                    File dest = new File(dataIniDir, "CommandMap.ini");
                    backupFileIfExists(dest);

                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                        out.write(data);
                    }
                    android.util.Log.i("GeneralsZH", "Extracted CommandMap.ini (" + fileSize + " bytes) from " + bigFile.getName() + " to " + dest.getAbsolutePath());
                    return true;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("GeneralsZH", "Could not extract CommandMap.ini from " + bigFile.getName(), e);
        }
        return false;
    }

    private void backupFileIfExists(File file) {
        if (file != null && file.isFile()) {
            File backup = new File(file.getParentFile(), file.getName() + ".bak");
            if (!backup.exists()) {
                try (java.io.InputStream in = new java.io.FileInputStream(file);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(backup)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    android.util.Log.i("GeneralsZH", "Created backup: " + backup.getAbsolutePath());
                } catch (java.io.IOException e) {
                    android.util.Log.w("GeneralsZH", "Failed to backup " + file.getName(), e);
                }
            }
        }
    }

    private int extractZipSafely(Uri uri, File destDir) throws java.io.IOException {
        int count = 0;
        String canonicalDest = destDir.getCanonicalPath();
        try (java.io.InputStream raw = getContentResolver().openInputStream(uri);
             java.util.zip.ZipInputStream zip = raw != null ? new java.util.zip.ZipInputStream(
                 new java.io.BufferedInputStream(raw, 65536)) : null) {
            if (zip == null) return 0;
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                File outFile = new File(destDir, name);
                String canonicalOut = outFile.getCanonicalPath();
                if (!canonicalOut.startsWith(canonicalDest + File.separator) && !canonicalOut.equals(canonicalDest)) {
                    // Prevent Zip Slip traversal vulnerability
                    zip.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = zip.read(buf)) > 0) {
                            out.write(buf, 0, len);
                        }
                    }
                    count++;
                }
                zip.closeEntry();
            }
        }
        return count;
    }

    private static File findFileByName(File dir, String name) {
        File[] children = dir.listFiles();
        if (children == null) {
            return null;
        }
        for (File c : children) {
            if (c.isDirectory()) {
                File found = findFileByName(c, name);
                if (found != null) {
                    return found;
                }
            } else if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursive(c);
                }
            }
        }
        f.delete();
    }

    private static String readWholeFile(File f) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private static String readFirstLine(File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String line = r.readLine();
            return line != null ? line.trim() : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // GeneralsX @feature Android port 31/07/2026 dxvk.conf editor: real-device
    // Mali-G76 performance tuning kept coming back to "edit one line of
    // dxvk.conf on the phone" (e.g. d3d9.samplerAnisotropy), which meant a
    // file manager and manual editing outside the app every time. This edits
    // the actual file the engine reads (SDL3Main.cpp/DXVK read dxvk.conf
    // relative to CWD, i.e. the selected game folder -- see
    // copyBundledRuntimeIfMissing()'s comment above), as raw text rather than
    // a bespoke widget per key, so any current or future DXVK config option
    // works here without launcher code changes, and existing comments in the
    // file round-trip untouched instead of being reparsed away.
    private EditText dxvkConfigEdit;

    // GeneralsX @feature Android port launcher-ui-2026 08/09/2026 The raw
    // EditText is now a Material 3 outlined text field. TextInputLayout picks
    // its style from ?attr/textInputStyle, so an outlined box on a view built
    // in code (rather than inflated from a layout with a style= attribute) is
    // a matter of constructing it against a ContextThemeWrapper that swaps
    // that one attribute -- see ThemeOverlay.GeneralsZH.OutlinedField.
    private void buildDxvkConfigSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_terminal,
            getString(R.string.setup_card_dxvk_config), false);

        ContextThemeWrapper fieldContext =
            new ContextThemeWrapper(this, R.style.ThemeOverlay_GeneralsZH_OutlinedField);
        TextInputLayout field = new TextInputLayout(fieldContext);
        field.setHint(R.string.setup_card_dxvk_config);
        field.setBoxStrokeColor(UiKit.color(this, R.color.gzh_primary));
        field.setHintTextColor(UiKit.tint(this, R.color.gzh_on_surface_variant));
        // Keep the label in its floated position even when the box is empty:
        // loadDxvkConfigIntoEditor() puts the "select a game folder first"
        // prompt in the field's own hint, and an expanded label would sit on
        // top of it.
        field.setExpandedHintEnabled(false);
        float boxRadius = UiKit.dim(this, R.dimen.gzh_radius_field);
        field.setBoxCornerRadii(boxRadius, boxRadius, boxRadius, boxRadius);

        TextInputEditText edit = new TextInputEditText(field.getContext());
        edit.setTypeface(android.graphics.Typeface.MONOSPACE);
        edit.setTextSize(12);
        edit.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        edit.setMinLines(6);
        edit.setMaxLines(20);
        edit.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.addView(edit, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        dxvkConfigEdit = edit;

        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldLp.topMargin = UiKit.dim(this, R.dimen.gzh_item_gap_tight);
        content.addView(field, fieldLp);

        LinearLayout row = UiKit.buttonRow(content);
        UiKit.share(UiKit.button(row, UiKit.BTN_PRIMARY, R.drawable.ic_gzh_save,
            getString(R.string.setup_button_dxvk_config_save), this::onSaveDxvkConfig), true);
        UiKit.share(UiKit.button(row, UiKit.BTN_DANGER, R.drawable.ic_gzh_refresh,
            getString(R.string.setup_button_dxvk_config_reset), this::onResetDxvkConfig), false);

        UiKit.helpText(content, getString(R.string.setup_dxvk_config_help));

        loadDxvkConfigIntoEditor();
    }

    // Live copy the engine actually reads -- lives in the user-selected game
    // folder, same as DefaultOptions.ini and fonts/ (see
    // copyBundledRuntimeIfMissing()).
    private File dxvkConfFile() {
        String gamePath = getSavedGamePath();
        return gamePath != null ? new File(gamePath, "dxvk.conf") : null;
    }

    // Pristine template this build ships, staged into getExternalFilesDir()
    // by GeneralsZHActivity on first run -- same source
    // copyBundledRuntimeIfMissing() copies from when a game folder is first
    // selected.
    private File bundledDxvkConfFile() {
        File root = getExternalFilesDir(null);
        return root != null ? new File(root, "dxvk.conf") : null;
    }

    private void loadDxvkConfigIntoEditor() {
        if (dxvkConfigEdit == null) {
            return;
        }
        File live = dxvkConfFile();
        File source = (live != null && live.isFile()) ? live : bundledDxvkConfFile();
        String text = "";
        if (source != null && source.isFile()) {
            try {
                text = readWholeFile(source);
            } catch (java.io.IOException e) {
                // Leave the editor empty; Save will still work and create a fresh file.
            }
        }
        dxvkConfigEdit.setText(text);
        boolean haveFolder = getSavedGamePath() != null;
        dxvkConfigEdit.setEnabled(haveFolder);
        dxvkConfigEdit.setHint(haveFolder ? null : getString(R.string.setup_dxvk_config_no_folder));
    }

    private void onSaveDxvkConfig() {
        File dest = dxvkConfFile();
        if (dest == null) {
            gxToast(this, R.string.setup_dxvk_config_no_folder, Toast.LENGTH_LONG).show();
            return;
        }
        try (java.io.Writer w = new java.io.FileWriter(dest, false)) {
            w.write(dxvkConfigEdit.getText().toString());
        } catch (java.io.IOException e) {
            gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        gxToast(this, R.string.setup_toast_dxvk_config_saved, Toast.LENGTH_SHORT).show();
    }

    private void onResetDxvkConfig() {
        File bundled = bundledDxvkConfFile();
        if (bundled == null || !bundled.isFile()) {
            gxToast(this, R.string.setup_toast_dxvk_config_no_default, Toast.LENGTH_LONG).show();
            return;
        }
        String text;
        try {
            text = readWholeFile(bundled);
        } catch (java.io.IOException e) {
            gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        dxvkConfigEdit.setText(text);
        File dest = dxvkConfFile();
        if (dest != null) {
            try (java.io.Writer w = new java.io.FileWriter(dest, false)) {
                w.write(text);
            } catch (java.io.IOException e) {
                gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                return;
            }
        }
        gxToast(this, R.string.setup_toast_dxvk_config_reset, Toast.LENGTH_SHORT).show();
    }

    // GeneralsX @feature Android port 02/08/2026 Diagnostic marker toggles:
    // GXTrace.h (gx_trace.txt/gx_perf.txt) and SDL3Main.cpp (dxvk_hud.txt/
    // dxvk_validation.txt/dxvk_verbose_log.txt) all gate opt-in logging
    // behind a plain marker file dropped into the selected game folder,
    // checked relative to CWD after the engine chdir()s there --
    // deliberately no-adb, no-rebuild, so a tester can enable them (see
    // docs/port/ANDROID_PORT.md's "Diagnostic marker files" table, the
    // canonical list this mirrors). In practice almost nobody who isn't
    // already comfortable with a file manager knows to create an empty file
    // with an exact name, so this just does it for them: each switch
    // creates/deletes the marker directly, no new native code needed since
    // the engine side only ever checked "does this file exist", never its
    // contents. Each switch gets its own plain-language title AND a "when to
    // turn this on" description (translated, not just the raw filename) --
    // the filename itself still appears at the end of the description in
    // parentheses so a tester can match it up with exact instructions from
    // an issue reporter/maintainer.
    private static final String[] DIAGNOSTIC_MARKERS = {
        "gx_trace.txt", "gx_perf.txt", "gx_gles_gputimer.txt", "gx_audio_trace.txt", "gx_net_trace.txt",
        "gx_touch_debug.txt", "dxvk_hud.txt",
        "dxvk_validation.txt", "dxvk_verbose_log.txt"
    };
    private static final int[] DIAGNOSTIC_TITLES = {
        R.string.setup_switch_gx_trace, R.string.setup_switch_gx_perf, R.string.setup_switch_gpu_timer,
        R.string.setup_switch_gx_audio_trace, R.string.setup_switch_gx_net_trace,
        R.string.setup_switch_touch_debug,
        R.string.setup_switch_dxvk_hud, R.string.setup_switch_dxvk_validation,
        R.string.setup_switch_dxvk_verbose_log
    };
    private static final int[] DIAGNOSTIC_DESCRIPTIONS = {
        R.string.setup_switch_gx_trace_desc, R.string.setup_switch_gx_perf_desc, R.string.setup_switch_gpu_timer_desc,
        R.string.setup_switch_gx_audio_trace_desc, R.string.setup_switch_gx_net_trace_desc,
        R.string.setup_switch_touch_debug_desc,
        R.string.setup_switch_dxvk_hud_desc, R.string.setup_switch_dxvk_validation_desc,
        R.string.setup_switch_dxvk_verbose_log_desc
    };
    private final SwitchCompat[] diagnosticSwitches = new SwitchCompat[DIAGNOSTIC_MARKERS.length];

    // GeneralsX @feature Android port launcher-ui-2026 08/09/2026 Each toggle
    // used to be a SwitchCompat whose LABEL was the title, with the "when to
    // turn this on" paragraph as a separate, differently-indented TextView
    // underneath -- so a long translated title wrapped around the switch and
    // the two halves of one control drifted apart. They are now proper
    // Material 3 list rows: title and description in a text column, the
    // switch pinned to the end edge, a hairline between rows.
    private void buildDiagnosticsSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_wrench,
            getString(R.string.setup_card_diagnostics), false);
        UiKit.supporting(content, getString(R.string.setup_diagnostics_help));

        diagnosticsNoFolderHint = UiKit.chip(content, R.drawable.ic_gzh_info,
            getString(R.string.setup_diagnostics_no_folder),
            R.color.gzh_status_warn, R.color.gzh_surface_container_high);

        // GeneralsX @feature Android port 27/09/2026 Master switch: when off, nothing is logged in
        // the background -- not the engine's stderr mirror, not crash.log, not GeneralsOnline.log,
        // not this launcher's network trace. Kept as a marker in the app's own files dir (not the
        // game folder) so it holds before any folder is chosen, and so the native side can find
        // it at load time, before SDL_main (see GXLogging.h).
        loggingSwitch = UiKit.switchRow(content,
            getString(R.string.setup_switch_logging), getString(R.string.setup_switch_logging_desc));
        loggingSwitch.setChecked(!isLoggingDisabled(this));
        loggingSwitch.setOnCheckedChangeListener((button, checked) -> setLoggingDisabled(!checked));
        TextView logWarn = UiKit.caption(content, getString(R.string.setup_logging_perf_warning));
        logWarn.setTextColor(0xFFFF5252); // Red warning
        logWarn.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        UiKit.divider(content);

        // GeneralsX @tweak Android port 18/10/2026 See RENDER_BACKEND_CHOICES_NO_VULKAN. Turning it
        // off while Vulkan is selected moves the game back to OpenGL ES.
        com.google.android.material.materialswitch.MaterialSwitch vulkanSwitch = UiKit.switchRow(content,
            getString(R.string.setup_switch_vulkan_experimental),
            getString(R.string.setup_switch_vulkan_experimental_desc, getString(R.string.nav_tab_graphics)));
        vulkanSwitch.setChecked(isVulkanUnlocked());
        vulkanSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(PREF_VULKAN_UNLOCKED, checked).apply();
            if (!checked && RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice())
                    && writeRenderBackend(RENDER_BACKEND_GLES)) {
                gxToast(this, R.string.setup_toast_render_backend_saved, Toast.LENGTH_LONG).show();
            }
            // The DXVK diagnostics below come and go with it.
            button.post(() -> showTab(TAB_TOOLS));
        });
        UiKit.divider(content);

        boolean first = true;
        for (int i = 0; i < DIAGNOSTIC_MARKERS.length; i++) {
            // GeneralsX @tweak Android port 18/10/2026 The DXVK switches (HUD, validation, verbose
            // log) do nothing under OpenGL ES, so they are shown only while Vulkan is offered.
            if (DIAGNOSTIC_MARKERS[i].startsWith("dxvk_") && !isVulkanUnlocked()) {
                diagnosticSwitches[i] = null;
                continue;
            }
            if (!first) {
                UiKit.divider(content);
            }
            first = false;
            diagnosticSwitches[i] = UiKit.switchRow(content,
                getString(DIAGNOSTIC_TITLES[i]), getString(DIAGNOSTIC_DESCRIPTIONS[i]));
        }

        refreshDiagnosticsSwitches();
    }

    private TextView diagnosticsNoFolderHint;
    private SwitchCompat loggingSwitch;

    static final String LOGGING_OFF_MARKER = "logging_off";
    private static final String PREF_LOGGING_SET = "pref_logging_user_configured";

    static boolean isLoggingDisabled(android.content.Context ctx) {
        // Flux Build: Default to DISABLED (OFF) for all users to maximize performance and avoid micro-stutters
        android.content.SharedPreferences sp = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!sp.contains(PREF_LOGGING_SET)) {
            try {
                new File(ctx.getFilesDir(), LOGGING_OFF_MARKER).createNewFile();
            } catch (Exception ignored) {}
            return true;
        }
        return new File(ctx.getFilesDir(), LOGGING_OFF_MARKER).isFile();
    }

    private void setLoggingDisabled(boolean disabled) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_LOGGING_SET, true).apply();
        File marker = new File(getFilesDir(), LOGGING_OFF_MARKER);
        if (disabled) {
            try {
                marker.createNewFile();
            } catch (java.io.IOException e) {
                gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else {
            marker.delete();
        }
        try {
            nativeSetLogLevel(disabled ? 0 : 3);
        } catch (Throwable ignored) {}
    }

    private static native void nativeSetLogLevel(int level);

    private File diagnosticMarkerFile(String name) {
        String gamePath = getSavedGamePath();
        return gamePath != null ? new File(gamePath, name) : null;
    }

    private void setDiagnosticMarker(String name, boolean enabled) {
        File marker = diagnosticMarkerFile(name);
        if (marker == null) {
            return;
        }
        if (enabled) {
            try {
                marker.createNewFile();
            } catch (java.io.IOException e) {
                gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else {
            marker.delete();
        }
    }

    private void refreshDiagnosticsSwitches() {
        boolean haveFolder = getSavedGamePath() != null;
        if (diagnosticsNoFolderHint != null) {
            diagnosticsNoFolderHint.setVisibility(haveFolder ? android.view.View.GONE : android.view.View.VISIBLE);
        }
        for (int i = 0; i < DIAGNOSTIC_MARKERS.length; i++) {
            final int index = i;
            SwitchCompat sw = diagnosticSwitches[index];
            if (sw == null) {
                continue;
            }
            File marker = diagnosticMarkerFile(DIAGNOSTIC_MARKERS[index]);
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(marker != null && marker.isFile());
            sw.setEnabled(haveFolder);
            sw.setOnCheckedChangeListener((button, checked) -> setDiagnosticMarker(DIAGNOSTIC_MARKERS[index], checked));
        }
    }

    // GeneralsX @feature Android port 10/07/2026 GeneralsOnline (playgenerals.online)
    // account status -- the actual sign-in flow lives in GeneralsOnlineActivity,
    // this is just a status line + entry point.
    private TextView onlineStatusView;

    private void buildGeneralsOnlineSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_account,
            getString(R.string.setup_card_online), false);

        onlineStatusView = UiKit.supporting(content, null);

        // GeneralsX @feature Android port 27/09/2026 The service refuses a join between lobbies
        // whose anti-cheat differs (anticheat_id, JoinLobbyResult_AnticheatMismatch), and this
        // client has none. A PC player running GeneralsOnline AntiCheat or Easy Anti-Cheat is
        // therefore unreachable from a phone until they turn it off -- say so up front instead
        // of leaving players to discover it as a failed join.
        // GeneralsX @tweak Android port 18/10/2026 Only about playing with PC players, so only while
        // cross-play is on (pc_crossplay in the signed settings; off since 18/10/2026).
        if (UpdateManager.featureEnabled(this, "pc_crossplay", false)) {
            UiKit.supporting(content, getString(R.string.setup_online_anticheat_note));
        }

        UiKit.button(content, UiKit.BTN_TONAL, R.drawable.ic_gzh_account,
            getString(R.string.setup_button_online_account), () ->
                startActivity(new Intent(this, GeneralsOnlineActivity.class)));
    }

    private void refreshGeneralsOnlineStatus() {
        if (onlineStatusView == null) {
            return;
        }
        String displayName = GeneralsOnlineActivity.getSignedInDisplayName(this);
        onlineStatusView.setText(displayName != null
            ? getString(R.string.setup_online_signed_in, displayName)
            : getString(R.string.setup_online_signed_out));
    }

    // GeneralsX @bugfix Android port 27/09/2026 The Options.ini the game reads lives in the shared
    // user-data dir (SDL3Main.cpp, GENERALSX_USERDATA_DIR) since issue #9 moved it there on
    // 18/07/2026. This still pointed at the old internal <filesDir>/.local/share/... copy, so the
    // text size was saved where the game never looks and every value looked the same in game.
    private File optionsIniFile() {
        return new File(userDataDir(this), "Options.ini");
    }

    private File defaultOptionsIniFile() {
        String gamePath = getSavedGamePath();
        return gamePath != null ? new File(gamePath, "DefaultOptions.ini") : null;
    }

    // GeneralsX @feature Flux Build 23/10/2026 First-launch hardware profile.
    private static final String PREF_AUTO_CONFIG_DONE = "auto_config_done_v1";

    /**
     * Runs once per install. An existing Options.ini that already carries a detail level means
     * the player (or a previous version of the app) has a real profile, so it is left untouched
     * even though the preference key is new after an upgrade. A bare file written by the old
     * launch path is merged instead. The explicit "Re-run Auto-Configuration" button bypasses
     * this check.
     */
    private void maybeRunAutoConfiguration() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_AUTO_CONFIG_DONE, false)) {
            return;
        }
        if (!hasStorageAccess()) {
            // Do not show a failure toast before the player has been asked for storage access;
            // onResume() retries after the permission screen returns.
            return;
        }
        File file = optionsIniFile();
        // A launch path from an older build can create a small Options.ini containing only
        // Resolution before the first-launch profile runs. A non-empty file is therefore not
        // proof of a real player profile: require the detail level the auto-config writes.
        // The merge still starts from the live file, so any other existing keys are preserved.
        java.util.Map<String, String> existing = readKeyValueFile(file);
        boolean alreadyConfigured = file.isFile() && file.length() > 0
            && (existing.containsKey("StaticGameLOD")
                || existing.containsKey("IdealStaticGameLOD"));
        if (alreadyConfigured) {
            // Mark done on upgrades too: the existing file is the player's profile.
            prefs.edit().putBoolean(PREF_AUTO_CONFIG_DONE, true).apply();
            android.util.Log.i("GeneralsZH",
                "auto-config: existing Options.ini kept, user settings not overridden");
            return;
        }
        // Only mark the profile done after a successful write. If the write fails (for example
        // because storage is full), onResume retries instead of leaving the install unconfigured
        // forever.
        if (applyAutoConfiguration(false) != null) {
            prefs.edit().putBoolean(PREF_AUTO_CONFIG_DONE, true).apply();
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    /** Returns null when Options.ini could not be written. */
    private DeviceAutoConfig.Result applyAutoConfiguration(boolean force) {
        DeviceAutoConfig.Result profile = DeviceAutoConfig.detect(this);
        java.util.LinkedHashMap<String, String> prefs = optionsTemplate();

        // The cap is 1080p on 6 GB devices and 1440p on 8 GB+ devices; the screen's own aspect
        // ratio is preserved and a smaller panel is never upscaled.
        prefs.put("Resolution", profile.targetWidth + " " + profile.targetHeight);
        prefs.put("StaticGameLOD", profile.lod);
        prefs.put("IdealStaticGameLOD", profile.lod);
        if (!writeKeyValueFile(optionsIniFile(), prefs)) {
            return null;
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_RES_WRITTEN, profile.targetWidth + "x" + profile.targetHeight).apply();

        // applyInternalResolutionSetting() is called on every game launch and would otherwise
        // write Native (100%) straight back over the profile. Point the existing dropdown at the
        // same cap so the launch path and the profile agree.
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt(PREF_INTERNAL_RES, profile.internalResolutionChoice)
            .apply();

        android.util.Log.i("GeneralsZH", "auto-config" + (force ? " (re-run)" : "")
            + ": " + profile.targetWidth + "x" + profile.targetHeight
            + ", LOD=" + profile.lod + ", RAM=" + profile.ramGigabytes
            + "GB, cores=" + profile.cpuCores);
        return profile;
    }

    /**
     * The key/value map an Options.ini write starts from: the live file, else the game folder's
     * DefaultOptions.ini, else the copy bundled in the APK. Without this a write before the first
     * game launch creates a bare file and the engine never gets its GPU defaults.
     */
    private java.util.LinkedHashMap<String, String> optionsTemplate() {
        File live = optionsIniFile();
        if (live.isFile() && live.length() > 0) {
            return new java.util.LinkedHashMap<>(readKeyValueFile(live));
        }
        java.util.LinkedHashMap<String, String> template =
            new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        if (template.isEmpty()) {
            template.putAll(readBundledDefaultOptions());
        }
        return template;
    }

    private java.util.Map<String, String> readBundledDefaultOptions() {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    getAssets().open("gamedata/DefaultOptions.ini"),
                    java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (!key.isEmpty() && !value.isEmpty()) {
                    result.put(key, value);
                }
            }
        } catch (java.io.IOException ignored) {
            // The engine will fall back to its own defaults; do not block first launch.
        }
        return result;
    }

    private int readUiScalePercent() {
        java.util.Map<String, String> prefs = readKeyValueFile(optionsIniFile());
        String val = prefs.get("ResolutionFontAdjustment");
        if (val != null) {
            try {
                int percent = Integer.parseInt(val.trim());
                // GeneralsX @bugfix Android port 27/09/2026 A negative value means "the game's
                // default": the in-game Options menu saves -100 when the player never set one
                // (OptionsMenu.cpp, getResolutionFontAdjustment() returns -1). Clamped to 0 it
                // showed as 0% here, and Apply then saved a real 0 -- unscaled 800x600-size text.
                if (percent >= 0) {
                    return Math.min(150, percent);
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the engine's own default below.
            }
        }
        return 70;
    }

    private void writeUiScalePercent(int percent) {
        File file = optionsIniFile();

        // TheSuperHackers @bugfix Android port 07/07/2026 SDL3Main.cpp only
        // seeds Options.ini from the game folder's DefaultOptions.ini (full
        // GPU-detail defaults) the FIRST time it doesn't already exist. If we
        // create a bare Options.ini containing only ResolutionFontAdjustment
        // before the user ever launches the game once, that seeding is
        // permanently skipped and they silently lose those defaults. Seed
        // from DefaultOptions.ini ourselves first when the file is new.
        java.util.LinkedHashMap<String, String> prefs;
        if (!file.isFile()) {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        } else {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        }
        prefs.put("ResolutionFontAdjustment", String.valueOf(percent));

        writeKeyValueFile(file, prefs);
    }

    private boolean writeKeyValueFile(File file, java.util.Map<String, String> prefs) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            gxToast(this, R.string.setup_toast_options_dir_failed, Toast.LENGTH_LONG).show();
            return false;
        }
        // Writer, not PrintWriter: PrintWriter swallows IO failures, which would let the
        // first-launch profile be marked done after a failed write.
        //
        // Write a complete sibling first and rename it over the real file. The old code
        // truncated Options.ini before writing, so a crash or a full disk in the middle of the
        // first-launch/re-run write could destroy an existing profile. rename() replaces the
        // destination atomically on the storage Android exposes.
        File staging = new File(file.getAbsolutePath() + ".tmp");
        try (java.io.Writer w = new java.io.BufferedWriter(new java.io.FileWriter(staging, false))) {
            for (java.util.Map.Entry<String, String> e : prefs.entrySet()) {
                w.write(e.getKey());
                w.write(" = ");
                w.write(e.getValue());
                w.write('\n');
            }
            w.flush();
        } catch (java.io.IOException e) {
            staging.delete();
            gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
        try {
            java.nio.file.Files.move(staging.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            staging.delete();
            gxToast(this, getString(R.string.setup_toast_options_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    // Matches UserPreferences::load()'s own format exactly ("key = value"
    // lines) so a file the engine already wrote round-trips untouched.
    private static java.util.Map<String, String> readKeyValueFile(File file) {
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        if (file == null || !file.isFile()) {
            return result;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                if (key.isEmpty() || val.isEmpty()) continue;
                result.put(key, val);
            }
        } catch (java.io.IOException ignored) {
            // Treat as empty; caller falls back to defaults.
        }
        return result;
    }

    // GeneralsX @feature Android port game-folder-integrity-check 07/09/2026
    // Plain text made "not set"/"looks valid"/"looks incomplete" too easy to
    // miss right after picking a folder -- the status line is now bold,
    // underlined and colored (green/amber/red) so it reads as a clear
    // verdict at a glance instead of blending into the paragraph around it.
    // The invalid/incomplete cases additionally get a blocking AlertDialog
    // right after picking (see onActivityResult()), since even a colored
    // line can be scrolled past unnoticed but a dialog can't.
    private void refreshStatus() {
        if (statusText == null && gamePathText == null) {
            return;  // the current page has no status line (see showTab())
        }
        String path = getSavedGamePath();
        if (path == null) {
            if (gamePathText != null) {
                gamePathText.setText(R.string.setup_status_folder_not_set);
            }
            if (statusText != null) {
                statusText.setText(R.string.setup_folder_status_missing);
                statusText.setTextColor(UiKit.color(this, R.color.gzh_on_surface_faint));
            }
            updateGameLanguageStatusView();
            return;
        }

        File dir = new File(path);
        boolean valid = isValidGameFolder(dir, getSelectedGame(this));
        String status;
        int statusColorRes;
        if (!valid) {
            status = getString(R.string.setup_folder_status_error) + " — "
                + invalidFolderMessage(dir).trim();
            statusColorRes = R.color.gzh_status_error;
        } else {
            java.util.List<String> issues = findGameFolderIntegrityIssues(dir);
            if (issues.isEmpty()) {
                status = getString(R.string.setup_folder_status_ok);
                statusColorRes = R.color.gzh_status_green;
            } else {
                status = getString(R.string.setup_folder_status_error) + " — "
                    + getString(R.string.setup_status_folder_incomplete, joinLines(issues)).trim();
                statusColorRes = R.color.gzh_status_error;
            }
        }

        if (gamePathText != null) {
            gamePathText.setText(path);
        }
        if (statusText != null) {
            // The plain-widget fallback has no separate path view, so it keeps both
            // lines in one block. The normal Home card shows them side by side:
            // path on top, one short verdict underneath.
            statusText.setText(gamePathText == null ? path + "\n" + status : status);
            statusText.setTextColor(UiKit.color(this, statusColorRes));
        }
        updateGameLanguageStatusView();
    }

    // String.join() is API 26; this launcher's UI code deliberately stays
    // inside the API 24 surface (see the minSdk note in android/app/build.gradle),
    // so the one place that needed it gets a three-line equivalent instead of
    // a version gate.
    private static String joinLines(java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private void showFolderProblemDialog(String message) {
        showFolderProblemDialog(message, false);
    }

    // GeneralsX @feature Android port 06/09/2026 When what is missing is the
    // base game specifically, listing the filenames is only half an answer:
    // the other half is that they do not have to be moved at all, since Setup
    // can be pointed at wherever they already live. Offer that here rather
    // than leaving it to be discovered among the buttons further down.
    private void showFolderProblemDialog(String message, boolean offerBasePicker) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
            .setTitle(R.string.setup_dialog_folder_problem_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null);
        if (offerBasePicker) {
            b.setNeutralButton(R.string.setup_button_select_base_generals,
                (d, which) -> onSelectBaseGeneralsFolder());
        }
        b.show();
    }


    // GeneralsX @feature Android port 09/09/2026 A language's text can live in a .big
    // archive, not just as a loose file, and this used to look only for the loose one.
    //
    // The engine never cared: g_csfFile is "data/%s/generals.csf" (SDL3Main.cpp) with %s
    // being whatever GetRegistryLanguage() returns, and it is opened through TheFileSystem,
    // which spans loose files AND every mounted .big equally. There is no list of supported
    // languages anywhere in the engine -- any token works if the CSF resolves. So the
    // launcher was the only thing insisting on a loose file, and it told players with a
    // perfectly good language pack that their game folder had nothing.
    //
    // Community translations ship exactly that way. The Russian pack, for instance, is a
    // single 00RussianZH.big whose entries are Data\English\generals.csf plus Russian menu
    // .wnd layouts and textures -- it REPLACES English rather than adding a token, and wins
    // because archives are mounted in name order, first one wins, and "00..." sorts first.
    //
    // Both shapes are now recognised: a loose data/<token>/generals.csf, or that path inside
    // any .big in the game folder (or one level down -- ZH_Generals/ and friends).
    private static boolean gameHasTextFor(String gamePath, String token) {
        if (gamePath == null || token == null) {
            return false;
        }
        // GeneralsX @feature Android port 09/09/2026 Either format counts. generals.str is
        // the plain-text one the engine now prefers and the one a language pack ships as
        // (languages/README.md); generals.csf is the compiled form the original SKUs and the
        // old .big translations carry. Asking only about the .csf would have told a player
        // who just downloaded a pack that their game folder had nothing in it.
        for (String leaf : new String[] { "generals.str", "generals.csf" }) {
            if (new File(gamePath, "data/" + token + "/" + leaf).isFile()) {
                return true;
            }
        }
        File root = new File(gamePath);
        File[] children = root.listFiles();
        for (String leaf : new String[] { "generals.str", "generals.csf" }) {
            String wanted = ("data/" + token + "/" + leaf).toLowerCase(java.util.Locale.ROOT);
            if (bigContainsEntry(root, wanted)) {
                return true;
            }
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory() && bigContainsEntry(child, wanted)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean bigContainsEntry(File dir, String wantedLowerSlash) {
        File[] bigs = dir.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".big"));
        if (bigs == null) {
            return false;
        }
        for (File big : bigs) {
            if (bigContainsEntry(big, wantedLowerSlash, 0)) {
                return true;
            }
        }
        return false;
    }

    // BIGF layout: "BIGF", archive size (little-endian), then file count and the offset of
    // the first file, both BIG-endian, then one record per file -- offset, size, and a
    // NUL-terminated name. Only the names are read here; nothing is decompressed, so this
    // costs a few kilobytes per archive however large the archive is.
    private static boolean bigContainsEntry(File big, String wantedLowerSlash, int unusedDepth) {
        final boolean[] hit = { false };
        forEachBigEntry(big, name -> {
            if (name.equals(wantedLowerSlash)) {
                hit[0] = true;
            }
        });
        return hit[0];
    }

    // BIGF layout: "BIGF", archive size (little-endian), then file count and the offset of
    // the first file, both BIG-endian, then one record per file -- offset, size, and a
    // NUL-terminated name. Only the names are read here; nothing is decompressed, so this
    // costs a few kilobytes per archive however large the archive is. Names are handed over
    // lowercased with backslashes turned into forward slashes, which is how every caller
    // wants to compare them.
    private static void forEachBigEntry(File big, java.util.function.Consumer<String> visit) {
        try (java.io.DataInputStream in =
                 new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(big), 1 << 16))) {
            byte[] magic = new byte[4];
            in.readFully(magic);
            if (magic[0] != 'B' || magic[1] != 'I' || magic[2] != 'G' || magic[3] != 'F') {
                return;
            }
            in.skipBytes(4);                 // archive size, little-endian, not needed
            int count = in.readInt();        // big-endian
            in.readInt();                    // offset of the first file, not needed
            if (count <= 0 || count > 200000) {
                return;                      // not a shape we recognise; do not chew memory over it
            }
            StringBuilder name = new StringBuilder(64);
            for (int i = 0; i < count; i++) {
                in.readInt();                // entry offset
                in.readInt();                // entry size
                name.setLength(0);
                int c;
                while ((c = in.read()) > 0) {
                    name.append((char) (c == '\\' ? '/' : c));
                }
                if (c < 0) {
                    return;                  // truncated archive
                }
                visit.accept(name.toString().toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception e) {
            // An unreadable or unfamiliar archive is not an error worth surfacing: it just
            // does not answer the question the caller asked.
        }
    }


    // GeneralsX @feature Android port 09/09/2026 Fetch a language pack straight from the
    // project's own repository.
    //
    // A language is one plain-text file now (languages/<token>/generals.str -- see
    // languages/README.md), so installing one is downloading a file into
    // data/<token>/generals.str inside the game folder. No archive, no unpacking, no
    // asking a player to find a folder with a file manager. And because the pack is a text
    // file in the repository, a translator's pull request reaches players as soon as it is
    // merged.
    private static final String LANGUAGE_PACK_INDEX =
        "https://api.github.com/repos/MYSOREZ/GeneralsZH-Android-Port/contents/languages?ref=main";
    private static final String LANGUAGE_PACK_BASE =
        "https://raw.githubusercontent.com/MYSOREZ/GeneralsZH-Android-Port/main/languages/";
    // GeneralsX @bugfix Android port 19/10/2026 The list of languages is a plain file served like the
    // packs themselves. api.github.com allows 60 requests an hour per address without signing in, and
    // a mobile operator puts a great many phones behind one address, so the directory listing above
    // answered HTTP 403 to players who had never pressed the button before. raw.githubusercontent.com
    // has no such limit; the listing stays only as a fallback for a branch without the file.
    private static final String LANGUAGE_PACK_LIST = LANGUAGE_PACK_BASE + "index.txt";

    // GeneralsX @feature Android port 09/09/2026 Fetch every pack there is, and do not ask
    // which one first.
    //
    // Asking was the wrong question twice over. The list it asked from was hardcoded, so a
    // language nobody had thought of when it was written -- Ukrainian, the very next one
    // contributed -- could not be chosen and therefore could not be downloaded. And the
    // question is premature anyway: choosing belongs to the picker below, which lists what is
    // really in the game folder, and there is nothing to choose between until something has
    // been downloaded. So this downloads all of them, the picker then shows what arrived, and
    // the set of languages comes from the repository rather than from this file.
    private void onDownloadLanguagePack() {
        final String gamePath = getSavedGamePath();
        if (gamePath == null) {
            toast(getString(R.string.setup_langpack_no_folder));
            return;
        }
        if (languagePackButton != null) {
            languagePackButton.setEnabled(false);
        }
        toast(getString(R.string.setup_langpack_downloading));

        new Thread(() -> {
            final java.util.List<String> tokens = new java.util.ArrayList<>();
            String listError = fetchLanguagePackList(tokens);
            if (listError != null) {
                tokens.clear();
                listError = fetchLanguagePackIndex(tokens);
            }
            final String indexError = listError;
            final java.util.List<String> installed = new java.util.ArrayList<>();
            // Each language with its reason: a list of language names alone (what this used to
            // show) said that something failed and nothing about what.
            final java.util.Map<String, java.util.List<String>> failedByReason =
                new java.util.LinkedHashMap<>();
            if (indexError == null) {
                for (String token : tokens) {
                    final String reason = downloadLanguagePack(token, gamePath);
                    if (reason == null) {
                        installed.add(token);
                    } else {
                        java.util.List<String> group = failedByReason.get(reason);
                        if (group == null) {
                            group = new java.util.ArrayList<>();
                            failedByReason.put(reason, group);
                        }
                        group.add(token);
                    }
                }
            }
            runOnUiThread(() -> {
                if (languagePackButton != null) {
                    languagePackButton.setEnabled(true);
                }
                if (indexError != null) {
                    toast(getString(R.string.setup_langpack_failed, indexError));
                    return;
                }
                if (installed.isEmpty()) {
                    toast(getString(R.string.setup_langpack_failed,
                        failedByReason.isEmpty() ? getString(R.string.setup_langpack_err_none_yet)
                                                 : describeLanguagePackFailures(failedByReason)));
                    return;
                }
                updateGameLanguageStatusView();
                StringBuilder names = new StringBuilder();
                for (String token : installed) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(gameTextName(token));
                }
                toast(getString(R.string.setup_langpack_done_n, installed.size(), names.toString()));
            });
        }, "gx-langpack").start();
    }

    /** One reason when every language failed the same way, else each reason with its languages. */
    private String describeLanguagePackFailures(java.util.Map<String, java.util.List<String>> byReason) {
        if (byReason.size() == 1) {
            return byReason.keySet().iterator().next();
        }
        StringBuilder text = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : byReason.entrySet()) {
            if (text.length() > 0) {
                text.append("; ");
            }
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String token : entry.getValue()) {
                names.add(gameTextName(token));
            }
            text.append(android.text.TextUtils.join(", ", names)).append(": ").append(entry.getKey());
        }
        return text.toString();
    }

    /**
     * The languages listed in languages/index.txt, one folder name a line ('#' starts a comment).
     * Returns null on success, else a reason.
     */
    private String fetchLanguagePackList(java.util.List<String> out) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(LANGUAGE_PACK_LIST).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "text/plain");
            final int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return "HTTP " + status;
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    final int hash = line.indexOf('#');
                    final String token = (hash >= 0 ? line.substring(0, hash) : line).trim();
                    // A folder name, nothing that could climb out of data/ in the game folder.
                    if (token.matches("[a-z][a-z0-9_-]*")) {
                        out.add(token);
                    }
                }
            }
            return out.isEmpty() ? getString(R.string.setup_langpack_err_none_yet) : null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return (msg == null || msg.isEmpty()) ? e.getClass().getSimpleName() : msg;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Ask the repository which languages exist. Returns null on success, else a reason.
     *
     * The directory listing IS the list -- add languages/<name>/generals.str to the project
     * and it appears here, with nothing in this app to update.
     */
    private String fetchLanguagePackIndex(java.util.List<String> out) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(LANGUAGE_PACK_INDEX);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            final int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                return "HTTP " + status;
            }
            StringBuilder body = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    body.append(line);
                }
            }
            org.json.JSONArray entries = new org.json.JSONArray(body.toString());
            for (int i = 0; i < entries.length(); i++) {
                org.json.JSONObject entry = entries.getJSONObject(i);
                if ("dir".equals(entry.optString("type"))) {
                    out.add(entry.optString("name"));
                }
            }
            return out.isEmpty() ? getString(R.string.setup_langpack_err_none_yet) : null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return (msg == null || msg.isEmpty()) ? e.getClass().getSimpleName() : msg;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** @return null on success, else a short reason to put in front of the user. */
    private String downloadLanguagePack(String token, String gamePath) {
        HttpURLConnection conn = null;
        // Written beside the real file and renamed at the end: a half-downloaded
        // generals.str in place would leave the game with a truncated string table, which
        // fails in a far more confusing way than not having the file at all.
        File dir = new File(gamePath, "data/" + token);
        File tmp = new File(dir, "generals.str.part");
        File dest = new File(dir, "generals.str");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return getString(R.string.setup_langpack_err_mkdir);
            }
            // GeneralsX @feature Android port 18/10/2026 A file per game (PLAN-026 step 1): Zero
            // Hour's translation is generalszh.str, the base game's generals.str. Either way it is
            // installed as data/<language>/generals.str in that game's own folder, the name the
            // engine reads.
            final String remoteFile = isGeneralsSelected(this) ? "generals.str" : "generalszh.str";
            URL url = new URL(LANGUAGE_PACK_BASE + token + "/" + remoteFile);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept", "text/plain");
            final int status = conn.getResponseCode();
            if (status == 404) {
                return getString(R.string.setup_langpack_err_none_yet);
            }
            if (status < 200 || status >= 300) {
                return "HTTP " + status;
            }
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            try (java.io.InputStream in = conn.getInputStream();
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 8L * 1024 * 1024) {
                        return "> 8 MB";   // a string table is a few hundred KB; this is not one
                    }
                }
            }
            if (total == 0) {
                return getString(R.string.setup_langpack_err_empty);
            }
            if (dest.exists() && !dest.delete()) {
                return getString(R.string.setup_langpack_err_replace);
            }
            if (!tmp.renameTo(dest)) {
                return getString(R.string.setup_langpack_err_replace);
            }
            return null;
        } catch (Exception e) {
            String msg = e.getMessage();
            return (msg == null || msg.isEmpty()) ? e.getClass().getSimpleName() : msg;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            if (tmp.exists()) {
                tmp.delete();
            }
        }
    }

    private void toast(CharSequence text) {
        gxToast(this, text, android.widget.Toast.LENGTH_LONG).show();
    }

    private com.google.android.material.button.MaterialButton languagePackButton;

    private String getSavedGamePath() {
        return getSavedGamePath(this);
    }

    // Public + static so GeneralsZHActivity uses the exact same recovery
    // logic instead of its own copy that only ever checked SharedPreferences.
    static String getSavedGamePath(android.content.Context ctx) {
        return getSavedGamePath(ctx, getSelectedGame(ctx));
    }

    static String getSavedGamePath(android.content.Context ctx, String game) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String key = gamePathPref(game);
        String path = prefs.getString(key, null);
        if (path != null) {
            return path;
        }

        // Not in this install's private prefs (fresh install after an
        // uninstall, most likely) -- check the external marker left by a
        // previous install and self-heal by restoring it into prefs. The
        // native marker is written on launch (writeNativeGameMarker()).
        String recovered = readExternalMarker(game);
        if (recovered != null && isValidGameFolder(new File(recovered), game)) {
            prefs.edit().putString(key, recovered).apply();
            return recovered;
        }

        // GeneralsX @feature Android port 04/10/2026 Someone who already pointed Zero Hour at
        // their base Generals archives has, by definition, a Generals folder: offer it.
        if (GAME_GENERALS.equals(game)) {
            String base = prefs.getString(PREF_BASE_GENERALS_PATH, null);
            if (base != null && isValidGameFolder(new File(base), game)) {
                prefs.edit().putString(key, base).apply();
                return base;
            }
        }
        return null;
    }

    // GeneralsX @feature Android port 04/10/2026 The folder SDL3Main.cpp enters is a plain-text
    // marker (native code has no SharedPreferences access). With two games it has to name the one
    // about to start, so it is written right before the engine loads rather than when a folder is
    // picked.
    static boolean writeNativeGameMarker(android.content.Context ctx, String path) {
        File marker = new File(ctx.getFilesDir(), "gamedata_path.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(path);
            w.write("\n");
            return true;
        } catch (java.io.IOException e) {
            android.util.Log.w("SetupActivity", "could not write the game folder marker", e);
            return false;
        }
    }

    private static File externalMarkerFile(String game) {
        File root = Environment.getExternalStorageDirectory();
        String name = GAME_GENERALS.equals(game) ? GENERALS_EXTERNAL_MARKER_NAME : EXTERNAL_MARKER_NAME;
        return root != null ? new File(root, name) : null;
    }

    private static String readExternalMarker(String game) {
        File marker = externalMarkerFile(game);
        if (marker == null || !marker.isFile()) {
            return null;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker))) {
            String line = r.readLine();
            return (line != null && !line.isEmpty()) ? line.trim() : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // GeneralsX @feature Android port 04/10/2026 What is wrong with a folder that is not a game
    // folder, for the game it was picked for.
    private String invalidFolderMessage(File dir) {
        if (!isGeneralsSelected(this)) {
            return getString(R.string.setup_status_folder_invalid);
        }
        if (dir != null && new File(dir, ZERO_HOUR_ONLY_FILE).exists()) {
            return getString(R.string.setup_status_folder_is_zero_hour);
        }
        return getString(R.string.setup_status_folder_invalid_generals);
    }

    // The only game folder within three levels below dir, or null when there is none or more than
    // one (then the player has to choose). Bounded, because shared storage behind FUSE is slow to list.
    static File findGameFolderBelow(File dir, String game) {
        java.util.ArrayDeque<File> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depths = new java.util.ArrayDeque<>();
        queue.add(dir);
        depths.add(0);
        File found = null;
        int listed = 0;
        while (!queue.isEmpty() && listed < 300) {
            File d = queue.poll();
            int depth = depths.poll();
            File[] children = d.listFiles(File::isDirectory);
            listed++;
            if (children == null) {
                continue;
            }
            for (File c : children) {
                if (isValidGameFolder(c, game)) {
                    if (found != null) {
                        return null;
                    }
                    found = c;
                } else if (depth + 1 < 3 && !c.getName().startsWith(".")) {
                    queue.add(c);
                    depths.add(depth + 1);
                }
            }
        }
        return found;
    }

    static boolean isValidGameFolder(File dir, String game) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        if (GAME_GENERALS.equals(game)) {
            return new File(dir, GENERALS_REQUIRED_FILE).exists() && !new File(dir, ZERO_HOUR_ONLY_FILE).exists();
        }
        for (String name : REQUIRED_GAME_FILES) {
            if (new File(dir, name).exists()) {
                return true;
            }
        }
        return false;
    }

    // GeneralsX @feature Android port game-folder-integrity-check 07/09/2026
    // isValidGameFolder() above only checks that INIZH.big/INI.big EXIST --
    // a truncated/incomplete copy (interrupted transfer, bad extraction,
    // etc.) can pass that check while still crashing the engine deep in
    // INI::loadFileDirectory() the first time it hits a directory whose
    // data never made it into the archive: a real user report hit
    // "[INI] ERROR: No files read from directory 'Data\INI\Default\Weather'"
    // followed by an uncaught C++ exception during GameEngine::init() --
    // well before any graphics/Vulkan code runs, so it isn't a driver bug.
    // The same broken copy also failed the same way in Winlator (a
    // completely different Wine/DXVK compatibility layer), confirming it's
    // the user's own file set, not this port's code.
    //
    // These two checks stay cheap by only reading each .big's small BIGF
    // directory table (a header + a null-terminated path per entry), never
    // the actual multi-hundred-MB file payloads the table points at:
    //   1. Every *.big already present must start with the "BIGF" magic
    //      and be non-zero size (catches an empty/corrupted download).
    //   2. Whichever ini archive is present (INI.big or INIZH.big) must
    //      contain Data/INI/Default/Weather.ini specifically -- the exact
    //      file the real crash above needed, and a reasonable proxy for
    //      "this archive's directory table wasn't truncated partway
    //      through", since an interrupted copy would most likely lose
    //      entries somewhere in the middle of the table, not leave out
    //      exactly and only this one file.
    //
    // BIG format read from this project's own parser --
    // Core/GameEngineDevice/Source/StdDevice/Common/StdBIGFileSystem.cpp,
    // openArchiveFile(): "BIGF" magic at offset 0, file count (big-endian)
    // at offset 8, directory table starting at offset 0x10, each entry is
    // [4-byte offset, big-endian][4-byte size, big-endian][null-terminated
    // backslash-separated path].
    private static final String BIG_CRITICAL_ENTRY = "data\\ini\\default\\weather.ini";
    // GeneralsX @bugfix Android port 04/10/2026 Weather.ini is Zero Hour's: the original Generals has
    // no weather settings at all, so every complete Generals copy failed the check above. GameData.ini
    // is the first INI the base game's engine loads, and every release of it has one.
    private static final String GENERALS_BIG_CRITICAL_ENTRY = "data\\ini\\gamedata.ini";

    // GeneralsX @bugfix Android port game-folder-integrity-check 08/30/2026
    // Some retail/Deluxe layouts don't put the base Generals archives (incl.
    // INI.big) next to the ZH ones -- they nest an entire base-game copy in
    // a subfolder instead (confirmed via real "Generals Deluxe" install
    // screenshots: root has INIZH.big + *ZH.big, and a "ZH_Generals"
    /** Data\Scripts\SkirmishScripts.scb in the folder, any letter case, or inside one of its archives. */
    private static boolean hasSkirmishScripts(File dir) {
        File level = dir;
        for (String part : new String[] { "data", "scripts", "skirmishscripts.scb" }) {
            File[] children = level.listFiles();
            File next = null;
            if (children != null) {
                for (File c : children) {
                    if (c.getName().equalsIgnoreCase(part)) {
                        next = c;
                        break;
                    }
                }
            }
            if (next == null) {
                return bigContainsEntry(dir, "data/scripts/skirmishscripts.scb");
            }
            level = next;
        }
        return level.isFile() && level.length() > 0;
    }

    // subfolder has a plain INI.big alongside unsuffixed archives). The
    // engine itself already expects layouts like this -- see
    // loadBaseGeneralsAssetsForZH() in StdBIGFileSystem.cpp, which tries a
    // handful of base-game locations including a "ZH_Generals" sibling --
    // but that folder name isn't the only one real installers have used
    // over the years, so rather than hardcode it, scan every immediate
    // subfolder of the selected directory for ini archives. One level deep
    // only: cheap (just a directory listing per subfolder), and matches
    // what the engine itself is willing to look for automatically.
    private java.util.List<String> findGameFolderIntegrityIssues(File dir) {
        java.util.List<String> issues = new java.util.ArrayList<>();
        m_lastCheckWantedBaseGenerals = false;
        final boolean generalsFolder = isGeneralsSelected(this);
        if (dir == null || !dir.isDirectory()) {
            return issues;
        }
        java.util.List<File> scanRoots = new java.util.ArrayList<>();
        scanRoots.add(dir);
        File[] subdirs = dir.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File sub : subdirs) {
                scanRoots.add(sub);
            }
        }

        // GeneralsX @bugfix Android port game-folder-integrity-check 07/09/2026
        // INI.big (base Generals) and INIZH.big (the Zero Hour expansion)
        // both get loaded and merged into one virtual file list by the
        // engine's ArchiveFileSystem -- they don't replace each other, ZH
        // only overrides/adds specific entries on top of the base game's.
        // Weather.ini is base-game content, not something Zero Hour
        // replaces, so on an install with both archives it can legitimately
        // live in INI.big while INIZH.big itself is missing/corrupted --
        // checking only whichever one was found last would have produced a
        // false "missing data" report. Collect every present ini archive
        // (root and any subfolder alike) and only flag a problem if NONE of
        // them have the entry, matching how the actual merged filesystem
        // resolves it.
        java.util.List<File> iniArchives = new java.util.ArrayList<>();
        java.util.Set<String> presentArchives = new java.util.HashSet<>();
        for (File root : scanRoots) {
            File[] bigFiles = root.listFiles((d, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".big"));
            if (bigFiles == null) {
                continue;
            }
            String prefix = (root == dir) ? "" : (root.getName() + "\\");
            for (File f : bigFiles) {
                if (f.length() == 0) {
                    issues.add(getString(R.string.setup_folder_issue_empty_file, prefix + f.getName()));
                    continue;
                }
                if (!hasBigFHeader(f)) {
                    issues.add(getString(R.string.setup_folder_issue_bad_header, prefix + f.getName()));
                    continue;
                }
                String lower = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (lower.equals("ini.big") || lower.equals("inizh.big")) {
                    iniArchives.add(f);
                }
                presentArchives.add(lower);
            }
        }
        if (!iniArchives.isEmpty()) {
            boolean found = false;
            for (File f : iniArchives) {
                if (bigArchiveHasEntry(f, generalsFolder ? GENERALS_BIG_CRITICAL_ENTRY : BIG_CRITICAL_ENTRY)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                StringBuilder names = new StringBuilder();
                for (File f : iniArchives) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    File parent = f.getParentFile();
                    boolean nested = parent != null && !parent.equals(dir);
                    names.append(nested ? (parent.getName() + "\\" + f.getName()) : f.getName());
                }
                issues.add(getString(R.string.setup_folder_issue_missing_data, names.toString()));
            }
        }

        // GeneralsX @bugfix Android port game-folder-integrity-check 06/09/2026
        // A copy holding only *ZH.big archives passes every check above -- the
        // archives are all present, valid and complete -- and then renders its
        // terrain and half its units in solid magenta, because Zero Hour is an
        // expansion: its archives carry only what it added or changed, and the
        // original game's artwork lives in the base game's Terrain.big /
        // Textures.big / W3D.big. Traced from a real report: every archive in
        // the folder mounted successfully, yet trdirtroad, trsidewalk,
        // trtwolane, trtraintrack and their neighbours were nowhere in the
        // merged filesystem, so the engine substituted the magenta
        // missing-texture placeholder for each of them.
        //
        // Only flag it when ALL of the base asset archives are absent from
        // every scanned root: a copy missing one of the three is unusual but
        // could still be deliberate, whereas none of the three is
        // unambiguously a Zero-Hour-only folder. Deliberately keyed on the
        // asset archives rather than "any archive without a ZH suffix" --
        // Music.big is commonly present on its own and would mask the problem.
        // A folder chosen as the base-Generals location supplies these instead, so
        // do not go on demanding them here -- the warning would be permanent and
        // wrong for exactly the people who already did the right thing.
        // GeneralsX @feature Android port 04/10/2026 For Generals itself these archives are its own:
        // nothing elsewhere can stand in for them.
        final String basePath = getBaseGeneralsPath();
        final boolean baseFolderCovers = !generalsFolder &&
            basePath != null && missingBaseGeneralsArchives(new File(basePath)).isEmpty();

        StringBuilder missing = new StringBuilder();
        if (!baseFolderCovers) {
            for (String name : BASE_GAME_REQUIRED_ARCHIVES) {
                if (!presentArchives.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                    missing.append("\n  \u2022 ").append(name);
                }
            }
        }
        if (missing.length() > 0 && generalsFolder) {
            issues.add(getString(R.string.setup_folder_issue_generals_incomplete, missing.toString()));
        } else if (missing.length() > 0) {
            issues.add(getString(R.string.setup_folder_issue_no_base_game, missing.toString()));
            m_lastCheckWantedBaseGenerals = true;
        }

        // GeneralsX @bugfix Android port 25/10/2026 The computer opponents' scripts are a loose file
        // beside the archives (Data\Scripts\SkirmishScripts.scb), not inside them. A copy of only the
        // .big files plays, but every AI player keeps its command center and one idle dozer for the
        // whole match (issue #64). Found loose, or inside an archive (some repacks put it there),
        // it is fine.
        if (!hasSkirmishScripts(dir)) {
            issues.add(getString(R.string.setup_folder_issue_no_ai_scripts));
        }

        // GeneralsX @bugfix Android port game-folder-integrity-check 06/09/2026
        // A check for a base-game language archive used to live here, matching
        // English.big / German.big / ... against the list registry.cpp probes.
        // Removed: it fired on a copy that plays perfectly well. Localisations
        // are routinely shipped as mods rather than as the archive a retail
        // installer would have written -- a real one here is named
        // 00RussianZH.big, its leading zeroes chosen so it sorts first and wins
        // the load order -- and no list of expected names can cover that. A
        // check that calls a working setup broken is worse than no check, and
        // what it guarded against costs some text and voices, not a playable
        // game.
        return issues;
    }

    // GeneralsX @bugfix Android port game-folder-integrity-check 06/09/2026
    // The base Generals archives, named as they ship. Zero Hour's own archives
    // all carry a ZH suffix and hold only what the expansion added or changed,
    // so a copy with nothing but *ZH.big passes every other check here -- all
    // archives valid, none truncated -- and then renders the original game's
    // terrain and units as solid magenta. Reported as the exact list of names
    // that are absent, because "your copy is incomplete" leaves the user with
    // nothing to act on.
    //
    // Deliberately limited to archives whose base-game names are certain.
    // Language-specific ones (English.big and the AudioEnglish/SpeechEnglish/
    // W3DEnglish family) are left out: which of them a given release ships
    // varies by SKU and language, and naming one that a perfectly good copy
    // never had would send people hunting for a file that does not exist.
    // A missing language archive also degrades far more gracefully than a
    // missing Textures.big.
    // GeneralsX @bugfix Android port 06/09/2026 TOP LEVEL ONLY, deliberately.
    // This also looked one folder deep, which made Setup more permissive than
    // the engine and produced the worst possible answer: pick the root of a
    // Steam copy, be told the folder is fine because the archives sit in its
    // ZH_Generals child, then watch the game render magenta anyway.
    //
    // The engine cannot see that child. StdLocalFileSystem::getFileListInDirectory()
    // recurses by passing the subfolder's NAME with an empty originalDirectory,
    // so the recursive call resolves it against the working directory instead of
    // the folder being scanned -- fine for the relative scan of the game folder,
    // useless for the absolute path CNC_GENERALS_PATH carries. Until that is
    // fixed in the engine, Setup has to judge a folder by what the engine will
    // really find in it; resolveBaseGeneralsFolder() descends into the child on
    // the user's behalf instead of pretending the engine would.
    java.util.List<String> missingBaseGeneralsArchives(File dir) {
        java.util.Set<String> present = new java.util.HashSet<>();
        if (dir != null && dir.isDirectory()) {
            File[] bigs = dir.listFiles((d, name) ->
                name.toLowerCase(java.util.Locale.ROOT).endsWith(".big"));
            if (bigs != null) {
                for (File f : bigs) {
                    present.add(f.getName().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String name : BASE_GAME_REQUIRED_ARCHIVES) {
            if (!present.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                missing.add(name);
            }
        }
        return missing;
    }

    // The three archives Zero Hour cannot supply for itself and that a magenta
    // battlefield depends on. A folder without a single one of them is not a
    // base Generals folder, whatever else it happens to contain -- one stray
    // Music.big was enough to fool an earlier version of this check.
    private static final String[] BASE_GAME_ASSET_ARCHIVES = { "terrain.big", "textures.big", "w3d.big" };

    private boolean hasAnyBaseGeneralsAssets(File dir) {
        java.util.List<String> missing = missingBaseGeneralsArchives(dir);
        for (String name : BASE_GAME_ASSET_ARCHIVES) {
            boolean absent = false;
            for (String m : missing) {
                if (m.toLowerCase(java.util.Locale.ROOT).equals(name)) {
                    absent = true;
                    break;
                }
            }
            if (!absent) {
                return true;
            }
        }
        return false;
    }

    private File resolveBaseGeneralsFolder(File picked) {
        if (picked == null || !picked.isDirectory()) {
            return null;
        }
        if (hasAnyBaseGeneralsAssets(picked)) {
            return picked;
        }
        File[] subdirs = picked.listFiles(File::isDirectory);
        if (subdirs != null) {
            java.util.Arrays.sort(subdirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File sub : subdirs) {
                if (hasAnyBaseGeneralsAssets(sub)) {
                    return sub;
                }
            }
        }
        return null;
    }

    // Set by findGameFolderIntegrityIssues(): whether the problems it just
    // found are ones that pointing Setup at a base-Generals folder would fix.
    private boolean m_lastCheckWantedBaseGenerals = false;

    private static final String[] BASE_GAME_REQUIRED_ARCHIVES = {
        "INI.big", "Terrain.big", "Textures.big", "W3D.big", "Window.big",
        "Shaders.big", "Audio.big", "Speech.big", "Maps.big", "Music.big"
    };

    private static boolean hasBigFHeader(File f) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            byte[] magic = new byte[4];
            if (raf.read(magic) != 4) {
                return false;
            }
            return magic[0] == 'B' && magic[1] == 'I' && magic[2] == 'G' && magic[3] == 'F';
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int readBigEndianInt(java.io.RandomAccessFile raf) throws java.io.IOException {
        int b0 = raf.read(), b1 = raf.read(), b2 = raf.read(), b3 = raf.read();
        if ((b0 | b1 | b2 | b3) < 0) {
            throw new java.io.EOFException();
        }
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static boolean bigArchiveHasEntry(File bigFile, String targetLowerPath) {
        if (!bigFile.isFile() || bigFile.length() < 0x10) {
            return false;
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(bigFile, "r")) {
            if (!hasBigFHeader(bigFile)) {
                return false;
            }
            raf.seek(8);
            int numFiles = readBigEndianInt(raf);
            if (numFiles < 0 || numFiles > 500000) {
                return false;  // sanity guard against a corrupted/garbage header
            }
            raf.seek(0x10);
            byte[] nameBuf = new byte[512];
            for (int i = 0; i < numFiles; i++) {
                raf.skipBytes(8);  // per-entry offset + size, not needed for this check
                int len = 0;
                int b;
                while ((b = raf.read()) > 0) {
                    if (len < nameBuf.length - 1) {
                        nameBuf[len++] = (byte) b;
                    }
                }
                if (b < 0) {
                    return false;  // ran off the end of the file mid-entry -- truncated archive
                }
                String path = new String(nameBuf, 0, len, java.nio.charset.StandardCharsets.US_ASCII)
                    .toLowerCase(java.util.Locale.ROOT);
                if (path.equals(targetLowerPath)) {
                    return true;
                }
            }
        } catch (java.io.IOException e) {
            return false;
        }
        return false;
    }

    private static final int REQUEST_LEGACY_STORAGE_PERMISSION = 1003;

    private void onSelectGameFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                gxToast(this, R.string.setup_toast_grant_all_files, Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
                return;
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // MANAGE_EXTERNAL_STORAGE / isExternalStorageManager() don't exist
            // before API 30 -- this is the pre-R equivalent, otherwise
            // FolderPickerActivity opens with no storage permission at all
            // and its File.listFiles() silently comes back empty.
            ActivityCompat.requestPermissions(this,
                new String[] { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE },
                REQUEST_LEGACY_STORAGE_PERMISSION);
            return;
        }
        startActivityForResult(new Intent(this, FolderPickerActivity.class), 1001);
    }

    // GeneralsX @feature Android port 06/09/2026 Second picker, same browser,
    // different destination. Deliberately not merged with the game-folder flow:
    // that one validates what it is given as a Zero Hour folder, and this one
    // must accept the opposite -- a folder with base archives and no *ZH.big.
    private void onSelectBaseGeneralsFolder() {
        startActivityForResult(new Intent(this, FolderPickerActivity.class), REQUEST_PICK_BASE_GENERALS);
    }

    private void onClearBaseGeneralsFolder() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(PREF_BASE_GENERALS_PATH).apply();
        new File(getFilesDir(), "generals_base_path.txt").delete();
        gxToast(this, R.string.setup_toast_base_generals_cleared, Toast.LENGTH_LONG).show();
        // Only the Home card changes shape (the "clear" button disappears) --
        // rebuilding that one page is enough, and keeps the user where they
        // are instead of restarting the whole Activity.
        showTab(TAB_HOME);
    }

    String getBaseGeneralsPath() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_BASE_GENERALS_PATH, null);
    }

    private void saveBaseGeneralsPath(String path) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_BASE_GENERALS_PATH, path)
            .apply();
        // SDL3Main.cpp reads this plain-text marker on the next launch and turns
        // it into CNC_GENERALS_PATH, which is the first location the engine's
        // base-game search consults. Native code cannot read SharedPreferences,
        // hence the file -- same arrangement as gamedata_path.txt.
        File marker = new File(getFilesDir(), "generals_base_path.txt");
        try (java.io.FileWriter w = new java.io.FileWriter(marker, false)) {
            w.write(path);
            w.write("\n");
        } catch (java.io.IOException e) {
            gxToast(this, getString(R.string.setup_toast_marker_save_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LEGACY_STORAGE_PERMISSION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                startActivityForResult(new Intent(this, FolderPickerActivity.class), 1001);
            } else if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
                // GeneralsX @bugfix Android port 24/09/2026 Issue #22: after "Don't ask again" the
                // system denies without showing a prompt, so retrying from here can never work.
                // Send the user to this app's settings page, where the Storage permission lives.
                gxToast(this, R.string.folderpicker_toast_cant_read_legacy, Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    // Some OEM builds lack the per-app page; the app list is the next best thing.
                    startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                }
            } else {
                gxToast(this, R.string.setup_toast_storage_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MODS) {
            // Back on Home: the mods switched on may have changed.
            showTab(currentTab);
            return;
        }
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
            if (path != null) {
                // GeneralsX @feature Android port 04/10/2026 A common mistake (4PDA report): picking the
                // folder the archive was unpacked INTO, while the game sits one or two folders down.
                // If exactly one folder below the pick is a game folder, take that one.
                if (!isValidGameFolder(new File(path), getSelectedGame(this))) {
                    File inside = findGameFolderBelow(new File(path), getSelectedGame(this));
                    if (inside != null) {
                        path = inside.getAbsolutePath();
                        gxToast(this, getString(R.string.setup_toast_folder_found_inside, path),
                            Toast.LENGTH_LONG).show();
                    }
                }
                saveGamePath(path);
                refreshStatus();
                File dir = new File(path);
                boolean valid = isValidGameFolder(dir, getSelectedGame(this));
                // GeneralsX @feature Android port game-folder-integrity-check
                // 07/09/2026 - a Toast auto-dismisses in a couple of seconds
                // and is easy to miss entirely; a real problem here means
                // the game WILL crash on launch, so it gets a blocking
                // dialog the user has to acknowledge instead. The colored
                // status line in refreshStatus() (visible right below) still
                // covers "I want to re-check the status later" without
                // re-triggering the dialog every time the screen redraws.
                if (!valid) {
                    showFolderProblemDialog(invalidFolderMessage(dir).trim());
                } else {
                    java.util.List<String> issues = findGameFolderIntegrityIssues(dir);
                    if (!issues.isEmpty()) {
                        showFolderProblemDialog(getString(R.string.setup_status_folder_incomplete,
                            joinLines(issues)).trim(), m_lastCheckWantedBaseGenerals);
                    } else {
                        gxToast(this, R.string.setup_toast_folder_saved, Toast.LENGTH_LONG).show();
                    }
                }
            }
        } else if (requestCode == REQUEST_PICK_BASE_GENERALS && resultCode == Activity.RESULT_OK && data != null) {
            String path = data.getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
            if (path != null) {
                // GeneralsX @bugfix Android port 06/09/2026 Judged on the archives
                // that actually carry the original game's artwork -- an earlier
                // version accepted a folder unless ALL ten were absent, which one
                // stray Music.big was enough to defeat.
                //
                // Picking the root of a Steam install is the natural thing to do,
                // so resolve it to the child that really holds them rather than
                // saving a path the engine cannot make use of.
                File resolved = resolveBaseGeneralsFolder(new File(path));
                if (resolved == null) {
                    showFolderProblemDialog(getString(R.string.setup_base_generals_not_here, path));
                } else {
                    saveBaseGeneralsPath(resolved.getAbsolutePath());
                    gxToast(this, getString(R.string.setup_toast_base_generals_saved,
                        resolved.getAbsolutePath()), Toast.LENGTH_LONG).show();
                    showTab(TAB_HOME);
                }
            }
        } else if (requestCode == REQUEST_IMPORT_DRIVER && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                importCustomDriver(uri);
            }
        } else if (requestCode == REQUEST_IMPORT_REPLAY && resultCode == Activity.RESULT_OK && data != null) {
            java.util.List<Uri> picked = new java.util.ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    picked.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (!picked.isEmpty()) {
                importReplayDocuments(picked);
            }
        } else if (requestCode == REQUEST_IMPORT_MAP && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                importMapDocument(uri);
            }
        } else if (requestCode == REQUEST_IMPORT_HOTKEYS && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                importHotkeyDocument(uri);
            }
        } else if (requestCode == REQUEST_IMPORT_CONTROLBAR && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                importControlBarDocument(uri);
            }
        }
    }

    private void saveGamePath(String path) {
        final String game = getSelectedGame(this);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(gamePathPref(game), path)
            .apply();
        // GeneralsZHActivity/SDL3Main.cpp read this plain-text marker on the
        // NEXT launch (native code has no Android SharedPreferences access).
        // GeneralsZHActivity rewrites it for whichever game is started.
        if (!writeNativeGameMarker(this, path)) {
            gxToast(this, getString(R.string.setup_toast_marker_save_failed, path), Toast.LENGTH_LONG).show();
        }
        File externalMarker = externalMarkerFile(game);
        if (externalMarker != null) {
            try (java.io.FileWriter w = new java.io.FileWriter(externalMarker, false)) {
                w.write(path);
                w.write("\n");
            } catch (java.io.IOException e) {
                // Not fatal: uninstall-survival just won't work for this
                // install; the private-prefs/marker-file path above still
                // covers normal in-place updates.
            }
        }
        File bundledRoot = getExternalFilesDir(null);
        if (bundledRoot != null) {
            copyBundledRuntimeIfMissing(bundledRoot, path);
        }
        applyGameLanguageOverride();
    }

    // GeneralsX @bugfix Android port 07/07/2026 dxvk.conf, DefaultOptions.ini,
    // AND the entire fonts/ directory are all read by the engine relative to
    // its CWD (see SDL3Main.cpp and render2dsentence.cpp's
    // Locate_Font_FontConfig, which does access("fonts/<name>.ttf", R_OK) with
    // no absolute-path fallback on Android/iOS). CWD is now whatever folder
    // the user picked — not the external-files-dir path package-android-zh.sh
    // originally extracted the APK's bundled copies into. Missing fonts/
    // specifically means EVERY W3DFont load fails ("load miss" for every
    // single font in the log) and every button in the UI renders with no
    // text at all — copy all three, not just dxvk.conf. Static + takes
    // bundledRoot explicitly so GeneralsZHActivity can also call this on
    // every launch (an already-configured install needs the fix applied
    // retroactively, not just at folder-selection time).
    static void copyBundledRuntimeIfMissing(File bundledRoot, String gameFolderPath) {
        copyFileIfMissing(new File(bundledRoot, "dxvk.conf"), new File(gameFolderPath, "dxvk.conf"));
        copyFileIfMissing(new File(bundledRoot, "DefaultOptions.ini"), new File(gameFolderPath, "DefaultOptions.ini"));
        copyDirIfMissing(new File(bundledRoot, "fonts"), new File(gameFolderPath, "fonts"));
        syncEngineWindowOverrides(bundledRoot, gameFolderPath);
        removeRetiredPortStringFiles(new File(gameFolderPath, "data"));
        removeRetiredPortStringFiles(new File(bundledRoot, "data"));
    }

    // GeneralsX @bugfix Android port 25/09/2026 One build (24-25/09/2026) installed a second
    // text file per language, data/<language>/generalsx.str, and created data/<language>/
    // folders for eleven languages to hold them. A language is one file again --
    // data/<language>/generals.str (languages/README.md) -- so take back exactly what that
    // build put there: every generalsx.str, and a folder only if that left it empty. A
    // player's generals.str/.csf, and any folder with anything else in it, is never touched.
    private static void removeRetiredPortStringFiles(File dataDir) {
        File[] languages = dataDir.listFiles();
        if (languages == null) {
            return;
        }
        for (File language : languages) {
            if (!language.isDirectory()) {
                continue;
            }
            File retired = new File(language, "generalsx.str");
            if (retired.isFile() && retired.delete()) {
                String[] left = language.list();
                if (left != null && left.length == 0) {
                    language.delete();
                }
            }
        }
    }

    // GeneralsX @bugfix Android port 02/08/2026 GroupPanel.wnd (the native
    // in-engine unit-group panel) is a loose Window\ override WE inject --
    // see GameWindowManagerScript.cpp's Window\ path resolution -- not a
    // user file, so unlike dxvk.conf/DefaultOptions.ini/fonts/ above it must
    // NOT use copy-if-missing: every edit to it (this exact feature has
    // already gone through several rounds of position/behavior/style fixes)
    // would otherwise silently never reach an existing install, since the
    // very first copy would win forever. Always overwrite this one
    // subdirectory; bundledRoot's own copy is kept fresh the same way, see
    // GeneralsZHActivity.copyAssetTree's ALWAYS_OVERWRITE_PREFIX.
    private static void syncEngineWindowOverrides(File bundledRoot, String gameFolderPath) {
        File srcDir = new File(bundledRoot, "Window");
        File[] children = srcDir.listFiles();
        if (children == null) {
            return;
        }
        File destDir = new File(gameFolderPath, "Window");
        if (!destDir.isDirectory() && !destDir.mkdirs()) {
            return;
        }
        for (File child : children) {
            if (child.isFile()) {
                copyFileOverwrite(child, new File(destDir, child.getName()));
            }
        }
    }

    private static void copyFileOverwrite(File src, File dest) {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            // Not fatal: worst case the engine keeps using a stale or
            // missing override, same as before this fix existed.
        }
    }

    private static void copyFileIfMissing(File bundled, File dest) {
        if (dest.exists() || !bundled.exists()) {
            return;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(bundled);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (java.io.IOException e) {
            // Not fatal: caller falls back to its own defaults without this file.
        }
    }

    private static void copyDirIfMissing(File bundledDir, File destDir) {
        if (destDir.exists() || !bundledDir.isDirectory()) {
            return;
        }
        if (!destDir.mkdirs()) {
            return;
        }
        File[] children = bundledDir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            copyFileIfMissing(child, new File(destDir, child.getName()));
        }
    }

    private void onClearGameFolder() {
        final String game = getSelectedGame(this);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(gamePathPref(game)).apply();
        new File(getFilesDir(), "gamedata_path.txt").delete();
        new File(getFilesDir(), "game_language.cfg").delete();
        File externalMarker = externalMarkerFile(game);
        if (externalMarker != null) {
            externalMarker.delete();
        }
        refreshStatus();
        refreshDiagnosticsSwitches();
        gxToast(this, R.string.setup_toast_folder_cleared, Toast.LENGTH_SHORT).show();
    }

    private void onViewLogs() {
        startActivity(new Intent(this, LogViewerActivity.class));
    }

    private void onNetworkDiagnostics() {
        startActivity(new Intent(this, NetworkDiagnosticsActivity.class));
    }

    // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.1 round 4 UI audit: one toast at a time. A new one
    // replaces the one still showing, so two quick actions (or one action with two steps, like a
    // folder found one level down and then saved) no longer stack duplicate notifications.
    private Toast mLastToast;

    private Toast gxToast(android.content.Context c, CharSequence text, int duration) {
        return trackToast(Toast.makeText(c, text, duration));
    }

    private Toast gxToast(android.content.Context c, int resId, int duration) {
        return trackToast(Toast.makeText(c, resId, duration));
    }

    private Toast trackToast(Toast t) {
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = t;
        return t;
    }

    // GeneralsX @feature Flux Build 22/10/2026 Internal Resolution Dropdown
    private static final String PREF_INTERNAL_RES = "internal_resolution_mode";

    private void buildInternalResolutionSection(LinearLayout root) {
        LinearLayout content = UiKit.card(root);
        UiKit.sectionHeader(content, R.drawable.ic_gzh_display, getString(R.string.setup_card_internal_resolution), false);
        UiKit.supporting(content, getString(R.string.setup_internal_resolution_desc));

        String[] options = {
            getString(R.string.setup_res_native),
            "1080p",
            "720p",
            "540p",
            "480p",
            // GeneralsX @feature Flux Build 23/10/2026 Added at the end so existing saved
            // indices (0-4) keep their meaning.
            getString(R.string.setup_res_1440p),
            // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 A size the player types (index 6).
            getString(R.string.setup_res_custom)
        };
        Spinner spinner = new Spinner(this);
        // A Spinner's closed view and popup rows take their text colour from the platform
        // layout, not from UiKit, so a custom text role or a dark custom base would leave
        // them unreadable. Theme both views and the popup surface here.
        final int spinnerText = UiKit.color(this, R.color.gzh_on_surface);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                row.setTextColor(spinnerText);
                return row;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getDropDownView(position, convertView, parent);
                row.setTextColor(spinnerText);
                return row;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
            UiKit.color(this, R.color.gzh_surface_container_high)));
        spinner.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            UiKit.color(this, R.color.gzh_primary)));
        adoptInGameResolution();
        int currentChoice = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_INTERNAL_RES, 0);
        spinner.setSelection(Math.max(0, Math.min(options.length - 1, currentChoice)));
        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 review: picking "Custom…" again fires no
        // selection, so the custom size gets its own button to change it while it is selected.
        final com.google.android.material.button.MaterialButton customEdit =
            new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle);
        customEdit.setText(R.string.setup_res_custom);
        customEdit.setOnClickListener(v -> askCustomResolution(spinner));
        customEdit.setVisibility(spinner.getSelectedItemPosition() == RES_CUSTOM ? View.VISIBLE : View.GONE);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean initial = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (initial) {
                    initial = false;
                    return;
                }
                if (customEdit != null) customEdit.setVisibility(position == RES_CUSTOM ? View.VISIBLE : View.GONE);
                if (mResRevert) {
                    // The custom dialog was cancelled: back to the previous choice, already saved.
                    mResRevert = false;
                    return;
                }
                if (position == RES_CUSTOM) {
                    askCustomResolution(spinner);
                    return;
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_INTERNAL_RES, position).apply();
                int[] res = applyInternalResolutionSetting();
                if (mResInUse != null) refreshResolutionInUse(mResInUse);
                gxToast(SetupActivity.this,
                    getString(R.string.setup_toast_resolution_saved, res[0], res[1]),
                    Toast.LENGTH_SHORT).show();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        content.addView(spinner);
        content.addView(customEdit);
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 Resolution sync: the size the next game
        // gets, and the one the engine reported using in the last game (DX8Wrapper, [GX-RES] in use).
        mResInUse = UiKit.caption(content, "");
        refreshResolutionInUse(mResInUse);
        UiKit.helpText(content, getString(R.string.setup_internal_resolution_help));

        // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 Display mode is back (GXDisplayMode, DX8Wrapper):
        // shelved in v1.4.1 round 4 because every test of it then ran at native resolution (the window
        // handler reset the resolution, fixed in round 5), so the bars were never really tested.
        // Fullscreen (stretch) when the key is absent, as the engine; Windowed keeps the picture's
        // shape with black bars, where the touch buttons can go (in-game editor: "Bars").
        UiKit.caption(content, getString(R.string.setup_display_mode_title));
        CharSequence[] modes = { getString(R.string.setup_display_mode_keep), getString(R.string.setup_display_mode_stretch) };
        UiKit.segmented(content, modes, "keep".equals(readOptionsKey("GXDisplayMode")) ? 0 : 1, index -> {
            writeOptionsKey("GXDisplayMode", index == 0 ? "keep" : "stretch");
            // The mode decides a preset's shape (16:9 in Windowed), so the resolution follows it -- after
            // keeping a size the player chose in the game since this card was built (review).
            adoptInGameResolution();
            applyInternalResolutionSetting();
            if (mResInUse != null) refreshResolutionInUse(mResInUse);
        });
        UiKit.helpText(content, getString(R.string.setup_display_mode_help));

        // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 Render rate (GXRenderFps in Options.ini; the engine's
        // GXRenderLimit.h): how many frames a second the game draws, in every game mode. The logic keeps its
        // own clock (the network online and on LAN, the Logic Time Scale offline), so the game speed, the
        // lockstep and the CRC do not change. 60 by default; never below 60 or the logic rate (engine).
        UiKit.caption(content, getString(R.string.setup_render_rate_title));
        buildRenderRateChips(content);
        mRenderRateNow = UiKit.caption(content, "");
        refreshRenderRateNow();
        UiKit.helpText(content, getString(R.string.setup_render_rate_help));

        // GeneralsX @tweak Flux Build 08/11/2026 v1.4.2 round 4 MSAA removed from this release (owner): its
        // chips and the Adaptive MSAA switch are gone, and a level saved by an earlier build is put back to
        // Off (the engine ignores it too: DX8Wrapper, d3d8gles, the in-game Options menu).
        final String aa = readOptionsKey("AntiAliasing");
        int aaLevel = 0;
        try {
            aaLevel = aa != null ? Integer.parseInt(aa.trim()) : 0;
        } catch (NumberFormatException e) {
            aaLevel = 2; // not a number: written back as Off
        }
        if (aaLevel > 1) writeOptionsKey("AntiAliasing", "0"); // review: 0 and 1 already mean Off
        if (readOptionsKey("GXAdaptiveMsaa") != null) removeOptionsKey("GXAdaptiveMsaa");
    }

    // ---- Render rate (GeneralsX @feature Flux Build 08/11/2026 v1.4.2) ----
    static final String PREF_RENDER_CHOICE = "render_fps_choice"; // "60".."144", "max", "uncapped", "custom"
    static final String PREF_RENDER_CUSTOM = "render_fps_custom";
    static final String PREF_RENDER_FPS = "render_fps";           // the number written to Options.ini
    static final int RENDER_FPS_UNCAPPED = 1000000;              // RenderFpsPreset::UncappedFpsValue
    private static final int[] RENDER_PRESETS = { 60, 75, 90, 100, 120, 144 };
    private TextView mRenderRateNow;
    private final java.util.List<com.google.android.material.button.MaterialButtonToggleGroup> mRenderGroups =
        new java.util.ArrayList<>();
    private final java.util.List<String> mRenderTokens = new java.util.ArrayList<>();
    private boolean mRenderSyncing;

    /** The highest refresh rate the screen offers at its current resolution, at least 60. */
    static int screenMaxRefresh(android.app.Activity a) {
        android.view.Display d = a.getWindowManager().getDefaultDisplay();
        float max = d.getRefreshRate();
        android.view.Display.Mode cur = d.getMode();
        for (android.view.Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight())
                max = Math.max(max, m.getRefreshRate());
        }
        return Math.max(60, Math.round(max));
    }

    /** The refresh rates the screen offers at its current resolution. */
    static java.util.List<Integer> screenRefreshRates(android.app.Activity a) {
        android.view.Display d = a.getWindowManager().getDefaultDisplay();
        android.view.Display.Mode cur = d.getMode();
        java.util.List<Integer> rates = new java.util.ArrayList<>();
        for (android.view.Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight())
                rates.add(Math.round(m.getRefreshRate()));
        }
        return rates;
    }

    /** The frames per second a choice stands for. */
    private int renderFpsFor(String token) {
        if ("uncapped".equals(token)) return RENDER_FPS_UNCAPPED;
        if ("max".equals(token)) return screenMaxRefresh(this);
        if ("custom".equals(token))
            return Math.max(60, getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_RENDER_CUSTOM, 60));
        try {
            return Math.max(60, Integer.parseInt(token));
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private String renderChoice() {
        String c = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RENDER_CHOICE, "60");
        return mRenderTokens.contains(c) ? c : "60";
    }

    private void saveRenderChoice(String token) {
        int fps = renderFpsFor(token);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_RENDER_CHOICE, token).putInt(PREF_RENDER_FPS, fps).apply();
        writeOptionsKey("GXRenderFps", String.valueOf(fps));
        refreshRenderRateNow();
    }

    /** Chips in rows of four (one segmented group per row, kept to one selection across the rows). */
    private void buildRenderRateChips(LinearLayout content) {
        final int screenMax = screenMaxRefresh(this);
        java.util.List<CharSequence> labels = new java.util.ArrayList<>();
        mRenderTokens.clear();
        mRenderGroups.clear();
        // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 review: only rates the screen really has
        // (a 60/120 Hz panel shows 75 or 90 unevenly); 60 always.
        final java.util.List<Integer> rates = screenRefreshRates(this);
        for (int fps : RENDER_PRESETS) {
            boolean offered = fps == 60;
            for (int r : rates) if (Math.abs(r - fps) <= 1) offered = true;
            if (!offered || fps > screenMax) continue;
            mRenderTokens.add(String.valueOf(fps));
            labels.add(String.valueOf(fps));
        }
        mRenderTokens.add("max");
        labels.add(getString(R.string.setup_render_rate_screen_max));
        mRenderTokens.add("uncapped");
        labels.add(getString(R.string.setup_render_rate_uncapped));
        mRenderTokens.add("custom");
        labels.add(getString(R.string.setup_render_rate_custom));
        // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.2 round 3 review: a saved rate the screen no longer
        // offers becomes Screen max, and the saved number and Options.ini (which the engine reads) are
        // brought in line with what the chips show -- a new game folder or a changed screen mode
        // otherwise left the engine at another rate than the one shown.
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RENDER_CHOICE, "60");
        if (!mRenderTokens.contains(saved)) saved = "max";
        String ini = readOptionsKey("GXRenderFps");
        int iniFps = -1;
        try {
            iniFps = ini != null ? Integer.parseInt(ini) : 60;
        } catch (NumberFormatException e) {
            iniFps = -1;
        }
        if (!saved.equals(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_RENDER_CHOICE, "60"))
            || iniFps != renderFpsFor(saved)
            || getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_RENDER_FPS, 60) != renderFpsFor(saved)) {
            saveRenderChoice(saved);
        }
        final int current = mRenderTokens.indexOf(renderChoice());
        for (int start = 0; start < labels.size(); start += 4) {
            final int first = start;
            final int end = Math.min(labels.size(), start + 4);
            CharSequence[] row = labels.subList(start, end).toArray(new CharSequence[0]);
            final int checked = current >= start && current < end ? current - start : -1;
            com.google.android.material.button.MaterialButtonToggleGroup group =
                UiKit.segmented(content, row, checked, index -> onRenderChip(first + index));
            group.setSelectionRequired(false);
            mRenderGroups.add(group);
        }
        // A tap on the chip already chosen would leave no chip lit: light it again.
        for (com.google.android.material.button.MaterialButtonToggleGroup g : mRenderGroups) {
            g.addOnButtonCheckedListener((group, id, isChecked) -> {
                if (!isChecked && !mRenderSyncing && noRenderChipChecked()) {
                    mRenderSyncing = true;
                    group.check(id);
                    mRenderSyncing = false;
                    // A second tap on Custom asks for a new number (review).
                    if ("custom".equals(renderChoice()))
                        askCustomRenderRate(() -> { });
                }
            });
        }
    }

    private boolean noRenderChipChecked() {
        for (com.google.android.material.button.MaterialButtonToggleGroup g : mRenderGroups)
            if (g.getCheckedButtonId() != View.NO_ID) return false;
        return true;
    }

    /** Lights the chip of index (across all rows) and no other. */
    private void showRenderChip(int index) {
        mRenderSyncing = true;
        int start = 0;
        for (com.google.android.material.button.MaterialButtonToggleGroup g : mRenderGroups) {
            int n = g.getChildCount();
            if (index >= start && index < start + n) {
                int id = g.getChildAt(index - start).getId();
                if (g.getCheckedButtonId() != id) g.check(id);
            } else if (g.getCheckedButtonId() != View.NO_ID) {
                g.clearChecked();
            }
            start += n;
        }
        mRenderSyncing = false;
    }

    private void onRenderChip(int index) {
        if (mRenderSyncing) return;
        final String token = mRenderTokens.get(index);
        final int previous = mRenderTokens.indexOf(renderChoice());
        showRenderChip(index);
        if ("custom".equals(token)) {
            askCustomRenderRate(() -> showRenderChip(previous));
            return;
        }
        saveRenderChoice(token);
        if (renderFpsFor(token) > 60)
            gxToast(this, R.string.setup_render_rate_heat_toast, Toast.LENGTH_LONG).show();
    }

    private void askCustomRenderRate(final Runnable onCancel) {
        final int saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_RENDER_CUSTOM, 0);
        final EditText in = numberField(R.string.setup_render_rate_custom_hint, saved);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(labelled(R.string.setup_render_rate_custom_hint, in));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_render_rate_custom_title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                int fps;
                try {
                    fps = Integer.parseInt(in.getText().toString().trim());
                } catch (NumberFormatException e) {
                    fps = -1;
                }
                if (fps < 60 || fps > 1000) {
                    gxToast(this, R.string.setup_render_rate_custom_rule, Toast.LENGTH_LONG).show();
                    onCancel.run();
                    return;
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_RENDER_CUSTOM, fps).apply();
                saveRenderChoice("custom");
                if (fps > 60)
                    gxToast(this, R.string.setup_render_rate_heat_toast, Toast.LENGTH_LONG).show();
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> onCancel.run())
            .setCancelable(false)
            .show();
    }

    private void refreshRenderRateNow() {
        if (mRenderRateNow == null) return;
        final int fps = renderFpsFor(renderChoice());
        final int screen = screenMaxRefresh(this);
        mRenderRateNow.setText(fps >= RENDER_FPS_UNCAPPED
            ? getString(R.string.setup_render_rate_now_uncapped, screen)
            : getString(R.string.setup_render_rate_now, fps, screen));
    }

    private static final int RES_CUSTOM = 6;
    /** "Next game / Last game" under the resolution choice (refreshResolutionInUse). */
    private TextView mResInUse;
    /** The next selection event is the spinner going back after a cancelled custom dialog. */
    private boolean mResRevert;

    private void revertResolutionSpinner(Spinner spinner, int previous) {
        int target = Math.min(previous, RES_CUSTOM);
        if (spinner.getSelectedItemPosition() != target) {
            mResRevert = true; // only when a selection event will follow
            spinner.setSelection(target);
        }
    }
    static final String PREF_CUSTOM_RES = "gx_custom_resolution";

    /** Asks for WxH, checked against the same rule as the engine's (SDL3Main.cpp [GX-RES]). */
    private void askCustomResolution(final Spinner spinner) {
        final int[] full = screenSize();
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 Two number fields, Width and Height
        // (owner request), in place of one "1280x720" text.
        final int[] saved = parseResolution(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CUSTOM_RES, ""));
        final EditText inW = numberField(R.string.setup_res_width, saved != null ? saved[0] : 0);
        final EditText inH = numberField(R.string.setup_res_height, saved != null ? saved[1] : 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(labelled(R.string.setup_res_width, inW), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView times = new TextView(this);
        times.setText("×");
        times.setTextSize(18);
        times.setPadding(dp(10), 0, dp(10), 0);
        times.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        row.addView(times);
        row.addView(labelled(R.string.setup_res_height, inH), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(row);
        final TextView verdict = new TextView(this);
        verdict.setTextSize(12);
        verdict.setPadding(0, dp(6), 0, 0);
        verdict.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        box.addView(verdict);
        // The two fields as "WxH", so the one parser and rule serve both.
        final java.util.function.Supplier<String> typed = () ->
            inW.getText().toString().trim() + "x" + inH.getText().toString().trim();
        final Runnable check = () -> {
            int[] wh = parseResolution(typed.get());
            if (wh == null) {
                verdict.setText(getString(R.string.setup_res_custom_rule, full[0], full[1]));
                return;
            }
            int[] ok = sanitizeResolution(wh[0], wh[1], full[0], full[1]);
            verdict.setText(ok[0] == wh[0] && ok[1] == wh[1]
                ? getString(R.string.setup_res_custom_ok, ok[0], ok[1])
                : getString(R.string.setup_res_custom_adjusted, ok[0], ok[1]));
        };
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { check.run(); }
        };
        inW.addTextChangedListener(watcher);
        inH.addTextChangedListener(watcher);
        check.run();
        final int previous = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_INTERNAL_RES, 0);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.setup_res_custom)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                int[] wh = parseResolution(typed.get());
                if (wh == null) {
                    gxToast(this, getString(R.string.setup_res_custom_rule, full[0], full[1]), Toast.LENGTH_LONG).show();
                    revertResolutionSpinner(spinner, previous);
                    return;
                }
                int[] ok = sanitizeResolution(wh[0], wh[1], full[0], full[1]);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putInt(PREF_INTERNAL_RES, RES_CUSTOM).putString(PREF_CUSTOM_RES, ok[0] + "x" + ok[1]).apply();
                int[] res = applyInternalResolutionSetting();
                if (mResInUse != null) refreshResolutionInUse(mResInUse);
                gxToast(this, getString(R.string.setup_toast_resolution_saved, res[0], res[1]), Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton(android.R.string.cancel, (d, w) -> revertResolutionSpinner(spinner, previous))
            .setCancelable(false)
            .show();
    }

    /** A small label over a field. */
    private LinearLayout labelled(int label, View field) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(12);
        t.setTextColor(UiKit.color(this, R.color.gzh_on_surface_variant));
        col.addView(t);
        col.addView(field);
        return col;
    }

    /** A digits-only field for one side of the resolution, with its label as hint. */
    private EditText numberField(int label, int value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(5) });
        e.setHint(label);
        e.setContentDescription(getString(label));
        if (value > 0) e.setText(String.valueOf(value));
        return e;
    }

    /** "1280x720", "1280 x 720", "1280*720" -> {1280, 720}; null if not two numbers. */
    static int[] parseResolution(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s*(\\d{3,5})\\s*[xX*\u00d7 ]\\s*(\\d{3,5})\\s*").matcher(text);
        if (!m.matches()) return null;
        try {
            return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The engine's rule (SDL3Main.cpp, [GX-RES]): landscape, an even width, at least 800x600 (scaled up
     * keeping the shape), at most the screen. Both sides apply it, so what is saved is what is drawn.
     */
    static int[] sanitizeResolution(int w, int h, int screenW, int screenH) {
        int sw = Math.max(screenW, screenH), sh = Math.min(screenW, screenH);
        if (w <= h) { w = sw; h = sh; }
        if (w > sw) w = sw;
        if (h > sh) h = sh;
        if (sh >= 600 && h < 600 && h > 0) {
            w = (int) ((long) w * 600 / h);
            h = 600;
        }
        if (w > sw) w = sw;
        if (sw >= 800 && w < 800) w = 800;
        // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.1 round 4 At least 4:3, the narrowest shape EA's
        // interface is laid out for (SDL3Main.cpp, the same rule).
        if ((long) w * 3 < (long) h * 4) {
            w = (int) ((long) h * 4 / 3);
            if (w > sw) { w = sw; h = (int) ((long) w * 3 / 4); }
        }
        return new int[] { w & ~1, h };
    }

    /** w x h narrowed to 16:9 when it is wider (the height stays); an even width. */
    static int[] widescreenInside(int w, int h) {
        if ((long) w * 9 > (long) h * 16 * 101 / 100) {
            w = (int) ((long) h * 16 / 9) & ~1;
        }
        return new int[] { w, h };
    }

    private static final int[] UPSCALE_PERCENTS = { 100, 77, 67, 59, 50 }; // DX8Wrapper UpscaleModePercent

    /** The scaling method for a game of w x h on this screen (d3d8gles stretchVirtualBackbuffer's rule). */
    private String scalingLabel(int w, int h, int[] screen, boolean gsrAllowed) {
        final int mode = readUpscaleMode();
        final int percent = UPSCALE_PERCENTS[Math.max(0, Math.min(UPSCALE_PERCENTS.length - 1, mode))];
        final int rw = (w * percent / 100) & ~1, rh = (h * percent / 100) & ~1;
        int ow = screen[0], oh = screen[1];
        if ("keep".equals(readOptionsKey("GXDisplayMode")) && h > 0 && oh > 0) {
            // d3d8gles_FitRect: the game's own shape, centred; within 1% counts as the screen's.
            final double game = (double) w / h, win = (double) ow / oh;
            if (Math.abs(game - win) > win * 0.01) {
                if (game > win) oh = (int) Math.round(ow / game);
                else ow = (int) Math.round(oh * game);
            }
        }
        if (rw <= 0 || rh <= 0) return "";
        final double scale = Math.min((double) ow / rw, (double) oh / rh);
        final String x = String.format(Locale.US, "%.2f", scale);
        if (rw == ow && rh == oh)
            return getString(R.string.setup_res_scaling_exact);
        if (scale < 1.0) return getString(R.string.setup_res_scaling_bilinear, x);
        final double maxScale = Math.max((double) ow / rw, (double) oh / rh); // engine: neither axis over 2x
        if (maxScale <= 2.0) return gsrAllowed ? getString(R.string.setup_res_scaling_gsr, x)
            : getString(R.string.setup_res_scaling_bilinear, x);
        return getString(R.string.setup_res_scaling_over2, x);
    }

    /** "Next game: W x H" from Options.ini, and what the engine reported for the last game. */
    private void refreshResolutionInUse(final TextView view) {
        final File report = new File(getFilesDir(), "gx_resolution_used.txt");
        // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 round 4 Adaptive GSR: how the next game's picture
        // will be scaled to the screen (the engine's rule, d3d8gles stretchVirtualBackbuffer). The GPU
        // name is read here, on the UI thread (it is cached after the first EGL query).
        final boolean gsrAllowed = isAdrenoGpu() && !UPSCALER_BILINEAR.equals(readUpscalerFilter());
        // Review: on Vulkan (DXVK) the picture is scaled by DXVK's own pillarbox -- no upscale mode, no GSR
        // -- so the method is not shown there.
        final boolean glesScaling = !RENDER_BACKEND_VULKAN.equals(getRenderBackendChoice());
        final int[] screen = screenSize();
        new Thread(() -> {
            String chosen = readKeyValueFile(optionsIniFile()).get("Resolution");
            String used = null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(report))) {
                used = r.readLine();
            } catch (java.io.IOException e) {
                // No game started with this build yet.
            }
            final StringBuilder text = new StringBuilder();
            int[] c = chosen != null ? parseResolution(chosen.trim()) : null;
            if (c != null) {
                text.append(getString(R.string.setup_res_next_game, c[0], c[1]));
                if (glesScaling)
                    text.append(" \u00b7 ").append(scalingLabel(c[0], c[1], screen, gsrAllowed));
            }
            if (used != null) {
                String[] p = used.trim().split("\\s+");
                if (p.length >= 2) {
                    try {
                        int uw = Integer.parseInt(p[0]), uh = Integer.parseInt(p[1]);
                        if (text.length() > 0) text.append("\n");
                        text.append(getString(R.string.setup_res_last_game, uw, uh));
                    } catch (NumberFormatException e) {
                        // A damaged report is not shown.
                    }
                }
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                view.setText(text);
                view.setVisibility(text.length() > 0 ? View.VISIBLE : View.GONE);
            });
        }, "gx-res-in-use").start();
    }

    /** One key of Options.ini (the engine's settings file), or null. */
    private String readOptionsKey(String key) {
        String v = readKeyValueFile(optionsIniFile()).get(key);
        return v != null ? v.trim() : null;
    }

    /** Removes one key of Options.ini, when the file exists (GeneralsX v1.4.2 round 4). */
    private void removeOptionsKey(String key) {
        File file = optionsIniFile();
        if (!file.isFile()) return;
        java.util.LinkedHashMap<String, String> prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        if (prefs.remove(key) != null) writeKeyValueFile(file, prefs);
    }

    /** Writes one key of Options.ini, seeded from DefaultOptions.ini when new (as writeSelectionMode). */
    private void writeOptionsKey(String key, String value) {
        File file = optionsIniFile();
        java.util.LinkedHashMap<String, String> prefs = new java.util.LinkedHashMap<>(
            readKeyValueFile(file.isFile() ? file : defaultOptionsIniFile()));
        prefs.put(key, value);
        writeKeyValueFile(file, prefs);
    }

    private int[] applyInternalResolutionSetting() {
        int choice = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_INTERNAL_RES, 0);
        int[] full = screenSize();
        float aspect = (float) full[0] / (float) full[1];
        int targetW = full[0];
        int targetH = full[1];
        switch (choice) {
            case 1: // 1080p
                targetH = Math.min(1080, full[1]);
                targetW = (Math.round(targetH * aspect)) & ~1;
                break;
            case 2: // 720p
                targetH = Math.min(720, full[1]);
                targetW = (Math.round(targetH * aspect)) & ~1;
                break;
            case 3: // 540p
                targetH = Math.min(540, full[1]);
                targetW = (Math.round(targetH * aspect)) & ~1;
                break;
            case 4: // 480p
                targetH = Math.min(480, full[1]);
                targetW = (Math.round(targetH * aspect)) & ~1;
                break;
            case 5: // 1440p
                targetH = Math.min(1440, full[1]);
                targetW = (Math.round(targetH * aspect)) & ~1;
                break;
            case RES_CUSTOM: {
                int[] wh = parseResolution(getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CUSTOM_RES, ""));
                if (wh != null) { targetW = wh[0]; targetH = wh[1]; }
                break;
            }
            default: // 0: Native 100%
                targetW = full[0];
                targetH = full[1];
                break;
        }

        File file = optionsIniFile();
        java.util.LinkedHashMap<String, String> prefs;
        if (!file.isFile()) {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(defaultOptionsIniFile()));
        } else {
            prefs = new java.util.LinkedHashMap<>(readKeyValueFile(file));
        }
        // GeneralsX @feature Flux Build 08/11/2026 v1.4.2 Windowed (GXDisplayMode keep) on a preset: a 16:9
        // picture, the PC's widescreen shape, with black bars at the sides of a wider screen -- a preset
        // has the screen's own shape, which would leave no room for bars. A custom size is used exactly.
        if (choice != RES_CUSTOM && "keep".equals(prefs.get("GXDisplayMode") != null ? prefs.get("GXDisplayMode").trim() : null)) {
            int[] wide = widescreenInside(targetW, targetH);
            targetW = wide[0];
            targetH = wide[1];
        }
        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 Every choice goes through the engine's rule, so
        // the 480p/540p presets (under the menus' 600 lines) no longer reach the GPU as is.
        int[] ok = sanitizeResolution(targetW, targetH, full[0], full[1]);
        targetW = ok[0];
        targetH = ok[1];
        prefs.put("Resolution", targetW + " " + targetH);
        if (writeKeyValueFile(file, prefs)) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_RES_WRITTEN, targetW + "x" + targetH).apply();
        }
        return new int[] { targetW, targetH };
    }

    // GeneralsX @bugfix Flux Build 08/11/2026 v1.4.1 round 4 Resolution sync, the other way: a size the
    // player picked in the game's own Options menu (it writes Options.ini) becomes the launcher's
    // custom size, so the next launch keeps it instead of writing the launcher's older choice over it
    // (owner report: the two disagreed). The launcher's own last write tells the two apart.
    private static final String PREF_RES_WRITTEN = "gx_res_written";

    private void adoptInGameResolution() {
        android.content.SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String written = sp.getString(PREF_RES_WRITTEN, null);
        String inFile = readKeyValueFile(optionsIniFile()).get("Resolution");
        int[] w = written != null ? parseResolution(written) : null;
        int[] f = inFile != null ? parseResolution(inFile.trim()) : null;
        if (w == null || f == null || (w[0] == f[0] && w[1] == f[1])) return;
        int[] full = screenSize();
        int[] ok = sanitizeResolution(f[0], f[1], full[0], full[1]);
        sp.edit().putInt(PREF_INTERNAL_RES, RES_CUSTOM).putString(PREF_CUSTOM_RES, ok[0] + "x" + ok[1])
            .putString(PREF_RES_WRITTEN, f[0] + "x" + f[1]).apply();
        android.util.Log.i("GeneralsZH", "resolution: kept the in-game choice " + ok[0] + "x" + ok[1]);
    }

    // GeneralsX @feature Flux Build 22/10/2026 Global Performance Policy Disclaimer Popup
    private static final String PREF_DISCLAIMER_DONT_SHOW = "disclaimer_dont_show_again";

    private void maybeShowPerformanceDisclaimerDialog() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_DISCLAIMER_DONT_SHOW, false)) {
            // GeneralsX @feature Flux Build 06/11/2026 What's new: right away when there is no
            // disclaimer, after it when there is -- never two dialogs on top of each other.
            WhatsNew.maybeShow(this);
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(UiKit.color(this, R.color.gzh_background));
        int pad = dp(20);
        layout.setPadding(pad, pad, pad, dp(8));

        TextView msg = new TextView(this);
        msg.setText(R.string.disclaimer_dialog_message);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        msg.setTextColor(UiKit.color(this, R.color.gzh_on_surface));
        msg.setLineSpacing(0, 1.25f);
        layout.addView(msg);

        CheckBox dontShow = new CheckBox(this);
        dontShow.setText(R.string.disclaimer_dialog_dont_show);
        dontShow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dontShow.setTextColor(UiKit.color(this, R.color.gzh_primary));
        dontShow.setButtonTintList(UiKit.tint(this, R.color.gzh_primary));
        LinearLayout.LayoutParams cbParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbParams.topMargin = dp(16);
        layout.addView(dontShow, cbParams);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.disclaimer_dialog_title)
            .setIcon(R.drawable.ic_gzh_shield)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                if (dontShow.isChecked()) {
                    prefs.edit().putBoolean(PREF_DISCLAIMER_DONT_SHOW, true).apply();
                }
                WhatsNew.maybeShow(this);
            })
            .setCancelable(false)
            .show();
    }

    private void onLaunchGame() {
        // GeneralsX @feature Android port 18/10/2026 Safe start with mods (PLAN-027): two starts in
        // a row with the same mods never reached the main menu -- offer to start without them, or
        // without the mod switched on last.
        final String modList = ModManager.activeList(this);
        if (!modList.isEmpty() && ModManager.failedStarts(this, modList) >= 2) {
            final String last = ModManager.lastEnabled(this);
            final boolean lastOn = last != null && ("\n" + modList + "\n").contains("/" + last + "\n");
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mods_safe_title)
                .setMessage(R.string.mods_safe_message)
                .setPositiveButton(R.string.mods_safe_without, (d, w) -> {
                    ModManager.switchOff(this, null);
                    showTab(currentTab);
                    launchGameNow();
                })
                .setNegativeButton(R.string.mods_safe_anyway, (d, w) -> launchGameNow());
            if (lastOn) {
                b.setNeutralButton(getString(R.string.mods_safe_without_last, last), (d, w) -> {
                    ModManager.switchOff(this, last);
                    showTab(currentTab);
                    launchGameNow();
                });
            }
            b.show();
            return;
        }
        launchGameNow();
    }

    private void launchGameNow() {
        if (!isAdrenoGpu()) {
            writeUpscaleMode(0);
        }
        // GeneralsX @feature Android port 04/10/2026 The text-language marker is checked against
        // the data of the game about to start, which may not be the one it was last written for.
        applyGameLanguageOverride();
        adoptInGameResolution();
        applyInternalResolutionSetting();

        // GeneralsX @bugfix Flux Build 07/11/2026 v1.4.0-community is Android-only. The launcher used to
        // write the PC cross-play marker on every start ("so all players can join any lobby"); a stale
        // one is removed instead, so no engine -- an older one updated over the air included -- claims
        // the PC client's checksum. The engine ignores the marker anyway unless the signed settings
        // turn cross-play on (pc_crossplay), which they do not.
        String gamePath = getSavedGamePath(this, GAME_ZERO_HOUR);
        if (gamePath != null && !UpdateManager.featureEnabled(this, "pc_crossplay", false)) {
            File pcCompat = new File(gamePath, "gx_pc_compat.txt");
            if (pcCompat.isFile() && !pcCompat.delete()) {
                android.util.Log.w("SetupActivity", "could not remove " + pcCompat);
            }
        }

        ReplayCheckActivity.clearCheckMarkers(this);
        // GeneralsX @bugfix Flux Build v1.2.7 28/10/2026 Start immediately.
        // GeneralsZHActivity is sensorLandscape, so the OS applies landscape
        // before its window is measured; there is no portrait window to race
        // the native size probe. The sensor still chooses normal or reverse
        // landscape for the 180-degree flip.
        startActivity(new Intent(this, GeneralsZHActivity.class));
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
