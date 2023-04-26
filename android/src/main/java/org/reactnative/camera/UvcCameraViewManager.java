package org.reactnative.camera;

import android.support.annotation.Nullable;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.ViewGroupManager;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.google.android.cameraview.AspectRatio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UvcCameraViewManager extends ViewGroupManager<UvcCameraView> {
  public enum Events {
    EVENT_CAMERA_READY("onCameraReady"),
    EVENT_ON_MOUNT_ERROR("onMountError"),

    private final String mName;

    Events(final String name) {
      mName = name;
    }

    @Override
    public String toString() {
      return mName;
    }
  }

  private static final String REACT_CLASS = "UvcCamera";

  @Override
  public void onDropViewInstance(UvcCameraView view) {
    view.stop();
    super.onDropViewInstance(view);
  }


  @Override
  public String getName() {
    return REACT_CLASS;
  }

  @Override
  protected UvcCameraView createViewInstance(ThemedReactContext themedReactContext) {
    return new UvcCameraView(themedReactContext);
  }

  @Override
  @Nullable
  public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
    MapBuilder.Builder<String, Object> builder = MapBuilder.builder();
    for (Events event : Events.values()) {
      builder.put(event.toString(), MapBuilder.of("registrationName", event.toString()));
    }
    return builder.build();
  }

  @ReactProp(name = "rotation")
  public void setDisplayRotation(UvcCameraView view, int rotation) {
    view.setDisplayRotation(rotation);
  }

  @ReactProp(name = "type")
  public void setType(UvcCameraView view, int type) {
    view.setFacing(type);
  }

  @ReactProp(name = "ratio")
  public void setRatio(UvcCameraView view, String ratio) {
    view.setAspectRatio(AspectRatio.parse(ratio));
  }

  @ReactProp(name = "flashMode")
  public void setFlashMode(UvcCameraView view, int torchMode) {
    view.setFlash(torchMode);
  }

  @ReactProp(name = "autoFocus")
  public void setAutoFocus(UvcCameraView view, boolean autoFocus) {
    view.setAutoFocus(autoFocus);
  }

  @ReactProp(name = "focusDepth")
  public void setFocusDepth(UvcCameraView view, float depth) {
    view.setFocusDepth(depth);
  }

  @ReactProp(name = "zoom")
  public void setZoom(UvcCameraView view, float zoom) {
    view.setZoom(zoom);
  }

  @ReactProp(name = "whiteBalance")
  public void setWhiteBalance(UvcCameraView view, int whiteBalance) {
    view.setWhiteBalance(whiteBalance);
  }

  @ReactProp(name = "useCamera2Api")
  public void setUseCamera2Api(UvcCameraView view, boolean useCamera2Api) {
    view.setUsingCamera2Api(useCamera2Api);
  }

  @ReactProp(name = "playSoundOnCapture")
  public void setPlaySoundOnCapture(UvcCameraView view, boolean playSoundOnCapture) {
    view.setPlaySoundOnCapture(playSoundOnCapture);
  }
}
