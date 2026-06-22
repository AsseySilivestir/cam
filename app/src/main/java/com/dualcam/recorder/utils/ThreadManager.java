package com.dualcam.recorder.utils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages thread pools for the DualCamRecorder application.
 * <p>
 * Provides three specialized executor services tailored to the app's concurrency needs:
 * <ul>
 *   <li><b>Camera Pool</b> — Fixed thread pool (2 threads) for concurrent camera operations
 *       such as simultaneous photo capture from front and back cameras.</li>
 *   <li><b>I/O Pool</b> — Single-thread executor for sequential file I/O operations
 *       (saving photos/videos, MediaStore inserts) to avoid disk contention.</li>
 *   <li><b>Scheduled Pool</b> — Scheduled executor for timed tasks like recording
 *       duration updates, countdown timers, and periodic checks.</li>
 * </ul>
 * </p>
 *
 * <h3>Thread Safety:</h3>
 * All public methods are thread-safe. The executor references are stored in volatile fields.
 *
 * <h3>Lifecycle:</h3>
 * Call {@link #shutdown()} during Activity.onPause/onDestroy to gracefully stop all pools.
 * Call {@link #shutdownNow()} for immediate termination during force-close scenarios.
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * ThreadManager threadManager = new ThreadManager();
 *
 * // Execute concurrent camera operations
 * threadManager.executeOnCameraPool(() -> captureBackCamera());
 * threadManager.executeOnCameraPool(() -> captureFrontCamera());
 *
 * // Execute file I/O
 * threadManager.executeOnIOPool(() -> saveVideoToFile(outputPath));
 *
 * // Schedule a repeating timer
 * threadManager.getScheduledPool().scheduleAtFixedRate(
 *     () -> updateRecordingTimer(), 1, 1, TimeUnit.SECONDS);
 *
 * // Cleanup
 * threadManager.shutdown();
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class ThreadManager {

    // =========================================================================
    // Constants
    // =========================================================================

    /** Tag for logging */
    private static final String TAG = "ThreadManager";

    /** Number of threads in the camera operations pool (one per camera) */
    private static final int CAMERA_POOL_SIZE = 2;

    /** Timeout in seconds for graceful shutdown */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5;

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Fixed thread pool for concurrent camera operations (2 threads) */
    private volatile ExecutorService mCameraPool;

    /** Single-thread executor for sequential file I/O operations */
    private volatile ExecutorService mIOPool;

    /** Scheduled thread pool for timed and recurring tasks */
    private volatile ScheduledExecutorService mScheduledPool;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new ThreadManager and initializes all three thread pools.
     * <p>The camera pool is created with {@link Executors#newFixedThreadPool}(2),
     * the I/O pool with {@link Executors#newSingleThreadExecutor()}, and the
     * scheduled pool with {@link Executors#newScheduledThreadPool}(1).</p>
     */
    public ThreadManager() {
        mCameraPool = Executors.newFixedThreadPool(CAMERA_POOL_SIZE, r -> {
            Thread thread = new Thread(r, "CameraPool-Thread");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });

        mIOPool = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "IOPool-Thread");
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });

        mScheduledPool = Executors.newScheduledThreadPool(1, r -> {
            Thread thread = new Thread(r, "ScheduledPool-Thread");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });

        AppLogger.i(TAG, "ThreadManager initialized with 3 pools");
    }

    // =========================================================================
    // Executor accessors
    // =========================================================================

    /**
     * Returns the camera operations thread pool.
     * <p>This is a fixed thread pool with 2 threads, suitable for concurrent
     * operations on both cameras simultaneously (e.g., dual photo capture).</p>
     *
     * @return the {@link ExecutorService} for camera operations
     * @throws IllegalStateException if the pool has been shut down
     */
    public ExecutorService getCameraPool() {
        if (mCameraPool == null || mCameraPool.isShutdown()) {
            throw new IllegalStateException("Camera pool has been shut down");
        }
        return mCameraPool;
    }

    /**
     * Returns the file I/O thread pool.
     * <p>This is a single-thread executor ensuring sequential I/O operations
     * to avoid disk contention and maintain ordering.</p>
     *
     * @return the {@link ExecutorService} for I/O operations
     * @throws IllegalStateException if the pool has been shut down
     */
    public ExecutorService getIOPool() {
        if (mIOPool == null || mIOPool.isShutdown()) {
            throw new IllegalStateException("I/O pool has been shut down");
        }
        return mIOPool;
    }

    /**
     * Returns the scheduled executor service.
     * <p>Suitable for recording timers, periodic status checks, and delayed tasks.</p>
     *
     * @return the {@link ScheduledExecutorService} for timed tasks
     * @throws IllegalStateException if the pool has been shut down
     */
    public ScheduledExecutorService getScheduledPool() {
        if (mScheduledPool == null || mScheduledPool.isShutdown()) {
            throw new IllegalStateException("Scheduled pool has been shut down");
        }
        return mScheduledPool;
    }

    // =========================================================================
    // Convenience execution methods
    // =========================================================================

    /**
     * Executes the given task on the camera operations thread pool.
     * <p>Ideal for concurrent camera operations such as simultaneous photo capture.</p>
     *
     * @param task the {@link Runnable} to execute
     * @throws IllegalArgumentException if task is null
     */
    public void executeOnCameraPool(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("Task must not be null");
        }
        try {
            getCameraPool().execute(task);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to execute task on camera pool");
        }
    }

    /**
     * Executes the given task on the file I/O thread pool.
     * <p>Ideal for saving files, writing to MediaStore, and other I/O operations.</p>
     *
     * @param task the {@link Runnable} to execute
     * @throws IllegalArgumentException if task is null
     */
    public void executeOnIOPool(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("Task must not be null");
        }
        try {
            getIOPool().execute(task);
        } catch (Exception e) {
            AppLogger.e(TAG, "Failed to execute task on I/O pool");
        }
    }

    // =========================================================================
    // Shutdown
    // =========================================================================

    /**
     * Gracefully shuts down all thread pools.
     * <p>Submits previously queued tasks are executed, but no new tasks will be accepted.
     * Waits up to {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds for each pool to terminate.</p>
     * <p>This should be called in {@code Activity.onPause()} or {@code Activity.onDestroy()}.</p>
     */
    public void shutdown() {
        AppLogger.i(TAG, "Shutting down all thread pools...");

        shutdownExecutor(mCameraPool, "CameraPool");
        shutdownExecutor(mIOPool, "IOPool");

        if (mScheduledPool != null) {
            mScheduledPool.shutdown();
            try {
                if (!mScheduledPool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    mScheduledPool.shutdownNow();
                    AppLogger.w(TAG, "ScheduledPool forced shutdown after timeout");
                }
            } catch (InterruptedException e) {
                mScheduledPool.shutdownNow();
                Thread.currentThread().interrupt();
                AppLogger.w(TAG, "ScheduledPool shutdown interrupted");
            }
            mScheduledPool = null;
        }

        AppLogger.i(TAG, "All thread pools shut down");
    }

    /**
     * Forces immediate shutdown of all thread pools.
     * <p>Attempts to stop all actively executing tasks and halts the processing
     * of waiting tasks. This should only be used in emergency shutdown scenarios.</p>
     */
    public void shutdownNow() {
        AppLogger.w(TAG, "Force shutting down all thread pools...");

        shutdownExecutorNow(mCameraPool, "CameraPool");
        shutdownExecutorNow(mIOPool, "IOPool");

        if (mScheduledPool != null) {
            mScheduledPool.shutdownNow();
            mScheduledPool = null;
        }

        AppLogger.w(TAG, "All thread pools force-shut down");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Gracefully shuts down a single executor service with timeout.
     *
     * @param executor the executor to shut down
     * @param name     the name of the executor for logging purposes
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null) return;
        try {
            executor.shutdown();
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                AppLogger.w(TAG, name + " forced shutdown after timeout");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            AppLogger.w(TAG, name + " shutdown interrupted");
        } catch (Exception e) {
            AppLogger.e(TAG, "Error shutting down " + name);
        }
    }

    /**
     * Immediately shuts down a single executor service.
     *
     * @param executor the executor to shut down
     * @param name     the name of the executor for logging purposes
     */
    private void shutdownExecutorNow(ExecutorService executor, String name) {
        if (executor == null) return;
        try {
            executor.shutdownNow();
        } catch (Exception e) {
            AppLogger.e(TAG, "Error force-shutting down " + name);
        }
    }
}
