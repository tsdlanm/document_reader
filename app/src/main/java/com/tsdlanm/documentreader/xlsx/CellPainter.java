package com.tsdlanm.documentreader.xlsx;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.LruCache;

import com.tsdlanm.documentreader.docx.FontMatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the cells of a worksheet: fills, gridlines, borders, text and filter buttons. Holds the
 * paints and caches of one thread, so the sheet's tiles can be drawn on a background thread while
 * the main thread draws with its own painter. {@link #set} gives it the sheet and zoom to draw.
 */
final class CellPainter {
    /** Excel sizes are in 96-dpi pixels / points; this scales them to dp, as large as Mi Doc Viewer shows them. */
    static final float SIZE_FACTOR = 0.88f;
    /** How many columns text spilling left is followed across (also how far right the pane looks for it). */
    static final int MAX_SPILL = 16;
    private static final int GRID = 0xFFDCDCDC, SELECT = 0xFF1A73E8;
    private static final int FILTER_BG = 0xFFF8F9FA, FILTER_ACTIVE_BG = 0xFFE8F0FE;
    private static final long MAX_COLS_KEY = 16384;

    private final float density;
    private final float baseTextPx;
    private final int headerLine, headerText;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint border = new Paint();
    /** Paints over gridlines (see {@link #hideGridUnder}). */
    private final Paint cover = new Paint();
    private final DashPathEffect dotted, dashed;
    private final RectF rect = new RectF();
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final android.graphics.Path arrow = new android.graphics.Path();
    /** Wrapped cell text, laid out at zoom 1; keyed by row and column. */
    private final LruCache<Long, StaticLayout> wrapped = new LruCache<>(300);
    /**
     * Width scale per cell style and text: measuring is too slow to repeat every frame. Looked up
     * with the objects at hand, so drawing a frame allocates nothing; cleared when it grows large.
     */
    private final java.util.HashMap<CellStyle, java.util.HashMap<String, Float>> widthScales = new java.util.HashMap<>();
    private int widthScaleCount;
    private final ArrayList<int[]> visibleMerges = new ArrayList<>();
    /**
     * For the pane being drawn: which visible merge (index + 1, 0 for none) covers each cell of
     * rows gridR0.. and columns gridC0.., so per-cell lookups need no scan. Reused between panes.
     */
    private int[] mergeGrid = new int[0];
    private int gridR0, gridC0, gridRows, gridCols;

    private SheetData data;
    /** Prefix sums of column widths / row heights at zoom 1, in px; length count + 1. */
    private float[] colX, rowY;
    private float headerW, headerH, zoom;
    /** Horizontal and vertical scroll of the pane being drawn. */
    private float curSx, curSy;

    CellPainter(float density, float baseTextPx, int headerLine, int headerText) {
        this.density = density;
        this.baseTextPx = baseTextPx;
        this.headerLine = headerLine;
        this.headerText = headerText;
        // One screen pixel, like Excel's gridlines: thicker ones crowd out the text.
        line.setStrokeWidth(1f);
        border.setStyle(Paint.Style.STROKE);
        cover.setStrokeWidth(2f);
        dotted = new DashPathEffect(new float[]{density, density * 1.5f}, 0);
        dashed = new DashPathEffect(new float[]{density * 3, density * 2}, 0);
    }

    /** What to draw next. The arrays are not changed afterwards; new ones replace them. */
    void set(SheetData data, float[] colX, float[] rowY, float headerW, float headerH, float zoom) {
        if (data != this.data) reset();
        this.data = data;
        this.colX = colX;
        this.rowY = rowY;
        this.headerW = headerW;
        this.headerH = headerH;
        this.zoom = zoom;
    }

    /** Forgets measured text: another sheet, a new font, or memory running short. */
    void reset() {
        wrapped.evictAll();
        widthScales.clear();
        widthScaleCount = 0;
    }

    /** Size of a filter button at zoom 1, fitted to its header row. */
    static float filterButtonSize(float[] rowY, int r, float density) {
        return Math.max(4f, Math.min(16 * density, rowY[r + 1] - rowY[r] - 2));
    }

    /** Index i such that edges[i] <= v < edges[i+1], clamped to valid cells. */
    static int find(float[] edges, float v) {
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

    static int[] mergeAt(List<int[]> merges, int r, int c) {
        for (int[] m : merges) {
            if (r >= m[0] && r <= m[2] && c >= m[1] && c <= m[3]) return m;
        }
        return null;
    }

    private float cellLeft(int c) {
        return headerW * zoom + colX[c] * zoom - curSx;
    }

    private float cellTop(int r) {
        return headerH * zoom + rowY[r] * zoom - curSy;
    }

    /**
     * Draws the cells of one pane, scrolled by {@code psx/psy}, clipped to the given screen box and
     * limited to the given rows and columns.
     */
    void drawPane(Canvas canvas, float psx, float psy, float left, float top, float right, float bottom,
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
        if (data.showGrid) drawGrid(canvas, c0, c1, r0, r1, left, top, right, bottom);

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

    /**
     * Only the gridlines of a pane, where its tiles are not drawn yet: cheap enough for every
     * frame, and reads as empty cells until the contents arrive.
     */
    void drawGridOnly(Canvas canvas, float psx, float psy, float left, float top, float right, float bottom,
                      int minRow, int maxRow, int minCol, int maxCol) {
        if (!data.showGrid || right <= left || bottom <= top) return;
        curSx = psx;
        curSy = psy;
        float hx = headerW * zoom, hy = headerH * zoom;
        int c0 = Math.max(minCol, find(colX, (left - hx + psx) / zoom));
        int c1 = Math.min(maxCol, find(colX, (right - hx + psx) / zoom));
        int r0 = Math.max(minRow, find(rowY, (top - hy + psy) / zoom));
        int r1 = Math.min(maxRow, find(rowY, (bottom - hy + psy) / zoom));
        if (c1 < c0 || r1 < r0) return;
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        drawGrid(canvas, c0, c1, r0, r1, left, top, right, bottom);
        canvas.restore();
    }

    private void drawGrid(Canvas canvas, int c0, int c1, int r0, int r1, float left, float top, float right, float bottom) {
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

    // ------------------------------------------------------------- filters

    /** The AutoFilter's drop-down buttons in its header row, bottom right of each cell. */
    private void drawFilterButtons(Canvas canvas, int r0, int r1, int c0, int c1) {
        int[] f = data.filterRange;
        if (f == null || f[0] < r0 || f[0] > r1) return;
        int r = f[0];
        float s = filterButtonSize(rowY, r, density) * zoom;
        float bottom = cellTop(r + 1) - zoom;
        for (int c = Math.max(c0, f[1]); c <= Math.min(c1, f[3]); c++) {
            float right = cellLeft(c + 1) - zoom;
            rect.set(right - s, bottom - s, right, bottom);
            boolean active = data.filters.containsKey(c);
            fill.setColor(active ? FILTER_ACTIVE_BG : FILTER_BG);
            canvas.drawRect(rect, fill);
            line.setColor(active ? SELECT : headerLine);
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
            fill.setColor(active ? SELECT : headerText);
            canvas.drawPath(arrow, fill);
        }
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
                // Hidden columns draw nothing, not even their borders.
                if (colX[c + 1] - colX[c] <= 0) continue;
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
        if (!merged && c < colX.length - 1 && colX[c + 1] - colX[c] <= 0) return;
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
                // Text in the frozen columns stops at the freeze line, as in Excel: otherwise it
                // would show again in the scrolled part.
                int stop = c < data.frozenCols ? data.frozenCols : Integer.MAX_VALUE;
                while (k < colX.length - 1 && k <= lastVisibleCol + 1 && k < stop && !data.hasCell(r, k)
                        && clipRight < reach) {
                    lastCol = k;
                    clipRight = cellLeft(++k);
                }
            }
            if (align == CellStyle.ALIGN_RIGHT || align == CellStyle.ALIGN_CENTER) {
                float reach = align == CellStyle.ALIGN_RIGHT ? right - need : (left + right - need) / 2f;
                int k = c - 1;
                int stop = c >= data.frozenCols ? data.frozenCols : 0;
                while (k >= stop && k >= c - MAX_SPILL && !data.hasCell(r, k) && clipLeft > reach) {
                    firstCol = k;
                    clipLeft = cellLeft(k--);
                }
            }
            if (firstCol < c || lastCol > lastC) hideGridUnder(canvas, r, firstCol, lastCol, top, bottom, c, lastC);
        }
        if (number && tw > right - left - 2 * pad) {
            String fitted = null;
            double v = data.number(r, c);
            if (st.format.kind == NumberFormat.GENERAL && !Double.isNaN(v) && Math.abs(v) < 1e11) {
                // A General number loses decimals until it fits, as in Excel.
                int sep = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'));
                int shown = sep >= 0 && s.indexOf('E') < 0 ? s.length() - 1 - sep : 0;
                for (int d = shown - 1; d >= 0 && fitted == null; d--) {
                    String t = NumberFormat.generalRounded(v, d);
                    if (t.length() < s.length() && text.measureText(t) <= right - left - 2 * pad) fitted = t;
                }
            }
            // Otherwise Excel shows #### when a number does not fit.
            s = fitted != null ? fitted : "###";
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

    /** Words of {@code s} in lines no wider than {@code width} (a long word gets a line of its own). */
    private java.util.List<String> wrapWords(String s, float width) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            if (line.length() > 0 && text.measureText(line + " " + word) > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
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
            canvas.translate((left + right) / 2f, (top + bottom) / 2f);
            canvas.rotate(deg);
            boolean upright = Math.abs(Math.abs(deg) - 90) < 0.5f;
            if (st.wrap && upright) {
                // Wrapped upright text: lines as long as the cell is tall, side by side, as Excel.
                java.util.List<String> lines = wrapWords(s, bottom - top - 2 * pad);
                float lineH = metrics.descent - metrics.ascent;
                float y0 = -lineH * lines.size() / 2f - metrics.ascent;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    canvas.drawText(line, -text.measureText(line) / 2f, y0 + i * lineH, text);
                }
            } else {
                float tw = text.measureText(s);
                canvas.drawText(s, -tw / 2f, -(metrics.ascent + metrics.descent) / 2f, text);
            }
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
}
