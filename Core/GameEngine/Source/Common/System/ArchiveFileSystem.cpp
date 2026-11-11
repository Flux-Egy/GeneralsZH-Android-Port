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

//----------------------------------------------------------------------------
//
//                       Westwood Studios Pacific.
//
//                       Confidential Information
//                Copyright (C) 2001 - All Rights Reserved
//
//----------------------------------------------------------------------------
//
// Project:   Generals
//
// Module:    Game Engine Common
//
// File name: ArchiveFileSystem.cpp
//
// Created:   11/26/01 TR
//
//----------------------------------------------------------------------------

//----------------------------------------------------------------------------
//         Includes
//----------------------------------------------------------------------------

#include "PreRTS.h"
#include "Common/ArchiveFile.h"
#include "Common/ArchiveFileSystem.h"
#include "Common/GlobalData.h"
#include "Common/GXManagedMods.h"
#include "Common/LocalFileSystem.h"
#include "Common/AsciiString.h"
#include "Common/PerfTimer.h"

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <map>
#include <cstring>
#include <string>


//----------------------------------------------------------------------------
//         Externals
//----------------------------------------------------------------------------



//----------------------------------------------------------------------------
//         Defines
//----------------------------------------------------------------------------



//----------------------------------------------------------------------------
//         Private Types
//----------------------------------------------------------------------------


//----------------------------------------------------------------------------
//         Private Data
//----------------------------------------------------------------------------



//----------------------------------------------------------------------------
//         Public Data
//----------------------------------------------------------------------------

ArchiveFileSystem *TheArchiveFileSystem = nullptr;


//----------------------------------------------------------------------------
//         Private Prototypes
//----------------------------------------------------------------------------



//----------------------------------------------------------------------------
//         Private Functions
//----------------------------------------------------------------------------

#if RTS_ZEROHOUR
// Presence turns the community data patch off without removing it -- the
// launcher writes and deletes this, and loadMods() below is the only reader.
static const char* const kCommunityPatchDisableMarker = "gx_no_community_patch.txt";
// Presence mounts the patch even when a data mod is installed; also written by the launcher.
static const char* const kCommunityPatchWithModsMarker = "gx_community_patch_with_mods.txt";
#endif

static AsciiString getBaseFilename(const AsciiString& path)
{
	const char* str = path.str();
	const char* p1 = strrchr(str, '\\');
	const char* p2 = strrchr(str, '/');
	const char* sep = (p1 == nullptr) ? p2 : ((p2 == nullptr) ? p1 : ((p1 > p2) ? p1 : p2));
	return sep ? AsciiString(sep + 1) : path;
}

// GeneralsX @bugfix Android port 19/10/2026 Whether an archive in the game folder is a gameplay mod,
// for keeping the GeneralsOnline community patch out (see loadMods). A '!' archive counts when it
// carries any Data\INI file, as before. Others count only when they define units or game rules:
// Cold War Crisis ships CWC.big, no '!', its generals in Data\INI\Object\CWC\, and the patch
// mounted next to it put its own split INI set on top of the mod's (issue #55). Retail archives carry
// INI too -- the language ones Video.ini and Credits.ini, a patch MappedImages -- but no object or
// rule files outside INIZH.big / INI.big, so those keep the patch for everyone who plays unmodded.
// GeneralsX @bugfix Flux Build 05/11/2026 Each rule file in both of its forms: the single file
// (Weapon.ini) and the split directory the community patch uses (Weapon\*.ini) -- an archive
// replacing one of the patch's split files changes the same definitions.
static Bool gxIsGameplayIni(const AsciiString &lowerPath)
{
	static const char *const kRuleFiles[] = {
		"data\\ini\\object", "data\\ini\\weapon", "data\\ini\\commandset",
		"data\\ini\\commandbutton", "data\\ini\\playertemplate", "data\\ini\\upgrade",
		"data\\ini\\science", "data\\ini\\specialpower", "data\\ini\\locomotor",
		"data\\ini\\armor", "data\\ini\\gamedata", "data\\ini\\aidata",
	};
	std::string path(lowerPath.str());
	for (size_t i = 0; i < path.size(); ++i)
		if (path[i] == '/')
			path[i] = '\\';
	for (size_t i = 0; i < sizeof(kRuleFiles) / sizeof(kRuleFiles[0]); ++i)
	{
		const size_t n = strlen(kRuleFiles[i]);
		if (path.compare(0, n, kRuleFiles[i]) == 0
			&& (path.compare(n, std::string::npos, ".ini") == 0 || (path.size() > n && path[n] == '\\')))
			return TRUE;
	}
	return FALSE;
}

// GeneralsX @bugfix Flux Build 03/11/2026 The archives loadManagedMods() mounted, for
// loadIntoDirectoryTree's sorted insert (the community patch never goes ahead of them). The archive
// file system is a singleton and these live until it is destroyed, so plain pointers are enough.
static std::vector<const ArchiveFile *> s_gxManagedModFiles;

static Bool isManagedModArchive(const ArchiveFile *archive)
{
	return std::find(s_gxManagedModFiles.begin(), s_gxManagedModFiles.end(), archive) != s_gxManagedModFiles.end();
}

