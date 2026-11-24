package cljl;

import cljl.lang.*;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimizing Analyzer, Macroexpander, Destructuring Compiler, and Specialized AST
 * Execution Engine for Clojol.
 *
 * Key optimizations:
 * 1. Flat lexical slot addressing: all fn/let/loop/catch locals are mapped to integer
 *    indices in a single Object[] frame per function activation (zero HashMap lookups).
 * 2. Zero-allocation loop/recur & tail-recursive fn/recur: compiled to a direct Java
 *    while(true) loop mutating frame slots in place.
 * 3. Arity-specialized call nodes (0..4 args) avoiding Object[] varargs allocation.
 * 4. Primitive-specialized arithmetic & comparison AST nodes with unboxed evalLong/evalBoolean.
 * 5. Direct Var resolution at compile time.
 */
public final class Compiler {

    public static final Object RECUR_SENTINEL = new Object();
    public static final Var LOADER = Var.create(null).setDynamic();
    public static final Var LOCAL_ENV = Var.create(null).setDynamic();
    public static final Var METHOD = Var.create(null).setDynamic();
    public static final Var CLEAR_SITES = Var.create(null).setDynamic();
    public static final Var LINE = Var.create(0).setDynamic();
    public static final Var COLUMN = Var.create(0).setDynamic();
    public static final Var SOURCE = Var.create("NO_SOURCE_FILE").setDynamic();
    public static final Var SOURCE_PATH = Var.create("NO_SOURCE_PATH").setDynamic();

    public static class CompilerException extends RuntimeException {
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

    private static final Set<Symbol> SPECIALS = Set.of(
            Symbol.intern("quote"),
            Symbol.intern("def"),
            Symbol.intern("if"),
            Symbol.intern("do"),
            Symbol.intern("let*"),
            Symbol.intern("letfn*"),
            Symbol.intern("loop*"),
            Symbol.intern("recur"),
            Symbol.intern("fn*"),
            Symbol.intern("defmacro*"),
            Symbol.intern("var"),
            Symbol.intern("try"),
            Symbol.intern("catch"),
            Symbol.intern("finally"),
            Symbol.intern("throw"),
            Symbol.intern("new"),
            Symbol.intern("."),
            Symbol.intern("set!"),
            Symbol.intern("case*"),
            Symbol.intern("monitor-enter"),
            Symbol.intern("monitor-exit"),
            Symbol.intern("locking*")
    );

    public interface Expr {}
    public static class ObjExpr implements Expr {
        public final Object tag;
        public ObjExpr() { this.tag = null; }
        public ObjExpr(Object tag) { this.tag = tag; }
    }
    public static class ObjMethod {
        public final ObjExpr objx;
        public final ObjMethod parent;
        public ObjMethod() { this.objx = null; this.parent = null; }
        public ObjMethod(ObjExpr objx, ObjMethod parent) { this.objx = objx; this.parent = parent; }
    }
    public static class LocalBinding {
        public final int idx;
        public final Symbol sym;
        public final Symbol tag;
        public final Expr init;
        public final String name;
        public final boolean isArg;
        private final Class<?> javaClass;

        public LocalBinding(int idx, Symbol sym, Symbol tag, Expr init) {
            this(idx, sym, tag, init, false);
        }

        public LocalBinding(int idx, Symbol sym, Symbol tag, Expr init, boolean isArg) {
            this.idx = idx;
            this.sym = sym;
            this.tag = tag;
            this.init = init;
            this.name = sym != null ? munge(sym.name) : null;
            this.isArg = isArg;
            Class<?> jc = null;
            if (tag != null) {
                jc = Interop.resolveClass(RT.currentNS(), tag.toString());
            }
            this.javaClass = jc;
        }

        public boolean hasJavaClass() {
            return javaClass != null;
        }

        public Class<?> getJavaClass() {
            return javaClass;
        }

        public Class<?> getPrimitiveType() {
            return (javaClass != null && javaClass.isPrimitive()) ? javaClass : null;
        }
    }

    public static final PersistentHashMap CHAR_MAP = PersistentHashMap.create(
            '-', "_",
            ':', "_COLON_",
            '+', "_PLUS_",
            '>', "_GT_",
            '<', "_LT_",
            '=', "_EQ_",
            '~', "_TILDE_",
            '!', "_BANG_",
            '@', "_CIRCA_",
            '#', "_SHARP_",
            '\'', "_SINGLEQUOTE_",
            '"', "_DOUBLEQUOTE_",
            '%', "_PERCENT_",
            '^', "_CARET_",
            '&', "_AMPERSAND_",
            '*', "_STAR_",
            '|', "_BAR_",
            '{', "_LBRACE_",
            '}', "_RBRACE_",
            '[', "_LBRACK_",
            ']', "_RBRACK_",
            '/', "_SLASH_",
            '\\', "_BSLASH_",
            '?', "_QMARK_",
            '.', "_DOT_"
    );

