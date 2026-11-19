// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.
//
// GeneralsX @feature Flux Build 13/11/2026 v1.4.5 INI manifest (GXIniManifest.h).
#include "PreRTS.h"	// This must go first in EVERY cpp file in the GameEngine

#include "Common/GXIniManifest.h"
#include "Common/crc.h"
#include "Common/file.h"
#include "Common/FileSystem.h"
#include "Common/GlobalData.h"
#include "Common/ArchiveFileSystem.h"
#include "Common/ArchiveFile.h"
#include "Common/LocalFileSystem.h"
#include "Common/GXManagedMods.h"
#include "GameClient/MapUtil.h"	// gxDescribeMapFileSource: loose file / archive name

#include <chrono>
#include <map>

namespace
{
	Bool s_dumped = FALSE;

	// Same key for "Data\INI\Weapon.ini" from an archive and "Data/INI/weapon.ini" from a loose file.
	AsciiString normalizedKey(const AsciiString &path)
	{
		AsciiString key;
		for (Int i = 0; i < path.getLength(); ++i)
		{
			const char c = path.getCharAt(i);
			key.concat(c == '/' ? '\\' : c);
		}
		key.toLower();
		return key;
	}

	// Copy of INI.cpp's gxIsModSource (form masking, PLAN-027) -- kept out of INI.cpp on purpose, keep the
	// two identical: a loose file, a '!' archive, a launcher-managed mod, -mod archive or -mod folder.
	Bool isModSource(const AsciiString &filename)
	{
		if (TheLocalFileSystem != nullptr && TheLocalFileSystem->doesFileExist(filename.str()))
			return TRUE;
		ArchiveFile *archive = TheArchiveFileSystem != nullptr ? TheArchiveFileSystem->getArchiveFile(filename) : nullptr;
		if (archive == nullptr)
			return FALSE;
		const AsciiString path = archive->getName();
		const char *base = path.str();
		for (const char *c = path.str(); *c; ++c)
			if (*c == '/' || *c == '\\')
				base = c + 1;
		if (base[0] == '!' || GXManagedMods::contains(path.str()))
			return TRUE;
		if (TheGlobalData->m_modBIG.isNotEmpty() && path.compareNoCase(TheGlobalData->m_modBIG) == 0)
			return TRUE;
		if (TheGlobalData->m_modDir.isNotEmpty())
		{
			AsciiString dir = TheGlobalData->m_modDir;
			dir.removeLastChar();
			if (path.startsWithNoCase(dir.str()))
				return TRUE;
		}
		return FALSE;
	}

	// Review 12191: INI::loadFileDirectory skips a set's directory files from below a mod when that mod
	// provides the set as one file (Data\INI\X.ini from a mod -> Data\INI\X\** not from a mod are not read).
	// Returns the masking set file, or "" when the engine reads this file.
	AsciiString maskedBy(const AsciiString &key, const AsciiString &path)
	{
		// key is lower-case with '\\'; walk its parent directories below "data\\ini\\".
		static const char prefix[] = "data\\ini\\";
		if (!key.startsWithNoCase(prefix))
			return AsciiString::TheEmptyString;
		AsciiString dir = key;
		for (;;)
		{
			const char *sep = dir.reverseFind('\\');
			if (sep == nullptr)
				break;
			dir.truncateBy((Int)strlen(sep));	// drop the last component
			if (dir.getLength() <= (Int)strlen(prefix) - 1)
				break;
			AsciiString setFile = dir;
			setFile.concat(".ini");
			if (TheFileSystem->doesFileExist(setFile.str()) && isModSource(setFile) && !isModSource(path))
				return setFile;
		}
		return AsciiString::TheEmptyString;
	}

