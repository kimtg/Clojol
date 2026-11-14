package cljl;

import cljl.lang.ISeq;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MVCC Software Transactional Memory engine for Clojure Refs (dosync, alter, commute, ref-set, ensure).
 */
public final class LockingTransaction {

    private static final int RETRY_LIMIT = 10000;
    private static final AtomicLong LAST_POINT = new AtomicLong(0);
    private static final ThreadLocal<LockingTransaction> TRANSACTION = new ThreadLocal<>();

    private static final class RetryEx extends RuntimeException {
        static final RetryEx INSTANCE = new RetryEx();
        private RetryEx() { super(null, null, false, false); }
    }

    private record Commute(Fn fn, ISeq args) {}

    private long readPoint;
    private long startPoint;
    private boolean running;
    private final Map<Ref, Object> vals = new TreeMap<>();
    private final Set<Ref> sets = new TreeSet<>();
    private final Map<Ref, List<Commute>> commutes = new TreeMap<>();
    private final Set<Ref> ensures = new TreeSet<>();
    private final List<Object> agentActions = new ArrayList<>();

    public static LockingTransaction get() {
        LockingTransaction t = TRANSACTION.get();
        return (t != null && t.running) ? t : null;
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
        if (t != null && t.running) {
            return fn.call();
        }
        if (t == null) {
            t = new LockingTransaction();
            TRANSACTION.set(t);
        }
        try {
            return t.run(fn);
        } finally {
            TRANSACTION.remove();
        }
    }

    private void resetState() {
        vals.clear();
        sets.clear();
        commutes.clear();
        ensures.clear();
        agentActions.clear();
    }

    private Object run(Callable<Object> fn) throws Exception {
        this.startPoint = LAST_POINT.incrementAndGet();
        for (int attempt = 0; attempt < RETRY_LIMIT; attempt++) {
            resetState();
            this.readPoint = LAST_POINT.incrementAndGet();
            this.running = true;
            Object ret;
            try {
                ret = fn.call();
            } catch (RetryEx r) {
                this.running = false;
                continue;
            } catch (Throwable ex) {
                this.running = false;
                throw ex;
            }
            if (commit()) {
                this.running = false;
                for (Object a : agentActions) {
                    Agent.dispatchAction(a);
                }
                return ret;
            }
            this.running = false;
        }
        throw new RuntimeException("Transaction failed after reaching retry limit");
    }

    public Object doGet(Ref ref) {
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
            throw RetryEx.INSTANCE;
        } finally {
            ref.lock.readLock().unlock();
        }
    }

    public Object doSet(Ref ref, Object val) {
        sets.add(ref);
        vals.put(ref, val);
        return val;
    }

    public Object doCommute(Ref ref, Fn fn, ISeq args) {
        if (!vals.containsKey(ref)) {
            ref.lock.readLock().lock();
            try {
                vals.put(ref, ref.tvals.val);
            } finally {
                ref.lock.readLock().unlock();
            }
        }
        commutes.computeIfAbsent(ref, k -> new ArrayList<>()).add(new Commute(fn, args));
        Object cur = vals.get(ref);
        Object next = applyFn(fn, cur, args);
        vals.put(ref, next);
        return next;
    }

    public void doEnsure(Ref ref) {
        if (!ensures.contains(ref)) {
            ensures.add(ref);
            doGet(ref);
        }
    }

    public void enqueue(Object action) {
        agentActions.add(action);
    }

    public int releasePendingSends() {
        int n = agentActions.size();
        for (Object a : agentActions) {
            Agent.dispatchAction(a);
        }
        agentActions.clear();
        return n;
    }

    private static Object applyFn(Fn fn, Object cur, ISeq args) {
        Object[] callArgs = new Object[1 + RT.count(args)];
        callArgs[0] = cur;
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        return fn.applyTo(callArgs);
    }

    private boolean commit() {
        Set<Ref> writeLocked = new TreeSet<>();
        Set<Ref> readLocked = new TreeSet<>();
        List<Runnable> notifications = new ArrayList<>();
        try {
            Set<Ref> allWrite = new TreeSet<>();
            allWrite.addAll(sets);
            allWrite.addAll(commutes.keySet());
            allWrite.addAll(ensures);

            for (Ref r : allWrite) {
                r.lock.writeLock().lock();
                writeLocked.add(r);
            }

            // Conflict check: no ref we read/wrote/ensured may have committed after readPoint
            for (Ref r : sets) {
                if (r.tvals.point > readPoint) {
                    return false;
                }
            }
            for (Ref r : ensures) {
                if (r.tvals.point > readPoint) {
                    return false;
                }
            }

            // Re-run commutes against latest committed state
            for (Map.Entry<Ref, List<Commute>> e : commutes.entrySet()) {
                Ref r = e.getKey();
                Object val = sets.contains(r) ? vals.get(r) : r.tvals.val;
                for (Commute c : e.getValue()) {
                    val = applyFn(c.fn, val, c.args);
                }
                vals.put(r, val);
            }

            // Validate before mutating
            Set<Ref> committedRefs = new TreeSet<>();
            committedRefs.addAll(sets);
            committedRefs.addAll(commutes.keySet());
            for (Ref r : committedRefs) {
                Fn v = r.validator;
                if (v != null && !RT.truthy(v.invoke(vals.get(r)))) {
                    throw new IllegalStateException("Invalid reference state");
                }
            }

            long commitPoint = LAST_POINT.incrementAndGet();
            for (Ref r : committedRefs) {
                Object oldVal = r.tvals.val;
                Object newVal = vals.get(r);
                int count = r.getHistoryCount();
                if (count < r.maxHistory) {
                    r.tvals = new Ref.TVal(newVal, commitPoint, r.tvals);
                } else {
                    Ref.TVal reused = r.tvals.next;
                    reused.val = newVal;
                    reused.point = commitPoint;
                    r.tvals = reused;
                }
                if (!r.watches.isEmpty()) {
                    notifications.add(() -> r.notifyWatches(oldVal, newVal));
                }
            }
        } finally {
            for (Ref r : readLocked) r.lock.readLock().unlock();
            for (Ref r : writeLocked) r.lock.writeLock().unlock();
        }
        for (Runnable n : notifications) {
            n.run();
        }
        return true;
    }
}
