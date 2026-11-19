// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the native 3D view (compat/wbview3d.h).
// Each function names the EA function in WorldBuilder/src/wbview3d.cpp it follows; "Flux:" marks
// what differs (no MFC window, touch camera instead of the mouse, no labels font yet).

#include "StdAfx.h"

#include "wbview3d.h"

#include "BuildListTool.h"
#include "DrawObject.h"
#include "FluxWBPlaceholderView.h"
#include "MeshMoldOptions.h"
#include "WBHeightMap.h"
#include "WHeightMapEdit.h"
#include "WorldBuilderDoc.h"

#include "Common/FramePacer.h"
#include "Common/GlobalData.h"
#include "Common/MapObject.h"
#include "Common/NameKeyGenerator.h"
#include "Common/PlayerTemplate.h"
#include "Common/ThingTemplate.h"
#include "Common/WellKnownKeys.h"
#include "GameClient/Water.h"
#include "GameLogic/SidesList.h"
#include "W3DDevice/GameClient/Module/W3DModelDraw.h"
#include "W3DDevice/GameClient/Module/W3DTreeDraw.h"
#include "W3DDevice/GameClient/W3DAssetManager.h"
#include "W3DDevice/GameClient/W3DScene.h"
#include "W3DDevice/GameClient/W3DShaderManager.h"
#include "W3DDevice/GameClient/W3DShadow.h"
#include "W3DDevice/GameClient/WorldHeightMap.h"

#include "agg_def.h"
#include "intersec.h"
#include "layer.h"
#include "W3DDevice/Common/W3DConvert.h"
#include "camera.h"
#include "colmath.h"
#include "coltest.h"
#include "dx8wrapper.h"
#include "light.h"
#include "part_ldr.h"
#include "predlod.h"
#include "rendobj.h"
#include "scene.h"
#include "wwmath.h"
#include "dx8renderer.h"
#include "WorldBuilder.h"
#include "ww3d.h"

#include <algorithm>
#include <cmath>

extern HWND ApplicationHWnd;

// The tool stand-ins' state (compat/*.h).
Bool BuildListTool::m_isActive = false;
Real MeshMoldOptions::m_currentScale = 1.0f;
Int MeshMoldOptions::m_currentAngle = 0;
AsciiString MeshMoldOptions::m_meshModelName;

WbView3d *WbView3d::s_active = nullptr;

namespace {

PlaceholderView bogusTacticalView;

/** EA's SkeletonSceneClass (wbview3d.cpp): removing an object the scene does not hold is a no-op. */
class SkeletonSceneClass : public RTS3DScene
{
public:
	virtual void Remove_Render_Object(RenderObjClass *obj) override
	{
		if (RenderList.Contains(obj)) {
			RenderObjClass *refPtr = nullptr;
			REF_PTR_SET(refPtr, obj); // ref it, as when it gets removed from the scene, may get deleted otherwise.
			RTS3DScene::Remove_Render_Object(obj);
			REF_PTR_RELEASE(refPtr);
		}
	}
};

#define TERRAIN_SAMPLE_SIZE 40.0f
Real getHeightAroundPos(WBHeightMap *heightMap, Real x, Real y)
{
	Real terrainHeight = heightMap->getHeightMapHeight(x, y, nullptr);
	Real terrainHeightMax = terrainHeight;
	terrainHeightMax = std::max(terrainHeightMax, heightMap->getHeightMapHeight(x + TERRAIN_SAMPLE_SIZE, y - TERRAIN_SAMPLE_SIZE, nullptr));
	terrainHeightMax = std::max(terrainHeightMax, heightMap->getHeightMapHeight(x - TERRAIN_SAMPLE_SIZE, y - TERRAIN_SAMPLE_SIZE, nullptr));
	terrainHeightMax = std::max(terrainHeightMax, heightMap->getHeightMapHeight(x + TERRAIN_SAMPLE_SIZE, y + TERRAIN_SAMPLE_SIZE, nullptr));
	terrainHeightMax = std::max(terrainHeightMax, heightMap->getHeightMapHeight(x - TERRAIN_SAMPLE_SIZE, y + TERRAIN_SAMPLE_SIZE, nullptr));
	return terrainHeightMax;
}

/** WbView3d::getBestModelName. */
AsciiString getBestModelName(const ThingTemplate *tt, const ModelConditionFlags &c)
{
	if (tt) {
		const ModuleInfo &mi = tt->getDrawModuleInfo();
		if (mi.getCount() > 0) {
			const ModuleData *mdd = mi.getNthData(0);
			const W3DModelDrawModuleData *md = mdd ? mdd->getAsW3DModelDrawModuleData() : nullptr;
			if (md) {
				return md->getBestModelNameForWB(c);
			}
			const W3DTreeDrawModuleData *treeData = mdd ? mdd->getAsW3DTreeDrawModuleData() : nullptr;
			if (treeData) {
				return treeData->m_modelName;
			}
		}
	}
	return AsciiString::TheEmptyString;
}

} // namespace

