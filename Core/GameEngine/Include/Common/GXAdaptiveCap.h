/*
**	Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
**
**	This program is free software: you can redistribute it and/or modify it under the terms of the
**	GNU General Public License as published by the Free Software Foundation, either version 3 of the
**	License, or (at your option) any later version.
*/

// GeneralsX @performance Flux Build 17/11/2026 v1.4.5 12210 Adaptive frame cap (owner). A sped-up offline game asks for
// 118 or 240 frames a second; a phone that draws 60-75 then shows uneven frames (the Snapdragon 8+ Gen 1 tester: "lag"
// at 71 fps where MYSOREZ at a 60 limit looked smooth with the same ~1000 draws a frame). When the game misses its
// limit by more than 10% for 3 seconds in a row, drawing is capped at 60 for steady pacing; when the engine thread's
// own work (the time before the limiter's wait) would fit the original limit with 25% to spare for 3 seconds, the cap
// lifts. Each state lasts at least 5 seconds (doubled, up to 60, when it has to come back within 30 s of lifting).
// Offline games only (skirmish, campaign; no network, no replay, never the menu or World Builder); off as soon as that
// stops being true. Review 12210: the game's pace follows the frames drawn (the speed catch-up is off since 12208 and a
// sped-up game's logic rate equals its render limit), so a cap at 60 also runs the game at 60 logic steps a second --
// the owner accepted that for a phone drawing 60-75; it engages only under MAX_ENGAGE_FPS (80) so it can never cost
// more than a quarter of the game's pace, and a phone drawing 100-200 of a 240 limit is never touched. While capped,
// GameEngine::canUpdateRegularGameLogic uses the capped limit too (capped()), so at normal speed the logic steps once
// per drawn frame exactly as with a 60 limit. Same logic frames in the same order: no CRC, replay, save or network
// change. gx_adaptive_cap_off.txt in the game folder turns it off.

#pragma once

#include <chrono>
#include <cstdio>

namespace GXAdaptiveCap
{
	const unsigned CAP_FPS = 60;
	const int WINDOW = 3;           // seconds measured before a change
	const double ENGAGE_RATIO = 0.90; // capped when every second of the window drew under 90% of the limit
	const double RELEASE_RATIO = 0.75; // lifted when the average work fits 75% of the limit's frame time
	const double MIN_HOLD_SECONDS = 5.0;
	const double HITCH_MS = 250.0;   // a frame this long (a load, a pause) restarts the measurement
	const double MAX_ENGAGE_FPS = 80.0; // review 12210: never cap a phone drawing more than this (pace cost <= 25%)
	const double MAX_HOLD_SECONDS = 60.0;
	const double FLAP_SECONDS = 30.0;   // coming back this soon after lifting doubles the hold

	struct State
	{
		int switchedOff = -1; // -1 unread
		bool capped = false;
		unsigned limit = 0;
		std::chrono::steady_clock::time_point bucketStart;
		bool haveBucket = false;
		unsigned frames = 0;
		double workMs = 0.0;
		unsigned workFrames = 0;
		double fps[WINDOW] = {};
		double work[WINDOW] = {};
		int filled = 0;
		std::chrono::steady_clock::time_point changedAt;
		std::chrono::steady_clock::time_point liftedAt;
		bool lifted = false;
		double hold = MIN_HOLD_SECONDS;
	};

	inline State &state()
	{
		static State s;
		return s;
	}

	/** Whether drawing is capped right now (GameEngine::canUpdateRegularGameLogic paces the logic by it). */
	inline bool capped()
	{
		return state().capped;
	}

	inline void resetWindow(State &s)
	{
		s.haveBucket = false;
		s.filled = 0;
		s.frames = 0;
		s.workMs = 0.0;
		s.workFrames = 0;
	}

