// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the globals the engine libraries expect the
// program to define. The game defines them in Core/Main/SDL3Main.cpp; the World Builder defines its
// own here (EA's WorldBuilder.cpp does the same on Windows).

#include "StdAfx.h"

#include "Common/CriticalSection.h"
#include "GameLogic/PolygonTrigger.h"
#include "W3DDevice/GameClient/HeightMap.h"
#include "WHeightMapEdit.h"
#include "WorldBuilderDoc.h"

#include <cmath>

struct SDL_Window;

int __argc = 0;
char **__argv = nullptr;
HWND ApplicationHWnd = nullptr;
SDL_Window *TheSDL3Window = nullptr;
const Char *g_csfFile = "data/%s/generals.csf";
const Char *g_strFile = "data/%s/generals.str";
const char *gAppPrefix = "wb_"; /// So WB can have a different debug log file name (EA).

static CriticalSection critSec1;
static CriticalSection critSec2;
static CriticalSection critSec3;
static CriticalSection critSec4;
static CriticalSection critSec5;

void FluxWB_InitGlobals()
{
	TheAsciiStringCriticalSection = &critSec1;
	TheUnicodeStringCriticalSection = &critSec2;
	TheDmaCriticalSection = &critSec3;
	TheMemoryPoolCriticalSection = &critSec4;
	TheDebugLogCriticalSection = &critSec5;
}

// EA defines this in GroveTool.cpp (not compiled yet); MapPreview.cpp uses it to colour water in the
// .tga preview. EA's body, with one Flux addition: without a 3D view (the round-trip tool) the
// height is read from the open map's height cells instead of the terrain render object.
Bool localIsUnderwater(Real x, Real y)
{
	ICoord3D iLoc;
	iLoc.x = (floor(x + 0.5f));
	iLoc.y = (floor(y + 0.5f));
	iLoc.z = 0;
	for (PolygonTrigger *pTrig = PolygonTrigger::getFirstPolygonTrigger(); pTrig; pTrig = pTrig->getNext()) {
		if (!pTrig->isWaterArea()) {
			continue;
		}
		if (pTrig->pointInTrigger(iLoc)) {
			Real wZ = pTrig->getPoint(0)->z;
			Real curHeight = 0;
			if (TheTerrainRenderObject != nullptr) {
				curHeight = TheTerrainRenderObject->getHeightMapHeight(x, y, nullptr);
			} else {
				CWorldBuilderDoc *doc = CWorldBuilderDoc::GetActiveDoc();
				WorldHeightMapEdit *map = doc ? doc->GetHeightMap() : nullptr;
				if (map != nullptr) {
					const Int cx = (Int)floor(x / MAP_XY_FACTOR) + map->getBorderSize();
					const Int cy = (Int)floor(y / MAP_XY_FACTOR) + map->getBorderSize();
					curHeight = map->getHeight(cx, cy) * MAP_HEIGHT_SCALE;
				}
			}
			return (curHeight < wZ);
		}
	}
	return false;
}

// The game's LinuxStubs.cpp defines these; the editor has no busy cursor and logs warnings.
void OSDisplaySetBusyState(bool is_busy, bool force_update)
{
	(void)is_busy;
	(void)force_update;
}

void OSDisplayWarningBox(AsciiString title, AsciiString message, unsigned int icon_type, unsigned int button_type)
{
	(void)icon_type;
	(void)button_type;
	fprintf(stderr, "[fluxwb] warning: %s: %s\n", title.str() ? title.str() : "", message.str() ? message.str() : "");
}
