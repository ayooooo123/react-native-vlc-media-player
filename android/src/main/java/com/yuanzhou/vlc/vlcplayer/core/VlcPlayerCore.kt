package com.yuanzhou.vlc.vlcplayer.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.interfaces.IVLCVout

class VlcPlayerCore private constructor(context: Context) {
    
    companion object {
        private const val TAG = "VlcPlayerCore"
        
        @Volatile
        private var instance: VlcPlayerCore? = null
        
        fun getInstance(context: Context): VlcPlayerCore {
            return instance ?: synchronized(this) {
                instance ?: VlcPlayerCore(context.applicationContext).also { instance = it }
            }
        }
        
        fun release() {
            instance?.releaseInternal()
            instance = null
        }
    }
    
    private val appContext: Context = context.applicationContext
    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<VlcPlayerCoreListener>()
    
    var isPlaying: Boolean = false
        private set
    var isPaused: Boolean = true
        private set
    var currentPosition: Float = 0f
        private set
    var currentTimeMs: Long = 0L
        private set
    var durationMs: Long = 0L
        private set
    var videoWidth: Int = 0
        private set
    var videoHeight: Int = 0
        private set
    
    private var preVolume: Int = 100
    private var isMuted: Boolean = false
    
    private val playerEventListener = MediaPlayer.EventListener { event ->
        val player = mediaPlayer ?: return@EventListener
        
        isPlaying = player.isPlaying
        currentTimeMs = player.time
        currentPosition = player.position
        durationMs = player.length
        
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                isPaused = false
                notifyPlaybackStateChanged(true)
            }
            MediaPlayer.Event.Paused -> {
                isPaused = true
                notifyPlaybackStateChanged(false)
            }
            MediaPlayer.Event.Stopped -> {
                isPaused = true
                isPlaying = false
                notifyStopped()
            }
            MediaPlayer.Event.EndReached -> {
                notifyEnded()
            }
            MediaPlayer.Event.EncounteredError -> {
                notifyError("Playback error")
            }
            MediaPlayer.Event.Buffering -> {
                notifyBuffering(event.buffering < 100f, event.buffering)
            }
            MediaPlayer.Event.TimeChanged -> {
                notifyProgressChanged()
            }
        }
    }
    
    private val videoLayoutListener = IVLCVout.OnNewVideoLayoutListener { _, width, height, _, _, _, _ ->
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            notifyVideoSizeChanged(width, height)
        }
    }
    
    fun loadMedia(
        uri: String,
        isNetwork: Boolean = true,
        initOptions: List<String>? = null,
        mediaOptions: List<String>? = null,
        hwDecoderEnabled: Boolean? = null,
        hwDecoderForced: Boolean? = null
    ) {
        releaseMedia()
        
        if (libVLC == null) {
            libVLC = if (initOptions.isNullOrEmpty()) {
                LibVLC(appContext)
            } else {
                LibVLC(appContext, ArrayList(initOptions))
            }
        }
        
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer(libVLC).apply {
                setEventListener(playerEventListener)
            }
        }
        
        val media = if (isNetwork) {
            Media(libVLC, Uri.parse(uri))
        } else {
            Media(libVLC, uri)
        }
        
        hwDecoderEnabled?.let { enabled ->
            hwDecoderForced?.let { forced ->
                media.setHWDecoderEnabled(enabled, forced)
            }
        }
        
        mediaOptions?.forEach { option ->
            media.addOption(option)
        }
        
        currentMedia = media
        mediaPlayer?.media = media
        
        Log.d(TAG, "Media loaded: $uri")
    }
    
    fun play() {
        mediaPlayer?.play()
        Log.d(TAG, "play()")
    }
    
    fun pause() {
        mediaPlayer?.pause()
        Log.d(TAG, "pause()")
    }
    
    fun stop() {
        mediaPlayer?.stop()
        Log.d(TAG, "stop()")
    }
    
    fun seekTo(position: Float) {
        if (position in 0f..1f) {
            mediaPlayer?.position = position
            Log.d(TAG, "seekTo: $position")
        }
    }
    
    fun seekByMs(deltaMs: Long) {
        val player = mediaPlayer ?: return
        val newTime = (player.time + deltaMs).coerceIn(0, player.length)
        player.time = newTime
        Log.d(TAG, "seekByMs: $deltaMs -> $newTime")
    }
    
    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(0, 100)
        if (!isMuted) {
            mediaPlayer?.volume = clamped
        }
        preVolume = clamped
        Log.d(TAG, "setVolume: $clamped")
    }
    
    fun setMuted(muted: Boolean) {
        isMuted = muted
        mediaPlayer?.let { player ->
            if (muted) {
                preVolume = player.volume
                player.volume = 0
            } else {
                player.volume = preVolume
            }
        }
        Log.d(TAG, "setMuted: $muted")
    }
    
    fun setAspectRatio(ratio: String?) {
        mediaPlayer?.aspectRatio = ratio
    }
    
    fun setAudioTrack(trackId: Int) {
        val player = mediaPlayer ?: return
        val tracks = player.getTracks(IMedia.Track.Type.Audio)
        val track = tracks?.find { it.id.hashCode() == trackId || it.id == trackId.toString() }
        if (track != null) {
            player.selectTrack(track.id)
        }
    }
    
    fun setTextTrack(trackId: Int) {
        val player = mediaPlayer ?: return
        if (trackId == -1) {
            player.unselectTrackType(IMedia.Track.Type.Text)
            return
        }
        val tracks = player.getTracks(IMedia.Track.Type.Text)
        val track = tracks?.find { it.id.hashCode() == trackId || it.id == trackId.toString() }
        if (track != null) {
            player.selectTrack(track.id)
        }
    }
    
    fun attachSurface(holder: SurfaceHolder) {
        val player = mediaPlayer ?: return
        val vlcOut = player.vlcVout
        
        if (!vlcOut.areViewsAttached()) {
            vlcOut.setVideoSurface(holder.surface, holder)
            vlcOut.attachViews(videoLayoutListener)
            Log.d(TAG, "Surface attached")
        }
    }
    
    fun detachSurface() {
        val player = mediaPlayer ?: return
        val vlcOut = player.vlcVout
        
        if (vlcOut.areViewsAttached()) {
            vlcOut.detachViews()
            Log.d(TAG, "Surface detached")
        }
    }
    
    fun setWindowSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            mediaPlayer?.let { player ->
                val vout = player.vlcVout
                vout.setWindowSize(width, height)
                if (vout.areViewsAttached()) {
                    try {
                        player.setScale(0f)
                    } catch (e: Exception) {
                        Log.w(TAG, "setScale failed: ${e.message}")
                    }
                }
                Log.d(TAG, "setWindowSize: ${width}x${height}, scale=0 (auto-fit)")
            }
        }
    }
    
    fun addListener(listener: VlcPlayerCoreListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: VlcPlayerCoreListener) {
        listeners.remove(listener)
    }
    
    private fun releaseMedia() {
        currentMedia?.release()
        currentMedia = null
    }
    
    private fun releaseInternal() {
        Log.d(TAG, "releaseInternal()")
        
        listeners.clear()
        
        mediaPlayer?.let { player ->
            player.setEventListener(null)
            val vlcOut = player.vlcVout
            if (vlcOut.areViewsAttached()) {
                vlcOut.detachViews()
            }
            player.stop()
            player.release()
        }
        mediaPlayer = null
        
        releaseMedia()
        
        libVLC?.release()
        libVLC = null
        
        isPlaying = false
        isPaused = true
        currentPosition = 0f
        currentTimeMs = 0L
        durationMs = 0L
        videoWidth = 0
        videoHeight = 0
    }
    
    private fun notifyPlaybackStateChanged(playing: Boolean) {
        mainHandler.post {
            listeners.forEach { it.onPlaybackStateChanged(playing) }
        }
    }
    
    private fun notifyProgressChanged() {
        mainHandler.post {
            listeners.forEach { it.onProgressChanged(currentPosition, currentTimeMs, durationMs) }
        }
    }
    
    private fun notifyVideoSizeChanged(width: Int, height: Int) {
        mainHandler.post {
            listeners.forEach { it.onVideoSizeChanged(width, height) }
        }
    }
    
    private fun notifyBuffering(buffering: Boolean, percent: Float) {
        mainHandler.post {
            listeners.forEach { it.onBuffering(buffering, percent) }
        }
    }
    
    private fun notifyEnded() {
        mainHandler.post {
            listeners.forEach { it.onEnded() }
        }
    }
    
    private fun notifyError(error: String) {
        mainHandler.post {
            listeners.forEach { it.onError(error) }
        }
    }
    
    private fun notifyStopped() {
        mainHandler.post {
            listeners.forEach { it.onStopped() }
        }
    }
    
    fun getMediaInfo(): MediaInfo? {
        val player = mediaPlayer ?: return null
        
        val audioTracks = mutableListOf<TrackInfo>()
        val textTracks = mutableListOf<TrackInfo>()
        val videoTracks = mutableListOf<TrackInfo>()
        
        player.getTracks(IMedia.Track.Type.Audio)?.forEach { track ->
            audioTracks.add(TrackInfo(track.id.hashCode(), track.name ?: "Track ${track.id}"))
        }
        
        player.getTracks(IMedia.Track.Type.Text)?.forEach { track ->
            textTracks.add(TrackInfo(track.id.hashCode(), track.name ?: "Track ${track.id}"))
        }
        
        return MediaInfo(
            durationMs = durationMs,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            audioTracks = audioTracks,
            textTracks = textTracks,
            videoTracks = videoTracks
        )
    }
}