WbView3d::WbView3d() :
	m_ww3dInited(false),
	m_dirty(true),
	m_assetManager(nullptr),
	m_scene(nullptr),
	m_overlayScene(nullptr),
	m_baseBuildScene(nullptr),
	m_camera(nullptr),
	m_heightMapRenderObj(nullptr),
	m_drawObject(nullptr),
	m_transparentObjectsScene(nullptr),
	m_intersector(nullptr),
	m_layer(nullptr),
	m_objectToolTrackingObj(nullptr),
	m_showObjToolTrackingObj(false),
	m_curTrackingZ(0),
	m_needToLoadRoads(true),
	m_centerPt(0, 0, 0),
	m_cameraAngle(0),
	m_FXPitch(1.0f),
	m_mouseWheelOffset(0),
	m_groundLevel(10.0f),
	m_groundSmooth(0),
	m_groundSmoothValid(false),
	m_cameraSource(0, 0, 0),
	m_cameraTarget(0, 0, 0)
{
	m_cameraOffset.x = m_cameraOffset.y = m_cameraOffset.z = 1;
	for (Int i = 0; i < MAX_GLOBAL_LIGHTS; i++) {
		m_globalLight[i] = nullptr;
	}
	s_active = this;
	// The W3DShadowManager accesses TheTacticalView (EA).
	TheTacticalView = &bogusTacticalView;
}

WbView3d::~WbView3d()
{
	shutdown();
	if (s_active == this) {
		s_active = nullptr;
	}
}

// ---------------------------------------------------------------------------------------------
// WbView3d::initWW3D + init3dScene + the constructor's lights.
// ---------------------------------------------------------------------------------------------

bool WbView3d::init(Int width, Int height)
{
	if (m_ww3dInited) {
		return true;
	}
	m_actualWinSize = CPoint(width, height);
	bogusTacticalView.setWidth(width);
	bogusTacticalView.setHeight(height);
	bogusTacticalView.setOrigin(0, 0);

	WWMath::Init();
	WW3D::Set_Prelit_Mode(WW3D::PRELIT_MODE_VERTEX);

	// initAssets
	m_assetManager = new W3DAssetManager;
	m_assetManager->Register_Prototype_Loader(&_ParticleEmitterLoader);
	m_assetManager->Register_Prototype_Loader(&_AggregateLoader);
	m_assetManager->Set_WW3D_Load_On_Demand(true);

	if (WW3D::Init(ApplicationHWnd) != WW3D_ERROR_OK) {
		fprintf(stderr, "[fluxwb] WW3D::Init failed\n");
		delete m_assetManager;
		m_assetManager = nullptr;
		WWMath::Shutdown();
		return false;
	}
	WW3D::Set_Prelit_Mode(WW3D::PRELIT_MODE_LIGHTMAP_MULTI_PASS);
	WW3D::Set_Collision_Box_Display_Mask(0x00);

	if (WW3D::Set_Render_Device(0, width, height, 32, true, true) != WW3D_ERROR_OK) {
		fprintf(stderr, "[fluxwb] no render device at %dx%d\n", width, height);
		delete m_assetManager;
		m_assetManager = nullptr;
		WW3D::Shutdown();
		WWMath::Shutdown();
		return false;
	}
	m_ww3dInited = true;
	WW3D::Enable_Static_Sort_Lists(true);
	WW3D::Set_Thumbnail_Enabled(false);
	WW3D::Set_Screen_UV_Bias(TRUE);

	W3DShaderManager::init();
	initScene();

	for (Int i = 0; i < MAX_GLOBAL_LIGHTS; i++) {
		m_globalLight[i] = NEW_REF(LightClass, (LightClass::DIRECTIONAL));
	}

	m_layer = new LayerClass(m_scene, m_camera);
	m_intersector = new IntersectionClass();
	m_drawObject = new DrawObject();
	m_overlayScene->Add_Render_Object(m_drawObject);

	TheWritableGlobalData->m_useShadowVolumes = true;
	TheWritableGlobalData->m_useShadowDecals = true;
	TheWritableGlobalData->m_enableBehindBuildingMarkers = false; //this is only for the game.
	if (TheW3DShadowManager == nullptr) {
		TheW3DShadowManager = new W3DShadowManager;
	}
	TheW3DShadowManager->init();

	updateLights();
	resetRenderObjects();
	fprintf(stderr, "[fluxwb] 3D view ready, %dx%d\n", width, height);
	return true;
}

void WbView3d::initScene()
{
	m_scene = NEW_REF(SkeletonSceneClass, ());
	m_overlayScene = NEW_REF(SkeletonSceneClass, ());
	m_baseBuildScene = NEW_REF(SkeletonSceneClass, ());
	m_transparentObjectsScene = NEW_REF(SkeletonSceneClass, ());
	m_scene->Set_Ambient_Light(Vector3(0.5f, 0.5f, 0.5f));
	m_overlayScene->Set_Ambient_Light(Vector3(0.5f, 0.5f, 0.5f));
	m_baseBuildScene->Set_Ambient_Light(Vector3(0.5f, 0.5f, 0.5f));
	m_camera = NEW_REF(CameraClass, ());
}

/** WbView3d::~WbView3d / the ww3d shut-down. */
void WbView3d::shutdown()
{
	if (!m_ww3dInited) {
		return;
	}
	if (m_assetManager) {
		PredictiveLODOptimizerClass::Free();
		m_assetManager->Free_Assets();
		delete m_assetManager;
		m_assetManager = nullptr;
	}
	if (TheW3DShadowManager) {
		TheW3DShadowManager->removeAllShadows();
		delete TheW3DShadowManager;
		TheW3DShadowManager = nullptr;
	}
	delete m_intersector;
	m_intersector = nullptr;
	delete m_layer;
	m_layer = nullptr;
	REF_PTR_RELEASE(m_objectToolTrackingObj);
	REF_PTR_RELEASE(m_transparentObjectsScene);
	REF_PTR_RELEASE(m_overlayScene);
	REF_PTR_RELEASE(m_baseBuildScene);
	REF_PTR_RELEASE(m_scene);
	REF_PTR_RELEASE(m_camera);
	REF_PTR_RELEASE(m_heightMapRenderObj);
	REF_PTR_RELEASE(m_drawObject);
	for (Int i = 0; i < MAX_GLOBAL_LIGHTS; i++) {
		REF_PTR_RELEASE(m_globalLight[i]);
	}
	W3DShaderManager::shutdown(); // EA: ~WbView3d, before WW3D goes
	WW3D::Shutdown();
	WWMath::Shutdown();
	m_ww3dInited = false;
}

