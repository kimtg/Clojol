package clojure.lang;

import java.util.Collection;
import java.util.List;

/**
 * Real clojure.lang.PersistentVector base class extended by cljl.lang.PersistentVector.
 */
public abstract class PersistentVector extends APersistentVector implements IObj, IEditableCollection, IReduce, IKVReduce {
    public static final cljl.lang.PersistentVector EMPTY = cljl.lang.PersistentVector.emptyInstance();

    protected PersistentVector() {}

    public static cljl.lang.PersistentVector create(Object... items) {
        if (items == null || items.length == 0) return EMPTY;
        return cljl.lang.PersistentVector.createOwning(items.clone());
    }

    public static cljl.lang.PersistentVector create(List<?> list) {
        return cljl.lang.PersistentVector.create((Collection<?>) list);
    }

    public static cljl.lang.PersistentVector create(Iterable<?> items) {
        if (items == null) return EMPTY;
        if (items instanceof Collection<?> c) return cljl.lang.PersistentVector.create(c);
        cljl.lang.PersistentVector.TransientVector tv = EMPTY.asTransient();
        for (Object item : items) tv = tv.conj(item);
        return tv.persistent();
    }

    public static cljl.lang.PersistentVector create(ISeq items) {
        if (items == null) return EMPTY;
        cljl.lang.PersistentVector.TransientVector tv = EMPTY.asTransient();
        for (ISeq s = items; s != null; s = s.next()) tv = tv.conj(s.first());
        return tv.persistent();
    }

    public static cljl.lang.PersistentVector adopt(Object[] items) {
        return cljl.lang.PersistentVector.createOwning(items);
    }
}
