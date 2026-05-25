package com.devicelink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devicelink.viewmodel.FileTransferInfo
import com.devicelink.viewmodel.MainViewModel
import com.devicelink.viewmodel.UiState

@Composable
fun FileTransferSection(
    uiState: UiState,
    viewModel: MainViewModel,
    onPickFile: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Send file button
        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AttachFile, null)
            Spacer(Modifier.width(8.dp))
            Text("Send File")
        }

        // Pending offers (incoming)
        if (uiState.pendingFileOffers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Incoming File Offers",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))

            uiState.pendingFileOffers.forEach { offer ->
                FileOfferCard(
                    offer = offer,
                    onAccept = { viewModel.acceptFileOffer(offer.transferId) },
                    onReject = { viewModel.rejectFileOffer(offer.transferId) }
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // Active/completed transfers
        if (uiState.fileTransfers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Transfers",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (uiState.fileTransfers.any { it.isCompleted }) {
                    TextButton(onClick = { viewModel.clearCompletedTransfers() }) {
                        Text("Clear done", fontSize = 12.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            uiState.fileTransfers.forEach { transfer ->
                FileTransferCard(transfer)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun FileOfferCard(
    offer: FileTransferInfo,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FilePresent,
                    null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(offer.fileName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "From ${offer.fromName} - ${formatFileSize(offer.fileSize)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) { Text("Reject") }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f)
                ) { Text("Accept") }
            }
        }
    }
}

@Composable
private fun FileTransferCard(transfer: FileTransferInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (transfer.isIncoming) Icons.Default.Download else Icons.Default.Upload,
                    null,
                    tint = if (transfer.isCompleted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(transfer.fileName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    Text(
                        formatFileSize(transfer.fileSize),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (transfer.isCompleted) {
                    Icon(
                        Icons.Default.CheckCircle,
                        "Complete",
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        "${(transfer.progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!transfer.isCompleted) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
