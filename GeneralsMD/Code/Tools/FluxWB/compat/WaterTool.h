// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's WaterTool header (the water tool
// comes later). Includes what EA's does (PolygonTool.h, and with it Tool.h) plus the editor sort types
// DrawObject.cpp needs; WaterTool::isActive() is false.
#pragma once
#include "Common/ThingSort.h"
#include "PolygonTool.h"
class WaterTool
{
public:
	static Bool isActive() { return false; }
};
