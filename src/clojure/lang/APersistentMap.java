package clojure.lang;

/**
 * Real clojure.lang.APersistentMap base class extended by PersistentHashMap, PersistentArrayMap,
 * PersistentTreeMap, and PersistentStructMap.
 */
public abstract class APersistentMap extends cljl.Fn implements IPersistentMap, MapEquivalence, IHashEq {
    protected APersistentMap() {}
}
