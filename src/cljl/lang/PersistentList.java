package cljl.lang;

import cljl.RT;
import java.util.*;

public final class PersistentList extends ASeq implements Counted {
    private final Object first;
    private final ISeq rest;
    private final int count;

    public static final EmptyList EMPTY = new EmptyList(null);

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

    public static ISeq create(List<?> list) {
        ISeq ret = EMPTY;
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
        return rest != null ? rest.seq() : null;
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public ISeq cons(Object o) {
        return new PersistentList(meta, o, this, count + 1);
    }

    @Override
    public PersistentList withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new PersistentList(meta, first, rest, count);
    }

    public Object peek() {
        return first;
    }

    public ISeq pop() {
        if (rest == null) return EMPTY.withMeta(meta);
        return rest;
    }

    public static final class EmptyList extends ASeq implements Counted {
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
        public ISeq cons(Object o) {
            return new PersistentList(meta, o, null, 1);
        }

        @Override
        public EmptyList withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new EmptyList(meta);
        }

        public Object peek() { return null; }
        public ISeq pop() { throw new IllegalStateException("Can't pop empty list"); }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof Seqable || obj instanceof List<?>)) return false;
            if (obj instanceof PersistentHashMap || obj instanceof PersistentHashSet
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
