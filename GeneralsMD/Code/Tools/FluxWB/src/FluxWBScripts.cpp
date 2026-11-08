// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: the mission editor -- players, teams, scripts.
//
// EA's ScriptDialog / PlayerListDlg / TeamsDialog work on a COPY of the sides list (m_sides =
// *TheSidesList); OK hands the copy to a SidesListUndoable, Cancel drops it. This file keeps that model
// exactly (begin / commit / cancel), so a whole editing session is one undo step and nothing reaches
// the map until the player confirms. The edits themselves are EA's: a new script is EA's (CONDITION_TRUE,
// NO_OP), conditions and actions are created from the script engine's templates, parameters keep the
// engine's types. The Java screen (WbScriptEditor) only shows the text this file produces and sends
// back indexes and values.
//
// Addressing: a script is "p g s" -- player index, group index (-1 = not in a group), script index.

#include "StdAfx.h"

#include "FluxWBScripts.h"

#include "CUndoable.h"
#include "WorldBuilderDoc.h"

#include "Common/MapObject.h"
#include "Common/PlayerTemplate.h"
#include "Common/ThingFactory.h"
#include "Common/ThingTemplate.h"
#include "Common/WellKnownKeys.h"
#include "GameLogic/PolygonTrigger.h"
#include "GameLogic/ScriptEngine.h"
#include "GameLogic/Scripts.h"
#include "GameLogic/SidesList.h"

#include <cctype>
#include <cstdio>
#include <cstdlib>
#include <set>
#include <sstream>
#include <string>
#include <vector>

