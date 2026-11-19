// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

#include "GameNetwork/GeneralsOnline/NGMPGame.h"
// GeneralsX @bugfix Android port 16/09/2026 This is the only unconditional
// P2P-transport dependency in this file -- every actual use of the complete
// NetworkMesh type below is already gated on GENERALS_ONLINE_ENABLE_P2P_TRANSPORT
// (pointer-only uses work fine against the forward declaration in
// NGMP_include.h/OnlineServices_Init.h), but this raw #include still pulled in
// the real definition -- and with it <steam/isteamnetworkingutils.h> -- on every
// platform, including the ones where GameNetworkingSockets is never linked
// (see the CMakeLists.txt if(ANDROID) guard on that library). Gate the include
// itself the same way.
#if defined(GENERALS_ONLINE_ENABLE_P2P_TRANSPORT)
#include "GameNetwork/GeneralsOnline/NetworkMesh.h"
#endif // GENERALS_ONLINE_ENABLE_P2P_TRANSPORT
#include "GameLogic/VictoryConditions.h"
#include "Common/PlayerList.h"
#include "GameLogic/GameLogic.h"
#include "GameNetwork/FileTransfer.h"
#include "GameClient/MapUtil.h"
#include "GameClient/GameText.h"
#include "GameNetwork/GameSpyOverlay.h"
#include "Common/RandomValue.h"
#include "GameNetwork/GeneralsOnline/NGMP_interfaces.h"
#include "GameNetwork/NetworkInterface.h"
#include "Common/GlobalData.h"
#include "GameClient/View.h"
#include "GameNetwork/GeneralsOnline/NextGenMP_defines.h"

NGMPGameSlot::NGMPGameSlot()
{
	GameSlot();
	m_profileID = 0;
	m_wins = 0;
	m_losses = 0;
	m_rankPoints = 0;
	m_favoriteSide = 0;
	m_pingInt = 0;
	m_profileID = 0;
	m_pingStr.clear();
}

// NGMPGame ----------------------------------------

NGMPGame::NGMPGame()
{
	NGMP_OnlineServices_LobbyInterface* pLobbyInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_LobbyInterface>();
	if (pLobbyInterface == nullptr)
	{
		return;
	}

	cleanUpSlotPointers();

	setLocalIP(0);

	m_ladderIP.clear();
	m_ladderPort = 0;

	enterGame(); // this is done on join in the GS impl, and must be called before setMap

	// NGMP: Store map
	setMap(pLobbyInterface->GetCurrentLobbyMapPath());

	// init
	//init();

	// NGMP: Populate slots
	UpdateSlotsFromCurrentLobby();
}

NGMPGame::~NGMPGame()
{
	// Force camera to update from config
	// GeneralsX @bugfix Android port 10/07/2026 upstream's setDefaultView grew
	// a 4th (bool) param in a commit outside this port's scope (View.h/W3DView.h
	// are existing engine files, not part of the GeneralsOnline module) -- our
	// tree still has the 3-arg signature.
	// GeneralsX @bugfix Android port 13/07/2026 - a literal 0.0f pitch is not
	// "leave the pitch alone": W3DView::setDefaultView() passes it straight
	// through to setDefaultPitch(), which stores it directly as the camera's
	// tilt in radians. 0.0f means "look dead level at the horizon" instead of
	// down at the battlefield, which is exactly the "first-person" camera a
	// real device screenshot showed right as a match started. The correct
	// value (confirmed by View::init()'s own default-pitch setup a few lines
	// away in View.cpp) is TheGlobalData->m_cameraPitch, converted from
	// degrees (how it's stored/configured) to radians.
	//
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 Guarded: this destructor runs
	// during match teardown, and whether TheTacticalView (a subsystem with its own
	// shutdown order) is still alive at that point depends on the path that ended the
	// game. The old unconditional call dereferenced it after destruction on the
	// disconnect/listen-server paths.
	if (TheTacticalView != nullptr && TheGlobalData != nullptr)
	{
		TheTacticalView->setDefaultView(DEG_TO_RADF(TheGlobalData->m_cameraPitch), 0.0f, 1.0f);
	}
}

