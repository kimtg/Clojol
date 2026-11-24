package cljl.lang;

import java.util.Map;

public interface IPersistentMap extends clojure.lang.IPersistentMap, Associative, ILookup, Counted, Seqable, MapEquivalence, Iterable<Map.Entry<Object, Object>> {
    @Override
    IPersistentMap assoc(Object key, Object val);
    @Override
    default IPersistentMap assocEx(Object key, Object val) {
        if (containsKey(key)) {
            throw new RuntimeException("Key already present");
        }
        return assoc(key, val);
    }
    @Override
    IPersistentMap without(Object key);
}
