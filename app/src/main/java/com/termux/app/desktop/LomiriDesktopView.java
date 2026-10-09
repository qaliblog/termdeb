package com.termux.app.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

/**
 * Hosts the Lomiri desktop surface.
 *
 * <p>Presents frames produced by the Mir compositor (running inside the Debian trixie
 * guest) onto this Android surface, and forwards touch input back to the guest bridge.
 * The compositor keeps its own device-independent coordinate space (the shared
 * framebuffer), so pointer coordinates are translated from view space to frame space
 * before being sent.
 *
 * <p>This view is the "app surface" that Mir's output is delivered to. It does not use
 * VNC or any remote-desktop transport.
 */
public final class LomiriDesktopView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final String LOG_TAG = "LomiriDesktopView";

    private static final long FRAME_INTERVAL_MS = 16;
    /** After this long with no frames, slow the poll loop down to save battery. */
    private static final long IDLE_INTERVAL_MS = 100;

    private final SurfaceHolder mHolder;
    private LomiriDesktopDisplay mDisplay;
    private Thread mRenderThread;
    private volatile boolean mRunning;

    public LomiriDesktopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHolder = getHolder();
        mHolder.addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setDisplay(LomiriDesktopDisplay display) {
        mDisplay = display;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        mRunning = true;
        mRenderThread = new Thread(this, "termdeb-desktop-render");
        mRenderThread.start();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.i(LOG_TAG, "Surface changed: " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        mRunning = false;
        Thread thread = mRenderThread;
        mRenderThread = null;
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        Rect dest = new Rect();
        while (mRunning) {
            LomiriDesktopDisplay display = mDisplay;
            Bitmap frame = display != null ? display.pollFrame() : null;

            if (frame != null) {
                Canvas canvas = null;
                try {
                    canvas = mHolder.lockCanvas();
                    if (canvas != null) {
                        canvas.drawColor(android.graphics.Color.BLACK);
                        computeDestination(canvas, frame, dest);
                        canvas.drawBitmap(frame, null, dest, null);
                    }
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Frame present failed", e);
                } finally {
                    if (canvas != null) {
                        try {
                            mHolder.unlockCanvasAndPost(canvas);
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
            }

            try {
                Thread.sleep(frame != null ? FRAME_INTERVAL_MS : IDLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Letterbox the frame into the surface, preserving the desktop aspect ratio. */
    private static void computeDestination(Canvas canvas, Bitmap frame, Rect out) {
        int viewWidth = canvas.getWidth();
        int viewHeight = canvas.getHeight();
        float scale = Math.min((float) viewWidth / frame.getWidth(), (float) viewHeight / frame.getHeight());
        int drawWidth = Math.round(frame.getWidth() * scale);
        int drawHeight = Math.round(frame.getHeight() * scale);
        int left = (viewWidth - drawWidth) / 2;
        int top = (viewHeight - drawHeight) / 2;
        out.set(left, top, left + drawWidth, top + drawHeight);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        LomiriDesktopDisplay display = mDisplay;
        if (display == null || !display.hasInputClient()) return super.onTouchEvent(event);

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) return true;

        // Map view coordinates into the letterboxed frame rectangle, then into frame pixels.
        float eventX = event.getX();
        float eventY = event.getY();

        int frameWidth = display.getFrameWidth();
        int frameHeight = display.getFrameHeight();
        if (frameWidth > 0 && frameHeight > 0) {
            float scale = Math.min((float) viewWidth / frameWidth, (float) viewHeight / frameHeight);
            int drawWidth = Math.round(frameWidth * scale);
            int drawHeight = Math.round(frameHeight * scale);
            int left = (viewWidth - drawWidth) / 2;
            int top = (viewHeight - drawHeight) / 2;
            eventX = (eventX - left) / scale;
            eventY = (eventY - top) / scale;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                display.sendTouch(LomiriDesktopDisplay.TOUCH_ACTION_DOWN, eventX, eventY);
                return true;
            case MotionEvent.ACTION_MOVE:
                display.sendTouch(LomiriDesktopDisplay.TOUCH_ACTION_MOVE, eventX, eventY);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                display.sendTouch(LomiriDesktopDisplay.TOUCH_ACTION_UP, eventX, eventY);
                return true;
            default:
                return true;
        }
    }
}
