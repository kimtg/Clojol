package cljl;

import cljl.lang.Ratio;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;

/**
 * High-speed numeric tower for ClojureLite supporting Long, Double, BigInteger,
 * BigDecimal, and Ratio with fast-paths for 64-bit Long and Double.
 */
public final class Numbers {

    private Numbers() {}

    public static boolean isNumber(Object o) {
        return o instanceof Number;
    }

    public static boolean isInteger(Object o) {
        return o instanceof Long || o instanceof Integer || o instanceof BigInteger
                || o instanceof Short || o instanceof Byte;
    }

    public static long toLong(Object o) {
        if (o instanceof Long l) return l;
        if (o instanceof Number n) return n.longValue();
        throw new ClassCastException("Cannot cast " + (o == null ? "nil" : o.getClass().getName()) + " to Number");
    }

    public static double toDouble(Object o) {
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        throw new ClassCastException("Cannot cast " + (o == null ? "nil" : o.getClass().getName()) + " to Number");
    }

    public static BigInteger toBigInteger(Object o) {
        if (o instanceof BigInteger bi) return bi;
        if (o instanceof Number n) return BigInteger.valueOf(n.longValue());
        if (o instanceof String s) return new BigInteger(s);
        throw new ClassCastException("Cannot convert to BigInteger: " + o);
    }

