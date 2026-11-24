package cljl.lang;

import cljl.RT;
import java.io.Serializable;
import java.util.*;

public abstract class ASeq extends clojure.lang.ASeq implements ISeq, IObj, Sequential, IHashEq, Serializable {
    private static final long serialVersionUID = 1L;
    protected final PersistentHashMap meta;
    private int hash;
    private volatile boolean hasHash;
    private int hasheq;
    private volatile boolean hasHasheq;

    protected ASeq() {
        this.meta = null;
    }

    protected ASeq(PersistentHashMap meta) {
        this.meta = meta;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public ISeq seq() {
        return this;
    }

    @Override
    public ISeq more() {
        ISeq s = next();
        return s == null ? PersistentList.EMPTY : s;
    }

    @Override
    public ISeq cons(Object o) {
        return new Cons(o, this);
    }

    @Override
    public int count() {
        int i = 1;
        for (ISeq s = next(); s != null; s = s.next(), i++) {
            if (s instanceof Counted c) {
                return i + c.count();
            }
        }
        return i;
    }

    @Override
    public int size() {
        return count();
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        for (ISeq s = seq(); s != null; s = s.next()) {
            if (RT.equiv(s.first(), o)) return true;
        }
        return false;
    }

    @Override
    public Iterator<Object> iterator() {
        return new SeqIterator(this);
    }

    @Override
    public Object[] toArray() {
        List<Object> list = new ArrayList<>();
        for (ISeq s = seq(); s != null; s = s.next()) {
            list.add(s.first());
        }
        return list.toArray();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        List<Object> list = new ArrayList<>();
        for (ISeq s = seq(); s != null; s = s.next()) {
            list.add(s.first());
        }
        return list.toArray(a);
    }

    @Override
    public Object get(int index) {
        return RT.nth(this, index);
    }

    @Override
    public int indexOf(Object o) {
        int i = 0;
        for (ISeq s = seq(); s != null; s = s.next(), i++) {
            if (RT.equiv(s.first(), o)) return i;
        }
        return -1;
    }

    @Override
    public int lastIndexOf(Object o) {
        int idx = -1;
        int i = 0;
        for (ISeq s = seq(); s != null; s = s.next(), i++) {
            if (RT.equiv(s.first(), o)) idx = i;
        }
        return idx;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Seqable || obj instanceof List<?>)) return false;
        if (obj instanceof IPersistentMap || obj instanceof IPersistentSet
                || obj instanceof Map<?, ?> || obj instanceof Set<?>) {
            return false;
        }
        ISeq ms = RT.seq(obj);
        ISeq s = this.seq();
        while (s != null) {
            if (ms == null || !RT.equiv(s.first(), ms.first())) return false;
            s = s.next();
            ms = ms.next();
        }
        return ms == null;
    }

    @Override
    public int hasheq() {
        if (!hasHasheq) {
            int h = Murmur3.hashOrdered(this);
            this.hasheq = h;
            this.hasHasheq = true;
            return h;
        }
        return this.hasheq;
    }

    @Override
    public int hashCode() {
        if (!hasHash) {
            int h = 1;
            for (ISeq s = seq(); s != null; s = s.next()) {
                h = 31 * h + RT.hash(s.first());
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

    // Immutable List mutator stubs
    @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
    @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
    @Override public boolean containsAll(Collection<?> c) {
        for (Object x : c) if (!contains(x)) return false;
        return true;
    }
    @Override public boolean addAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean addAll(int index, Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public Object set(int index, Object element) { throw new UnsupportedOperationException(); }
    @Override public void add(int index, Object element) { throw new UnsupportedOperationException(); }
    @Override public Object remove(int index) { throw new UnsupportedOperationException(); }
    @Override public ListIterator<Object> listIterator() { return Collections.unmodifiableList(Arrays.asList(toArray())).listIterator(); }
    @Override public ListIterator<Object> listIterator(int index) { return Collections.unmodifiableList(Arrays.asList(toArray())).listIterator(index); }
    @Override public List<Object> subList(int fromIndex, int toIndex) { return Collections.unmodifiableList(Arrays.asList(toArray())).subList(fromIndex, toIndex); }

    public static final class SeqIterator implements Iterator<Object> {
        private ISeq seq;
        public SeqIterator(ISeq seq) { this.seq = (seq != null) ? seq.seq() : null; }
        @Override public boolean hasNext() { return seq != null; }
        @Override public Object next() {
            if (seq == null) throw new NoSuchElementException();
            Object ret = seq.first();
            seq = seq.next();
            return ret;
        }
    }
}
