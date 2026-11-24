package clojure.lang;

public interface ISeq extends IPersistentCollection {
    Object first();
    ISeq next();
    ISeq more();
    @Override
    ISeq cons(Object o);
}
