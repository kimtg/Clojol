package cljl.lang;

public final class ChunkedCons extends ASeq implements IChunkedSeq {
    private final IChunk chunk;
    private final ISeq _more;

    public ChunkedCons(IChunk chunk, ISeq more) {
        super(null);
        this.chunk = chunk;
        this._more = more;
    }

    public ChunkedCons(PersistentHashMap meta, IChunk chunk, ISeq more) {
        super(meta);
        this.chunk = chunk;
        this._more = more;
    }

    @Override
    public Object first() {
        return chunk.nth(0);
    }

    @Override
    public ISeq next() {
        if (chunk.count() > 1) {
            return new ChunkedCons(chunk.dropFirst(), _more);
        }
        return chunkedNext();
    }

    @Override
    public IChunk chunkedFirst() {
        return chunk;
    }

    @Override
    public ISeq chunkedNext() {
        return _more != null ? _more.seq() : null;
    }

    @Override
    public ISeq chunkedMore() {
        return _more == null ? PersistentList.EMPTY : _more;
    }

    @Override
    public ChunkedCons withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new ChunkedCons(meta, chunk, _more);
    }
}
