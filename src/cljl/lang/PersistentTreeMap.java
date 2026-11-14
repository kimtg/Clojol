package cljl.lang;

import cljl.Fn;
import cljl.Numbers;
import cljl.RT;
import java.util.*;

/**
 * Persistent sorted map implemented as an immutable AVL tree with structural sharing.
 */
public final class PersistentTreeMap extends Fn
        implements Associative, IObj, Sorted, Reversible, Map<Object, Object>, Iterable<Map.Entry<Object, Object>> {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final Comparator<Object> DEFAULT_COMPARATOR = (k1, k2) -> {
        if (k1 == k2) return 0;
        if (k1 == null) return -1;
        if (k2 == null) return 1;
        if (k1 instanceof Number n1 && k2 instanceof Number n2) {
            return Numbers.compare(n1, n2);
        }
        return ((Comparable) k1).compareTo(k2);
    };

    public static final PersistentTreeMap EMPTY = new PersistentTreeMap(null, DEFAULT_COMPARATOR, null, 0);

    public final Comparator<Object> comp;
    public final Node tree;
    public final int count;
    private final PersistentHashMap meta;

    private static final class Node {
        final Object key;
        final Object val;
        final Node left;
        final Node right;
        final int height;

        Node(Object key, Object val, Node left, Node right) {
            this.key = key;
            this.val = val;
            this.left = left;
            this.right = right;
            this.height = 1 + Math.max(h(left), h(right));
        }

        static int h(Node n) {
            return n == null ? 0 : n.height;
        }
    }

    public PersistentTreeMap(PersistentHashMap meta, Comparator<Object> comp, Node tree, int count) {
        this.meta = meta;
        this.comp = comp != null ? comp : DEFAULT_COMPARATOR;
        this.tree = tree;
        this.count = count;
    }

    public static PersistentTreeMap create(Comparator<Object> comp, Object... kvs) {
        PersistentTreeMap m = new PersistentTreeMap(null, comp, null, 0);
        if (kvs != null) {
            for (int i = 0; i < kvs.length; i += 2) {
                m = m.assoc(kvs[i], kvs[i + 1]);
            }
        }
        return m;
    }

    public static PersistentTreeMap create(ISeq kvs) {
        return create(DEFAULT_COMPARATOR, kvs);
    }

    public static PersistentTreeMap create(Comparator<Object> comp, ISeq kvs) {
        PersistentTreeMap m = new PersistentTreeMap(null, comp, null, 0);
        for (ISeq s = kvs; s != null; s = s.next()) {
            Object k = s.first();
            s = s.next();
            if (s == null) throw new IllegalArgumentException("No value supplied for key: " + k);
            m = m.assoc(k, s.first());
        }
        return m;
    }

    private int doCompare(Object k1, Object k2) {
        return comp.compare(k1, k2);
    }

    private Node nodeAt(Object key) {
        Node t = tree;
        while (t != null) {
            int c = doCompare(key, t.key);
            if (c == 0) return t;
            t = (c < 0) ? t.left : t.right;
        }
        return null;
    }

    public Map.Entry<Object, Object> entryAt(Object key) {
        Node n = nodeAt(key);
        return n == null ? null : new PersistentHashMap.MapEntry(n.key, n.val);
    }

    @Override
    public boolean containsKey(Object key) {
        return nodeAt(key) != null;
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        Node n = nodeAt(key);
        return n != null ? n.val : notFound;
    }

    @Override
    public Object invoke(Object key) {
        return valAt(key);
    }

    @Override
    public Object invoke(Object key, Object notFound) {
        return valAt(key, notFound);
    }

    private static Node rotateLeft(Node n) {
        Node r = n.right;
        return new Node(r.key, r.val, new Node(n.key, n.val, n.left, r.left), r.right);
    }

    private static Node rotateRight(Node n) {
        Node l = n.left;
        return new Node(l.key, l.val, l.left, new Node(n.key, n.val, l.right, n.right));
    }

    private static Node balance(Node n) {
        if (n == null) return null;
        int bf = Node.h(n.left) - Node.h(n.right);
        if (bf > 1) {
            if (Node.h(n.left.left) < Node.h(n.left.right)) {
                n = new Node(n.key, n.val, rotateLeft(n.left), n.right);
            }
            return rotateRight(n);
        }
        if (bf < -1) {
            if (Node.h(n.right.right) < Node.h(n.right.left)) {
                n = new Node(n.key, n.val, n.left, rotateRight(n.right));
            }
            return rotateLeft(n);
        }
        return n;
    }

    private Node insert(Node n, Object key, Object val, boolean[] added) {
        if (n == null) {
            added[0] = true;
            return new Node(key, val, null, null);
        }
        int c = doCompare(key, n.key);
        if (c == 0) {
            if (n.val == val) return n;
            return new Node(n.key, val, n.left, n.right);
        }
        if (c < 0) {
            Node nl = insert(n.left, key, val, added);
            if (nl == n.left) return n;
            return balance(new Node(n.key, n.val, nl, n.right));
        } else {
            Node nr = insert(n.right, key, val, added);
            if (nr == n.right) return n;
            return balance(new Node(n.key, n.val, n.left, nr));
        }
    }

    private static Node minNode(Node n) {
        while (n.left != null) n = n.left;
        return n;
    }

    private Node removeNode(Node n, Object key, boolean[] removed) {
        if (n == null) return null;
        int c = doCompare(key, n.key);
        if (c < 0) {
            Node nl = removeNode(n.left, key, removed);
            if (!removed[0]) return n;
            return balance(new Node(n.key, n.val, nl, n.right));
        } else if (c > 0) {
            Node nr = removeNode(n.right, key, removed);
            if (!removed[0]) return n;
            return balance(new Node(n.key, n.val, n.left, nr));
        } else {
            removed[0] = true;
            if (n.left == null) return n.right;
            if (n.right == null) return n.left;
            Node succ = minNode(n.right);
            boolean[] dummy = new boolean[1];
            Node nr = removeNode(n.right, succ.key, dummy);
            return balance(new Node(succ.key, succ.val, n.left, nr));
        }
    }

    @Override
    public PersistentTreeMap assoc(Object key, Object val) {
        boolean[] added = new boolean[1];
        Node newTree = insert(tree, key, val, added);
        if (newTree == tree) return this;
        return new PersistentTreeMap(meta, comp, newTree, count + (added[0] ? 1 : 0));
    }

    public PersistentTreeMap without(Object key) {
        boolean[] removed = new boolean[1];
        Node newTree = removeNode(tree, key, removed);
        if (!removed[0]) return this;
        return new PersistentTreeMap(meta, comp, newTree, count - 1);
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
    public PersistentTreeMap withMeta(PersistentHashMap m) {
        return m == meta ? this : new PersistentTreeMap(m, comp, tree, count);
    }

    @Override
    public Comparator<?> comparator() {
        return comp;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object entryKey(Object entry) {
        return ((Map.Entry<Object, Object>) entry).getKey();
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
        if (count == 0) return null;
        return TreeSeq.create(tree, ascending);
    }

    @Override
    public ISeq seqFrom(Object key, boolean ascending) {
        if (count == 0) return null;
        ArrayList<Node> stack = new ArrayList<>();
        Node t = tree;
        while (t != null) {
            int c = doCompare(key, t.key);
            if (c == 0) {
                stack.add(t);
                break;
            } else if (ascending) {
                if (c < 0) {
                    stack.add(t);
                    t = t.left;
                } else {
                    t = t.right;
                }
            } else {
                if (c > 0) {
                    stack.add(t);
                    t = t.right;
                } else {
                    t = t.left;
                }
            }
        }
        return stack.isEmpty() ? null : new TreeSeq(stack, ascending);
    }

    private static final class TreeSeq extends ASeq {
        final List<Node> stack;
        final boolean asc;

        TreeSeq(List<Node> stack, boolean asc) {
            this(null, stack, asc);
        }

        TreeSeq(PersistentHashMap meta, List<Node> stack, boolean asc) {
            super(meta);
            this.stack = stack;
            this.asc = asc;
        }

        @Override
        public TreeSeq withMeta(PersistentHashMap m) {
            if (this.meta == m) return this;
            return new TreeSeq(m, stack, asc);
        }

        static TreeSeq create(Node root, boolean asc) {
            ArrayList<Node> st = new ArrayList<>();
            pushBranch(root, st, asc);
            return st.isEmpty() ? null : new TreeSeq(st, asc);
        }

        private static void pushBranch(Node n, List<Node> st, boolean asc) {
            while (n != null) {
                st.add(n);
                n = asc ? n.left : n.right;
            }
        }

        @Override
        public Object first() {
            Node top = stack.get(stack.size() - 1);
            return new PersistentHashMap.MapEntry(top.key, top.val);
        }

        @Override
        public ISeq next() {
            ArrayList<Node> nextStack = new ArrayList<>(stack);
            Node top = nextStack.remove(nextStack.size() - 1);
            pushBranch(asc ? top.right : top.left, nextStack, asc);
            return nextStack.isEmpty() ? null : new TreeSeq(nextStack, asc);
        }
    }

    public PersistentTreeMap empty() {
        return new PersistentTreeMap(meta, comp, null, 0);
    }

    // Map<Object, Object> & Object compatibility
    @Override public int size() { return count; }
    @Override public boolean isEmpty() { return count == 0; }
    @Override public Object get(Object key) { return valAt(key); }
    @Override public boolean containsValue(Object value) {
        for (Entry<Object, Object> e : entrySet()) {
            if (RT.equiv(e.getValue(), value)) return true;
        }
        return false;
    }
    @Override public Object put(Object key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(Map<?, ?> m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }

    @Override
    public Set<Object> keySet() {
        return new AbstractSet<>() {
            @Override public Iterator<Object> iterator() {
                return new Iterator<>() {
                    ISeq s = seq();
                    @Override public boolean hasNext() { return s != null; }
                    @Override public Object next() {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        s = s.next();
                        return e.getKey();
                    }
                };
            }
            @Override public int size() { return count; }
        };
    }

    @Override
    public Collection<Object> values() {
        return new AbstractCollection<>() {
            @Override public Iterator<Object> iterator() {
                return new Iterator<>() {
                    ISeq s = seq();
                    @Override public boolean hasNext() { return s != null; }
                    @Override public Object next() {
                        Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                        s = s.next();
                        return e.getValue();
                    }
                };
            }
            @Override public int size() { return count; }
        };
    }

    @Override
    public Set<Entry<Object, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override public Iterator<Entry<Object, Object>> iterator() {
                return PersistentTreeMap.this.iterator();
            }
            @Override public int size() { return count; }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public Iterator<Entry<Object, Object>> iterator() {
        return new Iterator<>() {
            ISeq s = seq();
            @Override public boolean hasNext() { return s != null; }
            @Override public Entry<Object, Object> next() {
                if (s == null) throw new NoSuchElementException();
                Entry<Object, Object> e = (Entry<Object, Object>) s.first();
                s = s.next();
                return e;
            }
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Map<?, ?> m)) return false;
        if (m.size() != count) return false;
        try {
            for (Entry<Object, Object> e : this) {
                Object k = e.getKey();
                if (!m.containsKey(k) || !RT.equiv(e.getValue(), m.get(k))) return false;
            }
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (Entry<Object, Object> e : this) {
            int kh = RT.hash(e.getKey());
            int vh = RT.hash(e.getValue());
            h += (kh ^ vh);
        }
        return h;
    }

    @Override
    public String toString() {
        return RT.printString(this, true);
    }
}
