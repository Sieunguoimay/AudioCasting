package com.audiocast

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.audiocast.audio.SystemAudioCapture
import com.audiocast.ui.MainScreen
import com.audiocast.ui.theme.AudioCastTheme
import com.audiocast.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private var pendingViewModel: MainViewModel? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingViewModel?.startBroadcasting()
            }
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                // Must start foreground service BEFORE getMediaProjection on Android 10+
                val serviceIntent = android.content.Intent(this, com.audiocast.service.AudioCaptureService::class.java).apply {
                    putExtra("server_name", pendingViewModel?.uiState?.value?.deviceName ?: "AudioCast")
                    putExtra("client_count", 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }

                // Small delay to ensure service is started before getMediaProjection
                window.decorView.postDelayed({
                    try {
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val projection = mgr.getMediaProjection(result.resultCode, result.data!!)
                        val capture = SystemAudioCapture(projection)
                        pendingViewModel?.startBroadcastingWithCapture(capture)
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "MediaProjection failed: ${e.message}")
                    }
                }, 500)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            AudioCastTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: MainViewModel = viewModel()
                    MainScreen(
                        viewModel = viewModel,
                        onRequestMicPermission = {
                            pendingViewModel = viewModel
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        hasMicPermission = {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                        },
                        onRequestSystemAudio = {
                            pendingViewModel = viewModel
                            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                        }
                    )
                }
            }
        }
    }
}
