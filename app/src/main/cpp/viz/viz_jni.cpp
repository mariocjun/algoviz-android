// JNI bridge for the ImGui sort visualizer (VizActivity).
//
// Architecture note (why this file exists instead of imgui_impl_android):
// ImGui's stock Android backend reads input from android_native_app_glue — but
// this template has no NativeActivity (see CLAUDE.md). So ImGui runs inside a
// Kotlin-owned GLSurfaceView with the GLES3 RENDERER backend
// (imgui_impl_opengl3) and a minimal CUSTOM platform layer fed from JNI here.
//
// Threads:
//   - GL thread (GLSurfaceView render thread): surfaceCreated/Changed/drawFrame
//     /onTouch — owns the ImGui context and the VizApp (g_app). Touch is hopped
//     here via GLSurfaceView.queueEvent so the context stays single-threaded.
//   - UI thread: audioResume/audioPause — drive the global AudioEngine, whose
//     lifecycle follows the Activity. The engine is built to take note()
//     (GL thread, lock-free) concurrently with start()/stop() (UI thread).

#include "audio_engine.h"
#include "viz_app.h"

#include "imgui.h"
#include "backends/imgui_impl_opengl3.h"

#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <memory>

#define VIZ_LOG_TAG "AlgoVizGL"
#define VIZLOGI(...) __android_log_print(ANDROID_LOG_INFO, VIZ_LOG_TAG, __VA_ARGS__)
#define VIZLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VIZ_LOG_TAG, __VA_ARGS__)

namespace {

viz::AudioEngine g_audio;                 // lifecycle driven by Activity resume/pause
std::unique_ptr<viz::VizApp> g_app;       // owned + used on the GL thread

bool g_context_created = false;
bool g_gl_inited = false;
bool g_style_scaled = false;
float g_scale = 2.5f;
std::chrono::steady_clock::time_point g_last_frame;
bool g_have_last_frame = false;

float frame_dt_seconds() {
    const auto now = std::chrono::steady_clock::now();
    if (!g_have_last_frame) {
        g_have_last_frame = true;
        g_last_frame = now;
        return 1.0f / 60.0f;
    }
    const std::chrono::duration<float> d = now - g_last_frame;
    g_last_frame = now;
    const float dt = d.count();
    if (dt <= 0.0f || dt > 0.25f) return 1.0f / 60.0f;
    return dt;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeSurfaceCreated(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_context_created) {
        IMGUI_CHECKVERSION();
        ImGui::CreateContext();
        ImGuiIO& io = ImGui::GetIO();
        io.IniFilename = nullptr;
        io.LogFilename = nullptr;
        ImGui::StyleColorsDark();
        g_app = std::make_unique<viz::VizApp>(&g_audio);
        g_context_created = true;
        VIZLOGI("ImGui context + VizApp created");
    }
    // (Re)create GL device objects for the current (possibly new) GL context.
    if (g_gl_inited) {
        ImGui_ImplOpenGL3_Shutdown();
        g_gl_inited = false;
    }
    if (ImGui_ImplOpenGL3_Init("#version 300 es")) {
        g_gl_inited = true;
        VIZLOGI("ImGui_ImplOpenGL3 initialised (GLES3)");
    } else {
        VIZLOGE("ImGui_ImplOpenGL3_Init failed");
    }
}

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeSurfaceChanged(
    JNIEnv* /*env*/, jobject /*thiz*/, jint width, jint height, jfloat density) {
    if (!g_context_created) return;
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));

    g_scale = density;
    if (g_scale < 1.0f) g_scale = 1.0f;
    if (g_scale > 4.0f) g_scale = 4.0f;
    if (!g_style_scaled) {
        ImGui::GetStyle().ScaleAllSizes(g_scale);
        g_style_scaled = true;
    }
    io.FontGlobalScale = g_scale;
    glViewport(0, 0, width, height);
    VIZLOGI("surfaceChanged %dx%d density=%.2f", width, height, static_cast<double>(density));
}

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeDrawFrame(JNIEnv* /*env*/, jobject /*thiz*/) {
    if (!g_context_created || !g_gl_inited) return;
    ImGuiIO& io = ImGui::GetIO();
    if (io.DisplaySize.x <= 0.0f || io.DisplaySize.y <= 0.0f) return;

    io.DeltaTime = frame_dt_seconds();

    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    if (g_app) g_app->draw();
    ImGui::Render();

    glViewport(0, 0, static_cast<int>(io.DisplaySize.x), static_cast<int>(io.DisplaySize.y));
    glClearColor(0.07f, 0.07f, 0.09f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

// action: 0 = down, 1 = move, 2 = up.
JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeOnTouch(
    JNIEnv* /*env*/, jobject /*thiz*/, jint action, jfloat x, jfloat y) {
    if (!g_context_created) return;
    ImGuiIO& io = ImGui::GetIO();
    io.AddMousePosEvent(x, y);
    if (action == 0) {
        io.AddMouseButtonEvent(0, true);
    } else if (action == 2) {
        io.AddMouseButtonEvent(0, false);
    }
}

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeAudioResume(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_audio.start();
}

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeAudioPause(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_audio.stop();
}

JNIEXPORT void JNICALL
Java_com_mariocjun_algoviz_VizActivity_nativeOnDestroy(JNIEnv* /*env*/, jobject /*thiz*/) {
    g_audio.stop();
    if (g_gl_inited) {
        ImGui_ImplOpenGL3_Shutdown();
        g_gl_inited = false;
    }
    if (g_context_created) {
        ImGui::DestroyContext();
        g_context_created = false;
        g_style_scaled = false;
        g_have_last_frame = false;
        g_app.reset();
        VIZLOGI("ImGui context destroyed");
    }
}

} // extern "C"
