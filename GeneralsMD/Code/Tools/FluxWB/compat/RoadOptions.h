// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's RoadOptions panel (roads come later).
#pragma once
#include "Lib/BaseType.h"
class RoadOptions
{
public:
	static Bool selectionIsRoadsOnly() { return false; }
	static void updateSelection() {}
};
