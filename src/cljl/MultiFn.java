package cljl;

import cljl.lang.IObj;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import cljl.lang.PersistentHashSet;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clojure multimethod supporting arbitrary dispatch functions and hierarchy/isa? matching.
 */
public final class MultiFn extends clojure.lang.MultiFn implements IObj {
    public final String name;
    public final Fn dispatchFn;
    public final Object defaultDispatchVal;
    public final cljl.lang.IDeref hierarchy;
    private final ConcurrentHashMap<Object, Fn> methodTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Object> preferTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Fn> methodCache = new ConcurrentHashMap<>();
    private volatile Object cachedHierarchy;
    private volatile PersistentHashMap cachedMethodTable = PersistentHashMap.EMPTY;
    private static final Keyword KW_PARENTS = Keyword.intern("parents");
    private static final Fn NO_METHOD = Fn.ofVarargs("multifn-no-method", args -> null);

    public MultiFn(String name, Fn dispatchFn, Object defaultDispatchVal) {
        this(name, dispatchFn, defaultDispatchVal, null);
    }

    public MultiFn(String name, Fn dispatchFn, Object defaultDispatchVal, cljl.lang.IDeref hierarchy) {
        this.name = name;
        this.dispatchFn = dispatchFn;
        this.defaultDispatchVal = defaultDispatchVal;
        this.hierarchy = hierarchy;
        this.cachedHierarchy = currentHierarchy();
    }

    private Object currentHierarchy() {
        return hierarchy != null ? hierarchy.deref() : CoreExtras.getGlobalHierarchy().deref();
    }

    private void resetCache() {
        methodCache.clear();
        cachedHierarchy = currentHierarchy();
    }

    private void ensureCacheFresh() {
        Object curH = currentHierarchy();
        if (curH != cachedHierarchy) {
            synchronized (this) {
                if (curH != cachedHierarchy) {
                    methodCache.clear();
                    cachedHierarchy = curH;
                }
            }
        }
    }

    private boolean isA(Object child, Object parent) {
        if (hierarchy != null) {
            return CoreExtras.isa(hierarchy.deref(), child, parent);
        }
        return Core.isa(child, parent);
    }

    public synchronized MultiFn addMethod(Object dispatchVal, Fn method) {
        methodTable.put(dispatchVal == null ? NULL_KEY : dispatchVal, method);
        cachedMethodTable = cachedMethodTable.assoc(dispatchVal, method);
        resetCache();
        return this;
    }

    public synchronized MultiFn removeMethod(Object dispatchVal) {
        methodTable.remove(dispatchVal == null ? NULL_KEY : dispatchVal);
        cachedMethodTable = cachedMethodTable.without(dispatchVal);
        resetCache();
        return this;
    }

    public synchronized MultiFn removeAllMethods() {
        methodTable.clear();
        cachedMethodTable = PersistentHashMap.EMPTY;
        resetCache();
        return this;
    }

    @SuppressWarnings("unchecked")
    public synchronized MultiFn preferMethod(Object dispatchValX, Object dispatchValY) {
        if (RT.equiv(dispatchValX, dispatchValY) || isPreferred(dispatchValY, dispatchValX)) {
            throw new IllegalStateException(
                    "Preference conflict in multimethod '" + name + "': "
                            + RT.printString(dispatchValY) + " is already preferred to " + RT.printString(dispatchValX));
        }
        Object xKey = dispatchValX == null ? NULL_KEY : dispatchValX;
        Object yKey = dispatchValY == null ? NULL_KEY : dispatchValY;
        PersistentHashSet prefs = (PersistentHashSet) preferTable.get(xKey);
        if (prefs == null) prefs = PersistentHashSet.EMPTY;
        preferTable.put(xKey, prefs.conj(yKey));
        resetCache();
        return this;
    }

    public PersistentHashMap prefers() {
        PersistentHashMap m = PersistentHashMap.EMPTY;
        for (Map.Entry<Object, Object> e : preferTable.entrySet()) {
            Object k = e.getKey() == NULL_KEY ? null : e.getKey();
            m = m.assoc(k, e.getValue());
        }
        return m;
    }

    private boolean isPreferred(Object x, Object y) {
        return isPreferred(x, y, new HashSet<>());
    }

