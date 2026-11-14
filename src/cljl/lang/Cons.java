package cljl.lang;

/**
 * Immutable cons cell prepending an element onto an {@link ISeq}.
 * Unlike {@link PersistentList}, {@code Cons} does not implement {@link Counted}
 * so lazily produced sequences retain accurate O(n) counting semantics.
 */
public final class Cons extends ASeq {
    private final Object first;
    private final ISeq _more;

    public Cons(Object first, ISeq more) {
        super(null);
        this.first = first;
        this._more = more;
    }

    public Cons(PersistentHashMap meta, Object first, ISeq more) {
        super(meta);
        this.first = first;
        this._more = more;
    }

    @Override
    public Object first() {
        return first;
    }

    @Override
    public ISeq next() {
        return _more != null ? _more.seq() : null;
    }

    @Override
    public ISeq more() {
        return _more == null ? PersistentList.EMPTY : _more;
    }

    @Override
    public int count() {
        int i = 1;
        for (ISeq s = next(); s != null; s = s.next(), i++) {
            if (s instanceof Counted c) {
                return i + c.count();
            }
        }
        return i;
    }

    @Override
    public Cons withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new Cons(meta, first, _more);
    }
}
