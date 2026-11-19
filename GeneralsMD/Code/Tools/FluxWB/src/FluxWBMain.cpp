// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: SDL_main of libfluxwb.so.
//
// WorldBuilderActivity (an SDLActivity in its own ":wb" process) loads SDL3 and this library and
// calls SDL_main. Start-up follows the game's (Core/Main/SDL3Main.cpp) for what the two must agree
// on -- the game folder the launcher chose, the user-data folder (Maps\), the render backend picked
// in the launcher, the log file -- and nothing else: no network, no game logic, no TouchInput.
// Touch goes straight from SDL's finger events to the editor's camera and tools below.
//
// Arguments (WorldBuilderActivity.getArguments):
//   -game <Zero Hour folder> -user <Zero Hour user-data folder> [-map <path>] [-new <w> <h> <height> <border>]
// The editor works on Zero Hour data whichever game the launcher has selected, so the activity passes
// the Zero Hour folders; the launcher's marker files (the selected game's) are only a fallback.

#include "StdAfx.h"

#include "FluxWBBridge.h"
#include "FluxWBCore.h"
#include "FluxWBTools.h"
#include "FluxWBScripts.h"
#include "WorldBuilder.h"
#include "WorldBuilderDoc.h"
#include "wbview3d.h"

#include "Common/GXLogging.h"
#include "d3d8gles.h"
#include "dx8wrapper.h"

#include <SDL3/SDL.h>
#include <SDL3/SDL_main.h>
#include <SDL3/SDL_vulkan.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <sstream>
#include <vector>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

extern SDL_Window *TheSDL3Window;
extern HWND ApplicationHWnd;
extern int __argc;
extern char **__argv;

namespace {

std::string readFirstLine(const std::string &path)
{
	FILE *f = fopen(path.c_str(), "r");
	if (f == nullptr) {
		return std::string();
	}
	char line[1024] = { 0 };
	std::string out;
	if (fgets(line, sizeof(line), f) != nullptr) {
		out = line;
		while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) {
			out.pop_back();
		}
	}
	fclose(f);
	return out;
}

// The game's display-size callbacks (W3DDisplay.cpp), for DX8Wrapper's viewport.
bool nativeDisplaySize(int &outW, int &outH, float &outDensity)
{
	if (!TheSDL3Window) return false;
	SDL_DisplayID displayId = SDL_GetDisplayForWindow(TheSDL3Window);
	const SDL_DisplayMode *mode = SDL_GetCurrentDisplayMode(displayId);
	if (!mode || mode->w <= 0 || mode->h <= 0) return false;
	outDensity = mode->pixel_density > 0 ? mode->pixel_density : 1.0f;
	outW = (int)(mode->w * outDensity);
	outH = (int)(mode->h * outDensity);
	return true;
}

bool windowSizeInPixels(int &outW, int &outH, float &outDensity)
{
	if (!TheSDL3Window) return false;
	int logW = 0, logH = 0, physW = 0, physH = 0;
	SDL_GetWindowSize(TheSDL3Window, &logW, &logH);
	SDL_GetWindowSizeInPixels(TheSDL3Window, &physW, &physH);
	if (physW <= 0 || physH <= 0) return false;
	outW = physW;
	outH = physH;
	outDensity = (logW > 0) ? (float)physW / (float)logW : 1.0f;
	return true;
}

/** Where things live, from the same marker files and folders the game uses (SDL3Main.cpp). */
struct AndroidPaths {
	std::string internal;
	std::string external;
	std::string gameDir;
	std::string userDataDir;
};

