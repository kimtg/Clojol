package clojure.lang;

public interface IPersistentSet extends IPersistentCollection, Counted {
    IPersistentSet disjoin(Object key);
    boolean contains(Object key);
    Object get(Object key);
}
