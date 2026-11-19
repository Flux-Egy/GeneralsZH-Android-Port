// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.
//
// GeneralsX @performance Flux Build 13/11/2026 v1.4.5 The launcher's low-end switches (Options.ini ->
// SDL3Main.cpp -> environment), applied after every detail preset by GameLODManager::applyStaticLODLevel
// (gxApplyLowEndOverrides) and kept by the in-game Options menu:
//   GXShadows = 0      (12188)  no shadows
//   GXParticles = 1             about half the particles (at least 500)
//   GXLowEffects = 1            no heat haze, no behind-building outlines, no cloud shadows / light map,
//                               hard shoreline, no tree sway
//   GXLowTracks = 1             shorter, faster-fading tank tracks
//   GXPotato = 1                all of the above (a quarter of the particles) plus no extra animations,
//                               dynamic LOD on and no cloud plane
// Drawing only: every value here is read by client-side drawing code, never by game logic, so players with
// different switches play together (no CRC, lockstep or network change). Particles use the client's own
// random numbers (ParticleSys.cpp: GameClientRandomValue only); trees are not touched (multiplayer forces
// them, GameLogic.cpp); the detail preset itself is unchanged.
#pragma once
#include <stdlib.h>
#include <string.h>

struct GXLowEndSettings
{
	int particles;		///< 0 = preset, 1 = half (>= 500), 2 = quarter (>= 250, Potato)
	bool lowEffects;
	bool lowTracks;
	bool potato;
	bool shadowsOff;
};

inline const GXLowEndSettings &gxLowEnd()
{
	static const GXLowEndSettings s = []()
	{
		auto on = [](const char *name)
		{
			const char *v = getenv(name);
			return v != nullptr && strcmp(v, "1") == 0;
		};
		GXLowEndSettings r = {};
		r.potato = on("GX_POTATO");
		r.particles = r.potato ? 2 : (on("GX_PARTICLES") ? 1 : 0);
		r.lowEffects = r.potato || on("GX_LOW_EFFECTS");
		r.lowTracks = r.potato || on("GX_LOW_TRACKS");
		const char *shadows = getenv("GX_SHADOWS");
		r.shadowsOff = r.potato || (shadows != nullptr && strcmp(shadows, "0") == 0);
		return r;
	}();
	return s;
}

inline bool gxLowEndAny()
{
	const GXLowEndSettings &s = gxLowEnd();
	return s.particles != 0 || s.lowEffects || s.lowTracks || s.potato || s.shadowsOff;
}

/// Applies the switches to TheWritableGlobalData (GameLOD.cpp). particles / tracks: also scale the particle
/// cap / tank tracks; the scaling remembers its last result, so applying again never halves twice.
void gxApplyLowEndOverrides(bool particles, bool tracks);
