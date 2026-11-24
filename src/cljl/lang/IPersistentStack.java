package cljl.lang;

/**
 * Persistent stack interface (clojure.lang.IPersistentStack).
 */
public interface IPersistentStack extends clojure.lang.IPersistentStack, IPersistentCollection {
    @Override
    Object peek();
    @Override
    IPersistentStack pop();
}
