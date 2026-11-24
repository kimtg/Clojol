package cljl.lang;

import cljl.RT;
import java.util.*;

public final class PersistentList extends clojure.lang.PersistentList implements IPersistentList {
    private static final long serialVersionUID = 1L;
    public final Object first;
    public final ISeq rest;
    public final int count;

    private static EmptyList _empty;
    public static EmptyList emptyInstance() {
        EmptyList e = _empty;
        if (e == null) { e = new EmptyList(null); _empty = e; }
        return e;
    }

    public static final EmptyList EMPTY = emptyInstance();

    public PersistentList(Object first) {
        super(null);
        this.first = first;
        this.rest = null;
        this.count = 1;
    }

    public PersistentList(PersistentHashMap meta, Object first, ISeq rest, int count) {
        super(meta);
        this.first = first;
        this.rest = rest;
        this.count = count;
    }

    public static IPersistentList create(List<?> list) {
        IPersistentList ret = EMPTY;
        for (int i = list.size() - 1; i >= 0; i--) {
            ret = ret.cons(list.get(i));
        }
        return ret;
    }

    public static ISeq createFromArray(Object... items) {
        if (items == null || items.length == 0) return EMPTY;
        ISeq ret = EMPTY;
        for (int i = items.length - 1; i >= 0; i--) {
            ret = ret.cons(items[i]);
        }
        return ret;
    }

    public static ISeq createFromArraySlice(Object[] items, int offset) {
        if (items == null || offset >= items.length) return EMPTY;
        ISeq ret = EMPTY;
        for (int i = items.length - 1; i >= offset; i--) {
            ret = ret.cons(items[i]);
        }
        return ret;
    }

    @Override
    public Object first() {
        return first;
    }

    @Override
    public ISeq next() {
        if (count == 1) return null;
        if (rest instanceof PersistentList) return rest;
        return rest != null ? rest.seq() : null;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public PersistentList cons(Object o) {
        return new PersistentList(meta, o, this, count + 1);
    }

    @Override
    public PersistentList withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new PersistentList(meta, first, rest, count);
    }

    @Override
    public Object peek() {
        return first;
    }

    @Override
    public IPersistentList pop() {
        if (rest == null) return EMPTY.withMeta(meta);
        if (rest instanceof IPersistentList pl) return pl;
        return EMPTY.withMeta(meta);
    }

    public static final class EmptyList extends ASeq implements IPersistentList {
        private static final long serialVersionUID = 1L;

        public EmptyList(PersistentHashMap meta) {
            super(meta);
        }

        @Override public Object first() { return null; }
        @Override public ISeq next() { return null; }
        @Override public ISeq more() { return this; }
        @Override public ISeq seq() { return null; }
        @Override public int count() { return 0; }
        @Override public boolean isEmpty() { return true; }

        @Override
        public PersistentList cons(Object o) {
            return new PersistentList(meta, o, null, 1);
        }

        @Override
        public EmptyList withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new EmptyList(meta);
        }

        @Override public Object peek() { return null; }
        @Override public IPersistentList pop() { throw new IllegalStateException("Can't pop empty list"); }

        @Override
        public int hasheq() {
            return Murmur3.mixCollHash(1, 0);
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Seqable || obj instanceof List<?>)) return false;
            if (obj instanceof IPersistentMap || obj instanceof IPersistentSet
                    || obj instanceof Map<?, ?> || obj instanceof Set<?>) {
                return false;
            }
            return RT.seq(obj) == null;
        }

        @Override
        public Iterator<Object> iterator() {
            return Collections.emptyIterator();
        }
    }
}
