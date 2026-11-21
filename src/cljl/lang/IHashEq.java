package cljl.lang;

/**
 * Interface for Clojure value-equality hash codes (Clojure clojure.lang.IHashEq).
 */
public interface IHashEq {
    int hasheq();
}
