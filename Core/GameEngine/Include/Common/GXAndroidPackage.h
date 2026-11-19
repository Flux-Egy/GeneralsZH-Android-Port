// GeneralsX @bugfix Flux Build 07/11/2026 The app's own package name, for the fallback paths into its
// private storage (/data/user/<id>/<package>/files/...), used where HOME or SDL's internal-storage path
// is not known yet. They used to name com.generalsx.flux.dev (and one com.generalsx.zerohour), which
// is wrong for any other application id -- v1.4.0-community is com.generalsx.flux.community. An
// Android app's main process is named after its package (/proc/self/cmdline, up to a ':' for a
// secondary process). Read once; this build's id stands in if /proc cannot be read.
#pragma once

#if defined(__ANDROID__)
#include <cstdio>
#include <cstring>

inline const char *gxAndroidPackageName()
{
	static char name[128] = { 0 };
	if (name[0] == '\0')
	{
		FILE *f = fopen("/proc/self/cmdline", "rb");
		if (f != nullptr)
		{
			const size_t n = fread(name, 1, sizeof(name) - 1, f);
			fclose(f);
			name[n] = '\0';
			char *colon = strchr(name, ':');
			if (colon != nullptr)
				*colon = '\0';
		}
		if (name[0] == '\0' || strchr(name, '.') == nullptr)
			snprintf(name, sizeof(name), "%s", "com.generalsx.flux.community");
	}
	return name;
}
#endif