AndroidPaths androidPaths()
{
	AndroidPaths p;
	const char *internal = SDL_GetAndroidInternalStoragePath();
	const char *external = SDL_GetAndroidExternalStoragePath();
	p.internal = internal ? internal : "";
	p.external = external ? external : "";
	if (!p.internal.empty()) {
		setenv("HOME", p.internal.c_str(), 1);
		p.gameDir = readFirstLine(p.internal + "/gamedata_path.txt");
		const std::string base = readFirstLine(p.internal + "/generals_base_path.txt");
		if (!base.empty()) {
			setenv("CNC_GENERALS_PATH", base.c_str(), 1);
		}
	}
	if (p.gameDir.empty() && !p.external.empty()) {
		p.gameDir = p.external + "/GameData";
	}
	// The game's user-data folder: /storage/emulated/<user>/Generals/<leaf> (issue #9).
	const int userId = (int)(getuid() / 100000);
	char root[280];
	snprintf(root, sizeof(root), "/storage/emulated/%d/Generals", userId);
	mkdir(root, 0755);
	p.userDataDir = std::string(root) + "/Command and Conquer Generals Zero Hour Data";
	if (!p.external.empty()) {
		setenv("GENERALSX_BUNDLED_FONTS_DIR", (p.external + "/fonts").c_str(), 1);
	}
	return p;
}

void openLog(const AndroidPaths &p)
{
	if (GXLoggingDisabled()) {
		return;
	}
	const std::string dir = !p.external.empty() ? p.external : p.internal;
	if (dir.empty()) {
		return;
	}
	const std::string log = dir + "/fluxwb-stderr.log";
	rename(log.c_str(), (dir + "/fluxwb-stderr-prev.log").c_str());
	if (freopen(log.c_str(), "w", stderr) != nullptr) {
		setvbuf(stderr, nullptr, _IOLBF, 0);
		fprintf(stderr, "=== Flux World Builder log (PID %d) ===\n", (int)getpid());
	}
}

/** The SDL window, for the backend the launcher picked (as SDL3Main does). */
bool createWindow(bool *useVulkan)
{
	*useVulkan = d3d8gles_ShouldUseVulkanBackend();
	if (!*useVulkan && d3d8gles_ShouldUseANGLE()) {
		SDL_SetHint(SDL_HINT_EGL_LIBRARY, "libEGL_angle.so");
		setenv("ANGLE_FEATURE_OVERRIDES_DISABLED", "enablePreRotateSurfaces", 1);
	}
	SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, "0");
	SDL_SetHint(SDL_HINT_ANDROID_BLOCK_ON_PAUSE, "0");
	if (!SDL_InitSubSystem(SDL_INIT_VIDEO)) {
		fprintf(stderr, "[fluxwb] SDL video: %s\n", SDL_GetError());
		return false;
	}
	if (*useVulkan) {
		setenv("DXVK_WSI_DRIVER", "SDL3", 1);
		if (!SDL_Vulkan_LoadLibrary(nullptr)) {
			fprintf(stderr, "[fluxwb] Vulkan: %s\n", SDL_GetError());
		}
	} else {
		SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
		SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
		SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
		SDL_GL_SetAttribute(SDL_GL_RED_SIZE, 8);
		SDL_GL_SetAttribute(SDL_GL_GREEN_SIZE, 8);
		SDL_GL_SetAttribute(SDL_GL_BLUE_SIZE, 8);
		SDL_GL_SetAttribute(SDL_GL_ALPHA_SIZE, 8);
		SDL_GL_SetAttribute(SDL_GL_DEPTH_SIZE, 24);
		SDL_GL_SetAttribute(SDL_GL_STENCIL_SIZE, 8);
		SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
	}
	const Uint32 flags = (*useVulkan ? SDL_WINDOW_VULKAN : SDL_WINDOW_OPENGL) | SDL_WINDOW_RESIZABLE |
		SDL_WINDOW_HIGH_PIXEL_DENSITY | SDL_WINDOW_FULLSCREEN;
	TheSDL3Window = SDL_CreateWindow("World Builder", 1280, 720, flags);
	if (TheSDL3Window == nullptr) {
		fprintf(stderr, "[fluxwb] window: %s\n", SDL_GetError());
		return false;
	}
	ApplicationHWnd = (HWND)TheSDL3Window;
	DX8Wrapper::Set_Display_Size_Provider(nativeDisplaySize, windowSizeInPixels);
	return true;
}