namespace {

SidesList *s_sides = nullptr; // the session's copy (EA: ScriptDialog::m_sides)

std::string clean(const AsciiString &s)
{
	std::string out = s.str() ? s.str() : "";
	for (char &c : out) {
		if (c == '\t' || c == '\n' || c == '\r') c = ' ';
	}
	return out;
}

std::vector<std::string> split(const std::string &s, size_t maxParts)
{
	std::vector<std::string> out;
	std::istringstream in(s);
	std::string tok;
	while (out.size() + 1 < maxParts && in >> tok) {
		out.push_back(tok);
	}
	std::string rest;
	std::getline(in, rest);
	size_t i = rest.find_first_not_of(' ');
	if (i != std::string::npos) out.push_back(rest.substr(i));
	return out;
}

ScriptList *listOf(int p)
{
	if (!s_sides || p < 0 || p >= s_sides->getNumSides()) return nullptr;
	return s_sides->getSideInfo(p)->getScriptList();
}

ScriptGroup *groupAt(ScriptList *sl, int g)
{
	if (!sl || g < 0) return nullptr;
	ScriptGroup *grp = sl->getScriptGroup();
	for (int i = 0; grp && i < g; i++) grp = grp->getNext();
	return grp;
}

Script *scriptAt(int p, int g, int s)
{
	ScriptList *sl = listOf(p);
	if (!sl || s < 0) return nullptr;
	Script *scr = nullptr;
	if (g < 0) {
		scr = sl->getScript();
	} else {
		ScriptGroup *grp = groupAt(sl, g);
		scr = grp ? grp->getScript() : nullptr;
	}
	for (int i = 0; scr && i < s; i++) scr = scr->getNext();
	return scr;
}

OrCondition *orAt(Script *scr, int o)
{
	OrCondition *oc = scr ? scr->getOrCondition() : nullptr;
	for (int i = 0; oc && i < o; i++) oc = oc->getNextOrCondition();
	return oc;
}

Condition *condAt(Script *scr, int o, int a)
{
	OrCondition *oc = orAt(scr, o);
	Condition *c = oc ? oc->getFirstAndCondition() : nullptr;
	for (int i = 0; c && i < a; i++) c = c->getNext();
	return c;
}

ScriptAction *actionAt(Script *scr, bool onTrue, int a)
{
	ScriptAction *act = scr ? (onTrue ? scr->getAction() : scr->getFalseAction()) : nullptr;
	for (int i = 0; act && i < a; i++) act = act->getNext();
	return act;
}

std::string paramLine(const char *prefix, Parameter *prm, int idx)
{
	std::ostringstream o;
	o << prefix << '\t' << idx << '\t' << (int)prm->getParameterType() << '\t' << clean(prm->getUiText());
	return o.str();
}

/** Parameter of a condition or action addressed by "c o a k" or "t a k" / "f a k". */
Parameter *paramAt(Script *scr, const std::vector<std::string> &w, size_t at)
{
	if (w.size() <= at) return nullptr;
	if (w[at] == "c" && w.size() > at + 3) {
		Condition *c = condAt(scr, atoi(w[at + 1].c_str()), atoi(w[at + 2].c_str()));
		int k = atoi(w[at + 3].c_str());
		return (c && k >= 0 && k < c->getNumParameters()) ? c->getParameter(k) : nullptr;
	}
	if ((w[at] == "t" || w[at] == "f") && w.size() > at + 2) {
		ScriptAction *a = actionAt(scr, w[at] == "t", atoi(w[at + 1].c_str()));
		int k = atoi(w[at + 2].c_str());
		return (a && k >= 0 && k < a->getNumParameters()) ? a->getParameter(k) : nullptr;
	}
	return nullptr;
}

bool isIntType(Parameter::ParameterType t)
{
	switch (t) {
		case Parameter::INT: case Parameter::COMPARISON: case Parameter::BOOLEAN: case Parameter::RELATION:
		case Parameter::AI_MOOD: case Parameter::KIND_OF_PARAM: case Parameter::RADAR_EVENT_TYPE:
		case Parameter::COMMANDBUTTON_ABILITY: case Parameter::BOUNDARY: case Parameter::BUILDABLE:
		case Parameter::SURFACES_ALLOWED: case Parameter::SHAKE_INTENSITY: case Parameter::COLOR:
		case Parameter::LEFT_OR_RIGHT:
			return true;
		default:
			return false;
	}
}

bool isRealType(Parameter::ParameterType t)
{
	return t == Parameter::REAL || t == Parameter::ANGLE || t == Parameter::PERCENT;
}

void collectScriptNames(std::set<std::string> &out, bool subroutinesOnly)
{
	for (int p = 0; s_sides && p < s_sides->getNumSides(); p++) {
		ScriptList *sl = listOf(p);
		if (!sl) continue;
		for (Script *s = sl->getScript(); s; s = s->getNext()) {
			if (!subroutinesOnly || s->isSubroutine()) out.insert(clean(s->getName()));
		}
		for (ScriptGroup *g = sl->getScriptGroup(); g; g = g->getNext()) {
			if (subroutinesOnly && g->isSubroutine()) out.insert(clean(g->getName()));
			for (Script *s = g->getScript(); s; s = s->getNext()) {
				if (!subroutinesOnly || s->isSubroutine()) out.insert(clean(s->getName()));
			}
		}
	}
}

/** Names already used by parameters of one type (counters, flags) anywhere in the session. */
void collectUsedStrings(std::set<std::string> &out, Parameter::ParameterType type)
{
	auto take = [&](Parameter *prm) {
		if (prm && prm->getParameterType() == type && !prm->getString().isEmpty()) out.insert(clean(prm->getString()));
	};
	auto scan = [&](Script *s) {
		for (OrCondition *o = s->getOrCondition(); o; o = o->getNextOrCondition())
			for (Condition *c = o->getFirstAndCondition(); c; c = c->getNext())
				for (int k = 0; k < c->getNumParameters(); k++) take(c->getParameter(k));
		for (ScriptAction *a = s->getAction(); a; a = a->getNext())
			for (int k = 0; k < a->getNumParameters(); k++) take(a->getParameter(k));
		for (ScriptAction *a = s->getFalseAction(); a; a = a->getNext())
			for (int k = 0; k < a->getNumParameters(); k++) take(a->getParameter(k));
	};
	for (int p = 0; s_sides && p < s_sides->getNumSides(); p++) {
		ScriptList *sl = listOf(p);
		if (!sl) continue;
		for (Script *s = sl->getScript(); s; s = s->getNext()) scan(s);
		for (ScriptGroup *g = sl->getScriptGroup(); g; g = g->getNext())
			for (Script *s = g->getScript(); s; s = s->getNext()) scan(s);
	}
}

void insertScript(ScriptList *sl, int g, Script *scr)
{
	if (g < 0) {
		Int n = 0;
		for (Script *s = sl->getScript(); s; s = s->getNext()) n++;
		sl->addScript(scr, n);
	} else {
		ScriptGroup *grp = groupAt(sl, g);
		Int n = 0;
		for (Script *s = grp->getScript(); s; s = s->getNext()) n++;
		grp->addScript(scr, n);
	}
}

} // namespace

