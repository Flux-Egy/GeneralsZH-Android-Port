// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

#include "GameNetwork/GeneralsOnline/NGMP_interfaces.h"
#include "GameNetwork/GeneralsOnline/json.hpp"
#include "GameNetwork/GeneralsOnline/HTTP/HTTPManager.h"
#include "GameNetwork/GeneralsOnline/OnlineServices_Init.h"

#include <cstdio>
#include <chrono>

#if defined(__ANDROID__)
#include <android/log.h>
#define QM_LOG(...) __android_log_print(ANDROID_LOG_INFO, "GX-QM", __VA_ARGS__)
#define QM_LOG_ERR(...) __android_log_print(ANDROID_LOG_ERROR, "GX-QM", __VA_ARGS__)
#else
#define QM_LOG(...) fprintf(stderr, "[GX-QM] " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr)
#define QM_LOG_ERR(...) fprintf(stderr, "[GX-QM-ERR] " __VA_ARGS__); fprintf(stderr, "\n"); fflush(stderr)
#endif

// GeneralsX @feature Flux Build v1.1.3 26/10/2026 Monotonic milliseconds for the
// deferred StartMatchmaking retry. steady_clock cannot jump backwards when the
// device's wall clock is adjusted between the request and the retry.
static int64_t GXQMNowMs()
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::steady_clock::now().time_since_epoch()).count();
}

NGMP_OnlineServices_MatchmakingInterface::NGMP_OnlineServices_MatchmakingInterface()
{

}

void NGMP_OnlineServices_MatchmakingInterface::RetrievePlaylists(std::function<void(std::vector<PlaylistEntry>)> callbackOnComplete)
{
	std::string strURI = NGMP_OnlineServicesManager::GetAPIEndpoint("Matchmaking/Playlists");
	std::map<std::string, std::string> mapHeaders;

	NGMP_OnlineServicesManager::GetInstance()->GetHTTPManager()->SendGETRequest(strURI.c_str(), EIPProtocolVersion::DONT_CARE, mapHeaders, [=, this](bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)
		{
			// TODO_NGMP: Error handling
			try
			{
				nlohmann::json jsonObject = nlohmann::json::parse(strBody);

				std::vector<PlaylistEntry> vecPlaylists;
				for (const auto& playlistEntryIter : jsonObject["playlists"])
				{
					PlaylistEntry playlistEntry;
					playlistEntryIter["PlaylistID"].get_to(playlistEntry.PlaylistID);
					playlistEntryIter["Name"].get_to(playlistEntry.Name);
					playlistEntryIter["MinPlayers"].get_to(playlistEntry.MinPlayers);
					playlistEntryIter["DesiredPlayers"].get_to(playlistEntry.DesiredPlayers);
					// GeneralsX @bugfix Android port 07/11/2026 - ported from upstream GeneralsOnline, used by WOLQuickMatchMenu.cpp
					playlistEntryIter["MinSelectedMaps"].get_to(playlistEntry.MinSelectedMaps);
					playlistEntryIter["AllowTeams"].get_to(playlistEntry.AllowTeams);
					playlistEntryIter["TeamSize"].get_to(playlistEntry.TeamSize);
					playlistEntryIter["AllowArmySelection"].get_to(playlistEntry.AllowArmySelection);
					playlistEntryIter["GracePeriodAtMinPlayersMSec"].get_to(playlistEntry.GracePeriodAtMinPlayersMSec);

					// maps
					for (const auto& mapEntryIter : playlistEntryIter["Maps"])
					{
						PlaylistMapEntry mapEntry;

						mapEntryIter["Name"].get_to(mapEntry.Name);
						mapEntryIter["Path"].get_to(mapEntry.Path);
						mapEntryIter["Custom"].get_to(mapEntry.Custom);

						playlistEntry.Maps.push_back(mapEntry);
					}

					vecPlaylists.push_back(playlistEntry);
				}

				// cache
				m_vecCachedPlaylists = vecPlaylists;

				// invoke callback
				if (callbackOnComplete != nullptr)
				{
					callbackOnComplete(vecPlaylists);
				}
			}
			catch (const std::exception& e)
			{
				// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 This used to swallow the
				// exception and never invoke the callback, leaving the QuickMatch playlist
				// box permanently empty with no line in the log. Report it and complete the
				// callback so the menu can show the failure instead of hanging on a spinner.
				QM_LOG_ERR("RetrievePlaylists failed: %s", e.what());
				if (callbackOnComplete != nullptr)
				{
					callbackOnComplete(std::vector<PlaylistEntry>());
				}
			}
			catch (...)
			{
				QM_LOG_ERR("RetrievePlaylists failed: unknown exception");
				if (callbackOnComplete != nullptr)
				{
					callbackOnComplete(std::vector<PlaylistEntry>());
				}
			}

		});
}