// GeneralsX @bugfix Flux Build 05/11/2026 Interface packs and the community patch. A '!' archive
// with a Data\INI file counted as a data mod, and a data mod keeps the GeneralsOnline community
// patch out -- so the Leikeze "Indicators" hotkey pack (CommandButton.ini and MappedImages, nothing
// else) left the INI checksum at retail's 4272612339 against every lobby's 2180732466: "This game's
// data differs from yours" (owner's log: "community patch not mounted: a mod is installed").
//
// Two kinds of interface INI, judged differently:
//  - MappedImages, ControlBarScheme, InGameUI, CommandMap: loaded without the checksum and read by
//    the client only (Image.cpp, ControlBarScheme.cpp, InGameUI.cpp, the meta map). They pass when
//    they hold nothing else: INI::load runs whatever block it finds in any file, so one of them
//    carrying, say, a Weapon block is a data mod (gxClientOnlyIniIsClean).
//  - CommandButton: also outside the checksum, but NOT client-only. The simulation reads buttons
//    (Object::canProduceUpgrade on every MSG_QUEUE_UPGRADE, AIUpdate, the AI player, scripts), and
//    with the patch mounted a '!' archive's single CommandButton.ini masks the patch's whole split
//    CommandButton\ set (INI::loadFileDirectory). So a '!' archive's buttons pass only when they are
//    the patch's buttons with other pictures: every button the patch defines is there (a missing one
//    would stop a CommandSet from loading), and every field the game logic reads (the non-display
//    half of CommandButton::s_commandButtonFieldParseTable) reads the same. Labels, images, borders,
//    cursors and sounds may differ.
// The reading (gxParseButtonLogic) follows INI.cpp, and anything it does not fully understand fails:
// ';' starts a comment and nothing else does, control characters are blanks, '=' is one more
// separator, block and field names are case-sensitive (strcmp), "End" is not. A header inside an
// open block, an unknown field, a block of another type, an unterminated block, or no patch buttons
// to compare with -- each makes the archive a data mod, as before.
// Archives without the '!' are not a mod source to INI::loadFileDirectory (their CommandButton.ini
// would load on top of the patch's split files, not instead of them), so for them CommandButton.ini
// stays a rule file (gxIsGameplayIni).
static std::string gxSlashPath(const AsciiString &lowerPath)
{
	std::string path(lowerPath.str());
	for (size_t i = 0; i < path.size(); ++i)
		if (path[i] == '/')
			path[i] = '\\';
	return path;
}

static Bool gxIsFileOrDirectory(const std::string &path, const char *stem)
{
	const size_t n = strlen(stem);
	return path.compare(0, n, stem) == 0 && (path.compare(n, std::string::npos, ".ini") == 0 || (path.size() > n && path[n] == '\\'));
}

// The block type a client-only INI may hold, or nullptr when the path is not one.
static const char *gxClientOnlyBlock(const AsciiString &lowerPath)
{
	const std::string path = gxSlashPath(lowerPath);
	if (path.compare(0, strlen("data\\ini\\mappedimages\\"), "data\\ini\\mappedimages\\") == 0)
		return "MappedImage";
	if (gxIsFileOrDirectory(path, "data\\ini\\controlbarscheme"))
		return "ControlBarScheme";
	if (gxIsFileOrDirectory(path, "data\\ini\\ingameui"))
		return "InGameUI";
	if (gxIsFileOrDirectory(path, "data\\ini\\commandmap"))
		return "CommandMap";
	return nullptr;
}

static Bool gxIsCommandButtonIni(const AsciiString &lowerPath)
{
	return gxIsFileOrDirectory(gxSlashPath(lowerPath), "data\\ini\\commandbutton");
}

// One line as INI::readLine and strtok(getSeps()) see it.
static void gxIniTokens(const char *text, size_t length, std::vector<std::string> &tokens)
{
	tokens.clear();
	std::string token;
	for (size_t i = 0; i < length; ++i)
	{
		char c = text[i];
		if (c == ';')
			break;
		if (c > 0 && c < 32)
			c = ' ';
		if (c == ' ' || c == '=')
		{
			if (!token.empty())
				tokens.push_back(token);
			token.clear();
		}
		else
		{
			token += c;
		}
	}
	if (!token.empty())
		tokens.push_back(token);
}

// FALSE for text INI.cpp would read differently from a plain line split: a line of
// INI_MAX_CHARS_PER_LINE (1028) characters or more, whose rest readLine reads as a new line even
// after a ';', or a NUL byte, which ends readLine's string mid-line. Neither is in a real file.
template <class F>
static Bool gxForEachIniLine(const char *text, size_t length, F f)
{
	static const size_t kMaxLine = 1027;
	std::vector<std::string> tokens;
	size_t pos = 0;
	while (pos < length)
	{
		size_t eol = pos;
		while (eol < length && text[eol] != '\n')
		{
			if (text[eol] == 0)
				return FALSE;
			++eol;
		}
		if (eol - pos > kMaxLine)
			return FALSE;
		gxIniTokens(text + pos, eol - pos, tokens);
		pos = eol + 1;
		if (!tokens.empty() && !f(tokens))
			return TRUE;
	}
	return TRUE;
}

// INI.cpp's block types (theTypeTable): a client-only INI holding any but the allowed ones
// (nullptr-terminated) is a data mod.
static Bool gxClientOnlyIniIsClean(const char *text, size_t length, const char *const *allowed)
{
	static const char *const kBlocks[] = {
		"AIData", "Animation", "Armor", "AudioEvent", "AudioSettings", "BenchProfile", "Bridge", "Campaign",
		"ChallengeGenerals", "CommandButton", "CommandMap", "CommandSet", "ControlBarResizer", "ControlBarScheme",
		"CrateData", "Credits", "DamageFX", "DialogEvent", "DrawGroupInfo", "DynamicGameLOD", "EvaEvent", "FXList",
		"GameData", "HeaderTemplate", "InGameUI", "LODPreset", "Language", "Locomotor", "MapCache", "MapData",
		"MappedImage", "MiscAudio", "Mouse", "MouseCursor", "MultiplayerColor", "MultiplayerSettings",
		"MultiplayerStartingMoneyChoice", "MusicTrack", "Object", "ObjectCreationList", "ObjectReskin",
		"OnlineChatColors", "ParticleSystem", "PlayerTemplate", "Rank", "ReallyLowMHz", "Road", "Science",
		"ScriptAction", "ScriptCondition", "ShellMenuScheme", "SpecialPower", "StaticGameLOD", "Terrain", "Upgrade",
		"Video", "WaterSet", "WaterTransparency", "Weapon", "Weather", "WebpageURL", "WindowTransition",
	};
	Bool clean = TRUE;
	const Bool readable = gxForEachIniLine(text, length, [&](const std::vector<std::string> &tokens) {
		for (size_t i = 0; i < sizeof(kBlocks) / sizeof(kBlocks[0]); ++i)
		{
			// Any line, nested or not, whose first word is another block type: a field of the same
			// name only costs a false "mod", never a missed one.
			if (strcmp(tokens[0].c_str(), kBlocks[i]) != 0)
				continue;
			Bool ok = FALSE;
			for (const char *const *a = allowed; *a != nullptr; ++a)
				if (strcmp(kBlocks[i], *a) == 0)
					ok = TRUE;
			if (!ok)
			{
				clean = FALSE;
				return false;
			}
		}
		return true;
	});
	return readable && clean;
}

