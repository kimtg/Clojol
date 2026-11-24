package cljl.lang;

public interface IPersistentVector extends IPersistentStack, Associative, Indexed, Sequential, Counted, Seqable, Reversible {
    default int length() { return count(); }
}
