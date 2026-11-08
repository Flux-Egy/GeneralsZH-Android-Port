// Copyright (C) 2026 Flux Build Team.
// This file is part of Flux Build, a derivative work of MYSOREZ/GeneralsZH-Android-Port.
// Licensed under GPLv3 with additional EA terms.

// GeneralsX @feature Flux Build 06/11/2026 PLAN-032: see FluxWBBridge.h.

#include "FluxWBBridge.h"

#include <android/log.h>
#include <jni.h>

#include <deque>
#include <mutex>

namespace {

std::mutex s_lock;
std::deque<FluxWB::Command> s_commands;
JavaVM *s_vm = nullptr;
jclass s_activityClass = nullptr;   // global ref, taken in JNI_OnLoad (the app's class loader)
jmethodID s_onNativeEvent = nullptr; // static void onNativeEvent(String kind, String text)

const size_t kMaxQueued = 512;

std::string toString(JNIEnv *env, jstring s)
{
	if (s == nullptr) {
		return std::string();
	}
	const char *chars = env->GetStringUTFChars(s, nullptr);
	std::string out = chars ? chars : "";
	if (chars) {
		env->ReleaseStringUTFChars(s, chars);
	}
	return out;
}

} // namespace

namespace FluxWB {

void postCommand(const std::string &verb, const std::string &arg)
{
	std::lock_guard<std::mutex> guard(s_lock);
	if (s_commands.size() >= kMaxQueued) {
		// A UI that floods the queue loses commands, never memory -- and says so.
		__android_log_print(ANDROID_LOG_WARN, "FluxWB", "command queue full, dropped: %s", verb.c_str());
		return;
	}
	s_commands.push_back(Command{ verb, arg });
}

bool popCommand(Command *out)
{
	std::lock_guard<std::mutex> guard(s_lock);
	if (s_commands.empty()) {
		return false;
	}
	*out = s_commands.front();
	s_commands.pop_front();
	return true;
}

void postEvent(const char *kind, const std::string &text)
{
	__android_log_print(ANDROID_LOG_INFO, "FluxWB", "%s: %s", kind, text.c_str());
	if (s_vm == nullptr || s_activityClass == nullptr || s_onNativeEvent == nullptr) {
		return;
	}
	JNIEnv *env = nullptr;
	bool attached = false;
	if (s_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		if (s_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
			return;
		}
		attached = true;
	}
	// NewStringUTF wants modified UTF-8; map names are ASCII in practice, and anything else is
	// replaced rather than risking an abort in the VM.
	std::string safe;
	safe.reserve(text.size());
	for (unsigned char c : text) {
		safe += (c < 0x80) ? (char)c : '?';
	}
	jstring jKind = env->NewStringUTF(kind);
	jstring jText = env->NewStringUTF(safe.c_str());
	env->CallStaticVoidMethod(s_activityClass, s_onNativeEvent, jKind, jText);
	if (env->ExceptionCheck()) {
		env->ExceptionClear();
	}
	env->DeleteLocalRef(jKind);
	env->DeleteLocalRef(jText);
	if (attached) {
		s_vm->DetachCurrentThread();
	}
}

} // namespace FluxWB

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
	(void)reserved;
	s_vm = vm;
	JNIEnv *env = nullptr;
	if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		return JNI_VERSION_1_6;
	}
	jclass local = env->FindClass("com/generalsx/zerohour/WorldBuilderActivity");
	if (local != nullptr) {
		s_activityClass = static_cast<jclass>(env->NewGlobalRef(local));
		env->DeleteLocalRef(local);
		s_onNativeEvent = env->GetStaticMethodID(s_activityClass, "onNativeEvent",
			"(Ljava/lang/String;Ljava/lang/String;)V");
	}
	if (env->ExceptionCheck()) {
		env->ExceptionClear();
		s_onNativeEvent = nullptr;
	}
	return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_generalsx_zerohour_WorldBuilderActivity_nativeCommand(JNIEnv *env, jclass clazz, jstring verb, jstring arg)
{
	(void)clazz;
	FluxWB::postCommand(toString(env, verb), toString(env, arg));
}
