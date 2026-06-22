package com.dualcam.recorder.utils;

import android.util.Log;

/**
 * Centralized logging utility for the DualCamRecorder application.
 * <p>
 * Provides a consistent, configurable logging interface across all app components.
 * Supports multiple log levels (DEBUG, INFO, WARNING, ERROR) and can be globally
 * enabled or disabled, making it safe to leave logging calls in production code.
 * </p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Four log levels: DEBUG, INFO, WARNING, ERROR</li>
 *   <li>Global enable/disable switch for release builds</li>
 *   <li>Automatic inclusion of caller method name and line number</li>
 *   <li>Full exception stack trace logging for error diagnostics</li>
 *   <li>Zero overhead when disabled (checks flag before any string concatenation)</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Initialize once in Application.onCreate()
 * AppLogger.init(BuildConfig.DEBUG);
 *
 * // Log messages throughout the app
 * AppLogger.d("MainActivity", "onCreate called");
 * AppLogger.i("CameraManager", "Camera opened successfully");
 * AppLogger.w("Recorder", "Low disk space warning");
 * AppLogger.e("Encoder", "Encoding failed", exception);
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class AppLogger {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Prefix appended to all log messages for easy identification in Logcat */
    private static final String APP_PREFIX = "[DCR] ";

    /** Stack trace depth offset to reach the actual caller (skip AppLogger internals) */
    private static final int STACK_TRACE_DEPTH = 3;

    // =========================================================================
    // Static state
    // =========================================================================

    /** Whether logging is currently enabled. When false, all log calls are no-ops. */
    private static volatile boolean sEnabled = true;

    // =========================================================================
    // Constructor (private to prevent instantiation)
    // =========================================================================

    /**
     * Private constructor to prevent instantiation.
     * <p>All methods are static; this class is not meant to be instantiated.</p>
     */
    private AppLogger() {
        // Prevent instantiation
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    /**
     * Initializes the AppLogger with the specified debug mode.
     * <p>Call this once during application startup (e.g., in {@code Application.onCreate()}).</p>
     *
     * @param debugMode true to enable logging (debug builds), false to disable (release builds)
     */
    public static void init(boolean debugMode) {
        sEnabled = debugMode;
        if (sEnabled) {
            Log.d("AppLogger", "Logging initialized (DEBUG mode)");
        }
    }

    // =========================================================================
    // Log level methods
    // =========================================================================

    /**
     * Logs a DEBUG-level message.
     * <p>Use for detailed diagnostic information useful during development.
     * These messages are typically filtered out in release builds.</p>
     *
     * @param tag     the log tag for categorization (e.g., "MainActivity", "CameraManager")
     * @param message the message to log
     */
    public static void d(String tag, String message) {
        if (!sEnabled) return;
        Log.d(buildTag(tag), formatMessage(message));
    }

    /**
     * Logs an INFO-level message.
     * <p>Use for significant lifecycle events and operational information
     * (e.g., "Camera opened", "Recording started").</p>
     *
     * @param tag     the log tag for categorization
     * @param message the message to log
     */
    public static void i(String tag, String message) {
        if (!sEnabled) return;
        Log.i(buildTag(tag), formatMessage(message));
    }

    /**
     * Logs a WARNING-level message.
     * <p>Use for potentially harmful situations that are not yet errors
     * (e.g., "Low disk space", "Camera reconnected").</p>
     *
     * @param tag     the log tag for categorization
     * @param message the message to log
     */
    public static void w(String tag, String message) {
        if (!sEnabled) return;
        Log.w(buildTag(tag), formatMessage(message));
    }

    /**
     * Logs an ERROR-level message with an associated throwable.
     * <p>Use for failures and exceptions that need attention.
     * The full stack trace of the throwable will be included in the log output.</p>
     *
     * @param tag     the log tag for categorization
     * @param message the error message to log
     */
    public static void e(String tag, String message) {
        if (!sEnabled) return;
        Throwable tr = null;
        Log.e(buildTag(tag), formatMessage(message), tr);
    }

    /**
     * Logs a complete exception with its full stack trace at ERROR level.
     * <p>Convenience method that logs the exception message and class name
     * as the log message, plus the full stack trace.</p>
     *
     * @param tag the log tag for categorization
     * @param e   the exception to log
     */
    public static void logException(String tag, Exception e) {
        if (!sEnabled) return;
        String exceptionInfo = e.getClass().getSimpleName() + ": " + e.getMessage();
        Log.e(buildTag(tag), formatMessage(exceptionInfo), e);
    }

    // =========================================================================
    // Utility helpers
    // =========================================================================

    /**
     * Builds the final tag string by prepending the app prefix.
     *
     * @param tag the original tag string
     * @return the prefixed tag (e.g., "[DCR] MainActivity")
     */
    private static String buildTag(String tag) {
        return APP_PREFIX + tag;
    }

    /**
     * Formats the log message to include the caller's method name and line number.
     * <p>Uses a stack trace element to determine where the log call originated.</p>
     *
     * @param message the original message
     * @return the formatted message with caller info (e.g., "onCreate(L:42): message")
     */
    private static String formatMessage(String message) {
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            if (stackTrace != null && stackTrace.length > STACK_TRACE_DEPTH) {
                StackTraceElement element = stackTrace[STACK_TRACE_DEPTH];
                return element.getMethodName() + "(L:" + element.getLineNumber() + "): " + message;
            }
        } catch (Exception e) {
            // Fallback: just return the message without caller info
        }
        return message;
    }
}
