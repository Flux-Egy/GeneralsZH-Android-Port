// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA's BuildListTool header.
// DrawObject.cpp asks whether the build-list tool is active (it then draws the AI build lists);
// that tool is not in the first native release.
#pragma once

class BuildListTool
{
public:
	static Bool isActive() { return m_isActive; }
	static Bool m_isActive;
};
