// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the World Builder document (compat/WorldBuilderDoc.h).
// Each function below names the EA function in WorldBuilderDoc.cpp it follows. Differences from EA
// are marked "Flux:" and are limited to what MFC did (files, dialogs, views).

#include "StdAfx.h"

#include "WorldBuilderDoc.h"

#include "Common/DataChunk.h"
#include "Common/GlobalData.h"
#include "Common/MapObject.h"
#include "Common/MapReaderWriterInfo.h"
#include "Common/NameKeyGenerator.h"
#include "Common/PlayerTemplate.h"
#include "Common/ThingFactory.h"
#include "Common/ThingTemplate.h"
#include "Common/WellKnownKeys.h"
#include "Compression.h"
#include "GameClient/GameText.h"
#include "GameLogic/PolygonTrigger.h"
#include "GameLogic/SidesList.h"
#include "MapPreview.h"
#include "PointerTool.h"
#include "WHeightMapEdit.h"
#include "resource.h"
#include "wbview3d.h"
#include "CUndoable.h"
#include "GameClient/Line2D.h"

#include <algorithm>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <list>

static CWorldBuilderDoc *s_activeDoc = nullptr;

CWorldBuilderDoc *CWorldBuilderDoc::GetActiveDoc()
{
	return s_activeDoc;
}

// TerrainModal stand-in (compat/TerrainModal.h).
void FluxWB_NoteMissingTexture(const AsciiString &name)
{
	if (s_activeDoc != nullptr) {
		s_activeDoc->noteMissingTexture(name);
	}
}

CWorldBuilderDoc::CWorldBuilderDoc() :
	m_heightMap(nullptr),
	m_curWaypointID(0),
	m_waypointTableNeedsUpdate(true),
	m_numWaypointLinks(0),
	m_startX(0),
	m_startY(0),
	m_broken(false),
	m_undoList(nullptr),
	m_curRedo(0),
	m_modified(false)
{
	memset(m_waypointTable, 0, sizeof(m_waypointTable));
	memset(m_waypointLinks, 0, sizeof(m_waypointLinks));
	s_activeDoc = this;
}

CWorldBuilderDoc::~CWorldBuilderDoc()
{
	REF_PTR_RELEASE(m_undoList);
	REF_PTR_RELEASE(m_heightMap);
	if (s_activeDoc == this) {
		s_activeDoc = nullptr;
	}
}

void CWorldBuilderDoc::noteMissingTexture(const AsciiString &name)
{
	for (const AsciiString &known : m_missingTextures) {
		if (known == name) {
			return;
		}
	}
	m_missingTextures.push_back(name);
	fprintf(stderr, "[fluxwb] map texture '%s' is not in this game's Terrain.ini; its tiles are dropped\n", name.str());
}

// ---------------------------------------------------------------------------------------------
// Saving: CompressedCachedMFCFileOutputStream + CWorldBuilderDoc::Serialize (storing).
// ---------------------------------------------------------------------------------------------

namespace {

/** EA's CompressedCachedMFCFileOutputStream, collecting into memory instead of a CFile. */
class CompressedCachedOutputStream : public OutputStream
{
public:
	virtual Int write(const void *pData, Int numBytes) override
	{
		const UnsignedByte *p = static_cast<const UnsignedByte *>(pData);
		m_data.insert(m_data.end(), p, p + numBytes);
		return numBytes;
	}

	/** EA's flush(): compress with the map's own compression, else the preferred one. */
	std::vector<UnsignedByte> flush()
	{
		std::vector<UnsignedByte> out;
		if (m_data.empty()) {
			return out;
		}
		CompressionType compressionToUse = CompressionManager::getPreferredCompression();
		Dict *worldDict = MapObject::getWorldDict();
		if (worldDict) {
			Bool exists = FALSE;
			compressionToUse = (CompressionType)worldDict->getInt(TheKey_compression, &exists);
			if (!exists || compressionToUse > COMPRESSION_MAX || compressionToUse < COMPRESSION_MIN)
				compressionToUse = CompressionManager::getPreferredCompression();
		}
		const Int total = (Int)m_data.size();
		Int compressedLen = CompressionManager::getMaxCompressedSize(total, compressionToUse);
		out.resize(compressedLen > 0 ? compressedLen : 0);
		compressedLen = compressedLen > 0
			? CompressionManager::compressData(compressionToUse, m_data.data(), total, out.data(), compressedLen)
			: 0;
		if (compressedLen) {
			out.resize(compressedLen);
		} else {
			out = m_data;  // EA writes the data uncompressed when compressing fails.
		}
		return out;
	}

	/** The uncompressed chunk data (the round-trip tool compares this). */
	const std::vector<UnsignedByte> &raw() const { return m_data; }

private:
	std::vector<UnsignedByte> m_data;
};

bool writeWholeFile(const std::string &path, const std::vector<UnsignedByte> &bytes, std::string *error)
{
	const std::string tmp = path + ".tmp";
	FILE *f = fopen(tmp.c_str(), "wb");
	if (f == nullptr) {
		if (error) *error = "cannot write " + tmp + ": " + strerror(errno);
		return false;
	}
	const size_t written = bytes.empty() ? 0 : fwrite(bytes.data(), 1, bytes.size(), f);
	const bool flushed = fflush(f) == 0;
	const bool closed = fclose(f) == 0;
	if (written != bytes.size() || !flushed || !closed) {
		remove(tmp.c_str());
		if (error) *error = "short write to " + tmp;
		return false;
	}
	if (rename(tmp.c_str(), path.c_str()) != 0) {
		remove(tmp.c_str());
		if (error) *error = "cannot replace " + path + ": " + strerror(errno);
		return false;
	}
	return true;
}

} // namespace

