package cljl.lang;

/**
 * Mutable single-value box (Clojure clojure.lang.Box equivalent).
 */
public final class Box extends clojure.lang.Box {
    public Box(Object val) {
        super(val);
    }
}
