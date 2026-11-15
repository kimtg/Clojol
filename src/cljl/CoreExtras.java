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

    public static final Var GLOBAL_HIERARCHY = RT.CLOJURE_NS.intern(
            "global-hierarchy", makeHierarchy());

    private static final CopyOnWriteArraySet<Fn> TAP_SET = new CopyOnWriteArraySet<>();
    private static final ExecutorService TAP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cljl-tap");
        t.setDaemon(true);
        return t;
    });

    public static final ThreadLocal<MathContext> MATH_CONTEXT = new ThreadLocal<>();

    private CoreExtras() {}

    public static void init(Namespace ns) {
        initDynamicVarsAndTables(ns);
        initPredicatesAndCasts(ns);
        initMathAndParsing(ns);
        initArrays(ns);
        initSortedCollections(ns);
        initSeqsAndMisc(ns);
        initTransducers(ns);
        initHierarchiesAndMulti(ns);
        initNamespacesAndVars(ns);
        initConcurrency(ns);
        initProtocolsAndTypes(ns);
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
        def(ns, "clojure-version", Fn.of0("clojure-version", () -> {
            PersistentHashMap m = (PersistentHashMap) RT.CLOJURE_VERSION.deref();
            StringBuilder sb = new StringBuilder();
            sb.append(m.valAt(Keyword.intern("major"))).append('.')
              .append(m.valAt(Keyword.intern("minor"))).append('.')
              .append(m.valAt(Keyword.intern("incremental")));
            Object q = m.valAt(Keyword.intern("qualifier"));
            if (q != null) sb.append('-').append(q);
            return sb.toString();
        }));

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
        def(ns, "any?", Fn.of1("any?", x -> Boolean.TRUE));
        def(ns, "bytes?", Fn.of1("bytes?", x -> x instanceof byte[]));
        def(ns, "ident?", Fn.of1("ident?", x -> x instanceof Keyword || x instanceof Symbol));
        def(ns, "simple-ident?", Fn.of1("simple-ident?", x ->
                (x instanceof Keyword k && k.getNamespace() == null)
                        || (x instanceof Symbol s && s.ns == null)));
        def(ns, "qualified-ident?", Fn.of1("qualified-ident?", x ->
                (x instanceof Keyword k && k.getNamespace() != null)
                        || (x instanceof Symbol s && s.ns != null)));
        def(ns, "indexed?", Fn.of1("indexed?", x -> x instanceof PersistentVector || x instanceof RandomAccess));
        def(ns, "infinite?", Fn.of1("infinite?", x -> {
            double d = ((Number) x).doubleValue();
            return Double.isInfinite(d);
        }));
        def(ns, "NaN?", Fn.of1("NaN?", x -> Double.isNaN(((Number) x).doubleValue())));
        def(ns, "inst?", Fn.of1("inst?", x -> x instanceof Date || x instanceof java.time.Instant));
        def(ns, "uuid?", Fn.of1("uuid?", x -> x instanceof UUID));
        def(ns, "uri?", Fn.of1("uri?", x -> x instanceof URI));
        def(ns, "map-entry?", Fn.of1("map-entry?", x -> x instanceof Map.Entry<?, ?>));
        def(ns, "record?", Fn.of1("record?", x -> x instanceof PersistentRecord));
        def(ns, "reversible?", Fn.of1("reversible?", x -> x instanceof Reversible));
        def(ns, "sorted?", Fn.of1("sorted?", x -> x instanceof Sorted));

        def(ns, "cast", Fn.of2("cast", (c, x) -> ((Class<?>) c).cast(x)));

        def(ns, "bases", Fn.of1("bases", c -> {
            if (c == null) return null;
            Class<?> cls = (Class<?>) c;
            List<Class<?>> ret = new ArrayList<>();
            if (cls.getSuperclass() != null) ret.add(cls.getSuperclass());
            Collections.addAll(ret, cls.getInterfaces());
            return ret.isEmpty() ? null : PersistentList.create(ret);
        }));

        def(ns, "supers", Fn.of1("supers", c -> {
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
        }));

        def(ns, "bean", Fn.of1("bean", x -> {
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
        }));

        def(ns, "StackTraceElement->vec", Fn.of1("StackTraceElement->vec", o -> {
            StackTraceElement ste = (StackTraceElement) o;
            return PersistentVector.createOwning(
                    Symbol.intern(ste.getClassName()),
                    Symbol.intern(ste.getMethodName()),
                    ste.getFileName(),
                    (long) ste.getLineNumber());
        }));

        def(ns, "Throwable->map", Fn.of1("Throwable->map", o -> throwableToMap((Throwable) o)));
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
        def(ns, "byte", Fn.of1("byte", Numbers::byteCast));
        def(ns, "short", Fn.of1("short", Numbers::shortCast));
        def(ns, "unchecked-byte", Fn.of1("unchecked-byte", Numbers::uncheckedByte));
        def(ns, "unchecked-short", Fn.of1("unchecked-short", Numbers::uncheckedShort));
        def(ns, "unchecked-int", Fn.of1("unchecked-int", Numbers::uncheckedInt));
        def(ns, "unchecked-long", Fn.of1("unchecked-long", Numbers::uncheckedLong));
        def(ns, "unchecked-char", Fn.of1("unchecked-char", Numbers::uncheckedChar));
        def(ns, "unchecked-float", Fn.of1("unchecked-float", Numbers::uncheckedFloat));
        def(ns, "unchecked-double", Fn.of1("unchecked-double", Numbers::uncheckedDouble));
        def(ns, "num", Fn.of1("num", x -> (Number) x));
        def(ns, "biginteger", Fn.of1("biginteger", x -> {
            if (x instanceof BigInteger bi) return bi;
            if (x instanceof BigDecimal bd) return bd.toBigInteger();
            if (x instanceof Ratio r) return r.numerator.divide(r.denominator);
            if (x instanceof String s) return new BigInteger(s);
            return BigInteger.valueOf(Numbers.toLong(x));
        }));
        def(ns, "rationalize", Fn.of1("rationalize", x -> {
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
        }));

        def(ns, "parse-boolean", Fn.of1("parse-boolean", s -> {
            String str = (String) s;
            if ("true".equals(str)) return Boolean.TRUE;
            if ("false".equals(str)) return Boolean.FALSE;
            return null;
        }));
        def(ns, "parse-long", Fn.of1("parse-long", s -> {
            try { return Long.parseLong((String) s); } catch (NumberFormatException e) { return null; }
        }));
        def(ns, "parse-double", Fn.of1("parse-double", s -> {
            try { return Double.parseDouble((String) s); } catch (NumberFormatException e) { return null; }
        }));
        def(ns, "parse-uuid", Fn.of1("parse-uuid", s -> {
            try { return UUID.fromString((String) s); } catch (IllegalArgumentException e) { return null; }
        }));
        def(ns, "random-uuid", Fn.of0("random-uuid", UUID::randomUUID));

        def(ns, "unchecked-add", Fn.of2("unchecked-add", (a, b) -> {
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return ((Number) a).doubleValue() + ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() + ((Number) b).longValue();
        }));
        def(ns, "unchecked-subtract", Fn.of2("unchecked-subtract", (a, b) -> {
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return ((Number) a).doubleValue() - ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() - ((Number) b).longValue();
        }));
        def(ns, "unchecked-multiply", Fn.of2("unchecked-multiply", (a, b) -> {
            if (a instanceof Double || b instanceof Double || a instanceof Float || b instanceof Float) {
                return ((Number) a).doubleValue() * ((Number) b).doubleValue();
            }
            return ((Number) a).longValue() * ((Number) b).longValue();
        }));
        def(ns, "unchecked-inc", Fn.of1("unchecked-inc", a -> {
            if (a instanceof Double || a instanceof Float) return ((Number) a).doubleValue() + 1.0;
            return ((Number) a).longValue() + 1L;
        }));
        def(ns, "unchecked-dec", Fn.of1("unchecked-dec", a -> {
            if (a instanceof Double || a instanceof Float) return ((Number) a).doubleValue() - 1.0;
            return ((Number) a).longValue() - 1L;
        }));
        def(ns, "unchecked-negate", Fn.of1("unchecked-negate", a -> {
            if (a instanceof Double || a instanceof Float) return -((Number) a).doubleValue();
            return -((Number) a).longValue();
        }));

        def(ns, "max-key", Fn.ofVarargs("max-key", args -> {
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
        }));
        def(ns, "min-key", Fn.ofVarargs("min-key", args -> {
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
        }));

        def(ns, "with-precision*", Fn.of3("with-precision*", (prec, rounding, bodyFn) -> {
            int p = (int) Numbers.toLong(prec);
            RoundingMode rm = rounding != null ? RoundingMode.valueOf(rounding.toString()) : RoundingMode.HALF_UP;
            MathContext prev = MATH_CONTEXT.get();
            MATH_CONTEXT.set(new MathContext(p, rm));
            try {
                return ((Fn) bodyFn).invoke();
            } finally {
                if (prev == null) MATH_CONTEXT.remove();
                else MATH_CONTEXT.set(prev);
            }
        }));
        defMacro(ns, "with-precision", Fn.ofVarargs("with-precision", args -> {
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
        }));
    }

    // ========================================================================
    // Java Arrays
    // ========================================================================

    private static void initArrays(Namespace ns) {
        def(ns, "booleans", Fn.of1("booleans", x -> (boolean[]) x));
        def(ns, "bytes", Fn.of1("bytes", x -> (byte[]) x));
        def(ns, "chars", Fn.of1("chars", x -> (char[]) x));
        def(ns, "doubles", Fn.of1("doubles", x -> (double[]) x));
        def(ns, "floats", Fn.of1("floats", x -> (float[]) x));
        def(ns, "ints", Fn.of1("ints", x -> (int[]) x));
        def(ns, "longs", Fn.of1("longs", x -> (long[]) x));
        def(ns, "shorts", Fn.of1("shorts", x -> (short[]) x));

        def(ns, "float-array", Fn.of12("float-array",
                sOrLen -> {
                    if (sOrLen instanceof Number n) return new float[n.intValue()];
                    Object[] arr = RT.toArray(sOrLen);
                    float[] ret = new float[arr.length];
                    for (int i = 0; i < arr.length; i++) ret[i] = ((Number) arr[i]).floatValue();
                    return ret;
                },
                (size, init) -> {
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
                }));

        def(ns, "short-array", Fn.of12("short-array",
                sOrLen -> {
                    if (sOrLen instanceof Number n) return new short[n.intValue()];
                    Object[] arr = RT.toArray(sOrLen);
                    short[] ret = new short[arr.length];
                    for (int i = 0; i < arr.length; i++) ret[i] = ((Number) arr[i]).shortValue();
                    return ret;
                },
                (size, init) -> {
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
                }));

        def(ns, "make-array", Fn.ofVarargs("make-array", args -> {
            Class<?> cls = (Class<?>) args[0];
            if (args.length == 2) return Array.newInstance(cls, (int) Numbers.toLong(args[1]));
            int[] dims = new int[args.length - 1];
            for (int i = 0; i < dims.length; i++) dims[i] = (int) Numbers.toLong(args[i + 1]);
            return Array.newInstance(cls, dims);
        }));

        def(ns, "into-array", Fn.of12("into-array",
                aseq -> {
                    ISeq s = RT.seq(aseq);
                    Class<?> type = (s != null && s.first() != null) ? s.first().getClass() : Object.class;
                    Object[] vals = RT.toArray(s);
                    Object arr = Array.newInstance(type, vals.length);
                    for (int i = 0; i < vals.length; i++) Array.set(arr, i, vals[i]);
                    return arr;
                },
                (type, aseq) -> {
                    Object[] vals = RT.toArray(aseq);
                    Class<?> c = (Class<?>) type;
                    Object arr = Array.newInstance(c, vals.length);
                    for (int i = 0; i < vals.length; i++) Array.set(arr, i, vals[i]);
                    return arr;
                }));

        def(ns, "to-array-2d", Fn.of1("to-array-2d", coll -> {
            Object[] outer = RT.toArray(coll);
            Object[][] ret = new Object[outer.length][];
            for (int i = 0; i < outer.length; i++) ret[i] = RT.toArray(outer[i]);
            return ret;
        }));

        def(ns, "aset-boolean", Fn.of3("aset-boolean", (arr, idx, v) -> {
            boolean b = RT.truthy(v); ((boolean[]) arr)[(int) Numbers.toLong(idx)] = b; return b;
        }));
        def(ns, "aset-byte", Fn.of3("aset-byte", (arr, idx, v) -> {
            byte b = ((Number) v).byteValue(); ((byte[]) arr)[(int) Numbers.toLong(idx)] = b; return b;
        }));
        def(ns, "aset-char", Fn.of3("aset-char", (arr, idx, v) -> {
            char c = (char) (v instanceof Character ch ? ch : ((Number) v).intValue());
            ((char[]) arr)[(int) Numbers.toLong(idx)] = c; return c;
        }));
        def(ns, "aset-short", Fn.of3("aset-short", (arr, idx, v) -> {
            short s = ((Number) v).shortValue(); ((short[]) arr)[(int) Numbers.toLong(idx)] = s; return s;
        }));
        def(ns, "aset-int", Fn.of3("aset-int", (arr, idx, v) -> {
            int n = ((Number) v).intValue(); ((int[]) arr)[(int) Numbers.toLong(idx)] = n; return n;
        }));
        def(ns, "aset-long", Fn.of3("aset-long", (arr, idx, v) -> {
            long l = Numbers.toLong(v); ((long[]) arr)[(int) Numbers.toLong(idx)] = l; return l;
        }));
        def(ns, "aset-float", Fn.of3("aset-float", (arr, idx, v) -> {
            float f = ((Number) v).floatValue(); ((float[]) arr)[(int) Numbers.toLong(idx)] = f; return f;
        }));
        def(ns, "aset-double", Fn.of3("aset-double", (arr, idx, v) -> {
            double d = ((Number) v).doubleValue(); ((double[]) arr)[(int) Numbers.toLong(idx)] = d; return d;
        }));

        // (amap a idx ret expr)
        defMacro(ns, "amap", Fn.ofVarargs("amap", args -> {
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
        }));

        // (areduce a idx ret init expr)
        defMacro(ns, "areduce", Fn.ofVarargs("areduce", args -> {
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
        }));
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
        def(ns, "comparator", Fn.of1("comparator", pred -> {
            Fn p = (Fn) pred;
            return new Fn.NamedFn("comparator") {
                @Override public int compare(Object a, Object b) {
                    if (RT.truthy(p.invoke(a, b))) return -1;
                    if (RT.truthy(p.invoke(b, a))) return 1;
                    return 0;
                }
                @Override public Object invoke(Object a, Object b) { return (long) compare(a, b); }
            };
        }));

        def(ns, "sorted-map", Fn.ofVarargs("sorted-map", args -> PersistentTreeMap.create(PersistentTreeMap.DEFAULT_COMPARATOR, args)));
        def(ns, "sorted-map-by", Fn.ofVarargs("sorted-map-by", args -> {
            Comparator<Object> comp = asComparator(args[0]);
            PersistentTreeMap m = new PersistentTreeMap(null, comp, null, 0);
            for (int i = 1; i < args.length; i += 2) {
                m = m.assoc(args[i], args[i + 1]);
            }
            return m;
        }));
        def(ns, "sorted-set", Fn.ofVarargs("sorted-set", args -> PersistentTreeSet.create(PersistentTreeMap.DEFAULT_COMPARATOR,
                args.length == 0 ? null : PersistentList.createFromArray(args))));
        def(ns, "sorted-set-by", Fn.ofVarargs("sorted-set-by", args -> {
            Comparator<Object> comp = asComparator(args[0]);
            ISeq items = args.length <= 1 ? null : PersistentList.createFromArraySlice(args, 1);
            return PersistentTreeSet.create(comp, items);
        }));

        def(ns, "subseq", Fn.ofVarargs("subseq", args -> subseqImpl((Sorted) args[0], true, args, 1)));
        def(ns, "rsubseq", Fn.ofVarargs("rsubseq", args -> subseqImpl((Sorted) args[0], false, args, 1)));

        def(ns, "vector-of", Fn.ofVarargs("vector-of", args -> {
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            for (int i = 1; i < args.length; i++) tv = tv.conj(args[i]);
            return tv.persistent();
        }));
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
        def(ns, "not-empty", Fn.of1("not-empty", coll -> RT.seq(coll) != null ? coll : null));
        def(ns, "bounded-count", Fn.of2("bounded-count", (n, coll) -> {
            if (coll instanceof Counted c) return (long) c.count();
            long limit = Numbers.toLong(n);
            long i = 0;
            for (ISeq s = RT.seq(coll); s != null && i < limit; s = s.next()) i++;
            return i;
        }));

        defMacro(ns, "lazy-cat", Fn.ofVarargs("lazy-cat", args -> {
            List<Object> seqs = new ArrayList<>(args.length + 1);
            seqs.add(Symbol.intern("clojure.core", "concat"));
            for (Object a : args) {
                seqs.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "lazy-seq"), a));
            }
            return PersistentList.create(seqs);
        }));

        def(ns, "line-seq", Fn.of1("line-seq", rdr -> {
            BufferedReader br = (rdr instanceof BufferedReader b) ? b : new BufferedReader((java.io.Reader) rdr);
            return lineSeqStep(br);
        }));

        def(ns, "file-seq", Fn.of1("file-seq", dir -> {
            Var treeSeq = RT.CLOJURE_NS.findInternedVar(Symbol.intern("tree-seq"));
            return ((Fn) treeSeq.deref()).invoke(
                    Fn.of1("is-dir?", f -> ((File) f).isDirectory()),
                    Fn.of1("children", f -> {
                        File[] files = ((File) f).listFiles();
                        return files == null ? null : PersistentList.createFromArray((Object[]) files);
                    }),
                    dir);
        }));

        def(ns, "enumeration-seq", Fn.of1("enumeration-seq", e -> {
            Enumeration<?> en = (Enumeration<?>) e;
            return RT.iteratorSeq(new Iterator<>() {
                @Override public boolean hasNext() { return en.hasMoreElements(); }
                @Override public Object next() { return en.nextElement(); }
            });
        }));
        def(ns, "iterator-seq", Fn.of1("iterator-seq", iter -> RT.iteratorSeq((Iterator<?>) iter)));

        def(ns, "xml-seq", Fn.of1("xml-seq", root -> {
            Var treeSeq = RT.CLOJURE_NS.findInternedVar(Symbol.intern("tree-seq"));
            Keyword contentKw = Keyword.intern("content");
            return ((Fn) treeSeq.deref()).invoke(
                    Fn.of1("branch?", x -> !(x instanceof String)),
                    Fn.of1("children", x -> RT.seq(RT.get(x, contentKw))),
                    root);
        }));

        def(ns, "resultset-seq", Fn.of1("resultset-seq", rsObj -> {
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
        }));

        def(ns, "seque", Fn.of12("seque",
                s -> sequeImpl(100, s),
                (n, s) -> sequeImpl((int) Numbers.toLong(n), s)));

        def(ns, "iteration", Fn.ofVarargs("iteration", args -> {
            Fn step = (Fn) args[0];
            Fn somef = RT.truthy(args.length > 1 ? getOpt(args, "somef") : null) ? (Fn) getOpt(args, "somef")
                    : Fn.of1("some?", x -> x != null);
            Fn vf = RT.truthy(getOpt(args, "vf")) ? (Fn) getOpt(args, "vf") : Fn.of1("identity", x -> x);
            Fn kf = RT.truthy(getOpt(args, "kf")) ? (Fn) getOpt(args, "kf") : null;
            Object initk = getOpt(args, "initk");
            return iterationSeq(step, somef, vf, kf, initk, true);
        }));

        def(ns, "update-keys", Fn.of2("update-keys", (m, f) -> {
            if (m == null) return PersistentHashMap.EMPTY;
            Fn fn = (Fn) f;
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                tm = tm.assoc(fn.invoke(e.getKey()), e.getValue());
            }
            return tm.persistent();
        }));

        def(ns, "update-vals", Fn.of2("update-vals", (m, f) -> {
            if (m == null) return PersistentHashMap.EMPTY;
            Fn fn = (Fn) f;
            Associative ret = (m instanceof PersistentTreeMap ptm) ? ptm.empty() : PersistentHashMap.EMPTY;
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                ret = ret.assoc(e.getKey(), fn.invoke(e.getValue()));
            }
            return ret;
        }));

        def(ns, "pcalls", Fn.ofVarargs("pcalls", args -> {
            Var pmapVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("pmap"));
            return ((Fn) pmapVar.deref()).invoke(Fn.of1("call", f -> ((Fn) f).invoke()),
                    PersistentList.createFromArray(args));
        }));

        defMacro(ns, "pvalues", Fn.ofVarargs("pvalues", args -> {
            List<Object> calls = new ArrayList<>(args.length + 1);
            calls.add(Symbol.intern("clojure.core", "pcalls"));
            for (Object expr : args) {
                calls.add(PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, expr));
            }
            return PersistentList.create(calls);
        }));

        def(ns, "test", Fn.of1("test", v -> {
            Var vr = (Var) v;
            Object tf = vr.meta().valAt(Keyword.intern("test"));
            if (tf instanceof Fn f) {
                f.invoke();
                return Keyword.intern("ok");
            }
            return Symbol.intern("no-test");
        }));

        defMacro(ns, "memfn", Fn.ofVarargs("memfn", args -> {
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
        }));

        defMacro(ns, "definline", Fn.ofVarargs("definline", args -> {
            // Define as standard defn so calls work everywhere
            return RT.cons(Symbol.intern("clojure.core", "defn"), PersistentList.createFromArray(args));
        }));

        defMacro(ns, "locking", Fn.ofVarargs("locking", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("locking*"),
                    args[0],
                    PersistentList.create(body));
        }));

        defMacro(ns, "io!", Fn.ofVarargs("io!", args -> {
            String msg = (args.length > 1 && args[0] instanceof String s) ? s : "I/O in transaction";
            int start = (args.length > 1 && args[0] instanceof String) ? 1 : 0;
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = start; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "io!*"),
                    msg,
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        }));
        def(ns, "io!*", Fn.of2("io!*", (msg, fn) -> {
            if (LockingTransaction.isRunning()) throw new IllegalStateException((String) msg);
            return ((Fn) fn).invoke();
        }));
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
        def(ns, "completing", Fn.of12("completing",
                f -> completing((Fn) f, Fn.of1("identity", x -> x)),
                (f, cf) -> completing((Fn) f, (Fn) cf)));

        def(ns, "transduce", Fn.ofVarargs("transduce", args -> {
            Fn xform = (Fn) args[0];
            Fn f = (Fn) args[1];
            Object init = (args.length == 4) ? args[2] : f.invoke();
            Object coll = args[args.length - 1];
            Fn rf = (Fn) xform.invoke(f);
            Object acc = init;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                acc = rf.invoke(acc, s.first());
                if (acc instanceof Reduced r) {
                    acc = r.deref();
                    break;
                }
            }
            return rf.invoke(acc);
        }));

        def(ns, "cat", Fn.of1("cat", rfObj -> {
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
        }));

        def(ns, "halt-when", Fn.of12("halt-when",
                pred -> haltWhen((Fn) pred, null),
                (pred, retf) -> haltWhen((Fn) pred, (Fn) retf)));

        def(ns, "eduction", Fn.ofVarargs("eduction", args -> {
            Fn compFn = (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("comp")).deref();
            Object[] xforms = new Object[args.length - 1];
            System.arraycopy(args, 0, xforms, 0, xforms.length);
            Fn xf = xforms.length == 1 ? (Fn) xforms[0] : (Fn) compFn.applyTo(xforms);
            Object coll = args[args.length - 1];
            return new Eduction(xf, coll);
        }));

        def(ns, "sequence", Fn.ofVarargs("sequence", args -> {
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
        }));

        // Add transducer arities to core sequence functions cleanly
        wrapTransducer1(ns, "map", f -> rf -> {
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("map-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) { return r.invoke(acc, fn.invoke(x)); }
                @Override public Object invokeVarargs(Object[] args) {
                    if (args.length < 2) return super.invokeVarargs(args);
                    Object[] vals = new Object[args.length - 1];
                    System.arraycopy(args, 1, vals, 0, vals.length);
                    return r.invoke(args[0], fn.applyTo(vals));
                }
            };
        });
        wrapTransducer1(ns, "filter", pred -> rf -> {
            Fn p = (Fn) pred; Fn r = (Fn) rf;
            return new Fn.NamedFn("filter-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    return RT.truthy(p.invoke(x)) ? r.invoke(acc, x) : acc;
                }
            };
        });
        wrapTransducer1(ns, "remove", pred -> rf -> {
            Fn p = (Fn) pred; Fn r = (Fn) rf;
            return new Fn.NamedFn("remove-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    return !RT.truthy(p.invoke(x)) ? r.invoke(acc, x) : acc;
                }
            };
        });
        wrapTransducer1(ns, "take", nObj -> rf -> {
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
        });
        wrapTransducer1(ns, "take-while", pred -> rf -> {
            Fn p = (Fn) pred; Fn r = (Fn) rf;
            return new Fn.NamedFn("take-while-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    return RT.truthy(p.invoke(x)) ? r.invoke(acc, x) : new Reduced(acc);
                }
            };
        });
        wrapTransducer1(ns, "take-nth", nObj -> rf -> {
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
        });
        wrapTransducer1(ns, "drop", nObj -> rf -> {
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
        });
        wrapTransducer1(ns, "drop-while", pred -> rf -> {
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
        });
        wrapTransducer1(ns, "keep", f -> rf -> {
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("keep-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    Object v = fn.invoke(x);
                    return v != null ? r.invoke(acc, v) : acc;
                }
            };
        });
        wrapTransducer1(ns, "keep-indexed", f -> rf -> {
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
        });
        wrapTransducer1(ns, "map-indexed", f -> rf -> {
            Fn fn = (Fn) f; Fn r = (Fn) rf;
            return new Fn.NamedFn("map-indexed-xf") {
                long idx = 0;
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) { return r.invoke(acc, fn.invoke(idx++, x)); }
            };
        });
        wrapTransducer1(ns, "mapcat", f -> {
            Fn fn = (Fn) f;
            Fn mapXf = (Fn) ((Fn) ns.findInternedVar(Symbol.intern("map")).deref()).invoke(fn);
            Fn catFn = (Fn) ns.findInternedVar(Symbol.intern("cat")).deref();
            return rf -> mapXf.invoke(catFn.invoke(rf));
        });
        wrapTransducer0(ns, "distinct", () -> rf -> {
            Fn r = (Fn) rf;
            Set<RT.EquivKey> seen = new HashSet<>();
            return new Fn.NamedFn("distinct-xf") {
                @Override public Object invoke() { return r.invoke(); }
                @Override public Object invoke(Object acc) { return r.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    return seen.add(new RT.EquivKey(x)) ? r.invoke(acc, x) : acc;
                }
            };
        });
        wrapTransducer0(ns, "dedupe", () -> rf -> {
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
        });
        wrapTransducer1(ns, "interpose", sep -> rf -> {
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
        });
        wrapTransducer1(ns, "partition-all", nObj -> rf -> {
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
        });
        wrapTransducer1(ns, "partition-by", f -> rf -> {
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
        });
        def(ns, "replace", Fn.of12("replace",
                smap -> Fn.of1("replace-xf", rf -> {
                    Fn r = (Fn) rf;
                    return new Fn.NamedFn("replace-step") {
                        @Override public Object invoke() { return r.invoke(); }
                        @Override public Object invoke(Object acc) { return r.invoke(acc); }
                        @Override public Object invoke(Object acc, Object x) {
                            Object v = RT.get(smap, x, REDUCED_NONE);
                            return r.invoke(acc, v == REDUCED_NONE ? x : v);
                        }
                    };
                }),
                (smap, coll) -> {
                    List<Object> out = new ArrayList<>();
                    for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                        Object x = s.first();
                        Object v = RT.get(smap, x, REDUCED_NONE);
                        out.add(v == REDUCED_NONE ? x : v);
                    }
                    return coll instanceof PersistentVector
                            ? PersistentVector.create(out)
                            : PersistentList.create(out);
                }));

        def(ns, "random-sample", Fn.of12("random-sample",
                prob -> {
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
                },
                (prob, coll) -> {
                    double p = ((Number) prob).doubleValue();
                    Fn pred = Fn.of1("rand-pred", ignored -> ThreadLocalRandom.current().nextDouble() < p);
                    return ((Fn) ns.findInternedVar(Symbol.intern("filter")).deref()).invoke(pred, coll);
                }));
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

    private static final Keyword KW_PARENTS = Keyword.intern("parents");
    private static final Keyword KW_ANCESTORS = Keyword.intern("ancestors");
    private static final Keyword KW_DESCENDANTS = Keyword.intern("descendants");

    public static PersistentHashMap makeHierarchy() {
        return PersistentHashMap.create(
                KW_PARENTS, PersistentHashMap.EMPTY,
                KW_ANCESTORS, PersistentHashMap.EMPTY,
                KW_DESCENDANTS, PersistentHashMap.EMPTY);
    }

    public static boolean isa(Object child, Object parent) {
        return isa(GLOBAL_HIERARCHY.deref(), child, parent);
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
        def(ns, "make-hierarchy", Fn.of0("make-hierarchy", CoreExtras::makeHierarchy));
        def(ns, "derive", Fn.of23("derive",
                (tag, parent) -> GLOBAL_HIERARCHY.alterRoot(
                        Fn.of1("derive-global", h -> deriveHierarchy((PersistentHashMap) h, tag, parent)),
                        Fn.EMPTY_ARGS),
                (h, tag, parent) -> deriveHierarchy((PersistentHashMap) h, tag, parent)));
        def(ns, "underive", Fn.of23("underive",
                (tag, parent) -> GLOBAL_HIERARCHY.alterRoot(
                        Fn.of1("underive-global", h -> underiveHierarchy((PersistentHashMap) h, tag, parent)),
                        Fn.EMPTY_ARGS),
                (h, tag, parent) -> underiveHierarchy((PersistentHashMap) h, tag, parent)));
        def(ns, "parents", Fn.of12("parents",
                tag -> RT.get(RT.get(GLOBAL_HIERARCHY.deref(), KW_PARENTS), tag),
                (h, tag) -> RT.get(RT.get(h, KW_PARENTS), tag)));
        def(ns, "ancestors", Fn.of12("ancestors",
                tag -> RT.get(RT.get(GLOBAL_HIERARCHY.deref(), KW_ANCESTORS), tag),
                (h, tag) -> RT.get(RT.get(h, KW_ANCESTORS), tag)));
        def(ns, "descendants", Fn.of12("descendants",
                tag -> RT.get(RT.get(GLOBAL_HIERARCHY.deref(), KW_DESCENDANTS), tag),
                (h, tag) -> RT.get(RT.get(h, KW_DESCENDANTS), tag)));

        def(ns, "prefer-method", Fn.of3("prefer-method", (mf, x, y) -> ((MultiFn) mf).preferMethod(x, y)));
        def(ns, "prefers", Fn.of1("prefers", mf -> ((MultiFn) mf).prefers()));
        def(ns, "remove-all-methods", Fn.of1("remove-all-methods", mf -> ((MultiFn) mf).removeAllMethods()));
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
        def(ns, "the-ns", Fn.of1("the-ns", x -> {
            Namespace n = resolveNsArg(x);
            if (n == null) throw new RuntimeException("No namespace: " + x + " found");
            return n;
        }));
        def(ns, "ns-interns", Fn.of1("ns-interns", x -> {
            Namespace n = resolveNsArg(x);
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns == n) tm = tm.assoc(e.getKey(), v);
            }
            return tm.persistent();
        }));
        def(ns, "ns-refers", Fn.of1("ns-refers", x -> {
            Namespace n = resolveNsArg(x);
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns != n) tm = tm.assoc(e.getKey(), v);
            }
            return tm.persistent();
        }));
        def(ns, "ns-imports", Fn.of1("ns-imports", x -> {
            Namespace n = resolveNsArg(x);
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                if (e.getValue() instanceof Class<?>) tm = tm.assoc(e.getKey(), e.getValue());
            }
            return tm.persistent();
        }));
        def(ns, "ns-resolve", Fn.of2("ns-resolve", (nsArg, sym) -> {
            Namespace n = resolveNsArg(nsArg);
            Symbol s = (Symbol) sym;
            Var v = Compiler.lookupVar(n, s, false);
            if (v != null) return v;
            return Interop.resolveClass(n, s.toString());
        }));
        def(ns, "ns-unalias", Fn.of2("ns-unalias", (nsArg, alias) -> {
            resolveNsArg(nsArg).removeAlias((Symbol) alias);
            return null;
        }));
        def(ns, "ns-unmap", Fn.of2("ns-unmap", (nsArg, sym) -> {
            resolveNsArg(nsArg).unmap((Symbol) sym);
            return null;
        }));
        def(ns, "intern", Fn.of23("intern",
                (nsArg, sym) -> {
                    Namespace target = resolveNsArg(nsArg);
                    return target.intern((Symbol) sym);
                },
                (nsArg, sym, val) -> {
                    Namespace target = resolveNsArg(nsArg);
                    return target.intern((Symbol) sym).bindRoot(val);
                }));
        def(ns, "requiring-resolve", Fn.of1("requiring-resolve", sym -> {
            Symbol s = (Symbol) sym;
            if (s.ns != null && Namespace.find(s.ns) == null) {
                Core.loadLib(s.ns);
            }
            Var v = Compiler.lookupVar(RT.currentNS(), s, false);
            return v;
        }));
        def(ns, "loaded-libs", Fn.of0("loaded-libs", Core::getLoadedLibs));
        def(ns, "load", Fn.ofVarargs("load", args -> {
            for (Object pathObj : args) {
                String p = pathObj.toString();
                if (p.startsWith("/")) p = p.substring(1);
                String nsName = p.replace('/', '.').replace('_', '-');
                Core.loadLib(nsName);
            }
            return null;
        }));
        def(ns, "load-reader", Fn.of1("load-reader", rdr -> Compiler.load((java.io.Reader) rdr, "<reader>")));
        def(ns, "compile", Fn.of1("compile", lib -> lib));

        def(ns, "alter-meta!", Fn.ofVarargs("alter-meta!", args -> {
            IReference ref = (IReference) args[0];
            Fn f = (Fn) args[1];
            ISeq extra = args.length > 2 ? PersistentList.createFromArraySlice(args, 2) : null;
            return ref.alterMeta(f, extra);
        }));
        def(ns, "reset-meta!", Fn.of2("reset-meta!", (ref, m) -> ((IReference) ref).resetMeta((PersistentHashMap) m)));

        def(ns, "get-thread-bindings", Fn.of0("get-thread-bindings", () -> {
            Map<Var, Object> m = Var.getThreadBindings();
            return m.isEmpty() ? PersistentHashMap.EMPTY : PersistentHashMap.createFromMap(m);
        }));
        def(ns, "thread-bound?", Fn.ofVarargs("thread-bound?", args -> {
            for (Object o : args) {
                if (!((Var) o).isThreadBound()) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));
        def(ns, "bound-fn*", Fn.of1("bound-fn*", fObj -> {
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
        }));
        defMacro(ns, "bound-fn", Fn.ofVarargs("bound-fn", args ->
                PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "bound-fn*"),
                        RT.cons(Symbol.intern("fn"), PersistentList.createFromArray(args)))));

        defMacro(ns, "with-local-vars", Fn.ofVarargs("with-local-vars", args -> {
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
        }));

        def(ns, "with-redefs-fn", Fn.of2("with-redefs-fn", (bindingMap, func) -> {
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
        }));

        defMacro(ns, "with-redefs", Fn.ofVarargs("with-redefs", args -> {
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
        }));
    }

    // ========================================================================
    // Concurrency: Tap, Agents, STM Refs
    // ========================================================================

    private static void initConcurrency(Namespace ns) {
        // Tap
        def(ns, "add-tap", Fn.of1("add-tap", f -> { TAP_SET.add((Fn) f); return null; }));
        def(ns, "remove-tap", Fn.of1("remove-tap", f -> { TAP_SET.remove((Fn) f); return null; }));
        def(ns, "tap>", Fn.of1("tap>", x -> {
            if (TAP_SET.isEmpty()) return Boolean.FALSE;
            TAP_EXECUTOR.execute(() -> {
                for (Fn f : TAP_SET) {
                    try { f.invoke(x); } catch (Throwable ignored) {}
                }
            });
            return Boolean.TRUE;
        }));

        // Agents
        def(ns, "agent", Fn.ofVarargs("agent", args -> {
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
        }));
        def(ns, "send", Fn.ofVarargs("send", args -> {
            Agent a = (Agent) args[0];
            Fn f = (Fn) args[1];
            Object[] extra = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : Fn.EMPTY_ARGS;
            return a.dispatch(f, extra, Agent.pooledExecutor);
        }));
        def(ns, "send-off", Fn.ofVarargs("send-off", args -> {
            Agent a = (Agent) args[0];
            Fn f = (Fn) args[1];
            Object[] extra = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : Fn.EMPTY_ARGS;
            return a.dispatch(f, extra, Agent.soloExecutor);
        }));
        def(ns, "send-via", Fn.ofVarargs("send-via", args -> {
            Executor exec = (Executor) args[0];
            Agent a = (Agent) args[1];
            Fn f = (Fn) args[2];
            Object[] extra = args.length > 3 ? Arrays.copyOfRange(args, 3, args.length) : Fn.EMPTY_ARGS;
            return a.dispatch(f, extra, exec);
        }));
        def(ns, "await", Fn.ofVarargs("await", args -> {
            Agent[] arr = new Agent[args.length];
            for (int i = 0; i < args.length; i++) arr[i] = (Agent) args[i];
            try { Agent.awaitAll(arr); } catch (InterruptedException e) { throw RT.sneakyThrow(e); }
            return null;
        }));
        def(ns, "await-for", Fn.ofVarargs("await-for", args -> {
            long ms = Numbers.toLong(args[0]);
            Agent[] arr = new Agent[args.length - 1];
            for (int i = 0; i < arr.length; i++) arr[i] = (Agent) args[i + 1];
            try { return Agent.awaitForAll(ms, arr); } catch (InterruptedException e) { throw RT.sneakyThrow(e); }
        }));
        def(ns, "agent-error", Fn.of1("agent-error", a -> ((Agent) a).getError()));
        def(ns, "restart-agent", Fn.ofVarargs("restart-agent", args -> {
            Agent a = (Agent) args[0];
            Object newState = args[1];
            boolean clearActions = false;
            for (int i = 2; i + 1 < args.length; i += 2) {
                if (Keyword.intern("clear-actions").equals(args[i])) clearActions = RT.truthy(args[i + 1]);
            }
            return a.restart(newState, clearActions);
        }));
        def(ns, "set-error-handler!", Fn.of2("set-error-handler!", (a, h) -> { ((Agent) a).setErrorHandler((Fn) h); return null; }));
        def(ns, "error-handler", Fn.of1("error-handler", a -> ((Agent) a).getErrorHandler()));
        def(ns, "set-error-mode!", Fn.of2("set-error-mode!", (a, m) -> { ((Agent) a).setErrorMode((Keyword) m); return null; }));
        def(ns, "error-mode", Fn.of1("error-mode", a -> ((Agent) a).getErrorMode()));
        def(ns, "release-pending-sends", Fn.of0("release-pending-sends", () -> (long) Agent.releasePendingSends()));
        def(ns, "shutdown-agents", Fn.of0("shutdown-agents", () -> { Agent.shutdown(); return null; }));
        def(ns, "set-agent-send-executor!", Fn.of1("set-agent-send-executor!", e -> { Agent.pooledExecutor = (ExecutorService) e; return e; }));
        def(ns, "set-agent-send-off-executor!", Fn.of1("set-agent-send-off-executor!", e -> { Agent.soloExecutor = (ExecutorService) e; return e; }));

        // Refs / STM
        def(ns, "ref", Fn.ofVarargs("ref", args -> {
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
        }));
        def(ns, "dosync*", Fn.of1("dosync*", fn -> {
            try { return LockingTransaction.runInTransaction(() -> ((Fn) fn).invoke()); }
            catch (Exception e) { throw RT.sneakyThrow(e); }
        }));
        defMacro(ns, "dosync", Fn.ofVarargs("dosync", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            Collections.addAll(body, args);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "dosync*"),
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        }));
        defMacro(ns, "sync", Fn.ofVarargs("sync", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "dosync*"),
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        }));
        def(ns, "ref-set", Fn.of2("ref-set", (r, v) -> ((Ref) r).set(v)));
        def(ns, "alter", Fn.ofVarargs("alter", args -> {
            Ref r = (Ref) args[0];
            Fn f = (Fn) args[1];
            ISeq extra = args.length > 2 ? PersistentList.createFromArraySlice(args, 2) : null;
            return r.alter(f, extra);
        }));
        def(ns, "commute", Fn.ofVarargs("commute", args -> {
            Ref r = (Ref) args[0];
            Fn f = (Fn) args[1];
            ISeq extra = args.length > 2 ? PersistentList.createFromArraySlice(args, 2) : null;
            return r.commute(f, extra);
        }));
        def(ns, "ensure", Fn.of1("ensure", r -> { ((Ref) r).touch(); return ((Ref) r).deref(); }));
        def(ns, "ref-history-count", Fn.of1("ref-history-count", r -> (long) ((Ref) r).getHistoryCount()));
        def(ns, "ref-min-history", Fn.of12("ref-min-history",
                r -> (long) ((Ref) r).getMinHistory(),
                (r, n) -> ((Ref) r).setMinHistory((int) Numbers.toLong(n))));
        def(ns, "ref-max-history", Fn.of12("ref-max-history",
                r -> (long) ((Ref) r).getMaxHistory(),
                (r, n) -> ((Ref) r).setMaxHistory((int) Numbers.toLong(n))));
    }

    // ========================================================================
    // Protocols, Records, Types, Interfaces, Reify, Proxy
    // ========================================================================

    private static void initProtocolsAndTypes(Namespace ns) {
        def(ns, "satisfies?", Fn.of2("satisfies?", (p, x) -> ((Protocol) p).satisfies(x)));
        def(ns, "extends?", Fn.of2("extends?", (p, c) -> ((Protocol) p).extendsType((Class<?>) c)));
        def(ns, "extenders", Fn.of1("extenders", p -> {
            Collection<Class<?>> ex = ((Protocol) p).extenders();
            return ex.isEmpty() ? null : PersistentList.create(new ArrayList<>(ex));
        }));

        def(ns, "extend", Fn.ofVarargs("extend", args -> {
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
        }));

        // (defprotocol Name doc? (method [this ...] doc?)*)
        def(ns, "register-protocol*", Fn.of2("register-protocol*", (nameSym, doc) ->
                new Protocol((Symbol) nameSym, (String) doc, null)));
        def(ns, "protocol-dispatch*", Fn.of3("protocol-dispatch*", (protoVar, methodKw, argsArr) -> {
            Protocol p = (Protocol) ((Var) protoVar).deref();
            Object[] callArgs = (Object[]) argsArr;
            Fn impl = p.resolveMethod((Keyword) methodKw, callArgs[0]);
            return impl.applyTo(callArgs);
        }));
        defMacro(ns, "defprotocol", Fn.ofVarargs("defprotocol", args -> {
            Symbol pname = (Symbol) args[0];
            int idx = 1;
            String doc = (idx < args.length && args[idx] instanceof String s) ? (String) args[idx++] : null;
            List<Object> forms = new ArrayList<>();
            forms.add(Symbol.intern("do"));
            forms.add(PersistentList.createFromArray(
                    Symbol.intern("def"),
                    pname,
                    PersistentList.createFromArray(
                            Symbol.intern("clojure.core", "register-protocol*"),
                            PersistentList.createFromArray(Symbol.intern("quote"), pname),
                            doc)));
            while (idx < args.length) {
                if (args[idx] instanceof ISeq sigSeq) {
                    Symbol mname = (Symbol) sigSeq.first();
                    List<PersistentVector> arglists = new ArrayList<>();
                    String mdoc = null;
                    for (ISeq s = sigSeq.next(); s != null; s = s.next()) {
                        if (s.first() instanceof PersistentVector v) arglists.add(v);
                        else if (s.first() instanceof String ms) mdoc = ms;
                    }
                    Keyword mk = Keyword.intern(mname.name);
                    List<Object> bodies = new ArrayList<>();
                    for (PersistentVector al : arglists) {
                        bodies.add(PersistentList.createFromArray(al,
                                PersistentList.createFromArray(
                                        Symbol.intern("clojure.core", "protocol-dispatch*"),
                                        PersistentList.createFromArray(Symbol.intern("var"), pname),
                                        mk,
                                        PersistentList.createFromArray(Symbol.intern("clojure.core", "into-array"),
                                                Symbol.intern("Object"), al))));
                    }
                    List<Object> defnForm = new ArrayList<>();
                    defnForm.add(Symbol.intern("clojure.core", "defn"));
                    defnForm.add(mname);
                    if (mdoc != null) defnForm.add(mdoc);
                    defnForm.addAll(bodies);
                    forms.add(PersistentList.create(defnForm));
                }
                idx++;
            }
            forms.add(pname);
            return PersistentList.create(forms);
        }));

        defMacro(ns, "extend-type", Fn.ofVarargs("extend-type", args -> expandExtendType(args)));
        defMacro(ns, "extend-protocol", Fn.ofVarargs("extend-protocol", args -> {
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
        }));

        // (defrecord Name [fields...] specs...)
        def(ns, "register-record*", Fn.ofVarargs("register-record*", args -> {
            Symbol nameSym = (Symbol) args[0];
            String[] fields = toStringArray(args[1]);
            Class<?>[] ifaces = resolveInterfaces(args[2]);
            Map<String, Fn> javaMethods = toMethodMap(args[3]);
            Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[4]);

            Namespace cur = RT.currentNS();
            String binaryName = cur.name.name + "." + nameSym.name;
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
        }));
        defMacro(ns, "defrecord", Fn.ofVarargs("defrecord", args -> expandTypeOrRecord(args, true)));

        def(ns, "register-type*", Fn.ofVarargs("register-type*", args -> {
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
            String binaryName = cur.name.name + "." + nameSym.name;
            Class<?> typeCls = TypeFactory.defineType(binaryName, fields, mutables, ifaces, javaMethods);
            cur.importClass(nameSym, typeCls);
            for (Map.Entry<Protocol, Map<Keyword, Fn>> e : protoImpls.entrySet()) {
                e.getKey().addImpl(typeCls, e.getValue());
            }
            cur.intern("->" + nameSym.name, Fn.ofVarargs("->" + nameSym.name,
                    ctorArgs -> Interop.invokeConstructor(typeCls, ctorArgs)));
            return typeCls;
        }));
        defMacro(ns, "deftype", Fn.ofVarargs("deftype", args -> expandTypeOrRecord(args, false)));

        def(ns, "reify*", Fn.ofVarargs("reify*", args -> {
            Class<?>[] ifaces = resolveInterfaces(args[0]);
            Map<String, Fn> javaMethods = toMethodMap(args[1]);
            Map<Protocol, Map<Keyword, Fn>> protoImpls = toProtoMap(args[2]);
            return TypeFactory.createReify(ifaces, javaMethods, protoImpls, null);
        }));
        defMacro(ns, "reify", Fn.ofVarargs("reify", args -> expandReify(args)));

        def(ns, "definterface*", Fn.of2("definterface*", (nameSym, sigs) -> {
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
        }));
        defMacro(ns, "definterface", Fn.ofVarargs("definterface", args ->
                PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "definterface*"),
                        PersistentList.createFromArray(Symbol.intern("quote"), args[0]),
                        PersistentList.createFromArray(Symbol.intern("quote"),
                                args.length > 1 ? PersistentList.createFromArraySlice(args, 1) : PersistentList.EMPTY))));

        def(ns, "gen-interface", Fn.ofVarargs("gen-interface", args -> {
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
        }));
        def(ns, "gen-class", Fn.ofVarargs("gen-class", args -> null));

        // Proxy family
        def(ns, "get-proxy-class", Fn.ofVarargs("get-proxy-class", args -> {
            Class<?> superCls = null;
            List<Class<?>> ifaces = new ArrayList<>();
            for (Object a : args) {
                Class<?> c = (Class<?>) a;
                if (c.isInterface()) ifaces.add(c);
                else if (superCls == null) superCls = c;
            }
            return TypeFactory.getProxyClass(superCls, ifaces.toArray(new Class<?>[0]));
        }));
        def(ns, "construct-proxy", Fn.ofVarargs("construct-proxy", args ->
                TypeFactory.constructProxy((Class<?>) args[0], Arrays.copyOfRange(args, 1, args.length))));
        def(ns, "init-proxy", Fn.of2("init-proxy", (p, m) -> TypeFactory.initProxy(p, (PersistentHashMap) m)));
        def(ns, "update-proxy", Fn.of2("update-proxy", (p, m) -> TypeFactory.updateProxy(p, (PersistentHashMap) m)));
        def(ns, "proxy-mappings", Fn.of1("proxy-mappings", TypeFactory::proxyMappings));

        defMacro(ns, "proxy", Fn.ofVarargs("proxy", args -> {
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
                fnClauses.add(Symbol.intern("fn"));
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
        }));

        defMacro(ns, "proxy-super", Fn.ofVarargs("proxy-super", args -> {
            Symbol mname = Symbol.intern("__super_" + args[0]);
            List<Object> dot = new ArrayList<>();
            dot.add(Symbol.intern("."));
            dot.add(Symbol.intern("this"));
            dot.add(mname);
            for (int i = 1; i < args.length; i++) dot.add(args[i]);
            return PersistentList.create(dot);
        }));
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
                kvs.add(RT.cons(Symbol.intern("fn"), body));
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
        List<Object> mutFlags = new ArrayList<>();
        Set<String> mutableSet = new HashSet<>();
        Keyword volKw = Keyword.intern("volatile-mutable");
        Keyword unsyncKw = Keyword.intern("unsynchronized-mutable");
        for (int i = 0; i < fieldsVec.count(); i++) {
            Symbol fs = (Symbol) fieldsVec.nth(i);
            fieldNames.add(fs.name);
            PersistentHashMap fm = fs.meta();
            boolean isMut = !isRecord && fm != null
                    && (RT.truthy(fm.valAt(volKw)) || RT.truthy(fm.valAt(unsyncKw)));
            mutFlags.add(isMut);
            if (isMut) mutableSet.add(fs.name);
        }

        List<Object> specs = new ArrayList<>();
        List<Object> javaMapKVs = new ArrayList<>();
        List<Object> protoMapKVs = new ArrayList<>();

        int i = 2;
        while (i < args.length) {
            Object spec = args[i++];
            specs.add(spec);
            Map<String, ISeq> methods = new LinkedHashMap<>();
            while (i < args.length && args[i] instanceof ISeq ms) {
                Symbol mname = (Symbol) ms.first();
                ISeq mbody = wrapMethodWithFields(fieldsVec, ms.next(), isRecord, mutableSet);
                String mn = mname.name;
                javaMapKVs.add(mn);
                javaMapKVs.add(RT.cons(Symbol.intern("fn"), mbody));
                methods.put(mn, RT.cons(Symbol.intern("fn"), mbody));
                i++;
            }
            List<Object> protoM = new ArrayList<>();
            for (Map.Entry<String, ISeq> e : methods.entrySet()) {
                protoM.add(Keyword.intern(e.getKey()));
                protoM.add(e.getValue());
            }
            protoMapKVs.add(spec);
            protoMapKVs.add(RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoM)));
        }

        String regFn = isRecord ? "register-record*" : "register-type*";
        if (isRecord) {
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", regFn),
                    PersistentList.createFromArray(Symbol.intern("quote"), nameSym),
                    PersistentVector.create(fieldNames),
                    PersistentVector.create(specs),
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(javaMapKVs)),
                    RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoMapKVs)));
        }
        return PersistentList.createFromArray(
                Symbol.intern("clojure.core", regFn),
                PersistentList.createFromArray(Symbol.intern("quote"), nameSym),
                PersistentVector.create(fieldNames),
                PersistentVector.create(specs),
                RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(javaMapKVs)),
                RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoMapKVs)),
                PersistentVector.create(mutFlags));
    }

    private static ISeq wrapMethodWithFields(
            PersistentVector fields, ISeq methodRest, boolean isRecord, Set<String> mutableSet) {
        if (methodRest.first() instanceof PersistentVector params) {
            return wrapSingleArity(fields, params, methodRest.next(), isRecord, mutableSet);
        }
        List<Object> clauses = new ArrayList<>();
        for (ISeq s = methodRest; s != null; s = s.next()) {
            ISeq arity = (ISeq) s.first();
            clauses.add(wrapSingleArity(fields, (PersistentVector) arity.first(), arity.next(), isRecord, mutableSet));
        }
        return PersistentList.create(clauses);
    }

    private static ISeq wrapSingleArity(
            PersistentVector fields, PersistentVector params, ISeq bodyForms, boolean isRecord, Set<String> mutableSet) {
        Object thisSym = params.nth(0);
        List<Object> lets = new ArrayList<>();
        for (int i = 0; i < fields.count(); i++) {
            Symbol f = (Symbol) fields.nth(i);
            if (!isRecord && mutableSet.contains(f.name)) continue;
            lets.add(f);
            if (isRecord) {
                lets.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "get"),
                        thisSym, Keyword.intern(f.name)));
            } else {
                lets.add(PersistentList.createFromArray(Symbol.intern("."), thisSym, Symbol.intern(f.name)));
            }
        }
        Set<String> shadowed = new HashSet<>();
        collectBindingSyms(params, shadowed);
        List<Object> body = new ArrayList<>();
        body.add(Symbol.intern("do"));
        for (ISeq s = bodyForms; s != null; s = s.next()) {
            Object b = mutableSet.isEmpty() ? s.first() : rewriteMutableFields(s.first(), thisSym, mutableSet, shadowed);
            body.add(b);
        }
        Object bodyExpr = PersistentList.create(body);
        if (lets.isEmpty()) {
            return PersistentList.createFromArray(params, bodyExpr);
        }
        Object letForm = PersistentList.createFromArray(
                Symbol.intern("let"), PersistentVector.create(lets), bodyExpr);
        return PersistentList.createFromArray(params, letForm);
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

    private static Object expandReify(Object[] args) {
        List<Object> specs = new ArrayList<>();
        List<Object> javaMapKVs = new ArrayList<>();
        List<Object> protoMapKVs = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            Object spec = args[i++];
            specs.add(spec);
            List<Object> protoM = new ArrayList<>();
            while (i < args.length && args[i] instanceof ISeq ms) {
                Symbol mname = (Symbol) ms.first();
                ISeq fnForm = RT.cons(Symbol.intern("fn"), ms.next());
                javaMapKVs.add(mname.name);
                javaMapKVs.add(fnForm);
                protoM.add(Keyword.intern(mname.name));
                protoM.add(fnForm);
                i++;
            }
            protoMapKVs.add(spec);
            protoMapKVs.add(RT.cons(Symbol.intern("clojure.core", "hash-map"), PersistentList.create(protoM)));
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
        List<Class<?>> ifaces = new ArrayList<>();
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
            if (s.first() instanceof Class<?> c && c.isInterface()) ifaces.add(c);
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

    private static Map<Protocol, Map<Keyword, Fn>> toProtoMap(Object mapObj) {
        Map<Protocol, Map<Keyword, Fn>> ret = new HashMap<>();
        if (mapObj instanceof Map<?, ?> mp) {
            for (Map.Entry<?, ?> e : mp.entrySet()) {
                if (e.getKey() instanceof Protocol p && e.getValue() instanceof Map<?, ?> pm) {
                    Map<Keyword, Fn> m = new HashMap<>();
                    for (Map.Entry<?, ?> me : pm.entrySet()) m.put((Keyword) me.getKey(), (Fn) me.getValue());
                    ret.put(p, m);
                }
            }
        }
        return ret;
    }
}
