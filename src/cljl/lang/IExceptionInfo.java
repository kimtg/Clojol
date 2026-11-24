package cljl.lang;

public interface IExceptionInfo extends clojure.lang.IExceptionInfo {
    @Override
    IPersistentMap getData();
}
