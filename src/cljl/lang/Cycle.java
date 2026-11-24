package cljl.lang;

import cljl.Fn;
import cljl.RT;

/**
 * Lazy infinite Cycle sequence (clojure.lang.Cycle) implementing IReduce and IDrop.
 */
public final class Cycle extends ASeq implements IReduce, IDrop {
    private static final long serialVersionUID = 1L;

    private final ISeq all;
    private final ISeq prev;
    private volatile ISeq current;
    private volatile ISeq nextCache;

    private Cycle(PersistentHashMap meta, ISeq all, ISeq prev, ISeq current) {
        super(meta);
        this.all = all;
        this.prev = prev;
        this.current = current;
    }

    public static ISeq create(Object coll) {
        ISeq s = RT.seq(coll);
        if (s == null) return PersistentList.EMPTY;
        return new Cycle(null, s, null, s);
    }

    private ISeq current() {
        ISeq c = this.current;
        if (c == null) {
            ISeq n = prev.next();
            c = (n == null) ? all : n;
            this.current = c;
        }
        return c;
    }

    @Override
    public Object first() {
        return current().first();
    }

    @Override
    public ISeq next() {
        ISeq n = nextCache;
        if (n == null) {
            n = new Cycle(null, all, current(), null);
            nextCache = n;
        }
        return n;
    }

    @Override
    public Object reduce(Fn f) {
        ISeq s = current();
        Object ret = s.first();
        while (true) {
            s = s.next();
            if (s == null) s = all;
            ret = f.invoke(ret, s.first());
            if (ret instanceof Reduced r) return r.deref();
        }
    }

    @Override
    public Object reduce(Fn f, Object init) {
        ISeq s = current();
        Object ret = init;
        while (true) {
            ret = f.invoke(ret, s.first());
            if (ret instanceof Reduced r) return r.deref();
            s = s.next();
            if (s == null) s = all;
        }
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0) return this;
        ISeq s = current();
        for (int i = 0; i < n; i++) {
            s = s.next();
            if (s == null) s = all;
        }
        return new Cycle(null, all, null, s);
    }

    @Override
    public Cycle withMeta(PersistentHashMap m) {
        if (this.meta == m) return this;
        return new Cycle(m, all, prev, current);
    }
}
