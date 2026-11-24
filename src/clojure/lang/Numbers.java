package clojure.lang;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Clean-room clojure.lang.Numbers static numeric bridge delegating to cljl.Numbers.
 */
public final class Numbers {
    private Numbers() {}

    public static boolean isZero(Object x) { return cljl.Numbers.isZero(x); }
    public static boolean isZero(long x) { return x == 0L; }
    public static boolean isZero(double x) { return x == 0.0; }
    public static boolean isPos(Object x) { return cljl.Numbers.isPos(x); }
    public static boolean isPos(long x) { return x > 0L; }
    public static boolean isPos(double x) { return x > 0.0; }
    public static boolean isNeg(Object x) { return cljl.Numbers.isNeg(x); }
    public static boolean isNeg(long x) { return x < 0L; }
    public static boolean isNeg(double x) { return x < 0.0; }

    public static Number num(Object x) { return cljl.Numbers.num(x); }
    public static Number num(long x) { return Long.valueOf(x); }
    public static Number num(double x) { return Double.valueOf(x); }
    public static Number num(int x) { return Long.valueOf(x); }
    public static Number num(float x) { return Double.valueOf(x); }

    public static Number add(Object x, Object y) { return cljl.Numbers.add(x, y); }
    public static Number add(Object x, long y) { return cljl.Numbers.add(x, y); }
    public static Number add(long x, Object y) { return cljl.Numbers.add(x, y); }
    public static long add(long x, long y) { return Math.addExact(x, y); }
    public static double add(double x, double y) { return x + y; }
    public static double add(double x, long y) { return x + y; }
    public static double add(long x, double y) { return x + y; }

    public static Number addP(Object x, Object y) { return cljl.Numbers.addP(x, y); }
    public static Number addP(long x, long y) { return cljl.Numbers.addP(Long.valueOf(x), Long.valueOf(y)); }
    public static double addP(double x, double y) { return x + y; }

    public static Number unchecked_add(Object x, Object y) { return cljl.Numbers.uncheckedAdd(x, y); }
    public static long unchecked_add(long x, long y) { return x + y; }
    public static double unchecked_add(double x, double y) { return x + y; }
    public static int unchecked_int_add(int x, int y) { return x + y; }

    public static Number minus(Object x) { return cljl.Numbers.minus(x); }
    public static long minus(long x) { return Math.negateExact(x); }
    public static double minus(double x) { return -x; }
    public static Number minusP(Object x) { return cljl.Numbers.minusP(x); }
    public static Number minus(Object x, Object y) { return cljl.Numbers.minus(x, y); }
    public static Number minus(Object x, long y) { return cljl.Numbers.minus(x, y); }
    public static Number minus(long x, Object y) { return cljl.Numbers.minus(x, y); }
    public static long minus(long x, long y) { return Math.subtractExact(x, y); }
    public static double minus(double x, double y) { return x - y; }
    public static Number minusP(Object x, Object y) { return cljl.Numbers.minusP(x, y); }
    public static Number unchecked_minus(Object x) { return cljl.Numbers.uncheckedNegate(x); }
    public static long unchecked_minus(long x) { return -x; }
    public static Number unchecked_minus(Object x, Object y) { return cljl.Numbers.uncheckedSubtract(x, y); }
    public static long unchecked_minus(long x, long y) { return x - y; }
    public static double unchecked_minus(double x, double y) { return x - y; }
    public static int unchecked_int_subtract(int x, int y) { return x - y; }

    public static Number multiply(Object x, Object y) { return cljl.Numbers.multiply(x, y); }
    public static Number multiply(Object x, long y) { return cljl.Numbers.multiply(x, y); }
    public static Number multiply(long x, Object y) { return cljl.Numbers.multiply(x, y); }
    public static long multiply(long x, long y) { return Math.multiplyExact(x, y); }
    public static double multiply(double x, double y) { return x * y; }
    public static Number multiplyP(Object x, Object y) { return cljl.Numbers.multiplyP(x, y); }
    public static Number unchecked_multiply(Object x, Object y) { return cljl.Numbers.uncheckedMultiply(x, y); }
    public static long unchecked_multiply(long x, long y) { return x * y; }
    public static double unchecked_multiply(double x, double y) { return x * y; }
    public static int unchecked_int_multiply(int x, int y) { return x * y; }

    public static Number divide(Object x, Object y) { return cljl.Numbers.divide(x, y); }
    public static Number divide(double x, Object y) { return cljl.Numbers.divide(x, y); }
    public static Number divide(Object x, double y) { return cljl.Numbers.divide(x, y); }
    public static double divide(double x, double y) { return x / y; }
    public static Number quotient(Object x, Object y) { return cljl.Numbers.quotient(x, y); }
    public static long quotient(long x, long y) { return x / y; }
    public static Number remainder(Object x, Object y) { return cljl.Numbers.remainder(x, y); }
    public static long remainder(long x, long y) { return x % y; }

