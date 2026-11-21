package cljl.lang;

public interface Indexed extends Counted {
    Object nth(int i);
    default Object nth(int i, Object notFound) {
        return (i >= 0 && i < count()) ? nth(i) : notFound;
    }
}
