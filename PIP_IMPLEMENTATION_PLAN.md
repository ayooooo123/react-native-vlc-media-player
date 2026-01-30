# Production-Ready PiP Implementation Plan

## Executive Summary

This plan transforms the react-native-vlc-media-player fork into a production-ready PiP implementation for PearTube. The core strategy is **architectural**, not cosmetic: decouple the player lifecycle from React views, use platform-appropriate rendering surfaces, and implement deterministic lifecycle handling.

**Target outcomes:**
- 70-90% reduction in PiP transition failures
- Controls that survive app backgrounding (Android) and work seamlessly (iOS)
- Debuggable, observable PiP state machine
- ~40% reduction in native code complexity (remove retry loops)

---

## Current State Analysis

### Existing File Structure
```
vendor/react-native-vlc-media-player/
├── android/src/main/java/com/yuanzhou/vlc/
│   ├── ReactVlcPlayerPackage.java
│   └── vlcplayer/
│       ├── ReactVlcPlayerView.java      # 1400+ lines, PiP + rendering + playback
│       ├── ReactVlcPlayerViewManager.java
│       └── VideoEventEmitter.java
├── ios/RCTVLCPlayer/
│   ├── RCTVLCPlayer.h/.m               # Main player (no PiP currently)
│   └── RCTVLCPlayerManager.h/.m
└── VLCPlayer.js                         # JS wrapper
```

### Current Problems
| Problem | Root Cause | Lines of Workaround Code |
|---------|-----------|-------------------------|
| Android PiP dimension failures | TextureView dual-size mismatch | ~300 lines of retry loops |
| Android PiP transition races | Player lifecycle tied to view | ~200 lines of state flags |
| iOS simulator crashes | Use-after-free in vmem callbacks | Unknown (in separate VLCPiPPlayer) |
| Controls desync | No centralized state owner | Scattered across multiple files |

---

## Target Architecture

### Design Principles
1. **Single Player Core Owner** - One object owns LibVLC lifecycle, independent of views
2. **Render Target Abstraction** - Views are "display targets", not playback controllers
3. **PiP as Window Mode** - Dedicated Activity (Android) / proper delegate (iOS) owns PiP
4. **Deterministic Lifecycle** - No retries; use platform callbacks correctly
5. **Controls via Services** - Survive backgrounding, stay responsive

### Target File Structure (Android)
```
android/src/main/java/com/yuanzhou/vlc/
├── ReactVlcPlayerPackage.java           # Package registration
├── vlcplayer/
│   ├── core/
│   │   ├── VlcPlayerCore.kt             # NEW: LibVLC owner, playback state
│   │   ├── VlcPlayerCoreListener.kt     # NEW: Callback interface
│   │   └── VlcSurfaceManager.kt         # NEW: Surface attach/detach logic
│   ├── service/
│   │   ├── PlaybackService.kt           # NEW: Foreground service, MediaSession
│   │   └── PipActionReceiver.kt         # NEW: Handles PiP button intents
│   ├── pip/
│   │   ├── PipHostActivity.kt           # NEW: Dedicated PiP window owner
│   │   └── PipParamsBuilder.kt          # NEW: Centralized PiP params
│   ├── view/
│   │   ├── VlcSurfaceView.kt            # NEW: SurfaceView-based renderer
│   │   └── ReactVlcPlayerView.kt        # REFACTORED: Thin RN bridge
│   ├── ReactVlcPlayerViewManager.kt     # MIGRATED: View manager
│   └── VideoEventEmitter.kt             # MIGRATED: Event emission
└── res/drawable/
    ├── ic_pip_play.xml
    ├── ic_pip_pause.xml
    ├── ic_pip_forward.xml
    └── ic_pip_rewind.xml
```

### Target File Structure (iOS)
```
ios/RCTVLCPlayer/
├── Core/
│   ├── VLCPlayerCore.h/.m               # NEW: VLC owner, playback state
│   ├── VLCPlayerCoreDelegate.h          # NEW: Callback protocol
│   └── VLCSampleBufferBridge.h/.m       # NEW: vmem -> AVSampleBuffer
├── PiP/
│   ├── VLCPiPController.h/.m            # NEW: AVPictureInPictureController owner
│   └── VLCPiPPlaybackDelegate.h/.m      # NEW: Sample buffer playback delegate
├── View/
│   ├── RCTVLCPlayer.h/.m                # REFACTORED: Thin RN bridge
│   └── RCTVLCPlayerManager.h/.m         # Unchanged
└── RemoteCommand/
    └── VLCRemoteCommandCenter.h/.m      # NEW: MPRemoteCommandCenter integration
```

---

## Phase 1: Android Player Core Extraction

**Duration:** 2-3 days  
**Risk:** Medium  
**Dependencies:** None

### 1.1 Create VlcPlayerCore.kt

**Purpose:** Single owner of LibVLC instance and playback state.

**Benefit:** Player survives view recreation, PiP transitions, and configuration changes. Eliminates the root cause of most PiP failures.

