package com.yuanzhou.vlc.vlcplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yuanzhou.vlc.vlcplayer.core.MediaInfo
import com.yuanzhou.vlc.vlcplayer.core.VlcPlayerCore
import com.yuanzhou.vlc.vlcplayer.core.VlcPlayerCoreListener

class PlaybackService : Service(), VlcPlayerCoreListener {
    
    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "vlc_playback_channel"
        private const val SKIP_INTERVAL_MS = 10_000L
        
        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
    
    private lateinit var playerCore: VlcPlayerCore
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: NotificationManager
    
    private val binder = LocalBinder()
    
    private var currentTitle: String = "Video"
    
    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        
        playerCore = VlcPlayerCore.getInstance(this)
        playerCore.addListener(this)
        
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        setupMediaSession()
        
        startForeground(NOTIFICATION_ID, buildNotification(false))
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        playerCore.removeListener(this)
        mediaSession.release()
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for video playback"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "VlcPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    playerCore.play()
                }
                
                override fun onPause() {
                    playerCore.pause()
                }
                
                override fun onSeekTo(pos: Long) {
                    val duration = playerCore.durationMs
                    if (duration > 0) {
                        playerCore.seekTo(pos.toFloat() / duration)
                    }
                }
                
                override fun onFastForward() {
                    playerCore.seekByMs(SKIP_INTERVAL_MS)
                }
                
                override fun onRewind() {
                    playerCore.seekByMs(-SKIP_INTERVAL_MS)
                }
                
                override fun onStop() {
                    playerCore.stop()
                }
            })
            isActive = true
        }
    }
    
    private fun buildNotification(isPlaying: Boolean): Notification {
        val playPauseIntent = if (isPlaying) {
            PipActionReceiver.createPauseIntent(this)
        } else {
            PipActionReceiver.createPlayIntent(this)
        }
        
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_rew, "Rewind", PipActionReceiver.createRewindIntent(this))
            .addAction(playPauseIcon, playPauseTitle, playPauseIntent)
            .addAction(android.R.drawable.ic_media_ff, "Forward", PipActionReceiver.createForwardIntent(this))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setOngoing(isPlaying)
            .build()
    }
    
    private fun updateMediaSessionState() {
        val state = if (playerCore.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (playerCore.isPaused) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_STOPPED
        }
        
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, playerCore.currentTimeMs, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_STOP
            )
            .build()
        
        mediaSession.setPlaybackState(playbackState)
    }
    
    private fun updateMediaSessionMetadata() {
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, playerCore.durationMs)
            .build()
        
        mediaSession.setMetadata(metadata)
    }
    
    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(playerCore.isPlaying))
    }
    
    fun setTitle(title: String) {
        currentTitle = title
        updateMediaSessionMetadata()
        updateNotification()
    }
    
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        Log.d(TAG, "onPlaybackStateChanged: $isPlaying")
        updateMediaSessionState()
        updateNotification()
    }
    
    override fun onProgressChanged(position: Float, currentTimeMs: Long, durationMs: Long) {
        updateMediaSessionState()
    }
    
    override fun onVideoSizeChanged(width: Int, height: Int) {}
    
    override fun onBuffering(buffering: Boolean, bufferPercent: Float) {}
    
    override fun onMediaLoaded(info: MediaInfo) {
        updateMediaSessionMetadata()
    }
    
    override fun onEnded() {
        updateMediaSessionState()
        updateNotification()
    }
    
    override fun onError(error: String) {
        Log.e(TAG, "Playback error: $error")
    }
    
    override fun onStopped() {
        updateMediaSessionState()
        updateNotification()
    }
}
