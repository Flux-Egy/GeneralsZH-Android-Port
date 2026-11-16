// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the MFC stand-ins of compat/StdAfx.h and
// compat/LayersList.h.

#include "StdAfx.h"

#include "FluxWBCore.h"
#include "LayersList.h"
#include "resource.h"

#include <cstdio>

LayersList *TheLayersList = nullptr;
bool g_fluxwbAddToSelection = false;

// EA's values (mapobjectprops.cpp).
const char* NEUTRAL_TEAM_UI_STR = "(neutral)";
const char* NEUTRAL_TEAM_INTERNAL_STR = "team";

namespace {
struct StringEntry {
	UINT id;
	const char *text;
};
// The entries of WorldBuilder.rc's STRINGTABLE that the compiled EA files use, word for word.
const StringEntry kStrings[] = {
	{ IDS_OUT_OF_MEMORY, "Out of memory." },
	{ IDS_BAD_VALUE, "A value was out of range. Sorry." },
	{ IDS_CONFIRM_REPLACE_TEXTURE, "Replace all occurences of texture %s?" },
	{ IDS_NEED_TO_FIX_TEAMS, "One or more objects had invalid teams.  Please run Validation>Fix Teams to correct this." },
};
CWnd s_mainWnd;
CWinApp s_app;
} // namespace

const char *FluxWB_LoadString(UINT id)
{
	for (const StringEntry &e : kStrings) {
		if (e.id == id) {
			return e.text;
		}
	}
	static thread_local char unknown[48];
	snprintf(unknown, sizeof(unknown), "World Builder message %u", id);
	return unknown;
}

CWinApp *AfxGetApp()
{
	return &s_app;
}

CWnd *AfxGetMainWnd()
{
	return &s_mainWnd;
}

int AfxMessageBox(const char *text, UINT type, UINT helpId)
{
	(void)helpId;
	const UINT buttons = type & 0x0F;
	const bool question = buttons != MB_OK;
	fprintf(stderr, "[fluxwb] message%s: %s\n", question ? " (answered no)" : "", text ? text : "");
	FluxWB::reportMessage(text ? text : "");
	switch (buttons) {
		case MB_OK: return IDOK;
		case MB_YESNO: return IDNO;
		default: return IDCANCEL;
	}
}

int AfxMessageBox(UINT id, UINT type, UINT helpId)
{
	return AfxMessageBox(FluxWB_LoadString(id), type, helpId);
}
