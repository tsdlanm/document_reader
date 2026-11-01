package com.tsdlanm.documentreader.pdf;

import android.animation.ValueAnimator;
import android.content.ComponentCallbacks2;
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

import com.tsdlanm.documentreader.util.MemoryBudget;

import java.util.HashSet;

/**
 * Continuous vertical PDF view with pinch/double-tap zoom. Pages are rendered once at screen
 * width and kept in a small memory-bounded cache; when zoomed in, the visible area is rendered
 * sharply in tiles as soon as the zoom settles, also while scrolling, and kept so that panning
 * only renders the tiles that come into view.
 */
public final class PdfView extends View {
    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    public interface OnLinkListener {
        /** A link to an address outside the document was tapped. */
        void onLink(String uri);
    }

    private static final float MAX_ZOOM = 5f;
    /** Around the pages: the app's light or dark background. */
    private final int BACKGROUND;
    /** Upper bound for one cached page bitmap, in pixels. */
    private static final int MAX_PAGE_PIXELS = 2048 * 2048;
    /**
     * Side of a zoom tile in pixels: small enough that a short pan renders little, large enough
     * that a screen takes few renders, each of which walks the whole page.
     */
    private static final int TILE = 384;

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

    /** Low-RAM phone: cache pages as RGB_565, half the memory of what PdfRenderer draws into. */
    private final boolean compact;
    private PdfEngine engine;
    private PdfAnnotations.Link[][] links;
    private OnLinkListener linkListener;
    /** Bumped when the page layout changes; renders started before are dropped. */
    private int generation;
    private float[] tops = new float[0], ws = new float[0], hs = new float[0];
    private float contentH;
    private int viewW, viewH;
    private float zoom = 1f, sx, sy;
    /**
     * Space above the first page for the toolbar floating over the view: at the top the page
     * starts below it; scrolled, the page passes under it. Pixels.
     */
    private int topInset;

    public void setTopInset(int px) {
        boolean atTop = sy <= 0;
        topInset = px;
        if (atTop) sy = -px;
        invalidate();
    }
    private volatile int wantFirst, wantLast = -1;

    private boolean scaling, flinging, draggingThumb;
    private float thumbGrab;
    private int currentPage = -1;
    private OnPageChangeListener listener;
    private ValueAnimator zoomAnimator;

    /**
     * Tiles of the settled zoom {@code tileZoom}, and those of the zoom before, drawn scaled
     * where the new ones are not rendered yet. Both empty when not zoomed in.
     */
    private LruCache<Long, Bitmap> tiles, oldTiles;
    private float tileZoom, oldZoom;
    private final HashSet<Long> pendingTiles = new HashSet<>();
    /** Tiles of pages drawn differently by a replacing engine: shown until rendered again. */
    private final HashSet<Long> staleTiles = new HashSet<>();
    /** For the render thread: the zoom wanted and the visible content area at that zoom. */
    private volatile float wantZoom, wantLeft, wantTop, wantRight, wantBottom;

    private final PdfEngine.Wanted wanted = index -> index >= wantFirst && index <= wantLast;

    private final PdfEngine.TileWanted tileWanted = t -> t.zoom == wantZoom
            && t.left < wantRight && t.left + t.w > wantLeft
            && t.top < wantBottom && t.top + t.h > wantTop;

    public PdfView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        BACKGROUND = context.getColor(com.tsdlanm.documentreader.R.color.page_background);
        gap = 6 * density;
        white.setColor(Color.WHITE);
        thumbPaint.setColor(0x99666666);
        scroller = new OverScroller(context);
        setBackgroundColor(BACKGROUND);

