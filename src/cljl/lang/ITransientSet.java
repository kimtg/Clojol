package cljl.lang;

/**
 * Transient set interface (Clojure clojure.lang.ITransientSet).
 */
public interface ITransientSet extends ITransientCollection, Counted {
    ITransientSet disjoin(Object key);
    boolean contains(Object key);
    Object get(Object key);
    @Override
    IPersistentSet persistent();
}
