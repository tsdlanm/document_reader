package com.tsdlanm.documentreader.docx;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain, Android-free model of a Word document: a flat list of blocks. Lengths are in twips
 * (1/20 point) unless stated otherwise, as in the file.
 */
public final class DocModel {
    private DocModel() {
    }

    public abstract static class Block {
    }

    public static final int ALIGN_START = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_END = 2;
    public static final int ALIGN_JUSTIFY = 3;

    public static final int TAB_LEFT = 0;
    public static final int TAB_CENTER = 1;
    public static final int TAB_RIGHT = 2;

    public static final int LINE_AUTO = 0;
    public static final int LINE_EXACT = 1;
    public static final int LINE_AT_LEAST = 2;

    public static final class Paragraph extends Block {
        public final List<Run> runs = new ArrayList<>();
        public int align = ALIGN_START;
        public int indLeft, indRight;
        /** First-line indent; negative for a hanging indent. */
        public int firstLine;
        public int spaceBefore, spaceAfter;
        /** Line spacing: 240ths of a line for LINE_AUTO, twips otherwise. */
        public int line = 240;
        public int lineRule = LINE_AUTO;
        public boolean contextualSpacing;
        public boolean pageBreakBefore;
        /** Keep on the same page as the next paragraph (headings). */
        public boolean keepNext;
        /** Custom tab stops, sorted, with their TAB_* alignment and leader character (0 for none). */
        public int[] tabs = new int[0];
        public int[] tabAligns = new int[0];
        public char[] tabLeaders = new char[0];
        public String styleId;
        /** 0 for body text, 1..6 for headings ("Title" counts as 1). */
        public int heading;
        /** List marker such as "•" or "2.", or null. */
        public String listPrefix;
        /** Separator after the list marker: tab, space or nothing. */
        public String listSuffix = "\t";
        /** Font of the paragraph mark; sets the height of an empty paragraph. */
        public int markHalfPoints;
        public String markFont;
        /** Footnotes referenced in this paragraph, or null. */
        public List<Footnote> footnotes;

        public boolean isEmpty() {
            if (listPrefix != null) return false;
            for (Run r : runs) if (r.text.length() > 0) return false;
            return true;
        }

        public String plainText() {
            StringBuilder sb = new StringBuilder();
            if (listPrefix != null) sb.append(listPrefix).append(' ');
            for (Run r : runs) sb.append(r.text);
            return sb.toString();
        }

        /** A paragraph that continues this one after an image or page break. */
        Paragraph continuation() {
            Paragraph p = new Paragraph();
            p.align = align;
            p.indLeft = indLeft;
            p.indRight = indRight;
            p.spaceAfter = spaceAfter;
            p.line = line;
            p.lineRule = lineRule;
            p.contextualSpacing = contextualSpacing;
            p.tabs = tabs;
            p.tabAligns = tabAligns;
            p.tabLeaders = tabLeaders;
            p.styleId = styleId;
            p.heading = heading;
            p.markHalfPoints = markHalfPoints;
            p.markFont = markFont;
            return p;
        }
    }

    public static final int VALIGN_BASELINE = 0;
    public static final int VALIGN_SUPER = 1;
    public static final int VALIGN_SUB = 2;

    public static final class Run {
        public final StringBuilder text = new StringBuilder();
        public boolean bold, italic, underline, strike, caps, smallCaps;
        /** Font size in half points, 0 for the document default. */
        public int halfPoints;
        /** Font name as written in the document, or null for the default. */
        public String font;
        /** ARGB, 0 for the default colour. */
        public int color;
        /** ARGB background (highlight or shading), 0 for none. */
        public int background;
        public int vertAlign;
        /** A PAGE field: the text is replaced by the current page number when drawn. */
        public boolean pageNumber;
        /** Extra space between characters in twips (negative = condensed). */
        public int charSpacing;
        /** Horizontal glyph scale in percent. */
        public int widthPercent = 100;
        /** Pair kerning is on (w:kern and the font is at least that size). */
        public boolean kern;

        boolean sameFormat(Run o) {
            return bold == o.bold && italic == o.italic && underline == o.underline && strike == o.strike
                    && caps == o.caps && smallCaps == o.smallCaps && halfPoints == o.halfPoints
                    && color == o.color && background == o.background && vertAlign == o.vertAlign
                    && !pageNumber && !o.pageNumber
                    && charSpacing == o.charSpacing && widthPercent == o.widthPercent && kern == o.kern
                    && (font == null ? o.font == null : font.equals(o.font));
        }
    }

    /** A drawing object (picture or text box), inline or floating on the page. */
    public abstract static class Drawing extends Block {
        /** Size in EMU (914400 per inch), 0 when unknown. */
        public final long widthEmu, heightEmu;
        /** Horizontal alignment and spacing of the paragraph holding the drawing. */
        public int align = ALIGN_START;
        public int spaceBefore, spaceAfter;
        /** Floating: positioned on the page instead of taking a line. */
        public boolean floating;
        /** Floating with text wrapped around it (square/tight); large ones push text down. */
        public boolean wrapsText;
        /** Floating position: REL_* reference and either an alignment or an EMU offset. */
        public int hRel = REL_MARGIN, vRel = REL_PARAGRAPH;
        public int hAlign = -1, vAlign = -1;
        public long hOffset, vOffset;

        Drawing(long widthEmu, long heightEmu) {
            this.widthEmu = widthEmu;
            this.heightEmu = heightEmu;
        }
    }

    public static final class Image extends Drawing {
        /** Zip entry name of the image data. */
        public final String part;
        /** Fractions of the picture cut off left, top, right and bottom; null when shown whole. */
        public float[] crop;

        public Image(String part, long widthEmu, long heightEmu) {
            super(widthEmu, heightEmu);
            this.part = part;
        }
    }

