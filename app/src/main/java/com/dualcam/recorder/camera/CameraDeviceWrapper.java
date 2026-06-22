package com.dualcam.recorder.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.util.List;

/**
 * Wraps a single {@link CameraDevice} from the Android Camera2 API, providing a
 * simplified interface for opening, configuring, and controlling a camera.
 * <p>
 * This class manages a dedicated {@link HandlerThread} for all camera operations,
 * ensuring that camera callbacks and capture sessions run on a background thread
 * and do not block the UI.
 * </p>
 *
 * <h3>Typical Lifecycle:</h3>
 * <ol>
 *   <li>Create an instance with a camera ID</li>
 *   <li>Call {@link #open(Context, String, CameraDevice.StateCallback)} to open the camera</li>
 *   <li>Call {@link #createCaptureSession(List, CameraCaptureSession.StateCallback)} to create a session</li>
 *   <li>Call {@link #startPreview(CaptureRequest.Builder)} to begin preview</li>
 *   <li>Call {@link #close()} when done to release all resources</li>
 * </ol>
 *
 * <h3>Thread Safety:</h3>
 * All public methods are thread-safe. Camera operations are serialized on a dedicated
 * background handler thread.
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class CameraDeviceWrapper {

    private static final String TAG = "CameraDeviceWrapper";

    /** Lock object for synchronizing access to shared state */
    private final Object mLock = new Object();

    /** Background handler thread for camera operations */
    private HandlerThread mCameraThread;

    /** Handler associated with the camera thread for posting camera callbacks */
    private Handler mCameraHandler;

    /** The underlying Camera2 CameraDevice instance */
    private CameraDevice mCameraDevice;

    /** The currently active capture session, or null if no session exists */
    private CameraCaptureSession mCaptureSession;

    /** The camera ID string (e.g., "0" for back, "1" for front) */
    private String mCameraId;

    /** Camera characteristics for the wrapped device */
    private CameraCharacteristics mCameraCharacteristics;

    /** Whether the camera device is currently open */
    private volatile boolean mIsOpen = false;

    /** Whether the wrapper has been released and should not be used */
    private volatile boolean mReleased = false;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new CameraDeviceWrapper.
     * <p>The background handler thread is started immediately upon construction.</p>
     */
    public CameraDeviceWrapper() {
        mCameraThread = new HandlerThread("CameraThread-" + hashCode());
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
        Log.d(TAG, "CameraDeviceWrapper created with thread: " + mCameraThread.getName());
    }

    // =========================================================================
    // Camera open / close
    // =========================================================================

    /**
     * Opens the camera device with the specified ID.
     * <p>This method is asynchronous. The result is delivered through the provided
     * {@link CameraDevice.StateCallback}. All camera operations will be dispatched
     * to the internal background handler thread.</p>
     *
     * @param context     the application context, used to obtain the CameraManager system service
     * @param cameraId    the ID of the camera to open (e.g., "0" for back, "1" for front)
     * @param callback    callback to receive camera state changes (opened, disconnected, error)
     * @throws IllegalStateException    if the wrapper has already been released
     * @throws IllegalArgumentException if context or cameraId is null
     */
    public void open(Context context, String cameraId, CameraDevice.StateCallback callback) {
        if (mReleased) {
            Log.e(TAG, "Cannot open camera: wrapper has been released");
            throw new IllegalStateException("CameraDeviceWrapper has been released");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        if (cameraId == null || cameraId.isEmpty()) {
            throw new IllegalArgumentException("Camera ID must not be null or empty");
        }
        if (callback == null) {
            throw new IllegalArgumentException("StateCallback must not be null");
        }

        synchronized (mLock) {
            if (mIsOpen) {
                Log.w(TAG, "Camera " + mCameraId + " is already open, closing first");
                closeInternalLocked();
            }

            mCameraId = cameraId;
            Log.d(TAG, "Opening camera: " + cameraId);

            try {
                CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (cameraManager == null) {
                    Log.e(TAG, "Failed to get CameraManager system service");
                    return;
                }

                // Retrieve camera characteristics
                mCameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);

                // Wrap the user callback to track open state internally
                CameraDevice.StateCallback wrappedCallback = new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        synchronized (mLock) {
                            mCameraDevice = camera;
                            mIsOpen = true;
                        }
                        Log.d(TAG, "Camera opened successfully: " + cameraId);
                        callback.onOpened(camera);
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        synchronized (mLock) {
                            mIsOpen = false;
                            mCameraDevice = null;
                        }
                        Log.w(TAG, "Camera disconnected: " + cameraId);
                        callback.onDisconnected(camera);
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        synchronized (mLock) {
                            mIsOpen = false;
                            mCameraDevice = null;
                        }
                        Log.e(TAG, "Camera error: " + cameraId + ", error code: " + error);
                        callback.onError(camera, error);
                    }
                };

                cameraManager.openCamera(cameraId, wrappedCallback, mCameraHandler);

            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException while opening camera " + cameraId, e);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException: camera permission not granted for " + cameraId, e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while opening camera " + cameraId, e);
            }
        }
    }

    /**
     * Closes the camera device and releases all associated resources.
     * <p>This method is safe to call from any thread. After calling this method,
     * the wrapper can be reused by calling {@link #open} again.</p>
     */
    public void close() {
        synchronized (mLock) {
            Log.d(TAG, "Closing camera device: " + mCameraId);
            closeInternalLocked();
        }
    }

    /**
     * Internal method that performs the actual close operation. Must be called while
     * holding {@link #mLock}.
     */
    private void closeInternalLocked() {
        try {
            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
                Log.d(TAG, "Capture session closed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing capture session", e);
        }

        try {
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
                Log.d(TAG, "Camera device closed");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera device", e);
        }

        mIsOpen = false;
    }

    /**
     * Fully releases this wrapper, including the background handler thread.
     * <p>After calling this method, the wrapper must NOT be reused.</p>
     */
    public void release() {
        synchronized (mLock) {
            if (mReleased) {
                Log.w(TAG, "release() called but wrapper is already released");
                return;
            }
            mReleased = true;
            closeInternalLocked();
        }

        if (mCameraThread != null) {
            try {
                mCameraThread.quitSafely();
                mCameraThread.join(2000);
                Log.d(TAG, "Camera handler thread stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for camera thread to stop", e);
                Thread.currentThread().interrupt();
            }
            mCameraThread = null;
            mCameraHandler = null;
        }

        Log.d(TAG, "CameraDeviceWrapper fully released");
    }

    // =========================================================================
    // Capture session
    // =========================================================================

    /**
     * Creates a new camera capture session with the given output surfaces.
     * <p>Any previously active capture session will be closed before creating the new one.</p>
     *
     * @param surfaces the list of {@link Surface} objects for the capture session outputs
     * @param callback callback to receive session state changes
     * @throws IllegalStateException if the camera is not open
     */
    public void createCaptureSession(List<Surface> surfaces, CameraCaptureSession.StateCallback callback) {
        if (callback == null) {
            Log.e(TAG, "createCaptureSession: StateCallback is null");
            return;
        }

        synchronized (mLock) {
            if (!mIsOpen || mCameraDevice == null) {
                Log.e(TAG, "Cannot create capture session: camera is not open");
                return;
            }

            // Close existing session if any
            if (mCaptureSession != null) {
                try {
                    mCaptureSession.close();
                    mCaptureSession = null;
                    Log.d(TAG, "Existing capture session closed before creating new one");
                } catch (Exception e) {
                    Log.e(TAG, "Error closing existing capture session", e);
                }
            }

            try {
                CameraCaptureSession.StateCallback wrappedCallback = new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        synchronized (mLock) {
                            mCaptureSession = session;
                        }
                        Log.d(TAG, "Capture session configured for camera: " + mCameraId);
                        callback.onConfigured(session);
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        Log.e(TAG, "Capture session configuration failed for camera: " + mCameraId);
                        callback.onConfigureFailed(session);
                    }
                };

                mCameraDevice.createCaptureSession(surfaces, wrappedCallback, mCameraHandler);
                Log.d(TAG, "Creating capture session with " + surfaces.size() + " surfaces");

            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException while creating capture session", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while creating capture session", e);
            }
        }
    }

    /**
     * Starts repeating preview by setting the given capture request builder as the
     * repeating request on the active capture session.
     *
     * @param previewBuilder the capture request builder configured for preview
     * @throws IllegalStateException if no capture session is available
     */
    public void startPreview(CaptureRequest.Builder previewBuilder) {
        if (previewBuilder == null) {
            Log.e(TAG, "startPreview: CaptureRequest.Builder is null");
            return;
        }

        synchronized (mLock) {
            if (mCaptureSession == null) {
                Log.e(TAG, "Cannot start preview: no capture session available");
                return;
            }

            try {
                CaptureRequest previewRequest = previewBuilder.build();
                mCaptureSession.setRepeatingRequest(previewRequest, null, mCameraHandler);
                Log.d(TAG, "Preview started for camera: " + mCameraId);
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException while starting preview", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while starting preview", e);
            }
        }
    }

    /**
     * Stops the repeating preview request.
     * <p>This is a no-op if no preview is currently running.</p>
     */
    public void stopPreview() {
        synchronized (mLock) {
            if (mCaptureSession == null) {
                Log.w(TAG, "stopPreview: no capture session available");
                return;
            }

            try {
                mCaptureSession.stopRepeating();
                Log.d(TAG, "Preview stopped for camera: " + mCameraId);
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException while stopping preview", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while stopping preview", e);
            }
        }
    }

    /**
     * Sends a single capture request (e.g., for taking a still photo).
     *
     * @param captureBuilder the capture request builder configured for still capture
     * @param callback       callback to receive the capture result
     */
    public void capture(CaptureRequest.Builder captureBuilder,
                        CameraCaptureSession.CaptureCallback callback) {
        if (captureBuilder == null) {
            Log.e(TAG, "capture: CaptureRequest.Builder is null");
            return;
        }

        synchronized (mLock) {
            if (mCaptureSession == null) {
                Log.e(TAG, "Cannot capture: no capture session available");
                return;
            }

            try {
                mCaptureSession.capture(captureBuilder.build(), callback, mCameraHandler);
                Log.d(TAG, "Single capture triggered for camera: " + mCameraId);
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException while capturing", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error while capturing", e);
            }
        }
    }

    /**
     * Updates the preview surface by creating a new capture session with the updated
     * surface texture.
     * <p>This is useful when the preview surface dimensions change, such as when
     * switching aspect ratios.</p>
     *
     * @param surfaceTexture the new SurfaceTexture for the preview
     * @param width          the width of the SurfaceTexture in pixels
     * @param height         the height of the SurfaceTexture in pixels
     */
    public void updatePreviewSurface(SurfaceTexture surfaceTexture, int width, int height) {
        if (surfaceTexture == null) {
            Log.e(TAG, "updatePreviewSurface: SurfaceTexture is null");
            return;
        }

        synchronized (mLock) {
            if (!mIsOpen || mCameraDevice == null) {
                Log.e(TAG, "Cannot update preview surface: camera is not open");
                return;
            }

            try {
                surfaceTexture.setDefaultBufferSize(width, height);
                Surface surface = new Surface(surfaceTexture);
                Log.d(TAG, "Preview surface updated: " + width + "x" + height +
                        " for camera: " + mCameraId);
            } catch (Exception e) {
                Log.e(TAG, "Error updating preview surface", e);
            }
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the camera ID associated with this wrapper.
     *
     * @return the camera ID string, or null if no camera has been opened
     */
    public String getCameraId() {
        synchronized (mLock) {
            return mCameraId;
        }
    }

    /**
     * Returns whether the camera device is currently open and ready for use.
     *
     * @return true if the camera is open, false otherwise
     */
    public boolean isCameraOpen() {
        return mIsOpen;
    }

    /**
     * Returns the {@link CameraDevice} instance, or null if the camera is not open.
     *
     * @return the CameraDevice, or null
     */
    public CameraDevice getCameraDevice() {
        synchronized (mLock) {
            return mCameraDevice;
        }
    }

    /**
     * Returns the {@link CameraCaptureSession} instance, or null if no session is active.
     *
     * @return the CameraCaptureSession, or null
     */
    public CameraCaptureSession getCaptureSession() {
        synchronized (mLock) {
            return mCaptureSession;
        }
    }

    /**
     * Returns the {@link CameraCharacteristics} for this camera device, or null if
     * the camera has not been opened yet.
     *
     * @return the CameraCharacteristics, or null
     */
    public CameraCharacteristics getCameraCharacteristics() {
        synchronized (mLock) {
            return mCameraCharacteristics;
        }
    }

    /**
     * Returns the background {@link Handler} used for camera operations.
     * <p>This handler can be used to post camera-related runnables.</p>
     *
     * @return the camera handler, or null if the wrapper has been released
     */
    public Handler getCameraHandler() {
        return mCameraHandler;
    }

    /**
     * Creates a new CaptureRequest.Builder for preview using the current camera device.
     *
     * @param templateType the template type (e.g., CameraDevice.TEMPLATE_PREVIEW)
     * @return a new CaptureRequest.Builder, or null if the camera is not open
     */
    public CaptureRequest.Builder createCaptureRequest(int templateType) {
        synchronized (mLock) {
            if (!mIsOpen || mCameraDevice == null) {
                Log.e(TAG, "Cannot create capture request: camera is not open");
                return null;
            }

            try {
                return mCameraDevice.createCaptureRequest(templateType);
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException while creating capture request", e);
                return null;
            }
        }
    }

    /**
     * Returns whether this wrapper has been released and should no longer be used.
     *
     * @return true if released, false otherwise
     */
    public boolean isReleased() {
        return mReleased;
    }
}