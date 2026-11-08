// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA's MeshMoldOptions panel.
// DrawObject.cpp reads the mesh-mold preview's model, scale and angle; without the mesh-mold tool the
// model name is empty and DrawObject draws no preview. EA's defaults otherwise.
#pragma once

#include "Common/AsciiString.h"

class MeshMoldOptions
{
public:
	static Real getScale() { return m_currentScale; }
	static Int getAngle() { return m_currentAngle; }
	static AsciiString getModelName() { return m_meshModelName; }

	static Real m_currentScale;
	static Int m_currentAngle;
	static AsciiString m_meshModelName;
};
