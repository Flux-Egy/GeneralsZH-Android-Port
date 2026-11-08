// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032 Phase 0: the map round-trip test.
//
//   fluxwb_roundtrip --game <game folder> --user <scratch user-data dir> --out <dir> [map ...]
//
// With no map named, every Maps\*.map the game can see (archives and the folder) is tested.
// For each map:
//   A = the map's chunk data as stored (decompressed if it was compressed);
//   B = open it with EA's loader, save it with EA's writer (CWorldBuilderDoc::save), read it back;
//   C = open B and save it again.
// Verdicts:
//   IDENTICAL  B == A byte for byte: the native editor writes this map exactly as it was.
//   UPGRADED   B != A, but B == C: EA's writer stored the same map in its current format (an older
//              chunk version, a renumbered waypoint); the per-chunk report says which chunks changed.
//   UNSTABLE   B != C: saving is not repeatable -- a defect; the tool exits non-zero.
//   REFUSED    the save was refused (a terrain texture this game lacks); nothing to compare.
//   FAILED     the map could not be read or written.
// Nothing is ever written outside --out and --user.

#include "StdAfx.h"

#include "FluxWBCore.h"
#include "WorldBuilderDoc.h"
#include "FluxWBScripts.h"
#include "wbview3d.h"

#include "Common/FileSystem.h"
#include "Common/MapObject.h"
#include "Compression.h"
#include "GameLogic/Scripts.h"

#include <cstdio>
#include <cstring>
#include <strings.h>
#include <algorithm>
#include <iterator>
#include <map>
#include <string>
#include <sys/stat.h>
#include <vector>

#include <csignal>
#include <dlfcn.h>
#include <unistd.h>
#include <unwind.h>

// A command-line program has no Java VM, and SDL's Android helpers need one. The engine asks SDL for
// the app's internal folder (the launcher's mod list, mods_active.txt, lives there): this tool answers
// with FLUXWB_INTERNAL_DIR, an empty scratch folder by default, so the maps are read with the plain
// game data. The definition here wins over libSDL3.so's for the engine code linked into this tool.
extern "C" const char *SDL_GetAndroidInternalStoragePath(void)
{
	const char *dir = getenv("FLUXWB_INTERNAL_DIR");
	return dir != nullptr ? dir : "/nonexistent-fluxwb-internal";
}
extern "C" void *SDL_GetAndroidJNIEnv(void)
{
	return nullptr;
}
extern "C" void *SDL_GetAndroidActivity(void)
{
	return nullptr;
}

namespace {

// A crash prints the frames (module + offset) for llvm-symbolizer; this tool has no other reporter.
_Unwind_Reason_Code printFrame(struct _Unwind_Context *ctx, void *arg)
{
	int *depth = static_cast<int *>(arg);
	const uintptr_t pc = _Unwind_GetIP(ctx);
	Dl_info info;
	if (pc && dladdr((void *)pc, &info) && info.dli_fname) {
		fprintf(stderr, "  #%02d %s+0x%zx\n", *depth, info.dli_fname, (size_t)(pc - (uintptr_t)info.dli_fbase));
	}
	return ++*depth < 48 ? _URC_NO_REASON : _URC_END_OF_STACK;
}

void onCrash(int sig)
{
	fprintf(stderr, "[fluxwb_roundtrip] signal %d\n", sig);
	int depth = 0;
	_Unwind_Backtrace(printFrame, &depth);
	fflush(stderr);
	_exit(128 + sig);
}

using Bytes = std::vector<unsigned char>;

bool readGameFile(const char *path, Bytes *out)
{
	File *file = TheFileSystem->openFile(path, File::READ | File::BINARY);
	if (file == nullptr) {
		return false;
	}
	const Int size = file->size();
	out->assign(size > 0 ? size : 0, 0);
	const Int got = size > 0 ? file->read(out->data(), size) : 0;
	file->close();
	return got == size;
}

bool readRealFile(const std::string &path, Bytes *out)
{
	FILE *f = fopen(path.c_str(), "rb");
	if (f == nullptr) {
		return false;
	}
	out->clear();
	unsigned char buf[65536];
	size_t n;
	while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
		out->insert(out->end(), buf, buf + n);
	}
	fclose(f);
	return true;
}

