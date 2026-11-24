package clojure.lang;

public interface ITransientAssociative2 extends ITransientAssociative {
    boolean containsKey(Object key);
    IMapEntry entryAt(Object key);
}
