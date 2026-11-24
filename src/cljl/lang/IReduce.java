package cljl.lang;

import cljl.Fn;

/**
 * Internal reduction protocol supporting both 1-arg (no init) and 2-arg (with init) reduce.
 */
public interface IReduce extends clojure.lang.IReduce, IReduceInit {
    Object reduce(Fn f);

    @Override
    default Object reduce(clojure.lang.IFn f) {
        return reduce(Fn.fromIFn(f));
    }
}