    public static Number inc(Object x) { return cljl.Numbers.inc(x); }
    public static long inc(long x) { return cljl.Numbers.incExact(x); }
    public static double inc(double x) { return x + 1.0; }
    public static Number incP(Object x) { return cljl.Numbers.incP(x); }
    public static Number unchecked_inc(Object x) { return cljl.Numbers.uncheckedInc(x); }
    public static long unchecked_inc(long x) { return x + 1L; }
    public static double unchecked_inc(double x) { return x + 1.0; }

    public static Number dec(Object x) { return cljl.Numbers.dec(x); }
    public static long dec(long x) { return cljl.Numbers.decExact(x); }
    public static double dec(double x) { return x - 1.0; }
    public static Number decP(Object x) { return cljl.Numbers.decP(x); }
    public static Number unchecked_dec(Object x) { return cljl.Numbers.uncheckedDec(x); }
    public static long unchecked_dec(long x) { return x - 1L; }
    public static double unchecked_dec(double x) { return x - 1.0; }

    public static boolean equiv(Object x, Object y) { return cljl.RT.equiv(x, y); }
    public static boolean equiv(long x, long y) { return x == y; }
    public static boolean equiv(double x, double y) { return x == y; }
    public static boolean lt(Object x, Object y) { return cljl.Numbers.lt(x, y); }
    public static boolean lt(Object x, long y) { return cljl.Numbers.lt(x, y); }
    public static boolean lt(long x, Object y) { return cljl.Numbers.lt(x, y); }
    public static boolean lt(long x, long y) { return x < y; }
    public static boolean lt(double x, double y) { return x < y; }
    public static boolean lte(Object x, Object y) { return cljl.Numbers.lte(x, y); }
    public static boolean lte(Object x, long y) { return cljl.Numbers.lte(x, y); }
    public static boolean lte(long x, Object y) { return cljl.Numbers.lte(x, y); }
    public static boolean lte(long x, long y) { return x <= y; }
    public static boolean lte(double x, double y) { return x <= y; }
    public static boolean gt(Object x, Object y) { return cljl.Numbers.gt(x, y); }
    public static boolean gt(Object x, long y) { return cljl.Numbers.gt(x, y); }
    public static boolean gt(long x, Object y) { return cljl.Numbers.gt(x, y); }
    public static boolean gt(long x, long y) { return x > y; }
    public static boolean gt(double x, double y) { return x > y; }
    public static boolean gte(Object x, Object y) { return cljl.Numbers.gte(x, y); }
    public static boolean gte(Object x, long y) { return cljl.Numbers.gte(x, y); }
    public static boolean gte(long x, Object y) { return cljl.Numbers.gte(x, y); }
    public static boolean gte(long x, long y) { return x >= y; }
    public static boolean gte(double x, double y) { return x >= y; }

    public static Number abs(Object x) { return cljl.Numbers.abs(x); }
    public static long abs(long x) { return Math.abs(x); }
    public static double abs(double x) { return Math.abs(x); }
    public static long and(long x, long y) { return x & y; }
    public static long and(Object x, Object y) { return cljl.Numbers.toLong(x) & cljl.Numbers.toLong(y); }
    public static long or(long x, long y) { return x | y; }
    public static long or(Object x, Object y) { return cljl.Numbers.toLong(x) | cljl.Numbers.toLong(y); }
    public static long xor(long x, long y) { return x ^ y; }
    public static long xor(Object x, Object y) { return cljl.Numbers.toLong(x) ^ cljl.Numbers.toLong(y); }
    public static long not(long x) { return ~x; }
    public static long not(Object x) { return ~cljl.Numbers.toLong(x); }
    public static long shiftLeft(long x, long n) { return x << n; }
    public static long shiftLeft(Object x, Object n) { return cljl.Numbers.toLong(x) << cljl.Numbers.toLong(n); }
    public static long shiftRight(long x, long n) { return x >> n; }
    public static long shiftRight(Object x, Object n) { return cljl.Numbers.toLong(x) >> cljl.Numbers.toLong(n); }
    public static long unsignedShiftRight(long x, long n) { return x >>> n; }
    public static long unsignedShiftRight(Object x, Object n) { return cljl.Numbers.toLong(x) >>> cljl.Numbers.toLong(n); }

    public static cljl.lang.BigInt toBigInt(Object x) { return cljl.Numbers.toBigInt(x); }
    public static BigInteger toBigInteger(Object x) { return cljl.Numbers.toBigInteger(x); }
    public static BigDecimal toBigDecimal(Object x) { return cljl.Numbers.toBigDecimal(x); }
    public static long longCast(Object x) { return cljl.Numbers.longCast(x); }
    public static int intCast(Object x) { return cljl.Numbers.intCast(x); }
    public static double doubleCast(Object x) { return cljl.Numbers.toDouble(x); }
}
