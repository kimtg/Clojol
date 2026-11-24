package cljl.lang;

import cljl.Fn;
import cljl.RT;

/**
 * Thunk-backed memoized lazy sequence.
 * Unwraps nested LazySeq instances iteratively to prevent stack overflow on deep lazy chains.
 */
public class LazySeq extends clojure.lang.LazySeq implements IReduceInit, IPending {
    private volatile Fn fn;
    private Object sv;
    private ISeq s;
    private boolean computed;
    private volatile boolean realized;

    public LazySeq(Fn fn) {
        super(null);
        this.fn = fn;
    }

    public LazySeq(PersistentHashMap meta, ISeq s) {
        super(meta);
        this.fn = null;
        this.s = s;
        this.computed = true;
        this.realized = true;
    }

    protected LazySeq(PersistentHashMap meta) {
        super(meta);
    }

    private LazySeq(PersistentHashMap meta, Fn fn) {
        super(meta);
        this.fn = fn;
    }

    /** Subclasses may override to avoid allocating a separate Fn thunk object per lazy node. */
    protected Object compute() {
        return fn.invoke();
    }

    private synchronized Object sval() {
        if (!computed) {
            computed = true;
            sv = compute();
            fn = null;
            if (sv == null) {
                s = null;
                realized = true;
            }
        }
        if (sv != null) {
            return sv;
        }
        return s;
    }

    @Override
    public ISeq seq() {
        if (realized) return s;
        return seqSlow();
    }

    private synchronized ISeq seqSlow() {
        if (realized) return s;
        if (!computed) {
            computed = true;
            sv = compute();
            fn = null;
        }
        if (sv != null) {
            Object ls = sv;
            while (ls instanceof LazySeq lseq) {
                ls = lseq.sval();
                sv = ls;
            }
            ISeq res = RT.seq(ls);
            sv = null;
            s = res;
        }
        realized = true;
        return s;
    }

    public boolean isRealized() {
        return realized;
    }

    @Override
    public Object first() {
        ISeq sq = seq();
        return sq == null ? null : sq.first();
    }

    @Override
    public ISeq next() {
        ISeq sq = seq();
        return sq == null ? null : sq.next();
    }

    @Override
    public ISeq more() {
        ISeq sq = seq();
        return sq == null ? PersistentList.EMPTY : sq.more();
    }

    @Override
    public boolean isEmpty() {
        return seq() == null;
    }

    @Override
    public int count() {
        int c = 0;
        for (ISeq sq = seq(); sq != null; sq = sq.next()) {
            c++;
        }
        return c;
    }

    @Override
    public Object reduce(Fn f, Object init) {
        ISeq sq = seq();
        if (sq == null) return init;
        if (sq instanceof IReduceInit ri) return ri.reduce(f, init);
        Object acc = init;
        for (; sq != null; sq = sq.next()) {
            acc = f.invoke(acc, sq.first());
            if (acc instanceof Reduced r) return r.deref();
        }
        return acc;
    }

    @Override
    public LazySeq withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        if (realized) {
            return new LazySeq(meta, s);
        }
        return new LazySeq(meta, Fn.of0("lazy-meta", this::seq));
    }
}
