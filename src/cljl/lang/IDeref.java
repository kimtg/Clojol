package cljl.lang;

public interface IDeref extends clojure.lang.IDeref, IBlockingDeref {
    @Override
    Object deref();
    @Override
    default Object deref(long timeoutMs, Object timeoutVal) {
        return deref();
    }
}
