// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: the app's tools (EA's CWorldBuilderApp keeps one
// instance of each tool and a "current" one, set by the tool bar; compat/WorldBuilder.h). The tool
// classes are EA's, compiled unchanged; this file only creates them, switches between them and
// applies the option sheets' values through the tools' own static setters, as EA's panels do.

#include "StdAfx.h"

#include "FluxWBTools.h"

#include "BrushTool.h"
#include "CUndoable.h"
#include "FeatherTool.h"
#include "MoundTool.h"
#include "ObjectOptions.h"
#include "ObjectTool.h"
#include "PointerTool.h"
#include "PolygonTool.h"
#include "WaypointOptions.h"
#include "WaypointTool.h"
#include "WorldBuilder.h"
#include "WorldBuilderDoc.h"
#include "wbview3d.h"

#include "Common/WellKnownKeys.h"
#include "GameLogic/SidesList.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace {

CWorldBuilderApp s_app;

struct Tools {
	PointerTool pointer;
	MoundTool raise;
	DigTool lower;
	FeatherTool smooth;
	BrushTool height;
	ObjectTool object;
	WaypointTool waypoint;
	PolygonTool area;
};
Tools *s_tools = nullptr;

Tool *toolNamed(const std::string &name)
{
	if (s_tools == nullptr) return nullptr;
	if (name == "select") return &s_tools->pointer;
	if (name == "raise") return &s_tools->raise;
	if (name == "lower") return &s_tools->lower;
	if (name == "smooth") return &s_tools->smooth;
	if (name == "height") return &s_tools->height;
	if (name == "object") return &s_tools->object;
	if (name == "waypoint") return &s_tools->waypoint;
	if (name == "area") return &s_tools->area;
	return nullptr;
}

} // namespace

CWorldBuilderApp *WbApp()
{
	return &s_app;
}

/** EA's CWorldBuilderApp::setActiveTool. */
void CWorldBuilderApp::setActiveTool(Tool *newTool)
{
	if (m_curTool == newTool) {
		return;
	}
	if (m_curTool) {
		m_curTool->deactivate();
	}
	m_curTool = newTool;
	if (m_curTool) {
		m_curTool->activate();
	}
}

void CWorldBuilderApp::selectPointerTool()
{
	if (s_tools) {
		setActiveTool(&s_tools->pointer);
	}
}

namespace FluxWB {

void createTools()
{
	if (s_tools) return;
	s_tools = new Tools;
	// Starting values (the panels' sliders in EA start from the registry; these are EA's mid values).
	MoundTool::setMoundHeight(2);
	MoundTool::setWidth(5);
	MoundTool::setFeather(3);
	BrushTool::setWidth(5);
	BrushTool::setFeather(2);
	BrushTool::setHeight(16);
	FeatherTool::setFeather(5);
	FeatherTool::setRate(3);
	FeatherTool::setRadius(1);
	ObjectOptions::buildList();
	s_app.selectPointerTool();
}

void destroyTools()
{
	if (!s_tools) return;
	s_app.setActiveTool(nullptr);
	delete s_tools;
	s_tools = nullptr;
	ObjectOptions::freeList();
}

std::string currentToolName()
{
	Tool *t = s_app.getCurTool();
	if (s_tools == nullptr || t == nullptr) return "";
	const char *names[] = { "select", "raise", "lower", "smooth", "height", "object", "waypoint", "area" };
	for (const char *n : names) {
		if (toolNamed(n) == t) return n;
	}
	return "";
}

bool selectTool(const std::string &name)
{
	Tool *t = toolNamed(name);
	if (t == nullptr) return false;
	s_app.setActiveTool(t);
	// EA turns the polygon display on while the polygon tool is active (PolygonTool::activate).
	return true;
}

/** "key value" pairs from an option sheet, applied through the tools' setters. */
void setOption(const std::string &arg)
{
	char key[32] = { 0 };
	char value[160] = { 0 };
	if (sscanf(arg.c_str(), "%31s %159[^\n]", key, value) < 1) return;
	const int v = atoi(value);
	const std::string k = key;
	if (k == "mound.height") MoundTool::setMoundHeight(v);
	else if (k == "mound.width") MoundTool::setWidth(v);
	else if (k == "mound.feather") MoundTool::setFeather(v);
	else if (k == "brush.height") BrushTool::setHeight(v);
	else if (k == "brush.width") BrushTool::setWidth(v);
	else if (k == "brush.feather") BrushTool::setFeather(v);
	else if (k == "smooth.feather") FeatherTool::setFeather(v);
	else if (k == "smooth.rate") FeatherTool::setRate(v);
	else if (k == "smooth.radius") FeatherTool::setRadius(v);
	else if (k == "snap") WbView::setSnapToGrid(v != 0);
	else if (k == "multiselect") g_fluxwbAddToSelection = (v != 0);
	else if (k == "object") ObjectOptions::setCurrent(AsciiString(value));
	else if (k == "owner") ObjectOptions::setOwner(AsciiString(value));
	else if (k == "waypoint.name") WaypointOptions::setSelectedName(AsciiString(value));
}

void undo()
{
	CWorldBuilderDoc *doc = CWorldBuilderDoc::GetActiveDoc();
	if (doc) {
		PointerTool::clearSelection();
		doc->OnEditUndo();
		doc->updateAllViews();
	}
}

void redo()
{
	CWorldBuilderDoc *doc = CWorldBuilderDoc::GetActiveDoc();
	if (doc) {
		PointerTool::clearSelection();
		doc->OnEditRedo();
		doc->updateAllViews();
	}
}

std::string objectList()
{
	return ObjectOptions::listForUi();
}

std::string teamList()
{
	std::string out;
	for (int i = 0; i < TheSidesList->getNumTeams(); i++) {
		AsciiString name = TheSidesList->getTeamInfo(i)->getDict()->getAsciiString(TheKey_teamName);
		out += name.str();
		out += '\n';
	}
	return out;
}

} // namespace FluxWB