    public static String munge(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            Object sub = CHAR_MAP.valAt(c, null);
            if (sub != null) sb.append((String) sub);
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String demunge(String mungedName) {
        StringBuilder sb = new StringBuilder(mungedName.length());
        int i = 0;
        int n = mungedName.length();
        while (i < n) {
            if (mungedName.charAt(i) == '_') {
                boolean matched = false;
                for (ISeq s = CHAR_MAP.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                    String tok = (String) me.val;
                    if (tok.length() > 1 && mungedName.startsWith(tok, i)) {
                        sb.append(me.key);
                        i += tok.length();
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    sb.append('-');
                    i++;
                }
            } else {
                sb.append(mungedName.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    public static final PersistentHashMap specials = buildSpecialsMap();

    private static PersistentHashMap buildSpecialsMap() {
        PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
        for (Symbol s : SPECIALS) tm = tm.assoc(s, Boolean.TRUE);
        tm = tm.assoc(Symbol.intern("finally"), Boolean.TRUE);
        tm = tm.assoc(Symbol.intern("catch"), Boolean.TRUE);
        tm = tm.assoc(Symbol.intern("deftype*"), Boolean.TRUE);
        tm = tm.assoc(Symbol.intern("reify*"), Boolean.TRUE);
        tm = tm.assoc(Symbol.intern("clojure.core/import*"), Boolean.TRUE);
        tm = tm.assoc(Symbol.intern("&"), Boolean.TRUE);
        return tm.persistent();
    }

    public static boolean isSpecial(Symbol sym) {
        return SPECIALS.contains(sym);
    }

    // ========================================================================
    // Public Entry Points
    // ========================================================================

    public static Object eval(Object form) {
        return eval(form, false);
    }

    public static Object eval(Object form, boolean freshLoader) {
        RT.ensureInit();
        form = macroexpand(form, null);
        if (form instanceof ISeq seq) {
            ISeq s = seq.seq();
            if (s != null && s.first() instanceof Symbol sym && sym.ns == null && "do".equals(sym.name)) {
                Object ret = null;
                for (ISeq rest = s.next(); rest != null; rest = rest.next()) {
                    ret = eval(rest.first());
                }
                return ret;
            }
        }
        Node node = compile(form, null);
        if (node instanceof TopFrameNode tf && CompiledFn.containsLoop(tf.body)) {
            FnMethod[] fixed = new FnMethod[21];
            ValType[] slotHints = new ValType[tf.maxSlots];
            Arrays.fill(slotHints, ValType.ANY);
            fixed[0] = new FnMethod(0, false, -1, new int[0], new int[0], new int[0],
                    tf.maxSlots, false, tf.body, new ValType[0], slotHints, ValType.ANY);
            CompiledFn cf = new CompiledFn("<eval>", false, fixed, null, null);
            Fn compiled = BytecodeJIT.compile(cf);
            if (compiled != null) {
                return compiled.invoke();
            }
        }
        return node.eval(Fn.EMPTY_ARGS);
    }

    public static Object load(String src, String filename) {
        return loadReader(new Reader(src, filename), filename);
    }

    public static Object load(java.io.Reader rdr, String filename) {
        return loadReader(new Reader(rdr, filename, null), filename);
    }

    public static Object load(java.io.Reader rdr, String sourcePath, String sourceName) {
        String fname = sourcePath != null ? sourcePath : sourceName;
        return loadReader(new Reader(rdr, fname, null), fname);
    }

    private static Object loadReader(Reader reader, String filename) {
        RT.ensureInit();
        String fname = filename != null ? filename : "NO_SOURCE_PATH";
        String shortName = fname;
        int slash = Math.max(fname.lastIndexOf('/'), fname.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < fname.length()) shortName = fname.substring(slash + 1);
        Map<Var, Object> bindings = new HashMap<>(16);
        bindings.put(RT.FILE, fname);
        bindings.put(RT.SOURCE_PATH, fname);
        bindings.put(SOURCE, shortName);
        bindings.put(SOURCE_PATH, fname);
        bindings.put(LINE, 0);
        bindings.put(COLUMN, 0);
        bindings.put(RT.WARN_ON_REFLECTION, RT.WARN_ON_REFLECTION.deref());
        bindings.put(RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref());
        bindings.put(RT.DATA_READERS, RT.DATA_READERS.deref());
        bindings.put(RT.READ_EVAL, RT.READ_EVAL.deref());
        bindings.put(RT.ASSERT, RT.ASSERT.deref());
        bindings.put(RT.COMPILER_OPTIONS, RT.COMPILER_OPTIONS.deref());
        bindings.put(RT.MATH_CONTEXT, RT.MATH_CONTEXT.deref());
        Var.pushThreadBindings(bindings);
        try {
            final Object[] last = new Object[]{null};
            reader.consumeForms(new java.util.function.Consumer<Object>() {
                @Override
                public void accept(Object form) {
                    last[0] = eval(form);
                }
            });
            return last[0];
        } finally {
            Var.popThreadBindings();
        }
    }

    private static final ThreadLocal<Integer> CURRENT_LINE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_COL = new ThreadLocal<>();
    private static final Keyword LINE_KW = Keyword.intern("line");
    private static final Keyword COLUMN_KW = Keyword.intern("column");
    private static final Keyword FILE_KW = Keyword.intern("file");
    private static final Keyword ELIDE_META_KW = Keyword.intern("elide-meta");
    private static final Keyword DIRECT_LINKING_KW = Keyword.intern("direct-linking");
    private static final Keyword WARN_ON_BOXED_KW = Keyword.intern("warn-on-boxed");

    public static boolean isDirectLinking() {
        Object opts = RT.COMPILER_OPTIONS.get();
        return opts instanceof IPersistentMap m && RT.truthy(m.valAt(DIRECT_LINKING_KW));
    }

    public static boolean isUncheckedMath() {
        return RT.truthy(RT.UNCHECKED_MATH.get());
    }

    public static boolean isWarnOnBoxed() {
        return WARN_ON_BOXED_KW.equals(RT.UNCHECKED_MATH.get());
    }

    private static PersistentHashMap applyElideMeta(PersistentHashMap meta) {
        if (meta == null) return null;
        Object opts = RT.COMPILER_OPTIONS.get();
        if (opts instanceof IPersistentMap om) {
            Object elide = om.valAt(ELIDE_META_KW);
            if (elide != null) {
                for (ISeq s = RT.seq(elide); s != null; s = s.next()) {
                    meta = meta.without(s.first());
                }
            }
        }
        return meta;
    }

    static void warnCompiler(String prefix, String msg) {
        Object errObj = RT.ERR.get();
        Object src = RT.FILE.get();
        if (src == null) src = SOURCE_PATH.get();
        Integer ln = CURRENT_LINE.get();
        Integer col = CURRENT_COL.get();
        String line = prefix + ", " + (src != null ? src : "NO_SOURCE_PATH")
                + ":" + (ln != null ? ln : 0) + ":" + (col != null ? col : 0) + " - " + msg + "\n";
        try {
            if (errObj instanceof java.io.Writer w) {
                w.write(line);
                w.flush();
            } else {
                System.err.print(line);
            }
        } catch (java.io.IOException ignored) {}
    }

    static void warnReflection(String msg) {
        if (RT.truthy(RT.WARN_ON_REFLECTION.get())) {
            warnCompiler("Reflection warning", msg);
        }
    }

    static void warnBoxedMath(String op) {
        if (isWarnOnBoxed()) {
            warnCompiler("Boxed math warning", "call: " + op + " can't be resolved to primitive ops");
        }
    }

    private static String locSuffix() {
        Object f = RT.FILE.get();
        Integer l = CURRENT_LINE.get();
        if (f != null && l != null) return " (" + f + ":" + l + ")";
        if (l != null) return " (line " + l + ")";
        if (f != null) return " (" + f + ")";
        return "";
    }

    // ========================================================================
    // Macroexpansion
    // ========================================================================

    public static Object macroexpand1(Object form) {
        return macroexpand1(form, null);
    }

    private static Object preserveLine(Object orig, Object expanded) {
        if (expanded != orig && orig instanceof IMeta fm && fm.meta() != null && expanded instanceof IObj eo) {
            Object lineObj = fm.meta().valAt(LINE_KW);
            if (lineObj != null) {
                PersistentHashMap em = eo.meta();
                if (em == null || !em.containsKey(LINE_KW)) {
                    return eo.withMeta((em != null ? em : PersistentHashMap.EMPTY).assoc(LINE_KW, lineObj));
                }
            }
        }
        return expanded;
    }

    private static Object macroexpand1(Object form, Scope scope) {
        if (!(form instanceof ISeq seq)) return form;
        ISeq s = seq.seq();
        if (s == null) return form;
        Object op = s.first();
        if (!(op instanceof Symbol sym)) return form;
        if (isSpecial(sym)) return form;
        if (scope != null && scope.resolveLocal(sym) != -1) {
            return form;
        }

        // Check for macro Var
        Var v = lookupVar(RT.currentNS(), sym, false);
        if (v != null && v.isMacro() && v.isBound()) {
            Fn macroFn = (Fn) v.deref();
            ISeq args = s.next();
            int argc = RT.count(args);
            Object[] callArgs = new Object[argc + 2];
            callArgs[0] = form;
            callArgs[1] = scope != null ? scope.lexicalEnvMap() : null;
            int idx = 2;
            for (ISeq it = args; it != null; it = it.next()) {
                callArgs[idx++] = it.first();
            }
            return preserveLine(form, macroFn.applyTo(callArgs));
        }

        // Interop sugar macroexpansion:
        // 1. (ClassName. arg1 arg2) -> (new ClassName arg1 arg2)
        if (sym.ns == null && sym.name.endsWith(".") && sym.name.length() > 1 && !sym.name.startsWith(".")) {
            Symbol classSym = Symbol.intern(sym.name.substring(0, sym.name.length() - 1));
            return preserveLine(form, RT.cons(Symbol.intern("new"), RT.cons(classSym, s.next())));
        }
        // 2. (.method target arg1 arg2) -> (. target method arg1 arg2)
        if (sym.ns == null && sym.name.startsWith(".") && sym.name.length() > 1 && !sym.name.equals("..")) {
            ISeq rest = s.next();
            if (rest == null) throw new IllegalArgumentException("Malformed member expression: " + form + locSuffix());
            Object target = rest.first();
            Symbol memberSym = Symbol.intern(sym.name.substring(1));
            return preserveLine(form, RT.cons(Symbol.intern("."), RT.cons(target, RT.cons(memberSym, rest.next()))));
        }

        return form;
    }

    public static Object macroexpand(Object form) {
        return macroexpand(form, null);
    }

    private static Object macroexpand(Object form, Scope scope) {
        Object ex = macroexpand1(form, scope);
        while (ex != form) {
            form = ex;
            ex = macroexpand1(form, scope);
        }
        return ex;
    }

    // ========================================================================
    // Destructuring Compiler
    // ========================================================================

    /**
     * Expands destructuring binding pairs [pattern1 init1 pattern2 init2 ...]
     * into flat [sym1 expr1 sym2 expr2 ...] pairs.
     */
    public static PersistentVector destructure(PersistentVector bindings) {
        if ((bindings.count() & 1) != 0) {
            throw new IllegalArgumentException("Bindings must contain an even number of forms");
        }
        boolean hasComplex = false;
        for (int i = 0; i < bindings.count(); i += 2) {
            if (!(bindings.nth(i) instanceof Symbol)) {
                hasComplex = true;
                break;
            }
        }
        if (!hasComplex) return bindings;

        List<Object> out = new ArrayList<>();
        for (int i = 0; i < bindings.count(); i += 2) {
            destructurePair(bindings.nth(i), bindings.nth(i + 1), out);
        }
        return PersistentVector.create(out);
    }

    private static void destructurePair(Object bform, Object init, List<Object> out) {
        if (bform instanceof Symbol sym) {
            out.add(sym);
            out.add(init);
        } else if (bform instanceof PersistentVector vec) {
            Symbol gvec = gensym("vec__");
            out.add(gvec);
            out.add(init);
            int n = vec.count();
            boolean seenRest = false;
            for (int i = 0; i < n; i++) {
                Object elem = vec.nth(i);
                if (elem instanceof Symbol s && "&".equals(s.name)) {
                    if (i + 1 < n) {
                        Object restPat = vec.nth(i + 1);
                        Object restExpr = PersistentList.createFromArray(
                                Symbol.intern("clojure.core", "nthnext"), gvec, (long) i);
                        destructurePair(restPat, restExpr, out);
                        i++;
                        seenRest = true;
                    }
                } else if (elem instanceof Keyword kw && "as".equals(kw.getName())) {
                    if (i + 1 < n) {
                        destructurePair(vec.nth(i + 1), gvec, out);
                        i++;
                    }
                } else if (!seenRest) {
                    Object nthExpr = PersistentList.createFromArray(
                            Symbol.intern("clojure.core", "nth"), gvec, (long) i, null);
                    destructurePair(elem, nthExpr, out);
                }
            }
        } else if (bform instanceof IPersistentMap map) {
            Symbol gmap = gensym("map__");
            out.add(gmap);
            out.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "__destructure-map"), init));

            Object orMap = map.valAt(Keyword.intern("or"), PersistentHashMap.EMPTY);
            Object asSym = map.valAt(Keyword.intern("as"), null);

            if (asSym != null) {
                destructurePair(asSym, gmap, out);
            }

            for (ISeq s = map.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                Object k = me.getKey();
                Object v = me.getValue();
                if (k instanceof Keyword kw) {
                    String kn = kw.getName();
                    if ((kw.getNamespace() == null && ("or".equals(kn) || "as".equals(kn)))
                            || "keys".equals(kn) || "strs".equals(kn) || "syms".equals(kn)) {
                        continue;
                    }
                }
                // In Clojure map destructuring {target-pat lookup-key}
                boolean hasDef = hasOrDefault(orMap, k);
                Object defaultExpr = hasDef ? getOrDefault(orMap, k) : null;
                Object lookupExpr = makeGetExpr(gmap, v, hasDef, defaultExpr);
                destructurePair(k, lookupExpr, out);
            }

            for (ISeq s = map.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                if (!(me.getKey() instanceof Keyword kw)) continue;
                String kn = kw.getName();
                String defaultNs = kw.getNamespace();
                if ("keys".equals(kn)) {
                    for (ISeq ks = RT.seq(me.getValue()); ks != null; ks = ks.next()) {
                        Object item = ks.first();
                        String itemNs;
                        String itemName;
                        if (item instanceof Symbol sy) {
                            itemNs = sy.ns != null ? sy.ns : defaultNs;
                            itemName = sy.name;
                        } else if (item instanceof Keyword ikw) {
                            itemNs = ikw.getNamespace() != null ? ikw.getNamespace() : defaultNs;
                            itemName = ikw.getName();
                        } else {
                            itemNs = defaultNs;
                            itemName = RT.name(item);
                        }
                        Symbol localSym = Symbol.intern(itemName);
                        Keyword lookupKw = Keyword.intern(itemNs, itemName);
                        boolean hasDef = hasOrDefault(orMap, localSym);
                        Object defaultExpr = hasDef ? getOrDefault(orMap, localSym) : null;
                        out.add(localSym);
                        out.add(makeGetExpr(gmap, lookupKw, hasDef, defaultExpr));
                    }
                } else if ("strs".equals(kn)) {
                    for (ISeq ss = RT.seq(me.getValue()); ss != null; ss = ss.next()) {
                        Object item = ss.first();
                        String itemNs = (item instanceof Symbol sy && sy.ns != null) ? sy.ns : defaultNs;
                        String itemName = (item instanceof Symbol sy) ? sy.name : RT.name(item);
                        String lookupStr = itemNs != null ? (itemNs + "/" + itemName) : itemName;
                        Symbol localSym = Symbol.intern(itemName);
                        boolean hasDef = hasOrDefault(orMap, localSym);
                        Object defaultExpr = hasDef ? getOrDefault(orMap, localSym) : null;
                        out.add(localSym);
                        out.add(makeGetExpr(gmap, lookupStr, hasDef, defaultExpr));
                    }
                } else if ("syms".equals(kn)) {
                    for (ISeq syms = RT.seq(me.getValue()); syms != null; syms = syms.next()) {
                        Symbol sym = (Symbol) syms.first();
                        String lookupNs = sym.ns != null ? sym.ns : defaultNs;
                        Symbol localSym = Symbol.intern(sym.name);
                        Symbol lookupSym = Symbol.intern(lookupNs, sym.name);
                        boolean hasDef = hasOrDefault(orMap, localSym);
                        Object defaultExpr = hasDef ? getOrDefault(orMap, localSym) : null;
                        out.add(localSym);
                        out.add(makeGetExpr(gmap, PersistentList.createFromArray(Symbol.intern("quote"), lookupSym), hasDef, defaultExpr));
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported binding form: " + bform);
        }
    }

    private static boolean hasOrDefault(Object orMap, Object pat) {
        return orMap instanceof IPersistentMap m && pat instanceof Symbol sym && m.containsKey(Symbol.intern(sym.name));
    }

    private static Object getOrDefault(Object orMap, Object pat) {
        if (orMap instanceof IPersistentMap m && pat instanceof Symbol sym) {
            return m.valAt(Symbol.intern(sym.name), null);
        }
        return null;
    }

    private static Object makeGetExpr(Symbol gmap, Object keyExpr, boolean hasDefault, Object defaultExpr) {
        if (hasDefault) {
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "get"), gmap, keyExpr, defaultExpr);
        }
        return PersistentList.createFromArray(Symbol.intern("clojure.core", "get"), gmap, keyExpr);
    }

    private static Symbol gensym(String prefix) {
        return Symbol.intern(prefix + RT.nextID());
    }

    // ========================================================================
    // Lexical Scope & Frame Allocation
    // ========================================================================

    private static final Keyword TAG_KW = Keyword.intern("tag");
    private static final Keyword PARAM_TAGS_KW = Keyword.intern("param-tags");
    private static final Keyword INLINE_KW = Keyword.intern("inline");
    private static final Keyword INLINE_ARITIES_KW = Keyword.intern("inline-arities");

    private static Class<?>[] extractParamTags(Symbol sym, Namespace ns) {
        if (sym != null && sym.meta() != null) {
            Object pt = sym.meta().valAt(PARAM_TAGS_KW);
            if (pt != null) {
                return Interop.resolveParamTags(ns, pt);
            }
        }
        return null;
    }

    private static ValType extractTagHint(Object obj) {
        if (obj instanceof IMeta im && im.meta() != null) {
            Object tag = im.meta().valAt(TAG_KW);
            if (tag != null) {
                return ValType.fromTag(tag, RT.currentNS());
            }
        }
        return ValType.ANY;
    }

    private static final class Scope {
        final Scope parent;
        final List<Map<Symbol, Integer>> blockStack = new ArrayList<>();
        final Map<Symbol, Integer> capturedBySymbol = new HashMap<>();
        final List<Integer> capturedParentSlots = new ArrayList<>();
        final List<ValType> slotHints = new ArrayList<>();
        final List<Symbol> slotTags = new ArrayList<>();
        final Set<Integer> staticDoubleSlots = new HashSet<>();
        final Deque<int[]> recurTargets = new ArrayDeque<>();
        int maxSlots = 0;
        int selfSlot = -1;
        boolean fnRecurUsed = false;

        Scope(Scope parent) {
            this.parent = parent;
            this.blockStack.add(new HashMap<>());
        }

        void pushBlock() {
            blockStack.add(new HashMap<>());
        }

        void popBlock() {
            blockStack.remove(blockStack.size() - 1);
        }

        int allocateSelfSlot(Symbol sym) {
            int s = allocateSlot(sym);
            this.selfSlot = s;
            return s;
        }

        ValType slotHint(int slot) {
            return (slot >= 0 && slot < slotHints.size()) ? slotHints.get(slot) : ValType.ANY;
        }

        int allocateSlot(Symbol sym) {
            int slot = maxSlots++;
            slotHints.add(extractTagHint(sym));
            Symbol tagSym = null;
            if (sym != null && sym.meta() != null) {
                Object tagObj = sym.meta().valAt(TAG_KW);
                if (tagObj instanceof Symbol ts) tagSym = ts;
                else if (tagObj instanceof String s) tagSym = Symbol.intern(s);
            }
            slotTags.add(tagSym);
            if (sym != null) {
                blockStack.get(blockStack.size() - 1).put(sym.withMeta(null), slot);
            }
            return slot;
        }

        int resolveLocal(Symbol sym) {
            Symbol key = sym.withMeta(null);
            for (int i = blockStack.size() - 1; i >= 0; i--) {
                Integer slot = blockStack.get(i).get(key);
                if (slot != null) return slot;
            }
            Integer capSlot = capturedBySymbol.get(key);
            if (capSlot != null) return capSlot;

            if (parent != null) {
                int parentSlot = parent.resolveLocal(key);
                if (parentSlot != -1) {
                    int mySlot = maxSlots++;
                    ValType parentHint = parentSlot < parent.slotHints.size()
                            ? parent.slotHints.get(parentSlot) : ValType.ANY;
                    slotHints.add(parentHint);
                    slotTags.add(parentSlot < parent.slotTags.size() ? parent.slotTags.get(parentSlot) : null);
                    capturedBySymbol.put(key, mySlot);
                    capturedParentSlots.add(parentSlot);
                    if (parent.staticDoubleSlots.contains(parentSlot)) {
                        staticDoubleSlots.add(mySlot);
                    }
                    return mySlot;
                }
            }
            return -1;
        }

        PersistentHashMap lexicalEnvMap() {
            PersistentHashMap env = PersistentHashMap.EMPTY;
            for (Scope cur = this; cur != null; cur = cur.parent) {
                for (int bi = cur.blockStack.size() - 1; bi >= 0; bi--) {
                    Map<Symbol, Integer> block = cur.blockStack.get(bi);
                    for (Map.Entry<Symbol, Integer> e : block.entrySet()) {
                        int slot = e.getValue();
                        if (slot == cur.selfSlot) continue;
                        Symbol sym = e.getKey();
                        if (!env.containsKey(sym)) {
                            Symbol tagSym = (slot >= 0 && slot < cur.slotTags.size()) ? cur.slotTags.get(slot) : null;
                            env = env.assoc(sym, new LocalBinding(slot, sym, tagSym, null));
                        }
                    }
                }
            }
            return env.count() == 0 ? null : env;
        }
    }

    // ========================================================================
    // Analyzer / Compiler
    // ========================================================================

    public static Node compile(Object form, Scope scope) {
        return compile(form, scope, false);
    }

    public static Node compile(Object form, Scope scope, boolean inTail) {
        Integer prevLine = CURRENT_LINE.get();
        Integer prevCol = CURRENT_COL.get();
        Integer formLine = prevLine;
        Integer formCol = prevCol;
        if (form instanceof IMeta im && im.meta() != null) {
            Object l = im.meta().valAt(LINE_KW);
            if (l instanceof Number n) {
                formLine = n.intValue();
            }
            Object c = im.meta().valAt(COLUMN_KW);
            if (c instanceof Number nc) {
                formCol = nc.intValue();
            }
        }
        boolean lineChanged = !Objects.equals(formLine, prevLine);
        boolean colChanged = !Objects.equals(formCol, prevCol);
        if (lineChanged) CURRENT_LINE.set(formLine);
        if (colChanged) CURRENT_COL.set(formCol);
        try {
            form = macroexpand(form, scope);

            if (form instanceof IMeta im2 && im2.meta() != null) {
                Object l2 = im2.meta().valAt(LINE_KW);
                if (l2 instanceof Number n2) {
                    int li = n2.intValue();
                    if (!Objects.equals(li, CURRENT_LINE.get())) {
                        CURRENT_LINE.set(li);
                        lineChanged = true;
                    }
                }
                Object c2 = im2.meta().valAt(COLUMN_KW);
                if (c2 instanceof Number nc2) {
                    int ci = nc2.intValue();
                    if (!Objects.equals(ci, CURRENT_COL.get())) {
                        CURRENT_COL.set(ci);
                        colChanged = true;
                    }
                }
            }

            if (form == null) return ConstNode.NIL;
            if (form == Boolean.TRUE) return ConstNode.TRUE;
            if (form == Boolean.FALSE) return ConstNode.FALSE;
            if (form instanceof Number || form instanceof String || form instanceof Character
                    || form instanceof Keyword || form instanceof java.util.regex.Pattern) {
                return new ConstNode(form);
            }

            if (form instanceof Symbol sym) {
                return compileSymbol(sym, scope);
            }

            if (form instanceof PersistentVector pv) {
                if (pv.count() == 0) return new ConstNode(PersistentVector.EMPTY);
                Node[] elems = new Node[pv.count()];
                boolean allConst = true;
                for (int i = 0; i < pv.count(); i++) {
                    elems[i] = compile(pv.nth(i), scope, false);
                    if (!(elems[i] instanceof ConstNode)) allConst = false;
                }
                if (allConst) {
                    Object[] vals = new Object[elems.length];
                    for (int i = 0; i < elems.length; i++) vals[i] = ((ConstNode) elems[i]).val;
                    return new ConstNode(PersistentVector.createOwning(vals).withMeta(pv.meta()));
                }
                return new VectorNode(elems);
            }

            if (form instanceof IPersistentMap pm) {
                PersistentHashMap formMeta = (pm instanceof IMeta im) ? im.meta() : null;
                if (pm.count() == 0) {
                    return new ConstNode(formMeta != null ? PersistentArrayMap.EMPTY.withMeta(formMeta) : PersistentArrayMap.EMPTY);
                }
                Node[] kvs = new Node[pm.count() * 2];
                int i = 0;
                boolean allConst = true;
                for (ISeq s = pm.seq(); s != null; s = s.next()) {
                    Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                    kvs[i] = compile(me.getKey(), scope, false);
                    kvs[i + 1] = compile(me.getValue(), scope, false);
                    if (!(kvs[i] instanceof ConstNode) || !(kvs[i + 1] instanceof ConstNode)) allConst = false;
                    i += 2;
                }
                if (allConst) {
                    Object[] vals = new Object[kvs.length];
                    for (int j = 0; j < kvs.length; j++) vals[j] = ((ConstNode) kvs[j]).val;
                    IPersistentMap res = RT.mapUniqueKeys(vals);
                    if (formMeta != null && res instanceof IObj io) res = (IPersistentMap) io.withMeta(formMeta);
                    return new ConstNode(res);
                }
                return new MapNode(kvs);
            }

            if (form instanceof PersistentHashSet ps) {
                if (ps.count() == 0) return new ConstNode(PersistentHashSet.EMPTY);
                Node[] elems = new Node[ps.count()];
                int i = 0;
                boolean allConst = true;
                for (ISeq s = ps.seq(); s != null; s = s.next()) {
                    elems[i] = compile(s.first(), scope, false);
                    if (!(elems[i] instanceof ConstNode)) allConst = false;
                    i++;
                }
                if (allConst) {
                    Object[] vals = new Object[elems.length];
                    for (int j = 0; j < elems.length; j++) vals[j] = ((ConstNode) elems[j]).val;
                    return new ConstNode(PersistentHashSet.create(vals).withMeta(ps.meta()));
                }
                return new SetNode(elems);
            }

            if (form instanceof ISeq seq) {
                ISeq s = seq.seq();
                if (s == null) return new ConstNode(PersistentList.EMPTY);
                return compileSeq(s, scope, inTail);
            }

            return new ConstNode(form);
        } finally {
            if (lineChanged) {
                if (prevLine == null) CURRENT_LINE.remove();
                else CURRENT_LINE.set(prevLine);
            }
            if (colChanged) {
                if (prevCol == null) CURRENT_COL.remove();
                else CURRENT_COL.set(prevCol);
            }
        }
    }

    private static Node compileSymbol(Symbol sym, Scope scope) {
        ValType symHint = extractTagHint(sym);
        if (sym.ns == null) {
            if (scope != null) {
                int slot = scope.resolveLocal(sym);
                if (slot != -1) {
                    ValType effectiveHint = symHint != ValType.ANY ? symHint : scope.slotHint(slot);
                    return new LocalNode(slot, effectiveHint, scope.staticDoubleSlots.contains(slot));
                }
            }
            Namespace ns = RT.currentNS();
            Object mapped = ns.getMapping(sym);
            if (mapped instanceof Var v) {
                return new VarRefNode(v, symHint);
            }
            if (mapped instanceof Class<?> c) {
                return new ConstNode(c);
            }
            Class<?> cls = Interop.resolveClass(ns, sym.name);
            if (cls != null) {
                return new ConstNode(cls);
            }
            Var v = lookupVar(ns, sym, false);
            if (v != null) {
                return new VarRefNode(v, symHint);
            }
            throw new RuntimeException("Unable to resolve symbol: " + sym + " in this context (" + ns.name + ")" + locSuffix());
        } else {
            // Qualified symbol: either ns/var or Class/STATIC_FIELD
            Namespace cur = RT.currentNS();
            Namespace targetNs = cur.lookupAlias(Symbol.intern(sym.ns));
            if (targetNs == null) targetNs = Namespace.find(sym.ns);
            if (targetNs != null) {
                Object mapped = targetNs.getMapping(Symbol.intern(sym.name));
                if (mapped instanceof Var v) {
                    return new VarRefNode(v, symHint);
                }
            }
            Class<?> cls = Interop.resolveClass(cur, sym.ns);
            if (cls != null) {
                if (Interop.isArrayDimToken(sym.name)) {
                    Class<?> arrCls = Interop.resolveArrayClass(cur, sym.ns, sym.name);
                    if (arrCls != null) return new ConstNode(arrCls);
                }
                Class<?>[] paramTags = extractParamTags(sym, cur);
                if ("new".equals(sym.name)) {
                    return new ConstNode(Interop.createConstructorFn(cls, paramTags));
                }
                if (sym.name.startsWith(".") && sym.name.length() > 1) {
                    return new ConstNode(Interop.createInstanceMethodFn(cls, sym.name.substring(1), paramTags));
                }
                if (paramTags == null && Interop.hasStaticField(cls, sym.name)) {
                    return new StaticFieldNode(cls, sym.name);
                }
                if (paramTags != null || Interop.hasStaticMethod(cls, sym.name)) {
                    return new ConstNode(Interop.createStaticMethodFn(cls, sym.name, paramTags));
                }
                throw new RuntimeException("No such field or method: " + sym + " on class " + cls.getName() + locSuffix());
            }
            // Auto-load built-in standard library namespace if not yet initialized
            if (StdLib.ensureLoaded(sym.ns)) {
                targetNs = Namespace.find(sym.ns);
                if (targetNs != null) {
                    Object mapped = targetNs.getMapping(Symbol.intern(sym.name));
                    if (mapped instanceof Var v) {
                        return new VarRefNode(v, symHint);
                    }
                }
            }
            throw new RuntimeException("No such var or static field: " + sym + locSuffix());
        }
    }

    public static Var lookupVar(Namespace ns, Symbol sym, boolean internIfMissing) {
        if (sym.ns != null) {
            Namespace targetNs = ns.lookupAlias(Symbol.intern(sym.ns));
            if (targetNs == null) targetNs = Namespace.find(sym.ns);
            if (targetNs == null && StdLib.ensureLoaded(sym.ns)) {
                targetNs = Namespace.find(sym.ns);
            }
            if (targetNs == null) return null;
            Object m = targetNs.getMapping(Symbol.intern(sym.name));
            return (m instanceof Var v) ? v : null;
        }
        Object m = ns.getMapping(sym);
        if (m instanceof Var v) return v;
        Object coreM = RT.CLOJURE_NS.getMapping(sym);
        if (coreM instanceof Var v) return v;
        if (internIfMissing) return ns.intern(sym);
        return null;
    }

    private static Node compileSeq(ISeq s, Scope scope, boolean inTail) {
        Object op = s.first();
        ISeq args = s.next();

        if (op instanceof Symbol sym) {
            // Special forms are never shadowed by local variables in operator position (matches Clojure)
            if (sym.ns == null) {
                switch (sym.name) {
                    case "quote" -> {
                        return new ConstNode(RT.first(args));
                    }
                    case "def" -> {
                        return compileDef(args, scope);
                    }
                    case "if" -> {
                        return compileIf(args, scope, inTail);
                    }
                    case "do" -> {
                        return compileDo(args, scope, inTail);
                    }
                    case "let*" -> {
                        return compileLet(args, scope, inTail);
                    }
                    case "letfn*" -> {
                        return compileLetfn(args, scope, inTail);
                    }
                    case "loop*" -> {
                        return compileLoop(args, scope);
                    }
                    case "recur" -> {
                        return compileRecur(args, scope, inTail);
                    }
                    case "fn*" -> {
                        return compileFn(args, scope);
                    }
                    case "defmacro*" -> {
                        return compileDefmacro(args, scope);
                    }
                    case "var" -> {
                        Symbol target = (Symbol) RT.first(args);
                        Var v = lookupVar(RT.currentNS(), target, false);
                        if (v == null) throw new RuntimeException("Unable to resolve var: " + target + locSuffix());
                        return new ConstNode(v);
                    }
                    case "try" -> {
                        return compileTry(args, scope);
                    }
                    case "throw" -> {
                        Node expr = compile(RT.first(args), scope, false);
                        return new ThrowNode(expr);
                    }
                    case "new" -> {
                        return compileNew(args, scope);
                    }
                    case "." -> {
                        return compileDot(args, scope);
                    }
                    case "set!" -> {
                        return compileSetBang(args, scope);
                    }
                    case "case*" -> {
                        return compileCase(args, scope, inTail);
                    }
                    case "monitor-enter" -> {
                        return new MonitorNode(compile(RT.first(args), scope, false), true);
                    }
                    case "monitor-exit" -> {
                        return new MonitorNode(compile(RT.first(args), scope, false), false);
                    }
                    case "locking*" -> {
                        return new LockingNode(compile(RT.first(args), scope, false),
                                compile(RT.second(args), scope, false));
                    }
                }
            }
            boolean shadowed = (sym.ns == null && scope != null && scope.resolveLocal(sym) != -1);

            // Check if this is a static/qualified method or constructor call (Class/method, Class/.method, Class/new)
            if (!shadowed && sym.ns != null) {
                Namespace cur = RT.currentNS();
                Namespace targetNs = cur.lookupAlias(Symbol.intern(sym.ns));
                if (targetNs == null) targetNs = Namespace.find(sym.ns);
                if (targetNs == null && !StdLib.ensureLoaded(sym.ns)) {
                    Class<?> cls = Interop.resolveClass(cur, sym.ns);
                    if (cls != null) {
                        Class<?>[] paramTags = extractParamTags(sym, cur);
                        Node[] argNodes = compileArgs(args, scope);
                        if ("new".equals(sym.name)) {
                            return new NewNode(cls, argNodes, paramTags);
                        }
                        if (sym.name.startsWith(".") && sym.name.length() > 1) {
                            if (argNodes.length < 1) {
                                throw new IllegalArgumentException(sym + " requires a target instance" + locSuffix());
                            }
                            Node targetNode = argNodes[0];
                            Node[] restArgs = argNodes.length == 1
                                    ? EMPTY_NODES
                                    : Arrays.copyOfRange(argNodes, 1, argNodes.length);
                            return new InstanceMethodNode(targetNode, sym.name.substring(1), restArgs, cls, paramTags);
                        }
                        return createStaticMethodNode(cls, sym.name, argNodes, paramTags);
                    }
                }
            }

            // Check if this resolves to a Var at compile time
            if (!shadowed) {
                Var targetVar = lookupVar(RT.currentNS(), sym, false);
                if (targetVar != null) {
                    PersistentHashMap vmeta = targetVar.meta();
                    if (vmeta != null) {
                        Object inlineObj = vmeta.valAt(INLINE_KW);
                        if (inlineObj instanceof Fn inlineFn) {
                            int argc = RT.count(args);
                            Object aritiesObj = vmeta.valAt(INLINE_ARITIES_KW);
                            if (aritiesObj == null || (aritiesObj instanceof Fn af && RT.truthy(af.invoke((long) argc)))) {
                                Object[] rawArgs = new Object[argc];
                                int k = 0;
                                for (ISeq it = args; it != null; it = it.next()) rawArgs[k++] = it.first();
                                Object inlined = inlineFn.applyTo(rawArgs);
                                return compile(inlined, scope, inTail);
                            }
                        }
                    }
                    Node[] argNodes = compileArgs(args, scope);
                    // Emit specialized intrinsic AST nodes for core hot-path operations
                    if (targetVar.ns == RT.CLOJURE_NS && !targetVar.isDynamic() && !Core.coreRedefined) {
                        Node intrinsic = tryCreateIntrinsic(targetVar.sym.name, argNodes);
                        if (intrinsic != null) return intrinsic;
                    }
                    return createDirectVarCall(targetVar, argNodes);
                }
            }
        }

        // General function call
        Node fnNode = compile(op, scope, false);
        Node[] argNodes = compileArgs(args, scope);
        return createCallNode(fnNode, argNodes);
    }

    private static Node[] compileArgs(ISeq args, Scope scope) {
        int n = RT.count(args);
        if (n == 0) return EMPTY_NODES;
        Node[] nodes = new Node[n];
        int i = 0;
        for (ISeq s = args; s != null; s = s.next()) {
            nodes[i++] = compile(s.first(), scope, false);
        }
        return nodes;
    }

    private static Node tryCreateIntrinsic(String name, Node[] args) {
        return CompilerIntrinsics.tryCreate(name, args);
    }

    private static Node createDirectVarCall(Var v, Node[] args) {
        return switch (args.length) {
            case 0 -> new VarCall0Node(v);
            case 1 -> new VarCall1Node(v, args[0]);
            case 2 -> new VarCall2Node(v, args[0], args[1]);
            case 3 -> new VarCall3Node(v, args[0], args[1], args[2]);
            case 4 -> new VarCall4Node(v, args[0], args[1], args[2], args[3]);
            default -> new VarCallNNode(v, args);
        };
    }

    private static Node createCallNode(Node fn, Node[] args) {
        return switch (args.length) {
            case 0 -> new Call0Node(fn);
            case 1 -> new Call1Node(fn, args[0]);
            case 2 -> new Call2Node(fn, args[0], args[1]);
            case 3 -> new Call3Node(fn, args[0], args[1], args[2]);
            case 4 -> new Call4Node(fn, args[0], args[1], args[2], args[3]);
            default -> new CallNNode(fn, args);
        };
    }

    private static boolean hasEvaluatableForm(Object o) {
        if (o instanceof ISeq || o instanceof Symbol) return true;
        if (o instanceof PersistentVector pv) {
            for (int i = 0; i < pv.count(); i++) {
                if (hasEvaluatableForm(pv.nth(i))) return true;
            }
            return false;
        }
        if (o instanceof IPersistentMap pm) {
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                if (hasEvaluatableForm(me.getKey()) || hasEvaluatableForm(me.getValue())) return true;
            }
            return false;
        }
        if (o instanceof PersistentHashSet ps) {
            for (ISeq s = ps.seq(); s != null; s = s.next()) {
                if (hasEvaluatableForm(s.first())) return true;
            }
            return false;
        }
        return false;
    }

    private static Node compileDynamicMeta(PersistentHashMap rawMeta, Scope scope) {
        if (rawMeta == null || rawMeta.count() == 0) return null;
        PersistentHashMap evalMap = PersistentHashMap.EMPTY;
        for (ISeq s = rawMeta.seq(); s != null; s = s.next()) {
            PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
            Object k = me.key;
            if (k instanceof Keyword kw) {
                String kn = kw.getName();
                if (kw.getNamespace() == null && ("line".equals(kn) || "column".equals(kn) || "file".equals(kn)
                        || "ns".equals(kn) || "name".equals(kn) || "doc".equals(kn) || "tag".equals(kn))) {
                    continue;
                }
            }
            if (hasEvaluatableForm(k) || hasEvaluatableForm(me.val)) {
                evalMap = evalMap.assoc(k, me.val);
            }
        }
        if (evalMap.count() == 0) return null;
        return compile(evalMap, scope, false);
    }

    private static Node compileDef(ISeq args, Scope scope) {
        Integer defLine = CURRENT_LINE.get();
        Object fileObj = RT.FILE.get();
        Symbol sym = (Symbol) args.first();
        ISeq more = args.next();
        Namespace ns = RT.currentNS();
        PersistentHashMap symMeta = sym.meta();
        // Intern var BEFORE compiling init expr so self-referential defs resolve (Clojure semantics)
        Var v = ns.intern(sym);
        String doc = null;
        Node initNode = null;
        if (more != null) {
            if (more.next() != null && more.first() instanceof String docStr) {
                doc = docStr;
                more = more.next();
            }
            initNode = compile(more.first(), scope, false);
        }
        if (initNode instanceof ConstNode cn && cn.val instanceof CompiledFn cfn) {
            cfn.setOwnerVar(v);
        }
        Node metaNode = compileDynamicMeta(symMeta, scope);
        PersistentHashMap meta = v.meta()
                .assoc(Keyword.intern("ns"), ns)
                .assoc(Keyword.intern("name"), sym.withMeta(null));
        if (doc != null) {
            meta = meta.assoc(Keyword.intern("doc"), doc);
        }
        if (defLine != null && (symMeta == null || !symMeta.containsKey(LINE_KW))) {
            meta = meta.assoc(LINE_KW, (long) defLine);
        }
        if (fileObj != null && (symMeta == null || !symMeta.containsKey(FILE_KW))) {
            meta = meta.assoc(FILE_KW, fileObj.toString());
        }
        v.setMeta(applyElideMeta(meta));
        return new DefNode(v, initNode, metaNode);
    }

    private static Node compileIf(ISeq args, Scope scope, boolean inTail) {
        int c = RT.count(args);
        if (c < 2 || c > 3) throw new IllegalArgumentException("if requires 2 or 3 arguments" + locSuffix());
        Node testNode = compile(args.first(), scope, false);
        ISeq s2 = args.next();
        Node thenNode = compile(s2.first(), scope, inTail);
        Node elseNode = (s2.next() != null) ? compile(s2.next().first(), scope, inTail) : ConstNode.NIL;
        return new IfNode(testNode, thenNode, elseNode);
    }

    private static Node compileDo(ISeq args, Scope scope, boolean inTail) {
        if (args == null || args.seq() == null) return ConstNode.NIL;
        int n = RT.count(args);
        if (n == 0) return ConstNode.NIL;
        Node[] nodes = new Node[n];
        int i = 0;
        for (ISeq s = args; s != null; s = s.next()) {
            boolean last = (i == n - 1);
            nodes[i++] = compile(s.first(), scope, inTail && last);
        }
        if (nodes.length == 1) return nodes[0];
        return new DoNode(nodes);
    }

    private static Node compileLet(ISeq args, Scope scope, boolean inTail) {
        PersistentVector rawBindings = (PersistentVector) args.first();
        PersistentVector bindings = destructure(rawBindings);
        ISeq bodyForms = args.next();

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);
        scope.pushBlock();
        try {
            int pairCount = bindings.count() / 2;
            int[] slots = new int[pairCount];
            Node[] inits = new Node[pairCount];
            for (int i = 0; i < pairCount; i++) {
                Symbol sym = (Symbol) bindings.nth(2 * i);
                Node init = compile(bindings.nth(2 * i + 1), scope, false);
                int slot = scope.allocateSlot(sym);
                if (isStaticDoubleNode(init)) {
                    scope.staticDoubleSlots.add(slot);
                }
                slots[i] = slot;
                inits[i] = init;
            }
            Node body = compileDo(bodyForms, scope, inTail);
            Node letNode = (pairCount == 0) ? body : new LetNode(slots, inits, body);
            if (isTopLevel) {
                return new TopFrameNode(scope.maxSlots, letNode);
            }
            return letNode;
        } finally {
            scope.popBlock();
        }
    }

    private static Node compileLetfn(ISeq args, Scope scope, boolean inTail) {
        PersistentVector rawBindings = (PersistentVector) args.first();
        if ((rawBindings.count() & 1) != 0) {
            throw new IllegalArgumentException("letfn* requires an even number of binding forms" + locSuffix());
        }
        ISeq bodyForms = args.next();

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);
        scope.pushBlock();
        try {
            int pairCount = rawBindings.count() / 2;
            int[] slots = new int[pairCount];
            Node[] inits = new Node[pairCount];
            // First pass: bind all fn names in lexical scope so siblings can capture each other
            for (int i = 0; i < pairCount; i++) {
                Object symObj = rawBindings.nth(2 * i);
                if (!(symObj instanceof Symbol sym)) {
                    throw new IllegalArgumentException("letfn* binding name must be a symbol: " + symObj + locSuffix());
                }
                slots[i] = scope.allocateSlot(sym);
            }
            // Second pass: compile each fn init expression with all sibling names visible
            for (int i = 0; i < pairCount; i++) {
                inits[i] = compile(rawBindings.nth(2 * i + 1), scope, false);
            }
            Node body = compileDo(bodyForms, scope, inTail);
            Node letfnNode = (pairCount == 0) ? body : new LetNode(slots, inits, body, true);
            if (isTopLevel) {
                return new TopFrameNode(scope.maxSlots, letfnNode);
            }
            return letfnNode;
        } finally {
            scope.popBlock();
        }
    }

    private static Node compileLoop(ISeq args, Scope scope) {
        PersistentVector rawBindings = (PersistentVector) args.first();
        if ((rawBindings.count() & 1) != 0) {
            throw new IllegalArgumentException("loop requires an even number of binding forms" + locSuffix());
        }
        ISeq bodyForms = args.next();

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);
        scope.pushBlock();
        try {
            int pairCount = rawBindings.count() / 2;
            int[] slots = new int[pairCount];
            Node[] inits = new Node[pairCount];
            List<Object> destructurePairs = new ArrayList<>();

            for (int i = 0; i < pairCount; i++) {
                Object pat = rawBindings.nth(2 * i);
                Object initForm = rawBindings.nth(2 * i + 1);
                Node init = compile(initForm, scope, false);
                if (pat instanceof Symbol sym) {
                    slots[i] = scope.allocateSlot(sym);
                } else {
                    Symbol tempSym = gensym("loop__");
                    slots[i] = scope.allocateSlot(tempSym);
                    destructurePairs.add(pat);
                    destructurePairs.add(tempSym);
                }
                inits[i] = init;
            }

            scope.recurTargets.push(slots);
            Node body;
            try {
                if (!destructurePairs.isEmpty()) {
                    PersistentVector dvec = PersistentVector.create(destructurePairs);
                    Object wrappedBody = RT.cons(Symbol.intern("let*"), RT.cons(dvec, bodyForms));
                    body = compile(wrappedBody, scope, true);
                } else {
                    body = compileDo(bodyForms, scope, true);
                }
            } finally {
                scope.recurTargets.pop();
            }

            Node loopNode = new LoopNode(slots, inits, body);
            if (isTopLevel) {
                return new TopFrameNode(scope.maxSlots, loopNode);
            }
            return loopNode;
        } finally {
            scope.popBlock();
        }
    }

