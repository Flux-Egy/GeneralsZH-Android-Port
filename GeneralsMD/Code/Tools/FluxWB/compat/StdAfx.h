// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: stands in for EA World Builder's StdAfx.h, which
// pulls in MFC (afxwin.h). The EA files compiled into libfluxwb.so (see ../CMakeLists.txt) use only a
// small part of MFC: CString, CPoint/CRect/CSize, AfxMessageBox and AfxGetMainWnd. That part is
// provided here, without any window: a message box is logged and reported to the Java UI through
// FluxWBMfc.cpp, and answers "no" / "cancel" where it asks a question, so nothing an EA routine does
// on a confirmation runs unless the user can be asked properly.
#pragma once

#define VC_EXTRALEAN

#include "Lib/BaseType.h"

#define _d3d9TYPES_H_

#include <windows.h>
#include <string>
#include <cstdarg>
#include <cstdio>
#include <cstring>

#include <Utility/stdio_adapter.h>

#include "Common/Debug.h"

#ifndef ASSERT
#define ASSERT(x) DEBUG_ASSERTCRASH((x), ("ASSERT(" #x ")"))
#endif

// Results and styles the engine's wnd_compat.h does not already define.
#ifndef IDOK
#define IDOK 1
#endif
#ifndef IDCANCEL
#define IDCANCEL 2
#endif
#ifndef MB_ICONEXCLAMATION
#define MB_ICONEXCLAMATION 0x00000030L
#endif
#ifndef MB_ICONWARNING
#define MB_ICONWARNING 0x00000030L
#endif
#ifndef MB_ICONINFORMATION
#define MB_ICONINFORMATION 0x00000040L
#endif
#ifndef MB_ICONQUESTION
#define MB_ICONQUESTION 0x00000020L
#endif
#ifndef MB_APPLMODAL
#define MB_APPLMODAL 0x00000000L
#endif

/** The string table of WorldBuilder.rc, for the IDS_ ids the compiled EA files use. */
const char *FluxWB_LoadString(UINT id);

class CString
{
public:
	CString() {}
	CString(const char *s) : m_s(s ? s : "") {}
	CString(const CString &o) = default;
	CString &operator=(const CString &o) = default;
	CString &operator=(const char *s) { m_s = s ? s : ""; return *this; }

	operator const char *() const { return m_s.c_str(); }
	const char *GetString() const { return m_s.c_str(); }
	int GetLength() const { return (int)m_s.size(); }
	bool IsEmpty() const { return m_s.empty(); }
	void Empty() { m_s.clear(); }

	CString &operator+=(const char *s) { if (s) m_s += s; return *this; }
	CString &operator+=(const CString &o) { m_s += o.m_s; return *this; }
	friend CString operator+(const CString &a, const char *b) { CString r(a); r += b; return r; }
	bool operator==(const char *s) const { return m_s == (s ? s : ""); }
	bool operator!=(const char *s) const { return !(*this == s); }

	BOOL LoadString(UINT id) { m_s = FluxWB_LoadString(id); return TRUE; }

	void Format(const char *fmt, ...)
	{
		va_list ap;
		va_start(ap, fmt);
		vformat(fmt, ap);
		va_end(ap);
	}
	void Format(UINT id, ...)
	{
		va_list ap;
		va_start(ap, id);
		vformat(FluxWB_LoadString(id), ap);
		va_end(ap);
	}

	int Replace(const char *from, const char *to)
	{
		if (from == nullptr || *from == 0) return 0;
		const size_t fromLen = strlen(from);
		const std::string with = to ? to : "";
		int count = 0;
		for (size_t pos = m_s.find(from); pos != std::string::npos; pos = m_s.find(from, pos + with.size())) {
			m_s.replace(pos, fromLen, with);
			++count;
		}
		return count;
	}

private:
	void vformat(const char *fmt, va_list ap)
	{
		va_list copy;
		va_copy(copy, ap);
		const int n = vsnprintf(nullptr, 0, fmt, copy);
		va_end(copy);
		if (n < 0) { m_s.clear(); return; }
		m_s.assign((size_t)n + 1, '\0');
		vsnprintf(&m_s[0], (size_t)n + 1, fmt, ap);
		m_s.resize((size_t)n);
	}

	std::string m_s;
};

class CPoint
{
public:
	CPoint() : x(0), y(0) {}
	CPoint(int ix, int iy) : x(ix), y(iy) {}
	bool operator==(const CPoint &o) const { return x == o.x && y == o.y; }
	bool operator!=(const CPoint &o) const { return !(*this == o); }
	int x;
	int y;
};

class CSize
{
public:
	CSize() : cx(0), cy(0) {}
	CSize(int w, int h) : cx(w), cy(h) {}
	int cx;
	int cy;
};

/** As in MFC, a CRect is a RECT (EA passes one where a RECT& is wanted). */
class CRect : public RECT
{
public:
	CRect() { left = top = right = bottom = 0; }
	CRect(int l, int t, int r, int b) { left = l; top = t; right = r; bottom = b; }
	int Width() const { return right - left; }
	int Height() const { return bottom - top; }
	BOOL PtInRect(CPoint p) const { return p.x >= left && p.x < right && p.y >= top && p.y < bottom; }
	void NormalizeRect()
	{
		if (left > right) { int t = left; left = right; right = t; }
		if (top > bottom) { int t = top; top = bottom; bottom = t; }
	}
};

/** EA's tools read Shift/Ctrl for "add to selection"; the tool bar's toggle stands in for them. */
#ifndef VK_SHIFT
#define VK_SHIFT 0x10
#endif
#ifndef VK_CONTROL
#define VK_CONTROL 0x11
#endif
extern bool g_fluxwbAddToSelection;
inline short GetAsyncKeyState(int vk)
{
	return (vk == VK_SHIFT && g_fluxwbAddToSelection) ? (short)0x8000 : (short)0;
}

/** No cursors on a touch screen: EA's tools load and set one each; both are no-ops here. */
typedef void *HCURSOR;
#ifndef MAKEINTRESOURCE
#define MAKEINTRESOURCE(i) ((const char *)(uintptr_t)(i))
#endif
inline BOOL DestroyCursor(HCURSOR cursor) { (void)cursor; return TRUE; }
class CWinApp
{
public:
	HCURSOR LoadCursor(const char *resource) { (void)resource; return nullptr; }
};
CWinApp *AfxGetApp();

/** There is no main window. SetFocus() after a message box (WHeightMapEdit) is a no-op. */
class CWnd
{
public:
	void SetFocus() {}
};
CWnd *AfxGetMainWnd();

/** Logged, and reported to the Java UI. Questions (MB_YESNO, MB_OKCANCEL...) answer IDNO/IDCANCEL. */
int AfxMessageBox(const char *text, UINT type = MB_OK, UINT helpId = 0);
int AfxMessageBox(UINT id, UINT type = MB_OK, UINT helpId = 0);
