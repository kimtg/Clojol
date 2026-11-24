package clojure.lang;

import java.util.List;

/**
 * Real clojure.lang.PersistentList base class extended by cljl.lang.PersistentList.
 */
public abstract class PersistentList extends cljl.lang.ASeq implements IPersistentList {
    private static final long serialVersionUID = 1L;

    public static final cljl.lang.PersistentList.EmptyList EMPTY = cljl.lang.PersistentList.emptyInstance();

    protected PersistentList(cljl.lang.PersistentHashMap meta) {
        super(meta);
    }

    public static IPersistentList create(List<?> init) {
        return cljl.lang.PersistentList.create(init);
    }
}
