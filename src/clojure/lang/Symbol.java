package clojure.lang;

import java.io.Serializable;

/**
 * Real clojure.lang.Symbol base class extended by cljl.Symbol.
 */
public abstract class Symbol extends cljl.Fn implements IObj, Named, IHashEq, Serializable {
    private static final long serialVersionUID = 1L;

    protected Symbol() {}

    public static cljl.Symbol intern(String ns, String name) {
        return cljl.Symbol.intern(ns, name);
    }

    public static cljl.Symbol intern(String nsname) {
        return cljl.Symbol.intern(nsname);
    }

    public static cljl.Symbol create(String ns, String name) {
        return cljl.Symbol.intern(ns, name);
    }

    public static cljl.Symbol create(String nsname) {
        return cljl.Symbol.intern(nsname);
    }
}
