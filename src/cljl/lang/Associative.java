package cljl.lang;

public interface Associative extends IPersistentCollection, ILookup, Counted {
    boolean containsKey(Object key);
    Associative assoc(Object key, Object val);
    default IMapEntry entryAt(Object key) {
        return containsKey(key) ? new PersistentHashMap.MapEntry(key, valAt(key)) : null;
    }
}
