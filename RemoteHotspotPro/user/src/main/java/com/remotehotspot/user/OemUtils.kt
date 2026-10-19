package com.remotehotspot.user

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class Oem { REALME, SAMSUNG, INFINIX, OTHER }

/** OEM detection + deep links into auto-start / battery screens (best effort; falls back to App Info). */
object OemUtils {
    fun detect(): Oem {
        val s = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            "realme" in s || "oppo" in s || "oneplus" in s -> Oem.REALME
            "samsung" in s -> Oem.SAMSUNG
            "infinix" in s || "tecno" in s || "itel" in s || "transsion" in s -> Oem.INFINIX
            else -> Oem.OTHER
        }
    }

    fun instructions(oem: Oem): String = when (oem) {
        Oem.REALME -> "Realme UI:\n1) Settings > Apps > App management > this app > Battery usage > allow Background activity + Auto-launch.\n2) Settings > Battery > turn OFF 'Optimize battery use' for this app.\n3) Recent apps: long-press this app's card and tap the lock icon."
        Oem.SAMSUNG -> "Samsung One UI:\n1) Settings > Battery and device care > Battery > Background usage limits > Never sleeping apps > add this app.\n2) Settings > Apps > this app > Battery > Unrestricted.\n3) Turn OFF 'Put unused apps to sleep'."
        Oem.INFINIX -> "Infinix XOS:\n1) Phone Master / Settings > App Management > Auto-start > enable this app.\n2) Settings > Battery > Battery Lab > App battery management > this app > No restrictions.\n3) Recent apps: pull the app card down to lock it."
        Oem.OTHER -> "Allow Auto-start / Unrestricted battery for this app in your phone's battery settings, and lock it in Recent apps."
    }

    private val candidates = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.itel.autostart" to "com.itel.autostart.MainActivity",
    )

    fun openAutoStart(ctx: Context) {
        for ((pkg, cls) in candidates) {
            val i = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) != null && runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
