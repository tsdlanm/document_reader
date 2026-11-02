package com.tsdlanm.documentreader.xlsx;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;
import android.widget.Toast;

import com.tsdlanm.documentreader.R;
import com.tsdlanm.documentreader.docx.FontMatch;
import com.tsdlanm.documentreader.util.MemoryBudget;
import com.tsdlanm.documentreader.util.Tasks;
import com.tsdlanm.documentreader.util.ZipImages;

import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Draws a worksheet directly on a canvas: only the visible cells are touched each frame, so large
 * sheets scroll smoothly without creating thousands of views.
 */
public final class SheetView extends View {
    public interface OnCellSelectedListener {
        void onCellSelected(int row, int col, String text);
    }

    private static final float SIZE_FACTOR = CellPainter.SIZE_FACTOR;
    private static final float MIN_ZOOM = 0.35f, MAX_ZOOM = 4f;
    private static final int SELECT = 0xFF1A73E8, SELECT_FILL = 0x221A73E8;
    /** Row and column headers follow the app's light or dark colours; the cells stay paper. */
    private final int HEADER_BG, HEADER_LINE, HEADER_TEXT, HEADER_SELECTED;
    private static final int FREEZE_LINE = 0xFF9E9E9E, PLACEHOLDER = 0xFFEEEEEE;
    private static final float EMU_PER_PX = 9525f;


    private final float density;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint headerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    /** Cells drawn on the main thread (while pinching, and the gridlines under missing tiles). */
    private final CellPainter painter;
    private final LruCache<String, Bitmap> images;
    private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
    private ZipFile zip;
    private int frozenRows, frozenCols;
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    private SheetData data;
    /**
     * Prefix sums of column widths / row heights at zoom 1, in px; length count + 1. Tiles drawn
     * in the background read them, so they are replaced, never changed in place.
     */
    private float[] colX = new float[1], rowY = new float[1];
    private float headerW, headerH;
    private float baseTextPx;
    private float zoom = 1f, sx, sy;
    private boolean scaling, flinging;
    private int selRow = -1, selCol = -1;
    private OnCellSelectedListener listener;

