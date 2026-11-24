package clojure.lang;

/**
 * Real clojure.lang.Delay base class extended by cljl.Atom.Delay.
 */
public abstract class Delay implements IDeref, IPending {
    protected Delay() {}

    public static Object force(Object x) {
        return (x instanceof Delay d) ? d.deref() : x;
    }
}
