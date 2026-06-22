package com.dualcam.recorder.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;

import com.dualcam.recorder.utils.AppLogger;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Manages media file creation and gallery access for the DualCamRecorder application.
 * <p>
 * Handles creating properly named output file paths for videos and photos in the
 * device's DCIM folder, inserting MediaStore entries for gallery visibility, and
 * providing file information utilities. Properly supports Android 10+ scoped storage
 * via the MediaStore API.</p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Creates output file paths with timestamps (e.g., "VID_20260621_153045.mp4")</li>
 *   <li>Manages subfolder "DCIM/DualCamRecorder/" for organized file storage</li>
 *   <li>Inserts MediaStore entries for immediate gallery visibility</li>
 *   <li>Provides file size formatting utilities</li>
 *   <li>Supports both legacy File API and MediaStore API (Android 10+)</li>
 *   <li>Opens the device gallery via intent</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * MediaFileHelper fileHelper = new MediaFileHelper(context);
 *
 * // Create a new video file path
 * File videoFile = fileHelper.createVideoFilePath();
 *
 * // After recording, insert to MediaStore
 * fileHelper.insertVideoToMediaStore(videoFile);
 *
 * // Get formatted file size
 * String size = fileHelper.formatFileSize(videoFile.length());
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class MediaFileHelper {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Tag for logging */
    private static final String TAG = "MediaFileHelper";

    /** Application subfolder name within DCIM */
    private static final String APP_FOLDER = "DualCamRecorder";

    /** Date format used for file naming (e.g., "20260621_153045") */
    private static final String TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss";

    /** Video file prefix */
    private static final String VIDEO_PREFIX = "VID_";

    /** Photo file prefix */
    private static final String PHOTO_PREFIX = "IMG_";

    /** Video file extension */
    private static final String VIDEO_EXTENSION = ".mp4";

    /** Photo file extension */
    private static final String PHOTO_EXTENSION = ".jpg";

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Application context */
    private final Context mContext;

    /** Date formatter for file name timestamps */
    private final SimpleDateFormat mDateFormat;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new MediaFileHelper.
     *
     * @param context the application context
     * @throws IllegalArgumentException if context is null
     */
    public MediaFileHelper(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        mContext = context.getApplicationContext();
        mDateFormat = new SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US);
        AppLogger.i(TAG, "MediaFileHelper created");
    }

    // =========================================================================
    // File path creation
    // =========================================================================

    /**
     * Creates a new video file path in the DCIM/DualCamRecorder/ directory.
     * <p>The file name follows the pattern: {@code VID_20260621_153045.mp4}</p>
     *
     * @return a {@link File} object representing the new video file path
     * @throws RuntimeException if the DCIM directory cannot be created or accessed
     */
    public File createVideoFilePath() {
        try {
            File dcimDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), APP_FOLDER);

            if (!dcimDir.exists()) {
                boolean created = dcimDir.mkdirs();
                if (!created) {
                    AppLogger.e(TAG, "Failed to create directory: " + dcimDir.getAbsolutePath());
                    // Fallback to standard DCIM directory
                    dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                }
            }

            String timestamp = mDateFormat.format(new Date());
            String fileName = VIDEO_PREFIX + timestamp + VIDEO_EXTENSION;
            File videoFile = new File(dcimDir, fileName);

            AppLogger.i(TAG, "Created video file path: " + videoFile.getAbsolutePath());
            return videoFile;
        } catch (Exception e) {
            AppLogger.e(TAG, "Error creating video file path");
            // Fallback: use app-specific external files directory
            File fallback = new File(mContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    VIDEO_PREFIX + mDateFormat.format(new Date()) + VIDEO_EXTENSION);
            AppLogger.w(TAG, "Using fallback path: " + fallback.getAbsolutePath());
            return fallback;
        }
    }

    /**
     * Creates a new photo file path in the DCIM/DualCamRecorder/ directory.
     * <p>The file name follows the pattern: {@code IMG_20260621_153045.jpg}</p>
     *
     * @return a {@link File} object representing the new photo file path
     * @throws RuntimeException if the DCIM directory cannot be created or accessed
     */
    public File createPhotoFilePath() {
        try {
            File dcimDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM), APP_FOLDER);

            if (!dcimDir.exists()) {
                boolean created = dcimDir.mkdirs();
                if (!created) {
                    AppLogger.e(TAG, "Failed to create directory: " + dcimDir.getAbsolutePath());
                    dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                }
            }

            String timestamp = mDateFormat.format(new Date());
            String fileName = PHOTO_PREFIX + timestamp + PHOTO_EXTENSION;
            File photoFile = new File(dcimDir, fileName);

            AppLogger.i(TAG, "Created photo file path: " + photoFile.getAbsolutePath());
            return photoFile;
        } catch (Exception e) {
            AppLogger.e(TAG, "Error creating photo file path");
            File fallback = new File(mContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    PHOTO_PREFIX + mDateFormat.format(new Date()) + PHOTO_EXTENSION);
            AppLogger.w(TAG, "Using fallback path: " + fallback.getAbsolutePath());
            return fallback;
        }
    }

    // =========================================================================
    // MediaStore integration
    // =========================================================================

    /**
     * Inserts a video file into the MediaStore so it appears in the device's gallery.
     * <p>On Android 10+ (API 29+), uses the MediaStore API for scoped storage compliance.
     * On older versions, uses a legacy scan intent.</p>
     *
     * @param file the video file to insert into MediaStore
     * @return the content {@link Uri} of the inserted entry, or null if insertion failed
     */
    public Uri insertVideoToMediaStore(File file) {
        if (file == null || !file.exists()) {
            AppLogger.e(TAG, "Cannot insert to MediaStore: file is null or does not exist");
            return null;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.TITLE, file.getName());
            values.put(MediaStore.Video.Media.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Video.Media.SIZE, file.length());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Use relative path for scoped storage
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/" + APP_FOLDER);
                values.put(MediaStore.Video.Media.IS_PENDING, 1);

                ContentResolver resolver = mContext.getContentResolver();
                Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = resolver.insert(collection, values);

                if (uri != null) {
                    // Copy file content to the MediaStore entry
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os != null) {
                            copyFileToStream(file, os);
                        }
                    }

                    // Clear IS_PENDING flag to make visible in gallery
                    values.clear();
                    values.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);

                    AppLogger.i(TAG, "Video inserted to MediaStore (Q+): " + uri);
                    return uri;
                }
            } else {
                // Legacy: Insert with absolute path
                values.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());

                ContentResolver resolver = mContext.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);

                // Scan the file to make it visible in gallery
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(file));
                mContext.sendBroadcast(scanIntent);

                AppLogger.i(TAG, "Video inserted to MediaStore (legacy): " + uri);
                return uri;
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error inserting video to MediaStore");
        }

        return null;
    }

    /**
     * Inserts a photo file into the MediaStore so it appears in the device's gallery.
     * <p>On Android 10+ (API 29+), uses the MediaStore API for scoped storage compliance.
     * On older versions, uses a legacy scan intent.</p>
     *
     * @param file the photo file to insert into MediaStore
     * @return the content {@link Uri} of the inserted entry, or null if insertion failed
     */
    public Uri insertPhotoToMediaStore(File file) {
        if (file == null || !file.exists()) {
            AppLogger.e(TAG, "Cannot insert to MediaStore: file is null or does not exist");
            return null;
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, file.getName());
            values.put(MediaStore.Images.Media.DISPLAY_NAME, file.getName());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
            values.put(MediaStore.Images.Media.SIZE, file.length());

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+: Use relative path for scoped storage
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_DCIM + "/" + APP_FOLDER);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                ContentResolver resolver = mContext.getContentResolver();
                Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = resolver.insert(collection, values);

                if (uri != null) {
                    // Copy file content to the MediaStore entry
                    try (OutputStream os = resolver.openOutputStream(uri)) {
                        if (os != null) {
                            copyFileToStream(file, os);
                        }
                    }

                    // Clear IS_PENDING flag to make visible in gallery
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, values, null, null);

                    AppLogger.i(TAG, "Photo inserted to MediaStore (Q+): " + uri);
                    return uri;
                }
            } else {
                // Legacy: Insert with absolute path
                values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());

                ContentResolver resolver = mContext.getContentResolver();
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

                // Scan the file to make it visible in gallery
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(file));
                mContext.sendBroadcast(scanIntent);

                AppLogger.i(TAG, "Photo inserted to MediaStore (legacy): " + uri);
                return uri;
            }
        } catch (Exception e) {
            AppLogger.e(TAG, "Error inserting photo to MediaStore");
        }

        return null;
    }

    // =========================================================================
    // File information utilities
    // =========================================================================

    /**
     * Returns the size of the specified file in bytes.
     *
     * @param file the file whose size to retrieve
     * @return the file size in bytes, or 0 if the file does not exist or cannot be read
     */
    public long getFileSize(File file) {
        if (file == null || !file.exists()) {
            return 0;
        }
        try {
            return file.length();
        } catch (SecurityException e) {
            AppLogger.e(TAG, "No permission to read file size: " + file.getAbsolutePath());
            return 0;
        }
    }

    /**
     * Formats a file size in bytes to a human-readable string.
     * <p>Examples: "1.2 KB", "3.5 MB", "1.8 GB"</p>
     *
     * @param bytes the file size in bytes
     * @return a formatted string (e.g., "3.5 MB"), or "0 B" for zero/negative values
     */
    public String formatFileSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";

        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024, exp), unit);
    }

    // =========================================================================
    // Gallery
    // =========================================================================

    /**
     * Opens the device's gallery application to view saved media.
     * <p>Launches an intent to view the DCIM folder, allowing the user to browse
     * recently saved photos and videos.</p>
     */
    public void openGallery() {
        try {
            Intent galleryIntent = new Intent(Intent.ACTION_VIEW);
            galleryIntent.setType("image/*");
            galleryIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Try to open the picker with both photos and videos
            Intent chooserIntent = Intent.createChooser(galleryIntent, "View Gallery");
            chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mContext.startActivity(chooserIntent);

            AppLogger.i(TAG, "Gallery opened");
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to open gallery");
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Copies the contents of a file to an output stream.
     *
     * @param file      the source file to copy
     * @param outputStream the destination output stream
     * @throws Exception if an I/O error occurs
     */
    private void copyFileToStream(File file, OutputStream outputStream) throws Exception {
        byte[] buffer = new byte[8192];
        int bytesRead;
        try (FileInputStream fis = new FileInputStream(file)) {
            while ((bytesRead = fis.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        }
    }
}