```kotlin
// core/VlcPlayerCore.kt
class VlcPlayerCore private constructor(context: Context) {
    
    private val libVLC: LibVLC
    private val mediaPlayer: MediaPlayer
    private var currentMedia: Media? = null
    private var attachedSurface: IVLCVout.Callback? = null
    
    // State
    var isPlaying: Boolean = false
        private set
    var isPaused: Boolean = false
        private set
    var currentPosition: Float = 0f
        private set
    var duration: Long = 0L
        private set
    
    // Listeners
    private val listeners = mutableSetOf<VlcPlayerCoreListener>()
    
    companion object {
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
    
    // Lifecycle
    fun loadMedia(uri: String, options: MediaOptions)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(position: Float)
    fun seekBy(deltaMs: Long)
    
    // Surface management (KEY for PiP)
    fun attachSurface(surfaceHolder: SurfaceHolder)
    fun detachSurface()
    fun setWindowSize(width: Int, height: Int)
    
    // Listener management
    fun addListener(listener: VlcPlayerCoreListener)
    fun removeListener(listener: VlcPlayerCoreListener)
}
```

**What this replaces:**
- All LibVLC setup in `ReactVlcPlayerView.createPlayer()`
- Playback state tracking scattered across the view
- Media loading logic

**Lines removed:** ~200 from ReactVlcPlayerView

---

### 1.2 Create VlcSurfaceManager.kt

**Purpose:** Deterministic surface attach/detach with proper sequencing.

**Benefit:** Eliminates race conditions during PiP transitions. No more retry loops.

```kotlin
// core/VlcSurfaceManager.kt
class VlcSurfaceManager(
    private val playerCore: VlcPlayerCore
) : SurfaceHolder.Callback {
    
    private var pendingAttach = false
    private var currentWidth = 0
    private var currentHeight = 0
    
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated: attaching vout")
        playerCore.attachSurface(holder)
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
        Log.d(TAG, "surfaceDestroyed: detaching vout")
        playerCore.detachSurface()
    }
    
    fun requestAttach(holder: SurfaceHolder) {
        if (holder.surface.isValid) {
            surfaceCreated(holder)
        } else {
            pendingAttach = true
        }
    }
}
```

**What this replaces:**
- `schedulePipDimensionCheck()` (5 retry attempts)
- `applyPipDimensionsWithRetry()` (3 retry attempts)
- `applyPipDimensionsImmediate()` complex logic
- `restoreWindowSizeAfterPip()` (5 retry attempts)
- `mGlobalLayoutListener` workaround
- `onLayoutChangeListener` PiP special cases

**Lines removed:** ~400 from ReactVlcPlayerView

---

### 1.3 Create VlcSurfaceView.kt

**Purpose:** SurfaceView-based rendering target for PiP.

**Benefit:** SurfaceView works correctly with Android's compositor during PiP transitions. No dual-size mismatch.

```kotlin
// view/VlcSurfaceView.kt
class VlcSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {
    
    private var surfaceManager: VlcSurfaceManager? = null
    
    fun attachToPlayer(playerCore: VlcPlayerCore) {
        surfaceManager = VlcSurfaceManager(playerCore)
        holder.addCallback(surfaceManager)
        
        // If surface already exists, attach immediately
        if (holder.surface.isValid) {
            surfaceManager?.requestAttach(holder)
        }
    }
    
    fun detachFromPlayer() {
        holder.removeCallback(surfaceManager)
        surfaceManager = null
    }
}
```

**What this replaces:**
- TextureView and all its complexity
- `SurfaceTexture.setDefaultBufferSize()` calls
- Transform matrix scaling tricks
- `onSurfaceTextureSizeChanged` special handling

**Lines removed:** ~150 from ReactVlcPlayerView

---

## Phase 2: Android PlaybackService + PiP Controls

**Duration:** 2-3 days  
**Risk:** Medium  
**Dependencies:** Phase 1

### 2.1 Create PlaybackService.kt

**Purpose:** Foreground service that owns VlcPlayerCore and MediaSession.

**Benefit:** Playback survives app backgrounding. Controls work even when React is paused. Required for Android 13+ media apps.

```kotlin
// service/PlaybackService.kt
class PlaybackService : Service(), VlcPlayerCoreListener {
    
    private lateinit var playerCore: VlcPlayerCore
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationManager: PlayerNotificationManager
    
    override fun onCreate() {
        super.onCreate()
        playerCore = VlcPlayerCore.getInstance(this)
        playerCore.addListener(this)
        
        setupMediaSession()
        setupNotification()
    }
    
    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "PearTubeVLC").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = playerCore.play()
                override fun onPause() = playerCore.pause()
                override fun onSeekTo(pos: Long) = playerCore.seekTo(pos / duration.toFloat())
                override fun onFastForward() = playerCore.seekBy(10_000)
                override fun onRewind() = playerCore.seekBy(-10_000)
            })
            isActive = true
        }
    }
    
    // VlcPlayerCoreListener
    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        updateMediaSessionState()
        updateNotification()
        updatePipParams()
    }
    
    override fun onProgressChanged(position: Float, duration: Long) {
        updateMediaSessionPosition()
    }
    
    // PiP params update (called by PipHostActivity)
    fun getPipParams(): PictureInPictureParams {
        return PipParamsBuilder.build(
            aspectRatio = playerCore.videoAspectRatio,
            isPlaying = playerCore.isPlaying,
            sourceRect = currentSourceRect
        )
    }
    
    // Binder for Activity connection
    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }
}
```

