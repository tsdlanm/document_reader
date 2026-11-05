package com.tsdlanm.documentreader;

import static org.junit.Assume.assumeTrue;

import com.tsdlanm.documentreader.docx.DocModel;
import com.tsdlanm.documentreader.docx.DocxParser;
import com.tsdlanm.documentreader.docx.PptxParser;
import com.tsdlanm.documentreader.xlsx.SheetData;
import com.tsdlanm.documentreader.xlsx.XlsxWorkbook;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Not a regular test: parses every document in the folder named by the SAMPLES_DIR environment
 * variable and writes an outline of each model to SAMPLES_OUT (default build/samples-dump), plus
 * a summary with errors and times. Skipped when SAMPLES_DIR is unset.
 */
public class SamplesDump {
    @Test
    public void dumpAll() throws IOException {
        String dir = System.getenv("SAMPLES_DIR");
        assumeTrue(dir != null);
        File out = new File(System.getenv("SAMPLES_OUT") != null ? System.getenv("SAMPLES_OUT") : "build/samples-dump");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        File[] files = new File(dir).listFiles();
        assumeTrue(files != null);
        Arrays.sort(files);
        StringBuilder summary = new StringBuilder();
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".docx") && !n.endsWith(".pptx") && !n.endsWith(".xlsx")) continue;
            StringBuilder sb = new StringBuilder();
            long t0 = System.nanoTime();
            String status;
            try (ZipFile zip = new ZipFile(f)) {
                if (n.endsWith(".xlsx")) dumpWorkbook(zip, sb);
                else {
                    DocModel.Document doc = n.endsWith(".docx") ? DocxParser.parse(zip, TestFiles.XML)
                            : PptxParser.parse(zip, TestFiles.XML, "Chart");
                    dumpDoc(doc, sb);
                }
                status = "ok";
            } catch (Throwable e) {
                StringWriter w = new StringWriter();
                e.printStackTrace(new PrintWriter(w));
                sb.append("\nERROR ").append(w);
                status = "ERROR " + e;
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            summary.append(String.format(Locale.ROOT, "%-6s %5d ms  %s%n", status.startsWith("ok") ? "ok" : "ERROR", ms, f.getName()));
            if (!status.equals("ok")) summary.append("       ").append(status).append('\n');
            write(new File(out, f.getName() + ".txt"), sb.toString());
        }
        write(new File(out, "_summary.txt"), summary.toString());
        System.out.println(summary);
    }

    private static void write(File f, String s) throws IOException {
        try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }

    // ---------------------------------------------------------------- docx

    private static void dumpDoc(DocModel.Document doc, StringBuilder sb) {
        DocModel.Section s = doc.lastSection;
        sb.append(String.format(Locale.ROOT, "defaultHalfPoints=%d defaultTab=%d%n", doc.defaultHalfPoints, doc.defaultTab));
        blocks(doc.blocks, sb, "");
        sb.append("LAST ");
        section(s, sb, "");
    }

    private static void section(DocModel.Section s, StringBuilder sb, String ind) {
        sb.append(String.format(Locale.ROOT, "SECTION page=%dx%d margins t%d r%d b%d l%d hdr=%d ftr=%d cont=%b title=%b cols=%s bg=%08x bgImg=%s%n",
                s.pageWidth, s.pageHeight, s.marginTop, s.marginRight, s.marginBottom, s.marginLeft,
                s.headerDistance, s.footerDistance, s.continuous, s.titlePage, Arrays.toString(s.columnWidths),
                s.background, s.backgroundImage));
        sub("header", s.header, sb, ind);
        sub("firstHeader", s.firstHeader, sb, ind);
        sub("footer", s.footer, sb, ind);
        sub("firstFooter", s.firstFooter, sb, ind);
    }

    private static void sub(String name, List<DocModel.Block> b, StringBuilder sb, String ind) {
        if (b == null) return;
        sb.append(ind).append("  ").append(name).append(":\n");
        blocks(b, sb, ind + "    ");
    }

    private static void blocks(List<DocModel.Block> list, StringBuilder sb, String ind) {
        for (DocModel.Block b : list) block(b, sb, ind);
    }

    private static void block(DocModel.Block b, StringBuilder sb, String ind) {
        if (b instanceof DocModel.Paragraph) {
            DocModel.Paragraph p = (DocModel.Paragraph) b;
            sb.append(ind).append(String.format(Locale.ROOT, "P al=%d ind=%d/%d/%d sp=%d/%d line=%d/%d h=%d style=%s%s%s%s mark=%d%n",
                    p.align, p.indLeft, p.indRight, p.firstLine, p.spaceBefore, p.spaceAfter, p.line, p.lineRule,
                    p.heading, p.styleId, p.listPrefix != null ? " list=\"" + p.listPrefix + "\"" : "",
                    p.pageBreakBefore ? " pbb" : "", p.tabs.length > 0 ? " tabs=" + Arrays.toString(p.tabs) : "",
                    p.markHalfPoints));
            for (DocModel.Run r : p.runs) {
                if (r.text.length() == 0) continue;
                sb.append(ind).append("  R");
                if (r.bold) sb.append(" b");
                if (r.italic) sb.append(" i");
                if (r.underline) sb.append(" u");
                if (r.caps) sb.append(" caps");
                if (r.halfPoints != 0) sb.append(" hp=").append(r.halfPoints);
                if (r.font != null) sb.append(" f=").append(r.font);
                if (r.color != 0) sb.append(String.format(" c=%08x", r.color));
                if (r.background != 0) sb.append(String.format(" bg=%08x", r.background));
                if (r.pageNumber) sb.append(" PAGE");
                String t = r.text.toString().replace("\t", "\\t").replace("\n", "\\n");
                if (t.length() > 120) t = t.substring(0, 120) + "…";
                sb.append(" \"").append(t).append("\"\n");
            }
            if (p.footnotes != null) for (DocModel.Footnote fn : p.footnotes) {
                sb.append(ind).append("  FOOTNOTE ").append(fn.number).append('\n');
                blocks(fn.blocks, sb, ind + "    ");
            }
        } else if (b instanceof DocModel.Table) {
            DocModel.Table t = (DocModel.Table) b;
            sb.append(ind).append(String.format(Locale.ROOT, "TABLE rows=%d cols=%d grid=%s align=%d indent=%d borders=%s%n",
                    t.rows.size(), t.columnCount(), t.grid, t.align, t.indent, borders(t.borders)));
            for (int r = 0; r < t.rows.size(); r++) {
                DocModel.Row row = t.rows.get(r);
                sb.append(ind).append(String.format(Locale.ROOT, "  ROW %d h=%d%s%n", r, row.height, row.exactHeight ? " exact" : ""));
                for (int c = 0; c < row.cells.size(); c++) {
                    DocModel.Cell cell = row.cells.get(c);
                    sb.append(ind).append(String.format(Locale.ROOT, "    CELL %d span=%d w=%d va=%d%s%s borders=%s%n", c, cell.span,
                            cell.width, cell.vAlign, cell.mergedContinue ? " vmerge-cont" : "",
                            cell.background != 0 ? String.format(" bg=%08x", cell.background) : "", borders(cell.borders)));
                    blocks(cell.blocks, sb, ind + "      ");
                }
            }
        } else if (b instanceof DocModel.Image) {
            DocModel.Image im = (DocModel.Image) b;
            sb.append(ind).append("IMAGE ").append(im.part).append(drawing(im)).append(im.crop != null ? " crop=" + Arrays.toString(im.crop) : "").append('\n');
        } else if (b instanceof DocModel.TextBox) {
            DocModel.TextBox tb = (DocModel.TextBox) b;
            sb.append(ind).append(String.format(Locale.ROOT, "TEXTBOX geom=%d fill=%08x border=%08x anchor=%d", tb.geometry, tb.fill, tb.border, tb.anchor))
                    .append(drawing(tb)).append('\n');
            blocks(tb.blocks, sb, ind + "  ");
        } else if (b instanceof DocModel.SectionBreak) {
            sb.append(ind);
            section(((DocModel.SectionBreak) b).section, sb, ind);
        } else if (b instanceof DocModel.PageBreak) {
            sb.append(ind).append("PAGEBREAK\n");
        } else if (b instanceof DocModel.ColumnBreak) {
            sb.append(ind).append("COLUMNBREAK\n");
        } else {
            sb.append(ind).append(b.getClass().getSimpleName()).append('\n');
        }
    }

    private static String drawing(DocModel.Drawing d) {
        return String.format(Locale.ROOT, " size=%.0fx%.0fpt al=%d float=%b wrap=%b hRel=%d vRel=%d hAl=%d vAl=%d off=%.0f,%.0fpt",
                d.widthEmu / 12700.0, d.heightEmu / 12700.0, d.align, d.floating, d.wrapsText, d.hRel, d.vRel,
                d.hAlign, d.vAlign, d.hOffset / 12700.0, d.vOffset / 12700.0);
    }

    private static String borders(DocModel.Border[] bs) {
        StringBuilder sb = new StringBuilder("[");
        for (DocModel.Border b : bs) {
            if (sb.length() > 1) sb.append(' ');
            sb.append(b == null ? "-" : !b.visible() ? "none" : b.size + "/" + String.format("%06x", b.color & 0xFFFFFF));
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------- xlsx

    private static void dumpWorkbook(ZipFile zip, StringBuilder sb) throws Exception {
        XlsxWorkbook wb = XlsxWorkbook.open(zip, TestFiles.XML);
        for (int i = 0; i < wb.sheets.size(); i++) {
            SheetData s = wb.loadSheet(i, 3_000_000);
            sb.append(String.format(Locale.ROOT, "SHEET %d \"%s\" rows=%d cols=%d truncated=%b frozen=%d/%d grid=%b merges=%d pictures=%d filter=%s defW=%.1f defH=%.1f%n",
                    i, wb.sheets.get(i).name, s.rowCount, s.colCount, s.truncated, s.frozenRows, s.frozenCols, s.showGrid,
                    s.merges.size(), s.pictures.size(), Arrays.toString(s.filterRange), s.defaultColWidth, s.defaultRowHeight));
            sb.append("  colWidths=").append(Arrays.toString(s.colWidths)).append('\n');
            for (SheetData.Picture p : s.pictures) {
                if (p.shapes == null) continue;
                for (SheetData.Shape sh : p.shapes) {
                    StringBuilder t = new StringBuilder();
                    if (sh.text != null) {
                        for (SheetData.Para para : sh.text) for (SheetData.Run r : para.runs) t.append(r.text);
                    }
                    sb.append(String.format(Locale.ROOT, "  SHAPE at %d,%d geom=%d fill=%08x grad=%b line=%08x box=%.2f,%.2f-%.2f,%.2f \"%s\"%n",
                            p.fromCol, p.fromRow, sh.geometry, sh.fill, sh.gradient != null, sh.line,
                            sh.x0, sh.y0, sh.x1, sh.y1, t));
                }
            }
            int rows = Math.min(s.rowCount, 60);
            for (int r = 0; r < rows; r++) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < s.colCount; c++) {
                    String t = s.text(r, c);
                    if (t == null) continue;
                    line.append(" | ").append(SheetData.columnName(c)).append(r + 1).append(s.isNumber(r, c) ? "#" : "")
                            .append('=').append(t.length() > 40 ? t.substring(0, 40) + "…" : t);
                }
                if (line.length() > 0) sb.append(String.format(Locale.ROOT, "  r%d h=%.1f%s%n", r + 1, s.rowHeights[r], line));
            }
        }
    }
}
