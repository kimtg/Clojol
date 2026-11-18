package cljl.lang;

import cljl.Fn;

public interface IChunk extends Counted {
    Object nth(int i);
    Object nth(int i, Object notFound);
    IChunk dropFirst();
    Object reduce(Fn f, Object start);
}
