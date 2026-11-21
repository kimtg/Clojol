package cljl;

import cljl.lang.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Clean-room JVM bytecode generator for Clojure's `gen-class` and AOT `compile`.
 * Emits standard JVM v49.0 .class bytecode (supports :name, :extends, :implements,
 * :prefix, :main, :state, :init, :post-init, :constructors, :methods, :impl-ns),
 * defines the class dynamically at runtime, and writes .class files to *compile-path*
 * when *compile-files* is true.
 */
public final class GenClass {

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

    public static void writeClassFileIfCompiling(String binaryName, byte[] bytes) {
        if (!RT.truthy(RT.COMPILE_FILES.deref())) return;
        String compilePath = String.valueOf(RT.COMPILE_PATH.deref());
        Path outFile = Path.of(compilePath, binaryName.replace('.', '/') + ".class");
        try {
            if (outFile.getParent() != null) {
                Files.createDirectories(outFile.getParent());
            }
            Files.write(outFile, bytes);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
    }

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
        String stateField = opts.valAt(Keyword.intern("state")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("state")))
                : null;
        String initName = opts.valAt(Keyword.intern("init")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("init")))
                : null;
        String postInitName = opts.valAt(Keyword.intern("post-init")) != null
                ? String.valueOf(opts.valAt(Keyword.intern("post-init")))
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

        // Constructors
        Object ctorsObj = opts.valAt(Keyword.intern("constructors"));
        if (ctorsObj instanceof Map<?, ?> cm && !cm.isEmpty()) {
            for (Map.Entry<?, ?> e : cm.entrySet()) {
                Class<?>[] pTypes = resolveTypeList(curNs, e.getKey());
                Class<?>[] superPTypes = resolveTypeList(curNs, e.getValue());
                cw.emitCtor(implNsName, prefix, initName, postInitName, stateField, superInternal, pTypes, superPTypes);
            }
        } else {
            cw.emitCtor(implNsName, prefix, initName, postInitName, stateField, superInternal, new Class<?>[0], new Class<?>[0]);
        }

        Set<String> emitted = new HashSet<>();

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
                    cw.emitMethod(implNsName, prefix, mName, isStatic, retType, pTypes);
                }
            }
        }

        // Implemented interface methods
        for (Class<?> iface : ifaces) {
            for (Method m : iface.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                String desc = methodDesc(m.getReturnType(), m.getParameterTypes());
                if (emitted.add(m.getName() + desc)) {
                    cw.emitMethod(implNsName, prefix, m.getName(), false, m.getReturnType(), m.getParameterTypes());
                }
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

    // ========================================================================
    // Runtime Dispatch Hooks Called by Generated GenClass Bytecode
    // ========================================================================

    private static Var resolveImplVar(String nsName, String fnName) {
        RT.ensureInit();
        Namespace ns = Namespace.find(nsName);
        if (ns == null) {
            Core.loadLib(nsName);
            ns = Namespace.find(nsName);
        }
        if (ns == null) throw new IllegalStateException("Namespace not found for gen-class: " + nsName);
        Var v = ns.findInternedVar(Symbol.intern(fnName));
        if (v == null || !v.isBound()) {
            throw new UnsupportedOperationException("gen-class function not defined: " + nsName + "/" + fnName);
        }
        return v;
    }

    public static Object dispatchInit(String nsName, String fnName, Object[] args) {
        Var v = resolveImplVar(nsName, fnName);
        Object[] boxed = boxArgs(args);
        Object res = ((Fn) v.deref()).applyTo(boxed);
        if (res instanceof PersistentVector pv && pv.count() >= 2) {
            return pv.nth(1);
        }
        return res;
    }

    public static void dispatchPostInit(String nsName, String fnName, Object self, Object[] args) {
        Var v = resolveImplVar(nsName, fnName);
        Object[] callArgs = new Object[1 + args.length];
        callArgs[0] = self;
        for (int i = 0; i < args.length; i++) callArgs[i + 1] = Interop.boxResult(args[i]);
        ((Fn) v.deref()).applyTo(callArgs);
    }

    public static Object dispatchInstance(String nsName, String fnName, Object self, Object[] args) {
        Var v = resolveImplVar(nsName, fnName);
        Object[] callArgs = new Object[1 + args.length];
        callArgs[0] = self;
        for (int i = 0; i < args.length; i++) callArgs[i + 1] = Interop.boxResult(args[i]);
        return ((Fn) v.deref()).applyTo(callArgs);
    }

    public static Object dispatchStatic(String nsName, String fnName, Object[] args) {
        Var v = resolveImplVar(nsName, fnName);
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

    private static String mungeClassName(String s) {
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
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(typeDesc(p));
        sb.append(')').append(typeDesc(ret));
        return sb.toString();
    }

    // ========================================================================
    // Minimal Self-Contained v49.0 Classfile Writer for GenClass
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
                    code.write(0x16); code.write(slot); // lload
                    int m = cpMethodref("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 2;
                } else if (pt == double.class) {
                    code.write(0x18); code.write(slot); // dload
                    int m = cpMethodref("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 2;
                } else if (pt == int.class || pt == short.class || pt == byte.class) {
                    code.write(0x15); code.write(slot); // iload
                    int m = cpMethodref("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else if (pt == float.class) {
                    code.write(0x17); code.write(slot); // fload
                    int m = cpMethodref("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else if (pt == boolean.class) {
                    code.write(0x15); code.write(slot); // iload
                    int m = cpMethodref("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else if (pt == char.class) {
                    code.write(0x15); code.write(slot); // iload
                    int m = cpMethodref("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;");
                    code.write(0xb8); code.write(m >> 8); code.write(m);
                    slot += 1;
                } else {
                    code.write(0x19); code.write(slot); // aload
                    slot += 1;
                }
                code.write(0x53); // aastore
            }
        }

        private void emitUnboxAndReturn(ByteArrayOutputStream code, Class<?> retType) {
            if (retType == void.class) {
                code.write(0x57); // pop
                code.write(0xb1); // return
                return;
            }
            if (retType.isPrimitive()) {
                int m = cpMethodref("cljl/TypeFactory", "coerceReturn", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;");
                // Simpler: convert directly via Numbers / RT
                if (retType == boolean.class) {
                    int tr = cpMethodref("cljl/RT", "truthy", "(Ljava/lang/Object;)Z");
                    code.write(0xb8); code.write(tr >> 8); code.write(tr);
                    code.write(0xac); // ireturn
                } else if (retType == long.class) {
                    int nl = cpMethodref("cljl/Numbers", "toLong", "(Ljava/lang/Object;)J");
                    code.write(0xb8); code.write(nl >> 8); code.write(nl);
                    code.write(0xad); // lreturn
                } else if (retType == int.class) {
                    int nl = cpMethodref("cljl/Numbers", "intCast", "(Ljava/lang/Object;)I");
                    code.write(0xb8); code.write(nl >> 8); code.write(nl);
                    code.write(0xac); // ireturn
                } else if (retType == double.class) {
                    int nd = cpMethodref("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D");
                    code.write(0xb8); code.write(nd >> 8); code.write(nd);
                    code.write(0xaf); // dreturn
                } else if (retType == float.class) {
                    int nd = cpMethodref("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D");
                    code.write(0xb8); code.write(nd >> 8); code.write(nd);
                    code.write(0x90); // d2f
                    code.write(0xae); // freturn
                } else if (retType == short.class) {
                    int nl = cpMethodref("cljl/Numbers", "shortCast", "(Ljava/lang/Object;)S");
                    code.write(0xb8); code.write(nl >> 8); code.write(nl);
                    code.write(0xac);
                } else if (retType == byte.class) {
                    int nl = cpMethodref("cljl/Numbers", "byteCast", "(Ljava/lang/Object;)B");
                    code.write(0xb8); code.write(nl >> 8); code.write(nl);
                    code.write(0xac);
                } else if (retType == char.class) {
                    int nl = cpMethodref("cljl/Numbers", "charCast", "(Ljava/lang/Object;)C");
                    code.write(0xb8); code.write(nl >> 8); code.write(nl);
                    code.write(0xac);
                }
                return;
            }
            if (retType != Object.class) {
                int cls = cpClass(retType.isArray() ? retType.getName().replace('.', '/') : retType.getName().replace('.', '/'));
                code.write(0xc0); code.write(cls >> 8); code.write(cls); // checkcast
            }
            code.write(0xb0); // areturn
        }

        void emitCtor(
                String implNsName, String prefix, String initName, String postInitName,
                String stateField, String superInternal, Class<?>[] pTypes, Class<?>[] superPTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            code.write(0x2a); // aload_0
            // Pass matching prefix of args to super if superPTypes matches pTypes prefix
            int slot = 1;
            for (int i = 0; i < superPTypes.length && i < pTypes.length; i++) {
                Class<?> pt = pTypes[i];
                if (pt == long.class) { code.write(0x16); code.write(slot); slot += 2; }
                else if (pt == double.class) { code.write(0x18); code.write(slot); slot += 2; }
                else if (pt == int.class || pt == short.class || pt == byte.class || pt == boolean.class || pt == char.class) {
                    code.write(0x15); code.write(slot); slot += 1;
                } else if (pt == float.class) { code.write(0x17); code.write(slot); slot += 1; }
                else { code.write(0x19); code.write(slot); slot += 1; }
            }
            int superCtor = cpMethodref(superInternal, "<init>", methodDesc(void.class, superPTypes));
            code.write(0xb7); code.write(superCtor >> 8); code.write(superCtor); // invokespecial

            if (initName != null && stateField != null) {
                code.write(0x2a); // aload_0
                emitLdc(code, cpString(implNsName));
                emitLdc(code, cpString(prefix + initName));
                emitPackArgs(code, pTypes, 1);
                int disp = cpMethodref("cljl/GenClass", "dispatchInit", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
                int fref = cpFieldref(thisInternal, stateField, "Ljava/lang/Object;");
                code.write(0xb5); code.write(fref >> 8); code.write(fref); // putfield
            } else if (stateField != null) {
                code.write(0x2a); // aload_0
                code.write(0x01); // aconst_null
                int fref = cpFieldref(thisInternal, stateField, "Ljava/lang/Object;");
                code.write(0xb5); code.write(fref >> 8); code.write(fref);
            }

            if (postInitName != null) {
                emitLdc(code, cpString(implNsName));
                emitLdc(code, cpString(prefix + postInitName));
                code.write(0x2a); // aload_0
                emitPackArgs(code, pTypes, 1);
                int disp = cpMethodref("cljl/GenClass", "dispatchPostInit", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
            }

            code.write(0xb1); // return
            int maxLocals = 1;
            for (Class<?> pt : pTypes) maxLocals += (pt == long.class || pt == double.class) ? 2 : 1;
            addMethod(0x0001, "<init>", methodDesc(void.class, pTypes), 12, maxLocals + 2, code.toByteArray());
        }

        void emitMethod(String implNsName, String prefix, String mName, boolean isStatic, Class<?> retType, Class<?>[] pTypes) {
            ByteArrayOutputStream code = new ByteArrayOutputStream();
            emitLdc(code, cpString(implNsName));
            emitLdc(code, cpString(prefix + mName));
            if (!isStatic) {
                code.write(0x2a); // aload_0
                emitPackArgs(code, pTypes, 1);
                int disp = cpMethodref("cljl/GenClass", "dispatchInstance", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
            } else {
                emitPackArgs(code, pTypes, 0);
                int disp = cpMethodref("cljl/GenClass", "dispatchStatic", "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;");
                code.write(0xb8); code.write(disp >> 8); code.write(disp);
            }
            emitUnboxAndReturn(code, retType);
            int maxLocals = isStatic ? 0 : 1;
            for (Class<?> pt : pTypes) maxLocals += (pt == long.class || pt == double.class) ? 2 : 1;
            int flags = isStatic ? 0x0009 : 0x0001;
            addMethod(flags, mName, methodDesc(retType, pTypes), 12, maxLocals + 2, code.toByteArray());
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
