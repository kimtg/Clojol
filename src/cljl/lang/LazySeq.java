package cljl.lang;

import cljl.Fn;
import cljl.RT;

/**
 * Thunk-backed memoized lazy sequence.
 * Unwraps nested LazySeq instances iteratively to prevent stack overflow on deep lazy chains.
 */
public final class LazySeq extends ASeq {
    private volatile Fn fn;
    private Object sv;
    private volatile ISeq s;

    public LazySeq(Fn fn) {
        super(null);
        this.fn = fn;
    }

    public LazySeq(PersistentHashMap meta, ISeq s) {
        super(meta);
        this.fn = null;
        this.s = s;
    }

    private synchronized Object sval() {
        if (fn != null) {
            sv = fn.invoke();
            fn = null;
        }
        if (sv != null) {
            return sv;
        }
        return s;
    }

    @Override
    public synchronized ISeq seq() {
        sval();
        if (sv != null) {
            Object ls = sv;
            sv = null;
            while (ls instanceof LazySeq lseq) {
                ls = lseq.sval();
            }
            s = RT.seq(ls);
        }
        return s;
    }

    public boolean isRealized() {
        return fn == null;
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
    public LazySeq withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new LazySeq(meta, seq());
    }
}
