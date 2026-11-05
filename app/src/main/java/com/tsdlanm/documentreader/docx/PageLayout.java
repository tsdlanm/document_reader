package com.tsdlanm.documentreader.docx;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineHeightSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.SubscriptSpan;
import android.text.style.SuperscriptSpan;
import android.text.style.TabStopSpan;
import android.text.style.UnderlineSpan;

import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Border;
import com.tsdlanm.documentreader.docx.DocModel.Cell;
import com.tsdlanm.documentreader.docx.DocModel.ColumnBreak;
import com.tsdlanm.documentreader.docx.DocModel.TextBox;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.PageBreak;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Row;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.Section;
import com.tsdlanm.documentreader.docx.DocModel.SectionBreak;
import com.tsdlanm.documentreader.docx.DocModel.Table;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lays a document out on pages of its own paper size, like Word's print layout. Coordinates are in
 * layout units ({@link #UNIT} per point); the view scales them to the screen. Document fonts are
 * mapped to the phone's serif, sans-serif or monospace family, so lines may break a little
 * differently than in Word.
 */
public final class PageLayout {
    /**
     * Layout units per point: one unit is a twip, Word's own unit. Line heights must be whole
     * units (Android's FontMetricsInt), and coarser units round every line a little differently
     * from Word, which over a page adds up to a whole line.
     */
    static final float UNIT = 20f;

    /** Supplies decoded images and the current drawing scale to the items. */
    public interface Painter {
        /** The bitmap for {@code item}, or null while it is still loading. */
        Bitmap image(ImageItem item);

        /** Screen pixels per layout unit. */
        float scale();
    }

    public static final class Page {
        public final float width, height;
        final List<Item> items = new ArrayList<>();
        final Sec sec;
        final boolean firstOfSection;
        final String number;
        /** The page number as a number, for odd and even headers. */
        int pageNo;
        /** Top and bottom of the body text, set by the layout (NaN before). */
        float textTop = Float.NaN, textBottom = Float.NaN;
        /** Header and footer, laid out on first draw. */
        List<Item> decorations;

        Page(Sec sec, boolean firstOfSection, String number) {
            this.sec = sec;
            this.width = sec.width;
            this.height = sec.height;
            this.firstOfSection = firstOfSection;
            this.number = number;
        }
    }

    public final List<Page> pages = new ArrayList<>();
    private final DocModel.Document doc;

    private PageLayout(DocModel.Document doc) {
        this.doc = doc;
    }

    static float tw(int twips) {
        return twips * UNIT / 20f;
    }

    static float emu(long emu) {
        return emu * UNIT / 12700f;
    }

    /** Receives finished pages while the layout is still running (on the layout thread). */
    public interface Progress {
        void onPages(List<Page> finished);
    }

    /** Lays out the whole document. Runs on a background thread. */
    public static PageLayout build(DocModel.Document doc) {
        PageLayout l = create(doc);
        l.layout(null);
        return l;
    }

    /** A layout that has not run yet; see {@link #layout}. */
    public static PageLayout create(DocModel.Document doc) {
        return new PageLayout(doc);
    }

    /**
     * Lays out the document on the calling (background) thread, handing over pages in batches as
     * they are finished, so the first pages can be shown long before a long document is done.
     * {@link #pages} must not be read by other threads until this returns.
     */
    public void layout(Progress progress) {
        new BodyFlow(this, progress).run();
    }

    /**
     * Held while drawing page items: drawing shares paints and Android's Layout objects, and pages
     * are drawn both on the UI thread (zoomed in) and on the page-cache thread.
     */
    static final java.util.concurrent.locks.ReentrantLock DRAW_LOCK = new java.util.concurrent.locks.ReentrantLock();

    /** Draws a page (body, header and footer) in layout units. Call with {@link #DRAW_LOCK} held. */
    void drawPage(Canvas c, Page page, Painter painter, float visTop, float visBottom) {
        List<Item> deco = decorations(page);
        // Slides draw their background first; on Word pages the header and footer (and the
        // watermarks they hold) lie behind the body text.
        boolean slide = page.sec.section.background != 0 || page.sec.section.backgroundImage != null
                || page.sec.section.backgroundGradient != null;
        if (!slide) drawItems(c, deco, painter, visTop, visBottom);
        drawItems(c, page.items, painter, visTop, visBottom);
        if (slide) drawItems(c, deco, painter, visTop, visBottom);
    }

    private static void drawItems(Canvas c, List<Item> items, Painter painter, float visTop, float visBottom) {
        for (Item item : items) {
            if (item.bottom >= visTop && item.top <= visBottom) item.draw(c, painter);
        }
    }

    /** Whether a page uses its section's even-page header and footer. */
    private boolean even(Page page) {
        return doc.evenAndOddHeaders && page.pageNo % 2 == 0
                && (page.sec.evenHeader != null || page.sec.evenFooter != null);
    }

    /** Measured header and footer flows, by block list: the same on most pages. */
    private final java.util.IdentityHashMap<List<Block>, Flow> decorationFlows = new java.util.IdentityHashMap<>();

    private Flow decorationFlow(List<Block> blocks, Sec s) {
        if (blocks == null || blocks.isEmpty()) return null;
        Flow f = decorationFlows.get(blocks);
        if (f == null) {
            f = measureFlow(blocks, s.contentWidth, "1");
            decorationFlows.put(blocks, f);
        }
        return f;
    }

    private float decorationHeight(List<Block> blocks, Sec s) {
        Flow f = decorationFlow(blocks, s);
        return f == null ? 0 : f.height;
    }

    /**
     * The lowest thin horizontal rule a header draws below its text (a letterhead's line), from the
     * page top; 0 for none. The body is kept below it: Word's taller header lines usually do that.
     */
    private float headerRuleBottom(List<Block> blocks, Sec s) {
        Flow f = decorationFlow(blocks, s);
        if (f == null) return 0;
        float top = tw(s.section.headerDistance), bottom = 0;
        for (int i = 0; i < f.pieces.size(); i++) {
            Object o = f.pieces.get(i);
            if (!(o instanceof FloatPiece)) continue;
            FloatPiece fp = (FloatPiece) o;
            DocModel.Drawing d = fp.drawing;
            boolean rule = d instanceof TextBox && fp.h <= 6 * UNIT && fp.w >= s.contentWidth * 0.5f
                    && (((TextBox) d).geometry == DocModel.GEOM_LINE || ((TextBox) d).blocks.isEmpty());
            if (!rule || d.vRel != DocModel.REL_PARAGRAPH) continue;
            float y = top + f.offsets.get(i) + emu(d.vOffset) + fp.h;
            if (y > bottom) bottom = y;
        }
        return bottom;
    }

    /**
     * Top and bottom of a page's body text: the margins, moved where the header reaches below the
     * top margin or the footer above the bottom margin (Word keeps the text clear of both).
     */
    float[] textArea(Page page) {
        Sec s = page.sec;
        boolean first = page.firstOfSection && s.section.titlePage;
        float top = s.top, bottom = s.bottom;
        // Slides have no header or footer flow.
        List<Block> headerBlocks = first ? s.firstHeader : even(page) ? s.evenHeader : s.header;
        float header = decorationHeight(headerBlocks, s);
        if (header > 0) top = Math.max(top, tw(s.section.headerDistance) + header);
        float rule = headerRuleBottom(headerBlocks, s);
        // A rule just past the text start pushes it down (a little room under the line too).
        if (rule > top && rule < top + 36 * UNIT) top = rule + 4 * UNIT;
        float footer = decorationHeight(first ? s.firstFooter : even(page) ? s.evenFooter : s.footer, s);
        if (footer > 0) bottom = Math.min(bottom, s.height - tw(s.section.footerDistance) - footer);
        // Never less than a third of the page for the text.
        if (bottom - top < (s.bottom - s.top) / 3f) return new float[]{s.top, s.bottom};
        return new float[]{top, bottom};
    }

    /** Header and footer items of a page; built lazily because they hold the page number. */
    List<Item> decorations(Page page) {
        if (page.decorations != null) return page.decorations;
        List<Item> out = new ArrayList<>();
        Sec s = page.sec;
        boolean first = page.firstOfSection && s.section.titlePage;
        List<Block> header = first ? s.firstHeader : even(page) ? s.evenHeader : s.header;
        List<Block> footer = first ? s.firstFooter : even(page) ? s.evenFooter : s.footer;
        if (header != null && !header.isEmpty()) {
            Flow f = measureFlow(header, s.contentWidth, page.number);
            f.marginTop = page.textTop;
            f.marginBottom = page.textBottom;
            emitFlow(f, s.left, tw(s.section.headerDistance), out, s);
        }
        if (footer != null && !footer.isEmpty()) {
            Flow f = measureFlow(footer, s.contentWidth, page.number);
            f.marginTop = page.textTop;
            f.marginBottom = page.textBottom;
            emitFlow(f, s.left, s.height - tw(s.section.footerDistance) - f.height, out, s);
        }
        page.decorations = out;
        return out;
    }

    // ================================================================ sections

    /** A section with inherited headers/footers resolved and sizes in layout units. */
    static final class Sec {
        final Section section;
        final float width, height, left, top, bottom, contentWidth;
        /** Text columns: left edge (from the content edge) and width of each. */
        final float[] colX, colW;
        List<Block> header, footer, firstHeader, firstFooter, evenHeader, evenFooter;

        Sec(Section s) {
            section = s;
            width = tw(s.pageWidth);
            height = tw(s.pageHeight);
            left = tw(s.marginLeft);
            top = tw(s.marginTop);
            bottom = height - tw(s.marginBottom);
            contentWidth = width - left - tw(s.marginRight);
            int n = s.columnWidths.length;
            if (n < 2) {
                colX = new float[]{0};
                colW = new float[]{contentWidth};
            } else {
                colX = new float[n];
                colW = new float[n];
                float x = 0;
                for (int i = 0; i < n; i++) {
                    colX[i] = x;
                    colW[i] = Math.max(UNIT * 20, tw(s.columnWidths[i]));
                    x += colW[i] + tw(s.columnGaps[i]);
                }
            }
        }
    }

    // ================================================================== items

    abstract static class Item {
        float top, bottom;

        abstract void draw(Canvas c, Painter painter);
    }

    /** Some consecutive lines of a paragraph layout. */
    static final class TextItem extends Item {
        final Layout layout;
        final int first, last;
        final float x;
        final Tail tail;
        /** Justified paragraph: its full lines reach the right edge of the layout. */
        final boolean justified;

        TextItem(Layout layout, int first, int last, float x, float y, Tail tail, boolean justified) {
            this.layout = layout;
            this.first = first;
            this.last = last;
            this.x = x;
            this.justified = justified;
            this.top = y;
            this.bottom = y + layout.getLineBottom(last) - layout.getLineTop(first);
            this.tail = last == layout.getLineCount() - 1 ? tail : null;
        }

        @Override
        void draw(Canvas c, Painter painter) {
            float dy = top - layout.getLineTop(first);
            c.save();
            // Clip to these lines only, so a paragraph split over two pages shows each line once.
            // The paragraph's final line gets some slack for descenders.
            float slack = last == layout.getLineCount() - 1 ? 3 * UNIT : 0;
            c.clipRect(-100000f, top - UNIT, 100000f, bottom + slack);
            c.translate(x, dy);
            drawShades(c);
            layout.draw(c);
            c.restore();
            if (tail != null) tail.draw(c, layout, last, x, dy);
        }

        /** Run shading of these lines: left, top, right, bottom of each piece, and its colour. */
        private float[] shades;
        private int[] shadeColors;

        /**
         * Shaded runs, drawn here rather than with BackgroundColorSpan, which leaves the space of
         * tabs unshaded (black title bars made of a tab-stretched run).
         */
        private void drawShades(Canvas c) {
            if (shades == null) shadeBoxes();
            for (int i = 0; i < shadeColors.length; i++) {
                FILL_PAINT.setColor(shadeColors[i]);
                c.drawRect(shades[4 * i], shades[4 * i + 1], shades[4 * i + 2], shades[4 * i + 3], FILL_PAINT);
            }
        }

        private void shadeBoxes() {
            shades = new float[0];
            shadeColors = new int[0];
            if (!(layout.getText() instanceof Spanned)) return;
            Spanned sp = (Spanned) layout.getText();
            Shade[] all = sp.getSpans(0, sp.length(), Shade.class);
            if (all.length == 0) return;
            List<Float> out = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            int lastLine = layout.getLineCount() - 1;
            for (Shade s : all) {
                int ss = sp.getSpanStart(s), se = sp.getSpanEnd(s);
                for (int line = first; line <= last; line++) {
                    int ls = layout.getLineStart(line), le = layout.getLineEnd(line);
                    int a = Math.max(ss, ls), b = Math.min(se, le);
                    if (a >= b) continue;
                    float x1 = layout.getPrimaryHorizontal(a);
                    // At a line's end the next offset belongs to the next line.
                    float x2 = b < le || line == lastLine ? layout.getPrimaryHorizontal(b) : layout.getLineRight(line);
                    if (b == le && line == lastLine && b == sp.length()) x2 = Math.max(x2, layout.getLineRight(line));
                    out.add(Math.min(x1, x2));
                    out.add((float) layout.getLineTop(line));
                    out.add(Math.max(x1, x2));
                    out.add((float) layout.getLineBottom(line));
                    colors.add(s.color);
                }
            }
            float[] f = new float[out.size()];
            for (int i = 0; i < f.length; i++) f[i] = out.get(i);
            int[] k = new int[colors.size()];
            for (int i = 0; i < k.length; i++) k[i] = colors.get(i);
            shades = f;
            shadeColors = k;
        }
    }

    /**
     * A slice of other items: those between {@code from} and {@code to}, moved down by {@code dy}
     * (part of a table row broken across pages).
     */
    static final class ClipItem extends Item {
        final List<Item> items;
        final float from, to, dy;
        /** Horizontal limits (a table cell's edges); unlimited by default. */
        float left = -100000f, right = 100000f;

        ClipItem(List<Item> items, float from, float to, float dy) {
            this.items = items;
            this.from = from;
            this.to = to;
            this.dy = dy;
            top = from + dy;
            bottom = to + dy;
        }

        @Override
        void draw(Canvas c, Painter painter) {
            c.save();
            c.clipRect(left, top, right, bottom);
            c.translate(0, dy);
            for (Item it : items) {
                if (it.bottom >= from && it.top <= to) it.draw(c, painter);
            }
            c.restore();
        }
    }

    /** Background colour of a run (highlight or shading), painted by {@link TextItem}. */
    static final class Shade {
        final int color;

        Shade(int color) {
            this.color = color;
        }
    }

    /** Text after a right-aligned tab stop (e.g. page numbers in a table of contents). */
    static final class Tail {
        final Layout text;
        /** Tab stop position, relative to the paragraph origin. */
        final float stop;
        final char leader;

        Tail(Layout text, float stop, char leader) {
            this.text = text;
            this.stop = stop;
            this.leader = leader;
        }

        void draw(Canvas c, Layout main, int line, float x, float dy) {
            float baseline = dy + main.getLineBaseline(line);
            float w = text.getLineWidth(0);
            float left = x + stop - w;
            if (leader != 0) {
                Paint p = main.getPaint();
                float from = x + main.getLineRight(line) + p.getTextSize() * 0.3f;
                float to = left - p.getTextSize() * 0.3f;
                float dot = p.measureText(String.valueOf(leader));
                if (dot > 0 && to - from > dot) {
                    int n = (int) ((to - from) / dot);
                    StringBuilder sb = new StringBuilder(n);
                    for (int i = 0; i < n; i++) sb.append(leader);
                    c.drawText(sb.toString(), to - n * dot, baseline, p);
                }
            }
            c.save();
            c.translate(left, baseline - text.getLineBaseline(0));
            text.draw(c);
            c.restore();
        }
    }

    static final class ImageItem extends Item {
        final String part;
        final RectF rect;
        /** Shown part of the picture: fractions cut off left, top, right, bottom; null for all of it. */
        float[] crop;
        /** DocModel.EFFECT_* and opacity of the picture. */
        int effect;
        float alpha = 1f;

        ImageItem(String part, float x, float y, float w, float h) {
            this.part = part;
            rect = new RectF(x, y, x + w, y + h);
            top = y;
            bottom = y + h;
        }

        /** The item for a document picture, with its crop, colour effect and transparency. */
        static ImageItem of(Image img, float x, float y, float w, float h) {
            ImageItem item = new ImageItem(img.part, x, y, w, h);
            item.crop = img.crop;
            item.effect = img.effect;
            item.alpha = img.alpha;
            return item;
        }

        private Paint paint() {
            if (effect == DocModel.EFFECT_NONE && alpha >= 1f) return BITMAP_PAINT;
            Paint p = EFFECT_PAINT;
            p.setAlpha(Math.round(255 * Math.max(0f, Math.min(1f, alpha))));
            p.setColorFilter(effect == DocModel.EFFECT_GRAY ? GRAY_FILTER
                    : effect == DocModel.EFFECT_BILEVEL ? BILEVEL_FILTER : null);
            return p;
        }

        /** Width of the whole picture at this item's scale (larger than the item when cropped). */
        float fullWidth() {
            return crop == null ? rect.width() : rect.width() / Math.max(0.05f, 1 - crop[0] - crop[2]);
        }

        @Override
        void draw(Canvas c, Painter painter) {
            // A spacer: the room an inline group of shapes takes in the text.
            if (part == null) return;
            Bitmap b = painter.image(this);
            if (b != null && crop != null) {
                int w = b.getWidth(), h = b.getHeight();
                android.graphics.Rect src = new android.graphics.Rect(Math.round(w * crop[0]), Math.round(h * crop[1]),
                        Math.round(w * (1 - crop[2])), Math.round(h * (1 - crop[3])));
                c.drawBitmap(b, src, rect, paint());
            } else if (b != null) {
                c.drawBitmap(b, null, rect, paint());
            } else {
                c.drawRect(rect, PLACEHOLDER_PAINT);
            }
        }
    }

    static final class RectItem extends Item {
        final RectF rect;
        final int color;

        RectItem(float l, float t, float r, float b, int color) {
            rect = new RectF(l, t, r, b);
            this.color = color;
            top = t;
            bottom = b;
        }

        @Override
        void draw(Canvas c, Painter painter) {
            FILL_PAINT.setColor(color);
            c.drawRect(rect, FILL_PAINT);
        }
    }

    static final class LineItem extends Item {
        final float x1, y1, x2, y2, width;
        final int color;

        LineItem(float x1, float y1, float x2, float y2, float width, int color) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.width = width;
            this.color = color;
            top = Math.min(y1, y2) - width;
            bottom = Math.max(y1, y2) + width;
        }

        /** Dash intervals in layout units, or null for a solid line. */
        float[] dash;
        private android.graphics.DashPathEffect effect;

        @Override
        void draw(Canvas c, Painter painter) {
            LINE_PAINT.setColor(color);
            // At least one screen pixel, or thin borders vanish when zoomed out.
            LINE_PAINT.setStrokeWidth(Math.max(width, 1f / painter.scale()));
            if (dash != null) {
                if (effect == null) effect = new android.graphics.DashPathEffect(dash, 0);
                LINE_PAINT.setPathEffect(effect);
                LINE_PAINT.setStrokeCap(Paint.Cap.BUTT);
            }
            if (dash == null) {
                c.drawLine(x1, y1, x2, y2, LINE_PAINT);
            } else {
                // A path: dashed drawLine is not drawn by every canvas.
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x1, y1);
                path.lineTo(x2, y2);
                LINE_PAINT.setStyle(Paint.Style.STROKE);
                c.drawPath(path, LINE_PAINT);
                LINE_PAINT.setPathEffect(null);
                LINE_PAINT.setStrokeCap(Paint.Cap.SQUARE);
            }
        }
    }

    /** A filled or outlined rectangle, rounded rectangle or ellipse (slide shapes). */
    static final class ShapeItem extends Item {
        final RectF rect;
        final int geometry, color;
        /** Outline width, or 0 to fill. */
        final float stroke;
        /** For GEOM_PATH, see {@link TextBox#path}. */
        float[] path;
        boolean flipH, flipV;
        float rotation;
        /** Gradient fill instead of {@link #color}, or null. */
        DocModel.Gradient gradient;
        /** Dash intervals of the outline, or null. */
        float[] dash;
        private android.graphics.Shader shader;

        private android.graphics.Shader shader() {
            if (shader == null) shader = DrawingML.shader(gradient, rect);
            return shader;
        }

        ShapeItem(float l, float t, float r, float b, int geometry, int color, float stroke) {
            rect = new RectF(l, t, r, b);
            this.geometry = geometry;
            this.color = color;
            this.stroke = stroke;
            top = t - stroke;
            bottom = b + stroke;
        }

        void rotate(float degrees) {
            rotation = degrees;
            if (degrees == 0) return;
            // A turned shape may reach past its box by up to half the longer side.
            float reach = Math.max(rect.width(), rect.height()) / 2f;
            top = rect.centerY() - reach - stroke;
            bottom = rect.centerY() + reach + stroke;
        }

        @Override
        void draw(Canvas c, Painter painter) {
            Paint p = SHAPE_PAINT;
            p.setColor(color);
            p.setStyle(stroke > 0 ? Paint.Style.STROKE : Paint.Style.FILL);
            p.setStrokeWidth(Math.max(stroke, 1f / painter.scale()));
            p.setShader(gradient != null && stroke == 0 ? shader() : null);
            p.setPathEffect(dash != null && stroke > 0 ? new android.graphics.DashPathEffect(dash, 0) : null);
            if (rotation != 0) {
                c.save();
                c.rotate(rotation, rect.centerX(), rect.centerY());
            }
            if (geometry == DocModel.GEOM_PATH && path != null) {
                c.drawPath(toPath(), p);
            } else if (geometry == DocModel.GEOM_ELLIPSE) {
                c.drawOval(rect, p);
            } else if (geometry == DocModel.GEOM_ROUND_RECT) {
                // PowerPoint's default corner: a sixth of the shorter side.
                float r = Math.min(rect.width(), rect.height()) / 6f;
                c.drawRoundRect(rect, r, r, p);
            } else {
                c.drawRect(rect, p);
            }
            if (rotation != 0) c.restore();
            p.setShader(null);
            p.setPathEffect(null);
        }

        private android.graphics.Path built;

        /** The outline scaled into the box, mirrored as flipped. */
        private android.graphics.Path toPath() {
            if (built != null) return built;
            android.graphics.Path out = new android.graphics.Path();
            float l = rect.left, t = rect.top, w = rect.width(), h = rect.height();
            float[] a = path;
            int i = 0;
            while (i < a.length) {
                int op = (int) a[i++];
                int n = op == DrawingML.CUBIC ? 3 : op == DrawingML.QUAD ? 2 : op == DrawingML.CLOSE ? 0 : 1;
                if (i + 2 * n > a.length) break;
                float[] pt = new float[2 * n];
                for (int k = 0; k < n; k++) {
                    float fx = a[i++], fy = a[i++];
                    pt[2 * k] = l + (flipH ? 1 - fx : fx) * w;
                    pt[2 * k + 1] = t + (flipV ? 1 - fy : fy) * h;
                }
                switch (op) {
                    case DrawingML.MOVE:
                        out.moveTo(pt[0], pt[1]);
                        break;
                    case DrawingML.LINE:
                        out.lineTo(pt[0], pt[1]);
                        break;
                    case DrawingML.CUBIC:
                        out.cubicTo(pt[0], pt[1], pt[2], pt[3], pt[4], pt[5]);
                        break;
                    case DrawingML.QUAD:
                        out.quadTo(pt[0], pt[1], pt[2], pt[3]);
                        break;
                    default:
                        out.close();
                }
            }
            built = out;
            return out;
        }
    }

    // Items are only drawn on the main thread.
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint EFFECT_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final android.graphics.ColorMatrixColorFilter GRAY_FILTER, BILEVEL_FILTER;

    static {
        android.graphics.ColorMatrix gray = new android.graphics.ColorMatrix();
        gray.setSaturation(0);
        GRAY_FILTER = new android.graphics.ColorMatrixColorFilter(gray);
        // Black and white at half brightness: luminance scaled steeply around 50%.
        float k = 40f, off = -128f * k + 128f;
        android.graphics.ColorMatrix bw = new android.graphics.ColorMatrix(new float[]{
                0.299f * k, 0.587f * k, 0.114f * k, 0, off,
                0.299f * k, 0.587f * k, 0.114f * k, 0, off,
                0.299f * k, 0.587f * k, 0.114f * k, 0, off,
                0, 0, 0, 1, 0});
        BILEVEL_FILTER = new android.graphics.ColorMatrixColorFilter(bw);
    }

    private static final Paint SHAPE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint PLACEHOLDER_PAINT = new Paint();
    private static final Paint FILL_PAINT = new Paint();
    private static final Paint LINE_PAINT = new Paint();

    static {
        PLACEHOLDER_PAINT.setColor(0xFFF0F0F0);
        LINE_PAINT.setStrokeCap(Paint.Cap.SQUARE);
    }

    // ================================================================== fonts

    static final class Fonts {
        private static final Typeface[] FAMILIES = {Typeface.SANS_SERIF, Typeface.SERIF, Typeface.MONOSPACE};
        private static final Typeface[][] FACES = new Typeface[3][4];

        private Fonts() {
        }

        static int family(String name) {
            if (name == null) return 0;
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("courier") || n.contains("mono") || n.contains("consolas") || n.contains("console")) {
                return 2;
            }
            if (n.contains("sans") || n.contains("gothic")) return 0;
            if (n.contains("calisto") || n.contains("century") || n.contains("goudy") || n.contains("bell mt")
                    || n.contains("californian") || n.contains("lucida bright") || n.contains("high tower")
                    || n.contains("elephant") || n.contains("modern no") || n.contains("sylfaen")
                    || n.contains("trajan") || n.contains("centaur") || n.contains("bembo")) {
                return 1;
            }
            if (n.contains("times") || n.contains("cambria") || n.contains("georgia") || n.contains("garamond")
                    || n.contains("book") || n.contains("palatino") || n.contains("serif")
                    || n.contains("schoolbook") || n.contains("minion") || n.contains("constantia")
                    || n.contains("playfair") || n.contains("bodoni") || n.contains("didot") || n.contains("baskerville")
                    || n.contains("lora") || n.contains("merriweather") || n.contains("rockwell") || n.contains("cormorant")
                    || n.contains("century schoolbook") || n.contains("bookman") || n.contains("perpetua")) {
                return 1;
            }
            return 0;
        }

        /** True when {@code face} has no real bold, so bold text must be faked. */
        static boolean fakeBold(Typeface face, boolean bold) {
            return bold && (face.getStyle() & Typeface.BOLD) == 0 && !FontDownloads.isBold(face);
        }

        static synchronized Typeface get(String name, boolean bold, boolean italic) {
            // A downloaded font with the original's metrics (Carlito for Calibri...) when there is one.
            Typeface exact = FontDownloads.get(name, bold, italic);
            if (exact != null) return exact;
            int f = family(name);
            int style = bold && italic ? Typeface.BOLD_ITALIC : bold ? Typeface.BOLD
                    : italic ? Typeface.ITALIC : Typeface.NORMAL;
            Typeface t = FACES[f][style];
            if (t == null) {
                t = Typeface.create(FAMILIES[f], style);
                FACES[f][style] = t;
            }
            return t;
        }

        /**
         * Sample whose width (in em) was measured in the Windows fonts; see {@link #wordWidth}.
         * Mostly lowercase like body text: capitals and digits have different width ratios.
         */
        private static final String SAMPLE = "Pengoperasian pompa pada sebagian rumah kos masih dilakukan secara "
                + "manual dengan memantau ketinggian air. The quick brown fox jumps over the lazy dog, 2026.";
        private static final java.util.Map<String, Float> SCALES = new java.util.HashMap<>();

        /** Forgets measured scales: another font has arrived (see {@link FontDownloads}). */
        static synchronized void reset() {
            SCALES.clear();
        }

        /** Width of {@link #SAMPLE} in em in the original font, or 0 when unknown. */
        static float wordWidth(String name, boolean bold) {
            if (name == null) return 0;
            String n = name.toLowerCase(Locale.ROOT);
            float w;
            float boldFactor = 1.05f;
            if (n.contains("times")) {
                w = 66.758f;
                boldFactor = 1.070f;
            } else if (n.contains("calibri light")) {
                w = 66.701f;
            } else if (n.contains("calibri")) {
                w = 67.543f;
                boldFactor = 1.023f;
            } else if (n.contains("arial") || n.contains("helvetica")) {
                w = 74.378f;
                boldFactor = 1.068f;
            } else if (n.contains("cambria")) {
                w = 70.897f;
            } else if (n.contains("georgia")) {
                w = 73.571f;
            } else if (n.contains("courier")) {
                // Monospace: bold glyphs are as wide as regular ones.
                return 93.615f;
            } else if (n.contains("tahoma")) {
                w = 74.088f;
            } else if (n.contains("verdana")) {
                w = 84.773f;
            } else if (n.contains("segoe")) {
                w = 73.586f;
            } else if (n.contains("book antiqua") || n.contains("palatino")) {
                w = 74.108f;
            } else if (n.contains("century gothic")) {
                w = 81.956f;
            } else if (n.contains("garamond")) {
                w = 64.213f;
            } else if (n.contains("sue ellen") || n.contains("amatic") || n.contains("gochi")
                    || n.contains("indie flower") || n.contains("shadows into")) {
                // Narrow handwriting fonts (Google Slides and Canva templates).
                w = 50f;
            } else if (n.contains("extra condensed") || n.contains("compressed")) {
                w = 54f;
            } else if (n.contains("semi condensed") || n.contains("semicondensed")) {
                w = 66f;
            } else if (n.contains("condensed") || n.contains("narrow") || n.contains("oswald")
                    || n.contains("bebas") || n.contains("league gothic") || n.contains("impact")) {
                w = 60f;
            } else {
                return 0;
            }
            return bold ? w * boldFactor : w;
        }

        /**
         * Horizontal scale that makes the phone font as wide as the document's font, so lines break
         * where they do in Word. Measured on the device, since phones ship different fonts.
         */
        static synchronized float widthScale(String name, boolean bold, boolean italic) {
            float target = wordWidth(name, bold);
            if (target <= 0) return 1f;
            String key = name.toLowerCase(Locale.ROOT) + (bold ? "/b" : "") + (italic ? "/i" : "");
            Float s = SCALES.get(key);
            if (s == null) {
                TextPaint p = new TextPaint(Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
                p.setTypeface(get(name, bold, italic));
                p.setFakeBoldText(fakeBold(p.getTypeface(), bold));
                p.setTextSize(1000);
                float phone = p.measureText(SAMPLE) / 1000f;
                s = phone <= 0 ? 1f : Math.max(0.7f, Math.min(1.3f, target / phone));
                SCALES.put(key, s);
            }
            return s;
        }

        /** Width table of the original font, or null when it is not one of the tabulated fonts. */
        static String widthTable(String name, boolean bold, boolean italic) {
            if (name == null) return null;
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("times")) {
                return bold ? (italic ? WordFontWidths.TIMES_BI : WordFontWidths.TIMES_B)
                        : italic ? WordFontWidths.TIMES_I : WordFontWidths.TIMES;
            }
            if (n.contains("calibri")) {
                if (n.contains("light") && !bold && !italic) return WordFontWidths.CALIBRI_LIGHT;
                return bold ? (italic ? WordFontWidths.CALIBRI_BI : WordFontWidths.CALIBRI_B)
                        : italic ? WordFontWidths.CALIBRI_I : WordFontWidths.CALIBRI;
            }
            if (n.contains("arial") || n.contains("helvetica")) {
                return bold ? (italic ? WordFontWidths.ARIAL_BI : WordFontWidths.ARIAL_B)
                        : italic ? WordFontWidths.ARIAL_I : WordFontWidths.ARIAL;
            }
            if (n.contains("tahoma")) return bold ? WordFontWidths.TAHOMA_B : WordFontWidths.TAHOMA;
            if (n.contains("cambria") && !n.contains("math")) {
                return bold ? (italic ? WordFontWidths.CAMBRIA_BI : WordFontWidths.CAMBRIA_B)
                        : italic ? WordFontWidths.CAMBRIA_I : WordFontWidths.CAMBRIA;
            }
            return null;
        }

        /** Kerning table matching {@link #widthTable}, or null. */
        static String kernTable(String name, boolean bold, boolean italic) {
            String w = widthTable(name, bold, italic);
            if (w == null) return null;
            if (w == WordFontWidths.TIMES) return WordFontWidths.KERN_TIMES;
            if (w == WordFontWidths.TIMES_B) return WordFontWidths.KERN_TIMES_B;
            if (w == WordFontWidths.TIMES_I) return WordFontWidths.KERN_TIMES_I;
            if (w == WordFontWidths.TIMES_BI) return WordFontWidths.KERN_TIMES_BI;
            if (w == WordFontWidths.CALIBRI) return WordFontWidths.KERN_CALIBRI;
            if (w == WordFontWidths.CALIBRI_B) return WordFontWidths.KERN_CALIBRI_B;
            if (w == WordFontWidths.CALIBRI_I) return WordFontWidths.KERN_CALIBRI_I;
            if (w == WordFontWidths.CALIBRI_BI) return WordFontWidths.KERN_CALIBRI_BI;
            if (w == WordFontWidths.CALIBRI_LIGHT) return WordFontWidths.KERN_CALIBRI_LIGHT;
            if (w == WordFontWidths.ARIAL) return WordFontWidths.KERN_ARIAL;
            if (w == WordFontWidths.ARIAL_B) return WordFontWidths.KERN_ARIAL_B;
            if (w == WordFontWidths.ARIAL_I) return WordFontWidths.KERN_ARIAL_I;
            if (w == WordFontWidths.ARIAL_BI) return WordFontWidths.KERN_ARIAL_BI;
            return null;
        }

        private static final java.util.IdentityHashMap<String, android.util.SparseIntArray> KERNS =
                new java.util.IdentityHashMap<>();

        /** Pair adjustments in 1/1000 em, keyed by (left << 8 | right); decoded once per font. */
        private static android.util.SparseIntArray kerns(String table) {
            android.util.SparseIntArray m = KERNS.get(table);
            if (m == null) {
                m = new android.util.SparseIntArray(table.length() / 3);
                for (int i = 0; i + 2 < table.length(); i += 3) {
                    int l = table.charAt(i) - 0x100 + 32, r = table.charAt(i + 1) - 0x100 + 32;
                    m.put(l << 8 | r, table.charAt(i + 2) - 0x4000);
                }
                KERNS.put(table, m);
            }
            return m;
        }

        /**
         * Measures at a large size with subpixel positioning: small, hinted sizes round each glyph to
         * whole pixels, which skewed the width scale by a few percent.
         */
        private static final TextPaint MEASURE = new TextPaint(Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        private static final float MEASURE_SIZE = 1000f;

        /**
         * Width scale for one run of text: its width in the original font (from the tables) over its
         * width in the phone font, so each line holds the same words as in Word. Characters outside
         * the tables count with the font's average scale.
         */
        static synchronized float runScale(String name, boolean bold, boolean italic, CharSequence text,
                                           boolean kern) {
            float avg = widthScale(name, bold, italic);
            String table = widthTable(name, bold, italic);
            if (table == null || text.length() == 0) return avg;
            Typeface face = get(name, bold, italic);
            MEASURE.setTypeface(face);
            MEASURE.setFakeBoldText(fakeBold(face, bold));
            MEASURE.setTextSize(MEASURE_SIZE);
            MEASURE.setTextScaleX(1f);
            MEASURE.setLetterSpacing(0f);
            // The phone width is measured over the whole run, as the layout will measure it.
            StringBuilder visible = new StringBuilder(text.length());
            float word = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c < 32) continue;
                visible.append(c);
                int w = c <= 255 ? table.charAt(c - 32) : 0;
                word += w > 0 ? w / 1000f : MEASURE.measureText(text, i, i + 1) / MEASURE_SIZE * avg;
            }
            float phone = MEASURE.measureText(visible, 0, visible.length()) / MEASURE_SIZE;
            String kt = kern ? kernTable(name, bold, italic) : null;
            if (kt != null) {
                android.util.SparseIntArray pairs = kerns(kt);
                for (int i = 0; i + 1 < text.length(); i++) {
                    char a = text.charAt(i), b = text.charAt(i + 1);
                    if (a < 256 && b < 256) word += pairs.get(a << 8 | b) / 1000f;
                }
            }
            if (phone <= 0) return avg;
            return Math.max(0.6f, Math.min(1.4f, word / phone));
        }

        /** Single line height / font size of the original font. */
        static float wordHeight(String name) {
            if (name == null) return 1.17f;
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("calibri")) return 1.22f;
            // Measured in Word: 12 and 14 pt Times New Roman lines are 1.15 x the font size.
            if (n.contains("times") || n.contains("arial") || n.contains("helvetica")) return 1.15f;
            if (n.contains("cambria")) return 1.172f;
            if (n.contains("courier")) return 1.133f;
            if (n.contains("georgia")) return 1.136f;
            if (n.contains("tahoma")) return 1.207f;
            if (n.contains("verdana")) return 1.215f;
            if (n.contains("segoe")) return 1.33f;
            return 1.17f;
        }
    }

    /**
     * Sets typeface, size (layout units) and width scale; fakes bold where the phone font has no
     * bold.
     */
    static final class FontSpan extends MetricAffectingSpan {
        final Typeface face;
        final float size, scaleX;
        /** Extra space after each character, in em. */
        final float letterSpacing;
        final boolean fakeBold;
        /** Superscript or subscript: does not make the line taller. */
        final boolean script;

        FontSpan(String font, boolean bold, boolean italic, float size, boolean script, CharSequence text) {
            this(font, bold, italic, size, script, text, 0, 100, false);
        }

        FontSpan(String font, boolean bold, boolean italic, float size, boolean script, CharSequence text,
                 int charSpacing, int widthPercent, boolean kern) {
            this.face = Fonts.get(font, bold, italic);
            this.size = size;
            this.script = script;
            this.scaleX = Fonts.runScale(font, bold, italic, text, kern) * widthPercent / 100f;
            this.letterSpacing = size > 0 ? tw(charSpacing) / size : 0;
            this.fakeBold = Fonts.fakeBold(face, bold);
        }

        private void apply(TextPaint p) {
            p.setTypeface(face);
            p.setTextSize(size);
            p.setTextScaleX(scaleX);
            p.setFakeBoldText(fakeBold);
            p.setLetterSpacing(letterSpacing);
        }

        @Override
        public void updateMeasureState(TextPaint p) {
            apply(p);
        }

        @Override
        public void updateDrawState(TextPaint p) {
            apply(p);
        }
    }

    /**
     * Narrows a space in a justified paragraph. Word lets justified lines squeeze their spaces to
     * fit one more word; Android cannot squeeze, so spaces are measured narrower and justification
     * widens them again. 25% was tuned against Word: line counts of 156 of 161 paragraphs in a
     * 48-page thesis then match (147 without squeezing), and none comes out shorter than in Word.
     */
    static final class ShrinkableSpace extends MetricAffectingSpan {
        static final float FACTOR = 0.75f;

        @Override
        public void updateMeasureState(TextPaint p) {
            p.setTextScaleX(p.getTextScaleX() * FACTOR);
        }

        @Override
        public void updateDrawState(TextPaint p) {
            p.setTextScaleX(p.getTextScaleX() * FACTOR);
        }
    }

    /**
     * Sets each line's height from the largest font on that line, as Word does. It must not derive
     * the height from the incoming metrics: StaticLayout reuses them between lines, so any
     * adjustment would compound from line to line.
     */
    static final class LineSpacing implements LineHeightSpan {
        final Paragraph para;
        final String font;
        final boolean bold, italic;
        final float defaultSize;
        private float lastSize = -1;
        private int[] lastBox;

        LineSpacing(Paragraph para, String font, boolean bold, boolean italic, float defaultSize) {
            this.para = para;
            this.font = font;
            this.bold = bold;
            this.italic = italic;
            this.defaultSize = defaultSize;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int lineHeight,
                                 Paint.FontMetricsInt fm) {
            float size = 0;
            if (text instanceof Spanned) {
                Spanned sp = (Spanned) text;
                for (FontSpan f : sp.getSpans(start, end, FontSpan.class)) {
                    // getSpans also returns spans that only touch the line's ends.
                    if (f.script || sp.getSpanEnd(f) <= start || sp.getSpanStart(f) >= end) continue;
                    size = Math.max(size, f.size);
                }
            }
            if (size <= 0) size = defaultSize;
            if (size != lastSize) {
                lastBox = lineBox(para, font, bold, italic, size);
                lastSize = size;
            }
            fm.ascent = -lastBox[0];
            fm.descent = lastBox[1] - lastBox[0];
            if (para.lineRule != DocModel.LINE_EXACT && text instanceof Spanned) {
                // A picture in the line stands on the baseline and makes the line taller.
                Spanned sp = (Spanned) text;
                for (InlinePicture ip : sp.getSpans(start, end, InlinePicture.class)) {
                    if (sp.getSpanStart(ip) < start || sp.getSpanStart(ip) >= end) continue;
                    int h = Math.round(ip.h);
                    if (h > -fm.ascent) fm.ascent = -h;
                }
            }
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
    }

    /** A space of a set width (the gap after a list number). */
    static final class FixedSpace extends android.text.style.ReplacementSpan {
        final float w;

        FixedSpace(float w) {
            this.w = w;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return Math.round(w);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
        }
    }

    /** Room for a picture inside a line of text; the picture itself is drawn as its own item. */
    static final class InlinePicture extends android.text.style.ReplacementSpan {
        final Image image;
        final float w, h;
        int offset;

        InlinePicture(Image image, float w, float h) {
            this.image = image;
            this.w = w;
            this.h = h;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) {
                int asc = -Math.round(h);
                if (asc < fm.ascent) {
                    fm.ascent = asc;
                    fm.top = asc;
                }
            }
            return Math.round(w);
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                         int bottom, Paint paint) {
        }
    }

    /** Pictures inside the lines {@code first..last} of a paragraph drawn at {@code x, top}. */
    static void addInlinePictures(ParaBox box, int first, int last, float x, float top, List<Item> out) {
        if (box.pictures == null) return;
        Layout lay = box.layout;
        for (InlinePicture ip : box.pictures) {
            int line = lay.getLineForOffset(ip.offset);
            if (line < first || line > last) continue;
            float px = x + lay.getPrimaryHorizontal(ip.offset);
            if (lay.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) px -= ip.w;
            float py = top + lay.getLineBaseline(line) - lay.getLineTop(first) - ip.h;
            out.add(ImageItem.of(ip.image, px, py, ip.w, ip.h));
        }
    }

    /**
     * Line box for Word spacing: a multiple of the font's single line, exactly, or at least a
     * height. Returns {ascent, height} in layout units.
     */
    static int[] lineBox(Paragraph p, String font, boolean bold, boolean italic, float size) {
        TextPaint tp = new TextPaint();
        tp.setTypeface(Fonts.get(font, bold, italic));
        tp.setTextSize(size);
        Paint.FontMetrics fm = tp.getFontMetrics();
        float natural = fm.descent - fm.ascent;
        float single = size * Fonts.wordHeight(font);
        float target = p.lineRule == DocModel.LINE_AUTO ? single * p.line / 240f
                : p.lineRule == DocModel.LINE_EXACT ? tw(p.line) : Math.max(single, tw(p.line));
        int t = Math.max(1, Math.round(target));
        float asc;
        if (p.lineRule == DocModel.LINE_EXACT) {
            // Word puts the baseline at about 80% of an exact line.
            asc = t * 0.8f;
        } else if (t < natural) {
            // Shrinking takes mostly from the (generous) space above the glyphs.
            asc = -fm.ascent - (natural - t) * 0.7f;
        } else {
            // Extra space goes below the text.
            asc = -fm.ascent;
        }
        return new int[]{Math.max(1, Math.round(asc)), t};
    }

    // ============================================================= paragraphs

    /** A laid-out paragraph, not yet placed on a page. */
    static final class ParaBox {
        final Paragraph para;
        /** Null for an empty paragraph, which only takes vertical space. */
        Layout layout;
        float emptyHeight;
        /** Horizontal offset from the content edge (negative indents reach into the margin). */
        float offsetX;
        Tail tail;
        /** Width it was laid out for. */
        float width;
        /** Border lines drawn for this paragraph (null for none) and the room they take above and below. */
        Border bTop, bBottom, bLeft, bRight;
        float padTop, padBottom;
        /** Pictures inside the text, or null. */
        List<InlinePicture> pictures;
        /** What the layout was built from, to build it again around a wrapped picture. */
        CharSequence text;
        TextPaint paint;
        int layoutWidth, firstMargin, restMargin;
        /** Laid out justified (a justified paragraph without tabs). */
        boolean justify;
        Layout.Alignment align;

        ParaBox(Paragraph para) {
            this.para = para;
        }

        boolean hasBorders() {
            return bTop != null || bBottom != null || bLeft != null || bRight != null;
        }

        float height() {
            return layout == null ? emptyHeight : layout.getHeight();
        }
    }

    // ======================================================= paragraph borders

    /** Thickness of a border line in layout units (double lines count both and the gap). */
    static float borderThickness(Border b) {
        float w = b.size / 8f * UNIT;
        if (b.style == DocModel.STROKE_DOUBLE) return w * 3;
        if (b.style == DocModel.STROKE_THIN_THICK || b.style == DocModel.STROKE_THICK_THIN) return w * 1.9f;
        return w;
    }

    private static boolean sameBorders(Block a, Block b) {
        if (!(a instanceof Paragraph) || !(b instanceof Paragraph)) return false;
        Paragraph p = (Paragraph) a, q = (Paragraph) b;
        if (p.borders == null || q.borders == null || p.indLeft != q.indLeft || p.indRight != q.indRight) return false;
        for (int i = 0; i < 4; i++) {
            Border x = p.borders[i], y = q.borders[i];
            boolean xv = x != null && x.visible(), yv = y != null && y.visible();
            if (xv != yv) return false;
            if (xv && (x.size != y.size || x.color != y.color || x.style != y.style)) return false;
        }
        return true;
    }

    private static Border visible(Border b) {
        return b != null && b.visible() ? b : null;
    }

    /**
     * Which border lines a paragraph draws: paragraphs with the same borders form one box, with
     * the top line on the first, the bottom on the last and "between" lines in between.
     */
    static void resolveBorders(ParaBox box, Block prev, Block next) {
        Paragraph p = box.para;
        box.bTop = box.bBottom = box.bLeft = box.bRight = null;
        box.padTop = box.padBottom = 0;
        if (p.borders == null) return;
        boolean joinPrev = sameBorders(prev, p), joinNext = sameBorders(p, next);
        box.bTop = joinPrev ? null : visible(p.borders[DocModel.B_TOP]);
        box.bBottom = visible(p.borders[joinNext ? DocModel.B_BETWEEN : DocModel.B_BOTTOM]);
        box.bLeft = visible(p.borders[DocModel.B_LEFT]);
        box.bRight = visible(p.borders[DocModel.B_RIGHT]);
        if (box.bTop != null) box.padTop = borderThickness(box.bTop) + box.bTop.space * UNIT;
        if (box.bBottom != null) box.padBottom = borderThickness(box.bBottom) + box.bBottom.space * UNIT;
    }

    /** Horizontal extent of a paragraph's borders: its indents, widened by side borders' spacing. */
    private static float[] borderSpan(ParaBox box, float x, float width) {
        Paragraph p = box.para;
        float l = x + tw(Math.min(p.indLeft, p.indLeft + Math.min(0, p.firstLine)));
        float r = x + width - tw(p.indRight);
        if (box.bLeft != null) l -= box.bLeft.space * UNIT + borderThickness(box.bLeft) / 2f;
        if (box.bRight != null) r += box.bRight.space * UNIT + borderThickness(box.bRight) / 2f;
        return new float[]{l, r};
    }

    /** The top line of a paragraph whose border box starts at {@code top}. */
    static void addTopBorder(ParaBox box, float x, float width, float top, List<Item> out) {
        if (box.bTop == null) return;
        float[] s = borderSpan(box, x, width);
        float t = borderThickness(box.bTop);
        stroke(out, box.bTop, s[0], top + t / 2f, s[1], top + t / 2f, false);
    }

    /** Bottom and side lines of a paragraph's border box from {@code top} to {@code bottom}. */
    static void addOtherBorders(ParaBox box, float x, float width, float top, float bottom, List<Item> out) {
        float[] s = borderSpan(box, x, width);
        if (box.bBottom != null) {
            float t = borderThickness(box.bBottom);
            stroke(out, box.bBottom, s[0], bottom - t / 2f, s[1], bottom - t / 2f, false);
        }
        if (box.bLeft != null) stroke(out, box.bLeft, s[0], top, s[0], bottom, true);
        if (box.bRight != null) stroke(out, box.bRight, s[1], top, s[1], bottom, true);
    }

    /** A border line in its style; {@code vertical} tells which way double lines are spread. */
    static void stroke(List<Item> out, Border b, float x1, float y1, float x2, float y2, boolean vertical) {
        float w = b.size / 8f * UNIT;
        float dx = vertical ? 1 : 0, dy = vertical ? 0 : 1;
        switch (b.style) {
            case DocModel.STROKE_DOUBLE:
                out.add(new LineItem(x1 - dx * w, y1 - dy * w, x2 - dx * w, y2 - dy * w, w, b.color));
                out.add(new LineItem(x1 + dx * w, y1 + dy * w, x2 + dx * w, y2 + dy * w, w, b.color));
                break;
            case DocModel.STROKE_THIN_THICK:
            case DocModel.STROKE_THICK_THIN: {
                float thin = Math.max(0.5f * UNIT, w / 3f), gap = w * 0.45f;
                float total = w + gap + thin;
                // Thin line first (above / left) for thinThick, last for thickThin.
                boolean thinFirst = b.style == DocModel.STROKE_THIN_THICK;
                float a = -total / 2f, thinC = thinFirst ? a + thin / 2f : a + total - thin / 2f;
                float thickC = thinFirst ? a + total - w / 2f : a + w / 2f;
                out.add(new LineItem(x1 + dx * thinC, y1 + dy * thinC, x2 + dx * thinC, y2 + dy * thinC, thin, b.color));
                out.add(new LineItem(x1 + dx * thickC, y1 + dy * thickC, x2 + dx * thickC, y2 + dy * thickC, w, b.color));
                break;
            }
            default: {
                LineItem li = new LineItem(x1, y1, x2, y2, w, b.color);
                li.dash = dashFor(b.style, w);
                out.add(li);
            }
        }
    }

    /** Dash intervals for a line style, or null for a solid line. */
    static float[] dashFor(int style, float w) {
        float u = Math.max(w, 0.75f * UNIT);
        switch (style) {
            case DocModel.STROKE_DASH:
                return new float[]{4 * u, 3 * u};
            case DocModel.STROKE_DOT:
                return new float[]{u, 2 * u};
            case DocModel.STROKE_DASH_DOT:
                return new float[]{4 * u, 2 * u, u, 2 * u};
            default:
                return null;
        }
    }

    private float sizeOf(int halfPoints) {
        return (halfPoints > 0 ? halfPoints : doc.defaultHalfPoints) / 2f * UNIT;
    }

    private static float singleLine(Paragraph p, String font, float sizePx) {
        float single = sizePx * Fonts.wordHeight(font);
        switch (p.lineRule) {
            case DocModel.LINE_EXACT:
                return tw(p.line);
            case DocModel.LINE_AT_LEAST:
                return Math.max(single, tw(p.line));
            default:
                return single * p.line / 240f;
        }
    }

    ParaBox layoutParagraph(Paragraph p, float width, String pageNumber) {
        ParaBox box = new ParaBox(p);
        box.width = width;
        float markSize = sizeOf(p.markHalfPoints);
        if (p.isEmpty()) {
            box.emptyHeight = singleLine(p, p.markFont, markSize);
            return box;
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();
        String mainFont = p.markFont;
        boolean mainBold = false, mainItalic = false;
        float mainSize = 0, maxSize = 0;
        if (p.listPrefix != null) {
            int s = sb.length();
            float listSize = p.listHalfPoints > 0 ? sizeOf(p.listHalfPoints) : markSize;
            FontSpan listFont = new FontSpan(p.markFont, false, false, listSize, false, p.listPrefix);
            if ("\t".equals(p.listSuffix)) {
                // The tab after the number, worked out as Word does (next stop, or the hanging
                // indent), and set as a fixed space: phones' tab handling put the text elsewhere.
                TextPaint mp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
                listFont.updateMeasureState(mp);
                float x = tw(p.indLeft + p.firstLine) + mp.measureText(p.listPrefix);
                float pos = -1;
                for (int t : p.tabs) {
                    if (tw(t) > x + 0.5f) {
                        pos = tw(t);
                        break;
                    }
                }
                float hanging = tw(p.indLeft);
                if (p.firstLine < 0 && hanging > x + 0.5f && (pos < 0 || hanging < pos)) pos = hanging;
                if (pos < 0) {
                    float step = tw(Math.max(1, doc.defaultTab));
                    pos = (float) Math.floor(x / step + 1) * step;
                }
                sb.append(p.listPrefix).append(' ');
                sb.setSpan(listFont, s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new FixedSpace(Math.max(UNIT, pos - x)), sb.length() - 1, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(p.listPrefix).append(p.listSuffix);
                sb.setSpan(listFont, s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (p.listColor != 0) {
                sb.setSpan(new ForegroundColorSpan(p.listColor), s, s + p.listPrefix.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        for (Run r : p.runs) {
            CharSequence text = r.pageNumber ? (pageNumber == null ? "" : pageNumber) : r.text;
            if (r.caps || r.smallCaps) text = text.toString().toUpperCase(Locale.getDefault());
            if (text.length() == 0) continue;
            int start = sb.length();
            if (r.image != null) {
                float[] s = imageSize(r.image, width, 100000f);
                if (s == null) continue;
                sb.append('￼');
                InlinePicture ip = new InlinePicture(r.image, s[0], s[1]);
                ip.offset = start;
                sb.setSpan(ip, start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (box.pictures == null) box.pictures = new ArrayList<>();
                box.pictures.add(ip);
                continue;
            }
            sb.append(text);
            int end = sb.length();
            float size = sizeOf(r.halfPoints);
            if (r.vertAlign == DocModel.VALIGN_BASELINE) maxSize = Math.max(maxSize, size);
            if (mainSize == 0 && r.text.toString().trim().length() > 0) {
                mainFont = r.font;
                mainBold = r.bold;
                mainItalic = r.italic;
                mainSize = size;
            }
            if (r.smallCaps) size *= 0.8f;
            if (r.vertAlign != DocModel.VALIGN_BASELINE) size *= 0.65f;
            int flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            // Each piece between tabs gets its own width scale: where a tab lands depends on
            // the width of the text before it alone.
            int segStart = start;
            for (int k = start; k <= end; k++) {
                if (k < end && sb.charAt(k) != '\t') continue;
                int segEnd = k < end ? k + 1 : end;
                if (segEnd > segStart) {
                    sb.setSpan(new FontSpan(r.font, r.bold, r.italic, size, r.vertAlign != DocModel.VALIGN_BASELINE,
                            sb.subSequence(segStart, segEnd), r.charSpacing, r.widthPercent, r.kern),
                            segStart, segEnd, flags);
                }
                segStart = segEnd;
            }
            if (r.color != 0) sb.setSpan(new ForegroundColorSpan(r.color), start, end, flags);
            if (r.background != 0) sb.setSpan(new Shade(r.background), start, end, flags);
            if (r.underline) sb.setSpan(new UnderlineSpan(), start, end, flags);
            if (r.strike) sb.setSpan(new StrikethroughSpan(), start, end, flags);
            if (r.vertAlign == DocModel.VALIGN_SUPER) sb.setSpan(new SuperscriptSpan(), start, end, flags);
            else if (r.vertAlign == DocModel.VALIGN_SUB) sb.setSpan(new SubscriptSpan(), start, end, flags);
        }
        if (sb.length() == 0) {
            box.emptyHeight = singleLine(p, p.markFont, markSize);
            return box;
        }

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.LINEAR_TEXT_FLAG);
        paint.setColor(0xFF000000);
        paint.setTypeface(Fonts.get(p.markFont, false, false));
        paint.setTextSize(markSize);
        paint.setTextScaleX(Fonts.widthScale(p.markFont, false, false));

        // Indents; negative ones reach into the page margin.
        float left = tw(p.indLeft);
        float first = tw(p.indLeft + p.firstLine);
        float origin = Math.min(0, Math.min(left, first));
        box.offsetX = origin;
        float layoutWidth = Math.max(UNIT * 10, width - origin - tw(p.indRight));
        int firstMargin = Math.round(Math.min(first - origin, layoutWidth - UNIT * 10));
        int restMargin = Math.round(Math.min(left - origin, layoutWidth - UNIT * 10));

        // Text after a right-aligned tab stop is placed separately, flush with the stop.
        int tabIndex = rightTabIndex(p);
        int lastTab = tabIndex >= 0 ? lastIndexOf(sb, '\t') : -1;
        // Only when the last tab really reaches that stop (not an earlier one).
        if (lastTab >= 0 && lastTabStop(sb, p, origin + firstMargin, paint) != tabIndex) lastTab = -1;
        if (lastTab >= 0 && indexOf(sb, '\n') < 0) {
            CharSequence tailText = sb.subSequence(lastTab + 1, sb.length());
            float stop = tw(p.tabs[tabIndex]) - origin;
            Layout tail = build(tailText, paint, Math.max(1, (int) Math.ceil(Layout.getDesiredWidth(tailText, paint))),
                    Layout.Alignment.ALIGN_NORMAL, false);
            float tailW = tail.getLineWidth(0);
            sb.delete(lastTab, sb.length());
            box.tail = new Tail(tail, stop, p.tabLeaders[tabIndex]);
            layoutWidth = Math.max(UNIT * 10, Math.min(layoutWidth, stop - tailW - UNIT * 4));
            firstMargin = Math.min(firstMargin, (int) layoutWidth - 1);
            restMargin = Math.min(restMargin, (int) layoutWidth - 1);
        }
        if (sb.length() == 0) sb.append(' ');

        int len = sb.length();
        int nl = indexOf(sb, '\n');
        // A manual line break is not a new paragraph in Word: only the first segment gets the first-line indent.
        int firstEnd = nl < 0 ? len : nl + 1;
        sb.setSpan(new LeadingMarginSpan.Standard(Math.max(0, firstMargin), Math.max(0, restMargin)),
                0, firstEnd, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        if (firstEnd < len) {
            sb.setSpan(new LeadingMarginSpan.Standard(Math.max(0, restMargin)), firstEnd, len,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        sb.setSpan(new LineSpacing(p, mainFont, mainBold, mainItalic, maxSize > 0 ? maxSize : markSize),
                0, len, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        addTabStops(sb, p, origin, firstMargin, layoutWidth, paint);
        // Android's justification misplaces text after tabs (words land on the next line), and
        // Word and PowerPoint stretch no tabbed text either: such paragraphs stay ragged.
        box.justify = p.align == DocModel.ALIGN_JUSTIFY && indexOf(sb, '\t') < 0;
        if (box.justify && Build.VERSION.SDK_INT >= 26) {
            // Set after the font spans, so it scales the width they set.
            for (int i = 0; i < len; i++) {
                if (sb.charAt(i) == ' ') {
                    sb.setSpan(new ShrinkableSpace(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        Layout.Alignment align = p.align == DocModel.ALIGN_CENTER ? Layout.Alignment.ALIGN_CENTER
                : p.align == DocModel.ALIGN_END ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        box.layout = build(sb, paint, (int) layoutWidth, align, box.justify);
        box.text = sb;
        box.firstMargin = firstMargin;
        box.restMargin = restMargin;
        box.paint = paint;
        box.layoutWidth = (int) layoutWidth;
        box.align = align;
        return box;
    }

    private static int rightTabIndex(Paragraph p) {
        for (int i = p.tabs.length - 1; i >= 0; i--) {
            if (p.tabAligns[i] == DocModel.TAB_RIGHT) return i;
        }
        return -1;
    }

    /** Tab stops relative to the first line's text start; Android only knows left-aligned stops. */
    private void addTabStops(SpannableStringBuilder sb, Paragraph p, float origin, int firstMargin, float width,
                             TextPaint paint) {
        if (indexOf(sb, '\t') < 0) return;
        float start = origin + firstMargin;
        if (indexOf(sb, '\n') < 0 && hasAlignedTab(p)) {
            addResolvedTabStops(sb, p, start, paint);
            return;
        }
        List<Float> stops = new ArrayList<>();
        float lastCustom = start;
        for (int pos : p.tabs) {
            float x = tw(pos) - start;
            if (x > 0) {
                stops.add(x);
                lastCustom = Math.max(lastCustom, tw(pos));
            }
        }
        if (p.firstLine < 0) {
            // A hanging indent acts as a tab stop (list markers).
            float x = tw(p.indLeft) - start;
            if (x > 0 && !stops.contains(x)) stops.add(x);
        }
        float step = tw(Math.max(1, doc.defaultTab));
        for (float x = (float) Math.floor(lastCustom / step + 1) * step; x - start < width + step; x += step) {
            if (x - start > 0) stops.add(x - start);
        }
        for (float x : stops) {
            sb.setSpan(new TabStopSpan.Standard(Math.round(x)), 0, sb.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
    }

    /**
     * Index in {@code p.tabs} of the stop the paragraph's last tab goes to, following Word's
     * stops from the text start; -1 for a default stop or the hanging indent.
     */
    private int lastTabStop(CharSequence sb, Paragraph p, float start, TextPaint paint) {
        float step = tw(Math.max(1, doc.defaultTab));
        int len = sb.length();
        int tab = indexOf(sb, '\t');
        float x = start + Layout.getDesiredWidth(sb, 0, Math.max(0, tab), paint);
        int result = -1;
        while (tab >= 0) {
            int next = tab + 1;
            while (next < len && sb.charAt(next) != '\t') next++;
            float w = Layout.getDesiredWidth(sb, tab + 1, next, paint);
            float pos = -1;
            int align = DocModel.TAB_LEFT;
            result = -1;
            for (int k = 0; k < p.tabs.length; k++) {
                if (tw(p.tabs[k]) > x + 0.5f) {
                    pos = tw(p.tabs[k]);
                    align = p.tabAligns[k];
                    result = k;
                    break;
                }
            }
            float hanging = tw(p.indLeft);
            if (p.firstLine < 0 && hanging > x + 0.5f && (pos < 0 || hanging < pos)) {
                pos = hanging;
                align = DocModel.TAB_LEFT;
                result = -1;
            }
            if (pos < 0) pos = (float) Math.floor(x / step + 1) * step;
            float at = align == DocModel.TAB_CENTER ? pos - w / 2f : align == DocModel.TAB_RIGHT ? pos - w : pos;
            x = Math.max(at, x + 1) + w;
            tab = next < len ? next : -1;
        }
        return result;
    }

    private static boolean hasAlignedTab(Paragraph p) {
        for (int a : p.tabAligns) if (a != DocModel.TAB_LEFT) return true;
        return false;
    }

    /**
     * Centre and right tab stops (e.g. signature blocks): works out where Word puts each piece of
     * text after a tab, and turns that into the left-aligned stops Android understands.
     */
    private void addResolvedTabStops(SpannableStringBuilder sb, Paragraph p, float start, TextPaint paint) {
        float step = tw(Math.max(1, doc.defaultTab));
        int len = sb.length();
        int tab = indexOf(sb, '\t');
        float x = start + Layout.getDesiredWidth(sb, 0, tab, paint);
        float lastStop = 0;
        while (tab >= 0) {
            int next = tab + 1;
            while (next < len && sb.charAt(next) != '\t') next++;
            float w = Layout.getDesiredWidth(sb, tab + 1, next, paint);
            float pos = -1;
            int align = DocModel.TAB_LEFT;
            for (int k = 0; k < p.tabs.length; k++) {
                if (tw(p.tabs[k]) > x + 0.5f) {
                    pos = tw(p.tabs[k]);
                    align = p.tabAligns[k];
                    break;
                }
            }
            // A hanging indent is an implicit left stop (list and table-of-contents numbers).
            float hanging = tw(p.indLeft);
            if (p.firstLine < 0 && hanging > x + 0.5f && (pos < 0 || hanging < pos)) {
                pos = hanging;
                align = DocModel.TAB_LEFT;
            }
            if (pos < 0) pos = (float) Math.floor(x / step + 1) * step;
            float at = align == DocModel.TAB_CENTER ? pos - w / 2f : align == DocModel.TAB_RIGHT ? pos - w : pos;
            // Text never moves left of where the previous text ended.
            at = Math.max(at, x + 1);
            float rel = Math.max(at - start, lastStop + 1);
            sb.setSpan(new TabStopSpan.Standard(Math.round(rel)), 0, len, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            lastStop = rel;
            x = start + rel + w;
            tab = next < len ? next : -1;
        }
    }

    private static int indexOf(CharSequence s, char c) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) return i;
        return -1;
    }

    private static int lastIndexOf(CharSequence s, char c) {
        for (int i = s.length() - 1; i >= 0; i--) if (s.charAt(i) == c) return i;
        return -1;
    }

    private static Layout build(CharSequence text, TextPaint paint, int width, Layout.Alignment align,
                                boolean justify) {
        // Simple (greedy) breaking without hyphenation, as Word does.
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
                .setAlignment(align)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        // Justified text needs Android 8; older phones show it left-aligned.
        if (justify && Build.VERSION.SDK_INT >= 26) Api26.justify(b);
        return b.build();
    }

    /** The paragraph laid out again with per-line indents (text flowing beside a picture). */
    static Layout buildIndented(ParaBox box, int[] left, int[] right) {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(box.text, 0, box.text.length(), box.paint,
                        Math.max(1, box.layoutWidth))
                .setAlignment(box.align)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setIndents(left, right);
        if (box.justify && Build.VERSION.SDK_INT >= 26) Api26.justify(b);
        return b.build();
    }

    @TargetApi(26)
    private static final class Api26 {
        static void justify(StaticLayout.Builder b) {
            b.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD);
        }
    }

    // ============================================== flows (cells, headers, footers)

    /** Blocks measured for a fixed width without page breaks. */
    static final class Flow {
        final List<Object> pieces = new ArrayList<>();
        /** Top offset of each piece. */
        final List<Float> offsets = new ArrayList<>();
        float height;
        /** Width the blocks were measured for. */
        float width;
        /** Page number shown by PAGE fields, also in the flow's text boxes; null for none. */
        String pageNumber;
        /** The page's text area, for drawings placed relative to the margin (headers); NaN if unset. */
        float marginTop = Float.NaN, marginBottom = Float.NaN;
    }

    static final class ImgPiece {
        final Image image;
        final float x, w, h;

        ImgPiece(Image image, float x, float w, float h) {
            this.image = image;
            this.x = x;
            this.w = w;
            this.h = h;
        }
    }

    /** A floating picture or text box in a flow; its offset is the top of its paragraph. */
    static final class FloatPiece {
        final DocModel.Drawing drawing;
        final float w, h;

        FloatPiece(DocModel.Drawing drawing, float w, float h) {
            this.drawing = drawing;
            this.w = w;
            this.h = h;
        }
    }

    /**
     * Where a floating drawing goes: relative to the page, to the margins (or column), or to its
     * paragraph. Without a page ({@code sec} null, e.g. inside a table cell) everything is relative
     * to the surrounding area. Returns {x, y}.
     */
    static float[] floatingPosition(DocModel.Drawing d, float w, float h, float areaLeft, float areaWidth,
                                    float paraY, Sec sec) {
        return floatingPosition(d, w, h, areaLeft, areaWidth, paraY, sec, Float.NaN, Float.NaN);
    }

    /**
     * {@code marginTop/marginBottom}: where "relative to the margin" means on this page, when a
     * tall header or footer moved the text (Word measures header and footer drawings from there);
     * NaN for the section's margins.
     */
    static float[] floatingPosition(DocModel.Drawing d, float w, float h, float areaLeft, float areaWidth,
                                    float paraY, Sec sec, float marginTop, float marginBottom) {
        if (d.frameW > 0 || d.frameH > 0) {
            // A part of a group: place the group's frame, then the part within it.
            float fw = d.frameW > 0 ? emu(d.frameW) : w, fh = d.frameH > 0 ? emu(d.frameH) : h;
            float[] at = framePosition(d, fw, fh, areaLeft, areaWidth, paraY, sec, marginTop, marginBottom);
            return new float[]{at[0] + emu(d.offX), at[1] + emu(d.offY)};
        }
        return framePosition(d, w, h, areaLeft, areaWidth, paraY, sec, marginTop, marginBottom);
    }

    private static float[] framePosition(DocModel.Drawing d, float w, float h, float areaLeft, float areaWidth,
                                         float paraY, Sec sec, float marginTop, float marginBottom) {
        float x;
        if (d.hRel == DocModel.REL_PAGE && sec != null) {
            x = d.hAlign >= 0 ? alignX(d.hAlign, sec.width, w) : emu(d.hOffset);
        } else if (d.hRel == DocModel.REL_FAR_MARGIN && sec != null) {
            float from = sec.left + sec.contentWidth;
            x = from + (d.hAlign >= 0 ? alignX(d.hAlign, sec.width - from, w) : emu(d.hOffset));
        } else {
            x = areaLeft + (d.hAlign >= 0 ? alignX(d.hAlign, areaWidth, w) : emu(d.hOffset));
        }
        float y;
        if (d.vRel == DocModel.REL_PAGE && sec != null) {
            y = d.vAlign >= 0 ? alignX(d.vAlign, sec.height, h) : emu(d.vOffset);
        } else if (d.vRel == DocModel.REL_MARGIN && sec != null) {
            float top = Float.isNaN(marginTop) ? sec.top : marginTop;
            float bottom = Float.isNaN(marginBottom) ? sec.bottom : marginBottom;
            y = top + (d.vAlign >= 0 ? alignX(d.vAlign, bottom - top, h) : emu(d.vOffset));
        } else if (d.vRel == DocModel.REL_FAR_MARGIN && sec != null) {
            y = sec.bottom + (d.vAlign >= 0 ? alignX(d.vAlign, sec.height - sec.bottom, h) : emu(d.vOffset));
        } else {
            y = paraY + emu(d.vOffset);
        }
        return new float[]{x, y};
    }

    void emitFloating(DocModel.Drawing d, float x, float y, float w, float h, List<Item> out) {
        emitFloating(d, x, y, w, h, out, null);
    }

    /** {@code pageNumber} fills PAGE fields in a text box (headers often number pages so). */
    void emitFloating(DocModel.Drawing d, float x, float y, float w, float h, List<Item> out, String pageNumber) {
        if (d instanceof Image) {
            out.add(ImageItem.of((Image) d, x, y, w, h));
        } else {
            emitTextBox((TextBox) d, x, y, w, h, out, pageNumber);
        }
    }

    /**
     * A picture and those on the same line after it ({@link Image#beside}), broken into lines that
     * fit {@code width}, as Word wraps a row of pictures. Each entry is {Image, float[] size}.
     */
    static List<List<Object[]>> imageLines(Image img, float width, float maxH) {
        List<Image> all = new ArrayList<>();
        all.add(img);
        all.addAll(img.beside);
        List<List<Object[]>> lines = new ArrayList<>();
        List<Object[]> cur = new ArrayList<>();
        float x = 0;
        for (Image i : all) {
            float[] s = imageSize(i, width, maxH);
            if (s == null) continue;
            if (!cur.isEmpty() && x + s[0] > width + UNIT) {
                lines.add(cur);
                cur = new ArrayList<>();
                x = 0;
            }
            cur.add(new Object[]{i, s});
            x += s[0];
        }
        if (!cur.isEmpty()) lines.add(cur);
        return lines;
    }

    static float lineWidth(List<Object[]> line) {
        float w = 0;
        for (Object[] o : line) w += ((float[]) o[1])[0];
        return w;
    }

    static float lineHeight(List<Object[]> line) {
        float h = 0;
        for (Object[] o : line) h = Math.max(h, ((float[]) o[1])[1]);
        return h;
    }

    /** Image size in layout units fitted into {@code maxW} x {@code maxH}; null when unknown. */
    static float[] imageSize(Image img, float maxW, float maxH) {
        if (img.widthEmu <= 0 || img.heightEmu <= 0) return null;
        float w = emu(img.widthEmu), h = emu(img.heightEmu);
        float f = Math.min(1f, Math.min(maxW / w, maxH / h));
        return new float[]{w * f, h * f};
    }

    /** Size of a picture (fitted) or text box (as given), or null when unknown. */
    static float[] drawingSize(DocModel.Drawing dr, float maxW, float maxH) {
        if (dr instanceof Image) return imageSize((Image) dr, maxW, maxH);
        // A straight line is flat in one direction.
        boolean line = dr instanceof TextBox && ((TextBox) dr).geometry == DocModel.GEOM_LINE;
        if (line ? dr.widthEmu <= 0 && dr.heightEmu <= 0 : dr.widthEmu <= 0 || dr.heightEmu <= 0) return null;
        return new float[]{Math.min(maxW, emu(dr.widthEmu)), Math.min(maxH, emu(dr.heightEmu))};
    }

    /** Word's default text-box padding: 0.1 inch left/right, 0.05 inch top/bottom. */
    private static final float BOX_PAD_X = 7.2f * UNIT, BOX_PAD_Y = 3.6f * UNIT;

    /** A text box: its fill, its own blocks laid out inside the padding, and its border. */
    void emitTextBox(TextBox tb, float x, float y, float w, float h, List<Item> out, String pageNumber) {
        float lw = tb.borderWidthEmu > 0 ? emu(tb.borderWidthEmu) : 0.75f * UNIT;
        if (tb.geometry == DocModel.GEOM_LINE) {
            if (tb.border != 0) {
                float x1 = tb.flipH ? x + w : x, x2 = tb.flipH ? x : x + w;
                float y1 = tb.flipV ? y + h : y, y2 = tb.flipV ? y : y + h;
                LineItem li = new LineItem(x1, y1, x2, y2, lw, tb.border);
                li.dash = dashFor(tb.dash, lw);
                out.add(li);
                if (tb.arrowHead) out.add(arrow(x2, y2, x1, y1, lw, tb.border));
                if (tb.arrowTail) out.add(arrow(x1, y1, x2, y2, lw, tb.border));
            }
            return;
        }
        if (tb.geometry == DocModel.GEOM_PATH || tb.rotation != 0) {
            // Outlines other than boxes, and turned shapes: drawn whole as one path.
            ShapeItem fill = tb.fill != 0 ? new ShapeItem(x, y, x + w, y + h, tb.geometry, tb.fill, 0) : null;
            if (fill != null) fill.gradient = tb.fillGradient;
            ShapeItem line = tb.border != 0 ? new ShapeItem(x, y, x + w, y + h, tb.geometry, tb.border, lw) : null;
            if (line != null) line.dash = dashFor(tb.dash, lw);
            for (ShapeItem s : new ShapeItem[]{fill, line}) {
                if (s == null) continue;
                s.path = tb.path;
                s.flipH = tb.flipH;
                s.flipV = tb.flipV;
                s.rotate(tb.rotation);
                out.add(s);
            }
            if (!tb.blocks.isEmpty() && Math.abs(tb.rotation % 180) < 45) emitBoxText(tb, x, y, w, h, out, pageNumber);
            return;
        }
        if (tb.fill != 0) {
            ShapeItem fill = new ShapeItem(x, y, x + w, y + h, tb.geometry, tb.fill, 0);
            fill.gradient = tb.fillGradient;
            out.add(fill);
        }
        if (!tb.blocks.isEmpty()) emitBoxText(tb, x, y, w, h, out, pageNumber);
        if (tb.border == 0) return;
        float[] dash = dashFor(tb.dash, lw);
        if (tb.geometry != DocModel.GEOM_RECT) {
            ShapeItem si = new ShapeItem(x, y, x + w, y + h, tb.geometry, tb.border, lw);
            si.dash = dash;
            out.add(si);
        } else {
            LineItem[] sides = {new LineItem(x, y, x + w, y, lw, tb.border),
                    new LineItem(x, y + h, x + w, y + h, lw, tb.border),
                    new LineItem(x, y, x, y + h, lw, tb.border),
                    new LineItem(x + w, y, x + w, y + h, lw, tb.border)};
            for (LineItem li : sides) {
                li.dash = dash;
                out.add(li);
            }
        }
    }

    /** A filled arrowhead at (x2, y2) of a line coming from (x1, y1), sized like Word's medium one. */
    static Item arrow(float x1, float y1, float x2, float y2, float lineWidth, int color) {
        float len = (float) Math.hypot(x2 - x1, y2 - y1);
        float size = Math.max(lineWidth * 3, 3 * UNIT);
        if (len < 0.01f) len = 0.01f;
        float ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        float bx = x2 - ux * size, by = y2 - uy * size;
        float half = size / 2f;
        return new PolygonItem(new float[]{x2, y2, bx - uy * half, by + ux * half, bx + uy * half, by - ux * half}, color);
    }

    /** A filled polygon in layout units (arrowheads). */
    static final class PolygonItem extends Item {
        final float[] xy;
        final int color;
        private final android.graphics.Path path = new android.graphics.Path();

        PolygonItem(float[] xy, int color) {
            this.xy = xy;
            this.color = color;
            top = Float.MAX_VALUE;
            bottom = -Float.MAX_VALUE;
            for (int i = 0; i + 1 < xy.length; i += 2) {
                if (i == 0) path.moveTo(xy[i], xy[i + 1]);
                else path.lineTo(xy[i], xy[i + 1]);
                top = Math.min(top, xy[i + 1]);
                bottom = Math.max(bottom, xy[i + 1]);
            }
            path.close();
        }

        @Override
        void draw(Canvas c, Painter painter) {
            SHAPE_PAINT.setColor(color);
            SHAPE_PAINT.setStyle(Paint.Style.FILL);
            c.drawPath(path, SHAPE_PAINT);
        }
    }

    private void emitBoxText(TextBox tb, float x, float y, float w, float h, List<Item> out, String pageNumber) {
        float l = BOX_PAD_X, t = BOX_PAD_Y, r = BOX_PAD_X, b = BOX_PAD_Y;
        if (tb.insets != null) {
            l = emu(tb.insets[0]);
            t = emu(tb.insets[1]);
            r = emu(tb.insets[2]);
            b = emu(tb.insets[3]);
        }
        Flow f = measureFlow(tb.blocks, Math.max(UNIT * 4, w - l - r), pageNumber);
        float ty = y + t;
        if (tb.anchor == DocModel.CELL_CENTER) ty = y + t + (h - t - b - f.height) / 2f;
        else if (tb.anchor == DocModel.CELL_BOTTOM) ty = y + h - b - f.height;
        if (!tb.clipText) {
            emitFlow(f, x + l, ty, out);
            return;
        }
        // Word cuts a text box's text at its bottom edge: lines that start below it are left out.
        List<Item> mine = new ArrayList<>(), kept = new ArrayList<>();
        emitFlow(f, x + l, ty, mine);
        if (f.height > h - t - b + UNIT) {
            // Overflowing text: lines that start below the box are left out, and the one
            // crossing its edge is cut there.
            for (Item it : mine) {
                if (it instanceof TextItem) {
                    TextItem ti = (TextItem) it;
                    int last = ti.first;
                    Layout lay = ti.layout;
                    float dy = ti.top - lay.getLineTop(ti.first);
                    while (last < ti.last && dy + lay.getLineTop(last + 1) < y + h) last++;
                    if (ti.top >= y + h) continue;
                    if (last < ti.last) it = new TextItem(lay, ti.first, last, ti.x, ti.top, null, ti.justified);
                } else if (it.top >= y + h) {
                    continue;
                }
                kept.add(it);
            }
            ClipItem clip = new ClipItem(kept, y, y + h, 0);
            clip.left = x;
            clip.right = x + w;
            out.add(clip);
            return;
        }
        for (Item it : mine) {
            if (it instanceof TextItem) {
                TextItem ti = (TextItem) it;
                int last = ti.first;
                Layout lay = ti.layout;
                float dy = ti.top - lay.getLineTop(ti.first);
                while (last < ti.last && dy + lay.getLineBottom(last + 1) <= y + h + UNIT) last++;
                if (ti.top >= y + h) continue;
                if (last < ti.last) it = new TextItem(lay, ti.first, last, ti.x, ti.top, null, ti.justified);
            } else if (it.top >= y + h) {
                continue;
            }
            out.add(it);
        }
    }

    /**
     * Wide pictures with text wrapped around them leave no room for text beside them, so they are
     * laid out like inline pictures that push the text down.
     */
    static boolean blocksText(Image img, float contentWidth) {
        return img.wrapsText && emu(img.widthEmu) >= contentWidth * 0.6f;
    }

    static float alignX(int align, float avail, float w) {
        if (align == DocModel.ALIGN_CENTER) return (avail - w) / 2f;
        if (align == DocModel.ALIGN_END) return avail - w;
        return 0;
    }

    Flow measureFlow(List<Block> blocks, float width, String pageNumber) {
        Flow f = new Flow();
        f.width = width;
        f.pageNumber = pageNumber;
        float y = 0;
        Paragraph prev = null;
        float prevAfter = 0;
        for (int bi = 0; bi < blocks.size(); bi++) {
            Block b = blocks.get(bi);
            if (b instanceof Paragraph) {
                Paragraph p = (Paragraph) b;
                float before = tw(p.spaceBefore);
                if (sameContext(prev, p)) {
                    y -= prevAfter;
                    before = 0;
                }
                // Larger of the previous space after and this space before, as in Word.
                y += Math.max(0, before - prevAfter);
                ParaBox box = layoutParagraph(p, width, pageNumber);
                resolveBorders(box, bi > 0 ? blocks.get(bi - 1) : null, bi + 1 < blocks.size() ? blocks.get(bi + 1) : null);
                y += box.padTop;
                f.pieces.add(box);
                f.offsets.add(y);
                y += box.height() + box.padBottom;
                prevAfter = tw(p.spaceAfter);
                y += prevAfter;
                prev = p;
            } else if (b instanceof Image) {
                Image img = (Image) b;
                // A floating picture (a page background in a header) may be wider than the text.
                float[] s = imageSize(img, img.floating ? Math.max(width * 4, 100000f) : width, 100000f);
                if (s == null) continue;
                if (img.floating && !blocksText(img, width)) {
                    // Positioned when emitted (it may be relative to the page); takes no space.
                    f.pieces.add(new FloatPiece(img, s[0], s[1]));
                    f.offsets.add(y);
                    continue;
                }
                y += Math.max(0, tw(img.spaceBefore) - prevAfter);
                if (!img.beside.isEmpty() && img.positioned) {
                    // One band of positioned pictures, each at its own x.
                    List<Image> all = new ArrayList<>();
                    all.add(img);
                    all.addAll(img.beside);
                    float band = 0;
                    for (Image i : all) {
                        float[] sz = imageSize(i, width, 100000f);
                        if (sz == null) continue;
                        float ix = i.hRel != DocModel.REL_PAGE ? floatingPosition(i, sz[0], sz[1], 0, width, 0, null)[0]
                                : alignX(i.align, width, sz[0]);
                        f.pieces.add(new ImgPiece(i, ix, sz[0], sz[1]));
                        f.offsets.add(y);
                        band = Math.max(band, sz[1]);
                    }
                    y += band;
                    prev = null;
                    prevAfter = tw(img.spaceAfter);
                    y += prevAfter;
                    continue;
                }
                if (!img.beside.isEmpty()) {
                    // A row of pictures, wrapped into lines.
                    for (List<Object[]> line : imageLines(img, width, 100000f)) {
                        float lx = alignX(img.align, width, lineWidth(line));
                        for (Object[] o : line) {
                            float[] sz = (float[]) o[1];
                            f.pieces.add(new ImgPiece((Image) o[0], lx, sz[0], sz[1]));
                            f.offsets.add(y);
                            lx += sz[0];
                        }
                        y += lineHeight(line);
                    }
                    prev = null;
                    prevAfter = tw(img.spaceAfter);
                    y += prevAfter;
                    continue;
                }
                float ix = img.positioned && img.hRel != DocModel.REL_PAGE
                        ? floatingPosition(img, s[0], s[1], 0, width, 0, null)[0] : alignX(img.align, width, s[0]);
                f.pieces.add(new ImgPiece(img, ix, s[0], s[1]));
                f.offsets.add(y);
                y += s[1];
                prev = null;
                prevAfter = tw(img.spaceAfter);
                y += prevAfter;
            } else if (b instanceof Table) {
                TableBox t = measureTable((Table) b, width, pageNumber);
                f.pieces.add(t);
                f.offsets.add(y);
                y += t.height();
                prev = null;
                prevAfter = 0;
            } else if (b instanceof TextBox) {
                // Floating text box (e.g. a letterhead): positioned when emitted, takes no space.
                float[] s = drawingSize((TextBox) b, 100000f, 100000f);
                if (s == null) continue;
                f.pieces.add(new FloatPiece((TextBox) b, s[0], s[1]));
                f.offsets.add(y);
            }
        }
        // Space after the last paragraph does not count (cells and headers end flush).
        f.height = Math.max(0, y - prevAfter);
        return f;
    }

    static boolean sameContext(Paragraph prev, Paragraph p) {
        return prev != null && p.contextualSpacing && prev.contextualSpacing
                && prev.styleId != null && prev.styleId.equals(p.styleId);
    }

    void emitFlow(Flow f, float x, float y, List<Item> out) {
        emitFlow(f, x, y, out, null);
    }

    /** {@code sec} gives page-relative floating drawings their page; null inside cells and boxes. */
    void emitFlow(Flow f, float x, float y, List<Item> out, Sec sec) {
        for (int i = 0; i < f.pieces.size(); i++) {
            Object o = f.pieces.get(i);
            float top = y + f.offsets.get(i);
            if (o instanceof ParaBox) {
                emitPara((ParaBox) o, x, top, out);
            } else if (o instanceof ImgPiece) {
                ImgPiece im = (ImgPiece) o;
                out.add(ImageItem.of(im.image, x + im.x, top, im.w, im.h));
            } else if (o instanceof FloatPiece) {
                FloatPiece fp = (FloatPiece) o;
                float[] at = floatingPosition(fp.drawing, fp.w, fp.h, x, f.width, top, sec, f.marginTop, f.marginBottom);
                emitFloating(fp.drawing, at[0], at[1], fp.w, fp.h, out, f.pageNumber);
            } else if (o instanceof TableBox) {
                TableBox t = (TableBox) o;
                float ry = top;
                for (int r = 0; r < t.rows.size(); r++) {
                    emitRow(t, r, x, ry, out);
                    ry += t.rows.get(r).height;
                }
            }
        }
    }

    /** A paragraph's shading: its lines' height, between the indents. */
    static Item shadeItem(Paragraph p, float x, float width, float top, float bottom) {
        float l = x + tw(Math.min(p.indLeft, p.indLeft + p.firstLine));
        float r = x + width - tw(p.indRight);
        return new RectItem(l, top, Math.max(l, r), bottom, p.shading);
    }

    static void emitPara(ParaBox box, float x, float y, List<Item> out) {
        if (box.para.shading != 0) out.add(shadeItem(box.para, x, box.width, y, y + box.height()));
        if (box.hasBorders()) {
            addTopBorder(box, x, box.width, y - box.padTop, out);
            addOtherBorders(box, x, box.width, y - box.padTop, y + box.height() + box.padBottom, out);
        }
        if (box.layout == null) return;
        out.add(new TextItem(box.layout, 0, box.layout.getLineCount() - 1, x + box.offsetX, y, box.tail,
                box.justify));
        addInlinePictures(box, 0, box.layout.getLineCount() - 1, x + box.offsetX, y, out);
    }

    // ================================================================= tables

    static final class CellBox {
        final Cell cell;
        final int col, span;
        float x0, x1;
        Flow flow;
        int background;
        Border top, left, bottom, right;
        /** Padding: the cell's own, or the table's. */
        float mTop, mLeft, mBottom, mRight;
        /** Rows this cell covers: more than one for the first cell of a vertical merge. */
        int rowSpan = 1;

        CellBox(Cell cell, int col, int span) {
            this.cell = cell;
            this.col = col;
            this.span = span;
        }
    }

    static final class RowBox {
        final List<CellBox> cells = new ArrayList<>();
        float height;
    }

    static final class TableBox {
        final Table table;
        /** Column edges relative to the table's left edge. */
        float[] colX;
        /** Table left edge relative to the content left edge. */
        float x;
        final List<RowBox> rows = new ArrayList<>();
        float marginTop, marginLeft, marginBottom, marginRight;

        TableBox(Table table) {
            this.table = table;
        }

        float height() {
            float h = 0;
            for (RowBox r : rows) h += r.height;
            return h;
        }
    }

    TableBox measureTable(Table t, float avail, String pageNumber) {
        return measureTable(t, avail, pageNumber, avail * 0.1f);
    }

    /**
     * {@code overhang}: how far past {@code avail} the table may reach before it is shrunk, as Word
     * lets a table run into the right margin up to the paper's edge.
     */
    TableBox measureTable(Table t, float avail, String pageNumber, float overhang) {
        TableBox tb = new TableBox(t);
        int cols = Math.max(1, t.columnCount());
        float[] w = new float[cols];
        float total = 0;
        int missing = 0;
        for (int i = 0; i < cols; i++) {
            w[i] = i < t.grid.size() ? tw(t.grid.get(i)) : 0;
            if (w[i] <= 0) missing++;
            total += w[i];
        }
        if (missing > 0 && !t.rows.isEmpty()) {
            // Fall back to the first row's cell widths.
            int c = 0;
            for (Cell cell : t.rows.get(0).cells) {
                for (int k = 0; k < cell.span && c < cols; k++, c++) {
                    if (w[c] <= 0 && cell.width > 0) {
                        w[c] = tw(cell.width) / cell.span;
                        total += w[c];
                        missing--;
                    }
                }
            }
        }
        if (missing > 0) {
            float each = Math.max(UNIT * 20, (avail - total) / missing);
            for (int i = 0; i < cols; i++) {
                if (w[i] <= 0) {
                    w[i] = each;
                    total += each;
                }
            }
        }
        // Word keeps a table's own width, even where it runs into the right margin; only a table
        // wider than the paper is shrunk.
        float indent = t.align == DocModel.ALIGN_START ? tw(t.indent) : 0;
        if (indent + total > avail + overhang) {
            float f = Math.max(UNIT * 20, avail + overhang - Math.max(0, indent)) / total;
            if (f < 1) {
                for (int i = 0; i < cols; i++) w[i] *= f;
                total *= f;
            }
        }
        tb.colX = new float[cols + 1];
        for (int i = 0; i < cols; i++) tb.colX[i + 1] = tb.colX[i] + w[i];
        tb.x = t.align == DocModel.ALIGN_START ? indent : alignX(t.align, avail, total);
        tb.marginTop = tw(t.cellMargin[0]);
        tb.marginLeft = tw(t.cellMargin[1]);
        tb.marginBottom = tw(t.cellMargin[2]);
        tb.marginRight = tw(t.cellMargin[3]);

        int rowCount = t.rows.size();
        CellBox[][] grid = new CellBox[rowCount][cols];
        for (int r = 0; r < rowCount; r++) {
            Row row = t.rows.get(r);
            RowBox rb = new RowBox();
            int c = 0;
            for (Cell cell : row.cells) {
                if (c >= cols) break;
                int span = Math.min(cell.span, cols - c);
                CellBox cb = new CellBox(cell, c, span);
                cb.x0 = tb.colX[c];
                cb.x1 = tb.colX[c + span];
                cb.background = cell.background;
                if (cell.mergedContinue && r > 0 && grid[r - 1][c] != null) {
                    cb.background = grid[r - 1][c].background;
                }
                int[] m = cell.margins;
                cb.mTop = m != null && m[0] >= 0 ? tw(m[0]) : tb.marginTop;
                cb.mLeft = m != null && m[1] >= 0 ? tw(m[1]) : tb.marginLeft;
                cb.mBottom = m != null && m[2] >= 0 ? tw(m[2]) : tb.marginBottom;
                cb.mRight = m != null && m[3] >= 0 ? tw(m[3]) : tb.marginRight;
                for (int k = c; k < c + span; k++) grid[r][k] = cb;
                float inner = Math.max(UNIT * 4, cb.x1 - cb.x0 - cb.mLeft - cb.mRight);
                cb.flow = cell.mergedContinue ? new Flow() : measureFlow(cell.blocks, inner, pageNumber);
                rb.cells.add(cb);
                c += span;
            }
            tb.rows.add(rb);
        }
        // The first cell of a vertical merge covers the rows below it that continue it.
        for (int r = 0; r < rowCount; r++) {
            for (CellBox cb : tb.rows.get(r).cells) {
                if (cb.cell.mergedContinue) continue;
                int n = 1;
                while (r + n < rowCount && grid[r + n][cb.col] != null && grid[r + n][cb.col].cell.mergedContinue
                        && grid[r + n][cb.col].col == cb.col) {
                    n++;
                }
                cb.rowSpan = n;
            }
        }
        // Word adds the width of a row's top border to its height (measured: 11 pt Times rows with
        // half-point borders are 13.0 pt tall, 12.65 pt of text plus the line).
        float[] borderTop = new float[rowCount];
        for (int r = 0; r < rowCount; r++) {
            Border tbTop = r == 0 ? t.borders[DocModel.B_TOP] : t.borders[DocModel.B_INSIDE_H];
            for (CellBox cb : tb.rows.get(r).cells) {
                Border b = cb.cell.borders[DocModel.B_TOP] != null ? cb.cell.borders[DocModel.B_TOP] : tbTop;
                if (b != null && b.visible() && !cb.cell.mergedContinue) borderTop[r] = Math.max(borderTop[r], b.size / 8f * UNIT);
            }
        }
        for (int r = 0; r < rowCount; r++) {
            RowBox rb = tb.rows.get(r);
            for (CellBox cb : rb.cells) {
                if (cb.cell.mergedContinue || cb.rowSpan > 1) continue;
                rb.height = Math.max(rb.height, cb.flow.height + cb.mTop + cb.mBottom + borderTop[r]);
            }
            Row row = t.rows.get(r);
            float h = tw(row.height);
            if (row.exactHeight && h > 0) rb.height = h;
            else rb.height = Math.max(Math.max(rb.height, h), UNIT * 4);
        }
        // A merged cell's text is spread over all its rows, as in Word; only what does not fit in
        // them makes the last row taller.
        for (int r = 0; r < rowCount; r++) {
            for (CellBox cb : tb.rows.get(r).cells) {
                if (cb.rowSpan <= 1) continue;
                float have = 0;
                for (int k = 0; k < cb.rowSpan; k++) have += tb.rows.get(r + k).height;
                float need = cb.flow.height + cb.mTop + cb.mBottom;
                RowBox last = tb.rows.get(r + cb.rowSpan - 1);
                if (need > have && !t.rows.get(r + cb.rowSpan - 1).exactHeight) last.height += need - have;
            }
        }

        // Resolve borders: cell's own, otherwise the table's outer or inside border.
        Border[] tbl = t.borders;
        for (int r = 0; r < rowCount; r++) {
            for (CellBox cb : tb.rows.get(r).cells) {
                Border[] own = cb.cell.borders;
                boolean lastCol = cb.col + cb.span >= cols;
                cb.top = own[DocModel.B_TOP] != null ? own[DocModel.B_TOP]
                        : r == 0 ? tbl[DocModel.B_TOP] : tbl[DocModel.B_INSIDE_H];
                cb.bottom = own[DocModel.B_BOTTOM] != null ? own[DocModel.B_BOTTOM]
                        : r == rowCount - 1 ? tbl[DocModel.B_BOTTOM] : tbl[DocModel.B_INSIDE_H];
                cb.left = own[DocModel.B_LEFT] != null ? own[DocModel.B_LEFT]
                        : cb.col == 0 ? tbl[DocModel.B_LEFT] : tbl[DocModel.B_INSIDE_V];
                cb.right = own[DocModel.B_RIGHT] != null ? own[DocModel.B_RIGHT]
                        : lastCol ? tbl[DocModel.B_RIGHT] : tbl[DocModel.B_INSIDE_V];
                if (cb.cell.mergedContinue) cb.top = null;
                if (r + 1 < rowCount) {
                    CellBox below = grid[r + 1][cb.col];
                    if (below != null && below.cell.mergedContinue) cb.bottom = null;
                }
            }
        }
        return tb;
    }

    void emitRow(TableBox t, int r, float x, float y, List<Item> out) {
        RowBox row = t.rows.get(r);
        float left = x + t.x;
        float bottom = y + row.height;
        for (CellBox cb : row.cells) {
            if (cb.background != 0) out.add(new RectItem(left + cb.x0, y, left + cb.x1, bottom, cb.background));
        }
        for (CellBox cb : row.cells) {
            if (cb.cell.mergedContinue) continue;
            float height = row.height;
            for (int k = 1; k < cb.rowSpan && r + k < t.rows.size(); k++) height += t.rows.get(r + k).height;
            float free = height - cb.flow.height - cb.mTop - cb.mBottom;
            float dy = cb.cell.vAlign == DocModel.CELL_CENTER ? free / 2f
                    : cb.cell.vAlign == DocModel.CELL_BOTTOM ? free : 0;
            // Word cuts a cell's content at its edges (list numbers hanging left of the cell).
            List<Item> all = new ArrayList<>();
            emitFlow(cb.flow, left + cb.x0 + cb.mLeft, y + cb.mTop + Math.max(0, dy), all);
            // Only text is cut; pictures and shapes (perhaps placed outside the cell) stay whole.
            List<Item> mine = new ArrayList<>();
            float top = Float.MAX_VALUE, bot = -Float.MAX_VALUE;
            for (Item it : all) {
                if (!(it instanceof TextItem)) {
                    out.add(it);
                    continue;
                }
                mine.add(it);
                top = Math.min(top, it.top);
                bot = Math.max(bot, it.bottom);
            }
            if (mine.isEmpty()) continue;
            ClipItem clip = new ClipItem(mine, top - UNIT * 4, bot + UNIT * 4, 0);
            clip.left = left + cb.x0;
            clip.right = left + cb.x1;
            out.add(clip);
        }
        for (CellBox cb : row.cells) {
            float l = left + cb.x0, rr = left + cb.x1;
            line(out, cb.top, l, y, rr, y);
            line(out, cb.bottom, l, bottom, rr, bottom);
            line(out, cb.left, l, y, l, bottom);
            line(out, cb.right, rr, y, rr, bottom);
        }
    }

    private static void line(List<Item> out, Border b, float x1, float y1, float x2, float y2) {
        if (b == null || !b.visible()) return;
        if (b.style == DocModel.STROKE_SOLID) out.add(new LineItem(x1, y1, x2, y2, b.size / 8f * UNIT, b.color));
        else stroke(out, b, x1, y1, x2, y2, x1 == x2);
    }

    // ============================================================== body flow

    /** Places the body blocks on pages, breaking paragraphs between lines and tables between rows. */
    private static final class BodyFlow {
        final PageLayout l;
        Sec sec;
        Page page;
        float y;
        int lastNumber;
        Paragraph prev;
        float prevAfter;
        /** The current page started because the previous one was full. */
        boolean naturalBreak;

        final Progress progress;
        /** Pages already handed to {@link #progress}. */
        int published;

        BodyFlow(PageLayout l, Progress progress) {
            this.l = l;
            this.progress = progress;
        }

        /**
         * Hands over the finished pages (all but the one being filled, or all at the end): the
         * first two at once so something shows quickly, later ones in batches.
         */
        void publish(boolean end) {
            if (progress == null) return;
            int finished = end ? l.pages.size() : l.pages.size() - 1;
            int batch = published == 0 ? 2 : 8;
            if (finished - published < (end ? 1 : batch)) return;
            progress.onPages(new ArrayList<>(l.pages.subList(published, finished)));
            published = finished;
        }

        void run() {
            List<Block> blocks = l.doc.blocks;
            Sec previous = null;
            int start = 0;
            for (int i = 0; i <= blocks.size(); i++) {
                boolean end = i == blocks.size();
                if (!end && !(blocks.get(i) instanceof SectionBreak)) continue;
                Section s = end ? l.doc.lastSection : ((SectionBreak) blocks.get(i)).section;
                Sec sc = new Sec(s);
                inherit(sc, previous);
                flowSection(sc, blocks.subList(start, i));
                previous = sc;
                start = i + 1;
            }
            if (l.pages.isEmpty()) newPage(previous != null ? previous : new Sec(new Section()), true);
            finishPage();
            publish(true);
        }

        static void inherit(Sec sc, Sec prev) {
            Section s = sc.section;
            sc.header = s.header != null ? s.header : prev != null ? prev.header : null;
            sc.footer = s.footer != null ? s.footer : prev != null ? prev.footer : null;
            sc.firstHeader = s.firstHeader != null ? s.firstHeader : prev != null ? prev.firstHeader : null;
            sc.firstFooter = s.firstFooter != null ? s.firstFooter : prev != null ? prev.firstFooter : null;
            sc.evenHeader = s.evenHeader != null ? s.evenHeader : prev != null ? prev.evenHeader : null;
            sc.evenFooter = s.evenFooter != null ? s.evenFooter : prev != null ? prev.evenFooter : null;
        }

        void flowSection(Sec sc, List<Block> blocks) {
            // A continuous section carries on down the current page, unless the paper size changes:
            // that cannot happen mid-page, so Word starts a new page.
            boolean samePaper = page != null && sec.section.pageWidth == sc.section.pageWidth
                    && sec.section.pageHeight == sc.section.pageHeight;
            if (!sc.section.continuous || !samePaper) {
                // A page break just before a new-page section makes no blank page in Word.
                if (page != null && page == breakPage && page.items.size() == breakItems && notes.isEmpty()
                        && !l.pages.isEmpty() && l.pages.get(l.pages.size() - 1) == page) {
                    l.pages.remove(l.pages.size() - 1);
                    lastNumber--;
                    page = null;
                }
                newPage(sc, true);
            } else {
                // Its columns start below what is already on the page.
                y = Math.max(y, colBottom);
                sec = sc;
                col = 0;
                colTop = y;
                colBottom = y;
            }
            Table pending = null;
            float pendingY = 0;
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
                if (b instanceof Table && pending == null) {
                    Table t = (Table) b;
                    if (t.floatRel == DocModel.REL_PAGE || t.floatRel == DocModel.REL_MARGIN) {
                        float ty = (t.floatRel == DocModel.REL_PAGE ? 0 : sec.top) + tw(t.floatY);
                        // A table fixed lower on the page: the text after it fills the space above.
                        if (ty > y + UNIT && ty < pageBottom) {
                            pending = t;
                            pendingY = ty;
                            continue;
                        }
                    }
                }
                if (pending != null) {
                    boolean fitsAbove = b instanceof Paragraph && y + blockHeight((Paragraph) b) <= pendingY;
                    if (!fitsAbove) {
                        y = Math.max(y, pendingY);
                        flowTable(pending);
                        pending = null;
                    }
                }
                if (b instanceof Paragraph) {
                    flowParagraph(blocks, i);
                } else if (b instanceof Table) {
                    flowTable((Table) b);
                } else if (b instanceof Image) {
                    flowImage((Image) b);
                } else if (b instanceof TextBox) {
                    placeFloating((TextBox) b);
                } else if (b instanceof ColumnBreak) {
                    if (col + 1 < sec.colX.length) nextColumn(false);
                    else forcedPage();
                } else if (b instanceof PageBreak) {
                    forcedPage();
                }
            }
            if (pending != null) {
                y = Math.max(y, pendingY);
                flowTable(pending);
            }
            colBottom = Math.max(colBottom, y);
        }

        /**
         * A row that does not fit the rest of the page is broken across pages, as Word does: at the
         * lowest line boundary that fits, then on the next page(s). Returns false when no line of
         * it fits here, so that the row moves to the next page whole.
         */
        boolean splitRow(TableBox tb, int r) {
            if (t(tb, r).exactHeight) return false;
            float h = tb.rows.get(r).height;
            float rowTop = y;
            List<Item> items = new ArrayList<>();
            l.emitRow(tb, r, left(), rowTop, items);
            float done = 0;
            boolean first = true;
            while (true) {
                float avail = bottom() - y;
                if (h - done <= avail + 0.5f) {
                    page.items.add(new ClipItem(items, rowTop + done, rowTop + h, y - (rowTop + done)));
                    y += h - done;
                    return true;
                }
                float from = rowTop + done;
                float cut = bestCut(items, from, from + avail);
                if (cut <= from + UNIT) {
                    if (first && !atTop()) return false;
                    // Nothing fits even on an empty page (a picture taller than it): cut anyway.
                    if (cut <= from + UNIT) cut = from + Math.max(avail, UNIT * 10);
                }
                page.items.add(new ClipItem(items, from, cut, y - from));
                done = cut - rowTop;
                first = false;
                overflow();
            }
        }

        private static Row t(TableBox tb, int r) {
            return tb.table.rows.get(r);
        }

        /** The lowest y in (from, to] not inside a line of text or a picture of {@code items}. */
        static float bestCut(List<Item> items, float from, float to) {
            List<float[]> spans = new ArrayList<>();
            collectSpans(items, spans);
            float best = from;
            List<Float> candidates = new ArrayList<>();
            candidates.add(to);
            for (float[] s : spans) {
                candidates.add(s[0]);
                candidates.add(s[1]);
            }
            for (float c : candidates) {
                if (c <= best || c > to) continue;
                boolean inside = false;
                for (float[] s : spans) {
                    if (s[0] < c - 0.5f && s[1] > c + 0.5f) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) best = c;
            }
            return best;
        }

        /** Vertical extents of the text lines and pictures in {@code items} (also inside clips). */
        static void collectSpans(List<Item> items, List<float[]> spans) {
            for (Item it : items) {
                if (it instanceof ClipItem) {
                    collectSpans(((ClipItem) it).items, spans);
                    continue;
                }
                if (it instanceof TextItem) {
                    TextItem ti = (TextItem) it;
                    float dy = ti.top - ti.layout.getLineTop(ti.first);
                    for (int line = ti.first; line <= ti.last; line++) {
                        spans.add(new float[]{dy + ti.layout.getLineTop(line), dy + ti.layout.getLineBottom(line)});
                    }
                } else if (it instanceof ImageItem || it instanceof ShapeItem) {
                    spans.add(new float[]{it.top, it.bottom});
                }
            }
        }

        /**
         * True when blocks i.. up to the section's end or the next page break are all empty
         * paragraphs: overflowing, they would only make a blank page.
         */
        boolean onlyEmptyFrom(List<Block> blocks, int i) {
            for (int j = i; j < blocks.size(); j++) {
                Block b = blocks.get(j);
                if (b instanceof Paragraph && ((Paragraph) b).isEmpty()) {
                    if (((Paragraph) b).pageBreakBefore && j > i) return true;
                    continue;
                }
                if (b instanceof PageBreak) return true;
                if (b instanceof ColumnBreak) continue;
                if (b instanceof Paragraph && ((Paragraph) b).pageBreakBefore) return true;
                return false;
            }
            return true;
        }

        /** Space a paragraph takes in the text, spacing included. */
        float blockHeight(Paragraph p) {
            return tw(p.spaceBefore) + box(p).height() + tw(p.spaceAfter);
        }

        /** Current column. Single-column sections have just column 0. */
        int col;
        /** Top of the columns on this page, and the lowest point any column reached. */
        float colTop, colBottom;

        float left() {
            return sec.left + sec.colX[col];
        }

        float width() {
            return sec.colW[col];
        }

        /** The column is full: continue in the next column, or on a new page after the last one. */
        void overflow() {
            if (col + 1 < sec.colX.length) nextColumn(true);
            else newPage(sec, false);
        }

        void nextColumn(boolean natural) {
            colBottom = Math.max(colBottom, y);
            col++;
            y = colTop;
            prev = null;
            prevAfter = 0;
            naturalBreak = natural;
        }

        /** A page started by a break or by keeping paragraphs together, not by running out of room. */
        void forcedPage() {
            newPage(sec, false);
            naturalBreak = false;
            breakPage = page;
            breakItems = page.items.size();
        }

        /** The page a page break started, and its item count then: still blank if unchanged. */
        Page breakPage;
        int breakItems;

        // ---------------------------------------------------------- footnotes

        /** Space between the text and the footnotes, where the short separator line is drawn. */
        private static final float NOTE_GAP = 14 * UNIT;
        /** Footnotes of the current page, laid out at its bottom when the page is finished. */
        final List<Flow> notes = new ArrayList<>();
        float notesHeight;

        /**
         * Bottom of the text area of this page: the bottom margin, or higher where the footer is
         * taller than it, as Word pushes the text away from the header and footer.
         */
        float pageBottom;

        /** Lowest point the text may reach: above the footnotes of this page. */
        float bottom() {
            return notes.isEmpty() ? pageBottom : pageBottom - notesHeight - NOTE_GAP;
        }

        /** Footnotes of a paragraph, measured; they go on the page where the paragraph starts. */
        List<Flow> measureNotes(Paragraph p) {
            List<Flow> out = new ArrayList<>();
            if (p.footnotes == null) return out;
            for (DocModel.Footnote n : p.footnotes) {
                // The note's own number mark is a placeholder run, filled like a page number.
                out.add(l.measureFlow(n.blocks, sec.contentWidth, String.valueOf(n.number)));
            }
            return out;
        }

        void finishPage() {
            if (page == null || notes.isEmpty()) return;
            float top = pageBottom - notesHeight;
            float line = top - NOTE_GAP / 2;
            page.items.add(new LineItem(sec.left, line, sec.left + Math.min(144 * UNIT, sec.contentWidth / 3),
                    line, 0.5f * UNIT, 0xFF000000));
            for (Flow f : notes) {
                l.emitFlow(f, sec.left, top, page.items);
                top += f.height;
            }
            notes.clear();
            notesHeight = 0;
        }

        void newPage(Sec sc, boolean firstOfSection) {
            finishPage();
            naturalBreak = !firstOfSection;
            sec = sc;
            int number = firstOfSection && sc.section.pageNumberStart >= 0 ? sc.section.pageNumberStart
                    : lastNumber + 1;
            lastNumber = number;
            page = new Page(sc, firstOfSection, Numbering.format(number, sc.section.pageNumberFormat));
            page.pageNo = number;
            bands.clear();
            wraps.clear();
            // Slide background, under everything else.
            if (sc.section.backgroundGradient != null) {
                ShapeItem bg = new ShapeItem(0, 0, sc.width, sc.height, DocModel.GEOM_RECT,
                        sc.section.background, 0);
                bg.gradient = sc.section.backgroundGradient;
                page.items.add(bg);
            } else if (sc.section.background != 0) {
                page.items.add(new RectItem(0, 0, sc.width, sc.height, sc.section.background));
            }
            if (sc.section.backgroundImage != null) {
                ImageItem bg = new ImageItem(sc.section.backgroundImage, 0, 0, sc.width, sc.height);
                bg.alpha = sc.section.backgroundAlpha;
                page.items.add(bg);
            }
            addPageBorder(sc, firstOfSection);
            l.pages.add(page);
            publish(false);
            float[] area = l.textArea(page);
            page.textTop = area[0];
            page.textBottom = area[1];
            pageBottom = area[1];
            y = area[0];
            col = 0;
            colTop = y;
            colBottom = y;
            prev = null;
            prevAfter = 0;
        }

        /** The section's page border (pgBorders): around the text, or from the paper's edge. */
        void addPageBorder(Sec sc, boolean firstOfSection) {
            Section s = sc.section;
            if (s.pageBorders == null) return;
            if (s.pageBorderPages == 1 && !firstOfSection || s.pageBorderPages == 2 && firstOfSection) return;
            float[] sp = new float[4];
            for (int i = 0; i < 4; i++) sp[i] = s.pageBorderSpace[i] * UNIT;
            float l, t, r, b;
            if (s.pageBorderFromPage) {
                t = sp[DocModel.B_TOP];
                l = sp[DocModel.B_LEFT];
                b = sc.height - sp[DocModel.B_BOTTOM];
                r = sc.width - sp[DocModel.B_RIGHT];
            } else {
                t = sc.top - sp[DocModel.B_TOP];
                l = sc.left - sp[DocModel.B_LEFT];
                b = sc.bottom + sp[DocModel.B_BOTTOM];
                r = sc.left + sc.contentWidth + sp[DocModel.B_RIGHT];
            }
            int passes = s.pageBorderDouble ? 2 : 1;
            for (int k = 0; k < passes; k++) {
                for (int i = 0; i < 4; i++) {
                    Border bd = s.pageBorders[i];
                    if (bd == null || !bd.visible()) continue;
                    float w = bd.size / 8f * UNIT / passes;
                    // The second line of a double border sits inside the first.
                    float in = k * Math.max(w * 3, 1.5f * UNIT);
                    float lt = l + in, tp = t + in, rt = r - in, bt = b - in;
                    if (i == DocModel.B_TOP) page.items.add(new LineItem(lt, tp, rt, tp, w, bd.color));
                    else if (i == DocModel.B_BOTTOM) page.items.add(new LineItem(lt, bt, rt, bt, w, bd.color));
                    else if (i == DocModel.B_LEFT) page.items.add(new LineItem(lt, tp, lt, bt, w, bd.color));
                    else page.items.add(new LineItem(rt, tp, rt, bt, w, bd.color));
                }
            }
        }

        boolean atTop() {
            return y <= colTop + 0.5f;
        }

        void flowParagraph(List<Block> blocks, int i) {
            Paragraph p = (Paragraph) blocks.get(i);
            if (p.pageBreakBefore && !atTop()) forcedPage();
            // A run of "keep with next" paragraphs (headings, title blocks) moves to the next page
            // as a whole, together with the start of the paragraph after it.
            if (p.keepNext && !atTop()) {
                float need = chainHeight(blocks, i);
                // Measured in Word: this break still drops the heading's space before.
                if (y + need > bottom() && need <= pageBottom - colTop) overflow();
            }
            ParaBox box = box(p);
            boxes.remove(p);
            float before = tw(p.spaceBefore);
            if (sameContext(prev, p)) {
                y -= prevAfter;
                before = 0;
            }
            // Word drops the space before a paragraph at the top of a page that simply filled up.
            if (atTop() && naturalBreak) before = 0;
            y += collapse(before);
            // Shapes wrapped above and below keep the text's lines off them.
            if (!bands.isEmpty()) {
                float first = box.layout == null ? box.emptyHeight : box.layout.getLineBottom(0);
                for (float[] b : bands) {
                    if (y < b[1] && y + first > b[0]) y = b[1];
                }
            }
            resolveBorders(box, i > 0 ? blocks.get(i - 1) : null, i + 1 < blocks.size() ? blocks.get(i + 1) : null);
            Page borderPage = page;
            float borderTop = y;
            if (box.hasBorders()) {
                float first = box.layout == null ? box.emptyHeight : box.layout.getLineBottom(0);
                if (y + box.padTop + first > bottom() && !atTop()) {
                    overflow();
                    borderPage = page;
                    borderTop = y;
                }
                addTopBorder(box, left(), width(), y, page.items);
                y += box.padTop;
            }
            if (box.layout != null && !wraps.isEmpty()) wrapAround(box);

            if (box.layout == null) {
                float h = box.emptyHeight;
                if (y + h > bottom() && !atTop()) {
                    // Empty lines that only just miss the page at the end of a section would make
                    // a blank page; line heights are not exact enough to tell, so they are dropped.
                    if (onlyEmptyFrom(blocks, i)) return;
                    overflow();
                }
                if (p.shading != 0) page.items.add(shadeItem(p, left(), width(), y, y + h));
                y += h;
            } else {
                List<Flow> own = measureNotes(p);
                if (!own.isEmpty()) {
                    // The first line and the paragraph's footnotes must fit on the same page.
                    float add = 0;
                    for (Flow f : own) add += f.height;
                    float first = box.layout.getLineBottom(0);
                    float limit = pageBottom - notesHeight - add - NOTE_GAP;
                    if (y + first > limit && !atTop()) overflow();
                    notes.addAll(own);
                    notesHeight += add;
                }
                placeLines(box);
            }
            if (box.hasBorders()) {
                y += box.padBottom;
                // Side lines start on this page when the paragraph went on from an earlier one.
                l.addOtherBorders(box, left(), width(), page == borderPage ? borderTop : colTop, y, page.items);
            }
            prevAfter = tw(p.spaceAfter);
            y += prevAfter;
            prev = p;
        }

        /**
         * Space to add before a block: the previous block's space after is already added, and Word
         * uses the larger of the two rather than their sum (measured with Word).
         */
        float collapse(float before) {
            return Math.max(0, before - prevAfter);
        }

        /** Layouts measured ahead (keep-with-next) are reused when the paragraph is placed. */
        final java.util.IdentityHashMap<Paragraph, ParaBox> boxes = new java.util.IdentityHashMap<>();

        ParaBox box(Paragraph p) {
            ParaBox b = boxes.get(p);
            // Measured ahead in another column of a different width: lay out again.
            if (b == null || b.width != width()) {
                b = l.layoutParagraph(p, width(), null);
                boxes.put(p, b);
            }
            return b;
        }

        /** Height of the keep-with-next chain starting at {@code i}, plus two lines of what follows. */
        float chainHeight(List<Block> blocks, int i) {
            float h = 0, reach = 0;
            float limit = pageBottom - sec.top;
            for (int j = i; j < blocks.size() && h <= limit; j++) {
                Block b = blocks.get(j);
                if (b instanceof DocModel.Drawing && ((DocModel.Drawing) b).floating
                        && ((DocModel.Drawing) b).vRel == DocModel.REL_PARAGRAPH && j > i) {
                    // A picture anchored to the paragraph the chain ends with goes on its page too.
                    DocModel.Drawing dr = (DocModel.Drawing) b;
                    float[] s = drawingSize(dr, width(), limit);
                    if (s != null && (dr.wrapsText || dr.clearsText)) {
                        reach = Math.max(reach, h + emu(dr.vOffset) + s[1]);
                    }
                }
                if (b instanceof TextBox) continue;
                if (b instanceof Image) {
                    Image img = (Image) b;
                    if (img.floating && !blocksText(img, width())) continue;
                    // The paragraph holding a picture is what the chain keeps together with.
                    float[] s = imageSize(img, width(), limit);
                    return s == null ? h : h + tw(img.spaceBefore) + s[1];
                }
                if (b instanceof Table) {
                    TableBox tb = l.measureTable((Table) b, width(), null);
                    return tb.rows.isEmpty() ? h : h + tb.rows.get(0).height;
                }
                if (!(b instanceof Paragraph)) break;
                Paragraph q = (Paragraph) b;
                ParaBox bx = box(q);
                h += tw(q.spaceBefore);
                if (!q.keepNext) {
                    Layout lay = bx.layout;
                    float own = h + (lay == null ? bx.emptyHeight : lay.getLineBottom(Math.min(1, lay.getLineCount() - 1)));
                    return Math.max(own, Math.min(reach, limit));
                }
                h += bx.height() + tw(q.spaceAfter);
            }
            return h;
        }

        void placeLines(ParaBox box) {
            Layout lay = box.layout;
            int n = lay.getLineCount();
            int line = 0;
            while (line < n) {
                float startTop = lay.getLineTop(line);
                float limit = bottom();
                if (y + lay.getLineBottom(line) - startTop > limit && !atTop()) {
                    overflow();
                    continue;
                }
                int last = line;
                while (last + 1 < n && y + lay.getLineBottom(last + 1) - startTop <= limit) last++;
                if (last + 1 < n) {
                    // Widow/orphan control: no single line alone at the bottom or top of a page.
                    if (line == 0 && last == 0 && n > 1 && !atTop()) {
                        overflow();
                        continue;
                    }
                    if (last + 2 == n && last > line) last--;
                }
                if (box.para.shading != 0) {
                    page.items.add(shadeItem(box.para, left(), box.width, y, y + lay.getLineBottom(last) - startTop));
                }
                page.items.add(new TextItem(lay, line, last, left() + box.offsetX, y, box.tail,
                        box.justify));
                addInlinePictures(box, line, last, left() + box.offsetX, y, page.items);
                y += lay.getLineBottom(last) - startTop;
                line = last + 1;
                if (line < n) overflow();
            }
        }

        void flowImage(Image img) {
            if (img.floating && !blocksText(img, width())) {
                placeFloating(img);
                return;
            }
            float[] s = imageSize(img, width(), pageBottom - sec.top);
            if (s == null && img.beside.isEmpty()) return;
            if (!atTop() || !naturalBreak) y += collapse(tw(img.spaceBefore));
            // Wrapped above and below, set lower than its paragraph's top: that far down.
            if (img.positioned && !img.afterText && img.vRel == DocModel.REL_PARAGRAPH && img.vOffset > 0) {
                y += Math.min(emu(img.vOffset), Math.max(0, bottom() - y - (s != null ? s[1] : 0)));
            }
            if (!img.beside.isEmpty() && img.positioned) {
                // Pictures wrapped above and below in one paragraph: one band, each at its own x.
                List<Image> all = new ArrayList<>();
                all.add(img);
                all.addAll(img.beside);
                float band = 0;
                List<float[]> sizes = new ArrayList<>();
                for (Image i : all) {
                    float[] sz = imageSize(i, width(), pageBottom - sec.top);
                    sizes.add(sz);
                    if (sz != null) band = Math.max(band, sz[1]);
                }
                if (y + band > bottom() && !atTop()) overflow();
                for (int k = 0; k < all.size(); k++) {
                    float[] sz = sizes.get(k);
                    if (sz == null) continue;
                    float x = floatingPosition(all.get(k), sz[0], sz[1], left(), width(), y, sec)[0];
                    page.items.add(ImageItem.of(all.get(k), x, y, sz[0], sz[1]));
                }
                y += band;
                prev = null;
                prevAfter = tw(img.spaceAfter);
                y += prevAfter;
                return;
            }
            if (!img.beside.isEmpty()) {
                // A row of pictures: each line of them moves to the next page as a whole.
                for (List<Object[]> line : imageLines(img, width(), pageBottom - sec.top)) {
                    float h = lineHeight(line);
                    if (y + h > bottom() && !atTop()) overflow();
                    float x = left() + alignX(img.align, width(), lineWidth(line));
                    for (Object[] o : line) {
                        float[] sz = (float[]) o[1];
                        page.items.add(ImageItem.of((Image) o[0], x, y, sz[0], sz[1]));
                        x += sz[0];
                    }
                    y += h;
                }
                prev = null;
                prevAfter = tw(img.spaceAfter);
                y += prevAfter;
                return;
            }
            if (y + s[1] > bottom() && !atTop()) overflow();
            float x = img.positioned ? floatingPosition(img, s[0], s[1], left(), width(), y, sec)[0]
                    : left() + alignX(img.align, width(), s[0]);
            page.items.add(ImageItem.of(img, x, y, s[0], s[1]));
            y += s[1];
            prev = null;
            prevAfter = tw(img.spaceAfter);
            y += prevAfter;
        }

        /**
         * A floating picture or text box is drawn where the document puts it and takes no space in
         * the text.
         */
        void placeFloating(DocModel.Drawing dr) {
            // Backgrounds and bleeding pictures may reach past the paper; they are cut by the page.
            float[] s = drawingSize(dr, sec.width * 2, sec.height * 2);
            if (s == null) return;
            float[] at = floatingPosition(dr, s[0], s[1], left(), width(), y, sec);
            // A picture placed below its paragraph that would run past the bottom margin goes to
            // the next page with that paragraph, as Word moves the anchor rather than cut it.
            // Shapes without wrapping stay with their paragraph and may cover the footer.
            if (dr.vRel == DocModel.REL_PARAGRAPH && (dr.wrapsText || dr.clearsText || dr instanceof Image)
                    && at[1] + s[1] > bottom() && !atTop()) {
                overflow();
                at = floatingPosition(dr, s[0], s[1], left(), width(), y, sec);
            }
            l.emitFloating(dr, at[0], at[1], s[0], s[1], page.items, page.number);
            if (dr.clearsText) bands.add(new float[]{at[1], at[1] + Math.max(s[1], UNIT)});
            else if (dr.wrapsText && s[0] < width() * 0.9f) {
                // Square wrapping: the text flows beside it, 9 pt away (Word's default distance).
                wraps.add(new float[]{at[0] - 9 * UNIT, at[1], at[0] + s[0] + 9 * UNIT, at[1] + s[1]});
            }
        }

        /** Pictures of this page the text flows around: left, top, right, bottom. */
        final List<float[]> wraps = new ArrayList<>();

        /** Narrowest text Word sets beside a wrapped picture; less and the text goes below it. */
        static final float MIN_BESIDE = 54 * UNIT;

        /** Room left for text at height {@code y} beside the wrapped pictures; {left, right} edges. */
        float[] freeSpan(float top, float bottom) {
            float l = left(), r = left() + width();
            for (float[] w : wraps) {
                if (bottom <= w[1] || top >= w[3]) continue;
                if ((w[0] + w[2]) / 2f < left() + width() / 2f) l = Math.max(l, w[2]);
                else r = Math.min(r, w[0]);
            }
            return new float[]{l, r};
        }

        /** Lays the paragraph out again so that its lines beside wrapped pictures get shorter. */
        void wrapAround(ParaBox box) {
            float x0 = left() + box.offsetX;
            // A paragraph starting where too little room is left beside a picture starts below it.
            float firstH = box.layout.getLineBottom(0);
            for (int guard = 0; guard < 8; guard++) {
                float[] f = freeSpan(y, y + firstH);
                if (f[1] - f[0] >= MIN_BESIDE) break;
                float below = y;
                for (float[] w : wraps) if (y + firstH > w[1] && y < w[3]) below = Math.max(below, w[3]);
                if (below <= y) break;
                y = below;
            }
            Layout lay = box.layout;
            boolean changed = false;
            for (int pass = 0; pass < 3; pass++) {
                int n = lay.getLineCount();
                int[] li = new int[n], ri = new int[n];
                boolean any = false;
                for (int k = 0; k < n; k++) {
                    float[] f = freeSpan(y + lay.getLineTop(k), y + lay.getLineBottom(k));
                    if (f[1] - f[0] < MIN_BESIDE) continue;
                    int margin = k == 0 ? box.firstMargin : box.restMargin;
                    li[k] = Math.max(0, Math.round(f[0] - x0 - margin));
                    ri[k] = Math.max(0, Math.round(x0 + box.layoutWidth - f[1]));
                    if (li[k] > 0 || ri[k] > 0) any = true;
                }
                if (!any && !changed) return;
                lay = buildIndented(box, li, ri);
                changed = true;
            }
            box.layout = lay;
        }

        /** Bands of this page kept clear of text by shapes wrapped above and below. */
        final List<float[]> bands = new ArrayList<>();

        void flowTable(Table t) {
            // A table may reach into the right margin up to the edge of the paper.
            float overhang = Math.max(0, sec.width - left() - width());
            TableBox tb = l.measureTable(t, width(), null, overhang);
            for (int r = 0; r < tb.rows.size(); r++) {
                float h = tb.rows.get(r).height;
                // Rows joined by a vertically merged cell stay on one page when they fit on one.
                float group = h;
                for (CellBox cb : tb.rows.get(r).cells) {
                    if (cb.rowSpan <= 1) continue;
                    float g = 0;
                    for (int k = 0; k < cb.rowSpan && r + k < tb.rows.size(); k++) g += tb.rows.get(r + k).height;
                    group = Math.max(group, g);
                }
                // Small groups stay together; Word lets a tall one run on over the page break.
                boolean keep = group > h && group <= (pageBottom - colTop) / 3f;
                if (keep && y + group > bottom() && !atTop()) overflow();
                if (y + h > bottom() && !keep && splitRow(tb, r)) continue;
                if (y + h > bottom() && !atTop()) overflow();
                l.emitRow(tb, r, left(), y, page.items);
                y += h;
            }
            prev = null;
            prevAfter = 0;
        }
    }
}
