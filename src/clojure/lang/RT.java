package clojure.lang;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

/**
 * Clean-room clojure.lang.RT runtime bridge for full Clojure 1.12 JVM binary compatibility.
 * Enables precompiled Java and AOT-compiled Clojure JARs to call clojure.lang.RT.* directly.
 */
public final class RT {
    public static final Boolean T = Boolean.TRUE;
    public static final Boolean F = Boolean.FALSE;
    public static final Object[] EMPTY_ARRAY = new Object[0];
    public static final cljl.Namespace CLOJURE_NS = cljl.RT.CLOJURE_NS;
    public static final cljl.Var CURRENT_NS = cljl.RT.CURRENT_NS;
    public static final cljl.Var OUT = cljl.RT.OUT;
    public static final cljl.Var IN = cljl.RT.IN;
    public static final cljl.Var ERR = cljl.RT.ERR;
    public static final cljl.Var PRINT_META = cljl.RT.PRINT_META;
    public static final cljl.Var PRINT_READABLY = cljl.RT.PRINT_READABLY;
    public static final cljl.Var PRINT_DUP = cljl.RT.PRINT_DUP;
    public static final cljl.Var PRINT_LENGTH = cljl.RT.PRINT_LENGTH;
    public static final cljl.Var PRINT_LEVEL = cljl.RT.PRINT_LEVEL;
    public static final cljl.Var PRINT_NAMESPACE_MAPS = cljl.RT.PRINT_NAMESPACE_MAPS;
    public static final cljl.Var FLUSH_ON_NEWLINE = cljl.RT.FLUSH_ON_NEWLINE;
    public static final cljl.Var WARN_ON_REFLECTION = cljl.RT.WARN_ON_REFLECTION;
    public static final cljl.Var UNCHECKED_MATH = cljl.RT.UNCHECKED_MATH;
    public static final cljl.Var READEVAL = cljl.RT.READ_EVAL;
    public static final cljl.Var READER_RESOLVER = cljl.RT.READER_RESOLVER;
    public static final cljl.Var SUPPRESS_READ = cljl.RT.SUPPRESS_READ;
    public static final cljl.Var DATA_READERS = cljl.RT.DATA_READERS;
    public static final cljl.Var DEFAULT_DATA_READERS = cljl.RT.DEFAULT_DATA_READERS;
    public static final cljl.Var USE_CONTEXT_CLASSLOADER = cljl.RT.USE_CONTEXT_CLASSLOADER;
    public static final cljl.Var FN_LOADER = cljl.RT.FN_LOADER;
    public static final cljl.Var ASSERT = cljl.RT.ASSERT;
    public static final cljl.Var MATH_CONTEXT = cljl.RT.MATH_CONTEXT;
    public static final cljl.Var AGENT = cljl.RT.AGENT;
    public static final cljl.Var FILE = cljl.RT.FILE;
    public static final cljl.Var COMMAND_LINE_ARGS = cljl.RT.COMMAND_LINE_ARGS;

    static {
        cljl.RT.ensureInit();
    }

    private RT() {}

    public static cljl.Var var(String ns, String name) {
        cljl.RT.ensureInit();
        cljl.Namespace target = cljl.Namespace.findOrCreate(cljl.Symbol.intern(ns));
        return target.intern(cljl.Symbol.intern(name));
    }

    public static cljl.Var var(String ns, String name, Object init) {
        cljl.Var v = var(ns, name);
        v.bindRoot(init);
        return v;
    }

    public static cljl.Keyword keyword(String ns, String name) {
        return cljl.Keyword.intern(ns, name);
    }

    public static ISeq seq(Object coll) { return cljl.RT.seq(coll); }
    public static Object first(Object x) { return cljl.RT.first(x); }
    public static ISeq next(Object x) { return cljl.RT.next(x); }
    public static ISeq more(Object x) { return cljl.RT.more(x); }
    public static Object second(Object x) { return first(next(x)); }
    public static Object third(Object x) { return first(next(next(x))); }
    public static Object fourth(Object x) { return first(next(next(next(x)))); }
    public static Object peek(Object x) { return x == null ? null : ((IPersistentStack) x).peek(); }
    public static Object pop(Object x) { return cljl.RT.pop(x); }
    public static ISeq cons(Object x, Object coll) { return cljl.RT.cons(x, coll); }
    public static IPersistentCollection conj(IPersistentCollection coll, Object x) {
        return (IPersistentCollection) cljl.RT.conj(coll, x);
    }

