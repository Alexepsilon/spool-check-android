package com.spoolcheck.app.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Minimal SharedPreferences wrapper for user-toggled scanner behavior.
 * Lives in core/ (not data/) because these are device-local UX
 * preferences, not domain data — they don't belong in Room.
 */
object Prefs {
    private const val FILE = "spool_check_prefs"
    private const val KEY_HAPTIC = "haptic_on_match"

    private fun store(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Buzz on a successful match / scan event. Default ON — most users
     *  want the feedback in noisy environments where they can't hear
     *  audio cues. */
    fun hapticOnMatch(ctx: Context): Boolean =
        store(ctx).getBoolean(KEY_HAPTIC, true)

    fun setHapticOnMatch(ctx: Context, enabled: Boolean) {
        store(ctx).edit().putBoolean(KEY_HAPTIC, enabled).apply()
    }
}
