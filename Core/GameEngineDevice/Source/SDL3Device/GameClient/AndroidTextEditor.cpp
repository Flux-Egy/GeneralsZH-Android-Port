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

/*
** AndroidTextEditor.cpp -- see AndroidTextEditor.h for why this exists.
*/

#include "SDL3Device/GameClient/AndroidTextEditor.h"

#if defined(__ANDROID__)

#include "Common/GXLogging.h"
#include "SDL3Device/GameClient/GXMouseInject.h"
#include "Common/GXDeviceStats.h"
#include "GameClient/Gadget.h"
#include "GameClient/GadgetTextEntry.h"
#include "GameClient/GameWindow.h"
#include "GameClient/GameWindowManager.h"

#include <SDL3/SDL_system.h>
#include <jni.h>

#include <mutex>
#include <string>
#include <vector>

namespace
{
	// Flags passed to GeneralsZHActivity.showTextEditor(); keep in step with the Java side.
	const int kFlagSecret = 1;
	const int kFlagDigitsOnly = 2;

	// VK_RETURN, the character GadgetTextEntryInput takes as "edit done".
	const WideChar kReturnCharacter = 0x0D;

	GameWindow* s_field = nullptr;
	// Raised for every open(); the bar echoes it, so anything it sends for an earlier field
	// (still in flight when focus moved) is recognised and dropped.
	int s_serial = 0;

	struct Pending
	{
		bool hasText = false;
		std::u16string text;
		bool done = false;
		bool submit = false;
		int serial = -1;
	};
	std::mutex s_pendingMutex;
	Pending s_pending;

	bool isEntryField(GameWindow* window)
	{
		return window != nullptr && BitIsSet(window->winGetStyle(), GWS_ENTRY_FIELD);
	}

	// Calls a void method of the activity; returns false when there is no JVM to call into.
	bool callActivity(const char* name, const char* signature, const jvalue* args)
	{
		JNIEnv* jni = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
		jobject activity = static_cast<jobject>(SDL_GetAndroidActivity());
		if (jni == nullptr || activity == nullptr)
			return false;
		jclass activityClass = jni->GetObjectClass(activity);
		jmethodID method = jni->GetMethodID(activityClass, name, signature);
		bool called = false;
		if (method != nullptr)
		{
			jni->CallVoidMethodA(activity, method, args);
			called = true;
		}
		if (jni->ExceptionCheck())
		{
			jni->ExceptionClear();
			called = false;
		}
		jni->DeleteLocalRef(activityClass);
		jni->DeleteLocalRef(activity);
		return called;
	}

	// Replace the field's text through its own character path, so the field's filters and
	// length limit apply and its owner hears GEM_UPDATE_TEXT exactly as for typed text.
	void applyText(GameWindow* field, const std::u16string& text)
	{
		UnicodeString wanted;
		for (char16_t unit : text)
		{
			// Control characters never belong in a one-line field (a pasted line break would
			// otherwise reach the gadget as VK_RETURN and end the edit), and the engine's
			// strings hold the Basic Multilingual Plane only.
			if (unit < 0x20 || (unit >= 0xD800 && unit <= 0xDFFF))
				continue;
			wanted.concat(static_cast<WideChar>(unit));
		}

		if (GadgetTextEntryGetText(field) == wanted)
			return;

		GadgetTextEntrySetText(field, UnicodeString::TheEmptyString);
		const Int length = wanted.getLength();
		for (Int i = 0; i < length; ++i)
			TheWindowManager->winSendInputMsg(field, GWM_IME_CHAR, static_cast<WindowMsgData>(wanted.getCharAt(i)), 0);
		if (length == 0)
			TheWindowManager->winSendSystemMsg(field->winGetOwner(), GEM_UPDATE_TEXT, (WindowMsgData)field, 0);
	}

	void storePending(JNIEnv* env, jstring text, jint serial, bool done, bool submit)
	{
		std::u16string copy;
		if (text != nullptr)
		{
			const jsize length = env->GetStringLength(text);
			const jchar* chars = env->GetStringChars(text, nullptr);
			if (chars != nullptr)
			{
				copy.assign(reinterpret_cast<const char16_t*>(chars), static_cast<size_t>(length));
				env->ReleaseStringChars(text, chars);
			}
		}

		std::lock_guard<std::mutex> lock(s_pendingMutex);
		if (s_pending.serial != serial)
			s_pending = Pending();
		s_pending.serial = serial;
		if (text != nullptr)
		{
			s_pending.hasText = true;
			s_pending.text = copy;
		}
		if (done)
		{
			s_pending.done = true;
			s_pending.submit = submit;
		}
	}
}

