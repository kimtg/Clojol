package cljl.lang;

import cljl.RT;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Iterator over an ISeq / Seqable (Clojure clojure.lang.SeqIterator).
 */
public final class SeqIterator implements Iterator<Object> {
    private static final Object START = new Object();
    private Object seq;
    private Object next;

    public SeqIterator(Object o) {
        this.seq = START;
        this.next = o;
    }

    @Override
    public boolean hasNext() {
        if (seq == START) {
            seq = null;
            next = RT.seq(next);
        } else if (seq == next) {
            next = RT.next(seq);
        }
        return next != null;
    }

    @Override
    public Object next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        seq = next;
        return RT.first(next);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
