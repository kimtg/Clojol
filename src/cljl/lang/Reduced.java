package cljl.lang;

public final class Reduced implements IDeref {
    public final Object val;

    public Reduced(Object val) {
        this.val = val;
    }

    @Override
    public Object deref() {
        return val;
    }

    @Override
    public String toString() {
        return "#<Reduced " + val + ">";
    }
}
