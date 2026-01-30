package com.yuanzhou.vlc.vlcplayer.pip

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.Rational
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.yuanzhou.vlc.vlcplayer.VlcPlayerBridge
import com.yuanzhou.vlc.vlcplayer.service.PlaybackService
import org.videolan.libvlc.interfaces.IVLCVout

class PipHostActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "PipHostActivity"
        const val ACTION_PIP_MODE_CHANGED = "com.yuanzhou.vlc.PIP_MODE_CHANGED"
        const val EXTRA_IS_IN_PIP = "isInPip"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        
        fun launch(context: Context) {
            if (!VlcPlayerBridge.hasActivePlayer()) {
                Log.w(TAG, "launch: No active player registered, cannot enter PiP")
                return
            }
            
            val intent = Intent(context, PipHostActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
        }
    }
    
    private lateinit var surfaceView: SurfaceView
    private lateinit var container: FrameLayout
    private var playbackService: PlaybackService? = null
    private var lastWidth = 0
    private var lastHeight = 0
    private var surfaceReady = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playbackService = (binder as PlaybackService.LocalBinder).getService()
            Log.d(TAG, "PlaybackService connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
            Log.d(TAG, "PlaybackService disconnected")
        }
    }
    
    private val videoLayoutListener = IVLCVout.OnNewVideoLayoutListener { _, width, height, _, _, _, _ ->
        if (width > 0 && height > 0) {
            Log.d(TAG, "onNewVideoLayout: ${width}x${height}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                updatePipParams()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")
        
        if (!VlcPlayerBridge.hasActivePlayer()) {
            Log.e(TAG, "onCreate: No active player, finishing")
            finish()
            return
        }
        
        VlcPlayerBridge.setPipV2Active(true)
        
        container = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        
        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(surfaceView)
        setContentView(container)
        
        container.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val width = right - left
            val height = bottom - top
            if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                Log.d(TAG, "Container layout changed: ${width}x${height}")
                lastWidth = width
                lastHeight = height
                if (surfaceReady) {
                    VlcPlayerBridge.setWindowSize(width, height)
                }
            }
        }
        
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceCreated")
                surfaceReady = true
                val attached = VlcPlayerBridge.attachPipSurface(holder, videoLayoutListener)
                Log.d(TAG, "Surface attached to bridge: $attached")
                
                if (lastWidth > 0 && lastHeight > 0) {
                    VlcPlayerBridge.setWindowSize(lastWidth, lastHeight)
                }
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "surfaceChanged: ${width}x${height}")
                VlcPlayerBridge.setWindowSize(width, height)
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "surfaceDestroyed")
                surfaceReady = false
            }
        })
        
        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canEnterPip()) {
            enterPipMode()
        }
    }
    
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (VlcPlayerBridge.isPlaying() && canEnterPip()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPipMode()
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        Log.d(TAG, "onPictureInPictureModeChanged: $isInPipMode")
        
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            Pair(window?.decorView?.width ?: 0, window?.decorView?.height ?: 0)
        }
        
        sendPipBroadcast(isInPipMode, width, height)
        
        if (!isInPipMode && !isFinishing) {
            bringMainAppToFront()
            finish()
        }
    }
    
    private fun bringMainAppToFront() {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                startActivity(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bring main app to front: ${e.message}")
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val params = buildPipParams()
        enterPictureInPictureMode(params)
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipParams() {
        if (!isInPictureInPictureMode) return
        
        val params = buildPipParams()
        setPictureInPictureParams(params)
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val (videoWidth, videoHeight) = VlcPlayerBridge.getVideoSize()
        val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
            Rational(videoWidth, videoHeight)
        } else {
            Rational(16, 9)
        }
        
        return PipParamsBuilder.build(
            context = this,
            aspectRatio = aspectRatio,
            isPlaying = VlcPlayerBridge.isPlaying(),
            sourceRect = getSourceRect()
        )
    }
    
    private fun getSourceRect(): Rect {
        val location = IntArray(2)
        surfaceView.getLocationOnScreen(location)
        return Rect(
            location[0],
            location[1],
            location[0] + surfaceView.width,
            location[1] + surfaceView.height
        )
    }
    
    private fun canEnterPip(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
               packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    
    private fun sendPipBroadcast(isInPip: Boolean, width: Int, height: Int) {
        Log.d(TAG, "Sending PiP broadcast: isInPip=$isInPip, ${width}x$height")
        val intent = Intent(ACTION_PIP_MODE_CHANGED).apply {
            putExtra(EXTRA_IS_IN_PIP, isInPip)
            putExtra(EXTRA_WIDTH, width)
            putExtra(EXTRA_HEIGHT, height)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        VlcPlayerBridge.setPipV2Active(false)
        VlcPlayerBridge.detachPipSurface()
        try {
            unbindService(serviceConnection)
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding service: ${e.message}")
        }
        super.onDestroy()
    }
}
