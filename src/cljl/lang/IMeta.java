package cljl.lang;

public interface IMeta extends clojure.lang.IMeta {
    @Override
    PersistentHashMap meta();
}
