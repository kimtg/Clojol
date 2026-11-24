package clojure.lang;

import java.util.Comparator;

/**
 * Real clojure.lang.PersistentTreeSet base class extended by cljl.lang.PersistentTreeSet.
 */
public abstract class PersistentTreeSet extends APersistentSet implements IObj, Sorted, Reversible {
    public static final cljl.lang.PersistentTreeSet EMPTY = cljl.lang.PersistentTreeSet.emptyInstance();

    protected PersistentTreeSet() {}

    public static cljl.lang.PersistentTreeSet create(ISeq items) {
        cljl.lang.PersistentTreeSet ret = EMPTY;
        for (ISeq s = items; s != null; s = s.next()) {
            ret = ret.conj(s.first());
        }
        return ret;
    }

    @SuppressWarnings("unchecked")
    public static cljl.lang.PersistentTreeSet create(Comparator<?> comp, ISeq items) {
        cljl.lang.PersistentTreeSet ret = new cljl.lang.PersistentTreeSet(null, new cljl.lang.PersistentTreeMap(null, (Comparator<Object>) comp, null, 0));
        for (ISeq s = items; s != null; s = s.next()) {
            ret = ret.conj(s.first());
        }
        return ret;
    }
}
