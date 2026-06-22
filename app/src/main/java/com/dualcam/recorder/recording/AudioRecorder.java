package com.dualcam.recorder.recording;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Records audio from the microphone using {@link AudioRecord} and provides
 * the PCM data for muxing with video in the {@link VideoRecorder}.
 * <p>
 * This class handles the complete audio recording pipeline:
 * <ol>
 *   <li>Configures an AudioRecord instance with appropriate parameters</li>
 *   <li>Reads PCM audio data on a dedicated background thread</li>
 *   <li>Optionally encodes audio using MediaCodec (AAC)</li>
 *   <li>Provides audio sample data for integration with the video muxer</li>
 * </ol>
 *
 * <h3>Configuration:</h3>
 * <ul>
 *   <li>Sample rate: 44100 Hz (configurable)</li>
 *   <li>Channel config: Mono (CHANNEL_IN_MONO)</li>
 *   <li>Audio format: 16-bit PCM (ENCODING_PCM_16BIT)</li>
 *   <li>Buffer size: Calculated from sample rate and minimum buffer size</li>
 * </ul>
 *
 * <h3>Thread Model:</h3>
 * Audio recording runs on a dedicated {@link HandlerThread}. The public API
 * methods are thread-safe and can be called from any thread.
 *
 * @author DualCamRecorder
 * @version 1.0
 * @see VideoRecorder
 */
public class AudioRecorder {

    private static final String TAG = "AudioRecorder";

    /** Default audio sample rate in Hz */
    private static final int DEFAULT_SAMPLE_RATE = 44100;

    /** Default channel configuration (mono) */
    private static final int DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;

    /** Default audio encoding (16-bit PCM) */
    private static final int DEFAULT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    /** Audio source type: microphone with camcorder optimization */
    private static final int AUDIO_SOURCE = MediaRecorder.AudioSource.MIC;

    /** Buffer size multiplier for reducing underrun risk */
    private static final int BUFFER_SIZE_MULTIPLIER = 2;

    /** MIME type for AAC audio codec */
    private static final String AUDIO_MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;

    /** Default audio bitrate in bits per second */
    private static final int DEFAULT_AUDIO_BITRATE = 128_000;

    /** Timeout for MediaCodec operations in microseconds */
    private static final long CODEC_TIMEOUT_US = 10_000L;

    // =========================================================================
    // Listener interface
    // =========================================================================

    /**
     * Callback interface for receiving audio recording events.
     */
    public interface AudioRecordingListener {
        /**
         * Called when audio data is available for processing.
         *
         * @param buffer     the ByteBuffer containing PCM audio data
         * @param bufferSize the number of valid bytes in the buffer
         */
        void onAudioDataAvailable(ByteBuffer buffer, int bufferSize);

        /**
         * Called when an encoded audio frame is ready for muxing.
         *
         * @param byteBuffer  the ByteBuffer containing encoded audio data
         * @param bufferInfo  the MediaCodec.BufferInfo with presentation time and flags
         */
        void onEncodedAudioAvailable(ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo);

        /**
         * Called when an audio recording error occurs.
         *
         * @param errorMessage a human-readable error description
         */
        void onAudioError(String errorMessage);
    }

    // =========================================================================
    // Instance fields
    // =========================================================================

    /** Lock object for thread-safe access to shared state */
    private final Object mLock = new Object();

    /** Application context */
    private final Context mContext;

    /** AudioRecord instance for capturing raw PCM audio */
    private AudioRecord mAudioRecord;

    /** MediaCodec encoder for AAC audio encoding */
    private MediaCodec mAudioEncoder;

    /** Background handler thread for audio recording */
    private HandlerThread mAudioThread;

    /** Handler for posting audio operations */
    private Handler mAudioHandler;

    /** Configured sample rate in Hz */
    private int mSampleRate;

    /** Configured channel count (1 for mono, 2 for stereo) */
    private int mChannelCount;

    /** Configured channel configuration constant */
    private int mChannelConfig;

    /** Buffer size in bytes for each AudioRecord read */
    private int mBufferSize;

    /** Whether audio recording is currently active */
    private volatile boolean mIsRecording = false;

    /** Whether the recorder has been released */
    private volatile boolean mReleased = false;