namespace FluxWB {

bool scriptsActive()
{
	return s_sides != nullptr;
}

void scriptsBegin()
{
	if (s_sides) return;
	ScriptList::updateDefaults();
	s_sides = new SidesList;
	*s_sides = *TheSidesList;
}

void scriptsCancel()
{
	delete s_sides;
	s_sides = nullptr;
}

bool scriptsCommit()
{
	if (!s_sides) return false;
	CWorldBuilderDoc *pDoc = CWorldBuilderDoc::GetActiveDoc();
	if (pDoc) {
		s_sides->validateSides();
		SidesListUndoable *pUndo = new SidesListUndoable(*s_sides, pDoc);
		pDoc->AddAndDoUndoable(pUndo);
		REF_PTR_RELEASE(pUndo); // belongs to pDoc now.
	}
	scriptsCancel();
	return pDoc != nullptr;
}

std::string scriptsTree()
{
	std::ostringstream o;
	if (!s_sides) return "";
	for (int p = 0; p < s_sides->getNumSides(); p++) {
		Dict *d = s_sides->getSideInfo(p)->getDict();
		o << "P\t" << p << '\t' << clean(d->getAsciiString(TheKey_playerName)) << '\t'
			<< clean(d->getAsciiString(TheKey_playerFaction)) << '\t' << (d->getBool(TheKey_playerIsHuman) ? 1 : 0) << '\n';
		ScriptList *sl = listOf(p);
		if (!sl) continue;
		int s = 0;
		for (Script *scr = sl->getScript(); scr; scr = scr->getNext(), s++) {
			o << "S\t" << p << "\t-1\t" << s << '\t' << clean(scr->getName()) << '\t' << (scr->isActive() ? 1 : 0)
				<< (scr->isSubroutine() ? 1 : 0) << '\n';
		}
		int g = 0;
		for (ScriptGroup *grp = sl->getScriptGroup(); grp; grp = grp->getNext(), g++) {
			o << "G\t" << p << '\t' << g << '\t' << clean(grp->getName()) << '\t' << (grp->isActive() ? 1 : 0)
				<< (grp->isSubroutine() ? 1 : 0) << '\n';
			s = 0;
			for (Script *scr = grp->getScript(); scr; scr = scr->getNext(), s++) {
				o << "S\t" << p << '\t' << g << '\t' << s << '\t' << clean(scr->getName()) << '\t'
					<< (scr->isActive() ? 1 : 0) << (scr->isSubroutine() ? 1 : 0) << '\n';
			}
		}
	}
	for (int t = 0; t < s_sides->getNumTeams(); t++) {
		Dict *d = s_sides->getTeamInfo(t)->getDict();
		o << "T\t" << t << '\t' << clean(d->getAsciiString(TheKey_teamName)) << '\t'
			<< clean(d->getAsciiString(TheKey_teamOwner)) << '\n';
	}
	return o.str();
}

std::string scriptDetail(int p, int g, int s)
{
	Script *scr = scriptAt(p, g, s);
	if (!scr) return "";
	std::ostringstream o;
	o << "N\t" << clean(scr->getName()) << '\n';
	o << "C\t" << clean(scr->getComment()) << '\n';
	o << "F\t" << (scr->isActive() ? 1 : 0) << (scr->isOneShot() ? 1 : 0) << (scr->isEasy() ? 1 : 0)
		<< (scr->isNormal() ? 1 : 0) << (scr->isHard() ? 1 : 0) << (scr->isSubroutine() ? 1 : 0) << '\t'
		<< scr->getDelayEvalSeconds() << '\n';
	int oi = 0;
	for (OrCondition *oc = scr->getOrCondition(); oc; oc = oc->getNextOrCondition(), oi++) {
		o << "O\t" << oi << '\n';
		int ai = 0;
		for (Condition *c = oc->getFirstAndCondition(); c; c = c->getNext(), ai++) {
			o << "c\t" << oi << '\t' << ai << '\t' << (int)c->getConditionType() << '\t' << clean(c->getUiText()) << '\n';
			for (int k = 0; k < c->getNumParameters(); k++) o << paramLine("cp", c->getParameter(k), k) << '\n';
		}
	}
	for (int pass = 0; pass < 2; pass++) {
		int ai = 0;
		for (ScriptAction *a = pass == 0 ? scr->getAction() : scr->getFalseAction(); a; a = a->getNext(), ai++) {
			o << (pass == 0 ? "t" : "f") << '\t' << ai << '\t' << (int)a->getActionType() << '\t' << clean(a->getUiText()) << '\n';
			for (int k = 0; k < a->getNumParameters(); k++) o << paramLine(pass == 0 ? "tp" : "fp", a->getParameter(k), k) << '\n';
		}
	}
	return o.str();
}

std::string scriptTemplates(bool actions)
{
	std::ostringstream o;
	const int n = actions ? (int)ScriptAction::NUM_ITEMS : (int)Condition::NUM_ITEMS;
	for (int i = 0; i < n; i++) {
		const Template *t = actions ? (const Template *)TheScriptEngine->getActionTemplate(i)
			: (const Template *)TheScriptEngine->getConditionTemplate(i);
		if (!t || t->getName().isEmpty()) continue;
		o << i << '\t' << clean(t->getName()) << '\n';
	}
	return o.str();
}

std::string scriptChoices(int typeInt)
{
	std::ostringstream o;
	std::set<std::string> names;
	const Parameter::ParameterType type = (Parameter::ParameterType)typeInt;
	switch (type) {
		case Parameter::TEAM:
			names.insert(THIS_TEAM);
			names.insert(ANY_TEAM);
			for (int t = 0; s_sides && t < s_sides->getNumTeams(); t++)
				names.insert(clean(s_sides->getTeamInfo(t)->getDict()->getAsciiString(TheKey_teamName)));
			break;
		case Parameter::SIDE:
			names.insert(THIS_PLAYER);
			names.insert(LOCAL_PLAYER);
			names.insert(THIS_PLAYER_ENEMY);
			for (int p = 0; s_sides && p < s_sides->getNumSides(); p++)
				names.insert(clean(s_sides->getSideInfo(p)->getDict()->getAsciiString(TheKey_playerName)));
			break;
		case Parameter::WAYPOINT:
			for (MapObject *m = MapObject::getFirstMapObject(); m; m = m->getNext())
				if (m->isWaypoint()) names.insert(clean(m->getWaypointName()));
			break;
		case Parameter::WAYPOINT_PATH:
			for (MapObject *m = MapObject::getFirstMapObject(); m; m = m->getNext()) {
				if (!m->isWaypoint()) continue;
				Bool exists;
				NameKeyType keys[] = { TheKey_waypointPathLabel1, TheKey_waypointPathLabel2, TheKey_waypointPathLabel3 };
				for (NameKeyType k : keys) {
					AsciiString l = m->getProperties()->getAsciiString(k, &exists);
					if (exists && !l.isEmpty()) names.insert(clean(l));
				}
			}
			break;
		case Parameter::TRIGGER_AREA:
			for (PolygonTrigger *t = PolygonTrigger::getFirstPolygonTrigger(); t; t = t->getNext())
				names.insert(clean(t->getTriggerName()));
			break;
		case Parameter::UNIT:
			names.insert(THIS_OBJECT);
			for (MapObject *m = MapObject::getFirstMapObject(); m; m = m->getNext()) {
				Bool exists;
				AsciiString n = m->getProperties()->getAsciiString(TheKey_objectName, &exists);
				if (exists && !n.isEmpty()) names.insert(clean(n));
			}
			break;
		case Parameter::OBJECT_TYPE:
			for (const ThingTemplate *tt = TheThingFactory->firstTemplate(); tt; tt = tt->friend_getNextTemplate())
				names.insert(clean(tt->getName()));
			break;
		case Parameter::SCRIPT:
			collectScriptNames(names, false);
			break;
		case Parameter::SCRIPT_SUBROUTINE:
			collectScriptNames(names, true);
			break;
		case Parameter::COUNTER:
		case Parameter::FLAG:
			collectUsedStrings(names, type);
			break;
		case Parameter::COMPARISON:
		case Parameter::BOOLEAN:
		case Parameter::RELATION: {
			// The values' own texts (Parameter::getUiText), as EA's combo boxes show them.
			std::vector<int> values;
			if (type == Parameter::COMPARISON) values = { Parameter::LESS_THAN, Parameter::LESS_EQUAL, Parameter::EQUAL,
				Parameter::GREATER_EQUAL, Parameter::GREATER, Parameter::NOT_EQUAL };
			else if (type == Parameter::BOOLEAN) values = { 1, 0 };
			else values = { Parameter::REL_ENEMY, Parameter::REL_NEUTRAL, Parameter::REL_FRIEND };
			for (int v : values) {
				Parameter *tmp = newInstance(Parameter)(type);
				tmp->friend_setInt(v);
				o << v << '\t' << clean(tmp->getUiText()) << '\n';
				deleteInstance(tmp);
			}
			return o.str();
		}
		default:
			return "";
	}
	for (const std::string &n : names) o << n << '\t' << n << '\n';
	return o.str();
}

std::string scriptFactions()
{
	std::ostringstream o;
	for (int i = 0; i < ThePlayerTemplateStore->getPlayerTemplateCount(); i++) {
		const PlayerTemplate *pt = ThePlayerTemplateStore->getNthPlayerTemplate(i);
		if (pt) o << clean(pt->getName()) << '\n';
	}
	return o.str();
}

/** One edit, e.g. "new 0 -1", "prop 0 -1 2 name Intro", "param 0 -1 2 t 0 1 s:Player_1_Start". */
std::string scriptEdit(const std::string &cmd)
{
	if (!s_sides) return "the mission editor is not open";
	// Values may contain spaces, so each edit is split into its exact number of words, the last one
	// taking the rest of the line.
	std::vector<std::string> head = split(cmd, 6);
	if (head.empty()) return "empty command";
	size_t parts = 12;
	if (head[0] == "param") parts = (head.size() > 4 && head[4] == "c") ? 9 : 8;
	else if (head[0] == "prop") parts = 6;
	else if (head[0] == "groupprop") parts = 5;
	else if (head[0] == "newgroup") parts = 3;
	std::vector<std::string> w = split(cmd, parts);
	const std::string &op = w[0];
	auto num = [&](size_t i) { return i < w.size() ? atoi(w[i].c_str()) : 0; };

	if (op == "addplayer" && w.size() >= 2) {
		// EA PlayerListDlg::OnNewplayer.
		if (s_sides->getNumSides() >= MAX_PLAYER_COUNT - 1) return "too many players";
		AsciiString pname;
		UnicodeString pnameu;
		Int n = 1;
		do {
			pname.format("player%04d", n);
			pnameu.format(L"Player %04d's Display Name", n);
			n++;
		} while (s_sides->findSideInfo(pname));
		Dict d;
		d.setAsciiString(TheKey_playerName, pname);
		d.setBool(TheKey_playerIsHuman, true);
		d.setUnicodeString(TheKey_playerDisplayName, pnameu);
		d.setAsciiString(TheKey_playerFaction, AsciiString(w[1].c_str()));
		d.setAsciiString(TheKey_playerEnemies, "");
		d.setAsciiString(TheKey_playerAllies, "");
		s_sides->addSide(&d);
		s_sides->validateSides();
		return "";
	}
	if (op == "removeplayer") {
		// EA PlayerListDlg::OnRemoveplayer: the player and every team it owns. Objects that use those
		// teams make EA ask first; here the command is refused with the count unless it says "force".
		int p = num(1);
		if (p < 0 || p >= s_sides->getNumSides()) return "no such player";
		AsciiString pname = s_sides->getSideInfo(p)->getDict()->getAsciiString(TheKey_playerName);
		if (pname.isEmpty()) return "the neutral player stays";
		Int count = 0;
		for (Int i = 0; i < s_sides->getNumTeams(); i++) {
			Dict *tdict = s_sides->getTeamInfo(i)->getDict();
			if (tdict->getAsciiString(TheKey_teamOwner) == pname)
				count += MapObject::countMapObjectsWithOwner(tdict->getAsciiString(TheKey_teamName));
		}
		if (count > 0 && !(w.size() >= 3 && w[2] == "force")) {
			return "inuse " + std::to_string(count);
		}
		if (s_sides->getNumSides() <= 1) return "the neutral player stays";
		s_sides->removeSide(p);
		for (Int i = 0; i < s_sides->getNumTeams();) {
			Dict *tdict = s_sides->getTeamInfo(i)->getDict();
			if (tdict->getAsciiString(TheKey_teamOwner) == pname) {
				s_sides->removeTeam(i);
				i = 0;
			} else {
				i++;
			}
		}
		s_sides->validateSides();
		return "";
	}
	if (op == "playerhuman") {
		int p = num(1);
		if (p < 0 || p >= s_sides->getNumSides()) return "no such player";
		s_sides->getSideInfo(p)->getDict()->setBool(TheKey_playerIsHuman, num(2) != 0);
		return "";
	}
	if (op == "addteam" && w.size() >= 3) {
		// EA TeamsDialog::OnNewteam: "team<owner>" naming is EA's for default teams; a new one is named here.
		if (s_sides->findTeamInfo(AsciiString(w[1].c_str()))) return "a team of that name exists";
		// "-" is the neutral player, whose name is empty.
		const AsciiString owner(w[2] == "-" ? "" : w[2].c_str());
		if (!s_sides->findSideInfo(owner)) return "no such player";
		Dict d;
		d.setAsciiString(TheKey_teamName, AsciiString(w[1].c_str()));
		d.setAsciiString(TheKey_teamOwner, owner);
		d.setBool(TheKey_teamIsSingleton, false);
		s_sides->addTeam(&d);
		s_sides->validateSides();
		return "";
	}
	if (op == "removeteam") {
		int t = num(1);
		if (t < 0 || t >= s_sides->getNumTeams()) return "no such team";
		AsciiString name = s_sides->getTeamInfo(t)->getDict()->getAsciiString(TheKey_teamName);
		AsciiString owner = s_sides->getTeamInfo(t)->getDict()->getAsciiString(TheKey_teamOwner);
		AsciiString def = "team";
		def.concat(owner);
		if (name == def) return "a player's default team stays";
		Int count = MapObject::countMapObjectsWithOwner(name);
		if (count > 0 && !(w.size() >= 3 && w[2] == "force")) {
			return "inuse " + std::to_string(count);
		}
		s_sides->removeTeam(t);
		s_sides->validateSides();
		return "";
	}

	ScriptList *sl = listOf(num(1));
	if (!sl) return "no such player";
	const int p = num(1), g = num(2);

	if (op == "newgroup") {
		// EA OnNewFolder (without its name dialog: the name comes with the command).
		ScriptGroup *grp = newInstance(ScriptGroup);
		AsciiString name;
		name.format("Folder %d", ScriptList::getNextID());
		if (w.size() >= 3) name = w[2].c_str();
		grp->setName(name);
		Int n = 0;
		for (ScriptGroup *x = sl->getScriptGroup(); x; x = x->getNext()) n++;
		sl->addGroup(grp, n);
		return "";
	}
	if (op == "deletegroup") {
		ScriptGroup *grp = groupAt(sl, g);
		if (!grp) return "no such folder";
		sl->deleteGroup(grp);
		return "";
	}
	if (op == "groupprop" && w.size() >= 5) {
		ScriptGroup *grp = groupAt(sl, g);
		if (!grp) return "no such folder";
		if (w[3] == "name") grp->setName(AsciiString(w[4].c_str()));
		else if (w[3] == "active") grp->setActive(w[4] == "1");
		else if (w[3] == "subroutine") grp->setSubroutine(w[4] == "1");
		return "";
	}
	if (op == "new") {
		// EA OnNewScript.
		if (g >= 0 && !groupAt(sl, g)) return "no such folder";
		Script *pNewScript = newInstance(Script);
		AsciiString name;
		name.format("Script %d", ScriptList::getNextID());
		pNewScript->setName(name);
		Condition *pFalse1 = newInstance(Condition)(Condition::CONDITION_TRUE);
		OrCondition *pOr = newInstance(OrCondition);
		pOr->setFirstAndCondition(pFalse1);
		pNewScript->setOrCondition(pOr);
		ScriptAction *action = newInstance(ScriptAction)(ScriptAction::NO_OP);
		pNewScript->setAction(action);
		insertScript(sl, g, pNewScript);
		return "";
	}

	const int s = num(3);
	Script *scr = scriptAt(p, g, s);
	if (!scr) return "no such script";

	if (op == "delete") {
		if (g < 0) sl->deleteScript(scr);
		else groupAt(sl, g)->deleteScript(scr);
		return "";
	}
	if (op == "copy") {
		// EA OnCopyScript: a duplicate named "<name> C".
		Script *dup = scr->duplicate();
		AsciiString name = dup->getName();
		name.concat(" C");
		dup->setName(name);
		insertScript(sl, g, dup);
		return "";
	}
	if (op == "prop" && w.size() >= 5) {
		const std::string key = w[4];
		const std::string value = w.size() >= 6 ? w[5] : "";
		if (key == "name") scr->setName(AsciiString(value.c_str()));
		else if (key == "comment") scr->setComment(AsciiString(value.c_str()));
		else if (key == "active") scr->setActive(value == "1");
		else if (key == "oneshot") scr->setOneShot(value == "1");
		else if (key == "easy") scr->setEasy(value == "1");
		else if (key == "normal") scr->setNormal(value == "1");
		else if (key == "hard") scr->setHard(value == "1");
		else if (key == "subroutine") scr->setSubroutine(value == "1");
		else if (key == "delay") scr->setDelayEvalSeconds(atoi(value.c_str()));
		else return "unknown property";
		return "";
	}
	if (op == "addor") {
		// EA ScriptConditionsDlg::OnOr: a new OR clause holding CONDITION_TRUE, at the end.
		OrCondition *pOr = newInstance(OrCondition);
		pOr->setFirstAndCondition(newInstance(Condition)(Condition::CONDITION_TRUE));
		OrCondition *last = scr->getOrCondition();
		if (!last) scr->setOrCondition(pOr);
		else {
			while (last->getNextOrCondition()) last = last->getNextOrCondition();
			last->setNextOrCondition(pOr);
		}
		return "";
	}
	if (op == "addcond") {
		// EA ScriptConditionsDlg::OnNew: an AND condition at the end of OR clause o.
		OrCondition *oc = orAt(scr, num(4));
		if (!oc) return "no such clause";
		int type = num(5);
		if (type < 0 || type >= Condition::NUM_ITEMS) return "no such condition";
		Condition *c = newInstance(Condition)((Condition::ConditionType)type);
		Condition *last = oc->getFirstAndCondition();
		if (!last) oc->setFirstAndCondition(c);
		else {
			while (last->getNext()) last = last->getNext();
			last->setNextCondition(c);
		}
		return "";
	}
	if (op == "setcond") {
		Condition *c = condAt(scr, num(4), num(5));
		int type = num(6);
		if (!c || type < 0 || type >= Condition::NUM_ITEMS) return "no such condition";
		c->setConditionType((Condition::ConditionType)type);
		return "";
	}
	if (op == "delcond") {
		OrCondition *oc = orAt(scr, num(4));
		Condition *c = condAt(scr, num(4), num(5));
		if (!oc || !c) return "no such condition";
		// EA ScriptConditionsDlg::OnDelete: the condition goes; its OR clause stays, even if empty.
		oc->deleteCondition(c);
		return "";
	}
	if (op == "addact" && w.size() >= 6) {
		bool onTrue = w[4] == "t";
		int type = num(5);
		if (type < 0 || type >= ScriptAction::NUM_ITEMS) return "no such action";
		ScriptAction *a = newInstance(ScriptAction)((ScriptAction::ScriptActionType)type);
		ScriptAction *last = onTrue ? scr->getAction() : scr->getFalseAction();
		if (!last) {
			if (onTrue) scr->setAction(a); else scr->setFalseAction(a);
		} else {
			while (last->getNext()) last = last->getNext();
			last->setNextAction(a);
		}
		return "";
	}
	if (op == "setact" && w.size() >= 7) {
		ScriptAction *a = actionAt(scr, w[4] == "t", num(5));
		int type = num(6);
		if (!a || type < 0 || type >= ScriptAction::NUM_ITEMS) return "no such action";
		a->setActionType((ScriptAction::ScriptActionType)type);
		return "";
	}
	if (op == "delact" && w.size() >= 6) {
		bool onTrue = w[4] == "t";
		ScriptAction *a = actionAt(scr, onTrue, num(5));
		if (!a) return "no such action";
		if (onTrue) scr->deleteAction(a); else scr->deleteFalseAction(a);
		return "";
	}
	if (op == "param") {
		// "param p g s c o a k <value>" or "param p g s t|f a k <value>", value "i:", "r:", "s:" or "xyz:".
		if (w.size() != parts) return "no value";
		Parameter *prm = paramAt(scr, w, 4);
		if (!prm) return "no such parameter";
		const std::string &value = w.back();
		const Parameter::ParameterType t = prm->getParameterType();
		const std::string body = value.size() > 2 ? value.substr(value.find(':') + 1) : "";
		if (value.rfind("xyz:", 0) == 0 || t == Parameter::COORD3D) {
			Coord3D c = { 0, 0, 0 };
			sscanf(body.c_str(), "%f,%f,%f", &c.x, &c.y, &c.z);
			prm->friend_setCoord3D(&c);
		} else if (isIntType(t)) {
			prm->friend_setInt(atoi(body.c_str()));
		} else if (isRealType(t)) {
			prm->friend_setReal((Real)atof(body.c_str()));
		} else {
			prm->friend_setString(AsciiString(body.c_str()));
		}
		return "";
	}
	return "unknown edit";
}

} // namespace FluxWB
