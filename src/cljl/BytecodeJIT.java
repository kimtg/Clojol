package cljl;

import cljl.Compiler.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier-1 JVM Bytecode JIT Compiler for ClojureLite.
 *
 * Compiles hot CompiledFn instances directly to JVM classfile (v49.0) bytecode at runtime
 * with zero external dependencies, turning Clojure locals into JVM local variable slots,
 * loop/recur into direct JVM goto loops, and core arithmetic/comparisons into inlineable
 * fast paths. Cold code stays on the zero-startup-overhead AST interpreter.
 */
public final class BytecodeJIT {

    private static final AtomicInteger CLASS_ID = new AtomicInteger(0);
    private static final JitClassLoader LOADER = new JitClassLoader();

    // JVM Opcodes
    private static final int ACONST_NULL = 0x01;
    private static final int ICONST_0 = 0x03;
    private static final int ICONST_1 = 0x04;
    private static final int BIPUSH = 0x10;
    private static final int SIPUSH = 0x11;
    private static final int LDC = 0x12;
    private static final int LDC_W = 0x13;
    private static final int LDC2_W = 0x14;
    private static final int ALOAD = 0x19;
    private static final int ALOAD_0 = 0x2a;
    private static final int ALOAD_1 = 0x2b;
    private static final int ALOAD_2 = 0x2c;
    private static final int ALOAD_3 = 0x2d;
    private static final int AALOAD = 0x32;
    private static final int ASTORE = 0x3a;
    private static final int AASTORE = 0x53;
    private static final int POP = 0x57;
    private static final int DUP = 0x59;
    private static final int IXOR = 0x82;
    private static final int IFEQ = 0x99;
    private static final int IFNE = 0x9a;
    private static final int IF_ICMPEQ = 0x9f;
    private static final int IF_ICMPNE = 0xa0;
    private static final int IF_ACMPEQ = 0xa5;
    private static final int IF_ACMPNE = 0xa6;
    private static final int GOTO = 0xa7;
    private static final int TABLESWITCH = 0xaa;
    private static final int ARETURN = 0xb0;
    private static final int RETURN = 0xb1;
    private static final int GETSTATIC = 0xb2;
    private static final int GETFIELD = 0xb4;
    private static final int PUTFIELD = 0xb5;
    private static final int INVOKEVIRTUAL = 0xb6;
    private static final int INVOKESPECIAL = 0xb7;
    private static final int INVOKESTATIC = 0xb8;
    private static final int ANEWARRAY = 0xbd;
    private static final int ATHROW = 0xbf;
    private static final int CHECKCAST = 0xc0;
    private static final int MONITORENTER = 0xc2;
    private static final int MONITOREXIT = 0xc3;
    private static final int IFNULL = 0xc6;
    private static final int IFNONNULL = 0xc7;

    private BytecodeJIT() {}

    private static final class JitClassLoader extends ClassLoader {
        JitClassLoader() {
            super(BytecodeJIT.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytecode) {
            return defineClass(binaryName, bytecode, 0, bytecode.length);
        }
    }

    public static boolean canCompile(CompiledFn cf) {
        FnMethod[] fixed = cf.getFixedMethods();
        for (FnMethod m : fixed) {
            if (m != null && !canCompileNode(m.body, true)) return false;
        }
        FnMethod variadic = cf.getVariadicMethod();
        if (variadic != null && !canCompileNode(variadic.body, true)) return false;
        return true;
    }

    private static boolean canCompileNode(Node n, boolean stackEmpty) {
        if (n == null) return true;
        if (n instanceof ConstNode || n instanceof LocalNode || n instanceof VarRefNode
                || n instanceof Recur0Node || n instanceof StaticFieldNode) {
            return true;
        }
        if (n instanceof TryNode) {
            return false;
        }
        if (n instanceof MonitorNode mn) {
            return canCompileNode(mn.target, false);
        }
        if (n instanceof LockingNode ln) {
            if (!stackEmpty) return false;
            return canCompileNode(ln.target, true) && canCompileNode(ln.body, true);
        }
        if (n instanceof CaseNode cn) {
            if (!canCompileNode(cn.expr, stackEmpty)) return false;
            if (cn.hasDefault && !canCompileNode(cn.defaultNode, stackEmpty)) return false;
            for (Node b : cn.table.values()) {
                if (!canCompileNode(b, stackEmpty)) return false;
            }
            return true;
        }
        if (n instanceof IfNode iff) {
            return canCompileNode(iff.test, stackEmpty)
                    && canCompileNode(iff.thenNode, stackEmpty)
                    && canCompileNode(iff.elseNode, stackEmpty);
        }
        if (n instanceof DoNode dn) {
            for (Node c : dn.nodes) if (!canCompileNode(c, stackEmpty)) return false;
            return true;
        }
        if (n instanceof LetNode ln) {
            for (Node i : ln.inits) if (!canCompileNode(i, stackEmpty)) return false;
            return canCompileNode(ln.body, stackEmpty);
        }
        if (n instanceof LoopNode lp) {
            for (Node i : lp.inits) if (!canCompileNode(i, stackEmpty)) return false;
            return canCompileNode(lp.body, stackEmpty);
        }
        if (n instanceof Recur1Node r1) return canCompileNode(r1.expr0, false);
        if (n instanceof Recur2Node r2) return canCompileNode(r2.expr0, false) && canCompileNode(r2.expr1, false);
        if (n instanceof RecurNNode rn) {
            for (Node e : rn.exprs) if (!canCompileNode(e, false)) return false;
            return true;
        }
        if (n instanceof VectorNode vn) {
            for (Node e : vn.elems) if (!canCompileNode(e, false)) return false;
            return true;
        }
        if (n instanceof MapNode mn) {
            for (Node e : mn.kvs) if (!canCompileNode(e, false)) return false;
            return true;
        }
        if (n instanceof SetNode sn) {
            for (Node e : sn.elems) if (!canCompileNode(e, false)) return false;
            return true;
        }
        if (n instanceof DefNode dn) return canCompileNode(dn.init, false);
        if (n instanceof DefMacroNode dm) return canCompileNode(dm.fnNode, false);
        if (n instanceof FnNode) return true;
        if (n instanceof VarCall0Node) return true;
        if (n instanceof VarCall1Node c) return canCompileNode(c.a, false);
        if (n instanceof VarCall2Node c) return canCompileNode(c.a, false) && canCompileNode(c.b, false);
        if (n instanceof VarCall3Node c) return canCompileNode(c.a, false) && canCompileNode(c.b, false) && canCompileNode(c.c, false);
        if (n instanceof VarCall4Node c) return canCompileNode(c.a, false) && canCompileNode(c.b, false) && canCompileNode(c.c, false) && canCompileNode(c.d, false);
        if (n instanceof VarCallNNode c) {
            for (Node a : c.args) if (!canCompileNode(a, false)) return false;
            return true;
        }
        if (n instanceof Call0Node c) return canCompileNode(c.fn, false);
        if (n instanceof Call1Node c) return canCompileNode(c.fn, false) && canCompileNode(c.a, false);
        if (n instanceof Call2Node c) return canCompileNode(c.fn, false) && canCompileNode(c.a, false) && canCompileNode(c.b, false);
        if (n instanceof Call3Node c) return canCompileNode(c.fn, false) && canCompileNode(c.a, false) && canCompileNode(c.b, false) && canCompileNode(c.c, false);
        if (n instanceof Call4Node c) return canCompileNode(c.fn, false) && canCompileNode(c.a, false) && canCompileNode(c.b, false) && canCompileNode(c.c, false) && canCompileNode(c.d, false);
        if (n instanceof CallNNode c) {
            if (!canCompileNode(c.fn, false)) return false;
            for (Node a : c.args) if (!canCompileNode(a, false)) return false;
            return true;
        }
        if (n instanceof Add2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Sub2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Mul2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof NegateNode a) return canCompileNode(a.a, false);
        if (n instanceof IncNode a) return canCompileNode(a.a, false);
        if (n instanceof DecNode a) return canCompileNode(a.a, false);
        if (n instanceof Rem2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Quot2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Lt2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Lte2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Gt2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Gte2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Equiv2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof NumEq2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof ZeroPNode a) return canCompileNode(a.a, false);
        if (n instanceof PosPNode a) return canCompileNode(a.a, false);
        if (n instanceof NegPNode a) return canCompileNode(a.a, false);
        if (n instanceof NilPNode a) return canCompileNode(a.a, false);
        if (n instanceof NotNode a) return canCompileNode(a.a, false);
        if (n instanceof IdenticalNode a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof ThrowNode a) return canCompileNode(a.expr, false);
        if (n instanceof NewNode a) {
            for (Node arg : a.args) if (!canCompileNode(arg, false)) return false;
            return true;
        }
        if (n instanceof StaticMethodNode a) {
            for (Node arg : a.args) if (!canCompileNode(arg, false)) return false;
            return true;
        }
        if (n instanceof InstanceFieldNode a) return canCompileNode(a.target, false);
        if (n instanceof InstanceMethodNode a) {
            if (!canCompileNode(a.target, false)) return false;
            for (Node arg : a.args) if (!canCompileNode(arg, false)) return false;
            return true;
        }
        if (n instanceof SetVarNode a) return canCompileNode(a.val, false);
        if (n instanceof SetFieldNode a) return canCompileNode(a.target, false) && canCompileNode(a.val, false);
        if (n instanceof SetStaticFieldNode a) return canCompileNode(a.val, false);
        return false;
    }

