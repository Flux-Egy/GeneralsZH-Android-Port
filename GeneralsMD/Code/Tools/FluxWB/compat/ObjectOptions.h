// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: EA's ObjectOptions panel without the dialog. The
// object list (one MapObject per ThingTemplate, as OnInitDialog builds it), the current object and owner,
// and the placement helpers are EA's (FluxWBPanels.cpp); the tree view is the Java object sheet.
#pragma once
#include "Common/AsciiString.h"
#include "Common/GameType.h"
#include "Lib/BaseType.h"
#include <string>
class MapObject;
class ObjectOptions
{
public:
	enum {NAME_MAX_LEN = 64};
	static MapObject *getCurMapObject();
	static const char *getCurObjectName() { return m_currentObjectName; }
	static MapObject *duplicateCurMapObjectForPlace(const Coord3D *loc, Real angle, Bool checkPlayers = true);
	static MapObject *getObjectNamed(AsciiString name);
	static Int getObjectNamedIndex(const AsciiString &name);
	static void selectObject(const MapObject *pObj);
	static Real getCurObjectHeight() { return 0; } // MAGIC_GROUND_Z: on the ground (EA's default)
	static void update();
	// Flux:
	static void buildList();
	static void freeList();
	static bool setCurrent(const AsciiString &name);
	static void setOwner(const AsciiString &teamName) { m_curOwnerName = teamName; }
	/** "name\tcategory" lines for the object sheet, by EA's editor sorting. */
	static std::string listForUi();
private:
	static MapObject *m_objectsList;
	static Int m_currentObjectIndex;
	static char m_currentObjectName[NAME_MAX_LEN];
	static AsciiString m_curOwnerName;
};
