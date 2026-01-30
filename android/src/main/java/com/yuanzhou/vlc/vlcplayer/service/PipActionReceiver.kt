package com.yuanzhou.vlc.vlcplayer.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.yuanzhou.vlc.vlcplayer.core.VlcPlayerCore

class PipActionReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "PipActionReceiver"
        
        const val ACTION_PLAY = "com.yuanzhou.vlc.ACTION_PLAY"
        const val ACTION_PAUSE = "com.yuanzhou.vlc.ACTION_PAUSE"
        const val ACTION_FORWARD = "com.yuanzhou.vlc.ACTION_FORWARD"
        const val ACTION_REWIND = "com.yuanzhou.vlc.ACTION_REWIND"
        
        private const val SKIP_INTERVAL_MS = 10_000L
        
        private fun createPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PipActionReceiver::class.java).apply {
                this.action = action
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        }
        
        fun createPlayIntent(context: Context): PendingIntent = createPendingIntent(context, ACTION_PLAY)
        fun createPauseIntent(context: Context): PendingIntent = createPendingIntent(context, ACTION_PAUSE)
        fun createForwardIntent(context: Context): PendingIntent = createPendingIntent(context, ACTION_FORWARD)
        fun createRewindIntent(context: Context): PendingIntent = createPendingIntent(context, ACTION_REWIND)
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "onReceive: $action")
        
        val playerCore = VlcPlayerCore.getInstance(context)
        
        when (action) {
            ACTION_PLAY -> playerCore.play()
            ACTION_PAUSE -> playerCore.pause()
            ACTION_FORWARD -> playerCore.seekByMs(SKIP_INTERVAL_MS)
            ACTION_REWIND -> playerCore.seekByMs(-SKIP_INTERVAL_MS)
        }
    }
}
