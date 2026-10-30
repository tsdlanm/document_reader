package com.tsdlanm.documentreader.pdf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class PdfAnnotationsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A two-page PDF with a classic xref table; objects are numbered from 1 in order. */
    private File pdf(String... objects) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(b, "%PDF-1.4\n");
        long[] offs = new long[objects.length];
        for (int i = 0; i < objects.length; i++) {
            offs[i] = b.size();
            write(b, (i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n");
        }
        long xref = b.size();
        write(b, "xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f\r\n");
        for (long o : offs) write(b, String.format("%010d 00000 n\r\n", o));
        write(b, "trailer\n<< /Size " + (objects.length + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        File f = tmp.newFile();
        try (FileOutputStream os = new FileOutputStream(f)) {
            b.writeTo(os);
        }
        return f;
    }

    private static void write(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(StandardCharsets.ISO_8859_1);
        b.write(d, 0, d.length);
    }

    private static String stream(String dict, String data) {
        return "<< " + dict + " /Length " + data.length() + " >>\nstream\n" + data + "\nendstream";
    }

    private File sample() throws IOException {
        return pdf(
                "<< /Type /Catalog /Pages 2 0 R /Names << /Dests 9 0 R >> >>",
                "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 /MediaBox [0 0 600 800] /Resources << /Font << >> >> >>",
                "<< /Type /Page /Parent 2 0 R /Contents 5 0 R /Annots [6 0 R 7 0 R 8 0 R 10 0 R] >>",
                "<< /Type /Page /Parent 2 0 R /Rotate 90 /Annots [11 0 R] >>",
                stream("", "0 0 1 rg 0 0 10 10 re f"),
                // A signature-like stamp: its appearance is 50 x 100 at (100, 200).
                "<< /Type /Annot /Subtype /Widget /FT /Sig /F 4 /Rect [100 200 150 300] /AP << /N 12 0 R >> >>",
                "<< /Type /Annot /Subtype /Link /Rect [0 700 300 800] /A << /S /URI /URI (https://example.com/a) >> >>",
                "<< /Type /Annot /Subtype /Link /Rect [0 0 60 80] /Dest (second) >>",
                "<< /Names [(second) [4 0 R /Fit]] >>",
                // Hidden: not drawn.
                "<< /Type /Annot /Subtype /Square /F 2 /Rect [0 0 10 10] /AP << /N 12 0 R >> >>",
                // On a page turned 90 degrees: the top-left corner of the page moves to the top right.
                "<< /Type /Annot /Subtype /Link /Rect [0 700 60 800] /Dest [3 0 R /Fit] >>",
                stream("/Type /XObject /Subtype /Form /BBox [0 0 25 50]", "0 g 0 0 25 50 re f"));
    }

    @Test
    public void drawsAppearancesIntoACopyAndCollectsLinks() throws IOException {
        File in = sample();
        File out = new File(tmp.getRoot(), "flat.pdf");
        PdfAnnotations.Result r;
        try (FileInputStream s = new FileInputStream(in)) {
            r = PdfAnnotations.read(s.getChannel(), 2, out);
        }
        assertNotNull(r);
        assertEquals(out, r.flattened);
        assertTrue(r.changed[0]);
        assertFalse(r.changed[1]);

        assertEquals(2, r.links[0].length);
        PdfAnnotations.Link web = r.links[0][0];
        assertEquals("https://example.com/a", web.uri);
        assertEquals(0f, web.left, 1e-4);
        assertEquals(0f, web.top, 1e-4);
        assertEquals(0.5f, web.right, 1e-4);
        assertEquals(0.125f, web.bottom, 1e-4);
        assertNull(r.links[0][1].uri);
        assertEquals(1, r.links[0][1].page);
        PdfAnnotations.Link turned = r.links[1][0];
        assertEquals(0, turned.page);
        assertEquals(0.875f, turned.left, 1e-4);
        assertEquals(0f, turned.top, 1e-4);
        assertEquals(1f, turned.right, 1e-4);
        assertEquals(0.1f, turned.bottom, 1e-4);

        // The copy starts with the original bytes and its update replaces page 1.
        assertTrue(out.length() > in.length());
        try (FileInputStream s = new FileInputStream(out)) {
            PdfFile f = new PdfFile(s.getChannel());
            assertTrue(((Number) f.trailer.get("Prev")).longValue() > 0);
            Map<String, Object> page = f.dict(new PdfFile.Ref(3, 0));
            assertFalse(page.containsKey("Annots"));
            List<Object> contents = f.array(page.get("Contents"));
            assertEquals(3, contents.size());
            assertEquals(5, ((PdfFile.Ref) contents.get(1)).num);
            assertEquals("q\n", data(f, contents.get(0)));
            // Appearance scaled 2x to fill the 50 x 100 rectangle.
            assertEquals("Q\nq 2 0 0 2 100 200 cm /DrAnnot0 Do Q\n", data(f, contents.get(2)));
            Map<String, Object> res = f.dict(page.get("Resources"));
            assertNotNull(res.get("Font"));
            assertEquals(12, ((PdfFile.Ref) f.dict(res.get("XObject")).get("DrAnnot0")).num);
            // Page 2 is unchanged and still inherits from the tree.
            assertTrue(f.dict(new PdfFile.Ref(4, 0)).containsKey("Annots"));
        }
    }

    @Test
    public void nothingToDrawLeavesNoCopy() throws IOException {
        File in = pdf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>");
        File out = new File(tmp.getRoot(), "none.pdf");
        try (FileInputStream s = new FileInputStream(in)) {
            PdfAnnotations.Result r = PdfAnnotations.read(s.getChannel(), 1, out);
            assertNotNull(r);
            assertNull(r.flattened);
            assertFalse(out.exists());
        }
        // A page count that differs from the renderer's: left alone.
        try (FileInputStream s = new FileInputStream(in)) {
            assertNull(PdfAnnotations.read(s.getChannel(), 2, out));
        }
    }

    private static String data(PdfFile f, Object ref) throws IOException {
        return new String(f.streamData((PdfFile.Stream) f.resolve(ref)), StandardCharsets.ISO_8859_1);
    }
}
