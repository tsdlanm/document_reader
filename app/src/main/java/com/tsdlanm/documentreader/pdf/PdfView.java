package com.tsdlanm.documentreader.pdf;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.LruCache;
import android.util.SparseBooleanArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

/**
 * Continuous vertical PDF view with pinch/double-tap zoom. Pages are rendered once at screen
 * width and kept in a small memory-bounded cache; when zoomed in, only the visible area is
 * re-rendered sharply after the gesture stops.
 */
public final class PdfView extends View {
    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    private static final float MAX_ZOOM = 5f;
    private static final int BACKGROUND = 0xFFDDDDDD;
    /** Upper bound for one cached page bitmap, in pixels. */
    private static final int MAX_PAGE_PIXELS = 2048 * 2048;

    private final float density;
    private final float gap;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint white = new Paint();
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private final LruCache<Integer, Bitmap> cache;
    private final SparseBooleanArray pending = new SparseBooleanArray();

    private PdfEngine engine;
    private float[] tops = new float[0], ws = new float[0], hs = new float[0];
    private float contentH;
    private int viewW, viewH;
    private float zoom = 1f, sx, sy;
    private volatile int wantFirst, wantLast = -1;

    private boolean scaling, flinging, draggingThumb;
    private float thumbGrab;
    private int currentPage = -1;
    private OnPageChangeListener listener;
    private ValueAnimator zoomAnimator;

    private Bitmap detailFront, detailBack;
    private float detailZoom, detailSx, detailSy;
    private boolean detailValid, detailBusy, detailDirty;

    private final Runnable detailRunnable = this::requestDetail;

    private final PdfEngine.Wanted wanted = index -> index >= wantFirst && index <= wantLast;

    public PdfView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        gap = 6 * density;
        white.setColor(Color.WHITE);
        thumbPaint.setColor(0x99666666);
        scroller = new OverScroller(context);
        setBackgroundColor(BACKGROUND);

