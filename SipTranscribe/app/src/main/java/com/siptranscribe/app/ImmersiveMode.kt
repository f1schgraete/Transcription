package com.siptranscribe.app

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hide the status bar, navigation bar, and the large-screen taskbar for a true
 * full-screen kiosk look.
 *
 * Uses "transient bars by swipe": a swipe from a screen edge briefly reveals
 * the bars and they auto-hide again, so the user can never get permanently
 * stuck with the bars covering content.
 *
 * Call this from both `onCreate` (after `setContentView`) and
 * `onWindowFocusChanged(hasFocus = true)`: Android re-shows the system bars
 * whenever the window loses and regains focus (dialogs, the soft keyboard,
 * an incoming call overlay…), so a kiosk has to re-assert immersive mode each
 * time focus comes back.
 */
fun Activity.enterImmersiveMode() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
}
