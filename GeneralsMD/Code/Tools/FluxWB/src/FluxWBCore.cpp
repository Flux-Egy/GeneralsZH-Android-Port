// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: see FluxWBCore.h. The subsystem list and its
// order are EA's (CWorldBuilderApp::InitInstance); each departure is marked "Flux:".

#include "StdAfx.h"

#include "FluxWBCore.h"
#include "LayersList.h"
#include "WHeightMapEdit.h"

#include "Common/ArchiveFileSystem.h"
#include "Common/DamageFX.h"
#include "Common/FileSystem.h"
#include "Common/FramePacer.h"
#include "Common/GameAudio.h"
#include "Common/GlobalData.h"
#include "Common/INI.h"
#include "Common/LocalFileSystem.h"
#include "Common/ModuleFactory.h"
#include "Common/MultiplayerSettings.h"
#include "Common/NameKeyGenerator.h"
#include "Common/PlayerTemplate.h"
#include "Common/Science.h"
#include "Common/SpecialPower.h"
#include "Common/SubsystemInterface.h"
#include "Common/TerrainTypes.h"
#include "Common/ThingFactory.h"
#include "Common/Upgrade.h"
#include "GameClient/Anim2D.h"
#include "GameClient/FXList.h"
#include "GameClient/GameText.h"
#include "GameClient/ParticleSys.h"
#include "GameClient/TerrainRoads.h"
#include "GameClient/Water.h"
#include "GameLogic/Armor.h"
#include "GameLogic/CaveSystem.h"
#include "GameLogic/CrateSystem.h"
#include "GameLogic/Locomotor.h"
#include "GameLogic/ObjectCreationList.h"
#include "GameLogic/PolygonTrigger.h"
#include "GameLogic/RankInfo.h"
#include "GameLogic/ScriptEngine.h"
#include "GameLogic/SidesList.h"
#include "GameLogic/Weapon.h"
#include "OpenALAudioDevice/OpenALAudioManager.h"
#include "StdDevice/Common/StdBIGFileSystem.h"
#include "StdDevice/Common/StdLocalFileSystem.h"
#include "W3DDevice/Common/W3DModuleFactory.h"
#include "W3DDevice/GameClient/W3DFileSystem.h"
#include "W3DDevice/GameClient/W3DParticleSys.h"
#include "W3DDevice/GameClient/W3DShadow.h"

#include <cstdlib>
#include <sys/stat.h>
#include <unistd.h>

extern void initMemoryManager();
extern void shutdownMemoryManager();
void FluxWB_InitGlobals(); // FluxWBGlobals.cpp

