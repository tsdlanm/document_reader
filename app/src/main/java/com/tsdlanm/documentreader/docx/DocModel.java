package com.tsdlanm.documentreader.docx;

import java.util.ArrayList;
import java.util.List;

/** Plain, Android-free model of a Word document: a flat list of blocks. */
public final class DocModel {
    private DocModel() {
    }

    public abstract static class Block {
    }

    public static final int ALIGN_START = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_END = 2;
    public static final int ALIGN_JUSTIFY = 3;

    public static final class Paragraph extends Block {
        public final List<Run> runs = new ArrayList<>();
        public int align = ALIGN_START;
        /** Left indent in twips (1/1440 inch). */
        public int indent;
        /** 0 for body text, 1..6 for headings, 1 also used for "Title". */
        public int heading;
        /** List marker such as "•" or "2.", or null. */
        public String listPrefix;

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

        Paragraph copyProps() {
            Paragraph p = new Paragraph();
            p.align = align;
            p.indent = indent;
            p.heading = heading;
            return p;
        }
    }

    public static final int VALIGN_BASELINE = 0;
    public static final int VALIGN_SUPER = 1;
    public static final int VALIGN_SUB = 2;

    public static final class Run {
        public final StringBuilder text = new StringBuilder();
        public boolean bold, italic, underline, strike;
        /** Font size in half points, 0 for the document default. */
        public int halfPoints;
        /** ARGB, 0 for the default colour. */
        public int color;
        /** ARGB background (highlight or shading), 0 for none. */
        public int background;
        public int vertAlign;

        boolean sameFormat(Run o) {
            return bold == o.bold && italic == o.italic && underline == o.underline && strike == o.strike
                    && halfPoints == o.halfPoints && color == o.color && background == o.background
                    && vertAlign == o.vertAlign;
        }
    }

    public static final class Image extends Block {
        /** Zip entry name of the image data. */
        public final String part;
        /** Size in EMU (914400 per inch), 0 when unknown. */
        public final long widthEmu, heightEmu;

        public Image(String part, long widthEmu, long heightEmu) {
            this.part = part;
            this.widthEmu = widthEmu;
            this.heightEmu = heightEmu;
        }
    }

    public static final class Cell {
        public final List<Block> blocks = new ArrayList<>();
        public int span = 1;
        /** True for the lower cells of a vertical merge; drawn empty. */
        public boolean mergedContinue;
        public int background;
    }

    public static final class Table extends Block {
        /** Column widths in twips from the table grid; may be empty. */
        public final List<Integer> grid = new ArrayList<>();
        public final List<List<Cell>> rows = new ArrayList<>();

        public int columnCount() {
            int max = grid.size();
            for (List<Cell> row : rows) {
                int n = 0;
                for (Cell c : row) n += c.span;
                if (n > max) max = n;
            }
            return max;
        }
    }

    public static final class Document {
        public final List<Block> blocks;
        /** Default body font size in half points. */
        public final int defaultHalfPoints;

        Document(List<Block> blocks, int defaultHalfPoints) {
            this.blocks = blocks;
            this.defaultHalfPoints = defaultHalfPoints;
        }
    }
}
