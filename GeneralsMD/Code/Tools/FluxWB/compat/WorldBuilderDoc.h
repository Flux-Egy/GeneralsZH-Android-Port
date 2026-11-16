// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: the World Builder document without MFC.
//
// It keeps EA's class name and the members the compiled EA files call (MapPreview uses
// GetActiveDoc()/GetHeightMap(); the tools and CUndoable, as they are brought over, use the rest), so
// those files compile against it unchanged. The bodies of open() and save() follow
// CWorldBuilderDoc::Serialize (WorldBuilderDoc.cpp) step by step: the same streams, the same order of
// calls, the same post-load fix-ups (validate, compressWaypointIds). Only the MFC plumbing around
// them (CArchive/CFile, views, dialogs) is replaced.
#pragma once

#include "Common/AsciiString.h"
#include "Common/DataChunk.h"

#include <string>
#include <vector>

class WorldHeightMapEdit;
class MapObject;
class WbView3d;
class Undoable;
class WorldHeightMap;
struct IRegion2D;
class CPoint;
struct ICoord2D;
struct Coord3D;

class CWorldBuilderDoc
{
public:
	enum { MAX_WAYPOINTS = 16000 };
	enum { MAX_UNDOS = 15 }; // EA's WorldBuilderDoc.h

	static CWorldBuilderDoc *GetActiveDoc();

	CWorldBuilderDoc();
	~CWorldBuilderDoc();

	/**
	 * Reads a map (a real path or one inside the game's archives). A failure part-way through has
	 * already replaced the objects, sides and triggers (EA's loader fills globals as it reads), so the
	 * document is then marked broken: saving is refused until a map opens or a new one is made.
	 */
	bool open(const char *path, std::string *error);
	/** A new flat map, as File > New with the given size, height and border. */
	bool newMap(Int width, Int height, Int initialHeight, Int border, std::string *error);
	/**
	 * Writes the map exactly as EA's World Builder does: <name>.tga preview beside it, then the
	 * chunks, compressed with the map's own (or the preferred) compression. Written to <path>.tmp and
	 * renamed, so a failed save never leaves half a map. Refused (false, *error set) when the map
	 * lost data on loading -- see missingTextures().
	 */
	bool save(const char *path, std::string *error);

	WorldHeightMapEdit *GetHeightMap() { return m_heightMap; }
	bool hasMap() const { return m_heightMap != nullptr; }

	/** Terrain textures the open map uses that this game lacks; EA's code dropped their tiles. */
	const std::vector<AsciiString> &missingTextures() const { return m_missingTextures; }
	void noteMissingTexture(const AsciiString &name);

	// Waypoints and their links, as in EA's document.
	Int getNumWaypointLinks() { return m_numWaypointLinks; }
	void getWaypointLink(Int ndx, Int *waypointID1, Int *waypointID2);
	void addWaypointLink(Int waypointID1, Int waypointID2);
	void removeWaypointLink(Int waypointID1, Int waypointID2);
	Bool waypointLinkExists(Int waypointID1, Int waypointID2);
	MapObject *getWaypointByID(Int waypointID);
	Int getNextWaypointID() { return ++m_curWaypointID; }
	void setNextWaypointID(Int newMax) { if (newMax > m_curWaypointID) m_curWaypointID = newMax; }
	void compressWaypointIds();
	void updateWaypointTable();

	// Called by EA's DrawObject.cpp, CUndoable.cpp and the tools (EA's bodies, FluxWBDoc.cpp).
	WbView3d *Get3DView();
	static WbView3d *GetActive3DView();
	void SetHeightMap(WorldHeightMapEdit *pMap, Bool doUpdate);
	void AddAndDoUndoable(Undoable *pUndo);
	void OnEditUndo();
	void OnEditRedo();
	bool canUndo() const;
	bool canRedo() const { return !m_broken && m_undoList != nullptr && m_curRedo > 0; }
	void invalObject(MapObject *pMapObj);
	void invalCell(int xIndex, int yIndex);
	void updateAllViews();
	void updateHeightMap(WorldHeightMap *htMap, Bool partial, const IRegion2D &partialRange);
	void updateLinkedWaypointLabels(MapObject *pWay);
	Bool isModified() const { return m_modified; }
	void SetModifiedFlag(Bool modified = true) { m_modified = modified; }
	Int getNumBoundaries() const;
	void getBoundary(Int ndx, ICoord2D *border) const;
	Bool getCellIndexFromCoord(Coord3D cpt, CPoint *ndxP);
	void getCoordFromCellIndex(CPoint ndx, Coord3D *pt);
	Bool getAllIndexesInRect(const Coord3D *bl, const Coord3D *br, const Coord3D *tl, const Coord3D *tr,
		Int widthOutside, std::vector<CPoint> *allIndices);

	/** Where the camera starts: InitialCameraPosition, else the middle of the map (EA's rule). */
	void getStartCenter(Real *x, Real *y) const { *x = m_startX; *y = m_startY; }

	// GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 Start positions. The game points the
	// camera of player N at the waypoint "Player_N_Start" (GameLogic.cpp) and counts a map's players
	// by them; without Player_1_Start, or with it in the border, a map opens on black terrain with
	// only the interface (owner report: a new map). EA's editor leaves that to the map maker.
	/** "" when Player_1_Start.. are there, numbered from 1 without a gap and inside the playable
	 *  area; else "missing=1,2;outside=3" (either part may be absent). */
	std::string checkStartPositions();
	/** Adds the missing starts (at least two) and moves those outside into the playable area, as
	 *  one undoable step. */
	bool fixStartPositions();
	/** The playable area in world units: 0..*w, 0..*h (the border lies around it). */
	void getPlayableSize(Real *w, Real *h) const;
	/** The game's player count (MAX_SLOTS). */
	enum { MAX_START = 8 };

private:
	static Bool ParseWaypointDataChunk(DataChunkInput &file, DataChunkInfo *info, void *userData);
	Bool ParseWaypointData(DataChunkInput &file, DataChunkInfo *info, void *userData);
	void validate();
	void afterLoad();
	void defaultStartLocation(Int player, Coord3D *pt) const;
	MapObject *newStartWaypoint(Int player, const Coord3D &pt);
	void updateLWL(MapObject *pWay, MapObject *pSrcWay);
	void clearUndo();

	WorldHeightMapEdit *m_heightMap;
	Int m_curWaypointID;
	MapObject *m_waypointTable[MAX_WAYPOINTS];
	Bool m_waypointTableNeedsUpdate;
	struct {
		Int waypoint1;
		Int waypoint2;
		Bool processedFlag;
	} m_waypointLinks[MAX_WAYPOINTS];
	Undoable *m_undoList;  ///< Head of undo/redo list.
	int m_curRedo;         ///< 0 means no redos available.
	Bool m_modified;
	Int m_numWaypointLinks;
	std::vector<AsciiString> m_missingTextures;
	Real m_startX;
	Real m_startY;
	/** A load failed part-way (see open()); save() refuses. */
	bool m_broken;
};