        // Keep page bitmaps within a quarter of the heap (pre-Android 8 bitmaps live on the heap).
        int maxKb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 4);
        cache = new LruCache<Integer, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling) return false;
                scrollByPx(dx, dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling) return false;
                flinging = true;
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxScrollX(), 0, (int) maxScrollY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                animateZoom(zoom > 1.5f ? 1f : 2.5f, e.getX(), e.getY());
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                performClick();
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                zoomAround(zoom * d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
                scheduleDetail();
            }
        });
    }

    public void setEngine(PdfEngine engine) {
        this.engine = engine;
        layoutPages();
        invalidate();
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        listener = l;
    }

    public int getPageCount() {
        return engine == null ? 0 : engine.pageCount;
    }

    public void goToPage(int index) {
        if (engine == null || index < 0 || index >= tops.length) return;
        scroller.forceFinished(true);
        sy = (tops[index] - gap) * zoom;
        clampScroll();
        invalidate();
        scheduleDetail();
    }

    /** Stops rendering and frees memory. The engine is closed by its owner. */
    public void release() {
        removeCallbacks(detailRunnable);
        engine = null;
        cache.evictAll();
        detailFront = detailBack = null;
        detailValid = false;
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float anchor = contentH > 0 ? (sy + viewH / 2f) / (contentH * zoom) : 0f;
        boolean widthChanged = w != viewW;
        viewW = w;
        viewH = h;
        if (widthChanged) {
            cache.evictAll();
            pending.clear();
            layoutPages();
        }
        detailValid = false;
        detailFront = detailBack = null;
        if (contentH > 0) sy = anchor * contentH * zoom - h / 2f;
        clampScroll();
        scheduleDetail();
    }

    private void layoutPages() {
        if (engine == null || viewW == 0) return;
        int n = engine.pageCount;
        float maxW = 1;
        for (float w : engine.widths) maxW = Math.max(maxW, w);
        float unit = viewW / maxW;
        tops = new float[n];
        ws = new float[n];
        hs = new float[n];
        float y = gap;
        for (int i = 0; i < n; i++) {
            ws[i] = engine.widths[i] * unit;
            hs[i] = engine.heights[i] * unit;
            tops[i] = y;
            y += hs[i] + gap;
        }
        contentH = y;
        clampScroll();
    }

    private float maxScrollX() {
        return Math.max(0, viewW * zoom - viewW);
    }

    private float maxScrollY() {
        return Math.max(0, contentH * zoom - viewH);
    }

    private void clampScroll() {
        sx = Math.max(0, Math.min(sx, maxScrollX()));
        sy = Math.max(0, Math.min(sy, maxScrollY()));
    }

    private int pageAt(float y) {
        int lo = 0, hi = tops.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (tops[mid] <= y) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    // --------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        if (engine == null || tops.length == 0) return;
        int first = pageAt(sy / zoom);
        int last = pageAt((sy + viewH) / zoom);
        wantFirst = first - 1;
        wantLast = last + 1;
        for (int i = first; i <= last; i++) {
            pageRect(i, rect);
            Bitmap b = cache.get(i);
            if (b != null) {
                canvas.drawBitmap(b, null, rect, bitmapPaint);
            } else {
                canvas.drawRect(rect, white);
                request(i);
            }
        }
        if (last + 1 < tops.length) request(last + 1);
        if (first > 0) request(first - 1);

        if (detailValid && detailFront != null && zoom > 1.01f && detailZoom == zoom) {
            canvas.drawBitmap(detailFront, detailSx - sx, detailSy - sy, null);
        }
        drawThumb(canvas);

        int page = pageAt((sy + viewH / 2f) / zoom);
        if (page != currentPage) {
            currentPage = page;
            if (listener != null) post(() -> listener.onPageChanged(currentPage));
        }
    }

    private void pageRect(int i, RectF out) {
        float left = (viewW - ws[i]) / 2f * zoom - sx;
        float top = tops[i] * zoom - sy;
        out.set(left, top, left + ws[i] * zoom, top + hs[i] * zoom);
    }

    private boolean thumbVisible() {
        return contentH * zoom > viewH * 2;
    }

    private float thumbHeight() {
        return Math.max(40 * density, viewH * viewH / (contentH * zoom));
    }

    private float thumbTop() {
        float max = maxScrollY();
        return max <= 0 ? 0 : (viewH - thumbHeight()) * sy / max;
    }

    private void drawThumb(Canvas canvas) {
        if (!thumbVisible()) return;
        float w = (draggingThumb ? 8 : 5) * density;
        float top = thumbTop();
        rect.set(viewW - w - 2 * density, top, viewW - 2 * density, top + thumbHeight());
        canvas.drawRoundRect(rect, w / 2, w / 2, thumbPaint);
    }

    private void request(final int i) {
        if (engine == null || pending.get(i) || cache.get(i) != null) return;
        pending.put(i, true);
        int w = Math.max(1, Math.round(ws[i]));
        int h = Math.max(1, Math.round(hs[i]));
        if ((long) w * h > MAX_PAGE_PIXELS) {
            float f = (float) Math.sqrt(MAX_PAGE_PIXELS / ((double) w * h));
            w = Math.max(1, (int) (w * f));
            h = Math.max(1, (int) (h * f));
        }
        final int expectedW = w;
        final PdfEngine e = engine;
        e.renderPage(i, w, h, wanted, (index, bitmap) -> {
            pending.delete(index);
            if (bitmap == null) {
                if (engine == e && index >= wantFirst && index <= wantLast && !e.isClosed()) {
                    // Most likely out of memory: free space, the next draw retries.
                    cache.trimToSize(cache.size() / 2);
                }
                return;
            }
            if (engine != e || bitmap.getWidth() != expectedW) return;
            cache.put(index, bitmap);
            invalidate();
        });
    }

    // ------------------------------------------------------- sharp zoom detail

    private void scheduleDetail() {
        removeCallbacks(detailRunnable);
        if (engine == null) return;
        if (zoom > 1.01f) {
            postDelayed(detailRunnable, 150);
        } else if (!detailBusy) {
            // Not zoomed: free the two screen-sized buffers.
            detailFront = detailBack = null;
            detailValid = false;
        }
    }

    private void requestDetail() {
        if (engine == null || viewW == 0 || viewH == 0 || zoom <= 1.01f) return;
        if (scaling || flinging || draggingThumb) {
            scheduleDetail();
            return;
        }
        if (detailBusy) {
            detailDirty = true;
            return;
        }
        if (detailValid && detailZoom == zoom && detailSx == sx && detailSy == sy) return;
        if (detailBack == null || detailBack.getWidth() != viewW || detailBack.getHeight() != viewH) {
            try {
                detailBack = Bitmap.createBitmap(viewW, viewH, Bitmap.Config.ARGB_8888);
            } catch (OutOfMemoryError oom) {
                detailBack = null;
                return;
            }
        }
        int first = pageAt(sy / zoom);
        int last = pageAt((sy + viewH) / zoom);
        PdfEngine.Region[] regions = new PdfEngine.Region[last - first + 1];
        for (int i = first; i <= last; i++) {
            pageRect(i, rect);
            regions[i - first] = new PdfEngine.Region(i, rect.left, rect.top, ws[i] / engine.widths[i] * zoom);
        }
        final float z = zoom, x = sx, y = sy;
        final Bitmap target = detailBack;
        final PdfEngine e = engine;
        detailBusy = true;
        e.renderDetail(target, regions, BACKGROUND, () -> {
            detailBusy = false;
            if (engine != e) return;
            if (target.getWidth() != viewW || target.getHeight() != viewH) {
                // The view was resized while rendering; start over at the new size.
                scheduleDetail();
                return;
            }
            detailBack = detailFront;
            detailFront = target;
            detailZoom = z;
            detailSx = x;
            detailSy = y;
            detailValid = true;
            invalidate();
            if (detailDirty || zoom != z || sx != x || sy != y) {
                detailDirty = false;
                scheduleDetail();
            }
        });
    }

    // ------------------------------------------------------------- gestures

    private void scrollByPx(float dx, float dy) {
        sx += dx;
        sy += dy;
        clampScroll();
        invalidate();
    }

    private void zoomAround(float newZoom, float fx, float fy) {
        newZoom = Math.max(1f, Math.min(MAX_ZOOM, newZoom));
        float ratio = newZoom / zoom;
        sx = (sx + fx) * ratio - fx;
        sy = (sy + fy) * ratio - fy;
        zoom = newZoom;
        clampScroll();
        invalidate();
    }

    private void animateZoom(float target, final float fx, final float fy) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        scroller.forceFinished(true);
        zoomAnimator = ValueAnimator.ofFloat(zoom, target);
        zoomAnimator.setDuration(200);
        zoomAnimator.addUpdateListener(a -> zoomAround((Float) a.getAnimatedValue(), fx, fy));
        zoomAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                scheduleDetail();
            }
        });
        zoomAnimator.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            flinging = false;
            if (thumbVisible() && e.getX() > viewW - 32 * density) {
                float top = thumbTop();
                if (e.getY() >= top - 16 * density && e.getY() <= top + thumbHeight() + 16 * density) {
                    draggingThumb = true;
                    thumbGrab = e.getY() - top;
                    invalidate();
                    return true;
                }
            }
        }
        if (draggingThumb) {
            if (action == MotionEvent.ACTION_MOVE) {
                float range = viewH - thumbHeight();
                float f = range <= 0 ? 0 : (e.getY() - thumbGrab) / range;
                sy = Math.max(0, Math.min(1, f)) * maxScrollY();
                clampScroll();
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                draggingThumb = false;
                invalidate();
                scheduleDetail();
            }
            return true;
        }
        scaleGestures.onTouchEvent(e);
        gestures.onTouchEvent(e);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flinging) {
            scheduleDetail();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            sx = scroller.getCurrX();
            sy = scroller.getCurrY();
            clampScroll();
            postInvalidateOnAnimation();
        } else if (flinging) {
            flinging = false;
            scheduleDetail();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (zoomAnimator != null) zoomAnimator.cancel();
        removeCallbacks(detailRunnable);
    }
}
