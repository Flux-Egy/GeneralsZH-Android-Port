/*
 * Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 */
package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * GeneralsX @feature Flux Build 16/11/2026 v1.4.5 12204 The ONA layout editor in the launcher (owner): the active
 * profile on a full-screen landscape page laid out like the game's window (immersive, into the cutout), over a
 * sketch of the Zero Hour control bar, with Test mode to try what each control sends without starting a game.
 * Same editor as the gear in the game (OnaEditorView). Save writes the profile and keeps it the active one.
 */
public class OnaEditorActivity extends Activity {

    private OnaEditorView editor;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.hide(WindowInsetsCompat.Type.systemBars());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        final OnaProfile profile = OnaProfile.active(this);
        editor = new OnaEditorView(this, profile, false, new OnaEditorView.Host() {
            @Override
            public void onSave(OnaProfile edited) {
                boolean ok = OnaProfile.save(OnaEditorActivity.this, edited);
                if (ok) OnaProfile.setActive(OnaEditorActivity.this, edited.id);
                Toast.makeText(getApplicationContext(), ok ? R.string.ona_edit_saved : R.string.ona_edit_save_failed,
                    Toast.LENGTH_SHORT).show();
                if (ok) {
                    setResult(RESULT_OK);
                    finish();
                }
            }

            @Override
            public void onCancel() {
                leave();
            }
        });
        setContentView(editor);
    }

    /** Leaves; with unsaved changes, asks first. */
    private void leave() {
        if (editor == null || !editor.dirty()) {
            finish();
            return;
        }
        new AlertDialog.Builder(OnaElementSheet.dialogContext(this))
            .setTitle(R.string.ona_unsaved_title)
            .setPositiveButton(R.string.ona_discard, (d, w) -> finish())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        leave();
    }
}
