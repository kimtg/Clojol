package cljl.lang;

/**
 * Transient associative collection interface (Clojure clojure.lang.ITransientAssociative).
 */
public interface ITransientAssociative extends ITransientCollection, ILookup {
    ITransientAssociative assoc(Object key, Object val);
}
