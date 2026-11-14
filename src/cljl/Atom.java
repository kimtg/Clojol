package cljl;

import cljl.lang.IDeref;
import cljl.lang.IMeta;
import cljl.lang.IReference;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Concurrency and state primitives: Atom, Volatile, Delay, Promise, FutureObj.
 * Futures run on Java 25 Virtual Threads for near-zero overhead concurrency.
 */
public final class Atom implements IDeref, IMeta, IReference {
    public static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicReference<Object> state;
    private volatile PersistentHashMap meta;
    private volatile Fn validator;
    private volatile ConcurrentHashMap<Object, Fn> watches;

    public Atom(Object state) {
        this.state = new AtomicReference<>(state);
        this.meta = null;
    }

    public Atom(Object state, PersistentHashMap meta) {
        this.state = new AtomicReference<>(state);
        this.meta = meta;
    }

    @Override
    public Object deref() {
        return state.get();
    }

    private void validate(Object val) {
        Fn v = this.validator;
        if (v != null && !RT.truthy(v.invoke(val))) {
            throw new IllegalStateException("Invalid reference state: " + val);
        }
    }

    public void setValidator(Fn validator) {
        if (validator != null) validate(state.get());
        this.validator = validator;
    }

    public Fn getValidator() {
        return validator;
    }

    public Object reset(Object newval) {
        validate(newval);
        Object oldval = state.getAndSet(newval);
        notifyWatches(oldval, newval);
        return newval;
    }

    public boolean compareAndSet(Object oldv, Object newv) {
        validate(newv);
        boolean ret = state.compareAndSet(oldv, newv);
        if (ret) notifyWatches(oldv, newv);
        return ret;
    }

    public Object swap(Fn f) {
        for (;;) {
            Object v = deref();
            Object newv = f.invoke(v);
            if (compareAndSet(v, newv)) return newv;
        }
    }

    public Object swap(Fn f, Object x) {
        for (;;) {
            Object v = deref();
            Object newv = f.invoke(v, x);
            if (compareAndSet(v, newv)) return newv;
        }
    }

    public Object swap(Fn f, Object x, Object y) {
        for (;;) {
            Object v = deref();
            Object newv = f.invoke(v, x, y);
            if (compareAndSet(v, newv)) return newv;
        }
    }

    public Object swap(Fn f, Object x, Object y, Object[] args) {
        for (;;) {
            Object v = deref();
            Object[] all = new Object[3 + args.length];
            all[0] = v;
            all[1] = x;
            all[2] = y;
            System.arraycopy(args, 0, all, 3, args.length);
            Object newv = f.applyTo(all);
            if (compareAndSet(v, newv)) return newv;
        }
    }

    public void addWatch(Object key, Fn callback) {
        if (watches == null) {
            synchronized (this) {
                if (watches == null) watches = new ConcurrentHashMap<>();
            }
        }
        watches.put(key, callback);
    }

    public void removeWatch(Object key) {
        if (watches != null) watches.remove(key);
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
        return "#<Atom " + RT.printString(state.get()) + ">";
    }

    public static final class Volatile implements IDeref {
        public volatile Object val;
        public Volatile(Object val) { this.val = val; }
        @Override public Object deref() { return val; }
        public Object reset(Object newval) { return this.val = newval; }
        @Override public String toString() { return "#<Volatile " + RT.printString(val) + ">"; }
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
