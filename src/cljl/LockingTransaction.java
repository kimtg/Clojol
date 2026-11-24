package cljl;

import cljl.lang.ISeq;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MVCC Software Transactional Memory engine for Clojure Refs (dosync, alter, commute, ref-set, ensure).
 */
public final class LockingTransaction {

    private static final int RETRY_LIMIT = 10000;
    private static final long LOCK_WAIT_MSECS = 100L;
    private static final long BARGE_WAIT_NANOS = 10_000_000L;

    static final int RUNNING = 0;
    static final int COMMITTING = 1;
    static final int RETRY = 2;
    static final int KILLED = 3;
    static final int COMMITTED = 4;

    private static final AtomicLong LAST_POINT = new AtomicLong(0);
    private static final ThreadLocal<LockingTransaction> TRANSACTION = new ThreadLocal<>();

    static final class RetryEx extends Error {
        static final RetryEx INSTANCE = new RetryEx();
        private RetryEx() { super(null, null, false, false); }
    }

    static final class Info {
        final AtomicInteger status;
        final long startPoint;
        final CountDownLatch latch;

        Info(int status, long startPoint) {
            this.status = new AtomicInteger(status);
            this.startPoint = startPoint;
            this.latch = new CountDownLatch(1);
        }

        boolean running() {
            int s = status.get();
            return s == RUNNING || s == COMMITTING;
        }
    }

    private record Commute(Fn fn, ISeq args) {}

    private Info info;
    private long readPoint;
    private long startPoint;
    private long startTime;
    private volatile boolean bodyRunning;

    private final Map<Ref, Object> vals = new HashMap<>();
    private final Set<Ref> sets = new HashSet<>();
    private final TreeMap<Ref, List<Commute>> commutes = new TreeMap<>();
    private final Set<Ref> ensures = new HashSet<>();
    private final List<Object> agentActions = new ArrayList<>();

    public static LockingTransaction get() {
        LockingTransaction t = TRANSACTION.get();
        return (t != null && t.bodyRunning && t.info != null) ? t : null;
    }

    public static boolean isRunning() {
        return get() != null;
    }

    public static LockingTransaction getEx() {
        LockingTransaction t = get();
        if (t == null) {
            throw new IllegalStateException("No transaction running");
        }
        return t;
    }

    public static Object runInTransaction(Callable<Object> fn) throws Exception {
        LockingTransaction t = TRANSACTION.get();
        if (t != null && t.bodyRunning && t.info != null) {
            return fn.call();
        }
        boolean created = false;
        if (t == null) {
            t = new LockingTransaction();
            TRANSACTION.set(t);
            created = true;
        }
        try {
            return t.run(fn);
        } finally {
            if (created) {
                TRANSACTION.remove();
            }
        }
    }

    private void getReadPoint() {
        this.readPoint = LAST_POINT.incrementAndGet();
    }

    private boolean bargeTimeElapsed() {
        return System.nanoTime() - startTime > BARGE_WAIT_NANOS;
    }

    private boolean barge(Info refinfo) {
        boolean barged = false;
        if (bargeTimeElapsed() && startPoint < refinfo.startPoint) {
            barged = refinfo.status.compareAndSet(RUNNING, KILLED);
            if (barged) {
                refinfo.latch.countDown();
            }
        }
        return barged;
    }

