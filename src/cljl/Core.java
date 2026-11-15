package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct Java-compiled clojure.core standard library.
 * Populates clojure.core without parsing bootstrap Clojure scripts at startup,
 * giving ClojureLite sub-100ms cold startup time.
 */
public final class Core {

    private static volatile boolean initialized = false;
    private static final Set<String> LOADED_LIBS = ConcurrentHashMap.newKeySet();

    private Core() {}

    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        Namespace ns = RT.CLOJURE_NS;

        initPredicates(ns);
        initMath(ns);
        initBitwiseAndCasts(ns);
        initSequences(ns);
        initCollections(ns);
        initStringsAndIO(ns);
        initFunctional(ns);
        initConcurrencyAndVars(ns);
        initNamespacesAndEval(ns);
        initArraysAndExceptions(ns);
        initMacros(ns);
        CoreExtras.init(ns);
    }

    private static void def(Namespace ns, String name, Object val) {
        ns.intern(name, val);
    }

    private static void defMacro(Namespace ns, String name, Fn fn) {
        ns.internMacro(name, fn);
    }

    public static boolean isa(Object child, Object parent) {
        return CoreExtras.isa(child, parent);
    }

    @SuppressWarnings("unchecked")
    private static Comparator<Object> toComparator(Object cmp) {
        if (cmp instanceof Comparator<?> c) return (Comparator<Object>) c;
        return ((Fn) cmp).asComparator();
    }

    // ========================================================================
    // Predicates & Equivalence
    // ========================================================================

    private static void initPredicates(Namespace ns) {
        def(ns, "nil?", Fn.of1("nil?", x -> x == null));
        def(ns, "some?", Fn.of1("some?", x -> x != null));
        def(ns, "true?", Fn.of1("true?", x -> x == Boolean.TRUE));
        def(ns, "false?", Fn.of1("false?", x -> x == Boolean.FALSE));
        def(ns, "boolean?", Fn.of1("boolean?", x -> x instanceof Boolean));
        def(ns, "boolean", Fn.of1("boolean", RT::truthy));
        def(ns, "not", Fn.of1("not", x -> !RT.truthy(x)));
        def(ns, "identical?", Fn.of2("identical?", (a, b) -> a == b));

        def(ns, "=", Fn.ofVarargs("=", args -> {
            if (args.length <= 1) return Boolean.TRUE;
            Object first = args[0];
            for (int i = 1; i < args.length; i++) {
                if (!RT.equiv(first, args[i])) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));

        def(ns, "not=", Fn.ofVarargs("not=", args -> {
            if (args.length <= 1) return Boolean.FALSE;
            Object first = args[0];
            for (int i = 1; i < args.length; i++) {
                if (!RT.equiv(first, args[i])) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }));

        def(ns, "==", Fn.ofVarargs("==", args -> {
            if (args.length <= 1) return Boolean.TRUE;
            for (int i = 0; i < args.length - 1; i++) {
                if (!Numbers.equal(args[i], args[i + 1])) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));

        def(ns, "compare", Fn.of2("compare", (a, b) -> (long) RT.compare(a, b)));
        def(ns, "hash", Fn.of1("hash", x -> (long) RT.hash(x)));

        def(ns, "number?", Fn.of1("number?", Numbers::isNumber));
        def(ns, "integer?", Fn.of1("integer?", Numbers::isInteger));
        def(ns, "int?", Fn.of1("int?", Numbers::isInteger));
        def(ns, "pos-int?", Fn.of1("pos-int?", x -> Numbers.isInteger(x) && Numbers.isPos(x)));
        def(ns, "nat-int?", Fn.of1("nat-int?", x -> Numbers.isInteger(x) && !Numbers.isNeg(x)));
        def(ns, "neg-int?", Fn.of1("neg-int?", x -> Numbers.isInteger(x) && Numbers.isNeg(x)));
        def(ns, "float?", Fn.of1("float?", x -> x instanceof Double || x instanceof Float));
        def(ns, "double?", Fn.of1("double?", x -> x instanceof Double));
        def(ns, "ratio?", Fn.of1("ratio?", x -> x instanceof Ratio));
        def(ns, "decimal?", Fn.of1("decimal?", x -> x instanceof BigDecimal));
        def(ns, "rational?", Fn.of1("rational?", x -> Numbers.isInteger(x) || x instanceof Ratio || x instanceof BigDecimal));

        def(ns, "zero?", Fn.of1("zero?", Numbers::isZero));
        def(ns, "pos?", Fn.of1("pos?", Numbers::isPos));
        def(ns, "neg?", Fn.of1("neg?", Numbers::isNeg));
        def(ns, "even?", Fn.of1("even?", x -> {
            if (!Numbers.isInteger(x)) throw new IllegalArgumentException("Argument must be an integer: " + x);
            return (Numbers.toLong(x) & 1L) == 0L;
        }));
        def(ns, "odd?", Fn.of1("odd?", x -> {
            if (!Numbers.isInteger(x)) throw new IllegalArgumentException("Argument must be an integer: " + x);
            return (Numbers.toLong(x) & 1L) != 0L;
        }));

        def(ns, "string?", Fn.of1("string?", x -> x instanceof String));
        def(ns, "char?", Fn.of1("char?", x -> x instanceof Character));
        def(ns, "symbol?", Fn.of1("symbol?", x -> x instanceof Symbol));
        def(ns, "keyword?", Fn.of1("keyword?", x -> x instanceof Keyword));
        def(ns, "simple-symbol?", Fn.of1("simple-symbol?", x -> x instanceof Symbol s && s.ns == null));
        def(ns, "qualified-symbol?", Fn.of1("qualified-symbol?", x -> x instanceof Symbol s && s.ns != null));
        def(ns, "simple-keyword?", Fn.of1("simple-keyword?", x -> x instanceof Keyword k && k.getNamespace() == null));
        def(ns, "qualified-keyword?", Fn.of1("qualified-keyword?", x -> x instanceof Keyword k && k.getNamespace() != null));
        def(ns, "list?", Fn.of1("list?", x -> x instanceof PersistentList || x instanceof PersistentList.EmptyList));
        def(ns, "vector?", Fn.of1("vector?", x -> x instanceof PersistentVector || x instanceof PersistentVector.SubVec || x instanceof PersistentHashMap.MapEntry));
        def(ns, "map?", Fn.of1("map?", x -> x instanceof PersistentHashMap || x instanceof Map<?, ?>));
        def(ns, "set?", Fn.of1("set?", x -> x instanceof PersistentHashSet || x instanceof Set<?>));
        def(ns, "seq?", Fn.of1("seq?", x -> x instanceof ISeq));
        def(ns, "seqable?", Fn.of1("seqable?", x -> x == null || x instanceof Seqable || x instanceof Iterable<?>
                || x instanceof Map<?, ?> || x instanceof CharSequence || x.getClass().isArray()));
        def(ns, "coll?", Fn.of1("coll?", x -> x instanceof PersistentVector || x instanceof PersistentHashMap
                || x instanceof PersistentHashSet || x instanceof PersistentTreeMap || x instanceof PersistentTreeSet
                || x instanceof PersistentRecord || x instanceof PersistentColl
                || x instanceof ISeq || x instanceof Collection<?>));
        def(ns, "associative?", Fn.of1("associative?", x -> x instanceof Associative));
        def(ns, "sequential?", Fn.of1("sequential?", x -> x instanceof ISeq || x instanceof PersistentVector || x instanceof List<?>));
        def(ns, "counted?", Fn.of1("counted?", x -> x instanceof Counted));
        def(ns, "ifn?", Fn.of1("ifn?", x -> x instanceof Fn));
        def(ns, "fn?", Fn.of1("fn?", x -> x instanceof Fn && !(x instanceof Keyword || x instanceof Symbol
                || x instanceof PersistentVector || x instanceof PersistentHashMap || x instanceof PersistentHashSet
                || x instanceof PersistentTreeMap || x instanceof PersistentTreeSet || x instanceof PersistentColl)));
        def(ns, "var?", Fn.of1("var?", x -> x instanceof Var));
        def(ns, "class?", Fn.of1("class?", x -> x instanceof Class<?>));
        def(ns, "instance?", Fn.of2("instance?", (c, x) -> ((Class<?>) c).isInstance(x)));
        def(ns, "isa?", Fn.of23("isa?", CoreExtras::isa, CoreExtras::isa));
        def(ns, "empty?", Fn.of1("empty?", x -> RT.seq(x) == null));
        def(ns, "reduced?", Fn.of1("reduced?", x -> x instanceof Reduced));
        def(ns, "realized?", Fn.of1("realized?", x -> {
            if (x instanceof LazySeq ls) return ls.isRealized();
            if (x instanceof Atom.Delay d) return d.isRealized();
            if (x instanceof Atom.Promise p) return p.isRealized();
            if (x instanceof Atom.FutureObj f) return f.isDone();
            return true;
        }));
        def(ns, "distinct?", Fn.ofVarargs("distinct?", args -> {
            if (args.length <= 1) return Boolean.TRUE;
            Set<RT.EquivKey> seen = new HashSet<>(args.length * 2);
            for (Object a : args) {
                if (!seen.add(new RT.EquivKey(a))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));
    }

    // ========================================================================
    // Arithmetic & Math
    // ========================================================================

    private static void initMath(Namespace ns) {
        def(ns, "+", new Fn.NamedFn("+") {
            @Override public Object invoke() { return 0L; }
            @Override public Object invoke(Object a) { return a; }
            @Override public Object invoke(Object a, Object b) { return Numbers.add(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return Numbers.add(Numbers.add(a, b), c); }
            @Override public Object invoke(Object a, Object b, Object c, Object d) { return Numbers.add(Numbers.add(Numbers.add(a, b), c), d); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return 0L;
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.add(acc, args[i]);
                return acc;
            }
        });

        def(ns, "+'", new Fn.NamedFn("+'") {
            @Override public Object invoke() { return 0L; }
            @Override public Object invoke(Object a) { return a; }
            @Override public Object invoke(Object a, Object b) { return Numbers.addP(a, b); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return 0L;
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.addP(acc, args[i]);
                return acc;
            }
        });

        def(ns, "-", new Fn.NamedFn("-") {
            @Override public Object invoke(Object a) { return Numbers.minus(a); }
            @Override public Object invoke(Object a, Object b) { return Numbers.minus(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return Numbers.minus(Numbers.minus(a, b), c); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                if (args.length == 1) return Numbers.minus(args[0]);
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.minus(acc, args[i]);
                return acc;
            }
        });

        def(ns, "-'", new Fn.NamedFn("-'") {
            @Override public Object invoke(Object a) { return Numbers.minusP(a); }
            @Override public Object invoke(Object a, Object b) { return Numbers.minusP(a, b); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                if (args.length == 1) return Numbers.minusP(args[0]);
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.minusP(acc, args[i]);
                return acc;
            }
        });

        def(ns, "*", new Fn.NamedFn("*") {
            @Override public Object invoke() { return 1L; }
            @Override public Object invoke(Object a) { return a; }
            @Override public Object invoke(Object a, Object b) { return Numbers.multiply(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return Numbers.multiply(Numbers.multiply(a, b), c); }
            @Override public Object invoke(Object a, Object b, Object c, Object d) { return Numbers.multiply(Numbers.multiply(Numbers.multiply(a, b), c), d); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return 1L;
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.multiply(acc, args[i]);
                return acc;
            }
        });

        def(ns, "*'", new Fn.NamedFn("*'") {
            @Override public Object invoke() { return 1L; }
            @Override public Object invoke(Object a) { return a; }
            @Override public Object invoke(Object a, Object b) { return Numbers.multiplyP(a, b); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return 1L;
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.multiplyP(acc, args[i]);
                return acc;
            }
        });

        def(ns, "/", Fn.ofVarargs("/", args -> {
            if (args.length == 0) throw new IllegalArgumentException("Wrong number of args (0) passed to: /");
            if (args.length == 1) return Numbers.divide(1L, args[0]);
            Object acc = args[0];
            for (int i = 1; i < args.length; i++) acc = Numbers.divide(acc, args[i]);
            return acc;
        }));

        def(ns, "quot", Fn.of2("quot", Numbers::quotient));
        def(ns, "rem", Fn.of2("rem", Numbers::remainder));
        def(ns, "mod", Fn.of2("mod", Numbers::mod));
        def(ns, "inc", Fn.of1("inc", Numbers::inc));
        def(ns, "inc'", Fn.of1("inc'", Numbers::incP));
        def(ns, "dec", Fn.of1("dec", Numbers::dec));
        def(ns, "dec'", Fn.of1("dec'", Numbers::decP));
        def(ns, "abs", Fn.of1("abs", Numbers::abs));

        def(ns, "<", Fn.ofVarargs("<", args -> {
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.lt(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        }));
        def(ns, "<=", Fn.ofVarargs("<=", args -> {
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.lte(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        }));
        def(ns, ">", Fn.ofVarargs(">", args -> {
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.gt(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        }));
        def(ns, ">=", Fn.ofVarargs(">=", args -> {
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.gte(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        }));

        def(ns, "max", Fn.ofVarargs("max", args -> {
            Object ret = args[0];
            for (int i = 1; i < args.length; i++) ret = Numbers.max(ret, args[i]);
            return ret;
        }));
        def(ns, "min", Fn.ofVarargs("min", args -> {
            Object ret = args[0];
            for (int i = 1; i < args.length; i++) ret = Numbers.min(ret, args[i]);
            return ret;
        }));

        def(ns, "rand", Fn.ofVarargs("rand", args -> {
            double r = ThreadLocalRandom.current().nextDouble();
            return args.length == 0 ? r : r * Numbers.toDouble(args[0]);
        }));
        def(ns, "rand-int", Fn.of1("rand-int", n -> {
            int bound = (int) Numbers.toLong(n);
            return bound <= 0 ? 0L : (long) ThreadLocalRandom.current().nextInt(bound);
        }));
        def(ns, "rand-nth", Fn.of1("rand-nth", coll -> {
            int n = RT.count(coll);
            if (n == 0) throw new IllegalArgumentException("Cannot choose rand-nth of empty collection");
            return RT.nth(coll, ThreadLocalRandom.current().nextInt(n));
        }));
    }

    private static void initBitwiseAndCasts(Namespace ns) {
        def(ns, "bit-and", Fn.ofVarargs("bit-and", args -> {
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r &= Numbers.toLong(args[i]);
            return r;
        }));
        def(ns, "bit-or", Fn.ofVarargs("bit-or", args -> {
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r |= Numbers.toLong(args[i]);
            return r;
        }));
        def(ns, "bit-xor", Fn.ofVarargs("bit-xor", args -> {
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r ^= Numbers.toLong(args[i]);
            return r;
        }));
        def(ns, "bit-not", Fn.of1("bit-not", Numbers::bitNot));
        def(ns, "bit-and-not", Fn.ofVarargs("bit-and-not", args -> {
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r &= ~Numbers.toLong(args[i]);
            return r;
        }));
        def(ns, "bit-shift-left", Fn.of2("bit-shift-left", Numbers::bitShiftLeft));
        def(ns, "bit-shift-right", Fn.of2("bit-shift-right", Numbers::bitShiftRight));
        def(ns, "unsigned-bit-shift-right", Fn.of2("unsigned-bit-shift-right", Numbers::unsignedBitShiftRight));
        def(ns, "bit-test", Fn.of2("bit-test", Numbers::bitTest));
        def(ns, "bit-set", Fn.of2("bit-set", Numbers::bitSet));
        def(ns, "bit-clear", Fn.of2("bit-clear", Numbers::bitClear));
        def(ns, "bit-flip", Fn.of2("bit-flip", Numbers::bitFlip));

        def(ns, "int", Fn.of1("int", x -> x instanceof Character c ? (long) c : (long) ((Number) x).intValue()));
        def(ns, "long", Fn.of1("long", x -> x instanceof Character c ? (long) c : Numbers.toLong(x)));
        def(ns, "double", Fn.of1("double", Numbers::toDouble));
        def(ns, "float", Fn.of1("float", x -> (double) ((Number) x).floatValue()));
        def(ns, "bigint", Fn.of1("bigint", Numbers::toBigInteger));
        def(ns, "bigdec", Fn.of1("bigdec", Numbers::toBigDecimal));
        def(ns, "char", Fn.of1("char", x -> x instanceof Character c ? c : (char) Numbers.toLong(x)));
    }

    // ========================================================================
    // Sequence Library
    // ========================================================================

    private static void initSequences(Namespace ns) {
        def(ns, "seq", Fn.of1("seq", RT::seq));
        def(ns, "first", Fn.of1("first", RT::first));
        def(ns, "ffirst", Fn.of1("ffirst", x -> RT.first(RT.first(x))));
        def(ns, "next", Fn.of1("next", RT::next));
        def(ns, "nfirst", Fn.of1("nfirst", x -> RT.next(RT.first(x))));
        def(ns, "fnext", Fn.of1("fnext", x -> RT.first(RT.next(x))));
        def(ns, "nnext", Fn.of1("nnext", x -> RT.next(RT.next(x))));
        def(ns, "rest", Fn.of1("rest", RT::more));
        def(ns, "second", Fn.of1("second", RT::second));
        def(ns, "last", Fn.of1("last", x -> {
            ISeq s = RT.seq(x);
            if (s == null) return null;
            if (s instanceof PersistentVector.VecSeq) {
                // O(1) via seq? Not directly, walk cleanly
            }
            Object ret = s.first();
            while ((s = s.next()) != null) ret = s.first();
            return ret;
        }));
        def(ns, "butlast", Fn.of1("butlast", x -> {
            ISeq s = RT.seq(x);
            if (s == null || s.next() == null) return null;
            List<Object> items = new ArrayList<>();
            while (s.next() != null) {
                items.add(s.first());
                s = s.next();
            }
            return PersistentList.create(items);
        }));

        def(ns, "cons", Fn.of2("cons", RT::cons));
        def(ns, "conj", new Fn.NamedFn("conj") {
            @Override public Object invoke() { return PersistentVector.EMPTY; }
            @Override public Object invoke(Object coll) { return coll; }
            @Override public Object invoke(Object coll, Object x) { return RT.conj(coll, x); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return PersistentVector.EMPTY;
                Object coll = args[0];
                for (int i = 1; i < args.length; i++) coll = RT.conj(coll, args[i]);
                return coll;
            }
        });

        def(ns, "count", Fn.of1("count", o -> (long) RT.count(o)));
        def(ns, "empty", Fn.of1("empty", o -> {
            if (o instanceof PersistentVector) return PersistentVector.EMPTY;
            if (o instanceof PersistentHashMap) return PersistentHashMap.EMPTY;
            if (o instanceof PersistentHashSet) return PersistentHashSet.EMPTY;
            if (o instanceof PersistentTreeMap ptm) return ptm.empty();
            if (o instanceof PersistentTreeSet pts) return pts.empty();
            if (o instanceof PersistentColl pc) return pc.empty();
            if (o instanceof ISeq) return PersistentList.EMPTY;
            if (o instanceof String) return "";
            return null;
        }));
        def(ns, "nth", Fn.of23("nth",
                (c, i) -> RT.nth(c, (int) Numbers.toLong(i)),
                (c, i, nf) -> RT.nth(c, (int) Numbers.toLong(i), nf)));
        def(ns, "nthnext", Fn.of2("nthnext", (coll, n) -> {
            long k = Numbers.toLong(n);
            ISeq s = RT.seq(coll);
            while (s != null && k > 0) {
                s = s.next();
                k--;
            }
            return s;
        }));
        def(ns, "nthrest", Fn.of2("nthrest", (coll, n) -> {
            long k = Numbers.toLong(n);
            ISeq s = RT.seq(coll);
            while (s != null && k > 0) {
                s = s.next();
                k--;
            }
            return s == null ? PersistentList.EMPTY : s;
        }));

        def(ns, "range", Fn.ofVarargs("range", args -> switch (args.length) {
            case 0 -> rangeSeq(0L, Long.MAX_VALUE, 1L);
            case 1 -> rangeSeq(0L, Numbers.toLong(args[0]), 1L);
            case 2 -> rangeSeq(Numbers.toLong(args[0]), Numbers.toLong(args[1]), 1L);
            case 3 -> rangeSeq(Numbers.toLong(args[0]), Numbers.toLong(args[1]), Numbers.toLong(args[2]));
            default -> throw new IllegalArgumentException("Wrong number of args to range");
        }));

        def(ns, "repeat", Fn.of12("repeat",
                x -> repeatInfSeq(x),
                (n, x) -> repeatNSeq(Numbers.toLong(n), x)));

        def(ns, "repeatedly", Fn.of12("repeatedly",
                f -> repeatedlyInfSeq((Fn) f),
                (n, f) -> repeatedlyNSeq(Numbers.toLong(n), (Fn) f)));

        def(ns, "iterate", Fn.of2("iterate", (f, x) -> iterateSeq((Fn) f, x)));
        def(ns, "cycle", Fn.of1("cycle", coll -> {
            ISeq s = RT.seq(coll);
            return s == null ? PersistentList.EMPTY : cycleSeq(s, s);
        }));

        def(ns, "take", Fn.of2("take", (n, coll) -> takeSeq(Numbers.toLong(n), RT.seq(coll))));
        def(ns, "take-while", Fn.of2("take-while", (pred, coll) -> takeWhileSeq((Fn) pred, RT.seq(coll))));
        def(ns, "take-nth", Fn.of2("take-nth", (n, coll) -> {
            long step = Numbers.toLong(n);
            if (step <= 0) return repeatInfSeq(RT.first(coll));
            return takeNthSeq(step, RT.seq(coll));
        }));
        def(ns, "take-last", Fn.of2("take-last", (n, coll) -> {
            int k = (int) Numbers.toLong(n);
            if (k <= 0) return null;
            ArrayDeque<Object> q = new ArrayDeque<>(k);
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (q.size() == k) q.removeFirst();
                q.addLast(s.first());
            }
            return q.isEmpty() ? null : PersistentList.create(new ArrayList<>(q));
        }));

        def(ns, "drop", Fn.of2("drop", (n, coll) -> dropSeq(Numbers.toLong(n), coll)));
        def(ns, "drop-while", Fn.of2("drop-while", (pred, coll) -> dropWhileSeq((Fn) pred, coll)));
        def(ns, "drop-last", Fn.of12("drop-last",
                coll -> dropLastSeq(1, coll),
                (n, coll) -> dropLastSeq((int) Numbers.toLong(n), coll)));

        def(ns, "map", Fn.ofVarargs("map", args -> {
            if (args.length < 2) throw new IllegalArgumentException("map requires at least 2 args");
            Fn f = (Fn) args[0];
            if (args.length == 2) return map1Seq(f, RT.seq(args[1]));
            ISeq[] seqs = new ISeq[args.length - 1];
            for (int i = 0; i < seqs.length; i++) seqs[i] = RT.seq(args[i + 1]);
            return mapNSeq(f, seqs);
        }));

        def(ns, "mapv", Fn.ofVarargs("mapv", args -> {
            if (args.length < 2) throw new IllegalArgumentException("mapv requires at least 2 args");
            Fn f = (Fn) args[0];
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            if (args.length == 2) {
                for (ISeq s = RT.seq(args[1]); s != null; s = s.next()) {
                    tv = tv.conj(f.invoke(s.first()));
                }
                return tv.persistent();
            }
            ISeq[] seqs = new ISeq[args.length - 1];
            for (int i = 0; i < seqs.length; i++) seqs[i] = RT.seq(args[i + 1]);
            Object[] callArgs = new Object[seqs.length];
            while (true) {
                for (int i = 0; i < seqs.length; i++) {
                    if (seqs[i] == null) return tv.persistent();
                    callArgs[i] = seqs[i].first();
                    seqs[i] = seqs[i].next();
                }
                tv = tv.conj(f.applyTo(callArgs));
            }
        }));

        def(ns, "mapcat", Fn.ofVarargs("mapcat", args -> {
            Fn f = (Fn) args[0];
            if (args.length == 2) return concatSeq(map1Seq(f, RT.seq(args[1])));
            ISeq[] seqs = new ISeq[args.length - 1];
            for (int i = 0; i < seqs.length; i++) seqs[i] = RT.seq(args[i + 1]);
            return concatSeq(mapNSeq(f, seqs));
        }));

        def(ns, "map-indexed", Fn.of2("map-indexed", (f, coll) -> mapIndexedSeq((Fn) f, 0L, RT.seq(coll))));

        def(ns, "keep", Fn.of2("keep", (f, coll) -> keepSeq((Fn) f, RT.seq(coll))));
        def(ns, "keep-indexed", Fn.of2("keep-indexed", (f, coll) -> keepIndexedSeq((Fn) f, 0L, RT.seq(coll))));

        def(ns, "filter", Fn.of2("filter", (pred, coll) -> filterSeq((Fn) pred, RT.seq(coll), false)));
        def(ns, "remove", Fn.of2("remove", (pred, coll) -> filterSeq((Fn) pred, RT.seq(coll), true)));
        def(ns, "filterv", Fn.of2("filterv", (pred, coll) -> {
            Fn p = (Fn) pred;
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                Object item = s.first();
                if (RT.truthy(p.invoke(item))) tv = tv.conj(item);
            }
            return tv.persistent();
        }));

        def(ns, "reduce", Fn.of23("reduce",
                (f, coll) -> reduce2((Fn) f, coll),
                (f, val, coll) -> reduce3((Fn) f, val, coll)));

        def(ns, "reduce-kv", Fn.of3("reduce-kv", (f, init, coll) -> {
            Fn fn = (Fn) f;
            if (coll == null) return init;
            if (coll instanceof PersistentHashMap phm) {
                return phm.reduceKV(fn, init);
            }
            if (coll instanceof PersistentVector pv) {
                Object acc = init;
                for (int i = 0; i < pv.cnt; i++) {
                    acc = fn.invoke(acc, (long) i, pv.nth(i));
                    if (acc instanceof Reduced r) return r.deref();
                }
                return acc;
            }
            Object acc = init;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                acc = fn.invoke(acc, e.getKey(), e.getValue());
                if (acc instanceof Reduced r) return r.deref();
            }
            return acc;
        }));

        def(ns, "reductions", Fn.of23("reductions",
                (f, coll) -> {
                    ISeq s = RT.seq(coll);
                    if (s == null) return PersistentList.createFromArray(((Fn) f).invoke());
                    return reductionsSeq((Fn) f, s.first(), s.next());
                },
                (f, init, coll) -> reductionsSeq((Fn) f, init, RT.seq(coll))));

        def(ns, "reduced", Fn.of1("reduced", Reduced::new));
        def(ns, "unreduced", Fn.of1("unreduced", x -> x instanceof Reduced r ? r.deref() : x));
        def(ns, "ensure-reduced", Fn.of1("ensure-reduced", x -> x instanceof Reduced ? x : new Reduced(x)));

        def(ns, "some", Fn.of2("some", (pred, coll) -> {
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                Object res = p.invoke(s.first());
                if (RT.truthy(res)) return res;
            }
            return null;
        }));

        def(ns, "every?", Fn.of2("every?", (pred, coll) -> {
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (!RT.truthy(p.invoke(s.first()))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));

        def(ns, "not-every?", Fn.of2("not-every?", (pred, coll) -> {
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (!RT.truthy(p.invoke(s.first()))) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        }));

        def(ns, "not-any?", Fn.of2("not-any?", (pred, coll) -> {
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (RT.truthy(p.invoke(s.first()))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));

        def(ns, "concat", Fn.ofVarargs("concat", args -> {
            if (args.length == 0) return PersistentList.EMPTY;
            return concatSeq(PersistentList.createFromArray(args));
        }));

        def(ns, "interleave", Fn.ofVarargs("interleave", args -> {
            if (args.length == 0) return PersistentList.EMPTY;
            ISeq[] seqs = new ISeq[args.length];
            for (int i = 0; i < args.length; i++) seqs[i] = RT.seq(args[i]);
            return interleaveSeq(seqs);
        }));

        def(ns, "interpose", Fn.of2("interpose", (sep, coll) -> {
            ISeq s = RT.seq(coll);
            if (s == null) return PersistentList.EMPTY;
            return interposeSeq(sep, s, true);
        }));

        def(ns, "partition", Fn.ofVarargs("partition", args -> switch (args.length) {
            case 2 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[0]), null, RT.seq(args[1]), false);
            case 3 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[1]), null, RT.seq(args[2]), false);
            case 4 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[1]), RT.seq(args[2]), RT.seq(args[3]), false);
            default -> throw new IllegalArgumentException("Wrong number of args to partition");
        }));

        def(ns, "partition-all", Fn.of23("partition-all",
                (n, coll) -> partitionSeq((int) Numbers.toLong(n), (int) Numbers.toLong(n), null, RT.seq(coll), true),
                (n, step, coll) -> partitionSeq((int) Numbers.toLong(n), (int) Numbers.toLong(step), null, RT.seq(coll), true)));

        def(ns, "partition-by", Fn.of2("partition-by", (f, coll) -> partitionBySeq((Fn) f, RT.seq(coll))));

        def(ns, "split-at", Fn.of2("split-at", (n, coll) -> {
            long k = Numbers.toLong(n);
            return PersistentVector.createOwning(takeSeq(k, RT.seq(coll)), dropSeq(k, coll));
        }));

        def(ns, "split-with", Fn.of2("split-with", (pred, coll) ->
                PersistentVector.createOwning(takeWhileSeq((Fn) pred, RT.seq(coll)), dropWhileSeq((Fn) pred, coll))));

        def(ns, "group-by", Fn.of2("group-by", (f, coll) -> {
            Fn fn = (Fn) f;
            Map<RT.EquivKey, PersistentVector.TransientVector> groups = new LinkedHashMap<>();
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                Object item = s.first();
                Object k = fn.invoke(item);
                groups.computeIfAbsent(new RT.EquivKey(k), _k -> PersistentVector.EMPTY.asTransient()).conj(item);
            }
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<RT.EquivKey, PersistentVector.TransientVector> e : groups.entrySet()) {
                ret = ret.assoc(e.getKey().val, e.getValue().persistent());
            }
            return ret.persistent();
        }));

        def(ns, "frequencies", Fn.of1("frequencies", coll -> {
            Map<RT.EquivKey, Long> counts = new HashMap<>();
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                counts.merge(new RT.EquivKey(s.first()), 1L, Long::sum);
            }
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<RT.EquivKey, Long> e : counts.entrySet()) {
                ret = ret.assoc(e.getKey().val, e.getValue());
            }
            return ret.persistent();
        }));

        def(ns, "distinct", Fn.of1("distinct", coll -> distinctSeq(RT.seq(coll), new HashSet<>())));
        def(ns, "dedupe", Fn.of1("dedupe", coll -> dedupeSeq(RT.seq(coll), new Object(), true)));

        def(ns, "flatten", Fn.of1("flatten", x -> {
            List<Object> out = new ArrayList<>();
            flattenInto(x, out);
            return PersistentList.create(out);
        }));

        def(ns, "tree-seq", Fn.of3("tree-seq", (branchP, childrenFn, root) ->
                treeSeqWalk((Fn) branchP, (Fn) childrenFn, root)));

        def(ns, "doall", Fn.of12("doall",
                coll -> { dorun(RT.seq(coll)); return coll; },
                (n, coll) -> { dorun((int) Numbers.toLong(n), RT.seq(coll)); return coll; }));
        def(ns, "dorun", Fn.of12("dorun",
                coll -> { dorun(RT.seq(coll)); return null; },
                (n, coll) -> { dorun((int) Numbers.toLong(n), RT.seq(coll)); return null; }));
        def(ns, "run!", Fn.of2("run!", (proc, coll) -> {
            Fn f = (Fn) proc;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) f.invoke(s.first());
            return null;
        }));
    }

    private static void flattenInto(Object x, List<Object> out) {
        if (x instanceof ISeq || x instanceof PersistentVector || x instanceof List<?>) {
            for (ISeq s = RT.seq(x); s != null; s = s.next()) flattenInto(s.first(), out);
        } else if (x != null) {
            out.add(x);
        }
    }

    private static void dorun(ISeq s) {
        while (s != null) s = s.next();
    }

    private static void dorun(int n, ISeq s) {
        while (s != null && n-- > 0) s = s.next();
    }

    private static ISeq rangeSeq(long start, long end, long step) {
        if ((step > 0 && start >= end) || (step < 0 && start <= end) || step == 0) {
            return PersistentList.EMPTY;
        }
        return new LazySeq(Fn.of0("range", () -> new Cons(start, rangeSeq(start + step, end, step))));
    }

    private static ISeq repeatInfSeq(Object x) {
        return new LazySeq(Fn.of0("repeat", () -> new Cons(x, repeatInfSeq(x))));
    }

    private static ISeq repeatNSeq(long n, Object x) {
        if (n <= 0) return PersistentList.EMPTY;
        return new LazySeq(Fn.of0("repeat", () -> new Cons(x, repeatNSeq(n - 1, x))));
    }

    private static ISeq repeatedlyInfSeq(Fn f) {
        return new LazySeq(Fn.of0("repeatedly", () -> new Cons(f.invoke(), repeatedlyInfSeq(f))));
    }

    private static ISeq repeatedlyNSeq(long n, Fn f) {
        if (n <= 0) return PersistentList.EMPTY;
        return new LazySeq(Fn.of0("repeatedly", () -> new Cons(f.invoke(), repeatedlyNSeq(n - 1, f))));
    }

    private static ISeq iterateSeq(Fn f, Object x) {
        return new LazySeq(Fn.of0("iterate", () -> new Cons(x, iterateSeq(f, f.invoke(x)))));
    }

    private static ISeq cycleSeq(ISeq orig, ISeq cur) {
        return new LazySeq(Fn.of0("cycle", () -> {
            ISeq s = cur.seq();
            if (s == null) s = orig.seq();
            ISeq nxt = s.next();
            return new Cons(s.first(), cycleSeq(orig, nxt != null ? nxt : orig));
        }));
    }

    private static ISeq takeSeq(long n, ISeq s) {
        if (n <= 0 || s == null) return null;
        return new LazySeq(Fn.of0("take", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            return new Cons(sq.first(), takeSeq(n - 1, sq.next()));
        }));
    }

    private static ISeq takeWhileSeq(Fn pred, ISeq s) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("take-while", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            Object v = sq.first();
            if (RT.truthy(pred.invoke(v))) {
                return new Cons(v, takeWhileSeq(pred, sq.next()));
            }
            return null;
        }));
    }

    private static ISeq takeNthSeq(long n, ISeq s) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("take-nth", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            Object head = sq.first();
            ISeq cur = sq;
            for (long i = 0; i < n && cur != null; i++) cur = cur.next();
            return new Cons(head, takeNthSeq(n, cur));
        }));
    }

    private static ISeq dropSeq(long n, Object coll) {
        return new LazySeq(Fn.of0("drop", () -> {
            ISeq s = RT.seq(coll);
            long k = n;
            while (s != null && k > 0) {
                s = s.next();
                k--;
            }
            return s;
        }));
    }

    private static ISeq dropWhileSeq(Fn pred, Object coll) {
        return new LazySeq(Fn.of0("drop-while", () -> {
            ISeq s = RT.seq(coll);
            while (s != null && RT.truthy(pred.invoke(s.first()))) {
                s = s.next();
            }
            return s;
        }));
    }

    private static ISeq dropLastSeq(int n, Object coll) {
        if (n <= 0) return RT.seq(coll);
        List<Object> all = new ArrayList<>();
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) all.add(s.first());
        if (all.size() <= n) return PersistentList.EMPTY;
        return PersistentList.create(all.subList(0, all.size() - n));
    }

    private static ISeq map1Seq(Fn f, ISeq s) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("map", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            return new Cons(f.invoke(sq.first()), map1Seq(f, sq.next()));
        }));
    }

    private static ISeq mapNSeq(Fn f, ISeq[] seqs) {
        return new LazySeq(Fn.of0("map", () -> {
            Object[] args = new Object[seqs.length];
            ISeq[] nexts = new ISeq[seqs.length];
            for (int i = 0; i < seqs.length; i++) {
                ISeq sq = seqs[i] == null ? null : seqs[i].seq();
                if (sq == null) return null;
                args[i] = sq.first();
                nexts[i] = sq.next();
            }
            return new Cons(f.applyTo(args), mapNSeq(f, nexts));
        }));
    }

    private static ISeq mapIndexedSeq(Fn f, long idx, ISeq s) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("map-indexed", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            return new Cons(f.invoke(idx, sq.first()), mapIndexedSeq(f, idx + 1, sq.next()));
        }));
    }

    private static ISeq keepSeq(Fn f, ISeq s) {
        return new LazySeq(Fn.of0("keep", () -> {
            ISeq sq = s;
            while (sq != null) {
                sq = sq.seq();
                if (sq == null) return null;
                Object res = f.invoke(sq.first());
                if (res != null) {
                    return new Cons(res, keepSeq(f, sq.next()));
                }
                sq = sq.next();
            }
            return null;
        }));
    }

    private static ISeq keepIndexedSeq(Fn f, long idx, ISeq s) {
        return new LazySeq(Fn.of0("keep-indexed", () -> {
            ISeq sq = s;
            long i = idx;
            while (sq != null) {
                sq = sq.seq();
                if (sq == null) return null;
                Object res = f.invoke(i, sq.first());
                if (res != null) {
                    return new Cons(res, keepIndexedSeq(f, i + 1, sq.next()));
                }
                sq = sq.next();
                i++;
            }
            return null;
        }));
    }

    private static ISeq filterSeq(Fn pred, ISeq s, boolean invert) {
        return new LazySeq(Fn.of0("filter", () -> {
            ISeq sq = s;
            while (sq != null) {
                sq = sq.seq();
                if (sq == null) return null;
                Object v = sq.first();
                boolean keep = RT.truthy(pred.invoke(v)) ^ invert;
                if (keep) {
                    return new Cons(v, filterSeq(pred, sq.next(), invert));
                }
                sq = sq.next();
            }
            return null;
        }));
    }

    private static Object reduce2(Fn f, Object coll) {
        ISeq s = RT.seq(coll);
        if (s == null) return f.invoke();
        Object acc = s.first();
        s = s.next();
        while (s != null) {
            acc = f.invoke(acc, s.first());
            if (acc instanceof Reduced r) return r.deref();
            s = s.next();
        }
        return acc;
    }

    private static Object reduce3(Fn f, Object init, Object coll) {
        Object acc = init;
        if (coll instanceof PersistentVector pv) {
            for (int i = 0; i < pv.cnt; i++) {
                acc = f.invoke(acc, pv.nth(i));
                if (acc instanceof Reduced r) return r.deref();
            }
            return acc;
        }
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
            acc = f.invoke(acc, s.first());
            if (acc instanceof Reduced r) return r.deref();
        }
        return acc;
    }

    private static ISeq reductionsSeq(Fn f, Object acc, ISeq s) {
        return new LazySeq(Fn.of0("reductions", () -> {
            if (acc instanceof Reduced r) {
                return new PersistentList(r.deref());
            }
            if (s == null) return new PersistentList(acc);
            Object nextAcc = f.invoke(acc, s.first());
            if (nextAcc instanceof Reduced r) {
                return new PersistentList(null, acc, new PersistentList(r.deref()), 2);
            }
            return new Cons(acc, reductionsSeq(f, nextAcc, s.next()));
        }));
    }

    private static ISeq concatSeq(ISeq seqs) {
        return new LazySeq(Fn.of0("concat", () -> {
            ISeq curSeqs = seqs;
            while (curSeqs != null) {
                ISeq inner = RT.seq(curSeqs.first());
                if (inner != null) {
                    return concatChain(inner, curSeqs.next());
                }
                curSeqs = curSeqs.next();
            }
            return null;
        }));
    }

    private static ISeq concatChain(ISeq headSeq, ISeq tailSeqs) {
        return new LazySeq(Fn.of0("concat", () -> {
            ISeq sq = headSeq.seq();
            if (sq == null) return concatSeq(tailSeqs);
            ISeq nxt = sq.next();
            return new Cons(sq.first(),
                    nxt != null ? concatChain(nxt, tailSeqs) : concatSeq(tailSeqs));
        }));
    }

    private static ISeq interleaveSeq(ISeq[] seqs) {
        return new LazySeq(Fn.of0("interleave", () -> {
            List<Object> stepItems = new ArrayList<>(seqs.length);
            ISeq[] nexts = new ISeq[seqs.length];
            for (int i = 0; i < seqs.length; i++) {
                ISeq sq = seqs[i] == null ? null : seqs[i].seq();
                if (sq == null) return null;
                stepItems.add(sq.first());
                nexts[i] = sq.next();
            }
            ISeq tail = interleaveSeq(nexts);
            ISeq res = tail;
            for (int i = stepItems.size() - 1; i >= 0; i--) {
                res = new Cons(stepItems.get(i), res);
            }
            return res;
        }));
    }

    private static ISeq interposeSeq(Object sep, ISeq s, boolean firstElem) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("interpose", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            if (firstElem) {
                return new Cons(sq.first(), interposeSeq(sep, sq.next(), false));
            }
            return new Cons(sep,
                    new Cons(sq.first(), interposeSeq(sep, sq.next(), false)));
        }));
    }

    private static ISeq partitionSeq(int n, int step, ISeq pad, ISeq s, boolean allowPartial) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("partition", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            List<Object> chunk = new ArrayList<>(n);
            ISeq cur = sq;
            for (int i = 0; i < n && cur != null; i++) {
                chunk.add(cur.first());
                cur = cur.next();
            }
            if (chunk.size() < n) {
                if (pad != null) {
                    ISeq p = pad;
                    while (chunk.size() < n && p != null) {
                        chunk.add(p.first());
                        p = p.next();
                    }
                } else if (!allowPartial) {
                    return null;
                }
            }
            ISeq nextStart = sq;
            for (int i = 0; i < step && nextStart != null; i++) nextStart = nextStart.next();
            return new Cons(PersistentList.create(chunk),
                    partitionSeq(n, step, pad, nextStart, allowPartial));
        }));
    }

    private static ISeq partitionBySeq(Fn f, ISeq s) {
        if (s == null) return null;
        return new LazySeq(Fn.of0("partition-by", () -> {
            ISeq sq = s.seq();
            if (sq == null) return null;
            Object firstVal = sq.first();
            Object fv = f.invoke(firstVal);
            List<Object> run = new ArrayList<>();
            run.add(firstVal);
            ISeq cur = sq.next();
            while (cur != null) {
                Object item = cur.first();
                if (!RT.equiv(fv, f.invoke(item))) break;
                run.add(item);
                cur = cur.next();
            }
            return new Cons(PersistentList.create(run), partitionBySeq(f, cur));
        }));
    }

    private static ISeq distinctSeq(ISeq s, Set<RT.EquivKey> seen) {
        return new LazySeq(Fn.of0("distinct", () -> {
            ISeq sq = s;
            while (sq != null) {
                sq = sq.seq();
                if (sq == null) return null;
                Object item = sq.first();
                if (seen.add(new RT.EquivKey(item))) {
                    return new Cons(item, distinctSeq(sq.next(), seen));
                }
                sq = sq.next();
            }
            return null;
        }));
    }

    private static ISeq dedupeSeq(ISeq s, Object prior, boolean isFirst) {
        return new LazySeq(Fn.of0("dedupe", () -> {
            ISeq sq = s;
            Object prev = prior;
            boolean firstFlag = isFirst;
            while (sq != null) {
                sq = sq.seq();
                if (sq == null) return null;
                Object item = sq.first();
                if (firstFlag || !RT.equiv(prev, item)) {
                    return new Cons(item, dedupeSeq(sq.next(), item, false));
                }
                sq = sq.next();
            }
            return null;
        }));
    }

    private static ISeq treeSeqWalk(Fn branchP, Fn childrenFn, Object node) {
        return new LazySeq(Fn.of0("tree-seq", () -> {
            ISeq rest = RT.truthy(branchP.invoke(node))
                    ? treeSeqChildren(branchP, childrenFn, RT.seq(childrenFn.invoke(node)))
                    : null;
            return new Cons(node, rest);
        }));
    }

    private static ISeq treeSeqChildren(Fn branchP, Fn childrenFn, ISeq children) {
        if (children == null) return null;
        return new LazySeq(Fn.of0("tree-seq", () -> {
            ISeq sq = children.seq();
            if (sq == null) return null;
            return concatChain(treeSeqWalk(branchP, childrenFn, sq.first()),
                    PersistentList.createFromArray(treeSeqChildren(branchP, childrenFn, sq.next())));
        }));
    }

    // ========================================================================
    // Collections & Maps
    // ========================================================================

    private static void initCollections(Namespace ns) {
        def(ns, "list", Fn.ofVarargs("list", PersistentList::createFromArray));
        def(ns, "list*", Fn.ofVarargs("list*", args -> {
            if (args.length == 0) return PersistentList.EMPTY;
            if (args.length == 1) return RT.seq(args[0]);
            ISeq tail = RT.seq(args[args.length - 1]);
            for (int i = args.length - 2; i >= 0; i--) {
                tail = RT.cons(args[i], tail);
            }
            return tail;
        }));

        def(ns, "vector", Fn.ofVarargs("vector", PersistentVector::createOwning));
        def(ns, "vec", Fn.of1("vec", coll -> {
            if (coll == null) return PersistentVector.EMPTY;
            if (coll instanceof PersistentVector pv) return pv;
            return PersistentVector.createFromISeq(RT.seq(coll));
        }));

        def(ns, "hash-map", Fn.ofVarargs("hash-map", PersistentHashMap::create));
        def(ns, "array-map", Fn.ofVarargs("array-map", PersistentHashMap::create));
        def(ns, "hash-set", Fn.ofVarargs("hash-set", PersistentHashSet::create));
        def(ns, "set", Fn.of1("set", coll -> {
            if (coll == null) return PersistentHashSet.EMPTY;
            if (coll instanceof PersistentHashSet ps) return ps;
            return PersistentHashSet.createFromISeq(RT.seq(coll));
        }));

        def(ns, "into", Fn.of23("into",
                (to, from) -> intoImpl(to, null, from),
                (to, xform, from) -> intoImpl(to, (Fn) xform, from)));

        def(ns, "get", Fn.of23("get", RT::get, RT::get));
        def(ns, "get-in", Fn.of23("get-in",
                (m, ks) -> getIn(m, ks, null),
                Core::getIn));

        def(ns, "assoc", Fn.ofVarargs("assoc", args -> {
            Object coll = args[0];
            for (int i = 1; i < args.length; i += 2) {
                coll = RT.assoc(coll, args[i], args[i + 1]);
            }
            return coll;
        }));

        def(ns, "assoc-in", Fn.of3("assoc-in", (m, ks, v) -> assocIn(m, RT.seq(ks), v)));

        def(ns, "dissoc", Fn.ofVarargs("dissoc", args -> {
            Object coll = args[0];
            for (int i = 1; i < args.length; i++) coll = RT.dissoc(coll, args[i]);
            return coll;
        }));

        def(ns, "update", Fn.ofVarargs("update", args -> {
            Object m = args[0];
            Object k = args[1];
            Fn f = (Fn) args[2];
            Object cur = RT.get(m, k);
            Object newVal = switch (args.length) {
                case 3 -> f.invoke(cur);
                case 4 -> f.invoke(cur, args[3]);
                case 5 -> f.invoke(cur, args[3], args[4]);
                case 6 -> f.invoke(cur, args[3], args[4], args[5]);
                default -> {
                    Object[] callArgs = new Object[args.length - 2];
                    callArgs[0] = cur;
                    System.arraycopy(args, 3, callArgs, 1, args.length - 3);
                    yield f.applyTo(callArgs);
                }
            };
            return RT.assoc(m, k, newVal);
        }));

        def(ns, "update-in", Fn.ofVarargs("update-in", args -> {
            Object m = args[0];
            ISeq ks = RT.seq(args[1]);
            Fn f = (Fn) args[2];
            Object[] extra = new Object[args.length - 3];
            System.arraycopy(args, 3, extra, 0, extra.length);
            return updateIn(m, ks, f, extra);
        }));

        def(ns, "find", Fn.of2("find", RT::find));
        def(ns, "contains?", Fn.of2("contains?", RT::contains));
        def(ns, "keys", Fn.of1("keys", RT::keys));
        def(ns, "vals", Fn.of1("vals", RT::vals));
        def(ns, "key", Fn.of1("key", e -> ((Map.Entry<?, ?>) e).getKey()));
        def(ns, "val", Fn.of1("val", e -> ((Map.Entry<?, ?>) e).getValue()));

        def(ns, "select-keys", Fn.of2("select-keys", (m, keyseq) -> {
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = RT.seq(keyseq); s != null; s = s.next()) {
                Object k = s.first();
                Object entry = RT.find(m, k);
                if (entry instanceof Map.Entry<?, ?> me) {
                    ret = ret.assoc(me.getKey(), me.getValue());
                }
            }
            return ret.persistent();
        }));

        def(ns, "merge", Fn.ofVarargs("merge", args -> {
            PersistentHashMap ret = PersistentHashMap.EMPTY;
            boolean allNil = true;
            for (Object a : args) {
                if (a != null) {
                    allNil = false;
                    for (ISeq s = RT.seq(a); s != null; s = s.next()) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        ret = ret.assoc(e.getKey(), e.getValue());
                    }
                }
            }
            return allNil ? null : ret;
        }));

        def(ns, "merge-with", Fn.ofVarargs("merge-with", args -> {
            Fn f = (Fn) args[0];
            PersistentHashMap ret = PersistentHashMap.EMPTY;
            boolean allNil = true;
            for (int i = 1; i < args.length; i++) {
                if (args[i] != null) {
                    allNil = false;
                    for (ISeq s = RT.seq(args[i]); s != null; s = s.next()) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        Object k = e.getKey();
                        Object v = e.getValue();
                        if (ret.containsKey(k)) {
                            ret = ret.assoc(k, f.invoke(ret.valAt(k), v));
                        } else {
                            ret = ret.assoc(k, v);
                        }
                    }
                }
            }
            return allNil ? null : ret;
        }));

        def(ns, "zipmap", Fn.of2("zipmap", (keys, vals) -> {
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            ISeq ks = RT.seq(keys);
            ISeq vs = RT.seq(vals);
            while (ks != null && vs != null) {
                ret = ret.assoc(ks.first(), vs.first());
                ks = ks.next();
                vs = vs.next();
            }
            return ret.persistent();
        }));

        def(ns, "peek", Fn.of1("peek", coll -> {
            if (coll == null) return null;
            if (coll instanceof PersistentVector pv) return pv.peek();
            if (coll instanceof PersistentList pl) return pl.peek();
            if (coll instanceof PersistentList.EmptyList) return null;
            if (coll instanceof PersistentColl pc) return pc.peek();
            return RT.first(coll);
        }));

        def(ns, "pop", Fn.of1("pop", coll -> {
            if (coll == null) return null;
            if (coll instanceof PersistentVector pv) return pv.pop();
            if (coll instanceof PersistentList pl) return pl.pop();
            if (coll instanceof PersistentList.EmptyList el) return el.pop();
            if (coll instanceof PersistentColl pc) return pc.pop();
            throw new IllegalArgumentException("pop not supported on: " + coll.getClass().getName());
        }));

        def(ns, "subvec", Fn.of23("subvec",
                (v, start) -> {
                    int s = (int) Numbers.toLong(start);
                    if (v instanceof PersistentVector pv) return pv.subVec(s, pv.cnt);
                    if (v instanceof PersistentVector.SubVec sv) return sv.subVec(s, sv.count());
                    throw new ClassCastException("subvec expects a vector: " + v);
                },
                (v, start, end) -> {
                    int s = (int) Numbers.toLong(start);
                    int e = (int) Numbers.toLong(end);
                    if (v instanceof PersistentVector pv) return pv.subVec(s, e);
                    if (v instanceof PersistentVector.SubVec sv) return sv.subVec(s, e);
                    throw new ClassCastException("subvec expects a vector: " + v);
                }));

        def(ns, "disj", Fn.ofVarargs("disj", args -> {
            Object s = args[0];
            if (s instanceof PersistentTreeSet pts) {
                for (int i = 1; i < args.length; i++) pts = pts.disjoin(args[i]);
                return pts;
            }
            if (s instanceof PersistentColl pc) {
                for (int i = 1; i < args.length; i++) pc = (PersistentColl) pc.without(args[i]);
                return pc;
            }
            PersistentHashSet set = (PersistentHashSet) s;
            for (int i = 1; i < args.length; i++) set = set.disjoin(args[i]);
            return set;
        }));

        def(ns, "reverse", Fn.of1("reverse", coll -> {
            ISeq ret = PersistentList.EMPTY;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                ret = ret.cons(s.first());
            }
            return ret;
        }));

        def(ns, "rseq", Fn.of1("rseq", coll -> {
            if (coll instanceof Reversible rev) return rev.rseq();
            return RT.seq(coll);
        }));

        def(ns, "sort", Fn.of12("sort",
                coll -> {
                    Object[] arr = RT.toArray(coll);
                    Arrays.sort(arr, RT::compare);
                    return arr.length == 0 ? PersistentList.EMPTY : PersistentList.createFromArray(arr);
                },
                (cmp, coll) -> {
                    Object[] arr = RT.toArray(coll);
                    Arrays.sort(arr, toComparator(cmp));
                    return arr.length == 0 ? PersistentList.EMPTY : PersistentList.createFromArray(arr);
                }));

        def(ns, "sort-by", Fn.of23("sort-by",
                (keyfn, coll) -> {
                    Fn kf = (Fn) keyfn;
                    Object[] arr = RT.toArray(coll);
                    Arrays.sort(arr, (a, b) -> RT.compare(kf.invoke(a), kf.invoke(b)));
                    return arr.length == 0 ? PersistentList.EMPTY : PersistentList.createFromArray(arr);
                },
                (keyfn, cmp, coll) -> {
                    Fn kf = (Fn) keyfn;
                    Comparator<Object> c = toComparator(cmp);
                    Object[] arr = RT.toArray(coll);
                    Arrays.sort(arr, (a, b) -> c.compare(kf.invoke(a), kf.invoke(b)));
                    return arr.length == 0 ? PersistentList.EMPTY : PersistentList.createFromArray(arr);
                }));

        def(ns, "shuffle", Fn.of1("shuffle", coll -> {
            List<Object> list = new ArrayList<>(Arrays.asList(RT.toArray(coll)));
            Collections.shuffle(list);
            return PersistentVector.create(list);
        }));

        // Transients
        def(ns, "transient", Fn.of1("transient", coll -> {
            if (coll instanceof PersistentVector pv) return pv.asTransient();
            if (coll instanceof PersistentHashMap pm) return pm.asTransient();
            if (coll instanceof PersistentHashSet ps) return ps.asTransient();
            throw new IllegalArgumentException("Cannot create transient from: " + coll);
        }));
        def(ns, "persistent!", Fn.of1("persistent!", t -> {
            if (t instanceof PersistentVector.TransientVector tv) return tv.persistent();
            if (t instanceof PersistentHashMap.TransientHashMap tm) return tm.persistent();
            if (t instanceof PersistentHashSet.TransientHashSet ts) return ts.persistent();
            throw new IllegalArgumentException("Not a transient: " + t);
        }));
        def(ns, "__destructure-map", Fn.of1("__destructure-map", RT::destructureMap));
        def(ns, "conj!", new Fn.NamedFn("conj!") {
            @Override public Object invoke() { return PersistentVector.EMPTY.asTransient(); }
            @Override public Object invoke(Object t) { return t; }
            @Override public Object invoke(Object t, Object x) {
                if (t instanceof PersistentVector.TransientVector tv) return tv.conj(x);
                if (t instanceof PersistentHashSet.TransientHashSet ts) return ts.conj(x);
                if (t instanceof PersistentHashMap.TransientHashMap tm) {
                    if (x == null) return tm;
                    if (x instanceof Map.Entry<?, ?> e) return tm.assoc(e.getKey(), e.getValue());
                    if (x instanceof PersistentVector v && v.cnt == 2) return tm.assoc(v.nth(0), v.nth(1));
                    if (x instanceof PersistentVector.SubVec sv && sv.count() == 2) return tm.assoc(sv.nth(0), sv.nth(1));
                    if (x instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) tm = tm.assoc(e.getKey(), e.getValue());
                        return tm;
                    }
                    throw new IllegalArgumentException("Cannot conj onto transient map: " + x);
                }
                throw new IllegalArgumentException("Not a transient: " + t);
            }
        });
        def(ns, "assoc!", new Fn.NamedFn("assoc!") {
            private Object assoc1(Object t, Object k, Object v) {
                if (t instanceof PersistentHashMap.TransientHashMap tm) return tm.assoc(k, v);
                if (t instanceof PersistentVector.TransientVector tv) return tv.assocN((int) Numbers.toLong(k), v);
                throw new IllegalArgumentException("Not an associative transient: " + t);
            }
            @Override public Object invoke(Object t, Object k, Object v) {
                return assoc1(t, k, v);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length < 3 || (args.length & 1) == 0) throw arityError(args.length);
                Object res = args[0];
                for (int i = 1; i < args.length; i += 2) {
                    res = assoc1(res, args[i], args[i + 1]);
                }
                return res;
            }
        });
        def(ns, "dissoc!", new Fn.NamedFn("dissoc!") {
            @Override public Object invoke(Object t) { return t; }
            @Override public Object invoke(Object t, Object k) {
                return ((PersistentHashMap.TransientHashMap) t).without(k);
            }
            @Override public Object invoke(Object t, Object k1, Object k2) {
                return ((PersistentHashMap.TransientHashMap) t).without(k1).without(k2);
            }
            @Override public Object invoke(Object t, Object k1, Object k2, Object k3) {
                return ((PersistentHashMap.TransientHashMap) t).without(k1).without(k2).without(k3);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                PersistentHashMap.TransientHashMap tm = (PersistentHashMap.TransientHashMap) args[0];
                for (int i = 1; i < args.length; i++) tm = tm.without(args[i]);
                return tm;
            }
        });
        def(ns, "pop!", Fn.of1("pop!", t -> ((PersistentVector.TransientVector) t).pop()));
        def(ns, "disj!", new Fn.NamedFn("disj!") {
            @Override public Object invoke(Object t) { return t; }
            @Override public Object invoke(Object t, Object k) {
                return ((PersistentHashSet.TransientHashSet) t).disjoin(k);
            }
            @Override public Object invoke(Object t, Object k1, Object k2) {
                return ((PersistentHashSet.TransientHashSet) t).disjoin(k1).disjoin(k2);
            }
            @Override public Object invoke(Object t, Object k1, Object k2, Object k3) {
                return ((PersistentHashSet.TransientHashSet) t).disjoin(k1).disjoin(k2).disjoin(k3);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                PersistentHashSet.TransientHashSet ts = (PersistentHashSet.TransientHashSet) args[0];
                for (int i = 1; i < args.length; i++) ts = ts.disjoin(args[i]);
                return ts;
            }
        });
    }

    private static Object intoImpl(Object to, Fn xform, Object from) {
        if (to == null) to = PersistentList.EMPTY;
        if (xform == null) {
            if (to instanceof PersistentVector pv) {
                PersistentVector.TransientVector tv = pv.asTransient();
                for (ISeq s = RT.seq(from); s != null; s = s.next()) tv = tv.conj(s.first());
                return tv.persistent();
            }
            if (to instanceof PersistentHashSet ps) {
                PersistentHashSet.TransientHashSet ts = ps.asTransient();
                for (ISeq s = RT.seq(from); s != null; s = s.next()) ts = ts.conj(s.first());
                return ts.persistent();
            }
            Object acc = to;
            for (ISeq s = RT.seq(from); s != null; s = s.next()) acc = RT.conj(acc, s.first());
            return acc;
        }
        Fn rf = (Fn) xform.invoke(Fn.of12("conj", x -> x, RT::conj));
        Object acc = to;
        for (ISeq s = RT.seq(from); s != null; s = s.next()) {
            acc = rf.invoke(acc, s.first());
            if (acc instanceof Reduced r) {
                acc = r.deref();
                break;
            }
        }
        return rf.invoke(acc);
    }

    private static Object getIn(Object m, Object ks, Object notFound) {
        Object cur = m;
        for (ISeq s = RT.seq(ks); s != null; s = s.next()) {
            cur = RT.get(cur, s.first(), NOT_FOUND);
            if (cur == NOT_FOUND) return notFound;
        }
        return cur;
    }

    private static final Object NOT_FOUND = new Object();

    private static Object assocIn(Object m, ISeq ks, Object v) {
        if (ks == null) return v;
        Object k = ks.first();
        ISeq rest = ks.next();
        if (rest != null) {
            return RT.assoc(m, k, assocIn(RT.get(m, k), rest, v));
        }
        return RT.assoc(m, k, v);
    }

    private static Object updateIn(Object m, ISeq ks, Fn f, Object[] extra) {
        Object k = ks.first();
        ISeq rest = ks.next();
        if (rest != null) {
            return RT.assoc(m, k, updateIn(RT.get(m, k), rest, f, extra));
        }
        Object cur = RT.get(m, k);
        Object[] all = new Object[1 + extra.length];
        all[0] = cur;
        System.arraycopy(extra, 0, all, 1, extra.length);
        return RT.assoc(m, k, f.applyTo(all));
    }

    // ========================================================================
    // Strings, Symbols, Keywords, Regex, I/O
    // ========================================================================

    private static void initStringsAndIO(Namespace ns) {
        def(ns, "str", new Fn.NamedFn("str") {
            @Override public Object invoke() { return ""; }
            @Override public Object invoke(Object a) { return a == null ? "" : a.toString(); }
            @Override public Object invoke(Object a, Object b) {
                return (a == null ? "" : a.toString()) + (b == null ? "" : b.toString());
            }
            @Override public Object invokeVarargs(Object[] args) {
                StringBuilder sb = new StringBuilder();
                for (Object a : args) {
                    if (a != null) sb.append(a);
                }
                return sb.toString();
            }
        });

        def(ns, "subs", Fn.of23("subs",
                (s, start) -> ((String) s).substring((int) Numbers.toLong(start)),
                (s, start, end) -> ((String) s).substring((int) Numbers.toLong(start), (int) Numbers.toLong(end))));

        def(ns, "format", Fn.ofVarargs("format", args -> {
            String fmt = (String) args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return String.format(fmt, rest);
        }));

        def(ns, "name", Fn.of1("name", RT::name));
        def(ns, "namespace", Fn.of1("namespace", RT::namespace));
        def(ns, "symbol", Fn.of12("symbol",
                s -> s instanceof Symbol sym ? sym : Symbol.intern((String) s),
                (nsName, name) -> Symbol.intern((String) nsName, (String) name)));
        def(ns, "keyword", Fn.of12("keyword",
                k -> {
                    if (k instanceof Keyword kw) return kw;
                    if (k instanceof Symbol s) return Keyword.intern(s);
                    if (k instanceof String str) return Keyword.intern(str);
                    return null;
                },
                (nsName, name) -> Keyword.intern((String) nsName, (String) name)));
        def(ns, "find-keyword", Fn.of12("find-keyword",
                k -> k instanceof Keyword kw ? kw : Keyword.find(null, k.toString()),
                (nsName, name) -> Keyword.find((String) nsName, (String) name)));

        def(ns, "gensym", Fn.ofVarargs("gensym", args -> {
            String prefix = args.length == 0 ? "G__" : args[0].toString();
            return Symbol.intern(prefix + RT.nextID());
        }));

        def(ns, "re-pattern", Fn.of1("re-pattern", s -> s instanceof Pattern p ? p : Pattern.compile((String) s)));
        def(ns, "re-matcher", Fn.of2("re-matcher", (p, s) -> ((Pattern) p).matcher((CharSequence) s)));
        def(ns, "re-groups", Fn.of1("re-groups", m -> {
            Matcher mat = (Matcher) m;
            int gc = mat.groupCount();
            if (gc == 0) return mat.group();
            Object[] ret = new Object[gc + 1];
            for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
            return PersistentVector.createOwning(ret);
        }));
        def(ns, "re-find", Fn.of12("re-find",
                m -> {
                    Matcher mat = (Matcher) m;
                    if (!mat.find()) return null;
                    int gc = mat.groupCount();
                    if (gc == 0) return mat.group();
                    Object[] ret = new Object[gc + 1];
                    for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
                    return PersistentVector.createOwning(ret);
                },
                (p, s) -> {
                    Matcher mat = ((Pattern) p).matcher((CharSequence) s);
                    if (!mat.find()) return null;
                    int gc = mat.groupCount();
                    if (gc == 0) return mat.group();
                    Object[] ret = new Object[gc + 1];
                    for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
                    return PersistentVector.createOwning(ret);
                }));
        def(ns, "re-matches", Fn.of2("re-matches", (p, s) -> {
            Matcher mat = ((Pattern) p).matcher((CharSequence) s);
            if (!mat.matches()) return null;
            int gc = mat.groupCount();
            if (gc == 0) return mat.group();
            Object[] ret = new Object[gc + 1];
            for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
            return PersistentVector.createOwning(ret);
        }));
        def(ns, "re-seq", Fn.of2("re-seq", (p, s) -> {
            Matcher mat = ((Pattern) p).matcher((CharSequence) s);
            List<Object> results = new ArrayList<>();
            int gc = mat.groupCount();
            while (mat.find()) {
                if (gc == 0) {
                    results.add(mat.group());
                } else {
                    Object[] ret = new Object[gc + 1];
                    for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
                    results.add(PersistentVector.createOwning(ret));
                }
            }
            return results.isEmpty() ? null : PersistentList.create(results);
        }));

        // Printing & I/O
        def(ns, "pr-str", Fn.ofVarargs("pr-str", args -> joinPrint(args, true)));
        def(ns, "prn-str", Fn.ofVarargs("prn-str", args -> joinPrint(args, true) + System.lineSeparator()));
        def(ns, "print-str", Fn.ofVarargs("print-str", args -> joinPrint(args, false)));
        def(ns, "println-str", Fn.ofVarargs("println-str", args -> joinPrint(args, false) + System.lineSeparator()));

        def(ns, "pr", Fn.ofVarargs("pr", args -> { writeOut(joinPrint(args, true), false); return null; }));
        def(ns, "prn", Fn.ofVarargs("prn", args -> { writeOut(joinPrint(args, true), true); return null; }));
        def(ns, "print", Fn.ofVarargs("print", args -> { writeOut(joinPrint(args, false), false); return null; }));
        def(ns, "println", Fn.ofVarargs("println", args -> { writeOut(joinPrint(args, false), true); return null; }));
        def(ns, "printf", Fn.ofVarargs("printf", args -> {
            String fmt = (String) args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            writeOut(String.format(fmt, rest), false);
            return null;
        }));
        def(ns, "newline", Fn.of0("newline", () -> { writeOut("", true); return null; }));
        def(ns, "flush", Fn.of0("flush", () -> {
            Object out = RT.OUT.deref();
            if (out instanceof Flushable f) {
                try { f.flush(); } catch (IOException e) { throw RT.sneakyThrow(e); }
            }
            return null;
        }));
        def(ns, "read-line", Fn.of0("read-line", () -> {
            try {
                BufferedReader br = (BufferedReader) RT.IN.deref();
                return br.readLine();
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        def(ns, "read", new Fn.NamedFn("read") {
            @Override
            public Object invoke() {
                return Reader.readFromStream((java.io.Reader) RT.IN.deref(), true, null, null);
            }

            @Override
            public Object invoke(Object stream) {
                return Reader.readFromStream((java.io.Reader) stream, true, null, null);
            }

            @Override
            public Object invoke(Object streamOrOpts, Object streamOrEofError) {
                if (streamOrOpts instanceof Map<?, ?> opts) {
                    return Reader.readFromStream(opts, (java.io.Reader) streamOrEofError);
                }
                return Reader.readFromStream((java.io.Reader) streamOrOpts, RT.truthy(streamOrEofError), null, null);
            }

            @Override
            public Object invoke(Object stream, Object eofErrorP, Object eofValue) {
                return Reader.readFromStream((java.io.Reader) stream, RT.truthy(eofErrorP), eofValue, null);
            }

            @Override
            public Object invoke(Object stream, Object eofErrorP, Object eofValue, Object recursiveP) {
                return Reader.readFromStream((java.io.Reader) stream, RT.truthy(eofErrorP), eofValue, null);
            }
        });
        def(ns, "read-string", Fn.of12("read-string",
                s -> Reader.readString((String) s),
                (opts, s) -> Reader.readString((Map<?, ?>) opts, (String) s)));
        def(ns, "slurp", Fn.ofVarargs("slurp", args -> {
            if (args.length == 0) throw new IllegalArgumentException("Wrong number of args (0) passed to: slurp");
            Charset charset = StandardCharsets.UTF_8;
            Keyword encKw = Keyword.intern("encoding");
            for (int i = 1; i + 1 < args.length; i += 2) {
                if (encKw.equals(args[i]) && args[i + 1] != null) {
                    charset = Charset.forName(args[i + 1].toString());
                }
            }
            return slurpImpl(args[0], charset);
        }));
        def(ns, "spit", Fn.of2("spit", (f, content) -> {
            try {
                Path p = (f instanceof File file) ? file.toPath() : Path.of(f.toString());
                Files.writeString(p, String.valueOf(content), StandardCharsets.UTF_8);
                return null;
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
    }

    private static String slurpImpl(Object f, Charset charset) {
        try {
            if (f instanceof String s) {
                if (s.contains("://") || s.startsWith("file:") || s.startsWith("jar:")) {
                    return readUrl(URI.create(s).toURL(), charset);
                }
                return Files.readString(Path.of(s), charset);
            }
            if (f instanceof Path p) return Files.readString(p, charset);
            if (f instanceof File file) return Files.readString(file.toPath(), charset);
            if (f instanceof URL url) return readUrl(url, charset);
            if (f instanceof URI uri) return readUrl(uri.toURL(), charset);
            if (f instanceof InputStream is) {
                try (InputStream in = is) {
                    return new String(in.readAllBytes(), charset);
                }
            }
            if (f instanceof byte[] bytes) return new String(bytes, charset);
            if (f instanceof java.io.Reader r) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
                return sb.toString();
            }
            return Files.readString(Path.of(f.toString()), charset);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static String readUrl(URL url, Charset charset) throws IOException {
        URL current = url;
        for (int redirects = 0; redirects < 10; redirects++) {
            URLConnection conn = current.openConnection();
            if (conn instanceof HttpURLConnection http) {
                http.setInstanceFollowRedirects(true);
                http.setRequestProperty("User-Agent", "ClojureLite/0.1");
                int status = http.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307 || status == 308) {
                    String location = http.getHeaderField("Location");
                    http.disconnect();
                    if (location == null) break;
                    try {
                        current = current.toURI().resolve(location).toURL();
                    } catch (java.net.URISyntaxException e) {
                        throw new IOException(e);
                    }
                    continue;
                }
                try (InputStream is = status >= 400 ? http.getErrorStream() : http.getInputStream()) {
                    if (is == null) throw new IOException("HTTP " + status + " for " + current);
                    byte[] bytes = is.readAllBytes();
                    if (status >= 400) {
                        throw new IOException("HTTP " + status + " for " + current + ": " + new String(bytes, charset));
                    }
                    return new String(bytes, charset);
                } finally {
                    http.disconnect();
                }
            } else {
                try (InputStream is = conn.getInputStream()) {
                    return new String(is.readAllBytes(), charset);
                }
            }
        }
        throw new IOException("Too many redirects for " + url);
    }

    private static String joinPrint(Object[] args, boolean readably) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            RT.print(args[i], sb, readably);
        }
        return sb.toString();
    }

    private static void writeOut(String s, boolean newline) {
        Object out = RT.OUT.deref();
        if (out instanceof PrintWriter pw) {
            if (newline) pw.println(s);
            else { pw.print(s); pw.flush(); }
        } else if (out instanceof Writer w) {
            try {
                w.write(s);
                if (newline) w.write(System.lineSeparator());
                w.flush();
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }
    }

    // ========================================================================
    // Higher-order Functions
    // ========================================================================

    private static void initFunctional(Namespace ns) {
        def(ns, "identity", Fn.of1("identity", x -> x));
        def(ns, "constantly", Fn.of1("constantly", x -> Fn.ofVarargs("constantly", args -> x)));
        def(ns, "complement", Fn.of1("complement", f -> {
            Fn fn = (Fn) f;
            return Fn.ofVarargs("complement", args -> !RT.truthy(fn.applyTo(args)));
        }));

        def(ns, "apply", Fn.ofVarargs("apply", args -> {
            Fn f = (Fn) args[0];
            if (args.length == 2) return f.applyTo(RT.toArray(args[1]));
            List<Object> list = new ArrayList<>();
            for (int i = 1; i < args.length - 1; i++) list.add(args[i]);
            for (ISeq s = RT.seq(args[args.length - 1]); s != null; s = s.next()) list.add(s.first());
            return f.applyTo(list.toArray());
        }));

        def(ns, "partial", Fn.ofVarargs("partial", pArgs -> {
            Fn f = (Fn) pArgs[0];
            Object[] fixed = new Object[pArgs.length - 1];
            System.arraycopy(pArgs, 1, fixed, 0, fixed.length);
            return Fn.ofVarargs("partial", callArgs -> {
                Object[] all = new Object[fixed.length + callArgs.length];
                System.arraycopy(fixed, 0, all, 0, fixed.length);
                System.arraycopy(callArgs, 0, all, fixed.length, callArgs.length);
                return f.applyTo(all);
            });
        }));

        def(ns, "comp", Fn.ofVarargs("comp", fns -> {
            if (fns.length == 0) return Fn.of1("identity", x -> x);
            if (fns.length == 1) return fns[0];
            return Fn.ofVarargs("comp", args -> {
                Object res = ((Fn) fns[fns.length - 1]).applyTo(args);
                for (int i = fns.length - 2; i >= 0; i--) {
                    res = ((Fn) fns[i]).invoke(res);
                }
                return res;
            });
        }));

        def(ns, "juxt", Fn.ofVarargs("juxt", fns -> Fn.ofVarargs("juxt", args -> {
            Object[] res = new Object[fns.length];
            for (int i = 0; i < fns.length; i++) {
                res[i] = ((Fn) fns[i]).applyTo(args);
            }
            return PersistentVector.createOwning(res);
        })));

        def(ns, "fnil", Fn.ofVarargs("fnil", fArgs -> {
            Fn f = (Fn) fArgs[0];
            Object[] defaults = new Object[fArgs.length - 1];
            System.arraycopy(fArgs, 1, defaults, 0, defaults.length);
            return Fn.ofVarargs("fnil", callArgs -> {
                Object[] patched = callArgs.clone();
                int limit = Math.min(patched.length, defaults.length);
                for (int i = 0; i < limit; i++) {
                    if (patched[i] == null) patched[i] = defaults[i];
                }
                return f.applyTo(patched);
            });
        }));

        def(ns, "memoize", Fn.of1("memoize", f -> {
            Fn fn = (Fn) f;
            ConcurrentHashMap<PersistentVector, Object> cache = new ConcurrentHashMap<>();
            Object NULL_SENTINEL = new Object();
            return Fn.ofVarargs("memoize", args -> {
                PersistentVector key = PersistentVector.createOwning(args);
                Object cached = cache.get(key);
                if (cached != null) return cached == NULL_SENTINEL ? null : cached;
                Object val = fn.applyTo(args);
                cache.putIfAbsent(key, val == null ? NULL_SENTINEL : val);
                return val;
            });
        }));

        def(ns, "trampoline", Fn.ofVarargs("trampoline", args -> {
            Fn f = (Fn) args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            Object ret = f.applyTo(rest);
            while (ret instanceof Fn nextFn && !(ret instanceof Keyword || ret instanceof Symbol
                    || ret instanceof PersistentVector || ret instanceof PersistentHashMap || ret instanceof PersistentHashSet)) {
                ret = nextFn.invoke();
            }
            return ret;
        }));

        def(ns, "every-pred", Fn.ofVarargs("every-pred", preds -> Fn.ofVarargs("every-pred", args -> {
            for (Object p : preds) {
                Fn pred = (Fn) p;
                for (Object a : args) {
                    if (!RT.truthy(pred.invoke(a))) return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        })));

        def(ns, "some-fn", Fn.ofVarargs("some-fn", preds -> Fn.ofVarargs("some-fn", args -> {
            for (Object p : preds) {
                Fn pred = (Fn) p;
                for (Object a : args) {
                    Object r = pred.invoke(a);
                    if (RT.truthy(r)) return r;
                }
            }
            return null;
        })));
    }

    // ========================================================================
    // Concurrency, Atoms, Vars, Metadata
    // ========================================================================

    private static void initConcurrencyAndVars(Namespace ns) {
        def(ns, "atom", Fn.ofVarargs("atom", args -> {
            if (args.length == 1) return new Atom(args[0]);
            PersistentHashMap meta = null;
            Fn validator = null;
            for (int i = 1; i < args.length; i += 2) {
                if (Keyword.intern("meta").equals(args[i])) meta = (PersistentHashMap) args[i + 1];
                else if (Keyword.intern("validator").equals(args[i])) validator = (Fn) args[i + 1];
            }
            Atom a = new Atom(args[0], meta);
            if (validator != null) a.setValidator(validator);
            return a;
        }));

        def(ns, "deref", Fn.ofVarargs("deref", args -> {
            IDeref d = (IDeref) args[0];
            if (args.length == 1) return d.deref();
            return d.deref(Numbers.toLong(args[1]), args[2]);
        }));

        def(ns, "reset!", Fn.of2("reset!", (a, v) -> ((Atom) a).reset(v)));
        def(ns, "reset-vals!", Fn.of2("reset-vals!", (a, v) -> {
            Atom atom = (Atom) a;
            for (;;) {
                Object oldv = atom.deref();
                if (atom.compareAndSet(oldv, v)) return PersistentVector.createOwning(oldv, v);
            }
        }));
        def(ns, "compare-and-set!", Fn.of3("compare-and-set!", (a, o, n) -> ((Atom) a).compareAndSet(o, n)));

        def(ns, "swap!", Fn.ofVarargs("swap!", args -> {
            Atom a = (Atom) args[0];
            Fn f = (Fn) args[1];
            return switch (args.length) {
                case 2 -> a.swap(f);
                case 3 -> a.swap(f, args[2]);
                case 4 -> a.swap(f, args[2], args[3]);
                default -> {
                    Object[] rest = new Object[args.length - 4];
                    System.arraycopy(args, 4, rest, 0, rest.length);
                    yield a.swap(f, args[2], args[3], rest);
                }
            };
        }));

        def(ns, "swap-vals!", Fn.ofVarargs("swap-vals!", args -> {
            Atom a = (Atom) args[0];
            Fn f = (Fn) args[1];
            for (;;) {
                Object oldv = a.deref();
                Object[] callArgs = new Object[args.length - 1];
                callArgs[0] = oldv;
                System.arraycopy(args, 2, callArgs, 1, args.length - 2);
                Object newv = f.applyTo(callArgs);
                if (a.compareAndSet(oldv, newv)) return PersistentVector.createOwning(oldv, newv);
            }
        }));

        def(ns, "volatile!", Fn.of1("volatile!", Atom.Volatile::new));
        def(ns, "vreset!", Fn.of2("vreset!", (v, val) -> ((Atom.Volatile) v).reset(val)));
        def(ns, "vswap!", Fn.ofVarargs("vswap!", args -> {
            Atom.Volatile vol = (Atom.Volatile) args[0];
            Fn f = (Fn) args[1];
            Object[] callArgs = new Object[args.length - 1];
            callArgs[0] = vol.val;
            System.arraycopy(args, 2, callArgs, 1, args.length - 2);
            return vol.reset(f.applyTo(callArgs));
        }));
        def(ns, "volatile?", Fn.of1("volatile?", x -> x instanceof Atom.Volatile));

        def(ns, "delay?", Fn.of1("delay?", x -> x instanceof Atom.Delay));
        def(ns, "force", Fn.of1("force", x -> x instanceof Atom.Delay d ? d.deref() : x));
        def(ns, "promise", Fn.of0("promise", Atom.Promise::new));
        def(ns, "deliver", Fn.of2("deliver", (p, v) -> ((Atom.Promise) p).deliver(v)));

        def(ns, "future-call", Fn.of1("future-call", f -> new Atom.FutureObj((Fn) f)));
        def(ns, "future?", Fn.of1("future?", x -> x instanceof Atom.FutureObj));
        def(ns, "future-done?", Fn.of1("future-done?", x -> ((Atom.FutureObj) x).isDone()));
        def(ns, "future-cancel", Fn.of1("future-cancel", x -> ((Atom.FutureObj) x).cancel(true)));
        def(ns, "future-cancelled?", Fn.of1("future-cancelled?", x -> ((Atom.FutureObj) x).isCancelled()));

        def(ns, "pmap", Fn.ofVarargs("pmap", args -> {
            Fn f = (Fn) args[0];
            ISeq rets;
            if (args.length == 2) {
                rets = pmapFutures1(f, RT.seq(args[1]));
            } else {
                ISeq[] seqs = new ISeq[args.length - 1];
                for (int i = 0; i < seqs.length; i++) seqs[i] = RT.seq(args[i + 1]);
                rets = pmapFuturesN(f, seqs);
            }
            int procs = Runtime.getRuntime().availableProcessors() + 2;
            ISeq ahead = rets;
            for (int i = 0; i < procs && ahead != null; i++) {
                ahead = RT.next(ahead);
            }
            return pmapStep(rets, ahead);
        }));

        def(ns, "add-watch", Fn.of3("add-watch", (ref, key, fn) -> {
            if (ref instanceof Atom a) a.addWatch(key, (Fn) fn);
            else if (ref instanceof Var v) v.addWatch(key, (Fn) fn);
            else if (ref instanceof Agent ag) ag.addWatch(key, (Fn) fn);
            else if (ref instanceof Ref r) r.addWatch(key, (Fn) fn);
            return ref;
        }));
        def(ns, "remove-watch", Fn.of2("remove-watch", (ref, key) -> {
            if (ref instanceof Atom a) a.removeWatch(key);
            else if (ref instanceof Var v) v.removeWatch(key);
            else if (ref instanceof Agent ag) ag.removeWatch(key);
            else if (ref instanceof Ref r) r.removeWatch(key);
            return ref;
        }));
        def(ns, "set-validator!", Fn.of2("set-validator!", (ref, fn) -> {
            if (ref instanceof Atom a) a.setValidator((Fn) fn);
            else if (ref instanceof Agent ag) ag.setValidator((Fn) fn);
            else if (ref instanceof Ref r) r.setValidator((Fn) fn);
            return null;
        }));
        def(ns, "get-validator", Fn.of1("get-validator", ref -> {
            if (ref instanceof Atom a) return a.getValidator();
            if (ref instanceof Agent ag) return ag.getValidator();
            if (ref instanceof Ref r) return r.getValidator();
            return null;
        }));

        // Metadata & Vars
        def(ns, "meta", Fn.of1("meta", x -> x instanceof IMeta m ? m.meta() : null));
        def(ns, "with-meta", Fn.of2("with-meta", (x, m) -> ((IObj) x).withMeta((PersistentHashMap) m)));
        def(ns, "vary-meta", Fn.ofVarargs("vary-meta", args -> {
            IObj obj = (IObj) args[0];
            Fn f = (Fn) args[1];
            Object[] callArgs = new Object[args.length - 1];
            callArgs[0] = obj.meta();
            System.arraycopy(args, 2, callArgs, 1, args.length - 2);
            return obj.withMeta((PersistentHashMap) f.applyTo(callArgs));
        }));

        def(ns, "var-get", Fn.of1("var-get", v -> ((Var) v).deref()));
        def(ns, "var-set", Fn.of2("var-set", (v, val) -> ((Var) v).set(val)));
        def(ns, "alter-var-root", Fn.ofVarargs("alter-var-root", args -> {
            Var v = (Var) args[0];
            Fn f = (Fn) args[1];
            Object[] rest = new Object[args.length - 2];
            System.arraycopy(args, 2, rest, 0, rest.length);
            return v.alterRoot(f, rest);
        }));
        def(ns, "bound?", Fn.ofVarargs("bound?", args -> {
            for (Object o : args) if (!((Var) o).isBound()) return Boolean.FALSE;
            return Boolean.TRUE;
        }));
        def(ns, "find-var", Fn.of1("find-var", sym -> {
            Symbol s = (Symbol) sym;
            Namespace targetNs = Namespace.find(s.ns);
            return targetNs != null ? targetNs.findInternedVar(Symbol.intern(s.name)) : null;
        }));
    }

    // ========================================================================
    // Namespaces, Eval, Loading, Multimethods
    // ========================================================================

    private static void initNamespacesAndEval(Namespace ns) {
        def(ns, "in-ns", Fn.of1("in-ns", name -> {
            Namespace target = Namespace.findOrCreate((Symbol) name);
            RT.CURRENT_NS.bindRoot(target);
            return target;
        }));
        def(ns, "find-ns", Fn.of1("find-ns", name -> Namespace.find((Symbol) name)));
        def(ns, "create-ns", Fn.of1("create-ns", name -> Namespace.findOrCreate((Symbol) name)));
        def(ns, "remove-ns", Fn.of1("remove-ns", name -> Namespace.remove((Symbol) name)));
        def(ns, "all-ns", Fn.of0("all-ns", () -> PersistentList.create(new ArrayList<>(Namespace.all()))));
        def(ns, "ns-name", Fn.of1("ns-name", x -> x instanceof Namespace n ? n.name : Namespace.find((Symbol) x).name));
        def(ns, "ns-map", Fn.of1("ns-map", x -> {
            Namespace target = x instanceof Namespace n ? n : Namespace.find((Symbol) x);
            return PersistentHashMap.createFromMap(target.getMappings());
        }));
        def(ns, "ns-publics", Fn.of1("ns-publics", x -> {
            Namespace target = x instanceof Namespace n ? n : Namespace.find((Symbol) x);
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : target.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns == target && !RT.truthy(v.meta().valAt(Keyword.intern("private")))) {
                    ret = ret.assoc(e.getKey(), v);
                }
            }
            return ret.persistent();
        }));
        def(ns, "ns-aliases", Fn.of1("ns-aliases", x -> {
            Namespace target = x instanceof Namespace n ? n : Namespace.find((Symbol) x);
            return PersistentHashMap.createFromMap(target.getAliases());
        }));
        def(ns, "alias", Fn.of2("alias", (alias, targetNsSym) -> {
            Namespace target = Namespace.find((Symbol) targetNsSym);
            if (target == null) throw new RuntimeException("No namespace: " + targetNsSym);
            RT.currentNS().addAlias((Symbol) alias, target);
            return null;
        }));
        def(ns, "resolve", Fn.of1("resolve", sym -> {
            Symbol s = (Symbol) sym;
            Var v = Compiler.lookupVar(RT.currentNS(), s, false);
            if (v != null) return v;
            return Interop.resolveClass(RT.currentNS(), s.toString());
        }));
        def(ns, "refer", Fn.ofVarargs("refer", args -> {
            Namespace target = Namespace.find((Symbol) args[0]);
            if (target == null) throw new RuntimeException("No namespace: " + args[0]);
            Set<Symbol> exclude = new HashSet<>();
            Set<Symbol> only = null;
            Map<Symbol, Symbol> rename = new HashMap<>();
            for (int i = 1; i + 1 < args.length; i += 2) {
                Object opt = args[i];
                Object val = args[i + 1];
                if (Keyword.intern("exclude").equals(opt)) {
                    for (ISeq s = RT.seq(val); s != null; s = s.next()) exclude.add(((Symbol) s.first()).withMeta(null));
                } else if (Keyword.intern("only").equals(opt)) {
                    only = new HashSet<>();
                    for (ISeq s = RT.seq(val); s != null; s = s.next()) only.add(((Symbol) s.first()).withMeta(null));
                } else if (Keyword.intern("rename").equals(opt)) {
                    for (ISeq s = RT.seq(val); s != null; s = s.next()) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        rename.put(((Symbol) e.getKey()).withMeta(null), ((Symbol) e.getValue()).withMeta(null));
                    }
                }
            }
            referWithFilters(RT.currentNS(), target, exclude, only, rename);
            return null;
        }));
        def(ns, "import", Fn.ofVarargs("import", args -> {
            Namespace cur = RT.currentNS();
            for (Object spec : args) {
                if (spec instanceof Symbol s) {
                    Class<?> c = Interop.findClass(s.name);
                    if (c == null) throw new RuntimeException("Class not found: " + s);
                    cur.importClass(c);
                } else if (spec instanceof ISeq || spec instanceof PersistentVector) {
                    ISeq seq = RT.seq(spec);
                    if (seq != null) {
                        String pkg = seq.first().toString();
                        for (ISeq s = seq.next(); s != null; s = s.next()) {
                            String clsName = pkg + "." + s.first();
                            Class<?> c = Interop.findClass(clsName);
                            if (c == null) throw new RuntimeException("Class not found: " + clsName);
                            cur.importClass(c);
                        }
                    }
                }
            }
            return null;
        }));

        def(ns, "require", Fn.ofVarargs("require", args -> {
            for (Object spec : args) processLibSpec(spec, false);
            return null;
        }));
        def(ns, "use", Fn.ofVarargs("use", args -> {
            for (Object spec : args) processLibSpec(spec, true);
            return null;
        }));

        def(ns, "eval", Fn.of1("eval", Compiler::eval));
        def(ns, "load-string", Fn.of1("load-string", s -> Compiler.load((String) s, "<load-string>")));
        def(ns, "load-file", Fn.of1("load-file", path -> {
            try {
                String src = Files.readString(Path.of(path.toString()), StandardCharsets.UTF_8);
                return Compiler.load(src, path.toString());
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));

        def(ns, "macroexpand-1", Fn.of1("macroexpand-1", Compiler::macroexpand1));
        def(ns, "macroexpand", Fn.of1("macroexpand", Compiler::macroexpand));

        // Multimethods
        def(ns, "methods", Fn.of1("methods", mf -> ((MultiFn) mf).getMethodTable()));
        def(ns, "get-method", Fn.of2("get-method", (mf, val) -> ((MultiFn) mf).getMethod(val)));
        def(ns, "remove-method", Fn.of2("remove-method", (mf, val) -> ((MultiFn) mf).removeMethod(val)));

        Var printDocVar = ns.intern("print-doc", Fn.of1("print-doc", Core::printDocValue));
        printDocVar.setMeta(PersistentHashMap.EMPTY
                .assoc(Keyword.intern("ns"), ns)
                .assoc(Keyword.intern("name"), Symbol.intern("print-doc"))
                .assoc(Keyword.intern("arglists"), PersistentList.createFromArray(
                        PersistentVector.createOwning(Symbol.intern("m"))))
                .assoc(Keyword.intern("doc"), "Prints documentation for a var, namespace, symbol, or metadata map."));
    }

    private static void referWithFilters(
            Namespace dst, Namespace target, Set<Symbol> exclude, Set<Symbol> only, Map<Symbol, Symbol> rename) {
        if (only == null && exclude.isEmpty() && rename.isEmpty()) {
            dst.referAll(target);
            return;
        }
        Keyword privKw = Keyword.intern("private");
        for (Map.Entry<Symbol, Object> e : target.getMappings().entrySet()) {
            if (e.getValue() instanceof Var v && v.ns == target) {
                if (RT.truthy(v.meta().valAt(privKw))) continue;
                Symbol sym = e.getKey().withMeta(null);
                if (only != null && !only.contains(sym)) continue;
                if (exclude.contains(sym)) continue;
                Symbol mapped = rename.getOrDefault(sym, sym);
                if (only != null || rename.containsKey(sym)) {
                    dst.refer(mapped, v);
                } else {
                    dst.getMappings().putIfAbsent(mapped, v);
                }
            }
        }
    }

    private static void processLibSpec(Object spec, boolean isUse) {
        if (spec instanceof Symbol s) {
            loadLib(s.name);
            if (isUse) {
                Namespace target = Namespace.find(s);
                if (target != null) RT.currentNS().referAll(target);
            }
            return;
        }
        if (spec instanceof ISeq prefixSeq) {
            ISeq sq = prefixSeq.seq();
            if (sq == null || !(sq.first() instanceof Symbol prefixSym)) {
                throw new IllegalArgumentException("Invalid prefix lib spec: " + spec);
            }
            String prefix = prefixSym.name;
            for (ISeq s = sq.next(); s != null; s = s.next()) {
                Object child = s.first();
                if (child instanceof Symbol cs) {
                    processLibSpec(Symbol.intern(prefix + "." + cs.name), isUse);
                } else if (child instanceof PersistentVector cv) {
                    Object[] arr = new Object[cv.count()];
                    arr[0] = Symbol.intern(prefix + "." + ((Symbol) cv.nth(0)).name);
                    for (int i = 1; i < cv.count(); i++) arr[i] = cv.nth(i);
                    processLibSpec(PersistentVector.createOwning(arr), isUse);
                } else if (child instanceof ISeq nested && nested.first() instanceof Symbol nsym) {
                    processLibSpec(RT.cons(Symbol.intern(prefix + "." + nsym.name), nested.next()), isUse);
                } else {
                    throw new IllegalArgumentException("Invalid child in prefix lib spec: " + child);
                }
            }
            return;
        }
        if (spec instanceof PersistentVector v) {
            Symbol libSym = (Symbol) v.nth(0);
            boolean reload = false;
            Symbol asAlias = null;
            ISeq referList = null;
            boolean referAll = false;
            Set<Symbol> exclude = new HashSet<>();
            Set<Symbol> only = null;
            Map<Symbol, Symbol> rename = new HashMap<>();

            for (int i = 1; i < v.count(); i++) {
                Object opt = v.nth(i);
                if (Keyword.intern("as").equals(opt)) {
                    asAlias = (Symbol) v.nth(++i);
                } else if (Keyword.intern("reload").equals(opt) || Keyword.intern("reload-all").equals(opt)) {
                    reload = true;
                } else if (Keyword.intern("refer").equals(opt)) {
                    Object r = v.nth(++i);
                    if (Keyword.intern("all").equals(r)) referAll = true;
                    else referList = RT.seq(r);
                } else if (Keyword.intern("exclude").equals(opt)) {
                    for (ISeq s = RT.seq(v.nth(++i)); s != null; s = s.next()) exclude.add(((Symbol) s.first()).withMeta(null));
                } else if (Keyword.intern("only").equals(opt)) {
                    only = new HashSet<>();
                    for (ISeq s = RT.seq(v.nth(++i)); s != null; s = s.next()) only.add(((Symbol) s.first()).withMeta(null));
                } else if (Keyword.intern("rename").equals(opt)) {
                    for (ISeq s = RT.seq(v.nth(++i)); s != null; s = s.next()) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        rename.put(((Symbol) e.getKey()).withMeta(null), ((Symbol) e.getValue()).withMeta(null));
                    }
                }
            }
            if (reload) LOADED_LIBS.remove(libSym.name);
            loadLib(libSym.name);
            Namespace target = Namespace.find(libSym);
            if (target == null) throw new RuntimeException("Namespace not found after require: " + libSym);
            if (asAlias != null) RT.currentNS().addAlias(asAlias, target);
            if (referList != null) {
                for (ISeq s = referList; s != null; s = s.next()) {
                    Symbol sym = ((Symbol) s.first()).withMeta(null);
                    if (exclude.contains(sym)) continue;
                    Var found = target.findInternedVar(sym);
                    if (found != null) RT.currentNS().refer(rename.getOrDefault(sym, sym), found);
                }
            } else if (referAll || isUse) {
                referWithFilters(RT.currentNS(), target, exclude, only, rename);
            }
            return;
        }
        throw new IllegalArgumentException("Invalid require spec: " + spec);
    }

    public static PersistentTreeSet getLoadedLibs() {
        List<Object> syms = new ArrayList<>(LOADED_LIBS.size());
        for (String s : LOADED_LIBS) syms.add(Symbol.intern(s));
        return PersistentTreeSet.create(PersistentTreeMap.DEFAULT_COMPARATOR, PersistentList.create(syms));
    }

    public static void markLibLoaded(String nsName) {
        if (nsName != null) {
            LOADED_LIBS.add(nsName);
        }
    }

    public static void loadLib(String nsName) {
        if ("clojure.core".equals(nsName)) return;
        if (LOADED_LIBS.contains(nsName) && Namespace.find(nsName) != null) return;
        if (StdLib.ensureLoaded(nsName)) {
            LOADED_LIBS.add(nsName);
            return;
        }
        String rel = nsName.replace('.', '/').replace('-', '_');
        String[] exts = {".cljl", ".clj", ".cljc"};
        // Check active classpath/runtime loader first
        for (String ext : exts) {
            try (InputStream is = RT.baseLoader().getResourceAsStream(rel + ext)) {
                if (is != null) {
                    Namespace savedNs = RT.currentNS();
                    try {
                        String src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        Compiler.load(src, rel + ext);
                        LOADED_LIBS.add(nsName);
                        return;
                    } finally {
                        RT.CURRENT_NS.bindRoot(savedNs);
                    }
                }
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }
        // Fallback to CWD / src / lib unless explicit deps.edn / -Scp / -T enforces strict isolation
        if (!(RT.hasCustomRuntimeLoader() && RT.isStrictRuntimeLoader())) {
            for (String prefix : new String[]{"", "src/", "lib/"}) {
                for (String ext : exts) {
                    String cand = prefix + rel + ext;
                    Path p = Path.of(cand);
                    if (Files.isRegularFile(p)) {
                        try {
                            Namespace savedNs = RT.currentNS();
                            try {
                                String src = Files.readString(p, StandardCharsets.UTF_8);
                                Compiler.load(src, cand);
                                LOADED_LIBS.add(nsName);
                                return;
                            } finally {
                                RT.CURRENT_NS.bindRoot(savedNs);
                            }
                        } catch (IOException e) {
                            throw RT.sneakyThrow(e);
                        }
                    }
                }
            }
        }
        if (Namespace.find(nsName) == null) {
            throw new RuntimeException("Could not locate namespace: " + nsName);
        }
    }

    // ========================================================================
    // Arrays, Types, Exceptions
    // ========================================================================

    private static void initArraysAndExceptions(Namespace ns) {
        def(ns, "to-array", Fn.of1("to-array", RT::toArray));
        def(ns, "object-array", Fn.of1("object-array", x ->
                x instanceof Number n ? new Object[n.intValue()] : RT.toArray(x)));
        def(ns, "int-array", Fn.of1("int-array", x -> {
            if (x instanceof Number n) return new int[n.intValue()];
            Object[] arr = RT.toArray(x);
            int[] out = new int[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).intValue();
            return out;
        }));
        def(ns, "long-array", Fn.of1("long-array", x -> {
            if (x instanceof Number n) return new long[n.intValue()];
            Object[] arr = RT.toArray(x);
            long[] out = new long[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).longValue();
            return out;
        }));
        def(ns, "double-array", Fn.of1("double-array", x -> {
            if (x instanceof Number n) return new double[n.intValue()];
            Object[] arr = RT.toArray(x);
            double[] out = new double[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).doubleValue();
            return out;
        }));
        def(ns, "byte-array", Fn.of1("byte-array", x -> {
            if (x instanceof Number n) return new byte[n.intValue()];
            Object[] arr = RT.toArray(x);
            byte[] out = new byte[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).byteValue();
            return out;
        }));
        def(ns, "char-array", Fn.of1("char-array", x -> {
            if (x instanceof Number n) return new char[n.intValue()];
            if (x instanceof String s) return s.toCharArray();
            Object[] arr = RT.toArray(x);
            char[] out = new char[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = (Character) arr[i];
            return out;
        }));
        def(ns, "boolean-array", Fn.of1("boolean-array", x -> {
            if (x instanceof Number n) return new boolean[n.intValue()];
            Object[] arr = RT.toArray(x);
            boolean[] out = new boolean[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = RT.truthy(arr[i]);
            return out;
        }));
        def(ns, "alength", Fn.of1("alength", a -> (long) Array.getLength(a)));
        def(ns, "aget", Fn.of2("aget", (a, idx) -> Interop.boxResult(Array.get(a, (int) Numbers.toLong(idx)))));
        def(ns, "aset", Fn.of3("aset", (a, idx, val) -> {
            Class<?> comp = a.getClass().getComponentType();
            Array.set(a, (int) Numbers.toLong(idx), Interop.coerceArg(comp, val));
            return val;
        }));
        def(ns, "aclone", Fn.of1("aclone", a -> {
            int len = Array.getLength(a);
            Object copy = Array.newInstance(a.getClass().getComponentType(), len);
            System.arraycopy(a, 0, copy, 0, len);
            return copy;
        }));

        def(ns, "class", Fn.of1("class", x -> x == null ? null : x.getClass()));
        def(ns, "type", Fn.of1("type", x -> {
            if (x instanceof IMeta m && m.meta() != null) {
                Object t = m.meta().valAt(Keyword.intern("type"));
                if (t != null) return t;
            }
            return x == null ? null : x.getClass();
        }));
        def(ns, "ex-info", Fn.of23("ex-info",
                (msg, data) -> new ExceptionInfo((String) msg, (PersistentHashMap) data, null),
                (msg, data, cause) -> new ExceptionInfo((String) msg, (PersistentHashMap) data, (Throwable) cause)));
        def(ns, "ex-data", Fn.of1("ex-data", ex -> ex instanceof ExceptionInfo ei ? ei.data : null));
        def(ns, "ex-message", Fn.of1("ex-message", ex -> ((Throwable) ex).getMessage()));
        def(ns, "ex-cause", Fn.of1("ex-cause", ex -> ((Throwable) ex).getCause()));
    }

    public static final class ExceptionInfo extends RuntimeException {
        public final PersistentHashMap data;

        public ExceptionInfo(String msg, PersistentHashMap data, Throwable cause) {
            super(msg, cause);
            this.data = data != null ? data : PersistentHashMap.EMPTY;
        }
    }

    // ========================================================================
    // Core Macros (Compiled directly in Java for instant startup)
    // ========================================================================

    private static void initMacros(Namespace ns) {
        Symbol fnStar = Symbol.intern("fn*");
        Symbol defSym = Symbol.intern("def");
        Symbol letStar = Symbol.intern("let*");
        Symbol loopStar = Symbol.intern("loop*");
        Symbol ifSym = Symbol.intern("if");
        Symbol doSym = Symbol.intern("do");
        Symbol quoteSym = Symbol.intern("quote");

        // (defn name doc? attr-map? [params] body...)
        defMacro(ns, "defn", Fn.ofVarargs("defn", args -> {
            Symbol name = (Symbol) args[0];
            int idx = 1;
            PersistentHashMap meta = name.meta() != null ? name.meta() : PersistentHashMap.EMPTY;
            if (idx < args.length && args[idx] instanceof String doc) {
                meta = meta.assoc(Keyword.intern("doc"), doc);
                idx++;
            }
            if (idx < args.length && args[idx] instanceof PersistentHashMap attr) {
                for (ISeq s = attr.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                    meta = meta.assoc(me.key, me.val);
                }
                idx++;
            }
            int endIdx = args.length;
            if (idx < endIdx && !(args[idx] instanceof PersistentVector)) {
                if (args[endIdx - 1] instanceof PersistentHashMap tailAttr) {
                    for (ISeq s = tailAttr.seq(); s != null; s = s.next()) {
                        PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                        meta = meta.assoc(me.key, me.val);
                    }
                    endIdx--;
                }
                List<Object> arglists = new ArrayList<>();
                for (int i = idx; i < endIdx; i++) {
                    if (args[i] instanceof ISeq clause && clause.first() != null) {
                        arglists.add(clause.first());
                    }
                }
                if (!arglists.isEmpty()) {
                    meta = meta.assoc(Keyword.intern("arglists"), PersistentList.create(arglists));
                }
            } else if (idx < endIdx && args[idx] instanceof PersistentVector paramsVec) {
                meta = meta.assoc(Keyword.intern("arglists"), PersistentList.createFromArray(paramsVec));
            }
            Symbol namedSym = meta.count() > 0 ? name.withMeta(meta) : name;
            List<Object> fnArgs = new ArrayList<>();
            fnArgs.add(fnStar);
            fnArgs.add(name);
            for (int i = idx; i < endIdx; i++) fnArgs.add(args[i]);
            return PersistentList.createFromArray(defSym, namedSym, PersistentList.create(fnArgs));
        }));

        Var docVar = ns.internMacro("doc", Fn.ofVarargs("doc", args -> {
            if (args.length != 1) {
                throw new IllegalArgumentException("doc requires 1 argument");
            }
            Object target = args[0];
            Object argExpr = (target instanceof ISeq s
                    && (Symbol.intern("var").equals(s.first()) || quoteSym.equals(s.first())))
                    ? target
                    : PersistentList.createFromArray(quoteSym, target);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "print-doc"), argExpr);
        }));
        docVar.setMeta(docVar.meta()
                .assoc(Keyword.intern("ns"), ns)
                .assoc(Keyword.intern("name"), Symbol.intern("doc"))
                .assoc(Keyword.intern("arglists"), PersistentList.createFromArray(
                        PersistentVector.createOwning(Symbol.intern("name"))))
                .assoc(Keyword.intern("doc"), "Prints documentation for a var or special form given its name"));

        defMacro(ns, "defn-", Fn.ofVarargs("defn-", args -> {
            Symbol name = (Symbol) args[0];
            PersistentHashMap meta = (name.meta() != null ? name.meta() : PersistentHashMap.EMPTY)
                    .assoc(Keyword.intern("private"), Boolean.TRUE);
            Object[] patched = args.clone();
            patched[0] = name.withMeta(meta);
            return ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("defn")).deref()).applyTo(patched);
        }));

        defMacro(ns, "fn", Fn.ofVarargs("fn", args -> {
            List<Object> list = new ArrayList<>(args.length + 1);
            list.add(fnStar);
            Collections.addAll(list, args);
            return PersistentList.create(list);
        }));

        defMacro(ns, "defonce", Fn.of2("defonce", (name, expr) ->
                PersistentList.createFromArray(
                        Symbol.intern("when-not"),
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "bound?"),
                                PersistentList.createFromArray(Symbol.intern("var"), name)),
                        PersistentList.createFromArray(defSym, name, expr))));

        defMacro(ns, "declare", Fn.ofVarargs("declare", args -> {
            List<Object> forms = new ArrayList<>();
            forms.add(doSym);
            for (Object s : args) forms.add(PersistentList.createFromArray(defSym, s));
            return PersistentList.create(forms);
        }));

        defMacro(ns, "let", Fn.ofVarargs("let", args -> {
            List<Object> list = new ArrayList<>(args.length + 1);
            list.add(letStar);
            Collections.addAll(list, args);
            return PersistentList.create(list);
        }));

        defMacro(ns, "loop", Fn.ofVarargs("loop", args -> {
            List<Object> list = new ArrayList<>(args.length + 1);
            list.add(loopStar);
            Collections.addAll(list, args);
            return PersistentList.create(list);
        }));

        defMacro(ns, "when", Fn.ofVarargs("when", args -> {
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(ifSym, args[0], PersistentList.create(body));
        }));

        defMacro(ns, "when-not", Fn.ofVarargs("when-not", args -> {
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(ifSym, args[0], null, PersistentList.create(body));
        }));

        defMacro(ns, "if-not", Fn.ofVarargs("if-not", args -> {
            Object test = args[0];
            Object thenForm = args[1];
            Object elseForm = args.length > 2 ? args[2] : null;
            return PersistentList.createFromArray(ifSym,
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "not"), test),
                    thenForm, elseForm);
        }));

        defMacro(ns, "when-let", Fn.ofVarargs("when-let", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("when"));
            body.add(temp);
            List<Object> innerLet = new ArrayList<>();
            innerLet.add(letStar);
            innerLet.add(PersistentVector.createOwning(form, temp));
            for (int i = 1; i < args.length; i++) innerLet.add(args[i]);
            body.add(PersistentList.create(innerLet));
            return PersistentList.createFromArray(letStar, PersistentVector.createOwning(temp, tst), PersistentList.create(body));
        }));

        defMacro(ns, "if-let", Fn.ofVarargs("if-let", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Object thenF = args[1];
            Object elseF = args.length > 2 ? args[2] : null;
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(temp, tst),
                    PersistentList.createFromArray(ifSym, temp,
                            PersistentList.createFromArray(letStar, PersistentVector.createOwning(form, temp), thenF),
                            elseF));
        }));

        defMacro(ns, "when-some", Fn.ofVarargs("when-some", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            List<Object> innerLet = new ArrayList<>();
            innerLet.add(letStar);
            innerLet.add(PersistentVector.createOwning(form, temp));
            for (int i = 1; i < args.length; i++) innerLet.add(args[i]);
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(temp, tst),
                    PersistentList.createFromArray(ifSym,
                            PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), temp),
                            null,
                            PersistentList.create(innerLet)));
        }));

        defMacro(ns, "if-some", Fn.ofVarargs("if-some", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Object thenF = args[1];
            Object elseF = args.length > 2 ? args[2] : null;
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(temp, tst),
                    PersistentList.createFromArray(ifSym,
                            PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), temp),
                            elseF,
                            PersistentList.createFromArray(letStar, PersistentVector.createOwning(form, temp), thenF)));
        }));

        defMacro(ns, "when-first", Fn.ofVarargs("when-first", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            Object x = bindings.nth(0);
            Object xs = bindings.nth(1);
            Symbol s = Symbol.intern("s__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            body.add(letStar);
            body.add(PersistentVector.createOwning(x, PersistentList.createFromArray(Symbol.intern("clojure.core", "first"), s)));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(Symbol.intern("when-let"),
                    PersistentVector.createOwning(s, PersistentList.createFromArray(Symbol.intern("clojure.core", "seq"), xs)),
                    PersistentList.create(body));
        }));

        defMacro(ns, "and", Fn.ofVarargs("and", args -> {
            if (args.length == 0) return Boolean.TRUE;
            if (args.length == 1) return args[0];
            Object acc = args[args.length - 1];
            for (int i = args.length - 2; i >= 0; i--) {
                Symbol temp = Symbol.intern("and__" + RT.nextID());
                acc = PersistentList.createFromArray(letStar,
                        PersistentVector.createOwning(temp, args[i]),
                        PersistentList.createFromArray(ifSym, temp, acc, temp));
            }
            return acc;
        }));

        defMacro(ns, "or", Fn.ofVarargs("or", args -> {
            if (args.length == 0) return null;
            if (args.length == 1) return args[0];
            Object acc = args[args.length - 1];
            for (int i = args.length - 2; i >= 0; i--) {
                Symbol temp = Symbol.intern("or__" + RT.nextID());
                acc = PersistentList.createFromArray(letStar,
                        PersistentVector.createOwning(temp, args[i]),
                        PersistentList.createFromArray(ifSym, temp, temp, acc));
            }
            return acc;
        }));

        defMacro(ns, "cond", Fn.ofVarargs("cond", args -> {
            if (args.length == 0) return null;
            if ((args.length & 1) != 0) throw new IllegalArgumentException("cond requires an even number of forms");
            Object acc = null;
            for (int i = args.length - 2; i >= 0; i -= 2) {
                Object test = args[i];
                Object expr = args[i + 1];
                if (Keyword.intern("else").equals(test) || Boolean.TRUE.equals(test)) {
                    acc = expr;
                } else {
                    acc = PersistentList.createFromArray(ifSym, test, expr, acc);
                }
            }
            return acc;
        }));

        defMacro(ns, "condp", Fn.ofVarargs("condp", args -> {
            if (args.length < 2) {
                throw new IllegalArgumentException("condp requires at least a predicate and expression");
            }
            Object pred = args[0];
            Object expr = args[1];
            Symbol gpred = Symbol.intern("pred__" + RT.nextID());
            Symbol gexpr = Symbol.intern("expr__" + RT.nextID());
            Keyword arrowKw = Keyword.intern(">>");

            List<Object[]> clauses = new ArrayList<>();
            boolean hasDefault = false;
            Object defaultExpr = null;
            int idx = 2;
            while (idx < args.length) {
                if (idx == args.length - 1) {
                    hasDefault = true;
                    defaultExpr = args[idx];
                    break;
                }
                if (idx + 2 < args.length && arrowKw.equals(args[idx + 1])) {
                    clauses.add(new Object[]{args[idx], args[idx + 2], Boolean.TRUE});
                    idx += 3;
                } else {
                    clauses.add(new Object[]{args[idx], args[idx + 1], Boolean.FALSE});
                    idx += 2;
                }
            }

            Object acc = hasDefault ? defaultExpr
                    : PersistentList.createFromArray(Symbol.intern("throw"),
                            PersistentList.createFromArray(Symbol.intern("IllegalArgumentException."),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "str"), "No matching clause: ", gexpr)));
            for (int i = clauses.size() - 1; i >= 0; i--) {
                Object[] c = clauses.get(i);
                Object testVal = c[0];
                Object resForm = c[1];
                boolean isArrow = (Boolean) c[2];
                Object predCall = PersistentList.createFromArray(gpred, testVal, gexpr);
                if (isArrow) {
                    Symbol resSym = Symbol.intern("condp__" + RT.nextID());
                    acc = PersistentList.createFromArray(letStar,
                            PersistentVector.createOwning(resSym, predCall),
                            PersistentList.createFromArray(ifSym,
                                    resSym,
                                    PersistentList.createFromArray(resForm, resSym),
                                    acc));
                } else {
                    acc = PersistentList.createFromArray(ifSym, predCall, resForm, acc);
                }
            }
            return PersistentList.createFromArray(letStar, PersistentVector.createOwning(gpred, pred, gexpr, expr), acc);
        }));

        defMacro(ns, "case", Fn.ofVarargs("case", args -> {
            Object expr = args[0];
            boolean hasDefault = ((args.length - 1) & 1) != 0;
            int end = hasDefault ? args.length - 1 : args.length;
            List<Object> pairs = new ArrayList<>();
            for (int i = 1; i < end; i++) pairs.add(args[i]);
            PersistentVector pvec = PersistentVector.create(pairs);
            if (hasDefault) {
                return PersistentList.createFromArray(Symbol.intern("case*"), expr, pvec, args[args.length - 1]);
            }
            return PersistentList.createFromArray(Symbol.intern("case*"), expr, pvec);
        }));

        defMacro(ns, "->", Fn.ofVarargs("->", args -> {
            Object acc = args[0];
            for (int i = 1; i < args.length; i++) {
                Object form = args[i];
                if (form instanceof ISeq s) {
                    acc = RT.cons(s.first(), RT.cons(acc, s.next()));
                } else {
                    acc = PersistentList.createFromArray(form, acc);
                }
            }
            return acc;
        }));

        defMacro(ns, "->>", Fn.ofVarargs("->>", args -> {
            Object acc = args[0];
            for (int i = 1; i < args.length; i++) {
                Object form = args[i];
                if (form instanceof ISeq s) {
                    List<Object> items = new ArrayList<>();
                    for (ISeq p = s.seq(); p != null; p = p.next()) items.add(p.first());
                    items.add(acc);
                    acc = PersistentList.create(items);
                } else {
                    acc = PersistentList.createFromArray(form, acc);
                }
            }
            return acc;
        }));

        defMacro(ns, "as->", Fn.ofVarargs("as->", args -> {
            Object expr = args[0];
            Symbol name = (Symbol) args[1];
            List<Object> bindings = new ArrayList<>();
            bindings.add(name);
            bindings.add(expr);
            for (int i = 2; i < args.length; i++) {
                bindings.add(name);
                bindings.add(args[i]);
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(bindings), name);
        }));

        defMacro(ns, "some->", Fn.ofVarargs("some->", args -> {
            Symbol g = Symbol.intern("some__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            for (int i = 1; i < args.length; i++) {
                Object form = args[i];
                Object step = (form instanceof ISeq s)
                        ? RT.cons(s.first(), RT.cons(g, s.next()))
                        : PersistentList.createFromArray(form, g);
                steps.add(g);
                steps.add(PersistentList.createFromArray(ifSym,
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), g),
                        null, step));
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), g);
        }));

        defMacro(ns, "some->>", Fn.ofVarargs("some->>", args -> {
            Symbol g = Symbol.intern("some__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            for (int i = 1; i < args.length; i++) {
                Object form = args[i];
                Object step;
                if (form instanceof ISeq s) {
                    List<Object> items = new ArrayList<>();
                    for (ISeq p = s.seq(); p != null; p = p.next()) items.add(p.first());
                    items.add(g);
                    step = PersistentList.create(items);
                } else {
                    step = PersistentList.createFromArray(form, g);
                }
                steps.add(g);
                steps.add(PersistentList.createFromArray(ifSym,
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), g),
                        null, step));
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), g);
        }));

        defMacro(ns, "cond->", Fn.ofVarargs("cond->", args -> {
            Symbol g = Symbol.intern("cond__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            for (int i = 1; i < args.length; i += 2) {
                Object test = args[i];
                Object form = args[i + 1];
                Object step = (form instanceof ISeq s)
                        ? RT.cons(s.first(), RT.cons(g, s.next()))
                        : PersistentList.createFromArray(form, g);
                steps.add(g);
                steps.add(PersistentList.createFromArray(ifSym, test, step, g));
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), g);
        }));

        defMacro(ns, "cond->>", Fn.ofVarargs("cond->>", args -> {
            Symbol g = Symbol.intern("cond__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            for (int i = 1; i < args.length; i += 2) {
                Object test = args[i];
                Object form = args[i + 1];
                Object step;
                if (form instanceof ISeq s) {
                    List<Object> items = new ArrayList<>();
                    for (ISeq p = s.seq(); p != null; p = p.next()) items.add(p.first());
                    items.add(g);
                    step = PersistentList.create(items);
                } else {
                    step = PersistentList.createFromArray(form, g);
                }
                steps.add(g);
                steps.add(PersistentList.createFromArray(ifSym, test, step, g));
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), g);
        }));

        defMacro(ns, "doto", Fn.ofVarargs("doto", args -> {
            Symbol gx = Symbol.intern("doto__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            body.add(letStar);
            body.add(PersistentVector.createOwning(gx, args[0]));
            for (int i = 1; i < args.length; i++) {
                Object form = args[i];
                if (form instanceof ISeq s) {
                    body.add(RT.cons(s.first(), RT.cons(gx, s.next())));
                } else {
                    body.add(PersistentList.createFromArray(form, gx));
                }
            }
            body.add(gx);
            return PersistentList.create(body);
        }));

        defMacro(ns, "..", Fn.ofVarargs("..", args -> {
            Object acc = PersistentList.createFromArray(Symbol.intern("."), args[0], args[1]);
            for (int i = 2; i < args.length; i++) {
                acc = PersistentList.createFromArray(Symbol.intern("."), acc, args[i]);
            }
            return acc;
        }));

        // Lazy-seq, delay, future
        def(ns, "lazy-seq*", Fn.of1("lazy-seq*", f -> new LazySeq((Fn) f)));
        defMacro(ns, "lazy-seq", Fn.ofVarargs("lazy-seq", args -> {
            List<Object> body = new ArrayList<>();
            body.add(fnStar);
            body.add(PersistentVector.EMPTY);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "lazy-seq*"), PersistentList.create(body));
        }));

        def(ns, "delay*", Fn.of1("delay*", f -> new Atom.Delay((Fn) f)));
        defMacro(ns, "delay", Fn.ofVarargs("delay", args -> {
            List<Object> body = new ArrayList<>();
            body.add(fnStar);
            body.add(PersistentVector.EMPTY);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "delay*"), PersistentList.create(body));
        }));

        defMacro(ns, "future", Fn.ofVarargs("future", args -> {
            List<Object> body = new ArrayList<>();
            body.add(fnStar);
            body.add(PersistentVector.EMPTY);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "future-call"), PersistentList.create(body));
        }));

        defMacro(ns, "comment", Fn.ofVarargs("comment", args -> null));

        // Loops & comprehensions: dotimes, while, doseq, for
        defMacro(ns, "dotimes", Fn.ofVarargs("dotimes", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            Object sym = bindings.nth(0);
            Object limit = bindings.nth(1);
            Symbol nSym = Symbol.intern("n__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            body.add(PersistentList.createFromArray(Symbol.intern("recur"),
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "unchecked-inc-long"), sym)));
            // Use standard inc for safety
            body.set(body.size() - 1, PersistentList.createFromArray(Symbol.intern("recur"),
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "inc"), sym)));
            List<Object> loopBody = new ArrayList<>();
            loopBody.add(loopStar);
            loopBody.add(PersistentVector.createOwning(sym, 0L));
            List<Object> whenForm = new ArrayList<>();
            whenForm.add(Symbol.intern("when"));
            whenForm.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "<"), sym, nSym));
            whenForm.addAll(body);
            loopBody.add(PersistentList.create(whenForm));
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(nSym, PersistentList.createFromArray(Symbol.intern("clojure.core", "long"), limit)),
                    PersistentList.create(loopBody));
        }));

        defMacro(ns, "while", Fn.ofVarargs("while", args -> {
            Object test = args[0];
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("when"));
            body.add(test);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            body.add(PersistentList.createFromArray(Symbol.intern("recur")));
            return PersistentList.createFromArray(loopStar, PersistentVector.EMPTY, PersistentList.create(body));
        }));

        defMacro(ns, "doseq", Fn.ofVarargs("doseq", args -> {
            PersistentVector seqExprs = (PersistentVector) args[0];
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            Object inner = PersistentList.create(body);
            return expandDoseq(seqExprs, 0, inner);
        }));

        defMacro(ns, "for", Fn.ofVarargs("for", args -> {
            PersistentVector seqExprs = (PersistentVector) args[0];
            Object bodyExpr = args[1];
            return expandFor(seqExprs, 0, bodyExpr);
        }));

        // Binding & With-Open & With-Out-Str & Time & Assert
        def(ns, "push-thread-bindings", Fn.of1("push-thread-bindings", m -> {
            Map<Var, Object> map = new HashMap<>();
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                map.put((Var) e.getKey(), e.getValue());
            }
            Var.pushThreadBindings(map);
            return null;
        }));
        def(ns, "pop-thread-bindings", Fn.of0("pop-thread-bindings", () -> {
            Var.popThreadBindings();
            return null;
        }));

        defMacro(ns, "binding", Fn.ofVarargs("binding", args -> {
            PersistentVector vec = (PersistentVector) args[0];
            List<Object> kvs = new ArrayList<>();
            kvs.add(Symbol.intern("clojure.core", "hash-map"));
            for (int i = 0; i < vec.count(); i += 2) {
                kvs.add(PersistentList.createFromArray(Symbol.intern("var"), vec.nth(i)));
                kvs.add(vec.nth(i + 1));
            }
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(doSym,
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "push-thread-bindings"), PersistentList.create(kvs)),
                    PersistentList.createFromArray(Symbol.intern("try"),
                            PersistentList.create(body),
                            PersistentList.createFromArray(Symbol.intern("finally"),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "pop-thread-bindings")))));
        }));

        defMacro(ns, "with-out-str", Fn.ofVarargs("with-out-str", args -> {
            Symbol sw = Symbol.intern("sw__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("binding"));
            body.add(PersistentVector.createOwning(
                    Symbol.intern("clojure.core", "*out*"),
                    PersistentList.createFromArray(Symbol.intern("java.io.PrintWriter."), sw)));
            for (Object a : args) body.add(a);
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(sw, PersistentList.createFromArray(Symbol.intern("java.io.StringWriter."))),
                    PersistentList.create(body),
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "str"), sw));
        }));

        defMacro(ns, "with-in-str", Fn.ofVarargs("with-in-str", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("binding"));
            body.add(PersistentVector.createOwning(
                    Symbol.intern("clojure.core", "*in*"),
                    PersistentList.createFromArray(Symbol.intern("java.io.BufferedReader."),
                            PersistentList.createFromArray(Symbol.intern("java.io.StringReader."), args[0]))));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        }));

        defMacro(ns, "with-open", Fn.ofVarargs("with-open", args -> {
            PersistentVector bindings = (PersistentVector) args[0];
            if (bindings.count() == 0) {
                List<Object> body = new ArrayList<>();
                body.add(doSym);
                for (int i = 1; i < args.length; i++) body.add(args[i]);
                return PersistentList.create(body);
            }
            Object sym = bindings.nth(0);
            Object init = bindings.nth(1);
            List<Object> restBindings = new ArrayList<>();
            for (int i = 2; i < bindings.count(); i++) restBindings.add(bindings.nth(i));
            List<Object> inner = new ArrayList<>();
            inner.add(Symbol.intern("with-open"));
            inner.add(PersistentVector.create(restBindings));
            for (int i = 1; i < args.length; i++) inner.add(args[i]);
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(sym, init),
                    PersistentList.createFromArray(Symbol.intern("try"),
                            PersistentList.create(inner),
                            PersistentList.createFromArray(Symbol.intern("finally"),
                                    PersistentList.createFromArray(Symbol.intern(".close"), sym))));
        }));

        defMacro(ns, "time", Fn.of1("time", expr -> {
            Symbol start = Symbol.intern("start__" + RT.nextID());
            Symbol ret = Symbol.intern("ret__" + RT.nextID());
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(
                            start, PersistentList.createFromArray(Symbol.intern("System/nanoTime")),
                            ret, expr),
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "println"),
                            PersistentList.createFromArray(Symbol.intern("clojure.core", "str"),
                                    "Elapsed time: ",
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "/"),
                                            PersistentList.createFromArray(Symbol.intern("clojure.core", "double"),
                                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "-"),
                                                            PersistentList.createFromArray(Symbol.intern("System/nanoTime")),
                                                            start)),
                                            1000000.0),
                                    " msecs")),
                    ret);
        }));

        defMacro(ns, "assert", Fn.ofVarargs("assert", args -> {
            Object x = args[0];
            Object msg = args.length > 1 ? args[1] : RT.printString(x);
            return PersistentList.createFromArray(Symbol.intern("when-not"), x,
                    PersistentList.createFromArray(Symbol.intern("throw"),
                            PersistentList.createFromArray(Symbol.intern("AssertionError."),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "str"), "Assert failed: ", msg))));
        }));

        // (ns foo.bar docstring? attr-map? (:refer-clojure ...) (:require ...) (:import ...))
        defMacro(ns, "ns", Fn.ofVarargs("ns", args -> {
            Symbol nsSym = (Symbol) args[0];
            int idx = 1;
            String doc = (idx < args.length && args[idx] instanceof String s) ? (String) args[idx++] : null;
            PersistentHashMap attrMap = (idx < args.length && args[idx] instanceof PersistentHashMap m)
                    ? (PersistentHashMap) args[idx++] : null;
            PersistentHashMap mergedMeta = nsSym.meta() != null ? nsSym.meta() : PersistentHashMap.EMPTY;
            if (doc != null) mergedMeta = mergedMeta.assoc(Keyword.intern("doc"), doc);
            if (attrMap != null) {
                for (ISeq s = attrMap.seq(); s != null; s = s.next()) {
                    Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                    mergedMeta = mergedMeta.assoc(e.getKey(), e.getValue());
                }
            }

            List<Object> referClojureOpts = null;
            List<Object> clauseForms = new ArrayList<>();
            for (int i = idx; i < args.length; i++) {
                if (args[i] instanceof ISeq clause) {
                    Object head = clause.first();
                    if (Keyword.intern("refer-clojure").equals(head)) {
                        referClojureOpts = new ArrayList<>();
                        referClojureOpts.add(Symbol.intern("clojure.core", "refer"));
                        referClojureOpts.add(PersistentList.createFromArray(quoteSym, Symbol.intern("clojure.core")));
                        for (ISeq s = clause.next(); s != null; s = s.next()) {
                            referClojureOpts.add(PersistentList.createFromArray(quoteSym, s.first()));
                        }
                    } else if (Keyword.intern("require").equals(head)) {
                        List<Object> req = new ArrayList<>();
                        req.add(Symbol.intern("clojure.core", "require"));
                        for (ISeq s = clause.next(); s != null; s = s.next()) {
                            req.add(PersistentList.createFromArray(quoteSym, s.first()));
                        }
                        clauseForms.add(PersistentList.create(req));
                    } else if (Keyword.intern("use").equals(head)) {
                        List<Object> use = new ArrayList<>();
                        use.add(Symbol.intern("clojure.core", "use"));
                        for (ISeq s = clause.next(); s != null; s = s.next()) {
                            use.add(PersistentList.createFromArray(quoteSym, s.first()));
                        }
                        clauseForms.add(PersistentList.create(use));
                    } else if (Keyword.intern("import").equals(head)) {
                        List<Object> imp = new ArrayList<>();
                        imp.add(Symbol.intern("clojure.core", "import"));
                        for (ISeq s = clause.next(); s != null; s = s.next()) {
                            imp.add(PersistentList.createFromArray(quoteSym, s.first()));
                        }
                        clauseForms.add(PersistentList.create(imp));
                    }
                }
            }

            List<Object> forms = new ArrayList<>();
            forms.add(doSym);
            forms.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "in-ns"),
                    PersistentList.createFromArray(quoteSym, nsSym)));
            if (mergedMeta.count() > 0) {
                forms.add(PersistentList.createFromArray(
                        Symbol.intern("clojure.core", "alter-meta!"),
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "the-ns"),
                                PersistentList.createFromArray(quoteSym, nsSym)),
                        Symbol.intern("clojure.core", "merge"),
                        PersistentList.createFromArray(quoteSym, mergedMeta)));
            }
            if (referClojureOpts != null) {
                forms.add(PersistentList.create(referClojureOpts));
            } else {
                forms.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "refer"),
                        PersistentList.createFromArray(quoteSym, Symbol.intern("clojure.core"))));
            }
            forms.addAll(clauseForms);
            return PersistentList.create(forms);
        }));

        // Multimethod macros: defmulti, defmethod
        def(ns, "create-multi", Fn.of3("create-multi", (name, dispatchFn, defaultVal) ->
                new MultiFn(name.toString(), (Fn) dispatchFn, defaultVal)));
        def(ns, "add-method!", Fn.of3("add-method!", (mf, dispatchVal, methodFn) ->
                ((MultiFn) mf).addMethod(dispatchVal, (Fn) methodFn)));

        defMacro(ns, "defmulti", Fn.ofVarargs("defmulti", args -> {
            Symbol name = (Symbol) args[0];
            int idx = 1;
            if (idx < args.length && args[idx] instanceof String) idx++;
            if (idx < args.length && args[idx] instanceof PersistentHashMap) idx++;
            Object dispatchFn = args[idx++];
            Object defaultVal = Keyword.intern("default");
            for (int i = idx; i < args.length; i += 2) {
                if (Keyword.intern("default").equals(args[i])) defaultVal = args[i + 1];
            }
            return PersistentList.createFromArray(defSym, name,
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "create-multi"),
                            name.name, dispatchFn, defaultVal));
        }));

        defMacro(ns, "defmethod", Fn.ofVarargs("defmethod", args -> {
            Symbol mfName = (Symbol) args[0];
            Object dispatchVal = args[1];
            List<Object> fnBody = new ArrayList<>();
            fnBody.add(fnStar);
            for (int i = 2; i < args.length; i++) fnBody.add(args[i]);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "add-method!"),
                    mfName, dispatchVal, PersistentList.create(fnBody));
        }));

        // letfn macro
        defMacro(ns, "letfn", Fn.ofVarargs("letfn", args -> {
            PersistentVector fnspecs = (PersistentVector) args[0];
            // Expand using atom indirection so mutual recursion works cleanly
            List<Object> bindings = new ArrayList<>();
            List<Object> resets = new ArrayList<>();
            List<Object> innerLets = new ArrayList<>();
            for (int i = 0; i < fnspecs.count(); i++) {
                ISeq spec = (ISeq) fnspecs.nth(i);
                Symbol fnName = (Symbol) spec.first();
                Symbol boxSym = Symbol.intern("box__" + fnName.name + "__" + RT.nextID());
                bindings.add(boxSym);
                bindings.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "atom"), null));
                innerLets.add(fnName);
                innerLets.add(PersistentList.createFromArray(fnStar,
                        PersistentVector.createOwning(Symbol.intern("&"), Symbol.intern("args__")),
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "apply"),
                                PersistentList.createFromArray(Symbol.intern("clojure.core", "deref"), boxSym),
                                Symbol.intern("args__"))));
                resets.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "reset!"),
                        boxSym,
                        RT.cons(fnStar, spec)));
            }
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            body.addAll(resets);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(letStar, PersistentVector.create(bindings),
                    PersistentList.createFromArray(letStar, PersistentVector.create(innerLets),
                            PersistentList.create(body)));
        }));
    }

    private static Object expandDoseq(PersistentVector seqExprs, int idx, Object body) {
        if (idx >= seqExprs.count()) return body;
        Object k = seqExprs.nth(idx);
        Object v = seqExprs.nth(idx + 1);
        if (Keyword.intern("let").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("let*"), v, expandDoseq(seqExprs, idx + 2, body));
        }
        if (Keyword.intern("when").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("when"), v, expandDoseq(seqExprs, idx + 2, body));
        }
        if (Keyword.intern("while").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("when"), v, expandDoseq(seqExprs, idx + 2, body));
        }
        Symbol s = Symbol.intern("seq__" + RT.nextID());
        Object inner = expandDoseq(seqExprs, idx + 2, body);
        return PersistentList.createFromArray(Symbol.intern("loop*"),
                PersistentVector.createOwning(s, PersistentList.createFromArray(Symbol.intern("clojure.core", "seq"), v)),
                PersistentList.createFromArray(Symbol.intern("when"), s,
                        PersistentList.createFromArray(Symbol.intern("let*"),
                                PersistentVector.createOwning(k, PersistentList.createFromArray(Symbol.intern("clojure.core", "first"), s)),
                                inner,
                                PersistentList.createFromArray(Symbol.intern("recur"),
                                        PersistentList.createFromArray(Symbol.intern("clojure.core", "next"), s)))));
    }

    private static Object expandFor(PersistentVector seqExprs, int idx, Object body) {
        if (idx >= seqExprs.count()) {
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "list"), body);
        }
        Object k = seqExprs.nth(idx);
        Object v = seqExprs.nth(idx + 1);
        if (Keyword.intern("let").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("let*"), v, expandFor(seqExprs, idx + 2, body));
        }
        if (Keyword.intern("when").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("when"), v, expandFor(seqExprs, idx + 2, body));
        }
        if (Keyword.intern("while").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("when"), v, expandFor(seqExprs, idx + 2, body));
        }
        Symbol stepFn = Symbol.intern("iter__" + RT.nextID());
        Symbol s = Symbol.intern("s__" + RT.nextID());
        Object inner = expandFor(seqExprs, idx + 2, body);
        boolean isLast = (idx + 2 >= seqExprs.count());
        Object recurse = PersistentList.createFromArray(stepFn,
                PersistentList.createFromArray(Symbol.intern("clojure.core", "next"), s));
        Object combined = isLast
                ? PersistentList.createFromArray(Symbol.intern("clojure.core", "cons"), body, recurse)
                : PersistentList.createFromArray(Symbol.intern("clojure.core", "concat"), inner, recurse);

        // Handle :when / :while inside the inner binding if present
        if (idx + 2 < seqExprs.count() && Keyword.intern("when").equals(seqExprs.nth(idx + 2))) {
            Object pred = seqExprs.nth(idx + 3);
            Object afterWhen = expandFor(seqExprs, idx + 4, body);
            boolean afterIsLast = (idx + 4 >= seqExprs.count());
            Object thenCombine = afterIsLast
                    ? PersistentList.createFromArray(Symbol.intern("clojure.core", "cons"), body, recurse)
                    : PersistentList.createFromArray(Symbol.intern("clojure.core", "concat"), afterWhen, recurse);
            combined = PersistentList.createFromArray(Symbol.intern("if"), pred, thenCombine, recurse);
        } else if (idx + 2 < seqExprs.count() && Keyword.intern("while").equals(seqExprs.nth(idx + 2))) {
            Object pred = seqExprs.nth(idx + 3);
            Object afterWhile = expandFor(seqExprs, idx + 4, body);
            boolean afterIsLast = (idx + 4 >= seqExprs.count());
            Object thenCombine = afterIsLast
                    ? PersistentList.createFromArray(Symbol.intern("clojure.core", "cons"), body, recurse)
                    : PersistentList.createFromArray(Symbol.intern("clojure.core", "concat"), afterWhile, recurse);
            combined = PersistentList.createFromArray(Symbol.intern("when"), pred, thenCombine);
        }

        return PersistentList.createFromArray(
                PersistentList.createFromArray(Symbol.intern("fn*"), stepFn, PersistentVector.createOwning(s),
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "lazy-seq"),
                                PersistentList.createFromArray(Symbol.intern("when-let"),
                                        PersistentVector.createOwning(s, PersistentList.createFromArray(Symbol.intern("clojure.core", "seq"), s)),
                                        PersistentList.createFromArray(Symbol.intern("let*"),
                                                PersistentVector.createOwning(k, PersistentList.createFromArray(Symbol.intern("clojure.core", "first"), s)),
                                                combined)))),
                v);
    }

    private static Object printDocValue(Object target) {
        if (target instanceof Var v) {
            printVarDoc(v);
        } else if (target instanceof PersistentHashMap m) {
            printMetaDoc(m, null, null);
        } else if (target instanceof Namespace nsObj) {
            printNamespaceDoc(nsObj);
        } else if (target instanceof Symbol sym) {
            if (sym.ns == null) {
                PersistentHashMap sf = specialFormMeta(sym.name);
                if (sf != null) {
                    printMetaDoc(sf, null, sym);
                    return null;
                }
            }
            Var v = Compiler.lookupVar(RT.currentNS(), sym, false);
            if (v != null) {
                printVarDoc(v);
                return null;
            }
            if (sym.ns == null) {
                Namespace foundNs = Namespace.find(sym);
                if (foundNs != null) {
                    printNamespaceDoc(foundNs);
                }
            }
        }
        return null;
    }

    private static void printVarDoc(Var v) {
        PersistentHashMap meta = v.meta();
        if (v.isMacro() && !RT.truthy(meta.valAt(Keyword.intern("macro")))) {
            meta = meta.assoc(Keyword.intern("macro"), Boolean.TRUE);
        }
        printMetaDoc(meta, v.ns, v.sym);
    }

    private static void printNamespaceDoc(Namespace nsObj) {
        writeOut("-------------------------", true);
        writeOut(nsObj.name.toString(), true);
        Object doc = nsObj.meta() != null ? nsObj.meta().valAt(Keyword.intern("doc")) : null;
        if (doc != null) {
            writeOut("  " + doc, true);
        }
    }

    private static void printMetaDoc(PersistentHashMap meta, Namespace fallbackNs, Symbol fallbackName) {
        Object nsObj = meta.valAt(Keyword.intern("ns"), fallbackNs);
        Object nameObj = meta.valAt(Keyword.intern("name"), fallbackName);
        String header;
        if (nsObj != null && nameObj != null) {
            String nsName = (nsObj instanceof Namespace n) ? n.name.toString() : nsObj.toString();
            header = nsName + "/" + nameObj;
        } else {
            header = nameObj != null ? nameObj.toString() : null;
        }

        writeOut("-------------------------", true);
        if (header != null) {
            writeOut(header, true);
        }
        Object forms = meta.valAt(Keyword.intern("forms"));
        if (forms != null) {
            for (ISeq s = RT.seq(forms); s != null; s = s.next()) {
                writeOut("  " + joinPrint(new Object[]{s.first()}, true), true);
            }
        }
        Object arglists = meta.valAt(Keyword.intern("arglists"));
        if (arglists != null) {
            writeOut(joinPrint(new Object[]{arglists}, true), true);
        }
        if (RT.truthy(meta.valAt(Keyword.intern("macro")))) {
            writeOut("Macro", true);
        }
        if (RT.truthy(meta.valAt(Keyword.intern("special-form")))) {
            writeOut("Special Form", true);
        }
        Object doc = meta.valAt(Keyword.intern("doc"));
        if (doc != null) {
            writeOut("  " + doc, true);
        }
    }

    private static PersistentHashMap specialFormMeta(String name) {
        String doc = switch (name) {
            case "def" -> "Creates and interns a global var with the name of symbol in the current namespace.";
            case "if" -> "Evaluates test. If not nil or false, evaluates and yields then, otherwise else or nil.";
            case "do" -> "Evaluates the expressions in order and returns the value of the last.";
            case "let*" -> "Evaluates the exprs in a lexical context in which the symbols are bound to their values.";
            case "loop*" -> "Evaluates the exprs in a lexical context and establishes a recursion point for recur.";
            case "recur" -> "Evaluates the exprs in order, then rebinds the recursion point bindings and jumps back.";
            case "fn*" -> "Defines an anonymous function.";
            case "quote" -> "Yields the unevaluated form.";
            case "var" -> "The symbol must resolve to a var, and the Var object itself (not its value) is returned.";
            case "try" -> "Evaluates exprs and catches exceptions matching catch clauses, running finally for side effects.";
            case "throw" -> "Throws an exception.";
            case "set!" -> "Assignments to thread-local var bindings or Java fields.";
            case "new" -> "Creates a new instance of a Java class.";
            case "." -> "Java interop member access and method invocation.";
            default -> null;
        };
        if (doc == null) return null;
        return PersistentHashMap.EMPTY
                .assoc(Keyword.intern("name"), Symbol.intern(name))
                .assoc(Keyword.intern("special-form"), Boolean.TRUE)
                .assoc(Keyword.intern("doc"), doc);
    }

    private static ISeq pmapFutures1(Fn f, ISeq s) {
        return new LazySeq(Fn.of0("pmap-futures", () -> {
            ISeq cur = RT.seq(s);
            if (cur == null) return null;
            Object item = cur.first();
            Atom.FutureObj fut = new Atom.FutureObj(Fn.of0("pmap-task", () -> f.invoke(item)));
            return new Cons(fut, pmapFutures1(f, cur.next()));
        }));
    }

    private static ISeq pmapFuturesN(Fn f, ISeq[] seqs) {
        return new LazySeq(Fn.of0("pmap-futures-n", () -> {
            Object[] callArgs = new Object[seqs.length];
            ISeq[] nextSeqs = new ISeq[seqs.length];
            for (int i = 0; i < seqs.length; i++) {
                ISeq cur = RT.seq(seqs[i]);
                if (cur == null) return null;
                callArgs[i] = cur.first();
                nextSeqs[i] = cur.next();
            }
            Atom.FutureObj fut = new Atom.FutureObj(Fn.of0("pmap-task", () -> f.applyTo(callArgs)));
            return new Cons(fut, pmapFuturesN(f, nextSeqs));
        }));
    }

    private static ISeq pmapStep(ISeq vs, ISeq fs) {
        return new LazySeq(Fn.of0("pmap-step", () -> {
            ISeq s = RT.seq(fs);
            if (s != null) {
                ISeq r = RT.seq(vs);
                if (r == null) return null;
                Atom.FutureObj fut = (Atom.FutureObj) r.first();
                return new Cons(fut.deref(), pmapStep(r.next(), s.next()));
            }
            return pmapDerefMap(vs);
        }));
    }

    private static ISeq pmapDerefMap(ISeq vs) {
        return new LazySeq(Fn.of0("pmap-deref", () -> {
            ISeq r = RT.seq(vs);
            if (r == null) return null;
            Atom.FutureObj fut = (Atom.FutureObj) r.first();
            return new Cons(fut.deref(), pmapDerefMap(r.next()));
        }));
    }
}
