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

import android.app.ActivityManager;
import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/**
 * GeneralsX @feature Flux Build 23/10/2026 First-launch hardware profiling.
 *
 * This class only measures the device. It does not write anything: SetupActivity owns the
 * Options.ini write so the "first launch or clean install only" rule and the manual re-run
 * button stay in one place.
 */
final class DeviceAutoConfig {
    private static final long GIB = 1024L * 1024L * 1024L;
    // Android reports physical RAM minus kernel/firmware reservations, so a phone sold as 8 GB
    // usually reports 7.3-7.7 GB and a 6 GB phone 5.4-5.8 GB. Classify on the marketed tier, not
    // on the raw number, or every 8 GB device would quietly fall into Medium.
    private static final long HIGH_RAM_THRESHOLD = 7L * GIB;
    private static final long MEDIUM_RAM_THRESHOLD = 5L * GIB;

    /** The internal-resolution spinner uses 1 = 1080p and 5 = 1440p (see SetupActivity). */
    static final int INTERNAL_CHOICE_1080 = 1;
    static final int INTERNAL_CHOICE_720 = 2;
    static final int INTERNAL_CHOICE_1440 = 5;

    static final class Result {
        final long totalRamBytes;
        final float ramGigabytes;
        final int cpuCores;
        final int screenWidth;
        final int screenHeight;
        final int targetWidth;
        final int targetHeight;
        final String lod;
        final int internalResolutionChoice;

        Result(long totalRamBytes, int cpuCores, int screenWidth, int screenHeight,
               int targetWidth, int targetHeight, String lod, int internalResolutionChoice) {
            this.totalRamBytes = totalRamBytes;
            this.ramGigabytes = totalRamBytes / (float) GIB;
            this.cpuCores = cpuCores;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            this.lod = lod;
            this.internalResolutionChoice = internalResolutionChoice;
        }
    }

    private DeviceAutoConfig() {}

    static Result detect(Context context) {
        long ram = 0L;
        try {
            ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                ram = memoryInfo.totalMem;
            }
        } catch (Throwable ignored) {
            // A device that refuses to report RAM still gets the low-detail profile.
        }
        if (ram <= 0L) {
            ram = 3L * GIB;
        }

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

        int screenW = 1920;
        int screenH = 1080;
        try {
            WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
                screenW = Math.max(metrics.widthPixels, metrics.heightPixels) & ~1;
                screenH = Math.min(metrics.widthPixels, metrics.heightPixels) & ~1;
            }
        } catch (Throwable ignored) {
            // Keep the safe 1080p fallback.
        }
        if (screenW <= 0 || screenH <= 0) {
            screenW = 1920;
            screenH = 1080;
        }

        String lod = lodForRam(ram);
        // Match the values applyInternalResolutionSetting() writes on every launch. A 1200p
        // panel on an 8 GB phone would otherwise get 1200p from the first-launch write and
        // 1080p from the launch path; 720p (choice 2) since 1.4.4.
        // GeneralsX @tweak Flux Build 10/11/2026 720p for every device on first launch (owner, v1.4.4): smooth
        // out of the box. The player can still pick Native, 1080p or a custom size on the Graphics tab.
        int targetH = Math.min(screenH, 720);
        int choice = INTERNAL_CHOICE_720;
        int targetW = Math.round((float) screenW * targetH / screenH) & ~1;
        if (targetW <= 0) {
            targetW = screenW;
            targetH = screenH;
        }
        return new Result(ram, cores, screenW, screenH,
            targetW, targetH, lod, choice);
    }

    /** 8 GB+ High, 6 GB Medium, below 6 GB Low. */
    static String lodForRam(long ramBytes) {
        if (ramBytes >= HIGH_RAM_THRESHOLD) {
            return "High";
        }
        if (ramBytes >= MEDIUM_RAM_THRESHOLD) {
            return "Medium";
        }
        return "Low";
    }
}
