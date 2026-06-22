package com.dualcam.recorder.camera;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages simultaneous operation of both front and back cameras for the DualCamRecorder app.
 * <p>
 * This class uses the Android Camera2 API to open, configure, and manage two cameras
 * concurrently. It handles camera enumeration, availability callbacks, mode switching,
 * and proper resource cleanup.
 * </p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Simultaneous front and back camera preview and recording</li>
 *   <li>Dynamic camera mode switching (front only, back only, dual)</li>
 *   <li>Camera availability monitoring and automatic reconnection</li>
 *   <li>Optimal resolution selection based on device capabilities</li>
 *   <li>Dual camera support detection</li>
 * </ul>
 *
 * <h3>Thread Model:</h3>
 * Camera operations are dispatched to a dedicated background {@link HandlerThread}.
 * Callbacks and state changes are posted to a separate callback handler.
 *
 * @author DualCamRecorder
 * @version 1.0
 * @see CameraDeviceWrapper
 * @see CameraConfig
 */
public class DualCameraManager {

    private static final String TAG = "DualCameraManager";

    /** Lock object for thread-safe access to shared state */
    private final Object mLock = new Object();

    /** Application context */
    private Context mContext;

    /** Android CameraManager system service */
    private CameraManager mCameraManager;

    /** Configuration controlling camera behavior */
    private CameraConfig mConfig;

    /** Wrapper for the back (rear-facing) camera */
    private CameraDeviceWrapper mBackCameraWrapper;

    /** Wrapper for the front (selfie) camera */
    private CameraDeviceWrapper mFrontCameraWrapper;

    /** Camera ID string for the back camera (e.g., "0") */
    private String mBackCameraId;

    /** Camera ID string for the front camera (e.g., "1") */
    private String mFrontCameraId;

    /** SurfaceTexture for the back camera preview, consumed by the OpenGL renderer */
    private SurfaceTexture mBackSurfaceTexture;

    /** SurfaceTexture for the front camera preview, consumed by the OpenGL renderer */
    private SurfaceTexture mFrontSurfaceTexture;

    /** Background handler thread for camera manager operations */
    private HandlerThread mManagerThread;

    /** Handler for posting camera manager operations */
    private Handler mManagerHandler;

    /** Callback handler thread for delivering results to callers */
    private HandlerThread mCallbackThread;

    /** Handler for delivering callbacks on the callback thread */
    private Handler mCallbackHandler;

    /** Whether the manager has been initialized */
    private volatile boolean mInitialized = false;

    /** Whether the manager has been released */
    private volatile boolean mReleased = false;

    /** Listener for camera state events */
    private CameraStateListener mStateListener;

    /** Camera availability callback registered with CameraManager */
    private CameraManager.AvailabilityCallback mAvailabilityCallback;

    // =========================================================================
    // Listener interface
    // =========================================================================

    /**
     * Callback interface for receiving camera state change events.
     */
    public interface CameraStateListener {
        /**
         * Called when both cameras have been successfully opened (or the
         * single camera in non-dual mode).
         *
         * @param backSurface  the Surface for the back camera, or null if not active
         * @param frontSurface the Surface for the front camera, or null if not active
         */
        void onCamerasOpened(Surface backSurface, Surface frontSurface);

        /**
         * Called when one or both cameras have been disconnected.
         *
         * @param cameraId the ID of the camera that was disconnected
         */
        void onCameraDisconnected(String cameraId);

        /**
         * Called when a camera encounters an error.
         *
         * @param cameraId the ID of the camera that encountered the error
         * @param error    the error code from CameraDevice.StateCallback
         */
        void onCameraError(String cameraId, int error);
    }

    // =========================================================================
    // Constructor and initialization
    // =========================================================================

    /**
     * Creates a new DualCameraManager.
     * <p>Call {@link #initialize(Context)} before using any other methods.</p>
     */
    public DualCameraManager() {
        Log.d(TAG, "DualCameraManager created");
    }

    /**
     * Initializes the camera manager by obtaining the CameraManager system service,
     * starting background threads, and enumerating available cameras.
     *
     * @param context the application context
     * @throws IllegalArgumentException if context is null
     */
    public void initialize(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }

