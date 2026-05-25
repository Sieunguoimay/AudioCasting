package com.devicelink.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.devicelink.network.ConnectionState
import com.devicelink.viewmodel.AppMode
import com.devicelink.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestMicPermission: () -> Unit = {},
    hasMicPermission: () -> Boolean = { false },
    onRequestSystemAudio: () -> Unit = {},
    onPickFile: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    // Touchpad overlay
    if (uiState.showTouchpad) {
        TouchpadScreen(
            onMove = { dx, dy -> viewModel.sendTouchpadMove(dx, dy) },
            onTap = { viewModel.sendTouchpadGesture("tap") },
            onTwoFingerTap = { viewModel.sendTouchpadGesture("right_click") },
            onScroll = { dx, dy -> viewModel.sendTouchpadGesture("scroll", dx, dy) },
            onKeyboardInput = { text -> viewModel.sendKeyboardInput(text) },
            onBack = { viewModel.setShowTouchpad(false) }
        )
        return
    }

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
                title = { Text("DeviceLink") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    if (uiState.connectionState == ConnectionState.CONNECTED) {
                        IconButton(onClick = { viewModel.setShowTouchpad(true) }) {
                            Icon(Icons.Default.Mouse, "Touchpad")
                        }
                        BadgedBox(
                            badge = {
                                if (uiState.unreadMessageCount > 0) {
                                    Badge { Text("${uiState.unreadMessageCount}") }
                                }
                            }
                        ) {
                            IconButton(onClick = { viewModel.toggleMessaging() }) {
                                Icon(
                                    if (uiState.showMessaging) Icons.Default.Headphones else Icons.Default.Chat,
                                    if (uiState.showMessaging) "Audio" else "Messages"
                                )
                            }
                        }
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
                            if (uiState.showMessaging) {
                                MessagingPanel(uiState, viewModel, onPickFile)
                            } else {
                                ConnectedView(uiState, viewModel)
                            }
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
