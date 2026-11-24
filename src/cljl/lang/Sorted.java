package cljl.lang;

import java.util.Comparator;

/**
 * Interface for sorted collections supporting subseq, rsubseq, comparator, and rseq.
 */
public interface Sorted extends clojure.lang.Sorted {
    @Override
    Comparator<?> comparator();
    @Override
    Object entryKey(Object entry);
    @Override
    ISeq seq(boolean ascending);
    @Override
    ISeq seqFrom(Object key, boolean ascending);
}
