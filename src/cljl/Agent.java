package cljl;

import cljl.lang.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Clojure Agent: uncoordinated asynchronous reference with serialized per-agent action queue,
 * error handling (:continue / :fail), watches, validators, and STM integration.
 */
public final class Agent extends Fn implements IDeref, IReference {

    public static volatile ExecutorService pooledExecutor =
            Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() + 2),
                    r -> {
                        Thread t = new Thread(r, "cljl-agent-pool");
                        t.setDaemon(true);
                        return t;
                    });

    public static volatile ExecutorService soloExecutor =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "cljl-agent-solo");
                t.setDaemon(true);
                return t;
            });

    public static final Var CURRENT_AGENT = RT.AGENT;

    private static final ConcurrentHashMap<Agent, Boolean> ACTIVE_AGENTS = new ConcurrentHashMap<>();

    private record Action(Agent agent, Fn fn, Object[] args, Executor exec, Map<Var, Object> bindings) {}

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
        Throwable e = this.error;
        if (e != null) {
            throw new RuntimeException("Agent is failed, needs restart", e);
        }
        return state;
    }

    public Object getError() {
        return error;
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
        if (valFn != null) validateState(valFn, state);
        this.validator = valFn;
    }

    public void addWatch(Object key, Fn callback) {
        watches.put(key, callback);
    }

    public void removeWatch(Object key) {
        watches.remove(key);
    }

    private void notifyWatches(Object oldVal, Object newVal) {
        for (Map.Entry<Object, Fn> e : watches.entrySet()) {
            e.getValue().invoke(e.getKey(), this, oldVal, newVal);
        }
    }

    public Agent dispatch(Fn fn, Object[] args, Executor exec) {
        Throwable curErr = this.error;
        if (curErr != null) {
            throw new RuntimeException("Agent is failed, needs restart", curErr);
        }
        Action action = new Action(this, fn, args != null ? args : Fn.EMPTY_ARGS, exec, Var.getThreadBindings());
        if (LockingTransaction.isRunning()) {
            LockingTransaction.getEx().enqueue(action);
            return this;
        }
        enqueueAction(action);
        return this;
    }

    void enqueueAction(Action action) {
        boolean schedule = false;
        synchronized (lock) {
            if (this.error != null) return;
            queue.add(action);
            if (!running) {
                running = true;
                schedule = true;
                ACTIVE_AGENTS.put(this, Boolean.TRUE);
            }
        }
        if (schedule) {
            action.exec.execute(() -> processQueue());
        }
    }

    public static void dispatchAction(Object act) {
        Action a = (Action) act;
        a.agent.enqueueAction(a);
    }

    private void processQueue() {
        while (true) {
            Action act;
            synchronized (lock) {
                act = queue.poll();
                if (act == null || this.error != null) {
                    running = false;
                    ACTIVE_AGENTS.remove(this);
                    lock.notifyAll();
                    return;
                }
            }
            executeAction(act);
        }
    }

    private void executeAction(Action act) {
        Map<Var, Object> frame = act.bindings != null ? new HashMap<>(act.bindings) : new HashMap<>();
        frame.put(CURRENT_AGENT, this);
        Var.pushThreadBindings(frame);
        try {
            Object oldVal = this.state;
            Object[] full = new Object[act.args.length + 1];
            full[0] = oldVal;
            System.arraycopy(act.args, 0, full, 1, act.args.length);
            Object newVal = act.fn.applyTo(full);
            if (validator != null) {
                validateState(validator, newVal);
            }
            this.state = newVal;
            notifyWatches(oldVal, newVal);
        } catch (Throwable t) {
            Fn handler = this.errorHandler;
            if (handler != null) {
                try {
                    handler.invoke(this, t);
                } catch (Throwable ignored) {}
            }
            if (Keyword.intern("fail").equals(this.errorMode)) {
                synchronized (lock) {
                    this.error = t;
                    this.queue.clear();
                }
            }
        } finally {
            Var.popThreadBindings();
        }
    }

    public Object restart(Object newState, boolean clearActions) {
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
                queue.clear();
            } else if (!queue.isEmpty() && !running) {
                running = true;
                ACTIVE_AGENTS.put(this, Boolean.TRUE);
                Action next = queue.peek();
                next.exec.execute(this::processQueue);
            }
            lock.notifyAll();
            return newState;
        }
    }

    public boolean await(long timeoutMs) throws InterruptedException {
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        synchronized (lock) {
            while (running || !queue.isEmpty()) {
                if (timeoutMs == 0) return false;
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) return false;
                lock.wait(rem);
            }
            return true;
        }
    }

    public static void awaitAll(Agent[] agents) throws InterruptedException {
        for (Agent a : agents) {
            a.await(-1);
        }
    }

    public static boolean awaitForAll(long ms, Agent[] agents) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        for (Agent a : agents) {
            long rem = Math.max(0, deadline - System.currentTimeMillis());
            if (!a.await(rem)) return false;
        }
        return true;
    }

    public static int releasePendingSends() {
        LockingTransaction tx = LockingTransaction.get();
        if (tx == null) return 0;
        return tx.releasePendingSends();
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
