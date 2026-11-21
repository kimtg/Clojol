package cljl;

import cljl.lang.IDeref;
import cljl.lang.IMeta;
import cljl.lang.IReference;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clojure Var with ultra-fast root access (zero ThreadLocal overhead unless dynamic)
 * and thread-local dynamic binding frame stack.
 */
public final class Var extends Fn implements IDeref, IMeta, IReference {
    public static final Object UNBOUND = new Object() {
        @Override public String toString() { return "#<Unbound>"; }
    };
    private static final Object SLOW_SENTINEL = new Object();

    private static final ThreadLocal<Deque<Map<Var, Object>>> BINDING_STACK = new ThreadLocal<>();

    public final Namespace ns;
    public final Symbol sym;
    private volatile Object root;
    // Equals root when !dynamic && root != UNBOUND; SLOW_SENTINEL otherwise (single-load fast path).
    private volatile Object fastRoot;
    private volatile boolean dynamic;
    private volatile boolean macro;
    private volatile PersistentHashMap meta;
    private volatile ConcurrentHashMap<Object, Fn> watches;

    public Var(Namespace ns, Symbol sym) {
        this.ns = ns;
        this.sym = sym;
        this.root = UNBOUND;
        this.fastRoot = SLOW_SENTINEL;
        this.meta = PersistentHashMap.EMPTY;
    }

    public static Var create() {
        return new Var(null, null).setDynamic();
    }

    public static Var create(Object root) {
        return new Var(null, null, root).setDynamic();
    }

    public Var(Namespace ns, Symbol sym, Object root) {
        this.ns = ns;
        this.sym = sym;
        this.root = root;
        this.fastRoot = (root == UNBOUND) ? SLOW_SENTINEL : root;
        this.meta = PersistentHashMap.EMPTY;
    }

    private void refreshFastRoot() {
        Object r = this.root;
        this.fastRoot = (this.dynamic || r == UNBOUND) ? SLOW_SENTINEL : r;
    }

