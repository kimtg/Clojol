package cljl.lang;

import cljl.Fn;
import cljl.RT;
import java.util.Arrays;
import java.util.Comparator;

/**
 * High-performance clean-room sort and sort-by implementations:
 * - O(n) pre-scan for already-sorted / strictly-reversed runs
 * - Adaptive 8-bit LSD radix sort (skipping uniform byte passes) for homogeneous Longs
 * - Monomorphic Comparators for homogeneous Doubles and Strings
 * - Schwartzian transform (keyfn evaluated once per element) + stable LSD radix / merge sort for sort-by
 */
public final class FastSort {

    private static final int RADIX_THRESHOLD = 256;

    private static final Comparator<Object> RT_CMP = new Comparator<>() {
        @Override
        public int compare(Object a, Object b) {
            return RT.compare(a, b);
        }
    };

    private static final Comparator<Object> STRING_CMP = new Comparator<>() {
        @Override
        public int compare(Object a, Object b) {
            return ((String) a).compareTo((String) b);
        }
    };

    private static final Comparator<Object> DOUBLE_CMP = new Comparator<>() {
        @Override
        public int compare(Object a, Object b) {
            return Double.compare(((Double) a).doubleValue(), ((Double) b).doubleValue());
        }
    };

    private FastSort() {}

    public static ISeq sort(Object coll) {
        if (coll instanceof LongArraySeq las) {
            int n = las.count();
            if (n == 0) return PersistentList.EMPTY;
            long[] vals = Arrays.copyOfRange(las.array, las.index, las.array.length);
            return sortLongArray(vals);
        }
        Object[] arr = RT.toArray(coll);
        int n = arr.length;
        if (n == 0) return PersistentList.EMPTY;
        if (n == 1) return ArraySeq.create(arr);

        Object first = arr[0];
        if (first instanceof Long l0) {
            boolean allLong = true;
            boolean asc = true;
            boolean desc = true;
            long prev = l0.longValue();
            for (int i = 1; i < n; i++) {
                Object o = arr[i];
                if (!(o instanceof Long li)) {
                    allLong = false;
                    break;
                }
                long cur = li.longValue();
                if (cur < prev) asc = false;
                if (cur >= prev) desc = false; // strictly descending guarantees stability on reverse
                prev = cur;
            }
            if (allLong) {
                if (asc) return ArraySeq.create(arr);
                if (desc) {
                    reverseInPlace(arr);
                    return ArraySeq.create(arr);
                }
                long[] vals = new long[n];
                for (int i = 0; i < n; i++) vals[i] = ((Long) arr[i]).longValue();
                return sortLongArray(vals);
            }
        } else if (first instanceof String s0) {
            boolean allStr = true;
            boolean asc = true;
            boolean desc = true;
            String prev = s0;
            for (int i = 1; i < n; i++) {
                Object o = arr[i];
                if (!(o instanceof String si)) {
                    allStr = false;
                    break;
                }
                int c = prev.compareTo(si);
                if (c > 0) asc = false;
                if (c <= 0) desc = false;
                prev = si;
            }
            if (allStr) {
                if (asc) return ArraySeq.create(arr);
                if (desc) {
                    reverseInPlace(arr);
                    return ArraySeq.create(arr);
                }
                Arrays.sort(arr, STRING_CMP);
                return ArraySeq.create(arr);
            }
        } else if (first instanceof Double d0 && !Double.isNaN(d0.doubleValue())) {
            boolean allDbl = true;
            boolean asc = true;
            boolean desc = true;
            double prev = d0.doubleValue();
            for (int i = 1; i < n; i++) {
                Object o = arr[i];
                if (!(o instanceof Double di)) {
                    allDbl = false;
                    break;
                }
                double cur = di.doubleValue();
                if (Double.isNaN(cur)) {
                    allDbl = false;
                    break;
                }
                if (cur < prev) asc = false;
                if (cur >= prev) desc = false;
                prev = cur;
            }
            if (allDbl) {
                if (asc) return ArraySeq.create(arr);
                if (desc) {
                    reverseInPlace(arr);
                    return ArraySeq.create(arr);
                }
                Arrays.sort(arr, DOUBLE_CMP);
                return ArraySeq.create(arr);
            }
        }

        Arrays.sort(arr, RT_CMP);
        return ArraySeq.create(arr);
    }

    public static ISeq sort(Comparator<Object> cmp, Object coll) {
        Object[] arr = RT.toArray(coll);
        if (arr.length == 0) return PersistentList.EMPTY;
        if (arr.length > 1) Arrays.sort(arr, cmp);
        return ArraySeq.create(arr);
    }

