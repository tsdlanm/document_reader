package com.tsdlanm.documentreader.docx;

import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Cell;
import com.tsdlanm.documentreader.docx.DocModel.Document;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.Table;
import com.tsdlanm.documentreader.util.XmlFactory;
import com.tsdlanm.documentreader.util.XmlUtil;
import com.tsdlanm.documentreader.util.ZipParts;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Streams word/document.xml into a {@link Document}. Supports text formatting, headings, lists,
 * tables, images and text boxes; headers, footers, footnotes and comments are ignored.
 */
public final class DocxParser {

    /** Run properties where null means "not set here, inherit". Colour 0 means automatic / none. */
    private static final class RunProps {
        Boolean bold, italic, underline, strike, hidden;
        Integer halfPoints, color, background, vertAlign;

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
            if (o.halfPoints != null) halfPoints = o.halfPoints;
            if (o.color != null) color = o.color;
            if (o.background != null) background = o.background;
            if (o.vertAlign != null) vertAlign = o.vertAlign;
        }
    }

    private static final class Style {
        String name = "";
        String basedOn;
        RunProps run = new RunProps();
        int align = -1;
        int indent = -1;
        int outline = -1;
        String numId;
        int ilvl = -1;
    }

    private final ZipFile zip;
    private final XmlFactory xml;
    private final Map<String, Style> styles = new HashMap<>();
    private final Numbering numbering = new Numbering();
    private final RunProps docDefaults = new RunProps();
    private Map<String, ZipParts.Rel> rels = new HashMap<>();
    private int defaultHalfPoints = 22;
    private String defaultParagraphStyle;

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
        for (ZipParts.Rel r : rels.values()) {
            if (r.external) continue;
            if (r.type.endsWith("/styles")) stylesPart = r.target;
            else if (r.type.endsWith("/numbering")) numberingPart = r.target;
        }
        ZipParts.Part sp = ZipParts.open(zip, stylesPart, xml);
        if (sp != null) {
            try {
                parseStyles(sp.parser);
            } finally {
                sp.close();
            }
        }
        if (docDefaults.halfPoints != null && docDefaults.halfPoints > 0) {
            defaultHalfPoints = docDefaults.halfPoints;
        }
        RunProps normal = resolveRun(defaultParagraphStyle, 0);
        if (normal.halfPoints != null && normal.halfPoints > 0) defaultHalfPoints = normal.halfPoints;
        ZipParts.Part np = ZipParts.open(zip, numberingPart, xml);
        if (np != null) {
            try {
                numbering.parse(np.parser);
            } finally {
                np.close();
            }
        }

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
        return new Document(blocks, defaultHalfPoints);
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
                    if ("rPrDefault".equals(p.getName())) {
                        int d2 = p.getDepth();
                        while (XmlUtil.nextChild(p, d2)) {
                            if ("rPr".equals(p.getName())) parseRunProps(p, docDefaults);
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
                            parseStylePPr(p, s);
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

    private void parseStylePPr(XmlPullParser p, Style s) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "jc":
                    s.align = align(XmlUtil.attr(p, "val"));
                    XmlUtil.skip(p);
                    break;
                case "ind":
                    s.indent = indentOf(p);
                    XmlUtil.skip(p);
                    break;
                case "outlineLvl":
                    s.outline = XmlUtil.intAttr(p, "val", -1);
                    XmlUtil.skip(p);
                    break;
                case "numPr":
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("numId".equals(p.getName())) s.numId = XmlUtil.attr(p, "val");
                        else if ("ilvl".equals(p.getName())) s.ilvl = XmlUtil.intAttr(p, "val", 0);
                        XmlUtil.skip(p);
                    }
                    break;
                default:
                    XmlUtil.skip(p);
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

    private Style findStyle(String styleId, int depth, StyleField field) {
        Style s = styleId == null ? null : styles.get(styleId);
        if (s == null || depth > 10) return null;
        if (field.isSet(s)) return s;
        return findStyle(s.basedOn, depth + 1, field);
    }

    private interface StyleField {
        boolean isSet(Style s);
    }

    private int headingLevel(String styleId) {
        Style s = styleId == null ? null : styles.get(styleId);
        String name = s != null ? s.name : (styleId == null ? "" : styleId.toLowerCase(Locale.ROOT));
        if ("title".equals(name)) return 1;
        if (name.startsWith("heading")) {
            String rest = name.substring(7).trim();
            int lvl = XmlUtil.parseInt(rest, 0);
            if (lvl >= 1 && lvl <= 9) return Math.min(lvl, 6);
        }
        Style o = findStyle(styleId, 0, st -> st.outline >= 0);
        if (o != null && o.outline < 9) return Math.min(o.outline + 1, 6);
        return 0;
    }

    // ------------------------------------------------------------- run props

    private void parseRunProps(XmlPullParser p, RunProps r) throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) parseSingleRunProp(p, r);
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

    private static int align(String v) {
        if (v == null) return -1;
        switch (v) {
            case "center": return DocModel.ALIGN_CENTER;
            case "right":
            case "end": return DocModel.ALIGN_END;
            case "both":
            case "distribute": return DocModel.ALIGN_JUSTIFY;
            default: return DocModel.ALIGN_START;
        }
    }

    private static int indentOf(XmlPullParser p) {
        String v = XmlUtil.attr(p, "left");
        if (v == null) v = XmlUtil.attr(p, "start");
        return Math.max(0, XmlUtil.parseInt(v, 0));
    }

    private static Run toRun(RunProps r) {
        Run run = new Run();
        run.bold = Boolean.TRUE.equals(r.bold);
        run.italic = Boolean.TRUE.equals(r.italic);
        run.underline = Boolean.TRUE.equals(r.underline);
        run.strike = Boolean.TRUE.equals(r.strike);
        run.halfPoints = r.halfPoints == null ? 0 : Math.max(0, r.halfPoints);
        run.color = r.color == null ? 0 : r.color;
        run.background = r.background == null ? 0 : r.background;
        run.vertAlign = r.vertAlign == null ? 0 : r.vertAlign;
        return run;
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

    /** State for the paragraph being built; images split it into several blocks. */
    private final class ParaCtx {
        final List<Block> out;
        final List<Block> deferred = new ArrayList<>();
        Paragraph para = new Paragraph();
        RunProps base = docDefaults.copy();

        ParaCtx(List<Block> out) {
            this.out = out;
        }

        void addRun(RunProps props, CharSequence text) {
            if (text.length() == 0 || Boolean.TRUE.equals(props.hidden)) return;
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

        void addImage(Image img) {
            if (!para.isEmpty()) out.add(para);
            Paragraph next = para.copyProps();
            out.add(img);
            para = next;
        }

        void finish() {
            // Keep empty paragraphs: authors use them as vertical spacing.
            out.add(para);
            out.addAll(deferred);
        }
    }

    private void parseParagraph(XmlPullParser p, List<Block> out) throws XmlPullParserException, IOException {
        ParaCtx ctx = new ParaCtx(out);
        String styleId = defaultParagraphStyle;
        applyStyle(ctx, styleId, null, -1, -1, -1);
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if ("pPr".equals(p.getName())) {
                parseParaProps(p, ctx);
            } else {
                parseInline(p, ctx);
            }
        }
        ctx.finish();
    }

    private void parseParaProps(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        String styleId = defaultParagraphStyle;
        String numId = null;
        int ilvl = -1;
        int alignV = -1;
        int indent = -1;
        boolean hasNum = false;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "pStyle":
                    styleId = XmlUtil.attr(p, "val");
                    XmlUtil.skip(p);
                    break;
                case "numPr":
                    hasNum = true;
                    int d2 = p.getDepth();
                    while (XmlUtil.nextChild(p, d2)) {
                        if ("numId".equals(p.getName())) numId = XmlUtil.attr(p, "val");
                        else if ("ilvl".equals(p.getName())) ilvl = XmlUtil.intAttr(p, "val", 0);
                        XmlUtil.skip(p);
                    }
                    break;
                case "jc":
                    alignV = align(XmlUtil.attr(p, "val"));
                    XmlUtil.skip(p);
                    break;
                case "ind":
                    indent = indentOf(p);
                    XmlUtil.skip(p);
                    break;
                case "rPr":
                    // Paragraph mark formatting; does not apply to the text.
                    XmlUtil.skip(p);
                    break;
                default:
                    XmlUtil.skip(p);
            }
        }
        applyStyle(ctx, styleId, hasNum ? (numId == null ? "" : numId) : null, ilvl, alignV, indent);
    }

    private void applyStyle(ParaCtx ctx, String styleId, String numId, int ilvl, int alignV, int indent) {
        Paragraph para = ctx.para;
        RunProps base = docDefaults.copy();
        base.apply(resolveRun(styleId, 0));
        ctx.base = base;
        para.heading = headingLevel(styleId);

        Style a = findStyle(styleId, 0, s -> s.align >= 0);
        para.align = alignV >= 0 ? alignV : (a != null ? a.align : DocModel.ALIGN_START);
        Style in = findStyle(styleId, 0, s -> s.indent >= 0);
        para.indent = indent >= 0 ? indent : (in != null ? in.indent : 0);

        if (numId == null) {
            Style ns = findStyle(styleId, 0, s -> s.numId != null);
            if (ns != null) {
                numId = ns.numId;
                if (ilvl < 0) ilvl = ns.ilvl;
            }
        }
        para.listPrefix = null;
        if (numId != null && !numId.isEmpty()) {
            int level = Math.max(0, ilvl);
            para.listPrefix = numbering.next(numId, level);
            if (para.listPrefix != null && para.indent == 0) para.indent = 360 * (level + 1);
        }
    }

    /** Handles run-level content inside a paragraph (runs, hyperlinks, fields, ...). */
    private void parseInline(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "r":
                parseRun(p, ctx);
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
            case "br":
            case "cr":
                ctx.addRun(props, "\n");
                XmlUtil.skip(p);
                return;
            case "noBreakHyphen":
                ctx.addRun(props, "‑");
                XmlUtil.skip(p);
                return;
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
                // instrText, delText, fldChar, footnoteReference, Fallback, ...
                XmlUtil.skip(p);
        }
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
            case "u":
                r.underline = XmlUtil.toggle(p);
                break;
            case "strike":
            case "dstrike":
                r.strike = XmlUtil.toggle(p);
                break;
            case "vanish":
                r.hidden = XmlUtil.toggle(p);
                break;
            case "sz": {
                int v = XmlUtil.intAttr(p, "val", 0);
                if (v > 0) r.halfPoints = v;
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

    /** Scans a drawing for an embedded picture and text-box content. */
    private void parseGraphic(XmlPullParser p, ParaCtx ctx) throws XmlPullParserException, IOException {
        String[] embed = new String[1];
        long[] size = new long[2];
        scanGraphic(p, ctx, embed, size);
        if (embed[0] == null) return;
        ZipParts.Rel rel = rels.get(embed[0]);
        if (rel == null || rel.external || ZipParts.entry(zip, rel.target) == null) return;
        ctx.addImage(new Image(rel.target, size[0], size[1]));
    }

    private void scanGraphic(XmlPullParser p, ParaCtx ctx, String[] embed, long[] size)
            throws XmlPullParserException, IOException {
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "Fallback":
                    XmlUtil.skip(p);
                    break;
                case "txbxContent":
                    parseBlocks(p, ctx.deferred);
                    break;
                case "extent":
                    if (size[0] == 0) {
                        size[0] = parseLong(XmlUtil.attr(p, "cx"));
                        size[1] = parseLong(XmlUtil.attr(p, "cy"));
                    }
                    XmlUtil.skip(p);
                    break;
                case "blip":
                    if (embed[0] == null) embed[0] = XmlUtil.attr(p, "embed");
                    XmlUtil.skip(p);
                    break;
                case "imagedata":
                    if (embed[0] == null) embed[0] = XmlUtil.attr(p, "id");
                    XmlUtil.skip(p);
                    break;
                default:
                    scanGraphic(p, ctx, embed, size);
            }
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
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) parseTableChild(p, t);
        return t;
    }

    private void parseTableChild(XmlPullParser p, Table t) throws XmlPullParserException, IOException {
        switch (p.getName()) {
            case "tblGrid": {
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) {
                    if ("gridCol".equals(p.getName())) t.grid.add(Math.max(0, XmlUtil.intAttr(p, "w", 0)));
                    XmlUtil.skip(p);
                }
                break;
            }
            case "tr": {
                List<Cell> row = new ArrayList<>();
                int d = p.getDepth();
                while (XmlUtil.nextChild(p, d)) parseRowChild(p, row);
                t.rows.add(row);
                break;
            }
            case "tblPr":
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

    private void parseRowChild(XmlPullParser p, List<Cell> row) throws XmlPullParserException, IOException {
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
                trimTrailingEmpty(c.blocks);
                row.add(c);
                break;
            }
            case "trPr":
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
                case "vMerge":
                    String v = XmlUtil.attr(p, "val");
                    c.mergedContinue = v == null || "continue".equals(v);
                    break;
                case "shd":
                    int col = XmlUtil.hexColor(XmlUtil.attr(p, "fill"));
                    if (col != 0xFFFFFFFF) c.background = col;
                    break;
                default:
                    break;
            }
            XmlUtil.skip(p);
        }
    }

    /** Nested tables are shown as plain paragraphs inside the cell. */
    private static void flattenTable(Table t, List<Block> out) {
        for (List<Cell> row : t.rows) {
            for (Cell c : row) {
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
