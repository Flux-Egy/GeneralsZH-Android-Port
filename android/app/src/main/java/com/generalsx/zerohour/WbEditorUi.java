// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: World Builder's screen furniture, laid out like EA's
// World Builder on the PC: a top bar (exit, map name, undo/redo, save, menu), a tool bar of editing
// modes down the left (EA's toolbar), and the current tool's options panel at the bottom (EA's floating
// options panels). Every value goes to EA's tool code through WorldBuilderActivity.nativeCommand;
// nothing here edits a map itself.
package com.generalsx.zerohour;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class WbEditorUi {
    /** The modes of the left tool bar: id (the native tool name), icon, label, short label. */
    private static final Object[][] TOOLS = {
        { "camera", R.drawable.ic_wb_hand, R.string.wb_tool_camera, R.string.wb_tool_camera_short },
        { "select", R.drawable.ic_wb_select, R.string.wb_tool_select, R.string.wb_tool_select_short },
        { "raise", R.drawable.ic_wb_raise, R.string.wb_tool_raise, R.string.wb_tool_raise_short },
        { "lower", R.drawable.ic_wb_lower, R.string.wb_tool_lower, R.string.wb_tool_lower_short },
        { "smooth", R.drawable.ic_wb_smooth, R.string.wb_tool_smooth, R.string.wb_tool_smooth_short },
        { "height", R.drawable.ic_wb_height, R.string.wb_tool_height, R.string.wb_tool_height_short },
        { "object", R.drawable.ic_wb_object, R.string.wb_tool_object, R.string.wb_tool_object_short },
        { "waypoint", R.drawable.ic_wb_waypoint, R.string.wb_tool_waypoint, R.string.wb_tool_waypoint_short },
        { "area", R.drawable.ic_wb_area, R.string.wb_tool_area, R.string.wb_tool_area_short },
    };
    /** EA's editor sortings shown as categories, in EA's tree order. */
    private static final String[][] CATEGORIES = {
        { "structure", "wb_cat_structure" }, { "infantry", "wb_cat_infantry" }, { "vehicle", "wb_cat_vehicle" },
        { "shrubbery", "wb_cat_shrubbery" }, { "misc_man_made", "wb_cat_man_made" },
        { "misc_natural", "wb_cat_natural" }, { "debris", "wb_cat_debris" }, { "fx", "wb_cat_fx" },
        { "road", "wb_cat_road" }, { "bridge", "wb_cat_bridge" },
    };

    interface Host {
        void onExit();
        void onSave();
        void onSaveAs();
        void onMenu(View anchor);
        void onScripts();
        void onHelp();
    }

    private final WorldBuilderActivity mActivity;
    private final Host mHost;
    private final List<View> mEnabledWhenReady = new ArrayList<>();
    private final Map<String, View> mToolButtons = new LinkedHashMap<>();
    private TextView mTitle;
    private View mUndo;
    private View mRedo;
    private LinearLayout mPanel;
    private LinearLayout mPanelBody;
    private TextView mPanelTitle;
    private String mTool = "camera";
    private boolean mPanelOpen = true;
    // Object panel state.
    private final List<String[]> mObjects = new ArrayList<>();
    private final List<String> mTeams = new ArrayList<>();
    private String mObjectCategory = "structure";
    private String mObjectFilter = "";
    private String mCurrentObject;
    private String mSelectedWaypoint;
    private String mSelectedArea;
    private String mSelectedObject;

    WbEditorUi(WorldBuilderActivity activity, Host host) {
        mActivity = activity;
        mHost = host;
    }

    // ------------------------------------------------------------------ building

    void build() {
        buildTopBar();
        buildToolBar();
        buildPanel();
        setReady(false);
    }

    private GradientDrawable surface(float radiusDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(mActivity, radiusDp));
        bg.setColor(UiKit.withAlpha(UiKit.color(mActivity, R.color.gzh_surface_container), 0xEC));
        bg.setStroke(UiKit.dp(mActivity, 1), UiKit.withAlpha(UiKit.color(mActivity, R.color.gzh_outline_variant), 0x80));
        return bg;
    }

    private View iconButton(int icon, int label, Runnable action) {
        ImageView b = new ImageView(mActivity);
        b.setImageResource(icon);
        b.setImageTintList(UiKit.tint(mActivity, R.color.gzh_on_surface));
        int pad = UiKit.dp(mActivity, 10);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(mActivity.getString(label));
        b.setTooltipText(mActivity.getString(label));
        b.setBackground(new android.graphics.drawable.RippleDrawable(
            UiKit.tint(mActivity, R.color.gzh_ripple_primary), null, null));
        b.setClickable(true);
        b.setOnClickListener(v -> action.run());
        int size = UiKit.dp(mActivity, 48);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return b;
    }

    /** A labelled pill button (the Scripts entry): the editor's most-asked-for feature is named, not iconic. */
    private View labelledButton(int icon, int label, Runnable action) {
        MaterialButton b = new MaterialButton(mActivity, null, com.google.android.material.R.attr.materialButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setIconResource(icon);
        b.setIconSize(UiKit.dp(mActivity, 20));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(mActivity, 48));
        lp.setMarginStart(UiKit.dp(mActivity, 4));
        lp.setMarginEnd(UiKit.dp(mActivity, 4));
        b.setLayoutParams(lp);
        return b;
    }

    /** A tool bar entry: the icon with its short name under it (EA's tool bar has tooltips; touch has none). */
    private View toolButton(int icon, int shortLabel, int fullLabel, Runnable action) {
        LinearLayout col = new LinearLayout(mActivity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = UiKit.dp(mActivity, 6);
        col.setPadding(pad, pad, pad, UiKit.dp(mActivity, 4));
        col.setContentDescription(mActivity.getString(fullLabel));
        col.setTooltipText(mActivity.getString(fullLabel));
        col.setClickable(true);
        col.setOnClickListener(v -> action.run());
        col.setBackground(new android.graphics.drawable.RippleDrawable(UiKit.tint(mActivity, R.color.gzh_ripple_primary), null, null));
        ImageView img = new ImageView(mActivity);
        img.setImageResource(icon);
        img.setImageTintList(UiKit.tint(mActivity, R.color.gzh_on_surface));
        int sz = UiKit.dp(mActivity, 30);
        col.addView(img, new LinearLayout.LayoutParams(sz, sz));
        TextView t = new TextView(mActivity);
        t.setText(shortLabel);
        t.setTextSize(10);
        t.setTextColor(UiKit.color(mActivity, R.color.gzh_on_surface_variant));
        t.setGravity(Gravity.CENTER);
        t.setMaxWidth(UiKit.dp(mActivity, 68));
        col.addView(t);
        col.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(mActivity, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
        return col;
    }

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = UiKit.dp(mActivity, 4);
        bar.setPadding(pad, pad, UiKit.dp(mActivity, 8), pad);
        bar.setBackground(surface(26));

        bar.addView(iconButton(R.drawable.ic_wb_close, R.string.wb_exit, mHost::onExit));
        mTitle = new TextView(mActivity);
        mTitle.setTextColor(UiKit.color(mActivity, R.color.gzh_on_surface));
        mTitle.setTextSize(14);
        mTitle.setSingleLine(true);
        mTitle.setMaxWidth(UiKit.dp(mActivity, 220));
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mTitle.setPadding(UiKit.dp(mActivity, 6), 0, UiKit.dp(mActivity, 10), 0);
        mTitle.setText(R.string.wb_loading);
        bar.addView(mTitle);
        mUndo = iconButton(R.drawable.ic_wb_undo, R.string.wb_undo, () -> WorldBuilderActivity.nativeCommand("undo", ""));
        mRedo = iconButton(R.drawable.ic_wb_redo, R.string.wb_redo, () -> WorldBuilderActivity.nativeCommand("redo", ""));
        View save = iconButton(R.drawable.ic_gzh_save, R.string.wb_save, mHost::onSave);
        // GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 Save As (a copy) beside Save, not only
        // in the menu (owner report: it was not found there).
        View saveAs = iconButton(R.drawable.ic_wb_save_as, R.string.wb_save_as, mHost::onSaveAs);
        View scripts = labelledButton(R.drawable.ic_wb_script, R.string.wb_scripts_short, mHost::onScripts);
        View help = iconButton(R.drawable.ic_wb_help, R.string.wb_help, mHost::onHelp);
        final View[] moreRef = new View[1];
        View more = iconButton(R.drawable.ic_wb_more, R.string.wb_more, () -> mHost.onMenu(moreRef[0]));
        moreRef[0] = more;
        for (View v : new View[] { mUndo, mRedo, save, saveAs, scripts, help, more }) {
            bar.addView(v);
            mEnabledWhenReady.add(v);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = UiKit.dp(mActivity, 10);
        mActivity.addContentView(bar, lp);
        bar.setElevation(1001f);
        setHistory(false, false, false);
    }

    private void buildToolBar() {
        LinearLayout rail = new LinearLayout(mActivity);
        rail.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(mActivity, 4);
        rail.setPadding(pad, pad, pad, pad);
        rail.setBackground(surface(26));
        for (Object[] t : TOOLS) {
            final String id = (String) t[0];
            View b = toolButton((Integer) t[1], (Integer) t[3], (Integer) t[2], () -> onToolTapped(id));
            rail.addView(b);
            mToolButtons.put(id, b);
            mEnabledWhenReady.add(b);
        }
        // The mission tools: trigger areas are a mode above; scripts open the script editor.
        View divider = new View(mActivity);
        divider.setBackgroundColor(UiKit.color(mActivity, R.color.gzh_outline_variant));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(UiKit.dp(mActivity, 48), UiKit.dp(mActivity, 1));
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.topMargin = dlp.bottomMargin = UiKit.dp(mActivity, 4);
        rail.addView(divider, dlp);
        View scriptsTool = toolButton(R.drawable.ic_wb_script, R.string.wb_tool_scripts_short, R.string.wb_scripts, mHost::onScripts);
        rail.addView(scriptsTool);
        mEnabledWhenReady.add(scriptsTool);
        ScrollView scroll = new ScrollView(mActivity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rail);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
        lp.setMarginStart(UiKit.dp(mActivity, 12));
        lp.topMargin = UiKit.dp(mActivity, 64);
        lp.bottomMargin = UiKit.dp(mActivity, 12);
        mActivity.addContentView(scroll, lp);
        scroll.setElevation(1001f);
    }

    private void buildPanel() {
        mPanel = new LinearLayout(mActivity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(mActivity, 12);
        mPanel.setPadding(pad, UiKit.dp(mActivity, 6), pad, pad);
        mPanel.setBackground(surface(22));

        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        mPanelTitle = new TextView(mActivity);
        mPanelTitle.setTextColor(UiKit.color(mActivity, R.color.gzh_primary));
        mPanelTitle.setTextSize(13);
        mPanelTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.addView(mPanelTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View toggle = iconButton(R.drawable.ic_wb_tune, R.string.wb_options, () -> setPanelOpen(!mPanelOpen));
        header.addView(toggle);
        mPanel.addView(header);

        mPanelBody = new LinearLayout(mActivity);
        mPanelBody.setOrientation(LinearLayout.VERTICAL);
        mPanel.addView(mPanelBody);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            UiKit.dp(mActivity, 380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMarginEnd(UiKit.dp(mActivity, 12));
        lp.bottomMargin = UiKit.dp(mActivity, 12);
        mActivity.addContentView(mPanel, lp);
        mPanel.setElevation(1001f);
        showPanelFor("camera");
    }

    // ------------------------------------------------------------------ state from the editor

    void setReady(boolean ready) {
        for (View v : mEnabledWhenReady) {
            v.setEnabled(ready);
            v.setAlpha(ready ? 1f : 0.4f);
        }
    }

    void setTitle(CharSequence title) {
        mTitle.setText(title);
    }

    void setHistory(boolean canUndo, boolean canRedo, boolean modified) {
        mUndo.setEnabled(canUndo);
        mUndo.setAlpha(canUndo ? 1f : 0.35f);
        mRedo.setEnabled(canRedo);
        mRedo.setAlpha(canRedo ? 1f : 0.35f);
    }

    /** The editor confirmed the current tool. */
    void setTool(String tool) {
        mTool = tool.isEmpty() ? "camera" : tool;
        for (Map.Entry<String, View> e : mToolButtons.entrySet()) {
            boolean on = e.getKey().equals(mTool);
            View b = e.getValue();
            if (on) {
                GradientDrawable sel = new GradientDrawable();
                sel.setCornerRadius(UiKit.dp(mActivity, 14));
                sel.setColor(UiKit.withAlpha(UiKit.color(mActivity, R.color.gzh_primary), 0x55));
                b.setBackground(sel);
            } else {
                b.setBackground(new android.graphics.drawable.RippleDrawable(
                    UiKit.tint(mActivity, R.color.gzh_ripple_primary), null, null));
            }
        }
        showPanelFor(mTool);
    }

    void setObjects(String list) {
        mObjects.clear();
        for (String line : list.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                mObjects.add(new String[] { line.substring(0, tab), line.substring(tab + 1) });
            }
        }
        if ("object".equals(mTool)) showPanelFor(mTool);
    }

    void setTeams(String list) {
        mTeams.clear();
        for (String line : list.split("\n")) {
            if (!line.isEmpty()) mTeams.add(line);
        }
    }

    void setSelection(String kind, String name) {
        switch (kind) {
            case "object": mSelectedObject = name.isEmpty() ? null : name; break;
            case "waypoint": mSelectedWaypoint = name.isEmpty() ? null : name; mSelectedArea = null; break;
            case "area": mSelectedArea = name.isEmpty() ? null : name; mSelectedWaypoint = null; break;
            default: break;
        }
        if ("select".equals(mTool) || "waypoint".equals(mTool) || "area".equals(mTool)) {
            showPanelFor(mTool);
        }
    }

    /** Opens or folds the options panel with a short slide, so the change is seen, not a jump. */
    private void setPanelOpen(boolean open) {
        mPanelOpen = open;
        if (open) {
            mPanelBody.setVisibility(View.VISIBLE);
            mPanelBody.setAlpha(0f);
            mPanelBody.setTranslationY(UiKit.dp(mActivity, 16));
            mPanelBody.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            mPanelBody.animate().alpha(0f).translationY(UiKit.dp(mActivity, 16)).setDuration(140)
                .withEndAction(() -> { if (!mPanelOpen) mPanelBody.setVisibility(View.GONE); }).start();
        }
    }

    private void onToolTapped(String id) {
        if (id.equals(mTool)) {
            setPanelOpen(!mPanelOpen);
            return;
        }
        mPanelOpen = true;
        WorldBuilderActivity.nativeCommand("tool", id);
        if ("object".equals(id) && mObjects.isEmpty()) {
            WorldBuilderActivity.nativeCommand("list", "objects");
        }
        if ("object".equals(id)) {
            WorldBuilderActivity.nativeCommand("list", "teams");
        }
    }

    // ------------------------------------------------------------------ the options panel

    private String label(int res) {
        return mActivity.getString(res);
    }

    private int stringId(String name) {
        return mActivity.getResources().getIdentifier(name, "string", mActivity.getPackageName());
    }

    private void showPanelFor(String tool) {
        mPanelBody.removeAllViews();
        mPanelBody.setVisibility(mPanelOpen ? View.VISIBLE : View.GONE);
        if (mPanelOpen) {
            mPanelBody.setAlpha(0f);
            mPanelBody.animate().alpha(1f).setDuration(150).start();
        }
        for (Object[] t : TOOLS) {
            if (t[0].equals(tool)) mPanelTitle.setText((Integer) t[2]);
        }
        switch (tool) {
            case "camera":
                hint(R.string.wb_hint_camera);
                break;
            case "select":
                hint(R.string.wb_hint_select);
                toggle(R.string.wb_opt_multiselect, false, v -> WorldBuilderActivity.nativeCommand("set", "multiselect " + (v ? 1 : 0)));
                UiKit.button(mPanelBody, UiKit.BTN_TONAL, R.drawable.ic_wb_delete,
                    label(R.string.wb_delete), () -> WorldBuilderActivity.nativeCommand("delete", ""));
                if (mSelectedObject != null) hint(mActivity.getString(R.string.wb_selected, mSelectedObject));
                if (mSelectedWaypoint != null) waypointNameEditor(mSelectedWaypoint);
                if (mSelectedArea != null) areaNameEditor(mSelectedArea);
                break;
            case "raise":
            case "lower":
                slider(R.string.wb_opt_amount, 1, 21, 2, v -> WorldBuilderActivity.nativeCommand("set", "mound.height " + v));
                slider(R.string.wb_opt_width, 1, 51, 5, v -> WorldBuilderActivity.nativeCommand("set", "mound.width " + v));
                slider(R.string.wb_opt_feather, 0, 20, 3, v -> WorldBuilderActivity.nativeCommand("set", "mound.feather " + v));
                break;
            case "smooth":
                slider(R.string.wb_opt_width, 2, 51, 5, v -> WorldBuilderActivity.nativeCommand("set", "smooth.feather " + v));
                slider(R.string.wb_opt_rate, 1, 10, 3, v -> WorldBuilderActivity.nativeCommand("set", "smooth.rate " + v));
                slider(R.string.wb_opt_radius, 1, 5, 1, v -> WorldBuilderActivity.nativeCommand("set", "smooth.radius " + v));
                break;
            case "height":
                slider(R.string.wb_opt_height, 0, 255, 16, v -> WorldBuilderActivity.nativeCommand("set", "brush.height " + v));
                slider(R.string.wb_opt_width, 1, 51, 5, v -> WorldBuilderActivity.nativeCommand("set", "brush.width " + v));
                slider(R.string.wb_opt_feather, 0, 20, 2, v -> WorldBuilderActivity.nativeCommand("set", "brush.feather " + v));
                break;
            case "object":
                objectPicker();
                break;
            case "waypoint":
                hint(R.string.wb_hint_waypoint);
                if (mSelectedWaypoint != null) waypointNameEditor(mSelectedWaypoint);
                break;
            case "area":
                hint(R.string.wb_hint_area);
                if (mSelectedArea != null) areaNameEditor(mSelectedArea);
                break;
            default:
                break;
        }
        if (!"camera".equals(tool) && !"object".equals(tool)) {
            toggle(R.string.wb_opt_snap, WorldBuilderActivity.prefSnap(mActivity), v -> {
                WorldBuilderActivity.setPrefSnap(mActivity, v);
                WorldBuilderActivity.nativeCommand("set", "snap " + (v ? 1 : 0));
            });
        }
    }

    private void hint(int res) {
        hint(label(res));
    }

    private void hint(CharSequence text) {
        TextView t = new TextView(mActivity);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(UiKit.color(mActivity, R.color.gzh_on_surface_variant));
        t.setPadding(0, UiKit.dp(mActivity, 2), 0, UiKit.dp(mActivity, 6));
        mPanelBody.addView(t);
    }

    interface IntSink { void accept(int v); }
    interface BoolSink { void accept(boolean v); }

    private void slider(int label, int from, int to, int initial, IntSink sink) {
        final TextView caption = new TextView(mActivity);
        caption.setTextSize(12);
        caption.setTextColor(UiKit.color(mActivity, R.color.gzh_on_surface));
        caption.setText(label(label) + ": " + initial);
        mPanelBody.addView(caption);
        Slider s = new Slider(mActivity);
        s.setValueFrom(from);
        s.setValueTo(to);
        s.setStepSize(1f);
        s.setValue(initial);
        s.addOnChangeListener((sl, value, fromUser) -> {
            caption.setText(label(label) + ": " + (int) value);
            if (fromUser) sink.accept((int) value);
        });
        mPanelBody.addView(s);
        sink.accept(initial);
    }

    private void toggle(int label, boolean initial, BoolSink sink) {
        MaterialSwitch sw = new MaterialSwitch(mActivity);
        sw.setText(label);
        sw.setTextSize(12);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((b, v) -> sink.accept(v));
        mPanelBody.addView(sw);
    }

    private void waypointNameEditor(String current) {
        nameEditor(R.string.wb_wp_name, current, "waypoint.name");
        // EA's name list: the camera start and the eight player starts.
        HorizontalScrollView hs = new HorizontalScrollView(mActivity);
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = { "InitialCameraPosition", "Player_1_Start", "Player_2_Start", "Player_3_Start",
            "Player_4_Start", "Player_5_Start", "Player_6_Start", "Player_7_Start", "Player_8_Start" };
        for (String n : names) {
            TextView chip = UiKit.chip(row, 0, n, R.color.gzh_on_surface, R.color.gzh_surface_container_high);
            chip.setOnClickListener(v -> WorldBuilderActivity.nativeCommand("set", "waypoint.name " + n));
        }
        hs.addView(row);
        mPanelBody.addView(hs);
    }

    private void areaNameEditor(String current) {
        nameEditor(R.string.wb_area_name, current, "waypoint.name");
    }

    private void nameEditor(int label, String current, String key) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final EditText name = new EditText(mActivity);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        name.setText(current);
        name.setHint(label);
        name.setTextSize(13);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton apply = new MaterialButton(mActivity);
        apply.setText(R.string.wb_apply);
        apply.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (!n.isEmpty()) WorldBuilderActivity.nativeCommand("set", key + " " + n);
        });
        row.addView(apply);
        mPanelBody.addView(row);
    }

    private void objectPicker() {
        if (mCurrentObject != null) {
            hint(mActivity.getString(R.string.wb_selected, mCurrentObject));
        } else {
            hint(R.string.wb_hint_object);
        }
        final EditText search = new EditText(mActivity);
        search.setSingleLine(true);
        search.setHint(R.string.wb_obj_search);
        search.setTextSize(13);
        search.setText(mObjectFilter);
        mPanelBody.addView(search);

        HorizontalScrollView hs = new HorizontalScrollView(mActivity);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(mActivity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] c : CATEGORIES) {
            final String cat = c[0];
            int res = stringId(c[1]);
            TextView chip = UiKit.chip(chips, 0, res != 0 ? label(res) : cat,
                cat.equals(mObjectCategory) ? R.color.gzh_on_primary : R.color.gzh_on_surface,
                cat.equals(mObjectCategory) ? R.color.gzh_primary : R.color.gzh_surface_container_high);
            chip.setOnClickListener(v -> {
                mObjectCategory = cat;
                mObjectFilter = "";
                showPanelFor("object");
            });
        }
        hs.addView(chips);
        mPanelBody.addView(hs);

        final ListView list = new ListView(mActivity);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        Runnable refill = () -> {
            adapter.clear();
            String f = mObjectFilter.toLowerCase(Locale.ROOT);
            for (String[] o : mObjects) {
                boolean match = f.isEmpty() ? o[1].equals(mObjectCategory) : o[0].toLowerCase(Locale.ROOT).contains(f);
                if (match) adapter.add(o[0]);
            }
            adapter.notifyDataSetChanged();
        };
        refill.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                mObjectFilter = s.toString().trim();
                refill.run();
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            mCurrentObject = adapter.getItem(pos);
            WorldBuilderActivity.nativeCommand("set", "object " + mCurrentObject);
            showPanelFor("object");
        });
        mPanelBody.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(mActivity, 160)));

        if (!mTeams.isEmpty()) {
            MaterialButton owner = new MaterialButton(mActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            owner.setText(R.string.wb_obj_owner);
            owner.setOnClickListener(v -> {
                CharSequence[] items = new CharSequence[mTeams.size()];
                for (int i = 0; i < mTeams.size(); i++) {
                    String t = mTeams.get(i);
                    items[i] = "team".equals(t) ? label(R.string.wb_obj_neutral) : t;
                }
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(mActivity)
                    .setTitle(R.string.wb_obj_owner)
                    .setItems(items, (d, which) -> WorldBuilderActivity.nativeCommand("set", "owner " + mTeams.get(which)))
                    .show();
            });
            mPanelBody.addView(owner);
        }
    }
}
