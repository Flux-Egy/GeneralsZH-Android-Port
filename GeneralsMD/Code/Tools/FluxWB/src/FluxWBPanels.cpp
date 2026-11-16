// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: the non-dialog parts of EA's option panels
// (ObjectOptions.cpp, WaypointOptions.cpp, LightOptions.cpp) and of WbView (wbview.cpp), which the
// compiled EA tools call. EA's bodies; "Flux:" marks what replaces a dialog.

#include "StdAfx.h"

#include "CUndoable.h"
#include "FluxWBCore.h"
#include "LayersList.h"
#include "WHeightMapEdit.h"
#include "mapobjectprops.h"
#include "LightOptions.h"
#include "ObjectOptions.h"
#include "PolygonTool.h"
#include "WaypointOptions.h"
#include "WaypointTool.h"
#include "WorldBuilder.h"
#include "WorldBuilderDoc.h"
#include "brushoptions.h"
#include "FeatherOptions.h"
#include "MoundOptions.h"
#include "wbview3d.h"

#include "Common/MapObject.h"
#include "Common/NameKeyGenerator.h"
#include "Common/PlayerTemplate.h"
#include "Common/ThingFactory.h"
#include "Common/ThingTemplate.h"
#include "Common/WellKnownKeys.h"
#include "GameLogic/PolygonTrigger.h"
#include "GameLogic/SidesList.h"

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

// The panels' values (their stand-ins in compat/).
Int BrushOptions::m_currentWidth = 0;
Int BrushOptions::m_currentFeather = 0;
Int BrushOptions::m_currentHeight = 0;
Int MoundOptions::m_currentWidth = 0;
Int MoundOptions::m_currentFeather = 0;
Int MoundOptions::m_currentHeight = 0;
Int FeatherOptions::m_currentFeather = 0;
Int FeatherOptions::m_currentRate = 3;
Int FeatherOptions::m_currentRadius = 1;

// ---------------------------------------------------------------------------------------------
// ObjectOptions (EA ObjectOptions.cpp)
// ---------------------------------------------------------------------------------------------

MapObject *ObjectOptions::m_objectsList = nullptr;
Int ObjectOptions::m_currentObjectIndex = -1;
char ObjectOptions::m_currentObjectName[NAME_MAX_LEN];
AsciiString ObjectOptions::m_curOwnerName;

static Int findSideListEntryWithPlayerOfSide(AsciiString side)
{
	for (int i = 0; i < TheSidesList->getNumSides(); i++) {
		AsciiString ptname = TheSidesList->getSideInfo(i)->getDict()->getAsciiString(TheKey_playerFaction);
		const PlayerTemplate *pt = ThePlayerTemplateStore->findPlayerTemplate(NAMEKEY(ptname));
		if (pt && pt->getSide() == side) {
			return i;
		}
	}
	return -1;
}

/** OnInitDialog: one MapObject per ThingTemplate. */
void ObjectOptions::buildList()
{
	if (m_objectsList != nullptr) {
		return;
	}
	for (const ThingTemplate *tTemplate = TheThingFactory->firstTemplate(); tTemplate;
			tTemplate = tTemplate->friend_getNextTemplate()) {
		Coord3D loc = { 0, 0, 0 };
		MapObject *pMap = newInstance(MapObject)(loc, tTemplate->getName(), 0.0f, 0, nullptr, tTemplate);
		pMap->setNextMap(m_objectsList);
		m_objectsList = pMap;
		Color cc = tTemplate->getDisplayColor();
		pMap->setColor(cc);
	}
	m_curOwnerName.set("team"); // neutral (EA's default owner)
}

void ObjectOptions::freeList()
{
	if (m_objectsList) {
		deleteInstance(m_objectsList); // deletes the whole chain (MapObject's destructor)
		m_objectsList = nullptr;
	}
	m_currentObjectIndex = -1;
	m_currentObjectName[0] = 0;
}

MapObject *ObjectOptions::getCurMapObject()
{
	if (m_currentObjectIndex >= 0) {
		int count = 0;
		for (MapObject *pObj = m_objectsList; pObj; pObj = pObj->getNext()) {
			if (count == m_currentObjectIndex) {
				return pObj;
			}
			count++;
		}
	}
	return nullptr;
}

