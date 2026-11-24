package cljl.lang;

import cljl.Numbers;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Arbitrary-precision Clojure integer distinct from java.math.BigInteger (clojure.lang.BigInt).
 * Stores 64-bit signed values unboxed in lpart (with bipart == null) and overflows to BigInteger.
 */
public final class BigInt extends clojure.lang.BigInt implements IHashEq, Comparable<Object>, Serializable {
    private static final long serialVersionUID = 1L;

    private static BigInt _zero;
    private static BigInt _one;

    public static BigInt zeroInstance() {
        BigInt z = _zero;
        if (z == null) { z = new BigInt(0L, null); _zero = z; }
        return z;
    }

    public static BigInt oneInstance() {
        BigInt o = _one;
        if (o == null) { o = new BigInt(1L, null); _one = o; }
        return o;
    }

    public static final BigInt ZERO = zeroInstance();
    public static final BigInt ONE = oneInstance();

    public final long lpart;
    public final BigInteger bipart;

    private BigInt(long lpart, BigInteger bipart) {
        this.lpart = lpart;
        this.bipart = bipart;
    }

    public static BigInt fromLong(long val) {
        if (val == 0L) return ZERO;
        if (val == 1L) return ONE;
        return new BigInt(val, null);
    }

    public static BigInt valueOf(long val) {
        return fromLong(val);
    }

    public static BigInt fromBigInteger(BigInteger val) {
        if (val == null) throw new IllegalArgumentException("BigInteger cannot be null");
        if (val.bitLength() < 64) {
            return fromLong(val.longValue());
        }
        return new BigInt(0L, val);
    }

    public BigInteger toBigInteger() {
        return bipart == null ? BigInteger.valueOf(lpart) : bipart;
    }

    public BigDecimal toBigDecimal() {
        return bipart == null ? BigDecimal.valueOf(lpart) : new BigDecimal(bipart);
    }

    public int signum() {
        return bipart == null ? Long.signum(lpart) : bipart.signum();
    }

    public BigInt negate() {
        if (bipart == null) {
            if (lpart == Long.MIN_VALUE) return fromBigInteger(BigInteger.valueOf(lpart).negate());
            return fromLong(-lpart);
        }
        return fromBigInteger(bipart.negate());
    }

    public BigInt abs() {
        if (bipart == null) {
            if (lpart == Long.MIN_VALUE) return fromBigInteger(BigInteger.valueOf(lpart).abs());
            return fromLong(Math.abs(lpart));
        }
        return fromBigInteger(bipart.abs());
    }

    public BigInt add(BigInt y) {
        if (bipart == null && y.bipart == null) {
            long ret = lpart + y.lpart;
            if (((lpart ^ ret) & (y.lpart ^ ret)) >= 0L) {
                return fromLong(ret);
            }
        }
        return fromBigInteger(toBigInteger().add(y.toBigInteger()));
    }

    public BigInt subtract(BigInt y) {
        if (bipart == null && y.bipart == null) {
            long ret = lpart - y.lpart;
            if (((lpart ^ y.lpart) & (lpart ^ ret)) >= 0L) {
                return fromLong(ret);
            }
        }
        return fromBigInteger(toBigInteger().subtract(y.toBigInteger()));
    }

    public BigInt multiply(BigInt y) {
        if (bipart == null && y.bipart == null) {
            try {
                return fromLong(Math.multiplyExact(lpart, y.lpart));
            } catch (ArithmeticException ignored) {}
        }
        return fromBigInteger(toBigInteger().multiply(y.toBigInteger()));
    }

    public BigInt quotient(BigInt y) {
        if (bipart == null && y.bipart == null) {
            if (y.lpart == 0L) throw new ArithmeticException("Divide by zero");
            if (!(lpart == Long.MIN_VALUE && y.lpart == -1L)) {
                return fromLong(lpart / y.lpart);
            }
        }
        return fromBigInteger(toBigInteger().divide(y.toBigInteger()));
    }

    public BigInt remainder(BigInt y) {
        if (bipart == null && y.bipart == null) {
            if (y.lpart == 0L) throw new ArithmeticException("Divide by zero");
            return fromLong(lpart % y.lpart);
        }
        return fromBigInteger(toBigInteger().remainder(y.toBigInteger()));
    }

    public boolean lt(BigInt y) {
        if (bipart == null && y.bipart == null) {
            return lpart < y.lpart;
        }
        return toBigInteger().compareTo(y.toBigInteger()) < 0;
    }

    @Override
    public int intValue() {
        return bipart == null ? (int) lpart : bipart.intValue();
    }

    @Override
    public long longValue() {
        return bipart == null ? lpart : bipart.longValue();
    }

    @Override
    public float floatValue() {
        return bipart == null ? (float) lpart : bipart.floatValue();
    }

    @Override
    public double doubleValue() {
        return bipart == null ? (double) lpart : bipart.doubleValue();
    }

    @Override
    public byte byteValue() {
        return bipart == null ? (byte) lpart : bipart.byteValue();
    }

    @Override
    public short shortValue() {
        return bipart == null ? (short) lpart : bipart.shortValue();
    }

    @Override
    public int hasheq() {
        return bipart == null ? Murmur3.hashLong(lpart) : bipart.hashCode();
    }

    @Override
    public int hashCode() {
        return bipart == null ? Long.hashCode(lpart) : bipart.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof BigInt bi) {
            if (bipart == null && bi.bipart == null) return lpart == bi.lpart;
            return toBigInteger().equals(bi.toBigInteger());
        }
        return false;
    }

    @Override
    public int compareTo(Object o) {
        if (o instanceof BigInt bi) {
            if (bipart == null && bi.bipart == null) return Long.compare(lpart, bi.lpart);
            return toBigInteger().compareTo(bi.toBigInteger());
        }
        if (o instanceof Number n) {
            return Numbers.compare(this, n);
        }
        throw new ClassCastException("Cannot compare BigInt with " + (o == null ? "null" : o.getClass().getName()));
    }

    @Override
    public String toString() {
        return bipart == null ? Long.toString(lpart) : bipart.toString();
    }
}
