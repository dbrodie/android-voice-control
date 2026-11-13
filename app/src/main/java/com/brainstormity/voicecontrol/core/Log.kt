package com.brainstormity.voicecontrol.core

import android.util.Log

/** Tiny logging helper: every tag is prefixed with `VC/` so `scripts/logs.sh` can filter on it. */
object VcLog {
    private fun tag(t: String) = if (t.startsWith("VC/")) t else "VC/$t"
    fun d(tag: String, msg: String) { Log.d(tag(tag), msg) }
    fun i(tag: String, msg: String) { Log.i(tag(tag), msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) { Log.w(tag(tag), msg, t) }
    fun e(tag: String, msg: String, t: Throwable? = null) { Log.e(tag(tag), msg, t) }
}
