package cljl.lang;

/**
 * Base interface for Clojure persistent collections (clojure.lang.IPersistentCollection).
 */
public interface IPersistentCollection extends Seqable {
    int count();
    IPersistentCollection cons(Object o);
    IPersistentCollection empty();
    default boolean equiv(Object o) {
        return this.equals(o);
    }
}
