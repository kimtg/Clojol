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
 * Runtime core utilities for ClojureLite: truthiness, value equivalence, hashing,
 * sequence operations, collection polymorphism, and printing.
 */
public final class RT {

    public static final Namespace CLOJURE_NS = Namespace.findOrCreate(Symbol.intern("clojure.core"));
    public static final Namespace USER_NS = Namespace.findOrCreate(Symbol.intern("user"));

    public static final Var CURRENT_NS = CLOJURE_NS.intern(Symbol.intern("*ns*")).bindRoot(USER_NS).setDynamic();
    public static final Var OUT = CLOJURE_NS.intern(Symbol.intern("*out*")).bindRoot(new PrintWriter(System.out, true)).setDynamic();
    public static final Var ERR = CLOJURE_NS.intern(Symbol.intern("*err*")).bindRoot(new PrintWriter(System.err, true)).setDynamic();
    public static final Var IN = CLOJURE_NS.intern(Symbol.intern("*in*")).bindRoot(new BufferedReader(new InputStreamReader(System.in))).setDynamic();
    public static final Var PRINT_READABLY = CLOJURE_NS.intern(Symbol.intern("*print-readably*")).bindRoot(Boolean.TRUE).setDynamic();
    public static final Var COMMAND_LINE_ARGS = CLOJURE_NS.intern(Symbol.intern("*command-line-args*")).bindRoot(null).setDynamic();

    private static final AtomicInteger GENSYM_ID = new AtomicInteger(1000);

    static {
        Core.init();
        USER_NS.referAll(CLOJURE_NS);
    }

    public static void ensureInit() {
        // Triggers static initializer if not yet run
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
        if (k1 instanceof Number n1 && k2 instanceof Number n2) {
            return Numbers.equiv(n1, n2);
        }
        return k1.equals(k2);
    }

