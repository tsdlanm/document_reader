package com.tsdlanm.documentreader.docx;

import com.tsdlanm.documentreader.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Produces list markers ("1.", "a)", "•") from word/numbering.xml. */
final class Numbering {
    private static final String[] BULLETS = {"•", "◦", "▪"};

    private static final class Level {
        String format = "decimal";
        String text = "%1.";
        int start = 1;
        /** Indents in twips from the level's pPr, -1 when not given. */
        int left = -1, hanging = -1;
        /** What follows the marker: tab, space or nothing. */
        String suffix = "tab";
    }

    private final Map<String, Level[]> abstracts = new HashMap<>();
    private final Map<String, String> numToAbstract = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();

    void parse(XmlPullParser p) throws XmlPullParserException, IOException {
        String currentAbstract = null;
        String currentNum = null;
        Level level = null;
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.END_TAG) {
                String n = p.getName();
                if ("abstractNum".equals(n)) currentAbstract = null;
                else if ("num".equals(n)) currentNum = null;
                else if ("lvl".equals(n)) level = null;
                continue;
            }
            if (e != XmlPullParser.START_TAG) continue;
            switch (p.getName()) {
                case "abstractNum":
                    currentAbstract = XmlUtil.attr(p, "abstractNumId");
                    if (currentAbstract != null) abstracts.put(currentAbstract, new Level[9]);
                    break;
                case "lvl": {
                    int ilvl = XmlUtil.intAttr(p, "ilvl", 0);
                    Level[] levels = currentAbstract == null ? null : abstracts.get(currentAbstract);
                    if (levels != null && ilvl >= 0 && ilvl < 9) {
                        level = new Level();
                        levels[ilvl] = level;
                    }
                    break;
                }
                case "start":
                    if (level != null) level.start = XmlUtil.intAttr(p, "val", 1);
                    break;
                case "numFmt":
                    if (level != null) {
                        String v = XmlUtil.attr(p, "val");
                        if (v != null) level.format = v;
                    }
                    break;
                case "lvlText":
                    if (level != null) {
                        String v = XmlUtil.attr(p, "val");
                        level.text = v == null ? "" : v;
                    }
                    break;
                case "suff":
                    if (level != null) {
                        String v = XmlUtil.attr(p, "val");
                        if (v != null) level.suffix = v;
                    }
                    break;
                case "ind":
                    if (level != null) {
                        String l = XmlUtil.attr(p, "left");
                        if (l == null) l = XmlUtil.attr(p, "start");
                        level.left = XmlUtil.parseInt(l, level.left);
                        level.hanging = XmlUtil.intAttr(p, "hanging", level.hanging);
                    }
                    break;
                case "num":
                    currentNum = XmlUtil.attr(p, "numId");
                    break;
                case "abstractNumId":
                    if (currentNum != null) numToAbstract.put(currentNum, XmlUtil.attr(p, "val"));
                    break;
                default:
                    break;
            }
        }
    }

    private Level level(String numId, int ilvl) {
        if (numId == null || ilvl < 0 || ilvl > 8) return null;
        String abs = numToAbstract.get(numId);
        Level[] levels = abs == null ? null : abstracts.get(abs);
        return levels == null ? null : levels[ilvl];
    }

    /** Left indent and hanging indent (twips) of a list level, or null when it sets none. */
    int[] indent(String numId, int ilvl) {
        Level l = level(numId, ilvl);
        if (l == null || (l.left < 0 && l.hanging < 0)) return null;
        return new int[]{l.left, l.hanging};
    }

    /** Separator after the marker: "\t", " " or "". */
    String suffix(String numId, int ilvl) {
        Level l = level(numId, ilvl);
        if (l == null || "tab".equals(l.suffix)) return "\t";
        return "space".equals(l.suffix) ? " " : "";
    }

    /** Returns the marker for the next item of list {@code numId} at {@code ilvl}, or null. */
    String next(String numId, int ilvl) {
        if (numId == null || "0".equals(numId)) return null;
        if (ilvl < 0 || ilvl > 8) ilvl = 0;
        String abs = numToAbstract.get(numId);
        Level[] levels = abs == null ? null : abstracts.get(abs);
        Level lvl = levels == null ? null : levels[ilvl];
        if (lvl == null) return BULLETS[ilvl % BULLETS.length];
        if ("bullet".equals(lvl.format)) return BULLETS[ilvl % BULLETS.length];
        if ("none".equals(lvl.format)) return null;

        int[] c = counters.get(numId);
        if (c == null) {
            c = new int[9];
            for (int i = 0; i < 9; i++) c[i] = Integer.MIN_VALUE;
            counters.put(numId, c);
        }
        c[ilvl] = c[ilvl] == Integer.MIN_VALUE ? lvl.start : c[ilvl] + 1;
        for (int i = ilvl + 1; i < 9; i++) c[i] = Integer.MIN_VALUE;

        String text = lvl.text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '%' && i + 1 < text.length() && text.charAt(i + 1) >= '1' && text.charAt(i + 1) <= '9') {
                int k = text.charAt(i + 1) - '1';
                Level l = levels[k] != null ? levels[k] : lvl;
                int value = c[k] == Integer.MIN_VALUE ? l.start : c[k];
                sb.append(format(value, l.format));
                i++;
            } else {
                sb.append(ch);
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static String format(int n, String fmt) {
        switch (fmt) {
            case "lowerLetter":
                return letters(n).toLowerCase();
            case "upperLetter":
                return letters(n);
            case "lowerRoman":
                return roman(n).toLowerCase();
            case "upperRoman":
                return roman(n);
            case "decimalZero":
                return n < 10 && n >= 0 ? "0" + n : String.valueOf(n);
            default:
                return String.valueOf(n);
        }
    }

    private static String letters(int n) {
        if (n <= 0) return String.valueOf(n);
        // Word repeats the letter: a..z, aa..zz, aaa..
        char ch = (char) ('A' + (n - 1) % 26);
        int count = (n - 1) / 26 + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(ch);
        return sb.toString();
    }

    private static String roman(int n) {
        if (n <= 0 || n >= 4000) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                sb.append(s[i]);
                n -= v[i];
            }
        }
        return sb.toString();
    }
}
