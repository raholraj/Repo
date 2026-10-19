package com.remotehotspot.admin

import android.content.Context

/** What the model decided: which phone ("all" allowed) and on / off / toggle. */
data class AiCall(val device: String?, val state: String?)

/**
 * Stub: on-device AI model is optional. Typed Hinglish commands work without it via RuleInterpreter.
 */
class LlmInterpreter(private val ctx: Context) {
    val isReady: Boolean get() = false

    suspend fun load(modelPath: String) { /* no-op without litertlm */ }

    suspend fun interpret(text: String): AiCall? = null

    fun close() {}
}
