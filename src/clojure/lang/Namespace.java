package clojure.lang;

import java.util.Collection;
import java.util.Map;

/**
 * Real clojure.lang.Namespace base class extended by cljl.Namespace.
 */
public abstract class Namespace implements IReference {
    protected Namespace() {}

    public static cljl.Namespace findOrCreate(Symbol name) {
        return cljl.Namespace.findOrCreate((cljl.Symbol) name);
    }

    public static cljl.Namespace find(Symbol name) {
        return cljl.Namespace.find((cljl.Symbol) name);
    }

    public static cljl.Namespace remove(Symbol name) {
        return cljl.Namespace.remove((cljl.Symbol) name);
    }

    public static ISeq allSeq() {
        return cljl.lang.PersistentList.create(new java.util.ArrayList<>(cljl.Namespace.all()));
    }

    public abstract Symbol getName();
    public abstract Map<cljl.Symbol, Object> getMappings();
    public abstract Map<cljl.Symbol, cljl.Namespace> getAliases();
}
