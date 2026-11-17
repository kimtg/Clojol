package cljl.lang;

/**
 * Lazy, O(1)-slice sequential view over a {@link CharSequence} (String, StringBuilder, etc.).
 */
public final class StringSeq extends ASeq implements Counted {

    public final CharSequence s;
    public final int i;

    public StringSeq(CharSequence s, int i) {
        super(null);
        this.s = s;
        this.i = i;
    }

    public StringSeq(PersistentHashMap meta, CharSequence s, int i) {
        super(meta);
        this.s = s;
        this.i = i;
    }

    @Override
    public Object first() {
        return s.charAt(i);
    }

    @Override
    public ISeq next() {
        return (i + 1 < s.length()) ? new StringSeq(s, i + 1) : null;
    }

    @Override
    public int count() {
        return s.length() - i;
    }

    @Override
    public StringSeq withMeta(PersistentHashMap meta) {
        if (this.meta == meta) return this;
        return new StringSeq(meta, s, i);
    }
}
