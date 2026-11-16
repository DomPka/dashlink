package com.dompka.dashlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A live picture from the camera. A FrameLayout holding a TextureView ([R.id.live_texture]); while it is started
 * and the texture's surface exists, one thread ("dashlink-live") runs [Live.run] into it: H.264 goes through
 * MediaCodec straight onto the surface, Motion JPEG is decoded with BitmapFactory and drawn onto it.
 *
 * The picture is letterboxed, never cropped, at the stream's aspect ratio (16:9 until the stream says otherwise).
 * A side left open by the parent (wrap_content) follows that ratio. It draws no chrome of its own: the screen
 * around it does that from [status]. Views the screen adds as children are laid out over the picture itself, not
 * over the bars beside it, so a chip or a date stamp stays on the video whatever its shape.
 *
 * While a download is running it does not touch the camera at all and reports [DOWNLOAD_BUSY]; it comes back by
 * itself when the download ends. Everything else that needs the camera (a file list, a thumbnail) makes Live.run
 * give it up for a moment.
 */
internal class LiveSurface(context: Context) : FrameLayout(context) {

    /** Called on the main thread whenever the status changes (at most a few times a second). */
    var onStatus: ((Live.Status) -> Unit)? = null

    /** The latest status (main thread). */
    val status: Live.Status get() = current

    private val main = Handler(Looper.getMainLooper())
    private val texture = TextureView(context)
    private var current = Live.Status(Live.Phase.STOPPED, "stopped")

    /** Width / height of the picture (main thread). */
    private var aspect = 16f / 9f
    private val frame = Rect()
    private val box = Rect()
    private val place = Rect()

    @Volatile
    private var wanted = false

    /**
     * Changes whenever the streaming thread must end: stop(), and the surface going away or coming back. Each
     * thread knows the value it was started with; statuses from an older thread are dropped on arrival.
     */
    @Volatile
    private var token = 0

    @Volatile
    private var surfaceTexture: SurfaceTexture? = null

    /** A picture has reached the texture since its surface was made. Main thread. */
    private var shown = false
    private var thread: Thread? = null

    /** Guards [connected]: a decoder or a canvas is connected to the surface, and a surface going away waits for it. */
    private val lock = ReentrantLock()
    private val disconnected = lock.newCondition()
    private var connected = false

