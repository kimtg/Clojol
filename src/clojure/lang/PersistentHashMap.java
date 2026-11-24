package clojure.lang;

import java.util.Map;

/**
 * Real clojure.lang.PersistentHashMap base class extended by cljl.lang.PersistentHashMap.
 */
public abstract class PersistentHashMap extends APersistentMap implements IObj, IEditableCollection, IKVReduce {
    public static final cljl.lang.PersistentHashMap EMPTY = cljl.lang.PersistentHashMap.emptyInstance();

    protected PersistentHashMap() {}

    public static cljl.lang.PersistentHashMap create(Object... init) {
        return cljl.lang.PersistentHashMap.create(init);
    }

    public static cljl.lang.PersistentHashMap create(Map<?, ?> other) {
        return cljl.lang.PersistentHashMap.createFromMap(other);
    }

    public static cljl.lang.PersistentHashMap create(ISeq items) {
        cljl.lang.PersistentHashMap.TransientHashMap t = EMPTY.asTransient();
        for (ISeq s = items; s != null; s = s.next()) {
            Object k = s.first();
            s = s.next();
            if (s == null) throw new IllegalArgumentException("No value supplied for key: " + k);
            t = t.assoc(k, s.first());
        }
        return t.persistent();
    }

    public static cljl.lang.PersistentHashMap createWithCheck(Object... init) {
        return cljl.lang.PersistentHashMap.createWithCheck(init);
    }

    public static cljl.lang.PersistentHashMap createWithCheck(ISeq items) {
        return cljl.lang.PersistentHashMap.createWithCheck(cljl.RT.toArray(items));
    }

    public static cljl.lang.PersistentHashMap createAsIfByAssoc(Object... init) {
        return cljl.lang.PersistentHashMap.create(init);
    }
}
