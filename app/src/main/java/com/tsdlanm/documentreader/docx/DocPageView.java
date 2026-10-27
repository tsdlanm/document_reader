package com.tsdlanm.documentreader.docx;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Process;
import android.util.LruCache;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;
import android.widget.Toast;

import com.tsdlanm.documentreader.R;
import com.tsdlanm.documentreader.util.Tasks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Shows a {@link PageLayout} as a continuous column of pages with pinch and double-tap zoom.
 * At normal size each page is drawn once, on a background thread, into a cached bitmap, so
 * scrolling only copies pictures (tables with hundreds of cells would otherwise be redrawn every
 * frame). Zoomed in, pages are drawn as vectors so text stays sharp. Pages can arrive while the
 * rest of the document is still being laid out ({@link #addPages}).
 */
public final class DocPageView extends View {
    public interface OnPageChangeListener {
        void onPageChanged(int page);
    }

    private static final float MAX_ZOOM = 5f;
    private static final int BACKGROUND = 0xFFDDDDDD;

    private final float density;
    private final float gap;
    private final Paint white = new Paint();
    private final Paint edge = new Paint();
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;
    private final LruCache<String, Bitmap> images;
    private final Set<String> loading = new HashSet<>();

    private PageLayout layout;
    /** Pages laid out so far; only touched on the UI thread. */
    private final List<PageLayout.Page> pages = new ArrayList<>();
    private ZipFile zip;
    /** Screen pixels per layout unit at zoom 1 (widest page fits the view). */
    private volatile float fit = 1f;

    // Page bitmap cache (zoom 1). RGB_565: pages are opaque, and it halves the memory.
    private final LruCache<Integer, Bitmap> pageBitmaps;
    private final Set<Integer> rendering = new HashSet<>();
    /** Pages drawn while one of their pictures was still loading; redrawn when it arrives. */
    private final Set<Integer> waitingForImages = new HashSet<>();
    private final ExecutorService renderer = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "page-render"));
    /** Bumped when the bitmap size changes; results of older renders are dropped. */
    private int renderGeneration;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float[] tops = new float[0];
    private float contentH;
    private int viewW, viewH;
    private float zoom = 1f, sx, sy;
    private boolean scaling, draggingThumb;
    private float thumbGrab;
    private int currentPage = -1;
    private OnPageChangeListener listener;
    private ValueAnimator zoomAnimator;
    private float drawScale = 1f;

    private final PageLayout.Painter painter = new PageLayout.Painter() {
        @Override
        public Bitmap image(PageLayout.ImageItem item) {
            return imageFor(item, fit);
        }

        @Override
        public float scale() {
            return drawScale;
        }
    };

    public DocPageView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        gap = 8 * density;
        white.setColor(Color.WHITE);
        edge.setColor(0xFFBDBDBD);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, density / 2));
        thumbPaint.setColor(0x99666666);
        selectionPaint.setColor(0x553390FF);
        handlePaint.setColor(0xFF1A73E8);
        scroller = new OverScroller(context);
        setBackgroundColor(BACKGROUND);

        int maxKb = (int) Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / 1024 / 8);
        images = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        // A handful of screen-wide pages: enough for what is visible plus its neighbours.
        int pagesKb = (int) Math.min(32 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 5);
        pageBitmaps = new LruCache<Integer, Bitmap>(pagesKb) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        // Zoomed tiles (512 KB each): a couple of screens' worth.
        int tilesKb = (int) Math.min(16 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 8);
        tiles = new LruCache<Long, Bitmap>(tilesKb) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
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
                sx += dx;
                sy += dy;
                clampScroll();
                invalidate();
                // Keep the copy toolbar next to the selection while scrolling.
                if (actionMode != null) actionMode.invalidateContentRect();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling) return false;
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
                if (selStart != null) clearSelection();
                performClick();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (scaling) return;
                selectWordAt(e.getX(), e.getY());
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
            }
        });
    }

    public void setDocument(PageLayout layout, ZipFile zip) {
        this.layout = layout;
        this.zip = zip;
        pages.clear();
        dropBitmaps();
        layoutPages();
        invalidate();
    }

    /** Appends pages as the layout finishes them. */
    public void addPages(List<PageLayout.Page> more) {
        if (layout == null || more.isEmpty()) return;
        pages.addAll(more);
        layoutPages();
        invalidate();
    }

    public void setOnPageChangeListener(OnPageChangeListener l) {
        listener = l;
    }

    public int getPageCount() {
        return pages.size();
    }

    public int getCurrentPage() {
        return Math.max(0, currentPage);
    }

    public void goToPage(int index) {
        if (layout == null || index < 0 || index >= tops.length) return;
        scroller.forceFinished(true);
        sy = (tops[index] - gap) * zoom;
        clampScroll();
        invalidate();
    }

    public void release() {
        clearSelection();
        layout = null;
        pages.clear();
        dropBitmaps();
        renderer.shutdown();
        images.evictAll();
    }

    private void dropBitmaps() {
        renderGeneration++;
        pageBitmaps.evictAll();
        rendering.clear();
        waitingForImages.clear();
        dropTiles();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float anchor = contentH > 0 ? (sy + viewH / 2f) / (contentH * zoom) : 0f;
        viewW = w;
        viewH = h;
        layoutPages();
        if (contentH > 0) sy = anchor * contentH * zoom - h / 2f;
        clampScroll();
    }

    private void layoutPages() {
        if (layout == null || viewW == 0) return;
        float maxW = 1;
        for (PageLayout.Page p : pages) maxW = Math.max(maxW, p.width);
        float newFit = (viewW - 2 * gap) / maxW;
        // Bitmaps are made for one scale; a new scale (rotation, a wider page) needs new ones.
        if (newFit != fit) dropBitmaps();
        fit = newFit;
        tops = new float[pages.size()];
        float y = gap;
        for (int i = 0; i < pages.size(); i++) {
            tops[i] = y;
            y += pages.get(i).height * fit + gap;
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
        if (layout == null || tops.length == 0) return;
        float scale = fit * zoom;
        drawScale = scale;
        int first = pageAt(sy / zoom);
        int last = pageAt((sy + viewH) / zoom);
        visibleFirst = first;
        visibleLast = last;
        boolean atFit = zoom <= 1.01f;
        // While a pinch or zoom animation runs, stretched bitmaps keep it smooth.
        boolean zooming = scaling || (zoomAnimator != null && zoomAnimator.isRunning());
        for (int i = first; i <= last; i++) {
            PageLayout.Page page = pages.get(i);
            float w = page.width * scale, h = page.height * scale;
            float left = (viewW * zoom - w) / 2f - sx;
            float top = tops[i] * zoom - sy;
            rect.set(left, top, left + w, top + h);
            Bitmap cached = atFit || zooming ? pageBitmaps.get(i) : null;
            if (cached != null) {
                canvas.drawBitmap(cached, null, rect, bitmapPaint);
            } else if (!atFit) {
                drawTiles(canvas, i, page, left, top, scale);
            } else {
                canvas.drawRect(rect, white);
                requestRender(i);
                // Not cached yet: draw it directly, unless the cache thread is busy with these
                // Layouts (then the page stays blank for the moment it takes to finish).
                if (PageLayout.DRAW_LOCK.tryLock()) {
                    try {
                        canvas.save();
                        canvas.clipRect(rect);
                        canvas.translate(left, top);
                        canvas.scale(scale, scale);
                        layout.drawPage(canvas, page, painter, Math.max(0, -top) / scale, (viewH - top) / scale);
                        canvas.restore();
                    } finally {
                        PageLayout.DRAW_LOCK.unlock();
                    }
                }
            }
            canvas.drawRect(rect, edge);
            if (selStart != null && i >= selStart.page && i <= selEnd.page) {
                canvas.save();
                canvas.clipRect(rect);
                canvas.translate(left, top);
                canvas.scale(scale, scale);
                drawSelectionOn(canvas, i, page);
                canvas.restore();
            }
        }
        if (atFit) {
            // Get the neighbours ready before they scroll into view.
            if (first > 0) requestRender(first - 1);
            if (last + 1 < pages.size()) requestRender(last + 1);
        }
        drawHandles(canvas);
        drawThumb(canvas);

        int page = pageAt((sy + viewH / 2f) / zoom);
        if (page != currentPage) {
            currentPage = page;
            if (listener != null) post(() -> listener.onPageChanged(currentPage));
        }
    }

    private static boolean lockDraw() {
        PageLayout.DRAW_LOCK.lock();
        return true;
    }

    // ------------------------------------------------------- tiles when zoomed

    /** Tile edge in screen pixels. */
    private static final int TILE = 512;
    /** Sharp tiles at the current zoom, keyed by {@link #tileKey}. */
    private final LruCache<Long, Bitmap> tiles;
    private final Set<Long> tileRequests = new HashSet<>();
    /** Zoom the cached tiles were made for (x1000); a different zoom needs new tiles. */
    private int tileZoom;
    private volatile int tileGeneration;
    private boolean tilesWaitForImages;
    /** Visible pages, read by the render thread to skip tiles that scrolled away. */
    private volatile int visibleFirst, visibleLast;

    private static long tileKey(int page, int tx, int ty) {
        return ((long) page << 40) | ((long) tx << 20) | ty;
    }

    private void dropTiles() {
        tileGeneration++;
        tiles.evictAll();
        tileRequests.clear();
    }

    /**
     * Zoomed in: the page is cut into tiles drawn sharp at this zoom on the render thread. Until a
     * tile is ready, the fit-to-width page bitmap stretched underneath stands in for it.
     */
    private void drawTiles(Canvas canvas, int index, PageLayout.Page page, float left, float top, float scale) {
        int z = Math.round(zoom * 1000);
        if (z != tileZoom) {
            tileZoom = z;
            dropTiles();
        }
        canvas.save();
        canvas.clipRect(rect);
        Bitmap base = pageBitmaps.get(index);
        if (base != null) canvas.drawBitmap(base, null, rect, bitmapPaint);
        else {
            canvas.drawRect(rect, white);
            requestRender(index);
        }
        float pw = page.width * scale, ph = page.height * scale;
        int tx0 = Math.max(0, (int) Math.floor(-left / TILE));
        int tx1 = Math.min((int) Math.ceil(pw / TILE) - 1, (int) Math.floor((viewW - left) / TILE));
        int ty0 = Math.max(0, (int) Math.floor(-top / TILE));
        int ty1 = Math.min((int) Math.ceil(ph / TILE) - 1, (int) Math.floor((viewH - top) / TILE));
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                Bitmap t = tiles.get(tileKey(index, tx, ty));
                if (t != null) canvas.drawBitmap(t, left + tx * TILE, top + ty * TILE, null);
                else requestTile(index, tx, ty, scale);
            }
        }
        canvas.restore();
    }

    private void requestTile(final int index, final int tx, final int ty, final float scale) {
        final long key = tileKey(index, tx, ty);
        if (!tileRequests.add(key)) return;
        final PageLayout.Page page = pages.get(index);
        final PageLayout l = layout;
        final int generation = tileGeneration;
        final float imageScale = fit;
        renderer.execute(() -> {
            // Zoom changed or the page scrolled well away meanwhile: not worth drawing.
            if (generation != tileGeneration || index < visibleFirst - 1 || index > visibleLast + 1) {
                Tasks.main(() -> {
                    if (generation == tileGeneration) tileRequests.remove(key);
                });
                return;
            }
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565);
            } catch (OutOfMemoryError e) {
                Tasks.main(() -> tileRequests.remove(key));
                return;
            }
            bmp.eraseColor(Color.WHITE);
            Canvas c = new Canvas(bmp);
            c.translate(-tx * TILE, -ty * TILE);
            c.scale(scale, scale);
            final boolean[] missingImage = {false};
            PageLayout.Painter p = new PageLayout.Painter() {
                @Override
                public Bitmap image(PageLayout.ImageItem item) {
                    Bitmap b = imageFor(item, imageScale);
                    if (b == null) missingImage[0] = true;
                    return b;
                }

                @Override
                public float scale() {
                    return scale;
                }
            };
            PageLayout.DRAW_LOCK.lock();
            try {
                l.drawPage(c, page, p, ty * TILE / scale, (ty + 1) * TILE / scale);
            } finally {
                PageLayout.DRAW_LOCK.unlock();
            }
            Tasks.main(() -> {
                if (generation != tileGeneration || layout != l) return;
                tileRequests.remove(key);
                tiles.put(key, bmp);
                // Drawn without a picture that is still loading: redrawn when it arrives.
                if (missingImage[0]) tilesWaitForImages = true;
                invalidate();
            });
        });
    }

    /** Draws page {@code index} into a bitmap on the cache thread. */
    private void requestRender(final int index) {
        if (rendering.contains(index) || pageBitmaps.get(index) != null) return;
        final PageLayout.Page page = pages.get(index);
        final float scale = fit;
        final int w = Math.max(1, Math.round(page.width * scale));
        final int h = Math.max(1, Math.round(page.height * scale));
        final int generation = renderGeneration;
        final PageLayout l = layout;
        rendering.add(index);
        renderer.execute(() -> {
            Bitmap bmp;
            try {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            } catch (OutOfMemoryError e) {
                Tasks.main(() -> rendering.remove(index));
                return;
            }
            bmp.eraseColor(Color.WHITE);
            Canvas c = new Canvas(bmp);
            c.scale(scale, scale);
            final boolean[] missingImage = {false};
            PageLayout.Painter p = new PageLayout.Painter() {
                @Override
                public Bitmap image(PageLayout.ImageItem item) {
                    Bitmap b = imageFor(item, scale);
                    if (b == null) missingImage[0] = true;
                    return b;
                }

                @Override
                public float scale() {
                    return scale;
                }
            };
            PageLayout.DRAW_LOCK.lock();
            try {
                l.drawPage(c, page, p, 0, page.height);
            } finally {
                PageLayout.DRAW_LOCK.unlock();
            }
            Tasks.main(() -> {
                if (generation != renderGeneration || layout != l) return;
                rendering.remove(index);
                pageBitmaps.put(index, bmp);
                if (missingImage[0]) waitingForImages.add(index);
                invalidate();
            });
        });
    }

    /** Selection highlight on one page (drawn over the page, in layout units). */
    private void drawSelectionOn(Canvas canvas, int pageIndex, PageLayout.Page page) {
        List<PageLayout.Item> items = page.items;
        lockDraw();
        try {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) instanceof PageLayout.TextItem) {
                    drawSelection(canvas, pageIndex, i, (PageLayout.TextItem) items.get(i));
                }
            }
        } finally {
            PageLayout.DRAW_LOCK.unlock();
        }
    }

    // ------------------------------------------------------------ selection

    /** A place in the text: page, index of a TextItem in the page's items, character offset. */
    private static final class Pos {
        final int page, item, offset;

        Pos(int page, int item, int offset) {
            this.page = page;
            this.item = item;
            this.offset = offset;
        }

        int compareTo(Pos o) {
            if (page != o.page) return page < o.page ? -1 : 1;
            if (item != o.item) return item < o.item ? -1 : 1;
            return Integer.compare(offset, o.offset);
        }
    }

    private Pos selStart, selEnd;
    /** Which handle a finger is dragging: 0 none, 1 start, 2 end. */
    private int dragHandle;
    private ActionMode actionMode;
    private final Paint selectionPaint = new Paint();
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path selectionPath = new Path();

    private static int firstChar(PageLayout.TextItem t) {
        return t.layout.getLineStart(t.first);
    }

    private static int endChar(PageLayout.TextItem t) {
        return t.layout.getLineEnd(t.last);
    }

    private void drawSelection(Canvas canvas, int page, int index, PageLayout.TextItem t) {
        Pos here = new Pos(page, index, 0);
        if (here.compareTo(new Pos(selStart.page, selStart.item, 0)) < 0) return;
        if (here.compareTo(new Pos(selEnd.page, selEnd.item, 0)) > 0) return;
        int from = page == selStart.page && index == selStart.item ? selStart.offset : firstChar(t);
        int to = page == selEnd.page && index == selEnd.item ? selEnd.offset : endChar(t);
        from = Math.max(from, firstChar(t));
        to = Math.min(to, endChar(t));
        if (to <= from) return;
        // Built line by line: Layout.getSelectionPath ignores justification at line ends.
        android.text.Layout lay = t.layout;
        int l0 = lay.getLineForOffset(from), l1 = lay.getLineForOffset(to);
        if (l1 > l0 && to == lay.getLineStart(l1)) l1--;
        canvas.save();
        canvas.clipRect(-100000f, t.top, 100000f, t.bottom);
        canvas.translate(t.x, t.top - lay.getLineTop(t.first));
        for (int l = Math.max(l0, t.first); l <= Math.min(l1, t.last); l++) {
            float left = l == l0 ? xOf(t, l, from) : lay.getLineLeft(l);
            float right = l == l1 && to < lay.getLineEnd(l) ? xOf(t, l, to) : lineRight(t, l);
            if (right > left) {
                canvas.drawRect(left, lay.getLineTop(l), right, lay.getLineBottom(l), selectionPaint);
            }
        }
        canvas.restore();
    }

    /** True for the lines of a justified paragraph that are stretched to the full width. */
    private static boolean stretched(PageLayout.TextItem t, int line) {
        android.text.Layout lay = t.layout;
        int end = lay.getLineEnd(line);
        return t.justified && line < lay.getLineCount() - 1 && lay.getText().charAt(end - 1) != '\n';
    }

    /** Right edge of a line's text; stretched lines reach the full width. */
    private static float lineRight(PageLayout.TextItem t, int line) {
        return stretched(t, line) ? t.layout.getWidth() : t.layout.getLineRight(line);
    }

    /**
     * Horizontal position of an offset. Layout's own positions leave out the space that
     * justification adds, so it is spread over the line's spaces here, as the text is drawn.
     */
    private static float xOf(PageLayout.TextItem t, int line, int offset) {
        android.text.Layout lay = t.layout;
        float x = lay.getPrimaryHorizontal(offset);
        if (!stretched(t, line)) return x;
        int start = lay.getLineStart(line), end = lay.getLineVisibleEnd(line);
        CharSequence text = lay.getText();
        int spaces = 0, before = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == ' ') {
                spaces++;
                if (i < offset) before++;
            }
        }
        if (spaces == 0) return x;
        // When Android already justified the positions this extra is about zero.
        float extra = (lay.getWidth() - lay.getLineRight(line)) / spaces;
        return x + Math.max(0, extra) * before;
    }

    /** Offset on a line nearest to x, using the justified positions of {@link #xOf}. */
    private static int offsetAt(PageLayout.TextItem t, int line, float x) {
        android.text.Layout lay = t.layout;
        if (!stretched(t, line)) return lay.getOffsetForHorizontal(line, x);
        int best = lay.getLineStart(line);
        float bestDist = Float.MAX_VALUE;
        for (int off = lay.getLineStart(line); off <= lay.getLineVisibleEnd(line); off++) {
            float d = Math.abs(xOf(t, line, off) - x);
            if (d < bestDist) {
                bestDist = d;
                best = off;
            }
        }
        return best;
    }

    /** Text position nearest to a point on the screen, or null when there is no text near it. */
    private Pos hit(float vx, float vy) {
        if (layout == null || tops.length == 0) return null;
        float scale = fit * zoom;
        int pageIndex = pageAt((vy + sy) / zoom);
        PageLayout.Page page = pages.get(pageIndex);
        float left = (viewW * zoom - page.width * scale) / 2f - sx;
        float top = tops[pageIndex] * zoom - sy;
        float ux = (vx - left) / scale, uy = (vy - top) / scale;
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < page.items.size(); i++) {
            PageLayout.Item it = page.items.get(i);
            if (!(it instanceof PageLayout.TextItem)) continue;
            PageLayout.TextItem t = (PageLayout.TextItem) it;
            float dy = uy < t.top ? t.top - uy : uy > t.bottom ? uy - t.bottom : 0;
            float right = t.x + t.layout.getWidth();
            float dx = ux < t.x ? t.x - ux : ux > right ? ux - right : 0;
            float d = dy * 4 + dx;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        if (best < 0) return null;
        PageLayout.TextItem t = (PageLayout.TextItem) page.items.get(best);
        float lineY = uy - t.top + t.layout.getLineTop(t.first);
        int line = Math.max(t.first, Math.min(t.last, t.layout.getLineForVertical(Math.round(lineY))));
        int off = offsetAt(t, line, ux - t.x);
        off = Math.max(firstChar(t), Math.min(endChar(t), off));
        return new Pos(pageIndex, best, off);
    }

    private void selectWordAt(float x, float y) {
        Pos p = hit(x, y);
        if (p == null) return;
        PageLayout.TextItem t = item(p);
        CharSequence text = t.layout.getText();
        int s = p.offset, e = p.offset;
        while (s > firstChar(t) && Character.isLetterOrDigit(text.charAt(s - 1))) s--;
        while (e < endChar(t) && Character.isLetterOrDigit(text.charAt(e))) e++;
        if (e == s && e < endChar(t)) e++;
        selStart = new Pos(p.page, p.item, s);
        selEnd = new Pos(p.page, p.item, e);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        startSelectionMode();
        invalidate();
    }

    private PageLayout.TextItem item(Pos p) {
        return (PageLayout.TextItem) pages.get(p.page).items.get(p.item);
    }

    /** Screen position of a selection end: x and the bottom of its line. */
    private float[] screenPoint(Pos p) {
        float scale = fit * zoom;
        PageLayout.Page page = pages.get(p.page);
        PageLayout.TextItem t = item(p);
        float left = (viewW * zoom - page.width * scale) / 2f - sx;
        float top = tops[p.page] * zoom - sy;
        int line = t.layout.getLineForOffset(p.offset);
        // An offset at the very end of a line belongs visually to that line, not the next.
        if (line > t.first && p.offset == t.layout.getLineStart(line) && p == selEnd) line--;
        line = Math.max(t.first, Math.min(t.last, line));
        float ux = t.x + (p.offset == t.layout.getLineEnd(line) && p.offset > t.layout.getLineStart(line)
                ? lineRight(t, line) : xOf(t, line, p.offset));
        float uy = t.top + t.layout.getLineBottom(line) - t.layout.getLineTop(t.first);
        return new float[]{left + ux * scale, top + uy * scale};
    }

    /** Handle radius: small, so it hides as little text as possible. */
    private float handleRadius() {
        return 6.5f * density;
    }

    /**
     * Teardrop handles like Android's own: the point touches the selection's corner and the body
     * hangs outward (start to the left, end to the right), away from the selected text.
     */
    private void drawHandles(Canvas canvas) {
        if (selStart == null) return;
        float r = handleRadius();
        float[] s = screenPoint(selStart), e = screenPoint(selEnd);
        canvas.drawCircle(s[0] - r, s[1] + r, r, handlePaint);
        canvas.drawRect(s[0] - r, s[1], s[0], s[1] + r, handlePaint);
        canvas.drawCircle(e[0] + r, e[1] + r, r, handlePaint);
        canvas.drawRect(e[0], e[1], e[0] + r, e[1] + r, handlePaint);
    }

    /** Starts dragging a handle if the touch is on one (the touch area is larger than the handle). */
    private boolean grabHandle(float x, float y) {
        if (selStart == null) return false;
        float reach = 24 * density;
        float r = handleRadius();
        float[] s = screenPoint(selStart), e = screenPoint(selEnd);
        float ds = (float) Math.hypot(x - (s[0] - r), y - (s[1] + r));
        float de = (float) Math.hypot(x - (e[0] + r), y - (e[1] + r));
        if (Math.min(ds, de) > reach) return false;
        dragHandle = de <= ds ? 2 : 1;
        // The floating toolbar steps aside while a handle is dragged.
        if (actionMode != null) actionMode.hide(60000);
        return true;
    }

    private void dragHandleTo(float x, float y) {
        // The finger holds the handle below the line; aim at the line above it.
        Pos p = hit(x, y - 14 * density);
        if (p == null) return;
        if (dragHandle == 1) {
            selStart = p;
            if (selStart.compareTo(selEnd) > 0) {
                selStart = selEnd;
                selEnd = p;
                dragHandle = 2;
            }
        } else {
            selEnd = p;
            if (selEnd.compareTo(selStart) < 0) {
                selEnd = selStart;
                selStart = p;
                dragHandle = 1;
            }
        }
        invalidate();
    }

    private void selectAll() {
        Pos first = null, last = null;
        for (int pi = 0; pi < pages.size(); pi++) {
            List<PageLayout.Item> items = pages.get(pi).items;
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof PageLayout.TextItem)) continue;
                PageLayout.TextItem t = (PageLayout.TextItem) items.get(i);
                if (first == null) first = new Pos(pi, i, firstChar(t));
                last = new Pos(pi, i, endChar(t));
            }
        }
        if (first == null) return;
        selStart = first;
        selEnd = last;
        invalidate();
    }

    /** Selected text; paragraphs split over pages are joined, separate items get a line break. */
    private String selectedText() {
        StringBuilder sb = new StringBuilder();
        android.text.Layout prev = null;
        for (int pi = selStart.page; pi <= selEnd.page; pi++) {
            List<PageLayout.Item> items = pages.get(pi).items;
            int from = pi == selStart.page ? selStart.item : 0;
            int to = pi == selEnd.page ? selEnd.item : items.size() - 1;
            for (int i = from; i <= to; i++) {
                if (!(items.get(i) instanceof PageLayout.TextItem)) continue;
                PageLayout.TextItem t = (PageLayout.TextItem) items.get(i);
                int s = pi == selStart.page && i == selStart.item ? selStart.offset : firstChar(t);
                int e = pi == selEnd.page && i == selEnd.item ? selEnd.offset : endChar(t);
                if (e <= s) continue;
                if (sb.length() > 0 && t.layout != prev) sb.append('\n');
                sb.append(t.layout.getText(), s, e);
                prev = t.layout;
            }
        }
        // Spans hold the text; tab and line-break characters stay as they are.
        return sb.toString().trim();
    }

    private void copySelection() {
        String text = selectedText();
        if (text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("text", text));
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(getContext(), R.string.copied, Toast.LENGTH_SHORT).show();
        }
    }

    public void clearSelection() {
        selStart = selEnd = null;
        dragHandle = 0;
        if (actionMode != null) {
            ActionMode m = actionMode;
            actionMode = null;
            m.finish();
        }
        invalidate();
    }

    private static final int MENU_COPY = 1, MENU_SELECT_ALL = 2;

    /** Floating copy toolbar placed next to the selection. */
    private final ActionMode.Callback2 selectionCallback = new ActionMode.Callback2() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            menu.add(0, MENU_COPY, 0, android.R.string.copy);
            menu.add(0, MENU_SELECT_ALL, 1, android.R.string.selectAll);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() == MENU_COPY) {
                copySelection();
                clearSelection();
                return true;
            }
            if (item.getItemId() == MENU_SELECT_ALL) {
                selectAll();
                mode.invalidateContentRect();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (actionMode == mode) {
                actionMode = null;
                selStart = selEnd = null;
                invalidate();
            }
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            if (selStart == null) {
                outRect.set(0, 0, getWidth(), 0);
                return;
            }
            float[] s = screenPoint(selStart), e = screenPoint(selEnd);
            int lineH = Math.round(20 * density);
            outRect.set(Math.round(Math.min(s[0], e[0])), Math.round(s[1]) - lineH,
                    Math.round(Math.max(s[0], e[0])), Math.round(e[1]));
        }
    };

    private void startSelectionMode() {
        if (actionMode != null) actionMode.invalidateContentRect();
        else actionMode = startActionMode(selectionCallback, ActionMode.TYPE_FLOATING);
    }

    /**
     * Decoded picture for an image item, or null while it loads. Called on the UI thread and on
     * the page-cache thread; {@code scale} is the fit-to-width scale.
     */
    private Bitmap imageFor(final PageLayout.ImageItem item, float scale) {
        // Decode at twice the fit-to-width size: sharp enough when zoomed in a little.
        final int targetW = Math.max(16, Math.min(2048, Math.round(item.rect.width() * scale * 2)));
        final String key = item.part + "@" + targetW;
        Bitmap b = images.get(key);
        final ZipFile z = zip;
        if (b != null || z == null) return b;
        synchronized (loading) {
            if (!loading.add(key)) return null;
        }
        Tasks.io().execute(() -> {
            final Bitmap bmp = ZipImages.decode(z, item.part, targetW);
            Tasks.main(() -> {
                synchronized (loading) {
                    loading.remove(key);
                }
                if (bmp == null || layout == null) return;
                images.put(key, bmp);
                // Cached pages drawn without this picture are redrawn.
                for (Integer page : waitingForImages) pageBitmaps.remove(page);
                waitingForImages.clear();
                if (tilesWaitForImages) {
                    tilesWaitForImages = false;
                    dropTiles();
                }
                invalidate();
            });
        });
        return null;
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

    // ------------------------------------------------------------- gestures

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
        zoomAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                invalidate();
            }
        });
        zoomAnimator.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && grabHandle(e.getX(), e.getY())) {
            scroller.forceFinished(true);
            return true;
        }
        if (dragHandle != 0) {
            if (action == MotionEvent.ACTION_MOVE) {
                dragHandleTo(e.getX(), e.getY());
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                dragHandle = 0;
                startSelectionMode();
                if (actionMode != null) actionMode.hide(0);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
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
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (zoomAnimator != null) zoomAnimator.cancel();
    }
}
