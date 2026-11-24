package cljl.lang;

/**
 * Transient associative collection interface (Clojure clojure.lang.ITransientAssociative / ITransientAssociative2).
 */
public interface ITransientAssociative extends clojure.lang.ITransientAssociative2, ITransientCollection, ILookup {
    Object NOT_FOUND = new Object();

    @Override
    ITransientAssociative assoc(Object key, Object val);

    @Override
    default boolean containsKey(Object key) {
        return valAt(key, NOT_FOUND) != NOT_FOUND;
    }

    @Override
    default IMapEntry entryAt(Object key) {
        Object v = valAt(key, NOT_FOUND);
        return v != NOT_FOUND ? new PersistentHashMap.MapEntry(key, v) : null;
    }
}
