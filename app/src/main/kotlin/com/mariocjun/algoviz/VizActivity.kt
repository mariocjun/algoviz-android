// VizActivity — the Phase 2 ImGui sort visualizer.
//
// A Kotlin-owned GLSurfaceView (which manages EGL + the render thread for us,
// so the template stays free of NativeActivity / native_app_glue) drives the
// native ImGui layer in libalgoviz.so via JNI. The Renderer callbacks run on
// the GL thread; touch events arrive on the UI thread and are hopped onto the
// GL thread with queueEvent() so the single global ImGui context is only ever
// touched from one thread.
//
// JNI symbols: Java_com_mariocjun_algoviz_VizActivity_native* (viz/viz_jni.cpp).

package com.mariocjun.algoviz

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VizActivity : AppCompatActivity() {

    companion object {
        init { System.loadLibrary("algoviz") }
    }

    private external fun nativeSurfaceCreated()
    private external fun nativeSurfaceChanged(width: Int, height: Int, density: Float)
    private external fun nativeDrawFrame()
    private external fun nativeOnTouch(action: Int, x: Float, y: Float)
    private external fun nativeOnDestroy()

    private lateinit var glView: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        glView = object : GLSurfaceView(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                val action = when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> 0
                    MotionEvent.ACTION_MOVE -> 1
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
                    else -> -1
                }
                if (action < 0) return true
                val x = event.x
                val y = event.y
                // Hop to the GL thread: ImGui IO must be touched there only.
                queueEvent { nativeOnTouch(action, x, y) }
                return true
            }
        }.apply {
            setEGLContextClientVersion(3)
            // Keep the GL context across pause where the device allows it, so
            // we don't churn ImGui's GL objects on every resume.
            preserveEGLContextOnPause = true
            setRenderer(VizRenderer(resources.displayMetrics.density))
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        // Tear down the ImGui context on the GL thread while it still exists.
        if (this::glView.isInitialized) {
            glView.queueEvent { nativeOnDestroy() }
        }
        super.onDestroy()
    }

    private inner class VizRenderer(private val density: Float) : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            nativeSurfaceCreated()
        }
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            nativeSurfaceChanged(width, height, density)
        }
        override fun onDrawFrame(gl: GL10?) {
            nativeDrawFrame()
        }
    }
}