/**
 * Touch. One finger uses the current tool exactly as EA's left mouse button does (WbView::mouseDown /
 * mouseMove / mouseUp); with no tool ("camera") it moves the map. Two fingers are always the camera:
 * pinch zooms, twist turns, moving together pans. A second finger landing during a tool stroke ends
 * the stroke there (it is committed as one undo step, like a mouse release). After a fast pan the map
 * keeps gliding and slows down (momentum), unless the player turned that off.
 *
 * A stroke does not start on the touch itself: EA's terrain tools edit inside mouseDown, so a stroke
 * started by the first finger of a pinch would leave a dab and an undo step. It starts on the first
 * real movement or after kStrokeDelayMs of holding still; a second finger landing before that makes the
 * gesture a camera gesture and the tool never sees it. A quick tap still reaches the tool as one press
 * and release at the tap point (object and waypoint placement).
 */
class Gestures
{
public:
	bool momentum = true;

	void onEvent(const SDL_Event &e, WbView3d &view, int width, int height)
	{
		if (e.type != SDL_EVENT_FINGER_DOWN && e.type != SDL_EVENT_FINGER_MOTION &&
				e.type != SDL_EVENT_FINGER_UP && e.type != SDL_EVENT_FINGER_CANCELED) {
			return;
		}
		const SDL_FingerID id = e.tfinger.fingerID;
		const float x = e.tfinger.x * width, y = e.tfinger.y * height;
		const Uint64 now = SDL_GetTicks();
		if (e.type == SDL_EVENT_FINGER_DOWN) {
			m_velX = m_velY = 0; // a touch stops a glide
			m_fingers[id] = Point{ x, y };
			if (m_fingers.size() == 1) {
				m_toolFinger = id;
				m_pending = toolActive();
				m_toolStroke = false;
				m_panning = !m_pending;
				m_pendingAt = Point{ x, y };
				m_pendingSince = now;
			} else {
				m_pending = false; // a second finger: a camera gesture, the tool never sees it
				endToolStroke(view);
				m_panning = false;
			}
			m_havePair = false;
			m_lastMove = now;
			return;
		}
		if (e.type == SDL_EVENT_FINGER_UP || e.type == SDL_EVENT_FINGER_CANCELED) {
			if (m_pending && id == m_toolFinger) {
				m_pending = false;
				if (e.type == SDL_EVENT_FINGER_UP) {
					// A tap: one press and release where the finger touched.
					const CPoint at((int)m_pendingAt.x, (int)m_pendingAt.y);
					view.mouseDown(TRACK_L, at);
					view.mouseUp(TRACK_L, at);
				}
			}
			if (m_toolStroke && id == m_toolFinger) {
				view.mouseUp(TRACK_L, CPoint((int)x, (int)y));
				m_toolStroke = false;
			}
			m_fingers.erase(id);
			if (m_fingers.empty()) {
				// A fling: keep the last speed if the finger was still moving when it lifted.
				if (!(momentum && now - m_lastMove < 80)) {
					m_velX = m_velY = 0;
				}
				m_panning = false;
			}
			m_havePair = false;
			return;
		}
		auto it = m_fingers.find(id);
		if (it == m_fingers.end()) {
			return;
		}
		const Point last = it->second;
		it->second = Point{ x, y };
		const float dt = std::max(1.0f, (float)(now - m_lastMove)) / 1000.0f;
		if (m_fingers.size() == 1) {
			if (m_pending && id == m_toolFinger) {
				const float mx = x - m_pendingAt.x, my = y - m_pendingAt.y;
				if (mx * mx + my * my >= kStrokeSlopPx * kStrokeSlopPx) {
					startStroke(view);
				}
			}
			if (m_toolStroke && id == m_toolFinger) {
				view.mouseMove(TRACK_L, CPoint((int)x, (int)y));
			} else if (m_panning) {
				m_accPanX += x - last.x;
				m_accPanY += y - last.y;
				trackVelocity((x - last.x) / dt, (y - last.y) / dt);
			}
		} else if (m_fingers.size() >= 2) {
			auto a = m_fingers.begin();
			auto b = std::next(a);
			const float dx = b->second.x - a->second.x, dy = b->second.y - a->second.y;
			const float dist = std::sqrt(dx * dx + dy * dy);
			const float angle = std::atan2(dy, dx);
			const float mx = (a->second.x + b->second.x) * 0.5f, my = (a->second.y + b->second.y) * 0.5f;
			if (m_havePair && m_pairDist > 1.0f && dist > 1.0f) {
				m_accZoom *= dist / m_pairDist;
				float turn = angle - m_pairAngle;
				if (turn > M_PI) turn -= 2 * M_PI;
				if (turn < -M_PI) turn += 2 * M_PI;
				m_accTurn -= turn;
				m_accPanX += mx - m_pairMidX;
				m_accPanY += my - m_pairMidY;
				trackVelocity((mx - m_pairMidX) / dt, (my - m_pairMidY) / dt);
			}
			m_havePair = true;
			m_pairDist = dist;
			m_pairAngle = angle;
			m_pairMidX = mx;
			m_pairMidY = my;
		}
		m_lastMove = now;
	}

