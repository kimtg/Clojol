package cljl;

import cljl.lang.IMeta;
import cljl.lang.IReference;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class Namespace implements IMeta, IReference {
    public interface LazyLoader {
        boolean loadSymbol(Symbol sym);
        void loadAll();
    }

    private static final ConcurrentHashMap<Symbol, Namespace> NAMESPACES = new ConcurrentHashMap<>();
    private static final Keyword PRIVATE_KW = Keyword.intern("private");

    private static final Object[] DEFAULT_IMPORTS = {
            Symbol.intern("Object"), Object.class,
            Symbol.intern("String"), String.class,
            Symbol.intern("StringBuilder"), StringBuilder.class,
            Symbol.intern("Boolean"), Boolean.class,
            Symbol.intern("Byte"), Byte.class,
            Symbol.intern("Short"), Short.class,
            Symbol.intern("Integer"), Integer.class,
            Symbol.intern("Long"), Long.class,
            Symbol.intern("Float"), Float.class,
            Symbol.intern("Double"), Double.class,
            Symbol.intern("Character"), Character.class,
            Symbol.intern("Number"), Number.class,
            Symbol.intern("Math"), Math.class,
            Symbol.intern("System"), System.class,
            Symbol.intern("Thread"), Thread.class,
            Symbol.intern("Runnable"), Runnable.class,
            Symbol.intern("Class"), Class.class,
            Symbol.intern("Exception"), Exception.class,
            Symbol.intern("RuntimeException"), RuntimeException.class,
            Symbol.intern("Throwable"), Throwable.class,
            Symbol.intern("Error"), Error.class,
            Symbol.intern("IllegalArgumentException"), IllegalArgumentException.class,
            Symbol.intern("IllegalStateException"), IllegalStateException.class,
            Symbol.intern("IndexOutOfBoundsException"), IndexOutOfBoundsException.class,
            Symbol.intern("NullPointerException"), NullPointerException.class,
            Symbol.intern("UnsupportedOperationException"), UnsupportedOperationException.class,
            Symbol.intern("ArithmeticException"), ArithmeticException.class,
            Symbol.intern("Comparable"), Comparable.class,
            Symbol.intern("Iterable"), Iterable.class,
            Symbol.intern("BigInteger"), java.math.BigInteger.class,
            Symbol.intern("BigDecimal"), java.math.BigDecimal.class,
            Symbol.intern("Callable"), java.util.concurrent.Callable.class,
            Symbol.intern("Compiler"), Compiler.class
    };

    private static final class MacroAdapterFn extends Fn.NamedFn {
        private final Fn fn;

        MacroAdapterFn(String name, Fn fn) {
            super(name);
            this.fn = fn;
        }

        @Override public Object invoke() { throw arityError(0); }
        @Override public Object invoke(Object a) { throw arityError(1); }
        @Override public Object invoke(Object a, Object b) { return fn.invoke(); }
        @Override public Object invoke(Object a, Object b, Object c) { return fn.invoke(c); }
        @Override public Object invoke(Object a, Object b, Object c, Object d) { return fn.invoke(c, d); }

        @Override
        public Object invokeVarargs(Object[] args) {
            if (args.length < 2) {
                throw new IllegalArgumentException("Wrong number of args (" + args.length + ") passed to macro: " + getName());
            }
            int n = args.length - 2;
            return switch (n) {
                case 0 -> fn.invoke();
                case 1 -> fn.invoke(args[2]);
                case 2 -> fn.invoke(args[2], args[3]);
                case 3 -> fn.invoke(args[2], args[3], args[4]);
                case 4 -> fn.invoke(args[2], args[3], args[4], args[5]);
                default -> {
                    Object[] userArgs = new Object[n];
                    System.arraycopy(args, 2, userArgs, 0, n);
                    yield fn.applyTo(userArgs);
                }
            };
        }
    }

    public final Symbol name;
    private final ConcurrentHashMap<Symbol, Object> mappings;
    private final ConcurrentHashMap<Symbol, Namespace> aliases = new ConcurrentHashMap<>(16);
    private PersistentHashMap meta;
    private volatile LazyLoader lazyLoader;
    private volatile Namespace virtualReferNs;
    private volatile Set<Symbol> virtualExcluded;
    private volatile boolean virtualMaterialized;

    private Namespace(Symbol name) {
        this.name = name;
        this.meta = name.meta();
        int cap = "clojure.core".equals(name.name) ? 1024 : 64;
        this.mappings = new ConcurrentHashMap<>(cap);
        importDefaultClasses();
    }

    void setLazyLoader(LazyLoader loader) {
        this.lazyLoader = loader;
    }

    private void importDefaultClasses() {
        for (int i = 0; i < DEFAULT_IMPORTS.length; i += 2) {
            mappings.put((Symbol) DEFAULT_IMPORTS[i], DEFAULT_IMPORTS[i + 1]);
        }
    }

    public static Namespace findOrCreate(Symbol name) {
        Symbol key = name.withMeta(null);
        Namespace existing = NAMESPACES.get(key);
        if (existing != null) return existing;
        Namespace created = new Namespace(key);
        Namespace prev = NAMESPACES.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    public static Namespace findOrCreate(String name) {
        return findOrCreate(Symbol.intern(name));
    }

    public static Namespace find(Symbol name) {
        return NAMESPACES.get(name.withMeta(null));
    }

    public static Namespace find(String name) {
        return find(Symbol.intern(name));
    }

    public static Namespace remove(Symbol name) {
        if ("clojure.core".equals(name.name)) {
            throw new IllegalArgumentException("Cannot remove clojure.core namespace");
        }
        return NAMESPACES.remove(name.withMeta(null));
    }

    public static Collection<Namespace> all() {
        return NAMESPACES.values();
    }

    public Var intern(Symbol sym) {
        if (sym.ns != null) {
            throw new IllegalArgumentException("Can't intern namespace-qualified symbol: " + sym);
        }
        Symbol key = sym.withMeta(null);
        LazyLoader ll = this.lazyLoader;
        if (ll != null && !mappings.containsKey(key)) {
            ll.loadSymbol(key);
        }
        Set<Symbol> excl = this.virtualExcluded;
        if (excl != null) {
            excl.remove(key);
        }
        Var v = null;
        while (v == null) {
            Object existing = mappings.get(key);
            if (existing instanceof Var ev && ev.ns == this) {
                v = ev;
            } else if (existing == null) {
                Var created = new Var(this, key);
                Object prev = mappings.putIfAbsent(key, created);
                if (prev == null) {
                    v = created;
                } else if (prev instanceof Var pv && pv.ns == this) {
                    v = pv;
                }
            } else {
                Var created = new Var(this, key);
                if (mappings.replace(key, existing, created)) {
                    v = created;
                }
            }
        }
        if (sym.meta() != null) {
            v.mergeMeta(sym.meta());
        }
        return v;
    }

    public Var intern(String name, Object val) {
        Var v = intern(Symbol.intern(name));
        v.bindRoot(val);
        return v;
    }

    public Var internMacro(String name, Fn fn) {
        Var v = intern(Symbol.intern(name));
        // Java-defined macros take only user args; adapt them to Clojure's (&form &env & args) calling convention.
        v.bindRoot(new MacroAdapterFn(name, fn));
        v.setMacro();
        return v;
    }

    public Var refer(Symbol sym, Var var) {
        Symbol key = sym.withMeta(null);
        Set<Symbol> excl = this.virtualExcluded;
        if (excl != null) excl.remove(key);
        mappings.put(key, var);
        return var;
    }

    public void referAll(Namespace other) {
        if (other == RT.CLOJURE_NS && this != RT.CLOJURE_NS) {
            synchronized (this) {
                this.virtualReferNs = other;
                this.virtualMaterialized = false;
                this.virtualExcluded = null;
            }
            return;
        }
        for (Map.Entry<Symbol, Object> e : other.getMappings().entrySet()) {
            if (e.getValue() instanceof Var v && v.ns == other) {
                boolean isPrivate = RT.truthy(v.meta().valAt(PRIVATE_KW));
                if (!isPrivate) {
                    mappings.putIfAbsent(e.getKey(), v);
                }
            }
        }
    }

    public Class<?> importClass(Symbol sym, Class<?> c) {
        Symbol key = sym.withMeta(null);
        Set<Symbol> excl = this.virtualExcluded;
        if (excl != null) excl.remove(key);
        mappings.put(key, c);
        return c;
    }

    public Class<?> importClass(Class<?> c) {
        String cname = c.getName();
        int dot = cname.lastIndexOf('.');
        String shortName = dot >= 0 ? cname.substring(dot + 1) : cname;
        return importClass(Symbol.intern(shortName), c);
    }

    public void addAlias(Symbol alias, Namespace ns) {
        aliases.put(alias.withMeta(null), ns);
    }

    public void removeAlias(Symbol alias) {
        aliases.remove(alias.withMeta(null));
    }

    public Namespace lookupAlias(Symbol alias) {
        return aliases.get(alias.withMeta(null));
    }

    public Object getMapping(Symbol name) {
        Symbol key = name.withMeta(null);
        Object o = mappings.get(key);
        if (o != null) return o;
        LazyLoader ll = this.lazyLoader;
        if (ll != null && ll.loadSymbol(key)) {
            o = mappings.get(key);
            if (o != null) return o;
        }
        Namespace vns = this.virtualReferNs;
        if (vns != null && !virtualMaterialized) {
            Set<Symbol> excl = this.virtualExcluded;
            if (excl == null || !excl.contains(key)) {
                Object coreObj = vns.getMapping(key);
                if (coreObj instanceof Var v && v.ns == vns && !RT.truthy(v.meta().valAt(PRIVATE_KW))) {
                    return v;
                }
            }
        }
        return null;
    }

    public Var findInternedVar(Symbol sym) {
        Symbol key = sym.withMeta(null);
        Object o = mappings.get(key);
        if (o instanceof Var v && v.ns == this) return v;
        if (o == null) {
            LazyLoader ll = this.lazyLoader;
            if (ll != null && ll.loadSymbol(key)) {
                o = mappings.get(key);
                if (o instanceof Var v && v.ns == this) return v;
            }
        }
        return null;
    }

    public void unmap(Symbol sym) {
        Symbol key = sym.withMeta(null);
        mappings.remove(key);
        if (virtualReferNs != null && !virtualMaterialized) {
            Set<Symbol> excl = this.virtualExcluded;
            if (excl == null) {
                synchronized (this) {
                    excl = this.virtualExcluded;
                    if (excl == null) {
                        excl = ConcurrentHashMap.newKeySet();
                        this.virtualExcluded = excl;
                    }
                }
            }
            excl.add(key);
        }
    }

    private void materializeIfNeeded() {
        LazyLoader ll = this.lazyLoader;
        if (ll != null) {
            synchronized (this) {
                ll = this.lazyLoader;
                if (ll != null) {
                    this.lazyLoader = null;
                    ll.loadAll();
                }
            }
        }
        Namespace vns = this.virtualReferNs;
        if (vns != null && !virtualMaterialized) {
            synchronized (this) {
                if (this.virtualReferNs != null && !virtualMaterialized) {
                    Set<Symbol> excl = this.virtualExcluded;
                    for (Map.Entry<Symbol, Object> e : vns.getMappings().entrySet()) {
                        if (e.getValue() instanceof Var v && v.ns == vns) {
                            if (excl != null && excl.contains(e.getKey())) continue;
                            if (!RT.truthy(v.meta().valAt(PRIVATE_KW))) {
                                mappings.putIfAbsent(e.getKey(), v);
                            }
                        }
                    }
                    this.virtualMaterialized = true;
                    this.virtualReferNs = null;
                    this.virtualExcluded = null;
                }
            }
        }
    }

    public Map<Symbol, Object> getMappings() {
        materializeIfNeeded();
        return mappings;
    }

    public Map<Symbol, Namespace> getAliases() {
        return aliases;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentHashMap resetMeta(PersistentHashMap m) {
        this.meta = m != null ? m : PersistentHashMap.EMPTY;
        return this.meta;
    }

    @Override
    public synchronized PersistentHashMap alterMeta(Fn alter, ISeq args) {
        Object[] callArgs = new Object[1 + RT.count(args)];
        callArgs[0] = this.meta;
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        this.meta = (PersistentHashMap) alter.applyTo(callArgs);
        return this.meta;
    }

    @Override
    public String toString() {
        return name.toString();
    }
}