**Key additions:**
- `MediaSessionCompat` for lock screen / notification controls
- Foreground notification (required for background audio)
- Centralized PiP params generation

---

### 2.2 Create PipActionReceiver.kt

**Purpose:** Handle PiP control button presses.

**Benefit:** PiP controls remain responsive even when app UI is not rendering.

```kotlin
// service/PipActionReceiver.kt
class PipActionReceiver : BroadcastReceiver() {
    
    companion object {
        const val ACTION_PLAY = "com.peartube.vlc.ACTION_PLAY"
        const val ACTION_PAUSE = "com.peartube.vlc.ACTION_PAUSE"
        const val ACTION_FORWARD = "com.peartube.vlc.ACTION_FORWARD"
        const val ACTION_REWIND = "com.peartube.vlc.ACTION_REWIND"
        
        fun createPlayIntent(context: Context): PendingIntent = 
            createIntent(context, ACTION_PLAY)
        
        fun createPauseIntent(context: Context): PendingIntent = 
            createIntent(context, ACTION_PAUSE)
        
        fun createForwardIntent(context: Context): PendingIntent = 
            createIntent(context, ACTION_FORWARD)
        
        fun createRewindIntent(context: Context): PendingIntent = 
            createIntent(context, ACTION_REWIND)
        
        private fun createIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, PipActionReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val playerCore = VlcPlayerCore.getInstance(context)
        
        when (intent.action) {
            ACTION_PLAY -> playerCore.play()
            ACTION_PAUSE -> playerCore.pause()
            ACTION_FORWARD -> playerCore.seekBy(10_000)
            ACTION_REWIND -> playerCore.seekBy(-10_000)
        }
    }
}
```

---

### 2.3 Create PipHostActivity.kt

**Purpose:** Dedicated Activity that owns the PiP window.

**Benefit:** PiP is a window mode of an Activity. Dedicated host eliminates the complexity of managing PiP state within MainActivity.

```kotlin
// pip/PipHostActivity.kt
class PipHostActivity : AppCompatActivity() {
    
    private lateinit var surfaceView: VlcSurfaceView
    private lateinit var playerCore: VlcPlayerCore
    private var playbackService: PlaybackService? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playbackService = (binder as PlaybackService.LocalBinder).getService()
            updatePipParams()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            playbackService = null
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pip_host)
        
        surfaceView = findViewById(R.id.pip_surface)
        playerCore = VlcPlayerCore.getInstance(this)
        
        // Bind to service
        bindService(
            Intent(this, PlaybackService::class.java),
            serviceConnection,
            BIND_AUTO_CREATE
        )
        
        // Attach surface to player
        surfaceView.attachToPlayer(playerCore)
    }
    
    override fun onUserLeaveHint() {
        // Auto-enter PiP when user presses home (Android < 12)
        if (playerCore.isPlaying && canEnterPip()) {
            enterPipMode()
        }
    }
    
    override fun onPictureInPictureModeChanged(isInPipMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPipMode, newConfig)
        
        Log.d(TAG, "onPictureInPictureModeChanged: $isInPipMode")
        
        if (isInPipMode) {
            // Hide non-essential UI
            hideControls()
        } else {
            // Restore UI or finish if user closed PiP
            if (isFinishing) return
            showControls()
        }
        
        // Emit to React Native
        emitPipStateChanged(isInPipMode)
    }
    
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        
        val params = PipParamsBuilder.build(
            aspectRatio = playerCore.videoAspectRatio,
            isPlaying = playerCore.isPlaying,
            sourceRect = getSourceRect()
        )
        
        enterPictureInPictureMode(params)
    }
    
    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!isInPictureInPictureMode) return
        
        val params = PipParamsBuilder.build(
            aspectRatio = playerCore.videoAspectRatio,
            isPlaying = playerCore.isPlaying,
            sourceRect = null  // No source rect update during PiP
        )
        
        setPictureInPictureParams(params)
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
    
    override fun onDestroy() {
        surfaceView.detachFromPlayer()
        unbindService(serviceConnection)
        super.onDestroy()
    }
}
```

---

### 2.4 Create PipParamsBuilder.kt

**Purpose:** Centralized, correct PiP params generation.

**Benefit:** Single source of truth for PiP configuration. Prevents bugs from inconsistent params.

