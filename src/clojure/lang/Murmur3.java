package clojure.lang;

/**
 * Clean-room clojure.lang.Murmur3 static bridge delegating to cljl.lang.Murmur3.
 */
public final class Murmur3 {
    private Murmur3() {}

    public static int hashInt(int input) { return cljl.lang.Murmur3.hashInt(input); }
    public static int hashLong(long input) { return cljl.lang.Murmur3.hashLong(input); }
    public static int hashUnencodedChars(CharSequence input) { return cljl.lang.Murmur3.hashUnencodedChars(input); }
    public static int mixCollHash(int hash, int count) { return cljl.lang.Murmur3.mixCollHash(hash, count); }
    public static int hashOrdered(Iterable<?> xs) { return cljl.lang.Murmur3.hashOrdered(xs); }
    public static int hashUnordered(Iterable<?> xs) { return cljl.lang.Murmur3.hashUnordered(xs); }
}
