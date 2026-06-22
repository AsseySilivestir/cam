package com.dualcam.recorder.permissions;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.dualcam.recorder.utils.AppLogger;

/**
 * Manages Android runtime permissions for the DualCamRecorder application.
 * <p>
 * Handles requesting, checking, and managing camera, microphone, and storage permissions
 * required by the app. Provides a clean callback interface for permission results
 * and handles rationale dialogs when users deny permissions with "don't ask again".</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Checks and requests camera, microphone, and storage permissions</li>
 *   <li>Detects when rationale should be shown (user selected "don't ask again")</li>
 *   <li>Shows a user-friendly dialog explaining why permissions are needed</li>
 *   <li>Provides individual permission state queries (camera, mic, storage)</li>
 *   <li>Callback interface for receiving permission results</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * PermissionHelper permissionHelper = new PermissionHelper(this);
 * permissionHelper.setOnPermissionResultListener(new PermissionHelper.OnPermissionResultListener() {
 *     {@literal @}Override
 *     public void onAllPermissionsGranted() {
 *         // Open cameras and start preview
 *     }
 *
 *     {@literal @}Override
 *     public void onPermissionDenied(boolean somePermanentlyDenied) {
 *         if (somePermanentlyDenied) {
 *             // Show settings dialog
 *         }
 *     }
 * });
 *
 * if (!permissionHelper.hasAllPermissions()) {
 *     permissionHelper.requestPermissions();
 * }
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class PermissionHelper {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Tag for logging */
    private static final String TAG = "PermissionHelper";

    /** Request code used when requesting permissions */
    private static final int PERMISSION_REQUEST_CODE = 1001;

    /** Required permissions for the app */
    private static final String[] REQUIRED_PERMISSIONS = {
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
    };

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** The host Activity for permission operations */
    private final Activity mActivity;

    /** Callback listener for permission results */
    private OnPermissionResultListener mResultListener;

    /** Whether a permission request is currently in flight */
    private boolean mRequestInProgress = false;

    // =========================================================================
    // Callback interface
    // =========================================================================

    /**
     * Callback interface for receiving permission request results.
     */
    public interface OnPermissionResultListener {

        /**
         * Called when all required permissions have been granted by the user.
         */
        void onAllPermissionsGranted();

        /**
         * Called when one or more required permissions have been denied.
         *
         * @param somePermanentlyDenied true if the user selected "don't ask again"
         *                              for any denied permission, requiring a trip to app settings
         */
        void onPermissionDenied(boolean somePermanentlyDenied);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new PermissionHelper for the given Activity.
     *
     * @param activity the host Activity used for permission requests and UI dialogs
     * @throws IllegalArgumentException if activity is null
     */
    public PermissionHelper(@NonNull Activity activity) {
        if (activity == null) {
            throw new IllegalArgumentException("Activity must not be null");
        }
        mActivity = activity;
        AppLogger.i(TAG, "PermissionHelper created");
    }

    // =========================================================================
    // Permission checks
    // =========================================================================

    /**
     * Checks whether all required permissions are currently granted.
     *
     * @return true if all camera, microphone, and storage permissions are granted, false otherwise
     */
    public boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(mActivity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether the camera permission is granted.
     *
     * @return true if the camera permission is granted
     */
    public boolean isCameraPermissionGranted() {
        return ContextCompat.checkSelfPermission(mActivity, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks whether the microphone/audio recording permission is granted.
     *
     * @return true if the record audio permission is granted
     */
    public boolean isMicPermissionGranted() {
        return ContextCompat.checkSelfPermission(mActivity, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks whether the storage/write external storage permission is granted.
     * <p>Note: On Android 10+ (API 29+), scoped storage is used and this permission
     * is generally not required. On older versions, WRITE_EXTERNAL_STORAGE is checked.</p>
     *
     * @return true if storage permission is granted (or not required on Android 10+)
     */
    public boolean isStoragePermissionGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage: no permission needed
            return true;
        }
        return ContextCompat.checkSelfPermission(mActivity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks whether a rationale dialog should be shown for any denied permission.
     * <p>This returns true if the user has previously denied a permission but did not
     * select "don't ask again", meaning a rationale explanation may persuade them.</p>
     *
     * @return true if rationale should be shown for at least one permission
     */
    public boolean shouldShowRationale() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(mActivity, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(mActivity, permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // Permission requests
    // =========================================================================

    /**
     * Requests all missing permissions from the user.
     * <p>If all permissions are already granted, this is a no-op and the callback
     * is triggered immediately.</p>
     *
     * @throws IllegalStateException if a permission request is already in progress
     */
    public void requestPermissions() {
        if (mRequestInProgress) {
            AppLogger.w(TAG, "Permission request already in progress");
            return;
        }

        if (hasAllPermissions()) {
            AppLogger.i(TAG, "All permissions already granted");
            if (mResultListener != null) {
                mResultListener.onAllPermissionsGranted();
            }
            return;
        }

        mRequestInProgress = true;
        AppLogger.i(TAG, "Requesting missing permissions...");

        ActivityCompat.requestPermissions(mActivity, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
    }

    /**
     * Handles the permission result from
     * {@link Activity#onRequestPermissionsResult(int, String[], int[])}.
     * <p>This method must be called from the host Activity's
     * {@code onRequestPermissionsResult} to properly route results.</p>
     *
     * @param requestCode  the request code passed to requestPermissions
     * @param permissions  the requested permissions (never null)
     * @param grantResults the grant results for each permission
     */
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != PERMISSION_REQUEST_CODE) {
            return;
        }

        mRequestInProgress = false;

        if (grantResults.length == 0) {
            AppLogger.w(TAG, "Permission request was cancelled");
            if (mResultListener != null) {
                mResultListener.onPermissionDenied(false);
            }
            return;
        }

        // Check if all permissions were granted
        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            AppLogger.i(TAG, "All permissions granted");
            if (mResultListener != null) {
                mResultListener.onAllPermissionsGranted();
            }
        } else {
            // Check if any permissions were permanently denied
            boolean anyPermanentlyDenied = false;
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(mActivity, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(mActivity, permission)) {
                        anyPermanentlyDenied = true;
                        break;
                    }
                }
            }

            AppLogger.w(TAG, "Permissions denied, permanently=" + anyPermanentlyDenied);

            if (mResultListener != null) {
                mResultListener.onPermissionDenied(anyPermanentlyDenied);
            }

            // Show rationale or settings dialog
            if (anyPermanentlyDenied) {
                showSettingsDialog();
            } else {
                showPermissionRationaleDialog();
            }
        }
    }

    // =========================================================================
    // Dialogs
    // =========================================================================

    /**
     * Shows a dialog explaining why the permissions are needed.
     * <p>This dialog is shown when the user has denied permissions but has not
     * selected "don't ask again", so a rationale explanation may help.</p>
     */
    public void showPermissionRationaleDialog() {
        try {
            new AlertDialog.Builder(mActivity)
                    .setTitle("Permissions Required")
                    .setMessage("DualCamRecorder needs access to your camera and microphone to record "
                            + "videos with both front and back cameras simultaneously.")
                    .setPositiveButton("Grant Permissions", (dialog, which) -> {
                        dialog.dismiss();
                        requestPermissions();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        dialog.dismiss();
                        Toast.makeText(mActivity, "Permissions denied. Some features may not work.",
                                Toast.LENGTH_LONG).show();
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            AppLogger.e(TAG, "Error showing rationale dialog");
        }
    }

    /**
     * Shows a dialog prompting the user to open app settings to manually grant permissions.
     * <p>This is shown when permissions have been permanently denied (user selected
     * "don't ask again"), so the only way to grant them is through system settings.</p>
     */
    private void showSettingsDialog() {
        try {
            new AlertDialog.Builder(mActivity)
                    .setTitle("Permissions Permanently Denied")
                    .setMessage("Camera and microphone permissions are required for this app to function. "
                            + "Please grant them in Settings > Apps > DualCamRecorder > Permissions.")
                    .setPositiveButton("Open Settings", (dialog, which) -> {
                        dialog.dismiss();
                        openAppSettings();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        dialog.dismiss();
                        Toast.makeText(mActivity, "Permissions denied. App cannot record.",
                                Toast.LENGTH_LONG).show();
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            AppLogger.e(TAG, "Error showing settings dialog");
        }
    }

    /**
     * Opens the system App Settings page for this application.
     * <p>Allows the user to manually grant permissions that were permanently denied.</p>
     */
    private void openAppSettings() {
        try {
            Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", mActivity.getPackageName(), null);
            settingsIntent.setData(uri);
            mActivity.startActivity(settingsIntent);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to open app settings");
            Toast.makeText(mActivity, "Unable to open settings. Please grant permissions manually.",
                    Toast.LENGTH_LONG).show();
        }
    }

    // =========================================================================
    // Listener management
    // =========================================================================

    /**
     * Sets the callback listener for permission results.
     *
     * @param listener the listener to receive permission callbacks, or null to remove
     */
    public void setOnPermissionResultListener(OnPermissionResultListener listener) {
        mResultListener = listener;
    }
}
