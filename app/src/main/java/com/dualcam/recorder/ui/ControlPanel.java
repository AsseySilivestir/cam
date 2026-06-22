package com.dualcam.recorder.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.dualcam.recorder.camera.CameraConfig;
import com.dualcam.recorder.utils.AppLogger;

/**
 * A custom control panel view for the bottom of the camera recording screen.
 * <p>
 * Provides a semi-transparent dark overlay containing all camera controls:
 * <ul>
 *   <li><b>Record Button</b> — Large red circle at center, pulses when recording</li>
 *   <li><b>Camera Switch</b> — Toggles between BACK_ONLY, FRONT_ONLY, DUAL_CAMERA modes</li>
 *   <li><b>Capture (Photo) Button</b> — Takes photo(s) based on current camera mode</li>
 *   <li><b>Mic Toggle</b> — Enables or disables microphone recording</li>
 *   <li><b>Aspect Ratio Selector</b> — Opens the AspectRatioSelector dialog</li>
 *   <li><b>Flash Toggle</b> — Enables or disables the flash/torch</li>
 *   <li><b>Duration Display</b> — Shows recording elapsed time</li>
 * </ul>
 * </p>
 *
 * <h3>Layout:</h3>
 * <pre>
 * ┌─────────────────────────────────────────────────┐
 * │  [Flash] [Ratio]  [Record ●]  [Camera] [Photo]  │
 * │                    00:00:23                      │
 * └─────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>Design:</h3>
 * <ul>
 *   <li>Semi-transparent dark background (90% black)</li>
 *   <li>Record button: 64dp red circle with pulse animation during recording</li>
 *   <li>Secondary buttons: 44dp circles with icon content</li>
 *   <li>Clean, minimalistic, modern design</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * ControlPanel panel = new ControlPanel(context);
 * panel.setOnControlListener(new ControlPanel.OnControlListener() {
 *     {@literal @}Override public void onRecordToggle() { startStopRecording(); }
 *     {@literal @}Override public void onCameraSwitch() { cycleCameraMode(); }
 *     // ... other callbacks
 * });
 * panel.setRecordingState(true);
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class ControlPanel extends LinearLayout {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Tag for logging */
    private static final String TAG = "ControlPanel";

    /** Semi-transparent dark background color (90% black) */
    private static final int BACKGROUND_COLOR = 0xE6000000;

    /** Record button color (red) when not recording */
    private static final int RECORD_IDLE_COLOR = 0xFFE53935;

    /** Record button color when actively recording */
    private static final int RECORD_ACTIVE_COLOR = 0xFFC62828;

    /** Record button background ring color */
    private static final int RECORD_RING_COLOR = 0x33FFFFFF;

    /** Secondary button icon color (white) */
    private static final int ICON_COLOR = 0xFFFFFFFF;

    /** Secondary button icon color when active (accent) */
    private static final int ICON_ACTIVE_COLOR = 0xFFE8503A;

    /** Button disabled overlay color */
    private static final int ICON_DISABLED_COLOR = 0x55FFFFFF;

    /** Duration text color */
    private static final int DURATION_TEXT_COLOR = 0xFFFFFFFF;

    // Dimensions in dp
    private static final int RECORD_BUTTON_SIZE_DP = 72;
    private static final int SECONDARY_BUTTON_SIZE_DP = 48;
    private static final int BUTTON_SPACING_DP = 16;
    private static final int PANEL_PADDING_VERTICAL_DP = 20;
    private static final int PANEL_PADDING_HORIZONTAL_DP = 24;
    private static final int DURATION_TOP_MARGIN_DP = 8;
    private static final int RECORD_RING_EXTRA_DP = 6;

    // =========================================================================
    // Callback interface
    // =========================================================================

    /**
     * Callback interface for receiving control panel interaction events.
     */
    public interface OnControlListener {

        /**
         * Called when the record button is tapped to start or stop recording.
         */
        void onRecordToggle();

        /**
         * Called when the camera switch button is tapped.
         * <p>Caller should cycle through camera modes.</p>
         */
        void onCameraSwitch();

        /**
         * Called when the capture (photo) button is tapped.
         */
        void onCapture();

        /**
         * Called when the microphone toggle button is tapped.
         */
        void onMicToggle();

        /**
         * Called when the aspect ratio selector button is tapped.
         */
        void onAspectRatioSelector();

        /**
         * Called when the flash toggle button is tapped.
         */
        void onFlashToggle();
    }

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Callback listener for control events */
    private OnControlListener mListener;

    /** Record button view */
    private View mRecordButton;

    /** Record button outer ring */
    private GradientDrawable mRecordRingDrawable;

    /** Record button inner circle */
    private GradientDrawable mRecordCircleDrawable;

    /** Camera switch button */
    private ImageView mCameraSwitchButton;

    /** Capture (photo) button */
    private ImageView mCaptureButton;

    /** Mic toggle button */
    private ImageView mMicButton;

    /** Aspect ratio selector button */
    private ImageView mRatioButton;

    /** Flash toggle button */
    private ImageView mFlashButton;

    /** Recording duration text view */
    private TextView mDurationText;

    /** Whether recording is currently active */
    private boolean mIsRecording = false;

    /** Pulse animation for the record button */
    private ValueAnimator mPulseAnimator;

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a new ControlPanel with programmatic context.
     *
     * @param context the activity or application context
     */
    public ControlPanel(@NonNull Context context) {
        super(context);
        init(context);
    }

    /**
     * Creates a new ControlPanel for inflation from XML layout.
     *
     * @param context the activity or application context
     * @param attrs   the XML attribute set
     */
    public ControlPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    /**
     * Creates a new ControlPanel for inflation from XML layout with a default style.
     *
     * @param context      the activity or application context
     * @param attrs        the XML attribute set
     * @param defStyleAttr the default style attribute
     */
    public ControlPanel(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Common initialization logic called from all constructors.
     * <p>Sets up the layout orientation, background, and creates all child views.</p>
     *
     * @param context the activity or application context
     */
    private void init(Context context) {
        AppLogger.d(TAG, "Initializing ControlPanel");

        setOrientation(LinearLayout.VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackgroundColor(BACKGROUND_COLOR);
        int pv = dpToPx(PANEL_PADDING_VERTICAL_DP);
        int ph = dpToPx(PANEL_PADDING_HORIZONTAL_DP);
        setPadding(ph, pv, ph, pv);

        // Create controls row
        LinearLayout controlsRow = new LinearLayout(context);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER_VERTICAL);
        controlsRow.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT));

        // Create buttons
        mFlashButton = createSecondaryButton("Flash");
        mRatioButton = createSecondaryButton("Ratio");
        mCameraSwitchButton = createSecondaryButton("Camera");
        mRecordButton = createRecordButton();
        mMicButton = createSecondaryButton("Mic");
        mCaptureButton = createSecondaryButton("Capture");

        // Add buttons to the controls row
        int spacing = dpToPx(BUTTON_SPACING_DP);

        controlsRow.addView(mFlashButton);
        addSpacing(controlsRow, spacing);

        controlsRow.addView(mRatioButton);
        addSpacing(controlsRow, spacing);

        controlsRow.addView(mCameraSwitchButton);
        addSpacing(controlsRow, spacing);

        controlsRow.addView(mRecordButton);
        addSpacing(controlsRow, spacing);

        controlsRow.addView(mMicButton);
        addSpacing(controlsRow, spacing);

        controlsRow.addView(mCaptureButton);

        addView(controlsRow);

        // Create duration display
        mDurationText = new TextView(context);
        mDurationText.setTextColor(DURATION_TEXT_COLOR);
        mDurationText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mDurationText.setText("00:00");
        mDurationText.setGravity(Gravity.CENTER);
        mDurationText.setVisibility(GONE);
        LinearLayout.LayoutParams durationParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT);
        durationParams.topMargin = dpToPx(DURATION_TOP_MARGIN_DP);
        mDurationText.setLayoutParams(durationParams);
        addView(mDurationText);

        // Set up click listeners
        setupClickListeners();

        AppLogger.i(TAG, "ControlPanel initialized");
    }

    // =========================================================================
    // View creation
    // =========================================================================

    /**
     * Creates the large record button with a ring and inner circle.
     * <p>The button consists of an outer semi-transparent ring and an inner red circle.</p>
     *
     * @return the record button view
     */
    private View createRecordButton() {
        int ringSize = dpToPx(RECORD_BUTTON_SIZE_DP + RECORD_RING_EXTRA_DP * 2);
        int circleSize = dpToPx(RECORD_BUTTON_SIZE_DP);

        // Outer ring
        mRecordRingDrawable = new GradientDrawable();
        mRecordRingDrawable.setShape(GradientDrawable.OVAL);
        mRecordRingDrawable.setColor(RECORD_RING_COLOR);
        mRecordRingDrawable.setStroke(dpToPx(2), 0x44FFFFFF);

        View ringView = new View(getContext());
        ringView.setLayoutParams(new LayoutParams(ringSize, ringSize));
        ringView.setBackground(mRecordRingDrawable);

        // Inner circle (the actual button visual)
        mRecordCircleDrawable = new GradientDrawable();
        mRecordCircleDrawable.setShape(GradientDrawable.OVAL);
        mRecordCircleDrawable.setColor(RECORD_IDLE_COLOR);

        View circleView = new View(getContext());
        circleView.setLayoutParams(new LayoutParams(circleSize, circleSize));
        circleView.setBackground(mRecordCircleDrawable);

        // Wrap in a FrameLayout-like container using a LinearLayout with gravity
        LinearLayout container = new LinearLayout(getContext());
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER);
        container.setLayoutParams(new LayoutParams(ringSize, ringSize));
        container.addView(ringView);

        // Overlay the circle on top of the ring
        LinearLayout overlayContainer = new LinearLayout(getContext());
        overlayContainer.setOrientation(LinearLayout.HORIZONTAL);
        overlayContainer.setGravity(Gravity.CENTER);
        overlayContainer.setLayoutParams(new LayoutParams(
                dpToPx(RECORD_BUTTON_SIZE_DP), dpToPx(RECORD_BUTTON_SIZE_DP)));
        overlayContainer.addView(circleView);

        // Wrap both in an outer container
        FrameLayoutWrapper wrapper = new FrameLayoutWrapper(getContext(), ringSize, ringSize);
        wrapper.addView(ringView);
        wrapper.addView(overlayContainer);

        mRecordButton = wrapper;
        return wrapper;
    }

    /**
     * Creates a secondary (small) circular button with default icon appearance.
     *
     * @param name identifier for the button type
     * @return the created ImageView button
     */
    private ImageView createSecondaryButton(String name) {
        ImageView button = new ImageView(getContext());
        int size = dpToPx(SECONDARY_BUTTON_SIZE_DP);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x33FFFFFF);

        button.setLayoutParams(new LayoutParams(size, size));
        button.setBackground(bg);
        button.setColorFilter(ICON_COLOR);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10));

        return button;
    }

    /**
     * Adds a fixed-width spacing view between buttons.
     *
     * @param parent   the parent LinearLayout
     * @param widthDp  the spacing width in dp
     */
    private void addSpacing(LinearLayout parent, int widthDp) {
        View spacer = new View(getContext());
        spacer.setLayoutParams(new LayoutParams(widthDp, 1));
        parent.addView(spacer);
    }

    // =========================================================================
    // Click listeners
    // =========================================================================

    /**
     * Sets up click listeners for all control buttons.
     */
    private void setupClickListeners() {
        mRecordButton.setOnClickListener(v -> {
            AppLogger.d(TAG, "Record button tapped");
            if (mListener != null) {
                mListener.onRecordToggle();
            }
        });

        mCameraSwitchButton.setOnClickListener(v -> {
            AppLogger.d(TAG, "Camera switch button tapped");
            if (mListener != null) {
                mListener.onCameraSwitch();
            }
        });

        mCaptureButton.setOnClickListener(v -> {
            AppLogger.d(TAG, "Capture button tapped");
            if (mListener != null) {
                mListener.onCapture();
            }
        });

        mMicButton.setOnClickListener(v -> {
            AppLogger.d(TAG, "Mic toggle button tapped");
            if (mListener != null) {
                mListener.onMicToggle();
            }
        });

        mRatioButton.setOnClickListener(v -> {
            AppLogger.d(TAG, "Aspect ratio button tapped");
            if (mListener != null) {
                mListener.onAspectRatioSelector();
            }
        });

        mFlashButton.setOnClickListener(v -> {
            AppLogger.d(TAG, "Flash toggle button tapped");
            if (mListener != null) {
                mListener.onFlashToggle();
            }
        });
    }

    // =========================================================================
    // Public API — State updates
    // =========================================================================

    /**
     * Sets the callback listener for control panel events.
     *
     * @param listener the listener to receive callbacks, or null to remove
     */
    public void setOnControlListener(@Nullable OnControlListener listener) {
        mListener = listener;
    }

    /**
     * Updates the UI to reflect the recording state.
     * <p>When recording starts, the record button changes color and a pulse animation
     * begins. When recording stops, the button returns to idle state.</p>
     *
     * @param isRecording true if currently recording, false otherwise
     */
    public void setRecordingState(boolean isRecording) {
        mIsRecording = isRecording;

        if (isRecording) {
            // Change record button color to active
            mRecordCircleDrawable.setColor(RECORD_ACTIVE_COLOR);
            mDurationText.setVisibility(VISIBLE);
            startPulseAnimation();
            AppLogger.d(TAG, "Recording state: ACTIVE");
        } else {
            // Reset record button color
            mRecordCircleDrawable.setColor(RECORD_IDLE_COLOR);
            mDurationText.setVisibility(GONE);
            mDurationText.setText("00:00");
            stopPulseAnimation();
            AppLogger.d(TAG, "Recording state: IDLE");
        }
    }

    /**
     * Updates the camera mode button to reflect the current camera mode.
     * <p>Visually differentiates between BACK_ONLY, FRONT_ONLY, and DUAL_CAMERA
     * by updating the icon color.</p>
     *
     * @param mode the current {@link CameraConfig.CameraMode}
     * @throws IllegalArgumentException if mode is null
     */
    public void updateCameraMode(@NonNull CameraConfig.CameraMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("CameraMode must not be null");
        }
        try {
            int color = (mode == CameraConfig.CameraMode.DUAL_CAMERA)
                    ? ICON_ACTIVE_COLOR : ICON_COLOR;
            mCameraSwitchButton.setColorFilter(color);
            AppLogger.d(TAG, "Camera mode updated: " + mode.name());
        } catch (Exception e) {
            AppLogger.e(TAG, "Error updating camera mode UI");
        }
    }

    /**
     * Updates the mic button visual state to reflect whether the mic is enabled.
     *
     * @param micOn true if the microphone is enabled, false if disabled
     */
    public void updateMicState(boolean micOn) {
        try {
            int color = micOn ? ICON_COLOR : ICON_DISABLED_COLOR;
            mMicButton.setColorFilter(color);
            AppLogger.d(TAG, "Mic state updated: " + (micOn ? "ON" : "OFF"));
        } catch (Exception e) {
            AppLogger.e(TAG, "Error updating mic state UI");
        }
    }

    /**
     * Updates the flash button visual state to reflect whether flash is enabled.
     *
     * @param flashOn true if flash is enabled, false if disabled
     */
    public void updateFlashState(boolean flashOn) {
        try {
            int color = flashOn ? ICON_ACTIVE_COLOR : ICON_COLOR;
            mFlashButton.setColorFilter(color);
            AppLogger.d(TAG, "Flash state updated: " + (flashOn ? "ON" : "OFF"));
        } catch (Exception e) {
            AppLogger.e(TAG, "Error updating flash state UI");
        }
    }

    /**
     * Updates the recording duration display.
     * <p>Formats the duration as MM:SS or HH:MM:SS depending on length.</p>
     *
     * @param millis the elapsed recording duration in milliseconds
     */
    public void setRecordingDuration(long millis) {
        if (!mIsRecording) return;
        try {
            String formatted = formatDuration(millis);
            mDurationText.setText(formatted);
        } catch (Exception e) {
            AppLogger.e(TAG, "Error formatting duration");
        }
    }

    // =========================================================================
    // Pulse animation
    // =========================================================================

    /**
     * Starts a pulse animation on the record button to indicate active recording.
     * <p>The record ring scales between 1.0x and 1.15x repeatedly.</p>
     */
    private void startPulseAnimation() {
        if (mPulseAnimator != null && mPulseAnimator.isRunning()) {
            return;
        }

        mPulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f);
        mPulseAnimator.setDuration(800);
        mPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        mPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());

        mPulseAnimator.addUpdateListener(animation -> {
            float scale = (float) animation.getAnimatedValue();
            mRecordButton.setScaleX(scale);
            mRecordButton.setScaleY(scale);
        });

        mPulseAnimator.start();
    }

    /**
     * Stops the pulse animation and resets the record button scale.
     */
    private void stopPulseAnimation() {
        if (mPulseAnimator != null) {
            mPulseAnimator.cancel();
            mPulseAnimator = null;
            mRecordButton.setScaleX(1.0f);
            mRecordButton.setScaleY(1.0f);
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Formats a duration in milliseconds to a human-readable time string.
     * <p>If the duration is less than 1 hour, returns MM:SS.
     * If 1 hour or more, returns HH:MM:SS.</p>
     *
     * @param millis the duration in milliseconds
     * @return the formatted time string
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

    /**
     * Converts a value in dp to pixels.
     *
     * @param dp the value in density-independent pixels
     * @return the equivalent value in pixels
     */
    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    // =========================================================================
    // Inner helper: FrameLayout-like wrapper
    // =========================================================================

    /**
     * Simple FrameLayout wrapper for overlaying the record circle on the ring.
     * <p>Used as a minimal alternative to android.widget.FrameLayout for
     * programmatic layout without requiring the full FrameLayout import.</p>
     */
    private static class FrameLayoutWrapper extends LinearLayout {

        /**
         * Creates a new FrameLayoutWrapper.
         *
         * @param context the context
         * @param width   the width in pixels
         * @param height  the height in pixels
         */
        FrameLayoutWrapper(Context context, int width, int height) {
            super(context);
            setOrientation(LinearLayout.VERTICAL);
            setGravity(Gravity.CENTER);
            LayoutParams params = new LayoutParams(width, height);
            setLayoutParams(params);
        }
    }
}
