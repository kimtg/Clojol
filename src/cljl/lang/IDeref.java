package cljl.lang;

public interface IDeref {
    Object deref();
    default Object deref(long timeoutMs, Object timeoutVal) {
        return deref();
    }
}