	// CRC of the file's bytes (the same CRC class calcCRC uses for maps). FALSE when it cannot be read.
	Bool fileCRC(const AsciiString &path, UnsignedInt &crcOut, UnsignedInt &sizeOut)
	{
		File *fp = TheFileSystem->openFile(path.str(), File::READ | File::BINARY);
		if (fp == nullptr)
			return FALSE;
		CRC crc;
		crc.clear();
		UnsignedByte buf[8192];
		Int num;
		UnsignedInt size = 0;
		while ((num = fp->read(buf, sizeof(buf))) > 0)
		{
			crc.computeCRC(buf, num);
			size += (UnsignedInt)num;
		}
		fp->close();
		crcOut = crc.get();
		sizeOut = size;
		return TRUE;
	}
}

void GXIniManifest::dumpOnce(const char *reason)
{
	// Main thread only: the file system's caches are not locked, so this does not run on a worker
	// thread; it reads a few MB once per session, in a menu.
	if (s_dumped || TheFileSystem == nullptr || TheGlobalData == nullptr)
		return;
	s_dumped = TRUE;
	const auto start = std::chrono::steady_clock::now();

	// The winning copy of every file the checksum covers, keyed so duplicates spelled differently
	// (archive vs loose, case, separator) count once.
	FilenameList found;
	TheFileSystem->getFileListInDirectory(AsciiString("Data\\INI\\"), AsciiString("*.ini"), found, TRUE);
	found.insert(AsciiString("Data\\Scripts\\SkirmishScripts.scb"));
	found.insert(AsciiString("Data\\Scripts\\MultiplayerScripts.scb"));
	std::map<AsciiString, AsciiString> files;	// normalized key -> path as listed
	for (FilenameList::const_iterator it = found.begin(); it != found.end(); ++it)
	{
		const AsciiString key = normalizedKey(*it);
		if (files.find(key) == files.end())
			files[key] = *it;
	}

	struct Line { UnsignedInt crc; UnsignedInt size; AsciiString source; AsciiString masked; Bool ok; };
	std::map<AsciiString, Line> lines;
	CRC total;
	total.clear();
	Int unreadable = 0;
	Int masked = 0;
	for (std::map<AsciiString, AsciiString>::const_iterator it = files.begin(); it != files.end(); ++it)
	{
		Line line;
		line.crc = 0;
		line.size = 0;
		line.ok = fileCRC(it->second, line.crc, line.size);
		if (!line.ok)
			++unreadable;
		line.source = line.ok ? gxDescribeMapFileSource(it->second) : AsciiString("missing");
		line.masked = maskedBy(it->first, it->second);
		lines[it->first] = line;
		if (line.masked.isNotEmpty())
		{
			++masked;
			continue;	// not read by the engine, so not in the checksum: not in the total either
		}
		// The total covers names and contents, so a missing or extra file changes it too.
		total.computeCRC(it->first.str(), it->first.getLength());
		total.computeCRC(&line.crc, sizeof(line.crc));
	}

	const long long ms = std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::steady_clock::now() - start).count();
	fprintf(stderr, "[GX-INI] manifest (%s): %d files (%d not read: a mod replaces their set), total 0x%08X"
		" | ini_crc %u exe_crc %u | %d unreadable, %lld ms\n",
		reason != nullptr ? reason : "?", (Int)lines.size(), masked, total.get(),
		(unsigned)TheGlobalData->m_iniCRC, (unsigned)TheGlobalData->m_exeCRC, unreadable, ms);
	fprintf(stderr, "[GX-INI] compare the total with the other player's; if it differs, the lines below show which file:\n");
	for (std::map<AsciiString, Line>::const_iterator it = lines.begin(); it != lines.end(); ++it)
	{
		if (it->second.masked.isNotEmpty())
			fprintf(stderr, "[GX-INI] 0x%08X %8u  %s  <- %s  (not read: %s from a mod replaces this set)\n", it->second.crc,
				it->second.size, it->first.str(), it->second.source.str(), it->second.masked.str());
		else
			fprintf(stderr, "[GX-INI] 0x%08X %8u  %s  <- %s\n", it->second.crc, it->second.size,
				it->first.str(), it->second.source.str());
	}
	fprintf(stderr, "[GX-INI] end of manifest\n");
	fflush(stderr);
}
