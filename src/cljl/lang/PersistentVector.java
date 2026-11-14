package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 32-way bit-partitioned persistent vector trie with tail buffer optimization
 * and TransientVector support for O(1) amortized batch construction.
 */
public final class PersistentVector extends Fn
        implements Associative, IObj, Reversible, List<Object>, RandomAccess, Comparable<Object> {

    private static final AtomicReference<Thread> NO_EDIT = new AtomicReference<>(null);
    public static final Node EMPTY_NODE = new Node(NO_EDIT, new Object[32]);
    public static final PersistentVector EMPTY = new PersistentVector(null, 0, 5, EMPTY_NODE, new Object[0]);

    public final int cnt;
    public final int shift;
    public final Node root;
    public final Object[] tail;
    private final PersistentHashMap meta;
    private int hash;
    private boolean hasHash;

    public static final class Node {
        public final AtomicReference<Thread> edit;
        public final Object[] array;

        public Node(AtomicReference<Thread> edit, Object[] array) {
            this.edit = edit;
            this.array = array;
        }

        public Node(AtomicReference<Thread> edit) {
            this.edit = edit;
            this.array = new Object[32];
        }
    }

    public PersistentVector(PersistentHashMap meta, int cnt, int shift, Node root, Object[] tail) {
        this.meta = meta;
        this.cnt = cnt;
        this.shift = shift;
        this.root = root;
        this.tail = tail;
    }

    public static PersistentVector create(Collection<?> items) {
        if (items == null || items.isEmpty()) return EMPTY;
        if (items.size() <= 32) {
            return new PersistentVector(null, items.size(), 5, EMPTY_NODE, items.toArray());
        }
        TransientVector tv = EMPTY.asTransient();
        for (Object item : items) {
            tv = tv.conj(item);
        }
        return tv.persistent();
    }

    public static PersistentVector createFromISeq(ISeq seq) {
        if (seq == null) return EMPTY;
        TransientVector tv = EMPTY.asTransient();
        for (ISeq s = seq; s != null; s = s.next()) {
            tv = tv.conj(s.first());
        }
        return tv.persistent();
    }

    public static PersistentVector createOwning(Object... items) {
        if (items == null || items.length == 0) return EMPTY;
        if (items.length <= 32) {
            return new PersistentVector(null, items.length, 5, EMPTY_NODE, items);
        }
        TransientVector tv = EMPTY.asTransient();
        for (Object item : items) {
            tv = tv.conj(item);
        }
        return tv.persistent();
    }

    private int tailoff() {
        if (cnt < 32) return 0;
        return ((cnt - 1) >>> 5) << 5;
    }

    public Object[] arrayFor(int i) {
        if (i >= 0 && i < cnt) {
            if (i >= tailoff()) return tail;
            Node node = root;
            for (int level = shift; level > 0; level -= 5) {
                node = (Node) node.array[(i >>> level) & 0x01f];
            }
            return node.array;
        }
        throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
    }

    public Object nth(int i) {
        Object[] node = arrayFor(i);
        return node[i & 0x01f];
    }

    public Object nth(int i, Object notFound) {
        if (i >= 0 && i < cnt) return nth(i);
        return notFound;
    }

    public PersistentVector assocN(int i, Object val) {
        if (i >= 0 && i < cnt) {
            if (i >= tailoff()) {
                Object[] newTail = new Object[tail.length];
                System.arraycopy(tail, 0, newTail, 0, tail.length);
                newTail[i & 0x01f] = val;
                return new PersistentVector(meta, cnt, shift, root, newTail);
            }
            return new PersistentVector(meta, cnt, shift, doAssoc(shift, root, i, val), tail);
        }
        if (i == cnt) return conj(val);
        throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
    }

    private static Node doAssoc(int level, Node node, int i, Object val) {
        Node ret = new Node(node.edit, node.array.clone());
        if (level == 0) {
            ret.array[i & 0x01f] = val;
        } else {
            int subidx = (i >>> level) & 0x01f;
            ret.array[subidx] = doAssoc(level - 5, (Node) node.array[subidx], i, val);
        }
        return ret;
    }

    public PersistentVector conj(Object val) {
        if (cnt - tailoff() < 32) {
            Object[] newTail = new Object[tail.length + 1];
            System.arraycopy(tail, 0, newTail, 0, tail.length);
            newTail[tail.length] = val;
            return new PersistentVector(meta, cnt + 1, shift, root, newTail);
        }
        Node newRoot;
        Node tailNode = new Node(root.edit, tail);
        int newShift = shift;
        if ((cnt >>> 5) > (1 << shift)) {
            newRoot = new Node(root.edit);
            newRoot.array[0] = root;
            newRoot.array[1] = newPath(root.edit, shift, tailNode);
            newShift += 5;
        } else {
            newRoot = pushTail(shift, root, tailNode);
        }
        return new PersistentVector(meta, cnt + 1, newShift, newRoot, new Object[]{val});
    }

    private Node pushTail(int level, Node parent, Node tailnode) {
        int subidx = ((cnt - 1) >>> level) & 0x01f;
        Node ret = new Node(parent.edit, parent.array.clone());
        Node nodeToInsert;
        if (level == 5) {
            nodeToInsert = tailnode;
        } else {
            Node child = (Node) parent.array[subidx];
            nodeToInsert = (child != null)
                    ? pushTail(level - 5, child, tailnode)
                    : newPath(root.edit, level - 5, tailnode);
        }
        ret.array[subidx] = nodeToInsert;
        return ret;
    }

    private static Node newPath(AtomicReference<Thread> edit, int level, Node node) {
        if (level == 0) return node;
        Node ret = new Node(edit);
        ret.array[0] = newPath(edit, level - 5, node);
        return ret;
    }

    public Object peek() {
        if (cnt == 0) return null;
        return nth(cnt - 1);
    }

    public PersistentVector pop() {
        if (cnt == 0) throw new IllegalStateException("Can't pop empty vector");
        if (cnt == 1) return EMPTY.withMeta(meta);
        if (cnt - tailoff() > 1) {
            Object[] newTail = new Object[tail.length - 1];
            System.arraycopy(tail, 0, newTail, 0, newTail.length);
            return new PersistentVector(meta, cnt - 1, shift, root, newTail);
        }
        Object[] newTail = arrayFor(cnt - 2);
        Node newRoot = popTail(shift, root);
        int newShift = shift;
        if (newRoot == null) {
            newRoot = EMPTY_NODE;
        }
        if (shift > 5 && newRoot.array[1] == null) {
            newRoot = (Node) newRoot.array[0];
            newShift -= 5;
        }
        return new PersistentVector(meta, cnt - 1, newShift, newRoot, newTail);
    }

    private Node popTail(int level, Node node) {
        int subidx = ((cnt - 2) >>> level) & 0x01f;
        if (level > 5) {
            Node newChild = popTail(level - 5, (Node) node.array[subidx]);
            if (newChild == null && subidx == 0) return null;
            Node ret = new Node(root.edit, node.array.clone());
            ret.array[subidx] = newChild;
            return ret;
        } else if (subidx == 0) {
            return null;
        } else {
            Node ret = new Node(root.edit, node.array.clone());
            ret.array[subidx] = null;
            return ret;
        }
    }

    @Override
    public int count() {
        return cnt;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentVector withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new PersistentVector(meta, cnt, shift, root, tail);
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
            long n = ((Number) key).longValue();
            return n >= 0 && n < cnt;
        }
        return false;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
            long i = ((Number) key).longValue();
            if (i >= 0 && i < cnt) return nth((int) i);
        }
        return notFound;
    }

    @Override
    public PersistentVector assoc(Object key, Object val) {
        if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
            int i = ((Number) key).intValue();
            return assocN(i, val);
        }
        throw new IllegalArgumentException("Key must be integer");
    }

    @Override
    public Object invoke(Object arg1) {
        if (arg1 instanceof Integer || arg1 instanceof Long || arg1 instanceof Short || arg1 instanceof Byte) {
            return nth(((Number) arg1).intValue());
        }
        throw new IllegalArgumentException("Key must be integer: " + arg1);
    }

    @Override
    public Object invoke(Object arg1, Object notFound) {
        if (arg1 instanceof Integer || arg1 instanceof Long || arg1 instanceof Short || arg1 instanceof Byte) {
            return nth(((Number) arg1).intValue(), notFound);
        }
        return notFound;
    }

    @Override
    public ISeq seq() {
        if (cnt == 0) return null;
        return new VecSeq(this, 0);
    }

    public ISeq rseq() {
        if (cnt == 0) return null;
        return new RVecSeq(this, cnt - 1);
    }

    public TransientVector asTransient() {
        return new TransientVector(this);
    }

    @Override
    public int compareTo(Object o) {
        if (o instanceof SubVec sv) {
            int n = sv.count();
            if (this.cnt < n) return -1;
            if (this.cnt > n) return 1;
            for (int i = 0; i < this.cnt; i++) {
                int c = RT.compare(this.nth(i), sv.nth(i));
                if (c != 0) return c;
            }
            return 0;
        }
        PersistentVector v = (PersistentVector) o;
        if (this.cnt < v.cnt) return -1;
        if (this.cnt > v.cnt) return 1;
        for (int i = 0; i < this.cnt; i++) {
            int c = RT.compare(this.nth(i), v.nth(i));
            if (c != 0) return c;
        }
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof PersistentVector pv) {
            if (this.cnt != pv.cnt) return false;
            for (int i = 0; i < cnt; i++) {
                if (!RT.equiv(nth(i), pv.nth(i))) return false;
            }
            return true;
        }
        if (obj instanceof SubVec sv) {
            if (this.cnt != sv.count()) return false;
            for (int i = 0; i < cnt; i++) {
                if (!RT.equiv(nth(i), sv.nth(i))) return false;
            }
            return true;
        }
        if (obj instanceof List<?> list && !(obj instanceof Seqable)) {
            if (this.cnt != list.size()) return false;
            Iterator<?> it = list.iterator();
            for (int i = 0; i < cnt; i++) {
                if (!RT.equiv(nth(i), it.next())) return false;
            }
            return true;
        }
        if (obj instanceof Seqable) {
            if (obj instanceof PersistentHashMap || obj instanceof PersistentHashSet) return false;
            ISeq ms = RT.seq(obj);
            for (int i = 0; i < cnt; i++) {
                if (ms == null || !RT.equiv(nth(i), ms.first())) return false;
                ms = ms.next();
            }
            return ms == null;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int h = this.hash;
        if (!hasHash) {
            h = 1;
            for (int i = 0; i < cnt; i++) {
                h = 31 * h + RT.hash(nth(i));
            }
            this.hash = h;
            this.hasHash = true;
        }
        return h;
    }

    @Override
    public String toString() {
        return RT.printString(this);
    }

    // List<Object> implementation
    @Override public int size() { return cnt; }
    @Override public boolean isEmpty() { return cnt == 0; }
    @Override public boolean contains(Object o) {
        for (int i = 0; i < cnt; i++) {
            if (RT.equiv(nth(i), o)) return true;
        }
        return false;
    }
    @Override public Iterator<Object> iterator() {
        return new Iterator<>() {
            int i = 0;
            @Override public boolean hasNext() { return i < cnt; }
            @Override public Object next() {
                if (i >= cnt) throw new NoSuchElementException();
                return nth(i++);
            }
        };
    }
    @Override public Object[] toArray() {
        Object[] ret = new Object[cnt];
        for (int i = 0; i < cnt; i++) ret[i] = nth(i);
        return ret;
    }
    @Override @SuppressWarnings("unchecked") public <T> T[] toArray(T[] a) {
        if (a.length < cnt) a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), cnt);
        for (int i = 0; i < cnt; i++) a[i] = (T) nth(i);
        if (a.length > cnt) a[cnt] = null;
        return a;
    }
    @Override public Object get(int index) { return nth(index); }
    @Override public int indexOf(Object o) {
        for (int i = 0; i < cnt; i++) if (RT.equiv(nth(i), o)) return i;
        return -1;
    }
    @Override public int lastIndexOf(Object o) {
        for (int i = cnt - 1; i >= 0; i--) if (RT.equiv(nth(i), o)) return i;
        return -1;
    }
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
    public Object subVec(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex > cnt || fromIndex > toIndex) throw new IndexOutOfBoundsException();
        if (fromIndex == toIndex) return EMPTY;
        return new SubVec(null, this, fromIndex, toIndex);
    }

    @Override public List<Object> subList(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex > cnt || fromIndex > toIndex) throw new IndexOutOfBoundsException();
        if (fromIndex == toIndex) return EMPTY;
        return new SubVec(null, this, fromIndex, toIndex);
    }

    public static final class VecSeq extends ASeq implements Counted {
        private final PersistentVector v;
        private final int i;
        private final int end;

        public VecSeq(PersistentVector v, int i) {
            this(null, v, i, v.cnt);
        }

        public VecSeq(PersistentHashMap meta, PersistentVector v, int i) {
            this(meta, v, i, v.cnt);
        }

        public VecSeq(PersistentHashMap meta, PersistentVector v, int i, int end) {
            super(meta);
            this.v = v;
            this.i = i;
            this.end = end;
        }

        @Override public Object first() { return v.nth(i); }
        @Override public ISeq next() {
            if (i + 1 < end) return new VecSeq(null, v, i + 1, end);
            return null;
        }
        @Override public int count() { return end - i; }
        @Override public VecSeq withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new VecSeq(meta, v, i, end);
        }
    }

    public static final class RVecSeq extends ASeq implements Counted {
        private final PersistentVector v;
        private final int i;
        private final int start;

        public RVecSeq(PersistentVector v, int i) {
            this(null, v, i, 0);
        }

        public RVecSeq(PersistentHashMap meta, PersistentVector v, int i) {
            this(meta, v, i, 0);
        }

        public RVecSeq(PersistentHashMap meta, PersistentVector v, int i, int start) {
            super(meta);
            this.v = v;
            this.i = i;
            this.start = start;
        }

        @Override public Object first() { return v.nth(i); }
        @Override public ISeq next() {
            if (i > start) return new RVecSeq(null, v, i - 1, start);
            return null;
        }
        @Override public int count() { return i - start + 1; }
        @Override public RVecSeq withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new RVecSeq(meta, v, i, start);
        }
    }

    public static final class SubVec extends Fn
            implements Associative, IObj, Reversible, PersistentColl, List<Object>, RandomAccess, Comparable<Object> {
        public final PersistentVector v;
        public final int start;
        public final int end;
        private final PersistentHashMap meta;
        private int hash;
        private boolean hasHash;

        public SubVec(PersistentHashMap meta, PersistentVector v, int start, int end) {
            if (v instanceof PersistentVector pv) {
                this.v = pv;
            } else {
                throw new IllegalArgumentException("SubVec expects PersistentVector");
            }
            this.meta = meta;
            this.start = start;
            this.end = end;
        }

        public Object nth(int i) {
            if (i < 0 || start + i >= end) {
                throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + count());
            }
            return v.nth(start + i);
        }

        public Object nth(int i, Object notFound) {
            if (i >= 0 && start + i < end) return v.nth(start + i);
            return notFound;
        }

        public SubVec assocN(int i, Object val) {
            if (i < 0 || start + i > end) {
                throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + count());
            }
            if (start + i == end) return conj(val);
            return new SubVec(meta, v.assocN(start + i, val), start, end);
        }

        public SubVec conj(Object val) {
            return new SubVec(meta, v.assocN(end, val), start, end + 1);
        }

        @Override
        public Object cons(Object x) {
            return conj(x);
        }

        @Override
        public Object without(Object key) {
            throw new UnsupportedOperationException("dissoc not supported on vector");
        }

        @Override
        public Object peek() {
            return end > start ? v.nth(end - 1) : null;
        }

        @Override
        public Object pop() {
            if (end <= start) throw new IllegalStateException("Can't pop empty vector");
            if (end - 1 == start) return EMPTY.withMeta(meta);
            return new SubVec(meta, v, start, end - 1);
        }

        @Override
        public Object empty() {
            return EMPTY.withMeta(meta);
        }

        public Object subVec(int fromIndex, int toIndex) {
            if (fromIndex < 0 || toIndex > count() || fromIndex > toIndex) throw new IndexOutOfBoundsException();
            if (fromIndex == toIndex) return EMPTY;
            return new SubVec(null, v, start + fromIndex, start + toIndex);
        }

        @Override public int count() { return end - start; }
        @Override public PersistentHashMap meta() { return meta; }
        @Override public SubVec withMeta(PersistentHashMap m) { return m == meta ? this : new SubVec(m, v, start, end); }

        @Override
        public boolean containsKey(Object key) {
            if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
                long n = ((Number) key).longValue();
                return n >= 0 && n < count();
            }
            return false;
        }

        @Override public Object valAt(Object key) { return valAt(key, null); }
        @Override public Object valAt(Object key, Object notFound) {
            if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
                long i = ((Number) key).longValue();
                if (i >= 0 && i < count()) return nth((int) i);
            }
            return notFound;
        }

        @Override public Associative assoc(Object key, Object val) {
            if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
                return assocN(((Number) key).intValue(), val);
            }
            throw new IllegalArgumentException("Key must be integer");
        }

        @Override public Object invoke(Object arg1) {
            if (arg1 instanceof Integer || arg1 instanceof Long || arg1 instanceof Short || arg1 instanceof Byte) {
                return nth(((Number) arg1).intValue());
            }
            throw new IllegalArgumentException("Key must be integer: " + arg1);
        }

        @Override public Object invoke(Object arg1, Object notFound) {
            if (arg1 instanceof Integer || arg1 instanceof Long || arg1 instanceof Short || arg1 instanceof Byte) {
                return nth(((Number) arg1).intValue(), notFound);
            }
            return notFound;
        }

        @Override public ISeq seq() { return start < end ? new VecSeq(null, v, start, end) : null; }
        @Override public ISeq rseq() { return start < end ? new RVecSeq(null, v, end - 1, start) : null; }

        @Override public int compareTo(Object o) {
            if (o instanceof PersistentVector pv) {
                int c1 = count();
                if (c1 < pv.cnt) return -1;
                if (c1 > pv.cnt) return 1;
                for (int i = 0; i < c1; i++) {
                    int c = RT.compare(nth(i), pv.nth(i));
                    if (c != 0) return c;
                }
                return 0;
            }
            SubVec sv = (SubVec) o;
            int c1 = count();
            int c2 = sv.count();
            if (c1 < c2) return -1;
            if (c1 > c2) return 1;
            for (int i = 0; i < c1; i++) {
                int c = RT.compare(nth(i), sv.nth(i));
                if (c != 0) return c;
            }
            return 0;
        }

        @Override public boolean equals(Object obj) {
            if (this == obj) return true;
            int cnt = count();
            if (obj instanceof PersistentVector pv) {
                if (cnt != pv.cnt) return false;
                for (int i = 0; i < cnt; i++) if (!RT.equiv(nth(i), pv.nth(i))) return false;
                return true;
            }
            if (obj instanceof SubVec sv) {
                if (cnt != sv.count()) return false;
                for (int i = 0; i < cnt; i++) if (!RT.equiv(nth(i), sv.nth(i))) return false;
                return true;
            }
            if (obj instanceof List<?> list && !(obj instanceof Seqable)) {
                if (cnt != list.size()) return false;
                Iterator<?> it = list.iterator();
                for (int i = 0; i < cnt; i++) if (!RT.equiv(nth(i), it.next())) return false;
                return true;
            }
            if (obj instanceof Seqable) {
                if (obj instanceof PersistentHashMap || obj instanceof PersistentHashSet) return false;
                ISeq ms = RT.seq(obj);
                for (int i = 0; i < cnt; i++) {
                    if (ms == null || !RT.equiv(nth(i), ms.first())) return false;
                    ms = ms.next();
                }
                return ms == null;
            }
            return false;
        }

        @Override public int hashCode() {
            int h = this.hash;
            if (!hasHash) {
                h = 1;
                int cnt = count();
                for (int i = 0; i < cnt; i++) h = 31 * h + RT.hash(nth(i));
                this.hash = h;
                this.hasHash = true;
            }
            return h;
        }

        @Override public String toString() { return RT.printString(this); }
        @Override public int size() { return count(); }
        @Override public boolean isEmpty() { return end <= start; }
        @Override public boolean contains(Object o) { return indexOf(o) >= 0; }
        @Override public Iterator<Object> iterator() {
            return new Iterator<>() {
                int i = 0;
                final int cnt = count();
                @Override public boolean hasNext() { return i < cnt; }
                @Override public Object next() {
                    if (i >= cnt) throw new NoSuchElementException();
                    return nth(i++);
                }
            };
        }
        @Override public Object[] toArray() {
            int cnt = count();
            Object[] ret = new Object[cnt];
            for (int i = 0; i < cnt; i++) ret[i] = nth(i);
            return ret;
        }
        @Override @SuppressWarnings("unchecked") public <T> T[] toArray(T[] a) {
            int cnt = count();
            if (a.length < cnt) a = (T[]) java.lang.reflect.Array.newInstance(a.getClass().getComponentType(), cnt);
            for (int i = 0; i < cnt; i++) a[i] = (T) nth(i);
            if (a.length > cnt) a[cnt] = null;
            return a;
        }
        @Override public Object get(int index) { return nth(index); }
        @Override public int indexOf(Object o) {
            int cnt = count();
            for (int i = 0; i < cnt; i++) if (RT.equiv(nth(i), o)) return i;
            return -1;
        }
        @Override public int lastIndexOf(Object o) {
            for (int i = count() - 1; i >= 0; i--) if (RT.equiv(nth(i), o)) return i;
            return -1;
        }
        @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
        @Override public boolean containsAll(Collection<?> c) { for (Object x : c) if (!contains(x)) return false; return true; }
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
        @Override public List<Object> subList(int fromIndex, int toIndex) {
            if (fromIndex < 0 || toIndex > count() || fromIndex > toIndex) throw new IndexOutOfBoundsException();
            if (fromIndex == toIndex) return EMPTY;
            return new SubVec(null, v, start + fromIndex, start + toIndex);
        }
    }

    public static final class TransientVector extends Fn implements Counted {
        private int cnt;
        private int shift;
        private Node root;
        private Object[] tail;

        TransientVector(PersistentVector v) {
            this.cnt = v.cnt;
            this.shift = v.shift;
            this.root = editableRoot(v.root);
            this.tail = editableTail(v.tail);
        }

        private static Node editableRoot(Node node) {
            return new Node(new AtomicReference<>(Thread.currentThread()), node.array.clone());
        }

        private static Object[] editableTail(Object[] tl) {
            Object[] ret = new Object[32];
            System.arraycopy(tl, 0, ret, 0, tl.length);
            return ret;
        }

        private void ensureEditable() {
            Thread owner = root.edit.get();
            if (owner == null) {
                throw new IllegalAccessError("Transient used after persistent! call");
            }
            if (owner != Thread.currentThread()) {
                throw new IllegalAccessError("Transient used by non-owner thread");
            }
        }

        private Node ensureEditable(Node node) {
            if (node.edit == root.edit) return node;
            return new Node(root.edit, node.array.clone());
        }

        private int tailoff() {
            if (cnt < 32) return 0;
            return ((cnt - 1) >>> 5) << 5;
        }

        public TransientVector conj(Object val) {
            ensureEditable();
            int i = cnt;
            if (i - tailoff() < 32) {
                tail[i & 0x01f] = val;
                cnt++;
                return this;
            }
            Node newRoot;
            Node tailNode = new Node(root.edit, tail);
            tail = new Object[32];
            tail[0] = val;
            int newShift = shift;
            if ((cnt >>> 5) > (1 << shift)) {
                newRoot = new Node(root.edit);
                newRoot.array[0] = root;
                newRoot.array[1] = newPath(root.edit, shift, tailNode);
                newShift += 5;
            } else {
                newRoot = pushTail(shift, root, tailNode);
            }
            root = newRoot;
            shift = newShift;
            cnt++;
            return this;
        }

        private Node pushTail(int level, Node parent, Node tailnode) {
            parent = ensureEditable(parent);
            int subidx = ((cnt - 1) >>> level) & 0x01f;
            Node nodeToInsert;
            if (level == 5) {
                nodeToInsert = tailnode;
            } else {
                Node child = (Node) parent.array[subidx];
                nodeToInsert = (child != null)
                        ? pushTail(level - 5, child, tailnode)
                        : newPath(root.edit, level - 5, tailnode);
            }
            parent.array[subidx] = nodeToInsert;
            return parent;
        }

        public TransientVector assocN(int i, Object val) {
            ensureEditable();
            if (i >= 0 && i < cnt) {
                if (i >= tailoff()) {
                    tail[i & 0x01f] = val;
                    return this;
                }
                root = doAssoc(shift, root, i, val);
                return this;
            }
            if (i == cnt) return conj(val);
            throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
        }

        private Node doAssoc(int level, Node node, int i, Object val) {
            node = ensureEditable(node);
            if (level == 0) {
                node.array[i & 0x01f] = val;
            } else {
                int subidx = (i >>> level) & 0x01f;
                node.array[subidx] = doAssoc(level - 5, (Node) node.array[subidx], i, val);
            }
            return node;
        }

        public TransientVector pop() {
            ensureEditable();
            if (cnt == 0) throw new IllegalStateException("Can't pop empty vector");
            if (cnt == 1) {
                tail[0] = null;
                cnt = 0;
                return this;
            }
            int i = cnt - 1;
            if ((i & 0x01f) > 0) {
                tail[i & 0x01f] = null;
                cnt--;
                return this;
            }
            Object[] newTail = editableArrayFor(cnt - 2);
            Node newRoot = popTail(shift, root);
            int newShift = shift;
            if (newRoot == null) {
                newRoot = new Node(root.edit);
            }
            if (shift > 5 && newRoot.array[1] == null) {
                newRoot = ensureEditable((Node) newRoot.array[0]);
                newShift -= 5;
            }
            root = newRoot;
            shift = newShift;
            cnt--;
            tail = newTail;
            return this;
        }

        private Object[] editableArrayFor(int i) {
            if (i >= 0 && i < cnt) {
                if (i >= tailoff()) return tail;
                Node node = root;
                for (int level = shift; level > 0; level -= 5) {
                    node = (Node) node.array[(i >>> level) & 0x01f];
                }
                return node.array.clone();
            }
            throw new IndexOutOfBoundsException();
        }

        private Node popTail(int level, Node node) {
            node = ensureEditable(node);
            int subidx = ((cnt - 2) >>> level) & 0x01f;
            if (level > 5) {
                Node newChild = popTail(level - 5, (Node) node.array[subidx]);
                if (newChild == null && subidx == 0) return null;
                node.array[subidx] = newChild;
                return node;
            } else if (subidx == 0) {
                return null;
            } else {
                node.array[subidx] = null;
                return node;
            }
        }

        public Object nth(int i) {
            ensureEditable();
            if (i >= 0 && i < cnt) {
                if (i >= tailoff()) return tail[i & 0x01f];
                Node node = root;
                for (int level = shift; level > 0; level -= 5) {
                    node = (Node) node.array[(i >>> level) & 0x01f];
                }
                return node.array[i & 0x01f];
            }
            throw new IndexOutOfBoundsException();
        }

        public Object valAt(Object key) {
            return valAt(key, null);
        }

        public Object valAt(Object key, Object notFound) {
            ensureEditable();
            if (key instanceof Integer || key instanceof Long || key instanceof Short || key instanceof Byte) {
                int i = ((Number) key).intValue();
                if (i >= 0 && i < cnt) return nth(i);
            }
            return notFound;
        }

        @Override
        public Object invoke(Object arg1) {
            ensureEditable();
            if (arg1 instanceof Integer || arg1 instanceof Long || arg1 instanceof Short || arg1 instanceof Byte) {
                return nth(((Number) arg1).intValue());
            }
            throw new IllegalArgumentException("Key must be integer: " + arg1);
        }

        @Override
        public Object invoke(Object arg1, Object notFound) {
            return valAt(arg1, notFound);
        }

        @Override
        public int count() {
            ensureEditable();
            return cnt;
        }

        public PersistentVector persistent() {
            ensureEditable();
            root.edit.set(null);
            Object[] trimmedTail = new Object[cnt - tailoff()];
            System.arraycopy(tail, 0, trimmedTail, 0, trimmedTail.length);
            return new PersistentVector(null, cnt, shift, root, trimmedTail);
        }
    }
}
