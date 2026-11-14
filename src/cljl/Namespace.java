package cljl;

import cljl.lang.IMeta;
import cljl.lang.IReference;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Namespace implements IMeta, IReference {
    private static final ConcurrentHashMap<Symbol, Namespace> NAMESPACES = new ConcurrentHashMap<>();

    public final Symbol name;
    private final ConcurrentHashMap<Symbol, Object> mappings = new ConcurrentHashMap<>(128);
    private final ConcurrentHashMap<Symbol, Namespace> aliases = new ConcurrentHashMap<>(16);
    private PersistentHashMap meta;

    private Namespace(Symbol name) {
        this.name = name;
        this.meta = name.meta();
        importDefaultClasses();
    }

    private void importDefaultClasses() {
        // Default java.lang imports available in every namespace
        mappings.put(Symbol.intern("Object"), Object.class);
        mappings.put(Symbol.intern("String"), String.class);
        mappings.put(Symbol.intern("StringBuilder"), StringBuilder.class);
        mappings.put(Symbol.intern("Boolean"), Boolean.class);
        mappings.put(Symbol.intern("Byte"), Byte.class);
        mappings.put(Symbol.intern("Short"), Short.class);
        mappings.put(Symbol.intern("Integer"), Integer.class);
        mappings.put(Symbol.intern("Long"), Long.class);
        mappings.put(Symbol.intern("Float"), Float.class);
        mappings.put(Symbol.intern("Double"), Double.class);
        mappings.put(Symbol.intern("Character"), Character.class);
        mappings.put(Symbol.intern("Number"), Number.class);
        mappings.put(Symbol.intern("Math"), Math.class);
        mappings.put(Symbol.intern("System"), System.class);
        mappings.put(Symbol.intern("Thread"), Thread.class);
        mappings.put(Symbol.intern("Runnable"), Runnable.class);
        mappings.put(Symbol.intern("Class"), Class.class);
        mappings.put(Symbol.intern("Exception"), Exception.class);
        mappings.put(Symbol.intern("RuntimeException"), RuntimeException.class);
        mappings.put(Symbol.intern("Throwable"), Throwable.class);
        mappings.put(Symbol.intern("Error"), Error.class);
        mappings.put(Symbol.intern("IllegalArgumentException"), IllegalArgumentException.class);
        mappings.put(Symbol.intern("IllegalStateException"), IllegalStateException.class);
        mappings.put(Symbol.intern("IndexOutOfBoundsException"), IndexOutOfBoundsException.class);
        mappings.put(Symbol.intern("NullPointerException"), NullPointerException.class);
        mappings.put(Symbol.intern("UnsupportedOperationException"), UnsupportedOperationException.class);
        mappings.put(Symbol.intern("ArithmeticException"), ArithmeticException.class);
        mappings.put(Symbol.intern("Comparable"), Comparable.class);
        mappings.put(Symbol.intern("Iterable"), Iterable.class);
        mappings.put(Symbol.intern("BigInteger"), java.math.BigInteger.class);
        mappings.put(Symbol.intern("BigDecimal"), java.math.BigDecimal.class);
    }

    public static Namespace findOrCreate(Symbol name) {
        return NAMESPACES.computeIfAbsent(name.withMeta(null), Namespace::new);
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
        Object existing = mappings.get(key);
        if (existing instanceof Var v && v.ns == this) {
            if (sym.meta() != null) v.setMeta(sym.meta());
            return v;
        }
        Var v = new Var(this, key);
        if (sym.meta() != null) v.setMeta(sym.meta());
        mappings.put(key, v);
        return v;
    }

    public Var intern(String name, Object val) {
        Var v = intern(Symbol.intern(name));
        v.bindRoot(val);
        return v;
    }

    public Var internMacro(String name, Fn fn) {
        Var v = intern(Symbol.intern(name));
        v.bindRoot(fn);
        v.setMacro();
        return v;
    }

    public Var refer(Symbol sym, Var var) {
        mappings.put(sym.withMeta(null), var);
        return var;
    }

    public void referAll(Namespace other) {
        for (Map.Entry<Symbol, Object> e : other.mappings.entrySet()) {
            if (e.getValue() instanceof Var v && v.ns == other) {
                boolean isPrivate = RT.truthy(v.meta().valAt(Keyword.intern("private")));
                if (!isPrivate) {
                    mappings.putIfAbsent(e.getKey(), v);
                }
            }
        }
    }

    public Class<?> importClass(Symbol sym, Class<?> c) {
        mappings.put(sym.withMeta(null), c);
        return c;
    }

    public Class<?> importClass(Class<?> c) {
        return importClass(Symbol.intern(c.getSimpleName()), c);
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
        return mappings.get(name.withMeta(null));
    }

    public Var findInternedVar(Symbol sym) {
        Object o = mappings.get(sym.withMeta(null));
        if (o instanceof Var v && v.ns == this) return v;
        return null;
    }

    public void unmap(Symbol sym) {
        mappings.remove(sym.withMeta(null));
    }

    public Map<Symbol, Object> getMappings() {
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
        return "#<Namespace " + name + ">";
    }
}
