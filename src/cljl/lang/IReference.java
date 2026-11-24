package cljl.lang;

import cljl.Fn;
import cljl.RT;

/**
 * Interface for mutable references carrying metadata (Vars, Atoms, Refs, Agents, Namespaces).
 */
public interface IReference extends clojure.lang.IReference, IMeta {
    @Override
    PersistentHashMap meta();
    PersistentHashMap resetMeta(PersistentHashMap m);
    PersistentHashMap alterMeta(Fn alter, ISeq args);

    @Override
    default clojure.lang.IPersistentMap resetMeta(clojure.lang.IPersistentMap m) {
        return resetMeta(PersistentHashMap.fromMap(m));
    }

    @Override
    default clojure.lang.IPersistentMap alterMeta(clojure.lang.IFn alter, clojure.lang.ISeq args) {
        return alterMeta(Fn.fromIFn(alter), RT.seq(args));
    }
}
