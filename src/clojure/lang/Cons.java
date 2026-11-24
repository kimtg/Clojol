package clojure.lang;

/**
 * Real clojure.lang.Cons base class extended by cljl.lang.Cons.
 */
public abstract class Cons extends cljl.lang.ASeq {
    private static final long serialVersionUID = 1L;

    protected Cons(cljl.lang.PersistentHashMap meta) {
        super(meta);
    }
}
