package cljl.lang;

import java.util.Map;

public interface IPersistentMap extends Associative, ILookup, Counted, Seqable, MapEquivalence, Iterable<Map.Entry<Object, Object>> {
    @Override
    IPersistentMap assoc(Object key, Object val);
    IPersistentMap without(Object key);
}
