package com.example.mirrordrive

import android.os.SystemClock

/**
 * Process-wide mirroring session state, fed by native session events (VideoRenderer.onStreamEvent)
 * and read by the waiting screen's guidance. All fields are written from UxPlay threads.
 */
object Session {
    @Volatile var running = false
        private set
    @Volatile var iphoneName: String? = null
        private set
    @Volatile var endedAtMs = 0L
        private set
    /** Since when we have been waiting for a stream (app start or last session end). */
    @Volatile var waitingSinceMs = SystemClock.uptimeMillis()
        private set
    @Volatile var decoderRecoveries = 0

    fun onEvent(code: Int, detail: String?) {
        val now = SystemClock.uptimeMillis()
        when (code) {
            EV_STARTED -> running = true
            EV_STOPPED, EV_VIDEO_RESET, EV_CONN_RESET -> {
                if (running) endedAtMs = now
                running = false
                waitingSinceMs = now
            }
            EV_CLIENT -> if (!detail.isNullOrBlank()) iphoneName = detail
        }
    }

    fun snapshot(now: Long = SystemClock.uptimeMillis()) = SessionSnapshot(
        running = running,
        iphoneName = iphoneName,
        endedAgoMs = if (endedAtMs == 0L) null else now - endedAtMs,
        waitingMs = if (running) 0 else now - waitingSinceMs,
    )

    const val EV_STARTED = 1
    const val EV_STOPPED = 2
    const val EV_VIDEO_RESET = 3
    const val EV_CONN_RESET = 4
    const val EV_CLIENT = 5
}

/** Immutable view of [Session] for the pure guidance functions. */
data class SessionSnapshot(
    val running: Boolean = false,
    val iphoneName: String? = null,
    val endedAgoMs: Long? = null,
    val waitingMs: Long = 0,
)