	/** Starts a stroke held still long enough, and applies the frame's camera moves (every frame). */
	void tick(WbView3d &view)
	{
		// Touch events come faster than frames and unevenly; the camera moves once per frame by their
		// sum, so each drawn frame advances by what the fingers did since the last one.
		if (m_accPanX != 0 || m_accPanY != 0) view.panByPixels(m_accPanX, m_accPanY);
		if (m_accZoom != 1.0f) view.zoomBy(m_accZoom);
		if (m_accTurn != 0) view.rotateBy(m_accTurn);
		m_accPanX = m_accPanY = 0;
		m_accZoom = 1.0f;
		m_accTurn = 0;
		if (m_pending && m_fingers.size() == 1 && SDL_GetTicks() - m_pendingSince >= kStrokeDelayMs) {
			startStroke(view);
		}
	}

	/** One frame of glide; true while the map is still moving. */
	bool glide(WbView3d &view, float dtSeconds)
	{
		if (!momentum || !m_fingers.empty() || (m_velX == 0 && m_velY == 0)) {
			return false;
		}
		view.panByPixels(m_velX * dtSeconds, m_velY * dtSeconds);
		const float decay = std::exp(-4.0f * dtSeconds); // about a quarter of the speed left after 0.35 s
		m_velX *= decay;
		m_velY *= decay;
		if (std::fabs(m_velX) < 20.0f && std::fabs(m_velY) < 20.0f) {
			m_velX = m_velY = 0;
		}
		return true;
	}

	/** A tool change or a dialog: end any stroke cleanly. */
	void cancel(WbView3d &view)
	{
		m_accPanX = m_accPanY = 0;
		m_accZoom = 1.0f;
		m_accTurn = 0;
		m_pending = false;
		endToolStroke(view);
		m_fingers.clear();
		m_velX = m_velY = 0;
		m_panning = false;
		m_havePair = false;
	}

private:
	struct Point {
		float x;
		float y;
	};

	static bool toolActive() { return !FluxWB::currentToolName().empty(); }

	static constexpr Uint64 kStrokeDelayMs = 80;
	static constexpr float kStrokeSlopPx = 10.0f;

	void startStroke(WbView3d &view)
	{
		m_pending = false;
		m_toolStroke = true;
		view.mouseDown(TRACK_L, CPoint((int)m_pendingAt.x, (int)m_pendingAt.y));
		auto it = m_fingers.find(m_toolFinger);
		if (it != m_fingers.end()) {
			view.mouseMove(TRACK_L, CPoint((int)it->second.x, (int)it->second.y));
		}
	}

	void endToolStroke(WbView3d &view)
	{
		if (!m_toolStroke) return;
		auto it = m_fingers.find(m_toolFinger);
		CPoint p = it != m_fingers.end() ? CPoint((int)it->second.x, (int)it->second.y) : CPoint(0, 0);
		view.mouseUp(TRACK_L, p);
		m_toolStroke = false;
	}

	void trackVelocity(float vx, float vy)
	{
		// Smoothed, and capped so one jittery sample cannot throw the map across the screen.
		const float cap = 6000.0f;
		vx = std::max(-cap, std::min(cap, vx));
		vy = std::max(-cap, std::min(cap, vy));
		m_velX = 0.6f * vx + 0.4f * m_velX;
		m_velY = 0.6f * vy + 0.4f * m_velY;
	}