bool ObjectOptions::setCurrent(const AsciiString &name)
{
	Int ndx = getObjectNamedIndex(name);
	if (ndx < 0) {
		return false;
	}
	m_currentObjectIndex = ndx;
	strlcpy(m_currentObjectName, name.str(), NAME_MAX_LEN);

	// EA updateLabel(): the owning team defaults to the team of the first player of the object's
	// side, else neutral ("team"); no team at all leaves the owner empty.
	AsciiString defTeamName;
	MapObject *pCur = getCurMapObject();
	const ThingTemplate *tt = pCur ? pCur->getThingTemplate() : nullptr;
	if (tt) {
		Int i = findSideListEntryWithPlayerOfSide(tt->getDefaultOwningSide());
		if (i >= 0) {
			defTeamName.set("team");
			defTeamName.concat(TheSidesList->getSideInfo(i)->getDict()->getAsciiString(TheKey_playerName));
		}
	} else {
		defTeamName.set("team");
	}
	Int neutral = -1;
	Int sel = -1;
	for (int i = 0; i < TheSidesList->getNumTeams(); i++) {
		AsciiString teamName = TheSidesList->getTeamInfo(i)->getDict()->getAsciiString(TheKey_teamName);
		if (teamName == defTeamName) sel = i;
		if (teamName == "team") neutral = i;
	}
	if (sel == -1) sel = neutral;
	if (sel == -1) {
		m_curOwnerName.clear();
	} else {
		m_curOwnerName = TheSidesList->getTeamInfo(sel)->getDict()->getAsciiString(TheKey_teamName);
	}
	return true;
}

MapObject *ObjectOptions::duplicateCurMapObjectForPlace(const Coord3D *loc, Real angle, Bool checkPlayers)
{
	MapObject *pCur = getCurMapObject();
	if (pCur) {
		Bool found = false;
		const ThingTemplate *tt = pCur->getThingTemplate();
		if (checkPlayers) {
			AsciiString defaultTeam("team");
			AsciiString objectTeamName = m_curOwnerName;
			if (objectTeamName != defaultTeam) {
				TeamsInfo *teamInfo = TheSidesList->findTeamInfo(objectTeamName);
				if (teamInfo) {
					AsciiString teamOwner = teamInfo->getDict()->getAsciiString(TheKey_teamOwner);
					SidesInfo *pSide = TheSidesList->findSideInfo(teamOwner);
					if (pSide) {
						found = true;
					}
				}
			}
			if (!found && tt) {
				AsciiString defPlayerSide = tt->getDefaultOwningSide();
				Int si = findSideListEntryWithPlayerOfSide(defPlayerSide);
				found = (si >= 0);
				if (found) {
					// Flux: EA's owner combo box is rebuilt whenever the sides change, so its selection
					// always names a team that exists. Here the owner can outlive a team removed in the
					// mission editor or by an undo; it falls back to the side's default team, the
					// combo's default (updateLabel).
					m_curOwnerName.set("team");
					m_curOwnerName.concat(TheSidesList->getSideInfo(si)->getDict()->getAsciiString(TheKey_playerName));
				}
				if (!found) {
					// Flux: EA asks to add a player of that side here (AddPlayerDialog). Until the
					// player list is in the editor, the object is placed neutral, as EA does for an
					// object whose side has no default player.
					m_curOwnerName.set("team");
					found = true;
					FluxWB::reportMessage("No player of this object's side exists yet; it was placed as neutral.");
				}
			} else {
				if (!found) {
					// neutral
					m_curOwnerName.set("team");
					found = true;
				}
			}
		} else {
			found = true;
		}
		if (found) {
			MapObject *pNew = newInstance(MapObject)(*loc, pCur->getName(), angle,
				pCur->getFlags(), pCur->getProperties(), pCur->getThingTemplate());
			pNew->getProperties()->setAsciiString(TheKey_originalOwner, m_curOwnerName);
			pNew->setColor(pCur->getColor());
			return pNew;
		}
	}
	AfxMessageBox("Unable to add object.");
	return nullptr;
}

