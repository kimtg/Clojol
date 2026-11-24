package clojure.lang;

public interface ITransientSet extends ITransientCollection, Counted {
    ITransientSet disjoin(Object key);
    boolean contains(Object key);
    Object get(Object key);
}