    private static Node compileRecur(ISeq args, Scope scope, boolean inTail) {
        if (scope == null || scope.recurTargets.isEmpty()) {
            throw new IllegalArgumentException("Can only recur from inside fn or loop" + locSuffix());
        }
        if (!inTail) {
            throw new IllegalArgumentException("Can only recur from tail position" + locSuffix());
        }
        int[] targetSlots = scope.recurTargets.peek();
        Node[] exprs = compileArgs(args, scope);
        if (exprs.length != targetSlots.length) {
            throw new IllegalArgumentException(
                    "Mismatched argument count to recur, expected: " + targetSlots.length + " args, got: " + exprs.length + locSuffix());
        }
        if (scope.recurTargets.size() == 1) {
            scope.fnRecurUsed = true;
        }
        return switch (targetSlots.length) {
            case 0 -> Recur0Node.INSTANCE;
            case 1 -> new Recur1Node(targetSlots[0], exprs[0]);
            case 2 -> new Recur2Node(targetSlots[0], targetSlots[1], exprs[0], exprs[1]);
            default -> new RecurNNode(targetSlots, exprs);
        };
    }

    private static Node compileFn(ISeq args, Scope parentScope) {
        return compileFn(args, parentScope, false);
    }

    private static Node compileFn(ISeq args, Scope parentScope, boolean isMacroFn) {
        Symbol fnName = null;
        ISeq s = args;
        if (s.first() instanceof Symbol sym) {
            fnName = sym;
            s = s.next();
        }

        List<ISeq> methodForms = new ArrayList<>();
        if (s.first() instanceof PersistentVector) {
            methodForms.add(s);
        } else {
            for (ISeq ms = s; ms != null; ms = ms.next()) {
                methodForms.add((ISeq) ms.first());
            }
        }

        int maxFixedLimit = isMacroFn ? 22 : 20;
        FnMethod[] fixedMethods = new FnMethod[23];
        FnMethod variadicMethod = null;
        boolean anyCaptures = false;

        for (ISeq mform : methodForms) {
            FnMethod method = compileFnMethod(fnName, mform, parentScope);
            if (method.fixedArity > maxFixedLimit) {
                throw new IllegalArgumentException("Can't specify more than 20 params" + locSuffix());
            }
            if (method.capturedParentSlots.length > 0) {
                anyCaptures = true;
            }
            if (method.isVariadic) {
                if (variadicMethod != null) {
                    throw new IllegalArgumentException("Can't have more than 1 variadic overload" + locSuffix());
                }
                variadicMethod = method;
            } else {
                if (fixedMethods[method.fixedArity] != null) {
                    throw new IllegalArgumentException("Can't have 2 overloads with same arity" + locSuffix());
                }
                fixedMethods[method.fixedArity] = method;
            }
        }
        if (variadicMethod != null) {
            for (int i = variadicMethod.fixedArity + 1; i < fixedMethods.length; i++) {
                if (fixedMethods[i] != null) {
                    throw new IllegalArgumentException("Can't have fixed arity function with more params than variadic function" + locSuffix());
                }
            }
        }

        String displayName = fnName != null ? fnName.name : "fn";
        if (!anyCaptures) {
            // Pre-allocate stateless CompiledFn at compile time!
            return new ConstNode(new CompiledFn(displayName, fnName != null, fixedMethods, variadicMethod, null));
        }
        return new FnNode(displayName, fnName != null, fixedMethods, variadicMethod);
    }