    private Object blockAndBail(Info refinfo) {
        stop(RETRY);
        try {
            refinfo.latch.await(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw RetryEx.INSTANCE;
    }

    private void stop(int status) {
        if (info != null) {
            synchronized (info) {
                info.status.set(status);
                info.latch.countDown();
            }
            info = null;
            vals.clear();
            sets.clear();
            commutes.clear();
            agentActions.clear();
        }
    }

    private void releaseIfEnsured(Ref ref) {
        if (ensures.remove(ref)) {
            ref.lock.readLock().unlock();
        }
    }

    private static void tryWriteLock(Ref ref) {
        try {
            if (!ref.lock.writeLock().tryLock(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS)) {
                throw RetryEx.INSTANCE;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RetryEx.INSTANCE;
        }
    }

    private Object lock(Ref ref) {
        releaseIfEnsured(ref);
        boolean unlocked = true;
        try {
            tryWriteLock(ref);
            unlocked = false;

            if (ref.tvals != null && ref.tvals.point > readPoint) {
                throw RetryEx.INSTANCE;
            }

            Info refinfo = ref.tinfo;
            if (refinfo != null && refinfo != info && refinfo.running()) {
                if (!barge(refinfo)) {
                    ref.lock.writeLock().unlock();
                    unlocked = true;
                    return blockAndBail(refinfo);
                }
            }
            ref.tinfo = info;
            return ref.tvals == null ? null : ref.tvals.val;
        } finally {
            if (!unlocked) {
                ref.lock.writeLock().unlock();
            }
        }
    }

    private Object run(Callable<Object> fn) throws Exception {
        boolean done = false;
        Object ret = null;
        ArrayList<Ref> locked = new ArrayList<>();
        ArrayList<Runnable> notifications = new ArrayList<>();
        List<Object> pendingActions = Collections.emptyList();

        for (int attempt = 0; !done && attempt < RETRY_LIMIT; attempt++) {
            try {
                getReadPoint();
                if (attempt == 0) {
                    startPoint = readPoint;
                    startTime = System.nanoTime();
                }
                info = new Info(RUNNING, startPoint);
                bodyRunning = true;
                ret = fn.call();
                // Freeze transaction write set before running commutes and committing
                bodyRunning = false;

                if (info.status.compareAndSet(RUNNING, COMMITTING)) {
                    TreeSet<Ref> allWrite = new TreeSet<>(sets);
                    allWrite.addAll(commutes.keySet());

                    for (Ref r : allWrite) {
                        boolean wasEnsured = ensures.contains(r);
                        releaseIfEnsured(r);
                        tryWriteLock(r);
                        locked.add(r);

                        if ((sets.contains(r) || wasEnsured) && r.tvals != null && r.tvals.point > readPoint) {
                            throw RetryEx.INSTANCE;
                        }
                        Info refinfo = r.tinfo;
                        if (sets.contains(r)) {
                            if (refinfo != info) {
                                throw RetryEx.INSTANCE;
                            }
                        } else if (refinfo != null && refinfo != info && refinfo.running()) {
                            if (!barge(refinfo)) {
                                throw RetryEx.INSTANCE;
                            }
                        }
                    }

                    // Re-run pure commutes (not preceded by doSet in this tx) against latest committed state
                    for (Map.Entry<Ref, List<Commute>> e : commutes.entrySet()) {
                        Ref r = e.getKey();
                        if (sets.contains(r)) continue;
                        Object val = r.tvals == null ? null : r.tvals.val;
                        for (Commute c : e.getValue()) {
                            val = applyFn(c.fn, val, c.args);
                        }
                        vals.put(r, val);
                    }

                    // Validate before mutating
                    for (Ref r : allWrite) {
                        Fn v = r.validator;
                        if (v != null && !RT.truthy(v.invoke(vals.get(r)))) {
                            throw new IllegalStateException("Invalid reference state");
                        }
                    }

                    long commitPoint = LAST_POINT.incrementAndGet();
                    for (Ref r : allWrite) {
                        Object oldVal = r.tvals.val;
                        Object newVal = vals.get(r);
                        int count = r.getHistoryCount();
                        int hfaults = r.faults.get();
                        if (count < r.minHistory || (hfaults > 0 && count < r.maxHistory)) {
                            r.tvals = new Ref.TVal(newVal, commitPoint, r.tvals);
                            if (hfaults > 0) r.faults.set(0);
                        } else {
                            Ref.TVal reused = r.tvals.next;
                            reused.val = newVal;
                            reused.point = commitPoint;
                            r.tvals = reused;
                        }
                        r.tinfo = null;
                        if (!r.watches.isEmpty()) {
                            final Object ov = oldVal;
                            final Object nv = newVal;
                            notifications.add(() -> r.notifyWatches(ov, nv));
                        }
                    }

                    done = true;
                    info.status.set(COMMITTED);
                    if (!agentActions.isEmpty()) {
                        pendingActions = new ArrayList<>(agentActions);
                    }
                }
            } catch (RetryEx r) {
                // retry transaction
            } finally {
                bodyRunning = false;
                for (int k = locked.size() - 1; k >= 0; k--) {
                    locked.get(k).lock.writeLock().unlock();
                }
                locked.clear();
                for (Ref r : ensures) {
                    r.lock.readLock().unlock();
                }
                ensures.clear();
                stop(done ? COMMITTED : RETRY);
                if (!done) {
                    notifications.clear();
                }
            }
        }

        if (!done) {
            throw new RuntimeException("Transaction failed after reaching retry limit");
        }

        // Post-commit dispatch with per-action and per-watch exception isolation
        for (Object a : pendingActions) {
            try {
                Agent.dispatchAction(a);
            } catch (Throwable ignored) {}
        }
        for (Runnable n : notifications) {
            try {
                n.run();
            } catch (Throwable ignored) {}
        }
        return ret;
    }

    public Object doGet(Ref ref) {
        if (!info.running()) {
            throw RetryEx.INSTANCE;
        }
        if (vals.containsKey(ref)) {
            return vals.get(ref);
        }
        ref.lock.readLock().lock();
        try {
            Ref.TVal ver = ref.tvals;
            Ref.TVal head = ver;
            do {
                if (ver.point <= readPoint) {
                    return ver.val;
                }
                ver = ver.prior;
            } while (ver != head);
            ref.faults.incrementAndGet();
            throw RetryEx.INSTANCE;
        } finally {
            ref.lock.readLock().unlock();
        }
    }

    public Object doSet(Ref ref, Object val) {
        if (!info.running()) {
            throw RetryEx.INSTANCE;
        }
        if (commutes.containsKey(ref)) {
            throw new IllegalStateException("Can't set after commute");
        }
        if (!sets.contains(ref)) {
            sets.add(ref);
            lock(ref);
        }
        vals.put(ref, val);
        return val;
    }

    public Object doCommute(Ref ref, Fn fn, ISeq args) {
        if (!info.running()) {
            throw RetryEx.INSTANCE;
        }
        if (!vals.containsKey(ref)) {
            Object val;
            ref.lock.readLock().lock();
            try {
                val = ref.tvals == null ? null : ref.tvals.val;
            } finally {
                ref.lock.readLock().unlock();
            }
            vals.put(ref, val);
        }
        commutes.computeIfAbsent(ref, k -> new ArrayList<>()).add(new Commute(fn, args));
        Object next = applyFn(fn, vals.get(ref), args);
        vals.put(ref, next);
        return next;
    }

    public Object doEnsure(Ref ref) {
        if (!info.running()) {
            throw RetryEx.INSTANCE;
        }
        if (ensures.contains(ref)) {
            return doGet(ref);
        }
        ref.lock.readLock().lock();
        if (ref.tvals != null && ref.tvals.point > readPoint) {
            ref.lock.readLock().unlock();
            throw RetryEx.INSTANCE;
        }
        Info refinfo = ref.tinfo;
        if (refinfo != null && refinfo.running()) {
            ref.lock.readLock().unlock();
            if (refinfo != info) {
                blockAndBail(refinfo);
            }
            return doGet(ref);
        }
        ensures.add(ref);
        return doGet(ref);
    }

    public void enqueue(Object action) {
        agentActions.add(action);
    }

    public int releasePendingSends() {
        int n = agentActions.size();
        List<Object> toSend = new ArrayList<>(agentActions);
        agentActions.clear();
        for (Object a : toSend) {
            try {
                Agent.dispatchAction(a);
            } catch (Throwable ignored) {}
        }
        return n;
    }

    private static Object applyFn(Fn fn, Object cur, ISeq args) {
        Object[] callArgs = new Object[1 + RT.count(args)];
        callArgs[0] = cur;
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        return fn.applyTo(callArgs);
    }
}
