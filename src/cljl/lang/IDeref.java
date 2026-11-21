package cljl.lang;

public interface IDeref extends IBlockingDeref {
    Object deref();
    @Override
    default Object deref(long timeoutMs, Object timeoutVal) {
        return deref();
    }
}