/** The chunk data, decompressed when the file is compressed (as CachedFileInputStream does). */
Bytes decompressed(const Bytes &in)
{
	if (in.empty() || !CompressionManager::isDataCompressed(in.data(), (Int)in.size())) {
		return in;
	}
	const Int size = CompressionManager::getUncompressedSize(in.data(), (Int)in.size());
	Bytes out(size > 0 ? size : 0);
	const Int got = size > 0
		? CompressionManager::decompressData((void *)in.data(), (Int)in.size(), out.data(), size)
		: 0;
	out.resize(got > 0 ? got : 0);
	return out;
}

struct Chunk {
	std::string name;
	unsigned short version;
	Bytes data;
};

/** Top-level chunks of a CkMp file, with their names from its table of contents. */
bool parseChunks(const Bytes &b, std::vector<Chunk> *chunks, std::vector<std::string> *tocOrder = nullptr)
{
	size_t p = 0;
	auto need = [&](size_t n) { return p + n <= b.size(); };
	if (!need(8) || memcmp(b.data(), "CkMp", 4) != 0) {
		return false;
	}
	p = 4;
	Int count;
	memcpy(&count, &b[p], 4);
	p += 4;
	std::map<unsigned, std::string> names;
	for (Int i = 0; i < count; ++i) {
		if (!need(1)) return false;
		const unsigned len = b[p++];
		if (!need(len + 4)) return false;
		std::string name((const char *)&b[p], len);
		p += len;
		unsigned id;
		memcpy(&id, &b[p], 4);
		p += 4;
		names[id] = name;
		if (tocOrder) tocOrder->push_back(name);
	}
	while (p < b.size()) {
		if (!need(10)) return false;
		unsigned id;
		unsigned short ver;
		Int size;
		memcpy(&id, &b[p], 4);
		memcpy(&ver, &b[p + 4], 2);
		memcpy(&size, &b[p + 6], 4);
		p += 10;
		if (size < 0 || !need((size_t)size)) return false;
		Chunk c;
		c.name = names.count(id) ? names[id] : "?" + std::to_string(id);
		c.version = ver;
		c.data.assign(b.begin() + p, b.begin() + p + size);
		chunks->push_back(c);
		p += size;
	}
	return true;
}

std::string chunkReport(const Bytes &a, const Bytes &b)
{
	std::vector<Chunk> ca, cb;
	std::vector<std::string> ta, tb;
	if (!parseChunks(a, &ca, &ta) || !parseChunks(b, &cb, &tb)) {
		return "  (not chunk files; no per-chunk report)";
	}
	std::string r;
	{
		// Chunk names and Dict keys are written as ids from this table, numbered in first-use order:
		// a different order changes the bytes of every chunk using them without changing the content.
		std::vector<std::string> sa(ta), sb(tb);
		std::sort(sa.begin(), sa.end());
		std::sort(sb.begin(), sb.end());
		std::vector<std::string> lost;
		std::set_difference(sa.begin(), sa.end(), sb.begin(), sb.end(), std::back_inserter(lost));
		char line[160];
		snprintf(line, sizeof(line), "  name table: %zu -> %zu names, %s, %zu dropped\n", ta.size(), tb.size(),
			ta == tb ? "same order" : "renumbered", lost.size());
		r += line;
		for (const std::string &n : lost) {
			r += "    dropped name: " + n + "\n";
		}
	}
	const size_t n = ca.size() > cb.size() ? ca.size() : cb.size();
	for (size_t i = 0; i < n; ++i) {
		char line[256];
		if (i >= ca.size()) {
			snprintf(line, sizeof(line), "  + %s v%u (%zu bytes) only in the saved map\n", cb[i].name.c_str(),
				cb[i].version, cb[i].data.size());
		} else if (i >= cb.size()) {
			snprintf(line, sizeof(line), "  - %s v%u (%zu bytes) only in the original\n", ca[i].name.c_str(),
				ca[i].version, ca[i].data.size());
		} else if (ca[i].name != cb[i].name) {
			snprintf(line, sizeof(line), "  ~ chunk %zu: %s in the original, %s in the saved map\n", i,
				ca[i].name.c_str(), cb[i].name.c_str());
		} else if (ca[i].version != cb[i].version || ca[i].data != cb[i].data) {
			size_t at = 0;
			while (at < ca[i].data.size() && at < cb[i].data.size() && ca[i].data[at] == cb[i].data[at]) ++at;
			snprintf(line, sizeof(line), "  ~ %s v%u -> v%u, %zu -> %zu bytes, first difference at %zu\n",
				ca[i].name.c_str(), ca[i].version, cb[i].version, ca[i].data.size(), cb[i].data.size(), at);
		} else {
			snprintf(line, sizeof(line), "  = %s v%u\n", ca[i].name.c_str(), ca[i].version);
		}
		r += line;
	}
	return r;
}

