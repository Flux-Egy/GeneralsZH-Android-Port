// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's RoadTool. CUndoable snaps a moved
// road end to the nearest road; with no road tool yet, nothing snaps (the end stays where it is dropped).
#pragma once
#include "Lib/BaseType.h"
struct Coord3D;
class RoadTool
{
public:
	static Bool snap(Coord3D *pLoc, Bool skipLast) { (void)pLoc; (void)skipLast; return false; }
};
