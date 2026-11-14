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

    private static final Pattern INT_PAT = Pattern.compile(
            "^([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[bB]([01]+))(N)?$");
    private static final Pattern RATIO_PAT = Pattern.compile("^([-+]?[0-9]+)/([0-9]+)$");
    private static final Pattern FLOAT_PAT = Pattern.compile("^([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?$");

    private final CharSource source;
    private int line = 1;
    private int col = 1;
    private final String filename;
    private final Set<Object> features;
    private final boolean readCondAllowed;

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

    @SuppressWarnings("unchecked")
    private Reader(CharSource source, String filename, Map<?, ?> opts) {
        this.source = source;
        this.filename = filename != null ? filename : "<stdin>";
        if (opts != null) {
            Object rc = opts.get(Keyword.intern("read-cond"));
            this.readCondAllowed = rc == null || Keyword.intern("allow").equals(rc);
            Object feats = opts.get(Keyword.intern("features"));
            this.features = (feats instanceof Set<?> set) ? (Set<Object>) set : null;
        } else {
            this.readCondAllowed = true;
            this.features = null;
        }
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

    public static List<Object> readAll(String s, String filename) {
        Reader r = new Reader(s, filename);
        List<Object> forms = new ArrayList<>();
        while (true) {
            Object form = r.readForm(false, EOF);
            if (form == EOF) break;
            if (form instanceof SpliceResult sr) {
                forms.addAll(sr.items);
            } else {
                forms.add(form);
            }
        }
        return forms;
    }

    private int peek() {
        return source.peek();
    }

    private int readChar() {
        int c = source.read();
        if (c == -1) return -1;
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
                Object form = readForm(true, null);
                yield PersistentList.createFromArray(QUOTE, form);
            }
            case '@' -> {
                Object form = readForm(true, null);
                yield PersistentList.createFromArray(DEREF, form);
            }
            case '`' -> readSyntaxQuote();
            case '~' -> {
                if (peek() == '@') {
                    readChar();
                    Object form = readForm(true, null);
                    yield PersistentList.createFromArray(UNQUOTE_SPLICING, form);
                } else {
                    Object form = readForm(true, null);
                    yield PersistentList.createFromArray(UNQUOTE, form);
                }
            }
            case '^' -> readMeta();
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
        } else {
            throw readerError("Metadata must be Symbol, Keyword, String or Map");
        }
        Object target = readForm(true, null);
        if (target instanceof IMeta im) {
            PersistentHashMap existing = im.meta();
            if (existing != null) {
                for (ISeq s = metaMap.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                    existing = existing.assoc(me.key, me.val);
                }
                return im.withMeta(existing);
            }
            return im.withMeta(metaMap);
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
                Object sym = readForm(true, null);
                yield PersistentList.createFromArray(VAR, sym);
            }
            case '"' -> readRegex();
            case '(' -> readAnonFn();
            case '!' -> {
                int ch = readChar();
                while (ch != -1 && ch != '\n') ch = readChar();
                yield DISCARD;
            }
            case '?' -> readConditional();
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
        Object readersObj = RT.DATA_READERS.get();
        Object readerFn = (readersObj != null) ? RT.get(readersObj, tag) : null;
        if (readerFn == null) {
            readerFn = RT.DEFAULT_DATA_READERS_MAP.valAt(tag);
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
        Matcher m = INT_PAT.matcher(s);
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
        m = FLOAT_PAT.matcher(s);
        if (m.matches()) {
            if (m.group(4) != null) {
                return new BigDecimal(m.group(1));
            }
            return Double.parseDouble(s);
        }
        m = RATIO_PAT.matcher(s);
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

    private RuntimeException readerError(String msg) {
        return new RuntimeException(msg + " [" + filename + ":" + line + ":" + col + "]");
    }
}