MapObject *ObjectOptions::getObjectNamed(AsciiString name)
{
	for (MapObject *pObj = m_objectsList; pObj; pObj = pObj->getNext()) {
		if (name == pObj->getName()) {
			return pObj;
		}
		const char *curName = pObj->getName().str();
		const char *leaf = curName;
		while (*curName) {
			if (*curName == '/') {
				leaf = curName + 1;
			}
			curName++;
		}
		if (0 == strcmp(leaf, name.str())) {
			return pObj;
		}
	}
	return nullptr;
}

Int ObjectOptions::getObjectNamedIndex(const AsciiString &name)
{
	Int index = 0;
	for (MapObject *pObj = m_objectsList; pObj; pObj = pObj->getNext(), index++) {
		if (name == pObj->getName()) {
			return index;
		}
	}
	return -1;
}

void ObjectOptions::selectObject(const MapObject *pObj)
{
	// Flux: EA selects the object's entry in the tree view; the Java sheet is told instead.
	if (pObj) {
		FluxWB::reportSelection(pObj->getName().str());
	}
}

void ObjectOptions::update()
{
	FluxWB::reportSelection(nullptr);
}

std::string ObjectOptions::listForUi()
{
	static const char *kSorting[] = { "none", "structure", "infantry", "vehicle", "shrubbery", "misc_man_made",
		"misc_natural", "debris", "system", "audio", "test", "fx", "road", "bridge" };
	std::vector<std::string> lines;
	for (MapObject *pObj = m_objectsList; pObj; pObj = pObj->getNext()) {
		const ThingTemplate *tt = pObj->getThingTemplate();
		if (tt == nullptr) continue;
		Int sort = (Int)tt->getEditorSorting();
		const char *cat = (sort >= 0 && sort < (Int)ARRAY_SIZE(kSorting)) ? kSorting[sort] : "none";
		if (sort == ES_NONE || sort == ES_SYSTEM || sort == ES_AUDIO || sort == ES_TEST) {
			continue; // EA's tree files these under hidden branches; not offered for placement here.
		}
		lines.push_back(std::string(pObj->getName().str()) + "\t" + cat);
	}
	std::sort(lines.begin(), lines.end());
	std::string out;
	for (const std::string &l : lines) {
		out += l;
		out += '\n';
	}
	return out;
}

// ---------------------------------------------------------------------------------------------
// WaypointOptions (EA WaypointOptions.cpp)
// ---------------------------------------------------------------------------------------------

void WaypointOptions::update()
{
	MapObject *way = getSingleSelectedWaypoint();
	PolygonTrigger *trig = getSingleSelectedPolygon();
	if (way) {
		FluxWB::reportWaypoint(way->getWaypointName().str());
	} else if (trig) {
		FluxWB::reportArea(trig->getTriggerName().str());
	} else {
		FluxWB::reportWaypoint(nullptr);
	}
}

MapObject *WaypointOptions::getSingleSelectedWaypoint()
{
	MapObject *theMapObj = nullptr;
	Int selCount = 0;
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		if (pMapObj->isSelected()) {
			if (pMapObj->isWaypoint()) {
				theMapObj = pMapObj;
			}
			selCount++;
		}
	}
	if (selCount == 1 && theMapObj) {
		return theMapObj;
	}
	return nullptr;
}

PolygonTrigger *WaypointOptions::getSingleSelectedPolygon()
{
	CWorldBuilderDoc *pDoc = CWorldBuilderDoc::GetActiveDoc();
	if (pDoc == nullptr) return nullptr;
	WbView3d *p3View = pDoc->GetActive3DView();
	Bool showPoly = false;
	if (p3View) {
		showPoly = p3View->isPolygonTriggerVisible();
	}
	if (showPoly || PolygonTool::isActive()) {
		for (PolygonTrigger *pTrig = PolygonTrigger::getFirstPolygonTrigger(); pTrig; pTrig = pTrig->getNext()) {
			if (PolygonTool::isSelected(pTrig)) {
				return pTrig;
			}
		}
	}
	return nullptr;
}