void NGMP_OnlineServices_MatchmakingInterface::WidenSearch()
{
	std::map<std::string, std::string> mapHeaders;
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 This POST used to go out with no
	// Content-Type and no body, which the ASP.NET endpoint rejects before the request
	// reaches the controller. It also ignored the reply, so a rejected widen looked
	// exactly like a queue that had simply stopped moving.
	mapHeaders["Content-Type"] = "application/json";
	std::string strURI = NGMP_OnlineServicesManager::GetAPIEndpoint("Matchmaking/Widen");
	QM_LOG("WidenSearch: sending POST %s", strURI.c_str());
	NGMP_OnlineServicesManager::GetInstance()->GetHTTPManager()->SendPOSTRequest(strURI.c_str(), EIPProtocolVersion::DONT_CARE, mapHeaders, "{}", [](bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)
		{
			if (!bSuccess || statusCode < 200 || statusCode >= 300)
			{
				QM_LOG_ERR("WidenSearch failed: status=%d body=%s", statusCode, strBody.c_str());
				return;
			}

			QM_LOG("WidenSearch accepted (status=%d)", statusCode);
		});
}

void NGMP_OnlineServices_MatchmakingInterface::StartMatchmaking(uint16_t playlistID, std::vector<int> vecSelectedMapIndexes, std::function<void(bool)> fnCallback)
{
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 The previous revision aborted the
	// entire request when the WebSocket was not connected. The queue itself is an HTTP
	// resource, and the socket is routinely mid-reconnect right after sign-in or a
	// lobby transition, so a transient state became a permanent "matchmaking failed"
	// with no way back. Send the PUT anyway (the socket only carries match-found and
	// chat notifications) and let the HTTP result decide; SendStartRequest retries the
	// transport failures that are worth retrying.
	std::shared_ptr<WebSocket> pWS = NGMP_OnlineServicesManager::GetWebSocket();
	if (pWS == nullptr || !pWS->IsConnected())
	{
		QM_LOG("StartMatchmaking: WebSocket not connected (reconnecting?) -- sending the PUT anyway");
	}

	// A new user action supersedes any deferred retry from the previous one.
	m_pendingStart = PendingStart();
	const int generation = ++m_startGeneration;

	SendStartRequest(playlistID, vecSelectedMapIndexes, fnCallback, 1, generation);
}

void NGMP_OnlineServices_MatchmakingInterface::SendStartRequest(uint16_t playlistID, const std::vector<int>& vecSelectedMapIndexes, std::function<void(bool)> fnCallback, int attempt, int generation)
{
	nlohmann::json j;
	j["playlist"] = playlistID;
	j["maps"] = vecSelectedMapIndexes;
	// GeneralsX @bugfix Flux Build v1.1.2 23/10/2026 QuickMatch must advertise the PC-spoof
	// checksum the launcher opted into. m_exeCRC is a shared writable field (lobby join,
	// /forcerelay, debug f_fakeCRC), so reading it here could send 0 or the native Android CRC
	// after an unrelated lobby action. The override is captured once in GlobalData::init().
	// GeneralsX @bugfix Flux Build 07/11/2026 v1.4.0-community: never the PC client's number. The
	// fallback here claimed the PC checksum whenever this client's own was 0 or the Android one; now
	// it is the cross-play override (only set behind the signed pc_crossplay gate) or our own.
	UnsignedInt qmExeCRC = TheGlobalData->m_exeCRC;
	if (TheGlobalData->m_exeCRCOverrideActive && TheGlobalData->m_exeCRCOverride != 0)
	{
		qmExeCRC = TheGlobalData->m_exeCRCOverride;
	}
	j["exe_crc"] = qmExeCRC;
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 Compute this once. Reading it back
	// out of the JSON with get<unsigned>() while building a log line put a throwing
	// conversion outside the completion callback's try/catch.
	const unsigned iniCRC = (TheGlobalData->m_iniCRC != 0) ? (unsigned)TheGlobalData->m_iniCRC : 2180732466U;
	j["ini_crc"] = iniCRC;
	// GeneralsX @bugfix Flux Build v1.1.2 25/10/2026 Mandatory backend contract: anticheat_id must be present.
	j["anticheat_id"] = 0;

	std::map<std::string, std::string> mapHeaders;
	mapHeaders["Content-Type"] = "application/json";
	std::string strPostData = j.dump();
	std::string strURI = NGMP_OnlineServicesManager::GetAPIEndpoint("Matchmaking");

	QM_LOG("StartMatchmaking: sending PUT %s (attempt %d/3)", strURI.c_str(), attempt);
	QM_LOG("StartMatchmaking payload: %s", strPostData.c_str());
	QM_LOG("StartMatchmaking exe_crc=%u ini_crc=%u anticheat_id=%d pc_spoof=%s",
		(unsigned)qmExeCRC, iniCRC, 0,
		TheGlobalData->m_exeCRCOverrideActive ? "on" : "off");

	NGMP_OnlineServicesManager::GetInstance()->GetHTTPManager()->SendPUTRequest(strURI.c_str(), EIPProtocolVersion::DONT_CARE, mapHeaders, strPostData.c_str(),
		[this, playlistID, maps = vecSelectedMapIndexes, fnCallback, attempt, generation](bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)
		{
			// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 A response for a superseded
			// request must not touch the queue state: CancelMatchmaking bumps the
			// generation precisely so a slow PUT cannot resurrect a cancelled search.
			if (generation != m_startGeneration)
			{
				QM_LOG("StartMatchmaking: ignoring response for superseded request (generation %d, current %d)", generation, m_startGeneration);
				return;
			}

			// GeneralsX @bugfix Flux Build v1.1.2 25/10/2026 Accept any successful 2xx status code
			// (the ASP.NET backend returns HTTP 204 No Content on successful registration).
			const bool bHttpOk = bSuccess && (statusCode >= 200 && statusCode < 300);

			if (bHttpOk)
			{
				QM_LOG("StartMatchmaking SUCCESS: Joined queue (status=%d, body=%s)", statusCode, strBody.c_str());
				m_bQueueActive = true;

				if (fnCallback != nullptr)
				{
					fnCallback(true);
				}

				return;
			}

			// A transport failure or a 5xx is worth another attempt; a 4xx (expired token,
			// bad playlist, already queued) is a real answer and must not be retried.
			// 200 ms, then 400 ms -- long enough for a reconnect to complete, short enough
			// to stay inside the QuickMatch window's patience.
			const bool bTransient = !bSuccess || statusCode == 0 || statusCode >= 500;
			if (bTransient && attempt < 3)
			{
				const int nextAttempt = attempt + 1;
				m_pendingStart.playlistID = playlistID;
				m_pendingStart.maps = maps;
				m_pendingStart.callback = fnCallback;
				m_pendingStart.attempt = nextAttempt;
				m_pendingStart.generation = generation;
				m_pendingStart.dueTickMs = GXQMNowMs() + (200 * attempt);
				m_pendingStart.active = true;

				QM_LOG_ERR("StartMatchmaking: status=%d transient failure, retrying in %d ms (attempt %d/3)",
					statusCode, 200 * attempt, nextAttempt);
				return;
			}

			QM_LOG_ERR("StartMatchmaking ERROR: PUT rejected: status=%d body=%s", statusCode, strBody.c_str());

			if (fnCallback != nullptr)
			{
				fnCallback(false);
			}
		});
}