```kotlin
// pip/PipParamsBuilder.kt
object PipParamsBuilder {
    
    private const val SKIP_INTERVAL_MS = 10_000L
    
    @RequiresApi(Build.VERSION_CODES.O)
    fun build(
        aspectRatio: Rational?,
        isPlaying: Boolean,
        sourceRect: Rect?
    ): PictureInPictureParams {
        
        val builder = PictureInPictureParams.Builder()
        
        // Aspect ratio (default 16:9)
        builder.setAspectRatio(aspectRatio ?: Rational(16, 9))
        
        // Source rect hint for smooth animation
        sourceRect?.let { builder.setSourceRectHint(it) }
        
        // Actions: play/pause + seek controls
        val actions = buildActions(isPlaying)
        builder.setActions(actions)
        
        // Android 12+ auto-enter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(true)
            builder.setSeamlessResizeEnabled(true)
        }
        
        return builder.build()
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildActions(isPlaying: Boolean): List<RemoteAction> {
        val context = VlcPlayerCore.getInstance(/* context */).context
        val actions = mutableListOf<RemoteAction>()
        
        // Rewind 10s
        actions.add(RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_pip_rewind),
            "Rewind",
            "Rewind 10 seconds",
            PipActionReceiver.createRewindIntent(context)
        ))
        
        // Play/Pause
        if (isPlaying) {
            actions.add(RemoteAction(
                Icon.createWithResource(context, R.drawable.ic_pip_pause),
                "Pause",
                "Pause playback",
                PipActionReceiver.createPauseIntent(context)
            ))
        } else {
            actions.add(RemoteAction(
                Icon.createWithResource(context, R.drawable.ic_pip_play),
                "Play",
                "Resume playback",
                PipActionReceiver.createPlayIntent(context)
            ))
        }
        
        // Forward 10s
        actions.add(RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_pip_forward),
            "Forward",
            "Forward 10 seconds",
            PipActionReceiver.createForwardIntent(context)
        ))
        
        return actions
    }
}
```

---

## Phase 3: Android React Native Bridge Refactor

**Duration:** 1-2 days  
**Risk:** Low  
**Dependencies:** Phases 1-2

### 3.1 Refactor ReactVlcPlayerView.kt

**Purpose:** Thin bridge between React Native and native player core.

**Benefit:** View becomes simple and stateless. All complexity lives in properly-architected native components.

```kotlin
// view/ReactVlcPlayerView.kt
class ReactVlcPlayerView(context: ThemedReactContext) : FrameLayout(context) {
    
    private val eventEmitter = VideoEventEmitter(context)
    private val playerCore = VlcPlayerCore.getInstance(context)
    private val surfaceView = VlcSurfaceView(context)
    
    private var isPipEnabled = false
    
    init {
        addView(surfaceView, LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        surfaceView.attachToPlayer(playerCore)
        playerCore.addListener(coreListener)
    }
    
    // Props from React Native
    fun setSource(source: ReadableMap) {
        val uri = source.getString("uri") ?: return
        playerCore.loadMedia(uri, MediaOptions.from(source))
    }
    
    fun setPaused(paused: Boolean) {
        if (paused) playerCore.pause() else playerCore.play()
    }
    
    fun setSeek(position: Float) {
        if (position >= 0) playerCore.seekTo(position)
    }
    
    fun setVolume(volume: Int) {
        playerCore.setVolume(volume)
    }
    
    fun setMuted(muted: Boolean) {
        playerCore.setMuted(muted)
    }
    
    fun setPictureInPictureEnabled(enabled: Boolean) {
        isPipEnabled = enabled
    }
    
    // Commands from React Native
    fun enterPictureInPicture() {
        if (!isPipEnabled) return
        
        // Launch PiP host activity
        val intent = Intent(context, PipHostActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
    
    // Core listener
    private val coreListener = object : VlcPlayerCoreListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            eventEmitter.isPlaying(isPlaying)
        }
        
        override fun onProgressChanged(position: Float, duration: Long) {
            eventEmitter.progressChanged(position.toDouble(), duration.toDouble())
        }
        
        override fun onError(error: String) {
            eventEmitter.sendEvent(
                Arguments.createMap().apply { putString("error", error) },
                VideoEventEmitter.EVENT_ON_ERROR
            )
        }
        
        override fun onEnded() {
            eventEmitter.sendEvent(Arguments.createMap(), VideoEventEmitter.EVENT_END)
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        playerCore.removeListener(coreListener)
        surfaceView.detachFromPlayer()
    }
}
```

**What this replaces:**
- Entire `ReactVlcPlayerView.java` (1400+ lines) becomes ~150 lines
- All retry loops: gone
- All PiP dimension tracking: gone
- All TextureView complexity: gone

---

## Phase 4: iOS Player Core Extraction

**Duration:** 2 days  
**Risk:** Medium  
**Dependencies:** None (parallel with Android)

### 4.1 Create VLCPlayerCore

**Purpose:** Single owner of VLCMediaPlayer, independent of views.

**Benefit:** Same as Android - player survives view recreation. Eliminates lifecycle confusion.