bool CWorldBuilderDoc::save(const char *path, std::string *error)
{
	if (m_heightMap == nullptr) {
		if (error) *error = "no map is open";
		return false;
	}
	if (m_broken) {
		if (error) *error = "the last map could not be read completely; open a map or start a new one before saving";
		return false;
	}
	// Flux: EA saves whatever is left after a texture was dropped on loading. That would write the
	// map back without those tiles, so it is refused here.
	if (!m_missingTextures.empty()) {
		if (error) {
			*error = "this map uses terrain textures this game does not have (";
			*error += m_missingTextures.front().str();
			*error += "); saving would lose them";
		}
		return false;
	}

	m_waypointTableNeedsUpdate = true;
	// EA: MapPreview mPreview; mPreview.save(path) -- the .tga beside the map.
	{
		MapPreview mPreview;
		mPreview.save(CString(path));
	}

	CompressedCachedOutputStream theStream;
	// EA wraps the write in try/catch (Serialize); a throw here must not end the editor with the
	// user's edits in it.
	try {
		DataChunkOutput *chunkWriter = new DataChunkOutput(&theStream);
		m_heightMap->saveToFile(*chunkWriter);
		/***************WAYPOINTS DATA ***************/
		chunkWriter->openDataChunk("WaypointsList", K_WAYPOINTS_VERSION_1);
		chunkWriter->writeInt(this->m_numWaypointLinks);
		for (Int i = 0; i < m_numWaypointLinks; i++) {
			chunkWriter->writeInt(this->m_waypointLinks[i].waypoint1);
			chunkWriter->writeInt(this->m_waypointLinks[i].waypoint2);
		}
		chunkWriter->closeDataChunk();
		delete chunkWriter;  // writes the table of contents, then the chunks, into theStream
	} catch (...) {
		if (error) *error = "the map could not be written";
		return false;
	}
	const std::vector<UnsignedByte> bytes = theStream.flush();
	if (!writeWholeFile(path, bytes, error)) {
		return false;
	}
	m_modified = false;
	fprintf(stderr, "[fluxwb] saved %s: %d bytes of chunks, %d on disk\n", path,
		(int)theStream.raw().size(), (int)bytes.size());
	return true;
}

// ---------------------------------------------------------------------------------------------
// Loading: CWorldBuilderDoc::OnOpenDocument + Serialize (loading).
// ---------------------------------------------------------------------------------------------

Bool CWorldBuilderDoc::ParseWaypointDataChunk(DataChunkInput &file, DataChunkInfo *info, void *userData)
{
	CWorldBuilderDoc *pThis = (CWorldBuilderDoc *)userData;
	return pThis->ParseWaypointData(file, info, userData);
}

Bool CWorldBuilderDoc::ParseWaypointData(DataChunkInput &file, DataChunkInfo *info, void *userData)
{
	(void)info;
	(void)userData;
	m_numWaypointLinks = file.readInt();
	// Flux: EA trusts the count; a damaged file could overrun the fixed table.
	if (m_numWaypointLinks < 0 || m_numWaypointLinks > MAX_WAYPOINTS) {
		m_numWaypointLinks = 0;
		return false;
	}
	for (Int i = 0; i < m_numWaypointLinks; i++) {
		this->m_waypointLinks[i].waypoint1 = file.readInt();
		this->m_waypointLinks[i].waypoint2 = file.readInt();
	}
	DEBUG_ASSERTCRASH(file.atEndOfChunk(), ("Unexpected data left over."));
	return true;
}

bool CWorldBuilderDoc::open(const char *path, std::string *error)
{
	// OnOpenDocument: clear out map-specific text, then read <map folder>/map.str.
	TheGameText->reset();
	AsciiString s = path;
	const char *lastSep = s.reverseFind('/');
	const char *lastBack = s.reverseFind('\\');
	if (lastBack != nullptr && (lastSep == nullptr || lastBack > lastSep)) {
		lastSep = lastBack;
	}
	if (lastSep != nullptr) {
		s.truncateTo(lastSep - s.str() + 1);
	} else {
		s.clear();
	}
	s.concat("map.str");
	TheGameText->initMapStringFile(s);

	// Serialize (loading).
	m_waypointTableNeedsUpdate = true;
	WorldHeightMapEdit *pOldHeightMap = m_heightMap;
	std::vector<AsciiString> oldMissing;
	oldMissing.swap(m_missingTextures);
	CachedFileInputStream theInputStream;
	if (!theInputStream.open(AsciiString(path))) {
		m_missingTextures.swap(oldMissing);
		if (error) *error = std::string("cannot open ") + path;
		return false;
	}
	try {
		PolygonTrigger::deleteTriggers();
		ChunkInputStream *pStrm = &theInputStream;

		// Read the logical data (map objects, waypoints, etc.)
		WorldHeightMap *terrainHeightMap = new WorldHeightMap(pStrm, true);
		REF_PTR_RELEASE(terrainHeightMap);
		pStrm->absoluteSeek(0);
		// Read & keep the graphical data.
		m_heightMap = NEW_REF(WorldHeightMapEdit, (pStrm));
		pStrm->absoluteSeek(0);
		m_numWaypointLinks = 0;
		try {
			DataChunkInput file(pStrm);
			if (file.isValidFileType()) {	// Backwards compatible files aren't valid data chunk files.
				// Read the waypoints.
				file.registerParser("WaypointsList", AsciiString::TheEmptyString, ParseWaypointDataChunk);
				if (!file.parse(this)) {
					throw(ERROR_CORRUPT_FILE_FORMAT);
				}
			}
		} catch (...) {
			// just eat the error - legacy files aren't chunk format.
		}
		theInputStream.close();

		validate();
		compressWaypointIds(); // remove any unused waypoint ids.
		m_heightMap->optimizeTiles(); // force to optimize tileset
		afterLoad();
		REF_PTR_RELEASE(pOldHeightMap);
		m_broken = false;
		clearUndo(); // EA: REF_PTR_RELEASE(m_undoList); m_curRedo = 0;
	} catch (...) {
		if (m_heightMap != pOldHeightMap) {
			REF_PTR_RELEASE(m_heightMap);
		}
		m_heightMap = pOldHeightMap;
		// Flux: the objects, sides and triggers are the new map's by now (or half of them), next to
		// the old terrain: keep the old map's texture refusal and refuse any save of this mixture.
		for (const AsciiString &name : oldMissing) {
			noteMissingTexture(name);
		}
		m_broken = true;
		// The old map's undo steps point at objects and triggers the load has already deleted.
		clearUndo();
		m_modified = true;
		if (error) *error = std::string("not a map EA's loader can read: ") + path;
		return false;
	}
	return true;
}

