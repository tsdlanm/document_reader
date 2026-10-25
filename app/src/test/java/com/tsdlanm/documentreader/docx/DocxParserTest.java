package com.tsdlanm.documentreader.docx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.tsdlanm.documentreader.TestFiles;
import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.Run;
import com.tsdlanm.documentreader.docx.DocModel.Table;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

public class DocxParserTest {

    private static List<Paragraph> paragraphs(DocModel.Document doc) {
        List<Paragraph> out = new ArrayList<>();
        for (Block b : doc.blocks) if (b instanceof Paragraph) out.add((Paragraph) b);
        return out;
    }

    private static Paragraph find(DocModel.Document doc, String text) {
        for (Paragraph p : paragraphs(doc)) if (p.plainText().contains(text)) return p;
        throw new AssertionError("No paragraph with " + text);
    }

    @Test
    public void parsesSampleDocument() throws Exception {
        try (ZipFile zip = TestFiles.zip("sample.docx")) {
            assertTrue(DocxParser.isDocx(zip));
            DocModel.Document doc = DocxParser.parse(zip, TestFiles.XML);

            Paragraph title = find(doc, "Judul Dokumen");
            assertEquals(1, title.heading);

            Paragraph body = find(doc, "Teks biasa");
            Run bold = null, red = null;
            for (Run r : body.runs) {
                if (r.text.toString().equals("tebal")) bold = r;
                if (r.text.toString().equals("miring merah")) red = r;
            }
            assertNotNull(bold);
            assertTrue(bold.bold);
            assertNotNull(red);
            assertTrue(red.italic);
            assertEquals(0xFFFF0000, red.color);

            assertEquals(DocModel.ALIGN_CENTER, find(doc, "Tengah").align);
            assertEquals("•", find(doc, "Butir satu").listPrefix);
            assertEquals("1.", find(doc, "Nomor satu").listPrefix);
            assertEquals("2.", find(doc, "Nomor dua").listPrefix);
            assertEquals(44, find(doc, "Besar").runs.get(0).halfPoints);

            Table table = null;
            Image image = null;
            for (Block b : doc.blocks) {
                if (b instanceof Table) table = (Table) b;
                if (b instanceof Image) image = (Image) b;
            }
            assertNotNull(table);
            assertEquals(2, table.rows.size());
            assertEquals(3, table.columnCount());
            Paragraph cell = (Paragraph) table.rows.get(1).get(2).blocks.get(0);
            assertEquals("R1C2", cell.plainText());

            assertNotNull(image);
            assertNotNull(zip.getEntry(image.part));
            assertEquals(2 * 914400L, image.widthEmu);
        }
    }

    @Test
    public void bodyTextIsNotHeading() throws Exception {
        try (ZipFile zip = TestFiles.zip("sample.docx")) {
            DocModel.Document doc = DocxParser.parse(zip, TestFiles.XML);
            Paragraph body = find(doc, "Teks biasa");
            assertEquals(0, body.heading);
            assertFalse(body.isEmpty());
            assertEquals(22, doc.defaultHalfPoints);
        }
    }

    @Test
    public void numberFormats() {
        assertEquals("iv", Numbering.format(4, "lowerRoman"));
        assertEquals("XIV", Numbering.format(14, "upperRoman"));
        assertEquals("b", Numbering.format(2, "lowerLetter"));
        assertEquals("AA", Numbering.format(27, "upperLetter"));
        assertEquals("07", Numbering.format(7, "decimalZero"));
    }
}
