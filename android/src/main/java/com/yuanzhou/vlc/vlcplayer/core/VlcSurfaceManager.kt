package com.yuanzhou.vlc.vlcplayer.core

import android.util.Log
import android.view.SurfaceHolder

class VlcSurfaceManager(
    private val playerCore: VlcPlayerCore
) : SurfaceHolder.Callback {
    
    companion object {
        private const val TAG = "VlcSurfaceManager"
    }
    
    private var currentWidth = 0
    private var currentHeight = 0
    private var isAttached = false
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated")
        playerCore.attachSurface(holder)
        isAttached = true
        
        if (currentWidth > 0 && currentHeight > 0) {
            playerCore.setWindowSize(currentWidth, currentHeight)
        }
    }
    
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "surfaceChanged: ${width}x${height}")
        currentWidth = width
        currentHeight = height
        playerCore.setWindowSize(width, height)
    }
    
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed")
        playerCore.detachSurface()
        isAttached = false
    }
    
    fun requestAttach(holder: SurfaceHolder) {
        if (holder.surface.isValid && !isAttached) {
            surfaceCreated(holder)
            val frame = holder.surfaceFrame
            if (frame.width() > 0 && frame.height() > 0) {
                surfaceChanged(holder, 0, frame.width(), frame.height())
            }
        }
    }
    
    fun getCurrentSize(): Pair<Int, Int> = Pair(currentWidth, currentHeight)
}
