package cljl.lang;

import cljl.Fn;
import cljl.Numbers;

/**
 * General numeric Range supporting Double, Ratio, BigInt, BigDecimal, and Long steps
 * (clojure.lang.Range), implementing IChunkedSeq, IReduce, IReduceInit, and IDrop.
 */
public final class Range extends ASeq implements IChunkedSeq, IReduce, IDrop {
    private static final long serialVersionUID = 1L;
    private static final int CHUNK_SIZE = 32;

    private final Number start;
    private final Number end;
    private final Number step;
    private final boolean positiveStep;
    private volatile ArrayChunk chunk;
    private volatile ISeq chunkNext;

    private Range(PersistentHashMap meta, Number start, Number end, Number step, boolean positiveStep) {
        super(meta);
        this.start = start;
        this.end = end;
        this.step = step;
        this.positiveStep = positiveStep;
    }

    public static ISeq create(Number start, Number end, Number step) {
        if (Numbers.isZero(step)) {
            if (Numbers.equal(start, end)) return PersistentList.EMPTY;
            return Repeat.create(start);
        }
        boolean pos = Numbers.isPos(step);
        if ((pos && Numbers.gte(start, end)) || (!pos && Numbers.lte(start, end))) {
            return PersistentList.EMPTY;
        }
        if (start instanceof Long ls && end instanceof Long le && step instanceof Long lst) {
            ISeq lr = LongRange.createOrNull(ls, le, lst);
            if (lr != null) return lr;
        }
        return new Range(null, start, end, step, pos);
    }

    public static ISeq create(Number end) {
        return create(0L, end, 1L);
    }

    public static ISeq create(Number start, Number end) {
        return create(start, end, 1L);
    }

    private boolean inBounds(Number n) {
        return positiveStep ? Numbers.lt(n, end) : Numbers.gt(n, end);
    }

    private void forceChunk() {
        if (chunk != null) return;
        synchronized (this) {
            if (chunk != null) return;
            Object[] arr = new Object[CHUNK_SIZE];
            Number n = start;
            int i = 0;
            while (i < CHUNK_SIZE && inBounds(n)) {
                arr[i++] = n;
                n = Numbers.addP(n, step);
            }
            if (i < CHUNK_SIZE) {
                Object[] trimmed = new Object[i];
                System.arraycopy(arr, 0, trimmed, 0, i);
                chunk = new ArrayChunk(trimmed, 0, i);
                chunkNext = null;
            } else {
                chunk = new ArrayChunk(arr, 0, CHUNK_SIZE);
                chunkNext = inBounds(n) ? new Range(null, n, end, step, positiveStep) : null;
            }
        }
    }

    @Override
    public Object first() {
        return start;
    }

    @Override
    public ISeq next() {
        Number nxt = Numbers.addP(start, step);
        return inBounds(nxt) ? new Range(null, nxt, end, step, positiveStep) : null;
    }

    @Override
    public IChunk chunkedFirst() {
        forceChunk();
        return chunk;
    }

    @Override
    public ISeq chunkedNext() {
        forceChunk();
        return chunkNext;
    }

    @Override
    public ISeq chunkedMore() {
        ISeq n = chunkedNext();
        return n == null ? PersistentList.EMPTY : n;
    }

    @Override
    public Object reduce(Fn f) {
        Object acc = start;
        Number n = Numbers.addP(start, step);
        while (inBounds(n)) {
            acc = f.invoke(acc, n);
            if (acc instanceof Reduced r) return r.deref();
            n = Numbers.addP(n, step);
        }
        return acc;
    }

    @Override
    public Object reduce(Fn f, Object init) {
        Object acc = init;
        Number n = start;
        while (inBounds(n)) {
            acc = f.invoke(acc, n);
            if (acc instanceof Reduced r) return r.deref();
            n = Numbers.addP(n, step);
        }
        return acc;
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0) return this;
        Number cur = start;
        for (int i = 0; i < n; i++) {
            cur = Numbers.addP(cur, step);
            if (!inBounds(cur)) return null;
        }
        return new Range(null, cur, end, step, positiveStep);
    }

    @Override
    public Range withMeta(PersistentHashMap m) {
        if (this.meta == m) return this;
        return new Range(m, start, end, step, positiveStep);
    }
}
