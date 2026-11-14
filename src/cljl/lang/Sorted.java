package cljl.lang;

import java.util.Comparator;

/**
 * Interface for sorted collections supporting subseq, rsubseq, comparator, and rseq.
 */
public interface Sorted {
    Comparator<?> comparator();
    Object entryKey(Object entry);
    ISeq seq(boolean ascending);
    ISeq seqFrom(Object key, boolean ascending);
}
