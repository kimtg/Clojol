package cljl.lang;

import cljl.Fn;

public interface IChunk extends clojure.lang.IChunk, Indexed {
    @Override
    Object nth(int i);
    @Override
    Object nth(int i, Object notFound);
    @Override
    IChunk dropFirst();
    Object reduce(Fn f, Object start);

    @Override
    default Object reduce(clojure.lang.IFn f, Object start) {
        return reduce(Fn.fromIFn(f), start);
    }
}
