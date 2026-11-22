package cljl;

import cljl.lang.IDeref;
import cljl.lang.IMeta;
import cljl.lang.IRef;
import cljl.lang.IReference;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import cljl.lang.PersistentVector;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Concurrency and state primitives: Atom, Volatile, Delay, Promise, FutureObj.
 * Futures run on Java 25 Virtual Threads for near-zero overhead concurrency.
 */
public final class Atom implements IRef, IMeta, IReference {
    public static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final boolean STRICT_ATOM = Boolean.getBoolean("cljl.atom.strict");
    private static volatile Thread SINGLE_THREAD = null;

    /**
     * Enables single-thread biased fast path for the sole application main thread.
     * Permanently revoked (set to null) BEFORE any second thread is started via
     * FutureObj, Agent, tap>, core.async, or Java Thread/Executor interop.
     */
    public static void initMainThread() {
        if (!STRICT_ATOM) {
            SINGLE_THREAD = Thread.currentThread();
        }
    }

    public static void markMultiThreaded() {
        if (SINGLE_THREAD != null) {
            SINGLE_THREAD = null;
        }
    }

    public static boolean isSingleThreaded() {
        return SINGLE_THREAD == Thread.currentThread();
    }

    private static final Object UNBOXED = new Object();
    private static final long MIN_FAST = -(1L << 62);
    private static final long MAX_FAST = (1L << 62) - 1L;
    private static final long MIN_XADD = -(1L << 60);
    private static final long MAX_XADD = (1L << 60);
    private static final long MIN_XADD_ENC = MIN_XADD << 1;
    private static final long MAX_XADD_ENC = MAX_XADD << 1;
    private static final long MAX_XADD_DELTA = 1L << 30;

