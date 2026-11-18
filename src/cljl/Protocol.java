package cljl;

import cljl.lang.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clojure Protocol descriptor and fast polymorphic class-based method dispatch table.
 */
public final class Protocol {
    public interface Dispatchable {
        Fn __protocolFn(Protocol p, Keyword methodKey);
        boolean __satisfies(Protocol p);
    }

    public final Symbol name;
    public final String doc;
    public final Class<?> onInterface;
    public final ConcurrentHashMap<Keyword, Fn> methodDefaults = new ConcurrentHashMap<>();
    // Target Class<?> -> (methodName Keyword -> Fn)
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<Keyword, Fn>> impls = new ConcurrentHashMap<>();
    // Cached resolved dispatch: Target Class<?> -> (methodName Keyword -> Fn or MISSING_IMPL)
    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<Keyword, Fn>> methodCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Boolean> satisfiesCache = new ConcurrentHashMap<>();
    // Special key for nil dispatch
    private static final Class<?> NIL_CLASS = Void.class;
    private static final Fn MISSING_IMPL = Fn.ofVarargs("protocol-missing", args -> {
        throw new IllegalStateException("Protocol missing sentinel invoked");
    });

    public Protocol(Symbol name, String doc, Class<?> onInterface) {
        this.name = name;
        this.doc = doc;
        this.onInterface = onInterface;
    }

    public void addImpl(Class<?> type, Map<Keyword, Fn> methods) {
        Class<?> key = (type == null) ? NIL_CLASS : type;
        ConcurrentHashMap<Keyword, Fn> map = impls.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        map.putAll(methods);
        methodCache.clear();
        satisfiesCache.clear();
    }

    public boolean satisfies(Object x) {
        if (x == null) return impls.containsKey(NIL_CLASS);
        if (x instanceof Dispatchable d && d.__satisfies(this)) return true;
        return extendsType(x.getClass());
    }

    public boolean extendsType(Class<?> c) {
        if (c == null) return impls.containsKey(NIL_CLASS);
        Boolean cached = satisfiesCache.get(c);
        if (cached != null) return cached;
        boolean res = (onInterface != null && onInterface.isAssignableFrom(c)) || findImplMap(c) != null;
        satisfiesCache.putIfAbsent(c, res);
        return res;
    }

    public Collection<Class<?>> extenders() {
        List<Class<?>> out = new ArrayList<>();
        for (Class<?> c : impls.keySet()) {
            out.add(c == NIL_CLASS ? null : c);
        }
        return out;
    }

    private ConcurrentHashMap<Keyword, Fn> findImplMap(Class<?> c) {
        if (c == null) return impls.get(NIL_CLASS);
        ConcurrentHashMap<Keyword, Fn> direct = impls.get(c);
        if (direct != null) return direct;
        // Walk superclass chain (excluding Object), then interfaces, then Object
        for (Class<?> sup = c.getSuperclass(); sup != null && sup != Object.class; sup = sup.getSuperclass()) {
            ConcurrentHashMap<Keyword, Fn> m = impls.get(sup);
            if (m != null) return m;
        }
        for (Class<?> iface : collectInterfaces(c)) {
            ConcurrentHashMap<Keyword, Fn> m = impls.get(iface);
            if (m != null) return m;
        }
        return impls.get(Object.class);
    }

    private static List<Class<?>> collectInterfaces(Class<?> c) {
        List<Class<?>> result = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> q = new ArrayDeque<>();
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            for (Class<?> i : cur.getInterfaces()) {
                if (seen.add(i)) q.add(i);
            }
        }
        while (!q.isEmpty()) {
            Class<?> i = q.poll();
            result.add(i);
            for (Class<?> sup : i.getInterfaces()) {
                if (seen.add(sup)) q.add(sup);
            }
        }
        return result;
    }

    private Fn resolveUncached(Class<?> cls, Keyword methodKey) {
        if (cls == NIL_CLASS) {
            ConcurrentHashMap<Keyword, Fn> nilMap = impls.get(NIL_CLASS);
            if (nilMap != null) {
                Fn f = nilMap.get(methodKey);
                if (f != null) return f;
            }
            return methodDefaults.get(methodKey);
        }
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            ConcurrentHashMap<Keyword, Fn> m = impls.get(cur);
            if (m != null) {
                Fn f = m.get(methodKey);
                if (f != null) return f;
            }
        }
        for (Class<?> iface : collectInterfaces(cls)) {
            ConcurrentHashMap<Keyword, Fn> m = impls.get(iface);
            if (m != null) {
                Fn f = m.get(methodKey);
                if (f != null) return f;
            }
        }
        ConcurrentHashMap<Keyword, Fn> objMap = impls.get(Object.class);
        if (objMap != null) {
            Fn f = objMap.get(methodKey);
            if (f != null) return f;
        }
        return methodDefaults.get(methodKey);
    }

    public Fn resolveMethod(Keyword methodKey, Object target) {
        if (target instanceof Dispatchable d) {
            Fn df = d.__protocolFn(this, methodKey);
            if (df != null) return df;
        }
        Class<?> cls = (target == null) ? NIL_CLASS : target.getClass();
        ConcurrentHashMap<Keyword, Fn> cmap = methodCache.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
        Fn f = cmap.get(methodKey);
        if (f == null) {
            Fn resolved = resolveUncached(cls, methodKey);
            f = (resolved != null) ? resolved : MISSING_IMPL;
            cmap.putIfAbsent(methodKey, f);
        }
        if (f != MISSING_IMPL) return f;
        throw new IllegalArgumentException(
                "No implementation of method: " + methodKey + " of protocol: " + name
                        + " found for class: " + (cls == NIL_CLASS ? "nil" : cls.getName()));
    }

    public Fn resolveMethodOrNull(Keyword methodKey, Object target) {
        if (target instanceof Dispatchable d) {
            Fn df = d.__protocolFn(this, methodKey);
            if (df != null) return df;
        }
        Class<?> cls = (target == null) ? NIL_CLASS : target.getClass();
        ConcurrentHashMap<Keyword, Fn> cmap = methodCache.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
        Fn f = cmap.get(methodKey);
        if (f == null) {
            Fn resolved = resolveUncached(cls, methodKey);
            f = (resolved != null) ? resolved : MISSING_IMPL;
            cmap.putIfAbsent(methodKey, f);
        }
        return f != MISSING_IMPL ? f : null;
    }

    public PersistentHashMap findImplFor(Object target) {
        if (target instanceof Dispatchable d && d.__satisfies(this)) {
            return PersistentHashMap.EMPTY;
        }
        Class<?> cls = (target == null) ? NIL_CLASS : target.getClass();
        ConcurrentHashMap<Keyword, Fn> m = findImplMap(cls == NIL_CLASS ? null : cls);
        if (m == null) {
            if (cls != NIL_CLASS && onInterface != null && onInterface.isAssignableFrom(cls)) {
                return PersistentHashMap.EMPTY;
            }
            return null;
        }
        PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
        for (Map.Entry<Keyword, Fn> e : m.entrySet()) {
            tm = tm.assoc(e.getKey(), e.getValue());
        }
        return tm.persistent();
    }

    public void resetCaches() {
        methodCache.clear();
        satisfiesCache.clear();
    }

    @Override
    public String toString() {
        return "#<Protocol " + name + ">";
    }
}
