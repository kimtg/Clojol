package cljl.lang;

import cljl.Numbers;
import cljl.RT;

/**
 * Static utility methods matching clojure.lang.Util.
 */
public final class Util {
    private Util() {}

    public static boolean equiv(Object k1, Object k2) {
        return RT.equiv(k1, k2);
    }

    public static boolean equiv(long k1, long k2) { return k1 == k2; }
    public static boolean equiv(double k1, double k2) { return k1 == k2; }
    public static boolean equiv(boolean k1, boolean k2) { return k1 == k2; }

    public static boolean equals(Object k1, Object k2) {
        if (k1 == k2) return true;
        return k1 != null && k1.equals(k2);
    }

    public static boolean identical(Object k1, Object k2) {
        return k1 == k2;
    }

    public static int hash(Object o) {
        return o == null ? 0 : o.hashCode();
    }

    public static int hasheq(Object o) {
        return RT.hash(o);
    }

    public static int hashCombine(int seed, int hash) {
        seed ^= hash + 0x9e3779b9 + (seed << 6) + (seed >>> 2);
        return seed;
    }

    public static int compare(Object k1, Object k2) {
        if (k1 == k2) return 0;
        if (k1 == null) return -1;
        if (k2 == null) return 1;
        if (k1 instanceof Number n1 && k2 instanceof Number n2) {
            return Numbers.compare(n1, n2);
        }
        @SuppressWarnings("unchecked")
        Comparable<Object> c1 = (Comparable<Object>) k1;
        return c1.compareTo(k2);
    }

    public static RuntimeException sneakyThrow(Throwable e) {
        return RT.sneakyThrow(e);
    }

    public static RuntimeException runtimeException(String s) {
        return new RuntimeException(s);
    }

    public static RuntimeException runtimeException(String s, Throwable e) {
        return new RuntimeException(s, e);
    }

    public static Object ret1(Object ret, Object nil) {
        return ret;
    }

    public static ISeq ret1(ISeq ret, Object nil) {
        return ret;
    }
}
