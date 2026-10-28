package com.tsdlanm.documentreader.xlsx;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** Excel sizes are in 96-dpi pixels / points; this scales them to dp, as large as Mi Doc Viewer shows them. */
    private static final float SIZE_FACTOR = 0.88f;
    private static final float MIN_ZOOM = 0.35f, MAX_ZOOM = 4f;
    private static final int GRID = 0xFFDCDCDC, SELECT = 0xFF1A73E8, SELECT_FILL = 0x221A73E8;
    /** Row and column headers follow the app's light or dark colours; the cells stay paper. */
    private final int HEADER_BG, HEADER_LINE, HEADER_TEXT, HEADER_SELECTED;
    private static final int FILTER_BG = 0xFFF8F9FA, FILTER_ACTIVE_BG = 0xFFE8F0FE;
    private static final int FREEZE_LINE = 0xFF9E9E9E, PLACEHOLDER = 0xFFEEEEEE;
    private static final float EMU_PER_PX = 9525f;
    private static final long MAX_COLS_KEY = 16384;
    /** How many columns text spilling left is followed across (also how far right the pane looks for it). */
    private static final int MAX_SPILL = 16;


    private final float density;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint headerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint();
    /** Paints over gridlines (see {@link #hideGridUnder}). */
    private final Paint cover = new Paint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final DashPathEffect dotted, dashed;
    private final RectF rect = new RectF();
    /** Wrapped cell text, laid out at zoom 1; keyed by row and column. */
    private final LruCache<Long, StaticLayout> wrapped = new LruCache<>(300);
    /**
     * Width scale per cell style and text: measuring is too slow to repeat every frame. Looked up
     * with the objects at hand, so drawing a frame allocates nothing; cleared when it grows large.
     */
    private final java.util.HashMap<CellStyle, java.util.HashMap<String, Float>> widthScales = new java.util.HashMap<>();
    private int widthScaleCount;
    private final LruCache<String, Bitmap> images;
    private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
    private ZipFile zip;
    private int frozenRows, frozenCols;
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    private SheetData data;
    /** Prefix sums of column widths / row heights at zoom 1, in px; length count + 1. */
    private float[] colX = new float[1], rowY = new float[1];
    private float headerW, headerH;
    private float baseTextPx;
    private float zoom = 1f, sx, sy;
    private boolean scaling, flinging;
    private int selRow = -1, selCol = -1;
    private OnCellSelectedListener listener;
    private final ArrayList<int[]> visibleMerges = new ArrayList<>();
    /**
     * For the pane being drawn: which visible merge (index + 1, 0 for none) covers each cell of
     * rows gridR0.. and columns gridC0.., so per-cell lookups need no scan. Reused between frames.
     */
    private int[] mergeGrid = new int[0];
    private int gridR0, gridC0, gridRows, gridCols;

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
        border.setStyle(Paint.Style.STROKE);
        cover.setStrokeWidth(2f);
        dotted = new DashPathEffect(new float[]{density, density * 1.5f}, 0);
        dashed = new DashPathEffect(new float[]{density * 3, density * 2}, 0);
        int tilesKb = MemoryBudget.kb(context, 12 * 1024, 8, 2.5f, 2);
        tiles = new LruCache<Long, Bitmap>(tilesKb) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
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
        widthScales.clear();
        widthScaleCount = 0;
        wrapped.evictAll();
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
        super.onDetachedFromWindow();
    }

    /** The workbook's zip, for pictures on the sheets. */
    public void setZip(ZipFile zip) {
        this.zip = zip;
    }

    public void setData(SheetData d) {
        data = d;
        wrapped.evictAll();
        dropTiles();
        widthScales.clear();
        widthScaleCount = 0;
        images.evictAll();
        failed.clear();
        frozenRows = d == null ? 0 : d.frozenRows;
        frozenCols = d == null ? 0 : d.frozenCols;
        selRow = selCol = -1;
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
        if (d != null) {
            // Open where the sheet was left in Excel.
            int tc = Math.max(frozenCols, Math.min(d.topCol, colX.length - 2));
            int tr = Math.max(frozenRows, Math.min(d.topRow, rowY.length - 2));
            sx = (colX[tc] - colX[frozenCols]) * zoom;
            sy = (rowY[tr] - rowY[frozenRows]) * zoom;
            if (getWidth() > 0) clamp();
        }
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

    /** Index i such that edges[i] <= v < edges[i+1], clamped to valid cells. */
    private static int find(float[] edges, float v) {
        int lo = 0, hi = edges.length - 2, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (edges[mid] <= v) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
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
                && cx >= colX[c + 1] - filterButtonSize(r) - 6 * density) {
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
            wrapped.evictAll();
        dropTiles();
            widthScales.clear();
            widthScaleCount = 0;
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
        return mergeAt(data.merges, r, c);
    }

    private static int[] mergeAt(List<int[]> merges, int r, int c) {
        for (int[] m : merges) {
            if (r >= m[0] && r <= m[2] && c >= m[1] && c <= m[3]) return m;
        }
        return null;
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

        // The scrolling part first, then the frozen panes over it.
        if (scaling) {
            // Pinching: the zoom changes every frame, so the cells are drawn directly.
            drawPane(canvas, sx, sy, fx, fy, w, h, frozenRows, lastRow, frozenCols, lastCol);
            if (frozenCols > 0) drawPane(canvas, 0, sy, hx, fy, fx, h, frozenRows, lastRow, 0, frozenCols - 1);
            if (frozenRows > 0) drawPane(canvas, sx, 0, fx, hy, w, fy, 0, frozenRows - 1, frozenCols, lastCol);
            if (frozenRows > 0 && frozenCols > 0) drawPane(canvas, 0, 0, hx, hy, fx, fy, 0, frozenRows - 1, 0, frozenCols - 1);
        } else {
            if (zoom != tileZoom) {
                tiles.evictAll();
                tileZoom = zoom;
            }
            blitPane(canvas, sx, sy, fx, fy, w, h);
            if (frozenCols > 0) blitPane(canvas, 0, sy, hx, fy, fx, h);
            if (frozenRows > 0) blitPane(canvas, sx, 0, fx, hy, w, fy);
            if (frozenRows > 0 && frozenCols > 0) blitPane(canvas, 0, 0, hx, hy, fx, fy);
        }

        drawPictures(canvas, w, h);
        drawSelection(canvas, w, h);

        line.setColor(FREEZE_LINE);
        if (frozenCols > 0) canvas.drawLine(fx, hy, fx, h, line);
        if (frozenRows > 0) canvas.drawLine(hx, fy, w, fy, line);
        drawHeaders(canvas, w, h, hx, hy, fx, fy);
    }

    // ---------------------------------------------------------------- tiles

    /**
     * The cells are drawn once into tiles at the current zoom; scrolling then only moves bitmaps,
     * as in the Word page view, instead of drawing every visible cell each frame. Selection,
     * pictures and headers are drawn over them directly. Tiles cover the sheet in zoomed pixels
     * from cell A1, so the frozen panes reuse the same tiles.
     */
    private static final int TILE = 512;
    private final LruCache<Long, Bitmap> tiles;
    private float tileZoom = -1;
    private final Canvas tileCanvas = new Canvas();

    /** Cell contents or sizes changed: the tiles are drawn again. */
    private void dropTiles() {
        tiles.evictAll();
    }

    /** Draws a pane from tiles: content scrolled by psx/psy into the screen box. */
    private void blitPane(Canvas canvas, float psx, float psy, float left, float top, float right, float bottom) {
        if (right <= left || bottom <= top) return;
        float hx = headerW * zoom, hy = headerH * zoom;
        float cx0 = left - hx + psx, cx1 = right - hx + psx;
        float cy0 = top - hy + psy, cy1 = bottom - hy + psy;
        int tx0 = Math.max(0, (int) Math.floor(cx0 / TILE)), tx1 = (int) Math.floor((cx1 - 1) / TILE);
        int ty0 = Math.max(0, (int) Math.floor(cy0 / TILE)), ty1 = (int) Math.floor((cy1 - 1) / TILE);
        float ox = Math.round(hx - psx), oy = Math.round(hy - psy);
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                Bitmap b = tile(tx, ty);
                if (b != null) canvas.drawBitmap(b, ox + tx * TILE, oy + ty * TILE, null);
            }
        }
        canvas.restore();
    }

    /** The tile at (tx, ty), drawn now if not cached; null past the sheet's edge or out of memory. */
    private Bitmap tile(int tx, int ty) {
        if (tx * (float) TILE >= colX[colX.length - 1] * zoom || ty * (float) TILE >= rowY[rowY.length - 1] * zoom) {
            return null;
        }
        long key = ((long) tx << 32) | (ty & 0xFFFFFFFFL);
        Bitmap b = tiles.get(key);
        if (b != null) return b;
        try {
            b = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565);
        } catch (OutOfMemoryError e) {
            tiles.evictAll();
            return null;
        }
        b.eraseColor(Color.WHITE);
        tileCanvas.setBitmap(b);
        float hx = headerW * zoom, hy = headerH * zoom;
        drawPane(tileCanvas, hx + tx * TILE, hy + ty * TILE, 0, 0, TILE, TILE,
                0, rowY.length - 2, 0, colX.length - 2);
        tileCanvas.setBitmap(null);
        tiles.put(key, b);
        return b;
    }

    /**
     * Draws the cells of one pane, scrolled by {@code psx/psy}, clipped to the given screen box and
     * limited to the given rows and columns.
     */
    private void drawPane(Canvas canvas, float psx, float psy, float left, float top, float right, float bottom,
                          int minRow, int maxRow, int minCol, int maxCol) {
        if (right <= left || bottom <= top || maxRow < minRow || maxCol < minCol) return;
        curSx = psx;
        curSy = psy;
        float hx = headerW * zoom, hy = headerH * zoom;
        int c0 = Math.max(minCol, find(colX, (left - hx + psx) / zoom));
        int c1 = Math.min(maxCol, find(colX, (right - hx + psx) / zoom));
        int r0 = Math.max(minRow, find(rowY, (top - hy + psy) / zoom));
        int r1 = Math.min(maxRow, find(rowY, (bottom - hy + psy) / zoom));
        if (c1 < c0 || r1 < r0) return;
        float textPx = baseTextPx * zoom;
        float pad = 3 * density * zoom;

        // Only merges touching the pane are checked per cell.
        visibleMerges.clear();
        for (int[] m : data.merges) {
            if (m[2] < r0 || m[0] > r1 || m[3] < c0 - MAX_SPILL || m[1] > c1) continue;
            visibleMerges.add(m);
        }
        buildMergeGrid(r0, r1, Math.max(0, c0 - MAX_SPILL), c1);

        canvas.save();
        canvas.clipRect(left, top, right, bottom);

        // Cell fills.
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int f = data.style(r, c).fillColor;
                if (f == 0) continue;
                fill.setColor(f);
                canvas.drawRect(cellLeft(c), cellTop(r), cellLeft(c + 1), cellTop(r + 1), fill);
            }
        }

        // Grid lines, unless the sheet turned them off.
        if (data.showGrid) {
            line.setColor(GRID);
            float gr = Math.min(right, cellLeft(colX.length - 1));
            float gb = Math.min(bottom, cellTop(rowY.length - 1));
            for (int c = c0; c <= c1 + 1 && c < colX.length; c++) {
                float x = cellLeft(c);
                canvas.drawLine(x, top, x, gb, line);
            }
            for (int r = r0; r <= r1 + 1 && r < rowY.length; r++) {
                float y = cellTop(r);
                canvas.drawLine(left, y, gr, y, line);
            }
        }

        // Merged areas cover the grid lines inside them.
        for (int[] m : visibleMerges) {
            int lastR = Math.min(m[2], data.rowCount - 1), lastC = Math.min(m[3], data.colCount - 1);
            float l = cellLeft(m[1]), t = cellTop(m[0]), rr = cellLeft(lastC + 1), bb = cellTop(lastR + 1);
            int f = data.style(m[0], m[1]).fillColor;
            fill.setColor(f != 0 ? f : Color.WHITE);
            canvas.drawRect(l + 1, t + 1, rr - 1, bb - 1, fill);
        }

        drawBorders(canvas, r0, r1, c0, c1);

        // Text.
        for (int r = r0; r <= r1; r++) {
            if (rowY[r + 1] - rowY[r] <= 0) continue;
            for (int c = c0; c <= c1; c++) {
                drawCellText(canvas, r, c, c1, textPx, pad, false);
            }
            // Text from a cell left of the pane can overflow into view.
            for (int c = c0 - 1; c >= minCol && c >= c0 - 8; c--) {
                if (data.hasCell(r, c)) {
                    drawCellText(canvas, r, c, c1, textPx, pad, false);
                    break;
                }
            }
            // And right- or centre-aligned text from a cell right of it.
            for (int c = c1 + 1; c <= maxCol && c <= c1 + MAX_SPILL; c++) {
                if (!data.hasCell(r, c)) continue;
                int al = data.style(r, c).align;
                if (!data.isNumber(r, c) && (al == CellStyle.ALIGN_RIGHT || al == CellStyle.ALIGN_CENTER)) {
                    drawCellText(canvas, r, c, c1, textPx, pad, false);
                }
                break;
            }
        }
        // A merge across the freeze line is drawn in each pane it reaches.
        for (int[] m : visibleMerges) {
            if (m[2] >= minRow && m[3] >= minCol) drawCellText(canvas, m[0], m[1], c1, textPx, pad, true);
        }
        drawFilterButtons(canvas, r0, r1, c0, c1);
        canvas.restore();
    }

    // ------------------------------------------------------------- filters

    public interface OnFilterListener {
        /** The filter button of a column was tapped. */
        void onFilter(int col);
    }

    private OnFilterListener filterListener;
    private final android.graphics.Path arrow = new android.graphics.Path();

    public void setOnFilterListener(OnFilterListener l) {
        filterListener = l;
    }

    /** Size of a filter button at zoom 1, fitted to its header row. */
    private float filterButtonSize(int r) {
        return Math.max(4f, Math.min(16 * density, rowY[r + 1] - rowY[r] - 2));
    }

    /** The AutoFilter's drop-down buttons in its header row, bottom right of each cell. */
    private void drawFilterButtons(Canvas canvas, int r0, int r1, int c0, int c1) {
        int[] f = data.filterRange;
        if (f == null || f[0] < r0 || f[0] > r1) return;
        int r = f[0];
        float s = filterButtonSize(r) * zoom;
        float bottom = cellTop(r + 1) - zoom;
        for (int c = Math.max(c0, f[1]); c <= Math.min(c1, f[3]); c++) {
            float right = cellLeft(c + 1) - zoom;
            rect.set(right - s, bottom - s, right, bottom);
            boolean active = data.filters.containsKey(c);
            fill.setColor(active ? FILTER_ACTIVE_BG : FILTER_BG);
            canvas.drawRect(rect, fill);
            line.setColor(active ? SELECT : HEADER_LINE);
            canvas.drawLine(rect.left, rect.top, rect.right, rect.top, line);
            canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, line);
            canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, line);
            canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom, line);
            float cx = rect.centerX(), cy = rect.centerY(), w = s * 0.25f;
            arrow.rewind();
            arrow.moveTo(cx - w, cy - w * 0.5f);
            arrow.lineTo(cx + w, cy - w * 0.5f);
            arrow.lineTo(cx, cy + w * 0.6f);
            arrow.close();
            fill.setColor(active ? SELECT : HEADER_TEXT);
            canvas.drawPath(arrow, fill);
        }
    }

    /** Row heights changed (a filter): lay the rows out again. */
    public void rowsChanged() {
        if (data == null) return;
        dropTiles();
        float px = SIZE_FACTOR * density;
        int rows = data.rowCount;
        float extra = rowY.length > rows + 1 ? rowY[rows + 1] - rowY[rows] : 0;
        for (int r = 0; r < rows; r++) rowY[r + 1] = rowY[r] + data.rowHeights[r] * 1.333f * px;
        for (int r = rows; r < rowY.length - 1; r++) rowY[r + 1] = rowY[r] + extra;
        selRow = selCol = -1;
        clamp();
        invalidate();
    }

    private void buildMergeGrid(int r0, int r1, int c0, int c1) {
        gridR0 = r0;
        gridC0 = c0;
        gridRows = r1 - r0 + 1;
        gridCols = c1 - c0 + 1;
        int n = gridRows * gridCols;
        if (mergeGrid.length < n) mergeGrid = new int[n];
        java.util.Arrays.fill(mergeGrid, 0, n, 0);
        for (int i = 0; i < visibleMerges.size(); i++) {
            int[] m = visibleMerges.get(i);
            int ra = Math.max(m[0], r0), rb = Math.min(m[2], r1);
            int ca = Math.max(m[1], c0), cb = Math.min(m[3], c1);
            for (int r = ra; r <= rb; r++) {
                int base = (r - r0) * gridCols - c0;
                for (int c = ca; c <= cb; c++) mergeGrid[base + c] = i + 1;
            }
        }
    }

    /** The visible merge covering a cell, from the grid when the cell is in it. */
    private int[] visibleMergeAt(int r, int c) {
        if (visibleMerges.isEmpty()) return null;
        int rr = r - gridR0, cc = c - gridC0;
        if (rr >= 0 && rr < gridRows && cc >= 0 && cc < gridCols) {
            int i = mergeGrid[rr * gridCols + cc];
            return i == 0 ? null : visibleMerges.get(i - 1);
        }
        return mergeAt(visibleMerges, r, c);
    }

    // ------------------------------------------------------------- borders

    /**
     * Cell borders. A shared edge shows the heavier of the two cells' borders on that side; each
     * cell draws its right and bottom edge, the pane's first row and column also their top / left.
     */
    private void drawBorders(Canvas canvas, int r0, int r1, int c0, int c1) {
        for (int r = r0; r <= r1; r++) {
            if (rowY[r + 1] - rowY[r] <= 0) continue;
            float t = cellTop(r), b = cellTop(r + 1);
            for (int c = c0; c <= c1; c++) {
                CellStyle st = data.style(r, c);
                float l = cellLeft(c), rr = cellLeft(c + 1);
                if (st.diagonals != 0) {
                    if ((st.diagonals & CellStyle.DIAG_DOWN) != 0) {
                        edge(canvas, l, t, rr, b, st, CellStyle.DIAGONAL, CellStyle.DEFAULT, CellStyle.DIAGONAL);
                    }
                    if ((st.diagonals & CellStyle.DIAG_UP) != 0) {
                        edge(canvas, l, b, rr, t, st, CellStyle.DIAGONAL, CellStyle.DEFAULT, CellStyle.DIAGONAL);
                    }
                }
                if (c == c0) {
                    CellStyle left = c > 0 ? data.style(r, c - 1) : CellStyle.DEFAULT;
                    edge(canvas, l, t, l, b, st, CellStyle.LEFT, left, CellStyle.RIGHT);
                }
                if (r == r0) {
                    CellStyle above = r > 0 ? data.style(r - 1, c) : CellStyle.DEFAULT;
                    edge(canvas, l, t, rr, t, st, CellStyle.TOP, above, CellStyle.BOTTOM);
                }
                // Edges inside a merged area are not drawn, as in Excel.
                int[] m = visibleMergeAt(r, c);
                if (m == null || c + 1 > m[3]) {
                    edge(canvas, rr, t, rr, b, st, CellStyle.RIGHT, data.style(r, c + 1), CellStyle.LEFT);
                }
                if (m == null || r + 1 > m[2]) {
                    edge(canvas, l, b, rr, b, st, CellStyle.BOTTOM, data.style(r + 1, c), CellStyle.TOP);
                }
            }
        }
    }

    private void edge(Canvas canvas, float x1, float y1, float x2, float y2,
                      CellStyle a, int sideA, CellStyle b, int sideB) {
        byte sa = a.border(sideA), sb = b.border(sideB);
        if (sa == CellStyle.B_NONE && sb == CellStyle.B_NONE) return;
        byte style;
        int color;
        if (sa >= sb) {
            style = sa;
            color = a.borderColor[sideA];
        } else {
            style = sb;
            color = b.borderColor[sideB];
        }
        border.setColor(color != 0 ? color : Color.BLACK);
        // A thin border is one screen pixel at normal size (as in Excel and Mi Doc) and grows when
        // zoomed in; medium and thick are two and three times that.
        float unit = Math.max(1f, density * zoom * 0.4f);
        float wdt;
        switch (style) {
            case CellStyle.B_HAIR:
                wdt = 1f;
                break;
            case CellStyle.B_MEDIUM:
            case CellStyle.B_MEDIUM_DASHED:
                wdt = 2 * unit;
                break;
            case CellStyle.B_THICK:
                wdt = 3 * unit;
                break;
            default:
                wdt = unit;
        }
        border.setStrokeWidth(wdt);
        border.setPathEffect(style == CellStyle.B_DOTTED ? dotted
                : style == CellStyle.B_DASHED || style == CellStyle.B_MEDIUM_DASHED ? dashed : null);
        if (style == CellStyle.B_DOUBLE) {
            boolean vertical = x1 == x2;
            float off = unit;
            if (vertical) {
                canvas.drawLine(x1 - off, y1, x2 - off, y2, border);
                canvas.drawLine(x1 + off, y1, x2 + off, y2, border);
            } else {
                canvas.drawLine(x1, y1 - off, x2, y2 - off, border);
                canvas.drawLine(x1, y1 + off, x2, y2 + off, border);
            }
        } else {
            canvas.drawLine(x1, y1, x2, y2, border);
        }
    }

    // ---------------------------------------------------------------- text

    /**
     * Sets the cell's font on {@code p}: the phone's serif, sans or monospace face for the cell's
     * font, stretched so {@code s} is as wide as in Excel (Calibri and Times are narrower than the
     * phone fonts). Then text wraps, overflows and shows "###" where Excel does.
     */
    private void applyFont(Paint p, CellStyle st, String s) {
        p.setTypeface(FontMatch.typeface(st.font, st.bold, st.italic));
        p.setFakeBoldText(FontMatch.fakeBold(st.font, st.bold, st.italic));
        p.setTextScaleX(st.font == null ? 1f : widthScale(st, s));
    }

    private float widthScale(CellStyle st, String s) {
        java.util.HashMap<String, Float> byText = widthScales.get(st);
        Float scale = byText == null ? null : byText.get(s);
        if (scale != null) return scale;
        if (widthScaleCount > 5000) {
            widthScales.clear();
            widthScaleCount = 0;
            byText = null;
        }
        if (byText == null) {
            byText = new java.util.HashMap<>();
            widthScales.put(st, byText);
        }
        scale = FontMatch.scale(st.font, st.bold, st.italic, s);
        byText.put(s, scale);
        widthScaleCount++;
        return scale;
    }

    private void drawCellText(Canvas canvas, int r, int c, int lastVisibleCol, float textPx, float pad,
                              boolean merged) {
        if (!merged && visibleMergeAt(r, c) != null) return;
        String s = data.text(r, c);
        if (s == null || s.isEmpty()) return;
        CellStyle st = data.style(r, c);
        boolean number = data.isNumber(r, c);
        int lastR = r, lastC = c;
        if (merged) {
            int[] m = visibleMergeAt(r, c);
            if (m != null) {
                lastR = Math.min(m[2], data.rowCount - 1);
                lastC = Math.min(m[3], data.colCount - 1);
            }
        }
        float left = cellLeft(c), top = cellTop(r);
        float right = cellLeft(lastC + 1), bottom = cellTop(lastR + 1);
        if (bottom - top < 2) return;
        int align = st.align;
        if (align == CellStyle.ALIGN_GENERAL) align = number ? CellStyle.ALIGN_RIGHT : CellStyle.ALIGN_LEFT;
        // Each indent level is about three characters wide.
        float indent = st.indent * 9f * SIZE_FACTOR * density * zoom;

        if (st.rotation != 0) {
            drawRotated(canvas, s, st, textPx, left, top, right, bottom, pad);
            return;
        }
        if (st.wrap && !number) {
            drawWrapped(canvas, r, c, s, st, align, left, top, right, bottom, pad, indent);
            return;
        }

        text.setTextSize(textPx * (st.fontSize > 0 ? st.fontSize : 1f));
        text.setColor(st.textColor != 0 ? st.textColor : Color.BLACK);
        text.setUnderlineText(st.underline);

        // Without wrapping, line breaks inside a cell become spaces.
        if (s.indexOf('\n') >= 0) s = s.replace('\n', ' ');
        applyFont(text, st, s);
        float tw = text.measureText(s);

        // Text too wide for its cell spills into empty neighbours, like Excel: left-aligned to the
        // right, right-aligned to the left, centred to both sides.
        float clipLeft = left, clipRight = right;
        int firstCol = c, lastCol = lastC;
        if (!merged && !number && tw + indent > right - left - 2 * pad) {
            float need = tw + indent + 2 * pad;
            if (align == CellStyle.ALIGN_LEFT || align == CellStyle.ALIGN_CENTER) {
                float reach = align == CellStyle.ALIGN_LEFT ? left + need : (left + right + need) / 2f;
                int k = lastC + 1;
                while (k < colX.length - 1 && k <= lastVisibleCol + 1 && !data.hasCell(r, k) && clipRight < reach) {
                    lastCol = k;
                    clipRight = cellLeft(++k);
                }
            }
            if (align == CellStyle.ALIGN_RIGHT || align == CellStyle.ALIGN_CENTER) {
                float reach = align == CellStyle.ALIGN_RIGHT ? right - need : (left + right - need) / 2f;
                int k = c - 1;
                while (k >= 0 && k >= c - MAX_SPILL && !data.hasCell(r, k) && clipLeft > reach) {
                    firstCol = k;
                    clipLeft = cellLeft(k--);
                }
            }
            if (firstCol < c || lastCol > lastC) hideGridUnder(canvas, r, firstCol, lastCol, top, bottom, c, lastC);
        }
        if (number && tw > right - left - 2 * pad) {
            // Excel shows #### when a number does not fit.
            s = "###";
            tw = text.measureText(s);
        }
        float x;
        if (align == CellStyle.ALIGN_RIGHT) x = right - pad - tw - indent;
        else if (align == CellStyle.ALIGN_CENTER) x = (left + right - tw) / 2f;
        else x = left + pad + indent;

        text.getFontMetrics(metrics);
        float textH = metrics.descent - metrics.ascent;
        float y;
        if (textH > bottom - top || st.vAlign == CellStyle.V_BOTTOM) {
            y = bottom - pad * 0.4f - metrics.descent;
        } else if (st.vAlign == CellStyle.V_TOP) {
            y = top + pad * 0.4f - metrics.ascent;
        } else {
            y = (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f;
        }

        boolean needClip = x < clipLeft + 1 || x + tw > clipRight - 1 || textH > bottom - top;
        if (needClip) {
            canvas.save();
            canvas.clipRect(clipLeft + 1, top, clipRight - 1, bottom);
        }
        canvas.drawText(s, x, y, text);
        if (needClip) canvas.restore();
    }

    /**
     * Excel hides the gridlines under text that spills into neighbouring cells, so the text reads
     * as one block. Cell borders stay.
     */
    private void hideGridUnder(Canvas canvas, int r, int firstCol, int lastCol, float top, float bottom,
                               int ownFirst, int ownLast) {
        if (!data.showGrid) return;
        for (int k = firstCol + 1; k <= lastCol; k++) {
            if (k > ownFirst && k <= ownLast) continue;
            CellStyle a = data.style(r, k - 1), b = data.style(r, k);
            if (a.border(CellStyle.RIGHT) != CellStyle.B_NONE || b.border(CellStyle.LEFT) != CellStyle.B_NONE) continue;
            int f = a.fillColor != 0 ? a.fillColor : b.fillColor;
            cover.setColor(f != 0 ? f : Color.WHITE);
            float x = cellLeft(k);
            canvas.drawLine(x, top + 1, x, bottom - 1, cover);
        }
    }

    /** Rotated text, or letters stacked top to bottom (rotation 255), centred in the cell. */
    private void drawRotated(Canvas canvas, String s, CellStyle st, float textPx,
                             float left, float top, float right, float bottom, float pad) {
        text.setTextSize(textPx * (st.fontSize > 0 ? st.fontSize : 1f));
        text.setColor(st.textColor != 0 ? st.textColor : Color.BLACK);
        text.setUnderlineText(false);
        s = s.replace('\n', ' ');
        applyFont(text, st, s);
        text.getFontMetrics(metrics);
        canvas.save();
        canvas.clipRect(left + 1, top, right - 1, bottom);
        if (st.rotation == 255) {
            float lineH = metrics.descent - metrics.ascent;
            float cx = (left + right) / 2f;
            float total = lineH * s.length();
            float y = st.vAlign == CellStyle.V_TOP || total > bottom - top ? top + pad * 0.4f
                    : st.vAlign == CellStyle.V_CENTER ? (top + bottom - total) / 2f : bottom - pad * 0.4f - total;
            for (int i = 0; i < s.length(); i++) {
                String ch = s.substring(i, i + 1);
                canvas.drawText(ch, cx - text.measureText(ch) / 2f, y - metrics.ascent, text);
                y += lineH;
            }
        } else {
            // 1..90: counter-clockwise; 91..180: clockwise by (rotation - 90).
            float deg = st.rotation <= 90 ? -st.rotation : st.rotation - 90;
            float tw = text.measureText(s);
            canvas.translate((left + right) / 2f, (top + bottom) / 2f);
            canvas.rotate(deg);
            canvas.drawText(s, -tw / 2f, -(metrics.ascent + metrics.descent) / 2f, text);
        }
        canvas.restore();
    }

    /**
     * Wrapped text: laid out once at zoom 1 for the column width and drawn scaled, so lines break in
     * the same places at every zoom, as in Excel.
     */
    private void drawWrapped(Canvas canvas, int r, int c, String s, CellStyle st, int align,
                             float left, float top, float right, float bottom, float pad, float indent) {
        float width = (right - left - 2 * pad - indent) / zoom;
        if (width < 4) return;
        long key = (long) r * MAX_COLS_KEY + c;
        StaticLayout layout = wrapped.get(key);
        if (layout == null || layout.getWidth() != (int) width) {
            TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            tp.setTextSize(baseTextPx * (st.fontSize > 0 ? st.fontSize : 1f));
            tp.setColor(st.textColor != 0 ? st.textColor : Color.BLACK);
            tp.setUnderlineText(st.underline);
            applyFont(tp, st, s);
            Layout.Alignment a = align == CellStyle.ALIGN_RIGHT ? Layout.Alignment.ALIGN_OPPOSITE
                    : align == CellStyle.ALIGN_CENTER ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL;
            layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, (int) width)
                    .setAlignment(a)
                    .setIncludePad(false)
                    .build();
            wrapped.put(key, layout);
        }
        float h = layout.getHeight() * zoom;
        float y;
        if (h > bottom - top || st.vAlign == CellStyle.V_TOP) y = top + pad * 0.4f;
        else if (st.vAlign == CellStyle.V_CENTER) y = (top + bottom - h) / 2f;
        else y = bottom - pad * 0.4f - h;
        float x = left + pad + (align == CellStyle.ALIGN_RIGHT ? 0 : indent);
        canvas.save();
        canvas.clipRect(left + 1, top, right - 1, bottom);
        canvas.translate(x, y);
        canvas.scale(zoom, zoom);
        layout.draw(canvas);
        canvas.restore();
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
