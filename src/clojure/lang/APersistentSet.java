package clojure.lang;

/**
 * Real clojure.lang.APersistentSet base class extended by PersistentHashSet and PersistentTreeSet.
 */
public abstract class APersistentSet extends cljl.Fn implements IPersistentSet, IHashEq {
    protected APersistentSet() {}
}
