package com.tommasov.mg4assistant.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A slow drift of coloured light behind the assistant, brightest while it is speaking.
 *
 * <p>Three soft blobs wander on unhurried sine paths and overlap. The colours are the
 * launcher's own icon gradient — #536dfe to #6a3de8 — with the shared accent between them, so
 * the effect reads as part of this car's software rather than decoration borrowed from a
 * phone.
 *
 * <p><b>Why it is drawn into a postage stamp.</b> Filling 1778x720 with three overlapping
 * radial gradients every frame is a lot of pixels for a 2018 head unit, and the result would
 * be a stutter exactly while the assistant is talking. So the blobs are drawn into a buffer an
 * eighth of the size — about 28,000 pixels instead of 1.8 million — and that buffer is scaled
 * up with filtering. On gradients this soft the upscale is invisible; the fill cost falls by
 * roughly sixty times.
 *
 * <p>Nothing is allocated while drawing. The shaders are built once per size change and moved
 * each frame with a {@link Matrix}, which is the cheap way to animate a gradient: rebuilding a
 * {@link RadialGradient} per frame would undo everything the small buffer just saved.
 *
 * <p>When the intensity reaches zero the view stops asking for frames. An animation that keeps
 * running behind an idle screen is a car quietly burning power at a standstill.
 */
public class AmbientGlowView extends View {

    /** How much smaller the offscreen buffer is than the view. */
    private static final int SCALE = 8;

    /** Full strength on a dark background; less on a light one, where it muddies quickly. */
    private static final float MAX_ALPHA_DARK = 0.55f;
    private static final float MAX_ALPHA_LIGHT = 0.30f;

    /** Seconds for the slowest blob to come back where it started. Unhurried on purpose. */
    private static final float[] SPEEDS = {0.055f, 0.081f, 0.043f};
    private static final float[] PHASES = {0f, 2.1f, 4.3f};
    private static final int[] COLOURS = {0xFF536DFE, 0xFF056EFF, 0xFF6A3DE8};

    /** How fast the glow fades in and out, in units of intensity per second. */
    private static final float FADE_PER_SECOND = 1.6f;

    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blitPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private final RadialGradient[] gradients = new RadialGradient[COLOURS.length];

    @Nullable private Bitmap buffer;
    @Nullable private Canvas bufferCanvas;

    private float maxAlpha = MAX_ALPHA_DARK;
    private float intensity;
    private float targetIntensity;
    private long lastFrameNanos;
    private float elapsedSeconds;
    private float blobRadius;

    public AmbientGlowView(Context context) {
        this(context, null);
    }

    public AmbientGlowView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        maxAlpha = mode == Configuration.UI_MODE_NIGHT_YES ? MAX_ALPHA_DARK : MAX_ALPHA_LIGHT;
        setWillNotDraw(false);
    }

    /**
     * How present the glow should be, from 0 (gone) to 1 (speaking). Values in between suit
     * the quieter states: listening, or waiting for an answer.
     */
    public void setIntensity(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (clamped == targetIntensity) {
            return;
        }
        targetIntensity = clamped;
        if (targetIntensity > 0f || intensity > 0f) {
            lastFrameNanos = 0;
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        int bufferWidth = Math.max(1, width / SCALE);
        int bufferHeight = Math.max(1, height / SCALE);
        buffer = Bitmap.createBitmap(bufferWidth, bufferHeight, Bitmap.Config.ARGB_8888);
        bufferCanvas = new Canvas(buffer);
        source.set(0, 0, bufferWidth, bufferHeight);
        destination.set(0, 0, width, height);

        // Larger than the buffer: the blobs should spill off every edge, so that what shows is
        // the middle of the light and never the ring where it runs out.
        blobRadius = Math.max(bufferWidth, bufferHeight) * 0.75f;
        for (int i = 0; i < COLOURS.length; i++) {
            gradients[i] = new RadialGradient(0f, 0f, blobRadius,
                    COLOURS[i], COLOURS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap target = buffer;
        Canvas into = bufferCanvas;
        if (target == null || into == null) {
            return;
        }

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        // A frame that arrives very late — the app was backgrounded, the system stalled —
        // should not make the glow jump across the screen.
        deltaSeconds = Math.min(deltaSeconds, 0.1f);
        elapsedSeconds += deltaSeconds;

        if (intensity < targetIntensity) {
            intensity = Math.min(targetIntensity, intensity + FADE_PER_SECOND * deltaSeconds);
        } else if (intensity > targetIntensity) {
            intensity = Math.max(targetIntensity, intensity - FADE_PER_SECOND * deltaSeconds);
        }
        if (intensity <= 0f) {
            // Nothing to show yet. If something is still being faded towards, ask for another
            // frame anyway — the first frame after a state change carries a delta of zero, so
            // leaving here without scheduling the next one is how the glow never starts at all.
            if (targetIntensity > 0f) {
                postInvalidateOnAnimation();
            }
            return;
        }

        into.drawColor(0, PorterDuff.Mode.CLEAR);
        float width = target.getWidth();
        float height = target.getHeight();
        for (int i = 0; i < gradients.length; i++) {
            float t = elapsedSeconds * SPEEDS[i] * (float) (Math.PI * 2) + PHASES[i];
            // Different multipliers on the two axes so the paths never settle into a circle
            // that the eye can follow and predict.
            float cx = width * (0.5f + 0.42f * (float) Math.sin(t));
            float cy = height * (0.5f + 0.38f * (float) Math.cos(t * 1.37f + PHASES[i]));
            matrix.setTranslate(cx, cy);
            gradients[i].setLocalMatrix(matrix);
            blobPaint.setShader(gradients[i]);
            into.drawRect(0f, 0f, width, height, blobPaint);
        }

        blitPaint.setAlpha((int) (intensity * maxAlpha * 255));
        canvas.drawBitmap(target, source, destination, blitPaint);

        if (intensity != targetIntensity || targetIntensity > 0f) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        targetIntensity = 0f;
        intensity = 0f;
    }

    @Override
    public boolean hasOverlappingRendering() {
        // The blobs are composited into the buffer, not onto each other on screen, so the
        // system can skip the offscreen layer it would otherwise allocate for this view.
        return false;
    }

    @NonNull
    @Override
    public String toString() {
        return "AmbientGlowView(intensity=" + intensity + ")";
    }
}
