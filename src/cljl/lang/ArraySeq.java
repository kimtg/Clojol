package cljl.lang;

import cljl.Fn;

/**
 * O(1) array-backed persistent sequence implementing IChunkedSeq, Counted, and IReduceInit.
 * Used by sort and array seqs to avoid allocating N PersistentList nodes.
 */
public final class ArraySeq extends ASeq implements IChunkedSeq, Counted, IReduceInit {
    private static final int CHUNK_SIZE = 32;
    public final Object[] array;
    public final int i;

    public ArraySeq(Object[] array) {
        this(null, array, 0);
    }

    public ArraySeq(PersistentHashMap meta, Object[] array, int i) {
        super(meta);
        this.array = array;
        this.i = i;
    }

    public static ISeq create(Object[] array) {
        if (array == null || array.length == 0) return PersistentList.EMPTY;
        return new ArraySeq(null, array, 0);
    }

    @Override
    public Object first() {
        return array[i];
    }

    @Override
    public ISeq next() {
        return (i + 1 < array.length) ? new ArraySeq(null, array, i + 1) : null;
    }

    @Override
    public int count() {
        return array.length - i;
    }

    @Override
    public IChunk chunkedFirst() {
        int end = Math.min(array.length, i + CHUNK_SIZE);
        return new ArrayChunk(array, i, end);
    }

    @Override
    public ISeq chunkedNext() {
        int nextI = i + CHUNK_SIZE;
        return nextI < array.length ? new ArraySeq(null, array, nextI) : null;
    }

    @Override
    public ISeq chunkedMore() {
        ISeq n = chunkedNext();
        return n == null ? PersistentList.EMPTY : n;
    }

    @Override
    public Object reduce(Fn f, Object init) {
        Object acc = init;
        for (int idx = i; idx < array.length; idx++) {
            acc = f.invoke(acc, array[idx]);
            if (acc instanceof Reduced r) return r.deref();
        }
        return acc;
    }

    @Override
    public ArraySeq withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new ArraySeq(meta, array, i);
    }
}