/** The camera start and the object fix-ups of Serialize after the terrain is read. */
void CWorldBuilderDoc::afterLoad()
{
	Real centerX = MAP_XY_FACTOR * m_heightMap->getXExtent() / 2;
	Real centerY = MAP_XY_FACTOR * m_heightMap->getYExtent() / 2;
	// GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 Flux: EA subtracts the border in cells
	// from a world position here (WorldBuilderDoc.cpp), which puts a map without
	// InitialCameraPosition -- every skirmish map -- off the middle; the border in world units.
	centerX -= m_heightMap->getBorderSize() * MAP_XY_FACTOR;
	centerY -= m_heightMap->getBorderSize() * MAP_XY_FACTOR;
	AsciiString startingCamName = TheNameKeyGenerator->keyToName(TheKey_InitialCameraPosition);

	// always assign unique IDs (EA).
	MapObject::fastAssignAllUniqueIDs();

	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		// Flux: EA adds each object to the Layers list and copies the colour of the Object panel's
		// template; neither is written to the map.
		if (pMapObj->isWaypoint()) {
			if (pMapObj->getWaypointID() >= m_curWaypointID) {
				m_curWaypointID = pMapObj->getWaypointID();
			}
			if (startingCamName == pMapObj->getWaypointName()) {
				centerX = pMapObj->getLocation()->x;
				centerY = pMapObj->getLocation()->y;
			}
		}
	}
	m_startX = centerX;
	m_startY = centerY;
}

bool CWorldBuilderDoc::newMap(Int width, Int height, Int initialHeight, Int border, std::string *error)
{
	// OnNewDocument, without the size dialog (the caller passes the sizes).
	if (width < 1 || height < 1 || border < 0 || initialHeight < 0 || initialHeight > 255) {
		if (error) *error = "bad map size";
		return false;
	}
	TheGameText->reset();
	REF_PTR_RELEASE(m_heightMap);
	m_missingTextures.clear();
	m_broken = false;
	clearUndo();
	m_numWaypointLinks = 0;
	m_waypointTableNeedsUpdate = true;
	m_curWaypointID = 0;

	PolygonTrigger::deleteTriggers();
	TheSidesList->clear();
	TheSidesList->validateSides();

	m_heightMap = NEW_REF(WorldHeightMapEdit, (width, height, initialHeight, border));

	// Create a default water area.
	PolygonTrigger *pTrig = newInstance(PolygonTrigger)(4);
	ICoord3D loc;
	pTrig->setWaterArea(true);
	pTrig->setTriggerName("Default Water");
	loc.x = -border * MAP_XY_FACTOR;
	loc.y = -border * MAP_XY_FACTOR;
	loc.z = TheGlobalData->m_waterPositionZ;
	pTrig->addPoint(loc);
	loc.x = (width + border) * MAP_XY_FACTOR;
	pTrig->addPoint(loc);
	loc.y = (height + border) * MAP_XY_FACTOR;
	pTrig->addPoint(loc);
	loc.x = -border * MAP_XY_FACTOR;
	pTrig->addPoint(loc);
	PolygonTrigger::addPolygonTrigger(pTrig);

	// GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 Flux: EA centres the view on
	// xExtent/2 - border cells (OnNewDocument), the middle of the playable area. This used the
	// playable width in place of the extent, so a new map opened 300 units from the corner, next to
	// the border, and maps were built there.
	m_startX = (m_heightMap->getXExtent() / 2 - border) * MAP_XY_FACTOR;
	m_startY = (m_heightMap->getYExtent() / 2 - border) * MAP_XY_FACTOR;

	// Flux: two start positions, so a new map is playable as it is (see checkStartPositions). Made as
	// EA's waypoint tool makes them; part of the new document, not an undo step.
	for (Int player = 1; player <= 2; ++player) {
		Coord3D pt;
		defaultStartLocation(player, &pt);
		MapObject *pNew = newStartWaypoint(player, pt);
		pNew->setNextMap(MapObject::TheMapObjectListPtr);
		MapObject::TheMapObjectListPtr = pNew;
		pNew->validate(); // a valid ID and team, as AddObjectUndoable::Do gives an added object
	}
	m_waypointTableNeedsUpdate = true;
	return true;
}

// ---------------------------------------------------------------------------------------------
// Start positions (Flux; see WorldBuilderDoc.h).
// ---------------------------------------------------------------------------------------------

/** "Player_N_Start" with N in 1..MAX_START, exactly the game's spelling (GameLogic.cpp) -> N, else 0. */
static Int StartWaypointPlayer(const AsciiString &name)
{
	Int n = 0;
	if (sscanf(name.str(), "Player_%d_Start", &n) != 1 || n < 1 || n > CWorldBuilderDoc::MAX_START) {
		return 0;
	}
	AsciiString exact;
	exact.format("Player_%d_Start", n);
	return exact == name ? n : 0;
}

void CWorldBuilderDoc::getPlayableSize(Real *w, Real *h) const
{
	*w = *h = 0;
	if (m_heightMap) {
		*w = (m_heightMap->getXExtent() - 2 * m_heightMap->getBorderSize()) * MAP_XY_FACTOR;
		*h = (m_heightMap->getYExtent() - 2 * m_heightMap->getBorderSize()) * MAP_XY_FACTOR;
	}
}