/** WbView3d::reset3dEngineDisplaySize. */
void WbView3d::resize(Int width, Int height)
{
	if (m_actualWinSize.x == width && m_actualWinSize.y == height) {
		return;
	}
	bogusTacticalView.setWidth(width);
	bogusTacticalView.setHeight(height);
	bogusTacticalView.setOrigin(0, 0);
	m_actualWinSize.x = width;
	m_actualWinSize.y = height;
	if (m_ww3dInited) {
		WW3D::Set_Device_Resolution(m_actualWinSize.x, m_actualWinSize.y, true);
	}
	m_dirty = true;
}

// ---------------------------------------------------------------------------------------------
// Scene content: resetRenderObjects, updateLights, updateHeightMapInView, invalObjectInView.
// ---------------------------------------------------------------------------------------------

void WbView3d::resetRenderObjects()
{
	if (!m_scene) return;
	if (TheW3DShadowManager) {
		TheW3DShadowManager->removeAllShadows();
	}
	RTS3DScene *scenes[] = { m_scene, m_baseBuildScene };
	for (RTS3DScene *scene : scenes) {
		SceneIterator *sceneIter = scene->Create_Iterator();
		sceneIter->First();
		while (!sceneIter->Is_Done()) {
			RenderObjClass *robj = sceneIter->Current_Item();
			robj->Add_Ref();
			scene->Remove_Render_Object(robj);
			robj->Release_Ref();
			sceneIter->Next();
		}
		scene->Destroy_Iterator(sceneIter);
	}
	// Erase references to render objs that have been removed.
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		pMapObj->setRenderObj(nullptr);
	}
	for (Int i = 0; i < TheSidesList->getNumSides(); i++) {
		SidesInfo *pSide = TheSidesList->getSideInfo(i);
		for (BuildListInfo *pBuild = pSide->getBuildList(); pBuild; pBuild = pBuild->getNext()) {
			pBuild->setRenderObj(nullptr);
		}
	}
	m_needToLoadRoads = true; // load roads next time we redraw.
	if (TheW3DShadowManager)
		TheW3DShadowManager->Reset();
	updateLights();
	if (m_heightMapRenderObj) {
		m_scene->Add_Render_Object(m_heightMapRenderObj);
		m_heightMapRenderObj->removeAllTrees();
		m_heightMapRenderObj->removeAllProps();
	}
	m_dirty = true;
}

void WbView3d::updateLights()
{
	TheWritableGlobalData->setTimeOfDay(TheGlobalData->m_timeOfDay);
	const GlobalData::TerrainLighting *ol = &TheGlobalData->m_terrainObjectsLighting[TheGlobalData->m_timeOfDay][0];
	if (m_scene) {
		m_scene->Set_Ambient_Light(Vector3(ol->ambient.red, ol->ambient.green, ol->ambient.blue));
		m_baseBuildScene->Set_Ambient_Light(Vector3(ol->ambient.red, ol->ambient.green, ol->ambient.blue));
	}
	if (TheW3DShadowManager) {
		TheW3DShadowManager->setTimeOfDay(TheGlobalData->m_timeOfDay);
	}
	for (Int i = 0; i < MAX_GLOBAL_LIGHTS; i++) {
		if (m_globalLight[i] && m_scene) {
			ol = &TheGlobalData->m_terrainObjectsLighting[TheGlobalData->m_timeOfDay][i];
			m_globalLight[i]->Set_Ambient(Vector3(0.0f, 0.0f, 0.0f));
			m_globalLight[i]->Set_Diffuse(Vector3(ol->diffuse.red, ol->diffuse.green, ol->diffuse.blue));
			m_globalLight[i]->Set_Specular(Vector3(0, 0, 0));
			Matrix3D mtx;
			mtx.Set(Vector3(1, 0, 0), Vector3(0, 1, 0), Vector3(ol->lightPos.x, ol->lightPos.y, ol->lightPos.z), Vector3(0, 0, 0));
			m_globalLight[i]->Set_Transform(mtx);
			m_scene->setGlobalLight(m_globalLight[i], i);
			m_baseBuildScene->setGlobalLight(m_globalLight[i], i);
		}
	}
	if (TheTerrainRenderObject) {
		TheTerrainRenderObject->setTimeOfDay(TheGlobalData->m_timeOfDay);
	}
	// Flux: EA also turns the map's light objects into W3D point lights here; they come with the
	// Lights tool (PLAN-032 phase 3).
	m_dirty = true;
}