	std::map<SDL_FingerID, Point> m_fingers;
	SDL_FingerID m_toolFinger = 0;
	bool m_toolStroke = false;
	bool m_pending = false;
	Point m_pendingAt = { 0, 0 };
	Uint64 m_pendingSince = 0;
	bool m_panning = false;
	bool m_havePair = false;
	float m_pairDist = 0, m_pairAngle = 0, m_pairMidX = 0, m_pairMidY = 0;
	float m_velX = 0, m_velY = 0;
	float m_accPanX = 0, m_accPanY = 0, m_accZoom = 1.0f, m_accTurn = 0;
	Uint64 m_lastMove = 0;
};

Gestures *s_gestures = nullptr;

std::string leaf(const std::string &path)
{
	const size_t s = path.find_last_of("/\\");
	return s == std::string::npos ? path : path.substr(s + 1);
}

void runCommand(const FluxWB::Command &c, CWorldBuilderDoc &doc, WbView3d &view)
{
	std::string error;
	if ((c.verb == "open" || c.verb == "new") && s_gestures) {
		s_gestures->cancel(view);
	}
	if (c.verb == "open" || c.verb == "new") {
		FluxWB::scriptsCancel();
		// EA (Serialize / OnNewDocument): leave the current tool first. Its deactivate() still looks
		// at the old map's selected trigger area, which the load is about to delete.
		WbApp()->setActiveTool(nullptr);
	}
	if (c.verb == "open") {
		if (doc.open(c.arg.c_str(), &error)) {
			view.showDocument();
			FluxWB::postEvent("opened", leaf(c.arg));
			if (!doc.missingTextures().empty()) {
				FluxWB::postEvent("error", "missing-textures " + std::string(doc.missingTextures().front().str()));
			}
		} else {
			FluxWB::postEvent("error", error);
		}
		WbApp()->selectPointerTool();
		FluxWB::postEvent("tool", FluxWB::currentToolName());
	} else if (c.verb == "new") {
		int w = 100, h = 100, ht = 16, border = 30;
		sscanf(c.arg.c_str(), "%d %d %d %d", &w, &h, &ht, &border);
		if (doc.newMap(w, h, ht, border, &error)) {
			view.showDocument();
			FluxWB::postEvent("opened", "");
		} else {
			FluxWB::postEvent("error", error);
		}
		WbApp()->selectPointerTool();
		FluxWB::postEvent("tool", FluxWB::currentToolName());
	} else if (c.verb == "save") {
		if (doc.save(c.arg.c_str(), &error)) {
			FluxWB::postEvent("saved", c.arg);
		} else {
			FluxWB::postEvent("error", error);
		}
	} else if (c.verb == "starts") {
		// GeneralsX @bugfix Flux Build 07/11/2026 v1.4.1 round 3 Start positions before a save: "check"
		// answers "starts <problems>" ("" = fine), "fix" repairs them as one undo step (drawn as an
		// undo is, through the document's invalObject), then answers.
		if (c.arg == "fix" && !FluxWB::scriptsActive()) {
			if (s_gestures) s_gestures->cancel(view);
			doc.fixStartPositions();
			view.Invalidate();
		}
		FluxWB::postEvent("starts", doc.checkStartPositions());
	} else if (c.verb == "camera" && c.arg == "reset") {
		view.setDefaultCamera();
	} else if (c.verb == "tool") {
		if (s_gestures) s_gestures->cancel(view);
		if (c.arg == "camera") {
			WbApp()->setActiveTool(nullptr);
		} else if (!FluxWB::selectTool(c.arg)) {
			WbApp()->selectPointerTool();
		}
		const std::string name = FluxWB::currentToolName();
		FluxWB::postEvent("tool", name.empty() ? "camera" : name);
		view.Invalidate();
	} else if (c.verb == "set") {
		FluxWB::setOption(c.arg);
		view.Invalidate();
	} else if (c.verb == "momentum") {
		if (s_gestures) s_gestures->momentum = (c.arg != "0");
	} else if (c.verb == "undo" || c.verb == "redo") {
		// The mission editor holds a copy of the sides; an undo under it would be overwritten by its OK.
		if (FluxWB::scriptsActive()) return;
		if (s_gestures) s_gestures->cancel(view);
		if (c.verb == "undo") FluxWB::undo(); else FluxWB::redo();
		view.Invalidate();
	} else if (c.verb == "delete") {
		view.deleteSelection();
		view.Invalidate();
	} else if (c.verb == "mission") {
		// The mission editor (players, teams, scripts): EA's copy-then-commit (FluxWBScripts.cpp).
		std::vector<std::string> w;
		{
			std::istringstream in(c.arg);
			std::string t;
			while (in >> t) w.push_back(t);
		}
		const std::string op = w.empty() ? "" : w[0];
		if (op == "begin") {
			FluxWB::scriptsBegin();
			FluxWB::postEvent("mission", FluxWB::scriptsTree());
		} else if (op == "commit") {
			FluxWB::scriptsCommit();
			view.Invalidate();
			FluxWB::postEvent("missiondone", "");
		} else if (op == "cancel") {
			FluxWB::scriptsCancel();
			FluxWB::postEvent("missiondone", "");
		} else if (op == "tree") {
			FluxWB::postEvent("mission", FluxWB::scriptsTree());
		} else if (op == "get" && w.size() >= 4) {
			FluxWB::postEvent("script", c.arg.substr(4) + "\n" +
				FluxWB::scriptDetail(atoi(w[1].c_str()), atoi(w[2].c_str()), atoi(w[3].c_str())));
		} else if (op == "templates" && w.size() >= 2) {
			FluxWB::postEvent(w[1] == "a" ? "templates_a" : "templates_c", FluxWB::scriptTemplates(w[1] == "a"));
		} else if (op == "choices" && w.size() >= 2) {
			FluxWB::postEvent("choices", w[1] + "\n" + FluxWB::scriptChoices(atoi(w[1].c_str())));
		} else if (op == "factions") {
			FluxWB::postEvent("factions", FluxWB::scriptFactions());
		} else if (op == "edit") {
			const std::string result = FluxWB::scriptEdit(c.arg.size() > 5 ? c.arg.substr(5) : "");
			if (!result.empty()) FluxWB::postEvent("error", result);
			FluxWB::postEvent("mission", FluxWB::scriptsTree());
			FluxWB::postEvent("edited", "");
		}
	} else if (c.verb == "list" && c.arg == "objects") {
		FluxWB::postEvent("objects", FluxWB::objectList());
	} else if (c.verb == "list" && c.arg == "teams") {
		FluxWB::postEvent("teams", FluxWB::teamList());
	}
}

