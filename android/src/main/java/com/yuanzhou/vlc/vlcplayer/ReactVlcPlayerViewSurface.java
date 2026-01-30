package com.yuanzhou.vlc.vlcplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Gravity;
import android.widget.FrameLayout;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.uimanager.ThemedReactContext;

import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.Dialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

import com.yuanzhou.vlc.vlcplayer.pip.PipHostActivity;

@SuppressLint("ViewConstructor")
class ReactVlcPlayerViewSurface extends FrameLayout implements
        LifecycleEventListener,
        SurfaceHolder.Callback,
        AudioManager.OnAudioFocusChangeListener,
        VlcPlayerBridgeListener {

    private static final String TAG = "ReactVlcPlayerViewSurf";

    private final VideoEventEmitter eventEmitter;
    private final ThemedReactContext themedReactContext;
    private final AudioManager audioManager;
    
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    
    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private boolean mMuted = false;
    private boolean isSurfaceReady = false;
    private String src;
    private String _subtitleUri;
    private boolean netStrTag;
    private ReadableMap srcMap;
    
    private int mVideoHeight = 0;
    private int mVideoWidth = 0;
    private int mVideoVisibleHeight = 0;
    private int mVideoVisibleWidth = 0;
    private int mSarNum = 0;
    private int mSarDen = 0;
    private int screenWidth = 0;
    private int screenHeight = 0;

    private boolean isPaused = true;
    private boolean isHostPaused = false;
    private int preVolume = 100;
    private boolean autoAspectRatio = false;
    private boolean acceptInvalidCertificates = false;
    
    private boolean mPictureInPictureEnabled = false;
    private boolean mPlayInPictureInPicture = true;
    private boolean mIsInPipMode = false;
    private boolean mPipTransitionInProgress = false;
    private int mPipTargetWidth = 0;
    private int mPipTargetHeight = 0;
    
    private ComponentCallbacks2 mPipCallbacks = null;
    private Handler mPipHandler = new Handler(Looper.getMainLooper());
    private long mLastPipApplyTime = 0;
    private static final long PIP_APPLY_DEBOUNCE_MS = 50;
    private Runnable mPendingPipDimensionCheck = null;
    private Runnable mPendingPipRestore = null;
    private ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener = null;
    private View.OnLayoutChangeListener mPipLayoutChangeListener = null;
    private int mLastLayoutWidth = 0;
    private int mLastLayoutHeight = 0;

    private float mProgressUpdateInterval = 0;
    private Handler mProgressUpdateHandler = new Handler();
    private Runnable mProgressUpdateRunnable = null;
    
    private Handler mLayoutHandler = new Handler(Looper.getMainLooper());
    private Runnable mLayoutRunnable = null;
    private int mPendingWidth = 0;
    private int mPendingHeight = 0;
    
    private int mLastFullscreenWidth = 0;
    private int mLastFullscreenHeight = 0;

    private WritableMap mVideoInfo = null;
    private String mVideoInfoHash = null;

    public ReactVlcPlayerViewSurface(ThemedReactContext context) {
        super(context);
        this.eventEmitter = new VideoEventEmitter(context);
        this.themedReactContext = context;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        screenWidth = dm.widthPixels;
        
        mSurfaceView = new SurfaceView(context);
        mSurfaceView.getHolder().addCallback(this);
        mSurfaceView.getHolder().setFormat(PixelFormat.RGBX_8888);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(mSurfaceView, params);
        
        this.addOnLayoutChangeListener(onLayoutChangeListener);
        context.addLifecycleEventListener(this);
        
        Log.i(TAG, "Created SurfaceView-based player");
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mIsInPipMode) {
            Log.d(TAG, "onMeasure (PiP): " + getMeasuredWidth() + "x" + getMeasuredHeight());
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated: " + getWidth() + "x" + getHeight());
        mSurfaceHolder = holder;
        isSurfaceReady = true;
        createPlayer(true, false);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height + ", inPip=" + mIsInPipMode);
        updateVideoSurfaces();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        isSurfaceReady = false;
        mSurfaceHolder = null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPictureInPictureEnabled) {
            registerPipCallbacks();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelPendingPipCallbacks();
        unregisterPipCallbacks();
        if (!mIsInPipMode) {
            stopPlayback();
        }
    }

    @Override
    public void onHostResume() {
        if (mMediaPlayer != null && isHostPaused && isSurfaceReady) {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (!vlcOut.areViewsAttached()) {
                vlcOut.setVideoView(mSurfaceView);
                vlcOut.attachViews(onNewVideoLayoutListener);
                isPaused = false;
                mMediaPlayer.play();
            }
        }
    }

    @Override
    public void onHostPause() {
        Log.i(TAG, "onHostPause: mIsInPipMode=" + mIsInPipMode);
        
        if (mIsInPipMode && mPlayInPictureInPicture) {
            return;
        }
        
        if (mPictureInPictureEnabled) {
            mPipHandler.postDelayed(() -> {
                boolean inPipNow = false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Activity activity = themedReactContext.getCurrentActivity();
                    if (activity != null) {
                        inPipNow = activity.isInPictureInPictureMode();
                    }
                }
                
                if ((mIsInPipMode || inPipNow) && mPlayInPictureInPicture) {
                    return;
                }
                
                doPause();
            }, 150);
        } else {
            doPause();
        }
    }
    
    private void doPause() {
        if (!isPaused && mMediaPlayer != null) {
            isPaused = true;
            isHostPaused = true;
            mMediaPlayer.pause();
            WritableMap map = Arguments.createMap();
            map.putString("type", "Paused");
            eventEmitter.onVideoStateChange(map);
        }
    }

    @Override
    public void onHostDestroy() {
        stopPlayback();
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
    }

    /**
     * VlcPlayerBridgeListener implementation.
     * Called when PipHostActivity exits and detaches its surface.
     * We need to reattach our surface to resume playback in the main view.
     */
    @Override
    public void onPipSurfaceDetached() {
        Log.i(TAG, "onPipSurfaceDetached: reattaching surface to player");
        if (mMediaPlayer != null && mSurfaceHolder != null && isSurfaceReady) {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (!vlcOut.areViewsAttached()) {
                vlcOut.setVideoView(mSurfaceView);
                vlcOut.attachViews(onNewVideoLayoutListener);
                Log.i(TAG, "onPipSurfaceDetached: surface reattached");
                updateVideoSurfaces();
            }
        }
    }

    private static final long LAYOUT_DEBOUNCE_MS = 100;
    
    private View.OnLayoutChangeListener onLayoutChangeListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View view, int i, int i1, int i2, int i3, int i4, int i5, int i6, int i7) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) return;
            
            Activity activity = themedReactContext.getCurrentActivity();
            boolean systemInPip = false;
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                systemInPip = activity.isInPictureInPictureMode();
            }
            
            if (mIsInPipMode || systemInPip) {
                if (!mIsInPipMode && systemInPip) {
                    mIsInPipMode = true;
                }
                updateVideoSurfaces();
                return;
            }
            
            updateLastFullscreenSize(w, h);
            updateVideoSurfaces();
        }
    };

    private MediaPlayer.EventListener mPlayerListener = new MediaPlayer.EventListener() {
        @Override
        public void onEvent(MediaPlayer.Event event) {
            if (mMediaPlayer == null) return;
            
            boolean isPlaying = mMediaPlayer.isPlaying();
            long currentTime = mMediaPlayer.getTime();
            float position = mMediaPlayer.getPosition();
            long totalLength = mMediaPlayer.getLength();
            
            WritableMap map = Arguments.createMap();
            map.putBoolean("isPlaying", isPlaying);
            map.putDouble("position", position);
            map.putDouble("currentTime", currentTime);
            map.putDouble("duration", totalLength);

            switch (event.type) {
                case MediaPlayer.Event.EndReached:
                    map.putString("type", "Ended");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_END);
                    break;
                case MediaPlayer.Event.Playing:
                    map.putString("type", "Playing");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_IS_PLAYING);
                    emitVideoSizeIfAvailable();
                    break;
                case MediaPlayer.Event.Opening:
                    map.putString("type", "Opening");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_OPEN);
                    break;
                case MediaPlayer.Event.Paused:
                    map.putString("type", "Paused");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_PAUSED);
                    break;
                case MediaPlayer.Event.Buffering:
                    map.putDouble("bufferRate", event.getBuffering());
                    map.putString("type", "Buffering");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_VIDEO_BUFFERING);
                    break;
                case MediaPlayer.Event.Stopped:
                    map.putString("type", "Stopped");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_VIDEO_STOPPED);
                    break;
                case MediaPlayer.Event.EncounteredError:
                    map.putString("type", "Error");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_ON_ERROR);
                    break;
                case MediaPlayer.Event.TimeChanged:
                    map.putString("type", "TimeChanged");
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_SEEK);
                    break;
                case MediaPlayer.Event.Vout:
                    if (event.getVoutCount() > 0) {
                        mMediaPlayer.updateVideoSurfaces();
                    }
                    map.putString("type", "Vout");
                    map.putInt("voutCount", event.getVoutCount());
                    eventEmitter.onVideoStateChange(map);
                    break;
                default:
                    map.putString("type", event.type + "");
                    eventEmitter.onVideoStateChange(map);
                    break;
            }
        }
    };

    private IVLCVout.OnNewVideoLayoutListener onNewVideoLayoutListener = new IVLCVout.OnNewVideoLayoutListener() {
        @Override
        public void onNewVideoLayout(IVLCVout vout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
            if (width * height == 0) return;
            
            mVideoWidth = width;
            mVideoHeight = height;
            mVideoVisibleWidth = visibleWidth;
            mVideoVisibleHeight = visibleHeight;
            mSarNum = sarNum;
            mSarDen = sarDen;
            
            Log.i(TAG, "onNewVideoLayout: video " + width + "x" + height + ", visible " + visibleWidth + "x" + visibleHeight);
            updateVideoSurfaces();
            
            WritableMap map = Arguments.createMap();
            map.putInt("mVideoWidth", mVideoWidth);
            map.putInt("mVideoHeight", mVideoHeight);
            map.putInt("mVideoVisibleWidth", mVideoVisibleWidth);
            map.putInt("mVideoVisibleHeight", mVideoVisibleHeight);
            map.putInt("mSarNum", mSarNum);
            map.putInt("mSarDen", mSarDen);
            map.putString("type", "onNewVideoLayout");
            eventEmitter.onVideoStateChange(map);
        }
    };

    private void stopPlayback() {
        onStopPlayback();
        releasePlayer();
    }

    private void onStopPlayback() {
        setKeepScreenOn(false);
        audioManager.abandonAudioFocus(this);
    }

    private void createPlayer(boolean autoplayResume, boolean isResume) {
        releasePlayer();
        mVideoSizeEmitted = false;
        if (!isSurfaceReady || mSurfaceHolder == null) {
            Log.w(TAG, "createPlayer: surface not ready");
            return;
        }
        
        try {
            final ArrayList<String> cOptions = new ArrayList<>();
            String uriString = srcMap != null && srcMap.hasKey("uri") ? srcMap.getString("uri") : null;
            if (uriString == null) {
                Log.w(TAG, "createPlayer: no URI");
                return;
            }
            
            boolean isNetwork = srcMap.hasKey("isNetwork") ? srcMap.getBoolean("isNetwork") : false;
            boolean autoplay = srcMap.hasKey("autoplay") ? srcMap.getBoolean("autoplay") : true;
            int initType = srcMap.hasKey("initType") ? srcMap.getInt("initType") : 1;
            ReadableArray initOptions = srcMap.hasKey("initOptions") ? srcMap.getArray("initOptions") : null;

            if (initOptions != null) {
                ArrayList options = initOptions.toArrayList();
                for (int i = 0; i < options.size(); i++) {
                    String option = (String) options.get(i);
                    cOptions.add(option);
                }
            }
            
            if (initType == 1) {
                libvlc = new LibVLC(getContext());
            } else {
                libvlc = new LibVLC(getContext(), cOptions);
            }
            
            mMediaPlayer = new MediaPlayer(libvlc);
            setMutedModifier(mMuted);
            mMediaPlayer.setEventListener(mPlayerListener);

            // Register with VlcPlayerBridge for PiP support
            VlcPlayerBridge.INSTANCE.registerPlayer(mMediaPlayer);
            VlcPlayerBridge.INSTANCE.setListener(this);

            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            vlcOut.setVideoView(mSurfaceView);
            vlcOut.attachViews(onNewVideoLayoutListener);
            
            Uri uri = Uri.parse(uriString);
            Media m = isNetwork ? new Media(libvlc, uri) : new Media(libvlc, uriString);
            
            ReadableArray mediaOptions = srcMap.hasKey("mediaOptions") ? srcMap.getArray("mediaOptions") : null;
            if (mediaOptions != null) {
                for (int i = 0; i < mediaOptions.size(); i++) {
                    String option = mediaOptions.getString(i);
                    m.addOption(option);
                }
            }
            
            mVideoInfo = null;
            mVideoInfoHash = null;
            mMediaPlayer.setMedia(m);
            m.release();
            safeSetScale(0);
            
            if (_subtitleUri != null) {
                mMediaPlayer.addSlave(Media.Slave.Type.Subtitle, _subtitleUri, true);
            }

            if (isResume) {
                if (autoplayResume) {
                    mMediaPlayer.play();
                }
            } else {
                if (autoplay) {
                    isPaused = false;
                    mMediaPlayer.play();
                }
            }
            
            eventEmitter.loadStart();
            setProgressUpdateRunnable();
            
            Log.i(TAG, "createPlayer: success");
        } catch (Exception e) {
            Log.e(TAG, "createPlayer failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void releasePlayer() {
        if (libvlc == null) return;

        // Unregister from VlcPlayerBridge before releasing
        if (mMediaPlayer != null) {
            VlcPlayerBridge.INSTANCE.setListener(null);
            VlcPlayerBridge.INSTANCE.unregisterPlayer(mMediaPlayer);
        }

        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.detachViews();
        mMediaPlayer.release();
        libvlc.release();
        libvlc = null;

        if (mProgressUpdateRunnable != null) {
            mProgressUpdateHandler.removeCallbacks(mProgressUpdateRunnable);
        }
    }

    private void setProgressUpdateRunnable() {
        if (mMediaPlayer != null && mProgressUpdateInterval > 0) {
            mProgressUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (mMediaPlayer != null && !isPaused) {
                        WritableMap map = Arguments.createMap();
                        map.putBoolean("isPlaying", mMediaPlayer.isPlaying());
                        map.putDouble("position", mMediaPlayer.getPosition());
                        map.putDouble("currentTime", mMediaPlayer.getTime());
                        map.putDouble("duration", mMediaPlayer.getLength());
                        eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_PROGRESS);
                    }
                    mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, Math.round(mProgressUpdateInterval));
                }
            };
            mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, 0);
        }
    }

    private void updateLastFullscreenSize(int width, int height) {
        if (width > 500 && height > 500 && !mIsInPipMode) {
            mLastFullscreenWidth = width;
            mLastFullscreenHeight = height;
        }
    }

    private void updateVideoSurfaces() {
        if (mSurfaceView == null || mMediaPlayer == null) return;

        int containerWidth = getWidth();
        int containerHeight = getHeight();

        if (containerWidth * containerHeight == 0) return;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSurfaceView.getLayoutParams();
        if (lp == null) {
            lp = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        }

        lp.width = LayoutParams.MATCH_PARENT;
        lp.height = LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.CENTER;
        mSurfaceView.setLayoutParams(lp);

        IVLCVout vout = mMediaPlayer.getVLCVout();
        if (vout != null && vout.areViewsAttached()) {
            vout.setWindowSize(containerWidth, containerHeight);
        }

        // Don't force aspect ratio in PiP mode - let VLC auto-letterbox with scale=0
        // This prevents cropping issues in small PiP windows
        mMediaPlayer.setAspectRatio(null);
        safeSetScale(0);
        mMediaPlayer.updateVideoSurfaces();

        int[] surfaceLoc = new int[2];
        mSurfaceView.getLocationInWindow(surfaceLoc);
        Log.i(TAG, "updateVideoSurfaces: container " + containerWidth + "x" + containerHeight +
              ", surface at (" + surfaceLoc[0] + "," + surfaceLoc[1] + ")" +
              ", surfaceSize " + mSurfaceView.getWidth() + "x" + mSurfaceView.getHeight() +
              ", pip=" + mIsInPipMode);
    }
    
    private boolean mVideoSizeEmitted = false;
    
    private void emitVideoSizeIfAvailable() {
        if (mMediaPlayer == null || mVideoSizeEmitted) return;
        
        IMedia.Track track = mMediaPlayer.getSelectedTrack(IMedia.Track.Type.Video);
        if (track instanceof IMedia.VideoTrack) {
            IMedia.VideoTrack videoTrack = (IMedia.VideoTrack) track;
            if (videoTrack.width > 0 && videoTrack.height > 0) {
                mVideoWidth = videoTrack.width;
                mVideoHeight = videoTrack.height;
                mVideoSizeEmitted = true;

                // Update VlcPlayerBridge with video dimensions for PiP
                VlcPlayerBridge.INSTANCE.updateVideoSize(videoTrack.width, videoTrack.height);

                Log.i(TAG, "emitVideoSizeIfAvailable: " + videoTrack.width + "x" + videoTrack.height);

                WritableMap map = Arguments.createMap();
                map.putInt("mVideoWidth", videoTrack.width);
                map.putInt("mVideoHeight", videoTrack.height);
                map.putString("type", "onNewVideoLayout");
                eventEmitter.onVideoStateChange(map);
                return;
            }
        }
        mPipHandler.postDelayed(() -> {
            if (!mVideoSizeEmitted) {
                emitVideoSizeIfAvailable();
            }
        }, 200);
    }

    public void setPosition(float position) {
        if (mMediaPlayer != null && position >= 0 && position <= 1) {
            mMediaPlayer.setPosition(position);
        }
    }

    public void setSubtitleUri(String subtitleUri) {
        _subtitleUri = subtitleUri;
        if (mMediaPlayer != null) {
            mMediaPlayer.addSlave(Media.Slave.Type.Subtitle, _subtitleUri, true);
        }
    }

    public void setSrc(ReadableMap src) {
        this.srcMap = src;
        createPlayer(true, false);
    }

    public void setRateModifier(float rateModifier) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setRate(rateModifier);
        }
    }

    public void setmProgressUpdateInterval(float interval) {
        mProgressUpdateInterval = interval;
        createPlayer(true, false);
    }

    public void setVolumeModifier(int volumeModifier) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(volumeModifier);
        }
    }

    public void setMutedModifier(boolean muted) {
        mMuted = muted;
        if (mMediaPlayer != null) {
            if (muted) {
                this.preVolume = mMediaPlayer.getVolume();
                mMediaPlayer.setVolume(0);
            } else {
                mMediaPlayer.setVolume(this.preVolume);
            }
        }
    }

    public void setPausedModifier(boolean paused) {
        if (mMediaPlayer != null) {
            if (paused) {
                isPaused = true;
                mMediaPlayer.pause();
            } else {
                isPaused = false;
                mMediaPlayer.play();
            }
        } else {
            createPlayer(!paused, false);
        }
    }

    public void doResume(boolean autoplay) {
        createPlayer(autoplay, true);
    }

    public void setRepeatModifier(boolean repeat) {
    }

    private void safeSetScale(float scale) {
        if (mMediaPlayer == null) return;
        IVLCVout vout = mMediaPlayer.getVLCVout();
        if (vout != null && vout.areViewsAttached()) {
            try {
                mMediaPlayer.setScale(scale);
            } catch (Exception e) {
                Log.w(TAG, "safeSetScale failed: " + e.getMessage());
            }
        }
    }

    public void setAspectRatio(String aspectRatio) {
        if (!autoAspectRatio && mMediaPlayer != null) {
            mMediaPlayer.setAspectRatio(aspectRatio);
        }
    }

    public void setAutoAspectRatio(boolean auto) {
        autoAspectRatio = auto;
    }

    public void setAudioTrack(int track) {
        if (mMediaPlayer == null) return;
        IMedia.Track[] tracks = mMediaPlayer.getTracks(IMedia.Track.Type.Audio);
        if (tracks != null) {
            for (IMedia.Track t : tracks) {
                if (t.id.hashCode() == track || t.id.equals(String.valueOf(track))) {
                    mMediaPlayer.selectTrack(t.id);
                    return;
                }
            }
        }
    }

    public void setTextTrack(int track) {
        if (mMediaPlayer == null) return;
        if (track == -1) {
            mMediaPlayer.unselectTrackType(IMedia.Track.Type.Text);
            return;
        }
        IMedia.Track[] tracks = mMediaPlayer.getTracks(IMedia.Track.Type.Text);
        if (tracks != null) {
            for (IMedia.Track t : tracks) {
                if (t.id.hashCode() == track || t.id.equals(String.valueOf(track))) {
                    mMediaPlayer.selectTrack(t.id);
                    return;
                }
            }
        }
    }

    public void stopPlayer() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
        }
    }

    public void setAcceptInvalidCertificates(boolean accept) {
        this.acceptInvalidCertificates = accept;
    }

    public void setPictureInPictureEnabled(boolean enabled) {
        boolean wasEnabled = mPictureInPictureEnabled;
        mPictureInPictureEnabled = enabled;
        
        if (enabled && !wasEnabled && isAttachedToWindow()) {
            registerPipCallbacks();
        } else if (!enabled && wasEnabled) {
            unregisterPipCallbacks();
        }
    }
    
    public void setPlayInPictureInPicture(boolean play) {
        mPlayInPictureInPicture = play;
    }

    public boolean enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        if (!mPictureInPictureEnabled) return false;
        
        Activity activity = themedReactContext.getCurrentActivity();
        if (activity == null) return false;
        
        try {
            int aspectWidth = mVideoWidth > 0 ? mVideoWidth : 16;
            int aspectHeight = mVideoHeight > 0 ? mVideoHeight : 9;
            int gcd = gcd(aspectWidth, aspectHeight);
            Rational aspectRatio = new Rational(aspectWidth / gcd, aspectHeight / gcd);
            Log.i(TAG, "enterPictureInPicture: using aspect ratio " + aspectRatio);
            
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(false);
            }
            return activity.enterPictureInPictureMode(builder.build());
        } catch (Exception e) {
            Log.e(TAG, "enterPictureInPicture failed: " + e.getMessage());
            return false;
        }
    }
    
    private int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private void registerPipCallbacks() {
        if (mPipCallbacks != null) return;
        
        mPipCallbacks = new ComponentCallbacks2() {
            @Override
            public void onTrimMemory(int level) {}
            
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                Activity activity = themedReactContext.getCurrentActivity();
                if (activity == null) return;
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    boolean inPip = activity.isInPictureInPictureMode();
                    if (inPip != mIsInPipMode) {
                        handlePipModeChanged(inPip);
                    } else if (!inPip) {
                        mPipHandler.postDelayed(() -> updateVideoSurfaces(), 150);
                    }
                }
            }
            
            @Override
            public void onLowMemory() {}
        };
        
        themedReactContext.registerComponentCallbacks(mPipCallbacks);
        setupGlobalLayoutListener();
    }
    
    private void unregisterPipCallbacks() {
        if (mPipCallbacks != null) {
            themedReactContext.unregisterComponentCallbacks(mPipCallbacks);
            mPipCallbacks = null;
        }
        removeGlobalLayoutListener();
    }

    private void setupGlobalLayoutListener() {
        if (mPipLayoutChangeListener != null) return;
        
        mPipLayoutChangeListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int newWidth = right - left;
                int newHeight = bottom - top;
                
                if (newWidth == mLastLayoutWidth && newHeight == mLastLayoutHeight) {
                    return;
                }
                
                Log.i(TAG, "onLayoutChange: " + mLastLayoutWidth + "x" + mLastLayoutHeight + 
                      " -> " + newWidth + "x" + newHeight + ", inPip=" + mIsInPipMode);
                
                mLastLayoutWidth = newWidth;
                mLastLayoutHeight = newHeight;
            }
        };
        
        addOnLayoutChangeListener(mPipLayoutChangeListener);
        if (mSurfaceView != null) {
            mSurfaceView.addOnLayoutChangeListener(mPipLayoutChangeListener);
        }
        Log.i(TAG, "setupGlobalLayoutListener: registered layout change listeners");
    }
    
    private void restoreFullscreenWindowSize() {
        if (mMediaPlayer == null || mIsInPipMode) return;
        updateVideoSurfaces();
    }
    
    private int[] getPipWindowSize(Activity activity) {
        int[] size = new int[2];
        if (activity == null) return size;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            size[0] = bounds.width();
            size[1] = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            size[0] = metrics.widthPixels;
            size[1] = metrics.heightPixels;
        }
        return size;
    }
    
    private void removeGlobalLayoutListener() {
        if (mGlobalLayoutListener != null) {
            getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
            mGlobalLayoutListener = null;
        }
        if (mPipLayoutChangeListener != null) {
            removeOnLayoutChangeListener(mPipLayoutChangeListener);
            if (mSurfaceView != null) {
                mSurfaceView.removeOnLayoutChangeListener(mPipLayoutChangeListener);
            }
            mPipLayoutChangeListener = null;
        }
    }

    private void handlePipModeChanged(boolean isInPip) {
        Log.i(TAG, "handlePipModeChanged: " + isInPip);
        mIsInPipMode = isInPip;
        
        if (isInPip) {
            if (mMediaPlayer != null) {
                mMediaPlayer.updateVideoSurfaces();
            }
            updateVideoSurfaces();
        } else {
            mPipHandler.postDelayed(() -> {
                if (mMediaPlayer != null) {
                    mMediaPlayer.updateVideoSurfaces();
                }
                updateVideoSurfaces();
            }, 200);
        }
        
        emitPipStatusChanged(isInPip);
    }

    private void cancelPendingPipCallbacks() {
        if (mPendingPipDimensionCheck != null) {
            mPipHandler.removeCallbacks(mPendingPipDimensionCheck);
            mPendingPipDimensionCheck = null;
        }
        if (mPendingPipRestore != null) {
            mPipHandler.removeCallbacks(mPendingPipRestore);
            mPendingPipRestore = null;
        }
    }

    private void clearPipDimensions() {
        mPipTargetWidth = 0;
        mPipTargetHeight = 0;
        Log.i(TAG, "Exited PiP mode");
    }

    private void emitPipStatusChanged(boolean isInPip) {
        WritableMap map = Arguments.createMap();
        map.putBoolean("isInPictureInPicture", isInPip);
        
        if (isInPip) {
            Activity activity = themedReactContext.getCurrentActivity();
            int[] size = getPipWindowSize(activity);
            if (size[0] > 0 && size[1] > 0) {
                map.putInt("width", size[0]);
                map.putInt("height", size[1]);
            }
        }
        
        eventEmitter.onPictureInPictureStatusChanged(map);
    }

    public boolean doSnapshot(String path) {
        return false;
    }

    public void startRecording(String recordingPath) {
        if (mMediaPlayer != null && recordingPath != null) {
            mMediaPlayer.record(recordingPath, true);
        }
    }

    public void stopRecording() {
        if (mMediaPlayer != null) {
            mMediaPlayer.record(null, false);
        }
    }
}