void WbView3d::updateHeightMapInView(WorldHeightMap *htMap, Bool partial, const IRegion2D &partialRange)
{
	if (htMap == nullptr || !m_ww3dInited)
		return;
	if (m_heightMapRenderObj == nullptr) {
		m_heightMapRenderObj = NEW_REF(WBHeightMap, ());
		m_scene->Add_Render_Object(m_heightMapRenderObj);
		partial = false;
	}
	if (partial) {
		m_heightMapRenderObj->doPartialUpdate(partialRange, htMap, nullptr);
	} else {
		// EA's default is m_showEntireMap (the whole map, not the cached subset).
		htMap->setDrawOrg(0, 0);
		htMap->setDrawWidth(htMap->getXExtent());
		htMap->setDrawHeight(htMap->getYExtent());
		m_heightMapRenderObj->initHeightData(htMap->getXExtent(), htMap->getYExtent(), htMap, nullptr);
		m_heightMapRenderObj->updateViewImpassableAreas();
	}
	invalObjectInView(nullptr); // update all the map objects, to account for ground changes
}

/** WbView3d::getModelNameAndScale, without the test-art path. */
AsciiString WbView3d::getModelNameAndScale(MapObject *pMapObj, Real *scale, BodyDamageType curDamageState)
{
	ModelConditionFlags state;
	switch (curDamageState) {
		case BODY_DAMAGED: state.set(MODELCONDITION_DAMAGED); break;
		case BODY_REALLYDAMAGED: state.set(MODELCONDITION_REALLY_DAMAGED); break;
		case BODY_RUBBLE: state.set(MODELCONDITION_RUBBLE); break;
		default: break;
	}
	Bool exists = false;
	Int objWeather = pMapObj->getProperties() ? pMapObj->getProperties()->getInt(TheKey_objectWeather, &exists) : 0;
	if ((objWeather == 0 && TheGlobalData->m_weather == WEATHER_SNOWY) || objWeather == 2) {
		state.set(MODELCONDITION_SNOW);
	}
	Int objTime = pMapObj->getProperties() ? pMapObj->getProperties()->getInt(TheKey_objectTime, &exists) : 0;
	if ((objTime == 0 && TheGlobalData->m_timeOfDay == TIME_OF_DAY_NIGHT) || objTime == 2) {
		state.set(MODELCONDITION_NIGHT);
	}
	*scale = 1.0f;
	AsciiString modelName = "No Model Name";
	const ThingTemplate *tTemplate = pMapObj->getThingTemplate();
	if (tTemplate && !(pMapObj->getFlags() & FLAG_DONT_RENDER)) {
		modelName = getBestModelName(tTemplate, state);
		*scale = tTemplate->getAssetScale();
	}
	return modelName;
}

/** WbView3d::invalObjectInView, for the whole map (pMapObjIn == nullptr) or one object. */
void WbView3d::invalObjectInView(MapObject *pMapObjIn)
{
	if (!m_ww3dInited) {
		return;
	}
	if (m_heightMapRenderObj == nullptr) {
		m_heightMapRenderObj = NEW_REF(WBHeightMap, ());
		m_scene->Add_Render_Object(m_heightMapRenderObj);
	}
	Bool found = false;
	Bool isRoad = false;
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		if (found) break;
		if (pMapObjIn == pMapObj)
			found = true;
		if (pMapObjIn != nullptr && !found) {
			continue;
		}
		if (pMapObj->getFlags() & (FLAG_ROAD_FLAGS | FLAG_BRIDGE_FLAGS)) {
			isRoad = true;
			continue; // Roads don't create drawable objects.
		}
		if (pMapObj->isLight() || pMapObj->isScorch()) {
			continue;
		}

		Coord3D loc = *pMapObj->getLocation();
		loc.z += m_heightMapRenderObj->getHeightMapHeight(loc.x, loc.y, nullptr);
		const ThingTemplate *tTemplate = pMapObj->getThingTemplate();
		// Flux: EA batches KINDOF_OPTIMIZED_TREE objects through the terrain's tree buffer
		// (updateTrees); the first release draws them as ordinary models below.

		RenderObjClass *renderObj = nullptr;
		REF_PTR_SET(renderObj, pMapObj->getRenderObj());
		Int playerColor = 0xFFFFFF;
		Bool exists = false;
		if (tTemplate && !(pMapObj->getFlags() & FLAG_DONT_RENDER)) {
			AsciiString objectTeamName = pMapObj->getProperties()->getAsciiString(TheKey_originalOwner, &exists);
			if (exists) {
				TeamsInfo *teamInfo = TheSidesList->findTeamInfo(objectTeamName);
				if (teamInfo) {
					AsciiString teamOwner = teamInfo->getDict()->getAsciiString(TheKey_teamOwner);
					SidesInfo *pSide = TheSidesList->findSideInfo(teamOwner);
					if (pSide) {
						Bool hasColor = false;
						Int color = pSide->getDict()->getInt(TheKey_playerColor, &hasColor);
						if (hasColor) {
							playerColor = color;
						} else {
							AsciiString tmplname = pSide->getDict()->getAsciiString(TheKey_playerFaction);
							const PlayerTemplate *pt = ThePlayerTemplateStore->findPlayerTemplate(NAMEKEY(tmplname));
							if (pt) {
								playerColor = pt->getPreferredColor()->getAsInt();
							}
						}
					}
				}
			}
		}
		Int health = pMapObj->getProperties()->getInt(TheKey_objectInitialHealth, &exists);
		if (!exists) health = 100;
		Real ratio = health / 100.0;
		BodyDamageType curDamageState;
		if (ratio > TheGlobalData->m_unitDamagedThresh) curDamageState = BODY_PRISTINE;
		else if (ratio > TheGlobalData->m_unitReallyDamagedThresh) curDamageState = BODY_DAMAGED;
		else if (ratio > 0.0f) curDamageState = BODY_REALLYDAMAGED;
		else curDamageState = BODY_RUBBLE;

		if (!renderObj) {
			Real scale = 1.0;
			AsciiString modelName = getModelNameAndScale(pMapObj, &scale, curDamageState);
			if (modelName.isEmpty() == FALSE && strncmp(modelName.str(), "No ", 3)) {
				renderObj = m_assetManager->Create_Render_Obj(modelName.str(), scale, playerColor);
				// Flux: EA adds a shadow per object when "show shadows" is on (off by default).
			}
		}

		if (renderObj && !(pMapObj->getFlags() & FLAG_DONT_RENDER)) {
			pMapObj->setRenderObj(renderObj);
			pMapObj->setShadowObj(nullptr);
			Matrix3D renderObjPos(true);
			renderObjPos.Translate(loc.x, loc.y, loc.z);
			renderObjPos.Rotate_Z(pMapObj->getAngle());
			renderObj->Set_Transform(renderObjPos);
			// Flux: EA tilts vehicles to the ground normal (makeAlignToNormalMatrix); later.
			m_scene->Add_Render_Object(renderObj);
			REF_PTR_RELEASE(renderObj); // belongs to m_scene now.
		} else if (renderObj) {
			m_scene->Remove_Render_Object(renderObj);
			REF_PTR_RELEASE(renderObj);
		}
		if (found) break;
	}
	if (!found && pMapObjIn && pMapObjIn->getRenderObj()) {
		m_scene->Remove_Render_Object(pMapObjIn->getRenderObj());
		pMapObjIn->setRenderObj(nullptr);
	}
	if (isRoad) {
		m_needToLoadRoads = true;
	}
	m_dirty = true;
}

