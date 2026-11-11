/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

////////////////////////////////////////////////////////////////////////////////
//																																						//
//  (c) 2001-2003 Electronic Arts Inc.																				//
//																																						//
////////////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////////////////////////
// FILE: FileTransfer.cpp
// Author: Matthew D. Campbell, December 2002
// Description: File Transfer wrapper using TheNetwork
///////////////////////////////////////////////////////////////////////////////////////

#include "PreRTS.h"	// This must go first in EVERY cpp file in the GameEngine

#include <cstdarg>

#include "Common/file.h"
#include "Common/LocalFileSystem.h"
#include "GameClient/LoadScreen.h"
#include "GameClient/MapUtil.h"
#include "GameClient/Shell.h"
#include "GameNetwork/FileTransfer.h"
#include "GameNetwork/GameInfo.h"
#include "GameNetwork/networkutil.h"

//-------------------------------------------------------------------------------------
// GeneralsX @feature Flux Build v1.2.5 28/10/2026 Raw stderr diagnostics for the map
// transfer loop. DEBUG_LOG is compiled out of release builds, which is why the v1.2.4
// 45-second stall produced no transfer lines at all in the log. These lines are always
// compiled in, and are deliberately terse (at most one line per 20% bucket per slot).
//-------------------------------------------------------------------------------------
static void gxMapXferLog(const char *fmt, ...)
{
	char buffer[1024];
	va_list args;
	va_start(args, fmt);
	vsnprintf(buffer, sizeof(buffer), fmt, args);
	va_end(args);
	fprintf(stderr, "[GX-MAP-XFER] %s\n", buffer);
	fflush(stderr);
}

//-------------------------------------------------------------------------------------
// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Path handling for transfers.
// The lobby (NGMP) hands us a path relative to the Maps directory, while the file
// announce round-trips through realMapPathToPortableMapPath() and
// portableMapPathToRealMapPath(), which reconstruct an absolute path. The two only
// match when the transfer loop uses the same absolute form, so resolve it once here.
// A relative path that is not resolved round-trips to an empty string and the
// receiver silently drops the file (no write, no progress message, no timeout on
// the sender).
//-------------------------------------------------------------------------------------
static Bool gxIsAbsolutePath(const AsciiString &path)
{
	if (path.isEmpty())
		return FALSE;
	if (path.getCharAt(0) == '/' || path.getCharAt(0) == '\\')
		return TRUE;
	if (path.getLength() > 1 && path.getCharAt(1) == ':')
		return TRUE;
	return FALSE;
}

AsciiString ResolveMapTransferPath( AsciiString mapName )
{
	if (mapName.isEmpty() || gxIsAbsolutePath(mapName))
		return mapName;

	AsciiString userMapDir = (TheMapCache != nullptr) ? TheMapCache->getUserMapDir() : AsciiString::TheEmptyString;
	if (userMapDir.isEmpty())
		return mapName;

	AsciiString resolved = userMapDir;
	if (!resolved.endsWith("/") && !resolved.endsWith("\\"))
		resolved.concat('/');
	resolved.concat(mapName);
	return resolved;
}

static Int gxMapFileSize(const AsciiString &path)
{
	if (path.isEmpty() || TheLocalFileSystem == nullptr)
		return -1;

	File *fp = TheLocalFileSystem->openFile(path.str(), File::READ | File::BINARY);
	if (fp == nullptr)
		return -1;

	Int size = fp->size();
	fp->close();
	return size;
}

// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Single "can the host send this?" test.
// The map-contents mask probe and the sender must agree, otherwise the host announces a
// file it cannot open and both sides wait for it until the timeout.
Bool CanSendMapFile( AsciiString path )
{
	return gxMapFileSize(path) > 0;
}

//-------------------------------------------------------------------------------------
//-------------------------------------------------------------------------------------

