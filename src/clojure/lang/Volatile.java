package clojure.lang;

/**
 * Real clojure.lang.Volatile base class extended by cljl.Atom.Volatile.
 */
public abstract class Volatile implements IDeref {
    protected Volatile() {}
    public abstract Object reset(Object newval);
}
