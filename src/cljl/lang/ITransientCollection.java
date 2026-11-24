package cljl.lang;

/**
 * Transient mutable collection interface (Clojure clojure.lang.ITransientCollection).
 */
public interface ITransientCollection extends clojure.lang.ITransientCollection {
    @Override
    ITransientCollection conj(Object val);
    @Override
    IPersistentCollection persistent();
}