// ---------------------------------------------------------------------------------------------
// The document and the camera: setDefaultCamera, setupCamera, viewToDocCoords.
// ---------------------------------------------------------------------------------------------

void WbView3d::showDocument()
{
	CWorldBuilderDoc *pDoc = CWorldBuilderDoc::GetActiveDoc();
	WorldHeightMapEdit *pMap = pDoc ? pDoc->GetHeightMap() : nullptr;
	if (pMap == nullptr || !m_ww3dInited) {
		return;
	}
	resetRenderObjects();
	IRegion2D all;
	all.lo.x = all.lo.y = all.hi.x = all.hi.y = 0;
	updateHeightMapInView(pMap, false, all);
	Real x, y;
	pDoc->getStartCenter(&x, &y);
	m_centerPt.X = x / MAP_XY_FACTOR;
	m_centerPt.Y = y / MAP_XY_FACTOR;
	m_centerPt.Z = 0;
	// GeneralsX @feature Flux Build 07/11/2026 v1.4.1 round 3 Flux: EA's View > Show Boundaries, on: the
	// line around the playable area, so the border (black in the game) is not built on by mistake.
	DrawObject::setDoBoundaryFeedback(true);
	setDefaultCamera();
}

void WbView3d::setDefaultCamera()
{
	m_mouseWheelOffset = 0;
	m_cameraAngle = 0;
	m_FXPitch = 1.0f;
	constrainCenterPt();
	m_groundLevel = 10.0;
	Coord3D pos;
	pos.x = m_centerPt.X * MAP_XY_FACTOR;
	pos.y = m_centerPt.Y * MAP_XY_FACTOR;
	if (m_heightMapRenderObj) {
		m_groundLevel = m_heightMapRenderObj->getHeightMapHeight(pos.x, pos.y, nullptr);
	}
	m_groundSmoothValid = false;
	m_cameraOffset.z = m_groundLevel + TheGlobalData->m_maxCameraHeight;
	m_cameraOffset.y = -(m_cameraOffset.z / tan(TheGlobalData->m_cameraPitch * (PI / 180.0)));
	m_cameraOffset.x = -(m_cameraOffset.y * tan(TheGlobalData->m_cameraYaw * (PI / 180.0)));
	m_dirty = true;
}

void WbView3d::constrainCenterPt()
{
	CWorldBuilderDoc *pDoc = CWorldBuilderDoc::GetActiveDoc();
	WorldHeightMapEdit *pMap = pDoc ? pDoc->GetHeightMap() : nullptr;
	if (pMap == nullptr) return;
	// Flux: EA leaves this disabled (#if 0) with a mouse; a finger flings far, so keep the centre on
	// the map, borders included.
	const Real border = (Real)pMap->getBorderSize();
	m_centerPt.X = std::min(std::max(m_centerPt.X, -border), (Real)pMap->getXExtent() - border);
	m_centerPt.Y = std::min(std::max(m_centerPt.Y, -border), (Real)pMap->getYExtent() - border);
}

