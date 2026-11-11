// Copyright (C) 2026 Flux Build Team. GPLv3 with additional EA terms.
// GeneralsX @performance Flux Build 09/11/2026 Split timers for the frame phase before drawing ("preRTT" in
// [GX-PERF-DISPLAY]): community logs showed 30-66 ms there on Borderlands Desertion after the terrain
// relighting fix, and the phase holds the camera/terrain update and the particle update. Accumulated by
// W3DView::updateTerrain and HeightMapRenderObjClass::updateCenter/updateBlock, printed and reset by
// W3DDisplay::draw as [GX-PERF-PRERTT]. Engine thread only.
#pragma once

#include <cstdint>
#include "GXTrace.h"

namespace GXPreRtt
{
	// Only while performance tracing is on (gx_perf.txt): no clock reads or counters otherwise (review).
	inline bool enabled() { return GXTrace::isPerfEnabled(); }
	inline double &terrainUs() { static double v = 0.0; return v; }   // W3DView::updateTerrain, whole
	inline double &baseUs() { static double v = 0.0; return v; }      // base updateCenter: trees, roads, bridges
	inline double &recenterUs() { static double v = 0.0; return v; }  // HeightMap re-centering after that
	inline int64_t &cells() { static int64_t v = 0; return v; }       // terrain cells rewritten by updateBlock
	inline int64_t &blocks() { static int64_t v = 0; return v; }      // updateBlock calls
}