void CWorldBuilderDoc::defaultStartLocation(Int player, Coord3D *pt) const
{
	// Opposite corners first (a 1v1 map), then the other corners and the middles of the edges.
	static const Real kSpots[MAX_START][2] = {
		{ 0.2f, 0.2f }, { 0.8f, 0.8f }, { 0.8f, 0.2f }, { 0.2f, 0.8f },
		{ 0.5f, 0.15f }, { 0.5f, 0.85f }, { 0.15f, 0.5f }, { 0.85f, 0.5f },
	};
	Real w, h;
	getPlayableSize(&w, &h);
	const Int i = (player >= 1 && player <= MAX_START) ? player - 1 : 0;
	pt->x = w * kSpots[i][0];
	pt->y = h * kSpots[i][1];
	pt->z = 0;
	if (m_heightMap) {
		// On the terrain, as EA's tools place a point (cell = world / MAP_XY_FACTOR + border).
		const Int cx = (Int)(pt->x / MAP_XY_FACTOR) + m_heightMap->getBorderSize();
		const Int cy = (Int)(pt->y / MAP_XY_FACTOR) + m_heightMap->getBorderSize();
		pt->z = m_heightMap->getHeight(cx, cy) * MAP_HEIGHT_SCALE;
	}
}

MapObject *CWorldBuilderDoc::newStartWaypoint(Int player, const Coord3D &pt)
{
	// WaypointTool::mouseUp, with the game's start name in place of "Waypoint N".
	Coord3D loc = pt;
	MapObject *pNew = newInstance(MapObject)(loc, "*Waypoints/Waypoint", 0, 0, nullptr, nullptr);
	Int id = getNextWaypointID();
	AsciiString name;
	name.format("Player_%d_Start", player);
	pNew->setIsWaypoint();
	pNew->setWaypointID(id);
	pNew->setWaypointName(name);
	pNew->getProperties()->setAsciiString(TheKey_originalOwner, "team");
	return pNew;
}

std::string CWorldBuilderDoc::checkStartPositions()
{
	if (!m_heightMap) return "";
	Real w, h;
	getPlayableSize(&w, &h);
	bool have[MAX_START + 1] = {};
	std::string outside;
	Int highest = 0;
	for (MapObject *pObj = MapObject::getFirstMapObject(); pObj; pObj = pObj->getNext()) {
		if (!pObj->isWaypoint()) continue;
		const Int n = StartWaypointPlayer(pObj->getWaypointName());
		if (n == 0) continue;
		const Coord3D *p = pObj->getLocation();
		if (!have[n] && (p->x < 0 || p->y < 0 || p->x > w || p->y > h)) {
			outside += (outside.empty() ? "" : ",") + std::to_string(n);
		}
		have[n] = true;
		highest = std::max(highest, n);
	}
	std::string missing;
	for (Int n = 1; n <= std::max<Int>(highest, 1); ++n) {
		if (!have[n]) missing += (missing.empty() ? "" : ",") + std::to_string(n);
	}
	std::string out;
	if (!missing.empty()) out = "missing=" + missing;
	if (!outside.empty()) out += (out.empty() ? "" : ";") + std::string("outside=") + outside;
	return out;
}

bool CWorldBuilderDoc::fixStartPositions()
{
	if (!m_heightMap || m_broken) return false;
	Real w, h;
	getPlayableSize(&w, &h);
	MapObject *found[MAX_START + 1] = {};
	Int highest = 0;
	for (MapObject *pObj = MapObject::getFirstMapObject(); pObj; pObj = pObj->getNext()) {
		if (!pObj->isWaypoint()) continue;
		const Int n = StartWaypointPlayer(pObj->getWaypointName());
		if (n == 0 || found[n]) continue;
		found[n] = pObj;
		highest = std::max(highest, n);
	}
	const Int count = std::max<Int>(highest, 2);
	MultipleUndoable *pAll = new MultipleUndoable;
	bool any = false;
	for (Int n = 1; n <= count; ++n) {
		Undoable *pUndo = nullptr;
		if (found[n] == nullptr) {
			Coord3D pt;
			defaultStartLocation(n, &pt);
			pUndo = new AddObjectUndoable(this, newStartWaypoint(n, pt));
		} else {
			const Coord3D *p = found[n]->getLocation();
			if (p->x >= 0 && p->y >= 0 && p->x <= w && p->y <= h) continue;
			// Into the playable area, a little inside its edge. EA's move undoable works on the
			// selection, as a drag of the pointer tool does; SetOffset moves at once, Do again.
			const Real margin = 5 * MAP_XY_FACTOR;
			const Real nx = std::min(std::max(p->x, margin), w - margin);
			const Real ny = std::min(std::max(p->y, margin), h - margin);
			PointerTool::clearSelection();
			found[n]->setSelected(true);
			ModifyObjectUndoable *pMove = new ModifyObjectUndoable(this);
			found[n]->setSelected(false);
			pMove->SetOffset(nx - p->x, ny - p->y);
			pUndo = pMove;
		}
		pAll->addUndoable(pUndo);
		REF_PTR_RELEASE(pUndo); // belongs to pAll now (EA, mapobjectprops.cpp).
		any = true;
	}
	if (any) {
		AddAndDoUndoable(pAll);
		m_waypointTableNeedsUpdate = true;
	}
	REF_PTR_RELEASE(pAll); // belongs to the document's undo list now.
	return true;
}

// ---------------------------------------------------------------------------------------------
// CWorldBuilderDoc::validate and its name helpers (EA, without ReplaceUnitDialog).
// ---------------------------------------------------------------------------------------------

static AsciiString ConvertToNonGCName(AsciiString name, Bool checkTemplate = true)
{
	const char *replacePrefix = "GC_";
	const size_t offset = name.startsWith(replacePrefix) ? strlen(replacePrefix) : 0u;
	char newName[256];
	strlcpy(newName, name.str() + offset, ARRAY_SIZE(newName));
	AsciiString swapName;
	swapName.set(newName);
	if (checkTemplate) {
		const ThingTemplate *tt = TheThingFactory->findTemplate(swapName);
		if (tt) {
			return swapName;
		}
		return AsciiString::TheEmptyString;
	}
	return swapName;
}

