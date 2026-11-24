package cljl;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Bounded, lock-free, JMM-safe memoization tables used by BytecodeJIT for provably
 * pure multi-branch self-recursive functions (e.g. Fibonacci, Tribonacci, Padovan,
 * Pell, binomial/choose, grid paths, Takeuchi, auto-promoting BigInt recurrences).
 *
 * Thread-safety & memory guarantees:
 * - Dense primitive tables use AtomicLongArray with opaque reads/writes (no word tearing,
 *   no locks, zero barriers on x86-64/ARM64) and Long.MIN_VALUE as the empty sentinel
 *   (legitimate Long.MIN_VALUE results are simply not cached so exactness is preserved).
 * - Overflow / multi-arity / boxed-numeric tables use fixed-size arrays of immutable
 *   Entry records with final fields, guaranteeing JMM safe publication (JLS §17.5) and
 *   strictly bounded memory footprint with zero leaks.
 * - put(...) is invoked only after normal return, so exceptions (e.g. ArithmeticException
 *   on long overflow) are never cached.
 */
public final class PureMemo {
    public static final long MISS = Long.MIN_VALUE;
    public static final Object MISS_OBJ = new Object();

    private PureMemo() {}

    private static int mix1(long k) {
        long z = k * 0x9e3779b97f4a7c15L;
        z ^= z >>> 30;
        z *= 0xbf58476d1ce4e5b9L;
        z ^= z >>> 27;
        return (int) (z ^ (z >>> 31));
    }

    private static int mix2(long a, long b) {
        return mix1(a ^ Long.rotateLeft(b * 0x9e3779b97f4a7c15L, 21));
    }

    private static int mix3(long a, long b, long c) {
        long h = a * 0x9e3779b97f4a7c15L;
        h ^= Long.rotateLeft(b * 0xbf58476d1ce4e5b9L, 19);
        h ^= Long.rotateLeft(c * 0x94d049bb133111ebL, 37);
        return mix1(h);
    }

    /** 1-arg long -> long: O(1) dense cache for 0 <= k < 4096 + bounded 4096-slot overflow cache. */
    public static final class Table1 {
        private static final int DENSE_SIZE = 4096;
        private static final int HASH_MASK = 4095;
        private final AtomicLongArray dense;
        private volatile Entry1[] overflow;

        private static final class Entry1 {
            final long k;
            final long v;
            Entry1(long k, long v) {
                this.k = k;
                this.v = v;
            }
        }

        public Table1() {
            AtomicLongArray a = new AtomicLongArray(DENSE_SIZE);
            for (int i = 0; i < DENSE_SIZE; i++) {
                a.setPlain(i, MISS);
            }
            this.dense = a;
        }

        public long get(long k) {
            if ((k & ~4095L) == 0L) {
                return dense.getOpaque((int) k);
            }
            Entry1[] ov = this.overflow;
            if (ov != null) {
                Entry1 e = ov[mix1(k) & HASH_MASK];
                if (e != null && e.k == k) return e.v;
            }
            return MISS;
        }

        public long put(long k, long v) {
            if (v != MISS) {
                if ((k & ~4095L) == 0L) {
                    dense.setOpaque((int) k, v);
                } else {
                    Entry1[] ov = this.overflow;
                    if (ov == null) {
                        ov = new Entry1[HASH_MASK + 1];
                        this.overflow = ov;
                    }
                    ov[mix1(k) & HASH_MASK] = new Entry1(k, v);
                }
            }
            return v;
        }
    }

    /** 2-arg (long, long) -> long: dense 128x128 cache for 0 <= a,b < 128 + bounded 8192-slot overflow cache. */
    public static final class Table2 {
        private static final int DENSE_SIZE = 128 * 128;
        private static final int HASH_MASK = 8191;
        private final AtomicLongArray dense;
        private volatile Entry2[] overflow;

        private static final class Entry2 {
            final long k1;
            final long k2;
            final long v;
            Entry2(long k1, long k2, long v) {
                this.k1 = k1;
                this.k2 = k2;
                this.v = v;
            }
        }

        public Table2() {
            AtomicLongArray a = new AtomicLongArray(DENSE_SIZE);
            for (int i = 0; i < DENSE_SIZE; i++) {
                a.setPlain(i, MISS);
            }
            this.dense = a;
        }