    private static FnMethod compileFnMethod(Symbol fnName, ISeq mform, Scope parentScope) {
        PersistentVector rawParams = (PersistentVector) mform.first();
        ISeq bodyForms = mform.next();

        // Support Clojure {:pre [...] :post [...]} condition maps in fn/defn bodies
        if (bodyForms != null && bodyForms.next() != null && bodyForms.first() instanceof IPersistentMap condMap) {
            Object preConds = condMap.valAt(Keyword.intern("pre"));
            Object postConds = condMap.valAt(Keyword.intern("post"));
            ISeq realBody = bodyForms.next();
            if (preConds != null || postConds != null) {
                Object innerExpr = realBody.next() == null
                        ? realBody.first()
                        : RT.cons(Symbol.intern("do"), realBody);
                if (postConds != null) {
                    List<Object> postBody = new ArrayList<>();
                    for (ISeq ps = RT.seq(postConds); ps != null; ps = ps.next()) {
                        postBody.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "assert"), ps.first()));
                    }
                    postBody.add(Symbol.intern("%"));
                    innerExpr = PersistentList.createFromArray(
                            Symbol.intern("let*"),
                            PersistentVector.createOwning(Symbol.intern("%"), innerExpr),
                            RT.cons(Symbol.intern("do"), PersistentList.create(postBody)));
                }
                if (preConds != null) {
                    List<Object> preList = new ArrayList<>();
                    for (ISeq ps = RT.seq(preConds); ps != null; ps = ps.next()) {
                        preList.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "assert"), ps.first()));
                    }
                    preList.add(innerExpr);
                    bodyForms = PersistentList.create(preList);
                } else {
                    bodyForms = PersistentList.createFromArray(innerExpr);
                }
            }
        }

        Scope fnScope = new Scope(parentScope);
        int selfSlot = -1;
        if (fnName != null) {
            selfSlot = fnScope.allocateSelfSlot(fnName);
        }

        List<Symbol> paramSyms = new ArrayList<>();
        List<Object> destructurePairs = new ArrayList<>();
        boolean isVariadic = false;
        Symbol restSym = null;

        for (int i = 0; i < rawParams.count(); i++) {
            Object p = rawParams.nth(i);
            if (p instanceof Symbol s && "&".equals(s.name)) {
                if (i + 1 >= rawParams.count()) {
                    throw new IllegalArgumentException("Expected parameter after & in fn params" + locSuffix());
                }
                if (i + 2 != rawParams.count()) {
                    throw new IllegalArgumentException("Unexpected parameter after & in fn params" + locSuffix());
                }
                isVariadic = true;
                Object restPat = rawParams.nth(i + 1);
                if (restPat instanceof Symbol rs) {
                    restSym = rs;
                } else {
                    restSym = gensym("rest__");
                    destructurePairs.add(restPat);
                    destructurePairs.add(restSym);
                }
                break;
            } else if (p instanceof Symbol s) {
                paramSyms.add(s);
            } else {
                Symbol gs = gensym("p__");
                paramSyms.add(gs);
                destructurePairs.add(p);
                destructurePairs.add(gs);
            }
        }

        int fixedArity = paramSyms.size();
        int totalParamSlots = fixedArity + (isVariadic ? 1 : 0);
        int[] paramSlots = new int[totalParamSlots];
        for (int i = 0; i < fixedArity; i++) {
            paramSlots[i] = fnScope.allocateSlot(paramSyms.get(i));
        }
        if (isVariadic) {
            paramSlots[fixedArity] = fnScope.allocateSlot(restSym);
        }

        fnScope.recurTargets.push(paramSlots);
        Node bodyNode;
        try {
            if (!destructurePairs.isEmpty()) {
                PersistentVector dvec = PersistentVector.create(destructurePairs);
                Object wrapped = RT.cons(Symbol.intern("let*"), RT.cons(dvec, bodyForms));
                bodyNode = compile(wrapped, fnScope, true);
            } else {
                bodyNode = compileDo(bodyForms, fnScope, true);
            }
        } finally {
            fnScope.recurTargets.pop();
        }

        int[] capParentSlots = new int[fnScope.capturedParentSlots.size()];
        int[] capLocalSlots = new int[fnScope.capturedParentSlots.size()];
        int idx = 0;
        for (Map.Entry<Symbol, Integer> e : fnScope.capturedBySymbol.entrySet()) {
            capLocalSlots[idx] = e.getValue();
            capParentSlots[idx] = parentScope.resolveLocal(e.getKey());
            idx++;
        }

        ValType[] slotHints = fnScope.slotHints.toArray(new ValType[0]);
        ValType[] paramHints = new ValType[totalParamSlots];
        for (int i = 0; i < totalParamSlots; i++) {
            paramHints[i] = slotHints[paramSlots[i]];
        }
        ValType returnHint = extractTagHint(fnName);
        if (returnHint == ValType.ANY) {
            returnHint = extractTagHint(rawParams);
        }

        return new FnMethod(
                fixedArity,
                isVariadic,
                selfSlot,
                paramSlots,
                capParentSlots,
                capLocalSlots,
                fnScope.maxSlots,
                fnScope.fnRecurUsed,
                bodyNode,
                paramHints,
                slotHints,
                returnHint);
    }

    private static Node compileDefmacro(ISeq args, Scope scope) {
        Integer defLine = CURRENT_LINE.get();
        Object fileObj = RT.FILE.get();
        Symbol name = (Symbol) args.first();
        ISeq rest = args.next();
        String doc = null;
        if (rest.first() instanceof String s && rest.next() != null) {
            doc = s;
            rest = rest.next();
        }
        IPersistentMap attrMap = null;
        if (rest.first() instanceof IPersistentMap m && rest.next() != null) {
            attrMap = m;
            rest = rest.next();
        }
        ISeq arglists = extractArglists(rest);
        Node fnNode = compileFn(RT.cons(name, prependMacroParams(rest)), scope, true);
        Namespace ns = RT.currentNS();
        PersistentHashMap symMeta = name.meta();
        Var v = ns.intern(name);
        v.setMacro();
        PersistentHashMap meta = v.meta()
                .assoc(Keyword.intern("ns"), ns)
                .assoc(Keyword.intern("name"), name.withMeta(null));
        if (attrMap != null) {
            for (ISeq s = attrMap.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                meta = meta.assoc(me.getKey(), me.getValue());
            }
        }
        if (doc != null) {
            meta = meta.assoc(Keyword.intern("doc"), doc);
        }
        if (arglists != null) {
            meta = meta.assoc(Keyword.intern("arglists"), arglists);
        }
        if (defLine != null && (symMeta == null || !symMeta.containsKey(LINE_KW))
                && (attrMap == null || !attrMap.containsKey(LINE_KW))) {
            meta = meta.assoc(LINE_KW, (long) defLine);
        }
        if (fileObj != null && (symMeta == null || !symMeta.containsKey(FILE_KW))
                && (attrMap == null || !attrMap.containsKey(FILE_KW))) {
            meta = meta.assoc(FILE_KW, fileObj.toString());
        }
        v.setMeta(applyElideMeta(meta));
        return new DefMacroNode(v, fnNode);
    }

    private static ISeq extractArglists(ISeq bodyForms) {
        if (bodyForms == null) return null;
        if (bodyForms.first() instanceof PersistentVector vec) {
            return PersistentList.createFromArray(vec);
        }
        List<Object> lists = new ArrayList<>();
        for (ISeq s = bodyForms; s != null; s = s.next()) {
            if (s.first() instanceof ISeq clause && clause.first() != null) {
                lists.add(clause.first());
            }
        }
        return lists.isEmpty() ? null : PersistentList.create(lists);
    }

    private static PersistentVector prependFormEnvVec(PersistentVector vec) {
        Object[] items = new Object[vec.count() + 2];
        items[0] = Symbol.intern("&form");
        items[1] = Symbol.intern("&env");
        for (int i = 0; i < vec.count(); i++) {
            items[i + 2] = vec.nth(i);
        }
        PersistentVector out = PersistentVector.createOwning(items);
        return vec.meta() != null ? out.withMeta(vec.meta()) : out;
    }

    private static ISeq prependMacroParams(ISeq rest) {
        if (rest == null) return null;
        if (rest.first() instanceof PersistentVector vec) {
            return RT.cons(prependFormEnvVec(vec), rest.next());
        }
        List<Object> clauses = new ArrayList<>();
        for (ISeq s = rest; s != null; s = s.next()) {
            Object item = s.first();
            if (item instanceof ISeq clause && clause.first() instanceof PersistentVector vec) {
                clauses.add(RT.cons(prependFormEnvVec(vec), clause.next()));
            }
        }
        return PersistentList.create(clauses);
    }

    private static Node compileTry(ISeq args, Scope scope) {
        List<Object> bodyForms = new ArrayList<>();
        List<CatchClause> catches = new ArrayList<>();
        ISeq finallyForms = null;

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);

        boolean seenCatch = false;
        boolean seenFinally = false;

        for (ISeq s = args; s != null; s = s.next()) {
            Object f = s.first();
            if (f instanceof ISeq clause && clause.seq() != null) {
                Object head = clause.first();
                if (Symbol.intern("catch").equals(head)) {
                    if (seenFinally) {
                        throw new IllegalArgumentException("catch clause cannot follow finally in try" + locSuffix());
                    }
                    seenCatch = true;
                    ISeq cargs = clause.next();
                    Symbol classSym = (Symbol) cargs.first();
                    Class<?> exClass = Interop.resolveClass(RT.currentNS(), classSym.toString());
                    if (exClass == null) throw new RuntimeException("Unable to resolve exception class: " + classSym + locSuffix());
                    Symbol exVar = (Symbol) cargs.next().first();
                    ISeq cbody = cargs.next().next();
                    scope.pushBlock();
                    int slot = scope.allocateSlot(exVar);
                    Node handler = compileDo(cbody, scope, false);
                    scope.popBlock();
                    catches.add(new CatchClause(exClass, slot, handler));
                    continue;
                } else if (Symbol.intern("finally").equals(head)) {
                    if (seenFinally) {
                        throw new IllegalArgumentException("Only one finally clause allowed in try" + locSuffix());
                    }
                    seenFinally = true;
                    finallyForms = clause.next();
                    continue;
                }
            }
            if (seenCatch || seenFinally) {
                throw new IllegalArgumentException("Only catch or finally clauses can follow catch/finally in try" + locSuffix());
            }
            bodyForms.add(f);
        }

        Node bodyNode = bodyForms.isEmpty() ? ConstNode.NIL : compileDo(PersistentList.create(bodyForms), scope, false);
        Node finallyNode = finallyForms != null ? compileDo(finallyForms, scope, false) : null;
        Node tryNode = new TryNode(bodyNode, catches.toArray(new CatchClause[0]), finallyNode);
        if (isTopLevel && scope.maxSlots > 0) {
            return new TopFrameNode(scope.maxSlots, tryNode);
        }
        return tryNode;
    }

    private static Node compileNew(ISeq args, Scope scope) {
        Object classSpec = args.first();
        Class<?> cls;
        if (classSpec instanceof Symbol sym) {
            cls = Interop.resolveClass(RT.currentNS(), sym.toString());
            if (cls == null) throw new RuntimeException("Unable to resolve class: " + sym + locSuffix());
        } else if (classSpec instanceof Class<?> c) {
            cls = c;
        } else {
            throw new IllegalArgumentException("new requires a Class symbol" + locSuffix());
        }
        Node[] argNodes = compileArgs(args.next(), scope);
        return new NewNode(cls, argNodes);
    }

    private static Node compileDot(ISeq args, Scope scope) {
        Object targetForm = args.first();
        ISeq rest = args.next();
        Object memberPart = rest.first();
        String memberName;
        Symbol memberSym;
        ISeq argForms;

        if (memberPart instanceof ISeq mseq) {
            memberSym = (Symbol) mseq.first();
            memberName = memberSym.name;
            argForms = mseq.next();
        } else if (memberPart instanceof Symbol msym) {
            memberSym = msym;
            memberName = msym.name;
            argForms = rest.next();
        } else {
            throw new IllegalArgumentException("Malformed . member expression" + locSuffix());
        }

        Class<?>[] paramTags = extractParamTags(memberSym, RT.currentNS());
        if (paramTags == null && memberPart instanceof IMeta im && im.meta() != null) {
            Object pt = im.meta().valAt(PARAM_TAGS_KW);
            if (pt != null) paramTags = Interop.resolveParamTags(RT.currentNS(), pt);
        }

        boolean isField = memberName.startsWith("-");
        if (isField) {
            memberName = memberName.substring(1);
        }

        Node[] argNodes = compileArgs(argForms, scope);

        // Check if targetForm is a static Class symbol
        if (targetForm instanceof Symbol sym && sym.ns == null && (scope == null || scope.resolveLocal(sym) == -1)) {
            Var v = lookupVar(RT.currentNS(), sym, false);
            if (v == null) {
                Class<?> cls = Interop.resolveClass(RT.currentNS(), sym.name);
                if (cls != null) {
                    if (isField && argNodes.length == 0) {
                        return new StaticFieldNode(cls, memberName);
                    }
                    return createStaticMethodNode(cls, memberName, argNodes, paramTags);
                }
            }
        }

        Node targetNode = compile(targetForm, scope, false);
        Class<?> hintedTarget = resolveTargetHintClass(targetForm, targetNode, scope);
        if (hintedTarget == null && paramTags == null) {
            warnReflection((isField ? "reference to field " : "call to method ") + memberName + " can't be resolved (target class is unknown).");
        }
        if (isField && argNodes.length == 0) {
            return new InstanceFieldNode(targetNode, memberName);
        }
        return new InstanceMethodNode(targetNode, memberName, argNodes, null, paramTags);
    }

    private static Class<?> resolveTargetHintClass(Object targetForm, Node targetNode, Scope scope) {
        if (targetForm instanceof IMeta im && im.meta() != null) {
            Object tag = im.meta().valAt(TAG_KW);
            if (tag instanceof Symbol ts) {
                Class<?> c = Interop.resolveClass(RT.currentNS(), ts.toString());
                if (c != null) return c;
            } else if (tag instanceof String s) {
                Class<?> c = Interop.resolveClass(RT.currentNS(), s);
                if (c != null) return c;
            } else if (tag instanceof Class<?> c) {
                return c;
            }
        }
        if (targetNode instanceof LocalNode ln && scope != null && ln.slot >= 0 && ln.slot < scope.slotTags.size()) {
            Symbol tagSym = scope.slotTags.get(ln.slot);
            if (tagSym != null) {
                return Interop.resolveClass(RT.currentNS(), tagSym.toString());
            }
        }
        if (targetNode instanceof ConstNode cn && cn.val != null) {
            return cn.val.getClass();
        }
        if (targetNode instanceof NewNode nn) {
            return nn.clazz;
        }
        return null;
    }

    private static Node createStaticMethodNode(Class<?> cls, String name, Node[] argNodes) {
        return createStaticMethodNode(cls, name, argNodes, null);
    }

    private static Node createStaticMethodNode(Class<?> cls, String name, Node[] argNodes, Class<?>[] paramTags) {
        if (paramTags == null && (cls == Math.class || cls == StrictMath.class)) {
            Object folded = tryFoldMathStatic(cls == StrictMath.class, name, argNodes);
            if (folded != null) {
                return new ConstNode(folded);
            }
        }
        return new StaticMethodNode(cls, name, argNodes, paramTags);
    }

    private static Object tryFoldMathStatic(boolean strict, String name, Node[] args) {
        int n = args.length;
        if (n == 1 && args[0] instanceof ConstNode c0 && c0.val instanceof Number num0) {
            if ("abs".equals(name)) {
                if (num0 instanceof Long l) {
                    long v = l.longValue();
                    return v == Long.MIN_VALUE ? null : Long.valueOf(Math.abs(v));
                }
                if (num0 instanceof Double d) return Double.valueOf(Math.abs(d.doubleValue()));
                return null;
            }
            if ("round".equals(name)) {
                if (num0 instanceof Double d) return Long.valueOf(strict ? StrictMath.round(d.doubleValue()) : Math.round(d.doubleValue()));
                return null;
            }
            double d = num0.doubleValue();
            return switch (name) {
                case "sin" -> Double.valueOf(strict ? StrictMath.sin(d) : Math.sin(d));
                case "cos" -> Double.valueOf(strict ? StrictMath.cos(d) : Math.cos(d));
                case "tan" -> Double.valueOf(strict ? StrictMath.tan(d) : Math.tan(d));
                case "asin" -> Double.valueOf(strict ? StrictMath.asin(d) : Math.asin(d));
                case "acos" -> Double.valueOf(strict ? StrictMath.acos(d) : Math.acos(d));
                case "atan" -> Double.valueOf(strict ? StrictMath.atan(d) : Math.atan(d));
                case "sinh" -> Double.valueOf(strict ? StrictMath.sinh(d) : Math.sinh(d));
                case "cosh" -> Double.valueOf(strict ? StrictMath.cosh(d) : Math.cosh(d));
                case "tanh" -> Double.valueOf(strict ? StrictMath.tanh(d) : Math.tanh(d));
                case "exp" -> Double.valueOf(strict ? StrictMath.exp(d) : Math.exp(d));
                case "expm1" -> Double.valueOf(strict ? StrictMath.expm1(d) : Math.expm1(d));
                case "log" -> Double.valueOf(strict ? StrictMath.log(d) : Math.log(d));
                case "log10" -> Double.valueOf(strict ? StrictMath.log10(d) : Math.log10(d));
                case "log1p" -> Double.valueOf(strict ? StrictMath.log1p(d) : Math.log1p(d));
                case "sqrt" -> Double.valueOf(strict ? StrictMath.sqrt(d) : Math.sqrt(d));
                case "cbrt" -> Double.valueOf(strict ? StrictMath.cbrt(d) : Math.cbrt(d));
                case "ceil" -> Double.valueOf(strict ? StrictMath.ceil(d) : Math.ceil(d));
                case "floor" -> Double.valueOf(strict ? StrictMath.floor(d) : Math.floor(d));
                case "rint" -> Double.valueOf(strict ? StrictMath.rint(d) : Math.rint(d));
                case "toRadians" -> Double.valueOf(strict ? StrictMath.toRadians(d) : Math.toRadians(d));
                case "toDegrees" -> Double.valueOf(strict ? StrictMath.toDegrees(d) : Math.toDegrees(d));
                case "ulp" -> Double.valueOf(strict ? StrictMath.ulp(d) : Math.ulp(d));
                case "signum" -> Double.valueOf(strict ? StrictMath.signum(d) : Math.signum(d));
                case "nextUp" -> Double.valueOf(strict ? StrictMath.nextUp(d) : Math.nextUp(d));
                case "nextDown" -> Double.valueOf(strict ? StrictMath.nextDown(d) : Math.nextDown(d));
                default -> null;
            };
        }
        if (n == 2 && args[0] instanceof ConstNode c0 && c0.val instanceof Number num0
                && args[1] instanceof ConstNode c1 && c1.val instanceof Number num1) {
            if ("min".equals(name)) {
                if (num0 instanceof Long a && num1 instanceof Long b) return Long.valueOf(Math.min(a, b));
                if (num0 instanceof Double || num1 instanceof Double) return Double.valueOf(Math.min(num0.doubleValue(), num1.doubleValue()));
                return null;
            }
            if ("max".equals(name)) {
                if (num0 instanceof Long a && num1 instanceof Long b) return Long.valueOf(Math.max(a, b));
                if (num0 instanceof Double || num1 instanceof Double) return Double.valueOf(Math.max(num0.doubleValue(), num1.doubleValue()));
                return null;
            }
            double a = num0.doubleValue();
            double b = num1.doubleValue();
            return switch (name) {
                case "atan2" -> Double.valueOf(strict ? StrictMath.atan2(a, b) : Math.atan2(a, b));
                case "pow" -> Double.valueOf(strict ? StrictMath.pow(a, b) : Math.pow(a, b));
                case "hypot" -> Double.valueOf(strict ? StrictMath.hypot(a, b) : Math.hypot(a, b));
                case "IEEEremainder" -> Double.valueOf(strict ? StrictMath.IEEEremainder(a, b) : Math.IEEEremainder(a, b));
                case "copySign" -> Double.valueOf(strict ? StrictMath.copySign(a, b) : Math.copySign(a, b));
                case "nextAfter" -> Double.valueOf(strict ? StrictMath.nextAfter(a, b) : Math.nextAfter(a, b));
                default -> null;
            };
        }
        if (n == 3 && "fma".equals(name)
                && args[0] instanceof ConstNode c0 && c0.val instanceof Number n0
                && args[1] instanceof ConstNode c1 && c1.val instanceof Number n1
                && args[2] instanceof ConstNode c2 && c2.val instanceof Number n2) {
            double a = n0.doubleValue(), b = n1.doubleValue(), c = n2.doubleValue();
            return Double.valueOf(strict ? StrictMath.fma(a, b, c) : Math.fma(a, b, c));
        }
        return null;
    }

    private static Node compileSetBang(ISeq args, Scope scope) {
        Object target = args.first();
        Node valNode = compile(RT.second(args), scope, false);
        if (target instanceof Symbol sym) {
            Var v = lookupVar(RT.currentNS(), sym, false);
            if (v != null) return new SetVarNode(v, valNode);
            if (sym.ns != null) {
                Class<?> cls = Interop.resolveClass(RT.currentNS(), sym.ns);
                if (cls != null) return new SetStaticFieldNode(cls, sym.name, valNode);
            }
            throw new RuntimeException("Unable to resolve var in set!: " + sym + locSuffix());
        }
        if (target instanceof ISeq seq) {
            Object expanded = macroexpand(seq, scope);
            if (expanded instanceof ISeq dotSeq && Symbol.intern(".").equals(dotSeq.first())) {
                Object objForm = RT.second(dotSeq);
                Object memForm = RT.nth(dotSeq, 2);
                String fieldName = memForm.toString();
                if (fieldName.startsWith("-")) fieldName = fieldName.substring(1);
                if (objForm instanceof Symbol objSym && objSym.ns == null && (scope == null || scope.resolveLocal(objSym) == -1)) {
                    Var v = lookupVar(RT.currentNS(), objSym, false);
                    if (v == null) {
                        Class<?> cls = Interop.resolveClass(RT.currentNS(), objSym.name);
                        if (cls != null) return new SetStaticFieldNode(cls, fieldName, valNode);
                    }
                }
                Node objNode = compile(objForm, scope, false);
                return new SetFieldNode(objNode, fieldName, valNode);
            }
        }
        throw new IllegalArgumentException("Invalid assignment target for set!: " + target + locSuffix());
    }

    private static Node compileCase(ISeq args, Scope scope, boolean inTail) {
        int argCount = RT.count(args);
        if (argCount >= 7) {
            // Official Clojure 1.12 signature:
            // (case* ge shift mask default imap switch-type test-type skip-check?)
            Node exprNode = compile(RT.nth(args, 0), scope, false);
            int shift = (int) Numbers.toLong(RT.nth(args, 1));
            int mask = (int) Numbers.toLong(RT.nth(args, 2));
            Node defaultNode = compile(RT.nth(args, 3), scope, inTail);
            IPersistentMap imap = (IPersistentMap) RT.nth(args, 4);
            Object testType = RT.nth(args, 6);
            Object skipCheckObj = argCount >= 8 ? RT.nth(args, 7) : null;
            boolean hasSkipCheck = skipCheckObj != null && RT.count(skipCheckObj) > 0;
            if (!hasSkipCheck) {
                Map<RT.EquivKey, Node> table = new HashMap<>();
                for (ISeq s = imap.seq(); s != null; s = s.next()) {
                    Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                    Object pair = me.getValue();
                    Object testConst = RT.first(pair);
                    Node thenNode = compile(RT.second(pair), scope, inTail);
                    table.put(new RT.EquivKey(testConst), thenNode);
                }
                return new CaseNode(exprNode, table, true, defaultNode);
            }
            // Hash/shift/mask mode with skip-check (handles hash-collision buckets)
            Map<Integer, Object> tests = new HashMap<>();
            Map<Integer, Node> thens = new HashMap<>();
            Set<Integer> skipSet = new HashSet<>();
            for (ISeq sc = RT.seq(skipCheckObj); sc != null; sc = sc.next()) {
                skipSet.add((int) Numbers.toLong(sc.first()));
            }
            for (ISeq s = imap.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> me = (Map.Entry<?, ?>) s.first();
                int bucket = (int) Numbers.toLong(me.getKey());
                Object pair = me.getValue();
                tests.put(bucket, RT.first(pair));
                thens.put(bucket, compile(RT.second(pair), scope, inTail));
            }
            return new CaseNode(exprNode, shift, mask, testType, tests, thens, skipSet, defaultNode);
        }
        // (case* expr [k1 v1 k2 v2 ...] default-opt)
        Node exprNode = compile(args.first(), scope, false);
        PersistentVector pairs = (PersistentVector) RT.second(args);
        boolean hasDefault = argCount >= 3;
        Node defaultNode = hasDefault ? compile(RT.nth(args, 2), scope, inTail) : null;

        Map<RT.EquivKey, Node> table = new HashMap<>();
        for (int i = 0; i < pairs.count(); i += 2) {
            Object k = pairs.nth(i);
            Node vNode = compile(pairs.nth(i + 1), scope, inTail);
            if (k instanceof ISeq ks && !Symbol.intern("quote").equals(ks.first())) {
                for (ISeq s = ks; s != null; s = s.next()) {
                    table.put(new RT.EquivKey(s.first()), vNode);
                }
            } else {
                table.put(new RT.EquivKey(k), vNode);
            }
        }
        return new CaseNode(exprNode, table, hasDefault, defaultNode);
    }

    // ========================================================================
    // AST Nodes
    // ========================================================================

    public static final Node[] EMPTY_NODES = new Node[0];

    public abstract static class Node {
        public abstract Object eval(Object[] frame);

        public long evalLong(Object[] frame) {
            return Numbers.toLong(eval(frame));
        }

        public boolean evalBoolean(Object[] frame) {
            return RT.truthy(eval(frame));
        }
    }

    public static final class ConstNode extends Node {
        public static final ConstNode NIL = new ConstNode(null);
        public static final ConstNode TRUE = new ConstNode(Boolean.TRUE);
        public static final ConstNode FALSE = new ConstNode(Boolean.FALSE);

        public final Object val;
        public final long longVal;
        public final double doubleVal;
        public final boolean isLong;
        public final boolean isDouble;
        public final boolean boolVal;

        public ConstNode(Object val) {
            this.val = val;
            this.isLong = val instanceof Long;
            this.isDouble = val instanceof Double;
            this.longVal = isLong ? (Long) val : 0L;
            this.doubleVal = isDouble ? (Double) val : 0.0;
            this.boolVal = RT.truthy(val);
        }

        @Override public Object eval(Object[] frame) { return val; }
        @Override public long evalLong(Object[] frame) { return isLong ? longVal : Numbers.toLong(val); }
        @Override public boolean evalBoolean(Object[] frame) { return boolVal; }
    }

    public static final class LocalNode extends Node {
        public final int slot;
        public final ValType hint;
        public final boolean staticDouble;
        public LocalNode(int slot) { this(slot, ValType.ANY, false); }
        public LocalNode(int slot, ValType hint) { this(slot, hint, false); }
        public LocalNode(int slot, ValType hint, boolean staticDouble) {
            this.slot = slot;
            this.hint = hint != null ? hint : ValType.ANY;
            this.staticDouble = staticDouble;
        }
        @Override public Object eval(Object[] frame) { return frame[slot]; }
    }

    public static final class VarRefNode extends Node {
        public final Var var;
        public final ValType hint;
        public VarRefNode(Var var) { this(var, ValType.ANY); }
        public VarRefNode(Var var, ValType hint) {
            this.var = var;
            this.hint = hint != null ? hint : ValType.ANY;
        }
        @Override public Object eval(Object[] frame) { return var.deref(); }
    }

    public static final class DefNode extends Node {
        public final Var var;
        public final Node init;
        public final Node metaNode;
        public DefNode(Var var, Node init) { this(var, init, null); }
        public DefNode(Var var, Node init, Node metaNode) {
            this.var = var;
            this.init = init;
            this.metaNode = metaNode;
        }
        @Override
        public Object eval(Object[] frame) {
            if (init != null) {
                Object val = init.eval(frame);
                if (val instanceof CompiledFn cfn) {
                    cfn.setOwnerVar(var);
                }
                var.bindRoot(val);
            }
            if (metaNode != null) {
                Object mv = metaNode.eval(frame);
                if (mv instanceof IPersistentMap pm) {
                    var.mergeMeta(PersistentHashMap.fromMap(pm));
                }
            }
            return var;
        }
    }

    public static final class DefMacroNode extends Node {
        public final Var var;
        public final Node fnNode;
        public DefMacroNode(Var var, Node fnNode) { this.var = var; this.fnNode = fnNode; }
        @Override
        public Object eval(Object[] frame) {
            var.bindRoot(fnNode.eval(frame));
            var.setMacro();
            return var;
        }
    }

    private static final ReferenceQueue<Object> MONITOR_QUEUE = new ReferenceQueue<>();
    private static final ConcurrentHashMap<WeakIdKey, java.util.concurrent.locks.ReentrantLock> MONITORS =
            new ConcurrentHashMap<>();

    private static final class WeakIdKey extends WeakReference<Object> {
        final int hash;

        WeakIdKey(Object referent, ReferenceQueue<Object> q) {
            super(referent, q);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof WeakIdKey other)) return false;
            Object a = this.get();
            Object b = other.get();
            return a != null && a == b;
        }
    }

    private static void expungeMonitors() {
        WeakIdKey ref;
        while ((ref = (WeakIdKey) MONITOR_QUEUE.poll()) != null) {
            MONITORS.remove(ref);
        }
    }

    public static Object monitorOp(Object obj, boolean enter) {
        if (obj == null) throw new NullPointerException("Cannot lock nil");
        expungeMonitors();
        WeakIdKey key = new WeakIdKey(obj, MONITOR_QUEUE);
        java.util.concurrent.locks.ReentrantLock lock = MONITORS.computeIfAbsent(key,
                k -> new java.util.concurrent.locks.ReentrantLock());
        if (enter) lock.lock();
        else lock.unlock();
        return null;
    }

    public static final class MonitorNode extends Node {
        public final Node target;
        public final boolean enter;
        public MonitorNode(Node target, boolean enter) { this.target = target; this.enter = enter; }
        @Override
        public Object eval(Object[] frame) {
            return monitorOp(target.eval(frame), enter);
        }
    }

    public static final class LockingNode extends Node {
        public final Node target;
        public final Node body;
        public LockingNode(Node target, Node body) { this.target = target; this.body = body; }
        @Override
        public Object eval(Object[] frame) {
            Object lock = target.eval(frame);
            if (lock == null) throw new NullPointerException("Cannot lock nil");
            synchronized (lock) {
                return body.eval(frame);
            }
        }
    }

    public static final class IfNode extends Node {
        public final Node test;
        public final Node thenNode;
        public final Node elseNode;

        public IfNode(Node test, Node thenNode, Node elseNode) {
            this.test = test;
            this.thenNode = thenNode;
            this.elseNode = elseNode;
        }

        @Override
        public Object eval(Object[] frame) {
            return test.evalBoolean(frame) ? thenNode.eval(frame) : elseNode.eval(frame);
        }

        @Override
        public long evalLong(Object[] frame) {
            return test.evalBoolean(frame) ? thenNode.evalLong(frame) : elseNode.evalLong(frame);
        }

        @Override
        public boolean evalBoolean(Object[] frame) {
            return test.evalBoolean(frame) ? thenNode.evalBoolean(frame) : elseNode.evalBoolean(frame);
        }
    }

    public static final class DoNode extends Node {
        public final Node[] nodes;
        public DoNode(Node[] nodes) { this.nodes = nodes; }
        @Override
        public Object eval(Object[] frame) {
            if (nodes.length == 0) return null;
            int last = nodes.length - 1;
            for (int i = 0; i < last; i++) {
                nodes[i].eval(frame);
            }
            return nodes[last].eval(frame);
        }
    }

    public static final class TopFrameNode extends Node {
        public final int maxSlots;
        public final Node body;
        public TopFrameNode(int maxSlots, Node body) {
            this.maxSlots = maxSlots;
            this.body = body;
        }
        @Override
        public Object eval(Object[] ignored) {
            return body.eval(new Object[maxSlots]);
        }
    }

    public static void backpatchLetfn(int[] slots, Object[] vals) {
        for (int i = 0; i < vals.length; i++) {
            if (vals[i] instanceof CompiledFn cfn) {
                cfn.patchCaptured(slots, vals);
            }
        }
    }

    public static final class LetNode extends Node {
        public final int[] slots;
        public final Node[] inits;
        public final Node body;
        public final boolean isLetfn;

        public LetNode(int[] slots, Node[] inits, Node body) {
            this(slots, inits, body, false);
        }

        public LetNode(int[] slots, Node[] inits, Node body, boolean isLetfn) {
            this.slots = slots;
            this.inits = inits;
            this.body = body;
            this.isLetfn = isLetfn;
        }

        @Override
        public Object eval(Object[] frame) {
            if (isLetfn) {
                Object[] vals = new Object[slots.length];
                for (int i = 0; i < slots.length; i++) {
                    frame[slots[i]] = null;
                }
                for (int i = 0; i < slots.length; i++) {
                    Object v = inits[i].eval(frame);
                    frame[slots[i]] = v;
                    vals[i] = v;
                }
                backpatchLetfn(slots, vals);
                return body.eval(frame);
            }
            for (int i = 0; i < slots.length; i++) {
                frame[slots[i]] = inits[i].eval(frame);
            }
            return body.eval(frame);
        }
    }

    public static final class LoopNode extends Node {
        public final int[] slots;
        public final Node[] inits;
        public final Node body;

        public LoopNode(int[] slots, Node[] inits, Node body) {
            this.slots = slots;
            this.inits = inits;
            this.body = body;
        }

        @Override
        public Object eval(Object[] frame) {
            for (int i = 0; i < slots.length; i++) {
                frame[slots[i]] = inits[i].eval(frame);
            }
            while (true) {
                Object res = body.eval(frame);
                if (res != RECUR_SENTINEL) return res;
            }
        }
    }

    public static final class Recur0Node extends Node {
        public static final Recur0Node INSTANCE = new Recur0Node();
        @Override public Object eval(Object[] frame) { return RECUR_SENTINEL; }
    }

    public static final class Recur1Node extends Node {
        public final int slot0;
        public final Node expr0;
        public Recur1Node(int slot0, Node expr0) {
            this.slot0 = slot0;
            this.expr0 = expr0;
        }
        @Override
        public Object eval(Object[] frame) {
            frame[slot0] = expr0.eval(frame);
            return RECUR_SENTINEL;
        }
    }

    public static final class Recur2Node extends Node {
        public final int slot0, slot1;
        public final Node expr0, expr1;
        public Recur2Node(int slot0, int slot1, Node expr0, Node expr1) {
            this.slot0 = slot0;
            this.slot1 = slot1;
            this.expr0 = expr0;
            this.expr1 = expr1;
        }
        @Override
        public Object eval(Object[] frame) {
            Object v0 = expr0.eval(frame);
            Object v1 = expr1.eval(frame);
            frame[slot0] = v0;
            frame[slot1] = v1;
            return RECUR_SENTINEL;
        }
    }

    public static final class RecurNNode extends Node {
        public final int[] slots;
        public final Node[] exprs;
        public RecurNNode(int[] slots, Node[] exprs) {
            this.slots = slots;
            this.exprs = exprs;
        }
        @Override
        public Object eval(Object[] frame) {
            int n = slots.length;
            Object[] temp = new Object[n];
            for (int i = 0; i < n; i++) {
                temp[i] = exprs[i].eval(frame);
            }
            for (int i = 0; i < n; i++) {
                frame[slots[i]] = temp[i];
            }
            return RECUR_SENTINEL;
        }
    }

    public static final class VectorNode extends Node {
        public final Node[] elems;
        public VectorNode(Node[] elems) { this.elems = elems; }
        @Override
        public Object eval(Object[] frame) {
            Object[] arr = new Object[elems.length];
            for (int i = 0; i < elems.length; i++) arr[i] = elems[i].eval(frame);
            return PersistentVector.createOwning(arr);
        }
    }

    public static final class MapNode extends Node {
        public final Node[] kvs;
        public MapNode(Node[] kvs) { this.kvs = kvs; }
        @Override
        public Object eval(Object[] frame) {
            Object[] arr = new Object[kvs.length];
            for (int i = 0; i < kvs.length; i++) arr[i] = kvs[i].eval(frame);
            return RT.mapUniqueKeys(arr);
        }
    }

    public static final class SetNode extends Node {
        public final Node[] elems;
        public SetNode(Node[] elems) { this.elems = elems; }
        @Override
        public Object eval(Object[] frame) {
            Object[] arr = new Object[elems.length];
            for (int i = 0; i < elems.length; i++) arr[i] = elems[i].eval(frame);
            return PersistentHashSet.create(arr);
        }
    }

    // ========================================================================
    // Function & Closure Compilation
    // ========================================================================

    public static final class FnMethod {
        public final int fixedArity;
        public final boolean isVariadic;
        public final int selfSlot;
        public final int[] paramSlots;
        public final int[] capturedParentSlots;
        public final int[] capturedLocalSlots;
        public final int maxSlots;
        public final boolean hasRecur;
        public final Node body;
        public final ValType[] paramHints;
        public final ValType[] slotHints;
        public final ValType returnHint;

        public FnMethod(
                int fixedArity,
                boolean isVariadic,
                int selfSlot,
                int[] paramSlots,
                int[] capturedParentSlots,
                int[] capturedLocalSlots,
                int maxSlots,
                boolean hasRecur,
                Node body,
                ValType[] paramHints,
                ValType[] slotHints,
                ValType returnHint) {
            this.fixedArity = fixedArity;
            this.isVariadic = isVariadic;
            this.selfSlot = selfSlot;
            this.paramSlots = paramSlots;
            this.capturedParentSlots = capturedParentSlots;
            this.capturedLocalSlots = capturedLocalSlots;
            this.maxSlots = maxSlots;
            this.hasRecur = hasRecur;
            this.body = body;
            this.paramHints = paramHints;
            this.slotHints = slotHints;
            this.returnHint = returnHint != null ? returnHint : ValType.ANY;
        }

        public Object[] initFrame(Fn self, Object[][] capturedByMethod, int methodIdx) {
            if (maxSlots == 0) return Fn.EMPTY_ARGS;
            Object[] frame = new Object[maxSlots];
            if (selfSlot != -1) frame[selfSlot] = self;
            if (capturedByMethod != null) {
                Object[] caps = capturedByMethod[methodIdx];
                if (caps != null) {
                    for (int i = 0; i < capturedLocalSlots.length; i++) {
                        frame[capturedLocalSlots[i]] = caps[i];
                    }
                }
            }
            return frame;
        }

        public Object runBody(Object[] frame) {
            if (!hasRecur) {
                return body.eval(frame);
            }
            while (true) {
                Object res = body.eval(frame);
                if (res != RECUR_SENTINEL) return res;
            }
        }
    }

    public static final class FnNode extends Node {
        public final String name;
        public final boolean isNamed;
        public final FnMethod[] fixedMethods;
        public final FnMethod variadicMethod;

        public FnNode(String name, boolean isNamed, FnMethod[] fixedMethods, FnMethod variadicMethod) {
            this.name = name;
            this.isNamed = isNamed;
            this.fixedMethods = fixedMethods;
            this.variadicMethod = variadicMethod;
        }

        @Override
        public Object eval(Object[] outerFrame) {
            Object[][] captured = new Object[fixedMethods.length + 1][];
            for (int i = 0; i < fixedMethods.length; i++) {
                FnMethod m = fixedMethods[i];
                if (m != null && m.capturedParentSlots.length > 0) {
                    Object[] caps = new Object[m.capturedParentSlots.length];
                    for (int j = 0; j < caps.length; j++) {
                        caps[j] = outerFrame[m.capturedParentSlots[j]];
                    }
                    captured[i] = caps;
                }
            }
            if (variadicMethod != null && variadicMethod.capturedParentSlots.length > 0) {
                Object[] caps = new Object[variadicMethod.capturedParentSlots.length];
                for (int j = 0; j < caps.length; j++) {
                    caps[j] = outerFrame[variadicMethod.capturedParentSlots[j]];
                }
                captured[fixedMethods.length] = caps;
            }
            return new CompiledFn(name, isNamed, fixedMethods, variadicMethod, captured);
        }
    }

    public static final class CompiledFn extends Fn implements clojure.lang.Fn, IObj {
        private static final int JIT_THRESHOLD = 16;

        private final String name;
        private final boolean isNamed;
        private final FnMethod[] fixedMethods;
        private final FnMethod variadicMethod;
        private final Object[][] captured;
        private final PersistentHashMap meta;
        private volatile Var ownerVar;
        private volatile Fn jitFn;
        private final AtomicInteger calls = new AtomicInteger(0);
        private volatile boolean jitAttempted;

        public CompiledFn(
                String name,
                boolean isNamed,
                FnMethod[] fixedMethods,
                FnMethod variadicMethod,
                Object[][] captured) {
            this(name, isNamed, fixedMethods, variadicMethod, captured, null);
        }

        public CompiledFn(
                String name,
                boolean isNamed,
                FnMethod[] fixedMethods,
                FnMethod variadicMethod,
                Object[][] captured,
                PersistentHashMap meta) {
            this.name = name;
            this.isNamed = isNamed;
            this.fixedMethods = fixedMethods;
            this.variadicMethod = variadicMethod;
            this.captured = captured;
            this.meta = meta;
        }

        public FnMethod[] getFixedMethods() { return fixedMethods; }
        public FnMethod getVariadicMethod() { return variadicMethod; }
        public Object[][] getCaptured() { return captured; }
        public Var getOwnerVar() { return ownerVar; }
        public void setOwnerVar(Var ownerVar) { this.ownerVar = ownerVar; }

        public void patchCaptured(int[] parentSlots, Object[] vals) {
            if (captured == null) return;
            for (int i = 0; i < fixedMethods.length; i++) {
                FnMethod m = fixedMethods[i];
                Object[] caps = captured[i];
                if (m != null && caps != null) {
                    patchMethodCaps(m.capturedParentSlots, caps, parentSlots, vals);
                }
            }
            if (variadicMethod != null && captured[fixedMethods.length] != null) {
                patchMethodCaps(variadicMethod.capturedParentSlots, captured[fixedMethods.length], parentSlots, vals);
            }
        }

        private static void patchMethodCaps(int[] capParentSlots, Object[] caps, int[] parentSlots, Object[] vals) {
            for (int j = 0; j < capParentSlots.length; j++) {
                int p = capParentSlots[j];
                for (int k = 0; k < parentSlots.length; k++) {
                    if (p == parentSlots[k]) {
                        caps[j] = vals[k];
                        break;
                    }
                }
            }
        }

        /** Eagerly compiles and returns the raw JIT Fn (or this if not JIT-eligible). */
        public Fn ensureJit() {
            Fn j = jitFn;
            if (j != null) return j;
            synchronized (this) {
                if (jitFn != null) return jitFn;
                if (!jitAttempted) {
                    jitAttempted = true;
                    Fn compiled = BytecodeJIT.compile(this);
                    if (compiled != null) {
                        jitFn = compiled;
                        return compiled;
                    }
                }
            }
            return this;
        }

        private void maybeCompileJit() {
            if (jitAttempted) return;
            int c = calls.incrementAndGet();
            if (c < JIT_THRESHOLD && (c != 1 || !hasLoop())) {
                return;
            }
            synchronized (this) {
                if (jitAttempted) return;
                jitAttempted = true;
                Fn compiled = BytecodeJIT.compile(this);
                if (compiled != null) {
                    jitFn = compiled;
                }
            }
        }

        private boolean hasLoop() {
            for (FnMethod m : fixedMethods) {
                if (m != null && (m.hasRecur || containsLoop(m.body))) return true;
            }
            return variadicMethod != null && (variadicMethod.hasRecur || containsLoop(variadicMethod.body));
        }

        private static boolean containsLoop(Node n) {
            if (n instanceof LoopNode) return true;
            if (n instanceof IfNode iff) {
                return containsLoop(iff.test) || containsLoop(iff.thenNode) || containsLoop(iff.elseNode);
            }
            if (n instanceof DoNode dn) {
                for (Node c : dn.nodes) if (containsLoop(c)) return true;
            }
            if (n instanceof LetNode ln) {
                for (Node init : ln.inits) if (containsLoop(init)) return true;
                return containsLoop(ln.body);
            }
            if (n instanceof TryNode tn) {
                if (containsLoop(tn.body)) return true;
                for (CatchClause cc : tn.catches) if (containsLoop(cc.handler())) return true;
                return containsLoop(tn.finallyNode);
            }
            if (n instanceof CaseNode cn) {
                if (containsLoop(cn.expr) || (cn.hasDefault && containsLoop(cn.defaultNode))) return true;
                for (Node b : cn.table.values()) if (containsLoop(b)) return true;
            }
            if (n instanceof LockingNode ln) {
                return containsLoop(ln.target) || containsLoop(ln.body);
            }
            if (n instanceof VolatileNode vn) {
                return containsLoop(vn.init);
            }
            if (n instanceof DerefNode dn) {
                return containsLoop(dn.target);
            }
            if (n instanceof VresetNode vr) {
                return containsLoop(vr.target) || containsLoop(vr.val);
            }
            if (n instanceof AtomNode an) {
                return containsLoop(an.init);
            }
            if (n instanceof ResetBangNode rb) {
                return containsLoop(rb.target) || containsLoop(rb.val);
            }
            if (n instanceof SwapBangNode sb) {
                if (containsLoop(sb.target) || containsLoop(sb.fn)) return true;
                for (Node a : sb.extraArgs) if (containsLoop(a)) return true;
            }
            return false;
        }

        @Override
        public Object invoke() {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invoke();
            return invokeSlow0();
        }

        public Object invokeInterpreted0() {
            FnMethod m = fixedMethods[0];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 0);
                return m.runBody(frame);
            }
            if (variadicMethod != null && variadicMethod.fixedArity == 0) {
                Object[] frame = variadicMethod.initFrame(this, captured, fixedMethods.length);
                frame[variadicMethod.paramSlots[0]] = null;
                return variadicMethod.runBody(frame);
            }
            throw arityError(0);
        }

        private Object invokeSlow0() {
            if (!Core.coreRedefined) {
                maybeCompileJit();
                Fn j = jitFn;
                if (j != null) return j.invoke();
            }
            return invokeInterpreted0();
        }

        @Override
        public Object invoke(Object a) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invoke(a);
            return invokeSlow1(a);
        }

        public Object invokeInterpreted1(Object a) {
            FnMethod m = fixedMethods[1];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 1);
                frame[m.paramSlots[0]] = a;
                return m.runBody(frame);
            }
            return invokeInterpretedVarargs(new Object[]{a});
        }

        private Object invokeSlow1(Object a) {
            if (!Core.coreRedefined) {
                maybeCompileJit();
                Fn j = jitFn;
                if (j != null) return j.invoke(a);
            }
            return invokeInterpreted1(a);
        }

        @Override
        public Object invoke(Object a, Object b) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invoke(a, b);
            return invokeSlow2(a, b);
        }

        @Override
        public boolean isUnboxedLong1() {
            Fn j = jitFn;
            return j != null && !Core.coreRedefined && j.isUnboxedLong1();
        }

        @Override
        public boolean isUnboxedLong2() {
            Fn j = jitFn;
            return j != null && !Core.coreRedefined && j.isUnboxedLong2();
        }

        @Override
        public long invokePrim_J_J(long a) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_J_J(a);
            return Numbers.longCast(invokeSlow1(Long.valueOf(a)));
        }

        @Override
        public double invokePrim_D_D(double a) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_D_D(a);
            return Numbers.toDouble(invokeSlow1(Double.valueOf(a)));
        }

        @Override
        public boolean invokePrim_J_Z(long a) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_J_Z(a);
            return RT.truthy(invokeSlow1(Long.valueOf(a)));
        }

        @Override
        public long invokePrim_JJ_J(long a, long b) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_JJ_J(a, b);
            return Numbers.longCast(invokeSlow2(Long.valueOf(a), Long.valueOf(b)));
        }

        @Override
        public double invokePrim_DD_D(double a, double b) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_DD_D(a, b);
            return Numbers.toDouble(invokeSlow2(Double.valueOf(a), Double.valueOf(b)));
        }

        @Override
        public long invokePrim_OJ_J(Object a, long b) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_OJ_J(a, b);
            return Numbers.longCast(invokeSlow2(a, Long.valueOf(b)));
        }

        @Override
        public Object invokePrim_OJ_O(Object a, long b) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokePrim_OJ_O(a, b);
            return invokeSlow2(a, Long.valueOf(b));
        }

        public Object invokeInterpreted2(Object a, Object b) {
            FnMethod m = fixedMethods[2];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 2);
                frame[m.paramSlots[0]] = a;
                frame[m.paramSlots[1]] = b;
                return m.runBody(frame);
            }
            return invokeInterpretedVarargs(new Object[]{a, b});
        }

        private Object invokeSlow2(Object a, Object b) {
            if (!Core.coreRedefined) {
                maybeCompileJit();
                Fn j = jitFn;
                if (j != null) return j.invoke(a, b);
            }
            return invokeInterpreted2(a, b);
        }

        @Override
        public Object invoke(Object a, Object b, Object c) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invoke(a, b, c);
            return invokeSlow3(a, b, c);
        }

        public Object invokeInterpreted3(Object a, Object b, Object c) {
            FnMethod m = fixedMethods[3];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 3);
                frame[m.paramSlots[0]] = a;
                frame[m.paramSlots[1]] = b;
                frame[m.paramSlots[2]] = c;
                return m.runBody(frame);
            }
            return invokeInterpretedVarargs(new Object[]{a, b, c});
        }

        private Object invokeSlow3(Object a, Object b, Object c) {
            if (!Core.coreRedefined) {
                maybeCompileJit();
                Fn j = jitFn;
                if (j != null) return j.invoke(a, b, c);
            }
            return invokeInterpreted3(a, b, c);
        }

        @Override
        public Object invoke(Object a, Object b, Object c, Object d) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invoke(a, b, c, d);
            return invokeSlow4(a, b, c, d);
        }

        public Object invokeInterpreted4(Object a, Object b, Object c, Object d) {
            FnMethod m = fixedMethods[4];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 4);
                frame[m.paramSlots[0]] = a;
                frame[m.paramSlots[1]] = b;
                frame[m.paramSlots[2]] = c;
                frame[m.paramSlots[3]] = d;
                return m.runBody(frame);
            }
            return invokeInterpretedVarargs(new Object[]{a, b, c, d});
        }

        private Object invokeSlow4(Object a, Object b, Object c, Object d) {
            if (!Core.coreRedefined) {
                maybeCompileJit();
                Fn j = jitFn;
                if (j != null) return j.invoke(a, b, c, d);
            }
            return invokeInterpreted4(a, b, c, d);
        }

        @Override
        public Object invokeVarargs(Object[] args) {
            Fn j = jitFn;
            if (j != null && !Core.coreRedefined) return j.invokeVarargs(args);
            return invokeSlowVarargs(args);
        }

        public Object invokeInterpretedVarargs(Object[] args) {
            int n = args.length;
            if (n < fixedMethods.length) {
                FnMethod m = fixedMethods[n];
                if (m != null) {
                    Object[] frame = m.initFrame(this, captured, n);
                    for (int i = 0; i < n; i++) {
                        frame[m.paramSlots[i]] = args[i];
                    }
                    return m.runBody(frame);
                }
            }
            if (variadicMethod != null && n >= variadicMethod.fixedArity) {
                Object[] frame = variadicMethod.initFrame(this, captured, fixedMethods.length);
                int fixed = variadicMethod.fixedArity;
                for (int i = 0; i < fixed; i++) {
                    frame[variadicMethod.paramSlots[i]] = args[i];
                }
                ISeq restSeq = (n > fixed) ? PersistentList.createFromArraySlice(args, fixed) : null;
                frame[variadicMethod.paramSlots[fixed]] = restSeq;
                return variadicMethod.runBody(frame);
            }
            throw arityError(n);
        }

        private Object invokeSlowVarargs(Object[] args) {
            if (!Core.coreRedefined) {
                maybeCompileJit();
                Fn j = jitFn;
                if (j != null) return j.invokeVarargs(args);
            }
            return invokeInterpretedVarargs(args);
        }

        @Override public String getName() { return name; }
        @Override public PersistentHashMap meta() { return meta; }
        @Override public CompiledFn withMeta(PersistentHashMap m) {
            if (this.meta == m) return this;
            CompiledFn copy = new CompiledFn(name, isNamed, fixedMethods, variadicMethod, captured, m);
            copy.ownerVar = this.ownerVar;
            copy.jitFn = this.jitFn;
            copy.jitAttempted = this.jitAttempted;
            return copy;
        }
        @Override public String toString() {
            String hex = Integer.toHexString(System.identityHashCode(this));
            Var ov = this.ownerVar;
            if (ov != null && ov.ns != null && ov.sym != null) {
                return Compiler.munge(ov.ns.name.name) + "$" + Compiler.munge(ov.sym.name) + "@" + hex;
            }
            if (name != null && !"fn".equals(name)) {
                return "user$" + Compiler.munge(name) + "@" + hex;
            }
            return "user$fn__" + (System.identityHashCode(this) & 0x7fffffff) + "@" + hex;
        }
    }

    // ========================================================================
    // Arity-Specialized Call Nodes
    // ========================================================================

    public static final class VarCall0Node extends Node {
        public final Var v;
        public VarCall0Node(Var v) { this.v = v; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) v.get()).invoke(); }
    }

    public static final class VarCall1Node extends Node {
        public final Var v;
        public final Node a;
        public VarCall1Node(Var v, Node a) { this.v = v; this.a = a; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) v.get()).invoke(a.eval(frame)); }
    }

    public static final class VarCall2Node extends Node {
        public final Var v;
        public final Node a, b;
        public VarCall2Node(Var v, Node a, Node b) { this.v = v; this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) v.get()).invoke(a.eval(frame), b.eval(frame)); }
    }

    public static final class VarCall3Node extends Node {
        public final Var v;
        public final Node a, b, c;
        public VarCall3Node(Var v, Node a, Node b, Node c) { this.v = v; this.a = a; this.b = b; this.c = c; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) v.get()).invoke(a.eval(frame), b.eval(frame), c.eval(frame)); }
    }

    public static final class VarCall4Node extends Node {
        public final Var v;
        public final Node a, b, c, d;
        public VarCall4Node(Var v, Node a, Node b, Node c, Node d) { this.v = v; this.a = a; this.b = b; this.c = c; this.d = d; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) v.get()).invoke(a.eval(frame), b.eval(frame), c.eval(frame), d.eval(frame)); }
    }

    public static final class VarCallNNode extends Node {
        public final Var v;
        public final Node[] args;
        public VarCallNNode(Var v, Node[] args) { this.v = v; this.args = args; }
        @Override
        public Object eval(Object[] frame) {
            Object fObj = v.get();
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            if (fObj instanceof Fn f) return f.invokeVarargs(evaluated);
            return ((clojure.lang.IFn) fObj).applyTo(PersistentList.createFromArray(evaluated));
        }
    }

    public static final class Call0Node extends Node {
        public final Node fn;
        public Call0Node(Node fn) { this.fn = fn; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) fn.eval(frame)).invoke(); }
    }

    public static final class Call1Node extends Node {
        public final Node fn, a;
        public Call1Node(Node fn, Node a) { this.fn = fn; this.a = a; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) fn.eval(frame)).invoke(a.eval(frame)); }
    }

    public static final class Call2Node extends Node {
        public final Node fn, a, b;
        public Call2Node(Node fn, Node a, Node b) { this.fn = fn; this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) fn.eval(frame)).invoke(a.eval(frame), b.eval(frame)); }
    }

    public static final class Call3Node extends Node {
        public final Node fn, a, b, c;
        public Call3Node(Node fn, Node a, Node b, Node c) { this.fn = fn; this.a = a; this.b = b; this.c = c; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) fn.eval(frame)).invoke(a.eval(frame), b.eval(frame), c.eval(frame)); }
    }

    public static final class Call4Node extends Node {
        public final Node fn, a, b, c, d;
        public Call4Node(Node fn, Node a, Node b, Node c, Node d) { this.fn = fn; this.a = a; this.b = b; this.c = c; this.d = d; }
        @Override public Object eval(Object[] frame) { return ((clojure.lang.IFn) fn.eval(frame)).invoke(a.eval(frame), b.eval(frame), c.eval(frame), d.eval(frame)); }
    }

    public static final class CallNNode extends Node {
        public final Node fn;
        public final Node[] args;
        public CallNNode(Node fn, Node[] args) { this.fn = fn; this.args = args; }
        @Override
        public Object eval(Object[] frame) {
            Object fObj = fn.eval(frame);
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            if (fObj instanceof Fn f) return f.invokeVarargs(evaluated);
            return ((clojure.lang.IFn) fObj).applyTo(PersistentList.createFromArray(evaluated));
        }
    }

    // ========================================================================
    // Primitive-Specialized Arithmetic & Comparison Intrinsics
    // ========================================================================

    public static final class Add2Node extends Node {
        public final Node a, b;
        public Add2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("+", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) {
                return Math.addExact(la, lb);
            }
            return Numbers.add(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("+", a.eval(frame), b.eval(frame)));
            return Math.addExact(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class Sub2Node extends Node {
        public final Node a, b;
        public Sub2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("-", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) {
                return Math.subtractExact(la, lb);
            }
            return Numbers.minus(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("-", a.eval(frame), b.eval(frame)));
            return Math.subtractExact(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class Mul2Node extends Node {
        public final Node a, b;
        public Mul2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("*", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) {
                return Math.multiplyExact(la, lb);
            }
            return Numbers.multiply(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("*", a.eval(frame), b.eval(frame)));
            return Math.multiplyExact(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class NegateNode extends Node {
        public final Node a;
        public NegateNode(Node a) { this.a = a; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("-", a.eval(frame));
            Object va = a.eval(frame);
            if (va instanceof Long la) return Math.negateExact(la);
            return Numbers.minus(va);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("-", a.eval(frame)));
            return Math.negateExact(a.evalLong(frame));
        }
    }

    public static final class IncNode extends Node {
        public final Node a;
        public IncNode(Node a) { this.a = a; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("inc", a.eval(frame));
            Object va = a.eval(frame);
            if (va instanceof Long la) return Math.incrementExact(la);
            return Numbers.inc(va);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("inc", a.eval(frame)));
            return Math.incrementExact(a.evalLong(frame));
        }
    }

    public static final class DecNode extends Node {
        public final Node a;
        public DecNode(Node a) { this.a = a; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("dec", a.eval(frame));
            Object va = a.eval(frame);
            if (va instanceof Long la) return Math.decrementExact(la);
            return Numbers.dec(va);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("dec", a.eval(frame)));
            return Math.decrementExact(a.evalLong(frame));
        }
    }

    public static final class Rem2Node extends Node {
        public final Node a, b;
        public Rem2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("rem", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la % lb;
            return Numbers.remainder(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("rem", a.eval(frame), b.eval(frame)));
            return a.evalLong(frame) % b.evalLong(frame);
        }
    }

    public static final class Mod2Node extends Node {
        public final Node a, b;
        public Mod2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("mod", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return Math.floorMod(la.longValue(), lb.longValue());
            return Numbers.mod(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("mod", a.eval(frame), b.eval(frame)));
            return Math.floorMod(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class Quot2Node extends Node {
        public final Node a, b;
        public Quot2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("quot", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la / lb;
            return Numbers.quotient(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("quot", a.eval(frame), b.eval(frame)));
            return a.evalLong(frame) / b.evalLong(frame);
        }
    }

    public static final class Lt2Node extends Node {
        public final Node a, b;
        public Lt2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("<", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("<", a.eval(frame), b.eval(frame)));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la < lb;
            return Numbers.lt(va, vb);
        }
    }

    public static final class Lte2Node extends Node {
        public final Node a, b;
        public Lte2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("<=", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("<=", a.eval(frame), b.eval(frame)));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la <= lb;
            return Numbers.lte(va, vb);
        }
    }

    public static final class Gt2Node extends Node {
        public final Node a, b;
        public Gt2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar(">", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar(">", a.eval(frame), b.eval(frame)));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la > lb;
            return Numbers.gt(va, vb);
        }
    }

    public static final class Gte2Node extends Node {
        public final Node a, b;
        public Gte2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar(">=", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar(">=", a.eval(frame), b.eval(frame)));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la >= lb;
            return Numbers.gte(va, vb);
        }
    }

    public static final class Equiv2Node extends Node {
        public final Node a, b;
        public Equiv2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("=", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("=", a.eval(frame), b.eval(frame)));
            return RT.equiv(a.eval(frame), b.eval(frame));
        }
    }

    public static final class NumEq2Node extends Node {
        public final Node a, b;
        public NumEq2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("==", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("==", a.eval(frame), b.eval(frame)));
            return Numbers.equal(a.eval(frame), b.eval(frame));
        }
    }

    public static final class ZeroPNode extends Node {
        public final Node a;
        public ZeroPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("zero?", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("zero?", a.eval(frame)));
            Object va = a.eval(frame);
            if (va instanceof Long la) return la == 0L;
            return Numbers.isZero(va);
        }
    }

    public static final class PosPNode extends Node {
        public final Node a;
        public PosPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("pos?", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("pos?", a.eval(frame)));
            Object va = a.eval(frame);
            if (va instanceof Long la) return la > 0L;
            return Numbers.isPos(va);
        }
    }

    public static final class NegPNode extends Node {
        public final Node a;
        public NegPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("neg?", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override
        public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("neg?", a.eval(frame)));
            Object va = a.eval(frame);
            if (va instanceof Long la) return la < 0L;
            return Numbers.isNeg(va);
        }
    }

    public static final class NilPNode extends Node {
        public final Node a;
        public NilPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("nil?", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("nil?", a.eval(frame)));
            return a.eval(frame) == null;
        }
    }

    public static final class NotNode extends Node {
        public final Node a;
        public NotNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("not", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("not", a.eval(frame)));
            return !a.evalBoolean(frame);
        }
    }

    public static final class IdenticalNode extends Node {
        public final Node a, b;
        public IdenticalNode(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("identical?", a.eval(frame), b.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("identical?", a.eval(frame), b.eval(frame)));
            return a.eval(frame) == b.eval(frame);
        }
    }

    // ========================================================================
    // Exceptions, Interop & Dispatch Nodes
    // ========================================================================

    public record CatchClause(Class<?> exClass, int slot, Node handler) {}

    public static final class TryNode extends Node {
        public final Node body;
        public final CatchClause[] catches;
        public final Node finallyNode;

        public TryNode(Node body, CatchClause[] catches, Node finallyNode) {
            this.body = body;
            this.catches = catches;
            this.finallyNode = finallyNode;
        }

        @Override
        public Object eval(Object[] frame) {
            try {
                return body.eval(frame);
            } catch (Throwable t) {
                for (CatchClause cc : catches) {
                    if (cc.exClass.isInstance(t)) {
                        frame[cc.slot] = t;
                        return cc.handler.eval(frame);
                    }
                }
                throw RT.sneakyThrow(t);
            } finally {
                if (finallyNode != null) {
                    finallyNode.eval(frame);
                }
            }
        }
    }

    public static final class ThrowNode extends Node {
        public final Node expr;
        public ThrowNode(Node expr) { this.expr = expr; }
        @Override
        public Object eval(Object[] frame) {
            Object ex = expr.eval(frame);
            if (ex instanceof Throwable t) throw RT.sneakyThrow(t);
            throw new RuntimeException(String.valueOf(ex));
        }
    }

    public static final class NewNode extends Node {
        public final Class<?> clazz;
        public final Node[] args;
        public final Class<?>[] paramTags;
        public NewNode(Class<?> clazz, Node[] args) { this(clazz, args, null); }
        public NewNode(Class<?> clazz, Node[] args, Class<?>[] paramTags) {
            this.clazz = clazz;
            this.args = args;
            this.paramTags = paramTags;
        }
        @Override
        public Object eval(Object[] frame) {
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return paramTags != null
                    ? Interop.invokeConstructorWithTags(clazz, paramTags, evaluated)
                    : Interop.invokeConstructor(clazz, evaluated);
        }
    }

    public static final class StaticFieldNode extends Node {
        public final Class<?> clazz;
        public final String fieldName;
        public StaticFieldNode(Class<?> clazz, String fieldName) {
            this.clazz = clazz;
            this.fieldName = fieldName;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.getStaticField(clazz, fieldName);
        }
    }

    public static final class StaticMethodNode extends Node {
        public final Class<?> clazz;
        public final String methodName;
        public final Node[] args;
        public final Class<?>[] paramTags;
        public StaticMethodNode(Class<?> clazz, String methodName, Node[] args) {
            this(clazz, methodName, args, null);
        }
        public StaticMethodNode(Class<?> clazz, String methodName, Node[] args, Class<?>[] paramTags) {
            this.clazz = clazz;
            this.methodName = methodName;
            this.args = args;
            this.paramTags = paramTags;
        }
        @Override
        public Object eval(Object[] frame) {
            if (paramTags == null && clazz == Math.class) {
                if (args.length == 1) {
                    Object a0 = args[0].eval(frame);
                    if (a0 instanceof Number n0) {
                        double d = n0.doubleValue();
                        switch (methodName) {
                            case "sin" -> { return Double.valueOf(Math.sin(d)); }
                            case "cos" -> { return Double.valueOf(Math.cos(d)); }
                            case "tan" -> { return Double.valueOf(Math.tan(d)); }
                            case "sqrt" -> { return Double.valueOf(Math.sqrt(d)); }
                            case "exp" -> { return Double.valueOf(Math.exp(d)); }
                            case "log" -> { return Double.valueOf(Math.log(d)); }
                            case "floor" -> { return Double.valueOf(Math.floor(d)); }
                            case "ceil" -> { return Double.valueOf(Math.ceil(d)); }
                            case "abs" -> {
                                if (n0 instanceof Long l) return Long.valueOf(Math.abs(l.longValue()));
                                if (n0 instanceof Double) return Double.valueOf(Math.abs(d));
                            }
                        }
                    }
                    return Interop.invokeStaticMethod(clazz, methodName, new Object[]{a0});
                }
                if (args.length == 2) {
                    Object a0 = args[0].eval(frame);
                    Object a1 = args[1].eval(frame);
                    if (a0 instanceof Number n0 && a1 instanceof Number n1) {
                        switch (methodName) {
                            case "pow" -> { return Double.valueOf(Math.pow(n0.doubleValue(), n1.doubleValue())); }
                            case "atan2" -> { return Double.valueOf(Math.atan2(n0.doubleValue(), n1.doubleValue())); }
                            case "hypot" -> { return Double.valueOf(Math.hypot(n0.doubleValue(), n1.doubleValue())); }
                            case "min" -> {
                                if (n0 instanceof Long l0 && n1 instanceof Long l1) return Long.valueOf(Math.min(l0, l1));
                                if (n0 instanceof Double || n1 instanceof Double) return Double.valueOf(Math.min(n0.doubleValue(), n1.doubleValue()));
                            }
                            case "max" -> {
                                if (n0 instanceof Long l0 && n1 instanceof Long l1) return Long.valueOf(Math.max(l0, l1));
                                if (n0 instanceof Double || n1 instanceof Double) return Double.valueOf(Math.max(n0.doubleValue(), n1.doubleValue()));
                            }
                        }
                    }
                    return Interop.invokeStaticMethod(clazz, methodName, new Object[]{a0, a1});
                }
            }
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return paramTags != null
                    ? Interop.invokeStaticMethodWithTags(clazz, methodName, paramTags, evaluated)
                    : Interop.invokeStaticMethod(clazz, methodName, evaluated);
        }
    }

    public static final class InstanceFieldNode extends Node {
        public final Node target;
        public final String fieldName;
        public InstanceFieldNode(Node target, String fieldName) {
            this.target = target;
            this.fieldName = fieldName;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.getInstanceField(target.eval(frame), fieldName);
        }
    }

    public static final class InstanceMethodNode extends Node {
        public final Node target;
        public final String methodName;
        public final Node[] args;
        public final Class<?> declaredClass;
        public final Class<?>[] paramTags;
        public InstanceMethodNode(Node target, String methodName, Node[] args) {
            this(target, methodName, args, null, null);
        }
        public InstanceMethodNode(Node target, String methodName, Node[] args, Class<?> declaredClass, Class<?>[] paramTags) {
            this.target = target;
            this.methodName = methodName;
            this.args = args;
            this.declaredClass = declaredClass;
            this.paramTags = paramTags;
        }
        @Override
        public Object eval(Object[] frame) {
            Object t = target.eval(frame);
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            if (declaredClass != null) {
                return Interop.invokeQualifiedInstanceMethod(declaredClass, methodName, paramTags, t, evaluated);
            }
            if (paramTags != null) {
                return Interop.invokeInstanceMethodWithTags(t, methodName, paramTags, evaluated);
            }
            return Interop.invokeInstanceMethod(t, methodName, evaluated);
        }
    }

    public static final class SetVarNode extends Node {
        public final Var var;
        public final Node val;
        public SetVarNode(Var var, Node val) { this.var = var; this.val = val; }
        @Override
        public Object eval(Object[] frame) {
            return var.set(val.eval(frame));
        }
    }

    public static final class SetFieldNode extends Node {
        public final Node target;
        public final String fieldName;
        public final Node val;
        public SetFieldNode(Node target, String fieldName, Node val) {
            this.target = target;
            this.fieldName = fieldName;
            this.val = val;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.setInstanceField(target.eval(frame), fieldName, val.eval(frame));
        }
    }

    public static final class SetStaticFieldNode extends Node {
        public final Class<?> clazz;
        public final String fieldName;
        public final Node val;
        public SetStaticFieldNode(Class<?> clazz, String fieldName, Node val) {
            this.clazz = clazz;
            this.fieldName = fieldName;
            this.val = val;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.setStaticField(clazz, fieldName, val.eval(frame));
        }
    }

    public static final class CaseNode extends Node {
        public final Node expr;
        public final Map<RT.EquivKey, Node> table;
        public final boolean hasDefault;
        public final Node defaultNode;
        public final boolean hashMode;
        public final int shift;
        public final int mask;
        public final Object testType;
        public final Map<Integer, Object> bucketTests;
        public final Map<Integer, Node> bucketThens;
        public final Set<Integer> skipCheck;

        public CaseNode(Node expr, Map<RT.EquivKey, Node> table, boolean hasDefault, Node defaultNode) {
            this.expr = expr;
            this.table = table;
            this.hasDefault = hasDefault;
            this.defaultNode = defaultNode;
            this.hashMode = false;
            this.shift = 0;
            this.mask = 0;
            this.testType = null;
            this.bucketTests = null;
            this.bucketThens = null;
            this.skipCheck = null;
        }

        public CaseNode(Node expr, int shift, int mask, Object testType,
                        Map<Integer, Object> bucketTests, Map<Integer, Node> bucketThens,
                        Set<Integer> skipCheck, Node defaultNode) {
            this.expr = expr;
            this.table = Collections.emptyMap();
            this.hasDefault = true;
            this.defaultNode = defaultNode;
            this.hashMode = true;
            this.shift = shift;
            this.mask = mask;
            this.testType = testType;
            this.bucketTests = bucketTests;
            this.bucketThens = bucketThens;
            this.skipCheck = skipCheck;
        }

        public int computeBucket(Object v) {
            int h;
            if (Keyword.intern("int").equals(testType)) {
                if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
                    long lv = ((Number) v).longValue();
                    if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) return Integer.MIN_VALUE;
                    h = (int) lv;
                } else if (v instanceof Character ch) {
                    h = ch.charValue();
                } else {
                    return Integer.MIN_VALUE;
                }
            } else {
                h = RT.hash(v);
            }
            return mask == 0 ? h : ((h >>> shift) & mask);
        }

        @Override
        public Object eval(Object[] frame) {
            Object v = expr.eval(frame);
            if (hashMode) {
                int b = computeBucket(v);
                Node match = bucketThens.get(b);
                if (match != null) {
                    if (skipCheck.contains(b)) {
                        return match.eval(frame);
                    }
                    Object expected = bucketTests.get(b);
                    boolean ok = Keyword.intern("hash-identity").equals(testType)
                            ? (v == expected)
                            : RT.equiv(v, expected);
                    if (ok) return match.eval(frame);
                }
                return defaultNode.eval(frame);
            }
            Node match = table.get(new RT.EquivKey(v));
            if (match != null) return match.eval(frame);
            if (hasDefault) return defaultNode.eval(frame);
            throw new IllegalArgumentException("No matching clause: " + v);
        }
    }

    // ========================================================================
    // Extended Intrinsic AST Nodes (Typed / Unboxed Arithmetic, Bitwise, Coll)
    // ========================================================================

    static boolean isStaticDoubleNode(Node n) {
        if (n instanceof ConstNode cn) return cn.isDouble;
        if (n instanceof DoubleCastNode) return true;
        if (n instanceof LocalNode ln) return ln.staticDouble || ln.hint == ValType.DOUBLE;
        if (n instanceof Div2Node d) return isStaticDoubleNode(d.a) || isStaticDoubleNode(d.b);
        if (n instanceof Add2Node a) return isStaticDoubleNode(a.a) || isStaticDoubleNode(a.b);
        if (n instanceof Sub2Node a) return isStaticDoubleNode(a.a) || isStaticDoubleNode(a.b);
        if (n instanceof Mul2Node a) return isStaticDoubleNode(a.a) || isStaticDoubleNode(a.b);
        if (n instanceof NegateNode a) return isStaticDoubleNode(a.a);
        if (n instanceof IncNode a) return isStaticDoubleNode(a.a);
        if (n instanceof DecNode a) return isStaticDoubleNode(a.a);
        return false;
    }

    public static final class Div2Node extends Node {
        public final Node a, b;
        public Div2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("/", a.eval(frame), b.eval(frame));
            if (isStaticDoubleNode(a)) return Numbers.divide(Numbers.toDouble(a.eval(frame)), b.eval(frame));
            if (isStaticDoubleNode(b)) return Numbers.divide(a.eval(frame), Numbers.toDouble(b.eval(frame)));
            return Numbers.divide(a.eval(frame), b.eval(frame));
        }
    }

    public static final class UncheckedAdd2Node extends Node {
        public final Node a, b;
        public UncheckedAdd2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("unchecked-add", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la + lb;
            return Numbers.uncheckedAdd(va, vb);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("unchecked-add", a.eval(frame), b.eval(frame)));
            return a.evalLong(frame) + b.evalLong(frame);
        }
    }

    public static final class UncheckedSub2Node extends Node {
        public final Node a, b;
        public UncheckedSub2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("unchecked-subtract", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la - lb;
            return Numbers.uncheckedSubtract(va, vb);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("unchecked-subtract", a.eval(frame), b.eval(frame)));
            return a.evalLong(frame) - b.evalLong(frame);
        }
    }

    public static final class UncheckedMul2Node extends Node {
        public final Node a, b;
        public UncheckedMul2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("unchecked-multiply", a.eval(frame), b.eval(frame));
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la * lb;
            return Numbers.uncheckedMultiply(va, vb);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("unchecked-multiply", a.eval(frame), b.eval(frame)));
            return a.evalLong(frame) * b.evalLong(frame);
        }
    }

    public static final class UncheckedIncNode extends Node {
        public final Node a;
        public UncheckedIncNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("unchecked-inc", a.eval(frame));
            Object va = a.eval(frame);
            if (va instanceof Long la) return la + 1L;
            return Numbers.uncheckedInc(va);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("unchecked-inc", a.eval(frame)));
            return a.evalLong(frame) + 1L;
        }
    }

    public static final class UncheckedDecNode extends Node {
        public final Node a;
        public UncheckedDecNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("unchecked-dec", a.eval(frame));
            Object va = a.eval(frame);
            if (va instanceof Long la) return la - 1L;
            return Numbers.uncheckedDec(va);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("unchecked-dec", a.eval(frame)));
            return a.evalLong(frame) - 1L;
        }
    }

    public static final class UncheckedNegateNode extends Node {
        public final Node a;
        public UncheckedNegateNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("unchecked-negate", a.eval(frame));
            Object va = a.eval(frame);
            if (va instanceof Long la) return -la;
            return Numbers.uncheckedNegate(va);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("unchecked-negate", a.eval(frame)));
            return -a.evalLong(frame);
        }
    }

    public enum BitOp { AND, OR, XOR, AND_NOT, SHL, SHR, USHR }

    public static final class BitOp2Node extends Node {
        public final BitOp op;
        public final Node a, b;
        public BitOp2Node(BitOp op, Node a, Node b) {
            this.op = op;
            this.a = a;
            this.b = b;
        }
        private String coreName() {
            return switch (op) {
                case AND -> "bit-and";
                case OR -> "bit-or";
                case XOR -> "bit-xor";
                case AND_NOT -> "bit-and-not";
                case SHL -> "bit-shift-left";
                case SHR -> "bit-shift-right";
                case USHR -> "unsigned-bit-shift-right";
            };
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar(coreName(), a.eval(frame), b.eval(frame));
            return evalLong(frame);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar(coreName(), a.eval(frame), b.eval(frame)));
            long la = a.evalLong(frame);
            long lb = b.evalLong(frame);
            return switch (op) {
                case AND -> la & lb;
                case OR -> la | lb;
                case XOR -> la ^ lb;
                case AND_NOT -> la & ~lb;
                case SHL -> la << lb;
                case SHR -> la >> lb;
                case USHR -> la >>> lb;
            };
        }
    }

    public static final class BitNotNode extends Node {
        public final Node a;
        public BitNotNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("bit-not", a.eval(frame));
            return evalLong(frame);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("bit-not", a.eval(frame)));
            return ~a.evalLong(frame);
        }
    }

    public static final class LongCastNode extends Node {
        public final Node a;
        public final boolean unchecked;
        public LongCastNode(Node a) { this(a, false); }
        public LongCastNode(Node a, boolean unchecked) { this.a = a; this.unchecked = unchecked; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar(unchecked ? "unchecked-long" : "long", a.eval(frame));
            return evalLong(frame);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar(unchecked ? "unchecked-long" : "long", a.eval(frame)));
            Object v = a.eval(frame);
            return unchecked ? Numbers.uncheckedLong(v) : Numbers.longCast(v);
        }
    }

    public static final class AlengthNode extends Node {
        public final Node arr;
        public AlengthNode(Node arr) { this.arr = arr; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("alength", arr.eval(frame));
            return evalLong(frame);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("alength", arr.eval(frame)));
            return RT.alength(arr.eval(frame));
        }
    }

    public static final class AgetNode extends Node {
        public final Node arr, idx;
        public AgetNode(Node arr, Node idx) { this.arr = arr; this.idx = idx; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("aget", arr.eval(frame), idx.eval(frame));
            return RT.aget(arr.eval(frame), (int) idx.evalLong(frame));
        }
    }

    public static final class AsetNode extends Node {
        public final Node arr, idx, val;
        public AsetNode(Node arr, Node idx, Node val) { this.arr = arr; this.idx = idx; this.val = val; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("aset", arr.eval(frame), idx.eval(frame), val.eval(frame));
            return RT.aset(arr.eval(frame), (int) idx.evalLong(frame), val.eval(frame));
        }
    }

    public static final class DoubleCastNode extends Node {
        public final Node a;
        public DoubleCastNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("double", a.eval(frame));
            return Numbers.toDouble(a.eval(frame));
        }
    }

    public static final class CountNode extends Node {
        public final Node coll;
        public CountNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("count", coll.eval(frame));
            return evalLong(frame);
        }
        @Override public long evalLong(Object[] frame) {
            if (Core.coreRedefined) return Numbers.longCast(Core.callCoreVar("count", coll.eval(frame)));
            return RT.count(coll.eval(frame));
        }
    }

    public static final class NthNode extends Node {
        public final Node coll, idx, notFound;
        public NthNode(Node coll, Node idx, Node notFound) {
            this.coll = coll;
            this.idx = idx;
            this.notFound = notFound;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) {
                Object c = coll.eval(frame);
                Object i = idx.eval(frame);
                return notFound == null ? Core.callCoreVar("nth", c, i) : Core.callCoreVar("nth", c, i, notFound.eval(frame));
            }
            Object c = coll.eval(frame);
            int i = (int) idx.evalLong(frame);
            return notFound == null ? RT.nth(c, i) : RT.nth(c, i, notFound.eval(frame));
        }
    }

    public static final class GetNode extends Node {
        public final Node coll, key, notFound;
        public GetNode(Node coll, Node key, Node notFound) {
            this.coll = coll;
            this.key = key;
            this.notFound = notFound;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) {
                Object c = coll.eval(frame);
                Object k = key.eval(frame);
                return notFound == null ? Core.callCoreVar("get", c, k) : Core.callCoreVar("get", c, k, notFound.eval(frame));
            }
            Object c = coll.eval(frame);
            Object k = key.eval(frame);
            return notFound == null ? RT.get(c, k) : RT.get(c, k, notFound.eval(frame));
        }
    }

    public static final class TransientNode extends Node {
        public final Node coll;
        public TransientNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("transient", coll.eval(frame));
            return RT.transientColl(coll.eval(frame));
        }
    }

    public static final class PersistentBangNode extends Node {
        public final Node target;
        public PersistentBangNode(Node target) { this.target = target; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("persistent!", target.eval(frame));
            return RT.persistentBang(target.eval(frame));
        }
    }

    public static final class ConjBangNode extends Node {
        public final Node target, val;
        public ConjBangNode(Node target, Node val) { this.target = target; this.val = val; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("conj!", target.eval(frame), val.eval(frame));
            return RT.conjBang(target.eval(frame), val.eval(frame));
        }
    }

    public static final class AssocBangNode extends Node {
        public final Node target, key, val;
        public AssocBangNode(Node target, Node key, Node val) {
            this.target = target;
            this.key = key;
            this.val = val;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("assoc!", target.eval(frame), key.eval(frame), val.eval(frame));
            return RT.assocBang(target.eval(frame), key.eval(frame), val.eval(frame));
        }
    }

    public static final class ConjNode extends Node {
        public final Node coll, val;
        public ConjNode(Node coll, Node val) { this.coll = coll; this.val = val; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("conj", coll.eval(frame), val.eval(frame));
            return RT.conj(coll.eval(frame), val.eval(frame));
        }
    }

    public static final class AssocNode extends Node {
        public final Node coll, key, val;
        public AssocNode(Node coll, Node key, Node val) {
            this.coll = coll;
            this.key = key;
            this.val = val;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("assoc", coll.eval(frame), key.eval(frame), val.eval(frame));
            return RT.assoc(coll.eval(frame), key.eval(frame), val.eval(frame));
        }
    }

    public static final class DissocNode extends Node {
        public final Node coll, key;
        public DissocNode(Node coll, Node key) {
            this.coll = coll;
            this.key = key;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("dissoc", coll.eval(frame), key.eval(frame));
            return RT.dissoc(coll.eval(frame), key.eval(frame));
        }
    }

    public static final class FirstNode extends Node {
        public final Node coll;
        public FirstNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("first", coll.eval(frame));
            return RT.first(coll.eval(frame));
        }
    }

    public static final class NextNode extends Node {
        public final Node coll;
        public NextNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("next", coll.eval(frame));
            return RT.next(coll.eval(frame));
        }
    }

    public static final class RestNode extends Node {
        public final Node coll;
        public RestNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("rest", coll.eval(frame));
            return RT.more(coll.eval(frame));
        }
    }

    public static final class SeqNode extends Node {
        public final Node coll;
        public SeqNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("seq", coll.eval(frame));
            return RT.seq(coll.eval(frame));
        }
    }

    public static final class PopNode extends Node {
        public final Node coll;
        public PopNode(Node coll) { this.coll = coll; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("pop", coll.eval(frame));
            return RT.pop(coll.eval(frame));
        }
    }

    public static final class VolatileNode extends Node {
        public final Node init;
        public VolatileNode(Node init) { this.init = init; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("volatile!", init.eval(frame));
            return new Atom.Volatile(init.eval(frame));
        }
    }

    public static final class DerefNode extends Node {
        public final Node target;
        public DerefNode(Node target) { this.target = target; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("deref", target.eval(frame));
            return RT.deref(target.eval(frame));
        }
    }

    public static final class VresetNode extends Node {
        public final Node target, val;
        public VresetNode(Node target, Node val) {
            this.target = target;
            this.val = val;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("vreset!", target.eval(frame), val.eval(frame));
            return ((Atom.Volatile) target.eval(frame)).reset(val.eval(frame));
        }
    }

    public static final class AtomNode extends Node {
        public final Node init;
        public AtomNode(Node init) { this.init = init; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("atom", init.eval(frame));
            return new Atom(init.eval(frame));
        }
    }

    public static final class ResetBangNode extends Node {
        public final Node target, val;
        public ResetBangNode(Node target, Node val) {
            this.target = target;
            this.val = val;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("reset!", target.eval(frame), val.eval(frame));
            return ((Atom) target.eval(frame)).reset(val.eval(frame));
        }
    }

    public static final class SwapBangNode extends Node {
        public final Node target, fn;
        public final Node[] extraArgs;
        public SwapBangNode(Node target, Node fn, Node[] extraArgs) {
            this.target = target;
            this.fn = fn;
            this.extraArgs = extraArgs;
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) {
                Object[] all = new Object[2 + extraArgs.length];
                all[0] = target.eval(frame);
                all[1] = fn.eval(frame);
                for (int i = 0; i < extraArgs.length; i++) all[2 + i] = extraArgs[i].eval(frame);
                return Core.callCoreVarApply("swap!", all);
            }
            Atom a = (Atom) target.eval(frame);
            Fn f = (Fn) fn.eval(frame);
            return switch (extraArgs.length) {
                case 0 -> a.swap(f);
                case 1 -> a.swap(f, extraArgs[0].eval(frame));
                case 2 -> a.swap(f, extraArgs[0].eval(frame), extraArgs[1].eval(frame));
                default -> {
                    Object[] rest = new Object[extraArgs.length - 2];
                    for (int i = 2; i < extraArgs.length; i++) rest[i - 2] = extraArgs[i].eval(frame);
                    yield a.swap(f, extraArgs[0].eval(frame), extraArgs[1].eval(frame), rest);
                }
            };
        }
    }

    public static final class EvenPNode extends Node {
        public final Node a;
        public EvenPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("even?", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("even?", a.eval(frame)));
            return Numbers.isEven(a.eval(frame));
        }
    }

    public static final class OddPNode extends Node {
        public final Node a;
        public OddPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar("odd?", a.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar("odd?", a.eval(frame)));
            return Numbers.isOdd(a.eval(frame));
        }
    }

    public enum TypePred { STRING, VECTOR, MAP, SET, SEQ, NUMBER, INT, KEYWORD, SYMBOL }

    public static final class TypePredNode extends Node {
        public final TypePred pred;
        public final Node a;
        public TypePredNode(TypePred pred, Node a) {
            this.pred = pred;
            this.a = a;
        }
        private String coreName() {
            return switch (pred) {
                case STRING -> "string?";
                case VECTOR -> "vector?";
                case MAP -> "map?";
                case SET -> "set?";
                case SEQ -> "seq?";
                case NUMBER -> "number?";
                case INT -> "int?";
                case KEYWORD -> "keyword?";
                case SYMBOL -> "symbol?";
            };
        }
        @Override public Object eval(Object[] frame) {
            if (Core.coreRedefined) return Core.callCoreVar(coreName(), a.eval(frame));
            return evalBoolean(frame);
        }
        @Override public boolean evalBoolean(Object[] frame) {
            if (Core.coreRedefined) return RT.truthy(Core.callCoreVar(coreName(), a.eval(frame)));
            Object x = a.eval(frame);
            return switch (pred) {
                case STRING -> x instanceof String;
                case VECTOR -> RT.isVector(x);
                case MAP -> x instanceof IPersistentMap;
                case SET -> x instanceof IPersistentSet;
                case SEQ -> x instanceof ISeq;
                case NUMBER -> x instanceof Number;
                case INT -> Numbers.isInt(x);
                case KEYWORD -> x instanceof Keyword;
                case SYMBOL -> x instanceof Symbol;
            };
        }
    }
}