// Button name -> logic field -> value, as the field's parser reads it.
typedef std::map<std::string, std::map<std::string, std::string> > GXButtonLogic;

// Reads the CommandButton blocks of one INI text into `buttons`; FALSE when the text holds anything
// this cannot vouch for. A name defined twice merges, later fields winning, as
// ControlBar::parseCommandButtonDefinition does outside override loads.
static Bool gxParseButtonLogic(const char *text, size_t length, GXButtonLogic &buttons)
{
	static const char *const kDisplayFields[] = {
		"TextLabel", "DescriptLabel", "PurchasedLabel", "ConflictingLabel", "ButtonImage", "CursorName",
		"InvalidCursorName", "ButtonBorderType", "RadiusCursorType", "UnitSpecificSound",
	};
	std::map<std::string, std::string> *current = nullptr;
	Bool ok = TRUE;
	const Bool readable = gxForEachIniLine(text, length, [&](const std::vector<std::string> &tokens) {
		const std::string &first = tokens[0];
		if (current == nullptr)
		{
			if (first != "CommandButton" || tokens.size() < 2)
			{
				ok = FALSE;
				return false;
			}
			current = &buttons[tokens[1]];
			return true;
		}
		if (stricmp(first.c_str(), "End") == 0)
		{
			current = nullptr;
			return true;
		}
		for (size_t i = 0; i < sizeof(kDisplayFields) / sizeof(kDisplayFields[0]); ++i)
			if (first == kDisplayFields[i])
				return true;
		std::string value;
		if (first == "Options" && tokens.size() < 2)
		{
			// parseBitString32 with no names keeps the bits set before: not a value to compare.
			ok = FALSE;
			return false;
		}
		if (first == "Options" || first == "Science")
		{
			// Lists: every token, in order (a different order or spelling fails, safely).
			for (size_t i = 1; i < tokens.size(); ++i)
			{
				if (!value.empty())
					value += ' ';
				value += tokens[i];
			}
			if (first == "Options")
				for (size_t i = 0; i < value.size(); ++i)
					value[i] = (char)toupper((unsigned char)value[i]); // looked up with stricmp
		}
		else if (first == "Command" || first == "WeaponSlot" || first == "MaxShotsToFire")
		{
			// One token; Command and WeaponSlot are looked up with stricmp.
			value = tokens.size() > 1 ? tokens[1] : std::string();
			for (size_t i = 0; i < value.size(); ++i)
				value[i] = (char)toupper((unsigned char)value[i]);
		}
		else if (first == "Object" || first == "Upgrade" || first == "SpecialPower")
		{
			// One token, looked up case-sensitively; "None" (any case) is no template.
			value = tokens.size() > 1 ? tokens[1] : std::string();
			if (stricmp(value.c_str(), "None") == 0)
				value.clear();
		}
		else
		{
			// A field CommandButton does not have (release builds skip it silently, a header
			// inside an open block included): nothing to vouch for.
			ok = FALSE;
			return false;
		}
		(*current)[first] = value;
		return true;
	});
	return readable && ok && current == nullptr;
}

// The CommandButton INI of one archive; FALSE when any of it cannot be vouched for.
static Bool gxReadButtonLogic(ArchiveFile *archive, GXButtonLogic &buttons)
{
	FilenameList iniFiles;
	archive->getFileListInDirectory(AsciiString(""), AsciiString(""), AsciiString("*.ini"), iniFiles, TRUE);
	for (FilenameList::const_iterator f = iniFiles.begin(); f != iniFiles.end(); ++f)
	{
		AsciiString lower = *f;
		lower.toLower();
		if (!gxIsCommandButtonIni(lower))
			continue;
		// INI::loadDirectory reads a folder's own files before its subfolders' -- not this set's
		// order, so a subfolder is not something to vouch for.
		const std::string path = gxSlashPath(lower);
		const size_t stem = strlen("data\\ini\\commandbutton\\");
		if (path.size() > stem && path.find('\\', stem) != std::string::npos)
			return FALSE;
		File *file = archive->openFile(f->str());
		if (file == nullptr)
			return FALSE;
		const Int length = file->size();
		char *text = file->readEntireAndClose();
		const Bool ok = text != nullptr && gxParseButtonLogic(text, length > 0 ? (size_t)length : 0, buttons);
		delete[] text;
		if (!ok)
			return FALSE;
	}
	return TRUE;
}

static Bool gxReadClientOnlyIni(ArchiveFile *archive, const AsciiString &path, const char *const *allowed)
{
	File *file = archive->openFile(path.str());
	if (file == nullptr)
		return FALSE;
	const Int length = file->size();
	char *text = file->readEntireAndClose();
	const Bool clean = text != nullptr && gxClientOnlyIniIsClean(text, length > 0 ? (size_t)length : 0, allowed);
	delete[] text;
	return clean;
}

// The community patch's buttons, read once per loadMods: `valid` is FALSE when there is no patch or
// its buttons could not all be read, and then no archive's buttons can pass.
struct GXPatchButtons
{
	ArchiveFile *archive = nullptr;
	Bool read = FALSE;
	Bool valid = FALSE;
	GXButtonLogic buttons;
};

