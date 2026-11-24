package cljl.lang;

/**
 * Transient set interface (Clojure clojure.lang.ITransientSet).
 */
public interface ITransientSet extends clojure.lang.ITransientSet, ITransientCollection, Counted {
    @Override
    ITransientSet disjoin(Object key);
    @Override
    boolean contains(Object key);
    @Override
    Object get(Object key);
    @Override
    IPersistentSet persistent();
}
