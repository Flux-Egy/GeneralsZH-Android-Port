// GeneralsX @feature Flux Build 06/11/2026 Device figures for the in-game HUD and the thermal tier.
//
// Implemented in the device layer (SDL3GameEngine.cpp), Android and Linux only:
//  - frames per second over the last whole second, counted in SDL3GameEngine::update;
//  - the hottest readable thermal zone in degrees C, read on a low-priority thread of its own
//    every 2 s (a sysfs read on the engine's thread once caused a hitch every two seconds; see
//    LESSON-gles-upscaler-and-frame-pacing.md), -1 while unknown or unreadable.
// Both are plain reads of atomics, callable from any thread (the HUD calls them through JNI).
#pragma once

float GXDeviceFps();
int GXDeviceHottestTempC();
