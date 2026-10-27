package com.tsdlanm.documentreader.docx;

import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Border;
import com.tsdlanm.documentreader.docx.DocModel.Cell;
import com.tsdlanm.documentreader.docx.DocModel.ColumnBreak;
import com.tsdlanm.documentreader.docx.DocModel.Drawing;
import com.tsdlanm.documentreader.docx.DocModel.TextBox;
import com.tsdlanm.documentreader.docx.DocModel.Document;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.PageBreak;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Row;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.Section;
import com.tsdlanm.documentreader.docx.DocModel.SectionBreak;
import com.tsdlanm.documentreader.docx.DocModel.Table;
import com.tsdlanm.documentreader.util.XmlFactory;
import com.tsdlanm.documentreader.util.XmlUtil;
import com.tsdlanm.documentreader.util.ZipParts;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Streams word/document.xml into a {@link Document}: text formatting, paragraph spacing and
 * indents, lists, tables with borders, images, text boxes, page and section breaks, and headers
 * and footers. Footnotes and comments are ignored.
 */
public final class DocxParser {

    /** Run properties where null means "not set here, inherit". Colour 0 means automatic / none. */
    private static final class RunProps {
        Boolean bold, italic, underline, strike, hidden, caps, smallCaps;
        Integer halfPoints, color, background, vertAlign, charSpacing, widthPercent;
        /** Kerning threshold in half points; 0 turns kerning off. */
        Integer kern;
        /** Either an explicit font name or a theme font slot such as "minorHAnsi". */
        String font, themeFont;

        RunProps copy() {
            RunProps r = new RunProps();
            r.apply(this);
            return r;
        }

        void apply(RunProps o) {
            if (o == null) return;
            if (o.bold != null) bold = o.bold;
            if (o.italic != null) italic = o.italic;
            if (o.underline != null) underline = o.underline;
            if (o.strike != null) strike = o.strike;
            if (o.hidden != null) hidden = o.hidden;
            if (o.caps != null) caps = o.caps;
            if (o.smallCaps != null) smallCaps = o.smallCaps;
            if (o.halfPoints != null) halfPoints = o.halfPoints;
            if (o.color != null) color = o.color;
            if (o.background != null) background = o.background;
            if (o.vertAlign != null) vertAlign = o.vertAlign;
            if (o.charSpacing != null) charSpacing = o.charSpacing;
            if (o.widthPercent != null) widthPercent = o.widthPercent;
            if (o.kern != null) kern = o.kern;
            if (o.themeFont != null) {
                themeFont = o.themeFont;
                font = null;
            } else if (o.font != null) {
                font = o.font;
                themeFont = null;
            }
        }
    }

    /** Paragraph properties where null means "inherit". */
    private static final class ParaProps {
        Integer align, indLeft, indRight, firstLine, spaceBefore, spaceAfter, line, lineRule, outline, ilvl;
        Boolean contextual, pageBreakBefore, keepNext;
        String numId;
        /** Tab stops as {position, alignment or -1 if cleared, leader char}. */
        List<int[]> tabs;

        void apply(ParaProps o) {
            if (o == null) return;
            if (o.align != null) align = o.align;
            if (o.indLeft != null) indLeft = o.indLeft;
            if (o.indRight != null) indRight = o.indRight;
            if (o.firstLine != null) firstLine = o.firstLine;
            if (o.spaceBefore != null) spaceBefore = o.spaceBefore;
            if (o.spaceAfter != null) spaceAfter = o.spaceAfter;
            if (o.line != null) line = o.line;
            if (o.lineRule != null) lineRule = o.lineRule;
            if (o.outline != null) outline = o.outline;
            if (o.ilvl != null) ilvl = o.ilvl;
            if (o.contextual != null) contextual = o.contextual;
            if (o.pageBreakBefore != null) pageBreakBefore = o.pageBreakBefore;
            if (o.keepNext != null) keepNext = o.keepNext;
            if (o.numId != null) numId = o.numId;
            if (o.tabs != null) {
                if (tabs == null) tabs = new ArrayList<>();
                tabs.addAll(o.tabs);
            }
        }
    }

    private static final class Style {
        String name = "";
        String basedOn;
        final RunProps run = new RunProps();
        final ParaProps para = new ParaProps();
        /** Table styles only. */
        final Border[] tableBorders = new Border[6];
        final int[] cellMargin = {-1, -1, -1, -1};
    }

    /** A complex field (fldChar begin ... separate ... end) being read. */
    private static final class Field {
        final StringBuilder instr = new StringBuilder();
        boolean separated;
        boolean page;
    }

    private final ZipFile zip;
    private final XmlFactory xml;
    private final Map<String, Style> styles = new HashMap<>();
    private final Numbering numbering = new Numbering();
    private final RunProps runDefaults = new RunProps();
    private final ParaProps paraDefaults = new ParaProps();
    private final Map<String, List<Block>> headerCache = new HashMap<>();
    private Map<String, ZipParts.Rel> rels = new HashMap<>();
    private List<Field> fields = new ArrayList<>();
    private String majorFont = "Calibri Light", minorFont = "Calibri";
    /** Word falls back to Times New Roman when the theme leaves the complex-script font empty. */
    private String majorBidiFont = "Times New Roman", minorBidiFont = "Times New Roman";
    private int defaultHalfPoints = 22;
    private int defaultTab = 720;
    private String defaultParagraphStyle;
    /** Style of the table whose cells are being read, or null. */
    private String tableStyle;
    private Section lastSection;

    private DocxParser(ZipFile zip, XmlFactory xml) {
        this.zip = zip;
        this.xml = xml;
    }

    public static boolean isDocx(ZipFile zip) {
        return zip.getEntry("word/document.xml") != null;
    }

    public static Document parse(ZipFile zip, XmlFactory xml) throws IOException, XmlPullParserException {
        return new DocxParser(zip, xml).run();
    }