static Bool gxButtonsMatchPatch(const AsciiString &baseName, ArchiveFile *archive, GXPatchButtons &patch)
{
	if (!patch.read)
	{
		patch.read = TRUE;
		patch.valid = patch.archive != nullptr && gxReadButtonLogic(patch.archive, patch.buttons) && !patch.buttons.empty();
		if (patch.archive != nullptr && !patch.valid)
			fprintf(stderr, "[gxbig] the community patch's CommandButtons could not be read: no '!' archive's buttons can be compared\n");
	}
	if (!patch.valid)
		return FALSE;
	GXButtonLogic mine;
	if (!gxReadButtonLogic(archive, mine))
	{
		fprintf(stderr, "[gxbig] %s counts as a data mod: its CommandButton INI holds more than buttons, or an unterminated block\n",
			baseName.str());
		return FALSE;
	}
	for (GXButtonLogic::const_iterator b = patch.buttons.begin(); b != patch.buttons.end(); ++b)
	{
		GXButtonLogic::const_iterator m = mine.find(b->first);
		if (m == mine.end())
		{
			fprintf(stderr, "[gxbig] %s counts as a data mod: it lacks the patch's CommandButton %s\n",
				baseName.str(), b->first.c_str());
			return FALSE;
		}
		// Both ways round: a field only one side sets differs too (an absent one is empty).
		for (int pass = 0; pass < 2; ++pass)
		{
			const std::map<std::string, std::string> &a = pass == 0 ? b->second : m->second;
			const std::map<std::string, std::string> &other = pass == 0 ? m->second : b->second;
			for (std::map<std::string, std::string>::const_iterator field = a.begin(); field != a.end(); ++field)
			{
				std::map<std::string, std::string>::const_iterator o = other.find(field->first);
				const std::string otherValue = o == other.end() ? std::string() : o->second;
				if (field->second != otherValue)
				{
					fprintf(stderr, "[gxbig] %s counts as a data mod: CommandButton %s %s differs from the patch's\n",
						baseName.str(), b->first.c_str(), field->first.c_str());
					return FALSE;
				}
			}
		}
	}
	// Every patch button was found above, so more here are buttons the patch does not have: map
	// scripts and map.ini CommandSets name buttons, and one only this player has would act here
	// and nowhere else.
	if (mine.size() != patch.buttons.size())
	{
		fprintf(stderr, "[gxbig] %s counts as a data mod: it defines %u CommandButtons the patch does not\n",
			baseName.str(), (unsigned)(mine.size() - patch.buttons.size()));
		return FALSE;
	}
	fprintf(stderr, "[gxbig] %s: its %u CommandButtons match the patch's in every logic field (only display differs)\n",
		baseName.str(), (unsigned)mine.size());
	return TRUE;
}

// Whether an archive's INI make it a data mod. anyIni: a '!' archive or a launcher-managed mod (a
// mod source to INI::loadFileDirectory), where any Data\INI file counts but the interface INI above;
// else only rule files.
static Bool gxArchiveIsDataMod(const AsciiString &baseName, ArchiveFile *archive, Bool anyIni, GXPatchButtons &patch)
{
	FilenameList iniFiles;
	archive->getFileListInDirectory(AsciiString(""), AsciiString(""), AsciiString("*.ini"), iniFiles, TRUE);
	Bool buttons = FALSE;
	for (FilenameList::const_iterator f = iniFiles.begin(); f != iniFiles.end(); ++f)
	{
		AsciiString lower = *f;
		lower.toLower();
		if (anyIni)
		{
			const char *ownBlock = gxClientOnlyBlock(lower);
			if (ownBlock != nullptr)
			{
				const char *const allowed[] = { ownBlock, nullptr };
				if (gxReadClientOnlyIni(archive, *f, allowed))
					continue;
				fprintf(stderr, "[gxbig] %s counts as a data mod: %s holds a block other than %s\n",
					baseName.str(), f->str(), ownBlock);
				return TRUE;
			}
			// Data\<Language>\ INI (CommandMap.ini, HeaderTemplate.ini, Language.ini) go through
			// INI::load as well, outside the checksum: the same test, for their own block types.
			if ((lower.startsWith("data\\") || lower.startsWith("data/")) && !lower.startsWith("data\\ini\\")
				&& !lower.startsWith("data/ini/"))
			{
				static const char *const kLanguageBlocks[] = { "CommandMap", "HeaderTemplate", "Language", nullptr };
				if (gxReadClientOnlyIni(archive, *f, kLanguageBlocks))
					continue;
				fprintf(stderr, "[gxbig] %s counts as a data mod: %s holds a block other than its own\n",
					baseName.str(), f->str());
				return TRUE;
			}
			if (gxIsCommandButtonIni(lower))
			{
				buttons = TRUE;
				continue;
			}
		}
		if (anyIni ? (lower.startsWith("data\\ini\\") || lower.startsWith("data/ini/")) : gxIsGameplayIni(lower))
			return TRUE;
	}
	return buttons && !gxButtonsMatchPatch(baseName, archive, patch);
}

static Bool gxIsDataModArchive(const AsciiString &baseName, ArchiveFile *archive, GXPatchButtons &patch)
{
	if (archive == nullptr || baseName.isEmpty())
		return FALSE;
	const Bool bang = baseName.getCharAt(0) == '!';
	if (!bang && (baseName.compareNoCase("INIZH.big") == 0 || baseName.compareNoCase("INI.big") == 0))
		return FALSE;
	return gxArchiveIsDataMod(baseName, archive, bang, patch);
}



//----------------------------------------------------------------------------
//         Public Functions
//----------------------------------------------------------------------------

//------------------------------------------------------
// ArchivedFileInfo
//------------------------------------------------------
ArchiveFileSystem::ArchiveFileSystem()
{
}

