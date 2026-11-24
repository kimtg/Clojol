package clojure.lang;

/**
 * Real clojure.lang.Agent base class extended by cljl.Agent.
 */
public abstract class Agent extends cljl.Fn implements IRef, IReference {
    protected Agent() {}

    public static void shutdown() {
        cljl.Agent.shutdown();
    }

    public static int releasePendingSends() {
        return cljl.Agent.releasePendingSends();
    }
}
