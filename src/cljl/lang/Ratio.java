package cljl.lang;

import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

public final class Ratio extends Number implements Comparable<Number> {
    private static final long serialVersionUID = 1L;
    public final BigInteger numerator;
    public final BigInteger denominator;

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        if (numerator == null || denominator == null || denominator.signum() <= 0) {
            throw new InvalidObjectException("Invalid Ratio state: numerator=" + numerator + ", denominator=" + denominator);
        }
    }

    public Ratio(BigInteger numerator, BigInteger denominator) {
        if (denominator.equals(BigInteger.ZERO)) {
            throw new ArithmeticException("Divide by zero");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        this.numerator = gcd.equals(BigInteger.ONE) ? numerator : numerator.divide(gcd);
        this.denominator = gcd.equals(BigInteger.ONE) ? denominator : denominator.divide(gcd);
    }

    public static Number create(BigInteger num, BigInteger den) {
        if (den.equals(BigInteger.ZERO)) {
            throw new ArithmeticException("Divide by zero");
        }
        if (num.equals(BigInteger.ZERO)) {
            return 0L;
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger gcd = num.gcd(den);
        if (!gcd.equals(BigInteger.ONE)) {
            num = num.divide(gcd);
            den = den.divide(gcd);
        }
        if (den.equals(BigInteger.ONE)) {
            if (num.bitLength() <= 63) {
                return num.longValue();
            }
            return BigInt.fromBigInteger(num);
        }
        return new Ratio(num, den);
    }

    public static Number create(long num, long den) {
        if (den == 0) throw new ArithmeticException("Divide by zero");
        if (num == 0) return 0L;
        if (num != Long.MIN_VALUE && den != Long.MIN_VALUE) {
            if (den < 0) {
                num = -num;
                den = -den;
            }
            long g = gcd(Math.abs(num), den);
            num /= g;
            den /= g;
            if (den == 1) return num;
            return new Ratio(BigInteger.valueOf(num), BigInteger.valueOf(den));
        }
        return create(BigInteger.valueOf(num), BigInteger.valueOf(den));
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a;
    }

    @Override
    public int intValue() {
        return (int) doubleValue();
    }

    @Override
    public long longValue() {
        return numerator.divide(denominator).longValue();
    }

    @Override
    public float floatValue() {
        return (float) doubleValue();
    }

    @Override
    public double doubleValue() {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), MathContext.DECIMAL64).doubleValue();
    }

    public BigDecimal toBigDecimal(MathContext mc) {
        return new BigDecimal(numerator).divide(new BigDecimal(denominator), mc);
    }

    @Override
    public int compareTo(Number other) {
        if (other instanceof Ratio r) {
            return numerator.multiply(r.denominator).compareTo(r.numerator.multiply(denominator));
        }
        if (other instanceof Long || other instanceof Integer || other instanceof Short || other instanceof Byte) {
            return numerator.compareTo(BigInteger.valueOf(other.longValue()).multiply(denominator));
        }
        if (other instanceof BigInt bi) {
            return numerator.compareTo(bi.toBigInteger().multiply(denominator));
        }
        if (other instanceof BigInteger bi) {
            return numerator.compareTo(bi.multiply(denominator));
        }
        if (other instanceof BigDecimal bd) {
            int scale = bd.scale();
            BigInteger unscaled = bd.unscaledValue();
            if (scale >= 0) {
                BigInteger pow10 = BigInteger.TEN.pow(scale);
                return numerator.multiply(pow10).compareTo(unscaled.multiply(denominator));
            } else {
                BigInteger pow10 = BigInteger.TEN.pow(-scale);
                return numerator.compareTo(unscaled.multiply(pow10).multiply(denominator));
            }
        }
        return cljl.Numbers.compare(this, other);
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof Ratio r)
                && numerator.equals(r.numerator)
                && denominator.equals(r.denominator);
    }

    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }

    @Override
    public String toString() {
        return numerator.toString() + "/" + denominator.toString();
    }
}