ArchiveFileSystem::~ArchiveFileSystem()
{
	ArchiveFileMap::iterator iter = m_archiveFileMap.begin();
	while (iter != m_archiveFileMap.end()) {
		ArchiveFile *file = iter->second;
		delete file;
		iter++;
	}
}

void ArchiveFileSystem::loadIntoDirectoryTree(ArchiveFile *archiveFile, Bool overwrite, Bool sortedByName)
{

	FilenameList filenameList;

	archiveFile->getFileListInDirectory("", "", "*", filenameList, TRUE);

	FilenameListIter it = filenameList.begin();

	while (it != filenameList.end())
	{
		ArchivedDirectoryInfo *dirInfo = &m_rootDirectory;

		AsciiString path;
		AsciiString token;
		AsciiString tokenizer = *it;
		tokenizer.toLower();
		Bool infoInPath = tokenizer.nextToken(&token, "\\/");

		while (infoInPath && (!token.find('.') || tokenizer.find('.')))
		{
			path.concat(token);
			path.concat('\\');

			ArchivedDirectoryInfoMap::iterator tempiter = dirInfo->m_directories.find(token);
			if (tempiter == dirInfo->m_directories.end())
			{
				dirInfo = &(dirInfo->m_directories[token]);
				dirInfo->m_path = path;
				dirInfo->m_directoryName = token;
			}
			else
			{
				dirInfo = &tempiter->second;
			}

			infoInPath = tokenizer.nextToken(&token, "\\/");
		}

		ArchivedFileLocationMap::iterator fileIt;
		if (sortedByName)
		{
			// Insert by case-insensitive archive filename, matching game folder load
			// order where the alphabetically first archive wins.
			const AsciiString baseName = getBaseFilename(archiveFile->getName());
			std::pair<ArchivedFileLocationMap::iterator, ArchivedFileLocationMap::iterator> range = dirInfo->m_files.equal_range(token);
			fileIt = range.first;
			// GeneralsX @bugfix Flux Build 03/11/2026 Never ahead of a launcher-managed mod. Those are
			// mounted before this (loadManagedMods, at init) at the front of the range, and a name
			// comparison alone put the community patch ("500_900_...") ahead of every mod archive
			// whose name sorts after it -- with the launcher's "patch with mods" switch on, the patch
			// overrode the mod it was meant to sit under. Mods > patch > retail, always.
			while (fileIt != range.second
				&& (isManagedModArchive(fileIt->second)
					|| getBaseFilename(fileIt->second->getName()).compareNoCase(baseName) <= 0))
				++fileIt;
		}
		else if (overwrite)
		{
			// When overwriting, place the new value at the very beginning of the key range.
			fileIt = dirInfo->m_files.equal_range(token).first;
		}
		else
		{
			// Append to the end of the key list.
			fileIt = dirInfo->m_files.end();
		}

		dirInfo->m_files.insert(fileIt, std::make_pair(token, archiveFile));

#if defined(DEBUG_LOGGING) && ENABLE_FILESYSTEM_LOGGING
		{
			const stl::const_range<ArchivedFileLocationMap> range = stl::get_range(dirInfo->m_files, token, 0);
			if (range.distance() >= 2)
			{
				ArchivedFileLocationMap::const_iterator rangeIt0;
				ArchivedFileLocationMap::const_iterator rangeIt1;

				if (overwrite)
				{
					rangeIt0 = range.begin;
					rangeIt1 = std::next(rangeIt0);

					DEBUG_LOG(("ArchiveFileSystem::loadIntoDirectoryTree - adding file %s, archived in %s, overwriting same file in %s",
						it->str(),
						rangeIt0->second->getName().str(),
						rangeIt1->second->getName().str()
					));
				}
				else
				{
					rangeIt1 = std::prev(range.end);
					rangeIt0 = std::prev(rangeIt1);

					DEBUG_LOG(("ArchiveFileSystem::loadIntoDirectoryTree - adding file %s, archived in %s, overwritten by same file in %s",
						it->str(),
						rangeIt1->second->getName().str(),
						rangeIt0->second->getName().str()
					));
				}
			}
			else
			{
				DEBUG_LOG(("ArchiveFileSystem::loadIntoDirectoryTree - adding file %s, archived in %s", it->str(), archiveFile->getName().str()));
			}
		}
#endif

		it++;
	}
}

// GeneralsX @feature Android port 18/10/2026 The .big files of the mods enabled in the launcher's
// Mods screen (GXManagedMods.h), lowest priority first, each mounted over the last (overwrite).
//
// GeneralsX @bugfix Flux Build 03/11/2026 Inside one mod, the archive whose FILE NAME sorts first
// wins -- the retail rule for archives in the game folder, and the one mod authors name their files
// for: Rise of the Reds ships !Rotr_*, !!Rotr_*, !!!Rotr_* and !!!!!!!!Hanpatch_*, each '!' one step
// higher. v1.3.1 mounted them in ascending order, so the alphabetically last archive won and the
// base overrode its own patches. Sorted by the file name, not the path: the full path put an archive
// in a subfolder (HanPatch/!!!!!!!!Hanpatch_1.big) behind every '!' archive at the mod's top level.
// The launcher's pack of the mod's loose files (!!loose_files.big) still goes on top, as loose
// files beat every archive in a retail game folder.
struct GXArchiveNameDescending
{
	bool operator()(const AsciiString &a, const AsciiString &b) const
	{
		const int byName = getBaseFilename(a).compareNoCase(getBaseFilename(b));
		return byName != 0 ? byName > 0 : a.compareNoCase(b) > 0;
	}
};

