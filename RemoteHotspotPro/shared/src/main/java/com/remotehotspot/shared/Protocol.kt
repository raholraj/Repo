package com.remotehotspot.shared

/** Commands the Admin can send. */
object Cmd {
    const val HOTSPOT_ON = "HOTSPOT_ON"
    const val HOTSPOT_OFF = "HOTSPOT_OFF"
    const val TOGGLE = "TOGGLE"
    const val GET_STATUS = "GET_STATUS"
    const val PING = "PING"
}

const val HEARTBEAT_MS = 30_000L
/** A device is shown in Admin only if its lastSeen is newer than this. */
const val ONLINE_WINDOW_MS = 90_000L

data class Command(val id: String, val cmd: String, val arg: String = "")

data class Reply(
    val id: String, val ok: Boolean, val msg: String = "",
    val hotspot: Boolean? = null, val battery: Int = -1, val charging: Boolean = false
)

/** Device snapshot shown on an Admin card. */
data class DeviceInfo(
    val deviceId: String, val name: String = "", val model: String = "", val manufacturer: String = "",
    val battery: Int = -1, val charging: Boolean = false, val hotspot: Boolean? = null,
    val lastSeen: Long = 0, val lastCmd: String = "", val lastCmdTs: Long = 0
)

/** Exponential backoff: 2s, 4s, 8s ... capped at 5 min. */
fun backoffMs(attempt: Int): Long = (2_000L shl attempt.coerceIn(0, 7)).coerceAtMost(300_000L)
