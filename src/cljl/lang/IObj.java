package cljl.lang;

public interface IObj extends clojure.lang.IObj, IMeta {
    IObj withMeta(PersistentHashMap meta);

    @Override
    default clojure.lang.IObj withMeta(clojure.lang.IPersistentMap meta) {
        return withMeta(PersistentHashMap.fromMap(meta));
    }
}