    public static Fn compile(CompiledFn cf) {
        if (!canCompile(cf)) return null;
        try {
            int id = CLASS_ID.incrementAndGet();
            String internalName = "cljl/jit/Fn$" + id;
            String binaryName = "cljl.jit.Fn$" + id;

            ClassEmitter ce = new ClassEmitter(internalName);
            emitMethods(ce, cf);
            byte[] bytecode = ce.toByteArray();

            Class<?> cls = LOADER.define(binaryName, bytecode);
            Constructor<?> ctor = cls.getConstructor(Object[].class, Object[][].class, String.class);
            return (Fn) ctor.newInstance(ce.constObjects.toArray(), cf.getCaptured(), cf.getName());
        } catch (Throwable t) {
            return null;
        }
    }

    // Helper methods called from generated bytecode
    public static RuntimeException throwObj(Object ex) {
        if (ex instanceof Throwable t) throw RT.sneakyThrow(t);
        throw new RuntimeException(String.valueOf(ex));
    }

    public static Fn instantiateFn(FnNode fnNode, Object[][] captured) {
        return new CompiledFn(fnNode.name, fnNode.isNamed, fnNode.fixedMethods, fnNode.variadicMethod, captured);
    }

    public static Object[] emptyArray() {
        return Fn.EMPTY_ARGS;
    }

    public static int caseLookup(Object val, Map<RT.EquivKey, Integer> table) {
        Integer idx = table.get(new RT.EquivKey(val));
        return idx != null ? idx : -1;
    }

    public static int caseLookupOrThrow(Object val, Map<RT.EquivKey, Integer> table) {
        Integer idx = table.get(new RT.EquivKey(val));
        if (idx != null) return idx;
        throw new IllegalArgumentException("No matching clause: " + val);
    }

    public static RuntimeException throwNoMatchingClause(Object val) {
        throw new IllegalArgumentException("No matching clause: " + val);
    }

    public static Object requireNonNullLock(Object lock) {
        if (lock == null) throw new NullPointerException("Cannot lock nil");
        return lock;
    }

    private static void emitMethods(ClassEmitter ce, CompiledFn cf) {
        FnMethod[] fixed = cf.getFixedMethods();
        FnMethod variadic = cf.getVariadicMethod();
        ce.fixedMethods = fixed;

        // Emit invoke()..invoke(Object,Object,Object,Object) for fixed arities 0..4
        for (int arity = 0; arity <= 4; arity++) {
            FnMethod m = fixed[arity];
            if (m != null) {
                emitFixedInvoke(ce, m, arity, arity);
            }
        }

        // Emit invokeVarargs([Ljava/lang/Object;)Ljava/lang/Object; if needed
        boolean hasHighFixed = false;
        for (int i = 5; i < fixed.length; i++) {
            if (fixed[i] != null) {
                hasHighFixed = true;
                break;
            }
        }
        if (hasHighFixed || variadic != null) {
            emitVarargsInvoke(ce, fixed, variadic);
        }
    }

    private static void emitFixedInvoke(ClassEmitter ce, FnMethod m, int arity, int methodCaptureIdx) {
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append("Ljava/lang/Object;");
        desc.append(")Ljava/lang/Object;");

        CodeGen cg = ce.beginMethod("invoke", desc.toString(), 1 + arity + m.maxSlots);
        cg.selfSlot = m.selfSlot;
        int baseSlot = 1 + arity;

        // Initialize selfSlot if present
        if (m.selfSlot != -1) {
            cg.aload(0);
            cg.astore(baseSlot + m.selfSlot);
        }
        // Initialize captured locals
        if (m.capturedLocalSlots.length > 0) {
            cg.aload(0);
            cg.getfield(ce.className, "captured", "[[Ljava/lang/Object;");
            cg.iconst(methodCaptureIdx);
            cg.aaload();
            for (int i = 0; i < m.capturedLocalSlots.length; i++) {
                if (i + 1 < m.capturedLocalSlots.length) cg.dup();
                cg.iconst(i);
                cg.aaload();
                cg.astore(baseSlot + m.capturedLocalSlots[i]);
            }
        }
        // Copy parameters into local slots
        for (int i = 0; i < arity; i++) {
            cg.aload(1 + i);
            cg.astore(baseSlot + m.paramSlots[i]);
        }

        Label fnLoopStart = new Label();
        cg.markLabel(fnLoopStart);
        cg.pushLoopTarget(m.paramSlots, fnLoopStart);
        boolean terminated = cg.emitBoxed(m.body, baseSlot);
        cg.popLoopTarget();
        if (!terminated) {
            cg.areturn();
        }
        cg.finish();
    }

