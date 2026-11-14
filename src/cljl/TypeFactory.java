package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic JVM class/interface/record/type/reify/proxy factory for ClojureLite.
 * Generates v49.0 JVM classfiles in memory with zero external dependencies.
 */
public final class TypeFactory {

    private static final DynamicClassLoader LOADER = new DynamicClassLoader();
    private static final AtomicInteger PROXY_ID = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Fn>> CLASS_METHODS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<List<Class<?>>, Class<?>> PROXY_CLASS_CACHE =
            new ConcurrentHashMap<>();

    private TypeFactory() {}

    public static final class DynamicClassLoader extends ClassLoader {
        DynamicClassLoader() {
            super(TypeFactory.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            throw new ClassNotFoundException(name);
        }
    }

    public interface ProxyObject {
        PersistentHashMap __getMappings();
        void __setMappings(PersistentHashMap m);
    }

    public record MethodSig(String name, Class<?> returnType, Class<?>[] paramTypes) {}

    public static Object dispatchMethod(String className, String methodKey, Object target, Object[] args) {
        ConcurrentHashMap<String, Fn> table = CLASS_METHODS.get(className);
        if (table != null) {
            Fn fn = table.get(methodKey);
            if (fn != null) {
                Object[] callArgs = new Object[1 + (args != null ? args.length : 0)];
                callArgs[0] = target;
                if (args != null && args.length > 0) {
                    System.arraycopy(args, 0, callArgs, 1, args.length);
                }
                return fn.applyTo(callArgs);
            }
        }
        throw new UnsupportedOperationException("Unimplemented method " + methodKey + " on " + className);
    }

    public static Fn lookupProxyFn(ProxyObject proxy, String name) {
        PersistentHashMap m = proxy.__getMappings();
        if (m == null) return null;
        Object fn = m.valAt(name);
        if (fn instanceof Fn f) return f;
        Object kwFn = m.valAt(Keyword.intern(name));
        return kwFn instanceof Fn f ? f : null;
    }

    public static Object invokeProxyFn(Fn fn, Object proxy, Object[] args) {
        Object[] callArgs = new Object[1 + (args != null ? args.length : 0)];
        callArgs[0] = proxy;
        if (args != null && args.length > 0) {
            System.arraycopy(args, 0, callArgs, 1, args.length);
        }
        return fn.applyTo(callArgs);
    }

    public static synchronized Class<?> defineInterface(String binaryName, List<MethodSig> methods) {
        String internalName = binaryName.replace('.', '/');
        ClassWriter cw = new ClassWriter(internalName, "java/lang/Object", new String[0], 0x0601); // PUBLIC | INTERFACE | ABSTRACT
        for (MethodSig m : methods) {
            cw.addAbstractMethod(m.name(), methodDesc(m.returnType(), m.paramTypes()));
        }
        byte[] bytes = cw.toByteArray();
        Class<?> cls = LOADER.define(binaryName, bytes);
        Interop.registerClass(binaryName, cls);
        return cls;
    }

    public static synchronized Class<?> defineRecord(
            String binaryName,
            String[] fieldNames,
            Class<?>[] interfaces,
            Map<String, Fn> methodImpls) {
        String internalName = binaryName.replace('.', '/');
        String superInternal = "cljl/lang/PersistentRecord";
        String[] ifaceInternals = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) ifaceInternals[i] = interfaces[i].getName().replace('.', '/');

        CLASS_METHODS.put(binaryName, new ConcurrentHashMap<>(methodImpls));

        ClassWriter cw = new ClassWriter(internalName, superInternal, ifaceInternals, 0x0011); // PUBLIC | FINAL

        // Fields: public final Object <field>;
        for (String f : fieldNames) {
            cw.addField(0x0011, f, "Ljava/lang/Object;");
        }
        // Static fields: KEYS ([Ljava/lang/Object;) initialized lazily via <clinit>
        cw.addField(0x001a, "__KEYS", "[Ljava/lang/Object;"); // PRIVATE | STATIC | FINAL
        cw.emitRecordClinit(fieldNames);

        // Short ctor: (Object f0, ...) -> this(f0, ..., null, null)
        cw.emitRecordShortCtor(fieldNames);
        // Full ctor: (Object f0, ..., PersistentHashMap meta, PersistentHashMap ext)
        cw.emitRecordFullCtor(fieldNames);
        // __baseKeys() -> __KEYS
        cw.emitBaseKeysMethod();
        // __fieldIndex(Object key)
        cw.emitFieldIndexMethod(fieldNames);
        // __fieldValue(int idx)
        cw.emitFieldValueMethod(fieldNames);
        // __withField(int idx, Object val)
        cw.emitWithFieldMethod(fieldNames);
        // __withExt(PersistentHashMap meta, PersistentHashMap ext)
        cw.emitWithExtMethod(fieldNames);

        // Implement interface methods delegating to TypeFactory.dispatchMethod
        emitInterfaceBridges(cw, binaryName, interfaces, methodImpls);

