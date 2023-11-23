/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.widget;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.serenegiant.encoder.IVideoEncoder;
import com.serenegiant.encoder.MediaEncoder;
import com.serenegiant.encoder.MediaVideoEncoder;
import com.serenegiant.glutils.EGLBase;
import com.serenegiant.glutils.GLDrawer2D;
import com.serenegiant.glutils.GLHelper;
import com.serenegiant.utils.FpsCounter;

/**
 * change the view size with keeping the specified aspect ratio.
 * if you set this view with in a FrameLayout and set property "android:layout_gravity="center",
 * you can show this view in the center of screen and keep the aspect ratio of content
 * XXX it is better that can set the aspect ratio as xml property
 */
public class UVCCameraTextureView extends AspectRatioTextureView    // API >= 14
	implements TextureView.SurfaceTextureListener, CameraViewInterface {
	public static interface IZoomCallback {
		public void onZoomView(int zoom);
	}

	private static final boolean DEBUG = true;	// TODO set false on release
	private static final String TAG = "UVCCameraTextureView";

	private boolean mHasSurface;
	private RenderHandler mRenderHandler;
	private final Object mCaptureSync = new Object();
	private Bitmap mTempBitmap;
	private boolean mReqesutCaptureStillImage;
	private Callback mCallback;

	///////////////////////////
	public static final float MAX_ZOOM_GESTURE_SIZE = 2.5f; // This affects the pinch to zoom gesture

	public ScaleGestureDetector mScaleGestureDetector;
	private float mSavedScaleFactor = 1.0f;
	private int mMaxZoom;
	Context mContext;

	IZoomCallback mZoomCallback;

	///////////////////
	private static final String SUPERSTATE_KEY = "superState";
	private static final String MIN_SCALE_KEY = "minScale";
	private static final String MAX_SCALE_KEY = "maxScale";

	private float minScale = 1f;
	private float maxScale = 5f;
	private float saveScale = 1f;

	public void setMinScale(float scale) {
		if (scale < 1.0f || scale > maxScale)
			throw new RuntimeException("minScale can't be lower than 1 or larger than maxScale(" + maxScale + ")");
		else minScale = scale;
	}

	public void setMaxScale(float scale) {
		if (scale < 1.0f || scale < minScale)
			throw new RuntimeException("maxScale can't be lower than 1 or minScale(" + minScale + ")");
		else minScale = scale;
	}

	private static final int NONE = 0;
	private static final int DRAG = 1;
	private static final int ZOOM = 2;
	private int mode = NONE;

	private Matrix matrix = new Matrix();
	private ScaleGestureDetector mScaleDetector;
	private GestureDetector mSimpleDetector;
	private float[] m;

	private PointF last = new PointF();
	private PointF start = new PointF();
	private float right, bottom;
	public int mX, mY, mDeltaX, mDeltaY;

	/** for calculation of frame rate */
	private final FpsCounter mFpsCounter = new FpsCounter();

	public UVCCameraTextureView(final Context context) {
		this(context, null, 0);
		mContext = context;
		initView(null);
	}

	public UVCCameraTextureView(final Context context, final AttributeSet attrs) {
		this(context, attrs, 0);
		mContext = context;
		initView(null);
	}

	public UVCCameraTextureView(final Context context, final AttributeSet attrs, final int defStyle) {
		super(context, attrs, defStyle);
		setSurfaceTextureListener(this);
		mContext = context;
		initView(null);
	}
	private void initView(AttributeSet attrs) {
		/*
		TypedArray a = mContext.getTheme().obtainStyledAttributes(
				attrs, R.styleable.ZoomableTextureView,
				0, 0);
		try {
			minScale = a.getFloat(R.styleable.ZoomableTextureView_minScale, minScale);
			maxScale = a.getFloat(R.styleable.ZoomableTextureView_maxScale, maxScale);
		} finally {
			a.recycle();
		}
		*/
		//setOnTouchListener(new ZoomOnTouchListeners());
	}
	public void setZoomAble(boolean zoom, IZoomCallback cb) {
		if (zoom){
			setOnTouchListener(new ZoomOnTouchListeners());
			mZoomCallback = cb;
		}
		else{
			setOnTouchListener(null);
			mZoomCallback = null;
		}
	}

	@Override
	public void onResume() {
		matrix = new Matrix();
		if (DEBUG) Log.v(TAG, "onResume:");
		if (mHasSurface) {
			mRenderHandler = RenderHandler.createHandler(mFpsCounter, super.getSurfaceTexture(), getWidth(), getHeight());
		}

		// Scale gesture detector is used to capture pinch to zoom
		mScaleGestureDetector = new ScaleGestureDetector(mContext, new ScaleGestureDetector.OnScaleGestureListener() {
			@Override
			public boolean onScaleBegin(ScaleGestureDetector detector) {
				return true;
			}

			@Override
			public boolean onScale(ScaleGestureDetector detector) {
				setCameraZoom(detector.getScaleFactor() * mSavedScaleFactor);
				return false;
			}

			@Override
			public void onScaleEnd(ScaleGestureDetector detector) {
				// Set saved scale factor and make sure it's within legal range
				mSavedScaleFactor = mSavedScaleFactor * detector.getScaleFactor();
				if (mSavedScaleFactor < 1.0f) {
					mSavedScaleFactor = 1.0f;
				} else if (mSavedScaleFactor > MAX_ZOOM_GESTURE_SIZE + 1) {
					mSavedScaleFactor = MAX_ZOOM_GESTURE_SIZE + 1;
				}
				setCameraZoom(mSavedScaleFactor);
			}
		});
	}

	@Override
	public void onPause() {
		if (DEBUG) Log.v(TAG, "onPause:");
		if (mRenderHandler != null) {
			mRenderHandler.release();
			mRenderHandler = null;
		}
		if (mTempBitmap != null) {
			mTempBitmap.recycle();
			mTempBitmap = null;
		}
	}

	@Override
	public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
		if (DEBUG) Log.v(TAG, "onSurfaceTextureAvailable:" + surface);
		if (mRenderHandler == null) {
			mRenderHandler = RenderHandler.createHandler(mFpsCounter, surface, width, height);
		} else {
			mRenderHandler.resize(width, height);
		}
		mHasSurface = true;
		if (mCallback != null) {
			mCallback.onSurfaceCreated(this, getSurface());
		}
	}

	private void setCameraZoom(float zoomScaleFactor) {
		// Convert gesture to camera zoom value
		int zoom = (int) ((zoomScaleFactor - 1) * mMaxZoom / MAX_ZOOM_GESTURE_SIZE);
		Log.e("zoomfactor", "fact "+zoomScaleFactor);
		// Sanity check for zoom level
		if (zoom > mMaxZoom) {
			zoom = mMaxZoom;
		} else if (zoom < 0) {
			zoom = 0;
		}
		int zoomVal = (int) zoomScaleFactor;
		if (mZoomCallback!=null)
			mZoomCallback.onZoomView(zoomVal);
		// Update the camera with the new zoom if it is supported
//        Camera.Parameters parameters = mCamera.getParameters();
//        if (parameters.isZoomSupported()) {
//            parameters.setZoom(zoom);
//            mCamera.setParameters(parameters);
//        }
	}

	@Override
	public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
		if (DEBUG) Log.v(TAG, "onSurfaceTextureSizeChanged:" + surface);
		if (mRenderHandler != null) {
			mRenderHandler.resize(width, height);
		}
		if (mCallback != null) {
			mCallback.onSurfaceChanged(this, getSurface(), width, height);
		}
	}

	@Override
	public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
		if (DEBUG) Log.v(TAG, "onSurfaceTextureDestroyed:" + surface);
		if (mRenderHandler != null) {
			mRenderHandler.release();
			mRenderHandler = null;
		}
		mHasSurface = false;
		if (mCallback != null) {
			mCallback.onSurfaceDestroy(this, getSurface());
		}
		if (mPreviewSurface != null) {
			mPreviewSurface.release();
			mPreviewSurface = null;
		}
		return true;
	}

	@Override
	public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
		synchronized (mCaptureSync) {
			if (mReqesutCaptureStillImage) {
				mReqesutCaptureStillImage = false;
				if (mTempBitmap == null)
					mTempBitmap = getBitmap();
				else
					getBitmap(mTempBitmap);
				mCaptureSync.notifyAll();
			}
		}
	}

	@Override
	public boolean hasSurface() {
		return mHasSurface;
	}

	/**
	 * capture preview image as a bitmap
	 * this method blocks current thread until bitmap is ready
	 * if you call this method at almost same time from different thread,
	 * the returned bitmap will be changed while you are processing the bitmap
	 * (because we return same instance of bitmap on each call for memory saving)
	 * if you need to call this method from multiple thread,
	 * you should change this method(copy and return)
	 */
	@Override
	public Bitmap captureStillImage() {
		synchronized (mCaptureSync) {
			mReqesutCaptureStillImage = true;
			try {
				mCaptureSync.wait();
			} catch (final InterruptedException e) {
			}
			return mTempBitmap;
		}
	}

	@Override
	public SurfaceTexture getSurfaceTexture() {
		return mRenderHandler != null ? mRenderHandler.getPreviewTexture() : null;
	}

	private Surface mPreviewSurface;
	@Override
	public Surface getSurface() {
		if (DEBUG) Log.v(TAG, "getSurface:hasSurface=" + mHasSurface);
		if (mPreviewSurface == null) {
			final SurfaceTexture st = getSurfaceTexture();
			if (st != null) {
				mPreviewSurface = new Surface(st);
			}
		}
		return mPreviewSurface;
	}

	@Override
	public void setVideoEncoder(final IVideoEncoder encoder) {
		if (mRenderHandler != null)
			mRenderHandler.setVideoEncoder(encoder);
	}

	@Override
	public void setCallback(final Callback callback) {
		mCallback = callback;
	}

	public void resetFps() {
		mFpsCounter.reset();
	}
	
	/** update frame rate of image processing */
	public void updateFps() {
		mFpsCounter.update();
	}

	/**
	 * get current frame rate of image processing
	 * @return
	 */
	public float getFps() {
		return mFpsCounter.getFps();
	}

	/**
	 * get total frame rate from start
	 * @return
	 */
	public float getTotalFps() {
		return mFpsCounter.getTotalFps();
	}

	/**
	 * render camera frames on this view on a private thread
	 * @author saki
	 *
	 */
	private static final class RenderHandler extends Handler
		implements SurfaceTexture.OnFrameAvailableListener  {

		private static final int MSG_REQUEST_RENDER = 1;
		private static final int MSG_SET_ENCODER = 2;
		private static final int MSG_CREATE_SURFACE = 3;
		private static final int MSG_RESIZE = 4;
		private static final int MSG_TERMINATE = 9;

		private RenderThread mThread;
		private boolean mIsActive = true;
		private final FpsCounter mFpsCounter;

		public static final RenderHandler createHandler(final FpsCounter counter,
			final SurfaceTexture surface, final int width, final int height) {
			
			final RenderThread thread = new RenderThread(counter, surface, width, height);
			thread.start();
			return thread.getHandler();
		}

		private RenderHandler(final FpsCounter counter, final RenderThread thread) {
			mThread = thread;
			mFpsCounter = counter;
		}

		public final void setVideoEncoder(final IVideoEncoder encoder) {
			if (DEBUG) Log.v(TAG, "setVideoEncoder:");
			if (mIsActive)
				sendMessage(obtainMessage(MSG_SET_ENCODER, encoder));
		}

		public final SurfaceTexture getPreviewTexture() {
			if (DEBUG) Log.v(TAG, "getPreviewTexture:");
			if (mIsActive) {
				synchronized (mThread.mSync) {
					sendEmptyMessage(MSG_CREATE_SURFACE);
					try {
						mThread.mSync.wait();
					} catch (final InterruptedException e) {
					}
					return mThread.mPreviewSurface;
				}
			} else {
				return null;
			}
		}

		public void resize(final int width, final int height) {
			if (DEBUG) Log.v(TAG, "resize:");
			if (mIsActive) {
				synchronized (mThread.mSync) {
					sendMessage(obtainMessage(MSG_RESIZE, width, height));
					try {
						mThread.mSync.wait();
					} catch (final InterruptedException e) {
					}
				}
			}
		}

		public final void release() {
			if (DEBUG) Log.v(TAG, "release:");
			if (mIsActive) {
				mIsActive = false;
				removeMessages(MSG_REQUEST_RENDER);
				removeMessages(MSG_SET_ENCODER);
				sendEmptyMessage(MSG_TERMINATE);
			}
		}

		@Override
		public final void onFrameAvailable(final SurfaceTexture surfaceTexture) {
			if (mIsActive) {
				mFpsCounter.count();
				sendEmptyMessage(MSG_REQUEST_RENDER);
			}
		}

		@Override
		public final void handleMessage(final Message msg) {
			if (mThread == null) return;
			switch (msg.what) {
			case MSG_REQUEST_RENDER:
				mThread.onDrawFrame();
				break;
			case MSG_SET_ENCODER:
				mThread.setEncoder((MediaEncoder)msg.obj);
				break;
			case MSG_CREATE_SURFACE:
				mThread.updatePreviewSurface();
				break;
			case MSG_RESIZE:
				mThread.resize(msg.arg1, msg.arg2);
				break;
			case MSG_TERMINATE:
				Looper.myLooper().quit();
				mThread = null;
				break;
			default:
				super.handleMessage(msg);
			}
		}

		private static final class RenderThread extends Thread {
	    	private final Object mSync = new Object();
	    	private final SurfaceTexture mSurface;
	    	private RenderHandler mHandler;
	    	private EGLBase mEgl;
	    	/** IEglSurface instance related to this TextureView */
	    	private EGLBase.IEglSurface mEglSurface;
	    	private GLDrawer2D mDrawer;
	    	private int mTexId = -1;
	    	/** SurfaceTexture instance to receive video images */
	    	private SurfaceTexture mPreviewSurface;
			private final float[] mStMatrix = new float[16];
			private MediaEncoder mEncoder;
			private int mViewWidth, mViewHeight;
			private final FpsCounter mFpsCounter;

			/**
			 * constructor
			 * @param surface: drawing surface came from TexureView
			 */
	    	public RenderThread(final FpsCounter fpsCounter, final SurfaceTexture surface, final int width, final int height) {
				mFpsCounter = fpsCounter;
	    		mSurface = surface;
				mViewWidth = width;
				mViewHeight = height;
	    		setName("RenderThread");
			}

			public final RenderHandler getHandler() {
				if (DEBUG) { Log.v(TAG, "RenderThread#getHandler:"); }
	            synchronized (mSync) {
	                // create rendering thread
	            	if (mHandler == null)
						try {
							mSync.wait();
						} catch (final InterruptedException e) {
						}
	            }
	            return mHandler;
			}

			public void resize(final int width, final int height) {
				if (((width > 0) && (width != mViewWidth)) || ((height > 0) && (height != mViewHeight))) {
					mViewWidth = width;
					mViewHeight = height;
					updatePreviewSurface();
				} else {
					synchronized (mSync) {
						mSync.notifyAll();
					}
				}
			}

			public final void updatePreviewSurface() {
	            if (DEBUG) Log.i(TAG, "RenderThread#updatePreviewSurface:");
	            synchronized (mSync) {
	            	if (mPreviewSurface != null) {
	            		if (DEBUG) Log.d(TAG, "updatePreviewSurface:release mPreviewSurface");
	            		mPreviewSurface.setOnFrameAvailableListener(null);
	            		mPreviewSurface.release();
	            		mPreviewSurface = null;
	            	}
					mEglSurface.makeCurrent();
		            if (mTexId >= 0) {
						mDrawer.deleteTex(mTexId);
		            }
		    		// create texture and SurfaceTexture for input from camera
		            mTexId = mDrawer.initTex();
		            if (DEBUG) Log.v(TAG, "updatePreviewSurface:tex_id=" + mTexId);
		            mPreviewSurface = new SurfaceTexture(mTexId);
					mPreviewSurface.setDefaultBufferSize(mViewWidth, mViewHeight);
		            mPreviewSurface.setOnFrameAvailableListener(mHandler);
		            // notify to caller thread that previewSurface is ready
					mSync.notifyAll();
	            }
			}

			public final void setEncoder(final MediaEncoder encoder) {
				if (DEBUG) Log.v(TAG, "RenderThread#setEncoder:encoder=" + encoder);
				if (encoder != null && (encoder instanceof MediaVideoEncoder)) {
					((MediaVideoEncoder)encoder).setEglContext(mEgl.getContext(), mTexId);
				}
				mEncoder = encoder;
			}

/*
 * Now you can get frame data as ByteBuffer(as YUV/RGB565/RGBX/NV21 pixel format) using IFrameCallback interface
 * with UVCCamera#setFrameCallback instead of using following code samples.
 */
/*			// for part1
 			private static final int BUF_NUM = 1;
			private static final int BUF_STRIDE = 640 * 480;
			private static final int BUF_SIZE = BUF_STRIDE * BUF_NUM;
			int cnt = 0;
			int offset = 0;
			final int pixels[] = new int[BUF_SIZE];
			final IntBuffer buffer = IntBuffer.wrap(pixels); */
/*			// for part2
			private ByteBuffer buf = ByteBuffer.allocateDirect(640 * 480 * 4);
 */
			/**
			 * draw a frame (and request to draw for video capturing if it is necessary)
			 */
			public final void onDrawFrame() {
				mEglSurface.makeCurrent();
				// update texture(came from camera)
				mPreviewSurface.updateTexImage();
				// get texture matrix
				mPreviewSurface.getTransformMatrix(mStMatrix);
				// notify video encoder if it exist
				if (mEncoder != null) {
					// notify to capturing thread that the camera frame is available.
					if (mEncoder instanceof MediaVideoEncoder)
						((MediaVideoEncoder)mEncoder).frameAvailableSoon(mStMatrix);
					else
						mEncoder.frameAvailableSoon();
				}
				// draw to preview screen
				mDrawer.draw(mTexId, mStMatrix, 0);
				mEglSurface.swap();
/*				// sample code to read pixels into Buffer and save as a Bitmap (part1)
				buffer.position(offset);
				GLES20.glReadPixels(0, 0, 640, 480, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer);
				if (++cnt == 100) { // save as a Bitmap, only once on this sample code
					// if you save every frame as a Bitmap, app will crash by Out of Memory exception...
					Log.i(TAG, "Capture image using glReadPixels:offset=" + offset);
					final Bitmap bitmap = createBitmap(pixels,offset,  640, 480);
					final File outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png");
					try {
						final BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(outputFile));
						try {
							try {
								bitmap.compress(CompressFormat.PNG, 100, os);
								os.flush();
								bitmap.recycle();
							} catch (IOException e) {
							}
						} finally {
							os.close();
						}
					} catch (FileNotFoundException e) {
					} catch (IOException e) {
					}
				}
				offset = (offset + BUF_STRIDE) % BUF_SIZE;
*/
/*				// sample code to read pixels into Buffer and save as a Bitmap (part2)
		        buf.order(ByteOrder.LITTLE_ENDIAN);	// it is enough to call this only once.
		        GLES20.glReadPixels(0, 0, 640, 480, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
		        buf.rewind();
				if (++cnt == 100) {	// save as a Bitmap, only once on this sample code
					// if you save every frame as a Bitmap, app will crash by Out of Memory exception...
					final File outputFile = MediaMuxerWrapper.getCaptureFile(Environment.DIRECTORY_DCIM, ".png");
			        BufferedOutputStream os = null;
					try {
				        try {
				            os = new BufferedOutputStream(new FileOutputStream(outputFile));
				            Bitmap bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
				            bmp.copyPixelsFromBuffer(buf);
				            bmp.compress(Bitmap.CompressFormat.PNG, 90, os);
				            bmp.recycle();
				        } finally {
				            if (os != null) os.close();
				        }
					} catch (FileNotFoundException e) {
					} catch (IOException e) {
					}
				}
*/
			}

/*			// sample code to read pixels into IntBuffer and save as a Bitmap (part1)
			private static Bitmap createBitmap(final int[] pixels, final int offset, final int width, final int height) {
				final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
				paint.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
						0, 0, 1, 0, 0,
						0, 1, 0, 0, 0,
						1, 0, 0, 0, 0,
						0, 0, 0, 1, 0
					})));

				final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
				final Canvas canvas = new Canvas(bitmap);

				final Matrix matrix = new Matrix();
				matrix.postScale(1.0f, -1.0f);
				matrix.postTranslate(0, height);
				canvas.concat(matrix);

				canvas.drawBitmap(pixels, offset, width, 0, 0, width, height, false, paint);

				return bitmap;
			} */

			@Override
			public final void run() {
				Log.d(TAG, getName() + " started");
	            init();
	            Looper.prepare();
	            synchronized (mSync) {
	            	mHandler = new RenderHandler(mFpsCounter, this);
	                mSync.notify();
	            }

	            Looper.loop();

	            Log.d(TAG, getName() + " finishing");
	            release();
	            synchronized (mSync) {
	                mHandler = null;
	                mSync.notify();
	            }
			}

			private final void init() {
				if (DEBUG) Log.v(TAG, "RenderThread#init:");
				// create EGLContext for this thread
	            mEgl = EGLBase.createFrom(null, false, false);
	    		mEglSurface = mEgl.createFromSurface(mSurface);
	    		mEglSurface.makeCurrent();
	    		// create drawing object
	    		mDrawer = new GLDrawer2D(true);
			}

	    	private final void release() {
				if (DEBUG) { Log.v(TAG, "RenderThread#release:"); }
	    		if (mDrawer != null) {
	    			mDrawer.release();
	    			mDrawer = null;
	    		}
	    		if (mPreviewSurface != null) {
	    			mPreviewSurface.release();
	    			mPreviewSurface = null;
	    		}
	            if (mTexId >= 0) {
	            	GLHelper.deleteTex(mTexId);
	            	mTexId = -1;
	            }
	    		if (mEglSurface != null) {
	    			mEglSurface.release();
	    			mEglSurface = null;
	    		}
	    		if (mEgl != null) {
	    			mEgl.release();
	    			mEgl = null;
	    		}
	    	}
		}
	}