    init {
        texture.id = R.id.live_texture
        texture.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                surfaceTexture = st
                shown = false
                launch()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceTexture = null
                shown = false
                token++
                // The decoder must let go of the surface before it is released, or it dies writing into nothing.
                // It notices within one frame or one poll of the camera; it is not waited for longer than that.
                val until = SystemClock.uptimeMillis() + RELEASE_WAIT_MS
                lock.withLock {
                    while (connected) {
                        val left = until - SystemClock.uptimeMillis()
                        if (left <= 0) break
                        disconnected.await(left, TimeUnit.MILLISECONDS)
                    }
                }
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
                shown = true
            }
        }
        addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Starts streaming as soon as the surface is ready. Harmless if already started. */
    fun start() {
        if (wanted) return
        wanted = true
        deliver(Live.Status(Live.Phase.STARTING, "starting"))
        launch()
    }

    /** Stops streaming and frees the camera; returns at once, the streaming thread ends by itself. */
    fun stop() {
        if (!wanted) return
        wanted = false
        token++
        deliver(Live.Status(Live.Phase.STOPPED, "stopped"))
    }

    /** The last picture shown, or null (for a frozen "paused" frame). Main thread. */
    fun snapshot(): Bitmap? = if (showing) texture.bitmap else null

    /** A picture is on the texture (it stays there, frozen, when the stream pauses). Main thread. */
    internal val showing: Boolean get() = shown && texture.isAvailable

    /** Starts a streaming thread for the current surface. One at a time: a new one waits for the one before. */
    private fun launch() {
        if (!wanted || surfaceTexture == null) return
        val mine = ++token
        val previous = thread
        val t = Thread({
            previous?.join(JOIN_MS)
            stream(mine)
        }, "dashlink-live")
        t.isDaemon = true
        thread = t
        t.start()
    }

    private fun running(mine: Int) = token == mine && wanted && surfaceTexture != null && !Thread.currentThread().isInterrupted

    /** The streaming thread. */
    private fun stream(mine: Int) {
        val decoder = Decoder()
        var paused = false
        try {
            while (running(mine)) {
                if (DownloadService.working) {
                    // The download has the camera: not a single request until it is done.
                    decoder.release()
                    if (!paused) post(mine, Live.Status(Live.Phase.PAUSED_BUSY, DOWNLOAD_BUSY))
                    paused = true
                    val until = SystemClock.uptimeMillis() + BUSY_POLL_MS
                    while (running(mine) && SystemClock.uptimeMillis() < until) Thread.sleep(STEP_MS)
                    continue
                }
                paused = false
                try {
                    Live.run(decoder, { !running(mine) || DownloadService.working }) { s ->
                        // Live.run ends whenever a download starts; that is a pause here, not the end.
                        if (s.phase != Live.Phase.STOPPED) post(mine, s)
                    }
                } catch (e: RuntimeException) {
                    // Live.run reports camera trouble itself; anything else must not take the app down with it.
                    post(mine, Live.Status(Live.Phase.UNAVAILABLE, "the live view failed (${Cam.describe(e)})"))
                    Thread.sleep(BUSY_POLL_MS)
                }
                decoder.release()
            }
        } catch (e: InterruptedException) {
            // ends the thread
        } finally {
            decoder.release()
        }
    }

    private fun post(mine: Int, s: Live.Status) {
        main.post { if (token == mine) deliver(s) }
    }

    private fun deliver(s: Live.Status) {
        current = s
        onStatus?.invoke(s)
    }

    /** From the streaming thread: the picture is [w] x [h]. */
    private fun shape(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val a = w.toFloat() / h
        main.post {
            if (abs(a - aspect) > 0.001f) {
                aspect = a
                requestLayout()
            }
        }
    }

    // ---------------------------------------------------------------- layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val maxW = if (wMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val maxH = if (hMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = maxH
        when {
            wMode == MeasureSpec.EXACTLY && hMode == MeasureSpec.EXACTLY -> Unit
            wMode == MeasureSpec.EXACTLY -> h = minOf(maxH, (w / aspect).roundToInt())
            hMode == MeasureSpec.EXACTLY -> w = minOf(maxW, (h * aspect).roundToInt())
            maxW == Int.MAX_VALUE && maxH == Int.MAX_VALUE -> {
                w = 0
                h = 0
            }
            else -> {
                fit(maxW, maxH, frame)
                w = frame.width()
                h = frame.height()
            }
        }
        setMeasuredDimension(w, h)
        fit(w, h, frame)
        val pw = MeasureSpec.makeMeasureSpec(frame.width(), MeasureSpec.EXACTLY)
        val ph = MeasureSpec.makeMeasureSpec(frame.height(), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child === texture) child.measure(pw, ph) else measureChildWithMargins(child, pw, 0, ph, 0)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fit(right - left, bottom - top, frame)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child === texture) {
                child.layout(frame.left, frame.top, frame.right, frame.bottom)
                continue
            }
            val lp = child.layoutParams as LayoutParams
            val gravity = if (lp.gravity == -1) Gravity.TOP or Gravity.START else lp.gravity
            box.set(frame.left + lp.leftMargin, frame.top + lp.topMargin, frame.right - lp.rightMargin, frame.bottom - lp.bottomMargin)
            Gravity.apply(gravity, child.measuredWidth, child.measuredHeight, box, place, layoutDirection)
            child.layout(place.left, place.top, place.right, place.bottom)
        }
    }

    /** The largest rectangle of the picture's shape that fits in [w] x [h], centred, into [out]. */
    private fun fit(w: Int, h: Int, out: Rect) {
        var pw = w
        var ph = (w / aspect).roundToInt()
        if (ph > h) {
            ph = h
            pw = (h * aspect).roundToInt()
        }
        val x = (w - pw) / 2
        val y = (h - ph) / 2
        out.set(x, y, x + pw, y + ph)
    }

    // ---------------------------------------------------------------- pictures onto the surface

    /** Turns the stream's pictures into pixels on the surface. Streaming thread only. */
    private inner class Decoder : VideoSink {
        private var codec: MediaCodec? = null
        private var codecSurface: Surface? = null
        private var canvasSurface: Surface? = null
        private var jpeg = false
        private val info = MediaCodec.BufferInfo()
        private var picture: Bitmap? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val target = Rect()

        override fun onFormat(format: VideoFormat) {
            release()
            when (format) {
                is VideoFormat.H264 -> open(format)
                is VideoFormat.Jpeg -> {
                    jpeg = true
                    shape(format.width, format.height)
                }
            }
        }

        override fun onFrame(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean) {
            if (jpeg) draw(data, length) else decode(data, length, ptsUs, keyFrame)
        }

        private fun open(f: VideoFormat.H264) {
            val st = surfaceTexture ?: throw IllegalStateException("the picture surface is gone")
            val known = f.width > 0 && f.height > 0
            if (known) shape(f.width, f.height)
            val w = if (known) f.width else 1280
            val h = if (known) f.height else 720
            val mf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            // Without them in the SDP the parameter sets come in-band, in front of the first key frame.
            f.sps?.let { mf.setByteBuffer("csd-0", annexB(it)) }
            f.pps?.let { mf.setByteBuffer("csd-1", annexB(it)) }
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (w * h * 3 / 2).coerceIn(1 shl 20, RtpDepacketizer.MAX_PICTURE))
            mf.setInteger(MediaFormat.KEY_PRIORITY, 0)                  // real time
            val c = try {
                MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (e: IOException) {
                throw IllegalStateException("this phone has no H.264 decoder", e)
            }
            codec = c
            connect()
            try {
                if (lowLatency(c)) mf.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                val s = Surface(st)
                codecSurface = s
                c.configure(mf, s, null, 0)
                c.start()
            } catch (e: RuntimeException) {
                release()
                throw IllegalStateException("the decoder did not start (${Cam.describe(e)})", e)
            }
        }

        private fun lowLatency(c: MediaCodec): Boolean = try {
            c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        } catch (e: RuntimeException) {
            false
        }

        private fun annexB(nal: ByteArray): ByteBuffer {
            val b = ByteBuffer.allocate(nal.size + 4)
            b.put(START_CODE).put(nal).flip()
            return b
        }

        /**
         * One access unit into the decoder. Live reuses [data] after this returns, so it is copied into an input
         * buffer first. A decoder that has no room within 20 ms loses the picture, unless it is a key frame: that one
         * is worth up to 100 ms, since everything after it depends on it. Then the newest finished picture is shown.
         */
        private fun decode(data: ByteArray, length: Int, ptsUs: Long, keyFrame: Boolean) {
            val c = codec ?: throw IllegalStateException("no decoder for this stream")
            try {
                var index = c.dequeueInputBuffer(INPUT_WAIT_US)
                if (index < 0 && keyFrame) {
                    val until = System.nanoTime() + KEY_WAIT_NS
                    while (index < 0 && System.nanoTime() < until) {
                        drain(c)
                        index = c.dequeueInputBuffer(INPUT_WAIT_US / 4)
                    }
                }
                if (index >= 0) {
                    val buf = c.getInputBuffer(index) ?: throw IllegalStateException("the decoder gave no input buffer")
                    buf.clear()
                    if (length <= buf.remaining()) {
                        buf.put(data, 0, length)
                        c.queueInputBuffer(index, 0, length, ptsUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    } else {
                        c.queueInputBuffer(index, 0, 0, ptsUs, 0)       // larger than the decoder takes: lost
                    }
                }
                drain(c)
            } catch (e: RuntimeException) {
                // MediaCodec.CodecException and IllegalStateException: Live ends this stream and starts a new one.
                release()
                throw IllegalStateException("the decoder failed (${Cam.describe(e)})", e)
            }
        }

        /** Takes every finished picture out of the decoder; only the newest is shown, older ones are dropped. */
        private fun drain(c: MediaCodec) {
            var newest = -1
            while (true) {
                val i = c.dequeueOutputBuffer(info, 0)
                when {
                    i >= 0 -> {
                        if (newest >= 0) c.releaseOutputBuffer(newest, false)
                        newest = i
                    }
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputShape(c.outputFormat)
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                }
            }
            if (newest >= 0) c.releaseOutputBuffer(newest, true)
        }

        /** The decoder's own idea of the picture, crop included (the keys are spelled out: their constants are API 33). */
        private fun outputShape(f: MediaFormat) {
            var w = int(f, MediaFormat.KEY_WIDTH)
            var h = int(f, MediaFormat.KEY_HEIGHT)
            val left = int(f, "crop-left")
            val right = int(f, "crop-right")
            val top = int(f, "crop-top")
            val bottom = int(f, "crop-bottom")
            if (left >= 0 && right > left && top >= 0 && bottom > top) {
                w = right - left + 1
                h = bottom - top + 1
            }
            shape(w, h)
        }

        private fun int(f: MediaFormat, key: String): Int = try {
            if (f.containsKey(key)) f.getInteger(key) else -1
        } catch (e: RuntimeException) {
            -1
        }

        /** One JPEG picture, decoded and drawn scaled to fit; a picture that does not decode is skipped. */
        private fun draw(data: ByteArray, length: Int) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, length, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            val opts = BitmapFactory.Options().apply { inMutable = true }
            val old = picture
            if (old != null && old.width == bounds.outWidth && old.height == bounds.outHeight) opts.inBitmap = old
            val bmp = try {
                BitmapFactory.decodeByteArray(data, 0, length, opts)
            } catch (e: IllegalArgumentException) {
                null
            } ?: return
            if (old == null || old.width != bmp.width || old.height != bmp.height) shape(bmp.width, bmp.height)
            picture = bmp
            val st = surfaceTexture ?: return
            try {
                val s = canvasSurface ?: Surface(st).also {
                    connect()
                    canvasSurface = it
                }
                val canvas = s.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    val scale = minOf(canvas.width.toFloat() / bmp.width, canvas.height.toFloat() / bmp.height)
                    val w = (bmp.width * scale).roundToInt()
                    val h = (bmp.height * scale).roundToInt()
                    target.set((canvas.width - w) / 2, (canvas.height - h) / 2, (canvas.width + w) / 2, (canvas.height + h) / 2)
                    canvas.drawBitmap(bmp, null, target, paint)
                } finally {
                    s.unlockCanvasAndPost(canvas)
                }
            } catch (e: RuntimeException) {
                // The surface went away or cannot be drawn on: Live ends this stream; the thread notices why.
                release()
                throw IllegalStateException("the picture could not be drawn (${Cam.describe(e)})", e)
            }
        }

        private fun connect() {
            lock.withLock { connected = true }
        }

        /** Lets go of the decoder and the surface, so that another stream (or another owner) can have them. */
        fun release() {
            jpeg = false
            codec?.let { c ->
                try {
                    c.stop()
                } catch (e: RuntimeException) {
                    // it had failed already
                }
                try {
                    c.release()
                } catch (e: RuntimeException) {
                    // nothing more to free
                }
            }
            codec = null
            // A plain Surface's release disconnects it, so the next decoder or canvas can connect to the texture.
            codecSurface?.release()
            codecSurface = null
            canvasSurface?.release()
            canvasSurface = null
            lock.withLock {
                connected = false
                disconnected.signalAll()
            }
        }
    }

    companion object {
        /** The status detail while a download has the camera: the live view does not touch it then. */
        const val DOWNLOAD_BUSY = "Camera busy with a download"

        /** How often to look whether the download is done. */
        private const val BUSY_POLL_MS = 500L
        private const val STEP_MS = 50L

        /** How long the surface waits for the decoder to let go of it when it is destroyed. */
        private const val RELEASE_WAIT_MS = 500L

        /** How long a new streaming thread waits for the one before it to end. */
        private const val JOIN_MS = 3_000L

        private const val INPUT_WAIT_US = 20_000L
        private const val KEY_WAIT_NS = 100_000_000L

        private val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

/** "24 fps · 850 kb/s" while playing (plus " · lagging"), "—" otherwise: the same wording on the tile and in the viewer. */
internal fun Live.Status.readout(): String =
    if (phase != Live.Phase.PLAYING) "—"
    else String.format(Locale.US, "%.0f fps · %d kb/s", fps, kbps) + if (lagging) " · lagging" else ""
