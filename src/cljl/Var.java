package cljl;

import cljl.lang.IDeref;
import cljl.lang.IMeta;
import cljl.lang.IRef;
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
public final class Var extends Fn implements IRef, IMeta, IReference {
    public static final class Unbound extends Fn {
        public final Var v;

        public Unbound(Var v) {
            this.v = v;
        }

        @Override public String toString() { return "Unbound: " + v; }
        @Override public Object invoke() { throw new IllegalStateException("Attempting to call unbound fn: " + v); }
        @Override public Object invoke(Object a) { throw new IllegalStateException("Attempting to call unbound fn: " + v); }
        @Override public Object invoke(Object a, Object b) { throw new IllegalStateException("Attempting to call unbound fn: " + v); }
        @Override public Object invoke(Object a, Object b, Object c) { throw new IllegalStateException("Attempting to call unbound fn: " + v); }
        @Override public Object invoke(Object a, Object b, Object c, Object d) { throw new IllegalStateException("Attempting to call unbound fn: " + v); }
        @Override public Object invokeVarargs(Object[] args) { throw new IllegalStateException("Attempting to call unbound fn: " + v); }
    }

    public static final Object UNBOUND = new Object() {
        @Override public String toString() { return "#<Unbound>"; }
    };
    private static final Object SLOW_SENTINEL = new Object();

    private static final ThreadLocal<Deque<Map<Var, Object>>> BINDING_STACK = new ThreadLocal<>();

    private static final Keyword NS_KW = Keyword.intern("ns");
    private static final Keyword NAME_KW = Keyword.intern("name");
    private static final Keyword DYNAMIC_KW = Keyword.intern("dynamic");
    private static final Keyword MACRO_KW = Keyword.intern("macro");

