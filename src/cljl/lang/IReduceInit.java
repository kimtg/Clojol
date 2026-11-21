package cljl.lang;

import cljl.Fn;

/**
 * Internal reduction protocol for collections and ranges, avoiding per-element ISeq allocation.
 * Implementations must unwrap any {@link Reduced} sentinel before returning.
 */
public interface IReduceInit {
    Object reduce(Fn f, Object init);
}
