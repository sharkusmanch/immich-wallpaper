package dev.immichwall.util

import android.util.Log

/** Thin logging facade so call sites stay uniform and a global prefix is applied. */
object Logg {
    private const val PREFIX = "ImmichWall/"

    fun d(tag: String, msg: String) {
        Log.d(PREFIX + tag, msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(PREFIX + tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(PREFIX + tag, msg, t) else Log.e(PREFIX + tag, msg)
    }
}
