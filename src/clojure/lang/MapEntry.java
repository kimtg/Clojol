package clojure.lang;

/**
 * Real clojure.lang.MapEntry base class extended by cljl.lang.PersistentHashMap.MapEntry.
 */
public abstract class MapEntry extends APersistentVector implements IMapEntry {
    protected MapEntry() {}

    public static cljl.lang.PersistentHashMap.MapEntry create(Object key, Object val) {
        return new cljl.lang.PersistentHashMap.MapEntry(key, val);
    }
}
