package clojure.lang;

/**
 * Real clojure.lang.TaggedLiteral base class extended by cljl.lang.TaggedLiteral.
 */
public abstract class TaggedLiteral implements ILookup {
    protected TaggedLiteral() {}

    public static cljl.lang.TaggedLiteral create(Symbol tag, Object form) {
        return cljl.lang.TaggedLiteral.create((cljl.Symbol) tag, form);
    }
}
