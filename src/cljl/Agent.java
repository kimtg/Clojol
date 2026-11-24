package cljl;

import cljl.lang.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clojure Agent: uncoordinated asynchronous reference with serialized per-agent action queue,
 * error handling (:continue / :fail), watches, validators, and STM integration.
 */
public final class Agent extends Fn implements IRef, IReference {

    private static final boolean NON_DAEMON_POOLS = Boolean.getBoolean("cljl.agent.non-daemon");

    public static volatile ExecutorService pooledExecutor =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() + 2),
                    r -> {
                        Thread t = new Thread(r, "cljl-agent-pool");
                        t.setDaemon(!NON_DAEMON_POOLS);
                        return t;
                    });

    public static volatile ExecutorService soloExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "cljl-agent-solo");
                t.setDaemon(!NON_DAEMON_POOLS);
                return t;
            });

    public static final Var CURRENT_AGENT = RT.AGENT;

    private static final ThreadLocal<List<Action>> NESTED_SENDS = new ThreadLocal<>();

    private record Action(Agent agent, Fn fn, Object[] args, Executor exec, Object bindingFrame, CountDownLatch barrier) {}

    private final Queue<Action> queue = new ArrayDeque<>();
    private boolean running = false;

    private volatile Object state;
    private volatile Throwable error;
    private volatile Fn errorHandler;
    private volatile Keyword errorMode;
    private volatile Fn validator;
    private volatile PersistentHashMap meta;
    private final ConcurrentHashMap<Object, Fn> watches = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public Agent(Object initialState, PersistentHashMap meta, Fn validator, Fn errorHandler, Keyword errorMode) {
        this.meta = meta != null ? meta : PersistentHashMap.EMPTY;
        this.validator = validator;
        this.errorHandler = errorHandler;
        this.errorMode = errorMode != null ? errorMode : Keyword.intern(errorHandler != null ? "continue" : "fail");
        if (validator != null) {
            validateState(validator, initialState);
        }
        this.state = initialState;
    }

    private static void validateState(Fn valFn, Object val) {
        if (!RT.truthy(valFn.invoke(val))) {
            throw new IllegalStateException("Invalid reference state");
        }
    }

    @Override
    public Object deref() {
        return state;
    }

    public Object getError() {
        return error;
    }

    public Object getRawState() {
        return state;
    }

    public Fn getErrorHandler() {
        return errorHandler;
    }

    public void setErrorHandler(Fn handler) {
        this.errorHandler = handler;
    }

    public Keyword getErrorMode() {
        return errorMode;
    }

    public void setErrorMode(Keyword mode) {
        if (!Keyword.intern("continue").equals(mode) && !Keyword.intern("fail").equals(mode)) {
            throw new IllegalArgumentException("Agent error-mode must be :continue or :fail");
        }
        this.errorMode = mode;
    }

    public Fn getValidator() {
        return validator;
    }

    public void setValidator(Fn valFn) {
        synchronized (lock) {
            if (valFn != null) validateState(valFn, state);
            this.validator = valFn;
        }
    }

    public void addWatch(Object key, Fn callback) {
        watches.put(key, callback);
    }

    public void removeWatch(Object key) {
        watches.remove(key);
    }

    private void notifyWatches(Object oldVal, Object newVal) {
        for (Map.Entry<Object, Fn> e : watches.entrySet()) {
            try {
                e.getValue().invoke(e.getKey(), this, oldVal, newVal);
            } catch (Throwable ignored) {}
        }
    }

    public Agent dispatch(Fn fn, Object[] args, Executor exec) {
        Throwable curErr = this.error;
        if (curErr != null) {
            throw new RuntimeException("Agent is failed, needs restart", curErr);
        }
        Action action = new Action(this, fn, args != null ? args : Fn.EMPTY_ARGS, exec, Var.cloneThreadBindingFrame(), null);
        if (LockingTransaction.isRunning()) {
            LockingTransaction.getEx().enqueue(action);
            return this;
        }
        List<Action> nested = NESTED_SENDS.get();
        if (nested != null) {
            nested.add(action);
            return this;
        }
        enqueueAction(action);
        return this;
    }

    void enqueueAction(Action action) {
        boolean schedule = false;
        synchronized (lock) {
            if (this.error != null) {
                throw new RuntimeException("Agent is failed, needs restart", this.error);
            }
            queue.add(action);
            if (!running) {
                running = true;
                schedule = true;
            }
        }
        if (schedule) {
            try {
                action.exec.execute(() -> processQueue(action.exec));
            } catch (Throwable t) {
                synchronized (lock) {
                    queue.remove(action);
                    running = false;
                    lock.notifyAll();
                }
                throw t;
            }
        }
    }

    private void enqueueBarrier(CountDownLatch latch) {
        boolean schedule = false;
        Executor exec = pooledExecutor;
        synchronized (lock) {
            if (this.error != null || (!running && queue.isEmpty())) {
                latch.countDown();
                return;
            }
            Action barrier = new Action(this, null, Fn.EMPTY_ARGS, exec, null, latch);
            queue.add(barrier);
            if (!running) {
                running = true;
                schedule = true;
            }
        }
        if (schedule) {
            try {
                exec.execute(() -> processQueue(exec));
            } catch (Throwable t) {
                synchronized (lock) {
                    running = false;
                    releaseBarriersLocked();
                    lock.notifyAll();
                }
                throw t;
            }
        }
    }

    private void releaseBarriersLocked() {
        Iterator<Action> it = queue.iterator();
        while (it.hasNext()) {
            Action a = it.next();
            if (a.barrier != null) {
                a.barrier.countDown();
                it.remove();
            }
        }
    }

    public static void dispatchAction(Object act) {
        Action a = (Action) act;
        a.agent.enqueueAction(a);
    }

    private void processQueue(Executor currentExec) {
        while (true) {
            Action act;
            Executor handoffExec = null;
            synchronized (lock) {
                if (this.error != null) {
                    releaseBarriersLocked();
                    running = false;
                    lock.notifyAll();
                    return;
                }
                Action peeked = queue.peek();
                if (peeked == null) {
                    running = false;
                    lock.notifyAll();
                    return;
                }
                if (peeked.barrier != null) {
                    act = queue.poll();
                } else if (peeked.exec != currentExec) {
                    handoffExec = peeked.exec;
                    act = null;
                } else {
                    act = queue.poll();
                }
            }
            if (handoffExec != null) {
                final Executor nextExec = handoffExec;
                try {
                    nextExec.execute(() -> processQueue(nextExec));
                    return;
                } catch (Throwable t) {
                    synchronized (lock) {
                        running = false;
                        releaseBarriersLocked();
                        lock.notifyAll();
                    }
                    throw t;
                }
            }
            if (act.barrier != null) {
                act.barrier.countDown();
            } else {
                executeAction(act);
            }
        }
    }

    private void executeAction(Action act) {
        Object savedFrame = Var.getThreadBindingFrame();
        Var.resetThreadBindingFrame(act.bindingFrame);
        Var.pushThreadBindings(Collections.singletonMap(CURRENT_AGENT, this));
        List<Action> nested = new ArrayList<>();
        NESTED_SENDS.set(nested);
        boolean succeeded = false;
        Object oldVal = null;
        Object newVal = null;
        Throwable err = null;
        try {
            oldVal = this.state;
            Object[] full = new Object[act.args.length + 1];
            full[0] = oldVal;
            System.arraycopy(act.args, 0, full, 1, act.args.length);
            newVal = act.fn.applyTo(full);
            Fn v = this.validator;
            if (v != null) {
                validateState(v, newVal);
            }
            this.state = newVal;
            succeeded = true;
        } catch (Throwable t) {
            err = t;
            if (!Keyword.intern("continue").equals(this.errorMode)) {
                synchronized (lock) {
                    this.error = t;
                    releaseBarriersLocked();
                    lock.notifyAll();
                }
            }
        } finally {
            // Clear NESTED_SENDS before watches, nested sends, or error handler execute
            NESTED_SENDS.remove();
        }
        try {
            if (succeeded) {
                notifyWatches(oldVal, newVal);
                for (Action a : nested) {
                    try {
                        a.agent.enqueueAction(a);
                    } catch (Throwable ignored) {}
                }
            } else if (err != null) {
                Fn handler = this.errorHandler;
                if (handler != null) {
                    try {
                        handler.invoke(this, err);
                    } catch (Throwable ignored) {}
                }
            }
        } finally {
            Var.resetThreadBindingFrame(savedFrame);
        }
    }

    public Object restart(Object newState, boolean clearActions) {
        Action nextToRun = null;
        synchronized (lock) {
            if (this.error == null) {
                throw new IllegalStateException("Agent does not need a restart");
            }
            if (validator != null) {
                validateState(validator, newState);
            }
            this.state = newState;
            this.error = null;
            if (clearActions) {
                releaseBarriersLocked();
                queue.clear();
            } else if (!queue.isEmpty() && !running) {
                running = true;
                nextToRun = queue.peek();
            }
            lock.notifyAll();
        }
        if (nextToRun != null) {
            final Executor exec = nextToRun.exec;
            try {
                exec.execute(() -> processQueue(exec));
            } catch (Throwable t) {
                synchronized (lock) {
                    running = false;
                    releaseBarriersLocked();
                    lock.notifyAll();
                }
                throw t;
            }
        }
        return newState;
    }

    public boolean await(long timeoutMs) throws InterruptedException {
        if (LockingTransaction.isRunning()) {
            throw new IllegalStateException("I/O in transaction");
        }
        if (NESTED_SENDS.get() != null) {
            throw new IllegalStateException("Can't await in agent action");
        }
        CountDownLatch latch = new CountDownLatch(1);
        enqueueBarrier(latch);
        if (timeoutMs < 0) {
            latch.await();
            return true;
        }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public static void awaitAll(Agent[] agents) throws InterruptedException {
        if (LockingTransaction.isRunning()) {
            throw new IllegalStateException("I/O in transaction");
        }
        if (NESTED_SENDS.get() != null) {
            throw new IllegalStateException("Can't await in agent action");
        }
        if (agents == null || agents.length == 0) return;
        CountDownLatch latch = new CountDownLatch(agents.length);
        for (Agent a : agents) {
            a.enqueueBarrier(latch);
        }
        latch.await();
    }

    public static boolean awaitForAll(long ms, Agent[] agents) throws InterruptedException {
        if (LockingTransaction.isRunning()) {
            throw new IllegalStateException("I/O in transaction");
        }
        if (NESTED_SENDS.get() != null) {
            throw new IllegalStateException("Can't await in agent action");
        }
        if (agents == null || agents.length == 0) return true;
        CountDownLatch latch = new CountDownLatch(agents.length);
        for (Agent a : agents) {
            a.enqueueBarrier(latch);
        }
        return latch.await(Math.max(0L, ms), TimeUnit.MILLISECONDS);
    }

    public static int releasePendingSends() {
        LockingTransaction tx = LockingTransaction.get();
        if (tx != null) {
            return tx.releasePendingSends();
        }
        List<Action> nested = NESTED_SENDS.get();
        if (nested != null && !nested.isEmpty()) {
            int n = nested.size();
            List<Action> toSend = new ArrayList<>(nested);
            nested.clear();
            for (Action a : toSend) {
                try {
                    a.agent.enqueueAction(a);
                } catch (Throwable ignored) {}
            }
            return n;
        }
        return 0;
    }

    public static void shutdown() {
        pooledExecutor.shutdown();
        soloExecutor.shutdown();
    }

    @Override
    public PersistentHashMap meta() {
        return meta;
    }

    @Override
    public synchronized PersistentHashMap resetMeta(PersistentHashMap m) {
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
        return "#<Agent@" + Integer.toHexString(System.identityHashCode(this)) + ": " + RT.printString(state, true) + ">";
    }
}