static AsciiString ConvertName(AsciiString name)
{
	const char *replacePrefix = "Fundamentalist";
	const size_t offset = name.startsWith(replacePrefix) ? strlen(replacePrefix) : 0u;
	char newName[256];
	strcpy(newName, "GLA");
	strlcat(newName, name.str() + offset, ARRAY_SIZE(newName));
	AsciiString swapName;
	swapName.set(newName);
	const ThingTemplate *tt = TheThingFactory->findTemplate(swapName);
	if (tt) {
		return swapName;
	}
	return AsciiString::TheEmptyString;
}

static AsciiString ConvertFaction(AsciiString name)
{
	const char *replacePrefix = "FactionFundamentalist";
	const size_t offset = name.startsWith(replacePrefix) ? strlen(replacePrefix) : 0u;
	char newName[256];
	strcpy(newName, "FactionGLA");
	strlcat(newName, name.str() + offset, ARRAY_SIZE(newName));
	AsciiString swapName;
	swapName.set(newName);
	const PlayerTemplate *pt = ThePlayerTemplateStore->findPlayerTemplate(NAMEKEY(swapName));
	if (pt) {
		return swapName;
	}
	return AsciiString::TheEmptyString;
}

void CWorldBuilderDoc::validate()
{
	Dict swapDict;
	AsciiString swapName;
	Bool needToFixTeams = false;

	// verify/fix the build lists
	for (int side = 0; side < TheSidesList->getNumSides(); side++) {
		SidesInfo *pSide = TheSidesList->getSideInfo(side);
		AsciiString tmplname = pSide->getDict()->getAsciiString(TheKey_playerFaction);
		if (tmplname.isEmpty()) {
			continue; // Neutral player has empty template.
		}
		const PlayerTemplate *pt = ThePlayerTemplateStore->findPlayerTemplate(NAMEKEY(tmplname));
		if (!pt && tmplname.startsWith("FactionFundamentalist")) {
			swapName = ConvertFaction(tmplname);
			if (swapName != AsciiString::TheEmptyString) {
				pSide->getDict()->setAsciiString(TheKey_playerFaction, swapName);
			}
		}
		for (BuildListInfo *pBuild = pSide->getBuildList(); pBuild; pBuild = pBuild->getNext()) {
			AsciiString name = pBuild->getTemplateName();
			if (name.startsWith("Fundamentalist")) {
				swapName = ConvertName(name);
				if (swapName != AsciiString::TheEmptyString) {
					pBuild->setTemplateName(swapName);
				}
			}
		}
	}

#define FIX_TEAM(key)                                                   \
	type = teamDict->getAsciiString(key, &exists);                    \
	if (exists && type.startsWith("Fundamentalist")) {                \
		swapName = ConvertName(type);                                   \
		if (swapName != AsciiString::TheEmptyString) {                  \
			teamDict->setAsciiString(key, swapName);                      \
		}                                                               \
	}

	// verify/fix the team definitions
	Int numTeams = TheSidesList->getNumTeams();
	for (Int team = 0; team < numTeams; team++) {
		TeamsInfo *ti = TheSidesList->getTeamInfo(team);
		Dict *teamDict = ti->getDict();
		AsciiString type;
		Bool exists;
		FIX_TEAM(TheKey_teamUnitType1)
		FIX_TEAM(TheKey_teamUnitType2)
		FIX_TEAM(TheKey_teamUnitType3)
		FIX_TEAM(TheKey_teamUnitType4)
		FIX_TEAM(TheKey_teamUnitType5)
		FIX_TEAM(TheKey_teamUnitType6)
		FIX_TEAM(TheKey_teamUnitType7)
	}
#undef FIX_TEAM

	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		if (pMapObj->isScorch() || pMapObj->isWaypoint() || pMapObj->isLight() ||
				pMapObj->getFlag(FLAG_ROAD_FLAGS) || pMapObj->getFlag(FLAG_BRIDGE_FLAGS)) {
			continue;
		}
		AsciiString name = pMapObj->getName();
		if (pMapObj->getThingTemplate() == nullptr) {
			Bool exists = false;
			swapName = swapDict.getAsciiString(NAMEKEY(name), &exists);
			if (name.startsWith("Fundamentalist")) {
				swapName = ConvertName(name);
				if (swapName != AsciiString::TheEmptyString) {
					swapDict.setAsciiString(NAMEKEY(name), swapName);
				}
			}
			if (name.startsWith("GC_")) {
				swapName = ConvertToNonGCName(name);
				if (swapName != AsciiString::TheEmptyString) {
					swapDict.setAsciiString(NAMEKEY(name), swapName);
				}
			}
			// Flux: EA asks for a replacement here (ReplaceUnitDialog). Without it the object keeps its
			// name and is written back unchanged.
			swapName = swapDict.getAsciiString(NAMEKEY(name), &exists);
			if (exists) {
				const ThingTemplate *tt = TheThingFactory->findTemplate(swapName);
				if (tt) {
					pMapObj->setName(swapName);
					pMapObj->setThingTemplate(tt);
				}
			} else {
				fprintf(stderr, "[fluxwb] object '%s' is not in this game's INI; kept as it is\n", name.str());
			}
		}

		Bool exists;
		AsciiString teamName = pMapObj->getProperties()->getAsciiString(TheKey_originalOwner, &exists);
		if (exists) {
			TeamsInfo *teamInfo = TheSidesList->findTeamInfo(teamName);
			if (teamInfo) {
				AsciiString teamOwner = teamInfo->getDict()->getAsciiString(TheKey_teamOwner);
				SidesInfo *pSide = TheSidesList->findSideInfo(teamOwner);
				if (pSide) {
					AsciiString tmplname = pSide->getDict()->getAsciiString(TheKey_playerFaction);
					if (tmplname.isEmpty()) {
						continue; // Neutral player has empty template.
					}
					const PlayerTemplate *pt = ThePlayerTemplateStore->findPlayerTemplate(NAMEKEY(tmplname));
					if (!pt && tmplname.startsWith("FactionFundamentalist")) {
						swapName = ConvertFaction(tmplname);
						if (swapName != AsciiString::TheEmptyString) {
							pSide->getDict()->setAsciiString(TheKey_playerFaction, swapName);
						}
					}
				} else {
					needToFixTeams = true;
				}
			} else {
				needToFixTeams = true;
			}
		} else {
			needToFixTeams = true;
		}
	}
	if (needToFixTeams) {
		AfxMessageBox(IDS_NEED_TO_FIX_TEAMS, MB_OK | MB_ICONERROR);
	}
}

