package cljl.lang;

public interface IBlockingDeref extends clojure.lang.IBlockingDeref {
    @Override
    Object deref(long timeoutMs, Object timeoutVal);
}
