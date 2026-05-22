package com.ddagunts.hgenmon

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-app log channel: every line is mirrored to Android Log AND to a SharedFlow that
 * the UI subscribes to. Replay buffer lets a new collector see the recent history.
 */
object AppLog {
    private const val TAG = "hgenmon"

    private val _flow = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 200)
    val flow: SharedFlow<String> = _flow.asSharedFlow()

    fun i(message: String) {
        Log.i(TAG, message)
        _flow.tryEmit(stamp() + message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        _flow.tryEmit(stamp() + "W " + message)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
        _flow.tryEmit(stamp() + "E " + message + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    private fun stamp(): String {
        val ms = System.currentTimeMillis() % 100_000
        return "%05d ".format(ms)
    }
}
