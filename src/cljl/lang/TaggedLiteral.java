package cljl.lang;

import cljl.Keyword;
import cljl.RT;
import cljl.Symbol;
import java.util.Objects;

public final class TaggedLiteral implements ILookup {
    private static final Keyword TAG_KW = Keyword.intern("tag");
    private static final Keyword FORM_KW = Keyword.intern("form");

    public final Symbol tag;
    public final Object form;

    private TaggedLiteral(Symbol tag, Object form) {
        this.tag = tag;
        this.form = form;
    }

    public static TaggedLiteral create(Symbol tag, Object form) {
        return new TaggedLiteral(tag, form);
    }

    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (TAG_KW.equals(key)) return tag;
        if (FORM_KW.equals(key)) return form;
        return notFound;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TaggedLiteral other)) return false;
        return Objects.equals(tag, other.tag) && RT.equiv(form, other.form);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(tag) + RT.hash(form);
    }

    @Override
    public String toString() {
        return "#" + tag + " " + RT.printString(form, true);
    }
}
