package clojure.lang;

import java.io.Serializable;

/**
 * Real clojure.lang.Keyword base class extended by cljl.Keyword.
 */
public abstract class Keyword extends cljl.Fn implements Named, IHashEq, Serializable {
    private static final long serialVersionUID = 1L;

    protected Keyword() {}

    public static cljl.Keyword intern(Symbol sym) {
        return cljl.Keyword.intern((cljl.Symbol) sym);
    }

    public static cljl.Keyword intern(String ns, String name) {
        return cljl.Keyword.intern(ns, name);
    }

    public static cljl.Keyword intern(String nsname) {
        return cljl.Keyword.intern(nsname);
    }

    public static cljl.Keyword find(Symbol sym) {
        if (sym == null) return null;
        return cljl.Keyword.find(sym.getNamespace(), sym.getName());
    }

    public static cljl.Keyword find(String ns, String name) {
        return cljl.Keyword.find(ns, name);
    }

    public static cljl.Keyword find(String nsname) {
        if (nsname == null) return null;
        int slash = nsname.indexOf('/');
        if (slash > 0 && slash < nsname.length() - 1) {
            return cljl.Keyword.find(nsname.substring(0, slash), nsname.substring(slash + 1));
        }
        return cljl.Keyword.find(null, nsname);
    }
}