        // A few screen-wide pages: enough for the visible pages and their neighbours. Capped, as a
        // share of the heap alone grew to 128 MB; smaller still on low-RAM phones.
        compact = MemoryBudget.low(context);
        int maxKb = MemoryBudget.kb(context, 32 * 1024, 6, 3, compact ? 2 : 4);
        cache = new LruCache<Integer, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        tiles = newTileCache();
        oldTiles = newTileCache();

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
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxScrollX(), -topInset, (int) maxScrollY());
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
                PdfAnnotations.Link link = linkAt(e.getX(), e.getY());
                if (link != null && linkListener != null) {
                    if (link.uri != null) linkListener.onLink(link.uri);
                    else goToPage(link.page);
                } else {
                    performClick();
                }
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
                invalidate();
            }
        });
    }

    /** Sized in {@link #onSizeChanged}, once the screen area to cover is known. */
    private LruCache<Long, Bitmap> newTileCache() {
        return new LruCache<Long, Bitmap>(1024) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public void setEngine(PdfEngine engine) {
        this.engine = engine;
        layoutPages();
        invalidate();
        engine.measureRest(this::pageSizesChanged);
    }

    /**
     * Switches to another rendering of the same document in which only the {@code changed}
     * pages look different. Those are rendered again while the old picture stays on screen; the
     * rest keep theirs. The old engine is closed by its owner.
     */
    public void replaceEngine(PdfEngine next, boolean[] changed) {
        PdfEngine old = engine;
        if (old == null || next.pageCount != old.pageCount) return;
        System.arraycopy(old.widths, 0, next.widths, 0, old.pageCount);
        System.arraycopy(old.heights, 0, next.heights, 0, old.pageCount);
        engine = next;
        generation++;
        pending.clear();
        pendingTiles.clear();
        if (!old.isMeasured()) next.measureRest(this::pageSizesChanged);
        for (int i = 0; i < changed.length && i < tops.length; i++) {
            if (!changed[i] || cache.get(i) == null) continue;
            if (i >= wantFirst && i <= wantLast) request(i, true);
            else cache.remove(i);
        }
        // Keep showing the sharp tiles of the changed pages until the new ones are drawn.
        for (Long key : tiles.snapshot().keySet()) {
            int page = (int) (key >>> 40);
            if (page < changed.length && changed[page]) staleTiles.add(key);
        }
        oldTiles.evictAll();
        invalidate();
    }

    public void setLinks(PdfAnnotations.Link[][] links, OnLinkListener listener) {
        this.links = links;
        linkListener = listener;
    }

    /** Whether a point on screen falls on a link, which a tap then follows. */
    public boolean isLinkAt(float rawX, float rawY) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        return linkAt(rawX - loc[0], rawY - loc[1]) != null;
    }

    private PdfAnnotations.Link linkAt(float x, float y) {
        if (links == null || tops.length == 0) return null;
        int page = pageAt((sy + y) / zoom);
        if (page >= links.length || links[page] == null) return null;
        pageRect(page, rect);
        if (!rect.contains(x, y)) return null;
        // A little slack around small links, as a finger is wider than a line of text.
        float slackX = 4 * density / rect.width(), slackY = 4 * density / rect.height();
        float fx = (x - rect.left) / rect.width(), fy = (y - rect.top) / rect.height();
        for (PdfAnnotations.Link l : links[page]) {
            if (fx >= l.left - slackX && fx <= l.right + slackX && fy >= l.top - slackY && fy <= l.bottom + slackY) {
                return l;
            }
        }
        return null;
    }

    /** Some page is not the size of the first: lay out again, keeping the page being read in place. */
    private void pageSizesChanged() {
        if (engine == null || tops.length == 0) return;
        int page = pageAt((sy + viewH / 2f) / zoom);
        float within = ((sy + viewH / 2f) / zoom - tops[page]) / Math.max(1f, hs[page]);
        generation++;
        cache.evictAll();
        pending.clear();
        dropTiles();
        layoutPages();
        if (page < tops.length) sy = (tops[page] + within * hs[page]) * zoom - viewH / 2f;
        clampScroll();
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
        sy = (tops[index] - gap) * zoom - topInset;
        clampScroll();
        invalidate();
    }

    /** Stops rendering and frees memory. The engine is closed by its owner. */
    public void release() {
        engine = null;
        cache.evictAll();
        dropTiles();
    }

    /** Frees cached pages when the system runs short of memory; they are re-rendered on demand. */
    public void trimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            cache.evictAll();
            dropTiles();
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            cache.trimToSize(cache.size() / 2);
            oldTiles.evictAll();
            tiles.trimToSize(tiles.size() / 2);
        }
        // Not re-rendered here (the app may be in the background): the next frame asks for it.
        invalidate();
    }

    /** Forgets all tiles; the next frame starts a new set if zoomed in. */
    private void dropTiles() {
        tiles.evictAll();
        oldTiles.evictAll();
        pendingTiles.clear();
        staleTiles.clear();
        tileZoom = 0;
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
            dropTiles();
            layoutPages();
        }
        // Room for the tiles that can show at once (a screen, plus the part tiles cut at its
        // edges) and half again, so that panning back and forth keeps them.
        long visible = (long) (w / TILE + 2) * (h / TILE + 2);
        int tileKb = (int) (visible * 3 / 2 * TILE * TILE * (compact ? 2 : 4) / 1024);
        tiles.resize(Math.max(1024, tileKb));
        oldTiles.resize(Math.max(1024, tileKb));
        // Rotation keeps the middle in view; a toolbar hiding or showing (same width) keeps the
        // scroll, so the page moves with the toolbar instead of jumping.
        if (contentH > 0 && widthChanged) sy = anchor * contentH * zoom - h / 2f;
        clampScroll();
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
        // Up to topInset above the first page: room for the floating toolbar.
        sy = Math.max(-topInset, Math.min(sy, maxScrollY()));
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

        drawTiles(canvas, first, last);
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

    // ------------------------------------------------------------ zoom tiles

    private void drawTiles(Canvas canvas, int first, int last) {
        if (zoom <= 1.01f) {
            // The screen-wide pages are sharp: free the tiles.
            if (tileZoom != 0) dropTiles();
            return;
        }
        boolean settled = !scaling && (zoomAnimator == null || !zoomAnimator.isRunning());
        if (settled && zoom != tileZoom) {
            // A new zoom: keep the current tiles, scaled, until the new ones cover the screen.
            LruCache<Long, Bitmap> t = oldTiles;
            t.evictAll();
            if (tileZoom != 0) {
                oldTiles = tiles;
                oldZoom = tileZoom;
                tiles = t;
            }
            pendingTiles.clear();
            staleTiles.clear();
            tileZoom = zoom;
        }
        if (tileZoom == 0) return;
        if (zoom == tileZoom) {
            float margin = TILE / 2f;
            wantLeft = sx - margin;
            wantTop = sy - margin;
            wantRight = sx + viewW + margin;
            wantBottom = sy + viewH + margin;
            wantZoom = zoom;
        } else {
            wantZoom = 0;
        }
        int missing = 0;
        for (int i = first; i <= last; i++) missing += tileLayer(canvas, i, tiles, tileZoom, false);
        if (missing > 0 && oldTiles.size() > 0) {
            for (int i = first; i <= last; i++) tileLayer(canvas, i, oldTiles, oldZoom, true);
        } else if (missing == 0 && zoom == tileZoom) {
            oldTiles.evictAll();
        }
        for (int i = first; i <= last; i++) tileLayer(canvas, i, tiles, tileZoom, true);
    }

    /**
     * The visible tiles of page {@code i} rendered at zoom {@code z}: drawn (scaled when the view
     * is at another zoom), or else counted and, at the current zoom, requested. Returns the
     * number missing.
     */
    private int tileLayer(Canvas canvas, int i, LruCache<Long, Bitmap> layer, float z, boolean draw) {
        float f = zoom / z;
        int pw = Math.round(ws[i] * z), ph = Math.round(hs[i] * z);
        float left = (viewW - ws[i]) / 2f * zoom - sx;
        float top = tops[i] * zoom - sy;
        if (f == 1f) {
            // Whole pixels, so that the tiles meet without seams and draw unscaled.
            left = Math.round(left);
            top = Math.round(top);
        }
        float step = TILE * f;
        int c0 = Math.max(0, (int) Math.floor(-left / step));
        int c1 = Math.min((pw - 1) / TILE, (int) Math.floor((viewW - left) / step));
        int r0 = Math.max(0, (int) Math.floor(-top / step));
        int r1 = Math.min((ph - 1) / TILE, (int) Math.floor((viewH - top) / step));
        int missing = 0;
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                long key = ((long) i << 40) | ((long) r << 20) | c;
                Bitmap b = layer.get(key);
                if (draw) {
                    if (b == null) continue;
                    if (f == 1f) {
                        canvas.drawBitmap(b, left + c * TILE, top + r * TILE, null);
                    } else {
                        float x = left + c * step, y = top + r * step;
                        rect.set(x, y, x + b.getWidth() * f, y + b.getHeight() * f);
                        canvas.drawBitmap(b, null, rect, bitmapPaint);
                    }
                } else if (b == null) {
                    missing++;
                    if (f == 1f) requestTile(i, r, c, key, pw, ph);
                } else if (f == 1f && staleTiles.contains(key)) {
                    requestTile(i, r, c, key, pw, ph);
                }
            }
        }
        return missing;
    }

    private void requestTile(int i, int r, int c, long key, int pw, int ph) {
        if (engine == null || !pendingTiles.add(key)) return;
        int x = c * TILE, y = r * TILE;
        float z = tileZoom;
        PdfEngine.Tile t = new PdfEngine.Tile(i, key, x, y, Math.min(TILE, pw - x), Math.min(TILE, ph - y),
                pw / engine.widths[i], z, (viewW - ws[i]) / 2f * z + x, tops[i] * z + y);
        final PdfEngine e = engine;
        final int gen = generation;
        e.renderTile(t, compact, tileWanted, (tile, bitmap) -> {
            if (gen != generation || engine != e || tile.zoom != tileZoom) return;
            pendingTiles.remove(tile.key);
            if (bitmap == null) {
                // Out of memory, most likely: free space, the next draw retries.
                if (tileWanted.isWanted(tile) && !e.isClosed()) tiles.trimToSize(tiles.size() / 2);
                return;
            }
            tiles.put(tile.key, bitmap);
            staleTiles.remove(tile.key);
            invalidate();
        });
    }

    private boolean thumbVisible() {
        return contentH * zoom > viewH * 2;
    }

    private float thumbHeight() {
        return Math.max(40 * density, viewH * viewH / (contentH * zoom));
    }

    private float thumbTop() {
        float max = maxScrollY();
        return max <= 0 ? 0 : (viewH - thumbHeight()) * Math.max(0, sy) / max;
    }

    private void drawThumb(Canvas canvas) {
        if (!thumbVisible()) return;
        float w = (draggingThumb ? 8 : 5) * density;
        float top = thumbTop();
        rect.set(viewW - w - 2 * density, top, viewW - 2 * density, top + thumbHeight());
        canvas.drawRoundRect(rect, w / 2, w / 2, thumbPaint);
    }

    private void request(int i) {
        request(i, false);
    }

    /** {@code again}: render even though a picture is cached, which stays until replaced. */
    private void request(final int i, boolean again) {
        if (engine == null || pending.get(i) || (!again && cache.get(i) != null)) return;
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
        final int gen = generation;
        e.renderPage(i, w, h, compact, wanted, (index, bitmap) -> {
            if (gen != generation) return;
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
                invalidate();
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
            }
            return true;
        }
        scaleGestures.onTouchEvent(e);
        gestures.onTouchEvent(e);
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
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (zoomAnimator != null) zoomAnimator.cancel();
    }
}
