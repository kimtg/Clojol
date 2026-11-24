package cljl.lang;

/**
 * Transient vector interface (Clojure clojure.lang.ITransientVector).
 */
public interface ITransientVector extends clojure.lang.ITransientVector, ITransientAssociative, Indexed {
    @Override
    ITransientVector assocN(int i, Object val);
    @Override
    ITransientVector pop();
    @Override
    IPersistentVector persistent();
}
