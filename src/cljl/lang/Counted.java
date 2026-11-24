package cljl.lang;

public interface Counted extends clojure.lang.Counted {
    @Override
    int count();
}
