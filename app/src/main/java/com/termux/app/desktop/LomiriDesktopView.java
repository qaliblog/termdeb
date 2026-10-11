package com.termux.app.desktop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>While no frame has arrived yet the view explains itself: bringing the guest up
 * takes a while (proot, Mir, Lomiri, first paint) and a desktop that never gets there
 * would otherwise just be a black screen. The producer publishes what it is doing in
 * the shared framebuffer header, so the surface can name the state and repeat the
 * producer's own last log lines.
 */
public final class LomiriDesktopView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final String LOG_TAG = "LomiriDesktopView";

    private static final long FRAME_INTERVAL_MS = 16;
    /** After this long with no frames, slow the poll loop down to save battery. */
    private static final long IDLE_INTERVAL_MS = 100;
    /** Grace period before the view says anything about missing frames. */
    private static final long NOTICE_DELAY_MS = 4000;
    /** How often the notice is redrawn while the desktop is not up yet. */
    private static final long NOTICE_REFRESH_MS = 2000;

    private final SurfaceHolder mHolder;
    private final Paint mNoticePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LomiriDesktopDisplay mDisplay;
    private Thread mRenderThread;
    private volatile boolean mRunning;

    public LomiriDesktopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mHolder = getHolder();
        mHolder.addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
        mNoticePaint.setColor(Color.WHITE);
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
        long started = SystemClock.elapsedRealtime();
        long lastNotice = 0;

        while (mRunning) {
            LomiriDesktopDisplay display = mDisplay;
            Bitmap frame = display != null ? display.pollFrame() : null;

            if (frame != null) {
                presentFrame(frame, dest);
                lastNotice = 0;
            } else if (display != null) {
                long now = SystemClock.elapsedRealtime();
                if (now - started >= NOTICE_DELAY_MS
                        && (lastNotice == 0 || now - lastNotice >= NOTICE_REFRESH_MS)) {
                    if (presentNotice(display, (now - started) / 1000))
                        lastNotice = now;
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

    private void presentFrame(@NonNull Bitmap frame, @NonNull Rect dest) {
        Canvas canvas = null;
        try {
            canvas = mHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
                computeDestination(canvas, frame, dest);
                canvas.drawBitmap(frame, null, dest, null);
            }
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Frame present failed", e);
        } finally {
            unlock(canvas);
        }
    }

    /**
     * Replace the black surface with an explanation of why there is no frame yet: the
     * producer's state, how long the desktop has been starting, and its own last log
     * lines when it has failed.
     */
    private boolean presentNotice(@NonNull LomiriDesktopDisplay display, long seconds) {
        Canvas canvas = null;
        try {
            canvas = mHolder.lockCanvas();
            if (canvas == null) return false;

            int state = display.getProducerState();
            float textSize = Math.max(22f, Math.min(canvas.getWidth(), canvas.getHeight()) / 24f);
            mNoticePaint.setTextSize(textSize);

            canvas.drawColor(Color.BLACK);
            float x = textSize;
            float y = textSize * 2f;
            float lineHeight = textSize * 1.35f;
            float maxWidth = canvas.getWidth() - 2f * x;
            for (String line : noticeLines(display, state, seconds, maxWidth)) {
                canvas.drawText(line, x, y, mNoticePaint);
                y += lineHeight;
                if (y > canvas.getHeight() - textSize) break;
            }
            return true;
        } catch (RuntimeException e) {
            Log.w(LOG_TAG, "Notice present failed", e);
            return false;
        } finally {
            unlock(canvas);
        }
    }

    private List<String> noticeLines(@NonNull LomiriDesktopDisplay display, int state,
                                     long seconds, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (state == LomiriDesktopDisplay.STATE_ERROR) {
            lines.add("TermDeb desktop: the guest display bridge failed.");
            lines.addAll(wrap(display.getProducerReason(), maxWidth));
            lines.add("Details are in the desktop session log and runtime/bridge.log.");
            return lines;
        }
        if (state == LomiriDesktopDisplay.STATE_STALLED) {
            lines.add("TermDeb desktop: the compositor is not producing frames.");
            lines.addAll(wrap(display.getProducerReason(), maxWidth));
            lines.add("Details are in the desktop session log and runtime/bridge.log.");
            return lines;
        }
        lines.add("TermDeb desktop is starting (" + seconds + "s)...");
        lines.add("Entering Debian, starting Mir and the Lomiri shell. The first frame can");
        lines.add("take a while; this screen fills in as soon as it arrives.");
        return lines;
    }

    @Nullable
    private List<String> wrap(@Nullable String text, float maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;

        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            while (!word.isEmpty()) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (mNoticePaint.measureText(candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    word = "";
                    break;
                }
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                // A single word longer than the line: hard-split it.
                int fit = word.length();
                while (fit > 1 && mNoticePaint.measureText(word.substring(0, fit)) > maxWidth) fit--;
                lines.add(word.substring(0, fit));
                word = word.substring(fit);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private void unlock(@Nullable Canvas canvas) {
        if (canvas == null) return;
        try {
            mHolder.unlockCanvasAndPost(canvas);
        } catch (RuntimeException ignored) {
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
