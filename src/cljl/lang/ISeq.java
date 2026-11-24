package cljl.lang;

import java.util.List;

public interface ISeq extends clojure.lang.ISeq, IPersistentCollection, Sequential, List<Object> {
    @Override
    Object first();
    @Override
    ISeq next();
    @Override
    ISeq more();
    @Override
    ISeq cons(Object o);
    @Override
    default IPersistentCollection empty() {
        return PersistentList.EMPTY;
    }
}
