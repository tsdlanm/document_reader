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
import android.text.style.BackgroundColorSpan;
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
        for (Item item : page.items) {
            if (item.bottom >= visTop && item.top <= visBottom) item.draw(c, painter);
        }
        for (Item item : decorations(page)) {
            if (item.bottom >= visTop && item.top <= visBottom) item.draw(c, painter);
        }
    }

    /** Header and footer items of a page; built lazily because they hold the page number. */
    List<Item> decorations(Page page) {
        if (page.decorations != null) return page.decorations;
        List<Item> out = new ArrayList<>();
        Sec s = page.sec;
        boolean first = page.firstOfSection && s.section.titlePage;
        List<Block> header = first ? s.firstHeader : s.header;
        List<Block> footer = first ? s.firstFooter : s.footer;
        if (header != null && !header.isEmpty()) {
            Flow f = measureFlow(header, s.contentWidth, page.number);
            emitFlow(f, s.left, tw(s.section.headerDistance), out, s);
        }
        if (footer != null && !footer.isEmpty()) {
            Flow f = measureFlow(footer, s.contentWidth, page.number);
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
        List<Block> header, footer, firstHeader, firstFooter;

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
            layout.draw(c);
            c.restore();
            if (tail != null) tail.draw(c, layout, last, x, dy);
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

        ImageItem(String part, float x, float y, float w, float h) {
            this.part = part;
            rect = new RectF(x, y, x + w, y + h);
            top = y;
            bottom = y + h;
        }

        /** Width of the whole picture at this item's scale (larger than the item when cropped). */
        float fullWidth() {
            return crop == null ? rect.width() : rect.width() / Math.max(0.05f, 1 - crop[0] - crop[2]);
        }

        @Override
        void draw(Canvas c, Painter painter) {
            Bitmap b = painter.image(this);
            if (b != null && crop != null) {
                int w = b.getWidth(), h = b.getHeight();
                android.graphics.Rect src = new android.graphics.Rect(Math.round(w * crop[0]), Math.round(h * crop[1]),
                        Math.round(w * (1 - crop[2])), Math.round(h * (1 - crop[3])));
                c.drawBitmap(b, src, rect, BITMAP_PAINT);
            } else if (b != null) {
                c.drawBitmap(b, null, rect, BITMAP_PAINT);
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

        @Override
        void draw(Canvas c, Painter painter) {
            LINE_PAINT.setColor(color);
            // At least one screen pixel, or thin borders vanish when zoomed out.
            LINE_PAINT.setStrokeWidth(Math.max(width, 1f / painter.scale()));
            c.drawLine(x1, y1, x2, y2, LINE_PAINT);
        }
    }

    /** A filled or outlined rectangle, rounded rectangle or ellipse (slide shapes). */
    static final class ShapeItem extends Item {
        final RectF rect;
        final int geometry, color;
        /** Outline width, or 0 to fill. */
        final float stroke;

        ShapeItem(float l, float t, float r, float b, int geometry, int color, float stroke) {
            rect = new RectF(l, t, r, b);
            this.geometry = geometry;
            this.color = color;
            this.stroke = stroke;
            top = t - stroke;
            bottom = b + stroke;
        }

        @Override
        void draw(Canvas c, Painter painter) {
            Paint p = SHAPE_PAINT;
            p.setColor(color);
            p.setStyle(stroke > 0 ? Paint.Style.STROKE : Paint.Style.FILL);
            p.setStrokeWidth(Math.max(stroke, 1f / painter.scale()));
            if (geometry == DocModel.GEOM_ELLIPSE) {
                c.drawOval(rect, p);
            } else if (geometry == DocModel.GEOM_ROUND_RECT) {
                // PowerPoint's default corner: a sixth of the shorter side.
                float r = Math.min(rect.width(), rect.height()) / 6f;
                c.drawRoundRect(rect, r, r, p);
            } else {
                c.drawRect(rect, p);
            }
        }
    }

    // Items are only drawn on the main thread.
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG);
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
            if (n.contains("sans")) return 0;
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
                p.setFakeBoldText(bold && (p.getTypeface().getStyle() & Typeface.BOLD) == 0);
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
            return WordFontWidths.KERN_ARIAL_BI;
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
            MEASURE.setFakeBoldText(bold && (face.getStyle() & Typeface.BOLD) == 0);
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
            this.fakeBold = bold && (face.getStyle() & Typeface.BOLD) == 0;
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
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
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

        ParaBox(Paragraph para) {
            this.para = para;
        }

        float height() {
            return layout == null ? emptyHeight : layout.getHeight();
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
            sb.append(p.listPrefix).append(p.listSuffix);
            sb.setSpan(new FontSpan(p.markFont, false, false, markSize, false, p.listPrefix), s, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (Run r : p.runs) {
            CharSequence text = r.pageNumber ? (pageNumber == null ? "" : pageNumber) : r.text;
            if (r.caps || r.smallCaps) text = text.toString().toUpperCase(Locale.getDefault());
            if (text.length() == 0) continue;
            int start = sb.length();
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
            sb.setSpan(new FontSpan(r.font, r.bold, r.italic, size, r.vertAlign != DocModel.VALIGN_BASELINE, text,
                    r.charSpacing, r.widthPercent, r.kern), start, end, flags);
            if (r.color != 0) sb.setSpan(new ForegroundColorSpan(r.color), start, end, flags);
            if (r.background != 0) sb.setSpan(new BackgroundColorSpan(r.background), start, end, flags);
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
        if (p.align == DocModel.ALIGN_JUSTIFY && Build.VERSION.SDK_INT >= 26) {
            // Set after the font spans, so it scales the width they set.
            for (int i = 0; i < len; i++) {
                if (sb.charAt(i) == ' ') {
                    sb.setSpan(new ShrinkableSpace(), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }

        Layout.Alignment align = p.align == DocModel.ALIGN_CENTER ? Layout.Alignment.ALIGN_CENTER
                : p.align == DocModel.ALIGN_END ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        box.layout = build(sb, paint, (int) layoutWidth, align, p.align == DocModel.ALIGN_JUSTIFY);
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
        float x;
        if (d.hRel == DocModel.REL_PAGE && sec != null) {
            x = d.hAlign >= 0 ? alignX(d.hAlign, sec.width, w) : emu(d.hOffset);
        } else {
            x = areaLeft + (d.hAlign >= 0 ? alignX(d.hAlign, areaWidth, w) : emu(d.hOffset));
        }
        float y;
        if (d.vRel == DocModel.REL_PAGE && sec != null) {
            y = d.vAlign >= 0 ? alignX(d.vAlign, sec.height, h) : emu(d.vOffset);
        } else if (d.vRel == DocModel.REL_MARGIN && sec != null) {
            y = sec.top + (d.vAlign >= 0 ? alignX(d.vAlign, sec.bottom - sec.top, h) : emu(d.vOffset));
        } else {
            y = paraY + emu(d.vOffset);
        }
        return new float[]{x, y};
    }

    void emitFloating(DocModel.Drawing d, float x, float y, float w, float h, List<Item> out) {
        if (d instanceof Image) {
            Image img = (Image) d;
            ImageItem item = new ImageItem(img.part, x, y, w, h);
            if (img.crop != null) item.crop = img.crop;
            out.add(item);
        } else {
            emitTextBox((TextBox) d, x, y, w, h, out);
        }
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
        if (dr.widthEmu <= 0 || dr.heightEmu <= 0) return null;
        return new float[]{Math.min(maxW, emu(dr.widthEmu)), Math.min(maxH, emu(dr.heightEmu))};
    }

    /** Word's default text-box padding: 0.1 inch left/right, 0.05 inch top/bottom. */
    private static final float BOX_PAD_X = 7.2f * UNIT, BOX_PAD_Y = 3.6f * UNIT;

    /** A text box: its fill, its own blocks laid out inside the padding, and its border. */
    void emitTextBox(TextBox tb, float x, float y, float w, float h, List<Item> out) {
        float lw = tb.borderWidthEmu > 0 ? emu(tb.borderWidthEmu) : 0.75f * UNIT;
        if (tb.geometry == DocModel.GEOM_LINE) {
            if (tb.border != 0) {
                float x1 = tb.flipH ? x + w : x, x2 = tb.flipH ? x : x + w;
                float y1 = tb.flipV ? y + h : y, y2 = tb.flipV ? y : y + h;
                out.add(new LineItem(x1, y1, x2, y2, lw, tb.border));
            }
            return;
        }
        if (tb.fill != 0) out.add(new ShapeItem(x, y, x + w, y + h, tb.geometry, tb.fill, 0));
        if (!tb.blocks.isEmpty()) {
            float l = BOX_PAD_X, t = BOX_PAD_Y, r = BOX_PAD_X, b = BOX_PAD_Y;
            if (tb.insets != null) {
                l = emu(tb.insets[0]);
                t = emu(tb.insets[1]);
                r = emu(tb.insets[2]);
                b = emu(tb.insets[3]);
            }
            Flow f = measureFlow(tb.blocks, Math.max(UNIT * 4, w - l - r), null);
            float ty = y + t;
            if (tb.anchor == DocModel.CELL_CENTER) ty = y + t + (h - t - b - f.height) / 2f;
            else if (tb.anchor == DocModel.CELL_BOTTOM) ty = y + h - b - f.height;
            emitFlow(f, x + l, ty, out);
        }
        if (tb.border == 0) return;
        if (tb.geometry != DocModel.GEOM_RECT) {
            out.add(new ShapeItem(x, y, x + w, y + h, tb.geometry, tb.border, lw));
        } else {
            out.add(new LineItem(x, y, x + w, y, lw, tb.border));
            out.add(new LineItem(x, y + h, x + w, y + h, lw, tb.border));
            out.add(new LineItem(x, y, x, y + h, lw, tb.border));
            out.add(new LineItem(x + w, y, x + w, y + h, lw, tb.border));
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
        float y = 0;
        Paragraph prev = null;
        float prevAfter = 0;
        for (Block b : blocks) {
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
                f.pieces.add(box);
                f.offsets.add(y);
                y += box.height();
                prevAfter = tw(p.spaceAfter);
                y += prevAfter;
                prev = p;
            } else if (b instanceof Image) {
                Image img = (Image) b;
                float[] s = imageSize(img, width, 100000f);
                if (s == null) continue;
                if (img.floating && !blocksText(img, width)) {
                    // Positioned when emitted (it may be relative to the page); takes no space.
                    f.pieces.add(new FloatPiece(img, s[0], s[1]));
                    f.offsets.add(y);
                    continue;
                }
                y += Math.max(0, tw(img.spaceBefore) - prevAfter);
                f.pieces.add(new ImgPiece(img, alignX(img.align, width, s[0]), s[0], s[1]));
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
                out.add(new ImageItem(im.image.part, x + im.x, top, im.w, im.h));
            } else if (o instanceof FloatPiece) {
                FloatPiece fp = (FloatPiece) o;
                float[] at = floatingPosition(fp.drawing, fp.w, fp.h, x, f.width, top, sec);
                emitFloating(fp.drawing, at[0], at[1], fp.w, fp.h, out);
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

    static void emitPara(ParaBox box, float x, float y, List<Item> out) {
        if (box.layout == null) return;
        out.add(new TextItem(box.layout, 0, box.layout.getLineCount() - 1, x + box.offsetX, y, box.tail,
                box.para.align == DocModel.ALIGN_JUSTIFY));
    }

    // ================================================================= tables

    static final class CellBox {
        final Cell cell;
        final int col, span;
        float x0, x1;
        Flow flow;
        int background;
        Border top, left, bottom, right;

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
        // Word lets tables run a little into the margin; shrink only clearly oversized ones.
        if (total > avail * 1.1f) {
            float f = avail / total;
            for (int i = 0; i < cols; i++) w[i] *= f;
            total = avail;
        }
        tb.colX = new float[cols + 1];
        for (int i = 0; i < cols; i++) tb.colX[i + 1] = tb.colX[i] + w[i];
        tb.x = t.align == DocModel.ALIGN_START ? tw(t.indent) : alignX(t.align, avail, total);
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
                for (int k = c; k < c + span; k++) grid[r][k] = cb;
                float inner = Math.max(UNIT * 4, cb.x1 - cb.x0 - tb.marginLeft - tb.marginRight);
                cb.flow = cell.mergedContinue ? new Flow() : measureFlow(cell.blocks, inner, pageNumber);
                if (!cell.mergedContinue) {
                    rb.height = Math.max(rb.height, cb.flow.height + tb.marginTop + tb.marginBottom);
                }
                rb.cells.add(cb);
                c += span;
            }
            float h = tw(row.height);
            if (row.exactHeight && h > 0) rb.height = h;
            else rb.height = Math.max(Math.max(rb.height, h), UNIT * 4);
            tb.rows.add(rb);
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
            float free = row.height - cb.flow.height - t.marginTop - t.marginBottom;
            float dy = cb.cell.vAlign == DocModel.CELL_CENTER ? free / 2f
                    : cb.cell.vAlign == DocModel.CELL_BOTTOM ? free : 0;
            emitFlow(cb.flow, left + cb.x0 + t.marginLeft, y + t.marginTop + Math.max(0, dy), out);
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
        out.add(new LineItem(x1, y1, x2, y2, b.size / 8f * UNIT, b.color));
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
        }

        void flowSection(Sec sc, List<Block> blocks) {
            // A continuous section carries on down the current page, unless the paper size changes:
            // that cannot happen mid-page, so Word starts a new page.
            boolean samePaper = page != null && sec.section.pageWidth == sc.section.pageWidth
                    && sec.section.pageHeight == sc.section.pageHeight;
            if (!sc.section.continuous || !samePaper) {
                newPage(sc, true);
            } else {
                // Its columns start below what is already on the page.
                y = Math.max(y, colBottom);
                sec = sc;
                col = 0;
                colTop = y;
                colBottom = y;
            }
            for (int i = 0; i < blocks.size(); i++) {
                Block b = blocks.get(i);
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
            colBottom = Math.max(colBottom, y);
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
        }

        // ---------------------------------------------------------- footnotes

        /** Space between the text and the footnotes, where the short separator line is drawn. */
        private static final float NOTE_GAP = 14 * UNIT;
        /** Footnotes of the current page, laid out at its bottom when the page is finished. */
        final List<Flow> notes = new ArrayList<>();
        float notesHeight;

        /** Lowest point the text may reach: above the footnotes of this page. */
        float bottom() {
            return notes.isEmpty() ? sec.bottom : sec.bottom - notesHeight - NOTE_GAP;
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
            float top = sec.bottom - notesHeight;
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
            // Slide background, under everything else.
            if (sc.section.background != 0) {
                page.items.add(new RectItem(0, 0, sc.width, sc.height, sc.section.background));
            }
            if (sc.section.backgroundImage != null) {
                page.items.add(new ImageItem(sc.section.backgroundImage, 0, 0, sc.width, sc.height));
            }
            l.pages.add(page);
            publish(false);
            y = sc.top;
            col = 0;
            colTop = sc.top;
            colBottom = sc.top;
            prev = null;
            prevAfter = 0;
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
                if (y + need > bottom() && need <= sec.bottom - colTop) overflow();
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

            if (box.layout == null) {
                float h = box.emptyHeight;
                if (y + h > bottom() && !atTop()) overflow();
                y += h;
            } else {
                List<Flow> own = measureNotes(p);
                if (!own.isEmpty()) {
                    // The first line and the paragraph's footnotes must fit on the same page.
                    float add = 0;
                    for (Flow f : own) add += f.height;
                    float first = box.layout.getLineBottom(0);
                    float limit = sec.bottom - notesHeight - add - NOTE_GAP;
                    if (y + first > limit && !atTop()) overflow();
                    notes.addAll(own);
                    notesHeight += add;
                }
                placeLines(box);
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
            float h = 0;
            float limit = sec.bottom - sec.top;
            for (int j = i; j < blocks.size() && h <= limit; j++) {
                Block b = blocks.get(j);
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
                    return h + (lay == null ? bx.emptyHeight : lay.getLineBottom(Math.min(1, lay.getLineCount() - 1)));
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
                page.items.add(new TextItem(lay, line, last, left() + box.offsetX, y, box.tail,
                        box.para.align == DocModel.ALIGN_JUSTIFY));
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
            float[] s = imageSize(img, width(), sec.bottom - sec.top);
            if (s == null) return;
            if (!atTop() || !naturalBreak) y += collapse(tw(img.spaceBefore));
            if (y + s[1] > bottom() && !atTop()) overflow();
            float x = left() + alignX(img.align, width(), s[0]);
            page.items.add(new ImageItem(img.part, x, y, s[0], s[1]));
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
            float[] s = drawingSize(dr, sec.width, sec.height);
            if (s == null) return;
            float[] at = floatingPosition(dr, s[0], s[1], left(), width(), y, sec);
            l.emitFloating(dr, at[0], at[1], s[0], s[1], page.items);
        }

        void flowTable(Table t) {
            TableBox tb = l.measureTable(t, width(), null);
            for (int r = 0; r < tb.rows.size(); r++) {
                float h = tb.rows.get(r).height;
                if (y + h > bottom() && !atTop()) overflow();
                l.emitRow(tb, r, left(), y, page.items);
                y += h;
            }
            prev = null;
            prevAfter = 0;
        }
    }
}