void NGMPGame::SyncWithLobby(LobbyEntry& lobby)
{
	// GeneralsX @bugfix Flux Build v1.2.0 27/10/2026 Use MapCache::findMap tiered lookup
	// to robustly match official or custom maps (by full path, relative path, or leaf filename).
	const MapMetaData *md = TheMapCache ? TheMapCache->findMap(lobby.map_path.c_str()) : nullptr;

	// GeneralsX @bugfix Flux Build v1.2.6 28/10/2026 A cache entry is only a valid CRC
	// baseline when the map it describes can still be read. findMap() deliberately does not
	// check m_doesExist (it also serves callers that only need metadata), and the cache is
	// loaded once at startup, so a deleted or replaced user map kept its old CRC here. When
	// the host then changed the lobby map (Vendetta -> Fort Thunder) the old else branch
	// left the *previous* map's CRC in m_mapCRC, and launchGame() compared the new file
	// against it. Official maps live in BIG archives and are always readable through
	// TheFileSystem, so only custom maps need the loose-file test.
	const Bool cacheValid = (md != nullptr)
		&& md->m_doesExist
		&& (md->m_isOfficial || CanSendMapFile(md->m_fileName));

	const UnsignedInt baselineCRC = cacheValid ? md->m_CRC : 0;
	const UnsignedInt baselineSize = cacheValid ? md->m_filesize : 0;

	if (cacheValid && TheNGMPGame && TheNGMPGame->getGameSpySlot(0))
	{
		TheNGMPGame->getGameSpySlot(0)->setMapAvailability(TRUE);
	}

	// Always write the baseline, including the zero. Clearing it is what stops the previous
	// map's CRC from surviving a lobby map change or a deleted cache entry.
	setMapCRC(baselineCRC);
	setMapSize(baselineSize);
	setMap(cacheValid ? md->m_fileName : AsciiString(lobby.map_path.c_str()));

	// GeneralsX @feature Flux Build v1.2.6 28/10/2026 Log the baseline only when it changes:
	// SyncWithLobby() runs on every lobby refresh, and a line per refresh would drown the log.
	{
		static AsciiString lastSyncMap;
		static UnsignedInt lastSyncCRC = 0;
		static Bool lastSyncValid = FALSE;
		const AsciiString syncMap(lobby.map_path.c_str());
		if (lastSyncMap.compareNoCase(syncMap) != 0 || lastSyncCRC != baselineCRC || lastSyncValid != cacheValid)
		{
			fprintf(stderr, "[GX-MAP-XFER] lobby sync: map='%s' cache=%d file=%d crc=0x%08X size=%d\n",
				lobby.map_path.c_str(), md != nullptr ? 1 : 0, cacheValid ? 1 : 0, baselineCRC, baselineSize);
			fflush(stderr);
			lastSyncMap = syncMap;
			lastSyncCRC = baselineCRC;
			lastSyncValid = cacheValid;
		}
	}

	// superweapon
	setSuperweaponRestriction(lobby.limit_superweapons);

	// vanilla teams
	setOldFactionsOnly(lobby.vanilla_teams);

	// stats
	setUseStats(lobby.track_stats);

	// rng seed
	setSeed(lobby.rng_seed);

	// observers
	setAllowObservers(lobby.allow_observers);

	setHasPassword(lobby.passworded);

	setExeCRC(lobby.exe_crc);
	setIniCRC(lobby.ini_crc);

	UnicodeString lobbyName = UnicodeString(from_utf8(lobby.name).c_str());
	setGameName(lobbyName);

	// starting cash
	Money startingCash;
	startingCash.deposit(lobby.starting_cash, FALSE);
	setStartingCash(startingCash);

}