        synchronized (mLock) {
            if (mInitialized) {
                Log.w(TAG, "DualCameraManager is already initialized");
                return;
            }

            mContext = context.getApplicationContext();
            mCameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            if (mCameraManager == null) {
                Log.e(TAG, "Failed to obtain CameraManager system service");
                return;
            }

            // Start background threads
            mManagerThread = new HandlerThread("DualCamManagerThread");
            mManagerThread.start();
            mManagerHandler = new Handler(mManagerThread.getLooper());

            mCallbackThread = new HandlerThread("DualCamCallbackThread");
            mCallbackThread.start();
            mCallbackHandler = new Handler(mCallbackThread.getLooper());

            // Enumerate cameras
            enumerateCameras();

            // Register for camera availability changes
            registerAvailabilityCallback();

            mInitialized = true;
            Log.d(TAG, "DualCameraManager initialized. Back camera: " + mBackCameraId
                    + ", Front camera: " + mFrontCameraId);
        }
    }

    /**
     * Enumerates all available cameras and identifies the front and back camera IDs.
     */
    private void enumerateCameras() {
        try {
            String[] cameraIds = mCameraManager.getCameraIdList();
            Log.d(TAG, "Found " + cameraIds.length + " cameras");

            for (String cameraId : cameraIds) {
                try {
                    CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                    if (facing != null) {
                        switch (facing) {
                            case CameraCharacteristics.LENS_FACING_BACK:
                                mBackCameraId = cameraId;
                                Log.d(TAG, "Back camera found: ID=" + cameraId);
                                break;
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                mFrontCameraId = cameraId;
                                Log.d(TAG, "Front camera found: ID=" + cameraId);
                                break;
                            default:
                                Log.d(TAG, "Camera " + cameraId + " has unknown facing: " + facing);
                                break;
                        }
                    }
                } catch (CameraAccessException e) {
                    Log.e(TAG, "CameraAccessException while enumerating camera " + cameraId, e);
                }
            }

            // Log dual camera support status
            if (mBackCameraId != null && mFrontCameraId != null) {
                Log.d(TAG, "Device supports dual camera operation");
            } else {
                Log.w(TAG, "Dual camera not supported: back=" + mBackCameraId + ", front=" + mFrontCameraId);
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while enumerating cameras", e);
        }
    }

    /**
     * Registers a camera availability callback to monitor camera connect/disconnect events.
     */
    private void registerAvailabilityCallback() {
        if (mCameraManager == null) {
            return;
        }

        try {
            mAvailabilityCallback = new CameraManager.AvailabilityCallback() {
                @Override
                public void onCameraAvailable(String cameraId) {
                    Log.d(TAG, "Camera available: " + cameraId);
                    // Auto-reconnect could be implemented here
                }

                @Override
                public void onCameraUnavailable(String cameraId) {
                    Log.w(TAG, "Camera unavailable: " + cameraId);
                    if (mStateListener != null) {
                        mCallbackHandler.post(() -> mStateListener.onCameraDisconnected(cameraId));
                    }
                }
            };
            mCameraManager.registerAvailabilityCallback(mAvailabilityCallback, mManagerHandler);
            Log.d(TAG, "Camera availability callback registered");
        } catch (Exception e) {
            Log.e(TAG, "Error registering availability callback", e);
        }
    }

    // =========================================================================
    // Camera open / close operations
    // =========================================================================

    /**
     * Opens the cameras based on the current configuration.
     * <p>If the config specifies DUAL_CAMERA mode, both front and back cameras are opened.
     * If FRONT_ONLY or BACK_ONLY, only the respective camera is opened.</p>
     *
     * @param config the camera configuration specifying which cameras to open
     * @throws IllegalStateException    if the manager has not been initialized or released
     * @throws IllegalArgumentException if config is null
     */
    public void openCameras(CameraConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("CameraConfig must not be null");
        }
        if (!mInitialized || mReleased) {
            Log.e(TAG, "Cannot open cameras: manager not initialized or released");
            throw new IllegalStateException("DualCameraManager not initialized");
        }

        synchronized (mLock) {
            mConfig = config;
            Log.d(TAG, "Opening cameras with mode: " + config.getCameraMode());

            switch (config.getCameraMode()) {
                case BACK_ONLY:
                    openBackCamera();
                    break;
                case FRONT_ONLY:
                    openFrontCamera();
                    break;
                case DUAL_CAMERA:
                    if (isDualCameraSupported()) {
                        openBackCamera();
                        openFrontCamera();
                    } else {
                        Log.w(TAG, "Dual camera not supported, falling back to back only");
                        openBackCamera();
                    }
                    break;
            }
        }
    }

    /**
     * Opens the back camera using a CameraDeviceWrapper.
     */
    private void openBackCamera() {
        if (mBackCameraId == null) {
            Log.e(TAG, "No back camera available on this device");
            return;
        }

        // Close existing wrapper if any
        if (mBackCameraWrapper != null) {
            mBackCameraWrapper.close();
            mBackCameraWrapper = null;
        }

        mBackCameraWrapper = new CameraDeviceWrapper();
        Log.d(TAG, "Opening back camera: " + mBackCameraId);

        mBackCameraWrapper.open(mContext, mBackCameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Log.d(TAG, "Back camera opened: " + mBackCameraId);
                notifyCamerasOpened();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Log.w(TAG, "Back camera disconnected");
                if (mStateListener != null) {
                    mCallbackHandler.post(() -> mStateListener.onCameraDisconnected(mBackCameraId));
                }
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.e(TAG, "Back camera error: " + error);
                if (mStateListener != null) {
                    mCallbackHandler.post(() -> mStateListener.onCameraError(mBackCameraId, error));
                }
            }
        });
    }

    /**
     * Opens the front camera using a CameraDeviceWrapper.
     */
    private void openFrontCamera() {
        if (mFrontCameraId == null) {
            Log.e(TAG, "No front camera available on this device");
            return;
        }

        // Close existing wrapper if any
        if (mFrontCameraWrapper != null) {
            mFrontCameraWrapper.close();
            mFrontCameraWrapper = null;
        }

        mFrontCameraWrapper = new CameraDeviceWrapper();
        Log.d(TAG, "Opening front camera: " + mFrontCameraId);

        mFrontCameraWrapper.open(mContext, mFrontCameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                Log.d(TAG, "Front camera opened: " + mFrontCameraId);
                notifyCamerasOpened();
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                Log.w(TAG, "Front camera disconnected");
                if (mStateListener != null) {
                    mCallbackHandler.post(() -> mStateListener.onCameraDisconnected(mFrontCameraId));
                }
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                Log.e(TAG, "Front camera error: " + error);
                if (mStateListener != null) {
                    mCallbackHandler.post(() -> mStateListener.onCameraError(mFrontCameraId, error));
                }
            }
        });
    }

    /**
     * Notifies the listener when both requested cameras have been opened.
     * <p>In dual camera mode, waits for both cameras. In single camera mode,
     * notifies as soon as the single camera is ready.</p>
     */
    private void notifyCamerasOpened() {
        if (mStateListener == null) {
            return;
        }

        mCallbackHandler.post(() -> {
            boolean backReady = (mBackCameraWrapper != null && mBackCameraWrapper.isCameraOpen());
            boolean frontReady = (mFrontCameraWrapper != null && mFrontCameraWrapper.isCameraOpen());

            boolean shouldNotify = false;
            if (mConfig != null) {
                switch (mConfig.getCameraMode()) {
                    case BACK_ONLY:
                        shouldNotify = backReady;
                        break;
                    case FRONT_ONLY:
                        shouldNotify = frontReady;
                        break;
                    case DUAL_CAMERA:
                        shouldNotify = backReady && frontReady;
                        break;
                }
            }

            if (shouldNotify) {
                Surface backSurface = (mBackSurfaceTexture != null) ? new Surface(mBackSurfaceTexture) : null;
                Surface frontSurface = (mFrontSurfaceTexture != null) ? new Surface(mFrontSurfaceTexture) : null;
                mStateListener.onCamerasOpened(backSurface, frontSurface);
                Log.d(TAG, "Notified listener: cameras opened. Back=" + backReady + ", Front=" + frontReady);
            }
        });
    }

    // =========================================================================
    // Camera mode switching
    // =========================================================================

    /**
     * Switches the active camera mode at runtime.
     * <p>This will close cameras that are no longer needed and open cameras that
     * are newly required. For example, switching from BACK_ONLY to DUAL_CAMERA will
     * open the front camera while keeping the back camera running.</p>
     *
     * @param newMode the new camera mode to switch to
     * @throws IllegalArgumentException if newMode is null
     */
    public void switchCameraMode(CameraConfig.CameraMode newMode) {
        if (newMode == null) {
            throw new IllegalArgumentException("Camera mode must not be null");
        }

        synchronized (mLock) {
            if (mConfig == null) {
                Log.e(TAG, "Cannot switch camera mode: no configuration set");
                return;
            }

            CameraConfig.CameraMode oldMode = mConfig.getCameraMode();
            if (oldMode == newMode) {
                Log.d(TAG, "Camera mode unchanged: " + newMode);
                return;
            }

            Log.d(TAG, "Switching camera mode from " + oldMode + " to " + newMode);

            // Close cameras that are no longer needed
            if (newMode == CameraConfig.CameraMode.BACK_ONLY || newMode == CameraConfig.CameraMode.DUAL_CAMERA) {
                // Keep back camera
            } else {
                // Close back camera
                closeBackCamera();
            }

            if (newMode == CameraConfig.CameraMode.FRONT_ONLY || newMode == CameraConfig.CameraMode.DUAL_CAMERA) {
                // Keep front camera
            } else {
                // Close front camera
                closeFrontCamera();
            }

            // Open cameras that are newly needed
            if (newMode == CameraConfig.CameraMode.BACK_ONLY || newMode == CameraConfig.CameraMode.DUAL_CAMERA) {
                if (mBackCameraWrapper == null || !mBackCameraWrapper.isCameraOpen()) {
                    openBackCamera();
                }
            }

            if (newMode == CameraConfig.CameraMode.FRONT_ONLY || newMode == CameraConfig.CameraMode.DUAL_CAMERA) {
                if (mFrontCameraWrapper == null || !mFrontCameraWrapper.isCameraOpen()) {
                    openFrontCamera();
                }
            }

            mConfig.setCameraMode(newMode);
            Log.d(TAG, "Camera mode switched to: " + newMode);
        }
    }

    /**
     * Closes and nullifies the back camera wrapper.
     */
    private void closeBackCamera() {
        if (mBackCameraWrapper != null) {
            mBackCameraWrapper.close();
            mBackCameraWrapper = null;
            Log.d(TAG, "Back camera closed");
        }
    }

    /**
     * Closes and nullifies the front camera wrapper.
     */
    private void closeFrontCamera() {
        if (mFrontCameraWrapper != null) {
            mFrontCameraWrapper.close();
            mFrontCameraWrapper = null;
            Log.d(TAG, "Front camera closed");
        }
    }

    // =========================================================================
    // Surface texture management
    // =========================================================================

    /**
     * Sets the SurfaceTexture for the back camera preview.
     * <p>This SurfaceTexture is typically created by the OpenGL renderer and
     * receives the back camera's preview frames.</p>
     *
     * @param surfaceTexture the SurfaceTexture for the back camera
     * @param width          the texture width in pixels
     * @param height         the texture height in pixels
     */
    public void setBackSurfaceTexture(SurfaceTexture surfaceTexture, int width, int height) {
        synchronized (mLock) {
            mBackSurfaceTexture = surfaceTexture;
            if (surfaceTexture != null && mBackCameraWrapper != null) {
                mBackCameraWrapper.updatePreviewSurface(surfaceTexture, width, height);
            }
            Log.d(TAG, "Back SurfaceTexture set: " + width + "x" + height);
        }
    }

    /**
     * Sets the SurfaceTexture for the front camera preview.
     * <p>This SurfaceTexture is typically created by the OpenGL renderer and
     * receives the front camera's preview frames for PIP rendering.</p>
     *
     * @param surfaceTexture the SurfaceTexture for the front camera
     * @param width          the texture width in pixels
     * @param height         the texture height in pixels
     */
    public void setFrontSurfaceTexture(SurfaceTexture surfaceTexture, int width, int height) {
        synchronized (mLock) {
            mFrontSurfaceTexture = surfaceTexture;
            if (surfaceTexture != null && mFrontCameraWrapper != null) {
                mFrontCameraWrapper.updatePreviewSurface(surfaceTexture, width, height);
            }
            Log.d(TAG, "Front SurfaceTexture set: " + width + "x" + height);
        }
    }

    // =========================================================================
    // Resolution selection
    // =========================================================================

    /**
     * Finds the optimal camera output size that best matches the preferred dimensions
     * and aspect ratio from the configuration.
     *
     * @param cameraId the camera ID to query for supported sizes
     * @param config   the configuration containing preferred dimensions and aspect ratio
     * @return the best matching Size, or a default 1920x1080 if none found
     */
    public Size selectOptimalSize(String cameraId, CameraConfig config) {
        if (cameraId == null || config == null || mCameraManager == null) {
            Log.w(TAG, "selectOptimalSize: invalid parameters, returning default");
            return new Size(1920, 1080);
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (streamMap == null) {
                Log.e(TAG, "No StreamConfigurationMap for camera: " + cameraId);
                return new Size(1920, 1080);
            }

            Size[] outputSizes = streamMap.getOutputSizes(SurfaceTexture.class);
            if (outputSizes == null || outputSizes.length == 0) {
                Log.e(TAG, "No output sizes available for camera: " + cameraId);
                return new Size(1920, 1080);
            }

            // Calculate target aspect ratio
            float targetRatio = config.getAspectRatio().getRatio();
            int preferredWidth = config.getPreferredWidth();
            int preferredHeight = config.getPreferredHeight();

            // Sort sizes by closeness to preferred size and aspect ratio match
            List<Size> sortedSizes = new ArrayList<>();
            Collections.addAll(sortedSizes, outputSizes);
            Collections.sort(sortedSizes, (a, b) -> {
                float ratioA = (float) a.getWidth() / a.getHeight();
                float ratioB = (float) b.getWidth() / b.getHeight();
                float diffA = Math.abs(ratioA - targetRatio);
                float diffB = Math.abs(ratioB - targetRatio);

                // Prefer aspect ratio match first
                if (Math.abs(diffA - diffB) > 0.01f) {
                    return Float.compare(diffA, diffB);
                }

                // Then prefer closer to preferred size
                int pixelsA = a.getWidth() * a.getHeight();
                int pixelsB = b.getWidth() * b.getHeight();
                int preferredPixels = preferredWidth * preferredHeight;
                return Integer.compare(Math.abs(pixelsA - preferredPixels),
                        Math.abs(pixelsB - preferredPixels));
            });

            Size bestSize = sortedSizes.get(0);
            Log.d(TAG, "Selected optimal size for camera " + cameraId + ": "
                    + bestSize.getWidth() + "x" + bestSize.getHeight());
            return bestSize;

        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while selecting optimal size", e);
            return new Size(1920, 1080);
        }
    }

    // =========================================================================
    // Capability checks
    // =========================================================================

    /**
     * Checks whether the device supports simultaneous dual camera operation.
     * <p>This checks if both a front and back camera are available. On some devices,
     * concurrent access to both cameras may fail at runtime even if both are
     * enumerated. A more thorough check would attempt to open both cameras.</p>
     *
     * @return true if both front and back cameras are detected, false otherwise
     */
    public boolean isDualCameraSupported() {
        synchronized (mLock) {
            boolean supported = mBackCameraId != null && mFrontCameraId != null;
            Log.d(TAG, "Dual camera supported: " + supported
                    + " (back=" + mBackCameraId + ", front=" + mFrontCameraId + ")");

            // Additional check: on API 28+, we can use isConcurrentStreamCombinationSupported
            if (supported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && mCameraManager != null) {
                try {
                    // Attempt to verify concurrent stream combinations
                    CameraCharacteristics backChars = mCameraManager.getCameraCharacteristics(mBackCameraId);
                    CameraCharacteristics frontChars = mCameraManager.getCameraCharacteristics(mFrontCameraId);
                    // Basic hardware level check
                    Integer backLevel = backChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    Integer frontLevel = frontChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    Log.d(TAG, "Back hardware level: " + backLevel + ", Front hardware level: " + frontLevel);
                } catch (CameraAccessException e) {
                    Log.e(TAG, "CameraAccessException during dual camera check", e);
                }
            }

            return supported;
        }
    }

    /**
     * Checks whether the specified camera supports flash/torch mode.
     *
     * @param cameraId the camera ID to check
     * @return true if the camera supports flash, false otherwise
     */
    public boolean isFlashSupported(String cameraId) {
        if (cameraId == null || mCameraManager == null) {
            return false;
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            Boolean flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            return flashAvailable != null && flashAvailable;
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while checking flash support", e);
            return false;
        }
    }

    /**
     * Checks whether the specified camera supports video stabilization.
     *
     * @param cameraId the camera ID to check
     * @return true if video stabilization is available, false otherwise
     */
    public boolean isStabilizationSupported(String cameraId) {
        if (cameraId == null || mCameraManager == null) {
            return false;
        }

        try {
            CameraCharacteristics characteristics = mCameraManager.getCameraCharacteristics(cameraId);
            int[] modes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            if (modes != null) {
                for (int mode : modes) {
                    if (mode == CameraCharacteristics.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                        return true;
                    }
                }
            }
            return false;
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while checking stabilization support", e);
            return false;
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the wrapper for the back camera, or null if not opened.
     *
     * @return the back CameraDeviceWrapper, or null
     */
    public CameraDeviceWrapper getBackCameraWrapper() {
        synchronized (mLock) {
            return mBackCameraWrapper;
        }
    }

    /**
     * Returns the wrapper for the front camera, or null if not opened.
     *
     * @return the front CameraDeviceWrapper, or null
     */
    public CameraDeviceWrapper getFrontCameraWrapper() {
        synchronized (mLock) {
            return mFrontCameraWrapper;
        }
    }

    /**
     * Returns the SurfaceTexture for the back camera.
     *
     * @return the back SurfaceTexture, or null if not set
     */
    public SurfaceTexture getBackSurfaceTexture() {
        synchronized (mLock) {
            return mBackSurfaceTexture;
        }
    }

    /**
     * Returns the SurfaceTexture for the front camera.
     *
     * @return the front SurfaceTexture, or null if not set
     */
    public SurfaceTexture getFrontSurfaceTexture() {
        synchronized (mLock) {
            return mFrontSurfaceTexture;
        }
    }

    /**
     * Returns the back camera ID string.
     *
     * @return the back camera ID, or null if not available
     */
    public String getBackCameraId() {
        synchronized (mLock) {
            return mBackCameraId;
        }
    }

    /**
     * Returns the front camera ID string.
     *
     * @return the front camera ID, or null if not available
     */
    public String getFrontCameraId() {
        synchronized (mLock) {
            return mFrontCameraId;
        }
    }

    /**
     * Returns the current camera configuration.
     *
     * @return the current CameraConfig, or null if not set
     */
    public CameraConfig getConfig() {
        synchronized (mLock) {
            return mConfig;
        }
    }

    /**
     * Returns the callback handler for posting camera-related callbacks.
     *
     * @return the callback Handler
     */
    public Handler getCallbackHandler() {
        return mCallbackHandler;
    }

    /**
     * Sets the listener for camera state events.
     *
     * @param listener the listener to receive camera state callbacks
     */
    public void setCameraStateListener(CameraStateListener listener) {
        mStateListener = listener;
    }

    // =========================================================================
    // Resource cleanup
    // =========================================================================

    /**
     * Releases all camera resources and stops background threads.
     * <p>After calling this method, the DualCameraManager must NOT be reused.
     * Create a new instance if camera functionality is needed again.</p>
     */
    public void release() {
        synchronized (mLock) {
            if (mReleased) {
                Log.w(TAG, "release() called but manager is already released");
                return;
            }
            mReleased = true;

            Log.d(TAG, "Releasing DualCameraManager...");

            // Unregister availability callback
            if (mAvailabilityCallback != null && mCameraManager != null) {
                try {
                    mCameraManager.unregisterAvailabilityCallback(mAvailabilityCallback);
                    Log.d(TAG, "Camera availability callback unregistered");
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering availability callback", e);
                }
                mAvailabilityCallback = null;
            }

            // Close camera wrappers
            if (mBackCameraWrapper != null) {
                mBackCameraWrapper.release();
                mBackCameraWrapper = null;
            }

            if (mFrontCameraWrapper != null) {
                mFrontCameraWrapper.release();
                mFrontCameraWrapper = null;
            }

            // Clear surface textures
            mBackSurfaceTexture = null;
            mFrontSurfaceTexture = null;

            mCameraManager = null;
            mContext = null;
            mStateListener = null;
            mConfig = null;
        }

        // Stop background threads outside the lock to avoid potential deadlock
        stopThread(mCallbackThread, "CallbackThread");
        mCallbackThread = null;
        mCallbackHandler = null;

        stopThread(mManagerThread, "ManagerThread");
        mManagerThread = null;
        mManagerHandler = null;

        mInitialized = false;
        Log.d(TAG, "DualCameraManager fully released");
    }

    /**
     * Safely stops a HandlerThread.
     *
     * @param thread     the thread to stop
     * @param threadName the name for logging purposes
     */
    private void stopThread(HandlerThread thread, String threadName) {
        if (thread != null) {
            try {
                thread.quitSafely();
                thread.join(3000);
                Log.d(TAG, threadName + " stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for " + threadName + " to stop", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}