    public static ISeq sortBy(Fn keyfn, Object coll) {
        Object[] arr = RT.toArray(coll);
        int n = arr.length;
        if (n == 0) return PersistentList.EMPTY;
        if (n == 1) return ArraySeq.create(arr);

        Object k0 = keyfn.invoke(arr[0]);
        if (k0 instanceof Long l0) {
            long[] keys = new long[n];
            long prev = l0.longValue();
            keys[0] = prev;
            boolean allLong = true;
            boolean asc = true;
            int failIdx = -1;
            Object failKey = null;
            for (int i = 1; i < n; i++) {
                Object ki = keyfn.invoke(arr[i]);
                if (ki instanceof Long li) {
                    long v = li.longValue();
                    keys[i] = v;
                    if (v < prev) asc = false;
                    prev = v;
                } else {
                    allLong = false;
                    failIdx = i;
                    failKey = ki;
                    break;
                }
            }
            if (allLong) {
                if (asc) return ArraySeq.create(arr);
                if (n >= RADIX_THRESHOLD) {
                    radixSortByLongKey(keys, arr);
                } else {
                    mergeSortByLongKey(keys, arr);
                }
                return ArraySeq.create(arr);
            }
            // Mixed key types: copy already-extracted keys into Object[] and finish extraction
            Object[] objKeys = new Object[n];
            for (int i = 0; i < failIdx; i++) objKeys[i] = Long.valueOf(keys[i]);
            objKeys[failIdx] = failKey;
            for (int i = failIdx + 1; i < n; i++) objKeys[i] = keyfn.invoke(arr[i]);
            mergeSortByObjectKey(objKeys, arr, RT_CMP);
            return ArraySeq.create(arr);
        }

        Object[] objKeys = new Object[n];
        objKeys[0] = k0;
        boolean allStr = (k0 instanceof String);
        for (int i = 1; i < n; i++) {
            Object ki = keyfn.invoke(arr[i]);
            objKeys[i] = ki;
            if (allStr && !(ki instanceof String)) allStr = false;
        }
        mergeSortByObjectKey(objKeys, arr, allStr ? STRING_CMP : RT_CMP);
        return ArraySeq.create(arr);
    }

    public static ISeq sortBy(Fn keyfn, Comparator<Object> cmp, Object coll) {
        Object[] arr = RT.toArray(coll);
        int n = arr.length;
        if (n == 0) return PersistentList.EMPTY;
        if (n == 1) return ArraySeq.create(arr);

        Object[] objKeys = new Object[n];
        for (int i = 0; i < n; i++) {
            objKeys[i] = keyfn.invoke(arr[i]);
        }
        mergeSortByObjectKey(objKeys, arr, cmp);
        return ArraySeq.create(arr);
    }

    private static ISeq sortLongArray(long[] vals) {
        int n = vals.length;
        if (n < RADIX_THRESHOLD) {
            Arrays.sort(vals);
            return LongArraySeq.create(vals);
        }
        long firstKey = vals[0] ^ Long.MIN_VALUE;
        long diffMask = 0L;
        for (int i = 1; i < n; i++) {
            diffMask |= (vals[i] ^ Long.MIN_VALUE) ^ firstKey;
        }
        if (diffMask == 0L) {
            return LongArraySeq.create(vals);
        }
        long[] src = vals;
        long[] dst = new long[n];
        int[] counts = new int[256];
        for (int shift = 0; shift < 64; shift += 8) {
            if (((diffMask >>> shift) & 0xFFL) == 0L) continue;
            Arrays.fill(counts, 0);
            if (shift == 56) {
                for (int i = 0; i < n; i++) counts[(int) ((src[i] >>> 56) ^ 0x80L)]++;
                int sum = 0;
                for (int b = 0; b < 256; b++) { int c = counts[b]; counts[b] = sum; sum += c; }
                for (int i = 0; i < n; i++) {
                    long v = src[i];
                    dst[counts[(int) ((v >>> 56) ^ 0x80L)]++] = v;
                }
            } else {
                for (int i = 0; i < n; i++) counts[(int) ((src[i] >>> shift) & 0xFFL)]++;
                int sum = 0;
                for (int b = 0; b < 256; b++) { int c = counts[b]; counts[b] = sum; sum += c; }
                for (int i = 0; i < n; i++) {
                    long v = src[i];
                    dst[counts[(int) ((v >>> shift) & 0xFFL)]++] = v;
                }
            }
            long[] tmp = src; src = dst; dst = tmp;
        }
        return LongArraySeq.create(src);
    }