void NGMPGame::UpdateSlotsFromCurrentLobby()
{
	// none of this should change while in-game, so ignore

	// NOTE: In progress means game has started, in-game just means in the lobby/fronend...
	if (m_inProgress)
	{
		return;
	}

	NGMP_OnlineServices_LobbyInterface* pLobbyInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_LobbyInterface>();
	if (pLobbyInterface == nullptr)
	{
		return;
	}

	for (Int i = 0; i < MAX_SLOTS; ++i)
	{
		// this list is provided by the service, ordered by slot index, so we dont need to look up / use the slot index from the member
		LobbyMemberEntry pLobbyMember = pLobbyInterface->GetRoomMemberFromIndex(i);

		// TODO_NGMP: Support spectators
		int playerTemplate = -1;
		if (pLobbyMember.side == -1)
		{
			playerTemplate = PLAYERTEMPLATE_RANDOM;
		}
		else
		{
			playerTemplate = pLobbyMember.side;
		}

		// human or AI player
		if (pLobbyMember.m_SlotState != SlotState::SLOT_OPEN && pLobbyMember.m_SlotState != SlotState::SLOT_CLOSED)
		{
			bool bIsAI = (pLobbyMember.m_SlotState == SlotState::SLOT_EASY_AI || pLobbyMember.m_SlotState == SlotState::SLOT_MED_AI|| pLobbyMember.m_SlotState == SlotState::SLOT_BRUTAL_AI);

			// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 The service's member list is
			// already ordered by slot index (see the note above), so the loop index is the
			// safe key. The member's own m_SlotIndex is still preferred when it is in range
			// (some services omit it, or send a sentinel for AI slots), but an out-of-range
			// value used to reach getSlot(), which returns nullptr, and the unchecked
			// dereference below crashed the client on lobby entry.
			const Int slotIndex =
				(pLobbyMember.m_SlotIndex >= 0 && pLobbyMember.m_SlotIndex < MAX_SLOTS)
					? (Int)pLobbyMember.m_SlotIndex : i;
			NGMPGameSlot* slot = (NGMPGameSlot*)getSlot(slotIndex);
			if (slot == nullptr)
			{
				continue;
			}

			// NOTE: Internally generals uses 'local ip' to detect which user is local... we dont have an IP, so just use player index for ip
			slot->setState((SlotState)pLobbyMember.m_SlotState, UnicodeString(from_utf8(pLobbyMember.display_name).c_str()), slotIndex);

			slot->setColor(pLobbyMember.color);
			slot->setTeamNumber(pLobbyMember.team);
			slot->setStartPos(pLobbyMember.startpos);
			slot->setPlayerTemplate(playerTemplate);

			if (!bIsAI)
			{
				// ready flag
				if (pLobbyMember.m_bIsReady)
				{
					slot->setAccept();
				}
				else
				{
					slot->unAccept();
				}

				// has map?
				slot->setMapAvailability(pLobbyMember.has_map);

				// store EOS ID
				slot->m_userID = pLobbyMember.user_id;
			}
			else
			{
				slot->setAccept();
				slot->setMapAvailability(true);
				slot->m_userID = -1;
			}
		}
		else
		{
			// handle open/closed
			NGMPGameSlot* slot = (NGMPGameSlot*)getSlot(i);
			if (slot == nullptr)
			{
				continue;
			}
			slot->setState((SlotState)pLobbyMember.m_SlotState);
		}

		// dont need to handle else here, we set it up upon lobby creation
	}
}


void NGMPGame::cleanUpSlotPointers(void)
{
	for (Int i = 0; i < MAX_SLOTS; ++i)
		setSlotPointer(i, &m_Slots[i]);
}

NGMPGameSlot* NGMPGame::getGameSpySlot(Int index)
{
	GameSlot* slot = getSlot(index);
	DEBUG_ASSERTCRASH(slot && (slot == &(m_Slots[index])), ("Bad game slot pointer\n"));
	return (NGMPGameSlot*)slot;
}

void NGMPGame::init(void)
{
	GameInfo::init();

	UpdateSlotsFromCurrentLobby();
}

void NGMPGame::setPingString(AsciiString pingStr)
{
	m_pingStr = pingStr;
	m_pingInt = 0;
	//m_pingInt = TheGameSpyInfo->getPingValue(pingStr);
}

Bool NGMPGame::amIHost(void) const
{
	NGMP_OnlineServices_LobbyInterface* pLobbyInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_LobbyInterface>();
	return pLobbyInterface == nullptr ? false : pLobbyInterface->IsHost();
}

void NGMPGame::resetAccepted(void)
{
	GameInfo::resetAccepted();

	if (amIHost())
	{
		/*
		peerStateChanged(TheGameSpyChat->getPeer());
		m_hasBeenQueried = false;
		DEBUG_LOG(("resetAccepted() called peerStateChange()\n"));
		*/
	}
}

