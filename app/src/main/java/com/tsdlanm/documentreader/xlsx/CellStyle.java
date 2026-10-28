package com.tsdlanm.documentreader.xlsx;

/** The parts of an Excel cell style the viewer draws. */
public final class CellStyle {
    public static final int ALIGN_GENERAL = 0, ALIGN_LEFT = 1, ALIGN_CENTER = 2, ALIGN_RIGHT = 3;
    /** Vertical alignment; Excel's default is the bottom of the cell. */
    public static final int V_BOTTOM = 0, V_CENTER = 1, V_TOP = 2;
    /** Border sides, index into {@link #borderStyle} and {@link #borderColor}; DIAGONAL is both diagonals. */
    public static final int LEFT = 0, RIGHT = 1, TOP = 2, BOTTOM = 3, DIAGONAL = 4;
    /** {@link #diagonals} bits. */
    public static final int DIAG_UP = 1, DIAG_DOWN = 2;
    /** Border line styles, ordered by how heavy they look (a heavier one wins on a shared edge). */
    public static final byte B_NONE = 0, B_HAIR = 1, B_DOTTED = 2, B_DASHED = 3, B_THIN = 4,
            B_MEDIUM_DASHED = 5, B_MEDIUM = 6, B_DOUBLE = 7, B_THICK = 8;

    static final CellStyle DEFAULT = new CellStyle();

    NumberFormat format = NumberFormat.GENERAL_FORMAT;
    public boolean bold, italic, underline;
    /** ARGB, 0 = default (black). */
    public int textColor;
    /** ARGB, 0 = no fill. */
    public int fillColor;
    public int align = ALIGN_GENERAL;
    public int vAlign = V_BOTTOM;
    /** Indent level (each about three characters wide). */
    public int indent;
    public boolean wrap;
    /** Excel's textRotation: 0 none, 1..90 degrees up, 91..180 down (90 + degrees), 255 stacked letters. */
    public int rotation;
    /** Font size in points, 0 = default. */
    public float fontSize;
    /** Font name as in the file (e.g. "Calibri"), or null. */
    public String font;
    /** Per side (LEFT, RIGHT, TOP, BOTTOM): B_* style, and ARGB colour (0 = automatic black). */
    public byte[] borderStyle;
    public int[] borderColor;
    /** DIAG_UP / DIAG_DOWN: which diagonal lines cross the cell (style and colour at DIAGONAL). */
    public int diagonals;

    CellStyle copy() {
        CellStyle c = new CellStyle();
        c.format = format;
        c.bold = bold;
        c.italic = italic;
        c.underline = underline;
        c.textColor = textColor;
        c.fillColor = fillColor;
        c.align = align;
        c.vAlign = vAlign;
        c.indent = indent;
        c.wrap = wrap;
        c.rotation = rotation;
        c.fontSize = fontSize;
        c.font = font;
        c.borderStyle = borderStyle;
        c.borderColor = borderColor;
        c.diagonals = diagonals;
        return c;
    }

    public byte border(int side) {
        return borderStyle == null ? B_NONE : borderStyle[side];
    }
}
