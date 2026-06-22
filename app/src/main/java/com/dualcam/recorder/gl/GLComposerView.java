package com.dualcam.recorder.gl;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import com.dualcam.recorder.camera.CameraConfig;

/**
 * Custom {@link GLSurfaceView} that hosts the {@link CameraStreamRenderer} for composing
 * two camera streams (main camera + PIP overlay).
 * <p>
 * This view manages the renderer lifecycle, provides a high-level interface for updating
 * camera textures, and handles touch events for PIP window repositioning.
 * </p>
 *
 * <h3>Features:</h3>
 * <ul>
 *   <li>Continuous rendering mode for smooth camera preview</li>
 *   <li>Touch-based PIP window dragging</li>
 *   <li>Delegate methods for aspect ratio and PIP configuration</li>
 *   <li>Surface creation/destruction callbacks</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * GLComposerView glView = new GLComposerView(context);
 * glView.setRenderer(new CameraStreamRenderer());
 * layout.addView(glView);
 *
 * // Update camera textures when cameras are opened
 * glView.updateMainCameraTexture(mainSurfaceTexture);
 * glView.updateFrontCameraTexture(frontSurfaceTexture);
 *
 * // Change aspect ratio
 * glView.setAspectRatio(CameraConfig.AspectRatio.RATIO_1_1);
 * }</pre>
 *
 * @author DualCamRecorder
 * @version 1.0
 * @see CameraStreamRenderer
 */
public class GLComposerView extends GLSurfaceView {

    private static final String TAG = "GLComposerView";

    /** The renderer instance that composes the camera streams */
    private CameraStreamRenderer mRenderer;

    /** SurfaceTexture for the main (back) camera */
    private SurfaceTexture mMainSurfaceTexture;

    /** SurfaceTexture for the front (PIP) camera */
    private SurfaceTexture mFrontSurfaceTexture;

    /** Whether PIP dragging is currently enabled */
    private boolean mPipDraggingEnabled = true;

    /** Whether the user is currently dragging the PIP window */
    private boolean mIsDragging = false;

    /** X coordinate of the initial touch for PIP dragging */
    private float mLastTouchX;

    /** Y coordinate of the initial touch for PIP dragging */
    private float mLastTouchY;

    /** Current PIP position X in normalized coordinates */
    private float mCurrentPipX;

    /** Current PIP position Y in normalized coordinates */
    private float mCurrentPipY;

    /** Minimum movement threshold (in pixels) to initiate PIP dragging */
    private static final float TOUCH_SLOP = 10.0f;

    /** Callback for surface creation/destruction events */
    private SurfaceLifecycleCallback mSurfaceCallback;

    // =========================================================================
    // Callback interface
    // =========================================================================

    /**
     * Callback interface for receiving surface lifecycle events from the GL view.
     */
    public interface SurfaceLifecycleCallback {
        /**
         * Called when the GL surface has been created and is ready for rendering.
         *
         * @param mainTextureId  the GL texture ID for the main camera stream
         * @param pipTextureId   the GL texture ID for the PIP camera stream
         */
        void onGLSurfaceCreated(int mainTextureId, int pipTextureId);

        /**
         * Called when the GL surface has been destroyed.
         */
        void onGLSurfaceDestroyed();
    }

    // =========================================================================
    // Constructors
    // =========================================================================

    /**
     * Creates a new GLComposerView with programmatic context.
     *
     * @param context the activity or application context
     */
    public GLComposerView(Context context) {
        super(context);
        init();
    }

    /**
     * Creates a new GLComposerView for inflation from XML layout.
     *
     * @param context the activity or application context
     * @param attrs   the XML attribute set
     */
    public GLComposerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /**
     * Common initialization logic called from all constructors.
     * <p>Sets up the GL context, rendering mode, and default configuration.</p>
     */
    private void init() {
        Log.d(TAG, "Initializing GLComposerView");

        // Set OpenGL ES 2.0 context
        setEGLContextClientVersion(2);

        // Use continuous rendering for smooth camera preview
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Set default PIP position to bottom-right (will be adjusted based on renderer)
        mCurrentPipX = 0.55f;
        mCurrentPipY = -0.55f;

        Log.d(TAG, "GLComposerView initialized");
    }

