package com.remotehotspot.user

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HotspotAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HotspotA11y"
        @Volatile var instance: HotspotAccessibilityService? = null
        val logBuffer = ArrayDeque<String>()
        fun log(m: String) {
            Log.d(TAG, m)
            synchronized(logBuffer) {
                logBuffer.addLast("${System.currentTimeMillis()} $m")
                if (logBuffer.size > 300) logBuffer.removeFirst()
            }
        }

        val LABELS = listOf(
            "mobile hotspot", "personal hotspot", "wi-fi hotspot", "wifi hotspot",
            "portable hotspot", "portable wi-fi hotspot", "hotspot", "tethering", "wlan hotspot", "mobile ap"
        )
        val ID_HINTS = listOf("hotspot", "tether", "wifi_ap", "softap")

        fun isHotspotOn(ctx: Context): Boolean? = runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val m = WifiManager::class.java.getDeclaredMethod("isWifiApEnabled")
            m.isAccessible = true
            m.invoke(wm) as Boolean
        }.getOrNull()
    }

    data class Result(val ok: Boolean, val msg: String, val state: Boolean?)

    private val mutex = Mutex()
    private var savedX = -1
    private var savedY = -1

    override fun onServiceConnected() {
        instance = this
        log("service connected")
        CoreService.start(this)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    suspend fun setHotspot(target: Boolean?): Result = mutex.withLock {
        wakeAndUnlock()
        val before = isHotspotOn(this)
        val want = target ?: !(before ?: false)

        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        delay(700)
        swipeDownToExpand()
        delay(400)

        val root = rootInActiveWindow
        val tile = findTile(root)
        if (tile != null) {
            val r = Rect(); tile.getBoundsInScreen(r)
            if (r.width() > 0) { savedX = r.centerX(); savedY = r.centerY() }
            if (!clickNode(tile)) {
                closePanel()
                return Result(false, "Could not click hotspot tile", before)
            }
        } else if (savedX > 0 && savedY > 0) {
            if (!tap(savedX, savedY)) {
                closePanel()
                return Result(false, "Tile not found and saved coords failed", before)
            }
        } else {
            closePanel()
            openTetherSettings()
            return Result(false, "Hotspot tile not found — opened settings", before)
        }

        delay(900)
        closePanel()
        val after = verify(want, tile)
        val ok = after.first
        val state = after.second
        return Result(ok, if (ok) "Hotspot ${if (want) "ON" else "OFF"}" else "State did not change", state)
    }

    private fun wakeAndUnlock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "hs:wake").acquire(3000)
        }
        val kg = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (kg.isKeyguardLocked && Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            kg.newKeyguardLock("hs:kg").disableKeyguard()
        }
    }

    private suspend fun verify(want: Boolean?, tile: AccessibilityNodeInfo?): Pair<Boolean, Boolean?> {
        val state = isHotspotOn(this)
        if (state != null && want != null) return (state == want) to state
        if (state != null) return true to state
        val checked = tile?.isChecked
        if (checked != null && want != null) return (checked == want) to checked
        return true to state
    }

    private fun findTile(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        val all = ArrayList<AccessibilityNodeInfo>()
        collect(root, all)
        for (label in LABELS) {
            all.firstOrNull { n ->
                n.text?.toString()?.lowercase()?.contains(label) == true ||
                    n.contentDescription?.toString()?.lowercase()?.contains(label) == true
            }?.let { return it }
        }
        return all.firstOrNull { n ->
            n.viewIdResourceName?.lowercase()?.let { id -> ID_HINTS.any { it in id } } == true
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        n ?: return
        if (depth > 30) return
        out.add(n)
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private suspend fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var c: AccessibilityNodeInfo? = n
        var hops = 0
        while (c != null && hops < 6) {
            if (c.isClickable && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            c = c.parent
            hops++
        }
        val r = Rect(); n.getBoundsInScreen(r)
        return r.width() > 0 && tap(r.centerX(), r.centerY())
    }

    private suspend fun tap(x: Int, y: Int): Boolean =
        dispatch(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 60)

    private suspend fun swipeDownToExpand() {
        val m = resources.displayMetrics
        dispatch(Path().apply {
            moveTo(m.widthPixels / 2f, 40f)
            lineTo(m.widthPixels / 2f, m.heightPixels * 0.6f)
        }, 300)
    }

    private suspend fun dispatch(p: Path, dur: Long): Boolean {
        var done = false
        var ok = false
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, dur)).build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; done = true }
            override fun onCancelled(d: GestureDescription?) { done = true }
        }, null)
        var waited = 0
        while (!done && waited < 2000) { delay(50); waited += 50 }
        return ok
    }

    private suspend fun closePanel() {
        performGlobalAction(GLOBAL_ACTION_BACK); delay(250)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun openTetherSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
