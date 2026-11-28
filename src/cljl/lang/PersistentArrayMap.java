package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.io.Serializable;
import java.util.*;

/**
 * Insertion-order-preserving small persistent map (clojure.lang.PersistentArrayMap).
 * Backs `array-map` and small map literals (<= 8 entries), upgrading to PersistentHashMap
 * when assoc'd beyond HASHTABLE_THRESHOLD.
 */
public final class PersistentArrayMap extends clojure.lang.PersistentArrayMap
        implements IPersistentMap, IObj, IEditableCollection, IHashEq, IKVReduce, MapEquivalence,
                   Map<Object, Object>, Iterable<Map.Entry<Object, Object>>, Serializable {

    private static final long serialVersionUID = 1L;
    public static final int HASHTABLE_THRESHOLD = 16; // 8 key-value pairs

    private static PersistentArrayMap _empty;
    public static PersistentArrayMap emptyInstance() {
        PersistentArrayMap e = _empty;
        if (e == null) { e = new PersistentArrayMap(null, new Object[0]); _empty = e; }
        return e;
    }
    public static final PersistentArrayMap EMPTY = emptyInstance();

    private final PersistentHashMap meta;
    final Object[] array;
    private int hash;
    private volatile boolean hasHash;
    private int hasheq;
    private volatile boolean hasHasheq;

    public PersistentArrayMap(PersistentHashMap meta, Object[] array) {
        this.meta = meta;
        this.array = array;
    }

    public PersistentArrayMap(Object[] array) {
        this(null, array);
    }

    public static PersistentArrayMap create(Object... init) {
        return createAsIfByAssoc(init);
    }

    public static PersistentArrayMap createAsIfByAssoc(Object[] init) {
        if (init == null || init.length == 0) return EMPTY;
        if ((init.length & 1) != 0) {
            throw new IllegalArgumentException("No value supplied for key: " + init[init.length - 1]);
        }
        Object[] out = new Object[init.length];
        int len = 0;
        for (int i = 0; i < init.length; i += 2) {
            Object k = init[i];
            Object v = init[i + 1];
            int idx = indexOfArray(out, len, k);
            if (idx >= 0) {
                out[idx + 1] = v;
            } else {
                out[len++] = k;
                out[len++] = v;
            }
        }
        if (len < out.length) {
            out = Arrays.copyOf(out, len);
        }
        return new PersistentArrayMap(null, out);
    }

    public static IPersistentMap createWithCheck(Object[] init) {
        if (init == null || init.length == 0) return EMPTY;
        if ((init.length & 1) != 0) {
            throw new IllegalArgumentException("No value supplied for key: " + init[init.length - 1]);
        }
        if (init.length > HASHTABLE_THRESHOLD) {
            return PersistentHashMap.createWithCheck(init);
        }
        for (int i = 0; i < init.length; i += 2) {
            Object k = init[i];
            for (int j = i + 2; j < init.length; j += 2) {
                if (RT.equiv(k, init[j])) {
                    throw new IllegalArgumentException("Duplicate key: " + k);
                }
            }
        }
        return new PersistentArrayMap(null, init.clone());
    }

    private static int indexOfArray(Object[] arr, int len, Object key) {
        if (key instanceof cljl.Keyword) {
            for (int i = 0; i < len; i += 2) {
                if (arr[i] == key) return i;
            }
            return -1;
        }
        for (int i = 0; i < len; i += 2) {
            if (RT.equiv(arr[i], key)) return i;
        }
        return -1;
    }

    private int indexOf(Object key) {
        return indexOfArray(array, array.length, key);
    }

    @Override
    public int count() {
        return array.length >>> 1;
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOf(key) >= 0;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        Object[] arr = this.array;
        if (key instanceof cljl.Keyword) {
            for (int i = 0, len = arr.length; i < len; i += 2) {
                if (arr[i] == key) return arr[i + 1];
            }
            return notFound;
        }
        for (int i = 0, len = arr.length; i < len; i += 2) {
            if (RT.equiv(arr[i], key)) return arr[i + 1];
        }
        return notFound;
    }

    public Object valAtKeyword(cljl.Keyword key, Object notFound) {
        Object[] arr = this.array;
        for (int i = 0, len = arr.length; i < len; i += 2) {
            if (arr[i] == key) return arr[i + 1];
        }
        return notFound;
    }

    @Override
    public IMapEntry entryAt(Object key) {
        int i = indexOf(key);
        return i >= 0 ? new PersistentHashMap.MapEntry(array[i], array[i + 1]) : null;
    }

    @Override
    public IPersistentMap assoc(Object key, Object val) {
        int i = indexOf(key);
        if (i >= 0) {
            if (array[i + 1] == val) return this;
            Object[] newArr = array.clone();
            newArr[i + 1] = val;
            return new PersistentArrayMap(meta, newArr);
        }
        if (array.length >= HASHTABLE_THRESHOLD) {
            return toHashMap().assoc(key, val);
        }
        Object[] newArr = new Object[array.length + 2];
        System.arraycopy(array, 0, newArr, 0, array.length);
        newArr[array.length] = key;
        newArr[array.length + 1] = val;
        return new PersistentArrayMap(meta, newArr);
    }

    @Override
    public IPersistentMap without(Object key) {
        int i = indexOf(key);
        if (i < 0) return this;
        int newLen = array.length - 2;
        if (newLen == 0) return meta == null ? EMPTY : new PersistentArrayMap(meta, new Object[0]);
        Object[] newArr = new Object[newLen];
        if (i > 0) System.arraycopy(array, 0, newArr, 0, i);
        if (i < newLen) System.arraycopy(array, i + 2, newArr, i, newLen - i);
        return new PersistentArrayMap(meta, newArr);
    }

    @Override
    public IPersistentCollection cons(Object o) {
        if (o == null) return this;
        if (o instanceof Map.Entry<?, ?> e) {
            return assoc(e.getKey(), e.getValue());
        }
        if (o instanceof IPersistentVector v) {
            if (v.count() != 2) throw new IllegalArgumentException("Vector arg to map conj must be a pair");
            return assoc(v.nth(0), v.nth(1));
        }
        IPersistentMap ret = this;
        for (ISeq s = RT.seq(o); s != null; s = s.next()) {
            Object item = s.first();
            if (item instanceof Map.Entry<?, ?> e) {
                ret = ret.assoc(e.getKey(), e.getValue());
            } else {
                throw new IllegalArgumentException("Can't conj " + item + " to map");
            }
        }
        return ret;
    }

    @Override
    public PersistentArrayMap empty() {
        return meta == null ? EMPTY : new PersistentArrayMap(meta, new Object[0]);
    }

    public PersistentHashMap toHashMap() {
        PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
        for (int i = 0; i < array.length; i += 2) {
            tm = tm.assoc(array[i], array[i + 1]);
        }
        PersistentHashMap m = tm.persistent();
        return meta != null ? m.withMeta(meta) : m;
    }

    @Override
    public ISeq seq() {
        return array.length > 0 ? new ArrayMapSeq(null, array, 0) : null;
    }

    @Override
    public Object kvreduce(Fn f, Object init) {
        Object acc = init;
        for (int i = 0; i < array.length; i += 2) {
            acc = f.invoke(acc, array[i], array[i + 1]);
            if (acc instanceof Reduced r) return r.deref();
        }
        return acc;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentArrayMap withMeta(PersistentHashMap m) {
        if (this.meta == m) return this;
        return new PersistentArrayMap(m, array);
    }

    @Override
    public ITransientMap asTransient() {
        return new TransientArrayMap(array);
    }

    @Override
    public Object invoke(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object invoke(Object key, Object notFound) {
        return valAt(key, notFound);
    }

    @Override
    public int hasheq() {
        if (!hasHasheq) {
            int sum = 0;
            for (int i = 0; i < array.length; i += 2) {
                int eh = Murmur3.mixCollHash(31 * (31 + RT.hash(array[i])) + RT.hash(array[i + 1]), 2);
                sum += eh;
            }
            int h = Murmur3.mixCollHash(sum, array.length >>> 1);
            this.hasheq = h;
            this.hasHasheq = true;
            return h;
        }
        return this.hasheq;
    }

    @Override
    public int hashCode() {
        if (!hasHash) {
            int h = 0;
            for (int i = 0; i < array.length; i += 2) {
                h += (array[i] == null ? 0 : array[i].hashCode())
                        ^ (array[i + 1] == null ? 0 : array[i + 1].hashCode());
            }
            this.hash = h;
            this.hasHash = true;
            return h;
        }
        return this.hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Map<?, ?> m)) return false;
        if (m instanceof PersistentRecord) return false;
        int n = count();
        if (m.size() != n) return false;
        for (int i = 0; i < array.length; i += 2) {
            Object k = array[i];
            if (!m.containsKey(k)) return false;
            if (!RT.equiv(array[i + 1], m.get(k))) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return RT.printString(this);
    }

    // java.util.Map implementation
    @Override public int size() { return count(); }
    @Override public boolean isEmpty() { return array.length == 0; }
    @Override public Object get(Object key) { return valAt(key, null); }
    @Override public boolean containsValue(Object value) {
        for (int i = 1; i < array.length; i += 2) {
            if (RT.equiv(array[i], value)) return true;
        }
        return false;
    }
    @Override public Set<Object> keySet() {
        LinkedHashSet<Object> s = new LinkedHashSet<>(count());
        for (int i = 0; i < array.length; i += 2) s.add(array[i]);
        return Collections.unmodifiableSet(s);
    }
    @Override public Collection<Object> values() {
        List<Object> vs = new ArrayList<>(count());
        for (int i = 1; i < array.length; i += 2) vs.add(array[i]);
        return Collections.unmodifiableList(vs);
    }
    @Override public Set<Map.Entry<Object, Object>> entrySet() {
        LinkedHashSet<Map.Entry<Object, Object>> es = new LinkedHashSet<>(count());
        for (int i = 0; i < array.length; i += 2) {
            es.add(new PersistentHashMap.MapEntry(array[i], array[i + 1]));
        }
        return Collections.unmodifiableSet(es);
    }
    @Override public Iterator<Map.Entry<Object, Object>> iterator() {
        return new Iterator<>() {
            int i = 0;
            @Override public boolean hasNext() { return i < array.length; }
            @Override public Map.Entry<Object, Object> next() {
                if (i >= array.length) throw new NoSuchElementException();
                Map.Entry<Object, Object> e = new PersistentHashMap.MapEntry(array[i], array[i + 1]);
                i += 2;
                return e;
            }
        };
    }
    @Override public Object put(Object key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<?, ?> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    public static final class ArrayMapSeq extends ASeq implements Counted {
        private static final long serialVersionUID = 1L;
        private final Object[] array;
        private final int i;

        ArrayMapSeq(PersistentHashMap meta, Object[] array, int i) {
            super(meta);
            this.array = array;
            this.i = i;
        }

        @Override
        public Object first() {
            return new PersistentHashMap.MapEntry(array[i], array[i + 1]);
        }

        @Override
        public ISeq next() {
            return (i + 2 < array.length) ? new ArrayMapSeq(null, array, i + 2) : null;
        }

        @Override
        public int count() {
            return (array.length - i) >>> 1;
        }

        @Override
        public ArrayMapSeq withMeta(PersistentHashMap m) {
            if (this.meta == m) return this;
            return new ArrayMapSeq(m, array, i);
        }
    }

    public static final class TransientArrayMap extends Fn implements ITransientMap {
        private Thread owner;
        private Object[] array;
        private int len;

        TransientArrayMap(Object[] src) {
            this.owner = Thread.currentThread();
            this.array = new Object[Math.max(HASHTABLE_THRESHOLD, src.length)];
            System.arraycopy(src, 0, this.array, 0, src.length);
            this.len = src.length;
        }

        private void ensureEditable() {
            if (owner == null) throw new IllegalAccessError("Transient used after persistent! call");
        }

        private int indexOf(Object key) {
            return indexOfArray(array, len, key);
        }

        @Override
        public ITransientMap assoc(Object key, Object val) {
            ensureEditable();
            int i = indexOf(key);
            if (i >= 0) {
                array[i + 1] = val;
                return this;
            }
            if (len >= HASHTABLE_THRESHOLD) {
                PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
                for (int j = 0; j < len; j += 2) {
                    tm = tm.assoc(array[j], array[j + 1]);
                }
                owner = null;
                return tm.assoc(key, val);
            }
            if (len + 2 > array.length) {
                array = Arrays.copyOf(array, Math.max(array.length * 2, len + 2));
            }
            array[len++] = key;
            array[len++] = val;
            return this;
        }

        @Override
        public ITransientMap without(Object key) {
            ensureEditable();
            int i = indexOf(key);
            if (i >= 0) {
                if (len - 2 > i) {
                    array[i] = array[len - 2];
                    array[i + 1] = array[len - 1];
                }
                array[len - 2] = null;
                array[len - 1] = null;
                len -= 2;
            }
            return this;
        }

        @Override
        public ITransientCollection conj(Object o) {
            ensureEditable();
            if (o == null) return this;
            if (o instanceof Map.Entry<?, ?> e) {
                return assoc(e.getKey(), e.getValue());
            }
            if (o instanceof IPersistentVector v) {
                if (v.count() != 2) throw new IllegalArgumentException("Vector arg to map conj must be a pair");
                return assoc(v.nth(0), v.nth(1));
            }
            ITransientMap ret = this;
            for (ISeq s = RT.seq(o); s != null; s = s.next()) {
                Object item = s.first();
                if (item instanceof Map.Entry<?, ?> e) {
                    ret = ret.assoc(e.getKey(), e.getValue());
                } else {
                    throw new IllegalArgumentException("Can't conj " + item + " to transient map");
                }
            }
            return ret;
        }

        @Override
        public Object valAt(Object key) {
            return valAt(key, null);
        }

        @Override
        public Object valAt(Object key, Object notFound) {
            ensureEditable();
            int i = indexOf(key);
            return i >= 0 ? array[i + 1] : notFound;
        }

        @Override
        public int count() {
            ensureEditable();
            return len >>> 1;
        }

        @Override
        public PersistentArrayMap persistent() {
            ensureEditable();
            owner = null;
            Object[] trimmed = new Object[len];
            System.arraycopy(array, 0, trimmed, 0, len);
            return new PersistentArrayMap(null, trimmed);
        }

        @Override public Object invoke(Object key) { return valAt(key, null); }
        @Override public Object invoke(Object key, Object notFound) { return valAt(key, notFound); }
    }
}
