package cljl;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import cljl.lang.IPersistentMap;
import cljl.lang.PersistentHashMap;

/**
 * High-speed cached Java reflection and interop for Clojol.
 */
public final class Interop {

    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>(256);
    private static final class NotAClass {}
    private static final Class<?> NOT_A_CLASS = NotAClass.class;
    private static final Class<?>[] EMPTY_CLASSES = new Class<?>[0];

    private static final class CacheEntry<T> {
        final Class<?>[] argTypes;
        final T target;

        CacheEntry(Class<?>[] argTypes, T target) {
            this.argTypes = argTypes;
            this.target = target;
        }

        boolean matches(Object[] args) {
            int n = args == null ? 0 : args.length;
            if (argTypes.length != n) return false;
            for (int i = 0; i < n; i++) {
                Object a = args[i];
                Class<?> expected = argTypes[i];
                if (a == null) {
                    if (expected != Void.class) return false;
                } else if (a.getClass() != expected) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class ClassCache {
        final ConcurrentHashMap<String, CacheEntry<Method>[]> instanceMethods = new ConcurrentHashMap<>(16);
        final ConcurrentHashMap<String, CacheEntry<Method>[]> staticMethods = new ConcurrentHashMap<>(8);
        final ConcurrentHashMap<String, Field> instanceFields = new ConcurrentHashMap<>(8);
        final ConcurrentHashMap<String, Field> staticFields = new ConcurrentHashMap<>(8);
        volatile CacheEntry<Constructor<?>>[] ctors;
    }

    private static final ClassValue<ClassCache> CLASS_INFO = new ClassValue<>() {
        @Override
        protected ClassCache computeValue(Class<?> type) {
            return new ClassCache();
        }
    };

    private Interop() {}

    public static void registerClass(String name, Class<?> cls) {
        CLASS_CACHE.put(name, cls);
    }

    public static Class<?> resolveClass(Namespace ns, String name) {
        if (name != null) {
            int slash = name.indexOf('/');
            if (slash > 0 && slash == name.length() - 2 && isArrayDimToken(name.substring(slash + 1))) {
                return resolveArrayClass(ns, name.substring(0, slash), name.substring(slash + 1));
            }
        }
        if (ns != null) {
            Object mapped = ns.getMapping(Symbol.intern(name));
            if (mapped instanceof Class<?> c) return c;
        }
        return findClass(name);
    }

    public static boolean isArrayDimToken(String s) {
        return s != null && s.length() == 1 && s.charAt(0) >= '1' && s.charAt(0) <= '9';
    }

    public static Class<?> resolveArrayClass(Namespace ns, String componentName, String dimsStr) {
        if (!isArrayDimToken(dimsStr)) return null;
        int dims = dimsStr.charAt(0) - '0';
        Class<?> comp = resolveClass(ns, componentName);
        if (comp == null) return null;
        Class<?> arr = comp;
        for (int i = 0; i < dims; i++) arr = arr.arrayType();
        return arr;
    }

    public static Class<?>[] resolveParamTags(Namespace ns, Object paramTagsObj) {
        if (!(paramTagsObj instanceof cljl.lang.PersistentVector pv)) return null;
        int n = pv.count();
        Class<?>[] tags = new Class<?>[n];
        for (int i = 0; i < n; i++) {
            Object item = pv.nth(i);
            if (item == null || (item instanceof Symbol s && s.ns == null && "_".equals(s.name))) {
                tags[i] = null; // wildcard
            } else if (item instanceof Class<?> c) {
                tags[i] = c;
            } else if (item instanceof Symbol sym) {
                Class<?> c = (sym.ns != null && isArrayDimToken(sym.name))
                        ? resolveArrayClass(ns, sym.ns, sym.name)
                        : resolveClass(ns, sym.ns == null ? sym.name : (sym.ns + "." + sym.name));
                if (c == null) throw new IllegalArgumentException("Unable to resolve param-tag class: " + sym);
                tags[i] = c;
            } else if (item instanceof String str) {
                Class<?> c = resolveClass(ns, str);
                if (c == null) throw new IllegalArgumentException("Unable to resolve param-tag class: " + str);
                tags[i] = c;
            } else {
                throw new IllegalArgumentException("Invalid param-tag: " + item);
            }
        }
        return tags;
    }

    public static boolean hasStaticField(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getField(fieldName);
            return Modifier.isStatic(f.getModifiers());
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    public static boolean hasStaticMethod(Class<?> clazz, String methodName) {
        if (clazz == java.math.BigInteger.class && ("fromBigInteger".equals(methodName) || "fromLong".equals(methodName))) {
            return true;
        }
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())) return true;
        }
        return false;
    }

    // Cached Functional Interface (SAM) lookup: returns the single abstract Method or NO_SAM_SENTINEL.
    private static final Method NO_SAM_SENTINEL;
    static {
        try {
            NO_SAM_SENTINEL = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static final ClassValue<Method> SAM_CACHE = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            if (!type.isInterface()) return NO_SAM_SENTINEL;
            Method found = null;
            for (Method m : type.getMethods()) {
                int mod = m.getModifiers();
                if (!Modifier.isAbstract(mod) || Modifier.isStatic(mod)) continue;
                if (isObjectPublicMethod(m)) continue;
                if (found != null) return NO_SAM_SENTINEL;
                found = m;
            }
            return found != null ? found : NO_SAM_SENTINEL;
        }
    };

    private static boolean isObjectPublicMethod(Method m) {
        String name = m.getName();
        int pc = m.getParameterCount();
        if ("toString".equals(name) && pc == 0) return true;
        if ("hashCode".equals(name) && pc == 0) return true;
        if ("equals".equals(name) && pc == 1 && m.getParameterTypes()[0] == Object.class) return true;
        return false;
    }

    public static boolean isFunctionalInterface(Class<?> c) {
        return c != null && c.isInterface() && SAM_CACHE.get(c) != NO_SAM_SENTINEL;
    }

    public static Object adaptFnToSam(Class<?> iface, Fn fn) {
        if (iface.isInstance(fn)) return fn;
        if (iface == Comparator.class) return fn.asComparator();
        Method sam = SAM_CACHE.get(iface);
        if (sam == NO_SAM_SENTINEL) {
            throw new IllegalArgumentException(iface.getName() + " is not a functional interface");
        }
        ClassLoader cl = iface.getClassLoader();
        if (cl == null) cl = RT.baseLoader();
        return Proxy.newProxyInstance(cl, new Class<?>[]{iface}, new SamInvocationHandler(sam, fn));
    }

    private static final class SamInvocationHandler implements InvocationHandler {
        private final Method sam;
        private final Fn fn;

