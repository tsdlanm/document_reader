package com.tsdlanm.documentreader.docx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tsdlanm.documentreader.TestFiles;
import com.tsdlanm.documentreader.docx.DocModel.Block;
import com.tsdlanm.documentreader.docx.DocModel.Image;
import com.tsdlanm.documentreader.docx.DocModel.Paragraph;
import com.tsdlanm.documentreader.docx.DocModel.SectionBreak;
import com.tsdlanm.documentreader.docx.DocModel.Table;
import com.tsdlanm.documentreader.docx.DocModel.TextBox;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Slides as pages: placeholder inheritance, text styles, colours, groups, tables, backgrounds. */
public class PptxParserTest {
    private static final String NS = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
    private static final String RELS = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private DocModel.Document parse() throws Exception {
        File f = tmp.newFile("t.pptx");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "ppt/presentation.xml", "<p:presentation " + NS + ">"
                    + "<p:sldIdLst><p:sldId id=\"256\" r:id=\"rS1\"/><p:sldId id=\"257\" r:id=\"rS2\"/><p:sldId id=\"258\" r:id=\"rS3\"/></p:sldIdLst>"
                    + "<p:sldSz cx=\"12192000\" cy=\"6858000\"/>"
                    + "<p:defaultTextStyle><a:lvl1pPr><a:defRPr sz=\"1800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill></a:defRPr></a:lvl1pPr></p:defaultTextStyle>"
                    + "</p:presentation>");
            put(z, "ppt/_rels/presentation.xml.rels", RELS
                    + "<Relationship Id=\"rS1\" Type=\"" + REL + "slide\" Target=\"slides/slide1.xml\"/>"
                    + "<Relationship Id=\"rS2\" Type=\"" + REL + "slide\" Target=\"slides/slide2.xml\"/>"
                    + "<Relationship Id=\"rS3\" Type=\"" + REL + "slide\" Target=\"slides/slide3.xml\"/></Relationships>");
            put(z, "ppt/theme/theme1.xml", "<a:theme " + NS + "><a:themeElements><a:clrScheme name=\"t\">"
                    + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1><a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
                    + "<a:dk2><a:srgbClr val=\"1F2937\"/></a:dk2><a:lt2><a:srgbClr val=\"EEEEEE\"/></a:lt2>"
                    + "<a:accent1><a:srgbClr val=\"336699\"/></a:accent1></a:clrScheme>"
                    + "<a:fontScheme name=\"f\"><a:majorFont><a:latin typeface=\"Georgia\"/></a:majorFont>"
                    + "<a:minorFont><a:latin typeface=\"Arial\"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>");
            put(z, "ppt/slideMasters/slideMaster1.xml", "<p:sldMaster " + NS + "><p:cSld>"
                    + "<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"FAFAFA\"/></a:solidFill></p:bgPr></p:bg><p:spTree>"
                    + sp("<p:ph type=\"title\"/>", "<a:xfrm><a:off x=\"100\" y=\"200\"/><a:ext cx=\"9000\" cy=\"1000\"/></a:xfrm>", "<a:bodyPr anchor=\"b\"/>", "")
                    + sp("<p:ph type=\"body\" idx=\"1\"/>", "<a:xfrm><a:off x=\"100\" y=\"2000\"/><a:ext cx=\"9000\" cy=\"4000\"/></a:xfrm>", "<a:bodyPr/>", "")
                    + sp("", "<a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"500\" cy=\"500\"/></a:xfrm><a:prstGeom prst=\"ellipse\"/>"
                    + "<a:solidFill><a:schemeClr val=\"bg2\"/></a:solidFill>", null, "")
                    + "</p:spTree></p:cSld>"
                    + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"dk2\" tx2=\"dk2\" accent1=\"accent1\"/>"
                    + "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"ctr\"><a:defRPr sz=\"4400\" b=\"1\"><a:solidFill><a:schemeClr val=\"tx2\"/></a:solidFill>"
                    + "<a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle>"
                    + "<p:bodyStyle><a:lvl1pPr marL=\"228600\" indent=\"-228600\"><a:buChar char=\"&#8226;\"/><a:defRPr sz=\"2800\"/></a:lvl1pPr>"
                    + "<a:lvl2pPr marL=\"685800\" indent=\"-228600\"><a:buAutoNum type=\"arabicPeriod\"/><a:defRPr sz=\"2400\"/></a:lvl2pPr></p:bodyStyle>"
                    + "<p:otherStyle/></p:txStyles></p:sldMaster>");
            put(z, "ppt/slideMasters/_rels/slideMaster1.xml.rels", RELS
                    + "<Relationship Id=\"rT\" Type=\"" + REL + "theme\" Target=\"../theme/theme1.xml\"/></Relationships>");
            put(z, "ppt/slideLayouts/slideLayout1.xml", "<p:sldLayout " + NS + "><p:cSld><p:spTree>"
                    + sp("<p:ph type=\"title\"/>", "<a:xfrm><a:off x=\"300\" y=\"400\"/><a:ext cx=\"8000\" cy=\"900\"/></a:xfrm>", null, "")
                    + "</p:spTree></p:cSld></p:sldLayout>");
            put(z, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", RELS
                    + "<Relationship Id=\"rM\" Type=\"" + REL + "slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>");
            String layoutRel = RELS + "<Relationship Id=\"rL\" Type=\"" + REL + "slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                    + "<Relationship Id=\"rI\" Type=\"" + REL + "image\" Target=\"../media/image1.png\"/></Relationships>";
            put(z, "ppt/slides/slide1.xml", "<p:sld " + NS + "><p:cSld><p:spTree>"
                    + sp("<p:ph type=\"title\"/>", "", null, "<a:p><a:r><a:rPr lang=\"id\"/><a:t>Judul</a:t></a:r></a:p>")
                    + sp("<p:ph idx=\"1\"/>", "", null,
                    "<a:p><a:r><a:t>Satu</a:t></a:r></a:p>"
                    + "<a:p><a:pPr lvl=\"1\"/><a:r><a:t>Dua</a:t></a:r></a:p>"
                    + "<a:p><a:pPr lvl=\"1\"/><a:r><a:rPr spc=\"100\"><a:solidFill><a:schemeClr val=\"accent1\"><a:lumMod val=\"50000\"/></a:schemeClr></a:solidFill></a:rPr><a:t>Tiga</a:t></a:r></a:p>")
                    + "<p:grpSp><p:nvGrpSpPr><p:cNvPr id=\"9\" name=\"g\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                    + "<p:grpSpPr><a:xfrm><a:off x=\"1000\" y=\"1000\"/><a:ext cx=\"2000\" cy=\"2000\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"1000\" cy=\"1000\"/></a:xfrm></p:grpSpPr>"
                    + "<p:pic><p:nvPicPr><p:cNvPr id=\"10\" name=\"pic\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
                    + "<p:blipFill><a:blip r:embed=\"rI\"/><a:srcRect l=\"10000\" r=\"10000\"/></p:blipFill>"
                    + "<p:spPr><a:xfrm><a:off x=\"100\" y=\"200\"/><a:ext cx=\"300\" cy=\"400\"/></a:xfrm></p:spPr></p:pic></p:grpSp>"
                    + "</p:spTree></p:cSld></p:sld>");
            put(z, "ppt/slides/_rels/slide1.xml.rels", layoutRel);
            put(z, "ppt/slides/slide2.xml", "<p:sld " + NS + " show=\"0\"><p:cSld><p:spTree/></p:cSld></p:sld>");
            put(z, "ppt/slides/_rels/slide2.xml.rels", layoutRel);
            put(z, "ppt/slides/slide3.xml", "<p:sld " + NS + " showMasterSp=\"0\"><p:cSld>"
                    + "<p:bg><p:bgPr><a:solidFill><a:schemeClr val=\"accent1\"/></a:solidFill></p:bgPr></p:bg><p:spTree>"
                    + "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"t\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>"
                    + "<p:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"6000\" cy=\"2000\"/></p:xfrm><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\">"
                    + "<a:tbl><a:tblPr firstRow=\"1\"/><a:tblGrid><a:gridCol w=\"3175000\"/><a:gridCol w=\"3175000\"/></a:tblGrid>"
                    + "<a:tr h=\"370840\"><a:tc gridSpan=\"2\"><a:txBody><a:bodyPr/><a:p><a:r><a:t>Kepala</a:t></a:r></a:p></a:txBody>"
                    + "<a:tcPr><a:solidFill><a:srgbClr val=\"FF0000\"/></a:solidFill></a:tcPr></a:tc><a:tc hMerge=\"1\"><a:txBody><a:bodyPr/><a:p/></a:txBody></a:tc></a:tr>"
                    + "</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"
                    + "</p:spTree></p:cSld></p:sld>");
            put(z, "ppt/slides/_rels/slide3.xml.rels", layoutRel);
        }
        try (ZipFile zip = new ZipFile(f)) {
            assertTrue(PptxParser.isPptx(zip));
            return PptxParser.parse(zip, TestFiles.XML, "Grafik");
        }
    }

    private static String sp(String ph, String spPr, String bodyPr, String paras) {
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"s\"/><p:cNvSpPr/><p:nvPr>" + ph + "</p:nvPr></p:nvSpPr>"
                + "<p:spPr>" + spPr + "</p:spPr>"
                + (bodyPr == null && paras.isEmpty() ? "" : "<p:txBody>" + (bodyPr == null ? "<a:bodyPr/>" : bodyPr)
                + "<a:lstStyle/>" + paras + "</p:txBody>")
                + "</p:sp>";
    }

    private static void put(ZipOutputStream z, String name, String xml) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(xml.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** The drawings of each slide, split at the section breaks. */
    private static List<List<Block>> slides(DocModel.Document doc) {
        List<List<Block>> out = new ArrayList<>();
        List<Block> cur = new ArrayList<>();
        for (Block b : doc.blocks) {
            if (b instanceof SectionBreak) {
                out.add(cur);
                cur = new ArrayList<>();
            } else {
                cur.add(b);
            }
        }
        out.add(cur);
        return out;
    }

    @Test
    public void slideSizeAndHiddenSlides() throws Exception {
        DocModel.Document doc = parse();
        // The hidden second slide is left out.
        assertEquals(2, slides(doc).size());
        assertEquals(12192000 / 635, doc.lastSection.pageWidth);
        assertEquals(6858000 / 635, doc.lastSection.pageHeight);
        assertEquals(0, doc.lastSection.marginLeft);
    }

    @Test
    public void placeholdersInheritPositionAndStyle() throws Exception {
        List<Block> s1 = slides(parse()).get(0);
        // Master decoration first (an ellipse), then the slide's shapes. Its bg2 fill goes through
        // the master's clrMap (bg2 -> dk2), which the file writes after the shapes.
        TextBox deco = (TextBox) s1.get(0);
        assertEquals(DocModel.GEOM_ELLIPSE, deco.geometry);
        assertEquals(0xFF1F2937, deco.fill);

        TextBox title = (TextBox) s1.get(1);
        // Position from the layout, anchoring from the master.
        assertEquals(300, title.hOffset);
        assertEquals(400, title.vOffset);
        assertEquals(8000, title.widthEmu);
        assertEquals(DocModel.REL_PAGE, title.hRel);
        assertEquals(DocModel.CELL_BOTTOM, title.anchor);
        Paragraph tp = (Paragraph) title.blocks.get(0);
        assertEquals(DocModel.ALIGN_CENTER, tp.align);
        assertEquals("Judul", tp.runs.get(0).text.toString());
        assertEquals(88, tp.runs.get(0).halfPoints);
        assertTrue(tp.runs.get(0).bold);
        assertEquals(0xFF1F2937, tp.runs.get(0).color);
        assertEquals("Georgia", tp.runs.get(0).font);

        TextBox body = (TextBox) s1.get(2);
        // Position from the master (the layout has no body placeholder).
        assertEquals(2000, body.vOffset);
        Paragraph one = (Paragraph) body.blocks.get(0);
        assertEquals("•", one.listPrefix);
        assertEquals(360, one.indLeft);
        assertEquals(-360, one.firstLine);
        assertEquals(56, one.runs.get(0).halfPoints);
        assertEquals("Arial", one.runs.get(0).font);
        Paragraph two = (Paragraph) body.blocks.get(1);
        Paragraph three = (Paragraph) body.blocks.get(2);
        assertEquals("1.", two.listPrefix);
        assertEquals("2.", three.listPrefix);
        assertEquals(1080, two.indLeft);
        // accent1 (336699) at half lightness.
        assertEquals(PptxParser.lum(0xFF336699, 0.5f, 0f), three.runs.get(0).color);
        assertEquals(20, three.runs.get(0).charSpacing);
    }

    @Test
    public void groupsScaleTheirChildrenAndPicturesKeepTheirCrop() throws Exception {
        List<Block> s1 = slides(parse()).get(0);
        Image pic = (Image) s1.get(3);
        assertEquals("ppt/media/image1.png", pic.part);
        // Child space 1000 -> 2000, offset 1000.
        assertEquals(1200, pic.hOffset);
        assertEquals(1400, pic.vOffset);
        assertEquals(600, pic.widthEmu);
        assertEquals(800, pic.heightEmu);
        assertNotNull(pic.crop);
        assertEquals(0.1f, pic.crop[0], 0.001f);
        assertEquals(0.1f, pic.crop[2], 0.001f);
    }

    @Test
    public void tablesAndBackgrounds() throws Exception {
        DocModel.Document doc = parse();
        assertEquals(0xFF336699, doc.lastSection.background);
        List<Block> s3 = slides(doc).get(1);
        // showMasterSp="0": no master ellipse.
        assertEquals(1, s3.size());
        TextBox frame = (TextBox) s3.get(0);
        Table t = (Table) frame.blocks.get(0);
        assertEquals(2, t.grid.size());
        assertEquals(5000, (int) t.grid.get(0));
        assertEquals(1, t.rows.get(0).cells.size());
        assertEquals(2, t.rows.get(0).cells.get(0).span);
        assertEquals(0xFFFF0000, t.rows.get(0).cells.get(0).background);
        Paragraph head = (Paragraph) t.rows.get(0).cells.get(0).blocks.get(0);
        assertEquals("Kepala", head.runs.get(0).text.toString());
        assertNull(head.listPrefix);
    }
}
