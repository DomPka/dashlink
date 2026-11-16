package com.dompka.dashlink

import android.content.Context
import android.graphics.Bitmap
import android.widget.FrameLayout

/*
 * CONTRACT (to be implemented): a live picture from the camera. A FrameLayout holding a TextureView; it runs
 * Live.run on its own thread while started and its surface exists, decodes H.264 with MediaCodec straight to
 * the surface (or draws JPEG frames onto it), and keeps the picture letterboxed at the stream's aspect ratio.
 * It draws no chrome of its own (no texts, chips or brackets): the screen around it does that from [status].
 */
internal class LiveSurface(context: Context) : FrameLayout(context) {

    /** Called on the main thread whenever the status changes (at most a few times a second). */
    var onStatus: ((Live.Status) -> Unit)? = null

    /** The latest status (main thread). */
    val status: Live.Status get() = TODO("contract")

    /** Starts streaming as soon as the surface is ready. Harmless if already started. */
    fun start() {
        TODO("contract")
    }

    /** Stops streaming and frees the camera; returns at once, the streaming thread ends by itself. */
    fun stop() {
        TODO("contract")
    }

    /** The last picture shown, or null (for a frozen "paused" frame). */
    fun snapshot(): Bitmap? = TODO("contract")
}
