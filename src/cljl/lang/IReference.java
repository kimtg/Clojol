package cljl.lang;

import cljl.Fn;

/**
 * Interface for mutable references carrying metadata (Vars, Atoms, Refs, Agents, Namespaces).
 */
public interface IReference {
    PersistentHashMap meta();
    PersistentHashMap resetMeta(PersistentHashMap m);
    PersistentHashMap alterMeta(Fn alter, ISeq args);
}
