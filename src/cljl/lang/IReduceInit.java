package cljl.lang;

import cljl.Fn;

/**
 * Internal reduction protocol for collections and ranges, avoiding per-element ISeq allocation.
 * Implementations must unwrap any {@link Reduced} sentinel before returning.
 */
public interface IReduceInit extends clojure.lang.IReduceInit {
    Object reduce(Fn f, Object init);

    @Override
    default Object reduce(clojure.lang.IFn f, Object start) {
        return reduce(Fn.fromIFn(f), start);
    }
}
