// GeneralsX @feature Flux Build 05/11/2026 Performance Guard and measurement report (GXPerfGuard.h).
#include "PreRTS.h"

#include "Common/GXPerfGuard.h"
#include "Common/GXDeviceStats.h"
#include "Common/FramePacer.h"
#include "Common/GameEngine.h"
#include "Common/MessageStream.h"
#include "Common/OptionPreferences.h"
#include "Common/Recorder.h"
#include "GameClient/GameText.h"
#include "GameLogic/GameLogic.h"
#include "GameLogic/VictoryConditions.h"
#include "GameNetwork/GameInfo.h"
#include "GameNetwork/GameSpyOverlay.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#if defined(__ANDROID__) || defined(__linux__)
#include <malloc.h>
#endif

#ifdef DISABLE_GAMEMEMORY
// Counted by the engine's global new (GameMemoryNull.cpp).
extern std::atomic<unsigned long long> g_gxAllocCount;
extern std::atomic<unsigned long long> g_gxAllocBytes;
#endif

namespace
{
	const Int kWarnFps = 50;
	const Int kKickFps = 45;
	const Int kKickSeconds = 30;
	const Int kRestrictAfter = 3;
	const Int kForgiveAfter = 3;
	const Int kSmallLobby = 4;
	const double kGraceSeconds = 15.0;
	const double kPauseFrameMs = 1000.0;
	const double kReportSeconds = 5.0;
	// A clean match must be a real one: at least two humans and this long (surrendering at once to
	// an AI in a LAN sandbox must not wipe the violations).
	const double kCleanMinSeconds = 180.0;
	const char *const kViolationsKey = "GX_FPS_Violations";
	const char *const kCleanKey = "GX_FPS_CleanMatches";

	// GeneralsX @feature Flux Build 06/11/2026 Thermal stability tier (owner's rule: unstable devices
	// keep to 4-player lobbies; PC cross-play is off and untouched). A counted second of a
	// multiplayer match is unstable when the phone is at kHotC or more AND below 90% of its frame
	// target, or when it held kSpikesPerSecond frames longer than kSpikeMs (visible hitches). A
	// match with kUnstableShare of its counted seconds unstable is an unstable match; kThermalStrikes
	// of them in a row restrict lobbies, and one smooth match (same minimum length as a clean one)
	// lifts it. Counted in Options.ini, GX_ThermalStrikes.
	const Int kHotC = 85;
	const double kSpikeMs = 50.0;
	const Int kSpikesPerSecond = 3;
	const double kUnstableShare = 0.25;
	const Int kThermalStrikes = 2;
	const Int kMinThermalSeconds = 60;
	const char *const kThermalKey = "GX_ThermalStrikes";

	typedef std::chrono::steady_clock Clock;

	struct State
	{
		Bool inMatch = FALSE;          // a real match (not the shell, not a replay)
		Bool multiplayer = FALSE;
		Int players = 0;               // players (not observers) at the start
		Bool sandbox = TRUE;
		Bool decided = FALSE;          // victory or defeat was reached
		Bool kicked = FALSE;
		Clock::time_point matchStart;
		Clock::time_point lastFrame;
		Bool haveLastFrame = FALSE;
		// one-second fps window
		Clock::time_point windowStart;
		Int windowFrames = 0;
		Bool windowPaused = FALSE;
		Bool warning = FALSE;
		Int lowSeconds = 0;
		// measurement report
		Clock::time_point reportStart;
		Int reportFrames = 0;
		double clientMs = 0.0, logicMs = 0.0;
		unsigned long long allocCount0 = 0, allocBytes0 = 0;
	};
	State s;
	// Per match, for the thermal tier.
	Int s_thermalCounted = 0;
	Int s_thermalUnstable = 0;
	Int s_windowSpikes = 0;
	Int s_hottestSeen = -1;
	double s_peakFps = 0.0;

	Bool realMatch()
	{
		return TheGameLogic != nullptr && TheGameLogic->isInGame() && !TheGameLogic->isInShellGame()
			&& !(TheRecorder != nullptr && TheRecorder->isPlaybackMode());
	}

	Int readCounter(const char *key)
	{
		OptionPreferences prefs;
		return prefs.getInt(AsciiString(key), 0);
	}

	void writeCounters(Int violations, Int clean)
	{
		OptionPreferences prefs;
		prefs.setInt(AsciiString(kViolationsKey), violations);
		prefs.setInt(AsciiString(kCleanKey), clean);
		prefs.write();
	}

	void startMatch(Clock::time_point now)
	{
		s.inMatch = TRUE;
		s.multiplayer = TheGameLogic->isInMultiplayerGame();
		// Players, not observers; a sandbox (everyone on one team, usually one human and AIs) is not a
		// match against anyone and never counts as clean.
		s.players = TheGameInfo != nullptr ? TheGameInfo->getNumNonObserverPlayers() : 0;
		s.sandbox = TheGameInfo == nullptr || TheGameInfo->isSandbox();
		s.decided = FALSE;
		s.kicked = FALSE;
		s.matchStart = now;
		s.windowStart = now;
		s.windowFrames = 0;
		s.windowPaused = FALSE;
		s.warning = FALSE;
		s.lowSeconds = 0;
		s.reportStart = now;
		s.reportFrames = 0;
		s.clientMs = s.logicMs = 0.0;
		s_thermalCounted = s_thermalUnstable = s_windowSpikes = 0;
		s_hottestSeen = -1;
		s_peakFps = 0.0;
	}

