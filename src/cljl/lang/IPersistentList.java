package cljl.lang;

/**
 * Marker interface for persistent lists (both non-empty and empty) supporting O(1) cons and count.
 */
public interface IPersistentList extends ISeq, IPersistentStack, Sequential, Counted {
    @Override
    PersistentList cons(Object o);
}
