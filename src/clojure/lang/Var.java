package clojure.lang;

import java.util.HashMap;
import java.util.Map;

/**
 * Real clojure.lang.Var base class extended by cljl.Var.
 */
public abstract class Var extends cljl.Fn implements IRef, IReference, Settable {

    public static abstract class Unbound extends cljl.Fn {
        protected Unbound() {}
    }

    protected Var() {}

    public static cljl.Var intern(Namespace ns, Symbol sym) {
        return ((cljl.Namespace) ns).intern((cljl.Symbol) sym);
    }

    public static cljl.Var intern(Namespace ns, Symbol sym, Object root) {
        cljl.Var v = ((cljl.Namespace) ns).intern((cljl.Symbol) sym);
        v.bindRoot(root);
        return v;
    }

    public static cljl.Var intern(Symbol nsName, Symbol sym) {
        cljl.Namespace ns = cljl.Namespace.findOrCreate((cljl.Symbol) nsName);
        return ns.intern((cljl.Symbol) sym);
    }

    public static cljl.Var find(Symbol nsQualifiedSym) {
        if (nsQualifiedSym == null || nsQualifiedSym.getNamespace() == null) {
            throw new IllegalArgumentException("Symbol must be namespace-qualified: " + nsQualifiedSym);
        }
        cljl.Namespace ns = cljl.Namespace.find(nsQualifiedSym.getNamespace());
        if (ns == null) return null;
        return ns.findInternedVar(cljl.Symbol.intern(nsQualifiedSym.getName()));
    }

    public static cljl.Var create() {
        return cljl.Var.create();
    }

    public static cljl.Var create(Object root) {
        return cljl.Var.create(root);
    }

    public static void pushThreadBindings(Associative bindings) {
        Map<cljl.Var, Object> map = new HashMap<>();
        for (ISeq s = bindings.seq(); s != null; s = s.next()) {
            IMapEntry e = (IMapEntry) s.first();
            map.put((cljl.Var) e.key(), e.val());
        }
        cljl.Var.pushThreadBindings(map);
    }

    public static void popThreadBindings() {
        cljl.Var.popThreadBindings();
    }

    public static IPersistentMap getThreadBindingsMap() {
        return cljl.lang.PersistentHashMap.createFromMap(cljl.Var.getThreadBindings());
    }

    public static Object getThreadBindingFrame() {
        return cljl.Var.getThreadBindingFrame();
    }

    public static Object cloneThreadBindingFrame() {
        return cljl.Var.cloneThreadBindingFrame();
    }

    public static void resetThreadBindingFrame(Object frame) {
        cljl.Var.resetThreadBindingFrame(frame);
    }

    public static cljl.Var internPrivate(String nsName, String sym) {
        cljl.Namespace ns = cljl.Namespace.findOrCreate(cljl.Symbol.intern(nsName));
        cljl.Var v = ns.intern(cljl.Symbol.intern(sym));
        v.setMeta(cljl.lang.PersistentHashMap.EMPTY.assoc(cljl.Keyword.intern("private"), Boolean.TRUE));
        return v;
    }

    public abstract Object get();
    public abstract Object getRawRoot();
    public abstract boolean hasRoot();
    public abstract boolean isBound();
    public abstract boolean isDynamic();
    public abstract boolean isMacro();
    public abstract boolean isPublic();
    public abstract Object set(Object val);
    public abstract Var bindRoot(Object root);
    public abstract void unbindRoot();
    public abstract Symbol toSymbol();
}
