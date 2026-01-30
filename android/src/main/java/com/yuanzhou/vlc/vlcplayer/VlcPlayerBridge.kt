package com.yuanzhou.vlc.vlcplayer

import android.content.Context
import android.util.Log
import android.view.SurfaceHolder
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import to.holepunch.modules.mediasession.PipBridge
import to.holepunch.modules.mediasession.PipEntryHandler
import com.yuanzhou.vlc.vlcplayer.pip.PipHostActivity

/**
 * Bridge singleton that holds reference to the active VLC MediaPlayer from ReactVlcPlayerView.
 * This allows PipHostActivity to attach its surface to the same player instance,
 * enabling seamless video handoff during PiP transitions.
 */
interface VlcPlayerBridgeListener {
    fun onPipSurfaceDetached()
}

/**
 * Implementation of PipEntryHandler that launches PipHostActivity.
 * This is registered with MediaSession's PipBridge when a VLC player is active.
 */
private object VlcPipEntryHandler : PipEntryHandler {
    override fun canEnterPip(): Boolean {
        return VlcPlayerBridge.hasActivePlayer()
    }

    override fun enterPip(context: Context) {
        Log.d("VlcPipEntryHandler", "Launching PipHostActivity")
        PipHostActivity.launch(context)
    }
}

object VlcPlayerBridge {
    private const val TAG = "VlcPlayerBridge"

    @Volatile
    private var activePlayer: MediaPlayer? = null
    
    @Volatile
    private var videoWidth: Int = 0
    
    @Volatile
    private var videoHeight: Int = 0
    
    @Volatile
    private var pipSurfaceAttached: Boolean = false
    
    @Volatile
    private var pipV2Active: Boolean = false
    
    @Volatile
    private var listener: VlcPlayerBridgeListener? = null
    
    fun setListener(l: VlcPlayerBridgeListener?) {
        listener = l
    }
    
    fun setPipV2Active(active: Boolean) {
        pipV2Active = active
        Log.d(TAG, "setPipV2Active: $active")
    }
    
    fun isPipV2Active(): Boolean = pipV2Active
    
    /**
     * Register the active MediaPlayer from ReactVlcPlayerView.
     * Called when playback starts.
     * Also registers VLC as the PiP handler with MediaSession's PipBridge.
     */
    fun registerPlayer(player: MediaPlayer) {
        val wasActive = activePlayer != null
        Log.d(TAG, "registerPlayer: ${player.hashCode()}")
        activePlayer = player

        // Register VLC as the PiP handler if this is the first active player
        if (!wasActive) {
            Log.d(TAG, "Registering VlcPipEntryHandler with PipBridge")
            PipBridge.registerPipEntryHandler(VlcPipEntryHandler)
        }
    }

    /**
     * Unregister the MediaPlayer when playback stops or view is destroyed.
     * Also unregisters VLC from PipBridge if no more active players.
     */
    fun unregisterPlayer(player: MediaPlayer) {
        if (activePlayer === player) {
            Log.d(TAG, "unregisterPlayer: ${player.hashCode()}")
            activePlayer = null
            videoWidth = 0
            videoHeight = 0
            pipSurfaceAttached = false

            // Unregister from PipBridge since no active player
            Log.d(TAG, "Unregistering VlcPipEntryHandler from PipBridge")
            PipBridge.registerPipEntryHandler(null)
        }
    }
    
    /**
     * Update video dimensions (called from ReactVlcPlayerView's layout listener).
     */
    fun updateVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            Log.d(TAG, "updateVideoSize: ${width}x${height}")
        }
    }
    
    /**
     * Get the current video dimensions.
     */
    fun getVideoSize(): Pair<Int, Int> = Pair(videoWidth, videoHeight)
    
    /**
     * Check if a player is currently registered.
     */
    fun hasActivePlayer(): Boolean = activePlayer != null
    
    /**
     * Check if the player is currently playing.
     */
    fun isPlaying(): Boolean = activePlayer?.isPlaying == true
    
    /**
     * Get current playback position (0-1).
     */
    fun getPosition(): Float = activePlayer?.position ?: 0f
    
    /**
     * Get current playback time in ms.
     */
    fun getTime(): Long = activePlayer?.time ?: 0L
    
    /**
     * Get total duration in ms.
     */
    fun getDuration(): Long = activePlayer?.length ?: 0L
    
    /**
     * Play the media.
     */
    fun play() {
        activePlayer?.play()
        Log.d(TAG, "play()")
    }
    
    /**
     * Pause the media.
     */
    fun pause() {
        activePlayer?.pause()
        Log.d(TAG, "pause()")
    }
    
    /**
     * Seek to position (0-1).
     */
    fun seekTo(position: Float) {
        activePlayer?.position = position.coerceIn(0f, 1f)
        Log.d(TAG, "seekTo: $position")
    }
    
    /**
     * Attach a PiP surface to the active player.
     * This detaches any existing surface and attaches the new one.
     * 
     * @param holder The SurfaceHolder from PipHostActivity
     * @param layoutListener Optional layout listener for video size changes
     * @return true if attachment succeeded
     */
    fun attachPipSurface(
        holder: SurfaceHolder,
        layoutListener: IVLCVout.OnNewVideoLayoutListener? = null
    ): Boolean {
        val player = activePlayer
        if (player == null) {
            Log.w(TAG, "attachPipSurface: no active player")
            return false
        }
        
        val vlcVout = player.vlcVout
        
        // Detach existing views first
        if (vlcVout.areViewsAttached()) {
            Log.d(TAG, "attachPipSurface: detaching existing views")
            vlcVout.detachViews()
        }
        
        // Attach new PiP surface
        vlcVout.setVideoSurface(holder.surface, holder)
        vlcVout.attachViews(layoutListener)
        pipSurfaceAttached = true
        
        Log.d(TAG, "attachPipSurface: attached successfully")
        return true
    }
    
    /**
     * Detach the PiP surface and restore original surface from ReactVlcPlayerView.
     * Called when exiting PiP.
     */
    fun detachPipSurface() {
        val player = activePlayer
        if (player == null) {
            Log.w(TAG, "detachPipSurface: no active player")
            return
        }
        
        val vlcVout = player.vlcVout
        if (vlcVout.areViewsAttached()) {
            Log.d(TAG, "detachPipSurface: detaching PiP surface")
            vlcVout.detachViews()
        }
        
        pipSurfaceAttached = false
        
        listener?.onPipSurfaceDetached()
    }
    
    /**
     * Check if PiP surface is currently attached.
     */
    fun isPipSurfaceAttached(): Boolean = pipSurfaceAttached
    
    /**
     * Set window size on the player (for proper video scaling).
     */
    fun setWindowSize(width: Int, height: Int) {
        val player = activePlayer ?: return
        if (width > 0 && height > 0) {
            val vout = player.vlcVout
            vout.setWindowSize(width, height)
            if (vout.areViewsAttached()) {
                try {
                    player.setScale(0f)
                } catch (e: Exception) {
                    Log.w(TAG, "setScale failed: ${e.message}")
                }
            }
            Log.d(TAG, "setWindowSize: ${width}x${height}")
        }
    }
    
    /**
     * Get the active VLCVout for direct manipulation if needed.
     */
    fun getVlcVout(): IVLCVout? = activePlayer?.vlcVout
}
