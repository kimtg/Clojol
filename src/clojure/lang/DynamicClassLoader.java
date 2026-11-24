package clojure.lang;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Real clojure.lang.DynamicClassLoader base class extended by cljl.TypeFactory.DynamicClassLoader.
 */
public class DynamicClassLoader extends URLClassLoader {
    public DynamicClassLoader() {
        super(new URL[0], DynamicClassLoader.class.getClassLoader());
    }

    public DynamicClassLoader(ClassLoader parent) {
        super(new URL[0], parent);
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    public Class<?> defineClass(String name, byte[] bytes, Object srcForm) {
        return defineClass(name, bytes, 0, bytes.length);
    }
}
