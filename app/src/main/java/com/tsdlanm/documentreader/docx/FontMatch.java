package com.tsdlanm.documentreader.docx;

import android.graphics.Typeface;

/**
 * The phone font standing in for a document font (serif, sans or monospace), and how much to
 * stretch it so text is as wide as in the original font (Calibri, Times New Roman, Arial...).
 * Shared with the spreadsheet view, so cells wrap and overflow where they do in Excel.
 */
public final class FontMatch {
    private FontMatch() {
    }

    /** Starts fetching the Office-compatible fonts; call early so they are ready when needed. */
    public static void prefetch(android.content.Context context) {
        FontDownloads.prefetch(context);
    }

    /** Runs {@code r} (main thread) when the fonts are fetched, or after {@code timeoutMs}. */
    public static void whenReady(Runnable r, long timeoutMs) {
        FontDownloads.whenReady(r, timeoutMs);
    }

    /** {@code r} runs on the main thread each time a downloaded font arrives. */
    public static void addListener(Runnable r) {
        FontDownloads.addListener(r);
    }

    public static void removeListener(Runnable r) {
        FontDownloads.removeListener(r);
    }

    public static Typeface typeface(String font, boolean bold, boolean italic) {
        return PageLayout.Fonts.get(font, bold, italic);
    }

    /** True when the phone font has no such bold and it must be faked. */
    public static boolean fakeBold(String font, boolean bold, boolean italic) {
        return bold && (typeface(font, bold, italic).getStyle() & Typeface.BOLD) == 0;
    }

    /** Horizontal text scale for {@code text}; 1 for fonts without width data. */
    public static float scale(String font, boolean bold, boolean italic, CharSequence text) {
        if (font == null) return 1f;
        return PageLayout.Fonts.runScale(font, bold, italic, text, false);
    }
}
