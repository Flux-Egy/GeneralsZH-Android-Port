// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: stands in for EA's WorldBuilder.h (the MFC app).
// Keeps what the compiled EA files use: MAX_OBJECTS_IN_MAP and the app's current-tool handling
// (WbApp()->getCurTool() and friends), which FluxWBTools.cpp implements for the native tool bar.
#pragma once

#include "Lib/BaseType.h"

class Tool;

enum {MAX_OBJECTS_IN_MAP = 3000};

class CWorldBuilderApp
{
public:
	Tool *getCurTool() { return m_curTool; }
	void setActiveTool(Tool *newTool);
	void updateCurTool(Bool forceHand) { (void)forceHand; }
	void lockCurTool() { m_lockCurTool++; }
	void unlockCurTool() { if (m_lockCurTool > 0) m_lockCurTool--; }
	Bool isCurToolLocked() { return m_lockCurTool > 0; }
	void selectPointerTool();

private:
	Tool *m_curTool = nullptr;
	Int m_lockCurTool = 0;
};

CWorldBuilderApp *WbApp();
