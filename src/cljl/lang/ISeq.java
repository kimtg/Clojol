package cljl.lang;

import java.util.List;

public interface ISeq extends Seqable, List<Object> {
    Object first();
    ISeq next();
    ISeq more();
    ISeq cons(Object o);
}
