package com.tsdlanm.documentreader.xlsx;

import com.tsdlanm.documentreader.docx.DocModel;
import com.tsdlanm.documentreader.docx.DrawingML;
import com.tsdlanm.documentreader.util.SymbolFonts;
import com.tsdlanm.documentreader.util.XmlUtil;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shapes drawn on a sheet (xdr:sp, xdr:cxnSp, xdr:grpSp): boxes and buttons with text, lines and
 * arrows. Their geometry, fills and colours are read as in Word and PowerPoint.
 */
final class XlsxShapes {
    private static final float EMU_PER_PT = 12700f;

    private final DrawingML.Scheme scheme;

    /** {@code theme}: colours in SpreadsheetML order (lt1, dk1, lt2, dk2, accent1..6, hlink, folHlink). */
    XlsxShapes(final int[] theme) {
        scheme = name -> {
            int i = index(name);
            return theme != null && i >= 0 && i < theme.length ? theme[i] : DrawingML.defaultScheme(name);
        };
    }

    private static int index(String name) {
        if (name == null) return -1;
        switch (name) {
            case "lt1":
            case "bg1":
                return 0;
            case "dk1":
            case "tx1":
                return 1;
            case "lt2":
            case "bg2":
                return 2;
            case "dk2":
            case "tx2":
                return 3;
            case "hlink":
                return 10;
            case "folHlink":
                return 11;
            default:
                if (name.startsWith("accent") && name.length() == 7) {
                    int n = name.charAt(6) - '0';
                    if (n >= 1 && n <= 6) return 3 + n;
                }
                return -1;
        }
    }

    /** Reads the shape or group the parser is on into {@code out}; leaves it on the end tag. */
    void read(XmlPullParser p, List<SheetData.Shape> out) throws XmlPullParserException, IOException {
        read(p, null, out);
    }

    /**
     * {@code map}: child coordinates to fractions of the anchor's box (ax, bx, ay, by: frac = a * v + b),
     * or null for a shape that fills the anchor.
     */
    private void read(XmlPullParser p, double[] map, List<SheetData.Shape> out)
            throws XmlPullParserException, IOException {
        String n = p.getName();
        if ("grpSp".equals(n)) {
            readGroup(p, map, out);
        } else if ("sp".equals(n) || "cxnSp".equals(n)) {
            SheetData.Shape s = readShape(p, map, "cxnSp".equals(n));
            if (s != null) out.add(s);
        } else {
            XmlUtil.skip(p);
        }
    }

