package clojure.lang;

/**
 * Real clojure.lang.Atom base class extended by cljl.Atom.
 * Supports both zero-allocation Clojol subclassing and direct Java `new clojure.lang.Atom(val)`.
 */
public class Atom implements IRef, IReference, IAtom2 {
    private final cljl.Atom delegate;

    protected Atom(boolean internalMarker) {
        this.delegate = null;
    }

    public Atom(Object state) {
        this.delegate = new cljl.Atom(state);
    }

    public Atom(Object state, IPersistentMap meta) {
        this.delegate = new cljl.Atom(state);
        if (meta != null) this.delegate.resetMeta(cljl.lang.PersistentHashMap.fromMap(meta));
    }

    private cljl.Atom target() {
        return delegate != null ? delegate : (cljl.Atom) this;
    }

    @Override public Object deref() { return target().deref(); }
    @Override public Object swap(IFn f) { return target().swap(f); }
    @Override public Object swap(IFn f, Object arg) { return target().swap(f, arg); }
    @Override public Object swap(IFn f, Object arg1, Object arg2) { return target().swap(f, arg1, arg2); }
    @Override public Object swap(IFn f, Object x, Object y, ISeq args) { return target().swap(f, x, y, args); }
    @Override public boolean compareAndSet(Object oldv, Object newv) { return target().compareAndSet(oldv, newv); }
    @Override public Object reset(Object newval) { return target().reset(newval); }
    @Override public IPersistentVector swapVals(IFn f) { return target().swapVals(f); }
    @Override public IPersistentVector swapVals(IFn f, Object arg) { return target().swapVals(f, arg); }
    @Override public IPersistentVector swapVals(IFn f, Object arg1, Object arg2) { return target().swapVals(f, arg1, arg2); }
    @Override public IPersistentVector swapVals(IFn f, Object x, Object y, ISeq args) { return target().swapVals(f, x, y, args); }
    @Override public IPersistentVector resetVals(Object newv) { return target().resetVals(newv); }
    @Override public void setValidator(IFn vf) { target().setValidator(cljl.Fn.fromIFn(vf)); }
    @Override public IFn getValidator() { return target().getValidator(); }
    @Override public cljl.lang.PersistentHashMap getWatches() { return target().getWatches(); }
    @Override public IRef addWatch(Object key, IFn callback) { return target().addWatch(key, cljl.Fn.fromIFn(callback)); }
    @Override public IRef removeWatch(Object key) { return target().removeWatch(key); }
    @Override public IPersistentMap meta() { return target().meta(); }
    @Override public IPersistentMap alterMeta(IFn alter, ISeq args) {
        return target().alterMeta(cljl.Fn.fromIFn(alter), cljl.RT.seq(args));
    }
    @Override public IPersistentMap resetMeta(IPersistentMap m) {
        return target().resetMeta(cljl.lang.PersistentHashMap.fromMap(m));
    }
}
