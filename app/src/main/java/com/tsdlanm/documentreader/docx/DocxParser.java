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
import com.tsdlanm.documentreader.util.SymbolFonts;
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
        /** Shading colour, 0 for explicitly none. */
        Integer shading;
        /** Paragraph borders by B_* side (null entries: not set here). */
        Border[] borders;
        Boolean contextual, pageBreakBefore, keepNext, bidi;
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
            if (o.shading != null) shading = o.shading;
            if (o.borders != null) {
                Border[] mine = borders == null ? new Border[5] : borders.clone();
                for (int i = 0; i < 5; i++) if (o.borders[i] != null) mine[i] = o.borders[i];
                borders = mine;
            }
            if (o.contextual != null) contextual = o.contextual;
            if (o.pageBreakBefore != null) pageBreakBefore = o.pageBreakBefore;
            if (o.keepNext != null) keepNext = o.keepNext;
            if (o.bidi != null) bidi = o.bidi;
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
        /** Table styles: formatting of parts of the table (firstRow, band1Horz...), by type. */
        final Map<String, TableCond> conds = new HashMap<>();
    }

    /** A table style's tblStylePr: text and cell formatting for one part of the table. */
    private static final class TableCond {
        final RunProps run = new RunProps();
        /** Cell fill, 0 for none set. */
        int fill;
        final Border[] borders = new Border[6];
    }

    /** Parts of the table its style formats, from tblLook (Word's default when absent). */
    private static final int LOOK_FIRST_ROW = 0x20, LOOK_LAST_ROW = 0x40, LOOK_FIRST_COL = 0x80,
            LOOK_LAST_COL = 0x100, LOOK_NO_HBAND = 0x200, LOOK_NO_VBAND = 0x400;

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
    /** Word's size when neither the defaults nor the Normal style give one: 10 pt (not 11). */
    private int defaultHalfPoints = 20;
    private int defaultTab = 720;
    /** Depth of internal links (w:hyperlink w:anchor) being read. */
    private int internalLinks;
    private String defaultParagraphStyle;
    /** Style of the table whose cells are being read, or null. */
    private String tableStyle;
    /** settings.xml evenAndOddHeaders: even pages use their own header and footer. */
    private boolean evenAndOdd;
    /** tblLook of the table being read (LOOK_* bits). */
    private int tableLook;
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
        Document doc = new Document(blocks, defaultHalfPoints, lastSection != null ? lastSection : new Section(),
                defaultTab);
        doc.evenAndOddHeaders = evenAndOdd;
        return doc;
    }

    // ------------------------------------------------------- theme, settings

    /** Theme colours (dk1, accent1, ...), for shapes. */
    private final Map<String, Integer> themeColors = new HashMap<>();

    /** Theme colour by name, with Word's aliases (tx1 is dk1...). */
    private int themeColor(String name) {
        if (name == null) return 0;
        String key = name;
        switch (name) {
            case "tx1": case "text1": key = "dk1"; break;
            case "bg1": case "background1": key = "lt1"; break;
            case "tx2": case "text2": key = "dk2"; break;
            case "bg2": case "background2": key = "lt2"; break;
            default: break;
        }
        Integer c = themeColors.get(key);
        return c != null ? c : DrawingML.defaultScheme(key);
    }

    private final DrawingML.Scheme scheme = this::themeColor;

    private void parseTheme(XmlPullParser p) throws XmlPullParserException, IOException {
        boolean major = false, minor = false, colors = false;
        String slot = null;
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.END_TAG) {
                if ("majorFont".equals(p.getName())) major = false;
                else if ("minorFont".equals(p.getName())) minor = false;
                else if ("clrScheme".equals(p.getName())) colors = false;
                continue;
            }
            if (e != XmlPullParser.START_TAG) continue;
            String n = p.getName();
            if ("clrScheme".equals(n)) {
                colors = true;
            } else if (colors && ("srgbClr".equals(n) || "sysClr".equals(n))) {
                int c = XmlUtil.hexColor(XmlUtil.attr(p, "srgbClr".equals(n) ? "val" : "lastClr"));
                if (slot != null && c != 0) themeColors.put(slot, c);
            } else if (colors) {
                slot = n;
            } else if ("majorFont".equals(n)) {
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
            if (e != XmlPullParser.START_TAG) continue;
            if ("defaultTabStop".equals(p.getName())) {
                int v = XmlUtil.intAttr(p, "val", 720);
                if (v > 0) defaultTab = v;
            } else if ("evenAndOddHeaders".equals(p.getName())) {
                evenAndOdd = XmlUtil.toggle(p);
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
                        case "tblStylePr": {
                            String kind = XmlUtil.attr(p, "type");
                            TableCond c = new TableCond();
                            int d2 = p.getDepth();
                            while (XmlUtil.nextChild(p, d2)) {
                                String cn = p.getName();
                                if ("rPr".equals(cn)) {
                                    parseRunProps(p, c.run);
                                } else if ("tcPr".equals(cn)) {
                                    int d3 = p.getDepth();
                                    while (XmlUtil.nextChild(p, d3)) {
                                        String tn = p.getName();
                                        if ("shd".equals(tn)) {
                                            int col = XmlUtil.hexColor(XmlUtil.attr(p, "fill"));
                                            if (col != 0) c.fill = col;
                                            XmlUtil.skip(p);
                                        } else if ("tcBorders".equals(tn)) {
                                            int d4 = p.getDepth();
                                            while (XmlUtil.nextChild(p, d4)) {
                                                int i = borderIndex(p.getName());
                                                if (i >= 0) c.borders[i] = border(p);
                                                XmlUtil.skip(p);
                                            }
                                        } else {
                                            XmlUtil.skip(p);
                                        }
                                    }
                                } else if ("tblPr".equals(cn)) {
                                    parseTablePr(p, c.borders, new int[4], null);
                                } else {
                                    XmlUtil.skip(p);
                                }
                            }
                            if (kind != null) s.conds.put(kind, c);
                            break;
                        }
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

    /** A table style's formatting for one part of the table, following basedOn; null when none. */
    private TableCond tableCond(String styleId, String kind, int depth) {
        Style s = styleId == null ? null : styles.get(styleId);
        if (s == null || depth > 10) return null;
        TableCond own = s.conds.get(kind);
        return own != null ? own : tableCond(s.basedOn, kind, depth + 1);
    }

    /**
     * Applies the table style's conditional formatting (header row, first column, banded rows...)
     * where the table's own cells and runs set nothing else.
     */
    private void applyTableStyle(Table t, String styleId, int look) {
        if (styleId == null || t.rows.isEmpty()) return;
        int rows = t.rows.size();
        boolean firstRow = (look & LOOK_FIRST_ROW) != 0, lastRow = (look & LOOK_LAST_ROW) != 0;
        boolean firstCol = (look & LOOK_FIRST_COL) != 0, lastCol = (look & LOOK_LAST_COL) != 0;
        int cols = t.columnCount();
        for (int r = 0; r < rows; r++) {
            Row row = t.rows.get(r);
            int body = r - (firstRow ? 1 : 0);
            int c = 0;
            for (Cell cell : row.cells) {
                List<String> kinds = new ArrayList<>();
                kinds.add("wholeTable");
                if ((look & LOOK_NO_VBAND) == 0) kinds.add(c % 2 == 0 ? "band1Vert" : "band2Vert");
                if ((look & LOOK_NO_HBAND) == 0 && body >= 0 && !(lastRow && r == rows - 1)) {
                    kinds.add(body % 2 == 0 ? "band1Horz" : "band2Horz");
                }
                if (firstCol && c == 0) kinds.add("firstCol");
                if (lastCol && c + cell.span >= cols) kinds.add("lastCol");
                if (firstRow && r == 0) kinds.add("firstRow");
                if (lastRow && r == rows - 1 && rows > 1) kinds.add("lastRow");
                int fill = 0;
                RunProps run = new RunProps();
                Border[] borders = new Border[6];
                for (String k : kinds) {
                    TableCond tc = tableCond(styleId, k, 0);
                    if (tc == null) continue;
                    if (tc.fill != 0) fill = tc.fill;
                    run.apply(tc.run);
                    for (int i = 0; i < 6; i++) if (tc.borders[i] != null) borders[i] = tc.borders[i];
                }
                if (cell.background == 0 && fill != 0 && fill != 0xFFFFFFFF) cell.background = fill;
                for (int i = 0; i < 4; i++) if (cell.borders[i] == null && borders[i] != null) cell.borders[i] = borders[i];
                applyRunProps(cell.blocks, run);
                c += cell.span;
            }
        }
    }

    private static void applyRunProps(List<Block> blocks, RunProps run) {
        for (Block b : blocks) {
            if (!(b instanceof Paragraph)) continue;
            for (Run r : ((Paragraph) b).runs) {
                if (Boolean.TRUE.equals(run.bold)) r.bold = true;
                if (Boolean.TRUE.equals(run.italic)) r.italic = true;
                if (Boolean.TRUE.equals(run.caps)) r.caps = true;
                if (run.color != null && run.color != 0 && r.color == 0) r.color = run.color;
            }
        }
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
                // Without a val (often just w:color) Word keeps the inherited underline.
                if (v != null) r.underline = !"none".equals(v);
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
                case "bidi":
                    out.bidi = XmlUtil.toggle(p);
                    XmlUtil.skip(p);
                    break;
                case "pBdr": {
                    if (out.borders == null) out.borders = new Border[5];
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String n = p.getName();
                        int side = "top".equals(n) ? DocModel.B_TOP : "bottom".equals(n) ? DocModel.B_BOTTOM
                                : "left".equals(n) || "start".equals(n) ? DocModel.B_LEFT
                                : "right".equals(n) || "end".equals(n) ? DocModel.B_RIGHT
                                : "between".equals(n) ? DocModel.B_BETWEEN : -1;
                        if (side >= 0) {
                            out.borders[side] = border(p);
                        }
                        XmlUtil.skip(p);
                    }
                    break;
                }
                case "shd": {
                    // Paragraph shading fills the width between the indents.
                    int c = XmlUtil.hexColor(XmlUtil.attr(p, "fill"));
                    out.shading = c == 0xFFFFFFFF ? 0 : c;
                    XmlUtil.skip(p);
                    break;
                }
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
                case "pgBorders": {
                    String disp = XmlUtil.attr(p, "display");
                    s.pageBorderPages = "firstPage".equals(disp) ? 1 : "notFirstPage".equals(disp) ? 2 : 0;
                    s.pageBorderFromPage = "page".equals(XmlUtil.attr(p, "offsetFrom"));
                    s.pageBorders = new Border[4];
                    s.pageBorderSpace = new int[4];
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        int i = borderIndex(p.getName());
                        if (i >= 0 && i < 4) {
                            s.pageBorders[i] = border(p);
                            s.pageBorderSpace[i] = XmlUtil.intAttr(p, "space", 0);
                            String v = XmlUtil.attr(p, "val");
                            // Double styles and art borders (twistedLines1...) are shown as two lines.
                            if (v != null && !"single".equals(v) && !"thick".equals(v) && !v.startsWith("dash")
                                    && !v.startsWith("dot") && !"nil".equals(v) && !"none".equals(v)) {
                                s.pageBorderDouble = true;
                            }
                        }
                        XmlUtil.skip(p);
                    }
                    continue;
                }
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
                    } else if ("even".equals(type)) {
                        if (header) s.evenHeader = content;
                        else s.evenFooter = content;
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
            if (SymbolFonts.isSymbolFont(run.font) || PptxParser.hasPrivateUse(text)) {
                // Symbol and Wingdings glyphs as Unicode, in the paragraph's ordinary font.
                text = SymbolFonts.map(run.font, text);
                if (SymbolFonts.isSymbolFont(run.font)) run.font = null;
            }
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
            // A small picture after some text stays in the line (words or symbols drawn as pictures).
            if (!img.positioned && img.heightEmu > 0 && img.heightEmu <= 914400L * 2
                    && !para.plainText().trim().isEmpty()) {
                Run run = new Run();
                run.image = img;
                run.text.append('￼');
                para.runs.add(run);
                return;
            }
            // Pictures with nothing but spaces between them share a line, as in Word.
            if (lastImage != null && !out.isEmpty() && out.get(out.size() - 1) == lastImage && img.beside.isEmpty()
                    && !img.positioned && !lastImage.positioned && para.listPrefix == null
                    && para.plainText().trim().isEmpty()) {
                lastImage.beside.add(img);
                return;
            }
            img.align = para.align;
            boolean leading = para.isEmpty();
            if (leading) img.spaceBefore = para.spaceBefore;
            split(img);
            if (leading) para.spaceBefore = 0;
            lastImage = img;
        }

        /** Image that ended the previous part of this paragraph, if nothing came after it yet. */
        Image lastImage;
        /** Pictures wrapped above and below (positioned) anchored in this paragraph. */
        final List<Image> blockImages = new ArrayList<>();

        void finish() {
            if (!blockImages.isEmpty()) {
                Image first = blockImages.get(0);
                for (int i = 1; i < blockImages.size(); i++) first.beside.add(blockImages.get(i));
                // Placed well below the top of its paragraph: Word shows the band after the text.
                first.align = para.align;
                if (first.vRel == DocModel.REL_PARAGRAPH && first.vOffset > 12700L * 14 && !para.isEmpty()) {
                    first.afterText = true;
                    deferred.add(0, first);
                } else {
                    // Near the top of the paragraph: before its text.
                    out.add(start, first);
                }
            }
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
        para.rtl = eff.bidi != null && eff.bidi;
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
        para.shading = eff.shading != null ? eff.shading : 0;
        para.borders = null;
        if (eff.borders != null) {
            for (Border b : eff.borders) {
                if (b != null && b.visible()) {
                    para.borders = eff.borders;
                    break;
                }
            }
        }
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
            case "hyperlink": {
                // A link within the document (table of contents, cross-reference) reads as plain
                // text, as Word prints it; links to web pages keep their link look.
                boolean internal = XmlUtil.attr(p, "anchor") != null && XmlUtil.attr(p, "id") == null;
                if (internal) internalLinks++;
                try {
                    int d = p.getDepth();
                    while (XmlUtil.nextChild(p, d)) parseInline(p, ctx);
                } finally {
                    if (internal) internalLinks--;
                }
                return;
            }
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
        boolean linkStyle = charStyle != null && charStyle.toLowerCase(java.util.Locale.ROOT).contains("hyperlink");
        if (charStyle != null && !(linkStyle && internalLinks > 0)) eff.apply(resolveRun(charStyle, 0));
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
        /** Picture crop (a:srcRect) as fractions left, top, right, bottom, or null. */
        float[] crop;
        int effect;
        float alpha = 1f;
        /** Word shapes (wps) and the parts of a group (wpg), in EMU within the drawing's frame. */
        final List<ShapeSpec> shapes = new ArrayList<>();
        boolean group;
    }

    /** One shape of a drawing: a box, line or outline, possibly with text, or a picture in a group. */
    private static final class ShapeSpec {
        long x, y, cx, cy;
        int geometry = DocModel.GEOM_RECT;
        float[] path;
        /** Own fill and line colours (0 none), or null to use the shape style's. */
        Integer fill, line, styleFill, styleLine;
        long lineWidth = -1;
        boolean flipH, flipV;
        float rotation;
        List<Block> box;
        long[] insets;
        int anchor = DocModel.CELL_TOP;
        String embed;
        float[] crop;
        int effect;
        float alpha = 1f;
        /** A VML line's end points, before they become its box. */
        long[][] lineFrom;
        boolean arrowHead, arrowTail;
        /** The box grows with its text (spAutoFit, VML mso-fit-shape-to-text): nothing is cut. */
        boolean autoFit;
        /** STROKE_* of the outline; an open outline (brace, bracket) is never filled. */
        int dash;
        boolean open;
    }

    /** Scans a drawing for an embedded picture and text-box content. */
    private void parseGraphic(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        Graphic g = new Graphic();
        scanGraphic(p, ctx, g);
        boolean floating = g.floating && !g.block;
        if (!g.shapes.isEmpty()) {
            emitShapes(g, ctx, floating);
        } else if (g.box != null) {
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
        img.crop = g.crop;
        img.effect = g.effect;
        img.alpha = Math.max(0f, Math.min(1f, g.alpha));
        if (floating) {
            ctx.floats.add(position(img, g));
        } else if (g.floating) {
            // Wrapped above and below: takes its own lines, but keeps the position it was given.
            position(img, g);
            img.floating = false;
            img.positioned = true;
            // Several in one paragraph share one band, each at its own x; placed in finish().
            ctx.blockImages.add(img);
        } else {
            ctx.addImage(img);
        }
    }

    /** Word shapes and groups: each part becomes a drawing placed within the drawing's frame. */
    private void emitShapes(Graphic g, ParaCtx ctx, boolean floating) {
        boolean single = !g.group && g.shapes.size() == 1;
        boolean inline = !g.floating && g.cx > 0 && g.cy > 0;
        if (inline) {
            // An inline drawing sits on the paragraph's line: placed where the line starts.
            int al = ctx.para.align;
            if (al == DocModel.ALIGN_CENTER || al == DocModel.ALIGN_END) g.hAlign = al;
            else g.hOffset = ctx.para.indLeft * 635L;
        }
        int before = ctx.floats.size();
        emitShapeParts(g, ctx, floating, single);
        if (inline && ctx.floats.size() > before) {
            // The shapes float; a picture-less spacer keeps their room in the text.
            ctx.addImage(new Image(null, g.cx, g.cy));
        }
    }

    private void emitShapeParts(Graphic g, ParaCtx ctx, boolean floating, boolean single) {
        for (ShapeSpec s : g.shapes) {
            long cx = s.cx > 0 || !single ? s.cx : g.cx, cy = s.cy > 0 || !single ? s.cy : g.cy;
            if (cx <= 0 && cy <= 0) continue;
            Drawing dr;
            if (s.embed != null) {
                ZipParts.Rel rel = rels.get(s.embed);
                if (rel == null || rel.external || ZipParts.entry(zip, rel.target) == null || cx <= 0 || cy <= 0) continue;
                Image img = new Image(rel.target, cx, cy);
                img.crop = s.crop;
                img.effect = s.effect;
                img.alpha = s.alpha;
                dr = img;
            } else {
                int fill = s.open ? 0 : s.fill != null ? s.fill : s.styleFill != null ? s.styleFill : 0;
                int line = s.line != null ? s.line : s.styleLine != null ? s.styleLine : 0;
                boolean text = s.box != null && hasContent(s.box);
                if (fill == 0 && line == 0 && !text) continue;
                if (text && single && !floating && fill == 0 && line == 0) {
                    // An inline text box without a frame: its text follows the paragraph.
                    ctx.deferred.addAll(s.box);
                    continue;
                }
                TextBox tb = new TextBox(text ? s.box : new ArrayList<Block>(), cx, cy);
                tb.geometry = s.geometry;
                tb.path = s.path;
                tb.fill = fill;
                tb.border = line;
                if (line != 0 && s.lineWidth > 0) tb.borderWidthEmu = s.lineWidth;
                tb.flipH = s.flipH;
                tb.flipV = s.flipV;
                tb.rotation = s.rotation;
                tb.arrowHead = s.arrowHead;
                tb.arrowTail = s.arrowTail;
                tb.dash = s.dash;
                tb.clipText = !s.autoFit;
                tb.anchor = s.anchor;
                if (s.insets != null) tb.insets = s.insets;
                dr = tb;
            }
            position(dr, g);
            dr.clearsText = g.block && g.floating;
            if (!single) {
                dr.frameW = g.cx;
                dr.frameH = g.cy;
                dr.offX = s.x;
                dr.offY = s.y;
            }
            ctx.floats.add(dr);
        }
    }

    private static boolean hasContent(List<Block> blocks) {
        for (Block b : blocks) {
            if (!(b instanceof Paragraph) || !((Paragraph) b).isEmpty()) return true;
        }
        return false;
    }

    /** A Word shape (wps:wsp): outline, colours, style, text. */
    private ShapeSpec parseShape(XmlPullParser p) throws XmlPullParserException, IOException {
        ShapeSpec s = new ShapeSpec();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "spPr":
                    parseShapeProps(p, s);
                    break;
                case "style": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String n = p.getName();
                        boolean ln = "lnRef".equals(n), fl = "fillRef".equals(n);
                        int idx = XmlUtil.intAttr(p, "idx", 0);
                        int c = 0;
                        int d3 = p.getDepth();
                        while (XmlUtil.nextChild(p, d3)) c = DrawingML.readColor(p, scheme);
                        // Index 0 means no line / no fill from the theme.
                        if (ln && idx > 0) s.styleLine = c;
                        if (fl && idx > 0) s.styleFill = c;
                    }
                    break;
                }
                case "txbx": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("txbxContent".equals(p.getName())) {
                            List<Field> saved = fields;
                            fields = new ArrayList<>();
                            if (s.box == null) s.box = new ArrayList<>();
                            parseBlocks(p, s.box);
                            fields = saved;
                        } else {
                            XmlUtil.skip(p);
                        }
                    }
                    break;
                }
                case "bodyPr": {
                    String[] names = {"lIns", "tIns", "rIns", "bIns"};
                    long[] ins = {91440, 45720, 91440, 45720};
                    boolean any = false;
                    for (int i = 0; i < 4; i++) {
                        String v = XmlUtil.attr(p, names[i]);
                        if (v != null) {
                            ins[i] = Math.max(0, XmlUtil.parseInt(v, 0));
                            any = true;
                        }
                    }
                    if (any) s.insets = ins;
                    String a = XmlUtil.attr(p, "anchor");
                    s.anchor = "ctr".equals(a) ? DocModel.CELL_CENTER : "b".equals(a) ? DocModel.CELL_BOTTOM : DocModel.CELL_TOP;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("spAutoFit".equals(p.getName())) s.autoFit = true;
                        XmlUtil.skip(p);
                    }
                    break;
                }
                default:
                    XmlUtil.skip(p);
            }
        }
        return s;
    }

    /** spPr of a shape or picture: transform, geometry, fill and line. */
    private void parseShapeProps(XmlPullParser p, ShapeSpec s) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "xfrm":
                    long[] x = readXfrm(p, s);
                    s.x = x[0];
                    s.y = x[1];
                    s.cx = x[2];
                    s.cy = x[3];
                    break;
                case "prstGeom": {
                    String prst = XmlUtil.attr(p, "prst");
                    s.geometry = DrawingML.geometry(prst);
                    if (s.geometry == DocModel.GEOM_PATH) s.path = DrawingML.presetPath(prst);
                    s.open = prst != null && (prst.endsWith("Brace") || prst.endsWith("Bracket"));
                    XmlUtil.skip(p);
                    break;
                }
                case "custGeom": {
                    float[] path = DrawingML.readCustGeom(p);
                    if (path != null) {
                        s.geometry = DocModel.GEOM_PATH;
                        s.path = path;
                    }
                    break;
                }
                case "ln": {
                    long w = XmlUtil.parseInt(XmlUtil.attr(p, "w"), -1);
                    if (w >= 0) s.lineWidth = w;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String ln = p.getName();
                        if ("prstDash".equals(ln)) {
                            s.dash = dashStyle(XmlUtil.attr(p, "val"));
                            XmlUtil.skip(p);
                            continue;
                        }
                        if ("headEnd".equals(ln) || "tailEnd".equals(ln)) {
                            String type = XmlUtil.attr(p, "type");
                            boolean arrow = type != null && !"none".equals(type);
                            if ("headEnd".equals(ln)) s.arrowHead = arrow;
                            else s.arrowTail = arrow;
                            XmlUtil.skip(p);
                            continue;
                        }
                        Integer c = DrawingML.readFill(p, scheme);
                        if (c != null) s.line = c;
                    }
                    break;
                }
                case "blipFill": {
                    // A shape filled with a picture (Canva and similar exports): shown as the picture.
                    Graphic g = new Graphic();
                    scanGraphic(p, null, g);
                    if (g.embed != null) {
                        s.embed = g.embed;
                        s.crop = g.crop;
                        s.effect = g.effect;
                        s.alpha = g.alpha;
                    }
                    break;
                }
                default: {
                    Integer c = DrawingML.readFill(p, scheme);
                    if (c != null) s.fill = c;
                }
            }
        }
    }

    /** a:xfrm (or a group's): {x, y, cx, cy, chX, chY, chCx, chCy}; sets rotation and flips. */
    private static long[] readXfrm(XmlPullParser p, ShapeSpec s) throws XmlPullParserException, IOException {
        long[] out = new long[8];
        if (s != null) {
            s.flipH = "1".equals(XmlUtil.attr(p, "flipH"));
            s.flipV = "1".equals(XmlUtil.attr(p, "flipV"));
            s.rotation = XmlUtil.parseInt(XmlUtil.attr(p, "rot"), 0) / 60000f;
        }
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("off".equals(n) || "chOff".equals(n)) {
                int i = "off".equals(n) ? 0 : 4;
                out[i] = parseSigned(XmlUtil.attr(p, "x"));
                out[i + 1] = parseSigned(XmlUtil.attr(p, "y"));
            } else if ("ext".equals(n) || "chExt".equals(n)) {
                int i = "ext".equals(n) ? 2 : 6;
                out[i] = parseLong(XmlUtil.attr(p, "cx"));
                out[i + 1] = parseLong(XmlUtil.attr(p, "cy"));
            }
            XmlUtil.skip(p);
        }
        return out;
    }

    /** A picture inside a group (pic:pic). */
    private ShapeSpec parseGroupPicture(XmlPullParser p) throws XmlPullParserException, IOException {
        ShapeSpec s = new ShapeSpec();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            if ("spPr".equals(n)) {
                parseShapeProps(p, s);
                s.fill = null;
                s.line = null;
            } else if ("blipFill".equals(n)) {
                Graphic g = new Graphic();
                scanGraphic(p, null, g);
                s.embed = g.embed;
                s.crop = g.crop;
                s.effect = g.effect;
                s.alpha = g.alpha;
            } else {
                XmlUtil.skip(p);
            }
        }
        return s;
    }

    /**
     * A group (wpg:wgp or grpSp): its parts, moved from the group's child coordinates into the
     * drawing's frame by {@code t} = {offsetX, offsetY, scaleX, scaleY}.
     */
    private void parseGroup(XmlPullParser p, List<ShapeSpec> out, double[] t, boolean root)
            throws XmlPullParserException, IOException {
        double[] mine = t;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String n = p.getName();
            switch (n) {
                case "grpSpPr": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if (!"xfrm".equals(p.getName())) {
                            XmlUtil.skip(p);
                            continue;
                        }
                        long[] x = readXfrm(p, null);
                        double chW = x[6] > 0 ? x[6] : x[2], chH = x[7] > 0 ? x[7] : x[3];
                        // The group's own box, in the parent's frame (the root group is the frame).
                        double gx = root ? 0 : t[0] + x[0] * t[2], gy = root ? 0 : t[1] + x[1] * t[3];
                        double gw = root ? x[2] : x[2] * t[2], gh = root ? x[3] : x[3] * t[3];
                        double sx = chW > 0 ? gw / chW : 1, sy = chH > 0 ? gh / chH : 1;
                        mine = new double[]{gx - x[4] * sx, gy - x[5] * sy, sx, sy};
                    }
                    break;
                }
                case "wsp":
                    out.add(place(parseShape(p), mine));
                    break;
                case "pic":
                    out.add(place(parseGroupPicture(p), mine));
                    break;
                case "grpSp":
                    parseGroup(p, out, mine, false);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
    }

    // ------------------------------------------------------------------- VML

    private static final String VML = "urn:schemas-microsoft-com:vml";

    /**
     * A VML drawing (older documents, and the shapes of w:pict): a box, oval, line, text box,
     * picture or group. Absolutely positioned ones float like DrawingML anchors; others are inline
     * pictures (equations and other objects) or inline text boxes.
     */
    private void parseVml(XmlPullParser p, Graphic g) throws XmlPullParserException, IOException {
        Map<String, String> st = vmlStyle(XmlUtil.attr(p, "style"));
        boolean absolute = "absolute".equals(st.get("position"));
        long w = vmlLength(st.get("width"), 12700), h = vmlLength(st.get("height"), 12700);
        if ("group".equals(p.getName())) {
            g.group = true;
            vmlPosition(g, st, w, h, absolute);
            long[] origin = vmlPair(XmlUtil.attr(p, "coordorigin"), 0, 0);
            long[] size = vmlPair(XmlUtil.attr(p, "coordsize"), 1000, 1000);
            double sx = size[0] > 0 ? (double) w / size[0] : 1, sy = size[1] > 0 ? (double) h / size[1] : 1;
            int d = p.getDepth();
            while (XmlUtil.nextChild(p, d)) {
                String n = p.getName();
                if (!VML.equals(p.getNamespace()) || "shapetype".equals(n) || "group".equals(n)) {
                    XmlUtil.skip(p);
                    continue;
                }
                Map<String, String> cs = vmlStyle(XmlUtil.attr(p, "style"));
                ShapeSpec s = vmlShape(p, cs, 1);
                // Child positions are in the group's coordinate units.
                s.x = Math.round((vmlNumber(cs.get("left")) - origin[0]) * sx);
                s.y = Math.round((vmlNumber(cs.get("top")) - origin[1]) * sy);
                s.cx = Math.round(vmlNumber(cs.get("width")) * sx);
                s.cy = Math.round(vmlNumber(cs.get("height")) * sy);
                if (s.geometry == DocModel.GEOM_LINE && s.lineFrom != null) vmlLine(s, s.lineFrom, sx, sy, origin);
                g.shapes.add(s);
            }
            return;
        }
        ShapeSpec s = vmlShape(p, st, 12700);
        if (!absolute) {
            // Inline: a picture (an equation, a pasted object) or a text box whose text follows.
            if (s.embed != null) {
                if (g.embed == null) g.embed = s.embed;
                g.cx = w;
                g.cy = h;
                g.crop = s.crop;
            } else if (s.box != null) {
                if (g.box == null) g.box = new ArrayList<>();
                g.box.addAll(s.box);
            }
            return;
        }
        if (s.geometry == DocModel.GEOM_LINE && s.lineFrom != null) {
            vmlLine(s, s.lineFrom, 1, 1, new long[]{0, 0});
            vmlPosition(g, st, s.cx, s.cy, true);
            g.hOffset += s.x;
            g.vOffset += s.y;
            s.x = 0;
            s.y = 0;
        } else {
            s.cx = w;
            s.cy = h;
            // A line turned upright (connectors rotated 90°): its box is upright too.
            if (s.geometry == DocModel.GEOM_LINE && Math.abs(Math.abs(s.rotation) % 180 - 90) < 1) {
                long mid = (w - h) / 2;
                s.cx = h;
                s.cy = w;
                st.put("margin-left", (vmlLength(st.get("margin-left"), 12700) + mid) / 12700.0 + "pt");
                st.put("margin-top", (vmlLength(st.get("margin-top"), 12700) - mid) / 12700.0 + "pt");
                s.rotation = 0;
            }
            vmlPosition(g, st, s.cx, s.cy, true);
        }
        g.shapes.add(s);
    }

    /** A line's from/to points (pt, or group units scaled by sx, sy) as its box and direction. */
    private static void vmlLine(ShapeSpec s, long[][] ft, double sx, double sy, long[] origin) {
        double x1 = (ft[0][0] - origin[0]) * sx, y1 = (ft[0][1] - origin[1]) * sy;
        double x2 = (ft[1][0] - origin[0]) * sx, y2 = (ft[1][1] - origin[1]) * sy;
        s.x = Math.round(Math.min(x1, x2));
        s.y = Math.round(Math.min(y1, y2));
        s.cx = Math.round(Math.abs(x2 - x1));
        s.cy = Math.round(Math.abs(y2 - y1));
        s.flipH = x2 < x1;
        s.flipV = y2 < y1;
        if (s.flipH && s.flipV) s.flipH = s.flipV = false;
    }

    /** Sets a floating drawing's anchor from a VML style. */
    private static void vmlPosition(Graphic g, Map<String, String> st, long w, long h, boolean absolute) {
        g.cx = w;
        g.cy = h;
        if (!absolute) return;
        g.floating = true;
        g.hOffset = vmlLength(st.get("margin-left"), 12700) + vmlLength(st.get("left"), 12700);
        g.vOffset = vmlLength(st.get("margin-top"), 12700) + vmlLength(st.get("top"), 12700);
        String hr = st.get("mso-position-horizontal-relative"), vr = st.get("mso-position-vertical-relative");
        g.hRel = "page".equals(hr) || "left-margin-area".equals(hr) ? DocModel.REL_PAGE
                : "right-margin-area".equals(hr) ? DocModel.REL_FAR_MARGIN
                : "char".equals(hr) ? DocModel.REL_PARAGRAPH : DocModel.REL_MARGIN;
        g.vRel = "page".equals(vr) || "top-margin-area".equals(vr) ? DocModel.REL_PAGE
                : "bottom-margin-area".equals(vr) ? DocModel.REL_FAR_MARGIN
                : "margin".equals(vr) ? DocModel.REL_MARGIN : DocModel.REL_PARAGRAPH;
        String ha = st.get("mso-position-horizontal"), va = st.get("mso-position-vertical");
        g.hAlign = vmlAlign(ha);
        g.vAlign = vmlAlign(va);
        String wrap = st.get("mso-wrap-style");
        if ("square".equals(wrap)) g.wrapsText = true;
    }

    private static int vmlAlign(String v) {
        if ("center".equals(v)) return DocModel.ALIGN_CENTER;
        if ("right".equals(v) || "bottom".equals(v) || "outside".equals(v)) return DocModel.ALIGN_END;
        if ("left".equals(v) || "top".equals(v) || "inside".equals(v)) return DocModel.ALIGN_START;
        return -1;
    }

    /** One VML shape element: geometry from its kind or shape type, colours, text, picture. */
    private ShapeSpec vmlShape(XmlPullParser p, Map<String, String> st, double unit)
            throws XmlPullParserException, IOException {
        ShapeSpec s = new ShapeSpec();
        String n = p.getName();
        String type = XmlUtil.attr(p, "type");
        String spt = XmlUtil.attr(p, "spt");
        int t = spt != null ? XmlUtil.parseInt(spt, 0)
                : type != null && type.startsWith("#_x0000_t") ? XmlUtil.parseInt(type.substring(9), 0) : 0;
        if ("oval".equals(n) || t == 3 || t == 120) s.geometry = DocModel.GEOM_ELLIPSE;
        else if ("roundrect".equals(n) || t == 2 || t == 116 || t == 176) s.geometry = DocModel.GEOM_ROUND_RECT;
        else if ("line".equals(n) || t == 20 || t == 32 || t == 33 || t == 34) s.geometry = DocModel.GEOM_LINE;
        else if (t == 4 || t == 110) {
            s.geometry = DocModel.GEOM_PATH;
            s.path = DrawingML.presetPath("diamond");
        } else if (t == 5) {
            s.geometry = DocModel.GEOM_PATH;
            s.path = DrawingML.presetPath("triangle");
        } else if (t == 111) {
            s.geometry = DocModel.GEOM_PATH;
            s.path = DrawingML.presetPath("parallelogram");
        } else if (t == 85 || t == 86 || t == 87 || t == 88) {
            // Brackets and braces: open outlines, never filled.
            s.geometry = DocModel.GEOM_PATH;
            s.path = DrawingML.presetPath(t == 85 ? "leftBracket" : t == 86 ? "rightBracket"
                    : t == 87 ? "leftBrace" : "rightBrace");
            s.open = true;
        }
        if ("line".equals(n)) {
            long[] from = vmlPair(XmlUtil.attr(p, "from"), 0, 0, unit);
            long[] to = vmlPair(XmlUtil.attr(p, "to"), 0, 0, unit);
            s.lineFrom = new long[][]{from, to};
        }
        s.rotation = (float) vmlNumber(st.get("rotation"));
        String flip = st.get("flip");
        if (flip != null) {
            s.flipH = flip.contains("x");
            s.flipV = flip.contains("y");
        }
        // VML fills white and strokes black unless told otherwise; pictures and lines fill nothing.
        boolean picture = t == 75;
        boolean filled = !"f".equals(XmlUtil.attr(p, "filled")) && !"false".equals(XmlUtil.attr(p, "filled"));
        boolean stroked = !"f".equals(XmlUtil.attr(p, "stroked")) && !"false".equals(XmlUtil.attr(p, "stroked"));
        int fill = vmlColor(XmlUtil.attr(p, "fillcolor"), 0xFFFFFFFF);
        int line = vmlColor(XmlUtil.attr(p, "strokecolor"), 0xFF000000);
        long weight = vmlLength(XmlUtil.attr(p, "strokeweight"), 12700);
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            String c = p.getName();
            if ("fill".equals(c)) {
                if ("f".equals(XmlUtil.attr(p, "on")) || "false".equals(XmlUtil.attr(p, "on"))) filled = false;
                String col = XmlUtil.attr(p, "color");
                if (col != null) fill = vmlColor(col, fill);
                XmlUtil.skip(p);
            } else if ("stroke".equals(c)) {
                if ("f".equals(XmlUtil.attr(p, "on")) || "false".equals(XmlUtil.attr(p, "on"))) stroked = false;
                String start = XmlUtil.attr(p, "startarrow"), end = XmlUtil.attr(p, "endarrow");
                s.arrowHead = start != null && !"none".equals(start);
                s.arrowTail = end != null && !"none".equals(end);
                s.dash = dashStyle(XmlUtil.attr(p, "dashstyle"));
                String col = XmlUtil.attr(p, "color");
                if (col != null) line = vmlColor(col, line);
                String wt = XmlUtil.attr(p, "weight");
                if (wt != null) weight = vmlLength(wt, 12700);
                XmlUtil.skip(p);
            } else if ("imagedata".equals(c)) {
                String id = XmlUtil.attr(p, "id");
                if (id == null) id = XmlUtil.attr(p, "pict");
                if (s.embed == null) s.embed = id;
                picture = true;
                // Washout (a watermark picture): low gain and a raised black level.
                float gain = vmlFraction(XmlUtil.attr(p, "gain"), 1f);
                float black = vmlFraction(XmlUtil.attr(p, "blacklevel"), 0f);
                if (gain < 0.7f || black > 0.2f) s.alpha = 0.3f;
                XmlUtil.skip(p);
            } else if ("textbox".equals(c)) {
                String ts = XmlUtil.attr(p, "style");
                if (ts != null && ts.replace(" ", "").contains("mso-fit-shape-to-text:t")) s.autoFit = true;
                int d2 = p.getDepth();
                while (XmlUtil.nextChild(p, d2)) {
                    if ("txbxContent".equals(p.getName())) {
                        List<Field> saved = fields;
                        fields = new ArrayList<>();
                        if (s.box == null) s.box = new ArrayList<>();
                        parseBlocks(p, s.box);
                        fields = saved;
                    } else {
                        XmlUtil.skip(p);
                    }
                }
            } else {
                XmlUtil.skip(p);
            }
        }
        if (picture || s.geometry == DocModel.GEOM_LINE || s.open) filled = false;
        if (picture && s.embed != null) stroked = "t".equals(XmlUtil.attr(p, "stroked"));
        s.fill = filled ? fill : 0;
        s.line = stroked ? line : 0;
        if (weight > 0) s.lineWidth = weight;
        return s;
    }

    /** STROKE_* for a VML dashstyle or DrawingML prstDash value. */
    static int dashStyle(String v) {
        if (v == null || "solid".equals(v)) return DocModel.STROKE_SOLID;
        String s = v.toLowerCase(Locale.ROOT);
        if (s.contains("dashdot") || s.contains("dash-dot")) return DocModel.STROKE_DASH_DOT;
        if (s.contains("dash")) return DocModel.STROKE_DASH;
        if (s.contains("dot") || s.contains("1 1")) return DocModel.STROKE_DOT;
        return DocModel.STROKE_DASH;
    }

    /** A VML style attribute as name -> value. */
    private static Map<String, String> vmlStyle(String style) {
        Map<String, String> m = new HashMap<>();
        if (style == null) return m;
        for (String part : style.split(";")) {
            int c = part.indexOf(':');
            if (c <= 0) continue;
            m.put(part.substring(0, c).trim().toLowerCase(Locale.ROOT), part.substring(c + 1).trim());
        }
        return m;
    }

    /** A CSS length in EMU; a bare number counts {@code unit} EMU. */
    private static long vmlLength(String v, double unit) {
        if (v == null || v.isEmpty()) return 0;
        v = v.trim().toLowerCase(Locale.ROOT);
        double f = unit;
        String num = v;
        String[][] units = {{"pt", "12700"}, {"in", "914400"}, {"cm", "360000"}, {"mm", "36000"}, {"px", "9525"},
                {"pc", "152400"}, {"emu", "1"}};
        for (String[] u : units) {
            if (v.endsWith(u[0])) {
                f = Double.parseDouble(u[1]);
                num = v.substring(0, v.length() - u[0].length());
                break;
            }
        }
        try {
            return Math.round(Double.parseDouble(num) * f);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** A VML fraction: "0.3", or "19661f" in 65536ths. */
    private static float vmlFraction(String v, float fallback) {
        if (v == null || v.isEmpty()) return fallback;
        try {
            if (v.endsWith("f")) return Integer.parseInt(v.substring(0, v.length() - 1)) / 65536f;
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double vmlNumber(String v) {
        if (v == null || v.isEmpty()) return 0;
        try {
            return Double.parseDouble(v.replaceAll("[a-z]+$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long[] vmlPair(String v, long dx, long dy) {
        return vmlPair(v, dx, dy, 1);
    }

    /** "x,y" scaled by {@code unit} (bare numbers) or converted from units. */
    private static long[] vmlPair(String v, long dx, long dy, double unit) {
        if (v == null) return new long[]{dx, dy};
        String[] parts = v.split(",");
        long x = parts.length > 0 && !parts[0].trim().isEmpty() ? vmlLength(parts[0], unit) : dx;
        long y = parts.length > 1 && !parts[1].trim().isEmpty() ? vmlLength(parts[1], unit) : dy;
        return new long[]{x, y};
    }

    /** A VML colour: #rrggbb, #rgb or a name, optionally followed by " [index]". */
    private static int vmlColor(String v, int fallback) {
        if (v == null) return fallback;
        v = v.trim();
        int sp = v.indexOf(' ');
        if (sp > 0) v = v.substring(0, sp);
        if (v.startsWith("#")) {
            String hex = v.substring(1);
            if (hex.length() == 3) {
                hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
            }
            int c = XmlUtil.hexColor(hex);
            return c != 0 ? c : fallback;
        }
        switch (v.toLowerCase(Locale.ROOT)) {
            case "black":
            case "windowtext":
                return 0xFF000000;
            case "white":
            case "window":
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
            case "silver":
                return 0xFFC0C0C0;
            case "navy":
                return 0xFF000080;
            case "maroon":
                return 0xFF800000;
            default:
                return fallback;
        }
    }

    private static ShapeSpec place(ShapeSpec s, double[] t) {
        s.x = Math.round(t[0] + s.x * t[2]);
        s.y = Math.round(t[1] + s.y * t[3]);
        s.cx = Math.round(s.cx * t[2]);
        s.cy = Math.round(s.cy * t[3]);
        return s;
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
                case "wsp": {
                    ShapeSpec s = parseShape(p);
                    // A shape of its own fills the drawing's frame.
                    s.x = 0;
                    s.y = 0;
                    g.shapes.add(s);
                    break;
                }
                case "wgp":
                    g.group = true;
                    parseGroup(p, g.shapes, new double[]{0, 0, 1, 1}, true);
                    break;
                case "shapetype":
                    XmlUtil.skip(p);
                    break;
                case "rect":
                case "roundrect":
                case "oval":
                case "line":
                case "shape":
                case "group":
                    if (VML.equals(p.getNamespace())) {
                        parseVml(p, g);
                        break;
                    }
                    scanGraphic(p, ctx, g);
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
                case "blip": {
                    if (g.embed == null) g.embed = XmlUtil.attr(p, "embed");
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        String n = p.getName();
                        if ("grayscl".equals(n)) g.effect = Math.max(g.effect, DocModel.EFFECT_GRAY);
                        else if ("biLevel".equals(n)) g.effect = DocModel.EFFECT_BILEVEL;
                        else if ("alphaModFix".equals(n)) g.alpha = XmlUtil.intAttr(p, "amt", 100000) / 100000f;
                        // Washout (Word's watermark picture): brighter and flatter.
                        else if ("lum".equals(n) && XmlUtil.intAttr(p, "bright", 0) > 30000
                                && XmlUtil.intAttr(p, "contrast", 0) < -30000) g.alpha = Math.min(g.alpha, 0.35f);
                        XmlUtil.skip(p);
                    }
                    break;
                }
                case "srcRect": {
                    float[] c = new float[4];
                    String[] sides = {"l", "t", "r", "b"};
                    // Negative values add space around the picture; only cutting is shown.
                    for (int i = 0; i < 4; i++) c[i] = Math.max(0, XmlUtil.intAttr(p, sides[i], 0) / 100000f);
                    if (c[0] + c[2] < 0.98f && c[1] + c[3] < 0.98f && (c[0] + c[1] + c[2] + c[3]) > 0.001f) g.crop = c;
                    XmlUtil.skip(p);
                    break;
                }
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
        // The top and left margin areas start at the paper's edge.
        if ("page".equals(v) || "topMargin".equals(v) || "leftMargin".equals(v)) return DocModel.REL_PAGE;
        if ("bottomMargin".equals(v) || "rightMargin".equals(v)) return DocModel.REL_FAR_MARGIN;
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
        int savedLook = tableLook;
        tableStyle = null;
        tableLook = LOOK_FIRST_ROW | LOOK_FIRST_COL | LOOK_NO_VBAND;
        try {
            int d = p.getDepth();
            while (XmlUtil.nextChild(p, d)) parseTableChild(p, t);
            applyTableStyle(t, tableStyle, tableLook);
        } finally {
            tableStyle = saved;
            tableLook = savedLook;
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
                // tblpPr: {vertical reference, y, horizontal alignment or -1, x}, or none.
                int[] pos = {-1, 0, -1, 0};
                // Word's default look: header row, first column and banded rows.
                int[] look = {LOOK_FIRST_ROW | LOOK_FIRST_COL | LOOK_NO_VBAND};
                parseTablePr(p, direct, margins, new Object[]{style, props, pos, look});
                tableLook = look[0];
                if (pos[0] >= 0) {
                    t.floatRel = pos[0];
                    t.floatY = pos[1];
                    if (pos[2] >= 0) {
                        props[1] = pos[2];
                    } else if (pos[3] != 0 && props[1] == DocModel.ALIGN_START) {
                        props[0] = pos[3];
                    }
                }
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
            } else if (extra != null && extra.length > 3 && "tblLook".equals(n)) {
                int[] look = (int[]) extra[3];
                String v = XmlUtil.attr(p, "val");
                if (v != null) {
                    try {
                        look[0] = Integer.parseInt(v, 16);
                    } catch (NumberFormatException ignored) {
                    }
                }
                String[] names = {"firstRow", "lastRow", "firstColumn", "lastColumn", "noHBand", "noVBand"};
                int[] bits = {LOOK_FIRST_ROW, LOOK_LAST_ROW, LOOK_FIRST_COL, LOOK_LAST_COL, LOOK_NO_HBAND, LOOK_NO_VBAND};
                for (int i = 0; i < names.length; i++) {
                    String a = XmlUtil.attr(p, names[i]);
                    if (a == null) continue;
                    if ("1".equals(a) || "true".equals(a)) look[0] |= bits[i];
                    else look[0] &= ~bits[i];
                }
                XmlUtil.skip(p);
            } else if (extra != null && extra.length > 2 && "tblpPr".equals(n)) {
                int[] pos = (int[]) extra[2];
                String v = XmlUtil.attr(p, "vertAnchor");
                pos[0] = "page".equals(v) ? DocModel.REL_PAGE : "margin".equals(v) ? DocModel.REL_MARGIN
                        : DocModel.REL_PARAGRAPH;
                pos[1] = XmlUtil.intAttr(p, "tblpY", 0);
                String xs = XmlUtil.attr(p, "tblpXSpec");
                pos[2] = xs == null ? -1 : "center".equals(xs) ? DocModel.ALIGN_CENTER
                        : "right".equals(xs) || "outside".equals(xs) ? DocModel.ALIGN_END : DocModel.ALIGN_START;
                // Relative to the margin unless it says the page.
                pos[3] = XmlUtil.intAttr(p, "tblpX", 0);
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
        Border b = new Border(size, color == 0 ? 0xFF000000 : color);
        b.style = borderStyle(v);
        b.space = Math.max(0, Math.min(31, XmlUtil.intAttr(p, "space", 0)));
        return b;
    }

    static int borderStyle(String v) {
        if (v == null) return DocModel.STROKE_SOLID;
        if (v.equals("double") || v.equals("triple")) return DocModel.STROKE_DOUBLE;
        if (v.startsWith("thinThick")) return DocModel.STROKE_THIN_THICK;
        if (v.startsWith("thickThin")) return DocModel.STROKE_THICK_THIN;
        if (v.startsWith("dotDash") || v.startsWith("dashDot")) return DocModel.STROKE_DASH_DOT;
        if (v.startsWith("dash")) return DocModel.STROKE_DASH;
        if (v.equals("dotted")) return DocModel.STROKE_DOT;
        return DocModel.STROKE_SOLID;
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
                        // Nested tables stay tables (bit tables, forms); the reading mode flattens them.
                        c.blocks.add(parseTable(p));
                    } else if ("sdtPr".equals(n) || "Fallback".equals(n)) {
                        XmlUtil.skip(p);
                    } else {
                        parseBlocks(p, c.blocks);
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
                case "tcMar": {
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        int i = borderIndex(p.getName());
                        if (i >= 0 && i < 4) {
                            if (c.margins == null) c.margins = new int[]{-1, -1, -1, -1};
                            c.margins[i] = Math.max(0, XmlUtil.intAttr(p, "w", 0));
                        }
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

    private static void trimTrailingEmpty(List<Block> blocks) {
        while (blocks.size() > 1) {
            Block last = blocks.get(blocks.size() - 1);
            if (last instanceof Paragraph && ((Paragraph) last).isEmpty()) blocks.remove(blocks.size() - 1);
            else break;
        }
    }
}
