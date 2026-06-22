package com.dualcam.recorder.camera;

/**
 * CameraConfig - Holds all camera and recording configuration parameters.
 * Provides preset factory methods for common social media aspect ratios.
 * <p>
 * This class is designed as a mutable configuration holder that can be passed
 * to {@link DualCameraManager} and other components to control camera behavior,
 * resolution, aspect ratio, and recording quality.
 * </p>
 *
 * <h3>Aspect Ratios:</h3>
 * <ul>
 *   <li>{@code RATIO_9_16}  (9:16)  - Portrait, ideal for TikTok, Reels, Shorts</li>
 *   <li>{@code RATIO_1_1}   (1:1)   - Square, ideal for Instagram feed posts</li>
 *   <li>{@code RATIO_16_9}  (16:9)  - Landscape, ideal for Facebook, YouTube</li>
 *   <li>{@code RATIO_4_3}   (4:3)   - Standard, classic camera ratio</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * // Use a preset for TikTok-style recording
 * CameraConfig config = CameraConfig.tikTokPreset();
 *
 * // Or build custom configuration
 * CameraConfig config = new CameraConfig.Builder()
 *     .setCameraMode(CameraConfig.CameraMode.DUAL_CAMERA)
 *     .setAspectRatio(CameraConfig.AspectRatio.RATIO_1_1)
 *     .setMicEnabled(true)
 *     .build();
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class CameraConfig {

    private static final String TAG = "CameraConfig";

    /**
     * Enum defining supported aspect ratios for different social media platforms.
     * Each constant carries its width-to-height ratio and a human-readable label.
     */
    public enum AspectRatio {
        /** 9:16 portrait ratio, ideal for TikTok, Instagram Reels, YouTube Shorts */
        RATIO_9_16(9f, 16f, "9:16 (TikTok/Reels)"),
        /** 1:1 square ratio, ideal for Instagram feed posts */
        RATIO_1_1(1f, 1f, "1:1 (Instagram)"),
        /** 16:9 landscape ratio, ideal for Facebook, YouTube */
        RATIO_16_9(16f, 9f, "16:9 (YouTube/Facebook)"),
        /** 4:3 standard ratio, classic camera aspect ratio */
        RATIO_4_3(4f, 3f, "4:3 (Standard)");

        /** Width component of the aspect ratio */
        public final float widthRatio;
        /** Height component of the aspect ratio */
        public final float heightRatio;
        /** Human-readable label describing this aspect ratio and its typical use case */
        public final String label;

        AspectRatio(float w, float h, String label) {
            this.widthRatio = w;
            this.heightRatio = h;
            this.label = label;
        }

        /**
         * Returns the aspect ratio as a floating point value (width / height).
         *
         * @return the aspect ratio value
         */
        public float getRatio() {
            return widthRatio / heightRatio;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * Enum for camera selection mode.
     * Determines which camera(s) are active during recording or preview.
     */
    public enum CameraMode {
        /** Only the back (rear-facing) camera is active */
        BACK_ONLY,
        /** Only the front (selfie) camera is active */
        FRONT_ONLY,
        /** Both front and back cameras are active simultaneously with PIP overlay */
        DUAL_CAMERA
    }

    /** Enum for recording quality levels that control bitrate and resolution */
    public enum RecordingQuality {
        /** Low quality - 720p, ~4 Mbps, smaller file size */
        LOW(1280, 720, 4_000_000),
        /** Medium quality - 1080p, ~8 Mbps, balanced quality and size */
        MEDIUM(1920, 1080, 8_000_000),
        /** High quality - 1080p, ~15 Mbps, excellent quality */
        HIGH(1920, 1080, 15_000_000),
        /** Ultra quality - 4K (3840x2160), ~20 Mbps, maximum quality */
        ULTRA(3840, 2160, 20_000_000);

        /** Video width in pixels for this quality level */
        public final int width;
        /** Video height in pixels for this quality level */
        public final int height;
        /** Video bitrate in bits per second for this quality level */
        public final int bitRate;

        RecordingQuality(int width, int height, int bitRate) {
            this.width = width;
            this.height = height;
            this.bitRate = bitRate;
        }
    }

    // ---------------------------------------------------------------------------
    // Instance fields
    // ---------------------------------------------------------------------------

    /** Active camera selection mode (front, back, or dual) */
    private CameraMode cameraMode;

    /** Preferred video width in pixels */
    private int preferredWidth;

    /** Preferred video height in pixels */
    private int preferredHeight;

    /** Active aspect ratio for the output video/preview */
    private AspectRatio aspectRatio;

    /** Whether the flash/torch should be enabled during recording */
    private boolean flashEnabled;

    /** Whether the microphone audio should be recorded */
    private boolean micEnabled;

    /** Target video bitrate in bits per second */
    private int bitRate;

    /** Target video frame rate in frames per second */
    private int frameRate;

    /** I-frame (key frame) interval in seconds */
    private int iFrameInterval;

    /** Whether to enable video stabilization if supported by the device */
    private boolean stabilizationEnabled;

    /** PIP (picture-in-picture) window scale factor, relative to the view size (0.0 - 1.0) */
    private float pipScale;

    // ---------------------------------------------------------------------------
    // Constructors
    // ---------------------------------------------------------------------------

    /**
     * Creates a new CameraConfig with default settings.
     * <p>Defaults: DUAL_CAMERA mode, 1920x1080, RATIO_9_16, flash off, mic on,
     * 8 Mbps bitrate, 30 fps, 2s I-frame interval, stabilization off, PIP scale 0.25.</p>
     */
    public CameraConfig() {
        this.cameraMode = CameraMode.DUAL_CAMERA;
        this.preferredWidth = 1920;
        this.preferredHeight = 1080;
        this.aspectRatio = AspectRatio.RATIO_9_16;
        this.flashEnabled = false;
        this.micEnabled = true;
        this.bitRate = 8_000_000;
        this.frameRate = 30;
        this.iFrameInterval = 2;
        this.stabilizationEnabled = false;
        this.pipScale = 0.25f;
    }

    /**
     * Private constructor used by the {@link Builder}.
     *
     * @param builder the Builder instance containing all configuration values
     */
    private CameraConfig(Builder builder) {
        this.cameraMode = builder.cameraMode;
        this.preferredWidth = builder.preferredWidth;
        this.preferredHeight = builder.preferredHeight;
        this.aspectRatio = builder.aspectRatio;
        this.flashEnabled = builder.flashEnabled;
        this.micEnabled = builder.micEnabled;
        this.bitRate = builder.bitRate;
        this.frameRate = builder.frameRate;
        this.iFrameInterval = builder.iFrameInterval;
        this.stabilizationEnabled = builder.stabilizationEnabled;
        this.pipScale = builder.pipScale;
    }

    // ---------------------------------------------------------------------------
    // Static factory methods for common presets
    // ---------------------------------------------------------------------------

    /**
     * Creates a configuration preset optimized for TikTok / Instagram Reels.
     * <p>Settings: 1080x1920, RATIO_9_16, DUAL_CAMERA, mic on, 8 Mbps, 30 fps.</p>
     *
     * @return a new CameraConfig instance configured for TikTok-style recording
     */
    public static CameraConfig tikTokPreset() {
        CameraConfig config = new CameraConfig();
        config.setCameraMode(CameraMode.DUAL_CAMERA);
        config.setAspectRatio(AspectRatio.RATIO_9_16);
        config.setPreferredWidth(1080);
        config.setPreferredHeight(1920);
        config.setMicEnabled(true);
        config.setBitRate(8_000_000);
        config.setFrameRate(30);
        android.util.Log.d(TAG, "Created TikTok preset config");
        return config;
    }

    /**
     * Creates a configuration preset optimized for Instagram feed posts.
     * <p>Settings: 1080x1080, RATIO_1_1, DUAL_CAMERA, mic on, 8 Mbps, 30 fps.</p>
     *
     * @return a new CameraConfig instance configured for Instagram posts
     */
    public static CameraConfig instagramPreset() {
        CameraConfig config = new CameraConfig();
        config.setCameraMode(CameraMode.DUAL_CAMERA);
        config.setAspectRatio(AspectRatio.RATIO_1_1);
        config.setPreferredWidth(1080);
        config.setPreferredHeight(1080);
        config.setMicEnabled(true);
        config.setBitRate(8_000_000);
        config.setFrameRate(30);
        android.util.Log.d(TAG, "Created Instagram preset config");
        return config;
    }

    /**
     * Creates a configuration preset optimized for YouTube / Facebook.
     * <p>Settings: 1920x1080, RATIO_16_9, DUAL_CAMERA, mic on, 15 Mbps, 30 fps.</p>
     *
     * @return a new CameraConfig instance configured for YouTube/Facebook recording
     */
    public static CameraConfig youtubePreset() {
        CameraConfig config = new CameraConfig();
        config.setCameraMode(CameraMode.DUAL_CAMERA);
        config.setAspectRatio(AspectRatio.RATIO_16_9);
        config.setPreferredWidth(1920);
        config.setPreferredHeight(1080);
        config.setMicEnabled(true);
        config.setBitRate(15_000_000);
        config.setFrameRate(30);
        android.util.Log.d(TAG, "Created YouTube preset config");
        return config;
    }

    /**
     * Creates a high-quality configuration preset for maximum video quality.
     * <p>Settings: 3840x2160, RATIO_16_9, DUAL_CAMERA, mic on, 20 Mbps, 30 fps.</p>
     *
     * @return a new CameraConfig instance configured for ultra-high-quality recording
     */
    public static CameraConfig ultraQualityPreset() {
        CameraConfig config = new CameraConfig();
        config.setCameraMode(CameraMode.DUAL_CAMERA);
        config.setAspectRatio(AspectRatio.RATIO_16_9);
        config.setPreferredWidth(3840);
        config.setPreferredHeight(2160);
        config.setMicEnabled(true);
        config.setBitRate(20_000_000);
        config.setFrameRate(30);
        android.util.Log.d(TAG, "Created ultra quality preset config");
        return config;
    }

    /**
     * Creates a configuration from a {@link RecordingQuality} preset.
     * <p>Applies the resolution and bitrate from the quality level while keeping
     * other settings at their defaults.</p>
     *
     * @param quality the desired recording quality level
     * @return a new CameraConfig instance configured with the given quality
     * @throws IllegalArgumentException if quality is null
     */
    public static CameraConfig fromQuality(RecordingQuality quality) {
        if (quality == null) {
            throw new IllegalArgumentException("Recording quality must not be null");
        }
        CameraConfig config = new CameraConfig();
        config.setPreferredWidth(quality.width);
        config.setPreferredHeight(quality.height);
        config.setBitRate(quality.bitRate);
        android.util.Log.d(TAG, "Created config from quality: " + quality.name());
        return config;
    }

    // ---------------------------------------------------------------------------
    // Getters and setters
    // ---------------------------------------------------------------------------

    /**
     * Returns the current camera selection mode.
     *
     * @return the active {@link CameraMode}
     */
    public CameraMode getCameraMode() {
        return cameraMode;
    }

    /**
     * Sets the camera selection mode.
     *
     * @param cameraMode the desired camera mode (front, back, or dual)
     * @throws IllegalArgumentException if cameraMode is null
     */
    public void setCameraMode(CameraMode cameraMode) {
        if (cameraMode == null) {
            throw new IllegalArgumentException("Camera mode must not be null");
        }
        this.cameraMode = cameraMode;
    }

    /**
     * Returns the preferred video width in pixels.
     *
     * @return the preferred width
     */
    public int getPreferredWidth() {
        return preferredWidth;
    }

    /**
     * Sets the preferred video width in pixels.
     *
     * @param preferredWidth the desired width (must be positive)
     * @throws IllegalArgumentException if preferredWidth is not positive
     */
    public void setPreferredWidth(int preferredWidth) {
        if (preferredWidth <= 0) {
            throw new IllegalArgumentException("Preferred width must be positive, got: " + preferredWidth);
        }
        this.preferredWidth = preferredWidth;
    }

    /**
     * Returns the preferred video height in pixels.
     *
     * @return the preferred height
     */
    public int getPreferredHeight() {
        return preferredHeight;
    }

    /**
     * Sets the preferred video height in pixels.
     *
     * @param preferredHeight the desired height (must be positive)
     * @throws IllegalArgumentException if preferredHeight is not positive
     */
    public void setPreferredHeight(int preferredHeight) {
        if (preferredHeight <= 0) {
            throw new IllegalArgumentException("Preferred height must be positive, got: " + preferredHeight);
        }
        this.preferredHeight = preferredHeight;
    }

    /**
     * Returns the current aspect ratio setting.
     *
     * @return the active {@link AspectRatio}
     */
    public AspectRatio getAspectRatio() {
        return aspectRatio;
    }

    /**
     * Sets the aspect ratio for video output and preview.
     *
     * @param aspectRatio the desired aspect ratio
     * @throws IllegalArgumentException if aspectRatio is null
     */
    public void setAspectRatio(AspectRatio aspectRatio) {
        if (aspectRatio == null) {
            throw new IllegalArgumentException("Aspect ratio must not be null");
        }
        this.aspectRatio = aspectRatio;
        android.util.Log.d(TAG, "Aspect ratio set to: " + aspectRatio.label);
    }

    /**
     * Returns whether the flash/torch is enabled.
     *
     * @return true if flash is enabled, false otherwise
     */
    public boolean isFlashEnabled() {
        return flashEnabled;
    }

    /**
     * Enables or disables the flash/torch during recording.
     *
     * @param flashEnabled true to enable flash, false to disable
     */
    public void setFlashEnabled(boolean flashEnabled) {
        this.flashEnabled = flashEnabled;
    }

    /**
     * Returns whether the microphone is enabled for audio recording.
     *
     * @return true if mic is enabled, false otherwise
     */
    public boolean isMicEnabled() {
        return micEnabled;
    }

    /**
     * Enables or disables microphone audio recording.
     *
     * @param micEnabled true to enable microphone, false to disable
     */
    public void setMicEnabled(boolean micEnabled) {
        this.micEnabled = micEnabled;
    }

    /**
     * Returns the target video bitrate in bits per second.
     *
     * @return the bitrate in bps
     */
    public int getBitRate() {
        return bitRate;
    }

    /**
     * Sets the target video bitrate.
     *
     * @param bitRate the desired bitrate in bits per second (must be positive)
     * @throws IllegalArgumentException if bitRate is not positive
     */
    public void setBitRate(int bitRate) {
        if (bitRate <= 0) {
            throw new IllegalArgumentException("Bit rate must be positive, got: " + bitRate);
        }
        this.bitRate = bitRate;
    }

    /**
     * Returns the target video frame rate in frames per second.
     *
     * @return the frame rate in fps
     */
    public int getFrameRate() {
        return frameRate;
    }

    /**
     * Sets the target video frame rate.
     *
     * @param frameRate the desired frame rate in fps (must be positive, typically 24-60)
     * @throws IllegalArgumentException if frameRate is not positive
     */
    public void setFrameRate(int frameRate) {
        if (frameRate <= 0) {
            throw new IllegalArgumentException("Frame rate must be positive, got: " + frameRate);
        }
        this.frameRate = frameRate;
    }

    /**
     * Returns the I-frame (key frame) interval in seconds.
     *
     * @return the I-frame interval in seconds
     */
    public int getIFrameInterval() {
        return iFrameInterval;
    }

    /**
     * Sets the I-frame (key frame) interval for the video encoder.
     *
     * @param iFrameInterval the interval in seconds (must be positive, typically 1-5)
     * @throws IllegalArgumentException if iFrameInterval is not positive
     */
    public void setIFrameInterval(int iFrameInterval) {
        if (iFrameInterval <= 0) {
            throw new IllegalArgumentException("I-frame interval must be positive, got: " + iFrameInterval);
        }
        this.iFrameInterval = iFrameInterval;
    }

    /**
     * Returns whether video stabilization is enabled.
     *
     * @return true if stabilization is enabled, false otherwise
     */
    public boolean isStabilizationEnabled() {
        return stabilizationEnabled;
    }

    /**
     * Enables or disables video stabilization.
     * Note: Stabilization may not be available on all devices.
     *
     * @param stabilizationEnabled true to enable stabilization, false to disable
     */
    public void setStabilizationEnabled(boolean stabilizationEnabled) {
        this.stabilizationEnabled = stabilizationEnabled;
    }

    /**
     * Returns the PIP window scale factor (0.0 to 1.0).
     *
     * @return the PIP scale factor
     */
    public float getPipScale() {
        return pipScale;
    }

    /**
     * Sets the PIP window scale factor relative to the main view.
     *
     * @param pipScale the desired scale (clamped to 0.1 - 0.5)
     */
    public void setPipScale(float pipScale) {
        this.pipScale = Math.max(0.1f, Math.min(0.5f, pipScale));
    }

    /**
     * Returns a copy of this configuration.
     *
     * @return a new CameraConfig with the same values as this instance
     */
    public CameraConfig copy() {
        CameraConfig copy = new CameraConfig();
        copy.cameraMode = this.cameraMode;
        copy.preferredWidth = this.preferredWidth;
        copy.preferredHeight = this.preferredHeight;
        copy.aspectRatio = this.aspectRatio;
        copy.flashEnabled = this.flashEnabled;
        copy.micEnabled = this.micEnabled;
        copy.bitRate = this.bitRate;
        copy.frameRate = this.frameRate;
        copy.iFrameInterval = this.iFrameInterval;
        copy.stabilizationEnabled = this.stabilizationEnabled;
        copy.pipScale = this.pipScale;
        return copy;
    }

    @Override
    public String toString() {
        return "CameraConfig{" +
                "cameraMode=" + cameraMode +
                ", preferredWidth=" + preferredWidth +
                ", preferredHeight=" + preferredHeight +
                ", aspectRatio=" + aspectRatio +
                ", flashEnabled=" + flashEnabled +
                ", micEnabled=" + micEnabled +
                ", bitRate=" + bitRate +
                ", frameRate=" + frameRate +
                ", iFrameInterval=" + iFrameInterval +
                ", stabilizationEnabled=" + stabilizationEnabled +
                ", pipScale=" + pipScale +
                '}';
    }

    // ---------------------------------------------------------------------------
    // Builder
    // ---------------------------------------------------------------------------

    /**
     * Builder class for constructing {@link CameraConfig} instances with a fluent API.
     * <p>All values have sensible defaults. Only override the values you wish to change.</p>
     *
     * <h3>Usage Example:</h3>
     * <pre>{@code
     * CameraConfig config = new CameraConfig.Builder()
     *     .setCameraMode(CameraConfig.CameraMode.DUAL_CAMERA)
     *     .setAspectRatio(CameraConfig.AspectRatio.RATIO_9_16)
     *     .setBitRate(12_000_000)
     *     .setMicEnabled(true)
     *     .build();
     * }</pre>
     */
    public static class Builder {
        private CameraMode cameraMode = CameraMode.DUAL_CAMERA;
        private int preferredWidth = 1920;
        private int preferredHeight = 1080;
        private AspectRatio aspectRatio = AspectRatio.RATIO_9_16;
        private boolean flashEnabled = false;
        private boolean micEnabled = true;
        private int bitRate = 8_000_000;
        private int frameRate = 30;
        private int iFrameInterval = 2;
        private boolean stabilizationEnabled = false;
        private float pipScale = 0.25f;

        /**
         * Sets the camera selection mode.
         *
         * @param cameraMode the desired camera mode
         * @return this Builder for chaining
         */
        public Builder setCameraMode(CameraMode cameraMode) {
            this.cameraMode = cameraMode;
            return this;
        }

        /**
         * Sets the preferred video width.
         *
         * @param width the desired width in pixels
         * @return this Builder for chaining
         */
        public Builder setPreferredWidth(int width) {
            this.preferredWidth = width;
            return this;
        }

        /**
         * Sets the preferred video height.
         *
         * @param height the desired height in pixels
         * @return this Builder for chaining
         */
        public Builder setPreferredHeight(int height) {
            this.preferredHeight = height;
            return this;
        }

        /**
         * Sets the aspect ratio.
         *
         * @param aspectRatio the desired aspect ratio
         * @return this Builder for chaining
         */
        public Builder setAspectRatio(AspectRatio aspectRatio) {
            this.aspectRatio = aspectRatio;
            return this;
        }

        /**
         * Enables or disables the flash.
         *
         * @param enabled true to enable flash
         * @return this Builder for chaining
         */
        public Builder setFlashEnabled(boolean enabled) {
            this.flashEnabled = enabled;
            return this;
        }

        /**
         * Enables or disables the microphone.
         *
         * @param enabled true to enable microphone
         * @return this Builder for chaining
         */
        public Builder setMicEnabled(boolean enabled) {
            this.micEnabled = enabled;
            return this;
        }

        /**
         * Sets the video bitrate.
         *
         * @param bitRate the desired bitrate in bps
         * @return this Builder for chaining
         */
        public Builder setBitRate(int bitRate) {
            this.bitRate = bitRate;
            return this;
        }

        /**
         * Sets the video frame rate.
         *
         * @param frameRate the desired frame rate in fps
         * @return this Builder for chaining
         */
        public Builder setFrameRate(int frameRate) {
            this.frameRate = frameRate;
            return this;
        }

        /**
         * Sets the I-frame interval.
         *
         * @param interval the interval in seconds
         * @return this Builder for chaining
         */
        public Builder setIFrameInterval(int interval) {
            this.iFrameInterval = interval;
            return this;
        }

        /**
         * Enables or disables video stabilization.
         *
         * @param enabled true to enable stabilization
         * @return this Builder for chaining
         */
        public Builder setStabilizationEnabled(boolean enabled) {
            this.stabilizationEnabled = enabled;
            return this;
        }

        /**
         * Sets the PIP window scale factor.
         *
         * @param scale the desired scale (0.1 - 0.5)
         * @return this Builder for chaining
         */
        public Builder setPipScale(float scale) {
            this.pipScale = Math.max(0.1f, Math.min(0.5f, scale));
            return this;
        }

        /**
         * Builds and returns a new {@link CameraConfig} with the configured values.
         *
         * @return a new CameraConfig instance
         */
        public CameraConfig build() {
            return new CameraConfig(this);
        }
    }
}