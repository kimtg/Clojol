package cljl.lang;

/**
 * O(1)/direct drop protocol for lazy/computed sequences (clojure.lang.IDrop).
 */
public interface IDrop {
    Sequential drop(int n);
}
