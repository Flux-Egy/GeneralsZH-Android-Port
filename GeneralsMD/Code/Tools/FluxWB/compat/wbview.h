// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 07/11/2026 PLAN-032: EA's WbView (wbview.h) without CView. Same members
// and the same virtuals the tools call; mouseDown/mouseMove/mouseUp are EA's dispatch to the current
// tool (FluxWBTools.cpp), fed by touch instead of WM_LBUTTONDOWN.
#pragma once

#include "StdAfx.h"
#include "Lib/BaseType.h"
#include "Tool.h"
#include "Common/Debug.h"
#include "Common/ThingSort.h"
#include "Common/GameType.h"
#include "Common/MapObject.h"
#include "WWMath/vector3.h"

class CWorldBuilderDoc;
class BuildListInfo;
class MapObject;
class WorldHeightMap;
struct IRegion2D;

enum TPickedStatus {
	PICK_NONE,
	PICK_CENTER,
	PICK_ARROW
};

enum RulerTypeEnum {
	RULER_NONE,
	RULER_LINE,
	RULER_CIRCLE,
};

class WbView
{
public:
	WbView();
	virtual ~WbView() {}

	// EA's dispatch (wbview.cpp), from FluxWBMain.cpp's gestures.
	void mouseDown(TTrackingMode m, CPoint viewPt);
	void mouseMove(TTrackingMode m, CPoint viewPt);
	void mouseUp(TTrackingMode m, CPoint viewPt);
	/** EA's OnEditDelete: the selected polygon, else the selected objects. */
	void deleteSelection();

	void doRectFeedback(Bool doFeedback, RECT &rect) { m_feedbackBox = rect; m_doRectFeedback = doFeedback; }
	void doRulerFeedback(int doRulerFeedback) { m_doRulerFeedback = doRulerFeedback; }

	virtual Bool viewToDocCoords(CPoint curPt, Coord3D *newPt, Bool constrained = true) = 0;
	virtual Bool docToViewCoords(Coord3D curPt, CPoint *newPt) = 0;
	virtual Bool viewToDocCoordZ(CPoint curPt, Coord3D *newPt, Real Z) { (void)Z; return viewToDocCoords(curPt, newPt, false); }
	virtual void setCenterInView(Real x, Real y) { (void)x; (void)y; }
	virtual void updateHeightMapInView(WorldHeightMap *htMap, Bool partial, const IRegion2D &partialRange) { (void)htMap; (void)partial; (void)partialRange; }
	virtual void invalObjectInView(MapObject *pObj) { (void)pObj; }
	virtual void invalidateCellInView(int xIndex, int yIndex) { (void)xIndex; (void)yIndex; }
	virtual void setDefaultCamera() {}
	virtual void Invalidate(Bool erase = true) = 0;
	virtual Int getPickPixels() { return 4; }
	virtual EditorSortingType GetPickConstraint() { return m_pickConstraint; }

	CWorldBuilderDoc *WbDoc();
	void snapPoint(Coord3D *thePt) { if (m_snapToGrid || m_lockAngle) { thePt->x = MAP_XY_FACTOR*floor(thePt->x/MAP_XY_FACTOR+0.5); thePt->y = MAP_XY_FACTOR*floor(thePt->y/MAP_XY_FACTOR+0.5); } }
	virtual TPickedStatus picked(MapObject *pObj, Coord3D docPt);
	virtual MapObject *picked3dObjectInView(CPoint viewPt) { (void)viewPt; return nullptr; }
	virtual BuildListInfo *pickedBuildObjectInView(CPoint viewPt) { (void)viewPt; return nullptr; }
	Bool isPolygonTriggerVisible() { return m_showPolygonTriggers; }
	Bool isWaypointVisible() { return m_showWaypoints; }
	Bool isNamesVisible() { return m_showNames; }
	void setShowModels(Bool show) { m_showModels = show; }
	Bool getShowModels() { return m_showModels; }
	Bool getShowTerrain() { return m_showTerrain; }
	void setShowGarrisoned(Bool show) { m_showGarrisoned = show; }
	Bool getShowGarrisoned() { return m_showGarrisoned; }
	virtual Bool isDoingPitch() { return false; }

	// Flux: the tool bar's toggles.
	static void setSnapToGrid(Bool snap) { m_snapToGrid = snap; }
	void setShowPolygonTriggers(Bool show) { m_showPolygonTriggers = show; }
	Bool isTracking() const { return m_trackingMode != TRACK_NONE; }

protected:
	TTrackingMode m_trackingMode;
	static Bool m_snapToGrid;
	Bool m_showObjects;
	Bool m_showModels;
	Bool m_showNames;
	Bool m_showGarrisoned;
	Bool m_showWaypoints;
	Bool m_showPolygonTriggers;
	Bool m_showTerrain;
	Real m_hysteresis;
	Bool m_lockAngle;
	Bool m_doLockAngle;
	CPoint m_mouseDownPoint;
	Coord3D m_mouseDownDocPoint;
	RECT m_feedbackBox;
	Bool m_doRectFeedback;
	int m_doRulerFeedback;
	EditorSortingType m_pickConstraint;
};
