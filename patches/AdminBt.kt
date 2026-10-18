package com.remotehotspot.admin

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.remotehotspot.shared.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AdminBt(private val ctx: Context) {
    private val sockets = ConcurrentHashMap<String, BluetoothSocket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun isBonded(btName: String): Boolean {
        if (btName.isBlank()) return false
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return false
        return a.bondedDevices?.any { it.name == btName } == true
    }

    @SuppressLint("MissingPermission")
    suspend fun send(dev: DeviceInfo, c: Command, timeoutMs: Long = 12_000L): Reply? = withContext(Dispatchers.IO) {
        if (dev.btName.isBlank()) return@withContext null
        val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return@withContext null
        val d = a.bondedDevices?.firstOrNull { it.name == dev.btName } ?: return@withContext null
        withTimeoutOrNull(timeoutMs) {
            val mutex = Any()
            synchronized(mutex) {
                var cached = sockets[dev.deviceId]
                var guard: Job? = null
                try {
                    if (cached == null || !cached.isConnected) {
                        runCatching { cached?.close() }
                        a.cancelDiscovery()
                        val fresh = d.createRfcommSocketToServiceRecord(UUID.fromString(BT_SERVICE_UUID))
                        cached = fresh
                        guard = scope.launch { delay(8_000); runCatching { fresh.close() } }
                        fresh.connect(); guard.cancel(); sockets[dev.deviceId] = fresh
                    }
                    val sock = cached ?: return@withTimeoutOrNull null
                    guard = scope.launch { delay(timeoutMs); runCatching { sock.close() } }
                    sock.outputStream.write((c.toJson() + "\n").toByteArray()); sock.outputStream.flush()
                    val line = sock.inputStream.bufferedReader().readLine()
                    guard.cancel()
                    return@withTimeoutOrNull line?.let { Reply.fromJson(it) }
                } catch (e: Exception) {
                    guard?.cancel(); runCatching { cached?.close() }; sockets.remove(dev.deviceId)
                    null
                }
            }
        }
    }

    fun closeAll() { sockets.values.forEach { runCatching { it.close() } }; sockets.clear() }
}
