package com.glasshome.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's YouTube button opens the YouTube app you use. With the stock
 * YouTube app turned off the button does nothing; this sends it to the app
 * that opens YouTube links instead (TizenTube, say). Turned on with
 * scripts/install.sh --youtube-button. It only sees key presses.
 */
class RemoteButtons : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isYouTubeButton(event)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openYouTube()
        return true
    }

    /**
     * The Chromecast with Google TV remote's YouTube button arrives as
     * KEYCODE_BUTTON_3 (HID usage 0x0C0077). Game controllers can send that code
     * too, so only the remote's counts.
     */
    private fun isYouTubeButton(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_3) return false
        val name = InputDevice.getDevice(event.deviceId)?.name ?: return false
        return name.contains("remote", ignoreCase = true)
    }

    private fun openYouTube() {
        val pkg = Shortcuts.youtubeApp(packageManager) ?: return
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

}