void NGMP_OnlineServices_MatchmakingInterface::Tick()
{
	if (!m_pendingStart.active)
	{
		return;
	}

	if (GXQMNowMs() < m_pendingStart.dueTickMs)
	{
		return;
	}

	PendingStart pending = std::move(m_pendingStart);
	m_pendingStart = PendingStart();

	SendStartRequest(pending.playlistID, pending.maps, pending.callback, pending.attempt, pending.generation);
}

void NGMP_OnlineServices_MatchmakingInterface::CancelMatchmaking()
{
	// A cancel supersedes any deferred retry, and invalidates an in-flight PUT.
	m_pendingStart = PendingStart();
	++m_startGeneration;

	const bool wasQueueActive = m_bQueueActive;
	m_bQueueActive = false;

	std::map<std::string, std::string> mapHeaders;
	mapHeaders["Content-Type"] = "application/json";
	std::string strURI = NGMP_OnlineServicesManager::GetAPIEndpoint("Matchmaking");
	QM_LOG("CancelMatchmaking: sending DELETE %s", strURI.c_str());
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 No body: the endpoint identifies the
	// queue from the bearer token. The old code sent "{}", which is a Content-Length: 2
	// DELETE carrying an empty JSON object for no reason.
	NGMP_OnlineServicesManager::GetInstance()->GetHTTPManager()->SendDELETERequest(strURI.c_str(), EIPProtocolVersion::DONT_CARE, mapHeaders, "", [](bool bSuccess, int statusCode, std::string strBody, HTTPRequest* pReq)
		{
			QM_LOG("CancelMatchmaking RESPONSE: status=%d success=%s body=%s",
				statusCode, bSuccess ? "true" : "false", strBody.c_str());
		});

	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 Only leave the lobby when this
	// session actually put the player in the queue. The old unconditional leave meant
	// that merely opening and closing the QuickMatch screen could drop a player from
	// the lobby they were already in.
	if (wasQueueActive)
	{
		NGMP_OnlineServices_LobbyInterface* pLobbyInterface = NGMP_OnlineServicesManager::GetInterface<NGMP_OnlineServices_LobbyInterface>();
		if (pLobbyInterface != nullptr)
		{
			pLobbyInterface->LeaveCurrentLobby();
			pLobbyInterface->ResetHostMigrationFlags();
		}
	}
}