	/**
	 * Once a frame, on the engine's thread, before the limiter waits. {@code limit}: the limit the game asks for;
	 * {@code eligible}: an offline game is being played; {@code workMs}: the engine thread's work in the frame that is
	 * ending (negative when unknown). Returns the limit to wait for.
	 */
	inline unsigned apply(unsigned limit, bool eligible, double workMs)
	{
		State &s = state();
		if (s.switchedOff < 0)
		{
			FILE *f = fopen("gx_adaptive_cap_off.txt", "r");
			s.switchedOff = f != nullptr ? 1 : 0;
			if (f != nullptr)
				fclose(f);
			fprintf(stderr, "[GX-FPS] adaptive cap %s\n", s.switchedOff ? "OFF (gx_adaptive_cap_off.txt)"
				: "on (offline: 60 when the limit is missed by 10% for 3 s, lifted when the work fits it again)");
		}
		const std::chrono::steady_clock::time_point now = std::chrono::steady_clock::now();
		if (s.switchedOff == 1 || !eligible || limit <= CAP_FPS || limit > 1000)
		{
			if (s.capped)
				fprintf(stderr, "[GX-FPS] adaptive cap off (not an offline game above %u fps any more)\n", CAP_FPS);
			s.capped = false;
			s.limit = 0;
			resetWindow(s);
			return limit;
		}
		if (limit != s.limit)
		{
			if (s.capped)
				fprintf(stderr, "[GX-FPS] adaptive cap off (the limit changed from %u to %u)\n", s.limit, limit);
			s.capped = false;
			s.limit = limit;
			s.changedAt = now;
			resetWindow(s);
		}
		if (workMs >= HITCH_MS)
		{
			resetWindow(s); // a load or a pause is not the game's pace
			return s.capped ? CAP_FPS : limit;
		}
		if (!s.haveBucket)
		{
			s.haveBucket = true;
			s.bucketStart = now;
			s.frames = 0;
			s.workMs = 0.0;
			s.workFrames = 0;
		}
		++s.frames;
		if (workMs >= 0.0)
		{
			s.workMs += workMs;
			++s.workFrames;
		}
		const double secs = std::chrono::duration<double>(now - s.bucketStart).count();
		if (secs >= 1.0)
		{
			for (int i = WINDOW - 1; i > 0; --i)
			{
				s.fps[i] = s.fps[i - 1];
				s.work[i] = s.work[i - 1];
			}
			s.fps[0] = s.frames / secs;
			s.work[0] = s.workFrames > 0 ? s.workMs / s.workFrames : 0.0;
			if (s.filled < WINDOW)
				++s.filled;
			s.bucketStart = now;
			s.frames = 0;
			s.workMs = 0.0;
			s.workFrames = 0;

			const double held = std::chrono::duration<double>(now - s.changedAt).count();
			if (s.filled == WINDOW && held >= s.hold)
			{
				if (!s.capped)
				{
					bool missed = true;
					for (int i = 0; i < WINDOW; ++i)
						missed = missed && s.fps[i] < ENGAGE_RATIO * limit && s.fps[i] < MAX_ENGAGE_FPS;
					if (missed)
					{
						s.capped = true;
						s.changedAt = now;
						// Review 12210: back soon after lifting -> hold the cap longer each time (no on / off every ~10 s).
						const bool flapping = s.lifted && std::chrono::duration<double>(now - s.liftedAt).count() < FLAP_SECONDS;
						s.hold = flapping ? (s.hold * 2.0 < MAX_HOLD_SECONDS ? s.hold * 2.0 : MAX_HOLD_SECONDS) : MIN_HOLD_SECONDS;
						fprintf(stderr, "[GX-FPS] adaptive cap %u on (measured %.0f/%.0f/%.0f fps, limit %u, hold %.0f s)\n",
							CAP_FPS, s.fps[2], s.fps[1], s.fps[0], limit, s.hold);
					}
				}
				else
				{
					const double avgWork = (s.work[0] + s.work[1] + s.work[2]) / WINDOW;
					const double budget = RELEASE_RATIO * 1000.0 / limit;
					if (avgWork > 0.0 && avgWork < budget)
					{
						s.capped = false;
						s.changedAt = now;
						s.liftedAt = now;
						s.lifted = true;
						fprintf(stderr, "[GX-FPS] adaptive cap off (work %.1f ms < %.1f ms for the %u limit)\n", avgWork, budget, limit);
					}
				}
			}
		}
		return s.capped ? CAP_FPS : limit;
	}
}