    public static int hash(Object o) {
        if (o == null) return 0;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int compare(Object k1, Object k2) {
        if (k1 == k2) return 0;
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
            Object[] chars = new Object[cs.length()];
            for (int i = 0; i < cs.length(); i++) chars[i] = cs.charAt(i);
            return PersistentList.createFromArray(chars);
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
                return new PersistentList(null, head, iteratorSeq(iter), 2);
            }
        });
    }

    public static Object first(Object x) {
        if (x == null) return null;
        if (x instanceof ISeq s) return s.first();
        if (x instanceof PersistentVector v) return v.cnt > 0 ? v.nth(0) : null;
        ISeq seq = seq(x);
        return seq == null ? null : seq.first();
    }

    public static Object second(Object x) {
        if (x instanceof PersistentVector v) return v.cnt > 1 ? v.nth(1) : null;
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
        if (coll instanceof ISeq s) return new PersistentList(null, x, s, (s instanceof Counted c ? c.count() + 1 : 2));
        return new PersistentList(null, x, seq(coll), 2);
    }

    public static Object conj(Object coll, Object x) {
        if (coll == null) return new PersistentList(x);
        if (coll instanceof PersistentVector v) return v.conj(x);
        if (coll instanceof PersistentList l) return l.cons(x);
        if (coll instanceof PersistentHashMap m) {
            if (x instanceof Map.Entry<?, ?> e) {
                return m.assoc(e.getKey(), e.getValue());
            }
            if (x instanceof PersistentVector v) {
                if (v.count() != 2) throw new IllegalArgumentException("Vector arg to map conj must be a pair");
                return m.assoc(v.nth(0), v.nth(1));
            }
            if (x instanceof Seqable || x instanceof Map<?, ?>) {
                PersistentHashMap ret = m;
                for (ISeq s = seq(x); s != null; s = s.next()) {
                    Object elem = s.first();
                    if (elem instanceof Map.Entry<?, ?> e) {
                        ret = ret.assoc(e.getKey(), e.getValue());
                    } else if (elem instanceof PersistentVector v && v.count() == 2) {
                        ret = ret.assoc(v.nth(0), v.nth(1));
                    } else {
                        throw new IllegalArgumentException("Cannot conj " + elem + " onto map");
                    }
                }
                return ret;
            }
            throw new IllegalArgumentException("Cannot conj " + x + " onto map");
        }
        if (coll instanceof PersistentHashSet s) return s.conj(x);
        if (coll instanceof ISeq s) return cons(x, s);
        if (coll instanceof Set<?> s) {
            Set<Object> copy = new LinkedHashSet<>(s);
            copy.add(x);
            return copy;
        }
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
        if (coll instanceof Map<?, ?> m) return PersistentHashMap.createFromMap(m).without(key);
        throw new IllegalArgumentException("dissoc not supported on: " + coll.getClass().getName());
    }

    public static Object get(Object coll, Object key) {
        return get(coll, key, null);
    }

    public static Object get(Object coll, Object key, Object notFound) {
        if (coll == null) return notFound;
        if (coll instanceof PersistentHashMap m) return m.valAt(key, notFound);
        if (coll instanceof Associative a) return a.valAt(key, notFound);
        if (coll instanceof PersistentHashSet s) return s.get(key, notFound);
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
        if (coll instanceof PersistentVector v && key instanceof Number n) {
            int i = n.intValue();
            if (i >= 0 && i < v.count()) return new PersistentHashMap.MapEntry(i, v.nth(i));
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
        if (coll instanceof CharSequence cs) return cs.charAt(n);
        if (coll.getClass().isArray()) return Array.get(coll, n);
        if (coll instanceof RandomAccess && coll instanceof List<?> l) return l.get(n);
        if (coll instanceof Map.Entry<?, ?> e) {
            if (n == 0) return e.getKey();
            if (n == 1) return e.getValue();
            throw new IndexOutOfBoundsException();
        }
        if (n < 0) throw new IndexOutOfBoundsException("Index cannot be negative: " + n);
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
        if (coll instanceof CharSequence cs) return n < cs.length() ? cs.charAt(n) : notFound;
        if (coll.getClass().isArray()) return n < Array.getLength(coll) ? Array.get(coll, n) : notFound;
        if (coll instanceof RandomAccess && coll instanceof List<?> l) return n < l.size() ? l.get(n) : notFound;
        if (coll instanceof Map.Entry<?, ?> e) {
            if (n == 0) return e.getKey();
            if (n == 1) return e.getValue();
            return notFound;
        }
        ISeq s = seq(coll);
        for (int i = 0; i <= n && s != null; i++, s = s.next()) {
            if (i == n) return s.first();
        }
        return notFound;
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
                return new PersistentList(null, f.invoke(sq.first()), mapSeq(f, sq.next()), 2);
            }
        });
    }

    public static Object[] toArray(Object coll) {
        if (coll == null) return Fn.EMPTY_ARGS;
        if (coll instanceof Object[] arr) return arr;
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
        if (x == null) {
            sb.append("nil");
            return;
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
        if (x instanceof PersistentVector v) {
            sb.append('[');
            for (int i = 0; i < v.cnt; i++) {
                if (i > 0) sb.append(' ');
                print(v.nth(i), sb, readably);
            }
            sb.append(']');
            return;
        }
        if (x instanceof PersistentHashMap.MapEntry me) {
            sb.append('[');
            print(me.key, sb, readably);
            sb.append(' ');
            print(me.val, sb, readably);
            sb.append(']');
            return;
        }
        if (x instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                print(e.getKey(), sb, readably);
                sb.append(' ');
                print(e.getValue(), sb, readably);
            }
            sb.append('}');
            return;
        }
        if (x instanceof Set<?> set) {
            sb.append("#{");
            boolean first = true;
            for (Object item : set) {
                if (!first) sb.append(' ');
                first = false;
                print(item, sb, readably);
            }
            sb.append('}');
            return;
        }
        if (x instanceof ISeq || x instanceof Seqable || x instanceof List<?>) {
            sb.append('(');
            ISeq s = seq(x);
            boolean first = true;
            while (s != null) {
                if (!first) sb.append(' ');
                first = false;
                print(s.first(), sb, readably);
                s = s.next();
            }
            sb.append(')');
            return;
        }
        if (x instanceof Class<?> c) {
            sb.append(c.getName());
            return;
        }
        sb.append(x.toString());
    }

    @SuppressWarnings("unchecked")
    public static <E extends Throwable> RuntimeException sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
