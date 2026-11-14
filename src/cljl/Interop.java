package cljl;

import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-speed cached Java reflection and interop for ClojureLite.
 */
public final class Interop {

    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<MemberKey, Executable> METHOD_CACHE = new ConcurrentHashMap<>(512);
    private static final ConcurrentHashMap<MemberKey, Field> FIELD_CACHE = new ConcurrentHashMap<>(256);
    private static final Class<?> NOT_A_CLASS = Interop.class;

    private record MemberKey(Class<?> clazz, String name, int arity, boolean isStatic) {}

    private Interop() {}

    public static void registerClass(String name, Class<?> cls) {
        CLASS_CACHE.put(name, cls);
    }

    public static Class<?> resolveClass(Namespace ns, String name) {
        if (ns != null) {
            Object mapped = ns.getMapping(Symbol.intern(name));
            if (mapped instanceof Class<?> c) return c;
        }
        return findClass(name);
    }

    public static Class<?> findClass(String name) {
        Class<?> cached = CLASS_CACHE.get(name);
        if (cached != null) return cached == NOT_A_CLASS ? null : cached;

        Class<?> found = switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "char" -> char.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "void" -> void.class;
            case "objects" -> Object[].class;
            case "ints" -> int[].class;
            case "longs" -> long[].class;
            case "doubles" -> double[].class;
            case "bytes" -> byte[].class;
            case "chars" -> char[].class;
            case "booleans" -> boolean[].class;
            case "floats" -> float[].class;
            case "shorts" -> short[].class;
            case "clojure.lang.IDeref" -> cljl.lang.IDeref.class;
            case "clojure.lang.IMeta" -> cljl.lang.IMeta.class;
            case "clojure.lang.IReference" -> cljl.lang.IReference.class;
            case "clojure.lang.ISeq" -> cljl.lang.ISeq.class;
            case "clojure.lang.Seqable" -> cljl.lang.Seqable.class;
            case "clojure.lang.Associative" -> cljl.lang.Associative.class;
            case "clojure.lang.Counted" -> cljl.lang.Counted.class;
            case "clojure.lang.Sorted" -> cljl.lang.Sorted.class;
            case "clojure.lang.Reversible" -> cljl.lang.Reversible.class;
            case "clojure.lang.IFn" -> Fn.class;
            case "clojure.lang.Var" -> Var.class;
            case "clojure.lang.Atom" -> Atom.class;
            case "clojure.lang.Agent" -> Agent.class;
            case "clojure.lang.Ref" -> Ref.class;
            case "clojure.lang.Namespace" -> Namespace.class;
            case "clojure.lang.Symbol" -> Symbol.class;
            case "clojure.lang.Keyword" -> Keyword.class;
            default -> null;
        };

        if (found == null) {
            try {
                found = Class.forName(name);
            } catch (ClassNotFoundException e) {
                if (!name.contains(".")) {
                    try {
                        found = Class.forName("java.lang." + name);
                    } catch (ClassNotFoundException ignored) {}
                }
            }
        }

