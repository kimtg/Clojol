package cljl.lang;

/**
 * Mutable single-value box (Clojure clojure.lang.Box equivalent).
 */
public final class Box {
    public Object val;

    public Box(Object val) {
        this.val = val;
    }
}
