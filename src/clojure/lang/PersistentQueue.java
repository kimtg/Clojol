package clojure.lang;

import java.io.Serializable;
import java.util.AbstractList;

/**
 * Real clojure.lang.PersistentQueue base class extended by cljl.lang.PersistentQueue.
 */
public abstract class PersistentQueue extends AbstractList<Object>
        implements IPersistentStack, Seqable, Counted, IObj, IReduceInit, Sequential, IHashEq, Serializable {
    private static final long serialVersionUID = 1L;

    public static final cljl.lang.PersistentQueue EMPTY = cljl.lang.PersistentQueue.emptyInstance();

    protected PersistentQueue() {}
}