    private static void emitVarargsInvoke(ClassEmitter ce, FnMethod[] fixed, FnMethod variadic) {
        int maxMethodSlots = 0;
        for (FnMethod m : fixed) if (m != null && m.maxSlots > maxMethodSlots) maxMethodSlots = m.maxSlots;
        if (variadic != null && variadic.maxSlots > maxMethodSlots) maxMethodSlots = variadic.maxSlots;

        CodeGen cg = ce.beginMethod("invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 3 + maxMethodSlots);
        // local 0: this, local 1: Object[] args, local 2: int len
        int lenLocal = 2;
        int baseSlot = 3;

        cg.aload(1);
        cg.arraylength();
        cg.istore(lenLocal);

        for (int i = 0; i < fixed.length; i++) {
            FnMethod m = fixed[i];
            if (m == null) continue;
            Label nextCheck = new Label();
            cg.iload(lenLocal);
            cg.iconst(i);
            cg.ifIcmpNe(nextCheck);

            if (i <= 4) {
                cg.aload(0);
                for (int j = 0; j < i; j++) {
                    cg.aload(1);
                    cg.iconst(j);
                    cg.aaload();
                }
                StringBuilder desc = new StringBuilder("(");
                for (int j = 0; j < i; j++) desc.append("Ljava/lang/Object;");
                desc.append(")Ljava/lang/Object;");
                cg.invokevirtual(ce.className, "invoke", desc.toString(), 1 + i, 1);
                cg.areturn();
            } else {
                emitVarargsMethodBody(ce, cg, m, i, baseSlot, false);
            }
            cg.markLabel(nextCheck);
            cg.curStack = 0;
        }

        if (variadic != null) {
            Label errLabel = new Label();
            cg.iload(lenLocal);
            cg.iconst(variadic.fixedArity);
            cg.ifIcmpLt(errLabel);
            emitVarargsMethodBody(ce, cg, variadic, fixed.length, baseSlot, true);
            cg.markLabel(errLabel);
            cg.curStack = 0;
        }

        // Throw arity error
        cg.aload(0);
        cg.iload(lenLocal);
        cg.invokevirtual("cljl/Fn", "arityError", "(I)Ljava/lang/RuntimeException;", 2, 1);
        cg.athrow();
        cg.finish();
    }

    private static void emitVarargsMethodBody(
            ClassEmitter ce, CodeGen cg, FnMethod m, int captureIdx, int baseSlot, boolean isVariadic) {
        int prevSelf = cg.selfSlot;
        cg.selfSlot = m.selfSlot;
        if (m.selfSlot != -1) {
            cg.aload(0);
            cg.astore(baseSlot + m.selfSlot);
        }
        if (m.capturedLocalSlots.length > 0) {
            cg.aload(0);
            cg.getfield(ce.className, "captured", "[[Ljava/lang/Object;");
            cg.iconst(captureIdx);
            cg.aaload();
            for (int i = 0; i < m.capturedLocalSlots.length; i++) {
                if (i + 1 < m.capturedLocalSlots.length) cg.dup();
                cg.iconst(i);
                cg.aaload();
                cg.astore(baseSlot + m.capturedLocalSlots[i]);
            }
        }
        for (int i = 0; i < m.fixedArity; i++) {
            cg.aload(1);
            cg.iconst(i);
            cg.aaload();
            cg.astore(baseSlot + m.paramSlots[i]);
        }
        if (isVariadic) {
            cg.aload(1);
            cg.iconst(m.fixedArity);
            cg.invokestatic("cljl/lang/PersistentList", "createFromArraySlice",
                    "([Ljava/lang/Object;I)Lcljl/lang/ISeq;", 2, 1);
            // If empty list returned for 0 rest args, Clojure passes nil
            // Note: createFromArraySlice returns EMPTY when offset >= len; Clojure rest param is nil when no rest args
            Label nonEmpty = new Label();
            Label doneRest = new Label();
            cg.iload(2);
            cg.iconst(m.fixedArity);
            cg.ifIcmpGt(nonEmpty);
            cg.pop();
            cg.aconstNull();
            cg.gotoLabel(doneRest);
            cg.markLabel(nonEmpty);
            cg.markLabel(doneRest);
            cg.astore(baseSlot + m.paramSlots[m.fixedArity]);
        }

        Label loopStart = new Label();
        cg.markLabel(loopStart);
        cg.pushLoopTarget(m.paramSlots, loopStart);
        boolean terminated = cg.emitBoxed(m.body, baseSlot);
        cg.popLoopTarget();
        if (!terminated) {
            cg.areturn();
        }
        cg.selfSlot = prevSelf;
    }

    // ========================================================================
    // Minimal JVM ClassFile Emitter (Version 49.0 — no StackMapTable required)
    // ========================================================================

    private static final class Label {
        int pos = -1;
        final List<int[]> fixups = new ArrayList<>(); // {opcodePos, operandPos}
        final List<int[]> wideFixups = new ArrayList<>(); // {opcodePos, operandPos} for 32-bit offsets
    }

    private record LoopTarget(int[] slots, Label start) {}

    private record ExceptionEntry(Label start, Label end, Label handler, int catchTypeCpIdx) {}

    private static final class ClassEmitter {
        final String className;
        FnMethod[] fixedMethods;
        final ConstantPool cp = new ConstantPool();
        final List<Object> constObjects = new ArrayList<>();
        final IdentityHashMap<Object, Integer> constIndexMap = new IdentityHashMap<>();
        final List<byte[]> methodInfos = new ArrayList<>();

        final int thisClassIdx;
        final int superClassIdx;
        final int codeAttrNameIdx;
        final int constsFieldIdx;
        final int capturedFieldIdx;
        final int nameFieldIdx;

        ClassEmitter(String className) {
            this.className = className;
            this.thisClassIdx = cp.addClass(className);
            this.superClassIdx = cp.addClass("cljl/Fn");
            this.codeAttrNameIdx = cp.addUtf8("Code");
            this.constsFieldIdx = cp.addFieldref(className, "consts", "[Ljava/lang/Object;");
            this.capturedFieldIdx = cp.addFieldref(className, "captured", "[[Ljava/lang/Object;");
            this.nameFieldIdx = cp.addFieldref(className, "name", "Ljava/lang/String;");

            emitConstructor();
            emitGetName();
        }

        int addConstObject(Object obj) {
            Integer existing = constIndexMap.get(obj);
            if (existing != null) return existing;
            int idx = constObjects.size();
            constObjects.add(obj);
            constIndexMap.put(obj, idx);
            return idx;
        }

        CodeGen beginMethod(String name, String desc, int maxLocals) {
            return new CodeGen(this, name, desc, maxLocals);
        }

        private void emitConstructor() {
            CodeGen cg = beginMethod("<init>", "([Ljava/lang/Object;[[Ljava/lang/Object;Ljava/lang/String;)V", 4);
            cg.aload(0);
            cg.invokespecial("cljl/Fn", "<init>", "()V", 1, 0);
            cg.aload(0);
            cg.aload(1);
            cg.putfield(className, "consts", "[Ljava/lang/Object;");
            cg.aload(0);
            cg.aload(2);
            cg.putfield(className, "captured", "[[Ljava/lang/Object;");
            cg.aload(0);
            cg.aload(3);
            cg.putfield(className, "name", "Ljava/lang/String;");
            cg.vreturn();
            cg.finish();
        }

        private void emitGetName() {
            CodeGen cg = beginMethod("getName", "()Ljava/lang/String;", 1);
            cg.aload(0);
            cg.getfield(className, "name", "Ljava/lang/String;");
            cg.areturn();
            cg.finish();
        }

        byte[] toByteArray() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            DataOutputStream out = new DataOutputStream(baos);

            out.writeInt(0xCAFEBABE);
            out.writeShort(0);   // minor
            out.writeShort(49);  // major (Java 5 — standard verifier, no StackMapTable needed)

            cp.writeTo(out);

            out.writeShort(0x0011); // ACC_PUBLIC | ACC_FINAL
            out.writeShort(thisClassIdx);
            out.writeShort(superClassIdx);
            out.writeShort(0); // interfaces count

            // 3 fields: consts, captured, name
            out.writeShort(3);
            writeField(out, 0x0012, "consts", "[Ljava/lang/Object;");   // private final
            writeField(out, 0x0012, "captured", "[[Ljava/lang/Object;");
            writeField(out, 0x0012, "name", "Ljava/lang/String;");

            // methods
            out.writeShort(methodInfos.size());
            for (byte[] m : methodInfos) {
                out.write(m);
            }

            out.writeShort(0); // class attributes count
            out.flush();
            return baos.toByteArray();
        }

        private void writeField(DataOutputStream out, int flags, String name, String desc) throws IOException {
            out.writeShort(flags);
            out.writeShort(cp.addUtf8(name));
            out.writeShort(cp.addUtf8(desc));
            out.writeShort(0);
        }
    }

    private static final class ConstantPool {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        private int count = 1;
        private final Map<String, Integer> utf8Map = new HashMap<>();
        private final Map<String, Integer> classMap = new HashMap<>();
        private final Map<String, Integer> strMap = new HashMap<>();
        private final Map<String, Integer> natMap = new HashMap<>();
        private final Map<String, Integer> refMap = new HashMap<>();
        private final Map<Integer, Integer> intMap = new HashMap<>();
        private final Map<Long, Integer> longMap = new HashMap<>();
        private final Map<Double, Integer> doubleMap = new HashMap<>();

