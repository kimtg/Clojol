package cljl.lang;

public interface IChunkedSeq extends ISeq, Seqable {
    IChunk chunkedFirst();
    ISeq chunkedNext();
    ISeq chunkedMore();
}
