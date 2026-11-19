// GeneralsX @bugfix Flux Build 06/11/2026 Time the app spent paused by the mobile lifecycle gate.
//
// While the app is in the background, SDL3GameEngine::update() returns before the engine update,
// so the first frame after coming back measures the whole pause as one frame. Code that reads a
// long frame as "the device froze" (the 20 s freeze gate in GameEngine.cpp) must not count that
// time: a 20 s phone call ended the player's match, skirmish included. The serial goes up once per
// skipped update; a reader that sees it change knows the gap it measured was a pause.
//
// A function-local static in an inline function is one object across every translation unit, so
// Core (SDL3GameEngine) and each game's GameEngine.cpp share it without a link-time symbol.
#pragma once

#include <atomic>

inline std::atomic<unsigned> &gxLifecyclePauseSerial()
{
	static std::atomic<unsigned> serial{0};
	return serial;
}
