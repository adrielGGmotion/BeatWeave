/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "include/beatweave_rubberband.h"
#include <jni.h>
#include <cstdint>
#include <vector>

static bw_rb_session *session(jlong h) { return reinterpret_cast<bw_rb_session *>(static_cast<intptr_t>(h)); }
static void fail(JNIEnv *env, const char *message = nullptr) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message ? message : bw_rb_last_error());
}
extern "C" {
JNIEXPORT jlong JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_create
  (JNIEnv *env, jobject, jint rate, jlong source, jlong output) {
    auto *s = bw_rb_create(rate, 2, source, output);
    if (!s) fail(env);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}
JNIEXPORT void JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_destroy
  (JNIEnv *, jobject, jlong h) { bw_rb_destroy(session(h)); }
JNIEXPORT jint JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_engineVersion
  (JNIEnv *env, jobject, jlong h) {
    int result = bw_rb_engine_version(session(h)); if (result < 0) fail(env); return result;
}
JNIEXPORT void JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_setKeyFrames
  (JNIEnv *env, jobject, jlong h, jlongArray source, jlongArray output) {
    if (!source || !output || env->GetArrayLength(source) != env->GetArrayLength(output)) { fail(env, "Mismatched keyframe arrays"); return; }
    const int n = env->GetArrayLength(source);
    // jlong and int64_t are not guaranteed to have the same C++ typedef.
    jlong *a = env->GetLongArrayElements(source, nullptr);
    if (!a) return;
    jlong *b = env->GetLongArrayElements(output, nullptr);
    if (!b) { env->ReleaseLongArrayElements(source, a, JNI_ABORT); return; }
    int result = -2;
    try {
        std::vector<int64_t> aa(a, a+n), bb(b, b+n);
        result = bw_rb_set_keyframes(session(h), aa.data(), bb.data(), n);
    } catch (...) { fail(env, "Could not allocate keyframe map"); }
    env->ReleaseLongArrayElements(source, a, JNI_ABORT);
    env->ReleaseLongArrayElements(output, b, JNI_ABORT);
    if (result < 0) fail(env);
}
JNIEXPORT void JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_study
  (JNIEnv *env, jobject, jlong h, jfloatArray audio, jint n, jboolean finalBlock) {
    if (!audio || n < 0 || n > 4096 || env->GetArrayLength(audio) < n*2) { fail(env, "Invalid study buffer"); return; }
    jfloat *a = env->GetFloatArrayElements(audio, nullptr); if (!a) return;
    int result = bw_rb_study(session(h), a, n, finalBlock);
    env->ReleaseFloatArrayElements(audio, a, JNI_ABORT);
    if (result < 0) fail(env);
}
JNIEXPORT void JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_process
  (JNIEnv *env, jobject, jlong h, jfloatArray audio, jint n, jboolean finalBlock) {
    if (!audio || n < 0 || n > 4096 || env->GetArrayLength(audio) < n*2) { fail(env, "Invalid process buffer"); return; }
    jfloat *a = env->GetFloatArrayElements(audio, nullptr); if (!a) return;
    int result = bw_rb_process(session(h), a, n, finalBlock);
    env->ReleaseFloatArrayElements(audio, a, JNI_ABORT);
    if (result < 0) fail(env);
}
JNIEXPORT jint JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_available
  (JNIEnv *env, jobject, jlong h) { int n=bw_rb_available(session(h)); if (n < -1) fail(env); return n; }
JNIEXPORT jint JNICALL Java_org_metrolist_beatweave_rubberband_RubberBandBridge_retrieve
  (JNIEnv *env, jobject, jlong h, jfloatArray audio, jint n) {
    if (!audio || n < 0 || n > 4096 || env->GetArrayLength(audio) < n*2) { fail(env, "Invalid retrieve buffer"); return 0; }
    jfloat *a = env->GetFloatArrayElements(audio, nullptr); if (!a) return 0;
    int result = bw_rb_retrieve(session(h), a, n);
    env->ReleaseFloatArrayElements(audio, a, 0);
    if (result < 0) fail(env);
    return result;
}
}