    /** Whether audio encoding (AAC) is enabled */
    private boolean mEncodingEnabled = false;

    /** Listener for audio recording events */
    private AudioRecordingListener mListener;

    /** Presentation time base in nanoseconds for audio PTS calculation */
    private long mPresentationTimeBaseNs = 0;

    /** Number of bytes read since recording started (for PTS calculation) */
    private long mBytesRead = 0;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new AudioRecorder with default configuration.
     *
     * @param context the application context
     * @throws IllegalArgumentException if context is null
     */
    public AudioRecorder(Context context) {
        this(context, DEFAULT_SAMPLE_RATE);
    }

    /**
     * Creates a new AudioRecorder with a specified sample rate.
     *
     * @param context    the application context
     * @param sampleRate the desired audio sample rate in Hz (e.g., 44100, 48000)
     * @throws IllegalArgumentException if context is null or sampleRate is not positive
     */
    public AudioRecorder(Context context, int sampleRate) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("Sample rate must be positive, got: " + sampleRate);
        }

        mContext = context.getApplicationContext();
        mSampleRate = sampleRate;
        mChannelConfig = DEFAULT_CHANNEL_CONFIG;
        mChannelCount = (mChannelConfig == AudioFormat.CHANNEL_IN_MONO) ? 1 : 2;

        // Calculate buffer size based on minimum required size
        int minBufferSize = AudioRecord.getMinBufferSize(
                mSampleRate, mChannelConfig, DEFAULT_AUDIO_FORMAT);

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid buffer size from getMinBufferSize, using fallback");
            mBufferSize = sampleRate * 2 * mChannelCount; // 1 second of 16-bit audio
        } else {
            mBufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER;
        }

        Log.d(TAG, "AudioRecorder created: sampleRate=" + mSampleRate
                + ", channels=" + mChannelCount + ", bufferSize=" + mBufferSize);
    }

    // =========================================================================
    // Preparation
    // =========================================================================

    /**
     * Prepares the AudioRecord instance for recording.
     * <p>This method creates the AudioRecord and the background handler thread.
     * Must be called before {@link #start()}.</p>
     *
     * @throws IllegalStateException if the recorder has been released
     */
    public void prepare() {
        synchronized (mLock) {
            if (mReleased) {
                throw new IllegalStateException("AudioRecorder has been released");
            }

            // Release any existing resources
            releaseInternalLocked();

            try {
                // Start audio thread
                mAudioThread = new HandlerThread("AudioRecordThread");
                mAudioThread.start();
                mAudioHandler = new Handler(mAudioThread.getLooper());

                // Create AudioRecord instance
                mAudioRecord = new AudioRecord(
                        AUDIO_SOURCE,
                        mSampleRate,
                        mChannelConfig,
                        DEFAULT_AUDIO_FORMAT,
                        mBufferSize);

                int state = mAudioRecord.getState();
                if (state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize, state: " + state);
                    releaseInternalLocked();
                    notifyError("Failed to initialize AudioRecord");
                    return;
                }

                Log.d(TAG, "AudioRecord prepared: sampleRate=" + mSampleRate
                        + ", bufferSize=" + mBufferSize);

            } catch (SecurityException e) {
                Log.e(TAG, "Microphone permission not granted", e);
                releaseInternalLocked();
                notifyError("Microphone permission not granted");
            } catch (Exception e) {
                Log.e(TAG, "Error preparing AudioRecorder", e);
                releaseInternalLocked();
                notifyError("Failed to prepare AudioRecorder: " + e.getMessage());
            }
        }
    }

    /**
     * Prepares the AudioRecorder with AAC encoding enabled.
     * <p>This creates both an AudioRecord and a MediaCodec encoder for AAC.
     * Encoded frames are delivered via the {@link AudioRecordingListener#onEncodedAudioAvailable}
     * callback.</p>
     *
     * @return the MediaFormat of the audio encoder, or null on failure
     */
    public MediaFormat prepareWithEncoding() {
        prepare();

        synchronized (mLock) {
            if (mAudioRecord == null || mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Cannot create encoder: AudioRecord not initialized");
                return null;
            }

            try {
                // Create AAC encoder
                MediaFormat audioFormat = new MediaFormat();
                audioFormat.setString(MediaFormat.KEY_MIME, AUDIO_MIME_TYPE);
                audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, mSampleRate);
                audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, mChannelCount);
                audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_AUDIO_BITRATE);
                audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);

                mAudioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME_TYPE);
                mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                mEncodingEnabled = true;

                Log.d(TAG, "AAC audio encoder prepared: " + AUDIO_MIME_TYPE
                        + ", bitrate=" + DEFAULT_AUDIO_BITRATE);

                return audioFormat;

            } catch (Exception e) {
                Log.e(TAG, "Error creating AAC audio encoder", e);
                notifyError("Failed to create audio encoder: " + e.getMessage());
                return null;
            }
        }
    }

    // =========================================================================
    // Recording control
    // =========================================================================

    /**
     * Starts audio recording.
     * <p>Audio data is read from the microphone on the background thread and
     * delivered via the listener callback. Must be called after {@link #prepare()}.</p>
     *
     * @throws IllegalStateException if not prepared or already recording
     */
    public void start() {
        synchronized (mLock) {
            if (mReleased) {
                Log.e(TAG, "Cannot start: recorder has been released");
                return;
            }
            if (mAudioRecord == null) {
                Log.e(TAG, "Cannot start: AudioRecord is null. Call prepare() first.");
                throw new IllegalStateException("AudioRecorder not prepared");
            }
            if (mIsRecording) {
                Log.w(TAG, "Already recording");
                return;
            }

            try {
                // Start encoder if encoding is enabled
                if (mEncodingEnabled && mAudioEncoder != null) {
                    mAudioEncoder.start();
                    Log.d(TAG, "Audio encoder started");
                }

                mAudioRecord.startRecording();
                mIsRecording = true;
                mPresentationTimeBaseNs = System.nanoTime();
                mBytesRead = 0;

                Log.d(TAG, "Audio recording started");

                // Start the audio reading loop on the background thread
                mAudioHandler.post(this::audioRecordingLoop);

            } catch (IllegalStateException e) {
                Log.e(TAG, "AudioRecord failed to start", e);
                notifyError("Failed to start audio recording: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error starting audio recording", e);
                notifyError("Unexpected error: " + e.getMessage());
            }
        }
    }

    /**
     * Stops audio recording.
     * <p>The AudioRecord is stopped and the background reading loop exits.</p>
     */
    public void stop() {
        synchronized (mLock) {
            if (!mIsRecording) {
                Log.w(TAG, "Not recording, nothing to stop");
                return;
            }

            mIsRecording = false;
            Log.d(TAG, "Audio recording stopping...");

            try {
                if (mAudioRecord != null) {
                    mAudioRecord.stop();
                    Log.d(TAG, "AudioRecord stopped");
                }
            } catch (IllegalStateException e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }

            // Stop and drain encoder
            if (mEncodingEnabled && mAudioEncoder != null) {
                try {
                    mAudioEncoder.stop();
                    Log.d(TAG, "Audio encoder stopped");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping audio encoder", e);
                }
            }
        }
    }

    // =========================================================================
    // Audio reading loop
    // =========================================================================

    /**
     * The main audio recording loop that runs on the background thread.
     * <p>Reads PCM data from the AudioRecord and delivers it via the listener.
     * Exits when {@link #mIsRecording} becomes false.</p>
     */
    private void audioRecordingLoop() {
        Log.d(TAG, "Audio recording loop started");

        ByteBuffer readBuffer = ByteBuffer.allocateDirect(mBufferSize);

        try {
            while (mIsRecording) {
                if (mAudioRecord == null) {
                    break;
                }

                int bytesRead = mAudioRecord.read(readBuffer, mBufferSize);

                if (bytesRead <= 0) {
                    switch (bytesRead) {
                        case AudioRecord.ERROR_INVALID_OPERATION:
                            Log.e(TAG, "AudioRecord read: ERROR_INVALID_OPERATION");
                            break;
                        case AudioRecord.ERROR_BAD_VALUE:
                            Log.e(TAG, "AudioRecord read: ERROR_BAD_VALUE");
                            break;
                        case AudioRecord.ERROR_DEAD_OBJECT:
                            Log.e(TAG, "AudioRecord read: ERROR_DEAD_OBJECT");
                            mIsRecording = false;
                            notifyError("AudioRecord dead object");
                            break;
                        default:
                            if (bytesRead < 0) {
                                Log.w(TAG, "AudioRecord read returned: " + bytesRead);
                            }
                            break;
                    }
                    continue;
                }

                mBytesRead += bytesRead;

                // Calculate presentation timestamp in microseconds
                long presentationTimeUs = ((mBytesRead * 1_000_000L)
                        / (mSampleRate * mChannelCount * 2)); // 2 bytes per sample (16-bit)

                // Deliver raw PCM data to listener
                if (mListener != null && !mEncodingEnabled) {
                    ByteBuffer dataBuffer = ByteBuffer.allocate(bytesRead);
                    readBuffer.position(0);
                    readBuffer.limit(bytesRead);
                    dataBuffer.put(readBuffer);
                    dataBuffer.flip();

                    mListener.onAudioDataAvailable(dataBuffer, bytesRead);
                }

                // Encode and deliver AAC data
                if (mEncodingEnabled && mAudioEncoder != null) {
                    encodeAudioFrame(readBuffer, bytesRead, presentationTimeUs);
                }

                // Reset buffer position for next read
                readBuffer.clear();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in audio recording loop", e);
            if (mIsRecording) {
                notifyError("Audio recording error: " + e.getMessage());
            }
        }

        // Drain remaining encoder data
        if (mEncodingEnabled && mAudioEncoder != null) {
            drainAudioEncoder();
        }

        Log.d(TAG, "Audio recording loop ended. Total bytes read: " + mBytesRead);
    }

    /**
     * Encodes a buffer of PCM audio data using the AAC encoder.
     *
     * @param pcmBuffer        the ByteBuffer containing PCM data
     * @param pcmSize          the number of valid bytes in the buffer
     * @param presentationTimeUs the presentation timestamp in microseconds
     */
    private void encodeAudioFrame(ByteBuffer pcmBuffer, int pcmSize, long presentationTimeUs) {
        try {
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mAudioEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    pcmBuffer.position(0);
                    pcmBuffer.limit(pcmSize);
                    inputBuffer.put(pcmBuffer);

                    mAudioEncoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            pcmSize,
                            presentationTimeUs,
                            0);
                }
            }

            // Dequeue output
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = mAudioEncoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);

            while (outputBufferIndex >= 0) {
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Output format change - handled by VideoRecorder
                    Log.d(TAG, "Audio encoder output format changed");
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer outputBuffer = mAudioEncoder.getOutputBuffer(outputBufferIndex);
                    if (outputBuffer != null && mListener != null) {
                        mListener.onEncodedAudioAvailable(outputBuffer, bufferInfo);
                    }
                    mAudioEncoder.releaseOutputBuffer(outputBufferIndex, false);
                }

                outputBufferIndex = mAudioEncoder.dequeueOutputBuffer(bufferInfo, 0);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error encoding audio frame", e);
        }
    }

    /**
     * Drains remaining frames from the audio encoder after recording stops.
     */
    private void drainAudioEncoder() {
        if (mAudioEncoder == null) {
            return;
        }

        try {
            // Signal end of stream
            int inputBufferIndex = mAudioEncoder.dequeueInputBuffer(CODEC_TIMEOUT_US);
            if (inputBufferIndex >= 0) {
                mAudioEncoder.queueInputBuffer(
                        inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }

            // Drain remaining output
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int drainAttempts = 0;
            final int MAX_DRAIN_ATTEMPTS = 50;

            while (drainAttempts < MAX_DRAIN_ATTEMPTS) {
                int outputBufferIndex = mAudioEncoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    drainAttempts++;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    continue;
                } else if (outputBufferIndex < 0) {
                    drainAttempts++;
                    continue;
                }

                ByteBuffer outputBuffer = mAudioEncoder.getOutputBuffer(outputBufferIndex);
                if (outputBuffer != null && mListener != null) {
                    mListener.onEncodedAudioAvailable(outputBuffer, bufferInfo);
                }

                mAudioEncoder.releaseOutputBuffer(outputBufferIndex, false);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Audio encoder end of stream");
                    break;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error draining audio encoder", e);
        }
    }

    // =========================================================================
    // Data access
    // =========================================================================

    /**
     * Reads audio data from the AudioRecord into the provided buffer.
     * <p>This is a blocking read. For non-blocking reads, use the listener callback
     * approach with {@link #start()}.</p>
     *
     * @param buffer the byte array to read data into
     * @return the number of bytes read, or a negative error code
     * @throws IllegalStateException if not recording
     */
    public int read(byte[] buffer) {
        synchronized (mLock) {
            if (!mIsRecording || mAudioRecord == null) {
                Log.w(TAG, "Cannot read: not recording");
                return -1;
            }
        }

        try {
            return mAudioRecord.read(buffer, 0, buffer.length);
        } catch (Exception e) {
            Log.e(TAG, "Error reading audio data", e);
            return -1;
        }
    }

    // =========================================================================
    // Error notification
    // =========================================================================

    /**
     * Notifies the listener of an audio recording error.
     *
     * @param message the error message
     */
    private void notifyError(String message) {
        Log.e(TAG, message);
        if (mListener != null) {
            new Handler(android.os.Looper.getMainLooper()).post(
                    () -> mListener.onAudioError(message));
        }
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns whether audio recording is currently active.
     *
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Returns the configured audio sample rate in Hz.
     *
     * @return the sample rate (e.g., 44100)
     */
    public int getSampleRate() {
        return mSampleRate;
    }

    /**
     * Returns the number of audio channels (1 for mono, 2 for stereo).
     *
     * @return the channel count
     */
    public int getChannelCount() {
        return mChannelCount;
    }

    /**
     * Returns the configured buffer size in bytes.
     *
     * @return the buffer size
     */
    public int getBufferSize() {
        return mBufferSize;
    }

    /**
     * Returns the AudioRecord instance, or null if not prepared.
     * <p>Use with caution. Prefer the listener-based API for audio data access.</p>
     *
     * @return the AudioRecord, or null
     */
    public AudioRecord getAudioRecord() {
        synchronized (mLock) {
            return mAudioRecord;
        }
    }

    /**
     * Returns the MediaCodec audio encoder, or null if encoding is not enabled.
     *
     * @return the MediaCodec encoder, or null
     */
    public MediaCodec getAudioEncoder() {
        synchronized (mLock) {
            return mAudioEncoder;
        }
    }

    /**
     * Sets the listener for audio recording events.
     *
     * @param listener the listener, or null to remove
     */
    public void setAudioRecordingListener(AudioRecordingListener listener) {
        mListener = listener;
    }

    // =========================================================================
    // Internal cleanup (must hold mLock)
    // =========================================================================

    /**
     * Internal cleanup method. Must be called while holding {@link #mLock}.
     */
    private void releaseInternalLocked() {
        try {
            if (mAudioEncoder != null) {
                try {
                    mAudioEncoder.stop();
                } catch (Exception e) {
                    // Ignore
                }
                try {
                    mAudioEncoder.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing audio encoder", e);
                }
                mAudioEncoder = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during encoder cleanup", e);
        }

        try {
            if (mAudioRecord != null) {
                if (mAudioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    try {
                        mAudioRecord.stop();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
                try {
                    mAudioRecord.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing AudioRecord", e);
                }
                mAudioRecord = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during AudioRecord cleanup", e);
        }

        mIsRecording = false;
        mEncodingEnabled = false;
    }

    // =========================================================================
    // Public release
    // =========================================================================

    /**
     * Releases all resources held by the AudioRecorder.
     * <p>Can be called from any thread. After calling this method, the recorder
     * must not be used again.</p>
     */
    public void release() {
        Log.d(TAG, "Releasing AudioRecorder");

        synchronized (mLock) {
            if (mReleased) {
                Log.w(TAG, "release() called but recorder is already released");
                return;
            }
            mReleased = true;

            mIsRecording = false;
            releaseInternalLocked();
        }

        // Stop audio thread outside the lock
        if (mAudioThread != null) {
            try {
                mAudioThread.quitSafely();
                mAudioThread.join(3000);
                Log.d(TAG, "Audio thread stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for audio thread", e);
                Thread.currentThread().interrupt();
            }
            mAudioThread = null;
            mAudioHandler = null;
        }

        mListener = null;
        Log.d(TAG, "AudioRecorder fully released");
    }
}