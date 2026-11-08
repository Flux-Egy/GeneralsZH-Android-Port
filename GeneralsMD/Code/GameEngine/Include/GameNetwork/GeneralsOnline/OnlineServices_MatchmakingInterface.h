// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

#pragma once

#include "NGMP_include.h"

struct PlaylistMapEntry
{
	std::string Name = std::string();
	std::string Path = std::string();
	bool Custom = false;
};

struct PlaylistEntry
{
	uint16_t PlaylistID = -1;
	std::string Name = std::string();
	int MinPlayers = -1;
	int DesiredPlayers = -1;
	// GeneralsX @bugfix Android port 07/11/2026 - ported from upstream GeneralsOnline, used by WOLQuickMatchMenu.cpp
	int MinSelectedMaps = 0;
	bool AllowTeams = false;
	int TeamSize = -1;
	bool AllowArmySelection = false;
	uint16_t GracePeriodAtMinPlayersMSec = 0;
	std::vector<PlaylistMapEntry> Maps = std::vector<PlaylistMapEntry>();
};

class NGMP_OnlineServices_MatchmakingInterface
{
public:
	NGMP_OnlineServices_MatchmakingInterface();

	void RetrievePlaylists(std::function<void(std::vector<PlaylistEntry>)> callbackOnComplete);

	void WidenSearch();

	void StartMatchmaking(uint16_t playlistID, std::vector<int> vecSelectedMapIndexes, std::function<void(bool)> fnCallback);

	void CancelMatchmaking();

	/// Drains the deferred StartMatchmaking retry. Called from the online-services
	/// tick; safe to call when nothing is pending. GeneralsX @feature Flux Build
	/// v1.1.3 26/10/2026.
	void Tick();

	std::vector<PlaylistEntry> GetCachedPlaylists() const { return m_vecCachedPlaylists; }
	PlaylistEntry GetCachedPlaylistFromID(uint16_t id) const
	{
		for (PlaylistEntry plEntry : m_vecCachedPlaylists)
		{
			if (plEntry.PlaylistID == id)
			{
				return plEntry;
			}
		}

		return PlaylistEntry();
	}

	PlaylistEntry GetCachedPlaylistFromIndex(int index) const
	{
		if (index < m_vecCachedPlaylists.size())
		{
			return m_vecCachedPlaylists.at(index);
		}

		return PlaylistEntry();
	}

private:
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 One PUT at a time: the retry only
	// exists for a transient transport failure, and the QuickMatch UI disables Start
	// while a request is outstanding, so a single slot is enough.
	struct PendingStart
	{
		uint16_t playlistID = 0;
		std::vector<int> maps;
		std::function<void(bool)> callback = nullptr;
		int64_t dueTickMs = 0;
		int attempt = 0;
		int generation = 0;
		bool active = false;
	};

	void SendStartRequest(uint16_t playlistID, const std::vector<int>& vecSelectedMapIndexes,
	                      std::function<void(bool)> fnCallback, int attempt, int generation);

	std::vector<PlaylistEntry> m_vecCachedPlaylists;
	PendingStart m_pendingStart;
	bool m_bQueueActive = false;
	// GeneralsX @bugfix Flux Build v1.1.3 26/10/2026 Bumped by every Start and Cancel. A
	// PUT that completes after the player cancelled (or restarted) the search has a stale
	// generation and must not flip m_bQueueActive back on or re-enable the UI.
	int m_startGeneration = 0;
};
