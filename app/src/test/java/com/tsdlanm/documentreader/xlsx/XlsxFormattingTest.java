package com.tsdlanm.documentreader.xlsx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tsdlanm.documentreader.TestFiles;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Borders, alignment, gridlines, freeze panes and pictures. */
public class XlsxFormattingTest {
    private static final String NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private SheetData load() throws Exception {
        File f = tmp.newFile("t.xlsx");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "xl/workbook.xml", "<workbook " + NS + "><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rS\"/></sheets></workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rS\" Type=\"" + REL + "worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "<Relationship Id=\"rT\" Type=\"" + REL + "styles\" Target=\"styles.xml\"/>"
                    + "<Relationship Id=\"rSS\" Type=\"" + REL + "sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>");
            put(z, "xl/sharedStrings.xml", "<sst " + NS + "><si><r><rPr><b/><sz val=\"7.5\"/><rFont val=\"Times New Roman\"/></rPr>"
                    + "<t>Kecil</t></r><r><rPr><sz val=\"20\"/></rPr><t> besar</t></r></si>"
                    + "<si><r><t>Total: </t></r><r><rPr><b/><sz val=\"20\"/></rPr><t>42</t></r></si></sst>");
            put(z, "xl/styles.xml", "<styleSheet " + NS + ">"
                    + "<fonts count=\"1\"><font><sz val=\"11\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"2\"><border/>"
                    + "<border><left style=\"thin\"><color rgb=\"FFFF0000\"/></left><right style=\"medium\"/>"
                    + "<top style=\"dashed\"/><bottom style=\"double\"><color auto=\"1\"/></bottom><diagonal/></border></borders>"
                    + "<cellXfs count=\"3\"><xf numFmtId=\"0\"/>"
                    + "<xf numFmtId=\"0\" borderId=\"1\"><alignment vertical=\"top\" wrapText=\"1\" indent=\"2\"/></xf>"
                    + "<xf numFmtId=\"0\"><alignment vertical=\"center\"/></xf>"
                    + "<xf numFmtId=\"0\"><alignment textRotation=\"255\"/></xf></cellXfs></styleSheet>");
            put(z, "xl/worksheets/sheet1.xml", "<worksheet " + NS + ">"
                    + "<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\">"
                    + "<pane xSplit=\"1\" ySplit=\"2\" topLeftCell=\"B3\" activePane=\"bottomRight\" state=\"frozen\"/>"
                    + "<selection pane=\"bottomRight\"/></sheetView></sheetViews>"
                    + "<sheetData><row r=\"1\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Teks panjang</t></is></c>"
                    + "<c r=\"B1\" s=\"1\"/><c r=\"C1\" s=\"2\"><v>5</v></c></row>"
                    + "<row r=\"4\"><c r=\"A4\"><v>1</v></c><c r=\"B4\"><v>2</v></c>"
                    + "<c r=\"C4\" s=\"2\" t=\"s\"><v>0</v></c><c r=\"D4\" s=\"3\" t=\"inlineStr\"><is><t>UAS</t></is></c>"
                    + "<c r=\"E4\" t=\"s\"><v>1</v></c></row></sheetData>"
                    + "<drawing r:id=\"rD\"/></worksheet>");
            put(z, "xl/worksheets/_rels/sheet1.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rD\" Type=\"" + REL + "drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>");
            put(z, "xl/drawings/drawing1.xml", "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" "
                    + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<xdr:twoCellAnchor><xdr:from><xdr:col>2</xdr:col><xdr:colOff>9525</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                    + "<xdr:to><xdr:col>6</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>9</xdr:row><xdr:rowOff>19050</xdr:rowOff></xdr:to>"
                    + "<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"2\" name=\"Logo\"/></xdr:nvPicPr>"
                    + "<xdr:blipFill><a:blip r:embed=\"rI\"/></xdr:blipFill></xdr:pic><xdr:clientData/></xdr:twoCellAnchor>"
                    + "<xdr:oneCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>12</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                    + "<xdr:ext cx=\"952500\" cy=\"476250\"/><xdr:graphicFrame><a:graphic/></xdr:graphicFrame><xdr:clientData/></xdr:oneCellAnchor>"
                    + "</xdr:wsDr>");
            put(z, "xl/drawings/_rels/drawing1.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rI\" Type=\"" + REL + "image\" Target=\"../media/image1.png\"/></Relationships>");
        }
        try (ZipFile zip = new ZipFile(f)) {
            return XlsxWorkbook.open(zip, TestFiles.XML).loadSheet(0, 1_000_000);
        }
    }

    private static void put(ZipOutputStream z, String name, String xml) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(xml.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    @Test
    public void bordersAndAlignment() throws Exception {
        SheetData s = load();
        CellStyle st = s.style(0, 0);
        assertEquals(CellStyle.B_THIN, st.border(CellStyle.LEFT));
        assertEquals(0xFFFF0000, st.borderColor[CellStyle.LEFT]);
        assertEquals(CellStyle.B_MEDIUM, st.border(CellStyle.RIGHT));
        assertEquals(CellStyle.B_DASHED, st.border(CellStyle.TOP));
        assertEquals(CellStyle.B_DOUBLE, st.border(CellStyle.BOTTOM));
        // Automatic colour: drawn black.
        assertEquals(0, st.borderColor[CellStyle.BOTTOM]);
        assertTrue(st.wrap);
        assertEquals(CellStyle.V_TOP, st.vAlign);
        assertEquals(2, st.indent);
        assertEquals(CellStyle.V_CENTER, s.style(0, 2).vAlign);
        // Excel's default is the bottom of the cell.
        assertEquals(CellStyle.V_BOTTOM, s.style(3, 0).vAlign);
        assertEquals(CellStyle.B_NONE, s.style(3, 0).border(CellStyle.LEFT));
    }

    @Test
    public void richTextUsesItsFirstRunsFont() throws Exception {
        SheetData s = load();
        assertEquals("Kecil besar", s.text(3, 2));
        CellStyle st = s.style(3, 2);
        assertEquals(7.5f / 11f, st.fontSize, 0.001f);
        assertTrue(st.bold);
        assertEquals("Times New Roman", st.font);
        // The cell's own alignment is kept.
        assertEquals(CellStyle.V_CENTER, st.vAlign);
    }

    @Test
    public void richTextWithAPlainFirstRunKeepsTheCellStyle() throws Exception {
        SheetData s = load();
        assertEquals("Total: 42", s.text(3, 4));
        assertFalse(s.style(3, 4).bold);
        assertEquals(1f, s.style(3, 4).fontSize, 0.001f);
    }

    @Test
    public void stackedText() throws Exception {
        assertEquals(255, load().style(3, 3).rotation);
    }

    @Test
    public void emptyCellKeepsItsBorder() throws Exception {
        SheetData s = load();
        assertNull(s.text(0, 1));
        assertFalse(s.hasCell(0, 1));
        assertEquals(CellStyle.B_THIN, s.style(0, 1).border(CellStyle.LEFT));
    }

    @Test
    public void gridlinesAndFreezePanes() throws Exception {
        SheetData s = load();
        assertFalse(s.showGrid);
        assertEquals(2, s.frozenRows);
        assertEquals(1, s.frozenCols);
    }

    @Test
    public void picturesAndCharts() throws Exception {
        SheetData s = load();
        assertEquals(2, s.pictures.size());
        SheetData.Picture logo = s.pictures.get(0);
        assertEquals("xl/media/image1.png", logo.part);
        assertEquals(2, logo.fromCol);
        assertEquals(1, logo.fromRow);
        assertEquals(9525, logo.fromColOff);
        assertEquals(6, logo.toCol);
        assertEquals(9, logo.toRow);
        assertEquals(19050, logo.toRowOff);
        SheetData.Picture chart = s.pictures.get(1);
        assertNull(chart.part);
        assertEquals(-1, chart.toCol);
        assertEquals(952500, chart.width);
        assertEquals(12, chart.fromRow);
        // The grid grows to hold the pictures.
        assertEquals(13, s.rowCount);
        assertEquals(7, s.colCount);
    }
}
