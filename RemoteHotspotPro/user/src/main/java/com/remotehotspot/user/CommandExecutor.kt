package com.remotehotspot.user

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.remotehotspot.shared.*

/** Executes commands received from Firebase. Authorization is enforced by the database rules. */
object CommandExecutor {
    fun battery(ctx: Context): Pair<Int, Boolean> {
        val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return (if (lvl >= 0) lvl * 100 / scale else -1) to (st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    }

    suspend fun run(ctx: Context, c: Command, repo: FirebaseRepo): Reply {
        val (bat, chg) = battery(ctx)
        fun r(ok: Boolean, msg: String, hs: Boolean? = HotspotAccessibilityService.isHotspotOn(ctx)) = Reply(c.id, ok, msg, hs, bat, chg)

        val reply = when (c.cmd) {
            Cmd.PING, Cmd.GET_STATUS -> r(true, "OK")
            Cmd.HOTSPOT_ON, Cmd.HOTSPOT_OFF, Cmd.TOGGLE -> {
                val svc = HotspotAccessibilityService.instance
                if (svc == null) r(false, "Accessibility service is not enabled") else {
                    val res = svc.setHotspot(when (c.cmd) { Cmd.HOTSPOT_ON -> true; Cmd.HOTSPOT_OFF -> false; else -> null })
                    r(res.ok, res.msg, res.state)
                }
            }
            else -> r(false, "Unknown command")
        }
        if (c.cmd != Cmd.PING) repo.writeStatus(Prefs(ctx).deviceId, bat, chg, reply.hotspot, "${c.cmd}: ${if (reply.ok) "OK" else reply.msg}")
        return reply
    }
}
