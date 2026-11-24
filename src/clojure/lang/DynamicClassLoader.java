package clojure.lang;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real clojure.lang.DynamicClassLoader base class with SoftReference classCache
 * for cross-loader visibility while remaining eligible for JVM class unloading.
 */
public class DynamicClassLoader extends URLClassLoader {
    private static final ConcurrentHashMap<String, Reference<Class<?>>> classCache = new ConcurrentHashMap<>();
    private static final ReferenceQueue<Class<?>> rq = new ReferenceQueue<>();

    private static final class NamedSoftRef extends SoftReference<Class<?>> {
        final String name;
        NamedSoftRef(String name, Class<?> cls) {
            super(cls, rq);
            this.name = name;
        }
    }

    private static void clearCache() {
        Reference<?> ref;
        while ((ref = rq.poll()) != null) {
            if (ref instanceof NamedSoftRef nsr) {
                classCache.remove(nsr.name, nsr);
            }
        }
    }

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

    public static Class<?> findInMemoryClass(String name) {
        Reference<Class<?>> ref = classCache.get(name);
        return ref != null ? ref.get() : null;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        Class<?> c = findInMemoryClass(name);
        if (c != null) return c;
        return super.findClass(name);
    }

    public Class<?> defineClass(String name, byte[] bytes, Object srcForm) {
        clearCache();
        Class<?> c = defineClass(name, bytes, 0, bytes.length);
        classCache.put(name, new NamedSoftRef(name, c));
        return c;
    }
}