std::string leafName(const std::string &path)
{
	size_t s = path.find_last_of("/\\");
	std::string leaf = s == std::string::npos ? path : path.substr(s + 1);
	for (char &c : leaf) {
		if (c == ' ') c = '_';
	}
	return leaf;
}

} // namespace

int main(int argc, char **argv)
{
	signal(SIGSEGV, onCrash);
	signal(SIGBUS, onCrash);
	signal(SIGABRT, onCrash);
	FluxWB::StartOptions options;
	options.headless = true;
	std::string outDir;
	std::vector<std::string> maps;
	bool editTest = false;
	bool newTest = false;
	for (int i = 1; i < argc; ++i) {
		const std::string a = argv[i];
		if (a == "--game" && i + 1 < argc) options.gameDir = argv[++i];
		else if (a == "--user" && i + 1 < argc) options.userDataDir = argv[++i];
		else if (a == "--out" && i + 1 < argc) outDir = argv[++i];
		else if (a == "--edit-test") editTest = true;
		else if (a == "--new-test") newTest = true;
		else maps.push_back(a);
	}
	if (options.gameDir.empty() || options.userDataDir.empty() || outDir.empty()) {
		fprintf(stderr, "usage: fluxwb_roundtrip --game <dir> --user <dir> --out <dir> [map ...]\n");
		return 2;
	}
	mkdir(outDir.c_str(), 0755);
	std::string error;
	if (!FluxWB::startEngine(options, &error)) {
		fprintf(stderr, "start-up failed: %s\n", error.c_str());
		return 2;
	}
	// --edit-test: the mission editor's edits survive EA's writer and reader, and saving stays stable.
	if (editTest && !maps.empty()) {
		int rc = 0;
		{
			// EA's undo steps talk to the 3D view, which the editor always has; a view that never
			// starts a device answers them without drawing.
			WbView3d view;
			CWorldBuilderDoc doc;
			const std::string out1 = outDir + "/edit_test.map", out2 = outDir + "/edit_test_again.map";
			auto check = [&](bool ok, const char *what) { printf("%s %s\n", ok ? "PASS" : "FAIL", what); fflush(stdout); if (!ok) rc = 1; };
			check(doc.open(maps[0].c_str(), &error), "open");
			FluxWB::scriptsBegin();
			{
				// EA OnRemoveplayer: a player's teams go with it (and the limit of 15 sides is EA's).
				const std::string r = FluxWB::scriptEdit("addplayer FactionChina");
				if (!r.empty()) printf("  (addplayer: %s)\n", r.c_str());
				check(r.empty(), "add a player to remove");
				check(FluxWB::scriptEdit("addteam teamFluxGone player0001").empty(), "add its team");
				std::string tree = FluxWB::scriptsTree();
				size_t pos = tree.find("\tplayer0001\t");
				size_t ls = pos == std::string::npos ? std::string::npos : tree.rfind('\n', pos);
				int pidx = pos == std::string::npos ? -1 : atoi(tree.c_str() + (ls == std::string::npos ? 0 : ls + 1) + 2);
				char rm[64];
				snprintf(rm, sizeof(rm), "removeplayer %d", pidx);
				check(FluxWB::scriptEdit(rm).empty(), "remove it");
				tree = FluxWB::scriptsTree();
				check(tree.find("player0001") == std::string::npos && tree.find("teamFluxGone") == std::string::npos,
					"its teams went with it");
			}
			check(FluxWB::scriptEdit("addplayer FactionAmerica").empty(), "add a player");
			check(FluxWB::scriptsTree().find("player0001") != std::string::npos, "player listed");
			check(FluxWB::scriptEdit("addteam teamFluxTest player0001").empty(), "add a team");
			check(FluxWB::scriptEdit("addteam teamFluxNeutral -").empty(), "add a neutral team");
			check(FluxWB::scriptEdit("new 0 -1").empty(), "new script");
			// The new script is the last top-level script of player 0.
			int last = -1;
			{
				std::string tree = FluxWB::scriptsTree();
				size_t pos = 0;
				while ((pos = tree.find("S\t0\t-1\t", pos)) != std::string::npos) { last = atoi(tree.c_str() + pos + 7); pos++; }
			}
			char cmd[200];
			snprintf(cmd, sizeof(cmd), "prop 0 -1 %d name Flux edit test", last);
			check(FluxWB::scriptEdit(cmd).empty(), "rename");
			snprintf(cmd, sizeof(cmd), "addact 0 -1 %d t %d", last, (int)ScriptAction::SET_FLAG);
			check(FluxWB::scriptEdit(cmd).empty(), "add action SET_FLAG");
			snprintf(cmd, sizeof(cmd), "param 0 -1 %d t 1 0 s:FluxFlag", last);
			check(FluxWB::scriptEdit(cmd).empty(), "set flag name");
			snprintf(cmd, sizeof(cmd), "param 0 -1 %d t 1 1 i:1", last);
			check(FluxWB::scriptEdit(cmd).empty(), "set flag value");
			check(FluxWB::scriptsCommit(), "commit");
			check(doc.canUndo(), "commit is one undo step");
			check(doc.save(out1.c_str(), &error), "save");
			check(doc.open(out1.c_str(), &error), "reopen");
			FluxWB::scriptsBegin();
			const std::string tree = FluxWB::scriptsTree();
			check(tree.find("Flux edit test") != std::string::npos, "script survives");
			check(tree.find("teamFluxTest") != std::string::npos, "team survives");
			check(tree.find("teamFluxNeutral") != std::string::npos, "neutral team survives");
			check(tree.find("player0001") != std::string::npos, "player survives");
			int idx = -1;
			{
				size_t pos = tree.find("Flux edit test");
				size_t lineStart = tree.rfind('\n', pos);
				lineStart = lineStart == std::string::npos ? 0 : lineStart + 1;
				if (tree.compare(lineStart, 7, "S\t0\t-1\t") == 0) idx = atoi(tree.c_str() + lineStart + 7);
			}
			const std::string detail = idx >= 0 ? FluxWB::scriptDetail(0, -1, idx) : "";
			check(detail.find("FluxFlag") != std::string::npos, "flag parameter survives");
			check(detail.find("N\tFlux edit test") != std::string::npos, "the edited script is the renamed one");
			FluxWB::scriptsCancel();
			check(doc.save(out2.c_str(), &error), "save again");
			Bytes a, b;
			check(readRealFile(out1, &a) && readRealFile(out2, &b) && decompressed(a) == decompressed(b), "stable");
		}
		FluxWB::stopEngine();
		return rc;
	}
	// GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 --new-test: a new map has its start
	// positions and its camera in the middle, keeps them through EA's writer and reader, and the
	// start check finds and repairs a missing start and one in the border, undoably.
	if (newTest) {
		int rc = 0;
		{
			WbView3d view;
			CWorldBuilderDoc doc;
			auto check = [&](bool ok, const char *what) { printf("%s %s\n", ok ? "PASS" : "FAIL", what); fflush(stdout); if (!ok) rc = 1; };
			auto start = [](int n) -> MapObject * {
				AsciiString name;
				name.format("Player_%d_Start", n);
				for (MapObject *o = MapObject::getFirstMapObject(); o; o = o->getNext()) {
					if (o->isWaypoint() && o->getWaypointName() == name) return o;
				}
				return nullptr;
			};
			auto inside = [&](MapObject *o) {
				Real w, h;
				doc.getPlayableSize(&w, &h);
				const Coord3D *p = o ? o->getLocation() : nullptr;
				return p && p->x >= 0 && p->y >= 0 && p->x <= w && p->y <= h;
			};
			const std::string out1 = outDir + "/new_test.map", out2 = outDir + "/new_test_fixed.map";
			check(doc.newMap(100, 100, 16, 30, &error), "new 100x100, border 30");
			Real cx, cy;
			doc.getStartCenter(&cx, &cy);
			printf("  camera at %.0f,%.0f\n", cx, cy);
			check(cx == 500 && cy == 500, "camera in the middle of the playable area");
			check(inside(start(1)) && inside(start(2)), "Player_1_Start and Player_2_Start inside");
			check(doc.checkStartPositions().empty(), "start check passes");
			check(doc.save(out1.c_str(), &error), "save");
			check(doc.open(out1.c_str(), &error), "open it again");
			doc.getStartCenter(&cx, &cy);
			printf("  camera after loading at %.0f,%.0f\n", cx, cy);
			check(cx == 500 && cy == 500, "camera in the middle after loading");
			check(inside(start(1)) && inside(start(2)), "starts survive EA's writer and reader");
			check(doc.checkStartPositions().empty(), "start check passes after loading");
			// The owner's map: start 1 named by hand, start 2 in the border.
			if (start(1)) start(1)->setWaypointName("P1");
			if (start(2)) {
				Coord3D far = *start(2)->getLocation();
				far.x = -100; far.y = 1030;
				start(2)->setLocation(&far);
			}
			const std::string problems = doc.checkStartPositions();
			printf("  check: %s\n", problems.c_str());
			check(problems == "missing=1;outside=2", "finds the missing start and the one in the border");
			check(doc.fixStartPositions(), "fix");
			check(doc.checkStartPositions().empty() && inside(start(1)) && inside(start(2)), "fixed");
			doc.OnEditUndo();
			check(doc.checkStartPositions() == "missing=1;outside=2", "undo brings the problems back");
			doc.OnEditRedo();
			check(doc.checkStartPositions().empty(), "redo fixes them again");
			check(doc.save(out2.c_str(), &error), "save the fixed map");
			check(doc.open(out2.c_str(), &error) && doc.checkStartPositions().empty(), "fixed map loads with its starts");
		}
		FluxWB::stopEngine();
		return rc;
	}
	if (maps.empty()) {
		FilenameList list;
		TheFileSystem->getFileListInDirectory("Maps\\", "*.map", list, TRUE);
		for (const AsciiString &m : list) {
			maps.push_back(m.str());
		}
	}

	int identical = 0, upgraded = 0, unstable = 0, refused = 0, failed = 0;
	{
	// The document is released before stopEngine() shuts the memory manager down.
	CWorldBuilderDoc doc;
	int index = 0;
	for (const std::string &map : maps) {
		++index;
		Bytes original;
		if (!readGameFile(map.c_str(), &original)) {
			printf("FAILED     %s (cannot read)\n", map.c_str());
			++failed;
			continue;
		}
		// Numbered, so two maps with the same file name in different folders never collide; no ".map"
		// inside the stem, or MapPreview's ".map" -> ".tga" (EA, every occurrence) misplaces the preview.
		std::string leaf = leafName(map);
		if (leaf.size() > 4 && strcasecmp(leaf.c_str() + leaf.size() - 4, ".map") == 0) {
			leaf.resize(leaf.size() - 4);
		}
		const std::string stem = outDir + "/" + std::to_string(index) + "_" + leaf;
		const std::string outB = stem + ".map";
		const std::string outC = stem + "_again.map";
		if (!doc.open(map.c_str(), &error)) {
			printf("FAILED     %s (%s)\n", map.c_str(), error.c_str());
			++failed;
			continue;
		}
		if (!doc.save(outB.c_str(), &error)) {
			const bool isRefusal = !doc.missingTextures().empty();
			printf("%s %s (%s)\n", isRefusal ? "REFUSED   " : "FAILED    ", map.c_str(), error.c_str());
			++(isRefusal ? refused : failed);
			continue;
		}
		Bytes savedB, savedC;
		if (!readRealFile(outB, &savedB) || !doc.open(outB.c_str(), &error) || !doc.save(outC.c_str(), &error) ||
				!readRealFile(outC, &savedC)) {
			printf("FAILED     %s (second pass: %s)\n", map.c_str(), error.c_str());
			++failed;
			continue;
		}
		const Bytes a = decompressed(original), b = decompressed(savedB), c = decompressed(savedC);
		if (b != c) {
			printf("UNSTABLE   %s\n%s", map.c_str(), chunkReport(b, c).c_str());
			++unstable;
		} else if (a == b) {
			printf("IDENTICAL  %s (%zu bytes of chunks)\n", map.c_str(), a.size());
			++identical;
		} else {
			printf("UPGRADED   %s\n%s", map.c_str(), chunkReport(a, b).c_str());
			++upgraded;
		}
		fflush(stdout);
		remove(outC.c_str());
		remove((stem + "_again.tga").c_str());
	}
	}
	printf("\n%zu maps: %d identical, %d upgraded, %d unstable, %d refused, %d failed\n", maps.size(),
		identical, upgraded, unstable, refused, failed);
	FluxWB::stopEngine();
	return unstable > 0 || failed > 0 ? 1 : 0;
}
