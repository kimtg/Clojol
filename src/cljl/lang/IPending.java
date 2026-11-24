package cljl.lang;

public interface IPending extends clojure.lang.IPending {
    @Override
    boolean isRealized();
}
