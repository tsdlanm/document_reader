package com.tsdlanm.documentreader.xlsx;

/** The parts of an Excel cell style the viewer draws. */
public final class CellStyle {
    public static final int ALIGN_GENERAL = 0, ALIGN_LEFT = 1, ALIGN_CENTER = 2, ALIGN_RIGHT = 3;

    static final CellStyle DEFAULT = new CellStyle();

    NumberFormat format = NumberFormat.GENERAL_FORMAT;
    public boolean bold, italic, underline;
    /** ARGB, 0 = default (black). */
    public int textColor;
    /** ARGB, 0 = no fill. */
    public int fillColor;
    public int align = ALIGN_GENERAL;
    public boolean wrap;
    /** Font size in points, 0 = default. */
    public float fontSize;
}
