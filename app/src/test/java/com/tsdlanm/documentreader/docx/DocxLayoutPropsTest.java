package com.tsdlanm.documentreader.docx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.tsdlanm.documentreader.TestFiles;
import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Border;
import com.tsdlanm.documentreader.docx.DocModel.PageBreak;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.SectionBreak;
import com.tsdlanm.documentreader.docx.DocModel.Table;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Page-layout properties: spacing, indents, fonts, breaks, sections, headers and table borders. */
public class DocxLayoutPropsTest {
    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DocModel.Document parse() throws Exception {
        File f = tmp.newFile("t.docx");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rTheme\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>"
                    + "<Relationship Id=\"rH\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>"
                    + "</Relationships>");
            put(z, "word/theme/theme1.xml", "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">"
                    + "<a:themeElements><a:fontScheme><a:majorFont><a:latin typeface=\"Cambria\"/></a:majorFont>"
                    + "<a:minorFont><a:latin typeface=\"Calibri\"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>");
            put(z, "word/styles.xml", "<w:styles " + W + ">"
                    + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:asciiTheme=\"minorHAnsi\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault>"
                    + "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                    + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
                    + "<w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/><w:sz w:val=\"24\"/></w:rPr></w:style>"
                    + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>"
                    + "<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>"
                    + "<w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\"/>"
                    + "<w:insideH w:val=\"single\" w:sz=\"8\" w:color=\"FF0000\"/></w:tblBorders></w:tblPr></w:style>"
                    + "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>"
                    + "<w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>"
                    + "</w:styles>");
            put(z, "word/header1.xml", "<w:hdr " + W + "><w:p><w:r><w:t xml:space=\"preserve\">Hal </w:t></w:r>"
                    + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText> PAGE </w:instrText></w:r>"
                    + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>7</w:t></w:r>"
                    + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:hdr>");
            put(z, "word/document.xml", "<w:document " + W + "><w:body>"
                    + "<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"240\" w:line=\"360\" w:lineRule=\"auto\"/>"
                    + "<w:ind w:left=\"720\" w:hanging=\"360\"/><w:jc w:val=\"both\"/>"
                    + "<w:tabs><w:tab w:val=\"right\" w:leader=\"dot\" w:pos=\"9000\"/></w:tabs></w:pPr>"
                    + "<w:r><w:t>Satu</w:t></w:r><w:r><w:br w:type=\"page\"/><w:t>Dua</w:t></w:r></w:p>"
                    + "<w:p><w:pPr><w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
                    + "<w:pgMar w:top=\"1701\" w:right=\"1701\" w:bottom=\"1701\" w:left=\"2268\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
                    + "<w:headerReference w:type=\"default\" r:id=\"rH\"/><w:pgNumType w:fmt=\"lowerRoman\" w:start=\"1\"/>"
                    + "</w:sectPr></w:pPr><w:r><w:rPr><w:rFonts w:asciiTheme=\"majorHAnsi\"/><w:caps/></w:rPr><w:t>Bab</w:t></w:r></w:p>"
                    + "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblBorders><w:top w:val=\"nil\"/></w:tblBorders></w:tblPr>"
                    + "<w:tblGrid><w:gridCol w:w=\"3000\"/></w:tblGrid>"
                    + "<w:tr><w:trPr><w:trHeight w:val=\"500\" w:hRule=\"exact\"/></w:trPr>"
                    + "<w:tc><w:tcPr><w:tcBorders><w:left w:val=\"double\" w:sz=\"12\" w:color=\"00FF00\"/></w:tcBorders>"
                    + "<w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:r><w:t>Sel</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                    + "<w:p><w:hyperlink w:anchor=\"_Toc1\"><w:r><w:rPr><w:rStyle w:val=\"Hyperlink\"/></w:rPr><w:t>Bab 1</w:t></w:r></w:hyperlink>"
                    + "<w:hyperlink r:id=\"rWeb\"><w:r><w:rPr><w:rStyle w:val=\"Hyperlink\"/></w:rPr><w:t>situs</w:t></w:r></w:hyperlink></w:p>"
                    + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"20160\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>"
                    + "</w:body></w:document>");
        }
        try (ZipFile zip = new ZipFile(f)) {
            return DocxParser.parse(zip, TestFiles.XML);
        }
    }

    private static void put(ZipOutputStream z, String name, String xml) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(xml.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    @Test
    public void internalLinksReadAsPlainText() throws Exception {
        DocModel.Document doc = parse();
        Paragraph links = null;
        for (DocModel.Block b : doc.blocks) {
            if (b instanceof Paragraph && ((Paragraph) b).plainText().startsWith("Bab 1")) links = (Paragraph) b;
        }
        assertNotNull(links);
        // A table-of-contents link: no link colour or underline.
        assertEquals(0, links.runs.get(0).color);
        assertFalse(links.runs.get(0).underline);
        // A web link keeps them.
        assertEquals(0xFF0563C1, links.runs.get(1).color);
        assertTrue(links.runs.get(1).underline);
    }

    @Test
    public void paragraphSpacingIndentsAndBreaks() throws Exception {
        DocModel.Document doc = parse();
        Paragraph first = (Paragraph) doc.blocks.get(0);
        assertEquals("Satu", first.plainText());
        assertEquals(120, first.spaceBefore);
        // The part before a page break does not get the paragraph's space after.
        assertEquals(0, first.spaceAfter);
        assertEquals(360, first.line);
        assertEquals(DocModel.LINE_AUTO, first.lineRule);
        assertEquals(720, first.indLeft);
        assertEquals(-360, first.firstLine);
        assertEquals(DocModel.ALIGN_JUSTIFY, first.align);
        assertEquals(9000, first.tabs[0]);
        assertEquals(DocModel.TAB_RIGHT, first.tabAligns[0]);
        assertEquals('.', first.tabLeaders[0]);
        assertEquals("Times New Roman", first.runs.get(0).font);
        assertEquals(24, first.runs.get(0).halfPoints);

        assertTrue(doc.blocks.get(1) instanceof PageBreak);
        Paragraph rest = (Paragraph) doc.blocks.get(2);
        assertEquals("Dua", rest.plainText());
        assertEquals(240, rest.spaceAfter);
    }

    @Test
    public void sectionsHeadersAndThemeFonts() throws Exception {
        DocModel.Document doc = parse();
        Paragraph bab = (Paragraph) doc.blocks.get(3);
        Run run = bab.runs.get(0);
        assertEquals("Cambria", run.font);
        assertTrue(run.caps);

        SectionBreak sb = (SectionBreak) doc.blocks.get(4);
        DocModel.Section s = sb.section;
        assertEquals(2268, s.marginLeft);
        assertEquals("lowerRoman", s.pageNumberFormat);
        assertEquals(1, s.pageNumberStart);
        assertNotNull(s.header);
        Paragraph hp = (Paragraph) s.header.get(0);
        assertEquals("Hal ", hp.runs.get(0).text.toString());
        assertTrue(hp.runs.get(1).pageNumber);
        // The cached field result "7" is dropped.
        assertEquals(2, hp.runs.size());

        assertEquals(12240, doc.lastSection.pageWidth);
        assertEquals(20160, doc.lastSection.pageHeight);
    }

    @Test
    public void tableStyleBordersAndCellProps() throws Exception {
        DocModel.Document doc = parse();
        Table t = (Table) doc.blocks.get(5);
        // Direct tblBorders override the style; others come from the style.
        assertSame(Border.NONE, t.borders[DocModel.B_TOP]);
        Border insideH = t.borders[DocModel.B_INSIDE_H];
        assertNotNull(insideH);
        assertEquals(8, insideH.size);
        assertEquals(0xFFFF0000, insideH.color);
        assertTrue(t.rows.get(0).exactHeight);
        assertEquals(500, t.rows.get(0).height);

        DocModel.Cell cell = t.rows.get(0).cells.get(0);
        assertEquals(DocModel.CELL_CENTER, cell.vAlign);
        assertEquals(0xFF00FF00, cell.borders[DocModel.B_LEFT].color);
        // Paragraphs in the table use the table style's spacing.
        Paragraph p = (Paragraph) cell.blocks.get(0);
        assertEquals(0, p.spaceAfter);
        assertEquals(240, p.line);
        assertFalse(p.isEmpty());
    }

    @Test
    public void columnsTextBoxesAndFootnotes() throws Exception {
        File f = tmp.newFile("c.docx");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rF\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes\" Target=\"footnotes.xml\"/>"
                    + "</Relationships>");
            put(z, "word/footnotes.xml", "<w:footnotes " + W + ">"
                    + "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
                    + "<w:footnote w:id=\"1\"><w:p><w:r><w:footnoteRef/></w:r><w:r><w:t xml:space=\"preserve\"> Sumber data.</w:t></w:r></w:p></w:footnote>"
                    + "</w:footnotes>");
            String wp = "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
                    + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                    + "xmlns:wps=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\"";
            put(z, "word/document.xml", "<w:document " + W + " " + wp + "><w:body>"
                    + "<w:p><w:r><w:t>Kiri</w:t></w:r><w:r><w:br w:type=\"column\"/><w:t>Kanan</w:t></w:r>"
                    + "<w:r><w:footnoteReference w:id=\"1\"/></w:r></w:p>"
                    + "<w:p><w:pPr><w:sectPr><w:type w:val=\"continuous\"/><w:cols w:num=\"2\" w:space=\"720\" w:equalWidth=\"0\">"
                    + "<w:col w:w=\"2910\" w:space=\"1792\"/><w:col w:w=\"5224\"/></w:cols></w:sectPr></w:pPr></w:p>"
                    + "<w:p><w:r><w:drawing><wp:anchor behindDoc=\"0\"><wp:positionH relativeFrom=\"page\"><wp:posOffset>914400</wp:posOffset></wp:positionH>"
                    + "<wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>12700</wp:posOffset></wp:positionV>"
                    + "<wp:extent cx=\"713740\" cy=\"894080\"/><wp:wrapNone/><a:graphic><a:graphicData><wps:wsp>"
                    + "<wps:spPr><a:ln><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></a:ln></wps:spPr>"
                    + "<wps:txbx><w:txbxContent><w:p><w:r><w:t>Foto</w:t></w:r></w:p></w:txbxContent></wps:txbx>"
                    + "</wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>"
                    + "</w:body></w:document>");
        }
        DocModel.Document doc;
        try (ZipFile zip = new ZipFile(f)) {
            doc = DocxParser.parse(zip, TestFiles.XML);
        }
        assertEquals("Kiri", ((Paragraph) doc.blocks.get(0)).plainText());
        assertTrue(doc.blocks.get(1) instanceof DocModel.ColumnBreak);
        Paragraph right = (Paragraph) doc.blocks.get(2);
        assertEquals("Kanan1", right.plainText());
        assertEquals(DocModel.VALIGN_SUPER, right.runs.get(1).vertAlign);
        assertEquals(1, right.footnotes.size());
        DocModel.Footnote note = right.footnotes.get(0);
        assertEquals(1, note.number);
        Paragraph noteText = (Paragraph) note.blocks.get(0);
        // The note's own mark is a placeholder filled with its number when laid out.
        assertTrue(noteText.runs.get(0).pageNumber);
        assertEquals(" Sumber data.", noteText.runs.get(1).text.toString());

        DocModel.Section cols = ((SectionBreak) doc.blocks.get(4)).section;
        assertTrue(cols.continuous);
        assertEquals(2910, cols.columnWidths[0]);
        assertEquals(1792, cols.columnGaps[0]);
        assertEquals(5224, cols.columnWidths[1]);

        DocModel.TextBox box = (DocModel.TextBox) doc.blocks.get(5);
        assertTrue(box.floating);
        assertEquals(DocModel.REL_PAGE, box.hRel);
        assertEquals(914400, box.hOffset);
        assertEquals(0xFFFF0000, box.border);
        assertEquals("Foto", ((Paragraph) box.blocks.get(0)).plainText());
    }

    @Test
    public void pageNumberFormats() {
        assertEquals("iv", Numbering.format(4, "lowerRoman"));
        assertEquals("12", Numbering.format(12, "decimal"));
    }
}
