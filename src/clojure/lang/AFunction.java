package clojure.lang;

import java.util.Comparator;

/**
 * Clean-room clojure.lang.AFunction base class for compiled Clojure functions.
 */
public abstract class AFunction extends cljl.Fn implements IObj, Comparator<Object>, Fn {
    public volatile MethodImplCache __methodImplCache;

    public static final class MethodImplCache {
        public final Symbol sym;
        public final Protocol protocol;
        public final Keyword methodk;
        public MethodImplCache(Symbol sym, Protocol protocol, Keyword methodk) {
            this.sym = sym;
            this.protocol = protocol;
            this.methodk = methodk;
        }
    }

    public static final class Protocol {}

    @Override
    public IPersistentMap meta() {
        return null;
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        if (meta == null) return this;
        return new cljl.Fn.MetaFn(this, cljl.lang.PersistentHashMap.fromMap(meta));
    }
}
