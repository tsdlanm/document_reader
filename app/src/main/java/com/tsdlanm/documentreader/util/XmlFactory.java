package com.tsdlanm.documentreader.util;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Creates namespace-aware pull parsers. Lets the parsers run on Android and in JVM unit tests. */
public interface XmlFactory {
    XmlPullParser newParser() throws XmlPullParserException;
}