static Bool doFileTransfer( AsciiString filename, MapTransferLoadScreen *ls, Int mask,
														Int timeoutPeriod, Int retryAfter, Int retryLimit )
{
	if (mask == 0)
		return TRUE;

	// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Resolve once; see ResolveMapTransferPath().
	AsciiString transferName = ResolveMapTransferPath(filename);

	const Bool amHost = TheGameInfo->amIHost();
	const Int localSlot = TheGameInfo->getLocalSlotNum();
	const Int fileSize = gxMapFileSize(transferName);

	ls->setCurrentFilename(transferName);

	UnsignedInt startTime = timeGetTime();
	UnsignedInt announceTime = 0;
	Bool announced = FALSE;
	Bool sentFile = FALSE;
	Bool sendBlockedLogged = FALSE;
	UnsignedShort fileCommandID = 0;

	ls->processTimeout(timeoutPeriod/1000);
	ls->update(0);

	if (amHost)
	{
		// GeneralsX @performance Flux Build v1.2.1 27/10/2026 Reduced from 500ms to 50ms.
		// Saves ~450ms per file transferred across multi-file transfers without dropping announce packets.
		Sleep(50);
		fileCommandID = TheNetwork->sendFileAnnounce(transferName, mask);
		if (fileCommandID == 0)
		{
			// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Fail fast instead of spinning for
			// the full timeout: the host cannot open the file, so nothing will ever be sent.
			gxMapXferLog("t=%u ANNOUNCE FAILED file='%s' mask=0x%02X size=%d - host cannot open it, not waiting",
				timeGetTime(), transferName.str(), mask, fileSize);
			return FALSE;
		}
		announced = TRUE;
		announceTime = timeGetTime();
		gxMapXferLog("t=%u Starting file: %s (host slot=%d mask=0x%02X size=%d announceID=%u)",
			announceTime, transferName.str(), localSlot, mask, fileSize, fileCommandID);
	}
	else
	{
		sentFile = TRUE;
		gxMapXferLog("t=%u Starting file: %s (client slot=%d mask=0x%02X size=%d)",
			startTime, transferName.str(), localSlot, mask, fileSize);
	}

	DEBUG_LOG(("Starting file transfer loop"));

	Int slotPercent[MAX_SLOTS];
	Int lastPercent[MAX_SLOTS];
	Int lastBucket[MAX_SLOTS];
	UnsignedInt lastChangeTime[MAX_SLOTS];
	Int retries[MAX_SLOTS];
	for (Int s = 0; s < MAX_SLOTS; ++s)
	{
		slotPercent[s] = -2;			// -2 = slot not pending for this file
		lastPercent[s] = -2;
		lastBucket[s] = -1;
		lastChangeTime[s] = 0;
		retries[s] = 0;
	}

	Bool fileTransferDone = FALSE;
	Int fileTransferPercent = 0;
	UnsignedInt lastProgressTime = timeGetTime();
	Bool notAnnouncedLogged = FALSE;

	while (!fileTransferDone)
	{
		UnsignedInt now = timeGetTime();

		// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Bound the "wait for empty queues"
		// gate. areAllQueuesEmpty() covers every connection, not just the recipients, so one
		// unrelated peer with a backed-up queue used to prevent the file from being sent at all.
		if (!sentFile)
		{
			if (TheNetwork->areAllQueuesEmpty())
			{
				TheNetwork->sendFile(transferName, mask, fileCommandID);
				sentFile = TRUE;
				gxMapXferLog("t=%u sending data for %s (mask=0x%02X announceID=%u)",
					now, transferName.str(), mask, fileCommandID);
			}
			else if (now - startTime > 2000)
			{
				TheNetwork->sendFile(transferName, mask, fileCommandID);
				sentFile = TRUE;
				gxMapXferLog("t=%u send queues busy for %u ms, sending %s anyway (mask=0x%02X)",
					now, now - startTime, transferName.str(), mask);
			}
			else if (!sendBlockedLogged)
			{
				gxMapXferLog("t=%u waiting for send queues before %s", now, transferName.str());
				sendBlockedLogged = TRUE;
			}
		}

		// Snapshot every slot that still needs the file (used for the per-player display).
		// Reset first: a slot that left or just got the map must drop out of the gate.
		Bool pending = FALSE;
		for (Int i = 1; i < MAX_SLOTS; ++i)
		{
			slotPercent[i] = -2;
			const GameSlot *slot = TheGameInfo->getConstSlot(i);
			if (slot == nullptr || !slot->isHuman() || slot->hasMap())
				continue;
			pending = TRUE;
			slotPercent[i] = TheNetwork->getFileTransferProgress(i, transferName);
		}

		if (!pending)
		{
			fileTransferDone = TRUE;
			fileTransferPercent = 100;
		}
		else
		{
			fileTransferDone = TRUE;
			fileTransferPercent = 100;
			Bool anyConsidered = FALSE;

			for (Int i = 1; i < MAX_SLOTS; ++i)
			{
				if (slotPercent[i] == -2)
					continue;

				// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 The host waits for every
				// recipient; a client gates on its own slot only. Another player's stalled
				// transfer must not abort this client's launch.
				if (!amHost && i != localSlot)
					continue;

				anyConsidered = TRUE;
				Int pct = slotPercent[i];

				if (pct < 0)
				{
					// Not announced yet: the host is still sending an earlier file. Do not
					// start this file's inactivity clock; the separate announce bound covers
					// a file that is never announced at all.
					if (!notAnnouncedLogged)
					{
						gxMapXferLog("t=%u not announced yet: %s (slot %d)", now, transferName.str(), i);
						notAnnouncedLogged = TRUE;
					}
					lastProgressTime = now;
					fileTransferDone = FALSE;
					fileTransferPercent = 0;
					continue;
				}

				if (!announced)
				{
					announced = TRUE;
					announceTime = now;
				}

				if (lastPercent[i] != pct)
				{
					lastPercent[i] = pct;
					lastChangeTime[i] = now;
					lastProgressTime = now;
				}
				else if (lastChangeTime[i] == 0)
				{
					lastChangeTime[i] = now;
				}

				if (pct < 100)
					fileTransferDone = FALSE;
				if (pct < fileTransferPercent)
					fileTransferPercent = pct;

				Int bucket = (pct >= 100) ? 5 : (pct / 20);
				if (pct > 0 && bucket != lastBucket[i])
				{
					lastBucket[i] = bucket;
					if (pct >= 100)
						gxMapXferLog("t=%u %s 100%% complete (slot %d)", now, transferName.str(), i);
					else
						gxMapXferLog("t=%u %s at %d%% (slot %d)", now, transferName.str(), pct, i);
				}

				// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Host-side targeted retry.
				// A slot that has not moved for retryAfter ms gets a fresh announce and a fresh
				// send to that slot only. Uses the existing announce/send path; no new message type.
				if (amHost && pct < 100 && retries[i] < retryLimit && lastChangeTime[i] != 0
					&& now - lastChangeTime[i] > (UnsignedInt)retryAfter)
				{
					++retries[i];
					Int retryMask = (1 << i);
					// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Announce with the full mask
					// but send the data only to the stuck slot. processFileAnnounce() records a
					// 100% "already done" entry for every slot outside the announce mask; with a
					// subset mask the other clients would get a bogus 100 for this path and the
					// max-over-duplicates lookup would let a client that never received the file
					// proceed. With the full mask every recipient gets a real 0% entry, and the
					// max keeps each slot's genuine progress.
					UnsignedShort retryID = TheNetwork->sendFileAnnounce(transferName, mask);
					if (retryID != 0)
					{
						TheNetwork->sendFile(transferName, retryMask, retryID);
						gxMapXferLog("t=%u RETRY %d/%d file=%s slot=%d stuck at %d%% for %u ms",
							now, retries[i], retryLimit, transferName.str(), i, pct, now - lastChangeTime[i]);
						lastChangeTime[i] = now;
					}
					else
					{
						gxMapXferLog("t=%u RETRY FAILED file=%s slot=%d - host cannot open it",
							now, transferName.str(), i);
						retries[i] = retryLimit;
					}
				}
			}

			if (!anyConsidered)
			{
				fileTransferDone = TRUE;
				fileTransferPercent = 100;
			}
		}

		// Per-player display (including players this client does not gate on).
		for (Int i = 1; i < MAX_SLOTS; ++i)
		{
			if (slotPercent[i] == -2)
				continue;
			Int displayPercent = (slotPercent[i] < 0) ? 0 : slotPercent[i];
			if (displayPercent == 0)
				ls->processProgress(i, displayPercent, "MapTransfer:Preparing");
			else if (displayPercent < 100)
				ls->processProgress(i, displayPercent, "MapTransfer:Recieving");
			else
				ls->processProgress(i, displayPercent, "MapTransfer:Done");
		}

		if (fileTransferPercent < 100)
		{
			if (fileTransferPercent == 0)
				ls->processProgress(0, fileTransferPercent, "MapTransfer:Preparing");
			else
				ls->processProgress(0, fileTransferPercent, "MapTransfer:Sending");
		}
		else
		{
			ls->processProgress(0, fileTransferPercent, "MapTransfer:Done");
		}

		// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 The timeout is an inactivity budget,
		// not a total-duration budget: any progress resets it. A large map on a slow link no
		// longer aborts at 45 s, while a file that never moves still aborts at 45 s.
		if (!fileTransferDone)
		{
			UnsignedInt idle = now - lastProgressTime;
			UnsignedInt elapsed = now - startTime;
			Bool idleTimeout = (idle > (UnsignedInt)timeoutPeriod);
			Bool announceTimeout = (!amHost && !announced && elapsed > 60000);
			if (idleTimeout || announceTimeout)
			{
				gxMapXferLog("t=%u TIMEOUT waiting for %s (idle=%u ms announced=%d sent=%d min=%d%% elapsed=%u ms reason=%s)",
					now, transferName.str(), idle, announced ? 1 : 0, sentFile ? 1 : 0,
					fileTransferPercent, elapsed, announceTimeout ? "never-announced" : "no-progress");
				for (Int s = 1; s < MAX_SLOTS; ++s)
				{
					if (slotPercent[s] == -2)
						continue;
					gxMapXferLog("t=%u   slot %d last=%d%% idle=%u ms retries=%d",
						now, s, lastPercent[s], lastChangeTime[s] ? (now - lastChangeTime[s]) : 0, retries[s]);
				}
				break;
			}

			Int secondsLeft = (timeoutPeriod - (Int)idle) / 1000;
			if (secondsLeft < 0)
				secondsLeft = 0;
			ls->processTimeout(secondsLeft);
		}

		ls->update(fileTransferPercent);
		// GeneralsX @performance Flux Build v1.2.1 27/10/2026 Yield CPU timeslice to OS network
		// socket threads without adding artificial 15ms display latency.
		Sleep(1);
	}

	if (!fileTransferDone)
	{
		gxMapXferLog("t=%u transfer FAILED for %s after %u ms",
			timeGetTime(), transferName.str(), timeGetTime() - startTime);
		return FALSE;
	}

	gxMapXferLog("t=%u %s 100%% complete (elapsed=%u ms)",
		timeGetTime(), transferName.str(), timeGetTime() - startTime);
	return TRUE;
}

