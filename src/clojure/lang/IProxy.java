package clojure.lang;

public interface IProxy {
    void __initClojureFnMappings(IPersistentMap m);
    void __updateClojureFnMappings(IPersistentMap m);
    IPersistentMap __getClojureFnMappings();
}
