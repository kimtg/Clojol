package clojure.lang;

/**
 * Real clojure.lang.LazySeq base class extended by cljl.lang.LazySeq.
 */
public abstract class LazySeq extends cljl.lang.ASeq implements IReduceInit, IPending {
    private static final long serialVersionUID = 1L;

    protected LazySeq(cljl.lang.PersistentHashMap meta) {
        super(meta);
    }
}
