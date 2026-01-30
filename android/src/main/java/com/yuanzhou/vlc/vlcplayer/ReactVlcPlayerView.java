package com.yuanzhou.vlc.vlcplayer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Rational;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
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
class ReactVlcPlayerView extends TextureView implements
        LifecycleEventListener,
        TextureView.SurfaceTextureListener,
        AudioManager.OnAudioFocusChangeListener {

    private static final String TAG = "ReactVlcPlayerView";
    private final String tag = "ReactVlcPlayerView";

    private final VideoEventEmitter eventEmitter;
    private LibVLC libvlc;
    private MediaPlayer mMediaPlayer = null;
    private boolean mMuted = false;
    private boolean isSurfaceViewDestory;
    private String src;
    private String _subtitleUri;
    private boolean netStrTag;
    private ReadableMap srcMap;
    private int mVideoHeight = 0;
    private TextureView surfaceView;
    private Surface surfaceVideo;
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
    private int mSurfaceTextureWidth = 0;
    private int mSurfaceTextureHeight = 0;
    private ComponentCallbacks2 mPipCallbacks = null;
    private Handler mPipHandler = new Handler(Looper.getMainLooper());
    private long mLastPipApplyTime = 0;
    private static final long PIP_APPLY_DEBOUNCE_MS = 50;
    private Runnable mPendingPipDimensionCheck = null;
    private Runnable mPendingPipRestore = null;
    private ViewTreeObserver.OnGlobalLayoutListener mGlobalLayoutListener = null;

    private float mProgressUpdateInterval = 0;
    private Handler mProgressUpdateHandler = new Handler();
    private Runnable mProgressUpdateRunnable = null;
    
    private Handler mLayoutHandler = new Handler(Looper.getMainLooper());
    private Runnable mLayoutRunnable = null;
    private int mPendingWidth = 0;
    private int mPendingHeight = 0;

    private final ThemedReactContext themedReactContext;
    private final AudioManager audioManager;

    private WritableMap mVideoInfo = null;
    private String mVideoInfoHash = null;


    public ReactVlcPlayerView(ThemedReactContext context) {
        super(context);
        this.eventEmitter = new VideoEventEmitter(context);
        this.themedReactContext = context;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenHeight = dm.heightPixels;
        screenWidth = dm.widthPixels;
        this.setSurfaceTextureListener(this);

        this.addOnLayoutChangeListener(onLayoutChangeListener);
        context.addLifecycleEventListener(this);
    }


    @Override
    public void setId(int id) {
        super.setId(id);
        eventEmitter.setViewId(id);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mIsInPipMode && mPipTargetWidth > 0 && mPipTargetHeight > 0) {
            int w = MeasureSpec.makeMeasureSpec(mPipTargetWidth, MeasureSpec.EXACTLY);
            int h = MeasureSpec.makeMeasureSpec(mPipTargetHeight, MeasureSpec.EXACTLY);
            super.onMeasure(w, h);
            Log.d(TAG, "onMeasure (PiP forced): " + mPipTargetWidth + "x" + mPipTargetHeight);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
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
        } else {
            Log.i(TAG, "Skipping stopPlayback during PiP mode");
        }
    }

    // LifecycleEventListener implementation

    @Override
    public void onHostResume() {
        if (mMediaPlayer != null && isSurfaceViewDestory && isHostPaused) {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (!vlcOut.areViewsAttached()) {
                // vlcOut.setVideoSurface(this.getHolder().getSurface(), this.getHolder());
                vlcOut.attachViews(onNewVideoLayoutListener);
                isSurfaceViewDestory = false;
                isPaused = false;
                // this.getHolder().setKeepScreenOn(true);
                mMediaPlayer.play();
            }
        }
    }


    @Override
    public void onHostPause() {
        Log.i(TAG, "onHostPause: mIsInPipMode=" + mIsInPipMode + ", pipEnabled=" + mPictureInPictureEnabled);
        
        if (mIsInPipMode && mPlayInPictureInPicture) {
            Log.i(TAG, "Skipping pause - in PiP mode with playInPictureInPicture=true");
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
                    Log.i(TAG, "onHostPause (delayed): Skipping pause - entered PiP");
                    return;
                }
                
                doPause();
            }, 150);
        } else {
            doPause();
        }
    }
    
    private void doPause() {
        Log.i(TAG, "doPause: Pausing playback");
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


    // AudioManager.OnAudioFocusChangeListener implementation
    @Override
    public void onAudioFocusChange(int focusChange) {
    }

    private void setProgressUpdateRunnable() {
        if (mMediaPlayer != null && mProgressUpdateInterval > 0){
            new Thread() {
                @Override
                public void run() {
                    super.run();

                    mProgressUpdateRunnable = new Runnable() {
                        @Override
                        public void run() {
                            if (mMediaPlayer != null && !isPaused) {
                                long currentTime = 0;
                                long totalLength = 0;
                                WritableMap event = Arguments.createMap();
                                boolean isPlaying = mMediaPlayer.isPlaying();
                                currentTime = mMediaPlayer.getTime();
                                float position = mMediaPlayer.getPosition();
                                totalLength = mMediaPlayer.getLength();
                                WritableMap map = Arguments.createMap();
                                map.putBoolean("isPlaying", isPlaying);
                                map.putDouble("position", position);
                                map.putDouble("currentTime", currentTime);
                                map.putDouble("duration", totalLength);
                                updateVideoInfo();
                                eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_PROGRESS);
                            }

                            mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, Math.round(mProgressUpdateInterval));
                        }
                    };

                    mProgressUpdateHandler.postDelayed(mProgressUpdateRunnable, 0);
                }
            }.start();
        }
    }


    /*************
     * Events  Listener
     *************/

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
                    Log.d(TAG, "onLayoutChange: detected PiP before callback, setting flag");
                    mIsInPipMode = true;
                    mPipTransitionInProgress = true;
                    schedulePipDimensionCheck(0);
                }
                
                // In PiP mode, ignore React layout dimensions - trust GlobalLayoutListener
                // React's layout system doesn't know about PiP window size
                Log.d(TAG, "onLayoutChange (PiP): ignoring React layout " + w + "x" + h + ", keeping target=" + mPipTargetWidth + "x" + mPipTargetHeight);
                return;
            }
            
            mPendingWidth = w;
            mPendingHeight = h;
            
            if (mLayoutRunnable != null) {
                mLayoutHandler.removeCallbacks(mLayoutRunnable);
            }
            
            mLayoutRunnable = () -> {
                if (mIsInPipMode) {
                    Log.d(TAG, "onLayoutChange (debounced): skipped (PiP mode)");
                    return;
                }
                if (mMediaPlayer != null && mPendingWidth > 0 && mPendingHeight > 0) {
                    IVLCVout vlcOut = mMediaPlayer.getVLCVout();
                    vlcOut.setWindowSize(mPendingWidth, mPendingHeight);
                    updateLastFullscreenSize(mPendingWidth, mPendingHeight);
                    Log.d(TAG, "onLayoutChange (debounced): setWindowSize(" + mPendingWidth + ", " + mPendingHeight + ")");
                    if (autoAspectRatio) {
                        mMediaPlayer.setAspectRatio(mPendingWidth + ":" + mPendingHeight);
                    }
                }
            };
            
            mLayoutHandler.postDelayed(mLayoutRunnable, LAYOUT_DEBOUNCE_MS);
        }
    };

    /**
     * 播放过程中的时间事件监听
     */
    private MediaPlayer.EventListener mPlayerListener = new MediaPlayer.EventListener() {
        long currentTime = 0;
        long totalLength = 0;

        @Override
        public void onEvent(MediaPlayer.Event event) {
            boolean isPlaying = mMediaPlayer.isPlaying();
            currentTime = mMediaPlayer.getTime();
            float position = mMediaPlayer.getPosition();
            totalLength = mMediaPlayer.getLength();
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
                case MediaPlayer.Event.RecordChanged:
                    map.putString("type", "RecordingPath");
                    map.putBoolean("isRecording", event.getRecording());
                    // Record started emits and event with the record path (but no file).
                    // Only want to emit when recording has stopped and the recording is created.
                    if(!event.getRecording() && event.getRecordPath() != null) {
                        map.putString("recordPath", event.getRecordPath());
                    }
                    eventEmitter.sendEvent(map, VideoEventEmitter.EVENT_RECORDING_STATE);
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
            if (width * height == 0)
                return;
            // store video size
            mVideoWidth = width;
            mVideoHeight = height;
            mVideoVisibleWidth = visibleWidth;
            mVideoVisibleHeight = visibleHeight;
            mSarNum = sarNum;
            mSarDen = sarDen;
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

    IVLCVout.Callback callback = new IVLCVout.Callback() {
        @Override
        public void onSurfacesCreated(IVLCVout ivlcVout) {
            isSurfaceViewDestory = false;
        }

        @Override
        public void onSurfacesDestroyed(IVLCVout ivlcVout) {
            isSurfaceViewDestory = true;
        }

    };
    
    /*************
     * MediaPlayer
     *************/


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
        if (this.getSurfaceTexture() == null) {
            return;
        }
        try {
            final ArrayList<String> cOptions = new ArrayList<>();
            String uriString = srcMap.hasKey("uri") ? srcMap.getString("uri") : null;
            //String extension = srcMap.hasKey("type") ? srcMap.getString("type") : null;
            boolean isNetwork = srcMap.hasKey("isNetwork") ? srcMap.getBoolean("isNetwork") : false;
            boolean autoplay = srcMap.hasKey("autoplay") ? srcMap.getBoolean("autoplay") : true;
            int initType = srcMap.hasKey("initType") ? srcMap.getInt("initType") : 1;
            ReadableArray mediaOptions = srcMap.hasKey("mediaOptions") ? srcMap.getArray("mediaOptions") : null;
            ReadableArray initOptions = srcMap.hasKey("initOptions") ? srcMap.getArray("initOptions") : null;
            Integer hwDecoderEnabled = srcMap.hasKey("hwDecoderEnabled") ? srcMap.getInt("hwDecoderEnabled") : null;
            Integer hwDecoderForced = srcMap.hasKey("hwDecoderForced") ? srcMap.getInt("hwDecoderForced") : null;

            if (initOptions != null) {
                ArrayList options = initOptions.toArrayList();
                for (int i = 0; i < options.size() - 1; i++) {
                    String option = (String) options.get(i);
                    cOptions.add(option);
                }
            }
            // Create LibVLC
            if (initType == 1) {
                libvlc = new LibVLC(getContext());
            } else {
                libvlc = new LibVLC(getContext(), cOptions);
            }
            // Create media player
            mMediaPlayer = new MediaPlayer(libvlc);
            setMutedModifier(mMuted);
            mMediaPlayer.setEventListener(mPlayerListener);
            
            // Register dialog callbacks for certificate handling
            Dialog.setCallbacks(libvlc, new Dialog.Callbacks() {
                @Override
                public void onDisplay(Dialog.QuestionDialog dialog) {
                    handleCertificateDialog(dialog);
                }
                
                @Override
                public void onDisplay(Dialog.ErrorMessage dialog) {
                    // Handle error dialogs if needed
                }
                
                @Override
                public void onDisplay(Dialog.LoginDialog dialog) {
                    // Handle login dialogs if needed
                }
                
                @Override
                public void onDisplay(Dialog.ProgressDialog dialog) {
                    // Handle progress dialogs if needed
                }
                
                @Override
                public void onCanceled(Dialog dialog) {
                    // Handle dialog cancellation
                }
                
                @Override
                public void onProgressUpdate(Dialog.ProgressDialog dialog) {
                    // Handle progress updates
                }
            });
            //this.getHolder().setKeepScreenOn(true);
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            if (mVideoWidth > 0 && mVideoHeight > 0 && !mIsInPipMode && !mPipTransitionInProgress) {
                vlcOut.setWindowSize(mVideoWidth, mVideoHeight);
                if (autoAspectRatio) {
                    mMediaPlayer.setAspectRatio(mVideoWidth + ":" + mVideoHeight);
                }
                //mMediaPlayer.setAspectRatio(mVideoWidth+":"+mVideoHeight);
            } else if (mIsInPipMode && mPipTargetWidth > 0 && mPipTargetHeight > 0) {
                if (mSurfaceTextureWidth == mPipTargetWidth && mSurfaceTextureHeight == mPipTargetHeight) {
                    vlcOut.setWindowSize(mPipTargetWidth, mPipTargetHeight);
                    Log.d(TAG, "createPlayer: using PiP dimensions " + mPipTargetWidth + "x" + mPipTargetHeight);
                } else if (mSurfaceTextureWidth > 0 && mSurfaceTextureHeight > 0) {
                    vlcOut.setWindowSize(mSurfaceTextureWidth, mSurfaceTextureHeight);
                    Log.d(TAG, "createPlayer: using surface dimensions " + mSurfaceTextureWidth + "x" + mSurfaceTextureHeight + " (PiP size pending)");
                }
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            Media m = null;
            if (isNetwork) {
                Uri uri = Uri.parse(uriString);
                m = new Media(libvlc, uri);
            } else {
                m = new Media(libvlc, uriString);
            }
            m.setEventListener(mMediaListener);
            if (hwDecoderEnabled != null && hwDecoderForced != null) {
                boolean hmEnabled = false;
                boolean hmForced = false;
                if (hwDecoderEnabled >= 1) {
                    hmEnabled = true;
                }
                if (hwDecoderForced >= 1) {
                    hmForced = true;
                }
                m.setHWDecoderEnabled(hmEnabled, hmForced);
            }
            //添加media  option
            if (mediaOptions != null) {
                ArrayList options = mediaOptions.toArrayList();
                for (int i = 0; i < options.size() - 1; i++) {
                    String option = (String) options.get(i);
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

            if (!vlcOut.areViewsAttached()) {
                vlcOut.addCallback(callback);
                // vlcOut.setVideoSurface(this.getSurfaceTexture());
                //vlcOut.setVideoSurface(this.getHolder().getSurface(), this.getHolder());
                //vlcOut.attachViews(onNewVideoLayoutListener);
                vlcOut.setVideoSurface(this.getSurfaceTexture());
                vlcOut.attachViews(onNewVideoLayoutListener);
                // vlcOut.attachSurfaceSlave(surfaceVideo,null,onNewVideoLayoutListener);
                //vlcOut.setVideoView(this);
                //vlcOut.attachViews(onNewVideoLayoutListener);
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
        } catch (Exception e) {
            e.printStackTrace();
            //Toast.makeText(getContext(), "Error creating player!", Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayer() {
        if (libvlc == null)
            return;
        
        final IVLCVout vout = mMediaPlayer.getVLCVout();
        vout.removeCallback(callback);
        vout.detachViews();
        mMediaPlayer.release();
        libvlc.release();
        libvlc = null;

        if(mProgressUpdateRunnable != null){
            mProgressUpdateHandler.removeCallbacks(mProgressUpdateRunnable);
        }
    }

    /**
     * 视频进度调整
     *
     * @param position
     */
    public void setPosition(float position) {
        if (mMediaPlayer != null) {
            if (position >= 0 && position <= 1) {
                mMediaPlayer.setPosition(position);
            }
        }
    }

    public void setSubtitleUri(String subtitleUri) {
        _subtitleUri = subtitleUri;
        if (mMediaPlayer != null) {
            mMediaPlayer.addSlave(Media.Slave.Type.Subtitle,  _subtitleUri, true);
        }
    }

    /**
     * 设置资源路径
     *
     * @param uri
     * @param isNetStr
     */
    public void setSrc(String uri, boolean isNetStr, boolean autoplay) {
        this.src = uri;
        this.netStrTag = isNetStr;
        createPlayer(autoplay, false);
    }

    public void setSrc(ReadableMap src) {
        this.srcMap = src;
        createPlayer(true, false);
    }

    /**
     * 改变播放速率
     *
     * @param rateModifier
     */
    public void setRateModifier(float rateModifier) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setRate(rateModifier);
        }
    }

    public void setmProgressUpdateInterval(float interval) {
        mProgressUpdateInterval = interval;
        createPlayer(true, false);
    }


    /**
     * 改变声音大小
     *
     * @param volumeModifier
     */
    public void setVolumeModifier(int volumeModifier) {
        if (mMediaPlayer != null) {
            mMediaPlayer.setVolume(volumeModifier);
        }
    }

    /**
     * 改变静音状态
     *
     * @param muted
     */
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

    /**
     * 改变播放状态
     *
     * @param paused
     */
    public void setPausedModifier(boolean paused) {
        Log.i("paused:", "" + paused + ":" + mMediaPlayer);
        if (mMediaPlayer != null) {
            if (paused) {
                isPaused = true;
                mMediaPlayer.pause();
            } else {
                isPaused = false;
                mMediaPlayer.play();
                Log.i("do play:", true + "");
            }
        } else {
            createPlayer(!paused, false);
        }
    }


    /**
     * Take a screenshot of the current video frame
     *
     * @param path The file path where to save the screenshot
     * @return boolean indicating if the screenshot was taken successfully
     */
    public boolean doSnapshot(String path) {
        if (mMediaPlayer != null) {
            try {
                Bitmap bitmap = getBitmap();
                if (bitmap == null) {
                    WritableMap event = Arguments.createMap();
                    event.putBoolean("success", false);
                    event.putString("error", "Failed to capture bitmap");
                    eventEmitter.sendEvent(event, VideoEventEmitter.EVENT_ON_SNAPSHOT);
                    return false;
                }

                File file = new File(path);
                file.getParentFile().mkdirs();

                FileOutputStream out = new FileOutputStream(file);

                String extension = path.substring(path.lastIndexOf(".") + 1);
                if (extension.equals("png")) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                } else {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
                }
                out.flush();
                out.close();

                bitmap.recycle();

                WritableMap event = Arguments.createMap();
                event.putBoolean("success", true);
                event.putString("path", path);
                eventEmitter.sendEvent(event, VideoEventEmitter.EVENT_ON_SNAPSHOT);
                return true;
            } catch (Exception e) {
                WritableMap event = Arguments.createMap();
                event.putBoolean("success", false);
                event.putString("error", e.getMessage());
                eventEmitter.sendEvent(event, VideoEventEmitter.EVENT_ON_SNAPSHOT);
                e.printStackTrace();
                return false;
            }
        }
        WritableMap event = Arguments.createMap();
        event.putBoolean("success", false);
        event.putString("error", "MediaPlayer is null");
        eventEmitter.sendEvent(event, VideoEventEmitter.EVENT_ON_SNAPSHOT);
        return false;
    }


    /**
     * 重新加载视频
     *
     * @param autoplay
     */
    public void doResume(boolean autoplay) {
        createPlayer(autoplay, true);
    }


    public void setRepeatModifier(boolean repeat) {
    }


    /**
     * 改变宽高比
     *
     * @param aspectRatio
     */
    public void setAspectRatio(String aspectRatio) {
        if (!autoAspectRatio && mMediaPlayer != null) {
            mMediaPlayer.setAspectRatio(aspectRatio);
        }
    }

    public void setAutoAspectRatio(boolean auto) {
        autoAspectRatio = auto;
    }

    private void safeSetScale(float scale) {
        if (mMediaPlayer == null) return;
        IVLCVout vout = mMediaPlayer.getVLCVout();
        if (vout != null && vout.areViewsAttached()) {
            try {
                mMediaPlayer.setScale(scale);
            } catch (Exception e) {
                Log.w(tag, "safeSetScale failed: " + e.getMessage());
            }
        }
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

    public void startRecording(String recordingPath) {
        if(mMediaPlayer == null) return;
        if(recordingPath != null) {
            mMediaPlayer.record(recordingPath, true);
        }
    }

    public void stopRecording() {
        if(mMediaPlayer == null) return;
        mMediaPlayer.record(null, false);
    }

    public void stopPlayer() {
        if(mMediaPlayer == null) return;
        mMediaPlayer.stop();
    }

    private void handleCertificateDialog(Dialog.QuestionDialog dialog) {
        String title = dialog.getTitle();
        String text = dialog.getText();
        
        Log.i(TAG, "Certificate dialog - Title: " + title + ", Text: " + text);
        
        // Check if it's a certificate validation dialog
        if (text != null && (text.contains("certificate") || text.contains("SSL") || text.contains("TLS") || text.contains("cert"))) {
            if (acceptInvalidCertificates) {
                // Auto-accept invalid certificate
                dialog.postAction(1); // Action 1 typically means "Accept"
                Log.i(TAG, "Auto-accepted certificate dialog");
            } else {
                // Reject invalid certificate (default secure behavior)
                dialog.postAction(2); // Action 2 typically means "Reject"
                Log.i(TAG, "Rejected certificate dialog (acceptInvalidCertificates=false)");
            }
        } else {
            // For non-certificate dialogs, dismiss
            dialog.dismiss();
            Log.i(TAG, "Dismissed non-certificate dialog");
        }
    }
    
    public void setAcceptInvalidCertificates(boolean accept) {
        this.acceptInvalidCertificates = accept;
        Log.i(TAG, "Set acceptInvalidCertificates to: " + accept);
    }

    public void setPictureInPictureEnabled(boolean enabled) {
        Log.i(TAG, "setPictureInPictureEnabled: " + enabled);
        boolean wasEnabled = mPictureInPictureEnabled;
        mPictureInPictureEnabled = enabled;
        
        if (enabled && !wasEnabled && isAttachedToWindow()) {
            registerPipCallbacks();
        } else if (!enabled && wasEnabled) {
            unregisterPipCallbacks();
        }
    }
    
    public void setPlayInPictureInPicture(boolean play) {
        Log.i(TAG, "setPlayInPictureInPicture: " + play);
        mPlayInPictureInPicture = play;
    }
    
    public boolean enterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "enterPictureInPicture: PiP requires API 26+");
            return false;
        }
        
        if (!mPictureInPictureEnabled) {
            Log.w(TAG, "enterPictureInPicture: PiP not enabled");
            return false;
        }
        
        Activity activity = themedReactContext.getCurrentActivity();
        if (activity == null) {
            Log.w(TAG, "enterPictureInPicture: No activity");
            return false;
        }
        
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
            
            Rect sourceRect = getSourceRectHint();
            if (sourceRect != null) {
                builder.setSourceRectHint(sourceRect);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setSeamlessResizeEnabled(false);
            }
            
            return activity.enterPictureInPictureMode(builder.build());
        } catch (Exception e) {
            Log.e(TAG, "enterPictureInPicture failed: " + e.getMessage());
            return false;
        }
    }
    
    public boolean enterPictureInPictureV2() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "enterPictureInPictureV2: PiP requires API 26+");
            return false;
        }
        
        if (!mPictureInPictureEnabled) {
            Log.w(TAG, "enterPictureInPictureV2: PiP not enabled");
            return false;
        }
        
        try {
            Log.i(TAG, "enterPictureInPictureV2: Launching PipHostActivity");
            PipHostActivity.Companion.launch(themedReactContext);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "enterPictureInPictureV2 failed: " + e.getMessage());
            return false;
        }
    }
    
    private Rect getSourceRectHint() {
        int[] location = new int[2];
        getLocationOnScreen(location);
        return new Rect(
            location[0],
            location[1],
            location[0] + getWidth(),
            location[1] + getHeight()
        );
    }
    
    private void registerPipCallbacks() {
        if (mPipCallbacks != null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        
        Activity activity = themedReactContext.getCurrentActivity();
        if (activity == null) return;
        
        Log.i(TAG, "Registering PiP callbacks");
        
        mPipCallbacks = new ComponentCallbacks2() {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    boolean inPip = activity.isInPictureInPictureMode();
                    Log.d(TAG, "onConfigurationChanged: inPip=" + inPip + ", mIsInPipMode=" + mIsInPipMode);
                    if (inPip != mIsInPipMode) {
                        handlePipModeChanged(inPip);
                    } else if (inPip && mIsInPipMode) {
                        handlePipWindowResized();
                    }
                }
            }
            
            @Override
            public void onLowMemory() {}
            
            @Override
            public void onTrimMemory(int level) {}
        };
        
        activity.registerComponentCallbacks(mPipCallbacks);
        
        mGlobalLayoutListener = () -> {
            if (!mIsInPipMode) return;
            
            Activity act = themedReactContext.getCurrentActivity();
            if (act == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
            if (!act.isInPictureInPictureMode()) return;
            
            Rect bounds = act.getWindowManager().getCurrentWindowMetrics().getBounds();
            int w = bounds.width();
            int h = bounds.height();
            
            if (w > 0 && h > 0 && (w != mPipTargetWidth || h != mPipTargetHeight)) {
                Log.d(TAG, "GlobalLayoutListener (PiP resize): " + mPipTargetWidth + "x" + mPipTargetHeight + " -> " + w + "x" + h);
                applyPipDimensionsImmediate(w, h);
            }
        };
        getViewTreeObserver().addOnGlobalLayoutListener(mGlobalLayoutListener);
    }
    
    private void unregisterPipCallbacks() {
        if (mGlobalLayoutListener != null) {
            getViewTreeObserver().removeOnGlobalLayoutListener(mGlobalLayoutListener);
            mGlobalLayoutListener = null;
        }
        
        if (mPipCallbacks == null) return;
        
        Activity activity = themedReactContext.getCurrentActivity();
        if (activity != null) {
            activity.unregisterComponentCallbacks(mPipCallbacks);
        }
        mPipCallbacks = null;
        Log.i(TAG, "Unregistered PiP callbacks");
    }
    
    private void handlePipModeChanged(boolean isInPip) {
        Log.i(TAG, "handlePipModeChanged: " + isInPip);
        mIsInPipMode = isInPip;
        
        cancelPendingPipCallbacks();
        
        if (isInPip) {
            mPipTransitionInProgress = true;
            schedulePipDimensionCheck(0);
        } else {
            mPipTransitionInProgress = false;
            clearPipDimensions();
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
    
    private void schedulePipDimensionCheck(int attempt) {
        int delay = attempt == 0 ? 16 : (attempt == 1 ? 50 : (attempt == 2 ? 100 : 200));
        
        if (mPendingPipDimensionCheck != null) {
            mPipHandler.removeCallbacks(mPendingPipDimensionCheck);
        }
        
        mPendingPipDimensionCheck = () -> {
            mPendingPipDimensionCheck = null;
            
            if (!mIsInPipMode) {
                mPipTransitionInProgress = false;
                return;
            }
            
            int viewW = getWidth();
            int viewH = getHeight();
            
            Activity activity = themedReactContext.getCurrentActivity();
            int windowW = 0;
            int windowH = 0;
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                windowW = bounds.width();
                windowH = bounds.height();
            }
            
            Log.i(TAG, "PiP dimension check #" + attempt + ": view=" + viewW + "x" + viewH + ", window=" + windowW + "x" + windowH);
            
            int w = (windowW > 0 && windowW < viewW) ? windowW : viewW;
            int h = (windowH > 0 && windowH < viewH) ? windowH : viewH;
            
            boolean dimensionsLookLikePip = (w > 0 && h > 0 && w < 1000 && h < 800);
            
            if (dimensionsLookLikePip) {
                applyPipDimensionsImmediate(w, h);
            }
            
            if (attempt < 6) {
                schedulePipDimensionCheck(attempt + 1);
            } else {
                mPipTransitionInProgress = false;
                Log.i(TAG, "PiP dimension check: transition complete");
            }
        };
        
        mPipHandler.postDelayed(mPendingPipDimensionCheck, delay);
    }
    
    private int mLastFullscreenWidth = 0;
    private int mLastFullscreenHeight = 0;
    
    private void updateLastFullscreenSize(int width, int height) {
        if (width > 500 && height > 500 && !mIsInPipMode) {
            mLastFullscreenWidth = width;
            mLastFullscreenHeight = height;
        }
    }
    
    private void forceVideoReinit(int width, int height) {
        if (mMediaPlayer == null) return;
        
        Log.i(TAG, "forceVideoReinit: " + width + "x" + height);
        
        try {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            
            if (vlcOut.areViewsAttached()) {
                vlcOut.detachViews();
            }
            
            SurfaceTexture surfaceTexture = this.getSurfaceTexture();
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(width, height);
                vlcOut.setVideoSurface(surfaceTexture);
            }
            
            vlcOut.setWindowSize(width, height);
            vlcOut.attachViews(onNewVideoLayoutListener);
            
            mMediaPlayer.setAspectRatio(width + ":" + height);
            safeSetScale(0);
            
            Log.i(TAG, "forceVideoReinit complete");
        } catch (Exception e) {
            Log.e(TAG, "forceVideoReinit failed: " + e.getMessage());
        }
    }
    
    private void applyPipDimensionsImmediate(int pipWindowWidth, int pipWindowHeight) {
        long now = System.currentTimeMillis();
        if (now - mLastPipApplyTime < PIP_APPLY_DEBOUNCE_MS && 
            pipWindowWidth == mPipTargetWidth && pipWindowHeight == mPipTargetHeight) {
            return;
        }
        mLastPipApplyTime = now;
        
        boolean dimensionsChanged = (pipWindowWidth != mPipTargetWidth || pipWindowHeight != mPipTargetHeight);
        boolean significantChange = dimensionsChanged && mPipTargetWidth > 0 && mPipTargetHeight > 0;
        
        mPipTargetWidth = pipWindowWidth;
        mPipTargetHeight = pipWindowHeight;
        
        if (mMediaPlayer == null) return;
        
        Log.i(TAG, "PiP apply: " + pipWindowWidth + "x" + pipWindowHeight + ", significantChange=" + significantChange);
        
        if (significantChange) {
            forceVideoReinit(pipWindowWidth, pipWindowHeight);
        } else {
            IVLCVout vlcOut = mMediaPlayer.getVLCVout();
            vlcOut.setWindowSize(pipWindowWidth, pipWindowHeight);
            mMediaPlayer.setAspectRatio(pipWindowWidth + ":" + pipWindowHeight);
            safeSetScale(0);
        }
    }
    
    private void clearPipScaleTransform() {
        setTransform(null);
        Log.i(TAG, "PiP: cleared transform");
    }
    
    private void applyPipDimensionsWithRetry(int attempt) {
        int delay = attempt == 0 ? 150 : (attempt == 1 ? 350 : 600);
        
        mPipHandler.postDelayed(() -> {
            if (!mIsInPipMode) {
                mPipTransitionInProgress = false;
                return;
            }
            
            Activity activity = themedReactContext.getCurrentActivity();
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                int w = bounds.width();
                int h = bounds.height();
                Log.i(TAG, "PiP verify attempt " + attempt + ": " + w + "x" + h + " (current: " + mPipTargetWidth + "x" + mPipTargetHeight + ")");
                if (w > 0 && h > 0 && (w != mPipTargetWidth || h != mPipTargetHeight)) {
                    applyPipDimensionsImmediate(w, h);
                }
            }
            
            if (attempt < 2) {
                applyPipDimensionsWithRetry(attempt + 1);
            } else {
                mPipTransitionInProgress = false;
                Log.i(TAG, "PiP transition complete");
            }
        }, delay);
    }
    
    private void handlePipWindowResized() {
        mPipHandler.postDelayed(() -> {
            if (!mIsInPipMode) return;
            
            Activity activity = themedReactContext.getCurrentActivity();
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                int w = bounds.width();
                int h = bounds.height();
                
                if (w > 0 && h > 0 && (w != mPipTargetWidth || h != mPipTargetHeight)) {
                    Log.i(TAG, "PiP window resized: " + mPipTargetWidth + "x" + mPipTargetHeight + " -> " + w + "x" + h);
                    applyPipDimensionsImmediate(w, h);
                }
            }
        }, 50);
    }
    
    private void clearPipDimensions() {
        mPipTargetWidth = 0;
        mPipTargetHeight = 0;
        
        clearPipScaleTransform();
        
        Log.i(TAG, "Exited PiP mode");
        
        emitPipStatusChanged(false);
        
        restoreWindowSizeAfterPip(0);
    }
    
    private void restoreWindowSizeAfterPip(int attempt) {
        if (mPendingPipRestore != null) {
            mPipHandler.removeCallbacks(mPendingPipRestore);
        }
        
        mPendingPipRestore = () -> {
            mPendingPipRestore = null;
            
            if (mIsInPipMode) {
                Log.i(TAG, "Post-PiP restore: cancelled, back in PiP");
                return;
            }
            
            int w = getWidth();
            int h = getHeight();
            
            Activity activity = themedReactContext.getCurrentActivity();
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !activity.isInPictureInPictureMode()) {
                Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                if (bounds.width() > w) {
                    w = bounds.width();
                    h = bounds.height();
                }
            }
            
            Log.i(TAG, "Post-PiP restore attempt " + attempt + ": size " + w + "x" + h);
            
            boolean needsRetry = (w <= 0 || h <= 0 || w < 400);
            
            if (mMediaPlayer != null && w > 0 && h > 0) {
                forceVideoReinit(w, h);
                mMediaPlayer.setAspectRatio(null);
                Log.i(TAG, "Post-PiP restore: forceVideoReinit(" + w + ", " + h + ")");
            }
            
            if (needsRetry && attempt < 5) {
                restoreWindowSizeAfterPip(attempt + 1);
            }
        };
        
        mPipHandler.postDelayed(mPendingPipRestore, attempt == 0 ? 100 : 200);
    }
    
    private void emitPipStatusChanged(boolean isInPip) {
        Activity activity = themedReactContext.getCurrentActivity();
        int width = 0;
        int height = 0;
        
        if (activity != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
                width = bounds.width();
                height = bounds.height();
            } else {
                width = getWidth();
                height = getHeight();
            }
        }
        
        WritableMap event = Arguments.createMap();
        event.putBoolean("isInPictureInPicture", isInPip);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int widthDp = density > 0 ? Math.round(width / density) : width;
        int heightDp = density > 0 ? Math.round(height / density) : height;
        event.putInt("width", widthDp);
        event.putInt("height", heightDp);
        eventEmitter.sendEvent(event, VideoEventEmitter.EVENT_PIP_STATUS_CHANGED);
    }
    
    @Deprecated
    public void setPipMode(boolean isInPipMode) {
        Log.i(TAG, "setPipMode (deprecated): " + isInPipMode);
        handlePipModeChanged(isInPipMode);
    }
    
    @Deprecated
    public void setPipWindowSize(int width, int height) {
        Log.i(TAG, "setPipWindowSize (deprecated): " + width + "x" + height);
        if (width > 0 && height > 0) {
            applyPipDimensionsImmediate(width, height);
        }
    }
    


    public void updateVideoSurfaces() {
        if (mMediaPlayer == null) {
            Log.w(TAG, "updateVideoSurfaces: no media player");
            return;
        }
        
        if (mIsInPipMode || mPipTransitionInProgress) {
            Log.d(TAG, "updateVideoSurfaces: skipped (PiP mode or transition)");
            return;
        }
        
        Log.i(TAG, "updateVideoSurfaces called");
        
        IVLCVout vlcOut = mMediaPlayer.getVLCVout();
        int width = getWidth();
        int height = getHeight();
        
        if (width > 0 && height > 0) {
            vlcOut.setWindowSize(width, height);
            Log.i(TAG, "Updated window size to: " + width + "x" + height);
            
            if (autoAspectRatio) {
                mMediaPlayer.setAspectRatio(width + ":" + height);
            }
        }
        
        post(() -> {
            requestLayout();
            invalidate();
        });
    }

    public boolean isInPipMode() {
        return mIsInPipMode;
    }

    public void cleanUpResources() {
        if (surfaceView != null) {
            surfaceView.removeOnLayoutChangeListener(onLayoutChangeListener);
        }
        stopPlayback();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
        mSurfaceTextureWidth = width;
        mSurfaceTextureHeight = height;
        updateLastFullscreenSize(width, height);
        surfaceVideo = new Surface(surface);
        createPlayer(true, false);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        Log.i(TAG, "onSurfaceTextureSizeChanged: " + width + "x" + height + ", inPip=" + mIsInPipMode + ", transition=" + mPipTransitionInProgress);
        
        if (width == mSurfaceTextureWidth && height == mSurfaceTextureHeight) {
            return;
        }
        
        mSurfaceTextureWidth = width;
        mSurfaceTextureHeight = height;

        if (mPipTransitionInProgress) {
            Log.d(TAG, "onSurfaceTextureSizeChanged: skipping during transition");
            return;
        }

        if (mIsInPipMode && mPipTargetWidth > 0 && mPipTargetHeight > 0) {
            if (mMediaPlayer != null) {
                IVLCVout vlcOut = mMediaPlayer.getVLCVout();
                vlcOut.setWindowSize(mPipTargetWidth, mPipTargetHeight);
                mMediaPlayer.setAspectRatio(mPipTargetWidth + ":" + mPipTargetHeight);
                safeSetScale(0);
                Log.i(TAG, "onSurfaceTextureSizeChanged (PiP): applied " + mPipTargetWidth + "x" + mPipTargetHeight);
            }
            return;
        }
        
        boolean dimensionsLookLikePip = (width > 0 && height > 0 && width < 1000 && height < 800);
        if (dimensionsLookLikePip && !mIsInPipMode) {
            Activity activity = themedReactContext.getCurrentActivity();
            boolean systemInPip = false;
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                systemInPip = activity.isInPictureInPictureMode();
            }
            if (systemInPip) {
                Log.d(TAG, "onSurfaceTextureSizeChanged: detected PiP before flag update");
                mIsInPipMode = true;
                mPipTargetWidth = width;
                mPipTargetHeight = height;
                applyPipDimensionsImmediate(width, height);
                return;
            }
        }
        
        if (dimensionsLookLikePip) {
            if (mMediaPlayer != null) {
                IVLCVout vlcOut = mMediaPlayer.getVLCVout();
                vlcOut.setWindowSize(width, height);
                mMediaPlayer.setAspectRatio(null);
                safeSetScale(0);
                Log.i(TAG, "onSurfaceTextureSizeChanged: applied " + width + "x" + height);
            }
            mPipTargetWidth = width;
            mPipTargetHeight = height;
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Log.i("onSurfaceTextureUpdated", "onSurfaceTextureUpdated");
    }

    private final IMedia.EventListener mMediaListener = new IMedia.EventListener() {
        @Override
        public void onEvent(IMedia.Event event) {
            switch (event.type) {
                case IMedia.Event.MetaChanged:
                    Log.i(tag, "IMedia.Event.MetaChanged:  =" + event.getMetaId());
                    break;
                case IMedia.Event.ParsedChanged:
                    Log.i(tag, "IMedia.Event.ParsedChanged  =" + event.getMetaId());
                    break;
                default:
                    Log.i(tag, "IMedia.Event.type=" + event.type + "   eventgetParsedStatus=" + event.getParsedStatus());
                    break;
            }
        }
    };

    private void updateVideoInfo() {
        StringBuilder infoHash = new StringBuilder();
        
        infoHash.append("duration:").append(mMediaPlayer.getLength()).append(";");
        
        IMedia.Track[] audioTracks = mMediaPlayer.getTracks(IMedia.Track.Type.Audio);
        if (audioTracks != null && audioTracks.length > 0) {
            infoHash.append("audioTracks:");
            for (IMedia.Track track : audioTracks) {
                infoHash.append(track.id).append(":").append(track.name).append(",");
            }
            infoHash.append(";");
        }

        IMedia.Track[] spuTracks = mMediaPlayer.getTracks(IMedia.Track.Type.Text);
        if (spuTracks != null && spuTracks.length > 0) {
            infoHash.append("textTracks:");
            for (IMedia.Track track : spuTracks) {
                infoHash.append(track.id).append(":").append(track.name).append(",");
            }
            infoHash.append(";");
        }

        IMedia.Track videoTrack = mMediaPlayer.getSelectedTrack(IMedia.Track.Type.Video);
        IMedia.VideoTrack video = (videoTrack instanceof IMedia.VideoTrack) ? (IMedia.VideoTrack) videoTrack : null;
        if (video != null) {
            infoHash.append("videoSize:").append(video.width).append("x").append(video.height).append(";");
        }
        
        String currentHash = infoHash.toString();
        
        if (mVideoInfoHash == null || !mVideoInfoHash.equals(currentHash)) {
            WritableMap info = Arguments.createMap();

            info.putDouble("duration", mMediaPlayer.getLength());

            if (audioTracks != null && audioTracks.length > 0) {
                WritableArray tracks = new WritableNativeArray();
                for (IMedia.Track track : audioTracks) {
                    WritableMap trackMap = Arguments.createMap();
                    trackMap.putInt("id", track.id.hashCode());
                    trackMap.putString("name", track.name);
                    tracks.pushMap(trackMap);
                }
                info.putArray("audioTracks", tracks);
            }

            if (spuTracks != null && spuTracks.length > 0) {
                WritableArray tracks = new WritableNativeArray();
                for (IMedia.Track track : spuTracks) {
                    WritableMap trackMap = Arguments.createMap();
                    trackMap.putInt("id", track.id.hashCode());
                    trackMap.putString("name", track.name);
                    tracks.pushMap(trackMap);
                }
                info.putArray("textTracks", tracks);
            }

            if (video != null) {
                WritableMap mapVideoSize = Arguments.createMap();
                mapVideoSize.putInt("width", video.width);
                mapVideoSize.putInt("height", video.height);
                info.putMap("videoSize", mapVideoSize);
            }
            
            eventEmitter.sendEvent(info, VideoEventEmitter.EVENT_ON_LOAD);
            mVideoInfo = info;
            mVideoInfoHash = currentHash;
        }
    }

    /*private void changeSurfaceSize(boolean message) {

        if (mMediaPlayer != null) {
            final IVLCVout vlcVout = mMediaPlayer.getVLCVout();
            vlcVout.setWindowSize(screenWidth, screenHeight);
        }

        double displayWidth = screenWidth, displayHeight = screenHeight;

        if (screenWidth < screenHeight) {
            displayWidth = screenHeight;
            displayHeight = screenWidth;
        }

        // sanity check
        if (displayWidth * displayHeight <= 1 || mVideoWidth * mVideoHeight <= 1) {
            return;
        }

        // compute the aspect ratio
        double aspectRatio, visibleWidth;
        if (mSarDen == mSarNum) {
            *//* No indication about the density, assuming 1:1 *//*
            visibleWidth = mVideoVisibleWidth;
            aspectRatio = (double) mVideoVisibleWidth / (double) mVideoVisibleHeight;
        } else {
            *//* Use the specified aspect ratio *//*
            visibleWidth = mVideoVisibleWidth * (double) mSarNum / mSarDen;
            aspectRatio = visibleWidth / mVideoVisibleHeight;
        }

        // compute the display aspect ratio
        double displayAspectRatio = displayWidth / displayHeight;

        counter ++;

        switch (mCurrentSize) {
            case SURFACE_BEST_FIT:
                if(counter > 2)
                    Toast.makeText(getContext(), "Best Fit", Toast.LENGTH_SHORT).show();
                if (displayAspectRatio < aspectRatio)
                    displayHeight = displayWidth / aspectRatio;
                else
                    displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_FIT_HORIZONTAL:
                Toast.makeText(getContext(), "Fit Horizontal", Toast.LENGTH_SHORT).show();
                displayHeight = displayWidth / aspectRatio;
                break;
            case SURFACE_FIT_VERTICAL:
                Toast.makeText(getContext(), "Fit Horizontal", Toast.LENGTH_SHORT).show();
                displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_FILL:
                Toast.makeText(getContext(), "Fill", Toast.LENGTH_SHORT).show();
                break;
            case SURFACE_16_9:
                Toast.makeText(getContext(), "16:9", Toast.LENGTH_SHORT).show();
                aspectRatio = 16.0 / 9.0;
                if (displayAspectRatio < aspectRatio)
                    displayHeight = displayWidth / aspectRatio;
                else
                    displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_4_3:
                Toast.makeText(getContext(), "4:3", Toast.LENGTH_SHORT).show();
                aspectRatio = 4.0 / 3.0;
                if (displayAspectRatio < aspectRatio)
                    displayHeight = displayWidth / aspectRatio;
                else
                    displayWidth = displayHeight * aspectRatio;
                break;
            case SURFACE_ORIGINAL:
                Toast.makeText(getContext(), "Original", Toast.LENGTH_SHORT).show();
                displayHeight = mVideoVisibleHeight;
                displayWidth = visibleWidth;
                break;
        }

        // set display size
        int finalWidth = (int) Math.ceil(displayWidth * mVideoWidth / mVideoVisibleWidth);
        int finalHeight = (int) Math.ceil(displayHeight * mVideoHeight / mVideoVisibleHeight);

        SurfaceHolder holder = this.getHolder();
        holder.setFixedSize(finalWidth, finalHeight);

        ViewGroup.LayoutParams lp = this.getLayoutParams();
        lp.width = finalWidth;
        lp.height = finalHeight;
        this.setLayoutParams(lp);
        this.invalidate();
    }*/
}
