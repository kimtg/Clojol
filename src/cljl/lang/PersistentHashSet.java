package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.util.*;

public final class PersistentHashSet extends Fn
        implements IPersistentSet, IObj, IEditableCollection, Set<Object> {

    public static final PersistentHashSet EMPTY = new PersistentHashSet(null, PersistentHashMap.EMPTY);

    public final PersistentHashMap impl;
    private final PersistentHashMap meta;
    private int hash;
    private volatile boolean hasHash;

    public PersistentHashSet(PersistentHashMap meta, PersistentHashMap impl) {
        this.meta = meta;
        this.impl = impl;
    }

    public static PersistentHashSet create(Object... init) {
        if (init == null || init.length == 0) return EMPTY;
        TransientHashSet t = EMPTY.asTransient();
        for (Object o : init) t = t.conj(o);
        return t.persistent();
    }

    public static PersistentHashSet createWithCheck(Object... init) {
        if (init == null || init.length == 0) return EMPTY;
        TransientHashSet t = EMPTY.asTransient();
        for (Object o : init) {
            if (t.contains(o)) throw new IllegalArgumentException("Duplicate key: " + o);
            t = t.conj(o);
        }
        return t.persistent();
    }

    public static PersistentHashSet createWithCheck(List<?> init) {
        if (init == null || init.isEmpty()) return EMPTY;
        TransientHashSet t = EMPTY.asTransient();
        for (Object o : init) {
            if (t.contains(o)) throw new IllegalArgumentException("Duplicate key: " + o);
            t = t.conj(o);
        }
        return t.persistent();
    }

    public static PersistentHashSet createFromColl(Iterable<?> init) {
        if (init == null) return EMPTY;
        TransientHashSet t = EMPTY.asTransient();
        for (Object o : init) t = t.conj(o);
        return t.persistent();
    }

    public static PersistentHashSet createFromISeq(ISeq seq) {
        if (seq == null) return EMPTY;
        TransientHashSet t = EMPTY.asTransient();
        for (ISeq s = seq; s != null; s = s.next()) t = t.conj(s.first());
        return t.persistent();
    }

    public PersistentHashSet cons(Object key) {
        return conj(key);
    }

    public PersistentHashSet empty() {
        return EMPTY;
    }

    public PersistentHashSet conj(Object key) {
        if (contains(key)) return this;
        return new PersistentHashSet(meta, impl.assoc(key, key));
    }

    public PersistentHashSet disjoin(Object key) {
        if (!contains(key)) return this;
        return new PersistentHashSet(meta, impl.without(key));
    }

    @Override
    public boolean contains(Object key) {
        return impl.containsKey(key);
    }

    public Object get(Object key) {
        return impl.valAt(key, null);
    }

    public Object get(Object key, Object notFound) {
        return impl.valAt(key, notFound);
    }

    @Override
    public int count() {
        return impl.count();
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentHashSet withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new PersistentHashSet(meta, impl);
    }

    @Override
    public Object invoke(Object key) {
        return get(key);
    }

    @Override
    public Object invoke(Object key, Object notFound) {
        return get(key, notFound);
    }

    @Override
    public ISeq seq() {
        return RT.keys(impl);
    }

    public TransientHashSet asTransient() {
        return new TransientHashSet(impl.asTransient());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Set<?> m)) return false;
        if (count() != m.size()) return false;
        try {
            for (ISeq s = seq(); s != null; s = s.next()) {
                if (!m.contains(s.first())) return false;
            }
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        if (!hasHash) {
            int h = 0;
            for (ISeq s = seq(); s != null; s = s.next()) {
                h += RT.hash(s.first());
            }
            this.hash = h;
            this.hasHash = true;
            return h;
        }
        return this.hash;
    }

    @Override
    public String toString() {
        return RT.printString(this);
    }

    // Set<Object> methods
    @Override public int size() { return count(); }
    @Override public boolean isEmpty() { return count() == 0; }
    @Override public Iterator<Object> iterator() {
        ISeq s = seq();
        return s == null ? Collections.emptyIterator() : new ASeq.SeqIterator(s);
    }
    @Override public Object[] toArray() {
        Object[] ret = new Object[count()];
        int i = 0;
        for (ISeq s = seq(); s != null; s = s.next()) ret[i++] = s.first();
        return ret;
    }
    @Override @SuppressWarnings("unchecked") public <T> T[] toArray(T[] a) {
        int n = count();
        if (a.length < n) a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), n);
        int i = 0;
        for (ISeq s = seq(); s != null; s = s.next()) a[i++] = (T) s.first();
        if (a.length > n) a[n] = null;
        return a;
    }
    @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
    @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
    @Override public boolean containsAll(Collection<?> c) {
        for (Object x : c) if (!contains(x)) return false;
        return true;
    }
    @Override public boolean addAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    public static final class TransientHashSet extends Fn implements Counted {
        private static final Object NOT_FOUND = new Object();
        private final PersistentHashMap.TransientHashMap impl;

        TransientHashSet(PersistentHashMap.TransientHashMap impl) {
            this.impl = impl;
        }

        public TransientHashSet conj(Object val) {
            if (!contains(val)) {
                impl.assoc(val, val);
            }
            return this;
        }

        public TransientHashSet disjoin(Object val) {
            impl.without(val);
            return this;
        }

        public boolean contains(Object key) {
            return impl.valAt(key, NOT_FOUND) != NOT_FOUND;
        }

        @Override
        public Object invoke(Object key) {
            return impl.valAt(key, null);
        }

        @Override
        public Object invoke(Object key, Object notFound) {
            return impl.valAt(key, notFound);
        }

        @Override
        public int count() {
            return impl.count();
        }

        public PersistentHashSet persistent() {
            return new PersistentHashSet(null, impl.persistent());
        }
    }
}
