// GeneralsX @feature Flux Build 05/11/2026 Performance Guard and measurement report.
//
// Guard (owner's rules), measured on the game's own frame rate in whole seconds:
//   - warning: a second at 50 fps or below shows a warning on screen (any real match);
//   - kick:    30 seconds in a row at 45 fps or below in a multiplayer match leaves the match the way
//              the earlier freeze gate does (MSG_CLEAR_GAME_DATA), and counts a violation;
//   - lobbies: with 3 violations or more, GeneralsOnline lobbies for more than 4 players can be
//              neither joined nor hosted;
//   - forgive: 3 multiplayer matches for at most 4 players played to the end (victory or defeat)
//              without a kick reset the violations.
//   - thermal: (06/11/2026) a multiplayer match where a quarter of the seconds were hot (85 C+) and
//              below 90% of the frame target, or had 3+ hitches over 50 ms, is unstable; two in a
//              row limit lobbies to 4 players the same way, one smooth match lifts it
//              (GX_ThermalStrikes, [GX-THERMAL] in the log). Temperature: GXDeviceStats.h.
// Counters live in Options.ini (GX_FPS_Violations, GX_FPS_CleanMatches). The first 15 seconds of a
// match (loading, first shader builds) and any second with a frame over a second long (the app was
// in the background, a call came in) never count. Replays and skirmish are never kicked.
//
// The guard is a local client decision, like quitting: nothing in it reaches game logic or the
// checksum. Every 5 seconds in a match it also logs the measurement-build line [GX-PERF]: game
// thread time split, allocations per frame, heap in use, and the guard's state.
#pragma once

#include "Lib/BaseType.h"

namespace GXPerfGuard
{
	/// Once per engine frame, at the end of GameEngine::update.
	void onFrame();
	/// Time spent this frame in TheGameClient->UPDATE and TheGameLogic->UPDATE (milliseconds).
	void noteClientMs(double ms);
	void noteLogicMs(double ms);
	/// Whether the low-fps warning is up.
	Bool warningActive();
	/// True (after telling the player why) when a lobby for maxPlayers may not be joined or hosted.
	Bool lobbyBlocked(Int maxPlayers);
}
