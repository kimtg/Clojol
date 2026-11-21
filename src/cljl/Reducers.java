package cljl;

import cljl.lang.*;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

/**
 * Clean-room implementation of clojure.core.reducers with ForkJoin parallel folding
 * and unboxed primitive long fast paths over LongRange, PersistentVector, SubVec, and LongArraySeq.
 * Lazily loaded via StdLibMore.ensureLoaded("clojure.core.reducers") (zero cold startup impact).
 */
public final class Reducers {

    private static final int DEFAULT_FOLD_N = 512;

    private Reducers() {}

    /** Internal interface for reducible collections created by r/reducer and r/folder. */
    public static class ReducibleColl implements IReduceInit {
        final Object coll;
        final Fn xform;

        ReducibleColl(Object coll, Fn xform) {
            this.coll = coll;
            this.xform = xform;
        }

        @Override
        public Object reduce(Fn f, Object init) {
            Fn rf = (Fn) xform.invoke(f);
            return reduceColl(rf, init, coll);
        }
    }

    /** Internal class for foldable collections created by r/folder. */
    public static final class FoldableColl extends ReducibleColl {
        FoldableColl(Object coll, Fn xform) {
            super(coll, xform);
        }

        public Object fold(int n, Fn combinef, Fn reducef) {
            Fn rf = (Fn) xform.invoke(reducef);
            return foldColl(n, combinef, rf, coll);
        }
    }

    /** Parallel tree-concatenated collection produced by r/cat and r/foldcat. */
    public static final class CatColl implements Counted, Seqable, IReduceInit {
        final int cnt;
        final Object left;
        final Object right;

        CatColl(int cnt, Object left, Object right) {
            this.cnt = cnt;
            this.left = left;
            this.right = right;
        }

        @Override
        public int count() {
            return cnt;
        }

        @Override
        public ISeq seq() {
            List<Object> out = new ArrayList<>(cnt);
            collectInto(this, out);
            return out.isEmpty() ? null : ArraySeq.create(out.toArray());
        }

        private static void collectInto(Object node, List<Object> out) {
            if (node instanceof CatColl c) {
                collectInto(c.left, out);
                collectInto(c.right, out);
            } else if (node instanceof List<?> list) {
                out.addAll(list);
            } else if (node != null) {
                for (ISeq s = RT.seq(node); s != null; s = s.next()) out.add(s.first());
            }
        }

        @Override
        public Object reduce(Fn f, Object init) {
            Object acc = reduceNode(f, init, left);
            if (acc instanceof Reduced r) return r.deref();
            acc = reduceNode(f, acc, right);
            return acc instanceof Reduced r ? r.deref() : acc;
        }

        private static Object reduceNode(Fn f, Object acc, Object node) {
            if (node instanceof CatColl c) {
                acc = reduceNode(f, acc, c.left);
                if (acc instanceof Reduced) return acc;
                return reduceNode(f, acc, c.right);
            }
            if (node instanceof List<?> list) {
                for (int i = 0, n = list.size(); i < n; i++) {
                    acc = f.invoke(acc, list.get(i));
                    if (acc instanceof Reduced) return acc;
                }
                return acc;
            }
            return Core.reduce3(f, acc, node);
        }
    }

    private static Fn unwrapJit(Fn f) {
        return (f instanceof Compiler.CompiledFn cf) ? cf.ensureJit() : f;
    }

    public static Object reduceColl(Fn f, Object init, Object coll) {
        if (coll == null) return init;
        if (coll instanceof ReducibleColl rc) return rc.reduce(f, init);
        return Core.reduce3(unwrapJit(f), init, coll);
    }

