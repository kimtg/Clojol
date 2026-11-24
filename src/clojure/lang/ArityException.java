package clojure.lang;

/**
 * Real clojure.lang.ArityException thrown when a Clojure function is invoked with the wrong number of arguments.
 */
public class ArityException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    public final int actual;
    public final String name;

    public ArityException(int actual, String name) {
        this(actual, name, null);
    }

    public ArityException(int actual, String name, Throwable cause) {
        super("Wrong number of args (" + actual + ") passed to: " + name, cause);
        this.actual = actual;
        this.name = name;
    }
}
