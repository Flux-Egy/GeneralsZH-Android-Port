/*
**	Command & Conquer Generals Zero Hour(tm) - Flux Build (GeneralsX Android port)
**
**	This program is free software: you can redistribute it and/or modify it under the terms of the
**	GNU General Public License as published by the Free Software Foundation, either version 3 of the
**	License, or (at your option) any later version.
*/

// GeneralsX @performance Flux Build 17/11/2026 v1.4.5 12210 CPU clock hints (owner: Snapdragon 8+ Gen 1). Android's
// Performance Hint API (ADPF, Android 13+, libandroid's APerformanceHint_*): one session for the engine's thread and
// the GLES render thread, told the frame-time target (from the frame limit) and, every frame, how long the engine's
// thread actually worked (FramePacer::update, before its limiter wait). The system then runs those threads on cores
// and clocks that meet the target instead of guessing -- on the 8+ Gen 1 the engine thread sat on mid cores at ~2.1
// GHz half the time. Looked up at run time (minSdk 28): no API, no hints, nothing else changes. Client-side only:
// timing reports to the OS, never game logic, the CRC or the network. gx_cpu_hints_off.txt in the game folder turns
// it off (Diagnostics). Engine thread only (FramePacer runs there).

#pragma once

#if defined(__ANDROID__)

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <unistd.h>

extern "C" int d3d8gles_render_thread_tid() __attribute__((weak));

namespace GXCpuHint
{
	typedef void *(*GetManagerFn)();
	typedef void *(*CreateSessionFn)(void *manager, const int32_t *tids, size_t count, int64_t targetNanos);
	typedef int (*UpdateTargetFn)(void *session, int64_t targetNanos);
	typedef int (*ReportActualFn)(void *session, int64_t actualNanos);
	typedef int (*SetThreadsFn)(void *session, const int32_t *tids, size_t count);

	struct State
	{
		int on = -1; // -1 not set up yet, 0 off / unavailable, 1 on
		GetManagerFn getManager = nullptr;
		CreateSessionFn createSession = nullptr;
		UpdateTargetFn updateTarget = nullptr;
		ReportActualFn reportActual = nullptr;
		SetThreadsFn setThreads = nullptr; // Android 14+
		void *session = nullptr;
		int64_t target = 0;
		int32_t tids[2] = { 0, 0 };
		size_t tidCount = 0;
		unsigned frames = 0;
	};

	inline State &state()
	{
		static State s;
		return s;
	}

	inline int64_t targetFor(unsigned maxFps)
	{
		const unsigned fps = (maxFps == 0 || maxFps > 1000) ? 120u : maxFps;
		return (int64_t)(1000000000LL / fps);
	}

	inline void setUp(State &s)
	{
		FILE *f = fopen("gx_cpu_hints_off.txt", "r");
		if (f != nullptr)
		{
			fclose(f);
			s.on = 0;
			fprintf(stderr, "[GX-ADPF] CPU clock hints OFF (gx_cpu_hints_off.txt)\n");
			return;
		}
		void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
		if (lib != nullptr)
		{
			s.getManager = (GetManagerFn)dlsym(lib, "APerformanceHint_getManager");
			s.createSession = (CreateSessionFn)dlsym(lib, "APerformanceHint_createSession");
			s.updateTarget = (UpdateTargetFn)dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
			s.reportActual = (ReportActualFn)dlsym(lib, "APerformanceHint_reportActualWorkDuration");
			s.setThreads = (SetThreadsFn)dlsym(lib, "APerformanceHint_setThreads");
		}
		if (s.getManager == nullptr || s.createSession == nullptr || s.updateTarget == nullptr || s.reportActual == nullptr)
		{
			s.on = 0;
			fprintf(stderr, "[GX-ADPF] CPU clock hints unavailable (Android 13+ only)\n");
			return;
		}
		s.on = 1;
	}

	inline int renderTid()
	{
		return d3d8gles_render_thread_tid ? d3d8gles_render_thread_tid() : 0;
	}

	inline void createSession(State &s, unsigned maxFps)
	{
		void *manager = s.getManager();
		s.tids[0] = (int32_t)gettid();
		s.tidCount = 1;
		const int rt = renderTid();
		if (rt > 0 && rt != s.tids[0])
			s.tids[s.tidCount++] = (int32_t)rt;
		s.target = targetFor(maxFps);
		s.session = manager != nullptr ? s.createSession(manager, s.tids, s.tidCount, s.target) : nullptr;
		if (s.session == nullptr)
		{
			s.on = 0;
			fprintf(stderr, "[GX-ADPF] CPU clock hints refused by the system (no session)\n");
			return;
		}
		fprintf(stderr, "[GX-ADPF] CPU clock hints on: engine thread %d%s, target %.2f ms\n", (int)s.tids[0],
			s.tidCount > 1 ? " + render thread" : "", (double)s.target / 1.0e6);
	}

	/** Once a frame, on the engine's thread, before the frame limiter waits: this frame's work and the limit. */
	inline void frame(std::chrono::steady_clock::duration work, unsigned maxFps)
	{
		State &s = state();
		if (s.on == 0)
			return;
		if (s.on < 0)
		{
			setUp(s);
			if (s.on == 0)
				return;
		}
		++s.frames;
		if (s.session == nullptr)
		{
			// The render thread starts with the GL context; wait for it a little so it is in the session.
			if (renderTid() <= 0 && s.frames < 120)
				return;
			createSession(s, maxFps);
			if (s.session == nullptr)
				return;
		}
		else if (s.tidCount == 1 && s.setThreads != nullptr && (s.frames % 600) == 0)
		{
			const int rt = renderTid();
			if (rt > 0 && rt != s.tids[0])
			{
				s.tids[1] = (int32_t)rt;
				if (s.setThreads(s.session, s.tids, 2) == 0)
				{
					s.tidCount = 2;
					fprintf(stderr, "[GX-ADPF] render thread %d added\n", rt);
				}
			}
		}
		const int64_t target = targetFor(maxFps);
		if (target != s.target)
		{
			s.target = target;
			s.updateTarget(s.session, target);
		}
		int64_t actual = (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(work).count();
		if (actual <= 0)
			return;
		// Review 12210: a frame far over the target is a load screen or the first frame back from the background, not
		// the game's pace -- reporting it would make Android raise the clocks for nothing.
		if (actual > 4 * s.target)
			return;
		s.reportActual(s.session, actual);
	}
}

#endif
