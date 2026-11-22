package cljl.lang;

/**
 * Transient vector interface (Clojure clojure.lang.ITransientVector).
 */
public interface ITransientVector extends ITransientAssociative, Indexed {
    ITransientVector assocN(int i, Object val);
    ITransientVector pop();
    @Override
    IPersistentVector persistent();
}
