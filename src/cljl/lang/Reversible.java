package cljl.lang;

/**
 * Interface for collections that support O(1) or efficient reverse sequence traversal.
 */
public interface Reversible {
    ISeq rseq();
}