Int NGMPGame::getLocalSlotNum(void) const
{
	DEBUG_ASSERTCRASH(m_inGame, ("Looking for local game slot while not in game"));
	if (!m_inGame)
		return -1;

	NGMP_OnlineServices_AuthInterface* pAuthInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_AuthInterface>();
	if (pAuthInterface == nullptr)
	{
		return -1;
	}

	Int64 localUserID = pAuthInterface->GetUserID();

	for (Int i = 0; i < MAX_SLOTS; ++i)
	{
		const NGMPGameSlot* slot = (const NGMPGameSlot*)getConstSlot(i);
		if (slot == NULL) {
			continue;
		}
		if (slot->m_userID == localUserID)
		{
			return i;
		}
	}

	return -1;
}

void NGMPGame::startGame(Int gameID)
{
	DEBUG_ASSERTCRASH(m_inGame, ("Starting a game while not in game"));
	DEBUG_LOG(("NGMPGame::startGame - game id = %d\n", gameID));
	//DEBUG_ASSERTCRASH(m_transport == NULL, ("m_transport is not NULL when it should be"));
	//DEBUG_ASSERTCRASH(TheNAT == NULL, ("TheNAT is not NULL when it should be"));

	//UnsignedInt localIP = TheGameSpyInfo->getInternalIP();
	UnsignedInt localIP = 1337; // dont care anymore
	setLocalIP(localIP);

	// fill in GS-specific info
	Int numHumans = 0;
	for (Int i = 0; i < MAX_SLOTS; ++i)
	{
		if (m_Slots[i].isHuman())
		{
			++numHumans;
			AsciiString gsName;
			gsName.translate(m_Slots[i].getName());

			if (m_isQM)
			{
				// TODO_NGMP: Does this matter anymore?
				if (getLocalSlotNum() == i)
					m_Slots[i].setProfileID(0);  // hehe - we know our own.  the rest, they'll tell us.
			}
			else
			{
				// TODO_NGMP
				/*
				PlayerInfoMap* pInfoMap = TheGameSpyInfo->getPlayerInfoMap();
				PlayerInfoMap::iterator it = pInfoMap->find(gsName);
				if (it != pInfoMap->end())
				{
					m_GameSpySlot[i].setProfileID(it->second.m_profileID);
					m_GameSpySlot[i].setLocale(it->second.m_locale);
					m_GameSpySlot[i].setSlotRankPoints(it->second.m_rankPoints);
					m_GameSpySlot[i].setFavoriteSide(it->second.m_side);
				}
				else
				{
					DEBUG_CRASH(("No player info for %s", gsName.str()));
				}
				*/
			}
		}
	}

	//#if defined(_DEBUG) || defined(_INTERNAL)
	if (numHumans < 2)
	{
		launchGame();
		
		
		// TODO_NGMP: LEave staging room? probably dont care anymore? its all one lobby nowadays
		//if (TheGameSpyInfo)
		//TheGameSpyInfo->leaveStagingRoom();
	}
	else
		//#endif defined(_DEBUG) || defined(_INTERNAL)
	{
		launchGame();
		// TODO_NGMP: We dont care about this anymore? we're already connected
		//TheNAT = NEW NAT();
		//TheNAT->attachSlotList(m_slot, getLocalSlotNum(), m_localIP);
		//TheNAT->establishConnectionPaths();
	}
}

AsciiString NGMPGame::generateGameSpyGameResultsPacket(void)
{
	return AsciiString();
}

AsciiString NGMPGame::generateLadderGameResultsPacket(void)
{
	// TODO_NGMP
	AsciiString results;
	return results;
}

