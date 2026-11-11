// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA's CMainFrame (the MFC frame
// window). EA's tools ask it to show their options panel and to note camera changes; the native
// options panels live in the Java UI, which the tool announces itself to (FluxWBMain.cpp), so these
// two calls have nothing left to do here.
#pragma once

// EA's MainFrm.h includes every options panel, and the tools rely on that; so does this one.
#include "brushoptions.h"
#include "FeatherOptions.h"
#include "TerrainMaterial.h"
#include "MoundOptions.h"
#include "ObjectOptions.h"
#include "RoadOptions.h"
#include "MeshMoldOptions.h"
#include "WaypointOptions.h"
#include "WaterOptions.h"
#include "LightOptions.h"
#include "mapobjectprops.h"
#include "BuildList.h"
#include "WaterTool.h"

class CMainFrame
{
public:
	static CMainFrame *GetMainFrame()
	{
		static CMainFrame frame;
		return &frame;
	}
	void showOptionsDialog(Int dialogID) { (void)dialogID; }
	void handleCameraChange() {}
};
