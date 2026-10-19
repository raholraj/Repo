package com.remotehotspot.user

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.remotehotspot.shared.*
import kotlinx.coroutines.*

/**
 * Persistent foreground service:
 *  - waits until the Accessibility service is enabled, then registers this phone under /devices/{id}
 *  - heartbeat every 30 s (battery, charging, hotspot, lastSeen)
 *  - listens for HOTSPOT_ON / HOTSPOT_OFF / TOGGLE commands
 *  - restarts itself if the task is removed
 */
class CoreService : Service() {
    companion object {
        const val CH = "core"
        fun start(ctx: Context) { runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, CoreService::class.java)) } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { FirebaseRepo() }
    private var stopListening: (() -> Unit)? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CH, "Device connection", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Hotspot Device active").setContentText("Visible to Admin and ready for commands")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) { started = true; scope.launch { run() } }
        return START_STICKY
    }

    private suspend fun run() {
        val prefs = Prefs(this)
        var attempt = 0
        while (scope.isActive) {
            try {
                if (HotspotAccessibilityService.instance == null) { delay(5000); continue }
                repo.uid()
                repo.registerDevice(prefs.deviceId, "${Build.MANUFACTURER} ${Build.MODEL}", Build.MODEL, Build.MANUFACTURER, prefs.token)
                if (stopListening == null) stopListening = repo.observeCommands(prefs.deviceId) { c -> scope.launch { handle(c) } }
                attempt = 0
                while (scope.isActive && HotspotAccessibilityService.instance != null) {
                    val (b, c) = CommandExecutor.battery(this)
                    repo.writeStatus(prefs.deviceId, b, c, HotspotAccessibilityService.isHotspotOn(this))
                    delay(HEARTBEAT_MS)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { HotspotAccessibilityService.log("core loop: ${e.message}"); delay(backoffMs(attempt++)) }
        }
    }

    private suspend fun handle(c: Command) {
        val reply = runCatching { CommandExecutor.run(this, c, repo) }.getOrElse { Reply(c.id, false, "Error: ${it.message}") }
        repo.writeResult(Prefs(this).deviceId, reply)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val pi = PendingIntent.getService(this, 7, Intent(this, CoreService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        (getSystemService(ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 2000, pi)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() { stopListening?.invoke(); scope.cancel(); start(applicationContext); super.onDestroy() }
    override fun onBind(i: Intent?): IBinder? = null
}
