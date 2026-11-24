package cljl;

import cljl.lang.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Clojure coordinated STM Reference (Ref) with history chain, validator, and watches.
 */
public final class Ref extends Fn implements IRef, IReference, Comparable<Ref> {

    private static final AtomicLong IDS = new AtomicLong(0);
    public final long id = IDS.incrementAndGet();

    final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    final AtomicInteger faults = new AtomicInteger(0);

    static final class TVal {
        Object val;
        long point;
        TVal prior;
        TVal next;

        TVal(Object val, long point) {
            this.val = val;
            this.point = point;
            this.prior = this;
            this.next = this;
        }

        TVal(Object val, long point, TVal prior) {
            this.val = val;
            this.point = point;
            this.prior = prior;
            this.next = prior.next;
            prior.next = this;
            this.next.prior = this;
        }
    }

    volatile TVal tvals;
    volatile LockingTransaction.Info tinfo;
    volatile int minHistory = 0;
    volatile int maxHistory = 10;
    volatile Fn validator;
    volatile PersistentHashMap meta;
    final ConcurrentHashMap<Object, Fn> watches = new ConcurrentHashMap<>();

    public Ref(Object initVal, PersistentHashMap meta, Fn validator, int minHistory, int maxHistory) {
        this.meta = meta != null ? meta : PersistentHashMap.EMPTY;
        this.validator = validator;
        this.minHistory = minHistory;
        this.maxHistory = maxHistory;
        if (validator != null && !RT.truthy(validator.invoke(initVal))) {
            throw new IllegalStateException("Invalid reference state");
        }
        this.tvals = new TVal(initVal, 0);
    }

    @Override
    public Object deref() {
        LockingTransaction t = LockingTransaction.get();
        if (t == null) {
            return currentVal();
        }
        return t.doGet(this);
    }

    Object currentVal() {
        lock.readLock().lock();
        try {
            return tvals.val;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Object set(Object val) {
        return LockingTransaction.getEx().doSet(this, val);
    }

    public Object alter(Fn fn, ISeq args) {
        LockingTransaction tx = LockingTransaction.getEx();
        Object cur = tx.doGet(this);
        Object[] callArgs = new Object[1 + RT.count(args)];
        callArgs[0] = cur;
        int i = 1;
        for (ISeq s = args; s != null; s = s.next()) callArgs[i++] = s.first();
        return tx.doSet(this, fn.applyTo(callArgs));
    }

    public Object commute(Fn fn, ISeq args) {
        return LockingTransaction.getEx().doCommute(this, fn, args);
    }

    public Object touch() {
        return LockingTransaction.getEx().doEnsure(this);
    }

    public int getHistoryCount() {
        lock.readLock().lock();
        try {
            if (tvals == null) return 0;
            int count = 0;
            TVal cur = tvals;
            do {
                count++;
                cur = cur.next;
            } while (cur != tvals);
            return count - 1;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getMinHistory() { return minHistory; }
    public Ref setMinHistory(int minHistory) { this.minHistory = minHistory; return this; }
    public int getMaxHistory() { return maxHistory; }
    public Ref setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; return this; }

    public Fn getValidator() { return validator; }
    public void setValidator(Fn valFn) {
        lock.writeLock().lock();
        try {
            if (valFn != null && !RT.truthy(valFn.invoke(tvals.val))) {
                throw new IllegalStateException("Invalid reference state");
            }
            this.validator = valFn;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addWatch(Object key, Fn cb) { watches.put(key, cb); }
    public void removeWatch(Object key) { watches.remove(key); }

    void notifyWatches(Object oldVal, Object newVal) {
        for (Map.Entry<Object, Fn> e : watches.entrySet()) {
            try {
                e.getValue().invoke(e.getKey(), this, oldVal, newVal);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public PersistentHashMap meta() { return meta; }

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
        this.meta = PersistentHashMap.fromMap(alter.applyTo(callArgs));
        return this.meta;
    }

    @Override
    public int compareTo(Ref o) {
        return Long.compare(this.id, o.id);
    }

    @Override
    public String toString() {
        return "#<Ref@" + Integer.toHexString(System.identityHashCode(this)) + ": " + RT.printString(currentVal(), true) + ">";
    }
}