    /** Stable adaptive 8-bit LSD radix sort ordering arr by signed long keys. */
    private static void radixSortByLongKey(long[] keys, Object[] arr) {
        int n = keys.length;
        long firstKey = keys[0] ^ Long.MIN_VALUE;
        long diffMask = 0L;
        for (int i = 1; i < n; i++) {
            diffMask |= (keys[i] ^ Long.MIN_VALUE) ^ firstKey;
        }
        if (diffMask == 0L) return;

        long[] kSrc = keys, kDst = new long[n];
        Object[] vSrc = arr, vDst = new Object[n];
        int[] counts = new int[256];
        for (int shift = 0; shift < 64; shift += 8) {
            if (((diffMask >>> shift) & 0xFFL) == 0L) continue;
            Arrays.fill(counts, 0);
            if (shift == 56) {
                for (int i = 0; i < n; i++) counts[(int) ((kSrc[i] >>> 56) ^ 0x80L)]++;
                int sum = 0;
                for (int b = 0; b < 256; b++) { int c = counts[b]; counts[b] = sum; sum += c; }
                for (int i = 0; i < n; i++) {
                    long k = kSrc[i];
                    int pos = counts[(int) ((k >>> 56) ^ 0x80L)]++;
                    kDst[pos] = k;
                    vDst[pos] = vSrc[i];
                }
            } else {
                for (int i = 0; i < n; i++) counts[(int) ((kSrc[i] >>> shift) & 0xFFL)]++;
                int sum = 0;
                for (int b = 0; b < 256; b++) { int c = counts[b]; counts[b] = sum; sum += c; }
                for (int i = 0; i < n; i++) {
                    long k = kSrc[i];
                    int pos = counts[(int) ((k >>> shift) & 0xFFL)]++;
                    kDst[pos] = k;
                    vDst[pos] = vSrc[i];
                }
            }
            long[] kt = kSrc; kSrc = kDst; kDst = kt;
            Object[] vt = vSrc; vSrc = vDst; vDst = vt;
        }
        if (vSrc != arr) {
            System.arraycopy(vSrc, 0, arr, 0, n);
        }
    }

    /** Stable bottom-up merge sort for small (long[] keys, Object[] arr) pairs. */
    private static void mergeSortByLongKey(long[] keys, Object[] arr) {
        int n = keys.length;
        long[] kAux = new long[n];
        Object[] vAux = new Object[n];
        // Insertion sort runs of 16
        for (int lo = 0; lo < n; lo += 16) {
            int hi = Math.min(lo + 16, n);
            for (int i = lo + 1; i < hi; i++) {
                long k = keys[i];
                Object v = arr[i];
                int j = i - 1;
                while (j >= lo && keys[j] > k) {
                    keys[j + 1] = keys[j];
                    arr[j + 1] = arr[j];
                    j--;
                }
                keys[j + 1] = k;
                arr[j + 1] = v;
            }
        }
        for (int width = 16; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + (width << 1), n);
                if (mid >= hi || keys[mid - 1] <= keys[mid]) continue;
                int i = lo, j = mid, out = lo;
                while (i < mid && j < hi) {
                    if (keys[i] <= keys[j]) {
                        kAux[out] = keys[i]; vAux[out++] = arr[i++];
                    } else {
                        kAux[out] = keys[j]; vAux[out++] = arr[j++];
                    }
                }
                while (i < mid) { kAux[out] = keys[i]; vAux[out++] = arr[i++]; }
                while (j < hi) { kAux[out] = keys[j]; vAux[out++] = arr[j++]; }
                System.arraycopy(kAux, lo, keys, lo, hi - lo);
                System.arraycopy(vAux, lo, arr, lo, hi - lo);
            }
        }
    }

    /** Stable bottom-up merge sort ordering arr by pre-extracted Object[] keys using cmp. */
    private static void mergeSortByObjectKey(Object[] keys, Object[] arr, Comparator<Object> cmp) {
        int n = keys.length;
        Object[] kAux = new Object[n];
        Object[] vAux = new Object[n];
        for (int lo = 0; lo < n; lo += 16) {
            int hi = Math.min(lo + 16, n);
            for (int i = lo + 1; i < hi; i++) {
                Object k = keys[i];
                Object v = arr[i];
                int j = i - 1;
                while (j >= lo && cmp.compare(keys[j], k) > 0) {
                    keys[j + 1] = keys[j];
                    arr[j + 1] = arr[j];
                    j--;
                }
                keys[j + 1] = k;
                arr[j + 1] = v;
            }
        }
        for (int width = 16; width < n; width <<= 1) {
            for (int lo = 0; lo < n; lo += width << 1) {
                int mid = Math.min(lo + width, n);
                int hi = Math.min(lo + (width << 1), n);
                if (mid >= hi || cmp.compare(keys[mid - 1], keys[mid]) <= 0) continue;
                int i = lo, j = mid, out = lo;
                while (i < mid && j < hi) {
                    if (cmp.compare(keys[i], keys[j]) <= 0) {
                        kAux[out] = keys[i]; vAux[out++] = arr[i++];
                    } else {
                        kAux[out] = keys[j]; vAux[out++] = arr[j++];
                    }
                }
                while (i < mid) { kAux[out] = keys[i]; vAux[out++] = arr[i++]; }
                while (j < hi) { kAux[out] = keys[j]; vAux[out++] = arr[j++]; }
                System.arraycopy(kAux, lo, keys, lo, hi - lo);
                System.arraycopy(vAux, lo, arr, lo, hi - lo);
            }
        }
    }

    private static void reverseInPlace(Object[] arr) {
        for (int l = 0, r = arr.length - 1; l < r; l++, r--) {
            Object tmp = arr[l];
            arr[l] = arr[r];
            arr[r] = tmp;
        }
    }
}
