package com.v1svaldis.openspeak.stream

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.webrtc.CapturerObserver
import org.webrtc.VideoFrame

/**
 * Android's screen capture only produces a frame when the screen content changes. A viewer who joins
 * while the screen is still would get nothing to decode and see black, so this re-sends the last
 * picture a couple of times per second while the capture is idle.
 *
 * The capturer's texture frames cannot be held (the capture surface has a single buffer), so a CPU
 * copy of the latest frame is taken at most once per [SNAPSHOT_INTERVAL_MS].
 */
class FrameRepeater(private val target: CapturerObserver) : CapturerObserver {
    companion object {
        private const val SNAPSHOT_INTERVAL_MS = 1000L
        private const val IDLE_MS = 600L
        private const val REPEAT_MS = 500L
    }

    private val thread = HandlerThread("ts-frame-repeater").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private var snapshot: VideoFrame.I420Buffer? = null
    private var snapshotRotation = 0
    private var snapshotAt = 0L
    @Volatile private var lastFrameAt = 0L
    @Volatile private var running = false

    private val repeat = object : Runnable {
        override fun run() {
            if (!running) return
            if (SystemClock.elapsedRealtime() - lastFrameAt > IDLE_MS) {
                synchronized(lock) {
                    val s = snapshot
                    if (s != null) {
                        s.retain()
                        val frame = VideoFrame(s, snapshotRotation, System.nanoTime())
                        target.onFrameCaptured(frame)
                        frame.release()
                    }
                }
            }
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onCapturerStarted(success: Boolean) {
        target.onCapturerStarted(success)
        running = true
        handler.postDelayed(repeat, REPEAT_MS)
    }

    override fun onCapturerStopped() {
        running = false
        handler.removeCallbacks(repeat)
        target.onCapturerStopped()
    }

    override fun onFrameCaptured(frame: VideoFrame) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        if (now - snapshotAt >= SNAPSHOT_INTERVAL_MS) {
            snapshotAt = now
            val copy = frame.buffer.toI420()
            synchronized(lock) {
                snapshot?.release()
                snapshot = copy
                snapshotRotation = frame.rotation
            }
        }
        target.onFrameCaptured(frame)
    }

    fun dispose() {
        running = false
        handler.removeCallbacks(repeat)
        handler.post {
            synchronized(lock) {
                snapshot?.release()
                snapshot = null
            }
            thread.quitSafely()
        }
    }
}