// GeneralsX @feature Flux Build 05/11/2026 Hotkey and interface packs are often passed around as
// BIG archives without the ".big" ("!HotkeysLeikezeZH", "*.zh"). They are mounted under their own
// names, in the same name order as the *.big beside them, so "!..." still sorts ahead of the retail
// archives. The name changes nothing else: what an archive contains decides whether it is a
// gameplay mod (gxIsDataModArchive) and what reaches the INI checksum, as for any .big.
static Bool gxHasBigSignature(const AsciiString &path)
{
	File *file = TheLocalFileSystem->openFile(path.str(), File::READ | File::BINARY);
	if (file == nullptr)
		return FALSE;
	char magic[4] = { 0, 0, 0, 0 };
	const Int read = file->read(magic, 4);
	file->close();
	// BIGF only: StdBIGFileSystem::openArchiveFile refuses BIG4, so a BIG4 here would never mount.
	return read == 4 && magic[0] == 'B' && magic[1] == 'I' && magic[2] == 'G' && magic[3] == 'F';
}

void ArchiveFileSystem::addRenamedBigArchives(const AsciiString &dir, FilenameList &list, Bool searchSubdirectories)
{
	// getFileListInDirectory compares extensions only: "*" lists the files that have none.
	static const char *const kMasks[] = { "*", "*.zh" };
	for (size_t m = 0; m < sizeof(kMasks) / sizeof(kMasks[0]); ++m)
	{
		FilenameList found;
		TheLocalFileSystem->getFileListInDirectory(dir, AsciiString(""), AsciiString(kMasks[m]), found, searchSubdirectories);
		for (FilenameList::const_iterator it = found.begin(); it != found.end(); ++it)
		{
			if (gxHasBigSignature(*it))
			{
				list.insert(*it);
				fprintf(stderr, "[gxbig] archive without .big: %s\n", it->str());
			}
		}
	}
}

static void gxManagedModArchives(std::vector<AsciiString> &out)
{
	const std::vector<std::string> &folders = GXManagedMods::folders();
	for (size_t i = folders.size(); i-- > 0;)
	{
		FilenameList files;
		TheLocalFileSystem->getFileListInDirectory(AsciiString(folders[i].c_str()), AsciiString(""),
			AsciiString("*.big"), files, TRUE);
		ArchiveFileSystem::addRenamedBigArchives(AsciiString(folders[i].c_str()), files, TRUE);

		std::vector<AsciiString> archives;
		AsciiString loosePack;
		for (FilenameList::const_iterator it = files.begin(); it != files.end(); ++it)
		{
			if (getBaseFilename(*it).startsWithNoCase("!!loose_files"))
				loosePack = *it;
			else
				archives.push_back(*it);
		}
		std::sort(archives.begin(), archives.end(), GXArchiveNameDescending());
		out.insert(out.end(), archives.begin(), archives.end());
		if (loosePack.isNotEmpty())
		{
			out.push_back(loosePack);
		}

		fprintf(stderr, "[gxbig] mod folder %s: %u archive(s), priority %u of %u\n", folders[i].c_str(),
			(unsigned)files.size(), (unsigned)(i + 1), (unsigned)folders.size());
	}
	fflush(stderr);
}

// GeneralsX @bugfix Android port 25/10/2026 The launcher's managed mods, mounted by the archive
// file system's init right after the game's own archives -- before GameData.ini and everything else
// is read, as a '!' archive in the game folder is. loadMods() runs after GameData.ini, so a mod in
// mods/ lost its GameData.ini changes (camera, build speeds, game rules) and loaded differently
// from the same archive placed in the game folder.
void ArchiveFileSystem::loadManagedMods()
{
	std::vector<AsciiString> managedArchives;
	gxManagedModArchives(managedArchives);
	for (size_t m = 0; m < managedArchives.size(); ++m)
	{
		ArchiveFile *archiveFile = openArchiveFile(managedArchives[m].str());
		fprintf(stderr, "[gxbig] mod %s: %s\n", archiveFile != nullptr ? "loaded" : "FAILED TO OPEN",
			managedArchives[m].str());
		if (archiveFile != nullptr)
		{
			loadIntoDirectoryTree(archiveFile, TRUE);
			m_archiveFileMap[managedArchives[m]] = archiveFile;
			m_managedModArchives.push_back(managedArchives[m]);
			s_gxManagedModFiles.push_back(archiveFile);
			fprintf(stderr, "[gxbig] mod mount %u of %u (the last one wins): %s\n", (unsigned)(m + 1),
				(unsigned)managedArchives.size(), managedArchives[m].str());
		}
	}
	fflush(stderr);
}

