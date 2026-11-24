package cljl.lang;

import cljl.Fn;

/**
 * Watchable, derefable reference interface (Clojure clojure.lang.IRef).
 */
public interface IRef extends clojure.lang.IRef, IDeref {
    default void setValidator(Fn vf) { throw new UnsupportedOperationException(); }
    @Override
    default Fn getValidator() { return null; }
    @Override
    default PersistentHashMap getWatches() { return PersistentHashMap.EMPTY; }
    default IRef addWatch(Object key, Fn callback) { throw new UnsupportedOperationException(); }
    @Override
    default IRef removeWatch(Object key) { throw new UnsupportedOperationException(); }

    @Override
    default void setValidator(clojure.lang.IFn vf) {
        setValidator(Fn.fromIFn(vf));
    }

    @Override
    default clojure.lang.IRef addWatch(Object key, clojure.lang.IFn callback) {
        return addWatch(key, Fn.fromIFn(callback));
    }
}
