// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: start-up and shut-down of the engine subsystems
// the World Builder needs, in the order of CWorldBuilderApp::InitInstance (WorldBuilder.cpp), with
// the Android file systems in place of the Win32 ones.
#pragma once

#include <string>

namespace FluxWB {

struct StartOptions {
	/** The game folder (the engine reads Data\..., *.big relative to it). */
	std::string gameDir;
	/** User data: Maps\ and the chunk writer's temporary file. Exported as GENERALSX_USERDATA_DIR. */
	std::string userDataDir;
	/** No window, no 3D device (the round-trip tool). */
	bool headless = false;
};

/** Runs the start-up. False (and *error) when the game data cannot be read. */
bool startEngine(const StartOptions &options, std::string *error);
/** Shuts down what startEngine() started, in reverse (ExitInstance). */
void stopEngine();
bool engineStarted();

/** A message box from EA's code (compat/StdAfx.h). The 3D view forwards it to the Java UI. */
void reportMessage(const char *text);
/** Selection changes for the Java sheets (null = nothing of that kind selected). */
void reportSelection(const char *objectName);
void reportWaypoint(const char *waypointName);
void reportArea(const char *areaName);
using MessageSink = void (*)(const char *text);
void setMessageSink(MessageSink sink);
using EventSink = void (*)(const char *kind, const char *text);
void setEventSink(EventSink sink);

} // namespace FluxWB
