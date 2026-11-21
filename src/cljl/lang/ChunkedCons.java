package cljl.lang;

import cljl.Core;
import cljl.Fn;
import cljl.Numbers;

public final class ChunkedCons extends ASeq implements IChunkedSeq, IReduceInit {
    private final IChunk chunk;
    private final ISeq _more;

    public ChunkedCons(IChunk chunk, ISeq more) {
        super(null);
        this.chunk = chunk;
        this._more = more;
    }

    public ChunkedCons(PersistentHashMap meta, IChunk chunk, ISeq more) {
        super(meta);
        this.chunk = chunk;
        this._more = more;
    }

    public Object reduce2(Fn f) {
        if (f instanceof cljl.Compiler.CompiledFn cf) {
            f = cf.ensureJit();
        }
        if (chunk instanceof LongArrayChunk lac) {
            int off = lac.off;
            int end = lac.end;
            if (f.isUnboxedLong2()) {
                long acc = lac.array[off];
                for (int i = off + 1; i < end; i++) {
                    acc = f.invokePrim_JJ_J(acc, lac.array[i]);
                }
                ISeq nxt = chunkedNext();
                if (nxt == null) return Long.valueOf(acc);
                if (nxt instanceof IReduceInit ri) return ri.reduce(f, Long.valueOf(acc));
                return Core.reduce3(f, Long.valueOf(acc), nxt);
            }
            Object acc = Long.valueOf(lac.array[off]);
            for (int i = off + 1; i < end; i++) {
                acc = f.invokePrim_OJ_O(acc, lac.array[i]);
                if (acc instanceof Reduced r) return r.deref();
            }
            ISeq nxt = chunkedNext();
            if (nxt == null) return acc;
            if (nxt instanceof IReduceInit ri) return ri.reduce(f, acc);
            return Core.reduce3(f, acc, nxt);
        }
        Object acc = chunk.nth(0);
        int cnt = chunk.count();
        if (chunk instanceof ArrayChunk ac && ac.array instanceof Object[] arr) {
            int off = ac.off;
            for (int i = 1; i < cnt; i++) {
                acc = f.invoke(acc, arr[off + i]);
                if (acc instanceof Reduced r) return r.deref();
            }
        } else {
            for (int i = 1; i < cnt; i++) {
                acc = f.invoke(acc, chunk.nth(i));
                if (acc instanceof Reduced r) return r.deref();
            }
        }
        ISeq nxt = chunkedNext();
        if (nxt == null) return acc;
        if (nxt instanceof IReduceInit ri) return ri.reduce(f, acc);
        return Core.reduce3(f, acc, nxt);
    }

    @Override
    public Object reduce(Fn f, Object init) {
        if (f instanceof cljl.Compiler.CompiledFn cf) {
            f = cf.ensureJit();
        }
        Object acc = init;
        ISeq cur = this;
        if (f.isUnboxedLong2() && acc instanceof Long l) {
            long sum = l.longValue();
            boolean boxed = false;
            boolean isAdd = (f == Core.addFn);
            while (cur != null) {
                if (cur instanceof ChunkedCons cc) {
                    IChunk ch = cc.chunk;
                    if (!boxed && ch instanceof LongArrayChunk lac) {
                        long[] arr = lac.array;
                        int end = lac.end;
                        if (isAdd) {
                            for (int i = lac.off; i < end; i++) {
                                sum = Math.addExact(sum, arr[i]);
                            }
                        } else {
                            for (int i = lac.off; i < end; i++) {
                                sum = f.invokePrim_JJ_J(sum, arr[i]);
                            }
                        }
                        cur = cc.chunkedNext();
                        continue;
                    }
                    if (ch instanceof ArrayChunk ac && ac.array instanceof Object[] arr) {
                        int i = ac.off;
                        int end = ac.end;
                        if (!boxed) {
                            for (; i < end; i++) {
                                Object x = arr[i];
                                if (x instanceof Long lx) {
                                    sum = isAdd ? Math.addExact(sum, lx.longValue())
                                                : f.invokePrim_JJ_J(sum, lx.longValue());
                                    continue;
                                }
                                acc = f.invoke(Long.valueOf(sum), x);
                                if (acc instanceof Reduced r) return r.deref();
                                boxed = true;
                                i++;
                                break;
                            }
                        }
                        if (boxed) {
                            for (; i < end; i++) {
                                acc = f.invoke(acc, arr[i]);
                                if (acc instanceof Reduced r) return r.deref();
                            }
                        }
                        cur = cc.chunkedNext();
                        continue;
                    }
                }
                if (!boxed) {
                    acc = Long.valueOf(sum);
                    boxed = true;
                }
                if (cur instanceof IReduceInit ri && !(cur instanceof ChunkedCons)) {
                    return ri.reduce(f, acc);
                }
                acc = f.invoke(acc, cur.first());
                if (acc instanceof Reduced r) return r.deref();
                cur = cur.next();
            }
            return boxed ? acc : Long.valueOf(sum);
        }
        while (cur != null) {
            if (cur instanceof ChunkedCons cc) {
                IChunk ch = cc.chunk;
                if (ch instanceof LongArrayChunk lac) {
                    acc = lac.reduceRaw(f, acc);
                    if (acc instanceof Reduced r) return r.deref();
                } else if (ch instanceof ArrayChunk ac) {
                    acc = ac.reduceRaw(f, acc);
                    if (acc instanceof Reduced r) return r.deref();
                } else {
                    int n = ch.count();
                    for (int i = 0; i < n; i++) {
                        acc = f.invoke(acc, ch.nth(i));
                        if (acc instanceof Reduced r) return r.deref();
                    }
                }
                cur = cc.chunkedNext();
            } else if (cur instanceof IReduceInit ri) {
                return ri.reduce(f, acc);
            } else {
                acc = f.invoke(acc, cur.first());
                if (acc instanceof Reduced r) return r.deref();
                cur = cur.next();
            }
        }
        return acc;
    }

    @Override
    public Object first() {
        return chunk.nth(0);
    }

    @Override
    public ISeq next() {
        if (chunk.count() > 1) {
            return new ChunkedCons(chunk.dropFirst(), _more);
        }
        return chunkedNext();
    }

    @Override
    public IChunk chunkedFirst() {
        return chunk;
    }

    @Override
    public ISeq chunkedNext() {
        return _more != null ? _more.seq() : null;
    }

    @Override
    public ISeq chunkedMore() {
        return _more == null ? PersistentList.EMPTY : _more;
    }

    @Override
    public ChunkedCons withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new ChunkedCons(meta, chunk, _more);
    }
}