void WbView3d::setupCamera()
{
	Matrix3D camtransform(1);
	float zOffset = -m_mouseWheelOffset / 1200;
	Real zoom = 1.0f;
	if (zOffset != 0) {
		Real zPos = (m_cameraOffset.length() - m_groundLevel) / m_cameraOffset.length();
		Real zAbs = zOffset + zPos;
		if (zAbs < 0) zAbs = -zAbs;
		if (zAbs < 0.01) zAbs = 0.01f;
		if (zOffset > 0) {
			zOffset *= zAbs;
		} else if (zOffset < -0.3f) {
			zOffset = -0.15f + zOffset / 2.0f;
		}
		if (zOffset < -0.6f) {
			zOffset = -0.3f + zOffset / 2.0f;
		}
		zoom = zAbs;
	}

	Vector3 sourcePos, targetPos;
	Real angle = m_cameraAngle;
	Real pitch = 0;
	Coord3D pos;
	pos.x = m_centerPt.X * MAP_XY_FACTOR;
	pos.y = m_centerPt.Y * MAP_XY_FACTOR;
	pos.z = m_centerPt.Z * MAP_XY_FACTOR;
	Real groundLevel = m_heightMapRenderObj ? getHeightAroundPos(m_heightMapRenderObj, pos.x, pos.y) : 0;
	// Flux: EA uses the sampled height directly; with a mouse it changes in small steps, under a sweeping
	// finger it jumps between frames and the whole view bobs. It is eased toward the sample instead, and
	// the view keeps drawing until it has settled.
	if (!m_groundSmoothValid) {
		m_groundSmooth = groundLevel;
		m_groundSmoothValid = true;
	} else if (fabs(groundLevel - m_groundSmooth) > 0.05f) {
		m_groundSmooth += (groundLevel - m_groundSmooth) * 0.2f;
		m_dirty = true;
	} else {
		m_groundSmooth = groundLevel;
	}
	groundLevel = m_groundSmooth;

	sourcePos.X = m_cameraOffset.x * zoom;
	sourcePos.Y = m_cameraOffset.y * zoom;
	sourcePos.Z = m_cameraOffset.z * zoom;
	targetPos.X = 0;
	targetPos.Y = 0;
	targetPos.Z = 0;
	Real factor = 1.0 - (groundLevel / sourcePos.Z);

	Matrix3D angleTransform(Vector3(0.0f, 0.0f, 1.0f), angle);
	Matrix3D pitchTransform(Vector3(1.0f, 0.0f, 0.0f), pitch);
	pitchTransform.mulVector3(sourcePos);
	angleTransform.mulVector3(sourcePos);
	sourcePos *= factor;

	sourcePos.X += pos.x;
	sourcePos.Y += pos.y;
	sourcePos.Z += pos.z + groundLevel;
	targetPos.X += pos.x;
	targetPos.Y += pos.y;
	targetPos.Z += pos.z + groundLevel;

	Real height = sourcePos.Z - targetPos.Z;
	height *= m_FXPitch;
	targetPos.Z = sourcePos.Z - height;

	m_cameraSource = sourcePos;
	m_cameraTarget = targetPos;

	camtransform.Make_Identity();
	if (factor < 0) { //WST 11/11/02. Fix camera flipping over when near the ground too early
		targetPos = sourcePos + (sourcePos - targetPos);
	}
	camtransform.Look_At(sourcePos, targetPos, 0);
	targetPos.Z = 0;
	Real lookDistance = (targetPos - sourcePos).Length();
	if (lookDistance < 300) lookDistance = 300;
	m_camera->Set_Clip_Planes(lookDistance / 200, lookDistance * 3);
	if (m_heightMapRenderObj) {
		m_heightMapRenderObj->setFlattenHeights(false);
	}
	m_camera->Set_Transform(camtransform);
	if (m_heightMapRenderObj) {
		m_heightMapRenderObj->setDrawEntireMap(true);
	}
	// Flux: EA sets the aspect when its window is sized; a phone rotates, so it is set every frame.
	if (m_actualWinSize.y > 0) {
		m_camera->Set_Aspect_Ratio((float)m_actualWinSize.x / (float)m_actualWinSize.y);
	}
}

bool WbView3d::screenToGround(Real x, Real y, Coord3D *world)
{
	// The camera's own conversion: always the terrain under the point.
	if (!m_ww3dInited || m_actualWinSize.x <= 0 || m_actualWinSize.y <= 0) {
		return false;
	}
	float logX = x / (Real)m_actualWinSize.x;
	float logY = y / (Real)m_actualWinSize.y;
	Matrix3D camera_matrix = m_camera->Get_Transform();
	Vector3 rayLocation = m_camera->Get_Position();
	const ViewportClass &viewport = m_camera->Get_Viewport();
	Vector2 min, max;
	m_camera->Get_View_Plane(min, max);
	float xscale = (max.X - min.X);
	float yscale = (max.Y - min.Y);
	float zmod = -1.0;
	float xmod = (-logX + 0.5 + viewport.Min.X) * zmod * xscale;
	float ymod = (logY - 0.5 - viewport.Min.Y) * zmod * yscale;
	float rx = zmod * camera_matrix[0][2] + xmod * camera_matrix[0][0] + ymod * camera_matrix[0][1];
	float ry = zmod * camera_matrix[1][2] + xmod * camera_matrix[1][0] + ymod * camera_matrix[1][1];
	float rz = zmod * camera_matrix[2][2] + xmod * camera_matrix[2][0] + ymod * camera_matrix[2][1];
	Vector3 rayDirection(rx, ry, rz);
	rayDirection.Normalize();
	float MaxDistance = m_camera->Get_Depth() * MAP_XY_FACTOR;
	Vector3 rayDirectionPt = rayLocation + rayDirection * MaxDistance;
	LineSegClass ray(rayLocation, rayDirectionPt);
	Vector3 intersection(0, 0, 0);
	bool result = false;
	if (TheTerrainRenderObject) {
		CastResultStruct castResult;
		RayCollisionTestClass rayCollide(ray, &castResult);
		if (TheTerrainRenderObject->Cast_Ray(rayCollide)) {
			intersection = castResult.ContactPoint;
			result = true;
		}
	}
	if (!result) {
		// Off the terrain: the ground plane, as EA's fixed-Z tracking.
		intersection.X = Vector3::Find_X_At_Z(0, rayLocation, rayDirectionPt);
		intersection.Y = Vector3::Find_Y_At_Z(0, rayLocation, rayDirectionPt);
		intersection.Z = 0;
	}
	world->x = intersection.X;
	world->y = intersection.Y;
	world->z = intersection.Z;
	return result;
}