    public static BigDecimal toBigDecimal(Object o) {
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof BigInteger bi) return new BigDecimal(bi);
        if (o instanceof Ratio r) return r.toBigDecimal(MathContext.DECIMAL128);
        if (o instanceof Double || o instanceof Float) return BigDecimal.valueOf(((Number) o).doubleValue());
        if (o instanceof Number n) return BigDecimal.valueOf(n.longValue());
        if (o instanceof String s) return new BigDecimal(s);
        throw new ClassCastException("Cannot convert to BigDecimal: " + o);
    }

    public static Number reduceBigInt(BigInteger bi) {
        if (bi.bitLength() <= 63) {
            return bi.longValue();
        }
        return bi;
    }

    public static Number add(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            return Math.addExact(lx, ly);
        }
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) + toDouble(y);
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            return toBigDecimal(x).add(toBigDecimal(y));
        }
        if (x instanceof Ratio || y instanceof Ratio) {
            Ratio rx = toRatio(x);
            Ratio ry = toRatio(y);
            return Ratio.create(
                    rx.numerator.multiply(ry.denominator).add(ry.numerator.multiply(rx.denominator)),
                    rx.denominator.multiply(ry.denominator));
        }
        if (x instanceof BigInteger || y instanceof BigInteger) {
            return toBigInteger(x).add(toBigInteger(y));
        }
        return Math.addExact(toLong(x), toLong(y));
    }

    public static Number addP(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            long r = lx + ly;
            if (((lx ^ r) & (ly ^ r)) < 0) {
                return BigInteger.valueOf(lx).add(BigInteger.valueOf(ly));
            }
            return r;
        }
        if (x instanceof Integer || y instanceof Integer || x instanceof Short || y instanceof Short || x instanceof Byte || y instanceof Byte) {
            if (!(x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float
                    || x instanceof BigDecimal || y instanceof BigDecimal || x instanceof Ratio || y instanceof Ratio
                    || x instanceof BigInteger || y instanceof BigInteger)) {
                return addP(toLong(x), toLong(y));
            }
        }
        return add(x, y);
    }

    public static Number minus(Object x) {
        if (x instanceof Long lx) return Math.negateExact(lx);
        if (x instanceof Double dx) return -dx;
        if (x instanceof Integer ix) return Math.negateExact((long) ix);
        if (x instanceof BigInteger bi) return bi.negate();
        if (x instanceof BigDecimal bd) return bd.negate();
        if (x instanceof Ratio r) return Ratio.create(r.numerator.negate(), r.denominator);
        return -toDouble(x);
    }

    public static Number minusP(Object x) {
        if (x instanceof Long lx) {
            if (lx == Long.MIN_VALUE) return BigInteger.valueOf(lx).negate();
            return -lx;
        }
        return minus(x);
    }

    public static Number minus(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            return Math.subtractExact(lx, ly);
        }
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) - toDouble(y);
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            return toBigDecimal(x).subtract(toBigDecimal(y));
        }
        if (x instanceof Ratio || y instanceof Ratio) {
            Ratio rx = toRatio(x);
            Ratio ry = toRatio(y);
            return Ratio.create(
                    rx.numerator.multiply(ry.denominator).subtract(ry.numerator.multiply(rx.denominator)),
                    rx.denominator.multiply(ry.denominator));
        }
        if (x instanceof BigInteger || y instanceof BigInteger) {
            return toBigInteger(x).subtract(toBigInteger(y));
        }
        return Math.subtractExact(toLong(x), toLong(y));
    }

    public static Number minusP(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            long r = lx - ly;
            if (((lx ^ ly) & (lx ^ r)) < 0) {
                return BigInteger.valueOf(lx).subtract(BigInteger.valueOf(ly));
            }
            return r;
        }
        return minus(x, y);
    }

    public static Number multiply(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            return Math.multiplyExact(lx, ly);
        }
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) * toDouble(y);
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            return toBigDecimal(x).multiply(toBigDecimal(y));
        }
        if (x instanceof Ratio || y instanceof Ratio) {
            Ratio rx = toRatio(x);
            Ratio ry = toRatio(y);
            return Ratio.create(rx.numerator.multiply(ry.numerator), rx.denominator.multiply(ry.denominator));
        }
        if (x instanceof BigInteger || y instanceof BigInteger) {
            return toBigInteger(x).multiply(toBigInteger(y));
        }
        return Math.multiplyExact(toLong(x), toLong(y));
    }

    public static Number multiplyP(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            try {
                return Math.multiplyExact(lx, ly);
            } catch (ArithmeticException e) {
                return BigInteger.valueOf(lx).multiply(BigInteger.valueOf(ly));
            }
        }
        return multiply(x, y);
    }

    public static Number divide(Object x, Object y) {
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) / toDouble(y);
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            BigDecimal bdy = toBigDecimal(y);
            if (bdy.compareTo(BigDecimal.ZERO) == 0) throw new ArithmeticException("Divide by zero");
            try {
                return toBigDecimal(x).divide(bdy);
            } catch (ArithmeticException e) {
                return toBigDecimal(x).divide(bdy, MathContext.DECIMAL128);
            }
        }
        if (x instanceof Long lx && y instanceof Long ly) {
            if (ly == 0) throw new ArithmeticException("Divide by zero");
            if (lx % ly == 0 && !(lx == Long.MIN_VALUE && ly == -1)) {
                return lx / ly;
            }
            return Ratio.create(lx, ly);
        }
        if (x instanceof Ratio || y instanceof Ratio) {
            Ratio rx = toRatio(x);
            Ratio ry = toRatio(y);
            if (ry.numerator.equals(BigInteger.ZERO)) throw new ArithmeticException("Divide by zero");
            return Ratio.create(rx.numerator.multiply(ry.denominator), rx.denominator.multiply(ry.numerator));
        }
        if (x instanceof BigInteger || y instanceof BigInteger) {
            return Ratio.create(toBigInteger(x), toBigInteger(y));
        }
        return Ratio.create(toLong(x), toLong(y));
    }

    public static Number quotient(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            return lx / ly;
        }
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            double q = toDouble(x) / toDouble(y);
            return q >= 0 ? Math.floor(q) : Math.ceil(q);
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            return toBigDecimal(x).divideToIntegralValue(toBigDecimal(y));
        }
        if (x instanceof BigInteger || y instanceof BigInteger || x instanceof Ratio || y instanceof Ratio) {
            BigInteger n = toBigInteger(divide(x, y));
            return reduceBigInt(n);
        }
        return toLong(x) / toLong(y);
    }

    public static Number remainder(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            return lx % ly;
        }
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) % toDouble(y);
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            return toBigDecimal(x).remainder(toBigDecimal(y));
        }
        if (x instanceof BigInteger || y instanceof BigInteger) {
            return reduceBigInt(toBigInteger(x).remainder(toBigInteger(y)));
        }
        if (x instanceof Ratio || y instanceof Ratio) {
            Number q = quotient(x, y);
            return minus(x, multiply(q, y));
        }
        return toLong(x) % toLong(y);
    }

    public static Number mod(Object x, Object y) {
        Number rem = remainder(x, y);
        if (isZero(rem)) return rem;
        if (isPos(x) == isPos(y)) return rem;
        return add(rem, y);
    }

    public static Number inc(Object x) {
        if (x instanceof Long lx) return Math.incrementExact(lx);
        return add(x, 1L);
    }

    public static Number incP(Object x) {
        if (x instanceof Long lx) {
            if (lx == Long.MAX_VALUE) return BigInteger.valueOf(lx).add(BigInteger.ONE);
            return lx + 1L;
        }
        return addP(x, 1L);
    }

    public static Number dec(Object x) {
        if (x instanceof Long lx) return Math.decrementExact(lx);
        return minus(x, 1L);
    }

    public static Number decP(Object x) {
        if (x instanceof Long lx) {
            if (lx == Long.MIN_VALUE) return BigInteger.valueOf(lx).subtract(BigInteger.ONE);
            return lx - 1L;
        }
        return minusP(x, 1L);
    }

    public static Number abs(Object x) {
        if (x instanceof Long lx) return Math.abs(lx);
        if (x instanceof Double dx) return Math.abs(dx);
        if (x instanceof Integer ix) return Math.abs(ix);
        if (x instanceof BigInteger bi) return bi.abs();
        if (x instanceof BigDecimal bd) return bd.abs();
        if (x instanceof Ratio r) return isNeg(r) ? minus(r) : r;
        return Math.abs(toLong(x));
    }

    public static boolean isZero(Object x) {
        if (x instanceof Long lx) return lx == 0L;
        if (x instanceof Double dx) return dx == 0.0;
        if (x instanceof Integer ix) return ix == 0;
        if (x instanceof BigInteger bi) return bi.signum() == 0;
        if (x instanceof BigDecimal bd) return bd.signum() == 0;
        if (x instanceof Ratio r) return r.numerator.signum() == 0;
        return toDouble(x) == 0.0;
    }

    public static boolean isPos(Object x) {
        if (x instanceof Long lx) return lx > 0L;
        if (x instanceof Double dx) return dx > 0.0;
        if (x instanceof Integer ix) return ix > 0;
        if (x instanceof BigInteger bi) return bi.signum() > 0;
        if (x instanceof BigDecimal bd) return bd.signum() > 0;
        if (x instanceof Ratio r) return r.numerator.signum() > 0;
        return toDouble(x) > 0.0;
    }

    public static boolean isNeg(Object x) {
        if (x instanceof Long lx) return lx < 0L;
        if (x instanceof Double dx) return dx < 0.0;
        if (x instanceof Integer ix) return ix < 0;
        if (x instanceof BigInteger bi) return bi.signum() < 0;
        if (x instanceof BigDecimal bd) return bd.signum() < 0;
        if (x instanceof Ratio r) return r.numerator.signum() < 0;
        return toDouble(x) < 0.0;
    }

    public static boolean lt(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) return lx < ly;
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) < toDouble(y);
        }
        return compare((Number) x, (Number) y) < 0;
    }

    public static boolean lte(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) return lx <= ly;
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) <= toDouble(y);
        }
        return compare((Number) x, (Number) y) <= 0;
    }

    public static boolean gt(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) return lx > ly;
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) > toDouble(y);
        }
        return compare((Number) x, (Number) y) > 0;
    }

    public static boolean gte(Object x, Object y) {
        if (x instanceof Long lx && y instanceof Long ly) return lx >= ly;
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return toDouble(x) >= toDouble(y);
        }
        return compare((Number) x, (Number) y) >= 0;
    }

    public static boolean equal(Object x, Object y) {
        if (!(x instanceof Number nx && y instanceof Number ny)) return false;
        if (x instanceof Long lx && y instanceof Long ly) return lx.longValue() == ly.longValue();
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return nx.doubleValue() == ny.doubleValue();
        }
        return compare(nx, ny) == 0;
    }

    /**
     * Clojure category-aware equivalence (=): integers equal integers of same value,
     * floats equal floats of same value, ratios equal ratios, BigDecimals equal BigDecimals.
     */
    public static boolean equiv(Number x, Number y) {
        if (x instanceof Long lx && y instanceof Long ly) return lx.longValue() == ly.longValue();
        if (isInteger(x) && isInteger(y)) {
            if (x instanceof BigInteger || y instanceof BigInteger) {
                return toBigInteger(x).equals(toBigInteger(y));
            }
            return x.longValue() == y.longValue();
        }
        if ((x instanceof Double || x instanceof Float) && (y instanceof Double || y instanceof Float)) {
            return x.doubleValue() == y.doubleValue();
        }
        if (x instanceof BigDecimal bdx && y instanceof BigDecimal bdy) {
            return bdx.compareTo(bdy) == 0;
        }
        if (x instanceof Ratio rx && y instanceof Ratio ry) {
            return rx.equals(ry);
        }
        return false;
    }

    public static int compare(Number x, Number y) {
        if (x instanceof Long lx && y instanceof Long ly) {
            return Long.compare(lx, ly);
        }
        if (x instanceof Double || y instanceof Double || x instanceof Float || y instanceof Float) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (x instanceof BigDecimal || y instanceof BigDecimal) {
            return toBigDecimal(x).compareTo(toBigDecimal(y));
        }
        if (x instanceof Ratio || y instanceof Ratio) {
            return toRatio(x).compareTo(toRatio(y));
        }
        if (x instanceof BigInteger || y instanceof BigInteger) {
            return toBigInteger(x).compareTo(toBigInteger(y));
        }
        return Long.compare(x.longValue(), y.longValue());
    }

    public static Object max(Object x, Object y) {
        if (x instanceof Double dx && Double.isNaN(dx)) return x;
        if (y instanceof Double dy && Double.isNaN(dy)) return y;
        return gt(x, y) ? x : y;
    }

    public static Object min(Object x, Object y) {
        if (x instanceof Double dx && Double.isNaN(dx)) return x;
        if (y instanceof Double dy && Double.isNaN(dy)) return y;
        return lt(x, y) ? x : y;
    }

    private static Ratio toRatio(Object x) {
        if (x instanceof Ratio r) return r;
        if (x instanceof BigInteger bi) return new Ratio(bi, BigInteger.ONE);
        return new Ratio(BigInteger.valueOf(toLong(x)), BigInteger.ONE);
    }

    // Bitwise operations
    public static long bitAnd(Object x, Object y) { return toLong(x) & toLong(y); }
    public static long bitOr(Object x, Object y) { return toLong(x) | toLong(y); }
    public static long bitXor(Object x, Object y) { return toLong(x) ^ toLong(y); }
    public static long bitNot(Object x) { return ~toLong(x); }
    public static long bitAndNot(Object x, Object y) { return toLong(x) & ~toLong(y); }
    public static long bitShiftLeft(Object x, Object n) { return toLong(x) << toLong(n); }
    public static long bitShiftRight(Object x, Object n) { return toLong(x) >> toLong(n); }
    public static long unsignedBitShiftRight(Object x, Object n) { return toLong(x) >>> toLong(n); }
    public static boolean bitTest(Object x, Object n) { return (toLong(x) & (1L << toLong(n))) != 0; }
    public static long bitSet(Object x, Object n) { return toLong(x) | (1L << toLong(n)); }
    public static long bitClear(Object x, Object n) { return toLong(x) & ~(1L << toLong(n)); }
    public static long bitFlip(Object x, Object n) { return toLong(x) ^ (1L << toLong(n)); }
}
