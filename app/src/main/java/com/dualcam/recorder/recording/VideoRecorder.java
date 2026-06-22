package com.dualcam.recorder.recording;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Records video from the composed OpenGL output using MediaCodec (H.264/AVC) and
 * MediaMuxer to produce MP4 files.
 * <p>
 * This class manages the complete video encoding pipeline:
 * <ol>
 *   <li>Configures a MediaCodec encoder with H.264 codec</li>
 *   <li>Receives frames from the OpenGL renderer via an input {@link Surface}</li>
 *   <li>Encodes frames on a dedicated background thread</li>
 *   <li>Muxes encoded video (and optional audio) into an MP4 container</li>
 *   <li>Supports pause/resume and recording duration tracking</li>
 * </ol>
 *
 * <h3>Encoder Configuration:</h3>
 * <ul>
 *   <li>Codec: H.264/AVC ({@link MediaFormat#MIMETYPE_VIDEO_AVC})</li>
 *   <li>Color format: {@link MediaCodecInfo.CodecCapabilities#COLOR_FormatSurface}</li>
 *   <li>I-frame interval: 2 seconds</li>
 *   <li>Bitrate: 8-20 Mbps (configurable)</li>
 *   <li>Frame rate: 30 fps (configurable)</li>
 * </ul>
 *
 * <h3>Thread Model:</h3>
 * All encoding and muxing operations run on a dedicated {@link HandlerThread}.
 * The public API methods are thread-safe and can be called from any thread.
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class VideoRecorder {

    private static final String TAG = "VideoRecorder";

    /** MIME type for H.264/AVC video codec */
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;

    /** Default timeout for MediaCodec operations in microseconds */
    private static final long CODEC_TIMEOUT_US = 10_000L;

    // =========================================================================
    // Recording state enum
    // =========================================================================

    /**
     * Enum representing the current state of the video recorder state machine.
     */
    public enum RecorderState {
        /** Recorder is idle and not prepared */
        IDLE,
        /** Recorder is configured and ready to start */
        PREPARED,
        /** Recorder is actively encoding and muxing frames */
        RECORDING,
        /** Recording is paused (encoder is still running but frames are dropped) */
        PAUSED,
        /** Recorder has been stopped and the output file is finalized */
        STOPPED
    }

    // =========================================================================
    // Listener interface
    // =========================================================================

    /**
     * Callback interface for receiving recording state change events.
     */
    public interface OnRecordingStateListener {
        /**
         * Called when the recorder state changes.
         *
         * @param newState the new recorder state
         */
        void onStateChanged(RecorderState newState);

        /**
         * Called when an error occurs during recording.
         *
         * @param errorMessage a human-readable error description
         * @param exception    the exception that caused the error, or null
         */
        void onError(String errorMessage, Exception exception);

        /**
         * Called when recording stops and the output file is ready.
         *
         * @param outputPath the absolute path to the output MP4 file
         * @param durationMs the total recording duration in milliseconds
         */
        void onRecordingComplete(String outputPath, long durationMs);
    }

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Lock for synchronizing access to shared state */
    private final Object mLock = new Object();

    /** Application context */
    private final Context mContext;

    /** MediaCodec encoder instance */
    private MediaCodec mEncoder;

    /** MediaMuxer instance for MP4 container */
    private MediaMuxer mMuxer;

    /** Input surface that the GL renderer draws to for encoding */
    private Surface mInputSurface;

    /** Background handler thread for encoding operations */
    private HandlerThread mEncoderThread;

    /** Handler for posting encoding operations */
    private Handler mEncoderHandler;

    /** Current recorder state */
    private volatile RecorderState mState = RecorderState.IDLE;

    /** Video width in pixels */
    private int mWidth;

    /** Video height in pixels */
    private int mHeight;

    /** Video bitrate in bits per second */
    private int mBitRate;

    /** Target frame rate in fps */
    private int mFrameRate;

    /** I-frame interval in seconds */
    private int mIFrameInterval;

    /** Output file path for the recorded MP4 */
    private String mOutputPath;

    /** Muxer track index for the video stream */
    private int mVideoTrackIndex = -1;

    /** Whether the muxer has started (at least one track added) */
    private boolean mMuxerStarted = false;

    /** BufferInfo for dequeueing encoded output buffers */
    private final MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    /** Recording start timestamp in milliseconds */
    private long mRecordStartTimeMs;

    /** Total paused duration in milliseconds (subtracted from total duration) */
    private long mPausedDurationMs;

    /** Timestamp when the current pause began */
    private long mPauseStartTimeMs;

    /** Listener for recording state events */
    private OnRecordingStateListener mStateListener;

    /** Whether audio is enabled for this recording session */
    private boolean mAudioEnabled = false;

    /** Audio track index in the muxer */
    private int mAudioTrackIndex = -1;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new VideoRecorder.
     *
     * @param context the application context
     * @throws IllegalArgumentException if context is null
     */
    public VideoRecorder(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        mContext = context.getApplicationContext();
        Log.d(TAG, "VideoRecorder created");
    }

    // =========================================================================
    // Preparation
    // =========================================================================

    /**
     * Prepares the video encoder and muxer with the given configuration.
     * <p>Must be called before {@link #start()}. The encoder is configured but not
     * started until {@link #start()} is called.</p>
     *
     * @param width      the video width in pixels
     * @param height     the video height in pixels
     * @param bitRate    the target bitrate in bits per second
     * @param outputPath the absolute path for the output MP4 file
     * @throws IllegalStateException    if the recorder is not in IDLE state
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public void prepare(int width, int height, int bitRate, String outputPath) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Invalid dimensions: " + width + "x" + height);
        }
        if (bitRate <= 0) {
            throw new IllegalArgumentException("Bit rate must be positive, got: " + bitRate);
        }
        if (outputPath == null || outputPath.isEmpty()) {
            throw new IllegalArgumentException("Output path must not be null or empty");
        }

        synchronized (mLock) {
            if (mState != RecorderState.IDLE) {
                throw new IllegalStateException("Cannot prepare: current state is " + mState
                        + ", expected IDLE");
            }

            // Release any previous resources
            releaseInternalLocked();

            mWidth = width;
            mHeight = height;
            mBitRate = bitRate;
            mFrameRate = 30;
            mIFrameInterval = 2;
            mOutputPath = outputPath;
            mMuxerStarted = false;
            mVideoTrackIndex = -1;
            mAudioTrackIndex = -1;

            try {
                // Start encoder thread
                mEncoderThread = new HandlerThread("VideoEncoderThread");
                mEncoderThread.start();
                mEncoderHandler = new Handler(mEncoderThread.getLooper());

                // Configure the video encoder
                MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, mIFrameInterval);

                // Try to set high quality profile if available
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not set VBR bitrate mode", e);
                    }
                }

                // Create encoder
                mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                // Get the input surface for the GL renderer
                mInputSurface = mEncoder.createInputSurface();

                Log.d(TAG, "Video encoder prepared: " + width + "x" + height
                        + ", bitrate=" + bitRate + "bps, fps=" + mFrameRate);

                // Create output directory if needed
                File outputFile = new File(outputPath);
                File outputDir = outputFile.getParentFile();
                if (outputDir != null && !outputDir.exists()) {
                    boolean created = outputDir.mkdirs();
                    Log.d(TAG, "Output directory created: " + created);
                }

                // Create muxer
                mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                Log.d(TAG, "MediaMuxer created for output: " + outputPath);

                setState(RecorderState.PREPARED);

            } catch (Exception e) {
                Log.e(TAG, "Error preparing video recorder", e);
                releaseInternalLocked();
                setState(RecorderState.IDLE);
                notifyError("Failed to prepare video recorder: " + e.getMessage(), e);
            }
        }
    }

    // =========================================================================
    // Recording control
    // =========================================================================

    /**
     * Starts video recording.
     * <p>The encoder begins processing frames from the input surface. The GL renderer
     * should start drawing to the {@link #getInputSurface()} for frames to be encoded.</p>
     *
     * @throws IllegalStateException if the recorder is not in PREPARED state
     */
    public void start() {
        synchronized (mLock) {
            if (mState != RecorderState.PREPARED) {
                Log.e(TAG, "Cannot start recording: current state is " + mState);
                throw new IllegalStateException("Cannot start: current state is " + mState);
            }

            if (mEncoder == null) {
                Log.e(TAG, "Cannot start: encoder is null");
                notifyError("Encoder is null", null);
                return;
            }

            try {
                mEncoder.start();
                mRecordStartTimeMs = System.currentTimeMillis();
                mPausedDurationMs = 0;

                setState(RecorderState.RECORDING);
                Log.d(TAG, "Recording started: " + mOutputPath);

                // Start the encoding loop on the background thread
                mEncoderHandler.post(this::encodingLoop);

            } catch (Exception e) {
                Log.e(TAG, "Error starting recording", e);
                notifyError("Failed to start recording: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Stops recording and finalizes the output file.
     * <p>This method signals the encoder to stop, drains remaining frames, and
     * finalizes the MP4 file. The listener is notified when complete.</p>
     *
     * @throws IllegalStateException if the recorder is not in RECORDING or PAUSED state
     */
    public void stop() {
        synchronized (mLock) {
            if (mState != RecorderState.RECORDING && mState != RecorderState.PAUSED) {
                Log.w(TAG, "Cannot stop: current state is " + mState);
                return;
            }

            Log.d(TAG, "Stopping recording...");
            setState(RecorderState.STOPPED);
        }

        // Signal encoder to stop on the encoder thread
        if (mEncoderHandler != null) {
            mEncoderHandler.post(this::drainAndReleaseEncoder);
        }
    }

    /**
     * Pauses the current recording.
     * <p>The encoder remains running but frames are not muxed to the output file.
     * Pause duration is tracked so the final video duration is accurate.</p>
     *
     * @throws IllegalStateException if the recorder is not in RECORDING state
     */
    public void pause() {
        synchronized (mLock) {
            if (mState != RecorderState.RECORDING) {
                Log.w(TAG, "Cannot pause: current state is " + mState);
                return;
            }
            mPauseStartTimeMs = System.currentTimeMillis();
            setState(RecorderState.PAUSED);
            Log.d(TAG, "Recording paused");
        }
    }

    /**
     * Resumes a paused recording.
     *
     * @throws IllegalStateException if the recorder is not in PAUSED state
     */
    public void resume() {
        synchronized (mLock) {
            if (mState != RecorderState.PAUSED) {
                Log.w(TAG, "Cannot resume: current state is " + mState);
                return;
            }
            if (mPauseStartTimeMs > 0) {
                mPausedDurationMs += System.currentTimeMillis() - mPauseStartTimeMs;
                mPauseStartTimeMs = 0;
            }
            setState(RecorderState.RECORDING);
            Log.d(TAG, "Recording resumed");
        }
    }

    // =========================================================================
    // Encoding loop
    // =========================================================================

    /**
     * The main encoding loop that runs on the background encoder thread.
     * <p>Dequeues encoded output buffers from the MediaCodec and writes them
     * to the MediaMuxer. Exits when the recorder state is no longer RECORDING or PAUSED.</p>
     */
    private void encodingLoop() {
        Log.d(TAG, "Encoding loop started");

        try {
            while (true) {
                RecorderState currentState;
                synchronized (mLock) {
                    currentState = mState;
                }

                if (currentState != RecorderState.RECORDING && currentState != RecorderState.PAUSED) {
                    Log.d(TAG, "Encoding loop exiting: state=" + currentState);
                    break;
                }

                // Dequeue an output buffer
                int outputBufferIndex = mEncoder.dequeueOutputBuffer(mBufferInfo, CODEC_TIMEOUT_US);

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // No output available yet
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format changed (should happen once at the start)
                    MediaFormat newFormat = mEncoder.getOutputFormat();
                    Log.d(TAG, "Encoder output format changed: " + newFormat);

                    synchronized (mLock) {
                        if (!mMuxerStarted && mMuxer != null) {
                            mVideoTrackIndex = mMuxer.addTrack(newFormat);
                            Log.d(TAG, "Video track added to muxer, index=" + mVideoTrackIndex);

                            // Start muxer if both video and audio tracks are ready
                            // (or if audio is not enabled)
                            if (!mAudioEnabled || mAudioTrackIndex >= 0) {
                                mMuxer.start();
                                mMuxerStarted = true;
                                Log.d(TAG, "MediaMuxer started");
                            }
                        }
                    }
                    continue;
                } else if (outputBufferIndex < 0) {
                    Log.w(TAG, "Unexpected dequeueOutputBuffer return: " + outputBufferIndex);
                    continue;
                }

                // Got a valid output buffer
                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                if (outputBuffer == null) {
                    Log.w(TAG, "Output buffer is null for index: " + outputBufferIndex);
                    mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }

                // Skip muxing if paused (but still release the buffer to the encoder)
                if (currentState == RecorderState.PAUSED) {
                    mEncoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }

                // Write the encoded data to the muxer
                synchronized (mLock) {
                    if (mMuxerStarted && mMuxer != null) {
                        outputBuffer.position(mBufferInfo.offset);
                        outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);

                        try {
                            mMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, mBufferInfo);
                        } catch (Exception e) {
                            Log.e(TAG, "Error writing sample data to muxer", e);
                        }
                    }
                }

                // Release the buffer back to the encoder
                mEncoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in encoding loop", e);
            if (mState == RecorderState.RECORDING || mState == RecorderState.PAUSED) {
                notifyError("Encoding error: " + e.getMessage(), e);
            }
        }

        Log.d(TAG, "Encoding loop ended");
    }

    /**
     * Drains remaining frames from the encoder, stops the muxer, and releases resources.
     * <p>This must be called on the encoder thread after the encoding loop exits.</p>
     */
    private void drainAndReleaseEncoder() {
        Log.d(TAG, "Draining encoder and finalizing output");

        try {
            if (mEncoder == null) {
                return;
            }

            // Signal end-of-stream
            mEncoder.signalEndOfInputStream();

            // Drain remaining output buffers
            drainEncoder();

            // Stop the encoder
            try {
                mEncoder.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping encoder", e);
            }

            // Stop the muxer
            synchronized (mLock) {
                if (mMuxer != null && mMuxerStarted) {
                    try {
                        mMuxer.stop();
                        Log.d(TAG, "MediaMuxer stopped");
                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping muxer", e);
                    }
                    try {
                        mMuxer.release();
                        Log.d(TAG, "MediaMuxer released");
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing muxer", e);
                    }
                    mMuxer = null;
                    mMuxerStarted = false;
                }
            }

            // Notify completion
            long durationMs = getDuration();
            Log.d(TAG, "Recording complete: " + mOutputPath + ", duration=" + durationMs + "ms");
            if (mStateListener != null) {
                // Post to main thread
                new Handler(android.os.Looper.getMainLooper()).post(
                        () -> mStateListener.onRecordingComplete(mOutputPath, durationMs));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in drainAndReleaseEncoder", e);
            notifyError("Error finalizing recording: " + e.getMessage(), e);
        }
    }

    /**
     * Drains all remaining encoded frames from the encoder after end-of-stream is signaled.
     */
    private void drainEncoder() {
        if (mEncoder == null) {
            return;
        }

        try {
            long drainTimeout = 10_000L; // 10 seconds max drain time
            while (true) {
                int outputBufferIndex = mEncoder.dequeueOutputBuffer(mBufferInfo, CODEC_TIMEOUT_US);

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Wait a bit and try again
                    try {
                        Thread.sleep(10);
                        drainTimeout -= 10;
                        if (drainTimeout <= 0) {
                            Log.w(TAG, "Drain timeout reached");
                            break;
                        }
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Drain interrupted");
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Ignore format changes during drain
                    continue;
                } else if (outputBufferIndex < 0) {
                    continue;
                }

                ByteBuffer outputBuffer = mEncoder.getOutputBuffer(outputBufferIndex);
                if (outputBuffer != null && mMuxerStarted && mMuxer != null) {
                    synchronized (mLock) {
                        outputBuffer.position(mBufferInfo.offset);
                        outputBuffer.limit(mBufferInfo.offset + mBufferInfo.size);
                        try {
                            mMuxer.writeSampleData(mVideoTrackIndex, outputBuffer, mBufferInfo);
                        } catch (Exception e) {
                            Log.e(TAG, "Error writing drain sample", e);
                        }
                    }
                }

                mEncoder.releaseOutputBuffer(outputBufferIndex, false);

                // Check for end-of-stream flag
                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "End of stream reached in drain");
                    break;
                }
            }
        } catch (IllegalStateException e) {
            Log.e(TAG, "Encoder not in executing state during drain", e);
        }
    }

    // =========================================================================
    // Audio track integration
    // =========================================================================

    /**
     * Adds an audio track to the muxer from the AudioRecorder.
     * <p>This should be called after the audio format is determined but before
     * the muxer is started (i.e., before the first video frame is available).</p>
     *
     * @param audioFormat the MediaFormat describing the audio stream
     * @return the audio track index in the muxer, or -1 on failure
     */
    public int addAudioTrack(MediaFormat audioFormat) {
        if (audioFormat == null) {
            Log.e(TAG, "addAudioTrack: audio format is null");
            return -1;
        }

        synchronized (mLock) {
            if (mMuxer == null || mMuxerStarted) {
                Log.e(TAG, "Cannot add audio track: muxer is null or already started");
                return -1;
            }

            try {
                mAudioTrackIndex = mMuxer.addTrack(audioFormat);
                mAudioEnabled = true;
                Log.d(TAG, "Audio track added to muxer, index=" + mAudioTrackIndex);

                // Start muxer now that both tracks are ready
                if (mVideoTrackIndex >= 0) {
                    mMuxer.start();
                    mMuxerStarted = true;
                    Log.d(TAG, "MediaMuxer started with audio");
                }

                return mAudioTrackIndex;
            } catch (Exception e) {
                Log.e(TAG, "Error adding audio track to muxer", e);
                return -1;
            }
        }
    }

    /**
     * Writes audio sample data to the muxer.
     * <p>Called from the AudioRecorder when audio data is available.</p>
     *
     * @param byteBuf   the ByteBuffer containing audio data
     * @param bufferInfo the BufferInfo containing presentation time and size
     */
    public void writeAudioSampleData(ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
        if (byteBuf == null || bufferInfo == null) {
            return;
        }

        synchronized (mLock) {
            if (!mMuxerStarted || mMuxer == null || mAudioTrackIndex < 0) {
                return;
            }

            try {
                byteBuf.position(bufferInfo.offset);
                byteBuf.limit(bufferInfo.offset + bufferInfo.size);
                mMuxer.writeSampleData(mAudioTrackIndex, byteBuf, bufferInfo);
            } catch (Exception e) {
                Log.e(TAG, "Error writing audio sample data", e);
            }
        }
    }

    // =========================================================================
    // State management
    // =========================================================================

    /**
     * Updates the recorder state and notifies the listener.
     *
     * @param newState the new recorder state
     */
    private void setState(RecorderState newState) {
        mState = newState;
        Log.d(TAG, "State changed to: " + newState);
        if (mStateListener != null) {
            new Handler(android.os.Looper.getMainLooper()).post(
                    () -> mStateListener.onStateChanged(newState));
        }
    }

    /**
     * Notifies the listener of an error.
     *
     * @param message  the error message
     * @param exception the causing exception, or null
     */
    private void notifyError(String message, Exception exception) {
        Log.e(TAG, message, exception);
        if (mStateListener != null) {
            new Handler(android.os.Looper.getMainLooper()).post(
                    () -> mStateListener.onError(message, exception));
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the input surface that the GL renderer should draw to for encoding.
     * <p>Only valid after {@link #prepare} has been called.</p>
     *
     * @return the encoder input Surface, or null if not prepared
     */
    public Surface getInputSurface() {
        synchronized (mLock) {
            return mInputSurface;
        }
    }

    /**
     * Returns whether the recorder is currently in the RECORDING state.
     *
     * @return true if recording is active, false otherwise
     */
    public boolean isRecording() {
        return mState == RecorderState.RECORDING;
    }

    /**
     * Returns the current recorder state.
     *
     * @return the current {@link RecorderState}
     */
    public RecorderState getState() {
        return mState;
    }

    /**
     * Returns the total recording duration in milliseconds.
     * <p>Excludes paused time for accurate duration reporting.</p>
     *
     * @return the duration in milliseconds, or 0 if not recording
     */
    public long getDuration() {
        if (mRecordStartTimeMs <= 0) {
            return 0;
        }

        long totalDuration = System.currentTimeMillis() - mRecordStartTimeMs - mPausedDurationMs;

        // If currently paused, subtract the ongoing pause duration
        if (mState == RecorderState.PAUSED && mPauseStartTimeMs > 0) {
            totalDuration -= (System.currentTimeMillis() - mPauseStartTimeMs);
        }

        return Math.max(0, totalDuration);
    }

    /**
     * Returns the output file path.
     *
     * @return the output path string, or null if not set
     */
    public String getOutputPath() {
        synchronized (mLock) {
            return mOutputPath;
        }
    }

    /**
     * Returns the muxer instance for audio integration.
     * <p>This should be used with caution. Prefer using {@link #addAudioTrack}
     * and {@link #writeAudioSampleData} instead.</p>
     *
     * @return the MediaMuxer, or null if not prepared
     */
    public MediaMuxer getMuxer() {
        synchronized (mLock) {
            return mMuxer;
        }
    }

    /**
     * Returns the video track index.
     *
     * @return the video track index, or -1 if not available
     */
    public int getVideoTrackIndex() {
        synchronized (mLock) {
            return mVideoTrackIndex;
        }
    }

    /**
     * Sets the listener for recording state events.
     *
     * @param listener the listener, or null to remove
     */
    public void setOnRecordingStateListener(OnRecordingStateListener listener) {
        mStateListener = listener;
    }

    // =========================================================================
    // Resource cleanup
    // =========================================================================

    /**
     * Internal cleanup method. Must be called while holding {@link #mLock}.
     */
    private void releaseInternalLocked() {
        try {
            if (mEncoder != null) {
                try {
                    mEncoder.stop();
                } catch (Exception e) {
                    // Ignore - encoder may not be started
                }
                try {
                    mEncoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing encoder", e);
                }
                mEncoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during encoder cleanup", e);
        }

        try {
            if (mMuxer != null) {
                try {
                    if (mMuxerStarted) {
                        mMuxer.stop();
                    }
                } catch (Exception e) {
                    // Ignore - muxer may not be started
                }
                try {
                    mMuxer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing muxer", e);
                }
                mMuxer = null;
                mMuxerStarted = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during muxer cleanup", e);
        }

        if (mInputSurface != null) {
            try {
                mInputSurface.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing input surface", e);
            }
            mInputSurface = null;
        }

        mVideoTrackIndex = -1;
        mAudioTrackIndex = -1;
    }

    /**
     * Releases all resources held by the video recorder.
     * <p>Can be called from any thread. After calling this method, the recorder
     * should not be used again.</p>
     */
    public void release() {
        Log.d(TAG, "Releasing VideoRecorder");

        synchronized (mLock) {
            if (mState == RecorderState.RECORDING) {
                Log.w(TAG, "Releasing while recording - stopping first");
                setState(RecorderState.STOPPED);
            }

            releaseInternalLocked();
            setState(RecorderState.IDLE);
        }

        // Stop encoder thread outside the lock
        if (mEncoderThread != null) {
            try {
                mEncoderThread.quitSafely();
                mEncoderThread.join(3000);
                Log.d(TAG, "Encoder thread stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for encoder thread", e);
                Thread.currentThread().interrupt();
            }
            mEncoderThread = null;
            mEncoderHandler = null;
        }

        mStateListener = null;
        Log.d(TAG, "VideoRecorder fully released");
    }
}