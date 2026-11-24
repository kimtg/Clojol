package cljl.lang;

public interface ILookup extends clojure.lang.ILookup {
    @Override
    Object valAt(Object key);
    @Override
    Object valAt(Object key, Object notFound);
}
