package cljl;

import cljl.lang.ILookup;
import cljl.lang.PersistentHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Keyword extends Fn implements cljl.lang.Named, Comparable<Keyword> {
    private static final ConcurrentHashMap<String, Keyword> TABLE = new ConcurrentHashMap<>(512);

    public final Symbol sym;
    public final int hash;
    private final String str;

    private Keyword(Symbol sym) {
        this.sym = sym;
        this.str = ":" + sym.toString();
        this.hash = sym.hashCode() + 0x9e3779b9;
    }

    public static Keyword intern(Symbol sym) {
        final Symbol clean = sym.meta() != null ? sym.withMeta(null) : sym;
        String key = clean.toString();
        Keyword kw = TABLE.get(key);
        if (kw != null) return kw;
        Keyword created = new Keyword(clean);
        Keyword prev = TABLE.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    public static Keyword intern(String ns, String name) {
        if (ns == null || ns.isEmpty()) return intern(name);
        String key = ns + "/" + name;
        Keyword kw = TABLE.get(key);
        if (kw != null) return kw;
        Keyword created = new Keyword(Symbol.intern(ns, name));
        Keyword prev = TABLE.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }

    public static Keyword intern(String nsname) {
        if (nsname.startsWith(":")) nsname = nsname.substring(1);
        Keyword kw = TABLE.get(nsname);
        if (kw != null) return kw;
        Keyword created = new Keyword(Symbol.intern(nsname));
        Keyword prev = TABLE.putIfAbsent(nsname, created);
        return prev != null ? prev : created;
    }

    public static Keyword find(String ns, String name) {
        String key = (ns == null || ns.isEmpty()) ? name : (ns + "/" + name);
        return TABLE.get(key);
    }

    public String getNamespace() {
        return sym.ns;
    }

    @Override
    public String getName() {
        return sym.name;
    }

    @Override
    public Object invoke(Object coll) {
        if (coll instanceof PersistentHashMap phm) {
            return phm.valAtKeyword(this, null);
        }
        if (coll instanceof ILookup lu) {
            return lu.valAt(this, null);
        }
        return RT.get(coll, this);
    }

    @Override
    public Object invoke(Object coll, Object notFound) {
        if (coll instanceof PersistentHashMap phm) {
            return phm.valAtKeyword(this, notFound);
        }
        if (coll instanceof ILookup lu) {
            return lu.valAt(this, notFound);
        }
        return RT.get(coll, this, notFound);
    }

    @Override
    public int compareTo(Keyword o) {
        if (this == o) return 0;
        return this.sym.compareTo(o.sym);
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return str;
    }
}
