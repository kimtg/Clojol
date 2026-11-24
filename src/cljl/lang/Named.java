package cljl.lang;

public interface Named extends clojure.lang.Named {
    @Override
    String getNamespace();
    @Override
    String getName();
}
