package com.tsdlanm.documentreader.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Draws Windows metafiles (EMF and WMF: logos, equations, pasted drawings), which Android cannot
 * decode, into a bitmap. Lines, polygons, curves, boxes, ellipses, text and embedded bitmaps are
 * drawn with their pens, brushes and fonts; clipping, raster operations, patterns and EMF+ records
 * are not.
 */
final class Metafile {
    private Metafile() {
    }

    private static final int MAX_PIXELS = 2_500_000, MAX_SIDE = 2048;

    /** The picture about {@code targetW} pixels wide, or null when it is not a metafile we read. */
    static Bitmap render(byte[] data, int targetW) {
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (data.length > 44 && b.getInt(0) == 1 && b.getInt(40) == 0x464D4520) return new Emf(b).draw(targetW);
            if (data.length > 18) return new Wmf(b).draw(targetW);
        } catch (RuntimeException | OutOfMemoryError e) {
            // Corrupt or unexpected: shown as a placeholder.
            android.util.Log.w("Metafile", "cannot draw", e);
        }
        return null;
    }

    // ===================================================================== state

    private static final class Pen {
        int color = 0xFF000000;
        float width;
        boolean none;
    }

    private static final class Brush {
        int color = 0xFFFFFFFF;
        boolean none;
    }

    private static final class Font {
        float height = 12;
        int weight = 400;
        boolean italic, underline;
        float escapement;
        String face = "";
    }

    /** A device context: what SaveDC / RestoreDC keep. */
    private static final class Dc implements Cloneable {
        Pen pen = new Pen();
        Brush brush = new Brush();
        Font font = new Font();
        int textColor = 0xFF000000, textAlign, bkMode = 2, bkColor = 0xFFFFFFFF;
        boolean winding;
        float winOrgX, winOrgY, winExtX = 1, winExtY = 1, vpOrgX, vpOrgY, vpExtX = 1, vpExtY = 1;
        boolean hasWindow, hasViewport;
        Matrix world = new Matrix();
        float curX, curY;

        Dc copy() {
            try {
                Dc d = (Dc) clone();
                d.world = new Matrix(world);
                return d;
            } catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Shared drawing for both formats: logical coordinates mapped onto the bitmap. */
    private abstract static class Player {
        final ByteBuffer b;
        Dc dc = new Dc();
        final Deque<Dc> saved = new ArrayDeque<>();
        Object[] objects = new Object[64];
        Canvas canvas;
        /** Device (or frame) units to bitmap pixels. */
        final Matrix toBitmap = new Matrix();
        final Matrix total = new Matrix();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path path;
        final Path work = new Path();
        /** Records that could not be drawn (only the first is logged). */
        int errors;

        Player(ByteBuffer b) {
            this.b = b;
        }

        /** The bitmap for a picture whose device extent is {@code w} x {@code h} units. */
        Bitmap start(RectF frame, int targetW) {
            if (frame.width() <= 0 || frame.height() <= 0) return null;
            float scale = Math.max(1, targetW) / frame.width();
            int w = Math.round(frame.width() * scale), h = Math.round(frame.height() * scale);
            float shrink = 1f;
            if (w > MAX_SIDE) shrink = Math.min(shrink, MAX_SIDE / (float) w);
            if (h > MAX_SIDE) shrink = Math.min(shrink, MAX_SIDE / (float) h);
            if ((long) w * h * shrink * shrink > MAX_PIXELS) shrink = (float) Math.sqrt(MAX_PIXELS / ((double) w * h));
            scale *= shrink;
            w = Math.max(1, Math.round(frame.width() * scale));
            h = Math.max(1, Math.round(frame.height() * scale));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bmp);
            toBitmap.setTranslate(-frame.left, -frame.top);
            toBitmap.postScale(scale, scale);
            return bmp;
        }

        /** Logical units to bitmap pixels: world transform, window to viewport, device to bitmap. */
        Matrix matrix() {
            total.set(dc.world);
            if (dc.hasWindow) {
                total.postTranslate(-dc.winOrgX, -dc.winOrgY);
                float sx = dc.hasViewport ? dc.vpExtX / dc.winExtX : 1, sy = dc.hasViewport ? dc.vpExtY / dc.winExtY : 1;
                total.postScale(sx, sy);
                total.postTranslate(dc.vpOrgX, dc.vpOrgY);
            }
            total.postConcat(toBitmap);
            return total;
        }

        void put(int index, Object o) {
            if (index < 0) return;
            if (index >= objects.length) objects = java.util.Arrays.copyOf(objects, Math.max(index + 1, objects.length * 2));
            objects[index] = o;
        }

        void select(Object o) {
            if (o instanceof Pen) dc.pen = (Pen) o;
            else if (o instanceof Brush) dc.brush = (Brush) o;
            else if (o instanceof Font) dc.font = (Font) o;
        }

        /** Stock objects (EMF index | 0x80000000). */
        void selectStock(int s) {
            switch (s) {
                case 0: dc.brush = brush(0xFFFFFFFF); break;
                case 1: dc.brush = brush(0xFFC0C0C0); break;
                case 2: dc.brush = brush(0xFF808080); break;
                case 3: dc.brush = brush(0xFF404040); break;
                case 4: dc.brush = brush(0xFF000000); break;
                case 5: dc.brush = new Brush(); dc.brush.none = true; break;
                case 6: dc.pen = pen(0xFFFFFFFF, 0); break;
                case 7: dc.pen = pen(0xFF000000, 0); break;
                case 8: dc.pen = new Pen(); dc.pen.none = true; break;
                default: break;
            }
        }

        static Brush brush(int color) {
            Brush b = new Brush();
            b.color = color;
            return b;
        }

        static Pen pen(int color, float width) {
            Pen p = new Pen();
            p.color = color;
            p.width = width;
            return p;
        }

        /** Fills (unless open) and strokes a path in logical units. */
        void shape(Path p, boolean closed) {
            Matrix m = matrix();
            work.set(p);
            work.transform(m);
            work.setFillType(dc.winding ? Path.FillType.WINDING : Path.FillType.EVEN_ODD);
            if (path != null) {
                // Between BeginPath and EndPath, figures only build the path.
                path.addPath(p);
                return;
            }
            if (closed && !dc.brush.none) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(dc.brush.color);
                canvas.drawPath(work, paint);
            }
            stroke(work, m);
        }

        void stroke(Path devicePath, Matrix m) {
            if (dc.pen.none) return;
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(dc.pen.color);
            // Width in logical units, scaled like the drawing; 0 is one pixel.
            float[] v = {dc.pen.width, 0};
            m.mapVectors(v);
            paint.setStrokeWidth(dc.pen.width <= 0 ? 1f : Math.max(1f, (float) Math.hypot(v[0], v[1])));
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawPath(devicePath, paint);
        }

        void fillPath(boolean fill, boolean stroke) {
            if (path == null) return;
            Path p = path;
            path = null;
            Matrix m = matrix();
            work.set(p);
            work.transform(m);
            work.setFillType(dc.winding ? Path.FillType.WINDING : Path.FillType.EVEN_ODD);
            if (fill && !dc.brush.none) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(dc.brush.color);
                canvas.drawPath(work, paint);
            }
            if (stroke) stroke(work, m);
        }

        void rect(float l, float t, float r, float bt, int kind, float rx, float ry) {
            Path p = new Path();
            RectF rf = new RectF(Math.min(l, r), Math.min(t, bt), Math.max(l, r), Math.max(t, bt));
            if (kind == 1) p.addOval(rf, Path.Direction.CW);
            else if (kind == 2) p.addRoundRect(rf, rx / 2, ry / 2, Path.Direction.CW);
            else p.addRect(rf, Path.Direction.CW);
            shape(p, true);
        }

        /** Text at a logical point, upright on the bitmap even when the mapping flips y. */
        void text(float x, float y, String s) {
            if (s.isEmpty()) return;
            Font f = dc.font;
            if (SymbolFonts.isSymbolFont(f.face)) s = SymbolFonts.map(f.face, s);
            Matrix m = matrix();
            float[] pt = {x, y};
            m.mapPoints(pt);
            float[] h = {0, Math.abs(f.height)};
            m.mapVectors(h);
            float size = (float) Math.hypot(h[0], h[1]);
            // A positive height includes the internal leading.
            if (f.height > 0) size *= 0.82f;
            if (size < 0.5f) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(dc.textColor);
            paint.setTextSize(size);
            paint.setTypeface(typeface(f));
            paint.setUnderlineText(f.underline);
            Paint.FontMetrics fm = paint.getFontMetrics();
            int align = dc.textAlign;
            float w = paint.measureText(s);
            float dx = (align & 6) == 6 ? -w / 2 : (align & 6) == 2 ? -w : 0;
            float dy = (align & 24) == 24 ? 0 : (align & 8) == 8 ? -fm.descent : -fm.ascent;
            canvas.save();
            canvas.translate(pt[0], pt[1]);
            if (f.escapement != 0) canvas.rotate(-f.escapement / 10f);
            canvas.drawText(s, dx, dy, paint);
            canvas.restore();
            paint.setUnderlineText(false);
        }

        static Typeface typeface(Font f) {
            String n = f.face.toLowerCase(Locale.ROOT);
            Typeface family = n.contains("courier") || n.contains("mono") ? Typeface.MONOSPACE
                    : n.contains("times") || n.contains("roman") || n.contains("serif") && !n.contains("sans")
                    || n.contains("cambria") || n.contains("symbol") || n.contains("mt extra") ? Typeface.SERIF
                    : Typeface.SANS_SERIF;
            boolean bold = f.weight >= 600;
            int style = bold && f.italic ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD
                    : f.italic ? Typeface.ITALIC : Typeface.NORMAL;
            return Typeface.create(family, style);
        }

        /** A DIB (BITMAPINFO at {@code bmi}, bits at {@code bits}) drawn into a logical box. */
        void dib(int bmi, int bmiSize, int bits, int bitsSize, float x, float y, float w, float h,
                 int srcX, int srcY, int srcW, int srcH) {
            Bitmap bmp = decodeDib(b, bmi, bmiSize, bits, bitsSize);
            if (bmp == null) return;
            Matrix m = matrix();
            float[] pts = {x, y, x + w, y + h};
            m.mapPoints(pts);
            RectF dst = new RectF(Math.min(pts[0], pts[2]), Math.min(pts[1], pts[3]),
                    Math.max(pts[0], pts[2]), Math.max(pts[1], pts[3]));
            android.graphics.Rect src = null;
            if (srcW > 0 && srcH > 0 && (srcW != bmp.getWidth() || srcH != bmp.getHeight() || srcX != 0 || srcY != 0)) {
                // Source rows count from the bottom of a bottom-up DIB; most are whole anyway.
                src = new android.graphics.Rect(srcX, Math.max(0, bmp.getHeight() - srcY - srcH), srcX + srcW,
                        Math.min(bmp.getHeight(), bmp.getHeight() - srcY));
            }
            paint.setStyle(Paint.Style.FILL);
            canvas.drawBitmap(bmp, src, dst, paint);
            bmp.recycle();
        }
    }

    /** BITMAPINFO + bits as a Bitmap, through a BMP file header (or JPEG / PNG bits as they are). */
    static Bitmap decodeDib(ByteBuffer b, int bmi, int bmiSize, int bits, int bitsSize) {
        if (bmiSize < 12 || bitsSize <= 0 || bmi < 0 || bits < 0 || bmi + bmiSize > b.limit()
                || bits + bitsSize > b.limit()) {
            return null;
        }
        int compression = bmiSize >= 40 ? b.getInt(bmi + 16) : 0;
        byte[] data = b.array();
        int off = b.arrayOffset();
        if (compression == 4 || compression == 5) {
            return BitmapFactory.decodeByteArray(data, off + bits, bitsSize);
        }
        int total = 14 + bmiSize + bitsSize;
        byte[] file = new byte[total];
        ByteBuffer f = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        f.put((byte) 'B').put((byte) 'M').putInt(total).putInt(0).putInt(14 + bmiSize);
        System.arraycopy(data, off + bmi, file, 14, bmiSize);
        System.arraycopy(data, off + bits, file, 14 + bmiSize, bitsSize);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(file, 0, total, o);
    }

    static int colorRef(int v) {
        return 0xFF000000 | (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16) & 0xFF;
    }

    // ======================================================================= EMF

    private static final class Emf extends Player {
        Emf(ByteBuffer b) {
            super(b);
        }

        Bitmap draw(int targetW) {
            // rclBounds: the device-unit box of everything drawn.
            RectF frame = new RectF(b.getInt(8), b.getInt(12), b.getInt(16) + 1, b.getInt(20) + 1);
            Bitmap bmp = start(frame, targetW);
            if (bmp == null) return null;
            int pos = 0, end = b.limit();
            while (pos + 8 <= end) {
                int type = b.getInt(pos), size = b.getInt(pos + 4);
                if (size < 8 || pos + size > end) break;
                if (type == 14) break;
                try {
                    record(type, pos, size);
                } catch (RuntimeException e) {
                    // One bad record leaves the rest of the picture.
                    if (errors++ == 0) android.util.Log.w("Metafile", "EMF record " + type, e);
                }
                pos += size;
            }
            return bmp;
        }

        private float fx(int p) {
            return b.getFloat(p);
        }

        private void record(int t, int p, int size) {
            switch (t) {
                case 9: dc.winExtX = nz(b.getInt(p + 8)); dc.winExtY = nz(b.getInt(p + 12)); dc.hasWindow = true; break;
                case 10: dc.winOrgX = b.getInt(p + 8); dc.winOrgY = b.getInt(p + 12); dc.hasWindow = true; break;
                case 11: dc.vpExtX = nz(b.getInt(p + 8)); dc.vpExtY = nz(b.getInt(p + 12)); dc.hasViewport = true; break;
                case 12: dc.vpOrgX = b.getInt(p + 8); dc.vpOrgY = b.getInt(p + 12); dc.hasViewport = true; break;
                case 18: dc.bkMode = b.getInt(p + 8); break;
                case 19: dc.winding = b.getInt(p + 8) == 2; break;
                case 22: dc.textAlign = b.getInt(p + 8); break;
                case 24: dc.textColor = colorRef(b.getInt(p + 8)); break;
                case 25: dc.bkColor = colorRef(b.getInt(p + 8)); break;
                case 27: dc.curX = b.getInt(p + 8); dc.curY = b.getInt(p + 12); if (path != null) path.moveTo(dc.curX, dc.curY); break;
                case 54: {
                    float x = b.getInt(p + 8), y = b.getInt(p + 12);
                    Path l = new Path();
                    l.moveTo(dc.curX, dc.curY);
                    l.lineTo(x, y);
                    if (path != null) path.lineTo(x, y);
                    else shape(l, false);
                    dc.curX = x;
                    dc.curY = y;
                    break;
                }
                case 33: saved.push(dc.copy()); break;
                case 34: {
                    int n = b.getInt(p + 8);
                    for (int i = 0; i < -n - 1 && saved.size() > 1; i++) saved.pop();
                    if (!saved.isEmpty()) dc = saved.pop();
                    break;
                }
                case 35: dc.world.setValues(xform(p + 8)); break;
                case 36: {
                    int mode = b.getInt(p + 32);
                    Matrix x = new Matrix();
                    x.setValues(xform(p + 8));
                    if (mode == 1) dc.world.reset();
                    else if (mode == 2) dc.world.preConcat(x);
                    else if (mode == 3) dc.world.postConcat(x);
                    else if (mode == 4) dc.world.set(x);
                    break;
                }
                case 37: {
                    int i = b.getInt(p + 8);
                    if ((i & 0x80000000) != 0) selectStock(i & 0x7FFFFFFF);
                    else if (i < objects.length) select(objects[i]);
                    break;
                }
                case 38: {
                    Pen pen = new Pen();
                    int style = b.getInt(p + 12);
                    pen.none = (style & 0xF) == 5;
                    pen.width = b.getInt(p + 16);
                    pen.color = colorRef(b.getInt(p + 24));
                    put(b.getInt(p + 8), pen);
                    break;
                }
                case 95: {
                    // ihPen, offBmi, cbBmi, offBits, cbBits, then LogPenEx: style, width, brush style, colour.
                    Pen pen = new Pen();
                    int style = b.getInt(p + 28);
                    pen.none = (style & 0xF) == 5;
                    pen.width = b.getInt(p + 32);
                    pen.color = colorRef(b.getInt(p + 40));
                    put(b.getInt(p + 8), pen);
                    break;
                }
                case 39: {
                    Brush br = new Brush();
                    int style = b.getInt(p + 12);
                    br.none = style == 1;
                    br.color = colorRef(b.getInt(p + 16));
                    put(b.getInt(p + 8), br);
                    break;
                }
                case 40: {
                    int i = b.getInt(p + 8);
                    if (i >= 0 && i < objects.length) objects[i] = null;
                    break;
                }
                case 82: {
                    Font f = new Font();
                    int lf = p + 12;
                    f.height = b.getInt(lf);
                    f.escapement = b.getInt(lf + 8);
                    f.weight = b.getInt(lf + 16);
                    f.italic = b.get(lf + 20) != 0;
                    f.underline = b.get(lf + 21) != 0;
                    f.face = utf16(lf + 28, 32);
                    put(b.getInt(p + 8), f);
                    break;
                }
                case 84:
                case 83: {
                    // EMRTEXT after bounds, graphics mode and scales.
                    int e = p + 36;
                    float x = b.getInt(e), y = b.getInt(e + 4);
                    int n = b.getInt(e + 8), off = b.getInt(e + 12);
                    if (n <= 0 || n > 10000 || off <= 0 || p + off + n * (t == 84 ? 2 : 1) > p + size) break;
                    String s = t == 84 ? utf16(p + off, n) : ansi(p + off, n);
                    if ((dc.textAlign & 1) != 0) {
                        x = dc.curX;
                        y = dc.curY;
                    }
                    text(x, y, s);
                    break;
                }
                case 3:
                case 4:
                case 2:
                case 5:
                case 6:
                    poly(t, p + 24, b.getInt(p + 24), false);
                    break;
                case 85:
                case 86:
                case 87:
                case 88:
                case 89:
                    poly(t - 83, p + 24, b.getInt(p + 24), true);
                    break;
                case 7:
                case 8:
                case 90:
                case 91: {
                    boolean small = t >= 90;
                    boolean closed = t == 8 || t == 91;
                    int n = b.getInt(p + 24);
                    int counts = p + 32;
                    int pts = counts + 4 * n;
                    Path all = new Path();
                    for (int k = 0; k < n; k++) {
                        int c = b.getInt(counts + 4 * k);
                        addPoints(all, pts, c, small, closed, true);
                        pts += c * (small ? 4 : 8);
                    }
                    shape(all, closed);
                    break;
                }
                case 42:
                case 43:
                    rect(b.getInt(p + 8), b.getInt(p + 12), b.getInt(p + 16), b.getInt(p + 20), t == 42 ? 1 : 0, 0, 0);
                    break;
                case 44:
                    rect(b.getInt(p + 8), b.getInt(p + 12), b.getInt(p + 16), b.getInt(p + 20), 2,
                            b.getInt(p + 24), b.getInt(p + 28));
                    break;
                case 59: path = new Path(); break;
                case 60: break;
                case 61: if (path != null) path.close(); break;
                case 62: fillPath(true, false); break;
                case 63: fillPath(true, true); break;
                case 64: fillPath(false, true); break;
                case 68: path = null; break;
                case 81: {
                    // STRETCHDIBITS
                    float x = b.getInt(p + 24), y = b.getInt(p + 28);
                    int sx = b.getInt(p + 32), sy = b.getInt(p + 36), sw = b.getInt(p + 40), sh = b.getInt(p + 44);
                    int offBmi = b.getInt(p + 48), cbBmi = b.getInt(p + 52), offBits = b.getInt(p + 56), cbBits = b.getInt(p + 60);
                    float w = b.getInt(p + 72), h = b.getInt(p + 76);
                    dib(p + offBmi, cbBmi, p + offBits, cbBits, x, y, w, h, sx, sy, sw, sh);
                    break;
                }
                case 76:
                case 77: {
                    // BITBLT / STRETCHBLT
                    float x = b.getInt(p + 24), y = b.getInt(p + 28), w = b.getInt(p + 32), h = b.getInt(p + 36);
                    int offBmi = b.getInt(p + 84), cbBmi = b.getInt(p + 88), offBits = b.getInt(p + 92), cbBits = b.getInt(p + 96);
                    int rop = b.getInt(p + 40);
                    if (cbBmi == 0) {
                        // No source: only PATCOPY paints (the brush); others (NOP...) leave it.
                        if (rop != PATCOPY) break;
                        boolean penNone = dc.pen.none;
                        dc.pen.none = true;
                        rect(x, y, x + w, y + h, 0, 0, 0);
                        dc.pen.none = penNone;
                    } else {
                        int sw = t == 77 ? b.getInt(p + 100) : 0, sh = t == 77 ? b.getInt(p + 104) : 0;
                        dib(p + offBmi, cbBmi, p + offBits, cbBits, x, y, w, h, 0, 0, sw, sh);
                    }
                    break;
                }
                default:
                    break;
            }
        }

        private float[] xform(int p) {
            // eM11 eM12 eM21 eM22 eDx eDy -> Android's row-major 3x3.
            return new float[]{fx(p), fx(p + 8), fx(p + 16), fx(p + 4), fx(p + 12), fx(p + 20), 0, 0, 1};
        }

        /** POLYBEZIER(2) POLYGON(3) POLYLINE(4) POLYBEZIERTO(5) POLYLINETO(6), 32- or 16-bit points. */
        private void poly(int kind, int countAt, int n, boolean small) {
            if (n <= 0 || n > 1_000_000) return;
            int pts = countAt + 4;
            boolean to = kind == 5 || kind == 6;
            // Inside BeginPath / EndPath the "...To" records continue the current figure (glyph
            // outlines are drawn so); elsewhere each record is a figure of its own.
            boolean continuing = to && path != null;
            Path p = continuing ? path : new Path();
            if (to && !continuing) p.moveTo(dc.curX, dc.curY);
            if (kind == 2 || kind == 5) {
                int i = 0;
                float[] q = point(pts, 0, small);
                if (!to) {
                    p.moveTo(q[0], q[1]);
                    i = 1;
                }
                for (; i + 2 < n + (to ? 0 : 0); i += 3) {
                    float[] a = point(pts, i, small), c = point(pts, i + 1, small), d = point(pts, i + 2, small);
                    p.cubicTo(a[0], a[1], c[0], c[1], d[0], d[1]);
                    dc.curX = d[0];
                    dc.curY = d[1];
                }
            } else {
                for (int i = 0; i < n; i++) {
                    float[] q = point(pts, i, small);
                    if (i == 0 && !to) p.moveTo(q[0], q[1]);
                    else p.lineTo(q[0], q[1]);
                    dc.curX = q[0];
                    dc.curY = q[1];
                }
            }
            if (kind == 3) p.close();
            if (continuing) return;
            if (path != null) {
                path.addPath(p);
                return;
            }
            shape(p, kind == 3);
        }

        private void addPoints(Path out, int pts, int n, boolean small, boolean close, boolean move) {
            for (int i = 0; i < n; i++) {
                float[] q = point(pts, i, small);
                if (i == 0 && move) out.moveTo(q[0], q[1]);
                else out.lineTo(q[0], q[1]);
            }
            if (close) out.close();
        }

        private float[] point(int pts, int i, boolean small) {
            if (small) return new float[]{b.getShort(pts + 4 * i), b.getShort(pts + 4 * i + 2)};
            return new float[]{b.getInt(pts + 8 * i), b.getInt(pts + 8 * i + 4)};
        }

        private String utf16(int p, int n) {
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n && p + 2 * i + 1 < b.limit(); i++) {
                char c = b.getChar(p + 2 * i);
                if (c == 0) break;
                sb.append(c);
            }
            return sb.toString();
        }

        private String ansi(int p, int n) {
            return new String(b.array(), b.arrayOffset() + p, Math.min(n, b.limit() - p), Charset.forName("windows-1252"));
        }
    }

    /** The raster operation that fills with the brush. */
    private static final int PATCOPY = 0x00F00021;

    private static float nz(int v) {
        return v == 0 ? 1 : v;
    }

    // ======================================================================= WMF

    private static final class Wmf extends Player {
        private int start;
        private RectF bounds;

        Wmf(ByteBuffer b) {
            super(b);
            if (b.getInt(0) == 0x9AC6CDD7) {
                // Placeable header: the picture's box in logical units.
                bounds = new RectF(b.getShort(6), b.getShort(8), b.getShort(10), b.getShort(12));
                start = 22;
            }
        }

        Bitmap draw(int targetW) {
            int headerWords = b.getShort(start + 2);
            if (headerWords != 9) return null;
            int pos = start + 18;
            // Without a placeable header, the window set by the records is the picture.
            if (bounds == null) bounds = scanWindow(pos);
            if (bounds == null || bounds.width() == 0 || bounds.height() == 0) return null;
            RectF frame = new RectF(0, 0, Math.abs(bounds.width()), Math.abs(bounds.height()));
            Bitmap bmp = start(frame, targetW);
            if (bmp == null) return null;
            // The picture's box is the viewport; the window starts as that box and the records may
            // set their own (logical units in the thousands, flipped extents mirroring).
            dc.hasWindow = true;
            dc.hasViewport = true;
            dc.winOrgX = bounds.left;
            dc.winOrgY = bounds.top;
            dc.winExtX = nzf(bounds.width());
            dc.winExtY = nzf(bounds.height());
            dc.vpExtX = frame.width();
            dc.vpExtY = frame.height();
            int end = b.limit();
            while (pos + 6 <= end) {
                long words = b.getInt(pos) & 0xFFFFFFFFL;
                int fn = b.getShort(pos + 4) & 0xFFFF;
                if (words < 3 || pos + words * 2 > end) break;
                if (fn == 0) break;
                try {
                    record(fn, pos + 6, (int) words);
                } catch (RuntimeException e) {
                    if (errors++ == 0) android.util.Log.w("Metafile", "WMF record " + Integer.toHexString(fn), e);
                }
                pos += (int) (words * 2);
            }
            return bmp;
        }

        private RectF scanWindow(int pos) {
            float ox = 0, oy = 0, ex = 0, ey = 0;
            int end = b.limit();
            while (pos + 6 <= end) {
                long words = b.getInt(pos) & 0xFFFFFFFFL;
                int fn = b.getShort(pos + 4) & 0xFFFF;
                if (words < 3 || pos + words * 2 > end || fn == 0) break;
                if (fn == 0x020B) {
                    oy = b.getShort(pos + 6);
                    ox = b.getShort(pos + 8);
                } else if (fn == 0x020C) {
                    ey = b.getShort(pos + 6);
                    ex = b.getShort(pos + 8);
                }
                pos += (int) (words * 2);
            }
            return ex == 0 || ey == 0 ? null : new RectF(ox, oy, ox + ex, oy + ey);
        }

        private short s(int p, int i) {
            return b.getShort(p + 2 * i);
        }

        private void record(int fn, int p, int words) {
            switch (fn) {
                case 0x020B:
                    dc.winOrgY = s(p, 0);
                    dc.winOrgX = s(p, 1);
                    break;
                case 0x020C:
                    dc.winExtY = nz(s(p, 0));
                    dc.winExtX = nz(s(p, 1));
                    break;
                case 0x061D: {
                    // PATBLT: rop, height, width, y, x; PATCOPY fills with the brush.
                    int rop = (s(p, 0) & 0xFFFF) | (s(p, 1) & 0xFFFF) << 16;
                    if (rop != PATCOPY) break;
                    float h = s(p, 2), w = s(p, 3), y = s(p, 4), x = s(p, 5);
                    boolean penNone = dc.pen.none;
                    dc.pen.none = true;
                    rect(x, y, x + w, y + h, 0, 0, 0);
                    dc.pen.none = penNone;
                    break;
                }
                case 0x0102: dc.bkMode = s(p, 0); break;
                case 0x0106: dc.winding = s(p, 0) == 2; break;
                case 0x012E: dc.textAlign = s(p, 0) & 0xFFFF; break;
                case 0x0209: dc.textColor = colorRef(b.getInt(p)); break;
                case 0x0201: dc.bkColor = colorRef(b.getInt(p)); break;
                case 0x001E: saved.push(dc.copy()); break;
                case 0x0127: if (!saved.isEmpty()) dc = saved.pop(); break;
                case 0x0214: dc.curY = s(p, 0); dc.curX = s(p, 1); break;
                case 0x0213: {
                    float y = s(p, 0), x = s(p, 1);
                    Path l = new Path();
                    l.moveTo(dc.curX, dc.curY);
                    l.lineTo(x, y);
                    shape(l, false);
                    dc.curX = x;
                    dc.curY = y;
                    break;
                }
                case 0x0324:
                case 0x0325: {
                    int n = s(p, 0);
                    Path path = new Path();
                    for (int i = 0; i < n; i++) {
                        float x = s(p, 1 + 2 * i), y = s(p, 2 + 2 * i);
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    if (fn == 0x0324) path.close();
                    shape(path, fn == 0x0324);
                    break;
                }
                case 0x0538: {
                    int n = s(p, 0);
                    int at = 1 + n;
                    Path path = new Path();
                    for (int k = 0; k < n; k++) {
                        int c = s(p, 1 + k);
                        for (int i = 0; i < c; i++) {
                            float x = s(p, at + 2 * i), y = s(p, at + 2 * i + 1);
                            if (i == 0) path.moveTo(x, y);
                            else path.lineTo(x, y);
                        }
                        path.close();
                        at += 2 * c;
                    }
                    shape(path, true);
                    break;
                }
                case 0x041B:
                case 0x0418:
                    rect(s(p, 3), s(p, 2), s(p, 1), s(p, 0), fn == 0x0418 ? 1 : 0, 0, 0);
                    break;
                case 0x061C:
                    rect(s(p, 5), s(p, 4), s(p, 3), s(p, 2), 2, s(p, 1), s(p, 0));
                    break;
                case 0x02FA: {
                    Pen pen = new Pen();
                    pen.none = (s(p, 0) & 0xF) == 5;
                    pen.width = s(p, 1);
                    pen.color = colorRef(b.getInt(p + 6));
                    add(pen);
                    break;
                }
                case 0x02FC: {
                    Brush br = new Brush();
                    br.none = s(p, 0) == 1;
                    br.color = colorRef(b.getInt(p + 2));
                    add(br);
                    break;
                }
                case 0x02FB: {
                    Font f = new Font();
                    f.height = s(p, 0);
                    f.escapement = s(p, 2);
                    f.weight = s(p, 4);
                    f.italic = b.get(p + 10) != 0;
                    f.underline = b.get(p + 11) != 0;
                    int faceAt = p + 18;
                    int len = 0;
                    while (len < 32 && faceAt + len < b.limit() && b.get(faceAt + len) != 0) len++;
                    f.face = new String(b.array(), b.arrayOffset() + faceAt, len, Charset.forName("windows-1252"));
                    add(f);
                    break;
                }
                case 0x00F7:
                case 0x01F9:
                case 0x06FF:
                case 0x0142:
                case 0x01F8:
                case 0x00FC:
                    // Palettes, regions and pattern brushes take a slot too.
                    add(new Object());
                    break;
                case 0x012D: {
                    int i = s(p, 0) & 0xFFFF;
                    if (i < objects.length) select(objects[i]);
                    break;
                }
                case 0x01F0: {
                    int i = s(p, 0) & 0xFFFF;
                    if (i < objects.length) objects[i] = null;
                    break;
                }
                case 0x0A32: {
                    float y = s(p, 0), x = s(p, 1);
                    int n = s(p, 2), opts = s(p, 3);
                    int str = p + 8 + ((opts & 6) != 0 ? 8 : 0);
                    if (n <= 0 || str + n > b.limit()) break;
                    text(x, y, new String(b.array(), b.arrayOffset() + str, n, Charset.forName("windows-1252")));
                    break;
                }
                case 0x0521: {
                    int n = s(p, 0);
                    int str = p + 2;
                    int after = str + ((n + 1) & ~1);
                    if (n <= 0 || after + 4 > b.limit()) break;
                    float y = b.getShort(after), x = b.getShort(after + 2);
                    text(x, y, new String(b.array(), b.arrayOffset() + str, n, Charset.forName("windows-1252")));
                    break;
                }
                case 0x0F43: {
                    // STRETCHDIB: rop(2 words), usage, srcH, srcW, ySrc, xSrc, destH, destW, yDst, xDst, DIB
                    float h = s(p, 7), w = s(p, 8), y = s(p, 9), x = s(p, 10);
                    blit(p + 22, words * 2 - 6 - 22, x, y, w, h);
                    break;
                }
                case 0x0B41: {
                    // DIBSTRETCHBLT: rop(2), srcH, srcW, ySrc, xSrc, destH, destW, yDst, xDst, DIB
                    float h = s(p, 6), w = s(p, 7), y = s(p, 8), x = s(p, 9);
                    blit(p + 20, words * 2 - 6 - 20, x, y, w, h);
                    break;
                }
                case 0x0940: {
                    // DIBBITBLT: rop(2), ySrc, xSrc, height, width, yDst, xDst, DIB
                    float h = s(p, 4), w = s(p, 5), y = s(p, 6), x = s(p, 7);
                    blit(p + 16, words * 2 - 6 - 16, x, y, w, h);
                    break;
                }
                default:
                    break;
            }
        }

        /** A DIB record's bitmap (BITMAPINFOHEADER, palette, bits) drawn into a logical box. */
        private void blit(int dibAt, int dibSize, float x, float y, float w, float h) {
            if (dibSize < 40) return;
            int hdr = b.getInt(dibAt);
            if (hdr < 12 || hdr > dibSize) return;
            int bpp = hdr >= 40 ? b.getShort(dibAt + 14) : b.getShort(dibAt + 10);
            int used = hdr >= 40 ? b.getInt(dibAt + 32) : 0;
            int colors = used > 0 ? used : bpp <= 8 ? 1 << bpp : 0;
            int compression = hdr >= 40 ? b.getInt(dibAt + 16) : 0;
            if (compression == 3) colors = 3;
            int bmiSize = hdr + colors * (hdr >= 40 ? 4 : 3);
            if (bmiSize >= dibSize) return;
            dib(dibAt, bmiSize, dibAt + bmiSize, dibSize - bmiSize, x, y, w, h, 0, 0, 0, 0);
        }

        /** WMF objects go in the first free slot. */
        private void add(Object o) {
            for (int i = 0; i < objects.length; i++) {
                if (objects[i] == null) {
                    objects[i] = o;
                    return;
                }
            }
            put(objects.length, o);
        }
    }

    private static float nzf(float v) {
        return v == 0 ? 1 : v;
    }
}
