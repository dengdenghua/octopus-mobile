package com.apk.claw.android.utils

import android.util.Log

object XLog {
    @JvmField var DEBUG = true

    @JvmStatic
    fun setDEBUG(debug: Boolean) {
        DEBUG = debug
    }

    private fun redact(msg: String?): String? = SecretRedactor.redact(msg)

    @JvmStatic fun i(tag: String, msg: String?) { if (DEBUG && msg != null) Log.i(tag, redact(msg) ?: msg) }
    @JvmStatic fun i(tag: String, msg: String?, tr: Throwable) { if (DEBUG) Log.i(tag, redact(msg), tr) }
    @JvmStatic fun d(tag: String, msg: String?) { if (DEBUG && msg != null) Log.d(tag, redact(msg) ?: msg) }
    @JvmStatic fun d(tag: String, msg: String?, tr: Throwable) { if (DEBUG) Log.d(tag, redact(msg), tr) }
    @JvmStatic fun e(tag: String, msg: String?) { if (msg != null) Log.e(tag, redact(msg) ?: msg) }
    @JvmStatic fun e(tag: String, msg: String?, tr: Throwable) { Log.e(tag, redact(msg), tr) }
    @JvmStatic fun e(tag: String, tr: Throwable) { Log.e(tag, "", tr) }
    @JvmStatic fun w(tag: String, msg: String?) { if (DEBUG && msg != null) Log.w(tag, redact(msg) ?: msg) }
    @JvmStatic fun w(tag: String, msg: String?, tr: Throwable) { if (DEBUG) Log.w(tag, redact(msg), tr) }
    @JvmStatic fun w(tag: String, tr: Throwable) { if (DEBUG) Log.w(tag, tr) }
    @JvmStatic fun v(tag: String, msg: String?) { if (DEBUG && msg != null) Log.v(tag, redact(msg) ?: msg) }
    @JvmStatic fun v(tag: String, msg: String?, tr: Throwable) { if (DEBUG) Log.v(tag, redact(msg), tr) }
    @JvmStatic fun wtf(tag: String, msg: String?) { if (DEBUG) Log.wtf(tag, redact(msg)) }
    @JvmStatic fun wtf(tag: String, msg: String?, tr: Throwable) { if (DEBUG) Log.wtf(tag, redact(msg), tr) }
    @JvmStatic fun wtf(tag: String, tr: Throwable) { if (DEBUG) Log.wtf(tag, tr) }
}
