package clojure.lang;

import java.io.Serializable;

/**
 * Real clojure.lang.ASeq base class extended by cljl.lang.ASeq.
 */
public abstract class ASeq implements ISeq, IObj, Sequential, IHashEq, Serializable {
    private static final long serialVersionUID = 1L;

    protected ASeq() {}
}