Bool WaypointOptions::isUnique(AsciiString name, MapObject *theMapObj)
{
	Bool didMatch = false;
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); !didMatch && pMapObj; pMapObj = pMapObj->getNext()) {
		if (pMapObj->isWaypoint()) {
			if (pMapObj == theMapObj) continue; // don't check against self.
			AsciiString wayName = pMapObj->getWaypointName();
			if (name == wayName) {
				didMatch = true;
				break;
			}
		}
	}
	return (didMatch == false);
}

AsciiString WaypointOptions::GenerateUniqueName(Int id)
{
	AsciiString name;
	name.format("Waypoint %d", id);
	while (!isUnique(name)) {
		id++;
		name.format("Waypoint %d", id);
	}
	return name;
}

/** EA's OnEditchangeWaypointnameEdit / changeWaypointName, without the combo box. */
bool WaypointOptions::setSelectedName(const AsciiString &name)
{
	MapObject *theMapObj = getSingleSelectedWaypoint();
	PolygonTrigger *theTrigger = getSingleSelectedPolygon();
	if (name.isEmpty() || (!theMapObj && !theTrigger)) {
		return false;
	}
	Bool didMatch = !isUnique(name, theMapObj);
	for (PolygonTrigger *pTrig = PolygonTrigger::getFirstPolygonTrigger(); !didMatch && pTrig; pTrig = pTrig->getNext()) {
		if (pTrig == theTrigger) continue;
		const AsciiString &trigName = pTrig->getTriggerName();
		if (name == trigName) {
			if (pTrig->isValid()) {
				didMatch = true;
			} else {
				PolygonTrigger::removePolygonTrigger(pTrig);
			}
			break;
		}
	}
	if (didMatch) {
		::AfxMessageBox("Name already in use");
		return false;
	}
	if (theMapObj) {
		AsciiString layerName = TheLayersList->removeMapObjectFromLayersList(theMapObj);
		theMapObj->setWaypointName(name);
		theMapObj->validate();
		TheLayersList->addMapObjectToLayersList(theMapObj, layerName);
	} else if (theTrigger) {
		theTrigger->setTriggerName(name);
	}
	CWorldBuilderDoc *pDoc = CWorldBuilderDoc::GetActiveDoc();
	if (pDoc) {
		pDoc->SetModifiedFlag();
		pDoc->updateAllViews();
	}
	return true;
}

// ---------------------------------------------------------------------------------------------
// MapObjectProps (EA mapobjectprops.cpp)
// ---------------------------------------------------------------------------------------------

MapObject *MapObjectProps::getSingleSelectedMapObject()
{
	MapObject *theMapObj = nullptr;
	Int selCount = 0;
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		if (pMapObj->isSelected()) {
			if (!pMapObj->isWaypoint() && !pMapObj->isLight()) {
				theMapObj = pMapObj;
			}
			selCount++;
		}
	}
	if (selCount == 1 && theMapObj) {
		return theMapObj;
	}
	return nullptr;
}

void MapObjectProps::update()
{
	MapObject *obj = getSingleSelectedMapObject();
	FluxWB::reportSelection(obj ? obj->getName().str() : nullptr);
}

// ---------------------------------------------------------------------------------------------
// LightOptions (EA)
// ---------------------------------------------------------------------------------------------

MapObject *LightOptions::getSingleSelectedLight()
{
	MapObject *theMapObj = nullptr;
	Int selCount = 0;
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		if (pMapObj->isSelected()) {
			if (pMapObj->isLight()) {
				theMapObj = pMapObj;
			}
			selCount++;
		}
	}
	if (selCount == 1 && theMapObj) {
		return theMapObj;
	}
	return nullptr;
}

// ---------------------------------------------------------------------------------------------
// WbView (EA wbview.cpp): defaults, mouse dispatch, picking, delete.
// ---------------------------------------------------------------------------------------------

Bool WbView::m_snapToGrid = false;

