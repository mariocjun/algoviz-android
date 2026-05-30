// JNI bridge for the native Compose visualizer (Kotlin `object VizBridge`).
//
// Replaces the ImGui/GLSurfaceView path: Compose owns all rendering + input;
// this exposes the C++ VizEngine (sorts/stepping) + AudioEngine (AAudio). The
// per-frame bar snapshot is written into a caller-owned direct ByteBuffer
// (zero-copy, no GC churn). Audio lifecycle follows the Activity (resume/pause).
#include "viz_engine.h"

#include "audio_engine.h"
#include "../algoviz/sort_registry.h"

#include <android/log.h>
#include <jni.h>

#include <memory>

namespace {
viz::AudioEngine g_audio;
std::unique_ptr<viz::VizEngine> g_engine;

viz::VizEngine* engine() {
    if (!g_engine) g_engine = std::make_unique<viz::VizEngine>(&g_audio);
    return g_engine.get();
}
}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) { engine(); }

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetMode(JNIEnv* /*env*/, jobject /*thiz*/, jint m) {
    engine()->set_mode(m);
}
JNIEXPORT jint JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeMode(JNIEnv* /*env*/, jobject /*thiz*/) {
    return engine()->mode();
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetAlgorithm(JNIEnv* /*env*/, jobject /*thiz*/, jint i) {
    engine()->set_algorithm(i);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetSize(JNIEnv* /*env*/, jobject /*thiz*/, jint n) {
    engine()->set_size(n);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetSpeed(JNIEnv* /*env*/, jobject /*thiz*/, jint s) {
    engine()->set_speed(s);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetPlaying(JNIEnv* /*env*/, jobject /*thiz*/, jboolean p) {
    engine()->set_playing(p != JNI_FALSE);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeTogglePlay(JNIEnv* /*env*/, jobject /*thiz*/) {
    engine()->toggle_play();
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeReset(JNIEnv* /*env*/, jobject /*thiz*/) {
    engine()->reset();
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeShuffle(JNIEnv* /*env*/, jobject /*thiz*/) {
    engine()->shuffle();
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeStep(JNIEnv* /*env*/, jobject /*thiz*/, jint dir) {
    engine()->step(dir);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetDrawMode(JNIEnv* /*env*/, jobject /*thiz*/, jboolean d) {
    engine()->set_draw_mode(d != JNI_FALSE);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativePaint(JNIEnv* /*env*/, jobject /*thiz*/, jint idx, jfloat v01) {
    engine()->paint(idx, v01);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetAutoLoop(JNIEnv* /*env*/, jobject /*thiz*/, jboolean b) {
    engine()->set_auto_loop(b != JNI_FALSE);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetSound(JNIEnv* /*env*/, jobject /*thiz*/, jboolean e) {
    engine()->set_sound(e != JNI_FALSE);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetVolume(JNIEnv* /*env*/, jobject /*thiz*/, jfloat v) {
    engine()->set_volume(v);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeSetScale(JNIEnv* /*env*/, jobject /*thiz*/, jint i) {
    engine()->set_scale(i);
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeUpdate(JNIEnv* /*env*/, jobject /*thiz*/, jfloat dt) {
    engine()->update(dt);
}
JNIEXPORT jint JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeFill(JNIEnv* env, jobject /*thiz*/, jobject buffer) {
    int* p = static_cast<int*>(env->GetDirectBufferAddress(buffer));
    const jlong bytes = env->GetDirectBufferCapacity(buffer);
    if (p == nullptr || bytes <= 0) return 0;
    return engine()->fill(p, static_cast<int>(bytes / static_cast<jlong>(sizeof(int))));
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeAudioResume(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_audio.start();
}
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeAudioPause(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_audio.stop();
}
JNIEXPORT jobjectArray JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeScaleNames(JNIEnv* env, jobject /*thiz*/) {
    const int n = viz::AudioEngine::scale_count();
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(n), str_cls, nullptr);
    for (int i = 0; i < n; ++i) {
        jstring s = env->NewStringUTF(viz::AudioEngine::scale_name(i));
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}
JNIEXPORT jobjectArray JNICALL
Java_com_mariocjun_algoviz_VizBridge_nativeAlgoNames(JNIEnv* env, jobject /*thiz*/) {
    const auto names = algoviz::all_sort_names();
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(names.size()), str_cls, nullptr);
    for (std::size_t i = 0; i < names.size(); ++i) {
        jstring s = env->NewStringUTF(names[i]);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}

}  // extern "C"
