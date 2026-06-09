package com.bbqarmy.tngwmusic_generatingai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.bbqarmy.tngwmusic_generatingai.audio.AudioPlayerManager
import com.bbqarmy.tngwmusic_generatingai.generator.MusicGenerationManager
import com.bbqarmy.tngwmusic_generatingai.ui.MainScreen
import com.bbqarmy.tngwmusic_generatingai.ui.theme.TNGWMusicGeneratingAITheme
import com.bbqarmy.tngwmusic_generatingai.util.NotificationHelper

class MainActivity : ComponentActivity() {
    private lateinit var generationManager: MusicGenerationManager
    private lateinit var playerManager: AudioPlayerManager
    private lateinit var notificationHelper: NotificationHelper

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _: Boolean ->
        // Permission result handled if needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        generationManager = MusicGenerationManager.getInstance(this)
        playerManager = AudioPlayerManager.getInstance(this)
        notificationHelper = NotificationHelper(this)

        checkAndRequestNotificationsPermission()
        
        enableEdgeToEdge()
        setContent {
            TNGWMusicGeneratingAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(
                        generationManager = generationManager,
                        playerManager = playerManager
                    )
                }
            }
        }
    }

    private fun checkAndRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            generationManager.release()
            playerManager.releasePlayer()
        }
    }
}
