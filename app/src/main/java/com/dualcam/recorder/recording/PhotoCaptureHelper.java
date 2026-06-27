package com.dualcam.recorder.recording;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import androidx.exifinterface.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;

import com.dualcam.recorder.camera.CameraDeviceWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper class for capturing still photos from one or both cameras simultaneously
 * using the Camera2 API's {@link ImageReader}.
 * <p>
 * This class handles the complete photo capture pipeline:
 * <ol>
 *   <li>Configures an ImageReader with JPEG format for each camera</li>
 *   <li>Triggers still capture via Camera2 capture requests</li>
 *   <li>Saves captured images to DCIM folder with proper EXIF orientation</li>
 *   <li>Supports simultaneous dual-camera capture using separate threads</li>
 * </ol>
 *
 * <h3>Thread Model:</h3>
 * Photo capture callbacks and I/O operations run on a dedicated background thread.
 * Dual-camera captures use a fixed thread pool of 2 threads.
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class PhotoCaptureHelper {

    private static final String TAG = "PhotoCaptureHelper";

    /** Maximum JPEG image dimension (for ImageReader maxImages parameter) */
    private static final int MAX_IMAGES = 2;

    /** JPEG compression quality (0-100) */
    private static final int JPEG_QUALITY = 95;

    /** Photo capture timeout in seconds */
    private static final long CAPTURE_TIMEOUT_SECONDS = 10;

    // =========================================================================
    // Callback interface
    // =========================================================================

    /**
     * Callback interface for receiving photo capture results.
     */
    public interface CaptureCallback {
        /**
         * Called when a photo has been successfully captured and saved.
         *
         * @param filePath the absolute path to the saved photo file
         * @param isBack   true if this is from the back camera, false if from the front
         */
        void onPhotoCaptured(String filePath, boolean isBack);

        /**
         * Called when dual-camera capture is complete with both photos.
         *
         * @param backFilePath  the path to the back camera photo, or null if failed
         * @param frontFilePath the path to the front camera photo, or null if failed
         */
        void onDualCaptureComplete(String backFilePath, String frontFilePath);

        /**
         * Called when a photo capture fails.
         *
         * @param errorMessage a human-readable error description
         */
        void onCaptureError(String errorMessage);
    }

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Lock object for thread-safe access */
    private final Object mLock = new Object();

    /** Application context */
    private final Context mContext;

    /** Background handler thread for capture operations */
    private HandlerThread mCaptureThread;

    /** Handler for posting capture operations */
    private Handler mCaptureHandler;

    /** Thread pool for concurrent dual-camera captures */
    private ExecutorService mDualCaptureExecutor;

    /** ImageReader for the back camera */
    private ImageReader mBackImageReader;

    /** ImageReader for the front camera */
    private ImageReader mFrontImageReader;

    /** Whether the helper has been released */
    private volatile boolean mReleased = false;

    /** Counter for generating unique file names */
    private final AtomicInteger mFileCounter = new AtomicInteger(0);

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new PhotoCaptureHelper.
     *
     * @param context the application context
     * @throws IllegalArgumentException if context is null
     */
    public PhotoCaptureHelper(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        mContext = context.getApplicationContext();

        // Start background thread for capture operations
        mCaptureThread = new HandlerThread("PhotoCaptureThread");
        mCaptureThread.start();
        mCaptureHandler = new Handler(mCaptureThread.getLooper());

        // Thread pool for concurrent dual-camera capture
        mDualCaptureExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DualCaptureThread-" + mFileCounter.incrementAndGet());
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });

        Log.d(TAG, "PhotoCaptureHelper created");
    }

    // =========================================================================
    // ImageReader setup
    // =========================================================================

    /**
     * Sets up an ImageReader for the back camera with the specified dimensions.
     * <p>The ImageReader uses JPEG format and provides a listener that saves the
     * captured image to storage.</p>
     *
     * @param width    the capture width in pixels
     * @param height   the capture height in pixels
     * @param callback callback to receive capture results
     * @return the configured ImageReader, or null on failure
     */
    public ImageReader setupBackImageReader(int width, int height, CaptureCallback callback) {
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid back camera dimensions: " + width + "x" + height);
            return null;
        }

        synchronized (mLock) {
            // Close existing reader if any
            closeImageReader(mBackImageReader, "back");

            try {
                mBackImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, MAX_IMAGES);
                mBackImageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            String filePath = saveImage(image, "back");
                            if (callback != null) {
                                new Handler(android.os.Looper.getMainLooper()).post(
                                        () -> callback.onPhotoCaptured(filePath, true));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing back camera image", e);
                        if (callback != null) {
                            new Handler(android.os.Looper.getMainLooper()).post(
                                    () -> callback.onCaptureError("Back camera capture error: " + e.getMessage()));
                        }
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }, mCaptureHandler);

                Log.d(TAG, "Back ImageReader created: " + width + "x" + height);
                return mBackImageReader;

            } catch (Exception e) {
                Log.e(TAG, "Error creating back ImageReader", e);
                return null;
            }
        }
    }

    /**
     * Sets up an ImageReader for the front camera with the specified dimensions.
     *
     * @param width    the capture width in pixels
     * @param height   the capture height in pixels
     * @param callback callback to receive capture results
     * @return the configured ImageReader, or null on failure
     */
    public ImageReader setupFrontImageReader(int width, int height, CaptureCallback callback) {
        if (width <= 0 || height <= 0) {
            Log.e(TAG, "Invalid front camera dimensions: " + width + "x" + height);
            return null;
        }

        synchronized (mLock) {
            // Close existing reader if any
            closeImageReader(mFrontImageReader, "front");

            try {
                mFrontImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, MAX_IMAGES);
                mFrontImageReader.setOnImageAvailableListener(reader -> {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image != null) {
                            String filePath = saveImage(image, "front");
                            if (callback != null) {
                                new Handler(android.os.Looper.getMainLooper()).post(
                                        () -> callback.onPhotoCaptured(filePath, false));
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing front camera image", e);
                        if (callback != null) {
                            new Handler(android.os.Looper.getMainLooper()).post(
                                    () -> callback.onCaptureError("Front camera capture error: " + e.getMessage()));
                        }
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }, mCaptureHandler);

                Log.d(TAG, "Front ImageReader created: " + width + "x" + height);
                return mFrontImageReader;

            } catch (Exception e) {
                Log.e(TAG, "Error creating front ImageReader", e);
                return null;
            }
        }
    }

    // =========================================================================
    // Capture methods
    // =========================================================================

    /**
     * Captures a still photo from the back camera.
     * <p>Creates a still capture request using the back camera's CameraDeviceWrapper
     * and the configured back ImageReader surface.</p>
     *
     * @param lastResult the last CaptureResult (used for 3A convergence)
     * @param wrapper    the CameraDeviceWrapper for the back camera
     * @param callback   callback to receive the capture result
     * @throws IllegalArgumentException if wrapper or lastResult is null
     */
    public void captureBackCamera(CaptureResult lastResult, CameraDeviceWrapper wrapper,
                                  CaptureCallback callback) {
        if (wrapper == null) {
            throw new IllegalArgumentException("CameraDeviceWrapper must not be null");
        }
        if (lastResult == null) {
            throw new IllegalArgumentException("CaptureResult must not be null");
        }

        synchronized (mLock) {
            if (mReleased) {
                Log.e(TAG, "Cannot capture: helper has been released");
                if (callback != null) {
                    callback.onCaptureError("PhotoCaptureHelper has been released");
                }
                return;
            }

            if (mBackImageReader == null) {
                Log.e(TAG, "Cannot capture back camera: ImageReader not set up");
                if (callback != null) {
                    callback.onCaptureError("Back ImageReader not initialized");
                }
                return;
            }

            try {
                CaptureRequest.Builder builder = wrapper.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                if (builder == null) {
                    Log.e(TAG, "Failed to create still capture request for back camera");
                    if (callback != null) {
                        callback.onCaptureError("Failed to create capture request");
                    }
                    return;
                }

                builder.addTarget(mBackImageReader.getSurface());
                applyAeSettings(builder, lastResult);

                try {
                    builder.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
                } catch (Exception e) {
                    Log.w(TAG, "Could not set JPEG quality", e);
                }

                wrapper.capture(builder, new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session,
                                                   CaptureRequest request, TotalCaptureResult result) {
                        Log.d(TAG, "Back camera capture completed");
                    }

                    @Override
                    public void onCaptureFailed(CameraCaptureSession session,
                                                CaptureRequest request, CaptureFailure failure) {
                        Log.e(TAG, "Back camera capture failed: " + failure.getReason());
                        if (callback != null) {
                            new Handler(android.os.Looper.getMainLooper()).post(
                                    () -> callback.onCaptureError("Back camera capture failed"));
                        }
                    }
                });

                Log.d(TAG, "Back camera capture triggered");

            } catch (Exception e) {
                Log.e(TAG, "Error capturing back camera photo", e);
                if (callback != null) {
                    new Handler(android.os.Looper.getMainLooper()).post(
                            () -> callback.onCaptureError("Capture error: " + e.getMessage()));
                }
            }
        }
    }

    /**
     * Captures a still photo from the front camera.
     * <p>Creates a still capture request using the front camera's CameraDeviceWrapper
     * and the configured front ImageReader surface.</p>
     *
     * @param lastResult the last CaptureResult (used for 3A convergence)
     * @param wrapper    the CameraDeviceWrapper for the front camera
     * @param callback   callback to receive the capture result
     * @throws IllegalArgumentException if wrapper or lastResult is null
     */
    public void captureFrontCamera(CaptureResult lastResult, CameraDeviceWrapper wrapper,
                                   CaptureCallback callback) {
        if (wrapper == null) {
            throw new IllegalArgumentException("CameraDeviceWrapper must not be null");
        }
        if (lastResult == null) {
            throw new IllegalArgumentException("CaptureResult must not be null");
        }

        synchronized (mLock) {
            if (mReleased) {
                Log.e(TAG, "Cannot capture: helper has been released");
                if (callback != null) {
                    callback.onCaptureError("PhotoCaptureHelper has been released");
                }
                return;
            }

            if (mFrontImageReader == null) {
                Log.e(TAG, "Cannot capture front camera: ImageReader not set up");
                if (callback != null) {
                    callback.onCaptureError("Front ImageReader not initialized");
                }
                return;
            }

            try {
                CaptureRequest.Builder builder = wrapper.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                if (builder == null) {
                    Log.e(TAG, "Failed to create still capture request for front camera");
                    if (callback != null) {
                        callback.onCaptureError("Failed to create capture request");
                    }
                    return;
                }

                builder.addTarget(mFrontImageReader.getSurface());
                applyAeSettings(builder, lastResult);

                try {
                    builder.set(CaptureRequest.JPEG_QUALITY, (byte) JPEG_QUALITY);
                } catch (Exception e) {
                    Log.w(TAG, "Could not set JPEG quality", e);
                }

                wrapper.capture(builder, new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(CameraCaptureSession session,
                                                   CaptureRequest request, TotalCaptureResult result) {
                        Log.d(TAG, "Front camera capture completed");
                    }

                    @Override
                    public void onCaptureFailed(CameraCaptureSession session,
                                                CaptureRequest request, CaptureFailure failure) {
                        Log.e(TAG, "Front camera capture failed: " + failure.getReason());
                        if (callback != null) {
                            new Handler(android.os.Looper.getMainLooper()).post(
                                    () -> callback.onCaptureError("Front camera capture failed"));
                        }
                    }
                });

                Log.d(TAG, "Front camera capture triggered");

            } catch (Exception e) {
                Log.e(TAG, "Error capturing front camera photo", e);
                if (callback != null) {
                    new Handler(android.os.Looper.getMainLooper()).post(
                            () -> callback.onCaptureError("Capture error: " + e.getMessage()));
                }
            }
        }
    }

    /**
     * Captures still photos from both cameras simultaneously.
     * <p>Both captures are triggered concurrently using the dual-capture thread pool.
     * The callback is invoked when both captures complete.</p>
     *
     * @param backLastResult  the last CaptureResult from the back camera
     * @param frontLastResult the last CaptureResult from the front camera
     * @param backWrapper     the CameraDeviceWrapper for the back camera
     * @param frontWrapper    the CameraDeviceWrapper for the front camera
     * @param callback        callback to receive the dual capture result
     * @throws IllegalArgumentException if any wrapper or CaptureResult parameter is null
     */
    public void captureDualCameras(CaptureResult backLastResult, CaptureResult frontLastResult,
                                   CameraDeviceWrapper backWrapper, CameraDeviceWrapper frontWrapper,
                                   CaptureCallback callback) {
        if (backWrapper == null || frontWrapper == null) {
            throw new IllegalArgumentException("CameraDeviceWrapper must not be null");
        }
        if (backLastResult == null || frontLastResult == null) {
            throw new IllegalArgumentException("CaptureResult must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }

        Log.d(TAG, "Starting dual camera capture");

        CountDownLatch latch = new CountDownLatch(2);
        String[] results = new String[2]; // [0] = back, [1] = front
        boolean[] errors = new boolean[2];

        // Capture back camera on thread pool
        mDualCaptureExecutor.submit(() -> {
            try {
                CountDownLatch backLatch = new CountDownLatch(1);

                CaptureCallback backCb = new CaptureCallback() {
                    @Override
                    public void onPhotoCaptured(String filePath, boolean isBack) {
                        results[0] = filePath;
                        backLatch.countDown();
                    }

                    @Override
                    public void onDualCaptureComplete(String backFilePath, String frontFilePath) {
                    }

                    @Override
                    public void onCaptureError(String errorMessage) {
                        errors[0] = true;
                        backLatch.countDown();
                    }
                };

                captureBackCamera(backLastResult, backWrapper, backCb);
                backLatch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            } catch (Exception e) {
                Log.e(TAG, "Error in back camera dual capture", e);
                errors[0] = true;
            } finally {
                latch.countDown();
            }
        });

        // Capture front camera on thread pool
        mDualCaptureExecutor.submit(() -> {
            try {
                CountDownLatch frontLatch = new CountDownLatch(1);

                CaptureCallback frontCb = new CaptureCallback() {
                    @Override
                    public void onPhotoCaptured(String filePath, boolean isBack) {
                        results[1] = filePath;
                        frontLatch.countDown();
                    }

                    @Override
                    public void onDualCaptureComplete(String backFilePath, String frontFilePath) {
                    }

                    @Override
                    public void onCaptureError(String errorMessage) {
                        errors[1] = true;
                        frontLatch.countDown();
                    }
                };

                captureFrontCamera(frontLastResult, frontWrapper, frontCb);
                frontLatch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            } catch (Exception e) {
                Log.e(TAG, "Error in front camera dual capture", e);
                errors[1] = true;
            } finally {
                latch.countDown();
            }
        });

        // Wait for both captures and deliver combined result
        mDualCaptureExecutor.submit(() -> {
            try {
                boolean completed = latch.await(CAPTURE_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
                if (!completed) {
                    Log.e(TAG, "Dual camera capture timed out");
                    new Handler(android.os.Looper.getMainLooper()).post(
                            () -> callback.onCaptureError("Dual capture timed out"));
                    return;
                }

                String backPath = errors[0] ? null : results[0];
                String frontPath = errors[1] ? null : results[1];

                Log.d(TAG, "Dual capture complete: back=" + backPath + ", front=" + frontPath);
                new Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onDualCaptureComplete(backPath, frontPath));

            } catch (InterruptedException e) {
                Log.e(TAG, "Dual capture wait interrupted", e);
                Thread.currentThread().interrupt();
                new Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onCaptureError("Capture interrupted"));
            }
        });

        Log.d(TAG, "Dual camera capture initiated");
    }

    // =========================================================================
    // Image saving
    // =========================================================================

    /**
     * Saves an Image from the ImageReader to the DCIM folder.
     * <p>The image is saved as a JPEG file with a timestamp-based filename.
     * EXIF orientation metadata is written based on the camera type.</p>
     *
     * @param image     the Image to save
     * @param cameraTag a tag identifying the camera ("back" or "front")
     * @return the absolute path to the saved file, or null on failure
     */
    public String saveImage(Image image, String cameraTag) {
        if (image == null) {
            Log.e(TAG, "saveImage: image is null");
            return null;
        }

        String filePath = null;
        OutputStream outputStream = null;

        try {
            // Get the JPEG data from the image planes
            Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) {
                Log.e(TAG, "Image has no planes");
                return null;
            }

            ByteBuffer buffer = planes[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            // Generate filename with timestamp
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            String filename = "DUALCAM_" + cameraTag + "_" + timestamp + ".jpg";

            // Save to DCIM/DualCamRecorder folder
            File dcimDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), "DualCamRecorder");

            if (!dcimDir.exists()) {
                boolean created = dcimDir.mkdirs();
                if (!created) {
                    Log.e(TAG, "Failed to create output directory: " + dcimDir.getAbsolutePath());
                    // Fallback to app-specific directory
                    dcimDir = new File(mContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                            "DualCamRecorder");
                    dcimDir.mkdirs();
                }
            }

            File outputFile = new File(dcimDir, filename);
            filePath = outputFile.getAbsolutePath();

            // Write JPEG data
            outputStream = new FileOutputStream(outputFile);
            outputStream.write(bytes);
            outputStream.flush();

            // Set EXIF orientation
            try {
                setExifOrientation(outputFile, "back".equals(cameraTag));
            } catch (Exception e) {
                Log.w(TAG, "Could not set EXIF orientation", e);
            }

            // Add to MediaStore so it appears in gallery
            addToMediaStore(outputFile, bytes.length);

            Log.d(TAG, "Photo saved: " + filePath + " (" + bytes.length + " bytes)");

        } catch (Exception e) {
            Log.e(TAG, "Error saving image", e);
            filePath = null;
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing output stream", e);
                }
            }
        }

        return filePath;
    }

    /**
     * Sets EXIF orientation on a saved JPEG file.
     *
     * @param file   the JPEG file
     * @param isBack true for back camera (normal orientation), false for front (mirrored)
     * @throws IOException if the file cannot be read or written
     */
    private void setExifOrientation(File file, boolean isBack) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());

        if (isBack) {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
        } else {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL));
        }

        exif.setAttribute(ExifInterface.TAG_MAKE, "DualCamRecorder");
        exif.setAttribute(ExifInterface.TAG_MODEL, "Dual Camera Capture");

        exif.saveAttributes();
        Log.d(TAG, "EXIF orientation set for " + file.getName()
                + (isBack ? " (normal)" : " (flipped)"));
    }

    /**
     * Adds a saved photo file to the Android MediaStore so it appears in the gallery.
     *
     * @param file     the photo file to add
     * @param fileSize the file size in bytes
     */
    private void addToMediaStore(File file, long fileSize) {
        if (file == null || !file.exists()) {
            return;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, file.getName());
            values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Images.Media.SIZE, fileSize);
            values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());

            mContext.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            Log.d(TAG, "Photo added to MediaStore: " + file.getName());

        } catch (Exception e) {
            Log.e(TAG, "Error adding photo to MediaStore", e);
        }
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    /**
     * Applies auto-exposure, auto-focus, and auto-white-balance settings from a
     * preview CaptureResult to a still capture request builder.
     *
     * @param builder    the capture request builder to apply settings to
     * @param lastResult the last preview CaptureResult with 3A state
     */
    private void applyAeSettings(CaptureRequest.Builder builder, CaptureResult lastResult) {
        try {
            Integer aeMode = lastResult.get(CaptureResult.CONTROL_AE_MODE);
            if (aeMode != null) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode);
            }

            Integer afMode = lastResult.get(CaptureResult.CONTROL_AF_MODE);
            if (afMode != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, afMode);
            }

            Integer awbMode = lastResult.get(CaptureResult.CONTROL_AWB_MODE);
            if (awbMode != null) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
            }

            Integer flashMode = lastResult.get(CaptureResult.FLASH_MODE);
            if (flashMode != null) {
                builder.set(CaptureRequest.FLASH_MODE, flashMode);
            }

            Long exposureTime = lastResult.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (exposureTime != null) {
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
            }

            Integer sensitivity = lastResult.get(CaptureResult.SENSOR_SENSITIVITY);
            if (sensitivity != null) {
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
            }

            Log.d(TAG, "3A settings applied to capture request");

        } catch (Exception e) {
            Log.w(TAG, "Error applying 3A settings, using defaults", e);
        }
    }

    /**
     * Safely closes an ImageReader.
     *
     * @param reader     the ImageReader to close, or null
     * @param readerName a descriptive name for logging
     */
    private void closeImageReader(ImageReader reader, String readerName) {
        if (reader != null) {
            try {
                reader.close();
                Log.d(TAG, readerName + " ImageReader closed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing " + readerName + " ImageReader", e);
            }
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the back camera ImageReader.
     *
     * @return the ImageReader, or null if not set up
     */
    public ImageReader getBackImageReader() {
        synchronized (mLock) {
            return mBackImageReader;
        }
    }

    /**
     * Returns the front camera ImageReader.
     *
     * @return the ImageReader, or null if not set up
     */
    public ImageReader getFrontImageReader() {
        synchronized (mLock) {
            return mFrontImageReader;
        }
    }

    // =========================================================================
    // Resource cleanup
    // =========================================================================

    /**
     * Releases all resources held by the PhotoCaptureHelper.
     * <p>After calling this method, the helper must NOT be reused.</p>
     */
    public void release() {
        synchronized (mLock) {
            if (mReleased) {
                Log.w(TAG, "release() called but helper is already released");
                return;
            }
            mReleased = true;

            Log.d(TAG, "Releasing PhotoCaptureHelper...");

            closeImageReader(mBackImageReader, "back");
            mBackImageReader = null;

            closeImageReader(mFrontImageReader, "front");
            mFrontImageReader = null;
        }

        // Shutdown executor outside the lock
        if (mDualCaptureExecutor != null) {
            try {
                mDualCaptureExecutor.shutdown();
                if (!mDualCaptureExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    mDualCaptureExecutor.shutdownNow();
                }
                Log.d(TAG, "Dual capture executor shut down");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for executor shutdown", e);
                Thread.currentThread().interrupt();
            }
            mDualCaptureExecutor = null;
        }

        // Stop capture thread
        if (mCaptureThread != null) {
            try {
                mCaptureThread.quitSafely();
                mCaptureThread.join(2000);
                Log.d(TAG, "Capture thread stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for capture thread", e);
                Thread.currentThread().interrupt();
            }
            mCaptureThread = null;
            mCaptureHandler = null;
        }

        Log.d(TAG, "PhotoCaptureHelper fully released");
    }
}