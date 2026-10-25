package com.tsdlanm.documentreader;

import com.tsdlanm.documentreader.util.XmlFactory;

import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.zip.ZipFile;

/** Fixtures and an XML factory for plain-JVM tests (Android's parser is kxml2 too). */
public final class TestFiles {
    public static final XmlFactory XML = () -> {
        XmlPullParser p = new KXmlParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        return p;
    };

    private TestFiles() {
    }

    public static ZipFile zip(String name) throws IOException {
        URL url = TestFiles.class.getClassLoader().getResource(name);
        if (url == null) throw new IOException("Missing fixture " + name);
        try {
            return new ZipFile(new File(url.toURI()));
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
    }
}
