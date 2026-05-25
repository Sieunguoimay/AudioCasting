package com.devicelink.ui

import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicelink.service.NotificationRelayService
import com.devicelink.viewmodel.MainViewModel
import com.devicelink.viewmodel.UiState

@Composable
fun ConnectingView(uiState: UiState) {
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
fun ColumnScope.ConnectedView(uiState: UiState, viewModel: MainViewModel) {
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

        // Notification relay toggle
        val context = LocalContext.current
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Notification Relay", fontWeight = FontWeight.Medium)
                    Text(
                        "Forward notifications to PC",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    context.startActivity(intent)
                }) {
                    Text(
                        if (NotificationRelayService.isPermissionGranted(context)) "Enabled" else "Enable"
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Touchpad button
        Button(
            onClick = { viewModel.setShowTouchpad(true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Mouse, null)
            Spacer(Modifier.width(8.dp))
            Text("Touchpad")
        }

        Spacer(Modifier.height(12.dp))

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
fun StatRow(label: String, value: String) {
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