    public static Object foldColl(int n, Fn combinef, Fn reducef, Object coll) {
        if (coll == null) return combinef.invoke();
        if (coll instanceof FoldableColl fc) {
            return fc.fold(n, combinef, reducef);
        }
        if (coll instanceof ReducibleColl rc) {
            // Non-foldable reducer (e.g. take/drop/take-while): sequential reduce
            return rc.reduce(reducef, combinef.invoke());
        }
        Fn cf = unwrapJit(combinef);
        Fn rf = unwrapJit(reducef);
        int thresh = Math.max(1, n);

        if (coll instanceof LongRange lr) {
            int cnt = lr.count();
            if (cnt == 0) return cf.invoke();
            if (cnt <= thresh) {
                return lr.reduce(rf, cf.invoke());
            }
            return foldLongRange(lr.start, lr.step, cnt, thresh, cf, rf);
        }
        if (coll instanceof PersistentVector pv) {
            int cnt = pv.cnt;
            if (cnt == 0) return cf.invoke();
            if (cnt <= thresh) {
                return reduceVectorSlice(rf, cf.invoke(), pv, 0, cnt);
            }
            return foldVector(pv, 0, cnt, thresh, cf, rf);
        }
        if (coll instanceof PersistentVector.SubVec sv) {
            int cnt = sv.end - sv.start;
            if (cnt == 0) return cf.invoke();
            if (cnt <= thresh) {
                return reduceVectorSlice(rf, cf.invoke(), sv.v, sv.start, sv.end);
            }
            return foldVector(sv.v, sv.start, sv.end, thresh, cf, rf);
        }
        if (coll instanceof LongArraySeq las) {
            int cnt = las.count();
            if (cnt == 0) return cf.invoke();
            if (cnt <= thresh) {
                return las.reduce(rf, cf.invoke());
            }
            return foldLongArray(las.array, las.index, las.array.length, thresh, cf, rf);
        }
        if (coll instanceof ArraySeq as) {
            int cnt = as.count();
            if (cnt == 0) return cf.invoke();
            if (cnt <= thresh) {
                return as.reduce(rf, cf.invoke());
            }
            return foldObjectArray(as.array, as.i, as.array.length, thresh, cf, rf);
        }
        return Core.reduce3(rf, cf.invoke(), coll);
    }

    private static int effectiveGrain(int total, int userN) {
        int par = Math.max(1, ForkJoinPool.getCommonPoolParallelism());
        int target = total / (par * 4);
        return Math.max(userN, Math.min(target, 65536));
    }

    private static Object foldLongRange(long start, long step, int count, int n, Fn cf, Fn rf) {
        int grain = effectiveGrain(count, n);
        if (count <= grain) {
            return reduceLongRangeSlice(rf, cf.invoke(), start, step, count);
        }
        Atom.markMultiThreaded();
        if (cf.isUnboxedLong2() && rf.isUnboxedLong2()) {
            Object initObj = cf.invoke();
            if (initObj instanceof Long initL) {
                long idVal = initL.longValue();
                long res = ForkJoinPool.commonPool().invoke(
                        new LongRangePrimTask(start, step, count, grain, idVal, cf, rf));
                return Long.valueOf(res);
            }
        }
        return ForkJoinPool.commonPool().invoke(new LongRangeBoxedTask(start, step, count, grain, cf, rf));
    }

    private static final class LongRangePrimTask extends RecursiveTask<Long> {
        final long start;
        final long step;
        final int count;
        final int grain;
        final long initVal;
        final Fn cf;
        final Fn rf;

        LongRangePrimTask(long start, long step, int count, int grain, long initVal, Fn cf, Fn rf) {
            this.start = start;
            this.step = step;
            this.count = count;
            this.grain = grain;
            this.initVal = initVal;
            this.cf = cf;
            this.rf = rf;
        }

        @Override
        protected Long compute() {
            if (count <= grain) {
                long acc = initVal;
                long v = start;
                long st = step;
                Fn r = rf;
                for (int i = 0, c = count; i < c; i++) {
                    acc = r.invokePrim_JJ_J(acc, v);
                    v += st;
                }
                return Long.valueOf(acc);
            }
            int half = count >>> 1;
            LongRangePrimTask left = new LongRangePrimTask(start, step, half, grain, initVal, cf, rf);
            LongRangePrimTask right = new LongRangePrimTask(start + step * half, step, count - half, grain, initVal, cf, rf);
            left.fork();
            long rv = right.compute().longValue();
            long lv = left.join().longValue();
            return Long.valueOf(cf.invokePrim_JJ_J(lv, rv));
        }
    }

    private static final class LongRangeBoxedTask extends RecursiveTask<Object> {
        final long start;
        final long step;
        final int count;
        final int grain;
        final Fn cf;
        final Fn rf;

        LongRangeBoxedTask(long start, long step, int count, int grain, Fn cf, Fn rf) {
            this.start = start;
            this.step = step;
            this.count = count;
            this.grain = grain;
            this.cf = cf;
            this.rf = rf;
        }