    public static int count(Object o) { return cljl.RT.count(o); }
    public static Object nth(Object coll, int n) { return cljl.RT.nth(coll, n); }
    public static Object nth(Object coll, int n, Object notFound) { return cljl.RT.nth(coll, n, notFound); }
    public static Object get(Object coll, Object key) { return cljl.RT.get(coll, key); }
    public static Object get(Object coll, Object key, Object notFound) { return cljl.RT.get(coll, key, notFound); }
    public static Associative assoc(Object coll, Object key, Object val) { return (Associative) cljl.RT.assoc(coll, key, val); }
    public static Object dissoc(Object coll, Object key) { return cljl.RT.dissoc(coll, key); }
    public static Object contains(Object coll, Object key) { return cljl.RT.contains(coll, key) ? T : F; }
    public static Object find(Object coll, Object key) { return cljl.RT.find(coll, key); }
    public static ISeq keys(Object coll) {
        cljl.RT.ensureInit();
        return (ISeq) var("clojure.core", "keys").invoke(coll);
    }
    public static ISeq vals(Object coll) {
        cljl.RT.ensureInit();
        return (ISeq) var("clojure.core", "vals").invoke(coll);
    }

    public static cljl.lang.PersistentVector vector(Object... init) { return cljl.lang.PersistentVector.create(init); }
    public static IPersistentVector vec(Object coll) {
        if (coll instanceof IPersistentVector v) return v;
        return cljl.lang.PersistentVector.create(cljl.RT.toArray(coll));
    }
    public static IPersistentMap map(Object... init) { return cljl.lang.PersistentArrayMap.create(init); }
    public static IPersistentMap mapUniqueKeys(Object... init) { return cljl.lang.PersistentArrayMap.create(init); }
    public static cljl.lang.PersistentHashSet set(Object... init) { return cljl.lang.PersistentHashSet.create(init); }
    public static ISeq list() { return null; }
    public static ISeq list(Object a1) { return new cljl.lang.PersistentList(a1); }
    public static ISeq list(Object a1, Object a2) { return cljl.lang.PersistentList.createFromArray(a1, a2); }
    public static ISeq list(Object a1, Object a2, Object a3) { return cljl.lang.PersistentList.createFromArray(a1, a2, a3); }
    public static ISeq list(Object a1, Object a2, Object a3, Object a4) { return cljl.lang.PersistentList.createFromArray(a1, a2, a3, a4); }
    public static ISeq list(Object a1, Object a2, Object a3, Object a4, Object a5) { return cljl.lang.PersistentList.createFromArray(a1, a2, a3, a4, a5); }
    public static ISeq listStar(Object arg1, ISeq rest) { return cons(arg1, rest); }
    public static ISeq listStar(Object arg1, Object arg2, ISeq rest) { return cons(arg1, cons(arg2, rest)); }
    public static ISeq listStar(Object arg1, Object arg2, Object arg3, ISeq rest) { return cons(arg1, cons(arg2, cons(arg3, rest))); }
    public static IPersistentVector subvec(IPersistentVector v, int start, int end) {
        if (v instanceof cljl.lang.PersistentVector pv) return (IPersistentVector) pv.subVec(start, end);
        if (v instanceof cljl.lang.PersistentVector.SubVec sv) return (IPersistentVector) sv.subVec(start, end);
        cljl.lang.PersistentVector.TransientVector tv = cljl.lang.PersistentVector.EMPTY.asTransient();
        for (int i = start; i < end; i++) tv = tv.conj(v.nth(i));
        return tv.persistent();
    }

    public static boolean booleanCast(Object x) { return cljl.RT.truthy(x); }
    public static boolean booleanCast(boolean x) { return x; }
    public static int intCast(Object x) { return cljl.Numbers.intCast(x); }
    public static int intCast(long x) { return Math.toIntExact(x); }
    public static int intCast(int x) { return x; }
    public static int intCast(double x) { return cljl.Numbers.intCast(Double.valueOf(x)); }
    public static int uncheckedIntCast(Object x) { return cljl.Numbers.uncheckedInt(x); }
    public static int uncheckedIntCast(long x) { return (int) x; }
    public static long longCast(Object x) { return cljl.Numbers.longCast(x); }
    public static long longCast(long x) { return x; }
    public static long longCast(int x) { return x; }
    public static long longCast(double x) { return cljl.Numbers.longCast(x); }
    public static long uncheckedLongCast(Object x) { return cljl.Numbers.uncheckedLong(x); }
    public static double doubleCast(Object x) { return cljl.Numbers.toDouble(x); }
    public static double doubleCast(double x) { return x; }
    public static double doubleCast(long x) { return (double) x; }
    public static float floatCast(Object x) { return (float) cljl.Numbers.toDouble(x); }
    public static short shortCast(Object x) { return cljl.Numbers.shortCast(x); }
    public static byte byteCast(Object x) { return cljl.Numbers.byteCast(x); }
    public static char charCast(Object x) { return cljl.Numbers.charCast(x); }

    public static boolean isReduced(Object r) { return r instanceof Reduced; }
    public static Object deref(Object ref) { return cljl.RT.deref(ref); }
    public static IPersistentMap meta(Object x) { return (x instanceof IMeta m) ? m.meta() : null; }
    public static Object withMeta(IObj x, IPersistentMap m) { return x.withMeta(m); }

