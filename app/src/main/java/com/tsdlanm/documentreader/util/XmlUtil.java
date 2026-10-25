package com.tsdlanm.documentreader.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public final class XmlUtil {
    private XmlUtil() {
    }

    /** Returns the value of the attribute with this local name, ignoring its namespace. */
    public static String attr(XmlPullParser p, String localName) {
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            if (localName.equals(p.getAttributeName(i))) return p.getAttributeValue(i);
        }
        return null;
    }

    public static int intAttr(XmlPullParser p, String localName, int fallback) {
        return parseInt(attr(p, localName), fallback);
    }

    public static float floatAttr(XmlPullParser p, String localName, float fallback) {
        String v = attr(p, localName);
        if (v == null) return fallback;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseInt(String v, int fallback) {
        if (v == null || v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(v);
            } catch (NumberFormatException e2) {
                return fallback;
            }
        }
    }

    /** OOXML on/off value: absent, "1", "true" and "on" mean on. */
    public static boolean toggle(XmlPullParser p) {
        String v = attr(p, "val");
        return v == null || !("0".equals(v) || "false".equals(v) || "off".equals(v) || "none".equals(v));
    }

    /** Skips the element the parser is positioned on, including all children. */
    public static void skip(XmlPullParser p) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) depth++;
            else if (e == XmlPullParser.END_TAG) depth--;
            else if (e == XmlPullParser.END_DOCUMENT) return;
        }
    }

    /**
     * Advances to the next child start tag of the element at {@code depth}. Returns false once the
     * element's end tag is reached.
     */
    public static boolean nextChild(XmlPullParser p, int depth) throws XmlPullParserException, IOException {
        while (true) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) return true;
            if (e == XmlPullParser.END_DOCUMENT) return false;
            if (e == XmlPullParser.END_TAG && p.getDepth() <= depth) return false;
        }
    }

    /** Collects all text inside the current element and leaves the parser on its end tag. */
    public static String text(XmlPullParser p) throws XmlPullParserException, IOException {
        StringBuilder sb = null;
        String single = null;
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) depth++;
            else if (e == XmlPullParser.END_TAG) depth--;
            else if (e == XmlPullParser.TEXT || e == XmlPullParser.ENTITY_REF || e == XmlPullParser.CDSECT) {
                String t = p.getText();
                if (t == null) continue;
                if (single == null && sb == null) {
                    single = t;
                } else {
                    if (sb == null) sb = new StringBuilder(single);
                    sb.append(t);
                }
            } else if (e == XmlPullParser.END_DOCUMENT) break;
        }
        if (sb != null) return sb.toString();
        return single == null ? "" : single;
    }

    /** Parses an OOXML hex colour (RRGGBB or AARRGGBB). Returns 0 when absent or "auto". */
    public static int hexColor(String v) {
        if (v == null) return 0;
        try {
            if (v.length() == 6) return 0xFF000000 | Integer.parseInt(v, 16);
            if (v.length() == 8) return 0xFF000000 | (int) (Long.parseLong(v, 16) & 0xFFFFFF);
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }
}
