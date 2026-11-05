package com.tsdlanm.documentreader.xlsx;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import com.tsdlanm.documentreader.docx.DocModel;
import com.tsdlanm.documentreader.docx.DrawingML;

import java.util.List;

/** Draws the shapes of a sheet drawing (see {@link XlsxShapes}) into their anchor's box. */
final class ShapePainter {
    private ShapePainter() {
    }

    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final TextPaint TEXT = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF BOX = new RectF();

    /** {@code ptPx}: screen pixels per point at the current zoom. */
    static void draw(Canvas c, List<SheetData.Shape> shapes, RectF anchor, float ptPx) {
        float w = anchor.width(), h = anchor.height();
        for (SheetData.Shape s : shapes) {
            RectF r = BOX;
            r.set(anchor.left + Math.min(s.x0, s.x1) * w, anchor.top + Math.min(s.y0, s.y1) * h,
                    anchor.left + Math.max(s.x0, s.x1) * w, anchor.top + Math.max(s.y0, s.y1) * h);
            if (s.rotation != 0) {
                c.save();
                c.rotate(s.rotation, r.centerX(), r.centerY());
            }
            if (s.geometry == DocModel.GEOM_LINE) {
                if (s.line != 0) {
                    stroke(s, ptPx);
                    float x1 = s.flipH ? r.right : r.left, x2 = s.flipH ? r.left : r.right;
                    float y1 = s.flipV ? r.bottom : r.top, y2 = s.flipV ? r.top : r.bottom;
                    c.drawLine(x1, y1, x2, y2, PAINT);
                }
            } else {
                if (s.fill != 0) {
                    PAINT.setStyle(Paint.Style.FILL);
                    PAINT.setColor(s.fill);
                    PAINT.setShader(s.gradient != null ? DrawingML.shader(s.gradient, r) : null);
                    outline(c, s, r);
                    PAINT.setShader(null);
                }
                if (s.line != 0) {
                    stroke(s, ptPx);
                    outline(c, s, r);
                }
            }
            if (s.text != null) text(c, s, r, ptPx);
            if (s.rotation != 0) c.restore();
        }
    }

    private static void stroke(SheetData.Shape s, float ptPx) {
        PAINT.setShader(null);
        PAINT.setStyle(Paint.Style.STROKE);
        PAINT.setColor(s.line);
        PAINT.setStrokeWidth(Math.max(1f, s.lineWidth * ptPx));
    }

    private static void outline(Canvas c, SheetData.Shape s, RectF r) {
        if (s.geometry == DocModel.GEOM_PATH && s.path != null) {
            c.drawPath(DrawingML.toPath(s.path, r.left, r.top, r.width(), r.height(), s.flipH, s.flipV), PAINT);
        } else if (s.geometry == DocModel.GEOM_ELLIPSE) {
            c.drawOval(r, PAINT);
        } else if (s.geometry == DocModel.GEOM_ROUND_RECT) {
            float rad = Math.min(r.width(), r.height()) / 6f;
            c.drawRoundRect(r, rad, rad, PAINT);
        } else {
            c.drawRect(r, PAINT);
        }
    }

    private static void text(Canvas c, SheetData.Shape s, RectF r, float ptPx) {
        float left = r.left + s.insetL * ptPx, right = r.right - s.insetR * ptPx;
        float top = r.top + s.insetT * ptPx, bottom = r.bottom - s.insetB * ptPx;
        int width = Math.max(1, Math.round(right - left));
        StaticLayout layout = s.layout instanceof StaticLayout && s.layoutKey == ptPx * 10000 + width
                ? (StaticLayout) s.layout : null;
        if (layout == null) {
            layout = build(s, width, ptPx);
            s.layout = layout;
            s.layoutKey = ptPx * 10000 + width;
        }
        float lh = layout.getHeight();
        float y = s.anchor == 1 ? (top + bottom - lh) / 2f : s.anchor == 2 ? bottom - lh : top;
        c.save();
        c.clipRect(r);
        c.translate(left, y);
        layout.draw(c);
        c.restore();
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout build(SheetData.Shape s, int width, float ptPx) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        Layout.Alignment align = Layout.Alignment.ALIGN_NORMAL;
        for (int i = 0; i < s.text.size(); i++) {
            SheetData.Para para = s.text.get(i);
            if (i == 0) align = Layout.Alignment.values()[Math.min(2, para.align)];
            int start = sb.length();
            if (para.runs.isEmpty()) {
                sb.append(' ');
                sb.setSpan(new AbsoluteSizeSpan(Math.round(para.size * ptPx)), start, sb.length(), 0);
            }
            for (SheetData.Run run : para.runs) {
                int a = sb.length();
                sb.append(run.text);
                int b = sb.length();
                if (b == a) continue;
                sb.setSpan(new AbsoluteSizeSpan(Math.max(1, Math.round(run.size * ptPx))), a, b, 0);
                sb.setSpan(new ForegroundColorSpan(run.color), a, b, 0);
                int style = run.bold && run.italic ? Typeface.BOLD_ITALIC : run.bold ? Typeface.BOLD
                        : run.italic ? Typeface.ITALIC : Typeface.NORMAL;
                if (style != Typeface.NORMAL) sb.setSpan(new StyleSpan(style), a, b, 0);
                if (run.underline) sb.setSpan(new UnderlineSpan(), a, b, 0);
            }
            if (i < s.text.size() - 1) sb.append('\n');
            if (i > 0 && sb.length() > start) {
                sb.setSpan(new AlignmentSpan.Standard(Layout.Alignment.values()[Math.min(2, para.align)]),
                        start, sb.length(), android.text.Spanned.SPAN_PARAGRAPH);
            }
        }
        TEXT.setTextSize(11 * ptPx);
        TEXT.setColor(0xFF000000);
        return new StaticLayout(sb, TEXT, width, align, 1f, 0f, false);
    }
}