// ---------------------------------------------------------------------------------------------
// Waypoints (EA, unchanged).
// ---------------------------------------------------------------------------------------------

void CWorldBuilderDoc::compressWaypointIds()
{
	updateWaypointTable();
	m_curWaypointID = 0;
	for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
		if (pMapObj->isWaypoint()) {
			Int nwpid = getNextWaypointID();
			pMapObj->setWaypointID(nwpid);
		}
	}
	for (Int i = 0; i < m_numWaypointLinks; i++) {
		MapObject *pWay1 = getWaypointByID(m_waypointLinks[i].waypoint1);
		MapObject *pWay2 = getWaypointByID(m_waypointLinks[i].waypoint2);
		if (pWay1 && pWay2) {
			m_waypointLinks[i].waypoint1 = pWay1->getWaypointID();
			m_waypointLinks[i].waypoint2 = pWay2->getWaypointID();
		} else {
			for (Int j = i; j < m_numWaypointLinks - 1; j++) {
				m_waypointLinks[j] = m_waypointLinks[j + 1];
			}
			m_numWaypointLinks--;
			i--;
		}
	}
	m_waypointTableNeedsUpdate = true;
	updateWaypointTable();
}

void CWorldBuilderDoc::updateWaypointTable()
{
	if (m_waypointTableNeedsUpdate) {
		m_waypointTableNeedsUpdate = false;
		for (Int i = 0; i < MAX_WAYPOINTS; i++) {
			m_waypointTable[i] = nullptr;
		}
		for (MapObject *pMapObj = MapObject::getFirstMapObject(); pMapObj; pMapObj = pMapObj->getNext()) {
			if (pMapObj->isWaypoint()) {
				Int id = pMapObj->getWaypointID();
				if (id > 0 && id < MAX_WAYPOINTS) {
					if (m_waypointTable[id] != nullptr) {
						pMapObj->setWaypointID(getNextWaypointID());
						m_waypointTableNeedsUpdate = true;
					} else {
						m_waypointTable[id] = pMapObj;
					}
				}
			}
		}
	}
}

void CWorldBuilderDoc::addWaypointLink(Int waypointID1, Int waypointID2)
{
	for (Int i = 0; i < m_numWaypointLinks; i++) {
		if (m_waypointLinks[i].waypoint1 == waypointID1 && m_waypointLinks[i].waypoint2 == waypointID2) {
			return; // already linked.
		}
	}
	if (m_numWaypointLinks < MAX_WAYPOINTS) {
		m_waypointLinks[m_numWaypointLinks].waypoint1 = waypointID1;
		m_waypointLinks[m_numWaypointLinks].waypoint2 = waypointID2;
		m_numWaypointLinks++;
	}
}

void CWorldBuilderDoc::removeWaypointLink(Int waypointID1, Int waypointID2)
{
	for (Int i = 0; i < m_numWaypointLinks; i++) {
		if (m_waypointLinks[i].waypoint1 == waypointID1 && m_waypointLinks[i].waypoint2 == waypointID2) {
			for (; i < m_numWaypointLinks - 1; i++) {
				m_waypointLinks[i] = m_waypointLinks[i + 1];
			}
			m_numWaypointLinks--;
		}
	}
}

MapObject *CWorldBuilderDoc::getWaypointByID(Int waypointID)
{
	updateWaypointTable();
	if (waypointID > 0 && waypointID < MAX_WAYPOINTS) {
		MapObject *pObj = m_waypointTable[waypointID];
		if (pObj && pObj->isWaypoint()) {
			return pObj;
		}
	}
	return nullptr;
}

void CWorldBuilderDoc::getWaypointLink(Int ndx, Int *waypointID1, Int *waypointID2)
{
	*waypointID1 = 0;
	*waypointID2 = 0;
	// Flux: EA tests ndx <= m_numWaypointLinks, one past the last link.
	if (ndx >= 0 && ndx < m_numWaypointLinks) {
		*waypointID1 = m_waypointLinks[ndx].waypoint1;
		*waypointID2 = m_waypointLinks[ndx].waypoint2;
	}
}

Bool CWorldBuilderDoc::waypointLinkExists(Int waypointID1, Int waypointID2)
{
	for (Int i = 0; i < m_numWaypointLinks; i++) {
		if (m_waypointLinks[i].waypoint1 == waypointID1 && m_waypointLinks[i].waypoint2 == waypointID2) {
			return true;
		}
	}
	return false;
}

// ---------------------------------------------------------------------------------------------
// For DrawObject.cpp (EA's bodies).
// ---------------------------------------------------------------------------------------------

WbView3d *CWorldBuilderDoc::Get3DView()
{
	return WbView3d::GetActiveView();
}

Int CWorldBuilderDoc::getNumBoundaries() const
{
	return m_heightMap ? m_heightMap->getNumBoundaries() : 0;
}

void CWorldBuilderDoc::getBoundary(Int ndx, ICoord2D *border) const
{
	if (m_heightMap) {
		m_heightMap->getBoundary(ndx, border);
	}
}

Bool CWorldBuilderDoc::getCellIndexFromCoord(Coord3D cpt, CPoint *ndxP)
{
	// Set up default return value.
	ndxP->x = -1;
	ndxP->y = -1;
	Bool inMap = true;

	WorldHeightMapEdit *pMap = GetHeightMap();
	if (pMap == nullptr) return false;

	Int xIndex = floor(cpt.x / MAP_XY_FACTOR);
	xIndex += pMap->getBorderSize();
	if (xIndex < 0) {
		inMap = false;
		xIndex = 0;
	}
	if (xIndex >= pMap->getXExtent()) {
		inMap = false;
		xIndex = pMap->getXExtent() - 1;
	}
	Int yIndex = floor(cpt.y / MAP_XY_FACTOR);
	yIndex += pMap->getBorderSize();
	if (yIndex < 0) {
		inMap = false;
		yIndex = 0;
	}
	if (yIndex >= pMap->getYExtent()) {
		inMap = false;
		yIndex = pMap->getYExtent() - 1;
	}
	ndxP->x = xIndex;
	ndxP->y = yIndex;
	return inMap;
}

