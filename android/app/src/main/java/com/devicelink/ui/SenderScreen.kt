package com.devicelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.devicelink.viewmodel.CaptureSource
import com.devicelink.viewmodel.MainViewModel
import com.devicelink.viewmodel.UiState

@Composable
fun ColumnScope.SenderView(
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