	void writeThermalStrikes(Int strikes)
	{
		OptionPreferences prefs;
		prefs.setInt(AsciiString(kThermalKey), strikes);
		prefs.write();
	}

	// End of a multiplayer match: unstable or smooth, if it ran long enough to tell.
	void judgeThermal(double played)
	{
		// A kicked match is the Performance Guard's verdict, not this one's: its counting stopped at
		// the kick, so what was counted would read as smooth and wipe the strikes.
		if (!s.multiplayer || s.kicked || played < kCleanMinSeconds || s_thermalCounted < kMinThermalSeconds)
			return;
		const double share = (double)s_thermalUnstable / (double)s_thermalCounted;
		const Int before = readCounter(kThermalKey);
		const Int strikes = share >= kUnstableShare ? (before < kThermalStrikes ? before + 1 : kThermalStrikes) : 0;
		fprintf(stderr, "[GX-THERMAL] match: %d of %d s unstable (%.0f%%), hottest %d C -> %s; strikes %d -> %d%s\n",
			s_thermalUnstable, s_thermalCounted, share * 100.0, s_hottestSeen,
			share >= kUnstableShare ? "unstable" : "smooth", before, strikes,
			strikes >= kThermalStrikes ? " -- lobbies limited to 4 players" : "");
		if (strikes != before)
			writeThermalStrikes(strikes);
	}

	void endMatch()
	{
		s.inMatch = FALSE;
		s.warning = FALSE;
		const double played = std::chrono::duration<double>(Clock::now() - s.matchStart).count();
		judgeThermal(played);
		if (!s.multiplayer || s.kicked || !s.decided || s.sandbox || s.players < 2 || s.players > kSmallLobby
			|| played < kCleanMinSeconds)
			return;
		Int violations = readCounter(kViolationsKey);
		if (violations <= 0)
			return;
		Int clean = readCounter(kCleanKey) + 1;
		if (clean >= kForgiveAfter)
		{
			fprintf(stderr, "[GX-PERFGUARD] %d clean matches for at most %d players: violations reset (were %d)\n",
				clean, kSmallLobby, violations);
			violations = 0;
			clean = 0;
		}
		else
		{
			fprintf(stderr, "[GX-PERFGUARD] clean match %d of %d towards resetting %d violation(s)\n", clean, kForgiveAfter, violations);
		}
		writeCounters(violations, clean);
	}

	void kick(double fps)
	{
		s.kicked = TRUE;
		const Int violations = readCounter(kViolationsKey) + 1;
		writeCounters(violations, 0);
		fprintf(stderr, "[GX-PERFGUARD] %d s at %d fps or below (last second %.0f fps): leaving the match. Violations now %d%s\n",
			kKickSeconds, kKickFps, fps, violations, violations >= kRestrictAfter ? " -- lobbies limited to 4 players" : "");
		fflush(stderr);
		TheMessageStream->appendMessage(GameMessage::MSG_CLEAR_GAME_DATA);
	}

	void report(Clock::time_point now)
	{
		const double seconds = std::chrono::duration<double>(now - s.reportStart).count();
		if (seconds < kReportSeconds || s.reportFrames <= 0)
			return;
		const double frames = (double)s.reportFrames;
		double allocs = 0.0, allocKB = 0.0;
#ifdef DISABLE_GAMEMEMORY
		const unsigned long long count = g_gxAllocCount.load(std::memory_order_relaxed);
		const unsigned long long bytes = g_gxAllocBytes.load(std::memory_order_relaxed);
		allocs = (double)(count - s.allocCount0) / frames;
		allocKB = (double)(bytes - s.allocBytes0) / 1024.0 / frames;
		s.allocCount0 = count;
		s.allocBytes0 = bytes;
#endif
		double heapMB = -1.0;
#if defined(__ANDROID__) || defined(__linux__)
		heapMB = (double)mallinfo().uordblks / (1024.0 * 1024.0);
#endif
		fprintf(stderr, "[GX-PERF] game thread ms/frame: client=%.2f logic=%.2f | fps=%.1f | allocs/frame=%.0f (%.1f KB) heap=%.0f MB"
			" | guard: mp=%d players=%d low<=%d=%ds warn=%d\n",
			s.clientMs / frames, s.logicMs / frames, frames / seconds, allocs, allocKB, heapMB,
			s.multiplayer ? 1 : 0, s.players, kKickFps, s.lowSeconds, s.warning ? 1 : 0);
		s.reportStart = now;
		s.reportFrames = 0;
		s.clientMs = s.logicMs = 0.0;
	}
}

