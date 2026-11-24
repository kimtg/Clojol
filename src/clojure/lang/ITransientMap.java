package clojure.lang;

public interface ITransientMap extends ITransientAssociative, Counted {
    @Override
    ITransientMap assoc(Object key, Object val);
    ITransientMap without(Object key);
    @Override
    IPersistentMap persistent();
}
