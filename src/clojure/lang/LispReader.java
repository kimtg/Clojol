package clojure.lang;

/**
 * Real clojure.lang.LispReader runtime bridge with nested Resolver and ReaderException.
 */
public class LispReader {
    public interface Resolver {
        Symbol currentNS();
        Symbol resolveClass(Symbol sym);
        Symbol resolveAlias(Symbol sym);
        Symbol resolveVar(Symbol sym);
    }

    public static class ReaderException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int line;
        public final int column;

        public ReaderException(int line, int column, Throwable cause) {
            super(cause);
            this.line = line;
            this.column = column;
        }

        public ReaderException(int line, int column, String message) {
            super(message);
            this.line = line;
            this.column = column;
        }
    }

    public static Object read(LineNumberingPushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive) {
        cljl.RT.ensureInit();
        return cljl.Reader.readFromStream(r, eofIsError, eofValue, null);
    }

    public static Object read(java.io.PushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive) {
        cljl.RT.ensureInit();
        return cljl.Reader.readFromStream(r, eofIsError, eofValue, null);
    }
}
