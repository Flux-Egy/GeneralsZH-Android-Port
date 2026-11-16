// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's FeatherOptions panel (smooth).
#pragma once
#include "Lib/BaseType.h"
class FeatherOptions
{
public:
	enum {MIN_FEATHER_SIZE=2, MAX_FEATHER_SIZE=51, MIN_RATE=1, MAX_RATE=10, MIN_RADIUS=1, MAX_RADIUS=5};
	static void setFeather(Int feather) { m_currentFeather = feather; }
	static void setRate(Int rate) { m_currentRate = rate; }
	static void setRadius(Int radius) { m_currentRadius = radius; }
	static Int m_currentFeather;
	static Int m_currentRadius;
	static Int m_currentRate;
};
