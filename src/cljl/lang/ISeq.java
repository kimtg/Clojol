package cljl.lang;

import java.util.List;

public interface ISeq extends IPersistentCollection, Sequential, List<Object> {
    Object first();
    ISeq next();
    ISeq more();
    @Override
    ISeq cons(Object o);
    @Override
    default IPersistentCollection empty() {
        return PersistentList.EMPTY;
    }
}
