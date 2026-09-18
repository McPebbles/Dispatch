package com.dispatch.reader.util

import android.util.Log

/**
 * Swallow-and-log helpers.
 *
 * Used only around platform calls that can throw for reasons outside the app's
 * control — a WebView that has been destroyed, a missing asset, a
 * CookieManager that is unavailable before WebView init. Never used to hide a
 * logic error.
 */
object Safely {

    const val TAG = "Dispatch"

    inline fun run(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "suppressed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    inline fun <T> call(block: () -> T, fallback: T): T = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "suppressed: ${t.javaClass.simpleName}: ${t.message}")
        fallback
    }
}
