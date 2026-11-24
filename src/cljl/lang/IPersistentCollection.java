package cljl.lang;

/**
 * Base interface for Clojure persistent collections (clojure.lang.IPersistentCollection).
 */
public interface IPersistentCollection extends clojure.lang.IPersistentCollection, Seqable {
    @Override
    int count();
    @Override
    IPersistentCollection cons(Object o);
    @Override
    IPersistentCollection empty();
    @Override
    default boolean equiv(Object o) {
        return this.equals(o);
    }
}
