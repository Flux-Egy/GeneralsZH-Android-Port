// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's TerrainMaterial panel (texture
// painting comes later). Included by the waypoint and polygon tools, which use nothing from it.
#pragma once
class WorldHeightMapEdit;
class TerrainMaterial
{
public:
	static void updateTextures(WorldHeightMapEdit *pMap) { (void)pMap; }
};
