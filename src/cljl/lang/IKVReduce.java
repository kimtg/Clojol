package cljl.lang;

import cljl.Fn;

/**
 * Key-value reduction protocol (clojure.lang.IKVReduce).
 */
public interface IKVReduce extends clojure.lang.IKVReduce {
    Object kvreduce(Fn f, Object init);

    @Override
    default Object kvreduce(clojure.lang.IFn f, Object init) {
        return kvreduce(Fn.fromIFn(f), init);
    }
}