void CWorldBuilderDoc::getCoordFromCellIndex(CPoint ndx, Coord3D *pt)
{
	if (!pt) {
		return;
	}
	WorldHeightMap *hm = GetHeightMap();
	if (!hm) {
		return;
	}
	(*pt).x = (ndx.x - hm->getBorderSize()) * MAP_XY_FACTOR;
	(*pt).y = (ndx.y - hm->getBorderSize()) * MAP_XY_FACTOR;
}

namespace {

enum DIRECTION { PREFER_CENTER, PREFER_LEFT, PREFER_TOP, PREFER_RIGHT, PREFER_BOTTOM };

// Find the index nearest the point in the preferred direction (EA).
void FindIndexNearest(CWorldBuilderDoc *pDoc, const Coord3D *point, CPoint *outNdx, DIRECTION pref)
{
	Coord3D testPoint = *point;
	switch (pref) {
		case PREFER_CENTER: break;
		case PREFER_LEFT: testPoint.x -= MAP_XY_FACTOR / 2; break;
		case PREFER_TOP: testPoint.y += MAP_XY_FACTOR / 2; break;
		case PREFER_RIGHT: testPoint.x += MAP_XY_FACTOR / 2; break;
		case PREFER_BOTTOM: testPoint.y -= MAP_XY_FACTOR / 2; break;
	}
	pDoc->getCellIndexFromCoord(testPoint, outNdx);
}

Bool IndexInRect(CWorldBuilderDoc *pDoc, const Coord3D *bl, const Coord3D *tl, const Coord3D *br, const Coord3D *tr, CPoint *index)
{
	Coord3D testPoint;
	pDoc->getCoordFromCellIndex(*index, &testPoint);
	return PointInsideRect3D(bl, tl, br, tr, &testPoint);
}

Bool AddUniqueAndNeighbors(CWorldBuilderDoc *pDoc, const Coord3D *bl, const Coord3D *tl, const Coord3D *br, const Coord3D *tr, CPoint ndx, std::vector<CPoint> *allIndices)
{
	if (!allIndices) {
		return false;
	}
	if (!IndexInRect(pDoc, bl, tl, br, tr, &ndx)) {
		return false;
	}
	if (std::find(allIndices->begin(), allIndices->end(), ndx) != allIndices->end()) {
		return false;
	}
	allIndices->push_back(ndx);
	ndx.x += 1;
	AddUniqueAndNeighbors(pDoc, bl, tl, br, tr, ndx, allIndices);
	ndx.x -= 2;
	AddUniqueAndNeighbors(pDoc, bl, tl, br, tr, ndx, allIndices);
	ndx.x += 1;
	ndx.y += 1;
	AddUniqueAndNeighbors(pDoc, bl, tl, br, tr, ndx, allIndices);
	ndx.y -= 2;
	AddUniqueAndNeighbors(pDoc, bl, tl, br, tr, ndx, allIndices);
	return true;
}

} // namespace

Bool CWorldBuilderDoc::getAllIndexesInRect(const Coord3D *bl, const Coord3D *br, const Coord3D *tl, const Coord3D *tr,
	Int widthOutside, std::vector<CPoint> *allIndices)
{
	(void)widthOutside; // EA's body ignores it too.
	if (!(bl && br && tl && tr && allIndices)) {
		return false;
	}
	Coord3D center = { (bl->x + tr->x) / 2, (bl->y + tr->y) / 2, (bl->z + tr->z) / 2 };
	allIndices->clear();
	CPoint ndx;
	// EA passes the corners to AddUniqueAndNeighbors as (bl, br, tl, tr) into parameters named
	// (bl, tl, br, tr); kept as EA has it.
	FindIndexNearest(this, &center, &ndx, PREFER_CENTER);
	AddUniqueAndNeighbors(this, bl, br, tl, tr, ndx, allIndices);
	FindIndexNearest(this, &center, &ndx, PREFER_LEFT);
	AddUniqueAndNeighbors(this, bl, br, tl, tr, ndx, allIndices);
	FindIndexNearest(this, &center, &ndx, PREFER_TOP);
	AddUniqueAndNeighbors(this, bl, br, tl, tr, ndx, allIndices);
	FindIndexNearest(this, &center, &ndx, PREFER_RIGHT);
	AddUniqueAndNeighbors(this, bl, br, tl, tr, ndx, allIndices);
	FindIndexNearest(this, &center, &ndx, PREFER_BOTTOM);
	AddUniqueAndNeighbors(this, bl, br, tl, tr, ndx, allIndices);
	return (!allIndices->empty());
}

// ---------------------------------------------------------------------------------------------
// Undo, invalidation, waypoint labels (EA's bodies; "all views" is the one 3D view).
// ---------------------------------------------------------------------------------------------

WbView3d *CWorldBuilderDoc::GetActive3DView()
{
	CWorldBuilderDoc *pDoc = GetActiveDoc();
	return pDoc ? pDoc->Get3DView() : nullptr;
}

void CWorldBuilderDoc::clearUndo()
{
	REF_PTR_RELEASE(m_undoList);
	m_curRedo = 0;
	m_modified = false;
}

void CWorldBuilderDoc::SetHeightMap(WorldHeightMapEdit *pMap, Bool doUpdate)
{
	REF_PTR_SET(m_heightMap, pMap);
	if (doUpdate) {
		WbView3d *pView = Get3DView();
		if (pView) {
			IRegion2D partialRange = {{0, 0}, {0, 0}};
			pView->updateHeightMapInView(m_heightMap, false, partialRange);
			pView->Invalidate(false);
		}
	}
}