        public long get(long a, long b) {
            if (((a | b) & ~127L) == 0L) {
                return dense.getOpaque(((int) a << 7) | (int) b);
            }
            Entry2[] ov = this.overflow;
            if (ov != null) {
                Entry2 e = ov[mix2(a, b) & HASH_MASK];
                if (e != null && e.k1 == a && e.k2 == b) return e.v;
            }
            return MISS;
        }

        public long put(long a, long b, long v) {
            if (v != MISS) {
                if (((a | b) & ~127L) == 0L) {
                    dense.setOpaque(((int) a << 7) | (int) b, v);
                } else {
                    Entry2[] ov = this.overflow;
                    if (ov == null) {
                        ov = new Entry2[HASH_MASK + 1];
                        this.overflow = ov;
                    }
                    ov[mix2(a, b) & HASH_MASK] = new Entry2(a, b, v);
                }
            }
            return v;
        }
    }

    /** 3-arg (long, long, long) -> long: bounded 8192-slot JMM-safe cache. */
    public static final class Table3 {
        private static final int HASH_MASK = 8191;
        private final Entry3[] table = new Entry3[HASH_MASK + 1];

        private static final class Entry3 {
            final long k1;
            final long k2;
            final long k3;
            final long v;
            Entry3(long k1, long k2, long k3, long v) {
                this.k1 = k1;
                this.k2 = k2;
                this.k3 = k3;
                this.v = v;
            }
        }

        public long get(long a, long b, long c) {
            Entry3 e = table[mix3(a, b, c) & HASH_MASK];
            return (e != null && e.k1 == a && e.k2 == b && e.k3 == c) ? e.v : MISS;
        }

        public long put(long a, long b, long c, long v) {
            if (v != MISS) {
                table[mix3(a, b, c) & HASH_MASK] = new Entry3(a, b, c, v);
            }
            return v;
        }
    }

    /**
     * 1-arg primitive long -> Object cache for pure auto-promoting multi-branch
     * recursive functions (e.g. fib with +' returning Long/BigInteger).
     */
    public static final class TableLongToObj {
        private static final int DENSE_SIZE = 4096;
        private static final int HASH_MASK = 4095;
        private final Object[] dense;
        private volatile EntryLO[] overflow;

        private static final class EntryLO {
            final long k;
            final Object v;
            EntryLO(long k, Object v) {
                this.k = k;
                this.v = v;
            }
        }

        public TableLongToObj() {
            Object[] a = new Object[DENSE_SIZE];
            java.util.Arrays.fill(a, MISS_OBJ);
            this.dense = a;
        }

        public Object get(long k) {
            if ((k & ~4095L) == 0L) {
                return dense[(int) k];
            }
            EntryLO[] ov = this.overflow;
            if (ov != null) {
                EntryLO e = ov[mix1(k) & HASH_MASK];
                if (e != null && e.k == k) return e.v;
            }
            return MISS_OBJ;
        }

        public Object put(long k, Object v) {
            if ((k & ~4095L) == 0L) {
                dense[(int) k] = v;
            } else {
                EntryLO[] ov = this.overflow;
                if (ov == null) {
                    ov = new EntryLO[HASH_MASK + 1];
                    this.overflow = ov;
                }
                ov[mix1(k) & HASH_MASK] = new EntryLO(k, v);
            }
            return v;
        }
    }

    /**
     * 1-arg immutable numeric (Long / BigInteger) -> Object cache for pure auto-promoting
     * multi-branch recursive functions (e.g. fib with +' or BigInt arguments).
     */
    public static final class TableObj1 {
        private static final int HASH_MASK = 4095;
        private final EntryObj1[] table = new EntryObj1[HASH_MASK + 1];

        private static final class EntryObj1 {
            final Object k;
            final Object v;
            EntryObj1(Object k, Object v) {
                this.k = k;
                this.v = v;
            }
        }

        public Object get(Object k) {
            if (!(k instanceof Long || k instanceof cljl.lang.BigInt || k instanceof BigInteger)) return MISS_OBJ;
            int idx = mix1(k.hashCode()) & HASH_MASK;
            EntryObj1 e = table[idx];
            if (e != null && e.k.getClass() == k.getClass() && e.k.equals(k)) {
                return e.v;
            }
            return MISS_OBJ;
        }

        public Object put(Object k, Object v) {
            if (k instanceof Long || k instanceof cljl.lang.BigInt || k instanceof BigInteger) {
                int idx = mix1(k.hashCode()) & HASH_MASK;
                table[idx] = new EntryObj1(k, v);
            }
            return v;
        }
    }
}