```objc
// Core/VLCPlayerCore.h
@protocol VLCPlayerCoreDelegate <NSObject>
- (void)playerCore:(VLCPlayerCore *)core playbackStateChanged:(BOOL)isPlaying;
- (void)playerCore:(VLCPlayerCore *)core progressChanged:(float)position duration:(NSInteger)duration;
- (void)playerCore:(VLCPlayerCore *)core encounteredError:(NSError *)error;
- (void)playerCorePlaybackEnded:(VLCPlayerCore *)core;
@end

@interface VLCPlayerCore : NSObject

@property (nonatomic, weak) id<VLCPlayerCoreDelegate> delegate;
@property (nonatomic, readonly) BOOL isPlaying;
@property (nonatomic, readonly) BOOL isPaused;
@property (nonatomic, readonly) float currentPosition;
@property (nonatomic, readonly) NSInteger duration;
@property (nonatomic, readonly) CGSize videoSize;

+ (instancetype)sharedInstance;

// Lifecycle
- (void)loadMediaWithURL:(NSURL *)url options:(NSDictionary *)options;
- (void)play;
- (void)pause;
- (void)stop;
- (void)seekToPosition:(float)position;
- (void)seekByInterval:(NSTimeInterval)interval;

// Volume
- (void)setVolume:(NSInteger)volume;
- (void)setMuted:(BOOL)muted;

// Video output (for PiP bridge)
- (void)attachVideoOutputToLayer:(CALayer *)layer;
- (void)detachVideoOutput;

@end
```

```objc
// Core/VLCPlayerCore.m
@implementation VLCPlayerCore {
    VLCMediaPlayer *_mediaPlayer;
    VLCMedia *_currentMedia;
    dispatch_queue_t _playerQueue;
}

+ (instancetype)sharedInstance {
    static VLCPlayerCore *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        instance = [[VLCPlayerCore alloc] init];
    });
    return instance;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _playerQueue = dispatch_queue_create("com.peartube.vlc.playercore", DISPATCH_QUEUE_SERIAL);
        _mediaPlayer = [[VLCMediaPlayer alloc] init];
        _mediaPlayer.delegate = self;
    }
    return self;
}

// ... implementation
@end
```

---

### 4.2 Create VLCSampleBufferBridge

**Purpose:** Safe vmem callback handling with proper lifecycle.

**Benefit:** Eliminates use-after-free crashes. Proper buffer ownership.

```objc
// Core/VLCSampleBufferBridge.h
@interface VLCSampleBufferBridge : NSObject

@property (nonatomic, strong, readonly) AVSampleBufferDisplayLayer *displayLayer;
@property (nonatomic, assign, readonly) BOOL isConfigured;

- (instancetype)initWithPlayerCore:(VLCPlayerCore *)playerCore;

// Lifecycle - MUST be called in order
- (void)start;
- (void)stop;  // Synchronous - blocks until callbacks are stopped

// For PiP
- (AVSampleBufferRenderSynchronizer *)renderSynchronizer;

@end
```

```objc
// Core/VLCSampleBufferBridge.m
@implementation VLCSampleBufferBridge {
    VLCPlayerCore *_playerCore;
    CVPixelBufferPoolRef _pixelBufferPool;
    NSMutableArray<NSValue *> *_availableBuffers;
    dispatch_queue_t _bufferQueue;
    
    // CRITICAL: Shutdown flag
    atomic_bool _isShuttingDown;
    
    // Timing
    CMTime _baseTime;
    uint64_t _frameCount;
}

- (void)stop {
    // Set shutdown flag FIRST
    atomic_store(&_isShuttingDown, true);
    
    // Wait for any in-flight callbacks to complete
    dispatch_sync(_bufferQueue, ^{
        // Flush display layer
        [self.displayLayer flush];
    });
    
    // Now safe to release resources
    [self destroyPixelBufferPool];
}

// vmem lock callback
static void *vlc_lock_cb(void *opaque, void **planes) {
    VLCSampleBufferBridge *bridge = (__bridge VLCSampleBufferBridge *)opaque;
    
    // CHECK SHUTDOWN FLAG FIRST
    if (atomic_load(&bridge->_isShuttingDown)) {
        return NULL;
    }
    
    // ... rest of implementation
}

// vmem display callback  
static void vlc_display_cb(void *opaque, void *picture) {
    VLCSampleBufferBridge *bridge = (__bridge VLCSampleBufferBridge *)opaque;
    
    // CHECK SHUTDOWN FLAG
    if (atomic_load(&bridge->_isShuttingDown)) {
        // Free context but don't enqueue
        VLCFrameContext *ctx = (VLCFrameContext *)picture;
        if (ctx) {
            CVPixelBufferRelease(ctx->pixelBuffer);
            free(ctx);
        }
        return;
    }
    
    // ... rest of implementation
}

@end
```

**Key safety additions:**
- `atomic_bool _isShuttingDown` checked in every callback
- `stop` is synchronous - waits for callbacks to drain
- Explicit buffer release in shutdown path

---

