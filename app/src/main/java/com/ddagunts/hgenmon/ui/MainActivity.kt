package com.ddagunts.hgenmon.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ddagunts.hgenmon.AppLog
import com.ddagunts.hgenmon.Notifications
import com.ddagunts.hgenmon.ble.GenClient
import com.ddagunts.hgenmon.ble.GenScanner
import com.ddagunts.hgenmon.data.GenStore
import com.ddagunts.hgenmon.data.PairedGen
import com.ddagunts.hgenmon.protocol.Z44Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GenMonTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

private enum class Screen { MAIN, SETTINGS, PAIR }

/** Stop-after-N-minutes options offered in the timed-stop sheet. */
private val STOP_TIMER_MINUTES = listOf(1, 5, 10, 15, 20, 25, 30, 45, 60, 90, 120, 180)

private val REQUIRED_PERMISSIONS: Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

@Composable
private fun App() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { GenStore(context) }
    val client = remember { GenClient(context) }
    val clientState by client.stateFlow.collectAsStateWithLifecycle()
    val paired by store.paired.collectAsStateWithLifecycle()
    val autoConnect by store.autoConnect.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { Notifications.ensureChannels(context) }

    var screen by remember { mutableStateOf(Screen.MAIN) }
    var outputPower by remember { mutableStateOf<Float?>(null) }
    var engineHours by remember { mutableStateOf<Float?>(null) }
    var wantConnected by remember { mutableStateOf(false) }
    var userInitiatedDisconnect by remember { mutableStateOf(false) }
    var scheduledStopAt by remember { mutableStateOf<Long?>(null) }
    var scheduledStopJob by remember { mutableStateOf<Job?>(null) }
    var stopTimerSheetOpen by remember { mutableStateOf(false) }
    var nowTick by remember { mutableStateOf(System.currentTimeMillis()) }

    // Drive the countdown UI / "Updated …s ago" string. 1 s is plenty.
    LaunchedEffect(scheduledStopAt, clientState.lastRefreshAt) {
        while (true) {
            nowTick = System.currentTimeMillis()
            delay(1000)
        }
    }

    var hasPermissions by remember {
        mutableStateOf(REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> hasPermissions = result.values.all { it } }

    // On launch / when paired list changes: if autoConnect on and we have paired devices, want it.
    LaunchedEffect(autoConnect, paired.isNotEmpty()) {
        if (autoConnect && paired.isNotEmpty() && !clientState.connected) {
            wantConnected = true
        }
    }

    // (Re)connect loop: scan for any paired device and connect. Retries until wantConnected
    // is cleared or we successfully connect.
    LaunchedEffect(wantConnected, clientState.connected, paired.size, hasPermissions) {
        if (!hasPermissions) return@LaunchedEffect
        while (wantConnected && !clientState.connected && paired.isNotEmpty()) {
            AppLog.i("auto: scan attempt for ${paired.size} paired device(s)")
            val targets = paired.map { it.mac }.toSet()
            val found = withTimeoutOrNull(20_000L) {
                GenScanner(context).discoverFlow().first { it.device.address in targets }
            }
            if (found != null) {
                AppLog.i("auto: matched ${found.device.address}, connecting")
                runCatching { client.connect(found.device) }
                    .onFailure { AppLog.e("auto: connect threw", it) }
            } else {
                AppLog.w("auto: no paired device in range; retry in 3s")
            }
            if (!clientState.connected) delay(3000)
        }
    }

    // Poll telemetry while connected. Disconnect after 2 consecutive empty cycles
    // (covers the case where the gen powers off mid-session — BLE supervisor timeout
    // would otherwise take 10–30 s to detect).
    LaunchedEffect(clientState.connected) {
        if (!clientState.connected) {
            outputPower = null
            engineHours = null
            return@LaunchedEffect
        }
        // Reset the user-initiated flag whenever a connection is freshly established.
        userInitiatedDisconnect = false
        var emptyCycles = 0
        var alarmPollTick = 0
        while (clientState.connected) {
            try {
                val w = client.poll(Z44Profile.DataItem.OUTPUT_POWER)
                val h = client.poll(Z44Profile.DataItem.ENGINE_HOURS)
                // Bitfields change rarely — and on some BT module FWs the D-group read isn't
                // supported and fails fast. The EWI indication is the real-time alarm path;
                // these are a fallback snapshot. Poll every 15th cycle (~30 s).
                if (alarmPollTick % 15 == 0) {
                    client.pollRaw(Z44Profile.DataItem.WARNING)
                    client.pollRaw(Z44Profile.DataItem.FAULT)
                }
                alarmPollTick++
                if (w == null && h == null) {
                    emptyCycles++
                    if (emptyCycles >= 2) {
                        AppLog.w("poll: 2 empty cycles, declaring disconnected")
                        client.disconnect()
                        break
                    }
                } else {
                    emptyCycles = 0
                    if (w != null) outputPower = w
                    if (h != null) engineHours = h
                }
            } catch (t: Throwable) {
                AppLog.e("poll loop threw", t)
            }
            delay(2000)
        }
    }

    // Persistent telemetry notification — shows current power + last refresh + active countdown
    // while connected; cleared on disconnect.
    LaunchedEffect(clientState.connected, outputPower, clientState.lastRefreshAt, scheduledStopAt, nowTick) {
        if (clientState.connected) {
            val countdown = scheduledStopAt?.let { at ->
                val remaining = ((at - nowTick) / 1000).coerceAtLeast(0)
                "Stopping in ${formatRemaining(remaining)}"
            }
            Notifications.showStatus(
                ctx = context,
                powerWatts = outputPower,
                lastRefreshAt = clientState.lastRefreshAt,
                nowMs = nowTick,
                countdownLine = countdown,
            )
        } else {
            Notifications.clearStatus(context)
        }
    }

    // Connectivity-loss notification: post when we transition connected → disconnected
    // unless the user pressed Disconnect (or never initiated a connection at all).
    var hadConnectionThisSession by remember { mutableStateOf(false) }
    var lastConnectedLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(clientState.connected) {
        if (clientState.connected) {
            hadConnectionThisSession = true
            lastConnectedLabel = paired.firstOrNull { it.mac == clientState.address }?.displayName()
                ?: clientState.serial
                ?: clientState.address
            return@LaunchedEffect
        }
        // Disconnect branch: only fire the alarm if we'd previously been connected this session.
        if (hadConnectionThisSession && wantConnected && !userInitiatedDisconnect) {
            AppLog.w("notify: connectivity lost")
            Notifications.showConnectivityLoss(context, lastConnectedLabel)
            // If a timed stop was pending, treat it as a failure — we can't deliver the command.
            scheduledStopJob?.cancel()
            scheduledStopJob = null
            if (scheduledStopAt != null) {
                scheduledStopAt = null
                Notifications.showStopFailure(
                    context,
                    "Lost connection before the timed stop could be sent. Engine NOT stopped.",
                )
            }
        }
    }

    // Alarm notifications — fire whenever bitfields go non-zero, or EWI fires.
    var lastAlarmKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(clientState.warningBits, clientState.faultBits, clientState.ewiAt) {
        val key = "${clientState.warningBits}|${clientState.faultBits}|${clientState.ewiAt ?: 0}"
        if (key == lastAlarmKey) return@LaunchedEffect
        lastAlarmKey = key
        val parts = mutableListOf<String>()
        if (clientState.warningBits != 0) parts.add("WARNING bits 0x%02x".format(clientState.warningBits))
        if (clientState.faultBits != 0) parts.add("FAULT bits 0x%04x".format(clientState.faultBits))
        clientState.ewiPayload?.let { p ->
            parts.add("EWI " + p.joinToString(" ") { "%02x".format(it) })
        }
        if (parts.isNotEmpty()) {
            // Bitfield → label table not yet decoded; surface raw values so the user notices.
            // Treat any non-zero state as "could be low oil — go check the generator".
            Notifications.showAlarm(
                ctx = context,
                title = "Generator alarm",
                body = "Check generator immediately. " + parts.joinToString("; "),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scheduledStopJob?.cancel()
            Notifications.clearStatus(context)
            client.disconnect()
        }
    }

    fun cancelScheduledStop() {
        scheduledStopJob?.cancel()
        scheduledStopJob = null
        scheduledStopAt = null
    }

    fun scheduleStopIn(minutes: Int) {
        cancelScheduledStop()
        val fireAt = System.currentTimeMillis() + minutes * 60_000L
        scheduledStopAt = fireAt
        scheduledStopJob = scope.launch {
            try {
                while (true) {
                    val remaining = fireAt - System.currentTimeMillis()
                    if (remaining <= 0) break
                    delay(remaining.coerceAtMost(1000L))
                }
                AppLog.i("timed-stop: firing after $minutes min")
                val ok = runCatching { client.stopEngine() }
                    .onFailure { AppLog.e("timed-stop: stopEngine threw", it) }
                    .getOrDefault(false)
                AppLog.i("timed-stop: stopEngine returned ok=$ok")
                if (ok) {
                    // Engine is shutting down — the BT module will drop in a few seconds.
                    // Treat the impending disconnect as expected so the alarm doesn't fire,
                    // and stop trying to auto-reconnect to an off generator.
                    userInitiatedDisconnect = true
                    wantConnected = false
                    Notifications.showStopSuccess(context)
                } else {
                    Notifications.showStopFailure(
                        context,
                        "Timed stop failed: generator did not acknowledge after 7 attempts.",
                    )
                }
            } finally {
                scheduledStopAt = null
                scheduledStopJob = null
            }
        }
    }

    when (screen) {
        Screen.MAIN -> MainScreen(
            clientState = clientState,
            outputPower = outputPower,
            engineHours = engineHours,
            paired = paired,
            hasPermissions = hasPermissions,
            nowMs = nowTick,
            scheduledStopAt = scheduledStopAt,
            onGrantPermissions = { permLauncher.launch(REQUIRED_PERMISSIONS) },
            onConnect = {
                if (paired.isEmpty()) screen = Screen.PAIR else wantConnected = true
            },
            onDisconnect = {
                userInitiatedDisconnect = true
                wantConnected = false
                cancelScheduledStop()
                client.disconnect()
            },
            onStopEngine = {
                scope.launch {
                    val ok = runCatching { client.stopEngine() }
                        .onFailure { AppLog.e("stopEngine threw", it) }
                        .getOrDefault(false)
                    if (ok) Notifications.showStopSuccess(context)
                    else Notifications.showStopFailure(
                        context,
                        "Generator did not acknowledge stop command after 7 attempts.",
                    )
                }
            },
            onOpenStopTimer = { stopTimerSheetOpen = true },
            onCancelStopTimer = { cancelScheduledStop() },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETTINGS -> SettingsScreen(
            paired = paired,
            autoConnect = autoConnect,
            onBack = { screen = Screen.MAIN },
            onToggleAutoConnect = { store.setAutoConnect(it) },
            onForget = { store.forget(it.mac) },
            onRename = { gen, newLabel -> store.save(gen.copy(label = newLabel)) },
            onPairNew = { screen = Screen.PAIR },
        )
        Screen.PAIR -> PairScreen(
            client = client,
            store = store,
            onBack = { screen = Screen.MAIN },
            onPaired = {
                wantConnected = true
                screen = Screen.MAIN
            },
        )
    }

    if (stopTimerSheetOpen) {
        StopTimerDialog(
            options = STOP_TIMER_MINUTES,
            onDismiss = { stopTimerSheetOpen = false },
            onPick = { minutes ->
                stopTimerSheetOpen = false
                scheduleStopIn(minutes)
            },
        )
    }
}

private fun formatRemaining(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec)
    else "%d:%02d".format(m, sec)
}

private fun formatAgo(epochMs: Long, nowMs: Long): String {
    val ageSec = ((nowMs - epochMs) / 1000).coerceAtLeast(0)
    return when {
        ageSec < 5 -> "just now"
        ageSec < 60 -> "${ageSec}s ago"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        else -> "${ageSec / 3600}h ago"
    }
}

// region — Main screen ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    clientState: GenClient.State,
    outputPower: Float?,
    engineHours: Float?,
    paired: List<PairedGen>,
    hasPermissions: Boolean,
    nowMs: Long,
    scheduledStopAt: Long?,
    onGrantPermissions: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onStopEngine: () -> Unit,
    onOpenStopTimer: () -> Unit,
    onCancelStopTimer: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val pairedGen = paired.firstOrNull { it.mac == clientState.address }
        ?: paired.firstOrNull()
    val genLabel = pairedGen?.displayName() ?: clientState.serial ?: clientState.address ?: "—"
    val alarmActive = clientState.warningBits != 0 || clientState.faultBits != 0 || clientState.ewiPayload != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HGenMon", fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(SettingsIcon, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))
            StatusChip(connected = clientState.connected)
            Spacer(Modifier.weight(0.5f))

            if (clientState.connected) {
                Text(
                    text = outputPower?.let { "%,.0f".format(it) } ?: "—",
                    fontSize = 128.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "watts",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "—",
                    fontSize = 128.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (paired.isEmpty()) "no generator paired" else "tap connect",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.weight(0.5f))

            if (clientState.connected) {
                StatRow(label = "Generator", value = genLabel)
                StatRow(label = "State", value = clientState.driveState.label)
                StatRow(label = "Engine hours", value = engineHours?.let { "%.0f h".format(it) } ?: "—")
                StatRow(
                    label = "Last update",
                    value = clientState.lastRefreshAt?.let { formatAgo(it, nowMs) } ?: "—",
                )
            }

            if (alarmActive) AlarmBanner(clientState)
            if (clientState.connected && scheduledStopAt != null) {
                ScheduledStopBanner(
                    remainingSec = ((scheduledStopAt - nowMs) / 1000).coerceAtLeast(0),
                    onCancel = onCancelStopTimer,
                )
            }

            Spacer(Modifier.weight(1f))

            when {
                !hasPermissions ->
                    PrimaryButton(text = "Grant Bluetooth + notification permissions", onClick = onGrantPermissions)
                clientState.connected -> {
                    PrimaryButton(text = "Stop engine", onClick = onStopEngine, danger = true)
                    Spacer(Modifier.height(10.dp))
                    if (scheduledStopAt == null) {
                        SecondaryButton(text = "Stop in…", onClick = onOpenStopTimer)
                        Spacer(Modifier.height(10.dp))
                    }
                    SecondaryButton(text = "Disconnect", onClick = onDisconnect)
                }
                else ->
                    PrimaryButton(
                        text = if (paired.isEmpty()) "Pair a generator" else "Connect",
                        onClick = onConnect,
                    )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AlarmBanner(state: GenClient.State) {
    val parts = mutableListOf<String>()
    if (state.warningBits != 0) parts.add("WARNING 0x%02x".format(state.warningBits))
    if (state.faultBits != 0) parts.add("FAULT 0x%04x".format(state.faultBits))
    if (state.ewiPayload != null) parts.add("EWI seen")
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Alarm active — check generator", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(parts.joinToString(" · "), fontSize = 12.sp)
        }
    }
}

@Composable
private fun ScheduledStopBanner(remainingSec: Long, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Auto-stop scheduled", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Stops in ${formatRemaining(remainingSec)}", fontSize = 12.sp)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun StopTimerDialog(
    options: List<Int>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop engine after…") },
        text = {
            Column {
                Text(
                    "App must stay running until the timer fires. Connection drop = stop won't be sent.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (m in options) {
                    TextButton(
                        onClick = { onPick(m) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = formatMinutesLabel(m),
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatMinutesLabel(minutes: Int): String = when {
    minutes == 1 -> "1 minute"
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    minutes % 60 == 0 -> "${minutes / 60} hours"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

@Composable
private fun StatusChip(connected: Boolean) {
    val tint = if (connected) Color(0xFF34A853) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).background(tint, CircleShape))
        Text(
            text = if (connected) "Connected" else "Disconnected",
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, danger: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = if (danger) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else ButtonDefaults.buttonColors(),
    ) { Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(text, fontSize = 16.sp)
    }
}

// endregion

// region — Settings screen -----------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    paired: List<PairedGen>,
    autoConnect: Boolean,
    onBack: () -> Unit,
    onToggleAutoConnect: (Boolean) -> Unit,
    onForget: (PairedGen) -> Unit,
    onRename: (PairedGen, String) -> Unit,
    onPairNew: () -> Unit,
) {
    var renaming: PairedGen? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 22.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.padding(end = 8.dp)) {
                    Text("Auto-connect", fontSize = 17.sp)
                    Text(
                        "Find paired generator and connect automatically.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoConnect, onCheckedChange = onToggleAutoConnect)
            }

            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "Paired generators",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            if (paired.isEmpty()) {
                Text("None yet.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                for (g in paired) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        onClick = { renaming = g },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(g.displayName(), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                if (g.label != null) {
                                    Text(g.serial, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(g.mac, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onForget(g) }) { Text("Forget") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SecondaryButton(text = "Pair new generator", onClick = onPairNew)

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "Logs",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LogPanel(modifier = Modifier.weight(1f))
        }
    }

    renaming?.let { gen ->
        RenameDialog(
            current = gen.label ?: "",
            onDismiss = { renaming = null },
            onConfirm = { newLabel ->
                onRename(gen, newLabel)
                renaming = null
            },
        )
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name generator") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(40) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LogPanel(modifier: Modifier = Modifier) {
    val lines = remember { mutableStateOf<List<String>>(emptyList()) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        AppLog.flow.collect { line -> lines.value = (lines.value + line).takeLast(300) }
    }
    LaunchedEffect(lines.value.size) {
        if (lines.value.isNotEmpty()) listState.animateScrollToItem(lines.value.lastIndex)
    }

    Card(modifier = modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF101418))) {
        LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
            items(lines.value) { line ->
                Text(
                    text = line,
                    color = Color(0xFFCFE7CF),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

// endregion

// region — Pair screen ---------------------------------------------------------------------------

private enum class PairStep { SCAN, IDENTIFYING, CONFIRM, DONE, ERROR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairScreen(
    client: GenClient,
    store: GenStore,
    onBack: () -> Unit,
    onPaired: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(PairStep.SCAN) }
    var discovered by remember { mutableStateOf<List<GenScanner.Discovered>>(emptyList()) }
    var selected by remember { mutableStateOf<GenScanner.Discovered?>(null) }
    var resolvedSerial by remember { mutableStateOf<String?>(null) }
    var pendingLabel by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(step) {
        if (step != PairStep.SCAN) return@DisposableEffect onDispose { }
        val scanner = GenScanner(context)
        val job = scanner.discoverFlow()
            .onEach { d -> if (d.isCompatible) discovered = (discovered + d).distinctBy { it.device.address } }
            .launchIn(scope)
        onDispose { job.cancel() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (step) {
                            PairStep.SCAN -> "Pair generator"
                            PairStep.IDENTIFYING -> "Identifying…"
                            PairStep.CONFIRM -> "Confirm"
                            PairStep.DONE -> "Paired"
                            PairStep.ERROR -> "Pairing failed"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(BackIcon, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                PairStep.SCAN -> {
                    Text(
                        "Make sure the generator is on. Tap your generator when it appears.",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (discovered.isEmpty()) {
                        Text("Scanning…", fontSize = 15.sp)
                    } else {
                        for (d in discovered) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    selected = d
                                    step = PairStep.IDENTIFYING
                                    scope.launch {
                                        val ok = try {
                                            client.connect(d.device)
                                        } catch (t: Throwable) {
                                            AppLog.e("pair connect threw", t)
                                            false
                                        }
                                        resolvedSerial = client.stateFlow.value.serial
                                        when {
                                            !ok -> {
                                                errorMessage = "Couldn't connect. Make sure the generator is on and within range."
                                                step = PairStep.ERROR
                                            }
                                            resolvedSerial.isNullOrBlank() -> {
                                                errorMessage = "Connected, but couldn't read the serial number."
                                                step = PairStep.ERROR
                                                client.disconnect()
                                            }
                                            else -> step = PairStep.CONFIRM
                                        }
                                    }
                                },
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                    Text(d.name ?: d.device.address, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${d.device.address}  RSSI ${d.rssi} dBm",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                PairStep.IDENTIFYING -> {
                    Text(
                        "Connecting to ${selected?.name ?: selected?.device?.address ?: "device"}…",
                        fontSize = 15.sp,
                    )
                }

                PairStep.CONFIRM -> {
                    Text(
                        "Found a generator. Give it a name (optional) and pair.",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                resolvedSerial ?: "—",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            selected?.device?.address?.let {
                                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = pendingLabel,
                        onValueChange = { pendingLabel = it.take(40) },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PrimaryButton(text = "Pair this generator", onClick = {
                        val sel = selected ?: return@PrimaryButton
                        val serial = resolvedSerial ?: return@PrimaryButton
                        store.save(PairedGen(
                            mac = sel.device.address,
                            serial = serial,
                            label = pendingLabel.trim().takeIf { it.isNotEmpty() },
                        ))
                        step = PairStep.DONE
                    })
                    SecondaryButton(text = "Not this one", onClick = {
                        client.disconnect()
                        step = PairStep.SCAN
                        discovered = emptyList()
                        resolvedSerial = null
                        pendingLabel = ""
                    })
                }

                PairStep.DONE -> {
                    Text("Saved.", fontSize = 17.sp)
                    PrimaryButton(text = "Done", onClick = onPaired)
                }

                PairStep.ERROR -> {
                    Text(errorMessage ?: "Pairing failed.", fontSize = 15.sp, color = MaterialTheme.colorScheme.error)
                    SecondaryButton(text = "Try again", onClick = {
                        errorMessage = null
                        step = PairStep.SCAN
                        discovered = emptyList()
                        resolvedSerial = null
                    })
                }
            }
        }
    }
}

// endregion

// region — Vector icons --------------------------------------------------------------------------

private val SettingsIcon: ImageVector = ImageVector.Builder(
    name = "Settings", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19.43f, 12.98f)
        curveToRelative(0.04f, -0.32f, 0.07f, -0.64f, 0.07f, -0.98f)
        curveToRelative(0f, -0.34f, -0.03f, -0.66f, -0.07f, -0.98f)
        lineToRelative(2.11f, -1.65f)
        curveToRelative(0.19f, -0.15f, 0.24f, -0.42f, 0.12f, -0.64f)
        lineToRelative(-2f, -3.46f)
        curveToRelative(-0.12f, -0.22f, -0.39f, -0.3f, -0.61f, -0.22f)
        lineToRelative(-2.49f, 1f)
        curveToRelative(-0.52f, -0.4f, -1.08f, -0.73f, -1.69f, -0.98f)
        lineToRelative(-0.38f, -2.65f)
        curveTo(14.46f, 2.18f, 14.25f, 2f, 14f, 2f)
        horizontalLineToRelative(-4f)
        curveToRelative(-0.25f, 0f, -0.46f, 0.18f, -0.49f, 0.42f)
        lineToRelative(-0.38f, 2.65f)
        curveToRelative(-0.61f, 0.25f, -1.17f, 0.59f, -1.69f, 0.98f)
        lineToRelative(-2.49f, -1f)
        curveToRelative(-0.23f, -0.09f, -0.49f, 0f, -0.61f, 0.22f)
        lineToRelative(-2f, 3.46f)
        curveToRelative(-0.13f, 0.22f, -0.07f, 0.49f, 0.12f, 0.64f)
        lineToRelative(2.11f, 1.65f)
        curveToRelative(-0.04f, 0.32f, -0.07f, 0.65f, -0.07f, 0.98f)
        curveToRelative(0f, 0.33f, 0.03f, 0.66f, 0.07f, 0.98f)
        lineToRelative(-2.11f, 1.65f)
        curveToRelative(-0.19f, 0.15f, -0.24f, 0.42f, -0.12f, 0.64f)
        lineToRelative(2f, 3.46f)
        curveToRelative(0.12f, 0.22f, 0.39f, 0.3f, 0.61f, 0.22f)
        lineToRelative(2.49f, -1f)
        curveToRelative(0.52f, 0.4f, 1.08f, 0.73f, 1.69f, 0.98f)
        lineToRelative(0.38f, 2.65f)
        curveToRelative(0.03f, 0.24f, 0.24f, 0.42f, 0.49f, 0.42f)
        horizontalLineToRelative(4f)
        curveToRelative(0.25f, 0f, 0.46f, -0.18f, 0.49f, -0.42f)
        lineToRelative(0.38f, -2.65f)
        curveToRelative(0.61f, -0.25f, 1.17f, -0.59f, 1.69f, -0.98f)
        lineToRelative(2.49f, 1f)
        curveToRelative(0.23f, 0.09f, 0.49f, 0f, 0.61f, -0.22f)
        lineToRelative(2f, -3.46f)
        curveToRelative(0.12f, -0.22f, 0.07f, -0.49f, -0.12f, -0.64f)
        lineToRelative(-2.11f, -1.65f)
        close()
        moveTo(12f, 15.5f)
        curveToRelative(-1.93f, 0f, -3.5f, -1.57f, -3.5f, -3.5f)
        reflectiveCurveToRelative(1.57f, -3.5f, 3.5f, -3.5f)
        reflectiveCurveToRelative(3.5f, 1.57f, 3.5f, 3.5f)
        reflectiveCurveToRelative(-1.57f, 3.5f, -3.5f, 3.5f)
        close()
    }
}.build()

private val BackIcon: ImageVector = ImageVector.Builder(
    name = "Back", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(20f, 11f)
        horizontalLineTo(7.83f)
        lineToRelative(5.59f, -5.59f)
        lineTo(12f, 4f)
        lineToRelative(-8f, 8f)
        lineToRelative(8f, 8f)
        lineToRelative(1.41f, -1.41f)
        lineTo(7.83f, 13f)
        horizontalLineTo(20f)
        verticalLineToRelative(-2f)
        close()
    }
}.build()

// endregion