    public static void pushThreadBindings(Map<Var, Object> bindings) {
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            BINDING_STACK.set(stack);
        }
        Map<Var, Object> merged = stack.isEmpty() ? new HashMap<>() : new HashMap<>(stack.peek());
        for (Map.Entry<Var, Object> e : bindings.entrySet()) {
            e.getKey().setDynamic(true);
            merged.put(e.getKey(), e.getValue());
        }
        stack.push(merged);
    }

    public static void popThreadBindings() {
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            stack.pop();
            if (stack.isEmpty()) {
                BINDING_STACK.remove();
            }
        }
    }

    public static Map<Var, Object> getThreadBindings() {
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        if (stack == null || stack.isEmpty()) return Collections.emptyMap();
        return stack.peek();
    }

    public static Map<Var, Object> cloneThreadBindings() {
        Map<Var, Object> top = getThreadBindings();
        return top.isEmpty() ? Collections.emptyMap() : new HashMap<>(top);
    }

    public boolean isThreadBound() {
        if (!dynamic) return false;
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        return stack != null && !stack.isEmpty() && stack.peek().containsKey(this);
    }

    public Object get() {
        Object v = fastRoot;
        return v != SLOW_SENTINEL ? v : getSlow();
    }

    private Object getSlow() {
        if (dynamic) {
            Deque<Map<Var, Object>> stack = BINDING_STACK.get();
            if (stack != null && !stack.isEmpty()) {
                Map<Var, Object> top = stack.peek();
                if (top.containsKey(this)) {
                    return top.get(this);
                }
            }
        }
        return root;
    }

    public Object getRawRoot() {
        return root;
    }

    @Override
    public Object deref() {
        Object v = fastRoot;
        return v != SLOW_SENTINEL ? v : derefSlow();
    }

    private Object derefSlow() {
        Object v = getSlow();
        if (v == UNBOUND) throwUnbound();
        return v;
    }

    private void throwUnbound() {
        throw new IllegalStateException("Var " + getName() + " is unbound.");
    }

    public boolean isBound() {
        if (root != UNBOUND) return true;
        if (dynamic) {
            Deque<Map<Var, Object>> stack = BINDING_STACK.get();
            return stack != null && !stack.isEmpty() && stack.peek().containsKey(this);
        }
        return false;
    }

    private boolean isCoreArithVar() {
        if (ns != RT.CLOJURE_NS || sym == null) return false;
        return switch (sym.name) {
            case "+", "-", "*", "inc", "dec",
                 "unchecked-add", "unchecked-subtract", "unchecked-multiply",
                 "unchecked-inc", "unchecked-dec" -> true;
            default -> false;
        };
    }

    public Var bindRoot(Object val) {
        Object old = this.root;
        if (old != UNBOUND && isCoreArithVar()) {
            Core.coreRedefined = true;
        }
        this.root = val;
        refreshFastRoot();
        notifyWatches(old, val);
        return this;
    }

    public Object set(Object val) {
        if (dynamic) {
            Deque<Map<Var, Object>> stack = BINDING_STACK.get();
            if (stack != null && !stack.isEmpty() && stack.peek().containsKey(this)) {
                stack.peek().put(this, val);
                return val;
            }
        }
        throw new IllegalStateException("Can't change/restore root binding of " + getName() + " without binding");
    }

    public synchronized Object alterRoot(Fn fn, Object[] args) {
        Object old = this.root;
        Object[] allArgs = new Object[(args == null ? 0 : args.length) + 1];
        allArgs[0] = old;
        if (args != null && args.length > 0) {
            System.arraycopy(args, 0, allArgs, 1, args.length);
        }
        Object newVal = fn.applyTo(allArgs);
        this.root = newVal;
        refreshFastRoot();
        notifyWatches(old, newVal);
        return newVal;
    }

    public Var setDynamic() {
        this.dynamic = true;
        this.fastRoot = SLOW_SENTINEL;
        return this;
    }

    public Var setDynamic(boolean b) {
        if (b && !this.dynamic && isCoreArithVar()) {
            Core.coreRedefined = true;
        }
        this.dynamic = b;
        refreshFastRoot();
        return this;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public Var setMacro() {
        this.macro = true;
        this.meta = this.meta.assoc(Keyword.intern("macro"), Boolean.TRUE);
        return this;
    }

    public boolean isMacro() {
        return macro;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    public synchronized Var mergeMeta(PersistentHashMap m) {
        if (m == null || m.count() == 0) return this;
        PersistentHashMap merged = this.meta != null ? this.meta : PersistentHashMap.EMPTY;
        for (ISeq s = m.seq(); s != null; s = s.next()) {
            @SuppressWarnings("unchecked")
            Map.Entry<Object, Object> e = (Map.Entry<Object, Object>) s.first();
            merged = merged.assoc(e.getKey(), e.getValue());
        }
        return setMeta(merged);
    }

    public Var setMeta(PersistentHashMap m) {
        this.meta = m != null ? m : PersistentHashMap.EMPTY;
        if (RT.truthy(this.meta.valAt(Keyword.intern("dynamic")))) {
            this.dynamic = true;
            this.fastRoot = SLOW_SENTINEL;
        }
        if (RT.truthy(this.meta.valAt(Keyword.intern("macro")))) {
            this.macro = true;
        }
        return this;
    }

    @Override
    public PersistentHashMap resetMeta(PersistentHashMap m) {
        setMeta(m);
        return this.meta;
    }

    @Override
    public synchronized PersistentHashMap alterMeta(Fn alter, ISeq args) {
        Object[] callArgs = new Object[1 + RT.count(args)];
        callArgs[0] = this.meta;
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        return resetMeta((PersistentHashMap) alter.applyTo(callArgs));
    }

    public void addWatch(Object key, Fn callback) {
        if (watches == null) {
            synchronized (this) {
                if (watches == null) watches = new ConcurrentHashMap<>();
            }
        }
        watches.put(key, callback);
    }

    public void removeWatch(Object key) {
        if (watches != null) watches.remove(key);
    }

    private void notifyWatches(Object oldVal, Object newVal) {
        ConcurrentHashMap<Object, Fn> w = this.watches;
        if (w != null && !w.isEmpty()) {
            for (Map.Entry<Object, Fn> e : w.entrySet()) {
                e.getValue().invoke(e.getKey(), this, oldVal, newVal);
            }
        }
    }

    public Fn fn() {
        Object v = deref();
        if (v instanceof Fn f) return f;
        throw new ClassCastException(getName() + " (" + v + ") is not a function");
    }

    @Override public Object invoke() { return fn().invoke(); }
    @Override public Object invoke(Object a) { return fn().invoke(a); }
    @Override public Object invoke(Object a, Object b) { return fn().invoke(a, b); }
    @Override public Object invoke(Object a, Object b, Object c) { return fn().invoke(a, b, c); }
    @Override public Object invoke(Object a, Object b, Object c, Object d) { return fn().invoke(a, b, c, d); }
    @Override public Object invokeVarargs(Object[] args) { return fn().invokeVarargs(args); }
    @Override public Object applyTo(Object[] args) { return fn().applyTo(args); }

    @Override
    public String getName() {
        if (ns != null && sym != null) return ns.name + "/" + sym.name;
        return "--unnamed--";
    }

    @Override
    public String toString() {
        if (ns != null && sym != null) return "#'" + ns.name + "/" + sym.name;
        return "#<Var: --unnamed-->";
    }
}
