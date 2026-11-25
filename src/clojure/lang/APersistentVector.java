package clojure.lang;

/**
 * Real clojure.lang.APersistentVector base class extended by PersistentVector, SubVec, Vec, and MapEntry.
 */
public abstract class APersistentVector extends cljl.Fn implements IPersistentVector, IHashEq {
    protected APersistentVector() {}

    public static final class RSeq extends cljl.lang.ASeq implements IndexedSeq, Counted {
        public final IPersistentVector v;
        public final int i;

        public RSeq(IPersistentVector v, int i) {
            this(null, v, i);
        }

        public RSeq(cljl.lang.PersistentHashMap meta, IPersistentVector v, int i) {
            super(meta);
            this.v = v;
            this.i = i;
        }

        @Override
        public int index() {
            return i;
        }

        @Override
        public Object first() {
            return v.nth(i);
        }

        @Override
        public cljl.lang.ISeq next() {
            return i > 0 ? new RSeq(v, i - 1) : null;
        }

        @Override
        public int count() {
            return i + 1;
        }

        @Override
        public RSeq withMeta(cljl.lang.PersistentHashMap meta) {
            if (this.meta == meta) return this;
            return new RSeq(meta, v, i);
        }
    }
}