        int addUtf8(String s) {
            return utf8Map.computeIfAbsent(s, k -> {
                try {
                    ByteArrayOutputStream tmp = new ByteArrayOutputStream();
                    DataOutputStream d = new DataOutputStream(tmp);
                    d.writeByte(1);
                    d.writeUTF(k);
                    buf.write(tmp.toByteArray());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return count++;
            });
        }

        int addClass(String internalName) {
            return classMap.computeIfAbsent(internalName, k -> {
                int nameIdx = addUtf8(k);
                buf.write(7);
                writeShort(nameIdx);
                return count++;
            });
        }

        int addString(String s) {
            return strMap.computeIfAbsent(s, k -> {
                int utfIdx = addUtf8(k);
                buf.write(8);
                writeShort(utfIdx);
                return count++;
            });
        }

        int addInteger(int v) {
            return intMap.computeIfAbsent(v, k -> {
                buf.write(3);
                buf.write((k >>> 24) & 0xff);
                buf.write((k >>> 16) & 0xff);
                buf.write((k >>> 8) & 0xff);
                buf.write(k & 0xff);
                return count++;
            });
        }

        int addLong(long v) {
            return longMap.computeIfAbsent(v, k -> {
                buf.write(5);
                writeLong(k);
                int idx = count;
                count += 2;
                return idx;
            });
        }

        int addDouble(double v) {
            return doubleMap.computeIfAbsent(v, k -> {
                buf.write(6);
                writeLong(Double.doubleToRawLongBits(k));
                int idx = count;
                count += 2;
                return idx;
            });
        }

        int addNameAndType(String name, String desc) {
            String key = name + "\0" + desc;
            return natMap.computeIfAbsent(key, k -> {
                int n = addUtf8(name);
                int d = addUtf8(desc);
                buf.write(12);
                writeShort(n);
                writeShort(d);
                return count++;
            });
        }

        int addFieldref(String owner, String name, String desc) {
            return addRef(9, owner, name, desc);
        }

        int addMethodref(String owner, String name, String desc) {
            return addRef(10, owner, name, desc);
        }

        private int addRef(int tag, String owner, String name, String desc) {
            String key = tag + "\0" + owner + "\0" + name + "\0" + desc;
            return refMap.computeIfAbsent(key, k -> {
                int c = addClass(owner);
                int nat = addNameAndType(name, desc);
                buf.write(tag);
                writeShort(c);
                writeShort(nat);
                return count++;
            });
        }

        private void writeShort(int v) {
            buf.write((v >>> 8) & 0xff);
            buf.write(v & 0xff);
        }

        private void writeLong(long v) {
            for (int i = 56; i >= 0; i -= 8) {
                buf.write((int) ((v >>> i) & 0xff));
            }
        }

        void writeTo(DataOutputStream out) throws IOException {
            out.writeShort(count);
            out.write(buf.toByteArray());
        }
    }

    private static final class CodeGen {
        final ClassEmitter ce;
        final int nameIdx;
        final int descIdx;
        final int maxLocals;
        final ByteArrayOutputStream code = new ByteArrayOutputStream(256);
        final Deque<LoopTarget> loopTargets = new ArrayDeque<>();
        final List<ExceptionEntry> exceptionTable = new ArrayList<>();
        int curTempLocals = 0;
        int maxTempLocals = 0;
        int selfSlot = -1;
        int curStack = 0;
        int maxStack = 0;

        CodeGen(ClassEmitter ce, String name, String desc, int maxLocals) {
            this.ce = ce;
            this.nameIdx = ce.cp.addUtf8(name);
            this.descIdx = ce.cp.addUtf8(desc);
            this.maxLocals = maxLocals;
        }

        int allocTempLocal() {
            int slot = maxLocals + curTempLocals++;
            if (curTempLocals > maxTempLocals) maxTempLocals = curTempLocals;
            return slot;
        }

        void freeTempLocals(int n) {
            curTempLocals -= n;
        }

        void pushLoopTarget(int[] slots, Label start) {
            loopTargets.push(new LoopTarget(slots, start));
        }

        void popLoopTarget() {
            loopTargets.pop();
        }

        void adjStack(int delta) {
            curStack += delta;
            if (curStack > maxStack) maxStack = curStack;
        }

        void markLabel(Label l) {
            l.pos = code.size();
            allLabels.add(l);
        }

        private void emitJump(int opcode, Label target) {
            int opcodePos = code.size();
            code.write(opcode);
            adjStack(opcode == GOTO ? 0 : (opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE ? -2 : -1));
            int operandPos = code.size();
            code.write(0);
            code.write(0);
            target.fixups.add(new int[]{opcodePos, operandPos});
        }

        private void writeInt32(int v) {
            code.write((v >>> 24) & 0xff);
            code.write((v >>> 16) & 0xff);
            code.write((v >>> 8) & 0xff);
            code.write(v & 0xff);
        }

        void tableswitch(int low, int high, Label defaultLabel, Label[] targets) {
            int opcodePos = code.size();
            code.write(TABLESWITCH);
            adjStack(-1);
            while ((code.size() & 3) != 0) {
                code.write(0);
            }
            defaultLabel.wideFixups.add(new int[]{opcodePos, code.size()});
            writeInt32(0);
            writeInt32(low);
            writeInt32(high);
            for (Label t : targets) {
                t.wideFixups.add(new int[]{opcodePos, code.size()});
                writeInt32(0);
            }
        }

        void gotoLabel(Label l) { emitJump(GOTO, l); }
        void ifeq(Label l) { emitJump(IFEQ, l); }
        void ifne(Label l) { emitJump(IFNE, l); }
        void ifIcmpNe(Label l) { emitJump(IF_ICMPNE, l); }
        void ifIcmpGt(Label l) {
            int opcodePos = code.size();
            code.write(0xa3); // IF_ICMPGT
            adjStack(-2);
            int operandPos = code.size();
            code.write(0); code.write(0);
            l.fixups.add(new int[]{opcodePos, operandPos});
        }
        void ifIcmpLt(Label l) {
            int opcodePos = code.size();
            code.write(0xa1); // IF_ICMPLT
            adjStack(-2);
            int operandPos = code.size();
            code.write(0); code.write(0);
            l.fixups.add(new int[]{opcodePos, operandPos});
        }
        void ifnull(Label l) { emitJump(IFNULL, l); }
        void ifnonnull(Label l) { emitJump(IFNONNULL, l); }
        void ifAcmpNe(Label l) { emitJump(IF_ACMPNE, l); }

        void aconstNull() { code.write(ACONST_NULL); adjStack(1); }
        void iconst(int v) {
            if (v >= -1 && v <= 5) {
                code.write(ICONST_0 + v);
            } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                code.write(BIPUSH);
                code.write(v & 0xff);
            } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                code.write(SIPUSH);
                code.write((v >>> 8) & 0xff);
                code.write(v & 0xff);
            } else {
                int idx = ce.cp.addInteger(v);
                if (idx <= 255) {
                    code.write(LDC);
                    code.write(idx);
                } else {
                    code.write(LDC_W);
                    code.write((idx >>> 8) & 0xff);
                    code.write(idx & 0xff);
                }
            }
            adjStack(1);
        }

        void ldcString(String s) {
            int idx = ce.cp.addString(s);
            if (idx <= 255) {
                code.write(LDC);
                code.write(idx);
            } else {
                code.write(LDC_W);
                code.write((idx >>> 8) & 0xff);
                code.write(idx & 0xff);
            }
            adjStack(1);
        }