        CLASS_CACHE.put(name, found != null ? found : NOT_A_CLASS);
        return found;
    }

    public static Object invokeConstructor(Class<?> clazz, Object[] args) {
        try {
            int arity = args == null ? 0 : args.length;
            Constructor<?>[] ctors = clazz.getConstructors();
            Constructor<?> best = null;
            int bestScore = Integer.MAX_VALUE;
            for (Constructor<?> c : ctors) {
                int score = matchScore(c.getParameterTypes(), c.isVarArgs(), args);
                if (score >= 0 && score < bestScore) {
                    bestScore = score;
                    best = c;
                    if (score == 0) break;
                }
            }
            if (best == null) {
                throw new IllegalArgumentException("No matching constructor found for " + clazz.getName() + " with " + arity + " args");
            }
            Object[] coerced = coerceArgs(best.getParameterTypes(), best.isVarArgs(), args);
            best.setAccessible(true);
            return best.newInstance(coerced);
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object getStaticField(Class<?> clazz, String fieldName) {
        try {
            MemberKey key = new MemberKey(clazz, fieldName, -1, true);
            Field f = FIELD_CACHE.get(key);
            if (f == null) {
                f = clazz.getField(fieldName);
                f.setAccessible(true);
                FIELD_CACHE.put(key, f);
            }
            return boxResult(f.get(null));
        } catch (NoSuchFieldException e) {
            // Maybe a zero-arg static method called as Class/member?
            throw new IllegalArgumentException("No such static field: " + clazz.getName() + "/" + fieldName);
        } catch (IllegalAccessException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object invokeStaticMethod(Class<?> clazz, String methodName, Object[] args) {
        try {
            int arity = args == null ? 0 : args.length;
            Method best = findBestMethod(clazz, methodName, args, true);
            if (best == null) {
                if (arity == 0) {
                    return getStaticField(clazz, methodName);
                }
                throw new IllegalArgumentException("No matching static method " + methodName + " on " + clazz.getName());
            }
            Object[] coerced = coerceArgs(best.getParameterTypes(), best.isVarArgs(), args);
            return boxResult(best.invoke(null, coerced));
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object getInstanceField(Object target, String fieldName) {
        if (target == null) throw new NullPointerException("Cannot access field " + fieldName + " on nil");
        if (target instanceof cljl.lang.PersistentHashMap phm) {
            Keyword kw = Keyword.intern(fieldName);
            if (phm.containsKey(kw)) return phm.valAt(kw);
        }
        Class<?> clazz = target.getClass();
        try {
            MemberKey key = new MemberKey(clazz, fieldName, -1, false);
            Field f = FIELD_CACHE.get(key);
            if (f == null) {
                f = findField(clazz, fieldName);
                if (f != null) {
                    f.setAccessible(true);
                    FIELD_CACHE.put(key, f);
                }
            }
            if (f != null) {
                return boxResult(f.get(target));
            }
            // Fall back to zero-arg getter or method
            return invokeInstanceMethod(target, fieldName, Fn.EMPTY_ARGS);
        } catch (IllegalAccessException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object setInstanceField(Object target, String fieldName, Object val) {
        if (target == null) throw new NullPointerException("Cannot set field " + fieldName + " on nil");
        Class<?> clazz = target.getClass();
        try {
            Field f = findField(clazz, fieldName);
            if (f == null) throw new IllegalArgumentException("No such field " + fieldName + " on " + clazz.getName());
            f.setAccessible(true);
            Object coerced = coerceArg(f.getType(), val);
            f.set(target, coerced);
            return val;
        } catch (IllegalAccessException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        try {
            return clazz.getField(name);
        } catch (NoSuchFieldException e) {
            for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
                try {
                    return c.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {}
            }
            return null;
        }
    }

    public static Object invokeInstanceMethod(Object target, String methodName, Object[] args) {
        if (target == null) {
            throw new NullPointerException("Cannot call method " + methodName + " on nil");
        }
        if (target instanceof Class<?> clazz) {
            Method instanceOnClass = findBestMethod(Class.class, methodName, args, false);
            Method staticOnTarget = findBestMethod(clazz, methodName, args, true);
            if (staticOnTarget != null && instanceOnClass == null) {
                return invokeStaticMethod(clazz, methodName, args);
            }
        }
        Class<?> clazz = target.getClass();
        try {
            int arity = args == null ? 0 : args.length;
            Method best = findBestMethod(clazz, methodName, args, false);
            if (best == null) {
                if (arity == 0) {
                    Field f = findField(clazz, methodName);
                    if (f != null) {
                        f.setAccessible(true);
                        return boxResult(f.get(target));
                    }
                }
                throw new IllegalArgumentException("No matching method " + methodName + " found taking " + arity + " args for " + clazz.getName());
            }
            Object[] coerced = coerceArgs(best.getParameterTypes(), best.isVarArgs(), args);
            return boxResult(best.invoke(target, coerced));
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static Method findBestMethod(Class<?> clazz, String name, Object[] args, boolean isStatic) {
        int arity = args == null ? 0 : args.length;
        MemberKey key = new MemberKey(clazz, name, arity, isStatic);
        Executable cached = METHOD_CACHE.get(key);
        if (cached instanceof Method cm && matchScore(cm.getParameterTypes(), cm.isVarArgs(), args) >= 0) {
            return cm;
        }

        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isStatic(m.getModifiers()) != isStatic) continue;
            int score = matchScore(m.getParameterTypes(), m.isVarArgs(), args);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                best = m;
                if (score == 0) break;
            }
        }
        if (best != null) {
            setAccessibleSafe(best, clazz);
            METHOD_CACHE.put(key, best);
        }
        return best;
    }

    private static void setAccessibleSafe(Method m, Class<?> clazz) {
        if (Modifier.isPublic(clazz.getModifiers()) && Modifier.isPublic(m.getModifiers())) {
            try { m.setAccessible(true); } catch (Exception ignored) {}
            return;
        }
        // Find accessible declaration on interface or superclass
        for (Class<?> iface : clazz.getInterfaces()) {
            try {
                Method im = iface.getMethod(m.getName(), m.getParameterTypes());
                im.setAccessible(true);
                return;
            } catch (Exception ignored) {}
        }
        for (Class<?> sup = clazz.getSuperclass(); sup != null; sup = sup.getSuperclass()) {
            if (Modifier.isPublic(sup.getModifiers())) {
                try {
                    Method sm = sup.getMethod(m.getName(), m.getParameterTypes());
                    sm.setAccessible(true);
                    return;
                } catch (Exception ignored) {}
            }
        }
        try { m.setAccessible(true); } catch (Exception ignored) {}
    }

    private static int matchScore(Class<?>[] paramTypes, boolean varArgs, Object[] args) {
        int argCount = args == null ? 0 : args.length;
        if (!varArgs) {
            if (paramTypes.length != argCount) return -1;
            int score = 0;
            for (int i = 0; i < argCount; i++) {
                int s = argScore(paramTypes[i], args[i]);
                if (s < 0) return -1;
                score += s;
            }
            return score;
        } else {
            int fixed = paramTypes.length - 1;
            if (argCount < fixed) return -1;
            int score = 10; // Slight penalty for varargs over exact match
            for (int i = 0; i < fixed; i++) {
                int s = argScore(paramTypes[i], args[i]);
                if (s < 0) return -1;
                score += s;
            }
            Class<?> compType = paramTypes[fixed].getComponentType();
            if (argCount == fixed + 1 && args[fixed] != null && paramTypes[fixed].isInstance(args[fixed])) {
                return score;
            }
            for (int i = fixed; i < argCount; i++) {
                int s = argScore(compType, args[i]);
                if (s < 0) return -1;
                score += s;
            }
            return score;
        }
    }

    private static int argScore(Class<?> param, Object arg) {
        if (arg == null) {
            return param.isPrimitive() ? -1 : 1;
        }
        Class<?> ac = arg.getClass();
        if (param == ac) return 0;
        if (param.isAssignableFrom(ac)) return 1;
        if (param == long.class || param == Long.class) {
            if (arg instanceof Long) return 0;
            if (Numbers.isInteger(arg)) return 2;
            return -1;
        }
        if (param == int.class || param == Integer.class) {
            if (arg instanceof Integer) return 0;
            if (Numbers.isInteger(arg)) return 3;
            return -1;
        }
        if (param == double.class || param == Double.class) {
            if (arg instanceof Double) return 0;
            if (arg instanceof Number) return 2;
            return -1;
        }
        if (param == float.class || param == Float.class) {
            if (arg instanceof Float) return 0;
            if (arg instanceof Number) return 3;
            return -1;
        }
        if (param == short.class || param == Short.class || param == byte.class || param == Byte.class) {
            if (Numbers.isInteger(arg)) return 4;
            return -1;
        }
        if (param == boolean.class || param == Boolean.class) {
            return (arg instanceof Boolean) ? 0 : -1;
        }
        if (param == char.class || param == Character.class) {
            return (arg instanceof Character) ? 0 : -1;
        }
        if (param == CharSequence.class && arg instanceof String) return 1;
        return -1;
    }

    private static Object[] coerceArgs(Class<?>[] paramTypes, boolean varArgs, Object[] args) {
        int argCount = args == null ? 0 : args.length;
        if (!varArgs) {
            if (argCount == 0) return Fn.EMPTY_ARGS;
            Object[] out = new Object[argCount];
            for (int i = 0; i < argCount; i++) {
                out[i] = coerceArg(paramTypes[i], args[i]);
            }
            return out;
        } else {
            int fixed = paramTypes.length - 1;
            Object[] out = new Object[paramTypes.length];
            for (int i = 0; i < fixed; i++) {
                out[i] = coerceArg(paramTypes[i], args[i]);
            }
            Class<?> varArrType = paramTypes[fixed];
            Class<?> compType = varArrType.getComponentType();
            if (argCount == fixed + 1 && args[fixed] != null && varArrType.isInstance(args[fixed])) {
                out[fixed] = args[fixed];
            } else {
                int varCount = argCount - fixed;
                Object varArr = Array.newInstance(compType, varCount);
                for (int i = 0; i < varCount; i++) {
                    Array.set(varArr, i, coerceArg(compType, args[fixed + i]));
                }
                out[fixed] = varArr;
            }
            return out;
        }
    }

    public static Object coerceArg(Class<?> param, Object arg) {
        if (arg == null) return null;
        if (param.isInstance(arg)) return arg;
        if (param == int.class || param == Integer.class) return ((Number) arg).intValue();
        if (param == long.class || param == Long.class) return ((Number) arg).longValue();
        if (param == double.class || param == Double.class) return ((Number) arg).doubleValue();
        if (param == float.class || param == Float.class) return ((Number) arg).floatValue();
        if (param == short.class || param == Short.class) return ((Number) arg).shortValue();
        if (param == byte.class || param == Byte.class) return ((Number) arg).byteValue();
        if (param == char.class || param == Character.class) {
            if (arg instanceof Character c) return c;
            if (arg instanceof Number n) return (char) n.intValue();
        }
        if (param == boolean.class || param == Boolean.class) return (Boolean) arg;
        return arg;
    }

    public static Object boxResult(Object res) {
        if (res instanceof Integer i) return i.longValue();
        if (res instanceof Short s) return s.longValue();
        if (res instanceof Byte b) return b.longValue();
        if (res instanceof Float f) return f.doubleValue();
        return res;
    }
}
