package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.io.Serializable;
import java.util.*;

/**
 * Persistent sorted set backed by PersistentTreeMap.
 */
public final class PersistentTreeSet extends Fn
        implements IPersistentSet, IObj, Sorted, Reversible, IHashEq, Set<Object>, Iterable<Object>, Serializable {

    private static final long serialVersionUID = 1L;
    public static final PersistentTreeSet EMPTY = new PersistentTreeSet(null, PersistentTreeMap.EMPTY);

    public final PersistentTreeMap impl;
    private final PersistentHashMap meta;

    public PersistentTreeSet(PersistentHashMap meta, PersistentTreeMap impl) {
        this.meta = meta;
        this.impl = impl;
    }

    public static PersistentTreeSet create(Comparator<Object> comp, ISeq items) {
        PersistentTreeMap m = new PersistentTreeMap(null, comp, null, 0);
        for (ISeq s = items; s != null; s = s.next()) {
            Object item = s.first();
            m = m.assoc(item, item);
        }
        return new PersistentTreeSet(null, m);
    }

    @Override
    public PersistentTreeSet cons(Object o) {
        return conj(o);
    }

    public PersistentTreeSet conj(Object o) {
        PersistentTreeMap nm = impl.assoc(o, o);
        return nm == impl ? this : new PersistentTreeSet(meta, nm);
    }

    @Override
    public PersistentTreeSet disjoin(Object key) {
        PersistentTreeMap nm = impl.without(key);
        return nm == impl ? this : new PersistentTreeSet(meta, nm);
    }

    @Override
    public Object get(Object key) {
        return get(key, null);
    }

    public Object get(Object key, Object notFound) {
        Map.Entry<Object, Object> e = impl.entryAt(key);
        return e != null ? e.getKey() : notFound;
    }

    @Override
    public Object invoke(Object key) {
        return get(key, null);
    }

    @Override
    public Object invoke(Object key, Object notFound) {
        return get(key, notFound);
    }

    @Override
    public boolean contains(Object key) {
        return impl.containsKey(key);
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
    public PersistentTreeSet withMeta(PersistentHashMap m) {
        return m == meta ? this : new PersistentTreeSet(m, impl);
    }

    @Override
    public Comparator<?> comparator() {
        return impl.comparator();
    }

    @Override
    public Object entryKey(Object entry) {
        return entry;
    }

    @Override
    public ISeq seq() {
        return seq(true);
    }

    @Override
    public ISeq rseq() {
        return seq(false);
    }

    @Override
    public ISeq seq(boolean ascending) {
        return RT.keys(impl.seq(ascending));
    }

    @Override
    public ISeq seqFrom(Object key, boolean ascending) {
        return RT.keys(impl.seqFrom(key, ascending));
    }

    @Override
    public PersistentTreeSet empty() {
        return new PersistentTreeSet(meta, impl.empty());
    }

    // Set<Object> methods
    @Override public int size() { return count(); }
    @Override public boolean isEmpty() { return count() == 0; }
    @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
            ISeq s = seq();
            @Override public boolean hasNext() { return s != null; }
            @Override public Object next() {
                if (s == null) throw new NoSuchElementException();
                Object v = s.first();
                s = s.next();
                return v;
            }
        };
    }
    @Override public Object[] toArray() { return RT.toArray(this); }
    @Override public <T> T[] toArray(T[] a) { return new ArrayList<>(this).toArray(a); }
    @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
    @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
    @Override public boolean containsAll(Collection<?> c) { for (Object o : c) if (!contains(o)) return false; return true; }
    @Override public boolean addAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Set<?> s)) return false;
        if (s.size() != count()) return false;
        try {
            for (Object item : this) {
                if (!s.contains(item)) return false;
            }
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    @Override
    public int hasheq() {
        return Murmur3.hashUnordered(this);
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Object item : this) {
            h += RT.hash(item);
        }
        return h;
    }

    @Override
    public String toString() {
        return RT.printString(this, true);
    }
}