WbView::WbView() :
	m_trackingMode(TRACK_NONE),
	m_showObjects(true),
	m_showModels(true),
	m_showNames(true),
	m_showGarrisoned(false),
	m_showWaypoints(true),
	m_showPolygonTriggers(true),
	m_showTerrain(true),
	m_hysteresis(0),
	m_lockAngle(false),
	m_doLockAngle(false),
	m_doRectFeedback(false),
	m_doRulerFeedback(RULER_NONE),
	m_pickConstraint(ES_NONE)
{
	m_mouseDownDocPoint.zero();
	memset(&m_feedbackBox, 0, sizeof(m_feedbackBox));
}

CWorldBuilderDoc *WbView::WbDoc()
{
	return CWorldBuilderDoc::GetActiveDoc();
}

void WbView::mouseDown(TTrackingMode m, CPoint viewPt)
{
	if (m_trackingMode != TRACK_NONE)
		return;
	m_mouseDownPoint = viewPt;
	viewToDocCoords(viewPt, &m_mouseDownDocPoint);
	m_trackingMode = m;
	WbApp()->updateCurTool(m == TRACK_R || m == TRACK_M);
	WbApp()->lockCurTool();
	if (WbApp()->getCurTool()) {
		WbApp()->getCurTool()->mouseDown(m, viewPt, this, WbDoc());
	}
}

void WbView::mouseMove(TTrackingMode m, CPoint viewPt)
{
	if (m_trackingMode == TRACK_NONE) {
		m_doLockAngle = false;
	} else {
		m_doLockAngle = m_lockAngle;
	}
	if (m_trackingMode == m) {
		WbApp()->updateCurTool(false);
		if (WbApp()->getCurTool()) {
			WbApp()->getCurTool()->mouseMoved(m, viewPt, this, WbDoc());
		}
	}
	// Flux: EA builds the status-bar text here (coordinates, object under the cursor).
}

void WbView::mouseUp(TTrackingMode m, CPoint viewPt)
{
	if (m_trackingMode == TRACK_NONE)
		return;
	if (WbApp()->getCurTool()) {
		WbApp()->getCurTool()->mouseUp(m, viewPt, this, WbDoc());
		WorldHeightMapEdit *pMap = WbDoc() ? WbDoc()->GetHeightMap() : nullptr;
		if (pMap) {
			if (pMap->tooManyTextures()) {
				AfxMessageBox("Too many textures. The map uses more tiles than the game allows.");
			}
			if (pMap->tooManyBlends()) {
				AfxMessageBox("Too many blends. The map uses more blend tiles than the game allows.");
			}
			pMap->clearStatus();
		}
	}
	WbApp()->unlockCurTool();
	WbApp()->updateCurTool(false);
	m_trackingMode = TRACK_NONE;
}

TPickedStatus WbView::picked(MapObject *pObj, Coord3D docPt)
{
	Coord3D cloc = *pObj->getLocation();
	if (!m_showObjects && !pObj->isWaypoint()) {
		return PICK_NONE;
	}
	if (!m_showWaypoints && !WaypointTool::isActive() && pObj->isWaypoint()) {
		return PICK_NONE;
	}
	Bool doArrow = pObj->isSelected();
	Coord3D cpt = docPt;
	cpt.x -= cloc.x;
	cpt.y -= cloc.y;
	cpt.z = 0;
	if (cpt.length() < 0.5f * MAP_XY_FACTOR + m_hysteresis) {
		return PICK_CENTER;
	}
	if (pObj->getFlag(FLAG_ROAD_FLAGS) || pObj->getFlag(FLAG_BRIDGE_FLAGS) || pObj->isWaypoint()) {
		doArrow = false;
	}
	if (doArrow && cpt.length() < 1.5f * MAP_XY_FACTOR + m_hysteresis) {
		return PICK_ARROW;
	}
	return PICK_NONE;
}

void WbView::deleteSelection()
{
	if (PolygonTool::isActive() || m_showPolygonTriggers) {
		if (PolygonTool::deleteSelectedPolygon()) {
			return;
		}
	}
	CWorldBuilderDoc *pDoc = WbDoc();
	if (pDoc == nullptr) return;
	DeleteObjectUndoable *pUndo = new DeleteObjectUndoable(pDoc);
	pDoc->AddAndDoUndoable(pUndo);
	REF_PTR_RELEASE(pUndo); // belongs to pDoc now.
}