void sinkMessage(const char *text)
{
	FluxWB::postEvent("message", text ? text : "");
}

void sinkEvent(const char *kind, const char *text)
{
	FluxWB::postEvent(kind, text ? text : "");
}

} // namespace

int main(int argc, char *argv[])
{
	__argc = argc;
	__argv = argv;
	AndroidPaths paths = androidPaths();
	for (int i = 1; i + 1 < argc; ++i) {
		if (strcmp(argv[i], "-game") == 0) {
			paths.gameDir = argv[++i];
		} else if (strcmp(argv[i], "-user") == 0) {
			paths.userDataDir = argv[++i];
		}
	}
	openLog(paths);

	bool useVulkan = false;
	if (!createWindow(&useVulkan)) {
		FluxWB::postEvent("error", "no window");
		return 1;
	}
	fprintf(stderr, "[fluxwb] backend %s, game folder %s\n", useVulkan ? "Vulkan" : "GLES", paths.gameDir.c_str());

	FluxWB::StartOptions options;
	options.gameDir = paths.gameDir;
	options.userDataDir = paths.userDataDir;
	options.headless = false;
	std::string error;
	if (!FluxWB::startEngine(options, &error)) {
		FluxWB::postEvent("error", error);
		SDL_DestroyWindow(TheSDL3Window);
		TheSDL3Window = nullptr;
		return 1;
	}
	FluxWB::setMessageSink(sinkMessage);
	FluxWB::setEventSink(sinkEvent);

	int width = 0, height = 0;
	SDL_GetWindowSizeInPixels(TheSDL3Window, &width, &height);
	int exitCode = 0;
	{
		CWorldBuilderDoc doc;
		WbView3d view;
		if (!view.init(width, height)) {
			FluxWB::postEvent("error", "the 3D device could not start");
			exitCode = 1;
		} else {
			// The first map: the one asked for, else a new 100x100 map (EA's defaults).
			for (int i = 1; i < argc; ++i) {
				if (strcmp(argv[i], "-map") == 0 && i + 1 < argc) {
					FluxWB::postCommand("open", argv[++i]);
				} else if (strcmp(argv[i], "-new") == 0 && i + 4 < argc) {
					std::string a = std::string(argv[i + 1]) + " " + argv[i + 2] + " " + argv[i + 3] + " " + argv[i + 4];
					FluxWB::postCommand("new", a);
					i += 4;
				}
			}
			if (!doc.hasMap()) {
				FluxWB::Command first;
				if (!FluxWB::popCommand(&first)) {
					first.verb = "new";
					first.arg = "100 100 16 30";
				}
				if (first.verb == "quit") {
					FluxWB::postCommand("quit", "");
				} else {
					runCommand(first, doc, view);
				}
			}
			FluxWB::createTools();
			FluxWB::postEvent("ready", useVulkan ? "vulkan" : "gles");
			FluxWB::postEvent("tool", FluxWB::currentToolName());

			Gestures gestures;
			s_gestures = &gestures;
			for (int i = 1; i + 1 < argc; ++i) {
				if (strcmp(argv[i], "-momentum") == 0) gestures.momentum = strcmp(argv[i + 1], "0") != 0;
			}
			Uint64 lastFrame = SDL_GetTicks();
			std::string lastHistory;
			bool running = true;
			bool visible = true;
			while (running) {
				SDL_Event e;
				while (SDL_PollEvent(&e)) {
					switch (e.type) {
						case SDL_EVENT_QUIT:
						case SDL_EVENT_TERMINATING:
							running = false;
							break;
						case SDL_EVENT_WILL_ENTER_BACKGROUND:
						case SDL_EVENT_DID_ENTER_BACKGROUND:
							// The surface goes away in the background: draw nothing until it is back.
							// A finger lifted while away never reports it; end any stroke and forget them.
							gestures.cancel(view);
							visible = false;
							break;
						case SDL_EVENT_DID_ENTER_FOREGROUND:
							visible = true;
							view.Invalidate(false);
							break;
						case SDL_EVENT_WINDOW_PIXEL_SIZE_CHANGED:
						case SDL_EVENT_WINDOW_RESIZED:
							SDL_GetWindowSizeInPixels(TheSDL3Window, &width, &height);
							if (width > 0 && height > 0) {
								view.resize(width, height);
							}
							break;
						default:
							// Flux (review): no gestures while the surface is away -- they would add up and
							// be applied as one jump when it comes back.
							if (visible) gestures.onEvent(e, view, width, height);
							break;
					}
				}
				FluxWB::Command c;
				while (FluxWB::popCommand(&c)) {
					if (c.verb == "quit") {
						running = false;
						break;
					}
					runCommand(c, doc, view);
				}
				const Uint64 frameNow = SDL_GetTicks();
				const float frameDt = std::min(0.1f, (float)(frameNow - lastFrame) / 1000.0f);
				lastFrame = frameNow;
				if (visible) {
					gestures.tick(view);
					gestures.glide(view, frameDt);
				}
				// Undo/redo availability for the tool bar, whenever a stroke or command changed it.
				{
					const std::string history = std::string(doc.canUndo() ? "1" : "0") + (doc.canRedo() ? "1" : "0") +
						(doc.isModified() ? "1" : "0");
					if (history != lastHistory) {
						lastHistory = history;
						FluxWB::postEvent("history", history);
					}
				}
				// Draw only when something changed: an editor that idles at 60 fps drains the battery.
				if (running && visible && view.isDirty()) {
					view.redraw();
					SDL_Delay(4);
				} else {
					SDL_Delay(visible ? 12 : 50);
				}
			}
			s_gestures = nullptr;
			FluxWB::destroyTools(); // before the view: a tool's deactivate() talks to it
			view.shutdown();
		}
	}
	FluxWB::setMessageSink(nullptr);
	FluxWB::stopEngine();
	SDL_DestroyWindow(TheSDL3Window);
	TheSDL3Window = nullptr;
	return exitCode;
}