/** WbView3d::viewToDocCoords: tools that modify the terrain track a fixed height (EA). */
Bool WbView3d::viewToDocCoords(CPoint curPt, Coord3D *newPt, Bool constrain)
{
	(void)constrain;
	Coord3D hit;
	Bool followTerrain = true;
	if (WbApp()->isCurToolLocked() && WbApp()->getCurTool()) {
		followTerrain = WbApp()->getCurTool()->followsTerrain();
	}
	Bool onTerrain = false;
	if (followTerrain) {
		onTerrain = screenToGround((Real)curPt.x, (Real)curPt.y, &hit);
		if (onTerrain) {
			m_curTrackingZ = hit.z;
		}
	}
	if (!onTerrain) {
		// EA: a fixed z plane at the last tracked height.
		Coord3D ground;
		screenToGround((Real)curPt.x, (Real)curPt.y, &ground);
		hit = ground;
		if (m_camera) {
			Vector3 from = m_camera->Get_Position();
			Vector3 to(ground.x, ground.y, ground.z);
			if (fabs(to.Z - from.Z) > 0.001f) {
				hit.x = Vector3::Find_X_At_Z(m_curTrackingZ, from, to);
				hit.y = Vector3::Find_Y_At_Z(m_curTrackingZ, from, to);
			}
		}
	}
	newPt->x = hit.x;
	newPt->y = hit.y;
	newPt->z = MAGIC_GROUND_Z;
	return true;
}

/** WbView3d::docToViewCoords (EA). */
Bool WbView3d::docToViewCoords(Coord3D curPt, CPoint *newPt)
{
	Bool coordInsideFrustum = true;
	Vector3 world;
	Vector3 screen;
	newPt->x = -1000;
	newPt->y = -1000;
	if (!m_camera) return false;
	if (m_heightMapRenderObj) {
		curPt.z += m_heightMapRenderObj->getHeightMapHeight(curPt.x, curPt.y, nullptr);
	}
	world.Set(curPt.x, curPt.y, curPt.z);
	coordInsideFrustum = (m_camera->Project(screen, world) == CameraClass::INSIDE_FRUSTUM);
	Int sx, sy;
	W3DLogicalScreenToPixelScreen(screen.X, screen.Y, &sx, &sy, m_actualWinSize.x, m_actualWinSize.y);
	newPt->x = sx;
	newPt->y = sy;
	return coordInsideFrustum;
}

/** WbView3d::picked3dObjectInView (EA). */
MapObject *WbView3d::picked3dObjectInView(CPoint viewPt)
{
	if (m_intersector && m_layer && m_actualWinSize.x > 0 && m_actualWinSize.y > 0) {
		float logX = (Real)viewPt.x / (Real)m_actualWinSize.x;
		float logY = (Real)viewPt.y / (Real)m_actualWinSize.y;
		Bool hit = m_intersector->Intersect_Screen_Point_Layer(logX, logY, *m_layer);
		if (hit) {
			for (MapObject *pObj = MapObject::getFirstMapObject(); pObj; pObj = pObj->getNext()) {
				if (pObj->getRenderObj() == m_intersector->Result.IntersectedRenderObject) {
					return pObj;
				}
			}
		}
	}
	return nullptr;
}

/** WbView3d::setObjTracking (EA). */
void WbView3d::setObjTracking(MapObject *pMapObj, Coord3D pos, Real angle, Bool show)
{
	m_showObjToolTrackingObj = show;
	m_dirty = true;
	if (!show || pMapObj == nullptr || !m_ww3dInited) return;
	Real scale;
	AsciiString modelName = getModelNameAndScale(pMapObj, &scale, BODY_PRISTINE);
	if (modelName != m_objectToolTrackingModelName) {
		m_objectToolTrackingModelName = modelName;
		REF_PTR_RELEASE(m_objectToolTrackingObj);
		m_objectToolTrackingObj = m_assetManager->Create_Render_Obj(modelName.str(), scale, 0);
	}
	if (m_objectToolTrackingObj == nullptr || m_heightMapRenderObj == nullptr) {
		return;
	}
	pos.z += m_heightMapRenderObj->getHeightMapHeight(pos.x, pos.y, nullptr);
	Matrix3D renderObjPos(true);
	renderObjPos.Translate(pos.x, pos.y, pos.z);
	renderObjPos.Rotate_Z(angle);
	m_objectToolTrackingObj->Set_Transform(renderObjPos);
}

void WbView3d::screenRay(Real x, Real y, Vector3 *from, Vector3 *to)
{
	float logX = x / (Real)m_actualWinSize.x;
	float logY = y / (Real)m_actualWinSize.y;
	Matrix3D camera_matrix = m_camera->Get_Transform();
	Vector3 rayLocation = m_camera->Get_Position();
	const ViewportClass &viewport = m_camera->Get_Viewport();
	Vector2 min, max;
	m_camera->Get_View_Plane(min, max);
	float xscale = (max.X - min.X);
	float yscale = (max.Y - min.Y);
	float zmod = -1.0;
	float xmod = (-logX + 0.5 + viewport.Min.X) * zmod * xscale;
	float ymod = (logY - 0.5 - viewport.Min.Y) * zmod * yscale;
	Vector3 dir(zmod * camera_matrix[0][2] + xmod * camera_matrix[0][0] + ymod * camera_matrix[0][1],
		zmod * camera_matrix[1][2] + xmod * camera_matrix[1][0] + ymod * camera_matrix[1][1],
		zmod * camera_matrix[2][2] + xmod * camera_matrix[2][0] + ymod * camera_matrix[2][1]);
	dir.Normalize();
	*from = rayLocation;
	*to = rayLocation + dir * (m_camera->Get_Depth() * MAP_XY_FACTOR);
}

