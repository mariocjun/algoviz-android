// JNI bridge for the ImGui sort visualizer (VizActivity).
//
// Architecture note (why this file exists instead of imgui_impl_android):
// ImGui's stock Android backend (imgui_impl_android.cpp) reads input from the
// android_native_app_glue input queue — but this template deliberately has no
// NativeActivity / native_app_glue (see CLAUDE.md). So we run ImGui inside a
// Kotlin-owned GLSurfaceView and provide our OWN minimal platform layer here:
//   - the GLES3 RENDERER backend (imgui_impl_opengl3) is reused as-is;
//   - the PLATFORM side (display size, delta time, mouse/touch input) is fed
//     from JNI calls that VizActivity issues on the GL thread.
//
// All ImGui calls happen on the GLSurfaceView render thread. Touch events
// originate on the UI thread but VizActivity forwards them via
// GLSurfaceView.queueEvent(), so nativeOnTouch also runs on the GL thread —
// no locking needed around the single global ImGui context.

#include "sort_visualizer.h"

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

std::unique_ptr<viz::SortVisualizer> g_viz;
bool g_context_created = false;   // ImGui context exists (survives GL ctx loss)
bool g_gl_inited = false;         // imgui_impl_opengl3 device objects valid
bool g_style_scaled = false;      // ScaleAllSizes applied once
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
    // Clamp: a long pause (app backgrounded) shouldn't produce a huge dt.
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
        io.IniFilename = nullptr;   // no imgui.ini on Android (no writable cwd)
        io.LogFilename = nullptr;
        ImGui::StyleColorsDark();
        g_viz = std::make_unique<viz::SortVisualizer>();
        g_context_created = true;
        VIZLOGI("ImGui context created");
    }
    // (Re)create GL device objects for the current (possibly new) GL context.
    // GLSurfaceView calls onSurfaceCreated again after a context loss
    // (app resume), at which point the previous GL objects are invalid.
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
        ImGui::GetStyle().ScaleAllSizes(g_scale);   // one-shot: widget metrics
        g_style_scaled = true;
    }
    io.FontGlobalScale = g_scale;                    // crisp-ish default font on hi-DPI
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
    if (g_viz) g_viz->draw();
    ImGui::Render();

    glViewport(0, 0, static_cast<int>(io.DisplaySize.x), static_cast<int>(io.DisplaySize.y));
    glClearColor(0.07f, 0.07f, 0.09f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
}

// action: 0 = down, 1 = move, 2 = up (mirrors VizActivity's mapping of
// MotionEvent ACTION_DOWN/MOVE/UP). Touch is presented to ImGui as the left
// mouse button.
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
Java_com_mariocjun_algoviz_VizActivity_nativeOnDestroy(JNIEnv* /*env*/, jobject /*thiz*/) {
    // Best-effort teardown. The GL context may already be gone here (Activity
    // destroyed), so only shut down the backend if we believe it's valid.
    if (g_gl_inited) {
        ImGui_ImplOpenGL3_Shutdown();
        g_gl_inited = false;
    }
    if (g_context_created) {
        ImGui::DestroyContext();
        g_context_created = false;
        g_style_scaled = false;
        g_have_last_frame = false;
        g_viz.reset();
        VIZLOGI("ImGui context destroyed");
    }
}

} // extern "C"
