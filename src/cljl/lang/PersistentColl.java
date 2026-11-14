package cljl.lang;

/**
 * Extension interface for persistent collections implemented outside core primitives
 * (priority maps, int maps/sets, ordered maps/sets).
 */
public interface PersistentColl {
    Object cons(Object x);
    Object without(Object key);
    Object empty();
    default Object peek() { throw new UnsupportedOperationException("peek not supported on " + getClass().getSimpleName()); }
    default Object pop() { throw new UnsupportedOperationException("pop not supported on " + getClass().getSimpleName()); }
}
