package com.example.assistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.view.Display
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.accessibility.AccessibilityNodeInfo

/** Lets the assistant tap, scroll and read the screen in any app. User must enable it in Settings > Accessibility. */
class AssistantAccessibilityService : AccessibilityService() {
    companion object { var instance: AssistantAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun perform(action: String, text: String): String = when (action) {
        "back" -> ok(performGlobalAction(GLOBAL_ACTION_BACK))
        "home" -> ok(performGlobalAction(GLOBAL_ACTION_HOME))
        "recents" -> ok(performGlobalAction(GLOBAL_ACTION_RECENTS))
        "notifications" -> ok(performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS))
        "scroll_down" -> ok(scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        "scroll_up" -> ok(scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        "screenshot" -> ok(performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT))
        "lock_screen" -> ok(performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN))
        "tap_text" -> ok(tap(text))
        else -> "Unknown action"
    }

    private fun ok(b: Boolean) = if (b) "Done" else "Couldn't do that"

    private fun scroll(dir: Int): Boolean {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        return find(rootInActiveWindow)?.performAction(dir) ?: false
    }

    private fun tap(text: String): Boolean {
        val node = rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull() ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    /** Screenshot as "IMG:<base64 jpeg>" so Claude can look at the screen. Android 11+. */
    fun screenshotBase64(): String {
        if (Build.VERSION.SDK_INT < 30) return "Looking at the screen needs Android 11 or newer"
        val latch = CountDownLatch(1)
        var result = "Couldn't capture the screen"
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(s: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    s.hardwareBuffer.close()
                    if (bmp != null) {
                        val scale = 1024f / maxOf(bmp.width, bmp.height)
                        val small = if (scale < 1f)
                            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                        val out = ByteArrayOutputStream()
                        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
                        result = "IMG:" + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                } finally { latch.countDown() }
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(6, TimeUnit.SECONDS)
        return result
    }

    fun readScreen(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            n.text?.let { sb.append(it).append('\n') }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString().take(4000).ifBlank { "Nothing readable on screen" }
    }
}
