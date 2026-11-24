package clojure.lang;

import java.util.List;

/**
 * Real clojure.lang.PersistentHashSet base class extended by cljl.lang.PersistentHashSet.
 */
public abstract class PersistentHashSet extends APersistentSet implements IObj, IEditableCollection {
    public static final cljl.lang.PersistentHashSet EMPTY = cljl.lang.PersistentHashSet.emptyInstance();

    protected PersistentHashSet() {}

    public static cljl.lang.PersistentHashSet create(Object... init) {
        return cljl.lang.PersistentHashSet.create(init);
    }

    public static cljl.lang.PersistentHashSet create(List<?> init) {
        if (init == null || init.isEmpty()) return EMPTY;
        cljl.lang.PersistentHashSet.TransientHashSet ts = EMPTY.asTransient();
        for (Object item : init) ts = ts.conj(item);
        return ts.persistent();
    }

    public static cljl.lang.PersistentHashSet create(ISeq items) {
        if (items == null) return EMPTY;
        cljl.lang.PersistentHashSet.TransientHashSet ts = EMPTY.asTransient();
        for (ISeq s = items; s != null; s = s.next()) ts = ts.conj(s.first());
        return ts.persistent();
    }

    public static cljl.lang.PersistentHashSet createWithCheck(Object... init) {
        return cljl.lang.PersistentHashSet.createWithCheck(init);
    }

    public static cljl.lang.PersistentHashSet createWithCheck(List<?> init) {
        return cljl.lang.PersistentHashSet.createWithCheck(init != null ? init.toArray() : new Object[0]);
    }

    public static cljl.lang.PersistentHashSet createWithCheck(ISeq items) {
        return cljl.lang.PersistentHashSet.createWithCheck(cljl.RT.toArray(items));
    }
}
