package cljl.lang;

/**
 * Transient mutable collection interface (Clojure clojure.lang.ITransientCollection).
 */
public interface ITransientCollection {
    ITransientCollection conj(Object val);
    Object persistent();
}
