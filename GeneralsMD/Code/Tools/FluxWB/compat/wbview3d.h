// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the World Builder's 3D view without MFC.
//
// EA's WbView3d (wbview3d.cpp) is a CView. This one keeps the class name and the members the
// compiled EA files call (DrawObject.cpp: Invalidate, getActualWinSize), and draws the same scene
// the same way: WBHeightMap for the terrain, the W3D asset manager for objects, EA's DrawObject for
// waypoints, paths, water and boundaries, the map's global lights. The window is the SDL window of
// WorldBuilderActivity, the device is d3d8gles, as in the game. Camera input comes from touch
// (FluxWBMain.cpp) instead of the mouse.
#pragma once

#include "StdAfx.h"
#include "wbview.h"
#include "Lib/BaseType.h"
#include "Common/AsciiString.h"
#include "Common/GameType.h"
#include "Common/GlobalData.h"
#include "GameLogic/Module/BodyModule.h"
#include "WWMath/vector3.h"

class CameraClass;
class IntersectionClass;
class LayerClass;
class RenderObjClass;
class CWorldBuilderDoc;
class DrawObject;
class LightClass;
class MapObject;
class RTS3DScene;
class W3DAssetManager;
class WBHeightMap;
class WorldHeightMap;
struct IRegion2D;

class WbView3d : public WbView
{
public:
	WbView3d();
	~WbView3d();

	/** WW3D and the render device on the current window (ApplicationHWnd), as initWW3D. */
	bool init(Int width, Int height);
	void shutdown();
	/** The window changed size (rotation, split screen). */
	void resize(Int width, Int height);

	/** Builds the terrain and objects of the active document, and puts the camera at its start. */
	void showDocument();
	/** Draws one frame when something changed (or always while the camera moves). */
	void redraw();

	// Called by EA's files.
	virtual void Invalidate(Bool erase = true) override { (void)erase; m_dirty = true; }
	bool isDirty() const { return m_dirty; }
	CPoint getActualWinSize() { return m_actualWinSize; }
	virtual void invalObjectInView(MapObject *pMapObjIn) override;
	virtual void updateHeightMapInView(WorldHeightMap *htMap, Bool partial, const IRegion2D &partialRange) override;
	virtual Bool viewToDocCoords(CPoint curPt, Coord3D *newPt, Bool constrained = true) override;
	virtual Bool docToViewCoords(Coord3D curPt, CPoint *newPt) override;
	virtual MapObject *picked3dObjectInView(CPoint viewPt) override;
	/** EA: the semi-transparent preview of the object the object tool would place. */
	void setObjTracking(MapObject *pMapObj, Coord3D pos, Real angle, Bool show);
	void resetRenderObjects();
	void updateLights();

	// Camera, in EA's terms: m_centerPt is in cells, the zoom is EA's mouse-wheel offset.
	virtual void setDefaultCamera() override;
	/** Moves the view by a screen distance (one-finger drag of the hand tool, two-finger pan). */
	void panByPixels(Real dx, Real dy);
	/** Pinch: factor > 1 zooms in. */
	void zoomBy(Real factor);
	/** Two-finger twist, radians. */
	void rotateBy(Real radians);
	/** The ground point under a screen pixel (false off the terrain). */
	bool screenToGround(Real x, Real y, Coord3D *world);

	static WbView3d *GetActiveView() { return s_active; }

private:
	void initScene();
	void setupCamera();
	void constrainCenterPt();
	void render();
	AsciiString getModelNameAndScale(MapObject *pMapObj, Real *scale, BodyDamageType curDamageState);

	static WbView3d *s_active;

	bool m_ww3dInited;
	bool m_dirty;
	CPoint m_actualWinSize;
	W3DAssetManager *m_assetManager;
	RTS3DScene *m_scene;
	RTS3DScene *m_overlayScene;
	RTS3DScene *m_baseBuildScene;
	CameraClass *m_camera;
	WBHeightMap *m_heightMapRenderObj;
	DrawObject *m_drawObject;
	RTS3DScene *m_transparentObjectsScene;
	IntersectionClass *m_intersector;
	LayerClass *m_layer;
	RenderObjClass *m_objectToolTrackingObj;
	AsciiString m_objectToolTrackingModelName;
	Bool m_showObjToolTrackingObj;
	Real m_curTrackingZ;
	LightClass *m_globalLight[MAX_GLOBAL_LIGHTS];
	bool m_needToLoadRoads;

	Vector3 m_centerPt;
	Coord3D m_cameraOffset;
	Real m_cameraAngle;
	Real m_FXPitch;
	Real m_mouseWheelOffset;
	Real m_groundLevel;
	/** Flux: the camera's ground level, eased toward EA's sampled one (no bob when panning over hills). */
	Real m_groundSmooth;
	bool m_groundSmoothValid;
	/** The ray under a screen pixel (world space). */
	void screenRay(Real x, Real y, Vector3 *from, Vector3 *to);
	Vector3 m_cameraSource;
	Vector3 m_cameraTarget;
};