namespace FluxWB {

namespace {

SubsystemInterfaceList *s_subsystems = nullptr;
bool s_started = false;
bool s_headless = false;
MessageSink s_messageSink = nullptr;
EventSink s_eventSink = nullptr;

template<class SUBSYSTEM>
void initSubsystem(SUBSYSTEM *&sysref, SUBSYSTEM *sys, const char *path1 = nullptr, const char *path2 = nullptr)
{
	sysref = sys;
	s_subsystems->initSubsystem(sys, path1, path2, nullptr);
}

std::string withSlash(std::string dir)
{
	if (!dir.empty() && dir.back() != '/') {
		dir += '/';
	}
	return dir;
}

} // namespace

void reportMessage(const char *text)
{
	if (s_messageSink != nullptr) {
		s_messageSink(text);
	}
}

void setMessageSink(MessageSink sink)
{
	s_messageSink = sink;
}

void setEventSink(EventSink sink)
{
	s_eventSink = sink;
}

void reportSelection(const char *objectName)
{
	if (s_eventSink) s_eventSink("object", objectName ? objectName : "");
}

void reportWaypoint(const char *waypointName)
{
	if (s_eventSink) s_eventSink("waypoint", waypointName ? waypointName : "");
}

void reportArea(const char *areaName)
{
	if (s_eventSink) s_eventSink("area", areaName ? areaName : "");
}

bool engineStarted()
{
	return s_started;
}

bool startEngine(const StartOptions &options, std::string *error)
{
	if (s_started) {
		return true;
	}
	if (options.gameDir.empty() || chdir(options.gameDir.c_str()) != 0) {
		if (error) *error = "cannot enter the game folder " + options.gameDir;
		return false;
	}
	// Flux: the user-data folder comes from the caller, as GENERALSX_USERDATA_DIR does for the game
	// (GlobalData::BuildUserDataPathFromRegistry). The maps go to its Maps/.
	if (!options.userDataDir.empty()) {
		mkdir(options.userDataDir.c_str(), 0755);
		setenv("GENERALSX_USERDATA_DIR", options.userDataDir.c_str(), 1);
	}
	// Flux: the editor makes no sound. OpenAL's null device keeps TheAudio (whose INI the object
	// templates refer to) without opening the speaker.
	setenv("ALSOFT_DRIVERS", "null", 1);

	try {
		FluxWB_InitGlobals();
		initMemoryManager();
		s_subsystems = new SubsystemInterfaceList;

		// not part of the subsystem list, because it should normally never be reset!
		TheNameKeyGenerator = new NameKeyGenerator;
		TheNameKeyGenerator->init();

		TheFileSystem = new FileSystem;
		// Flux: StdLocalFileSystem / StdBIGFileSystem, as the game uses on Android
		// (SDL3GameEngine::createLocalFileSystem / createArchiveFileSystem).
		initSubsystem(TheLocalFileSystem, (LocalFileSystem *)new StdLocalFileSystem);
		initSubsystem(TheArchiveFileSystem, (ArchiveFileSystem *)new StdBIGFileSystem);

		INI ini;
		initSubsystem(TheWritableGlobalData, new GlobalData(), "Data\\INI\\Default\\GameData", "Data\\INI\\GameData");
		TheWritableGlobalData->m_headless = options.headless;

		TheFramePacer = new FramePacer();

		// ensure the user maps dir exists
		{
			std::string maps = withSlash(TheGlobalData->getPath_UserData().str()) + "Maps";
			mkdir(maps.c_str(), 0755);
		}

		// read the water settings from INI (must do prior to initing GameClient, apparently)
		ini.loadFileDirectory("Data\\INI\\Default\\Water", INI_LOAD_OVERWRITE, nullptr);
		ini.loadFileDirectory("Data\\INI\\Water", INI_LOAD_OVERWRITE, nullptr);

		initSubsystem(TheGameText, CreateGameTextInterface());
		initSubsystem(TheScienceStore, new ScienceStore(), "Data\\INI\\Default\\Science", "Data\\INI\\Science");
		initSubsystem(TheMultiplayerSettings, new MultiplayerSettings(), "Data\\INI\\Default\\Multiplayer", "Data\\INI\\Multiplayer");
		initSubsystem(TheTerrainTypes, new TerrainTypeCollection(), "Data\\INI\\Default\\Terrain", "Data\\INI\\Terrain");
		initSubsystem(TheTerrainRoads, new TerrainRoadCollection(), "Data\\INI\\Default\\Roads", "Data\\INI\\Roads");

		WorldHeightMapEdit::init();

		initSubsystem(TheScriptEngine, (ScriptEngine *)(new ScriptEngine()));
		TheScriptEngine->turnBreezeOff(); // stop the tree sway.
		ini.loadFileDirectory("Data\\Scripts\\Scripts", INI_LOAD_OVERWRITE, nullptr);

		// Flux: OpenAL (null device) instead of Miles; no video player (the editor plays no movie).
		initSubsystem(TheAudio, (AudioManager *)new OpenALAudioManager());
		initSubsystem(TheModuleFactory, (ModuleFactory *)(new W3DModuleFactory()));
		initSubsystem(TheSidesList, new SidesList());
		initSubsystem(TheCaveSystem, new CaveSystem());
		initSubsystem(TheRankInfoStore, new RankInfoStore(), nullptr, "Data\\INI\\Rank");
		initSubsystem(ThePlayerTemplateStore, new PlayerTemplateStore(), "Data\\INI\\Default\\PlayerTemplate", "Data\\INI\\PlayerTemplate");
		initSubsystem(TheSpecialPowerStore, new SpecialPowerStore(), "Data\\INI\\Default\\SpecialPower", "Data\\INI\\SpecialPower");
		if (options.headless) {
			initSubsystem(TheParticleSystemManager, (ParticleSystemManager *)(new ParticleSystemManagerDummy()));
		} else {
			initSubsystem(TheParticleSystemManager, (ParticleSystemManager *)(new W3DParticleSystemManager()));
		}
		initSubsystem(TheFXListStore, new FXListStore(), "Data\\INI\\Default\\FXList", "Data\\INI\\FXList");
		initSubsystem(TheWeaponStore, new WeaponStore(), nullptr, "Data\\INI\\Weapon");
		initSubsystem(TheObjectCreationListStore, new ObjectCreationListStore(), "Data\\INI\\Default\\ObjectCreationList", "Data\\INI\\ObjectCreationList");
		initSubsystem(TheLocomotorStore, new LocomotorStore(), nullptr, "Data\\INI\\Locomotor");
		initSubsystem(TheDamageFXStore, new DamageFXStore(), nullptr, "Data\\INI\\DamageFX");
		initSubsystem(TheArmorStore, new ArmorStore(), nullptr, "Data\\INI\\Armor");
		initSubsystem(TheThingFactory, new ThingFactory(), "Data\\INI\\Default\\Object", "Data\\INI\\Object");
		initSubsystem(TheCrateSystem, new CrateSystem(), "Data\\INI\\Default\\Crate", "Data\\INI\\Crate");
		initSubsystem(TheUpgradeCenter, new UpgradeCenter, "Data\\INI\\Default\\Upgrade", "Data\\INI\\Upgrade");
		initSubsystem(TheAnim2DCollection, new Anim2DCollection); //Init's itself.

		s_subsystems->postProcessLoadAll();

		// Flux: EA's WB_W3DFileSystem only adds WB-folder files the editor's own art lives in.
		TheW3DFileSystem = new W3DFileSystem;
		TheLayersList = new LayersList;

		// Just to be sure - wb doesn't do well with half res terrain.
		TheWritableGlobalData->m_useHalfHeightMap = false;
#if ENABLE_CONFIGURABLE_SHROUD
		// WB never uses the shroud. With shroud, terrain is black.
		TheWritableGlobalData->m_shroudOn = FALSE;
#endif
		TheWritableGlobalData->m_isWorldBuilder = TRUE;

		// Flux: EA creates the shadow manager with the 3D view (WbView3d::initWW3D). The map's
		// shadow colour is kept only in it -- read into it on loading, written from it on saving -- so
		// it must exist before any map is opened, with a view or without. Its constructor needs no
		// 3D device; init() (the GPU side) is called by the view.
		if (TheW3DShadowManager == nullptr) {
			TheW3DShadowManager = new W3DShadowManager;
		}
	} catch (...) {
		if (error) *error = "the game data could not be loaded (see the log)";
		return false;
	}
	s_started = true;
	s_headless = options.headless;
	fprintf(stderr, "[fluxwb] engine started in %s, user data %s\n", options.gameDir.c_str(),
		TheGlobalData->getPath_UserData().str());
	return true;
}

void stopEngine()
{
	if (!s_started) {
		return;
	}
	s_started = false;
	PolygonTrigger::deleteTriggers();
	ScriptList::reset();
	// The shadow manager's destructor releases GPU resources. The view deletes it (before WW3D
	// shuts down) when it set it up; if no view ever started -- the round-trip tool, or a device that
	// failed -- it has no GPU side and is left to the process exit rather than torn down without one.
	(void)s_headless;
	s_subsystems->shutdownAll();
	WorldHeightMapEdit::shutdown();
	delete TheLayersList;
	TheLayersList = nullptr;
	delete TheFramePacer;
	TheFramePacer = nullptr;
	delete TheFileSystem;
	TheFileSystem = nullptr;
	delete TheW3DFileSystem;
	TheW3DFileSystem = nullptr;
	delete TheNameKeyGenerator;
	TheNameKeyGenerator = nullptr;
	delete s_subsystems;
	s_subsystems = nullptr;
	shutdownMemoryManager();
}

} // namespace FluxWB
