package clojure.lang;

import java.util.Map;

/**
 * Real clojure.lang.PersistentArrayMap base class extended by cljl.lang.PersistentArrayMap.
 */
public abstract class PersistentArrayMap extends APersistentMap implements IObj, IEditableCollection, IKVReduce {
    public static final cljl.lang.PersistentArrayMap EMPTY = cljl.lang.PersistentArrayMap.emptyInstance();

    protected PersistentArrayMap() {}

    public static IPersistentMap create(Object... init) {
        return cljl.lang.PersistentArrayMap.create(init);
    }

    public static IPersistentMap createWithCheck(Object[] init) {
        return cljl.lang.PersistentArrayMap.createWithCheck(init);
    }

    public static IPersistentMap createAsIfByAssoc(Object[] init) {
        return cljl.lang.PersistentArrayMap.createAsIfByAssoc(init);
    }

    public static IPersistentMap create(Map<?, ?> other) {
        return cljl.lang.PersistentHashMap.createFromMap(other);
    }
}
