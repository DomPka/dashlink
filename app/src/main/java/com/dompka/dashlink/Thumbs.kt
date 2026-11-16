package com.dompka.dashlink

import android.content.Context
import android.widget.ImageView

/*
 * CONTRACT (to be implemented): small still pictures of clips on the camera, made on the phone from the clip
 * itself (MediaMetadataRetriever over a RemoteFile), one at a time on a background thread, cached in memory and
 * on disk (cache dir) by path + size + time. Never while a download is running; stops trying for this
 * connection after repeated failures. Photos (.JPG) are decoded directly, downscaled.
 */
internal object Thumbs {
    /** Call once (App.onCreate). */
    fun init(ctx: Context) {
        TODO("contract")
    }

    /**
     * Shows the thumbnail of [clip] in [view] when it is ready (main thread). [view] may be recycled by a list:
     * binding it to another clip cancels the earlier request. [ready] is called on the main thread with true
     * when a picture was set, false when none will come.
     */
    fun bind(view: ImageView, clip: Cam.Clip, ready: (Boolean) -> Unit) {
        TODO("contract")
    }

    /** Forget [view] (it left the screen). */
    fun unbind(view: ImageView) {
        TODO("contract")
    }

    /** While true, no new thumbnail is fetched (the screen is not visible, a live view is open...). */
    @Volatile
    var paused = false
}
