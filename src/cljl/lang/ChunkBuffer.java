package cljl.lang;

public final class ChunkBuffer {
    private Object[] buffer;
    private int end;

    public ChunkBuffer(int capacity) {
        this.buffer = new Object[Math.max(1, capacity)];
        this.end = 0;
    }

    public void add(Object o) {
        if (end >= buffer.length) {
            Object[] next = new Object[buffer.length * 2];
            System.arraycopy(buffer, 0, next, 0, end);
            buffer = next;
        }
        buffer[end++] = o;
    }

    public IChunk chunk() {
        ArrayChunk ret = new ArrayChunk(buffer, 0, end);
        buffer = null;
        return ret;
    }

    public int count() {
        return end;
    }
}
