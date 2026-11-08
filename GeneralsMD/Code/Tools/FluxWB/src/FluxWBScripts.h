// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: the mission editor (players, teams, scripts) --
// see FluxWBScripts.cpp.
#pragma once

#include <string>

namespace FluxWB {

bool scriptsActive();
/** Copies the sides list (EA: m_sides = *TheSidesList). */
void scriptsBegin();
/** Hands the copy to a SidesListUndoable (EA: OnOK): one undo step. */
bool scriptsCommit();
/** Drops the copy (EA: OnCancel). */
void scriptsCancel();
/** Lines: P player, S script, G folder, T team (tab-separated, see the .cpp). */
std::string scriptsTree();
std::string scriptDetail(int player, int group, int script);
std::string scriptTemplates(bool actions);
/** "value\tlabel" lines for a parameter type; empty: free entry. */
std::string scriptChoices(int parameterType);
std::string scriptFactions();
/** One edit; "" on success, else the reason. */
std::string scriptEdit(const std::string &command);

} // namespace FluxWB