        @Override
        protected Object compute() {
            if (count <= grain) {
                return reduceLongRangeSlice(rf, cf.invoke(), start, step, count);
            }
            int half = count >>> 1;
            LongRangeBoxedTask left = new LongRangeBoxedTask(start, step, half, grain, cf, rf);
            LongRangeBoxedTask right = new LongRangeBoxedTask(start + step * half, step, count - half, grain, cf, rf);
            left.fork();
            Object rv = right.compute();
            Object lv = left.join();
            return cf.invoke(lv, rv);
        }
    }

    private static Object reduceLongRangeSlice(Fn rf, Object init, long start, long step, int count) {
        if (rf.isUnboxedLong2() && init instanceof Long l) {
            long acc = l.longValue();
            long v = start;
            for (int i = 0; i < count; i++) {
                acc = rf.invokePrim_JJ_J(acc, v);
                v += step;
            }
            return Long.valueOf(acc);
        }
        Object acc = init;
        long v = start;
        for (int i = 0; i < count; i++) {
            acc = rf.invokePrim_OJ_O(acc, v);
            if (acc instanceof Reduced r) return r.deref();
            v += step;
        }
        return acc;
    }

    private static Object foldVector(PersistentVector pv, int start, int end, int n, Fn cf, Fn rf) {
        int count = end - start;
        int grain = effectiveGrain(count, n);
        if (count <= grain) {
            return reduceVectorSlice(rf, cf.invoke(), pv, start, end);
        }
        Atom.markMultiThreaded();
        if (cf.isUnboxedLong2() && rf.isUnboxedLong2()) {
            Object initObj = cf.invoke();
            if (initObj instanceof Long initL) {
                return ForkJoinPool.commonPool().invoke(
                        new VectorPrimTask(pv, start, end, grain, initL.longValue(), cf, rf));
            }
        }
        return ForkJoinPool.commonPool().invoke(new VectorBoxedTask(pv, start, end, grain, cf, rf));
    }

    private static Object reduceVectorSlice(Fn rf, Object init, PersistentVector pv, int start, int end) {
        if (rf.isUnboxedLong2() && init instanceof Long l) {
            long acc = l.longValue();
            int i = start;
            while (i < end) {
                Object[] chunk = pv.arrayFor(i);
                int off = i & 0x1f;
                int limit = Math.min(chunk.length, off + (end - i));
                for (int j = off; j < limit; j++) {
                    Object elem = chunk[j];
                    if (elem instanceof Long lv) {
                        acc = rf.invokePrim_JJ_J(acc, lv.longValue());
                    } else {
                        Object bAcc = rf.invoke(Long.valueOf(acc), elem);
                        if (bAcc instanceof Reduced r) return r.deref();
                        return finishReduceVectorSlice(rf, bAcc, pv, i + (j - off) + 1, end);
                    }
                }
                i += (limit - off);
            }
            return Long.valueOf(acc);
        }
        return finishReduceVectorSlice(rf, init, pv, start, end);
    }

    private static Object finishReduceVectorSlice(Fn rf, Object acc, PersistentVector pv, int start, int end) {
        int i = start;
        while (i < end) {
            Object[] chunk = pv.arrayFor(i);
            int off = i & 0x1f;
            int limit = Math.min(chunk.length, off + (end - i));
            for (int j = off; j < limit; j++) {
                Object elem = chunk[j];
                acc = (elem instanceof Long lv)
                        ? rf.invokePrim_OJ_O(acc, lv.longValue())
                        : rf.invoke(acc, elem);
                if (acc instanceof Reduced r) return r.deref();
            }
            i += (limit - off);
        }
        return acc;
    }

    private static final class VectorPrimTask extends RecursiveTask<Object> {
        final PersistentVector pv;
        final int start, end, grain;
        final long initVal;
        final Fn cf, rf;

        VectorPrimTask(PersistentVector pv, int start, int end, int grain, long initVal, Fn cf, Fn rf) {
            this.pv = pv; this.start = start; this.end = end; this.grain = grain;
            this.initVal = initVal; this.cf = cf; this.rf = rf;
        }

