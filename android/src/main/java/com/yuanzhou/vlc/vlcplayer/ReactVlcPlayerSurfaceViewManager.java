package com.yuanzhou.vlc.vlcplayer;

import android.content.Context;
import android.text.TextUtils;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.util.Map;

import javax.annotation.Nullable;

public class ReactVlcPlayerSurfaceViewManager extends SimpleViewManager<ReactVlcPlayerViewSurface> {

    private static final String REACT_CLASS = "RCTVLCPlayerSurface";

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @Override
    protected ReactVlcPlayerViewSurface createViewInstance(ThemedReactContext themedReactContext) {
        return new ReactVlcPlayerViewSurface(themedReactContext);
    }

    @Override
    public void onDropViewInstance(ReactVlcPlayerViewSurface view) {
        super.onDropViewInstance(view);
    }

    @Override
    public @Nullable Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        MapBuilder.Builder<String, Object> builder = MapBuilder.builder();
        for (String event : VideoEventEmitter.Events) {
            builder.put(event, MapBuilder.of("registrationName", event));
        }
        return builder.build();
    }

    @ReactProp(name = "source")
    public void setSrc(final ReactVlcPlayerViewSurface videoView, @Nullable ReadableMap src) {
        if (src == null) return;
        String uriString = src.hasKey("uri") ? src.getString("uri") : null;
        if (TextUtils.isEmpty(uriString)) return;
        videoView.setSrc(src);
    }

    @ReactProp(name = "subtitleUri")
    public void setSubtitleUri(final ReactVlcPlayerViewSurface videoView, final String subtitleUri) {
        videoView.setSubtitleUri(subtitleUri);
    }

    @ReactProp(name = "repeat", defaultBoolean = false)
    public void setRepeat(final ReactVlcPlayerViewSurface videoView, final boolean repeat) {
        videoView.setRepeatModifier(repeat);
    }

    @ReactProp(name = "progressUpdateInterval", defaultFloat = 0f)
    public void setInterval(final ReactVlcPlayerViewSurface videoView, final float interval) {
        videoView.setmProgressUpdateInterval(interval);
    }

    @ReactProp(name = "paused", defaultBoolean = false)
    public void setPaused(final ReactVlcPlayerViewSurface videoView, final boolean paused) {
        videoView.setPausedModifier(paused);
    }

    @ReactProp(name = "muted", defaultBoolean = false)
    public void setMuted(final ReactVlcPlayerViewSurface videoView, final boolean muted) {
        videoView.setMutedModifier(muted);
    }

    @ReactProp(name = "volume", defaultFloat = 1.0f)
    public void setVolume(final ReactVlcPlayerViewSurface videoView, final float volume) {
        videoView.setVolumeModifier((int) volume);
    }

    @ReactProp(name = "seek")
    public void setSeek(final ReactVlcPlayerViewSurface videoView, final float seek) {
        videoView.setPosition(seek);
    }

    @ReactProp(name = "autoAspectRatio", defaultBoolean = false)
    public void setAutoAspectRatio(final ReactVlcPlayerViewSurface videoView, final boolean auto) {
        videoView.setAutoAspectRatio(auto);
    }

    @ReactProp(name = "resume", defaultBoolean = true)
    public void setResume(final ReactVlcPlayerViewSurface videoView, final boolean autoPlay) {
        videoView.doResume(autoPlay);
    }

    @ReactProp(name = "rate")
    public void setRate(final ReactVlcPlayerViewSurface videoView, final float rate) {
        videoView.setRateModifier(rate);
    }

    @ReactProp(name = "videoAspectRatio")
    public void setVideoAspectRatio(final ReactVlcPlayerViewSurface videoView, final String aspectRatio) {
        videoView.setAspectRatio(aspectRatio);
    }

    @ReactProp(name = "audioTrack")
    public void setAudioTrack(final ReactVlcPlayerViewSurface videoView, final int audioTrack) {
        videoView.setAudioTrack(audioTrack);
    }

    @ReactProp(name = "textTrack")
    public void setTextTrack(final ReactVlcPlayerViewSurface videoView, final int textTrack) {
        videoView.setTextTrack(textTrack);
    }

    @ReactProp(name = "acceptInvalidCertificates", defaultBoolean = false)
    public void setAcceptInvalidCertificates(final ReactVlcPlayerViewSurface videoView, final boolean accept) {
        videoView.setAcceptInvalidCertificates(accept);
    }

    @ReactProp(name = "pictureInPictureEnabled", defaultBoolean = false)
    public void setPictureInPictureEnabled(final ReactVlcPlayerViewSurface videoView, final boolean enabled) {
        videoView.setPictureInPictureEnabled(enabled);
    }

    @ReactProp(name = "playInPictureInPicture", defaultBoolean = true)
    public void setPlayInPictureInPicture(final ReactVlcPlayerViewSurface videoView, final boolean play) {
        videoView.setPlayInPictureInPicture(play);
    }

    @Override
    public Map<String, Integer> getCommandsMap() {
        MapBuilder.Builder<String, Integer> builder = MapBuilder.builder();
        builder.put("startRecording", 1);
        builder.put("stopRecording", 2);
        builder.put("snapshot", 3);
        builder.put("enterPictureInPicture", 7);
        return builder.build();
    }

    @Override
    public void receiveCommand(ReactVlcPlayerViewSurface root, int commandId, @Nullable ReadableArray args) {
        switch (commandId) {
            case 1:
                if (args != null && args.size() > 0 && !args.isNull(0)) {
                    String path = args.getString(0);
                    root.startRecording(path);
                }
                break;
            case 2:
                root.stopRecording();
                break;
            case 3:
                if (args != null && args.size() > 0 && !args.isNull(0)) {
                    String path = args.getString(0);
                    root.doSnapshot(path);
                }
                break;
            case 7:
                root.enterPictureInPicture();
                break;
            default:
                break;
        }
    }
}
