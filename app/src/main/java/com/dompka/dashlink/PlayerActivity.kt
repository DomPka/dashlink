package com.dompka.dashlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Plays a clip straight from the camera's SD card (spec 5.10): MediaPlayer reads it through a MediaDataSource over a
 * RemoteFile, which fetches it with HTTP Range on the camera link and reads ahead of the player. Play and pause, a
 * seek bar, ten seconds back and forward, the clip's facts, and a Download button. Photos are shown as a picture.
 *
 * The camera is only used while this screen is on screen (onStart to onStop): leaving it closes the file at once,
 * which frees the camera, and coming back opens it again where it was. It never reads while a download is running;
 * it says so, and plays by itself once the download is done. Download first lets go of the clip for the same reason.
 *
 * Letting go of a MediaPlayer can block in native code while a read hangs on a dead link, so it is done in this
 * order: pause, close the RemoteFile (every read waiting in it fails at once), and only then reset and release,
 * on a background thread.
 */
class PlayerActivity : Activity() {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SIZE = "size"
        const val EXTRA_TIME = "time"
        const val EXTRA_RES = "res"
        const val EXTRA_SECS = "secs"
        const val EXTRA_FOLDER = "folder"

        fun open(ctx: Context, clip: Cam.Clip, folder: String) {
            val intent = Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PATH, clip.path).putExtra(EXTRA_SIZE, clip.size).putExtra(EXTRA_TIME, clip.time)
                .putExtra(EXTRA_RES, clip.res).putExtra(EXTRA_SECS, clip.secs).putExtra(EXTRA_FOLDER, folder)
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }

        /** One thread for every player that is let go of: reset() and release() may block, never on the main thread. */
        private val releaser = Executors.newSingleThreadExecutor { r -> Thread(r, "dashlink-player").apply { isDaemon = true } }

        private const val TICK_MS = 250L
        private const val PREPARE_MS = 25_000L

        /** Playing, yet the position has not moved for this long: show the buffering ring. */
        private const val STALL_MS = 800L

        /** After Download was tapped, the service may take a moment to say it is working. */
        private const val START_GRACE_MS = 5_000L
        private const val SKIP_MS = 10_000L
        private const val PHOTO_SIDE = 2048
        private const val PHOTO_LIMIT = 32L * 1024 * 1024
        private const val KEY_POSITION = "position"
        private const val KEY_PLAY = "play"
    }

    private enum class Show { OPENING, READY, ERROR, BUSY, LOST }

    private lateinit var clip: Cam.Clip
    private var folder = ""
    private var photo = false
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var t: Tokens
    private lateinit var dark: Context
    private lateinit var td: Tokens

    // ---------------------------------------------------------------- playback state (main thread)

    private var player: MediaPlayer? = null
    private var file: RemoteFile? = null
    private var surface: Surface? = null
    private var prepared = false
    private var seeking = false
    private var buffering = false
    private var wantPlay = true
    private var position = 0L
    private var duration = 0L
    private var lastSeen = -1L
    private var stalledSince = 0L
    private var show = Show.OPENING
    private var started = false
    private var photoShown = false
    private var tracking = false
    private var tens = -1L
    private var ticks = 0

    /** When Download was tapped (uptime), 0 if not; and what Android said if it refused to start the service. */
    private var askedAt = 0L
    private var problem: String? = null

    // ---------------------------------------------------------------- views

    private lateinit var frame: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var left: LinearLayout
    private lateinit var right: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var videoFrame: AspectFrame
    private lateinit var video: TextureView
    private lateinit var photoView: ImageView
    private lateinit var ring: ProgressBar
    private lateinit var feedback: ImageView
    private lateinit var feedbackGlyph: PlayGlyphDrawable
    private lateinit var errorBox: LinearLayout
    private lateinit var errorTitle: TextView
    private lateinit var noticeBox: LinearLayout
    private lateinit var noticeIcon: ImageView
    private lateinit var noticeTitle: TextView
    private lateinit var noticeDetail: TextView
    private lateinit var transport: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var total: TextView
    private lateinit var play: ImageButton
    private lateinit var back10: ImageButton
    private lateinit var fwd10: ImageButton
    private lateinit var facts: LinearLayout
    private val factValues = HashMap<String, TextView>()
    private lateinit var strip: LinearLayout
    private lateinit var savedRow: LinearLayout
    private lateinit var stripNote: TextView
    private lateinit var downloadBtn: Button
    private lateinit var progressBox: LinearLayout
    private lateinit var progressTitle: TextView
    private lateinit var progressMb: TextView
    private lateinit var road: ProgressBar
    private lateinit var bubble: TextView
    private var wide: Boolean? = null
    private var insetBottom = 0

    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, TICK_MS)
        }
    }
    private val prepareTimeout = Runnable { if (player != null && !prepared) failed() }
    private val linkListener: () -> Unit = { linkChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        clip = Cam.Clip(
            i.getStringExtra(EXTRA_PATH) ?: "", i.getLongExtra(EXTRA_SIZE, -1L), i.getStringExtra(EXTRA_TIME) ?: "",
            i.getStringExtra(EXTRA_RES) ?: "", i.getStringExtra(EXTRA_SECS) ?: "",
        )
        folder = i.getStringExtra(EXTRA_FOLDER) ?: ""
        photo = clip.name.endsWith(".jpg", ignoreCase = true) || clip.name.endsWith(".jpeg", ignoreCase = true)
        savedInstanceState?.let {
            position = it.getLong(KEY_POSITION)
            wantPlay = it.getBoolean(KEY_PLAY, true)
        }
        dark = MediaChrome.dark(this)
        td = Tokens.of(dark)
        t = Tokens.of(this)
        window.setDecorFitsSystemWindows(false)
        window.setBackgroundDrawable(FieldBackground(this))
        build()
        setContentView(frame)
        arrange()
        if (clip.path.isEmpty()) finish()                  // nothing to play
    }

    override fun onStart() {
        super.onStart()
        started = true
        Thumbs.hold(this, true)
        Link.addListener(linkListener)
        renderStrip()
        open()
        ui.post(ticker)
    }

    override fun onStop() {
        started = false
        release()
        ui.removeCallbacks(ticker)
        Link.removeListener(linkListener)
        Thumbs.hold(this, false)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, currentPosition())
        outState.putBoolean(KEY_PLAY, wantPlay)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    // ---------------------------------------------------------------- opening and letting go

    private fun open() {
        if (!started || player != null || file != null) return
        when {
            !Link.ready -> enter(Show.LOST)
            downloadBusy() -> enter(Show.BUSY)
            photo -> if (photoShown) enter(Show.READY) else openPhoto()
            else -> openVideo()
        }
    }

    private fun openVideo() {
        enter(Show.OPENING)
        val st = video.surfaceTexture ?: return             // onSurfaceTextureAvailable opens it
        val f = RemoteFile(clip.path, clip.size, sequential = true)
        val mp = MediaPlayer()
        val s = Surface(st)
        file = f
        player = mp
        surface = s
        prepared = false
        seeking = false
        buffering = false
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build(),
            )
            mp.setOnPreparedListener { if (it === player) prepared(it) }
            mp.setOnErrorListener { p, _, _ ->
                if (p === player) failed()
                true
            }
            mp.setOnInfoListener { p, what, _ ->
                if (p === player) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) buffering = false
                    showRing()
                }
                false
            }
            mp.setOnCompletionListener {
                if (it === player) {
                    wantPlay = false
                    position = duration
                    showPlayState()
                }
            }
            mp.setOnSeekCompleteListener { if (it === player) seeking = false }
            mp.setOnVideoSizeChangedListener { p, w, h -> if (p === player && w > 0 && h > 0) videoFrame.aspect = w.toFloat() / h }
            mp.setDataSource(ClipSource(f))
            mp.setSurface(s)
            mp.prepareAsync()
        } catch (e: IOException) {
            failed()
            return
        } catch (e: RuntimeException) {
            failed()
            return
        }
        ui.postDelayed(prepareTimeout, PREPARE_MS)
    }

    private fun prepared(mp: MediaPlayer) {
        prepared = true
        ui.removeCallbacks(prepareTimeout)
        duration = mp.duration.toLong().coerceAtLeast(0)
        seek.max = duration.toInt().coerceAtLeast(1)
        total.text = MediaChrome.clock(duration)
        if (clip.secs.isEmpty() && duration > 0) factValues["Length"]?.text = MediaChrome.clock(duration)
        if (position in 1 until duration) {
            seeking = true
            mp.seekTo(position, MediaPlayer.SEEK_CLOSEST)
        }
        if (wantPlay) mp.start()
        enter(Show.READY)
    }

    private fun failed() {
        release()
        enter(Show.ERROR)
    }

    /** Lets go of the clip and the camera at once; the player itself is released in the background. */
    private fun release() {
        ui.removeCallbacks(prepareTimeout)
        val mp = player
        if (mp != null) position = currentPosition()
        player = null
        val wasPrepared = prepared
        prepared = false
        seeking = false
        buffering = false
        stalledSince = 0
        if (mp != null && wasPrepared) {
            try {
                if (mp.isPlaying) mp.pause()
            } catch (e: IllegalStateException) {
                // it had failed already
            }
        }
        // First: a read the player is stuck in fails at once, and the camera is free again.
        file?.close()
        file = null
        val s = surface
        surface = null
        if (mp != null) {
            releaser.execute {
                try {
                    mp.reset()
                } catch (e: RuntimeException) {
                    // released below anyway
                }
                try {
                    mp.release()
                } catch (e: RuntimeException) {
                    // nothing more to free
                }
                s?.release()
            }
        } else {
            s?.release()
        }
        showRing()
    }

    private fun currentPosition(): Long {
        val mp = player ?: return position
        if (!prepared) return position
        return try {
            mp.currentPosition.toLong()
        } catch (e: IllegalStateException) {
            position
        }
    }

    private fun downloadBusy(): Boolean =
        DownloadService.working || (askedAt > 0 && SystemClock.uptimeMillis() - askedAt < START_GRACE_MS && Jobs.hasWork())

    private fun linkChanged() {
        if (!started) return
        if (!Link.ready && show != Show.LOST) {
            release()
            enter(Show.LOST)
        } else if (Link.ready && show == Show.LOST) {
            open()
        }
    }

    // ---------------------------------------------------------------- photos

    private fun openPhoto() {
        enter(Show.OPENING)
        val f = RemoteFile(clip.path, clip.size, sequential = false)
        file = f
        val thread = Thread({
            val picture = try {
                readPhoto(f)
            } catch (e: Exception) {
                null                                           // a dropped link, a closed file, a broken photo
            } catch (e: OutOfMemoryError) {
                null
            } finally {
                f.close()
            }
            ui.post {
                if (file !== f) return@post                // let go of meanwhile
                file = null
                if (picture == null) failed() else showPhoto(picture)
            }
        }, "dashlink-photo")
        thread.isDaemon = true
        thread.start()
    }

    private class Photo(val bitmap: Bitmap, val width: Int, val height: Int)

    /** The whole photo, decoded at the smallest power-of-two reduction that keeps its long side within 2048 px. */
    private fun readPhoto(f: RemoteFile): Photo? {
        val size = if (clip.size > 0) clip.size else f.length()
        if (size > PHOTO_LIMIT) return null
        val bytes = ByteArray(size.toInt())
        var at = 0
        while (at < bytes.size) {
            val n = f.readAt(at.toLong(), bytes, at, bytes.size - at)
            if (n < 0) break
            at += n
        }
        if (at < bytes.size) throw IOException("the photo ended after $at of ${bytes.size} bytes")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PHOTO_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return Photo(bitmap, bounds.outWidth, bounds.outHeight)
    }

    private fun showPhoto(p: Photo) {
        photoShown = true
        photoView.setImageBitmap(p.bitmap)
        photoView.visibility = View.VISIBLE
        videoFrame.aspect = p.width.toFloat() / p.height
        factValues["Resolution"]?.text = "${p.width}x${p.height}"
        enter(Show.READY)
    }

    // ---------------------------------------------------------------- what is on screen

    private fun enter(s: Show) {
        show = s
        val ready = s == Show.READY
        errorBox.visibility = if (s == Show.ERROR) View.VISIBLE else View.GONE
        noticeBox.visibility = if (s == Show.BUSY || s == Show.LOST) View.VISIBLE else View.GONE
        if (s == Show.BUSY) notice(R.drawable.ic_download, td.warnText, LiveSurface.DOWNLOAD_BUSY, "The clip plays when the download is done.")
        if (s == Show.LOST) notice(R.drawable.ic_lens_off, td.errorText, "Lost the camera", "The clip plays again when the camera is back.")
        for (v in listOf(seek, back10, fwd10)) Glass.setEnabledLook(v, ready)
        Glass.setEnabledLook(play, ready || s == Show.ERROR)
        showPlayState()
        showRing()
    }

    private fun notice(icon: Int, color: Int, title: String, detail: String) {
        noticeIcon.setImageDrawable(getDrawable(icon)?.mutate()?.also { it.setTint(color) })
        noticeTitle.text = title
        noticeDetail.text = detail
    }

    private fun playing(): Boolean = try {
        prepared && player?.isPlaying == true
    } catch (e: IllegalStateException) {
        false
    }

    private fun showPlayState() {
        val on = playing() || (wantPlay && show == Show.OPENING)
        play.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
        play.contentDescription = if (on) "Pause" else "Play"
    }

    private fun showRing() {
        val stalled = stalledSince > 0 && SystemClock.uptimeMillis() - stalledSince > STALL_MS
        val want = show == Show.OPENING || (show == Show.READY && !photo && (seeking || buffering || stalled))
        val v = if (want) View.VISIBLE else View.GONE
        if (ring.visibility != v) ring.visibility = v
    }

    private fun showPosition(ms: Long) {
        if (!tracking) seek.progress = ms.toInt()
        val text = MediaChrome.clock(ms)
        if (elapsed.text.toString() != text) {
            elapsed.text = text
            seek.stateDescription = text
        }
    }

    private fun tick() {
        ticks++
        val mp = player
        if (mp != null && prepared) {
            val pos = currentPosition()
            if (!tracking) {
                position = pos
                showPosition(pos)
            }
            // How far the clip is in memory, as a time: bytes and time are taken to grow together, which they
            // only roughly do (a still scene takes fewer bytes than a busy one), so this is an estimate.
            val f = file
            if (f != null && duration > 0 && clip.size > 0) {
                val until = f.bufferedUntil(clip.size * pos / duration)
                seek.secondaryProgress = (until * duration / clip.size).toInt()
            }
            val now = SystemClock.uptimeMillis()
            if (playing() && !seeking && pos == lastSeen) {
                if (stalledSince == 0L) stalledSince = now
            } else {
                stalledSince = 0
            }
            lastSeen = pos
            showPlayState()
        }
        showRing()
        if (ticks % 4 == 0) slowTick()
    }

    /** Once a second: the download strip, and whether the camera may be used (or used again). */
    private fun slowTick() {
        renderStrip()
        when (show) {
            Show.OPENING, Show.READY -> if (downloadBusy() && (player != null || file != null)) {
                release()
                enter(Show.BUSY)
            }
            Show.BUSY -> if (!downloadBusy()) open()
            Show.LOST -> if (Link.ready) open()
            Show.ERROR -> Unit
        }
    }

    private fun renderStrip() {
        val job = Jobs.all.lastOrNull { it.clip.path == clip.path }
        val active = job != null && Jobs.isWanted(job)
        val saved = clip.name in DownloadService.savedNames(this)
        savedRow.visibility = if (saved && !active) View.VISIBLE else View.GONE
        downloadBtn.visibility = if (active) View.GONE else View.VISIBLE
        progressBox.visibility = if (active) View.VISIBLE else View.GONE
        val note = problem?.let { "Android did not start the download ($it). Try again from the clip list." }
        stripNote.visibility = if (note != null && !active) View.VISIBLE else View.GONE
        if (note != null) stripNote.text = note
        if (job == null || !active) return
        progressTitle.text = when (job.state) {
            Jobs.WAITING -> "Waiting for the camera…"
            Jobs.SAVING -> "Saving…"
            else -> "Downloading…"
        }
        val size = job.total
        progressMb.text = if (size > 0) "${DownloadService.mb(job.done)} / ${DownloadService.mb(size)} MB" else "${DownloadService.mb(job.done)} MB"
        val permille = if (size > 0) (job.done.coerceIn(0L, size) * 1000 / size).toInt() else 0
        if (road.progress != permille) road.setProgress(permille, true)
    }

    // ---------------------------------------------------------------- actions

    private fun togglePlay() {
        if (show == Show.ERROR) {
            open()
            return
        }
        val mp = player ?: return
        if (show != Show.READY || !prepared) return
        try {
            if (mp.isPlaying) {
                mp.pause()
                wantPlay = false
            } else {
                mp.start()
                wantPlay = true
            }
        } catch (e: IllegalStateException) {
            failed()
            return
        }
        flash(!wantPlay)
        showPlayState()
    }

    private fun skip(deltaMs: Long) {
        if (show != Show.READY || !prepared || duration <= 0) return
        seekTo((currentPosition() + deltaMs).coerceIn(0, duration))
    }

    private fun seekTo(ms: Long) {
        val mp = player ?: return
        if (!prepared) return
        seeking = true
        position = ms
        showPosition(ms)
        showRing()
        try {
            mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        } catch (e: IllegalStateException) {
            failed()
        }
    }

    private fun download() {
        release()                                          // frees the camera for the download
        problem = DownloadService.enqueue(this, listOf(clip))
        askedAt = if (problem == null) SystemClock.uptimeMillis() else 0L
        renderStrip()
        if (problem == null) enter(Show.BUSY) else open()
    }

    /** The play / pause glyph over the video, briefly (it is feedback, not the control). */
    private fun flash(paused: Boolean) {
        feedbackGlyph.paused = paused
        feedback.animate().cancel()
        feedback.alpha = 0f
        feedback.visibility = View.VISIBLE
        feedback.animate().alpha(1f).setStartDelay(0).setDuration(150).withEndAction {
            feedback.animate().alpha(0f).setStartDelay(350).setDuration(150).withEndAction { feedback.visibility = View.GONE }.start()
        }.start()
    }

    // ---------------------------------------------------------------- building the screen

    private fun build() {
        frame = FrameLayout(this)
        root = LinearLayout(this)
        root.clipChildren = false
        frame.addView(root, FrameLayout.LayoutParams(match, match))
        frame.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            root.setPadding(bars.left, bars.top, bars.right, 0)
            insetBottom = bars.bottom
            padBottom()
            insets
        }
        left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.clipChildren = false
        root.addView(left, LinearLayout.LayoutParams(match, match))
        right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        right.clipChildren = false
        topBar = buildTopBar()
        left.addView(topBar, LinearLayout.LayoutParams(match, wrap))
        videoFrame = buildVideo()
        left.addView(videoFrame, LinearLayout.LayoutParams(match, wrap))
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(16f))
        body.clipChildren = false
        scroll.addView(body, ViewGroup.LayoutParams(match, wrap))
        transport = buildTransport()
        facts = buildFacts()
        strip = buildStrip()
        bubble = TextView(this)
        Type.osd(bubble, color = t.text1)
        bubble.background = Glass.surface(this, 3, Corners.PILL)
        bubble.setPadding(dpi(10f), dpi(4f), dpi(10f), dpi(4f))
        bubble.visibility = View.GONE
        frame.addView(bubble, FrameLayout.LayoutParams(wrap, wrap))
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = dpi(56f)
        bar.setPadding(dpi(8f), dpi(4f), dpi(16f), dpi(4f))
        val back = ImageButton(this)
        back.id = R.id.player_back
        back.contentDescription = "Back"
        back.setImageResource(R.drawable.ic_back)
        back.imageTintList = ColorStateList.valueOf(t.text1)
        back.background = Glass.iconButton(this)
        back.setOnClickListener { finish() }
        bar.addView(back, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        val title = TextView(this)
        title.id = R.id.player_title
        title.text = clip.time.ifEmpty { clip.name }
        Type.title(title, screen = true)
        title.setSingleLine()
        title.ellipsize = TextUtils.TruncateAt.END
        val sub = TextView(this)
        sub.text = folder.ifEmpty { if (photo) "Photo" else "Clip" }
        Type.caption(sub)
        titles.addView(title)
        titles.addView(sub)
        bar.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dpi(12f) })
        return bar
    }

    private fun buildVideo(): AspectFrame {
        val f = AspectFrame(this)
        f.setBackgroundColor(Color.BLACK)
        f.contentDescription = if (photo) "Photo" else "Video. Tap to play or pause."
        Glass.clipTo(f, Corners.VIDEO)
        Glass.elevate(f, 2f)
        f.setOnClickListener { if (!photo) togglePlay() }
        video = TextureView(this)
        video.id = R.id.player_video
        video.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                if (show == Show.OPENING) open()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                release()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        f.addView(video, FrameLayout.LayoutParams(match, match))
        photoView = ImageView(this)
        photoView.id = R.id.player_photo
        photoView.scaleType = ImageView.ScaleType.FIT_CENTER
        photoView.visibility = View.GONE
        photoView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        f.addView(photoView, FrameLayout.LayoutParams(match, match))
        // The frame's own lines over the picture: hairline and viewfinder corners.
        val lines = View(this)
        lines.background = LayerDrawable(arrayOf(MediaChrome.hairline(this, Corners.VIDEO, 0.30f), BracketsDrawable.video(this)))
        f.addView(lines, FrameLayout.LayoutParams(match, match))
        val stamp = TextView(this)
        Type.osd(stamp, onVideo = true)
        stamp.text = clip.time.replace('-', '/')
        f.addView(stamp, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dpi(14f)
            bottomMargin = dpi(12f)
        })
        ring = ProgressBar(this)
        ring.isIndeterminate = true
        ring.indeterminateTintList = ColorStateList.valueOf(withAlpha(Color.WHITE, 0.80f))
        ring.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        f.addView(ring, FrameLayout.LayoutParams(dpi(40f), dpi(40f), Gravity.CENTER))
        feedbackGlyph = PlayGlyphDrawable.feedback(this)
        feedback = ImageView(this)
        feedback.setImageDrawable(feedbackGlyph)
        feedback.visibility = View.GONE
        feedback.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        f.addView(feedback, FrameLayout.LayoutParams(dpi(56f), dpi(56f), Gravity.CENTER))
        errorBox = stateBox()
        val errorIcon = errorBox.getChildAt(0) as ImageView
        errorIcon.setImageDrawable(getDrawable(R.drawable.ic_warning)?.mutate()?.also { it.setTint(td.errorText) })
        errorTitle = errorBox.getChildAt(1) as TextView
        errorTitle.id = R.id.player_error
        errorTitle.text = if (photo) "Can't show this photo" else "Can't play this clip"
        val errorDetail = errorBox.getChildAt(2) as TextView
        errorDetail.id = R.id.player_error_detail
        errorDetail.text = "Try downloading it instead."
        f.addView(errorBox, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        noticeBox = stateBox()
        noticeIcon = noticeBox.getChildAt(0) as ImageView
        noticeTitle = noticeBox.getChildAt(1) as TextView
        noticeTitle.id = R.id.player_busy
        noticeDetail = noticeBox.getChildAt(2) as TextView
        noticeDetail.id = R.id.player_busy_detail
        f.addView(noticeBox, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        return f
    }

    /** A small dark glass card over the video: icon, title, detail. */
    private fun stateBox(): LinearLayout {
        val box = LinearLayout(dark)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.background = Glass.surface(dark, 3, Corners.SUB)
        box.setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(16f))
        box.visibility = View.GONE
        val icon = ImageView(dark)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        box.addView(icon, LinearLayout.LayoutParams(dpi(28f), dpi(28f)).apply { bottomMargin = dpi(8f) })
        val title = TextView(dark)
        Type.title(title)
        title.gravity = Gravity.CENTER
        box.addView(title, LinearLayout.LayoutParams(wrap, wrap))
        val detail = TextView(dark)
        Type.body(detail)
        detail.gravity = Gravity.CENTER
        box.addView(detail, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(2f) })
        return box
    }

    private fun buildTransport(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Glass.surface(this, 3, Corners.SUB)
        Glass.elevate(card, 6f)
        card.setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(16f))
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        elapsed = TextView(this)
        elapsed.id = R.id.player_elapsed
        Type.osd(elapsed)
        elapsed.text = MediaChrome.clock(position)
        total = TextView(this)
        total.id = R.id.player_total
        Type.osd(total)
        total.text = MediaChrome.clock(0)
        seek = SeekBar(this)
        seek.id = R.id.player_seek
        seek.contentDescription = "Position in the clip"
        seek.progressDrawable = track()
        seek.thumb = laneMarker()
        seek.splitTrack = false
        seek.setPadding(dpi(12f), 0, dpi(12f), 0)
        seek.max = 1
        seek.setOnSeekBarChangeListener(scrub)
        row.addView(elapsed)
        row.addView(seek, LinearLayout.LayoutParams(0, dpi(48f), 1f))
        row.addView(total)
        card.addView(row, LinearLayout.LayoutParams(match, wrap))
        val buttons = LinearLayout(this)
        buttons.gravity = Gravity.CENTER
        back10 = roundButton(R.id.player_back10, R.drawable.ic_replay_10, "Back 10 seconds") { skip(-SKIP_MS) }
        play = ImageButton(this)
        play.id = R.id.player_play
        play.background = Glass.buttonFilled(this, 64f)
        play.imageTintList = ColorStateList.valueOf(t.onAccent)
        play.scaleType = ImageView.ScaleType.FIT_CENTER
        play.setPadding(dpi(18f), dpi(18f), dpi(18f), dpi(18f))
        play.setOnClickListener { togglePlay() }
        Glass.pressable(play)
        fwd10 = roundButton(R.id.player_fwd10, R.drawable.ic_forward_10, "Forward 10 seconds") { skip(SKIP_MS) }
        buttons.addView(back10, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        buttons.addView(play, LinearLayout.LayoutParams(dpi(64f), dpi(64f)).apply { setMargins(dpi(24f), 0, dpi(24f), 0) })
        buttons.addView(fwd10, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        card.addView(buttons, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(4f) })
        return card
    }

    private fun roundButton(id: Int, icon: Int, label: String, action: () -> Unit): ImageButton {
        val b = ImageButton(this)
        b.id = id
        b.contentDescription = label
        b.setImageResource(icon)
        b.imageTintList = ColorStateList.valueOf(t.text1)
        b.background = Glass.iconButton(this)
        b.setOnClickListener { action() }
        Glass.pressable(b)
        return b
    }

    /** The seek track: 4dp, text1 at 20%, the buffered part in accent at 40%, the played part in accent (5.10). */
    private fun track(): Drawable {
        fun line(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(2f)
        }
        val layers = LayerDrawable(
            arrayOf(
                line(withAlpha(t.text1, 0.20f)),
                ClipDrawable(line(withAlpha(t.accent, 0.40f)), Gravity.START, ClipDrawable.HORIZONTAL),
                ClipDrawable(line(t.accent), Gravity.START, ClipDrawable.HORIZONTAL),
            ),
        )
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.secondaryProgress)
        layers.setId(2, android.R.id.progress)
        for (i in 0..2) {
            layers.setLayerGravity(i, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            layers.setLayerHeight(i, dpi(4f))
        }
        return layers
    }

    /** The thumb, a lane marker: a 6 x 24dp accent bar with a white hairline, 6 x 30dp while held. */
    private fun laneMarker(): Drawable {
        fun bar(heightDp: Float) = GradientDrawable().apply {
            setColor(t.accent)
            cornerRadius = dp(3f)
            setStroke(dpi(1f), withAlpha(Color.WHITE, 0.90f))
            setSize(dpi(6f), dpi(heightDp))
        }
        // Both states are 30dp tall, so the thumb keeps its place; the resting bar is inset to 24dp.
        return StateListDrawable().apply {
            setEnterFadeDuration(120)
            setExitFadeDuration(120)
            addState(intArrayOf(android.R.attr.state_pressed), bar(30f))
            addState(intArrayOf(), InsetDrawable(bar(24f), 0, dpi(3f), 0, dpi(3f)))
        }
    }

    private val scrub = object : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar) {
            tracking = true
            tens = sb.progress / SKIP_MS
            bubble.text = MediaChrome.clock(sb.progress.toLong())
            bubble.visibility = View.VISIBLE
            bubble.post { placeBubble() }
        }

        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            val text = MediaChrome.clock(progress.toLong())
            elapsed.text = text
            bubble.text = text
            placeBubble()
            if (progress / SKIP_MS != tens) {
                tens = progress / SKIP_MS
                sb.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
            }
            if (!tracking) seekTo(progress.toLong())             // keys and accessibility actions
        }

        override fun onStopTrackingTouch(sb: SeekBar) {
            tracking = false
            bubble.visibility = View.GONE
            seekTo(sb.progress.toLong())
        }
    }

    /** The time bubble while scrubbing: above the thumb, in the window's own coordinates. */
    private fun placeBubble() {
        val at = IntArray(2)
        val base = IntArray(2)
        seek.getLocationInWindow(at)
        frame.getLocationInWindow(base)
        val inner = seek.width - seek.paddingLeft - seek.paddingRight
        val fraction = if (seek.max > 0) seek.progress.toFloat() / seek.max else 0f
        val x = at[0] - base[0] + seek.paddingLeft + inner * fraction
        bubble.translationX = x - bubble.width / 2f
        bubble.translationY = (at[1] - base[1] - bubble.height + dpi(2f)).toFloat()
    }

    private fun buildFacts(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = Glass.card(this)
        Glass.elevate(card, 2f)
        card.setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(18f))
        val size = if (clip.size >= 0) DownloadService.mb(clip.size) + " MB" else "unknown"
        val length = if (photo) "photo" else if (clip.secs.isNotEmpty()) clip.secs + " s" else "—"
        card.addView(pair("Recorded", clip.time.ifEmpty { "—" }, "Length", length))
        card.addView(pair("Size", size, "Resolution", clip.res.ifEmpty { "—" }))
        card.addView(pair("Folder", folder.ifEmpty { "—" }, null, null))
        val name = cell("File name", clip.name)
        val value = name.getChildAt(1) as TextView
        Type.osd(value, color = t.text1)
        value.ellipsize = TextUtils.TruncateAt.MIDDLE
        card.addView(name, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(12f) })
        return card
    }

    private fun pair(a: String, av: String, b: String?, bv: String?): View {
        val row = LinearLayout(this)
        row.addView(cell(a, av), LinearLayout.LayoutParams(0, wrap, 1f))
        if (b != null && bv != null) row.addView(cell(b, bv), LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dpi(12f) })
        row.setPadding(0, dpi(6f), 0, dpi(6f))
        return row
    }

    private fun cell(label: String, value: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val l = TextView(this)
        l.text = label
        Type.overline(l)
        val v = TextView(this)
        v.text = value
        Type.label(v)
        v.setSingleLine()
        v.ellipsize = TextUtils.TruncateAt.END
        factValues[label] = v
        c.addView(l)
        c.addView(v, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(2f) })
        return c
    }

    private fun buildStrip(): LinearLayout {
        val s = LinearLayout(this)
        s.orientation = LinearLayout.VERTICAL
        savedRow = LinearLayout(this)
        savedRow.gravity = Gravity.CENTER_VERTICAL
        val stamp = TextView(this)
        stamp.id = R.id.player_saved
        stamp.text = "Saved"
        Stamp.apply(stamp)
        val note = TextView(this)
        note.id = R.id.player_saved_note
        note.text = "Saved earlier"
        Type.caption(note)
        savedRow.addView(stamp)
        savedRow.addView(note, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dpi(10f) })
        savedRow.visibility = View.GONE
        s.addView(savedRow, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(12f) })
        stripNote = TextView(this)
        Type.caption(stripNote, t.errorText)
        stripNote.visibility = View.GONE
        s.addView(stripNote, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(10f) })
        downloadBtn = MediaChrome.button(this, "Download", R.id.player_download, Glass.buttonFilled(this, 56f), t.onAccent)
        downloadBtn.setOnClickListener { download() }
        s.addView(downloadBtn, LinearLayout.LayoutParams(match, dpi(56f)))
        progressBox = LinearLayout(this)
        progressBox.orientation = LinearLayout.VERTICAL
        progressBox.visibility = View.GONE
        val head = LinearLayout(this)
        head.gravity = Gravity.CENTER_VERTICAL
        progressTitle = TextView(this)
        progressTitle.id = R.id.player_progress
        progressTitle.text = "Downloading…"
        Type.meta(progressTitle, t.text1)
        progressMb = TextView(this)
        Type.osd(progressMb)
        head.addView(progressTitle, LinearLayout.LayoutParams(0, wrap, 1f))
        head.addView(progressMb)
        progressBox.addView(head, LinearLayout.LayoutParams(match, wrap))
        road = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        road.progressDrawable = RoadProgressDrawable.create(this)
        road.isIndeterminate = false
        road.max = 1000
        road.minHeight = dpi(8f)
        road.maxHeight = dpi(8f)
        progressBox.addView(road, LinearLayout.LayoutParams(match, dpi(8f)).apply { topMargin = dpi(10f) })
        val stop = MediaChrome.button(this, "Stop", R.id.player_stop, Glass.buttonTonal(this), t.text1)
        stop.setOnClickListener {
            DownloadService.stopAll(this)
            askedAt = 0
            renderStrip()
        }
        progressBox.addView(stop, LinearLayout.LayoutParams(match, dpi(52f)).apply { topMargin = dpi(12f) })
        s.addView(progressBox, LinearLayout.LayoutParams(match, wrap))
        return s
    }

    /**
     * Puts the pieces where the screen's shape wants them: one column on a phone held upright, two columns sideways
     * or on a wide screen. The video frame never changes parent: that would end its surface and the playback.
     */
    private fun arrange() {
        val c = resources.configuration
        val nowWide = c.orientation == Configuration.ORIENTATION_LANDSCAPE || c.screenWidthDp >= 600
        if (nowWide == wide) return
        wide = nowWide
        // Everything that moves is taken out; the left column itself stays where it is, video frame and all.
        for (v in listOf(transport, facts, strip, scroll)) (v.parent as? ViewGroup)?.removeView(v)
        root.removeView(right)
        if (nowWide) {
            root.orientation = LinearLayout.HORIZONTAL
            left.layoutParams = LinearLayout.LayoutParams(0, match, 6f)
            videoFrame.layoutParams = LinearLayout.LayoutParams(match, 0, 1f).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(dpi(16f), dpi(4f), dpi(8f), dpi(12f))
            }
            if (!photo) left.addView(transport, LinearLayout.LayoutParams(match, wrap).apply { setMargins(dpi(16f), 0, dpi(8f), dpi(16f)) })
            body.addView(facts, LinearLayout.LayoutParams(match, wrap))
            right.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            right.addView(strip, LinearLayout.LayoutParams(match, wrap).apply { setMargins(dpi(8f), 0, dpi(16f), dpi(16f)) })
            strip.background = Glass.panel(this, floating = true)
            root.addView(right, LinearLayout.LayoutParams(0, match, 4f))
        } else {
            root.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(match, match)
            videoFrame.layoutParams = LinearLayout.LayoutParams(match, wrap).apply { setMargins(dpi(16f), dpi(4f), dpi(16f), dpi(12f)) }
            if (!photo) body.addView(transport, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(12f) })
            body.addView(facts, LinearLayout.LayoutParams(match, wrap))
            left.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            left.addView(strip, LinearLayout.LayoutParams(match, wrap))
            strip.background = Glass.panel(this)
        }
        Glass.elevate(strip, 6f)
        padBottom()
    }

    /** The navigation bar's inset goes inside the download strip, so the glass runs down behind the bar. */
    private fun padBottom() {
        val floating = wide == true
        strip.setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f) + if (floating) 0 else insetBottom)
        left.setPadding(0, 0, 0, if (floating) insetBottom else 0)
        right.setPadding(0, 0, 0, if (floating) insetBottom else 0)
    }
}

/** A frame that takes the largest size of [aspect] (width / height) that fits what its parent offers. */
private class AspectFrame(context: Context) : FrameLayout(context) {
    var aspect = 16f / 9f
        set(value) {
            if (value > 0f && abs(value - field) > 0.001f) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        val maxH = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(heightMeasureSpec)
        var w = if (maxW == Int.MAX_VALUE) (maxH * aspect).roundToInt() else maxW
        var h = (w / aspect).roundToInt()
        if (h > maxH) {
            h = maxH
            w = (h * aspect).roundToInt()
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }
}
