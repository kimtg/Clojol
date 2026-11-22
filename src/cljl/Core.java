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
 * giving Clojol sub-100ms cold startup time.
 */
public final class Core {

    private static volatile int doneMask = 0;
    private static int loadingMask = 0;
    private static final Set<String> LOADED_LIBS = ConcurrentHashMap.newKeySet();
    public static volatile boolean coreRedefined = false;
    public static Fn addFn;
    public static Fn subFn;
    public static Fn mulFn;
    public static Fn incFn;
    public static Fn decFn;
    public static Fn conjFn;
    public static Fn uncheckedAddFn;
    public static Fn uncheckedSubFn;
    public static Fn uncheckedMulFn;
    public static Fn uncheckedIncFn;
    public static Fn uncheckedDecFn;

    private Core() {}

    public static void init() {
        RT.CLOJURE_NS.setLazyLoader(CoreExtrasIndex.LOADER);
    }

    public static void initAll(Namespace ns) {
        for (int i = 0; i < CoreExtrasIndex.CORE_SUBSYSTEM_COUNT; i++) {
            initSubsystem(i);
        }
    }

    public static void initSubsystem(int id) {
        int bit = 1 << id;
        if ((doneMask & bit) != 0) return;
        synchronized (Core.class) {
            if ((doneMask & bit) != 0 || (loadingMask & bit) != 0) return;
            loadingMask |= bit;
            Namespace ns = RT.CLOJURE_NS;
            try {
                switch (id) {
                    case 0 -> initPredicates(ns);
                    case 1 -> initMath(ns);
                    case 2 -> initBitwiseAndCasts(ns);
                    case 3 -> initSequences(ns);
                    case 4 -> initCollections(ns);
                    case 5 -> initStringsAndIO(ns);
                    case 6 -> initFunctional(ns);
                    case 7 -> initConcurrencyAndVars(ns);
                    case 8 -> initNamespacesAndEval(ns);
                    case 9 -> initArraysAndExceptions(ns);
                    case 10 -> initMacros(ns);
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

    private static Object expandDefn(Object[] args) {
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
        Keyword arglistsKw = Keyword.intern("arglists");
        if (idx < endIdx && !(args[idx] instanceof PersistentVector)) {
            if (args[endIdx - 1] instanceof PersistentHashMap tailAttr) {
                for (ISeq s = tailAttr.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                    meta = meta.assoc(me.key, me.val);
                }
                endIdx--;
            }
            if (!meta.containsKey(arglistsKw)) {
                List<Object> arglists = new ArrayList<>();
                for (int i = idx; i < endIdx; i++) {
                    if (args[i] instanceof ISeq clause && clause.first() != null) {
                        arglists.add(clause.first());
                    }
                }
                if (!arglists.isEmpty()) {
                    meta = meta.assoc(arglistsKw, PersistentList.createFromArray(quoteSym, PersistentList.create(arglists)));
                }
            }
        } else if (idx < endIdx && args[idx] instanceof PersistentVector paramsVec) {
            if (!meta.containsKey(arglistsKw)) {
                meta = meta.assoc(arglistsKw, PersistentList.createFromArray(quoteSym, PersistentList.createFromArray(paramsVec)));
            }
        }
        Symbol namedSym = meta.count() > 0 ? name.withMeta(meta) : name;
        Object[] fnSpec = new Object[1 + (endIdx - idx)];
        fnSpec[0] = name;
        for (int i = idx; i < endIdx; i++) fnSpec[1 + (i - idx)] = args[i];
        return PersistentList.createFromArray(Symbol.intern("def"), namedSym, expandFn(fnSpec));
    }

    private static Object expandLet(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof PersistentVector rawBindings)) {
            throw new IllegalArgumentException("let requires a vector for its binding");
        }
        PersistentVector bindings = Compiler.destructure(rawBindings);
        List<Object> list = new ArrayList<>(args.length + 1);
        list.add(letStar);
        list.add(bindings);
        for (int i = 1; i < args.length; i++) list.add(args[i]);
        return PersistentList.create(list);
    }

    private static Object expandLoop(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof PersistentVector rawBindings)) {
            throw new IllegalArgumentException("loop requires a vector for its binding");
        }
        if ((rawBindings.count() & 1) != 0) {
            throw new IllegalArgumentException("loop requires an even number of forms in binding vector");
        }
        boolean hasComplex = false;
        for (int i = 0; i < rawBindings.count(); i += 2) {
            if (!(rawBindings.nth(i) instanceof Symbol)) {
                hasComplex = true;
                break;
            }
        }
        if (!hasComplex) {
            List<Object> list = new ArrayList<>(args.length + 1);
            list.add(loopStar);
            Collections.addAll(list, args);
            return PersistentList.create(list);
        }
        List<Object> outerLet = new ArrayList<>();
        List<Object> loopBindings = new ArrayList<>();
        List<Object> innerLet = new ArrayList<>();
        for (int i = 0; i < rawBindings.count(); i += 2) {
            Object pat = rawBindings.nth(i);
            Object init = rawBindings.nth(i + 1);
            if (pat instanceof Symbol sym) {
                outerLet.add(sym);
                outerLet.add(init);
                loopBindings.add(sym);
                loopBindings.add(sym);
            } else {
                Symbol g = Symbol.intern("loop__" + RT.nextID());
                outerLet.add(g);
                outerLet.add(init);
                outerLet.add(pat);
                outerLet.add(g);
                loopBindings.add(g);
                loopBindings.add(g);
                innerLet.add(pat);
                innerLet.add(g);
            }
        }
        List<Object> innerBody = new ArrayList<>(args.length + 1);
        innerBody.add(letStar);
        innerBody.add(Compiler.destructure(PersistentVector.create(innerLet)));
        for (int i = 1; i < args.length; i++) innerBody.add(args[i]);
        Object loopForm = PersistentList.createFromArray(
                loopStar,
                PersistentVector.create(loopBindings),
                PersistentList.create(innerBody));
        return PersistentList.createFromArray(
                letStar,
                Compiler.destructure(PersistentVector.create(outerLet)),
                loopForm);
    }

    private static ISeq expandFnArity(PersistentVector rawParams, ISeq bodyForms) {
        boolean hasComplex = false;
        for (int i = 0; i < rawParams.count(); i++) {
            Object p = rawParams.nth(i);
            if (!(p instanceof Symbol)) {
                hasComplex = true;
                break;
            }
        }
        if (!hasComplex) {
            return RT.cons(rawParams, bodyForms);
        }
        List<Object> newParams = new ArrayList<>(rawParams.count());
        List<Object> destructurePairs = new ArrayList<>();
        for (int i = 0; i < rawParams.count(); i++) {
            Object p = rawParams.nth(i);
            if (p instanceof Symbol s && "&".equals(s.name)) {
                newParams.add(s);
                if (i + 1 < rawParams.count()) {
                    Object restPat = rawParams.nth(++i);
                    if (restPat instanceof Symbol rs) {
                        newParams.add(rs);
                    } else {
                        Symbol g = Symbol.intern("p__" + RT.nextID());
                        newParams.add(g);
                        destructurePairs.add(restPat);
                        destructurePairs.add(g);
                    }
                }
            } else if (p instanceof Symbol s) {
                newParams.add(s);
            } else {
                Symbol g = Symbol.intern("p__" + RT.nextID());
                newParams.add(g);
                destructurePairs.add(p);
                destructurePairs.add(g);
            }
        }
        // Handle :pre / :post condition map before wrapping in let* so :pre/:post see destructured locals
        if (bodyForms != null && bodyForms.first() instanceof PersistentHashMap condMap && bodyForms.next() != null) {
            Object preConds = condMap.valAt(Keyword.intern("pre"));
            Object postConds = condMap.valAt(Keyword.intern("post"));
            if (preConds != null || postConds != null) {
                ISeq actualBody = bodyForms.next();
                Object innerExpr = (actualBody.next() == null) ? actualBody.first() : RT.cons(doSym, actualBody);
                if (postConds != null) {
                    Symbol retSym = Symbol.intern("%");
                    List<Object> postBody = new ArrayList<>();
                    for (ISeq ps = RT.seq(postConds); ps != null; ps = ps.next()) {
                        postBody.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "assert"), ps.first()));
                    }
                    postBody.add(retSym);
                    innerExpr = RT.cons(letStar, RT.cons(
                            PersistentVector.createOwning(retSym, innerExpr),
                            PersistentList.create(postBody)));
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
        PersistentVector paramsVec = PersistentVector.create(newParams);
        if (rawParams.meta() != null) paramsVec = paramsVec.withMeta(rawParams.meta());
        PersistentVector db = Compiler.destructure(PersistentVector.create(destructurePairs));
        Object wrapped = RT.cons(letStar, RT.cons(db, bodyForms));
        return PersistentList.createFromArray(paramsVec, wrapped);
    }

    private static Object expandFn(Object[] args) {
        int idx = 0;
        Symbol name = null;
        if (idx < args.length && args[idx] instanceof Symbol s) {
            name = s;
            idx++;
        }
        List<Object> out = new ArrayList<>(args.length + 1);
        out.add(fnStar);
        if (name != null) out.add(name);
        if (idx < args.length && args[idx] instanceof PersistentVector rawParams) {
            List<Object> body = new ArrayList<>();
            for (int i = idx + 1; i < args.length; i++) body.add(args[i]);
            ISeq expanded = expandFnArity(rawParams, PersistentList.create(body));
            for (ISeq s = expanded; s != null; s = s.next()) out.add(s.first());
        } else {
            for (int i = idx; i < args.length; i++) {
                if (args[i] instanceof ISeq clause && clause.first() instanceof PersistentVector rawParams) {
                    out.add(expandFnArity(rawParams, clause.next()));
                } else {
                    out.add(args[i]);
                }
            }
        }
        return PersistentList.create(out);
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
        def(ns, "nil?", new CoreFn1(0, "nil?"));
        def(ns, "some?", new CoreFn1(1, "some?"));
        def(ns, "true?", new CoreFn1(2, "true?"));
        def(ns, "false?", new CoreFn1(3, "false?"));
        def(ns, "boolean?", new CoreFn1(4, "boolean?"));
        def(ns, "boolean", new CoreFn1(5, "boolean"));
        def(ns, "not", new CoreFn1(6, "not"));
        def(ns, "identical?", new CoreFn2(0, "identical?"));

        def(ns, "=", new CoreFnV(0, "="));

        def(ns, "not=", new CoreFnV(1, "not="));

        def(ns, "==", new CoreFnV(2, "=="));

        def(ns, "compare", new CoreFn2(1, "compare"));
        def(ns, "hash", new CoreFn1(7, "hash"));

        def(ns, "number?", new CoreFn1(8, "number?"));
        def(ns, "integer?", new CoreFn1(9, "integer?"));
        def(ns, "int?", new CoreFn1(10, "int?"));
        def(ns, "pos-int?", new CoreFn1(11, "pos-int?"));
        def(ns, "nat-int?", new CoreFn1(12, "nat-int?"));
        def(ns, "neg-int?", new CoreFn1(13, "neg-int?"));
        def(ns, "float?", new CoreFn1(14, "float?"));
        def(ns, "double?", new CoreFn1(15, "double?"));
        def(ns, "ratio?", new CoreFn1(16, "ratio?"));
        def(ns, "decimal?", new CoreFn1(17, "decimal?"));
        def(ns, "rational?", new CoreFn1(18, "rational?"));

        def(ns, "zero?", new CoreFn1(19, "zero?"));
        def(ns, "pos?", new CoreFn1(20, "pos?"));
        def(ns, "neg?", new CoreFn1(21, "neg?"));
        def(ns, "even?", new CoreFn1(22, "even?"));
        def(ns, "odd?", new CoreFn1(23, "odd?"));

        def(ns, "string?", new CoreFn1(24, "string?"));
        def(ns, "char?", new CoreFn1(25, "char?"));
        def(ns, "symbol?", new CoreFn1(26, "symbol?"));
        def(ns, "keyword?", new CoreFn1(27, "keyword?"));
        def(ns, "simple-symbol?", new CoreFn1(28, "simple-symbol?"));
        def(ns, "qualified-symbol?", new CoreFn1(29, "qualified-symbol?"));
        def(ns, "simple-keyword?", new CoreFn1(30, "simple-keyword?"));
        def(ns, "qualified-keyword?", new CoreFn1(31, "qualified-keyword?"));
        def(ns, "list?", new CoreFn1(32, "list?"));
        def(ns, "vector?", new CoreFn1(33, "vector?"));
        def(ns, "map?", new CoreFn1(34, "map?"));
        def(ns, "set?", new CoreFn1(35, "set?"));
        def(ns, "seq?", new CoreFn1(36, "seq?"));
        def(ns, "seqable?", new CoreFn1(37, "seqable?"));
        def(ns, "coll?", new CoreFn1(38, "coll?"));
        def(ns, "associative?", new CoreFn1(39, "associative?"));
        def(ns, "sequential?", new CoreFn1(40, "sequential?"));
        def(ns, "counted?", new CoreFn1(41, "counted?"));
        def(ns, "ifn?", new CoreFn1(42, "ifn?"));
        def(ns, "fn?", new CoreFn1(43, "fn?"));
        def(ns, "var?", new CoreFn1(44, "var?"));
        def(ns, "class?", new CoreFn1(45, "class?"));
        def(ns, "instance?", new CoreFn2(2, "instance?"));
        def(ns, "isa?", new CoreFn23(3, 0, "isa?"));
        def(ns, "empty?", new CoreFn1(46, "empty?"));
        def(ns, "reduced?", new CoreFn1(47, "reduced?"));
        def(ns, "realized?", new CoreFn1(48, "realized?"));
        def(ns, "distinct?", new CoreFnV(3, "distinct?"));
    }

    // ========================================================================
    // Arithmetic & Math
    // ========================================================================