//-------------------------------------------------------------------------------------
//-------------------------------------------------------------------------------------

AsciiString GetBasePathFromPath( AsciiString path )
{
	// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Handle both separators. Map paths are
	// absolute on POSIX/Android ("/storage/.../Maps/foo/foo.map"); looking only for '\'
	// returned an empty base and built "\foo.tga" for every companion file.
	const char *bs = path.reverseFind('\\');
	const char *fs = path.reverseFind('/');
	const char *s = nullptr;
	if (bs != nullptr && fs != nullptr)
		s = (bs > fs) ? bs : fs;
	else if (bs != nullptr)
		s = bs;
	else
		s = fs;

	if (s)
	{
		Int len = s - path.str();

		AsciiString base;
		char *buf = base.getBufferForRead(len + 1);
		memcpy(buf, path.str(), len);
		buf[len] = 0;
		return buf;
	}
	return AsciiString::TheEmptyString;
}

AsciiString GetFileFromPath( AsciiString path )
{
	// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Handle both separators (see above).
	const char *bs = path.reverseFind('\\');
	const char *fs = path.reverseFind('/');
	const char *s = nullptr;
	if (bs != nullptr && fs != nullptr)
		s = (bs > fs) ? bs : fs;
	else if (bs != nullptr)
		s = bs;
	else
		s = fs;

	if (s)
		return s+1;
	return path;
}

