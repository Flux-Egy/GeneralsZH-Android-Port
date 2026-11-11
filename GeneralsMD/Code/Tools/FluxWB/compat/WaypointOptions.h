// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: EA's WaypointOptions panel without the dialog
// (FluxWBPanels.cpp has EA's bodies); the Java waypoint sheet edits the name.
#pragma once
#include "Common/AsciiString.h"
#include "Lib/BaseType.h"
class MapObject;
class PolygonTrigger;
class WaypointOptions
{
public:
	static void update();
	static MapObject *getSingleSelectedWaypoint();
	static PolygonTrigger *getSingleSelectedPolygon();
	static Bool isUnique(AsciiString name, MapObject *theMapObj = nullptr);
	static AsciiString GenerateUniqueName(Int id);
	/** EA's name edit (OnEditchangeWaypointnameEdit): false and a message when the name is taken. */
	static bool setSelectedName(const AsciiString &name);
};
