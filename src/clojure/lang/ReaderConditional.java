package clojure.lang;

/**
 * Real clojure.lang.ReaderConditional base class extended by cljl.lang.ReaderConditional.
 */
public abstract class ReaderConditional implements ILookup {
    protected ReaderConditional() {}

    public static cljl.lang.ReaderConditional create(Object form, boolean splicing) {
        return cljl.lang.ReaderConditional.create(form, splicing);
    }
}