AsciiString GetExtensionFromFile( AsciiString fname )
{
	const char *s = fname.reverseFind('.');
	if (s)
		return s+1;
	return fname;
}

AsciiString GetBaseFileFromFile( AsciiString fname )
{
	const char *s = fname.reverseFind('.');
	if (s)
	{
		Int len = s - fname.str();

		AsciiString base;
		char *buf = base.getBufferForRead(len + 1);
		memcpy(buf, fname.str(), len);
		buf[len] = 0;
		return buf;
	}
	return AsciiString::TheEmptyString;
}

AsciiString GetPreviewFromMap( AsciiString path )
{
	AsciiString fname = GetBaseFileFromFile(GetFileFromPath(path));
	AsciiString base = GetBasePathFromPath(path);

	AsciiString out;
	out.format("%s\\%s.tga", base.str(), fname.str());
	return out;
}

AsciiString GetINIFromMap( AsciiString path )
{
	AsciiString base = GetBasePathFromPath(path);

	AsciiString out;
	out.format("%s\\map.ini", base.str());
	return out;
}

AsciiString GetStrFileFromMap( AsciiString path )
{
	AsciiString base = GetBasePathFromPath(path);

	AsciiString out;
	out.format("%s\\map.str", base.str());
	return out;
}

AsciiString GetSoloINIFromMap( AsciiString path )
{
	AsciiString base = GetBasePathFromPath(path);

	AsciiString out;
	out.format("%s\\solo.ini", base.str());
	return out;
}