    public static void load(String scriptbase) throws IOException, ClassNotFoundException {
        load(scriptbase, true);
    }

    public static void load(String scriptbase, boolean failIfNotFound) throws IOException, ClassNotFoundException {
        cljl.RT.ensureInit();
        String nsName = scriptbase.startsWith("/") ? scriptbase.substring(1) : scriptbase;
        nsName = nsName.replace('/', '.').replace('_', '-');
        try {
            cljl.Core.loadLib(nsName);
        } catch (RuntimeException e) {
            if (failIfNotFound) throw e;
        }
    }

    public static ClassLoader baseLoader() { return cljl.RT.baseLoader(); }
    public static ClassLoader makeClassLoader() { return cljl.RT.makeClassLoader(); }
    public static java.io.InputStream resourceAsStream(ClassLoader loader, String name) {
        return (loader != null ? loader : baseLoader()).getResourceAsStream(name);
    }
    public static Object readString(String s) { cljl.RT.ensureInit(); return cljl.Reader.readString(s); }
    public static Object readString(String s, Object opts) {
        cljl.RT.ensureInit();
        return opts instanceof Map<?, ?> m ? cljl.Reader.readString(m, s) : cljl.Reader.readString(s);
    }
    public static String printString(Object x) { return cljl.RT.printString(x); }
    public static void print(Object x, java.io.Writer w) throws IOException {
        w.write(cljl.RT.printString(x));
    }
    public static Class<?> classForName(String name) throws ClassNotFoundException {
        return cljl.RT.classForName(name);
    }
    public static Class<?> classForName(String name, boolean doInit, ClassLoader loader) throws ClassNotFoundException {
        return cljl.RT.classForName(name, doInit, loader);
    }
    public static Class<?> classForNameNonLoading(String name) throws ClassNotFoundException {
        return cljl.RT.classForNameNonLoading(name);
    }

    public static Object aget(Object[] xs, int i) { return xs[i]; }
    public static int aget(int[] xs, int i) { return xs[i]; }
    public static long aget(long[] xs, int i) { return xs[i]; }
    public static double aget(double[] xs, int i) { return xs[i]; }
    public static float aget(float[] xs, int i) { return xs[i]; }
    public static short aget(short[] xs, int i) { return xs[i]; }
    public static byte aget(byte[] xs, int i) { return xs[i]; }
    public static char aget(char[] xs, int i) { return xs[i]; }
    public static boolean aget(boolean[] xs, int i) { return xs[i]; }

    public static Object aset(Object[] xs, int i, Object v) { xs[i] = v; return v; }
    public static int aset(int[] xs, int i, int v) { xs[i] = v; return v; }
    public static long aset(long[] xs, int i, long v) { xs[i] = v; return v; }
    public static double aset(double[] xs, int i, double v) { xs[i] = v; return v; }
    public static float aset(float[] xs, int i, float v) { xs[i] = v; return v; }
    public static short aset(short[] xs, int i, short v) { xs[i] = v; return v; }
    public static byte aset(byte[] xs, int i, byte v) { xs[i] = v; return v; }
    public static char aset(char[] xs, int i, char v) { xs[i] = v; return v; }
    public static boolean aset(boolean[] xs, int i, boolean v) { xs[i] = v; return v; }

    public static int alength(Object[] xs) { return xs.length; }
    public static int alength(int[] xs) { return xs.length; }
    public static int alength(long[] xs) { return xs.length; }
    public static int alength(double[] xs) { return xs.length; }
    public static int alength(float[] xs) { return xs.length; }
    public static int alength(short[] xs) { return xs.length; }
    public static int alength(byte[] xs) { return xs.length; }
    public static int alength(char[] xs) { return xs.length; }
    public static int alength(boolean[] xs) { return xs.length; }

    public static Object[] seqToArray(ISeq seq) { return cljl.RT.toArray(seq); }
    public static Object[] toArray(Object coll) { return cljl.RT.toArray(coll); }
    public static int nextID() { return cljl.RT.nextID(); }

    public static Iterator<?> iter(Object coll) {
        if (coll == null) return java.util.Collections.emptyIterator();
        if (coll instanceof Iterable<?> it) return it.iterator();
        if (coll instanceof Map<?, ?> m) return m.entrySet().iterator();
        return new cljl.lang.SeqIterator(cljl.RT.seq(coll));
    }

    public static boolean isChunkedSeq(Object o) { return o instanceof IChunkedSeq; }
    public static IChunk chunkFirst(Object o) { return ((IChunkedSeq) o).chunkedFirst(); }
    public static ISeq chunkNext(Object o) { return ((IChunkedSeq) o).chunkedNext(); }
    public static ISeq chunkRest(Object o) { return ((IChunkedSeq) o).chunkedMore(); }
}
