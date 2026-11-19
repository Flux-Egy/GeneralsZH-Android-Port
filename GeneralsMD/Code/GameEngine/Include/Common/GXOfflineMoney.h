// GeneralsX @feature Flux Build 10/11/2026 Offline money boost. The launcher writes gx_offline_money.txt
// into the selected game folder (the engine's working directory, SDL3Main chdir) while "Start money boost" is on. The engine
// then offers 500k / 1M / 5M in the starting-money list (Skirmish, and a LAN host's lobby; LAN clients
// take the host's amount) and keeps GeneralsOnline closed. Read once per run; never read by game logic.
#pragma once

#include <cstdio>

inline bool gxOfflineMoneyEnabled()
{
	static int s_state = -1;
	if (s_state < 0)
	{
		FILE *f = fopen("gx_offline_money.txt", "r");
		s_state = f != nullptr ? 1 : 0;
		if (f != nullptr)
			fclose(f);
		fprintf(stderr, "[GX-OFFLINE-MONEY] %s\n", s_state ? "on: money up to 5M in Skirmish and LAN, Online closed" : "off");
	}
	return s_state == 1;
}
