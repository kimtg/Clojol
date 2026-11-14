package cljl.lang;

public interface IObj extends IMeta {
    Object withMeta(PersistentHashMap meta);
}
