package org.reactnative.camera;

import android.graphics.Bitmap;
import android.os.Build;
import com.facebook.react.bridge.*;
import com.facebook.react.uimanager.NativeViewHierarchyManager;
import com.facebook.react.uimanager.UIBlock;
import com.facebook.react.uimanager.UIManagerModule;
import com.google.android.cameraview.AspectRatio;
import com.google.zxing.BarcodeFormat;
import org.reactnative.barcodedetector.BarcodeFormatUtils;
import org.reactnative.camera.tasks.ResolveTakenPictureAsyncTask;
import org.reactnative.camera.utils.ScopedContext;
import org.reactnative.facedetector.RNFaceDetector;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class UvcCameraModule extends ReactContextBaseJavaModule {
  private static final String TAG = "UvcCameraModule";

  private ScopedContext mScopedContext;
  static final int VIDEO_2160P = 0;
  static final int VIDEO_1080P = 1;
  static final int VIDEO_720P = 2;
  static final int VIDEO_480P = 3;
  static final int VIDEO_4x3 = 4;

  public UvcCameraModule(ReactApplicationContext reactContext) {
    super(reactContext);
    mScopedContext = new ScopedContext(reactContext);
  }

  public ScopedContext getScopedContext() {
    return mScopedContext;
  }

  @Override
  public String getName() {
    return "UvcCameraModule";
  }

  @Nullable
  @Override
  public Map<String, Object> getConstants() {
    return Collections.unmodifiableMap(new HashMap<String, Object>() {
      {
        put("Type", getTypeConstants());
        put("FlashMode", getFlashModeConstants());
        put("AutoFocus", getAutoFocusConstants());
        put("WhiteBalance", getWhiteBalanceConstants());
        put("VideoQuality", getVideoQualityConstants());
      }

      private Map<String, Object> getTypeConstants() {
        return Collections.unmodifiableMap(new HashMap<String, Object>() {
          {
            put("front", Constants.FACING_FRONT);
            put("back", Constants.FACING_BACK);
          }
        });
      }

      private Map<String, Object> getFlashModeConstants() {
        return Collections.unmodifiableMap(new HashMap<String, Object>() {
          {
            put("off", Constants.FLASH_OFF);
            put("on", Constants.FLASH_ON);
            put("auto", Constants.FLASH_AUTO);
            put("torch", Constants.FLASH_TORCH);
          }
        });
      }

      private Map<String, Object> getAutoFocusConstants() {
        return Collections.unmodifiableMap(new HashMap<String, Object>() {
          {
            put("on", true);
            put("off", false);
          }
        });
      }

      private Map<String, Object> getWhiteBalanceConstants() {
        return Collections.unmodifiableMap(new HashMap<String, Object>() {
          {
            put("auto", Constants.WB_AUTO);
            put("cloudy", Constants.WB_CLOUDY);
            put("sunny", Constants.WB_SUNNY);
            put("shadow", Constants.WB_SHADOW);
            put("fluorescent", Constants.WB_FLUORESCENT);
            put("incandescent", Constants.WB_INCANDESCENT);
          }
        });
      }

      private Map<String, Object> getVideoQualityConstants() {
        return Collections.unmodifiableMap(new HashMap<String, Object>() {
          {
            put("2160p", VIDEO_2160P);
            put("1080p", VIDEO_1080P);
            put("720p", VIDEO_720P);
            put("480p", VIDEO_480P);
            put("4:3", VIDEO_4x3);
          }
        });
      }

    });
  }

  @ReactMethod
  public void takePicture(final ReadableMap options, final int viewTag, final Promise promise) {
    final ReactApplicationContext context = getReactApplicationContext();
    final File cacheDirectory = mScopedContext.getCacheDirectory();
    UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
    uiManager.addUIBlock(new UIBlock() {
      @Override
      public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
          UvcCameraView cameraView = (UvcCameraView) nativeViewHierarchyManager.resolveView(viewTag);
          try {
            if (cameraView.isCameraOpened()) {
              cameraView.takePicture(options, promise, cacheDirectory);
            } else {
              promise.reject("E_CAMERA_UNAVAILABLE", "Camera is not running");
            }
        } catch (Exception e) {
          promise.reject("E_CAMERA_BAD_VIEWTAG", "takePictureAsync: Expected a Camera component");
        }
      }
    });
  }

  @ReactMethod
  public void record(final ReadableMap options, final int viewTag, final Promise promise) {
      final ReactApplicationContext context = getReactApplicationContext();
      final File cacheDirectory = mScopedContext.getCacheDirectory();
      UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);

      uiManager.addUIBlock(new UIBlock() {
          @Override
          public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
              final UvcCameraView cameraView;

              try {
                  cameraView = (UvcCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                  if (cameraView.isCameraOpened()) {
                      cameraView.record(options, promise, cacheDirectory);
                  } else {
                      promise.reject("E_CAMERA_UNAVAILABLE", "Camera is not running");
                  }
              } catch (Exception e) {
                  promise.reject("E_CAMERA_BAD_VIEWTAG", "recordAsync: Expected a Camera component");
              }
          }
      });
  }

  @ReactMethod
  public void stopRecording(final int viewTag) {
      final ReactApplicationContext context = getReactApplicationContext();
      UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
      uiManager.addUIBlock(new UIBlock() {
          @Override
          public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
              final UvcCameraView cameraView;

              try {
                  cameraView = (UvcCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                  if (cameraView.isCameraOpened()) {
                      cameraView.stopRecording();
                  }
              } catch (Exception e) {
                  e.printStackTrace();
              }
          }
      });
  }

  @ReactMethod
  public void getSupportedRatios(final int viewTag, final Promise promise) {
      final ReactApplicationContext context = getReactApplicationContext();
      UIManagerModule uiManager = context.getNativeModule(UIManagerModule.class);
      uiManager.addUIBlock(new UIBlock() {
          @Override
          public void execute(NativeViewHierarchyManager nativeViewHierarchyManager) {
              final UvcCameraView cameraView;
              try {
                  cameraView = (UvcCameraView) nativeViewHierarchyManager.resolveView(viewTag);
                  WritableArray result = Arguments.createArray();
                  if (cameraView.isCameraOpened()) {
                      Set<AspectRatio> ratios = cameraView.getSupportedAspectRatios();
                      for (AspectRatio ratio : ratios) {
                          result.pushString(ratio.toString());
                      }
                      promise.resolve(result);
                  } else {
                      promise.reject("E_CAMERA_UNAVAILABLE", "Camera is not running");
                  }
              } catch (Exception e) {
                  e.printStackTrace();
              }
          }
      });
  }
}