    public SheetView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        HEADER_BG = context.getColor(R.color.header_bg);
        HEADER_LINE = context.getColor(R.color.header_line);
        HEADER_TEXT = context.getColor(R.color.header_text);
        HEADER_SELECTED = context.getColor(R.color.header_selected);
        setBackgroundColor(Color.WHITE);
        // One screen pixel, like Excel's gridlines: thicker ones crowd out the text.
        line.setStrokeWidth(1f);
        int tilesKb = MemoryBudget.kb(context, 16 * 1024, 8, 3f, 2);
        tiles = newTileCache(tilesKb);
        oldTiles = newTileCache(tilesKb);
        int imagesKb = MemoryBudget.kb(context, 16 * 1024, 8, 0, 2);
        images = new LruCache<String, Bitmap>(imagesKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        headerText.setColor(HEADER_TEXT);
        headerText.setTextAlign(Paint.Align.CENTER);
        scroller = new OverScroller(context);
        baseTextPx = 11f * 1.333f * SIZE_FACTOR * density;
        painter = new CellPainter(density, baseTextPx, HEADER_LINE, HEADER_TEXT);
        tilePainter = new CellPainter(density, baseTextPx, HEADER_LINE, HEADER_TEXT);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scaling) return false;
                // Lock to one axis for mostly-straight swipes; easier to read rows and columns.
                if (Math.abs(dx) > 2 * Math.abs(dy)) dy = 0;
                else if (Math.abs(dy) > 2 * Math.abs(dx)) dx = 0;
                sx += dx;
                sy += dy;
                clamp();
                invalidate();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scaling) return false;
                if (Math.abs(vx) > 2 * Math.abs(vy)) vy = 0;
                else if (Math.abs(vy) > 2 * Math.abs(vx)) vx = 0;
                flinging = true;
                scroller.fling((int) sx, (int) sy, (int) -vx, (int) -vy, 0, (int) maxX(), 0, (int) maxY());
                postInvalidateOnAnimation();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                selectAt(e.getX(), e.getY());
                performClick();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAround(zoom < 1.4f ? 2f : 1f, e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (scaling) return;
                selectAt(e.getX(), e.getY());
                copyCell();
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
                // Draw the tiles at the new zoom.
                invalidate();
            }
        });
    }

    public void setOnCellSelectedListener(OnCellSelectedListener l) {
        listener = l;
    }

    /** A downloaded font arrived: re-measure and redraw the text in it. */
    private final Runnable fontsChanged = () -> {
        resetPainters();
        dropTiles();
        invalidate();
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        FontMatch.addListener(fontsChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        FontMatch.removeListener(fontsChanged);
        // Tiles still queued are dropped.
        dropTiles();
        super.onDetachedFromWindow();
    }

    /** The workbook's zip, for pictures on the sheets. */
    public void setZip(ZipFile zip) {
        this.zip = zip;
    }

    public void setData(SheetData d) {
        data = d;
        dropTiles();
        images.evictAll();
        failed.clear();
        frozenRows = d == null ? 0 : d.frozenRows;
        frozenCols = d == null ? 0 : d.frozenCols;
        selRow = selCol = -1;
        matches = new int[0];
        matchCurrent = -1;
        sx = sy = 0;
        scroller.forceFinished(true);
        int cols = d == null ? 0 : d.colCount;
        int rows = d == null ? 0 : d.rowCount;
        float px = SIZE_FACTOR * density;
        // Like Excel, the grid goes on past the data: empty columns and rows fill the screen even
        // fully zoomed out, plus a few more to scroll into.
        float[] dataX = new float[cols + 1], dataY = new float[rows + 1];
        for (int c = 0; c < cols; c++) dataX[c + 1] = dataX[c] + d.colWidths[c] * px;
        for (int r = 0; r < rows; r++) dataY[r + 1] = dataY[r] + d.rowHeights[r] * 1.333f * px;
        int extraCols = 0, extraRows = 0;
        float colW = 0, rowH = 0;
        if (d != null && !d.isEmpty()) {
            colW = Math.max(8f, d.defaultColWidth * px);
            rowH = Math.max(4f, d.defaultRowHeight * 1.333f * px);
            float screenW = getResources().getDisplayMetrics().widthPixels / MIN_ZOOM;
            float screenH = getResources().getDisplayMetrics().heightPixels / MIN_ZOOM;
            extraCols = Math.max(3, (int) Math.ceil((screenW - dataX[cols]) / colW));
            extraRows = Math.max(10, (int) Math.ceil((screenH - dataY[rows]) / rowH));
        }
        colX = java.util.Arrays.copyOf(dataX, cols + extraCols + 1);
        rowY = java.util.Arrays.copyOf(dataY, rows + extraRows + 1);
        for (int c = cols; c < cols + extraCols; c++) colX[c + 1] = colX[c] + colW;
        for (int r = rows; r < rows + extraRows; r++) rowY[r + 1] = rowY[r] + rowH;

        headerText.setTextSize(11 * density);
        String widest = String.valueOf(Math.max(1, rowY.length - 1));
        headerW = Math.max(32 * density, headerText.measureText(widest) + 12 * density);
        headerH = 22 * density;
        invalidate();
    }

    // ------------------------------------------------------------- geometry

    private float maxX() {
        return Math.max(0, colX[colX.length - 1] * zoom - (getWidth() - headerW * zoom));
    }

    private float maxY() {
        return Math.max(0, rowY[rowY.length - 1] * zoom - (getHeight() - topInset - headerH * zoom));
    }

    private void clamp() {
        sx = Math.max(0, Math.min(sx, maxX()));
        sy = Math.max(0, Math.min(sy, maxY()));
    }

    private static int find(float[] edges, float v) {
        return CellPainter.find(edges, v);
    }

    /** Horizontal and vertical scroll of the region being drawn: 0 inside frozen panes. */
    private float curSx, curSy;

    private float cellLeft(int c) {
        return headerW * zoom + colX[c] * zoom - curSx;
    }

    private float cellTop(int r) {
        return headerH * zoom + rowY[r] * zoom - curSy;
    }

    /** Uses the scroll of the pane that holds this cell. */
    private void scrollFor(int r, int c) {
        curSx = c < frozenCols ? 0 : sx;
        curSy = r < frozenRows ? 0 : sy;
    }

    /** Right / bottom screen edge of the frozen panes. */
    private float frozenRight() {
        return headerW * zoom + colX[frozenCols] * zoom;
    }

    private float frozenBottom() {
        return headerH * zoom + rowY[frozenRows] * zoom;
    }

    private void zoomAround(float z, float fx, float fy) {
        z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
        float hx = headerW * zoom, hy = headerH * zoom;
        float contentX = (sx + fx - hx) / zoom;
        float contentY = (sy + fy - hy) / zoom;
        zoom = z;
        sx = contentX * zoom - (fx - headerW * zoom);
        sy = contentY * zoom - (fy - headerH * zoom);
        clamp();
        invalidate();
    }

    private void selectAt(float x, float y) {
        y -= topInset;
        if (data == null || data.isEmpty()) return;
        float vx = x - headerW * zoom, vy = y - headerH * zoom;
        if (vx < 0 || vy < 0) return;
        // Inside a frozen pane the sheet does not scroll.
        float cx = vx < colX[frozenCols] * zoom ? vx / zoom : (vx + sx) / zoom;
        float cy = vy < rowY[frozenRows] * zoom ? vy / zoom : (vy + sy) / zoom;
        if (cx >= colX[colX.length - 1] || cy >= rowY[rowY.length - 1]) return;
        int c = find(colX, cx), r = find(rowY, cy);
        int[] f = data.filterRange;
        if (f != null && filterListener != null && r == f[0] && c >= f[1] && c <= f[3]
                && cx >= colX[c + 1] - CellPainter.filterButtonSize(rowY, r, density) - 6 * density) {
            filterListener.onFilter(c);
            return;
        }
        int[] m = mergeAt(r, c);
        if (m != null) {
            r = m[0];
            c = m[1];
        }
        selRow = r;
        selCol = c;
        invalidate();
        if (listener != null) listener.onCellSelected(r, c, data.text(r, c));
    }

    /** Frees decoded pictures and cached text layouts when memory runs short; rebuilt on demand. */
    public void trimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            images.evictAll();
            resetPainters();
            dropTiles();
            invalidate();
        }
    }


    public void clearSelection() {
        selRow = selCol = -1;
        invalidate();
    }

    /** Copies the selected cell's text to the clipboard. */
    private void copyCell() {
        if (data == null || selRow < 0) return;
        String text = data.text(selRow, selCol);
        if (text == null || text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("cell", text));
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(getContext(), R.string.copied, Toast.LENGTH_SHORT).show();
    }

    private int[] mergeAt(int r, int c) {
        return CellPainter.mergeAt(data.merges, r, c);
    }

    // -------------------------------------------------------------- drawing

    /**
     * Room above the sheet for the toolbar, which floats over the view (pixels). A sheet keeps
     * its toolbar, so the whole sheet, column letters included, is drawn below it.
     */
    private int topInset;

    public void setTopInset(int px) {
        topInset = px;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (topInset <= 0) {
            drawSheet(canvas);
            return;
        }
        canvas.save();
        canvas.translate(0, topInset);
        drawSheet(canvas);
        canvas.restore();
    }

    private void drawSheet(Canvas canvas) {
        if (data == null || data.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        float hx = headerW * zoom, hy = headerH * zoom;
        float fx = Math.min(w, frozenRight()), fy = Math.min(h, frozenBottom());
        int lastRow = rowY.length - 2, lastCol = colX.length - 2;

        // Pinching changes the zoom every frame: the tiles drawn so far are shown scaled, and
        // tiles at the new zoom are drawn once the fingers lift.
        if (!scaling && zoom != tileZoom) startTileZoom();
        painter.set(data, colX, rowY, headerW, headerH, zoom);
        wantCount = 0;
        // The scrolling part first, then the frozen panes over it.
        pane(canvas, sx, sy, fx, fy, w, h, frozenRows, lastRow, frozenCols, lastCol, true);
        if (frozenCols > 0) pane(canvas, 0, sy, hx, fy, fx, h, frozenRows, lastRow, 0, frozenCols - 1, false);
        if (frozenRows > 0) pane(canvas, sx, 0, fx, hy, w, fy, 0, frozenRows - 1, frozenCols, lastCol, false);
        if (frozenRows > 0 && frozenCols > 0) pane(canvas, 0, 0, hx, hy, fx, fy, 0, frozenRows - 1, 0, frozenCols - 1, false);
        wantAreas = java.util.Arrays.copyOf(wantBuf, wantCount);
        lastSx = sx;
        lastSy = sy;

        drawPictures(canvas, w, h);
        drawMatches(canvas, w, h);
        drawSelection(canvas, w, h);

        line.setColor(FREEZE_LINE);
        if (frozenCols > 0) canvas.drawLine(fx, hy, fx, h, line);
        if (frozenRows > 0) canvas.drawLine(hx, fy, w, fy, line);
        drawHeaders(canvas, w, h, hx, hy, fx, fy);
    }

    // ---------------------------------------------------------------- tiles

    /**
     * The cells are drawn into tiles at the current zoom on a background thread, and the frames
     * only place those bitmaps, as in the Word page view: drawing the cells takes far longer than
     * a frame on a low-end phone. Where a tile is not ready yet the gridlines show, or the tiles of
     * the previous zoom, scaled. Selection, pictures and headers are drawn over them directly.
     * Tiles cover the sheet in zoomed pixels from cell A1, so the frozen panes reuse the same tiles.
     */
    private static final int TILE = 384;
    private static Handler tileHandler;
    private LruCache<Long, Bitmap> tiles, oldTiles;
    private float tileZoom = -1, oldZoom;
    private final HashSet<Long> pendingTiles = new HashSet<>();
    /** Bumped when the tiles in progress no longer fit: another zoom, sheet, font or row layout. */
    private int tileGen;
    /** Tile thread only. */
    private final CellPainter tilePainter;
    private final Canvas tileCanvas = new Canvas();
    /**
     * For the tile thread: the current generation, and the parts of the sheet the panes show
     * (left, top, right, bottom in zoomed pixels, a tile wider each way), so tiles that scrolled
     * away are skipped.
     */
    private volatile int wantGen;
    private volatile float[] wantAreas = new float[0];
    private final float[] wantBuf = new float[16];
    private int wantCount;
    /** Scroll at the last frame, for the direction to draw tiles ahead in. */
    private float lastSx, lastSy;

    private static synchronized Handler tileHandler() {
        if (tileHandler == null) {
            HandlerThread t = new HandlerThread("sheet", Process.THREAD_PRIORITY_DEFAULT);
            t.start();
            tileHandler = new Handler(t.getLooper());
        }
        return tileHandler;
    }

    private static LruCache<Long, Bitmap> newTileCache(int kb) {
        return new LruCache<Long, Bitmap>(kb) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    /** Cell contents or sizes changed: the tiles are drawn again. */
    private void dropTiles() {
        tiles.evictAll();
        oldTiles.evictAll();
        pendingTiles.clear();
        tileZoom = -1;
        wantGen = ++tileGen;
    }

    /** Fonts or memory changed: both painters measure their text again. */
    private void resetPainters() {
        painter.reset();
        tileHandler().post(tilePainter::reset);
    }

    /** Settled at a new zoom: the current tiles become the stand-ins until the new ones are drawn. */
    private void startTileZoom() {
        LruCache<Long, Bitmap> spare = oldTiles;
        spare.evictAll();
        if (tileZoom > 0 && tiles.size() > 0) {
            oldTiles = tiles;
            oldZoom = tileZoom;
            tiles = spare;
        }
        pendingTiles.clear();
        tileZoom = zoom;
        wantGen = ++tileGen;
    }

    /**
     * Draws a pane: content scrolled by psx/psy into the screen box, from the tiles, with the
     * gridlines and the previous zoom's tiles under the ones still missing. {@code ahead}: also
     * draw the tiles about to scroll into view.
     */
    private void pane(Canvas canvas, float psx, float psy, float left, float top, float right, float bottom,
                      int minRow, int maxRow, int minCol, int maxCol, boolean ahead) {
        if (right <= left || bottom <= top) return;
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        boolean exact = zoom == tileZoom;
        if (exact && wantCount + 4 <= wantBuf.length) {
            float ox = headerW * zoom - psx, oy = headerH * zoom - psy;
            wantBuf[wantCount++] = left - ox - TILE;
            wantBuf[wantCount++] = top - oy - TILE;
            wantBuf[wantCount++] = right - ox + TILE;
            wantBuf[wantCount++] = bottom - oy + TILE;
        }
        int missing = exact ? layer(canvas, tiles, tileZoom, psx, psy, left, top, right, bottom, false) : 1;
        if (missing > 0) {
            painter.drawGridOnly(canvas, psx, psy, left, top, right, bottom, minRow, maxRow, minCol, maxCol);
            if (oldTiles.size() > 0) layer(canvas, oldTiles, oldZoom, psx, psy, left, top, right, bottom, true);
        } else if (ahead) {
            drawAhead(psx, psy, left, top, right, bottom);
        }
        layer(canvas, tiles, tileZoom, psx, psy, left, top, right, bottom, true);
        canvas.restore();
    }

    /**
     * The tiles of {@code layer}, drawn at zoom {@code z}, over the screen box: drawn (scaled when
     * the view is at another zoom), or else counted and requested. Returns the number missing.
     */
    private int layer(Canvas canvas, LruCache<Long, Bitmap> layer, float z, float psx, float psy,
                      float left, float top, float right, float bottom, boolean draw) {
        if (z <= 0) return 0;
        float f = zoom / z, step = TILE * f;
        float ox = headerW * zoom - psx, oy = headerH * zoom - psy;
        if (f == 1f) {
            // Whole pixels, so that the tiles meet without seams and draw unscaled.
            ox = Math.round(ox);
            oy = Math.round(oy);
        }
        int tx0 = Math.max(0, (int) Math.floor((left - ox) / step));
        int ty0 = Math.max(0, (int) Math.floor((top - oy) / step));
        int tx1 = Math.min(lastTile(colX, z), (int) Math.floor((right - ox - 1) / step));
        int ty1 = Math.min(lastTile(rowY, z), (int) Math.floor((bottom - oy - 1) / step));
        int missing = 0;
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                long key = ((long) tx << 32) | (ty & 0xFFFFFFFFL);
                Bitmap b = layer.get(key);
                if (!draw) {
                    if (b == null) {
                        missing++;
                        requestTile(tx, ty, key);
                    }
                } else if (b != null) {
                    if (f == 1f) {
                        canvas.drawBitmap(b, ox + tx * TILE, oy + ty * TILE, null);
                    } else {
                        float x = ox + tx * step, y = oy + ty * step;
                        rect.set(x, y, x + step, y + step);
                        canvas.drawBitmap(b, null, rect, bitmapPaint);
                    }
                }
            }
        }
        return missing;
    }

    /** Index of the last tile across the sheet's {@code edges} at zoom {@code z}. */
    private static int lastTile(float[] edges, float z) {
        return (int) Math.ceil(edges[edges.length - 1] * z / TILE) - 1;
    }

    /**
     * With the visible tiles all drawn, the next row or column in the direction of scrolling is
     * drawn too, so that it is usually ready when it comes into view. Only when the cache holds
     * it besides the visible tiles, which it must not push out.
     */
    private void drawAhead(float psx, float psy, float left, float top, float right, float bottom) {
        float dx = sx - lastSx, dy = sy - lastSy;
        if (dx == 0 && dy == 0) return;
        float ox = Math.round(headerW * zoom - psx), oy = Math.round(headerH * zoom - psy);
        int tx0 = Math.max(0, (int) Math.floor((left - ox) / TILE));
        int ty0 = Math.max(0, (int) Math.floor((top - oy) / TILE));
        int tx1 = Math.min(lastTile(colX, zoom), (int) Math.floor((right - ox - 1) / TILE));
        int ty1 = Math.min(lastTile(rowY, zoom), (int) Math.floor((bottom - oy - 1) / TILE));
        boolean vertical = Math.abs(dy) >= Math.abs(dx);
        int extra = vertical ? tx1 - tx0 + 1 : ty1 - ty0 + 1;
        long visible = (long) (tx1 - tx0 + 1) * (ty1 - ty0 + 1);
        if ((visible + extra) * TILE * TILE * 2 / 1024 > tiles.maxSize()) return;
        if (vertical) {
            int ty = dy > 0 ? ty1 + 1 : ty0 - 1;
            if (ty < 0 || ty > lastTile(rowY, zoom)) return;
            for (int tx = tx0; tx <= tx1; tx++) aheadTile(tx, ty);
        } else {
            int tx = dx > 0 ? tx1 + 1 : tx0 - 1;
            if (tx < 0 || tx > lastTile(colX, zoom)) return;
            for (int ty = ty0; ty <= ty1; ty++) aheadTile(tx, ty);
        }
    }

    private void aheadTile(int tx, int ty) {
        long key = ((long) tx << 32) | (ty & 0xFFFFFFFFL);
        if (tiles.get(key) == null) requestTile(tx, ty, key);
    }

    /** Queues the tile at (tx, ty) of the current zoom for the tile thread. */
    private void requestTile(final int tx, final int ty, final long key) {
        if (!pendingTiles.add(key)) return;
        final int gen = tileGen;
        final float z = tileZoom;
        final SheetData d = data;
        final float[] cx = colX, ry = rowY;
        final float hw = headerW, hh = headerH;
        tileHandler().post(() -> {
            Bitmap b = null;
            if (gen == wantGen && wanted(tx * TILE, ty * TILE)) b = drawTile(d, cx, ry, hw, hh, z, tx, ty);
            final Bitmap result = b;
            Tasks.main(() -> {
                if (gen != tileGen) return;
                pendingTiles.remove(key);
                if (result == null) return;
                tiles.put(key, result);
                invalidate();
            });
        });
    }

    /** Tile thread: whether a tile at (x, y) in zoomed pixels is in or near a pane on screen. */
    private boolean wanted(float x, float y) {
        float[] a = wantAreas;
        for (int i = 0; i + 3 < a.length; i += 4) {
            if (x < a[i + 2] && x + TILE > a[i] && y < a[i + 3] && y + TILE > a[i + 1]) return true;
        }
        return false;
    }

    /** Tile thread: the cells of one tile, or null out of memory. */
    private Bitmap drawTile(SheetData d, float[] cx, float[] ry, float hw, float hh, float z, int tx, int ty) {
        Bitmap b;
        try {
            b = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565);
        } catch (OutOfMemoryError e) {
            return null;
        }
        b.eraseColor(Color.WHITE);
        tileCanvas.setBitmap(b);
        try {
            tilePainter.set(d, cx, ry, hw, hh, z);
            tilePainter.drawPane(tileCanvas, hw * z + tx * TILE, hh * z + ty * TILE, 0, 0, TILE, TILE,
                    0, ry.length - 2, 0, cx.length - 2);
        } catch (RuntimeException e) {
            // The sheet changed meanwhile (a filter); this tile is dropped and drawn again.
            b = null;
        } finally {
            tileCanvas.setBitmap(null);
        }
        return b;
    }

    // ------------------------------------------------------------- filters

    public interface OnFilterListener {
        /** The filter button of a column was tapped. */
        void onFilter(int col);
    }

    private OnFilterListener filterListener;

    public void setOnFilterListener(OnFilterListener l) {
        filterListener = l;
    }

    /** Row heights changed (a filter): lay the rows out again. */
    public void rowsChanged() {
        if (data == null) return;
        dropTiles();
        float px = SIZE_FACTOR * density;
        int rows = data.rowCount;
        float[] y = rowY.clone();
        float extra = y.length > rows + 1 ? y[rows + 1] - y[rows] : 0;
        for (int r = 0; r < rows; r++) y[r + 1] = y[r] + data.rowHeights[r] * 1.333f * px;
        for (int r = rows; r < y.length - 1; r++) y[r + 1] = y[r] + extra;
        rowY = y;
        selRow = selCol = -1;
        clamp();
        invalidate();
    }

    // ------------------------------------------------------------ pictures

    /** Pictures and charts, each scrolled with the pane its top-left corner is in. */
    private void drawPictures(Canvas canvas, int w, int h) {
        if (data.pictures.isEmpty()) return;
        float px = SIZE_FACTOR * density * zoom / EMU_PER_PX;
        for (SheetData.Picture p : data.pictures) {
            int fc = Math.min(p.fromCol, colX.length - 1), fr = Math.min(p.fromRow, rowY.length - 1);
            scrollFor(fr, fc);
            float l = cellLeft(fc) + p.fromColOff * px, t = cellTop(fr) + p.fromRowOff * px;
            float r, b;
            if (p.toCol >= 0) {
                r = cellLeft(Math.min(p.toCol, colX.length - 1)) + p.toColOff * px;
                b = cellTop(Math.min(p.toRow, rowY.length - 1)) + p.toRowOff * px;
            } else {
                r = l + p.width * px;
                b = t + p.height * px;
            }
            if (r <= l || b <= t || r < 0 || b < 0 || l > w || t > h) continue;
            canvas.save();
            canvas.clipRect(fc < frozenCols ? headerW * zoom : Math.min(w, frozenRight()),
                    fr < frozenRows ? headerH * zoom : Math.min(h, frozenBottom()), w, h);
            rect.set(l, t, r, b);
            Bitmap bmp = p.part == null ? null : image(p.part, (r - l) / zoom);
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, rect, bitmapPaint);
            } else {
                fill.setColor(PLACEHOLDER);
                canvas.drawRect(rect, fill);
                if (p.part == null) {
                    headerText.setTextSize(12 * density * Math.max(0.6f, Math.min(1.5f, zoom)));
                    canvas.drawText(getContext().getString(R.string.chart), rect.centerX(), rect.centerY(), headerText);
                }
            }
            canvas.restore();
        }
    }

    /** The decoded picture, or null while it loads (the view is redrawn when it arrives). */
    private Bitmap image(final String part, float widthPx) {
        final int targetW = Math.max(16, Math.min(2048, Math.round(widthPx * 2)));
        final String key = part + "@" + targetW;
        Bitmap b = images.get(key);
        final ZipFile z = zip;
        if (b != null || z == null || failed.contains(key) || !loading.add(key)) return b;
        Tasks.io().execute(() -> {
            final Bitmap bmp = ZipImages.decode(z, part, targetW);
            post(() -> {
                loading.remove(key);
                if (bmp == null) {
                    failed.add(key);
                } else if (zip == z) {
                    // Pictures are keyed by part, so another sheet showing the same one uses it too.
                    images.put(key, bmp);
                    invalidate();
                }
            });
        });
        return null;
    }

    // -------------------------------------------------------------- search

    private static final int MATCH_FILL = 0x66FFEB3B, MATCH_CURRENT_FILL = 0x99FF9800, MATCH_CURRENT = 0xFFE65100;
    /** Found cells as row, column pairs, and the one in focus (-1 for none). */
    private int[] matches = new int[0];
    private int matchCurrent = -1;

    /** Marks the cells found by a search; {@code current} is the pair shown in the stronger colour. */
    public void setMatches(int[] cells, int current) {
        matches = cells == null ? new int[0] : cells;
        matchCurrent = current;
        invalidate();
    }

    /** Scrolls so that the cell is on screen, centred only when it is not already. */
    public void showCell(int row, int col) {
        if (data == null || row >= rowY.length - 1 || col >= colX.length - 1) return;
        int[] m = mergeAt(row, col);
        int lastR = m != null ? Math.min(m[2], rowY.length - 2) : row;
        int lastC = m != null ? Math.min(m[3], colX.length - 2) : col;
        float w = getWidth(), h = getHeight() - topInset;
        if (col >= frozenCols) {
            float l = headerW * zoom + colX[col] * zoom - sx, r = headerW * zoom + colX[lastC + 1] * zoom - sx;
            if (l < frozenRight() || r > w) {
                sx = headerW * zoom + (colX[col] + colX[lastC + 1]) / 2f * zoom - (frozenRight() + w) / 2f;
            }
        }
        if (row >= frozenRows) {
            float t = headerH * zoom + rowY[row] * zoom - sy, b = headerH * zoom + rowY[lastR + 1] * zoom - sy;
            if (t < frozenBottom() || b > h) {
                sy = headerH * zoom + (rowY[row] + rowY[lastR + 1]) / 2f * zoom - (frozenBottom() + h) / 2f;
            }
        }
        scroller.forceFinished(true);
        clamp();
        invalidate();
    }

    /** The found cells on screen, over the tiles so that the text underneath shows through. */
    private void drawMatches(Canvas canvas, int w, int h) {
        if (matches.length == 0) return;
        float hx = headerW * zoom, hy = headerH * zoom;
        float fx = Math.min(w, frozenRight()), fy = Math.min(h, frozenBottom());
        for (int i = 0; i + 1 < matches.length; i += 2) {
            int r = matches[i], c = matches[i + 1];
            if (r >= rowY.length - 1 || c >= colX.length - 1) continue;
            scrollFor(r, c);
            float l = cellLeft(c), t = cellTop(r);
            int[] m = mergeAt(r, c);
            float rr = cellLeft(m != null ? Math.min(m[3], colX.length - 2) + 1 : c + 1);
            float bb = cellTop(m != null ? Math.min(m[2], rowY.length - 2) + 1 : r + 1);
            if (rr <= hx || bb <= hy || l >= w || t >= h) continue;
            canvas.save();
            canvas.clipRect(c < frozenCols ? hx : fx, r < frozenRows ? hy : fy, w, h);
            boolean current = i == matchCurrent * 2;
            fill.setColor(current ? MATCH_CURRENT_FILL : MATCH_FILL);
            canvas.drawRect(l, t, rr, bb, fill);
            if (current) {
                line.setColor(MATCH_CURRENT);
                float sw = line.getStrokeWidth();
                line.setStrokeWidth(2 * density);
                canvas.drawLine(l, t, rr, t, line);
                canvas.drawLine(l, bb, rr, bb, line);
                canvas.drawLine(l, t, l, bb, line);
                canvas.drawLine(rr, t, rr, bb, line);
                line.setStrokeWidth(sw);
            }
            canvas.restore();
        }
    }

    private void drawSelection(Canvas canvas, int w, int h) {
        if (selRow < 0) return;
        scrollFor(selRow, selCol);
        int[] m = mergeAt(selRow, selCol);
        int lastR = m != null ? Math.min(m[2], data.rowCount - 1) : selRow;
        int lastC = m != null ? Math.min(m[3], data.colCount - 1) : selCol;
        canvas.save();
        canvas.clipRect(selCol < frozenCols ? headerW * zoom : Math.min(w, frozenRight()),
                selRow < frozenRows ? headerH * zoom : Math.min(h, frozenBottom()), w, h);
        fill.setColor(SELECT_FILL);
        float l = cellLeft(selCol), t = cellTop(selRow), rr = cellLeft(lastC + 1), bb = cellTop(lastR + 1);
        canvas.drawRect(l, t, rr, bb, fill);
        line.setColor(SELECT);
        float sw = line.getStrokeWidth();
        line.setStrokeWidth(2 * density);
        canvas.drawLine(l, t, rr, t, line);
        canvas.drawLine(l, bb, rr, bb, line);
        canvas.drawLine(l, t, l, bb, line);
        canvas.drawLine(rr, t, rr, bb, line);
        line.setStrokeWidth(sw);
        canvas.restore();
    }

    // ------------------------------------------------------------- headers

    private void drawHeaders(Canvas canvas, int w, int h, float hx, float hy, float fx, float fy) {
        headerText.setTextSize(11 * density * Math.max(0.7f, Math.min(1.4f, zoom)));
        headerText.getFontMetrics(metrics);
        float mid = -(metrics.ascent + metrics.descent) / 2f;
        fill.setColor(HEADER_BG);
        canvas.drawRect(0, 0, w, hy, fill);
        canvas.drawRect(0, hy, hx, h, fill);
        line.setColor(HEADER_LINE);

        int lastCol = colX.length - 2, lastRow = rowY.length - 2;
        drawColumnLetters(canvas, sx, fx, w, hy, mid, frozenCols, lastCol);
        if (frozenCols > 0) drawColumnLetters(canvas, 0, hx, fx, hy, mid, 0, frozenCols - 1);
        drawRowNumbers(canvas, sy, fy, h, hx, mid, frozenRows, lastRow);
        if (frozenRows > 0) drawRowNumbers(canvas, 0, hy, fy, hx, mid, 0, frozenRows - 1);

        canvas.drawLine(hx, 0, hx, h, line);
        canvas.drawLine(0, hy, w, hy, line);
    }

    private void drawColumnLetters(Canvas canvas, float psx, float from, float to, float hy, float mid,
                                   int minCol, int maxCol) {
        if (to <= from) return;
        curSx = psx;
        float hx = headerW * zoom;
        int c0 = Math.max(minCol, find(colX, (from - hx + psx) / zoom));
        int c1 = Math.min(maxCol, find(colX, (to - hx + psx) / zoom));
        canvas.save();
        canvas.clipRect(from, 0, to, hy);
        for (int c = c0; c <= c1; c++) {
            float l = cellLeft(c), r = cellLeft(c + 1);
            if (c == selCol) {
                fill.setColor(HEADER_SELECTED);
                canvas.drawRect(l, 0, r, hy, fill);
            }
            canvas.drawLine(r, 0, r, hy, line);
            if (r - l > 8 * density) canvas.drawText(SheetData.columnName(c), (l + r) / 2f, hy / 2f + mid, headerText);
        }
        canvas.restore();
    }

    private void drawRowNumbers(Canvas canvas, float psy, float from, float to, float hx, float mid,
                                int minRow, int maxRow) {
        if (to <= from) return;
        curSy = psy;
        float hy = headerH * zoom;
        int r0 = Math.max(minRow, find(rowY, (from - hy + psy) / zoom));
        int r1 = Math.min(maxRow, find(rowY, (to - hy + psy) / zoom));
        canvas.save();
        canvas.clipRect(0, from, hx, to);
        for (int r = r0; r <= r1; r++) {
            float t = cellTop(r), b = cellTop(r + 1);
            if (r == selRow) {
                fill.setColor(HEADER_SELECTED);
                canvas.drawRect(0, t, hx, b, fill);
            }
            canvas.drawLine(0, b, hx, b, line);
            if (b - t > 6 * density) canvas.drawText(String.valueOf(r + 1), hx / 2f, (t + b) / 2f + mid, headerText);
        }
        canvas.restore();
    }

    // ------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true);
            flinging = false;
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
            clamp();
            postInvalidateOnAnimation();
        } else {
            flinging = false;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        clamp();
    }
}
