package cljl.lang;

/**
 * Transient map interface (Clojure clojure.lang.ITransientMap).
 */
public interface ITransientMap extends clojure.lang.ITransientMap, ITransientAssociative, Counted {
    @Override
    ITransientMap assoc(Object key, Object val);
    @Override
    ITransientMap without(Object key);
    @Override
    IPersistentMap persistent();
}
