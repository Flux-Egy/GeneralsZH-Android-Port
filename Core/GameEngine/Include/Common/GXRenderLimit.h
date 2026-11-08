// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 08/11/2026 v1.4.2 The player's render frame-rate limit (launcher: Render rate,
// Options.ini GXRenderFps, passed on by SDL3Main.cpp as GX_RENDER_FPS).
//
// Network games (online, LAN): the network clocks the logic -- Network::timeForNewFrame releases one
// logic frame every 1/frameRate seconds of wall time and GameEngine runs it on the next drawn frame
// (canUpdateNetworkGameLogic). A higher render rate draws more frames between the same logic frames;
// the logic, its commands and the CRC do not change. GameEngine::update applies it each frame.
//
// Offline games (skirmish, campaign, challenge, replay): TheSuperHackers' Logic Time Scale runs the
// logic at the game-speed rate on its own clock and the frames are drawn at this rate
// (GameLogicDispatch MSG_NEW_GAME). The menus' background battle keeps the original coupling.
//
// The one rule: never fewer drawn frames than logic frames. The limit is at least 60 (owner) and at
// least the logic rate (GENERALS_ONLINE_HIGH_FPS_LIMIT, the network rate, the game speed).
#pragma once

#include <stdlib.h>

#if defined(__ANDROID__)
inline int gxRenderLimit()
{
	static int s_limit = -1;
	if (s_limit < 0)
	{
		const char *env = getenv("GX_RENDER_FPS");
		long v = env ? strtol(env, nullptr, 10) : 0;
		if (v <= 0)
			v = 60; // the default: steady and cool
		if (v > 1000000)
			v = 1000000; // RenderFpsPreset::UncappedFpsValue
		s_limit = (int)v;
	}
	int limit = s_limit;
	if (limit < 60)
		limit = 60;
	if (limit < GENERALS_ONLINE_HIGH_FPS_LIMIT)
		limit = GENERALS_ONLINE_HIGH_FPS_LIMIT;
	return limit;
}
#endif