        void ldcLong(long v) {
            int idx = ce.cp.addLong(v);
            code.write(LDC2_W);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(2);
        }

        void ldcDouble(double v) {
            int idx = ce.cp.addDouble(v);
            code.write(LDC2_W);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(2);
        }

        void aload(int idx) {
            if (idx <= 3) {
                code.write(ALOAD_0 + idx);
            } else if (idx <= 255) {
                code.write(ALOAD);
                code.write(idx);
            } else {
                code.write(0xc4); // WIDE
                code.write(ALOAD);
                code.write((idx >>> 8) & 0xff);
                code.write(idx & 0xff);
            }
            adjStack(1);
        }

        void astore(int idx) {
            if (idx <= 3) {
                code.write(0x4b + idx); // ASTORE_0..3
            } else if (idx <= 255) {
                code.write(ASTORE);
                code.write(idx);
            } else {
                code.write(0xc4); // WIDE
                code.write(ASTORE);
                code.write((idx >>> 8) & 0xff);
                code.write(idx & 0xff);
            }
            adjStack(-1);
        }

        void iload(int idx) {
            if (idx <= 3) {
                code.write(0x1a + idx);
            } else if (idx <= 255) {
                code.write(0x15);
                code.write(idx);
            } else {
                code.write(0xc4); // WIDE
                code.write(0x15);
                code.write((idx >>> 8) & 0xff);
                code.write(idx & 0xff);
            }
            adjStack(1);
        }

        void istore(int idx) {
            if (idx <= 3) {
                code.write(0x3b + idx);
            } else if (idx <= 255) {
                code.write(0x36);
                code.write(idx);
            } else {
                code.write(0xc4); // WIDE
                code.write(0x36);
                code.write((idx >>> 8) & 0xff);
                code.write(idx & 0xff);
            }
            adjStack(-1);
        }

        void aaload() { code.write(AALOAD); adjStack(-1); }
        void aastore() { code.write(AASTORE); adjStack(-3); }
        void arraylength() { code.write(0xbe); } // ARRAYLENGTH: -1 + 1 = 0
        void pop() { code.write(POP); adjStack(-1); }
        void dup() { code.write(DUP); adjStack(1); }
        void ixor() { code.write(IXOR); adjStack(-1); }
        void areturn() { code.write(ARETURN); adjStack(-1); }
        void vreturn() { code.write(RETURN); }
        void athrow() { code.write(ATHROW); adjStack(-1); }
        void monitorenter() { code.write(MONITORENTER); adjStack(-1); }
        void monitorexit() { code.write(MONITOREXIT); adjStack(-1); }

        void anewarray(String internalName) {
            int idx = ce.cp.addClass(internalName);
            code.write(ANEWARRAY);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            // pops int count (-1), pushes array (+1) => 0
        }

        void checkcast(String internalName) {
            int idx = ce.cp.addClass(internalName);
            code.write(CHECKCAST);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
        }

        void getstatic(String owner, String name, String desc) {
            int idx = ce.cp.addFieldref(owner, name, desc);
            code.write(GETSTATIC);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(1);
        }

        void getfield(String owner, String name, String desc) {
            int idx = ce.cp.addFieldref(owner, name, desc);
            code.write(GETFIELD);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            // pops obj (-1), pushes val (+1) => 0
        }

        void putfield(String owner, String name, String desc) {
            int idx = ce.cp.addFieldref(owner, name, desc);
            code.write(PUTFIELD);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(-2);
        }

        void invokevirtual(String owner, String name, String desc, int argSlots, int retSlots) {
            int idx = ce.cp.addMethodref(owner, name, desc);
            code.write(INVOKEVIRTUAL);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(retSlots - argSlots);
        }

        void invokespecial(String owner, String name, String desc, int argSlots, int retSlots) {
            int idx = ce.cp.addMethodref(owner, name, desc);
            code.write(INVOKESPECIAL);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(retSlots - argSlots);
        }

        void invokestatic(String owner, String name, String desc, int argSlots, int retSlots) {
            int idx = ce.cp.addMethodref(owner, name, desc);
            code.write(INVOKESTATIC);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(retSlots - argSlots);
        }

        void loadConstObj(Object obj) {
            int cIdx = ce.addConstObject(obj);
            aload(0);
            getfield(ce.className, "consts", "[Ljava/lang/Object;");
            iconst(cIdx);
            aaload();
        }

        // ====================================================================
        // AST -> Bytecode Compilation
        // ====================================================================

