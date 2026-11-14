package cljl;

import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clojure multimethod supporting arbitrary dispatch functions and hierarchy/isa? matching.
 */
public final class MultiFn extends Fn {
    public final String name;
    public final Fn dispatchFn;
    public final Object defaultDispatchVal;
    private final ConcurrentHashMap<Object, Fn> methodTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Object> preferTable = new ConcurrentHashMap<>();
    private volatile PersistentHashMap cachedMethodTable = PersistentHashMap.EMPTY;

    public MultiFn(String name, Fn dispatchFn, Object defaultDispatchVal) {
        this.name = name;
        this.dispatchFn = dispatchFn;
        this.defaultDispatchVal = defaultDispatchVal;
    }

    public synchronized MultiFn addMethod(Object dispatchVal, Fn method) {
        methodTable.put(dispatchVal == null ? NULL_KEY : dispatchVal, method);
        cachedMethodTable = cachedMethodTable.assoc(dispatchVal, method);
        return this;
    }

    public synchronized MultiFn removeMethod(Object dispatchVal) {
        methodTable.remove(dispatchVal == null ? NULL_KEY : dispatchVal);
        cachedMethodTable = cachedMethodTable.without(dispatchVal);
        return this;
    }

    public synchronized MultiFn removeAllMethods() {
        methodTable.clear();
        cachedMethodTable = PersistentHashMap.EMPTY;
        return this;
    }

    @SuppressWarnings("unchecked")
    public synchronized MultiFn preferMethod(Object dispatchValX, Object dispatchValY) {
        Object xKey = dispatchValX == null ? NULL_KEY : dispatchValX;
        Object yKey = dispatchValY == null ? NULL_KEY : dispatchValY;
        cljl.lang.PersistentHashSet prefs = (cljl.lang.PersistentHashSet) preferTable.get(xKey);
        if (prefs == null) prefs = cljl.lang.PersistentHashSet.EMPTY;
        preferTable.put(xKey, prefs.conj(yKey));
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
        Object xKey = x == null ? NULL_KEY : x;
        Object yKey = y == null ? NULL_KEY : y;
        Object prefs = preferTable.get(xKey);
        if (prefs instanceof cljl.lang.PersistentHashSet s && s.contains(yKey)) return true;
        return false;
    }

    public PersistentHashMap getMethodTable() {
        return cachedMethodTable;
    }

    public Fn getMethod(Object dispatchVal) {
        Object key = dispatchVal == null ? NULL_KEY : dispatchVal;
        Fn m = methodTable.get(key);
        if (m != null) return m;
        // Collect all isa?-matching entries and pick most specific / preferred
        Map.Entry<Object, Fn> best = null;
        for (Map.Entry<Object, Fn> e : methodTable.entrySet()) {
            Object k = e.getKey() == NULL_KEY ? null : e.getKey();
            if (k != null && !RT.equiv(k, defaultDispatchVal) && Core.isa(dispatchVal, k)) {
                if (best == null) {
                    best = e;
                } else {
                    Object bk = best.getKey() == NULL_KEY ? null : best.getKey();
                    if (isPreferred(k, bk) || (!isPreferred(bk, k) && Core.isa(k, bk))) {
                        best = e;
                    }
                }
            }
        }
        if (best != null) return best.getValue();
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
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "#<MultiFn " + name + ">";
    }

    private static final Object NULL_KEY = new Object();
}
