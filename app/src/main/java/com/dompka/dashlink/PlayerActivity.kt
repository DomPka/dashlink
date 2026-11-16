package com.dompka.dashlink

import android.app.Activity
import android.content.Context
import android.content.Intent

/*
 * CONTRACT (to be implemented): plays a clip straight from the camera's SD card (MediaPlayer with a
 * MediaDataSource over RemoteFile), with play/pause, seek, the clip's facts and a Download button.
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
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_PATH, clip.path).putExtra(EXTRA_SIZE, clip.size).putExtra(EXTRA_TIME, clip.time)
                    .putExtra(EXTRA_RES, clip.res).putExtra(EXTRA_SECS, clip.secs).putExtra(EXTRA_FOLDER, folder)
            )
        }
    }
}
