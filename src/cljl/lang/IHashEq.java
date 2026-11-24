package cljl.lang;

/**
 * Interface for Clojure value-equality hash codes (Clojure clojure.lang.IHashEq).
 */
public interface IHashEq extends clojure.lang.IHashEq {
    @Override
    int hasheq();
}
