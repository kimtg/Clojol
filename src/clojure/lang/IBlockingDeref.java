package clojure.lang;

public interface IBlockingDeref {
    Object deref(long timeoutInMilliseconds, Object timeoutValue);
}