        SamInvocationHandler(Method sam, Fn fn) {
            this.sam = sam;
            this.fn = fn;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> fn.toString();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.invoke(this, args);
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            Object res;
            if (args == null || args.length == 0) {
                res = fn.invoke();
            } else {
                Object[] boxed = new Object[args.length];
                for (int i = 0; i < args.length; i++) boxed[i] = boxResult(args[i]);
                res = fn.applyTo(boxed);
            }
            Class<?> ret = method.getReturnType();
            if (ret == void.class || ret == Void.class) return null;
            if (ret == boolean.class || ret == Boolean.class) return RT.truthy(res);
            return coerceArg(ret, res);
        }
    }

    static void invalidateClassCache(String name) {
        if (name != null) CLASS_CACHE.remove(name);
    }

    public static List<Method> getMethods(Class<?> c, int arity, String name, boolean getStatics) {
        if (c == null || name == null) return Collections.emptyList();
        List<Method> matching = new ArrayList<>();
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name)
                    && Modifier.isStatic(m.getModifiers()) == getStatics
                    && m.getParameterCount() == arity) {
                matching.add(m);
            }
        }
        return matching;
    }

    public static Object prepRet(Class<?> c, Object x) {
        if (!(c.isPrimitive())) return x;
        if (x instanceof Boolean) return ((Boolean) x).booleanValue() ? Boolean.TRUE : Boolean.FALSE;
        if (x instanceof Integer i) return i.longValue();
        return x;
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
            case "clojure.lang.IDeref" -> clojure.lang.IDeref.class;
            case "clojure.lang.IBlockingDeref" -> clojure.lang.IBlockingDeref.class;
            case "clojure.lang.IPending" -> clojure.lang.IPending.class;
            case "clojure.lang.IMeta" -> clojure.lang.IMeta.class;
            case "clojure.lang.IObj" -> clojure.lang.IObj.class;
            case "clojure.lang.IReference", "clojure.lang.AReference" -> clojure.lang.IReference.class;
            case "clojure.lang.IRef" -> clojure.lang.IRef.class;
            case "clojure.lang.IAtom" -> clojure.lang.IAtom.class;
            case "clojure.lang.IAtom2" -> clojure.lang.IAtom2.class;
            case "clojure.lang.Settable" -> clojure.lang.Settable.class;
            case "clojure.lang.IProxy" -> clojure.lang.IProxy.class;
            case "clojure.lang.DynamicClassLoader" -> clojure.lang.DynamicClassLoader.class;
            case "clojure.lang.ISeq" -> clojure.lang.ISeq.class;
            case "clojure.lang.ASeq" -> clojure.lang.ASeq.class;
            case "clojure.lang.SeqIterator" -> cljl.lang.SeqIterator.class;
            case "clojure.lang.Seqable" -> clojure.lang.Seqable.class;
            case "clojure.lang.Sequential" -> clojure.lang.Sequential.class;
            case "clojure.lang.Associative" -> clojure.lang.Associative.class;
            case "clojure.lang.ILookup" -> clojure.lang.ILookup.class;
            case "clojure.lang.Indexed" -> clojure.lang.Indexed.class;
            case "clojure.lang.Named" -> clojure.lang.Named.class;
            case "clojure.lang.Counted" -> clojure.lang.Counted.class;
            case "clojure.lang.Sorted" -> clojure.lang.Sorted.class;
            case "clojure.lang.Reversible" -> clojure.lang.Reversible.class;
            case "clojure.lang.IReduce" -> clojure.lang.IReduce.class;
            case "clojure.lang.IReduceInit" -> clojure.lang.IReduceInit.class;
            case "clojure.lang.IKVReduce" -> clojure.lang.IKVReduce.class;
            case "clojure.lang.IDrop" -> clojure.lang.IDrop.class;
            case "clojure.lang.IChunk" -> clojure.lang.IChunk.class;
            case "clojure.lang.IChunkedSeq" -> clojure.lang.IChunkedSeq.class;
            case "clojure.lang.ChunkedCons" -> cljl.lang.ChunkedCons.class;
            case "clojure.lang.ArrayChunk", "clojure.core.ArrayChunk" -> cljl.lang.ArrayChunk.class;
            case "clojure.core.ArrayManager" -> cljl.lang.ArrayManager.class;
            case "clojure.lang.IPersistentMap" -> clojure.lang.IPersistentMap.class;
            case "clojure.lang.MapEquivalence" -> clojure.lang.MapEquivalence.class;
            case "clojure.lang.IPersistentVector" -> clojure.lang.IPersistentVector.class;
            case "clojure.lang.IPersistentSet" -> clojure.lang.IPersistentSet.class;
            case "clojure.lang.IPersistentList" -> clojure.lang.IPersistentList.class;
            case "clojure.lang.IPersistentStack" -> clojure.lang.IPersistentStack.class;
            case "clojure.lang.IPersistentCollection" -> clojure.lang.IPersistentCollection.class;
            case "clojure.lang.APersistentMap" -> clojure.lang.APersistentMap.class;
            case "clojure.lang.PersistentHashMap" -> clojure.lang.PersistentHashMap.class;
            case "clojure.lang.PersistentArrayMap" -> clojure.lang.PersistentArrayMap.class;
            case "clojure.lang.PersistentHashMap$NodeSeq" -> cljl.lang.PersistentHashMap.NodeSeq.class;
            case "clojure.lang.PersistentArrayMap$Seq" -> cljl.lang.PersistentArrayMap.ArrayMapSeq.class;
            case "clojure.lang.APersistentVector" -> clojure.lang.APersistentVector.class;
            case "clojure.lang.PersistentVector", "clojure.lang.LazilyPersistentVector" -> clojure.lang.PersistentVector.class;
            case "clojure.lang.PersistentVector$ChunkedSeq" -> cljl.lang.PersistentVector.VecSeq.class;
            case "clojure.lang.PersistentVector$Node" -> cljl.lang.PersistentVector.Node.class;
            case "clojure.lang.PersistentVector$TransientVector" -> cljl.lang.PersistentVector.TransientVector.class;
            case "clojure.lang.APersistentVector$RSeq" -> clojure.lang.APersistentVector.RSeq.class;
            case "clojure.lang.APersistentVector$SubVector" -> cljl.lang.PersistentVector.SubVec.class;
            case "clojure.core.Vec" -> cljl.lang.Vec.class;
            case "clojure.core.VecNode" -> cljl.lang.Vec.VecNode.class;
            case "clojure.core.VecSeq" -> cljl.lang.Vec.VecSeq.class;
            case "clojure.lang.Cons" -> clojure.lang.Cons.class;
            case "clojure.lang.Box" -> clojure.lang.Box.class;
            case "clojure.lang.Util" -> clojure.lang.Util.class;
            case "clojure.lang.Murmur3" -> clojure.lang.Murmur3.class;
            case "clojure.lang.Reflector" -> clojure.lang.Reflector.class;
            case "clojure.lang.ArityException" -> clojure.lang.ArityException.class;
            case "clojure.lang.IType" -> clojure.lang.IType.class;
            case "clojure.lang.IRecord" -> clojure.lang.IRecord.class;
            case "clojure.lang.IEditableCollection" -> clojure.lang.IEditableCollection.class;
            case "clojure.lang.IHashEq" -> clojure.lang.IHashEq.class;
            case "clojure.lang.ITransientSet" -> clojure.lang.ITransientSet.class;
            case "clojure.lang.ITransientVector" -> clojure.lang.ITransientVector.class;
            case "clojure.lang.ITransientMap" -> clojure.lang.ITransientMap.class;
            case "clojure.lang.ITransientAssociative" -> clojure.lang.ITransientAssociative.class;
            case "clojure.lang.ITransientAssociative2" -> clojure.lang.ITransientAssociative2.class;
            case "clojure.lang.ITransientCollection" -> clojure.lang.ITransientCollection.class;
            case "clojure.lang.APersistentSet" -> clojure.lang.APersistentSet.class;
            case "clojure.lang.PersistentHashSet" -> clojure.lang.PersistentHashSet.class;
            case "clojure.lang.PersistentList" -> clojure.lang.PersistentList.class;
            case "clojure.lang.PersistentQueue" -> clojure.lang.PersistentQueue.class;
            case "clojure.lang.PersistentTreeMap" -> clojure.lang.PersistentTreeMap.class;
            case "clojure.lang.PersistentTreeSet" -> clojure.lang.PersistentTreeSet.class;
            case "clojure.lang.PersistentStructMap" -> clojure.lang.PersistentStructMap.class;
            case "clojure.lang.MapEntry" -> clojure.lang.MapEntry.class;
            case "clojure.lang.IMapEntry" -> clojure.lang.IMapEntry.class;
            case "clojure.lang.LazySeq" -> clojure.lang.LazySeq.class;
            case "clojure.lang.LongRange" -> cljl.lang.LongRange.class;
            case "clojure.lang.Range" -> cljl.lang.Range.class;
            case "clojure.lang.Repeat" -> cljl.lang.Repeat.class;
            case "clojure.lang.Iterate" -> cljl.lang.Iterate.class;
            case "clojure.lang.LispReader" -> clojure.lang.LispReader.class;
            case "clojure.lang.LispReader$Resolver" -> clojure.lang.LispReader.Resolver.class;
            case "clojure.lang.Cycle" -> cljl.lang.Cycle.class;
            case "clojure.lang.Reduced" -> clojure.lang.Reduced.class;
            case "clojure.lang.Ratio" -> clojure.lang.Ratio.class;
            case "clojure.lang.BigInt" -> clojure.lang.BigInt.class;
            case "clojure.lang.TaggedLiteral" -> clojure.lang.TaggedLiteral.class;
            case "clojure.lang.ReaderConditional" -> clojure.lang.ReaderConditional.class;
            case "clojure.lang.ExceptionInfo" -> clojure.lang.ExceptionInfo.class;
            case "clojure.lang.IExceptionInfo" -> clojure.lang.IExceptionInfo.class;
            case "clojure.lang.IFn" -> clojure.lang.IFn.class;
            case "clojure.lang.Fn" -> clojure.lang.Fn.class;
            case "clojure.lang.AFn" -> clojure.lang.AFn.class;
            case "clojure.lang.AFunction" -> clojure.lang.AFunction.class;
            case "clojure.lang.RestFn" -> clojure.lang.RestFn.class;
            case "clojure.lang.MultiFn" -> clojure.lang.MultiFn.class;
            case "clojure.lang.Var" -> clojure.lang.Var.class;
            case "clojure.lang.Var$Unbound" -> clojure.lang.Var.Unbound.class;
            case "clojure.lang.Atom" -> clojure.lang.Atom.class;
            case "clojure.lang.Delay" -> clojure.lang.Delay.class;
            case "clojure.lang.Volatile" -> clojure.lang.Volatile.class;
            case "clojure.lang.Agent" -> clojure.lang.Agent.class;
            case "clojure.lang.Ref" -> clojure.lang.Ref.class;
            case "clojure.lang.LockingTransaction" -> clojure.lang.LockingTransaction.class;
            case "clojure.lang.Namespace" -> clojure.lang.Namespace.class;
            case "clojure.lang.Symbol" -> clojure.lang.Symbol.class;
            case "clojure.lang.Keyword" -> clojure.lang.Keyword.class;
            case "clojure.lang.RT" -> clojure.lang.RT.class;
            case "clojure.lang.Numbers" -> clojure.lang.Numbers.class;
            case "clojure.lang.Compiler" -> clojure.lang.Compiler.class;
            case "clojure.lang.Compiler$CompilerException" -> clojure.lang.Compiler.CompilerException.class;
            case "clojure.lang.Compiler$Expr" -> Compiler.Expr.class;
            case "clojure.lang.Compiler$ObjExpr" -> Compiler.ObjExpr.class;
            case "clojure.lang.Compiler$ObjMethod" -> Compiler.ObjMethod.class;
            case "clojure.lang.Compiler$LocalBinding" -> Compiler.LocalBinding.class;
            case "clojure.lang.LineNumberingPushbackReader" -> clojure.lang.LineNumberingPushbackReader.class;
            case "clojure.lang.LispReader$ReaderException" -> clojure.lang.LispReader.ReaderException.class;
            case "clojure.lang.LispReader$StringReader" -> Reader.StringReaderFn.class;
            case "clojure.lang.LispReader$RegexReader" -> Reader.RegexReaderFn.class;
            default -> null;
        };

        if (found == null) {
            found = TypeFactory.findDynamicClass(name);
        }
        if (found == null) {
            ClassLoader loader = RT.baseLoader();
            try {
                found = Class.forName(name, false, loader);
            } catch (ClassNotFoundException e) {
                if (!name.contains(".")) {
                    try {
                        found = Class.forName("java.lang." + name, false, loader);
                    } catch (ClassNotFoundException ignored) {}
                }
            }
        }

        if (found != null || RT.hasCustomRuntimeLoader()) {
            CLASS_CACHE.put(name, found != null ? found : NOT_A_CLASS);
        }
        return found;
    }

    private static Class<?>[] captureArgTypes(Object[] args) {
        if (args == null || args.length == 0) return EMPTY_CLASSES;
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = (args[i] == null) ? Void.class : args[i].getClass();
        }
        return types;
    }

    private static <T> T lookupEntry(CacheEntry<T>[] entries, Object[] args) {
        if (entries == null) return null;
        for (CacheEntry<T> e : entries) {
            if (e.matches(args)) return e.target;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> CacheEntry<T>[] appendEntry(CacheEntry<T>[] existing, CacheEntry<T> added) {
        if (existing == null || existing.length == 0) {
            return new CacheEntry[]{added};
        }
        if (existing.length >= 16) {
            CacheEntry<T>[] next = existing.clone();
            next[existing.length - 1] = added;
            return next;
        }
        CacheEntry<T>[] next = Arrays.copyOf(existing, existing.length + 1);
        next[existing.length] = added;
        return next;
    }

    public static boolean isConcurrencyClass(Class<?> c) {
        return c != null && (
                Thread.class.isAssignableFrom(c)
                || Thread.Builder.class.isAssignableFrom(c)
                || java.util.concurrent.Executor.class.isAssignableFrom(c)
                || java.util.concurrent.ForkJoinTask.class.isAssignableFrom(c)
                || java.util.concurrent.CompletableFuture.class.isAssignableFrom(c)
                || java.util.Timer.class.isAssignableFrom(c)
                || java.util.stream.BaseStream.class.isAssignableFrom(c));
    }

    public static Class<?> concreteClass(Class<?> clazz) {
        if (clazz == clojure.lang.Cons.class) return cljl.lang.Cons.class;
        if (clazz == clojure.lang.LazySeq.class) return cljl.lang.LazySeq.class;
        if (clazz == clojure.lang.Ratio.class) return cljl.lang.Ratio.class;
        if (clazz == clojure.lang.Reduced.class) return cljl.lang.Reduced.class;
        if (clazz == clojure.lang.MapEntry.class) return cljl.lang.PersistentHashMap.MapEntry.class;
        if (clazz == clojure.lang.Atom.class) return Atom.class;
        if (clazz == clojure.lang.Ref.class) return Ref.class;
        if (clazz == clojure.lang.Agent.class) return Agent.class;
        if (clazz == clojure.lang.Delay.class) return Atom.Delay.class;
        if (clazz == clojure.lang.Volatile.class) return Atom.Volatile.class;
        if (clazz == clojure.lang.MultiFn.class) return MultiFn.class;
        if (clazz == clojure.lang.DynamicClassLoader.class) return TypeFactory.DynamicClassLoader.class;
        if (clazz == clojure.lang.LineNumberingPushbackReader.class) return cljl.lang.LineNumberingPushbackReader.class;
        if (clazz == clojure.lang.Box.class) return cljl.lang.Box.class;
        return clazz;
    }

    public static Object invokeConstructor(Class<?> clazz, Object[] args) {
        clazz = concreteClass(clazz);
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        try {
            int arity = args == null ? 0 : args.length;
            ClassCache cc = CLASS_INFO.get(clazz);
            Constructor<?> best = lookupEntry(cc.ctors, args);
            if (best == null) {
                Constructor<?>[] ctors = clazz.getConstructors();
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
                if (!Modifier.isPublic(best.getModifiers()) || !isAccessibleFromHere(clazz)) {
                    try { best.setAccessible(true); } catch (Exception ignored) {}
                }
                synchronized (cc) {
                    if (lookupEntry(cc.ctors, args) == null) {
                        cc.ctors = appendEntry(cc.ctors, new CacheEntry<>(captureArgTypes(args), best));
                    }
                }
            }
            Object[] coerced = coerceArgs(best.getParameterTypes(), best.isVarArgs(), args);
            return best.newInstance(coerced);
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static void setFieldAccessibleSafe(Field f) {
        if (!Modifier.isPublic(f.getModifiers()) || !isAccessibleFromHere(f.getDeclaringClass())) {
            try { f.setAccessible(true); } catch (Exception ignored) {}
        }
    }

    public static Object getStaticField(Class<?> clazz, String fieldName) {
        try {
            ClassCache cc = CLASS_INFO.get(clazz);
            Field f = cc.staticFields.get(fieldName);
            if (f == null) {
                f = clazz.getField(fieldName);
                setFieldAccessibleSafe(f);
                cc.staticFields.putIfAbsent(fieldName, f);
            }
            return boxResult(f.get(null));
        } catch (NoSuchFieldException e) {
            // Maybe a zero-arg static method called as Class/member?
            throw new IllegalArgumentException("No such static field: " + clazz.getName() + "/" + fieldName);
        } catch (IllegalAccessException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object setStaticField(Class<?> clazz, String fieldName, Object val) {
        try {
            ClassCache cc = CLASS_INFO.get(clazz);
            Field f = cc.staticFields.get(fieldName);
            if (f == null) {
                f = clazz.getField(fieldName);
                setFieldAccessibleSafe(f);
                cc.staticFields.putIfAbsent(fieldName, f);
            }
            f.set(null, coerceArg(f.getType(), val));
            return val;
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("No such static field: " + clazz.getName() + "/" + fieldName);
        } catch (IllegalAccessException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object invokeStaticMethod(Class<?> clazz, String methodName, Object[] args) {
        if (clazz == java.math.BigInteger.class) {
            if ("fromBigInteger".equals(methodName) && args != null && args.length == 1) {
                return args[0] instanceof java.math.BigInteger bi ? bi : java.math.BigInteger.valueOf(Numbers.toLong(args[0]));
            }
            if ("fromLong".equals(methodName) && args != null && args.length == 1) {
                return java.math.BigInteger.valueOf(Numbers.toLong(args[0]));
            }
        }
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        try {
            int arity = args == null ? 0 : args.length;
            Method best = findBestMethod(clazz, methodName, args, true);
            if (best == null) {
                Method classMethod = findBestMethod(Class.class, methodName, args, false);
                if (classMethod != null) {
                    Object[] coerced = coerceArgs(classMethod.getParameterTypes(), classMethod.isVarArgs(), args);
                    return boxResult(classMethod.invoke(clazz, coerced));
                }
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
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        try {
            ClassCache cc = CLASS_INFO.get(clazz);
            Field f = cc.instanceFields.get(fieldName);
            if (f == null) {
                f = findField(clazz, fieldName);
                if (f != null) {
                    setFieldAccessibleSafe(f);
                    cc.instanceFields.putIfAbsent(fieldName, f);
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
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        try {
            Field f = findField(clazz, fieldName);
            if (f == null) throw new IllegalArgumentException("No such field " + fieldName + " on " + clazz.getName());
            setFieldAccessibleSafe(f);
            Object coerced = coerceArg(f.getType(), val);
            f.set(target, coerced);
            return val;
        } catch (IllegalAccessException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Field f = findFieldExact(clazz, name);
        if (f != null) return f;
        String munged = mungeMemberName(name);
        if (!munged.equals(name)) {
            return findFieldExact(clazz, munged);
        }
        return null;
    }

    private static Field findFieldExact(Class<?> clazz, String name) {
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

    private static String mungeMemberName(String name) {
        if (name == null || name.isEmpty()) return name;
        boolean needs = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isJavaIdentifierPart(c) || (i == 0 && !Character.isJavaIdentifierStart(c))) {
                needs = true;
                break;
            }
        }
        if (!needs) return name;
        StringBuilder sb = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '-' -> sb.append('_');
                case '?' -> sb.append("_QMARK_");
                case '!' -> sb.append("_BANG_");
                case '*' -> sb.append("_STAR_");
                case '+' -> sb.append("_PLUS_");
                case '>' -> sb.append("_GT_");
                case '<' -> sb.append("_LT_");
                case '=' -> sb.append("_EQ_");
                case '/' -> sb.append("_SLASH_");
                default -> sb.append(c);
            }
        }
        return sb.toString();
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
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        try {
            int arity = args == null ? 0 : args.length;
            Method best = findBestMethod(clazz, methodName, args, false);
            if (best == null) {
                if (arity == 0) {
                    Field f = findField(clazz, methodName);
                    if (f != null) {
                        setFieldAccessibleSafe(f);
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
        ClassCache cc = CLASS_INFO.get(clazz);
        ConcurrentHashMap<String, CacheEntry<Method>[]> map = isStatic ? cc.staticMethods : cc.instanceMethods;
        Method cached = lookupEntry(map.get(name), args);
        if (cached != null) {
            return cached;
        }

        Method best = findBestMethodExact(clazz, name, args, isStatic);
        if (best == null) {
            String munged = mungeMemberName(name);
            if (!munged.equals(name)) {
                best = findBestMethodExact(clazz, munged, args, isStatic);
            }
        }
        if (best != null) {
            best = setAccessibleSafe(best, clazz);
            final Method resolved = best;
            final Class<?>[] sig = captureArgTypes(args);
            map.compute(name, (k, existing) -> {
                if (lookupEntry(existing, args) != null) return existing;
                return appendEntry(existing, new CacheEntry<>(sig, resolved));
            });
        }
        return best;
    }

    private static Method findBestMethodExact(Class<?> clazz, String name, Object[] args, boolean isStatic) {
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
        return best;
    }

    private static boolean isAccessibleFromHere(Class<?> c) {
        if (!Modifier.isPublic(c.getModifiers())) return false;
        Module targetMod = c.getModule();
        Module myMod = Interop.class.getModule();
        if (targetMod == myMod) return true;
        String pkg = c.getPackageName();
        return pkg.isEmpty() || targetMod.isExported(pkg, myMod);
    }

    private static Method setAccessibleSafe(Method m, Class<?> clazz) {
        if (isAccessibleFromHere(m.getDeclaringClass()) && Modifier.isPublic(m.getModifiers())) {
            return m;
        }
        Method pub = findPublicMethodOnHierarchy(clazz, m.getName(), m.getParameterTypes());
        if (pub != null) {
            return pub;
        }
        try { m.setAccessible(true); } catch (Exception ignored) {}
        return m;
    }

    private static Method findPublicMethodOnHierarchy(Class<?> clazz, String name, Class<?>[] paramTypes) {
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            if (isAccessibleFromHere(cur)) {
                try {
                    Method sm = cur.getDeclaredMethod(name, paramTypes);
                    if (Modifier.isPublic(sm.getModifiers())) return sm;
                } catch (NoSuchMethodException ignored) {}
                Method compat = findCompatiblePublicMethod(cur.getDeclaredMethods(), name, paramTypes);
                if (compat != null) return compat;
            }
            Method ifaceMethod = findPublicMethodOnInterfaces(cur.getInterfaces(), name, paramTypes);
            if (ifaceMethod != null) return ifaceMethod;
        }
        return null;
    }

    private static Method findPublicMethodOnInterfaces(Class<?>[] ifaces, String name, Class<?>[] paramTypes) {
        for (Class<?> iface : ifaces) {
            if (isAccessibleFromHere(iface)) {
                try {
                    Method im = iface.getMethod(name, paramTypes);
                    if (Modifier.isPublic(im.getModifiers())) return im;
                } catch (NoSuchMethodException ignored) {}
                Method compat = findCompatiblePublicMethod(iface.getMethods(), name, paramTypes);
                if (compat != null) return compat;
            }
            Method sup = findPublicMethodOnInterfaces(iface.getInterfaces(), name, paramTypes);
            if (sup != null) return sup;
        }
        return null;
    }

    private static Method findCompatiblePublicMethod(Method[] methods, String name, Class<?>[] paramTypes) {
        for (Method m : methods) {
            if (!m.getName().equals(name) || !Modifier.isPublic(m.getModifiers()) || m.isBridge()) continue;
            Class<?>[] candidateTypes = m.getParameterTypes();
            if (candidateTypes.length != paramTypes.length) continue;
            boolean ok = true;
            for (int i = 0; i < paramTypes.length; i++) {
                if (!candidateTypes[i].isAssignableFrom(paramTypes[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) return m;
        }
        return null;
    }

    public static Method findPublicMethodForTypes(Class<?> clazz, String name, ValType[] argTypes, boolean isStatic) {
        if (clazz == null || !Modifier.isPublic(clazz.getModifiers())) return null;
        int arity = argTypes == null ? 0 : argTypes.length;
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean ambiguous = false;
        int candidateCount = 0;
        Method singleCandidate = null;
        boolean hasVarArgsCandidate = false;

        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isStatic(m.getModifiers()) != isStatic) continue;
            if (m.isVarArgs()) {
                if (m.getParameterCount() - 1 <= arity) {
                    hasVarArgsCandidate = true;
                }
                continue;
            }
            if (m.getParameterCount() != arity) continue;
            candidateCount++;
            singleCandidate = m;
            int s = staticMatchScore(m.getParameterTypes(), argTypes);
            if (s >= 0) {
                if (s < bestScore) {
                    bestScore = s;
                    best = m;
                    ambiguous = false;
                } else if (s == bestScore) {
                    ambiguous = true;
                }
            }
        }
        if (!hasVarArgsCandidate && candidateCount == 1 && singleCandidate != null
                && staticCompatibleWithUnknown(singleCandidate.getParameterTypes(), argTypes)) {
            best = singleCandidate;
            ambiguous = false;
        }
        if (hasVarArgsCandidate && hasUnknownArgType(argTypes)) {
            return null;
        }
        if (best == null || ambiguous) return null;
        if (!Modifier.isPublic(best.getModifiers())) return null;
        if (!Modifier.isPublic(best.getDeclaringClass().getModifiers())) {
            Method pub = findPublicMethodOnHierarchy(clazz, best.getName(), best.getParameterTypes());
            if (pub == null) return null;
            best = pub;
        }
        return best;
    }

    public static Constructor<?> findPublicConstructorForTypes(Class<?> clazz, ValType[] argTypes) {
        if (clazz == null || !Modifier.isPublic(clazz.getModifiers())) return null;
        int arity = argTypes == null ? 0 : argTypes.length;
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean ambiguous = false;
        int candidateCount = 0;
        Constructor<?> singleCandidate = null;
        boolean hasVarArgsCandidate = false;

        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.isVarArgs()) {
                if (c.getParameterCount() - 1 <= arity) {
                    hasVarArgsCandidate = true;
                }
                continue;
            }
            if (c.getParameterCount() != arity) continue;
            candidateCount++;
            singleCandidate = c;
            int s = staticMatchScore(c.getParameterTypes(), argTypes);
            if (s >= 0) {
                if (s < bestScore) {
                    bestScore = s;
                    best = c;
                    ambiguous = false;
                } else if (s == bestScore) {
                    ambiguous = true;
                }
            }
        }
        if (!hasVarArgsCandidate && candidateCount == 1 && singleCandidate != null
                && staticCompatibleWithUnknown(singleCandidate.getParameterTypes(), argTypes)) {
            best = singleCandidate;
            ambiguous = false;
        }
        if (hasVarArgsCandidate && hasUnknownArgType(argTypes)) {
            return null;
        }
        return (best != null && !ambiguous && Modifier.isPublic(best.getModifiers())) ? best : null;
    }

    private static boolean hasUnknownArgType(ValType[] argTypes) {
        if (argTypes == null) return false;
        for (ValType at : argTypes) {
            if (at == null || at.kind == ValType.Kind.ANY || at.kind == ValType.Kind.BOTTOM) {
                return true;
            }
        }
        return false;
    }

    private static boolean staticCompatibleWithUnknown(Class<?>[] paramTypes, ValType[] argTypes) {
        for (int i = 0; i < paramTypes.length; i++) {
            ValType at = argTypes[i];
            if (at == null || at.kind == ValType.Kind.ANY || at.kind == ValType.Kind.BOTTOM) {
                continue;
            }
            if (staticMatchScore(new Class<?>[]{paramTypes[i]}, new ValType[]{at}) < 0) {
                return false;
            }
        }
        return true;
    }

    public static Field findPublicField(Class<?> clazz, String fieldName, boolean isStatic) {
        if (clazz == null || !Modifier.isPublic(clazz.getModifiers())) return null;
        try {
            Field f = clazz.getField(fieldName);
            if (Modifier.isStatic(f.getModifiers()) == isStatic
                    && Modifier.isPublic(f.getModifiers())
                    && Modifier.isPublic(f.getDeclaringClass().getModifiers())) {
                return f;
            }
        } catch (NoSuchFieldException ignored) {}
        return null;
    }

    private static int staticMatchScore(Class<?>[] paramTypes, ValType[] argTypes) {
        int score = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> p = paramTypes[i];
            ValType at = argTypes[i];
            if (at == null || at.kind == ValType.Kind.ANY || at.kind == ValType.Kind.BOTTOM) {
                if (p == Object.class) score += 2;
                else return -1;
            } else if (at.kind == ValType.Kind.NIL) {
                if (p.isPrimitive()) return -1;
                score += 1;
            } else if (at.kind == ValType.Kind.LONG) {
                if (p == long.class || p == Long.class) score += 0;
                else if (p == int.class || p == Integer.class) score += 1;
                else if (p == double.class || p == Double.class || p == float.class || p == Float.class
                        || p == short.class || p == Short.class || p == byte.class || p == Byte.class) score += 2;
                else if (p == Number.class || p == Object.class) score += 3;
                else return -1;
            } else if (at.kind == ValType.Kind.DOUBLE) {
                if (p == double.class || p == Double.class) score += 0;
                else if (p == float.class || p == Float.class) score += 1;
                else if (p == Number.class || p == Object.class) score += 3;
                else return -1;
            } else if (at.kind == ValType.Kind.BOOL) {
                if (p == boolean.class || p == Boolean.class) score += 0;
                else if (p == Object.class) score += 3;
                else return -1;
            } else if (at.kind == ValType.Kind.REF) {
                Class<?> ac = at.refClass;
                if (p == ac) score += 0;
                else if (p.isAssignableFrom(ac)) score += (p == Object.class ? 3 : 1);
                else if (p == Comparator.class && Fn.class.isAssignableFrom(ac)) score += 1;
                else if (Fn.class.isAssignableFrom(ac) && isFunctionalInterface(p)) score += 2;
                else return -1;
            }
        }
        return score;
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
        if (param == PersistentHashMap.class && arg instanceof IPersistentMap) return 2;
        if (param == Comparator.class && arg instanceof Fn) return 1;
        if (arg instanceof Fn && isFunctionalInterface(param)) return 2;
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
        if (param == PersistentHashMap.class && arg instanceof IPersistentMap) return PersistentHashMap.fromMap(arg);
        if (param == Comparator.class && arg instanceof Fn f) return f.asComparator();
        if (arg instanceof Fn f && isFunctionalInterface(param)) return adaptFnToSam(param, f);
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

    // ========================================================================
    // Clojure 1.12 Param-Tags, Qualified Method Invocations & Method Values
    // ========================================================================

    private static boolean matchesParamTags(Class<?>[] paramTypes, Class<?>[] paramTags) {
        if (paramTags == null) return true;
        if (paramTypes.length != paramTags.length) return false;
        for (int i = 0; i < paramTags.length; i++) {
            Class<?> tag = paramTags[i];
            if (tag != null && paramTypes[i] != tag) return false;
        }
        return true;
    }

    public static Method findPublicMethodForTags(
            Class<?> clazz, String name, Class<?>[] paramTags, ValType[] argTypes, boolean isStatic) {
        if (paramTags == null) return findPublicMethodForTypes(clazz, name, argTypes, isStatic);
        if (clazz == null || !Modifier.isPublic(clazz.getModifiers())) return null;
        int arity = argTypes == null ? 0 : argTypes.length;
        if (paramTags.length != arity) return null;
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean ambiguous = false;
        int matchCount = 0;
        Method singleMatch = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (Modifier.isStatic(m.getModifiers()) != isStatic) continue;
            if (m.getParameterCount() != arity) continue;
            if (!matchesParamTags(m.getParameterTypes(), paramTags)) continue;
            matchCount++;
            singleMatch = m;
            int s = staticMatchScore(m.getParameterTypes(), argTypes);
            if (s >= 0) {
                if (s < bestScore) { bestScore = s; best = m; ambiguous = false; }
                else if (s == bestScore) ambiguous = true;
            }
        }
        if (matchCount == 1 && singleMatch != null && !singleMatch.isVarArgs()
                && staticCompatibleWithUnknown(singleMatch.getParameterTypes(), argTypes)) {
            best = singleMatch;
            ambiguous = false;
        }
        if (best == null || ambiguous || best.isVarArgs()) return null;
        if (!Modifier.isPublic(best.getModifiers())) return null;
        if (!Modifier.isPublic(best.getDeclaringClass().getModifiers())) {
            Method pub = findPublicMethodOnHierarchy(clazz, best.getName(), best.getParameterTypes());
            if (pub == null) return null;
            best = pub;
        }
        return best;
    }

    public static Constructor<?> findPublicConstructorForTags(
            Class<?> clazz, Class<?>[] paramTags, ValType[] argTypes) {
        if (paramTags == null) return findPublicConstructorForTypes(clazz, argTypes);
        if (clazz == null || !Modifier.isPublic(clazz.getModifiers())) return null;
        int arity = argTypes == null ? 0 : argTypes.length;
        if (paramTags.length != arity) return null;
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        boolean ambiguous = false;
        int matchCount = 0;
        Constructor<?> singleMatch = null;
        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.getParameterCount() != arity) continue;
            if (!matchesParamTags(c.getParameterTypes(), paramTags)) continue;
            matchCount++;
            singleMatch = c;
            int s = staticMatchScore(c.getParameterTypes(), argTypes);
            if (s >= 0) {
                if (s < bestScore) { bestScore = s; best = c; ambiguous = false; }
                else if (s == bestScore) ambiguous = true;
            }
        }
        if (matchCount == 1 && singleMatch != null && !singleMatch.isVarArgs()
                && staticCompatibleWithUnknown(singleMatch.getParameterTypes(), argTypes)) {
            best = singleMatch;
            ambiguous = false;
        }
        return (best != null && !ambiguous && !best.isVarArgs() && Modifier.isPublic(best.getModifiers())) ? best : null;
    }

    private static boolean effectiveVarArgs(Class<?>[] params, boolean isVarArgs, Object[] args) {
        if (!isVarArgs) return false;
        int n = args == null ? 0 : args.length;
        if (n == params.length) {
            Object last = args[n - 1];
            if (last == null || params[params.length - 1].isInstance(last)) {
                return false;
            }
        }
        return true;
    }

    public static Object invokeConstructorWithTags(Class<?> clazz, Class<?>[] paramTags, Object[] args) {
        if (paramTags == null) return invokeConstructor(clazz, args);
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        int arity = args == null ? 0 : args.length;
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Constructor<?> c : clazz.getConstructors()) {
            if (!matchesParamTags(c.getParameterTypes(), paramTags)) continue;
            boolean va = effectiveVarArgs(c.getParameterTypes(), c.isVarArgs(), args);
            if (!va && paramTags.length != arity) continue;
            int score = matchScore(c.getParameterTypes(), va, args);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("No matching constructor for " + clazz.getName() + " with param-tags");
        }
        if (!Modifier.isPublic(best.getModifiers()) || !isAccessibleFromHere(clazz)) {
            try { best.setAccessible(true); } catch (Exception ignored) {}
        }
        boolean va = effectiveVarArgs(best.getParameterTypes(), best.isVarArgs(), args);
        try {
            return best.newInstance(coerceArgs(best.getParameterTypes(), va, args));
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object invokeStaticMethodWithTags(Class<?> clazz, String methodName, Class<?>[] paramTags, Object[] args) {
        if (paramTags == null) return invokeStaticMethod(clazz, methodName, args);
        if (isConcurrencyClass(clazz)) Atom.markMultiThreaded();
        int arity = args == null ? 0 : args.length;
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(methodName) || !Modifier.isStatic(m.getModifiers())) continue;
            if (!matchesParamTags(m.getParameterTypes(), paramTags)) continue;
            boolean va = effectiveVarArgs(m.getParameterTypes(), m.isVarArgs(), args);
            if (!va && paramTags.length != arity) continue;
            int score = matchScore(m.getParameterTypes(), va, args);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("No matching static method " + clazz.getName() + "/" + methodName + " with param-tags");
        }
        best = setAccessibleSafe(best, clazz);
        boolean va = effectiveVarArgs(best.getParameterTypes(), best.isVarArgs(), args);
        try {
            return boxResult(best.invoke(null, coerceArgs(best.getParameterTypes(), va, args)));
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    public static Object invokeInstanceMethodWithTags(Object target, String methodName, Class<?>[] paramTags, Object[] args) {
        if (target == null) {
            throw new NullPointerException("Cannot call ." + methodName + " on nil");
        }
        return invokeQualifiedInstanceMethod(target.getClass(), methodName, paramTags, target, args);
    }

    public static Object invokeQualifiedInstanceMethod(
            Class<?> declaredClass, String methodName, Class<?>[] paramTags, Object target, Object[] args) {
        if (target == null) {
            throw new NullPointerException("Cannot call " + declaredClass.getName() + "/." + methodName + " on nil");
        }
        if (!declaredClass.isInstance(target)) {
            throw new ClassCastException("Cannot cast " + target.getClass().getName() + " to " + declaredClass.getName());
        }
        if (paramTags == null) {
            return invokeInstanceMethod(target, methodName, args);
        }
        if (isConcurrencyClass(declaredClass)) Atom.markMultiThreaded();
        int arity = args == null ? 0 : args.length;
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : declaredClass.getMethods()) {
            if (!m.getName().equals(methodName) || Modifier.isStatic(m.getModifiers())) continue;
            if (!matchesParamTags(m.getParameterTypes(), paramTags)) continue;
            boolean va = effectiveVarArgs(m.getParameterTypes(), m.isVarArgs(), args);
            if (!va && paramTags.length != arity) continue;
            int score = matchScore(m.getParameterTypes(), va, args);
            if (score >= 0 && score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("No matching instance method " + declaredClass.getName() + "/." + methodName + " with param-tags");
        }
        best = setAccessibleSafe(best, declaredClass);
        boolean va = effectiveVarArgs(best.getParameterTypes(), best.isVarArgs(), args);
        try {
            return boxResult(best.invoke(target, coerceArgs(best.getParameterTypes(), va, args)));
        } catch (InvocationTargetException e) {
            throw RT.sneakyThrow(e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static List<Method> dedupMethods(List<Method> raw) {
        Map<List<Class<?>>, Method> bySig = new LinkedHashMap<>();
        for (Method m : raw) {
            List<Class<?>> sig = Arrays.asList(m.getParameterTypes());
            Method prev = bySig.get(sig);
            if (prev == null || (prev.isBridge() && !m.isBridge())
                    || (!m.isBridge() && prev.getReturnType().isAssignableFrom(m.getReturnType()))) {
                bySig.put(sig, m);
            }
        }
        return new ArrayList<>(bySig.values());
    }

    private static Method[] buildUniqueByArity(Class<?> clazz, String label, List<Method> methods, Class<?>[] paramTags) {
        List<Method> dedup = dedupMethods(methods);
        if (dedup.isEmpty()) {
            throw new IllegalArgumentException("No matching method " + label + (paramTags != null ? " for param-tags" : ""));
        }
        if (paramTags != null) {
            if (dedup.size() > 1) {
                throw new IllegalArgumentException("Ambiguous method " + label + " for param-tags (" + dedup.size() + " matches)");
            }
            Method m = setAccessibleSafe(dedup.get(0), clazz);
            int pCount = m.getParameterCount();
            Method[] arr = new Method[pCount + 1];
            arr[pCount] = m;
            return arr;
        }
        int maxArity = 0;
        for (Method m : dedup) {
            maxArity = Math.max(maxArity, m.getParameterCount());
        }
        Method[] byArity = new Method[maxArity + 1];
        for (Method m : dedup) {
            int a = m.getParameterCount();
            if (byArity[a] != null) {
                throw new IllegalArgumentException("Method " + label + " is ambiguous for arity " + a + "; specify ^[...] :param-tags");
            }
            byArity[a] = setAccessibleSafe(m, clazz);
        }
        return byArity;
    }

    public static Fn createStaticMethodFn(Class<?> clazz, String methodName, Class<?>[] paramTags) {
        List<Method> matches = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())
                    && (paramTags == null || matchesParamTags(m.getParameterTypes(), paramTags))) {
                matches.add(m);
            }
        }
        Method[] byArity = buildUniqueByArity(clazz, clazz.getName() + "/" + methodName, matches, paramTags);
        return new StaticMethodValueFn(clazz, methodName, paramTags, byArity);
    }

    public static Fn createInstanceMethodFn(Class<?> clazz, String methodName, Class<?>[] paramTags) {
        List<Method> matches = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && !Modifier.isStatic(m.getModifiers())
                    && (paramTags == null || matchesParamTags(m.getParameterTypes(), paramTags))) {
                matches.add(m);
            }
        }
        Method[] byArity = buildUniqueByArity(clazz, clazz.getName() + "/." + methodName, matches, paramTags);
        return new InstanceMethodValueFn(clazz, methodName, paramTags, byArity);
    }

    public static Fn createConstructorFn(Class<?> clazz, Class<?>[] paramTags) {
        List<Constructor<?>> matches = new ArrayList<>();
        for (Constructor<?> c : clazz.getConstructors()) {
            if (paramTags == null || matchesParamTags(c.getParameterTypes(), paramTags)) {
                matches.add(c);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No matching constructor " + clazz.getName() + "/new" + (paramTags != null ? " for param-tags" : ""));
        }
        if (paramTags != null && matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous constructor " + clazz.getName() + "/new for param-tags (" + matches.size() + " matches)");
        }
        int maxArity = 0;
        for (Constructor<?> c : matches) maxArity = Math.max(maxArity, c.getParameterCount());
        Constructor<?>[] byArity = new Constructor<?>[maxArity + 1];
        for (Constructor<?> c : matches) {
            int a = c.getParameterCount();
            if (byArity[a] != null) {
                throw new IllegalArgumentException("Constructor " + clazz.getName() + "/new is ambiguous for arity " + a + "; specify ^[...] :param-tags");
            }
            if (!Modifier.isPublic(c.getModifiers()) || !isAccessibleFromHere(clazz)) {
                try { c.setAccessible(true); } catch (Exception ignored) {}
            }
            byArity[a] = c;
        }
        return new ConstructorValueFn(clazz, paramTags, byArity);
    }

    private static final class StaticMethodValueFn extends Fn.NamedFn {
        private final Class<?> clazz;
        private final String methodName;
        private final Class<?>[] paramTags;
        private final Method[] byArity;
        private final Method varArgsMethod;

        StaticMethodValueFn(Class<?> clazz, String methodName, Class<?>[] paramTags, Method[] byArity) {
            super(clazz.getName() + "/" + methodName);
            this.clazz = clazz;
            this.methodName = methodName;
            this.paramTags = paramTags;
            this.byArity = byArity;
            Method va = null;
            for (Method m : byArity) {
                if (m != null && m.isVarArgs()) { va = m; break; }
            }
            this.varArgsMethod = va;
        }

        @Override
        public Object invokeVarargs(Object[] args) {
            int n = args.length;
            Method m = (n < byArity.length) ? byArity[n] : null;
            if (m == null && varArgsMethod != null && n >= varArgsMethod.getParameterCount() - 1) {
                m = varArgsMethod;
            }
            if (m == null) {
                throw arityError(n);
            }
            boolean va = effectiveVarArgs(m.getParameterTypes(), m.isVarArgs(), args);
            try {
                return boxResult(m.invoke(null, coerceArgs(m.getParameterTypes(), va, args)));
            } catch (InvocationTargetException e) {
                throw RT.sneakyThrow(e.getTargetException());
            } catch (ReflectiveOperationException e) {
                throw RT.sneakyThrow(e);
            }
        }
    }

    private static final class InstanceMethodValueFn extends Fn.NamedFn {
        private final Class<?> clazz;
        private final String methodName;
        private final Class<?>[] paramTags;
        private final Method[] byArity;
        private final Method varArgsMethod;

        InstanceMethodValueFn(Class<?> clazz, String methodName, Class<?>[] paramTags, Method[] byArity) {
            super(clazz.getName() + "/." + methodName);
            this.clazz = clazz;
            this.methodName = methodName;
            this.paramTags = paramTags;
            this.byArity = byArity;
            Method va = null;
            for (Method m : byArity) {
                if (m != null && m.isVarArgs()) { va = m; break; }
            }
            this.varArgsMethod = va;
        }

        @Override
        public Object invokeVarargs(Object[] args) {
            if (args.length < 1) {
                throw arityError(0);
            }
            Object target = args[0];
            if (target == null) {
                throw new NullPointerException("Cannot call " + clazz.getName() + "/." + methodName + " on nil");
            }
            if (!clazz.isInstance(target)) {
                throw new ClassCastException("Cannot cast " + target.getClass().getName() + " to " + clazz.getName());
            }
            Object[] rest = args.length == 1 ? Fn.EMPTY_ARGS : Arrays.copyOfRange(args, 1, args.length);
            int n = rest.length;
            Method m = (n < byArity.length) ? byArity[n] : null;
            if (m == null && varArgsMethod != null && n >= varArgsMethod.getParameterCount() - 1) {
                m = varArgsMethod;
            }
            if (m == null) {
                throw arityError(args.length);
            }
            boolean va = effectiveVarArgs(m.getParameterTypes(), m.isVarArgs(), rest);
            try {
                return boxResult(m.invoke(target, coerceArgs(m.getParameterTypes(), va, rest)));
            } catch (InvocationTargetException e) {
                throw RT.sneakyThrow(e.getTargetException());
            } catch (ReflectiveOperationException e) {
                throw RT.sneakyThrow(e);
            }
        }
    }

    private static final class ConstructorValueFn extends Fn.NamedFn {
        private final Class<?> clazz;
        private final Class<?>[] paramTags;
        private final Constructor<?>[] byArity;
        private final Constructor<?> varArgsCtor;

        ConstructorValueFn(Class<?> clazz, Class<?>[] paramTags, Constructor<?>[] byArity) {
            super(clazz.getName() + "/new");
            this.clazz = clazz;
            this.paramTags = paramTags;
            this.byArity = byArity;
            Constructor<?> va = null;
            for (Constructor<?> c : byArity) {
                if (c != null && c.isVarArgs()) { va = c; break; }
            }
            this.varArgsCtor = va;
        }

        @Override
        public Object invokeVarargs(Object[] args) {
            int n = args.length;
            Constructor<?> c = (n < byArity.length) ? byArity[n] : null;
            if (c == null && varArgsCtor != null && n >= varArgsCtor.getParameterCount() - 1) {
                c = varArgsCtor;
            }
            if (c == null) {
                throw arityError(n);
            }
            boolean va = effectiveVarArgs(c.getParameterTypes(), c.isVarArgs(), args);
            try {
                return c.newInstance(coerceArgs(c.getParameterTypes(), va, args));
            } catch (InvocationTargetException e) {
                throw RT.sneakyThrow(e.getTargetException());
            } catch (ReflectiveOperationException e) {
                throw RT.sneakyThrow(e);
            }
        }
    }
}
