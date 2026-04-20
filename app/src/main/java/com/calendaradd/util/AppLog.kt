package com.calendaradd.util

import android.util.Log

object AppLog {
    fun i(tag: String, message: String) {
        runSafely { Log.i(tag, message) }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        runSafely {
            if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        runSafely {
            if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    private inline fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // android.util.Log is a stub in local JVM tests.
        }
    }
}
