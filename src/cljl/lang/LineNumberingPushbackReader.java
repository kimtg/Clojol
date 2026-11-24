package cljl.lang;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.PushbackReader;
import java.io.Reader;

/**
 * PushbackReader tracking 1-based line and column numbers (Clojure-compatible API).
 */
public class LineNumberingPushbackReader extends clojure.lang.LineNumberingPushbackReader {
    private static final int NEWLINE = '\n';
    private boolean atLineStart = true;
    private boolean prevAtLineStart = true;
    private int columnNumber = 1;
    private int prevColumnNumber = 1;

    public LineNumberingPushbackReader(Reader r) {
        super(new LineNumberReader(r));
        ((LineNumberReader) this.in).setLineNumber(1);
    }

    public LineNumberingPushbackReader(Reader r, int size) {
        super(new LineNumberReader(r, size));
        ((LineNumberReader) this.in).setLineNumber(1);
    }

    public int getLineNumber() {
        return ((LineNumberReader) this.in).getLineNumber();
    }

    public void setLineNumber(int line) {
        ((LineNumberReader) this.in).setLineNumber(line);
    }

    public int getColumnNumber() {
        return columnNumber;
    }

    public boolean atLineStart() {
        return atLineStart;
    }

    @Override
    public int read() throws IOException {
        int ret = super.read();
        prevAtLineStart = atLineStart;
        prevColumnNumber = columnNumber;
        if (ret == -1) {
            atLineStart = true;
            return -1;
        }
        if (ret == NEWLINE) {
            atLineStart = true;
            columnNumber = 1;
        } else {
            atLineStart = false;
            columnNumber++;
        }
        return ret;
    }

    @Override
    public void unread(int c) throws IOException {
        if (c == -1) return;
        super.unread(c);
        atLineStart = prevAtLineStart;
        columnNumber = prevColumnNumber;
    }

    public String readLine() throws IOException {
        int c = read();
        if (c == -1) return null;
        StringBuilder sb = new StringBuilder();
        while (c != -1 && c != NEWLINE) {
            if (c != '\r') sb.append((char) c);
            c = read();
        }
        return sb.toString();
    }
}
