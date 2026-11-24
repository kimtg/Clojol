package cljl.lang;

public interface IPersistentVector extends clojure.lang.IPersistentVector, IPersistentStack, Associative, Indexed, Sequential, Counted, Seqable, Reversible {
    @Override
    default int length() { return count(); }
    @Override
    default IPersistentVector assocN(int i, Object val) {
        return (IPersistentVector) assoc(Integer.valueOf(i), val);
    }
    @Override
    IPersistentVector cons(Object o);
}
