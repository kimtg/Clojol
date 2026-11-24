package cljl.lang;

/**
 * Persistent stack interface (clojure.lang.IPersistentStack).
 */
public interface IPersistentStack extends IPersistentCollection {
    Object peek();
    IPersistentStack pop();
}
