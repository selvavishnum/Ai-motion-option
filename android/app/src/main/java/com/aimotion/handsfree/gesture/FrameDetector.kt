package com.aimotion.handsfree.gesture

import android.graphics.Bitmap

/**
 * A consumer of camera frames.
 *
 * Exists so the frame loop can select its consumer *before* preparing a frame. Converting a
 * CameraX buffer into an upright, mirrored bitmap costs a full-resolution copy; deciding
 * afterwards which detector wanted it meant paying that cost even when nothing was listening.
 * With a common type the loop resolves a single nullable [FrameDetector] first and can abandon
 * the frame for free.
 */
interface FrameDetector {
    fun detectAsync(bitmap: Bitmap, timestampMs: Long)
    fun close()
}
