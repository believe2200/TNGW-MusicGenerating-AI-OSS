package com.bbqarmy.tngwmusic_generatingai.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bbqarmy.tngwmusic_generatingai.MainActivity

class NotificationHelper(private val context: Context) {
    companion object {
        const val CHANNEL_ID = "music_gen_channel"
        const val NOTIFICATION_ID = 101
        
        const val ACTION_PAUSE = "com.bbqarmy.tngwmusic_generatingai.PAUSE"
        const val ACTION_RESUME = "com.bbqarmy.tngwmusic_generatingai.RESUME"
        const val ACTION_STOP = "com.bbqarmy.tngwmusic_generatingai.STOP"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Music Generation"
            val descriptionText = "Notifications for music generation status"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showCompletionNotification(fileName: String) {
        val builder = createBaseBuilder()
            .setContentTitle("Music Generated")
            .setContentText("Finished generating: $fileName")
            .setAutoCancel(true)

        notify(builder.build())
    }

    fun getOngoingNotification(isPaused: Boolean): android.app.Notification {
        val builder = createBaseBuilder()
            .setContentTitle(if (isPaused) "Generation Paused" else "Generating Music...")
            .setSmallIcon(if (isPaused) android.R.drawable.ic_media_pause else android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        // Add Actions
        if (isPaused) {
            val resumeIntent = Intent(context, com.bbqarmy.tngwmusic_generatingai.service.MusicGenerationService::class.java).apply {
                action = ACTION_RESUME
            }
            val resumePending = PendingIntent.getService(context, 1, resumeIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePending)
        } else {
            val pauseIntent = Intent(context, com.bbqarmy.tngwmusic_generatingai.service.MusicGenerationService::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePending = PendingIntent.getService(context, 2, pauseIntent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePending)
        }

        val stopIntent = Intent(context, com.bbqarmy.tngwmusic_generatingai.service.MusicGenerationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(context, 3, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)

        return builder.build()
    }

    private fun createBaseBuilder(): NotificationCompat.Builder {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setPriority(NotificationCompat.PRIORITY_LOW) // Ongoing usually low
            .setContentIntent(pendingIntent)
    }

    private fun notify(notification: android.app.Notification) {
        with(NotificationManagerCompat.from(context)) {
            try {
                notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Permission not granted
            }
        }
    }
}
