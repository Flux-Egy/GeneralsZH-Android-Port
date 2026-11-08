// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the link between WorldBuilderActivity (Java,
// the UI thread) and the editor (C++, SDL's thread). The Java tool bar posts commands; the SDL loop
// takes them one at a time between frames, so EA's code only ever runs on one thread. Results go
// back as events, which the activity shows on its UI thread.
//
// Commands (verb, argument):
//   open <path>              open a map (a real file, or "Maps\X\X.map" from the game's archives)
//   new <w> <h> <height> <border>
//   save <path>              save the open map with EA's writer
//   tool <camera|raise|lower|object|waypoint>
//   camera reset
// Events (kind, text): ready, opened, saved, error, message, tool.
#pragma once

#include <string>

namespace FluxWB {

struct Command {
	std::string verb;
	std::string arg;
};

/** From the JNI thread. */
void postCommand(const std::string &verb, const std::string &arg);
/** On the SDL thread; false when the queue is empty. */
bool popCommand(Command *out);
/** To the activity (any thread). Does nothing when the activity is gone. */
void postEvent(const char *kind, const std::string &text);

} // namespace FluxWB
