package com.dualcam.recorder.gl;

import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import com.dualcam.recorder.camera.CameraConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GLES11Ext;

/**
 * OpenGL ES 2.0 renderer that composes two camera streams into a single view.
 * <p>
 * The main (back) camera stream fills the entire viewport, while the front camera
 * stream is rendered as a small PIP (Picture-in-Picture) window in the bottom-right
 * corner with a rounded semi-transparent border.
 * </p>
 *
 * <h3>Rendering Pipeline:</h3>
 * <ol>
 *   <li>Clear the framebuffer</li>
 *   <li>Draw the main camera texture to fill the viewport (with aspect ratio correction)</li>
 *   <li>Draw the PIP camera texture in a small rectangle at the bottom-right</li>
 *   <li>Draw a rounded border outline around the PIP window</li>
 * </ol>
 *
 * <h3>Thread Safety:</h3>
 * All GL operations must occur on the GL thread. Setter methods use volatile fields
 * and are safe to call from any thread.
 *
 * @author DualCamRecorder
 * @version 1.0
 */
public class CameraStreamRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "CameraStreamRenderer";

    // =========================================================================
    // Shader source code
    // =========================================================================

    /** Vertex shader source for rendering a textured quad */
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform mat4 uMVPMatrix;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}\n";

    /** Fragment shader source for sampling an OES external texture */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}\n";

    /** Fragment shader for the PIP border (solid color) */
    private static final String BORDER_FRAGMENT_SHADER =
            "precision mediump float;\n" +
                    "uniform vec4 uBorderColor;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = uBorderColor;\n" +
                    "}\n";

    // =========================================================================
    // Geometry data
    // =========================================================================

    /** Full-screen quad vertex positions (x, y) */
    private static final float[] QUAD_VERTICES = {
            -1.0f, -1.0f,  // bottom-left
            1.0f, -1.0f,  // bottom-right
            -1.0f,  1.0f,  // top-left
            1.0f,  1.0f,  // top-right
    };

    /** Full-screen quad texture coordinates (s, t) */
    private static final float[] QUAD_TEX_COORDS = {
            0.0f, 1.0f,  // bottom-left of texture (note: Y is flipped for camera)
            1.0f, 1.0f,  // bottom-right
            0.0f, 0.0f,  // top-left
            1.0f, 0.0f,  // top-right
    };

    /** Front camera texture coordinates (mirrored horizontally for selfie view) */
    private static final float[] PIP_TEX_COORDS_MIRRORED = {
            1.0f, 1.0f,  // bottom-left (mirrored)
            0.0f, 1.0f,  // bottom-right (mirrored)
            1.0f, 0.0f,  // top-left (mirrored)
            0.0f, 0.0f,  // top-right (mirrored)
    };

    /** PIP border line vertices - a rectangle outline with rounded corners approximation */
    private static final float[] BORDER_LINE_VERTICES = {
            // Bottom edge
            -0.9f, -0.9f,   0.9f, -0.9f,
            // Right edge
            0.9f, -0.9f,   0.9f,  0.9f,
            // Top edge
            0.9f,  0.9f,  -0.9f,  0.9f,
            // Left edge
            -0.9f,  0.9f,  -0.9f, -0.9f,
    };

    // =========================================================================
    // EGL display / context / surface for the encoder (EGL14 API)
    // =========================================================================

    // Field types use the android.opengl.* (EGL14) variants because
    // EGLExt.eglPresentationTimeANDROID() requires those types. The
    // GLSurfaceView.Renderer callback still receives a
    // javax.microedition.khronos.egl.EGLConfig in onSurfaceCreated(), which is
    // imported separately above.

    /** The encoder's input Surface, received from VideoRecorder.getInputSurface() */
    private Surface mEncoderSurface = null;

    /** EGL surface created from the encoder's input Surface for recording */
    private android.opengl.EGLSurface mEncoderEGLSurface = null;

    /** EGL context shared with the main rendering context for encoder surface */
    private android.opengl.EGLContext mEncoderEGLContext = null;

    /** EGL display used for creating the encoder surface */
    private android.opengl.EGLDisplay mEncoderEGLDisplay = null;

    /** Lock for encoder surface operations */
    private final Object mEncoderLock = new Object();

    /** Whether the encoder EGL surface has been successfully created */
    private volatile boolean mEncoderSurfaceReady = false;

    // =========================================================================
    // Instance state
    // =========================================================================
    private final Object mLock = new Object();

    /** OpenGL texture ID for the main (back) camera stream */
    private volatile int mMainTextureId = -1;

    /** OpenGL texture ID for the PIP (front) camera stream */
    private volatile int mPIPCameraTextureId = -1;

    /** Currently active aspect ratio configuration */
    private volatile CameraConfig.AspectRatio mAspectRatio = CameraConfig.AspectRatio.RATIO_9_16;

    /** PIP window scale factor (0.2 - 0.4 of screen size) */
    private volatile float mPIPScale = 0.25f;

    /** PIP window X position in normalized coordinates (-1 to 1) */
    private volatile float mPIPPositionX = 0.0f;

    /** PIP window Y position in normalized coordinates (-1 to 1) */
    private volatile float mPIPPositionY = 0.0f;

    /** Current viewport width in pixels */
    private int mViewWidth = 1;

    /** Current viewport height in pixels */
    private int mViewHeight = 1;

    // GL program handles
    private int mCameraProgram = -1;
    private int mBorderProgram = -1;

    // Attribute locations for camera program
    private int mCameraPositionHandle = -1;
    private int mCameraTexCoordHandle = -1;
    private int mCameraMVPMatrixHandle = -1;
    private int mCameraTextureHandle = -1;

    // Attribute locations for border program
    private int mBorderPositionHandle = -1;
    private int mBorderColorHandle = -1;
    private int mBorderMVPMatrixHandle = -1;

    // Vertex buffers
    private FloatBuffer mQuadVertexBuffer;
    private FloatBuffer mQuadTexCoordBuffer;
    private FloatBuffer mPIPTexCoordBuffer;
    private FloatBuffer mBorderVertexBuffer;

    // Transformation matrices
    private final float[] mViewMatrix = new float[16];
    private final float[] mProjMatrix = new float[16];
    private final float[] mVPMatrix = new float[16];
    private final float[] mMainMVPMatrix = new float[16];
    private final float[] mPIPMVPMatrix = new float[16];
    private final float[] mBorderMVPMatrix = new float[16];

    /** Whether the PIP camera texture should be mirrored (true for front camera selfie) */
    private boolean mMirrorPIP = true;

    /** Border color RGBA (semi-transparent white) */
    private final float[] mBorderColor = {1.0f, 1.0f, 1.0f, 0.7f};

    /** Border line width in pixels */
    private static final float BORDER_WIDTH = 3.0f;

    /** Border corner radius as fraction of PIP size */
    private static final float BORDER_CORNER_RADIUS = 0.08f;

    /** Whether GL has been initialized */
    private boolean mInitialized = false;

    /** Callback for when the GL surface is created (provides texture IDs) */
    private SurfaceCreatedCallback mSurfaceCreatedCallback;

    // =========================================================================
    // Encoder surface fields (for video recording)
    // =========================================================================
    // Encoder EGL display / context / surface are declared above (alongside
    // the EGL14 explanation) so the encoder-surface management section
    // appears together with the rest of the encoder state below.

    // =========================================================================
    // Callback interface
    // =========================================================================

    /**
     * Callback interface for when the GL surface is created and texture IDs are generated.
     */
    public interface SurfaceCreatedCallback {
        /**
         * Called when the GL surface is created and OES external texture IDs are available.
         *
         * @param mainTextureId  the GL texture ID for the main camera stream
         * @param pipTextureId   the GL texture ID for the PIP camera stream
         */
        void onSurfaceTextureCreated(int mainTextureId, int pipTextureId);
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * Creates a new CameraStreamRenderer with default settings.
     */
    public CameraStreamRenderer() {
        Log.d(TAG, "CameraStreamRenderer created");
    }

    // =========================================================================
    // GLSurfaceView.Renderer implementation
    // =========================================================================

    /**
     * Called when the GL surface is created. Initializes shaders, buffers, and textures.
     *
     * @param gl     the GL10 interface (not used directly; GLES20 is used instead)
     * @param config the EGLConfig for the surface
     */
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated: Initializing OpenGL ES state");

        try {
            // Set background color to black
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            // Disable depth testing (we are doing 2D rendering only)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);

            // Enable blending for PIP border transparency
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            // Compile and link shader programs
            mCameraProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mCameraProgram == -1) {
                Log.e(TAG, "Failed to create camera shader program");
                return;
            }

            mBorderProgram = createProgram(VERTEX_SHADER, BORDER_FRAGMENT_SHADER);
            if (mBorderProgram == -1) {
                Log.e(TAG, "Failed to create border shader program");
                return;
            }

            // Get attribute/uniform locations for camera program
            mCameraPositionHandle = GLES20.glGetAttribLocation(mCameraProgram, "aPosition");
            mCameraTexCoordHandle = GLES20.glGetAttribLocation(mCameraProgram, "aTexCoord");
            mCameraMVPMatrixHandle = GLES20.glGetUniformLocation(mCameraProgram, "uMVPMatrix");
            mCameraTextureHandle = GLES20.glGetUniformLocation(mCameraProgram, "uTexture");

            // Get attribute/uniform locations for border program
            mBorderPositionHandle = GLES20.glGetAttribLocation(mBorderProgram, "aPosition");
            mBorderColorHandle = GLES20.glGetUniformLocation(mBorderProgram, "uBorderColor");
            mBorderMVPMatrixHandle = GLES20.glGetUniformLocation(mBorderProgram, "uMVPMatrix");

            // Create vertex buffers
            mQuadVertexBuffer = createFloatBuffer(QUAD_VERTICES);
            mQuadTexCoordBuffer = createFloatBuffer(QUAD_TEX_COORDS);
            mPIPTexCoordBuffer = createFloatBuffer(PIP_TEX_COORDS_MIRRORED);
            mBorderVertexBuffer = createFloatBuffer(BORDER_LINE_VERTICES);

            // Generate OES external texture IDs
            int[] textureIds = new int[2];
            GLES20.glGenTextures(2, textureIds, 0);

            synchronized (mLock) {
                mMainTextureId = textureIds[0];
                mPIPCameraTextureId = textureIds[1];
            }

            // Configure texture parameters for main camera
            configureOESTexture(mMainTextureId);
            // Configure texture parameters for PIP camera
            configureOESTexture(mPIPCameraTextureId);

            // Initialize view and projection matrices
            Matrix.setLookAtM(mViewMatrix, 0,
                    0.0f, 0.0f, 1.0f,   // eye position
                    0.0f, 0.0f, 0.0f,   // center
                    0.0f, 1.0f, 0.0f);  // up vector
            Matrix.orthoM(mProjMatrix, 0, -1.0f, 1.0f, -1.0f, 1.0f, -1.0f, 1.0f);
            Matrix.multiplyMM(mVPMatrix, 0, mProjMatrix, 0, mViewMatrix, 0);

            mInitialized = true;
            Log.d(TAG, "OpenGL ES initialized successfully. Main texture ID: " + mMainTextureId
                    + ", PIP texture ID: " + mPIPCameraTextureId);

            // Notify callback
            if (mSurfaceCreatedCallback != null) {
                mSurfaceCreatedCallback.onSurfaceTextureCreated(mMainTextureId, mPIPCameraTextureId);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in onSurfaceCreated", e);
        }
    }

    /**
     * Called when the GL surface is resized. Updates the viewport and recalculates
     * aspect-ratio-corrected transformation matrices.
     *
     * @param gl     the GL10 interface
     * @param width  the new surface width in pixels
     * @param height the new surface height in pixels
     */
    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "onSurfaceChanged: invalid dimensions " + width + "x" + height);
            return;
        }

        mViewWidth = width;
        mViewHeight = height;

        GLES20.glViewport(0, 0, width, height);
        Log.d(TAG, "onSurfaceChanged: viewport set to " + width + "x" + height);

        // Recalculate the main camera MVP matrix with aspect ratio correction
        updateMatrices();

        // Update border geometry to match PIP dimensions
        updateBorderGeometry();
    }

    /**
     * Called to render each frame. Draws the main camera texture filling the viewport,
     * then draws the PIP window and its border.
     *
     * @param gl the GL10 interface
     */
    @Override
    public void onDrawFrame(GL10 gl) {
        if (!mInitialized) {
            return;
        }

        try {
            // Clear the framebuffer
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            // Draw main camera texture
            drawMainCamera();

            // Draw PIP camera texture
            drawPIPCamera();

            // Draw PIP border
            drawPIPBorder();

            // If an encoder surface is set, also render the composed frame to it
            renderToEncoderSurface();

        } catch (Exception e) {
            Log.e(TAG, "Error in onDrawFrame", e);
        }
    }

    /**
     * Renders the current composed frame to the video encoder's input surface.
     * <p>
     * This method uses EGL to create a shared context and surface from the
     * MediaCodec encoder's input Surface. Each frame rendered to the screen
     * is also rendered to the encoder surface, ensuring the recorded video
     * contains the same composed output (main camera + PIP overlay).
     * </p>
     */
    private void renderToEncoderSurface() {
        synchronized (mEncoderLock) {
            if (mEncoderSurface == null || !mEncoderSurfaceReady) {
                return;
            }

            if (mEncoderEGLDisplay == null
                    || mEncoderEGLContext == null
                    || mEncoderEGLSurface == null) {
                return;
            }

            // EGL14 and EGL10 both wrap the same underlying native EGL
            // implementation, so EGL14.eglGetCurrentContext() will see the
            // GLSurfaceView's GL-thread context even though GLSurfaceView's
            // default factory uses the legacy EGL10 Java API.
            android.opengl.EGLContext currentContext = EGL14.eglGetCurrentContext();
            if (currentContext == null
                    || currentContext == EGL14.EGL_NO_CONTEXT) {
                return;
            }

            try {
                // Save the current EGL display, context, and surfaces so we can
                // restore them after rendering to the encoder surface.
                android.opengl.EGLDisplay currentDisplay = EGL14.eglGetCurrentDisplay();
                android.opengl.EGLSurface currentDrawSurface =
                        EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);
                android.opengl.EGLSurface currentReadSurface =
                        EGL14.eglGetCurrentSurface(EGL14.EGL_READ);

                // Make the encoder EGL context current on the encoder surface
                boolean madeCurrent = EGL14.eglMakeCurrent(
                        mEncoderEGLDisplay,
                        mEncoderEGLSurface,
                        mEncoderEGLSurface,
                        mEncoderEGLContext);

                if (!madeCurrent) {
                    Log.e(TAG, "Failed to make encoder EGL context current");
                    return;
                }

                // Set viewport to match encoder resolution
                GLES20.glViewport(0, 0, mViewWidth, mViewHeight);

                // Clear the encoder framebuffer
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // Re-draw all content to the encoder surface
                GLES20.glEnable(GLES20.GL_BLEND);
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

                drawMainCamera();
                drawPIPCamera();
                drawPIPBorder();

                // Set presentation time stamp (in nanoseconds) for the frame
                // that will be submitted to the encoder via eglSwapBuffers.
                // EGLExt.eglPresentationTimeANDROID requires the
                // android.opengl.EGLDisplay / EGLSurface types produced by
                // EGL14, which is why the encoder surface management uses
                // EGL14 rather than the legacy EGL10 API.
                long pts = System.nanoTime();
                EGLExt.eglPresentationTimeANDROID(mEncoderEGLDisplay, mEncoderEGLSurface, pts);

                // Swap buffers to deliver the frame to the encoder
                EGL14.eglSwapBuffers(mEncoderEGLDisplay, mEncoderEGLSurface);

                // Restore the original EGL context and surface on the original
                // display (the GLSurfaceView's display).
                if (currentDisplay != null && currentDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                            currentDisplay,
                            currentDrawSurface,
                            currentReadSurface,
                            currentContext);
                }

                // Restore viewport
                GLES20.glViewport(0, 0, mViewWidth, mViewHeight);

            } catch (Exception e) {
                Log.e(TAG, "Error rendering to encoder surface", e);
            }
        }
    }

    // =========================================================================
    // Encoder surface management
    // =========================================================================

    /**
     * Sets the video encoder's input Surface for recording the composed frame.
     * <p>
     * When set, every frame rendered to the screen will also be rendered to
     * the encoder's input surface, enabling the VideoRecorder to encode the
     * composed dual-camera output into an MP4 file.
     * </p>
     *
     * @param encoderSurface the Surface from VideoRecorder.getInputSurface(),
     *                       or null to stop rendering to the encoder
     */
    public void setInputSurface(Surface encoderSurface) {
        Log.d(TAG, "setInputSurface: " + (encoderSurface != null ? "setting" : "clearing"));
        synchronized (mEncoderLock) {
            releaseEncoderSurfaceInternal();
            mEncoderSurface = encoderSurface;

            if (encoderSurface == null) {
                mEncoderSurfaceReady = false;
                return;
            }

            try {
                mEncoderEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (mEncoderEGLDisplay == null
                        || mEncoderEGLDisplay == EGL14.EGL_NO_DISPLAY) {
                    Log.e(TAG, "Failed to get EGL display for encoder surface");
                    return;
                }

                // EGL14.eglInitialize takes separate major/minor arrays with
                // explicit offsets (unlike EGL10 which took a single version[2]).
                int[] major = new int[1];
                int[] minor = new int[1];
                if (!EGL14.eglInitialize(mEncoderEGLDisplay, major, 0, minor, 0)) {
                    Log.e(TAG, "Failed to initialize EGL for encoder surface");
                    return;
                }

                int[] configSpec = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_NONE
                };

                android.opengl.EGLConfig[] configs = new android.opengl.EGLConfig[1];
                int[] numConfigs = new int[1];
                if (!EGL14.eglChooseConfig(mEncoderEGLDisplay, configSpec, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                    Log.e(TAG, "No EGL config found for encoder surface");
                    return;
                }

                int[] contextAttribs = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE
                };

                // Share data with whichever EGL context is current on the
                // calling thread (typically the GLSurfaceView's GL-thread
                // context, when this method is queued via queueEvent()).
                android.opengl.EGLContext shareContext = EGL14.eglGetCurrentContext();
                if (shareContext == null) {
                    shareContext = EGL14.EGL_NO_CONTEXT;
                }
                mEncoderEGLContext = EGL14.eglCreateContext(mEncoderEGLDisplay, configs[0], shareContext, contextAttribs, 0);

                if (mEncoderEGLContext == null
                        || mEncoderEGLContext == EGL14.EGL_NO_CONTEXT) {
                    Log.e(TAG, "Failed to create shared EGL context for encoder");
                    return;
                }

                int[] surfaceAttribs = {EGL14.EGL_NONE};
                mEncoderEGLSurface = EGL14.eglCreateWindowSurface(mEncoderEGLDisplay, configs[0], encoderSurface, surfaceAttribs, 0);

                if (mEncoderEGLSurface == null
                        || mEncoderEGLSurface == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "Failed to create EGL surface from encoder Surface");
                    return;
                }

                mEncoderSurfaceReady = true;
                Log.d(TAG, "Encoder EGL surface created successfully");

            } catch (Exception e) {
                Log.e(TAG, "Error creating encoder EGL surface", e);
                releaseEncoderSurfaceInternal();
            }
        }
    }

    /**
     * Releases the encoder EGL surface and context resources internally.
     */
    private void releaseEncoderSurfaceInternal() {
        try {
            if (mEncoderEGLDisplay != null) {
                if (mEncoderEGLSurface != null && mEncoderEGLContext != null) {
                    EGL14.eglMakeCurrent(mEncoderEGLDisplay,
                            EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                            EGL14.EGL_NO_CONTEXT);
                }
                if (mEncoderEGLSurface != null
                        && mEncoderEGLSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(mEncoderEGLDisplay, mEncoderEGLSurface);
                    mEncoderEGLSurface = null;
                }
                if (mEncoderEGLContext != null
                        && mEncoderEGLContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(mEncoderEGLDisplay, mEncoderEGLContext);
                    mEncoderEGLContext = null;
                }
                mEncoderEGLDisplay = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing encoder surface", e);
        }
        mEncoderSurfaceReady = false;
    }

    /**
     * Public method to release encoder surface resources. Thread-safe.
     */
    public void releaseEncoderSurface() {
        synchronized (mEncoderLock) {
            releaseEncoderSurfaceInternal();
            mEncoderSurface = null;
        }
    }

    // =========================================================================
    // Drawing methods
    // =========================================================================

    /**
     * Draws the main (back) camera texture to fill the entire viewport with
     * proper aspect ratio correction.
     */
    private void drawMainCamera() {
        synchronized (mLock) {
            if (mMainTextureId == -1) {
                return;
            }

            GLES20.glUseProgram(mCameraProgram);

            // Bind the main camera texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mMainTextureId);
            GLES20.glUniform1i(mCameraTextureHandle, 0);

            // Set the vertex position data
            GLES20.glEnableVertexAttribArray(mCameraPositionHandle);
            GLES20.glVertexAttribPointer(mCameraPositionHandle, 2, GLES20.GL_FLOAT,
                    false, 0, mQuadVertexBuffer);

            // Set the texture coordinate data
            GLES20.glEnableVertexAttribArray(mCameraTexCoordHandle);
            GLES20.glVertexAttribPointer(mCameraTexCoordHandle, 2, GLES20.GL_FLOAT,
                    false, 0, mQuadTexCoordBuffer);

            // Set the MVP matrix with aspect ratio correction
            GLES20.glUniformMatrix4fv(mCameraMVPMatrixHandle, 1, false, mMainMVPMatrix, 0);

            // Draw the quad
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Clean up
            GLES20.glDisableVertexAttribArray(mCameraPositionHandle);
            GLES20.glDisableVertexAttribArray(mCameraTexCoordHandle);
        }
    }

    /**
     * Draws the PIP (front) camera texture as a small window at the configured position.
     */
    private void drawPIPCamera() {
        synchronized (mLock) {
            if (mPIPCameraTextureId == -1) {
                return;
            }

            GLES20.glUseProgram(mCameraProgram);

            // Bind the PIP camera texture
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mPIPCameraTextureId);
            GLES20.glUniform1i(mCameraTextureHandle, 0);

            // Set vertex positions
            GLES20.glEnableVertexAttribArray(mCameraPositionHandle);
            GLES20.glVertexAttribPointer(mCameraPositionHandle, 2, GLES20.GL_FLOAT,
                    false, 0, mQuadVertexBuffer);

            // Set texture coordinates (mirrored for front camera selfie view)
            GLES20.glEnableVertexAttribArray(mCameraTexCoordHandle);
            FloatBuffer texBuffer = mMirrorPIP ? mPIPTexCoordBuffer : mQuadTexCoordBuffer;
            GLES20.glVertexAttribPointer(mCameraTexCoordHandle, 2, GLES20.GL_FLOAT,
                    false, 0, texBuffer);

            // Set the PIP MVP matrix (scaled and translated to PIP position)
            GLES20.glUniformMatrix4fv(mCameraMVPMatrixHandle, 1, false, mPIPMVPMatrix, 0);

            // Draw the PIP quad
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            // Clean up
            GLES20.glDisableVertexAttribArray(mCameraPositionHandle);
            GLES20.glDisableVertexAttribArray(mCameraTexCoordHandle);
        }
    }

    /**
     * Draws a rounded border outline around the PIP window.
     * <p>The border is rendered as semi-transparent lines around the PIP rectangle
     * with a corner radius effect achieved through the border fragment shader.</p>
     */
    private void drawPIPBorder() {
        synchronized (mLock) {
            GLES20.glUseProgram(mBorderProgram);

            // Set border color (semi-transparent white)
            GLES20.glUniform4fv(mBorderColorHandle, 1, mBorderColor, 0);

            // Set the PIP MVP matrix (same as PIP texture)
            GLES20.glUniformMatrix4fv(mBorderMVPMatrixHandle, 1, false, mBorderMVPMatrix, 0);

            // Set vertex positions for the border lines
            GLES20.glEnableVertexAttribArray(mBorderPositionHandle);
            GLES20.glVertexAttribPointer(mBorderPositionHandle, 2, GLES20.GL_FLOAT,
                    false, 0, mBorderVertexBuffer);

            // Set line width
            GLES20.glLineWidth(BORDER_WIDTH);

            // Draw border as lines (4 line segments forming a rectangle)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, 8);

            // Draw rounded corners as triangle fans for smoother appearance
            drawRoundedCorners();

            // Clean up
            GLES20.glDisableVertexAttribArray(mBorderPositionHandle);
        }
    }

    /**
     * Draws rounded corners on the PIP border using triangle fans.
     * <p>This creates a smoother visual appearance at the corners of the PIP window.</p>
     */
    private void drawRoundedCorners() {
        int segments = 8; // Number of segments per corner
        float radius = BORDER_CORNER_RADIUS;

        float[] cornerCenters = {
                0.9f - radius, -0.9f + radius,  // bottom-right
                0.9f - radius,  0.9f - radius,  // top-right
                -0.9f + radius,  0.9f - radius, // top-left
                -0.9f + radius, -0.9f + radius, // bottom-left
        };

        float[] startAngles = {0.0f, 90.0f, 180.0f, 270.0f};

        FloatBuffer cornerBuffer = ByteBuffer.allocateDirect((segments + 2) * 2 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();

        for (int c = 0; c < 4; c++) {
            cornerBuffer.clear();
            float cx = cornerCenters[c * 2];
            float cy = cornerCenters[c * 2 + 1];
            float startAngle = startAngles[c];

            // Center point
            cornerBuffer.put(cx);
            cornerBuffer.put(cy);

            // Arc points
            for (int i = 0; i <= segments; i++) {
                float angle = (float) Math.toRadians(startAngle + (90.0f * i / segments));
                cornerBuffer.put(cx + radius * (float) Math.cos(angle));
                cornerBuffer.put(cy + radius * (float) Math.sin(angle));
            }

            cornerBuffer.position(0);
            GLES20.glVertexAttribPointer(mBorderPositionHandle, 2, GLES20.GL_FLOAT,
                    false, 0, cornerBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, segments + 2);
        }
    }

    // =========================================================================
    // Matrix and geometry updates
    // =========================================================================

    /**
     * Recalculates the transformation matrices for the main camera and PIP window
     * based on the current viewport size and aspect ratio.
     */
    private void updateMatrices() {
        CameraConfig.AspectRatio ratio = mAspectRatio;
        float targetRatio = ratio.getRatio();
        float viewRatio = (float) mViewWidth / mViewHeight;

        // Initialize with identity (orthographic projection)
        float[] tempMatrix = new float[16];
        Matrix.setIdentityM(mMainMVPMatrix, 0);

        // Calculate scale to maintain aspect ratio (cover mode)
        float scaleX, scaleY;
        if (viewRatio > targetRatio) {
            // View is wider than target: scale height to fill, crop sides
            scaleX = viewRatio / targetRatio;
            scaleY = 1.0f;
        } else {
            // View is taller than target: scale width to fill, crop top/bottom
            scaleX = 1.0f;
            scaleY = targetRatio / viewRatio;
        }

        Matrix.setIdentityM(tempMatrix, 0);
        Matrix.scaleM(tempMatrix, 0, scaleX, scaleY, 1.0f);
        System.arraycopy(tempMatrix, 0, mMainMVPMatrix, 0, 16);

        // PIP matrix: scale down and translate to position
        Matrix.setIdentityM(mPIPMVPMatrix, 0);
        Matrix.translateM(mPIPMVPMatrix, 0, mPIPPositionX, mPIPPositionY, 0.0f);
        Matrix.scaleM(mPIPMVPMatrix, 0, mPIPScale, mPIPScale, 1.0f);

        // Border matrix: slightly larger than PIP for the outline effect
        System.arraycopy(mPIPMVPMatrix, 0, mBorderMVPMatrix, 0, 16);
        float borderExpand = 1.03f;
        Matrix.scaleM(mBorderMVPMatrix, 0, borderExpand, borderExpand, 1.0f);

        Log.d(TAG, "Matrices updated: viewRatio=" + viewRatio + ", targetRatio=" + targetRatio
                + ", scaleX=" + scaleX + ", scaleY=" + scaleY);
    }

    /**
     * Updates the border geometry to match the current PIP dimensions.
     */
    private void updateBorderGeometry() {
        // Border vertices are defined in normalized PIP-space (-1 to 1)
        // The MVP matrix handles the actual screen positioning
        // No need to update geometry dynamically unless shape changes
    }

    // =========================================================================
    // Shader utilities
    // =========================================================================

    /**
     * Compiles a vertex or fragment shader.
     *
     * @param shaderType the shader type (GLES20.GL_VERTEX_SHADER or GLES20.GL_FRAGMENT_SHADER)
     * @param source     the GLSL shader source code
     * @return the compiled shader handle, or -1 on failure
     */
    private int compileShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader == 0) {
            Log.e(TAG, "glCreateShader failed for type: " + shaderType);
            return -1;
        }

        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);

        if (compileStatus[0] == GLES20.GL_FALSE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            Log.e(TAG, "Shader compilation failed: " + log);
            return -1;
        }

        return shader;
    }

    /**
     * Creates a GL program by compiling and linking vertex and fragment shaders.
     *
     * @param vertexSource   the vertex shader source code
     * @param fragmentSource the fragment shader source code
     * @return the linked program handle, or -1 on failure
     */
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == -1) {
            Log.e(TAG, "Failed to compile vertex shader");
            return -1;
        }

        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fragmentShader == -1) {
            Log.e(TAG, "Failed to compile fragment shader");
            GLES20.glDeleteShader(vertexShader);
            return -1;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            Log.e(TAG, "glCreateProgram failed");
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return -1;
        }

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);

        if (linkStatus[0] == GLES20.GL_FALSE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            Log.e(TAG, "Program link failed: " + log);
            return -1;
        }

        // Shaders can be deleted after linking
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    /**
     * Configures an OES external texture with standard sampling parameters.
     *
     * @param textureId the GL texture ID to configure
     */
    private void configureOESTexture(int textureId) {
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    /**
     * Creates a direct ByteBuffer from a float array for use as a GL vertex buffer.
     *
     * @param data the float array data
     * @return a FloatBuffer backed by a direct ByteBuffer
     */
    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    // =========================================================================
    // Public setters (thread-safe via volatile)
    // =========================================================================

    /**
     * Updates the main camera texture ID.
     * <p>This is typically called when a new SurfaceTexture is attached to the camera.</p>
     *
     * @param textureId the GL texture ID for the main camera stream
     */
    public void updateMainTexture(int textureId) {
        synchronized (mLock) {
            mMainTextureId = textureId;
            Log.d(TAG, "Main texture updated: " + textureId);
        }
    }

    /**
     * Updates the PIP camera texture ID.
     *
     * @param textureId the GL texture ID for the PIP (front) camera stream
     */
    public void updatePIPCameraTexture(int textureId) {
        synchronized (mLock) {
            mPIPCameraTextureId = textureId;
            Log.d(TAG, "PIP camera texture updated: " + textureId);
        }
    }

    /**
     * Sets the active aspect ratio and recalculates transformation matrices.
     *
     * @param aspectRatio the desired aspect ratio
     * @throws IllegalArgumentException if aspectRatio is null
     */
    public void setAspectRatio(CameraConfig.AspectRatio aspectRatio) {
        if (aspectRatio == null) {
            throw new IllegalArgumentException("Aspect ratio must not be null");
        }
        mAspectRatio = aspectRatio;
        Log.d(TAG, "Aspect ratio set to: " + aspectRatio.label);
        // Matrices will be recalculated in the next onDrawFrame or onSurfaceChanged
    }

    /**
     * Sets the PIP window scale factor.
     * <p>Valid range is 0.1 to 0.5. Values outside this range are clamped.</p>
     *
     * @param scale the PIP scale factor (0.1 - 0.5)
     */
    public void setPIPScale(float scale) {
        mPIPScale = Math.max(0.1f, Math.min(0.5f, scale));
        Log.d(TAG, "PIP scale set to: " + mPIPScale);
    }

    /**
     * Sets the PIP window position in normalized coordinates.
     *
     * @param x the X position (-1.0 to 1.0, where 1.0 is right)
     * @param y the Y position (-1.0 to 1.0, where 1.0 is top)
     */
    public void setPIPPosition(float x, float y) {
        mPIPPositionX = Math.max(-1.0f, Math.min(1.0f, x));
        mPIPPositionY = Math.max(-1.0f, Math.min(1.0f, y));
        Log.d(TAG, "PIP position set to: (" + mPIPPositionX + ", " + mPIPPositionY + ")");
    }

    /**
     * Sets the callback for surface creation events.
     *
     * @param callback the callback to receive texture IDs when the surface is created
     */
    public void setSurfaceCreatedCallback(SurfaceCreatedCallback callback) {
        mSurfaceCreatedCallback = callback;
    }

    /**
     * Sets whether the PIP camera texture should be mirrored horizontally.
     * <p>Typically true for front (selfie) camera to provide a mirror effect.</p>
     *
     * @param mirror true to mirror the PIP texture, false for normal orientation
     */
    public void setMirrorPIP(boolean mirror) {
        mMirrorPIP = mirror;
    }

    /**
     * Returns the current viewport width.
     *
     * @return the viewport width in pixels
     */
    public int getViewWidth() {
        return mViewWidth;
    }

    /**
     * Returns the current viewport height.
     *
     * @return the viewport height in pixels
     */
    public int getViewHeight() {
        return mViewHeight;
    }

    /**
     * Returns the main camera texture ID.
     *
     * @return the GL texture ID, or -1 if not created
     */
    public int getMainTextureId() {
        synchronized (mLock) {
            return mMainTextureId;
        }
    }

    /**
     * Returns the PIP camera texture ID.
     *
     * @return the GL texture ID, or -1 if not created
     */
    public int getPIPCameraTextureId() {
        synchronized (mLock) {
            return mPIPCameraTextureId;
        }
    }

    /**
     * Releases all GL resources (programs, textures, buffers).
     * <p>Must be called on the GL thread, or queued via {@link GLSurfaceView#queueEvent(Runnable)}.</p>
     */
    public void releaseGLResources() {
        Log.d(TAG, "Releasing GL resources");

        try {
            if (mCameraProgram != -1) {
                GLES20.glDeleteProgram(mCameraProgram);
                mCameraProgram = -1;
            }
            if (mBorderProgram != -1) {
                GLES20.glDeleteProgram(mBorderProgram);
                mBorderProgram = -1;
            }

            // Collect only the valid texture IDs so we never call glDeleteTextures
            // with an uninitialized (0) slot, which would attempt to delete the
            // default texture object.
            java.util.ArrayList<Integer> texturesToDelete = new java.util.ArrayList<>(2);
            synchronized (mLock) {
                if (mMainTextureId != -1) {
                    texturesToDelete.add(mMainTextureId);
                    mMainTextureId = -1;
                }
                if (mPIPCameraTextureId != -1) {
                    texturesToDelete.add(mPIPCameraTextureId);
                    mPIPCameraTextureId = -1;
                }
            }
            if (!texturesToDelete.isEmpty()) {
                int[] textures = new int[texturesToDelete.size()];
                for (int i = 0; i < textures.length; i++) {
                    textures[i] = texturesToDelete.get(i);
                }
                GLES20.glDeleteTextures(textures.length, textures, 0);
            }

            mInitialized = false;
            Log.d(TAG, "GL resources released");

        } catch (Exception e) {
            Log.e(TAG, "Error releasing GL resources", e);
        }
    }
}