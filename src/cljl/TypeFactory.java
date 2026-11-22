package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.invoke.VarHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dynamic JVM class/interface/record/type/reify/proxy factory for Clojol.
 * Generates v49.0 JVM classfiles in memory with zero external dependencies.
 */
public final class TypeFactory {

    private static final DynamicClassLoader LOADER = new DynamicClassLoader();
    private static final AtomicInteger PROXY_ID = new AtomicInteger(0);
    private static final AtomicInteger PROTO_FN_ID = new AtomicInteger(0);
    private static final AtomicInteger PROTO_IFACE_ID = new AtomicInteger(0);
    private static final AtomicInteger PROTO_HOLDER_ID = new AtomicInteger(0);
    private static final ConcurrentHashMap<Class<?>, String> RECORD_PM_HOLDER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Fn> PENDING_PM_FNS = new ConcurrentHashMap<>();

    public static Fn takePendingProtoFn(String key) {
        return PENDING_PM_FNS.remove(key);
    }
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Fn>> CLASS_METHODS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<List<Class<?>>, Class<?>> PROXY_CLASS_CACHE =
            new ConcurrentHashMap<>();

    private TypeFactory() {}

    static Class<?> findDynamicClass(String binaryName) {
        return LOADER.findAlreadyLoaded(binaryName);
    }

    public static synchronized Class<?> defineRawClass(String binaryName, byte[] bytes) {
        Class<?> existing = LOADER.findAlreadyLoaded(binaryName);
        if (existing != null) return existing;
        Class<?> defined = LOADER.define(binaryName, bytes);
        Interop.invalidateClassCache(binaryName);
        return defined;
    }

    public static final class DynamicClassLoader extends java.net.URLClassLoader {
        public DynamicClassLoader() {
            super(new java.net.URL[0], TypeFactory.class.getClassLoader());
        }

        public DynamicClassLoader(ClassLoader parent) {
            super(new java.net.URL[0], parent);
        }

        @Override
        public void addURL(java.net.URL url) {
            super.addURL(url);
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }

        Class<?> findAlreadyLoaded(String name) {
            return findLoadedClass(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            try {
                return super.findClass(name);
            } catch (ClassNotFoundException ignored) {}
            ClassLoader rt = RT.baseLoader();
            if (rt != null && rt != this && rt != getParent()) {
                return rt.loadClass(name);
            }
            throw new ClassNotFoundException(name);
        }
    }

    public interface ProxyObject {
        PersistentHashMap __getMappings();
        void __setMappings(PersistentHashMap m);
    }

    public record MethodSig(String name, Class<?> returnType, Class<?>[] paramTypes) {}

    public static void setClassMethods(String binaryName, Map<String, Fn> methodImpls) {
        ConcurrentHashMap<String, Fn> cm = new ConcurrentHashMap<>();
        for (Map.Entry<String, Fn> e : methodImpls.entrySet()) {
            if (e.getValue() != null) cm.put(e.getKey(), e.getValue());
        }
        CLASS_METHODS.put(binaryName, cm);
    }

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