    private void readGroup(XmlPullParser p, double[] map, List<SheetData.Shape> out)
            throws XmlPullParserException, IOException {
        double[] inner = null;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("grpSpPr".equals(n)) {
                long[] x = readGroupXfrm(p);
                if (x != null) {
                    double sx = x[6] == 0 ? 1 : (double) x[2] / x[6];
                    double sy = x[7] == 0 ? 1 : (double) x[3] / x[7];
                    if (map == null) {
                        // The group's box is the anchor.
                        double ax = x[6] == 0 ? 0 : 1.0 / x[6], ay = x[7] == 0 ? 0 : 1.0 / x[7];
                        inner = new double[]{ax, -x[4] * ax, ay, -x[5] * ay};
                    } else {
                        inner = new double[]{map[0] * sx, map[0] * (x[0] - x[4] * sx) + map[1],
                                map[2] * sy, map[2] * (x[1] - x[5] * sy) + map[3]};
                    }
                }
            } else if ("sp".equals(n) || "cxnSp".equals(n) || "grpSp".equals(n)) {
                read(p, inner != null ? inner : map, out);
            } else {
                XmlUtil.skip(p);
            }
        }
    }

    /** off x, y, ext cx, cy, chOff x, y, chExt cx, cy of a grpSpPr; null without xfrm. */
    private static long[] readGroupXfrm(XmlPullParser p) throws XmlPullParserException, IOException {
        long[] x = null;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if (!"xfrm".equals(p.getName())) {
                XmlUtil.skip(p);
                continue;
            }
            x = new long[8];
            int d2 = p.getDepth();
            while (XmlUtil.nextChild(p, d2)) {
                String n = p.getName();
                int k = "off".equals(n) ? 0 : "ext".equals(n) ? 2 : "chOff".equals(n) ? 4 : "chExt".equals(n) ? 6 : -1;
                if (k >= 0) {
                    boolean pos = k == 0 || k == 4;
                    x[k] = parseLong(XmlUtil.attr(p, pos ? "x" : "cx"));
                    x[k + 1] = parseLong(XmlUtil.attr(p, pos ? "y" : "cy"));
                }
                XmlUtil.skip(p);
            }
        }
        return x;
    }

    private SheetData.Shape readShape(XmlPullParser p, double[] map, boolean connector)
            throws XmlPullParserException, IOException {
        SheetData.Shape s = new SheetData.Shape();
        long offX = 0, offY = 0, extX = 0, extY = 0;
        Integer fill = null;
        DocModel.Gradient gradient = null;
        Integer line = null;
        boolean hasLine = false;
        int fillRef = 0, lineRef = 0, fillRefIdx = 0, lineRefIdx = 0, fontColor = 0xFF000000;
        String prst = null;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "spPr": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String m = p.getName();
                        switch (m) {
                            case "xfrm": {
                                s.rotation = XmlUtil.intAttr(p, "rot", 0) / 60000f;
                                s.flipH = "1".equals(XmlUtil.attr(p, "flipH"));
                                s.flipV = "1".equals(XmlUtil.attr(p, "flipV"));
                                int d3 = p.getDepth();
                                while (XmlUtil.nextChild(p, d3)) {
                                    if ("off".equals(p.getName())) {
                                        offX = parseLong(XmlUtil.attr(p, "x"));
                                        offY = parseLong(XmlUtil.attr(p, "y"));
                                    } else if ("ext".equals(p.getName())) {
                                        extX = parseLong(XmlUtil.attr(p, "cx"));
                                        extY = parseLong(XmlUtil.attr(p, "cy"));
                                    }
                                    XmlUtil.skip(p);
                                }
                                break;
                            }
                            case "prstGeom":
                                prst = XmlUtil.attr(p, "prst");
                                XmlUtil.skip(p);
                                break;
                            case "custGeom":
                                s.path = DrawingML.readCustGeom(p);
                                prst = s.path != null ? "" : "rect";
                                break;
                            case "gradFill":
                                gradient = DrawingML.readGradient(p, scheme);
                                fill = gradient != null ? gradient.average() : 0;
                                break;
                            case "noFill":
                            case "solidFill":
                            case "pattFill":
                                fill = DrawingML.readFill(p, scheme);
                                break;
                            case "ln": {
                                hasLine = true;
                                int w = XmlUtil.intAttr(p, "w", -1);
                                if (w >= 0) s.lineWidth = w / EMU_PER_PT;
                                int d3 = p.getDepth();
                                while (XmlUtil.nextChild(p, d3)) {
                                    String k = p.getName();
                                    if ("noFill".equals(k) || "solidFill".equals(k) || "gradFill".equals(k)
                                            || "pattFill".equals(k)) {
                                        line = DrawingML.readFill(p, scheme);
                                    } else {
                                        XmlUtil.skip(p);
                                    }
                                }
                                break;
                            }
                            default:
                                XmlUtil.skip(p);
                        }
                    }
                    break;
                }
                case "style": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String m = p.getName();
                        int idx = XmlUtil.intAttr(p, "idx", 0);
                        int c = 0;
                        int d3 = p.getDepth();
                        while (XmlUtil.nextChild(p, d3)) c = DrawingML.readColor(p, scheme);
                        if ("lnRef".equals(m)) {
                            lineRef = c;
                            lineRefIdx = idx;
                        } else if ("fillRef".equals(m)) {
                            fillRef = c;
                            fillRefIdx = idx;
                        } else if ("fontRef".equals(m) && c != 0) {
                            fontColor = c;
                        }
                    }
                    break;
                }
                case "txBody":
                    s.text = readText(p, s, fontColor);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
        if (connector) {
            s.geometry = DocModel.GEOM_LINE;
        } else if (s.path != null) {
            s.geometry = DocModel.GEOM_PATH;
        } else {
            s.geometry = DrawingML.geometry(prst);
            if (s.geometry == DocModel.GEOM_PATH) s.path = DrawingML.presetPath(prst);
        }
        if (s.geometry == DocModel.GEOM_LINE) {
            s.fill = 0;
        } else if (fill != null) {
            s.fill = fill;
            s.gradient = gradient;
        } else if (fillRefIdx > 0) {
            s.fill = fillRef;
        }
        if (line != null) s.line = line;
        else if (lineRefIdx > 0) s.line = lineRef;
        else if (hasLine && s.geometry == DocModel.GEOM_LINE) s.line = 0xFF000000;
        if (map != null) {
            s.x0 = (float) (map[0] * offX + map[1]);
            s.x1 = (float) (map[0] * (offX + extX) + map[1]);
            s.y0 = (float) (map[2] * offY + map[3]);
            s.y1 = (float) (map[2] * (offY + extY) + map[3]);
        }
        boolean hasText = false;
        if (s.text != null) {
            for (SheetData.Para para : s.text) {
                for (SheetData.Run r : para.runs) {
                    if (r.text.trim().length() > 0) hasText = true;
                }
            }
            if (!hasText) s.text = null;
        }
        if (s.fill == 0 && s.line == 0 && !hasText) return null;
        return s;
    }

    private List<SheetData.Para> readText(XmlPullParser p, SheetData.Shape s, int fontColor)
            throws XmlPullParserException, IOException {
        List<SheetData.Para> paras = new ArrayList<>();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("bodyPr".equals(n)) {
                String a = XmlUtil.attr(p, "anchor");
                s.anchor = "ctr".equals(a) ? 1 : "b".equals(a) ? 2 : 0;
                s.insetL = inset(p, "lIns", s.insetL);
                s.insetT = inset(p, "tIns", s.insetT);
                s.insetR = inset(p, "rIns", s.insetR);
                s.insetB = inset(p, "bIns", s.insetB);
                XmlUtil.skip(p);
            } else if ("p".equals(n)) {
                paras.add(readPara(p, fontColor));
            } else {
                XmlUtil.skip(p);
            }
        }
        return paras;
    }

    private static float inset(XmlPullParser p, String name, float def) {
        String v = XmlUtil.attr(p, name);
        return v == null ? def : parseLong(v) / EMU_PER_PT;
    }

    private SheetData.Para readPara(XmlPullParser p, int fontColor) throws XmlPullParserException, IOException {
        SheetData.Para para = new SheetData.Para();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "pPr": {
                    String a = XmlUtil.attr(p, "algn");
                    para.align = "ctr".equals(a) ? 2 : "r".equals(a) ? 1 : 0;
                    XmlUtil.skip(p);
                    break;
                }
                case "r":
                case "fld": {
                    SheetData.Run r = new SheetData.Run();
                    r.color = fontColor;
                    StringBuilder sb = new StringBuilder();
                    String font = null;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String m = p.getName();
                        if ("rPr".equals(m)) {
                            font = readRunProps(p, r);
                        } else if ("t".equals(m)) {
                            sb.append(XmlUtil.text(p));
                        } else {
                            XmlUtil.skip(p);
                        }
                    }
                    r.text = SymbolFonts.isSymbolFont(font) ? SymbolFonts.map(font, sb) : sb.toString();
                    para.runs.add(r);
                    para.size = r.size;
                    break;
                }
                case "br": {
                    SheetData.Run r = new SheetData.Run();
                    r.text = "\n";
                    r.size = para.size;
                    XmlUtil.skip(p);
                    para.runs.add(r);
                    break;
                }
                case "endParaRPr": {
                    int sz = XmlUtil.intAttr(p, "sz", 0);
                    if (sz > 0 && para.runs.isEmpty()) para.size = sz / 100f;
                    XmlUtil.skip(p);
                    break;
                }
                default:
                    XmlUtil.skip(p);
            }
        }
        return para;
    }

    /** Reads a:rPr into {@code r}; returns its latin / symbol typeface. */
    private String readRunProps(XmlPullParser p, SheetData.Run r) throws XmlPullParserException, IOException {
        int sz = XmlUtil.intAttr(p, "sz", 0);
        if (sz > 0) r.size = sz / 100f;
        r.bold = "1".equals(XmlUtil.attr(p, "b"));
        r.italic = "1".equals(XmlUtil.attr(p, "i"));
        String u = XmlUtil.attr(p, "u");
        r.underline = u != null && !"none".equals(u);
        String font = null;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("solidFill".equals(n)) {
                Integer c = DrawingML.readFill(p, scheme);
                if (c != null && c != 0) r.color = c;
            } else if ("sym".equals(n) || "latin".equals(n)) {
                String f = XmlUtil.attr(p, "typeface");
                if ("sym".equals(n) || font == null) font = f;
                XmlUtil.skip(p);
            } else {
                XmlUtil.skip(p);
            }
        }
        return font;
    }

    private static long parseLong(String v) {
        if (v == null) return 0;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