    private Document run() throws IOException, XmlPullParserException {
        String main = ZipParts.mainPart(zip, xml, "word/document.xml");
        rels = ZipParts.rels(zip, main, xml);
        String stylesPart = "word/styles.xml";
        String numberingPart = "word/numbering.xml";
        String themePart = "word/theme/theme1.xml";
        String settingsPart = "word/settings.xml";
        String footnotesPart = null;
        for (ZipParts.Rel r : rels.values()) {
            if (r.external) continue;
            if (r.type.endsWith("/styles")) stylesPart = r.target;
            else if (r.type.endsWith("/numbering")) numberingPart = r.target;
            else if (r.type.endsWith("/theme")) themePart = r.target;
            else if (r.type.endsWith("/settings")) settingsPart = r.target;
            else if (r.type.endsWith("/footnotes")) footnotesPart = r.target;
        }
        ZipParts.Part tp = ZipParts.open(zip, themePart, xml);
        if (tp != null) {
            try {
                parseTheme(tp.parser);
            } finally {
                tp.close();
            }
        }
        ZipParts.Part setp = ZipParts.open(zip, settingsPart, xml);
        if (setp != null) {
            try {
                parseSettings(setp.parser);
            } finally {
                setp.close();
            }
        }
        ZipParts.Part sp = ZipParts.open(zip, stylesPart, xml);
        if (sp != null) {
            try {
                parseStyles(sp.parser);
            } finally {
                sp.close();
            }
        }
        RunProps normal = runDefaults.copy();
        normal.apply(resolveRun(defaultParagraphStyle, 0));
        if (normal.halfPoints != null && normal.halfPoints > 0) defaultHalfPoints = normal.halfPoints;
        ZipParts.Part np = ZipParts.open(zip, numberingPart, xml);
        if (np != null) {
            try {
                numbering.parse(np.parser);
            } finally {
                np.close();
            }
        }
        if (footnotesPart != null) parseFootnotes(footnotesPart);

        List<Block> blocks = new ArrayList<>();
        ZipParts.Part dp = ZipParts.open(zip, main, xml);
        if (dp == null) throw new IOException("No document part");
        try {
            XmlPullParser p = dp.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && "body".equals(p.getName())) {
                    parseBlocks(p, blocks);
                    break;
                }
            }
        } finally {
            dp.close();
        }
        return new Document(blocks, defaultHalfPoints, lastSection != null ? lastSection : new Section(),
                defaultTab);
    }

    // ------------------------------------------------------- theme, settings

    private void parseTheme(XmlPullParser p) throws XmlPullParserException, IOException {
        boolean major = false, minor = false;
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.END_TAG) {
                if ("majorFont".equals(p.getName())) major = false;
                else if ("minorFont".equals(p.getName())) minor = false;
                continue;
            }
            if (e != XmlPullParser.START_TAG) continue;
            String n = p.getName();
            if ("majorFont".equals(n)) {
                major = true;
            } else if ("minorFont".equals(n)) {
                minor = true;
            } else if (("latin".equals(n) || "cs".equals(n)) && (major || minor)) {
                String face = XmlUtil.attr(p, "typeface");
                if (face != null && !face.isEmpty()) {
                    boolean cs = "cs".equals(n);
                    if (major && cs) majorBidiFont = face;
                    else if (major) majorFont = face;
                    else if (cs) minorBidiFont = face;
                    else minorFont = face;
                }
            }
        }
    }

    private void parseSettings(XmlPullParser p) throws XmlPullParserException, IOException {
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && "defaultTabStop".equals(p.getName())) {
                int v = XmlUtil.intAttr(p, "val", 720);
                if (v > 0) defaultTab = v;
                return;
            }
        }
    }

    /** Font for a theme slot; the "Bidi" slots use the theme's complex-script font. */
    private String themeFontName(String slot) {
        boolean major = slot.startsWith("major");
        if (slot.endsWith("Bidi")) return major ? majorBidiFont : minorBidiFont;
        return major ? majorFont : minorFont;
    }

    // ---------------------------------------------------------------- styles

    private void parseStyles(XmlPullParser p) throws XmlPullParserException, IOException {
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (e != XmlPullParser.START_TAG) continue;
            String n = p.getName();
            if ("docDefaults".equals(n)) {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    String c = p.getName();
                    if ("rPrDefault".equals(c) || "pPrDefault".equals(c)) {
                        int d2 = p.getDepth();
                        while (XmlUtil.nextChild(p, d2)) {
                            if ("rPr".equals(p.getName())) parseRunProps(p, runDefaults);
                            else if ("pPr".equals(p.getName())) parsePPr(p, paraDefaults, null, null, null);
                            else XmlUtil.skip(p);
                        }
                    } else {
                        XmlUtil.skip(p);
                    }
                }
            } else if ("style".equals(n)) {
                String type = XmlUtil.attr(p, "type");
                String id = XmlUtil.attr(p, "styleId");
                boolean isDefault = "1".equals(XmlUtil.attr(p, "default")) || "true".equals(XmlUtil.attr(p, "default"));
                Style s = new Style();
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    switch (p.getName()) {
                        case "name":
                            String nm = XmlUtil.attr(p, "val");
                            if (nm != null) s.name = nm.toLowerCase(Locale.ROOT);
                            XmlUtil.skip(p);
                            break;
                        case "basedOn":
                            s.basedOn = XmlUtil.attr(p, "val");
                            XmlUtil.skip(p);
                            break;
                        case "rPr":
                            parseRunProps(p, s.run);
                            break;
                        case "pPr":
                            parsePPr(p, s.para, null, null, null);
                            break;
                        case "tblPr":
                            parseTablePr(p, s.tableBorders, s.cellMargin, null);
                            break;
                        default:
                            XmlUtil.skip(p);
                    }
                }
                if (id != null) styles.put(id, s);
                if (isDefault && "paragraph".equals(type)) defaultParagraphStyle = id;
            }
        }
    }

    /** Run props of a style including everything it is based on. */
    private RunProps resolveRun(String styleId, int depth) {
        RunProps out = new RunProps();
        Style s = styleId == null ? null : styles.get(styleId);
        if (s == null || depth > 10) return out;
        out.apply(resolveRun(s.basedOn, depth + 1));
        out.apply(s.run);
        return out;
    }

    private ParaProps resolvePara(String styleId, int depth) {
        ParaProps out = new ParaProps();
        Style s = styleId == null ? null : styles.get(styleId);
        if (s == null || depth > 10) return out;
        out.apply(resolvePara(s.basedOn, depth + 1));
        out.apply(s.para);
        return out;
    }

    /** Table borders and cell margins of a table style chain, written into the arrays. */
    private void resolveTableStyle(String styleId, int depth, Border[] borders, int[] margins) {
        Style s = styleId == null ? null : styles.get(styleId);
        if (s == null || depth > 10) return;
        resolveTableStyle(s.basedOn, depth + 1, borders, margins);
        for (int i = 0; i < 6; i++) if (s.tableBorders[i] != null) borders[i] = s.tableBorders[i];
        for (int i = 0; i < 4; i++) if (s.cellMargin[i] >= 0) margins[i] = s.cellMargin[i];
    }

    private int headingLevel(String styleId, ParaProps eff) {
        Style s = styleId == null ? null : styles.get(styleId);
        String name = s != null ? s.name : (styleId == null ? "" : styleId.toLowerCase(Locale.ROOT));
        if ("title".equals(name)) return 1;
        if (name.startsWith("heading")) {
            int lvl = XmlUtil.parseInt(name.substring(7).trim(), 0);
            if (lvl >= 1 && lvl <= 9) return Math.min(lvl, 6);
        }
        if (eff.outline != null && eff.outline >= 0 && eff.outline < 9) return Math.min(eff.outline + 1, 6);
        return 0;
    }

    // ------------------------------------------------------------- run props

    private void parseRunProps(XmlPullParser p, RunProps r) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) parseSingleRunProp(p, r);
    }

    /** Parses one child of rPr (the parser is on its start tag) and consumes it. */
    private void parseSingleRunProp(XmlPullParser p, RunProps r) throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "b":
                r.bold = XmlUtil.toggle(p);
                break;
            case "i":
                r.italic = XmlUtil.toggle(p);
                break;
            case "u": {
                String v = XmlUtil.attr(p, "val");
                r.underline = v == null || !"none".equals(v);
                break;
            }
            case "strike":
            case "dstrike":
                r.strike = XmlUtil.toggle(p);
                break;
            case "vanish":
                r.hidden = XmlUtil.toggle(p);
                break;
            case "caps":
                r.caps = XmlUtil.toggle(p);
                break;
            case "smallCaps":
                r.smallCaps = XmlUtil.toggle(p);
                break;
            case "sz": {
                int v = XmlUtil.intAttr(p, "val", 0);
                if (v > 0) r.halfPoints = v;
                break;
            }
            case "spacing":
                // Character spacing (in rPr); paragraph spacing is read in parsePPr.
                r.charSpacing = Math.max(-200, Math.min(400, XmlUtil.intAttr(p, "val", 0)));
                break;
            case "w":
                r.widthPercent = Math.max(10, Math.min(600, XmlUtil.intAttr(p, "val", 100)));
                break;
            case "kern":
                r.kern = Math.max(0, XmlUtil.intAttr(p, "val", 0));
                break;
            case "rFonts": {
                // Theme attributes win over explicit names, as in Word.
                String theme = XmlUtil.attr(p, "asciiTheme");
                if (theme == null) theme = XmlUtil.attr(p, "hAnsiTheme");
                String name = XmlUtil.attr(p, "ascii");
                if (name == null) name = XmlUtil.attr(p, "hAnsi");
                if (theme != null) {
                    r.themeFont = theme;
                    r.font = null;
                } else if (name != null) {
                    r.font = name;
                    r.themeFont = null;
                }
                break;
            }
            case "color":
                r.color = XmlUtil.hexColor(XmlUtil.attr(p, "val"));
                break;
            case "highlight":
                r.background = highlight(XmlUtil.attr(p, "val"));
                break;
            case "shd": {
                int c = XmlUtil.hexColor(XmlUtil.attr(p, "fill"));
                if (c != 0 && c != 0xFFFFFFFF) r.background = c;
                break;
            }
            case "vertAlign": {
                String v = XmlUtil.attr(p, "val");
                r.vertAlign = "superscript".equals(v) ? DocModel.VALIGN_SUPER
                        : "subscript".equals(v) ? DocModel.VALIGN_SUB : DocModel.VALIGN_BASELINE;
                break;
            }
            default:
                break;
        }
        XmlUtil.skip(p);
    }

    private static int highlight(String v) {
        if (v == null) return 0;
        switch (v) {
            case "yellow": return 0xFFFFFF00;
            case "green": return 0xFF00FF00;
            case "cyan": return 0xFF00FFFF;
            case "magenta": return 0xFFFF00FF;
            case "blue": return 0xFF0000FF;
            case "red": return 0xFFFF0000;
            case "darkBlue": return 0xFF000080;
            case "darkCyan": return 0xFF008080;
            case "darkGreen": return 0xFF008000;
            case "darkMagenta": return 0xFF800080;
            case "darkRed": return 0xFF800000;
            case "darkYellow": return 0xFF808000;
            case "darkGray": return 0xFF808080;
            case "lightGray": return 0xFFC0C0C0;
            case "black": return 0xFF000000;
            default: return 0;
        }
    }

    private Run toRun(RunProps r) {
        Run run = new Run();
        run.bold = Boolean.TRUE.equals(r.bold);
        run.italic = Boolean.TRUE.equals(r.italic);
        run.underline = Boolean.TRUE.equals(r.underline);
        run.strike = Boolean.TRUE.equals(r.strike);
        run.caps = Boolean.TRUE.equals(r.caps);
        run.smallCaps = Boolean.TRUE.equals(r.smallCaps);
        run.halfPoints = r.halfPoints == null ? 0 : Math.max(0, r.halfPoints);
        run.font = r.themeFont != null ? themeFontName(r.themeFont) : r.font;
        run.color = r.color == null ? 0 : r.color;
        run.background = r.background == null ? 0 : r.background;
        run.vertAlign = r.vertAlign == null ? 0 : r.vertAlign;
        run.charSpacing = r.charSpacing == null ? 0 : r.charSpacing;
        run.widthPercent = r.widthPercent == null ? 100 : r.widthPercent;
        // w:kern gives the smallest font size (half points) that is kerned.
        int size = run.halfPoints > 0 ? run.halfPoints : defaultHalfPoints;
        run.kern = r.kern != null && r.kern > 0 && size >= r.kern;
        return run;
    }

    // ------------------------------------------------------- paragraph props

    private static int align(String v) {
        if (v == null) return DocModel.ALIGN_START;
        switch (v) {
            case "center": return DocModel.ALIGN_CENTER;
            case "right":
            case "end": return DocModel.ALIGN_END;
            case "both":
            case "distribute": return DocModel.ALIGN_JUSTIFY;
            default: return DocModel.ALIGN_START;
        }
    }

    /**
     * Parses a pPr element into {@code out}. The style id, paragraph mark formatting and section
     * properties go to the optional holders.
     */
    private void parsePPr(XmlPullParser p, ParaProps out, String[] styleId, RunProps mark, Section[] section)
            throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "pStyle":
                    if (styleId != null) styleId[0] = XmlUtil.attr(p, "val");
                    XmlUtil.skip(p);
                    break;
                case "numPr": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("numId".equals(p.getName())) out.numId = XmlUtil.attr(p, "val");
                        else if ("ilvl".equals(p.getName())) out.ilvl = XmlUtil.intAttr(p, "val", 0);
                        XmlUtil.skip(p);
                    }
                    break;
                }
                case "jc":
                    out.align = align(XmlUtil.attr(p, "val"));
                    XmlUtil.skip(p);
                    break;
                case "ind": {
                    String l = XmlUtil.attr(p, "left");
                    if (l == null) l = XmlUtil.attr(p, "start");
                    if (l != null) out.indLeft = XmlUtil.parseInt(l, 0);
                    String r = XmlUtil.attr(p, "right");
                    if (r == null) r = XmlUtil.attr(p, "end");
                    if (r != null) out.indRight = XmlUtil.parseInt(r, 0);
                    String h = XmlUtil.attr(p, "hanging");
                    String f = XmlUtil.attr(p, "firstLine");
                    if (h != null) out.firstLine = -XmlUtil.parseInt(h, 0);
                    else if (f != null) out.firstLine = XmlUtil.parseInt(f, 0);
                    XmlUtil.skip(p);
                    break;
                }
                case "spacing": {
                    String auto = XmlUtil.attr(p, "beforeAutospacing");
                    if ("1".equals(auto) || "true".equals(auto)) out.spaceBefore = 280;
                    else if (XmlUtil.attr(p, "before") != null) out.spaceBefore = XmlUtil.intAttr(p, "before", 0);
                    auto = XmlUtil.attr(p, "afterAutospacing");
                    if ("1".equals(auto) || "true".equals(auto)) out.spaceAfter = 280;
                    else if (XmlUtil.attr(p, "after") != null) out.spaceAfter = XmlUtil.intAttr(p, "after", 0);
                    if (XmlUtil.attr(p, "line") != null) {
                        out.line = XmlUtil.intAttr(p, "line", 240);
                        String rule = XmlUtil.attr(p, "lineRule");
                        out.lineRule = "exact".equals(rule) ? DocModel.LINE_EXACT
                                : "atLeast".equals(rule) ? DocModel.LINE_AT_LEAST : DocModel.LINE_AUTO;
                    }
                    XmlUtil.skip(p);
                    break;
                }
                case "tabs": {
                    out.tabs = new ArrayList<>();
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("tab".equals(p.getName())) {
                            String v = XmlUtil.attr(p, "val");
                            int pos = XmlUtil.intAttr(p, "pos", -1);
                            if (pos >= 0 && !"bar".equals(v)) {
                                int al = "clear".equals(v) ? -1
                                        : "right".equals(v) || "end".equals(v) ? DocModel.TAB_RIGHT
                                        : "center".equals(v) ? DocModel.TAB_CENTER : DocModel.TAB_LEFT;
                                out.tabs.add(new int[]{pos, al, leader(XmlUtil.attr(p, "leader"))});
                            }
                        }
                        XmlUtil.skip(p);
                    }
                    break;
                }
                case "outlineLvl":
                    out.outline = XmlUtil.intAttr(p, "val", 9);
                    XmlUtil.skip(p);
                    break;
                case "contextualSpacing":
                    out.contextual = XmlUtil.toggle(p);
                    XmlUtil.skip(p);
                    break;
                case "pageBreakBefore":
                    out.pageBreakBefore = XmlUtil.toggle(p);
                    XmlUtil.skip(p);
                    break;
                case "keepNext":
                    out.keepNext = XmlUtil.toggle(p);
                    XmlUtil.skip(p);
                    break;
                case "rPr":
                    if (mark != null) parseRunProps(p, mark);
                    else XmlUtil.skip(p);
                    break;
                case "sectPr":
                    if (section != null) section[0] = parseSection(p);
                    else XmlUtil.skip(p);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
    }

    // --------------------------------------------------------------- sections

    private Section parseSection(XmlPullParser p) throws XmlPullParserException, IOException {
        Section s = new Section();
        int columns = 1, columnGap = 720;
        List<int[]> explicitColumns = new ArrayList<>();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("cols".equals(p.getName())) {
                columns = Math.max(1, Math.min(16, XmlUtil.intAttr(p, "num", 1)));
                columnGap = Math.max(0, XmlUtil.intAttr(p, "space", 720));
                boolean equal = !"0".equals(XmlUtil.attr(p, "equalWidth"))
                        && !"false".equals(XmlUtil.attr(p, "equalWidth"));
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if (!equal && "col".equals(p.getName())) {
                        explicitColumns.add(new int[]{Math.max(0, XmlUtil.intAttr(p, "w", 0)),
                                Math.max(0, XmlUtil.intAttr(p, "space", 0))});
                    }
                    XmlUtil.skip(p);
                }
                continue;
            }
            switch (p.getName()) {
                case "pgSz": {
                    s.pageWidth = clampPage(XmlUtil.intAttr(p, "w", s.pageWidth));
                    s.pageHeight = clampPage(XmlUtil.intAttr(p, "h", s.pageHeight));
                    break;
                }
                case "pgMar":
                    s.marginTop = Math.abs(XmlUtil.intAttr(p, "top", s.marginTop));
                    s.marginBottom = Math.abs(XmlUtil.intAttr(p, "bottom", s.marginBottom));
                    s.marginLeft = Math.max(0, XmlUtil.intAttr(p, "left", s.marginLeft))
                            + Math.max(0, XmlUtil.intAttr(p, "gutter", 0));
                    s.marginRight = Math.max(0, XmlUtil.intAttr(p, "right", s.marginRight));
                    s.headerDistance = Math.max(0, XmlUtil.intAttr(p, "header", s.headerDistance));
                    s.footerDistance = Math.max(0, XmlUtil.intAttr(p, "footer", s.footerDistance));
                    break;
                case "type":
                    s.continuous = "continuous".equals(XmlUtil.attr(p, "val"));
                    break;
                case "titlePg":
                    s.titlePage = XmlUtil.toggle(p);
                    break;
                case "pgNumType": {
                    s.pageNumberStart = XmlUtil.intAttr(p, "start", -1);
                    String fmt = XmlUtil.attr(p, "fmt");
                    if (fmt != null) s.pageNumberFormat = fmt;
                    break;
                }
                case "headerReference":
                case "footerReference": {
                    boolean header = "headerReference".equals(p.getName());
                    String type = XmlUtil.attr(p, "type");
                    List<Block> content = headerFooter(XmlUtil.attr(p, "id"), header ? "hdr" : "ftr");
                    if ("first".equals(type)) {
                        if (header) s.firstHeader = content;
                        else s.firstFooter = content;
                    } else if (type == null || "default".equals(type)) {
                        if (header) s.header = content;
                        else s.footer = content;
                    }
                    break;
                }
                default:
                    break;
            }
            XmlUtil.skip(p);
        }
        // Keep at least a little room for text even with odd margins.
        int minContent = 1440;
        if (s.marginLeft + s.marginRight > s.pageWidth - minContent) {
            s.marginLeft = s.marginRight = Math.max(0, (s.pageWidth - minContent) / 2);
        }
        if (s.marginTop + s.marginBottom > s.pageHeight - minContent) {
            s.marginTop = s.marginBottom = Math.max(0, (s.pageHeight - minContent) / 2);
        }
        int content = s.pageWidth - s.marginLeft - s.marginRight;
        if (explicitColumns.size() > 1) {
            int n = explicitColumns.size();
            s.columnWidths = new int[n];
            s.columnGaps = new int[n];
            for (int i = 0; i < n; i++) {
                s.columnWidths[i] = explicitColumns.get(i)[0];
                s.columnGaps[i] = explicitColumns.get(i)[1];
            }
        } else if (columns > 1) {
            int w = Math.max(720, (content - columnGap * (columns - 1)) / columns);
            s.columnWidths = new int[columns];
            s.columnGaps = new int[columns];
            Arrays.fill(s.columnWidths, w);
            Arrays.fill(s.columnGaps, columnGap);
        }
        return s;
    }

    private static int clampPage(int v) {
        return Math.max(2880, Math.min(31680, v));
    }

    /** Parses a header or footer part (cached, since sections often share them). */
    private List<Block> headerFooter(String relId, String root) throws XmlPullParserException, IOException {
        ZipParts.Rel rel = relId == null ? null : rels.get(relId);
        if (rel == null || rel.external) return new ArrayList<>();
        List<Block> cached = headerCache.get(rel.target);
        if (cached != null) return cached;
        List<Block> blocks = new ArrayList<>();
        headerCache.put(rel.target, blocks);

        Map<String, ZipParts.Rel> savedRels = rels;
        List<Field> savedFields = fields;
        String savedTable = tableStyle;
        ZipParts.Part part = ZipParts.open(zip, rel.target, xml);
        if (part == null) return blocks;
        try {
            rels = ZipParts.rels(zip, rel.target, xml);
            fields = new ArrayList<>();
            tableStyle = null;
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && root.equals(p.getName())) {
                    parseBlocks(p, blocks);
                    break;
                }
            }
        } finally {
            part.close();
            rels = savedRels;
            fields = savedFields;
            tableStyle = savedTable;
        }
        trimTrailingEmpty(blocks);
        return blocks;
    }

    // ------------------------------------------------------------- footnotes

    /** Footnote text by id, read before the body; numbered in the order the body refers to them. */
    private final Map<String, List<Block>> footnoteText = new HashMap<>();
    private int footnoteCount;

    private void parseFootnotes(String part) throws XmlPullParserException, IOException {
        Map<String, ZipParts.Rel> savedRels = rels;
        ZipParts.Part fp = ZipParts.open(zip, part, xml);
        if (fp == null) return;
        try {
            rels = ZipParts.rels(zip, part, xml);
            XmlPullParser p = fp.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG || !"footnote".equals(p.getName())) continue;
                String type = XmlUtil.attr(p, "type");
                String id = XmlUtil.attr(p, "id");
                if (id == null || "separator".equals(type) || "continuationSeparator".equals(type)
                        || "continuationNotice".equals(type)) {
                    XmlUtil.skip(p);
                    continue;
                }
                List<Block> blocks = new ArrayList<>();
                fields = new ArrayList<>();
                parseBlocks(p, blocks);
                trimTrailingEmpty(blocks);
                footnoteText.put(id, blocks);
            }
        } finally {
            fp.close();
            rels = savedRels;
            fields = new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------- blocks

    /** Parses block-level children (paragraphs, tables) of the current element. */
    private void parseBlocks(XmlPullParser p, List<Block> out) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "p":
                    parseParagraph(p, out);
                    break;
                case "tbl":
                    out.add(parseTable(p));
                    break;
                case "sectPr":
                    // Only the body has one: the properties of the last section.
                    lastSection = parseSection(p);
                    break;
                case "Fallback":
                case "del":
                case "moveFrom":
                case "sdtPr":
                case "sdtEndPr":
                    XmlUtil.skip(p);
                    break;
                default:
                    // Containers such as sdt, sdtContent, customXml, ins, AlternateContent.
                    parseBlocks(p, out);
            }
        }
    }

    /** State for the paragraph being built; images and page breaks split it into several blocks. */
    private final class ParaCtx {
        final List<Block> out;
        final List<Block> deferred = new ArrayList<>();
        /** Floating pictures and text boxes anchored to this paragraph; placed relative to its top. */
        final List<Drawing> floats = new ArrayList<>();
        Paragraph para = new Paragraph();
        RunProps base = runDefaults.copy();
        Section section;

        /** Index in {@link #out} where this paragraph's blocks begin. */
        final int start;

        ParaCtx(List<Block> out) {
            this.out = out;
            this.start = out.size();
        }

        void addRun(RunProps props, CharSequence text) {
            if (text.length() == 0 || Boolean.TRUE.equals(props.hidden) || inPageFieldResult()) return;
            Run run = toRun(props);
            List<Run> runs = para.runs;
            if (!runs.isEmpty()) {
                Run last = runs.get(runs.size() - 1);
                if (last.sameFormat(run)) {
                    last.text.append(text);
                    return;
                }
            }
            run.text.append(text);
            runs.add(run);
        }

        void addPageNumber(RunProps props) {
            Run run = toRun(props);
            run.pageNumber = true;
            run.text.append('#');
            para.runs.add(run);
        }

        /** Ends the current part of the paragraph and starts a continuation after {@code b}. */
        void split(Block b) {
            Paragraph next = para.continuation();
            if (!para.isEmpty()) {
                para.spaceAfter = 0;
                out.add(para);
            } else {
                next.spaceBefore = para.spaceBefore;
            }
            out.add(b);
            para = next;
        }

        void addImage(Image img) {
            img.align = para.align;
            boolean leading = para.isEmpty();
            if (leading) img.spaceBefore = para.spaceBefore;
            split(img);
            if (leading) para.spaceBefore = 0;
            lastImage = img;
        }

        /** Image that ended the previous part of this paragraph, if nothing came after it yet. */
        Image lastImage;

        void finish() {
            if (lastImage != null && para.isEmpty() && out.get(out.size() - 1) == lastImage) {
                // A paragraph holding only a picture is one line as tall as the picture.
                lastImage.spaceAfter = para.spaceAfter;
            } else {
                // Keep empty paragraphs: authors use them as vertical spacing.
                out.add(para);
            }
            // Floating pictures go first, so the layout knows where their paragraph starts.
            out.addAll(start, floats);
            out.addAll(deferred);
            if (section != null) out.add(new SectionBreak(section));
        }
    }

    private void parseParagraph(XmlPullParser p, List<Block> out) throws XmlPullParserException, IOException {
        ParaCtx ctx = new ParaCtx(out);
        // pPr comes first when present; resolve exactly once so list counters advance once.
        boolean resolved = false;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("pPr".equals(p.getName()) && !resolved) {
                ParaProps direct = new ParaProps();
                String[] styleId = {defaultParagraphStyle};
                RunProps mark = new RunProps();
                Section[] section = new Section[1];
                parsePPr(p, direct, styleId, mark, section);
                applyProps(ctx, styleId[0], direct, mark);
                ctx.section = section[0];
                resolved = true;
            } else {
                if (!resolved) {
                    applyProps(ctx, defaultParagraphStyle, new ParaProps(), null);
                    resolved = true;
                }
                parseInline(p, ctx);
            }
        }
        if (!resolved) applyProps(ctx, defaultParagraphStyle, new ParaProps(), null);
        ctx.finish();
    }

    /** Resolves defaults, table style, paragraph style, list level and direct formatting. */
    private void applyProps(ParaCtx ctx, String styleId, ParaProps direct, RunProps mark) {
        Paragraph para = ctx.para;
        Style table = tableStyle == null ? null : styles.get(tableStyle);

        ParaProps eff = new ParaProps();
        eff.apply(paraDefaults);
        if (table != null) eff.apply(resolvePara(tableStyle, 0));
        ParaProps fromStyle = resolvePara(styleId, 0);
        eff.apply(fromStyle);

        String numId = direct.numId != null ? direct.numId : fromStyle.numId;
        int ilvl = direct.ilvl != null ? direct.ilvl : fromStyle.ilvl != null ? fromStyle.ilvl : 0;
        boolean list = numId != null && !numId.isEmpty() && !"0".equals(numId);
        if (list) {
            int[] ind = numbering.indent(numId, ilvl);
            if (ind != null) {
                if (ind[0] >= 0) eff.indLeft = ind[0];
                if (ind[1] >= 0) eff.firstLine = -ind[1];
            }
        }
        eff.apply(direct);

        RunProps base = runDefaults.copy();
        if (table != null) base.apply(resolveRun(tableStyle, 0));
        base.apply(resolveRun(styleId, 0));
        ctx.base = base;

        para.styleId = styleId;
        para.heading = headingLevel(styleId, eff);
        para.align = eff.align != null ? eff.align : DocModel.ALIGN_START;
        para.indLeft = eff.indLeft != null ? eff.indLeft : 0;
        para.indRight = eff.indRight != null ? eff.indRight : 0;
        para.firstLine = eff.firstLine != null ? eff.firstLine : 0;
        para.spaceBefore = Math.max(0, eff.spaceBefore != null ? eff.spaceBefore : 0);
        para.spaceAfter = Math.max(0, eff.spaceAfter != null ? eff.spaceAfter : 0);
        para.line = eff.line != null ? eff.line : 240;
        para.lineRule = eff.lineRule != null ? eff.lineRule : DocModel.LINE_AUTO;
        if (para.line <= 0) {
            para.line = 240;
            para.lineRule = DocModel.LINE_AUTO;
        }
        para.contextualSpacing = Boolean.TRUE.equals(eff.contextual);
        para.pageBreakBefore = Boolean.TRUE.equals(eff.pageBreakBefore);
        para.keepNext = Boolean.TRUE.equals(eff.keepNext);
        setTabStops(para, eff.tabs);

        RunProps markProps = base.copy();
        markProps.apply(mark);
        Run markRun = toRun(markProps);
        para.markHalfPoints = markRun.halfPoints;
        para.markFont = markRun.font;

        para.listPrefix = null;
        para.listSuffix = "\t";
        if (list) {
            para.listPrefix = numbering.next(numId, ilvl);
            para.listSuffix = numbering.suffix(numId, ilvl);
        }
    }

    /** Merges inherited and direct tab stops (later ones replace or clear earlier ones). */
    private static void setTabStops(Paragraph para, List<int[]> tabs) {
        if (tabs == null || tabs.isEmpty()) return;
        Map<Integer, int[]> byPos = new HashMap<>();
        for (int[] t : tabs) {
            if (t[1] < 0) byPos.remove(t[0]);
            else byPos.put(t[0], t);
        }
        int[] pos = new int[byPos.size()];
        int i = 0;
        for (Integer k : byPos.keySet()) pos[i++] = k;
        Arrays.sort(pos);
        para.tabs = pos;
        para.tabAligns = new int[pos.length];
        para.tabLeaders = new char[pos.length];
        for (i = 0; i < pos.length; i++) {
            int[] t = byPos.get(pos[i]);
            para.tabAligns[i] = t[1];
            para.tabLeaders[i] = (char) t[2];
        }
    }

    private static int leader(String v) {
        if (v == null) return 0;
        switch (v) {
            case "dot": return '.';
            case "hyphen": return '-';
            case "underscore":
            case "heavy": return '_';
            case "middleDot": return '·';
            default: return 0;
        }
    }

    /** Handles run-level content inside a paragraph (runs, hyperlinks, fields, ...). */
    private void parseInline(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "r":
                parseRun(p, ctx);
                return;
            case "fldSimple":
                if (isPageField(XmlUtil.attr(p, "instr"))) {
                    ctx.addPageNumber(ctx.base);
                    XmlUtil.skip(p);
                } else {
                    int d = p.getDepth();
                    while (XmlUtil.nextChild(p, d)) parseInline(p, ctx);
                }
                return;
            case "del":
            case "moveFrom":
            case "Fallback":
            case "bookmarkStart":
            case "bookmarkEnd":
            case "proofErr":
            case "commentRangeStart":
            case "commentRangeEnd":
            case "sdtPr":
            case "sdtEndPr":
                XmlUtil.skip(p);
                return;
            case "p":
                // Paragraph nested in inline content (rare): parse it as its own block.
                parseParagraph(p, ctx.deferred);
                return;
            default:
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) parseInline(p, ctx);
        }
    }

    private void parseRun(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        RunProps props = ctx.base;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("rPr".equals(p.getName())) props = parseRunPr(p, ctx.base);
            else parseRunChild(p, ctx, props);
        }
    }

    /** Effective run formatting: paragraph base, then character style, then direct formatting. */
    private RunProps parseRunPr(XmlPullParser p, RunProps base) throws XmlPullParserException, IOException {
        RunProps direct = new RunProps();
        String charStyle = null;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("rStyle".equals(p.getName())) {
                charStyle = XmlUtil.attr(p, "val");
                XmlUtil.skip(p);
            } else {
                parseSingleRunProp(p, direct);
            }
        }
        RunProps eff = base.copy();
        if (charStyle != null) eff.apply(resolveRun(charStyle, 0));
        eff.apply(direct);
        return eff;
    }

    private void parseRunChild(XmlPullParser p, ParaCtx ctx, RunProps props)
            throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "t":
                ctx.addRun(props, XmlUtil.text(p));
                return;
            case "tab":
            case "ptab":
                ctx.addRun(props, "\t");
                XmlUtil.skip(p);
                return;
            case "br": {
                String type = XmlUtil.attr(p, "type");
                if ("page".equals(type)) {
                    if (!inPageFieldResult()) ctx.split(new PageBreak());
                } else if ("column".equals(type)) {
                    if (!inPageFieldResult()) ctx.split(new ColumnBreak());
                } else {
                    ctx.addRun(props, "\n");
                }
                XmlUtil.skip(p);
                return;
            }
            case "cr":
                ctx.addRun(props, "\n");
                XmlUtil.skip(p);
                return;
            case "noBreakHyphen":
                ctx.addRun(props, "‑");
                XmlUtil.skip(p);
                return;
            case "footnoteReference": {
                List<Block> text = footnoteText.get(XmlUtil.attr(p, "id"));
                if (text != null) {
                    DocModel.Footnote note = new DocModel.Footnote(++footnoteCount, text);
                    RunProps mark = props.copy();
                    // The reference style is normally superscript already; make sure it is.
                    mark.vertAlign = DocModel.VALIGN_SUPER;
                    ctx.addRun(mark, String.valueOf(note.number));
                    if (ctx.para.footnotes == null) ctx.para.footnotes = new ArrayList<>();
                    ctx.para.footnotes.add(note);
                }
                XmlUtil.skip(p);
                return;
            }
            case "footnoteRef":
                // Inside a footnote: its own number, filled in when the note is laid out.
                ctx.addPageNumber(props);
                XmlUtil.skip(p);
                return;
            case "fldChar": {
                String type = XmlUtil.attr(p, "fldCharType");
                if ("begin".equals(type)) {
                    fields.add(new Field());
                } else if ("separate".equals(type) && !fields.isEmpty()) {
                    Field f = fields.get(fields.size() - 1);
                    f.separated = true;
                    if (isPageField(f.instr.toString())) {
                        ctx.addPageNumber(props);
                        f.page = true;
                    }
                } else if ("end".equals(type) && !fields.isEmpty()) {
                    fields.remove(fields.size() - 1);
                }
                XmlUtil.skip(p);
                return;
            }
            case "instrText": {
                String t = XmlUtil.text(p);
                if (!fields.isEmpty()) {
                    Field f = fields.get(fields.size() - 1);
                    if (!f.separated) f.instr.append(t);
                }
                return;
            }
            case "drawing":
            case "pict":
            case "object":
                parseGraphic(p, ctx);
                return;
            case "AlternateContent":
            case "Choice": {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) parseRunChild(p, ctx, props);
                return;
            }
            default:
                // delText, footnoteReference, Fallback, ...
                XmlUtil.skip(p);
        }
    }

    private static boolean isPageField(String instr) {
        if (instr == null) return false;
        String s = instr.trim();
        int sp = s.indexOf(' ');
        return "PAGE".equalsIgnoreCase(sp < 0 ? s : s.substring(0, sp));
    }

    /** True while reading the cached result of a PAGE field, which is replaced when drawn. */
    private boolean inPageFieldResult() {
        for (Field f : fields) if (f.page && f.separated) return true;
        return false;
    }

    /** What a drawing element holds: picture, size and, for floating ones, position and wrapping. */
    private static final class Graphic {
        String embed;
        long cx, cy;
        boolean floating, wrapsText, block;
        int hRel = DocModel.REL_MARGIN, vRel = DocModel.REL_PARAGRAPH;
        int hAlign = -1, vAlign = -1;
        long hOffset, vOffset;
        /** Text-box content, or null. */
        List<Block> box;
        /** Outline colour of the shape, 0 for none. */
        int outline;
    }

    /** Scans a drawing for an embedded picture and text-box content. */
    private void parseGraphic(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        Graphic g = new Graphic();
        scanGraphic(p, ctx, g);
        boolean floating = g.floating && !g.block;
        if (g.box != null) {
            if (floating && g.cx > 0 && g.cy > 0) {
                TextBox tb = new TextBox(g.box, g.cx, g.cy);
                tb.border = g.outline;
                ctx.floats.add(position(tb, g));
            } else {
                // Inline or wrapping text boxes: their text follows the paragraph.
                ctx.deferred.addAll(g.box);
            }
        }
        if (g.embed == null) return;
        ZipParts.Rel rel = rels.get(g.embed);
        if (rel == null || rel.external || ZipParts.entry(zip, rel.target) == null) return;
        Image img = new Image(rel.target, g.cx, g.cy);
        if (floating) ctx.floats.add(position(img, g));
        else ctx.addImage(img);
    }

    private static Drawing position(Drawing dr, Graphic g) {
        dr.floating = true;
        dr.wrapsText = g.wrapsText;
        dr.hRel = g.hRel;
        dr.vRel = g.vRel;
        dr.hAlign = g.hAlign;
        dr.vAlign = g.vAlign;
        dr.hOffset = g.hOffset;
        dr.vOffset = g.vOffset;
        return dr;
    }

    private void scanGraphic(XmlPullParser p, ParaCtx ctx, Graphic g) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "Fallback":
                    XmlUtil.skip(p);
                    break;
                case "txbxContent": {
                    List<Field> saved = fields;
                    fields = new ArrayList<>();
                    if (g.box == null) g.box = new ArrayList<>();
                    parseBlocks(p, g.box);
                    fields = saved;
                    break;
                }
                case "ln": {
                    // Shape outline: visible unless it says noFill.
                    int color = 0xFF000000;
                    boolean none = false;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("noFill".equals(p.getName())) {
                            none = true;
                        } else if ("solidFill".equals(p.getName())) {
                            int d3 = p.getDepth();
                            while (XmlUtil.nextChild(p, d3)) {
                                if ("srgbClr".equals(p.getName())) {
                                    int c = XmlUtil.hexColor(XmlUtil.attr(p, "val"));
                                    if (c != 0) color = c;
                                }
                                XmlUtil.skip(p);
                            }
                            continue;
                        }
                        XmlUtil.skip(p);
                    }
                    g.outline = none ? 0 : color;
                    break;
                }
                case "anchor":
                    g.floating = true;
                    scanGraphic(p, ctx, g);
                    break;
                case "positionH":
                case "positionV": {
                    boolean h = "positionH".equals(p.getName());
                    int rel = relative(XmlUtil.attr(p, "relativeFrom"));
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String n = p.getName();
                        String v = XmlUtil.text(p).trim();
                        if ("posOffset".equals(n)) {
                            long off = parseSigned(v);
                            if (h) g.hOffset = off;
                            else g.vOffset = off;
                        } else if ("align".equals(n)) {
                            int al = "center".equals(v) ? DocModel.ALIGN_CENTER
                                    : "right".equals(v) || "bottom".equals(v) || "outside".equals(v)
                                    ? DocModel.ALIGN_END : DocModel.ALIGN_START;
                            if (h) g.hAlign = al;
                            else g.vAlign = al;
                        }
                    }
                    if (h) g.hRel = rel;
                    else g.vRel = rel;
                    break;
                }
                case "wrapTopAndBottom":
                    if (g.floating) g.block = true;
                    XmlUtil.skip(p);
                    break;
                case "wrapSquare":
                case "wrapTight":
                case "wrapThrough":
                    g.wrapsText = true;
                    XmlUtil.skip(p);
                    break;
                case "extent":
                    if (g.cx == 0) {
                        g.cx = parseLong(XmlUtil.attr(p, "cx"));
                        g.cy = parseLong(XmlUtil.attr(p, "cy"));
                    }
                    XmlUtil.skip(p);
                    break;
                case "blip":
                    if (g.embed == null) g.embed = XmlUtil.attr(p, "embed");
                    XmlUtil.skip(p);
                    break;
                case "imagedata":
                    if (g.embed == null) g.embed = XmlUtil.attr(p, "id");
                    XmlUtil.skip(p);
                    break;
                default:
                    scanGraphic(p, ctx, g);
            }
        }
    }

    private static int relative(String v) {
        if ("page".equals(v)) return DocModel.REL_PAGE;
        if ("paragraph".equals(v) || "line".equals(v) || "character".equals(v)) return DocModel.REL_PARAGRAPH;
        return DocModel.REL_MARGIN;
    }

    private static long parseSigned(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String v) {
        if (v == null) return 0;
        try {
            return Math.max(0, Long.parseLong(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- tables

    private Table parseTable(XmlPullParser p) throws XmlPullParserException, IOException {
        Table t = new Table();
        String saved = tableStyle;
        tableStyle = null;
        try {
            int d = p.getDepth();
            while (XmlUtil.nextChild(p, d)) parseTableChild(p, t);
        } finally {
            tableStyle = saved;
        }
        return t;
    }

    private void parseTableChild(XmlPullParser p, Table t) throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "tblPr": {
                Border[] direct = new Border[6];
                int[] margins = {-1, -1, -1, -1};
                String[] style = new String[1];
                int[] props = {0, DocModel.ALIGN_START};
                parseTablePr(p, direct, margins, new Object[]{style, props});
                tableStyle = style[0];
                int[] styleMargins = {-1, -1, -1, -1};
                resolveTableStyle(tableStyle, 0, t.borders, styleMargins);
                for (int i = 0; i < 6; i++) if (direct[i] != null) t.borders[i] = direct[i];
                for (int i = 0; i < 4; i++) {
                    int m = margins[i] >= 0 ? margins[i] : styleMargins[i];
                    if (m >= 0) t.cellMargin[i] = m;
                }
                t.indent = props[0];
                t.align = props[1];
                break;
            }
            case "tblGrid": {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    if ("gridCol".equals(p.getName())) t.grid.add(Math.max(0, XmlUtil.intAttr(p, "w", 0)));
                    XmlUtil.skip(p);
                }
                break;
            }
            case "tr": {
                Row row = new Row();
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) parseRowChild(p, row);
                t.rows.add(row);
                break;
            }
            case "del":
            case "sdtPr":
            case "Fallback":
                XmlUtil.skip(p);
                break;
            default: {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) parseTableChild(p, t);
            }
        }
    }

    /**
     * Reads tblPr borders and cell margins. {@code extra} (tables only) receives
     * {String[] style, int[] {indent, align}}.
     */
    private void parseTablePr(XmlPullParser p, Border[] borders, int[] margins, Object[] extra)
            throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("tblBorders".equals(n)) {
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    int i = borderIndex(p.getName());
                    if (i >= 0) borders[i] = border(p);
                    XmlUtil.skip(p);
                }
            } else if ("tblCellMar".equals(n)) {
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    int i = borderIndex(p.getName());
                    if (i >= 0 && i < 4) margins[i] = Math.max(0, XmlUtil.intAttr(p, "w", 0));
                    XmlUtil.skip(p);
                }
            } else if (extra != null && "tblStyle".equals(n)) {
                ((String[]) extra[0])[0] = XmlUtil.attr(p, "val");
                XmlUtil.skip(p);
            } else if (extra != null && "tblInd".equals(n)) {
                if (!"pct".equals(XmlUtil.attr(p, "type"))) ((int[]) extra[1])[0] = XmlUtil.intAttr(p, "w", 0);
                XmlUtil.skip(p);
            } else if (extra != null && "jc".equals(n)) {
                ((int[]) extra[1])[1] = align(XmlUtil.attr(p, "val"));
                XmlUtil.skip(p);
            } else {
                XmlUtil.skip(p);
            }
        }
    }

    private static int borderIndex(String name) {
        switch (name) {
            case "top": return DocModel.B_TOP;
            case "left":
            case "start": return DocModel.B_LEFT;
            case "bottom": return DocModel.B_BOTTOM;
            case "right":
            case "end": return DocModel.B_RIGHT;
            case "insideH": return DocModel.B_INSIDE_H;
            case "insideV": return DocModel.B_INSIDE_V;
            default: return -1;
        }
    }

    private static Border border(XmlPullParser p) {
        String v = XmlUtil.attr(p, "val");
        if (v == null || "nil".equals(v) || "none".equals(v)) return Border.NONE;
        int size = Math.max(2, Math.min(96, XmlUtil.intAttr(p, "sz", 4)));
        int color = XmlUtil.hexColor(XmlUtil.attr(p, "color"));
        return new Border(size, color == 0 ? 0xFF000000 : color);
    }

    private void parseRowChild(XmlPullParser p, Row row) throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "tc": {
                Cell c = new Cell();
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    String n = p.getName();
                    if ("tcPr".equals(n)) {
                        parseCellProps(p, c);
                    } else if ("p".equals(n)) {
                        parseParagraph(p, c.blocks);
                    } else if ("tbl".equals(n)) {
                        flattenTable(parseTable(p), c.blocks);
                    } else if ("sdtPr".equals(n) || "Fallback".equals(n)) {
                        XmlUtil.skip(p);
                    } else {
                        List<Block> tmp = new ArrayList<>();
                        parseBlocks(p, tmp);
                        for (Block b : tmp) {
                            if (b instanceof Table) flattenTable((Table) b, c.blocks);
                            else c.blocks.add(b);
                        }
                    }
                }
                row.cells.add(c);
                break;
            }
            case "trPr": {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    if ("trHeight".equals(p.getName())) {
                        row.height = Math.max(0, XmlUtil.intAttr(p, "val", 0));
                        row.exactHeight = "exact".equals(XmlUtil.attr(p, "hRule"));
                    }
                    XmlUtil.skip(p);
                }
                break;
            }
            case "tblPrEx":
            case "del":
            case "sdtPr":
                XmlUtil.skip(p);
                break;
            default: {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) parseRowChild(p, row);
            }
        }
    }

    private void parseCellProps(XmlPullParser p, Cell c) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "gridSpan":
                    c.span = Math.max(1, Math.min(64, XmlUtil.intAttr(p, "val", 1)));
                    break;
                case "vMerge": {
                    String v = XmlUtil.attr(p, "val");
                    c.mergedContinue = v == null || "continue".equals(v);
                    break;
                }
                case "tcW":
                    if ("dxa".equals(XmlUtil.attr(p, "type")) || XmlUtil.attr(p, "type") == null) {
                        c.width = Math.max(0, XmlUtil.intAttr(p, "w", 0));
                    }
                    break;
                case "vAlign": {
                    String v = XmlUtil.attr(p, "val");
                    c.vAlign = "center".equals(v) ? DocModel.CELL_CENTER
                            : "bottom".equals(v) ? DocModel.CELL_BOTTOM : DocModel.CELL_TOP;
                    break;
                }
                case "shd": {
                    int col = XmlUtil.hexColor(XmlUtil.attr(p, "fill"));
                    if (col != 0xFFFFFFFF) c.background = col;
                    break;
                }
                case "tcBorders": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        int i = borderIndex(p.getName());
                        if (i >= 0 && i < 4) c.borders[i] = border(p);
                        XmlUtil.skip(p);
                    }
                    continue;
                }
                default:
                    break;
            }
            XmlUtil.skip(p);
        }
    }

    /** Nested tables are shown as plain paragraphs inside the cell. */
    private static void flattenTable(Table t, List<Block> out) {
        for (Row row : t.rows) {
            for (Cell c : row.cells) {
                for (Block b : c.blocks) {
                    if (b instanceof Paragraph && ((Paragraph) b).isEmpty()) continue;
                    out.add(b);
                }
            }
        }
    }

    private static void trimTrailingEmpty(List<Block> blocks) {
        while (blocks.size() > 1) {
            Block last = blocks.get(blocks.size() - 1);
            if (last instanceof Paragraph && ((Paragraph) last).isEmpty()) blocks.remove(blocks.size() - 1);
            else break;
        }
    }
}
