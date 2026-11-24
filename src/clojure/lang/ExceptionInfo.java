package clojure.lang;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;

/**
 * Real clojure.lang.ExceptionInfo runtime class carrying an IPersistentMap payload.
 */
public class ExceptionInfo extends RuntimeException implements IExceptionInfo {
    private static final long serialVersionUID = -1073473305916521986L;
    public final IPersistentMap data;

    public ExceptionInfo(String s, IPersistentMap data) {
        this(s, data, null);
    }

    public ExceptionInfo(String s, IPersistentMap data, Throwable cause) {
        super(s, cause);
        if (data != null) {
            this.data = data;
        } else {
            throw new IllegalArgumentException("Additional data must be non-nil.");
        }
    }

    @Override
    public IPersistentMap getData() {
        return data;
    }

    @Override
    public String toString() {
        return "clojure.lang.ExceptionInfo: " + getMessage() + " " + cljl.RT.printString(data);
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (data == null) {
            throw new InvalidObjectException("ExceptionInfo data cannot be null");
        }
    }
}
