package cljl.lang;

import cljl.Fn;
import cljl.Keyword;
import cljl.RT;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 32-way Hash Array Mapped Trie (HAMT) persistent map with MapEntry and TransientHashMap.
 */
public final class PersistentHashMap extends Fn
        implements IPersistentMap, IObj, Map<Object, Object>, Iterable<Map.Entry<Object, Object>> {

    private static final Object NOT_FOUND = new Object();
    public static final PersistentHashMap EMPTY = new PersistentHashMap(null, 0, null, false, null);

    public final int count;
    public final INode root;
    public final boolean hasNull;
    public final Object nullValue;
    private final PersistentHashMap meta;
    private int hash;
    private volatile boolean hasHash;

    public PersistentHashMap(PersistentHashMap meta, int count, INode root, boolean hasNull, Object nullValue) {
        this.meta = meta;
        this.count = count;
        this.root = root;
        this.hasNull = hasNull;
        this.nullValue = nullValue;
    }

    public static PersistentHashMap create(Object... init) {
        if (init == null || init.length == 0) return EMPTY;
        if ((init.length & 1) != 0) {
            throw new IllegalArgumentException("No value supplied for key: " + init[init.length - 1]);
        }
        if (init.length == 2) {
            Object k = init[0];
            Object v = init[1];
            if (k == null) {
                return new PersistentHashMap(null, 1, null, true, v);
            }
            int bit = bitpos(RT.hash(k), 0);
            return new PersistentHashMap(null, 1, new BitmapIndexedNode(bit, new Object[]{k, v}), false, null);
        }
        TransientHashMap t = EMPTY.asTransient();
        for (int i = 0; i < init.length; i += 2) {
            t = t.assoc(init[i], init[i + 1]);
        }
        return t.persistent();
    }

    public static PersistentHashMap createFromMap(Map<?, ?> m) {
        if (m == null || m.isEmpty()) return EMPTY;
        if (m instanceof PersistentHashMap phm) return phm;
        TransientHashMap t = EMPTY.asTransient();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            t = t.assoc(e.getKey(), e.getValue());
        }
        return t.persistent();
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentHashMap withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new PersistentHashMap(meta, count, root, hasNull, nullValue);
    }

    @Override
    public boolean containsKey(Object key) {
        if (key == null) return hasNull;
        return root != null && root.find(0, RT.hash(key), key, NOT_FOUND) != NOT_FOUND;
    }

    public MapEntry entryAt(Object key) {
        if (key == null) {
            return hasNull ? new MapEntry(null, nullValue) : null;
        }
        if (root == null) return null;
        Object val = root.find(0, RT.hash(key), key, NOT_FOUND);
        return val != NOT_FOUND ? new MapEntry(key, val) : null;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (key == null) return hasNull ? nullValue : notFound;
        if (root == null) return notFound;
        if (key instanceof Keyword kw) return valAtKeyword(kw, notFound);
        return root.find(0, RT.hash(key), key, notFound);
    }

    public Object valAtKeyword(Keyword key, Object notFound) {
        INode r = root;
        if (r == null) return notFound;
        int hash = key.hash;
        if (r instanceof BitmapIndexedNode node) {
            int shift = 0;
            while (true) {
                int bit = 1 << ((hash >>> shift) & 0x1f);
                int bitmap = node.bitmap;
                if ((bitmap & bit) == 0) return notFound;
                int idx = Integer.bitCount(bitmap & (bit - 1));
                Object[] arr = node.array;
                Object keyOrNull = arr[2 * idx];
                Object valOrNode = arr[2 * idx + 1];
                if (keyOrNull == key) return valOrNode;
                if (keyOrNull == null) {
                    if (valOrNode instanceof BitmapIndexedNode nextNode) {
                        node = nextNode;
                        shift += 5;
                        continue;
                    }
                    return ((INode) valOrNode).find(shift + 5, hash, key, notFound);
                }
                return notFound;
            }
        }
        return r.find(0, hash, key, notFound);
    }

    @Override
    public PersistentHashMap assoc(Object key, Object val) {
        if (key == null) {
            if (hasNull && RT.equiv(val, nullValue)) return this;
            return new PersistentHashMap(meta, hasNull ? count : count + 1, root, true, val);
        }
        Box addedLeaf = new Box(null);
        INode newroot = (root == null ? BitmapIndexedNode.EMPTY : root)
                .assoc(0, RT.hash(key), key, val, addedLeaf);
        if (newroot == root) return this;
        return new PersistentHashMap(meta, addedLeaf.val == null ? count : count + 1, newroot, hasNull, nullValue);
    }

    public PersistentHashMap without(Object key) {
        if (key == null) {
            return hasNull ? new PersistentHashMap(meta, count - 1, root, false, null) : this;
        }
        if (root == null) return this;
        INode newroot = root.without(0, RT.hash(key), key);
        if (newroot == root) return this;
        return new PersistentHashMap(meta, count - 1, newroot, hasNull, nullValue);
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
    public ISeq seq() {
        ISeq s = root != null ? root.nodeSeq() : null;
        return hasNull ? new Cons(new MapEntry(null, nullValue), s) : s;
    }

    public TransientHashMap asTransient() {
        return new TransientHashMap(this);
    }

    public Object reduceKV(Fn f, Object init) {
        Object acc = init;
        if (hasNull) {
            acc = f.invoke(acc, null, nullValue);
            if (acc instanceof Reduced r) return r.deref();
        }
        if (root != null) {
            acc = root.kvreduce(f, acc);
            if (acc instanceof Reduced r) return r.deref();
        }
        return acc;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Map<?, ?> m)) return false;
        if (count != m.size()) return false;
        try {
            for (ISeq s = seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                Object k = e.getKey();
                if (m instanceof PersistentHashMap phm) {
                    Object v = phm.valAt(k, NOT_FOUND);
                    if (v == NOT_FOUND || !RT.equiv(e.getValue(), v)) return false;
                } else {
                    if (!m.containsKey(k) || !RT.equiv(e.getValue(), m.get(k))) return false;
                }
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
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                h += (RT.hash(e.getKey()) ^ RT.hash(e.getValue()));
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

    // Map<Object, Object> methods
    @Override public int size() { return count; }
    @Override public boolean isEmpty() { return count == 0; }
    @Override public boolean containsValue(Object value) {
        for (ISeq s = seq(); s != null; s = s.next()) {
            MapEntry e = (MapEntry) s.first();
            if (RT.equiv(e.val, value)) return true;
        }
        return false;
    }
    @Override public Object get(Object key) { return valAt(key); }
    @Override public Object put(Object key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<?, ?> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    @Override
    public Set<Object> keySet() {
        Set<Object> set = new LinkedHashSet<>(count);
        for (ISeq s = seq(); s != null; s = s.next()) {
            set.add(((MapEntry) s.first()).key);
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public Collection<Object> values() {
        List<Object> list = new ArrayList<>(count);
        for (ISeq s = seq(); s != null; s = s.next()) {
            list.add(((MapEntry) s.first()).val);
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public Set<Map.Entry<Object, Object>> entrySet() {
        Set<Map.Entry<Object, Object>> set = new LinkedHashSet<>(count);
        for (ISeq s = seq(); s != null; s = s.next()) {
            set.add((MapEntry) s.first());
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Iterator<Map.Entry<Object, Object>> iterator() {
        final ISeq s = seq();
        if (s == null) return Collections.emptyIterator();
        return (Iterator) new ASeq.SeqIterator(s);
    }

    public static final class Box {
        public Object val;
        public Box(Object val) { this.val = val; }
    }

    public interface INode {
        INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf);
        INode without(int shift, int hash, Object key);
        INode assoc(AtomicReference<Thread> edit, int shift, int hash, Object key, Object val, Box addedLeaf);
        INode without(AtomicReference<Thread> edit, int shift, int hash, Object key, Box removedLeaf);
        Object find(int shift, int hash, Object key, Object notFound);
        ISeq nodeSeq();
        Object kvreduce(Fn f, Object init);
    }

    static int mask(int hash, int shift) {
        return (hash >>> shift) & 0x01f;
    }

    static int bitpos(int hash, int shift) {
        return 1 << mask(hash, shift);
    }

    public static final class BitmapIndexedNode implements INode {
        public static final BitmapIndexedNode EMPTY = new BitmapIndexedNode(null, 0, new Object[0]);

        final AtomicReference<Thread> edit;
        int bitmap;
        Object[] array;

        BitmapIndexedNode(int bitmap, Object[] array) {
            this(null, bitmap, array);
        }

        BitmapIndexedNode(AtomicReference<Thread> edit, int bitmap, Object[] array) {
            this.edit = edit;
            this.bitmap = bitmap;
            this.array = array;
        }

        int index(int bit) {
            return Integer.bitCount(bitmap & (bit - 1));
        }

        BitmapIndexedNode ensureEditable(AtomicReference<Thread> edit) {
            if (this.edit == edit) return this;
            return new BitmapIndexedNode(edit, bitmap, array.clone());
        }

        @Override
        public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf) {
            int bit = bitpos(hash, shift);
            int idx = index(bit);
            if ((bitmap & bit) != 0) {
                Object keyOrNull = array[2 * idx];
                Object valOrNode = array[2 * idx + 1];
                if (keyOrNull == null) {
                    INode n = ((INode) valOrNode).assoc(shift + 5, hash, key, val, addedLeaf);
                    if (n == valOrNode) return this;
                    Object[] newArray = array.clone();
                    newArray[2 * idx + 1] = n;
                    return new BitmapIndexedNode(bitmap, newArray);
                }
                if (key == keyOrNull || RT.equiv(key, keyOrNull)) {
                    if (val == valOrNode) return this;
                    Object[] newArray = array.clone();
                    newArray[2 * idx + 1] = val;
                    return new BitmapIndexedNode(bitmap, newArray);
                }
                addedLeaf.val = addedLeaf;
                Object[] newArray = array.clone();
                newArray[2 * idx] = null;
                newArray[2 * idx + 1] = createNode(shift + 5, keyOrNull, valOrNode, hash, key, val);
                return new BitmapIndexedNode(bitmap, newArray);
            } else {
                int n = Integer.bitCount(bitmap);
                Object[] newArray = new Object[2 * (n + 1)];
                System.arraycopy(array, 0, newArray, 0, 2 * idx);
                newArray[2 * idx] = key;
                addedLeaf.val = addedLeaf;
                newArray[2 * idx + 1] = val;
                System.arraycopy(array, 2 * idx, newArray, 2 * (idx + 1), 2 * (n - idx));
                return new BitmapIndexedNode(bitmap | bit, newArray);
            }
        }

        @Override
        public INode assoc(AtomicReference<Thread> edit, int shift, int hash, Object key, Object val, Box addedLeaf) {
            int bit = bitpos(hash, shift);
            int idx = index(bit);
            if ((bitmap & bit) != 0) {
                Object keyOrNull = array[2 * idx];
                Object valOrNode = array[2 * idx + 1];
                if (keyOrNull == null) {
                    INode n = ((INode) valOrNode).assoc(edit, shift + 5, hash, key, val, addedLeaf);
                    if (n == valOrNode) return this;
                    BitmapIndexedNode editable = ensureEditable(edit);
                    editable.array[2 * idx + 1] = n;
                    return editable;
                }
                if (key == keyOrNull || RT.equiv(key, keyOrNull)) {
                    if (val == valOrNode) return this;
                    BitmapIndexedNode editable = ensureEditable(edit);
                    editable.array[2 * idx + 1] = val;
                    return editable;
                }
                addedLeaf.val = addedLeaf;
                BitmapIndexedNode editable = ensureEditable(edit);
                editable.array[2 * idx] = null;
                editable.array[2 * idx + 1] = createNode(edit, shift + 5, keyOrNull, valOrNode, hash, key, val);
                return editable;
            } else {
                int n = Integer.bitCount(bitmap);
                BitmapIndexedNode editable = ensureEditable(edit);
                Object[] newArray = new Object[2 * (n + 1)];
                System.arraycopy(editable.array, 0, newArray, 0, 2 * idx);
                newArray[2 * idx] = key;
                addedLeaf.val = addedLeaf;
                newArray[2 * idx + 1] = val;
                System.arraycopy(editable.array, 2 * idx, newArray, 2 * (idx + 1), 2 * (n - idx));
                editable.array = newArray;
                editable.bitmap |= bit;
                return editable;
            }
        }

        @Override
        public INode without(int shift, int hash, Object key) {
            int bit = bitpos(hash, shift);
            if ((bitmap & bit) == 0) return this;
            int idx = index(bit);
            Object keyOrNull = array[2 * idx];
            Object valOrNode = array[2 * idx + 1];
            if (keyOrNull == null) {
                INode n = ((INode) valOrNode).without(shift + 5, hash, key);
                if (n == valOrNode) return this;
                if (n != null) {
                    Object[] newArray = array.clone();
                    newArray[2 * idx + 1] = n;
                    return new BitmapIndexedNode(bitmap, newArray);
                }
                if (bitmap == bit) return null;
                Object[] newArray = new Object[array.length - 2];
                System.arraycopy(array, 0, newArray, 0, 2 * idx);
                System.arraycopy(array, 2 * (idx + 1), newArray, 2 * idx, array.length - 2 * (idx + 1));
                return new BitmapIndexedNode(bitmap ^ bit, newArray);
            }
            if (RT.equiv(key, keyOrNull)) {
                if (bitmap == bit) return null;
                Object[] newArray = new Object[array.length - 2];
                System.arraycopy(array, 0, newArray, 0, 2 * idx);
                System.arraycopy(array, 2 * (idx + 1), newArray, 2 * idx, array.length - 2 * (idx + 1));
                return new BitmapIndexedNode(bitmap ^ bit, newArray);
            }
            return this;
        }

        @Override
        public INode without(AtomicReference<Thread> edit, int shift, int hash, Object key, Box removedLeaf) {
            int bit = bitpos(hash, shift);
            if ((bitmap & bit) == 0) return this;
            int idx = index(bit);
            Object keyOrNull = array[2 * idx];
            Object valOrNode = array[2 * idx + 1];
            if (keyOrNull == null) {
                INode n = ((INode) valOrNode).without(edit, shift + 5, hash, key, removedLeaf);
                if (n == valOrNode) return this;
                BitmapIndexedNode editable = ensureEditable(edit);
                if (n != null) {
                    editable.array[2 * idx + 1] = n;
                    return editable;
                }
                if (editable.bitmap == bit) return null;
                Object[] newArray = new Object[editable.array.length - 2];
                System.arraycopy(editable.array, 0, newArray, 0, 2 * idx);
                System.arraycopy(editable.array, 2 * (idx + 1), newArray, 2 * idx, editable.array.length - 2 * (idx + 1));
                editable.bitmap ^= bit;
                editable.array = newArray;
                return editable;
            }
            if (RT.equiv(key, keyOrNull)) {
                removedLeaf.val = removedLeaf;
                if (bitmap == bit) return null;
                BitmapIndexedNode editable = ensureEditable(edit);
                Object[] newArray = new Object[editable.array.length - 2];
                System.arraycopy(editable.array, 0, newArray, 0, 2 * idx);
                System.arraycopy(editable.array, 2 * (idx + 1), newArray, 2 * idx, editable.array.length - 2 * (idx + 1));
                editable.bitmap ^= bit;
                editable.array = newArray;
                return editable;
            }
            return this;
        }

        @Override
        public Object find(int shift, int hash, Object key, Object notFound) {
            BitmapIndexedNode node = this;
            while (true) {
                int bit = bitpos(hash, shift);
                int bitmap = node.bitmap;
                if ((bitmap & bit) == 0) return notFound;
                int idx = Integer.bitCount(bitmap & (bit - 1));
                Object[] arr = node.array;
                Object keyOrNull = arr[2 * idx];
                Object valOrNode = arr[2 * idx + 1];
                if (keyOrNull == null) {
                    if (valOrNode instanceof BitmapIndexedNode nextNode) {
                        node = nextNode;
                        shift += 5;
                        continue;
                    }
                    return ((INode) valOrNode).find(shift + 5, hash, key, notFound);
                }
                if (key == keyOrNull || RT.equiv(key, keyOrNull)) {
                    return valOrNode;
                }
                return notFound;
            }
        }

        @Override
        public ISeq nodeSeq() {
            return NodeSeq.create(array);
        }

        @Override
        public Object kvreduce(Fn f, Object init) {
            for (int i = 0; i < array.length; i += 2) {
                Object k = array[i];
                Object v = array[i + 1];
                if (k != null) {
                    init = f.invoke(init, k, v);
                } else if (v != null) {
                    init = ((INode) v).kvreduce(f, init);
                }
                if (init instanceof Reduced) return init;
            }
            return init;
        }
    }

    static INode createNode(int shift, Object key1, Object val1, int key2hash, Object key2, Object val2) {
        int key1hash = RT.hash(key1);
        if (key1hash == key2hash) {
            return new HashCollisionNode(null, key1hash, 2, new Object[]{key1, val1, key2, val2});
        }
        Box addedLeaf = new Box(null);
        return BitmapIndexedNode.EMPTY
                .assoc(shift, key1hash, key1, val1, addedLeaf)
                .assoc(shift, key2hash, key2, val2, addedLeaf);
    }

    static INode createNode(AtomicReference<Thread> edit, int shift, Object key1, Object val1, int key2hash, Object key2, Object val2) {
        int key1hash = RT.hash(key1);
        if (key1hash == key2hash) {
            return new HashCollisionNode(edit, key1hash, 2, new Object[]{key1, val1, key2, val2});
        }
        Box addedLeaf = new Box(null);
        return BitmapIndexedNode.EMPTY
                .assoc(edit, shift, key1hash, key1, val1, addedLeaf)
                .assoc(edit, shift, key2hash, key2, val2, addedLeaf);
    }

    static final class HashCollisionNode implements INode {
        final AtomicReference<Thread> edit;
        final int hash;
        int count;
        Object[] array;

        HashCollisionNode(int hash, int count, Object[] array) {
            this(null, hash, count, array);
        }

        HashCollisionNode(AtomicReference<Thread> edit, int hash, int count, Object[] array) {
            this.edit = edit;
            this.hash = hash;
            this.count = count;
            this.array = array;
        }

        HashCollisionNode ensureEditable(AtomicReference<Thread> edit) {
            if (this.edit == edit) return this;
            return new HashCollisionNode(edit, hash, count, array.clone());
        }

        int findIndex(Object key) {
            for (int i = 0; i < 2 * count; i += 2) {
                if (RT.equiv(key, array[i])) return i;
            }
            return -1;
        }

        @Override
        public INode assoc(int shift, int hash, Object key, Object val, Box addedLeaf) {
            if (hash == this.hash) {
                int idx = findIndex(key);
                if (idx != -1) {
                    if (array[idx + 1] == val) return this;
                    Object[] newArray = array.clone();
                    newArray[idx + 1] = val;
                    return new HashCollisionNode(hash, count, newArray);
                }
                Object[] newArray = new Object[2 * (count + 1)];
                System.arraycopy(array, 0, newArray, 0, 2 * count);
                newArray[2 * count] = key;
                newArray[2 * count + 1] = val;
                addedLeaf.val = addedLeaf;
                return new HashCollisionNode(hash, count + 1, newArray);
            }
            return new BitmapIndexedNode(bitpos(this.hash, shift), new Object[]{null, this})
                    .assoc(shift, hash, key, val, addedLeaf);
        }

        @Override
        public INode assoc(AtomicReference<Thread> edit, int shift, int hash, Object key, Object val, Box addedLeaf) {
            if (hash == this.hash) {
                int idx = findIndex(key);
                if (idx != -1) {
                    if (array[idx + 1] == val) return this;
                    HashCollisionNode editable = ensureEditable(edit);
                    editable.array[idx + 1] = val;
                    return editable;
                }
                HashCollisionNode editable = ensureEditable(edit);
                Object[] newArray = new Object[2 * (editable.count + 1)];
                System.arraycopy(editable.array, 0, newArray, 0, 2 * editable.count);
                newArray[2 * editable.count] = key;
                newArray[2 * editable.count + 1] = val;
                editable.array = newArray;
                editable.count++;
                addedLeaf.val = addedLeaf;
                return editable;
            }
            return new BitmapIndexedNode(edit, bitpos(this.hash, shift), new Object[]{null, this})
                    .assoc(edit, shift, hash, key, val, addedLeaf);
        }

        @Override
        public INode without(int shift, int hash, Object key) {
            int idx = findIndex(key);
            if (idx == -1) return this;
            if (count == 1) return null;
            Object[] newArray = new Object[2 * (count - 1)];
            System.arraycopy(array, 0, newArray, 0, idx);
            System.arraycopy(array, idx + 2, newArray, idx, 2 * count - (idx + 2));
            return new HashCollisionNode(hash, count - 1, newArray);
        }

        @Override
        public INode without(AtomicReference<Thread> edit, int shift, int hash, Object key, Box removedLeaf) {
            int idx = findIndex(key);
            if (idx == -1) return this;
            removedLeaf.val = removedLeaf;
            if (count == 1) return null;
            HashCollisionNode editable = ensureEditable(edit);
            Object[] newArray = new Object[2 * (editable.count - 1)];
            System.arraycopy(editable.array, 0, newArray, 0, idx);
            System.arraycopy(editable.array, idx + 2, newArray, idx, 2 * editable.count - (idx + 2));
            editable.array = newArray;
            editable.count--;
            return editable;
        }

        @Override
        public Object find(int shift, int hash, Object key, Object notFound) {
            int idx = findIndex(key);
            if (idx < 0) return notFound;
            return array[idx + 1];
        }

        @Override
        public ISeq nodeSeq() {
            return NodeSeq.create(array);
        }

        @Override
        public Object kvreduce(Fn f, Object init) {
            for (int i = 0; i < 2 * count; i += 2) {
                init = f.invoke(init, array[i], array[i + 1]);
                if (init instanceof Reduced) return init;
            }
            return init;
        }
    }

    static final class NodeSeq extends ASeq {
        final Object[] array;
        final int i;
        final ISeq s;

        NodeSeq(PersistentHashMap meta, Object[] array, int i, ISeq s) {
            super(meta);
            this.array = array;
            this.i = i;
            this.s = s;
        }

        static ISeq create(Object[] array) {
            return create(array, 0, null);
        }

        private static ISeq create(Object[] array, int i, ISeq s) {
            if (s != null) return new NodeSeq(null, array, i, s);
            for (int j = i; j < array.length; j += 2) {
                if (array[j] != null) return new NodeSeq(null, array, j, null);
                INode node = (INode) array[j + 1];
                if (node != null) {
                    ISeq nodeSeq = node.nodeSeq();
                    if (nodeSeq != null) return new NodeSeq(null, array, j + 2, nodeSeq);
                }
            }
            return null;
        }

        @Override
        public Object first() {
            if (s != null) return s.first();
            return new MapEntry(array[i], array[i + 1]);
        }

        @Override
        public ISeq next() {
            if (s != null) return create(array, i, s.next());
            return create(array, i + 2, null);
        }

        @Override
        public NodeSeq withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new NodeSeq(meta, array, i, s);
        }
    }

    public static final class MapEntry extends Fn
            implements IObj, Map.Entry<Object, Object>, Seqable, Counted, List<Object>, RandomAccess, Comparable<Object> {
        public final Object key;
        public final Object val;
        private final PersistentHashMap meta;

        public MapEntry(Object key, Object val) {
            this(null, key, val);
        }

        public MapEntry(PersistentHashMap meta, Object key, Object val) {
            this.meta = meta;
            this.key = key;
            this.val = val;
        }

        @Override public PersistentHashMap meta() { return meta; }
        @Override public MapEntry withMeta(PersistentHashMap m) {
            return m == meta ? this : new MapEntry(m, key, val);
        }

        @Override public Object getKey() { return key; }
        @Override public Object getValue() { return val; }
        @Override public Object setValue(Object value) { throw new UnsupportedOperationException(); }

        public Object nth(int i) {
            return switch (i) {
                case 0 -> key;
                case 1 -> val;
                default -> throw new IndexOutOfBoundsException("Index " + i + " out of bounds for MapEntry");
            };
        }

        public PersistentVector toVector() {
            return PersistentVector.createOwning(key, val);
        }

        @Override public int count() { return 2; }
        @Override public ISeq seq() { return PersistentList.createFromArray(key, val); }

        @Override
        public Object invoke(Object arg1) {
            if (arg1 instanceof Number n) return nth(n.intValue());
            throw new IllegalArgumentException("Key must be integer");
        }

        @Override
        public int compareTo(Object o) {
            return toVector().compareTo(o);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj instanceof Map.Entry<?, ?> e) {
                return RT.equiv(key, e.getKey()) && RT.equiv(val, e.getValue());
            }
            return toVector().equals(obj);
        }

        @Override
        public int hashCode() {
            return 31 * (31 + RT.hash(key)) + RT.hash(val);
        }

        @Override
        public String toString() {
            return "[" + RT.printString(key) + " " + RT.printString(val) + "]";
        }

        // List methods for seamless vector compatibility
        @Override public int size() { return 2; }
        @Override public boolean isEmpty() { return false; }
        @Override public boolean contains(Object o) { return RT.equiv(key, o) || RT.equiv(val, o); }
        @Override public Iterator<Object> iterator() { return Arrays.asList(key, val).iterator(); }
        @Override public Object[] toArray() { return new Object[]{key, val}; }
        @Override @SuppressWarnings("unchecked") public <T> T[] toArray(T[] a) { return (T[]) Arrays.asList(key, val).toArray(a); }
        @Override public Object get(int index) { return nth(index); }
        @Override public int indexOf(Object o) { return RT.equiv(key, o) ? 0 : (RT.equiv(val, o) ? 1 : -1); }
        @Override public int lastIndexOf(Object o) { return RT.equiv(val, o) ? 1 : (RT.equiv(key, o) ? 0 : -1); }
        @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
        @Override public boolean containsAll(Collection<?> c) { return toVector().containsAll(c); }
        @Override public boolean addAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean addAll(int index, Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public Object set(int index, Object element) { throw new UnsupportedOperationException(); }
        @Override public void add(int index, Object element) { throw new UnsupportedOperationException(); }
        @Override public Object remove(int index) { throw new UnsupportedOperationException(); }
        @Override public ListIterator<Object> listIterator() { return Arrays.asList(key, val).listIterator(); }
        @Override public ListIterator<Object> listIterator(int index) { return Arrays.asList(key, val).listIterator(index); }
        @Override public List<Object> subList(int fromIndex, int toIndex) { return toVector().subList(fromIndex, toIndex); }
    }

    public static final class TransientHashMap extends Fn implements Counted {
        private final AtomicReference<Thread> edit;
        private INode root;
        private int count;
        private boolean hasNull;
        private Object nullValue;
        private final Box leafFlag = new Box(null);

        TransientHashMap(PersistentHashMap map) {
            this.edit = new AtomicReference<>(Thread.currentThread());
            this.root = map.root;
            this.count = map.count;
            this.hasNull = map.hasNull;
            this.nullValue = map.nullValue;
        }

        private void ensureEditable() {
            Thread owner = edit.get();
            if (owner == null) throw new IllegalAccessError("Transient used after persistent! call");
            if (owner != Thread.currentThread()) throw new IllegalAccessError("Transient used by non-owner thread");
        }

        public TransientHashMap assoc(Object key, Object val) {
            ensureEditable();
            if (key == null) {
                if (nullValue != val) nullValue = val;
                if (!hasNull) {
                    count++;
                    hasNull = true;
                }
                return this;
            }
            leafFlag.val = null;
            INode n = (root == null ? BitmapIndexedNode.EMPTY : root)
                    .assoc(edit, 0, RT.hash(key), key, val, leafFlag);
            if (n != root) root = n;
            if (leafFlag.val != null) count++;
            return this;
        }

        public TransientHashMap without(Object key) {
            ensureEditable();
            if (key == null) {
                if (!hasNull) return this;
                hasNull = false;
                nullValue = null;
                count--;
                return this;
            }
            if (root == null) return this;
            leafFlag.val = null;
            INode n = root.without(edit, 0, RT.hash(key), key, leafFlag);
            if (n != root) root = n;
            if (leafFlag.val != null) count--;
            return this;
        }

        public Object valAt(Object key, Object notFound) {
            ensureEditable();
            if (key == null) return hasNull ? nullValue : notFound;
            if (root == null) return notFound;
            return root.find(0, RT.hash(key), key, notFound);
        }

        @Override
        public int count() {
            ensureEditable();
            return count;
        }

        @Override
        public Object invoke(Object key) {
            return valAt(key, null);
        }

        @Override
        public Object invoke(Object key, Object notFound) {
            return valAt(key, notFound);
        }

        public PersistentHashMap persistent() {
            ensureEditable();
            edit.set(null);
            return new PersistentHashMap(null, count, root, hasNull, nullValue);
        }
    }
}