namespace GXPerfGuard
{
	void noteClientMs(double ms) { s.clientMs += ms; }
	void noteLogicMs(double ms) { s.logicMs += ms; }
	Bool warningActive() { return s.inMatch && s.warning; }

	void onFrame()
	{
		const Clock::time_point now = Clock::now();
		const Bool match = realMatch();
		if (match && !s.inMatch)
			startMatch(now);
		else if (!match && s.inMatch)
			endMatch();
		// Multiplayer only: in a campaign the victory conditions have no local slot (index -1).
		if (s.inMatch && s.multiplayer && TheVictoryConditions != nullptr
			&& (TheVictoryConditions->isLocalAlliedVictory() || TheVictoryConditions->isLocalDefeat()))
			s.decided = TRUE;

		const double frameMs = s.haveLastFrame ? std::chrono::duration<double, std::milli>(now - s.lastFrame).count() : 0.0;
		s.lastFrame = now;
		s.haveLastFrame = TRUE;
		if (!s.inMatch)
			return;

		s.reportFrames++;
		report(now);

		// A frame over a second long: the app was paused or in the background. That second does
		// not count, and the low-fps streak starts again.
		if (frameMs > kPauseFrameMs)
		{
			s.windowPaused = TRUE;
			s.lowSeconds = 0;
		}
		s.windowFrames++;
		if (frameMs > kSpikeMs && frameMs <= kPauseFrameMs)
			s_windowSpikes++;
		const double windowSeconds = std::chrono::duration<double>(now - s.windowStart).count();
		if (windowSeconds < 1.0)
			return;
		const double fps = s.windowFrames / windowSeconds;
		const Bool counts = !s.windowPaused && std::chrono::duration<double>(now - s.matchStart).count() >= kGraceSeconds;
		s.windowStart = now;
		s.windowFrames = 0;
		s.windowPaused = FALSE;
		const Int spikes = s_windowSpikes;
		s_windowSpikes = 0;
		if (!counts || s.kicked)
			return;

		// The thresholds are for a 60 fps game. Where the game is limited lower on purpose -- the
		// 30 Hz engine offline, a lower Game Speed in skirmish, a lower online limit -- running at
		// that limit is normal, so the thresholds scale with it (50 and 45 of 60).
		double warnFps = kWarnFps, kickFps = kKickFps;
		if (TheFramePacer != nullptr && TheFramePacer->isFramesPerSecondLimitEnabled())
		{
			const Int limit = TheFramePacer->getActualFramesPerSecondLimit();
			if (limit > 0 && limit < 60)
			{
				warnFps = kWarnFps * limit / 60.0;
				kickFps = kKickFps * limit / 60.0;
			}
		}
		// The thermal tier's second (multiplayer only; the warning and kick below are unchanged).
		// Not once the match is decided (end screen, watching on).
		if (s.multiplayer && !s.decided)
		{
			const Int tempC = GXDeviceHottestTempC();
			if (tempC > s_hottestSeen)
				s_hottestSeen = tempC;
			// The frame target: the limit in force, or -- without one -- the best this match has run,
			// so a 30 Hz engine running 30 fps is not "slow" against an assumed 60.
			if (fps > s_peakFps)
				s_peakFps = fps;
			const double target = (TheFramePacer != nullptr && TheFramePacer->isFramesPerSecondLimitEnabled()
				&& TheFramePacer->getActualFramesPerSecondLimit() > 0) ? (double)TheFramePacer->getActualFramesPerSecondLimit() : s_peakFps;
			const Bool hotAndSlow = tempC >= kHotC && fps < target * 0.9;
			s_thermalCounted++;
			if (hotAndSlow || spikes >= kSpikesPerSecond)
				s_thermalUnstable++;
		}
		s.warning = fps <= warnFps;
		s.lowSeconds = fps <= kickFps ? s.lowSeconds + 1 : 0;
		// Not once the match is decided: watching the end screen of a won or lost game is no reason
		// to be kicked.
		if (s.multiplayer && !s.decided && s.lowSeconds >= kKickSeconds)
			kick(fps);
	}

	Bool lobbyBlocked(Int maxPlayers)
	{
		if (maxPlayers <= kSmallLobby)
			return FALSE;
		if (readCounter(kViolationsKey) >= kRestrictAfter)
		{
			fprintf(stderr, "[GX-PERFGUARD] lobby for %d players refused: %d violation(s)\n", maxPlayers, readCounter(kViolationsKey));
			GSMessageBoxOk(TheGameText->fetch("GX:PerfGuardTitle"), TheGameText->fetch("GX:PerfGuardRestricted"));
			return TRUE;
		}
		if (readCounter(kThermalKey) >= kThermalStrikes)
		{
			fprintf(stderr, "[GX-THERMAL] lobby for %d players refused: thermal tier unstable (%d strikes)\n", maxPlayers, readCounter(kThermalKey));
			GSMessageBoxOk(TheGameText->fetch("GX:PerfGuardTitle"), TheGameText->fetch("GX:ThermalRestricted"));
			return TRUE;
		}
		return FALSE;
	}
}