    public static String mangleProtoMethod(String name) {
        StringBuilder sb = new StringBuilder(name.length() + 4);
        sb.append("__p_");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_').append(Integer.toHexString(c)).append('_');
            }
        }
        return sb.toString();
    }

    private static final ConcurrentHashMap<String, String> PROTO_MANGLED_TO_NAME = new ConcurrentHashMap<>();

    private static final Set<String> RESERVED_PROTO_IFACE_NAMES = Set.of(
            "toString", "hashCode", "equals", "getClass", "notify", "notifyAll", "wait", "clone", "finalize",
            "meta", "withMeta", "valAt", "assoc", "without", "count", "seq", "empty", "cons", "conj", "equiv",
            "containsKey", "entryAt", "get", "size", "isEmpty", "keySet", "values", "entrySet",
            "put", "remove", "putAll", "clear", "containsValue", "iterator", "invoke", "applyTo",
            "__protocolFn", "__satisfies", "__getMappings", "__setMappings"
    );

    private static String standardProtoMethodName(String origName) {
        if (origName == null || origName.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(origName.length());
        for (int i = 0; i < origName.length(); i++) {
            char c = origName.charAt(i);
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
                default -> {
                    if (Character.isJavaIdentifierPart(c) && (i > 0 || Character.isJavaIdentifierStart(c))) {
                        sb.append(c);
                    } else {
                        sb.append('_').append(Integer.toHexString(c)).append('_');
                    }
                }
            }
        }
        String munged = sb.toString();
        if (munged.isEmpty() || RESERVED_PROTO_IFACE_NAMES.contains(munged)) return null;
        return munged;
    }

    public static synchronized Class<?> defineProtocolInterface(String desiredBinaryName, Map<String, int[]> methodArities) {
        if (desiredBinaryName == null || desiredBinaryName.isEmpty()) return null;
        String binaryName = desiredBinaryName;
        if (LOADER.findAlreadyLoaded(binaryName) != null) {
            binaryName = desiredBinaryName + "$" + PROTO_IFACE_ID.incrementAndGet();
        }
        String internalName = binaryName.replace('.', '/');

        ClassWriter cw = new ClassWriter(internalName, "java/lang/Object", new String[0], 0x0601); // PUBLIC | INTERFACE | ABSTRACT
        if (methodArities != null) {
            Set<String> emittedSigs = new HashSet<>();
            for (Map.Entry<String, int[]> e : methodArities.entrySet()) {
                String origName = e.getKey();
                String jm = mangleProtoMethod(origName);
                String stdName = standardProtoMethodName(origName);
                PROTO_MANGLED_TO_NAME.put(jm, origName);
                if (stdName != null && !stdName.equals(jm)) {
                    PROTO_MANGLED_TO_NAME.put(stdName, origName);
                }
                for (int arity : e.getValue()) {
                    if (arity < 1 || arity > 4) continue;
                    StringBuilder desc = new StringBuilder("(");
                    for (int i = 1; i < arity; i++) desc.append("Ljava/lang/Object;");
                    desc.append(")Ljava/lang/Object;");
                    String descStr = desc.toString();
                    if (emittedSigs.add(jm + descStr)) {
                        cw.addAbstractMethod(jm, descStr);
                    }
                    if (stdName != null && !stdName.equals(jm) && emittedSigs.add(stdName + descStr)) {
                        cw.addAbstractMethod(stdName, descStr);
                    }
                    if (arity == 2) {
                        if (emittedSigs.add(jm + "_J(J)J")) {
                            cw.addAbstractMethod(jm + "_J", "(J)J");
                        }
                        if (emittedSigs.add(jm + "_JO(J)Ljava/lang/Object;")) {
                            cw.addAbstractMethod(jm + "_JO", "(J)Ljava/lang/Object;");
                        }
                        PROTO_MANGLED_TO_NAME.put(jm + "_J", origName);
                        PROTO_MANGLED_TO_NAME.put(jm + "_JO", origName);
                    }
                }
            }
        }
        byte[] bytes = cw.toByteArray();
        GenClass.writeClassFileIfCompiling(binaryName, bytes);
        Class<?> iface = LOADER.define(binaryName, bytes);
        Interop.registerClass(desiredBinaryName, iface);
        if (!desiredBinaryName.equals(binaryName)) {
            Interop.registerClass(binaryName, iface);
        }
        return iface;
    }

    private static boolean containsArity(int[] arities, int a) {
        if (arities == null) return false;
        for (int v : arities) if (v == a) return true;
        return false;
    }

    public static Protocol.MethodFn defineProtocolMethodFn(Var protoVar, Keyword methodKey, String name) {
        return defineProtocolMethodFn(protoVar, methodKey, name, false);
    }

    public static Protocol.MethodFn defineProtocolMethodFn(Var protoVar, Keyword methodKey, String name, boolean picOnly) {
        int id = PROTO_FN_ID.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 32; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String safeName = sb.length() > 0 ? sb.toString() : "method";
        String binaryName = "cljl.proto." + safeName + "$" + id;
        String internalName = binaryName.replace('.', '/');
        String superInternal = "cljl/Protocol$MethodFn";

        Protocol proto = (!picOnly && protoVar != null && protoVar.getRawRoot() instanceof Protocol p && !p.hasInterfaceOverrides) ? p : null;
        String ifaceInternal = (proto != null && proto.onInterface != null)
                ? proto.onInterface.getName().replace('.', '/') : null;
        int[] ifArities = (proto != null && proto.interfaceMethodArities != null)
                ? proto.interfaceMethodArities.get(name) : null;
        String jm = mangleProtoMethod(name);

        ClassWriter cw = new ClassWriter(internalName, superInternal, new String[0], 0x0011); // PUBLIC | FINAL
        cw.emitProtocolMethodCtor(superInternal);
        cw.emitProtocolMethodDispatch("invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, false, false,
                containsArity(ifArities, 1) ? ifaceInternal : null, jm, "()Ljava/lang/Object;");
        cw.emitProtocolMethodDispatch("invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2, false, false,
                containsArity(ifArities, 2) ? ifaceInternal : null, jm, "(Ljava/lang/Object;)Ljava/lang/Object;");
        cw.emitProtocolMethodDispatch("invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, false, false,
                containsArity(ifArities, 3) ? ifaceInternal : null, jm, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        cw.emitProtocolMethodDispatch("invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, false, false,
                containsArity(ifArities, 4) ? ifaceInternal : null, jm, "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
        cw.emitProtocolMethodDispatch("invokePrim_OJ_J", "(Ljava/lang/Object;J)J", 2, true, true,
                containsArity(ifArities, 2) ? ifaceInternal : null, jm + "_J", "(J)J");
        cw.emitProtocolMethodDispatch("invokePrim_OJ_O", "(Ljava/lang/Object;J)Ljava/lang/Object;", 2, true, false,
                containsArity(ifArities, 2) ? ifaceInternal : null, jm + "_JO", "(J)Ljava/lang/Object;");

        try {
            Class<?> cls = LOADER.define(binaryName, cw.toByteArray());
            Constructor<?> ctor = cls.getConstructor(Var.class, Keyword.class, String.class);
            return (Protocol.MethodFn) ctor.newInstance(protoVar, methodKey, name);
        } catch (Throwable t) {
            return new Protocol.MethodFn(protoVar, methodKey, name);
        }
    }

    public static synchronized Class<?> defineInterface(String binaryName, List<MethodSig> methods) {
        String internalName = binaryName.replace('.', '/');
        ClassWriter cw = new ClassWriter(internalName, "java/lang/Object", new String[0], 0x0601); // PUBLIC | INTERFACE | ABSTRACT
        for (MethodSig m : methods) {
            cw.addAbstractMethod(m.name(), methodDesc(m.returnType(), m.paramTypes()));
        }
        byte[] bytes = cw.toByteArray();
        GenClass.writeClassFileIfCompiling(binaryName, bytes);
        Class<?> cls = LOADER.define(binaryName, bytes);
        Interop.registerClass(binaryName, cls);
        return cls;
    }

    public static synchronized Class<?> defineRecord(
            String binaryName,
            String[] fieldNames,
            Class<?>[] interfaces,
            Map<String, Fn> methodImpls) {
        return defineRecord(binaryName, fieldNames, null, interfaces, methodImpls, Collections.emptyList());
    }

    public static synchronized Class<?> defineRecord(
            String binaryName,
            String[] fieldNames,
            char[] fieldTypes,
            Class<?>[] interfaces,
            Map<String, Fn> methodImpls,
            List<Protocol> directProtos) {
        char[] ftypes = new char[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            ftypes[i] = (fieldTypes != null && i < fieldTypes.length) ? fieldTypes[i] : 'O';
        }
        String internalName = binaryName.replace('.', '/');
        String superInternal = "cljl/lang/PersistentRecord";
        List<String> ifaceList = new ArrayList<>(interfaces.length + directProtos.size());
        Set<String> ifaceSeen = new HashSet<>();
        for (Class<?> iface : interfaces) {
            String iname = iface.getName().replace('.', '/');
            if (ifaceSeen.add(iname)) ifaceList.add(iname);
        }
        List<Protocol> activeDirectProtos = new ArrayList<>();
        Set<String> protoMethodNames = new HashSet<>();
        for (Protocol p : directProtos) {
            if (p.onInterface == null || p.interfaceMethodArities == null) continue;
            boolean conflict = false;
            for (String mn : p.interfaceMethodArities.keySet()) {
                if (protoMethodNames.contains(mn)) { conflict = true; break; }
            }
            if (conflict) continue;
            String iname = p.onInterface.getName().replace('.', '/');
            if (ifaceSeen.add(iname)) {
                ifaceList.add(iname);
                activeDirectProtos.add(p);
                protoMethodNames.addAll(p.interfaceMethodArities.keySet());
            }
        }
        String[] ifaceInternals = ifaceList.toArray(new String[0]);

        ConcurrentHashMap<String, Fn> cm = new ConcurrentHashMap<>();
        for (Map.Entry<String, Fn> e : methodImpls.entrySet()) {
            if (e.getValue() != null) cm.put(e.getKey(), e.getValue());
        }
        CLASS_METHODS.put(binaryName, cm);

        ClassWriter cw = new ClassWriter(internalName, superInternal, ifaceInternals, 0x0011); // PUBLIC | FINAL

        // Fields: public final long/double/Object <field>;
        for (int i = 0; i < fieldNames.length; i++) {
            cw.addField(0x0011, fieldNames[i], ClassWriter.fieldDescFor(ftypes[i]));
        }
        // Static fields: KEYS ([Ljava/lang/Object;) initialized lazily via <clinit>
        cw.addField(0x001a, "__KEYS", "[Ljava/lang/Object;"); // PRIVATE | STATIC | FINAL
        cw.emitRecordClinit(fieldNames);

        // Short ctor: (Object f0, ...) -> this(f0, ..., null, null)
        cw.emitRecordShortCtor(fieldNames);
        // Full ctor: (Object f0, ..., PersistentHashMap meta, PersistentHashMap ext)
        cw.emitRecordFullCtor(fieldNames, ftypes);
        // __baseKeys() -> __KEYS
        cw.emitBaseKeysMethod();
        // __fieldIndex(Object key)
        cw.emitFieldIndexMethod(fieldNames);
        // __fieldValue(int idx)
        cw.emitFieldValueMethod(fieldNames, ftypes);
        // __withField(int idx, Object val)
        cw.emitWithFieldMethod(fieldNames, ftypes);
        // __withExt(PersistentHashMap meta, PersistentHashMap ext)
        cw.emitWithExtMethod(fieldNames, ftypes);
        // Fast direct valAt(key) and valAt(key, notFound) bypassing __fieldIndex/__fieldValue virtual calls
        cw.emitRecordValAtMethods(fieldNames, ftypes);

        // Implement direct protocol interface methods backed by static final Fn fields in a companion holder class
        // so HotSpot C2 constant-folds the Fn instance and inlines the JIT body directly into callers.
        String pmHolderBinary = null;
        if (!activeDirectProtos.isEmpty()) {
            pmHolderBinary = binaryName + "$__pm$" + PROTO_HOLDER_ID.incrementAndGet();
            String pmHolderInternal = pmHolderBinary.replace('.', '/');
            Set<String> emittedRecProtos = new HashSet<>();
            for (Protocol p : activeDirectProtos) {
                for (Map.Entry<String, int[]> e : p.interfaceMethodArities.entrySet()) {
                    String origName = e.getKey();
                    String jm = mangleProtoMethod(origName);
                    String stdName = standardProtoMethodName(origName);
                    String fnField = "__pm_" + jm;
                    for (int arity : e.getValue()) {
                        if (arity < 1 || arity > 4) continue;
                        if (emittedRecProtos.add(jm + "/" + arity)) {
                            cw.emitRecordDirectProtoBridge(pmHolderInternal, fnField, jm, arity, false, false);
                        }
                        if (stdName != null && !stdName.equals(jm) && emittedRecProtos.add(stdName + "/" + arity)) {
                            cw.emitRecordDirectProtoBridge(pmHolderInternal, fnField, stdName, arity, false, false);
                        }
                        if (arity == 2) {
                            if (emittedRecProtos.add(jm + "_J/2")) {
                                cw.emitRecordDirectProtoBridge(pmHolderInternal, fnField, jm + "_J", 2, true, true);
                            }
                            if (emittedRecProtos.add(jm + "_JO/2")) {
                                cw.emitRecordDirectProtoBridge(pmHolderInternal, fnField, jm + "_JO", 2, true, false);
                            }
                        }
                    }
                }
            }
        }

        // Implement Java interface methods delegating to TypeFactory.dispatchMethod
        emitInterfaceBridges(cw, binaryName, interfaces, methodImpls);

        byte[] bytes = cw.toByteArray();
        GenClass.writeClassFileIfCompiling(binaryName, bytes);
        Class<?> cls = LOADER.define(binaryName, bytes);
        if (pmHolderBinary != null) {
            RECORD_PM_HOLDER.put(cls, pmHolderBinary);
        }
        Interop.registerClass(binaryName, cls);
        return cls;
    }

    public static void bindRecordProtocolMethods(Class<?> recCls, Map<Protocol, Map<Keyword, Fn>> protoImpls) {
        String pmHolderBinary = RECORD_PM_HOLDER.remove(recCls);
        if (pmHolderBinary != null) {
            Map<String, Fn> fieldsToInit = new LinkedHashMap<>();
            for (Map.Entry<Protocol, Map<Keyword, Fn>> pe : protoImpls.entrySet()) {
                Protocol p = pe.getKey();
                if (p.onInterface != null && p.onInterface.isAssignableFrom(recCls)) {
                    for (Map.Entry<Keyword, Fn> me : pe.getValue().entrySet()) {
                        Fn impl = me.getValue();
                        if (impl instanceof Compiler.CompiledFn cf) {
                            impl = cf.ensureJit();
                        }
                        String fnField = "__pm_" + mangleProtoMethod(me.getKey().getName());
                        fieldsToInit.putIfAbsent(fnField, impl);
                    }
                }
            }
            String holderInternal = pmHolderBinary.replace('.', '/');
            ClassWriter hcw = new ClassWriter(holderInternal, "java/lang/Object", new String[0], 0x0011);
            MethodAssembler clinit = new MethodAssembler(hcw);
            for (Map.Entry<String, Fn> e : fieldsToInit.entrySet()) {
                String fnField = e.getKey();
                hcw.addField(0x0019, fnField, "Lcljl/Fn;"); // PUBLIC | STATIC | FINAL
                String key = pmHolderBinary + "." + fnField;
                PENDING_PM_FNS.put(key, e.getValue());
                clinit.ldcString(key);
                clinit.invokestatic("cljl/TypeFactory", "takePendingProtoFn", "(Ljava/lang/String;)Lcljl/Fn;");
                clinit.putstatic(holderInternal, fnField, "Lcljl/Fn;");
            }
            clinit.vreturn();
            hcw.addCodeMethod(0x0008, "<clinit>", "()V", 2, 0, clinit.toBytes());
            Class<?> holderCls = LOADER.define(pmHolderBinary, hcw.toByteArray());
            // Force <clinit> eagerly so PENDING_PM_FNS entries are consumed and static final fields are published
            try {
                Class.forName(pmHolderBinary, true, LOADER);
            } catch (ClassNotFoundException e) {
                throw RT.sneakyThrow(e);
            }
            if (holderCls == null) throw new IllegalStateException("Failed to define holder " + pmHolderBinary);
        }
        for (Map.Entry<Protocol, Map<Keyword, Fn>> pe : protoImpls.entrySet()) {
            Protocol p = pe.getKey();
            if (p.onInterface != null && p.onInterface.isAssignableFrom(recCls)) {
                p.addDirectImpl(recCls, pe.getValue());
            } else {
                p.addImpl(recCls, pe.getValue());
            }
        }
    }

    public static synchronized Class<?> defineType(
            String binaryName,
            String[] fieldNames,
            boolean[] mutable,
            Class<?>[] interfaces,
            Map<String, Fn> methodImpls) {
        String internalName = binaryName.replace('.', '/');
        boolean hasIType = false;
        for (Class<?> iface : interfaces) {
            if (iface == IType.class) { hasIType = true; break; }
        }
        String[] ifaceInternals = new String[interfaces.length + (hasIType ? 0 : 1)];
        for (int i = 0; i < interfaces.length; i++) ifaceInternals[i] = interfaces[i].getName().replace('.', '/');
        if (!hasIType) ifaceInternals[interfaces.length] = "cljl/lang/IType";

        ConcurrentHashMap<String, Fn> cm = new ConcurrentHashMap<>();
        for (Map.Entry<String, Fn> e : methodImpls.entrySet()) {
            if (e.getValue() != null) cm.put(e.getKey(), e.getValue());
        }
        CLASS_METHODS.put(binaryName, cm);

        ClassWriter cw = new ClassWriter(internalName, "java/lang/Object", ifaceInternals, 0x0011);
        for (int i = 0; i < fieldNames.length; i++) {
            int flags = mutable[i] ? 0x0041 : 0x0011; // PUBLIC | VOLATILE vs PUBLIC | FINAL
            cw.addField(flags, fieldNames[i], "Ljava/lang/Object;");
        }
        cw.emitSimpleCtor(fieldNames);
        emitInterfaceBridges(cw, binaryName, interfaces, methodImpls);

        byte[] bytes = cw.toByteArray();
        GenClass.writeClassFileIfCompiling(binaryName, bytes);
        Class<?> cls = LOADER.define(binaryName, bytes);
        Interop.registerClass(binaryName, cls);
        return cls;
    }

    private static void emitInterfaceBridges(
            ClassWriter cw, String binaryName, Class<?>[] interfaces, Map<String, Fn> methodImpls) {
        Set<String> emitted = new HashSet<>();
        for (Class<?> iface : interfaces) {
            boolean isProtoIface = Protocol.findByInterface(iface) != null;
            for (Method m : iface.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                String desc = methodDesc(m.getReturnType(), m.getParameterTypes());
                String sigKey = m.getName() + desc;
                if (!emitted.add(sigKey)) continue;
                if (isProtoIface) {
                    String origName = PROTO_MANGLED_TO_NAME.getOrDefault(m.getName(), m.getName());
                    cw.emitDispatchBridgeWithKey(binaryName, m.getName(), origName, m.getReturnType(), m.getParameterTypes());
                } else {
                    cw.emitDispatchBridge(binaryName, m.getName(), m.getReturnType(), m.getParameterTypes());
                }
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
        allIfaces.add(IObj.class);
        allIfaces.add(Protocol.Dispatchable.class);
        for (Class<?> c : interfaces) {
            if (c != IObj.class && c != IMeta.class && c != Protocol.Dispatchable.class) {
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
                if (impl == null) {
                    String orig = PROTO_MANGLED_TO_NAME.get(mname);
                    if (orig != null) impl = javaMethodImpls.get(orig);
                }
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
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
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
        cw.addField(0x00c2, "__mappings", "Lcljl/lang/PersistentHashMap;"); // PRIVATE | VOLATILE | TRANSIENT
        cw.emitProxyMappingsAccessors();
        cw.emitProxySerializationGuards();

        // Forward constructors from superclass
        for (Constructor<?> ctor : superClass.getDeclaredConstructors()) {
            if (Modifier.isPrivate(ctor.getModifiers())) continue;
            cw.emitProxyCtor(superInternal, ctor.getParameterTypes());
        }

        // Record final methods from superclass hierarchy so interfaces never override them
        Set<String> seen = new HashSet<>();
        for (Class<?> cur = superClass; cur != null; cur = cur.getSuperclass()) {
            for (Method m : cur.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isFinal(mod) && !Modifier.isStatic(mod) && !Modifier.isPrivate(mod)) {
                    seen.add(m.getName() + methodDesc(m.getReturnType(), m.getParameterTypes()));
                }
            }
        }

        // Override overridable methods
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

    public static void checkProxySerializable(Object proxy) throws java.io.NotSerializableException {
        if (!Boolean.getBoolean("clojure.serializable.fns")) {
            throw new java.io.NotSerializableException(proxy.getClass().getName());
        }
    }

    public static PersistentHashMap proxyMappings(Object proxy) {
        return ((ProxyObject) proxy).__getMappings();
    }

    public static String proxyName(Class<?> superClass, Class<?>[] interfaces) {
        StringBuilder sb = new StringBuilder("cljl.proxy.");
        sb.append((superClass != null ? superClass : Object.class).getName());
        if (interfaces != null) {
            for (Class<?> c : interfaces) {
                sb.append('$').append(c.getName());
            }
        }
        return sb.toString();
    }

    public static Object proxyCallWithSuper(Fn call, Object proxy, String methName) {
        ProxyObject p = (ProxyObject) proxy;
        PersistentHashMap orig = p.__getMappings();
        PersistentHashMap temp = orig != null ? orig.without(methName).without(Keyword.intern(methName)) : PersistentHashMap.EMPTY;
        p.__setMappings(temp);
        try {
            return call.invoke();
        } finally {
            p.__setMappings(orig);
        }
    }

    public static PersistentVector methodSig(Method m) {
        return PersistentVector.createOwning(
                m.getName(),
                PersistentVector.createOwning((Object[]) m.getParameterTypes()),
                m.getReturnType());
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
        final Map<Integer, Integer> intMap = new HashMap<>();
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

        int addInteger(int v) {
            return intMap.computeIfAbsent(v, k -> {
                cpBuf.write(3);
                cpBuf.write((k >>> 24) & 0xff);
                cpBuf.write((k >>> 16) & 0xff);
                cpBuf.write((k >>> 8) & 0xff);
                cpBuf.write(k & 0xff);
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

        private static String fieldDescFor(char ft) {
            if (ft == 'J') return "J";
            if (ft == 'D') return "D";
            return "Ljava/lang/Object;";
        }

        private void emitLoadBoxedField(MethodAssembler ma, String fname, char ft) {
            ma.aload(0);
            if (ft == 'J') {
                ma.getfield(className, fname, "J");
                ma.invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
            } else if (ft == 'D') {
                ma.getfield(className, fname, "D");
                ma.invokestatic("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
            } else {
                ma.getfield(className, fname, "Ljava/lang/Object;");
            }
        }

        void emitRecordFullCtor(String[] fieldNames, char[] ftypes) {
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
                if (ftypes[i] == 'J') {
                    ma.invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J");
                    ma.putfield(className, fieldNames[i], "J");
                } else if (ftypes[i] == 'D') {
                    ma.invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D");
                    ma.putfield(className, fieldNames[i], "D");
                } else {
                    ma.putfield(className, fieldNames[i], "Ljava/lang/Object;");
                }
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

        void emitFieldValueMethod(String[] fieldNames, char[] ftypes) {
            MethodAssembler ma = new MethodAssembler(this);
            for (int i = 0; i < fieldNames.length; i++) {
                ma.iload(1);
                ma.iconst(i);
                int nextJump = ma.ifIcmpNePlaceholder();
                emitLoadBoxedField(ma, fieldNames[i], ftypes[i]);
                ma.areturn();
                ma.patchBranchHere(nextJump);
            }
            ma.aconstNull();
            ma.areturn();
            addCodeMethod(0x0004, "__fieldValue", "(I)Ljava/lang/Object;", 4, 2, ma.toBytes());
        }

        void emitWithFieldMethod(String[] fieldNames, char[] ftypes) {
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
                    else emitLoadBoxedField(ma, fieldNames[j], ftypes[j]);
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
                    Math.max(6, fieldNames.length + 8), 3, ma.toBytes());
        }

        void emitWithExtMethod(String[] fieldNames, char[] ftypes) {
            StringBuilder fullDesc = new StringBuilder("(");
            for (String ignored : fieldNames) fullDesc.append("Ljava/lang/Object;");
            fullDesc.append("Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)V");

            MethodAssembler ma = new MethodAssembler(this);
            ma.newObj(className);
            ma.dup();
            for (int i = 0; i < fieldNames.length; i++) {
                emitLoadBoxedField(ma, fieldNames[i], ftypes[i]);
            }
            ma.aload(1);
            ma.aload(2);
            ma.invokespecial(className, "<init>", fullDesc.toString());
            ma.areturn();
            addCodeMethod(0x0004, "__withExt",
                    "(Lcljl/lang/PersistentHashMap;Lcljl/lang/PersistentHashMap;)Lcljl/lang/PersistentRecord;",
                    Math.max(6, fieldNames.length + 7), 3, ma.toBytes());
        }

        void emitRecordValAtMethods(String[] fieldNames, char[] ftypes) {
            // public Object valAt(Object key)
            MethodAssembler ma1 = new MethodAssembler(this);
            if (fieldNames.length > 0) {
                ma1.getstatic(className, "__KEYS", "[Ljava/lang/Object;");
                ma1.astore(2);
                for (int i = 0; i < fieldNames.length; i++) {
                    ma1.aload(2);
                    ma1.iconst(i);
                    ma1.aaload();
                    ma1.aload(1);
                    int nextJump = ma1.ifAcmpNePlaceholder();
                    emitLoadBoxedField(ma1, fieldNames[i], ftypes[i]);
                    ma1.areturn();
                    ma1.patchBranchHere(nextJump);
                }
            }
            ma1.aload(0);
            ma1.getfield(superName, "__extmap", "Lcljl/lang/PersistentHashMap;");
            ma1.aload(1);
            ma1.invokevirtual("cljl/lang/PersistentHashMap", "valAt", "(Ljava/lang/Object;)Ljava/lang/Object;");
            ma1.areturn();
            addCodeMethod(0x0001, "valAt", "(Ljava/lang/Object;)Ljava/lang/Object;", 4, 3, ma1.toBytes());

            // public Object valAt(Object key, Object notFound)
            MethodAssembler ma2 = new MethodAssembler(this);
            if (fieldNames.length > 0) {
                ma2.getstatic(className, "__KEYS", "[Ljava/lang/Object;");
                ma2.astore(3);
                for (int i = 0; i < fieldNames.length; i++) {
                    ma2.aload(3);
                    ma2.iconst(i);
                    ma2.aaload();
                    ma2.aload(1);
                    int nextJump = ma2.ifAcmpNePlaceholder();
                    emitLoadBoxedField(ma2, fieldNames[i], ftypes[i]);
                    ma2.areturn();
                    ma2.patchBranchHere(nextJump);
                }
            }
            ma2.aload(0);
            ma2.getfield(superName, "__extmap", "Lcljl/lang/PersistentHashMap;");
            ma2.aload(1);
            ma2.aload(2);
            ma2.invokevirtual("cljl/lang/PersistentHashMap", "valAt",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
            ma2.areturn();
            addCodeMethod(0x0001, "valAt", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 4, ma2.toBytes());
        }

        void emitProtocolMethodCtor(String superInternal) {
            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            ma.aload(1);
            ma.aload(2);
            ma.aload(3);
            ma.invokespecial(superInternal, "<init>", "(Lcljl/Var;Lcljl/Keyword;Ljava/lang/String;)V");
            ma.vreturn();
            addCodeMethod(0x0001, "<init>", "(Lcljl/Var;Lcljl/Keyword;Ljava/lang/String;)V", 4, 4, ma.toBytes());
        }

        void emitProtocolMethodCtorWithProto(String superInternal, String selfInternal) {
            MethodAssembler ma = new MethodAssembler(this);
            ma.aload(0);
            ma.aload(1);
            ma.aload(2);
            ma.aload(3);
            ma.invokespecial(superInternal, "<init>", "(Lcljl/Var;Lcljl/Keyword;Ljava/lang/String;)V");
            ma.aload(0);
            ma.aload(4);
            ma.putfield(selfInternal, "fastProto", "Lcljl/Protocol;");
            ma.vreturn();
            addCodeMethod(0x0001, "<init>", "(Lcljl/Var;Lcljl/Keyword;Ljava/lang/String;Lcljl/Protocol;)V", 4, 5, ma.toBytes());
        }

        void emitRecordDirectProtoBridge(String holderInternal, String fnField, String ifaceMethodName, int arity, boolean secondIsLong, boolean returnsLong) {
            String ifaceDesc;
            String fnName;
            String fnDesc;
            int maxLocals;
            if (secondIsLong) {
                ifaceDesc = returnsLong ? "(J)J" : "(J)Ljava/lang/Object;";
                fnName = returnsLong ? "invokePrim_OJ_J" : "invokePrim_OJ_O";
                fnDesc = returnsLong ? "(Ljava/lang/Object;J)J" : "(Ljava/lang/Object;J)Ljava/lang/Object;";
                maxLocals = 3;
            } else {
                StringBuilder id = new StringBuilder("(");
                for (int i = 1; i < arity; i++) id.append("Ljava/lang/Object;");
                id.append(")Ljava/lang/Object;");
                ifaceDesc = id.toString();
                fnName = "invoke";
                StringBuilder fd = new StringBuilder("(");
                for (int i = 0; i < arity; i++) fd.append("Ljava/lang/Object;");
                fd.append(")Ljava/lang/Object;");
                fnDesc = fd.toString();
                maxLocals = arity;
            }
            MethodAssembler ma = new MethodAssembler(this);
            ma.getstatic(holderInternal, fnField, "Lcljl/Fn;");
            ma.aload(0);
            if (secondIsLong) {
                ma.lload(1);
            } else {
                for (int i = 1; i < arity; i++) ma.aload(i);
            }
            ma.invokevirtual("cljl/Fn", fnName, fnDesc);
            if (returnsLong) ma.lreturn(); else ma.areturn();
            addCodeMethod(0x0001, ifaceMethodName, ifaceDesc, Math.max(4, arity + 2), maxLocals, ma.toBytes());
        }

        void emitProtocolMethodDispatch(
                String mname, String desc, int arity, boolean secondIsLong, boolean returnsLong,
                String ifaceInternal, String ifaceMethodName, String ifaceDesc) {
            int paramEndSlot = secondIsLong ? 4 : (1 + arity);
            if (ifaceInternal != null) {
                // Ultra-tiny (~18 bytes) hot method for direct interface dispatch; PIC lives out-of-line
                String picName = "pic_" + mname + "_" + arity;
                MethodAssembler fast = new MethodAssembler(this);
                fast.aload(1);
                fast.instanceofOp(ifaceInternal);
                int notInst = fast.ifEqPlaceholder();
                fast.aload(1);
                fast.checkcast(ifaceInternal);
                int ifaceArgWords;
                if (secondIsLong) {
                    fast.lload(2);
                    ifaceArgWords = 3;
                } else {
                    for (int i = 2; i <= arity; i++) fast.aload(i);
                    ifaceArgWords = arity;
                }
                fast.invokeinterface(ifaceInternal, ifaceMethodName, ifaceDesc, ifaceArgWords);
                if (returnsLong) fast.lreturn(); else fast.areturn();
                fast.patchBranchHere(notInst);
                fast.aload(0);
                emitProtoArgs(fast, arity, secondIsLong);
                fast.invokevirtual(className, picName, desc);
                if (returnsLong) fast.lreturn(); else fast.areturn();
                addCodeMethod(0x0001, mname, desc, Math.max(5, paramEndSlot + 1), paramEndSlot, fast.toBytes());

                emitProtocolPicBody(picName, mname, desc, arity, secondIsLong, returnsLong, 0x0002);
            } else {
                emitProtocolPicBody(mname, mname, desc, arity, secondIsLong, returnsLong, 0x0001);
            }
        }

        private void emitProtocolPicBody(
                String emitName, String callName, String desc, int arity, boolean secondIsLong, boolean returnsLong, int accessFlags) {
            int paramEndSlot = secondIsLong ? 4 : (1 + arity);
            int clsSlot = paramEndSlot;
            int entrySlot = paramEndSlot + 1;
            String owner = "cljl/Protocol$MethodFn";
            String entryType = "cljl/Protocol$MethodFn$CacheEntry";
            String entryDesc = "Lcljl/Protocol$MethodFn$CacheEntry;";

            MethodAssembler ma = new MethodAssembler(this);

            // Class<?> cls = (a != null) ? a.getClass() : Protocol.NIL_CLASS;
            ma.aload(1);
            int isNull = ma.ifNullPlaceholder();
            ma.aload(1);
            ma.invokevirtual("java/lang/Object", "getClass", "()Ljava/lang/Class;");
            int haveCls = ma.gotoPlaceholder();
            ma.patchBranchHere(isNull);
            ma.getstatic("cljl/Protocol", "NIL_CLASS", "Ljava/lang/Class;");
            ma.patchBranchHere(haveCls);
            ma.astore(clsSlot);

            // Fast slot 1 (dedicated monomorphic call site #1)
            ma.aload(0);
            ma.getfield(owner, "slot1", entryDesc);
            ma.astore(entrySlot);
            ma.aload(entrySlot);
            int s1Null = ma.ifNullPlaceholder();
            ma.aload(entrySlot);
            ma.getfield(entryType, "cls", "Ljava/lang/Class;");
            ma.aload(clsSlot);
            int s1Miss = ma.ifAcmpNePlaceholder();
            ma.aload(entrySlot);
            ma.getfield(entryType, "fn", "Lcljl/Fn;");
            emitProtoArgs(ma, arity, secondIsLong);
            ma.invokevirtual("cljl/Fn", callName, desc);
            if (returnsLong) ma.lreturn(); else ma.areturn();

            // Fast slot 2 (dedicated monomorphic call site #2 for bimorphic dispatch)
            ma.patchBranchHere(s1Null);
            ma.patchBranchHere(s1Miss);
            ma.aload(0);
            ma.getfield(owner, "slot2", entryDesc);
            ma.astore(entrySlot);
            ma.aload(entrySlot);
            int s2Null = ma.ifNullPlaceholder();
            ma.aload(entrySlot);
            ma.getfield(entryType, "cls", "Ljava/lang/Class;");
            ma.aload(clsSlot);
            int s2Miss = ma.ifAcmpNePlaceholder();
            ma.aload(entrySlot);
            ma.getfield(entryType, "fn", "Lcljl/Fn;");
            emitProtoArgs(ma, arity, secondIsLong);
            ma.invokevirtual("cljl/Fn", callName, desc);
            if (returnsLong) ma.lreturn(); else ma.areturn();

            // Slow path: resolveAndCache(a, cls)
            ma.patchBranchHere(s2Null);
            ma.patchBranchHere(s2Miss);
            ma.aload(0);
            ma.aload(1);
            ma.aload(clsSlot);
            ma.invokevirtual(owner, "resolveAndCache", "(Ljava/lang/Object;Ljava/lang/Class;)Lcljl/Fn;");
            emitProtoArgs(ma, arity, secondIsLong);
            ma.invokevirtual("cljl/Fn", callName, desc);
            if (returnsLong) ma.lreturn(); else ma.areturn();

            addCodeMethod(accessFlags, emitName, desc, Math.max(6, paramEndSlot + 2), entrySlot + 1, ma.toBytes());
        }

        private static void emitProtoArgs(MethodAssembler ma, int arity, boolean secondIsLong) {
            ma.aload(1);
            if (secondIsLong) {
                ma.lload(2);
            } else {
                for (int i = 2; i <= arity; i++) ma.aload(i);
            }
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
            emitDispatchBridgeWithKey(binaryName, mname, mname, retType, paramTypes);
        }

        void emitDispatchBridgeWithKey(String binaryName, String mname, String dispatchKey, Class<?> retType, Class<?>[] paramTypes) {
            String desc = methodDesc(retType, paramTypes);
            MethodAssembler ma = new MethodAssembler(this);
            int nextSlot = 1;
            int[] paramSlots = new int[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                paramSlots[i] = nextSlot;
                nextSlot += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
            }
            ma.ldcString(binaryName);
            ma.ldcString(dispatchKey);
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

        void emitProxySerializationGuards() {
            // Block Java serialization/deserialization of generated proxy classes by default
            // (GHSA-x4c4-68gp-vrhc / CLJ-2993) unless -Dclojure.serializable.fns=true is set.
            MethodAssembler wo = new MethodAssembler(this);
            wo.aload(0);
            wo.invokestatic("cljl/TypeFactory", "checkProxySerializable", "(Ljava/lang/Object;)V");
            wo.aload(1);
            wo.invokevirtual("java/io/ObjectOutputStream", "defaultWriteObject", "()V");
            wo.vreturn();
            addCodeMethod(0x0002, "writeObject", "(Ljava/io/ObjectOutputStream;)V", 2, 2, wo.toBytes());

            MethodAssembler ro = new MethodAssembler(this);
            ro.aload(0);
            ro.invokestatic("cljl/TypeFactory", "checkProxySerializable", "(Ljava/lang/Object;)V");
            ro.aload(1);
            ro.invokevirtual("java/io/ObjectInputStream", "defaultReadObject", "()V");
            ro.vreturn();
            addCodeMethod(0x0002, "readObject", "(Ljava/io/ObjectInputStream;)V", 2, 2, ro.toBytes());

            MethodAssembler rond = new MethodAssembler(this);
            rond.aload(0);
            rond.invokestatic("cljl/TypeFactory", "checkProxySerializable", "(Ljava/lang/Object;)V");
            rond.vreturn();
            addCodeMethod(0x0002, "readObjectNoData", "()V", 2, 1, rond.toBytes());
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
            else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) { code.write(0x10); code.write(v & 0xff); }
            else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) { code.write(0x11); code.write((v >>> 8) & 0xff); code.write(v & 0xff); }
            else {
                int idx = cw.addInteger(v);
                if (idx < 256) { code.write(0x12); code.write(idx); }
                else { code.write(0x13); code.write((idx >>> 8) & 0xff); code.write(idx & 0xff); }
            }
        }
        void aconstNull() { code.write(0x01); }
        void dup() { code.write(0x59); }
        void pop() { code.write(0x57); }
        private void emitVarOp(int baseOp4, int wideOp, int slot) {
            if (baseOp4 >= 0 && slot <= 3) {
                code.write(baseOp4 + slot);
            } else if (slot <= 255) {
                code.write(wideOp);
                code.write(slot);
            } else {
                code.write(0xc4); // WIDE
                code.write(wideOp);
                code.write((slot >>> 8) & 0xff);
                code.write(slot & 0xff);
            }
        }
        void aload(int slot) { emitVarOp(0x2a, 0x19, slot); }
        void iload(int slot) { emitVarOp(0x1a, 0x15, slot); }
        void lload(int slot) { emitVarOp(0x1e, 0x16, slot); }
        void astore(int slot) { emitVarOp(0x4b, 0x3a, slot); }
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
        void instanceofOp(String internalName) {
            int ci = cw.addClass(internalName);
            code.write(0xc1);
            code.write((ci >>> 8) & 0xff); code.write(ci & 0xff);
        }
        void getfield(String owner, String name, String desc) { emitRef(0xb4, 9, owner, name, desc); }
        void putfield(String owner, String name, String desc) { emitRef(0xb5, 9, owner, name, desc); }
        void getstatic(String owner, String name, String desc) { emitRef(0xb2, 9, owner, name, desc); }
        void putstatic(String owner, String name, String desc) { emitRef(0xb3, 9, owner, name, desc); }
        void invokevirtual(String owner, String name, String desc) { emitRef(0xb6, 10, owner, name, desc); }
        void invokespecial(String owner, String name, String desc) { emitRef(0xb7, 10, owner, name, desc); }
        void invokestatic(String owner, String name, String desc) { emitRef(0xb8, 10, owner, name, desc); }
        void invokeinterface(String owner, String name, String desc, int count) {
            int ri = cw.addRef(11, owner, name, desc);
            code.write(0xb9);
            code.write((ri >>> 8) & 0xff); code.write(ri & 0xff);
            code.write(count & 0xff);
            code.write(0);
        }
        void emitRef(int op, int tag, String owner, String name, String desc) {
            int ri = cw.addRef(tag, owner, name, desc);
            code.write(op);
            code.write((ri >>> 8) & 0xff); code.write(ri & 0xff);
        }
        int ifEqPlaceholder() { return branchPlaceholder(0x99); }
        int ifNePlaceholder() { return branchPlaceholder(0x9a); }
        int ifAcmpNePlaceholder() { return branchPlaceholder(0xa6); }
        int ifIcmpNePlaceholder() { return branchPlaceholder(0xa0); }
        int ifNullPlaceholder() { return branchPlaceholder(0xc6); }
        int gotoPlaceholder() { return branchPlaceholder(0xa7); }
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
        void lreturn() { code.write(0xad); }
        void areturn() { code.write(0xb0); }
        void athrow() { code.write(0xbf); }

        void loadTyped(Class<?> t, int slot) {
            if (t == boolean.class || t == byte.class || t == short.class || t == char.class || t == int.class) iload(slot);
            else if (t == long.class) emitVarOp(0x1e, 0x16, slot);
            else if (t == float.class) emitVarOp(0x22, 0x17, slot);
            else if (t == double.class) emitVarOp(0x26, 0x18, slot);
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
                invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z");
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
                if (rel < Short.MIN_VALUE || rel > Short.MAX_VALUE) {
                    throw new IllegalStateException("Bytecode branch offset overflow: " + rel);
                }
                b[p[0] + 1] = (byte) ((rel >>> 8) & 0xff);
                b[p[0] + 2] = (byte) (rel & 0xff);
            }
            return b;
        }
    }
}
