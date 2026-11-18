package cljl.lang;

import cljl.Fn;
import cljl.Numbers;
import cljl.RT;
import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Primitive-array-backed persistent vector (Vec, VecNode, VecSeq) for ->Vec / ->VecNode / ->VecSeq.
 */
public final class Vec extends Fn
        implements Associative, IObj, Reversible, List<Object>, RandomAccess, Comparable<Object> {

    public static final class VecNode {
        public final AtomicReference<Thread> edit;
        public final Object[] arr;

        public VecNode(AtomicReference<Thread> edit, Object[] arr) {
            this.edit = edit;
            this.arr = arr;
        }
    }

    public static final class VecSeq extends ASeq implements IChunkedSeq, Counted {
        public final Object am;
        public final Vec vec;
        public final VecNode anode;
        public final int i;
        public final int offset;

        public VecSeq(Object am, Vec vec, VecNode anode, int i, int offset, PersistentHashMap meta) {
            super(meta);
            this.am = am;
            this.vec = vec;
            this.anode = anode;
            this.i = i;
            this.offset = offset;
        }

        private VecNode arrayFor(int idx) {
            return vec.nodeFor(idx);
        }

        @Override
        public IChunk chunkedFirst() {
            return new ArrayChunk(anode.arr[0] != null && anode.arr.length == 1 && anode.arr[0].getClass().isArray()
                    ? anode.arr[0] : anode.arr, offset,
                    Math.min(32, vec.cnt - (i - offset)));
        }

        @Override
        public ISeq chunkedNext() {
            int nextI = (i - offset) + 32;
            if (nextI >= vec.cnt) return null;
            return new VecSeq(am, vec, arrayFor(nextI), nextI, 0, null);
        }

        @Override
        public ISeq chunkedMore() {
            ISeq n = chunkedNext();
            return n == null ? PersistentList.EMPTY : n;
        }

        @Override
        public Object first() {
            return vec.nth(i);
        }

        @Override
        public ISeq next() {
            if (i + 1 >= vec.cnt) return null;
            if (offset + 1 < 32) {
                return new VecSeq(am, vec, anode, i + 1, offset + 1, null);
            }
            return chunkedNext();
        }

        @Override
        public int count() {
            return vec.cnt - i;
        }

        @Override
        public VecSeq withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new VecSeq(am, vec, anode, i, offset, meta);
        }
    }

    public final Object am;
    public final int cnt;
    public final int shift;
    public final VecNode root;
    public final Object tail;
    private final PersistentHashMap meta;

    public Vec(Object am, int cnt, int shift, VecNode root, Object tail, PersistentHashMap meta) {
        this.am = am;
        this.cnt = cnt;
        this.shift = shift;
        this.root = root;
        this.tail = tail;
        this.meta = meta;
    }

    private int tailoff() {
        return cnt < 32 ? 0 : (((cnt - 1) >>> 5) << 5);
    }

    private Object arrayFor(int i) {
        if (i >= 0 && i < cnt) {
            if (i >= tailoff()) return tail instanceof VecNode vn ? vn.arr : tail;
            Object node = root;
            for (int level = shift; level > 0; level -= 5) {
                node = ((VecNode) node).arr[(i >>> level) & 0x01f];
            }
            return ((VecNode) node).arr;
        }
        throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
    }

    private VecNode nodeFor(int i) {
        if (i >= 0 && i < cnt) {
            if (i >= tailoff()) {
                return tail instanceof VecNode vn ? vn : new VecNode(null, new Object[]{tail});
            }
            VecNode node = root;
            for (int level = shift; level > 0; level -= 5) {
                node = (VecNode) node.arr[(i >>> level) & 0x01f];
            }
            return node;
        }
        throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
    }

    public Object nth(int i) {
        Object arr = arrayFor(i);
        if (arr instanceof Object[] oa) return oa[i & 0x01f];
        return Array.get(arr, i & 0x01f);
    }

    public Object nth(int i, Object notFound) {
        if (i >= 0 && i < cnt) return nth(i);
        return notFound;
    }

    @Override public int count() { return cnt; }
    @Override public PersistentHashMap meta() { return meta; }
    @Override public Vec withMeta(PersistentHashMap m) {
        return this.meta == m ? this : new Vec(am, cnt, shift, root, tail, m);
    }
    @Override public boolean containsKey(Object key) {
        if (key instanceof Number n) {
            long i = n.longValue();
            return i >= 0 && i < cnt;
        }
        return false;
    }
    @Override public Object valAt(Object key) { return valAt(key, null); }
    @Override public Object valAt(Object key, Object notFound) {
        if (key instanceof Number n) return nth(n.intValue(), notFound);
        return notFound;
    }
    @Override public Associative assoc(Object key, Object val) {
        if (key instanceof Number n) return toPersistentVector().assocN(n.intValue(), val);
        throw new IllegalArgumentException("Key must be integer");
    }
    public PersistentVector toPersistentVector() {
        PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
        for (int i = 0; i < cnt; i++) tv = tv.conj(nth(i));
        return tv.persistent();
    }
    @Override public ISeq seq() {
        if (cnt == 0) return null;
        return new VecSeq(am, this, nodeFor(0), 0, 0, null);
    }
    @Override public ISeq rseq() {
        if (cnt == 0) return null;
        List<Object> rev = new ArrayList<>(cnt);
        for (int i = cnt - 1; i >= 0; i--) rev.add(nth(i));
        return PersistentList.create(rev);
    }
    @Override public Object invoke(Object a) { return nth((int) Numbers.toLong(a)); }
    @Override public Object invoke(Object a, Object nf) {
        if (a instanceof Number n) return nth(n.intValue(), nf);
        return nf;
    }

    @Override public int size() { return cnt; }
    @Override public boolean isEmpty() { return cnt == 0; }
    @Override public boolean contains(Object o) { return indexOf(o) >= 0; }
    @Override public Object get(int index) { return nth(index); }
    @Override public int indexOf(Object o) {
        for (int i = 0; i < cnt; i++) if (RT.equiv(nth(i), o)) return i;
        return -1;
    }
    @Override public int lastIndexOf(Object o) {
        for (int i = cnt - 1; i >= 0; i--) if (RT.equiv(nth(i), o)) return i;
        return -1;
    }
    @Override public Object[] toArray() {
        Object[] a = new Object[cnt];
        for (int i = 0; i < cnt; i++) a[i] = nth(i);
        return a;
    }
    @Override @SuppressWarnings("unchecked")
    public <T> T[] toArray(T[] a) {
        return (T[]) Arrays.copyOf(toArray(), cnt, a.getClass());
    }
    @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
            int i = 0;
            @Override public boolean hasNext() { return i < cnt; }
            @Override public Object next() { return nth(i++); }
        };
    }
    @Override public ListIterator<Object> listIterator() { return listIterator(0); }
    @Override public ListIterator<Object> listIterator(int index) {
        return Arrays.asList(toArray()).listIterator(index);
    }
    @Override public List<Object> subList(int fromIndex, int toIndex) {
        return toPersistentVector().subList(fromIndex, toIndex);
    }
    @Override public boolean containsAll(Collection<?> c) {
        for (Object x : c) if (!contains(x)) return false;
        return true;
    }
    @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
    @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
    @Override public boolean addAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean addAll(int index, Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public Object set(int index, Object element) { throw new UnsupportedOperationException(); }
    @Override public void add(int index, Object element) { throw new UnsupportedOperationException(); }
    @Override public Object remove(int index) { throw new UnsupportedOperationException(); }

    @Override
    public int compareTo(Object o) {
        Vec other = (Vec) o;
        int n = Math.min(cnt, other.cnt);
        for (int i = 0; i < n; i++) {
            int c = RT.compare(nth(i), other.nth(i));
            if (c != 0) return c;
        }
        return Integer.compare(cnt, other.cnt);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof List<?> l) {
            if (cnt != l.size()) return false;
            for (int i = 0; i < cnt; i++) if (!RT.equiv(nth(i), l.get(i))) return false;
            return true;
        }
        return toPersistentVector().equals(obj);
    }

    @Override
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < cnt; i++) h = 31 * h + RT.hash(nth(i));
        return h;
    }

    @Override
    public String toString() {
        return RT.printString(this);
    }
}
