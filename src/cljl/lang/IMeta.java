package cljl.lang;

public interface IMeta {
    PersistentHashMap meta();
    Object withMeta(PersistentHashMap meta);
}
