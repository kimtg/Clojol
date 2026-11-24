package cljl.lang;

public interface Seqable extends clojure.lang.Seqable {
    @Override
    ISeq seq();
}
