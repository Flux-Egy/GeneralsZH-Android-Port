// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.
//
// GeneralsX @feature Flux Build 13/11/2026 v1.4.5 INI manifest ([GX-INI] lines in the engine log).
//
// The INI checksum a lobby compares is one rolling CRC over every INI line the engine read
// (GameEngine.cpp xferCRC, INI.cpp), so a mismatch says nothing about WHICH file differs. Two
// players with "the same mod" differ by a missing or outdated file. This writes, once per session,
// one line per file the checksum is built from -- every Data\INI\**\*.ini and the two .scb scripts
// the Android exe checksum hashes -- with the CRC of that file's bytes, its size and where it was
// read from (loose file or archive name), sorted by path, plus a total. Two players compare the
// totals, then the lines. Read-only: the real checksums are not touched.
#pragma once

namespace GXIniManifest
{
	/// Writes the manifest to the engine log unless it was already written this session.
	/// reason: why now ("LAN lobby", "online lobby", "join refused"). Main thread only.
	void dumpOnce(const char *reason);
}
