package cljl;

import cljl.lang.*;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.*;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.*;
import java.util.concurrent.*;

/**
 * Extended clojure.core functions, macros, transducers, sorted collections,
 * hierarchies, STM/Agents/Tap concurrency, arrays, and protocols/records/types.
 */
public final class CoreExtras {

    private static final Object REDUCED_NONE = new Object();

    private static final Keyword KW_PARENTS = Keyword.intern("parents");
    private static final Keyword KW_ANCESTORS = Keyword.intern("ancestors");
    private static final Keyword KW_DESCENDANTS = Keyword.intern("descendants");

    private static volatile Var globalHierarchyVar;

    public static Var getGlobalHierarchy() {
        Var v = globalHierarchyVar;
        if (v == null) {
            synchronized (CoreExtras.class) {
                v = globalHierarchyVar;
                if (v == null) {
                    v = RT.CLOJURE_NS.intern("global-hierarchy", makeHierarchy());
                    globalHierarchyVar = v;
                }
            }
        }
        return v;
    }

    private static volatile CopyOnWriteArraySet<Fn> tapSet;

    private static CopyOnWriteArraySet<Fn> getTapSet() {
        CopyOnWriteArraySet<Fn> s = tapSet;
        if (s == null) {
            synchronized (CoreExtras.class) {
                s = tapSet;
                if (s == null) {
                    s = new CopyOnWriteArraySet<>();
                    tapSet = s;
                }
            }
        }
        return s;
    }

