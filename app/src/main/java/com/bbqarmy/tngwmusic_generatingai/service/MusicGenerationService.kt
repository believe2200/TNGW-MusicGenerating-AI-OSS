package com.bbqarmy.tngwmusic_generatingai.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.bbqarmy.tngwmusic_generatingai.generator.MusicGenerationManager
import com.bbqarmy.tngwmusic_generatingai.ui.GeneratedTrack
import com.bbqarmy.tngwmusic_generatingai.util.NotificationHelper
import com.bbqarmy.tngwmusic_generatingai.util.TrackHistoryManager
import kotlinx.coroutines.*
import java.io.File
import android.content.pm.ServiceInfo
import android.os.Build

class MusicGenerationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var generationManager: MusicGenerationManager
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var historyManager: TrackHistoryManager

    override fun onCreate() {
        super.onCreate()
        generationManager = MusicGenerationManager.getInstance(this)
        notificationHelper = NotificationHelper(this)
        historyManager = TrackHistoryManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            handleAction(action)
            return START_NOT_STICKY
        }

        val prompt = intent?.getStringExtra("prompt") ?: ""
        val modelDir = intent?.getStringExtra("modelDir")?.let { File(it) }
        val duration = intent?.getFloatExtra("duration", 10f) ?: 10f
        val steps = intent?.getIntExtra("steps", 25) ?: 25
        val cfgScale = intent?.getFloatExtra("cfgScale", 4.5f) ?: 4.5f
        val sampler = intent?.getStringExtra("sampler") ?: "pingpong"
        val seed = intent?.getLongExtra("seed", 0L) ?: 0L

        if (modelDir != null && prompt.isNotEmpty()) {
            lastPrompt = prompt
            startForegroundService(prompt)
            
            serviceScope.launch {
                // Collect paused state and update notification (only on state change)
                val statusJob = launch {
                    generationManager.isPaused.collect { paused ->
                        val notification = notificationHelper.getOngoingNotification(paused)
                        startForegroundCompat(notification)
                    }
                }

                val resultFile = generationManager.generateMusic(
                    prompt, modelDir, duration, steps, cfgScale, sampler, seed,
                )
                
                statusJob.cancel()
                
                if (resultFile != null) {
                    val actualDurationMs = (((resultFile.length() - 44) * 1000.0) / 176400.0).toLong()
                    val newTrack = GeneratedTrack(resultFile, prompt, seed, actualDurationMs)
                    
                    val currentHistory = historyManager.loadHistory().toMutableList()
                    currentHistory.add(0, newTrack)
                    historyManager.saveHistory(currentHistory)

                    notificationHelper.showCompletionNotification(resultFile.name)
                }
                
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun handleAction(action: String) {
        when (action) {
            NotificationHelper.ACTION_PAUSE -> {
                generationManager.pauseGeneration()
                updateNotification()
            }
            NotificationHelper.ACTION_RESUME -> {
                generationManager.resumeGeneration()
                updateNotification()
            }
            NotificationHelper.ACTION_STOP -> {
                generationManager.stopGeneration()
            }
        }
    }

    private var lastPrompt = ""
    private fun updateNotification() {
        val isPaused = generationManager.isPaused.value
        val notification = notificationHelper.getOngoingNotification(isPaused)
        startForegroundCompat(notification)
    }

    private fun startForegroundService(prompt: String) {
        lastPrompt = prompt
        val notification = notificationHelper.getOngoingNotification(isPaused = false)
        startForegroundCompat(notification)
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
