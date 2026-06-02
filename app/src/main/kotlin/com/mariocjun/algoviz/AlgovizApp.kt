// App-wide auto-close ("sleep guard"): the app shuts itself down after 39 min of
// foreground time, so it never runs all night if you doze off while using it.
// A hidden gesture (10 quick taps in the bottom 20% of any Compose screen, see
// AutoCloseDisableOverlay) reveals a button that disables it for the session.
package com.mariocjun.algoviz

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class AlgovizApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Progress.init(this)   // local-only persistence (no PII); foundation for onboarding flags + mastery
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = AutoClose.onForeground(activity)
            override fun onActivityPaused(activity: Activity) = AutoClose.onBackground()
            override fun onActivityDestroyed(activity: Activity) = AutoClose.onDestroyed(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })
    }
}

/** Counts foreground time across activities; finishes the task at the limit. */
object AutoClose {
    private const val LIMIT_MS = 39L * 60L * 1000L      // 39 minutes
    private val handler = Handler(Looper.getMainLooper())
    private var elapsedMs = 0L                           // accumulated foreground time
    private var resumedAt = 0L
    private var current: Activity? = null
    var disabled = false
        private set
    private val closeRunnable = Runnable { current?.finishAffinity() }

    fun onForeground(a: Activity) {
        current = a
        if (disabled) return
        resumedAt = SystemClock.elapsedRealtime()
        handler.removeCallbacks(closeRunnable)
        handler.postDelayed(closeRunnable, (LIMIT_MS - elapsedMs).coerceAtLeast(0L))
    }

    fun onBackground() {
        handler.removeCallbacks(closeRunnable)
        if (resumedAt > 0L) { elapsedMs += SystemClock.elapsedRealtime() - resumedAt; resumedAt = 0L }
    }

    fun onDestroyed(a: Activity) { if (current === a) current = null }

    fun disable() { disabled = true; handler.removeCallbacks(closeRunnable) }
}
