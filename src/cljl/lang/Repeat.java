package cljl.lang;

import cljl.Fn;

/**
 * Infinite or bounded Repeat sequence (clojure.lang.Repeat) implementing IReduce and IDrop.
 */
public final class Repeat extends ASeq implements IReduce, IDrop {
    private static final long serialVersionUID = 1L;
    private static final long INFINITE = -1L;

    private final long count;
    private final Object val;
    private volatile ISeq nextCache;

    private Repeat(PersistentHashMap meta, long count, Object val) {
        super(meta);
        this.count = count;
        this.val = val;
    }

    public static ISeq create(Object val) {
        return new Repeat(null, INFINITE, val);
    }

    public static ISeq create(long count, Object val) {
        if (count <= 0) return PersistentList.EMPTY;
        return new Repeat(null, count, val);
    }

    @Override
    public Object first() {
        return val;
    }

    @Override
    public ISeq next() {
        if (count == INFINITE) {
            ISeq n = nextCache;
            if (n == null) {
                n = new Repeat(null, INFINITE, val);
                nextCache = n;
            }
            return n;
        }
        if (count > 1) {
            return new Repeat(null, count - 1, val);
        }
        return null;
    }

    @Override
    public Object reduce(Fn f) {
        Object ret = val;
        if (count == INFINITE) {
            while (true) {
                ret = f.invoke(ret, val);
                if (ret instanceof Reduced r) return r.deref();
            }
        }
        for (long i = 1; i < count; i++) {
            ret = f.invoke(ret, val);
            if (ret instanceof Reduced r) return r.deref();
        }
        return ret;
    }

    @Override
    public Object reduce(Fn f, Object init) {
        Object ret = init;
        if (count == INFINITE) {
            while (true) {
                ret = f.invoke(ret, val);
                if (ret instanceof Reduced r) return r.deref();
            }
        }
        for (long i = 0; i < count; i++) {
            ret = f.invoke(ret, val);
            if (ret instanceof Reduced r) return r.deref();
        }
        return ret;
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0) return this;
        if (count == INFINITE) return this;
        long dropped = count - n;
        return dropped > 0 ? new Repeat(null, dropped, val) : null;
    }

    @Override
    public Repeat withMeta(PersistentHashMap m) {
        if (this.meta == m) return this;
        return new Repeat(m, count, val);
    }
}
