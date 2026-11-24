package clojure.lang;

import java.util.Comparator;

/**
 * Real clojure.lang.PersistentTreeMap base class extended by cljl.lang.PersistentTreeMap.
 */
public abstract class PersistentTreeMap extends APersistentMap implements IObj, Sorted, Reversible, IKVReduce {
    public static final cljl.lang.PersistentTreeMap EMPTY = cljl.lang.PersistentTreeMap.emptyInstance();

    protected PersistentTreeMap() {}

    public static cljl.lang.PersistentTreeMap create(ISeq items) {
        cljl.lang.PersistentTreeMap ret = EMPTY;
        for (ISeq s = items; s != null; s = s.next()) {
            Object k = s.first();
            s = s.next();
            if (s == null) throw new IllegalArgumentException("No value supplied for key: " + k);
            ret = ret.assoc(k, s.first());
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    public static cljl.lang.PersistentTreeMap create(Comparator<?> comp, ISeq items) {
        cljl.lang.PersistentTreeMap ret = new cljl.lang.PersistentTreeMap(null, (Comparator<Object>) comp, null, 0);
        for (ISeq s = items; s != null; s = s.next()) {
            Object k = s.first();
            s = s.next();
            if (s == null) throw new IllegalArgumentException("No value supplied for key: " + k);
            ret = ret.assoc(k, s.first());
        }
        return ret;
    }
}
