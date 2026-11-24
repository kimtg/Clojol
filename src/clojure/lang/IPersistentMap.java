package clojure.lang;

import java.util.Map;

public interface IPersistentMap extends Iterable<Map.Entry<Object, Object>>, Associative, Counted {
    @Override
    IPersistentMap assoc(Object key, Object val);
    IPersistentMap assocEx(Object key, Object val);
    IPersistentMap without(Object key);
}