    public static final int GEOM_RECT = 0, GEOM_ROUND_RECT = 1, GEOM_ELLIPSE = 2, GEOM_LINE = 3;

    /**
     * A floating text box: its own blocks inside an optional border. Slides use it for every shape,
     * so it can also have a fill, another outline shape, its own padding and vertical anchoring.
     */
    public static final class TextBox extends Drawing {
        public final List<Block> blocks;
        /** Border colour (ARGB), 0 for none. */
        public int border;
        /** Border width in EMU, 0 for Word's 0.75 pt. */
        public long borderWidthEmu;
        /** Fill colour (ARGB), 0 for none. */
        public int fill;
        /** GEOM_*: the outline's shape. A line runs corner to corner, mirrored by flipH / flipV. */
        public int geometry = GEOM_RECT;
        public boolean flipH, flipV;
        /** Text padding (left, top, right, bottom) in EMU, or null for Word's default. */
        public long[] insets;
        /** Vertical position of the text: CELL_TOP, CELL_CENTER or CELL_BOTTOM. */
        public int anchor = CELL_TOP;

        TextBox(List<Block> blocks, long widthEmu, long heightEmu) {
            super(widthEmu, heightEmu);
            this.blocks = blocks;
        }
    }

    /**
     * A footnote: its number and text. In the text, the runs marked {@link Run#pageNumber} are the
     * footnote's own reference mark and show {@link #number}.
     */
    public static final class Footnote {
        public final int number;
        public final List<Block> blocks;

        Footnote(int number, List<Block> blocks) {
            this.number = number;
            this.blocks = blocks;
        }
    }

    /** Forced column break (w:br type="column"). */
    public static final class ColumnBreak extends Block {
    }

    public static final int REL_PAGE = 0, REL_MARGIN = 1, REL_PARAGRAPH = 2;

    /** Forced page break (w:br type="page"). */
    public static final class PageBreak extends Block {
    }

    /** Ends a section; the blocks since the previous break use {@link #section}. */
    public static final class SectionBreak extends Block {
        public final Section section;

        SectionBreak(Section section) {
            this.section = section;
        }
    }

    public static final class Section {
        /** Page size and margins in twips; defaults are A4 with 1 inch margins. */
        public int pageWidth = 11906, pageHeight = 16838;
        public int marginTop = 1440, marginRight = 1440, marginBottom = 1440, marginLeft = 1440;
        public int headerDistance = 720, footerDistance = 720;
        /** Starts on the same page as the previous section. */
        public boolean continuous;
        /** Use the first-page header/footer on the section's first page. */
        public boolean titlePage;
        /** Page number of the first page, or -1 to continue counting. */
        public int pageNumberStart = -1;
        /** decimal, lowerRoman, upperRoman, lowerLetter, upperLetter. */
        public String pageNumberFormat = "decimal";
        /** Header/footer content; null means "same as the previous section". */
        public List<Block> header, footer, firstHeader, firstFooter;
        /** Text columns: widths and the gap after each (twips). One column when empty. */
        public int[] columnWidths = new int[0], columnGaps = new int[0];
        /** Page background colour (ARGB, 0 for white) and picture (zip entry, or null); for slides. */
        public int background;
        public String backgroundImage;
    }

    /** A border line; {@link #NONE} hides an inherited border. Width in eighths of a point. */
    public static final class Border {
        public static final Border NONE = new Border(0, 0);

        public final int size;
        public final int color;

        Border(int size, int color) {
            this.size = size;
            this.color = color;
        }

        public boolean visible() {
            return this != NONE;
        }
    }

    public static final int CELL_TOP = 0, CELL_CENTER = 1, CELL_BOTTOM = 2;

    public static final class Cell {
        public final List<Block> blocks = new ArrayList<>();
        public int span = 1;
        /** True for the lower cells of a vertical merge; drawn empty. */
        public boolean mergedContinue;
        public int background;
        /** Preferred width in twips, 0 when unknown. */
        public int width;
        public int vAlign = CELL_TOP;
        /** Top, left, bottom, right; null entries fall back to the table borders. */
        public final Border[] borders = new Border[4];
    }

    public static final class Row {
        public final List<Cell> cells = new ArrayList<>();
        /** Height in twips, 0 for automatic. */
        public int height;
        public boolean exactHeight;
    }

    public static final int B_TOP = 0, B_LEFT = 1, B_BOTTOM = 2, B_RIGHT = 3, B_INSIDE_H = 4, B_INSIDE_V = 5;

    public static final class Table extends Block {
        /** Column widths in twips from the table grid; may be empty. */
        public final List<Integer> grid = new ArrayList<>();
        public final List<Row> rows = new ArrayList<>();
        /** Top, left, bottom, right, inside horizontal, inside vertical; null for none. */
        public final Border[] borders = new Border[6];
        public int indent;
        public int align = ALIGN_START;
        /** Cell padding: top, left, bottom, right. */
        public final int[] cellMargin = {0, 108, 0, 108};

        public int columnCount() {
            int max = grid.size();
            for (Row row : rows) {
                int n = 0;
                for (Cell c : row.cells) n += c.span;
                if (n > max) max = n;
            }
            return max;
        }
    }

    public static final class Document {
        public final List<Block> blocks;
        /** Default body font size in half points. */
        public final int defaultHalfPoints;
        /** Section of the blocks after the last section break. */
        public final Section lastSection;
        /** Distance between default tab stops, in twips. */
        public final int defaultTab;

        Document(List<Block> blocks, int defaultHalfPoints, Section lastSection, int defaultTab) {
            this.blocks = blocks;
            this.defaultHalfPoints = defaultHalfPoints;
            this.lastSection = lastSection;
            this.defaultTab = defaultTab;
        }
    }
}
