package cljl;

import cljl.lang.IDeref;
import cljl.lang.IMeta;
import cljl.lang.IReference;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Concurrency and state primitives: Atom, Volatile, Delay, Promise, FutureObj.
 * Futures run on Java 25 Virtual Threads for near-zero overhead concurrency.
 */
public final class Atom implements IDeref, IMeta, IReference {
    public static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final Object UNBOXED = new Object();
    private static final long MIN_FAST = -(1L << 62);
    private static final long MAX_FAST = (1L << 62) - 1L;

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
            for (;;) {
                long f = this.fastVal;
                if ((f & 1L) != 0L) break;
                if (FAST_VH.compareAndSet(this, f, encoded)) return newval;
            }
        } else {
            ensureBoxed();
        }
        if (!this.hasHooks) {
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
            return STATE_VH.compareAndSet(this, oldv, newv);
        }
        validate(newv);
        boolean ret = STATE_VH.compareAndSet(this, oldv, newv);
        if (ret) notifyWatches(oldv, newv);
        return ret;
    }

    public void swapDiscard(Fn f) {
        if (f == Core.incFn) {
            for (;;) {
                long fv = this.fastVal;
                if ((fv & 1L) != 0L) break;
                long cur = fv >> 1;
                if (cur < MAX_FAST) {
                    if (FAST_VH.compareAndSet(this, fv, (cur + 1L) << 1)) return;
                } else {
                    ensureBoxed();
                    break;
                }
            }
        } else if (f == Core.decFn) {
            for (;;) {
                long fv = this.fastVal;
                if ((fv & 1L) != 0L) break;
                long cur = fv >> 1;
                if (cur > MIN_FAST) {
                    if (FAST_VH.compareAndSet(this, fv, (cur - 1L) << 1)) return;
                } else {
                    ensureBoxed();
                    break;
                }
            }
        }
        swap(f);
    }

    public void swapDiscard(Fn f, long x) {
        if (f == Core.addFn) {
            for (;;) {
                long fv = this.fastVal;
                if ((fv & 1L) != 0L) break;
                long cur = fv >> 1;
                long nxt = cur + x;
                if (((cur ^ nxt) & (x ^ nxt)) >= 0L && isFastLong(nxt)) {
                    if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return;
                } else {
                    ensureBoxed();
                    break;
                }
            }
        }
        swap(f, Long.valueOf(x));
    }

    public Object swap(Fn f) {
        if (f == Core.incFn) {
            for (;;) {
                long fv = this.fastVal;
                if ((fv & 1L) != 0L) break;
                long cur = fv >> 1;
                if (cur < MAX_FAST) {
                    long nxt = cur + 1L;
                    if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return Long.valueOf(nxt);
                } else {
                    ensureBoxed();
                    break;
                }
            }
        } else if (f == Core.decFn) {
            for (;;) {
                long fv = this.fastVal;
                if ((fv & 1L) != 0L) break;
                long cur = fv >> 1;
                if (cur > MIN_FAST) {
                    long nxt = cur - 1L;
                    if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return Long.valueOf(nxt);
                } else {
                    ensureBoxed();
                    break;
                }
            }
        } else {
            ensureBoxed();
        }
        if (!this.hasHooks) {
            for (;;) {
                Object v = getBoxedState();
                Object newv = f.invoke(v);
                if (STATE_VH.compareAndSet(this, v, newv)) return newv;
            }
        }
        for (;;) {
            Object v = getBoxedState();
            Object newv = f.invoke(v);
            validate(newv);
            if (STATE_VH.compareAndSet(this, v, newv)) {
                notifyWatches(v, newv);
                return newv;
            }
        }
    }

    public Object swap(Fn f, Object x) {
        if (f == Core.addFn && x instanceof Long lx) {
            long y = lx;
            for (;;) {
                long fv = this.fastVal;
                if ((fv & 1L) != 0L) break;
                long cur = fv >> 1;
                long nxt = cur + y;
                if (((cur ^ nxt) & (y ^ nxt)) >= 0L && isFastLong(nxt)) {
                    if (FAST_VH.compareAndSet(this, fv, nxt << 1)) return Long.valueOf(nxt);
                } else {
                    ensureBoxed();
                    break;
                }
            }
        } else {
            ensureBoxed();
        }
        if (!this.hasHooks) {
            for (;;) {
                Object v = getBoxedState();
                Object newv = f.invoke(v, x);
                if (STATE_VH.compareAndSet(this, v, newv)) return newv;
            }
        }
        for (;;) {
            Object v = getBoxedState();
            Object newv = f.invoke(v, x);
            validate(newv);
            if (STATE_VH.compareAndSet(this, v, newv)) {
                notifyWatches(v, newv);
                return newv;
            }
        }
    }

    public Object swap(Fn f, Object x, Object y) {
        ensureBoxed();
        if (!this.hasHooks) {
            for (;;) {
                Object v = getBoxedState();
                Object newv = f.invoke(v, x, y);
                if (STATE_VH.compareAndSet(this, v, newv)) return newv;
            }
        }
        for (;;) {
            Object v = getBoxedState();
            Object newv = f.invoke(v, x, y);
            validate(newv);
            if (STATE_VH.compareAndSet(this, v, newv)) {
                notifyWatches(v, newv);
                return newv;
            }
        }
    }

    public Object swap(Fn f, Object x, Object y, Object[] args) {
        ensureBoxed();
        for (;;) {
            Object v = getBoxedState();
            Object[] all = new Object[3 + args.length];
            all[0] = v;
            all[1] = x;
            all[2] = y;
            System.arraycopy(args, 0, all, 3, args.length);
            Object newv = f.applyTo(all);
            if (this.hasHooks) validate(newv);
            if (STATE_VH.compareAndSet(this, v, newv)) {
                if (this.hasHooks) notifyWatches(v, newv);
                return newv;
            }
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
        private static final Object BOXED_LONG = new Object();
        private static final Object BOXED_DOUBLE = new Object();

        public volatile Object val;
        public volatile long lval;
        public volatile double dval;

        public Volatile(Object val) {
            if (val instanceof Long l) {
                this.lval = l;
                this.val = BOXED_LONG;
            } else if (val instanceof Double d) {
                this.dval = d;
                this.val = BOXED_DOUBLE;
            } else {
                this.val = val;
            }
        }

        public Volatile(long val) {
            this.lval = val;
            this.val = BOXED_LONG;
        }

        public Volatile(double val) {
            this.dval = val;
            this.val = BOXED_DOUBLE;
        }

        @Override
        public Object deref() {
            Object o = val;
            if (o == BOXED_LONG) return Long.valueOf(lval);
            if (o == BOXED_DOUBLE) return Double.valueOf(dval);
            return o;
        }

        public long derefLong() {
            Object o = val;
            if (o == BOXED_LONG) return lval;
            if (o == BOXED_DOUBLE) return Numbers.longCast(dval);
            return Numbers.longCast(o);
        }

        public double derefDouble() {
            Object o = val;
            if (o == BOXED_DOUBLE) return dval;
            if (o == BOXED_LONG) return (double) lval;
            return Numbers.toDouble(o);
        }

        public Object reset(Object newval) {
            Object cur = val;
            if (cur == BOXED_LONG && newval instanceof Long l) {
                this.lval = l;
                return newval;
            }
            if (cur == BOXED_DOUBLE && newval instanceof Double d) {
                this.dval = d;
                return newval;
            }
            this.val = newval;
            return newval;
        }

        public long resetLong(long newval) {
            if (val == BOXED_LONG) {
                this.lval = newval;
            } else {
                this.val = Long.valueOf(newval);
            }
            return newval;
        }

        public double resetDouble(double newval) {
            if (val == BOXED_DOUBLE) {
                this.dval = newval;
            } else {
                this.val = Double.valueOf(newval);
            }
            return newval;
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
