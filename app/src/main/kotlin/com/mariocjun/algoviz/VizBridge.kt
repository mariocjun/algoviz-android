// VizBridge — JNI surface for the native Compose visualizer.
//
// Compose owns all rendering + input; the C++ VizEngine (sorts/stepping) and
// AudioEngine (AAudio) live behind these calls. Per-frame the UI calls
// nativeUpdate(dt) then nativeFill(buffer) to pull a zero-copy bar snapshot into
// a direct ByteBuffer. JNI symbols: Java_com_mariocjun_algoviz_VizBridge_*
// (viz/native_bridge.cpp).
package com.mariocjun.algoviz

import java.nio.ByteBuffer

object VizBridge {
    init { System.loadLibrary("algoviz") }

    external fun nativeInit()
    external fun nativeSetMode(m: Int)
    external fun nativeMode(): Int
    external fun nativeSetAlgorithm(i: Int)
    external fun nativeSetSize(n: Int)
    external fun nativeSetSpeed(s: Int)
    external fun nativeSetPlaying(p: Boolean)
    external fun nativeTogglePlay()
    external fun nativeReset()
    external fun nativeShuffle()
    external fun nativeStep(dir: Int)
    external fun nativeSetDrawMode(d: Boolean)
    external fun nativePaint(idx: Int, v01: Float)
    external fun nativeSetAutoLoop(b: Boolean)
    external fun nativeSetSound(e: Boolean)
    external fun nativeSetVolume(v: Float)
    external fun nativeSetScale(i: Int)
    external fun nativeCelebrate()
    external fun nativePlayNote(v01: Float)
    external fun nativeSetSlow(ms: Int)
    external fun nativeSetRaceMode(m: Int)
    external fun nativeUpdate(dt: Float)
    external fun nativeFill(buffer: ByteBuffer): Int
    external fun nativeAudioResume()
    external fun nativeAudioPause()
    external fun nativeAlgoNames(): Array<String>
    external fun nativeScaleNames(): Array<String>
    external fun nativeScaleNotes(i: Int): Int
}
