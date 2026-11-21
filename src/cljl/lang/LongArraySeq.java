package cljl.lang;

import cljl.Fn;

/**
 * Primitive long[]-backed persistent sequence returned by FastSort for homogeneous Long sorts.
 * Avoids eager Long.valueOf re-boxing while providing O(1) count, chunked seq, and unboxed IReduceInit.
 */
public final class LongArraySeq extends ASeq implements IChunkedSeq, Counted, IReduceInit {
    private static final int CHUNK_SIZE = 32;

    public final long[] array;
    public final int index;

    public LongArraySeq(long[] array, int index) {
        super(null);
        this.array = array;
        this.index = index;
    }

    private LongArraySeq(PersistentHashMap meta, long[] array, int index) {
        super(meta);
        this.array = array;
        this.index = index;
    }

    public static ISeq create(long[] arr) {
        if (arr == null || arr.length == 0) return PersistentList.EMPTY;
        return new LongArraySeq(arr, 0);
    }

    @Override
    public int count() {
        return array.length - index;
    }

    @Override
    public Object first() {
        return Long.valueOf(array[index]);
    }

    @Override
    public ISeq next() {
        int nextIdx = index + 1;
        return nextIdx < array.length ? new LongArraySeq(array, nextIdx) : null;
    }

    @Override
    public IChunk chunkedFirst() {
        int rem = array.length - index;
        int len = Math.min(CHUNK_SIZE, rem);
        Object[] chunk = new Object[len];
        for (int i = 0; i < len; i++) {
            chunk[i] = Long.valueOf(array[index + i]);
        }
        return new ArrayChunk(chunk, 0, len);
    }

    @Override
    public ISeq chunkedNext() {
        int nextIdx = index + CHUNK_SIZE;
        return nextIdx < array.length ? new LongArraySeq(array, nextIdx) : null;
    }

    @Override
    public ISeq chunkedMore() {
        ISeq n = chunkedNext();
        return n == null ? PersistentList.EMPTY : n;
    }

    @Override
    public Object reduce(Fn f, Object init) {
        long[] arr = this.array;
        int len = arr.length;
        if (f.isUnboxedLong2() && init instanceof Long l) {
            long acc = l.longValue();
            for (int i = index; i < len; i++) {
                acc = f.invokePrim_JJ_J(acc, arr[i]);
            }
            return Long.valueOf(acc);
        }
        Object acc = init;
        for (int i = index; i < len; i++) {
            acc = f.invokePrim_OJ_O(acc, arr[i]);
            if (acc instanceof Reduced r) return r.deref();
        }
        return acc;
    }

    public Object[] toBoxedArray() {
        int n = array.length - index;
        Object[] out = new Object[n];
        for (int i = 0; i < n; i++) out[i] = Long.valueOf(array[index + i]);
        return out;
    }

    @Override
    public LongArraySeq withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new LongArraySeq(meta, array, index);
    }
}
