package com.tsdlanm.documentreader.xlsx;

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

/** Reads the sheet list of an .xlsx file and loads sheets on demand. */
public final class XlsxWorkbook {
    public static final class Sheet {
        public final String name;
        final String part;

        Sheet(String name, String part) {
            this.name = name;
            this.part = part;
        }
    }

    private static final int MAX_ROWS = 1048576;
    private static final int MAX_COLS = 16384;

    public final List<Sheet> sheets = new ArrayList<>();
    private final ZipFile zip;
    private final XmlFactory xml;
    private boolean date1904;
    private String sharedStringsPart, stylesPart, themePart;
    private String[] sharedStrings;
    private CellStyle[] styles;

    private XlsxWorkbook(ZipFile zip, XmlFactory xml) {
        this.zip = zip;
        this.xml = xml;
    }

    public static boolean isXlsx(ZipFile zip) {
        return zip.getEntry("xl/workbook.xml") != null;
    }

    public static XlsxWorkbook open(ZipFile zip, XmlFactory xml) throws IOException, XmlPullParserException {
        XlsxWorkbook wb = new XlsxWorkbook(zip, xml);
        wb.readWorkbook();
        return wb;
    }

    private void readWorkbook() throws IOException, XmlPullParserException {
        String main = ZipParts.mainPart(zip, xml, "xl/workbook.xml");
        Map<String, ZipParts.Rel> rels = ZipParts.rels(zip, main, xml);
        for (ZipParts.Rel r : rels.values()) {
            if (r.external) continue;
            if (r.type.endsWith("/sharedStrings")) sharedStringsPart = r.target;
            else if (r.type.endsWith("/styles")) stylesPart = r.target;
            else if (r.type.endsWith("/theme")) themePart = r.target;
        }
        if (sharedStringsPart == null) sharedStringsPart = "xl/sharedStrings.xml";
        if (stylesPart == null) stylesPart = "xl/styles.xml";
        if (themePart == null) themePart = "xl/theme/theme1.xml";

        ZipParts.Part part = ZipParts.open(zip, main, xml);
        if (part == null) throw new IOException("No workbook part");
        try {
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                if ("workbookPr".equals(n)) {
                    String v = XmlUtil.attr(p, "date1904");
                    date1904 = "1".equals(v) || "true".equals(v);
                } else if ("sheet".equals(n)) {
                    String state = XmlUtil.attr(p, "state");
                    if ("hidden".equals(state) || "veryHidden".equals(state)) continue;
                    String id = XmlUtil.attr(p, "id");
                    ZipParts.Rel rel = id == null ? null : rels.get(id);
                    // Chart sheets and dialog sheets have no cells to show.
                    if (rel == null || rel.external || !rel.type.endsWith("/worksheet")) continue;
                    if (ZipParts.entry(zip, rel.target) == null) continue;
                    String name = XmlUtil.attr(p, "name");
                    sheets.add(new Sheet(name == null ? "Sheet" + (sheets.size() + 1) : name, rel.target));
                }
            }
        } finally {
            part.close();
        }
    }

    /**
     * Loads one sheet. {@code maxCells} caps memory use; cells beyond it are dropped and the result
     * is marked truncated.
     */
    public synchronized SheetData loadSheet(int index, long maxCells) throws IOException, XmlPullParserException {
        if (sharedStrings == null) sharedStrings = readSharedStrings();
        if (styles == null) styles = readStyles();
        Sheet sheet = sheets.get(index);
        ZipParts.Part part = ZipParts.open(zip, sheet.part, xml);
        if (part == null) throw new IOException("Missing sheet");
        try {
            return readSheet(part.parser, maxCells);
        } finally {
            part.close();
        }
    }

    // --------------------------------------------------------- shared strings

    private String[] readSharedStrings() throws IOException, XmlPullParserException {
        ZipParts.Part part = ZipParts.open(zip, sharedStringsPart, xml);
        if (part == null) return new String[0];
        List<String> out = new ArrayList<>();
        try {
            XmlPullParser p = part.parser;
            StringBuilder sb = new StringBuilder();
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) {
                    String n = p.getName();
                    if ("si".equals(n)) sb.setLength(0);
                    else if ("rPh".equals(n) || "phoneticPr".equals(n)) XmlUtil.skip(p);
                    else if ("t".equals(n)) sb.append(XmlUtil.text(p));
                } else if (e == XmlPullParser.END_TAG && "si".equals(p.getName())) {
                    out.add(sb.toString());
                }
            }
        } finally {
            part.close();
        }
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------ styles

    private static final class Font {
        boolean bold, italic, underline;
        int color;
        float size;
    }

    private CellStyle[] readStyles() throws IOException, XmlPullParserException {
        int[] theme = readTheme();
        ZipParts.Part part = ZipParts.open(zip, stylesPart, xml);
        if (part == null) return new CellStyle[0];
        Map<Integer, NumberFormat> formats = new HashMap<>();
        List<Font> fonts = new ArrayList<>();
        List<Integer> fills = new ArrayList<>();
        List<CellStyle> xfs = new ArrayList<>();
        try {
            XmlPullParser p = part.parser;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG || p.getDepth() != 2) continue;
                int d = p.getDepth();
                switch (p.getName()) {
                    case "numFmts":
                        while (XmlUtil.nextChild(p, d)) {
                            if ("numFmt".equals(p.getName())) {
                                int id = XmlUtil.intAttr(p, "numFmtId", -1);
                                formats.put(id, NumberFormat.parse(XmlUtil.attr(p, "formatCode")));
                            }
                            XmlUtil.skip(p);
                        }
                        break;
                    case "fonts":
                        while (XmlUtil.nextChild(p, d)) {
                            if ("font".equals(p.getName())) fonts.add(readFont(p, theme));
                            else XmlUtil.skip(p);
                        }
                        break;
                    case "fills":
                        while (XmlUtil.nextChild(p, d)) {
                            if ("fill".equals(p.getName())) fills.add(readFill(p, theme));
                            else XmlUtil.skip(p);
                        }
                        break;
                    case "cellXfs":
                        while (XmlUtil.nextChild(p, d)) {
                            if (!"xf".equals(p.getName())) {
                                XmlUtil.skip(p);
                                continue;
                            }
                            CellStyle cs = new CellStyle();
                            int fmtId = XmlUtil.intAttr(p, "numFmtId", 0);
                            NumberFormat nf = formats.get(fmtId);
                            cs.format = nf != null ? nf : NumberFormat.builtin(fmtId);
                            int fontId = XmlUtil.intAttr(p, "fontId", 0);
                            if (fontId >= 0 && fontId < fonts.size()) {
                                Font f = fonts.get(fontId);
                                cs.bold = f.bold;
                                cs.italic = f.italic;
                                cs.underline = f.underline;
                                cs.textColor = f.color;
                                cs.fontSize = f.size;
                            }
                            int fillId = XmlUtil.intAttr(p, "fillId", 0);
                            if (fillId >= 0 && fillId < fills.size()) cs.fillColor = fills.get(fillId);
                            int d2 = p.getDepth();
                            while (XmlUtil.nextChild(p, d2)) {
                                if ("alignment".equals(p.getName())) {
                                    String h = XmlUtil.attr(p, "horizontal");
                                    if ("left".equals(h)) cs.align = CellStyle.ALIGN_LEFT;
                                    else if ("center".equals(h) || "centerContinuous".equals(h)) {
                                        cs.align = CellStyle.ALIGN_CENTER;
                                    } else if ("right".equals(h)) cs.align = CellStyle.ALIGN_RIGHT;
                                    String w = XmlUtil.attr(p, "wrapText");
                                    cs.wrap = "1".equals(w) || "true".equals(w);
                                }
                                XmlUtil.skip(p);
                            }
                            xfs.add(cs);
                        }
                        break;
                    default:
                        XmlUtil.skip(p);
                }
            }
        } finally {
            part.close();
        }
        // Sizes are drawn relative to the workbook's default font.
        float base = fonts.isEmpty() || fonts.get(0).size <= 0 ? 11f : fonts.get(0).size;
        for (CellStyle cs : xfs) {
            cs.fontSize = cs.fontSize > 0 ? cs.fontSize / base : 1f;
            if (Math.abs(cs.fontSize - 1f) < 0.01f) cs.fontSize = 1f;
        }
        return xfs.toArray(new CellStyle[0]);
    }

    private static Font readFont(XmlPullParser p, int[] theme) throws XmlPullParserException, IOException {
        Font f = new Font();
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            switch (p.getName()) {
                case "b":
                    f.bold = XmlUtil.toggle(p);
                    break;
                case "i":
                    f.italic = XmlUtil.toggle(p);
                    break;
                case "u":
                    f.underline = XmlUtil.toggle(p);
                    break;
                case "sz":
                    f.size = XmlUtil.floatAttr(p, "val", 0);
                    break;
                case "color":
                    f.color = color(p, theme, true);
                    break;
                default:
                    break;
            }
            XmlUtil.skip(p);
        }
        return f;
    }

    private static int readFill(XmlPullParser p, int[] theme) throws XmlPullParserException, IOException {
        int result = 0;
        int d = p.getDepth();
        while (XmlUtil.nextChild(p, d)) {
            if (!"patternFill".equals(p.getName())) {
                XmlUtil.skip(p);
                continue;
            }
            String type = XmlUtil.attr(p, "patternType");
            boolean visible = type != null && !"none".equals(type) && !"gray125".equals(type);
            int fg = 0, bg = 0;
            int d2 = p.getDepth();
            while (XmlUtil.nextChild(p, d2)) {
                if ("fgColor".equals(p.getName())) fg = color(p, theme, false);
                else if ("bgColor".equals(p.getName())) bg = color(p, theme, false);
                XmlUtil.skip(p);
            }
            if (visible) result = fg != 0 ? fg : bg;
        }
        return result;
    }

    /** Reads rgb / theme / indexed colour attributes. Returns 0 for automatic. */
    private static int color(XmlPullParser p, int[] theme, boolean font) {
        int c = 0;
        String rgb = XmlUtil.attr(p, "rgb");
        String th = XmlUtil.attr(p, "theme");
        String idx = XmlUtil.attr(p, "indexed");
        if (rgb != null) {
            c = XmlUtil.hexColor(rgb);
        } else if (th != null) {
            int t = XmlUtil.parseInt(th, -1);
            if (t >= 0 && t < theme.length) c = theme[t];
        } else if (idx != null) {
            int i = XmlUtil.parseInt(idx, -1);
            if (i >= 0 && i < INDEXED.length) c = INDEXED[i];
            else return 0;
        } else {
            return 0;
        }
        if (c == 0) return 0;
        float tint = XmlUtil.floatAttr(p, "tint", 0f);
        if (tint != 0f) c = tint(c, tint);
        // Black text is the default; white fills are the same as no fill.
        if (font && (c & 0xFFFFFF) == 0) return 0;
        if (!font && (c & 0xFFFFFF) == 0xFFFFFF) return 0;
        return c;
    }

    private static int tint(int c, float tint) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        if (tint < 0) {
            float f = 1 + tint;
            r = (int) (r * f);
            g = (int) (g * f);
            b = (int) (b * f);
        } else {
            r = (int) (r + (255 - r) * tint);
            g = (int) (g + (255 - g) * tint);
            b = (int) (b + (255 - b) * tint);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Theme colours in SpreadsheetML index order (lt1, dk1, lt2, dk2, accent1..6, hlink, folHlink). */
    private int[] readTheme() throws IOException, XmlPullParserException {
        int[] scheme = {0xFFFFFFFF, 0xFF000000, 0xFFE7E6E6, 0xFF44546A, 0xFF4472C4, 0xFFED7D31,
                0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47, 0xFF0563C1, 0xFF954F72};
        ZipParts.Part part = ZipParts.open(zip, themePart, xml);
        if (part == null) return scheme;
        // Order in the theme file: dk1, lt1, dk2, lt2, accent1..6, hlink, folHlink.
        String[] names = {"dk1", "lt1", "dk2", "lt2", "accent1", "accent2", "accent3", "accent4",
                "accent5", "accent6", "hlink", "folHlink"};
        int[] map = {1, 0, 3, 2, 4, 5, 6, 7, 8, 9, 10, 11};
        try {
            XmlPullParser p = part.parser;
            int current = -1;
            int e;
            while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (e != XmlPullParser.START_TAG) continue;
                String n = p.getName();
                if ("clrScheme".equals(n)) {
                    current = -1;
                    continue;
                }
                int k = indexOf(names, n);
                if (k >= 0) {
                    current = map[k];
                } else if (current >= 0 && ("srgbClr".equals(n) || "sysClr".equals(n))) {
                    String v = XmlUtil.attr(p, "srgbClr".equals(n) ? "val" : "lastClr");
                    int c = XmlUtil.hexColor(v);
                    if (c != 0) scheme[current] = c;
                    current = -1;
                } else if ("fontScheme".equals(n)) {
                    break;
                }
            }
        } finally {
            part.close();
        }
        return scheme;
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }

    // ------------------------------------------------------------------- sheet

    private SheetData readSheet(XmlPullParser p, long maxCells) throws XmlPullParserException, IOException {
        List<SheetData.Row> rows = new ArrayList<>();
        List<Integer> rowIndex = new ArrayList<>();
        List<float[]> colDefs = new ArrayList<>();
        List<int[]> merges = new ArrayList<>();
        float defaultRowHeight = 15f;
        float defaultColWidth = 64f;
        int maxRow = -1, maxCol = -1;
        long cells = 0;
        boolean truncated = false;

        SheetData.Row row = null;
        int rowNum = -1;
        int colCursor = -1;
        int e;
        while ((e = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (e != XmlPullParser.START_TAG) continue;
            switch (p.getName()) {
                case "sheetFormatPr": {
                    defaultRowHeight = XmlUtil.floatAttr(p, "defaultRowHeight", 15f);
                    float dcw = XmlUtil.floatAttr(p, "defaultColWidth", -1f);
                    float base = XmlUtil.floatAttr(p, "baseColWidth", -1f);
                    if (dcw > 0) defaultColWidth = dcw * 7f;
                    else if (base > 0) defaultColWidth = base * 7f + 8f;
                    break;
                }
                case "col": {
                    int min = XmlUtil.intAttr(p, "min", 0);
                    int max = XmlUtil.intAttr(p, "max", min);
                    String hidden = XmlUtil.attr(p, "hidden");
                    boolean h = "1".equals(hidden) || "true".equals(hidden);
                    float w = XmlUtil.floatAttr(p, "width", -1f);
                    if (min > 0) colDefs.add(new float[]{min - 1, Math.min(max, MAX_COLS) - 1, h ? 0 : w * 7f, h ? 1 : 0, w});
                    break;
                }
                case "row": {
                    rowNum = XmlUtil.intAttr(p, "r", rowNum + 2) - 1;
                    if (rowNum < 0 || rowNum >= MAX_ROWS) {
                        row = null;
                        XmlUtil.skip(p);
                        break;
                    }
                    row = new SheetData.Row();
                    String hidden = XmlUtil.attr(p, "hidden");
                    if ("1".equals(hidden) || "true".equals(hidden)) row.height = -1;
                    else {
                        String custom = XmlUtil.attr(p, "customHeight");
                        float ht = XmlUtil.floatAttr(p, "ht", 0f);
                        if (ht > 0 && ("1".equals(custom) || "true".equals(custom) || ht > defaultRowHeight)) {
                            row.height = Math.min(ht, 409f);
                        }
                    }
                    rows.add(row);
                    rowIndex.add(rowNum);
                    colCursor = -1;
                    break;
                }
                case "c": {
                    if (row == null) {
                        XmlUtil.skip(p);
                        break;
                    }
                    String ref = XmlUtil.attr(p, "r");
                    int col = ref == null ? colCursor + 1 : parseColumn(ref);
                    if (col < 0) col = colCursor + 1;
                    colCursor = col;
                    String type = XmlUtil.attr(p, "t");
                    int style = XmlUtil.intAttr(p, "s", 0);
                    String value = null;
                    int d = p.getDepth();
                    while (XmlUtil.nextChild(p, d)) {
                        String n = p.getName();
                        if ("v".equals(n) || "is".equals(n)) value = XmlUtil.text(p);
                        else XmlUtil.skip(p);
                    }
                    if (col >= MAX_COLS) break;
                    if (cells >= maxCells) {
                        truncated = true;
                        break;
                    }
                    boolean added = addCell(row, col, type, value, style);
                    if (added) {
                        cells++;
                        if (rowNum > maxRow) maxRow = rowNum;
                        if (col > maxCol) maxCol = col;
                    }
                    break;
                }
                case "mergeCell": {
                    int[] m = parseRange(XmlUtil.attr(p, "ref"));
                    if (m != null) merges.add(m);
                    break;
                }
                default:
                    break;
            }
        }

        int rowCount = maxRow + 1;
        int colCount = maxCol + 1;
        // Make sure merged areas of the visible part are fully shown.
        for (int[] m : merges) {
            if (m[0] < rowCount && m[1] < colCount) {
                rowCount = Math.max(rowCount, Math.min(m[2] + 1, MAX_ROWS));
                colCount = Math.max(colCount, Math.min(m[3] + 1, MAX_COLS));
            }
        }
        if (maxCol < 0) colCount = 0;

        SheetData.Row[] rowArr = new SheetData.Row[Math.max(0, rowCount)];
        float[] heights = new float[rowArr.length];
        for (int i = 0; i < heights.length; i++) heights[i] = defaultRowHeight;
        for (int i = 0; i < rows.size(); i++) {
            int r = rowIndex.get(i);
            if (r >= rowArr.length) continue;
            SheetData.Row rr = rows.get(i);
            if (rr.height < 0) heights[r] = 0;
            else if (rr.height > 0) heights[r] = rr.height;
            if (rr.size > 0) rowArr[r] = rr;
        }
        float[] widths = new float[Math.max(0, colCount)];
        for (int i = 0; i < widths.length; i++) widths[i] = defaultColWidth;
        for (float[] def : colDefs) {
            int from = (int) def[0], to = Math.min((int) def[1], widths.length - 1);
            for (int c = Math.max(0, from); c <= to; c++) {
                if (def[3] == 1) widths[c] = 0;
                else if (def[4] > 0) widths[c] = def[2];
            }
        }
        return new SheetData(rowArr, colCount, heights, widths, merges, truncated, styles, date1904);
    }

    private boolean addCell(SheetData.Row row, int col, String type, String value, int style) {
        if (value == null || value.isEmpty()) {
            CellStyle cs = style >= 0 && style < styles.length ? styles[style] : null;
            if (cs != null && cs.fillColor != 0) {
                row.add(col, SheetData.EMPTY, null, 0, style);
                return true;
            }
            return false;
        }
        if (type == null || "n".equals(type)) {
            try {
                double d = Double.parseDouble(value);
                CellStyle cs = style >= 0 && style < styles.length ? styles[style] : null;
                if (cs != null && cs.format.kind == NumberFormat.TEXT) {
                    row.add(col, SheetData.TEXT, NumberFormat.general(d), 0, style);
                } else {
                    row.add(col, SheetData.NUMBER, null, d, style);
                }
                return true;
            } catch (NumberFormatException ignored) {
                row.add(col, SheetData.TEXT, value, 0, style);
                return true;
            }
        }
        String text;
        switch (type) {
            case "s": {
                int i = XmlUtil.parseInt(value.trim(), -1);
                text = i >= 0 && i < sharedStrings.length ? sharedStrings[i] : "";
                break;
            }
            case "b":
                text = "1".equals(value.trim()) || "true".equals(value.trim()) ? "TRUE" : "FALSE";
                break;
            default:
                // str, inlineStr, e (error), d (ISO date)
                text = value;
        }
        if (text.isEmpty()) return false;
        row.add(col, SheetData.TEXT, text, 0, style);
        return true;
    }

    /** Column index of a reference such as "AB12", or -1. */
    static int parseColumn(String ref) {
        int col = 0;
        int i = 0;
        for (; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') col = col * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') col = col * 26 + (c - 'a' + 1);
            else if (c == '$') continue;
            else break;
            if (col > MAX_COLS) return -1;
        }
        return i == 0 || col == 0 ? -1 : col - 1;
    }

    static int parseRowNum(String ref) {
        int i = 0;
        while (i < ref.length() && !Character.isDigit(ref.charAt(i))) i++;
        return XmlUtil.parseInt(ref.substring(i), 0) - 1;
    }

    static int[] parseRange(String ref) {
        if (ref == null) return null;
        int colon = ref.indexOf(':');
        if (colon < 0) return null;
        String a = ref.substring(0, colon), b = ref.substring(colon + 1);
        int r1 = parseRowNum(a), c1 = parseColumn(a), r2 = parseRowNum(b), c2 = parseColumn(b);
        if (r1 < 0 || c1 < 0 || r2 < r1 || c2 < c1) return null;
        return new int[]{r1, c1, r2, c2};
    }

    /** Legacy indexed colour palette (indices 0-63). */
    private static final int[] INDEXED = {
            0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFFFF00FF, 0xFF00FFFF,
            0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00, 0xFFFF00FF, 0xFF00FFFF,
            0xFF800000, 0xFF008000, 0xFF000080, 0xFF808000, 0xFF800080, 0xFF008080, 0xFFC0C0C0, 0xFF808080,
            0xFF9999FF, 0xFF993366, 0xFFFFFFCC, 0xFFCCFFFF, 0xFF660066, 0xFFFF8080, 0xFF0066CC, 0xFFCCCCFF,
            0xFF000080, 0xFFFF00FF, 0xFFFFFF00, 0xFF00FFFF, 0xFF800080, 0xFF800000, 0xFF008080, 0xFF0000FF,
            0xFF00CCFF, 0xFFCCFFFF, 0xFFCCFFCC, 0xFFFFFF99, 0xFF99CCFF, 0xFFFF99CC, 0xFFCC99FF, 0xFFFFCC99,
            0xFF3366FF, 0xFF33CCCC, 0xFF99CC00, 0xFFFFCC00, 0xFFFF9900, 0xFFFF6600, 0xFF666699, 0xFF969696,
            0xFF003366, 0xFF339966, 0xFF003300, 0xFF333300, 0xFF993300, 0xFF993366, 0xFF333399, 0xFF333333,
            0xFF000000, 0xFFFFFFFF
    };
}