private class ZoomOnTouchListeners implements View.OnTouchListener {
	public ZoomOnTouchListeners() {
		super();
		m = new float[9];
		mScaleDetector = new ScaleGestureDetector(mContext, new ScaleListener());
		mSimpleDetector = new GestureDetector(new GestureDetector.SimpleOnGestureListener() {
			// 用到的双击的方法
			@Override
			public boolean onDoubleTap(MotionEvent e) {
				Log.d(TAG, "onDoubleTap");
				mode = NONE;
				matrix.reset();
				saveScale = 1;
				//UVCCameraTextureView.this.setTransform(matrix);
				//UVCCameraTextureView.this.invalidate();
				return true;
			}
		});
	}

	@Override
	public boolean onTouch(View view, MotionEvent motionEvent) {
		mSimpleDetector.onTouchEvent(motionEvent);
		mScaleDetector.onTouchEvent(motionEvent);

		matrix.getValues(m);
		float x = m[Matrix.MTRANS_X];
		float y = m[Matrix.MTRANS_Y];
		PointF curr = new PointF(motionEvent.getX(), motionEvent.getY());
		//PointF curr = new PointF(0, 0);
		Log.e("zoomCOORDINATES", "X :"+x+", Y :"+y);

		switch (motionEvent.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				last.set(motionEvent.getX(), motionEvent.getY());
				start.set(last);
				mode = DRAG;
				break;
			case MotionEvent.ACTION_UP:
				mode = NONE;
				break;
			case MotionEvent.ACTION_POINTER_DOWN:
				last.set(motionEvent.getX(), motionEvent.getY());
				start.set(last);
				mode = ZOOM;
				break;
			case MotionEvent.ACTION_POINTER_UP:
				mode = NONE;
				break;
			case MotionEvent.ACTION_MOVE:
				if (mode == ZOOM || (mode == DRAG && saveScale > minScale)) {
					float deltaX = curr.x - last.x;// x difference
					float deltaY = curr.y - last.y;// y difference
					if (y + deltaY > 0)
						deltaY = -y;
					else if (y + deltaY < -bottom)
						deltaY = -(y + bottom);

					if (x + deltaX > 0)
						deltaX = -x;
					else if (x + deltaX < -right)
						deltaX = -(x + right);
					matrix.postTranslate(deltaX, deltaY);
					last.set(curr.x, curr.y);
					Log.e("COORDINATES", "X :"+deltaX+", Y :"+deltaY);
				}
				break;
		}
		UVCCameraTextureView.this.setTransform(matrix);
		UVCCameraTextureView.this.invalidate();
		return true;
	}

