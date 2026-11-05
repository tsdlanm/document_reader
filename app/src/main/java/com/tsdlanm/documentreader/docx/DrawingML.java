package com.tsdlanm.documentreader.docx;

import com.tsdlanm.documentreader.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * DrawingML parts shared by Word and PowerPoint shapes: colours and fills, preset geometries and
 * freeform (custGeom) outlines. Outlines are paths in fractions of the shape's box, as
 * {@link DocModel.TextBox#path} describes.
 */
public final class DrawingML {
    private DrawingML() {
    }

    /** Resolves theme colour names (accent1, tx1, ...) to ARGB. */
    public interface Scheme {
        int color(String name);
    }

    public static final int MOVE = 0, LINE = 1, CUBIC = 2, QUAD = 3, CLOSE = 4;

    /** A path in fractions of a box (see {@link #presetPath}) scaled into {@code l, t, w, h}. */
    public static android.graphics.Path toPath(float[] a, float l, float t, float w, float h,
                                               boolean flipH, boolean flipV) {
        android.graphics.Path out = new android.graphics.Path();
        int i = 0;
        float[] pt = new float[6];
        while (i < a.length) {
            int op = (int) a[i++];
            int n = op == CUBIC ? 3 : op == QUAD ? 2 : op == CLOSE ? 0 : 1;
            if (i + 2 * n > a.length) break;
            for (int k = 0; k < n; k++) {
                float fx = a[i++], fy = a[i++];
                pt[2 * k] = l + (flipH ? 1 - fx : fx) * w;
                pt[2 * k + 1] = t + (flipV ? 1 - fy : fy) * h;
            }
            if (op == MOVE) out.moveTo(pt[0], pt[1]);
            else if (op == LINE) out.lineTo(pt[0], pt[1]);
            else if (op == CUBIC) out.cubicTo(pt[0], pt[1], pt[2], pt[3], pt[4], pt[5]);
            else if (op == QUAD) out.quadTo(pt[0], pt[1], pt[2], pt[3]);
            else out.close();
        }
        return out;
    }

    /** A linear gradient filling the box at the gradient's angle. */
    public static android.graphics.Shader shader(DocModel.Gradient g, android.graphics.RectF rect) {
        int[] colors = g.colors.length > 1 ? g.colors : new int[]{g.colors[0], g.colors[0]};
        float[] pos = g.colors.length > 1 ? g.positions : null;
        if (g.radial) {
            // Stop 0 is the centre (the fillToRect point), stop 1 the far corner.
            float cx = rect.left + rect.width() * g.centerX, cy = rect.top + rect.height() * g.centerY;
            float rx = Math.max(cx - rect.left, rect.right - cx), ry = Math.max(cy - rect.top, rect.bottom - cy);
            float r = Math.max(1f, (float) Math.hypot(rx, ry));
            return new android.graphics.RadialGradient(cx, cy, r, colors, pos, android.graphics.Shader.TileMode.CLAMP);
        }
        double a = Math.toRadians(g.angle);
        float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
        float half = (Math.abs(rect.width() * dx) + Math.abs(rect.height() * dy)) / 2f;
        float cx = rect.centerX(), cy = rect.centerY();
        return new android.graphics.LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half,
                cy + dy * half, colors, pos, android.graphics.Shader.TileMode.CLAMP);
    }

    // ------------------------------------------------------------------ colours

    /**
     * A fill element (solidFill, gradFill, pattFill, noFill). Returns its colour, 0 for no fill, or
     * null (element skipped) for anything else.
     */
    public static Integer readFill(XmlPullParser p, Scheme scheme) throws XmlPullParserException, IOException {
        String n = p.getName();
        switch (n) {
            case "noFill":
                XmlUtil.skip(p);
                return 0;
            case "solidFill": {
                int c = 0;
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) c = readColor(p, scheme);
                return c;
            }
            case "gradFill": {
                // No gradients: the colour halfway along stands in (the stops' average).
                long r = 0, g = 0, b = 0;
                int count = 0;
                int depth = 1;
                while (depth > 0) {
                    int e = p.next();
                    if (e == XmlPullParser.START_TAG) {
                        if ("gs".equals(p.getName())) {
                            int d2 = p.getDepth();
                            int c = 0;
                            while (XmlUtil.nextChild(p, d2)) c = readColor(p, scheme);
                            if (c != 0) {
                                r += (c >> 16) & 0xFF;
                                g += (c >> 8) & 0xFF;
                                b += c & 0xFF;
                                count++;
                            }
                            continue;
                        }
                        depth++;
                    } else if (e == XmlPullParser.END_TAG) {
                        depth--;
                    } else if (e == XmlPullParser.END_DOCUMENT) {
                        break;
                    }
                }
                if (count == 0) return 0;
                return 0xFF000000 | (int) (r / count) << 16 | (int) (g / count) << 8 | (int) (b / count);
            }
            case "pattFill": {
                int c = 0;
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    boolean fg = "fgClr".equals(p.getName());
                    int d2 = p.getDepth();
                    int v = 0;
                    while (XmlUtil.nextChild(p, d2)) v = readColor(p, scheme);
                    if (fg) c = v;
                }
                return c;
            }
            default:
                XmlUtil.skip(p);
                return null;
        }
    }

    /**
     * A gradFill as stops (ARGB at positions 0..1) and an angle in degrees, clockwise from left to
     * right; null without stops. Leaves the parser on the element's end tag.
     */
    public static DocModel.Gradient readGradient(XmlPullParser p, Scheme scheme) throws XmlPullParserException, IOException {
        java.util.List<int[]> stops = new java.util.ArrayList<>();
        float angle = 90, cx = 0.5f, cy = 0.5f;
        boolean radial = false;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("gsLst".equals(n)) {
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if (!"gs".equals(p.getName())) {
                        XmlUtil.skip(p);
                        continue;
                    }
                    int pos = XmlUtil.intAttr(p, "pos", 0);
                    int c = 0;
                    int d3 = p.getDepth();
                    // Stops keep their transparency: fades to clear are common in slide designs.
                    while (XmlUtil.nextChild(p, d3)) c = readColor(p, scheme, true);
                    stops.add(new int[]{pos, c});
                }
            } else if ("lin".equals(n)) {
                angle = XmlUtil.intAttr(p, "ang", 5400000) / 60000f;
                XmlUtil.skip(p);
            } else if ("path".equals(n)) {
                // circle / rect / shape: drawn as a radial fade from the centre (or fillToRect).
                radial = true;
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if ("fillToRect".equals(p.getName())) {
                        float l = XmlUtil.intAttr(p, "l", 50000) / 100000f, t = XmlUtil.intAttr(p, "t", 50000) / 100000f;
                        float r = XmlUtil.intAttr(p, "r", 50000) / 100000f, b = XmlUtil.intAttr(p, "b", 50000) / 100000f;
                        cx = (l + 1 - r) / 2f;
                        cy = (t + 1 - b) / 2f;
                    }
                    XmlUtil.skip(p);
                }
            } else {
                XmlUtil.skip(p);
            }
        }
        if (stops.isEmpty()) return null;
        java.util.Collections.sort(stops, (a, b) -> Integer.compare(a[0], b[0]));
        DocModel.Gradient g = new DocModel.Gradient();
        g.colors = new int[stops.size()];
        g.positions = new float[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            g.positions[i] = Math.max(0, Math.min(1, stops.get(i)[0] / 100000f));
            g.colors[i] = stops.get(i)[1];
        }
        g.angle = angle;
        g.radial = radial;
        g.centerX = cx;
        g.centerY = cy;
        boolean visible = false;
        for (int c : g.colors) if ((c >>> 24) != 0) visible = true;
        return visible ? g : null;
    }

    /** A colour element with its modifiers; 0 when unknown or fully transparent. */
    public static int readColor(XmlPullParser p, Scheme scheme) throws XmlPullParserException, IOException {
        return readColor(p, scheme, false);
    }

    /**
     * {@code keepAlpha}: the colour carries its a:alpha (gradient stops); otherwise mostly
     * transparent colours count as none and the rest as opaque.
     */
    public static int readColor(XmlPullParser p, Scheme scheme, boolean keepAlpha)
            throws XmlPullParserException, IOException {
        String n = p.getName();
        int c;
        switch (n) {
            case "srgbClr":
                c = XmlUtil.hexColor(XmlUtil.attr(p, "val"));
                break;
            case "sysClr":
                c = XmlUtil.hexColor(XmlUtil.attr(p, "lastClr"));
                if (c == 0) c = "window".equals(XmlUtil.attr(p, "val")) ? 0xFFFFFFFF : 0xFF000000;
                break;
            case "schemeClr":
                c = scheme.color(XmlUtil.attr(p, "val"));
                break;
            case "prstClr":
                c = preset(XmlUtil.attr(p, "val"));
                break;
            case "scrgbClr":
                c = 0xFF000000 | (pct255(p, "r") << 16) | (pct255(p, "g") << 8) | pct255(p, "b");
                break;
            default:
                XmlUtil.skip(p);
                return 0;
        }
        int d = p.getDepth();
        int alpha = 255;
        while (XmlUtil.nextChild(p, d)) {
            String m = p.getName();
            int v = XmlUtil.intAttr(p, "val", 100000);
            XmlUtil.skip(p);
            if (c == 0) continue;
            if (keepAlpha && "alpha".equals(m)) {
                alpha = Math.max(0, Math.min(255, Math.round(v / 100000f * 255)));
                continue;
            }
            switch (m) {
                case "lumMod":
                    c = lum(c, v / 100000f, 0f);
                    break;
                case "lumOff":
                    c = lum(c, 1f, v / 100000f);
                    break;
                case "tint":
                    c = mixLinear(c, 1f, 1f - v / 100000f);
                    break;
                case "shade":
                    c = mixLinear(c, 0f, 1f - v / 100000f);
                    break;
                case "alpha":
                    // Mostly transparent colours are treated as no colour.
                    if (v < 15000) c = 0;
                    break;
                default:
                    break;
            }
        }
        if (keepAlpha && c != 0) c = (c & 0x00FFFFFF) | (alpha << 24);
        return c;
    }

    private static int pct255(XmlPullParser p, String name) {
        long v = XmlUtil.parseInt(XmlUtil.attr(p, name), 0);
        return (int) Math.max(0, Math.min(255, Math.round(v / 100000.0 * 255)));
    }

    /** Office's default theme, for theme colours a document does not define. */
    public static int defaultScheme(String name) {
        if (name == null) return 0;
        switch (name) {
            case "lt1":
            case "bg1":
                return 0xFFFFFFFF;
            case "dk1":
            case "tx1":
                return 0xFF000000;
            case "lt2":
            case "bg2":
                return 0xFFE7E6E6;
            case "dk2":
            case "tx2":
                return 0xFF44546A;
            case "accent2":
                return 0xFFED7D31;
            case "accent3":
                return 0xFFA5A5A5;
            case "accent4":
                return 0xFFFFC000;
            case "accent5":
                return 0xFF5B9BD5;
            case "accent6":
                return 0xFF70AD47;
            case "hlink":
                return 0xFF0563C1;
            default:
                return 0xFF4472C4;
        }
    }

    private static int preset(String v) {
        if (v == null) return 0;
        switch (v) {
            case "white":
                return 0xFFFFFFFF;
            case "red":
                return 0xFFFF0000;
            case "green":
                return 0xFF008000;
            case "blue":
                return 0xFF0000FF;
            case "yellow":
                return 0xFFFFFF00;
            case "gray":
            case "grey":
                return 0xFF808080;
            default:
                return 0xFF000000;
        }
    }

    /** Scales and shifts the HSL lightness, as DrawingML's lumMod / lumOff. */
    static int lum(int c, float mod, float off) {
        float r = ((c >> 16) & 0xFF) / 255f, g = ((c >> 8) & 0xFF) / 255f, b = (c & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f, h = 0f, s = 0f;
        if (max != min) {
            float dd = max - min;
            s = l > 0.5f ? dd / (2f - max - min) : dd / (max + min);
            if (max == r) h = (g - b) / dd + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / dd + 2;
            else h = (r - g) / dd + 4;
            h /= 6f;
        }
        l = Math.max(0f, Math.min(1f, l * mod + off));
        float rr, gg, bb;
        if (s == 0) {
            rr = gg = bb = l;
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float pp = 2 * l - q;
            rr = hue(pp, q, h + 1f / 3);
            gg = hue(pp, q, h);
            bb = hue(pp, q, h - 1f / 3);
        }
        return 0xFF000000 | (Math.round(rr * 255) << 16) | (Math.round(gg * 255) << 8) | Math.round(bb * 255);
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 1f / 2) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    /**
     * Tint (towards white, {@code to} 1) or shade (towards black, 0) as Office does it: in linear
     * light, so a 75% tint of black is a mid grey rather than a dark one.
     */
    static int mixLinear(int c, float to, float amount) {
        int r = toSrgb(toLinear((c >> 16) & 0xFF) * (1 - amount) + to * amount);
        int g = toSrgb(toLinear((c >> 8) & 0xFF) * (1 - amount) + to * amount);
        int b = toSrgb(toLinear(c & 0xFF) * (1 - amount) + to * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float toLinear(int v) {
        float s = v / 255f;
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055f) / 1.055f, 2.4);
    }

    private static int toSrgb(float l) {
        l = Math.max(0f, Math.min(1f, l));
        float s = l <= 0.0031308f ? l * 12.92f : (float) (1.055 * Math.pow(l, 1 / 2.4) - 0.055);
        return Math.round(s * 255);
    }

    static int mix(int c, int with, float amount) {
        int r = Math.round(((c >> 16) & 0xFF) * (1 - amount) + ((with >> 16) & 0xFF) * amount);
        int g = Math.round(((c >> 8) & 0xFF) * (1 - amount) + ((with >> 8) & 0xFF) * amount);
        int b = Math.round((c & 0xFF) * (1 - amount) + (with & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // --------------------------------------------------------------- geometry

    /** GEOM_* for a preset geometry; GEOM_PATH when {@link #presetPath} has its outline. */
    public static int geometry(String prst) {
        if (prst == null) return DocModel.GEOM_RECT;
        switch (prst) {
            case "roundRect":
            case "round1Rect":
            case "round2SameRect":
            case "snipRoundRect":
            case "flowChartAlternateProcess":
                return DocModel.GEOM_ROUND_RECT;
            case "ellipse":
            case "flowChartConnector":
            case "donut":
                return DocModel.GEOM_ELLIPSE;
            case "line":
            case "straightConnector1":
            case "bentConnector2":
            case "bentConnector3":
            case "curvedConnector3":
                return DocModel.GEOM_LINE;
            default:
                return presetPath(prst) != null ? DocModel.GEOM_PATH : DocModel.GEOM_RECT;
        }
    }

    /** Outline of common preset shapes at their default adjustments, or null. */
    public static float[] presetPath(String prst) {
        if (prst == null) return null;
        switch (prst) {
            case "triangle":
            case "flowChartExtract":
                return poly(0.5f, 0, 1, 1, 0, 1);
            case "flowChartMerge":
                return poly(0, 0, 1, 0, 0.5f, 1);
            case "rtTriangle":
                return poly(0, 0, 1, 1, 0, 1);
            case "diamond":
            case "flowChartDecision":
                return poly(0.5f, 0, 1, 0.5f, 0.5f, 1, 0, 0.5f);
            case "parallelogram":
            case "flowChartInputOutput":
                return poly(0.25f, 0, 1, 0, 0.75f, 1, 0, 1);
            case "trapezoid":
                return poly(0.25f, 0, 0.75f, 0, 1, 1, 0, 1);
            case "pentagon":
            case "homePlate":
                return poly(0, 0, 0.8f, 0, 1, 0.5f, 0.8f, 1, 0, 1);
            case "chevron":
                return poly(0, 0, 0.8f, 0, 1, 0.5f, 0.8f, 1, 0, 1, 0.2f, 0.5f);
            case "hexagon":
                return poly(0.25f, 0, 0.75f, 0, 1, 0.5f, 0.75f, 1, 0.25f, 1, 0, 0.5f);
            case "octagon":
            case "flowChartPunchedCard":
                return poly(0.29f, 0, 0.71f, 0, 1, 0.29f, 1, 0.71f, 0.71f, 1, 0.29f, 1, 0, 0.71f, 0, 0.29f);
            case "snip1Rect":
                return poly(0, 0, 0.83f, 0, 1, 0.17f, 1, 1, 0, 1);
            case "snip2SameRect":
                return poly(0.17f, 0, 0.83f, 0, 1, 0.17f, 1, 1, 0, 1, 0, 0.17f);
            case "flowChartManualInput":
                return poly(0, 0.2f, 1, 0, 1, 1, 0, 1);
            case "rightArrow":
                return poly(0, 0.25f, 0.6f, 0.25f, 0.6f, 0, 1, 0.5f, 0.6f, 1, 0.6f, 0.75f, 0, 0.75f);
            case "leftArrow":
                return poly(1, 0.25f, 0.4f, 0.25f, 0.4f, 0, 0, 0.5f, 0.4f, 1, 0.4f, 0.75f, 1, 0.75f);
            case "upArrow":
                return poly(0.25f, 1, 0.25f, 0.4f, 0, 0.4f, 0.5f, 0, 1, 0.4f, 0.75f, 0.4f, 0.75f, 1);
            case "downArrow":
                return poly(0.25f, 0, 0.25f, 0.6f, 0, 0.6f, 0.5f, 1, 1, 0.6f, 0.75f, 0.6f, 0.75f, 0);
            case "plus":
            case "mathPlus":
                return poly(0.33f, 0, 0.67f, 0, 0.67f, 0.33f, 1, 0.33f, 1, 0.67f, 0.67f, 0.67f, 0.67f, 1,
                        0.33f, 1, 0.33f, 0.67f, 0, 0.67f, 0, 0.33f, 0.33f, 0.33f);
            case "leftBrace":
                return new float[]{MOVE, 1, 0, CUBIC, 0.75f, 0, 0.5f, 0.02f, 0.5f, 0.083f, LINE, 0.5f, 0.417f,
                        CUBIC, 0.5f, 0.48f, 0.25f, 0.5f, 0, 0.5f, CUBIC, 0.25f, 0.5f, 0.5f, 0.52f, 0.5f, 0.583f,
                        LINE, 0.5f, 0.917f, CUBIC, 0.5f, 0.98f, 0.75f, 1, 1, 1};
            case "rightBrace":
                return new float[]{MOVE, 0, 0, CUBIC, 0.25f, 0, 0.5f, 0.02f, 0.5f, 0.083f, LINE, 0.5f, 0.417f,
                        CUBIC, 0.5f, 0.48f, 0.75f, 0.5f, 1, 0.5f, CUBIC, 0.75f, 0.5f, 0.5f, 0.52f, 0.5f, 0.583f,
                        LINE, 0.5f, 0.917f, CUBIC, 0.5f, 0.98f, 0.25f, 1, 0, 1};
            case "leftBracket":
                return new float[]{MOVE, 1, 0, QUAD, 0, 0, 0, 0.08f, LINE, 0, 0.92f, QUAD, 0, 1, 1, 1};
            case "rightBracket":
                return new float[]{MOVE, 0, 0, QUAD, 1, 0, 1, 0.08f, LINE, 1, 0.92f, QUAD, 1, 1, 0, 1};
            case "star5": {
                float[] xy = new float[20];
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 0.5f : 0.19f;
                    xy[2 * i] = 0.5f + (float) (r * Math.cos(a));
                    xy[2 * i + 1] = 0.5f + (float) (r * Math.sin(a)) * 1.05f;
                }
                return poly(xy);
            }
            default:
                return null;
        }
    }

    private static float[] poly(float... xy) {
        int n = xy.length / 2;
        float[] out = new float[n * 3 + 1];
        int k = 0;
        for (int i = 0; i < n; i++) {
            out[k++] = i == 0 ? MOVE : LINE;
            out[k++] = xy[2 * i];
            out[k++] = xy[2 * i + 1];
        }
        out[k] = CLOSE;
        return out;
    }

    /**
     * Reads a:custGeom into a path in fractions of the shape's box; null when it has no path.
     * Leaves the parser on the element's end tag. Guides and adjustments are not evaluated: points
     * given by guide names (rare outside presets) count as 0.
     */
    public static float[] readCustGeom(XmlPullParser p) throws XmlPullParserException, IOException {
        FloatList out = new FloatList();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if (!"pathLst".equals(p.getName())) {
                XmlUtil.skip(p);
                continue;
            }
            int d2 = p.getDepth();
            while (XmlUtil.nextChild(p, d2)) {
                if (!"path".equals(p.getName())) {
                    XmlUtil.skip(p);
                    continue;
                }
                float w = Math.max(1, XmlUtil.parseInt(XmlUtil.attr(p, "w"), 0));
                float h = Math.max(1, XmlUtil.parseInt(XmlUtil.attr(p, "h"), 0));
                boolean noFill = "none".equals(XmlUtil.attr(p, "fill"));
                readPath(p, w, h, noFill, out);
            }
        }
        return out.size == 0 ? null : out.toArray();
    }

    private static void readPath(XmlPullParser p, float w, float h, boolean noFill, FloatList out)
            throws XmlPullParserException, IOException {
        // Current point, for arcs.
        float cx = 0, cy = 0;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "moveTo":
                case "lnTo": {
                    float[] pt = points(p, w, h, 1);
                    out.add(n.equals("moveTo") ? MOVE : LINE);
                    out.add(pt[0]);
                    out.add(pt[1]);
                    cx = pt[0];
                    cy = pt[1];
                    break;
                }
                case "cubicBezTo": {
                    float[] pt = points(p, w, h, 3);
                    out.add(CUBIC);
                    for (float v : pt) out.add(v);
                    cx = pt[4];
                    cy = pt[5];
                    break;
                }
                case "quadBezTo": {
                    float[] pt = points(p, w, h, 2);
                    out.add(QUAD);
                    for (float v : pt) out.add(v);
                    cx = pt[2];
                    cy = pt[3];
                    break;
                }
                case "arcTo": {
                    // Elliptical arc from the current point: radii and angles in 60000ths of a degree.
                    float wr = XmlUtil.parseInt(XmlUtil.attr(p, "wR"), 0) / w;
                    float hr = XmlUtil.parseInt(XmlUtil.attr(p, "hR"), 0) / h;
                    double st = Math.toRadians(XmlUtil.parseInt(XmlUtil.attr(p, "stAng"), 0) / 60000.0);
                    double sw = Math.toRadians(XmlUtil.parseInt(XmlUtil.attr(p, "swAng"), 0) / 60000.0);
                    XmlUtil.skip(p);
                    float ox = cx - (float) (wr * Math.cos(st)), oy = cy - (float) (hr * Math.sin(st));
                    int steps = Math.max(2, (int) Math.ceil(Math.abs(sw) / (Math.PI / 12)));
                    for (int i = 1; i <= steps; i++) {
                        double a = st + sw * i / steps;
                        cx = ox + (float) (wr * Math.cos(a));
                        cy = oy + (float) (hr * Math.sin(a));
                        out.add(LINE);
                        out.add(cx);
                        out.add(cy);
                    }
                    break;
                }
                case "close":
                    XmlUtil.skip(p);
                    if (!noFill) out.add(CLOSE);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
    }

    /** The a:pt children of a path command, as fractions of the path box. */
    private static float[] points(XmlPullParser p, float w, float h, int count)
            throws XmlPullParserException, IOException {
        float[] out = new float[count * 2];
        int i = 0;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("pt".equals(p.getName()) && i < count) {
                out[2 * i] = XmlUtil.parseInt(XmlUtil.attr(p, "x"), 0) / w;
                out[2 * i + 1] = XmlUtil.parseInt(XmlUtil.attr(p, "y"), 0) / h;
                i++;
            }
            XmlUtil.skip(p);
        }
        return out;
    }

    private static final class FloatList {
        float[] a = new float[64];
        int size;

        void add(float v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(a, size);
        }
    }
}
