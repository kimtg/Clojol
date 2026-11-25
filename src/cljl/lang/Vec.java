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
public final class Vec extends clojure.lang.APersistentVector
        implements IPersistentVector, IObj, IHashEq, List<Object>, RandomAccess, Comparable<Object>, java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public static final class VecNode implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final transient AtomicReference<Thread> edit;
        public final Object arr;

        public VecNode(AtomicReference<Thread> edit, Object arr) {
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
            Object target = (anode.arr instanceof Object[] oa && oa.length == 1 && oa[0] != null && oa[0].getClass().isArray())
                    ? oa[0] : anode.arr;
            return new ArrayChunk(target, offset,
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
                node = ((Object[]) ((VecNode) node).arr)[(i >>> level) & 0x01f];
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
                node = (VecNode) ((Object[]) node.arr)[(i >>> level) & 0x01f];
            }
            return node;
        }
        throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
    }

    public static final cljl.Keyword KW_BOOLEAN = cljl.Keyword.intern("boolean");
    public static final cljl.Keyword KW_BYTE = cljl.Keyword.intern("byte");
    public static final cljl.Keyword KW_SHORT = cljl.Keyword.intern("short");
    public static final cljl.Keyword KW_INT = cljl.Keyword.intern("int");
    public static final cljl.Keyword KW_LONG = cljl.Keyword.intern("long");
    public static final cljl.Keyword KW_FLOAT = cljl.Keyword.intern("float");
    public static final cljl.Keyword KW_DOUBLE = cljl.Keyword.intern("double");
    public static final cljl.Keyword KW_CHAR = cljl.Keyword.intern("char");

    private static boolean isValidPrimType(Object typeKw) {
        return typeKw == KW_BOOLEAN || typeKw == KW_BYTE || typeKw == KW_SHORT
                || typeKw == KW_INT || typeKw == KW_LONG || typeKw == KW_FLOAT
                || typeKw == KW_DOUBLE || typeKw == KW_CHAR;
    }

    private static Object newPrimArray(Object typeKw, int len) {
        if (typeKw == KW_BOOLEAN) return new boolean[len];
        if (typeKw == KW_BYTE) return new byte[len];
        if (typeKw == KW_SHORT) return new short[len];
        if (typeKw == KW_INT) return new int[len];
        if (typeKw == KW_LONG) return new long[len];
        if (typeKw == KW_FLOAT) return new float[len];
        if (typeKw == KW_DOUBLE) return new double[len];
        if (typeKw == KW_CHAR) return new char[len];
        return new Object[len];
    }

    private static void setPrim(Object typeKw, Object arr, int idx, Object val) {
        if (val == null) throw new NullPointerException();
        if (typeKw == KW_BOOLEAN) {
            if (!(val instanceof Boolean b)) throw new ClassCastException("Cannot cast " + val.getClass().getName() + " to boolean");
            ((boolean[]) arr)[idx] = b;
            return;
        }
        if (typeKw == KW_CHAR) {
            char c;
            if (val instanceof Character ch) c = ch;
            else if (val instanceof Number n) c = (char) n.intValue();
            else throw new ClassCastException("Cannot cast " + val.getClass().getName() + " to char");
            ((char[]) arr)[idx] = c;
            return;
        }
        if (typeKw == KW_BYTE || typeKw == KW_SHORT || typeKw == KW_INT || typeKw == KW_LONG
                || typeKw == KW_FLOAT || typeKw == KW_DOUBLE) {
            if (val instanceof Character ch) {
                int cv = ch;
                if (typeKw == KW_BYTE) ((byte[]) arr)[idx] = (byte) cv;
                else if (typeKw == KW_SHORT) ((short[]) arr)[idx] = (short) cv;
                else if (typeKw == KW_INT) ((int[]) arr)[idx] = cv;
                else if (typeKw == KW_LONG) ((long[]) arr)[idx] = cv;
                else if (typeKw == KW_FLOAT) ((float[]) arr)[idx] = cv;
                else ((double[]) arr)[idx] = cv;
                return;
            }
            if (!(val instanceof Number n)) {
                throw new ClassCastException("Cannot cast " + val.getClass().getName() + " to " + typeKw);
            }
            if (typeKw == KW_BYTE) ((byte[]) arr)[idx] = n.byteValue();
            else if (typeKw == KW_SHORT) ((short[]) arr)[idx] = n.shortValue();
            else if (typeKw == KW_INT) ((int[]) arr)[idx] = n.intValue();
            else if (typeKw == KW_LONG) ((long[]) arr)[idx] = n.longValue();
            else if (typeKw == KW_FLOAT) ((float[]) arr)[idx] = n.floatValue();
            else ((double[]) arr)[idx] = n.doubleValue();
            return;
        }
        ((Object[]) arr)[idx] = val;
    }

    private static Object clonePrimArray(Object arr, int newLen) {
        if (arr instanceof boolean[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof byte[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof short[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof int[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof long[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof float[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof double[] a) return Arrays.copyOf(a, newLen);
        if (arr instanceof char[] a) return Arrays.copyOf(a, newLen);
        return Arrays.copyOf((Object[]) arr, newLen);
    }

    public static Vec createOf(Object typeKw, Object[] args, int offset) {
        if (!isValidPrimType(typeKw)) {
            throw new IllegalArgumentException("Unsupported type: " + typeKw);
        }
        Vec v = new Vec(typeKw, 0, 5, new VecNode(null, new Object[32]), newPrimArray(typeKw, 0), null);
        for (int i = offset; i < args.length; i++) {
            v = v.conj(args[i]);
        }
        return v;
    }

    private static VecNode pushTail(int level, VecNode parent, VecNode tailnode, int cnt) {
        int subidx = ((cnt - 1) >>> level) & 0x01f;
        Object[] newArr = parent != null ? ((Object[]) parent.arr).clone() : new Object[32];
        VecNode nodeToInsert;
        if (level == 5) {
            nodeToInsert = tailnode;
        } else {
            VecNode child = parent != null ? (VecNode) newArr[subidx] : null;
            nodeToInsert = child != null
                    ? pushTail(level - 5, child, tailnode, cnt)
                    : newPath(level - 5, tailnode);
        }
        newArr[subidx] = nodeToInsert;
        return new VecNode(null, newArr);
    }

    private static VecNode newPath(int level, VecNode node) {
        if (level == 0) return node;
        Object[] arr = new Object[32];
        arr[0] = newPath(level - 5, node);
        return new VecNode(null, arr);
    }

    public Vec conj(Object val) {
        if (!isValidPrimType(am)) {
            throw new UnsupportedOperationException("conj on custom ArrayManager Vec not supported");
        }
        int tailLen = cnt - tailoff();
        Object tailArr = tail instanceof VecNode vn ? vn.arr : tail;
        if (tailLen < 32) {
            Object newTail = clonePrimArray(tailArr, tailLen + 1);
            setPrim(am, newTail, tailLen, val);
            return new Vec(am, cnt + 1, shift, root, newTail, meta);
        }
        VecNode tailnode = new VecNode(null, tailArr);
        VecNode newRoot;
        int newShift = shift;
        if ((cnt >>> 5) > (1 << shift)) {
            Object[] nrArr = new Object[32];
            nrArr[0] = root;
            nrArr[1] = newPath(shift, tailnode);
            newRoot = new VecNode(null, nrArr);
            newShift += 5;
        } else {
            newRoot = pushTail(shift, root, tailnode, cnt);
        }
        Object newTail = newPrimArray(am, 1);
        setPrim(am, newTail, 0, val);
        return new Vec(am, cnt + 1, newShift, newRoot, newTail, meta);
    }

    public Vec subVec(int start, int end) {
        if (start < 0 || end < start || end > cnt) {
            throw new IndexOutOfBoundsException("subvec out of range: " + start + ".." + end + " (count=" + cnt + ")");
        }
        if (isValidPrimType(am)) {
            Vec v = new Vec(am, 0, 5, new VecNode(null, new Object[32]), newPrimArray(am, 0), null);
            for (int i = start; i < end; i++) v = v.conj(nth(i));
            return v;
        }
        Object[] slice = new Object[end - start];
        for (int i = start; i < end; i++) slice[i - start] = nth(i);
        return new Vec(am, slice.length, 5, new VecNode(null, new Object[32]), slice, null);
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
    @Override public int length() { return cnt; }
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
    @Override public IMapEntry entryAt(Object key) {
        if (key instanceof Number n) {
            int i = n.intValue();
            if (i >= 0 && i < cnt) return new PersistentHashMap.MapEntry(i, nth(i));
        }
        return null;
    }
    @Override public Object valAt(Object key) { return valAt(key, null); }
    @Override public Object valAt(Object key, Object notFound) {
        if (key instanceof Number n) return nth(n.intValue(), notFound);
        return notFound;
    }
    @Override public Associative assoc(Object key, Object val) {
        if (key instanceof Number n) return assocN(n.intValue(), val);
        throw new IllegalArgumentException("Key must be integer");
    }
    @Override public IPersistentVector assocN(int i, Object val) {
        if (!isValidPrimType(am)) return toPersistentVector().assocN(i, val);
        if (i >= 0 && i < cnt) {
            if (i >= tailoff()) {
                Object tailArr = tail instanceof VecNode vn ? vn.arr : tail;
                Object newTail = clonePrimArray(tailArr, Array.getLength(tailArr));
                setPrim(am, newTail, i & 0x01f, val);
                return new Vec(am, cnt, shift, root, newTail, meta);
            }
            return new Vec(am, cnt, shift, doAssoc(shift, root, i, val), tail, meta);
        }
        if (i == cnt) return conj(val);
        throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + cnt);
    }
    private VecNode doAssoc(int level, VecNode node, int i, Object val) {
        if (level == 0) {
            Object newArr = clonePrimArray(node.arr, Array.getLength(node.arr));
            setPrim(am, newArr, i & 0x01f, val);
            return new VecNode(null, newArr);
        }
        Object[] arr = ((Object[]) node.arr).clone();
        int subidx = (i >>> level) & 0x01f;
        arr[subidx] = doAssoc(level - 5, (VecNode) arr[subidx], i, val);
        return new VecNode(null, arr);
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
        return new clojure.lang.APersistentVector.RSeq(this, cnt - 1);
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
        return subVec(fromIndex, toIndex);
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

    @Override public IPersistentVector cons(Object o) {
        return isValidPrimType(am) ? conj(o) : toPersistentVector().conj(o);
    }
    @Override public IPersistentCollection empty() {
        if (isValidPrimType(am)) {
            return new Vec(am, 0, 5, new VecNode(null, new Object[32]), newPrimArray(am, 0), meta);
        }
        return meta == null ? PersistentVector.EMPTY : PersistentVector.EMPTY.withMeta(meta);
    }
    @Override public Object peek() { return cnt > 0 ? nth(cnt - 1) : null; }
    @Override public IPersistentStack pop() {
        if (cnt == 0) throw new IllegalStateException("Can't pop empty vector");
        if (!isValidPrimType(am)) return toPersistentVector().pop();
        if (cnt == 1) return (IPersistentStack) empty();
        return subVec(0, cnt - 1).withMeta(meta);
    }

    @Override
    public int compareTo(Object o) {
        IPersistentVector other = (IPersistentVector) o;
        int n = other.count();
        if (cnt < n) return -1;
        if (cnt > n) return 1;
        for (int i = 0; i < cnt; i++) {
            int c = RT.compare(nth(i), other.nth(i));
            if (c != 0) return c;
        }
        return 0;
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
    public int hasheq() {
        int h = 1;
        for (int i = 0; i < cnt; i++) h = 31 * h + RT.hash(nth(i));
        return Murmur3.mixCollHash(h, cnt);
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
