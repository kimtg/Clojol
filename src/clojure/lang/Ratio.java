package clojure.lang;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * Real clojure.lang.Ratio base class extended by cljl.lang.Ratio.
 */
public abstract class Ratio extends Number implements Comparable<Number> {
    private static final long serialVersionUID = 1L;

    protected Ratio() {}

    public static Number create(BigInteger num, BigInteger den) {
        return cljl.lang.Ratio.create(num, den);
    }

}
