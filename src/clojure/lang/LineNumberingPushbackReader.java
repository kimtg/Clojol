package clojure.lang;

import java.io.PushbackReader;
import java.io.Reader;

/**
 * Real clojure.lang.LineNumberingPushbackReader base class extended by cljl.lang.LineNumberingPushbackReader.
 */
public class LineNumberingPushbackReader extends PushbackReader {
    public LineNumberingPushbackReader(Reader in) {
        super(in);
    }

    public boolean atLineStart() {
        return true;
    }
}
