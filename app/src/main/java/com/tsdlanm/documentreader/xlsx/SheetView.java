package com.tsdlanm.documentreader.xlsx;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a worksheet directly on a canvas: only the visible cells are touched each frame, so large
 * sheets scroll smoothly without creating thousands of views.
 */
public final class SheetView extends View {
    public interface OnCellSelectedListener {
        void onCellSelected(int row, int col, String text);
    }

    /** Excel sizes are in 96-dpi pixels / points; this scales them to dp for a phone. */
    private static final float SIZE_FACTOR = 1.15f;
    private static final float MIN_ZOOM = 0.35f, MAX_ZOOM = 4f;
    private static final int GRID = 0xFFD6D6D6, HEADER_BG = 0xFFF1F3F4, HEADER_LINE = 0xFFBDBDBD;
    private static final int HEADER_TEXT = 0xFF5F6368, SELECT = 0xFF1A73E8, SELECT_FILL = 0x221A73E8;

    private final float density;
    private final Paint fill = new Paint();
    private final Paint line = new Paint();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint headerText = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    public SheetView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.WHITE);
        line.setStrokeWidth(Math.max(1f, density * 0.75f));
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

    public void setOnCellSelectedListener(OnCellSelectedListener l) {
        listener = l;
    }

    public void setData(SheetData d) {
        data = d;
        selRow = selCol = -1;
        sx = sy = 0;
        scroller.forceFinished(true);
        int cols = d == null ? 0 : d.colCount;
        int rows = d == null ? 0 : d.rowCount;
        colX = new float[cols + 1];
        rowY = new float[rows + 1];
        float px = SIZE_FACTOR * density;
        for (int c = 0; c < cols; c++) colX[c + 1] = colX[c] + d.colWidths[c] * px;
        for (int r = 0; r < rows; r++) rowY[r + 1] = rowY[r] + d.rowHeights[r] * 1.333f * px;

        headerText.setTextSize(11 * density);
        String widest = String.valueOf(Math.max(1, rows));
        headerW = Math.max(32 * density, headerText.measureText(widest) + 12 * density);
        headerH = 22 * density;
        invalidate();
    }

    // ------------------------------------------------------------- geometry

    private float maxX() {
        return Math.max(0, colX[colX.length - 1] * zoom - (getWidth() - headerW * zoom));
    }

    private float maxY() {
        return Math.max(0, rowY[rowY.length - 1] * zoom - (getHeight() - headerH * zoom));
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

    private float cellLeft(int c) {
        return headerW * zoom + colX[c] * zoom - sx;
    }

    private float cellTop(int r) {
        return headerH * zoom + rowY[r] * zoom - sy;
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
        if (data == null || data.isEmpty()) return;
        if (x < headerW * zoom || y < headerH * zoom) return;
        float cx = (x - headerW * zoom + sx) / zoom;
        float cy = (y - headerH * zoom + sy) / zoom;
        if (cx >= colX[colX.length - 1] || cy >= rowY[rowY.length - 1]) return;
        int c = find(colX, cx), r = find(rowY, cy);
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

    @Override
    protected void onDraw(Canvas canvas) {
        if (data == null || data.isEmpty()) return;
        int w = getWidth(), h = getHeight();
        float hx = headerW * zoom, hy = headerH * zoom;
        int c0 = find(colX, sx / zoom);
        int c1 = find(colX, (sx + w - hx) / zoom);
        int r0 = find(rowY, sy / zoom);
        int r1 = find(rowY, (sy + h - hy) / zoom);
        float textPx = baseTextPx * zoom;
        float pad = 3 * density * zoom;

        // Only merges touching the screen are checked per cell.
        visibleMerges.clear();
        for (int[] m : data.merges) {
            if (m[2] < r0 || m[0] > r1 || m[3] < c0 - 8 || m[1] > c1) continue;
            visibleMerges.add(m);
        }

        canvas.save();
        canvas.clipRect(hx, hy, w, h);

        // Cell fills.
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                int f = data.style(r, c).fillColor;
                if (f == 0) continue;
                fill.setColor(f);
                canvas.drawRect(cellLeft(c), cellTop(r), cellLeft(c + 1), cellTop(r + 1), fill);
            }
        }

        // Grid lines.
        line.setColor(GRID);
        float right = Math.min(w, cellLeft(colX.length - 1));
        float bottom = Math.min(h, cellTop(rowY.length - 1));
        for (int c = c0; c <= c1 + 1 && c < colX.length; c++) {
            float x = cellLeft(c);
            canvas.drawLine(x, hy, x, bottom, line);
        }
        for (int r = r0; r <= r1 + 1 && r < rowY.length; r++) {
            float y = cellTop(r);
            canvas.drawLine(hx, y, right, y, line);
        }

        // Merged areas cover the grid lines inside them.
        for (int[] m : visibleMerges) {
            int lastR = Math.min(m[2], data.rowCount - 1), lastC = Math.min(m[3], data.colCount - 1);
            float l = cellLeft(m[1]), t = cellTop(m[0]), rr = cellLeft(lastC + 1), bb = cellTop(lastR + 1);
            int f = data.style(m[0], m[1]).fillColor;
            fill.setColor(f != 0 ? f : Color.WHITE);
            canvas.drawRect(l + 1, t + 1, rr - 1, bb - 1, fill);
        }

        // Text.
        for (int r = r0; r <= r1; r++) {
            if (rowY[r + 1] - rowY[r] <= 0) continue;
            for (int c = c0; c <= c1; c++) {
                drawCellText(canvas, r, c, c1, textPx, pad, false);
            }
            // Text from a cell left of the screen can overflow into view.
            for (int c = c0 - 1; c >= 0 && c >= c0 - 8; c--) {
                if (data.hasCell(r, c)) {
                    drawCellText(canvas, r, c, c1, textPx, pad, false);
                    break;
                }
            }
        }
        for (int[] m : visibleMerges) drawCellText(canvas, m[0], m[1], c1, textPx, pad, true);

        // Selection.
        if (selRow >= 0) {
            int[] m = mergeAt(selRow, selCol);
            int lastR = m != null ? Math.min(m[2], data.rowCount - 1) : selRow;
            int lastC = m != null ? Math.min(m[3], data.colCount - 1) : selCol;
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
        }
        canvas.restore();

        drawHeaders(canvas, w, h, hx, hy, c0, c1, r0, r1);
    }

    private void drawCellText(Canvas canvas, int r, int c, int lastVisibleCol, float textPx, float pad,
                              boolean merged) {
        if (!merged && mergeAt(visibleMerges, r, c) != null) return;
        String s = data.text(r, c);
        if (s == null || s.isEmpty()) return;
        CellStyle st = data.style(r, c);
        boolean number = data.isNumber(r, c);
        int lastR = r, lastC = c;
        if (merged) {
            int[] m = mergeAt(visibleMerges, r, c);
            if (m != null) {
                lastR = Math.min(m[2], data.rowCount - 1);
                lastC = Math.min(m[3], data.colCount - 1);
            }
        }
        float left = cellLeft(c), top = cellTop(r);
        float right = cellLeft(lastC + 1), bottom = cellTop(lastR + 1);
        if (bottom - top < 2) return;

        text.setTextSize(textPx * (st.fontSize > 0 ? st.fontSize : 1f));
        text.setColor(st.textColor != 0 ? st.textColor : 0xFF202124);
        text.setTypeface(st.bold && st.italic ? Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                : st.bold ? Typeface.DEFAULT_BOLD
                : st.italic ? Typeface.create(Typeface.DEFAULT, Typeface.ITALIC) : Typeface.DEFAULT);
        text.setUnderlineText(st.underline);

        // Display text on one line; line breaks inside a cell become spaces.
        if (s.indexOf('\n') >= 0) s = s.replace('\n', ' ');
        float tw = text.measureText(s);
        int align = st.align;
        if (align == CellStyle.ALIGN_GENERAL) align = number ? CellStyle.ALIGN_RIGHT : CellStyle.ALIGN_LEFT;

        float clipRight = right;
        if (!merged && align == CellStyle.ALIGN_LEFT && !number && tw > right - left - 2 * pad) {
            // Overflow into empty neighbours, like Excel.
            int k = lastC + 1;
            while (k < data.colCount && k <= lastVisibleCol + 1 && !data.hasCell(r, k)
                    && cellLeft(k) - left < tw + 2 * pad) {
                k++;
                clipRight = cellLeft(k);
            }
        }
        if (number && tw > right - left - 2 * pad) {
            // Excel shows #### when a number does not fit.
            s = "###";
            tw = text.measureText(s);
        }
        float x;
        if (align == CellStyle.ALIGN_RIGHT) x = right - pad - tw;
        else if (align == CellStyle.ALIGN_CENTER) x = (left + right - tw) / 2f;
        else x = left + pad;

        text.getFontMetrics(metrics);
        float textH = metrics.descent - metrics.ascent;
        float y = bottom - textH > top ? (top + bottom) / 2f - (metrics.ascent + metrics.descent) / 2f
                : bottom - metrics.descent;

        boolean needClip = x < left + 1 || x + tw > clipRight - 1 || textH > bottom - top;
        if (needClip) {
            canvas.save();
            canvas.clipRect(left + 1, top, clipRight - 1, bottom);
        }
        canvas.drawText(s, x, y, text);
        if (needClip) canvas.restore();
    }

    private void drawHeaders(Canvas canvas, int w, int h, float hx, float hy, int c0, int c1, int r0, int r1) {
        headerText.setTextSize(11 * density * Math.max(0.7f, Math.min(1.4f, zoom)));
        headerText.getFontMetrics(metrics);
        float mid = -(metrics.ascent + metrics.descent) / 2f;
        fill.setColor(HEADER_BG);
        line.setColor(HEADER_LINE);

        // Column letters.
        canvas.drawRect(0, 0, w, hy, fill);
        canvas.save();
        canvas.clipRect(hx, 0, w, hy);
        for (int c = c0; c <= c1; c++) {
            float l = cellLeft(c), r = cellLeft(c + 1);
            boolean sel = c == selCol;
            if (sel) {
                fill.setColor(0xFFD2E3FC);
                canvas.drawRect(l, 0, r, hy, fill);
                fill.setColor(HEADER_BG);
            }
            canvas.drawLine(r, 0, r, hy, line);
            if (r - l > 8 * density) canvas.drawText(SheetData.columnName(c), (l + r) / 2f, hy / 2f + mid, headerText);
        }
        canvas.restore();

        // Row numbers.
        canvas.drawRect(0, hy, hx, h, fill);
        canvas.save();
        canvas.clipRect(0, hy, hx, h);
        for (int r = r0; r <= r1; r++) {
            float t = cellTop(r), b = cellTop(r + 1);
            if (r == selRow) {
                fill.setColor(0xFFD2E3FC);
                canvas.drawRect(0, t, hx, b, fill);
                fill.setColor(HEADER_BG);
            }
            canvas.drawLine(0, b, hx, b, line);
            if (b - t > 6 * density) canvas.drawText(String.valueOf(r + 1), hx / 2f, (t + b) / 2f + mid, headerText);
        }
        canvas.restore();

        canvas.drawLine(hx, 0, hx, h, line);
        canvas.drawLine(0, hy, w, hy, line);
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
