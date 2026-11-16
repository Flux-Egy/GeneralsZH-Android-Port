// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: the editing tools (FluxWBTools.cpp).
#pragma once

#include <string>

namespace FluxWB {

/** EA's tool instances, created after the engine starts (they read INI data). */
void createTools();
void destroyTools();
/** select, raise, lower, smooth, height, object, waypoint, area. */
bool selectTool(const std::string &name);
std::string currentToolName();
/** "key value", e.g. "mound.width 7", "object AmericaTankCrusader", "snap 1". */
void setOption(const std::string &arg);
void undo();
void redo();
/** "name\tcategory\n" for every placeable object; "name\n" for every team. */
std::string objectList();
std::string teamList();

} // namespace FluxWB
