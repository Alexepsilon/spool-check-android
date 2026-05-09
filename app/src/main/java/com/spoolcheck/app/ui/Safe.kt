package com.spoolcheck.app.ui

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Wraps a coroutine body in a try/catch so a single broken DB write or
 * unexpected exception doesn't crash the whole app. Logs to logcat and
 * surfaces the failure to the user via a Toast.
 *
 * Use everywhere the UI launches a coroutine that touches Room, the
 * filesystem, or any external API.
 */
fun CoroutineScope.launchSafe(
    ctx: Context,
    tag: String = "Spool",
    block: suspend () -> Unit,
): Job = launch {
    try {
        block()
    } catch (e: Throwable) {
        Log.e(tag, "launchSafe failure", e)
        // Toasts have to be on the main thread.
        withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "Error: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
}