void WbView3d::panByPixels(Real dx, Real dy)
{
	// Flux: the screen centre and the centre moved by (dx, dy) are taken on ONE flat plane at the
	// camera's (eased) ground level, not on the terrain: on the terrain the two points sit on different
	// slopes from one step to the next and the map shudders. On the plane the point under the finger
	// stays under the finger, smoothly, over hills and valleys alike.
	if (!m_ww3dInited || m_camera == nullptr || m_actualWinSize.x <= 0 || m_actualWinSize.y <= 0) return;
	const Real cx = m_actualWinSize.x * 0.5f, cy = m_actualWinSize.y * 0.5f;
	const Real z = m_groundSmoothValid ? m_groundSmooth : 0;
	Vector3 f1, t1, f2, t2;
	screenRay(cx, cy, &f1, &t1);
	screenRay(cx - dx, cy - dy, &f2, &t2);
	// Flux (review): both rays must come down to the plane in front of the camera. A point at or
	// above the horizon (low pitch) would meet it behind the camera or far away and throw the view
	// to the map's edge -- that part of the move is skipped.
	if (t1.Z > f1.Z - 0.001f || t2.Z > f2.Z - 0.001f || f1.Z <= z || f2.Z <= z || t1.Z > z || t2.Z > z) return;
	const Real ax = Vector3::Find_X_At_Z(z, f1, t1), ay = Vector3::Find_Y_At_Z(z, f1, t1);
	const Real bx = Vector3::Find_X_At_Z(z, f2, t2), by = Vector3::Find_Y_At_Z(z, f2, t2);
	m_centerPt.X += (bx - ax) / MAP_XY_FACTOR;
	m_centerPt.Y += (by - ay) / MAP_XY_FACTOR;
	constrainCenterPt();
	m_dirty = true;
}

void WbView3d::zoomBy(Real factor)
{
	// EA zooms with the mouse wheel (120 per notch, scaled by 1/1200); a pinch maps onto the same
	// offset, inside the range the wheel reaches comfortably.
	if (factor <= 0) return;
	m_mouseWheelOffset -= 1200.0f * logf(factor);
	m_mouseWheelOffset = std::min(std::max(m_mouseWheelOffset, -1100.0f), 1800.0f);
	m_dirty = true;
}

void WbView3d::rotateBy(Real radians)
{
	m_cameraAngle += radians;
	m_dirty = true;
}

// ---------------------------------------------------------------------------------------------
// WbView3d::redraw + render.
// ---------------------------------------------------------------------------------------------

void WbView3d::redraw()
{
	if (!m_ww3dInited || m_camera == nullptr) {
		return;
	}
	setupCamera();
	if (m_heightMapRenderObj) {
		if (m_needToLoadRoads) {
			m_heightMapRenderObj->loadRoadsAndBridges(nullptr, FALSE);
			m_heightMapRenderObj->worldBuilderUpdateBridgeTowers(m_assetManager, m_scene);
			m_needToLoadRoads = false;
		}
		m_heightMapRenderObj->updateCenter(m_camera, &m_cameraTarget, nullptr);
		m_heightMapRenderObj->On_Frame_Update();
	}
	if (m_drawObject) {
		// EA's defaults: objects, waypoints and polygon triggers shown; the debug overlays off.
		m_drawObject->setDrawObjects(true, true, true, false, false, false, false, false, false);
	}
	WW3D::Update_Logic_Frame_Time(TheFramePacer->getLogicTimeStepMilliseconds());
	WW3D::Sync(WW3D::Get_Fractional_Sync_Milliseconds() >= WWSyncMilliseconds);
	render();
	TheFramePacer->update();
	m_dirty = false;
}

void WbView3d::render()
{
	if (WW3D::Begin_Render(true, true, Vector3(0.5f, 0.5f, 0.5f), TheWaterTransparency->m_minWaterOpacity) == WW3D_ERROR_OK) {
		if (m_heightMapRenderObj) {
			m_heightMapRenderObj->Set_Hidden(0);
			m_heightMapRenderObj->doTextures(true);
		}
		m_scene->Set_Polygon_Mode(SceneClass::FILL);
		WW3D::Render(m_scene, m_camera);
		WW3D::Render(m_baseBuildScene, m_camera);
		if (m_showObjToolTrackingObj && m_objectToolTrackingObj) {
			m_transparentObjectsScene->Add_Render_Object(m_objectToolTrackingObj);
			DX8TextureCategoryClass::SetForceMultiply(true);
			TheDX8MeshRenderer.Enable_Lighting(false);
			Real lightLevel = 1.0f;
			m_transparentObjectsScene->Set_Ambient_Light(Vector3(lightLevel, lightLevel, lightLevel));
			WW3D::Render(m_transparentObjectsScene, m_camera);
			TheDX8MeshRenderer.Enable_Lighting(true);
			DX8TextureCategoryClass::SetForceMultiply(false);
			m_transparentObjectsScene->Remove_Render_Object(m_objectToolTrackingObj);
		}
		// Draw the 3d obj icons on top of the rest of the data.
		WW3D::Render(m_overlayScene, m_camera);
		WW3D::End_Render();
	}
}