    private static final class TapExecutorHolder {
        static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "cljl-tap");
            t.setDaemon(true);
            return t;
        });
    }

    public static final ThreadLocal<MathContext> MATH_CONTEXT = new ThreadLocal<>();
    private static volatile Protocol instProto;

    private static volatile int doneMask = 0;
    private static int loadingMask = 0;

    private CoreExtras() {}

    public static void init(Namespace ns) {
        initAll(ns);
    }

    public static void initAll(Namespace ns) {
        for (int i = 0; i < CoreExtrasIndex.SUBSYSTEM_COUNT; i++) {
            initSubsystem(i);
        }
    }

    public static void initSubsystem(int id) {
        int bit = 1 << id;
        if ((doneMask & bit) != 0) return;
        synchronized (CoreExtras.class) {
            if ((doneMask & bit) != 0 || (loadingMask & bit) != 0) return;
            loadingMask |= bit;
            Namespace ns = RT.CLOJURE_NS;
            try {
                switch (id) {
                    case 0 -> initDynamicVarsAndTables(ns);
                    case 1 -> initPredicatesAndCasts(ns);
                    case 2 -> initMathAndParsing(ns);
                    case 3 -> initArrays(ns);
                    case 4 -> initSortedCollections(ns);
                    case 5 -> initSeqsAndMisc(ns);
                    case 6 -> initTransducers(ns);
                    case 7 -> initHierarchiesAndMulti(ns);
                    case 8 -> initNamespacesAndVars(ns);
                    case 9 -> initConcurrency(ns);
                    case 10 -> initProtocolsAndTypes(ns);
                }
            } finally {
                loadingMask &= ~bit;
            }
            doneMask |= bit;
        }
    }

    private static void def(Namespace ns, String name, Object val) {
        ns.intern(name, val);
    }

    private static void defMacro(Namespace ns, String name, Fn fn) {
        ns.internMacro(name, fn);
    }

    // ========================================================================
    // Dynamic Vars & Tables
    // ========================================================================

    private static void initDynamicVarsAndTables(Namespace ns) {
        def(ns, "clojure-version", new ExtraFn0(0, "clojure-version"));

        def(ns, "char-escape-string", PersistentHashMap.create(
                '\n', "\\n", '\t', "\\t", '\r', "\\r", '"', "\\\"",
                '\\', "\\\\", '\f', "\\f", '\b', "\\b"));

        def(ns, "char-name-string", PersistentHashMap.create(
                '\n', "newline", '\t', "tab", '\r', "return",
                ' ', "space", '\f', "formfeed", '\b', "backspace"));
    }

    // ========================================================================
    // Predicates, Reflection, Exceptions, Beans
    // ========================================================================

    private static void initPredicatesAndCasts(Namespace ns) {
        def(ns, "any?", new ExtraFn1(0, "any?"));
        def(ns, "bytes?", new ExtraFn1(1, "bytes?"));
        def(ns, "ident?", new ExtraFn1(2, "ident?"));
        def(ns, "simple-ident?", new ExtraFn1(3, "simple-ident?"));
        def(ns, "qualified-ident?", new ExtraFn1(4, "qualified-ident?"));
        def(ns, "indexed?", new ExtraFn1(5, "indexed?"));
        def(ns, "infinite?", new ExtraFn1(6, "infinite?"));
        def(ns, "NaN?", new ExtraFn1(7, "NaN?"));
        def(ns, "inst?", new ExtraFn1(8, "inst?"));
        def(ns, "uuid?", new ExtraFn1(9, "uuid?"));
        def(ns, "uri?", new ExtraFn1(10, "uri?"));
        def(ns, "map-entry?", new ExtraFn1(11, "map-entry?"));
        def(ns, "record?", new ExtraFn1(12, "record?"));
        def(ns, "reversible?", new ExtraFn1(13, "reversible?"));
        def(ns, "sorted?", new ExtraFn1(14, "sorted?"));

        def(ns, "cast", new ExtraFn2(0, "cast"));

        def(ns, "bases", new ExtraFn1(15, "bases"));

        def(ns, "supers", new ExtraFn1(16, "supers"));

        def(ns, "bean", new ExtraFn1(17, "bean"));

        def(ns, "StackTraceElement->vec", new ExtraFn1(18, "StackTraceElement->vec"));

        def(ns, "Throwable->map", new ExtraFn1(19, "Throwable->map"));
    }

    private static PersistentHashMap throwableToMap(Throwable t) {
        List<Object> via = new ArrayList<>();
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            PersistentHashMap entry = PersistentHashMap.EMPTY
                    .assoc(Keyword.intern("type"), Symbol.intern(cur.getClass().getName()))
                    .assoc(Keyword.intern("message"), cur.getMessage());
            if (cur instanceof Core.ExceptionInfo ei) {
                entry = entry.assoc(Keyword.intern("data"), ei.data);
            }
            via.add(entry);
        }
        StackTraceElement[] st = t.getStackTrace();
        List<Object> trace = new ArrayList<>(st.length);
        for (StackTraceElement ste : st) {
            trace.add(PersistentVector.createOwning(
                    Symbol.intern(ste.getClassName()),
                    Symbol.intern(ste.getMethodName()),
                    ste.getFileName(),
                    (long) ste.getLineNumber()));
        }
        PersistentHashMap res = PersistentHashMap.EMPTY
                .assoc(Keyword.intern("via"), PersistentVector.create(via))
                .assoc(Keyword.intern("trace"), PersistentVector.create(trace));
        if (t.getMessage() != null) res = res.assoc(Keyword.intern("cause"), t.getMessage());
        if (t instanceof Core.ExceptionInfo ei) res = res.assoc(Keyword.intern("data"), ei.data);
        return res;
    }

    // ========================================================================
    // Numbers, Parsing, Unchecked Math, Max/Min-Key, With-Precision
    // ========================================================================

    private static void initMathAndParsing(Namespace ns) {
        def(ns, "byte", new ExtraFn1(20, "byte"));
        def(ns, "short", new ExtraFn1(21, "short"));
        def(ns, "unchecked-byte", new ExtraFn1(22, "unchecked-byte"));
        def(ns, "unchecked-short", new ExtraFn1(23, "unchecked-short"));
        def(ns, "unchecked-int", new ExtraFn1(24, "unchecked-int"));
        def(ns, "unchecked-long", new ExtraFn1(25, "unchecked-long"));
        def(ns, "unchecked-char", new ExtraFn1(26, "unchecked-char"));
        def(ns, "unchecked-float", new ExtraFn1(27, "unchecked-float"));
        def(ns, "unchecked-double", new ExtraFn1(28, "unchecked-double"));
        def(ns, "unchecked-add-int", new ExtraFn2(1, "unchecked-add-int"));
        def(ns, "unchecked-subtract-int", new ExtraFn2(2, "unchecked-subtract-int"));
        def(ns, "unchecked-multiply-int", new ExtraFn2(3, "unchecked-multiply-int"));
        def(ns, "unchecked-inc-int", new ExtraFn1(29, "unchecked-inc-int"));
        def(ns, "unchecked-dec-int", new ExtraFn1(30, "unchecked-dec-int"));
        def(ns, "unchecked-negate-int", new ExtraFn1(31, "unchecked-negate-int"));
        def(ns, "unchecked-divide-int", new ExtraFn2(4, "unchecked-divide-int"));
        def(ns, "unchecked-remainder-int", new ExtraFn2(5, "unchecked-remainder-int"));
        def(ns, "numerator", new ExtraFn1(32, "numerator"));
        def(ns, "denominator", new ExtraFn1(33, "denominator"));
        def(ns, "hash-combine", new ExtraFn2(6, "hash-combine"));
        def(ns, "mix-collection-hash", new ExtraFn2(7, "mix-collection-hash"));
        def(ns, "hash-ordered-coll", new ExtraFn1(34, "hash-ordered-coll"));
        def(ns, "hash-unordered-coll", new ExtraFn1(35, "hash-unordered-coll"));
        def(ns, "munge", new ExtraFn1(36, "munge"));
        def(ns, "namespace-munge", new ExtraFn1(37, "namespace-munge"));

        Protocol instProto = new Protocol(Symbol.intern("clojure.core", "Inst"), "Protocol for instant-like types", null);
        CoreExtras.instProto = instProto;
        def(ns, "Inst", instProto);
        def(ns, "inst-ms*", new ExtraFn1(38, "inst-ms*"));
        instProto.addImpl(Date.class, Map.of(Keyword.intern("inst-ms*"), new ExtraFn1(39, "inst-ms*")));
        instProto.addImpl(java.time.Instant.class, Map.of(Keyword.intern("inst-ms*"), new ExtraFn1(40, "inst-ms*")));
        def(ns, "inst-ms", new ExtraFn1(41, "inst-ms"));
        def(ns, "num", new ExtraFn1(42, "num"));
        def(ns, "biginteger", new ExtraFn1(43, "biginteger"));
        def(ns, "rationalize", new ExtraFn1(44, "rationalize"));

        def(ns, "tagged-literal", new ExtraFn2(8, "tagged-literal"));
        def(ns, "tagged-literal?", new ExtraFn1(45, "tagged-literal?"));
        def(ns, "reader-conditional", new ExtraFn2(9, "reader-conditional"));
        def(ns, "reader-conditional?", new ExtraFn1(46, "reader-conditional?"));

        def(ns, "parse-boolean", new ExtraFn1(47, "parse-boolean"));
        def(ns, "parse-long", new ExtraFn1(48, "parse-long"));
        def(ns, "parse-double", new ExtraFn1(49, "parse-double"));
        def(ns, "parse-uuid", new ExtraFn1(50, "parse-uuid"));
        def(ns, "random-uuid", new ExtraFn0(1, "random-uuid"));

        Core.uncheckedAddFn = new ExtraFn2(10, "unchecked-add");
        Core.uncheckedSubFn = new ExtraFn2(11, "unchecked-subtract");
        Core.uncheckedMulFn = new ExtraFn2(12, "unchecked-multiply");
        Core.uncheckedIncFn = new ExtraFn1(51, "unchecked-inc");
        Core.uncheckedDecFn = new ExtraFn1(52, "unchecked-dec");
        def(ns, "unchecked-add", Core.uncheckedAddFn);
        def(ns, "unchecked-subtract", Core.uncheckedSubFn);
        def(ns, "unchecked-multiply", Core.uncheckedMulFn);
        def(ns, "unchecked-inc", Core.uncheckedIncFn);
        def(ns, "unchecked-dec", Core.uncheckedDecFn);
        def(ns, "unchecked-negate", new ExtraFn1(53, "unchecked-negate"));

        def(ns, "max-key", new ExtraFnV(0, "max-key"));
        def(ns, "min-key", new ExtraFnV(1, "min-key"));

        def(ns, "with-precision*", new ExtraFn3(0, "with-precision*"));
        defMacro(ns, "with-precision", new ExtraFnV(2, "with-precision"));
    }

    // ========================================================================
    // Java Arrays
    // ========================================================================

    private static void initArrays(Namespace ns) {
        def(ns, "booleans", new ExtraFn1(54, "booleans"));
        def(ns, "bytes", new ExtraFn1(55, "bytes"));
        def(ns, "chars", new ExtraFn1(56, "chars"));
        def(ns, "doubles", new ExtraFn1(57, "doubles"));
        def(ns, "floats", new ExtraFn1(58, "floats"));
        def(ns, "ints", new ExtraFn1(59, "ints"));
        def(ns, "longs", new ExtraFn1(60, "longs"));
        def(ns, "shorts", new ExtraFn1(61, "shorts"));

        def(ns, "float-array", new ExtraFn12(62, 13, "float-array"));

        def(ns, "short-array", new ExtraFn12(63, 14, "short-array"));

        def(ns, "make-array", new ExtraFnV(3, "make-array"));

        def(ns, "into-array", new ExtraFn12(64, 15, "into-array"));

        def(ns, "to-array-2d", new ExtraFn1(65, "to-array-2d"));

        def(ns, "aset-boolean", new ExtraFn3(1, "aset-boolean"));
        def(ns, "aset-byte", new ExtraFn3(2, "aset-byte"));
        def(ns, "aset-char", new ExtraFn3(3, "aset-char"));
        def(ns, "aset-short", new ExtraFn3(4, "aset-short"));
        def(ns, "aset-int", new ExtraFn3(5, "aset-int"));
        def(ns, "aset-long", new ExtraFn3(6, "aset-long"));
        def(ns, "aset-float", new ExtraFn3(7, "aset-float"));
        def(ns, "aset-double", new ExtraFn3(8, "aset-double"));

        // (amap a idx ret expr)
        defMacro(ns, "amap", new ExtraFnV(4, "amap"));

        // (areduce a idx ret init expr)
        defMacro(ns, "areduce", new ExtraFnV(5, "areduce"));
    }

    // ========================================================================
    // Sorted Collections & Vector-Of
    // ========================================================================

    @SuppressWarnings("unchecked")
    private static Comparator<Object> asComparator(Object cmp) {
        if (cmp == null) return PersistentTreeMap.DEFAULT_COMPARATOR;
        if (cmp instanceof Comparator<?> c) return (Comparator<Object>) c;
        if (cmp instanceof Fn f) return f.asComparator();
        throw new IllegalArgumentException("Expected comparator or fn: " + cmp);
    }

    private static void initSortedCollections(Namespace ns) {
        def(ns, "comparator", new ExtraFn1(66, "comparator"));

        def(ns, "sorted-map", new ExtraFnV(6, "sorted-map"));
        def(ns, "sorted-map-by", new ExtraFnV(7, "sorted-map-by"));
        def(ns, "sorted-set", new ExtraFnV(8, "sorted-set"));
        def(ns, "sorted-set-by", new ExtraFnV(9, "sorted-set-by"));

        def(ns, "subseq", new ExtraFnV(10, "subseq"));
        def(ns, "rsubseq", new ExtraFnV(11, "rsubseq"));

        def(ns, "vector-of", new ExtraFnV(12, "vector-of"));

        // Structs
        def(ns, "create-struct", new ExtraFnV(13, "create-struct"));
        defMacro(ns, "defstruct", new ExtraFnV(14, "defstruct"));
        def(ns, "struct", new ExtraFnV(15, "struct"));
        def(ns, "struct-map", new ExtraFnV(16, "struct-map"));
        def(ns, "accessor", new ExtraFn2(16, "accessor"));

        // Chunked sequences & primitive vector constructors
        def(ns, "chunk-buffer", new ExtraFn1(67, "chunk-buffer"));
        def(ns, "chunk-append", new ExtraFn2(17, "chunk-append"));
        def(ns, "chunk", new ExtraFn1(68, "chunk"));
        def(ns, "chunk-cons", new ExtraFn2(18, "chunk-cons"));
        def(ns, "chunk-first", new ExtraFn1(69, "chunk-first"));
        def(ns, "chunk-next", new ExtraFn1(70, "chunk-next"));
        def(ns, "chunk-rest", new ExtraFn1(71, "chunk-rest"));
        def(ns, "chunked-seq?", new ExtraFn1(72, "chunked-seq?"));
        def(ns, "->ArrayChunk", new ExtraFnV(17, "->ArrayChunk"));
        def(ns, "->VecNode", new ExtraFn2(19, "->VecNode"));
        def(ns, "->Vec", new ExtraFnV(18, "->Vec"));
        def(ns, "->VecSeq", new ExtraFnV(19, "->VecSeq"));
        def(ns, "->Eduction", new ExtraFn2(20, "->Eduction"));
        def(ns, "EMPTY-NODE", PersistentHashMap.BitmapIndexedNode.EMPTY);
        def(ns, "primitives-classnames", PersistentHashMap.create(
                Boolean.TYPE, "Boolean",
                Byte.TYPE, "Byte",
                Character.TYPE, "Character",
                Short.TYPE, "Short",
                Integer.TYPE, "Integer",
                Long.TYPE, "Long",
                Float.TYPE, "Float",
                Double.TYPE, "Double"));
    }

    @SuppressWarnings("unchecked")
    private static ISeq subseqImpl(Sorted sc, boolean asc, Object[] args, int idx) {
        Comparator<Object> comp = (Comparator<Object>) sc.comparator();
        if (args.length - idx == 2) {
            Fn test = (Fn) args[idx];
            Object key = args[idx + 1];
            ISeq start = asc ? (testNameEq(test, ">", ">=") ? sc.seqFrom(key, true) : sc.seq(true))
                             : (testNameEq(test, "<", "<=") ? sc.seqFrom(key, false) : sc.seq(false));
            List<Object> out = new ArrayList<>();
            for (ISeq s = start; s != null; s = s.next()) {
                Object entry = s.first();
                Object ek = sc.entryKey(entry);
                if (matchSubseqTest(comp, test, ek, key)) out.add(entry);
                else if (asc && testNameEq(test, "<", "<=")) break;
                else if (!asc && testNameEq(test, ">", ">=")) break;
            }
            return out.isEmpty() ? null : PersistentList.create(out);
        }
        Fn startTest = (Fn) args[idx];
        Object startKey = args[idx + 1];
        Fn endTest = (Fn) args[idx + 2];
        Object endKey = args[idx + 3];
        ISeq s = sc.seqFrom(asc ? startKey : endKey, asc);
        List<Object> out = new ArrayList<>();
        for (; s != null; s = s.next()) {
            Object entry = s.first();
            Object ek = sc.entryKey(entry);
            boolean okStart = matchSubseqTest(comp, startTest, ek, startKey);
            boolean okEnd = matchSubseqTest(comp, endTest, ek, endKey);
            if (okStart && okEnd) out.add(entry);
            else if (asc && !okEnd) break;
            else if (!asc && !okStart) break;
        }
        return out.isEmpty() ? null : PersistentList.create(out);
    }

    private static boolean matchSubseqTest(Comparator<Object> comp, Fn test, Object ek, Object key) {
        String n = test.getName();
        int c = comp.compare(ek, key);
        return switch (n) {
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            default -> RT.truthy(test.invoke(ek, key));
        };
    }

    private static boolean testNameEq(Fn f, String a, String b) {
        String n = f.getName();
        return a.equals(n) || b.equals(n);
    }

    // ========================================================================
    // Sequences, Map Helpers, Concurrency Helpers
    // ========================================================================

    private static void initSeqsAndMisc(Namespace ns) {
        def(ns, "not-empty", new ExtraFn1(73, "not-empty"));
        def(ns, "bounded-count", new ExtraFn2(21, "bounded-count"));

        defMacro(ns, "lazy-cat", new ExtraFnV(20, "lazy-cat"));

        def(ns, "line-seq", new ExtraFn1(74, "line-seq"));

        def(ns, "file-seq", new ExtraFn1(75, "file-seq"));

        def(ns, "enumeration-seq", new ExtraFn1(76, "enumeration-seq"));
        def(ns, "iterator-seq", new ExtraFn1(77, "iterator-seq"));

        def(ns, "xml-seq", new ExtraFn1(78, "xml-seq"));

        def(ns, "resultset-seq", new ExtraFn1(79, "resultset-seq"));

        def(ns, "seque", new ExtraFn12(80, 22, "seque"));

        def(ns, "iteration", new ExtraFnV(21, "iteration"));

        def(ns, "update-keys", new ExtraFn2(23, "update-keys"));

        def(ns, "update-vals", new ExtraFn2(24, "update-vals"));

        def(ns, "pcalls", new ExtraFnV(22, "pcalls"));

        defMacro(ns, "pvalues", new ExtraFnV(23, "pvalues"));

        def(ns, "test", new ExtraFn1(81, "test"));

        defMacro(ns, "memfn", new ExtraFnV(24, "memfn"));

        defMacro(ns, "definline", new ExtraFnV(25, "definline"));

        defMacro(ns, "locking", new ExtraFnV(26, "locking"));

        defMacro(ns, "io!", new ExtraFnV(27, "io!"));
        def(ns, "io!*", new ExtraFn2(25, "io!*"));
    }

    private static Object getOpt(Object[] args, String kwName) {
        Keyword k = Keyword.intern(kwName);
        for (int i = 1; i + 1 < args.length; i += 2) {
            if (k.equals(args[i])) return args[i + 1];
        }
        return null;
    }

    private static ISeq iterationSeq(Fn step, Fn somef, Fn vf, Fn kf, Object k, boolean first) {
        return new LazySeq(Fn.of0("iter-step", () -> {
            if (!first && k == null) return null;
            Object ret = step.invoke(k);
            if (!RT.truthy(somef.invoke(ret))) return null;
            Object val = vf.invoke(ret);
            Object nextk = (kf != null) ? kf.invoke(ret) : null;
            ISeq nextSeq = (nextk != null) ? iterationSeq(step, somef, vf, kf, nextk, false) : null;
            return new Cons(val, nextSeq);
        }));
    }

    private static ISeq lineSeqStep(BufferedReader br) {
        return new LazySeq(Fn.of0("line-seq-step", () -> {
            try {
                String line = br.readLine();
                if (line == null) return null;
                return new Cons(line, lineSeqStep(br));
            } catch (IOException e) { throw RT.sneakyThrow(e); }
        }));
    }

    private static ISeq sequeImpl(int n, Object s) {
        BlockingQueue<Object> q = new LinkedBlockingQueue<>(Math.max(1, n));
        Object END = new Object();
        Atom.VIRTUAL_EXECUTOR.execute(() -> {
            try {
                for (ISeq sq = RT.seq(s); sq != null; sq = sq.next()) {
                    Object item = sq.first();
                    q.put(item == null ? REDUCED_NONE : item);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                try { q.put(END); } catch (InterruptedException ignored) {}
            }
        });
        return sequeRead(q, END);
    }

    private static ISeq sequeRead(BlockingQueue<Object> q, Object END) {
        return new LazySeq(Fn.of0("seque-step", () -> {
            try {
                Object item = q.take();
                if (item == END) return null;
                return new Cons(item == REDUCED_NONE ? null : item, sequeRead(q, END));
            } catch (InterruptedException e) { throw RT.sneakyThrow(e); }
        }));
    }

    // ========================================================================
    // Transducers
    // ========================================================================

    private static void initTransducers(Namespace ns) {
        def(ns, "completing", new ExtraFn12(82, 26, "completing"));

        def(ns, "transduce", new ExtraFnV(28, "transduce"));

        def(ns, "cat", new ExtraFn1(83, "cat"));

        def(ns, "halt-when", new ExtraFn12(84, 27, "halt-when"));

        def(ns, "eduction", new ExtraFnV(29, "eduction"));

        def(ns, "sequence", new ExtraFnV(30, "sequence"));

        // Clojure 1.12 Java Stream helpers
        def(ns, "stream-seq!", new ExtraFn1(85, "stream-seq!"));
        def(ns, "stream-reduce!", new ExtraFn23(28, 9, "stream-reduce!"));
        def(ns, "stream-transduce!", new ExtraFnV(31, "stream-transduce!"));
        def(ns, "stream-into!", new ExtraFn23(29, 10, "stream-into!"));
        def(ns, "replace", new ExtraFn12(86, 30, "replace"));

        def(ns, "random-sample", new ExtraFn12(87, 31, "random-sample"));
    }

    @FunctionalInterface private interface XfMaker0 { Fn.S1 make(); }
    @FunctionalInterface private interface XfMaker1 { Fn.S1 make(Object a); }

    private static void wrapTransducer0(Namespace ns, String name, XfMaker0 maker) {
        Var v = ns.findInternedVar(Symbol.intern(name));
        Fn orig = (Fn) v.deref();
        v.bindRoot(new Fn.NamedFn(name) {
            @Override public Object invoke() { return Fn.of1(name + "-xf", maker.make()); }
            @Override public Object invoke(Object a) { return orig.invoke(a); }
            @Override public Object invokeVarargs(Object[] args) { return orig.applyTo(args); }
        });
    }

    private static void wrapTransducer1(Namespace ns, String name, XfMaker1 maker) {
        Var v = ns.findInternedVar(Symbol.intern(name));
        Fn orig = (Fn) v.deref();
        v.bindRoot(new Fn.NamedFn(name) {
            @Override public Object invoke(Object a) { return Fn.of1(name + "-xf", maker.make(a)); }
            @Override public Object invoke(Object a, Object b) { return orig.invoke(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return orig.invoke(a, b, c); }
            @Override public Object invoke(Object a, Object b, Object c, Object d) { return orig.invoke(a, b, c, d); }
            @Override public Object invokeVarargs(Object[] args) { return orig.applyTo(args); }
        });
    }

    private static Fn completing(Fn f, Fn cf) {
        return new Fn.NamedFn("completing") {
            @Override public Object invoke() { return f.invoke(); }
            @Override public Object invoke(Object acc) { return cf.invoke(acc); }
            @Override public Object invoke(Object acc, Object x) { return f.invoke(acc, x); }
            @Override public Object invoke(Object acc, Object k, Object v) { return f.invoke(acc, k, v); }
        };
    }

    private static Fn haltWhen(Fn pred, Fn retf) {
        return Fn.of1("halt-when-xf", rfObj -> {
            Fn rf = (Fn) rfObj;
            return new Fn.NamedFn("halt-when-step") {
                @Override public Object invoke() { return rf.invoke(); }
                @Override public Object invoke(Object acc) {
                    if (acc instanceof Map<?, ?> m && m.containsKey(REDUCED_NONE)) {
                        return m.get(REDUCED_NONE);
                    }
                    return rf.invoke(acc);
                }
                @Override public Object invoke(Object acc, Object x) {
                    if (RT.truthy(pred.invoke(x))) {
                        Object res = retf != null ? retf.invoke(rf.invoke(acc), x) : x;
                        return new Reduced(Collections.singletonMap(REDUCED_NONE, res));
                    }
                    return rf.invoke(acc, x);
                }
            };
        });
    }

    public static final class Eduction implements Seqable, Iterable<Object> {
        public final Fn xform;
        public final Object coll;

        public Eduction(Fn xform, Object coll) {
            this.xform = xform;
            this.coll = coll;
        }

        public PersistentVector realize() {
            return (PersistentVector) ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("into")).deref())
                    .invoke(PersistentVector.EMPTY, xform, coll);
        }

        @Override public ISeq seq() { return realize().seq(); }
        @Override public Iterator<Object> iterator() {
            ISeq s = seq();
            return new Iterator<>() {
                ISeq cur = s;
                @Override public boolean hasNext() { return cur != null; }
                @Override public Object next() { Object f = cur.first(); cur = cur.next(); return f; }
            };
        }
    }

    private static PersistentVector sequenceTransduce(Fn xf, Object coll) {
        List<Object> out = new ArrayList<>();
        Fn rf = (Fn) xf.invoke(new Fn.NamedFn("seq-conj") {
            @Override public Object invoke() { return out; }
            @Override public Object invoke(Object acc) { return acc; }
            @Override public Object invoke(Object acc, Object x) { out.add(x); return acc; }
        });
        Object acc = out;
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
            acc = rf.invoke(acc, s.first());
            if (acc instanceof Reduced r) { r.deref(); break; }
        }
        rf.invoke(out);
        return PersistentVector.create(out);
    }

    private static PersistentVector sequenceTransduceMulti(Fn xf, Object[] colls) {
        List<Object> out = new ArrayList<>();
        Fn rf = (Fn) xf.invoke(new Fn.NamedFn("seq-conj") {
            @Override public Object invoke() { return out; }
            @Override public Object invoke(Object acc) { return acc; }
            @Override public Object invoke(Object acc, Object x) { out.add(x); return acc; }
        });
        ISeq[] seqs = new ISeq[colls.length];
        for (int i = 0; i < colls.length; i++) seqs[i] = RT.seq(colls[i]);
        Object acc = out;
        while (true) {
            Object[] row = new Object[seqs.length + 1];
            row[0] = acc;
            boolean done = false;
            for (int i = 0; i < seqs.length; i++) {
                if (seqs[i] == null) { done = true; break; }
                row[i + 1] = seqs[i].first();
                seqs[i] = seqs[i].next();
            }
            if (done) break;
            acc = rf.applyTo(row);
            if (acc instanceof Reduced r) { r.deref(); break; }
        }
        rf.invoke(out);
        return PersistentVector.create(out);
    }

    // ========================================================================
    // Hierarchies & Multimethods
    // ========================================================================

    private static Object parentsOf(Object h, Object tag) {
        Object hp = RT.get(RT.get(h, KW_PARENTS), tag);
        if (!(tag instanceof Class<?> cls)) return hp;
        PersistentHashSet.TransientHashSet ts = PersistentHashSet.EMPTY.asTransient();
        if (hp != null) {
            for (ISeq s = RT.seq(hp); s != null; s = s.next()) ts = ts.conj(s.first());
        }
        if (cls.getSuperclass() != null) ts = ts.conj(cls.getSuperclass());
        for (Class<?> iface : cls.getInterfaces()) ts = ts.conj(iface);
        PersistentHashSet res = ts.persistent();
        return res.count() == 0 ? null : res;
    }

    private static Object ancestorsOf(Object h, Object tag) {
        Object hAncMap = RT.get(h, KW_ANCESTORS);
        Object ha = RT.get(hAncMap, tag);
        if (!(tag instanceof Class<?> cls)) return ha;
        PersistentHashSet.TransientHashSet ts = PersistentHashSet.EMPTY.asTransient();
        if (ha != null) {
            for (ISeq s = RT.seq(ha); s != null; s = s.next()) ts = ts.conj(s.first());
        }
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> q = new ArrayDeque<>();
        if (cls.getSuperclass() != null) q.add(cls.getSuperclass());
        Collections.addAll(q, cls.getInterfaces());
        while (!q.isEmpty()) {
            Class<?> cur = q.poll();
            if (seen.add(cur)) {
                ts = ts.conj(cur);
                Object curAnc = RT.get(hAncMap, cur);
                if (curAnc != null) {
                    for (ISeq s = RT.seq(curAnc); s != null; s = s.next()) ts = ts.conj(s.first());
                }
                if (cur.getSuperclass() != null) q.add(cur.getSuperclass());
                Collections.addAll(q, cur.getInterfaces());
            }
        }
        PersistentHashSet res = ts.persistent();
        return res.count() == 0 ? null : res;
    }

    public static PersistentHashMap makeHierarchy() {
        return PersistentHashMap.create(
                KW_PARENTS, PersistentHashMap.EMPTY,
                KW_ANCESTORS, PersistentHashMap.EMPTY,
                KW_DESCENDANTS, PersistentHashMap.EMPTY);
    }

    public static boolean isa(Object child, Object parent) {
        return isa(getGlobalHierarchy().deref(), child, parent);
    }

    public static boolean isa(Object hObj, Object child, Object parent) {
        if (RT.equiv(child, parent)) return true;
        if (child instanceof Class<?> cc && parent instanceof Class<?> pc) {
            return pc.isAssignableFrom(cc);
        }
        if (child instanceof PersistentVector cv && parent instanceof PersistentVector pv) {
            if (cv.count() != pv.count()) return false;
            for (int i = 0; i < cv.count(); i++) {
                if (!isa(hObj, cv.nth(i), pv.nth(i))) return false;
            }
            return true;
        }
        if (hObj instanceof Map<?, ?> h) {
            Object ancestors = ((Map<?, ?>) h).get(KW_ANCESTORS);
            if (ancestors != null) {
                Object childAncs = RT.get(ancestors, child);
                if (childAncs != null && RT.contains(childAncs, parent)) return true;
                if (child instanceof Class<?> cc) {
                    for (ISeq s = RT.seq(ancestors); s != null; s = s.next()) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        if (e.getKey() instanceof Class<?> kc && kc.isAssignableFrom(cc)) {
                            if (RT.equiv(kc, parent) || RT.contains(e.getValue(), parent)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static PersistentHashMap addToSetMap(PersistentHashMap m, Object k, Object v) {
        PersistentHashSet s = (PersistentHashSet) m.valAt(k, PersistentHashSet.EMPTY);
        return m.assoc(k, s.conj(v));
    }

    public static PersistentHashMap deriveHierarchy(PersistentHashMap h, Object tag, Object parent) {
        if (RT.equiv(tag, parent)) throw new IllegalArgumentException("Cannot derive self");
        if (isa(h, parent, tag)) throw new IllegalArgumentException("Cyclic derivation: " + tag + " -> " + parent);
        PersistentHashMap parents = (PersistentHashMap) h.valAt(KW_PARENTS, PersistentHashMap.EMPTY);
        PersistentHashMap ancestors = (PersistentHashMap) h.valAt(KW_ANCESTORS, PersistentHashMap.EMPTY);
        PersistentHashMap descendants = (PersistentHashMap) h.valAt(KW_DESCENDANTS, PersistentHashMap.EMPTY);

        parents = addToSetMap(parents, tag, parent);
        PersistentHashSet parentAncs = (PersistentHashSet) ancestors.valAt(parent, PersistentHashSet.EMPTY);
        PersistentHashSet tagDescs = (PersistentHashSet) descendants.valAt(tag, PersistentHashSet.EMPTY);

        Set<Object> allAncsOfParent = new LinkedHashSet<>();
        allAncsOfParent.add(parent);
        for (ISeq s = parentAncs.seq(); s != null; s = s.next()) allAncsOfParent.add(s.first());

        Set<Object> allDescsOfTag = new LinkedHashSet<>();
        allDescsOfTag.add(tag);
        for (ISeq s = tagDescs.seq(); s != null; s = s.next()) allDescsOfTag.add(s.first());

        for (Object d : allDescsOfTag) {
            for (Object a : allAncsOfParent) {
                ancestors = addToSetMap(ancestors, d, a);
                descendants = addToSetMap(descendants, a, d);
            }
        }
        return PersistentHashMap.create(KW_PARENTS, parents, KW_ANCESTORS, ancestors, KW_DESCENDANTS, descendants);
    }

    public static PersistentHashMap underiveHierarchy(PersistentHashMap h, Object tag, Object parent) {
        PersistentHashMap parents = (PersistentHashMap) h.valAt(KW_PARENTS, PersistentHashMap.EMPTY);
        PersistentHashSet pset = (PersistentHashSet) parents.valAt(tag);
        if (pset == null || !pset.contains(parent)) return h;
        PersistentHashSet newP = pset.disjoin(parent);
        parents = newP.count() == 0 ? parents.without(tag) : parents.assoc(tag, newP);
        PersistentHashMap res = makeHierarchy();
        for (ISeq s = parents.seq(); s != null; s = s.next()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
            for (ISeq ps = RT.seq(e.getValue()); ps != null; ps = ps.next()) {
                res = deriveHierarchy(res, e.getKey(), ps.first());
            }
        }
        return res;
    }

    private static void initHierarchiesAndMulti(Namespace ns) {
        getGlobalHierarchy();
        def(ns, "make-hierarchy", new ExtraFn0(2, "make-hierarchy"));
        def(ns, "derive", new ExtraFn23(32, 11, "derive"));
        def(ns, "underive", new ExtraFn23(33, 12, "underive"));
        def(ns, "parents", new ExtraFn12(88, 34, "parents"));
        def(ns, "ancestors", new ExtraFn12(89, 35, "ancestors"));
        def(ns, "descendants", new ExtraFn12(90, 36, "descendants"));

        def(ns, "prefer-method", new ExtraFn3(13, "prefer-method"));
        def(ns, "prefers", new ExtraFn1(91, "prefers"));
        def(ns, "remove-all-methods", new ExtraFn1(92, "remove-all-methods"));
    }

    // ========================================================================
    // Namespaces, Vars, Meta, Bindings
    // ========================================================================

    private static Namespace resolveNsArg(Object nsOrSym) {
        if (nsOrSym instanceof Namespace n) return n;
        if (nsOrSym instanceof Symbol s) return Namespace.find(s);
        return null;
    }

    private static void initNamespacesAndVars(Namespace ns) {
        def(ns, "the-ns", new ExtraFn1(93, "the-ns"));
        def(ns, "ns-interns", new ExtraFn1(94, "ns-interns"));
        def(ns, "ns-refers", new ExtraFn1(95, "ns-refers"));
        def(ns, "ns-imports", new ExtraFn1(96, "ns-imports"));
        def(ns, "ns-resolve", new ExtraFn23(37, 21, "ns-resolve"));
        def(ns, "ns-unalias", new ExtraFn2(38, "ns-unalias"));
        def(ns, "ns-unmap", new ExtraFn2(39, "ns-unmap"));
        def(ns, "intern", new ExtraFn23(40, 14, "intern"));
        def(ns, "requiring-resolve", new ExtraFn1(97, "requiring-resolve"));
        def(ns, "loaded-libs", new ExtraFn0(3, "loaded-libs"));
        def(ns, "load", new ExtraFnV(32, "load"));
        def(ns, "load-reader", new ExtraFn1(98, "load-reader"));
        def(ns, "compile", new ExtraFn1(99, "compile"));

        def(ns, "alter-meta!", new ExtraFnV(33, "alter-meta!"));
        def(ns, "reset-meta!", new ExtraFn2(41, "reset-meta!"));

        def(ns, "get-thread-bindings", new ExtraFn0(4, "get-thread-bindings"));
        def(ns, "thread-bound?", new ExtraFnV(34, "thread-bound?"));
        def(ns, "bound-fn*", new ExtraFn1(100, "bound-fn*"));
        defMacro(ns, "bound-fn", new ExtraFnV(35, "bound-fn"));

        defMacro(ns, "with-local-vars", new ExtraFnV(36, "with-local-vars"));

        def(ns, "with-redefs-fn", new ExtraFn2(42, "with-redefs-fn"));

        defMacro(ns, "with-redefs", new ExtraFnV(37, "with-redefs"));
    }

    // ========================================================================
    // Concurrency: Tap, Agents, STM Refs
    // ========================================================================

    private static void initConcurrency(Namespace ns) {
        // Tap
        def(ns, "add-tap", new ExtraFn1(101, "add-tap"));
        def(ns, "remove-tap", new ExtraFn1(102, "remove-tap"));
        def(ns, "tap>", new ExtraFn1(103, "tap>"));

        // Agents
        def(ns, "agent", new ExtraFnV(38, "agent"));
        def(ns, "send", new ExtraFnV(39, "send"));
        def(ns, "send-off", new ExtraFnV(40, "send-off"));
        def(ns, "send-via", new ExtraFnV(41, "send-via"));
        def(ns, "await", new ExtraFnV(42, "await"));
        def(ns, "await-for", new ExtraFnV(43, "await-for"));
        def(ns, "agent-error", new ExtraFn1(104, "agent-error"));
        def(ns, "agent-errors", new ExtraFn1(105, "agent-errors"));
        def(ns, "clear-agent-errors", new ExtraFn1(106, "clear-agent-errors"));
        def(ns, "await1", new ExtraFn1(107, "await1"));
        def(ns, "restart-agent", new ExtraFnV(44, "restart-agent"));
        def(ns, "set-error-handler!", new ExtraFn2(43, "set-error-handler!"));
        def(ns, "error-handler", new ExtraFn1(108, "error-handler"));
        def(ns, "set-error-mode!", new ExtraFn2(44, "set-error-mode!"));
        def(ns, "error-mode", new ExtraFn1(109, "error-mode"));
        def(ns, "release-pending-sends", new ExtraFn0(5, "release-pending-sends"));
        def(ns, "shutdown-agents", new ExtraFn0(6, "shutdown-agents"));
        def(ns, "set-agent-send-executor!", new ExtraFn1(110, "set-agent-send-executor!"));
        def(ns, "set-agent-send-off-executor!", new ExtraFn1(111, "set-agent-send-off-executor!"));

        // Refs / STM
        def(ns, "ref", new ExtraFnV(45, "ref"));
        def(ns, "dosync*", new ExtraFn1(112, "dosync*"));
        defMacro(ns, "dosync", new ExtraFnV(46, "dosync"));
        defMacro(ns, "sync", new ExtraFnV(47, "sync"));
        def(ns, "ref-set", new ExtraFn2(45, "ref-set"));
        def(ns, "alter", new ExtraFnV(48, "alter"));
        def(ns, "commute", new ExtraFnV(49, "commute"));
        def(ns, "ensure", new ExtraFn1(113, "ensure"));
        def(ns, "ref-history-count", new ExtraFn1(114, "ref-history-count"));
        def(ns, "ref-min-history", new ExtraFn12(115, 46, "ref-min-history"));
        def(ns, "ref-max-history", new ExtraFn12(116, 47, "ref-max-history"));
    }

    // ========================================================================
    // Protocols, Records, Types, Interfaces, Reify, Proxy
    // ========================================================================

    private static void initProtocolsAndTypes(Namespace ns) {
        def(ns, "satisfies?", new ExtraFn2(48, "satisfies?"));
        def(ns, "extends?", new ExtraFn2(49, "extends?"));
        def(ns, "extenders", new ExtraFn1(117, "extenders"));
        def(ns, "find-protocol-impl", new ExtraFn2(50, "find-protocol-impl"));
        def(ns, "find-protocol-method", new ExtraFn3(15, "find-protocol-method"));
        def(ns, "-cache-protocol-fn", new ExtraFnV(50, "-cache-protocol-fn"));
        def(ns, "-reset-methods", new ExtraFn1(118, "-reset-methods"));

        def(ns, "extend", new ExtraFnV(51, "extend"));

        // (defprotocol Name doc? (method [this ...] doc?)*)
        def(ns, "register-protocol*", new ExtraFnV(70, "register-protocol*"));
        def(ns, "protocol-dispatch*", new ExtraFn3(16, "protocol-dispatch*"));
        def(ns, "make-protocol-fn*", new ExtraFn3(20, "make-protocol-fn*"));
        defMacro(ns, "defprotocol", new ExtraFnV(52, "defprotocol"));

        defMacro(ns, "extend-type", new ExtraFnV(53, "extend-type"));
        defMacro(ns, "extend-protocol", new ExtraFnV(54, "extend-protocol"));

        // (defrecord Name [fields...] specs...)
        def(ns, "register-record*", new ExtraFnV(55, "register-record*"));
        def(ns, "register-record-class*", new ExtraFnV(68, "register-record-class*"));
        def(ns, "register-record-impls*", new ExtraFnV(69, "register-record-impls*"));
        defMacro(ns, "defrecord", new ExtraFnV(56, "defrecord"));

        def(ns, "register-type*", new ExtraFnV(57, "register-type*"));
        def(ns, "register-type-class*", new ExtraFnV(71, "register-type-class*"));
        def(ns, "register-type-impls*", new ExtraFnV(72, "register-type-impls*"));
        defMacro(ns, "deftype", new ExtraFnV(58, "deftype"));

        def(ns, "reify*", new ExtraFnV(59, "reify*"));
        defMacro(ns, "reify", new ExtraFnV(60, "reify"));

        def(ns, "definterface*", new ExtraFn2(52, "definterface*"));
        defMacro(ns, "definterface", new ExtraFnV(61, "definterface"));

        def(ns, "gen-interface", new ExtraFnV(62, "gen-interface"));
        def(ns, "gen-class", new ExtraFnV(63, "gen-class"));

        // Proxy family
        def(ns, "get-proxy-class", new ExtraFnV(64, "get-proxy-class"));
        def(ns, "construct-proxy", new ExtraFnV(65, "construct-proxy"));
        def(ns, "init-proxy", new ExtraFn2(53, "init-proxy"));
        def(ns, "update-proxy", new ExtraFn2(54, "update-proxy"));
        def(ns, "proxy-mappings", new ExtraFn1(119, "proxy-mappings"));
        def(ns, "proxy-name", new ExtraFn2(55, "proxy-name"));
        def(ns, "proxy-call-with-super", new ExtraFn3(17, "proxy-call-with-super"));
        def(ns, "method-sig", new ExtraFn1(120, "method-sig"));

        // Printing multimethods & PrintWriter helpers
        MultiFn printMethod = new MultiFn("print-method", new ExtraFn2(56, "print-method-dispatch"), Keyword.intern("default"));
        printMethod.addMethod(Keyword.intern("default"), new ExtraFn2(57, "print-method-default"));
        RT.PRINT_METHOD_FN = printMethod;
        def(ns, "print-method", printMethod);

        MultiFn printDup = new MultiFn("print-dup", new ExtraFn2(58, "print-dup-dispatch"), Keyword.intern("default"));
        printDup.addMethod(Keyword.intern("default"), new ExtraFn2(59, "print-dup-default"));
        RT.PRINT_DUP_FN = printDup;
        def(ns, "print-dup", printDup);

        def(ns, "print-simple", new ExtraFn2(60, "print-simple"));
        def(ns, "pr-on", new ExtraFn2(62, "pr-on"));
        def(ns, "print-ctor", new ExtraFn3(18, "print-ctor"));
        def(ns, "PrintWriter-on", new ExtraFn23(61, 19, "PrintWriter-on"));

        defMacro(ns, "proxy", new ExtraFnV(66, "proxy"));

        defMacro(ns, "proxy-super", new ExtraFnV(67, "proxy-super"));
    }

    private static PersistentVector insertThisFirst(PersistentVector pv) {
        List<Object> params = new ArrayList<>(pv.count() + 1);
        params.add(Symbol.intern("this"));
        for (int i = 0; i < pv.count(); i++) params.add(pv.nth(i));
        return PersistentVector.create(params);
    }

    private static Object expandExtendType(Object[] args) {
        Object typeSym = args[0];
        List<Object> forms = new ArrayList<>();
        forms.add(Symbol.intern("do"));
        int i = 1;
        while (i < args.length) {
            Object protoSym = args[i++];
            Map<Keyword, List<Object>> methods = new LinkedHashMap<>();
            while (i < args.length && args[i] instanceof ISeq ms) {
                Symbol mname = (Symbol) ms.first();
                methods.put(Keyword.intern(mname.name), List.of(ms.next()));
                i++;
            }
            List<Object> kvs = new ArrayList<>();
            for (Map.Entry<Keyword, List<Object>> e : methods.entrySet()) {
                kvs.add(e.getKey());
                ISeq body = (ISeq) e.getValue().get(0);
                kvs.add(RT.cons(Symbol.intern("clojure.core", "fn"), body));
            }
            forms.add(PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "extend"),
                    typeSym,
                    protoSym,
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(kvs))));
        }
        return PersistentList.create(forms);
    }

    private static Object expandTypeOrRecord(Object[] args, boolean isRecord) {
        Symbol nameSym = (Symbol) args[0];
        PersistentVector fieldsVec = (PersistentVector) args[1];
        List<Object> fieldNames = new ArrayList<>();
        List<Object> fieldTypeTags = new ArrayList<>();
        List<Object> mutFlags = new ArrayList<>();
        Set<String> mutableSet = new HashSet<>();
        Keyword volKw = Keyword.intern("volatile-mutable");
        Keyword unsyncKw = Keyword.intern("unsynchronized-mutable");
        Keyword tagKw = Keyword.intern("tag");
        for (int i = 0; i < fieldsVec.count(); i++) {
            Symbol fs = (Symbol) fieldsVec.nth(i);
            fieldNames.add(fs.name);
            PersistentHashMap fm = fs.meta();
            String ftype = "O";
            if (isRecord && fm != null) {
                Object tag = fm.valAt(tagKw);
                if (tag != null) {
                    String ts = tag.toString();
                    if ("long".equals(ts)) ftype = "J";
                    else if ("double".equals(ts)) ftype = "D";
                }
            }
            fieldTypeTags.add(ftype);
            boolean isMut = !isRecord && fm != null
                    && (RT.truthy(fm.valAt(volKw)) || RT.truthy(fm.valAt(unsyncKw)));
            mutFlags.add(isMut);
            if (isMut) mutableSet.add(fs.name);
        }

        List<Object> specs = new ArrayList<>();
        Map<String, List<ISeq>> allJavaGrouped = new LinkedHashMap<>();
        List<Object> protoMapKVs = new ArrayList<>();
        Set<String> javaMethodNameSet = new LinkedHashSet<>();

        int i = 2;
        while (i < args.length) {
            Object spec = args[i++];
            specs.add(spec);
            Map<String, List<ISeq>> specGrouped = new LinkedHashMap<>();
            while (i < args.length && args[i] instanceof ISeq ms) {
                Symbol mname = (Symbol) ms.first();
                ISeq mbody = wrapMethodWithFields(fieldsVec, ms.next(), isRecord, mutableSet, isRecord ? nameSym : null);
                String mn = mname.name;
                javaMethodNameSet.add(mn);
                addMethodClause(allJavaGrouped, mn, mbody);
                addMethodClause(specGrouped, mn, mbody);
                i++;
            }
            List<Object> protoM = new ArrayList<>();
            for (Map.Entry<String, List<ISeq>> e : specGrouped.entrySet()) {
                protoM.add(Keyword.intern(e.getKey()));
                protoM.add(buildMethodFnForm(e.getValue()));
            }
            protoMapKVs.add(spec);
            protoMapKVs.add(RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoM)));
        }
        List<Object> javaMethodNames = new ArrayList<>(javaMethodNameSet);
        List<Object> javaMapKVs = new ArrayList<>();
        for (Map.Entry<String, List<ISeq>> e : allJavaGrouped.entrySet()) {
            javaMapKVs.add(e.getKey());
            javaMapKVs.add(buildMethodFnForm(e.getValue()));
        }

        if (isRecord) {
            Object defineClsForm = PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "register-record-class*"),
                    PersistentList.createFromArray(Symbol.intern("quote"), nameSym),
                    PersistentVector.create(fieldNames),
                    PersistentVector.create(specs),
                    PersistentVector.create(javaMethodNames),
                    PersistentVector.create(fieldTypeTags));
            Object attachImplsForm = PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "register-record-impls*"),
                    PersistentList.createFromArray(Symbol.intern("quote"), nameSym),
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(javaMapKVs)),
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoMapKVs)));
            return PersistentList.createFromArray(Symbol.intern("do"), defineClsForm, attachImplsForm);
        }
        Object defineTypeClsForm = PersistentList.createFromArray(
                Symbol.intern("clojure.core", "register-type-class*"),
                PersistentList.createFromArray(Symbol.intern("quote"), nameSym),
                PersistentVector.create(fieldNames),
                PersistentVector.create(specs),
                PersistentVector.create(javaMethodNames),
                PersistentVector.create(mutFlags));
        Object attachTypeImplsForm = PersistentList.createFromArray(
                Symbol.intern("clojure.core", "register-type-impls*"),
                PersistentList.createFromArray(Symbol.intern("quote"), nameSym),
                RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(javaMapKVs)),
                RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoMapKVs)));
        return PersistentList.createFromArray(Symbol.intern("do"), defineTypeClsForm, attachTypeImplsForm);
    }

    private static ISeq wrapMethodWithFields(
            PersistentVector fields, ISeq methodRest, boolean isRecord, Set<String> mutableSet, Symbol recordClassSym) {
        if (methodRest.first() instanceof PersistentVector params) {
            return wrapSingleArity(fields, params, methodRest.next(), isRecord, mutableSet, recordClassSym);
        }
        List<Object> clauses = new ArrayList<>();
        for (ISeq s = methodRest; s != null; s = s.next()) {
            ISeq arity = (ISeq) s.first();
            clauses.add(wrapSingleArity(fields, (PersistentVector) arity.first(), arity.next(), isRecord, mutableSet, recordClassSym));
        }
        return PersistentList.create(clauses);
    }

    private static boolean formReferencesSym(Object form, String name) {
        if (form instanceof Symbol s) {
            return s.ns == null && name.equals(s.name);
        }
        if (form instanceof ISeq seq) {
            for (ISeq s = seq.seq(); s != null; s = s.next()) {
                if (formReferencesSym(s.first(), name)) return true;
            }
            return false;
        }
        if (form instanceof PersistentVector pv) {
            for (int i = 0; i < pv.count(); i++) {
                if (formReferencesSym(pv.nth(i), name)) return true;
            }
            return false;
        }
        if (form instanceof PersistentHashMap pm) {
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                if (formReferencesSym(e.getKey(), name) || formReferencesSym(e.getValue(), name)) return true;
            }
            return false;
        }
        if (form instanceof PersistentHashSet ps) {
            for (ISeq s = ps.seq(); s != null; s = s.next()) {
                if (formReferencesSym(s.first(), name)) return true;
            }
            return false;
        }
        return false;
    }

    private static ISeq wrapSingleArity(
            PersistentVector fields, PersistentVector params, ISeq bodyForms,
            boolean isRecord, Set<String> mutableSet, Symbol recordClassSym) {
        Object thisSym = params.nth(0);
        PersistentVector effectiveParams = params;
        if (isRecord && recordClassSym != null && thisSym instanceof Symbol ts) {
            PersistentHashMap curMeta = ts.meta();
            if (curMeta == null || !curMeta.containsKey(Keyword.intern("tag"))) {
                PersistentHashMap newMeta = (curMeta != null ? curMeta : PersistentHashMap.EMPTY)
                        .assoc(Keyword.intern("tag"), recordClassSym);
                Symbol hintedThis = ts.withMeta(newMeta);
                thisSym = hintedThis;
                effectiveParams = (PersistentVector) params.assoc(0, hintedThis);
            }
        }
        List<Object> lets = new ArrayList<>();
        for (int i = 0; i < fields.count(); i++) {
            Symbol f = (Symbol) fields.nth(i);
            if (!isRecord && mutableSet.contains(f.name)) continue;
            if (isRecord && !formReferencesSym(bodyForms, f.name)) continue;
            lets.add(f);
            lets.add(PersistentList.createFromArray(Symbol.intern("."), thisSym, Symbol.intern("-" + f.name)));
        }
        Set<String> shadowed = new HashSet<>();
        collectBindingSyms(effectiveParams, shadowed);
        List<Object> body = new ArrayList<>();
        body.add(Symbol.intern("do"));
        for (ISeq s = bodyForms; s != null; s = s.next()) {
            Object b = mutableSet.isEmpty() ? s.first() : rewriteMutableFields(s.first(), thisSym, mutableSet, shadowed);
            body.add(b);
        }
        Object bodyExpr = PersistentList.create(body);
        if (lets.isEmpty()) {
            return PersistentList.createFromArray(effectiveParams, bodyExpr);
        }
        Object letForm = PersistentList.createFromArray(
                Symbol.intern("let"), PersistentVector.create(lets), bodyExpr);
        return PersistentList.createFromArray(effectiveParams, letForm);
    }

    private static void collectBindingSyms(Object pat, Set<String> out) {
        if (pat instanceof Symbol s && s.ns == null && !"&".equals(s.name)) {
            out.add(s.name);
        } else if (pat instanceof PersistentVector pv) {
            for (int i = 0; i < pv.count(); i++) {
                Object elem = pv.nth(i);
                if (Keyword.intern("as").equals(elem) && i + 1 < pv.count()) {
                    collectBindingSyms(pv.nth(++i), out);
                } else {
                    collectBindingSyms(elem, out);
                }
            }
        } else if (pat instanceof PersistentHashMap pm) {
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                Object k = e.getKey(), v = e.getValue();
                if (Keyword.intern("keys").equals(k) || Keyword.intern("strs").equals(k) || Keyword.intern("syms").equals(k)) {
                    for (ISeq ks = RT.seq(v); ks != null; ks = ks.next()) {
                        if (ks.first() instanceof Symbol sy) out.add(sy.name);
                        else if (ks.first() instanceof Keyword kw) out.add(kw.getName());
                        else if (ks.first() instanceof String st) out.add(st);
                    }
                } else if (Keyword.intern("as").equals(k) && v instanceof Symbol sy) {
                    out.add(sy.name);
                } else if (!Keyword.intern("or").equals(k)) {
                    collectBindingSyms(k, out);
                }
            }
        }
    }

    private static Object rewriteMutableFields(Object form, Object thisSym, Set<String> mutableSet, Set<String> shadowed) {
        if (form instanceof Symbol s && s.ns == null) {
            if (mutableSet.contains(s.name) && !shadowed.contains(s.name)) {
                return PersistentList.createFromArray(Symbol.intern("."), thisSym, Symbol.intern(s.name));
            }
            return form;
        }
        if (form instanceof ISeq seq) {
            ISeq sq = seq.seq();
            if (sq == null) return form;
            Object head = sq.first();
            if (Symbol.intern("quote").equals(head)) return form;
            if (Symbol.intern("set!").equals(head) && sq.next() != null && sq.next().next() != null) {
                Object target = sq.next().first();
                Object valExpr = rewriteMutableFields(sq.next().next().first(), thisSym, mutableSet, shadowed);
                if (target instanceof Symbol ts && ts.ns == null
                        && mutableSet.contains(ts.name) && !shadowed.contains(ts.name)) {
                    return PersistentList.createFromArray(
                            head,
                            PersistentList.createFromArray(Symbol.intern("."), thisSym, Symbol.intern(ts.name)),
                            valExpr);
                }
                return PersistentList.createFromArray(head, rewriteMutableFields(target, thisSym, mutableSet, shadowed), valExpr);
            }
            if ((Symbol.intern("let").equals(head) || Symbol.intern("let*").equals(head)
                    || Symbol.intern("loop").equals(head) || Symbol.intern("loop*").equals(head))
                    && sq.next() != null && sq.next().first() instanceof PersistentVector bvec) {
                Set<String> curShadowed = new HashSet<>(shadowed);
                List<Object> newBinds = new ArrayList<>(bvec.count());
                for (int i = 0; i + 1 < bvec.count(); i += 2) {
                    Object bpat = bvec.nth(i);
                    Object binit = rewriteMutableFields(bvec.nth(i + 1), thisSym, mutableSet, curShadowed);
                    newBinds.add(bpat);
                    newBinds.add(binit);
                    collectBindingSyms(bpat, curShadowed);
                }
                List<Object> out = new ArrayList<>();
                out.add(head);
                out.add(PersistentVector.create(newBinds));
                for (ISeq b = sq.next().next(); b != null; b = b.next()) {
                    out.add(rewriteMutableFields(b.first(), thisSym, mutableSet, curShadowed));
                }
                return PersistentList.create(out);
            }
            if (Symbol.intern("fn").equals(head) || Symbol.intern("fn*").equals(head)) {
                ISeq cur = sq.next();
                List<Object> out = new ArrayList<>();
                out.add(head);
                if (cur != null && cur.first() instanceof Symbol fnName) {
                    out.add(fnName);
                    cur = cur.next();
                }
                if (cur != null && cur.first() instanceof PersistentVector params) {
                    out.add(params);
                    Set<String> fnShadowed = new HashSet<>(shadowed);
                    collectBindingSyms(params, fnShadowed);
                    for (ISeq b = cur.next(); b != null; b = b.next()) {
                        out.add(rewriteMutableFields(b.first(), thisSym, mutableSet, fnShadowed));
                    }
                    return PersistentList.create(out);
                }
                for (ISeq cl = cur; cl != null; cl = cl.next()) {
                    if (cl.first() instanceof ISeq arity && arity.first() instanceof PersistentVector params) {
                        Set<String> fnShadowed = new HashSet<>(shadowed);
                        collectBindingSyms(params, fnShadowed);
                        List<Object> aOut = new ArrayList<>();
                        aOut.add(params);
                        for (ISeq b = arity.next(); b != null; b = b.next()) {
                            aOut.add(rewriteMutableFields(b.first(), thisSym, mutableSet, fnShadowed));
                        }
                        out.add(PersistentList.create(aOut));
                    } else {
                        out.add(cl.first());
                    }
                }
                return PersistentList.create(out);
            }
            List<Object> out = new ArrayList<>();
            for (ISeq s = sq; s != null; s = s.next()) {
                out.add(rewriteMutableFields(s.first(), thisSym, mutableSet, shadowed));
            }
            return PersistentList.create(out);
        }
        if (form instanceof PersistentVector pv) {
            List<Object> out = new ArrayList<>(pv.count());
            for (int i = 0; i < pv.count(); i++) {
                out.add(rewriteMutableFields(pv.nth(i), thisSym, mutableSet, shadowed));
            }
            return PersistentVector.create(out);
        }
        if (form instanceof PersistentHashMap pm) {
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                tm = tm.assoc(
                        rewriteMutableFields(e.getKey(), thisSym, mutableSet, shadowed),
                        rewriteMutableFields(e.getValue(), thisSym, mutableSet, shadowed));
            }
            return tm.persistent();
        }
        if (form instanceof PersistentHashSet ps) {
            PersistentHashSet.TransientHashSet ts = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = ps.seq(); s != null; s = s.next()) {
                ts = ts.conj(rewriteMutableFields(s.first(), thisSym, mutableSet, shadowed));
            }
            return ts.persistent();
        }
        return form;
    }

    private static void addMethodClause(Map<String, List<ISeq>> grouped, String mn, ISeq mbody) {
        List<ISeq> list = grouped.computeIfAbsent(mn, k -> new ArrayList<>());
        if (mbody.first() instanceof PersistentVector) {
            list.add(mbody);
        } else {
            for (ISeq s = mbody; s != null; s = s.next()) {
                list.add((ISeq) s.first());
            }
        }
    }

    private static ISeq buildMethodFnForm(List<ISeq> arities) {
        if (arities.size() == 1) {
            return RT.cons(Symbol.intern("clojure.core", "fn"), arities.get(0));
        }
        return RT.cons(Symbol.intern("clojure.core", "fn"), PersistentList.create(arities));
    }

    private static Object expandReify(Object[] args) {
        List<Object> specs = new ArrayList<>();
        Map<String, List<ISeq>> allJavaGrouped = new LinkedHashMap<>();
        List<Object> protoMapKVs = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            Object spec = args[i++];
            specs.add(spec);
            Map<String, List<ISeq>> specGrouped = new LinkedHashMap<>();
            while (i < args.length && args[i] instanceof ISeq ms) {
                Symbol mname = (Symbol) ms.first();
                addMethodClause(allJavaGrouped, mname.name, ms.next());
                addMethodClause(specGrouped, mname.name, ms.next());
                i++;
            }
            List<Object> protoM = new ArrayList<>();
            for (Map.Entry<String, List<ISeq>> e : specGrouped.entrySet()) {
                protoM.add(Keyword.intern(e.getKey()));
                protoM.add(buildMethodFnForm(e.getValue()));
            }
            protoMapKVs.add(spec);
            protoMapKVs.add(RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoM)));
        }
        List<Object> javaMapKVs = new ArrayList<>();
        for (Map.Entry<String, List<ISeq>> e : allJavaGrouped.entrySet()) {
            javaMapKVs.add(e.getKey());
            javaMapKVs.add(buildMethodFnForm(e.getValue()));
        }
        return PersistentList.createFromArray(
                Symbol.intern("clojure.core", "reify*"),
                PersistentVector.create(specs),
                RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(javaMapKVs)),
                RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoMapKVs)));
    }

    private static String[] toStringArray(Object coll) {
        Object[] arr = RT.toArray(coll);
        String[] ret = new String[arr.length];
        for (int i = 0; i < arr.length; i++) ret[i] = arr[i].toString();
        return ret;
    }

    private static Class<?>[] resolveInterfaces(Object coll) {
        Set<Class<?>> ifaces = new LinkedHashSet<>();
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
            Object item = s.first();
            if (item instanceof Class<?> c && c.isInterface()) ifaces.add(c);
            else if (item instanceof Protocol p && p.onInterface != null) ifaces.add(p.onInterface);
        }
        return ifaces.toArray(new Class<?>[0]);
    }

    private static Map<String, Fn> toMethodMap(Object mapObj) {
        Map<String, Fn> m = new HashMap<>();
        if (mapObj instanceof Map<?, ?> mp) {
            for (Map.Entry<?, ?> e : mp.entrySet()) {
                m.put(e.getKey().toString(), (Fn) e.getValue());
            }
        }
        return m;
    }

    private static PrintWriter makePrintWriter(Fn flushFn, Fn closeFn, boolean autoFlush) {
        Writer w = new Writer() {
            @Override public void write(char[] cbuf, int off, int len) {}
            @Override public void flush() { if (flushFn != null) flushFn.invoke(); }
            @Override public void close() { if (closeFn != null) closeFn.invoke(); }
        };
        return new PrintWriter(w, autoFlush);
    }

    private static Object intoIterator(Object to, Fn xform, Iterator<?> it) {
        if (to == null) to = PersistentList.EMPTY;
        if (xform != null) {
            if (to instanceof PersistentVector pv) {
                PersistentVector.TransientVector tv = pv.asTransient();
                Fn rf = (Fn) xform.invoke(new Fn.NamedFn("conj!") {
                    @Override public Object invoke() { return tv; }
                    @Override public Object invoke(Object acc) { return ((PersistentVector.TransientVector) acc).persistent(); }
                    @Override public Object invoke(Object acc, Object x) { return ((PersistentVector.TransientVector) acc).conj(x); }
                });
                Object acc = tv;
                while (it.hasNext()) {
                    acc = rf.invoke(acc, it.next());
                    if (acc instanceof Reduced r) { acc = r.deref(); break; }
                }
                return rf.invoke(acc);
            }
            Fn rf = (Fn) xform.invoke(new Fn.NamedFn("conj") {
                @Override public Object invoke() { return PersistentVector.EMPTY; }
                @Override public Object invoke(Object acc) { return acc; }
                @Override public Object invoke(Object acc, Object x) { return RT.conj(acc, x); }
            });
            Object acc = to;
            while (it.hasNext()) {
                acc = rf.invoke(acc, it.next());
                if (acc instanceof Reduced r) { acc = r.deref(); break; }
            }
            return rf.invoke(acc);
        }
        if (to instanceof PersistentVector pv) {
            PersistentVector.TransientVector tv = pv.asTransient();
            while (it.hasNext()) tv = tv.conj(it.next());
            return tv.persistent();
        }
        Object acc = to;
        while (it.hasNext()) acc = RT.conj(acc, it.next());
        return acc;
    }

    private static final Map<Character, String> MUNGE_MAP = createMungeMap();

    private static Map<Character, String> createMungeMap() {
        Map<Character, String> m = new HashMap<>();
        m.put('-', "_");
        m.put('.', "_DOT_");
        m.put(':', "_COLON_");
        m.put('+', "_PLUS_");
        m.put('>', "_GT_");
        m.put('<', "_LT_");
        m.put('=', "_EQ_");
        m.put('~', "_TILDE_");
        m.put('!', "_BANG_");
        m.put('@', "_CIRCA_");
        m.put('#', "_SHARP_");
        m.put('\'', "_SINGLEQUOTE_");
        m.put('"', "_DOUBLEQUOTE_");
        m.put('%', "_PERCENT_");
        m.put('^', "_CARET_");
        m.put('&', "_AMP_");
        m.put('*', "_STAR_");
        m.put('|', "_BAR_");
        m.put('{', "_LBRACE_");
        m.put('}', "_RBRACE_");
        m.put('[', "_LBRACK_");
        m.put(']', "_RBRACK_");
        m.put('/', "_SLASH_");
        m.put('\\', "_BSLASH_");
        m.put('?', "_QMARK_");
        return m;
    }

    public static String mungeString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String repl = MUNGE_MAP.get(c);
            if (repl != null) sb.append(repl);
            else sb.append(c);
        }
        return sb.toString();
    }

    private static Object mungeObj(Object x) {
        if (x instanceof Symbol sym) {
            if (sym.ns == null) return Symbol.intern(mungeString(sym.name));
            return Symbol.intern(mungeString(sym.ns), mungeString(sym.name));
        }
        return mungeString(x.toString());
    }

    private static Object namespaceMunge(Object nsObj) {
        String s;
        if (nsObj instanceof Namespace ns) s = ns.name.name;
        else if (nsObj instanceof Symbol sym) s = sym.name;
        else s = nsObj.toString();
        String[] parts = s.split("\\.", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = mungeString(parts[i]);
        }
        return String.join(".", parts);
    }

    private static Map<Protocol, Map<Keyword, Fn>> toProtoMap(Object mapObj) {
        Map<Protocol, Map<Keyword, Fn>> ret = new HashMap<>();
        if (mapObj instanceof Map<?, ?> mp) {
            for (Map.Entry<?, ?> e : mp.entrySet()) {
                Protocol p = null;
                if (e.getKey() instanceof Protocol proto) p = proto;
                else if (e.getKey() instanceof Class<?> c) p = Protocol.findByInterface(c);
                if (p != null && e.getValue() instanceof Map<?, ?> pm) {
                    Map<Keyword, Fn> m = new HashMap<>();
                    for (Map.Entry<?, ?> me : pm.entrySet()) m.put((Keyword) me.getKey(), (Fn) me.getValue());
                    ret.put(p, m);
                }
            }
        }
        return ret;
    }

    // ========================================================================
    // Compact Tableswitch Function Dispatchers (Zero LambdaMetafactory Overhead)
    // ========================================================================

    private static final class ExtraFn0 extends Fn.NamedFn {
        private final int id;
        ExtraFn0(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke() { return call0(id); }
    }

    private static final class ExtraFn1 extends Fn.NamedFn {
        private final int id;
        ExtraFn1(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke(Object a) { return call1(id, a); }
    }

    private static final class ExtraFn2 extends Fn.NamedFn {
        private final int id;
        ExtraFn2(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke(Object a, Object b) { return call2(id, a, b); }
    }

    private static final class ExtraFn3 extends Fn.NamedFn {
        private final int id;
        ExtraFn3(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke(Object a, Object b, Object c) { return call3(id, a, b, c); }
    }

    private static final class ExtraFn12 extends Fn.NamedFn {
        private final int id1, id2;
        ExtraFn12(int id1, int id2, String name) { super(name); this.id1 = id1; this.id2 = id2; }
        @Override public Object invoke(Object a) { return call1(id1, a); }
        @Override public Object invoke(Object a, Object b) { return call2(id2, a, b); }
    }

    private static final class ExtraFn23 extends Fn.NamedFn {
        private final int id2, id3;
        ExtraFn23(int id2, int id3, String name) { super(name); this.id2 = id2; this.id3 = id3; }
        @Override public Object invoke(Object a, Object b) { return call2(id2, a, b); }
        @Override public Object invoke(Object a, Object b, Object c) { return call3(id3, a, b, c); }
    }

    private static final class ExtraFnV extends Fn.NamedFn {
        private final int id;
        ExtraFnV(int id, String name) { super(name); this.id = id; }
        @Override public Object invokeVarargs(Object[] args) { return callV(id, args); }
    }

    @SuppressWarnings("unchecked")
    private static Object call0(int _id) {
        switch (_id) {
            case 0: {
                
            PersistentHashMap m = (PersistentHashMap) RT.CLOJURE_VERSION.deref();
            StringBuilder sb = new StringBuilder();
            sb.append(m.valAt(Keyword.intern("major"))).append('.')
              .append(m.valAt(Keyword.intern("minor"))).append('.')
              .append(m.valAt(Keyword.intern("incremental")));
            Object q = m.valAt(Keyword.intern("qualifier"));
            if (q != null) sb.append('-').append(q);
            return sb.toString();
        
            }
            case 1: return UUID.randomUUID();
            case 2: return CoreExtras.makeHierarchy();
            case 3: return Core.getLoadedLibs();
            case 4: {
                
            Map<Var, Object> m = Var.getThreadBindings();
            return m.isEmpty() ? PersistentHashMap.EMPTY : PersistentHashMap.createFromMap(m);
        
            }
            case 5: return ((long) Agent.releasePendingSends());
            case 6: {
                 Agent.shutdown(); return null; 
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    private static Object call1(int _id, Object _a) {
        return switch (_id >>> 5) {
            case 0 -> call1_0(_id, _a);
            case 1 -> call1_1(_id, _a);
            case 2 -> call1_2(_id, _a);
            case 3 -> call1_3(_id, _a);
            default -> throw new IllegalStateException("Invalid dispatcher id: " + _id);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object call1_0(int _id, Object _a) {
        switch (_id) {
            case 0: { Object x = _a; return (Boolean.TRUE); }
            case 1: { Object x = _a; return (x instanceof byte[]); }
            case 2: { Object x = _a; return (x instanceof Keyword || x instanceof Symbol); }
            case 3: { Object x = _a; return ((x instanceof Keyword k && k.getNamespace() == null)
                        || (x instanceof Symbol s && s.ns == null)); }
            case 4: { Object x = _a; return ((x instanceof Keyword k && k.getNamespace() != null)
                        || (x instanceof Symbol s && s.ns != null)); }
            case 5: { Object x = _a; return (x instanceof PersistentVector || x instanceof RandomAccess); }
            case 6: {
                Object x = _a;
            double d = ((Number) x).doubleValue();
            return Double.isInfinite(d);
        
            }
            case 7: { Object x = _a; return (Double.isNaN(((Number) x).doubleValue())); }
            case 8: { Object x = _a; return (x instanceof Date || x instanceof java.time.Instant); }
            case 9: { Object x = _a; return (x instanceof UUID); }
            case 10: { Object x = _a; return (x instanceof URI); }
            case 11: { Object x = _a; return (x instanceof Map.Entry<?, ?>); }
            case 12: { Object x = _a; return (x instanceof PersistentRecord); }
            case 13: { Object x = _a; return (x instanceof Reversible); }
            case 14: { Object x = _a; return (x instanceof Sorted); }
            case 15: {
                Object c = _a;
            if (c == null) return null;
            Class<?> cls = (Class<?>) c;
            List<Class<?>> ret = new ArrayList<>();
            if (cls.getSuperclass() != null) ret.add(cls.getSuperclass());
            Collections.addAll(ret, cls.getInterfaces());
            return ret.isEmpty() ? null : PersistentList.create(ret);
        
            }
            case 16: {
                Object c = _a;
            if (c == null) return null;
            Class<?> cls = (Class<?>) c;
            Set<Class<?>> all = new LinkedHashSet<>();
            Deque<Class<?>> q = new ArrayDeque<>();
            if (cls.getSuperclass() != null) q.add(cls.getSuperclass());
            Collections.addAll(q, cls.getInterfaces());
            while (!q.isEmpty()) {
                Class<?> cur = q.poll();
                if (all.add(cur)) {
                    if (cur.getSuperclass() != null) q.add(cur.getSuperclass());
                    Collections.addAll(q, cur.getInterfaces());
                }
            }
            return all.isEmpty() ? null : PersistentHashSet.create(all);
        
            }
            case 17: {
                Object x = _a;
            if (x == null) return PersistentHashMap.EMPTY;
            try {
                BeanInfo info = Introspector.getBeanInfo(x.getClass());
                PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
                for (PropertyDescriptor pd : info.getPropertyDescriptors()) {
                    if (pd.getReadMethod() != null) {
                        Object val = pd.getReadMethod().invoke(x);
                        tm = tm.assoc(Keyword.intern(pd.getName()), val);
                    }
                }
                return tm.persistent();
            } catch (Exception e) {
                throw RT.sneakyThrow(e);
            }
        
            }
            case 18: {
                Object o = _a;
            StackTraceElement ste = (StackTraceElement) o;
            return PersistentVector.createOwning(
                    Symbol.intern(ste.getClassName()),
                    Symbol.intern(ste.getMethodName()),
                    ste.getFileName(),
                    (long) ste.getLineNumber());
        
            }
            case 19: { Object o = _a; return (throwableToMap((Throwable) o)); }
            case 20: return Numbers.byteCast(_a);
            case 21: return Numbers.shortCast(_a);
            case 22: return Numbers.uncheckedByte(_a);
            case 23: return Numbers.uncheckedShort(_a);
            case 24: return Numbers.uncheckedInt(_a);
            case 25: return Numbers.uncheckedLong(_a);
            case 26: return Numbers.uncheckedChar(_a);
            case 27: return Numbers.uncheckedFloat(_a);
            case 28: return Numbers.uncheckedDouble(_a);
            case 29: return Numbers.uncheckedIncInt(_a);
            case 30: return Numbers.uncheckedDecInt(_a);
            case 31: return Numbers.uncheckedNegateInt(_a);
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_1(int _id, Object _a) {
        switch (_id) {
            case 32: { Object r = _a; return (((Ratio) r).numerator); }
            case 33: { Object r = _a; return (((Ratio) r).denominator); }
            case 34: { Object coll = _a; return ((long) RT.hashOrderedColl((Iterable<?>) coll)); }
            case 35: { Object coll = _a; return ((long) RT.hashUnorderedColl((Iterable<?>) coll)); }
            case 36: return CoreExtras.mungeObj(_a);
            case 37: return CoreExtras.namespaceMunge(_a);
            case 38: {
                Object inst = _a;
            if (inst instanceof Date d) return d.getTime();
            if (inst instanceof java.time.Instant ins) return ins.toEpochMilli();
            Fn impl = instProto.resolveMethod(Keyword.intern("inst-ms*"), inst);
            return impl.invoke(inst);
        
            }
            case 39: { Object d = _a; return (((Date) d).getTime()); }
            case 40: { Object i = _a; return (((java.time.Instant) i).toEpochMilli()); }
            case 41: {
                Object inst = _a;
            if (inst instanceof Date d) return d.getTime();
            if (inst instanceof java.time.Instant ins) return ins.toEpochMilli();
            Fn impl = instProto.resolveMethod(Keyword.intern("inst-ms*"), inst);
            return impl.invoke(inst);
        
            }
            case 42: { Object x = _a; return ((Number) x); }
            case 43: {
                Object x = _a;
            if (x instanceof BigInteger bi) return bi;
            if (x instanceof BigDecimal bd) return bd.toBigInteger();
            if (x instanceof Ratio r) return r.numerator.divide(r.denominator);
            if (x instanceof String s) return new BigInteger(s);
            return BigInteger.valueOf(Numbers.toLong(x));
        
            }
            case 44: {
                Object x = _a;
            if (x instanceof BigDecimal bd) {
                int scale = bd.scale();
                if (scale <= 0) return bd.toBigInteger();
                return Ratio.create(bd.unscaledValue(), BigInteger.TEN.pow(scale));
            }
            if (x instanceof Double || x instanceof Float) {
                BigDecimal bd = BigDecimal.valueOf(((Number) x).doubleValue());
                int scale = bd.scale();
                if (scale <= 0) return bd.toBigInteger();
                return Ratio.create(bd.unscaledValue(), BigInteger.TEN.pow(scale));
            }
            return x;
        
            }
            case 45: { Object x = _a; return (x instanceof TaggedLiteral); }
            case 46: { Object x = _a; return (x instanceof ReaderConditional); }
            case 47: {
                Object s = _a;
            String str = (String) s;
            if ("true".equals(str)) return Boolean.TRUE;
            if ("false".equals(str)) return Boolean.FALSE;
            return null;
        
            }
            case 48: {
                Object s = _a;
            try { return Long.parseLong((String) s); } catch (NumberFormatException e) { return null; }
        
            }
            case 49: {
                Object s = _a;
            try { return Double.parseDouble((String) s); } catch (NumberFormatException e) { return null; }
        
            }
            case 50: {
                Object s = _a;
            try { return UUID.fromString((String) s); } catch (IllegalArgumentException e) { return null; }
        
            }
            case 51: {
                Object a = _a;
            if (a instanceof Double || a instanceof Float) return ((Number) a).doubleValue() + 1.0;
            return ((Number) a).longValue() + 1L;
        
            }
            case 52: {
                Object a = _a;
            if (a instanceof Double || a instanceof Float) return ((Number) a).doubleValue() - 1.0;
            return ((Number) a).longValue() - 1L;
        
            }
            case 53: {
                Object a = _a;
            if (a instanceof Double || a instanceof Float) return -((Number) a).doubleValue();
            return -((Number) a).longValue();
        
            }
            case 54: { Object x = _a; return ((boolean[]) x); }
            case 55: { Object x = _a; return ((byte[]) x); }
            case 56: { Object x = _a; return ((char[]) x); }
            case 57: { Object x = _a; return ((double[]) x); }
            case 58: { Object x = _a; return ((float[]) x); }
            case 59: { Object x = _a; return ((int[]) x); }
            case 60: { Object x = _a; return ((long[]) x); }
            case 61: { Object x = _a; return ((short[]) x); }
            case 62: {
                Object sOrLen = _a;
                    if (sOrLen instanceof Number n) return new float[n.intValue()];
                    Object[] arr = RT.toArray(sOrLen);
                    float[] ret = new float[arr.length];
                    for (int i = 0; i < arr.length; i++) ret[i] = ((Number) arr[i]).floatValue();
                    return ret;
                
            }
            case 63: {
                Object sOrLen = _a;
                    if (sOrLen instanceof Number n) return new short[n.intValue()];
                    Object[] arr = RT.toArray(sOrLen);
                    short[] ret = new short[arr.length];
                    for (int i = 0; i < arr.length; i++) ret[i] = ((Number) arr[i]).shortValue();
                    return ret;
                
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_2(int _id, Object _a) {
        switch (_id) {
            case 64: {
                Object aseq = _a;
                    ISeq s = RT.seq(aseq);
                    Class<?> type = (s != null && s.first() != null) ? s.first().getClass() : Object.class;
                    Object[] vals = RT.toArray(s);
                    Object arr = Array.newInstance(type, vals.length);
                    for (int i = 0; i < vals.length; i++) Array.set(arr, i, vals[i]);
                    return arr;
                
            }
            case 65: {
                Object coll = _a;
            Object[] outer = RT.toArray(coll);
            Object[][] ret = new Object[outer.length][];
            for (int i = 0; i < outer.length; i++) ret[i] = RT.toArray(outer[i]);
            return ret;
        
            }
            case 66: {
                Object pred = _a;
            Fn p = (Fn) pred;
            return new Fn.NamedFn("comparator") {
                @Override public int compare(Object a, Object b) {
                    if (RT.truthy(p.invoke(a, b))) return -1;
                    if (RT.truthy(p.invoke(b, a))) return 1;
                    return 0;
                }
                @Override public Object invoke(Object a, Object b) { return (long) compare(a, b); }
            };
        
            }
            case 67: { Object cap = _a; return (new ChunkBuffer((int) Numbers.toLong(cap))); }
            case 68: { Object b = _a; return (((ChunkBuffer) b).chunk()); }
            case 69: { Object s = _a; return (((IChunkedSeq) s).chunkedFirst()); }
            case 70: { Object s = _a; return (((IChunkedSeq) s).chunkedNext()); }
            case 71: { Object s = _a; return (((IChunkedSeq) s).chunkedMore()); }
            case 72: { Object s = _a; return (s instanceof IChunkedSeq); }
            case 73: { Object coll = _a; return (RT.seq(coll) != null ? coll : null); }
            case 74: {
                Object rdr = _a;
            BufferedReader br = (rdr instanceof BufferedReader b) ? b : new BufferedReader((java.io.Reader) rdr);
            return lineSeqStep(br);
        
            }
            case 75: {
                Object dir = _a;
            Var treeSeq = RT.CLOJURE_NS.findInternedVar(Symbol.intern("tree-seq"));
            return ((Fn) treeSeq.deref()).invoke(
                    Fn.of1("is-dir?", f -> ((File) f).isDirectory()),
                    Fn.of1("children", f -> {
                        File[] files = ((File) f).listFiles();
                        return files == null ? null : PersistentList.createFromArray((Object[]) files);
                    }),
                    dir);
        
            }
            case 76: {
                Object e = _a;
            Enumeration<?> en = (Enumeration<?>) e;
            return RT.iteratorSeq(new Iterator<>() {
                @Override public boolean hasNext() { return en.hasMoreElements(); }
                @Override public Object next() { return en.nextElement(); }
            });
        
            }
            case 77: { Object iter = _a; return (RT.iteratorSeq((Iterator<?>) iter)); }
            case 78: {
                Object root = _a;
            Var treeSeq = RT.CLOJURE_NS.findInternedVar(Symbol.intern("tree-seq"));
            Keyword contentKw = Keyword.intern("content");
            return ((Fn) treeSeq.deref()).invoke(
                    Fn.of1("branch?", x -> !(x instanceof String)),
                    Fn.of1("children", x -> RT.seq(RT.get(x, contentKw))),
                    root);
        
            }
            case 79: {
                Object rsObj = _a;
            try {
                ResultSet rs = (ResultSet) rsObj;
                ResultSetMetaData md = rs.getMetaData();
                int cols = md.getColumnCount();
                Keyword[] keys = new Keyword[cols];
                for (int i = 0; i < cols; i++) {
                    keys[i] = Keyword.intern(md.getColumnLabel(i + 1).toLowerCase(Locale.ROOT));
                }
                List<Object> rows = new ArrayList<>();
                while (rs.next()) {
                    PersistentHashMap.TransientHashMap row = PersistentHashMap.EMPTY.asTransient();
                    for (int i = 0; i < cols; i++) row = row.assoc(keys[i], rs.getObject(i + 1));
                    rows.add(row.persistent());
                }
                return rows.isEmpty() ? null : PersistentList.create(rows);
            } catch (Exception e) { throw RT.sneakyThrow(e); }
        
            }
            case 80: { Object s = _a; return (sequeImpl(100, s)); }
            case 81: {
                Object v = _a;
            Var vr = (Var) v;
            Object tf = vr.meta().valAt(Keyword.intern("test"));
            if (tf instanceof Fn f) {
                f.invoke();
                return Keyword.intern("ok");
            }
            return Symbol.intern("no-test");
        
            }
            case 82: { Object f = _a; return (completing((Fn) f, Fn.of1("identity", x -> x))); }
            case 83: {
                Object rfObj = _a;
            Fn rf = (Fn) rfObj;
            Fn preserveReducedRf = new Fn.NamedFn("preserving-reduced") {
                @Override public Object invoke() { return rf.invoke(); }
                @Override public Object invoke(Object acc) { return rf.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    Object ret = rf.invoke(acc, x);
                    return (ret instanceof Reduced) ? new Reduced(ret) : ret;
                }
            };
            return new Fn.NamedFn("cat") {
                @Override public Object invoke() { return rf.invoke(); }
                @Override public Object invoke(Object acc) { return rf.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    Object res = acc;
                    for (ISeq s = RT.seq(x); s != null; s = s.next()) {
                        res = preserveReducedRf.invoke(res, s.first());
                        if (res instanceof Reduced r) return r.deref();
                    }
                    return res;
                }
            };
        
            }
            case 84: { Object pred = _a; return (haltWhen((Fn) pred, null)); }
            case 85: { Object s = _a; return (s == null ? null : RT.iteratorSeq(((java.util.stream.BaseStream<?, ?>) s).iterator())); }
            case 86: { Object smap = _a; return (Fn.of1("replace-xf", rf -> {
                    Fn r = (Fn) rf;
                    return new Fn.NamedFn("replace-step") {
                        @Override public Object invoke() { return r.invoke(); }
                        @Override public Object invoke(Object acc) { return r.invoke(acc); }
                        @Override public Object invoke(Object acc, Object x) {
                            Object v = RT.get(smap, x, REDUCED_NONE);
                            return r.invoke(acc, v == REDUCED_NONE ? x : v);
                        }
                    };
                })); }
            case 87: {
                Object prob = _a;
                    double p = ((Number) prob).doubleValue();
                    return Fn.of1("random-sample-xf", rf -> {
                        Fn r = (Fn) rf;
                        return new Fn.NamedFn("random-sample-step") {
                            @Override public Object invoke() { return r.invoke(); }
                            @Override public Object invoke(Object acc) { return r.invoke(acc); }
                            @Override public Object invoke(Object acc, Object x) {
                                return ThreadLocalRandom.current().nextDouble() < p ? r.invoke(acc, x) : acc;
                            }
                        };
                    });
                
            }
            case 88: { Object tag = _a; return (parentsOf(getGlobalHierarchy().deref(), tag)); }
            case 89: { Object tag = _a; return (ancestorsOf(getGlobalHierarchy().deref(), tag)); }
            case 90: { Object tag = _a; return (RT.get(RT.get(getGlobalHierarchy().deref(), KW_DESCENDANTS), tag)); }
            case 91: { Object mf = _a; return (((MultiFn) mf).prefers()); }
            case 92: { Object mf = _a; return (((MultiFn) mf).removeAllMethods()); }
            case 93: {
                Object x = _a;
            Namespace n = resolveNsArg(x);
            if (n == null) throw new RuntimeException("No namespace: " + x + " found");
            return n;
        
            }
            case 94: {
                Object x = _a;
            Namespace n = resolveNsArg(x);
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns == n) tm = tm.assoc(e.getKey(), v);
            }
            return tm.persistent();
        
            }
            case 95: {
                Object x = _a;
            Namespace n = resolveNsArg(x);
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns != n) tm = tm.assoc(e.getKey(), v);
            }
            return tm.persistent();
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_3(int _id, Object _a) {
        switch (_id) {
            case 96: {
                Object x = _a;
            Namespace n = resolveNsArg(x);
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                if (e.getValue() instanceof Class<?>) tm = tm.assoc(e.getKey(), e.getValue());
            }
            return tm.persistent();
        
            }
            case 97: {
                Object sym = _a;
            Symbol s = (Symbol) sym;
            if (s.ns != null && Namespace.find(s.ns) == null) {
                Core.loadLib(s.ns);
            }
            Var v = Compiler.lookupVar(RT.currentNS(), s, false);
            return v;
        
            }
            case 98: { Object rdr = _a; return (Compiler.load((java.io.Reader) rdr, "<reader>")); }
            case 99: { Object lib = _a; return (GenClass.compileNamespace((Symbol) lib)); }
            case 100: {
                Object fObj = _a;
            Fn f = (Fn) fObj;
            Map<Var, Object> captured = Var.getThreadBindings();
            return Fn.ofVarargs("bound-fn", args -> {
                Var.pushThreadBindings(captured);
                try {
                    return f.applyTo(args);
                } finally {
                    Var.popThreadBindings();
                }
            });
        
            }
            case 101: {
                Object f = _a; getTapSet().add((Fn) f); return null; 
            }
            case 102: {
                Object f = _a;
            CopyOnWriteArraySet<Fn> s = tapSet;
            if (s != null) s.remove((Fn) f);
            return null;
        
            }
            case 103: {
                Object x = _a;
            CopyOnWriteArraySet<Fn> s = tapSet;
            if (s == null || s.isEmpty()) return Boolean.FALSE;
            Atom.markMultiThreaded();
            TapExecutorHolder.EXECUTOR.execute(() -> {
                for (Fn f : s) {
                    try { f.invoke(x); } catch (Throwable ignored) {}
                }
            });
            return Boolean.TRUE;
        
            }
            case 104: { Object a = _a; return (((Agent) a).getError()); }
            case 105: {
                Object a = _a;
            Object err = ((Agent) a).getError();
            return err == null ? null : PersistentList.createFromArray(err);
        
            }
            case 106: {
                Object a = _a;
            Agent ag = (Agent) a;
            if (ag.getError() != null) ag.restart(ag.getRawState(), false);
            return null;
        
            }
            case 107: {
                Object a = _a;
            Agent ag = (Agent) a;
            try { ag.await(-1); } catch (InterruptedException e) { throw RT.sneakyThrow(e); }
            return ag;
        
            }
            case 108: { Object a = _a; return (((Agent) a).getErrorHandler()); }
            case 109: { Object a = _a; return (((Agent) a).getErrorMode()); }
            case 110: {
                Object e = _a; Agent.pooledExecutor = (ExecutorService) e; return e; 
            }
            case 111: {
                Object e = _a; Agent.soloExecutor = (ExecutorService) e; return e; 
            }
            case 112: {
                Object fn = _a;
            try { return LockingTransaction.runInTransaction(() -> ((Fn) fn).invoke()); }
            catch (Exception e) { throw RT.sneakyThrow(e); }
        
            }
            case 113: {
                Object r = _a; ((Ref) r).touch(); return ((Ref) r).deref(); 
            }
            case 114: { Object r = _a; return ((long) ((Ref) r).getHistoryCount()); }
            case 115: { Object r = _a; return ((long) ((Ref) r).getMinHistory()); }
            case 116: { Object r = _a; return ((long) ((Ref) r).getMaxHistory()); }
            case 117: {
                Object p = _a;
            Collection<Class<?>> ex = ((Protocol) p).extenders();
            return ex.isEmpty() ? null : PersistentList.create(new ArrayList<>(ex));
        
            }
            case 118: {
                Object p = _a; ((Protocol) p).resetCaches(); return p; 
            }
            case 119: return TypeFactory.proxyMappings(_a);
            case 120: { Object m = _a; return (TypeFactory.methodSig((java.lang.reflect.Method) m)); }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    private static Object call2(int _id, Object _a, Object _b) {
        return switch (_id >>> 5) {
            case 0 -> call2_0(_id, _a, _b);
            case 1 -> call2_1(_id, _a, _b);
            default -> throw new IllegalStateException("Invalid dispatcher id: " + _id);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object call2_0(int _id, Object _a, Object _b) {
        switch (_id) {
            case 0: { Object c = _a; Object x = _b; return (((Class<?>) c).cast(x)); }
            case 1: return Numbers.uncheckedAddInt(_a, _b);
            case 2: return Numbers.uncheckedSubtractInt(_a, _b);
            case 3: return Numbers.uncheckedMultiplyInt(_a, _b);
            case 4: return Numbers.uncheckedDivideInt(_a, _b);
            case 5: return Numbers.uncheckedRemainderInt(_a, _b);
            case 6: { Object x = _a; Object y = _b; return ((long) RT.hashCombine((int) Numbers.toLong(x), (int) Numbers.toLong(y))); }
            case 7: { Object b = _a; Object c = _b; return ((long) RT.mixCollectionHash((int) Numbers.toLong(b), (int) Numbers.toLong(c))); }
            case 8: { Object tag = _a; Object form = _b; return (TaggedLiteral.create((Symbol) tag, form)); }
            case 9: { Object form = _a; Object splicing = _b; return (ReaderConditional.create(form, RT.truthy(splicing))); }
            case 10: {
                Object a = _a; Object b = _b;
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return ((Number) a).doubleValue() + ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() + ((Number) b).longValue();
        
            }
            case 11: {
                Object a = _a; Object b = _b;
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return ((Number) a).doubleValue() - ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() - ((Number) b).longValue();
        
            }
            case 12: {
                Object a = _a; Object b = _b;
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return ((Number) a).doubleValue() * ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() * ((Number) b).longValue();
        
            }
            case 13: {
                Object size = _a; Object init = _b;
                    int n = (int) Numbers.toLong(size);
                    float[] ret = new float[n];
                    if (init instanceof Number num) Arrays.fill(ret, num.floatValue());
                    else {
                        int i = 0;
                        for (ISeq s = RT.seq(init); s != null && i < n; s = s.next(), i++) {
                            ret[i] = ((Number) s.first()).floatValue();
                        }
                    }
                    return ret;
                
            }
            case 14: {
                Object size = _a; Object init = _b;
                    int n = (int) Numbers.toLong(size);
                    short[] ret = new short[n];
                    if (init instanceof Number num) Arrays.fill(ret, num.shortValue());
                    else {
                        int i = 0;
                        for (ISeq s = RT.seq(init); s != null && i < n; s = s.next(), i++) {
                            ret[i] = ((Number) s.first()).shortValue();
                        }
                    }
                    return ret;
                
            }
            case 15: {
                Object type = _a; Object aseq = _b;
                    Object[] vals = RT.toArray(aseq);
                    Class<?> c = (Class<?>) type;
                    Object arr = Array.newInstance(c, vals.length);
                    for (int i = 0; i < vals.length; i++) Array.set(arr, i, vals[i]);
                    return arr;
                
            }
            case 16: { Object s = _a; Object k = _b; return (PersistentStructMap.accessor((PersistentStructMap.Def) s, k)); }
            case 17: {
                Object b = _a; Object x = _b; ((ChunkBuffer) b).add(x); return null; 
            }
            case 18: {
                Object c = _a; Object rest = _b;
            IChunk ch = (IChunk) c;
            if (ch.count() == 0) return rest;
            return new ChunkedCons(ch, RT.seq(rest));
        
            }
            case 19: { Object edit = _a; Object arr = _b; return (new Vec.VecNode((java.util.concurrent.atomic.AtomicReference<Thread>) edit, (Object[]) arr)); }
            case 20: { Object xf = _a; Object coll = _b; return (new Eduction((Fn) xf, coll)); }
            case 21: {
                Object n = _a; Object coll = _b;
            if (coll instanceof Counted c) return (long) c.count();
            long limit = Numbers.toLong(n);
            long i = 0;
            for (ISeq s = RT.seq(coll); s != null && i < limit; s = s.next()) i++;
            return i;
        
            }
            case 22: { Object n = _a; Object s = _b; return (sequeImpl((int) Numbers.toLong(n), s)); }
            case 23: {
                Object m = _a; Object f = _b;
            if (m == null) return PersistentHashMap.EMPTY;
            Fn fn = (Fn) f;
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                tm = tm.assoc(fn.invoke(e.getKey()), e.getValue());
            }
            return tm.persistent();
        
            }
            case 24: {
                Object m = _a; Object f = _b;
            if (m == null) return PersistentHashMap.EMPTY;
            Fn fn = (Fn) f;
            Associative ret = (m instanceof PersistentTreeMap ptm) ? ptm.empty() : PersistentHashMap.EMPTY;
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                ret = ret.assoc(e.getKey(), fn.invoke(e.getValue()));
            }
            return ret;
        
            }
            case 25: {
                Object msg = _a; Object fn = _b;
            if (LockingTransaction.isRunning()) throw new IllegalStateException((String) msg);
            return ((Fn) fn).invoke();
        
            }
            case 26: { Object f = _a; Object cf = _b; return (completing((Fn) f, (Fn) cf)); }
            case 27: { Object pred = _a; Object retf = _b; return (haltWhen((Fn) pred, (Fn) retf)); }
            case 28: {
                Object f = _a; Object s = _b;
                    Fn fn = (Fn) f;
                    Iterator<?> it = ((java.util.stream.BaseStream<?, ?>) s).iterator();
                    if (!it.hasNext()) return fn.invoke();
                    Object acc = it.next();
                    while (it.hasNext()) {
                        acc = fn.invoke(acc, it.next());
                        if (acc instanceof Reduced r) return r.deref();
                    }
                    return acc;
                
            }
            case 29: {
                Object to = _a; Object s = _b;
                    Iterator<?> it = ((java.util.stream.BaseStream<?, ?>) s).iterator();
                    return intoIterator(to, null, it);
                
            }
            case 30: {
                Object smap = _a; Object coll = _b;
                    List<Object> out = new ArrayList<>();
                    for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                        Object x = s.first();
                        Object v = RT.get(smap, x, REDUCED_NONE);
                        out.add(v == REDUCED_NONE ? x : v);
                    }
                    return coll instanceof PersistentVector
                            ? PersistentVector.create(out)
                            : PersistentList.create(out);
                
            }
            case 31: {
                Object prob = _a; Object coll = _b;
                    double p = ((Number) prob).doubleValue();
                    Fn pred = Fn.of1("rand-pred", ignored -> ThreadLocalRandom.current().nextDouble() < p);
                    return ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("filter")).deref()).invoke(pred, coll);
                
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call2_1(int _id, Object _a, Object _b) {
        switch (_id) {
            case 32: { Object tag = _a; Object parent = _b; return (getGlobalHierarchy().alterRoot(
                        Fn.of1("derive-global", h -> deriveHierarchy((PersistentHashMap) h, tag, parent)),
                        Fn.EMPTY_ARGS)); }
            case 33: { Object tag = _a; Object parent = _b; return (getGlobalHierarchy().alterRoot(
                        Fn.of1("underive-global", h -> underiveHierarchy((PersistentHashMap) h, tag, parent)),
                        Fn.EMPTY_ARGS)); }
            case 34: { Object h = _a; Object tag = _b; return (parentsOf(h, tag)); }
            case 35: { Object h = _a; Object tag = _b; return (ancestorsOf(h, tag)); }
            case 36: { Object h = _a; Object tag = _b; return (RT.get(RT.get(h, KW_DESCENDANTS), tag)); }
            case 37: {
                Object nsArg = _a; Object sym = _b;
            Namespace n = resolveNsArg(nsArg);
            Symbol s = (Symbol) sym;
            Var v = Compiler.lookupVar(n, s, false);
            if (v != null) return v;
            return Interop.resolveClass(n, s.toString());
        
            }
            case 38: {
                Object nsArg = _a; Object alias = _b;
            resolveNsArg(nsArg).removeAlias((Symbol) alias);
            return null;
        
            }
            case 39: {
                Object nsArg = _a; Object sym = _b;
            resolveNsArg(nsArg).unmap((Symbol) sym);
            return null;
        
            }
            case 40: {
                Object nsArg = _a; Object sym = _b;
                    Namespace target = resolveNsArg(nsArg);
                    return target.intern((Symbol) sym);
                
            }
            case 41: { Object ref = _a; Object m = _b; return (((IReference) ref).resetMeta((PersistentHashMap) m)); }
            case 42: {
                Object bindingMap = _a; Object func = _b;
            Map<Var, Object> saved = new LinkedHashMap<>();
            PersistentHashMap bm = (PersistentHashMap) bindingMap;
            for (ISeq s = bm.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                Var v = (Var) e.getKey();
                saved.put(v, v.isBound() ? v.getRawRoot() : Var.UNBOUND);
                v.bindRoot(e.getValue());
            }
            try {
                return ((Fn) func).invoke();
            } finally {
                for (Map.Entry<Var, Object> e : saved.entrySet()) {
                    e.getKey().bindRoot(e.getValue());
                }
            }
        
            }
            case 43: {
                Object a = _a; Object h = _b; ((Agent) a).setErrorHandler((Fn) h); return null; 
            }
            case 44: {
                Object a = _a; Object m = _b; ((Agent) a).setErrorMode((Keyword) m); return null; 
            }
            case 45: { Object r = _a; Object v = _b; return (((Ref) r).set(v)); }
            case 46: { Object r = _a; Object n = _b; return (((Ref) r).setMinHistory((int) Numbers.toLong(n))); }
            case 47: { Object r = _a; Object n = _b; return (((Ref) r).setMaxHistory((int) Numbers.toLong(n))); }
            case 48: { Object p = _a; Object x = _b; return (((Protocol) p).satisfies(x)); }
            case 49: { Object p = _a; Object c = _b; return (((Protocol) p).extendsType((Class<?>) c)); }
            case 50: { Object p = _a; Object x = _b; return (((Protocol) p).findImplFor(x)); }
            case 51: {
                Symbol nameSym = (Symbol) _a;
                Var existing = RT.currentNS().findInternedVar(nameSym);
                if (existing != null && existing.getRawRoot() instanceof Protocol oldP) {
                    oldP.resetCaches();
                }
                return new Protocol(nameSym, (String) _b, null);
            }
            case 52: {
                Object nameSym = _a; Object sigs = _b;
            Namespace cur = RT.currentNS();
            String binaryName = cur.name.name + "." + ((Symbol) nameSym).name;
            List<TypeFactory.MethodSig> ms = new ArrayList<>();
            for (ISeq s = RT.seq(sigs); s != null; s = s.next()) {
                ISeq sig = (ISeq) s.first();
                String mname = sig.first().toString();
                PersistentVector pv = (PersistentVector) RT.second(sig);
                Class<?>[] ptypes = new Class<?>[pv.count()];
                Arrays.fill(ptypes, Object.class);
                ms.add(new TypeFactory.MethodSig(mname, Object.class, ptypes));
            }
            Class<?> cls = TypeFactory.defineInterface(binaryName, ms);
            cur.importClass((Symbol) nameSym, cls);
            return cls;
        
            }
            case 53: { Object p = _a; Object m = _b; return (TypeFactory.initProxy(p, (PersistentHashMap) m)); }
            case 54: { Object p = _a; Object m = _b; return (TypeFactory.updateProxy(p, (PersistentHashMap) m)); }
            case 55: {
                Object sup = _a; Object ifaces = _b;
            List<Class<?>> ifs = new ArrayList<>();
            for (ISeq s = RT.seq(ifaces); s != null; s = s.next()) ifs.add((Class<?>) s.first());
            return TypeFactory.proxyName((Class<?>) sup, ifs.toArray(new Class<?>[0]));
        
            }
            case 56: { Object o = _a; Object w = _b; return (o == null ? null : o.getClass()); }
            case 57: {
                Object o = _a; Object w = _b; RT.printSimple(o, (Writer) w); return null; 
            }
            case 58: { Object o = _a; Object w = _b; return (o == null ? null : o.getClass()); }
            case 59: {
                Object o = _a; Object w = _b; RT.printSimple(o, (Writer) w); return null; 
            }
            case 60: {
                Object o = _a; Object w = _b; RT.printSimple(o, (Writer) w); return null; 
            }
            case 61: { Object flushFn = _a; Object closeFn = _b; return (makePrintWriter((Fn) flushFn, (Fn) closeFn, false)); }
            case 62: {
                Object x = _a; Object w = _b;
                if (RT.truthy(RT.PRINT_DUP.deref())) {
                    RT.PRINT_DUP_FN.invoke(x, w);
                } else {
                    RT.PRINT_METHOD_FN.invoke(x, w);
                }
                return null;
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call3(int _id, Object _a, Object _b, Object _c) {
        switch (_id) {
            case 0: {
                Object prec = _a; Object rounding = _b; Object bodyFn = _c;
            int p = (int) Numbers.toLong(prec);
            RoundingMode rm = rounding != null ? RoundingMode.valueOf(rounding.toString()) : RoundingMode.HALF_UP;
            MathContext mc = new MathContext(p, rm);
            MathContext prev = MATH_CONTEXT.get();
            MATH_CONTEXT.set(mc);
            Var.pushThreadBindings(Map.of(RT.MATH_CONTEXT, mc));
            try {
                return ((Fn) bodyFn).invoke();
            } finally {
                Var.popThreadBindings();
                if (prev == null) MATH_CONTEXT.remove();
                else MATH_CONTEXT.set(prev);
            }
        
            }
            case 1: {
                Object arr = _a; Object idx = _b; Object v = _c;
            boolean b = RT.truthy(v); ((boolean[]) arr)[(int) Numbers.toLong(idx)] = b; return b;
        
            }
            case 2: {
                Object arr = _a; Object idx = _b; Object v = _c;
            byte b = ((Number) v).byteValue(); ((byte[]) arr)[(int) Numbers.toLong(idx)] = b; return b;
        
            }
            case 3: {
                Object arr = _a; Object idx = _b; Object v = _c;
            char c = (char) (v instanceof Character ch ? ch : ((Number) v).intValue());
            ((char[]) arr)[(int) Numbers.toLong(idx)] = c; return c;
        
            }
            case 4: {
                Object arr = _a; Object idx = _b; Object v = _c;
            short s = ((Number) v).shortValue(); ((short[]) arr)[(int) Numbers.toLong(idx)] = s; return s;
        
            }
            case 5: {
                Object arr = _a; Object idx = _b; Object v = _c;
            int n = ((Number) v).intValue(); ((int[]) arr)[(int) Numbers.toLong(idx)] = n; return n;
        
            }
            case 6: {
                Object arr = _a; Object idx = _b; Object v = _c;
            long l = Numbers.toLong(v); ((long[]) arr)[(int) Numbers.toLong(idx)] = l; return l;
        
            }
            case 7: {
                Object arr = _a; Object idx = _b; Object v = _c;
            float f = ((Number) v).floatValue(); ((float[]) arr)[(int) Numbers.toLong(idx)] = f; return f;
        
            }
            case 8: {
                Object arr = _a; Object idx = _b; Object v = _c;
            double d = ((Number) v).doubleValue(); ((double[]) arr)[(int) Numbers.toLong(idx)] = d; return d;
        
            }
            case 9: {
                Object f = _a; Object init = _b; Object s = _c;
                    Fn fn = (Fn) f;
                    Iterator<?> it = ((java.util.stream.BaseStream<?, ?>) s).iterator();
                    Object acc = init;
                    while (it.hasNext()) {
                        acc = fn.invoke(acc, it.next());
                        if (acc instanceof Reduced r) return r.deref();
                    }
                    return acc;
                
            }
            case 10: {
                Object to = _a; Object xform = _b; Object s = _c;
                    Iterator<?> it = ((java.util.stream.BaseStream<?, ?>) s).iterator();
                    return intoIterator(to, (Fn) xform, it);
                
            }
            case 11: { Object h = _a; Object tag = _b; Object parent = _c; return (deriveHierarchy((PersistentHashMap) h, tag, parent)); }
            case 12: { Object h = _a; Object tag = _b; Object parent = _c; return (underiveHierarchy((PersistentHashMap) h, tag, parent)); }
            case 13: { Object mf = _a; Object x = _b; Object y = _c; return (((MultiFn) mf).preferMethod(x, y)); }
            case 14: {
                Object nsArg = _a; Object sym = _b; Object val = _c;
                    Namespace target = resolveNsArg(nsArg);
                    return target.intern((Symbol) sym).bindRoot(val);
                
            }
            case 15: { Object p = _a; Object mk = _b; Object x = _c; return (((Protocol) p).resolveMethodOrNull((Keyword) mk, x)); }
            case 16: {
                Object protoVar = _a; Object methodKw = _b; Object argsArr = _c;
            Protocol p = (Protocol) ((Var) protoVar).deref();
            Object[] callArgs = (Object[]) argsArr;
            Fn impl = p.resolveMethod((Keyword) methodKw, callArgs[0]);
            return impl.applyTo(callArgs);
        
            }
            case 17: { Object call = _a; Object proxy = _b; Object meth = _c; return (TypeFactory.proxyCallWithSuper((Fn) call, proxy, meth.toString())); }
            case 18: {
                Object o = _a; Object printArgs = _b; Object w = _c;
            try {
                Writer wr = (Writer) w;
                wr.write("#=(" + o.getClass().getName() + ". ");
                ((Fn) printArgs).invoke(o, wr);
                wr.write(")");
                return null;
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        
            }
            case 19: { Object flushFn = _a; Object closeFn = _b; Object autoFlush = _c; return (makePrintWriter((Fn) flushFn, (Fn) closeFn, RT.truthy(autoFlush))); }
            case 20: {
                Var protoVar = (Var) _a;
                Keyword methodKw = (Keyword) _b;
                String mname = _c.toString();
                Protocol.MethodFn mf = TypeFactory.defineProtocolMethodFn(protoVar, methodKw, mname, false);
                Var mvar = RT.currentNS().intern(Symbol.intern(mname));
                if (protoVar.getRawRoot() instanceof Protocol p) {
                    p.registerMethodVar(mvar, methodKw, mname);
                }
                return mf;
            }
            case 21: {
                Object nsArg = _a; Object env = _b; Object sym = _c;
                if (env != null && RT.contains(env, sym)) return null;
                Namespace n = resolveNsArg(nsArg);
                Symbol s = (Symbol) sym;
                Var v = Compiler.lookupVar(n, s, false);
                if (v != null) return v;
                return Interop.resolveClass(n, s.toString());
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    private static Object callV(int _id, Object[] _args) {
        return switch (_id >>> 5) {
            case 0 -> callV_0(_id, _args);
            case 1 -> callV_1(_id, _args);
            case 2 -> callV_2(_id, _args);
            default -> throw new IllegalStateException("Invalid dispatcher id: " + _id);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object callV_0(int _id, Object[] _args) {
        switch (_id) {
            case 0: {
                Object[] args = _args;
            Fn k = (Fn) args[0];
            Object best = args[1];
            if (args.length == 2) return best;
            double bestScore = ((Number) k.invoke(best)).doubleValue();
            for (int i = 2; i < args.length; i++) {
                Object cur = args[i];
                double sc = ((Number) k.invoke(cur)).doubleValue();
                if (sc >= bestScore) { bestScore = sc; best = cur; }
            }
            return best;
        
            }
            case 1: {
                Object[] args = _args;
            Fn k = (Fn) args[0];
            Object best = args[1];
            if (args.length == 2) return best;
            double bestScore = ((Number) k.invoke(best)).doubleValue();
            for (int i = 2; i < args.length; i++) {
                Object cur = args[i];
                double sc = ((Number) k.invoke(cur)).doubleValue();
                if (sc <= bestScore) { bestScore = sc; best = cur; }
            }
            return best;
        
            }
            case 2: {
                Object[] args = _args;
            Object precision = args[0];
            int idx = 1;
            Object rounding = null;
            if (idx + 1 < args.length && Keyword.intern("rounding").equals(args[idx])) {
                rounding = args[idx + 1];
                idx += 2;
            }
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = idx; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "with-precision*"),
                    precision,
                    rounding != null ? PersistentList.createFromArray(Symbol.intern("quote"), rounding) : null,
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        
            }
            case 3: {
                Object[] args = _args;
            Class<?> cls = (Class<?>) args[0];
            if (args.length == 2) return Array.newInstance(cls, (int) Numbers.toLong(args[1]));
            int[] dims = new int[args.length - 1];
            for (int i = 0; i < dims.length; i++) dims[i] = (int) Numbers.toLong(args[i + 1]);
            return Array.newInstance(cls, dims);
        
            }
            case 4: {
                Object[] args = _args;
            Object a = args[0], idx = args[1], ret = args[2], expr = args[3];
            return PersistentList.createFromArray(Symbol.intern("let"),
                    PersistentVector.createOwning(
                            Symbol.intern("a__"), a,
                            ret, PersistentList.createFromArray(Symbol.intern("clojure.core", "aclone"), Symbol.intern("a__"))),
                    PersistentList.createFromArray(Symbol.intern("loop"),
                            PersistentVector.createOwning(idx, 0L),
                            PersistentList.createFromArray(Symbol.intern("if"),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "<"), idx,
                                            PersistentList.createFromArray(Symbol.intern("clojure.core", "alength"), ret)),
                                    PersistentList.createFromArray(Symbol.intern("do"),
                                            PersistentList.createFromArray(Symbol.intern("clojure.core", "aset"), ret, idx, expr),
                                            PersistentList.createFromArray(Symbol.intern("recur"),
                                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "inc"), idx))),
                                    ret)));
        
            }
            case 5: {
                Object[] args = _args;
            Object a = args[0], idx = args[1], ret = args[2], init = args[3], expr = args[4];
            return PersistentList.createFromArray(Symbol.intern("let"),
                    PersistentVector.createOwning(Symbol.intern("a__"), a),
                    PersistentList.createFromArray(Symbol.intern("loop"),
                            PersistentVector.createOwning(idx, 0L, ret, init),
                            PersistentList.createFromArray(Symbol.intern("if"),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "<"), idx,
                                            PersistentList.createFromArray(Symbol.intern("clojure.core", "alength"), Symbol.intern("a__"))),
                                    PersistentList.createFromArray(Symbol.intern("recur"),
                                            PersistentList.createFromArray(Symbol.intern("clojure.core", "inc"), idx),
                                            expr),
                                    ret)));
        
            }
            case 6: { Object[] args = _args; return (PersistentTreeMap.create(PersistentTreeMap.DEFAULT_COMPARATOR, args)); }
            case 7: {
                Object[] args = _args;
            Comparator<Object> comp = asComparator(args[0]);
            PersistentTreeMap m = new PersistentTreeMap(null, comp, null, 0);
            for (int i = 1; i < args.length; i += 2) {
                m = m.assoc(args[i], args[i + 1]);
            }
            return m;
        
            }
            case 8: { Object[] args = _args; return (PersistentTreeSet.create(PersistentTreeMap.DEFAULT_COMPARATOR,
                args.length == 0 ? null : PersistentList.createFromArray(args))); }
            case 9: {
                Object[] args = _args;
            Comparator<Object> comp = asComparator(args[0]);
            ISeq items = args.length <= 1 ? null : PersistentList.createFromArraySlice(args, 1);
            return PersistentTreeSet.create(comp, items);
        
            }
            case 10: { Object[] args = _args; return (subseqImpl((Sorted) args[0], true, args, 1)); }
            case 11: { Object[] args = _args; return (subseqImpl((Sorted) args[0], false, args, 1)); }
            case 12: {
                Object[] args = _args;
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            for (int i = 1; i < args.length; i++) tv = tv.conj(args[i]);
            return tv.persistent();
        
            }
            case 13: { Object[] args = _args; return (PersistentStructMap.createSlotMap(PersistentList.createFromArray(args))); }
            case 14: {
                Object[] args = _args;
            Symbol sname = (Symbol) args[0];
            List<Object> createCall = new ArrayList<>();
            createCall.add(Symbol.intern("clojure.core", "create-struct"));
            for (int i = 1; i < args.length; i++) createCall.add(args[i]);
            return PersistentList.createFromArray(Symbol.intern("def"), sname, PersistentList.create(createCall));
        
            }
            case 15: { Object[] args = _args; return (PersistentStructMap.construct(
                        (PersistentStructMap.Def) args[0],
                        args.length > 1 ? PersistentList.createFromArraySlice(args, 1) : null)); }
            case 16: { Object[] args = _args; return (PersistentStructMap.create(
                        (PersistentStructMap.Def) args[0],
                        args.length > 1 ? PersistentList.createFromArraySlice(args, 1) : null)); }
            case 17: { Object[] args = _args; return (new ArrayChunk(args[1], (int) Numbers.toLong(args[2]), (int) Numbers.toLong(args[3]))); }
            case 18: { Object[] args = _args; return (new Vec(args[0], (int) Numbers.toLong(args[1]), (int) Numbers.toLong(args[2]),
                        (Vec.VecNode) args[3], args[4], (PersistentHashMap) args[5])); }
            case 19: { Object[] args = _args; return (new Vec.VecSeq(args[0], (Vec) args[1], (Vec.VecNode) args[2],
                        (int) Numbers.toLong(args[3]), (int) Numbers.toLong(args[4]), (PersistentHashMap) args[5])); }
            case 20: {
                Object[] args = _args;
            List<Object> seqs = new ArrayList<>(args.length + 1);
            seqs.add(Symbol.intern("clojure.core", "concat"));
            for (Object a : args) {
                seqs.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "lazy-seq"), a));
            }
            return PersistentList.create(seqs);
        
            }
            case 21: {
                Object[] args = _args;
            Fn step = (Fn) args[0];
            Fn somef = RT.truthy(args.length > 1 ? getOpt(args, "somef") : null) ? (Fn) getOpt(args, "somef")
                    : Fn.of1("some?", x -> x != null);
            Fn vf = RT.truthy(getOpt(args, "vf")) ? (Fn) getOpt(args, "vf") : Fn.of1("identity", x -> x);
            Fn kf = RT.truthy(getOpt(args, "kf")) ? (Fn) getOpt(args, "kf") : null;
            Object initk = getOpt(args, "initk");
            return iterationSeq(step, somef, vf, kf, initk, true);
        
            }
            case 22: {
                Object[] args = _args;
            Var pmapVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("pmap"));
            return ((Fn) pmapVar.deref()).invoke(Fn.of1("call", f -> ((Fn) f).invoke()),
                    PersistentList.createFromArray(args));
        
            }
            case 23: {
                Object[] args = _args;
            List<Object> calls = new ArrayList<>(args.length + 1);
            calls.add(Symbol.intern("clojure.core", "pcalls"));
            for (Object expr : args) {
                calls.add(PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, expr));
            }
            return PersistentList.create(calls);
        
            }
            case 24: {
                Object[] args = _args;
            Symbol name = (Symbol) args[0];
            List<Object> params = new ArrayList<>();
            Symbol target = Symbol.intern("target__" + RT.nextID());
            params.add(target);
            for (int i = 1; i < args.length; i++) params.add(args[i]);
            List<Object> dotCall = new ArrayList<>();
            dotCall.add(Symbol.intern("."));
            dotCall.add(target);
            dotCall.add(name);
            for (int i = 1; i < args.length; i++) dotCall.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("fn*"), PersistentVector.create(params), PersistentList.create(dotCall));
        
            }
            case 25: {
                Object[] args = _args;
                // (definline name doc? attr-map? [params*] body*)
                Symbol nameSym = (Symbol) args[0];
                int idx = 1;
                String doc = null;
                if (idx < args.length && args[idx] instanceof String s) {
                    doc = s;
                    idx++;
                }
                PersistentHashMap attrMap = null;
                if (idx < args.length && args[idx] instanceof PersistentHashMap pm) {
                    attrMap = pm;
                    idx++;
                }
                PersistentVector params = (PersistentVector) args[idx++];
                List<Object> bodyForms = new ArrayList<>();
                for (int i = idx; i < args.length; i++) bodyForms.add(args[i]);
                Object inlineFnForm = RT.cons(Symbol.intern("clojure.core", "fn"),
                        RT.cons(params, PersistentList.create(bodyForms)));

                // Strip & if variadic for non-inlined wrapper; collect clean param syms
                boolean variadic = false;
                List<Object> paramSyms = new ArrayList<>();
                for (int i = 0; i < params.count(); i++) {
                    Object p = params.nth(i);
                    if (p instanceof Symbol s && "&".equals(s.name)) { variadic = true; continue; }
                    paramSyms.add(p);
                }
                Symbol ifnSym = Symbol.intern("inline_fn__" + RT.nextID());
                Object quotedParams = PersistentList.createFromArray(Symbol.intern("quote"), params);
                Object quotedArgSyms = PersistentList.createFromArray(Symbol.intern("quote"), PersistentVector.create(paramSyms));
                Object evalFnForm = PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "eval"),
                        PersistentList.createFromArray(
                                Symbol.intern("clojure.core", "list"),
                                PersistentList.createFromArray(Symbol.intern("quote"), Symbol.intern("clojure.core", "fn")),
                                quotedParams,
                                PersistentList.createFromArray(Symbol.intern("clojure.core", "apply"), ifnSym, quotedArgSyms)));
                List<Object> metaUpdates = new ArrayList<>();
                metaUpdates.add(Symbol.intern("clojure.core", "alter-meta!"));
                metaUpdates.add(PersistentList.createFromArray(Symbol.intern("var"), nameSym));
                metaUpdates.add(Symbol.intern("clojure.core", "assoc"));
                metaUpdates.add(Keyword.intern("inline"));
                metaUpdates.add(ifnSym);
                if (!variadic) {
                    metaUpdates.add(Keyword.intern("inline-arities"));
                    metaUpdates.add(PersistentHashSet.create((long) paramSyms.size()));
                }
                if (doc != null) {
                    metaUpdates.add(Keyword.intern("doc"));
                    metaUpdates.add(doc);
                }
                if (attrMap != null) {
                    for (ISeq s = attrMap.seq(); s != null; s = s.next()) {
                        PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                        metaUpdates.add(me.key);
                        metaUpdates.add(me.val);
                    }
                }
                return PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "let"),
                        PersistentVector.createOwning(ifnSym, inlineFnForm),
                        PersistentList.createFromArray(Symbol.intern("def"), nameSym, evalFnForm),
                        PersistentList.create(metaUpdates),
                        PersistentList.createFromArray(Symbol.intern("var"), nameSym));
            }
            case 26: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("locking*"),
                    args[0],
                    PersistentList.create(body));
        
            }
            case 27: {
                Object[] args = _args;
            String msg = (args.length > 1 && args[0] instanceof String s) ? s : "I/O in transaction";
            int start = (args.length > 1 && args[0] instanceof String) ? 1 : 0;
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = start; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "io!*"),
                    msg,
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        
            }
            case 28: {
                Object[] args = _args;
                Fn xform = (Fn) args[0];
                Fn f = (Fn) args[1];
                Object init = (args.length == 4) ? args[2] : f.invoke();
                Object coll = args[args.length - 1];
                Fn rf = (Fn) xform.invoke(f);
                Object acc = Core.reduce3(rf, init, coll);
                return rf.invoke(acc);
            }
            case 29: {
                Object[] args = _args;
            Fn compFn = (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("comp")).deref();
            Object[] xforms = new Object[args.length - 1];
            System.arraycopy(args, 0, xforms, 0, xforms.length);
            Fn xf = xforms.length == 1 ? (Fn) xforms[0] : (Fn) compFn.applyTo(xforms);
            Object coll = args[args.length - 1];
            return new Eduction(xf, coll);
        
            }
            case 30: {
                Object[] args = _args;
            if (args.length == 1) {
                Object c = args[0];
                if (c instanceof ISeq) return c;
                ISeq s = RT.seq(c);
                return s == null ? PersistentList.EMPTY : s;
            }
            Fn xf = (Fn) args[0];
            if (args.length == 2) {
                return RT.seq(sequenceTransduce(xf, args[1]));
            }
            Object[] colls = new Object[args.length - 1];
            System.arraycopy(args, 1, colls, 0, colls.length);
            return RT.seq(sequenceTransduceMulti(xf, colls));
        
            }
            case 31: {
                Object[] args = _args;
            Fn xf = (Fn) args[0];
            Fn f = (Fn) args[1];
            Fn rf = (Fn) xf.invoke(f);
            Object acc = (args.length == 4) ? args[2] : rf.invoke();
            Iterator<?> it = ((java.util.stream.BaseStream<?, ?>) args[args.length - 1]).iterator();
            while (it.hasNext()) {
                acc = rf.invoke(acc, it.next());
                if (acc instanceof Reduced r) {
                    acc = r.deref();
                    break;
                }
            }
            return rf.invoke(acc);
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object callV_1(int _id, Object[] _args) {
        switch (_id) {
            case 32: {
                Object[] args = _args;
            for (Object pathObj : args) {
                String p = pathObj.toString();
                if (p.startsWith("/")) p = p.substring(1);
                String nsName = p.replace('/', '.').replace('_', '-');
                Core.loadLib(nsName);
            }
            return null;
        
            }
            case 33: {
                Object[] args = _args;
            IReference ref = (IReference) args[0];
            Fn f = (Fn) args[1];
            ISeq extra = args.length > 2 ? PersistentList.createFromArraySlice(args, 2) : null;
            return ref.alterMeta(f, extra);
        
            }
            case 34: {
                Object[] args = _args;
            for (Object o : args) {
                if (!((Var) o).isThreadBound()) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        
            }
            case 35: { Object[] args = _args; return (PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "bound-fn*"),
                        RT.cons(Symbol.intern("clojure.core", "fn"), PersistentList.createFromArray(args)))); }
            case 36: {
                Object[] args = _args;
            PersistentVector bindings = (PersistentVector) args[0];
            List<Object> letBindings = new ArrayList<>();
            List<Object> bindMap = new ArrayList<>();
            for (int i = 0; i < bindings.count(); i += 2) {
                Object sym = bindings.nth(i);
                Object init = bindings.nth(i + 1);
                letBindings.add(sym);
                letBindings.add(PersistentList.createFromArray(Symbol.intern("cljl.Var", "create")));
                bindMap.add(sym);
                bindMap.add(init);
            }
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("let"), PersistentVector.create(letBindings),
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "push-thread-bindings"),
                            PersistentHashMap.create(bindMap.toArray())),
                    PersistentList.createFromArray(Symbol.intern("try"),
                            PersistentList.create(body),
                            PersistentList.createFromArray(Symbol.intern("finally"),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "pop-thread-bindings")))));
        
            }
            case 37: {
                Object[] args = _args;
            PersistentVector bindings = (PersistentVector) args[0];
            List<Object> mKVs = new ArrayList<>();
            for (int i = 0; i < bindings.count(); i += 2) {
                mKVs.add(PersistentList.createFromArray(Symbol.intern("var"), bindings.nth(i)));
                mKVs.add(bindings.nth(i + 1));
            }
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "with-redefs-fn"),
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(mKVs)),
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        
            }
            case 38: {
                Object[] args = _args;
            Object init = args[0];
            PersistentHashMap meta = null;
            Fn validator = null;
            Fn errHandler = null;
            Keyword errMode = null;
            for (int i = 1; i + 1 < args.length; i += 2) {
                Object k = args[i];
                Object v = args[i + 1];
                if (Keyword.intern("meta").equals(k)) meta = (PersistentHashMap) v;
                else if (Keyword.intern("validator").equals(k)) validator = (Fn) v;
                else if (Keyword.intern("error-handler").equals(k)) errHandler = (Fn) v;
                else if (Keyword.intern("error-mode").equals(k)) errMode = (Keyword) v;
            }
            return new Agent(init, meta, validator, errHandler, errMode);
        
            }
            case 39: {
                Object[] args = _args;
            Agent a = (Agent) args[0];
            Fn f = (Fn) args[1];
            Object[] extra = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : Fn.EMPTY_ARGS;
            return a.dispatch(f, extra, Agent.pooledExecutor);
        
            }
            case 40: {
                Object[] args = _args;
            Agent a = (Agent) args[0];
            Fn f = (Fn) args[1];
            Object[] extra = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : Fn.EMPTY_ARGS;
            return a.dispatch(f, extra, Agent.soloExecutor);
        
            }
            case 41: {
                Object[] args = _args;
            Executor exec = (Executor) args[0];
            Agent a = (Agent) args[1];
            Fn f = (Fn) args[2];
            Object[] extra = args.length > 3 ? Arrays.copyOfRange(args, 3, args.length) : Fn.EMPTY_ARGS;
            return a.dispatch(f, extra, exec);
        
            }
            case 42: {
                Object[] args = _args;
            Agent[] arr = new Agent[args.length];
            for (int i = 0; i < args.length; i++) arr[i] = (Agent) args[i];
            try { Agent.awaitAll(arr); } catch (InterruptedException e) { throw RT.sneakyThrow(e); }
            return null;
        
            }
            case 43: {
                Object[] args = _args;
            long ms = Numbers.toLong(args[0]);
            Agent[] arr = new Agent[args.length - 1];
            for (int i = 0; i < arr.length; i++) arr[i] = (Agent) args[i + 1];
            try { return Agent.awaitForAll(ms, arr); } catch (InterruptedException e) { throw RT.sneakyThrow(e); }
        
            }
            case 44: {
                Object[] args = _args;
            Agent a = (Agent) args[0];
            Object newState = args[1];
            boolean clearActions = false;
            for (int i = 2; i + 1 < args.length; i += 2) {
                if (Keyword.intern("clear-actions").equals(args[i])) clearActions = RT.truthy(args[i + 1]);
            }
            return a.restart(newState, clearActions);
        
            }
            case 45: {
                Object[] args = _args;
            Object init = args[0];
            PersistentHashMap meta = null;
            Fn validator = null;
            int minH = 0, maxH = 10;
            for (int i = 1; i + 1 < args.length; i += 2) {
                Object k = args[i], v = args[i + 1];
                if (Keyword.intern("meta").equals(k)) meta = (PersistentHashMap) v;
                else if (Keyword.intern("validator").equals(k)) validator = (Fn) v;
                else if (Keyword.intern("min-history").equals(k)) minH = (int) Numbers.toLong(v);
                else if (Keyword.intern("max-history").equals(k)) maxH = (int) Numbers.toLong(v);
            }
            return new Ref(init, meta, validator, minH, maxH);
        
            }
            case 46: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            Collections.addAll(body, args);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "dosync*"),
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        
            }
            case 47: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "dosync*"),
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        
            }
            case 48: {
                Object[] args = _args;
            Ref r = (Ref) args[0];
            Fn f = (Fn) args[1];
            ISeq extra = args.length > 2 ? PersistentList.createFromArraySlice(args, 2) : null;
            return r.alter(f, extra);
        
            }
            case 49: {
                Object[] args = _args;
            Ref r = (Ref) args[0];
            Fn f = (Fn) args[1];
            ISeq extra = args.length > 2 ? PersistentList.createFromArraySlice(args, 2) : null;
            return r.commute(f, extra);
        
            }
            case 50: {
                Object[] args = _args;
            Fn pf = (Fn) args[0];
            return pf.invoke(args[1]);
        
            }
            case 51: {
                Object[] args = _args;
            Class<?> atype = (Class<?>) args[0];
            for (int i = 1; i + 1 < args.length; i += 2) {
                Protocol p = (Protocol) args[i];
                PersistentHashMap mmap = (PersistentHashMap) args[i + 1];
                Map<Keyword, Fn> m = new HashMap<>();
                for (ISeq s = mmap.seq(); s != null; s = s.next()) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                    m.put((Keyword) e.getKey(), (Fn) e.getValue());
                }
                p.addImpl(atype, m);
            }
            return null;
        
            }
            case 52: {
                Object[] args = _args;
            Symbol pname = (Symbol) args[0];
            int idx = 1;
            String doc = (idx < args.length && args[idx] instanceof String s) ? (String) args[idx++] : null;
            List<Object> sigSpecs = new ArrayList<>();
            List<Object> methodDefForms = new ArrayList<>();
            while (idx < args.length) {
                if (args[idx] instanceof ISeq sigSeq) {
                    Symbol mname = (Symbol) sigSeq.first();
                    List<PersistentVector> arglists = new ArrayList<>();
                    List<Object> fixedArities = new ArrayList<>();
                    String mdoc = null;
                    for (ISeq s = sigSeq.next(); s != null; s = s.next()) {
                        if (s.first() instanceof PersistentVector v) {
                            arglists.add(v);
                            boolean variadic = false;
                            for (int ai = 0; ai < v.count(); ai++) {
                                if (v.nth(ai) instanceof Symbol as && as.ns == null && "&".equals(as.name)) {
                                    variadic = true;
                                    break;
                                }
                            }
                            if (!variadic && v.count() >= 1 && v.count() <= 4) {
                                fixedArities.add(Long.valueOf(v.count()));
                            }
                        } else if (s.first() instanceof String ms) {
                            mdoc = ms;
                        }
                    }
                    if (!fixedArities.isEmpty()) {
                        sigSpecs.add(PersistentVector.createOwning(mname.name, PersistentVector.create(fixedArities)));
                    }
                    Keyword mk = Keyword.intern(mname.name);
                    PersistentHashMap meta = PersistentHashMap.EMPTY
                            .assoc(Keyword.intern("arglists"), PersistentList.createFromArray(
                                    Symbol.intern("quote"), PersistentList.create(arglists)));
                    if (mdoc != null) meta = meta.assoc(Keyword.intern("doc"), mdoc);
                    Symbol mnameWithMeta = mname.withMeta(meta);
                    methodDefForms.add(PersistentList.createFromArray(
                            Symbol.intern("def"),
                            mnameWithMeta,
                            PersistentList.createFromArray(
                                    Symbol.intern("clojure.core", "make-protocol-fn*"),
                                    PersistentList.createFromArray(Symbol.intern("var"), pname),
                                    mk,
                                    mname.name)));
                }
                idx++;
            }
            List<Object> forms = new ArrayList<>();
            forms.add(Symbol.intern("do"));
            forms.add(PersistentList.createFromArray(
                    Symbol.intern("def"),
                    pname,
                    PersistentList.createFromArray(
                            Symbol.intern("clojure.core", "register-protocol*"),
                            PersistentList.createFromArray(Symbol.intern("quote"), pname),
                            doc,
                            PersistentVector.create(sigSpecs))));
            forms.addAll(methodDefForms);
            forms.add(pname);
            return PersistentList.create(forms);
        
            }
            case 53: { Object[] args = _args; return (expandExtendType(args)); }
            case 54: {
                Object[] args = _args;
            Object proto = args[0];
            List<Object> forms = new ArrayList<>();
            forms.add(Symbol.intern("do"));
            int i = 1;
            while (i < args.length) {
                Object typeSym = args[i++];
                List<Object> etArgs = new ArrayList<>();
                etArgs.add(Symbol.intern("clojure.core", "extend-type"));
                etArgs.add(typeSym);
                etArgs.add(proto);
                while (i < args.length && args[i] instanceof ISeq) {
                    etArgs.add(args[i++]);
                }
                forms.add(PersistentList.create(etArgs));
            }
            return PersistentList.create(forms);
        
            }
            case 55: {
                Object[] args = _args;
            Symbol nameSym = (Symbol) args[0];
            String[] fields = toStringArray(args[1]);
            Class<?>[] ifaces = resolveInterfaces(args[2]);
            Map<String, Fn> javaMethods = toMethodMap(args[3]);
            Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[4]);

            Namespace cur = RT.currentNS();
            String binaryName = cur.name.name.replace('-', '_') + "." + nameSym.name;
            Class<?> recCls = TypeFactory.defineRecord(binaryName, fields, ifaces, javaMethods);
            cur.importClass(nameSym, recCls);
            for (Map.Entry<Protocol, Map<Keyword, Fn>> e : protoImpls.entrySet()) {
                e.getKey().addImpl(recCls, e.getValue());
            }
            // Bind ->Name and map->Name constructors
            cur.intern("->" + nameSym.name, Fn.ofVarargs("->" + nameSym.name,
                    ctorArgs -> Interop.invokeConstructor(recCls, ctorArgs)));
            cur.intern("map->" + nameSym.name, Fn.of1("map->" + nameSym.name, mObj -> {
                Map<?, ?> m = (Map<?, ?>) mObj;
                Object[] ctorArgs = new Object[fields.length + 2];
                PersistentHashMap ext = PersistentHashMap.EMPTY;
                Set<Object> baseSet = new HashSet<>();
                for (int i = 0; i < fields.length; i++) {
                    Keyword fk = Keyword.intern(fields[i]);
                    baseSet.add(fk);
                    ctorArgs[i] = m != null ? m.get(fk) : null;
                }
                if (m != null) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (!baseSet.contains(e.getKey())) ext = ext.assoc(e.getKey(), e.getValue());
                    }
                }
                ctorArgs[fields.length] = null;
                ctorArgs[fields.length + 1] = ext;
                return Interop.invokeConstructor(recCls, ctorArgs);
            }));
            return recCls;
        
            }
            case 56: { Object[] args = _args; return (expandTypeOrRecord(args, true)); }
            case 57: {
                Object[] args = _args;
            Symbol nameSym = (Symbol) args[0];
            String[] fields = toStringArray(args[1]);
            boolean[] mutables = new boolean[fields.length];
            if (args.length > 5 && args[5] instanceof PersistentVector mv) {
                for (int i = 0; i < Math.min(fields.length, mv.count()); i++) {
                    mutables[i] = RT.truthy(mv.nth(i));
                }
            }
            Class<?>[] ifaces = resolveInterfaces(args[2]);
            Map<String, Fn> javaMethods = toMethodMap(args[3]);
            Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[4]);

            Namespace cur = RT.currentNS();
            String binaryName = cur.name.name.replace('-', '_') + "." + nameSym.name;
            Class<?> typeCls = TypeFactory.defineType(binaryName, fields, mutables, ifaces, javaMethods);
            cur.importClass(nameSym, typeCls);
            for (Map.Entry<Protocol, Map<Keyword, Fn>> e : protoImpls.entrySet()) {
                e.getKey().addImpl(typeCls, e.getValue());
            }
            cur.intern("->" + nameSym.name, Fn.ofVarargs("->" + nameSym.name,
                    ctorArgs -> Interop.invokeConstructor(typeCls, ctorArgs)));
            return typeCls;
        
            }
            case 58: { Object[] args = _args; return (expandTypeOrRecord(args, false)); }
            case 59: {
                Object[] args = _args;
            Class<?>[] ifaces = resolveInterfaces(args[0]);
            Map<String, Fn> javaMethods = toMethodMap(args[1]);
            Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[2]);
            return TypeFactory.createReify(ifaces, javaMethods, protoImpls, null);
        
            }
            case 60: { Object[] args = _args; return (expandReify(args)); }
            case 61: { Object[] args = _args; return (PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "definterface*"),
                        PersistentList.createFromArray(Symbol.intern("quote"), args[0]),
                        PersistentList.createFromArray(Symbol.intern("quote"),
                                args.length > 1 ? PersistentList.createFromArraySlice(args, 1) : PersistentList.EMPTY))); }
            case 62: {
                Object[] args = _args;
            String name = getOpt(args, "name").toString();
            Object methodsObj = getOpt(args, "methods");
            List<TypeFactory.MethodSig> ms = new ArrayList<>();
            for (ISeq s = RT.seq(methodsObj); s != null; s = s.next()) {
                PersistentVector sig = (PersistentVector) s.first();
                String mname = sig.nth(0).toString();
                PersistentVector pv = (PersistentVector) sig.nth(1);
                Class<?>[] ptypes = new Class<?>[pv.count()];
                for (int i = 0; i < ptypes.length; i++) {
                    Class<?> c = Interop.resolveClass(RT.currentNS(), pv.nth(i).toString());
                    ptypes[i] = c != null ? c : Object.class;
                }
                Class<?> ret = Interop.resolveClass(RT.currentNS(), sig.nth(2).toString());
                ms.add(new TypeFactory.MethodSig(mname, ret != null ? ret : Object.class, ptypes));
            }
            return TypeFactory.defineInterface(name, ms);
        
            }
            case 63: { Object[] args = _args; return (GenClass.genClass(args)); }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object callV_2(int _id, Object[] _args) {
        switch (_id) {
            case 64: {
                Object[] args = _args;
            Class<?> superCls = null;
            List<Class<?>> ifaces = new ArrayList<>();
            for (Object a : args) {
                Class<?> c = (Class<?>) a;
                if (c.isInterface()) ifaces.add(c);
                else if (superCls == null) superCls = c;
            }
            return TypeFactory.getProxyClass(superCls, ifaces.toArray(new Class<?>[0]));
        
            }
            case 65: { Object[] args = _args; return (TypeFactory.constructProxy((Class<?>) args[0], Arrays.copyOfRange(args, 1, args.length))); }
            case 66: {
                Object[] args = _args;
            PersistentVector classAndIfaces = (PersistentVector) args[0];
            PersistentVector superArgs = (PersistentVector) args[1];
            List<Object> getClassCall = new ArrayList<>();
            getClassCall.add(Symbol.intern("clojure.core", "get-proxy-class"));
            for (int i = 0; i < classAndIfaces.count(); i++) getClassCall.add(classAndIfaces.nth(i));

            List<Object> constructCall = new ArrayList<>();
            constructCall.add(Symbol.intern("clojure.core", "construct-proxy"));
            constructCall.add(PersistentList.create(getClassCall));
            for (int i = 0; i < superArgs.count(); i++) constructCall.add(superArgs.nth(i));

            Map<String, List<Object>> grouped = new LinkedHashMap<>();
            for (int i = 2; i < args.length; i++) {
                ISeq ms = (ISeq) args[i];
                String mname = ms.first().toString();
                ISeq rest = ms.next();
                grouped.computeIfAbsent(mname, k -> new ArrayList<>()).add(rest);
            }
            List<Object> mapKVs = new ArrayList<>();
            for (Map.Entry<String, List<Object>> e : grouped.entrySet()) {
                mapKVs.add(e.getKey());
                List<Object> fnClauses = new ArrayList<>();
                fnClauses.add(Symbol.intern("clojure.core", "fn"));
                for (Object clause : e.getValue()) {
                    ISeq cs = (ISeq) clause;
                    if (cs.first() instanceof PersistentVector pv) {
                        PersistentVector withThis = insertThisFirst(pv);
                        fnClauses.add(withThis);
                        for (ISeq b = cs.next(); b != null; b = b.next()) fnClauses.add(b.first());
                        break;
                    } else {
                        for (ISeq cs2 = cs; cs2 != null; cs2 = cs2.next()) {
                            ISeq arity = (ISeq) cs2.first();
                            PersistentVector withThis = insertThisFirst((PersistentVector) arity.first());
                            fnClauses.add(RT.cons(withThis, arity.next()));
                        }
                        break;
                    }
                }
                mapKVs.add(PersistentList.create(fnClauses));
            }
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "init-proxy"),
                    PersistentList.create(constructCall),
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(mapKVs)));
        
            }
            case 67: {
                Object[] args = _args;
            Symbol mname = Symbol.intern("__super_" + args[0]);
            List<Object> dot = new ArrayList<>();
            dot.add(Symbol.intern("."));
            dot.add(Symbol.intern("this"));
            dot.add(mname);
            for (int i = 1; i < args.length; i++) dot.add(args[i]);
            return PersistentList.create(dot);
        
            }
            case 68: {
                // register-record-class*: defines JVM record class & constructors before method fns compile
                Object[] args = _args;
                Symbol nameSym = (Symbol) args[0];
                String[] fields = toStringArray(args[1]);
                Class<?>[] ifaces = resolveInterfaces(args[2]);
                Set<String> implMethodNames = new HashSet<>();
                Map<String, Fn> placeholderMethods = new LinkedHashMap<>();
                for (String mn : toStringArray(args[3])) {
                    placeholderMethods.put(mn, null);
                    implMethodNames.add(mn);
                }
                List<Protocol> directProtos = new ArrayList<>();
                Set<Class<?>> directProtoIfaces = new HashSet<>();
                for (ISeq s = RT.seq(args[2]); s != null; s = s.next()) {
                    Object item = s.first();
                    Protocol p = item instanceof Protocol pr ? pr : (item instanceof Class<?> c ? Protocol.findByInterface(c) : null);
                    if (p != null && p.onInterface != null && p.interfaceMethodArities != null && !p.interfaceMethodArities.isEmpty()) {
                        if (implMethodNames.containsAll(p.interfaceMethodArities.keySet())) {
                            directProtos.add(p);
                            directProtoIfaces.add(p.onInterface);
                        }
                    }
                }
                if (!directProtoIfaces.isEmpty() && ifaces.length > 0) {
                    List<Class<?>> filtered = new ArrayList<>(ifaces.length);
                    for (Class<?> ic : ifaces) {
                        if (!directProtoIfaces.contains(ic)) filtered.add(ic);
                    }
                    ifaces = filtered.toArray(new Class<?>[0]);
                }
                char[] fieldTypes = new char[fields.length];
                Arrays.fill(fieldTypes, 'O');
                if (args.length > 4 && args[4] != null) {
                    String[] fts = toStringArray(args[4]);
                    for (int fi = 0; fi < Math.min(fields.length, fts.length); fi++) {
                        fieldTypes[fi] = fts[fi].isEmpty() ? 'O' : fts[fi].charAt(0);
                    }
                }
                Namespace cur = RT.currentNS();
                String binaryName = cur.name.name.replace('-', '_') + "." + nameSym.name;
                Class<?> recCls = TypeFactory.defineRecord(binaryName, fields, fieldTypes, ifaces, placeholderMethods, directProtos);
                cur.importClass(nameSym, recCls);
                cur.intern("->" + nameSym.name, Fn.ofVarargs("->" + nameSym.name,
                        ctorArgs -> Interop.invokeConstructor(recCls, ctorArgs)));
                cur.intern("map->" + nameSym.name, Fn.of1("map->" + nameSym.name, mObj -> {
                    Map<?, ?> m = (Map<?, ?>) mObj;
                    Object[] ctorArgs = new Object[fields.length + 2];
                    PersistentHashMap ext = PersistentHashMap.EMPTY;
                    Set<Object> baseSet = new HashSet<>();
                    for (int i = 0; i < fields.length; i++) {
                        Keyword fk = Keyword.intern(fields[i]);
                        baseSet.add(fk);
                        ctorArgs[i] = m != null ? m.get(fk) : null;
                    }
                    if (m != null) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            if (!baseSet.contains(e.getKey())) ext = ext.assoc(e.getKey(), e.getValue());
                        }
                    }
                    ctorArgs[fields.length] = null;
                    ctorArgs[fields.length + 1] = ext;
                    return Interop.invokeConstructor(recCls, ctorArgs);
                }));
                return recCls;
            }
            case 69: {
                // register-record-impls*: attaches compiled Java & protocol method fns to already-defined record class
                Object[] args = _args;
                Symbol nameSym = (Symbol) args[0];
                Map<String, Fn> javaMethods = toMethodMap(args[1]);
                Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[2]);
                Namespace cur = RT.currentNS();
                String binaryName = cur.name.name.replace('-', '_') + "." + nameSym.name;
                Class<?> recCls = Interop.resolveClass(cur, nameSym.name);
                TypeFactory.setClassMethods(binaryName, javaMethods);
                TypeFactory.bindRecordProtocolMethods(recCls, protoImpls);
                return recCls;
            }
            case 70: {
                // register-protocol*: creates Protocol + optional direct dispatch JVM interface
                Object[] args = _args;
                Symbol nameSym = (Symbol) args[0];
                String doc = args.length > 1 ? (String) args[1] : null;
                Var existing = RT.currentNS().findInternedVar(nameSym);
                if (existing != null && existing.getRawRoot() instanceof Protocol oldP) {
                    oldP.markRedefined();
                }
                Map<String, int[]> aritiesMap = null;
                Class<?> onIface = null;
                if (args.length > 2 && args[2] != null) {
                    aritiesMap = new LinkedHashMap<>();
                    for (ISeq s = RT.seq(args[2]); s != null; s = s.next()) {
                        PersistentVector entry = (PersistentVector) s.first();
                        String mn = entry.nth(0).toString();
                        PersistentVector avec = (PersistentVector) entry.nth(1);
                        int[] arr = new int[avec.count()];
                        for (int i = 0; i < arr.length; i++) arr[i] = (int) Numbers.toLong(avec.nth(i));
                        aritiesMap.put(mn, arr);
                    }
                }
                String ifaceBinaryName = RT.currentNS().name.name.replace('-', '_') + "." + nameSym.name;
                onIface = TypeFactory.defineProtocolInterface(ifaceBinaryName, aritiesMap);
                return new Protocol(nameSym, doc, onIface, aritiesMap);
            }
            case 71: {
                // register-type-class*: defines deftype JVM class & ->Name ctor BEFORE method bodies compile
                Object[] args = _args;
                Symbol nameSym = (Symbol) args[0];
                String[] fields = toStringArray(args[1]);
                Class<?>[] ifaces = resolveInterfaces(args[2]);
                Map<String, Fn> placeholderMethods = new LinkedHashMap<>();
                for (String mn : toStringArray(args[3])) {
                    placeholderMethods.put(mn, null);
                }
                boolean[] mutables = new boolean[fields.length];
                if (args.length > 4 && args[4] != null) {
                    int k = 0;
                    for (ISeq s = RT.seq(args[4]); s != null && k < fields.length; s = s.next(), k++) {
                        mutables[k] = RT.truthy(s.first());
                    }
                }
                Namespace cur = RT.currentNS();
                String binaryName = cur.name.name.replace('-', '_') + "." + nameSym.name;
                Class<?> typeCls = TypeFactory.defineType(binaryName, fields, mutables, ifaces, placeholderMethods);
                cur.importClass(nameSym, typeCls);
                cur.intern("->" + nameSym.name, Fn.ofVarargs("->" + nameSym.name,
                        ctorArgs -> Interop.invokeConstructor(typeCls, ctorArgs)));
                return typeCls;
            }
            case 72: {
                // register-type-impls*: attaches compiled Java & protocol method fns to already-defined deftype class
                Object[] args = _args;
                Symbol nameSym = (Symbol) args[0];
                Map<String, Fn> javaMethods = toMethodMap(args[1]);
                Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[2]);
                Namespace cur = RT.currentNS();
                String binaryName = cur.name.name.replace('-', '_') + "." + nameSym.name;
                Class<?> typeCls = Interop.resolveClass(cur, nameSym.name);
                TypeFactory.setClassMethods(binaryName, javaMethods);
                for (Map.Entry<Protocol, Map<Keyword, Fn>> e : protoImpls.entrySet()) {
                    e.getKey().addImpl(typeCls, e.getValue());
                }
                return typeCls;
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    static Fn createTransducer0(int id, String name) {
        return new Fn.NamedFn(name + "-xf") {
            @Override public Object invoke(Object rf) { return makeTransducer0(id, rf); }
        };
    }

    static Fn createTransducer1(int id, String name, Object a) {
        return new Fn.NamedFn(name + "-xf") {
            @Override public Object invoke(Object rf) { return makeTransducer1(id, a, rf); }
        };
    }

    @SuppressWarnings("unchecked")
    private static Object makeTransducer0(int _id, Object _rf) {
        switch (_id) {
            case 0: {
                Object rf = _rf;
            Fn r = (Fn) rf;
            Set<RT.EquivKey> seen = new HashSet<>();
            return new Fn.NamedFn("distinct-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    return seen.add(new RT.EquivKey(x)) ? r.invoke(acc, x) : acc;
                }
            };
        
            }
            case 1: {
                Object rf = _rf;
            Fn r = (Fn) rf;
            return new Fn.NamedFn("dedupe-xf") {
                Object prior = REDUCED_NONE;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    if (prior != REDUCED_NONE && RT.equiv(prior, x)) return acc;
                    prior = x;
                    return r.invoke(acc, x);
                }
            };
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    private static Fn unwrapHotFn(Fn f) {
        return (f instanceof Compiler.CompiledFn cf) ? cf.ensureJit() : f;
    }

    private static final class MapRF extends Fn.NamedFn {
        private final Fn fn;
        private final Fn r;
        MapRF(Fn fn, Fn r) { super("map-xf"); this.fn = unwrapHotFn(fn); this.r = unwrapHotFn(r); }
        @Override public Object invoke() { return r.invoke(); }
        @Override public Object invoke(Object acc) { return r.invoke(acc); }
        @Override public Object invoke(Object acc, Object x) { return r.invoke(acc, fn.invoke(x)); }
        @Override public boolean isUnboxedLong2() { return fn.isUnboxedLong1() && r.isUnboxedLong2(); }
        @Override public long invokePrim_JJ_J(long acc, long x) { return r.invokePrim_JJ_J(acc, fn.invokePrim_J_J(x)); }
        @Override public Object invokePrim_OJ_O(Object acc, long x) {
            return fn.isUnboxedLong1()
                    ? r.invokePrim_OJ_O(acc, fn.invokePrim_J_J(x))
                    : r.invoke(acc, fn.invoke(Long.valueOf(x)));
        }
        @Override public Object invokeVarargs(Object[] args) {
            if (args.length < 2) return super.invokeVarargs(args);
            Object[] vals = new Object[args.length - 1];
            System.arraycopy(args, 1, vals, 0, vals.length);
            return r.invoke(args[0], fn.applyTo(vals));
        }
    }

    private static final class FilterRF extends Fn.NamedFn {
        private final Fn p;
        private final Fn r;
        FilterRF(Fn p, Fn r) { super("filter-xf"); this.p = unwrapHotFn(p); this.r = unwrapHotFn(r); }
        @Override public Object invoke() { return r.invoke(); }
        @Override public Object invoke(Object acc) { return r.invoke(acc); }
        @Override public Object invoke(Object acc, Object x) {
            return RT.truthy(p.invoke(x)) ? r.invoke(acc, x) : acc;
        }
        @Override public boolean isUnboxedLong2() { return r.isUnboxedLong2(); }
        @Override public long invokePrim_JJ_J(long acc, long x) {
            return p.invokePrim_J_Z(x) ? r.invokePrim_JJ_J(acc, x) : acc;
        }
        @Override public Object invokePrim_OJ_O(Object acc, long x) {
            return p.invokePrim_J_Z(x) ? r.invokePrim_OJ_O(acc, x) : acc;
        }
    }

    private static final class RemoveRF extends Fn.NamedFn {
        private final Fn p;
        private final Fn r;
        RemoveRF(Fn p, Fn r) { super("remove-xf"); this.p = unwrapHotFn(p); this.r = unwrapHotFn(r); }
        @Override public Object invoke() { return r.invoke(); }
        @Override public Object invoke(Object acc) { return r.invoke(acc); }
        @Override public Object invoke(Object acc, Object x) {
            return !RT.truthy(p.invoke(x)) ? r.invoke(acc, x) : acc;
        }
        @Override public boolean isUnboxedLong2() { return r.isUnboxedLong2(); }
        @Override public long invokePrim_JJ_J(long acc, long x) {
            return !p.invokePrim_J_Z(x) ? r.invokePrim_JJ_J(acc, x) : acc;
        }
        @Override public Object invokePrim_OJ_O(Object acc, long x) {
            return !p.invokePrim_J_Z(x) ? r.invokePrim_OJ_O(acc, x) : acc;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object makeTransducer1(int _id, Object _a, Object _rf) {
        switch (_id) {
            case 0: {
                return new MapRF((Fn) _a, (Fn) _rf);
            }
            case 1: {
                return new FilterRF((Fn) _a, (Fn) _rf);
            }
            case 2: {
                return new RemoveRF((Fn) _a, (Fn) _rf);
            }
            case 3: {
                Object nObj = _a; Object rf = _rf;
            long n = Numbers.toLong(nObj); Fn r = (Fn) rf;
            return new Fn.NamedFn("take-xf") {
                long rem = n;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    if (rem <= 0) return acc instanceof Reduced ? acc : new Reduced(acc);
                    long cur = rem--;
                    Object res = cur > 0 ? r.invoke(acc, x) : acc;
                    return (rem <= 0 && !(res instanceof Reduced)) ? new Reduced(res) : res;
                }
            };
        
            }
            case 4: {
                Object pred = _a; Object rf = _rf;
            Fn p = (Fn) pred; Fn r = (Fn) rf;
            return new Fn.NamedFn("take-while-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    return RT.truthy(p.invoke(x)) ? r.invoke(acc, x) : new Reduced(acc);
                }
            };
        
            }
            case 5: {
                Object nObj = _a; Object rf = _rf;
            long n = Numbers.toLong(nObj); Fn r = (Fn) rf;
            return new Fn.NamedFn("take-nth-xf") {
                long idx = -1;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    idx++;
                    return (n <= 0 || idx % n == 0) ? r.invoke(acc, x) : acc;
                }
            };
        
            }
            case 6: {
                Object nObj = _a; Object rf = _rf;
            long n = Numbers.toLong(nObj); Fn r = (Fn) rf;
            return new Fn.NamedFn("drop-xf") {
                long rem = n;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    if (rem > 0) { rem--; return acc; }
                    return r.invoke(acc, x);
                }
            };
        
            }
            case 7: {
                Object pred = _a; Object rf = _rf;
            Fn p = (Fn) pred; Fn r = (Fn) rf;
            return new Fn.NamedFn("drop-while-xf") {
                boolean dropping = true;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    if (dropping && RT.truthy(p.invoke(x))) return acc;
                    dropping = false;
                    return r.invoke(acc, x);
                }
            };
        
            }
            case 8: {
                Object f = _a; Object rf = _rf;
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("keep-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    Object v = fn.invoke(x);
                    return v != null ? r.invoke(acc, v) : acc;
                }
            };
        
            }
            case 9: {
                Object f = _a; Object rf = _rf;
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("keep-indexed-xf") {
                long idx = 0;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    Object v = fn.invoke(idx++, x);
                    return v != null ? r.invoke(acc, v) : acc;
                }
            };
        
            }
            case 10: {
                Object f = _a; Object rf = _rf;
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("map-indexed-xf") {
                long idx = 0;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) { return r.invoke(acc, fn.invoke(idx++, x)); }
            };
        
            }
            case 11: {
                Fn fn = (Fn) _a;
                Fn mapXf = (Fn) ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("map")).deref()).invoke(fn);
                Fn catFn = (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("cat")).deref();
                return mapXf.invoke(catFn.invoke(_rf));
            }
            case 12: {
                Object sep = _a; Object rf = _rf;
            Fn r = (Fn) rf;
            return new Fn.NamedFn("interpose-xf") {
                boolean started = false;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    if (started) {
                        Object res = r.invoke(acc, sep);
                        if (res instanceof Reduced) return res;
                        return r.invoke(res, x);
                    }
                    started = true;
                    return r.invoke(acc, x);
                }
            };
        
            }
            case 13: {
                Object nObj = _a; Object rf = _rf;
            int n = (int) Numbers.toLong(nObj); Fn r = (Fn) rf;
            return new Fn.NamedFn("partition-all-xf") {
                List<Object> buf = new ArrayList<>(n);
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) {
                    Object res = acc;
                    if (!buf.isEmpty()) {
                        PersistentVector v = PersistentVector.create(buf);
                        buf.clear();
                        res = r.invoke(res, v);
                        if (res instanceof Reduced red) res = red.deref();
                    }
                    return r.invoke(res);
                }
                @Override public Object invoke(Object acc, Object x) {
                    buf.add(x);
                    if (buf.size() == n) {
                        PersistentVector v = PersistentVector.create(buf);
                        buf.clear();
                        return r.invoke(acc, v);
                    }
                    return acc;
                }
            };
        
            }
            case 14: {
                Object nObj = _a; Object rf = _rf;
            int n = (int) Numbers.toLong(nObj); Fn r = (Fn) rf;
            return new Fn.NamedFn("partitionv-all-xf") {
                List<Object> buf = new ArrayList<>(n);
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) {
                    Object res = acc;
                    if (!buf.isEmpty()) {
                        PersistentVector v = PersistentVector.create(buf);
                        buf.clear();
                        res = r.invoke(res, v);
                        if (res instanceof Reduced red) res = red.deref();
                    }
                    return r.invoke(res);
                }
                @Override public Object invoke(Object acc, Object x) {
                    buf.add(x);
                    if (buf.size() == n) {
                        PersistentVector v = PersistentVector.create(buf);
                        buf.clear();
                        return r.invoke(acc, v);
                    }
                    return acc;
                }
            };
        
            }
            case 15: {
                Object f = _a; Object rf = _rf;
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("partition-by-xf") {
                List<Object> buf = new ArrayList<>();
                Object prev = REDUCED_NONE;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) {
                    Object res = acc;
                    if (!buf.isEmpty()) {
                        PersistentVector v = PersistentVector.create(buf);
                        buf.clear();
                        res = r.invoke(res, v);
                        if (res instanceof Reduced red) res = red.deref();
                    }
                    return r.invoke(res);
                }
                @Override public Object invoke(Object acc, Object x) {
                    Object k = fn.invoke(x);
                    if (prev == REDUCED_NONE || RT.equiv(prev, k)) {
                        prev = k;
                        buf.add(x);
                        return acc;
                    }
                    PersistentVector v = PersistentVector.create(buf);
                    buf.clear();
                    buf.add(x);
                    prev = k;
                    return r.invoke(acc, v);
                }
            };
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }
}
