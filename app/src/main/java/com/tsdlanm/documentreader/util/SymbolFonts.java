package com.tsdlanm.documentreader.util;

import java.util.Locale;

/**
 * Symbol and Wingdings put their glyphs on ordinary letters (Symbol "q" is θ) or on the private
 * use area (U+F0xx). Phones have neither font, so such text is turned into the Unicode characters
 * those glyphs show.
 */
public final class SymbolFonts {
    private SymbolFonts() {
    }

    private static final int SYMBOL = 1, WINGDINGS = 2, WINGDINGS2 = 3, WINGDINGS3 = 4;

    private static int kind(String font) {
        if (font == null) return 0;
        String f = font.toLowerCase(Locale.ROOT).trim();
        if (f.equals("symbol")) return SYMBOL;
        if (f.startsWith("wingdings 3")) return WINGDINGS3;
        if (f.startsWith("wingdings 2")) return WINGDINGS2;
        if (f.startsWith("wingdings") || f.startsWith("webdings")) return WINGDINGS;
        return 0;
    }

    public static boolean isSymbolFont(String font) {
        return kind(font) != 0;
    }

    /** {@code text} in {@code font} as Unicode; unchanged when the font is not a symbol font. */
    public static String map(String font, CharSequence text) {
        int k = kind(font);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // The private-use copies (U+F020..U+F0FF) stand for the same codes.
            int code = c >= 0xF020 && c <= 0xF0FF ? c - 0xF000 : c;
            String m = k == 0 && c < 0xF020 ? null : glyph(k == 0 ? SYMBOL : k, code);
            if (m != null) sb.append(m);
            // An unknown picture character: a bullet is the likeliest meaning.
            else if ((k > SYMBOL || c >= 0xF020) && code >= 0x20 && code <= 0xFF) sb.append('•');
            else sb.append(c);
        }
        return sb.toString();
    }

    private static final String GREEK_LOWER = "αβχδεφγηιϕκλμνοπθρστυϖωξψζ";
    private static final String GREEK_UPPER = "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ";

    private static String glyph(int kind, int code) {
        switch (kind) {
            case SYMBOL:
                if (code >= 'a' && code <= 'z') return String.valueOf(GREEK_LOWER.charAt(code - 'a'));
                if (code >= 'A' && code <= 'Z') return String.valueOf(GREEK_UPPER.charAt(code - 'A'));
                return symbol(code);
            case WINGDINGS:
                return wingdings(code);
            case WINGDINGS2:
                return wingdings2(code);
            default:
                return wingdings3(code);
        }
    }

    private static String symbol(int c) {
        switch (c) {
            case 0x22: return "∀";
            case 0x24: return "∃";
            case 0x27: return "∋";
            case 0x2A: return "∗";
            case 0x2D: return "−";
            case 0x40: return "≅";
            case 0x5C: return "∴";
            case 0x5E: return "⊥";
            case 0x60: return "‾";
            case 0x7E: return "∼";
            case 0xA1: return "ϒ";
            case 0xA2: return "′";
            case 0xA3: return "≤";
            case 0xA4: return "⁄";
            case 0xA5: return "∞";
            case 0xA6: return "ƒ";
            case 0xA7: return "♣";
            case 0xA8: return "♦";
            case 0xA9: return "♥";
            case 0xAA: return "♠";
            case 0xAB: return "↔";
            case 0xAC: return "←";
            case 0xAD: return "↑";
            case 0xAE: return "→";
            case 0xAF: return "↓";
            case 0xB0: return "°";
            case 0xB1: return "±";
            case 0xB2: return "″";
            case 0xB3: return "≥";
            case 0xB4: return "×";
            case 0xB5: return "∝";
            case 0xB6: return "∂";
            case 0xB7: return "•";
            case 0xB8: return "÷";
            case 0xB9: return "≠";
            case 0xBA: return "≡";
            case 0xBB: return "≈";
            case 0xBC: return "…";
            case 0xC4: return "⊗";
            case 0xC5: return "⊕";
            case 0xC6: return "∅";
            case 0xC7: return "∩";
            case 0xC8: return "∪";
            case 0xC9: return "⊃";
            case 0xCA: return "⊇";
            case 0xCB: return "⊄";
            case 0xCC: return "⊂";
            case 0xCD: return "⊆";
            case 0xCE: return "∈";
            case 0xCF: return "∉";
            case 0xD0: return "∠";
            case 0xD1: return "∇";
            case 0xD5: return "∏";
            case 0xD6: return "√";
            case 0xD7: return "⋅";
            case 0xD8: return "¬";
            case 0xD9: return "∧";
            case 0xDA: return "∨";
            case 0xDB: return "⇔";
            case 0xDC: return "⇐";
            case 0xDD: return "⇑";
            case 0xDE: return "⇒";
            case 0xDF: return "⇓";
            case 0xE0: return "◊";
            case 0xE1: return "〈";
            case 0xE5: return "∑";
            // Pieces of tall brackets, braces and integrals (equation editors build them so).
            case 0xE6: return "⎛";
            case 0xE7: return "⎜";
            case 0xE8: return "⎝";
            case 0xE9: return "⎡";
            case 0xEA: return "⎢";
            case 0xEB: return "⎣";
            case 0xEC: return "⎧";
            case 0xED: return "⎨";
            case 0xEE: return "⎩";
            case 0xEF: return "⎪";
            case 0xF1: return "〉";
            case 0xF2: return "∫";
            case 0xF3: return "⌠";
            case 0xF4: return "⎮";
            case 0xF5: return "⌡";
            case 0xF6: return "⎞";
            case 0xF7: return "⎟";
            case 0xF8: return "⎠";
            case 0xF9: return "⎤";
            case 0xFA: return "⎥";
            case 0xFB: return "⎦";
            case 0xFC: return "⎫";
            case 0xFD: return "⎬";
            case 0xFE: return "⎭";
            default:
                // Digits and punctuation are the same as in ordinary fonts.
                return c < 0x80 ? String.valueOf((char) c) : null;
        }
    }

    private static String wingdings(int c) {
        switch (c) {
            case 0x20: return " ";
            case 0x21: return "✏";
            case 0x22: return "✂";
            case 0x26: return "📖";
            case 0x28: return "☎";
            case 0x2A: return "✉";
            case 0x36: return "⌛";
            case 0x3E: return "✇";
            case 0x41: return "✌";
            case 0x43: return "👍";
            case 0x44: return "👎";
            case 0x45: return "☜";
            case 0x46: return "☞";
            case 0x4A: return "☺";
            case 0x4B: return "😐";
            case 0x4C: return "☹";
            case 0x4E: return "☠";
            case 0x52: return "☼";
            case 0x54: return "❄";
            case 0x58: return "✠";
            case 0x5B: return "☯";
            case 0x6C: return "●";
            case 0x6D: return "❍";
            case 0x6E: return "■";
            case 0x6F: return "□";
            case 0x70: return "◻";
            case 0x71: return "❑";
            case 0x72: return "❒";
            case 0x73: return "⬧";
            case 0x74: return "⧫";
            case 0x75: return "◆";
            case 0x76: return "❖";
            case 0x77: return "⬥";
            case 0x78: return "⌧";
            case 0x9F: return "•";
            case 0xA0: return "▫";
            case 0xA1: return "○";
            case 0xA2: return "⭕";
            case 0xA7: return "▪";
            case 0xA8: return "◻";
            case 0xAB: return "★";
            case 0xB6: return "✶";
            case 0xD8: return "➢";
            case 0xDF: return "⇦";
            case 0xE0: return "⇨";
            case 0xE8: return "➔";
            case 0xEF: return "⇦";
            case 0xF0: return "⇨";
            case 0xF1: return "⇧";
            case 0xF2: return "⇩";
            case 0xFB: return "✗";
            case 0xFC: return "✓";
            case 0xFD: return "☒";
            case 0xFE: return "☑";
            default: return null;
        }
    }

    private static String wingdings2(int c) {
        switch (c) {
            case 0x50: return "✓";
            case 0x4F: return "✗";
            case 0x52: return "☑";
            case 0x54: return "☒";
            case 0x96: return "•";
            case 0x97: return "•";
            case 0x98: return "●";
            case 0xA0: return "▪";
            case 0xA2: return "◼";
            case 0xA3: return "▪";
            case 0xF0: return "■";
            default: return null;
        }
    }

    private static String wingdings3(int c) {
        switch (c) {
            case 0x5B: return "◀";
            case 0x5C: return "▶";
            case 0x5D: return "▲";
            case 0x5E: return "▼";
            case 0x70: return "◄";
            case 0x71: return "►";
            case 0x72: return "▲";
            case 0x73: return "▼";
            case 0x74: return "◂";
            case 0x75: return "▸";
            case 0x76: return "▴";
            case 0x77: return "▾";
            case 0x7D: return "▸";
            default: return null;
        }
    }
}
