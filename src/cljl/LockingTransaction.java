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
    private boolean running;
    private final Map<Ref, Object> vals = new TreeMap<>();
    private final Map<Ref, Object> setVals = new TreeMap<>();
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
        setVals.clear();
        sets.clear();
        commutes.clear();
        ensures.clear();
        agentActions.clear();
    }

    private Object run(Callable<Object> fn) throws Exception {
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
            List<Runnable> notifications = new ArrayList<>();
            if (commit(notifications)) {
                this.running = false;
                try {
                    for (Object a : agentActions) {
                        Agent.dispatchAction(a);
                    }
                } finally {
                    for (Runnable n : notifications) {
                        n.run();
                    }
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
            ref.faults.incrementAndGet();
            throw RetryEx.INSTANCE;
        } finally {
            ref.lock.readLock().unlock();
        }
    }

    public Object doSet(Ref ref, Object val) {
        sets.add(ref);
        vals.put(ref, val);
        setVals.put(ref, val);
        commutes.remove(ref);
        return val;
    }

    public Object doCommute(Ref ref, Fn fn, ISeq args) {
        Object cur;
        if (vals.containsKey(ref)) {
            cur = vals.get(ref);
        } else {
            cur = doGet(ref);
            vals.put(ref, cur);
        }
        commutes.computeIfAbsent(ref, k -> new ArrayList<>()).add(new Commute(fn, args));
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

    private boolean commit(List<Runnable> notifications) {
        Set<Ref> writeLocked = new TreeSet<>();
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

            // Re-run commutes against latest committed state (or last doSet in this tx)
            for (Map.Entry<Ref, List<Commute>> e : commutes.entrySet()) {
                Ref r = e.getKey();
                Object val = sets.contains(r) ? setVals.get(r) : r.tvals.val;
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
                if (!r.watches.isEmpty()) {
                    notifications.add(() -> r.notifyWatches(oldVal, newVal));
                }
            }
        } finally {
            for (Ref r : writeLocked) r.lock.writeLock().unlock();
        }
        return true;
    }
}
