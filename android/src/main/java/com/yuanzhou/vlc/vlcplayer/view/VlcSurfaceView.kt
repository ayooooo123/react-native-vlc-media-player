package com.yuanzhou.vlc.vlcplayer.view

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import com.yuanzhou.vlc.vlcplayer.core.VlcPlayerCore
import com.yuanzhou.vlc.vlcplayer.core.VlcSurfaceManager

class VlcSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {
    
    companion object {
        private const val TAG = "VlcSurfaceView"
    }
    
    private var surfaceManager: VlcSurfaceManager? = null
    
    fun attachToPlayer(playerCore: VlcPlayerCore) {
        Log.d(TAG, "attachToPlayer")
        
        surfaceManager?.let {
            holder.removeCallback(it)
        }
        
        surfaceManager = VlcSurfaceManager(playerCore).also { manager ->
            holder.addCallback(manager)
            
            if (holder.surface.isValid) {
                manager.requestAttach(holder)
            }
        }
    }
    
    fun detachFromPlayer() {
        Log.d(TAG, "detachFromPlayer")
        
        surfaceManager?.let { manager ->
            holder.removeCallback(manager)
        }
        surfaceManager = null
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detachFromPlayer()
    }
}
