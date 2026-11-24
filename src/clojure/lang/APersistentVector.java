package clojure.lang;

/**
 * Real clojure.lang.APersistentVector base class extended by PersistentVector, SubVec, Vec, and MapEntry.
 */
public abstract class APersistentVector extends cljl.Fn implements IPersistentVector, IHashEq {
    protected APersistentVector() {}
}
