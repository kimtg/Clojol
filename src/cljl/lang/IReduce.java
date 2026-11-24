package cljl.lang;

import cljl.Fn;

/**
 * Internal reduction protocol supporting both 1-arg (no init) and 2-arg (with init) reduce.
 */
public interface IReduce extends IReduceInit {
    Object reduce(Fn f);
}