        @Override
        protected Object compute() {
            if (end - start <= grain) {
                return reduceVectorSlice(rf, Long.valueOf(initVal), pv, start, end);
            }
            int mid = (start + end) >>> 1;
            VectorPrimTask left = new VectorPrimTask(pv, start, mid, grain, initVal, cf, rf);
            VectorPrimTask right = new VectorPrimTask(pv, mid, end, grain, initVal, cf, rf);
            left.fork();
            Object rv = right.compute();
            Object lv = left.join();
            if (lv instanceof Long ll && rv instanceof Long rl) {
                return Long.valueOf(cf.invokePrim_JJ_J(ll.longValue(), rl.longValue()));
            }
            return cf.invoke(lv, rv);
        }
    }

    private static final class VectorBoxedTask extends RecursiveTask<Object> {
        final PersistentVector pv;
        final int start, end, grain;
        final Fn cf, rf;

        VectorBoxedTask(PersistentVector pv, int start, int end, int grain, Fn cf, Fn rf) {
            this.pv = pv; this.start = start; this.end = end; this.grain = grain;
            this.cf = cf; this.rf = rf;
        }

        @Override
        protected Object compute() {
            if (end - start <= grain) {
                return reduceVectorSlice(rf, cf.invoke(), pv, start, end);
            }
            int mid = (start + end) >>> 1;
            VectorBoxedTask left = new VectorBoxedTask(pv, start, mid, grain, cf, rf);
            VectorBoxedTask right = new VectorBoxedTask(pv, mid, end, grain, cf, rf);
            left.fork();
            Object rv = right.compute();
            Object lv = left.join();
            return cf.invoke(lv, rv);
        }
    }

    private static Object foldLongArray(long[] arr, int start, int end, int n, Fn cf, Fn rf) {
        int grain = effectiveGrain(end - start, n);
        Atom.markMultiThreaded();
        if (cf.isUnboxedLong2() && rf.isUnboxedLong2() && cf.invoke() instanceof Long initL) {
            long idVal = initL.longValue();
            return Long.valueOf(ForkJoinPool.commonPool().invoke(new RecursiveTask<Long>() {
                private long computeRange(int lo, int hi) {
                    if (hi - lo <= grain) {
                        long acc = idVal;
                        for (int i = lo; i < hi; i++) acc = rf.invokePrim_JJ_J(acc, arr[i]);
                        return acc;
                    }
                    int mid = (lo + hi) >>> 1;
                    RecursiveTask<Long> left = new RecursiveTask<>() {
                        @Override protected Long compute() { return Long.valueOf(computeRange(lo, mid)); }
                    };
                    left.fork();
                    long rv = computeRange(mid, hi);
                    return cf.invokePrim_JJ_J(left.join().longValue(), rv);
                }
                @Override protected Long compute() { return Long.valueOf(computeRange(start, end)); }
            }));
        }
        return ForkJoinPool.commonPool().invoke(new RecursiveTask<Object>() {
            private Object computeRange(int lo, int hi) {
                if (hi - lo <= grain) {
                    Object acc = cf.invoke();
                    for (int i = lo; i < hi; i++) {
                        acc = rf.invokePrim_OJ_O(acc, arr[i]);
                        if (acc instanceof Reduced r) return r.deref();
                    }
                    return acc;
                }
                int mid = (lo + hi) >>> 1;
                RecursiveTask<Object> left = new RecursiveTask<>() {
                    @Override protected Object compute() { return computeRange(lo, mid); }
                };
                left.fork();
                Object rv = computeRange(mid, hi);
                return cf.invoke(left.join(), rv);
            }
            @Override protected Object compute() { return computeRange(start, end); }
        });
    }

    private static Object foldObjectArray(Object[] arr, int start, int end, int n, Fn cf, Fn rf) {
        int grain = effectiveGrain(end - start, n);
        Atom.markMultiThreaded();
        return ForkJoinPool.commonPool().invoke(new RecursiveTask<Object>() {
            private Object computeRange(int lo, int hi) {
                if (hi - lo <= grain) {
                    Object acc = cf.invoke();
                    for (int i = lo; i < hi; i++) {
                        acc = rf.invoke(acc, arr[i]);
                        if (acc instanceof Reduced r) return r.deref();
                    }
                    return acc;
                }
                int mid = (lo + hi) >>> 1;
                RecursiveTask<Object> left = new RecursiveTask<>() {
                    @Override protected Object compute() { return computeRange(lo, mid); }
                };
                left.fork();
                Object rv = computeRange(mid, hi);
                return cf.invoke(left.join(), rv);
            }
            @Override protected Object compute() { return computeRange(start, end); }
        });
    }