    private static void initMath(Namespace ns) {
        Fn plus = new Fn.NamedFn("+") {
            @Override public Object invoke() { return 0L; }
            @Override public Object invoke(Object a) { return a; }
            @Override public Object invoke(Object a, Object b) { return Numbers.add(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return Numbers.add(Numbers.add(a, b), c); }
            @Override public Object invoke(Object a, Object b, Object c, Object d) { return Numbers.add(Numbers.add(Numbers.add(a, b), c), d); }
            @Override public boolean isUnboxedLong2() { return true; }
            @Override public long invokePrim_JJ_J(long a, long b) { return Math.addExact(a, b); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return 0L;
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.add(acc, args[i]);
                return acc;
            }
        };
        addFn = plus;
        def(ns, "+", plus);

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

        subFn = new Fn.NamedFn("-") {
            @Override public Object invoke(Object a) { return Numbers.minus(a); }
            @Override public Object invoke(Object a, Object b) { return Numbers.minus(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return Numbers.minus(Numbers.minus(a, b), c); }
            @Override public boolean isUnboxedLong2() { return true; }
            @Override public long invokePrim_JJ_J(long a, long b) { return Math.subtractExact(a, b); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                if (args.length == 1) return Numbers.minus(args[0]);
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.minus(acc, args[i]);
                return acc;
            }
        };
        def(ns, "-", subFn);

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

        mulFn = new Fn.NamedFn("*") {
            @Override public Object invoke() { return 1L; }
            @Override public Object invoke(Object a) { return a; }
            @Override public Object invoke(Object a, Object b) { return Numbers.multiply(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return Numbers.multiply(Numbers.multiply(a, b), c); }
            @Override public Object invoke(Object a, Object b, Object c, Object d) { return Numbers.multiply(Numbers.multiply(Numbers.multiply(a, b), c), d); }
            @Override public boolean isUnboxedLong2() { return true; }
            @Override public long invokePrim_JJ_J(long a, long b) { return Math.multiplyExact(a, b); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return 1L;
                Object acc = args[0];
                for (int i = 1; i < args.length; i++) acc = Numbers.multiply(acc, args[i]);
                return acc;
            }
        };
        def(ns, "*", mulFn);

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

        def(ns, "/", new CoreFnV(4, "/"));

        def(ns, "quot", new CoreFn2(4, "quot"));
        def(ns, "rem", new CoreFn2(5, "rem"));
        def(ns, "mod", new CoreFn2(6, "mod"));
        incFn = new Fn.NamedFn("inc") {
            @Override public Object invoke(Object x) { return Numbers.inc(x); }
            @Override public boolean isUnboxedLong1() { return true; }
            @Override public long invokePrim_J_J(long x) { return Numbers.incExact(x); }
        };
        def(ns, "inc", incFn);
        def(ns, "inc'", new Fn.NamedFn("inc'") {
            @Override public Object invoke(Object x) { return Numbers.incP(x); }
        });
        decFn = new Fn.NamedFn("dec") {
            @Override public Object invoke(Object x) { return Numbers.dec(x); }
            @Override public boolean isUnboxedLong1() { return true; }
            @Override public long invokePrim_J_J(long x) { return Numbers.decExact(x); }
        };
        def(ns, "dec", decFn);
        def(ns, "dec'", new Fn.NamedFn("dec'") {
            @Override public Object invoke(Object x) { return Numbers.decP(x); }
        });
        def(ns, "abs", new CoreFn1(49, "abs"));

        def(ns, "<", new CoreFnV(5, "<"));
        def(ns, "<=", new CoreFnV(6, "<="));
        def(ns, ">", new CoreFnV(7, ">"));
        def(ns, ">=", new CoreFnV(8, ">="));

        def(ns, "max", new CoreFnV(9, "max"));
        def(ns, "min", new CoreFnV(10, "min"));

        def(ns, "rand", new CoreFnV(11, "rand"));
        def(ns, "rand-int", new CoreFn1(50, "rand-int"));
        def(ns, "rand-nth", new CoreFn1(51, "rand-nth"));
    }

    private static void initBitwiseAndCasts(Namespace ns) {
        def(ns, "bit-and", new CoreFnV(12, "bit-and"));
        def(ns, "bit-or", new CoreFnV(13, "bit-or"));
        def(ns, "bit-xor", new CoreFnV(14, "bit-xor"));
        def(ns, "bit-not", new CoreFn1(52, "bit-not"));
        def(ns, "bit-and-not", new CoreFnV(15, "bit-and-not"));
        def(ns, "bit-shift-left", new CoreFn2(7, "bit-shift-left"));
        def(ns, "bit-shift-right", new CoreFn2(8, "bit-shift-right"));
        def(ns, "unsigned-bit-shift-right", new CoreFn2(9, "unsigned-bit-shift-right"));
        def(ns, "bit-test", new CoreFn2(10, "bit-test"));
        def(ns, "bit-set", new CoreFn2(11, "bit-set"));
        def(ns, "bit-clear", new CoreFn2(12, "bit-clear"));
        def(ns, "bit-flip", new CoreFn2(13, "bit-flip"));

        def(ns, "int", new CoreFn1(53, "int"));
        def(ns, "long", new CoreFn1(54, "long"));
        def(ns, "double", new CoreFn1(55, "double"));
        def(ns, "float", new CoreFn1(56, "float"));
        def(ns, "bigint", new CoreFn1(57, "bigint"));
        def(ns, "bigdec", new CoreFn1(58, "bigdec"));
        def(ns, "char", new CoreFn1(59, "char"));
    }

    // ========================================================================
    // Sequence Library
    // ========================================================================

    private static void initSequences(Namespace ns) {
        def(ns, "seq", new CoreFn1(60, "seq"));
        def(ns, "first", new CoreFn1(61, "first"));
        def(ns, "ffirst", new CoreFn1(62, "ffirst"));
        def(ns, "next", new CoreFn1(63, "next"));
        def(ns, "nfirst", new CoreFn1(64, "nfirst"));
        def(ns, "fnext", new CoreFn1(65, "fnext"));
        def(ns, "nnext", new CoreFn1(66, "nnext"));
        def(ns, "rest", new CoreFn1(67, "rest"));
        def(ns, "second", new CoreFn1(68, "second"));
        def(ns, "last", new CoreFn1(69, "last"));
        def(ns, "butlast", new CoreFn1(70, "butlast"));

        def(ns, "cons", new CoreFn2(14, "cons"));
        conjFn = new Fn.NamedFn("conj") {
            @Override public Object invoke() { return PersistentVector.EMPTY; }
            @Override public Object invoke(Object coll) { return coll; }
            @Override public Object invoke(Object coll, Object x) { return RT.conj(coll, x); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) return PersistentVector.EMPTY;
                Object coll = args[0];
                for (int i = 1; i < args.length; i++) coll = RT.conj(coll, args[i]);
                return coll;
            }
        };
        def(ns, "conj", conjFn);

        def(ns, "count", new CoreFn1(71, "count"));
        def(ns, "empty", new CoreFn1(72, "empty"));
        def(ns, "nth", new CoreFn23(15, 1, "nth"));
        def(ns, "nthnext", new CoreFn2(16, "nthnext"));
        def(ns, "nthrest", new CoreFn2(17, "nthrest"));

        def(ns, "range", new CoreFnV(16, "range"));

        def(ns, "repeat", new CoreFn12(73, 18, "repeat"));
        def(ns, "replicate", new CoreFn2(19, "replicate"));

        def(ns, "repeatedly", new CoreFn12(74, 20, "repeatedly"));

        def(ns, "iterate", new CoreFn2(21, "iterate"));
        def(ns, "cycle", new CoreFn1(75, "cycle"));

        def(ns, "take", new CoreTransducerFn1(3, "take", new CoreFn2(22, "take")));
        def(ns, "take-while", new CoreTransducerFn1(4, "take-while", new CoreFn2(23, "take-while")));
        def(ns, "take-nth", new CoreTransducerFn1(5, "take-nth", new CoreFn2(24, "take-nth")));
        def(ns, "take-last", new CoreFn2(25, "take-last"));

        def(ns, "drop", new CoreTransducerFn1(6, "drop", new CoreFn2(26, "drop")));
        def(ns, "drop-while", new CoreTransducerFn1(7, "drop-while", new CoreFn2(27, "drop-while")));
        def(ns, "drop-last", new CoreFn12(76, 28, "drop-last"));

        def(ns, "map", new CoreTransducerFn1(0, "map", new CoreFnV(17, "map")));

        def(ns, "mapv", new CoreFnV(18, "mapv"));

        def(ns, "mapcat", new CoreTransducerFn1(11, "mapcat", new CoreFnV(19, "mapcat")));

        def(ns, "map-indexed", new CoreTransducerFn1(10, "map-indexed", new CoreFn2(29, "map-indexed")));

        def(ns, "keep", new CoreTransducerFn1(8, "keep", new CoreFn2(30, "keep")));
        def(ns, "keep-indexed", new CoreTransducerFn1(9, "keep-indexed", new CoreFn2(31, "keep-indexed")));

        def(ns, "filter", new CoreTransducerFn1(1, "filter", new CoreFn2(32, "filter")));
        def(ns, "remove", new CoreTransducerFn1(2, "remove", new CoreFn2(33, "remove")));
        def(ns, "filterv", new CoreFn2(34, "filterv"));

        def(ns, "reduce", new CoreFn23(35, 2, "reduce"));

        def(ns, "reduce-kv", new CoreFn3(3, "reduce-kv"));

        def(ns, "reductions", new CoreFn23(36, 4, "reductions"));

        def(ns, "reduced", new CoreFn1(77, "reduced"));
        def(ns, "unreduced", new CoreFn1(78, "unreduced"));
        def(ns, "ensure-reduced", new CoreFn1(79, "ensure-reduced"));

        def(ns, "some", new CoreFn2(37, "some"));

        def(ns, "every?", new CoreFn2(38, "every?"));

        def(ns, "not-every?", new CoreFn2(39, "not-every?"));

        def(ns, "not-any?", new CoreFn2(40, "not-any?"));

        def(ns, "concat", new CoreFnV(20, "concat"));

        def(ns, "interleave", new CoreFnV(21, "interleave"));

        def(ns, "interpose", new CoreTransducerFn1(12, "interpose", new CoreFn2(41, "interpose")));

        def(ns, "partition", new CoreFnV(22, "partition"));

        def(ns, "partition-all", new CoreTransducerFn1(13, "partition-all", new CoreFn23(42, 5, "partition-all")));

        def(ns, "partitionv", new CoreFnV(23, "partitionv"));

        def(ns, "partitionv-all", new CoreTransducerFn1(14, "partitionv-all", new CoreFn23(43, 6, "partitionv-all")));

        def(ns, "partition-by", new CoreTransducerFn1(15, "partition-by", new CoreFn2(44, "partition-by")));

        def(ns, "split-at", new CoreFn2(45, "split-at"));

        def(ns, "splitv-at", new CoreFn2(46, "splitv-at"));

        def(ns, "split-with", new CoreFn2(47, "split-with"));

        def(ns, "group-by", new CoreFn2(48, "group-by"));

        def(ns, "frequencies", new CoreFn1(80, "frequencies"));

        def(ns, "distinct", new CoreTransducerFn0(0, "distinct", new CoreFn1(81, "distinct")));
        def(ns, "dedupe", new CoreTransducerFn0(1, "dedupe", new CoreFn1(82, "dedupe")));

        def(ns, "flatten", new CoreFn1(83, "flatten"));

        def(ns, "tree-seq", new CoreFn3(7, "tree-seq"));

        def(ns, "doall", new CoreFn12(84, 49, "doall"));
        def(ns, "dorun", new CoreFn12(85, 50, "dorun"));
        def(ns, "run!", new CoreFn2(51, "run!"));
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
        ISeq lr = LongRange.createOrNull(start, end, step);
        if (lr != null) return lr;
        return rangeLazySeq(start, end, step);
    }

    private static ISeq rangeLazySeq(long start, long end, long step) {
        if ((step > 0 && start >= end) || (step < 0 && start <= end) || (step == 0 && start == end)) {
            return PersistentList.EMPTY;
        }
        return new LazySeq(Fn.of0("range", () -> new Cons(start, rangeLazySeq(start + step, end, step))));
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
            if (sq instanceof IChunkedSeq cs) {
                IChunk c = cs.chunkedFirst();
                int cnt = c.count();
                if (n >= cnt) {
                    return new ChunkedCons(c, takeSeq(n - cnt, cs.chunkedNext()));
                }
                int takeN = (int) n;
                Object[] arr = new Object[takeN];
                for (int i = 0; i < takeN; i++) arr[i] = c.nth(i);
                return new ChunkedCons(new ArrayChunk(arr, 0, takeN), null);
            }
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
                if (s instanceof IChunkedSeq cs) {
                    IChunk c = cs.chunkedFirst();
                    int cnt = c.count();
                    if (k >= cnt) {
                        k -= cnt;
                        s = cs.chunkedNext();
                        continue;
                    }
                    int off = (int) k;
                    if (c instanceof ArrayChunk ac) {
                        return new ChunkedCons(new ArrayChunk(ac.array, ac.off + off, ac.end), cs.chunkedNext());
                    }
                    Object[] arr = new Object[cnt - off];
                    for (int i = off; i < cnt; i++) arr[i - off] = c.nth(i);
                    return new ChunkedCons(new ArrayChunk(arr, 0, arr.length), cs.chunkedNext());
                }
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

    private static final class Map1LazySeq extends LazySeq {
        private Fn f;
        private ISeq src;
        Map1LazySeq(Fn f, ISeq src) {
            super((PersistentHashMap) null);
            this.f = f;
            this.src = src;
        }
        @Override
        protected Object compute() {
            ISeq s = this.src;
            this.src = null;
            ISeq sq = s.seq();
            if (sq == null) return null;
            Fn fn = this.f;
            if (fn instanceof Compiler.CompiledFn cf) {
                fn = cf.ensureJit();
                this.f = fn;
            }
            if (sq instanceof LongRange lr) {
                int len = Math.min(LongRange.CHUNK_SIZE, lr.count());
                long v = lr.start;
                long step = lr.step;
                if (fn.isUnboxedLong1()) {
                    long[] larr = new long[len];
                    for (int i = 0; i < len; i++) {
                        larr[i] = fn.invokePrim_J_J(v);
                        v += step;
                    }
                    return new ChunkedCons(new LongArrayChunk(larr, 0, len), map1Seq(fn, lr.chunkedNext()));
                }
                Object[] arr = new Object[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = fn.invoke(Long.valueOf(v));
                    v += step;
                }
                return new ChunkedCons(new ArrayChunk(arr, 0, len), map1Seq(fn, lr.chunkedNext()));
            }
            if (sq instanceof IChunkedSeq cs) {
                IChunk c = cs.chunkedFirst();
                int n = c.count();
                if (c instanceof LongArrayChunk lac && fn.isUnboxedLong1()) {
                    long[] larr = new long[n];
                    long[] srcArr = lac.array;
                    int off = lac.off;
                    for (int i = 0; i < n; i++) larr[i] = fn.invokePrim_J_J(srcArr[off + i]);
                    return new ChunkedCons(new LongArrayChunk(larr, 0, n), map1Seq(fn, cs.chunkedNext()));
                }
                Object[] arr = new Object[n];
                if (c instanceof ArrayChunk ac && ac.array instanceof Object[] srcArr) {
                    int off = ac.off;
                    if (fn.isUnboxedLong1()) {
                        for (int i = 0; i < n; i++) {
                            Object elem = srcArr[off + i];
                            arr[i] = (elem instanceof Long lv)
                                    ? Long.valueOf(fn.invokePrim_J_J(lv.longValue()))
                                    : fn.invoke(elem);
                        }
                    } else {
                        for (int i = 0; i < n; i++) arr[i] = fn.invoke(srcArr[off + i]);
                    }
                } else {
                    for (int i = 0; i < n; i++) arr[i] = fn.invoke(c.nth(i));
                }
                return new ChunkedCons(new ArrayChunk(arr, 0, n), map1Seq(fn, cs.chunkedNext()));
            }
            return new Cons(fn.invoke(sq.first()), map1Seq(fn, sq.next()));
        }
    }

    private static ISeq map1Seq(Fn f, ISeq s) {
        if (s == null) return null;
        return new Map1LazySeq(f, s);
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
                if (sq instanceof IChunkedSeq cs) {
                    IChunk c = cs.chunkedFirst();
                    int n = c.count();
                    ChunkBuffer cb = new ChunkBuffer(n);
                    if (c instanceof ArrayChunk ac && ac.array instanceof Object[] src) {
                        int off = ac.off;
                        for (int i = 0; i < n; i++) {
                            Object res = f.invoke(src[off + i]);
                            if (res != null) cb.add(res);
                        }
                    } else {
                        for (int i = 0; i < n; i++) {
                            Object res = f.invoke(c.nth(i));
                            if (res != null) cb.add(res);
                        }
                    }
                    ISeq more = cs.chunkedNext();
                    if (cb.count() > 0) {
                        return new ChunkedCons(cb.chunk(), keepSeq(f, more));
                    }
                    sq = more;
                    continue;
                }
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

    private static final class FilterLazySeq extends LazySeq {
        private Fn pred;
        private ISeq src;
        private final boolean invert;
        FilterLazySeq(Fn pred, ISeq src, boolean invert) {
            super((PersistentHashMap) null);
            this.pred = pred;
            this.src = src;
            this.invert = invert;
        }
        @Override
        protected Object compute() {
            ISeq sq = this.src;
            this.src = null;
            Fn p = this.pred;
            if (p instanceof Compiler.CompiledFn cf) {
                p = cf.ensureJit();
                this.pred = p;
            }
            while (sq != null) {
                sq = sq.seq();
                if (sq == null) return null;
                if (sq instanceof LongRange lr) {
                    int len = Math.min(LongRange.CHUNK_SIZE, lr.count());
                    long[] lbuf = new long[len];
                    int cnt = 0;
                    long v = lr.start;
                    long step = lr.step;
                    for (int i = 0; i < len; i++) {
                        if (p.invokePrim_J_Z(v) ^ invert) lbuf[cnt++] = v;
                        v += step;
                    }
                    ISeq more = lr.chunkedNext();
                    if (cnt > 0) {
                        return new ChunkedCons(new LongArrayChunk(lbuf, 0, cnt), filterSeq(p, more, invert));
                    }
                    sq = more;
                    continue;
                }
                if (sq instanceof IChunkedSeq cs) {
                    IChunk c = cs.chunkedFirst();
                    int n = c.count();
                    if (c instanceof LongArrayChunk lac) {
                        long[] lbuf = new long[n];
                        int cnt = 0;
                        long[] srcArr = lac.array;
                        int off = lac.off;
                        for (int i = 0; i < n; i++) {
                            long lv = srcArr[off + i];
                            if (p.invokePrim_J_Z(lv) ^ invert) lbuf[cnt++] = lv;
                        }
                        ISeq more = cs.chunkedNext();
                        if (cnt > 0) {
                            return new ChunkedCons(new LongArrayChunk(lbuf, 0, cnt), filterSeq(p, more, invert));
                        }
                        sq = more;
                        continue;
                    }
                    Object[] buf = new Object[n];
                    int cnt = 0;
                    if (c instanceof ArrayChunk ac && ac.array instanceof Object[] srcArr) {
                        int off = ac.off;
                        for (int i = 0; i < n; i++) {
                            Object v = srcArr[off + i];
                            boolean ok = (v instanceof Long lv)
                                    ? p.invokePrim_J_Z(lv.longValue())
                                    : RT.truthy(p.invoke(v));
                            if (ok ^ invert) buf[cnt++] = v;
                        }
                    } else {
                        for (int i = 0; i < n; i++) {
                            Object v = c.nth(i);
                            boolean ok = (v instanceof Long lv)
                                    ? p.invokePrim_J_Z(lv.longValue())
                                    : RT.truthy(p.invoke(v));
                            if (ok ^ invert) buf[cnt++] = v;
                        }
                    }
                    ISeq more = cs.chunkedNext();
                    if (cnt > 0) {
                        return new ChunkedCons(new ArrayChunk(buf, 0, cnt), filterSeq(p, more, invert));
                    }
                    sq = more;
                    continue;
                }
                Object v = sq.first();
                boolean keep = RT.truthy(p.invoke(v)) ^ invert;
                if (keep) {
                    return new Cons(v, filterSeq(p, sq.next(), invert));
                }
                sq = sq.next();
            }
            return null;
        }
    }

    private static ISeq filterSeq(Fn pred, ISeq s, boolean invert) {
        if (s == null) return null;
        return new FilterLazySeq(pred, s, invert);
    }

    private static PersistentVector mapvVectorRange(Fn f, PersistentVector pv, int start, int end) {
        PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
        int i = start;
        while (i < end) {
            Object[] chunk = pv.arrayFor(i);
            int offset = i & 0x01f;
            int limit = Math.min(chunk.length, offset + (end - i));
            for (int j = offset; j < limit; j++) {
                tv = tv.conj(f.invoke(chunk[j]));
            }
            i += (limit - offset);
        }
        return tv.persistent();
    }

    private static PersistentVector filtervVectorRange(Fn p, PersistentVector pv, int start, int end) {
        PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
        int i = start;
        while (i < end) {
            Object[] chunk = pv.arrayFor(i);
            int offset = i & 0x01f;
            int limit = Math.min(chunk.length, offset + (end - i));
            for (int j = offset; j < limit; j++) {
                Object item = chunk[j];
                if (RT.truthy(p.invoke(item))) tv = tv.conj(item);
            }
            i += (limit - offset);
        }
        return tv.persistent();
    }

    private static Object reduceVectorRange(Fn f, Object acc, PersistentVector pv, int start, int end) {
        if (f == addFn) {
            return reduceAddVectorRange(acc, pv, start, end);
        }
        int i = start;
        while (i < end) {
            Object[] chunk = pv.arrayFor(i);
            int offset = i & 0x01f;
            int limit = Math.min(chunk.length, offset + (end - i));
            for (int j = offset; j < limit; j++) {
                acc = f.invoke(acc, chunk[j]);
                if (acc instanceof Reduced r) return r.deref();
            }
            i += (limit - offset);
        }
        return acc;
    }

    private static Object reduceAddVectorRange(Object acc, PersistentVector pv, int start, int end) {
        int i = start;
        if (acc instanceof Long lacc) {
            long sum = lacc;
            while (i < end) {
                Object[] chunk = pv.arrayFor(i);
                int offset = i & 0x01f;
                int limit = Math.min(chunk.length, offset + (end - i));
                for (int j = offset; j < limit; j++) {
                    Object v = chunk[j];
                    if (v instanceof Long lv) {
                        sum = Math.addExact(sum, lv);
                    } else {
                        acc = Numbers.add(sum, v);
                        return finishReduceAddVectorRange(acc, pv, i + (j - offset) + 1, end);
                    }
                }
                i += (limit - offset);
            }
            return Long.valueOf(sum);
        }
        return finishReduceAddVectorRange(acc, pv, start, end);
    }

    private static Object finishReduceAddVectorRange(Object acc, PersistentVector pv, int start, int end) {
        int i = start;
        while (i < end) {
            Object[] chunk = pv.arrayFor(i);
            int offset = i & 0x01f;
            int limit = Math.min(chunk.length, offset + (end - i));
            for (int j = offset; j < limit; j++) {
                acc = Numbers.add(acc, chunk[j]);
            }
            i += (limit - offset);
        }
        return acc;
    }

    private static Object reduceList(Fn f, Object acc, PersistentList pl) {
        if (f == addFn) {
            return reduceAddList(acc, pl);
        }
        PersistentList cur = pl;
        while (true) {
            acc = f.invoke(acc, cur.first);
            if (acc instanceof Reduced r) return r.deref();
            if (cur.rest instanceof PersistentList nextPl) {
                cur = nextPl;
            } else {
                for (ISeq s = cur.next(); s != null; s = s.next()) {
                    acc = f.invoke(acc, s.first());
                    if (acc instanceof Reduced r) return r.deref();
                }
                return acc;
            }
        }
    }

    private static Object reduceAddList(Object acc, PersistentList pl) {
        PersistentList cur = pl;
        if (acc instanceof Long lacc) {
            long sum = lacc;
            while (true) {
                Object v = cur.first;
                if (v instanceof Long lv) {
                    sum = Math.addExact(sum, lv);
                } else {
                    Object objAcc = Numbers.add(sum, v);
                    return finishReduceAddSeq(objAcc, cur.next());
                }
                if (cur.rest instanceof PersistentList nextPl) {
                    cur = nextPl;
                } else {
                    ISeq tail = cur.next();
                    return tail == null ? Long.valueOf(sum) : finishReduceAddSeq(Long.valueOf(sum), tail);
                }
            }
        }
        return finishReduceAddSeq(acc, cur);
    }

    private static Object finishReduceAddSeq(Object acc, ISeq s) {
        while (s != null) {
            acc = Numbers.add(acc, s.first());
            s = s.next();
        }
        return acc;
    }

    private static Object reduce2(Fn f, Object coll) {
        if (coll instanceof PersistentVector pv) {
            if (pv.cnt == 0) return f.invoke();
            return reduceVectorRange(f, pv.nth(0), pv, 1, pv.cnt);
        }
        if (coll instanceof PersistentVector.SubVec sv) {
            if (sv.count() == 0) return f.invoke();
            return reduceVectorRange(f, sv.nth(0), sv.v, sv.start + 1, sv.end);
        }
        if (coll instanceof LongRange lr) {
            return lr.reduce2(f);
        }
        if (coll instanceof ChunkedCons cc) {
            return cc.reduce2(f);
        }
        if (coll instanceof PersistentList pl) {
            Object acc = pl.first;
            if (pl.rest instanceof PersistentList nextPl) {
                return reduceList(f, acc, nextPl);
            }
            for (ISeq s = pl.next(); s != null; s = s.next()) {
                acc = f.invoke(acc, s.first());
                if (acc instanceof Reduced r) return r.deref();
            }
            return acc;
        }
        if (coll instanceof PersistentList.EmptyList || coll == null) {
            return f.invoke();
        }
        ISeq s = RT.seq(coll);
        if (s == null) return f.invoke();
        if (s instanceof LongRange lr) return lr.reduce2(f);
        if (s instanceof ChunkedCons cc) return cc.reduce2(f);
        Object acc = s.first();
        s = s.next();
        if (s == null) return acc;
        if (s instanceof IReduceInit ri) return ri.reduce(f, acc);
        while (s != null) {
            acc = f.invoke(acc, s.first());
            if (acc instanceof Reduced r) return r.deref();
            s = s.next();
        }
        return acc;
    }

    public static Object reduce3(Fn f, Object init, Object coll) {
        if (coll instanceof PersistentVector pv) {
            return reduceVectorRange(f, init, pv, 0, pv.cnt);
        }
        if (coll instanceof PersistentVector.SubVec sv) {
            return reduceVectorRange(f, init, sv.v, sv.start, sv.end);
        }
        if (coll instanceof IReduceInit ri) {
            return ri.reduce(f, init);
        }
        if (coll instanceof PersistentList pl) {
            return reduceList(f, init, pl);
        }
        if (coll instanceof PersistentList.EmptyList || coll == null) {
            return init;
        }
        ISeq s = RT.seq(coll);
        if (s == null) return init;
        if (s instanceof IReduceInit ri) {
            return ri.reduce(f, init);
        }
        Object acc = init;
        for (; s != null; s = s.next()) {
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

    private static ISeq partitionSeq(int n, int step, ISeq pad, ISeq s, boolean allowPartial, boolean asVector) {
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
            Object part = asVector ? PersistentVector.create(chunk) : PersistentList.create(chunk);
            return new Cons(part,
                    partitionSeq(n, step, pad, nextStart, allowPartial, asVector));
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
        def(ns, "list", new CoreFnV(24, "list"));
        def(ns, "list*", new CoreFnV(25, "list*"));

        def(ns, "vector", new CoreFnV(26, "vector"));
        def(ns, "vec", new CoreFn1(86, "vec"));

        def(ns, "hash-map", new CoreFnV(27, "hash-map"));
        def(ns, "array-map", new CoreFnV(28, "array-map"));
        def(ns, "hash-set", new CoreFnV(29, "hash-set"));
        def(ns, "set", new CoreFn1(87, "set"));

        def(ns, "into", new CoreFn23(52, 8, "into"));

        def(ns, "get", new CoreFn23(53, 9, "get"));
        def(ns, "get-in", new CoreFn23(54, 10, "get-in"));

        def(ns, "assoc", new Fn.NamedFn("assoc") {
            @Override public Object invoke(Object coll) { return coll; }
            @Override public Object invoke(Object coll, Object k, Object v) { return RT.assoc(coll, k, v); }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length < 1) throw arityError(args.length);
                Object coll = args[0];
                if ((args.length & 1) == 0) throw new IllegalArgumentException("assoc requires even number of key/value args");
                for (int i = 1; i < args.length; i += 2) {
                    coll = RT.assoc(coll, args[i], args[i + 1]);
                }
                return coll;
            }
        });

        def(ns, "assoc-in", new CoreFn3(11, "assoc-in"));

        def(ns, "dissoc", new Fn.NamedFn("dissoc") {
            @Override public Object invoke(Object coll) { return coll; }
            @Override public Object invoke(Object coll, Object k) { return RT.dissoc(coll, k); }
            @Override public Object invoke(Object coll, Object k1, Object k2) { return RT.dissoc(RT.dissoc(coll, k1), k2); }
            @Override public Object invoke(Object coll, Object k1, Object k2, Object k3) {
                return RT.dissoc(RT.dissoc(RT.dissoc(coll, k1), k2), k3);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length < 1) throw arityError(args.length);
                Object coll = args[0];
                for (int i = 1; i < args.length; i++) coll = RT.dissoc(coll, args[i]);
                return coll;
            }
        });

        def(ns, "update", new CoreFnV(30, "update"));

        def(ns, "update-in", new CoreFnV(31, "update-in"));

        def(ns, "find", new CoreFn2(55, "find"));
        def(ns, "contains?", new CoreFn2(56, "contains?"));
        def(ns, "keys", new CoreFn1(88, "keys"));
        def(ns, "vals", new CoreFn1(89, "vals"));
        def(ns, "key", new CoreFn1(90, "key"));
        def(ns, "val", new CoreFn1(91, "val"));

        def(ns, "select-keys", new CoreFn2(57, "select-keys"));

        def(ns, "merge", new CoreFnV(32, "merge"));

        def(ns, "merge-with", new CoreFnV(33, "merge-with"));

        def(ns, "zipmap", new CoreFn2(58, "zipmap"));

        def(ns, "peek", new CoreFn1(92, "peek"));

        def(ns, "pop", new CoreFn1(93, "pop"));

        def(ns, "subvec", new CoreFn23(59, 12, "subvec"));

        def(ns, "disj", new CoreFnV(34, "disj"));

        def(ns, "reverse", new CoreFn1(94, "reverse"));

        def(ns, "rseq", new CoreFn1(95, "rseq"));

        def(ns, "sort", new CoreFn12(96, 60, "sort"));

        def(ns, "sort-by", new CoreFn23(61, 13, "sort-by"));

        def(ns, "shuffle", new CoreFn1(97, "shuffle"));

        // Transients
        def(ns, "transient", new CoreFn1(98, "transient"));
        def(ns, "persistent!", new CoreFn1(99, "persistent!"));
        def(ns, "__destructure-map", new CoreFn1(100, "__destructure-map"));
        def(ns, "conj!", new Fn.NamedFn("conj!") {
            @Override public Object invoke() { return PersistentVector.EMPTY.asTransient(); }
            @Override public Object invoke(Object t) { return t; }
            @Override public Object invoke(Object t, Object x) {
                return RT.conjBang(t, x);
            }
        });
        def(ns, "assoc!", new Fn.NamedFn("assoc!") {
            @Override public Object invoke(Object t, Object k, Object v) {
                return RT.assocBang(t, k, v);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length < 3 || (args.length & 1) == 0) throw arityError(args.length);
                Object res = args[0];
                for (int i = 1; i < args.length; i += 2) {
                    res = RT.assocBang(res, args[i], args[i + 1]);
                }
                return res;
            }
        });
        def(ns, "dissoc!", new Fn.NamedFn("dissoc!") {
            @Override public Object invoke(Object t) { return t; }
            @Override public Object invoke(Object t, Object k) {
                return ((ITransientMap) t).without(k);
            }
            @Override public Object invoke(Object t, Object k1, Object k2) {
                return ((ITransientMap) t).without(k1).without(k2);
            }
            @Override public Object invoke(Object t, Object k1, Object k2, Object k3) {
                return ((ITransientMap) t).without(k1).without(k2).without(k3);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                ITransientMap tm = (ITransientMap) args[0];
                for (int i = 1; i < args.length; i++) tm = tm.without(args[i]);
                return tm;
            }
        });
        def(ns, "pop!", new CoreFn1(101, "pop!"));
        def(ns, "disj!", new Fn.NamedFn("disj!") {
            @Override public Object invoke(Object t) { return t; }
            @Override public Object invoke(Object t, Object k) {
                return ((ITransientSet) t).disjoin(k);
            }
            @Override public Object invoke(Object t, Object k1, Object k2) {
                return ((ITransientSet) t).disjoin(k1).disjoin(k2);
            }
            @Override public Object invoke(Object t, Object k1, Object k2, Object k3) {
                return ((ITransientSet) t).disjoin(k1).disjoin(k2).disjoin(k3);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                ITransientSet ts = (ITransientSet) args[0];
                for (int i = 1; i < args.length; i++) ts = ts.disjoin(args[i]);
                return ts;
            }
        });
    }

    private static final Fn CONJ_RF = new Fn.NamedFn("conj") {
        @Override public Object invoke() { return PersistentVector.EMPTY; }
        @Override public Object invoke(Object x) { return x; }
        @Override public Object invoke(Object a, Object b) { return RT.conj(a, b); }
    };
    private static final Fn CONJ_BANG_RF = new Fn.NamedFn("conj!") {
        @Override public Object invoke() { return PersistentVector.EMPTY.asTransient(); }
        @Override public Object invoke(Object x) { return x; }
        @Override public Object invoke(Object a, Object b) { return RT.conjBang(a, b); }
    };

    private static Object intoImpl(Object to, Fn xform, Object from) {
        if (to == null) to = PersistentList.EMPTY;
        if (xform == null) {
            if (to instanceof PersistentVector pv) {
                PersistentVector.TransientVector tv = pv.asTransient();
                if (from instanceof PersistentVector src) {
                    int i = 0;
                    while (i < src.cnt) {
                        Object[] chunk = src.arrayFor(i);
                        int limit = Math.min(chunk.length, src.cnt - i);
                        for (int j = 0; j < limit; j++) tv = tv.conj(chunk[j]);
                        i += limit;
                    }
                    return tv.persistent();
                }
                if (from instanceof LongRange lr) {
                    long v = lr.start;
                    long step = lr.step;
                    int n = lr.count();
                    for (int i = 0; i < n; i++) {
                        tv = tv.conj(Long.valueOf(v));
                        v += step;
                    }
                    return tv.persistent();
                }
                if (from instanceof IReduceInit ri) {
                    tv = (PersistentVector.TransientVector) ri.reduce(CONJ_BANG_RF, tv);
                    return tv.persistent();
                }
                ISeq s = RT.seq(from);
                if (s instanceof IReduceInit ri) {
                    tv = (PersistentVector.TransientVector) ri.reduce(CONJ_BANG_RF, tv);
                    return tv.persistent();
                }
                for (; s != null; s = s.next()) tv = tv.conj(s.first());
                return tv.persistent();
            }
            if (to instanceof PersistentHashMap pm) {
                PersistentHashMap.TransientHashMap tm = pm.asTransient();
                if (from instanceof IReduceInit ri) {
                    tm = (PersistentHashMap.TransientHashMap) ri.reduce(CONJ_BANG_RF, tm);
                    return tm.persistent();
                }
                ISeq s = RT.seq(from);
                if (s instanceof IReduceInit ri) {
                    tm = (PersistentHashMap.TransientHashMap) ri.reduce(CONJ_BANG_RF, tm);
                    return tm.persistent();
                }
                for (; s != null; s = s.next()) tm = (PersistentHashMap.TransientHashMap) RT.conjBang(tm, s.first());
                return tm.persistent();
            }
            if (to instanceof PersistentHashSet ps) {
                PersistentHashSet.TransientHashSet ts = ps.asTransient();
                if (from instanceof IReduceInit ri) {
                    ts = (PersistentHashSet.TransientHashSet) ri.reduce(CONJ_BANG_RF, ts);
                    return ts.persistent();
                }
                ISeq s = RT.seq(from);
                if (s instanceof IReduceInit ri) {
                    ts = (PersistentHashSet.TransientHashSet) ri.reduce(CONJ_BANG_RF, ts);
                    return ts.persistent();
                }
                for (; s != null; s = s.next()) ts = ts.conj(s.first());
                return ts.persistent();
            }
            return reduce3(CONJ_RF, to, from);
        }
        if (to instanceof PersistentVector pv) {
            Fn rf = (Fn) xform.invoke(CONJ_BANG_RF);
            Object acc = reduce3(rf, pv.asTransient(), from);
            acc = rf.invoke(acc);
            return ((PersistentVector.TransientVector) acc).persistent();
        }
        if (to instanceof PersistentHashMap pm) {
            Fn rf = (Fn) xform.invoke(CONJ_BANG_RF);
            Object acc = reduce3(rf, pm.asTransient(), from);
            acc = rf.invoke(acc);
            return ((PersistentHashMap.TransientHashMap) acc).persistent();
        }
        if (to instanceof PersistentHashSet ps) {
            Fn rf = (Fn) xform.invoke(CONJ_BANG_RF);
            Object acc = reduce3(rf, ps.asTransient(), from);
            acc = rf.invoke(acc);
            return ((PersistentHashSet.TransientHashSet) acc).persistent();
        }
        Fn rf = (Fn) xform.invoke(CONJ_RF);
        Object acc = reduce3(rf, to, from);
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
        Object k = ks != null ? ks.first() : null;
        ISeq rest = ks != null ? ks.next() : null;
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

        def(ns, "subs", new CoreFn23(62, 14, "subs"));

        def(ns, "format", new CoreFnV(35, "format"));

        def(ns, "name", new CoreFn1(102, "name"));
        def(ns, "namespace", new CoreFn1(103, "namespace"));
        def(ns, "symbol", new CoreFn12(104, 63, "symbol"));
        def(ns, "keyword", new CoreFn12(105, 64, "keyword"));
        def(ns, "find-keyword", new CoreFn12(106, 65, "find-keyword"));

        def(ns, "gensym", new CoreFnV(36, "gensym"));

        def(ns, "re-pattern", new CoreFn1(107, "re-pattern"));
        def(ns, "re-matcher", new CoreFn2(66, "re-matcher"));
        def(ns, "re-groups", new CoreFn1(108, "re-groups"));
        def(ns, "re-find", new CoreFn12(109, 67, "re-find"));
        def(ns, "re-matches", new CoreFn2(68, "re-matches"));
        def(ns, "re-seq", new CoreFn2(69, "re-seq"));

        // Printing & I/O
        def(ns, "pr-str", new CoreFnV(37, "pr-str"));
        def(ns, "prn-str", new CoreFnV(38, "prn-str"));
        def(ns, "print-str", new CoreFnV(39, "print-str"));
        def(ns, "println-str", new CoreFnV(40, "println-str"));

        def(ns, "pr", new CoreFnV(41, "pr"));
        def(ns, "prn", new CoreFnV(42, "prn"));
        def(ns, "print", new CoreFnV(43, "print"));
        def(ns, "println", new CoreFnV(44, "println"));
        def(ns, "printf", new CoreFnV(45, "printf"));
        def(ns, "newline", new CoreFn0(0, "newline"));
        def(ns, "flush", new CoreFn0(1, "flush"));
        def(ns, "read-line", new CoreFn0(2, "read-line"));
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
        def(ns, "read-string", new CoreFn12(110, 70, "read-string"));
        def(ns, "read+string", new Fn.NamedFn("read+string") {
            @Override public Object invoke() {
                return Reader.readFromStreamWithString((java.io.Reader) RT.IN.deref(), true, null, null);
            }
            @Override public Object invoke(Object stream) {
                return Reader.readFromStreamWithString((java.io.Reader) stream, true, null, null);
            }
            @Override public Object invoke(Object streamOrOpts, Object streamOrEofError) {
                if (streamOrOpts instanceof Map<?, ?> opts) {
                    return Reader.readFromStreamWithString(opts, (java.io.Reader) streamOrEofError);
                }
                return Reader.readFromStreamWithString((java.io.Reader) streamOrOpts, RT.truthy(streamOrEofError), null, null);
            }
            @Override public Object invoke(Object stream, Object eofErrorP, Object eofValue) {
                return Reader.readFromStreamWithString((java.io.Reader) stream, RT.truthy(eofErrorP), eofValue, null);
            }
            @Override public Object invoke(Object stream, Object eofErrorP, Object eofValue, Object recursiveP) {
                return Reader.readFromStreamWithString((java.io.Reader) stream, RT.truthy(eofErrorP), eofValue, null);
            }
        });
        def(ns, "slurp", new CoreFnV(46, "slurp"));
        def(ns, "spit", new CoreFn2(71, "spit"));
    }

    private static String slurpImpl(Object f, Charset charset) {
        try {
            if (f instanceof String s) {
                if (s.contains("://") || s.startsWith("file:") || s.startsWith("jar:")) {
                    return readUrl(URI.create(s).toURL(), charset);
                }
                try (InputStream in = new FileInputStream(s)) {
                    return new String(in.readAllBytes(), charset);
                }
            }
            if (f instanceof Path p) {
                try (InputStream in = new FileInputStream(p.toFile())) {
                    return new String(in.readAllBytes(), charset);
                }
            }
            if (f instanceof File file) {
                try (InputStream in = new FileInputStream(file)) {
                    return new String(in.readAllBytes(), charset);
                }
            }
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
                http.setRequestProperty("User-Agent", "Clojol/0.1");
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
        if (args.length == 0) return "";
        if (!readably && RT.hasCustomPrintMethod() && RT.PRINT_READABLY.get() != null) {
            Var.pushThreadBindings(Collections.singletonMap(RT.PRINT_READABLY, null));
            try {
                return joinPrintRaw(args, false);
            } finally {
                Var.popThreadBindings();
            }
        }
        return joinPrintRaw(args, readably);
    }

    private static String joinPrintRaw(Object[] args, boolean readably) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            RT.print(args[i], sb, readably);
        }
        return sb.toString();
    }

    private static void writeOut(String s, boolean newline) {
        Object out = RT.OUT.deref();
        boolean flushNl = newline && RT.truthy(RT.FLUSH_ON_NEWLINE.get());
        if (out instanceof Writer w) {
            try {
                if (!s.isEmpty()) w.write(s);
                if (newline) w.write(System.lineSeparator());
                if (flushNl) w.flush();
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }
    }

    // ========================================================================
    // Higher-order Functions
    // ========================================================================

    private static void initFunctional(Namespace ns) {
        def(ns, "identity", new CoreFn1(111, "identity"));
        def(ns, "constantly", new CoreFn1(112, "constantly"));
        def(ns, "complement", new CoreFn1(113, "complement"));

        def(ns, "apply", new CoreFnV(47, "apply"));

        def(ns, "partial", new CoreFnV(48, "partial"));

        def(ns, "comp", new CoreFnV(49, "comp"));

        def(ns, "juxt", new CoreFnV(50, "juxt"));

        def(ns, "fnil", new CoreFnV(51, "fnil"));

        def(ns, "memoize", new CoreFn1(114, "memoize"));

        def(ns, "trampoline", new CoreFnV(52, "trampoline"));

        def(ns, "every-pred", new CoreFnV(53, "every-pred"));

        def(ns, "some-fn", new CoreFnV(54, "some-fn"));
    }

    // ========================================================================
    // Concurrency, Atoms, Vars, Metadata
    // ========================================================================

    private static void initConcurrencyAndVars(Namespace ns) {
        def(ns, "atom", new CoreFnV(55, "atom"));

        def(ns, "deref", new Fn.NamedFn("deref") {
            @Override public Object invoke(Object ref) {
                return RT.deref(ref);
            }
            @Override public Object invoke(Object ref, Object timeoutMs, Object timeoutVal) {
                return RT.deref(ref, Numbers.toLong(timeoutMs), timeoutVal);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 1) return RT.deref(args[0]);
                if (args.length == 3) return RT.deref(args[0], Numbers.toLong(args[1]), args[2]);
                throw arityError(args.length);
            }
        });

        def(ns, "reset!", new CoreFn2(72, "reset!"));
        def(ns, "reset-vals!", new CoreFn2(73, "reset-vals!"));
        def(ns, "compare-and-set!", new CoreFn3(15, "compare-and-set!"));

        def(ns, "swap!", new Fn.NamedFn("swap!") {
            @Override public Object invoke(Object a, Object f) {
                return ((Atom) a).swap((Fn) f);
            }
            @Override public Object invoke(Object a, Object f, Object x) {
                return ((Atom) a).swap((Fn) f, x);
            }
            @Override public Object invoke(Object a, Object f, Object x, Object y) {
                return ((Atom) a).swap((Fn) f, x, y);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length < 2) throw arityError(args.length);
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
            }
        });

        def(ns, "swap-vals!", new CoreFnV(56, "swap-vals!"));

        def(ns, "volatile!", new CoreFn1(115, "volatile!"));
        def(ns, "vreset!", new CoreFn2(74, "vreset!"));
        defMacro(ns, "vswap!", new CoreFnV(57, "vswap!"));
        def(ns, "volatile?", new CoreFn1(116, "volatile?"));

        def(ns, "delay?", new CoreFn1(117, "delay?"));
        def(ns, "force", new CoreFn1(118, "force"));
        def(ns, "promise", new CoreFn0(3, "promise"));
        def(ns, "deliver", new CoreFn2(75, "deliver"));

        def(ns, "future-call", new CoreFn1(119, "future-call"));
        def(ns, "future?", new CoreFn1(120, "future?"));
        def(ns, "future-done?", new CoreFn1(121, "future-done?"));
        def(ns, "future-cancel", new CoreFn1(122, "future-cancel"));
        def(ns, "future-cancelled?", new CoreFn1(123, "future-cancelled?"));

        def(ns, "pmap", new CoreFnV(58, "pmap"));

        def(ns, "add-watch", new CoreFn3(16, "add-watch"));
        def(ns, "remove-watch", new CoreFn2(76, "remove-watch"));
        def(ns, "set-validator!", new CoreFn2(77, "set-validator!"));
        def(ns, "get-validator", new CoreFn1(124, "get-validator"));

        // Metadata & Vars
        def(ns, "meta", new CoreFn1(125, "meta"));
        def(ns, "with-meta", new CoreFn2(78, "with-meta"));
        def(ns, "vary-meta", new CoreFnV(59, "vary-meta"));

        def(ns, "var-get", new CoreFn1(126, "var-get"));
        def(ns, "var-set", new CoreFn2(79, "var-set"));
        def(ns, "alter-var-root", new CoreFnV(60, "alter-var-root"));
        def(ns, "bound?", new CoreFnV(61, "bound?"));
        def(ns, "find-var", new CoreFn1(127, "find-var"));
    }

