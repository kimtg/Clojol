package cljl.lang;

public interface Associative extends clojure.lang.Associative, IPersistentCollection, ILookup, Counted {
    @Override
    boolean containsKey(Object key);
    @Override
    Associative assoc(Object key, Object val);
    @Override
    default IMapEntry entryAt(Object key) {
        return containsKey(key) ? new PersistentHashMap.MapEntry(key, valAt(key)) : null;
    }
}
