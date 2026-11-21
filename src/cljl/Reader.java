package cljl;

import cljl.lang.*;
import java.io.IOException;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full-featured Clojure Reader supporting lists, vectors, maps, sets, metadata,
 * syntax-quote (`), unquote (~), unquote-splicing (~@), auto-gensyms (x#),
 * anonymous fn literals #(...), regexes #"", discard #_, var-quote #',
 * deref @, and reader conditionals #?() / #?@().
 */
public final class Reader {

    private static final Object EOF = new Object();
    private static final Object DISCARD = new Object();

    private static final Symbol QUOTE = Symbol.intern("quote");
    private static final Symbol VAR = Symbol.intern("var");
    private static final Symbol DEREF = Symbol.intern("clojure.core", "deref");
    private static final Symbol FN = Symbol.intern("fn*");
    private static final Symbol UNQUOTE = Symbol.intern("clojure.core", "unquote");
    private static final Symbol UNQUOTE_SPLICING = Symbol.intern("clojure.core", "unquote-splicing");
    private static final Symbol SEQ = Symbol.intern("clojure.core", "seq");
    private static final Symbol CONCAT = Symbol.intern("clojure.core", "concat");
    private static final Symbol LIST = Symbol.intern("clojure.core", "list");
    private static final Symbol APPLY = Symbol.intern("clojure.core", "apply");
    private static final Symbol VECTOR = Symbol.intern("clojure.core", "vector");
    private static final Symbol HASH_MAP = Symbol.intern("clojure.core", "hash-map");
    private static final Symbol HASH_SET = Symbol.intern("clojure.core", "hash-set");

    private static final class NumberPatterns {
        static final Pattern INT_PAT = Pattern.compile(
                "^([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[bB]([01]+))(N)?$");
        static final Pattern RATIO_PAT = Pattern.compile("^([-+]?[0-9]+)/([0-9]+)$");
        static final Pattern FLOAT_PAT = Pattern.compile(
                "^([-+]?[0-9]+(?:\\.[0-9]*(?:[eE][-+]?[0-9]+)?|[eE][-+]?[0-9]+))(M)?$|^([-+]?[0-9]+)(M)$");
    }

    private final CharSource source;
    private int line = 1;
    private int col = 1;
    private final String filename;
    private final Set<Object> features;
    private final boolean readCondAllowed;
    private final boolean readCondPreserve;
    private final boolean ednMode;
    private final Map<?, ?> customReaders;
    private final Fn defaultReaderFn;
    private StringBuilder captureBuf = null;

    // Context for #(...) anonymous function reader
    private Deque<FnEnv> fnEnvStack;
    // Context for syntax-quote auto-gensym (foo#)
    private Deque<Map<Symbol, Symbol>> gensymEnvStack;

    private interface CharSource {
        int peek();
        int read();
        default void finish() {}
    }

    private static final class StringSource implements CharSource {
        private final String src;
        private final int len;
        private int pos;

        StringSource(String src) {
            this.src = src != null ? src : "";
            this.len = this.src.length();
        }

        @Override
        public int peek() {
            return pos < len ? src.charAt(pos) : -1;
        }

        @Override
        public int read() {
            return pos < len ? src.charAt(pos++) : -1;
        }
    }

    private static final Map<java.io.Reader, Integer> PUSHBACK_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class StreamSource implements CharSource {
        private final java.io.Reader reader;
        private boolean hasLookahead = false;
        private int lookahead = -1;

        StreamSource(java.io.Reader reader) {
            this.reader = reader;
        }

        @Override
        public int peek() {
            if (!hasLookahead) {
                try {
                    Integer cached = PUSHBACK_CACHE.remove(reader);
                    if (cached != null) {
                        lookahead = cached;
                    } else {
                        if (reader.markSupported()) {
                            reader.mark(1);
                        }
                        lookahead = reader.read();
                    }
                    hasLookahead = true;
                } catch (IOException e) {
                    throw RT.sneakyThrow(e);
                }
            }
            return lookahead;
        }

        @Override
        public int read() {
            if (hasLookahead) {
                hasLookahead = false;
                return lookahead;
            }
            try {
                Integer cached = PUSHBACK_CACHE.remove(reader);
                if (cached != null) {
                    return cached;
                }
                return reader.read();
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }

        @Override
        public void finish() {
            if (!hasLookahead) return;
            int c = lookahead;
            hasLookahead = false;
            if (c == -1) return;
            try {
                if (reader instanceof PushbackReader pbr) {
                    pbr.unread(c);
                } else if (reader.markSupported()) {
                    reader.reset();
                } else {
                    PUSHBACK_CACHE.put(reader, c);
                }
            } catch (IOException e) {
                PUSHBACK_CACHE.put(reader, c);
            }
        }
    }

    private static final class FnEnv {
        int maxArg = 0;
        boolean hasRest = false;
        final Map<Integer, Symbol> args = new HashMap<>();
        Symbol restSym = null;

        Symbol getArg(int n) {
            if (n > maxArg) maxArg = n;
            return args.computeIfAbsent(n, k -> Symbol.intern("p__" + k + "__" + RT.nextID()));
        }

        Symbol getRest() {
            hasRest = true;
            if (restSym == null) restSym = Symbol.intern("rest__" + RT.nextID());
            return restSym;
        }
    }

    private record SpliceResult(List<Object> items) {}

    public Reader(String src, String filename) {
        this(new StringSource(src), filename, null);
    }

    public Reader(java.io.Reader reader, String filename, Map<?, ?> opts) {
        this(new StreamSource(reader), filename, opts);
    }

    private Reader(CharSource source, String filename, Map<?, ?> opts) {
        this(source, filename, opts, false);
    }

    @SuppressWarnings("unchecked")
    private Reader(CharSource source, String filename, Map<?, ?> opts, boolean ednMode) {
        this.source = source;
        this.filename = filename != null ? filename : "<stdin>";
        this.ednMode = ednMode;
        if (opts != null) {
            Object rc = opts.get(Keyword.intern("read-cond"));
            this.readCondPreserve = !ednMode && Keyword.intern("preserve").equals(rc);
            this.readCondAllowed = !ednMode && (rc == null || Keyword.intern("allow").equals(rc) || this.readCondPreserve);
            Object feats = opts.get(Keyword.intern("features"));
            this.features = (feats instanceof Set<?> set) ? (Set<Object>) set : null;
            Object readersObj = opts.get(Keyword.intern("readers"));
            this.customReaders = (readersObj instanceof Map<?, ?> m) ? m : null;
            Object defObj = opts.get(Keyword.intern("default"));
            this.defaultReaderFn = (defObj instanceof Fn f) ? f : null;
        } else {
            this.readCondAllowed = !ednMode;
            this.readCondPreserve = false;
            this.features = null;
            this.customReaders = null;
            this.defaultReaderFn = null;
        }
    }

    public static Object readEdnString(Map<?, ?> opts, String s) {
        boolean eofIsError = isEofError(opts, false);
        Object eofValue = extractEofValue(opts, eofIsError, null);
        if (s == null) {
            if (eofIsError) throw new RuntimeException("EOF while reading");
            return eofValue;
        }
        Reader r = new Reader(new StringSource(s), "<edn>", opts, true);
        return r.readForm(eofIsError, eofValue);
    }

    public static Object readEdnFromStream(java.io.Reader stream, boolean eofIsError, Object eofValue, Map<?, ?> opts) {
        if (stream == null) {
            if (eofIsError) throw new RuntimeException("EOF while reading");
            return eofValue;
        }
        Reader r = new Reader(new StreamSource(stream), "<edn>", opts, true);
        try {
            return r.readForm(eofIsError, eofValue);
        } finally {
            r.source.finish();
        }
    }

    public static Object readEdnFromStream(Map<?, ?> opts, java.io.Reader stream) {
        boolean eofIsError = isEofError(opts, true);
        Object eofValue = extractEofValue(opts, eofIsError, null);
        return readEdnFromStream(stream, eofIsError, eofValue, opts);
    }

    public static Object readString(String s) {
        if (s == null) return null;
        Reader r = new Reader(s, "<string>");
        return r.readForm(true, null);
    }

    public static Object readString(String s, boolean eofIsError, Object eofValue) {
        if (s == null) {
            if (eofIsError) throw new RuntimeException("EOF while reading");
            return eofValue;
        }
        Reader r = new Reader(s, "<string>");
        return r.readForm(eofIsError, eofValue);
    }

    public static Object readString(Map<?, ?> opts, String s) {
        if (s == null) return extractEofValue(opts, true, null);
        boolean eofIsError = isEofError(opts, true);
        Object eofValue = extractEofValue(opts, eofIsError, null);
        Reader r = new Reader(new StringSource(s), "<string>", opts);
        return r.readForm(eofIsError, eofValue);
    }

    public static Object readFromStream(java.io.Reader stream, boolean eofIsError, Object eofValue, Map<?, ?> opts) {
        if (stream == null) {
            if (eofIsError) throw new RuntimeException("EOF while reading");
            return eofValue;
        }
        Reader r = new Reader(stream, "<stream>", opts);
        try {
            return r.readForm(eofIsError, eofValue);
        } finally {
            r.source.finish();
        }
    }

    public static Object readFromStream(Map<?, ?> opts, java.io.Reader stream) {
        boolean eofIsError = isEofError(opts, true);
        Object eofValue = extractEofValue(opts, eofIsError, null);
        return readFromStream(stream, eofIsError, eofValue, opts);
    }

    public static PersistentVector readFromStreamWithString(java.io.Reader stream, boolean eofIsError, Object eofValue, Map<?, ?> opts) {
        if (stream == null) {
            if (eofIsError) throw new RuntimeException("EOF while reading");
            return PersistentVector.createOwning(eofValue, "");
        }
        Reader r = new Reader(stream, "<stream>", opts);
        try {
            return r.readFormWithString(eofIsError, eofValue);
        } finally {
            r.source.finish();
        }
    }

    public static PersistentVector readFromStreamWithString(Map<?, ?> opts, java.io.Reader stream) {
        boolean eofIsError = isEofError(opts, true);
        Object eofValue = extractEofValue(opts, eofIsError, null);
        return readFromStreamWithString(stream, eofIsError, eofValue, opts);
    }

    public PersistentVector readFormWithString(boolean eofIsError, Object eofValue) {
        skipWhitespaceAndComments();
        if (peek() == -1) {
            if (eofIsError) throw readerError("EOF while reading");
            return PersistentVector.createOwning(eofValue, "");
        }
        StringBuilder cap = new StringBuilder();
        this.captureBuf = cap;
        try {
            Object form = readForm(eofIsError, eofValue);
            return PersistentVector.createOwning(form, cap.toString().trim());
        } finally {
            this.captureBuf = null;
        }
    }

    private static boolean isEofError(Map<?, ?> opts, boolean defaultErr) {
        if (opts == null) return defaultErr;
        Keyword eofKw = Keyword.intern("eof");
        if (!opts.containsKey(eofKw)) return defaultErr;
        return Keyword.intern("eofthrow").equals(opts.get(eofKw));
    }

    private static Object extractEofValue(Map<?, ?> opts, boolean eofIsError, Object defaultVal) {
        if (eofIsError || opts == null) return defaultVal;
        return opts.get(Keyword.intern("eof"));
    }

    public void consumeForms(java.util.function.Consumer<Object> consumer) {
        try {
            while (true) {
                Object form = readForm(false, EOF);
                if (form == EOF) break;
                if (form instanceof SpliceResult sr) {
                    for (Object item : sr.items) {
                        consumer.accept(item);
                    }
                } else {
                    consumer.accept(form);
                }
            }
        } finally {
            source.finish();
        }
    }

    public static List<Object> readAll(String s, String filename) {
        List<Object> forms = new ArrayList<>();
        new Reader(s, filename).consumeForms(forms::add);
        return forms;
    }

    private int peek() {
        return source.peek();
    }

    private int readChar() {
        int c = source.read();
        if (c == -1) return -1;
        if (captureBuf != null) captureBuf.append((char) c);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',' || c == '\f';
    }

    private static boolean isTerminatingMacro(int c) {
        return c == '"' || c == ';' || c == '@' || c == '^' || c == '`' || c == '~'
                || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '\\';
    }

    private void skipWhitespaceAndComments() {
        while (true) {
            int c = peek();
            if (c == -1) break;
            if (isWhitespace(c)) {
                readChar();
            } else if (c == ';') {
                int ch = readChar();
                while (ch != -1 && ch != '\n') ch = readChar();
            } else {
                break;
            }
        }
    }

    public Object readForm(boolean eofIsError, Object eofValue) {
        while (true) {
            Object v = readNext(eofIsError, eofValue);
            if (v != DISCARD) return v;
        }
    }

    private Object readNext(boolean eofIsError, Object eofValue) {
        skipWhitespaceAndComments();
        if (peek() == -1) {
            if (eofIsError) throw readerError("EOF while reading");
            return eofValue;
        }
        int startLine = line;
        int c = readChar();
        return switch (c) {
            case '(' -> readList(startLine);
            case '[' -> readVector();
            case '{' -> readMap();
            case ')', ']', '}' -> throw readerError("Unmatched delimiter: " + (char) c);
            case '"' -> readStringLiteral();
            case '\\' -> readCharacter();
            case '\'' -> {
                if (ednMode) throw readerError("Invalid EDN reader macro: '");
                Object form = readForm(true, null);
                yield PersistentList.createFromArray(QUOTE, form);
            }
            case '@' -> {
                if (ednMode) throw readerError("Invalid EDN reader macro: @");
                Object form = readForm(true, null);
                yield PersistentList.createFromArray(DEREF, form);
            }
            case '`' -> {
                if (ednMode) throw readerError("Invalid EDN reader macro: `");
                yield readSyntaxQuote();
            }
            case '~' -> {
                if (ednMode) throw readerError("Invalid EDN reader macro: ~");
                if (peek() == '@') {
                    readChar();
                    Object form = readForm(true, null);
                    yield PersistentList.createFromArray(UNQUOTE_SPLICING, form);
                } else {
                    Object form = readForm(true, null);
                    yield PersistentList.createFromArray(UNQUOTE, form);
                }
            }
            case '^' -> {
                if (ednMode) throw readerError("Invalid EDN reader macro: ^");
                yield readMeta();
            }
            case '#' -> readDispatch();
            default -> {
                if (c >= '0' && c <= '9') {
                    yield readNumber((char) c);
                }
                if ((c == '+' || c == '-') && peek() >= '0' && peek() <= '9') {
                    yield readNumber((char) c);
                }
                yield readToken((char) c);
            }
        };
    }

    private Object readList(int startLine) {
        List<Object> items = readDelimited(')');
        ISeq list = PersistentList.create(items);
        if (list instanceof PersistentList pl) {
            PersistentHashMap meta = PersistentHashMap.EMPTY.assoc(Keyword.intern("line"), (long) startLine);
            return pl.withMeta(meta);
        }
        return list;
    }

    private Object readVector() {
        List<Object> items = readDelimited(']');
        return PersistentVector.create(items);
    }

    private Object readMap() {
        List<Object> items = readDelimited('}');
        if ((items.size() & 1) != 0) {
            throw readerError("Map literal must contain an even number of forms");
        }
        return PersistentHashMap.create(items.toArray());
    }

    private Object readSet() {
        List<Object> items = readDelimited('}');
        return PersistentHashSet.createFromColl(items);
    }

    private List<Object> readDelimited(char endChar) {
        List<Object> list = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (peek() == -1) {
                throw readerError("EOF while reading, expected '" + endChar + "'");
            }
            if (peek() == endChar) {
                readChar();
                return list;
            }
            Object item = readNext(true, null);
            if (item == DISCARD) continue;
            if (item instanceof SpliceResult sr) {
                list.addAll(sr.items);
            } else {
                list.add(item);
            }
        }
    }

    private String readStringLiteral() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = readChar();
            if (c == -1) throw readerError("EOF while reading string");
            if (c == '"') return sb.toString();
            if (c == '\\') {
                int esc = readChar();
                if (esc == -1) throw readerError("EOF while reading string escape");
                switch (esc) {
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'n' -> sb.append('\n');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        StringBuilder hex = new StringBuilder(4);
                        for (int i = 0; i < 4; i++) {
                            int hc = readChar();
                            if (hc == -1) throw readerError("Invalid unicode escape");
                            hex.append((char) hc);
                        }
                        sb.append((char) Integer.parseInt(hex.toString(), 16));
                    }
                    default -> {
                        if (esc >= '0' && esc <= '7') {
                            StringBuilder oct = new StringBuilder();
                            oct.append((char) esc);
                            for (int i = 0; i < 2 && peek() >= '0' && peek() <= '7'; i++) {
                                oct.append((char) readChar());
                            }
                            sb.append((char) Integer.parseInt(oct.toString(), 8));
                        } else {
                            throw readerError("Unsupported escape character: \\" + (char) esc);
                        }
                    }
                }
            } else {
                sb.append((char) c);
            }
        }
    }

    private Character readCharacter() {
        int c = readChar();
        if (c == -1) throw readerError("EOF while reading character");
        StringBuilder sb = new StringBuilder();
        sb.append((char) c);
        while (true) {
            int p = peek();
            if (p == -1 || isWhitespace(p) || isTerminatingMacro(p)) break;
            sb.append((char) readChar());
        }
        String token = sb.toString();
        if (token.length() == 1) return token.charAt(0);
        return switch (token) {
            case "newline" -> '\n';
            case "space" -> ' ';
            case "tab" -> '\t';
            case "backspace" -> '\b';
            case "formfeed" -> '\f';
            case "return" -> '\r';
            default -> {
                if (token.startsWith("u") && token.length() == 5) {
                    yield (char) Integer.parseInt(token.substring(1), 16);
                }
                if (token.startsWith("o") && token.length() <= 4) {
                    yield (char) Integer.parseInt(token.substring(1), 8);
                }
                throw readerError("Unsupported character: \\" + token);
            }
        };
    }

    private Object readMeta() {
        Object metaVal = readForm(true, null);
        PersistentHashMap metaMap;
        if (metaVal instanceof Symbol || metaVal instanceof String) {
            metaMap = PersistentHashMap.EMPTY.assoc(Keyword.intern("tag"), metaVal);
        } else if (metaVal instanceof Keyword kw) {
            metaMap = PersistentHashMap.EMPTY.assoc(kw, Boolean.TRUE);
        } else if (metaVal instanceof PersistentHashMap m) {
            metaMap = m;
        } else if (metaVal instanceof PersistentVector v) {
            metaMap = PersistentHashMap.EMPTY.assoc(Keyword.intern("param-tags"), v);
        } else {
            throw readerError("Metadata must be Symbol, Keyword, String, Vector or Map");
        }
        Object target = readForm(true, null);
        if (target instanceof IObj io) {
            PersistentHashMap existing = io.meta();
            if (existing != null) {
                for (ISeq s = metaMap.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                    existing = existing.assoc(me.key, me.val);
                }
                return io.withMeta(existing);
            }
            return io.withMeta(metaMap);
        }
        return target;
    }

    private Object readDispatch() {
        int c = readChar();
        if (c == -1) throw readerError("EOF while reading dispatch macro");
        return switch (c) {
            case '{' -> readSet();
            case '_' -> {
                readForm(true, null);
                yield DISCARD;
            }
            case '\'' -> {
                if (ednMode) throw readerError("No EDN dispatch macro for: '");
                Object sym = readForm(true, null);
                yield PersistentList.createFromArray(VAR, sym);
            }
            case '"' -> {
                if (ednMode) throw readerError("No EDN dispatch macro for: \"");
                yield readRegex();
            }
            case '(' -> {
                if (ednMode) throw readerError("No EDN dispatch macro for: (");
                yield readAnonFn();
            }
            case '!' -> {
                if (ednMode) throw readerError("No EDN dispatch macro for: !");
                int ch = readChar();
                while (ch != -1 && ch != '\n') ch = readChar();
                yield DISCARD;
            }
            case '?' -> {
                if (ednMode) throw readerError("No EDN dispatch macro for: ?");
                yield readConditional();
            }
            case ':' -> readNamespacedMap();
            case '#' -> readSymbolicValue();
            default -> {
                if (Character.isLetter(c) || c == '_' || c == '*' || c == '+' || c == '!' || c == '-' || c == '?') {
                    yield readTaggedLiteral((char) c);
                }
                throw readerError("No dispatch macro for: " + (char) c);
            }
        };
    }

    private Object readTaggedLiteral(char firstChar) {
        Object tagObj = readToken(firstChar);
        if (!(tagObj instanceof Symbol tag)) {
            throw readerError("Reader tag must be a symbol");
        }
        Object form = readForm(true, null);
        if (ednMode) {
            Object readerFn = (customReaders != null) ? customReaders.get(tag) : null;
            if (readerFn == null) {
                readerFn = RT.DEFAULT_DATA_READERS_MAP.valAt(tag);
            }
            if (readerFn instanceof Fn f) {
                return f.invoke(form);
            }
            if (defaultReaderFn != null) {
                return defaultReaderFn.invoke(tag, form);
            }
            throw readerError("No reader function for tag " + tag);
        }
        Object readersObj = RT.DATA_READERS.get();
        Object readerFn = (readersObj != null) ? RT.get(readersObj, tag) : null;
        if (readerFn == null) {
            readerFn = RT.DEFAULT_DATA_READERS_MAP.valAt(tag);
        }
        if (readerFn == null) {
            Object cpReaderSym = RT.getClasspathDataReaders().valAt(tag);
            if (cpReaderSym instanceof Symbol fnSym && fnSym.ns != null) {
                Core.loadLib(fnSym.ns);
                Var v = Compiler.lookupVar(RT.currentNS(), fnSym, false);
                if (v != null && v.isBound()) readerFn = v.deref();
            }
        }
        if (readerFn instanceof Fn f) {
            return f.invoke(form);
        }
        Object defFn = RT.DEFAULT_DATA_READER_FN.get();
        if (defFn instanceof Fn f) {
            return f.invoke(tag, form);
        }
        throw readerError("No reader function for tag " + tag);
    }

    private Pattern readRegex() {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = readChar();
            if (c == -1) throw readerError("EOF while reading regex");
            if (c == '"') return Pattern.compile(sb.toString());
            if (c == '\\') {
                int next = readChar();
                if (next == -1) throw readerError("EOF while reading regex");
                if (next != '"' && next != '\\') {
                    sb.append('\\');
                } else if (next == '\\') {
                    sb.append('\\');
                }
                sb.append((char) next);
            } else {
                sb.append((char) c);
            }
        }
    }

    private Object readAnonFn() {
        if (fnEnvStack != null && !fnEnvStack.isEmpty()) {
            throw readerError("Nested #()s are not allowed");
        }
        if (fnEnvStack == null) fnEnvStack = new ArrayDeque<>();
        FnEnv env = new FnEnv();
        fnEnvStack.push(env);
        try {
            List<Object> bodyItems = readDelimited(')');
            ISeq bodyForm = PersistentList.create(bodyItems);
            List<Object> params = new ArrayList<>();
            for (int i = 1; i <= env.maxArg; i++) {
                params.add(env.getArg(i));
            }
            if (env.hasRest) {
                params.add(Symbol.intern("&"));
                params.add(env.getRest());
            }
            PersistentVector paramVec = PersistentVector.create(params);
            return PersistentList.createFromArray(FN, paramVec, bodyForm);
        } finally {
            fnEnvStack.pop();
        }
    }

    private Object readConditional() {
        boolean splicing = false;
        if (peek() == '@') {
            readChar();
            splicing = true;
        }
        skipWhitespaceAndComments();
        if (readChar() != '(') {
            throw readerError("Reader conditional body must be a list");
        }
        List<Object> pairs = readDelimited(')');
        if ((pairs.size() & 1) != 0) {
            throw readerError("Reader conditional requires an even number of forms");
        }
        if (!readCondAllowed) {
            throw readerError("Conditional read not allowed");
        }
        if (readCondPreserve) {
            return ReaderConditional.create(PersistentList.create(pairs), splicing);
        }
        Keyword cljlKw = Keyword.intern("cljl");
        Keyword cljKw = Keyword.intern("clj");
        Keyword defKw = Keyword.intern("default");
        for (int i = 0; i < pairs.size(); i += 2) {
            Object feat = pairs.get(i);
            Object expr = pairs.get(i + 1);
            boolean match = (features != null)
                    ? (features.contains(feat) || feat == defKw)
                    : (feat == cljlKw || feat == cljKw || feat == defKw);
            if (match) {
                if (splicing) {
                    List<Object> items = new ArrayList<>();
                    for (ISeq s = RT.seq(expr); s != null; s = s.next()) {
                        items.add(s.first());
                    }
                    return new SpliceResult(items);
                }
                return expr;
            }
        }
        return DISCARD;
    }

    private Object readSymbolicValue() {
        Object sym = readForm(true, null);
        if (sym instanceof Symbol s && s.ns == null) {
            return switch (s.name) {
                case "Inf" -> Double.POSITIVE_INFINITY;
                case "-Inf" -> Double.NEGATIVE_INFINITY;
                case "NaN" -> Double.NaN;
                default -> throw readerError("Unknown symbolic value: ##" + s);
            };
        }
        throw readerError("Invalid symbolic value: ##" + sym);
    }

    private Object readNamespacedMap() {
        String nsStr;
        if (peek() == ':') {
            readChar();
            if (peek() == '{') {
                nsStr = RT.currentNS().name.name;
            } else {
                String aliasName = readRawToken((char) readChar());
                Namespace aliased = RT.currentNS().lookupAlias(Symbol.intern(aliasName));
                nsStr = aliased != null ? aliased.name.name : aliasName;
            }
        } else {
            nsStr = readRawToken((char) readChar());
        }
        skipWhitespaceAndComments();
        if (readChar() != '{') throw readerError("Expected '{' in namespaced map literal");
        List<Object> items = readDelimited('}');
        if ((items.size() & 1) != 0) throw readerError("Namespaced map must contain an even number of forms");
        Object[] out = new Object[items.size()];
        for (int i = 0; i < items.size(); i += 2) {
            Object k = items.get(i);
            Object v = items.get(i + 1);
            if (k instanceof Keyword kw && kw.getNamespace() == null) {
                k = Keyword.intern(nsStr, kw.getName());
            } else if (k instanceof Symbol sym && sym.getNamespace() == null) {
                k = Symbol.intern(nsStr, sym.getName());
            }
            out[i] = k;
            out[i + 1] = v;
        }
        return PersistentHashMap.create(out);
    }

    private Object readSyntaxQuote() {
        if (gensymEnvStack == null) gensymEnvStack = new ArrayDeque<>();
        gensymEnvStack.push(new HashMap<>());
        try {
            Object form = readForm(true, null);
            return syntaxQuote(form);
        } finally {
            gensymEnvStack.pop();
        }
    }

    private Object syntaxQuote(Object form) {
        if (form == null || form instanceof Boolean || form instanceof Number
                || form instanceof Character || form instanceof String || form instanceof Keyword
                || form instanceof Pattern) {
            return form;
        }
        if (isUnquote(form)) {
            return RT.second(form);
        }
        if (isUnquoteSplicing(form)) {
            throw readerError("unquote-splicing (~@) not in list");
        }
        if (form instanceof Symbol sym) {
            Symbol resolved = resolveSyntaxQuoteSymbol(sym);
            return PersistentList.createFromArray(QUOTE, resolved);
        }
        if (form instanceof PersistentVector pv) {
            return PersistentList.createFromArray(APPLY, VECTOR,
                    PersistentList.createFromArray(SEQ, RT.cons(CONCAT, sqExpandList(pv.seq()))));
        }
        if (form instanceof PersistentHashMap pm) {
            List<Object> flat = new ArrayList<>();
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                flat.add(me.key);
                flat.add(me.val);
            }
            return PersistentList.createFromArray(APPLY, HASH_MAP,
                    PersistentList.createFromArray(SEQ, RT.cons(CONCAT, sqExpandList(PersistentList.create(flat)))));
        }
        if (form instanceof PersistentHashSet ps) {
            return PersistentList.createFromArray(APPLY, HASH_SET,
                    PersistentList.createFromArray(SEQ, RT.cons(CONCAT, sqExpandList(ps.seq()))));
        }
        if (form instanceof ISeq || form instanceof List<?>) {
            ISeq seq = RT.seq(form);
            if (seq == null) {
                return PersistentList.createFromArray(LIST);
            }
            return PersistentList.createFromArray(SEQ, RT.cons(CONCAT, sqExpandList(seq)));
        }
        return PersistentList.createFromArray(QUOTE, form);
    }

    private ISeq sqExpandList(ISeq seq) {
        List<Object> parts = new ArrayList<>();
        for (; seq != null; seq = seq.next()) {
            Object item = seq.first();
            if (isUnquote(item)) {
                parts.add(PersistentList.createFromArray(LIST, RT.second(item)));
            } else if (isUnquoteSplicing(item)) {
                parts.add(RT.second(item));
            } else {
                parts.add(PersistentList.createFromArray(LIST, syntaxQuote(item)));
            }
        }
        return PersistentList.create(parts);
    }

    private Symbol resolveSyntaxQuoteSymbol(Symbol sym) {
        if (sym.ns == null && sym.name.endsWith("#")) {
            Map<Symbol, Symbol> gmap = gensymEnvStack.peek();
            Symbol gs = gmap.get(sym);
            if (gs == null) {
                String base = sym.name.substring(0, sym.name.length() - 1);
                gs = Symbol.intern(null, base + "__" + RT.nextID() + "__auto__");
                gmap.put(sym, gs);
            }
            return gs;
        }
        if (Compiler.isSpecial(sym)) {
            return sym;
        }
        if (sym.ns == null && (sym.name.startsWith(".") || sym.name.endsWith(".") || sym.name.equals("&"))) {
            return sym;
        }
        Namespace cur = RT.currentNS();
        if (sym.ns != null) {
            if (Interop.isArrayDimToken(sym.name)) {
                Class<?> comp = Interop.resolveClass(cur, sym.ns);
                if (comp != null) {
                    return Symbol.intern(comp.getName(), sym.name);
                }
            }
            Namespace aliasNs = cur.lookupAlias(Symbol.intern(sym.ns));
            if (aliasNs != null) {
                return Symbol.intern(aliasNs.name.name, sym.name);
            }
            return sym;
        }
        Object mapped = cur.getMapping(sym);
        if (mapped instanceof Var v) {
            return Symbol.intern(v.ns.name.name, v.sym.name);
        }
        if (mapped instanceof Class<?> c) {
            return Symbol.intern(null, c.getName());
        }
        return Symbol.intern(cur.name.name, sym.name);
    }

    private static boolean isUnquote(Object form) {
        return form instanceof ISeq s && UNQUOTE.equals(s.first());
    }

    private static boolean isUnquoteSplicing(Object form) {
        return form instanceof ISeq s && UNQUOTE_SPLICING.equals(s.first());
    }

    private Object readNumber(char initChar) {
        StringBuilder sb = new StringBuilder();
        sb.append(initChar);
        while (true) {
            int c = peek();
            if (c == -1 || isWhitespace(c) || isTerminatingMacro(c)) break;
            sb.append((char) readChar());
        }
        String s = sb.toString();
        Number n = matchNumber(s);
        if (n == null) throw readerError("Invalid number: " + s);
        return n;
    }

    private static Number matchNumber(String s) {
        // Fast zero-regex path for ordinary decimal integers (avoids loading java.util.regex.Pattern at startup)
        int len = s.length();
        if (len > 0) {
            int i = 0;
            boolean neg = false;
            char c0 = s.charAt(0);
            if (c0 == '-' || c0 == '+') {
                neg = (c0 == '-');
                i = 1;
            }
            int digits = len - i;
            if (digits == 1) {
                char d = s.charAt(i);
                if (d >= '0' && d <= '9') {
                    long v = d - '0';
                    return Long.valueOf(neg ? -v : v);
                }
            } else if (digits > 1 && digits <= 18 && s.charAt(i) >= '1' && s.charAt(i) <= '9') {
                long acc = 0L;
                boolean allDigits = true;
                for (int j = i; j < len; j++) {
                    char ch = s.charAt(j);
                    if (ch < '0' || ch > '9') {
                        allDigits = false;
                        break;
                    }
                    acc = acc * 10L + (ch - '0');
                }
                if (allDigits) {
                    return Long.valueOf(neg ? -acc : acc);
                }
            }
        }
        Matcher m = NumberPatterns.INT_PAT.matcher(s);
        if (m.matches()) {
            if (m.group(2) != null) {
                if (m.group(9) != null) return BigInteger.ZERO;
                return 0L;
            }
            boolean negate = "-".equals(m.group(1));
            String n;
            int radix = 10;
            if ((n = m.group(3)) != null) radix = 10;
            else if ((n = m.group(4)) != null) radix = 16;
            else if ((n = m.group(5)) != null) radix = 8;
            else if ((n = m.group(7)) != null) {
                radix = Integer.parseInt(m.group(6));
            } else if ((n = m.group(8)) != null) radix = 2;
            if (n == null) return null;
            BigInteger bn = new BigInteger(n, radix);
            if (negate) bn = bn.negate();
            if (m.group(9) != null) return bn;
            return bn.bitLength() <= 63 ? bn.longValue() : bn;
        }
        m = NumberPatterns.FLOAT_PAT.matcher(s);
        if (m.matches()) {
            if (m.group(3) != null) {
                return new BigDecimal(m.group(3));
            }
            if (m.group(2) != null) {
                return new BigDecimal(m.group(1));
            }
            return Double.parseDouble(m.group(1));
        }
        m = NumberPatterns.RATIO_PAT.matcher(s);
        if (m.matches()) {
            String numerator = m.group(1);
            if (numerator.startsWith("+")) numerator = numerator.substring(1);
            return Ratio.create(new BigInteger(numerator), new BigInteger(m.group(2)));
        }
        return null;
    }

    private String readRawToken(char initChar) {
        StringBuilder sb = new StringBuilder();
        sb.append(initChar);
        while (true) {
            int c = peek();
            if (c == -1 || isWhitespace(c) || isTerminatingMacro(c)) break;
            sb.append((char) readChar());
        }
        return sb.toString();
    }

    private Object readToken(char initChar) {
        String s = readRawToken(initChar);
        if ("nil".equals(s)) return null;
        if ("true".equals(s)) return Boolean.TRUE;
        if ("false".equals(s)) return Boolean.FALSE;

        if (s.startsWith(":")) {
            if (s.startsWith("::")) {
                String k = s.substring(2);
                int slash = k.indexOf('/');
                if (slash == -1) {
                    return Keyword.intern(RT.currentNS().name.name, k);
                } else {
                    String alias = k.substring(0, slash);
                    String name = k.substring(slash + 1);
                    Namespace aliasedNs = RT.currentNS().lookupAlias(Symbol.intern(alias));
                    String targetNs = aliasedNs != null ? aliasedNs.name.name : alias;
                    return Keyword.intern(targetNs, name);
                }
            }
            return Keyword.intern(s.substring(1));
        }

        if (fnEnvStack != null && !fnEnvStack.isEmpty() && s.startsWith("%")) {
            FnEnv env = fnEnvStack.peek();
            if ("%".equals(s) || "%1".equals(s)) return env.getArg(1);
            if ("%&".equals(s)) return env.getRest();
            try {
                int idx = Integer.parseInt(s.substring(1));
                if (idx >= 1) return env.getArg(idx);
            } catch (NumberFormatException ignored) {}
        }

        return Symbol.intern(s);
    }

    public static final class StringReaderFn extends Fn.NamedFn {
        public StringReaderFn() {
            super("LispReader$StringReader");
        }

        @Override
        public Object invoke(Object rdrObj, Object doublequote) {
            return invoke(rdrObj, doublequote, null);
        }

        @Override
        public Object invoke(Object rdrObj, Object doublequote, Object opts, Object pendingForms) {
            return invoke(rdrObj, doublequote, opts);
        }

        @Override
        public Object invoke(Object rdrObj, Object doublequote, Object opts) {
            java.io.Reader r = (java.io.Reader) rdrObj;
            StringBuilder sb = new StringBuilder();
            try {
                while (true) {
                    int c = r.read();
                    if (c == -1) throw new RuntimeException("EOF while reading string");
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        int esc = r.read();
                        if (esc == -1) throw new RuntimeException("EOF while reading string escape");
                        switch (esc) {
                            case 't' -> sb.append('\t');
                            case 'r' -> sb.append('\r');
                            case 'n' -> sb.append('\n');
                            case '\\' -> sb.append('\\');
                            case '"' -> sb.append('"');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                StringBuilder hex = new StringBuilder(4);
                                for (int i = 0; i < 4; i++) {
                                    int hc = r.read();
                                    if (hc == -1) throw new RuntimeException("Invalid unicode escape");
                                    hex.append((char) hc);
                                }
                                sb.append((char) Integer.parseInt(hex.toString(), 16));
                            }
                            default -> {
                                if (esc >= '0' && esc <= '7') {
                                    StringBuilder oct = new StringBuilder();
                                    oct.append((char) esc);
                                    if (r instanceof java.io.PushbackReader pbr) {
                                        for (int i = 0; i < 2; i++) {
                                            int next = pbr.read();
                                            if (next >= '0' && next <= '7') oct.append((char) next);
                                            else { if (next != -1) pbr.unread(next); break; }
                                        }
                                    }
                                    sb.append((char) Integer.parseInt(oct.toString(), 8));
                                } else {
                                    throw new RuntimeException("Unsupported escape character: \\" + (char) esc);
                                }
                            }
                        }
                    } else {
                        sb.append((char) c);
                    }
                }
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }
    }

    private RuntimeException readerError(String msg) {
        return new RuntimeException(msg + " [" + filename + ":" + line + ":" + col + "]");
    }
}
