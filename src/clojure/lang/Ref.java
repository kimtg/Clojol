package clojure.lang;

/**
 * Real clojure.lang.Ref base class extended by cljl.Ref.
 */
public abstract class Ref extends cljl.Fn implements IRef, IReference {
    protected Ref() {}
}
