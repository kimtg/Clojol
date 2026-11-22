package cljl.lang;

/**
 * Array manager interface for primitive/object vectors (Clojure clojure.core.ArrayManager).
 */
public interface ArrayManager {
    Object array(int size);
    int alength(Object arr);
    Object aclone(Object arr);
    Object aget(Object arr, int i);
    void aset(Object arr, int i, Object val);
}
