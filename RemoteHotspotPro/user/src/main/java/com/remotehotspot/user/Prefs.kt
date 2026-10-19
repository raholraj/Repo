package com.remotehotspot.user

import android.content.Context
import java.util.UUID

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    val deviceId: String get() = sp.getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }
}