    // ========================================================================
    // Namespaces, Eval, Loading, Multimethods
    // ========================================================================

    private static void initNamespacesAndEval(Namespace ns) {
        def(ns, "in-ns", new CoreFn1(128, "in-ns"));
        def(ns, "find-ns", new CoreFn1(129, "find-ns"));
        def(ns, "create-ns", new CoreFn1(130, "create-ns"));
        def(ns, "remove-ns", new CoreFn1(131, "remove-ns"));
        def(ns, "all-ns", new CoreFn0(4, "all-ns"));
        def(ns, "ns-name", new CoreFn1(132, "ns-name"));
        def(ns, "ns-map", new CoreFn1(133, "ns-map"));
        def(ns, "ns-publics", new CoreFn1(134, "ns-publics"));
        def(ns, "ns-aliases", new CoreFn1(135, "ns-aliases"));
        def(ns, "alias", new CoreFn2(80, "alias"));
        def(ns, "resolve", new CoreFn12(136, 85, "resolve"));
        def(ns, "refer", new CoreFnV(62, "refer"));
        def(ns, "import", new CoreFnV(63, "import"));

        def(ns, "require", new CoreFnV(64, "require"));
        def(ns, "use", new CoreFnV(65, "use"));

        def(ns, "eval", new CoreFn1(137, "eval"));
        def(ns, "load-string", new CoreFn1(138, "load-string"));
        def(ns, "load-file", new CoreFn1(139, "load-file"));

        def(ns, "macroexpand-1", new CoreFn1(140, "macroexpand-1"));
        def(ns, "macroexpand", new CoreFn1(141, "macroexpand"));
        def(ns, "special-symbol?", new CoreFn1(142, "special-symbol?"));
        def(ns, "destructure", new CoreFn1(143, "destructure"));
        def(ns, "seq-to-map-for-destructuring", new CoreFn1(144, "seq-to-map-for-destructuring"));
        def(ns, "add-classpath", new CoreFn1(145, "add-classpath"));
        def(ns, "unquote", new CoreFnV(66, "unquote"));
        def(ns, "unquote-splicing", new CoreFnV(67, "unquote-splicing"));

        // Multimethods
        def(ns, "methods", new CoreFn1(146, "methods"));
        def(ns, "get-method", new CoreFn2(81, "get-method"));
        def(ns, "remove-method", new CoreFn2(82, "remove-method"));

        Var printDocVar = ns.intern("print-doc", new CoreFn1(147, "print-doc"));
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
            if (v.count() >= 2 && !(v.nth(1) instanceof Keyword)) {
                String prefix = ((Symbol) v.nth(0)).name;
                for (int i = 1; i < v.count(); i++) {
                    Object child = v.nth(i);
                    if (child instanceof Symbol cs) {
                        processLibSpec(Symbol.intern(prefix + "." + cs.name), isUse);
                    } else if (child instanceof PersistentVector cv) {
                        Object[] arr = new Object[cv.count()];
                        arr[0] = Symbol.intern(prefix + "." + ((Symbol) cv.nth(0)).name);
                        for (int j = 1; j < cv.count(); j++) arr[j] = cv.nth(j);
                        processLibSpec(PersistentVector.createOwning(arr), isUse);
                    } else if (child instanceof ISeq nested && nested.first() instanceof Symbol nsym) {
                        processLibSpec(RT.cons(Symbol.intern(prefix + "." + nsym.name), nested.next()), isUse);
                    } else {
                        throw new IllegalArgumentException("Invalid child in prefix lib spec: " + child);
                    }
                }
                return;
            }
            Symbol libSym = (Symbol) v.nth(0);
            boolean reload = false;
            Symbol asAlias = null;
            Symbol asAliasOnly = null;
            boolean hasNonAliasOnlyOpt = false;
            ISeq referList = null;
            boolean referAll = false;
            Set<Symbol> exclude = new HashSet<>();
            Set<Symbol> only = null;
            Map<Symbol, Symbol> rename = new HashMap<>();

            for (int i = 1; i < v.count(); i++) {
                Object opt = v.nth(i);
                if (Keyword.intern("as").equals(opt)) {
                    asAlias = (Symbol) v.nth(++i);
                    hasNonAliasOnlyOpt = true;
                } else if (Keyword.intern("as-alias").equals(opt)) {
                    asAliasOnly = (Symbol) v.nth(++i);
                } else if (Keyword.intern("reload").equals(opt) || Keyword.intern("reload-all").equals(opt)) {
                    reload = true;
                    hasNonAliasOnlyOpt = true;
                } else if (Keyword.intern("refer").equals(opt) || Keyword.intern("refer-macros").equals(opt)) {
                    Object r = v.nth(++i);
                    if (Keyword.intern("all").equals(r)) referAll = true;
                    else referList = RT.seq(r);
                    hasNonAliasOnlyOpt = true;
                } else if (Keyword.intern("exclude").equals(opt)) {
                    for (ISeq s = RT.seq(v.nth(++i)); s != null; s = s.next()) exclude.add(((Symbol) s.first()).withMeta(null));
                    hasNonAliasOnlyOpt = true;
                } else if (Keyword.intern("only").equals(opt)) {
                    only = new HashSet<>();
                    for (ISeq s = RT.seq(v.nth(++i)); s != null; s = s.next()) only.add(((Symbol) s.first()).withMeta(null));
                    hasNonAliasOnlyOpt = true;
                } else if (Keyword.intern("rename").equals(opt)) {
                    for (ISeq s = RT.seq(v.nth(++i)); s != null; s = s.next()) {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        rename.put(((Symbol) e.getKey()).withMeta(null), ((Symbol) e.getValue()).withMeta(null));
                    }
                    hasNonAliasOnlyOpt = true;
                } else if (Keyword.intern("include-macros").equals(opt)) {
                    i++; // .cljc compatibility
                }
            }
            if (asAliasOnly != null && !hasNonAliasOnlyOpt && !isUse) {
                Namespace aliasTarget = Namespace.findOrCreate(libSym);
                RT.currentNS().addAlias(asAliasOnly, aliasTarget);
                return;
            }
            if (reload) LOADED_LIBS.remove(libSym.name);
            loadLib(libSym.name);
            Namespace target = Namespace.find(libSym);
            if (target == null) throw new RuntimeException("Namespace not found after require: " + libSym);
            if (asAlias != null) RT.currentNS().addAlias(asAlias, target);
            if (asAliasOnly != null) RT.currentNS().addAlias(asAliasOnly, target);
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

    public static void unloadLib(String nsName) {
        if (nsName != null) {
            LOADED_LIBS.remove(nsName);
        }
    }

    private static final ThreadLocal<Set<String>> LOADING_LIBS = new ThreadLocal<>();

    private static boolean isContribFallbackLib(String nsName) {
        return switch (nsName) {
            case "clojure.core.async",
                 "clojure.spec.alpha",
                 "clojure.data.priority-map",
                 "clojure.data.int-map",
                 "clojure.data.avl",
                 "flatland.ordered.map",
                 "flatland.ordered.set",
                 "flatland.useful.map" -> true;
            default -> false;
        };
    }

    public static void loadLib(String nsName) {
        if ("clojure.core".equals(nsName)) return;
        if (LOADED_LIBS.contains(nsName) && Namespace.find(nsName) != null) return;
        Set<String> loading = LOADING_LIBS.get();
        if (loading != null && loading.contains(nsName)) return;
        boolean contribFallback = isContribFallbackLib(nsName);
        if (!contribFallback && StdLib.ensureLoaded(nsName)) {
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
                    Var.pushThreadBindings(Map.of(RT.CURRENT_NS, savedNs));
                    if (loading == null) {
                        loading = new HashSet<>();
                        LOADING_LIBS.set(loading);
                    }
                    loading.add(nsName);
                    try {
                        String src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                        Compiler.load(src, rel + ext);
                        LOADED_LIBS.add(nsName);
                        return;
                    } finally {
                        loading.remove(nsName);
                        if (loading.isEmpty()) LOADING_LIBS.remove();
                        Var.popThreadBindings();
                    }
                }
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }
        if (contribFallback && StdLib.ensureLoaded(nsName)) {
            LOADED_LIBS.add(nsName);
            return;
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
                            Var.pushThreadBindings(Map.of(RT.CURRENT_NS, savedNs));
                            if (loading == null) {
                                loading = new HashSet<>();
                                LOADING_LIBS.set(loading);
                            }
                            loading.add(nsName);
                            try {
                                String src = Files.readString(p, StandardCharsets.UTF_8);
                                Compiler.load(src, cand);
                                LOADED_LIBS.add(nsName);
                                return;
                            } finally {
                                loading.remove(nsName);
                                if (loading.isEmpty()) LOADING_LIBS.remove();
                                Var.popThreadBindings();
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
        def(ns, "to-array", new CoreFn1(148, "to-array"));
        def(ns, "object-array", new CoreFn1(149, "object-array"));
        def(ns, "int-array", new CoreFn1(150, "int-array"));
        def(ns, "long-array", new CoreFn1(151, "long-array"));
        def(ns, "double-array", new CoreFn1(152, "double-array"));
        def(ns, "byte-array", new CoreFn1(153, "byte-array"));
        def(ns, "char-array", new CoreFn1(154, "char-array"));
        def(ns, "boolean-array", new CoreFn1(155, "boolean-array"));
        def(ns, "alength", new CoreFn1(156, "alength"));
        def(ns, "aget", new CoreFnV(68, "aget"));
        def(ns, "aset", new CoreFnV(69, "aset"));
        def(ns, "aclone", new CoreFn1(157, "aclone"));

        def(ns, "class", new CoreFn1(158, "class"));
        def(ns, "type", new CoreFn1(159, "type"));
        def(ns, "ex-info", new CoreFn23(83, 17, "ex-info"));
        def(ns, "ex-data", new CoreFn1(160, "ex-data"));
        def(ns, "ex-message", new CoreFn1(161, "ex-message"));
        def(ns, "ex-cause", new CoreFn1(162, "ex-cause"));
    }

    public static final class ExceptionInfo extends RuntimeException implements IExceptionInfo {
        private static final long serialVersionUID = 1L;
        public final Object data;

        public ExceptionInfo(String msg, Object data, Throwable cause) {
            super(msg, cause);
            if (data == null) {
                throw new IllegalArgumentException("Additional data must be non-nil.");
            }
            this.data = data;
        }

        @Override
        public Object getData() {
            return data;
        }

        @Override
        public String toString() {
            return "clojure.lang.ExceptionInfo: " + getMessage() + " " + RT.printString(data);
        }

        private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
            in.defaultReadObject();
            if (data == null) {
                throw new java.io.InvalidObjectException("ExceptionInfo data cannot be null");
            }
        }
    }

    // ========================================================================
    // Core Macros (Compiled directly in Java for instant startup)
    // ========================================================================

    private static final Symbol fnStar = Symbol.intern("fn*");
    private static final Symbol defSym = Symbol.intern("def");
    private static final Symbol letStar = Symbol.intern("let*");
    private static final Symbol loopStar = Symbol.intern("loop*");
    private static final Symbol ifSym = Symbol.intern("if");
    private static final Symbol doSym = Symbol.intern("do");
    private static final Symbol quoteSym = Symbol.intern("quote");

    private static void initMacros(Namespace ns) {
        // (defn name doc? attr-map? [params] body...)
        defMacro(ns, "defn", new CoreFnV(70, "defn"));

        Var docVar = ns.internMacro("doc", new CoreFnV(71, "doc"));
        docVar.setMeta(docVar.meta()
                .assoc(Keyword.intern("ns"), ns)
                .assoc(Keyword.intern("name"), Symbol.intern("doc"))
                .assoc(Keyword.intern("arglists"), PersistentList.createFromArray(
                        PersistentVector.createOwning(Symbol.intern("name"))))
                .assoc(Keyword.intern("doc"), "Prints documentation for a var or special form given its name"));

        defMacro(ns, "defn-", new CoreFnV(72, "defn-"));

        defMacro(ns, "fn", new CoreFnV(73, "fn"));

        defMacro(ns, "defonce", new CoreFn2(84, "defonce"));

        defMacro(ns, "declare", new CoreFnV(74, "declare"));

        defMacro(ns, "let", new CoreFnV(75, "let"));

        defMacro(ns, "loop", new CoreFnV(76, "loop"));

        defMacro(ns, "when", new CoreFnV(77, "when"));

        defMacro(ns, "when-not", new CoreFnV(78, "when-not"));

        defMacro(ns, "if-not", new CoreFnV(79, "if-not"));

        defMacro(ns, "when-let", new CoreFnV(80, "when-let"));

        defMacro(ns, "if-let", new CoreFnV(81, "if-let"));

        defMacro(ns, "when-some", new CoreFnV(82, "when-some"));

        defMacro(ns, "if-some", new CoreFnV(83, "if-some"));

        defMacro(ns, "when-first", new CoreFnV(84, "when-first"));

        defMacro(ns, "and", new CoreFnV(85, "and"));

        defMacro(ns, "or", new CoreFnV(86, "or"));

        defMacro(ns, "cond", new CoreFnV(87, "cond"));

        defMacro(ns, "condp", new CoreFnV(88, "condp"));

        defMacro(ns, "case", new CoreFnV(89, "case"));

        defMacro(ns, "->", new CoreFnV(90, "->"));

        defMacro(ns, "->>", new CoreFnV(91, "->>"));

        defMacro(ns, "as->", new CoreFnV(92, "as->"));

        defMacro(ns, "some->", new CoreFnV(93, "some->"));

        defMacro(ns, "some->>", new CoreFnV(94, "some->>"));

        defMacro(ns, "cond->", new CoreFnV(95, "cond->"));

        defMacro(ns, "cond->>", new CoreFnV(96, "cond->>"));

        defMacro(ns, "doto", new CoreFnV(97, "doto"));

        defMacro(ns, "..", new CoreFnV(98, ".."));

        // Lazy-seq, delay, future
        def(ns, "lazy-seq*", new CoreFn1(163, "lazy-seq*"));
        defMacro(ns, "lazy-seq", new CoreFnV(99, "lazy-seq"));

        def(ns, "delay*", new CoreFn1(164, "delay*"));
        defMacro(ns, "delay", new CoreFnV(100, "delay"));

        defMacro(ns, "future", new CoreFnV(101, "future"));

        defMacro(ns, "comment", new CoreFnV(102, "comment"));

        // Loops & comprehensions: dotimes, while, doseq, for
        defMacro(ns, "dotimes", new CoreFnV(103, "dotimes"));

        defMacro(ns, "while", new CoreFnV(104, "while"));

        defMacro(ns, "doseq", new CoreFnV(105, "doseq"));

        defMacro(ns, "for", new CoreFnV(106, "for"));

        // Binding & With-Open & With-Out-Str & Time & Assert
        def(ns, "push-thread-bindings", new CoreFn1(165, "push-thread-bindings"));
        def(ns, "pop-thread-bindings", new CoreFn0(5, "pop-thread-bindings"));

        defMacro(ns, "defmacro", new CoreFnV(107, "defmacro"));

        defMacro(ns, "refer-clojure", new CoreFnV(108, "refer-clojure"));

        def(ns, "with-bindings*", new CoreFnV(109, "with-bindings*"));

        defMacro(ns, "with-bindings", new CoreFnV(110, "with-bindings"));

        def(ns, "with-loading-context*", new CoreFn1(166, "with-loading-context*"));

        defMacro(ns, "with-loading-context", new CoreFnV(111, "with-loading-context"));

        defMacro(ns, "binding", new CoreFnV(112, "binding"));

        defMacro(ns, "with-out-str", new CoreFnV(113, "with-out-str"));

        defMacro(ns, "with-in-str", new CoreFnV(114, "with-in-str"));

        defMacro(ns, "with-open", new CoreFnV(115, "with-open"));

        defMacro(ns, "time", new CoreFn1(167, "time"));

        defMacro(ns, "assert", new CoreFnV(116, "assert"));

        // (ns foo.bar docstring? attr-map? (:refer-clojure ...) (:require ...) (:import ...))
        defMacro(ns, "ns", new CoreFnV(117, "ns"));

        // Multimethod macros: defmulti, defmethod
        def(ns, "create-multi", new Fn.NamedFn("create-multi") {
            @Override public Object invoke(Object name, Object dispatchFn, Object defaultVal) {
                return new MultiFn(name.toString(), (Fn) dispatchFn, defaultVal);
            }
            @Override public Object invoke(Object name, Object dispatchFn, Object defaultVal, Object hierarchy) {
                return new MultiFn(name.toString(), (Fn) dispatchFn, defaultVal, (cljl.lang.IDeref) hierarchy);
            }
        });
        def(ns, "add-method!", new CoreFn3(19, "add-method!"));

        defMacro(ns, "defmulti", new CoreFnV(118, "defmulti"));

        defMacro(ns, "defmethod", new CoreFnV(119, "defmethod"));

        // letfn macro
        defMacro(ns, "letfn", new CoreFnV(120, "letfn"));
    }

    private static Object expandDoseq(PersistentVector seqExprs, int idx, Object body) {
        if (idx >= seqExprs.count()) return body;
        Object k = seqExprs.nth(idx);
        Object v = seqExprs.nth(idx + 1);
        if (Keyword.intern("let").equals(k)) {
            return PersistentList.createFromArray(Symbol.intern("let*"), Compiler.destructure((PersistentVector) v), expandDoseq(seqExprs, idx + 2, body));
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
                                Compiler.destructure(PersistentVector.createOwning(k, PersistentList.createFromArray(Symbol.intern("clojure.core", "first"), s))),
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
            return PersistentList.createFromArray(Symbol.intern("let*"), Compiler.destructure((PersistentVector) v), expandFor(seqExprs, idx + 2, body));
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
                                                Compiler.destructure(PersistentVector.createOwning(k, PersistentList.createFromArray(Symbol.intern("clojure.core", "first"), s))),
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

    // ========================================================================
    // Compact Tableswitch Function Dispatchers (Zero LambdaMetafactory Overhead)
    // ========================================================================

    private static final class CoreFn0 extends Fn.NamedFn {
        private final int id;
        CoreFn0(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke() { return call0(id); }
    }

    private static final class CoreFn1 extends Fn.NamedFn {
        private final int id;
        CoreFn1(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke(Object a) { return call1(id, a); }
        @Override public boolean invokePrim_J_Z(long a) {
            return switch (id) {
                case 19 -> a == 0L;
                case 20 -> a > 0L;
                case 21 -> a < 0L;
                case 22 -> (a & 1L) == 0L;
                case 23 -> (a & 1L) != 0L;
                default -> RT.truthy(call1(id, Long.valueOf(a)));
            };
        }
    }

    private static final class CoreFn2 extends Fn.NamedFn {
        private final int id;
        CoreFn2(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke(Object a, Object b) { return call2(id, a, b); }
    }

    private static final class CoreFn3 extends Fn.NamedFn {
        private final int id;
        CoreFn3(int id, String name) { super(name); this.id = id; }
        @Override public Object invoke(Object a, Object b, Object c) { return call3(id, a, b, c); }
    }

    private static final class CoreFn12 extends Fn.NamedFn {
        private final int id1, id2;
        CoreFn12(int id1, int id2, String name) { super(name); this.id1 = id1; this.id2 = id2; }
        @Override public Object invoke(Object a) { return call1(id1, a); }
        @Override public Object invoke(Object a, Object b) { return call2(id2, a, b); }
    }

    private static final class CoreFn23 extends Fn.NamedFn {
        private final int id2, id3;
        CoreFn23(int id2, int id3, String name) { super(name); this.id2 = id2; this.id3 = id3; }
        @Override public Object invoke(Object a, Object b) { return call2(id2, a, b); }
        @Override public Object invoke(Object a, Object b, Object c) { return call3(id3, a, b, c); }
    }

    private static final class CoreFnV extends Fn.NamedFn {
        private final int id;
        CoreFnV(int id, String name) { super(name); this.id = id; }
        @Override public Object invokeVarargs(Object[] args) { return callV(id, args); }
    }

    private static final class CoreTransducerFn0 extends Fn.NamedFn {
        private final int xfId;
        private final Fn base;
        CoreTransducerFn0(int xfId, String name, Fn base) { super(name); this.xfId = xfId; this.base = base; }
        @Override public Object invoke() { return CoreExtras.createTransducer0(xfId, getName()); }
        @Override public Object invoke(Object a) { return base.invoke(a); }
        @Override public Object invokeVarargs(Object[] args) { return base.applyTo(args); }
    }

    private static final class CoreTransducerFn1 extends Fn.NamedFn {
        private final int xfId;
        private final Fn base;
        CoreTransducerFn1(int xfId, String name, Fn base) { super(name); this.xfId = xfId; this.base = base; }
        @Override public Object invoke(Object a) { return CoreExtras.createTransducer1(xfId, getName(), a); }
        @Override public Object invoke(Object a, Object b) { return base.invoke(a, b); }
        @Override public Object invoke(Object a, Object b, Object c) { return base.invoke(a, b, c); }
        @Override public Object invoke(Object a, Object b, Object c, Object d) { return base.invoke(a, b, c, d); }
        @Override public Object invokeVarargs(Object[] args) { return base.applyTo(args); }
    }

    @SuppressWarnings("unchecked")
    private static Object call0(int _id) {
        switch (_id) {
            case 0: {
                 writeOut("", true); return null; 
            }
            case 1: {
                
            Object out = RT.OUT.deref();
            if (out instanceof Flushable f) {
                try { f.flush(); } catch (IOException e) { throw RT.sneakyThrow(e); }
            }
            return null;
        
            }
            case 2: {
                
            try {
                Object in = RT.IN.deref();
                if (in instanceof LineNumberingPushbackReader lnr) {
                    return lnr.readLine();
                }
                if (in instanceof BufferedReader br) {
                    return br.readLine();
                }
                if (in instanceof java.io.Reader r) {
                    int c = r.read();
                    if (c == -1) return null;
                    StringBuilder sb = new StringBuilder();
                    while (c != -1 && c != '\n') {
                        if (c != '\r') sb.append((char) c);
                        c = r.read();
                    }
                    return sb.toString();
                }
                throw new ClassCastException("Cannot read-line from " + (in == null ? "nil" : in.getClass().getName()));
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        
            }
            case 3: return new Atom.Promise();
            case 4: return (PersistentList.create(new ArrayList<>(Namespace.all())));
            case 5: {
                
            Var.popThreadBindings();
            return null;
        
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
            case 4 -> call1_4(_id, _a);
            case 5 -> call1_5(_id, _a);
            default -> throw new IllegalStateException("Invalid dispatcher id: " + _id);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object call1_0(int _id, Object _a) {
        switch (_id) {
            case 0: { Object x = _a; return (x == null); }
            case 1: { Object x = _a; return (x != null); }
            case 2: { Object x = _a; return (x == Boolean.TRUE); }
            case 3: { Object x = _a; return (x == Boolean.FALSE); }
            case 4: { Object x = _a; return (x instanceof Boolean); }
            case 5: return RT.truthy(_a);
            case 6: { Object x = _a; return (!RT.truthy(x)); }
            case 7: { Object x = _a; return ((long) RT.hash(x)); }
            case 8: return Numbers.isNumber(_a);
            case 9: return Numbers.isInteger(_a);
            case 10: return Numbers.isInteger(_a);
            case 11: { Object x = _a; return (Numbers.isInteger(x) && Numbers.isPos(x)); }
            case 12: { Object x = _a; return (Numbers.isInteger(x) && !Numbers.isNeg(x)); }
            case 13: { Object x = _a; return (Numbers.isInteger(x) && Numbers.isNeg(x)); }
            case 14: { Object x = _a; return (x instanceof Double || x instanceof Float); }
            case 15: { Object x = _a; return (x instanceof Double); }
            case 16: { Object x = _a; return (x instanceof Ratio); }
            case 17: { Object x = _a; return (x instanceof BigDecimal); }
            case 18: { Object x = _a; return (Numbers.isInteger(x) || x instanceof Ratio || x instanceof BigDecimal); }
            case 19: return Numbers.isZero(_a);
            case 20: return Numbers.isPos(_a);
            case 21: return Numbers.isNeg(_a);
            case 22: {
                Object x = _a;
            if (!Numbers.isInteger(x)) throw new IllegalArgumentException("Argument must be an integer: " + x);
            return (Numbers.toLong(x) & 1L) == 0L;
        
            }
            case 23: {
                Object x = _a;
            if (!Numbers.isInteger(x)) throw new IllegalArgumentException("Argument must be an integer: " + x);
            return (Numbers.toLong(x) & 1L) != 0L;
        
            }
            case 24: { Object x = _a; return (x instanceof String); }
            case 25: { Object x = _a; return (x instanceof Character); }
            case 26: { Object x = _a; return (x instanceof Symbol); }
            case 27: { Object x = _a; return (x instanceof Keyword); }
            case 28: { Object x = _a; return (x instanceof Symbol s && s.ns == null); }
            case 29: { Object x = _a; return (x instanceof Symbol s && s.ns != null); }
            case 30: { Object x = _a; return (x instanceof Keyword k && k.getNamespace() == null); }
            case 31: { Object x = _a; return (x instanceof Keyword k && k.getNamespace() != null); }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_1(int _id, Object _a) {
        switch (_id) {
            case 32: { Object x = _a; return (x instanceof IPersistentList || x instanceof PersistentList || x instanceof PersistentList.EmptyList); }
            case 33: { Object x = _a; return RT.isVector(x); }
            case 34: { Object x = _a; return (x instanceof IPersistentMap || x instanceof PersistentHashMap || x instanceof Map<?, ?>); }
            case 35: { Object x = _a; return (x instanceof IPersistentSet || x instanceof PersistentHashSet || x instanceof Set<?>); }
            case 36: { Object x = _a; return (x instanceof ISeq); }
            case 37: { Object x = _a; return (x == null || x instanceof Seqable || x instanceof Iterable<?>
                || x instanceof Map<?, ?> || x instanceof CharSequence || x.getClass().isArray()); }
            case 38: { Object x = _a; return (x instanceof IPersistentVector || x instanceof IPersistentMap
                || x instanceof IPersistentSet || x instanceof IPersistentList
                || x instanceof PersistentVector || x instanceof PersistentHashMap
                || x instanceof PersistentHashSet || x instanceof PersistentTreeMap || x instanceof PersistentTreeSet
                || x instanceof PersistentRecord || x instanceof PersistentColl
                || x instanceof ISeq || x instanceof Collection<?>); }
            case 39: { Object x = _a; return (x instanceof Associative); }
            case 40: { Object x = _a; return (x instanceof Sequential || x instanceof ISeq || x instanceof PersistentVector || x instanceof List<?>); }
            case 41: { Object x = _a; return (x instanceof Counted); }
            case 42: { Object x = _a; return (x instanceof clojure.lang.IFn); }
            case 43: { Object x = _a; return (x instanceof Fn && !(x instanceof Keyword || x instanceof Symbol
                || x instanceof PersistentVector || x instanceof PersistentHashMap || x instanceof PersistentHashSet
                || x instanceof PersistentTreeMap || x instanceof PersistentTreeSet || x instanceof PersistentColl)); }
            case 44: { Object x = _a; return (x instanceof Var); }
            case 45: { Object x = _a; return (x instanceof Class<?>); }
            case 46: { Object x = _a; return (RT.seq(x) == null); }
            case 47: { Object x = _a; return (x instanceof Reduced); }
            case 48: {
                Object x = _a;
            if (x instanceof LazySeq ls) return ls.isRealized();
            if (x instanceof Atom.Delay d) return d.isRealized();
            if (x instanceof Atom.Promise p) return p.isRealized();
            if (x instanceof Atom.FutureObj f) return f.isDone();
            if (x instanceof java.util.concurrent.Future<?> f) return f.isDone();
            if (x instanceof IPending p) return p.isRealized();
            return true;
        
            }
            case 49: return Numbers.abs(_a);
            case 50: {
                Object n = _a;
            int bound = (int) Numbers.toLong(n);
            return bound <= 0 ? 0L : (long) ThreadLocalRandom.current().nextInt(bound);
        
            }
            case 51: {
                Object coll = _a;
            int n = RT.count(coll);
            if (n == 0) throw new IllegalArgumentException("Cannot choose rand-nth of empty collection");
            return RT.nth(coll, ThreadLocalRandom.current().nextInt(n));
        
            }
            case 52: return Numbers.bitNot(_a);
            case 53: { Object x = _a; return ((long) Numbers.intCast(x)); }
            case 54: return Numbers.longCast(_a);
            case 55: return Numbers.toDouble(_a);
            case 56: { Object x = _a; return ((double) ((Number) x).floatValue()); }
            case 57: return Numbers.toBigInteger(_a);
            case 58: return Numbers.toBigDecimal(_a);
            case 59: return Numbers.charCast(_a);
            case 60: return RT.seq(_a);
            case 61: return RT.first(_a);
            case 62: { Object x = _a; return (RT.first(RT.first(x))); }
            case 63: return RT.next(_a);
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_2(int _id, Object _a) {
        switch (_id) {
            case 64: { Object x = _a; return (RT.next(RT.first(x))); }
            case 65: { Object x = _a; return (RT.first(RT.next(x))); }
            case 66: { Object x = _a; return (RT.next(RT.next(x))); }
            case 67: return RT.more(_a);
            case 68: return RT.second(_a);
            case 69: {
                Object x = _a;
            ISeq s = RT.seq(x);
            if (s == null) return null;
            if (s instanceof PersistentVector.VecSeq) {
                // O(1) via seq? Not directly, walk cleanly
            }
            Object ret = s.first();
            while ((s = s.next()) != null) ret = s.first();
            return ret;
        
            }
            case 70: {
                Object x = _a;
            ISeq s = RT.seq(x);
            if (s == null || s.next() == null) return null;
            List<Object> items = new ArrayList<>();
            while (s.next() != null) {
                items.add(s.first());
                s = s.next();
            }
            return PersistentList.create(items);
        
            }
            case 71: { Object o = _a; return ((long) RT.count(o)); }
            case 72: {
                Object o = _a;
            if (o instanceof PersistentVector) return PersistentVector.EMPTY;
            if (o instanceof PersistentHashMap) return PersistentHashMap.EMPTY;
            if (o instanceof PersistentHashSet) return PersistentHashSet.EMPTY;
            if (o instanceof PersistentTreeMap ptm) return ptm.empty();
            if (o instanceof PersistentTreeSet pts) return pts.empty();
            if (o instanceof PersistentColl pc) return pc.empty();
            if (o instanceof ISeq) return PersistentList.EMPTY;
            if (o instanceof String) return "";
            return null;
        
            }
            case 73: { Object x = _a; return (repeatInfSeq(x)); }
            case 74: { Object f = _a; return (repeatedlyInfSeq((Fn) f)); }
            case 75: {
                Object coll = _a;
            ISeq s = RT.seq(coll);
            return s == null ? PersistentList.EMPTY : cycleSeq(s, s);
        
            }
            case 76: { Object coll = _a; return (dropLastSeq(1, coll)); }
            case 77: return new Reduced(_a);
            case 78: { Object x = _a; return (x instanceof Reduced r ? r.deref() : x); }
            case 79: { Object x = _a; return (x instanceof Reduced ? x : new Reduced(x)); }
            case 80: {
                Object coll = _a;
            Map<RT.EquivKey, Long> counts = new HashMap<>();
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                counts.merge(new RT.EquivKey(s.first()), 1L, Long::sum);
            }
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<RT.EquivKey, Long> e : counts.entrySet()) {
                ret = ret.assoc(e.getKey().val, e.getValue());
            }
            return ret.persistent();
        
            }
            case 81: { Object coll = _a; return (distinctSeq(RT.seq(coll), new HashSet<>())); }
            case 82: { Object coll = _a; return (dedupeSeq(RT.seq(coll), new Object(), true)); }
            case 83: {
                Object x = _a;
            List<Object> out = new ArrayList<>();
            flattenInto(x, out);
            return PersistentList.create(out);
        
            }
            case 84: {
                Object coll = _a; dorun(RT.seq(coll)); return coll; 
            }
            case 85: {
                Object coll = _a; dorun(RT.seq(coll)); return null; 
            }
            case 86: {
                Object coll = _a;
                if (coll == null) return PersistentVector.EMPTY;
                if (coll instanceof PersistentVector pv) return pv;
                if (coll instanceof LongRange lr) return PersistentVector.createFromLongRange(lr);
                if (coll instanceof LongArraySeq las) return PersistentVector.createFromLongArraySeq(las);
                return PersistentVector.createFromISeq(RT.seq(coll));
            }
            case 87: {
                Object coll = _a;
            if (coll == null) return PersistentHashSet.EMPTY;
            if (coll instanceof PersistentHashSet ps) return ps;
            return PersistentHashSet.createFromISeq(RT.seq(coll));
        
            }
            case 88: return RT.keys(_a);
            case 89: return RT.vals(_a);
            case 90: { Object e = _a; return (((Map.Entry<?, ?>) e).getKey()); }
            case 91: { Object e = _a; return (((Map.Entry<?, ?>) e).getValue()); }
            case 92: {
                Object coll = _a;
            if (coll == null) return null;
            if (coll instanceof PersistentVector pv) return pv.peek();
            if (coll instanceof PersistentList pl) return pl.peek();
            if (coll instanceof PersistentList.EmptyList) return null;
            if (coll instanceof PersistentColl pc) return pc.peek();
            return RT.first(coll);
        
            }
            case 93: return RT.pop(_a);
            case 94: {
                Object coll = _a;
            if (coll instanceof PersistentList pl) {
                PersistentList ret = new PersistentList(null, pl.first, null, 1);
                if (pl.rest instanceof PersistentList nextPl) {
                    PersistentList cur = nextPl;
                    while (true) {
                        ret = ret.cons(cur.first);
                        if (cur.rest instanceof PersistentList n) {
                            cur = n;
                        } else {
                            ISeq rem = cur.next();
                            if (rem == null) return ret;
                            ISeq sRet = ret;
                            for (; rem != null; rem = rem.next()) sRet = sRet.cons(rem.first());
                            return sRet;
                        }
                    }
                }
                ISeq sRet = ret;
                for (ISeq rem = pl.next(); rem != null; rem = rem.next()) sRet = sRet.cons(rem.first());
                return sRet;
            }
            if (coll instanceof PersistentVector pv) {
                if (pv.cnt == 0) return PersistentList.EMPTY;
                PersistentList ret = null;
                int i = 0;
                while (i < pv.cnt) {
                    Object[] chunk = pv.arrayFor(i);
                    int limit = Math.min(chunk.length, pv.cnt - i);
                    for (int j = 0; j < limit; j++) {
                        ret = ret == null ? new PersistentList(null, chunk[j], null, 1) : ret.cons(chunk[j]);
                    }
                    i += limit;
                }
                return ret;
            }
            ISeq ret = PersistentList.EMPTY;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                ret = ret.cons(s.first());
            }
            return ret;
        
            }
            case 95: {
                Object coll = _a;
            if (coll instanceof Reversible rev) return rev.rseq();
            return RT.seq(coll);
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_3(int _id, Object _a) {
        switch (_id) {
            case 96: {
                return FastSort.sort(_a);
            }
            case 97: {
                Object coll = _a;
            List<Object> list = new ArrayList<>(Arrays.asList(RT.toArray(coll)));
            Collections.shuffle(list);
            return PersistentVector.create(list);
        
            }
            case 98: return RT.transientColl(_a);
            case 99: return RT.persistentBang(_a);
            case 100: return RT.destructureMap(_a);
            case 101: { Object t = _a; return (((ITransientVector) t).pop()); }
            case 102: return RT.name(_a);
            case 103: return RT.namespace(_a);
            case 104: { Object s = _a; return (s instanceof Symbol sym ? sym : Symbol.intern((String) s)); }
            case 105: {
                Object k = _a;
                    if (k instanceof Keyword kw) return kw;
                    if (k instanceof Symbol s) return Keyword.intern(s);
                    if (k instanceof String str) return Keyword.intern(str);
                    return null;
                
            }
            case 106: { Object k = _a; return (k instanceof Keyword kw ? kw : Keyword.find(null, k.toString())); }
            case 107: { Object s = _a; return (s instanceof Pattern p ? p : Pattern.compile((String) s)); }
            case 108: {
                Object m = _a;
            Matcher mat = (Matcher) m;
            int gc = mat.groupCount();
            if (gc == 0) return mat.group();
            Object[] ret = new Object[gc + 1];
            for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
            return PersistentVector.createOwning(ret);
        
            }
            case 109: {
                Object m = _a;
                    Matcher mat = (Matcher) m;
                    if (!mat.find()) return null;
                    int gc = mat.groupCount();
                    if (gc == 0) return mat.group();
                    Object[] ret = new Object[gc + 1];
                    for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
                    return PersistentVector.createOwning(ret);
                
            }
            case 110: { Object s = _a; return (Reader.readString((String) s)); }
            case 111: { Object x = _a; return (x); }
            case 112: { Object x = _a; return (Fn.ofVarargs("constantly", args -> x)); }
            case 113: {
                Object f = _a;
            Fn fn = (Fn) f;
            return Fn.ofVarargs("complement", args -> !RT.truthy(fn.applyTo(args)));
        
            }
            case 114: {
                Object f = _a;
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
        
            }
            case 115: return new Atom.Volatile(_a);
            case 116: { Object x = _a; return (x instanceof Atom.Volatile); }
            case 117: { Object x = _a; return (x instanceof Atom.Delay); }
            case 118: { Object x = _a; return (x instanceof Atom.Delay d ? d.deref() : x); }
            case 119: { Object f = _a; return (new Atom.FutureObj((Fn) f)); }
            case 120: { Object x = _a; return (x instanceof Atom.FutureObj); }
            case 121: { Object x = _a; return (((Atom.FutureObj) x).isDone()); }
            case 122: { Object x = _a; return (((Atom.FutureObj) x).cancel(true)); }
            case 123: { Object x = _a; return (((Atom.FutureObj) x).isCancelled()); }
            case 124: {
                Object ref = _a;
            if (ref instanceof Atom a) return a.getValidator();
            if (ref instanceof Agent ag) return ag.getValidator();
            if (ref instanceof Ref r) return r.getValidator();
            if (ref instanceof IRef ir) return ir.getValidator();
            return null;
        
            }
            case 125: { Object x = _a; return (x instanceof IMeta m ? m.meta() : null); }
            case 126: { Object v = _a; return (((Var) v).deref()); }
            case 127: {
                Object sym = _a;
            Symbol s = (Symbol) sym;
            Namespace targetNs = Namespace.find(s.ns);
            return targetNs != null ? targetNs.findInternedVar(Symbol.intern(s.name)) : null;
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_4(int _id, Object _a) {
        switch (_id) {
            case 128: {
                Object name = _a;
            Namespace target = Namespace.findOrCreate((Symbol) name);
            if (RT.CURRENT_NS.isThreadBound()) RT.CURRENT_NS.set(target);
            else RT.CURRENT_NS.bindRoot(target);
            return target;
        
            }
            case 129: { Object name = _a; return (Namespace.find((Symbol) name)); }
            case 130: { Object name = _a; return (Namespace.findOrCreate((Symbol) name)); }
            case 131: { Object name = _a; return (Namespace.remove((Symbol) name)); }
            case 132: { Object x = _a; return (x instanceof Namespace n ? n.name : Namespace.find((Symbol) x).name); }
            case 133: {
                Object x = _a;
            Namespace target = x instanceof Namespace n ? n : Namespace.find((Symbol) x);
            return PersistentHashMap.createFromMap(target.getMappings());
        
            }
            case 134: {
                Object x = _a;
            Namespace target = x instanceof Namespace n ? n : Namespace.find((Symbol) x);
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Symbol, Object> e : target.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns == target && !RT.truthy(v.meta().valAt(Keyword.intern("private")))) {
                    ret = ret.assoc(e.getKey(), v);
                }
            }
            return ret.persistent();
        
            }
            case 135: {
                Object x = _a;
            Namespace target = x instanceof Namespace n ? n : Namespace.find((Symbol) x);
            return PersistentHashMap.createFromMap(target.getAliases());
        
            }
            case 136: {
                Object sym = _a;
            Symbol s = (Symbol) sym;
            Var v = Compiler.lookupVar(RT.currentNS(), s, false);
            if (v != null) return v;
            return Interop.resolveClass(RT.currentNS(), s.toString());
        
            }
            case 137: return Compiler.eval(_a);
            case 138: { Object s = _a; return (Compiler.load((String) s, "<load-string>")); }
            case 139: {
                Object path = _a;
            try {
                String src = Files.readString(Path.of(path.toString()), StandardCharsets.UTF_8);
                return Compiler.load(src, path.toString());
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        
            }
            case 140: return Compiler.macroexpand1(_a);
            case 141: return Compiler.macroexpand(_a);
            case 142: { Object x = _a; return (x instanceof Symbol sym && sym.ns == null && Compiler.isSpecial(sym)); }
            case 143: { Object b = _a; return (Compiler.destructure((PersistentVector) b)); }
            case 144: return RT.destructureMap(_a);
            case 145: return RT.addClasspath(_a);
            case 146: { Object mf = _a; return (((MultiFn) mf).getMethodTable()); }
            case 147: return Core.printDocValue(_a);
            case 148: return RT.toArray(_a);
            case 149: { Object x = _a; return (x instanceof Number n ? new Object[n.intValue()] : RT.toArray(x)); }
            case 150: {
                Object x = _a;
            if (x instanceof Number n) return new int[n.intValue()];
            Object[] arr = RT.toArray(x);
            int[] out = new int[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).intValue();
            return out;
        
            }
            case 151: {
                Object x = _a;
            if (x instanceof Number n) return new long[n.intValue()];
            Object[] arr = RT.toArray(x);
            long[] out = new long[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).longValue();
            return out;
        
            }
            case 152: {
                Object x = _a;
            if (x instanceof Number n) return new double[n.intValue()];
            Object[] arr = RT.toArray(x);
            double[] out = new double[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).doubleValue();
            return out;
        
            }
            case 153: {
                Object x = _a;
            if (x instanceof Number n) return new byte[n.intValue()];
            Object[] arr = RT.toArray(x);
            byte[] out = new byte[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = ((Number) arr[i]).byteValue();
            return out;
        
            }
            case 154: {
                Object x = _a;
            if (x instanceof Number n) return new char[n.intValue()];
            if (x instanceof String s) return s.toCharArray();
            Object[] arr = RT.toArray(x);
            char[] out = new char[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = (Character) arr[i];
            return out;
        
            }
            case 155: {
                Object x = _a;
            if (x instanceof Number n) return new boolean[n.intValue()];
            Object[] arr = RT.toArray(x);
            boolean[] out = new boolean[arr.length];
            for (int i = 0; i < arr.length; i++) out[i] = RT.truthy(arr[i]);
            return out;
        
            }
            case 156: { Object a = _a; return ((long) RT.alength(a)); }
            case 157: {
                Object a = _a;
            int len = Array.getLength(a);
            Object copy = Array.newInstance(a.getClass().getComponentType(), len);
            System.arraycopy(a, 0, copy, 0, len);
            return copy;
        
            }
            case 158: { Object x = _a; return (x == null ? null : x.getClass()); }
            case 159: {
                Object x = _a;
            if (x instanceof IMeta m && m.meta() != null) {
                Object t = m.meta().valAt(Keyword.intern("type"));
                if (t != null) return t;
            }
            return x == null ? null : x.getClass();
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call1_5(int _id, Object _a) {
        switch (_id) {
            case 160: { Object ex = _a; return (ex instanceof IExceptionInfo ei ? ei.getData() : null); }
            case 161: { Object ex = _a; return (((Throwable) ex).getMessage()); }
            case 162: { Object ex = _a; return (((Throwable) ex).getCause()); }
            case 163: { Object f = _a; return (new LazySeq((Fn) f)); }
            case 164: { Object f = _a; return (new Atom.Delay((Fn) f)); }
            case 165: {
                Object m = _a;
            Map<Var, Object> map = new HashMap<>();
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                map.put((Var) e.getKey(), e.getValue());
            }
            Var.pushThreadBindings(map);
            return null;
        
            }
            case 166: {
                Object fnObj = _a;
            ClassLoader savedCl = Thread.currentThread().getContextClassLoader();
            Var.pushThreadBindings(Collections.emptyMap());
            try {
                return ((Fn) fnObj).invoke();
            } finally {
                Var.popThreadBindings();
                Thread.currentThread().setContextClassLoader(savedCl);
            }
        
            }
            case 167: {
                Object expr = _a;
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
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    private static Object call2(int _id, Object _a, Object _b) {
        return switch (_id >>> 5) {
            case 0 -> call2_0(_id, _a, _b);
            case 1 -> call2_1(_id, _a, _b);
            case 2 -> call2_2(_id, _a, _b);
            default -> throw new IllegalStateException("Invalid dispatcher id: " + _id);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object call2_0(int _id, Object _a, Object _b) {
        switch (_id) {
            case 0: { Object a = _a; Object b = _b; return (a == b); }
            case 1: { Object a = _a; Object b = _b; return ((long) RT.compare(a, b)); }
            case 2: { Object c = _a; Object x = _b; return (((Class<?>) c).isInstance(x)); }
            case 3: return CoreExtras.isa(_a, _b);
            case 4: return Numbers.quotient(_a, _b);
            case 5: return Numbers.remainder(_a, _b);
            case 6: return Numbers.mod(_a, _b);
            case 7: return Numbers.bitShiftLeft(_a, _b);
            case 8: return Numbers.bitShiftRight(_a, _b);
            case 9: return Numbers.unsignedBitShiftRight(_a, _b);
            case 10: return Numbers.bitTest(_a, _b);
            case 11: return Numbers.bitSet(_a, _b);
            case 12: return Numbers.bitClear(_a, _b);
            case 13: return Numbers.bitFlip(_a, _b);
            case 14: return RT.cons(_a, _b);
            case 15: { Object c = _a; Object i = _b; return (RT.nth(c, (int) Numbers.toLong(i))); }
            case 16: {
                Object coll = _a; Object n = _b;
            long k = Numbers.toLong(n);
            ISeq s = RT.seq(coll);
            while (s != null && k > 0) {
                s = s.next();
                k--;
            }
            return s;
        
            }
            case 17: {
                Object coll = _a; Object n = _b;
            long k = Numbers.toLong(n);
            ISeq s = RT.seq(coll);
            while (s != null && k > 0) {
                s = s.next();
                k--;
            }
            return s == null ? PersistentList.EMPTY : s;
        
            }
            case 18: { Object n = _a; Object x = _b; return (repeatNSeq(Numbers.toLong(n), x)); }
            case 19: { Object n = _a; Object x = _b; return (repeatNSeq(Numbers.toLong(n), x)); }
            case 20: { Object n = _a; Object f = _b; return (repeatedlyNSeq(Numbers.toLong(n), (Fn) f)); }
            case 21: { Object f = _a; Object x = _b; return (iterateSeq((Fn) f, x)); }
            case 22: { Object n = _a; Object coll = _b; return (takeSeq(Numbers.toLong(n), RT.seq(coll))); }
            case 23: { Object pred = _a; Object coll = _b; return (takeWhileSeq((Fn) pred, RT.seq(coll))); }
            case 24: {
                Object n = _a; Object coll = _b;
            long step = Numbers.toLong(n);
            if (step <= 0) return repeatInfSeq(RT.first(coll));
            return takeNthSeq(step, RT.seq(coll));
        
            }
            case 25: {
                Object n = _a; Object coll = _b;
            int k = (int) Numbers.toLong(n);
            if (k <= 0) return null;
            ArrayDeque<Object> q = new ArrayDeque<>(k);
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (q.size() == k) q.removeFirst();
                q.addLast(s.first());
            }
            return q.isEmpty() ? null : PersistentList.create(new ArrayList<>(q));
        
            }
            case 26: { Object n = _a; Object coll = _b; return (dropSeq(Numbers.toLong(n), coll)); }
            case 27: { Object pred = _a; Object coll = _b; return (dropWhileSeq((Fn) pred, coll)); }
            case 28: { Object n = _a; Object coll = _b; return (dropLastSeq((int) Numbers.toLong(n), coll)); }
            case 29: { Object f = _a; Object coll = _b; return (mapIndexedSeq((Fn) f, 0L, RT.seq(coll))); }
            case 30: { Object f = _a; Object coll = _b; return (keepSeq((Fn) f, RT.seq(coll))); }
            case 31: { Object f = _a; Object coll = _b; return (keepIndexedSeq((Fn) f, 0L, RT.seq(coll))); }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call2_1(int _id, Object _a, Object _b) {
        switch (_id) {
            case 32: { Object pred = _a; Object coll = _b; return (filterSeq((Fn) pred, RT.seq(coll), false)); }
            case 33: { Object pred = _a; Object coll = _b; return (filterSeq((Fn) pred, RT.seq(coll), true)); }
            case 34: {
                Object pred = _a; Object coll = _b;
            Fn p = (Fn) pred;
            if (coll instanceof PersistentVector pv) {
                return filtervVectorRange(p, pv, 0, pv.cnt);
            }
            if (coll instanceof PersistentVector.SubVec sv) {
                return filtervVectorRange(p, sv.v, sv.start, sv.end);
            }
            if (coll instanceof PersistentList.EmptyList || coll == null) {
                return PersistentVector.EMPTY;
            }
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            if (coll instanceof PersistentList pl) {
                PersistentList cur = pl;
                while (true) {
                    Object item = cur.first;
                    if (RT.truthy(p.invoke(item))) tv = tv.conj(item);
                    if (cur.rest instanceof PersistentList nextPl) {
                        cur = nextPl;
                    } else {
                        for (ISeq s = cur.next(); s != null; s = s.next()) {
                            Object it = s.first();
                            if (RT.truthy(p.invoke(it))) tv = tv.conj(it);
                        }
                        return tv.persistent();
                    }
                }
            }
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                Object item = s.first();
                if (RT.truthy(p.invoke(item))) tv = tv.conj(item);
            }
            return tv.persistent();
        
            }
            case 35: { Object f = _a; Object coll = _b; return (reduce2((Fn) f, coll)); }
            case 36: {
                Object f = _a; Object coll = _b;
                    ISeq s = RT.seq(coll);
                    if (s == null) return PersistentList.createFromArray(((Fn) f).invoke());
                    return reductionsSeq((Fn) f, s.first(), s.next());
                
            }
            case 37: {
                Object pred = _a; Object coll = _b;
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                Object res = p.invoke(s.first());
                if (RT.truthy(res)) return res;
            }
            return null;
        
            }
            case 38: {
                Object pred = _a; Object coll = _b;
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (!RT.truthy(p.invoke(s.first()))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        
            }
            case 39: {
                Object pred = _a; Object coll = _b;
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (!RT.truthy(p.invoke(s.first()))) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        
            }
            case 40: {
                Object pred = _a; Object coll = _b;
            Fn p = (Fn) pred;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                if (RT.truthy(p.invoke(s.first()))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        
            }
            case 41: {
                Object sep = _a; Object coll = _b;
            ISeq s = RT.seq(coll);
            if (s == null) return PersistentList.EMPTY;
            return interposeSeq(sep, s, true);
        
            }
            case 42: { Object n = _a; Object coll = _b; return (partitionSeq((int) Numbers.toLong(n), (int) Numbers.toLong(n), null, RT.seq(coll), true, false)); }
            case 43: { Object n = _a; Object coll = _b; return (partitionSeq((int) Numbers.toLong(n), (int) Numbers.toLong(n), null, RT.seq(coll), true, true)); }
            case 44: { Object f = _a; Object coll = _b; return (partitionBySeq((Fn) f, RT.seq(coll))); }
            case 45: {
                Object n = _a; Object coll = _b;
            long k = Numbers.toLong(n);
            return PersistentVector.createOwning(takeSeq(k, RT.seq(coll)), dropSeq(k, coll));
        
            }
            case 46: {
                Object n = _a; Object coll = _b;
            long k = Numbers.toLong(n);
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            ISeq s = RT.seq(coll);
            for (long i = 0; i < k && s != null; i++) {
                tv = tv.conj(s.first());
                s = s.next();
            }
            return PersistentVector.createOwning(tv.persistent(), s == null ? PersistentList.EMPTY : s);
        
            }
            case 47: { Object pred = _a; Object coll = _b; return (PersistentVector.createOwning(takeWhileSeq((Fn) pred, RT.seq(coll)), dropWhileSeq((Fn) pred, coll))); }
            case 48: {
                Object f = _a; Object coll = _b;
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
        
            }
            case 49: {
                Object n = _a; Object coll = _b; dorun((int) Numbers.toLong(n), RT.seq(coll)); return coll; 
            }
            case 50: {
                Object n = _a; Object coll = _b; dorun((int) Numbers.toLong(n), RT.seq(coll)); return null; 
            }
            case 51: {
                Object proc = _a; Object coll = _b;
            Fn f = (Fn) proc;
            for (ISeq s = RT.seq(coll); s != null; s = s.next()) f.invoke(s.first());
            return null;
        
            }
            case 52: { Object to = _a; Object from = _b; return (intoImpl(to, null, from)); }
            case 53: return RT.get(_a, _b);
            case 54: { Object m = _a; Object ks = _b; return (getIn(m, ks, null)); }
            case 55: return RT.find(_a, _b);
            case 56: return RT.contains(_a, _b);
            case 57: {
                Object m = _a; Object keyseq = _b;
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = RT.seq(keyseq); s != null; s = s.next()) {
                Object k = s.first();
                Object entry = RT.find(m, k);
                if (entry instanceof Map.Entry<?, ?> me) {
                    ret = ret.assoc(me.getKey(), me.getValue());
                }
            }
            return ret.persistent();
        
            }
            case 58: {
                Object keys = _a; Object vals = _b;
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            ISeq ks = RT.seq(keys);
            ISeq vs = RT.seq(vals);
            while (ks != null && vs != null) {
                ret = ret.assoc(ks.first(), vs.first());
                ks = ks.next();
                vs = vs.next();
            }
            return ret.persistent();
        
            }
            case 59: {
                Object v = _a; Object start = _b;
                    int s = (int) Numbers.toLong(start);
                    if (v instanceof PersistentVector pv) return pv.subVec(s, pv.cnt);
                    if (v instanceof PersistentVector.SubVec sv) return sv.subVec(s, sv.count());
                    throw new ClassCastException("subvec expects a vector: " + v);
                
            }
            case 60: {
                return FastSort.sort(toComparator(_a), _b);
            }
            case 61: {
                return FastSort.sortBy((Fn) _a, _b);
            }
            case 62: { Object s = _a; Object start = _b; return (((String) s).substring((int) Numbers.toLong(start))); }
            case 63: { Object nsName = _a; Object name = _b; return (Symbol.intern((String) nsName, (String) name)); }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call2_2(int _id, Object _a, Object _b) {
        switch (_id) {
            case 64: { Object nsName = _a; Object name = _b; return (Keyword.intern((String) nsName, (String) name)); }
            case 65: { Object nsName = _a; Object name = _b; return (Keyword.find((String) nsName, (String) name)); }
            case 66: { Object p = _a; Object s = _b; return (((Pattern) p).matcher((CharSequence) s)); }
            case 67: {
                Object p = _a; Object s = _b;
                    Matcher mat = ((Pattern) p).matcher((CharSequence) s);
                    if (!mat.find()) return null;
                    int gc = mat.groupCount();
                    if (gc == 0) return mat.group();
                    Object[] ret = new Object[gc + 1];
                    for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
                    return PersistentVector.createOwning(ret);
                
            }
            case 68: {
                Object p = _a; Object s = _b;
            Matcher mat = ((Pattern) p).matcher((CharSequence) s);
            if (!mat.matches()) return null;
            int gc = mat.groupCount();
            if (gc == 0) return mat.group();
            Object[] ret = new Object[gc + 1];
            for (int i = 0; i <= gc; i++) ret[i] = mat.group(i);
            return PersistentVector.createOwning(ret);
        
            }
            case 69: {
                Object p = _a; Object s = _b;
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
        
            }
            case 70: { Object opts = _a; Object s = _b; return (Reader.readString((Map<?, ?>) opts, (String) s)); }
            case 71: {
                Object f = _a; Object content = _b;
            try {
                Path p = (f instanceof File file) ? file.toPath() : Path.of(f.toString());
                Files.writeString(p, String.valueOf(content), StandardCharsets.UTF_8);
                return null;
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        
            }
            case 72: { Object a = _a; Object v = _b; return (((Atom) a).reset(v)); }
            case 73: { Object a = _a; Object v = _b; return (((Atom) a).resetVals(v)); }
            case 74: { Object v = _a; Object val = _b; return (((Atom.Volatile) v).reset(val)); }
            case 75: { Object p = _a; Object v = _b; return (((Atom.Promise) p).deliver(v)); }
            case 76: {
                Object ref = _a; Object key = _b;
            if (ref instanceof Atom a) a.removeWatch(key);
            else if (ref instanceof Var v) v.removeWatch(key);
            else if (ref instanceof Agent ag) ag.removeWatch(key);
            else if (ref instanceof Ref r) r.removeWatch(key);
            else if (ref instanceof IRef ir) ir.removeWatch(key);
            return ref;
        
            }
            case 77: {
                Object ref = _a; Object fn = _b;
            if (ref instanceof Atom a) a.setValidator((Fn) fn);
            else if (ref instanceof Agent ag) ag.setValidator((Fn) fn);
            else if (ref instanceof Ref r) r.setValidator((Fn) fn);
            else if (ref instanceof IRef ir) ir.setValidator((Fn) fn);
            return null;
        
            }
            case 78: { Object x = _a; Object m = _b; return (((IObj) x).withMeta((PersistentHashMap) m)); }
            case 79: { Object v = _a; Object val = _b; return (((Var) v).set(val)); }
            case 80: {
                Object alias = _a; Object targetNsSym = _b;
            Namespace target = Namespace.find((Symbol) targetNsSym);
            if (target == null) throw new RuntimeException("No namespace: " + targetNsSym);
            RT.currentNS().addAlias((Symbol) alias, target);
            return null;
        
            }
            case 81: { Object mf = _a; Object val = _b; return (((MultiFn) mf).getMethod(val)); }
            case 82: { Object mf = _a; Object val = _b; return (((MultiFn) mf).removeMethod(val)); }
            case 83: { Object msg = _a; Object data = _b; return (new ExceptionInfo(msg != null ? msg.toString() : null, data, null)); }
            case 84: {
                Object name = _a;
                Object expr = _b;
                Symbol vSym = Symbol.intern("v__" + RT.nextID());
                return PersistentList.createFromArray(
                        letStar,
                        PersistentVector.createOwning(vSym, PersistentList.createFromArray(defSym, name)),
                        PersistentList.createFromArray(
                                Symbol.intern("clojure.core", "when-not"),
                                PersistentList.createFromArray(Symbol.intern("clojure.core", "bound?"), vSym),
                                PersistentList.createFromArray(defSym, name, expr)));
            }
            case 85: {
                Object env = _a; Object sym = _b;
                if (env != null && RT.contains(env, sym)) return null;
                return call1(136, sym);
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object call3(int _id, Object _a, Object _b, Object _c) {
        switch (_id) {
            case 0: return CoreExtras.isa(_a, _b, _c);
            case 1: { Object c = _a; Object i = _b; Object nf = _c; return (RT.nth(c, (int) Numbers.toLong(i), nf)); }
            case 2: { Object f = _a; Object val = _b; Object coll = _c; return (reduce3((Fn) f, val, coll)); }
            case 3: {
                Object f = _a; Object init = _b; Object coll = _c;
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
        
            }
            case 4: { Object f = _a; Object init = _b; Object coll = _c; return (reductionsSeq((Fn) f, init, RT.seq(coll))); }
            case 5: { Object n = _a; Object step = _b; Object coll = _c; return (partitionSeq((int) Numbers.toLong(n), (int) Numbers.toLong(step), null, RT.seq(coll), true, false)); }
            case 6: { Object n = _a; Object step = _b; Object coll = _c; return (partitionSeq((int) Numbers.toLong(n), (int) Numbers.toLong(step), null, RT.seq(coll), true, true)); }
            case 7: { Object branchP = _a; Object childrenFn = _b; Object root = _c; return (treeSeqWalk((Fn) branchP, (Fn) childrenFn, root)); }
            case 8: { Object to = _a; Object xform = _b; Object from = _c; return (intoImpl(to, (Fn) xform, from)); }
            case 9: return RT.get(_a, _b, _c);
            case 10: return Core.getIn(_a, _b, _c);
            case 11: { Object m = _a; Object ks = _b; Object v = _c; return (assocIn(m, RT.seq(ks), v)); }
            case 12: {
                Object v = _a; Object start = _b; Object end = _c;
                    int s = (int) Numbers.toLong(start);
                    int e = (int) Numbers.toLong(end);
                    if (v instanceof PersistentVector pv) return pv.subVec(s, e);
                    if (v instanceof PersistentVector.SubVec sv) return sv.subVec(s, e);
                    throw new ClassCastException("subvec expects a vector: " + v);
                
            }
            case 13: {
                Object keyfn = _a; Object cmp = _b; Object coll = _c;
                    Fn kf = (Fn) keyfn;
                    return FastSort.sortBy(kf, toComparator(cmp), coll);
                
            }
            case 14: { Object s = _a; Object start = _b; Object end = _c; return (((String) s).substring((int) Numbers.toLong(start), (int) Numbers.toLong(end))); }
            case 15: { Object a = _a; Object o = _b; Object n = _c; return (((Atom) a).compareAndSet(o, n)); }
            case 16: {
                Object ref = _a; Object key = _b; Object fn = _c;
            if (ref instanceof Atom a) a.addWatch(key, (Fn) fn);
            else if (ref instanceof Var v) v.addWatch(key, (Fn) fn);
            else if (ref instanceof Agent ag) ag.addWatch(key, (Fn) fn);
            else if (ref instanceof Ref r) r.addWatch(key, (Fn) fn);
            else if (ref instanceof IRef ir) ir.addWatch(key, (Fn) fn);
            return ref;
        
            }
            case 17: { Object msg = _a; Object data = _b; Object cause = _c; return (new ExceptionInfo(msg != null ? msg.toString() : null, data, (Throwable) cause)); }
            case 18: { Object name = _a; Object dispatchFn = _b; Object defaultVal = _c; return (new MultiFn(name.toString(), (Fn) dispatchFn, defaultVal)); }
            case 19: { Object mf = _a; Object dispatchVal = _b; Object methodFn = _c; return (((MultiFn) mf).addMethod(dispatchVal, (Fn) methodFn)); }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    private static Object callV(int _id, Object[] _args) {
        return switch (_id >>> 5) {
            case 0 -> callV_0(_id, _args);
            case 1 -> callV_1(_id, _args);
            case 2 -> callV_2(_id, _args);
            case 3 -> callV_3(_id, _args);
            default -> throw new IllegalStateException("Invalid dispatcher id: " + _id);
        };
    }

    @SuppressWarnings("unchecked")
    private static Object callV_0(int _id, Object[] _args) {
        switch (_id) {
            case 0: {
                Object[] args = _args;
            if (args.length <= 1) return Boolean.TRUE;
            Object first = args[0];
            for (int i = 1; i < args.length; i++) {
                if (!RT.equiv(first, args[i])) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        
            }
            case 1: {
                Object[] args = _args;
            if (args.length <= 1) return Boolean.FALSE;
            Object first = args[0];
            for (int i = 1; i < args.length; i++) {
                if (!RT.equiv(first, args[i])) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        
            }
            case 2: {
                Object[] args = _args;
            if (args.length <= 1) return Boolean.TRUE;
            for (int i = 0; i < args.length - 1; i++) {
                if (!Numbers.equal(args[i], args[i + 1])) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        
            }
            case 3: {
                Object[] args = _args;
            if (args.length <= 1) return Boolean.TRUE;
            Set<RT.EquivKey> seen = new HashSet<>(args.length * 2);
            for (Object a : args) {
                if (!seen.add(new RT.EquivKey(a))) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        
            }
            case 4: {
                Object[] args = _args;
            if (args.length == 0) throw new IllegalArgumentException("Wrong number of args (0) passed to: /");
            if (args.length == 1) return Numbers.divide(1L, args[0]);
            Object acc = args[0];
            for (int i = 1; i < args.length; i++) acc = Numbers.divide(acc, args[i]);
            return acc;
        
            }
            case 5: {
                Object[] args = _args;
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.lt(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        
            }
            case 6: {
                Object[] args = _args;
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.lte(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        
            }
            case 7: {
                Object[] args = _args;
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.gt(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        
            }
            case 8: {
                Object[] args = _args;
            for (int i = 0; i < args.length - 1; i++) if (!Numbers.gte(args[i], args[i + 1])) return Boolean.FALSE;
            return Boolean.TRUE;
        
            }
            case 9: {
                Object[] args = _args;
            Object ret = args[0];
            for (int i = 1; i < args.length; i++) ret = Numbers.max(ret, args[i]);
            return ret;
        
            }
            case 10: {
                Object[] args = _args;
            Object ret = args[0];
            for (int i = 1; i < args.length; i++) ret = Numbers.min(ret, args[i]);
            return ret;
        
            }
            case 11: {
                Object[] args = _args;
            double r = ThreadLocalRandom.current().nextDouble();
            return args.length == 0 ? r : r * Numbers.toDouble(args[0]);
        
            }
            case 12: {
                Object[] args = _args;
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r &= Numbers.toLong(args[i]);
            return r;
        
            }
            case 13: {
                Object[] args = _args;
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r |= Numbers.toLong(args[i]);
            return r;
        
            }
            case 14: {
                Object[] args = _args;
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r ^= Numbers.toLong(args[i]);
            return r;
        
            }
            case 15: {
                Object[] args = _args;
            long r = Numbers.toLong(args[0]);
            for (int i = 1; i < args.length; i++) r &= ~Numbers.toLong(args[i]);
            return r;
        
            }
            case 16: { Object[] args = _args; return (switch (args.length) {
            case 0 -> rangeSeq(0L, Long.MAX_VALUE, 1L);
            case 1 -> rangeSeq(0L, Numbers.toLong(args[0]), 1L);
            case 2 -> rangeSeq(Numbers.toLong(args[0]), Numbers.toLong(args[1]), 1L);
            case 3 -> rangeSeq(Numbers.toLong(args[0]), Numbers.toLong(args[1]), Numbers.toLong(args[2]));
            default -> throw new IllegalArgumentException("Wrong number of args to range");
        }); }
            case 17: {
                Object[] args = _args;
            if (args.length < 2) throw new IllegalArgumentException("map requires at least 2 args");
            Fn f = (Fn) args[0];
            if (args.length == 2) return map1Seq(f, RT.seq(args[1]));
            ISeq[] seqs = new ISeq[args.length - 1];
            for (int i = 0; i < seqs.length; i++) seqs[i] = RT.seq(args[i + 1]);
            return mapNSeq(f, seqs);
        
            }
            case 18: {
                Object[] args = _args;
            if (args.length < 2) throw new IllegalArgumentException("mapv requires at least 2 args");
            Fn f = (Fn) args[0];
            if (args.length == 2) {
                Object coll = args[1];
                if (coll instanceof PersistentVector pv) {
                    return mapvVectorRange(f, pv, 0, pv.cnt);
                }
                if (coll instanceof PersistentVector.SubVec sv) {
                    return mapvVectorRange(f, sv.v, sv.start, sv.end);
                }
                if (coll instanceof PersistentList.EmptyList || coll == null) {
                    return PersistentVector.EMPTY;
                }
                PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
                if (coll instanceof PersistentList pl) {
                    PersistentList cur = pl;
                    while (true) {
                        tv = tv.conj(f.invoke(cur.first));
                        if (cur.rest instanceof PersistentList nextPl) {
                            cur = nextPl;
                        } else {
                            for (ISeq s = cur.next(); s != null; s = s.next()) tv = tv.conj(f.invoke(s.first()));
                            return tv.persistent();
                        }
                    }
                }
                for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
                    tv = tv.conj(f.invoke(s.first()));
                }
                return tv.persistent();
            }
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
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
        
            }
            case 19: {
                Object[] args = _args;
            Fn f = (Fn) args[0];
            if (args.length == 2) return concatSeq(map1Seq(f, RT.seq(args[1])));
            ISeq[] seqs = new ISeq[args.length - 1];
            for (int i = 0; i < seqs.length; i++) seqs[i] = RT.seq(args[i + 1]);
            return concatSeq(mapNSeq(f, seqs));
        
            }
            case 20: {
                Object[] args = _args;
            if (args.length == 0) return PersistentList.EMPTY;
            return concatSeq(PersistentList.createFromArray(args));
        
            }
            case 21: {
                Object[] args = _args;
            if (args.length == 0) return PersistentList.EMPTY;
            ISeq[] seqs = new ISeq[args.length];
            for (int i = 0; i < args.length; i++) seqs[i] = RT.seq(args[i]);
            return interleaveSeq(seqs);
        
            }
            case 22: { Object[] args = _args; return (switch (args.length) {
            case 2 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[0]), null, RT.seq(args[1]), false, false);
            case 3 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[1]), null, RT.seq(args[2]), false, false);
            case 4 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[1]), RT.seq(args[2]), RT.seq(args[3]), false, false);
            default -> throw new IllegalArgumentException("Wrong number of args to partition");
        }); }
            case 23: { Object[] args = _args; return (switch (args.length) {
            case 2 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[0]), null, RT.seq(args[1]), false, true);
            case 3 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[1]), null, RT.seq(args[2]), false, true);
            case 4 -> partitionSeq((int) Numbers.toLong(args[0]), (int) Numbers.toLong(args[1]), RT.seq(args[2]), RT.seq(args[3]), false, true);
            default -> throw new IllegalArgumentException("Wrong number of args to partitionv");
        }); }
            case 24: return PersistentList.createFromArray(_args);
            case 25: {
                Object[] args = _args;
            if (args.length == 0) return PersistentList.EMPTY;
            if (args.length == 1) return RT.seq(args[0]);
            ISeq tail = RT.seq(args[args.length - 1]);
            for (int i = args.length - 2; i >= 0; i--) {
                tail = RT.cons(args[i], tail);
            }
            return tail;
        
            }
            case 26: return PersistentVector.createOwning(_args);
            case 27: return PersistentHashMap.create(_args);
            case 28: return PersistentHashMap.create(_args);
            case 29: return PersistentHashSet.create(_args);
            case 30: {
                Object[] args = _args;
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
        
            }
            case 31: {
                Object[] args = _args;
            Object m = args[0];
            ISeq ks = RT.seq(args[1]);
            Fn f = (Fn) args[2];
            Object[] extra = new Object[args.length - 3];
            System.arraycopy(args, 3, extra, 0, extra.length);
            return updateIn(m, ks, f, extra);
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object callV_1(int _id, Object[] _args) {
        switch (_id) {
            case 32: {
                Object[] args = _args;
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
        
            }
            case 33: {
                Object[] args = _args;
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
        
            }
            case 34: {
                Object[] args = _args;
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
        
            }
            case 35: {
                Object[] args = _args;
            String fmt = (String) args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return String.format(fmt, rest);
        
            }
            case 36: {
                Object[] args = _args;
            String prefix = args.length == 0 ? "G__" : args[0].toString();
            return Symbol.intern(prefix + RT.nextID());
        
            }
            case 37: { Object[] args = _args; return (joinPrint(args, RT.truthy(RT.PRINT_READABLY.get()))); }
            case 38: { Object[] args = _args; return (joinPrint(args, RT.truthy(RT.PRINT_READABLY.get())) + System.lineSeparator()); }
            case 39: { Object[] args = _args; return (joinPrint(args, false)); }
            case 40: { Object[] args = _args; return (joinPrint(args, false) + System.lineSeparator()); }
            case 41: {
                Object[] args = _args; writeOut(joinPrint(args, RT.truthy(RT.PRINT_READABLY.get())), false); return null; 
            }
            case 42: {
                Object[] args = _args; writeOut(joinPrint(args, RT.truthy(RT.PRINT_READABLY.get())), true); return null; 
            }
            case 43: {
                Object[] args = _args; writeOut(joinPrint(args, false), false); return null; 
            }
            case 44: {
                Object[] args = _args; writeOut(joinPrint(args, false), true); return null; 
            }
            case 45: {
                Object[] args = _args;
            String fmt = (String) args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            writeOut(String.format(fmt, rest), false);
            return null;
        
            }
            case 46: {
                Object[] args = _args;
            if (args.length == 0) throw new IllegalArgumentException("Wrong number of args (0) passed to: slurp");
            Charset charset = StandardCharsets.UTF_8;
            Keyword encKw = Keyword.intern("encoding");
            for (int i = 1; i + 1 < args.length; i += 2) {
                if (encKw.equals(args[i]) && args[i + 1] != null) {
                    charset = Charset.forName(args[i + 1].toString());
                }
            }
            return slurpImpl(args[0], charset);
        
            }
            case 47: {
                Object[] args = _args;
            Fn f = (Fn) args[0];
            if (args.length == 2) return f.applyTo(RT.toArray(args[1]));
            List<Object> list = new ArrayList<>();
            for (int i = 1; i < args.length - 1; i++) list.add(args[i]);
            for (ISeq s = RT.seq(args[args.length - 1]); s != null; s = s.next()) list.add(s.first());
            return f.applyTo(list.toArray());
        
            }
            case 48: {
                Object[] pArgs = _args;
            Fn f = (Fn) pArgs[0];
            Object[] fixed = new Object[pArgs.length - 1];
            System.arraycopy(pArgs, 1, fixed, 0, fixed.length);
            return Fn.ofVarargs("partial", callArgs -> {
                Object[] all = new Object[fixed.length + callArgs.length];
                System.arraycopy(fixed, 0, all, 0, fixed.length);
                System.arraycopy(callArgs, 0, all, fixed.length, callArgs.length);
                return f.applyTo(all);
            });
        
            }
            case 49: {
                Object[] fns = _args;
            if (fns.length == 0) return Fn.of1("identity", x -> x);
            if (fns.length == 1) return fns[0];
            if (fns.length == 2) {
                Fn f0 = (Fn) fns[0], f1 = (Fn) fns[1];
                return new Fn.NamedFn("comp") {
                    @Override public Object invoke() { return f0.invoke(f1.invoke()); }
                    @Override public Object invoke(Object a) { return f0.invoke(f1.invoke(a)); }
                    @Override public Object invoke(Object a, Object b) { return f0.invoke(f1.invoke(a, b)); }
                    @Override public Object invoke(Object a, Object b, Object c) { return f0.invoke(f1.invoke(a, b, c)); }
                    @Override public Object invokeVarargs(Object[] args) { return f0.invoke(f1.applyTo(args)); }
                };
            }
            if (fns.length == 3) {
                Fn f0 = (Fn) fns[0], f1 = (Fn) fns[1], f2 = (Fn) fns[2];
                return new Fn.NamedFn("comp") {
                    @Override public Object invoke() { return f0.invoke(f1.invoke(f2.invoke())); }
                    @Override public Object invoke(Object a) { return f0.invoke(f1.invoke(f2.invoke(a))); }
                    @Override public Object invoke(Object a, Object b) { return f0.invoke(f1.invoke(f2.invoke(a, b))); }
                    @Override public Object invokeVarargs(Object[] args) { return f0.invoke(f1.invoke(f2.applyTo(args))); }
                };
            }
            return Fn.ofVarargs("comp", args -> {
                Object res = ((Fn) fns[fns.length - 1]).applyTo(args);
                for (int i = fns.length - 2; i >= 0; i--) {
                    res = ((Fn) fns[i]).invoke(res);
                }
                return res;
            });
        
            }
            case 50: { Object[] fns = _args; return (Fn.ofVarargs("juxt", args -> {
            Object[] res = new Object[fns.length];
            for (int i = 0; i < fns.length; i++) {
                res[i] = ((Fn) fns[i]).applyTo(args);
            }
            return PersistentVector.createOwning(res);
        })); }
            case 51: {
                Object[] fArgs = _args;
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
        
            }
            case 52: {
                Object[] args = _args;
            Fn f = (Fn) args[0];
            Object[] rest = new Object[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            Object ret = f.applyTo(rest);
            while (ret instanceof Fn nextFn && !(ret instanceof Keyword || ret instanceof Symbol
                    || ret instanceof PersistentVector || ret instanceof PersistentHashMap || ret instanceof PersistentHashSet)) {
                ret = nextFn.invoke();
            }
            return ret;
        
            }
            case 53: { Object[] preds = _args; return (Fn.ofVarargs("every-pred", args -> {
            for (Object p : preds) {
                Fn pred = (Fn) p;
                for (Object a : args) {
                    if (!RT.truthy(pred.invoke(a))) return Boolean.FALSE;
                }
            }
            return Boolean.TRUE;
        })); }
            case 54: { Object[] preds = _args; return (Fn.ofVarargs("some-fn", args -> {
            for (Object p : preds) {
                Fn pred = (Fn) p;
                for (Object a : args) {
                    Object r = pred.invoke(a);
                    if (RT.truthy(r)) return r;
                }
            }
            return null;
        })); }
            case 55: {
                Object[] args = _args;
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
        
            }
            case 56: {
                Object[] args = _args;
                Atom a = (Atom) args[0];
                Fn f = (Fn) args[1];
                if (args.length == 2) return a.swapVals(f);
                if (args.length == 3) return a.swapVals(f, args[2]);
                if (args.length == 4) return a.swapVals(f, args[2], args[3]);
                Object[] rest = new Object[args.length - 2];
                System.arraycopy(args, 2, rest, 0, rest.length);
                return a.swapVals(f, rest);
            }
            case 57: {
                Object[] args = _args;
            if (args.length < 2) throw new IllegalArgumentException("vswap! requires at least 2 arguments");
            Symbol volSym = Symbol.intern("vol__" + RT.nextID());
            List<Object> call = new ArrayList<>(args.length);
            call.add(args[1]);
            call.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "deref"), volSym));
            for (int i = 2; i < args.length; i++) call.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("let*"),
                    PersistentVector.createOwning(volSym, args[0]),
                    PersistentList.createFromArray(
                            Symbol.intern("clojure.core", "vreset!"),
                            volSym,
                            PersistentList.create(call)));
        
            }
            case 58: {
                Object[] args = _args;
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
        
            }
            case 59: {
                Object[] args = _args;
            IObj obj = (IObj) args[0];
            Fn f = (Fn) args[1];
            Object[] callArgs = new Object[args.length - 1];
            callArgs[0] = obj.meta();
            System.arraycopy(args, 2, callArgs, 1, args.length - 2);
            return obj.withMeta((PersistentHashMap) f.applyTo(callArgs));
        
            }
            case 60: {
                Object[] args = _args;
            Var v = (Var) args[0];
            Fn f = (Fn) args[1];
            Object[] rest = new Object[args.length - 2];
            System.arraycopy(args, 2, rest, 0, rest.length);
            return v.alterRoot(f, rest);
        
            }
            case 61: {
                Object[] args = _args;
            for (Object o : args) if (!((Var) o).isBound()) return Boolean.FALSE;
            return Boolean.TRUE;
        
            }
            case 62: {
                Object[] args = _args;
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
        
            }
            case 63: {
                Object[] args = _args;
            Namespace cur = RT.currentNS();
            Class<?> last = null;
            for (Object spec : args) {
                if (spec instanceof Symbol s) {
                    Class<?> c = Interop.findClass(s.name);
                    if (c == null) throw RT.sneakyThrow(new ClassNotFoundException(s.name));
                    int dot = s.name.lastIndexOf('.');
                    String alias = dot >= 0 ? s.name.substring(dot + 1) : s.name;
                    cur.importClass(Symbol.intern(alias), c);
                    last = c;
                } else if (spec instanceof ISeq || spec instanceof PersistentVector) {
                    ISeq seq = RT.seq(spec);
                    if (seq != null) {
                        String pkg = seq.first().toString();
                        for (ISeq s = seq.next(); s != null; s = s.next()) {
                            String shortName = s.first().toString();
                            String clsName = pkg + "." + shortName;
                            Class<?> c = Interop.findClass(clsName);
                            if (c == null) throw RT.sneakyThrow(new ClassNotFoundException(clsName));
                            cur.importClass(Symbol.intern(shortName), c);
                            last = c;
                        }
                    }
                }
            }
            return last;
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object callV_2(int _id, Object[] _args) {
        switch (_id) {
            case 64: {
                Object[] args = _args;
            for (Object spec : args) processLibSpec(spec, false);
            return null;
        
            }
            case 65: {
                Object[] args = _args;
            for (Object spec : args) processLibSpec(spec, true);
            return null;
        
            }
            case 66: {
                Object[] args = _args;
            throw new IllegalStateException("unquote used outside syntax-quote");
        
            }
            case 67: {
                Object[] args = _args;
            throw new IllegalStateException("unquote-splicing used outside syntax-quote");
        
            }
            case 68: {
                Object[] args = _args;
            if (args.length < 2) throw new IllegalArgumentException("aget requires array and at least one index");
            Object cur = RT.aget(args[0], (int) Numbers.toLong(args[1]));
            for (int i = 2; i < args.length; i++) {
                cur = RT.aget(cur, (int) Numbers.toLong(args[i]));
            }
            return cur;
        
            }
            case 69: {
                Object[] args = _args;
            if (args.length < 3) throw new IllegalArgumentException("aset requires array, index(es), and value");
            Object cur = args[0];
            for (int i = 1; i < args.length - 2; i++) {
                cur = RT.aget(cur, (int) Numbers.toLong(args[i]));
            }
            return RT.aset(cur, (int) Numbers.toLong(args[args.length - 2]), args[args.length - 1]);
        
            }
            case 70: return Core.expandDefn(_args);
            case 71: {
                Object[] args = _args;
            if (args.length != 1) {
                throw new IllegalArgumentException("doc requires 1 argument");
            }
            Object target = args[0];
            Object argExpr = (target instanceof ISeq s
                    && (Symbol.intern("var").equals(s.first()) || quoteSym.equals(s.first())))
                    ? target
                    : PersistentList.createFromArray(quoteSym, target);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "print-doc"), argExpr);
        
            }
            case 72: {
                Object[] args = _args;
            Symbol name = (Symbol) args[0];
            PersistentHashMap meta = (name.meta() != null ? name.meta() : PersistentHashMap.EMPTY)
                    .assoc(Keyword.intern("private"), Boolean.TRUE);
            Object[] patched = args.clone();
            patched[0] = name.withMeta(meta);
            return expandDefn(patched);
        
            }
            case 73: return Core.expandFn(_args);
            case 74: {
                Object[] args = _args;
            List<Object> forms = new ArrayList<>();
            forms.add(doSym);
            for (Object s : args) forms.add(PersistentList.createFromArray(defSym, s));
            return PersistentList.create(forms);
        
            }
            case 75: return Core.expandLet(_args);
            case 76: return Core.expandLoop(_args);
            case 77: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(ifSym, args[0], PersistentList.create(body));
        
            }
            case 78: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(ifSym, args[0], null, PersistentList.create(body));
        
            }
            case 79: {
                Object[] args = _args;
            Object test = args[0];
            Object thenForm = args[1];
            Object elseForm = args.length > 2 ? args[2] : null;
            return PersistentList.createFromArray(ifSym,
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "not"), test),
                    thenForm, elseForm);
        
            }
            case 80: {
                Object[] args = _args;
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("when"));
            body.add(temp);
            List<Object> innerLet = new ArrayList<>();
            innerLet.add(letStar);
            innerLet.add(Compiler.destructure(PersistentVector.createOwning(form, temp)));
            for (int i = 1; i < args.length; i++) innerLet.add(args[i]);
            body.add(PersistentList.create(innerLet));
            return PersistentList.createFromArray(letStar, PersistentVector.createOwning(temp, tst), PersistentList.create(body));
        
            }
            case 81: {
                Object[] args = _args;
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Object thenF = args[1];
            Object elseF = args.length > 2 ? args[2] : null;
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(temp, tst),
                    PersistentList.createFromArray(ifSym, temp,
                            PersistentList.createFromArray(letStar, Compiler.destructure(PersistentVector.createOwning(form, temp)), thenF),
                            elseF));
        
            }
            case 82: {
                Object[] args = _args;
            PersistentVector bindings = (PersistentVector) args[0];
            Object form = bindings.nth(0);
            Object tst = bindings.nth(1);
            Symbol temp = Symbol.intern("temp__" + RT.nextID());
            List<Object> innerLet = new ArrayList<>();
            innerLet.add(letStar);
            innerLet.add(Compiler.destructure(PersistentVector.createOwning(form, temp)));
            for (int i = 1; i < args.length; i++) innerLet.add(args[i]);
            return PersistentList.createFromArray(letStar,
                    PersistentVector.createOwning(temp, tst),
                    PersistentList.createFromArray(ifSym,
                            PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), temp),
                            null,
                            PersistentList.create(innerLet)));
        
            }
            case 83: {
                Object[] args = _args;
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
                            PersistentList.createFromArray(letStar, Compiler.destructure(PersistentVector.createOwning(form, temp)), thenF)));
        
            }
            case 84: {
                Object[] args = _args;
            PersistentVector bindings = (PersistentVector) args[0];
            Object x = bindings.nth(0);
            Object xs = bindings.nth(1);
            Symbol s = Symbol.intern("s__" + RT.nextID());
            List<Object> body = new ArrayList<>();
            body.add(letStar);
            body.add(Compiler.destructure(PersistentVector.createOwning(x, PersistentList.createFromArray(Symbol.intern("clojure.core", "first"), s))));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(Symbol.intern("when-let"),
                    PersistentVector.createOwning(s, PersistentList.createFromArray(Symbol.intern("clojure.core", "seq"), xs)),
                    PersistentList.create(body));
        
            }
            case 85: {
                Object[] args = _args;
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
        
            }
            case 86: {
                Object[] args = _args;
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
        
            }
            case 87: {
                Object[] args = _args;
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
        
            }
            case 88: {
                Object[] args = _args;
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
        
            }
            case 89: {
                Object[] args = _args;
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
        
            }
            case 90: {
                Object[] args = _args;
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
        
            }
            case 91: {
                Object[] args = _args;
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
        
            }
            case 92: {
                Object[] args = _args;
            Object expr = args[0];
            Symbol name = (Symbol) args[1];
            List<Object> bindings = new ArrayList<>();
            bindings.add(name);
            bindings.add(expr);
            if (args.length == 2) {
                return PersistentList.createFromArray(letStar, PersistentVector.create(bindings), name);
            }
            for (int i = 2; i < args.length - 1; i++) {
                bindings.add(name);
                bindings.add(args[i]);
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(bindings), args[args.length - 1]);
        
            }
            case 93: {
                Object[] args = _args;
            if (args.length == 1) return args[0];
            Symbol g = Symbol.intern("some__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            Object lastExpr = g;
            for (int i = 1; i < args.length; i++) {
                Object form = args[i];
                Object step = (form instanceof ISeq s)
                        ? RT.cons(s.first(), RT.cons(g, s.next()))
                        : PersistentList.createFromArray(form, g);
                Object condExpr = PersistentList.createFromArray(ifSym,
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), g),
                        null, step);
                if (i == args.length - 1) {
                    lastExpr = condExpr;
                } else {
                    steps.add(g);
                    steps.add(condExpr);
                }
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), lastExpr);
        
            }
            case 94: {
                Object[] args = _args;
            if (args.length == 1) return args[0];
            Symbol g = Symbol.intern("some__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            Object lastExpr = g;
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
                Object condExpr = PersistentList.createFromArray(ifSym,
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "nil?"), g),
                        null, step);
                if (i == args.length - 1) {
                    lastExpr = condExpr;
                } else {
                    steps.add(g);
                    steps.add(condExpr);
                }
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), lastExpr);
        
            }
            case 95: {
                Object[] args = _args;
            if (args.length == 1) return args[0];
            if (((args.length - 1) & 1) != 0) throw new IllegalArgumentException("cond-> requires an even number of forms");
            Symbol g = Symbol.intern("cond__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            Object lastExpr = g;
            for (int i = 1; i < args.length; i += 2) {
                Object test = args[i];
                Object form = args[i + 1];
                Object step = (form instanceof ISeq s)
                        ? RT.cons(s.first(), RT.cons(g, s.next()))
                        : PersistentList.createFromArray(form, g);
                Object condExpr = PersistentList.createFromArray(ifSym, test, step, g);
                if (i + 2 >= args.length) {
                    lastExpr = condExpr;
                } else {
                    steps.add(g);
                    steps.add(condExpr);
                }
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), lastExpr);
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object callV_3(int _id, Object[] _args) {
        switch (_id) {
            case 96: {
                Object[] args = _args;
            if (args.length == 1) return args[0];
            if (((args.length - 1) & 1) != 0) throw new IllegalArgumentException("cond->> requires an even number of forms");
            Symbol g = Symbol.intern("cond__" + RT.nextID());
            List<Object> steps = new ArrayList<>();
            steps.add(g);
            steps.add(args[0]);
            Object lastExpr = g;
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
                Object condExpr = PersistentList.createFromArray(ifSym, test, step, g);
                if (i + 2 >= args.length) {
                    lastExpr = condExpr;
                } else {
                    steps.add(g);
                    steps.add(condExpr);
                }
            }
            return PersistentList.createFromArray(letStar, PersistentVector.create(steps), lastExpr);
        
            }
            case 97: {
                Object[] args = _args;
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
        
            }
            case 98: {
                Object[] args = _args;
            Object acc = PersistentList.createFromArray(Symbol.intern("."), args[0], args[1]);
            for (int i = 2; i < args.length; i++) {
                acc = PersistentList.createFromArray(Symbol.intern("."), acc, args[i]);
            }
            return acc;
        
            }
            case 99: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(fnStar);
            body.add(PersistentVector.EMPTY);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "lazy-seq*"), PersistentList.create(body));
        
            }
            case 100: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(fnStar);
            body.add(PersistentVector.EMPTY);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "delay*"), PersistentList.create(body));
        
            }
            case 101: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(fnStar);
            body.add(PersistentVector.EMPTY);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "future-call"), PersistentList.create(body));
        
            }
            case 102: { Object[] args = _args; return (null); }
            case 103: {
                Object[] args = _args;
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
        
            }
            case 104: {
                Object[] args = _args;
            Object test = args[0];
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("when"));
            body.add(test);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            body.add(PersistentList.createFromArray(Symbol.intern("recur")));
            return PersistentList.createFromArray(loopStar, PersistentVector.EMPTY, PersistentList.create(body));
        
            }
            case 105: {
                Object[] args = _args;
            PersistentVector seqExprs = (PersistentVector) args[0];
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            Object inner = PersistentList.create(body);
            return expandDoseq(seqExprs, 0, inner);
        
            }
            case 106: {
                Object[] args = _args;
            PersistentVector seqExprs = (PersistentVector) args[0];
            Object bodyExpr = args[1];
            return expandFor(seqExprs, 0, bodyExpr);
        
            }
            case 107: {
                Object[] args = _args;
            List<Object> out = new ArrayList<>(args.length + 1);
            out.add(Symbol.intern("defmacro*"));
            Collections.addAll(out, args);
            return PersistentList.create(out);
        
            }
            case 108: {
                Object[] args = _args;
            List<Object> out = new ArrayList<>(args.length + 2);
            out.add(Symbol.intern("clojure.core", "refer"));
            out.add(PersistentList.createFromArray(Symbol.intern("quote"), Symbol.intern("clojure.core")));
            Collections.addAll(out, args);
            return PersistentList.create(out);
        
            }
            case 109: {
                Object[] args = _args;
            Object bmap = args[0];
            Fn f = (Fn) args[1];
            Object[] fargs = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : Fn.EMPTY_ARGS;
            Map<Var, Object> map = new HashMap<>();
            for (ISeq s = RT.seq(bmap); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                map.put((Var) e.getKey(), e.getValue());
            }
            Var.pushThreadBindings(map);
            try {
                return f.applyTo(fargs);
            } finally {
                Var.popThreadBindings();
            }
        
            }
            case 110: {
                Object[] args = _args;
            List<Object> fnBody = new ArrayList<>();
            fnBody.add(doSym);
            for (int i = 1; i < args.length; i++) fnBody.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "with-bindings*"),
                    args[0],
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(fnBody)));
        
            }
            case 111: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(doSym);
            Collections.addAll(body, args);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "with-loading-context*"),
                    PersistentList.createFromArray(Symbol.intern("fn*"), PersistentVector.EMPTY, PersistentList.create(body)));
        
            }
            case 112: {
                Object[] args = _args;
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
        
            }
            case 113: {
                Object[] args = _args;
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
        
            }
            case 114: {
                Object[] args = _args;
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("binding"));
            body.add(PersistentVector.createOwning(
                    Symbol.intern("clojure.core", "*in*"),
                    PersistentList.createFromArray(Symbol.intern("java.io.BufferedReader."),
                            PersistentList.createFromArray(Symbol.intern("java.io.StringReader."), args[0]))));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        
            }
            case 115: {
                Object[] args = _args;
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
        
            }
            case 116: {
                Object[] args = _args;
            if (!RT.truthy(RT.ASSERT.deref())) return null;
            Object x = args[0];
            Object msg = args.length > 1 ? args[1] : RT.printString(x);
            return PersistentList.createFromArray(Symbol.intern("when-not"), x,
                    PersistentList.createFromArray(Symbol.intern("throw"),
                            PersistentList.createFromArray(Symbol.intern("AssertionError."),
                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "str"), "Assert failed: ", msg))));
        
            }
            case 117: {
                Object[] args = _args;
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
                    } else if (Keyword.intern("gen-class").equals(head)) {
                        List<Object> gc = new ArrayList<>();
                        gc.add(Symbol.intern("clojure.core", "gen-class"));
                        boolean hasName = false;
                        for (ISeq s = clause.next(); s != null; s = s.next()) {
                            Object item = s.first();
                            if (Keyword.intern("name").equals(item)) hasName = true;
                            gc.add(PersistentList.createFromArray(quoteSym, item));
                        }
                        if (!hasName) {
                            gc.add(Keyword.intern("name"));
                            gc.add(PersistentList.createFromArray(quoteSym, nsSym));
                        }
                        clauseForms.add(PersistentList.create(gc));
                    } else if (Keyword.intern("load").equals(head)) {
                        List<Object> ld = new ArrayList<>();
                        ld.add(Symbol.intern("clojure.core", "load"));
                        for (ISeq s = clause.next(); s != null; s = s.next()) {
                            ld.add(s.first());
                        }
                        clauseForms.add(PersistentList.create(ld));
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
        
            }
            case 118: {
                Object[] args = _args;
            Symbol name = (Symbol) args[0];
            int idx = 1;
            if (idx < args.length && args[idx] instanceof String) idx++;
            if (idx < args.length && args[idx] instanceof PersistentHashMap) idx++;
            Object dispatchFn = args[idx++];
            Object defaultVal = Keyword.intern("default");
            Object hierarchyVal = null;
            for (int i = idx; i + 1 < args.length; i += 2) {
                if (Keyword.intern("default").equals(args[i])) defaultVal = args[i + 1];
                else if (Keyword.intern("hierarchy").equals(args[i])) hierarchyVal = args[i + 1];
            }
            if (hierarchyVal != null) {
                return PersistentList.createFromArray(defSym, name,
                        PersistentList.createFromArray(Symbol.intern("clojure.core", "create-multi"),
                                name.name, dispatchFn, defaultVal, hierarchyVal));
            }
            return PersistentList.createFromArray(defSym, name,
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "create-multi"),
                            name.name, dispatchFn, defaultVal));
        
            }
            case 119: {
                Object[] args = _args;
            Symbol mfName = (Symbol) args[0];
            Object dispatchVal = args[1];
            List<Object> fnBody = new ArrayList<>();
            fnBody.add(fnStar);
            for (int i = 2; i < args.length; i++) fnBody.add(args[i]);
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "add-method!"),
                    mfName, dispatchVal, PersistentList.create(fnBody));
        
            }
            case 120: {
                Object[] args = _args;
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
        
            }
            default: throw new IllegalStateException("Invalid dispatcher id: " + _id);
        }
    }
}
