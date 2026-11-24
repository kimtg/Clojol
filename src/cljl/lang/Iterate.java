package cljl.lang;

import cljl.Fn;

/**
 * Lazy infinite Iterate sequence (clojure.lang.Iterate) implementing IReduce, IDrop, and IPending.
 */
public final class Iterate extends ASeq implements IReduce, IDrop, IPending {
    private static final long serialVersionUID = 1L;
    private static final Object UNREALIZED_SEED = new Object();

    private final Fn f;
    private final Object prevSeed;
    private volatile Object seed;
    private volatile ISeq nextCache;

    private Iterate(PersistentHashMap meta, Fn f, Object prevSeed, Object seed) {
        super(meta);
        this.f = f;
        this.prevSeed = prevSeed;
        this.seed = seed;
    }

    public static ISeq create(Fn f, Object seed) {
        return new Iterate(null, f, null, seed);
    }

    @Override
    public boolean isRealized() {
        return seed != UNREALIZED_SEED;
    }

    @Override
    public Object first() {
        if (seed == UNREALIZED_SEED) {
            synchronized (this) {
                if (seed == UNREALIZED_SEED) {
                    seed = f.invoke(prevSeed);
                }
            }
        }
        return seed;
    }

    @Override
    public ISeq next() {
        ISeq n = nextCache;
        if (n == null) {
            n = new Iterate(null, f, first(), UNREALIZED_SEED);
            nextCache = n;
        }
        return n;
    }

    @Override
    public Object reduce(Fn rf) {
        Object firstVal = first();
        Object ret = firstVal;
        Object v = f.invoke(firstVal);
        while (true) {
            ret = rf.invoke(ret, v);
            if (ret instanceof Reduced r) return r.deref();
            v = f.invoke(v);
        }
    }

    @Override
    public Object reduce(Fn rf, Object init) {
        Object v = first();
        Object ret = rf.invoke(init, v);
        if (ret instanceof Reduced r) return r.deref();
        while (true) {
            v = f.invoke(v);
            ret = rf.invoke(ret, v);
            if (ret instanceof Reduced r) return r.deref();
        }
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0) return this;
        Object cur = first();
        for (int i = 0; i < n; i++) {
            cur = f.invoke(cur);
        }
        return new Iterate(null, f, null, cur);
    }

    @Override
    public Iterate withMeta(PersistentHashMap m) {
        if (this.meta == m) return this;
        return new Iterate(m, f, prevSeed, seed);
    }
}
