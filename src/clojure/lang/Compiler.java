package clojure.lang;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Clean-room clojure.lang.Compiler static bridge exposing standard Clojure compiler vars and entry points.
 */
public final class Compiler {
    public static final cljl.Var LOADER = cljl.Compiler.LOADER;
    public static final cljl.Var LOCAL_ENV = cljl.Compiler.LOCAL_ENV;
    public static final cljl.Var METHOD = cljl.Compiler.METHOD;
    public static final cljl.Var CLEAR_SITES = cljl.Compiler.CLEAR_SITES;
    public static final cljl.Var LINE = cljl.Compiler.LINE;
    public static final cljl.Var COLUMN = cljl.Compiler.COLUMN;
    public static final cljl.Var SOURCE = cljl.Compiler.SOURCE;
    public static final cljl.Var SOURCE_PATH = cljl.Compiler.SOURCE_PATH;
    public static final cljl.Var COMPILE_FILES = cljl.RT.COMPILE_FILES;
    public static final cljl.Var COMPILE_PATH = cljl.RT.COMPILE_PATH;
    public static final cljl.Var COMPILER_OPTIONS = cljl.RT.COMPILER_OPTIONS;
    public static final cljl.lang.PersistentHashMap CHAR_MAP = cljl.Compiler.CHAR_MAP;
    public static final cljl.lang.PersistentHashMap specials = cljl.Compiler.specials;

    public static boolean isSpecial(Object sym) {
        return sym instanceof cljl.Symbol s && cljl.Compiler.isSpecial(s);
    }

    public static Object macroexpand1(Object form) {
        return cljl.Compiler.macroexpand1(form);
    }

    public static Object macroexpand(Object form) {
        return cljl.Compiler.macroexpand(form);
    }

    public static class CompilerException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String source;
        public final int line;

        public CompilerException(String source, int line, Throwable cause) {
            super("Syntax error compiling at (" + source + ":" + line + ")", cause);
            this.source = source;
            this.line = line;
        }

        public CompilerException(String message, Throwable cause) {
            super(message, cause);
            this.source = null;
            this.line = -1;
        }
    }

    private Compiler() {}

    public static Object eval(Object form) {
        cljl.RT.ensureInit();
        return cljl.Compiler.eval(form);
    }

    public static Object load(Reader r) {
        return load(r, null, "NO_SOURCE_FILE");
    }

    public static Object load(Reader r, String sourcePath, String sourceName) {
        cljl.RT.ensureInit();
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return cljl.Compiler.load(sb.toString(), sourcePath != null ? sourcePath : sourceName);
        } catch (IOException e) {
            throw cljl.RT.sneakyThrow(e);
        }
    }

    public static Object loadFile(String fileName) throws IOException {
        cljl.RT.ensureInit();
        String src = Files.readString(Path.of(fileName));
        return cljl.Compiler.load(src, fileName);
    }

    public static String munge(String name) {
        return cljl.Compiler.munge(name);
    }

    public static String demunge(String mungedName) {
        return cljl.Compiler.demunge(mungedName);
    }
}
