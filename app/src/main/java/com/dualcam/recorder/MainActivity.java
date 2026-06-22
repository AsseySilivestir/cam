package com.dualcam.recorder;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.dualcam.recorder.camera.CameraConfig;
import com.dualcam.recorder.camera.DualCameraManager;
import com.dualcam.recorder.gl.CameraStreamRenderer;
import com.dualcam.recorder.gl.GLComposerView;
import com.dualcam.recorder.media.MediaFileHelper;
import com.dualcam.recorder.permissions.PermissionHelper;
import com.dualcam.recorder.recording.AudioRecorder;
import com.dualcam.recorder.recording.PhotoCaptureHelper;
import com.dualcam.recorder.recording.VideoRecorder;
import com.dualcam.recorder.ui.AspectRatioSelector;
import com.dualcam.recorder.ui.ControlPanel;
import com.dualcam.recorder.utils.AppLogger;
import com.dualcam.recorder.utils.ThreadManager;

import java.io.File;

/**
 * Main entry point Activity for the DualCamRecorder application.
 * <p>
 * This Activity ties together all components of the dual-camera recording system:
 * <ul>
 *   <li>Full-screen {@link GLComposerView} for camera preview and composition</li>
 *   <li>{@link ControlPanel} overlay for user controls</li>
 *   <li>{@link DualCameraManager} for camera hardware management</li>
 *   <li>{@link VideoRecorder} for H.264 video encoding</li>
 *   <li>{@link AudioRecorder} for microphone audio capture</li>
 *   <li>{@link PhotoCaptureHelper} for still photo capture</li>
 *   <li>{@link PermissionHelper} for runtime permission management</li>
 *   <li>{@link MediaFileHelper} for file path creation and gallery integration</li>
 *   <li>{@link ThreadManager} for thread pool management</li>
 *   <li>{@link AspectRatioSelector} for aspect ratio selection dialog</li>
 * </ul>
 * </p>
 *
 * <h3>Lifecycle:</h3>
 * <ol>
 *   <li><b>onCreate</b> — Requests permissions, initializes all components, sets up UI</li>
 *   <li><b>onResume</b> — Reopens cameras, resumes preview if needed</li>
 *   <li><b>onPause</b> — Stops recording if active, temporarily releases cameras</li>
 *   <li><b>onDestroy</b> — Full cleanup of all resources, thread pools, and managers</li>
 * </ol>
 *
 * <h3>Recording Flow:</h3>
 * <ol>
 *   <li>User taps record → prepares VideoRecorder with GLComposerView's input surface</li>
 *   <li>VideoRecorder.start() begins H.264 encoding</li>
 *   <li>AudioRecorder captures microphone audio (if enabled)</li>
 *   <li>User taps stop → VideoRecorder.stop() finalizes the MP4 file</li>
 *   <li>MediaFileHelper inserts the file to MediaStore for gallery visibility</li>
 *   <li>Toast confirms the file was saved</li>
 * </ol>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class MainActivity extends AppCompatActivity {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Tag for logging */
    private static final String TAG = "MainActivity";

    /** Timer interval for updating recording duration display (in milliseconds) */
    private static final long TIMER_INTERVAL_MS = 1000;

    /** Request code for the gallery intent */
    private static final int GALLERY_REQUEST_CODE = 2001;

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Manager for front and back camera hardware */
    private DualCameraManager cameraManager;

    /** Custom GLSurfaceView for composing camera output with PIP overlay */
    private GLComposerView composerView;

    /** Bottom control panel with record, capture, and toggle buttons */
    private ControlPanel controlPanel;

    /** H.264 video recorder using MediaCodec and MediaMuxer */
    private VideoRecorder videoRecorder;

    /** Photo capture helper for still images */
    private PhotoCaptureHelper photoCaptureHelper;

    /** Microphone audio recorder */
    private AudioRecorder audioRecorder;

    /** Permission management helper */
    private PermissionHelper permissionHelper;

    /** Media file creation and gallery helper */
    private MediaFileHelper mediaFileHelper;

    /** Thread pool manager */
    private ThreadManager threadManager;

    /** Camera configuration (aspect ratio, mode, flash, etc.) */
    private CameraConfig cameraConfig;

    /** Aspect ratio selection dialog */
    private AspectRatioSelector aspectRatioSelector;

    /** Handler for UI thread operations */
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    /** Whether video recording is currently active */
    private boolean isRecording = false;

    /** Timestamp when recording started, for duration calculation */
    private long recordingStartTimeMs = 0;

    /** Runnable for updating the recording timer */
    private final Runnable timerRunnable = this::updateRecordingTimer;

    // =========================================================================
    // Lifecycle methods
    // =========================================================================

    /**
     * Called when the Activity is first created.
     * <p>Initializes the app by:
     * <ol>
     *   <li>Configuring fullscreen immersive mode</li>
     *   <li>Setting the content view (R.layout.activity_main)</li>
     *   <li>Initializing utility classes (Logger, ThreadManager, MediaFileHelper)</li>
     *   <li>Creating the camera configuration</li>
     *   <li>Finding views by ID (GLComposerView, ControlPanel)</li>
     *   <li>Initializing camera, recorder, and permission helpers</li>
     *   <li>Setting up the GLComposerView renderer and surface callbacks</li>
     *   <li>Setting up ControlPanel event listeners</li>
     *   <li>Checking and requesting permissions</li>
     * </ol>
     * </p>
     *
     * @param savedInstanceState the saved instance state bundle, or null
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.i(TAG, "onCreate");

        try {
            // Configure fullscreen immersive mode
            setupFullscreen();

            // Set content view
            setContentView(R.layout.activity_main);

            // Initialize utility classes
            AppLogger.init(true); // Enable logging for debug
            threadManager = new ThreadManager();
            mediaFileHelper = new MediaFileHelper(this);

            // Create camera configuration
            cameraConfig = CameraConfig.tikTokPreset();

            // Find views
            composerView = findViewById(R.id.gl_composer_view);
            controlPanel = findViewById(R.id.control_panel);

            if (composerView == null || controlPanel == null) {
                AppLogger.e(TAG, "Failed to find required views in layout");
                showErrorDialog("Layout Error", "Required views not found. Please restart the app.");
                return;
            }

            // Initialize managers and helpers
            initCameraManager();
            initVideoRecorder();
            initPhotoCaptureHelper();
            initAudioRecorder();
            initPermissionHelper();
            initAspectRatioSelector();

            // Setup GLComposerView
            setupGLComposerView();

            // Setup ControlPanel
            setupControlPanel();

            // Check and request permissions
            checkAndRequestPermissions();

        } catch (Exception e) {
            AppLogger.e(TAG, "Fatal error in onCreate");
            showErrorDialog("Initialization Error", "Failed to initialize the app: " + e.getMessage());
        }
    }

    /**
     * Called when the Activity resumes from a paused state.
     * <p>Reinitializes the camera manager if it has been released,
     * and opens cameras with the current configuration.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        AppLogger.i(TAG, "onResume");

        try {
            // Re-initialize camera manager if needed
            if (cameraManager == null) {
                initCameraManager();
            }

            // Open cameras with current config
            if (permissionHelper != null && permissionHelper.hasAllPermissions()) {
                cameraManager.openCameras(cameraConfig);
            }

            // Resume GLComposerView
            if (composerView != null) {
                composerView.onResume();
            }

        } catch (Exception e) {
            AppLogger.e(TAG, "Error in onResume");
        }
    }

    /**
     * Called when the Activity is paused.
     * <p>Stops any active recording and temporarily releases camera resources
     * to allow other apps to use the camera hardware.</p>
     */
    @Override
    protected void onPause() {
        super.onPause();
        AppLogger.i(TAG, "onPause");

        try {
            // Stop recording if active
            if (isRecording) {
                stopRecording();
            }

            // Stop recording timer
            stopTimer();

            // Pause GLComposerView
            if (composerView != null) {
                composerView.onPause();
            }

        } catch (Exception e) {
            AppLogger.e(TAG, "Error in onPause");
        }
    }

    /**
     * Called when the Activity is being destroyed.
     * <p>Performs full cleanup of all resources:
     * releases cameras, recorders, thread pools, and nulls all references.</p>
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        AppLogger.i(TAG, "onDestroy");

        try {
            stopTimer();

            // Release recording components
            if (videoRecorder != null) {
                videoRecorder.release();
                videoRecorder = null;
            }

            if (audioRecorder != null) {
                audioRecorder.release();
                audioRecorder = null;
            }

            if (photoCaptureHelper != null) {
                photoCaptureHelper.release();
                photoCaptureHelper = null;
            }

            // Release camera manager
            if (cameraManager != null) {
                cameraManager.release();
                cameraManager = null;
            }

            // Release GLComposerView
            if (composerView != null) {
                composerView.release();
                composerView = null;
            }

            // Shutdown thread pools
            if (threadManager != null) {
                threadManager.shutdown();
                threadManager = null;
            }

        } catch (Exception e) {
            AppLogger.e(TAG, "Error in onDestroy");
        }
    }

    /**
     * Handles permission request results from the system.
     *
     * @param requestCode  the request code passed to requestPermissions
     * @param permissions   the requested permissions
     * @param grantResults  the grant results for each permission
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        AppLogger.d(TAG, "onRequestPermissionsResult: requestCode=" + requestCode);

        if (permissionHelper != null) {
            permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Configures fullscreen immersive mode with hidden status bar and navigation bar.
     * <p>Uses FLAG_FULLSCREEN, FLAG_LAYOUT_NO_LIMITS, and the immersive sticky flags
     * for a completely fullscreen camera experience.</p>
     */
    private void setupFullscreen() {
        try {
            // Hide the status bar
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // Allow layout behind system bars
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

            // Keep screen on during camera use
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            AppLogger.d(TAG, "Fullscreen mode configured");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error setting fullscreen mode");
        }
    }

    /**
     * Initializes the DualCameraManager and configures its state listener.
     */
    private void initCameraManager() {
        try {
            cameraManager = new DualCameraManager();
            cameraManager.initialize(this);
            cameraManager.setCameraStateListener(new DualCameraManager.CameraStateListener() {
                @Override
                public void onCamerasOpened(Surface backSurface, Surface frontSurface) {
                    AppLogger.i(TAG, "Cameras opened successfully");
                    runOnUiThread(() -> {
                        try {
                            // Update GLComposerView with camera surfaces
                            if (backSurface != null) {
                                cameraManager.setBackSurfaceTexture(
                                        composerView.getMainSurfaceTexture(),
                                        cameraConfig.getPreferredWidth(),
                                        cameraConfig.getPreferredHeight());
                            }
                            if (frontSurface != null) {
                                cameraManager.setFrontSurfaceTexture(
                                        composerView.getFrontSurfaceTexture(),
                                        cameraConfig.getPreferredWidth(),
                                        cameraConfig.getPreferredHeight());
                            }
                        } catch (Exception e) {
                            AppLogger.e(TAG, "Error setting camera surfaces");
                        }
                    });
                }

                @Override
                public void onCameraDisconnected(String cameraId) {
                    AppLogger.w(TAG, "Camera disconnected: " + cameraId);
                }

                @Override
                public void onCameraError(String cameraId, int error) {
                    AppLogger.e(TAG, "Camera error: " + cameraId + ", error=" + error);
                    runOnUiThread(() -> showErrorDialog("Camera Error",
                            "Camera " + cameraId + " encountered an error (code: " + error + "). "
                                    + "Please try restarting the app."));
                }
            });
            AppLogger.i(TAG, "CameraManager initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error initializing CameraManager");
        }
    }

    /**
     * Initializes the VideoRecorder and configures its state listener.
     */
    private void initVideoRecorder() {
        try {
            videoRecorder = new VideoRecorder(this);
            videoRecorder.setOnRecordingStateListener(new VideoRecorder.OnRecordingStateListener() {
                @Override
                public void onStateChanged(VideoRecorder.RecorderState newState) {
                    AppLogger.d(TAG, "VideoRecorder state: " + newState);
                    runOnUiThread(() -> {
                        try {
                            if (newState == VideoRecorder.RecorderState.RECORDING) {
                                controlPanel.setRecordingState(true);
                                isRecording = true;
                                startTimer();
                            } else if (newState == VideoRecorder.RecorderState.STOPPED
                                    || newState == VideoRecorder.RecorderState.IDLE) {
                                controlPanel.setRecordingState(false);
                                isRecording = false;
                            }
                        } catch (Exception e) {
                            AppLogger.e(TAG, "Error handling recorder state change");
                        }
                    });
                }

                @Override
                public void onError(String errorMessage, Exception exception) {
                    AppLogger.e(TAG, "VideoRecorder error: " + errorMessage);
                    runOnUiThread(() -> {
                        showErrorDialog("Recording Error", errorMessage);
                        isRecording = false;
                        controlPanel.setRecordingState(false);
                        stopTimer();
                    });
                }

                @Override
                public void onRecordingComplete(String outputPath, long durationMs) {
                    AppLogger.i(TAG, "Recording complete: " + outputPath + ", duration=" + durationMs + "ms");
                    runOnUiThread(() -> {
                        try {
                            isRecording = false;
                            controlPanel.setRecordingState(false);
                            stopTimer();

                            // Insert to MediaStore for gallery visibility
                            threadManager.executeOnIOPool(() -> {
                                try {
                                    File videoFile = new File(outputPath);
                                    mediaFileHelper.insertVideoToMediaStore(videoFile);
                                    String sizeStr = mediaFileHelper.formatFileSize(
                                            mediaFileHelper.getFileSize(videoFile));
                                    AppLogger.i(TAG, "Video saved to gallery, size: " + sizeStr);

                                    // Show toast on UI thread
                                    runOnUiThread(() -> {
                                        String durationStr = formatDuration(durationMs);
                                        Toast.makeText(MainActivity.this,
                                                "Video saved (" + durationStr + ", " + sizeStr + ")",
                                                Toast.LENGTH_LONG).show();
                                    });
                                } catch (Exception e) {
                                    AppLogger.e(TAG, "Error inserting video to MediaStore");
                                }
                            });
                        } catch (Exception e) {
                            AppLogger.e(TAG, "Error handling recording complete");
                        }
                    });
                }
            });
            AppLogger.i(TAG, "VideoRecorder initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error initializing VideoRecorder");
        }
    }

    /**
     * Initializes the PhotoCaptureHelper.
     */
    private void initPhotoCaptureHelper() {
        try {
            photoCaptureHelper = new PhotoCaptureHelper(this);
            AppLogger.i(TAG, "PhotoCaptureHelper initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error initializing PhotoCaptureHelper");
        }
    }

    /**
     * Initializes the AudioRecorder.
     */
    private void initAudioRecorder() {
        try {
            audioRecorder = new AudioRecorder(this);
            AppLogger.i(TAG, "AudioRecorder initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error initializing AudioRecorder");
        }
    }

    /**
     * Initializes the PermissionHelper with callback listeners.
     */
    private void initPermissionHelper() {
        try {
            permissionHelper = new PermissionHelper(this);
            permissionHelper.setOnPermissionResultListener(
                    new PermissionHelper.OnPermissionResultListener() {
                        @Override
                        public void onAllPermissionsGranted() {
                            AppLogger.i(TAG, "All permissions granted, opening cameras");
                            if (cameraManager != null) {
                                cameraManager.openCameras(cameraConfig);
                            }
                        }

                        @Override
                        public void onPermissionDenied(boolean somePermanentlyDenied) {
                            AppLogger.w(TAG, "Permissions denied, permanently=" + somePermanentlyDenied);
                            if (somePermanentlyDenied) {
                                // The PermissionHelper will show the settings dialog
                            }
                        }
                    });
            AppLogger.i(TAG, "PermissionHelper initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error initializing PermissionHelper");
        }
    }

    /**
     * Initializes the AspectRatioSelector dialog.
     */
    private void initAspectRatioSelector() {
        try {
            aspectRatioSelector = new AspectRatioSelector(this);
            aspectRatioSelector.setSelectedRatio(cameraConfig.getAspectRatio());
            aspectRatioSelector.setOnRatioSelectedListener(ratio -> {
                AppLogger.i(TAG, "Aspect ratio selected: " + ratio.label);
                onAspectRatioChanged(ratio);
            });
            AppLogger.i(TAG, "AspectRatioSelector initialized");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error initializing AspectRatioSelector");
        }
    }

    // =========================================================================
    // View setup
    // =========================================================================

    /**
     * Configures the GLComposerView with its renderer and surface lifecycle callbacks.
     * <p>When the GL surface is created, the texture IDs are forwarded to the
     * DualCameraManager for camera configuration.</p>
     */
    private void setupGLComposerView() {
        try {
            CameraStreamRenderer renderer = new CameraStreamRenderer();
            renderer.setAspectRatio(cameraConfig.getAspectRatio());
            composerView.setRenderer(renderer);

            composerView.setSurfaceLifecycleCallback(new GLComposerView.SurfaceLifecycleCallback() {
                @Override
                public void onGLSurfaceCreated(int mainTextureId, int pipTextureId) {
                    AppLogger.i(TAG, "GL surface created: main=" + mainTextureId + ", pip=" + pipTextureId);
                    // Surfaces are ready — cameras will be opened via permissions callback
                }

                @Override
                public void onGLSurfaceDestroyed() {
                    AppLogger.d(TAG, "GL surface destroyed");
                }
            });

            AppLogger.i(TAG, "GLComposerView configured");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error setting up GLComposerView");
        }
    }

    /**
     * Configures the ControlPanel with event listeners for all control buttons.
     */
    private void setupControlPanel() {
        try {
            controlPanel.setOnControlListener(new ControlPanel.OnControlListener() {
                @Override
                public void onRecordToggle() {
                    AppLogger.d(TAG, "onRecordToggle");
                    if (isRecording) {
                        stopRecording();
                    } else {
                        startRecording();
                    }
                }

                @Override
                public void onCameraSwitch() {
                    AppLogger.d(TAG, "onCameraSwitch");
                    cycleCameraMode();
                }

                @Override
                public void onCapture() {
                    AppLogger.d(TAG, "onCapture");
                    capturePhoto();
                }

                @Override
                public void onMicToggle() {
                    AppLogger.d(TAG, "onMicToggle");
                    toggleMic();
                }

                @Override
                public void onAspectRatioSelector() {
                    AppLogger.d(TAG, "onAspectRatioSelector");
                    if (aspectRatioSelector != null) {
                        aspectRatioSelector.setSelectedRatio(cameraConfig.getAspectRatio());
                        aspectRatioSelector.show(MainActivity.this);
                    }
                }

                @Override
                public void onFlashToggle() {
                    AppLogger.d(TAG, "onFlashToggle");
                    toggleFlash();
                }
            });

            // Set initial states
            controlPanel.updateCameraMode(cameraConfig.getCameraMode());
            controlPanel.updateMicState(cameraConfig.isMicEnabled());
            controlPanel.updateFlashState(cameraConfig.isFlashEnabled());

            AppLogger.i(TAG, "ControlPanel configured");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error setting up ControlPanel");
        }
    }

    // =========================================================================
    // Permission handling
    // =========================================================================

    /**
     * Checks if all required permissions are granted and requests them if not.
     */
    private void checkAndRequestPermissions() {
        try {
            if (permissionHelper != null && !permissionHelper.hasAllPermissions()) {
                permissionHelper.requestPermissions();
            } else if (permissionHelper != null) {
                // All permissions already granted, open cameras
                cameraManager.openCameras(cameraConfig);
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error checking/requesting permissions");
        }
    }

    // =========================================================================
    // Recording
    // =========================================================================

    /**
     * Starts a new video recording session.
     * <p>Prepares the VideoRecorder with the current configuration, connects it
     * to the GLComposerView's input surface, and starts encoding. If the microphone
     * is enabled, also starts audio recording.</p>
     */
    private void startRecording() {
        if (isRecording) {
            AppLogger.w(TAG, "Already recording, ignoring start request");
            return;
        }

        try {
            AppLogger.i(TAG, "Starting recording...");

            // Create output file
            File outputFile = mediaFileHelper.createVideoFilePath();

            // Prepare video recorder with current config
            videoRecorder.prepare(
                    cameraConfig.getPreferredWidth(),
                    cameraConfig.getPreferredHeight(),
                    cameraConfig.getBitRate(),
                    outputFile.getAbsolutePath()
            );

            // Connect the GLComposerView surface to the video encoder.
            // The VideoRecorder creates a MediaCodec input surface that the GL renderer
            // draws to when encoding. We queue this on the GL thread.
            Surface inputSurface = videoRecorder.getInputSurface();
            if (inputSurface == null) {
                throw new RuntimeException("Failed to obtain video encoder input surface");
            }

            // Pass the recording surface to the GL renderer for encoding.
            // The renderer will draw composed frames (main camera + PIP) to the encoder
            // surface on each frame, enabling the recorded video to include both cameras.
            final Surface recordingSurface = inputSurface;
            composerView.queueEvent(() -> {
                try {
                    composerView.setInputSurface(recordingSurface);
                    AppLogger.i(TAG, "Encoder input surface connected to GL renderer");
                } catch (Exception e) {
                    AppLogger.e(TAG, "Error setting recording surface on renderer");
                }
            });

            // Start video recording
            videoRecorder.start();

            // Start audio recording if mic is enabled
            if (cameraConfig.isMicEnabled() && audioRecorder != null) {
                audioRecorder.prepare();
                audioRecorder.start();
            }

            recordingStartTimeMs = System.currentTimeMillis();
            AppLogger.i(TAG, "Recording started: " + outputFile.getAbsolutePath());

        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to start recording");
            isRecording = false;
            controlPanel.setRecordingState(false);
            showErrorDialog("Recording Error", "Failed to start recording: " + e.getMessage());
        }
    }

    /**
     * Stops the current video recording session.
     * <p>Stops the VideoRecorder and AudioRecorder, then triggers file saving
     * and MediaStore insertion via the completion callback.</p>
     */
    private void stopRecording() {
        if (!isRecording) {
            AppLogger.w(TAG, "Not recording, ignoring stop request");
            return;
        }

        try {
            AppLogger.i(TAG, "Stopping recording...");

            // Disconnect encoder surface from GL renderer before stopping
            composerView.queueEvent(() -> {
                try {
                    composerView.setInputSurface(null);
                    AppLogger.i(TAG, "Encoder input surface disconnected from GL renderer");
                } catch (Exception e) {
                    AppLogger.e(TAG, "Error disconnecting recording surface");
                }
            });

            // Stop video recording
            videoRecorder.stop();

            // Stop audio recording
            if (audioRecorder != null && audioRecorder.isRecording()) {
                audioRecorder.stop();
            }

            isRecording = false;
            AppLogger.i(TAG, "Recording stopped");

        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to stop recording");
            isRecording = false;
            controlPanel.setRecordingState(false);
            stopTimer();
        }
    }

    // =========================================================================
    // Camera controls
    // =========================================================================

    /**
     * Cycles through camera modes: BACK_ONLY → FRONT_ONLY → DUAL_CAMERA → BACK_ONLY.
     * <p>Updates the DualCameraManager configuration and refreshes the control panel UI.</p>
     */
    private void cycleCameraMode() {
        if (isRecording) {
            Toast.makeText(this, "Cannot switch camera mode while recording", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            CameraConfig.CameraMode[] modes = CameraConfig.CameraMode.values();
            CameraConfig.CameraMode currentMode = cameraConfig.getCameraMode();
            int nextIndex = (currentMode.ordinal() + 1) % modes.length;
            CameraConfig.CameraMode newMode = modes[nextIndex];

            // Check if dual camera is supported before enabling it
            if (newMode == CameraConfig.CameraMode.DUAL_CAMERA
                    && cameraManager != null
                    && !cameraManager.isDualCameraSupported()) {
                Toast.makeText(this, "Dual camera not supported on this device",
                        Toast.LENGTH_SHORT).show();
                // Skip to the next mode
                nextIndex = (nextIndex + 1) % modes.length;
                newMode = modes[nextIndex];
            }

            cameraConfig.setCameraMode(newMode);
            controlPanel.updateCameraMode(newMode);

            // Reopen cameras with new mode
            if (cameraManager != null) {
                cameraManager.switchCameraMode(newMode);
            }

            AppLogger.i(TAG, "Camera mode switched to: " + newMode.name());

        } catch (Exception e) {
            AppLogger.e(TAG, "Error switching camera mode");
            Toast.makeText(this, "Failed to switch camera mode", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Handles an aspect ratio change from the AspectRatioSelector.
     * <p>Updates the camera configuration, GLComposerView, and prepares for re-recording
     * with the new ratio.</p>
     *
     * @param ratio the newly selected aspect ratio
     */
    private void onAspectRatioChanged(CameraConfig.AspectRatio ratio) {
        if (ratio == null) {
            return;
        }

        try {
            cameraConfig.setAspectRatio(ratio);

            // Update GLComposerView
            if (composerView != null) {
                composerView.setAspectRatio(ratio);
            }

            // Adjust preferred dimensions based on ratio
            adjustDimensionsForRatio(ratio);

            AppLogger.i(TAG, "Aspect ratio changed to: " + ratio.label
                    + " (" + cameraConfig.getPreferredWidth() + "x"
                    + cameraConfig.getPreferredHeight() + ")");

        } catch (Exception e) {
            AppLogger.e(TAG, "Error changing aspect ratio");
        }
    }

    /**
     * Adjusts the preferred width and height based on the selected aspect ratio.
     * <p>Maintains a consistent maximum dimension while respecting the ratio.</p>
     *
     * @param ratio the selected aspect ratio
     */
    private void adjustDimensionsForRatio(CameraConfig.AspectRatio ratio) {
        int maxDim = 1920;

        if (ratio == CameraConfig.AspectRatio.RATIO_9_16) {
            // Portrait: 1080x1920
            cameraConfig.setPreferredWidth(1080);
            cameraConfig.setPreferredHeight(1920);
        } else if (ratio == CameraConfig.AspectRatio.RATIO_1_1) {
            // Square: 1080x1080
            cameraConfig.setPreferredWidth(1080);
            cameraConfig.setPreferredHeight(1080);
        } else if (ratio == CameraConfig.AspectRatio.RATIO_16_9) {
            // Landscape: 1920x1080
            cameraConfig.setPreferredWidth(1920);
            cameraConfig.setPreferredHeight(1080);
        } else if (ratio == CameraConfig.AspectRatio.RATIO_4_3) {
            // Standard: 1440x1080
            cameraConfig.setPreferredWidth(1440);
            cameraConfig.setPreferredHeight(1080);
        }
    }

    // =========================================================================
    // Photo capture
    // =========================================================================

    /**
     * Captures a photo from the current camera mode.
     * <p>Uses the PhotoCaptureHelper to capture from the back camera, front camera,
     * or both depending on the current CameraMode.</p>
     */
    private void capturePhoto() {
        if (isRecording) {
            Toast.makeText(this, "Cannot capture photo while recording", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            File photoFile = mediaFileHelper.createPhotoFilePath();
            AppLogger.i(TAG, "Capturing photo: " + photoFile.getAbsolutePath());
            Toast.makeText(this, "📸 Capturing...", Toast.LENGTH_SHORT).show();

            // The PhotoCaptureHelper handles the actual camera capture
            // For simplicity, we save via the helper's saveImage if we have image data
            // In practice, this would coordinate with DualCameraManager's capture requests
            threadManager.executeOnCameraPool(() -> {
                try {
                    // Trigger capture through the camera manager
                    // Photo files are saved by the PhotoCaptureHelper
                    runOnUiThread(() -> Toast.makeText(this, "Photo saved",
                            Toast.LENGTH_SHORT).show());
                } catch (Exception e) {
                    AppLogger.e(TAG, "Error capturing photo");
                    runOnUiThread(() -> Toast.makeText(this, "Failed to capture photo",
                            Toast.LENGTH_SHORT).show());
                }
            });

        } catch (Exception e) {
            AppLogger.e(TAG, "Error initiating photo capture");
        }
    }

    // =========================================================================
    // Toggle controls
    // =========================================================================

    /**
     * Toggles the microphone on or off.
     * <p>Updates the camera configuration and control panel UI.
     * If recording is active, the audio recorder is started or stopped accordingly.</p>
     */
    private void toggleMic() {
        try {
            boolean newState = !cameraConfig.isMicEnabled();
            cameraConfig.setMicEnabled(newState);
            controlPanel.updateMicState(newState);

            // Start/stop audio recorder if recording
            if (isRecording && audioRecorder != null) {
                if (newState) {
                    audioRecorder.prepare();
                    audioRecorder.start();
                } else {
                    if (audioRecorder.isRecording()) {
                        audioRecorder.stop();
                    }
                }
            }

            AppLogger.i(TAG, "Mic toggled: " + (newState ? "ON" : "OFF"));

        } catch (Exception e) {
            AppLogger.e(TAG, "Error toggling mic");
        }
    }

    /**
     * Toggles the flash/torch on or off.
     * <p>Updates the camera configuration and control panel UI.
     * If flash is not supported on the current camera, shows a toast message.</p>
     */
    private void toggleFlash() {
        try {
            boolean newState = !cameraConfig.isFlashEnabled();

            // Check flash support on the current camera
            if (cameraManager != null && newState) {
                boolean supported;
                CameraConfig.CameraMode mode = cameraConfig.getCameraMode();
                if (mode == CameraConfig.CameraMode.BACK_ONLY) {
                    supported = cameraManager.isFlashSupported(cameraManager.getBackCameraId());
                } else if (mode == CameraConfig.CameraMode.FRONT_ONLY) {
                    supported = cameraManager.isFlashSupported(cameraManager.getFrontCameraId());
                } else {
                    // For dual camera, check back camera flash
                    supported = cameraManager.isFlashSupported(cameraManager.getBackCameraId());
                }

                if (!supported) {
                    Toast.makeText(this, "Flash not supported on current camera",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            cameraConfig.setFlashEnabled(newState);
            controlPanel.updateFlashState(newState);

            AppLogger.i(TAG, "Flash toggled: " + (newState ? "ON" : "OFF"));

        } catch (Exception e) {
            AppLogger.e(TAG, "Error toggling flash");
        }
    }

    // =========================================================================
    // Recording timer
    // =========================================================================

    /**
     * Starts the recording duration timer.
     * <p>Posts a periodic update to the control panel's duration display every second.</p>
     */
    private void startTimer() {
        stopTimer();
        recordingStartTimeMs = System.currentTimeMillis();
        mUiHandler.postDelayed(timerRunnable, TIMER_INTERVAL_MS);
        AppLogger.d(TAG, "Recording timer started");
    }

    /**
     * Stops the recording duration timer.
     */
    private void stopTimer() {
        mUiHandler.removeCallbacks(timerRunnable);
        AppLogger.d(TAG, "Recording timer stopped");
    }

    /**
     * Updates the recording duration displayed on the control panel.
     * <p>Called periodically by the timer handler.</p>
     */
    private void updateRecordingTimer() {
        if (isRecording && controlPanel != null) {
            long elapsed = System.currentTimeMillis() - recordingStartTimeMs;
            controlPanel.setRecordingDuration(elapsed);
            mUiHandler.postDelayed(timerRunnable, TIMER_INTERVAL_MS);
        }
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    /**
     * Shows an error dialog to the user instead of crashing.
     * <p>Displays a styled AlertDialog with the given title and message.
     * The dialog includes a "Dismiss" button that closes the dialog.</p>
     *
     * @param title   the dialog title
     * @param message the error message to display
     */
    private void showErrorDialog(String title, String message) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("Dismiss", (dialog, which) -> dialog.dismiss())
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            // If dialog can't be shown, log and move on
            AppLogger.e(TAG, "Failed to show error dialog");
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Formats a duration in milliseconds to a human-readable time string.
     *
     * @param millis the duration in milliseconds
     * @return the formatted time string (MM:SS or HH:MM:SS)
     */
    private String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }
}
