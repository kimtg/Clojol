package cljl.lang;

import cljl.Core;
import cljl.Fn;
import cljl.Numbers;
import java.math.BigInteger;

/**
 * High-performance finite integer range implementing IChunkedSeq, Counted, and IReduceInit.
 * Avoids per-element LazySeq/Cons allocation during reduce, transduce, into, and chunked pipelines.
 */
public final class LongRange extends ASeq implements IChunkedSeq, Counted, IReduce, IDrop {
    private static final long serialVersionUID = 1L;
    public static final int CHUNK_SIZE = 32;

    public final long start;
    public final long end;
    public final long step;
    private final int count;
    private volatile ArrayChunk cachedChunk;

    private LongRange(PersistentHashMap meta, long start, long end, long step, int count) {
        super(meta);
        this.start = start;
        this.end = end;
        this.step = step;
        this.count = count;
    }

    /**
     * Returns a LongRange for finite non-overflowing integer ranges, PersistentList.EMPTY if empty,
     * or null if the range cannot be represented as a finite int-counted LongRange (caller falls back to Range).
     */
    public static ISeq createOrNull(long start, long end, long step) {
        if (step == 0L) {
            return start == end ? PersistentList.EMPTY : null;
        }
        if ((step > 0L && start >= end) || (step < 0L && start <= end)) {
            return PersistentList.EMPTY;
        }
        BigInteger bStart = BigInteger.valueOf(start);
        BigInteger bEnd = BigInteger.valueOf(end);
        BigInteger bStep = BigInteger.valueOf(step);
        BigInteger diff = bEnd.subtract(bStart);
        BigInteger q = diff.divide(bStep);
        if (diff.remainder(bStep).signum() != 0) {
            q = q.add(BigInteger.ONE);
        }
        if (q.signum() <= 0) return PersistentList.EMPTY;
        if (q.bitLength() > 30) return null;
        int cnt = q.intValue();
        BigInteger afterLast = bStart.add(bStep.multiply(BigInteger.valueOf(cnt)));
        if (afterLast.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                || afterLast.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0) {
            return null;
        }
        return new LongRange(null, start, end, step, cnt);
    }

    @Override
    public int count() {
        return count;
    }

    @Override
    public Object first() {
        return Long.valueOf(start);
    }

    @Override
    public ISeq next() {
        if (count <= 1) return null;
        return new LongRange(null, start + step, end, step, count - 1);
    }

    @Override
    public IChunk chunkedFirst() {
        ArrayChunk c = cachedChunk;
        if (c != null) return c;
        int len = Math.min(CHUNK_SIZE, count);
        Object[] arr = new Object[len];
        long v = start;
        for (int i = 0; i < len; i++) {
            arr[i] = Long.valueOf(v);
            v += step;
        }
        c = new ArrayChunk(arr, 0, len);
        cachedChunk = c;
        return c;
    }

    @Override
    public ISeq chunkedNext() {
        int len = Math.min(CHUNK_SIZE, count);
        int rem = count - len;
        if (rem <= 0) return null;
        return new LongRange(null, start + step * len, end, step, rem);
    }

    @Override
    public ISeq chunkedMore() {
        ISeq n = chunkedNext();
        return n == null ? PersistentList.EMPTY : n;
    }

    @Override
    public Sequential drop(int n) {
        if (n <= 0) return this;
        if (n >= count) return null;
        return new LongRange(null, start + step * (long) n, end, step, count - n);
    }

    @Override
    public Object reduce(Fn f) {
        return reduce2(f);
    }

    public Object reduce2(Fn f) {
        if (count == 1) return Long.valueOf(start);
        if (count >= 32 && f instanceof cljl.Compiler.CompiledFn cf) {
            f = cf.ensureJit();
        }
        if (f.isUnboxedLong2()) {
            long acc = start;
            long v = start + step;
            int rem = count - 1;
            for (int i = 0; i < rem; i++) {
                acc = f.invokePrim_JJ_J(acc, v);
                v += step;
            }
            return Long.valueOf(acc);
        }
        Object acc = Long.valueOf(start);
        long v = start + step;
        int rem = count - 1;
        for (int i = 0; i < rem; i++) {
            acc = f.invokePrim_OJ_O(acc, v);
            if (acc instanceof Reduced r) return r.deref();
            v += step;
        }
        return acc;
    }

    @Override
    public Object reduce(Fn f, Object init) {
        if (count >= 32 && f instanceof cljl.Compiler.CompiledFn cf) {
            f = cf.ensureJit();
        }
        if (f.isUnboxedLong2() && init instanceof Long l) {
            long acc = l.longValue();
            long v = start;
            int rem = count;
            for (int i = 0; i < rem; i++) {
                acc = f.invokePrim_JJ_J(acc, v);
                v += step;
            }
            return Long.valueOf(acc);
        }
        Object acc = init;
        long v = start;
        int rem = count;
        for (int i = 0; i < rem; i++) {
            acc = f.invokePrim_OJ_O(acc, v);
            if (acc instanceof Reduced r) return r.deref();
            v += step;
        }
        return acc;
    }

    @Override
    public LongRange withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new LongRange(meta, start, end, step, count);
    }
}
