// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's MoundOptions panel (raise/lower).
#pragma once
#include "Lib/BaseType.h"
class MoundOptions
{
public:
	enum {MIN_MOUND_HEIGHT=1, MAX_MOUND_HEIGHT=21};
	enum {MIN_BRUSH_SIZE=1, MAX_BRUSH_SIZE=51, MIN_FEATHER=0, MAX_FEATHER=20};
	static void setWidth(Int width) { m_currentWidth = width; }
	static void setFeather(Int feather) { m_currentFeather = feather; }
	static void setHeight(Int height) { m_currentHeight = height; }
	static Int m_currentWidth;
	static Int m_currentFeather;
	static Int m_currentHeight;
};
