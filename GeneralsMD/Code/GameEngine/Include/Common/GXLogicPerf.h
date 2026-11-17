/*
**	Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
**
**	This program is free software: you can redistribute it and/or modify it under the terms of the
**	GNU General Public License as published by the Free Software Foundation, either version 3 of the
**	License, or (at your option) any later version.
*/

// GeneralsX @performance Flux Build 17/11/2026 v1.4.5 12204 [GX-PERF-LOGIC] (owner: Snapdragon 8+ Gen 1, the logic
// step 6.7 ms on Flux against 2.3 ms on MYSOREZ 1.4.1, same phone and map). Where the time of one GameLogic::update
// goes, phase by phase, summed over every update (a drawn frame's own step and the speed catch-up steps alike);
// GameEngine.cpp prints it with the [GX-PERF] window and resets it. Clock reads only while performance tracing is
// on (GXTrace::isPerfEnabled); measuring only, nothing here changes what the logic does.

#pragma once

#include "GXTrace.h"

#include <chrono>

namespace GXLogicPerf
{
	enum Phase
	{
		SCRIPTS,	// TheScriptEngine
		TERRAIN,	// TheTerrainLogic
		CRC_COMMANDS,	// the replay / multiplayer CRC, stats, recorder, the command list
		OBJECTS,	// every object's update modules (normal and sleepy)
		AI,		// TheAI (pathfinding queue, skirmish AI players)
		BUILD,		// TheBuildAssistant
		PARTITION,	// ThePartitionManager (vision, shroud, collisions)
		DESTROY,	// the destroy list
		REST,		// weapon / locomotor stores, victory, disabled status, the rest of update()
		PHASE_COUNT
	};

	struct Totals
	{
		double us[PHASE_COUNT];
		unsigned updates;
	};

	inline Totals &totals()
	{
		static Totals t = {};
		return t;
	}

	inline const char *name(int p)
	{
		static const char *const s_names[PHASE_COUNT] = {
			"scripts", "terrain", "crc+commands", "objects", "ai", "build", "partition", "destroy", "rest" };
		return p >= 0 && p < PHASE_COUNT ? s_names[p] : "?";
	}

	/** One GameLogic::update: mark() closes the phase that just ran; the destructor books the rest. */
	class StepTimer
	{
	public:
		StepTimer() : m_on(GXTrace::isPerfEnabled())
		{
			if (m_on)
				m_last = std::chrono::steady_clock::now();
		}

		void mark(Phase p)
		{
			if (!m_on)
				return;
			const std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now();
			totals().us[p] += std::chrono::duration<double, std::micro>(now - m_last).count();
			m_last = now;
		}

		/** An update that stops early (time frozen: a cinematic) is not counted as a logic step. */
		void cancel()
		{
			m_on = false;
		}

		~StepTimer()
		{
			if (!m_on)
				return;
			mark(REST);
			++totals().updates;
		}

	private:
		bool m_on;
		std::chrono::steady_clock::time_point m_last;
	};
}