    // ========================================================================
    // Namespace Registration: clojure.core.reducers
    // ========================================================================

    public static void initNamespace() {
        Namespace ns = Namespace.findOrCreate("clojure.core.reducers");

        Protocol collReduceProto = new Protocol(Symbol.intern("clojure.core.reducers", "CollReduce"),
                "Protocol for reducible collections", null);
        ns.intern("CollReduce", collReduceProto);
        ns.intern("coll-reduce", new Fn.NamedFn("coll-reduce") {
            @Override public Object invoke(Object coll, Object f) {
                return invoke(coll, f, ((Fn) f).invoke());
            }
            @Override public Object invoke(Object coll, Object f, Object init) {
                return reduceColl((Fn) f, init, coll);
            }
        });

        Protocol collFoldProto = new Protocol(Symbol.intern("clojure.core.reducers", "CollFold"),
                "Protocol for parallel foldable collections", null);
        ns.intern("CollFold", collFoldProto);
        ns.intern("coll-fold", new Fn.NamedFn("coll-fold") {
            @Override public Object invoke(Object coll, Object n, Object combinef, Object reducef) {
                return foldColl((int) Numbers.toLong(n), (Fn) combinef, (Fn) reducef, coll);
            }
        });

        ns.intern("reduce", new Fn.NamedFn("reduce") {
            @Override public Object invoke(Object f, Object coll) {
                Fn fn = (Fn) f;
                return reduceColl(fn, fn.invoke(), coll);
            }
            @Override public Object invoke(Object f, Object init, Object coll) {
                return reduceColl((Fn) f, init, coll);
            }
        });

        ns.intern("fold", new Fn.NamedFn("fold") {
            @Override public Object invoke(Object reducef, Object coll) {
                Fn rf = (Fn) reducef;
                return foldColl(DEFAULT_FOLD_N, rf, rf, coll);
            }
            @Override public Object invoke(Object combinef, Object reducef, Object coll) {
                return foldColl(DEFAULT_FOLD_N, (Fn) combinef, (Fn) reducef, coll);
            }
            @Override public Object invoke(Object n, Object combinef, Object reducef, Object coll) {
                return foldColl((int) Numbers.toLong(n), (Fn) combinef, (Fn) reducef, coll);
            }
        });

        ns.intern("reducer", Fn.of2("reducer", (coll, xf) -> new ReducibleColl(coll, (Fn) xf)));
        ns.intern("folder", Fn.of2("folder", (coll, xf) -> new FoldableColl(coll, (Fn) xf)));

        ns.intern("monoid", Fn.of2("monoid", (opObj, ctorObj) -> {
            Fn op = unwrapJit((Fn) opObj);
            Fn ctor = (Fn) ctorObj;
            return new Fn.NamedFn("monoid") {
                @Override public Object invoke() { return ctor.invoke(); }
                @Override public Object invoke(Object a, Object b) { return op.invoke(a, b); }
                @Override public boolean isUnboxedLong2() { return op.isUnboxedLong2(); }
                @Override public long invokePrim_JJ_J(long a, long b) { return op.invokePrim_JJ_J(a, b); }
            };
        }));

        Fn catFn = new Fn.NamedFn("cat") {
            @Override public Object invoke() { return new ArrayList<>(128); }
            @Override public Object invoke(Object left, Object right) {
                int lc = catSize(left);
                int rc = catSize(right);
                if (lc == 0) return right;
                if (rc == 0) return left;
                return new CatColl(lc + rc, left, right);
            }
        };
        ns.intern("cat", catFn);

        Fn appendBangFn = Fn.of2("append!", (acc, x) -> {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) acc;
            list.add(x);
            return list;
        });
        ns.intern("append!", appendBangFn);

        ns.intern("foldcat", Fn.of1("foldcat", coll -> foldColl(DEFAULT_FOLD_N, catFn, appendBangFn, coll)));

