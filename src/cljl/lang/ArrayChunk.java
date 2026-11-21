package cljl.lang;

import cljl.Fn;
import java.lang.reflect.Array;

public final class ArrayChunk implements IChunk {
    public final Object array;
    public final int off;
    public final int end;
    private final boolean isObjArray;

    public ArrayChunk(Object[] array) {
        this(array, 0, array.length);
    }

    public ArrayChunk(Object[] array, int off, int end) {
        this.array = array;
        this.off = off;
        this.end = end;
        this.isObjArray = true;
    }

    public ArrayChunk(Object array, int off, int end) {
        this.array = array;
        this.off = off;
        this.end = end;
        this.isObjArray = array instanceof Object[];
    }

    @Override
    public int count() {
        return Math.max(0, end - off);
    }

    @Override
    public Object nth(int i) {
        int idx = off + i;
        if (idx < off || idx >= end) {
            throw new IndexOutOfBoundsException("Index " + i + " out of bounds for chunk length " + count());
        }
        return isObjArray ? ((Object[]) array)[idx] : Array.get(array, idx);
    }

    @Override
    public Object nth(int i, Object notFound) {
        int idx = off + i;
        if (idx < off || idx >= end) return notFound;
        return isObjArray ? ((Object[]) array)[idx] : Array.get(array, idx);
    }

    @Override
    public IChunk dropFirst() {
        if (off >= end) throw new IllegalStateException("dropFirst of empty chunk");
        return new ArrayChunk(array, off + 1, end);
    }

    @Override
    public Object reduce(Fn f, Object start) {
        Object acc = reduceRaw(f, start);
        return (acc instanceof Reduced r) ? r.deref() : acc;
    }

    public Object reduceRaw(Fn f, Object start) {
        Object acc = start;
        if (isObjArray) {
            Object[] arr = (Object[]) array;
            for (int i = off; i < end; i++) {
                acc = f.invoke(acc, arr[i]);
                if (acc instanceof Reduced) return acc;
            }
            return acc;
        }
        for (int i = off; i < end; i++) {
            acc = f.invoke(acc, Array.get(array, i));
            if (acc instanceof Reduced) return acc;
        }
        return acc;
    }
}
