package com.remotehotspot.admin

import com.remotehotspot.shared.Cmd
import com.remotehotspot.shared.DeviceInfo

/** What the Admin typed, turned into an action + target phones. */
data class Parsed(val cmd: String?, val targets: List<DeviceInfo>)

/**
 * Fast offline parser for Hinglish / Hindi / English. Runs BEFORE the AI model because it is instant and
 * always right for simple commands ("realme ka hotspot band karo", "sab on", "सब चालू").
 */
object RuleInterpreter {
    private val ON = setOf("on", "chalu", "chaalu", "chalao", "chala", "start", "enable", "activate", "shuru", "kholo", "khol", "चालू", "चलाओ", "शुरू", "खोलो")
    private val OFF = setOf("off", "band", "bandh", "bund", "stop", "disable", "deactivate", "close", "बंद", "बन्द", "रोको")
    private val TOGGLE = setOf("toggle", "palat", "palto", "badlo", "switch", "पलटो", "बदलो")
    private val ALL = setOf("sab", "sabhi", "saare", "sare", "sabka", "sabke", "all", "everyone", "everything", "सब", "सभी", "सारे")

    fun tokens(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun parse(text: String, devices: List<DeviceInfo>): Parsed {
        val t = tokens(text)
        val on = t.any { it in ON }; val off = t.any { it in OFF }; val tog = t.any { it in TOGGLE }
        val cmd = when {
            tog && !on && !off -> Cmd.TOGGLE
            on && !off -> Cmd.HOTSPOT_ON
            off && !on -> Cmd.HOTSPOT_OFF
            else -> null
        }
        val targets = when {
            t.any { it in ALL } -> devices
            else -> match(text, devices).ifEmpty { if (devices.size == 1) devices else emptyList() }
        }
        return Parsed(cmd, targets)
    }

    fun match(text: String, devices: List<DeviceInfo>): List<DeviceInfo> {
        val words = tokens(text).toSet()
        return devices.filter { d ->
            tokens("${d.name} ${d.model} ${d.manufacturer}").filter { it.length >= 3 }.any { it in words }
        }
    }

    fun isAll(text: String) = tokens(text).any { it in ALL }
}
