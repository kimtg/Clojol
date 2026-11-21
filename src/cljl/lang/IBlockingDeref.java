package cljl.lang;

public interface IBlockingDeref {
    Object deref(long timeoutMs, Object timeoutVal);
}
