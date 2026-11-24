package cljl.lang;

public interface IPersistentSet extends clojure.lang.IPersistentSet, IPersistentCollection, Counted, Seqable {
    @Override
    IPersistentSet disjoin(Object key);
    @Override
    boolean contains(Object key);
    @Override
    Object get(Object key);
}