    // =========================================================================
    // Renderer management
    // =========================================================================

    /**
     * Sets the {@link CameraStreamRenderer} and configures it with a surface creation callback.
     * <p>This method also sets the renderer on the GLSurfaceView and preserves the
     * EGL context when paused to avoid recreating the GL state.</p>
     *
     * @param renderer the CameraStreamRenderer to use for rendering
     * @throws IllegalArgumentException if renderer is null
     */
    public void setRenderer(CameraStreamRenderer renderer) {
        if (renderer == null) {
            throw new IllegalArgumentException("Renderer must not be null");
        }

        mRenderer = renderer;

        // Set the surface created callback to forward texture IDs
        renderer.setSurfaceCreatedCallback((mainTextureId, pipTextureId) -> {
            Log.d(TAG, "Surface created callback: main=" + mainTextureId + ", pip=" + pipTextureId);

            // Create SurfaceTextures from the GL texture IDs
            try {
                mMainSurfaceTexture = new SurfaceTexture(mainTextureId);
                mFrontSurfaceTexture = new SurfaceTexture(pipTextureId);

                Log.d(TAG, "SurfaceTextures created from GL texture IDs");

                // Forward to external callback
                if (mSurfaceCallback != null) {
                    mSurfaceCallback.onGLSurfaceCreated(mainTextureId, pipTextureId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error creating SurfaceTextures from GL texture IDs", e);
            }
        });

        // Set the renderer on the GLSurfaceView
        super.setRenderer(renderer);

        // Preserve the EGL context when the activity is paused
        setPreserveEGLContextOnPause(true);

        Log.d(TAG, "CameraStreamRenderer set on GLSurfaceView");
    }

    // =========================================================================
    // Camera texture updates
    // =========================================================================

    /**
     * Updates the main (back) camera surface texture.
     * <p>If a SurfaceTexture was previously created from the GL texture ID, it is
     * released before setting the new one.</p>
     *
     * @param surfaceTexture the new SurfaceTexture for the main camera, or null to clear
     */
    public void updateMainCameraTexture(SurfaceTexture surfaceTexture) {
        if (mMainSurfaceTexture != null && mMainSurfaceTexture != surfaceTexture) {
            try {
                mMainSurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing previous main SurfaceTexture", e);
            }
        }
        mMainSurfaceTexture = surfaceTexture;
        Log.d(TAG, "Main camera SurfaceTexture updated");
    }

    /**
     * Updates the front (PIP) camera surface texture.
     * <p>If a SurfaceTexture was previously created from the GL texture ID, it is
     * released before setting the new one.</p>
     *
     * @param surfaceTexture the new SurfaceTexture for the front camera, or null to clear
     */
    public void updateFrontCameraTexture(SurfaceTexture surfaceTexture) {
        if (mFrontSurfaceTexture != null && mFrontSurfaceTexture != surfaceTexture) {
            try {
                mFrontSurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing previous front SurfaceTexture", e);
            }
        }
        mFrontSurfaceTexture = surfaceTexture;
        Log.d(TAG, "Front camera SurfaceTexture updated");
    }

    // =========================================================================
    // Configuration delegates
    // =========================================================================

    /**
     * Sets the aspect ratio for the composed output.
     * <p>Delegates to the underlying renderer, which adjusts the main camera
     * transformation matrix accordingly.</p>
     *
     * @param aspectRatio the desired aspect ratio
     * @throws IllegalArgumentException if aspectRatio is null
     */
    public void setAspectRatio(CameraConfig.AspectRatio aspectRatio) {
        if (aspectRatio == null) {
            throw new IllegalArgumentException("Aspect ratio must not be null");
        }
        if (mRenderer != null) {
            mRenderer.setAspectRatio(aspectRatio);
        }
        Log.d(TAG, "Aspect ratio set to: " + aspectRatio.label);
    }

    /**
     * Sets the PIP window scale factor.
     *
     * @param scale the PIP scale (0.1 - 0.5)
     */
    public void setPIPScale(float scale) {
        if (mRenderer != null) {
            mRenderer.setPIPScale(scale);
        }
    }

    /**
     * Sets the PIP window position programmatically.
     *
     * @param x normalized X position (-1.0 to 1.0)
     * @param y normalized Y position (-1.0 to 1.0)
     */
    public void setPIPPosition(float x, float y) {
        if (mRenderer != null) {
            mRenderer.setPIPPosition(x, y);
        }
        mCurrentPipX = x;
        mCurrentPipY = y;
    }

    /**
     * Enables or disables PIP window dragging via touch events.
     *
     * @param enabled true to enable dragging, false to disable
     */
    public void setPipDraggingEnabled(boolean enabled) {
        mPipDraggingEnabled = enabled;
        Log.d(TAG, "PIP dragging " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Sets the surface lifecycle callback for receiving creation/destruction events.
     *
     * @param callback the callback, or null to remove
     */
    public void setSurfaceLifecycleCallback(SurfaceLifecycleCallback callback) {
        mSurfaceCallback = callback;
    }

    // =========================================================================
    // Touch handling for PIP dragging
    // =========================================================================

    /**
     * Handles touch events for PIP window repositioning.
     * <p>When the user touches and drags in the PIP area, the PIP window follows
     * the finger. The PIP position is constrained to stay within the view bounds.</p>
     *
     * @param event the MotionEvent from the touch system
     * @return true if the event was consumed, false otherwise
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mPipDraggingEnabled || mRenderer == null) {
            return super.onTouchEvent(event);
        }

        final int action = event.getActionMasked();
        final float x = event.getX();
        final float y = event.getY();

        try {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (isTouchInPIPArea(x, y)) {
                        mIsDragging = true;
                        mLastTouchX = x;
                        mLastTouchY = y;
                        Log.d(TAG, "PIP drag started at (" + x + ", " + y + ")");
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (mIsDragging) {
                        float dx = x - mLastTouchX;
                        float dy = y - mLastTouchY;

                        // Check if movement exceeds touch slop
                        if (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP) {
                            // Convert pixel deltas to normalized coordinates
                            float normDx = dx / getWidth() * 2.0f;
                            float normDy = -dy / getHeight() * 2.0f; // Invert Y for GL

                            mCurrentPipX = Math.max(-1.0f, Math.min(1.0f, mCurrentPipX + normDx));
                            mCurrentPipY = Math.max(-1.0f, Math.min(1.0f, mCurrentPipY + normDy));

                            // Update renderer on the GL thread
                            queueEvent(() -> {
                                if (mRenderer != null) {
                                    mRenderer.setPIPPosition(mCurrentPipX, mCurrentPipY);
                                }
                            });

                            mLastTouchX = x;
                            mLastTouchY = y;
                        }
                        return true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mIsDragging) {
                        mIsDragging = false;
                        Log.d(TAG, "PIP drag ended at (" + mCurrentPipX + ", " + mCurrentPipY + ")");
                        return true;
                    }
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling touch event", e);
            mIsDragging = false;
        }

        return super.onTouchEvent(event);
    }

    /**
     * Checks whether a touch point falls within the PIP window area.
     * <p>Uses the PIP scale and position to calculate the bounding rectangle.</p>
     *
     * @param touchX the touch X coordinate in pixels
     * @param touchY the touch Y coordinate in pixels
     * @return true if the touch is within the PIP area, false otherwise
     */
    private boolean isTouchInPIPArea(float touchX, float touchY) {
        if (getWidth() <= 0 || getHeight() <= 0 || mRenderer == null) {
            return false;
        }

        float pipScale = 0.25f; // Default scale

        // Calculate PIP center in pixel coordinates
        float pipCenterX = ((mCurrentPipX + 1.0f) / 2.0f) * getWidth();
        float pipCenterY = ((-mCurrentPipY + 1.0f) / 2.0f) * getHeight(); // Invert Y

        // Calculate PIP half-size in pixels
        float pipHalfWidth = (pipScale / 2.0f) * getWidth();
        float pipHalfHeight = (pipScale / 2.0f) * getHeight();

        // Check if touch is within the PIP bounding box (with some padding)
        float padding = 20.0f; // Extra touch area padding in pixels
        boolean inArea = (touchX >= pipCenterX - pipHalfWidth - padding) &&
                (touchX <= pipCenterX + pipHalfWidth + padding) &&
                (touchY >= pipCenterY - pipHalfHeight - padding) &&
                (touchY <= pipCenterY + pipHalfHeight + padding);

        return inArea;
    }

    // =========================================================================
    // Accessors
    // =========================================================================

    /**
     * Returns the main camera SurfaceTexture.
     *
     * @return the main SurfaceTexture, or null if not created
     */
    public SurfaceTexture getMainSurfaceTexture() {
        return mMainSurfaceTexture;
    }

    /**
     * Returns the front camera SurfaceTexture.
     *
     * @return the front SurfaceTexture, or null if not created
     */
    public SurfaceTexture getFrontSurfaceTexture() {
        return mFrontSurfaceTexture;
    }

    /**
     * Returns the renderer instance.
     *
     * @return the CameraStreamRenderer, or null if not set
     */
    public CameraStreamRenderer getCameraStreamRenderer() {
        return mRenderer;
    }

    /**
     * Sets the video encoder's input Surface on the renderer.
     * <p>
     * When set, the GL renderer will compose each frame to both the screen
     * and the encoder surface simultaneously, enabling dual-camera video
     * recording with PIP overlay baked into the output.
     * </p>
     *
     * @param encoderSurface the encoder input Surface from VideoRecorder.getInputSurface(),
     *                       or null to stop recording
     */
    public void setInputSurface(Surface encoderSurface) {
        if (mRenderer != null) {
            mRenderer.setInputSurface(encoderSurface);
        }
    }

    /**
     * Releases the encoder surface resources from the renderer.
     * <p>Call this when recording is stopped.</p>
     */
    public void releaseEncoderSurface() {
        if (mRenderer != null) {
            mRenderer.releaseEncoderSurface();
        }
    }

    // =========================================================================
    // Lifecycle and cleanup
    // =========================================================================

    /**
     * Called when the GL surface is about to be destroyed.
     * <p>Releases GL resources via the renderer and notifies the lifecycle callback.</p>
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Log.d(TAG, "GLComposerView detached from window");

        // Queue GL resource release on the GL thread
        if (mRenderer != null) {
            final CameraStreamRenderer rendererRef = mRenderer;
            queueEvent(rendererRef::releaseGLResources);
        }

        // Notify callback
        if (mSurfaceCallback != null) {
            mSurfaceCallback.onGLSurfaceDestroyed();
        }
    }

    /**
     * Releases all resources held by this view.
     * <p>Call this when the view is no longer needed (e.g., in Activity.onDestroy).</p>
     */
    public void release() {
        Log.d(TAG, "Releasing GLComposerView");

        // Release SurfaceTextures
        if (mMainSurfaceTexture != null) {
            try {
                mMainSurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing main SurfaceTexture", e);
            }
            mMainSurfaceTexture = null;
        }

        if (mFrontSurfaceTexture != null) {
            try {
                mFrontSurfaceTexture.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing front SurfaceTexture", e);
            }
            mFrontSurfaceTexture = null;
        }

        // Queue GL resource release on the GL thread
        if (mRenderer != null) {
            final CameraStreamRenderer rendererRef = mRenderer;
            try {
                queueEvent(rendererRef::releaseGLResources);
            } catch (Exception e) {
                Log.e(TAG, "Error queuing GL resource release", e);
            }
        }

        mSurfaceCallback = null;
        mRenderer = null;

        Log.d(TAG, "GLComposerView released");
    }
}