### 4.3 Create VLCPiPController

**Purpose:** Clean AVPictureInPictureController ownership.

**Benefit:** Proper delegate lifecycle. Clear responsibility boundaries.

```objc
// PiP/VLCPiPController.h
@protocol VLCPiPControllerDelegate <NSObject>
- (void)pipControllerDidStart:(VLCPiPController *)controller;
- (void)pipControllerDidStop:(VLCPiPController *)controller;
- (void)pipController:(VLCPiPController *)controller failedWithError:(NSError *)error;
@end

@interface VLCPiPController : NSObject

@property (nonatomic, weak) id<VLCPiPControllerDelegate> delegate;
@property (nonatomic, readonly) BOOL isPiPActive;
@property (nonatomic, readonly) BOOL isPiPSupported;

- (instancetype)initWithSampleBufferBridge:(VLCSampleBufferBridge *)bridge
                                playerCore:(VLCPlayerCore *)playerCore;

- (BOOL)startPiP;
- (void)stopPiP;

@end
```

```objc
// PiP/VLCPiPController.m
@implementation VLCPiPController {
    VLCSampleBufferBridge *_bridge;
    VLCPlayerCore *_playerCore;
    AVPictureInPictureController *_pipController;
}

- (instancetype)initWithSampleBufferBridge:(VLCSampleBufferBridge *)bridge
                                playerCore:(VLCPlayerCore *)playerCore {
    self = [super init];
    if (self) {
        _bridge = bridge;
        _playerCore = playerCore;
        
        if ([AVPictureInPictureController isPictureInPictureSupported]) {
            [self setupPiPController];
        }
    }
    return self;
}

- (void)setupPiPController {
    AVPictureInPictureControllerContentSource *contentSource =
        [[AVPictureInPictureControllerContentSource alloc]
            initWithSampleBufferDisplayLayer:_bridge.displayLayer
            playbackDelegate:self];
    
    _pipController = [[AVPictureInPictureController alloc] initWithContentSource:contentSource];
    _pipController.delegate = self;
}

- (void)dealloc {
    // CRITICAL: Stop PiP before dealloc
    if (_pipController.isPictureInPictureActive) {
        [_pipController stopPictureInPicture];
    }
    _pipController = nil;
}

#pragma mark - AVPictureInPictureSampleBufferPlaybackDelegate

- (BOOL)pictureInPictureControllerIsPlaybackPaused:(AVPictureInPictureController *)controller {
    return !_playerCore.isPlaying;
}

- (CMTimeRange)pictureInPictureControllerTimeRangeForPlayback:(AVPictureInPictureController *)controller {
    if (_playerCore.duration > 0) {
        CMTime duration = CMTimeMake(_playerCore.duration, 1000);
        return CMTimeRangeMake(kCMTimeZero, duration);
    }
    return CMTimeRangeMake(kCMTimeZero, CMTimeMake(INT64_MAX, 1));
}

- (void)pictureInPictureController:(AVPictureInPictureController *)controller
                       setPlaying:(BOOL)playing {
    if (playing) {
        [_playerCore play];
    } else {
        [_playerCore pause];
    }
}

- (void)pictureInPictureController:(AVPictureInPictureController *)controller
                    skipByInterval:(CMTime)skipInterval
                 completionHandler:(void (^)(void))completionHandler {
    NSTimeInterval seconds = CMTimeGetSeconds(skipInterval);
    [_playerCore seekByInterval:seconds];
    if (completionHandler) completionHandler();
}

@end
```

---

### 4.4 Create VLCRemoteCommandCenter

**Purpose:** Lock screen and Control Center controls.

**Benefit:** Controls work consistently across PiP, lock screen, and Control Center.

```objc
// RemoteCommand/VLCRemoteCommandCenter.h
@interface VLCRemoteCommandCenter : NSObject

+ (instancetype)sharedInstance;

- (void)setupWithPlayerCore:(VLCPlayerCore *)playerCore;
- (void)updateNowPlayingInfo:(NSDictionary *)info;
- (void)teardown;

@end
```

```objc
// RemoteCommand/VLCRemoteCommandCenter.m
@implementation VLCRemoteCommandCenter {
    VLCPlayerCore *_playerCore;
}

- (void)setupWithPlayerCore:(VLCPlayerCore *)playerCore {
    _playerCore = playerCore;
    
    MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
    
    // Play/Pause
    [center.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        [_playerCore play];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    
    [center.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        [_playerCore pause];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    
    [center.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        if (_playerCore.isPlaying) {
            [_playerCore pause];
        } else {
            [_playerCore play];
        }
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    
    // Skip forward/back (10 seconds)
    center.skipForwardCommand.preferredIntervals = @[@10];
    [center.skipForwardCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        [_playerCore seekByInterval:10.0];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
    
    center.skipBackwardCommand.preferredIntervals = @[@10];
    [center.skipBackwardCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
        [_playerCore seekByInterval:-10.0];
        return MPRemoteCommandHandlerStatusSuccess;
    }];
}

@end
```