AsciiString GetAssetUsageFromMap( AsciiString path )
{
	AsciiString base = GetBasePathFromPath(path);

	AsciiString out;
	out.format("%s\\assetusage.txt", base.str());
	return out;
}

AsciiString GetReadmeFromMap( AsciiString path )
{
	AsciiString base = GetBasePathFromPath(path);

	AsciiString out;
	out.format("%s\\readme.txt", base.str());
	return out;
}

//-------------------------------------------------------------------------------------
//-------------------------------------------------------------------------------------

Bool DoAnyMapTransfers(GameInfo *game)
{
	TheGameInfo = game;
	if (TheGameInfo == nullptr)
		return TRUE;

	// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Resolve the lobby's relative map path
	// once; every companion path is built from the same absolute base.
	AsciiString mapPath = ResolveMapTransferPath(game->getMap());

	Int mask = 0;
	for (Int i = 1; i < MAX_SLOTS; ++i)
	{
		const GameSlot *slot = TheGameInfo->getConstSlot(i);
		if (slot != nullptr && slot->isHuman() && !slot->hasMap())
		{
			DEBUG_LOG(("Adding player %d to transfer mask", i));
			mask |= (1<<i);
		}
	}
	if (!mask)
		return TRUE;

	const Bool amHost = TheGameInfo->amIHost();
	Int contents = TheGameInfo->getMapContentsMask();

	if (amHost)
	{
		// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Re-verify the map-contents mask against
		// the files the sender can actually open. GameInfo::setMap() probes at lobby-refresh time;
		// a file can disappear in between, and the old probe accepted files the sender could not
		// open, which produced a 0% stall on every client.
		Int verified = 0;
		if (CanSendMapFile(mapPath))
			verified |= 1;
		if ((contents & 2) && CanSendMapFile(GetPreviewFromMap(mapPath)))
			verified |= 2;
		if ((contents & 4) && CanSendMapFile(GetINIFromMap(mapPath)))
			verified |= 4;
		if ((contents & 8) && CanSendMapFile(GetStrFileFromMap(mapPath)))
			verified |= 8;
		if ((contents & 16) && CanSendMapFile(GetSoloINIFromMap(mapPath)))
			verified |= 16;
		if ((contents & 32) && CanSendMapFile(GetAssetUsageFromMap(mapPath)))
			verified |= 32;
		if ((contents & 64) && CanSendMapFile(GetReadmeFromMap(mapPath)))
			verified |= 64;

		if (verified != contents)
			gxMapXferLog("t=%u host map mask 0x%02X -> 0x%02X after file verification for '%s'",
				timeGetTime(), contents, verified, mapPath.str());
		contents = verified;
	}

	gxMapXferLog("t=%u launch: map='%s' host=%d slot=%d mask=0x%02X contents=0x%02X",
		timeGetTime(), mapPath.str(), amHost ? 1 : 0, TheGameInfo->getLocalSlotNum(), mask, contents);

	TheShell->hideShell();
	MapTransferLoadScreen *ls = NEW MapTransferLoadScreen;
	ls->init(TheGameInfo);
	Bool ok = TRUE;

	// GeneralsX @bugfix Flux Build v1.2.5 28/10/2026 Transfer order: the two files the engine
	// consumes while loading the map come first, then the mandatory .map, then the cosmetic
	// files. A cosmetic file can therefore never block the .map, and a client that already has
	// the .map returns immediately from the remaining steps.
	struct TransferStep
	{
		AsciiString path;
		Int bit;
		Bool mandatory;
	};

	TransferStep steps[7];
	Int stepCount = 0;
	if (contents & 4)
	{
		steps[stepCount].path = GetINIFromMap(mapPath);
		steps[stepCount].bit = 4;
		steps[stepCount].mandatory = TRUE;
		++stepCount;
	}
	if (contents & 8)
	{
		steps[stepCount].path = GetStrFileFromMap(mapPath);
		steps[stepCount].bit = 8;
		steps[stepCount].mandatory = TRUE;
		++stepCount;
	}
	steps[stepCount].path = mapPath;
	steps[stepCount].bit = 1;
	steps[stepCount].mandatory = TRUE;
	++stepCount;
	if (contents & 2)
	{
		steps[stepCount].path = GetPreviewFromMap(mapPath);
		steps[stepCount].bit = 2;
		steps[stepCount].mandatory = FALSE;
		++stepCount;
	}
	if (contents & 16)
	{
		steps[stepCount].path = GetSoloINIFromMap(mapPath);
		steps[stepCount].bit = 16;
		steps[stepCount].mandatory = FALSE;
		++stepCount;
	}
	if (contents & 32)
	{
		steps[stepCount].path = GetAssetUsageFromMap(mapPath);
		steps[stepCount].bit = 32;
		steps[stepCount].mandatory = FALSE;
		++stepCount;
	}
	if (contents & 64)
	{
		steps[stepCount].path = GetReadmeFromMap(mapPath);
		steps[stepCount].bit = 64;
		steps[stepCount].mandatory = FALSE;
		++stepCount;
	}

	for (Int s = 0; s < stepCount; ++s)
	{
		// Recompute the mask before every file: a slot that left or already has the map drops out.
		Int currentMask = 0;
		for (Int i = 1; i < MAX_SLOTS; ++i)
		{
			const GameSlot *slot = TheGameInfo->getConstSlot(i);
			if (slot != nullptr && slot->isHuman() && !slot->hasMap())
				currentMask |= (1 << i);
		}
		if (!currentMask)
			break;

		if (amHost && !CanSendMapFile(steps[s].path))
		{
			if (steps[s].mandatory)
			{
				gxMapXferLog("t=%u mandatory file missing on host: %s (bit %d)",
					timeGetTime(), steps[s].path.str(), steps[s].bit);
				ok = FALSE;
				break;
			}
			gxMapXferLog("t=%u skipping optional file missing on host: %s (bit %d)",
				timeGetTime(), steps[s].path.str(), steps[s].bit);
			continue;
		}

		const Int timeoutMs = steps[s].mandatory ? 45000 : 10000;
		const Int retryAfterMs = steps[s].mandatory ? 5000 : 3000;
		const Int retryLimit = steps[s].mandatory ? 2 : 1;

		ok = doFileTransfer(steps[s].path, ls, currentMask, timeoutMs, retryAfterMs, retryLimit);
		if (!ok)
		{
			if (steps[s].mandatory)
			{
				gxMapXferLog("t=%u mandatory transfer failed: %s (bit %d)",
					timeGetTime(), steps[s].path.str(), steps[s].bit);
				break;
			}
			gxMapXferLog("t=%u skipping optional file after failed transfer: %s (bit %d)",
				timeGetTime(), steps[s].path.str(), steps[s].bit);
			ok = TRUE;
		}
	}

	delete ls;
	ls = nullptr;
	if (!ok)
		TheShell->showShell(TRUE);
	return ok;
}
