// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: the mission editor screen -- EA's Player List,
// Teams and Script dialogs on one full-screen page. It works like EA's: the editor copies the players,
// teams and scripts when the page opens; every change goes to that copy (FluxWBScripts.cpp, EA's edit
// logic); OK puts the copy into the map as one undo step, Cancel throws it away. This class only shows
// the text the editor sends and returns indexes and values.
package com.generalsx.zerohour;

import android.app.Dialog;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WbScriptEditor {
    private static WbScriptEditor sOpen;

    private final WorldBuilderActivity mActivity;
    private final Dialog mDialog;
    private final LinearLayout mBody;
    private int mTab;
    // The editor's data (scriptsTree / scriptDetail lines).
    private final List<String[]> mTree = new ArrayList<>();
    private List<String[]> mDetail = new ArrayList<>();
    private String mDetailKey;
    private final List<String[]> mConditionTemplates = new ArrayList<>();
    private final List<String[]> mActionTemplates = new ArrayList<>();
    private final List<String> mFactions = new ArrayList<>();
    /** The script on screen ("p g s"), or null for the lists. */
    private String mScript;
    private int mSelectedPlayer;
    private int mSelectedGroup = -1;
    private ChoiceCallback mPendingChoices;

    interface ChoiceCallback { void onChoices(List<String[]> choices); }

    static void open(WorldBuilderActivity activity) {
        if (sOpen != null) return;
        sOpen = new WbScriptEditor(activity);
        WorldBuilderActivity.nativeCommand("mission", "begin");
        WorldBuilderActivity.nativeCommand("mission", "templates c");
        WorldBuilderActivity.nativeCommand("mission", "templates a");
        WorldBuilderActivity.nativeCommand("mission", "factions");
        sOpen.mDialog.show();
    }

    /** Events from the editor while the page is open; false when the page is not open. */
    static boolean onEvent(String kind, String text) {
        WbScriptEditor e = sOpen;
        if (e == null) return false;
        switch (kind) {
            case "mission": e.mTree.clear(); for (String l : lines(text)) e.mTree.add(l.split("\t", -1)); e.refresh(); return true;
            case "script": e.setDetail(text); return true;
            case "templates_c": e.mConditionTemplates.clear(); for (String l : lines(text)) e.mConditionTemplates.add(l.split("\t", 2)); return true;
            case "templates_a": e.mActionTemplates.clear(); for (String l : lines(text)) e.mActionTemplates.add(l.split("\t", 2)); return true;
            case "factions": e.mFactions.clear(); e.mFactions.addAll(lines(text)); return true;
            case "choices": e.deliverChoices(text); return true;
            case "edited": e.mPendingForce = null; if (e.mScript != null) WorldBuilderActivity.nativeCommand("mission", "get " + e.mScript); return true;
            case "missiondone": return true;
            case "error":
                if (text.startsWith("inuse ") && e.mPendingForce != null) {
                    final String cmd = e.mPendingForce;
                    e.mPendingForce = null;
                    new MaterialAlertDialogBuilder(e.mActivity)
                        .setTitle(R.string.wb_delete)
                        .setMessage(e.mActivity.getString(R.string.wb_inuse_message, text.substring(6)))
                        .setPositiveButton(R.string.wb_delete, (d, w) -> edit(cmd + " force"))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                    return true;
                }
                return false;
            default: return false;
        }
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) if (!l.isEmpty()) out.add(l);
        return out;
    }

    private WbScriptEditor(WorldBuilderActivity activity) {
        mActivity = activity;
        mDialog = new Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setCancelable(false);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(activity, R.color.gzh_background));
        int pad = UiKit.dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(R.string.wb_mission_title);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(activity, R.color.gzh_on_surface));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton cancel = new MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(v -> close(false));
        top.addView(cancel);
        MaterialButton ok = new MaterialButton(activity);
        ok.setText(android.R.string.ok);
        ok.setOnClickListener(v -> close(true));
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okLp.setMarginStart(UiKit.dp(activity, 8));
        top.addView(ok, okLp);
        root.addView(top);

        TabLayout tabs = new TabLayout(activity);
        tabs.addTab(tabs.newTab().setText(R.string.wb_mission_scripts));
        tabs.addTab(tabs.newTab().setText(R.string.wb_mission_players));
        tabs.addTab(tabs.newTab().setText(R.string.wb_mission_teams));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { mTab = tab.getPosition(); mScript = null; refresh(); }
            @Override public void onTabUnselected(TabLayout.Tab tab) { }
            @Override public void onTabReselected(TabLayout.Tab tab) { mScript = null; refresh(); }
        });
        root.addView(tabs);

        ScrollView scroll = new ScrollView(activity);
        mBody = new LinearLayout(activity);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mBody.setPadding(0, UiKit.dp(activity, 8), 0, UiKit.dp(activity, 24));
        scroll.addView(mBody);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mDialog.setContentView(root);
    }

    private void close(boolean commit) {
        WorldBuilderActivity.nativeCommand("mission", commit ? "commit" : "cancel");
        mDialog.dismiss();
        sOpen = null;
    }

    static void closeIfOpen() {
        if (sOpen != null) {
            sOpen.mDialog.dismiss();
            sOpen = null;
        }
    }

    /** Before another map is opened: the copy belongs to the old map, so it is dropped. */
    static void cancelIfOpen() {
        if (sOpen != null) {
            WorldBuilderActivity.nativeCommand("mission", "cancel");
            closeIfOpen();
        }
    }

    private static void edit(String command) {
        WorldBuilderActivity.nativeCommand("mission", "edit " + command);
    }

    /** EA asks before removing a player or team that map objects use; the editor answers "inuse N". */
    private String mPendingForce;

    private void editConfirmingUse(String command) {
        mPendingForce = command;
        edit(command);
    }

    // ------------------------------------------------------------------ pages

    private void refresh() {
        mBody.removeAllViews();
        if (mScript != null && mTab == 0) {
            showScript();
            return;
        }
        switch (mTab) {
            case 0: showScriptTree(); break;
            case 1: showPlayers(); break;
            default: showTeams(); break;
        }
    }

    private TextView row(CharSequence text, int indentDp, boolean bold, View.OnClickListener click) {
        TextView t = new TextView(mActivity);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(UiKit.color(mActivity, bold ? R.color.gzh_primary : R.color.gzh_on_surface));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        int v = UiKit.dp(mActivity, 9);
        t.setPadding(UiKit.dp(mActivity, 8 + indentDp), v, UiKit.dp(mActivity, 8), v);
        if (click != null) {
            t.setBackground(new android.graphics.drawable.RippleDrawable(UiKit.tint(mActivity, R.color.gzh_ripple_primary), null, null));
            t.setOnClickListener(click);
        }
        mBody.addView(t);
        return t;
    }

    private LinearLayout buttons() {
        LinearLayout r = new LinearLayout(mActivity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        mBody.addView(r);
        return r;
    }

    private MaterialButton button(LinearLayout row, int label, View.OnClickListener click) {
        MaterialButton b = new MaterialButton(mActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(UiKit.dp(mActivity, 6));
        row.addView(b, lp);
        return b;
    }

    private String playerLabel(String[] p) {
        String name = p.length > 2 && !p[2].isEmpty() ? p[2] : mActivity.getString(R.string.wb_obj_neutral);
        return p.length > 3 && !p[3].isEmpty() ? name + "  \u00b7  " + p[3] : name;
    }

    private void showScriptTree() {
        LinearLayout actions = buttons();
        button(actions, R.string.wb_script_new, v -> edit("new " + mSelectedPlayer + " " + mSelectedGroup));
        button(actions, R.string.wb_script_new_folder, v -> askText(R.string.wb_script_new_folder, "", name ->
            edit("newgroup " + mSelectedPlayer + " " + name.replace(' ', '_'))));
        row(mActivity.getString(R.string.wb_script_hint), 0, false, null).setTextSize(12);
        for (String[] l : mTree) {
            switch (l[0]) {
                case "P": {
                    final int p = Integer.parseInt(l[1]);
                    TextView t = row(playerLabel(l), 0, true, v -> { mSelectedPlayer = p; mSelectedGroup = -1; refresh(); });
                    if (p == mSelectedPlayer && mSelectedGroup < 0) t.setText("\u25b6 " + t.getText());
                    break;
                }
                case "G": {
                    final int p = Integer.parseInt(l[1]), g = Integer.parseInt(l[2]);
                    String label = "📁 " + l[3] + (l[4].charAt(0) == '1' ? "" : "  " + mActivity.getString(R.string.wb_script_inactive));
                    TextView t = row(label, 16, false, v -> { mSelectedPlayer = p; mSelectedGroup = g; refresh(); });
                    t.setOnLongClickListener(v -> { confirm(R.string.wb_script_delete_folder, () -> edit("deletegroup " + p + " " + g)); return true; });
                    if (p == mSelectedPlayer && g == mSelectedGroup) t.setText("\u25b6 " + t.getText());
                    break;
                }
                case "S": {
                    final String key = l[1] + " " + l[2] + " " + l[3];
                    boolean inGroup = !"-1".equals(l[2]);
                    String flags = l[5];
                    String label = l[4] + (flags.charAt(0) == '1' ? "" : "  " + mActivity.getString(R.string.wb_script_inactive))
                        + (flags.length() > 1 && flags.charAt(1) == '1' ? "  " + mActivity.getString(R.string.wb_script_subroutine) : "");
                    row(label, inGroup ? 32 : 16, false, v -> {
                        mScript = key;
                        mDetail = new ArrayList<>();
                        WorldBuilderActivity.nativeCommand("mission", "get " + key);
                        refresh();
                    });
                    break;
                }
                default:
                    break;
            }
        }
    }

    private void showPlayers() {
        LinearLayout actions = buttons();
        button(actions, R.string.wb_player_add, v -> {
            if (mFactions.isEmpty()) return;
            new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.wb_player_add)
                .setItems(mFactions.toArray(new CharSequence[0]), (d, w) -> edit("addplayer " + mFactions.get(w)))
                .show();
        });
        row(mActivity.getString(R.string.wb_player_hint), 0, false, null).setTextSize(12);
        for (String[] l : mTree) {
            if (!"P".equals(l[0])) continue;
            final int p = Integer.parseInt(l[1]);
            final boolean human = l.length > 4 && "1".equals(l[4]);
            String label = playerLabel(l) + "  \u00b7  " + mActivity.getString(human ? R.string.wb_player_human : R.string.wb_player_computer);
            TextView t = row(label, 0, false, v -> {
                if (p == 0) return;
                CharSequence[] items = { mActivity.getString(human ? R.string.wb_player_make_computer : R.string.wb_player_make_human),
                    mActivity.getString(R.string.wb_delete) };
                new MaterialAlertDialogBuilder(mActivity).setTitle(playerLabel(l)).setItems(items, (d, w) -> {
                    if (w == 0) edit("playerhuman " + p + " " + (human ? 0 : 1));
                    else confirm(R.string.wb_delete, () -> editConfirmingUse("removeplayer " + p));
                }).show();
            });
        }
    }

    private void showTeams() {
        LinearLayout actions = buttons();
        button(actions, R.string.wb_team_add, v -> {
            final List<String> owners = new ArrayList<>();
            // The neutral player's name is empty; the editor takes "-" for it.
            for (String[] l : mTree) if ("P".equals(l[0])) owners.add(l.length > 2 && !l[2].isEmpty() ? l[2] : "-");
            CharSequence[] labels = new CharSequence[owners.size()];
            for (int i = 0; i < owners.size(); i++) labels[i] = "-".equals(owners.get(i)) ? mActivity.getString(R.string.wb_obj_neutral) : owners.get(i);
            new MaterialAlertDialogBuilder(mActivity).setTitle(R.string.wb_team_owner).setItems(labels, (d, w) ->
                askText(R.string.wb_team_name, "", name -> edit("addteam " + name.replace(' ', '_') + " " + owners.get(w))))
                .show();
        });
        for (String[] l : mTree) {
            if (!"T".equals(l[0])) continue;
            final int t = Integer.parseInt(l[1]);
            String owner = l.length > 3 && !l[3].isEmpty() ? l[3] : mActivity.getString(R.string.wb_obj_neutral);
            row(l[2] + "  \u00b7  " + owner, 0, false, v -> confirm(R.string.wb_delete, () -> editConfirmingUse("removeteam " + t)));
        }
    }

    // ------------------------------------------------------------------ the script page

    private void setDetail(String text) {
        List<String> ls = lines(text);
        if (ls.isEmpty()) return;
        String key = ls.get(0).trim();
        if (!key.equals(mScript)) return;
        mDetailKey = key;
        mDetail = new ArrayList<>();
        for (int i = 1; i < ls.size(); i++) mDetail.add(ls.get(i).split("\t", -1));
        if (mTab == 0 && mScript != null) refresh();
    }

    private String[] line(String kind) {
        for (String[] l : mDetail) if (l[0].equals(kind)) return l;
        return new String[0];
    }

    private String field(String kind) {
        for (String[] l : mDetail) if (l[0].equals(kind)) return l.length > 1 ? l[1] : "";
        return "";
    }

    private void showScript() {
        final String key = mScript;
        LinearLayout nav = buttons();
        button(nav, R.string.wb_script_back, v -> { mScript = null; refresh(); });
        button(nav, R.string.wb_script_copy, v -> { edit("copy " + key); mScript = null; });
        button(nav, R.string.wb_delete, v -> confirm(R.string.wb_delete, () -> { edit("delete " + key); mScript = null; }));
        if (!key.equals(mDetailKey)) {
            row(mActivity.getString(R.string.wb_loading), 0, false, null);
            return;
        }
        if (mDetail.isEmpty()) {
            // The script is gone (deleted or moved): back to the list.
            mScript = null;
            refresh();
            return;
        }
        // Properties (EA ScriptProperties page).
        row(mActivity.getString(R.string.wb_script_name) + ": " + field("N"), 0, true,
            v -> askText(R.string.wb_script_name, field("N"), s -> edit("prop " + key + " name " + s)));
        row(mActivity.getString(R.string.wb_script_comment) + ": " + field("C"), 0, false,
            v -> askText(R.string.wb_script_comment, field("C"), s -> edit("prop " + key + " comment " + s)));
        String[] fline = line("F");
        String bits = fline.length > 1 ? fline[1] : "000000";
        String[] names = { "active", "oneshot", "easy", "normal", "hard", "subroutine" };
        int[] labels = { R.string.wb_script_active, R.string.wb_script_oneshot, R.string.wb_script_easy,
            R.string.wb_script_normal, R.string.wb_script_hard, R.string.wb_script_subroutine_flag };
        for (int i = 0; i < names.length && i < bits.length(); i++) {
            final String n = names[i];
            MaterialSwitch sw = new MaterialSwitch(mActivity);
            sw.setText(labels[i]);
            sw.setChecked(bits.charAt(i) == '1');
            sw.setOnCheckedChangeListener((b, on) -> edit("prop " + key + " " + n + " " + (on ? 1 : 0)));
            mBody.addView(sw);
        }
        String delay = fline.length > 2 ? fline[2] : "0";
        row(mActivity.getString(R.string.wb_script_delay, delay), 0, false,
            v -> askNumber(R.string.wb_script_delay_title, delay, s -> edit("prop " + key + " delay " + s)));

        // If (EA ScriptConditions page): OR clauses of AND conditions.
        row(mActivity.getString(R.string.wb_script_if), 0, true, null);
        int clauses = 0;
        for (int i = 0; i < mDetail.size(); i++) {
            String[] l = mDetail.get(i);
            if (l[0].equals("O")) {
                final int o = Integer.parseInt(l[1]);
                if (clauses++ > 0) row(mActivity.getString(R.string.wb_script_or), 0, true, null);
                LinearLayout add = buttons();
                button(add, R.string.wb_script_add_and, v -> pickTemplate(mConditionTemplates, type -> edit("addcond " + key + " " + o + " " + type)));
            } else if (l[0].equals("c")) {
                final String addr = l[1] + " " + l[2];
                itemRow(l[4], 16, "c", addr, i, key, mConditionTemplates,
                    type -> edit("setcond " + key + " " + addr + " " + type),
                    () -> edit("delcond " + key + " " + addr));
            }
        }
        LinearLayout or = buttons();
        button(or, R.string.wb_script_add_or, v -> edit("addor " + key));

        for (String section : new String[] { "t", "f" }) {
            row(mActivity.getString("t".equals(section) ? R.string.wb_script_then : R.string.wb_script_else), 0, true, null);
            for (int i = 0; i < mDetail.size(); i++) {
                String[] l = mDetail.get(i);
                if (!l[0].equals(section)) continue;
                final String addr = section + " " + l[1];
                itemRow(l[3], 16, section, l[1], i, key, mActionTemplates,
                    type -> edit("setact " + key + " " + addr + " " + type),
                    () -> edit("delact " + key + " " + addr));
            }
            LinearLayout add = buttons();
            button(add, R.string.wb_script_add_action, v -> pickTemplate(mActionTemplates, type -> edit("addact " + key + " " + section + " " + type)));
        }
    }

    interface IntSink { void accept(int v); }
    interface TextSink { void accept(String s); }

    /** A condition or action: its sentence; tap for the parameter list, change type or delete. */
    private void itemRow(String text, int indent, String kind, String addr, int lineIndex, String key,
                         List<String[]> templates, IntSink setType, Runnable delete) {
        final List<String[]> params = new ArrayList<>();
        for (int j = lineIndex + 1; j < mDetail.size(); j++) {
            String[] p = mDetail.get(j);
            if (p[0].equals(kind + "p")) params.add(p); else break;
        }
        row("\u2022 " + text, indent, false, v -> {
            List<CharSequence> items = new ArrayList<>();
            for (String[] p : params) items.add(mActivity.getString(R.string.wb_script_param, Integer.parseInt(p[1]) + 1, p[3]));
            items.add(mActivity.getString(R.string.wb_script_change_type));
            items.add(mActivity.getString(R.string.wb_delete));
            new MaterialAlertDialogBuilder(mActivity).setTitle(text).setItems(items.toArray(new CharSequence[0]), (d, w) -> {
                if (w < params.size()) {
                    String[] p = params.get(w);
                    editParameter(key + " " + kind + " " + addr + " " + p[1], Integer.parseInt(p[2]), p[3]);
                } else if (w == params.size()) {
                    pickTemplate(templates, setType::accept);
                } else {
                    delete.run();
                }
            }).show();
        });
    }

    /** EA EditParameter: a list for the types with known values, else free entry. */
    private void editParameter(String address, int type, String current) {
        mPendingChoices = choices -> {
            if (choices.isEmpty()) {
                boolean numeric = isNumeric(type);
                // A coordinate is typed as x,y,z; its display text is not that, so it is not offered.
                askTextTyped(R.string.wb_script_value, isCoord(type) ? "" : stripQuotes(current), numeric, s ->
                    edit("param " + address + " " + (isReal(type) ? "r:" : isInt(type) ? "i:" : isCoord(type) ? "xyz:" : "s:") + s));
                return;
            }
            showChoicePicker(choices, value -> edit("param " + address + " " + (isInt(type) ? "i:" : "s:") + value));
        };
        WorldBuilderActivity.nativeCommand("mission", "choices " + type);
    }

    private void deliverChoices(String text) {
        ChoiceCallback cb = mPendingChoices;
        mPendingChoices = null;
        if (cb == null) return;
        List<String> ls = lines(text);
        List<String[]> out = new ArrayList<>();
        for (int i = 1; i < ls.size(); i++) out.add(ls.get(i).split("\t", 2));
        cb.onChoices(out);
    }

    // Parameter::ParameterType values (Scripts.h).
    private static boolean isInt(int t) {
        return t == 0 || t == 6 || t == 8 || t == 19 || t == 20 || t == 27 || t == 29 || t == 33 || t == 34 || t == 35
            || t == 36 || t == 37 || t == 43 || t == 50;
    }
    private static boolean isReal(int t) { return t == 1 || t == 17 || t == 51; }
    private static boolean isCoord(int t) { return t == 16; }
    private static boolean isNumeric(int t) { return isInt(t) || isReal(t); }

    private static String stripQuotes(String s) {
        return s.length() >= 2 && s.startsWith("'") && s.endsWith("'") ? s.substring(1, s.length() - 1) : s;
    }

    private void pickTemplate(List<String[]> templates, IntSink sink) {
        List<String[]> shown = new ArrayList<>();
        for (String[] t : templates) if (t.length > 1) shown.add(new String[] { t[0], t[1].replace("_/", " / ") });
        showChoicePicker(shown, v -> sink.accept(Integer.parseInt(v)));
    }

    /** A searchable list of {value, label}. */
    private void showChoicePicker(List<String[]> choices, TextSink sink) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(mActivity, 16);
        box.setPadding(pad, 0, pad, 0);
        EditText search = new EditText(mActivity);
        search.setSingleLine(true);
        search.setHint(R.string.wb_obj_search);
        box.addView(search);
        ListView list = new ListView(mActivity);
        final List<String[]> filtered = new ArrayList<>(choices);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1, new ArrayList<>());
        Runnable fill = () -> {
            adapter.clear();
            for (String[] c : filtered) adapter.add(c.length > 1 ? c[1] : c[0]);
            adapter.notifyDataSetChanged();
        };
        fill.run();
        list.setAdapter(adapter);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(mActivity, 320)));
        final Dialog[] dlg = new Dialog[1];
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String f = s.toString().toLowerCase(Locale.ROOT).trim();
                filtered.clear();
                for (String[] c : choices) {
                    String label = c.length > 1 ? c[1] : c[0];
                    if (f.isEmpty() || label.toLowerCase(Locale.ROOT).contains(f)) filtered.add(c);
                }
                fill.run();
            }
        });
        list.setOnItemClickListener((p, v, pos, id) -> {
            sink.accept(filtered.get(pos)[0]);
            if (dlg[0] != null) dlg[0].dismiss();
        });
        dlg[0] = new MaterialAlertDialogBuilder(mActivity).setView(box).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void askText(int title, String current, TextSink sink) {
        askTextTyped(title, current, false, sink);
    }

    private void askNumber(int title, String current, TextSink sink) {
        askTextTyped(title, current, true, sink);
    }

    private void askTextTyped(int title, String current, boolean numeric, TextSink sink) {
        final EditText in = new EditText(mActivity);
        in.setSingleLine(true);
        in.setText(current);
        in.setInputType(numeric ? (InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED)
            : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        FrameLayout box = new FrameLayout(mActivity);
        int pad = UiKit.dp(mActivity, 20);
        box.setPadding(pad, UiKit.dp(mActivity, 8), pad, 0);
        box.addView(in);
        new MaterialAlertDialogBuilder(mActivity).setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String s = in.getText().toString().trim();
                if (!s.isEmpty() || !numeric) sink.accept(s);
            })
            .setNegativeButton(android.R.string.cancel, null).show();
    }

    private void confirm(int title, Runnable yes) {
        new MaterialAlertDialogBuilder(mActivity).setTitle(title)
            .setPositiveButton(android.R.string.ok, (d, w) -> yes.run())
            .setNegativeButton(android.R.string.cancel, null).show();
    }
}
