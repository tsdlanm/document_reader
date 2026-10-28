package com.tsdlanm.documentreader.docx;

import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Border;
import com.tsdlanm.documentreader.docx.DocModel.Cell;
import com.tsdlanm.documentreader.docx.DocModel.Document;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Row;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.Section;
import com.tsdlanm.documentreader.docx.DocModel.SectionBreak;
import com.tsdlanm.documentreader.docx.DocModel.Table;
import com.tsdlanm.documentreader.docx.DocModel.TextBox;
import com.tsdlanm.documentreader.util.XmlFactory;
import com.tsdlanm.documentreader.util.XmlUtil;
import com.tsdlanm.documentreader.util.ZipParts;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Reads a PowerPoint (.pptx) file into the same model as a Word document: every slide becomes a
 * page of the slide's size holding its shapes as floating text boxes and pictures, so the print
 * layout view shows, zooms and selects slides like pages. Placeholders inherit their position and
 * text style from the slide layout and master, as in PowerPoint.
 */
public final class PptxParser {
    /** Twips per EMU divisor: 1 twip = 635 EMU. */
    private static final int EMU_PER_TWIP = 635;
    private static final int DEFAULT_SIZE = 1800;

    private final ZipFile zip;
    private final XmlFactory xml;
    private final String chartLabel;
    private final Map<String, Master> masters = new HashMap<>();
    private final Map<String, Layout> layouts = new HashMap<>();
    private String presentationPart;

    private PptxParser(ZipFile zip, XmlFactory xml, String chartLabel) {
        this.zip = zip;
        this.xml = xml;
        this.chartLabel = chartLabel;
    }

    public static boolean isPptx(ZipFile zip) {
        return zip.getEntry("ppt/presentation.xml") != null;
    }

    /** {@code chartLabel} is shown in place of charts and diagrams, which are not drawn. */
    public static Document parse(ZipFile zip, XmlFactory xml, String chartLabel)
            throws IOException, XmlPullParserException {
        return new PptxParser(zip, xml, chartLabel).read();
    }

    // ================================================================ model

    /** Paragraph and character properties at one list level; null fields are inherited. */
    static final class Props {
        Integer algn, marL, indent, lnPct, lnPts, befPts, befPct, aftPts, aftPct;
        /** 0 no bullet, 1 character, 2 automatic number. */
        Integer buType;
        String buChar, buFont, buAuto;
        Integer buStart;
        Integer sz, color, baseline, spc;
        Boolean b, i, u, strike;
        String cap, latin;

        void over(Props o) {
            if (o == null) return;
            if (o.algn != null) algn = o.algn;
            if (o.marL != null) marL = o.marL;
            if (o.indent != null) indent = o.indent;
            if (o.lnPct != null || o.lnPts != null) {
                lnPct = o.lnPct;
                lnPts = o.lnPts;
            }
            if (o.befPct != null || o.befPts != null) {
                befPct = o.befPct;
                befPts = o.befPts;
            }
            if (o.aftPct != null || o.aftPts != null) {
                aftPct = o.aftPct;
                aftPts = o.aftPts;
            }
            if (o.buType != null) {
                buType = o.buType;
                if (o.buChar != null) buChar = o.buChar;
                if (o.buAuto != null) buAuto = o.buAuto;
                if (o.buStart != null) buStart = o.buStart;
            }
            if (o.buFont != null) buFont = o.buFont;
            overRun(o);
        }

        void overRun(Props o) {
            if (o == null) return;
            if (o.sz != null) sz = o.sz;
            if (o.color != null) color = o.color;
            if (o.baseline != null) baseline = o.baseline;
            if (o.spc != null) spc = o.spc;
            if (o.b != null) b = o.b;
            if (o.i != null) i = o.i;
            if (o.u != null) u = o.u;
            if (o.strike != null) strike = o.strike;
            if (o.cap != null) cap = o.cap;
            if (o.latin != null) latin = o.latin;
        }
    }

    /** A list style: index 0 is the default, 1..9 the levels. */
    private static Props[] newLevels() {
        return new Props[10];
    }

    static final class TextRun {
        Props props = new Props();
        String text = "";
        boolean slideNumber;
    }

    static final class Para {
        int level;
        Props props = new Props();
        Props end;
        final List<TextRun> runs = new ArrayList<>();
    }

    /** A shape as written in a slide, layout or master; unset fields are inherited. */
    static final class Shape {
        boolean placeholder;
        String phType;
        int phIdx = -1;
        boolean hasXfrm;
        long x, y, cx, cy;
        boolean flipH, flipV;
        /** GEOM_* or -1 when not given. */
        int geometry = -1;
        /** Fill and outline colour: null unset, 0 none. */
        Integer fill, line;
        long lineWidth = -1;
        Long[] insets = new Long[4];
        Integer anchor;
        int fontScale = 100000, lnSpcReduction;
        Props[] lstStyle;
        boolean hasText;
        final List<Para> paras = new ArrayList<>();
        /** Text colour from the shape's style (fontRef). */
        Integer styleText;
        String picture;
        /** Picture crop (see {@link DocModel.Image#crop}), or null. */
        float[] crop;
        Table table;
        boolean chart;
    }

    /** Slide background: a colour, a picture or nothing (inherit). */
    static final class Background {
        int color;
        String picture;
    }

    static final class Master {
        final Map<String, Integer> scheme = new HashMap<>();
        final Map<String, String> clrMap = new HashMap<>();
        String majorFont = "Calibri Light", minorFont = "Calibri";
        Props[] titleStyle = newLevels(), bodyStyle = newLevels(), otherStyle = newLevels();
        Props[] defaultStyle = newLevels();
        final List<Shape> shapes = new ArrayList<>();
        Background background;
    }

    static final class Layout {
        Master master;
        final List<Shape> shapes = new ArrayList<>();
        Background background;
        boolean showMasterShapes = true;
    }

    /** Reading context of one part: its master (colours, fonts) and relationships. */
    private static final class Ctx {
        final Master master;
        final Map<String, ZipParts.Rel> rels;

        Ctx(Master master, Map<String, ZipParts.Rel> rels) {
            this.master = master;
            this.rels = rels;
        }
    }

    // ============================================================ document

    private Document read() throws IOException, XmlPullParserException {
        presentationPart = ZipParts.mainPart(zip, xml, "ppt/presentation.xml");
        Map<String, ZipParts.Rel> rels = ZipParts.rels(zip, presentationPart, xml);
        long slideW = 9144000, slideH = 6858000;
        List<String> slides = new ArrayList<>();
        ZipParts.Part part = ZipParts.open(zip, presentationPart, xml);
        if (part == null) throw new IOException("No presentation part");
        try {
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                if ("sldSz".equals(n)) {
                    slideW = attrLong(p, "cx", slideW);
                    slideH = attrLong(p, "cy", slideH);
                } else if ("sldId".equals(n)) {
                    ZipParts.Rel r = rels.get(relId(p));
                    if (r != null && !r.external && ZipParts.entry(zip, r.target) != null) slides.add(r.target);
                } else if ("defaultTextStyle".equals(n) || "notesSz".equals(n)) {
                    break;
                }
            }
        } finally {
            part.close();
        }

