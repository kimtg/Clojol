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

    @Override
    public Object reduce(Fn f, Object init) {
        Object acc = init;
        ISeq cur = this;
        if (f == Core.addFn && acc instanceof Long l) {
            long sum = l.longValue();
            boolean boxed = false;
            while (cur != null) {
                if (cur instanceof ChunkedCons cc && cc.chunk instanceof ArrayChunk ac && ac.array instanceof Object[] arr) {
                    int i = ac.off;
                    int end = ac.end;
                    if (!boxed) {
                        for (; i < end; i++) {
                            Object x = arr[i];
                            if (x instanceof Long lx) {
                                long lv = lx.longValue();
                                long nxt = sum + lv;
                                if (((sum ^ nxt) & (lv ^ nxt)) >= 0L) {
                                    sum = nxt;
                                    continue;
                                }
                            }
                            acc = Numbers.add(Long.valueOf(sum), x);
                            boxed = true;
                            i++;
                            break;
                        }
                    }
                    if (boxed) {
                        for (; i < end; i++) acc = Numbers.add(acc, arr[i]);
                    }
                    cur = cc.chunkedNext();
                } else {
                    if (!boxed) {
                        acc = Long.valueOf(sum);
                        boxed = true;
                    }
                    acc = Numbers.add(acc, cur.first());
                    cur = cur.next();
                }
            }
            return boxed ? acc : Long.valueOf(sum);
        }
        while (cur != null) {
            if (cur instanceof ChunkedCons cc) {
                IChunk ch = cc.chunk;
                if (ch instanceof ArrayChunk ac) {
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
