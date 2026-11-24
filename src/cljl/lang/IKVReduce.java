package cljl.lang;

import cljl.Fn;

/**
 * Key-value reduction protocol (clojure.lang.IKVReduce).
 */
public interface IKVReduce {
    Object kvreduce(Fn f, Object init);
}
