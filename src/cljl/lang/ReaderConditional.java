package cljl.lang;

import cljl.Keyword;
import cljl.RT;

public final class ReaderConditional implements ILookup {
    private static final Keyword FORM_KW = Keyword.intern("form");
    private static final Keyword SPLICING_KW = Keyword.intern("splicing?");

    public final Object form;
    public final boolean splicing;

    private ReaderConditional(Object form, boolean splicing) {
        this.form = form;
        this.splicing = splicing;
    }

    public static ReaderConditional create(Object form, boolean splicing) {
        return new ReaderConditional(form, splicing);
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (FORM_KW.equals(key)) return form;
        if (SPLICING_KW.equals(key)) return splicing ? Boolean.TRUE : Boolean.FALSE;
        return notFound;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReaderConditional other)) return false;
        return splicing == other.splicing && RT.equiv(form, other.form);
    }

    @Override
    public int hashCode() {
        return 31 * RT.hash(form) + Boolean.hashCode(splicing);
    }

    @Override
    public String toString() {
        return (splicing ? "#?@" : "#?") + RT.printString(form, true);
    }
}
