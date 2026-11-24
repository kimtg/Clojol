package cljl.lang;

public interface Indexed extends clojure.lang.Indexed, Counted {
    @Override
    Object nth(int i);
    @Override
    default Object nth(int i, Object notFound) {
        return (i >= 0 && i < count()) ? nth(i) : notFound;
    }
}