void CWorldBuilderDoc::AddAndDoUndoable(Undoable *pUndo)
{
	Undoable *pCurUndo = m_undoList;
	Int count = m_curRedo;
	while (count > 0 && pCurUndo != nullptr) {
		count--;
		pCurUndo = pCurUndo->GetNext();
	}
	m_waypointTableNeedsUpdate = true;
	m_curRedo = 0;
	pUndo->LinkNext(pCurUndo);
	REF_PTR_SET(m_undoList, pUndo);
	pUndo->Do();
	SetModifiedFlag();
	pCurUndo = m_undoList;
	count = 0;
	while (pCurUndo) {
		count++;
		if (count >= MAX_UNDOS) {
			pCurUndo->LinkNext(nullptr);
			break;
		}
		pCurUndo = pCurUndo->GetNext();
	}
}

bool CWorldBuilderDoc::canUndo() const
{
	if (m_broken) return false;
	Undoable *pUndo = m_undoList;
	Int count = m_curRedo;
	while (count > 0 && pUndo != nullptr) {
		count--;
		pUndo = pUndo->GetNext();
	}
	return pUndo != nullptr;
}

void CWorldBuilderDoc::OnEditRedo()
{
	if (m_broken) return;
	Undoable *pUndo = m_undoList;
	m_waypointTableNeedsUpdate = true;
	if (m_curRedo > 0) {
		Int count = m_curRedo - 1;
		while (count > 0 && pUndo) {
			count--;
			pUndo = pUndo->GetNext();
		}
		if (pUndo) {
			pUndo->Redo();
			SetModifiedFlag();
			m_curRedo--;
		}
	}
}

void CWorldBuilderDoc::OnEditUndo()
{
	if (m_broken) return;
	Undoable *pUndo = m_undoList;
	m_waypointTableNeedsUpdate = true;
	Int count = m_curRedo;
	while (count > 0 && pUndo != nullptr) {
		count--;
		pUndo = pUndo->GetNext();
	}
	if (pUndo != nullptr) {
		pUndo->Undo();
		SetModifiedFlag();
		m_curRedo++;
	}
}

void CWorldBuilderDoc::invalObject(MapObject *pMapObj)
{
	WbView3d *pView = Get3DView();
	if (pView) {
		pView->invalObjectInView(pMapObj);
	}
}

void CWorldBuilderDoc::invalCell(int xIndex, int yIndex)
{
	WbView3d *pView = Get3DView();
	if (pView) {
		pView->invalidateCellInView(xIndex, yIndex);
	}
}

void CWorldBuilderDoc::updateAllViews()
{
	WbView3d *pView = Get3DView();
	if (pView) {
		pView->Invalidate(false);
	}
}

void CWorldBuilderDoc::updateHeightMap(WorldHeightMap *htMap, Bool partial, const IRegion2D &partialRange)
{
	WbView3d *pView = Get3DView();
	if (pView) {
		pView->updateHeightMapInView(htMap, partial, partialRange);
		pView->Invalidate();
	}
}

void CWorldBuilderDoc::updateLinkedWaypointLabels(MapObject *pWay)
{
	updateWaypointTable();
	for (Int i = 0; i < m_numWaypointLinks; i++) {
		m_waypointLinks[i].processedFlag = false;
	}
	updateLWL(pWay, pWay);
}

void CWorldBuilderDoc::updateLWL(MapObject *pWay, MapObject *pSrcWay)
{
	while (pWay) {
		Bool exists;
		AsciiString label;
		label = pSrcWay->getProperties()->getAsciiString(TheKey_waypointPathLabel1, &exists);
		if (exists) {
			pWay->getProperties()->setAsciiString(TheKey_waypointPathLabel1, label);
		} else if (pWay->getProperties()->known(TheKey_waypointPathLabel1, Dict::DICT_ASCIISTRING)) {
			pWay->getProperties()->remove(TheKey_waypointPathLabel1);
		}
		label = pSrcWay->getProperties()->getAsciiString(TheKey_waypointPathLabel2, &exists);
		if (exists) {
			pWay->getProperties()->setAsciiString(TheKey_waypointPathLabel2, label);
		} else if (pWay->getProperties()->known(TheKey_waypointPathLabel2, Dict::DICT_ASCIISTRING)) {
			pWay->getProperties()->remove(TheKey_waypointPathLabel2);
		}
		label = pSrcWay->getProperties()->getAsciiString(TheKey_waypointPathLabel3, &exists);
		if (exists) {
			pWay->getProperties()->setAsciiString(TheKey_waypointPathLabel3, label);
		} else if (pWay->getProperties()->known(TheKey_waypointPathLabel3, Dict::DICT_ASCIISTRING)) {
			pWay->getProperties()->remove(TheKey_waypointPathLabel3);
		}
		Bool biDirectional;
		biDirectional = pSrcWay->getProperties()->getBool(TheKey_waypointPathBiDirectional, &exists);
		if (exists) {
			pWay->getProperties()->setBool(TheKey_waypointPathBiDirectional, biDirectional);
		}
		MapObject *pCurWay = pWay;
		pWay = nullptr;
		for (Int i = 0; i < m_numWaypointLinks; i++) {
			if (m_waypointLinks[i].processedFlag) continue;
			Bool process = false;
			MapObject *pNewWay = nullptr;
			Int waypointID1 = m_waypointLinks[i].waypoint1;
			Int waypointID2 = m_waypointLinks[i].waypoint2;
			if (waypointID1 > 0 && waypointID1 < MAX_WAYPOINTS && waypointID2 > 0 && waypointID2 < MAX_WAYPOINTS) {
				MapObject *pObj = m_waypointTable[waypointID1];
				if (pObj == pCurWay) {
					process = true;
					pNewWay = m_waypointTable[waypointID2];
				}
				pObj = m_waypointTable[waypointID2];
				if (pObj == pCurWay) {
					process = true;
					pNewWay = m_waypointTable[waypointID1];
				}
			}
			if (process) {
				m_waypointLinks[i].processedFlag = true;
				if (pWay == nullptr) {
					pWay = pNewWay;
				} else {
					updateLWL(pNewWay, pSrcWay);
				}
			}
		}
	}
}