void ArchiveFileSystem::loadMods()
{

#if RTS_ZEROHOUR
	// GeneralsX @bugfix Android port 13/09/2026 Mount the GeneralsOnline community
	// data patch, which is why this port could not join a single PC-hosted lobby.
	//
	// The lobby gate compares INI checksums, and the two never matched: this port
	// reports 4272612339, every PC lobby reports 2180732466. It was tempting to
	// read that as "the PC players are modded" -- GeneralsOnline's own source even
	// calls 4272612339 VANILLA_INI_CRC -- but it is the other way round. The PC
	// client loads an extra archive nobody installs by hand: the community patch
	// is a data pack it downloads into the user-data folder and mounts on every
	// launch unless explicitly disabled, so 2180732466 is simply what a normal
	// GeneralsOnline install computes, and matching it is not a workaround.
	//
	// Mounted sorted-by-name rather than as an overwrite, which is what the PC
	// client does and what the "500_900_" prefix is for: the patch takes priority
	// over the retail archives because digits sort ahead of letters, and later
	// data packs slot in by number rather than by mount order.
	//
	// The path is the user-data folder, unchanged from the PC client's -- which
	// on this port is already a plain, visible directory laid out exactly like
	// Windows' Documents leaf (see SDL3Main.cpp and BuildUserDataPathFromRegistry).
	// So this needs no new location and invents no convention: the same relative
	// path under the folder that already holds Options.ini, save games and Maps.
	//
	// Presence is the switch. Without the file this does nothing and the port
	// behaves as before; with it, the INI checksum should land on the PC's value
	// -- and land on it honestly, because the simulation is then reading the same
	// unit data, which is the whole point of the checksum.
	{
		AsciiString userData = TheGlobalData->getPath_UserData();
		if (userData.isNotEmpty() && !userData.endsWith("/") && !userData.endsWith("\\"))
			userData.concat('/');

		AsciiString patchPath;
		AsciiString disableMarkerPath;
		AsciiString withModsMarkerPath;

		if (userData.isNotEmpty())
		{
			patchPath = userData;
			patchPath.concat("GeneralsOnlineGameData/500_900_CommunityPatch_CoreINI.big");

			disableMarkerPath = userData;
			disableMarkerPath.concat(kCommunityPatchDisableMarker);

			withModsMarkerPath = userData;
			withModsMarkerPath.concat(kCommunityPatchWithModsMarker);
		}

		// The PC client has a settings switch for this (DataPacks_UseCommunityPatch)
		// and a command-line override; a marker file is this port's equivalent, and
		// it sits beside the patch so turning the data off never means deleting it.
		const Bool disabled = disableMarkerPath.isNotEmpty()
			&& TheLocalFileSystem->doesFileExist(disableMarkerPath.str());

		// GeneralsX @bugfix Android port 27/09/2026 Not with a mod. The patch is the whole Zero
		// Hour INI set re-laid out as directories (Data\INI\CommandButton\*.ini, Object\...,
		// with CommandButton.ini itself emptied). A mod such as Contra 007 (!Contra007.big)
		// replaces the single-file originals and wins on the files both have, but not on the
		// patch's directory files, so both sets of definitions got loaded and the mod stopped
		// starting -- on 1.2.2, before the patch existed, it ran. The patch only exists to match
		// a PC lobby's INI checksum, which a modded install cannot match anyway. Mods name their
		// archives with a leading '!' so that they sort ahead of the retail ones (which all start
		// with a letter); -mod is the other way a mod is loaded.
		//
		// Only an archive that carries game data (Data\INI\...) counts: UI and texture add-ons
		// (HD control bars, texture packs) use the same '!' prefix, touch no INI, and must not
		// keep a player out of PC lobbies. The launcher applies the same rule to say which mod
		// it found (DataPackInstaller.findDataMod), and can set the player's override below.
		// GeneralsX @bugfix Flux Build 05/11/2026 Opened before the mod check, which compares a '!'
		// archive's CommandButtons with the patch's (gxButtonsMatchPatch); mounted below if no mod
		// keeps it out, closed otherwise.
		const Bool patchPresent = !disabled && patchPath.isNotEmpty() && TheLocalFileSystem->doesFileExist(patchPath.str());
		ArchiveFile *patchArchive = patchPresent ? openArchiveFile(patchPath.str()) : nullptr;
		GXPatchButtons patchButtons;
		patchButtons.archive = patchArchive;

		AsciiString modArchive;
		for (ArchiveFileMap::const_iterator it = m_archiveFileMap.begin(); it != m_archiveFileMap.end(); ++it)
		{
			// Mods-screen archives are judged below, by the mod-source rule INI.cpp applies to them.
			if (isManagedModArchive(it->second))
				continue;
			const AsciiString base = getBaseFilename(it->first);
			if (gxIsDataModArchive(base, it->second, patchButtons))
			{
				modArchive = base;
				break;
			}
		}
		// GeneralsX @feature Android port 18/10/2026 The same rule for the mods turned on in the
		// launcher's Mods screen: one of their archives with game data keeps the patch out.
		for (size_t m = 0; m < m_managedModArchives.size() && modArchive.isEmpty(); ++m)
		{
			ArchiveFileMap::const_iterator managed = m_archiveFileMap.find(m_managedModArchives[m]);
			if (managed == m_archiveFileMap.end() || managed->second == nullptr)
				continue;
			// GeneralsX @bugfix Flux Build 05/11/2026 The '!' rule (gxArchiveIsDataMod): an indicator
			// pack moved into the Mods screen keeps the patch exactly when it would in the game folder.
			const AsciiString base = getBaseFilename(m_managedModArchives[m]);
			if (gxArchiveIsDataMod(base, managed->second, TRUE, patchButtons))
				modArchive = base;
		}
		// GeneralsX @bugfix Android port 19/10/2026 A mod unpacked straight into the game folder
		// (issue #55: the reporter tried CWC's files loose too) is as much a mod as its archive.
		if (modArchive.isEmpty())
		{
			FilenameList looseFiles;
			TheLocalFileSystem->getFileListInDirectory(AsciiString::TheEmptyString, AsciiString("Data\\INI\\"),
				AsciiString("*.ini"), looseFiles, TRUE);
			for (FilenameList::const_iterator f = looseFiles.begin(); f != looseFiles.end(); ++f)
			{
				AsciiString lower = *f;
				lower.toLower();
				if (gxIsGameplayIni(lower))
				{
					modArchive = "loose files in Data\\INI";
					break;
				}
			}
		}
		if (modArchive.isEmpty() && TheGlobalData->m_modBIG.isNotEmpty())
			modArchive = TheGlobalData->m_modBIG;
		if (modArchive.isEmpty() && TheGlobalData->m_modDir.isNotEmpty())
			modArchive = TheGlobalData->m_modDir;

		// The player's override from the launcher ("use the patch with mods too"), for a mod
		// built on top of the GeneralsOnline patch: written beside the disable marker.
		const Bool withMods = withModsMarkerPath.isNotEmpty()
			&& TheLocalFileSystem->doesFileExist(withModsMarkerPath.str());
		if (modArchive.isNotEmpty() && withMods && !disabled)
		{
			fprintf(stderr, "[gxbig] mod %s installed, community patch mounted anyway (launcher "
				"switch)\n", modArchive.str());
			modArchive.clear();
		}

		if (disabled)
		{
			fprintf(stderr, "[gxbig] community patch disabled by %s; INI stays retail\n",
				disableMarkerPath.str());
		}
		else if (modArchive.isNotEmpty())
		{
			fprintf(stderr, "[gxbig] community patch not mounted: a mod is installed (%s), and the "
				"patch would mix its own INI files into the mod's\n", modArchive.str());
		}
		else if (patchPresent)
		{
			ArchiveFile* archiveFile = patchArchive;
			patchArchive = nullptr;
			if (archiveFile != nullptr)
			{
				loadIntoDirectoryTree(archiveFile, FALSE, TRUE);
				m_archiveFileMap[patchPath] = archiveFile;
				fprintf(stderr, "[gxbig] community patch mounted: %s\n", patchPath.str());
			}
			else
			{
				fprintf(stderr, "[gxbig] community patch found but could not be opened: %s\n", patchPath.str());
			}
		}
		else
		{
			fprintf(stderr, "[gxbig] no community patch archive at %s; INI stays retail\n",
				patchPath.isEmpty() ? "<no user data dir>" : patchPath.str());
		}
		// Opened for the comparison and not mounted (a mod keeps it out).
		delete patchArchive;
		fflush(stderr);
	}
#endif

	if (TheGlobalData->m_modBIG.isNotEmpty())
	{
		ArchiveFile *archiveFile = openArchiveFile(TheGlobalData->m_modBIG.str());

		if (archiveFile != nullptr) {
			DEBUG_LOG(("ArchiveFileSystem::loadMods - loading %s into the directory tree.", TheGlobalData->m_modBIG.str()));
			loadIntoDirectoryTree(archiveFile, TRUE);
			m_archiveFileMap[TheGlobalData->m_modBIG] = archiveFile;
			DEBUG_LOG(("ArchiveFileSystem::loadMods - %s inserted into the archive file map.", TheGlobalData->m_modBIG.str()));
		}
		else
		{
			DEBUG_LOG(("ArchiveFileSystem::loadMods - could not openArchiveFile(%s)", TheGlobalData->m_modBIG.str()));
		}
	}

	if (TheGlobalData->m_modDir.isNotEmpty())
	{
		MAYBE_UNUSED Bool ret = loadBigFilesFromDirectory(TheGlobalData->m_modDir, "*.big", TRUE);
		(void)ret;
		DEBUG_ASSERTLOG(ret, ("loadBigFilesFromDirectory(%s) returned FALSE!", TheGlobalData->m_modDir.str()));
	}
}

