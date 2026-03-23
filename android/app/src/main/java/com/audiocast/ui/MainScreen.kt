package com.audiocast.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.audiocast.data.ProfileManager
import com.audiocast.network.ConnectionState
import com.audiocast.network.DiscoveredServer
import com.audiocast.viewmodel.AppMode
import com.audiocast.viewmodel.CaptureSource
import com.audiocast.viewmodel.MainViewModel
import com.audiocast.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit = {},
    hasMicPermission: () -> Boolean = { false },
    onRequestSystemAudio: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // PIN Dialog
    if (uiState.showPinDialog) {
        PinDialog(
            pin = uiState.pinInput,
            onPinChange = { viewModel.updatePinInput(it) },
            onSubmit = { viewModel.submitPin(uiState.pinInput) },
            onDismiss = { viewModel.dismissPinDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AudioCast") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (uiState.connectionState == ConnectionState.CONNECTED) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.Default.Close, "Disconnect")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Mode toggle: Receive / Send
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = uiState.appMode == AppMode.RECEIVER,
                        onClick = { viewModel.switchMode(AppMode.RECEIVER) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Headphones, null, modifier = Modifier.size(18.dp)) }
                    ) { Text("Receive") }
                    SegmentedButton(
                        selected = uiState.appMode == AppMode.SENDER,
                        onClick = { viewModel.switchMode(AppMode.SENDER) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp)) }
                    ) { Text("Send") }
                }
            }

            when (uiState.appMode) {
                AppMode.RECEIVER -> {
                    when (uiState.connectionState) {
                        ConnectionState.DISCONNECTED, ConnectionState.ERROR, ConnectionState.AUTH_REQUIRED -> {
                            DisconnectedView(uiState, viewModel)
                        }
                        ConnectionState.CONNECTING -> {
                            ConnectingView(uiState)
                        }
                        ConnectionState.CONNECTED -> {
                            ConnectedView(uiState, viewModel)
                        }
                    }
                }
                AppMode.SENDER -> {
                    SenderView(uiState, viewModel, onRequestMicPermission, hasMicPermission, onRequestSystemAudio)
                }
            }
        }
    }
}