    private boolean isPreferred(Object x, Object y, Set<Object> visited) {
        Object xKey = x == null ? NULL_KEY : x;
        if (!visited.add(xKey)) return false;
        Object yKey = y == null ? NULL_KEY : y;
        Object prefs = preferTable.get(xKey);
        if (prefs instanceof PersistentHashSet s) {
            if (s.contains(yKey)) return true;
            for (ISeq it = s.seq(); it != null; it = it.next()) {
                Object zKey = it.first();
                Object z = zKey == NULL_KEY ? null : zKey;
                if (isPreferred(z, y, visited)) return true;
            }
        }
        // Check parents of x in hierarchy (and Java supertypes if Class)
        Object h = currentHierarchy();
        if (h instanceof Map<?, ?> hm && x != null) {
            Object pmap = hm.get(KW_PARENTS);
            if (pmap != null) {
                Object ps = RT.get(pmap, x);
                for (ISeq it = RT.seq(ps); it != null; it = it.next()) {
                    if (isPreferred(it.first(), y, visited)) return true;
                }
            }
        }
        if (x instanceof Class<?> cls) {
            Class<?> sup = cls.getSuperclass();
            if (sup != null && isPreferred(sup, y, visited)) return true;
            for (Class<?> iface : cls.getInterfaces()) {
                if (isPreferred(iface, y, visited)) return true;
            }
        }
        return false;
    }

    private boolean dominates(Object x, Object y) {
        return isPreferred(x, y) || (!isPreferred(y, x) && isA(x, y));
    }

    public PersistentHashMap getMethodTable() {
        return cachedMethodTable;
    }

    public Fn getMethod(Object dispatchVal) {
        ensureCacheFresh();
        Object key = dispatchVal == null ? NULL_KEY : dispatchVal;
        Fn cached = methodCache.get(key);
        if (cached != null) {
            return cached == NO_METHOD ? null : cached;
        }
        Fn m = findBestMethod(dispatchVal, key);
        methodCache.put(key, m != null ? m : NO_METHOD);
        return m;
    }

    private Fn findBestMethod(Object dispatchVal, Object key) {
        Fn m = methodTable.get(key);
        if (m != null) return m;
        // Collect all isa?-matching entries and verify a single dominant match
        List<Map.Entry<Object, Fn>> matches = new ArrayList<>();
        for (Map.Entry<Object, Fn> e : methodTable.entrySet()) {
            Object k = e.getKey() == NULL_KEY ? null : e.getKey();
            if (k != null && !RT.equiv(k, defaultDispatchVal) && isA(dispatchVal, k)) {
                matches.add(e);
            }
        }
        if (!matches.isEmpty()) {
            Map.Entry<Object, Fn> best = matches.get(0);
            Object bestKey = best.getKey() == NULL_KEY ? null : best.getKey();
            for (int i = 1; i < matches.size(); i++) {
                Map.Entry<Object, Fn> cand = matches.get(i);
                Object candKey = cand.getKey() == NULL_KEY ? null : cand.getKey();
                if (dominates(candKey, bestKey)) {
                    best = cand;
                    bestKey = candKey;
                }
            }
            for (Map.Entry<Object, Fn> other : matches) {
                if (other == best) continue;
                Object otherKey = other.getKey() == NULL_KEY ? null : other.getKey();
                if (!dominates(bestKey, otherKey)) {
                    throw new IllegalArgumentException(
                            "Multiple methods in multimethod '" + name + "' match dispatch value: "
                                    + RT.printString(dispatchVal) + " -> "
                                    + RT.printString(bestKey) + " and " + RT.printString(otherKey)
                                    + ", and neither is preferred");
                }
            }
            return best.getValue();
        }
        return methodTable.get(defaultDispatchVal == null ? NULL_KEY : defaultDispatchVal);
    }

    private Fn findAndCheckMethod(Object dispatchVal) {
        Fn m = getMethod(dispatchVal);
        if (m == null) {
            throw new IllegalArgumentException(
                    "No method in multimethod '" + name + "' for dispatch value: " + RT.printString(dispatchVal));
        }
        return m;
    }

    @Override
    public Object invoke() {
        return findAndCheckMethod(dispatchFn.invoke()).invoke();
    }

    @Override
    public Object invoke(Object a) {
        return findAndCheckMethod(dispatchFn.invoke(a)).invoke(a);
    }

    @Override
    public Object invoke(Object a, Object b) {
        return findAndCheckMethod(dispatchFn.invoke(a, b)).invoke(a, b);
    }

    @Override
    public Object invoke(Object a, Object b, Object c) {
        return findAndCheckMethod(dispatchFn.invoke(a, b, c)).invoke(a, b, c);
    }

    @Override
    public Object invoke(Object a, Object b, Object c, Object d) {
        return findAndCheckMethod(dispatchFn.invoke(a, b, c, d)).invoke(a, b, c, d);
    }

    @Override
    public Object invokeVarargs(Object[] args) {
        return findAndCheckMethod(dispatchFn.invokeVarargs(args)).invokeVarargs(args);
    }

    @Override
    public PersistentHashMap meta() {
        return null;
    }

    @Override
    public IObj withMeta(PersistentHashMap m) {
        return m == null ? this : new MetaFn(this, m);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "#<MultiFn " + name + ">";
    }

    private static final Object NULL_KEY = new Object();
}
