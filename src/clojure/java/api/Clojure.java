package clojure.java.api;

import cljl.*;
import clojure.lang.IFn;

/**
 * Clean-room implementation of Clojure's official Java embedding API (clojure.java.api.Clojure).
 * Allows Java applications to look up and invoke Clojol vars and read EDN/Clojure data
 * using the exact standard Clojure Java API.
 */
public final class Clojure {

    private Clojure() {}

    private static Symbol asSym(Object o) {
        if (o instanceof Symbol s) return s;
        if (o instanceof String str) return Symbol.intern(str);
        throw new IllegalArgumentException("Expected Symbol or String, got: " + o);
    }

    public static IFn var(Object qualifiedName) {
        RT.ensureInit();
        Symbol s = asSym(qualifiedName);
        if (s.ns == null) {
            throw new IllegalArgumentException("Qualified symbol required: " + s);
        }
        return var(s.ns, s.name);
    }

    public static IFn var(Object ns, Object name) {
        RT.ensureInit();
        Symbol nsSym = asSym(ns);
        Symbol nameSym = asSym(name);
        if ("clojure.core".equals(nsSym.name)) {
            Var v = RT.CLOJURE_NS.findInternedVar(nameSym);
            if (v != null) return v;
            return RT.CLOJURE_NS.intern(nameSym);
        }
        Namespace target = Namespace.find(nsSym);
        if (target == null) {
            StdLib.ensureLoaded(nsSym.name);
            target = Namespace.findOrCreate(nsSym);
        }
        Var v = target.findInternedVar(nameSym);
        return v != null ? v : target.intern(nameSym);
    }

    public static Object read(String s) {
        RT.ensureInit();
        return Reader.readString(s);
    }
}