	private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
		@Override
		public boolean onScaleBegin(ScaleGestureDetector detector) {
			mode = ZOOM;
			return true;
		}

		@Override
		public boolean onScale(ScaleGestureDetector detector) {
			float mScaleFactor = detector.getScaleFactor();
			float origScale = saveScale;
			saveScale *= mScaleFactor;
			Log.d(TAG, String.format("onScale %f %f", mScaleFactor, saveScale));
			if (saveScale > maxScale) {
				saveScale = maxScale;
				mScaleFactor = maxScale / origScale;
			} else if (saveScale < minScale) {
				saveScale = minScale;
				mScaleFactor = minScale / origScale;
			}
			right = getWidth() * saveScale - getWidth();
			bottom = getHeight() * saveScale - getHeight();
			if (0 <= getWidth() || 0 <= getHeight()) {
				// matrix.postScale(mScaleFactor, mScaleFactor, detector.getFocusX(), detector.getFocusY());
				matrix.postScale(mScaleFactor, mScaleFactor, 0,0);
				if (mScaleFactor < 1) {
					matrix.getValues(m);
					float x = m[Matrix.MTRANS_X];
					float y = m[Matrix.MTRANS_Y];
					if (mScaleFactor < 1) {
						if (0 < getWidth()) {
							if (y < -bottom)
								matrix.postTranslate(0, -(y + bottom));
							else if (y > 0)
								matrix.postTranslate(0, -y);
						} else {
							if (x < -right)
								matrix.postTranslate(-(x + right), 0);
							else if (x > 0)
								matrix.postTranslate(-x, 0);
						}
					}
				}
			} else {
				// matrix.postScale(mScaleFactor, mScaleFactor, detector.getFocusX(), detector.getFocusY());
				matrix.postScale(mScaleFactor, mScaleFactor, 0,0);
				matrix.getValues(m);
				float x = m[Matrix.MTRANS_X];
				float y = m[Matrix.MTRANS_Y];
				if (mScaleFactor < 1) {
					if (x < -right)
						matrix.postTranslate(-(x + right), 0);
					else if (x > 0)
						matrix.postTranslate(-x, 0);
					if (y < -bottom)
						matrix.postTranslate(0, -(y + bottom));
					else if (y > 0)
						matrix.postTranslate(0, -y);
				}
			}
			return true;
		}
	}
}

}