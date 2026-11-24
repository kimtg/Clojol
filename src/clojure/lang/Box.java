package clojure.lang;

/**
 * Real clojure.lang.Box base class extended by cljl.lang.Box.
 */
public class Box {
    public Object val;

    public Box(Object val) {
        this.val = val;
    }
}