        byte[] bytes = cw.toByteArray();
        Class<?> cls = LOADER.define(binaryName, bytes);
        Interop.registerClass(binaryName, cls);
        return cls;
    }

    public static synchronized Class<?> defineType(
            String binaryName,
            String[] fieldNames,
            boolean[] mutable,
            Class<?>[] interfaces,
            Map<String, Fn> methodImpls) {
        String internalName = binaryName.replace('.', '/');
        String[] ifaceInternals = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) ifaceInternals[i] = interfaces[i].getName().replace('.', '/');

        CLASS_METHODS.put(binaryName, new ConcurrentHashMap<>(methodImpls));

        ClassWriter cw = new ClassWriter(internalName, "java/lang/Object", ifaceInternals, 0x0011);
        for (int i = 0; i < fieldNames.length; i++) {
            int flags = mutable[i] ? 0x0041 : 0x0011; // PUBLIC | VOLATILE vs PUBLIC | FINAL
            cw.addField(flags, fieldNames[i], "Ljava/lang/Object;");
        }
        cw.emitSimpleCtor(fieldNames);
        emitInterfaceBridges(cw, binaryName, interfaces, methodImpls);

        byte[] bytes = cw.toByteArray();
        Class<?> cls = LOADER.define(binaryName, bytes);
        Interop.registerClass(binaryName, cls);
        return cls;
    }

    private static void emitInterfaceBridges(
            ClassWriter cw, String binaryName, Class<?>[] interfaces, Map<String, Fn> methodImpls) {
        Set<String> emitted = new HashSet<>();
        for (Class<?> iface : interfaces) {
            for (Method m : iface.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                String desc = methodDesc(m.getReturnType(), m.getParameterTypes());
                String sigKey = m.getName() + desc;
                if (!emitted.add(sigKey)) continue;
                cw.emitDispatchBridge(binaryName, m.getName(), m.getReturnType(), m.getParameterTypes());
            }
        }
        // Also emit Object methods (toString/equals/hashCode) if explicitly overridden in methodImpls
        for (String name : methodImpls.keySet()) {
            if ("toString".equals(name) && emitted.add("toString()Ljava/lang/String;")) {
                cw.emitDispatchBridge(binaryName, "toString", String.class, new Class<?>[0]);
            } else if ("hashCode".equals(name) && emitted.add("hashCode()I")) {
                cw.emitDispatchBridge(binaryName, "hashCode", int.class, new Class<?>[0]);
            } else if ("equals".equals(name) && emitted.add("equals(Ljava/lang/Object;)Z")) {
                cw.emitDispatchBridge(binaryName, "equals", boolean.class, new Class<?>[]{Object.class});
            }
        }
    }

    public static Object createReify(
            Class<?>[] interfaces,
            Map<String, Fn> javaMethodImpls,
            Map<Protocol, Map<Keyword, Fn>> protocolImpls,
            PersistentHashMap meta) {
        List<Class<?>> allIfaces = new ArrayList<>();
        allIfaces.add(IMeta.class);
        allIfaces.add(Protocol.Dispatchable.class);
        for (Class<?> c : interfaces) {
            if (c != IMeta.class && c != Protocol.Dispatchable.class) {
                allIfaces.add(c);
            }
        }
        InvocationHandler handler = new InvocationHandler() {
            private final PersistentHashMap myMeta = meta;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String mname = method.getName();
                if ("meta".equals(mname) && method.getParameterCount() == 0) {
                    return myMeta;
                }
                if ("withMeta".equals(mname) && method.getParameterCount() == 1) {
                    return createReify(interfaces, javaMethodImpls, protocolImpls, (PersistentHashMap) args[0]);
                }
                if ("__protocolFn".equals(mname) && method.getParameterCount() == 2) {
                    Protocol p = (Protocol) args[0];
                    Keyword mk = (Keyword) args[1];
                    Map<Keyword, Fn> pmap = protocolImpls.get(p);
                    return pmap != null ? pmap.get(mk) : null;
                }
                if ("__satisfies".equals(mname) && method.getParameterCount() == 1) {
                    return protocolImpls.containsKey((Protocol) args[0]);
                }
                Fn impl = javaMethodImpls.get(mname);
                if (impl != null) {
                    Object[] callArgs = new Object[1 + (args != null ? args.length : 0)];
                    callArgs[0] = proxy;
                    if (args != null && args.length > 0) System.arraycopy(args, 0, callArgs, 1, args.length);
                    return coerceReturn(method.getReturnType(), impl.applyTo(callArgs));
                }
                if ("toString".equals(mname) && method.getParameterCount() == 0) {
                    return "#<reify@" + Integer.toHexString(System.identityHashCode(proxy)) + ">";
                }
                if ("hashCode".equals(mname) && method.getParameterCount() == 0) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(mname) && method.getParameterCount() == 1) {
                    return proxy == args[0];
                }
                throw new UnsupportedOperationException("No implementation of " + mname + " in reify");
            }
        };
        return Proxy.newProxyInstance(LOADER, allIfaces.toArray(new Class<?>[0]), handler);
    }

    public static Class<?> getProxyClass(Class<?> superClass, Class<?>[] interfaces) {
        Class<?> sup = superClass != null ? superClass : Object.class;
        List<Class<?>> key = new ArrayList<>(1 + interfaces.length);
        key.add(sup);
        Collections.addAll(key, interfaces);
        return PROXY_CLASS_CACHE.computeIfAbsent(key, k -> generateProxyClass(sup, interfaces));
    }

    private static synchronized Class<?> generateProxyClass(Class<?> superClass, Class<?>[] interfaces) {
        int id = PROXY_ID.incrementAndGet();
        String binaryName = "cljl.proxy.Proxy$" + id;
        String internalName = binaryName.replace('.', '/');
        String superInternal = superClass.getName().replace('.', '/');

        List<String> ifaceList = new ArrayList<>();
        ifaceList.add("cljl/TypeFactory$ProxyObject");
        for (Class<?> c : interfaces) {
            ifaceList.add(c.getName().replace('.', '/'));
        }

        ClassWriter cw = new ClassWriter(internalName, superInternal, ifaceList.toArray(new String[0]), 0x0001);
        cw.addField(0x0042, "__mappings", "Lcljl/lang/PersistentHashMap;"); // PRIVATE | VOLATILE
        cw.emitProxyMappingsAccessors();

        // Forward constructors from superclass
        for (Constructor<?> ctor : superClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(ctor.getModifiers())) continue;
            cw.emitProxyCtor(superInternal, ctor.getParameterTypes());
        }

        // Override overridable methods
        Set<String> seen = new HashSet<>();
        List<Method> toOverride = new ArrayList<>();
        for (Class<?> iface : interfaces) {
            for (Method m : iface.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                String sig = m.getName() + methodDesc(m.getReturnType(), m.getParameterTypes());
                if (seen.add(sig)) toOverride.add(m);
            }
        }
        for (Class<?> cur = superClass; cur != null; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || Modifier.isPrivate(mod)) continue;
                if (m.getDeclaringClass() == Object.class
                        && !("toString".equals(m.getName()) || "equals".equals(m.getName()) || "hashCode".equals(m.getName()))) {
                    continue;
                }
                String sig = m.getName() + methodDesc(m.getReturnType(), m.getParameterTypes());
                if (seen.add(sig)) toOverride.add(m);
            }
        }
        for (Method m : toOverride) {
            boolean hasSuper = !m.getDeclaringClass().isInterface() && !Modifier.isAbstract(m.getModifiers());
            String ownerInternal = m.getDeclaringClass().getName().replace('.', '/');
            cw.emitProxyOverrideMethod(m.getName(), m.getReturnType(), m.getParameterTypes(), hasSuper, ownerInternal);
            if (hasSuper) {
                cw.emitProxySuperBridge(m.getName(), m.getReturnType(), m.getParameterTypes(), ownerInternal);
            }
        }

        byte[] bytes = cw.toByteArray();
        return LOADER.define(binaryName, bytes);
    }

    public static Object constructProxy(Class<?> proxyClass, Object[] superArgs) {
        return Interop.invokeConstructor(proxyClass, superArgs != null ? superArgs : Fn.EMPTY_ARGS);
    }

    public static Object initProxy(Object proxy, PersistentHashMap mappings) {
        ((ProxyObject) proxy).__setMappings(mappings);
        return proxy;
    }

    public static Object updateProxy(Object proxy, PersistentHashMap mappings) {
        ProxyObject p = (ProxyObject) proxy;
        PersistentHashMap cur = p.__getMappings();
        if (cur == null) cur = PersistentHashMap.EMPTY;
        for (ISeq s = mappings.seq(); s != null; s = s.next()) {
            PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
            cur = cur.assoc(me.key, me.val);
        }
        p.__setMappings(cur);
        return proxy;
    }

    public static PersistentHashMap proxyMappings(Object proxy) {
        return ((ProxyObject) proxy).__getMappings();
    }

    public static Object coerceReturn(Class<?> retType, Object val) {
        if (retType == void.class) return null;
        if (!retType.isPrimitive()) return val;
        if (retType == boolean.class) return Boolean.valueOf(RT.truthy(val));
        if (retType == char.class) return (val instanceof Character c) ? c : Character.valueOf((char) ((Number) val).intValue());
        Number n = (Number) val;
        if (retType == byte.class) return n.byteValue();
        if (retType == short.class) return n.shortValue();
        if (retType == int.class) return n.intValue();
        if (retType == long.class) return n.longValue();
        if (retType == float.class) return n.floatValue();
        if (retType == double.class) return n.doubleValue();
        return val;
    }

    public static String methodDesc(Class<?> ret, Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(typeDesc(p));
        sb.append(')').append(typeDesc(ret));
        return sb.toString();
    }

    public static String typeDesc(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == short.class) return "S";
        if (c == char.class) return "C";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    // ========================================================================
    // Minimal JVM v49.0 ClassFile Writer
    // ========================================================================

    private static final class ClassWriter {
        final String className;
        final String superName;
        final String[] interfaces;
        final int accessFlags;

        final ByteArrayOutputStream cpBuf = new ByteArrayOutputStream(512);
        int cpCount = 1;
        final Map<String, Integer> utf8Map = new HashMap<>();
        final Map<String, Integer> classMap = new HashMap<>();
        final Map<String, Integer> strMap = new HashMap<>();
        final Map<String, Integer> natMap = new HashMap<>();
        final Map<String, Integer> refMap = new HashMap<>();

        final List<byte[]> fields = new ArrayList<>();
        final List<byte[]> methods = new ArrayList<>();

        final int thisIdx;
        final int superIdx;
        final int[] ifaceIdxs;
        final int codeUtfIdx;

        ClassWriter(String className, String superName, String[] interfaces, int accessFlags) {
            this.className = className;
            this.superName = superName;
            this.interfaces = interfaces;
            this.accessFlags = accessFlags;
            this.thisIdx = addClass(className);
            this.superIdx = addClass(superName);
            this.ifaceIdxs = new int[interfaces.length];
            for (int i = 0; i < interfaces.length; i++) ifaceIdxs[i] = addClass(interfaces[i]);
            this.codeUtfIdx = addUtf8("Code");
        }

        int addUtf8(String s) {
            return utf8Map.computeIfAbsent(s, k -> {
                try {
                    ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                    DataOutputStream d = new DataOutputStream(tmp);
                    d.writeByte(1);
                    d.writeUTF(k);
                    cpBuf.write(tmp.toByteArray());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return cpCount++;
            });
        }

        int addClass(String internalName) {
            return classMap.computeIfAbsent(internalName, k -> {
                int ni = addUtf8(k);
                cpBuf.write(7);
                cpBuf.write((ni >>> 8) & 0xff);
                cpBuf.write(ni & 0xff);
                return cpCount++;
            });
        }

        int addString(String s) {
            return strMap.computeIfAbsent(s, k -> {
                int ui = addUtf8(k);
                cpBuf.write(8);
                cpBuf.write((ui >>> 8) & 0xff);
                cpBuf.write(ui & 0xff);
                return cpCount++;
            });
        }

        int addNaT(String name, String desc) {
            return natMap.computeIfAbsent(name + "\0" + desc, k -> {
                int ni = addUtf8(name);
                int di = addUtf8(desc);
                cpBuf.write(12);
                cpBuf.write((ni >>> 8) & 0xff); cpBuf.write(ni & 0xff);
                cpBuf.write((di >>> 8) & 0xff); cpBuf.write(di & 0xff);
                return cpCount++;
            });
        }

        int addRef(int tag, String owner, String name, String desc) {
            return refMap.computeIfAbsent(tag + "\0" + owner + "\0" + name + "\0" + desc, k -> {
                int ci = addClass(owner);
                int nti = addNaT(name, desc);
                cpBuf.write(tag);
                cpBuf.write((ci >>> 8) & 0xff); cpBuf.write(ci & 0xff);
                cpBuf.write((nti >>> 8) & 0xff); cpBuf.write(nti & 0xff);
                return cpCount++;
            });
        }

        void addField(int flags, String name, String desc) {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream(16);
                DataOutputStream d = new DataOutputStream(b);
                d.writeShort(flags);
                d.writeShort(addUtf8(name));
                d.writeShort(addUtf8(desc));
                d.writeShort(0);
                d.flush();
                fields.add(b.toByteArray());
            } catch (IOException e) { throw new RuntimeException(e); }
        }

        void addAbstractMethod(String name, String desc) {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream(16);
                DataOutputStream d = new DataOutputStream(b);
                d.writeShort(0x0401); // PUBLIC | ABSTRACT
                d.writeShort(addUtf8(name));
                d.writeShort(addUtf8(desc));
                d.writeShort(0);
                d.flush();
                methods.add(b.toByteArray());
            } catch (IOException e) { throw new RuntimeException(e); }
        }

        void addCodeMethod(int flags, String name, String desc, int maxStack, int maxLocals, byte[] codeBytes) {
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream(codeBytes.length + 32);
                DataOutputStream d = new DataOutputStream(b);
                d.writeShort(flags);
                d.writeShort(addUtf8(name));
                d.writeShort(addUtf8(desc));
                d.writeShort(1); // 1 attribute: Code
                d.writeShort(codeUtfIdx);
                d.writeInt(12 + codeBytes.length);
                d.writeShort(maxStack);
                d.writeShort(maxLocals);
                d.writeInt(codeBytes.length);
                d.write(codeBytes);
                d.writeShort(0);
                d.writeShort(0);
                d.flush();
                methods.add(b.toByteArray());
            } catch (IOException e) { throw new RuntimeException(e); }
        }

        void emitRecordClinit(String[] fieldNames) {
            MethodAssembler ma = new MethodAssembler(this);
            ma.iconst(fieldNames.length);
            ma.anewarray("java/lang/Object");
            for (int i = 0; i < fieldNames.length; i++) {
                ma.dup();
                ma.iconst(i);
                ma.ldcString(fieldNames[i]);
                ma.invokestatic("cljl/Keyword", "intern", "(Ljava/lang/String;)Lcljl/Keyword;");
                ma.aastore();
            }
            ma.putstatic(className, "__KEYS", "[Ljava/lang/Object;");
            ma.vreturn();
            addCodeMethod(0x0008, "<clinit>", "()V", 6, 0, ma.toBytes());
        }

        void emitRecordShortCtor(String[] fieldNames) {
            StringBuilder desc = new StringBuilder("(");
            StringBuilder fullDesc = new StringBuilder("(");
            for (String ignored : fieldNames) {
                desc.append("Ljava/lang/Object;");
                fullDesc.append("Ljava/lang/Object;");
            }
            desc.append(")V");
            fullDesc.append("Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)V");

            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            for (int i = 0; i < fieldNames.length; i++) ma.aload(1 + i);
            ma.aconstNull();
            ma.aconstNull();
            ma.invokespecial(className, "<init>", fullDesc.toString());
            ma.vreturn();
            addCodeMethod(0x0001, "<init>", desc.toString(), Math.max(4, fieldNames.length + 4), fieldNames.length + 1, ma.toBytes());
        }

        void emitRecordFullCtor(String[] fieldNames) {
            StringBuilder desc = new StringBuilder("(");
            for (String ignored : fieldNames) desc.append("Ljava/lang/Object;");
            desc.append("Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)V");

            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            ma.aload(1 + fieldNames.length);
            ma.aload(2 + fieldNames.length);
            ma.invokespecial(superName, "<init>", "(Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)V");
            for (int i = 0; i < fieldNames.length; i++) {
                ma.aload(0);
                ma.aload(1 + i);
                ma.putfield(className, fieldNames[i], "Ljava/lang/Object;");
            }
            ma.vreturn();
            addCodeMethod(0x0001, "<init>", desc.toString(), 4, fieldNames.length + 3, ma.toBytes());
        }

        void emitBaseKeysMethod() {
            MethodAssembler ma = new MethodAssembler(this);
            ma.getstatic(className, "__KEYS", "[Ljava/lang/Object;");
            ma.areturn();
            addCodeMethod(0x0001, "__baseKeys", "()[Ljava/lang/Object;", 2, 1, ma.toBytes());
        }

        void emitFieldIndexMethod(String[] fieldNames) {
            MethodAssembler ma = new MethodAssembler(this);
            for (int i = 0; i < fieldNames.length; i++) {
                ma.getstatic(className, "__KEYS", "[Ljava/lang/Object;");
                ma.iconst(i);
                ma.aaload();
                ma.aload(1);
                int nextJump = ma.ifAcmpNePlaceholder();
                ma.iconst(i);
                ma.ireturn();
                ma.patchBranchHere(nextJump);
            }
            ma.iconst(-1);
            ma.ireturn();
            addCodeMethod(0x0004, "__fieldIndex", "(Ljava/lang/Object;)I", 4, 2, ma.toBytes());
        }

        void emitFieldValueMethod(String[] fieldNames) {
            MethodAssembler ma = new MethodAssembler(this);
            for (int i = 0; i < fieldNames.length; i++) {
                ma.iload(1);
                ma.iconst(i);
                int nextJump = ma.ifIcmpNePlaceholder();
                ma.aload(0);
                ma.getfield(className, fieldNames[i], "Ljava/lang/Object;");
                ma.areturn();
                ma.patchBranchHere(nextJump);
            }
            ma.aconstNull();
            ma.areturn();
            addCodeMethod(0x0004, "__fieldValue", "(I)Ljava/lang/Object;", 4, 2, ma.toBytes());
        }

        void emitWithFieldMethod(String[] fieldNames) {
            StringBuilder fullDesc = new StringBuilder("(");
            for (String ignored : fieldNames) fullDesc.append("Ljava/lang/Object;");
            fullDesc.append("Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)V");

            MethodAssembler ma = new MethodAssembler(this);
            for (int idx = 0; idx < fieldNames.length; idx++) {
                ma.iload(1);
                ma.iconst(idx);
                int nextJump = ma.ifIcmpNePlaceholder();
                ma.newObj(className);
                ma.dup();
                for (int j = 0; j < fieldNames.length; j++) {
                    if (j == idx) ma.aload(2);
                    else {
                        ma.aload(0);
                        ma.getfield(className, fieldNames[j], "Ljava/lang/Object;");
                    }
                }
                ma.aload(0);
                ma.getfield(superName, "__meta", "Lcljl/lang/PersistentHashMap;");
                ma.aload(0);
                ma.getfield(superName, "__extmap", "Lcljl/lang/PersistentHashMap;");
                ma.invokespecial(className, "<init>", fullDesc.toString());
                ma.areturn();
                ma.patchBranchHere(nextJump);
            }
            ma.aload(0);
            ma.areturn();
            addCodeMethod(0x0004, "__withField", "(ILjava/lang/Object;)Lcljl/lang/PersistentRecord;",
                    Math.max(6, fieldNames.length + 6), 3, ma.toBytes());
        }

        void emitWithExtMethod(String[] fieldNames) {
            StringBuilder fullDesc = new StringBuilder("(");
            for (String ignored : fieldNames) fullDesc.append("Ljava/lang/Object;");
            fullDesc.append("Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)V");

            MethodAssembler ma = new MethodAssembler(this);
            ma.newObj(className);
            ma.dup();
            for (String f : fieldNames) {
                ma.aload(0);
                ma.getfield(className, f, "Ljava/lang/Object;");
            }
            ma.aload(1);
            ma.aload(2);
            ma.invokespecial(className, "<init>", fullDesc.toString());
            ma.areturn();
            addCodeMethod(0x0004, "__withExt",
                    "(Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)Lcljl/lang/PersistentRecord;",
                    Math.max(6, fieldNames.length + 5), 3, ma.toBytes());
        }

        void emitSimpleCtor(String[] fieldNames) {
            StringBuilder desc = new StringBuilder("(");
            for (String ignored : fieldNames) desc.append("Ljava/lang/Object;");
            desc.append(")V");
            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            ma.invokespecial("java/lang/Object", "<init>", "()V");
            for (int i = 0; i < fieldNames.length; i++) {
                ma.aload(0);
                ma.aload(1 + i);
                ma.putfield(className, fieldNames[i], "Ljava/lang/Object;");
            }
            ma.vreturn();
            addCodeMethod(0x0001, "<init>", desc.toString(), 4, fieldNames.length + 1, ma.toBytes());
        }

        void emitDispatchBridge(String binaryName, String mname, Class<?> retType, Class<?>[] paramTypes) {
            String desc = methodDesc(retType, paramTypes);
            MethodAssembler ma = new MethodAssembler(this);
            int nextSlot = 1;
            int[] paramSlots = new int[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramSlots[i] = nextSlot;
                nextSlot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
            }
            ma.ldcString(binaryName);
            ma.ldcString(mname);
            ma.aload(0);
            ma.emitBoxedArray(paramTypes, paramSlots);
            ma.invokestatic("cljl/TypeFactory", "dispatchMethod",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
            ma.emitReturnUnboxed(retType);
            addCodeMethod(0x0001, mname, desc, Math.max(8, paramTypes.length + 6), nextSlot, ma.toBytes());
        }

        void emitProxyMappingsAccessors() {
            MethodAssembler get = new MethodAssembler(this);
            get.aload(0);
            get.getfield(className, "__mappings", "Lcljl/lang/PersistentHashMap;");
            get.areturn();
            addCodeMethod(0x0001, "__getMappings", "()Lcljl/lang/PersistentHashMap;", 2, 1, get.toBytes());

            MethodAssembler set = new MethodAssembler(this);
            set.aload(0);
            set.aload(1);
            set.putfield(className, "__mappings", "Lcljl/lang/PersistentHashMap;");
            set.vreturn();
            addCodeMethod(0x0001, "__setMappings", "(Lcljl/lang/PersistentHashMap;)V", 2, 2, set.toBytes());
        }

        void emitProxyCtor(String superInternal, Class<?>[] paramTypes) {
            String desc = methodDesc(void.class, paramTypes);
            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            int slot = 1;
            for (Class<?> p : paramTypes) {
                ma.loadTyped(p, slot);
                slot += (p == long.class || p == double.class) ? 2 : 1;
            }
            ma.invokespecial(superInternal, "<init>", desc);
            ma.vreturn();
            addCodeMethod(0x0001, "<init>", desc, Math.max(4, slot + 1), slot, ma.toBytes());
        }

        void emitProxyOverrideMethod(
                String mname, Class<?> retType, Class<?>[] paramTypes, boolean hasSuper, String superOwnerInternal) {
            String desc = methodDesc(retType, paramTypes);
            MethodAssembler ma = new MethodAssembler(this);
            int nextSlot = 1;
            int[] paramSlots = new int[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramSlots[i] = nextSlot;
                nextSlot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
            }
            int fnLocal = nextSlot++;
            ma.aload(0);
            ma.ldcString(mname);
            ma.invokestatic("cljl/TypeFactory", "lookupProxyFn",
                    "(Lcljl/TypeFactory$ProxyObject;Ljava/lang/String;)Lcljl/Fn;");
            ma.astore(fnLocal);
            ma.aload(fnLocal);
            int notFoundJump = ma.ifNullPlaceholder();

            ma.aload(fnLocal);
            ma.aload(0);
            ma.emitBoxedArray(paramTypes, paramSlots);
            ma.invokestatic("cljl/TypeFactory", "invokeProxyFn",
                    "(Lcljl/Fn;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
            ma.emitReturnUnboxed(retType);

            ma.patchBranchHere(notFoundJump);
            if (hasSuper) {
                ma.aload(0);
                for (int i = 0; i < paramTypes.length; i++) {
                    ma.loadTyped(paramTypes[i], paramSlots[i]);
                }
                ma.invokespecial(superOwnerInternal, mname, desc);
                ma.emitTypedReturn(retType);
            } else {
                ma.newObj("java/lang/UnsupportedOperationException");
                ma.dup();
                ma.ldcString("Method " + mname + " not implemented in proxy");
                ma.invokespecial("java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V");
                ma.athrow();
            }
            addCodeMethod(0x0001, mname, desc, Math.max(8, paramTypes.length + 6), nextSlot, ma.toBytes());
        }

        void emitProxySuperBridge(String mname, Class<?> retType, Class<?>[] paramTypes, String superOwnerInternal) {
            String desc = methodDesc(retType, paramTypes);
            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            int slot = 1;
            for (Class<?> p : paramTypes) {
                ma.loadTyped(p, slot);
                slot += (p == long.class || p == double.class) ? 2 : 1;
            }
            ma.invokespecial(superOwnerInternal, mname, desc);
            ma.emitTypedReturn(retType);
            addCodeMethod(0x0011, "__super_" + mname, desc, Math.max(4, slot + 1), slot, ma.toBytes());
        }

        byte[] toByteArray() {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
                DataOutputStream out = new DataOutputStream(baos);
                out.writeInt(0xCAFEBABE);
                out.writeShort(0);
                out.writeShort(49);
                out.writeShort(cpCount);
                out.write(cpBuf.toByteArray());
                out.writeShort(accessFlags);
                out.writeShort(thisIdx);
                out.writeShort(superIdx);
                out.writeShort(ifaceIdxs.length);
                for (int idx : ifaceIdxs) out.writeShort(idx);
                out.writeShort(fields.size());
                for (byte[] f : fields) out.write(f);
                out.writeShort(methods.size());
                for (byte[] m : methods) out.write(m);
                out.writeShort(0);
                out.flush();
                return baos.toByteArray();
            } catch (IOException e) { throw new RuntimeException(e); }
        }
    }

    private static final class MethodAssembler {
        final ClassWriter cw;
        final ByteArrayOutputStream code = new ByteArrayOutputStream(128);

        MethodAssembler(ClassWriter cw) { this.cw = cw; }

        void iconst(int v) {
            if (v == -1) code.write(0x02);
            else if (v >= 0 && v <= 5) code.write(0x03 + v);
            else if (v >= -128 && v <= 127) { code.write(0x10); code.write(v & 0xff); }
            else { code.write(0x11); code.write((v >>> 8) & 0xff); code.write(v & 0xff); }
        }
        void aconstNull() { code.write(0x01); }
        void dup() { code.write(0x59); }
        void pop() { code.write(0x57); }
        void aload(int slot) {
            if (slot <= 3) code.write(0x2a + slot);
            else { code.write(0x19); code.write(slot); }
        }
        void iload(int slot) {
            if (slot <= 3) code.write(0x1a + slot);
            else { code.write(0x15); code.write(slot); }
        }
        void astore(int slot) {
            if (slot <= 3) code.write(0x4b + slot);
            else { code.write(0x3a); code.write(slot); }
        }
        void aaload() { code.write(0x32); }
        void aastore() { code.write(0x53); }
        void anewarray(String internalName) {
            int ci = cw.addClass(internalName);
            code.write(0xbd);
            code.write((ci >>> 8) & 0xff); code.write(ci & 0xff);
        }
        void ldcString(String s) {
            int idx = cw.addString(s);
            if (idx < 256) { code.write(0x12); code.write(idx); }
            else { code.write(0x13); code.write((idx >>> 8) & 0xff); code.write(idx & 0xff); }
        }
        void newObj(String internalName) {
            int ci = cw.addClass(internalName);
            code.write(0xbb);
            code.write((ci >>> 8) & 0xff); code.write(ci & 0xff);
        }
        void checkcast(String internalName) {
            int ci = cw.addClass(internalName);
            code.write(0xc0);
            code.write((ci >>> 8) & 0xff); code.write(ci & 0xff);
        }
        void getfield(String owner, String name, String desc) { emitRef(0xb4, 9, owner, name, desc); }
        void putfield(String owner, String name, String desc) { emitRef(0xb5, 9, owner, name, desc); }
        void getstatic(String owner, String name, String desc) { emitRef(0xb2, 9, owner, name, desc); }
        void putstatic(String owner, String name, String desc) { emitRef(0xb3, 9, owner, name, desc); }
        void invokevirtual(String owner, String name, String desc) { emitRef(0xb6, 10, owner, name, desc); }
        void invokespecial(String owner, String name, String desc) { emitRef(0xb7, 10, owner, name, desc); }
        void invokestatic(String owner, String name, String desc) { emitRef(0xb8, 10, owner, name, desc); }
        void emitRef(int op, int tag, String owner, String name, String desc) {
            int ri = cw.addRef(tag, owner, name, desc);
            code.write(op);
            code.write((ri >>> 8) & 0xff); code.write(ri & 0xff);
        }
        int ifAcmpNePlaceholder() { return branchPlaceholder(0xa6); }
        int ifIcmpNePlaceholder() { return branchPlaceholder(0xa0); }
        int ifNullPlaceholder() { return branchPlaceholder(0xc6); }
        private int branchPlaceholder(int op) {
            int pos = code.size();
            code.write(op);
            code.write(0); code.write(0);
            return pos;
        }
        private final List<int[]> patchList = new ArrayList<>();
        void patchBranchHere(int opPos) {
            patchList.add(new int[]{opPos, code.size()});
        }
        void vreturn() { code.write(0xb1); }
        void ireturn() { code.write(0xac); }
        void areturn() { code.write(0xb0); }
        void athrow() { code.write(0xbf); }

        void loadTyped(Class<?> t, int slot) {
            if (t == boolean.class || t == byte.class || t == short.class || t == char.class || t == int.class) iload(slot);
            else if (t == long.class) { code.write(0x16); code.write(slot); }
            else if (t == float.class) { code.write(0x17); code.write(slot); }
            else if (t == double.class) { code.write(0x18); code.write(slot); }
            else aload(slot);
        }

        void emitBoxedArray(Class<?>[] paramTypes, int[] slots) {
            iconst(paramTypes.length);
            anewarray("java/lang/Object");
            for (int i = 0; i < paramTypes.length; i++) {
                dup();
                iconst(i);
                Class<?> p = paramTypes[i];
                loadTyped(p, slots[i]);
                if (p == boolean.class) invokestatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
                else if (p == byte.class) invokestatic("java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;");
                else if (p == short.class) invokestatic("java/lang/Short", "valueOf", "(S)Ljava/lang/Short;");
                else if (p == char.class) invokestatic("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;");
                else if (p == int.class) invokestatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
                else if (p == long.class) invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
                else if (p == float.class) invokestatic("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");
                else if (p == double.class) invokestatic("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
                aastore();
            }
        }

        void emitReturnUnboxed(Class<?> retType) {
            if (retType == void.class) { pop(); vreturn(); return; }
            if (!retType.isPrimitive()) {
                if (retType != Object.class) checkcast(retType.getName().replace('.', '/'));
                areturn();
                return;
            }
            if (retType == boolean.class) {
                checkcast("java/lang/Boolean");
                invokevirtual("java/lang/Boolean", "booleanValue", "()Z");
                ireturn();
            } else if (retType == char.class) {
                checkcast("java/lang/Character");
                invokevirtual("java/lang/Character", "charValue", "()C");
                ireturn();
            } else if (retType == byte.class) {
                checkcast("java/lang/Number");
                invokevirtual("java/lang/Number", "byteValue", "()B");
                ireturn();
            } else if (retType == short.class) {
                checkcast("java/lang/Number");
                invokevirtual("java/lang/Number", "shortValue", "()S");
                ireturn();
            } else if (retType == int.class) {
                checkcast("java/lang/Number");
                invokevirtual("java/lang/Number", "intValue", "()I");
                ireturn();
            } else if (retType == long.class) {
                checkcast("java/lang/Number");
                invokevirtual("java/lang/Number", "longValue", "()J");
                code.write(0xad); // lreturn
            } else if (retType == float.class) {
                checkcast("java/lang/Number");
                invokevirtual("java/lang/Number", "floatValue", "()F");
                code.write(0xae); // freturn
            } else if (retType == double.class) {
                checkcast("java/lang/Number");
                invokevirtual("java/lang/Number", "doubleValue", "()D");
                code.write(0xaf); // dreturn
            }
        }

        void emitTypedReturn(Class<?> retType) {
            if (retType == void.class) vreturn();
            else if (retType == long.class) code.write(0xad);
            else if (retType == float.class) code.write(0xae);
            else if (retType == double.class) code.write(0xaf);
            else if (retType.isPrimitive()) ireturn();
            else areturn();
        }

        byte[] toBytes() {
            byte[] b = code.toByteArray();
            for (int[] p : patchList) {
                int rel = p[1] - p[0];
                b[p[0] + 1] = (byte) ((rel >>> 8) & 0xff);
                b[p[0] + 2] = (byte) (rel & 0xff);
            }
            return b;
        }
    }
}