        // Foldable transformers: map, filter, remove, mapcat, flatten
        ns.intern("map", new Fn.NamedFn("map") {
            @Override public Object invoke(Object f) {
                Fn fn = (Fn) f;
                return Fn.of1("r/map", coll -> new FoldableColl(coll, CoreExtras.createTransducer1(0, "map", fn)));
            }
            @Override public Object invoke(Object f, Object coll) {
                return new FoldableColl(coll, CoreExtras.createTransducer1(0, "map", (Fn) f));
            }
        });

        ns.intern("filter", new Fn.NamedFn("filter") {
            @Override public Object invoke(Object pred) {
                Fn p = (Fn) pred;
                return Fn.of1("r/filter", coll -> new FoldableColl(coll, CoreExtras.createTransducer1(1, "filter", p)));
            }
            @Override public Object invoke(Object pred, Object coll) {
                return new FoldableColl(coll, CoreExtras.createTransducer1(1, "filter", (Fn) pred));
            }
        });

        ns.intern("remove", new Fn.NamedFn("remove") {
            @Override public Object invoke(Object pred) {
                Fn p = (Fn) pred;
                return Fn.of1("r/remove", coll -> new FoldableColl(coll, CoreExtras.createTransducer1(2, "remove", p)));
            }
            @Override public Object invoke(Object pred, Object coll) {
                return new FoldableColl(coll, CoreExtras.createTransducer1(2, "remove", (Fn) pred));
            }
        });

        ns.intern("mapcat", new Fn.NamedFn("mapcat") {
            @Override public Object invoke(Object f) {
                Fn fn = (Fn) f;
                return Fn.of1("r/mapcat", coll -> new FoldableColl(coll, CoreExtras.createTransducer1(11, "mapcat", fn)));
            }
            @Override public Object invoke(Object f, Object coll) {
                return new FoldableColl(coll, CoreExtras.createTransducer1(11, "mapcat", (Fn) f));
            }
        });

        Fn flattenXf = Fn.of1("flatten-xf", rfObj -> {
            Fn rf = (Fn) rfObj;
            return new Fn.NamedFn("flatten-rf") {
                @Override public Object invoke() { return rf.invoke(); }
                @Override public Object invoke(Object acc) { return rf.invoke(acc); }
                @Override public Object invoke(Object acc, Object x) {
                    if (x instanceof Seqable || x instanceof ISeq || x instanceof Iterable<?>) {
                        return Core.reduce3(this, acc, x);
                    }
                    return rf.invoke(acc, x);
                }
            };
        });
        ns.intern("flatten", new Fn.NamedFn("flatten") {
            @Override public Object invoke() {
                return Fn.of1("r/flatten", coll -> new FoldableColl(coll, flattenXf));
            }
            @Override public Object invoke(Object coll) {
                return new FoldableColl(coll, flattenXf);
            }
        });

        // Sequential-only transformers (order-dependent): take-while, take, drop
        ns.intern("take-while", new Fn.NamedFn("take-while") {
            @Override public Object invoke(Object pred) {
                Fn p = (Fn) pred;
                return Fn.of1("r/take-while", coll -> new ReducibleColl(coll, CoreExtras.createTransducer1(4, "take-while", p)));
            }
            @Override public Object invoke(Object pred, Object coll) {
                return new ReducibleColl(coll, CoreExtras.createTransducer1(4, "take-while", (Fn) pred));
            }
        });

        ns.intern("take", new Fn.NamedFn("take") {
            @Override public Object invoke(Object n) {
                return Fn.of1("r/take", coll -> new ReducibleColl(coll, CoreExtras.createTransducer1(3, "take", n)));
            }
            @Override public Object invoke(Object n, Object coll) {
                return new ReducibleColl(coll, CoreExtras.createTransducer1(3, "take", n));
            }
        });

        ns.intern("drop", new Fn.NamedFn("drop") {
            @Override public Object invoke(Object n) {
                return Fn.of1("r/drop", coll -> new ReducibleColl(coll, CoreExtras.createTransducer1(6, "drop", n)));
            }
            @Override public Object invoke(Object n, Object coll) {
                return new ReducibleColl(coll, CoreExtras.createTransducer1(6, "drop", n));
            }
        });
    }

    private static int catSize(Object x) {
        if (x instanceof CatColl c) return c.cnt;
        if (x instanceof List<?> l) return l.size();
        return RT.count(x);
    }
}
