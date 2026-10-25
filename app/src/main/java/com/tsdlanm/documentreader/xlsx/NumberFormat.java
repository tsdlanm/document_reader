package com.tsdlanm.documentreader.xlsx;

import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** A simplified Excel number format: general, fixed decimals, percent, date/time and text. */
final class NumberFormat {
    static final int GENERAL = 0, FIXED = 1, PERCENT = 2, DATE = 3, TIME = 4, DATETIME = 5, TEXT = 6;

    static final NumberFormat GENERAL_FORMAT = new NumberFormat(GENERAL, 0, false, "", "");

    final int kind;
    final int decimals;
    final boolean grouping;
    final String prefix, suffix;

    private DecimalFormat decimalFormat;
    private DateFormat dateFormat;

    private NumberFormat(int kind, int decimals, boolean grouping, String prefix, String suffix) {
        this.kind = kind;
        this.decimals = decimals;
        this.grouping = grouping;
        this.prefix = prefix;
        this.suffix = suffix;
    }

    static NumberFormat builtin(int id) {
        switch (id) {
            case 1: return new NumberFormat(FIXED, 0, false, "", "");
            case 2: return new NumberFormat(FIXED, 2, false, "", "");
            case 3: case 37: case 38: return new NumberFormat(FIXED, 0, true, "", "");
            case 4: case 39: case 40: return new NumberFormat(FIXED, 2, true, "", "");
            case 9: return new NumberFormat(PERCENT, 0, false, "", "");
            case 10: return new NumberFormat(PERCENT, 2, false, "", "");
            case 14: case 15: case 16: case 17: return new NumberFormat(DATE, 0, false, "", "");
            case 18: case 19: case 20: case 21: case 45: case 46: case 47:
                return new NumberFormat(TIME, 0, false, "", "");
            case 22: return new NumberFormat(DATETIME, 0, false, "", "");
            case 49: return new NumberFormat(TEXT, 0, false, "", "");
            default: return GENERAL_FORMAT;
        }
    }

    /** Interprets the first section of a custom format code. */
    static NumberFormat parse(String code) {
        if (code == null) return GENERAL_FORMAT;
        int semi = sectionEnd(code);
        String sec = code.substring(0, semi);
        if (sec.trim().equalsIgnoreCase("general")) return GENERAL_FORMAT;

        StringBuilder pre = new StringBuilder();
        StringBuilder suf = new StringBuilder();
        boolean seenDigit = false;
        boolean yearOrDay = false, month = false, time = false;
        boolean percent = false, grouping = false, sci = false, text = false;
        int decimals = 0;
        boolean inDecimals = false;
        for (int i = 0; i < sec.length(); i++) {
            char c = sec.charAt(i);
            StringBuilder lit = seenDigit ? suf : pre;
            if (c == '"') {
                int j = sec.indexOf('"', i + 1);
                if (j < 0) j = sec.length();
                lit.append(sec, i + 1, j);
                i = j;
            } else if (c == '\\' && i + 1 < sec.length()) {
                lit.append(sec.charAt(++i));
            } else if (c == '[') {
                int j = sec.indexOf(']', i);
                if (j < 0) break;
                String b = sec.substring(i + 1, j);
                if (b.startsWith("$")) {
                    int dash = b.indexOf('-');
                    lit.append(dash < 0 ? b.substring(1) : b.substring(1, dash));
                } else if (b.matches("(?i)h+|m+|s+")) {
                    time = true;
                }
                i = j;
            } else if (c == '_' || c == '*') {
                i++;
                if (c == '_') lit.append(' ');
            } else if (c == '0' || c == '#' || c == '?') {
                seenDigit = true;
                if (inDecimals) decimals++;
            } else if (c == '.' && seenDigit) {
                inDecimals = true;
            } else if (c == ',' && seenDigit) {
                grouping = true;
            } else if (c == '%') {
                percent = true;
            } else if ((c == 'E' || c == 'e') && seenDigit) {
                sci = true;
            } else if ("yYdD".indexOf(c) >= 0) {
                yearOrDay = true;
            } else if ("hHsS".indexOf(c) >= 0) {
                time = true;
            } else if (c == 'm' || c == 'M') {
                // Months, or minutes when used together with hours/seconds.
                month = true;
            } else if (c == '@') {
                text = true;
            } else {
                lit.append(c);
            }
        }
        boolean date = yearOrDay || (month && !time);
        if (date || time) {
            int kind = date && time ? DATETIME : date ? DATE : TIME;
            return new NumberFormat(kind, 0, false, "", "");
        }
        if (text && !seenDigit) return new NumberFormat(TEXT, 0, false, "", "");
        if (sci || !seenDigit) return GENERAL_FORMAT;
        return new NumberFormat(percent ? PERCENT : FIXED, Math.min(decimals, 15), grouping,
                pre.toString(), suf.toString());
    }

    private static int sectionEnd(String code) {
        boolean quoted = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == '\\') i++;
            else if (c == ';' && !quoted) return i;
        }
        return code.length();
    }

    boolean isDate() {
        return kind == DATE || kind == TIME || kind == DATETIME;
    }

    String format(double v, boolean date1904) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "#NUM!";
        switch (kind) {
            case FIXED:
                return (prefix + decimal().format(v) + suffix).trim();
            case PERCENT:
                return (prefix + decimal().format(v * 100) + "%" + suffix).trim();
            case DATE:
            case TIME:
            case DATETIME:
                return formatDate(v, date1904);
            default:
                return general(v);
        }
    }

    private DecimalFormat decimal() {
        if (decimalFormat == null) {
            StringBuilder pattern = new StringBuilder(grouping ? "#,##0" : "0");
            if (decimals > 0) {
                pattern.append('.');
                for (int i = 0; i < decimals; i++) pattern.append('0');
            }
            decimalFormat = new DecimalFormat(pattern.toString(), DecimalFormatSymbols.getInstance());
        }
        return decimalFormat;
    }

    private String formatDate(double v, boolean date1904) {
        if (v < 0 || v > 2958465) return general(v);
        double days = v + (date1904 ? 1462 : 0);
        long millis = Math.round((days - 25569) * 86400000.0);
        if (dateFormat == null) {
            Locale l = Locale.getDefault();
            if (kind == DATE) dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, l);
            else if (kind == TIME) dateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM, l);
            else dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, l);
            dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        }
        return dateFormat.format(new Date(millis));
    }

    private static final ThreadLocal<DecimalFormat> GENERAL_DECIMAL = new ThreadLocal<>();

    /** Excel "General": up to 11 significant digits, scientific for very large/small values. */
    static String general(double v) {
        if (v == 0) return "0";
        double a = Math.abs(v);
        if (a >= 1e11 || a < 1e-9) {
            return new BigDecimal(v).round(new MathContext(6)).stripTrailingZeros().toString();
        }
        BigDecimal bd = new BigDecimal(v).round(new MathContext(11)).stripTrailingZeros();
        int scale = Math.max(0, bd.scale());
        if (scale == 0) return bd.toPlainString();
        DecimalFormat df = GENERAL_DECIMAL.get();
        if (df == null) {
            df = new DecimalFormat("0", DecimalFormatSymbols.getInstance());
            df.setGroupingUsed(false);
            GENERAL_DECIMAL.set(df);
        }
        df.setMaximumFractionDigits(scale);
        df.setMinimumFractionDigits(0);
        return df.format(bd);
    }
}
