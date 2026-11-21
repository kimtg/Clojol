package cljl.lang;

import cljl.Fn;

/**
 * Primitive long[]-backed IChunk used by chunked map/filter over LongRange and long chunks.
 * Eliminates Long.valueOf allocations during chunked filter/map + reduce pipelines while
 * preserving exact lazy-seq memoization semantics when elements are later accessed via seq/first/nth.
 */
public final class LongArrayChunk implements IChunk {
    public final long[] array;
    public final int off;
    public final int end;

    public LongArrayChunk(long[] array, int off, int end) {
        this.array = array;
        this.off = off;
        this.end = end;
    }

    @Override
    public int count() {
        return end - off;
    }

    @Override
    public Object nth(int i) {
        return Long.valueOf(array[off + i]);
    }

    @Override
    public Object nth(int i, Object notFound) {
        int idx = off + i;
        return (i >= 0 && idx < end) ? Long.valueOf(array[idx]) : notFound;
    }

    @Override
    public IChunk dropFirst() {
        if (off >= end) throw new IllegalStateException("dropFirst of empty chunk");
        return new LongArrayChunk(array, off + 1, end);
    }

    @Override
    public Object reduce(Fn f, Object start) {
        Object ret = reduceRaw(f, start);
        return ret instanceof Reduced r ? r.deref() : ret;
    }

    public Object reduceRaw(Fn f, Object start) {
        if (f.isUnboxedLong2() && start instanceof Long l) {
            long acc = l.longValue();
            for (int i = off; i < end; i++) {
                acc = f.invokePrim_JJ_J(acc, array[i]);
            }
            return Long.valueOf(acc);
        }
        Object acc = start;
        for (int i = off; i < end; i++) {
            acc = f.invokePrim_OJ_O(acc, array[i]);
            if (acc instanceof Reduced) return acc;
        }
        return acc;
    }
}
