package cljl.lang;

public interface Associative extends ILookup, Seqable, Counted {
    boolean containsKey(Object key);
    Associative assoc(Object key, Object val);
}
