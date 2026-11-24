package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Clean-room JVM bytecode generator for Clojure's `gen-class` and AOT `compile`.
 * Emits standard JVM v49.0 .class bytecode supporting:
 *   :name, :extends, :implements, :prefix, :main, :state, :init, :post-init,
 *   :constructors (including super-arg forwarding from -init), :methods, :impl-ns,
 *   :exposes, :exposes-methods, :factory, :load-impl-ns, and automatic superclass
 *   method overrides with super fallback,
 * plus full AOT `<munged_ns>__init.class` emission for source-free AOT loading.
 */
public final class GenClass {

    private static final int CHUNK_SIZE = 28000;

    private GenClass() {}

    public static Object genClass(Object[] args) {
        PersistentHashMap opts = PersistentHashMap.EMPTY;
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts = opts.assoc(args[i], args[i + 1]);
        }
        return defineGenClass(opts, RT.currentNS());
    }

    public static Object compileNamespace(Symbol libSym) {
        if (libSym == null) throw new IllegalArgumentException("compile requires a namespace symbol");
        String compilePath = String.valueOf(RT.COMPILE_PATH.deref());
        try {
            Files.createDirectories(Path.of(compilePath));
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
        Core.unloadLib(libSym.name);
        Var.pushThreadBindings(Map.of(RT.COMPILE_FILES, Boolean.TRUE));
        try {
            Core.loadLib(libSym.name);
        } finally {
            Var.popThreadBindings();
        }
        return libSym;
    }

    /**
     * Emits `<munged_ns>__init.class` into *compile-path* when *compile-files* is true.
     * The generated class exposes `public static void load()` and a `<clinit>` that invokes `load()`,
     * matching Clojure's AOT namespace initialization contract so namespaces load cleanly without .clj sources.
     */
    public static void emitNamespaceInitClass(String nsName, String sourceName, String sourceText) {
        if (!RT.truthy(RT.COMPILE_FILES.deref())) return;
        if (nsName == null || nsName.isEmpty()) return;
        String binaryName = mungeClassName(nsName) + "__init";
        String internalName = binaryName.replace('.', '/');

        // Compress source text with GZIP and Base64-encode into <= 28KB chunks (well below 65535 UTF-8 limit)
        byte[] gzipped;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(sourceText.getBytes(StandardCharsets.UTF_8));
            }
            gzipped = baos.toByteArray();
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
        String b64 = Base64.getEncoder().encodeToString(gzipped);
        int numChunks = Math.max(1, (b64.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        String[] chunks = new String[numChunks];
        for (int i = 0; i < numChunks; i++) {
            int start = i * CHUNK_SIZE;
            int end = Math.min(b64.length(), start + CHUNK_SIZE);
            chunks[i] = b64.substring(start, end);
        }

        GenClassWriter cw = new GenClassWriter(internalName, "java/lang/Object", new String[0]);
        cw.emitNamespaceInitMethods(nsName, sourceName != null ? sourceName : (internalName + ".clj"), chunks);
        byte[] bytes = cw.toByteArray();
        writeClassFileIfCompiling(binaryName, bytes);
    }

    /**
     * Runtime entrypoint invoked by `<munged_ns>__init.load()` to initialize an AOT-compiled namespace
     * without requiring original `.clj` source files on the classpath.
     */
    public static void loadCompiledNamespace(String nsName, String sourceName, String[] base64GzipChunks) {
        RT.ensureInit();
        if (Core.isLibLoaded(nsName) && Namespace.find(nsName) != null) return;
        if (Core.isLibLoading(nsName)) return;

        StringBuilder sb = new StringBuilder();
        if (base64GzipChunks != null) {
            for (String c : base64GzipChunks) sb.append(c);
        }
        byte[] compressed = Base64.getDecoder().decode(sb.toString());
        String src;
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            src = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }

        Namespace savedNs = RT.currentNS();
        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, savedNs, RT.COMPILE_FILES, Boolean.FALSE));
        Core.beginLibLoading(nsName);
        try {
            Compiler.load(src, sourceName);
            Core.markLibLoaded(nsName);
        } finally {
            Core.endLibLoading(nsName);
            Var.popThreadBindings();
        }
    }

    public static void writeClassFileIfCompiling(String binaryName, byte[] bytes) {
        if (!RT.truthy(RT.COMPILE_FILES.deref())) return;
        if (binaryName == null || binaryName.isEmpty() || binaryName.contains("..") || binaryName.indexOf('\0') >= 0) {
            throw new SecurityException("Invalid class name for compilation: " + binaryName);
        }
        String compilePath = String.valueOf(RT.COMPILE_PATH.deref());
        Path baseDir = Path.of(compilePath).toAbsolutePath().normalize();
        Path outFile = baseDir.resolve(binaryName.replace('.', '/') + ".class").normalize();
        if (!outFile.startsWith(baseDir)) {
            throw new SecurityException("Path traversal detected in compile output: " + binaryName);
        }
        try {
            if (outFile.getParent() != null) {
                Files.createDirectories(outFile.getParent());
            }
            Files.write(outFile, bytes);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Class<?> defineGenClass(PersistentHashMap opts, Namespace curNs) {
        Object nameObj = opts.valAt(Keyword.intern("name"));
        String binaryName = nameObj != null ? mungeClassName(String.valueOf(nameObj))
                : mungeClassName(curNs.name.name);
        Object implNsObj = opts.valAt(Keyword.intern("impl-ns"));
        String implNsName = implNsObj != null ? String.valueOf(implNsObj) : curNs.name.name;
        String prefix = opts.valAt(Keyword.intern("prefix")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("prefix")))
                : "-";
        boolean genMain = RT.truthy(opts.valAt(Keyword.intern("main")));
        Object loadImplNsObj = opts.valAt(Keyword.intern("load-impl-ns"), Boolean.TRUE);
        boolean loadImplNs = RT.truthy(loadImplNsObj);
        String stateField = opts.valAt(Keyword.intern("state")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("state")))
                : null;
        String initName = opts.valAt(Keyword.intern("init")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("init")))
                : null;
        String postInitName = opts.valAt(Keyword.intern("post-init")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("post-init")))
                : null;
        String factoryName = opts.valAt(Keyword.intern("factory")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("factory")))
                : null;

        Class<?> superClass = Object.class;
        Object extObj = opts.valAt(Keyword.intern("extends"));
        if (extObj != null) {
            superClass = resolveTypeSpec(curNs, extObj);
            if (superClass == null) throw new IllegalArgumentException("Unable to resolve :extends class: " + extObj);
        }

        List<Class<?>> ifaces = new ArrayList<>();
        Object implsObj = opts.valAt(Keyword.intern("implements"));
        if (implsObj != null) {
            for (ISeq s = RT.seq(implsObj); s != null; s = s.next()) {
                Class<?> c = resolveTypeSpec(curNs, s.first());
                if (c != null && c.isInterface()) ifaces.add(c);
            }
        }

        String internalName = binaryName.replace('.', '/');
        String superInternal = superClass.getName().replace('.', '/');
        String[] ifaceInternals = new String[ifaces.size()];
        for (int i = 0; i < ifaces.size(); i++) ifaceInternals[i] = ifaces.get(i).getName().replace('.', '/');

        GenClassWriter cw = new GenClassWriter(internalName, superInternal, ifaceInternals);

        if (stateField != null) {
            cw.addField(0x0011, stateField, "Ljava/lang/Object;"); // PUBLIC | FINAL
        }

        // Static initializer: auto-load impl-ns when :load-impl-ns is true (default)
        if (loadImplNs) {
            cw.emitClinit(implNsName);
        }

        // Constructors (with :init super-arg forwarding and optional :factory static methods)
        List<Class<?>[]> ctorParamLists = new ArrayList<>();
        Object ctorsObj = opts.valAt(Keyword.intern("constructors"));
        if (ctorsObj instanceof Map<?, ?> cm && !cm.isEmpty()) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                Class<?>[] pTypes = resolveTypeList(curNs, e.getKey());
                Class<?>[] superPTypes = resolveTypeList(curNs, e.getValue());
                cw.emitCtor(implNsName, prefix, initName, postInitName, stateField, loadImplNs,
                        superInternal, pTypes, superPTypes);
                ctorParamLists.add(pTypes);
            }
        } else {
            // Mirror accessible superclass constructors (default [] -> [] for Object)
            List<Class<?>[]> mirrored = new ArrayList<>();
            for (Constructor<?> c : superClass.getDeclaredConstructors()) {
                int mods = c.getModifiers();
                if (Modifier.isPublic(mods) || Modifier.isProtected(mods)) {
                    mirrored.add(c.getParameterTypes());
                }
            }
            if (mirrored.isEmpty()) mirrored.add(new Class<?>[0]);
            for (Class<?>[] pTypes : mirrored) {
                cw.emitCtor(implNsName, prefix, initName, postInitName, stateField, loadImplNs,
                        superInternal, pTypes, pTypes);
                ctorParamLists.add(pTypes);
            }
        }

        Set<String> emitted = new HashSet<>();

        // :factory static factory methods calling each constructor
        if (factoryName != null) {
            for (Class<?>[] pTypes : ctorParamLists) {
                String desc = methodDescRaw("L" + internalName + ";", pTypes);
                if (emitted.add(factoryName + desc)) {
                    cw.emitFactoryMethod(factoryName, pTypes);
                }
            }
        }

        // :exposes {superField {:get getSuperField :set setSuperField}}
        Object exposesObj = opts.valAt(Keyword.intern("exposes"));
        if (exposesObj instanceof Map<?, ?> em) {
            for (Map.Entry<?, ?> e : em.entrySet()) {
                String fieldName = String.valueOf(e.getKey());
                Field f = findAccessibleField(superClass, fieldName);
                if (f == null) {
                    throw new IllegalArgumentException("No accessible field '" + fieldName + "' on " + superClass.getName());
                }
                Class<?> fType = f.getType();
                String ownerInternal = f.getDeclaringClass().getName().replace('.', '/');
                if (e.getValue() instanceof Map<?, ?> spec) {
                    Object getSym = spec.get(Keyword.intern("get"));
                    if (getSym != null) {
                        String gName = String.valueOf(getSym);
                        String desc = methodDesc(fType, new Class<?>[0]);
                        if (emitted.add(gName + desc)) {
                            cw.emitFieldGetter(ownerInternal, fieldName, fType, gName);
                        }
                    }
                    Object setSym = spec.get(Keyword.intern("set"));
                    if (setSym != null) {
                        String sName = String.valueOf(setSym);
                        String desc = methodDesc(void.class, new Class<?>[]{fType});
                        if (emitted.add(sName + desc)) {
                            cw.emitFieldSetter(ownerInternal, fieldName, fType, sName);
                        }
                    }
                }
            }
        }

        // :exposes-methods {superMethod exposedName}
        Object exposesMethodsObj = opts.valAt(Keyword.intern("exposes-methods"));
        if (exposesMethodsObj instanceof Map<?, ?> emm) {
            for (Map.Entry<?, ?> e : emm.entrySet()) {
                String superMName = String.valueOf(e.getKey());
                String exposedName = String.valueOf(e.getValue());
                List<Method> matches = findAccessibleMethodsByName(superClass, superMName);
                if (matches.isEmpty()) {
                    throw new IllegalArgumentException("No accessible method '" + superMName + "' on " + superClass.getName());
                }
                for (Method m : matches) {
                    if (Modifier.isAbstract(m.getModifiers())) continue;
                    String desc = methodDesc(m.getReturnType(), m.getParameterTypes());
                    if (emitted.add(exposedName + desc)) {
                        cw.emitExposedSuperMethod(superInternal, superMName, exposedName, m.getReturnType(), m.getParameterTypes());
                    }
                }
            }
        }

        // Explicit :methods
        Object methodsObj = opts.valAt(Keyword.intern("methods"));
        if (methodsObj != null) {
            for (ISeq s = RT.seq(methodsObj); s != null; s = s.next()) {
                PersistentVector spec = (PersistentVector) s.first();
                boolean isStatic = spec.meta() != null && RT.truthy(spec.meta().valAt(Keyword.intern("static")));
                String mName = String.valueOf(spec.nth(0));
                Class<?>[] pTypes = resolveTypeList(curNs, spec.nth(1));
                Class<?> retType = resolveTypeSpec(curNs, spec.nth(2));
                if (retType == null) retType = Object.class;
                String desc = methodDesc(retType, pTypes);
                if (emitted.add(mName + desc)) {
                    cw.emitMethod(implNsName, prefix, mName, isStatic, loadImplNs, retType, pTypes);
                }
            }
        }

        // Implemented interface methods
        for (Class<?> iface : ifaces) {
            for (Method m : iface.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                String desc = methodDesc(m.getReturnType(), m.getParameterTypes());
                if (emitted.add(m.getName() + desc)) {
                    cw.emitMethod(implNsName, prefix, m.getName(), false, loadImplNs, m.getReturnType(), m.getParameterTypes());
                }
            }
        }

        // Overridable superclass methods (abstract -> dispatch directly; concrete -> dispatch if var exists else super)
        for (Method m : collectOverridableSuperMethods(superClass)) {
            String desc = methodDesc(m.getReturnType(), m.getParameterTypes());
            if (!emitted.add(m.getName() + desc)) continue;
            if (Modifier.isAbstract(m.getModifiers())) {
                cw.emitMethod(implNsName, prefix, m.getName(), false, loadImplNs, m.getReturnType(), m.getParameterTypes());
            } else {
                cw.emitSuperFallbackMethod(implNsName, prefix, m.getName(), loadImplNs,
                        superInternal, m.getReturnType(), m.getParameterTypes());
            }
        }

        // :main true -> public static void main(String[] args)
        if (genMain && emitted.add("main([Ljava/lang/String;)V")) {
            cw.emitMainMethod(implNsName, prefix + "main");
        }

        byte[] bytes = cw.toByteArray();
        writeClassFileIfCompiling(binaryName, bytes);
        Class<?> cls = TypeFactory.defineRawClass(binaryName, bytes);
        Interop.registerClass(binaryName, cls);
        int dot = binaryName.lastIndexOf('.');
        String simple = dot >= 0 ? binaryName.substring(dot + 1) : binaryName;
        curNs.importClass(Symbol.intern(simple), cls);
        return cls;
    }

    private static Field findAccessibleField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                int mods = f.getModifiers();
                if ((Modifier.isPublic(mods) || Modifier.isProtected(mods)) && !Modifier.isStatic(mods)) {
                    return f;
                }
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static List<Method> findAccessibleMethodsByName(Class<?> cls, String name) {
        List<Method> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                int mods = m.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isPrivate(mods) || m.isSynthetic() || m.isBridge()) continue;
                if (!Modifier.isPublic(mods) && !Modifier.isProtected(mods)) continue;
                String sig = m.getName() + methodDesc(m.getReturnType(), m.getParameterTypes());
                if (seen.add(sig)) result.add(m);
            }
        }
        return result;
    }

    private static List<Method> collectOverridableSuperMethods(Class<?> superClass) {
        List<Method> result = new ArrayList<>();
        Set<String> seenSigs = new HashSet<>();
        Set<String> finalSigs = new HashSet<>();
        for (Class<?> c = superClass; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isPrivate(mods) || m.isSynthetic() || m.isBridge()) continue;
                if (!Modifier.isPublic(mods) && !Modifier.isProtected(mods)) continue;
                String name = m.getName();
                if (c == Object.class && ("getClass".equals(name) || "notify".equals(name)
                        || "notifyAll".equals(name) || "wait".equals(name) || "finalize".equals(name))) {
                    continue;
                }
                String paramSig = name + paramDescOnly(m.getParameterTypes());
                if (Modifier.isFinal(mods)) {
                    finalSigs.add(paramSig);
                    continue;
                }
                if (finalSigs.contains(paramSig)) continue;
                if (seenSigs.add(paramSig)) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    private static String paramDescOnly(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(typeDesc(p));
        sb.append(')');
        return sb.toString();
    }

    // ========================================================================
    // Runtime Dispatch Hooks Called by Generated GenClass Bytecode
    // ========================================================================

    public static void ensureImplNsLoaded(String nsName) {
        RT.ensureInit();
        if (Namespace.find(nsName) == null) {
            Core.loadLib(nsName);
        }
    }

    public static Var findImplVar(String nsName, String fnName, boolean loadImplNs) {
        RT.ensureInit();
        Namespace ns = Namespace.find(nsName);
        if (ns == null && loadImplNs) {
            Core.loadLib(nsName);
            ns = Namespace.find(nsName);
        }
        if (ns == null) return null;
        Var v = ns.findInternedVar(Symbol.intern(fnName));
        return (v != null && v.isBound()) ? v : null;
    }

    private static Var resolveImplVar(String nsName, String fnName, boolean loadImplNs) {
        Var v = findImplVar(nsName, fnName, loadImplNs);
        if (v == null) {
            Namespace ns = Namespace.find(nsName);
            if (ns == null) {
                throw new IllegalStateException("Namespace not found for gen-class: " + nsName);
            }
            throw new UnsupportedOperationException("gen-class function not defined: " + nsName + "/" + fnName);
        }
        return v;
    }

    /**
     * Invokes the `:init` function before `super(...)` and returns a 2-element array:
     * `[0]` = `Object[]` of arguments to pass to `super(...)`, `[1]` = initial state value.
     */
    public static Object[] dispatchInitPair(String nsName, String fnName, boolean loadImplNs, Object[] args) {
        Var v = resolveImplVar(nsName, fnName, loadImplNs);
        Object res = ((Fn) v.deref()).applyTo(boxArgs(args));
        if (res instanceof clojure.lang.IPersistentVector pv && pv.count() >= 1) {
            Object superArgsObj = pv.nth(0);
            Object[] superArgs = superArgsObj != null ? RT.toArray(superArgsObj) : Fn.EMPTY_ARGS;
            Object stateVal = pv.count() >= 2 ? pv.nth(1) : null;
            return new Object[]{superArgs, stateVal};
        }
        return new Object[]{Fn.EMPTY_ARGS, res};
    }

    public static Object dispatchInit(String nsName, String fnName, Object[] args) {
        Object[] pair = dispatchInitPair(nsName, fnName, true, args);
        return pair[1];
    }

    public static void dispatchPostInit(String nsName, String fnName, boolean loadImplNs, Object self, Object[] args) {
        Var v = resolveImplVar(nsName, fnName, loadImplNs);
        Object[] callArgs = new Object[1 + args.length];
        callArgs[0] = self;
        for (int i = 0; i < args.length; i++) callArgs[i + 1] = Interop.boxResult(args[i]);
        ((Fn) v.deref()).applyTo(callArgs);
    }

    public static Object dispatchInstance(String nsName, String fnName, boolean loadImplNs, Object self, Object[] args) {
        Var v = resolveImplVar(nsName, fnName, loadImplNs);
        return invokeVarInstance(v, self, args);
    }

    public static Object invokeVarInstance(Var v, Object self, Object[] args) {
        Object[] callArgs = new Object[1 + args.length];
        callArgs[0] = self;
        for (int i = 0; i < args.length; i++) callArgs[i + 1] = Interop.boxResult(args[i]);
        return ((Fn) v.deref()).applyTo(callArgs);
    }

    public static Object dispatchStatic(String nsName, String fnName, boolean loadImplNs, Object[] args) {
        Var v = resolveImplVar(nsName, fnName, loadImplNs);
        return ((Fn) v.deref()).applyTo(boxArgs(args));
    }

    public static void dispatchMain(String nsName, String fnName, String[] args) {
        RT.ensureInit();
        Namespace ns = Namespace.find(nsName);
        if (ns == null) {
            Core.loadLib(nsName);
            ns = Namespace.find(nsName);
        }
        if (ns == null) throw new IllegalStateException("Namespace not found for main: " + nsName);
        Var v = ns.findInternedVar(Symbol.intern(fnName));
        if (v == null || !v.isBound()) {
            throw new IllegalStateException("Main function not defined: " + nsName + "/" + fnName);
        }
        Object[] callArgs = args != null ? Arrays.copyOf(args, args.length, Object[].class) : Fn.EMPTY_ARGS;
        ((Fn) v.deref()).applyTo(callArgs);
    }

    private static Object[] boxArgs(Object[] args) {
        if (args == null || args.length == 0) return Fn.EMPTY_ARGS;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) out[i] = Interop.boxResult(args[i]);
        return out;
    }

    public static String mungeClassName(String s) {
        return s.replace('-', '_');
    }

    private static Class<?> resolveTypeSpec(Namespace ns, Object spec) {
        if (spec == null) return Object.class;
        if (spec instanceof Class<?> c) return c;
        if (spec instanceof Symbol sym) {
            if (sym.ns != null && Interop.isArrayDimToken(sym.name)) {
                return Interop.resolveArrayClass(ns, sym.ns, sym.name);
            }
            String name = sym.ns == null ? sym.name : (sym.ns + "." + sym.name);
            return Interop.resolveClass(ns, name);
        }
        return Interop.resolveClass(ns, String.valueOf(spec));
    }

    private static Class<?>[] resolveTypeList(Namespace ns, Object coll) {
        if (coll == null) return new Class<?>[0];
        List<Class<?>> list = new ArrayList<>();
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
            Class<?> c = resolveTypeSpec(ns, s.first());
            list.add(c != null ? c : Object.class);
        }
        return list.toArray(new Class<?>[0]);
    }

    private static String typeDesc(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return c.getName().replace('.', '/');
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String methodDesc(Class<?> ret, Class<?>[] params) {
        return methodDescRaw(typeDesc(ret), params);
    }

    private static String methodDescRaw(String retDesc, Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(typeDesc(p));
        sb.append(')').append(retDesc);
        return sb.toString();
    }

    // ========================================================================
    // Self-Contained v49.0 Classfile Writer for GenClass and AOT __init
    // ========================================================================

    private static final class GenClassWriter {
        private final List<byte[]> cpEntries = new ArrayList<>();
        private final Map<String, Integer> utf8Map = new HashMap<>();
        private final Map<String, Integer> classMap = new HashMap<>();
        private final Map<String, Integer> natMap = new HashMap<>();
        private final Map<String, Integer> memberMap = new HashMap<>();
        private final Map<String, Integer> stringMap = new HashMap<>();

        private final String thisInternal;
        private final int thisClassIdx;
        private final int superClassIdx;
        private final int[] ifaceIdxs;
        private final List<byte[]> fields = new ArrayList<>();
        private final List<byte[]> methods = new ArrayList<>();

        GenClassWriter(String thisInternal, String superInternal, String[] ifaceInternals) {
            this.thisInternal = thisInternal;
            this.thisClassIdx = cpClass(thisInternal);
            this.superClassIdx = cpClass(superInternal);
            this.ifaceIdxs = new int[ifaceInternals.length];
            for (int i = 0; i < ifaceInternals.length; i++) ifaceIdxs[i] = cpClass(ifaceInternals[i]);
        }

        private int addCp(byte[] entry) {
            cpEntries.add(entry);
            return cpEntries.size();
        }

        private int cpUtf8(String s) {
            Integer idx = utf8Map.get(s);
            if (idx != null) return idx;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeByte(1);
                dos.writeUTF(s);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            int id = addCp(baos.toByteArray());
            utf8Map.put(s, id);
            return id;
        }

        private int cpClass(String internal) {
            Integer idx = classMap.get(internal);
            if (idx != null) return idx;
            int u = cpUtf8(internal);
            int id = addCp(new byte[]{7, (byte) (u >> 8), (byte) u});
            classMap.put(internal, id);
            return id;
        }

        private int cpString(String s) {
            Integer idx = stringMap.get(s);
            if (idx != null) return idx;
            int u = cpUtf8(s);
            int id = addCp(new byte[]{8, (byte) (u >> 8), (byte) u});
            stringMap.put(s, id);
            return id;
        }

        private int cpNat(String name, String desc) {
            String key = name + "\0" + desc;
            Integer idx = natMap.get(key);
            if (idx != null) return idx;
            int n = cpUtf8(name);
            int d = cpUtf8(desc);
            int id = addCp(new byte[]{12, (byte) (n >> 8), (byte) n, (byte) (d >> 8), (byte) d});
            natMap.put(key, id);
            return id;
        }

        private int cpFieldref(String cls, String name, String desc) {
            return cpMember(9, cls, name, desc);
        }

        private int cpMethodref(String cls, String name, String desc) {
            return cpMember(10, cls, name, desc);
        }

        private int cpMember(int tag, String cls, String name, String desc) {
            String key = tag + ":" + cls + "." + name + ":" + desc;
            Integer idx = memberMap.get(key);
            if (idx != null) return idx;
            int c = cpClass(cls);
            int nat = cpNat(name, desc);
            int id = addCp(new byte[]{(byte) tag, (byte) (c >> 8), (byte) c, (byte) (nat >> 8), (byte) nat});
            memberMap.put(key, id);
            return id;
        }

        void addField(int flags, String name, String desc) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeShort(flags);
                dos.writeShort(cpUtf8(name));
                dos.writeShort(cpUtf8(desc));
                dos.writeShort(0);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            fields.add(baos.toByteArray());
        }

        private void emitLdc(ByteArrayOutputStream code, int cpIdx) {
            if (cpIdx <= 255) {
                code.write(0x12); // ldc
                code.write(cpIdx);
            } else {
                code.write(0x13); // ldc_w
                code.write(cpIdx >> 8);
                code.write(cpIdx);
            }
        }

        private void emitIntConst(ByteArrayOutputStream code, int v) {
            if (v >= 0 && v <= 5) code.write(0x03 + v);
            else if (v >= -128 && v <= 127) { code.write(0x10); code.write(v); }
            else { code.write(0x11); code.write(v >> 8); code.write(v); }
        }

        private void emitLoadTypedParam(ByteArrayOutputStream code, Class<?> pt, int slot) {
            if (pt == long.class) { code.write(0x16); code.write(slot); }
            else if (pt == double.class) { code.write(0x18); code.write(slot); }
            else if (pt == float.class) { code.write(0x17); code.write(slot); }
            else if (pt == int.class || pt == short.class || pt == byte.class || pt == boolean.class || pt == char.class) {
                code.write(0x15); code.write(slot);
            } else {
                code.write(0x19); code.write(slot);
            }
        }

        private void emitLoadTypedParams(ByteArrayOutputStream code, Class<?>[] pTypes, int firstLocal) {
            int slot = firstLocal;
            for (Class<?> pt : pTypes) {
                emitLoadTypedParam(code, pt, slot);
                slot += (pt == long.class || pt == double.class) ? 2 : 1;
            }
        }

        private void emitTypedReturn(ByteArrayOutputStream code, Class<?> retType) {
            if (retType == void.class) code.write(0xb1);
            else if (retType == long.class) code.write(0xad);
            else if (retType == double.class) code.write(0xaf);
            else if (retType == float.class) code.write(0xae);
            else if (retType.isPrimitive()) code.write(0xac);
            else code.write(0xb0);
        }

        private void emitPackArgs(ByteArrayOutputStream code, Class<?>[] paramTypes, int firstLocal) {
            emitIntConst(code, paramTypes.length);
            int objCls = cpClass("java/lang/Object");
            code.write(0xbd); code.write(objCls >> 8); code.write(objCls); // anewarray Object
            int slot = firstLocal;
            for (int i = 0; i < paramTypes.length; i++) {
                code.write(0x59); // dup
                emitIntConst(code, i);
                Class<?> pt = paramTypes[i];
                if (pt == long.class) {
                    code.write(0x16); code.write(slot);
                    int m = cpMethodref("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 2;
                } else if (pt == double.class) {
                    code.write(0x18); code.write(slot);
                    int m = cpMethodref("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 2;
                } else if (pt == int.class || pt == short.class || pt == byte.class) {
                    code.write(0x15); code.write(slot);
                    int m = cpMethodref("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else if (pt == float.class) {
                    code.write(0x17); code.write(slot);
                    int m = cpMethodref("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else if (pt == boolean.class) {
                    code.write(0x15); code.write(slot);
                    int m = cpMethodref("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else if (pt == char.class) {
                    code.write(0x15); code.write(slot);
                    int m = cpMethodref("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else {
                    code.write(0x19); code.write(slot);
                    slot += 1;
                }
                code.write(0x53); // aastore
            }
        }

        private void emitUnboxOrCastValue(ByteArrayOutputStream code, Class<?> targetType) {
            if (targetType == boolean.class) {
                int tr = cpMethodref("cljl/RT", "truthy", "(Ljava/lang/Object;)Z");
                code.write(0xb8); code.write(tr >> 8); code.write(tr);
            } else if (targetType == long.class) {
                int nl = cpMethodref("cljl/Numbers", "toLong", "(Ljava/lang/Object;)J");
                code.write(0xb8); code.write(nl >> 8); code.write(nl);
            } else if (targetType == int.class) {
                int nl = cpMethodref("cljl/Numbers", "intCast", "(Ljava/lang/Object;)I");
                code.write(0xb8); code.write(nl >> 8); code.write(nl);
            } else if (targetType == double.class) {
                int nd = cpMethodref("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D");
                code.write(0xb8); code.write(nd >> 8); code.write(nd);
            } else if (targetType == float.class) {
                int nd = cpMethodref("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D");
                code.write(0xb8); code.write(nd >> 8); code.write(nd);
                code.write(0x90); // d2f
            } else if (targetType == short.class) {
                int nl = cpMethodref("cljl/Numbers", "shortCast", "(Ljava/lang/Object;)S");
                code.write(0xb8); code.write(nl >> 8); code.write(nl);
            } else if (targetType == byte.class) {
                int nl = cpMethodref("cljl/Numbers", "byteCast", "(Ljava/lang/Object;)B");
                code.write(0xb8); code.write(nl >> 8); code.write(nl);
            } else if (targetType == char.class) {
                int nl = cpMethodref("cljl/Numbers", "charCast", "(Ljava/lang/Object;)C");
                code.write(0xb8); code.write(nl >> 8); code.write(nl);
            } else if (targetType != Object.class) {
                int cls = cpClass(targetType.getName().replace('.', '/'));
                code.write(0xc0); code.write(cls >> 8); code.write(cls); // checkcast
            }
        }

        private void emitUnboxAndReturn(ByteArrayOutputStream code, Class<?> retType) {
            if (retType == void.class) {
                code.write(0x57); // pop
                code.write(0xb1); // return
                return;
            }
            emitUnboxOrCastValue(code, retType);
            emitTypedReturn(code, retType);
        }

        void emitClinit(String implNsName) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            emitLdc(code, cpString(implNsName));
            int m = cpMethodref("cljl/GenClass", "ensureImplNsLoaded", "(Ljava/lang/String;)V");
            code.write(0xb8); code.write(m >> 8); code.write(m);
            code.write(0xb1); // return
            addMethod(0x0008, "<clinit>", "()V", 4, 0, code.toByteArray());
        }

        void emitNamespaceInitMethods(String nsName, String sourceName, String[] chunks) {
            // public static synchronized void load()
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            emitLdc(code, cpString(nsName));
            emitLdc(code, cpString(sourceName));
            emitIntConst(code, chunks.length);
            int strCls = cpClass("java/lang/String");
            code.write(0xbd); code.write(strCls >> 8); code.write(strCls); // anewarray String
            for (int i = 0; i < chunks.length; i++) {
                code.write(0x59); // dup
                emitIntConst(code, i);
                emitLdc(code, cpString(chunks[i]));
                code.write(0x53); // aastore
            }
            int loadRef = cpMethodref("cljl/GenClass", "loadCompiledNamespace",
                    "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V");
            code.write(0xb8); code.write(loadRef >> 8); code.write(loadRef);
            code.write(0xb1);
            addMethod(0x0029, "load", "()V", 8, 0, code.toByteArray()); // PUBLIC | STATIC | SYNCHRONIZED

            // static { load(); }
            ByteArrayOutputStream clinit = new ByteArrayOutputStream();
            int selfLoad = cpMethodref(thisInternal, "load", "()V");
            clinit.write(0xb8); clinit.write(selfLoad >> 8); clinit.write(selfLoad);
            clinit.write(0xb1);
            addMethod(0x0008, "<clinit>", "()V", 2, 0, clinit.toByteArray());
        }

        void emitCtor(
                String implNsName, String prefix, String initName, String postInitName,
                String stateField, boolean loadImplNs, String superInternal,
                Class<?>[] pTypes, Class<?>[] superPTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            int firstFreeLocal = 1;
            for (Class<?> pt : pTypes) firstFreeLocal += (pt == long.class || pt == double.class) ? 2 : 1;

            if (initName != null) {
                // Invoke :init BEFORE super(...) so returned [super-args state] can be forwarded to super(...)
                emitLdc(code, cpString(implNsName));
                emitLdc(code, cpString(prefix + initName));
                code.write(loadImplNs ? 0x04 : 0x03);
                emitPackArgs(code, pTypes, 1);
                int disp = cpMethodref("cljl/GenClass", "dispatchInitPair",
                        "(Ljava/lang/String;Ljava/lang/String;Z[Ljava/lang/Object;)[Ljava/lang/Object;");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
                code.write(0x3a); code.write(firstFreeLocal); // astore initPair

                code.write(0x2a); // aload_0
                int objArrCls = cpClass("[Ljava/lang/Object;");
                for (int i = 0; i < superPTypes.length; i++) {
                    code.write(0x19); code.write(firstFreeLocal); // aload initPair
                    code.write(0x03); // iconst_0 -> superArgs
                    code.write(0x32); // aaload
                    code.write(0xc0); code.write(objArrCls >> 8); code.write(objArrCls); // checkcast Object[]
                    emitIntConst(code, i);
                    code.write(0x32); // aaload
                    emitUnboxOrCastValue(code, superPTypes[i]);
                }
                int superCtor = cpMethodref(superInternal, "<init>", methodDesc(void.class, superPTypes));
                code.write(0xb7); code.write(superCtor >> 8); code.write(superCtor); // invokespecial

                if (stateField != null) {
                    code.write(0x2a); // aload_0
                    code.write(0x19); code.write(firstFreeLocal); // aload initPair
                    code.write(0x04); // iconst_1 -> stateVal
                    code.write(0x32); // aaload
                    int fref = cpFieldref(thisInternal, stateField, "Ljava/lang/Object;");
                    code.write(0xb5); code.write(fref >> 8); code.write(fref); // putfield
                }
            } else {
                code.write(0x2a); // aload_0
                int slot = 1;
                for (int i = 0; i < superPTypes.length && i < pTypes.length; i++) {
                    emitLoadTypedParam(code, pTypes[i], slot);
                    slot += (pTypes[i] == long.class || pTypes[i] == double.class) ? 2 : 1;
                }
                int superCtor = cpMethodref(superInternal, "<init>", methodDesc(void.class, superPTypes));
                code.write(0xb7); code.write(superCtor >> 8); code.write(superCtor);

                if (stateField != null) {
                    code.write(0x2a); // aload_0
                    code.write(0x01); // aconst_null
                    int fref = cpFieldref(thisInternal, stateField, "Ljava/lang/Object;");
                    code.write(0xb5); code.write(fref >> 8); code.write(fref);
                }
            }

            if (postInitName != null) {
                emitLdc(code, cpString(implNsName));
                emitLdc(code, cpString(prefix + postInitName));
                code.write(loadImplNs ? 0x04 : 0x03);
                code.write(0x2a); // aload_0
                emitPackArgs(code, pTypes, 1);
                int disp = cpMethodref("cljl/GenClass", "dispatchPostInit",
                        "(Ljava/lang/String;Ljava/lang/String;ZLjava/lang/Object;[Ljava/lang/Object;)V");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
            }

            code.write(0xb1); // return
            int maxStack = Math.max(12, 4 + superPTypes.length * 2);
            addMethod(0x0001, "<init>", methodDesc(void.class, pTypes), maxStack, firstFreeLocal + 2, code.toByteArray());
        }

        void emitFactoryMethod(String factoryName, Class<?>[] pTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            code.write(0xbb); code.write(thisClassIdx >> 8); code.write(thisClassIdx); // new thisInternal
            code.write(0x59); // dup
            emitLoadTypedParams(code, pTypes, 0);
            int ctorRef = cpMethodref(thisInternal, "<init>", methodDesc(void.class, pTypes));
            code.write(0xb7); code.write(ctorRef >> 8); code.write(ctorRef); // invokespecial
            code.write(0xb0); // areturn
            int maxLocals = 0;
            for (Class<?> pt : pTypes) maxLocals += (pt == long.class || pt == double.class) ? 2 : 1;
            addMethod(0x0009, factoryName, methodDescRaw("L" + thisInternal + ";", pTypes),
                    Math.max(4, maxLocals + 3), maxLocals, code.toByteArray());
        }

        void emitFieldGetter(String ownerInternal, String fieldName, Class<?> fieldType, String getterName) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            code.write(0x2a); // aload_0
            int fref = cpFieldref(ownerInternal, fieldName, typeDesc(fieldType));
            code.write(0xb4); code.write(fref >> 8); code.write(fref); // getfield
            emitTypedReturn(code, fieldType);
            addMethod(0x0001, getterName, methodDesc(fieldType, new Class<?>[0]), 4, 1, code.toByteArray());
        }

        void emitFieldSetter(String ownerInternal, String fieldName, Class<?> fieldType, String setterName) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            code.write(0x2a); // aload_0
            emitLoadTypedParam(code, fieldType, 1);
            int fref = cpFieldref(ownerInternal, fieldName, typeDesc(fieldType));
            code.write(0xb5); code.write(fref >> 8); code.write(fref); // putfield
            code.write(0xb1); // return
            int locals = 1 + ((fieldType == long.class || fieldType == double.class) ? 2 : 1);
            addMethod(0x0001, setterName, methodDesc(void.class, new Class<?>[]{fieldType}), 4, locals, code.toByteArray());
        }

        void emitExposedSuperMethod(String superInternal, String superMName, String exposedName, Class<?> retType, Class<?>[] pTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            code.write(0x2a); // aload_0
            emitLoadTypedParams(code, pTypes, 1);
            int superRef = cpMethodref(superInternal, superMName, methodDesc(retType, pTypes));
            code.write(0xb7); code.write(superRef >> 8); code.write(superRef); // invokespecial
            emitTypedReturn(code, retType);
            int maxLocals = 1;
            for (Class<?> pt : pTypes) maxLocals += (pt == long.class || pt == double.class) ? 2 : 1;
            addMethod(0x0001, exposedName, methodDesc(retType, pTypes), Math.max(4, maxLocals + 2), maxLocals, code.toByteArray());
        }

        void emitMethod(String implNsName, String prefix, String mName, boolean isStatic, boolean loadImplNs,
                        Class<?> retType, Class<?>[] pTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            emitLdc(code, cpString(implNsName));
            emitLdc(code, cpString(prefix + mName));
            code.write(loadImplNs ? 0x04 : 0x03);
            if (!isStatic) {
                code.write(0x2a); // aload_0
                emitPackArgs(code, pTypes, 1);
                int disp = cpMethodref("cljl/GenClass", "dispatchInstance",
                        "(Ljava/lang/String;Ljava/lang/String;ZLjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
            } else {
                emitPackArgs(code, pTypes, 0);
                int disp = cpMethodref("cljl/GenClass", "dispatchStatic",
                        "(Ljava/lang/String;Ljava/lang/String;Z[Ljava/lang/Object;)Ljava/lang/Object;");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
            }
            emitUnboxAndReturn(code, retType);
            int maxLocals = isStatic ? 0 : 1;
            for (Class<?> pt : pTypes) maxLocals += (pt == long.class || pt == double.class) ? 2 : 1;
            int flags = isStatic ? 0x0009 : 0x0001;
            addMethod(flags, mName, methodDesc(retType, pTypes), 12, maxLocals + 2, code.toByteArray());
        }

        void emitSuperFallbackMethod(String implNsName, String prefix, String mName, boolean loadImplNs,
                                     String superInternal, Class<?> retType, Class<?>[] pTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            emitLdc(code, cpString(implNsName));
            emitLdc(code, cpString(prefix + mName));
            code.write(loadImplNs ? 0x04 : 0x03);
            int findRef = cpMethodref("cljl/GenClass", "findImplVar", "(Ljava/lang/String;Ljava/lang/String;Z)Lcljl/Var;");
            code.write(0xb8); code.write(findRef >> 8); code.write(findRef);
            code.write(0x59); // dup -> [Var, Var]

            ByteArrayOutputStream foundBranch = new ByteArrayOutputStream();
            foundBranch.write(0x2a); // aload_0
            emitPackArgs(foundBranch, pTypes, 1);
            int invRef = cpMethodref("cljl/GenClass", "invokeVarInstance",
                    "(Lcljl/Var;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
            foundBranch.write(0xb8); foundBranch.write(invRef >> 8); foundBranch.write(invRef);
            emitUnboxAndReturn(foundBranch, retType);
            byte[] foundBytes = foundBranch.toByteArray();

            int offset = 3 + foundBytes.length;
            code.write(0xc6); // ifnull L_super
            code.write((offset >> 8) & 0xFF);
            code.write(offset & 0xFF);
            code.write(foundBytes, 0, foundBytes.length);

            // L_super: pop the null Var and delegate via invokespecial super.<mName>
            code.write(0x57); // pop
            code.write(0x2a); // aload_0
            emitLoadTypedParams(code, pTypes, 1);
            int superRef = cpMethodref(superInternal, mName, methodDesc(retType, pTypes));
            code.write(0xb7); code.write(superRef >> 8); code.write(superRef);
            emitTypedReturn(code, retType);

            int maxLocals = 1;
            for (Class<?> pt : pTypes) maxLocals += (pt == long.class || pt == double.class) ? 2 : 1;
            addMethod(0x0001, mName, methodDesc(retType, pTypes), Math.max(12, maxLocals + 6), maxLocals + 2, code.toByteArray());
        }

        void emitMainMethod(String implNsName, String mainFnName) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            emitLdc(code, cpString(implNsName));
            emitLdc(code, cpString(mainFnName));
            code.write(0x2a); // aload_0 (String[] args)
            int disp = cpMethodref("cljl/GenClass", "dispatchMain", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V");
            code.write(0xb8); code.write(disp >> 8); code.write(disp);
            code.write(0xb1); // return
            addMethod(0x0009, "main", "([Ljava/lang/String;)V", 6, 2, code.toByteArray());
        }

        private void addMethod(int flags, String name, String desc, int maxStack, int maxLocals, byte[] bytecode) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeShort(flags);
                dos.writeShort(cpUtf8(name));
                dos.writeShort(cpUtf8(desc));
                dos.writeShort(1); // 1 attribute: Code
                dos.writeShort(cpUtf8("Code"));
                dos.writeInt(12 + bytecode.length);
                dos.writeShort(maxStack);
                dos.writeShort(maxLocals);
                dos.writeInt(bytecode.length);
                dos.write(bytecode);
                dos.writeShort(0); // exception_table_length
                dos.writeShort(0); // attributes_count
            } catch (IOException e) { throw new UncheckedIOException(e); }
            methods.add(baos.toByteArray());
        }

        byte[] toByteArray() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                dos.writeInt(0xCAFEBABE);
                dos.writeShort(0);
                dos.writeShort(49); // Java 5 classfile (no StackMapTable required)
                dos.writeShort(cpEntries.size() + 1);
                for (byte[] entry : cpEntries) dos.write(entry);
                dos.writeShort(0x0021); // PUBLIC | SUPER
                dos.writeShort(thisClassIdx);
                dos.writeShort(superClassIdx);
                dos.writeShort(ifaceIdxs.length);
                for (int idx : ifaceIdxs) dos.writeShort(idx);
                dos.writeShort(fields.size());
                for (byte[] f : fields) dos.write(f);
                dos.writeShort(methods.size());
                for (byte[] m : methods) dos.write(m);
                dos.writeShort(0);
            } catch (IOException e) { throw new UncheckedIOException(e); }
            return baos.toByteArray();
        }
    }
}