@Composable
private fun PinDialog(
    pin: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "PIN Required",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Enter the server PIN to connect",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = onPinChange,
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onSubmit,
                        modifier = Modifier.weight(1f),
                        enabled = pin.isNotEmpty()
                    ) {
                        Text("Connect")
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.DisconnectedView(uiState: UiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
    ) {
        // Error / Reconnecting message
        if (uiState.isReconnecting) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2D1A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Reconnecting... (attempt ${uiState.reconnectAttempt})",
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }

        uiState.errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1A1A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Device name
        OutlinedTextField(
            value = uiState.deviceName,
            onValueChange = { viewModel.updateDeviceName(it) },
            label = { Text("Device Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Phone, null) }
        )

        Spacer(Modifier.height(24.dp))

        // Discovered servers section
        Text(
            "Discovered Servers",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

        if (uiState.servers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Searching for servers...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            uiState.servers.forEach { server ->
                ServerCard(server) { viewModel.connectToServer(server) }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Saved servers section
        if (uiState.savedServers.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Saved Servers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            uiState.savedServers.forEach { profile ->
                SavedServerCard(
                    profile = profile,
                    onClick = { viewModel.connectSavedServer(profile) },
                    onRemove = { viewModel.removeSavedServer(profile) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        Spacer(Modifier.height(24.dp))

        // Manual connection section
        Text(
            "Manual Connection",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.manualHost,
                onValueChange = { viewModel.updateManualHost(it) },
                label = { Text("IP Address") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                placeholder = { Text("192.168.1.100") }
            )
            OutlinedTextField(
                value = uiState.manualPort,
                onValueChange = { viewModel.updateManualPort(it) },
                label = { Text("Port") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { viewModel.connectManually() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text("Connect")
        }

        Spacer(Modifier.height(24.dp))

        // Settings section
        Text(
            "Settings",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Auto-reconnect toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Auto-Reconnect", fontWeight = FontWeight.Medium)
                        Text(
                            "Reconnect when connection drops",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoReconnect,
                        onCheckedChange = { viewModel.setAutoReconnect(it) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Adaptive buffer toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Adaptive Buffer", fontWeight = FontWeight.Medium)
                        Text(
                            "Auto-adjust buffer based on network",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.adaptiveBuffer,
                        onCheckedChange = { viewModel.setAdaptiveBuffer(it) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Volume group
                OutlinedTextField(
                    value = uiState.volumeGroup,
                    onValueChange = { viewModel.setVolumeGroup(it) },
                    label = { Text("Volume Group") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g., Living Room") },
                    leadingIcon = { Icon(Icons.Default.Groups, null) },
                    supportingText = { Text("Link volume with other devices in the same group") }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ServerCard(server: DiscoveredServer, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Computer,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    server.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text(
                    "${server.host}:${server.port}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Text(
                    "${server.codec.uppercase()} | ${server.sampleRate}Hz | ${server.channels}ch",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            Icon(
                Icons.Default.PlayArrow,
                "Connect",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SavedServerCard(
    profile: ProfileManager.ServerProfile,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.History,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    profile.name.ifEmpty { "${profile.host}:${profile.port}" },
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "${profile.host}:${profile.port}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectingView(uiState: UiState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 4.dp
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "Connecting to ${uiState.connectedServer?.name ?: "server"}...",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                uiState.connectedServer?.let { "${it.host}:${it.port}" } ?: "",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ColumnScope.ConnectedView(uiState: UiState, viewModel: MainViewModel) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
    ) {
        // Now playing header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF4CAF50))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Connected", color = Color(0xFF4CAF50), fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    uiState.connectedServer?.name ?: "Server",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "${uiState.connectionInfo.codec.uppercase()} | ${uiState.connectionInfo.sampleRate}Hz | ${uiState.connectionInfo.channels}ch",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.volumeGroup.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Groups, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Group: ${uiState.volumeGroup}",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Volume slider
        Text("Volume", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VolumeDown, null, modifier = Modifier.size(20.dp))
            Slider(
                value = uiState.volume,
                onValueChange = { viewModel.setVolume(it) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            Icon(Icons.Default.VolumeUp, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("${(uiState.volume * 100).toInt()}%", fontSize = 14.sp)
        }

        Spacer(Modifier.height(16.dp))

        // Buffer slider
        Text(
            "Buffer: ${uiState.bufferMs}ms" +
                if (uiState.adaptiveBuffer && uiState.bufferStats.adaptiveTargetMs > 0)
                    " (adaptive target: ${uiState.bufferStats.adaptiveTargetMs}ms)"
                else "",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
        Slider(
            value = uiState.bufferMs.toFloat(),
            onValueChange = { viewModel.setBufferMs(it.toInt()) },
            valueRange = 10f..200f,
            steps = 18
        )

        Spacer(Modifier.height(24.dp))

        // Stats
        Text("Statistics", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Buffer Depth", "${uiState.bufferStats.currentDepthMs}ms")
                StatRow("Frames Received", "${uiState.bufferStats.framesReceived}")
                StatRow("Frames Played", "${uiState.bufferStats.framesPlayed}")
                StatRow("Frames Lost", "${uiState.bufferStats.framesLost}")
                StatRow("Frames Dropped", "${uiState.bufferStats.framesDropped}")
                if (uiState.adaptiveBuffer) {
                    StatRow("Network Jitter", "${uiState.bufferStats.networkJitterMs.toInt()}ms")
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Disconnect button
        OutlinedButton(
            onClick = { viewModel.disconnect() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Close, null)
            Spacer(Modifier.width(8.dp))
            Text("Disconnect")
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

// ──── Sender Mode UI ────

@Composable
private fun ColumnScope.SenderView(
    uiState: UiState,
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit,
    hasMicPermission: () -> Boolean,
    onRequestSystemAudio: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
    ) {
        // Error message
        uiState.errorMessage?.let { error ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1A1A)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(12.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Device name (used as server name)
        OutlinedTextField(
            value = uiState.deviceName,
            onValueChange = { viewModel.updateDeviceName(it) },
            label = { Text("Server Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Phone, null) },
            enabled = !uiState.isBroadcasting
        )

        Spacer(Modifier.height(16.dp))

        // Audio source selector
        Text("Audio Source", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = uiState.captureSource == CaptureSource.MICROPHONE,
                onClick = { viewModel.updateCaptureSource(CaptureSource.MICROPHONE) },
                label = { Text("Microphone") },
                leadingIcon = { Icon(Icons.Default.Mic, null, modifier = Modifier.size(18.dp)) },
                enabled = !uiState.isBroadcasting
            )
            FilterChip(
                selected = uiState.captureSource == CaptureSource.SYSTEM_AUDIO,
                onClick = { viewModel.updateCaptureSource(CaptureSource.SYSTEM_AUDIO) },
                label = { Text("System Audio") },
                leadingIcon = { Icon(Icons.Default.MusicNote, null, modifier = Modifier.size(18.dp)) },
                enabled = !uiState.isBroadcasting && android.os.Build.VERSION.SDK_INT >= 29
            )
        }

        if (uiState.captureSource == CaptureSource.SYSTEM_AUDIO && android.os.Build.VERSION.SDK_INT < 29) {
            Text(
                "System audio requires Android 10+",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // Server config
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = uiState.senderPort,
                onValueChange = { viewModel.updateSenderPort(it) },
                label = { Text("Port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                enabled = !uiState.isBroadcasting
            )
            OutlinedTextField(
                value = uiState.senderPin,
                onValueChange = { viewModel.updateSenderPin(it) },
                label = { Text("PIN (optional)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = !uiState.isBroadcasting
            )
        }

        Spacer(Modifier.height(24.dp))

        // Start/Stop button
        if (uiState.isBroadcasting) {
            Button(
                onClick = { viewModel.stopBroadcasting() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Stop Broadcasting")
            }
        } else {
            Button(
                onClick = {
                    when (uiState.captureSource) {
                        CaptureSource.MICROPHONE -> {
                            if (hasMicPermission()) {
                                viewModel.startBroadcasting()
                            } else {
                                onRequestMicPermission()
                            }
                        }
                        CaptureSource.SYSTEM_AUDIO -> {
                            onRequestSystemAudio()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (uiState.captureSource == CaptureSource.MICROPHONE) Icons.Default.Mic else Icons.Default.MusicNote,
                    null
                )
                Spacer(Modifier.width(8.dp))
                Text("Start Broadcasting")
            }
        }

        // Broadcasting status
        if (uiState.isBroadcasting) {
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF4444))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Broadcasting", color = Color(0xFFFF4444), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        uiState.deviceName,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "PCM | 48000Hz | 2ch | Port ${uiState.senderPort}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.senderPin.isNotEmpty()) {
                        Text(
                            "PIN protected",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Connected clients
            Text(
                "Connected Clients (${uiState.senderClientCount})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            if (uiState.senderClients.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Text(
                        "Waiting for clients to connect...",
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                uiState.senderClients.forEach { clientInfo ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(clientInfo, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}
