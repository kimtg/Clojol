package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.reflect.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * Lazy-loaded additional standard Clojure namespaces:
 * clojure.core.protocols, clojure.stacktrace, clojure.template,
 * clojure.instant, clojure.reflect, and clojure.uuid.
 */
public final class StdLibExtra {

    private StdLibExtra() {}

    // ========================================================================
    // clojure.core.protocols
    // ========================================================================

    public static void initCoreProtocols() {
        Namespace ns = Namespace.findOrCreate("clojure.core.protocols");

        // CollReduce protocol
        Protocol collReduceProto = new Protocol(Symbol.intern("clojure.core.protocols", "CollReduce"), null, null);
        Fn collReduceFn = new Fn.NamedFn("coll-reduce") {
            @Override public Object invoke(Object coll, Object f) {
                Fn impl = collReduceProto.resolveMethodOrNull(Keyword.intern("coll-reduce"), coll);
                if (impl != null) return impl.invoke(coll, f);
                Var redVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("reduce"));
                return ((Fn) redVar.deref()).invoke(f, coll);
            }
            @Override public Object invoke(Object coll, Object f, Object val) {
                Fn impl = collReduceProto.resolveMethodOrNull(Keyword.intern("coll-reduce"), coll);
                if (impl != null) return impl.invoke(coll, f, val);
                Var redVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("reduce"));
                return ((Fn) redVar.deref()).invoke(f, val, coll);
            }
        };
        ns.intern("CollReduce", collReduceProto);
        ns.intern("coll-reduce", collReduceFn);

        // InternalReduce protocol
        Protocol internalReduceProto = new Protocol(Symbol.intern("clojure.core.protocols", "InternalReduce"), null, null);
        Fn internalReduceFn = new Fn.NamedFn("internal-reduce") {
            @Override public Object invoke(Object seq, Object f, Object start) {
                Fn impl = internalReduceProto.resolveMethodOrNull(Keyword.intern("internal-reduce"), seq);
                if (impl != null) return impl.invoke(seq, f, start);
                Var redVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("reduce"));
                return ((Fn) redVar.deref()).invoke(f, start, seq);
            }
        };
        ns.intern("InternalReduce", internalReduceProto);
        ns.intern("internal-reduce", internalReduceFn);

        // IKVReduce protocol
        Protocol kvReduceProto = new Protocol(Symbol.intern("clojure.core.protocols", "IKVReduce"), null, null);
        Fn kvReduceFn = new Fn.NamedFn("kv-reduce") {
            @Override public Object invoke(Object amap, Object f, Object init) {
                Fn impl = kvReduceProto.resolveMethodOrNull(Keyword.intern("kv-reduce"), amap);
                if (impl != null) return impl.invoke(amap, f, init);
                Var rkvVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("reduce-kv"));
                return ((Fn) rkvVar.deref()).invoke(f, init, amap);
            }
        };
        ns.intern("IKVReduce", kvReduceProto);
        ns.intern("kv-reduce", kvReduceFn);

        // Datafiable protocol
        Protocol datafiableProto = new Protocol(Symbol.intern("clojure.core.protocols", "Datafiable"), null, null);
        Keyword datafyKw = Keyword.intern("datafy");
        Keyword datafyMetaKw = Keyword.intern("clojure.core.protocols", "datafy");
        Fn datafyFn = Fn.of1("datafy", o -> {
            if (o instanceof IMeta im && im.meta() != null) {
                Object mf = im.meta().valAt(datafyMetaKw);
                if (mf instanceof Fn f) return f.invoke(o);
            }
            Fn impl = datafiableProto.resolveMethodOrNull(datafyKw, o);
            if (impl != null) return impl.invoke(o);
            return o;
        });
        ns.intern("Datafiable", datafiableProto);
        ns.intern("datafy", datafyFn);

        // Navigable protocol
        Protocol navigableProto = new Protocol(Symbol.intern("clojure.core.protocols", "Navigable"), null, null);
        Keyword navKw = Keyword.intern("nav");
        Keyword navMetaKw = Keyword.intern("clojure.core.protocols", "nav");
        Fn navFn = Fn.of3("nav", (coll, k, v) -> {
            if (coll instanceof IMeta im && im.meta() != null) {
                Object mf = im.meta().valAt(navMetaKw);
                if (mf instanceof Fn f) return f.invoke(coll, k, v);
            }
            Fn impl = navigableProto.resolveMethodOrNull(navKw, coll);
            if (impl != null) return impl.invoke(coll, k, v);
            return v;
        });
        ns.intern("Navigable", navigableProto);
        ns.intern("nav", navFn);

        Core.markLibLoaded("clojure.core.protocols");
    }

    // ========================================================================
    // clojure.stacktrace
    // ========================================================================

    public static void initStacktrace() {
        Namespace ns = Namespace.findOrCreate("clojure.stacktrace");

        Fn rootCauseFn = Fn.of1("root-cause", t -> {
            Throwable cur = (Throwable) t;
            while (cur != null && cur.getCause() != null && cur.getCause() != cur) {
                cur = cur.getCause();
            }
            return cur;
        });
        ns.intern("root-cause", rootCauseFn);

        Fn printTraceElemFn = Fn.of1("print-trace-element", el -> {
            StackTraceElement ste = (StackTraceElement) el;
            PrintWriter out = RT.outWriter();
            out.print("    " + ste.getClassName() + "." + ste.getMethodName()
                    + " (" + (ste.getFileName() != null ? ste.getFileName() : "Unknown Source")
                    + ":" + ste.getLineNumber() + ")");
            out.flush();
            return null;
        });
        ns.intern("print-trace-element", printTraceElemFn);

        Fn printThrowableFn = Fn.of1("print-throwable", tr -> {
            Throwable t = (Throwable) tr;
            PrintWriter out = RT.outWriter();
            out.print(t.getClass().getName() + ": " + t.getMessage());
            out.flush();
            return null;
        });
        ns.intern("print-throwable", printThrowableFn);

        Fn printStackTraceFn = Fn.of12("print-stack-trace",
                tr -> printStackTraceImpl((Throwable) tr, -1),
                (tr, n) -> printStackTraceImpl((Throwable) tr, n == null ? -1 : (int) Numbers.toLong(n)));
        ns.intern("print-stack-trace", printStackTraceFn);

        Fn printCauseTraceFn = Fn.of12("print-cause-trace",
                tr -> printCauseTraceImpl((Throwable) tr, -1),
                (tr, n) -> printCauseTraceImpl((Throwable) tr, n == null ? -1 : (int) Numbers.toLong(n)));
        ns.intern("print-cause-trace", printCauseTraceFn);

        ns.intern("e", Fn.of0("e", () -> {
            Object e = RT.STAR_E.deref();
            if (e instanceof Throwable t) printCauseTraceImpl(t, 8);
            return null;
        }));

        Core.markLibLoaded("clojure.stacktrace");
    }

    private static Object printStackTraceImpl(Throwable t, int n) {
        if (t == null) return null;
        PrintWriter out = RT.outWriter();
        out.println(t.getClass().getName() + ": " + t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        int lim = n < 0 ? st.length : Math.min(n, st.length);
        for (int i = 0; i < lim; i++) {
            StackTraceElement ste = st[i];
            out.println("    " + ste.getClassName() + "." + ste.getMethodName()
                    + " (" + (ste.getFileName() != null ? ste.getFileName() : "Unknown Source")
                    + ":" + ste.getLineNumber() + ")");
        }
        out.flush();
        return null;
    }

    private static Object printCauseTraceImpl(Throwable t, int n) {
        if (t == null) return null;
        printStackTraceImpl(t, n);
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            PrintWriter out = RT.outWriter();
            out.print("Caused by: ");
            out.flush();
            printCauseTraceImpl(cause, n);
        }
        return null;
    }

    // ========================================================================
    // clojure.template
    // ========================================================================

    public static void initTemplate() {
        Namespace ns = Namespace.findOrCreate("clojure.template");

        ns.intern("apply-template", Fn.of3("apply-template", (argv, expr, values) -> {
            PersistentVector av = (PersistentVector) argv;
            List<Object> vals = new ArrayList<>();
            for (ISeq s = RT.seq(values); s != null; s = s.next()) vals.add(s.first());
            Map<Object, Object> subs = new HashMap<>();
            int n = Math.min(av.count(), vals.size());
            for (int i = 0; i < n; i++) {
                subs.put(av.nth(i), vals.get(i));
            }
            return substituteTemplate(expr, subs);
        }));

        ns.internMacro("do-template", Fn.ofVarargs("do-template", args -> {
            if (args.length < 2 || !(args[0] instanceof PersistentVector argv)) {
                throw new IllegalArgumentException("do-template requires argv vector and expr");
            }
            Object expr = args[1];
            int arity = argv.count();
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            if (arity > 0) {
                for (int i = 2; i + arity <= args.length; i += arity) {
                    Map<Object, Object> subs = new HashMap<>();
                    for (int j = 0; j < arity; j++) {
                        subs.put(argv.nth(j), args[i + j]);
                    }
                    body.add(substituteTemplate(expr, subs));
                }
            }
            return PersistentList.create(body);
        }));

        Core.markLibLoaded("clojure.template");
    }

    private static Object substituteTemplate(Object form, Map<Object, Object> subs) {
        if (subs.containsKey(form)) return subs.get(form);
        if (form instanceof Symbol sym) {
            Symbol clean = sym.withMeta(null);
            if (subs.containsKey(clean)) return subs.get(clean);
            return form;
        }
        if (form instanceof PersistentVector pv) {
            Object[] out = new Object[pv.count()];
            for (int i = 0; i < pv.count(); i++) out[i] = substituteTemplate(pv.nth(i), subs);
            PersistentVector res = PersistentVector.createOwning(out);
            return pv.meta() != null ? res.withMeta(pv.meta()) : res;
        }
        if (form instanceof PersistentHashMap pm) {
            PersistentHashMap out = PersistentHashMap.EMPTY;
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                out = out.assoc(substituteTemplate(me.key, subs), substituteTemplate(me.val, subs));
            }
            return pm.meta() != null ? out.withMeta(pm.meta()) : out;
        }
        if (form instanceof PersistentHashSet ps) {
            List<Object> items = new ArrayList<>();
            for (ISeq s = ps.seq(); s != null; s = s.next()) items.add(substituteTemplate(s.first(), subs));
            PersistentHashSet res = PersistentHashSet.createFromColl(items);
            return ps.meta() != null ? res.withMeta(ps.meta()) : res;
        }
        if (form instanceof ISeq seq) {
            List<Object> items = new ArrayList<>();
            for (ISeq s = seq.seq(); s != null; s = s.next()) items.add(substituteTemplate(s.first(), subs));
            return PersistentList.create(items);
        }
        return form;
    }

    // ========================================================================
    // clojure.instant
    // ========================================================================

    public static void initInstant() {
        Namespace ns = Namespace.findOrCreate("clojure.instant");

        ns.intern("read-instant-date", Fn.of1("read-instant-date", cs -> Date.from(parseInstantStr(cs.toString()))));
        ns.intern("read-instant-calendar", Fn.of1("read-instant-calendar", cs -> {
            OffsetDateTime odt = parseOffsetDateTime(cs.toString());
            return GregorianCalendar.from(odt.toZonedDateTime());
        }));
        ns.intern("read-instant-timestamp", Fn.of1("read-instant-timestamp", cs -> Timestamp.from(parseInstantStr(cs.toString()))));
        ns.intern("validated", Fn.of1("validated", fn -> fn));
        ns.intern("parse-timestamp", Fn.of2("parse-timestamp", (newInst, cs) -> {
            OffsetDateTime odt = parseOffsetDateTime(cs.toString());
            int offsetSec = odt.getOffset().getTotalSeconds();
            int offsetSign = offsetSec < 0 ? -1 : 1;
            int absSec = Math.abs(offsetSec);
            int offsetHours = absSec / 3600;
            int offsetMinutes = (absSec % 3600) / 60;
            return ((Fn) newInst).applyTo(new Object[]{
                    (long) odt.getYear(),
                    (long) odt.getMonthValue(),
                    (long) odt.getDayOfMonth(),
                    (long) odt.getHour(),
                    (long) odt.getMinute(),
                    (long) odt.getSecond(),
                    (long) odt.getNano(),
                    (long) offsetSign,
                    (long) offsetHours,
                    (long) offsetMinutes
            });
        }));

        Core.markLibLoaded("clojure.instant");
    }

    private static Instant parseInstantStr(String s) {
        return parseOffsetDateTime(s).toInstant();
    }

    private static OffsetDateTime parseOffsetDateTime(String s) {
        String norm = s.trim();
        if (norm.length() == 4) norm = norm + "-01-01T00:00:00Z";
        else if (norm.length() == 7) norm = norm + "-01T00:00:00Z";
        else if (norm.length() == 10 && norm.indexOf('T') < 0) norm = norm + "T00:00:00Z";
        else {
            if (!norm.endsWith("Z") && !norm.matches(".*[+-]\\d\\d:?\\d\\d$")) {
                norm = norm + "Z";
            }
            norm = norm.replaceFirst("([+-]\\d\\d)(\\d\\d)$", "$1:$2");
        }
        return OffsetDateTime.parse(norm);
    }

    // ========================================================================
    // clojure.reflect
    // ========================================================================

    public static void initReflect() {
        Namespace ns = Namespace.findOrCreate("clojure.reflect");
        Core.markLibLoaded("clojure.reflect");

        ns.intern("__type-name-of", Fn.of1("__type-name-of", o -> {
            if (o instanceof Class<?> c) return typeNameOf(c);
            return String.valueOf(o);
        }));

        ns.intern("resolve-class", Fn.of2("resolve-class", (cl, sym) -> {
            String name = String.valueOf(sym);
            return resolveReflectClass(cl instanceof ClassLoader loader ? loader : RT.baseLoader(), name);
        }));

        ns.intern("__reflect-class", Fn.of2("__reflect-class", (clObj, typeref) -> {
            ClassLoader loader = clObj instanceof ClassLoader cl ? cl : RT.baseLoader();
            Class<?> cls = typeref instanceof Class<?> c ? c : resolveReflectClass(loader, String.valueOf(typeref));
            if (cls == null) throw new RuntimeException("Could not resolve class: " + typeref);
            return reflectClass(ns, cls);
        }));

        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, ns));
        try {
            Compiler.load("""
                (defprotocol TypeReference
                  (typename [o] "Returns Java type name string for o."))
                (extend-protocol TypeReference
                  Class
                  (typename [c] (__type-name-of c))
                  Object
                  (typename [o] (str o)))
                (defprotocol Reflector
                  (do-reflect [reflector typeref]))
                (defrecord Constructor [name declaring-class parameter-types exception-types flags])
                (defrecord Method [name return-type declaring-class parameter-types exception-types flags])
                (defrecord Field [name type declaring-class flags])
                (defrecord JavaReflector [classloader]
                  Reflector
                  (do-reflect [_ typeref]
                    (__reflect-class classloader typeref)))
                (def default-reflector (->JavaReflector (clojure.lang.RT/baseLoader)))
                (defn type-reflect [typeref & options]
                  (let [{:keys [ancestors reflector] :or {reflector default-reflector}}
                        (when (seq options) (apply hash-map options))
                        res (do-reflect reflector typeref)]
                    (if-not ancestors
                      res
                      (loop [queue (vec (:bases res))
                             seen #{(symbol (typename typeref))}
                             all-bases (set (:bases res))
                             all-members (set (:members res))]
                        (if (empty? queue)
                          (assoc res :bases all-bases :members all-members)
                          (let [b (peek queue)
                                q (pop queue)]
                            (if (contains? seen b)
                              (recur q seen all-bases all-members)
                              (let [r (try (do-reflect reflector b) (catch Throwable _ nil))]
                                (if r
                                  (recur (into q (:bases r))
                                         (conj seen b)
                                         (into all-bases (:bases r))
                                         (into all-members (:members r)))
                                  (recur q (conj seen b) all-bases all-members))))))))))
                (defn reflect [obj & options]
                  (apply type-reflect (if (class? obj) obj (class obj)) options))
                """, "clojure/reflect.clj");
        } finally {
            Var.popThreadBindings();
        }
    }

    private static Class<?> resolveReflectClass(ClassLoader loader, String name) {
        if (name == null) return null;
        int arrayDims = 0;
        while (name.endsWith("<>")) {
            arrayDims++;
            name = name.substring(0, name.length() - 2);
        }
        Class<?> base = switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "void" -> void.class;
            default -> {
                Class<?> c = Interop.findClass(name);
                if (c != null) yield c;
                try {
                    yield Class.forName(name, false, loader);
                } catch (ClassNotFoundException e) {
                    yield null;
                }
            }
        };
        if (base == null || arrayDims == 0) return base;
        Class<?> arr = base;
        for (int i = 0; i < arrayDims; i++) {
            arr = java.lang.reflect.Array.newInstance(arr, 0).getClass();
        }
        return arr;
    }

    private static String typeNameOf(Class<?> c) {
        if (c.isArray()) return typeNameOf(c.getComponentType()) + "<>";
        return c.getName();
    }

    private static PersistentHashSet modifierFlags(int mod) {
        List<Object> flags = new ArrayList<>();
        if (Modifier.isPublic(mod)) flags.add(Keyword.intern("public"));
        if (Modifier.isPrivate(mod)) flags.add(Keyword.intern("private"));
        if (Modifier.isProtected(mod)) flags.add(Keyword.intern("protected"));
        if (Modifier.isStatic(mod)) flags.add(Keyword.intern("static"));
        if (Modifier.isFinal(mod)) flags.add(Keyword.intern("final"));
        if (Modifier.isAbstract(mod)) flags.add(Keyword.intern("abstract"));
        if (Modifier.isInterface(mod)) flags.add(Keyword.intern("interface"));
        if (Modifier.isSynchronized(mod)) flags.add(Keyword.intern("synchronized"));
        if (Modifier.isVolatile(mod)) flags.add(Keyword.intern("volatile"));
        if (Modifier.isTransient(mod)) flags.add(Keyword.intern("transient"));
        if (Modifier.isNative(mod)) flags.add(Keyword.intern("native"));
        return PersistentHashSet.createFromColl(flags);
    }

    private static PersistentVector classSymVec(Class<?>[] classes) {
        Object[] out = new Object[classes.length];
        for (int i = 0; i < classes.length; i++) out[i] = Symbol.intern(typeNameOf(classes[i]));
        return PersistentVector.createOwning(out);
    }

    private static PersistentHashMap reflectClass(Namespace ns, Class<?> cls) {
        List<Object> bases = new ArrayList<>();
        if (cls.getSuperclass() != null) bases.add(Symbol.intern(cls.getSuperclass().getName()));
        for (Class<?> iface : cls.getInterfaces()) bases.add(Symbol.intern(iface.getName()));

        Fn mkCtor = (Fn) ns.findInternedVar(Symbol.intern("->Constructor")).deref();
        Fn mkMethod = (Fn) ns.findInternedVar(Symbol.intern("->Method")).deref();
        Fn mkField = (Fn) ns.findInternedVar(Symbol.intern("->Field")).deref();

        List<Object> members = new ArrayList<>();
        Symbol declCls = Symbol.intern(cls.getName());
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            members.add(mkCtor.applyTo(new Object[]{
                    declCls, declCls,
                    classSymVec(c.getParameterTypes()),
                    classSymVec(c.getExceptionTypes()),
                    modifierFlags(c.getModifiers())}));
        }
        for (Method m : cls.getDeclaredMethods()) {
            members.add(mkMethod.applyTo(new Object[]{
                    Symbol.intern(m.getName()),
                    Symbol.intern(typeNameOf(m.getReturnType())),
                    declCls,
                    classSymVec(m.getParameterTypes()),
                    classSymVec(m.getExceptionTypes()),
                    modifierFlags(m.getModifiers())}));
        }
        for (Field f : cls.getDeclaredFields()) {
            members.add(mkField.applyTo(new Object[]{
                    Symbol.intern(f.getName()),
                    Symbol.intern(typeNameOf(f.getType())),
                    declCls,
                    modifierFlags(f.getModifiers())}));
        }

        return PersistentHashMap.create(
                Keyword.intern("bases"), PersistentHashSet.createFromColl(bases),
                Keyword.intern("flags"), modifierFlags(cls.getModifiers()),
                Keyword.intern("members"), PersistentHashSet.createFromColl(members));
    }

    // ========================================================================
    // clojure.uuid
    // ========================================================================

    public static void initUuid() {
        Namespace.findOrCreate("clojure.uuid");
        Core.markLibLoaded("clojure.uuid");
    }
}
