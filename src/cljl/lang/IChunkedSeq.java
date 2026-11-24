package cljl.lang;

public interface IChunkedSeq extends clojure.lang.IChunkedSeq, ISeq, Seqable {
    @Override
    IChunk chunkedFirst();
    @Override
    ISeq chunkedNext();
    @Override
    ISeq chunkedMore();
}
