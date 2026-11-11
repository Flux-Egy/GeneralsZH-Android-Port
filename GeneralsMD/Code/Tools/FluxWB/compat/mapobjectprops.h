// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA's MapObjectProps dialog
// header. WHeightMapEdit.cpp uses only the two neutral-team names (defined in FluxWBMfc.cpp, with
// EA's values from mapobjectprops.cpp); the native Properties panel (PLAN-032 phase 3) will declare
// what the tools need here.
#pragma once

extern const char* NEUTRAL_TEAM_UI_STR;
class MapObject;
/** EA's MapObjectProps statics the tools call (FluxWBPanels.cpp); the panel is the Java sheet. */
class MapObjectProps
{
public:
	static MapObject *getSingleSelectedMapObject();
	static void update();
};
extern const char* NEUTRAL_TEAM_INTERNAL_STR;
