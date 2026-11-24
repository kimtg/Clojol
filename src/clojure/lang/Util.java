package clojure.lang;

/**
 * Clean-room clojure.lang.Util static bridge delegating to cljl.lang.Util and cljl.RT.
 */
public final class Util {
    private Util() {}

    public static boolean equiv(Object k1, Object k2) { return cljl.lang.Util.equiv(k1, k2); }
    public static boolean equiv(long k1, long k2) { return k1 == k2; }
    public static boolean equiv(double k1, double k2) { return k1 == k2; }
    public static boolean equiv(boolean k1, boolean k2) { return k1 == k2; }
    public static boolean equals(Object k1, Object k2) { return cljl.lang.Util.equals(k1, k2); }
    public static boolean identical(Object k1, Object k2) { return k1 == k2; }
    public static int hash(Object o) { return cljl.lang.Util.hash(o); }
    public static int hasheq(Object o) { return cljl.lang.Util.hasheq(o); }
    public static int hashCombine(int seed, int hash) { return cljl.lang.Util.hashCombine(seed, hash); }
    public static int compare(Object k1, Object k2) { return cljl.lang.Util.compare(k1, k2); }
    public static RuntimeException sneakyThrow(Throwable e) { return cljl.lang.Util.sneakyThrow(e); }
    public static RuntimeException runtimeException(String s) { return new RuntimeException(s); }
    public static RuntimeException runtimeException(String s, Throwable e) { return new RuntimeException(s, e); }
    public static Object ret1(Object ret, Object nil) { return ret; }
    public static ISeq ret1(ISeq ret, Object nil) { return ret; }
}
