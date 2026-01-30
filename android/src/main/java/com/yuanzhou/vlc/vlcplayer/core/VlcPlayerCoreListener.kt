package com.yuanzhou.vlc.vlcplayer.core

interface VlcPlayerCoreListener {
    fun onPlaybackStateChanged(isPlaying: Boolean)
    fun onProgressChanged(position: Float, currentTimeMs: Long, durationMs: Long)
    fun onVideoSizeChanged(width: Int, height: Int)
    fun onBuffering(buffering: Boolean, bufferPercent: Float)
    fun onMediaLoaded(info: MediaInfo)
    fun onEnded()
    fun onError(error: String)
    fun onStopped()
}

data class MediaInfo(
    val durationMs: Long,
    val videoWidth: Int,
    val videoHeight: Int,
    val audioTracks: List<TrackInfo>,
    val textTracks: List<TrackInfo>,
    val videoTracks: List<TrackInfo>
)

data class TrackInfo(
    val id: Int,
    val name: String
)
