package com.dualcam.recorder.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;
import com.dualcam.recorder.camera.CameraConfig;
import com.dualcam.recorder.utils.AppLogger;
import com.google.android.material.bottomsheet.BottomSheetDialog;

/**
 * A bottom-sheet dialog that allows the user to select a video aspect ratio.
 * <p>
 * Displays four aspect ratio options with visual previews and human-readable labels:
 * <ul>
 *   <li><b>9:16</b> — Portrait, ideal for TikTok, Instagram Reels, YouTube Shorts</li>
 *   <li><b>1:1</b> — Square, ideal for Instagram feed posts</li>
 *   <li><b>16:9</b> — Landscape, ideal for YouTube, Facebook</li>
 *   <li><b>4:3</b> — Standard, classic camera ratio</li>
 * </ul>
 * Each option shows a visual preview rectangle matching the selected ratio, with the
 * currently selected ratio highlighted with a colored accent border.</p>
 *
 * <h3>Design:</h3>
 * <ul>
 *   <li>Bottom sheet with rounded top corners</li>
 *   <li>Each option is a horizontal row: visual preview + label text</li>
 *   <li>Selected item highlighted with accent-colored border and text</li>
 *   <li>Clean, modern dark theme for consistency with the camera UI</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * AspectRatioSelector selector = new AspectRatioSelector(context);
 * selector.setOnRatioSelectedListener(ratio -> {
 *     cameraConfig.setAspectRatio(ratio);
 *     composerView.setAspectRatio(ratio);
 * });
 * selector.setSelectedRatio(CameraConfig.AspectRatio.RATIO_9_16);
 * selector.show(activity);
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class AspectRatioSelector {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Tag for logging */
    private static final String TAG = "AspectRatioSelector";

    /** Color for the selected item highlight (coral red accent) */
    private static final int ACCENT_COLOR = 0xFFE8503A;

    /** Color for unselected items (subtle gray) */
    private static final int NORMAL_COLOR = 0xFF888888;

    /** Color for the background of the bottom sheet (dark translucent) */
    private static final int SHEET_BG_COLOR = 0xE6222222;

    /** Color for option item backgrounds (dark semi-transparent) */
    private static final int ITEM_BG_COLOR = 0x33FFFFFF;

    /** Color for text labels (white) */
    private static final int TEXT_COLOR = 0xFFFFFFFF;

    /** Color for selected text labels (accent) */
    private static final int TEXT_SELECTED_COLOR = 0xFFE8503A;

    /** Height of the visual preview rectangles in dp */
    private static final int PREVIEW_HEIGHT_DP = 48;

    /** Border stroke width in dp */
    private static final int BORDER_STROKE_DP = 2;

    /** Corner radius for the bottom sheet in dp */
    private static final int CORNER_RADIUS_DP = 16;

    /** Padding between items in dp */
    private static final int ITEM_MARGIN_DP = 8;

    /** Padding inside each item in dp */
    private static final int ITEM_PADDING_DP = 12;

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Application context */
    private final Context mContext;

    /** The BottomSheetDialog instance, or null if not showing */
    private BottomSheetDialog mDialog;

    /** The currently selected aspect ratio */
    private CameraConfig.AspectRatio mSelectedRatio;

    /** Callback for when a ratio is selected */
    private OnRatioSelectedListener mListener;

    /** References to the option views for updating selection state */
    private View[] mOptionViews;

    /** References to the preview drawables for updating selection highlight */
    private Drawable[] mPreviewDrawables;

    /** References to the label text views for updating selection text color */
    private TextView[] mLabelViews;

    /** The aspect ratio options to display */
    private final CameraConfig.AspectRatio[] mRatios = CameraConfig.AspectRatio.values();

    // =========================================================================
    // Callback interface
    // =========================================================================

    /**
     * Callback interface for receiving aspect ratio selection events.
     */
    public interface OnRatioSelectedListener {

        /**
         * Called when the user selects an aspect ratio.
         *
         * @param ratio the selected {@link CameraConfig.AspectRatio}
         */
        void onRatioSelected(CameraConfig.AspectRatio ratio);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new AspectRatioSelector.
     *
     * @param context the activity or application context
     * @throws IllegalArgumentException if context is null
     */
    public AspectRatioSelector(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        mContext = context;
        mSelectedRatio = CameraConfig.AspectRatio.RATIO_9_16;
        mOptionViews = new View[mRatios.length];
        mPreviewDrawables = new Drawable[mRatios.length];
        mLabelViews = new TextView[mRatios.length];
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Shows the aspect ratio selector as a bottom sheet dialog.
     *
     * @param activity the AppCompatActivity used to host the BottomSheetDialog
     */
    public void show(@NonNull AppCompatActivity activity) {
        if (activity == null) {
            AppLogger.e(TAG, "Activity is null, cannot show dialog");
            return;
        }

        try {
            dismiss(); // Dismiss any existing dialog

            mDialog = new BottomSheetDialog(activity);
            View contentView = createContentView();
            mDialog.setContentView(contentView);

            // Make the bottom sheet background transparent to show rounded corners
            mDialog.getWindow().findViewById(com.google.android.material.R.id.design_bottom_sheet)
                    .setBackgroundDrawable(createRoundedCornerDrawable());

            mDialog.setOnDismissListener(dialog -> {
                mDialog = null;
                AppLogger.d(TAG, "Dialog dismissed");
            });

            mDialog.show();
            AppLogger.i(TAG, "Aspect ratio selector shown");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error showing aspect ratio dialog");
        }
    }

    /**
     * Sets the currently selected aspect ratio and updates the UI highlight.
     * <p>Call this before {@link #show(AppCompatActivity)} to pre-select an option.</p>
     *
     * @param ratio the aspect ratio to mark as selected
     * @throws IllegalArgumentException if ratio is null
     */
    public void setSelectedRatio(@NonNull CameraConfig.AspectRatio ratio) {
        if (ratio == null) {
            throw new IllegalArgumentException("Ratio must not be null");
        }
        mSelectedRatio = ratio;
        updateSelectionUI();
    }

    /**
     * Sets the callback listener for ratio selection events.
     *
     * @param listener the listener to receive selection callbacks, or null to remove
     */
    public void setOnRatioSelectedListener(@Nullable OnRatioSelectedListener listener) {
        mListener = listener;
    }

    /**
     * Dismisses the dialog if it is currently showing.
     * <p>Safe to call even if the dialog is not visible.</p>
     */
    public void dismiss() {
        if (mDialog != null && mDialog.isShowing()) {
            try {
                mDialog.dismiss();
            } catch (Exception e) {
                AppLogger.e(TAG, "Error dismissing dialog");
            }
        }
        mDialog = null;
    }

    // =========================================================================
    // UI building
    // =========================================================================

    /**
     * Creates the content view for the bottom sheet dialog.
     * <p>Builds a vertical LinearLayout with a title header and four ratio options.</p>
     *
     * @return the root view of the dialog content
     */
    private View createContentView() {
        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SHEET_BG_COLOR);
        root.setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(24));

        // Header
        TextView header = new TextView(mContext);
        header.setText("Aspect Ratio");
        header.setTextColor(TEXT_COLOR);
        header.setTextSize(18);
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        header.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(16));
        root.addView(header);

        // Ratio options
        for (int i = 0; i < mRatios.length; i++) {
            View optionView = createRatioOption(mRatios[i], i);
            root.addView(optionView);
        }

        return root;
    }

    /**
     * Creates a single aspect ratio option row.
     * <p>Each row consists of a visual preview rectangle (showing the ratio visually)
     * and a label text view.</p>
     *
     * @param ratio    the aspect ratio this option represents
     * @param index    the index of this option in the list
     * @return the option view
     */
    private View createRatioOption(CameraConfig.AspectRatio ratio, int index) {
        LinearLayout option = new LinearLayout(mContext);
        option.setOrientation(LinearLayout.HORIZONTAL);
        option.setGravity(android.view.Gravity.CENTER_VERTICAL);
        option.setPadding(dpToPx(ITEM_PADDING_DP), dpToPx(ITEM_PADDING_DP),
                dpToPx(ITEM_PADDING_DP), dpToPx(ITEM_PADDING_DP));
        option.setBackground(createItemBackground());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        int margin = dpToPx(ITEM_MARGIN_DP);
        params.setMargins(0, margin, 0, margin);
        option.setLayoutParams(params);

        // Visual preview rectangle
        View preview = createPreviewRectangle(ratio);
        mPreviewDrawables[index] = preview.getBackground();
        option.addView(preview);

        // Label text
        TextView label = new TextView(mContext);
        label.setText(ratio.label);
        label.setTextColor(isSelected(ratio) ? TEXT_SELECTED_COLOR : TEXT_COLOR);
        label.setTextSize(14);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(dpToPx(16), 0, 0, 0);
        label.setLayoutParams(labelParams);
        mLabelViews[index] = label;
        option.addView(label);

        // Click handler
        option.setOnClickListener(v -> {
            mSelectedRatio = ratio;
            updateSelectionUI();
            if (mListener != null) {
                mListener.onRatioSelected(ratio);
            }
            dismiss();
        });

        mOptionViews[index] = option;
        return option;
    }

    /**
     * Creates a visual preview rectangle that represents the aspect ratio visually.
     * <p>The rectangle's width and height are scaled to represent the actual ratio
     * while fitting within a fixed height. A 9:16 ratio will appear tall and narrow,
     * while 16:9 will appear wide and short.</p>
     *
     * @param ratio the aspect ratio to visualize
     * @return a View with the ratio-proportional background shape
     */
    private View createPreviewRectangle(CameraConfig.AspectRatio ratio) {
        View preview = new View(mContext);

        int maxHeight = dpToPx(PREVIEW_HEIGHT_DP);
        float ratioValue = ratio.getRatio(); // width / height
        int width;
        int height;

        if (ratioValue >= 1.0f) {
            // Landscape: constrain by height
            height = maxHeight;
            width = (int) (maxHeight * ratioValue);
        } else {
            // Portrait: constrain by height, reduce width
            height = maxHeight;
            width = (int) (maxHeight * ratioValue);
        }

        // Ensure minimum width for visibility
        width = Math.max(dpToPx(24), width);
        height = Math.max(dpToPx(24), height);

        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dpToPx(4));
        drawable.setColor(0x44FFFFFF);
        drawable.setStroke(dpToPx(BORDER_STROKE_DP),
                isSelected(ratio) ? ACCENT_COLOR : NORMAL_COLOR);

        preview.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        preview.setBackground(drawable);

        return preview;
    }

    /**
     * Creates a rounded-corner drawable for the bottom sheet background.
     *
     * @return a GradientDrawable with rounded top corners
     */
    private Drawable createRoundedCornerDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(SHEET_BG_COLOR);
        drawable.setCornerRadius(dpToPx(CORNER_RADIUS_DP));
        return drawable;
    }

    /**
     * Creates a background drawable for option items.
     *
     * @return a GradientDrawable with subtle rounded corners
     */
    private Drawable createItemBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(ITEM_BG_COLOR);
        drawable.setCornerRadius(dpToPx(8));
        return drawable;
    }

    // =========================================================================
    // Selection state management
    // =========================================================================

    /**
     * Checks whether the given ratio is the currently selected ratio.
     *
     * @param ratio the ratio to check
     * @return true if the ratio matches the current selection
     */
    private boolean isSelected(CameraConfig.AspectRatio ratio) {
        return mSelectedRatio != null && mSelectedRatio == ratio;
    }

    /**
     * Updates all option views to reflect the current selection state.
     * <p>Updates border colors on preview rectangles and text colors on labels.</p>
     */
    private void updateSelectionUI() {
        for (int i = 0; i < mRatios.length; i++) {
            boolean selected = isSelected(mRatios[i]);

            // Update preview border color
            if (mPreviewDrawables[i] instanceof GradientDrawable) {
                ((GradientDrawable) mPreviewDrawables[i]).setStroke(
                        dpToPx(BORDER_STROKE_DP), selected ? ACCENT_COLOR : NORMAL_COLOR);
            }

            // Update label text color
            if (mLabelViews[i] != null) {
                mLabelViews[i].setTextColor(selected ? TEXT_SELECTED_COLOR : TEXT_COLOR);
            }
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Converts a value in dp to pixels.
     *
     * @param dp the value in density-independent pixels
     * @return the equivalent value in pixels
     */
    private int dpToPx(int dp) {
        float density = mContext.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }
}