bool AndroidTextEditor::open(GameWindow* field)
{
	if (!isEntryField(field) || TheWindowManager == nullptr)
		return false;

	const EntryData* entry = static_cast<const EntryData*>(field->winGetUserData());
	const UnicodeString current = GadgetTextEntryGetText(field);

	int flags = 0;
	int maxLength = 0;
	if (entry != nullptr)
	{
		if (entry->secretText)
			flags |= kFlagSecret;
		if (entry->numericalOnly)
			flags |= kFlagDigitsOnly;
		// GadgetTextEntryInput accepts a character only while charPos < maxTextLen - 1.
		maxLength = entry->maxTextLen > 1 ? entry->maxTextLen - 1 : 0;
	}

	JNIEnv* jni = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
	if (jni == nullptr)
		return false;
	std::vector<jchar> units;
	units.reserve(current.getLength());
	for (Int i = 0; i < current.getLength(); ++i)
		units.push_back(static_cast<jchar>(current.getCharAt(i)));
	jstring initial = jni->NewString(units.data(), static_cast<jsize>(units.size()));
	if (initial == nullptr)
		return false;

	++s_serial;
	jvalue args[4];
	args[0].l = initial;
	args[1].i = maxLength;
	args[2].i = flags;
	args[3].i = s_serial;
	const bool shown = callActivity("showTextEditor", "(Ljava/lang/String;III)V", args);
	if (shown)
		s_field = field;
	jni->DeleteLocalRef(initial);
	return shown;
}

void AndroidTextEditor::close()
{
	if (s_field == nullptr)
		return;
	s_field = nullptr;
	++s_serial;
	callActivity("hideTextEditor", "()V", nullptr);
}

GameWindow* AndroidTextEditor::field()
{
	return s_field;
}

void AndroidTextEditor::pump()
{
	Pending pending;
	{
		std::lock_guard<std::mutex> lock(s_pendingMutex);
		pending = s_pending;
		s_pending = Pending();
	}
	if (pending.serial != s_serial || s_field == nullptr || TheWindowManager == nullptr)
		return;

	GameWindow* field = s_field;
	if (pending.done)
		s_field = nullptr; // the bar has hidden itself
	if (field == nullptr || !isEntryField(field))
		return;

	// GeneralsX @bugfix Flux Build 22/10/2026 In-game chat: when soft keyboard closes, focus
	// might have momentarily shifted; re-asserting focus on the target entry field guarantees
	// that both the committed text and the VK_RETURN character (GEM_EDIT_DONE) reach the gadget
	// and submit the chat message to the network.
	TheWindowManager->winSetFocus(field);

	if (pending.hasText)
		applyText(field, pending.text);
	if (pending.done && pending.submit)
		TheWindowManager->winSendInputMsg(field, GWM_IME_CHAR, static_cast<WindowMsgData>(kReturnCharacter), 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorChanged(JNIEnv* env, jclass, jstring text, jint serial)
{
	storePending(env, text, serial, false, false);
}

extern "C" JNIEXPORT void JNICALL
Java_com_generalsx_zerohour_GeneralsZHActivity_nativeTextEditorDone(JNIEnv* env, jclass, jstring text, jboolean submit, jint serial)
{
	storePending(env, text, serial, true, submit == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_generalsx_zerohour_SetupActivity_nativeSetLogLevel(JNIEnv*, jclass, jint level)
{
	GXSetLogLevel(level);
}

// GeneralsX @feature Flux Build 23/10/2026 Floating LMB/RMB buttons. Declared in SDLActivity
// (the class that already owns the key/mouse JNI bridge) and implemented here because this
// file is the Android-only JNI translation unit of the engine library.
extern "C" JNIEXPORT void JNICALL
Java_org_libsdl_app_SDLActivity_nativeInjectMouseButton(JNIEnv*, jclass, jint button, jboolean down)
{
	GXInjectFloatingMouseButton(static_cast<int>(button), down == JNI_TRUE);
}

// GeneralsX @feature Flux Build 06/11/2026 The "i" Info button (GXMouseInject.h GXSetUnitInfoMode).
extern "C" JNIEXPORT void JNICALL
Java_com_generalsx_zerohour_GeneralsZHActivity_nativeSetUnitInfoMode(JNIEnv*, jclass, jboolean on)
{
	GXSetUnitInfoMode(on == JNI_TRUE);
}

// GeneralsX @feature Flux Build 06/11/2026 The in-game HUD (InGameHud.java): {fps, hottest zone in C
// or -1}. Plain reads of atomics (GXDeviceStats.h); the HUD polls once a second.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_generalsx_zerohour_GeneralsZHActivity_nativeGetHudStats(JNIEnv *env, jclass)
{
	const jfloat values[2] = { GXDeviceFps(), (jfloat)GXDeviceHottestTempC() };
	jfloatArray out = env->NewFloatArray(2);
	if (out != nullptr)
		env->SetFloatArrayRegion(out, 0, 2, values);
	return out;
}

#else

bool AndroidTextEditor::open(GameWindow*) { return false; }
void AndroidTextEditor::close() {}
GameWindow* AndroidTextEditor::field() { return nullptr; }
void AndroidTextEditor::pump() {}

#endif
