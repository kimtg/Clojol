package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.io.Serializable;
import java.util.*;

/**
 * Persistent FIFO queue (Okasaki amortized O(1) conj/peek/pop).
 * Matches clojure.lang.PersistentQueue semantics, including sequential equality
 * and pop on empty returning EMPTY.
 */
public final class PersistentQueue extends AbstractList<Object>
        implements IPersistentStack, PersistentColl, Seqable, Counted, IObj, IReduceInit, Sequential, IHashEq, Serializable {

    private static final long serialVersionUID = 1L;
    public static final PersistentQueue EMPTY = new PersistentQueue(null, 0, null, null);

    private final PersistentHashMap meta;
    private final int cnt;
    private final ISeq f;
    private final PersistentVector r;
    private int cachedHash;
    private volatile boolean hasHash;
    private int cachedHasheq;
    private volatile boolean hasHasheq;

    private PersistentQueue(PersistentHashMap meta, int cnt, ISeq f, PersistentVector r) {
        this.meta = meta;
        this.cnt = cnt;
        this.f = f;
        this.r = r;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentQueue withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new PersistentQueue(meta, cnt, f, r);
    }

    @Override
    public int count() {
        return cnt;
    }

    @Override
    public int size() {
        return cnt;
    }

    @Override
    public boolean isEmpty() {
        return cnt == 0;
    }

    @Override
    public PersistentQueue empty() {
        return meta == null ? EMPTY : EMPTY.withMeta(meta);
    }

    @Override
    public PersistentQueue cons(Object val) {
        if (f == null) {
            return new PersistentQueue(meta, 1, new PersistentList(val), null);
        }
        PersistentVector newR = (r == null ? PersistentVector.EMPTY : r).conj(val);
        return new PersistentQueue(meta, cnt + 1, f, newR);
    }

    @Override
    public Object without(Object key) {
        throw new UnsupportedOperationException("without not supported on PersistentQueue");
    }

    @Override
    public Object peek() {
        return f == null ? null : f.first();
    }

    @Override
    public PersistentQueue pop() {
        if (f == null) return this;
        ISeq f1 = f.next();
        if (f1 == null) {
            if (r == null) return empty();
            return new PersistentQueue(meta, cnt - 1, r.seq(), null);
        }
        return new PersistentQueue(meta, cnt - 1, f1, r);
    }

    @Override
    public ISeq seq() {
        if (f == null) return null;
        return new QueueSeq(null, f, r != null ? r.seq() : null);
    }

    @Override
    public Object get(int index) {
        if (index < 0 || index >= cnt) throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + cnt);
        ISeq s = f;
        int i = 0;
        while (s != null) {
            if (i == index) return s.first();
            s = s.next();
            i++;
        }
        if (r != null) {
            return r.nth(index - i);
        }
        throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + cnt);
    }

    @Override
    public Object reduce(Fn fn, Object init) {
        Object acc = init;
        for (ISeq s = f; s != null; s = s.next()) {
            acc = fn.invoke(acc, s.first());
            if (acc instanceof Reduced red) return red.deref();
        }
        if (r != null) {
            return r.reduce(fn, acc);
        }
        return acc;
    }

    @Override
    public int hasheq() {
        if (!hasHasheq) {
            int h = Murmur3.hashOrdered(this);
            cachedHasheq = h;
            hasHasheq = true;
            return h;
        }
        return cachedHasheq;
    }

    @Override
    public int hashCode() {
        if (!hasHash) {
            int h = 1;
            for (ISeq s = seq(); s != null; s = s.next()) {
                h = 31 * h + RT.hash(s.first());
            }
            cachedHash = h;
            hasHash = true;
        }
        return cachedHash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Sequential || obj instanceof List<?>)) return false;
        if (obj instanceof Map<?, ?> || obj instanceof Set<?>) return false;
        if (obj instanceof Counted c && c.count() != cnt) return false;
        ISeq ms = seq();
        ISeq os = RT.seq(obj);
        while (ms != null && os != null) {
            if (!RT.equiv(ms.first(), os.first())) return false;
            ms = ms.next();
            os = os.next();
        }
        return ms == null && os == null;
    }

    private static final class QueueSeq extends ASeq {
        private final ISeq f;
        private final ISeq rseq;

        QueueSeq(PersistentHashMap meta, ISeq f, ISeq rseq) {
            super(meta);
            this.f = f;
            this.rseq = rseq;
        }

        @Override
        public Object first() {
            return f.first();
        }

        @Override
        public ISeq next() {
            ISeq f1 = f.next();
            if (f1 != null) return new QueueSeq(null, f1, rseq);
            return rseq;
        }

        @Override
        public QueueSeq withMeta(PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new QueueSeq(meta, f, rseq);
        }
    }
}
