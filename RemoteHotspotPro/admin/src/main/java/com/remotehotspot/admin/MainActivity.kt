package com.remotehotspot.admin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotehotspot.shared.Cmd
import com.remotehotspot.shared.DeviceInfo
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val vm: AdminViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF4DD0E1))
            else lightColorScheme(primary = Color(0xFF00796B))
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { AdminScreen(vm) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminScreen(vm: AdminViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val aiBusy by vm.aiBusy.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle(initialValue = null)
    val ctx = LocalContext.current
    var showLogs by remember { mutableStateOf(false) }
    var cmdText by remember { mutableStateOf("") }

    val snack = remember { SnackbarHostState() }
    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it) } }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hotspot Admin") },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { showLogs = true }) { Icon(Icons.Filled.History, "Logs") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Command bar
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = cmdText,
                    onValueChange = { cmdText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("e.g. sab on / realme band karo") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.ask(cmdText); cmdText = "" }, enabled = !aiBusy && cmdText.isNotBlank()) {
                    Text(if (aiBusy) "..." else "Go")
                }
            }

            if (devices.isNotEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { vm.bulk(Cmd.HOTSPOT_ON) }, label = { Text("All ON") })
                    AssistChip(onClick = { vm.bulk(Cmd.HOTSPOT_OFF) }, label = { Text("All OFF") })
                    AssistChip(onClick = { vm.bulk(Cmd.TOGGLE) }, label = { Text("All Toggle") })
                }
            }

            when {
                loading && devices.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                devices.isEmpty() -> EmptyState()
                else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(devices, key = { it.deviceId }) { d ->
                        DeviceCard(d, d.deviceId in busy) { cmd -> vm.command(d, cmd) }
                    }
                }
            }
        }
    }

    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Event log") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (logs.isEmpty()) item { Text("No events yet") }
                    items(logs) { Text(it, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            },
            confirmButton = { TextButton({ showLogs = false }) { Text("Close") } }
        )
    }

    plan?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.cancelPlan() },
            title = { Text("Confirm") },
            text = {
                Text("${p.cmd} on ${p.targets.joinToString { it.name }}?")
            },
            confirmButton = { TextButton({ vm.confirmPlan() }) { Text("Send") } },
            dismissButton = { TextButton({ vm.cancelPlan() }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun EmptyState() = Column(
    Modifier.fillMaxSize().padding(32.dp),
    Arrangement.Center,
    Alignment.CenterHorizontally
) {
    Icon(Icons.Filled.WifiTethering, null, Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(16.dp))
    Text(
        "Install User app and turn on Accessibility. Devices appear here automatically.",
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun DeviceCard(d: DeviceInfo, busy: Boolean, onCmd: (String) -> Unit) {
    val time = DateFormat.getTimeInstance()
    Card(Modifier.fillMaxWidth().animateContentSize(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(0xFF00897B), CircleShape))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                    Text("${d.manufacturer} ${d.model}", style = MaterialTheme.typography.bodySmall)
                }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Battery: ${if (d.battery >= 0) "${d.battery}%" else "?"}${if (d.charging) " ⚡" else ""}")
                Text("Hotspot: ${when (d.hotspot) { true -> "ON"; false -> "OFF"; null -> "?" }}")
            }
            if (d.lastSeen > 0) Text("Last seen: ${time.format(Date(d.lastSeen))}", style = MaterialTheme.typography.bodySmall)
            if (d.lastCmd.isNotEmpty()) Text("Last command: ${d.lastCmd}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onCmd(Cmd.HOTSPOT_ON) }, enabled = !busy) { Text("ON") }
                Button(onClick = { onCmd(Cmd.HOTSPOT_OFF) }, enabled = !busy) { Text("OFF") }
                OutlinedButton(onClick = { onCmd(Cmd.TOGGLE) }, enabled = !busy) { Text("Toggle") }
            }
        }
    }
}
