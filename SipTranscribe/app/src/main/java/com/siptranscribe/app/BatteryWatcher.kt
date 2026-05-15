package com.siptranscribe.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.BatteryManager
import android.view.View
import android.widget.TextView

/**
 * Renders battery level + charging state into a [TextView] while the host
 * Activity is in the foreground. Used by both [MainActivity] and
 * [CallActivity] so the elderly user can see the battery on the in-app
 * header — under kiosk pinning the system status bar is hidden and that's
 * the only place a battery indicator could live.
 *
 * Optionally also toggles a "↘ Aufladen" hint view when the battery drops
 * below [LOW_THRESHOLD] percent and the charger isn't plugged in. Per
 * design we never post system notifications — the reminder only appears
 * while the user is looking at the app.
 *
 * Lifecycle is owner-driven: call [start] from onResume, [stop] from
 * onPause. unregister is wrapped in a try/catch because the receiver may
 * already have been torn down by the system in low-memory cases.
 */
class BatteryWatcher(
    private val context: Context,
    private val display: TextView?,
    private val chargeHint: View? = null,
    /** Colour used when the level is fine; null keeps the view's current colour. */
    private val normalColor: Int? = null
) {

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { render(intent) }
    }

    fun start() {
        // ACTION_BATTERY_CHANGED is a sticky broadcast; the return value
        // gives the current state without waiting for the next tick.
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        render(sticky)
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
    }

    private fun render(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return
        val pct = level * 100 / scale
        val low = !plugged && pct in 1..LOW_THRESHOLD

        display?.let { tv ->
            val prefix = if (plugged) "⚡ " else ""   // ⚡ when charging
            tv.text = "$prefix$pct %"
            tv.setTextColor(
                if (low) Color.RED else (normalColor ?: tv.currentTextColor)
            )
        }
        chargeHint?.visibility = if (low) View.VISIBLE else View.GONE
    }

    companion object {
        /** Percent threshold at or below which we treat the battery as low. */
        const val LOW_THRESHOLD = 19
    }
}