    private static final VarHandle STATE_VH;
    private static final VarHandle FAST_VH;
    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            STATE_VH = lookup.findVarHandle(Atom.class, "state", Object.class);
            FAST_VH = lookup.findVarHandle(Atom.class, "fastVal", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static boolean isFastLong(long v) {
        return v >= MIN_FAST && v <= MAX_FAST;
    }

    // Bit 0 == 0: active 63-bit fast-long mode, value is (fastVal >> 1), and validator/watches are guaranteed empty.
    // Bit 0 == 1: permanently frozen/boxed mode, authoritative value is in `state`.
    private volatile long fastVal;
    private volatile Object state;
    private volatile boolean hasHooks;
    private volatile PersistentHashMap meta;
    private volatile Fn validator;
    private volatile ConcurrentHashMap<Object, Fn> watches;

    public Atom(long state) {
        if (isFastLong(state)) {
            this.state = UNBOXED;
            this.fastVal = state << 1;
        } else {
            this.fastVal = 1L;
            this.state = Long.valueOf(state);
        }
        this.meta = null;
    }

    public Atom(Object state) {
        this(state, null);
    }

    public Atom(Object state, PersistentHashMap meta) {
        if (state instanceof Long l && isFastLong(l)) {
            this.state = UNBOXED;
            this.fastVal = l << 1;
        } else {
            this.fastVal = 1L;
            this.state = state;
        }
        this.meta = meta;
    }

    private Object getBoxedState() {
        Object s = this.state;
        if (s != UNBOXED) return s;
        synchronized (this) {
            return this.state;
        }
    }

    private Object ensureBoxed() {
        if ((this.fastVal & 1L) != 0L) {
            return getBoxedState();
        }
        synchronized (this) {
            for (;;) {
                long prev = this.fastVal;
                if ((prev & 1L) != 0L) return this.state;
                if (FAST_VH.compareAndSet(this, prev, prev | 1L)) {
                    Object boxed = Long.valueOf(prev >> 1);
                    this.state = boxed;
                    return boxed;
                }
            }
        }
    }

    @Override
    public Object deref() {
        long f = this.fastVal;
        if ((f & 1L) == 0L) return Long.valueOf(f >> 1);
        return getBoxedState();
    }

    public long derefLong() {
        long f = this.fastVal;
        if ((f & 1L) == 0L) return f >> 1;
        return Numbers.longCast(getBoxedState());
    }

    private void validate(Object val) {
        Fn v = this.validator;
        if (v != null && !RT.truthy(v.invoke(val))) {
            throw new IllegalStateException("Invalid reference state: " + val);
        }
    }

    private void updateHasHooks() {
        ConcurrentHashMap<Object, Fn> w = this.watches;
        this.hasHooks = (this.validator != null) || (w != null && !w.isEmpty());
    }

    public void setValidator(Fn validator) {
        if (validator != null) {
            Object cur = ensureBoxed();
            if (!RT.truthy(validator.invoke(cur))) {
                throw new IllegalStateException("Invalid reference state: " + cur);
            }
        }
        this.validator = validator;
        updateHasHooks();
    }

    public Fn getValidator() {
        return validator;
    }

    public Object reset(Object newval) {
        if (newval instanceof Long nl && isFastLong(nl)) {
            long encoded = nl << 1;
            if (SINGLE_THREAD == Thread.currentThread() && (this.fastVal & 1L) == 0L) {
                FAST_VH.setRelease(this, encoded);
                return newval;
            }
            for (;;) {
                long f = this.fastVal;
                if ((f & 1L) != 0L) break;
                if (FAST_VH.compareAndSet(this, f, encoded)) return newval;
            }
        } else {
            ensureBoxed();
        }
        if (!this.hasHooks) {
            if (SINGLE_THREAD == Thread.currentThread()) {
                STATE_VH.setRelease(this, newval);
                return newval;
            }
            STATE_VH.getAndSet(this, newval);
            return newval;
        }
        validate(newval);
        Object oldval = STATE_VH.getAndSet(this, newval);
        notifyWatches(oldval, newval);
        return newval;
    }

    public long resetLong(long newval) {
        if (isFastLong(newval)) {
            long encoded = newval << 1;
            if (SINGLE_THREAD == Thread.currentThread() && (this.fastVal & 1L) == 0L) {
                FAST_VH.setRelease(this, encoded);
                return newval;
            }
            for (;;) {
                long f = this.fastVal;
                if ((f & 1L) != 0L) break;
                if (FAST_VH.compareAndSet(this, f, encoded)) return newval;
            }
        }
        reset(Long.valueOf(newval));
        return newval;
    }

    public boolean compareAndSet(Object oldv, Object newv) {
        long f = this.fastVal;
        if ((f & 1L) == 0L) {
            if (!(oldv instanceof Long ol) || !isFastLong(ol) || f != (ol << 1)) {
                return false;
            }
            if (newv instanceof Long nl && isFastLong(nl)) {
                if (SINGLE_THREAD == Thread.currentThread()) {
                    FAST_VH.setRelease(this, nl << 1);
                    return true;
                }
                if (FAST_VH.compareAndSet(this, f, nl << 1)) return true;
                if ((this.fastVal & 1L) == 0L) return false;
            } else {
                Object boxed = ensureBoxed();
                if (boxed instanceof Long sl && sl.longValue() == ol.longValue()) {
                    oldv = boxed;
                }
            }
        }
        if (!this.hasHooks) {
            if (SINGLE_THREAD == Thread.currentThread()) {
                if (this.state == oldv) {
                    STATE_VH.setRelease(this, newv);
                    return true;
                }
                return false;
            }
            return STATE_VH.compareAndSet(this, oldv, newv);
        }
        validate(newv);
        boolean ret = STATE_VH.compareAndSet(this, oldv, newv);
        if (ret) notifyWatches(oldv, newv);
        return ret;
    }

    private Object readBoxedState() {
        Object s = this.state;
        return (s != UNBOXED) ? s : ensureBoxed();
    }

    public void resetLongDiscard(long newval) {
        if (isFastLong(newval)) {
            long encoded = newval << 1;
            if (SINGLE_THREAD == Thread.currentThread() && (this.fastVal & 1L) == 0L) {
                FAST_VH.setRelease(this, encoded);
                return;
            }
            for (;;) {
                long f = this.fastVal;
                if ((f & 1L) != 0L) break;
                if (FAST_VH.compareAndSet(this, f, encoded)) return;
            }
        }
        reset(Long.valueOf(newval));
    }

    public Object resetVals(Object newval) {
        if (newval instanceof Long nl && isFastLong(nl)) {
            long encoded = nl << 1;
            for (;;) {
                long f = this.fastVal;
                if ((f & 1L) != 0L) break;
                if (FAST_VH.compareAndSet(this, f, encoded)) {
                    return PersistentVector.createOwning(Long.valueOf(f >> 1), newval);
                }
            }
        } else {
            ensureBoxed();
        }
        if (!this.hasHooks) {
            Object oldval = STATE_VH.getAndSet(this, newval);
            return PersistentVector.createOwning(oldval, newval);
        }
        validate(newval);
        Object oldval = STATE_VH.getAndSet(this, newval);
        notifyWatches(oldval, newval);
        return PersistentVector.createOwning(oldval, newval);
    }

    private boolean tryAddFastDiscard(long delta, boolean checked) {
        if (SINGLE_THREAD == Thread.currentThread()) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return false;
            long cur = fv >> 1;
            long nxt = cur + delta;
            if ((!checked || ((cur ^ nxt) & (delta ^ nxt)) >= 0L) && isFastLong(nxt)) {
                FAST_VH.setRelease(this, nxt << 1);
                return true;
            }
            ensureBoxed();
            return false;
        }
        if (delta >= -MAX_XADD_DELTA && delta <= MAX_XADD_DELTA) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                long prev = (long) FAST_VH.getAndAdd(this, delta << 1);
                if ((prev & 1L) == 0L) {
                    long nxt = (prev >> 1) + delta;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return true;
                }
                return false;
            }
        }
        for (;;) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return false;
            long cur = fv >> 1;
            long nxt = cur + delta;
            if ((!checked || ((cur ^ nxt) & (delta ^ nxt)) >= 0L) && isFastLong(nxt)) {
                if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return true;
                Thread.onSpinWait();
            } else {
                ensureBoxed();
                return false;
            }
        }
    }

    private boolean trySubFastDiscard(long x, boolean checked) {
        if (SINGLE_THREAD == Thread.currentThread()) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return false;
            long cur = fv >> 1;
            long nxt = cur - x;
            if ((!checked || ((cur ^ x) & (cur ^ nxt)) >= 0L) && isFastLong(nxt)) {
                FAST_VH.setRelease(this, nxt << 1);
                return true;
            }
            ensureBoxed();
            return false;
        }
        if (x >= -MAX_XADD_DELTA && x <= MAX_XADD_DELTA) {
            return tryAddFastDiscard(-x, checked);
        }
        for (;;) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return false;
            long cur = fv >> 1;
            long nxt = cur - x;
            if ((!checked || ((cur ^ x) & (cur ^ nxt)) >= 0L) && isFastLong(nxt)) {
                if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return true;
                Thread.onSpinWait();
            } else {
                ensureBoxed();
                return false;
            }
        }
    }

    private boolean tryMulFastDiscard(long x, boolean checked) {
        if (SINGLE_THREAD == Thread.currentThread()) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return false;
            long cur = fv >> 1;
            long nxt;
            if (checked) {
                try { nxt = Math.multiplyExact(cur, x); }
                catch (ArithmeticException e) { ensureBoxed(); return false; }
            } else {
                nxt = cur * x;
            }
            if (isFastLong(nxt)) {
                FAST_VH.setRelease(this, nxt << 1);
                return true;
            }
            ensureBoxed();
            return false;
        }
        for (;;) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return false;
            long cur = fv >> 1;
            long nxt;
            if (checked) {
                try {
                    nxt = Math.multiplyExact(cur, x);
                } catch (ArithmeticException e) {
                    ensureBoxed();
                    return false;
                }
            } else {
                nxt = cur * x;
            }
            if (isFastLong(nxt)) {
                if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return true;
                Thread.onSpinWait();
            } else {
                ensureBoxed();
                return false;
            }
        }
    }

    private Object tryAddFastBoxed(long delta, boolean checked) {
        if (SINGLE_THREAD == Thread.currentThread()) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return UNBOXED;
            long cur = fv >> 1;
            long nxt = cur + delta;
            if ((!checked || ((cur ^ nxt) & (delta ^ nxt)) >= 0L) && isFastLong(nxt)) {
                FAST_VH.setRelease(this, nxt << 1);
                return Long.valueOf(nxt);
            }
            ensureBoxed();
            return UNBOXED;
        }
        if (delta >= -MAX_XADD_DELTA && delta <= MAX_XADD_DELTA) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                long prev = (long) FAST_VH.getAndAdd(this, delta << 1);
                if ((prev & 1L) == 0L) {
                    long nxt = (prev >> 1) + delta;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return Long.valueOf(nxt);
                }
                return UNBOXED;
            }
        }
        for (;;) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return UNBOXED;
            long cur = fv >> 1;
            long nxt = cur + delta;
            if ((!checked || ((cur ^ nxt) & (delta ^ nxt)) >= 0L) && isFastLong(nxt)) {
                if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return Long.valueOf(nxt);
                Thread.onSpinWait();
            } else {
                ensureBoxed();
                return UNBOXED;
            }
        }
    }

    private Object trySubFastBoxed(long x, boolean checked) {
        if (SINGLE_THREAD == Thread.currentThread()) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return UNBOXED;
            long cur = fv >> 1;
            long nxt = cur - x;
            if ((!checked || ((cur ^ x) & (cur ^ nxt)) >= 0L) && isFastLong(nxt)) {
                FAST_VH.setRelease(this, nxt << 1);
                return Long.valueOf(nxt);
            }
            ensureBoxed();
            return UNBOXED;
        }
        if (x >= -MAX_XADD_DELTA && x <= MAX_XADD_DELTA) {
            return tryAddFastBoxed(-x, checked);
        }
        for (;;) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return UNBOXED;
            long cur = fv >> 1;
            long nxt = cur - x;
            if ((!checked || ((cur ^ x) & (cur ^ nxt)) >= 0L) && isFastLong(nxt)) {
                if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return Long.valueOf(nxt);
                Thread.onSpinWait();
            } else {
                ensureBoxed();
                return UNBOXED;
            }
        }
    }

    private Object tryMulFastBoxed(long x, boolean checked) {
        if (SINGLE_THREAD == Thread.currentThread()) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return UNBOXED;
            long cur = fv >> 1;
            long nxt;
            if (checked) {
                try { nxt = Math.multiplyExact(cur, x); }
                catch (ArithmeticException e) { ensureBoxed(); return UNBOXED; }
            } else {
                nxt = cur * x;
            }
            if (isFastLong(nxt)) {
                FAST_VH.setRelease(this, nxt << 1);
                return Long.valueOf(nxt);
            }
            ensureBoxed();
            return UNBOXED;
        }
        for (;;) {
            long fv = this.fastVal;
            if ((fv & 1L) != 0L) return UNBOXED;
            long cur = fv >> 1;
            long nxt;
            if (checked) {
                try {
                    nxt = Math.multiplyExact(cur, x);
                } catch (ArithmeticException e) {
                    ensureBoxed();
                    return UNBOXED;
                }
            } else {
                nxt = cur * x;
            }
            if (isFastLong(nxt)) {
                if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return Long.valueOf(nxt);
                Thread.onSpinWait();
            } else {
                ensureBoxed();
                return UNBOXED;
            }
        }
    }

    private boolean trySwapFast0Slow(Fn f) {
        if (f == Core.decFn) return tryAddFastDiscard(-1L, true);
        if (f == Core.uncheckedIncFn) return tryAddFastDiscard(1L, false);
        if (f == Core.uncheckedDecFn) return tryAddFastDiscard(-1L, false);
        return false;
    }

    private boolean trySwapFastLong1(Fn f, long x) {
        if (f == Core.addFn) return tryAddFastDiscard(x, true);
        if (f == Core.subFn) return trySubFastDiscard(x, true);
        if (f == Core.uncheckedAddFn) return tryAddFastDiscard(x, false);
        if (f == Core.uncheckedSubFn) return trySubFastDiscard(x, false);
        if (f == Core.mulFn) return tryMulFastDiscard(x, true);
        if (f == Core.uncheckedMulFn) return tryMulFastDiscard(x, false);
        return false;
    }

    private Object trySwapFastBoxed1(Fn f, long y) {
        if (f == Core.addFn) return tryAddFastBoxed(y, true);
        if (f == Core.subFn) return trySubFastBoxed(y, true);
        if (f == Core.uncheckedAddFn) return tryAddFastBoxed(y, false);
        if (f == Core.uncheckedSubFn) return trySubFastBoxed(y, false);
        if (f == Core.mulFn) return tryMulFastBoxed(y, true);
        if (f == Core.uncheckedMulFn) return tryMulFastBoxed(y, false);
        return UNBOXED;
    }

    public void swapDiscard(Fn f) {
        if (f == Core.incFn) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv < MAX_XADD_ENC) {
                if (SINGLE_THREAD == Thread.currentThread()) {
                    FAST_VH.setRelease(this, fv + 2L);
                    return;
                }
                long prev = (long) FAST_VH.getAndAdd(this, 2L);
                if ((prev & 1L) == 0L) {
                    long nxt = (prev >> 1) + 1L;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return;
                }
            } else if ((fv & 1L) == 0L && tryAddFastDiscard(1L, true)) {
                return;
            }
        } else if (this.state == UNBOXED && trySwapFast0Slow(f)) {
            return;
        }
        swapBoxed0(f);
    }

    public void swapDiscard(Fn f, long x) {
        Object s = this.state;
        if (s == UNBOXED && trySwapFastLong1(f, x)) return;
        swapBoxed1(f, Long.valueOf(x), s);
    }

    public void swapDiscard(Fn f, Object x) {
        Object s = this.state;
        if (s == UNBOXED && x instanceof Long lx && trySwapFastLong1(f, lx.longValue())) return;
        swapBoxed1(f, x, s);
    }

    public long swapLong(Fn f) {
        if (f == Core.incFn || f == Core.uncheckedIncFn) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv < MAX_XADD_ENC) {
                if (SINGLE_THREAD == Thread.currentThread()) {
                    FAST_VH.setRelease(this, fv + 2L);
                    return (fv >> 1) + 1L;
                }
                long prev = (long) FAST_VH.getAndAdd(this, 2L);
                if ((prev & 1L) == 0L) {
                    long nxt = (prev >> 1) + 1L;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return nxt;
                }
            }
        } else if (f == Core.decFn || f == Core.uncheckedDecFn) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv > MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                if (SINGLE_THREAD == Thread.currentThread()) {
                    FAST_VH.setRelease(this, fv - 2L);
                    return (fv >> 1) - 1L;
                }
                long prev = (long) FAST_VH.getAndAdd(this, -2L);
                if ((prev & 1L) == 0L) {
                    long nxt = (prev >> 1) - 1L;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return nxt;
                }
            }
        }
        return Numbers.longCast(swap(f));
    }

    public long swapLong(Fn f, long x) {
        if (x >= -MAX_XADD_DELTA && x <= MAX_XADD_DELTA) {
            if (f == Core.addFn || f == Core.uncheckedAddFn) {
                long fv = this.fastVal;
                if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                    if (SINGLE_THREAD == Thread.currentThread()) {
                        long nxt = (fv >> 1) + x;
                        FAST_VH.setRelease(this, nxt << 1);
                        if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                        return nxt;
                    }
                    long prev = (long) FAST_VH.getAndAdd(this, x << 1);
                    if ((prev & 1L) == 0L) {
                        long nxt = (prev >> 1) + x;
                        if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                        return nxt;
                    }
                }
            } else if (f == Core.subFn || f == Core.uncheckedSubFn) {
                long fv = this.fastVal;
                if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                    if (SINGLE_THREAD == Thread.currentThread()) {
                        long nxt = (fv >> 1) - x;
                        FAST_VH.setRelease(this, nxt << 1);
                        if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                        return nxt;
                    }
                    long prev = (long) FAST_VH.getAndAdd(this, (-x) << 1);
                    if ((prev & 1L) == 0L) {
                        long nxt = (prev >> 1) - x;
                        if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                        return nxt;
                    }
                }
            }
        }
        return Numbers.longCast(swap(f, Long.valueOf(x)));
    }

    private Object swapBoxed0(Fn f) {
        if (this.hasHooks) return swapBoxed0WithHooks(f);
        Object v = readBoxedState();
        if (SINGLE_THREAD == Thread.currentThread()) {
            Object newv = f.invoke(v);
            if (this.state == v && SINGLE_THREAD == Thread.currentThread()) {
                STATE_VH.setRelease(this, newv);
                return newv;
            }
            v = this.state;
        }
        for (;;) {
            Object newv = f.invoke(v);
            if (STATE_VH.compareAndSet(this, v, newv)) return newv;
            Thread.onSpinWait();
            v = this.state;
        }
    }

    private Object swapBoxed0WithHooks(Fn f) {
        Object v = readBoxedState();
        for (;;) {
            Object newv = f.invoke(v);
            validate(newv);
            if (STATE_VH.compareAndSet(this, v, newv)) {
                notifyWatches(v, newv);
                return newv;
            }
            Thread.onSpinWait();
            v = this.state;
        }
    }

    private Object swapBoxed1(Fn f, Object x, Object s) {
        if (this.hasHooks) return swapBoxed1WithHooks(f, x);
        Object v = (s != UNBOXED) ? s : ensureBoxed();
        if (SINGLE_THREAD == Thread.currentThread()) {
            Object newv = f.invoke(v, x);
            if (this.state == v && SINGLE_THREAD == Thread.currentThread()) {
                STATE_VH.setRelease(this, newv);
                return newv;
            }
            v = this.state;
        }
        for (;;) {
            Object newv = f.invoke(v, x);
            if (STATE_VH.compareAndSet(this, v, newv)) return newv;
            Thread.onSpinWait();
            v = this.state;
        }
    }

    private Object swapBoxed1WithHooks(Fn f, Object x) {
        Object v = readBoxedState();
        for (;;) {
            Object newv = f.invoke(v, x);
            validate(newv);
            if (STATE_VH.compareAndSet(this, v, newv)) {
                notifyWatches(v, newv);
                return newv;
            }
            Thread.onSpinWait();
            v = this.state;
        }
    }

    public Object swap(Fn f) {
        if (this.state == UNBOXED) {
            if (f == Core.incFn) {
                Object r = tryAddFastBoxed(1L, true);
                if (r != UNBOXED) return r;
            } else if (f == Core.decFn) {
                Object r = tryAddFastBoxed(-1L, true);
                if (r != UNBOXED) return r;
            } else if (f == Core.uncheckedIncFn) {
                Object r = tryAddFastBoxed(1L, false);
                if (r != UNBOXED) return r;
            } else if (f == Core.uncheckedDecFn) {
                Object r = tryAddFastBoxed(-1L, false);
                if (r != UNBOXED) return r;
            }
        }
        return swapBoxed0(f);
    }

    public Object swap(Fn f, Object x) {
        Object s = this.state;
        if (s == UNBOXED && x instanceof Long lx) {
            Object r = trySwapFastBoxed1(f, lx.longValue());
            if (r != UNBOXED) return r;
        }
        return swapBoxed1(f, x, s);
    }

    public Object swap(Fn f, Object x, Object y) {
        Object v = readBoxedState();
        if (!this.hasHooks) {
            for (;;) {
                Object newv = f.invoke(v, x, y);
                Object witness = STATE_VH.compareAndExchange(this, v, newv);
                if (witness == v) return newv;
                v = witness;
                Thread.onSpinWait();
            }
        }
        for (;;) {
            Object newv = f.invoke(v, x, y);
            validate(newv);
            Object witness = STATE_VH.compareAndExchange(this, v, newv);
            if (witness == v) {
                notifyWatches(v, newv);
                return newv;
            }
            v = witness;
            Thread.onSpinWait();
        }
    }

    public Object swap(Fn f, Object x, Object y, Object[] args) {
        Object v = readBoxedState();
        Object[] all = new Object[3 + args.length];
        all[1] = x;
        all[2] = y;
        System.arraycopy(args, 0, all, 3, args.length);
        for (;;) {
            all[0] = v;
            Object newv = f.applyTo(all);
            if (this.hasHooks) validate(newv);
            Object witness = STATE_VH.compareAndExchange(this, v, newv);
            if (witness == v) {
                if (this.hasHooks) notifyWatches(v, newv);
                return newv;
            }
            v = witness;
            Thread.onSpinWait();
        }
    }

    public Object swapVals(Fn f) {
        if (f == Core.incFn || f == Core.uncheckedIncFn) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                long prev = (long) FAST_VH.getAndAdd(this, 2L);
                if ((prev & 1L) == 0L) {
                    long oldv = prev >> 1;
                    long nxt = oldv + 1L;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return PersistentVector.createOwning(Long.valueOf(oldv), Long.valueOf(nxt));
                }
            }
        } else if (f == Core.decFn || f == Core.uncheckedDecFn) {
            long fv = this.fastVal;
            if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                long prev = (long) FAST_VH.getAndAdd(this, -2L);
                if ((prev & 1L) == 0L) {
                    long oldv = prev >> 1;
                    long nxt = oldv - 1L;
                    if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                    return PersistentVector.createOwning(Long.valueOf(oldv), Long.valueOf(nxt));
                }
            }
        }
        Object v = readBoxedState();
        for (;;) {
            Object newv = f.invoke(v);
            if (this.hasHooks) validate(newv);
            Object witness = STATE_VH.compareAndExchange(this, v, newv);
            if (witness == v) {
                if (this.hasHooks) notifyWatches(v, newv);
                return PersistentVector.createOwning(v, newv);
            }
            v = witness;
            Thread.onSpinWait();
        }
    }

    public Object swapVals(Fn f, Object x) {
        if (x instanceof Long lx) {
            long y = lx.longValue();
            if ((f == Core.addFn || f == Core.uncheckedAddFn) && y >= -MAX_XADD_DELTA && y <= MAX_XADD_DELTA) {
                long fv = this.fastVal;
                if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                    long prev = (long) FAST_VH.getAndAdd(this, y << 1);
                    if ((prev & 1L) == 0L) {
                        long oldv = prev >> 1;
                        long nxt = oldv + y;
                        if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                        return PersistentVector.createOwning(Long.valueOf(oldv), Long.valueOf(nxt));
                    }
                }
            } else if ((f == Core.subFn || f == Core.uncheckedSubFn) && y >= -MAX_XADD_DELTA && y <= MAX_XADD_DELTA) {
                long fv = this.fastVal;
                if ((fv & 1L) == 0L && fv >= MIN_XADD_ENC && fv <= MAX_XADD_ENC) {
                    long prev = (long) FAST_VH.getAndAdd(this, (-y) << 1);
                    if ((prev & 1L) == 0L) {
                        long oldv = prev >> 1;
                        long nxt = oldv - y;
                        if (nxt < MIN_XADD || nxt > MAX_XADD) ensureBoxed();
                        return PersistentVector.createOwning(Long.valueOf(oldv), Long.valueOf(nxt));
                    }
                }
            }
        }
        Object v = readBoxedState();
        for (;;) {
            Object newv = f.invoke(v, x);
            if (this.hasHooks) validate(newv);
            Object witness = STATE_VH.compareAndExchange(this, v, newv);
            if (witness == v) {
                if (this.hasHooks) notifyWatches(v, newv);
                return PersistentVector.createOwning(v, newv);
            }
            v = witness;
            Thread.onSpinWait();
        }
    }

    public Object swapVals(Fn f, Object x, Object y) {
        Object v = readBoxedState();
        for (;;) {
            Object newv = f.invoke(v, x, y);
            if (this.hasHooks) validate(newv);
            Object witness = STATE_VH.compareAndExchange(this, v, newv);
            if (witness == v) {
                if (this.hasHooks) notifyWatches(v, newv);
                return PersistentVector.createOwning(v, newv);
            }
            v = witness;
            Thread.onSpinWait();
        }
    }

    public Object swapVals(Fn f, Object[] extra) {
        Object v = readBoxedState();
        Object[] all = new Object[1 + extra.length];
        System.arraycopy(extra, 0, all, 1, extra.length);
        for (;;) {
            all[0] = v;
            Object newv = f.applyTo(all);
            if (this.hasHooks) validate(newv);
            Object witness = STATE_VH.compareAndExchange(this, v, newv);
            if (witness == v) {
                if (this.hasHooks) notifyWatches(v, newv);
                return PersistentVector.createOwning(v, newv);
            }
            v = witness;
            Thread.onSpinWait();
        }
    }

    public void addWatch(Object key, Fn callback) {
        ensureBoxed();
        if (watches == null) {
            synchronized (this) {
                if (watches == null) watches = new ConcurrentHashMap<>();
            }
        }
        watches.put(key, callback);
        this.hasHooks = true;
    }

    public void removeWatch(Object key) {
        if (watches != null) {
            watches.remove(key);
            updateHasHooks();
        }
    }

    private void notifyWatches(Object oldVal, Object newVal) {
        ConcurrentHashMap<Object, Fn> w = this.watches;
        if (w != null && !w.isEmpty()) {
            for (Map.Entry<Object, Fn> e : w.entrySet()) {
                e.getValue().invoke(e.getKey(), this, oldVal, newVal);
            }
        }
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public PersistentHashMap resetMeta(PersistentHashMap m) {
        this.meta = m != null ? m : PersistentHashMap.EMPTY;
        return this.meta;
    }

    @Override
    public synchronized PersistentHashMap alterMeta(Fn alter, ISeq args) {
        Object[] callArgs = new Object[1 + RT.count(args)];
        callArgs[0] = this.meta;
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        this.meta = (PersistentHashMap) alter.applyTo(callArgs);
        return this.meta;
    }

    @Override
    public String toString() {
        return "#<Atom " + RT.printString(deref()) + ">";
    }

    public static final class Volatile implements IDeref {
        private static final boolean STRICT_VOLATILE = Boolean.getBoolean("cljl.volatile.strict");
        private static final Object VOL_UNBOXED = new Object();
        private static final Object BOXED_DOUBLE = new Object();

        private static final VarHandle VOL_FAST_VH;
        private static final VarHandle VOL_VAL_VH;
        private static final VarHandle VOL_DVAL_VH;
        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                VOL_FAST_VH = lookup.findVarHandle(Volatile.class, "fastVal", long.class);
                VOL_VAL_VH = lookup.findVarHandle(Volatile.class, "val", Object.class);
                VOL_DVAL_VH = lookup.findVarHandle(Volatile.class, "dval", double.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        // Bit 0 == 0: active 63-bit fast-long mode, value is (fastVal >> 1).
        // Bit 0 == 1: fallback mode (val == BOXED_DOUBLE -> dval, otherwise boxed Object in val).
        // Transitions are strictly one-way (fast-long -> boxed, fast-double -> boxed),
        // preventing any torn (tag, value) reads across threads.
        private volatile long fastVal;
        public volatile Object val;
        private volatile double dval;

        public Volatile(Object val) {
            if (val instanceof Long l && isFastLong(l)) {
                this.val = VOL_UNBOXED;
                this.fastVal = l << 1;
            } else if (val instanceof Double d) {
                this.dval = d;
                this.val = BOXED_DOUBLE;
                this.fastVal = 1L;
            } else {
                this.val = val;
                this.fastVal = 1L;
            }
        }

        public Volatile(long val) {
            if (isFastLong(val)) {
                this.val = VOL_UNBOXED;
                this.fastVal = val << 1;
            } else {
                this.val = Long.valueOf(val);
                this.fastVal = 1L;
            }
        }

        public Volatile(double val) {
            this.dval = val;
            this.val = BOXED_DOUBLE;
            this.fastVal = 1L;
        }

        private long getFast() {
            return STRICT_VOLATILE ? this.fastVal : (long) VOL_FAST_VH.getAcquire(this);
        }

        private void setFast(long encoded) {
            if (STRICT_VOLATILE) {
                this.fastVal = encoded;
            } else {
                VOL_FAST_VH.setRelease(this, encoded);
            }
        }

        private Object getValField() {
            return STRICT_VOLATILE ? this.val : VOL_VAL_VH.getAcquire(this);
        }

        private void setValField(Object v) {
            if (STRICT_VOLATILE) {
                this.val = v;
            } else {
                VOL_VAL_VH.setRelease(this, v);
            }
        }

        private double getDvalField() {
            return STRICT_VOLATILE ? this.dval : (double) VOL_DVAL_VH.getAcquire(this);
        }

        private void setDvalField(double d) {
            if (STRICT_VOLATILE) {
                this.dval = d;
            } else {
                VOL_DVAL_VH.setRelease(this, d);
            }
        }

        @Override
        public Object deref() {
            long f = getFast();
            if ((f & 1L) == 0L) return Long.valueOf(f >> 1);
            Object o = getValField();
            if (o == BOXED_DOUBLE) return Double.valueOf(getDvalField());
            return o;
        }

        public long derefLong() {
            long f = getFast();
            if ((f & 1L) == 0L) return f >> 1;
            Object o = getValField();
            if (o == BOXED_DOUBLE) return Numbers.longCast(getDvalField());
            return Numbers.longCast(o);
        }

        public double derefDouble() {
            long f = getFast();
            if ((f & 1L) == 0L) return (double) (f >> 1);
            Object o = getValField();
            if (o == BOXED_DOUBLE) return getDvalField();
            return Numbers.toDouble(o);
        }

        public Object reset(Object newval) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                if (newval instanceof Long l && isFastLong(l)) {
                    setFast(l << 1);
                    return newval;
                }
                setValField(newval);
                setFast(1L);
                return newval;
            }
            Object cur = getValField();
            if (cur == BOXED_DOUBLE && newval instanceof Double d) {
                setDvalField(d);
                return newval;
            }
            setValField(newval);
            return newval;
        }

        public long resetLong(long newval) {
            long f = getFast();
            if ((f & 1L) == 0L && isFastLong(newval)) {
                setFast(newval << 1);
                return newval;
            }
            Object boxed = Long.valueOf(newval);
            setValField(boxed);
            if ((f & 1L) == 0L) setFast(1L);
            return newval;
        }

        public double resetDouble(double newval) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                setDvalField(newval);
                return newval;
            }
            Object boxed = Double.valueOf(newval);
            setValField(boxed);
            if ((f & 1L) == 0L) setFast(1L);
            return newval;
        }

        // Fused unboxed RMW helpers for (vswap! v op ...) and (vreset! v (op @v ...))

        public void vswapIncDiscard() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur < MAX_FAST) {
                    setFast((cur + 1L) << 1);
                    return;
                }
            }
            reset(Numbers.inc(deref()));
        }

        public Object vswapInc() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur < MAX_FAST) {
                    long nxt = cur + 1L;
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            }
            return reset(Numbers.inc(deref()));
        }

        public long vswapIncLong() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur < MAX_FAST) {
                    long nxt = cur + 1L;
                    setFast(nxt << 1);
                    return nxt;
                }
            }
            return Numbers.longCast(reset(Numbers.inc(deref())));
        }

        public void vswapDecDiscard() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur > MIN_FAST) {
                    setFast((cur - 1L) << 1);
                    return;
                }
            }
            reset(Numbers.dec(deref()));
        }

        public Object vswapDec() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur > MIN_FAST) {
                    long nxt = cur - 1L;
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            }
            return reset(Numbers.dec(deref()));
        }

        public long vswapDecLong() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur > MIN_FAST) {
                    long nxt = cur - 1L;
                    setFast(nxt << 1);
                    return nxt;
                }
            }
            return Numbers.longCast(reset(Numbers.dec(deref())));
        }

        public void vswapUncheckedIncDiscard() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur < MAX_FAST) {
                    setFast((cur + 1L) << 1);
                    return;
                }
            }
            reset(Numbers.uncheckedInc(deref()));
        }

        public Object vswapUncheckedInc() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur < MAX_FAST) {
                    long nxt = cur + 1L;
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            }
            return reset(Numbers.uncheckedInc(deref()));
        }

        public long vswapUncheckedIncLong() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur < MAX_FAST) {
                    long nxt = cur + 1L;
                    setFast(nxt << 1);
                    return nxt;
                }
            }
            return Numbers.longCast(reset(Numbers.uncheckedInc(deref())));
        }

        public void vswapUncheckedDecDiscard() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur > MIN_FAST) {
                    setFast((cur - 1L) << 1);
                    return;
                }
            }
            reset(Numbers.uncheckedDec(deref()));
        }

        public Object vswapUncheckedDec() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur > MIN_FAST) {
                    long nxt = cur - 1L;
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            }
            return reset(Numbers.uncheckedDec(deref()));
        }

        public long vswapUncheckedDecLong() {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                if (cur > MIN_FAST) {
                    long nxt = cur - 1L;
                    setFast(nxt << 1);
                    return nxt;
                }
            }
            return Numbers.longCast(reset(Numbers.uncheckedDec(deref())));
        }

        public void vswapAddLongDiscard(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur + x;
                if (((cur ^ nxt) & (x ^ nxt)) >= 0L && isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return;
                }
            } else if (getValField() == BOXED_DOUBLE) {
                setDvalField(getDvalField() + (double) x);
                return;
            }
            reset(Numbers.add(deref(), Long.valueOf(x)));
        }

        public Object vswapAddLong(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur + x;
                if (((cur ^ nxt) & (x ^ nxt)) >= 0L && isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            } else if (getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() + (double) x;
                setDvalField(nxt);
                return Double.valueOf(nxt);
            }
            return reset(Numbers.add(deref(), Long.valueOf(x)));
        }

        public long vswapAddLongPrim(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur + x;
                if (((cur ^ nxt) & (x ^ nxt)) >= 0L && isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return nxt;
                }
            }
            return Numbers.longCast(reset(Numbers.add(deref(), Long.valueOf(x))));
        }

        public void vswapUncheckedAddLongDiscard(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur + x;
                if (isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return;
                }
            }
            reset(Numbers.uncheckedAdd(deref(), Long.valueOf(x)));
        }

        public Object vswapUncheckedAddLong(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur + x;
                if (isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            }
            return reset(Numbers.uncheckedAdd(deref(), Long.valueOf(x)));
        }

        public void vswapAddDoubleDiscard(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                setDvalField(getDvalField() + x);
                return;
            }
            reset(Numbers.add(deref(), Double.valueOf(x)));
        }

        public Object vswapAddDouble(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() + x;
                setDvalField(nxt);
                return Double.valueOf(nxt);
            }
            return reset(Numbers.add(deref(), Double.valueOf(x)));
        }

        public double vswapAddDoublePrim(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() + x;
                setDvalField(nxt);
                return nxt;
            }
            return Numbers.toDouble(reset(Numbers.add(deref(), Double.valueOf(x))));
        }

        public void vswapAddDiscard(Object x) {
            if (x instanceof Long lx) {
                vswapAddLongDiscard(lx);
                return;
            }
            if (x instanceof Double dx) {
                vswapAddDoubleDiscard(dx);
                return;
            }
            reset(Numbers.add(deref(), x));
        }

        public Object vswapAdd(Object x) {
            if (x instanceof Long lx) {
                return vswapAddLong(lx);
            }
            if (x instanceof Double dx) {
                return vswapAddDouble(dx);
            }
            return reset(Numbers.add(deref(), x));
        }

        public void vswapSubLongDiscard(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur - x;
                if (((cur ^ x) & (cur ^ nxt)) >= 0L && isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return;
                }
            } else if (getValField() == BOXED_DOUBLE) {
                setDvalField(getDvalField() - (double) x);
                return;
            }
            reset(Numbers.minus(deref(), Long.valueOf(x)));
        }

        public Object vswapSubLong(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long nxt = cur - x;
                if (((cur ^ x) & (cur ^ nxt)) >= 0L && isFastLong(nxt)) {
                    setFast(nxt << 1);
                    return Long.valueOf(nxt);
                }
            } else if (getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() - (double) x;
                setDvalField(nxt);
                return Double.valueOf(nxt);
            }
            return reset(Numbers.minus(deref(), Long.valueOf(x)));
        }

        public void vswapSubDoubleDiscard(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                setDvalField(getDvalField() - x);
                return;
            }
            reset(Numbers.minus(deref(), Double.valueOf(x)));
        }

        public Object vswapSubDouble(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() - x;
                setDvalField(nxt);
                return Double.valueOf(nxt);
            }
            return reset(Numbers.minus(deref(), Double.valueOf(x)));
        }

        public void vswapSubDiscard(Object x) {
            if (x instanceof Long lx) {
                vswapSubLongDiscard(lx);
                return;
            }
            if (x instanceof Double dx) {
                vswapSubDoubleDiscard(dx);
                return;
            }
            reset(Numbers.minus(deref(), x));
        }

        public Object vswapSub(Object x) {
            if (x instanceof Long lx) {
                return vswapSubLong(lx);
            }
            if (x instanceof Double dx) {
                return vswapSubDouble(dx);
            }
            return reset(Numbers.minus(deref(), x));
        }

        public void vswapMulLongDiscard(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long ax = cur < 0 ? -cur : cur;
                long ay = x < 0 ? -x : x;
                if (((ax | ay) >>> 31) == 0L) {
                    long nxt = cur * x;
                    if (isFastLong(nxt)) {
                        setFast(nxt << 1);
                        return;
                    }
                }
            } else if (getValField() == BOXED_DOUBLE) {
                setDvalField(getDvalField() * (double) x);
                return;
            }
            reset(Numbers.multiply(deref(), Long.valueOf(x)));
        }

        public Object vswapMulLong(long x) {
            long f = getFast();
            if ((f & 1L) == 0L) {
                long cur = f >> 1;
                long ax = cur < 0 ? -cur : cur;
                long ay = x < 0 ? -x : x;
                if (((ax | ay) >>> 31) == 0L) {
                    long nxt = cur * x;
                    if (isFastLong(nxt)) {
                        setFast(nxt << 1);
                        return Long.valueOf(nxt);
                    }
                }
            } else if (getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() * (double) x;
                setDvalField(nxt);
                return Double.valueOf(nxt);
            }
            return reset(Numbers.multiply(deref(), Long.valueOf(x)));
        }

        public void vswapMulDoubleDiscard(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                setDvalField(getDvalField() * x);
                return;
            }
            reset(Numbers.multiply(deref(), Double.valueOf(x)));
        }

        public Object vswapMulDouble(double x) {
            long f = getFast();
            if ((f & 1L) != 0L && getValField() == BOXED_DOUBLE) {
                double nxt = getDvalField() * x;
                setDvalField(nxt);
                return Double.valueOf(nxt);
            }
            return reset(Numbers.multiply(deref(), Double.valueOf(x)));
        }

        public void vswapMulDiscard(Object x) {
            if (x instanceof Long lx) {
                vswapMulLongDiscard(lx);
                return;
            }
            if (x instanceof Double dx) {
                vswapMulDoubleDiscard(dx);
                return;
            }
            reset(Numbers.multiply(deref(), x));
        }

        public Object vswapMul(Object x) {
            if (x instanceof Long lx) {
                return vswapMulLong(lx);
            }
            if (x instanceof Double dx) {
                return vswapMulDouble(dx);
            }
            return reset(Numbers.multiply(deref(), x));
        }

        @Override
        public String toString() {
            return "#<Volatile " + RT.printString(deref()) + ">";
        }
    }

    public static final class Delay implements IDeref {
        private volatile Fn fn;
        private Object val;
        private Throwable exception;

        public Delay(Fn fn) {
            this.fn = fn;
        }

        @Override
        public Object deref() {
            if (fn != null) {
                synchronized (this) {
                    if (fn != null) {
                        try {
                            val = fn.invoke();
                        } catch (Throwable t) {
                            exception = t;
                        } finally {
                            fn = null;
                        }
                    }
                }
            }
            if (exception != null) throw RT.sneakyThrow(exception);
            return val;
        }

        public boolean isRealized() {
            return fn == null;
        }

        @Override
        public String toString() {
            if (!isRealized()) return "#<Delay :pending>";
            if (exception != null) return "#<Delay :failed " + exception + ">";
            return "#<Delay " + RT.printString(val) + ">";
        }
    }

    public static final class Promise extends Fn implements IDeref {
        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile Object val;

        @Override
        public Object deref() {
            try {
                latch.await();
                return val;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        @Override
        public Object deref(long timeoutMs, Object timeoutVal) {
            try {
                if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return val;
                }
                return timeoutVal;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        public synchronized Promise deliver(Object v) {
            if (latch.getCount() > 0) {
                val = v;
                latch.countDown();
                return this;
            }
            return null;
        }

        public boolean isRealized() {
            return latch.getCount() == 0;
        }

        @Override
        public Object invoke(Object v) {
            return deliver(v);
        }

        @Override
        public String toString() {
            return "#<Promise " + (isRealized() ? RT.printString(val) : ":pending") + ">";
        }
    }

    public static final class FutureObj implements IDeref, Future<Object> {
        private final Future<Object> fut;

        public FutureObj(Fn fn) {
            Atom.markMultiThreaded();
            Map<Var, Object> bindings = Var.cloneThreadBindings();
            this.fut = VIRTUAL_EXECUTOR.submit(() -> {
                if (bindings.isEmpty()) {
                    return fn.invoke();
                }
                Var.pushThreadBindings(bindings);
                try {
                    return fn.invoke();
                } finally {
                    Var.popThreadBindings();
                }
            });
        }

        @Override
        public Object deref() {
            try {
                return fut.get();
            } catch (ExecutionException e) {
                throw RT.sneakyThrow(e.getCause() != null ? e.getCause() : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        @Override
        public Object deref(long timeoutMs, Object timeoutVal) {
            try {
                return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return timeoutVal;
            } catch (ExecutionException e) {
                throw RT.sneakyThrow(e.getCause() != null ? e.getCause() : e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) { return fut.cancel(mayInterruptIfRunning); }
        @Override public boolean isCancelled() { return fut.isCancelled(); }
        @Override public boolean isDone() { return fut.isDone(); }
        @Override public Object get() throws InterruptedException, ExecutionException { return fut.get(); }
        @Override public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException { return fut.get(timeout, unit); }

        @Override
        public String toString() {
            return "#<Future " + (fut.isDone() ? ":done" : ":pending") + ">";
        }
    }
}
