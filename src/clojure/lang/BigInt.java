package clojure.lang;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Real clojure.lang.BigInt base class extended by cljl.lang.BigInt.
 */
public abstract class BigInt extends Number implements IHashEq, Comparable<Object>, Serializable {
    private static final long serialVersionUID = 1L;

    public static final cljl.lang.BigInt ZERO = cljl.lang.BigInt.zeroInstance();
    public static final cljl.lang.BigInt ONE = cljl.lang.BigInt.oneInstance();

    protected BigInt() {}

    public static cljl.lang.BigInt fromLong(long val) {
        return cljl.lang.BigInt.fromLong(val);
    }

    public static cljl.lang.BigInt valueOf(long val) {
        return cljl.lang.BigInt.fromLong(val);
    }

    public static cljl.lang.BigInt fromBigInteger(BigInteger val) {
        return cljl.lang.BigInt.fromBigInteger(val);
    }

    public abstract BigInteger toBigInteger();
    public abstract BigDecimal toBigDecimal();
}
