package clojure.lang;

import java.util.concurrent.Callable;

/**
 * Clean-room clojure.lang.LockingTransaction bridge delegating to cljl.LockingTransaction.
 */
public final class LockingTransaction {
    private LockingTransaction() {}

    public static cljl.LockingTransaction get() { return cljl.LockingTransaction.get(); }
    public static boolean isRunning() { return cljl.LockingTransaction.isRunning(); }
    public static cljl.LockingTransaction getEx() { return cljl.LockingTransaction.getEx(); }

    @SuppressWarnings("unchecked")
    public static Object runInTransaction(Callable<?> fn) throws Exception {
        return cljl.LockingTransaction.runInTransaction((Callable<Object>) fn);
    }
}