        boolean emitBoxed(Node node, int baseSlot) {
            if (node instanceof ConstNode cn) {
                Object v = cn.val;
                if (v == null) {
                    aconstNull();
                } else if (v == Boolean.TRUE) {
                    getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
                } else if (v == Boolean.FALSE) {
                    getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
                } else if (v instanceof String s) {
                    ldcString(s);
                } else if (v instanceof Long l && l >= -128L && l <= 127L) {
                    ldcLong(l);
                    invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", 2, 1);
                } else {
                    loadConstObj(v);
                }
                return false;
            }
            if (node instanceof LocalNode ln) {
                aload(baseSlot + ln.slot);
                return false;
            }
            if (node instanceof VarRefNode vr) {
                loadConstObj(vr.var);
                checkcast("cljl/Var");
                invokevirtual("cljl/Var", "deref", "()Ljava/lang/Object;", 1, 1);
                return false;
            }
            if (node instanceof DefNode dn) {
                loadConstObj(dn.var);
                checkcast("cljl/Var");
                if (dn.init != null) {
                    dup();
                    emitBoxed(dn.init, baseSlot);
                    invokevirtual("cljl/Var", "bindRoot", "(Ljava/lang/Object;)Lcljl/Var;", 2, 1);
                    pop();
                }
                return false;
            }
            if (node instanceof DefMacroNode dm) {
                loadConstObj(dm.var);
                checkcast("cljl/Var");
                emitBoxed(dm.fnNode, baseSlot);
                invokevirtual("cljl/Var", "bindRoot", "(Ljava/lang/Object;)Lcljl/Var;", 2, 1);
                invokevirtual("cljl/Var", "setMacro", "()Lcljl/Var;", 1, 1);
                return false;
            }
            if (node instanceof IfNode iff) {
                Label elseLabel = new Label();
                Label endLabel = new Label();
                emitBoolean(iff.test, baseSlot);
                ifeq(elseLabel);
                int stackAtIf = curStack;
                boolean thenTerm = emitBoxed(iff.thenNode, baseSlot);
                if (!thenTerm) {
                    gotoLabel(endLabel);
                }
                curStack = stackAtIf;
                markLabel(elseLabel);
                boolean elseTerm = emitBoxed(iff.elseNode, baseSlot);
                markLabel(endLabel);
                if (!thenTerm || !elseTerm) {
                    curStack = stackAtIf + 1;
                } else {
                    curStack = stackAtIf;
                }
                return thenTerm && elseTerm;
            }
            if (node instanceof DoNode dn) {
                if (dn.nodes.length == 0) {
                    aconstNull();
                    return false;
                }
                int last = dn.nodes.length - 1;
                for (int i = 0; i < last; i++) {
                    boolean term = emitBoxed(dn.nodes[i], baseSlot);
                    if (term) return true;
                    pop();
                }
                return emitBoxed(dn.nodes[last], baseSlot);
            }
            if (node instanceof LetNode ln) {
                for (int i = 0; i < ln.slots.length; i++) {
                    boolean term = emitBoxed(ln.inits[i], baseSlot);
                    if (term) return true;
                    astore(baseSlot + ln.slots[i]);
                }
                return emitBoxed(ln.body, baseSlot);
            }
            if (node instanceof LoopNode lp) {
                for (int i = 0; i < lp.slots.length; i++) {
                    boolean term = emitBoxed(lp.inits[i], baseSlot);
                    if (term) return true;
                    astore(baseSlot + lp.slots[i]);
                }
                Label loopStart = new Label();
                markLabel(loopStart);
                pushLoopTarget(lp.slots, loopStart);
                boolean term = emitBoxed(lp.body, baseSlot);
                popLoopTarget();
                return term;
            }
            if (node instanceof Recur0Node) {
                LoopTarget target = loopTargets.peek();
                gotoLabel(target.start);
                return true;
            }
            if (node instanceof Recur1Node r1) {
                LoopTarget target = loopTargets.peek();
                emitBoxed(r1.expr0, baseSlot);
                astore(baseSlot + target.slots[0]);
                gotoLabel(target.start);
                return true;
            }
            if (node instanceof Recur2Node r2) {
                LoopTarget target = loopTargets.peek();
                emitBoxed(r2.expr0, baseSlot);
                emitBoxed(r2.expr1, baseSlot);
                astore(baseSlot + target.slots[1]);
                astore(baseSlot + target.slots[0]);
                gotoLabel(target.start);
                return true;
            }
            if (node instanceof RecurNNode rn) {
                LoopTarget target = loopTargets.peek();
                for (Node expr : rn.exprs) {
                    emitBoxed(expr, baseSlot);
                }
                for (int i = rn.slots.length - 1; i >= 0; i--) {
                    astore(baseSlot + target.slots[i]);
                }
                gotoLabel(target.start);
                return true;
            }
            if (node instanceof VectorNode vn) {
                emitObjectArray(vn.elems, baseSlot);
                invokestatic("cljl/lang/PersistentVector", "createOwning",
                        "([Ljava/lang/Object;)Lcljl/lang/PersistentVector;", 1, 1);
                return false;
            }
            if (node instanceof MapNode mn) {
                emitObjectArray(mn.kvs, baseSlot);
                invokestatic("cljl/lang/PersistentHashMap", "create",
                        "([Ljava/lang/Object;)Lcljl/lang/PersistentHashMap;", 1, 1);
                return false;
            }
            if (node instanceof SetNode sn) {
                emitObjectArray(sn.elems, baseSlot);
                invokestatic("cljl/lang/PersistentHashSet", "create",
                        "([Ljava/lang/Object;)Lcljl/lang/PersistentHashSet;", 1, 1);
                return false;
            }
            if (node instanceof FnNode fn) {
                loadConstObj(fn);
                checkcast("cljl/Compiler$FnNode");
                int capLen = fn.fixedMethods.length + 1;
                iconst(capLen);
                anewarray("[Ljava/lang/Object;");
                for (int i = 0; i < fn.fixedMethods.length; i++) {
                    FnMethod m = fn.fixedMethods[i];
                    if (m != null && m.capturedParentSlots.length > 0) {
                        dup();
                        iconst(i);
                        emitCapturedSlots(m.capturedParentSlots, baseSlot);
                        aastore();
                    }
                }
                if (fn.variadicMethod != null && fn.variadicMethod.capturedParentSlots.length > 0) {
                    dup();
                    iconst(fn.fixedMethods.length);
                    emitCapturedSlots(fn.variadicMethod.capturedParentSlots, baseSlot);
                    aastore();
                }
                invokestatic("cljl/BytecodeJIT", "instantiateFn",
                        "(Lcljl/Compiler$FnNode;[[Ljava/lang/Object;)Lcljl/Fn;", 2, 1);
                return false;
            }

            // Var calls
            if (node instanceof VarCall0Node c) {
                emitVarFn(c.v);
                invokevirtual("cljl/Fn", "invoke", "()Ljava/lang/Object;", 1, 1);
                return false;
            }
            if (node instanceof VarCall1Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a, baseSlot);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof VarCall2Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a, baseSlot);
                emitBoxed(c.b, baseSlot);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof VarCall3Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a, baseSlot);
                emitBoxed(c.b, baseSlot);
                emitBoxed(c.c, baseSlot);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                return false;
            }
            if (node instanceof VarCall4Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a, baseSlot);
                emitBoxed(c.b, baseSlot);
                emitBoxed(c.c, baseSlot);
                emitBoxed(c.d, baseSlot);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                return false;
            }
            if (node instanceof VarCallNNode c) {
                emitVarFn(c.v);
                emitObjectArray(c.args, baseSlot);
                invokevirtual("cljl/Fn", "invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }

            // General fn calls
            if (node instanceof Call0Node c) {
                boolean directSelf = selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot
                        && ce.fixedMethods != null && ce.fixedMethods.length > 0 && ce.fixedMethods[0] != null;
                if (directSelf) {
                    aload(0);
                    invokevirtual(ce.className, "invoke", "()Ljava/lang/Object;", 1, 1);
                } else {
                    emitBoxed(c.fn, baseSlot);
                    checkcast("cljl/Fn");
                    invokevirtual("cljl/Fn", "invoke", "()Ljava/lang/Object;", 1, 1);
                }
                return false;
            }
            if (node instanceof Call1Node c) {
                boolean directSelf = selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot
                        && ce.fixedMethods != null && ce.fixedMethods.length > 1 && ce.fixedMethods[1] != null;
                if (directSelf) {
                    aload(0);
                    emitBoxed(c.a, baseSlot);
                    invokevirtual(ce.className, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                } else {
                    emitBoxed(c.fn, baseSlot);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a, baseSlot);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return false;
            }
            if (node instanceof Call2Node c) {
                boolean directSelf = selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot
                        && ce.fixedMethods != null && ce.fixedMethods.length > 2 && ce.fixedMethods[2] != null;
                if (directSelf) {
                    aload(0);
                    emitBoxed(c.a, baseSlot);
                    emitBoxed(c.b, baseSlot);
                    invokevirtual(ce.className, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                } else {
                    emitBoxed(c.fn, baseSlot);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a, baseSlot);
                    emitBoxed(c.b, baseSlot);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                }
                return false;
            }
            if (node instanceof Call3Node c) {
                boolean directSelf = selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot
                        && ce.fixedMethods != null && ce.fixedMethods.length > 3 && ce.fixedMethods[3] != null;
                if (directSelf) {
                    aload(0);
                    emitBoxed(c.a, baseSlot);
                    emitBoxed(c.b, baseSlot);
                    emitBoxed(c.c, baseSlot);
                    invokevirtual(ce.className, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                } else {
                    emitBoxed(c.fn, baseSlot);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a, baseSlot);
                    emitBoxed(c.b, baseSlot);
                    emitBoxed(c.c, baseSlot);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                }
                return false;
            }
            if (node instanceof Call4Node c) {
                boolean directSelf = selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot
                        && ce.fixedMethods != null && ce.fixedMethods.length > 4 && ce.fixedMethods[4] != null;
                if (directSelf) {
                    aload(0);
                    emitBoxed(c.a, baseSlot);
                    emitBoxed(c.b, baseSlot);
                    emitBoxed(c.c, baseSlot);
                    emitBoxed(c.d, baseSlot);
                    invokevirtual(ce.className, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                } else {
                    emitBoxed(c.fn, baseSlot);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a, baseSlot);
                    emitBoxed(c.b, baseSlot);
                    emitBoxed(c.c, baseSlot);
                    emitBoxed(c.d, baseSlot);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                }
                return false;
            }
            if (node instanceof CallNNode c) {
                emitBoxed(c.fn, baseSlot);
                checkcast("cljl/Fn");
                emitObjectArray(c.args, baseSlot);
                invokevirtual("cljl/Fn", "invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }

            // Arithmetic intrinsics
            if (node instanceof Add2Node a) {
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    emitBoxed(a.a, baseSlot);
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "add", "(Ljava/lang/Object;J)Ljava/lang/Number;", 3, 1);
                } else if (a.a instanceof ConstNode cn && cn.isLong) {
                    emitBoxed(a.b, baseSlot);
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "add", "(Ljava/lang/Object;J)Ljava/lang/Number;", 3, 1);
                } else {
                    emitBoxed(a.a, baseSlot);
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "add", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                }
                return false;
            }
            if (node instanceof Sub2Node a) {
                emitBoxed(a.a, baseSlot);
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "minus", "(Ljava/lang/Object;J)Ljava/lang/Number;", 3, 1);
                } else {
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "minus", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                }
                return false;
            }
            if (node instanceof Mul2Node a) {
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    emitBoxed(a.a, baseSlot);
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "multiply", "(Ljava/lang/Object;J)Ljava/lang/Number;", 3, 1);
                } else if (a.a instanceof ConstNode cn && cn.isLong) {
                    emitBoxed(a.b, baseSlot);
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "multiply", "(Ljava/lang/Object;J)Ljava/lang/Number;", 3, 1);
                } else {
                    emitBoxed(a.a, baseSlot);
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "multiply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                }
                return false;
            }
            if (node instanceof NegateNode a) {
                emitBoxed(a.a, baseSlot);
                invokestatic("cljl/Numbers", "minus", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof IncNode a) {
                emitBoxed(a.a, baseSlot);
                invokestatic("cljl/Numbers", "inc", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof DecNode a) {
                emitBoxed(a.a, baseSlot);
                invokestatic("cljl/Numbers", "dec", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof Rem2Node a) {
                emitBoxed(a.a, baseSlot);
                emitBoxed(a.b, baseSlot);
                invokestatic("cljl/Numbers", "remainder", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof Quot2Node a) {
                emitBoxed(a.a, baseSlot);
                emitBoxed(a.b, baseSlot);
                invokestatic("cljl/Numbers", "quotient", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }

            // Boolean-returning intrinsics boxed via Boolean.valueOf
            if (isBooleanNode(node)) {
                emitBoolean(node, baseSlot);
                invokestatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", 1, 1);
                return false;
            }

            // Exceptions, Monitors, Case & Interop
            if (node instanceof ThrowNode tn) {
                emitBoxed(tn.expr, baseSlot);
                invokestatic("cljl/BytecodeJIT", "throwObj", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", 1, 1);
                athrow();
                return true;
            }
            if (node instanceof NewNode nn) {
                loadConstObj(nn.clazz);
                checkcast("java/lang/Class");
                emitObjectArrayOrEmpty(nn.args, baseSlot);
                invokestatic("cljl/Interop", "invokeConstructor", "(Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof StaticFieldNode sf) {
                loadConstObj(sf.clazz);
                checkcast("java/lang/Class");
                ldcString(sf.fieldName);
                invokestatic("cljl/Interop", "getStaticField", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof StaticMethodNode sm) {
                loadConstObj(sm.clazz);
                checkcast("java/lang/Class");
                ldcString(sm.methodName);
                emitObjectArrayOrEmpty(sm.args, baseSlot);
                invokestatic("cljl/Interop", "invokeStaticMethod", "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof InstanceFieldNode inf) {
                emitBoxed(inf.target, baseSlot);
                ldcString(inf.fieldName);
                invokestatic("cljl/Interop", "getInstanceField", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof InstanceMethodNode im) {
                emitBoxed(im.target, baseSlot);
                ldcString(im.methodName);
                emitObjectArrayOrEmpty(im.args, baseSlot);
                invokestatic("cljl/Interop", "invokeInstanceMethod", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof SetVarNode sv) {
                loadConstObj(sv.var);
                checkcast("cljl/Var");
                emitBoxed(sv.val, baseSlot);
                invokevirtual("cljl/Var", "set", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof SetFieldNode sf) {
                emitBoxed(sf.target, baseSlot);
                ldcString(sf.fieldName);
                emitBoxed(sf.val, baseSlot);
                invokestatic("cljl/Interop", "setInstanceField", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof SetStaticFieldNode ssf) {
                loadConstObj(ssf.clazz);
                checkcast("java/lang/Class");
                ldcString(ssf.fieldName);
                emitBoxed(ssf.val, baseSlot);
                invokestatic("cljl/Interop", "setStaticField", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof MonitorNode mn) {
                emitBoxed(mn.target, baseSlot);
                iconst(mn.enter ? 1 : 0);
                invokestatic("cljl/Compiler", "monitorOp", "(Ljava/lang/Object;Z)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof LockingNode ln) {
                int lockLocal = allocTempLocal();
                int exLocal = allocTempLocal();
                emitBoxed(ln.target, baseSlot);
                invokestatic("cljl/BytecodeJIT", "requireNonNullLock", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
                dup();
                astore(lockLocal);
                monitorenter();

                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label catchStart = new Label();
                Label done = new Label();

                markLabel(tryStart);
                boolean bodyTerm = emitBoxed(ln.body, baseSlot);
                markLabel(tryEnd);

                if (!bodyTerm) {
                    aload(lockLocal);
                    monitorexit();
                    gotoLabel(done);
                }

                curStack = 1;
                if (maxStack < 1) maxStack = 1;
                markLabel(catchStart);
                astore(exLocal);
                aload(lockLocal);
                monitorexit();
                aload(exLocal);
                athrow();

                exceptionTable.add(new ExceptionEntry(tryStart, tryEnd, catchStart, 0));

                freeTempLocals(2);
                if (!bodyTerm) {
                    curStack = 1;
                    markLabel(done);
                    return false;
                } else {
                    curStack = 0;
                    return true;
                }
            }
            if (node instanceof CaseNode cn) {
                List<Node> branches = new ArrayList<>();
                IdentityHashMap<Node, Integer> nodeToIdx = new IdentityHashMap<>();
                Map<RT.EquivKey, Integer> keyToIdx = new HashMap<>(cn.table.size() * 2 + 1);
                for (Map.Entry<RT.EquivKey, Node> e : cn.table.entrySet()) {
                    Integer idx = nodeToIdx.get(e.getValue());
                    if (idx == null) {
                        idx = branches.size();
                        nodeToIdx.put(e.getValue(), idx);
                        branches.add(e.getValue());
                    }
                    keyToIdx.put(e.getKey(), idx);
                }

                if (branches.isEmpty()) {
                    emitBoxed(cn.expr, baseSlot);
                    if (cn.hasDefault) {
                        pop();
                        return emitBoxed(cn.defaultNode, baseSlot);
                    }
                    invokestatic("cljl/BytecodeJIT", "throwNoMatchingClause", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", 1, 1);
                    athrow();
                    return true;
                }

                int stackAtEntry = curStack;
                emitBoxed(cn.expr, baseSlot);
                loadConstObj(keyToIdx);
                checkcast("java/util/Map");
                if (cn.hasDefault) {
                    invokestatic("cljl/BytecodeJIT", "caseLookup", "(Ljava/lang/Object;Ljava/util/Map;)I", 2, 1);
                } else {
                    invokestatic("cljl/BytecodeJIT", "caseLookupOrThrow", "(Ljava/lang/Object;Ljava/util/Map;)I", 2, 1);
                }

                int n = branches.size();
                Label[] branchLabels = new Label[n];
                for (int i = 0; i < n; i++) branchLabels[i] = new Label();
                Label defaultLabel = new Label();
                Label endLabel = new Label();

                tableswitch(0, n - 1, defaultLabel, branchLabels);

                boolean anyNonTerm = false;
                for (int i = 0; i < n; i++) {
                    curStack = stackAtEntry;
                    markLabel(branchLabels[i]);
                    boolean bTerm = emitBoxed(branches.get(i), baseSlot);
                    if (!bTerm) {
                        anyNonTerm = true;
                        gotoLabel(endLabel);
                    }
                }

                curStack = stackAtEntry;
                markLabel(defaultLabel);
                if (cn.hasDefault) {
                    boolean dTerm = emitBoxed(cn.defaultNode, baseSlot);
                    if (!dTerm) {
                        anyNonTerm = true;
                    }
                } else {
                    aconstNull();
                    invokestatic("cljl/BytecodeJIT", "throwNoMatchingClause", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", 1, 1);
                    athrow();
                }

                if (anyNonTerm) {
                    curStack = stackAtEntry + 1;
                    markLabel(endLabel);
                    return false;
                } else {
                    curStack = stackAtEntry;
                    return true;
                }
            }

            throw new IllegalStateException("Unsupported node in BytecodeJIT: " + node.getClass().getName());
        }

        private static boolean isBooleanNode(Node n) {
            return n instanceof Lt2Node || n instanceof Lte2Node || n instanceof Gt2Node || n instanceof Gte2Node
                    || n instanceof Equiv2Node || n instanceof NumEq2Node || n instanceof ZeroPNode
                    || n instanceof PosPNode || n instanceof NegPNode || n instanceof NilPNode
                    || n instanceof NotNode || n instanceof IdenticalNode;
        }

        void emitBoolean(Node node, int baseSlot) {
            if (node instanceof Lt2Node a) {
                emitBoxed(a.a, baseSlot);
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "lt", "(Ljava/lang/Object;J)Z", 3, 1);
                } else {
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "lt", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                }
                return;
            }
            if (node instanceof Lte2Node a) {
                emitBoxed(a.a, baseSlot);
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "lte", "(Ljava/lang/Object;J)Z", 3, 1);
                } else {
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "lte", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                }
                return;
            }
            if (node instanceof Gt2Node a) {
                emitBoxed(a.a, baseSlot);
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "gt", "(Ljava/lang/Object;J)Z", 3, 1);
                } else {
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "gt", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                }
                return;
            }
            if (node instanceof Gte2Node a) {
                emitBoxed(a.a, baseSlot);
                if (a.b instanceof ConstNode cn && cn.isLong) {
                    ldcLong(cn.longVal);
                    invokestatic("cljl/Numbers", "gte", "(Ljava/lang/Object;J)Z", 3, 1);
                } else {
                    emitBoxed(a.b, baseSlot);
                    invokestatic("cljl/Numbers", "gte", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                }
                return;
            }
            if (node instanceof Equiv2Node a) {
                emitBoxed(a.a, baseSlot);
                emitBoxed(a.b, baseSlot);
                invokestatic("cljl/RT", "equiv", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                return;
            }
            if (node instanceof NumEq2Node a) {
                emitBoxed(a.a, baseSlot);
                emitBoxed(a.b, baseSlot);
                invokestatic("cljl/Numbers", "equal", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                return;
            }
            if (node instanceof ZeroPNode a) {
                emitBoxed(a.a, baseSlot);
                invokestatic("cljl/Numbers", "isZero", "(Ljava/lang/Object;)Z", 1, 1);
                return;
            }
            if (node instanceof PosPNode a) {
                emitBoxed(a.a, baseSlot);
                invokestatic("cljl/Numbers", "isPos", "(Ljava/lang/Object;)Z", 1, 1);
                return;
            }
            if (node instanceof NegPNode a) {
                emitBoxed(a.a, baseSlot);
                invokestatic("cljl/Numbers", "isNeg", "(Ljava/lang/Object;)Z", 1, 1);
                return;
            }
            if (node instanceof NilPNode a) {
                emitBoxed(a.a, baseSlot);
                Label trueL = new Label();
                Label endL = new Label();
                ifnull(trueL);
                iconst(0);
                gotoLabel(endL);
                curStack--;
                markLabel(trueL);
                iconst(1);
                markLabel(endL);
                return;
            }
            if (node instanceof IdenticalNode a) {
                emitBoxed(a.a, baseSlot);
                emitBoxed(a.b, baseSlot);
                Label neL = new Label();
                Label endL = new Label();
                ifAcmpNe(neL);
                iconst(1);
                gotoLabel(endL);
                curStack--;
                markLabel(neL);
                iconst(0);
                markLabel(endL);
                return;
            }
            if (node instanceof NotNode a) {
                emitBoolean(a.a, baseSlot);
                iconst(1);
                ixor();
                return;
            }
            emitBoxed(node, baseSlot);
            invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z", 1, 1);
        }

        private void emitVarFn(Var v) {
            loadConstObj(v);
            checkcast("cljl/Var");
            invokevirtual("cljl/Var", "get", "()Ljava/lang/Object;", 1, 1);
            checkcast("cljl/Fn");
        }

        private void emitObjectArray(Node[] nodes, int baseSlot) {
            iconst(nodes.length);
            anewarray("java/lang/Object");
            for (int i = 0; i < nodes.length; i++) {
                dup();
                iconst(i);
                emitBoxed(nodes[i], baseSlot);
                aastore();
            }
        }

        private void emitObjectArrayOrEmpty(Node[] nodes, int baseSlot) {
            if (nodes.length == 0) {
                invokestatic("cljl/BytecodeJIT", "emptyArray", "()[Ljava/lang/Object;", 0, 1);
            } else {
                emitObjectArray(nodes, baseSlot);
            }
        }

        private void emitCapturedSlots(int[] parentSlots, int baseSlot) {
            iconst(parentSlots.length);
            anewarray("java/lang/Object");
            for (int i = 0; i < parentSlots.length; i++) {
                dup();
                iconst(i);
                aload(baseSlot + parentSlots[i]);
                aastore();
            }
        }

        void finish() {
            byte[] codeBytes = code.toByteArray();
            // Patch jump offsets
            for (LoopTarget lt : loopTargets) {
                patchLabel(codeBytes, lt.start);
            }
            for (Label l : allLabels) {
                patchLabel(codeBytes, l);
            }

            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(codeBytes.length + 32 + exceptionTable.size() * 8);
                DataOutputStream out = new DataOutputStream(baos);
                out.writeShort(0x0001); // ACC_PUBLIC
                out.writeShort(nameIdx);
                out.writeShort(descIdx);
                out.writeShort(1); // 1 attribute: Code

                out.writeShort(ce.codeAttrNameIdx);
                out.writeInt(12 + codeBytes.length + exceptionTable.size() * 8);
                out.writeShort(Math.max(maxStack, 4));
                out.writeShort(maxLocals + maxTempLocals);
                out.writeInt(codeBytes.length);
                out.write(codeBytes);
                out.writeShort(exceptionTable.size());
                for (ExceptionEntry ex : exceptionTable) {
                    out.writeShort(ex.start.pos);
                    out.writeShort(ex.end.pos);
                    out.writeShort(ex.handler.pos);
                    out.writeShort(ex.catchTypeCpIdx);
                }
                out.writeShort(0); // attributes_count
                out.flush();
                ce.methodInfos.add(baos.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private final List<Label> allLabels = new ArrayList<>();

        private void patchLabel(byte[] codeBytes, Label l) {
            if (l.pos < 0) return;
            for (int[] f : l.fixups) {
                int rel = l.pos - f[0];
                if (rel < Short.MIN_VALUE || rel > Short.MAX_VALUE) {
                    throw new IllegalStateException("Bytecode jump offset overflow: " + rel);
                }
                codeBytes[f[1]] = (byte) ((rel >>> 8) & 0xff);
                codeBytes[f[1] + 1] = (byte) (rel & 0xff);
            }
            for (int[] wf : l.wideFixups) {
                int rel = l.pos - wf[0];
                int p = wf[1];
                codeBytes[p] = (byte) ((rel >>> 24) & 0xff);
                codeBytes[p + 1] = (byte) ((rel >>> 16) & 0xff);
                codeBytes[p + 2] = (byte) ((rel >>> 8) & 0xff);
                codeBytes[p + 3] = (byte) (rel & 0xff);
            }
        }
    }
}