Bool ArchiveFileSystem::doesFileExist(const Char *filename, FileInstance instance) const
{
	ArchivedDirectoryInfoResult result = const_cast<ArchiveFileSystem*>(this)->getArchivedDirectoryInfo(filename);

	if (!result.valid())
		return false;

	stl::const_range<ArchivedFileLocationMap> range = stl::get_range(result.dirInfo->m_files, result.lastToken, instance);

	return range.valid();
}

ArchivedDirectoryInfo* ArchiveFileSystem::friend_getArchivedDirectoryInfo(const Char* directory)
{
	ArchivedDirectoryInfoResult result = getArchivedDirectoryInfo(directory);

	return result.dirInfo;
}

ArchiveFileSystem::ArchivedDirectoryInfoResult ArchiveFileSystem::getArchivedDirectoryInfo(const Char* directory)
{
	ArchivedDirectoryInfoResult result;
	ArchivedDirectoryInfo* dirInfo = &m_rootDirectory;

	AsciiString token;
	AsciiString tokenizer = directory;
	tokenizer.toLower();
	Bool infoInPath = tokenizer.nextToken(&token, "\\/");

	while (infoInPath && (!token.find('.') || tokenizer.find('.')))
	{
		ArchivedDirectoryInfoMap::iterator tempiter = dirInfo->m_directories.find(token);
		if (tempiter != dirInfo->m_directories.end())
		{
			dirInfo = &tempiter->second;
			infoInPath = tokenizer.nextToken(&token, "\\/");
		}
		else
		{
			// the directory doesn't exist
			result.dirInfo = nullptr;
			result.lastToken = AsciiString::TheEmptyString;
			return result;
		}
	}

	result.dirInfo = dirInfo;
	result.lastToken = token;
	return result;
}

File * ArchiveFileSystem::openFile(const Char *filename, Int access, FileInstance instance)
{
	ArchiveFile* archive = getArchiveFile(filename, instance);

	if (archive == nullptr)
		return nullptr;

	return archive->openFile(filename, access);
}

Bool ArchiveFileSystem::getFileInfo(const AsciiString& filename, FileInfo *fileInfo, FileInstance instance) const
{
	if (fileInfo == nullptr) {
		return FALSE;
	}

	if (filename.isEmpty()) {
		return FALSE;
	}

	ArchiveFile* archive = getArchiveFile(filename, instance);

	if (archive == nullptr)
		return FALSE;

	return archive->getFileInfo(filename, fileInfo);
}

ArchiveFile* ArchiveFileSystem::getArchiveFile(const AsciiString& filename, FileInstance instance) const
{
	ArchivedDirectoryInfoResult result = const_cast<ArchiveFileSystem*>(this)->getArchivedDirectoryInfo(filename.str());

	if (!result.valid())
		return nullptr;

	stl::const_range<ArchivedFileLocationMap> range = stl::get_range(result.dirInfo->m_files, result.lastToken, instance);

	if (!range.valid())
		return nullptr;
	
	return range.get()->second;
}

void ArchiveFileSystem::getFileListInDirectory(const AsciiString& currentDirectory, const AsciiString& originalDirectory, const AsciiString& searchName, FilenameList &filenameList, Bool searchSubdirectories) const
{
	ArchiveFileMap::const_iterator it = m_archiveFileMap.begin();
	while (it != m_archiveFileMap.end()) {
		it->second->getFileListInDirectory(currentDirectory, originalDirectory, searchName, filenameList, searchSubdirectories);
		it++;
	}
}