---

## Phase 5: iOS React Native Bridge Refactor

**Duration:** 1 day  
**Risk:** Low  
**Dependencies:** Phase 4

### 5.1 Refactor RCTVLCPlayer

**Purpose:** Thin bridge to player core and PiP controller.

```objc
// View/RCTVLCPlayer.h (updated)
@interface RCTVLCPlayer : UIView

// Existing event blocks...

// PiP
@property (nonatomic, copy) RCTDirectEventBlock onPiPStateChange;

- (BOOL)startPiP;
- (void)stopPiP;

@end
```

```objc
// View/RCTVLCPlayer.m (simplified)
@implementation RCTVLCPlayer {
    VLCPlayerCore *_playerCore;
    VLCSampleBufferBridge *_sampleBufferBridge;
    VLCPiPController *_pipController;
}

- (instancetype)initWithEventDispatcher:(RCTEventDispatcher *)eventDispatcher {
    self = [super init];
    if (self) {
        _playerCore = [VLCPlayerCore sharedInstance];
        _playerCore.delegate = self;
        
        _sampleBufferBridge = [[VLCSampleBufferBridge alloc] initWithPlayerCore:_playerCore];
        [self.layer addSublayer:_sampleBufferBridge.displayLayer];
        
        _pipController = [[VLCPiPController alloc] initWithSampleBufferBridge:_sampleBufferBridge
                                                                   playerCore:_playerCore];
        _pipController.delegate = self;
        
        [[VLCRemoteCommandCenter sharedInstance] setupWithPlayerCore:_playerCore];
    }
    return self;
}

- (void)dealloc {
    // CRITICAL ORDER
    [_pipController stopPiP];
    [_sampleBufferBridge stop];  // Synchronous
    _playerCore.delegate = nil;
}

// ... rest of implementation
@end
```

---

## Phase 6: Unified JavaScript API

**Duration:** 1 day  
**Risk:** Low  
**Dependencies:** Phases 3, 5

### 6.1 Update VLCPlayer.js

```javascript
// VLCPlayer.js
import { requireNativeComponent, NativeModules, Platform } from 'react-native';

const RCTVLCPlayer = requireNativeComponent('RCTVLCPlayer');

export class VLCPlayer extends React.Component {
  
  // PiP API
  static isPiPSupported() {
    if (Platform.OS === 'ios') {
      return Platform.Version >= 15;
    }
    if (Platform.OS === 'android') {
      return Platform.Version >= 26;
    }
    return false;
  }
  
  enterPiP() {
    if (this._root) {
      UIManager.dispatchViewManagerCommand(
        findNodeHandle(this._root),
        UIManager.getViewManagerConfig('RCTVLCPlayer').Commands.enterPiP,
        []
      );
    }
  }
  
  exitPiP() {
    if (this._root) {
      UIManager.dispatchViewManagerCommand(
        findNodeHandle(this._root),
        UIManager.getViewManagerConfig('RCTVLCPlayer').Commands.exitPiP,
        []
      );
    }
  }
  
  render() {
    return (
      <RCTVLCPlayer
        ref={ref => this._root = ref}
        {...this.props}
        onPiPStateChange={this._onPiPStateChange}
      />
    );
  }
  
  _onPiPStateChange = (event) => {
    // Normalize event across platforms
    const { isActive, width, height } = event.nativeEvent;
    this.props.onPiPStateChange?.({
      isInPictureInPicture: isActive,
      width: width || 0,
      height: height || 0
    });
  }
}
```

### 6.2 Update TypeScript Definitions

```typescript
// index.d.ts
export interface PiPStateChangeEvent {
  isInPictureInPicture: boolean;
  width: number;
  height: number;
}

export interface VLCPlayerProps {
  // ... existing props
  
  /**
   * Enable Picture-in-Picture support.
   * @platform android, ios
   */
  pictureInPictureEnabled?: boolean;
  
  /**
   * Called when PiP state changes.
   * @platform android, ios
   */
  onPiPStateChange?: (event: PiPStateChangeEvent) => void;
}

export class VLCPlayer extends React.Component<VLCPlayerProps> {
  /**
   * Check if PiP is supported on current device.
   */
  static isPiPSupported(): boolean;
  
  /**
   * Enter Picture-in-Picture mode.
   */
  enterPiP(): void;
  
  /**
   * Exit Picture-in-Picture mode.
   */
  exitPiP(): void;
}
```

---

## Phase 7: Observability & Debugging

**Duration:** 0.5 days  
**Risk:** Low  
**Dependencies:** All previous phases

### 7.1 Structured Logging

