package com.remotehotspot.admin

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remotehotspot.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*

/** Auto-discovers every device whose lastSeen is recent. No pairing. */
class AdminViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = FirebaseRepo()

    data class Plan(val text: String, val cmd: String, val targets: List<DeviceInfo>, val via: String)

    private val raw = MutableStateFlow<List<DeviceInfo>>(emptyList())
    private val tick = MutableStateFlow(0)
    val loading = MutableStateFlow(true)
    val busy = MutableStateFlow<Set<String>>(emptySet())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val aiStatus = MutableStateFlow("Typed Hinglish commands work: 'realme ka hotspot band karo', 'sab on'")
    val aiBusy = MutableStateFlow(false)
    val plan = MutableStateFlow<Plan?>(null)

    val devices: StateFlow<List<DeviceInfo>> = combine(raw, tick) { list, _ ->
        val now = repo.now()
        list.filter { now - it.lastSeen < ONLINE_WINDOW_MS }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        notifChannel()
        viewModelScope.launch { while (isActive) { delay(10_000); tick.value++ } }

        viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    if (BuildConfig.ADMIN_KEY.isBlank()) {
                        log("ADMIN_KEY was not set when this APK was built")
                        loading.value = false
                        delay(60_000)
                        continue
                    }
                    repo.registerAdmin(BuildConfig.ADMIN_KEY)
                    attempt = 0
                    repo.observeAllDevices().collect { raw.value = it; loading.value = false }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    log("Connection failed: ${e.message}")
                    loading.value = false
                    delay(backoffMs(attempt++))
                }
            }
        }

        viewModelScope.launch {
            var known: Map<String, String>? = null
            devices.collect { list ->
                val cur = list.associate { it.deviceId to it.name }
                known?.let { prev ->
                    (cur.keys - prev.keys).forEach { log("${cur[it]} connected"); notify(it, "${cur[it]} connected") }
                    (prev.keys - cur.keys).forEach { log("${prev[it]} went offline"); notify(it, "${prev[it]} went offline") }
                }
                known = cur
            }
        }
    }

    fun refresh() { tick.value++; toast.tryEmit("Refreshed") }

    fun command(d: DeviceInfo, cmd: String) = viewModelScope.launch { runCommand(d, cmd) }

    fun bulk(cmd: String) = viewModelScope.launch {
        val targets = devices.value
        if (targets.isEmpty()) { toast.tryEmit("No online devices"); return@launch }
        targets.map { d -> async { runCommand(d, cmd) } }.awaitAll()
    }

    private suspend fun runCommand(d: DeviceInfo, cmd: String) {
        busy.update { it + d.deviceId }
        val c = Command(UUID.randomUUID().toString().take(12), cmd)
        val reply = runCatching { repo.sendCommand(d.deviceId, c) }.getOrNull()
        busy.update { it - d.deviceId }
        val msg = reply?.let { "${d.name}: ${if (it.ok) "OK" else "Failed"} - ${it.msg}" }
            ?: "${d.name}: unreachable"
        log(msg); toast.tryEmit(msg)
    }

    fun ask(text: String) = viewModelScope.launch {
        val t = text.trim()
        if (t.isBlank()) return@launch
        aiBusy.value = true
        try {
            val devs = devices.value
            val parsed = RuleInterpreter.parse(t, devs)
            if (parsed.cmd != null && parsed.targets.isNotEmpty()) {
                plan.value = Plan(t, parsed.cmd, parsed.targets, "rules")
            } else {
                toast.tryEmit("Samajh nahi aaya. Aise likho: 'realme ka hotspot band karo' ya 'sab on'")
            }
        } finally { aiBusy.value = false }
    }

    fun confirmPlan() {
        val p = plan.value ?: return
        plan.value = null
        viewModelScope.launch {
            p.targets.map { d -> async { runCommand(d, p.cmd) } }.awaitAll()
        }
    }

    fun cancelPlan() { plan.value = null }

    fun exportLogs(): String = logs.value.joinToString("\n")

    private fun log(m: String) {
        logs.update { (listOf("${fmt.format(Date())}  $m") + it).take(500) }
    }

    private fun notifChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getApplication<Application>().getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel("conn", "Device connections", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun notify(id: String, text: String) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(app, "conn")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Hotspot Admin")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }
}
