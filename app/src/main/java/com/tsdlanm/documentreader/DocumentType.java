package com.tsdlanm.documentreader;

import java.util.Locale;

/** Supported formats, detected from the file content rather than its name. */
public enum DocumentType {
    PDF, DOCX, XLSX, PPTX, ZIP, LEGACY_OFFICE, UNKNOWN;

    public static DocumentType sniff(byte[] head, int len) {
        if (len >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F') return PDF;
        if (len >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) return ZIP;
        if (len >= 8 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF && (head[2] & 0xFF) == 0x11
                && (head[3] & 0xFF) == 0xE0) {
            return LEGACY_OFFICE;
        }
        // Some PDFs have junk before the header; look in the first bytes.
        for (int i = 0; i + 4 < len && i < 1024; i++) {
            if (head[i] == '%' && head[i + 1] == 'P' && head[i + 2] == 'D' && head[i + 3] == 'F') return PDF;
        }
        return UNKNOWN;
    }

    /** Best guess from name or MIME type; used only for labels and error messages. */
    public static String labelFor(String name, String mime) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String m = mime == null ? "" : mime;
        if (n.endsWith(".pdf") || m.equals("application/pdf")) return "PDF";
        if (n.endsWith(".docx") || n.endsWith(".doc") || m.contains("word")) return "Word";
        if (n.endsWith(".xlsx") || n.endsWith(".xls") || m.contains("sheet") || m.contains("excel")) return "Excel";
        if (n.endsWith(".pptx") || n.endsWith(".ppt") || m.contains("presentation") || m.contains("powerpoint")) {
            return "PowerPoint";
        }
        return "";
    }
}