```kotlin
// Android: PiPLogger.kt
object PiPLogger {
    private const val TAG = "VLC-PiP"
    var isVerbose = BuildConfig.DEBUG
    
    fun logPiPRequested(sourceRect: Rect?, aspectRatio: Rational?) {
        if (isVerbose) {
            Log.d(TAG, "PiP REQUESTED | sourceRect=$sourceRect | aspectRatio=$aspectRatio")
        }
    }
    
    fun logPiPEntered(width: Int, height: Int) {
        Log.i(TAG, "PiP ENTERED | ${width}x${height}")
    }
    
    fun logPiPExited() {
        Log.i(TAG, "PiP EXITED")
    }
    
    fun logSurfaceAttached(width: Int, height: Int) {
        if (isVerbose) {
            Log.d(TAG, "SURFACE ATTACHED | ${width}x${height}")
        }
    }
    
    fun logSurfaceDetached() {
        if (isVerbose) {
            Log.d(TAG, "SURFACE DETACHED")
        }
    }
    
    fun logError(message: String, error: Throwable? = null) {
        Log.e(TAG, "ERROR | $message", error)
    }
}
```

```objc
// iOS: VLCPiPLogger.h/.m
@interface VLCPiPLogger : NSObject
+ (void)setVerbose:(BOOL)verbose;
+ (void)logPiPRequested;
+ (void)logPiPEnteredWithSize:(CGSize)size;
+ (void)logPiPExited;
+ (void)logSampleBufferEnqueued:(CMTime)pts;
+ (void)logError:(NSString *)message error:(NSError *)error;
@end
```

---

## Phase 8: Testing & QA

**Duration:** 2-3 days  
**Risk:** Low  
**Dependencies:** All previous phases

### 8.1 Test Matrix

| Platform | Version | Device | Priority |
|----------|---------|--------|----------|
| Android | API 26 (8.0) | Emulator | High |
| Android | API 29 (10) | Pixel | High |
| Android | API 31 (12) | Pixel | High |
| Android | API 33 (13) | Samsung | High |
| Android | API 34 (14) | Pixel | Medium |
| iOS | 15.x | iPhone 12 | High |
| iOS | 16.x | iPhone 14 | High |
| iOS | 17.x | iPhone 15 | High |
| iOS | 17.x | Simulator | Medium (known issues) |

### 8.2 Test Scenarios

```markdown
## PiP Entry/Exit (20 cycles each device)
- [ ] Enter PiP via home button
- [ ] Enter PiP via explicit call
- [ ] Exit PiP via expand button
- [ ] Exit PiP via close button
- [ ] Exit PiP via returning to app

## Controls (each device)
- [ ] Play/Pause responds in PiP
- [ ] Seek forward responds in PiP
- [ ] Seek backward responds in PiP
- [ ] Controls survive 5s background

## Edge Cases
- [ ] Rotation during PiP
- [ ] Split-screen + PiP (Android)
- [ ] Memory pressure during PiP
- [ ] Network change during PiP
- [ ] Lock screen controls (both)

## Regression
- [ ] Normal playback (no PiP) still works
- [ ] All existing events fire correctly
- [ ] No memory leaks (Instruments/Profiler)
```

### 8.3 Acceptance Criteria

| Metric | Target |
|--------|--------|
| PiP entry success rate | > 99% |
| PiP exit success rate | > 99% |
| Control response time | < 200ms |
| No black frames during transition | 100% |
| Crash-free rate | > 99.9% |

---

## Summary: Benefits by Phase

| Phase | Key Benefit | Lines Changed | Risk |
|-------|-------------|---------------|------|
| 1. Android Player Core | Eliminates root cause of PiP failures | +300, -600 | Medium |
| 2. Android Service + PiP | Controls survive backgrounding | +400 | Medium |
| 3. Android Bridge Refactor | Simplifies codebase dramatically | +150, -1200 | Low |
| 4. iOS Player Core | Same benefits as Android | +250 | Medium |
| 5. iOS Bridge Refactor | Eliminates simulator crashes | +100, -200 | Low |
| 6. JS API | Unified, predictable API | +50 | Low |
| 7. Observability | Diagnosable failures | +100 | Low |
| 8. QA | Confidence to ship | N/A | Low |

**Total estimated time:** 10-14 days  
**Total lines:** ~1400 new, ~2000 removed  
**Net complexity reduction:** ~30-40%

---

## Execution Order

```
Week 1:
├── Day 1-2: Phase 1 (Android Player Core)
├── Day 3-4: Phase 2 (Android Service + PiP)
└── Day 5: Phase 3 (Android Bridge)

Week 2:
├── Day 1-2: Phase 4 (iOS Player Core)
├── Day 3: Phase 5 (iOS Bridge)
├── Day 4: Phase 6 (JS API) + Phase 7 (Observability)
└── Day 5: Phase 8 (QA begins)

Week 3:
└── Day 1-3: Phase 8 (QA continues, bug fixes)
```

---

## Rollback Plan

Each phase is independently testable. If a phase causes regressions:

1. **Phase 1-3 (Android):** Revert to current `ReactVlcPlayerView.java`
2. **Phase 4-5 (iOS):** Revert to current `RCTVLCPlayer.m`
3. **Phase 6 (JS):** Revert `VLCPlayer.js` changes

The current implementation remains functional throughout; new code is additive until the final switchover.
