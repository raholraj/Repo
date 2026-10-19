package com.remotehotspot.user

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : ComponentActivity() {
    override fun onCreate(b: android.os.Bundle?) {
        super.onCreate(b)
        CoreService.start(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF4DD0E1)) else lightColorScheme(primary = Color(0xFF00796B))
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Wizard() } }
        }
    }
}

private fun a11yEnabled(ctx: Context): Boolean {
    val me = ComponentName(ctx, HotspotAccessibilityService::class.java).flattenToString()
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return TextUtils.SimpleStringSplitter(':').apply { setString(s) }.any { it.equals(me, true) }
}

private fun notifGranted(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun batteryIgnored(ctx: Context) =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
private fun Wizard() {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permOk by remember { mutableStateOf(notifGranted(ctx)) }
    var battOk by remember { mutableStateOf(batteryIgnored(ctx)) }
    var a11yOk by remember { mutableStateOf(a11yEnabled(ctx)) }
    val oem = remember { OemUtils.detect() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permOk = notifGranted(ctx)
    }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                permOk = notifGranted(ctx); battOk = batteryIgnored(ctx); a11yOk = a11yEnabled(ctx)
                if (a11yOk) CoreService.start(ctx)
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Device setup", style = MaterialTheme.typography.headlineSmall)
        Text("Finish these steps once. After that this phone connects to your Admin automatically.", style = MaterialTheme.typography.bodyMedium)

        StepCard("1. Permissions", "Notifications", permOk, "Allow") {
            if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        StepCard("2. Battery optimization", "Allow unrestricted background use.\n\n" + OemUtils.instructions(oem), battOk, "Allow") {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
        StepCard("3. Accessibility", "Open Accessibility settings, find 'Hotspot Control' and turn it on.", a11yOk, "Open settings") {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val ready = permOk && battOk && a11yOk
        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp),
            colors = CardDefaults.cardColors(containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4. Ready", style = MaterialTheme.typography.titleMedium)
                Text(if (ready) "✓ All set. This phone is visible to the Admin. You can close this app."
                     else "Complete the steps above. Hotspot control works only while the screen is on and unlocked.")
                if (!ready && a11yOk) Text("Accessibility is on, so the phone is already visible to the Admin.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { OemUtils.openAutoStart(ctx) }) { Text("Open auto-start settings (recommended)") }
            }
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, done: Boolean, action: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                    tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (!done) FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}
