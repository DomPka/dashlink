package com.dompka.dashlink

import android.app.Activity
import android.content.Context
import android.content.Intent

/* CONTRACT (to be implemented): the full-screen live viewer (see the design spec, "full-screen live viewer"). */
class LiveActivity : Activity() {
    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, LiveActivity::class.java))
        }
    }
}
