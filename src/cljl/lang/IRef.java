package cljl.lang;

import cljl.Fn;

/**
 * Watchable, derefable reference interface (Clojure clojure.lang.IRef).
 */
public interface IRef extends IDeref {
    default void setValidator(Fn vf) { throw new UnsupportedOperationException(); }
    default Fn getValidator() { return null; }
    default PersistentHashMap getWatches() { return PersistentHashMap.EMPTY; }
    default void addWatch(Object key, Fn callback) { throw new UnsupportedOperationException(); }
    default void removeWatch(Object key) { throw new UnsupportedOperationException(); }
}
