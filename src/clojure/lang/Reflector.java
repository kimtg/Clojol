package clojure.lang;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Clean-room clojure.lang.Reflector static bridge delegating to cljl.Interop.
 */
public final class Reflector {
    private Reflector() {}

    public static Object invokeInstanceMethod(Object target, String methodName, Object[] args) {
        return cljl.Interop.invokeInstanceMethod(target, methodName, args);
    }

    public static Object invokeStaticMethod(Class<?> c, String methodName, Object[] args) {
        return cljl.Interop.invokeStaticMethod(c, methodName, args);
    }

    public static Object invokeStaticMethod(String className, String methodName, Object[] args) {
        Class<?> c = cljl.Interop.findClass(className);
        if (c == null) throw new IllegalArgumentException("Class not found: " + className);
        return cljl.Interop.invokeStaticMethod(c, methodName, args);
    }

    public static Object invokeConstructor(Class<?> c, Object[] args) {
        return cljl.Interop.invokeConstructor(c, args);
    }

    public static Object invokeNoArgInstanceMember(Object target, String name) {
        if (target == null) throw new NullPointerException("Cannot invoke member " + name + " on nil");
        try {
            return cljl.Interop.invokeInstanceMethod(target, name, cljl.Fn.EMPTY_ARGS);
        } catch (RuntimeException e) {
            if (cljl.Interop.findPublicField(target.getClass(), name, false) != null) {
                return cljl.Interop.getInstanceField(target, name);
            }
            throw e;
        }
    }

    public static Object getInstanceField(Object target, String fieldName) {
        return cljl.Interop.getInstanceField(target, fieldName);
    }

    public static Object setInstanceField(Object target, String fieldName, Object val) {
        return cljl.Interop.setInstanceField(target, fieldName, val);
    }

    public static Object getStaticField(Class<?> c, String fieldName) {
        return cljl.Interop.getStaticField(c, fieldName);
    }

    public static Object getStaticField(String className, String fieldName) {
        Class<?> c = cljl.Interop.findClass(className);
        if (c == null) throw new IllegalArgumentException("Class not found: " + className);
        return cljl.Interop.getStaticField(c, fieldName);
    }

    public static Object setStaticField(Class<?> c, String fieldName, Object val) {
        return cljl.Interop.setStaticField(c, fieldName, val);
    }

    public static Object setStaticField(String className, String fieldName, Object val) {
        Class<?> c = cljl.Interop.findClass(className);
        if (c == null) throw new IllegalArgumentException("Class not found: " + className);
        return cljl.Interop.setStaticField(c, fieldName, val);
    }

    public static Object prepRet(Class<?> c, Object x) {
        return cljl.Interop.prepRet(c, x);
    }

    public static List<Method> getMethods(Class<?> c, int arity, String name, boolean getStatics) {
        return cljl.Interop.getMethods(c, arity, name, getStatics);
    }
}