        List<Block> blocks = new ArrayList<>();
        Section last = null;
        for (String slide : slides) {
            Section sec = new Section();
            sec.pageWidth = (int) (slideW / EMU_PER_TWIP);
            sec.pageHeight = (int) (slideH / EMU_PER_TWIP);
            sec.marginTop = sec.marginBottom = sec.marginLeft = sec.marginRight = 0;
            List<Block> content = new ArrayList<>();
            if (!readSlide(slide, sec, content)) continue;
            if (last != null) blocks.add(new SectionBreak(last));
            blocks.addAll(content);
            last = sec;
        }
        Section end = last != null ? last : new Section();
        return new Document(blocks, DEFAULT_SIZE / 50, end, 1440);
    }

    /** Adds a slide's drawings to {@code out}; false for a hidden slide. */
    private boolean readSlide(String path, Section sec, List<Block> out) throws IOException, XmlPullParserException {
        Map<String, ZipParts.Rel> rels = ZipParts.rels(zip, path, xml);
        Layout layout = null;
        for (ZipParts.Rel r : rels.values()) {
            if (!r.external && r.type.endsWith("/slideLayout")) layout = layout(r.target);
        }
        Master master = layout != null ? layout.master : new Master();
        if (layout == null) {
            layout = new Layout();
            layout.master = master;
        }
        Ctx ctx = new Ctx(master, rels);
        List<Shape> shapes = new ArrayList<>();
        Background[] bg = new Background[1];
        boolean[] flags = {true, true};
        if (!readShapes(path, ctx, shapes, bg, flags)) return false;
        boolean showMaster = flags[0];

        Background b = bg[0] != null ? bg[0] : layout.background != null ? layout.background : master.background;
        if (b != null) {
            sec.background = b.color;
            sec.backgroundImage = b.picture;
        }
        if (showMaster && layout.showMasterShapes) {
            for (Shape s : master.shapes) if (!s.placeholder) emit(s, null, null, master, out);
        }
        if (showMaster) {
            for (Shape s : layout.shapes) if (!s.placeholder) emit(s, null, null, master, out);
        }
        for (Shape s : shapes) {
            Shape lph = s.placeholder ? findPlaceholder(layout.shapes, s) : null;
            Shape mph = s.placeholder ? findMasterPlaceholder(master.shapes, lph != null ? lph : s) : null;
            emit(s, lph, mph, master, out);
        }
        return true;
    }

    private Layout layout(String path) throws IOException, XmlPullParserException {
        Layout l = layouts.get(path);
        if (l != null) return l;
        l = new Layout();
        layouts.put(path, l);
        Map<String, ZipParts.Rel> rels = ZipParts.rels(zip, path, xml);
        for (ZipParts.Rel r : rels.values()) {
            if (!r.external && r.type.endsWith("/slideMaster")) l.master = master(r.target);
        }
        if (l.master == null) l.master = new Master();
        Background[] bg = new Background[1];
        boolean[] flags = {true, true};
        readShapes(path, new Ctx(l.master, rels), l.shapes, bg, flags);
        l.background = bg[0];
        l.showMasterShapes = flags[0];
        return l;
    }

    private Master master(String path) throws IOException, XmlPullParserException {
        Master m = masters.get(path);
        if (m != null) return m;
        m = new Master();
        masters.put(path, m);
        Map<String, ZipParts.Rel> rels = ZipParts.rels(zip, path, xml);
        for (ZipParts.Rel r : rels.values()) {
            if (!r.external && r.type.endsWith("/theme")) readTheme(r.target, m);
        }
        // Default mapping, replaced by the master's own p:clrMap.
        String[][] map = {{"bg1", "lt1"}, {"tx1", "dk1"}, {"bg2", "lt2"}, {"tx2", "dk2"}};
        for (String[] kv : map) m.clrMap.put(kv[0], kv[1]);
        // p:clrMap comes after the master's own shapes in the file; their colours need it first.
        readClrMap(path, m);
        Ctx ctx = new Ctx(m, rels);
        Background[] bg = new Background[1];
        readShapes(path, ctx, m.shapes, bg, new boolean[]{true, true});
        m.background = bg[0];
        readDefaultTextStyle(ctx);
        return m;
    }

    private void readClrMap(String path, Master m) throws IOException, XmlPullParserException {
        ZipParts.Part part = ZipParts.open(zip, path, xml);
        if (part == null) return;
        try {
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG) continue;
                if ("cSld".equals(p.getName())) {
                    XmlUtil.skip(p);
                } else if ("clrMap".equals(p.getName())) {
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        m.clrMap.put(p.getAttributeName(i), p.getAttributeValue(i));
                    }
                    return;
                }
            }
        } finally {
            part.close();
        }
    }

    private void readTheme(String path, Master m) throws IOException, XmlPullParserException {
        ZipParts.Part part = ZipParts.open(zip, path, xml);
        if (part == null) return;
        try {
            XmlPullParser p = part.parser;
            String current = null;
            boolean major = false, minor = false;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.END_TAG) {
                    String n = p.getName();
                    if ("majorFont".equals(n)) major = false;
                    else if ("minorFont".equals(n)) minor = false;
                    continue;
                }
                if (e != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                switch (n) {
                    case "dk1": case "lt1": case "dk2": case "lt2":
                    case "accent1": case "accent2": case "accent3": case "accent4": case "accent5": case "accent6":
                    case "hlink": case "folHlink":
                        current = n;
                        break;
                    case "srgbClr":
                    case "sysClr":
                        if (current != null) {
                            int c = XmlUtil.hexColor(XmlUtil.attr(p, "srgbClr".equals(n) ? "val" : "lastClr"));
                            if (c != 0) m.scheme.put(current, c);
                            current = null;
                        }
                        break;
                    case "majorFont":
                        major = true;
                        break;
                    case "minorFont":
                        minor = true;
                        break;
                    case "latin": {
                        String face = XmlUtil.attr(p, "typeface");
                        if (face != null && !face.isEmpty()) {
                            if (major) m.majorFont = face;
                            else if (minor) m.minorFont = face;
                        }
                        break;
                    }
                    case "fmtScheme":
                        return;
                    default:
                        break;
                }
            }
        } finally {
            part.close();
        }
    }

    /** The presentation's default text style (for text boxes), with this master's colours. */
    private void readDefaultTextStyle(Ctx ctx) throws IOException, XmlPullParserException {
        ZipParts.Part part = ZipParts.open(zip, presentationPart, xml);
        if (part == null) return;
        try {
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && "defaultTextStyle".equals(p.getName())) {
                    readListStyle(p, ctx, ctx.master.defaultStyle);
                    return;
                }
            }
        } finally {
            part.close();
        }
    }

    // ============================================================== shapes

    /**
     * Reads the shapes and background of a slide, layout or master. {@code flags[0]} receives
     * showMasterSp. Returns false for a hidden slide.
     */
    private boolean readShapes(String path, Ctx ctx, List<Shape> out, Background[] bg, boolean[] flags)
            throws IOException, XmlPullParserException {
        ZipParts.Part part = ZipParts.open(zip, path, xml);
        if (part == null) return false;
        try {
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                switch (n) {
                    case "sld":
                    case "sldLayout":
                    case "sldMaster": {
                        String show = XmlUtil.attr(p, "show");
                        if ("0".equals(show) || "false".equals(show)) return false;
                        String sm = XmlUtil.attr(p, "showMasterSp");
                        flags[0] = !("0".equals(sm) || "false".equals(sm));
                        break;
                    }
                    case "clrMap": {
                        for (int i = 0; i < p.getAttributeCount(); i++) {
                            ctx.master.clrMap.put(p.getAttributeName(i), p.getAttributeValue(i));
                        }
                        break;
                    }
                    case "bg":
                        bg[0] = readBackground(p, ctx);
                        break;
                    case "spTree":
                        readTree(p, ctx, out, null);
                        break;
                    case "titleStyle":
                        readListStyle(p, ctx, ctx.master.titleStyle);
                        break;
                    case "bodyStyle":
                        readListStyle(p, ctx, ctx.master.bodyStyle);
                        break;
                    case "otherStyle":
                        readListStyle(p, ctx, ctx.master.otherStyle);
                        break;
                    default:
                        break;
                }
            }
        } finally {
            part.close();
        }
        return true;
    }

    private Background readBackground(XmlPullParser p, Ctx ctx) throws XmlPullParserException, IOException {
        Background b = new Background();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("bgPr".equals(n)) {
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    String f = p.getName();
                    if ("blipFill".equals(f)) {
                        b.picture = blipPart(p, ctx, null);
                    } else {
                        Integer c = readFill(p, ctx);
                        if (c != null) b.color = c;
                    }
                }
            } else if ("bgRef".equals(n)) {
                // A theme background style; its colour is the best guess without the style list.
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    int c = readColor(p, ctx);
                    if (c != 0) b.color = c;
                }
            } else {
                XmlUtil.skip(p);
            }
        }
        return b;
    }

    /** Transform of a group: child coordinates to slide coordinates. */
    private static final class Xf {
        final double sx, sy, tx, ty;

        Xf(double sx, double sy, double tx, double ty) {
            this.sx = sx;
            this.sy = sy;
            this.tx = tx;
            this.ty = ty;
        }

        void apply(Shape s) {
            if (!s.hasXfrm) return;
            s.x = Math.round(s.x * sx + tx);
            s.y = Math.round(s.y * sy + ty);
            s.cx = Math.round(s.cx * sx);
            s.cy = Math.round(s.cy * sy);
        }
    }

    /** Reads the children of a shape tree or group. */
    private void readTree(XmlPullParser p, Ctx ctx, List<Shape> out, Xf xf) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            readShapeElement(p, ctx, out, xf);
        }
    }

    private void readShapeElement(XmlPullParser p, Ctx ctx, List<Shape> out, Xf xf)
            throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "sp":
            case "cxnSp":
            case "pic":
            case "graphicFrame": {
                Shape s = readShape(p, ctx);
                if (s != null) {
                    if (xf != null) xf.apply(s);
                    out.add(s);
                }
                break;
            }
            case "grpSp":
                readGroup(p, ctx, out, xf);
                break;
            case "AlternateContent": {
                // Newer content (e.g. equations, 3D models) with a plain fallback: use the fallback.
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    if ("Fallback".equals(p.getName())) readTree(p, ctx, out, xf);
                    else XmlUtil.skip(p);
                }
                break;
            }
            default:
                XmlUtil.skip(p);
        }
    }

    private void readGroup(XmlPullParser p, Ctx ctx, List<Shape> out, Xf outer) throws XmlPullParserException, IOException {
        Xf xf = outer;
        boolean hidden = false;
        List<Shape> children = new ArrayList<>();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("nvGrpSpPr".equals(n)) {
                hidden = isHidden(p);
            } else if ("grpSpPr".equals(n)) {
                long[] g = new long[8];
                boolean found = readGroupXfrm(p, g);
                if (found && g[6] > 0 && g[7] > 0) {
                    double sx = (double) g[2] / g[6], sy = (double) g[3] / g[7];
                    double tx = g[0] - g[4] * sx, ty = g[1] - g[5] * sy;
                    Xf local = new Xf(sx, sy, tx, ty);
                    xf = outer == null ? local
                            : new Xf(local.sx * outer.sx, local.sy * outer.sy,
                            local.tx * outer.sx + outer.tx, local.ty * outer.sy + outer.ty);
                }
            } else {
                readShapeElement(p, ctx, children, xf);
            }
        }
        if (!hidden) out.addAll(children);
    }

    /** off x/y, ext cx/cy, chOff x/y, chExt cx/cy of a group's a:xfrm. */
    private static boolean readGroupXfrm(XmlPullParser p, long[] g) throws XmlPullParserException, IOException {
        boolean found = false;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if (!"xfrm".equals(p.getName())) {
                XmlUtil.skip(p);
                continue;
            }
            found = true;
            int d2 = p.getDepth();
            while (XmlUtil.nextChild(p, d2)) {
                switch (p.getName()) {
                    case "off":
                        g[0] = attrLong(p, "x", 0);
                        g[1] = attrLong(p, "y", 0);
                        break;
                    case "ext":
                        g[2] = attrLong(p, "cx", 0);
                        g[3] = attrLong(p, "cy", 0);
                        break;
                    case "chOff":
                        g[4] = attrLong(p, "x", 0);
                        g[5] = attrLong(p, "y", 0);
                        break;
                    case "chExt":
                        g[6] = attrLong(p, "cx", 0);
                        g[7] = attrLong(p, "cy", 0);
                        break;
                    default:
                        break;
                }
                XmlUtil.skip(p);
            }
        }
        return found;
    }

    private static boolean isHidden(XmlPullParser p) throws XmlPullParserException, IOException {
        boolean hidden = false;
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) {
                depth++;
                if ("cNvPr".equals(p.getName())) {
                    String h = XmlUtil.attr(p, "hidden");
                    hidden = "1".equals(h) || "true".equals(h);
                }
            } else if (e == XmlPullParser.END_TAG) {
                depth--;
            } else if (e == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
        return hidden;
    }

    /** One sp / cxnSp / pic / graphicFrame; null when hidden. */
    private Shape readShape(XmlPullParser p, Ctx ctx) throws XmlPullParserException, IOException {
        String kind = p.getName();
        Shape s = new Shape();
        if ("cxnSp".equals(kind)) s.geometry = DocModel.GEOM_LINE;
        boolean hidden = false;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "nvSpPr":
                case "nvCxnSpPr":
                case "nvPicPr":
                case "nvGraphicFramePr":
                    hidden = readNonVisual(p, s);
                    break;
                case "spPr":
                    readShapeProps(p, ctx, s);
                    break;
                case "xfrm":
                    // graphicFrame keeps its transform outside spPr.
                    readXfrm(p, s);
                    break;
                case "style":
                    readStyle(p, ctx, s);
                    break;
                case "txBody":
                    readTextBody(p, ctx, s);
                    break;
                case "blipFill":
                    s.crop = new float[4];
                    s.picture = blipPart(p, ctx, s.crop);
                    break;
                case "graphic":
                    readGraphic(p, ctx, s);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
        return hidden ? null : s;
    }

    private static boolean readNonVisual(XmlPullParser p, Shape s) throws XmlPullParserException, IOException {
        boolean hidden = false;
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) {
                depth++;
                String n = p.getName();
                if ("cNvPr".equals(n)) {
                    String h = XmlUtil.attr(p, "hidden");
                    hidden = "1".equals(h) || "true".equals(h);
                } else if ("ph".equals(n)) {
                    s.placeholder = true;
                    s.phType = XmlUtil.attr(p, "type");
                    s.phIdx = XmlUtil.intAttr(p, "idx", -1);
                }
            } else if (e == XmlPullParser.END_TAG) {
                depth--;
            } else if (e == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
        return hidden;
    }

    private void readShapeProps(XmlPullParser p, Ctx ctx, Shape s) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "xfrm":
                    readXfrm(p, s);
                    break;
                case "prstGeom":
                    s.geometry = geometry(XmlUtil.attr(p, "prst"));
                    XmlUtil.skip(p);
                    break;
                case "custGeom":
                    if (s.geometry < 0) s.geometry = DocModel.GEOM_RECT;
                    XmlUtil.skip(p);
                    break;
                case "blipFill":
                    // A shape filled with a picture (common in Canva exports): drawn as the picture.
                    s.crop = new float[4];
                    s.picture = blipPart(p, ctx, s.crop);
                    break;
                case "ln": {
                    long w = attrLong(p, "w", -1);
                    if (w >= 0) s.lineWidth = w;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        Integer c = readFill(p, ctx);
                        if (c != null) s.line = c;
                    }
                    break;
                }
                default: {
                    Integer c = readFill(p, ctx);
                    if (c != null) s.fill = c;
                }
            }
        }
    }

    private static void readXfrm(XmlPullParser p, Shape s) throws XmlPullParserException, IOException {
        s.flipH = "1".equals(XmlUtil.attr(p, "flipH"));
        s.flipV = "1".equals(XmlUtil.attr(p, "flipV"));
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("off".equals(p.getName())) {
                s.x = attrLong(p, "x", 0);
                s.y = attrLong(p, "y", 0);
                s.hasXfrm = true;
            } else if ("ext".equals(p.getName())) {
                s.cx = attrLong(p, "cx", 0);
                s.cy = attrLong(p, "cy", 0);
                s.hasXfrm = true;
            }
            XmlUtil.skip(p);
        }
    }

    private static int geometry(String prst) {
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
                return DocModel.GEOM_RECT;
        }
    }

    /** p:style: the theme fill, line and text colours a shape uses when it sets none itself. */
    private void readStyle(XmlPullParser p, Ctx ctx, Shape s) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            int idx = XmlUtil.intAttr(p, "idx", 0);
            int color = 0;
            int d2 = p.getDepth();
            while (XmlUtil.nextChild(p, d2)) {
                int c = readColor(p, ctx);
                if (c != 0) color = c;
            }
            if ("fillRef".equals(n)) {
                if (s.fill == null) s.fill = idx > 0 ? color : 0;
            } else if ("lnRef".equals(n)) {
                if (s.line == null) s.line = idx > 0 ? color : 0;
                if (s.lineWidth < 0 && idx > 0) s.lineWidth = 9525L * idx;
            } else if ("fontRef".equals(n)) {
                if (color != 0) s.styleText = color;
            }
        }
    }

    /** a:graphic of a graphicFrame: a table, or a chart / diagram shown as a labelled box. */
    private void readGraphic(XmlPullParser p, Ctx ctx, Shape s) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) {
                String n = p.getName();
                if ("tbl".equals(n)) {
                    s.table = readTable(p, ctx);
                    continue;
                }
                if ("chart".equals(n) || "relIds".equals(n) || "oleObj".equals(n)) s.chart = true;
                depth++;
            } else if (e == XmlPullParser.END_TAG) {
                depth--;
            } else if (e == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
    }

    // ================================================================ text

    private void readTextBody(XmlPullParser p, Ctx ctx, Shape s) throws XmlPullParserException, IOException {
        s.hasText = true;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "bodyPr":
                    readBodyProps(p, s);
                    break;
                case "lstStyle":
                    s.lstStyle = newLevels();
                    readListStyle(p, ctx, s.lstStyle);
                    break;
                case "p":
                    s.paras.add(readPara(p, ctx));
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
    }

    private static void readBodyProps(XmlPullParser p, Shape s) throws XmlPullParserException, IOException {
        String[] ins = {"lIns", "tIns", "rIns", "bIns"};
        for (int i = 0; i < 4; i++) {
            long v = attrLong(p, ins[i], -1);
            if (v >= 0) s.insets[i] = v;
        }
        String a = XmlUtil.attr(p, "anchor");
        if ("ctr".equals(a)) s.anchor = DocModel.CELL_CENTER;
        else if ("b".equals(a)) s.anchor = DocModel.CELL_BOTTOM;
        else if ("t".equals(a)) s.anchor = DocModel.CELL_TOP;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("normAutofit".equals(p.getName())) {
                s.fontScale = (int) attrLong(p, "fontScale", 100000);
                s.lnSpcReduction = (int) attrLong(p, "lnSpcReduction", 0);
            }
            XmlUtil.skip(p);
        }
    }

    private void readListStyle(XmlPullParser p, Ctx ctx, Props[] levels) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            int lvl = -1;
            if ("defPPr".equals(n)) lvl = 0;
            else if (n.startsWith("lvl") && n.endsWith("pPr") && n.length() == 7) lvl = n.charAt(3) - '0';
            if (lvl < 0 || lvl > 9) {
                XmlUtil.skip(p);
                continue;
            }
            Props pr = new Props();
            readParaProps(p, ctx, pr);
            if (levels[lvl] == null) levels[lvl] = pr;
            else levels[lvl].over(pr);
        }
    }

    private Para readPara(XmlPullParser p, Ctx ctx) throws XmlPullParserException, IOException {
        Para para = new Para();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "pPr":
                    para.level = Math.max(0, Math.min(8, XmlUtil.intAttr(p, "lvl", 0)));
                    readParaProps(p, ctx, para.props);
                    break;
                case "r":
                case "fld": {
                    TextRun r = new TextRun();
                    r.slideNumber = "fld".equals(p.getName()) && "slidenum".equals(XmlUtil.attr(p, "type"));
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("rPr".equals(p.getName())) readRunProps(p, ctx, r.props);
                        else if ("t".equals(p.getName())) r.text = XmlUtil.text(p);
                        else XmlUtil.skip(p);
                    }
                    para.runs.add(r);
                    break;
                }
                case "br": {
                    TextRun r = new TextRun();
                    r.text = "\n";
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("rPr".equals(p.getName())) readRunProps(p, ctx, r.props);
                        else XmlUtil.skip(p);
                    }
                    para.runs.add(r);
                    break;
                }
                case "endParaRPr":
                    para.end = new Props();
                    readRunProps(p, ctx, para.end);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
        return para;
    }

    /** a:pPr or a list-style level: paragraph attributes and children, and the default run (defRPr). */
    private void readParaProps(XmlPullParser p, Ctx ctx, Props pr) throws XmlPullParserException, IOException {
        String algn = XmlUtil.attr(p, "algn");
        if (algn != null) {
            switch (algn) {
                case "ctr":
                    pr.algn = DocModel.ALIGN_CENTER;
                    break;
                case "r":
                    pr.algn = DocModel.ALIGN_END;
                    break;
                case "just":
                case "dist":
                case "thaiDist":
                    pr.algn = DocModel.ALIGN_JUSTIFY;
                    break;
                default:
                    pr.algn = DocModel.ALIGN_START;
            }
        }
        String marL = XmlUtil.attr(p, "marL");
        if (marL != null) pr.marL = XmlUtil.parseInt(marL, 0);
        String indent = XmlUtil.attr(p, "indent");
        if (indent != null) pr.indent = XmlUtil.parseInt(indent, 0);
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "lnSpc":
                case "spcBef":
                case "spcAft": {
                    Integer pct = null, pts = null;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("spcPct".equals(p.getName())) pct = XmlUtil.intAttr(p, "val", 100000);
                        else if ("spcPts".equals(p.getName())) pts = XmlUtil.intAttr(p, "val", 0);
                        XmlUtil.skip(p);
                    }
                    if ("lnSpc".equals(n)) {
                        pr.lnPct = pct;
                        pr.lnPts = pts;
                    } else if ("spcBef".equals(n)) {
                        pr.befPct = pct;
                        pr.befPts = pts;
                    } else {
                        pr.aftPct = pct;
                        pr.aftPts = pts;
                    }
                    break;
                }
                case "buNone":
                    pr.buType = 0;
                    XmlUtil.skip(p);
                    break;
                case "buChar":
                    pr.buType = 1;
                    pr.buChar = XmlUtil.attr(p, "char");
                    XmlUtil.skip(p);
                    break;
                case "buAutoNum":
                    pr.buType = 2;
                    pr.buAuto = XmlUtil.attr(p, "type");
                    pr.buStart = XmlUtil.intAttr(p, "startAt", 1);
                    XmlUtil.skip(p);
                    break;
                case "buFont":
                    pr.buFont = XmlUtil.attr(p, "typeface");
                    XmlUtil.skip(p);
                    break;
                case "defRPr":
                    readRunProps(p, ctx, pr);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
    }

    private void readRunProps(XmlPullParser p, Ctx ctx, Props pr) throws XmlPullParserException, IOException {
        String sz = XmlUtil.attr(p, "sz");
        if (sz != null) pr.sz = XmlUtil.parseInt(sz, DEFAULT_SIZE);
        String b = XmlUtil.attr(p, "b");
        if (b != null) pr.b = "1".equals(b) || "true".equals(b);
        String i = XmlUtil.attr(p, "i");
        if (i != null) pr.i = "1".equals(i) || "true".equals(i);
        String u = XmlUtil.attr(p, "u");
        if (u != null) pr.u = !"none".equals(u);
        String strike = XmlUtil.attr(p, "strike");
        if (strike != null) pr.strike = !"noStrike".equals(strike);
        String cap = XmlUtil.attr(p, "cap");
        if (cap != null) pr.cap = cap;
        String baseline = XmlUtil.attr(p, "baseline");
        if (baseline != null) pr.baseline = XmlUtil.parseInt(baseline, 0);
        String spc = XmlUtil.attr(p, "spc");
        if (spc != null) pr.spc = XmlUtil.parseInt(spc, 0);
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("latin".equals(n)) {
                String face = XmlUtil.attr(p, "typeface");
                if (face != null && !face.isEmpty()) pr.latin = face;
                XmlUtil.skip(p);
            } else if ("solidFill".equals(n) || "gradFill".equals(n)) {
                Integer c = readFill(p, ctx);
                if (c != null && c != 0) pr.color = c;
            } else {
                XmlUtil.skip(p);
            }
        }
    }

    // ============================================================== colours

    /**
     * A fill element (solidFill, gradFill, pattFill, noFill). Returns its colour, 0 for no fill, or
     * null (element skipped) for anything else.
     */
    private Integer readFill(XmlPullParser p, Ctx ctx) throws XmlPullParserException, IOException {
        String n = p.getName();
        switch (n) {
            case "noFill":
                XmlUtil.skip(p);
                return 0;
            case "solidFill": {
                int c = 0;
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) c = readColor(p, ctx);
                return c;
            }
            case "gradFill": {
                // No gradients: the first stop's colour stands in.
                int c = 0;
                boolean found = false;
                int depth = 1;
                while (depth > 0) {
                    int e = p.next();
                    if (e == XmlPullParser.START_TAG) {
                        if (!found && "gs".equals(p.getName())) {
                            int d2 = p.getDepth();
                            while (XmlUtil.nextChild(p, d2)) c = readColor(p, ctx);
                            found = true;
                            continue;
                        }
                        depth++;
                    } else if (e == XmlPullParser.END_TAG) {
                        depth--;
                    } else if (e == XmlPullParser.END_DOCUMENT) {
                        break;
                    }
                }
                return c;
            }
            case "pattFill": {
                int c = 0;
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    boolean fg = "fgClr".equals(p.getName());
                    int d2 = p.getDepth();
                    int v = 0;
                    while (XmlUtil.nextChild(p, d2)) v = readColor(p, ctx);
                    if (fg) c = v;
                }
                return c;
            }
            default:
                XmlUtil.skip(p);
                return null;
        }
    }

    /** A colour element with its modifiers; 0 when unknown or fully transparent. */
    private int readColor(XmlPullParser p, Ctx ctx) throws XmlPullParserException, IOException {
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
                c = scheme(ctx.master, XmlUtil.attr(p, "val"));
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
        while (XmlUtil.nextChild(p, d)) {
            String m = p.getName();
            int v = XmlUtil.intAttr(p, "val", 100000);
            XmlUtil.skip(p);
            if (c == 0) continue;
            switch (m) {
                case "lumMod":
                    c = lum(c, v / 100000f, 0f);
                    break;
                case "lumOff":
                    c = lum(c, 1f, v / 100000f);
                    break;
                case "tint":
                    c = mix(c, 0xFFFFFFFF, 1f - v / 100000f);
                    break;
                case "shade":
                    c = mix(c, 0xFF000000, 1f - v / 100000f);
                    break;
                case "alpha":
                    // Mostly transparent colours are treated as no colour.
                    if (v < 15000) c = 0;
                    break;
                default:
                    break;
            }
        }
        return c;
    }

    private static int pct255(XmlPullParser p, String name) {
        long v = attrLong(p, name, 0);
        return (int) Math.max(0, Math.min(255, Math.round(v / 100000.0 * 255)));
    }

    private static int scheme(Master m, String name) {
        if (name == null) return 0;
        String mapped = m.clrMap.get(name);
        Integer c = m.scheme.get(mapped != null ? mapped : name);
        if (c != null) return c;
        switch (mapped != null ? mapped : name) {
            case "lt1":
                return 0xFFFFFFFF;
            case "dk1":
                return 0xFF000000;
            case "lt2":
                return 0xFFE7E6E6;
            case "dk2":
                return 0xFF44546A;
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

    private static int mix(int c, int with, float amount) {
        int r = Math.round(((c >> 16) & 0xFF) * (1 - amount) + ((with >> 16) & 0xFF) * amount);
        int g = Math.round(((c >> 8) & 0xFF) * (1 - amount) + ((with >> 8) & 0xFF) * amount);
        int b = Math.round((c & 0xFF) * (1 - amount) + (with & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * The image part of a blipFill (a:blip r:embed); leaves the parser on the element's end tag.
     * {@code crop}, when given, receives the shown part: a:srcRect cuts the picture, a:fillRect
     * (negative insets) stretches it past the shape, whose outline then cuts it.
     */
    private static String blipPart(XmlPullParser p, Ctx ctx, float[] crop) throws XmlPullParserException, IOException {
        String embed = null;
        float[] src = new float[4], fill = new float[4];
        String[] sides = {"l", "t", "r", "b"};
        int depth = 1;
        while (depth > 0) {
            int e = p.next();
            if (e == XmlPullParser.START_TAG) {
                depth++;
                String n = p.getName();
                if (embed == null && "blip".equals(n)) {
                    embed = XmlUtil.attr(p, "embed");
                } else if ("srcRect".equals(n) || "fillRect".equals(n)) {
                    float[] t = "srcRect".equals(n) ? src : fill;
                    for (int i = 0; i < 4; i++) t[i] = attrLong(p, sides[i], 0) / 100000f;
                }
            } else if (e == XmlPullParser.END_TAG) {
                depth--;
            } else if (e == XmlPullParser.END_DOCUMENT) {
                break;
            }
        }
        if (crop != null) {
            // Of the source part left by srcRect, fillRect shows the slice under the shape.
            float keepW = Math.max(0.01f, 1 - src[0] - src[2]), keepH = Math.max(0.01f, 1 - src[1] - src[3]);
            float spanW = Math.max(0.01f, 1 - fill[0] - fill[2]), spanH = Math.max(0.01f, 1 - fill[1] - fill[3]);
            crop[0] = src[0] + keepW * Math.max(0, -fill[0] / spanW);
            crop[2] = src[2] + keepW * Math.max(0, -fill[2] / spanW);
            crop[1] = src[1] + keepH * Math.max(0, -fill[1] / spanH);
            crop[3] = src[3] + keepH * Math.max(0, -fill[3] / spanH);
        }
        ZipParts.Rel r = embed == null ? null : ctx.rels.get(embed);
        return r == null || r.external ? null : r.target;
    }

    // =============================================================== tables

    private Table readTable(XmlPullParser p, Ctx ctx) throws XmlPullParserException, IOException {
        Table t = new Table();
        t.cellMargin[0] = t.cellMargin[2] = 72;
        t.cellMargin[1] = t.cellMargin[3] = 144;
        boolean firstRow = false, bandRow = false, styled = false;
        List<int[]> rowSpans = new ArrayList<>();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("tblPr".equals(n)) {
                firstRow = "1".equals(XmlUtil.attr(p, "firstRow"));
                bandRow = "1".equals(XmlUtil.attr(p, "bandRow"));
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if ("tableStyleId".equals(p.getName())) styled = !XmlUtil.text(p).trim().isEmpty();
                    else XmlUtil.skip(p);
                }
            } else if ("tblGrid".equals(n)) {
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if ("gridCol".equals(p.getName())) t.grid.add((int) (attrLong(p, "w", 0) / EMU_PER_TWIP));
                    XmlUtil.skip(p);
                }
            } else if ("tr".equals(n)) {
                Row row = new Row();
                row.height = (int) (attrLong(p, "h", 0) / EMU_PER_TWIP);
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if (!"tc".equals(p.getName())) {
                        XmlUtil.skip(p);
                        continue;
                    }
                    boolean hMerge = "1".equals(XmlUtil.attr(p, "hMerge"));
                    Cell cell = new Cell();
                    cell.span = Math.max(1, XmlUtil.intAttr(p, "gridSpan", 1));
                    cell.mergedContinue = "1".equals(XmlUtil.attr(p, "vMerge"));
                    readCell(p, ctx, cell);
                    // Cells covered by a horizontal merge are part of the gridSpan before them.
                    if (!hMerge) row.cells.add(cell);
                }
                t.rows.add(row);
            } else {
                XmlUtil.skip(p);
            }
        }
        if (styled) applyDefaultTableStyle(t, ctx.master, firstRow, bandRow);
        return t;
    }

    private void readCell(XmlPullParser p, Ctx ctx, Cell cell) throws XmlPullParserException, IOException {
        Shape text = new Shape();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("txBody".equals(n)) {
                readTextBody(p, ctx, text);
            } else if ("tcPr".equals(n)) {
                String a = XmlUtil.attr(p, "anchor");
                if ("ctr".equals(a)) cell.vAlign = DocModel.CELL_CENTER;
                else if ("b".equals(a)) cell.vAlign = DocModel.CELL_BOTTOM;
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    String c = p.getName();
                    int side = "lnT".equals(c) ? 0 : "lnL".equals(c) ? 1 : "lnB".equals(c) ? 2 : "lnR".equals(c) ? 3 : -1;
                    if (side >= 0) {
                        long w = attrLong(p, "w", 12700);
                        Integer col = null;
                        int d3 = p.getDepth();
                        while (XmlUtil.nextChild(p, d3)) {
                            Integer f = readFill(p, ctx);
                            if (f != null) col = f;
                        }
                        if (col != null) {
                            cell.borders[side] = col == 0 ? Border.NONE
                                    : new Border((int) Math.max(1, w * 8 / 12700), col);
                        }
                    } else {
                        Integer f = readFill(p, ctx);
                        if (f != null) cell.background = f;
                    }
                }
            } else {
                XmlUtil.skip(p);
            }
        }
        Master m = ctx.master;
        Props[] base = newLevels();
        mergeLevels(base, m.otherStyle);
        mergeLevels(base, text.lstStyle);
        cell.blocks.addAll(paragraphs(text, base, null, m));
    }

    /**
     * PowerPoint's default table look (Medium Style 2, first accent): a dark header row with white
     * bold text, banded light rows and white lines. Only cells without their own fill get it.
     */
    private static void applyDefaultTableStyle(Table t, Master m, boolean firstRow, boolean bandRow) {
        int accent = scheme(m, "accent1");
        Border white = new Border(8, 0xFFFFFFFF);
        for (int r = 0; r < t.rows.size(); r++) {
            boolean header = firstRow && r == 0;
            int band = (r - (firstRow ? 1 : 0)) % 2 == 0 ? mix(accent, 0xFFFFFFFF, 0.6f) : mix(accent, 0xFFFFFFFF, 0.8f);
            for (Cell c : t.rows.get(r).cells) {
                if (c.background == 0) c.background = header ? accent : bandRow ? band : mix(accent, 0xFFFFFFFF, 0.8f);
                for (int i = 0; i < 4; i++) if (c.borders[i] == null) c.borders[i] = white;
                if (!header) continue;
                for (Block b : c.blocks) {
                    if (!(b instanceof Paragraph)) continue;
                    for (Run run : ((Paragraph) b).runs) {
                        run.bold = true;
                        if (run.color == 0 || run.color == 0xFF000000) run.color = 0xFFFFFFFF;
                    }
                }
            }
        }
    }

    // ============================================================ emitting

    private static Shape findPlaceholder(List<Shape> shapes, Shape s) {
        if (s.phIdx >= 0) {
            for (Shape o : shapes) if (o.placeholder && o.phIdx == s.phIdx) return o;
        }
        String type = phType(s);
        for (Shape o : shapes) if (o.placeholder && phType(o).equals(type)) return o;
        return null;
    }

    private static Shape findMasterPlaceholder(List<Shape> shapes, Shape s) {
        String type = masterType(phType(s));
        for (Shape o : shapes) if (o.placeholder && masterType(phType(o)).equals(type)) return o;
        return null;
    }

    private static String phType(Shape s) {
        return s.phType == null ? "body" : s.phType;
    }

    /** Masters only have title, body, date, footer and slide number placeholders. */
    private static String masterType(String type) {
        switch (type) {
            case "ctrTitle":
            case "title":
                return "title";
            case "dt":
            case "ftr":
            case "sldNum":
                return type;
            default:
                return "body";
        }
    }

    private static Props[] textStyleFor(Shape s, Master m) {
        if (!s.placeholder) return m.defaultStyle;
        switch (masterType(phType(s))) {
            case "title":
                return m.titleStyle;
            case "body":
                return m.bodyStyle;
            default:
                return m.otherStyle;
        }
    }

    private static void mergeLevels(Props[] into, Props[] from) {
        if (from == null) return;
        for (int i = 0; i < into.length; i++) {
            if (from[i] == null) continue;
            if (into[i] == null) into[i] = new Props();
            into[i].over(from[i]);
        }
    }

    private static <T> T first(T a, T b, T c) {
        return a != null ? a : b != null ? b : c;
    }

    /** Turns a shape (with its layout and master placeholders) into drawings. */
    private void emit(Shape s, Shape lph, Shape mph, Master m, List<Block> out) {
        Shape geo = s.hasXfrm ? s : lph != null && lph.hasXfrm ? lph : mph != null && mph.hasXfrm ? mph : null;
        if (geo == null || geo.cx < 0 || geo.cy < 0) return;
        long w = geo.cx, h = geo.cy;

        if (s.picture != null) {
            if (w <= 0 || h <= 0) return;
            Image img = new Image(s.picture, w, h);
            float[] c = s.crop;
            if (c != null && (c[0] > 0.001f || c[1] > 0.001f || c[2] > 0.001f || c[3] > 0.001f)
                    && c[0] + c[2] < 0.98f && c[1] + c[3] < 0.98f) {
                img.crop = c;
            }
            place(img, geo);
            out.add(img);
            return;
        }

        int geometry = s.geometry >= 0 ? s.geometry : lph != null && lph.geometry >= 0 ? lph.geometry
                : mph != null && mph.geometry >= 0 ? mph.geometry : DocModel.GEOM_RECT;
        List<Block> blocks = new ArrayList<>();
        if (s.table != null) {
            blocks.add(s.table);
        } else if (s.hasText) {
            Props[] levels = newLevels();
            mergeLevels(levels, textStyleFor(s, m));
            if (mph != null) mergeLevels(levels, mph.lstStyle);
            if (lph != null) mergeLevels(levels, lph.lstStyle);
            Integer styleText = first(s.styleText, lph != null ? lph.styleText : null, mph != null ? mph.styleText : null);
            if (styleText != null) {
                Props c = new Props();
                c.color = styleText;
                for (int i = 0; i < levels.length; i++) {
                    if (levels[i] == null) levels[i] = new Props();
                    levels[i].overRun(c);
                }
            }
            mergeLevels(levels, s.lstStyle);
            blocks.addAll(paragraphs(s, levels, s, m));
        } else if (s.chart) {
            blocks.add(label(chartLabel));
        }

        Integer fill = first(s.fill, lph != null ? lph.fill : null, mph != null ? mph.fill : null);
        Integer line = first(s.line, lph != null ? lph.line : null, mph != null ? mph.line : null);
        if (s.chart && fill == null) fill = 0xFFF2F2F2;
        if (s.chart && line == null) line = 0xFFBFBFBF;
        boolean hasInk = (fill != null && fill != 0) || (line != null && line != 0) || hasVisibleText(blocks);
        if (!hasInk) return;
        if (geometry == DocModel.GEOM_LINE) {
            // A straight line has no height or width in one direction.
            w = Math.max(w, 1);
            h = Math.max(h, 1);
        } else if (w <= 0 || h <= 0) {
            return;
        }

        TextBox tb = new TextBox(blocks, w, h);
        tb.geometry = geometry;
        tb.flipH = geo.flipH;
        tb.flipV = geo.flipV;
        tb.fill = fill != null ? fill : 0;
        tb.border = line != null ? line : 0;
        long lw = s.lineWidth >= 0 ? s.lineWidth : lph != null && lph.lineWidth >= 0 ? lph.lineWidth
                : mph != null && mph.lineWidth >= 0 ? mph.lineWidth : 9525;
        tb.borderWidthEmu = Math.max(1, lw);
        long[] defaults = {91440, 45720, 91440, 45720};
        tb.insets = new long[4];
        for (int i = 0; i < 4; i++) {
            Long v = first(s.insets[i], lph != null ? lph.insets[i] : null, mph != null ? mph.insets[i] : null);
            tb.insets[i] = s.table != null ? 0 : v != null ? v : defaults[i];
        }
        Integer anchor = first(s.anchor, lph != null ? lph.anchor : null, mph != null ? mph.anchor : null);
        tb.anchor = s.chart ? DocModel.CELL_CENTER : anchor != null ? anchor : DocModel.CELL_TOP;
        place(tb, geo);
        out.add(tb);
    }

    private static void place(DocModel.Drawing dr, Shape geo) {
        dr.floating = true;
        dr.wrapsText = false;
        dr.hRel = DocModel.REL_PAGE;
        dr.vRel = DocModel.REL_PAGE;
        dr.hOffset = geo.x;
        dr.vOffset = geo.y;
    }

    private static boolean hasVisibleText(List<Block> blocks) {
        for (Block b : blocks) {
            if (b instanceof Table) return true;
            if (b instanceof Paragraph && !((Paragraph) b).isEmpty()) return true;
        }
        return false;
    }

    private static Paragraph label(String text) {
        Paragraph p = new Paragraph();
        p.align = DocModel.ALIGN_CENTER;
        Run r = new Run();
        r.text.append(text);
        r.color = 0xFF7F7F7F;
        r.halfPoints = 28;
        r.font = "Arial";
        p.runs.add(r);
        return p;
    }

    /**
     * The paragraphs of a text body, styled from {@code levels}. {@code autofit} (the shape) scales
     * fonts and line spacing when PowerPoint shrank the text to fit; {@code m} supplies the theme fonts.
     */
    private static List<Block> paragraphs(Shape body, Props[] levels, Shape autofit, Master m) {
        List<Block> out = new ArrayList<>();
        float scale = autofit != null ? autofit.fontScale / 100000f : 1f;
        int reduce = autofit != null ? autofit.lnSpcReduction : 0;
        int[] counters = new int[10];
        int lastLevel = -1;
        boolean any = false;
        for (Para para : body.paras) {
            Props pr = new Props();
            pr.over(levels[0]);
            pr.over(levels[Math.min(9, para.level + 1)]);
            pr.over(para.props);

            Paragraph p = new Paragraph();
            p.align = pr.algn != null ? pr.algn : DocModel.ALIGN_START;
            p.indLeft = (pr.marL != null ? pr.marL : 0) / EMU_PER_TWIP;
            p.firstLine = (pr.indent != null ? pr.indent : 0) / EMU_PER_TWIP;
            int baseSize = pr.sz != null ? pr.sz : DEFAULT_SIZE;
            float sizePt = baseSize / 100f * scale;
            if (pr.lnPts != null) {
                p.lineRule = DocModel.LINE_EXACT;
                p.line = Math.round(pr.lnPts / 5f);
            } else {
                int pct = (pr.lnPct != null ? pr.lnPct : 100000) - reduce;
                p.line = Math.max(60, Math.round(240f * pct / 100000f));
            }
            p.spaceBefore = spacing(pr.befPts, pr.befPct, sizePt);
            p.spaceAfter = spacing(pr.aftPts, pr.aftPct, sizePt);

            boolean hasText = false;
            for (TextRun tr : para.runs) {
                if (!tr.text.isEmpty() || tr.slideNumber) hasText = true;
            }
            // Numbering restarts after a paragraph at a lower level or without a number.
            int lvl = para.level;
            if (lvl < lastLevel) for (int i = lvl + 1; i < counters.length; i++) counters[i] = 0;
            lastLevel = lvl;
            if (hasText && pr.buType != null && pr.buType == 1 && pr.buChar != null) {
                p.listPrefix = bullet(pr.buChar, pr.buFont);
            } else if (hasText && pr.buType != null && pr.buType == 2) {
                int n = (pr.buStart != null ? pr.buStart : 1) + counters[lvl]++;
                p.listPrefix = autoNumber(pr.buAuto, n);
            } else if (pr.buType == null || pr.buType != 2) {
                counters[lvl] = 0;
            }
            if (p.listPrefix != null) {
                // The marker hangs in the indent and the text starts at marL, as in PowerPoint.
                p.listSuffix = p.firstLine < 0 ? "\t" : " ";
            }

            for (TextRun tr : para.runs) {
                Props rp = new Props();
                rp.overRun(pr);
                rp.overRun(tr.props);
                Run r = run(rp, scale, m);
                if (tr.slideNumber) r.pageNumber = true;
                r.text.append(tr.text);
                p.runs.add(r);
            }
            Props end = new Props();
            end.overRun(pr);
            end.overRun(para.end);
            p.markHalfPoints = Math.max(2, Math.round((end.sz != null ? end.sz : DEFAULT_SIZE) / 50f * scale));
            p.markFont = font(end.latin, m);
            if (hasText) any = true;
            out.add(p);
        }
        // A placeholder with no text of its own shows nothing (its prompt text is not printed).
        if (!any) out.clear();
        return out;
    }

    private static Run run(Props rp, float scale, Master m) {
        Run r = new Run();
        int sz = rp.sz != null ? rp.sz : DEFAULT_SIZE;
        r.halfPoints = Math.max(2, Math.round(sz / 50f * scale));
        r.bold = rp.b != null && rp.b;
        r.italic = rp.i != null && rp.i;
        r.underline = rp.u != null && rp.u;
        r.strike = rp.strike != null && rp.strike;
        r.caps = "all".equals(rp.cap);
        r.smallCaps = "small".equals(rp.cap);
        r.color = rp.color != null ? rp.color : 0;
        if (rp.baseline != null && rp.baseline != 0) {
            r.vertAlign = rp.baseline > 0 ? DocModel.VALIGN_SUPER : DocModel.VALIGN_SUB;
        }
        r.font = font(rp.latin, m);
        // Letter spacing in hundredths of a point; twips are twentieths.
        if (rp.spc != null) r.charSpacing = Math.round(rp.spc * scale / 5f);
        return r;
    }

    /** Resolves theme font references (+mj-lt, +mn-lt); no font means the theme's body font. */
    private static String font(String latin, Master m) {
        if (latin == null || latin.startsWith("+mn")) return m.minorFont;
        if (latin.startsWith("+mj")) return m.majorFont;
        return latin;
    }

    /** Spacing in twips from points (hundredths) or a percentage of a line. */
    private static int spacing(Integer pts, Integer pct, float sizePt) {
        if (pts != null) return Math.round(pts / 5f);
        if (pct != null) return Math.round(pct / 100000f * sizePt * 1.2f * 20f);
        return 0;
    }

    /** Symbol-font bullets drawn with ordinary characters. */
    private static String bullet(String ch, String font) {
        if (ch == null || ch.isEmpty()) return "•";
        String f = font == null ? "" : font.toLowerCase(java.util.Locale.ROOT);
        if (f.contains("wingdings") || f.contains("symbol")) {
            switch (ch.charAt(0)) {
                case '§':
                case 'n':
                    return "▪";
                case 'Ø':
                    return "➢";
                case 'ü':
                    return "✓";
                case 'q':
                case 'o':
                    return "□";
                case 'v':
                    return "❖";
                case 'l':
                    return "●";
                default:
                    return "•";
            }
        }
        return ch;
    }

    private static String autoNumber(String type, int n) {
        if (type == null) type = "arabicPeriod";
        String num;
        if (type.startsWith("alphaLc")) num = Numbering.format(n, "lowerLetter");
        else if (type.startsWith("alphaUc")) num = Numbering.format(n, "upperLetter");
        else if (type.startsWith("romanLc")) num = Numbering.format(n, "lowerRoman");
        else if (type.startsWith("romanUc")) num = Numbering.format(n, "upperRoman");
        else num = String.valueOf(n);
        if (type.endsWith("ParenBoth")) return "(" + num + ")";
        if (type.endsWith("ParenR")) return num + ")";
        if (type.endsWith("Plain")) return num;
        return num + ".";
    }

    /** The r:id attribute; p:sldId also has a plain id, so the namespace tells them apart. */
    private static String relId(XmlPullParser p) {
        for (int i = 0, n = p.getAttributeCount(); i < n; i++) {
            String ns = p.getAttributeNamespace(i);
            if ("id".equals(p.getAttributeName(i)) && ns != null && !ns.isEmpty()) return p.getAttributeValue(i);
        }
        return null;
    }

    private static long attrLong(XmlPullParser p, String name, long fallback) {
        String v = XmlUtil.attr(p, name);
        if (v == null) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
