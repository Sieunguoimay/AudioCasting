package com.devicelink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TouchpadScreen(
    onMove: (dx: Float, dy: Float) -> Unit,
    onTap: () -> Unit,
    onTwoFingerTap: () -> Unit,
    onScroll: (dx: Float, dy: Float) -> Unit,
    onKeyboardInput: (text: String) -> Unit,
    onBack: () -> Unit,
    sensitivity: Float = 1.5f
) {
    var showKeyboard by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf(TextFieldValue("")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("Back", color = Color(0xFF00D4FF))
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("Touchpad", color = Color.White, fontSize = 18.sp)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { showKeyboard = !showKeyboard }) {
                Text(if (showKeyboard) "Hide KB" else "Show KB", color = Color(0xFF00D4FF))
            }
        }

        // Touchpad surface
        @Suppress("UNUSED_VARIABLE")
        var fingerCount by remember { mutableIntStateOf(0) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF12122A))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        onMove(dragAmount.x * sensitivity / 2f, dragAmount.y * sensitivity / 2f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onTwoFingerTap() } // Long press as right-click fallback
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "Drag to move mouse\nTap to click\nLong press to right-click",
                color = Color(0xFF555570),
                fontSize = 14.sp
            )
        }

        // Keyboard input
        if (showKeyboard) {
            BasicTextField(
                value = keyboardText,
                onValueChange = { new ->
                    val added = new.text.removePrefix(keyboardText.text)
                    if (added.isNotEmpty()) {
                        onKeyboardInput(added)
                    }
                    keyboardText = new
                },
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A2E))
                    .padding(16.dp)
            )
        }

        // Button bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A2E))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onTap,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
            ) { Text("Left Click") }
            Button(
                onClick = onTwoFingerTap,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
            ) { Text("Right Click") }
            Button(
                onClick = { onScroll(0f, -3f) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
            ) { Text("Scroll Up") }
            Button(
                onClick = { onScroll(0f, 3f) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A4A))
            ) { Text("Scroll Down") }
        }
    }
}
