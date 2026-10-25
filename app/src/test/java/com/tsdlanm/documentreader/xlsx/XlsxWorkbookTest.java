package com.tsdlanm.documentreader.xlsx;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tsdlanm.documentreader.TestFiles;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;
import java.util.zip.ZipFile;

public class XlsxWorkbookTest {
    private Locale saved;

    @Before
    public void setUp() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @After
    public void tearDown() {
        Locale.setDefault(saved);
    }

    @Test
    public void readsSheetsAndCells() throws Exception {
        try (ZipFile zip = TestFiles.zip("sample.xlsx")) {
            assertTrue(XlsxWorkbook.isXlsx(zip));
            XlsxWorkbook wb = XlsxWorkbook.open(zip, TestFiles.XML);
            // The hidden sheet is left out.
            assertEquals(2, wb.sheets.size());
            assertEquals("Data", wb.sheets.get(0).name);
            assertEquals("Kosong", wb.sheets.get(1).name);

            SheetData s = wb.loadSheet(0, 1_000_000);
            assertFalse(s.truncated);
            assertEquals("Nama", s.text(0, 0));
            assertTrue(s.style(0, 0).bold);
            assertEquals(0xFFFFFF00, s.style(0, 0).fillColor);
            assertEquals("1,234.50", s.text(1, 1));
            assertTrue(s.isNumber(1, 1));
            assertEquals("25.6%", s.text(2, 1));
            assertEquals("Mar 15, 2024", s.text(3, 1));
            assertEquals("TRUE", s.text(4, 1));
            assertEquals("0.3", s.text(1, 2));
            assertEquals("Gabung", s.text(7, 3));
            assertNull(s.text(0, 5));
            assertEquals(1, s.merges.size());
            assertArrayEquals(new int[]{7, 3, 8, 5}, s.merges.get(0));
            assertEquals(9, s.rowCount);
            assertEquals(6, s.colCount);
            // Column A was widened to 20 characters.
            assertTrue(s.colWidths[0] > s.colWidths[1]);

            assertTrue(wb.loadSheet(1, 1_000_000).isEmpty());
        }
    }

    @Test
    public void truncatesAtCellLimit() throws Exception {
        try (ZipFile zip = TestFiles.zip("sample.xlsx")) {
            XlsxWorkbook wb = XlsxWorkbook.open(zip, TestFiles.XML);
            SheetData s = wb.loadSheet(0, 3);
            assertTrue(s.truncated);
        }
    }

    @Test
    public void cellReferences() {
        assertEquals(0, XlsxWorkbook.parseColumn("A1"));
        assertEquals(27, XlsxWorkbook.parseColumn("AB12"));
        assertEquals(2, XlsxWorkbook.parseColumn("$C$3"));
        assertArrayEquals(new int[]{0, 0, 1, 2}, XlsxWorkbook.parseRange("A1:C2"));
        assertEquals("A", SheetData.columnName(0));
        assertEquals("AA", SheetData.columnName(26));
        assertEquals("XFD", SheetData.columnName(16383));
    }

    @Test
    public void numberFormats() {
        assertEquals("1234.5", NumberFormat.general(1234.5));
        assertEquals("0.3", NumberFormat.general(0.1 + 0.2));
        assertEquals("1.23457E+11", NumberFormat.general(123456789012.0));
        assertEquals("1,000", NumberFormat.builtin(3).format(1000, false));
        assertEquals("12%", NumberFormat.builtin(9).format(0.12, false));
        assertEquals("Rp1,500", NumberFormat.parse("[$Rp-421]#,##0").format(1500, false));
        assertEquals("5 kg", NumberFormat.parse("0 \"kg\"").format(5, false));
        assertTrue(NumberFormat.parse("dd/mm/yyyy").isDate());
        assertEquals(NumberFormat.TIME, NumberFormat.parse("h:mm").kind);
        assertEquals(NumberFormat.TIME, NumberFormat.parse("mm:ss").kind);
        assertEquals(NumberFormat.DATE, NumberFormat.parse("mmm-yy").kind);
        assertEquals(NumberFormat.GENERAL, NumberFormat.parse("General").kind);
    }
}