    public final Namespace ns;
    public final Symbol sym;
    private volatile Object root;
    // Equals root when !dynamic && root != UNBOUND; SLOW_SENTINEL otherwise (single-load fast path).
    private volatile Object fastRoot;
    private volatile boolean dynamic;
    private volatile boolean macro;
    private volatile PersistentHashMap meta;
    private volatile Fn validator;
    private volatile ConcurrentHashMap<Object, Fn> watches;
    private volatile Unbound unbound;
    private volatile Object initialCoreRoot = UNBOUND;
    private volatile boolean redefinedCore = false;

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
        if (root != UNBOUND && isIntrinsicCoreVar()) {
            this.initialCoreRoot = root;
        }
        this.fastRoot = (root == UNBOUND) ? SLOW_SENTINEL : root;
        this.meta = PersistentHashMap.EMPTY;
    }

    private Unbound getUnbound() {
        Unbound u = this.unbound;
        if (u == null) {
            u = new Unbound(this);
            this.unbound = u;
        }
        return u;
    }

    private void refreshFastRoot() {
        Object r = this.root;
        this.fastRoot = (this.dynamic || r == UNBOUND) ? SLOW_SENTINEL : r;
    }

    private void validate(Object val) {
        Fn v = this.validator;
        if (v != null && !RT.truthy(v.invoke(val))) {
            throw new IllegalStateException("Invalid reference state");
        }
    }

    @Override
    public Fn getValidator() {
        return validator;
    }

    @Override
    public void setValidator(Fn vf) {
        if (vf != null && this.root != UNBOUND) {
            if (!RT.truthy(vf.invoke(this.root))) {
                throw new IllegalStateException("Invalid reference state");
            }
        }
        this.validator = vf;
    }

    public static void pushThreadBindings(Map<Var, Object> bindings) {
        for (Map.Entry<Var, Object> e : bindings.entrySet()) {
            Var v = e.getKey();
            if (!v.isDynamic()) {
                throw new IllegalStateException("Can't dynamically bind non-dynamic var: " + v);
            }
            v.validate(e.getValue());
        }
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            BINDING_STACK.set(stack);
        }
        Map<Var, Object> merged = stack.isEmpty() ? new HashMap<>() : new HashMap<>(stack.peek());
        merged.putAll(bindings);
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

    public static Object getThreadBindingFrame() {
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        if (stack == null || stack.isEmpty()) return Collections.emptyList();
        return new ArrayDeque<>(stack);
    }

    public static Object cloneThreadBindingFrame() {
        Deque<Map<Var, Object>> stack = BINDING_STACK.get();
        if (stack == null || stack.isEmpty()) return Collections.emptyList();
        ArrayDeque<Map<Var, Object>> copy = new ArrayDeque<>(stack.size());
        for (Map<Var, Object> frame : stack) {
            copy.addLast(new HashMap<>(frame));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static void resetThreadBindingFrame(Object frame) {
        if (frame == null) {
            BINDING_STACK.remove();
            return;
        }
        if (frame instanceof java.util.Collection<?> c) {
            if (c.isEmpty()) {
                BINDING_STACK.remove();
                return;
            }
            ArrayDeque<Map<Var, Object>> stack = new ArrayDeque<>(c.size());
            for (Object item : c) {
                stack.addLast(new HashMap<>((Map<Var, Object>) item));
            }
            BINDING_STACK.set(stack);
            return;
        }
        if (frame instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                BINDING_STACK.remove();
                return;
            }
            Deque<Map<Var, Object>> stack = new ArrayDeque<>();
            stack.push(new HashMap<>((Map<Var, Object>) m));
            BINDING_STACK.set(stack);
        }
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
        return root == UNBOUND ? getUnbound() : root;
    }

    public Object getRawRoot() {
        return root;
    }

    public boolean hasRoot() {
        return root != UNBOUND;
    }

    public boolean isPublic() {
        PersistentHashMap m = this.meta;
        return m == null || !RT.truthy(m.valAt(Keyword.intern("private")));
    }

    public Symbol toSymbol() {
        return ns != null && sym != null ? Symbol.intern(ns.name.name, sym.name) : sym;
    }

    @Override
    public Object deref() {
        Object v = fastRoot;
        return v != SLOW_SENTINEL ? v : derefSlow();
    }

    private Object derefSlow() {
        return getSlow();
    }

    public boolean isBound() {
        if (root != UNBOUND) return true;
        if (dynamic) {
            Deque<Map<Var, Object>> stack = BINDING_STACK.get();
            return stack != null && !stack.isEmpty() && stack.peek().containsKey(this);
        }
        return false;
    }

    private boolean isIntrinsicCoreVar() {
        return ns == RT.CLOJURE_NS && sym != null && CompilerIntrinsics.isIntrinsicName(sym.name);
    }

    private synchronized void updateCoreRedefStatus() {
        if (!isIntrinsicCoreVar()) return;
        if (this.initialCoreRoot == UNBOUND) {
            if (this.root != UNBOUND) {
                this.initialCoreRoot = this.root;
            }
            return;
        }
        boolean nowRedefined = this.dynamic || this.root != this.initialCoreRoot;
        if (nowRedefined != this.redefinedCore) {
            this.redefinedCore = nowRedefined;
            Core.adjustCoreRedefined(nowRedefined ? 1 : -1);
        }
    }

    public Var bindRoot(Object val) {
        validate(val);
        Object old = this.root;
        this.root = val;
        refreshFastRoot();
        updateCoreRedefStatus();
        notifyWatches(old == UNBOUND ? getUnbound() : old, val);
        return this;
    }

    public synchronized void unbindRoot() {
        this.root = UNBOUND;
        refreshFastRoot();
        updateCoreRedefStatus();
    }

    public Object set(Object val) {
        if (dynamic) {
            Deque<Map<Var, Object>> stack = BINDING_STACK.get();
            if (stack != null && !stack.isEmpty() && stack.peek().containsKey(this)) {
                validate(val);
                stack.peek().put(this, val);
                return val;
            }
        }
        throw new IllegalStateException("Can't change/restore root binding of " + getName() + " without binding");
    }

    public synchronized Object alterRoot(Fn fn, Object[] args) {
        Object old = this.root;
        Object oldExposed = old == UNBOUND ? getUnbound() : old;
        Object[] allArgs = new Object[(args == null ? 0 : args.length) + 1];
        allArgs[0] = oldExposed;
        if (args != null && args.length > 0) {
            System.arraycopy(args, 0, allArgs, 1, args.length);
        }
        Object newVal = fn.applyTo(allArgs);
        validate(newVal);
        this.root = newVal;
        refreshFastRoot();
        updateCoreRedefStatus();
        notifyWatches(oldExposed, newVal);
        return newVal;
    }

    public Var setDynamic() {
        return setDynamic(true);
    }

    public Var setDynamic(boolean b) {
        this.dynamic = b;
        PersistentHashMap m = this.meta;
        if (b) {
            this.meta = (m != null ? m : PersistentHashMap.EMPTY).assoc(DYNAMIC_KW, Boolean.TRUE);
        } else if (m != null && m.containsKey(DYNAMIC_KW)) {
            this.meta = m.without(DYNAMIC_KW);
        }
        refreshFastRoot();
        updateCoreRedefStatus();
        return this;
    }

    public boolean isDynamic() {
        return dynamic;
    }

    public Var setMacro() {
        this.macro = true;
        this.meta = this.meta.assoc(MACRO_KW, Boolean.TRUE);
        return this;
    }

    public boolean isMacro() {
        return macro;
    }

    @Override
    public PersistentHashMap meta() {
        PersistentHashMap m = this.meta;
        if (this.ns != null && this.sym != null && (!m.containsKey(NS_KW) || !m.containsKey(NAME_KW))) {
            if (!m.containsKey(NS_KW)) m = m.assoc(NS_KW, this.ns);
            if (!m.containsKey(NAME_KW)) m = m.assoc(NAME_KW, this.sym);
            this.meta = m;
        }
        return m;
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
        this.dynamic = RT.truthy(this.meta.valAt(DYNAMIC_KW));
        this.macro = RT.truthy(this.meta.valAt(MACRO_KW));
        refreshFastRoot();
        updateCoreRedefStatus();
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
        callArgs[0] = meta();
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        return resetMeta(PersistentHashMap.fromMap(alter.applyTo(callArgs)));
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
