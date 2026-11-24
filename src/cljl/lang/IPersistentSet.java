package cljl.lang;

public interface IPersistentSet extends IPersistentCollection, Counted, Seqable {
    IPersistentSet disjoin(Object key);
    boolean contains(Object key);
    Object get(Object key);
}
