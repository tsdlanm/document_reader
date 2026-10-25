package com.tsdlanm.documentreader.util;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

/** XmlFactory backed by the parser built into Android. */
public final class AndroidXml implements XmlFactory {
    public static final AndroidXml INSTANCE = new AndroidXml();

    private AndroidXml() {
    }

    @Override
    public XmlPullParser newParser() throws org.xmlpull.v1.XmlPullParserException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        return p;
    }
}
