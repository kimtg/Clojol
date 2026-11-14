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
    // Special key for nil dispatch
    private static final Class<?> NIL_CLASS = Void.class;

    public Protocol(Symbol name, String doc, Class<?> onInterface) {
        this.name = name;
        this.doc = doc;
        this.onInterface = onInterface;
    }

    public void addImpl(Class<?> type, Map<Keyword, Fn> methods) {
        Class<?> key = (type == null) ? NIL_CLASS : type;
        ConcurrentHashMap<Keyword, Fn> map = impls.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        map.putAll(methods);
    }

    public boolean satisfies(Object x) {
        if (x == null) return impls.containsKey(NIL_CLASS);
        if (x instanceof Dispatchable d && d.__satisfies(this)) return true;
        Class<?> c = x.getClass();
        if (onInterface != null && onInterface.isAssignableFrom(c)) return true;
        return findImplMap(c) != null;
    }

    public boolean extendsType(Class<?> c) {
        if (c == null) return impls.containsKey(NIL_CLASS);
        if (onInterface != null && onInterface.isAssignableFrom(c)) return true;
        return findImplMap(c) != null;
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
        // Walk superclass chain, then interfaces, then Object
        for (Class<?> sup = c.getSuperclass(); sup != null; sup = sup.getSuperclass()) {
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

    public Fn resolveMethod(Keyword methodKey, Object target) {
        if (target instanceof Dispatchable d) {
            Fn df = d.__protocolFn(this, methodKey);
            if (df != null) return df;
        }
        Class<?> cls = (target == null) ? null : target.getClass();
        ConcurrentHashMap<Keyword, Fn> m = findImplMap(cls);
        if (m != null) {
            Fn f = m.get(methodKey);
            if (f != null) return f;
        }
        if (cls != null && impls.containsKey(Object.class)) {
            Fn f = impls.get(Object.class).get(methodKey);
            if (f != null) return f;
        }
        throw new IllegalArgumentException(
                "No implementation of method: " + methodKey + " of protocol: " + name
                        + " found for class: " + (cls == null ? "nil" : cls.getName()));
    }

    @Override
    public String toString() {
        return "#<Protocol " + name + ">";
    }
}
