package cljl.lang;

public interface Associative extends Seqable, Counted {
    boolean containsKey(Object key);
    Object valAt(Object key);
    Object valAt(Object key, Object notFound);
    Associative assoc(Object key, Object val);
}
