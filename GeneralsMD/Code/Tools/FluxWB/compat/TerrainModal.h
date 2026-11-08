// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA's TerrainModal dialog.
// WorldHeightMapEdit opens it when a map names a terrain texture this game's Terrain.ini does not
// have, and asks which texture to use instead. Here it always answers "cancel" -- EA's code then
// drops that texture's tiles -- and records the name, so FluxWBCore can tell the user and REFUSE to
// save the map: saving would write the map back without those tiles.
#pragma once

#include "Common/AsciiString.h"

class WorldHeightMapEdit;

/** Records a texture name the open map uses and this game lacks. */
void FluxWB_NoteMissingTexture(const AsciiString &name);

class TerrainModal
{
public:
	TerrainModal(AsciiString path, WorldHeightMapEdit *pMap) : m_path(path)
	{
		(void)pMap;
	}
	int DoModal()
	{
		FluxWB_NoteMissingTexture(m_path);
		return IDCANCEL;
	}
	Int getNewNdx() { return -1; }

private:
	AsciiString m_path;
};