void NGMPGame::launchGame(void)
{
	// TODO_NGMP: Better way of doing this, plus maybe load from file?
#if defined(RTS_DEBUG)
	TheWritableGlobalData->m_benchmarkTimer = 999999999;
	TheWritableGlobalData->m_debugShowGraphicalFramerate = true;
	TheWritableGlobalData->m_showMetrics = true;
#endif

	//TheWritableGlobalData->m_networkPlayerTimeoutTime = 60000;
	//TheWritableGlobalData->m_networkDisconnectScreenNotifyTime = 2500;

	// process service config
	NGMP_OnlineServicesManager* pOnlineServicesMgr = NGMP_OnlineServicesManager::GetInstance();
	if (pOnlineServicesMgr != nullptr)
	{
		ServiceConfig& serviceConf = pOnlineServicesMgr->GetServiceConfig();

		if (serviceConf.use_default_config)
		{
			TheWritableGlobalData->m_networkFPSHistoryLength = 30;
			TheWritableGlobalData->m_networkLatencyHistoryLength = 200;
			TheWritableGlobalData->m_networkRunAheadSlack = serviceConf.ra_slack_override_percent_in_default; // normally 10
			TheWritableGlobalData->m_networkRunAheadMetricsTime = 5000;
		}
		else
		{
			TheWritableGlobalData->m_networkFPSHistoryLength = 10;
			TheWritableGlobalData->m_networkLatencyHistoryLength = 10;

			MIN_RUNAHEAD = serviceConf.min_run_ahead_frames;

			TheWritableGlobalData->m_networkRunAheadSlack = serviceConf.ra_slack_percent;
			TheWritableGlobalData->m_networkRunAheadMetricsTime = serviceConf.ra_update_frequency_frames * (float)(1000.f / GENERALS_ONLINE_HIGH_FPS_LIMIT);

			FRAME_GROUPING_CAP = serviceConf.frame_grouping_frames * (float)(1000.f / GENERALS_ONLINE_HIGH_FPS_LIMIT);
		}
	}
	

#if defined(GENERALS_ONLINE_HIGH_FPS_RENDER)
	TheWritableGlobalData->m_horizontalScrollSpeedFactor = NGMP_OnlineServicesManager::Settings.Camera_MoveSpeedRatio();
	TheWritableGlobalData->m_verticalScrollSpeedFactor = NGMP_OnlineServicesManager::Settings.Camera_MoveSpeedRatio();
#endif

	setGameInProgress(TRUE);

	for (Int i = 0; i < MAX_SLOTS; ++i)
	{
		const NGMPGameSlot* slot = getGameSpySlot(i);
		if (slot->isHuman())
		{
			// TODO_NGMP:
			bool bPreordered = false;
			if (bPreordered)
				markPlayerAsPreorder(i);
		}
	}

	// Set up the game network
	AsciiString user;
	AsciiString userList;
	DEBUG_ASSERTCRASH(TheNetwork == NULL, ("For some reason TheNetwork isn't NULL at the start of this game.  Better look into that."));

	if (TheNetwork != NULL) {
		delete TheNetwork;
		TheNetwork = NULL;
	}

	// TODO_NGMP: do we care? we are already connected
	
	// Time to initialize TheNetwork for this game.
	TheNetwork = NetworkInterface::createNetwork();
	TheNetwork->init();
	
	// GeneralsX @bugfix Android port 10/07/2026 P2P transport (NetworkMesh)
	// deferred, see NGMP_include.h -- GetNetworkMesh() is always null in this
	// build, and NetworkInterface::SeedLatencyData() is itself part of the
	// same upstream change set this port doesn't carry (existing engine file).
#if defined(GENERALS_ONLINE_ENABLE_P2P_TRANSPORT)
	NetworkMesh* pMesh = NGMP_OnlineServicesManager::GetNetworkMesh();
	if (pMesh != nullptr)
	{
		TheNetwork->SeedLatencyData(pMesh->getMaximumHistoricalLatency());
	}
#endif

	// TODO_NGMP: Do we really care about these values anymore
	TheNetwork->setLocalAddress(getLocalIP(), 8888);

	TheNetwork->initTransport();

	TheNetwork->parseUserList(this);

	if (TheGameLogic->isInGame()) {
		TheGameLogic->clearGameData();
	}

	// GeneralsX @feature Flux Build v1.2.6 28/10/2026 Remember whether this client already
	// had the map before the transfer. A slot that goes 0 -> 1 just received the host's file,
	// and that file is the authoritative baseline for the check below.
	const Int localSlot = getLocalSlotNum();
	const Bool hadMapBefore = (localSlot >= 0)
		&& (getConstSlot(localSlot) != nullptr)
		&& getConstSlot(localSlot)->hasMap();

	Bool filesOk = DoAnyMapTransfers(this);

	// GeneralsX @bugfix Flux Build v1.2.4 28/10/2026 Full-file CRC calculation after transfer.
	// Transfer is 100% complete and files are closed. Read the completed map binary from disk,
	// compute the exact full-file CRC, and update TheMapCache's live entry.
	TheMapCache->updateCache();
	const MapMetaData *md = TheMapCache->findMap(getMap());
	bool mapCrcMismatch = false;
	Bool receivedMapNow = FALSE;
	if (md != nullptr)
	{
		const UnsignedInt fullFileCRC = calcCRC(md->m_fileName);
		if (fullFileCRC != 0)
		{
			MapMetaData& liveEntry = (*TheMapCache)[md->m_fileName];
			liveEntry.m_CRC = fullFileCRC;
		}

		// GeneralsX @bugfix Flux Build v1.2.6 28/10/2026 If this client just received the
		// map, the file on disk is the host's file: it is the baseline, not the value that
		// was in the cache before the transfer. m_mapCRC is only ever set from the *local*
		// cache in SyncWithLobby(); NGMP never transmits the host's CRC, so comparing a
		// freshly received file against a pre-transfer local value is a self-comparison
		// across time. That is what produced the Vendetta -> Fort Thunder false mismatch.
		receivedMapNow = (!hadMapBefore && localSlot >= 0
			&& getConstSlot(localSlot) != nullptr
			&& getConstSlot(localSlot)->hasMap());
		if (receivedMapNow)
		{
			const UnsignedInt previousCRC = getMapCRC();
			setMapCRC(fullFileCRC);
			setMapSize(md->m_filesize);
			fprintf(stderr, "[GX-MAP-XFER] CRC baseline refreshed after transfer: 0x%08X (was 0x%08X)\n",
				fullFileCRC, previousCRC);
			fflush(stderr);
		}

		if (!receivedMapNow && getMapCRC() != 0 && fullFileCRC != 0)
		{
			if (fullFileCRC != getMapCRC())
			{
				mapCrcMismatch = true;
				NetworkLog(ELogVerbosity::LOG_RELEASE, "Map CRC mismatch: local 0x%X vs cached baseline 0x%X for '%s'", fullFileCRC, getMapCRC(), getMap().str());
			}
		}
	}

	// GeneralsX @feature Flux Build v1.2.5 28/10/2026 Raw launch summary: transfer result,
	// map-cache lookup and per-slot map availability in one line. DEBUG_LOG is compiled out of
	// release builds, which is why the v1.2.4 failure left nothing to read.
	{
		fprintf(stderr, "[GX-MAP-XFER] launch result: filesOk=%d md=%d map='%s' mask=0x%02X host=%d slot=%d hadMap=%d received=%d baseline=0x%08X",
			filesOk ? 1 : 0, md != nullptr ? 1 : 0, getMap().str(), getMapContentsMask(),
			amIHost() ? 1 : 0, getLocalSlotNum(), hadMapBefore ? 1 : 0, receivedMapNow ? 1 : 0, getMapCRC());
		for (Int s = 0; s < MAX_SLOTS; ++s)
		{
			const GameSlot *slot = getConstSlot(s);
			if (slot != nullptr && slot->isHuman())
				fprintf(stderr, " slot%d(hasMap=%d)", s, slot->hasMap() ? 1 : 0);
		}
		fprintf(stderr, "\n");
		fflush(stderr);
	}

	if (!filesOk || md == nullptr || mapCrcMismatch)
	{
		DEBUG_LOG(("After transfer/validation, map check failed (filesOk=%d, md=%p, crcMismatch=%d). Bailing...\n", filesOk, md, mapCrcMismatch));
		
		// GeneralsX @bugfix Flux Build v1.2.0 27/10/2026 Reset game-in-progress state on transfer abort
		// so subsequent lobby message packets are not discarded.
		setGameInProgress(FALSE);

		if (TheNetwork != NULL) {
			delete TheNetwork;
			TheNetwork = NULL;
		}

		// GeneralsX @bugfix Flux Build v1.2.0 27/10/2026 Restore shell menus with full re-init
		// so the player returns cleanly to the lobby without hanging on a black screen.
		if (TheShell != nullptr) {
			TheShell->showShell(TRUE);
		}

		// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Say which failure happened; the old
		// "ask the host to use an official map" message was wrong for every case it covered.
		UnicodeString errorMsg;
		if (!filesOk)
			errorMsg = L"Map transfer failed. The map files could not be received; see the [GX-MAP-XFER] lines in the device log.";
		else if (mapCrcMismatch)
			errorMsg = L"Map verification failed. The received map does not match the expected checksum (stale map cache).";
		else
			errorMsg = L"Map transfer failed. The map is not present on this device after transfer.";
		GSMessageBoxOk(TheGameText->fetch("GUI:Error"), errorMsg);

		void PopBackToLobby(void);
		PopBackToLobby();
		return;
	}

	// GeneralsX @bugfix Flux Build v1.2.2 27/10/2026 Update verified map filename and mark slot ready
	// so MSG_NEW_GAME and lockstep synchronization begin simultaneously on host and client.
	if (md != nullptr)
	{
		setMap(md->m_fileName.isNotEmpty() ? md->m_fileName : getMap());
		if (getSlot(getLocalSlotNum()) != nullptr)
		{
			getSlot(getLocalSlotNum())->setMapAvailability(TRUE);
		}
	}

	// Force camera to update from config
	// GeneralsX @bugfix Android port 10/07/2026 see NGMPGame::~NGMPGame() above --
	// our setDefaultView() is still the 3-arg signature.
	// GeneralsX @bugfix Android port 13/07/2026 - see the matching fix in
	// NGMPGame::~NGMPGame() above: a literal 0.0f pitch made the camera look
	// dead level at the horizon (a real device screenshot showed a
	// "first-person" view right as the match started) instead of down at the
	// battlefield. Use TheGlobalData->m_cameraPitch (degrees -> radians),
	// same as View::init()'s own default-pitch setup.
	TheTacticalView->setDefaultView(DEG_TO_RADF(TheGlobalData->m_cameraPitch), 0.0f, 1.0f);


	// shutdown the top, but do not pop it off the stack
//		TheShell->hideShell();
	// setup the Global Data with the Map and Seed
	TheWritableGlobalData->m_pendingFile = getMap();

	// send a message to the logic for a new game
	GameMessage* msg = TheMessageStream->appendMessage(GameMessage::MSG_NEW_GAME);
	msg->appendIntegerArgument(GAME_INTERNET);

#if defined(GENERALS_ONLINE_HIGH_FPS_RENDER)

	if (NGMP_OnlineServicesManager::Settings.Graphics_LimitFramerate())
	{
		TheWritableGlobalData->m_framesPerSecondLimit = NGMP_OnlineServicesManager::Settings.Graphics_GetFPSLimit();
		TheWritableGlobalData->m_useFpsLimit = true;
	}
	else
	{
		TheWritableGlobalData->m_framesPerSecondLimit = 30000; // game does this... it's not great
		TheWritableGlobalData->m_useFpsLimit = false;
	}
	
#endif
	//TheWritableGlobalData->m_useFpsLimit = false;

	// Set the random seed
	InitRandom(getSeed());
	DEBUG_LOG(("InitGameLogicRandom( %d )\n", getSeed()));

	// mark us as "Loading" in the buddy list
	// TODO_NGMP
	/*
	BuddyRequest req;
	req.buddyRequestType = BuddyRequest::BUDDYREQUEST_SETSTATUS;
	req.arg.status.status = GP_PLAYING;
	strcpy(req.arg.status.statusString, "Loading");
	sprintf(req.arg.status.locationString, "%s", WideCharStringToMultiByte(TheGameSpyGame->getGameName().str()).c_str());
	TheGameSpyBuddyMessageQueue->addRequest(req);
	*/

    	// Show map name in the match start communicator hint notification
	{
        NGMP_OnlineServices_LobbyInterface* pLobbyInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_LobbyInterface>();
		if (pLobbyInterface != nullptr)
		{
			LobbyEntry& currentLobby = pLobbyInterface->GetCurrentLobby();

			std::string strMapName = currentLobby.map_name; // NOTE: Includes .map (2) etc
			const std::string strExt = ".map";
			size_t pos = strMapName.find(strExt);
			if (pos != std::string::npos) { strMapName.erase(pos, strExt.size()); }

            UnicodeString msg;
            msg.format(L"Map: %hs\nPress F5 or INSERT to open the communicator.", strMapName.c_str());
            showNotificationBox(AsciiString::TheEmptyString, msg, false);
		}

		
	}
}

void NGMPGame::reset(void)
{
	GameInfo::reset();
}

void NGMPGame::StartCountdown()
{
	m_bCountdownStarted = true;
	m_countdownStartTime = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
	m_countdownLastCheckTime = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();

	std::shared_ptr<WebSocket>  pWS = NGMP_OnlineServicesManager::GetWebSocket();
	if (pWS != nullptr)
	{
		pWS->SendData_CountdownStarted();
	}
}

