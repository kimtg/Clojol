package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Runtime core utilities for Clojol: truthiness, value equivalence, hashing,
 * sequence operations, collection polymorphism, and printing.
 */
public final class RT {

    public static final Namespace CLOJURE_NS = Namespace.findOrCreate(Symbol.intern("clojure.core"));
    public static final Namespace USER_NS = Namespace.findOrCreate(Symbol.intern("user"));

    public static final Var CURRENT_NS = CLOJURE_NS.intern(Symbol.intern("*ns*")).bindRoot(USER_NS).setDynamic();
    public static final Var OUT = CLOJURE_NS.intern(Symbol.intern("*out*"))
            .bindRoot(new OutputStreamWriter(System.out))
            .setDynamic()
            .setMeta(PersistentHashMap.create(Keyword.intern("tag"), Symbol.intern("java.io.Writer")));
    public static final Var ERR = CLOJURE_NS.intern(Symbol.intern("*err*"))
            .bindRoot(new PrintWriter(new OutputStreamWriter(System.err), true))
            .setDynamic();
    public static final Var IN = CLOJURE_NS.intern(Symbol.intern("*in*"))
            .bindRoot(new LineNumberingPushbackReader(new InputStreamReader(System.in)))
            .setDynamic();
    public static final Var PRINT_READABLY = CLOJURE_NS.intern(Symbol.intern("*print-readably*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var PRINT_META = CLOJURE_NS.intern(Symbol.intern("*print-meta*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var PRINT_DUP = CLOJURE_NS.intern(Symbol.intern("*print-dup*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var PRINT_LENGTH = CLOJURE_NS.intern(Symbol.intern("*print-length*")).bindRoot(null).setDynamic();
    public static final Var PRINT_LEVEL = CLOJURE_NS.intern(Symbol.intern("*print-level*")).bindRoot(null).setDynamic();
    public static final Var COMMAND_LINE_ARGS = CLOJURE_NS.intern(Symbol.intern("*command-line-args*")).bindRoot(null).setDynamic();
    public static final Var STAR_1 = CLOJURE_NS.intern(Symbol.intern("*1")).bindRoot(null).setDynamic();
    public static final Var STAR_2 = CLOJURE_NS.intern(Symbol.intern("*2")).bindRoot(null).setDynamic();
    public static final Var STAR_3 = CLOJURE_NS.intern(Symbol.intern("*3")).bindRoot(null).setDynamic();
    public static final Var STAR_E = CLOJURE_NS.intern(Symbol.intern("*e")).bindRoot(null).setDynamic();
    public static final Var FILE = CLOJURE_NS.intern(Symbol.intern("*file*")).bindRoot("NO_SOURCE_PATH").setDynamic();
    public static final Var AGENT = CLOJURE_NS.intern(Symbol.intern("*agent*")).bindRoot(null).setDynamic();
    public static final Var COMPILE_FILES = CLOJURE_NS.intern(Symbol.intern("*compile-files*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var COMPILE_PATH = CLOJURE_NS.intern(Symbol.intern("*compile-path*")).bindRoot("classes").setDynamic();
    public static final Var UNCHECKED_MATH = CLOJURE_NS.intern(Symbol.intern("*unchecked-math*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var WARN_ON_REFLECTION = CLOJURE_NS.intern(Symbol.intern("*warn-on-reflection*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var DATA_READERS = CLOJURE_NS.intern(Symbol.intern("*data-readers*")).bindRoot(PersistentHashMap.EMPTY).setDynamic();
    public static final Var DEFAULT_DATA_READER_FN = CLOJURE_NS.intern(Symbol.intern("*default-data-reader-fn*")).bindRoot(null).setDynamic();
    public static final Var ASSERT = CLOJURE_NS.intern(Symbol.intern("*assert*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var MATH_CONTEXT = CLOJURE_NS.intern(Symbol.intern("*math-context*")).bindRoot(null).setDynamic();
    public static final Var READ_EVAL = CLOJURE_NS.intern(Symbol.intern("*read-eval*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var READER_RESOLVER = CLOJURE_NS.intern(Symbol.intern("*reader-resolver*")).bindRoot(null).setDynamic();
    public static final Var SUPPRESS_READ = CLOJURE_NS.intern(Symbol.intern("*suppress-read*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var PRINT_NAMESPACE_MAPS = CLOJURE_NS.intern(Symbol.intern("*print-namespace-maps*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var FLUSH_ON_NEWLINE = CLOJURE_NS.intern(Symbol.intern("*flush-on-newline*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var REPL = CLOJURE_NS.intern(Symbol.intern("*repl*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var SOURCE_PATH = CLOJURE_NS.intern(Symbol.intern("*source-path*")).bindRoot("NO_SOURCE_PATH").setDynamic();
    public static final Var FN_LOADER = CLOJURE_NS.intern(Symbol.intern("*fn-loader*")).bindRoot(null).setDynamic();
    public static final Var USE_CONTEXT_CLASSLOADER = CLOJURE_NS.intern(Symbol.intern("*use-context-classloader*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var ALLOW_UNRESOLVED_VARS = CLOJURE_NS.intern(Symbol.intern("*allow-unresolved-vars*")).bindRoot(Boolean.FALSE).setDynamic();
    public static final Var COMPILER_OPTIONS = CLOJURE_NS.intern(Symbol.intern("*compiler-options*")).bindRoot(null).setDynamic();
    public static final Var VERBOSE_DEFRECORDS = CLOJURE_NS.intern(Symbol.intern("*verbose-defrecords*")).bindRoot(Boolean.FALSE).setDynamic();

    public static volatile MultiFn PRINT_METHOD_FN = null;
    public static volatile MultiFn PRINT_DUP_FN = null;
    public static final PersistentHashMap DEFAULT_DATA_READERS_MAP = PersistentHashMap.create(
            Symbol.intern("uuid"), new Fn.NamedFn("uuid-reader") {
                @Override public Object invoke(Object s) { return UUID.fromString(s.toString()); }
            },
            Symbol.intern("inst"), new Fn.NamedFn("inst-reader") {
                @Override public Object invoke(Object s) { return Date.from(java.time.Instant.parse(normalizeInst(s.toString()))); }
            }
    );
    public static final Var DEFAULT_DATA_READERS = CLOJURE_NS.intern(Symbol.intern("default-data-readers")).bindRoot(DEFAULT_DATA_READERS_MAP);
    public static final Var CLOJURE_VERSION = CLOJURE_NS.intern(Symbol.intern("*clojure-version*")).bindRoot(
            PersistentHashMap.create(
                    Keyword.intern("major"), 1L,
                    Keyword.intern("minor"), 12L,
                    Keyword.intern("incremental"), 0L,
                    Keyword.intern("qualifier"), "clojol"
            )).setDynamic();

    private static String normalizeInst(String s) {
        if (s.indexOf('T') < 0 && s.length() == 10) return s + "T00:00:00Z";
        if (!s.endsWith("Z") && !s.matches(".*[+-]\\d\\d:?\\d\\d$")) return s + "Z";
        return s.replaceFirst("([+-]\\d\\d)(\\d\\d)$", "$1:$2");
    }

    private static final AtomicInteger GENSYM_ID = new AtomicInteger(1000);
    private static volatile ClassLoader runtimeClassLoader = null;
    private static volatile boolean strictRuntimeLoader = false;
    private static volatile PersistentHashMap classpathDataReaders = null;

    public static PersistentHashMap getClasspathDataReaders() {
        PersistentHashMap cached = classpathDataReaders;
        if (cached != null) return cached;
        return loadClasspathDataReaders();
    }

    private static synchronized PersistentHashMap loadClasspathDataReaders() {
        if (classpathDataReaders != null) return classpathDataReaders;
        PersistentHashMap merged = PersistentHashMap.EMPTY;
        ClassLoader cl = baseLoader();
        for (String resName : new String[]{"data_readers.clj", "data_readers.cljc"}) {
            try {
                Enumeration<java.net.URL> urls = cl.getResources(resName);
                while (urls.hasMoreElements()) {
                    java.net.URL url = urls.nextElement();
                    try (InputStream is = url.openStream()) {
                        String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        Object form = Reader.readString(content);
                        if (form instanceof PersistentHashMap pm) {
                            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                                PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                                if (me.key instanceof Symbol tagSym && me.val instanceof Symbol fnSym) {
                                    merged = merged.assoc(tagSym, fnSym);
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (IOException ignored) {}
        }
        classpathDataReaders = merged;
        return merged;
    }

    private static volatile boolean coreInitialized = false;

    public static void ensureInit() {
        if (!coreInitialized) {
            initCore();
        }
    }

    private static synchronized void initCore() {
        if (coreInitialized) return;
        CLOJURE_NS.setLazyLoader(CoreExtrasIndex.LOADER);
        USER_NS.referAll(CLOJURE_NS);
        coreInitialized = true;
    }

    public static PrintWriter outWriter() {
        Object o = OUT.deref();
        if (o instanceof PrintWriter pw) return pw;
        if (o instanceof Writer w) return new PrintWriter(w, false);
        return new PrintWriter(System.out, false);
    }

    public static void setRuntimeClassLoader(ClassLoader cl) {
        setRuntimeClassLoader(cl, true);
    }

    public static void setRuntimeClassLoader(ClassLoader cl, boolean strict) {
        strictRuntimeLoader = strict;
        runtimeClassLoader = cl;
        classpathDataReaders = null;
        if (cl != null) {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    public static ClassLoader baseLoader() {
        ClassLoader rt = runtimeClassLoader;
        if (rt != null) return rt;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        return ctx != null ? ctx : RT.class.getClassLoader();
    }

    public static boolean hasCustomRuntimeLoader() {
        return runtimeClassLoader != null;
    }

    public static boolean isStrictRuntimeLoader() {
        return strictRuntimeLoader;
    }

    public static Object addClasspath(Object urlObj) {
        try {
            java.net.URL url;
            if (urlObj instanceof java.net.URL u) {
                url = u;
            } else {
                String s = urlObj.toString();
                if (s.startsWith("file:") || s.startsWith("http:") || s.startsWith("https:") || s.startsWith("jar:")) {
                    url = java.net.URI.create(s).toURL();
                } else {
                    url = java.nio.file.Path.of(s).toUri().toURL();
                }
            }
            java.net.URLClassLoader ucl = new java.net.URLClassLoader(new java.net.URL[]{url}, baseLoader());
            setRuntimeClassLoader(ucl, isStrictRuntimeLoader());
            return null;
        } catch (Exception e) {
            throw sneakyThrow(e);
        }
    }

    public static Namespace currentNS() {
        return (Namespace) CURRENT_NS.deref();
    }

    public static int nextID() {
        return GENSYM_ID.incrementAndGet();
    }

    public static String name(Object x) {
        if (x == null) return null;
        if (x instanceof String s) return s;
        if (x instanceof Keyword kw) return kw.getName();
        if (x instanceof Symbol sym) return sym.getName();
        throw new IllegalArgumentException("Doesn't support name: " + x);
    }

    public static String namespace(Object x) {
        if (x == null) return null;
        if (x instanceof Keyword kw) return kw.getNamespace();
        if (x instanceof Symbol sym) return sym.getNamespace();
        throw new IllegalArgumentException("Doesn't support namespace: " + x);
    }

    public static boolean truthy(Object o) {
        return o != null && o != Boolean.FALSE;
    }

    public static boolean equiv(Object k1, Object k2) {
        if (k1 == k2) return true;
        if (k1 == null || k2 == null) return false;
        if (k1 instanceof Long l1 && k2 instanceof Long l2) {
            return l1.longValue() == l2.longValue();
        }
        if (k1 instanceof Number n1 && k2 instanceof Number n2) {
            return Numbers.equiv(n1, n2);
        }
        return k1.equals(k2);
    }

    public static int hash(Object o) {
        if (o == null) return 0;
        if (o instanceof Keyword kw) return kw.hash;
        if (o instanceof String s) return s.hashCode();
        if (o instanceof Long l) return Long.hashCode(l);
        if (o instanceof Integer || o instanceof Short || o instanceof Byte) {
            return Long.hashCode(((Number) o).longValue());
        }
        if (o instanceof Double d) {
            if (d == 0.0) return 0;
            return d.hashCode();
        }
        if (o instanceof Float f) {
            if (f == 0.0f) return 0;
            return Double.hashCode(f.doubleValue());
        }
        if (o instanceof BigInteger bi) {
            if (bi.bitLength() <= 63) return Long.hashCode(bi.longValue());
            return bi.hashCode();
        }
        if (o instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().hashCode();
        }
        return o.hashCode();
    }

    public static int hashCombine(int x, int y) {
        return x ^ (y + 0x9e3779b9 + (x << 6) + (x >>> 2));
    }

    public static int mixCollectionHash(int hashBasis, int count) {
        int k1 = hashBasis * 0xcc9e2d51;
        k1 = Integer.rotateLeft(k1, 15);
        k1 *= 0x1b873593;
        int h1 = Integer.rotateLeft(k1, 13);
        h1 = h1 * 5 + 0xe6546b64;
        h1 ^= count;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }

    public static int hashOrderedColl(Iterable<?> coll) {
        int n = 0;
        int hash = 1;
        for (Object x : coll) {
            hash = 31 * hash + hash(x);
            n++;
        }
        return mixCollectionHash(hash, n);
    }

    public static int hashUnorderedColl(Iterable<?> coll) {
        int n = 0;
        int hash = 0;
        for (Object x : coll) {
            hash += hash(x);
            n++;
        }
        return mixCollectionHash(hash, n);
    }

    public static final class EquivKey {
        public final Object val;
        private final int hash;

        public EquivKey(Object val) {
            this.val = val;
            this.hash = RT.hash(val);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            return obj instanceof EquivKey ek && RT.equiv(val, ek.val);
        }
    }

    public static Object destructureMap(Object x) {
        if (x == null || x instanceof Associative || x instanceof Map<?, ?>) {
            return x;
        }
        if (x instanceof ISeq seq) {
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            ISeq s = seq.seq();
            while (s != null) {
                Object k = s.first();
                ISeq n = s.next();
                if (n == null) {
                    if (k == null) break;
                    if (k instanceof Map<?, ?> m) {
                        for (Map.Entry<?, ?> e : m.entrySet()) {
                            tm.assoc(e.getKey(), e.getValue());
                        }
                        break;
                    }
                    throw new IllegalArgumentException("No value supplied for key: " + k);
                }
                tm.assoc(k, n.first());
                s = n.next();
            }
            return tm.persistent();
        }
        return x;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object k1, Object k2) {
        if (k1 == k2) return 0;
        if (k1 instanceof Long l1 && k2 instanceof Long l2) {
            return Long.compare(l1.longValue(), l2.longValue());
        }
        if (k1 != null) {
            if (k2 == null) return 1;
            if (k1 instanceof Number n1 && k2 instanceof Number n2) {
                return Numbers.compare(n1, n2);
            }
            return ((Comparable) k1).compareTo(k2);
        }
        return -1;
    }

    public static ISeq seq(Object coll) {
        if (coll == null) return null;
        if (coll instanceof ASeq aseq) return aseq.seq();
        if (coll instanceof LazySeq lseq) return lseq.seq();
        if (coll instanceof Seqable s) return s.seq();
        return seqFrom(coll);
    }

    private static ISeq seqFrom(Object coll) {
        if (coll instanceof CharSequence cs) {
            if (cs.isEmpty()) return null;
            return new StringSeq(cs, 0);
        }
        if (coll instanceof Iterable<?> it) {
            return iteratorSeq(it.iterator());
        }
        if (coll instanceof Map<?, ?> m) {
            if (m.isEmpty()) return null;
            List<Object> entries = new ArrayList<>(m.size());
            for (Map.Entry<?, ?> e : m.entrySet()) {
                entries.add(new PersistentHashMap.MapEntry(e.getKey(), e.getValue()));
            }
            return PersistentList.create(entries);
        }
        if (coll instanceof Enumeration<?> en) {
            return iteratorSeq(new Iterator<>() {
                @Override public boolean hasNext() { return en.hasMoreElements(); }
                @Override public Object next() { return en.nextElement(); }
            });
        }
        if (coll instanceof Iterator<?> iter) {
            return iteratorSeq(iter);
        }
        if (coll.getClass().isArray()) {
            int len = Array.getLength(coll);
            if (len == 0) return null;
            Object[] arr = new Object[len];
            for (int i = 0; i < len; i++) arr[i] = Array.get(coll, i);
            return PersistentList.createFromArray(arr);
        }
        throw new IllegalArgumentException("Don't know how to create ISeq from: " + coll.getClass().getName());
    }

    public static ISeq iteratorSeq(Iterator<?> iter) {
        if (iter == null || !iter.hasNext()) return null;
        return new LazySeq(new Fn() {
            @Override
            public Object invoke() {
                if (!iter.hasNext()) return null;
                Object head = iter.next();
                return new Cons(head, iteratorSeq(iter));
            }
        });
    }

    public static Object first(Object x) {
        if (x == null) return null;
        if (x instanceof ISeq s) return s.first();
        if (x instanceof PersistentVector v) return v.cnt > 0 ? v.nth(0) : null;
        if (x instanceof PersistentVector.SubVec sv) return sv.count() > 0 ? sv.nth(0) : null;
        ISeq seq = seq(x);
        return seq == null ? null : seq.first();
    }

    public static Object second(Object x) {
        if (x instanceof PersistentVector v) return v.cnt > 1 ? v.nth(1) : null;
        if (x instanceof PersistentVector.SubVec sv) return sv.count() > 1 ? sv.nth(1) : null;
        return first(next(x));
    }

    public static ISeq next(Object x) {
        if (x == null) return null;
        if (x instanceof ISeq s) return s.next();
        ISeq seq = seq(x);
        return seq == null ? null : seq.next();
    }

    public static ISeq more(Object x) {
        if (x == null) return PersistentList.EMPTY;
        if (x instanceof ISeq s) return s.more();
        ISeq seq = seq(x);
        return seq == null ? PersistentList.EMPTY : seq.more();
    }

    public static ISeq cons(Object x, Object coll) {
        if (coll == null) return new PersistentList(x);
        if (coll instanceof ISeq s) return new Cons(x, s);
        return new Cons(x, seq(coll));
    }

    public static Object conj(Object coll, Object x) {
        if (coll == null) return new PersistentList(x);
        if (coll instanceof PersistentVector v) return v.conj(x);
        if (coll instanceof PersistentVector.SubVec sv) return sv.conj(x);
        if (coll instanceof PersistentList l) return l.cons(x);
        if (coll instanceof PersistentList.EmptyList l) return l.cons(x);
        if (coll instanceof PersistentHashMap m) {
            if (x instanceof Map.Entry<?, ?> e) {
                return m.assoc(e.getKey(), e.getValue());
            }
            if (x instanceof PersistentVector v) {
                if (v.count() != 2) throw new IllegalArgumentException("Vector arg to map conj must be a pair");
                return m.assoc(v.nth(0), v.nth(1));
            }
            if (x instanceof PersistentVector.SubVec sv) {
                if (sv.count() != 2) throw new IllegalArgumentException("Vector arg to map conj must be a pair");
                return m.assoc(sv.nth(0), sv.nth(1));
            }
            if (x instanceof Seqable || x instanceof Map<?, ?>) {
                PersistentHashMap ret = m;
                for (ISeq s = seq(x); s != null; s = s.next()) {
                    Object elem = s.first();
                    if (elem instanceof Map.Entry<?, ?> e) {
                        ret = ret.assoc(e.getKey(), e.getValue());
                    } else if (elem instanceof PersistentVector v && v.count() == 2) {
                        ret = ret.assoc(v.nth(0), v.nth(1));
                    } else if (elem instanceof PersistentVector.SubVec sv && sv.count() == 2) {
                        ret = ret.assoc(sv.nth(0), sv.nth(1));
                    } else {
                        throw new IllegalArgumentException("Cannot conj " + elem + " onto map");
                    }
                }
                return ret;
            }
            throw new IllegalArgumentException("Cannot conj " + x + " onto map");
        }
        if (coll instanceof PersistentHashSet s) return s.conj(x);
        if (coll instanceof PersistentTreeSet s) return s.conj(x);
        if (coll instanceof PersistentTreeMap m) {
            if (x instanceof Map.Entry<?, ?> e) return m.assoc(e.getKey(), e.getValue());
            if (x instanceof PersistentVector v && v.count() == 2) return m.assoc(v.nth(0), v.nth(1));
            if (x instanceof PersistentVector.SubVec sv && sv.count() == 2) return m.assoc(sv.nth(0), sv.nth(1));
            if (x instanceof Seqable || x instanceof Map<?, ?>) {
                PersistentTreeMap ret = m;
                for (ISeq s = seq(x); s != null; s = s.next()) {
                    Object elem = s.first();
                    if (elem instanceof Map.Entry<?, ?> e) ret = ret.assoc(e.getKey(), e.getValue());
                    else if (elem instanceof PersistentVector v && v.count() == 2) ret = ret.assoc(v.nth(0), v.nth(1));
                    else if (elem instanceof PersistentVector.SubVec sv && sv.count() == 2) ret = ret.assoc(sv.nth(0), sv.nth(1));
                    else throw new IllegalArgumentException("Cannot conj " + elem + " onto map");
                }
                return ret;
            }
            throw new IllegalArgumentException("Cannot conj " + x + " onto map");
        }
        if (coll instanceof PersistentRecord rec) {
            if (x instanceof Map.Entry<?, ?> e) return rec.assoc(e.getKey(), e.getValue());
            if (x instanceof PersistentVector v && v.count() == 2) return rec.assoc(v.nth(0), v.nth(1));
            if (x instanceof PersistentVector.SubVec sv && sv.count() == 2) return rec.assoc(sv.nth(0), sv.nth(1));
            throw new IllegalArgumentException("Cannot conj " + x + " onto record");
        }
        if (coll instanceof ISeq s) return cons(x, s);
        if (coll instanceof Set<?> s) {
            Set<Object> copy = new LinkedHashSet<>(s);
            copy.add(x);
            return copy;
        }
        if (coll instanceof PersistentColl pc) return pc.cons(x);
        if (coll instanceof List<?> l) {
            List<Object> copy = new ArrayList<>(l);
            copy.add(x);
            return copy;
        }
        throw new IllegalArgumentException("Don't know how to conj onto: " + coll.getClass().getName());
    }

    public static Associative assoc(Object coll, Object key, Object val) {
        if (coll == null) return PersistentHashMap.EMPTY.assoc(key, val);
        if (coll instanceof Associative a) return a.assoc(key, val);
        if (coll instanceof Map<?, ?> m) return PersistentHashMap.createFromMap(m).assoc(key, val);
        throw new IllegalArgumentException("assoc not supported on: " + coll.getClass().getName());
    }

    public static Object dissoc(Object coll, Object key) {
        if (coll == null) return null;
        if (coll instanceof PersistentHashMap m) return m.without(key);
        if (coll instanceof PersistentTreeMap m) return m.without(key);
        if (coll instanceof PersistentRecord rec) return rec.without(key);
        if (coll instanceof PersistentStructMap sm) return sm.without(key);
        if (coll instanceof PersistentColl pc) return pc.without(key);
        if (coll instanceof Map<?, ?> m) return PersistentHashMap.createFromMap(m).without(key);
        throw new IllegalArgumentException("dissoc not supported on: " + coll.getClass().getName());
    }

    public static Object pop(Object coll) {
        if (coll == null) return null;
        if (coll instanceof PersistentVector pv) return pv.pop();
        if (coll instanceof PersistentList pl) return pl.pop();
        if (coll instanceof PersistentList.EmptyList el) return el.pop();
        if (coll instanceof PersistentColl pc) return pc.pop();
        throw new IllegalArgumentException("pop not supported on: " + coll.getClass().getName());
    }

    public static Object transientColl(Object coll) {
        if (coll instanceof PersistentVector pv) return pv.asTransient();
        if (coll instanceof PersistentHashMap pm) return pm.asTransient();
        if (coll instanceof PersistentHashSet ps) return ps.asTransient();
        throw new IllegalArgumentException("Cannot create transient from: " + coll);
    }

    public static Object persistentBang(Object t) {
        if (t instanceof PersistentVector.TransientVector tv) return tv.persistent();
        if (t instanceof PersistentHashMap.TransientHashMap tm) return tm.persistent();
        if (t instanceof PersistentHashSet.TransientHashSet ts) return ts.persistent();
        throw new IllegalArgumentException("Not a transient: " + t);
    }

    public static Object conjBang(Object t, Object x) {
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

    public static Object assocBang(Object t, Object k, Object v) {
        if (t instanceof PersistentHashMap.TransientHashMap tm) return tm.assoc(k, v);
        if (t instanceof PersistentVector.TransientVector tv) return tv.assocN((int) Numbers.toLong(k), v);
        throw new IllegalArgumentException("Not an associative transient: " + t);
    }

    public static boolean isVector(Object x) {
        return x instanceof PersistentVector || x instanceof PersistentVector.SubVec
                || x instanceof PersistentHashMap.MapEntry;
    }

    public static Object get(Object coll, Object key) {
        return get(coll, key, null);
    }

    public static Object get(Object coll, Object key, Object notFound) {
        if (coll == null) return notFound;
        if (coll instanceof PersistentHashMap m) return m.valAt(key, notFound);
        if (coll instanceof ILookup lu) return lu.valAt(key, notFound);
        if (coll instanceof PersistentHashSet s) return s.get(key, notFound);
        if (coll instanceof PersistentTreeSet s) return s.get(key, notFound);
        if (coll instanceof Map<?, ?> m) {
            if (m.containsKey(key)) return m.get(key);
            return notFound;
        }
        if (coll instanceof Set<?> s) {
            return s.contains(key) ? key : notFound;
        }
        if (coll instanceof CharSequence cs && key instanceof Number n) {
            int i = n.intValue();
            return (i >= 0 && i < cs.length()) ? cs.charAt(i) : notFound;
        }
        if (coll.getClass().isArray() && key instanceof Number n) {
            int i = n.intValue();
            return (i >= 0 && i < Array.getLength(coll)) ? Array.get(coll, i) : notFound;
        }
        if (coll instanceof PersistentVector.TransientVector tv && key instanceof Number n) {
            int i = n.intValue();
            return (i >= 0 && i < tv.count()) ? tv.nth(i) : notFound;
        }
        if (coll instanceof PersistentHashMap.TransientHashMap tm) {
            return tm.valAt(key, notFound);
        }
        if (coll instanceof PersistentHashSet.TransientHashSet ts) {
            return ts.invoke(key, notFound);
        }
        return notFound;
    }

    public static boolean contains(Object coll, Object key) {
        if (coll == null) return false;
        if (coll instanceof Associative a) return a.containsKey(key);
        if (coll instanceof PersistentHashSet s) return s.contains(key);
        if (coll instanceof PersistentTreeSet s) return s.contains(key);
        if (coll instanceof Map<?, ?> m) return m.containsKey(key);
        if (coll instanceof Set<?> s) return s.contains(key);
        if (coll instanceof CharSequence cs && key instanceof Number n) {
            int i = n.intValue();
            return i >= 0 && i < cs.length();
        }
        if (coll.getClass().isArray() && key instanceof Number n) {
            int i = n.intValue();
            return i >= 0 && i < Array.getLength(coll);
        }
        return false;
    }

    public static Object find(Object coll, Object key) {
        if (coll == null) return null;
        if (coll instanceof PersistentHashMap m) return m.entryAt(key);
        if (coll instanceof PersistentTreeMap m) return m.entryAt(key);
        if (coll instanceof PersistentRecord r) return r.entryAt(key);
        if (coll instanceof PersistentVector v && key instanceof Number n) {
            int i = n.intValue();
            if (i >= 0 && i < v.count()) return new PersistentHashMap.MapEntry(i, v.nth(i));
            return null;
        }
        if (coll instanceof PersistentVector.SubVec sv && key instanceof Number n) {
            int i = n.intValue();
            if (i >= 0 && i < sv.count()) return new PersistentHashMap.MapEntry(i, sv.nth(i));
            return null;
        }
        if (coll instanceof Map<?, ?> m) {
            if (m.containsKey(key)) return new PersistentHashMap.MapEntry(key, m.get(key));
        }
        return null;
    }

    public static int count(Object o) {
        if (o == null) return 0;
        if (o instanceof Counted c) return c.count();
        if (o instanceof CharSequence cs) return cs.length();
        if (o instanceof Collection<?> c) return c.size();
        if (o instanceof Map<?, ?> m) return m.size();
        if (o.getClass().isArray()) return Array.getLength(o);
        int i = 0;
        for (ISeq s = seq(o); s != null; s = s.next()) {
            if (s instanceof Counted c) return i + c.count();
            i++;
        }
        return i;
    }

    public static Object nth(Object coll, int n) {
        if (coll == null) return null;
        if (coll instanceof PersistentVector v) return v.nth(n);
        if (coll instanceof PersistentVector.SubVec sv) return sv.nth(n);
        if (coll instanceof CharSequence cs) return cs.charAt(n);
        if (coll.getClass().isArray()) return Array.get(coll, n);
        if (coll instanceof RandomAccess && coll instanceof List<?> l) return l.get(n);
        if (coll instanceof Map.Entry<?, ?> e) {
            if (n == 0) return e.getKey();
            if (n == 1) return e.getValue();
            throw new IndexOutOfBoundsException();
        }
        if (n < 0) throw new IndexOutOfBoundsException("Index cannot be negative: " + n);
        if (coll instanceof PersistentList pl) {
            if (n >= pl.count) throw new IndexOutOfBoundsException("Index " + n + " out of bounds for length " + pl.count);
            PersistentList cur = pl;
            for (int i = 0; i < n; i++) {
                if (cur.rest instanceof PersistentList nextPl) {
                    cur = nextPl;
                } else {
                    ISeq s = cur.next();
                    for (int j = i + 1; j < n && s != null; j++) s = s.next();
                    if (s == null) throw new IndexOutOfBoundsException("Index " + n + " out of bounds");
                    return s.first();
                }
            }
            return cur.first;
        }
        if (coll instanceof PersistentList.EmptyList) {
            throw new IndexOutOfBoundsException("Index " + n + " out of bounds for length 0");
        }
        ISeq s = seq(coll);
        for (int i = 0; i <= n && s != null; i++, s = s.next()) {
            if (i == n) return s.first();
        }
        throw new IndexOutOfBoundsException("Index " + n + " out of bounds");
    }

    public static Object nth(Object coll, int n, Object notFound) {
        if (coll == null) return notFound;
        if (n < 0) return notFound;
        if (coll instanceof PersistentVector v) return v.nth(n, notFound);
        if (coll instanceof PersistentVector.SubVec sv) return sv.nth(n, notFound);
        if (coll instanceof CharSequence cs) return n < cs.length() ? cs.charAt(n) : notFound;
        if (coll.getClass().isArray()) return n < Array.getLength(coll) ? Array.get(coll, n) : notFound;
        if (coll instanceof RandomAccess && coll instanceof List<?> l) return n < l.size() ? l.get(n) : notFound;
        if (coll instanceof Map.Entry<?, ?> e) {
            if (n == 0) return e.getKey();
            if (n == 1) return e.getValue();
            return notFound;
        }
        if (coll instanceof PersistentList pl) {
            if (n >= pl.count) return notFound;
            PersistentList cur = pl;
            for (int i = 0; i < n; i++) {
                if (cur.rest instanceof PersistentList nextPl) {
                    cur = nextPl;
                } else {
                    ISeq s = cur.next();
                    for (int j = i + 1; j < n && s != null; j++) s = s.next();
                    return s == null ? notFound : s.first();
                }
            }
            return cur.first;
        }
        if (coll instanceof PersistentList.EmptyList) return notFound;
        ISeq s = seq(coll);
        for (int i = 0; i <= n && s != null; i++, s = s.next()) {
            if (i == n) return s.first();
        }
        return notFound;
    }

    public static int alength(Object arr) {
        if (arr instanceof double[] da) return da.length;
        if (arr instanceof long[] la) return la.length;
        if (arr instanceof int[] ia) return ia.length;
        if (arr instanceof Object[] oa) return oa.length;
        if (arr instanceof byte[] ba) return ba.length;
        if (arr instanceof short[] sa) return sa.length;
        if (arr instanceof float[] fa) return fa.length;
        if (arr instanceof char[] ca) return ca.length;
        if (arr instanceof boolean[] za) return za.length;
        return Array.getLength(arr);
    }

    public static Object aget(Object arr, int idx) {
        if (arr instanceof double[] da) return da[idx];
        if (arr instanceof long[] la) return la[idx];
        if (arr instanceof int[] ia) return (long) ia[idx];
        if (arr instanceof Object[] oa) return oa[idx];
        if (arr instanceof byte[] ba) return (long) ba[idx];
        if (arr instanceof short[] sa) return (long) sa[idx];
        if (arr instanceof float[] fa) return (double) fa[idx];
        if (arr instanceof char[] ca) return ca[idx];
        if (arr instanceof boolean[] za) return za[idx] ? Boolean.TRUE : Boolean.FALSE;
        return Interop.boxResult(Array.get(arr, idx));
    }

    public static Object aset(Object arr, int idx, Object val) {
        if (arr instanceof double[] da) {
            da[idx] = Numbers.toDouble(val);
            return val;
        }
        if (arr instanceof long[] la) {
            la[idx] = Numbers.toLong(val);
            return val;
        }
        if (arr instanceof int[] ia) {
            ia[idx] = (int) Numbers.toLong(val);
            return val;
        }
        if (arr instanceof Object[] oa) {
            oa[idx] = val;
            return val;
        }
        if (arr instanceof byte[] ba) {
            ba[idx] = (byte) Numbers.toLong(val);
            return val;
        }
        if (arr instanceof short[] sa) {
            sa[idx] = (short) Numbers.toLong(val);
            return val;
        }
        if (arr instanceof float[] fa) {
            fa[idx] = (float) Numbers.toDouble(val);
            return val;
        }
        if (arr instanceof char[] ca) {
            ca[idx] = (val instanceof Character c) ? c : (char) Numbers.toLong(val);
            return val;
        }
        if (arr instanceof boolean[] za) {
            za[idx] = RT.truthy(val);
            return val;
        }
        Class<?> comp = arr.getClass().getComponentType();
        Array.set(arr, idx, Interop.coerceArg(comp, val));
        return val;
    }

    public static ISeq keys(Object coll) {
        if (coll == null) return null;
        ISeq s = seq(coll);
        if (s == null) return null;
        return mapSeq(new Fn() {
            @Override public Object invoke(Object e) {
                return ((Map.Entry<?, ?>) e).getKey();
            }
        }, s);
    }

    public static ISeq vals(Object coll) {
        if (coll == null) return null;
        ISeq s = seq(coll);
        if (s == null) return null;
        return mapSeq(new Fn() {
            @Override public Object invoke(Object e) {
                return ((Map.Entry<?, ?>) e).getValue();
            }
        }, s);
    }

    public static ISeq mapSeq(Fn f, ISeq s) {
        if (s == null) return null;
        return new LazySeq(new Fn() {
            @Override
            public Object invoke() {
                ISeq sq = s.seq();
                if (sq == null) return null;
                return new Cons(f.invoke(sq.first()), mapSeq(f, sq.next()));
            }
        });
    }

    public static PersistentHashMap map(Object... init) {
        return PersistentHashMap.create(init);
    }

    public static PersistentHashMap mapUniqueKeys(Object... init) {
        return PersistentHashMap.createWithCheck(init);
    }

    public static PersistentVector vector(Object... init) {
        return PersistentVector.createOwning(init);
    }

    public static PersistentHashSet set(Object... init) {
        return PersistentHashSet.create(init);
    }

    public static Object[] toArray(Object coll) {
        if (coll == null) return Fn.EMPTY_ARGS;
        if (coll instanceof Object[] arr) return arr;
        if (coll instanceof PersistentVector.RVecSeq rvs) return rvs.toReverseArray();
        if (coll instanceof LongArraySeq las) return las.toBoxedArray();
        if (coll instanceof LongRange lr) {
            int n = lr.count();
            Object[] ret = new Object[n];
            long v = lr.start;
            long step = lr.step;
            for (int i = 0; i < n; i++) { ret[i] = Long.valueOf(v); v += step; }
            return ret;
        }
        if (coll instanceof Collection<?> c) return c.toArray();
        if (coll.getClass().isArray()) {
            int n = Array.getLength(coll);
            Object[] ret = new Object[n];
            for (int i = 0; i < n; i++) ret[i] = Array.get(coll, i);
            return ret;
        }
        List<Object> list = new ArrayList<>();
        for (ISeq s = seq(coll); s != null; s = s.next()) {
            list.add(s.first());
        }
        return list.toArray();
    }

    public static String printString(Object x) {
        return printString(x, truthy(PRINT_READABLY.get()));
    }

    public static String printString(Object x, boolean readably) {
        StringBuilder sb = new StringBuilder();
        print(x, sb, readably);
        return sb.toString();
    }

    public static void print(Object x, StringBuilder sb, boolean readably) {
        int maxLen = PRINT_LENGTH.get() instanceof Number n ? n.intValue() : -1;
        int maxLevel = PRINT_LEVEL.get() instanceof Number n ? n.intValue() : -1;
        boolean printMeta = truthy(PRINT_META.get()) && readably;
        printDepth(x, sb, readably, printMeta, maxLen, maxLevel, 0);
    }

    public static void printSimple(Object x, Writer w) {
        StringBuilder sb = new StringBuilder();
        int maxLen = PRINT_LENGTH.get() instanceof Number n ? n.intValue() : -1;
        int maxLevel = PRINT_LEVEL.get() instanceof Number n ? n.intValue() : -1;
        boolean readably = truthy(PRINT_READABLY.get());
        boolean printMeta = truthy(PRINT_META.get()) && readably;
        printSimpleDepth(x, sb, readably, printMeta, maxLen, maxLevel, 0);
        try {
            w.write(sb.toString());
        } catch (IOException e) {
            throw sneakyThrow(e);
        }
    }

    public static boolean hasCustomPrintMethod() {
        MultiFn mf = truthy(PRINT_DUP.get()) ? PRINT_DUP_FN : PRINT_METHOD_FN;
        return mf != null && mf.getMethodTable().count() > 1;
    }

    private static void printDepth(Object x, StringBuilder sb, boolean readably, boolean printMeta, int maxLen, int maxLevel, int level) {
        MultiFn mf = truthy(PRINT_DUP.get()) ? PRINT_DUP_FN : PRINT_METHOD_FN;
        if (mf != null && mf.getMethodTable().count() > 1) {
            Fn custom = mf.getMethod(x == null ? null : x.getClass());
            Fn defMethod = mf.getMethod(Keyword.intern("default"));
            if (custom != null && custom != defMethod) {
                StringWriter sw = new StringWriter();
                custom.invoke(x, sw);
                sb.append(sw.toString());
                return;
            }
        }
        printSimpleDepth(x, sb, readably, printMeta, maxLen, maxLevel, level);
    }

    private static void printSimpleDepth(Object x, StringBuilder sb, boolean readably, boolean printMeta, int maxLen, int maxLevel, int level) {
        if (x == null) {
            sb.append("nil");
            return;
        }
        if (printMeta && x instanceof IMeta im && im.meta() != null && !im.meta().isEmpty()) {
            sb.append('^');
            printDepth(im.meta(), sb, true, printMeta, maxLen, maxLevel, level);
            sb.append(' ');
        }
        if (x instanceof String s) {
            if (readably) {
                sb.append('"');
                for (int i = 0; i < s.length(); i++) {
                    char c = s.charAt(i);
                    switch (c) {
                        case '\n' -> sb.append("\\n");
                        case '\t' -> sb.append("\\t");
                        case '\r' -> sb.append("\\r");
                        case '\b' -> sb.append("\\b");
                        case '\f' -> sb.append("\\f");
                        case '"' -> sb.append("\\\"");
                        case '\\' -> sb.append("\\\\");
                        default -> sb.append(c);
                    }
                }
                sb.append('"');
            } else {
                sb.append(s);
            }
            return;
        }
        if (x instanceof Character c) {
            if (readably) {
                sb.append('\\');
                switch (c) {
                    case '\n' -> sb.append("newline");
                    case ' ' -> sb.append("space");
                    case '\t' -> sb.append("tab");
                    case '\b' -> sb.append("backspace");
                    case '\f' -> sb.append("formfeed");
                    case '\r' -> sb.append("return");
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
            return;
        }
        if (x instanceof Pattern p) {
            sb.append("#\"").append(p.pattern()).append('"');
            return;
        }
        if (x instanceof BigDecimal bd) {
            sb.append(bd.toString());
            if (readably) sb.append('M');
            return;
        }
        if (x instanceof BigInteger bi) {
            sb.append(bi.toString());
            if (readably && bi.bitLength() <= 63) sb.append('N');
            return;
        }
        if (x instanceof Double d) {
            if (d == Double.POSITIVE_INFINITY) sb.append("##Inf");
            else if (d == Double.NEGATIVE_INFINITY) sb.append("##-Inf");
            else if (Double.isNaN(d)) sb.append("##NaN");
            else sb.append(d.toString());
            return;
        }
        if (x instanceof Float f) {
            if (f == Float.POSITIVE_INFINITY) sb.append("##Inf");
            else if (f == Float.NEGATIVE_INFINITY) sb.append("##-Inf");
            else if (Float.isNaN(f)) sb.append("##NaN");
            else sb.append(f.toString());
            return;
        }
        if (x instanceof PersistentVector v) {
            if (maxLevel >= 0 && level >= maxLevel) { sb.append('#'); return; }
            sb.append('[');
            int lim = maxLen >= 0 ? Math.min(maxLen, v.cnt) : v.cnt;
            for (int i = 0; i < lim; i++) {
                if (i > 0) sb.append(' ');
                printDepth(v.nth(i), sb, readably, printMeta, maxLen, maxLevel, level + 1);
            }
            if (maxLen >= 0 && v.cnt > maxLen) {
                if (lim > 0) sb.append(' ');
                sb.append("...");
            }
            sb.append(']');
            return;
        }
        if (x instanceof PersistentHashMap.MapEntry me) {
            if (maxLevel >= 0 && level >= maxLevel) { sb.append('#'); return; }
            sb.append('[');
            printDepth(me.key, sb, readably, printMeta, maxLen, maxLevel, level + 1);
            sb.append(' ');
            printDepth(me.val, sb, readably, printMeta, maxLen, maxLevel, level + 1);
            sb.append(']');
            return;
        }
        if (x instanceof Map<?, ?> m) {
            if (maxLevel >= 0 && level >= maxLevel) { sb.append('#'); return; }
            sb.append('{');
            int count = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (maxLen >= 0 && count >= maxLen) {
                    if (count > 0) sb.append(", ");
                    sb.append("...");
                    break;
                }
                if (count > 0) sb.append(", ");
                printDepth(e.getKey(), sb, readably, printMeta, maxLen, maxLevel, level + 1);
                sb.append(' ');
                printDepth(e.getValue(), sb, readably, printMeta, maxLen, maxLevel, level + 1);
                count++;
            }
            sb.append('}');
            return;
        }
        if (x instanceof Set<?> set) {
            if (maxLevel >= 0 && level >= maxLevel) { sb.append('#'); return; }
            sb.append("#{");
            int count = 0;
            for (Object item : set) {
                if (maxLen >= 0 && count >= maxLen) {
                    if (count > 0) sb.append(' ');
                    sb.append("...");
                    break;
                }
                if (count > 0) sb.append(' ');
                printDepth(item, sb, readably, printMeta, maxLen, maxLevel, level + 1);
                count++;
            }
            sb.append('}');
            return;
        }
        if (x instanceof ISeq || x instanceof Seqable || x instanceof List<?>) {
            if (maxLevel >= 0 && level >= maxLevel) { sb.append('#'); return; }
            sb.append('(');
            ISeq s = seq(x);
            int count = 0;
            while (s != null) {
                if (maxLen >= 0 && count >= maxLen) {
                    if (count > 0) sb.append(' ');
                    sb.append("...");
                    break;
                }
                if (count > 0) sb.append(' ');
                printDepth(s.first(), sb, readably, printMeta, maxLen, maxLevel, level + 1);
                s = s.next();
                count++;
            }
            sb.append(')');
            return;
        }
        if (x instanceof UUID u) {
            if (readably) sb.append("#uuid \"").append(u.toString()).append('"');
            else sb.append(u.toString());
            return;
        }
        if (x instanceof Date dt) {
            if (readably) sb.append("#inst \"").append(dt.toInstant().toString()).append('"');
            else sb.append(dt.toString());
            return;
        }
        if (x instanceof Class<?> c) {
            sb.append(c.getName());
            return;
        }
        if (x instanceof TaggedLiteral tl) {
            sb.append('#').append(tl.tag).append(' ');
            printDepth(tl.form, sb, readably, printMeta, maxLen, maxLevel, level + 1);
            return;
        }
        if (x instanceof ReaderConditional rc) {
            sb.append(rc.splicing ? "#?@" : "#?");
            printDepth(rc.form, sb, readably, printMeta, maxLen, maxLevel, level + 1);
            return;
        }
        sb.append(x.toString());
    }

    public static Class<?> classForName(String name) throws ClassNotFoundException {
        Class<?> c = Interop.findClass(name);
        if (c != null) return c;
        return Class.forName(name, true, baseLoader());
    }

    public static Class<?> classForName(String name, boolean doInit, ClassLoader loader) throws ClassNotFoundException {
        Class<?> c = Interop.findClass(name);
        if (c != null) return c;
        return Class.forName(name, doInit, loader != null ? loader : baseLoader());
    }

    public static Class<?> classForNameNonLoading(String name) throws ClassNotFoundException {
        Class<?> c = Interop.findClass(name);
        if (c != null) return c;
        return Class.forName(name, false, baseLoader());
    }

    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
