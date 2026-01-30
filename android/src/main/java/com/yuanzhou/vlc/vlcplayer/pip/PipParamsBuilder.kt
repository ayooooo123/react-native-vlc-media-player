package com.yuanzhou.vlc.vlcplayer.pip

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import com.yuanzhou.vlc.vlcplayer.service.PipActionReceiver

object PipParamsBuilder {
    
    @RequiresApi(Build.VERSION_CODES.O)
    fun build(
        context: Context,
        aspectRatio: Rational?,
        isPlaying: Boolean,
        sourceRect: Rect?
    ): PictureInPictureParams {
        
        val builder = PictureInPictureParams.Builder()
        
        val safeAspectRatio = aspectRatio ?: Rational(16, 9)
        val clampedRatio = clampAspectRatio(safeAspectRatio)
        builder.setAspectRatio(clampedRatio)
        
        sourceRect?.let { builder.setSourceRectHint(it) }
        
        val actions = buildActions(context, isPlaying)
        builder.setActions(actions)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(false)
        }
        
        return builder.build()
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildActions(context: Context, isPlaying: Boolean): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        
        val rewindIcon = Icon.createWithResource(context, android.R.drawable.ic_media_rew)
        actions.add(RemoteAction(
            rewindIcon,
            "Rewind",
            "Rewind 10 seconds",
            PipActionReceiver.createRewindIntent(context)
        ))
        
        if (isPlaying) {
            val pauseIcon = Icon.createWithResource(context, android.R.drawable.ic_media_pause)
            actions.add(RemoteAction(
                pauseIcon,
                "Pause",
                "Pause playback",
                PipActionReceiver.createPauseIntent(context)
            ))
        } else {
            val playIcon = Icon.createWithResource(context, android.R.drawable.ic_media_play)
            actions.add(RemoteAction(
                playIcon,
                "Play",
                "Resume playback",
                PipActionReceiver.createPlayIntent(context)
            ))
        }
        
        val forwardIcon = Icon.createWithResource(context, android.R.drawable.ic_media_ff)
        actions.add(RemoteAction(
            forwardIcon,
            "Forward",
            "Forward 10 seconds",
            PipActionReceiver.createForwardIntent(context)
        ))
        
        return actions
    }
    
    private fun clampAspectRatio(ratio: Rational): Rational {
        val min = Rational(1, 2)   // 0.5 (tall)
        val max = Rational(239, 100) // 2.39 (wide)
        
        return when {
            ratio.toFloat() < min.toFloat() -> min
            ratio.toFloat() > max.toFloat() -> max
            else -> ratio
        }
    }
    
    fun calculateAspectRatio(width: Int, height: Int): Rational? {
        if (width <= 0 || height <= 0) return null
        return Rational(width, height)
    }
}
