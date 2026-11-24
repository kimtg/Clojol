package cljl;

import cljl.lang.IHashEq;
import cljl.lang.IObj;
import cljl.lang.Murmur3;
import cljl.lang.PersistentHashMap;
import cljl.lang.Util;
import java.io.Serializable;
import java.util.Objects;

public final class Symbol extends Fn implements IObj, cljl.lang.Named, IHashEq, Comparable<Symbol>, Serializable {
    private static final long serialVersionUID = 1L;
    public final String ns;
    public final String name;
    private final PersistentHashMap meta;
    private int hash;
    private int hasheq;
    private String str;

    public Symbol(PersistentHashMap meta, String ns, String name) {
        this.meta = meta;
        this.ns = (ns != null && ns.isEmpty()) ? null : ns;
        this.name = Objects.requireNonNull(name);
    }

    public static Symbol intern(String ns, String name) {
        return new Symbol(null, ns, name);
    }

    public static Symbol intern(String nsname) {
        if ("/".equals(nsname)) {
            return new Symbol(null, null, "/");
        }
        int i = nsname.indexOf('/');
        if (i == -1) {
            return new Symbol(null, null, nsname);
        }
        return new Symbol(null, nsname.substring(0, i), nsname.substring(i + 1));
    }

    public String getNamespace() {
        return ns;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public Symbol withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new Symbol(meta, ns, name);
    }

    @Override
    public Object invoke(Object coll) {
        return RT.get(coll, this);
    }

    @Override
    public Object invoke(Object coll, Object notFound) {
        return RT.get(coll, this, notFound);
    }

    @Override
    public int compareTo(Symbol o) {
        if (this.equals(o)) return 0;
        if (this.ns == null && o.ns != null) return -1;
        if (this.ns != null) {
            if (o.ns == null) return 1;
            int nsc = this.ns.compareTo(o.ns);
            if (nsc != 0) return nsc;
        }
        return this.name.compareTo(o.name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Symbol s)) return false;
        return Objects.equals(ns, s.ns) && name.equals(s.name);
    }

    @Override
    public int hasheq() {
        int h = this.hasheq;
        if (h == 0) {
            h = Util.hashCombine(Murmur3.hashUnencodedChars(name),
                    ns == null ? 0 : Murmur3.hashUnencodedChars(ns));
            if (h == 0) h = 1;
            this.hasheq = h;
        }
        return h;
    }

    @Override
    public int hashCode() {
        int h = this.hash;
        if (h == 0) {
            h = Util.hashCombine(name.hashCode(), ns == null ? 0 : ns.hashCode());
            if (h == 0) h = 1;
            this.hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        if (str == null) {
            str = (ns != null) ? (ns + "/" + name) : name;
        }
        return str;
    }
}
