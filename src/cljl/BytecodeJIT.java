package cljl;

import cljl.Compiler.*;
import cljl.TypeAnalyzer.*;
import cljl.lang.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tier-1 Type-Specializing JVM Bytecode JIT Compiler for Clojol.
 *
 * Compiles hot CompiledFn instances directly to JVM classfile (v49.0) bytecode at runtime
 * with zero external dependencies, using TypeAnalyzer's fixpoint type inference and :tag hints
 * to unbox primitive locals/loops/arithmetic, emit specialized invokePrim_* overloads,
 * inline direct collection/transient virtual calls, and emit direct typed Java interop.
 */
public final class BytecodeJIT {

    private static final boolean STRICT_JIT = Boolean.getBoolean("cljl.jit.strict");
    private static final AtomicInteger CLASS_ID = new AtomicInteger(0);
    private static final JitClassLoader LOADER = new JitClassLoader();
    private static final ConcurrentHashMap<String, Object[]> PENDING_CONSTS = new ConcurrentHashMap<>();

    public static Object[] takePendingConsts(String key) {
        return PENDING_CONSTS.remove(key);
    }

    // JVM Opcodes
    private static final int ACONST_NULL = 0x01;
    private static final int ICONST_0 = 0x03;
    private static final int ICONST_1 = 0x04;
    private static final int LCONST_0 = 0x09;
    private static final int LCONST_1 = 0x0a;
    private static final int DCONST_0 = 0x0e;
    private static final int DCONST_1 = 0x0f;
    private static final int BIPUSH = 0x10;
    private static final int SIPUSH = 0x11;
    private static final int LDC = 0x12;
    private static final int LDC_W = 0x13;
    private static final int LDC2_W = 0x14;
    private static final int ILOAD = 0x15;
    private static final int LLOAD = 0x16;
    private static final int DLOAD = 0x18;
    private static final int ALOAD = 0x19;
    private static final int ALOAD_0 = 0x2a;
    private static final int IALOAD = 0x2e;
    private static final int LALOAD = 0x2f;
    private static final int FALOAD = 0x30;
    private static final int DALOAD = 0x31;
    private static final int AALOAD = 0x32;
    private static final int BALOAD = 0x33;
    private static final int CALOAD = 0x34;
    private static final int SALOAD = 0x35;
    private static final int ISTORE = 0x36;
    private static final int LSTORE = 0x37;
    private static final int DSTORE = 0x39;
    private static final int ASTORE = 0x3a;
    private static final int IASTORE = 0x4f;
    private static final int LASTORE = 0x50;
    private static final int FASTORE = 0x51;
    private static final int DASTORE = 0x52;
    private static final int AASTORE = 0x53;
    private static final int BASTORE = 0x54;
    private static final int CASTORE = 0x55;
    private static final int SASTORE = 0x56;
    private static final int POP = 0x57;
    private static final int POP2 = 0x58;
    private static final int DUP = 0x59;
    private static final int DUP_X2 = 0x5b;
    private static final int DUP2 = 0x5c;
    private static final int DUP2_X2 = 0x5e;
    private static final int LADD = 0x61;
    private static final int DADD = 0x63;
    private static final int LSUB = 0x65;
    private static final int DSUB = 0x67;
    private static final int LMUL = 0x69;
    private static final int DMUL = 0x6b;
    private static final int LDIV = 0x6d;
    private static final int DDIV = 0x6f;
    private static final int LREM = 0x71;
    private static final int DREM = 0x73;
    private static final int LNEG = 0x75;
    private static final int DNEG = 0x77;
    private static final int LSHL = 0x79;
    private static final int LSHR = 0x7b;
    private static final int LUSHR = 0x7d;
    private static final int LAND = 0x7f;
    private static final int LOR = 0x81;
    private static final int IXOR = 0x82;
    private static final int LXOR = 0x83;
    private static final int I2L = 0x85;
    private static final int L2I = 0x88;
    private static final int L2D = 0x8a;
    private static final int F2D = 0x8d;
    private static final int D2L = 0x8f;
    private static final int D2F = 0x90;
    private static final int I2B = 0x91;
    private static final int I2S = 0x93;
    private static final int LCMP = 0x94;
    private static final int DCMPL = 0x97;
    private static final int DCMPG = 0x98;
    private static final int IFEQ = 0x99;
    private static final int IFNE = 0x9a;
    private static final int IFLT = 0x9b;
    private static final int IFGE = 0x9c;
    private static final int IFGT = 0x9d;
    private static final int IFLE = 0x9e;
    private static final int IF_ICMPEQ = 0x9f;
    private static final int IF_ICMPNE = 0xa0;
    private static final int IF_ICMPLT = 0xa1;
    private static final int IF_ICMPGE = 0xa2;
    private static final int IF_ICMPGT = 0xa3;
    private static final int IF_ICMPLE = 0xa4;
    private static final int IF_ACMPEQ = 0xa5;
    private static final int IF_ACMPNE = 0xa6;
    private static final int GOTO = 0xa7;
    private static final int TABLESWITCH = 0xaa;
    private static final int IRETURN = 0xac;
    private static final int LRETURN = 0xad;
    private static final int DRETURN = 0xaf;
    private static final int ARETURN = 0xb0;
    private static final int RETURN = 0xb1;
    private static final int GETSTATIC = 0xb2;
    private static final int GETFIELD = 0xb4;
    private static final int PUTFIELD = 0xb5;
    private static final int INVOKEVIRTUAL = 0xb6;
    private static final int INVOKESPECIAL = 0xb7;
    private static final int INVOKESTATIC = 0xb8;
    private static final int INVOKEINTERFACE = 0xb9;
    private static final int NEW = 0xbb;
    private static final int ANEWARRAY = 0xbd;
    private static final int ARRAYLENGTH = 0xbe;
    private static final int ATHROW = 0xbf;
    private static final int CHECKCAST = 0xc0;
    private static final int INSTANCEOF = 0xc1;
    private static final int MONITORENTER = 0xc2;
    private static final int MONITOREXIT = 0xc3;
    private static final int WIDE = 0xc4;
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

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> dyn = TypeFactory.findDynamicClass(name);
            if (dyn != null) return dyn;
            ClassLoader rt = RT.baseLoader();
            if (rt != null && rt != this && rt != getParent()) {
                return rt.loadClass(name);
            }
            throw new ClassNotFoundException(name);
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
        if (n instanceof TryNode tn) {
            if (!stackEmpty) return false;
            if (!canCompileNode(tn.body, true)) return false;
            for (CatchClause cc : tn.catches) {
                if (!canCompileNode(cc.handler(), true)) return false;
            }
            return tn.finallyNode == null || canCompileNode(tn.finallyNode, true);
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
        if (n instanceof Div2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof NegateNode a) return canCompileNode(a.a, false);
        if (n instanceof IncNode a) return canCompileNode(a.a, false);
        if (n instanceof DecNode a) return canCompileNode(a.a, false);
        if (n instanceof Rem2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Mod2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof Quot2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof UncheckedAdd2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof UncheckedSub2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof UncheckedMul2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof UncheckedIncNode a) return canCompileNode(a.a, false);
        if (n instanceof UncheckedDecNode a) return canCompileNode(a.a, false);
        if (n instanceof UncheckedNegateNode a) return canCompileNode(a.a, false);
        if (n instanceof BitOp2Node a) return canCompileNode(a.a, false) && canCompileNode(a.b, false);
        if (n instanceof BitNotNode a) return canCompileNode(a.a, false);
        if (n instanceof LongCastNode a) return canCompileNode(a.a, false);
        if (n instanceof DoubleCastNode a) return canCompileNode(a.a, false);
        if (n instanceof AlengthNode a) return canCompileNode(a.arr, false);
        if (n instanceof AgetNode a) return canCompileNode(a.arr, false) && canCompileNode(a.idx, false);
        if (n instanceof AsetNode a) return canCompileNode(a.arr, false) && canCompileNode(a.idx, false) && canCompileNode(a.val, false);
        if (n instanceof CountNode a) return canCompileNode(a.coll, false);
        if (n instanceof NthNode a) {
            return canCompileNode(a.coll, false) && canCompileNode(a.idx, false)
                    && (a.notFound == null || canCompileNode(a.notFound, false));
        }
        if (n instanceof GetNode a) {
            return canCompileNode(a.coll, false) && canCompileNode(a.key, false)
                    && (a.notFound == null || canCompileNode(a.notFound, false));
        }
        if (n instanceof TransientNode a) return canCompileNode(a.coll, false);
        if (n instanceof PersistentBangNode a) return canCompileNode(a.target, false);
        if (n instanceof ConjBangNode a) return canCompileNode(a.target, false) && canCompileNode(a.val, false);
        if (n instanceof AssocBangNode a) return canCompileNode(a.target, false) && canCompileNode(a.key, false) && canCompileNode(a.val, false);
        if (n instanceof ConjNode a) return canCompileNode(a.coll, false) && canCompileNode(a.val, false);
        if (n instanceof AssocNode a) return canCompileNode(a.coll, false) && canCompileNode(a.key, false) && canCompileNode(a.val, false);
        if (n instanceof DissocNode a) return canCompileNode(a.coll, false) && canCompileNode(a.key, false);
        if (n instanceof FirstNode a) return canCompileNode(a.coll, false);
        if (n instanceof NextNode a) return canCompileNode(a.coll, false);
        if (n instanceof RestNode a) return canCompileNode(a.coll, false);
        if (n instanceof SeqNode a) return canCompileNode(a.coll, false);
        if (n instanceof PopNode a) return canCompileNode(a.coll, false);
        if (n instanceof VolatileNode a) return canCompileNode(a.init, false);
        if (n instanceof DerefNode a) return canCompileNode(a.target, false);
        if (n instanceof VresetNode a) return canCompileNode(a.target, false) && canCompileNode(a.val, false);
        if (n instanceof AtomNode a) return canCompileNode(a.init, false);
        if (n instanceof ResetBangNode a) return canCompileNode(a.target, false) && canCompileNode(a.val, false);
        if (n instanceof SwapBangNode a) {
            if (!canCompileNode(a.target, false) || !canCompileNode(a.fn, false)) return false;
            for (Node arg : a.extraArgs) if (!canCompileNode(arg, false)) return false;
            return true;
        }
        if (n instanceof EvenPNode a) return canCompileNode(a.a, false);
        if (n instanceof OddPNode a) return canCompileNode(a.a, false);
        if (n instanceof TypePredNode a) return canCompileNode(a.a, false);
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

    private static String sanitizeClassName(String raw) {
        if (raw == null || raw.isEmpty()) return "Fn";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), 40));
        for (int i = 0; i < raw.length() && sb.length() < 40; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else if (c == '-' || c == '/' || c == '.') {
                sb.append('_');
            }
        }
        if (sb.length() == 0 || (sb.charAt(0) >= '0' && sb.charAt(0) <= '9')) {
            sb.insert(0, "Fn_");
        }
        return sb.toString();
    }

    public static Fn compile(CompiledFn cf) {
        if (!canCompile(cf)) return null;
        try {
            int id = CLASS_ID.incrementAndGet();
            String fnBase = sanitizeClassName(cf.getName());
            String internalName = "cljl/jit/" + fnBase + "$" + id;
            String binaryName = "cljl.jit." + fnBase + "$" + id;
            String sourceFile = (cf.getName() != null && !cf.getName().isEmpty()) ? (cf.getName() + ".clj") : "cljl.clj";

            ClassEmitter ce = new ClassEmitter(internalName, sourceFile);
            emitMethods(ce, cf);
            byte[] bytecode = ce.toByteArray();

            boolean hasConsts = !ce.constObjects.isEmpty();
            if (hasConsts) {
                PENDING_CONSTS.put(internalName, ce.constObjects.toArray());
            }
            try {
                Class<?> cls = LOADER.define(binaryName, bytecode);
                Constructor<?> ctor = cls.getConstructor(
                        Object[][].class, String.class, CompiledFn.class);
                return (Fn) ctor.newInstance(cf.getCaptured(), cf.getName(), cf);
            } finally {
                if (hasConsts) PENDING_CONSTS.remove(internalName);
            }
        } catch (Throwable t) {
            if (STRICT_JIT) {
                throw new RuntimeException("BytecodeJIT compilation failed for " + cf.getName(), t);
            }
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

    public static int caseIdentityLookup(Object val, IdentityHashMap<Object, Integer> table) {
        Integer idx = table.get(val);
        return idx != null ? idx : -1;
    }

    public static int caseIdentityLookupOrThrow(Object val, IdentityHashMap<Object, Integer> table) {
        Integer idx = table.get(val);
        if (idx != null) return idx;
        throw new IllegalArgumentException("No matching clause: " + val);
    }

    public static int caseDirectLookup(Object val, Map<Object, Integer> table) {
        Integer idx = table.get(val);
        return idx != null ? idx : -1;
    }

    public static int caseDirectLookupOrThrow(Object val, Map<Object, Integer> table) {
        Integer idx = table.get(val);
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

    public static char toChar(Object o) {
        if (o instanceof Character c) return c;
        if (o instanceof Number n) return (char) n.intValue();
        throw new ClassCastException("Cannot cast " + (o == null ? "nil" : o.getClass().getName()) + " to char");
    }

    public static boolean isMap(Object x) {
        return x instanceof PersistentHashMap || x instanceof Map<?, ?>;
    }

    public static boolean isSet(Object x) {
        return x instanceof PersistentHashSet || x instanceof Set<?>;
    }

    // ========================================================================
    // Method & Specialization Planning
    // ========================================================================

    static final class SlotLayout {
        final ValType[] slotTypes;
        final int[] jvmSlot;
        final int totalLocals;

        SlotLayout(FnMethod m, ValType[] slotTypes, int baseSlot) {
            this.slotTypes = slotTypes;
            this.jvmSlot = new int[m.maxSlots];
            int next = baseSlot;
            for (int i = 0; i < m.maxSlots; i++) {
                this.jvmSlot[i] = next;
                ValType t = (i < slotTypes.length) ? slotTypes[i] : ValType.ANY;
                next += (t == ValType.LONG || t == ValType.DOUBLE) ? 2 : 1;
            }
            this.totalLocals = next;
        }
    }

    private static void emitMethods(ClassEmitter ce, CompiledFn cf) {
        FnMethod[] fixed = cf.getFixedMethods();
        FnMethod variadic = cf.getVariadicMethod();
        ce.fixedMethods = fixed;
        ce.ownerFn = cf;
        ce.ownerVar = cf.getOwnerVar();

        // Pass 1: discover primitive specializations and generic analyses for all arities
        AnalysisResult[] genericFixed = new AnalysisResult[fixed.length];
        for (int arity = 0; arity < fixed.length; arity++) {
            FnMethod m = fixed[arity];
            if (m != null) {
                genericFixed[arity] = TypeAnalyzer.analyzeGeneric(m, cf);
                if (arity >= 1 && arity <= 3) {
                    List<PrimSpec> specs = TypeAnalyzer.discoverPrimSpecs(m, cf);
                    if (!specs.isEmpty()) {
                        ce.primSpecsByArity.put(arity, specs);
                    }
                }
            }
        }
        AnalysisResult genericVariadic = variadic != null ? TypeAnalyzer.analyzeGeneric(variadic, cf) : null;

        // Pass 2: emit primitive specialized methods
        boolean hasUnboxedLong1 = false;
        boolean hasUnboxedLong2 = false;
        for (Map.Entry<Integer, List<PrimSpec>> e : ce.primSpecsByArity.entrySet()) {
            int arity = e.getKey();
            FnMethod m = fixed[arity];
            for (PrimSpec ps : e.getValue()) {
                emitPrimInvoke(ce, m, arity, arity, ps, genericFixed[arity]);
                if ("invokePrim_J_J".equals(ps.methodName) && "(J)J".equals(ps.descriptor)) {
                    hasUnboxedLong1 = true;
                } else if ("invokePrim_JJ_J".equals(ps.methodName) && "(JJ)J".equals(ps.descriptor)) {
                    hasUnboxedLong2 = true;
                }
            }
        }
        if (hasUnboxedLong1) {
            CodeGen cg = ce.beginMethod("isUnboxedLong1", "()Z", 1);
            cg.iconst(1);
            cg.ireturn();
            cg.finish();
        }
        if (hasUnboxedLong2) {
            CodeGen cg = ce.beginMethod("isUnboxedLong2", "()Z", 1);
            cg.iconst(1);
            cg.ireturn();
            cg.finish();
        }

        // Pass 3: emit invoke()..invoke(Object,Object,Object,Object) for fixed arities 0..4,
        // plus direct bridges for variadic arities 0..4 not covered by fixed methods
        for (int arity = 0; arity <= 4; arity++) {
            FnMethod m = fixed[arity];
            if (m != null) {
                emitFixedInvoke(ce, m, arity, arity, genericFixed[arity], ce.primSpecsByArity.get(arity));
            } else if (variadic != null && arity >= variadic.fixedArity) {
                emitVariadicBridgeInvoke(ce, variadic, arity, fixed.length, genericVariadic);
            }
        }

        // Pass 4: emit invokeVarargs([Ljava/lang/Object;)Ljava/lang/Object; if needed
        boolean hasHighFixed = false;
        for (int i = 5; i < fixed.length; i++) {
            if (fixed[i] != null) {
                hasHighFixed = true;
                break;
            }
        }
        if (hasHighFixed || variadic != null) {
            emitVarargsInvoke(ce, fixed, genericFixed, variadic, genericVariadic);
        }
    }

    private static boolean isPureConstantForStatic(ConstNode cn) {
        Object v = cn.val;
        return v == null || v instanceof Long || v instanceof Double || v instanceof Boolean || v instanceof String;
    }

    private static boolean containsSelfCall(Node n, Var ownerVar, int selfSlot, int arity) {
        if (n == null) return false;
        if (n instanceof VarCall1Node c) return (arity == 1 && ownerVar != null && c.v == ownerVar) || containsSelfCall(c.a, ownerVar, selfSlot, arity);
        if (n instanceof VarCall2Node c) return (arity == 2 && ownerVar != null && c.v == ownerVar) || containsSelfCall(c.a, ownerVar, selfSlot, arity) || containsSelfCall(c.b, ownerVar, selfSlot, arity);
        if (n instanceof VarCall3Node c) return (arity == 3 && ownerVar != null && c.v == ownerVar) || containsSelfCall(c.a, ownerVar, selfSlot, arity) || containsSelfCall(c.b, ownerVar, selfSlot, arity) || containsSelfCall(c.c, ownerVar, selfSlot, arity);
        if (n instanceof Call1Node c) return (arity == 1 && selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot) || containsSelfCall(c.fn, ownerVar, selfSlot, arity) || containsSelfCall(c.a, ownerVar, selfSlot, arity);
        if (n instanceof Call2Node c) return (arity == 2 && selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot) || containsSelfCall(c.fn, ownerVar, selfSlot, arity) || containsSelfCall(c.a, ownerVar, selfSlot, arity) || containsSelfCall(c.b, ownerVar, selfSlot, arity);
        if (n instanceof Call3Node c) return (arity == 3 && selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot) || containsSelfCall(c.fn, ownerVar, selfSlot, arity) || containsSelfCall(c.a, ownerVar, selfSlot, arity) || containsSelfCall(c.b, ownerVar, selfSlot, arity) || containsSelfCall(c.c, ownerVar, selfSlot, arity);
        if (n instanceof IfNode iff) return containsSelfCall(iff.test, ownerVar, selfSlot, arity) || containsSelfCall(iff.thenNode, ownerVar, selfSlot, arity) || containsSelfCall(iff.elseNode, ownerVar, selfSlot, arity);
        if (n instanceof DoNode dn) { for (Node c : dn.nodes) if (containsSelfCall(c, ownerVar, selfSlot, arity)) return true; return false; }
        if (n instanceof LetNode ln) { for (Node i : ln.inits) if (containsSelfCall(i, ownerVar, selfSlot, arity)) return true; return containsSelfCall(ln.body, ownerVar, selfSlot, arity); }
        if (n instanceof Add2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Sub2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Mul2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Div2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Rem2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Mod2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Quot2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof UncheckedAdd2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof UncheckedSub2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof UncheckedMul2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof BitOp2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Lt2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Lte2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Gt2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Gte2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof Equiv2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof NumEq2Node a) return containsSelfCall(a.a, ownerVar, selfSlot, arity) || containsSelfCall(a.b, ownerVar, selfSlot, arity);
        if (n instanceof NegateNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof IncNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof DecNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof UncheckedIncNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof UncheckedDecNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof UncheckedNegateNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof BitNotNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof LongCastNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof DoubleCastNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof ZeroPNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof PosPNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof NegPNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof EvenPNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof OddPNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        if (n instanceof NotNode a) return containsSelfCall(a.a, ownerVar, selfSlot, arity);
        return false;
    }

    private static boolean isPureSelfRecursiveBody(
            Node n, Var ownerVar, int selfSlot, int arity, AnalysisResult analysis, PrimSpec ps, int[] stats) {
        if (n == null) return true;
        stats[0]++;
        if (n instanceof ConstNode cn) return isPureConstantForStatic(cn);
        if (n instanceof LocalNode ln) return ln.slot != selfSlot;
        if (n instanceof IfNode iff) {
            return isPureSelfRecursiveBody(iff.test, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(iff.thenNode, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(iff.elseNode, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof DoNode dn) {
            for (Node c : dn.nodes) {
                if (!isPureSelfRecursiveBody(c, ownerVar, selfSlot, arity, analysis, ps, stats)) return false;
            }
            return true;
        }
        if (n instanceof LetNode ln) {
            for (Node i : ln.inits) {
                if (!isPureSelfRecursiveBody(i, ownerVar, selfSlot, arity, analysis, ps, stats)) return false;
            }
            return isPureSelfRecursiveBody(ln.body, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof VarCall1Node c && arity == 1 && ownerVar != null && c.v == ownerVar) {
            if (analysis.typeOf(c.a) != ps.paramTypes[0]) return false;
            stats[1]++;
            return isPureSelfRecursiveBody(c.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof VarCall2Node c && arity == 2 && ownerVar != null && c.v == ownerVar) {
            if (analysis.typeOf(c.a) != ps.paramTypes[0] || analysis.typeOf(c.b) != ps.paramTypes[1]) return false;
            stats[1]++;
            return isPureSelfRecursiveBody(c.a, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(c.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof VarCall3Node c && arity == 3 && ownerVar != null && c.v == ownerVar) {
            if (analysis.typeOf(c.a) != ps.paramTypes[0] || analysis.typeOf(c.b) != ps.paramTypes[1]
                    || analysis.typeOf(c.c) != ps.paramTypes[2]) return false;
            stats[1]++;
            return isPureSelfRecursiveBody(c.a, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(c.b, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(c.c, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof Call1Node c && arity == 1 && selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot) {
            if (analysis.typeOf(c.a) != ps.paramTypes[0]) return false;
            stats[1]++;
            return isPureSelfRecursiveBody(c.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof Call2Node c && arity == 2 && selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot) {
            if (analysis.typeOf(c.a) != ps.paramTypes[0] || analysis.typeOf(c.b) != ps.paramTypes[1]) return false;
            stats[1]++;
            return isPureSelfRecursiveBody(c.a, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(c.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof Call3Node c && arity == 3 && selfSlot != -1 && c.fn instanceof LocalNode ln && ln.slot == selfSlot) {
            if (analysis.typeOf(c.a) != ps.paramTypes[0] || analysis.typeOf(c.b) != ps.paramTypes[1]
                    || analysis.typeOf(c.c) != ps.paramTypes[2]) return false;
            stats[1]++;
            return isPureSelfRecursiveBody(c.a, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(c.b, ownerVar, selfSlot, arity, analysis, ps, stats)
                    && isPureSelfRecursiveBody(c.c, ownerVar, selfSlot, arity, analysis, ps, stats);
        }
        if (n instanceof Add2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Sub2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Mul2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Div2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Rem2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Mod2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Quot2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof UncheckedAdd2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof UncheckedSub2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof UncheckedMul2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof BitOp2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Lt2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Lte2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Gt2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Gte2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof Equiv2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof NumEq2Node a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats) && isPureSelfRecursiveBody(a.b, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof NegateNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof IncNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof DecNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof UncheckedIncNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof UncheckedDecNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof UncheckedNegateNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof BitNotNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof LongCastNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof DoubleCastNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof ZeroPNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof PosPNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof NegPNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof EvenPNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof OddPNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        if (n instanceof NotNode a) return isPureSelfRecursiveBody(a.a, ownerVar, selfSlot, arity, analysis, ps, stats);
        return false;
    }

    private static boolean canEmitStaticFastWorker(ClassEmitter ce, FnMethod m, int arity, PrimSpec ps, int[] stats) {
        if (m.capturedLocalSlots.length != 0) return false;
        if (!ps.analysis.returnType.isPrimitive()) return false;
        for (int i = 0; i < arity; i++) {
            if (!ps.paramTypes[i].isPrimitive()) return false;
        }
        for (int i = 0; i < m.maxSlots; i++) {
            if (i == m.selfSlot) continue;
            ValType st = ps.analysis.slotTypes[i];
            if (!st.isPrimitive()) return false;
        }
        return isPureSelfRecursiveBody(m.body, ce.ownerVar, m.selfSlot, arity, ps.analysis, ps, stats) && stats[1] >= 1;
    }

    private static void emitPrimInvoke(
            ClassEmitter ce, FnMethod m, int arity, int methodCaptureIdx, PrimSpec ps, AnalysisResult genericAnalysis) {
        int[] stats = new int[2];
        String staticFastName = null;
        if (canEmitStaticFastWorker(ce, m, arity, ps, stats)) {
            staticFastName = "fast_" + ps.methodName;
            emitStaticFastPrimWorker(ce, m, arity, ps, staticFastName, stats[0] <= 24);
        }

        int[] argJvmSlots = new int[arity];
        int nextArgSlot = 1;
        for (int i = 0; i < arity; i++) {
            argJvmSlots[i] = nextArgSlot;
            ValType pt = ps.paramTypes[i];
            nextArgSlot += (pt == ValType.LONG || pt == ValType.DOUBLE) ? 2 : 1;
        }

        SlotLayout layout = new SlotLayout(m, ps.analysis.slotTypes, nextArgSlot);
        CodeGen cg = ce.beginMethod(ps.methodName, ps.descriptor, layout.totalLocals);
        cg.setContext(m, ps.analysis, layout);
        cg.genericAnalysis = genericAnalysis;

        ValType retT = ps.analysis.returnType;
        if (staticFastName != null) {
            Label slowEntry = null;
            if (ce.ownerVar != null) {
                slowEntry = new Label();
                cg.loadConstObj(ce.ownerVar);
                cg.checkcast("cljl/Var");
                cg.invokevirtual("cljl/Var", "get", "()Ljava/lang/Object;", 1, 1);
                cg.aload(0);
                cg.getfield(ce.className, "outerFn", "Lcljl/Compiler$CompiledFn;", 1);
                cg.ifAcmpNe(slowEntry);
            }
            int argWords = 0;
            for (int i = 0; i < arity; i++) {
                ValType pt = ps.paramTypes[i];
                cg.loadJvmLocal(argJvmSlots[i], pt);
                argWords += (pt == ValType.LONG || pt == ValType.DOUBLE) ? 2 : 1;
            }
            int retWords = (retT == ValType.LONG || retT == ValType.DOUBLE) ? 2 : 1;
            cg.invokestatic(ce.className, staticFastName, ps.descriptor, argWords, retWords);
            cg.emitReturnInstruction(retT);
            if (slowEntry == null) {
                cg.finish();
                return;
            }
            cg.markLabel(slowEntry);
            cg.curStack = 0;
        }

        // Initialize selfSlot
        if (m.selfSlot != -1) {
            cg.aload(0);
            cg.astore(layout.jvmSlot[m.selfSlot]);
        }
        // Initialize captured locals
        emitInitCaptured(ce, cg, m, methodCaptureIdx, layout);
        // Copy specialized arguments into logical slots
        for (int i = 0; i < arity; i++) {
            ValType entryT = ps.paramTypes[i];
            int logicalSlot = m.paramSlots[i];
            ValType targetT = layout.slotTypes[logicalSlot];
            cg.loadJvmLocal(argJvmSlots[i], entryT);
            cg.convertValue(entryT, targetT);
            cg.storeLogicalSlot(logicalSlot);
        }

        Label fnLoopStart = new Label();
        cg.markLabel(fnLoopStart);
        cg.pushLoopTarget(m.paramSlots, fnLoopStart);
        boolean terminated = cg.emitReturnTyped(m.body, retT);
        cg.popLoopTarget();
        if (!terminated) {
            cg.emitReturnInstruction(retT);
        }
        cg.finish();
    }

    private static void emitStaticFastPrimWorker(
            ClassEmitter ce, FnMethod m, int arity, PrimSpec ps, String fastName, boolean canSelfInline) {
        LinearRecOptimizer.LinearRecSpec linearSpec = LinearRecOptimizer.matchLinearDecrementRec(ce, m, arity, ps);
        if (linearSpec != null) {
            LinearRecOptimizer.emitLinearDecrementWorkers(ce, m, ps, fastName, linearSpec);
            return;
        }

        int[] argJvmSlots = new int[arity];
        int nextArgSlot = 0;
        for (int i = 0; i < arity; i++) {
            argJvmSlots[i] = nextArgSlot;
            ValType pt = ps.paramTypes[i];
            nextArgSlot += (pt == ValType.LONG || pt == ValType.DOUBLE) ? 2 : 1;
        }

        SlotLayout layout = new SlotLayout(m, ps.analysis.slotTypes, nextArgSlot);
        // Reuse incoming JVM argument slots directly for paramSlots when types match
        for (int i = 0; i < arity; i++) {
            int logicalSlot = m.paramSlots[i];
            if (ps.paramTypes[i] == layout.slotTypes[logicalSlot]) {
                layout.jvmSlot[logicalSlot] = argJvmSlots[i];
            }
        }

        CodeGen cg = ce.beginStaticMethod(fastName, ps.descriptor, layout.totalLocals);
        cg.setContext(m, ps.analysis, layout);
        cg.staticFastSelfSpec = ps;
        cg.staticFastSelfMethodName = fastName;
        cg.staticFastSelfCanInline = canSelfInline;
        cg.staticFastSelfInlineDepth = 0;

        for (int i = 0; i < arity; i++) {
            ValType entryT = ps.paramTypes[i];
            int logicalSlot = m.paramSlots[i];
            ValType targetT = layout.slotTypes[logicalSlot];
            if (entryT != targetT) {
                cg.loadJvmLocal(argJvmSlots[i], entryT);
                cg.convertValue(entryT, targetT);
                cg.storeLogicalSlot(logicalSlot);
            }
        }

        ValType retT = ps.analysis.returnType;
        boolean terminated = cg.emitReturnTyped(m.body, retT);
        if (!terminated) {
            cg.emitReturnInstruction(retT);
        }
        cg.finish();
    }

    private static void emitFixedInvoke(
            ClassEmitter ce, FnMethod m, int arity, int methodCaptureIdx,
            AnalysisResult analysis, List<PrimSpec> specs) {
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < arity; i++) desc.append("Ljava/lang/Object;");
        desc.append(")Ljava/lang/Object;");

        int baseSlot = 1 + arity;
        SlotLayout layout = new SlotLayout(m, analysis.slotTypes, baseSlot);
        CodeGen cg = ce.beginMethod("invoke", desc.toString(), layout.totalLocals);
        cg.setContext(m, analysis, layout);

        // Fast guarded dispatch to primitive-specialized overloads
        if (specs != null && !specs.isEmpty()) {
            for (PrimSpec ps : specs) {
                Label nextSpec = new Label();
                for (int i = 0; i < arity; i++) {
                    ValType pt = ps.paramTypes[i];
                    if (pt == ValType.LONG) {
                        cg.aload(1 + i);
                        cg.emitInstanceof("java/lang/Long");
                        cg.ifeq(nextSpec);
                    } else if (pt == ValType.DOUBLE) {
                        cg.aload(1 + i);
                        cg.emitInstanceof("java/lang/Double");
                        cg.ifeq(nextSpec);
                    } else if (pt == ValType.BOOL) {
                        cg.aload(1 + i);
                        cg.emitInstanceof("java/lang/Boolean");
                        cg.ifeq(nextSpec);
                    }
                }
                cg.aload(0);
                int argWords = 1;
                for (int i = 0; i < arity; i++) {
                    ValType pt = ps.paramTypes[i];
                    cg.aload(1 + i);
                    if (pt == ValType.LONG) {
                        cg.checkcast("java/lang/Long");
                        cg.invokevirtual("java/lang/Long", "longValue", "()J", 1, 2);
                        argWords += 2;
                    } else if (pt == ValType.DOUBLE) {
                        cg.checkcast("java/lang/Double");
                        cg.invokevirtual("java/lang/Double", "doubleValue", "()D", 1, 2);
                        argWords += 2;
                    } else if (pt == ValType.BOOL) {
                        cg.checkcast("java/lang/Boolean");
                        cg.invokevirtual("java/lang/Boolean", "booleanValue", "()Z", 1, 1);
                        argWords += 1;
                    } else {
                        argWords += 1;
                    }
                }
                ValType retT = ps.analysis.returnType;
                int retWords = (retT == ValType.LONG || retT == ValType.DOUBLE) ? 2 : 1;
                cg.invokevirtual(ce.className, ps.methodName, ps.descriptor, argWords, retWords);
                cg.boxValue(retT);
                cg.areturn();
                cg.markLabel(nextSpec);
                cg.curStack = 0;
            }
        }

        // Generic body fallback
        if (m.selfSlot != -1) {
            cg.aload(0);
            cg.astore(layout.jvmSlot[m.selfSlot]);
        }
        emitInitCaptured(ce, cg, m, methodCaptureIdx, layout);
        for (int i = 0; i < arity; i++) {
            int logicalSlot = m.paramSlots[i];
            ValType targetT = layout.slotTypes[logicalSlot];
            cg.aload(1 + i);
            cg.unboxFromObject(targetT);
            cg.storeLogicalSlot(logicalSlot);
        }

        Label fnLoopStart = new Label();
        cg.markLabel(fnLoopStart);
        cg.pushLoopTarget(m.paramSlots, fnLoopStart);
        boolean terminated = cg.emitBoxed(m.body);
        cg.popLoopTarget();
        if (!terminated) {
            cg.areturn();
        }
        cg.finish();
    }

    private static void emitVariadicBridgeInvoke(
            ClassEmitter ce, FnMethod m, int callArity, int methodCaptureIdx, AnalysisResult analysis) {
        StringBuilder desc = new StringBuilder("(");
        for (int i = 0; i < callArity; i++) desc.append("Ljava/lang/Object;");
        desc.append(")Ljava/lang/Object;");

        int baseSlot = 1 + callArity;
        SlotLayout layout = new SlotLayout(m, analysis.slotTypes, baseSlot);
        CodeGen cg = ce.beginMethod("invoke", desc.toString(), layout.totalLocals);
        cg.setContext(m, analysis, layout);

        if (m.selfSlot != -1) {
            cg.aload(0);
            cg.astore(layout.jvmSlot[m.selfSlot]);
        }
        emitInitCaptured(ce, cg, m, methodCaptureIdx, layout);
        for (int i = 0; i < m.fixedArity; i++) {
            int logicalSlot = m.paramSlots[i];
            ValType targetT = layout.slotTypes[logicalSlot];
            cg.aload(1 + i);
            cg.unboxFromObject(targetT);
            cg.storeLogicalSlot(logicalSlot);
        }

        if (callArity == m.fixedArity) {
            cg.aconstNull();
        } else {
            cg.newInst("cljl/lang/PersistentList");
            cg.dup();
            cg.aload(callArity);
            cg.invokespecial("cljl/lang/PersistentList", "<init>", "(Ljava/lang/Object;)V", 2, 0);
            for (int j = callArity - 1; j >= m.fixedArity + 1; j--) {
                cg.aload(j);
                cg.invokevirtual("cljl/lang/PersistentList", "cons",
                        "(Ljava/lang/Object;)Lcljl/lang/PersistentList;", 2, 1);
            }
        }
        int restSlot = m.paramSlots[m.fixedArity];
        cg.unboxFromObject(layout.slotTypes[restSlot]);
        cg.storeLogicalSlot(restSlot);

        Label fnLoopStart = new Label();
        cg.markLabel(fnLoopStart);
        cg.pushLoopTarget(m.paramSlots, fnLoopStart);
        boolean terminated = cg.emitBoxed(m.body);
        cg.popLoopTarget();
        if (!terminated) {
            cg.areturn();
        }
        cg.finish();
    }

    private static void emitVarargsInvoke(
            ClassEmitter ce, FnMethod[] fixed, AnalysisResult[] genericFixed,
            FnMethod variadic, AnalysisResult genericVariadic) {
        int baseSlot = 3;
        int maxNeededLocals = baseSlot;
        SlotLayout[] fixedLayouts = new SlotLayout[fixed.length];
        for (int i = 5; i < fixed.length; i++) {
            if (fixed[i] != null) {
                fixedLayouts[i] = new SlotLayout(fixed[i], genericFixed[i].slotTypes, baseSlot);
                maxNeededLocals = Math.max(maxNeededLocals, fixedLayouts[i].totalLocals);
            }
        }
        SlotLayout varLayout = null;
        if (variadic != null) {
            varLayout = new SlotLayout(variadic, genericVariadic.slotTypes, baseSlot);
            maxNeededLocals = Math.max(maxNeededLocals, varLayout.totalLocals);
        }

        CodeGen cg = ce.beginMethod("invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", maxNeededLocals);
        int lenLocal = 2;

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
                emitVarargsMethodBody(ce, cg, m, genericFixed[i], fixedLayouts[i], i, false);
            }
            cg.markLabel(nextCheck);
            cg.curStack = 0;
        }

        if (variadic != null) {
            Label errLabel = new Label();
            cg.iload(lenLocal);
            cg.iconst(variadic.fixedArity);
            cg.ifIcmpLt(errLabel);
            emitVarargsMethodBody(ce, cg, variadic, genericVariadic, varLayout, fixed.length, true);
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
            ClassEmitter ce, CodeGen cg, FnMethod m, AnalysisResult analysis, SlotLayout layout,
            int captureIdx, boolean isVariadic) {
        cg.setContext(m, analysis, layout);
        if (m.selfSlot != -1) {
            cg.aload(0);
            cg.astore(layout.jvmSlot[m.selfSlot]);
        }
        emitInitCaptured(ce, cg, m, captureIdx, layout);
        for (int i = 0; i < m.fixedArity; i++) {
            int logicalSlot = m.paramSlots[i];
            ValType targetT = layout.slotTypes[logicalSlot];
            cg.aload(1);
            cg.iconst(i);
            cg.aaload();
            cg.unboxFromObject(targetT);
            cg.storeLogicalSlot(logicalSlot);
        }
        if (isVariadic) {
            Label nonEmpty = new Label();
            Label doneRest = new Label();
            cg.iload(2);
            cg.iconst(m.fixedArity);
            cg.ifIcmpGt(nonEmpty);
            cg.aconstNull();
            cg.gotoLabel(doneRest);
            cg.curStack = 0;
            cg.markLabel(nonEmpty);
            cg.aload(1);
            cg.iconst(m.fixedArity);
            cg.invokestatic("cljl/lang/PersistentList", "createFromArraySlice",
                    "([Ljava/lang/Object;I)Lcljl/lang/ISeq;", 2, 1);
            cg.markLabel(doneRest);
            int restSlot = m.paramSlots[m.fixedArity];
            cg.unboxFromObject(layout.slotTypes[restSlot]);
            cg.storeLogicalSlot(restSlot);
        }

        Label loopStart = new Label();
        cg.markLabel(loopStart);
        cg.pushLoopTarget(m.paramSlots, loopStart);
        boolean terminated = cg.emitBoxed(m.body);
        cg.popLoopTarget();
        if (!terminated) {
            cg.areturn();
        }
    }

    private static void emitInitCaptured(
            ClassEmitter ce, CodeGen cg, FnMethod m, int methodCaptureIdx, SlotLayout layout) {
        if (m.capturedLocalSlots.length == 0) return;
        cg.aload(0);
        cg.getfield(ce.className, "captured", "[[Ljava/lang/Object;", 1);
        cg.iconst(methodCaptureIdx);
        cg.aaload();
        for (int i = 0; i < m.capturedLocalSlots.length; i++) {
            if (i + 1 < m.capturedLocalSlots.length) cg.dup();
            cg.iconst(i);
            cg.aaload();
            int logicalSlot = m.capturedLocalSlots[i];
            cg.unboxFromObject(layout.slotTypes[logicalSlot]);
            cg.storeLogicalSlot(logicalSlot);
        }
    }

    // ========================================================================
    // Minimal JVM ClassFile Emitter (Version 49.0 — no StackMapTable required)
    // ========================================================================

    static final class Label {
        int pos = -1;
        final List<int[]> fixups = new ArrayList<>();
        final List<int[]> wideFixups = new ArrayList<>();
    }

    private record LoopTarget(int[] slots, Label start) {}

    private record ExceptionEntry(Label start, Label end, Label handler, int catchTypeCpIdx) {}

    static final class ClassEmitter {
        final String className;
        final String sourceFile;
        FnMethod[] fixedMethods;
        CompiledFn ownerFn;
        Var ownerVar;
        final Map<Integer, List<PrimSpec>> primSpecsByArity = new LinkedHashMap<>();
        final JitConstantPool cp = new JitConstantPool();
        final List<Object> constObjects = new ArrayList<>();
        final IdentityHashMap<Object, Integer> constIndexMap = new IdentityHashMap<>();
        final List<byte[]> methodInfos = new ArrayList<>();

        final int thisClassIdx;
        final int superClassIdx;
        final int codeAttrNameIdx;
        final int sourceFileAttrNameIdx;
        final int sourceFileUtfIdx;

        ClassEmitter(String className, String sourceFile) {
            this.className = className;
            this.sourceFile = sourceFile;
            this.thisClassIdx = cp.addClass(className);
            this.superClassIdx = cp.addClass("cljl/Fn");
            this.codeAttrNameIdx = cp.addUtf8("Code");
            this.sourceFileAttrNameIdx = cp.addUtf8("SourceFile");
            this.sourceFileUtfIdx = cp.addUtf8(sourceFile);
            cp.addFieldref(className, "captured", "[[Ljava/lang/Object;");
            cp.addFieldref(className, "name", "Ljava/lang/String;");
            cp.addFieldref(className, "outerFn", "Lcljl/Compiler$CompiledFn;");

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
            return new CodeGen(this, 0x0001, name, desc, maxLocals);
        }

        CodeGen beginStaticMethod(String name, String desc, int maxLocals) {
            return new CodeGen(this, 0x000A, name, desc, maxLocals);
        }

        private void emitConstructor() {
            CodeGen cg = beginMethod(
                    "<init>",
                    "([[Ljava/lang/Object;Ljava/lang/String;Lcljl/Compiler$CompiledFn;)V",
                    4);
            cg.aload(0);
            cg.invokespecial("cljl/Fn", "<init>", "()V", 1, 0);
            cg.aload(0);
            cg.aload(1);
            cg.putfield(className, "captured", "[[Ljava/lang/Object;");
            cg.aload(0);
            cg.aload(2);
            cg.putfield(className, "name", "Ljava/lang/String;");
            cg.aload(0);
            cg.aload(3);
            cg.putfield(className, "outerFn", "Lcljl/Compiler$CompiledFn;");
            cg.vreturn();
            cg.finish();
        }

        private void emitClinitForConsts() {
            int n = constObjects.size();
            if (n == 0) return;
            CodeGen cg = new CodeGen(this, 0x0008, "<clinit>", "()V", 1);
            cg.ldcString(className);
            cg.invokestatic("cljl/BytecodeJIT", "takePendingConsts", "(Ljava/lang/String;)[Ljava/lang/Object;", 1, 1);
            cg.astore(0);
            for (int i = 0; i < n; i++) {
                cg.aload(0);
                cg.iconst(i);
                cg.aaload();
                cg.putstatic(className, "k" + i, "Ljava/lang/Object;", 1);
            }
            cg.vreturn();
            cg.finish();
        }

        private void emitGetName() {
            CodeGen cg = beginMethod("getName", "()Ljava/lang/String;", 1);
            cg.aload(0);
            cg.getfield(className, "name", "Ljava/lang/String;", 1);
            cg.areturn();
            cg.finish();
        }

        byte[] toByteArray() throws IOException {
            emitClinitForConsts();
            // Pre-intern field name/desc Utf8 entries before writing constant pool
            int nConsts = constObjects.size();
            int[] kNameIdxs = new int[nConsts];
            int objDescIdx = cp.addUtf8("Ljava/lang/Object;");
            for (int i = 0; i < nConsts; i++) {
                kNameIdxs[i] = cp.addUtf8("k" + i);
            }
            int capNameIdx = cp.addUtf8("captured");
            int capDescIdx = cp.addUtf8("[[Ljava/lang/Object;");
            int nameNameIdx = cp.addUtf8("name");
            int strDescIdx = cp.addUtf8("Ljava/lang/String;");
            int outerNameIdx = cp.addUtf8("outerFn");
            int outerDescIdx = cp.addUtf8("Lcljl/Compiler$CompiledFn;");

            ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
            DataOutputStream out = new DataOutputStream(baos);

            out.writeInt(0xCAFEBABE);
            out.writeShort(0);   // minor
            out.writeShort(49);  // major (Java 5 — no StackMapTable needed)

            cp.writeTo(out);

            out.writeShort(0x0011); // ACC_PUBLIC | ACC_FINAL
            out.writeShort(thisClassIdx);
            out.writeShort(superClassIdx);
            out.writeShort(0); // interfaces count

            // 3 instance fields + nConsts static final constant fields (trusted as compile-time constants by HotSpot C2)
            out.writeShort(3 + nConsts);
            writeFieldIdx(out, 0x0012, capNameIdx, capDescIdx);
            writeFieldIdx(out, 0x0012, nameNameIdx, strDescIdx);
            writeFieldIdx(out, 0x0012, outerNameIdx, outerDescIdx);
            for (int i = 0; i < nConsts; i++) {
                writeFieldIdx(out, 0x001A, kNameIdxs[i], objDescIdx); // PRIVATE | STATIC | FINAL
            }

            out.writeShort(methodInfos.size());
            for (byte[] m : methodInfos) {
                out.write(m);
            }

            out.writeShort(1); // SourceFile attribute
            out.writeShort(sourceFileAttrNameIdx);
            out.writeInt(2);
            out.writeShort(sourceFileUtfIdx);
            out.flush();
            return baos.toByteArray();
        }

        private void writeFieldIdx(DataOutputStream out, int flags, int nameIdx, int descIdx) throws IOException {
            out.writeShort(flags);
            out.writeShort(nameIdx);
            out.writeShort(descIdx);
            out.writeShort(0);
        }
    }

    static final class CodeGen {
        final ClassEmitter ce;
        final int accessFlags;
        final int nameIdx;
        final int descIdx;
        final int maxLocals;
        final ByteArrayOutputStream code = new ByteArrayOutputStream(256);
        final Deque<LoopTarget> loopTargets = new ArrayDeque<>();
        final List<ExceptionEntry> exceptionTable = new ArrayList<>();
        final List<Label> allLabels = new ArrayList<>();
        final Set<Integer> boundedBelowMaxLong = new HashSet<>();
        final Set<Integer> boundedAboveMinLong = new HashSet<>();
        final Set<Integer> nonNegativeLong = new HashSet<>();
        final Set<Integer> boundedBelowMaxVolatiles = new HashSet<>();
        final Set<Integer> boundedAboveMinVolatiles = new HashSet<>();

        FnMethod currentMethod;
        AnalysisResult analysis;
        AnalysisResult genericAnalysis;
        SlotLayout layout;
        int selfSlot = -1;
        PrimSpec staticFastSelfSpec = null;
        String staticFastSelfMethodName = null;
        boolean staticFastSelfCanInline = false;
        int staticFastSelfInlineDepth = 0;
        int curTempLocals = 0;
        int maxTempLocals = 0;
        int curStack = 0;
        int maxStack = 0;

        CodeGen(ClassEmitter ce, int accessFlags, String name, String desc, int maxLocals) {
            this.ce = ce;
            this.accessFlags = accessFlags;
            this.nameIdx = ce.cp.addUtf8(name);
            this.descIdx = ce.cp.addUtf8(desc);
            this.maxLocals = maxLocals;
        }

        void setContext(FnMethod method, AnalysisResult analysis, SlotLayout layout) {
            this.currentMethod = method;
            this.analysis = analysis;
            this.layout = layout;
            this.selfSlot = method != null ? method.selfSlot : -1;
        }

        ValType typeOf(Node n) {
            return analysis != null ? analysis.typeOf(n) : ValType.ANY;
        }

        int allocTempLocal() {
            int slot = maxLocals + curTempLocals++;
            if (curTempLocals > maxTempLocals) maxTempLocals = curTempLocals;
            return slot;
        }

        int allocTempLocal(int words) {
            int slot = maxLocals + curTempLocals;
            curTempLocals += words;
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
            int delta;
            if (opcode == GOTO) delta = 0;
            else if (opcode >= IF_ICMPEQ && opcode <= IF_ACMPNE) delta = -2;
            else delta = -1;
            adjStack(delta);
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
        void iflt(Label l) { emitJump(IFLT, l); }
        void ifge(Label l) { emitJump(IFGE, l); }
        void ifgt(Label l) { emitJump(IFGT, l); }
        void ifle(Label l) { emitJump(IFLE, l); }
        void ifIcmpNe(Label l) { emitJump(IF_ICMPNE, l); }
        void ifIcmpLt(Label l) { emitJump(IF_ICMPLT, l); }
        void ifIcmpGt(Label l) { emitJump(IF_ICMPGT, l); }
        void ifnull(Label l) { emitJump(IFNULL, l); }
        void ifnonnull(Label l) { emitJump(IFNONNULL, l); }
        void ifAcmpNe(Label l) { emitJump(IF_ACMPNE, l); }
        void ifAcmpEq(Label l) { emitJump(IF_ACMPEQ, l); }

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
            if (v == 0L) {
                code.write(LCONST_0);
                adjStack(2);
                return;
            }
            if (v == 1L) {
                code.write(LCONST_1);
                adjStack(2);
                return;
            }
            int idx = ce.cp.addLong(v);
            code.write(LDC2_W);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(2);
        }

        void ldcDouble(double v) {
            if (Double.doubleToRawLongBits(v) == 0L) {
                code.write(DCONST_0);
                adjStack(2);
                return;
            }
            if (v == 1.0) {
                code.write(DCONST_1);
                adjStack(2);
                return;
            }
            int idx = ce.cp.addDouble(v);
            code.write(LDC2_W);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(2);
        }

        private void emitLocalOp(int baseShort, int wideOp, int idx, int stackDelta) {
            if (idx <= 3) {
                code.write(baseShort + idx);
            } else if (idx <= 255) {
                code.write(wideOp);
                code.write(idx);
            } else {
                code.write(WIDE);
                code.write(wideOp);
                code.write((idx >>> 8) & 0xff);
                code.write(idx & 0xff);
            }
            adjStack(stackDelta);
        }

        void aload(int idx) { emitLocalOp(ALOAD_0, ALOAD, idx, 1); }
        void astore(int idx) { emitLocalOp(0x4b, ASTORE, idx, -1); }
        void iload(int idx) { emitLocalOp(0x1a, ILOAD, idx, 1); }
        void istore(int idx) { emitLocalOp(0x3b, ISTORE, idx, -1); }
        void lload(int idx) { emitLocalOp(0x1e, LLOAD, idx, 2); }
        void lstore(int idx) { emitLocalOp(0x3f, LSTORE, idx, -2); }
        void dload(int idx) { emitLocalOp(0x26, DLOAD, idx, 2); }
        void dstore(int idx) { emitLocalOp(0x47, DSTORE, idx, -2); }

        void loadJvmLocal(int phys, ValType t) {
            if (t == ValType.LONG) lload(phys);
            else if (t == ValType.DOUBLE) dload(phys);
            else if (t == ValType.BOOL) iload(phys);
            else aload(phys);
        }

        void storeJvmLocal(int phys, ValType t) {
            if (t == ValType.LONG) lstore(phys);
            else if (t == ValType.DOUBLE) dstore(phys);
            else if (t == ValType.BOOL) istore(phys);
            else astore(phys);
        }

        void storeLogicalSlot(int logicalSlot) {
            int phys = layout.jvmSlot[logicalSlot];
            ValType st = layout.slotTypes[logicalSlot];
            if (st == ValType.LONG) lstore(phys);
            else if (st == ValType.DOUBLE) dstore(phys);
            else if (st == ValType.BOOL) istore(phys);
            else astore(phys);
        }

        void loadLogicalSlot(int logicalSlot) {
            int phys = layout.jvmSlot[logicalSlot];
            loadJvmLocal(phys, layout.slotTypes[logicalSlot]);
        }

        void iaload() { code.write(IALOAD); adjStack(-1); }
        void laload() { code.write(LALOAD); }
        void faload() { code.write(FALOAD); adjStack(-1); }
        void daload() { code.write(DALOAD); }
        void aaload() { code.write(AALOAD); adjStack(-1); }
        void baload() { code.write(BALOAD); adjStack(-1); }
        void caload() { code.write(CALOAD); adjStack(-1); }
        void saload() { code.write(SALOAD); adjStack(-1); }
        void iastore() { code.write(IASTORE); adjStack(-3); }
        void lastore() { code.write(LASTORE); adjStack(-4); }
        void fastore() { code.write(FASTORE); adjStack(-3); }
        void dastore() { code.write(DASTORE); adjStack(-4); }
        void aastore() { code.write(AASTORE); adjStack(-3); }
        void bastore() { code.write(BASTORE); adjStack(-3); }
        void castore() { code.write(CASTORE); adjStack(-3); }
        void sastore() { code.write(SASTORE); adjStack(-3); }
        void arraylength() { code.write(ARRAYLENGTH); }
        void pop() { code.write(POP); adjStack(-1); }
        void pop2() { code.write(POP2); adjStack(-2); }
        void dup() { code.write(DUP); adjStack(1); }
        void dupX2() { code.write(DUP_X2); adjStack(1); }
        void dup2() { code.write(DUP2); adjStack(2); }
        void dup2X2() { code.write(DUP2_X2); adjStack(2); }
        void ixor() { code.write(IXOR); adjStack(-1); }
        void ladd() { code.write(LADD); adjStack(-2); }
        void lsub() { code.write(LSUB); adjStack(-2); }
        void lmul() { code.write(LMUL); adjStack(-2); }
        void ldiv() { code.write(LDIV); adjStack(-2); }
        void lrem() { code.write(LREM); adjStack(-2); }
        void lneg() { code.write(LNEG); }
        void lshl() { code.write(LSHL); adjStack(-1); }
        void lshr() { code.write(LSHR); adjStack(-1); }
        void lushr() { code.write(LUSHR); adjStack(-1); }
        void land() { code.write(LAND); adjStack(-2); }
        void lor() { code.write(LOR); adjStack(-2); }
        void lxor() { code.write(LXOR); adjStack(-2); }
        void dadd() { code.write(DADD); adjStack(-2); }
        void dsub() { code.write(DSUB); adjStack(-2); }
        void dmul() { code.write(DMUL); adjStack(-2); }
        void ddiv() { code.write(DDIV); adjStack(-2); }
        void drem() { code.write(DREM); adjStack(-2); }
        void dneg() { code.write(DNEG); }
        void i2l() { code.write(I2L); adjStack(1); }
        void l2i() { code.write(L2I); adjStack(-1); }
        void l2d() { code.write(L2D); }
        void f2d() { code.write(F2D); adjStack(1); }
        void d2l() { code.write(D2L); }
        void d2f() { code.write(D2F); adjStack(-1); }
        void i2b() { code.write(I2B); }
        void i2s() { code.write(I2S); }
        void lcmp() { code.write(LCMP); adjStack(-3); }
        void dcmpl() { code.write(DCMPL); adjStack(-3); }
        void dcmpg() { code.write(DCMPG); adjStack(-3); }
        void ireturn() { code.write(IRETURN); adjStack(-1); }
        void lreturn() { code.write(LRETURN); adjStack(-2); }
        void dreturn() { code.write(DRETURN); adjStack(-2); }
        void areturn() { code.write(ARETURN); adjStack(-1); }
        void vreturn() { code.write(RETURN); }
        void athrow() { code.write(ATHROW); adjStack(-1); }
        void monitorenter() { code.write(MONITORENTER); adjStack(-1); }
        void monitorexit() { code.write(MONITOREXIT); adjStack(-1); }

        void newInst(String internalName) {
            int idx = ce.cp.addClass(internalName);
            code.write(NEW);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(1);
        }

        void anewarray(String internalName) {
            int idx = ce.cp.addClass(internalName);
            code.write(ANEWARRAY);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
        }

        void checkcast(String internalName) {
            int idx = ce.cp.addClass(internalName);
            code.write(CHECKCAST);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
        }

        void emitInstanceof(String internalName) {
            int idx = ce.cp.addClass(internalName);
            code.write(INSTANCEOF);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
        }

        void getstatic(String owner, String name, String desc, int width) {
            int idx = ce.cp.addFieldref(owner, name, desc);
            code.write(GETSTATIC);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(width);
        }

        void putstatic(String owner, String name, String desc, int width) {
            int idx = ce.cp.addFieldref(owner, name, desc);
            code.write(0xb3); // PUTSTATIC
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(-width);
        }

        void getfield(String owner, String name, String desc, int width) {
            int idx = ce.cp.addFieldref(owner, name, desc);
            code.write(GETFIELD);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            adjStack(width - 1);
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

        void invokeinterface(String owner, String name, String desc, int argSlots, int retSlots) {
            int idx = ce.cp.addInterfaceMethodref(owner, name, desc);
            code.write(INVOKEINTERFACE);
            code.write((idx >>> 8) & 0xff);
            code.write(idx & 0xff);
            code.write(argSlots & 0xff);
            code.write(0);
            adjStack(retSlots - argSlots);
        }

        void loadConstObj(Object obj) {
            int cIdx = ce.addConstObject(obj);
            getstatic(ce.className, "k" + cIdx, "Ljava/lang/Object;", 1);
        }

        // ====================================================================
        // Type Conversion Helpers
        // ====================================================================

        void boxValue(ValType t) {
            if (t == ValType.LONG) {
                invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", 2, 1);
            } else if (t == ValType.DOUBLE) {
                invokestatic("java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", 2, 1);
            } else if (t == ValType.BOOL) {
                invokestatic("java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", 1, 1);
            }
        }

        void unboxFromObject(ValType targetT) {
            if (targetT == ValType.LONG) {
                invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
            } else if (targetT == ValType.DOUBLE) {
                invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
            } else if (targetT == ValType.BOOL) {
                invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z", 1, 1);
            }
        }

        void convertValue(ValType from, ValType to) {
            if (from == to) return;
            if (from == ValType.LONG && to == ValType.DOUBLE) {
                l2d();
                return;
            }
            if (from == ValType.DOUBLE && to == ValType.LONG) {
                d2l();
                return;
            }
            if (from.isPrimitive() && !to.isPrimitive()) {
                boxValue(from);
            } else if (!from.isPrimitive() && to.isPrimitive()) {
                unboxFromObject(to);
            }
        }

        boolean emitReturnTyped(Node body, ValType retT) {
            if (retT == ValType.LONG) return emitLong(body);
            if (retT == ValType.DOUBLE) return emitDouble(body);
            if (retT == ValType.BOOL) return emitBoolean(body);
            return emitBoxed(body);
        }

        void emitReturnInstruction(ValType retT) {
            if (retT == ValType.LONG) lreturn();
            else if (retT == ValType.DOUBLE) dreturn();
            else if (retT == ValType.BOOL) ireturn();
            else areturn();
        }

        private int scalarVolatileRoot(Node target) {
            return analysis != null ? analysis.resolveScalarVolatileRoot(target) : -1;
        }

        private int scalarAtomRoot(Node target) {
            return analysis != null ? analysis.resolveScalarAtomRoot(target) : -1;
        }

        private int scalarBoxRoot(Node target) {
            int r = scalarVolatileRoot(target);
            return r != -1 ? r : scalarAtomRoot(target);
        }

        private int scalarLongVolatileSlot(Node n) {
            if (n instanceof DerefNode dn) {
                int root = scalarBoxRoot(dn.target);
                if (root != -1 && layout != null && layout.slotTypes[root] == ValType.LONG) {
                    return root;
                }
            }
            return -1;
        }

        private void invalidateVolatileBounds(int root) {
            boundedBelowMaxVolatiles.remove(root);
            boundedAboveMinVolatiles.remove(root);
        }

        private void emitMaterializeScalarVolatile(int rootSlot) {
            ValType st = layout.slotTypes[rootSlot];
            newInst("cljl/Atom$Volatile");
            dup();
            loadLogicalSlot(rootSlot);
            if (st == ValType.LONG) {
                invokespecial("cljl/Atom$Volatile", "<init>", "(J)V", 3, 0);
            } else if (st == ValType.DOUBLE) {
                invokespecial("cljl/Atom$Volatile", "<init>", "(D)V", 3, 0);
            } else {
                if (st == ValType.BOOL) boxValue(ValType.BOOL);
                invokespecial("cljl/Atom$Volatile", "<init>", "(Ljava/lang/Object;)V", 2, 0);
            }
        }

        private void emitMaterializeScalarAtom(int rootSlot) {
            ValType st = layout.slotTypes[rootSlot];
            newInst("cljl/Atom");
            dup();
            loadLogicalSlot(rootSlot);
            if (st == ValType.LONG) {
                invokespecial("cljl/Atom", "<init>", "(J)V", 3, 0);
            } else {
                boxValue(st);
                invokespecial("cljl/Atom", "<init>", "(Ljava/lang/Object;)V", 2, 0);
            }
        }

        private static String coreSwapLongField(String name, int extraArgCount) {
            if (extraArgCount == 0) {
                return switch (name) {
                    case "inc" -> "incFn";
                    case "dec" -> "decFn";
                    case "unchecked-inc" -> "uncheckedIncFn";
                    case "unchecked-dec" -> "uncheckedDecFn";
                    default -> null;
                };
            }
            if (extraArgCount == 1) {
                return switch (name) {
                    case "+" -> "addFn";
                    case "-" -> "subFn";
                    case "*" -> "mulFn";
                    case "unchecked-add" -> "uncheckedAddFn";
                    case "unchecked-subtract" -> "uncheckedSubFn";
                    case "unchecked-multiply" -> "uncheckedMulFn";
                    default -> null;
                };
            }
            return null;
        }

        private boolean emitScalarAtomSwap(SwapBangNode sb, int root, ValType resultMode) {
            ValType st = layout.slotTypes[root];
            // Fast inline unboxed long path for core arithmetic ops (with with-redefs guard)
            if (st == ValType.LONG && sb.fn instanceof VarRefNode vr && vr.var != null && vr.var.ns == RT.CLOJURE_NS) {
                String name = vr.var.sym.name;
                String coreField = coreSwapLongField(name, sb.extraArgs.length);
                if (coreField != null && (sb.extraArgs.length == 0 || typeOf(sb.extraArgs[0]) == ValType.LONG)) {
                    int savedTemp = curTempLocals;
                    int argSlot = -1;
                    if (sb.extraArgs.length == 1) {
                        if (emitLong(sb.extraArgs[0])) { curTempLocals = savedTemp; return true; }
                        argSlot = allocTempLocal(2);
                        lstore(argSlot);
                    }
                    int stackAtBranch = curStack;
                    getstatic("cljl/Core", "coreRedefined", "Z", 1);
                    Label slowLbl = new Label();
                    Label doneLbl = new Label();
                    ifne(slowLbl);

                    // Fast unboxed path
                    loadLogicalSlot(root);
                    if (sb.extraArgs.length == 0) {
                        switch (name) {
                            case "inc" -> {
                                ldcLong(1L);
                                if (boundedBelowMaxVolatiles.contains(root)) ladd();
                                else invokestatic("java/lang/Math", "addExact", "(JJ)J", 4, 2);
                            }
                            case "dec" -> {
                                ldcLong(1L);
                                if (boundedAboveMinVolatiles.contains(root)) lsub();
                                else invokestatic("java/lang/Math", "subtractExact", "(JJ)J", 4, 2);
                            }
                            case "unchecked-inc" -> { ldcLong(1L); ladd(); }
                            case "unchecked-dec" -> { ldcLong(1L); lsub(); }
                        }
                    } else {
                        lload(argSlot);
                        switch (name) {
                            case "+" -> invokestatic("java/lang/Math", "addExact", "(JJ)J", 4, 2);
                            case "-" -> invokestatic("java/lang/Math", "subtractExact", "(JJ)J", 4, 2);
                            case "*" -> invokestatic("java/lang/Math", "multiplyExact", "(JJ)J", 4, 2);
                            case "unchecked-add" -> ladd();
                            case "unchecked-subtract" -> lsub();
                            case "unchecked-multiply" -> lmul();
                        }
                    }
                    gotoLabel(doneLbl);

                    // Slow path (if core arithmetic Var was rebound via with-redefs)
                    curStack = stackAtBranch;
                    markLabel(slowLbl);
                    emitVarFn(vr.var);
                    loadLogicalSlot(root);
                    boxValue(ValType.LONG);
                    if (sb.extraArgs.length == 0) {
                        invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                    } else {
                        lload(argSlot);
                        boxValue(ValType.LONG);
                        invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                    }
                    invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);

                    markLabel(doneLbl);
                    curStack = stackAtBranch + 2;
                    curTempLocals = savedTemp;

                    if (resultMode == ValType.LONG) {
                        dup2();
                        storeLogicalSlot(root);
                        invalidateVolatileBounds(root);
                        return false;
                    }
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    if (resultMode != null) emitScalarSlotAs(root, resultMode);
                    return false;
                }
            }

            // General scalar-replaced atom swap: local = fn(local, extraArgs...)
            if (sb.fn instanceof VarRefNode vr) emitVarFn(vr.var);
            else emitRef(sb.fn, Fn.class);
            emitScalarSlotAs(root, ValType.ANY);
            int extra = sb.extraArgs.length;
            if (extra <= 3) {
                for (int i = 0; i < extra; i++) {
                    if (emitBoxed(sb.extraArgs[i])) return true;
                }
                String desc = switch (extra) {
                    case 0 -> "(Ljava/lang/Object;)Ljava/lang/Object;";
                    case 1 -> "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                    case 2 -> "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                    default -> "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                };
                invokevirtual("cljl/Fn", "invoke", desc, 2 + extra, 1);
            } else {
                // Build Object[] { currentVal, extraArgs... }
                int savedTemp = curTempLocals;
                int valTmp = allocTempLocal(1);
                astore(valTmp);
                iconst(1 + extra);
                anewarray("java/lang/Object");
                dup();
                iconst(0);
                aload(valTmp);
                aastore();
                for (int i = 0; i < extra; i++) {
                    dup();
                    iconst(1 + i);
                    if (emitBoxed(sb.extraArgs[i])) { curTempLocals = savedTemp; return true; }
                    aastore();
                }
                curTempLocals = savedTemp;
                invokevirtual("cljl/Fn", "applyTo", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
            }
            if (st == ValType.LONG) {
                invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
            } else if (st == ValType.DOUBLE) {
                invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
            } else if (st == ValType.BOOL) {
                invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z", 1, 1);
            } else if (st.isRef() && st.refClass != null && st.refClass != Object.class) {
                checkcast(st.refClass.getName().replace('.', '/'));
            }
            storeLogicalSlot(root);
            invalidateVolatileBounds(root);
            if (resultMode != null) emitScalarSlotAs(root, resultMode);
            return false;
        }

        private static boolean isSameLocalDeref(Node n, int targetSlot) {
            return n instanceof DerefNode dn && dn.target instanceof LocalNode ln && ln.slot == targetSlot;
        }

        /**
         * Fuses (vreset! v (op @v ...)) / (vswap! v op ...) on an escaping Atom.Volatile local
         * into a single allocation-free Atom$Volatile.vswap* call.
         * Returns: 0 = not fused, 1 = fused normally, 2 = fused and terminated.
         */
        private int tryEmitFusedVreset(VresetNode vr, ValType resultMode) {
            if (!(vr.target instanceof LocalNode tl)) return 0;
            int slot = tl.slot;
            Node val = vr.val;

            if (val instanceof IncNode a && isSameLocalDeref(a.a, slot)) {
                emitRef(vr.target, Atom.Volatile.class);
                if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapIncDiscard", "()V", 1, 0);
                else if (resultMode == ValType.LONG) invokevirtual("cljl/Atom$Volatile", "vswapIncLong", "()J", 1, 2);
                else if (resultMode == ValType.ANY) invokevirtual("cljl/Atom$Volatile", "vswapInc", "()Ljava/lang/Object;", 1, 1);
                else return 0;
                return 1;
            }
            if (val instanceof DecNode a && isSameLocalDeref(a.a, slot)) {
                emitRef(vr.target, Atom.Volatile.class);
                if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapDecDiscard", "()V", 1, 0);
                else if (resultMode == ValType.LONG) invokevirtual("cljl/Atom$Volatile", "vswapDecLong", "()J", 1, 2);
                else if (resultMode == ValType.ANY) invokevirtual("cljl/Atom$Volatile", "vswapDec", "()Ljava/lang/Object;", 1, 1);
                else return 0;
                return 1;
            }
            if (val instanceof UncheckedIncNode a && isSameLocalDeref(a.a, slot)) {
                emitRef(vr.target, Atom.Volatile.class);
                if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedIncDiscard", "()V", 1, 0);
                else if (resultMode == ValType.LONG) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedIncLong", "()J", 1, 2);
                else if (resultMode == ValType.ANY) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedInc", "()Ljava/lang/Object;", 1, 1);
                else return 0;
                return 1;
            }
            if (val instanceof UncheckedDecNode a && isSameLocalDeref(a.a, slot)) {
                emitRef(vr.target, Atom.Volatile.class);
                if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedDecDiscard", "()V", 1, 0);
                else if (resultMode == ValType.LONG) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedDecLong", "()J", 1, 2);
                else if (resultMode == ValType.ANY) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedDec", "()Ljava/lang/Object;", 1, 1);
                else return 0;
                return 1;
            }
            if (val instanceof Add2Node a && isSameLocalDeref(a.a, slot)) {
                ValType rt = typeOf(a.b);
                if (rt == ValType.LONG) {
                    if (resultMode == null || resultMode == ValType.LONG || resultMode == ValType.ANY) {
                        emitRef(vr.target, Atom.Volatile.class);
                        if (emitLong(a.b)) return 2;
                        if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapAddLongDiscard", "(J)V", 3, 0);
                        else if (resultMode == ValType.LONG) invokevirtual("cljl/Atom$Volatile", "vswapAddLongPrim", "(J)J", 3, 2);
                        else invokevirtual("cljl/Atom$Volatile", "vswapAddLong", "(J)Ljava/lang/Object;", 3, 1);
                        return 1;
                    }
                } else if (rt == ValType.DOUBLE) {
                    if (resultMode == null || resultMode == ValType.DOUBLE || resultMode == ValType.ANY) {
                        emitRef(vr.target, Atom.Volatile.class);
                        if (emitDouble(a.b)) return 2;
                        if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapAddDoubleDiscard", "(D)V", 3, 0);
                        else if (resultMode == ValType.DOUBLE) invokevirtual("cljl/Atom$Volatile", "vswapAddDoublePrim", "(D)D", 3, 2);
                        else invokevirtual("cljl/Atom$Volatile", "vswapAddDouble", "(D)Ljava/lang/Object;", 3, 1);
                        return 1;
                    }
                } else if (resultMode == null || resultMode == ValType.ANY) {
                    emitRef(vr.target, Atom.Volatile.class);
                    if (emitBoxed(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapAddDiscard", "(Ljava/lang/Object;)V", 2, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapAdd", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                    return 1;
                }
            }
            if (val instanceof UncheckedAdd2Node a && isSameLocalDeref(a.a, slot) && typeOf(a.b) == ValType.LONG) {
                if (resultMode == null || resultMode == ValType.ANY) {
                    emitRef(vr.target, Atom.Volatile.class);
                    if (emitLong(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapUncheckedAddLongDiscard", "(J)V", 3, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapUncheckedAddLong", "(J)Ljava/lang/Object;", 3, 1);
                    return 1;
                }
            }
            if (val instanceof Sub2Node a && isSameLocalDeref(a.a, slot) && (resultMode == null || resultMode == ValType.ANY)) {
                ValType rt = typeOf(a.b);
                emitRef(vr.target, Atom.Volatile.class);
                if (rt == ValType.LONG) {
                    if (emitLong(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapSubLongDiscard", "(J)V", 3, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapSubLong", "(J)Ljava/lang/Object;", 3, 1);
                } else if (rt == ValType.DOUBLE) {
                    if (emitDouble(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapSubDoubleDiscard", "(D)V", 3, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapSubDouble", "(D)Ljava/lang/Object;", 3, 1);
                } else {
                    if (emitBoxed(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapSubDiscard", "(Ljava/lang/Object;)V", 2, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapSub", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return 1;
            }
            if (val instanceof Mul2Node a && isSameLocalDeref(a.a, slot) && (resultMode == null || resultMode == ValType.ANY)) {
                ValType rt = typeOf(a.b);
                emitRef(vr.target, Atom.Volatile.class);
                if (rt == ValType.LONG) {
                    if (emitLong(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapMulLongDiscard", "(J)V", 3, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapMulLong", "(J)Ljava/lang/Object;", 3, 1);
                } else if (rt == ValType.DOUBLE) {
                    if (emitDouble(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapMulDoubleDiscard", "(D)V", 3, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapMulDouble", "(D)Ljava/lang/Object;", 3, 1);
                } else {
                    if (emitBoxed(a.b)) return 2;
                    if (resultMode == null) invokevirtual("cljl/Atom$Volatile", "vswapMulDiscard", "(Ljava/lang/Object;)V", 2, 0);
                    else invokevirtual("cljl/Atom$Volatile", "vswapMul", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return 1;
            }
            return 0;
        }

        private void emitScalarSlotAs(int rootSlot, ValType desiredT) {
            ValType st = layout.slotTypes[rootSlot];
            if (desiredT == ValType.BOOL) {
                if (st == ValType.BOOL) {
                    loadLogicalSlot(rootSlot);
                } else if (st == ValType.LONG || st == ValType.DOUBLE) {
                    iconst(1);
                } else {
                    loadLogicalSlot(rootSlot);
                    invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z", 1, 1);
                }
                return;
            }
            if (desiredT == ValType.LONG) {
                loadLogicalSlot(rootSlot);
                if (st == ValType.LONG) return;
                if (st == ValType.DOUBLE) {
                    invokestatic("cljl/Numbers", "longCast", "(D)J", 2, 2);
                    return;
                }
                if (st == ValType.BOOL) boxValue(ValType.BOOL);
                invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
                return;
            }
            if (desiredT == ValType.DOUBLE) {
                loadLogicalSlot(rootSlot);
                if (st == ValType.DOUBLE) return;
                if (st == ValType.LONG) {
                    l2d();
                    return;
                }
                if (st == ValType.BOOL) boxValue(ValType.BOOL);
                invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
                return;
            }
            loadLogicalSlot(rootSlot);
            boxValue(st);
        }

        private boolean emitLetSlotBinding(int slot, Node init) {
            if (analysis != null) {
                if (analysis.isScalarBoxAlias(slot)) {
                    return false;
                }
                if (analysis.scalarVolatileSlots.contains(slot) && init instanceof VolatileNode vn) {
                    if (emitSlotInit(slot, vn.init)) return true;
                    storeLogicalSlot(slot);
                    return false;
                }
                if (analysis.scalarAtomSlots.contains(slot) && init instanceof AtomNode an) {
                    if (emitSlotInit(slot, an.init)) return true;
                    storeLogicalSlot(slot);
                    return false;
                }
            }
            if (emitSlotInit(slot, init)) return true;
            storeLogicalSlot(slot);
            return false;
        }

        boolean emitDiscard(Node n) {
            ValType t = typeOf(n);
            if (n instanceof LetNode ln) {
                for (int i = 0; i < ln.slots.length; i++) {
                    if (emitLetSlotBinding(ln.slots[i], ln.inits[i])) return true;
                }
                return emitDiscard(ln.body);
            }
            if (n instanceof VresetNode vr) {
                int root = scalarVolatileRoot(vr.target);
                if (root != -1) {
                    if (emitSlotInit(root, vr.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    return false;
                }
                int fused = tryEmitFusedVreset(vr, null);
                if (fused != 0) return fused == 2;
                emitRef(vr.target, Atom.Volatile.class);
                ValType vt = typeOf(vr.val);
                if (vt == ValType.LONG) {
                    if (emitLong(vr.val)) return true;
                    invokevirtual("cljl/Atom$Volatile", "resetLong", "(J)J", 3, 2);
                    pop2();
                } else if (vt == ValType.DOUBLE) {
                    if (emitDouble(vr.val)) return true;
                    invokevirtual("cljl/Atom$Volatile", "resetDouble", "(D)D", 3, 2);
                    pop2();
                } else {
                    if (emitBoxed(vr.val)) return true;
                    invokevirtual("cljl/Atom$Volatile", "reset", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                    pop();
                }
                return false;
            }
            if (n instanceof SwapBangNode sb) {
                int root = scalarAtomRoot(sb.target);
                if (root != -1) {
                    return emitScalarAtomSwap(sb, root, null);
                }
                if (sb.extraArgs.length == 0) {
                    emitRef(sb.target, Atom.class);
                    if (sb.fn instanceof VarRefNode vr) emitVarFn(vr.var);
                    else emitRef(sb.fn, Fn.class);
                    invokevirtual("cljl/Atom", "swapDiscard", "(Lcljl/Fn;)V", 2, 0);
                    return false;
                }
                if (sb.extraArgs.length == 1) {
                    emitRef(sb.target, Atom.class);
                    if (sb.fn instanceof VarRefNode vr) emitVarFn(vr.var);
                    else emitRef(sb.fn, Fn.class);
                    if (typeOf(sb.extraArgs[0]) == ValType.LONG) {
                        if (emitLong(sb.extraArgs[0])) return true;
                        invokevirtual("cljl/Atom", "swapDiscard", "(Lcljl/Fn;J)V", 4, 0);
                    } else {
                        if (emitBoxed(sb.extraArgs[0])) return true;
                        invokevirtual("cljl/Atom", "swapDiscard", "(Lcljl/Fn;Ljava/lang/Object;)V", 3, 0);
                    }
                    return false;
                }
            }
            if (n instanceof ResetBangNode rb) {
                int root = scalarAtomRoot(rb.target);
                if (root != -1) {
                    if (emitSlotInit(root, rb.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    return false;
                }
                if (typeOf(rb.val) == ValType.LONG) {
                    emitRef(rb.target, Atom.class);
                    if (emitLong(rb.val)) return true;
                    invokevirtual("cljl/Atom", "resetLongDiscard", "(J)V", 3, 0);
                    return false;
                }
            }
            if (n instanceof StaticMethodNode sm && analysis != null) {
                Method m = analysis.resolvedMethods.get(sm);
                if (m != null && m.getReturnType() == void.class) {
                    emitResolvedStaticCallRaw(sm, m);
                    return false;
                }
            }
            if (n instanceof InstanceMethodNode im && analysis != null) {
                Method m = analysis.resolvedMethods.get(im);
                if (m != null && m.getReturnType() == void.class) {
                    emitResolvedInstanceCallRaw(im, m);
                    return false;
                }
            }
            if (t == ValType.LONG) {
                if (emitLong(n)) return true;
                pop2();
                return false;
            }
            if (t == ValType.DOUBLE) {
                if (emitDouble(n)) return true;
                pop2();
                return false;
            }
            if (t == ValType.BOOL) {
                if (emitBoolean(n)) return true;
                pop();
                return false;
            }
            if (emitBoxed(n)) return true;
            pop();
            return false;
        }

        void emitRef(Node n, Class<?> expected) {
            emitBoxed(n);
            if (expected != null && expected != Object.class) {
                checkcast(expected.getName().replace('.', '/'));
            }
        }

        private Class<?> arrayClassOf(Node arr) {
            ValType at = typeOf(arr);
            if (at.kind == ValType.Kind.REF && at.refClass != null && at.refClass.isArray()) {
                return at.refClass;
            }
            return null;
        }

        private void emitArrayAndIndex(Node arr, Class<?> arrCls, Node idx) {
            emitRef(arr, arrCls);
            emitLong(idx);
            l2i();
        }

        // ====================================================================
        // Typed Self-Call Lookup
        // ====================================================================

        private PrimSpec findMatchingPrimSpec(int arity, Node[] args) {
            List<PrimSpec> specs = ce.primSpecsByArity.get(arity);
            if (specs == null || specs.isEmpty()) return null;
            for (PrimSpec ps : specs) {
                boolean match = true;
                for (int i = 0; i < arity; i++) {
                    ValType pt = ps.paramTypes[i];
                    if (pt == ValType.ANY) continue;
                    if (!pt.equals(typeOf(args[i]))) {
                        match = false;
                        break;
                    }
                }
                if (match) return ps;
            }
            return null;
        }

        private boolean isDirectSelf(Node fnNode, int arity) {
            return selfSlot != -1 && fnNode instanceof LocalNode ln && ln.slot == selfSlot
                    && ce.fixedMethods != null && arity < ce.fixedMethods.length && ce.fixedMethods[arity] != null;
        }

        private boolean isOwnerVarCall(Var v, int arity) {
            return ce.ownerVar != null && v == ce.ownerVar && ce.ownerFn != null
                    && ce.fixedMethods != null && arity < ce.fixedMethods.length && ce.fixedMethods[arity] != null;
        }

        // ====================================================================
        // Long Emission (2 stack words, returns true if terminated)
        // ====================================================================

        boolean emitLong(Node node) {
            if (node instanceof ConstNode cn) {
                if (cn.isLong) {
                    ldcLong(cn.longVal);
                } else if (cn.val instanceof Number num) {
                    ldcLong(num.longValue());
                } else if (cn.val instanceof Character ch) {
                    ldcLong((long) ch);
                } else {
                    emitBoxedImpl(node);
                    invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
                }
                return false;
            }
            if (node instanceof LocalNode ln) {
                ValType st = layout.slotTypes[ln.slot];
                if (st == ValType.LONG) {
                    loadLogicalSlot(ln.slot);
                } else if (st == ValType.DOUBLE) {
                    loadLogicalSlot(ln.slot);
                    d2l();
                } else {
                    loadLogicalSlot(ln.slot);
                    invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
                }
                return false;
            }
            if (node instanceof VarRefNode) {
                emitBoxedImpl(node);
                invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
                return false;
            }
            if (node instanceof IfNode iff) {
                return emitIfTyped(iff, ValType.LONG);
            }
            if (node instanceof DoNode dn) {
                return emitDoTyped(dn, ValType.LONG);
            }
            if (node instanceof LetNode ln) {
                return emitLetTyped(ln, ValType.LONG);
            }
            if (node instanceof LoopNode lp) {
                return emitLoopTyped(lp, ValType.LONG);
            }
            if (node instanceof LockingNode ln) {
                return emitLockingTyped(ln, ValType.LONG);
            }
            if (node instanceof TryNode tn) {
                return emitTryTyped(tn, ValType.LONG);
            }
            if (node instanceof CaseNode cn) {
                return emitCaseTyped(cn, ValType.LONG);
            }
            if (node instanceof Recur0Node || node instanceof Recur1Node
                    || node instanceof Recur2Node || node instanceof RecurNNode) {
                return emitRecur(node);
            }
            if (node instanceof ThrowNode tn) {
                return emitThrow(tn);
            }
            if (node instanceof LongCastNode lc) {
                ValType at = typeOf(lc.a);
                if (at == ValType.LONG) return emitLong(lc.a);
                if (at == ValType.DOUBLE) {
                    if (emitDouble(lc.a)) return true;
                    if (lc.unchecked) {
                        d2l();
                    } else {
                        invokestatic("cljl/Numbers", "longCast", "(D)J", 2, 2);
                    }
                    return false;
                }
                if (!lc.unchecked) {
                    int primRes = tryEmitPrimCall(lc.a, ValType.LONG);
                    if (primRes != 0) return primRes == 2;
                }
                if (emitBoxed(lc.a)) return true;
                invokestatic("cljl/Numbers", lc.unchecked ? "uncheckedLong" : "longCast", "(Ljava/lang/Object;)J", 1, 2);
                return false;
            }
            if (node instanceof Add2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                invokestatic("java/lang/Math", "addExact", "(JJ)J", 4, 2);
                return false;
            }
            if (node instanceof Sub2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (a.a instanceof LocalNode ln && nonNegativeLong.contains(ln.slot)
                        && a.b instanceof ConstNode cn && cn.isLong && cn.longVal >= 0L) {
                    loadLogicalSlot(ln.slot);
                    ldcLong(cn.longVal);
                    lsub();
                    return false;
                }
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                invokestatic("java/lang/Math", "subtractExact", "(JJ)J", 4, 2);
                return false;
            }
            if (node instanceof Mul2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                invokestatic("java/lang/Math", "multiplyExact", "(JJ)J", 4, 2);
                return false;
            }
            if (node instanceof IncNode a && typeOf(a.a) == ValType.LONG) {
                if (a.a instanceof LocalNode ln && boundedBelowMaxLong.contains(ln.slot)) {
                    loadLogicalSlot(ln.slot);
                    ldcLong(1L);
                    ladd();
                    return false;
                }
                int vroot = scalarLongVolatileSlot(a.a);
                if (vroot != -1 && boundedBelowMaxVolatiles.contains(vroot)) {
                    loadLogicalSlot(vroot);
                    ldcLong(1L);
                    ladd();
                    return false;
                }
                if (emitLong(a.a)) return true;
                invokestatic("cljl/Numbers", "incExact", "(J)J", 2, 2);
                return false;
            }
            if (node instanceof DecNode a && typeOf(a.a) == ValType.LONG) {
                if (a.a instanceof LocalNode ln && boundedAboveMinLong.contains(ln.slot)) {
                    loadLogicalSlot(ln.slot);
                    ldcLong(1L);
                    lsub();
                    return false;
                }
                int vroot = scalarLongVolatileSlot(a.a);
                if (vroot != -1 && boundedAboveMinVolatiles.contains(vroot)) {
                    loadLogicalSlot(vroot);
                    ldcLong(1L);
                    lsub();
                    return false;
                }
                if (emitLong(a.a)) return true;
                invokestatic("cljl/Numbers", "decExact", "(J)J", 2, 2);
                return false;
            }
            if (node instanceof NegateNode a && typeOf(a.a) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                invokestatic("java/lang/Math", "negateExact", "(J)J", 2, 2);
                return false;
            }
            if (node instanceof Rem2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                lrem();
                return false;
            }
            if (node instanceof Mod2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                invokestatic("java/lang/Math", "floorMod", "(JJ)J", 4, 2);
                return false;
            }
            if (node instanceof Quot2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                ldiv();
                return false;
            }
            if (node instanceof UncheckedAdd2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                ladd();
                return false;
            }
            if (node instanceof UncheckedSub2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                lsub();
                return false;
            }
            if (node instanceof UncheckedMul2Node a && typeOf(a.a) == ValType.LONG && typeOf(a.b) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                if (emitLong(a.b)) return true;
                lmul();
                return false;
            }
            if (node instanceof UncheckedIncNode a && typeOf(a.a) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                ldcLong(1L);
                ladd();
                return false;
            }
            if (node instanceof UncheckedDecNode a && typeOf(a.a) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                ldcLong(1L);
                lsub();
                return false;
            }
            if (node instanceof UncheckedNegateNode a && typeOf(a.a) == ValType.LONG) {
                if (emitLong(a.a)) return true;
                lneg();
                return false;
            }
            if (node instanceof BitOp2Node b) {
                if (emitLong(b.a)) return true;
                if (emitLong(b.b)) return true;
                switch (b.op) {
                    case AND -> land();
                    case OR -> lor();
                    case XOR -> lxor();
                    case AND_NOT -> { ldcLong(-1L); lxor(); land(); }
                    case SHL -> { l2i(); lshl(); }
                    case SHR -> { l2i(); lshr(); }
                    case USHR -> { l2i(); lushr(); }
                }
                return false;
            }
            if (node instanceof BitNotNode b) {
                if (emitLong(b.a)) return true;
                ldcLong(-1L);
                lxor();
                return false;
            }
            if (node instanceof CountNode c) {
                ValType ct = typeOf(c.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentVector.class) {
                        emitRef(c.coll, PersistentVector.class);
                        getfield("cljl/lang/PersistentVector", "cnt", "I", 1);
                        i2l();
                        return false;
                    }
                    if (ct.refClass == PersistentHashMap.class) {
                        emitRef(c.coll, PersistentHashMap.class);
                        getfield("cljl/lang/PersistentHashMap", "count", "I", 1);
                        i2l();
                        return false;
                    }
                    if (ct.refClass == PersistentList.class) {
                        emitRef(c.coll, PersistentList.class);
                        getfield("cljl/lang/PersistentList", "count", "I", 1);
                        i2l();
                        return false;
                    }
                    if (Counted.class.isAssignableFrom(ct.refClass)) {
                        emitRef(c.coll, Counted.class);
                        invokeinterface("cljl/lang/Counted", "count", "()I", 1, 1);
                        i2l();
                        return false;
                    }
                    if (ct.refClass == String.class) {
                        emitRef(c.coll, String.class);
                        invokevirtual("java/lang/String", "length", "()I", 1, 1);
                        i2l();
                        return false;
                    }
                }
                emitBoxed(c.coll);
                invokestatic("cljl/RT", "count", "(Ljava/lang/Object;)I", 1, 1);
                i2l();
                return false;
            }
            if (node instanceof AlengthNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls != null) {
                    emitRef(a.arr, arrCls);
                    arraylength();
                    i2l();
                    return false;
                }
                emitBoxed(a.arr);
                invokestatic("cljl/RT", "alength", "(Ljava/lang/Object;)I", 1, 1);
                i2l();
                return false;
            }
            if (node instanceof AgetNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls == long[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx); laload(); return false;
                }
                if (arrCls == int[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx); iaload(); i2l(); return false;
                }
                if (arrCls == short[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx); saload(); i2l(); return false;
                }
                if (arrCls == byte[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx); baload(); i2l(); return false;
                }
                if (arrCls == double[].class || arrCls == float[].class) {
                    if (emitDouble(node)) return true;
                    invokestatic("cljl/Numbers", "longCast", "(D)J", 2, 2);
                    return false;
                }
            }
            if (node instanceof AsetNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls == long[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (emitLong(a.val)) return true;
                    dup2X2(); lastore(); return false;
                }
                if (arrCls == int[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (emitLong(a.val)) return true;
                    l2i(); dupX2(); iastore(); i2l(); return false;
                }
                if (arrCls == short[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (emitLong(a.val)) return true;
                    l2i(); i2s(); dupX2(); sastore(); i2l(); return false;
                }
                if (arrCls == byte[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (emitLong(a.val)) return true;
                    l2i(); i2b(); dupX2(); bastore(); i2l(); return false;
                }
            }
            if (node instanceof StaticMethodNode sm && analysis != null) {
                Method m = analysis.resolvedMethods.get(sm);
                if (m != null && ValType.fromJavaReturn(m.getReturnType()) == ValType.LONG) {
                    emitResolvedStaticCallRaw(sm, m);
                    coerceJavaReturnToStack(m.getReturnType(), ValType.LONG);
                    return false;
                }
            }
            if (node instanceof InstanceMethodNode im && analysis != null) {
                Method m = analysis.resolvedMethods.get(im);
                if (m != null && ValType.fromJavaReturn(m.getReturnType()) == ValType.LONG) {
                    emitResolvedInstanceCallRaw(im, m);
                    coerceJavaReturnToStack(m.getReturnType(), ValType.LONG);
                    return false;
                }
            }
            if (node instanceof StaticFieldNode sf && analysis != null) {
                Field f = analysis.resolvedFields.get(sf);
                if (f != null && ValType.fromJavaReturn(f.getType()) == ValType.LONG) {
                    emitResolvedStaticFieldRaw(f);
                    coerceJavaReturnToStack(f.getType(), ValType.LONG);
                    return false;
                }
            }
            if (node instanceof InstanceFieldNode inf && analysis != null) {
                Field f = analysis.resolvedFields.get(inf);
                if (f != null && ValType.fromJavaReturn(f.getType()) == ValType.LONG) {
                    emitResolvedInstanceFieldRaw(inf, f);
                    coerceJavaReturnToStack(f.getType(), ValType.LONG);
                    return false;
                }
            }
            if (node instanceof DerefNode dn) {
                int root = scalarBoxRoot(dn.target);
                if (root != -1) {
                    emitScalarSlotAs(root, ValType.LONG);
                    return false;
                }
                ValType tt = typeOf(dn.target);
                if (tt.isRef() && tt.refClass == Atom.Volatile.class) {
                    emitRef(dn.target, Atom.Volatile.class);
                    invokevirtual("cljl/Atom$Volatile", "derefLong", "()J", 1, 2);
                    return false;
                }
                if (tt.isRef() && tt.refClass == Atom.class) {
                    emitRef(dn.target, Atom.class);
                    invokevirtual("cljl/Atom", "derefLong", "()J", 1, 2);
                    return false;
                }
            }
            if (node instanceof ResetBangNode rb) {
                int root = scalarAtomRoot(rb.target);
                if (root != -1) {
                    ValType st = layout.slotTypes[root];
                    if (st == ValType.LONG) {
                        if (emitLong(rb.val)) return true;
                        dup2();
                        storeLogicalSlot(root);
                        invalidateVolatileBounds(root);
                        return false;
                    }
                    if (emitSlotInit(root, rb.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.LONG);
                    return false;
                }
                emitRef(rb.target, Atom.class);
                if (emitLong(rb.val)) return true;
                invokevirtual("cljl/Atom", "resetLong", "(J)J", 3, 2);
                return false;
            }
            if (node instanceof SwapBangNode sb) {
                int root = scalarAtomRoot(sb.target);
                if (root != -1) {
                    return emitScalarAtomSwap(sb, root, ValType.LONG);
                }
                if (sb.extraArgs.length == 0) {
                    emitRef(sb.target, Atom.class);
                    if (sb.fn instanceof VarRefNode vr) emitVarFn(vr.var);
                    else emitRef(sb.fn, Fn.class);
                    invokevirtual("cljl/Atom", "swapLong", "(Lcljl/Fn;)J", 2, 2);
                    return false;
                }
                if (sb.extraArgs.length == 1 && typeOf(sb.extraArgs[0]) == ValType.LONG) {
                    emitRef(sb.target, Atom.class);
                    if (sb.fn instanceof VarRefNode vr) emitVarFn(vr.var);
                    else emitRef(sb.fn, Fn.class);
                    if (emitLong(sb.extraArgs[0])) return true;
                    invokevirtual("cljl/Atom", "swapLong", "(Lcljl/Fn;J)J", 4, 2);
                    return false;
                }
            }
            if (node instanceof VresetNode vr) {
                int root = scalarVolatileRoot(vr.target);
                if (root != -1) {
                    ValType st = layout.slotTypes[root];
                    if (st == ValType.LONG) {
                        if (emitLong(vr.val)) return true;
                        dup2();
                        storeLogicalSlot(root);
                        invalidateVolatileBounds(root);
                        return false;
                    }
                    if (emitSlotInit(root, vr.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.LONG);
                    return false;
                }
                int fused = tryEmitFusedVreset(vr, ValType.LONG);
                if (fused != 0) return fused == 2;
                emitRef(vr.target, Atom.Volatile.class);
                if (emitLong(vr.val)) return true;
                invokevirtual("cljl/Atom$Volatile", "resetLong", "(J)J", 3, 2);
                return false;
            }
            // Self-calls returning unboxed long
            if (tryEmitSelfCall(node, ValType.LONG)) {
                return false;
            }
            int primLongRes = tryEmitPrimCall(node, ValType.LONG);
            if (primLongRes != 0) return primLongRes == 2;

            if (emitBoxedImpl(node)) return true;
            invokestatic("cljl/Numbers", "longCast", "(Ljava/lang/Object;)J", 1, 2);
            return false;
        }

        // ====================================================================
        // Double Emission (2 stack words, returns true if terminated)
        // ====================================================================

        boolean emitDouble(Node node) {
            if (node instanceof ConstNode cn) {
                if (cn.isDouble) {
                    ldcDouble(cn.doubleVal);
                } else if (cn.isLong) {
                    ldcDouble((double) cn.longVal);
                } else if (cn.val instanceof Number num) {
                    ldcDouble(num.doubleValue());
                } else {
                    emitBoxedImpl(node);
                    invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
                }
                return false;
            }
            if (node instanceof LocalNode ln) {
                ValType st = layout.slotTypes[ln.slot];
                if (st == ValType.DOUBLE) {
                    loadLogicalSlot(ln.slot);
                } else if (st == ValType.LONG) {
                    loadLogicalSlot(ln.slot);
                    l2d();
                } else {
                    loadLogicalSlot(ln.slot);
                    invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
                }
                return false;
            }
            if (node instanceof VarRefNode) {
                emitBoxedImpl(node);
                invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
                return false;
            }
            if (node instanceof IfNode iff) return emitIfTyped(iff, ValType.DOUBLE);
            if (node instanceof DoNode dn) return emitDoTyped(dn, ValType.DOUBLE);
            if (node instanceof LetNode ln) return emitLetTyped(ln, ValType.DOUBLE);
            if (node instanceof LoopNode lp) return emitLoopTyped(lp, ValType.DOUBLE);
            if (node instanceof LockingNode ln) return emitLockingTyped(ln, ValType.DOUBLE);
            if (node instanceof TryNode tn) return emitTryTyped(tn, ValType.DOUBLE);
            if (node instanceof CaseNode cn) return emitCaseTyped(cn, ValType.DOUBLE);
            if (node instanceof Recur0Node || node instanceof Recur1Node
                    || node instanceof Recur2Node || node instanceof RecurNNode) {
                return emitRecur(node);
            }
            if (node instanceof ThrowNode tn) return emitThrow(tn);

            if (node instanceof DoubleCastNode dc) {
                ValType at = typeOf(dc.a);
                if (at == ValType.DOUBLE) return emitDouble(dc.a);
                if (at == ValType.LONG) {
                    if (emitLong(dc.a)) return true;
                    l2d();
                    return false;
                }
                int primRes = tryEmitPrimCall(dc.a, ValType.DOUBLE);
                if (primRes != 0) return primRes == 2;
                if (emitBoxed(dc.a)) return true;
                invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
                return false;
            }
            if (node instanceof Add2Node a && isDoubleOrLongPair(a.a, a.b)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                dadd();
                return false;
            }
            if (node instanceof Sub2Node a && isDoubleOrLongPair(a.a, a.b)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                dsub();
                return false;
            }
            if (node instanceof Mul2Node a && isDoubleOrLongPair(a.a, a.b)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                dmul();
                return false;
            }
            if (node instanceof Div2Node a && isDoubleOrLongPair(a.a, a.b)
                    && (typeOf(a.a) == ValType.DOUBLE || typeOf(a.b) == ValType.DOUBLE)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                boolean staticFloat = genericAnalysis == null
                        || genericAnalysis.typeOf(a.a) == ValType.DOUBLE
                        || genericAnalysis.typeOf(a.b) == ValType.DOUBLE;
                if (staticFloat) {
                    ddiv();
                } else {
                    invokestatic("cljl/Numbers", "divideChecked", "(DD)D", 4, 2);
                }
                return false;
            }
            if (node instanceof Rem2Node a && isDoubleOrLongPair(a.a, a.b)
                    && (typeOf(a.a) == ValType.DOUBLE || typeOf(a.b) == ValType.DOUBLE)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                drem();
                return false;
            }
            if (node instanceof UncheckedAdd2Node a && isDoubleOrLongPair(a.a, a.b)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                dadd();
                return false;
            }
            if (node instanceof UncheckedSub2Node a && isDoubleOrLongPair(a.a, a.b)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                dsub();
                return false;
            }
            if (node instanceof UncheckedMul2Node a && isDoubleOrLongPair(a.a, a.b)) {
                if (emitDouble(a.a)) return true;
                if (emitDouble(a.b)) return true;
                dmul();
                return false;
            }
            if (node instanceof IncNode a && typeOf(a.a) == ValType.DOUBLE) {
                if (emitDouble(a.a)) return true;
                ldcDouble(1.0);
                dadd();
                return false;
            }
            if (node instanceof UncheckedIncNode a && typeOf(a.a) == ValType.DOUBLE) {
                if (emitDouble(a.a)) return true;
                ldcDouble(1.0);
                dadd();
                return false;
            }
            if (node instanceof DecNode a && typeOf(a.a) == ValType.DOUBLE) {
                if (emitDouble(a.a)) return true;
                ldcDouble(1.0);
                dsub();
                return false;
            }
            if (node instanceof UncheckedDecNode a && typeOf(a.a) == ValType.DOUBLE) {
                if (emitDouble(a.a)) return true;
                ldcDouble(1.0);
                dsub();
                return false;
            }
            if (node instanceof NegateNode a && typeOf(a.a) == ValType.DOUBLE) {
                if (emitDouble(a.a)) return true;
                dneg();
                return false;
            }
            if (node instanceof UncheckedNegateNode a && typeOf(a.a) == ValType.DOUBLE) {
                if (emitDouble(a.a)) return true;
                dneg();
                return false;
            }
            if (node instanceof AgetNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls == double[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx); daload(); return false;
                }
                if (arrCls == float[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx); faload(); f2d(); return false;
                }
            }
            if (node instanceof AsetNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls == double[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (emitDouble(a.val)) return true;
                    dup2X2(); dastore(); return false;
                }
                if (arrCls == float[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (emitDouble(a.val)) return true;
                    d2f(); dupX2(); fastore(); f2d(); return false;
                }
            }
            if (node instanceof StaticMethodNode sm && analysis != null) {
                Method m = analysis.resolvedMethods.get(sm);
                if (m != null && ValType.fromJavaReturn(m.getReturnType()) == ValType.DOUBLE) {
                    emitResolvedStaticCallRaw(sm, m);
                    coerceJavaReturnToStack(m.getReturnType(), ValType.DOUBLE);
                    return false;
                }
            }
            if (node instanceof InstanceMethodNode im && analysis != null) {
                Method m = analysis.resolvedMethods.get(im);
                if (m != null && ValType.fromJavaReturn(m.getReturnType()) == ValType.DOUBLE) {
                    emitResolvedInstanceCallRaw(im, m);
                    coerceJavaReturnToStack(m.getReturnType(), ValType.DOUBLE);
                    return false;
                }
            }
            if (node instanceof StaticFieldNode sf && analysis != null) {
                Field f = analysis.resolvedFields.get(sf);
                if (f != null && ValType.fromJavaReturn(f.getType()) == ValType.DOUBLE) {
                    emitResolvedStaticFieldRaw(f);
                    coerceJavaReturnToStack(f.getType(), ValType.DOUBLE);
                    return false;
                }
            }
            if (node instanceof InstanceFieldNode inf && analysis != null) {
                Field f = analysis.resolvedFields.get(inf);
                if (f != null && ValType.fromJavaReturn(f.getType()) == ValType.DOUBLE) {
                    emitResolvedInstanceFieldRaw(inf, f);
                    coerceJavaReturnToStack(f.getType(), ValType.DOUBLE);
                    return false;
                }
            }
            if (node instanceof DerefNode dn) {
                int root = scalarBoxRoot(dn.target);
                if (root != -1) {
                    emitScalarSlotAs(root, ValType.DOUBLE);
                    return false;
                }
                ValType tt = typeOf(dn.target);
                if (tt.isRef() && tt.refClass == Atom.Volatile.class) {
                    emitRef(dn.target, Atom.Volatile.class);
                    invokevirtual("cljl/Atom$Volatile", "derefDouble", "()D", 1, 2);
                    return false;
                }
            }
            if (node instanceof ResetBangNode rb) {
                int root = scalarAtomRoot(rb.target);
                if (root != -1) {
                    ValType st = layout.slotTypes[root];
                    if (st == ValType.DOUBLE) {
                        if (emitDouble(rb.val)) return true;
                        dup2();
                        storeLogicalSlot(root);
                        invalidateVolatileBounds(root);
                        return false;
                    }
                    if (emitSlotInit(root, rb.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.DOUBLE);
                    return false;
                }
            }
            if (node instanceof SwapBangNode sb) {
                int root = scalarAtomRoot(sb.target);
                if (root != -1) {
                    return emitScalarAtomSwap(sb, root, ValType.DOUBLE);
                }
            }
            if (node instanceof VresetNode vr) {
                int root = scalarVolatileRoot(vr.target);
                if (root != -1) {
                    ValType st = layout.slotTypes[root];
                    if (st == ValType.DOUBLE) {
                        if (emitDouble(vr.val)) return true;
                        dup2();
                        storeLogicalSlot(root);
                        invalidateVolatileBounds(root);
                        return false;
                    }
                    if (emitSlotInit(root, vr.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.DOUBLE);
                    return false;
                }
                int fused = tryEmitFusedVreset(vr, ValType.DOUBLE);
                if (fused != 0) return fused == 2;
                emitRef(vr.target, Atom.Volatile.class);
                if (emitDouble(vr.val)) return true;
                invokevirtual("cljl/Atom$Volatile", "resetDouble", "(D)D", 3, 2);
                return false;
            }
            if (tryEmitSelfCall(node, ValType.DOUBLE)) {
                return false;
            }
            // If typeOf(node) == LONG, emitLong then L2D
            if (typeOf(node) == ValType.LONG) {
                if (emitLong(node)) return true;
                l2d();
                return false;
            }
            int primDblRes = tryEmitPrimCall(node, ValType.DOUBLE);
            if (primDblRes != 0) return primDblRes == 2;
            if (emitBoxedImpl(node)) return true;
            invokestatic("cljl/Numbers", "toDouble", "(Ljava/lang/Object;)D", 1, 2);
            return false;
        }

        /**
         * Emits a direct primitive-specialized virtual call on cljl.Fn (invokePrim_*) when the
         * call arguments and desired return type match one of Fn's primitive dispatch hooks.
         * Returns 0 = not applicable, 1 = emitted, 2 = terminated.
         */
        private int tryEmitPrimCall(Node node, ValType desiredRet) {
            if (desiredRet == ValType.DOUBLE) {
                if (node instanceof Call1Node c && typeOf(c.a) == ValType.DOUBLE) {
                    emitRef(c.fn, Fn.class);
                    if (emitDouble(c.a)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_D_D", "(D)D", 3, 2);
                    return 1;
                }
                if (node instanceof VarCall1Node c && typeOf(c.a) == ValType.DOUBLE) {
                    emitVarFn(c.v);
                    if (emitDouble(c.a)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_D_D", "(D)D", 3, 2);
                    return 1;
                }
                if (node instanceof Call2Node c && typeOf(c.a) == ValType.DOUBLE && typeOf(c.b) == ValType.DOUBLE) {
                    emitRef(c.fn, Fn.class);
                    if (emitDouble(c.a)) return 2;
                    if (emitDouble(c.b)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_DD_D", "(DD)D", 5, 2);
                    return 1;
                }
                if (node instanceof VarCall2Node c && typeOf(c.a) == ValType.DOUBLE && typeOf(c.b) == ValType.DOUBLE) {
                    emitVarFn(c.v);
                    if (emitDouble(c.a)) return 2;
                    if (emitDouble(c.b)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_DD_D", "(DD)D", 5, 2);
                    return 1;
                }
                return 0;
            }
            if (desiredRet == ValType.LONG) {
                if (node instanceof Call1Node c && typeOf(c.a) == ValType.LONG) {
                    emitRef(c.fn, Fn.class);
                    if (emitLong(c.a)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_J_J", "(J)J", 3, 2);
                    return 1;
                }
                if (node instanceof VarCall1Node c && typeOf(c.a) == ValType.LONG) {
                    emitVarFn(c.v);
                    if (emitLong(c.a)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_J_J", "(J)J", 3, 2);
                    return 1;
                }
                if (node instanceof Call2Node c && typeOf(c.b) == ValType.LONG) {
                    if (typeOf(c.a) == ValType.LONG) {
                        emitRef(c.fn, Fn.class);
                        if (emitLong(c.a)) return 2;
                        if (emitLong(c.b)) return 2;
                        invokevirtual("cljl/Fn", "invokePrim_JJ_J", "(JJ)J", 5, 2);
                        return 1;
                    }
                    emitRef(c.fn, Fn.class);
                    if (emitBoxed(c.a)) return 2;
                    if (emitLong(c.b)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_OJ_J", "(Ljava/lang/Object;J)J", 4, 2);
                    return 1;
                }
                if (node instanceof VarCall2Node c && typeOf(c.b) == ValType.LONG) {
                    if (typeOf(c.a) == ValType.LONG) {
                        emitVarFn(c.v);
                        if (emitLong(c.a)) return 2;
                        if (emitLong(c.b)) return 2;
                        invokevirtual("cljl/Fn", "invokePrim_JJ_J", "(JJ)J", 5, 2);
                        return 1;
                    }
                    emitVarFn(c.v);
                    if (emitBoxed(c.a)) return 2;
                    if (emitLong(c.b)) return 2;
                    invokevirtual("cljl/Fn", "invokePrim_OJ_J", "(Ljava/lang/Object;J)J", 4, 2);
                    return 1;
                }
            }
            return 0;
        }

        private boolean isDoubleOrLongPair(Node a, Node b) {
            ValType ta = typeOf(a);
            ValType tb = typeOf(b);
            return (ta == ValType.DOUBLE || ta == ValType.LONG) && (tb == ValType.DOUBLE || tb == ValType.LONG);
        }

        // ====================================================================
        // Boolean Emission & Direct Branching
        // ====================================================================

        void emitBranchIfFalse(Node test, Label falseLabel) {
            if (test instanceof ConstNode cn) {
                if (!cn.boolVal) gotoLabel(falseLabel);
                return;
            }
            if (test instanceof NotNode n) {
                emitBranchIfTrue(n.a, falseLabel);
                return;
            }
            if (test instanceof NilPNode np) {
                if (typeOf(np.a).isPrimitive()) {
                    // Primitives are never nil
                    emitDiscard(np.a);
                    gotoLabel(falseLabel);
                } else {
                    emitBoxed(np.a);
                    ifnonnull(falseLabel);
                }
                return;
            }
            if (test instanceof IdenticalNode id) {
                if (!typeOf(id.a).isPrimitive() && !typeOf(id.b).isPrimitive()) {
                    emitBoxed(id.a);
                    emitBoxed(id.b);
                    ifAcmpNe(falseLabel);
                    return;
                }
            }
            if (test instanceof Lt2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if (ta == ValType.LONG && tb == ValType.LONG) {
                    emitLong(a.a); emitLong(a.b); lcmp(); ifge(falseLabel); return;
                }
                if (isDoubleOrLongPair(a.a, a.b)) {
                    emitDouble(a.a); emitDouble(a.b); dcmpg(); ifge(falseLabel); return;
                }
            }
            if (test instanceof Lte2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if (ta == ValType.LONG && tb == ValType.LONG) {
                    emitLong(a.a); emitLong(a.b); lcmp(); ifgt(falseLabel); return;
                }
                if (isDoubleOrLongPair(a.a, a.b)) {
                    emitDouble(a.a); emitDouble(a.b); dcmpg(); ifgt(falseLabel); return;
                }
            }
            if (test instanceof Gt2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if (ta == ValType.LONG && tb == ValType.LONG) {
                    emitLong(a.a); emitLong(a.b); lcmp(); ifle(falseLabel); return;
                }
                if (isDoubleOrLongPair(a.a, a.b)) {
                    emitDouble(a.a); emitDouble(a.b); dcmpl(); ifle(falseLabel); return;
                }
            }
            if (test instanceof Gte2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if (ta == ValType.LONG && tb == ValType.LONG) {
                    emitLong(a.a); emitLong(a.b); lcmp(); iflt(falseLabel); return;
                }
                if (isDoubleOrLongPair(a.a, a.b)) {
                    emitDouble(a.a); emitDouble(a.b); dcmpl(); iflt(falseLabel); return;
                }
            }
            if (test instanceof Equiv2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if (ta == ValType.LONG && tb == ValType.LONG) {
                    emitLong(a.a); emitLong(a.b); lcmp(); ifne(falseLabel); return;
                }
                if (ta == ValType.BOOL && tb == ValType.BOOL) {
                    emitBoolean(a.a); emitBoolean(a.b); ifIcmpNe(falseLabel); return;
                }
            }
            if (test instanceof NumEq2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if (ta == ValType.LONG && tb == ValType.LONG) {
                    emitLong(a.a); emitLong(a.b); lcmp(); ifne(falseLabel); return;
                }
                if (isDoubleOrLongPair(a.a, a.b)) {
                    emitDouble(a.a); emitDouble(a.b); dcmpl(); ifne(falseLabel); return;
                }
            }
            if (test instanceof ZeroPNode z) {
                if (z.a instanceof Mod2Node m && typeOf(m.a) == ValType.LONG && typeOf(m.b) == ValType.LONG) {
                    // For all longs a, b != 0: floorMod(a, b) == 0 iff (a % b) == 0
                    emitLong(m.a); emitLong(m.b); lrem(); ldcLong(0L); lcmp(); ifne(falseLabel); return;
                }
                ValType ta = typeOf(z.a);
                if (ta == ValType.LONG) {
                    emitLong(z.a); ldcLong(0L); lcmp(); ifne(falseLabel); return;
                }
                if (ta == ValType.DOUBLE) {
                    emitDouble(z.a); ldcDouble(0.0); dcmpl(); ifne(falseLabel); return;
                }
            }
            if (test instanceof PosPNode p) {
                ValType ta = typeOf(p.a);
                if (ta == ValType.LONG) {
                    emitLong(p.a); ldcLong(0L); lcmp(); ifle(falseLabel); return;
                }
                if (ta == ValType.DOUBLE) {
                    emitDouble(p.a); ldcDouble(0.0); dcmpl(); ifle(falseLabel); return;
                }
            }
            if (test instanceof NegPNode ng) {
                ValType ta = typeOf(ng.a);
                if (ta == ValType.LONG) {
                    emitLong(ng.a); ldcLong(0L); lcmp(); ifge(falseLabel); return;
                }
                if (ta == ValType.DOUBLE) {
                    emitDouble(ng.a); ldcDouble(0.0); dcmpg(); ifge(falseLabel); return;
                }
            }
            if (test instanceof EvenPNode ev && typeOf(ev.a) == ValType.LONG) {
                emitLong(ev.a); ldcLong(1L); land(); ldcLong(0L); lcmp(); ifne(falseLabel); return;
            }
            if (test instanceof OddPNode od && typeOf(od.a) == ValType.LONG) {
                emitLong(od.a); ldcLong(1L); land(); ldcLong(0L); lcmp(); ifeq(falseLabel); return;
            }

            emitBoolean(test);
            ifeq(falseLabel);
        }

        void emitBranchIfTrue(Node test, Label trueLabel) {
            if (test instanceof NilPNode np && !typeOf(np.a).isPrimitive()) {
                emitBoxed(np.a);
                ifnull(trueLabel);
                return;
            }
            if (test instanceof IdenticalNode id && !typeOf(id.a).isPrimitive() && !typeOf(id.b).isPrimitive()) {
                emitBoxed(id.a);
                emitBoxed(id.b);
                ifAcmpEq(trueLabel);
                return;
            }
            if (test instanceof ZeroPNode z) {
                if (z.a instanceof Mod2Node m && typeOf(m.a) == ValType.LONG && typeOf(m.b) == ValType.LONG) {
                    emitLong(m.a); emitLong(m.b); lrem(); ldcLong(0L); lcmp(); ifeq(trueLabel); return;
                }
                ValType ta = typeOf(z.a);
                if (ta == ValType.LONG) {
                    emitLong(z.a); ldcLong(0L); lcmp(); ifeq(trueLabel); return;
                }
                if (ta == ValType.DOUBLE) {
                    emitDouble(z.a); ldcDouble(0.0); dcmpl(); ifeq(trueLabel); return;
                }
            }
            emitBoolean(test);
            ifne(trueLabel);
        }

        boolean emitBoolean(Node node) {
            if (node instanceof ConstNode cn) {
                iconst(cn.boolVal ? 1 : 0);
                return false;
            }
            if (node instanceof LocalNode ln) {
                ValType st = layout.slotTypes[ln.slot];
                if (st == ValType.BOOL) {
                    loadLogicalSlot(ln.slot);
                    return false;
                }
                if (st == ValType.LONG || st == ValType.DOUBLE) {
                    // Long and Double are always truthy in Clojure
                    loadLogicalSlot(ln.slot);
                    pop2();
                    iconst(1);
                    return false;
                }
                loadLogicalSlot(ln.slot);
                invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z", 1, 1);
                return false;
            }
            if (node instanceof IfNode iff) return emitIfTyped(iff, ValType.BOOL);
            if (node instanceof DoNode dn) return emitDoTyped(dn, ValType.BOOL);
            if (node instanceof LetNode ln) return emitLetTyped(ln, ValType.BOOL);
            if (node instanceof LoopNode lp) return emitLoopTyped(lp, ValType.BOOL);
            if (node instanceof LockingNode ln) return emitLockingTyped(ln, ValType.BOOL);
            if (node instanceof TryNode tn) return emitTryTyped(tn, ValType.BOOL);
            if (node instanceof CaseNode cn) return emitCaseTyped(cn, ValType.BOOL);
            if (node instanceof Recur0Node || node instanceof Recur1Node
                    || node instanceof Recur2Node || node instanceof RecurNNode) {
                return emitRecur(node);
            }
            if (node instanceof ThrowNode tn) return emitThrow(tn);

            if (node instanceof NotNode a) {
                if (emitBoolean(a.a)) return true;
                iconst(1);
                ixor();
                return false;
            }
            if (node instanceof Lt2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if ((ta == ValType.LONG && tb == ValType.LONG) || isDoubleOrLongPair(a.a, a.b)) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitCompareViaNumbers(a.a, a.b, "lt");
                return false;
            }
            if (node instanceof Lte2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if ((ta == ValType.LONG && tb == ValType.LONG) || isDoubleOrLongPair(a.a, a.b)) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitCompareViaNumbers(a.a, a.b, "lte");
                return false;
            }
            if (node instanceof Gt2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if ((ta == ValType.LONG && tb == ValType.LONG) || isDoubleOrLongPair(a.a, a.b)) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitCompareViaNumbers(a.a, a.b, "gt");
                return false;
            }
            if (node instanceof Gte2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if ((ta == ValType.LONG && tb == ValType.LONG) || isDoubleOrLongPair(a.a, a.b)) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitCompareViaNumbers(a.a, a.b, "gte");
                return false;
            }
            if (node instanceof Equiv2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if ((ta == ValType.LONG && tb == ValType.LONG) || (ta == ValType.BOOL && tb == ValType.BOOL)) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/RT", "equiv", "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
                return false;
            }
            if (node instanceof NumEq2Node a) {
                ValType ta = typeOf(a.a), tb = typeOf(a.b);
                if ((ta == ValType.LONG && tb == ValType.LONG) || isDoubleOrLongPair(a.a, a.b)) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitCompareViaNumbers(a.a, a.b, "equal");
                return false;
            }
            if (node instanceof ZeroPNode a) {
                ValType ta = typeOf(a.a);
                if (ta == ValType.LONG || ta == ValType.DOUBLE) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "isZero", "(Ljava/lang/Object;)Z", 1, 1);
                return false;
            }
            if (node instanceof PosPNode a) {
                ValType ta = typeOf(a.a);
                if (ta == ValType.LONG || ta == ValType.DOUBLE) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "isPos", "(Ljava/lang/Object;)Z", 1, 1);
                return false;
            }
            if (node instanceof NegPNode a) {
                ValType ta = typeOf(a.a);
                if (ta == ValType.LONG || ta == ValType.DOUBLE) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "isNeg", "(Ljava/lang/Object;)Z", 1, 1);
                return false;
            }
            if (node instanceof NilPNode a) {
                if (typeOf(a.a).isPrimitive()) {
                    emitDiscard(a.a);
                    iconst(0);
                    return false;
                }
                emitBooleanFromBranch(node);
                return false;
            }
            if (node instanceof IdenticalNode a) {
                if (!typeOf(a.a).isPrimitive() && !typeOf(a.b).isPrimitive()) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                emitBoxed(a.b);
                Label neL = new Label();
                Label endL = new Label();
                ifAcmpNe(neL);
                iconst(1);
                gotoLabel(endL);
                curStack--;
                markLabel(neL);
                iconst(0);
                markLabel(endL);
                return false;
            }
            if (node instanceof EvenPNode a) {
                if (typeOf(a.a) == ValType.LONG) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "isEven", "(Ljava/lang/Object;)Z", 1, 1);
                return false;
            }
            if (node instanceof OddPNode a) {
                if (typeOf(a.a) == ValType.LONG) {
                    emitBooleanFromBranch(node);
                    return false;
                }
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "isOdd", "(Ljava/lang/Object;)Z", 1, 1);
                return false;
            }
            if (node instanceof TypePredNode tp) {
                emitBoxed(tp.a);
                switch (tp.pred) {
                    case STRING -> emitInstanceof("java/lang/String");
                    case VECTOR -> invokestatic("cljl/RT", "isVector", "(Ljava/lang/Object;)Z", 1, 1);
                    case MAP -> invokestatic("cljl/BytecodeJIT", "isMap", "(Ljava/lang/Object;)Z", 1, 1);
                    case SET -> invokestatic("cljl/BytecodeJIT", "isSet", "(Ljava/lang/Object;)Z", 1, 1);
                    case SEQ -> emitInstanceof("cljl/lang/ISeq");
                    case NUMBER -> emitInstanceof("java/lang/Number");
                    case INT -> invokestatic("cljl/Numbers", "isInteger", "(Ljava/lang/Object;)Z", 1, 1);
                    case KEYWORD -> emitInstanceof("cljl/Keyword");
                    case SYMBOL -> emitInstanceof("cljl/Symbol");
                }
                return false;
            }
            if (node instanceof AgetNode a && arrayClassOf(a.arr) == boolean[].class) {
                emitArrayAndIndex(a.arr, boolean[].class, a.idx);
                baload();
                return false;
            }
            if (node instanceof AsetNode a && arrayClassOf(a.arr) == boolean[].class) {
                emitArrayAndIndex(a.arr, boolean[].class, a.idx);
                if (emitBoolean(a.val)) return true;
                dupX2();
                bastore();
                return false;
            }
            if (node instanceof StaticMethodNode sm && analysis != null) {
                Method m = analysis.resolvedMethods.get(sm);
                if (m != null && m.getReturnType() == boolean.class) {
                    emitResolvedStaticCallRaw(sm, m);
                    return false;
                }
            }
            if (node instanceof InstanceMethodNode im && analysis != null) {
                Method m = analysis.resolvedMethods.get(im);
                if (m != null && m.getReturnType() == boolean.class) {
                    emitResolvedInstanceCallRaw(im, m);
                    return false;
                }
            }
            if (node instanceof StaticFieldNode sf && analysis != null) {
                Field f = analysis.resolvedFields.get(sf);
                if (f != null && f.getType() == boolean.class) {
                    emitResolvedStaticFieldRaw(f);
                    return false;
                }
            }
            if (node instanceof InstanceFieldNode inf && analysis != null) {
                Field f = analysis.resolvedFields.get(inf);
                if (f != null && f.getType() == boolean.class) {
                    emitResolvedInstanceFieldRaw(inf, f);
                    return false;
                }
            }
            if (node instanceof DerefNode dn) {
                int root = scalarBoxRoot(dn.target);
                if (root != -1) {
                    emitScalarSlotAs(root, ValType.BOOL);
                    return false;
                }
            }
            if (node instanceof ResetBangNode rb) {
                int root = scalarAtomRoot(rb.target);
                if (root != -1) {
                    if (emitSlotInit(root, rb.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.BOOL);
                    return false;
                }
            }
            if (node instanceof SwapBangNode sb) {
                int root = scalarAtomRoot(sb.target);
                if (root != -1) {
                    return emitScalarAtomSwap(sb, root, ValType.BOOL);
                }
            }
            if (node instanceof VresetNode vr) {
                int root = scalarVolatileRoot(vr.target);
                if (root != -1) {
                    if (emitSlotInit(root, vr.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.BOOL);
                    return false;
                }
            }
            if (tryEmitSelfCall(node, ValType.BOOL)) {
                return false;
            }

            if (emitBoxedImpl(node)) return true;
            invokestatic("cljl/RT", "truthy", "(Ljava/lang/Object;)Z", 1, 1);
            return false;
        }

        private void emitBooleanFromBranch(Node cond) {
            Label falseL = new Label();
            Label endL = new Label();
            emitBranchIfFalse(cond, falseL);
            iconst(1);
            gotoLabel(endL);
            curStack--;
            markLabel(falseL);
            iconst(0);
            markLabel(endL);
        }

        private void emitCompareViaNumbers(Node a, Node b, String method) {
            if (typeOf(b) == ValType.LONG || (b instanceof ConstNode cn && cn.isLong)) {
                emitBoxed(a);
                emitLong(b);
                invokestatic("cljl/Numbers", method, "(Ljava/lang/Object;J)Z", 3, 1);
            } else if (typeOf(a) == ValType.LONG || (a instanceof ConstNode cn && cn.isLong)) {
                emitLong(a);
                emitBoxed(b);
                invokestatic("cljl/Numbers", method, "(JLjava/lang/Object;)Z", 3, 1);
            } else {
                emitBoxed(a);
                emitBoxed(b);
                invokestatic("cljl/Numbers", method, "(Ljava/lang/Object;Ljava/lang/Object;)Z", 2, 1);
            }
        }

        // ====================================================================
        // Control-Flow Shared Typed Emitters (If, Do, Let, Loop, Locking, Case)
        // ====================================================================

        private boolean emitTyped(Node n, ValType targetT) {
            if (targetT == ValType.LONG) return emitLong(n);
            if (targetT == ValType.DOUBLE) return emitDouble(n);
            if (targetT == ValType.BOOL) return emitBoolean(n);
            return emitBoxed(n);
        }

        private boolean isImmutableLongSlot(int slot) {
            return layout != null && slot >= 0 && slot < layout.slotTypes.length
                    && layout.slotTypes[slot] == ValType.LONG
                    && (analysis == null || !analysis.isScalarBoxSlot(slot));
        }

        private boolean emitIfTyped(IfNode iff, ValType targetT) {
            int valWords = (targetT == ValType.LONG || targetT == ValType.DOUBLE) ? 2 : 1;
            Label elseLabel = new Label();
            Label endLabel = new Label();
            emitBranchIfFalse(iff.test, elseLabel);
            int stackAtIf = curStack;

            int thenBelowMax = -1, thenAboveMin = -1;
            int elseBelowMax = -1, elseAboveMin = -1;
            int thenNonNeg = -1, elseNonNeg = -1;
            int thenBelowMaxVol = -1, thenAboveMinVol = -1;
            int elseBelowMaxVol = -1, elseAboveMinVol = -1;
            if (iff.test instanceof Lt2Node lt && typeOf(lt.a) == ValType.LONG && typeOf(lt.b) == ValType.LONG) {
                if (lt.a instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    thenBelowMax = ln.slot;
                    if (lt.b instanceof ConstNode cn && cn.isLong && cn.longVal >= 0L) elseNonNeg = ln.slot;
                } else thenBelowMaxVol = scalarLongVolatileSlot(lt.a);
                if (lt.b instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    thenAboveMin = ln.slot;
                    if (lt.a instanceof ConstNode cn && cn.isLong && cn.longVal >= -1L) thenNonNeg = ln.slot;
                } else thenAboveMinVol = scalarLongVolatileSlot(lt.b);
            } else if (iff.test instanceof Gt2Node gt && typeOf(gt.a) == ValType.LONG && typeOf(gt.b) == ValType.LONG) {
                if (gt.a instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    thenAboveMin = ln.slot;
                    if (gt.b instanceof ConstNode cn && cn.isLong && cn.longVal >= -1L) thenNonNeg = ln.slot;
                } else thenAboveMinVol = scalarLongVolatileSlot(gt.a);
                if (gt.b instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    thenBelowMax = ln.slot;
                    if (gt.a instanceof ConstNode cn && cn.isLong && cn.longVal >= 0L) elseNonNeg = ln.slot;
                } else thenBelowMaxVol = scalarLongVolatileSlot(gt.b);
            } else if (iff.test instanceof Gte2Node ge && typeOf(ge.a) == ValType.LONG && typeOf(ge.b) == ValType.LONG) {
                if (ge.a instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    elseBelowMax = ln.slot;
                    if (ge.b instanceof ConstNode cn && cn.isLong && cn.longVal >= 0L) thenNonNeg = ln.slot;
                } else elseBelowMaxVol = scalarLongVolatileSlot(ge.a);
                if (ge.b instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    elseAboveMin = ln.slot;
                    if (ge.a instanceof ConstNode cn && cn.isLong && cn.longVal >= -1L) elseNonNeg = ln.slot;
                } else elseAboveMinVol = scalarLongVolatileSlot(ge.b);
            } else if (iff.test instanceof Lte2Node le && typeOf(le.a) == ValType.LONG && typeOf(le.b) == ValType.LONG) {
                if (le.a instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    elseAboveMin = ln.slot;
                    if (le.b instanceof ConstNode cn && cn.isLong && cn.longVal >= -1L) elseNonNeg = ln.slot;
                } else elseAboveMinVol = scalarLongVolatileSlot(le.a);
                if (le.b instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                    elseBelowMax = ln.slot;
                    if (le.a instanceof ConstNode cn && cn.isLong && cn.longVal >= 0L) thenNonNeg = ln.slot;
                } else elseBelowMaxVol = scalarLongVolatileSlot(le.b);
            } else if (iff.test instanceof PosPNode p && p.a instanceof LocalNode ln && isImmutableLongSlot(ln.slot)) {
                thenNonNeg = ln.slot;
            }

            Set<Integer> outerVolBelow = new HashSet<>(boundedBelowMaxVolatiles);
            Set<Integer> outerVolAbove = new HashSet<>(boundedAboveMinVolatiles);

            boolean addedThenBelow = thenBelowMax != -1 && boundedBelowMaxLong.add(thenBelowMax);
            boolean addedThenAbove = thenAboveMin != -1 && boundedAboveMinLong.add(thenAboveMin);
            boolean addedThenNonNeg = thenNonNeg != -1 && nonNegativeLong.add(thenNonNeg);
            if (thenBelowMaxVol != -1) boundedBelowMaxVolatiles.add(thenBelowMaxVol);
            if (thenAboveMinVol != -1) boundedAboveMinVolatiles.add(thenAboveMinVol);
            boolean thenTerm = emitTyped(iff.thenNode, targetT);
            if (addedThenBelow) boundedBelowMaxLong.remove(thenBelowMax);
            if (addedThenAbove) boundedAboveMinLong.remove(thenAboveMin);
            if (addedThenNonNeg) nonNegativeLong.remove(thenNonNeg);
            Set<Integer> afterThenVolBelow = new HashSet<>(boundedBelowMaxVolatiles);
            Set<Integer> afterThenVolAbove = new HashSet<>(boundedAboveMinVolatiles);
            if (!thenTerm) {
                gotoLabel(endLabel);
            }
            curStack = stackAtIf;
            markLabel(elseLabel);
            boundedBelowMaxVolatiles.clear();
            boundedBelowMaxVolatiles.addAll(outerVolBelow);
            boundedAboveMinVolatiles.clear();
            boundedAboveMinVolatiles.addAll(outerVolAbove);
            boolean addedElseBelow = elseBelowMax != -1 && boundedBelowMaxLong.add(elseBelowMax);
            boolean addedElseAbove = elseAboveMin != -1 && boundedAboveMinLong.add(elseAboveMin);
            boolean addedElseNonNeg = elseNonNeg != -1 && nonNegativeLong.add(elseNonNeg);
            if (elseBelowMaxVol != -1) boundedBelowMaxVolatiles.add(elseBelowMaxVol);
            if (elseAboveMinVol != -1) boundedAboveMinVolatiles.add(elseAboveMinVol);
            boolean elseTerm = emitTyped(iff.elseNode, targetT);
            if (addedElseBelow) boundedBelowMaxLong.remove(elseBelowMax);
            if (addedElseAbove) boundedAboveMinLong.remove(elseAboveMin);
            if (addedElseNonNeg) nonNegativeLong.remove(elseNonNeg);
            Set<Integer> afterElseVolBelow = new HashSet<>(boundedBelowMaxVolatiles);
            Set<Integer> afterElseVolAbove = new HashSet<>(boundedAboveMinVolatiles);
            boundedBelowMaxVolatiles.clear();
            boundedBelowMaxVolatiles.addAll(outerVolBelow);
            boundedAboveMinVolatiles.clear();
            boundedAboveMinVolatiles.addAll(outerVolAbove);
            if (!thenTerm) {
                boundedBelowMaxVolatiles.retainAll(afterThenVolBelow);
                boundedAboveMinVolatiles.retainAll(afterThenVolAbove);
            }
            if (!elseTerm) {
                boundedBelowMaxVolatiles.retainAll(afterElseVolBelow);
                boundedAboveMinVolatiles.retainAll(afterElseVolAbove);
            }
            markLabel(endLabel);
            if (!thenTerm || !elseTerm) {
                curStack = stackAtIf + valWords;
            } else {
                curStack = stackAtIf;
            }
            return thenTerm && elseTerm;
        }

        private boolean emitDoTyped(DoNode dn, ValType targetT) {
            if (dn.nodes.length == 0) {
                if (targetT == ValType.BOOL) {
                    iconst(0);
                    return false;
                }
                aconstNull();
                if (targetT != ValType.ANY && targetT.isPrimitive()) unboxFromObject(targetT);
                return false;
            }
            int last = dn.nodes.length - 1;
            for (int i = 0; i < last; i++) {
                if (emitDiscard(dn.nodes[i])) return true;
            }
            return emitTyped(dn.nodes[last], targetT);
        }

        private boolean emitLetTyped(LetNode ln, ValType targetT) {
            for (int i = 0; i < ln.slots.length; i++) {
                if (emitLetSlotBinding(ln.slots[i], ln.inits[i])) return true;
            }
            return emitTyped(ln.body, targetT);
        }

        private boolean emitLoopTyped(LoopNode lp, ValType targetT) {
            for (int i = 0; i < lp.slots.length; i++) {
                if (emitLetSlotBinding(lp.slots[i], lp.inits[i])) return true;
            }
            boundedBelowMaxVolatiles.clear();
            boundedAboveMinVolatiles.clear();
            Label loopStart = new Label();
            markLabel(loopStart);
            pushLoopTarget(lp.slots, loopStart);
            boolean term = emitTyped(lp.body, targetT);
            popLoopTarget();
            boundedBelowMaxVolatiles.clear();
            boundedAboveMinVolatiles.clear();
            return term;
        }

        private boolean emitLockingTyped(LockingNode ln, ValType targetT) {
            int valWords = (targetT == ValType.LONG || targetT == ValType.DOUBLE) ? 2 : 1;
            int lockLocal = allocTempLocal();
            int exLocal = allocTempLocal();
            emitBoxed(ln.target);
            invokestatic("cljl/BytecodeJIT", "requireNonNullLock", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
            dup();
            astore(lockLocal);
            monitorenter();

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchStart = new Label();
            Label done = new Label();

            markLabel(tryStart);
            boolean bodyTerm = emitTyped(ln.body, targetT);
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
                curStack = valWords;
                markLabel(done);
                return false;
            } else {
                curStack = 0;
                return true;
            }
        }

        private boolean emitTryTyped(TryNode tn, ValType targetT) {
            int stackAtEntry = curStack;
            int valWords = (targetT == ValType.LONG || targetT == ValType.DOUBLE) ? 2 : 1;

            if (tn.finallyNode == null) {
                Label tryStart = new Label();
                Label tryEnd = new Label();
                Label done = new Label();

                markLabel(tryStart);
                boolean bodyTerm = emitTyped(tn.body, targetT);
                markLabel(tryEnd);

                boolean anyNonTerm = !bodyTerm;
                if (!bodyTerm && tn.catches.length > 0) {
                    gotoLabel(done);
                }

                for (CatchClause cc : tn.catches) {
                    Label catchStart = new Label();
                    curStack = stackAtEntry + 1;
                    if (curStack > maxStack) maxStack = curStack;
                    markLabel(catchStart);
                    astore(layout.jvmSlot[cc.slot()]);
                    boolean hTerm = emitTyped(cc.handler(), targetT);
                    if (!hTerm) {
                        anyNonTerm = true;
                        gotoLabel(done);
                    }
                    if (tryEnd.pos > tryStart.pos) {
                        int exTypeIdx = ce.cp.addClass(cc.exClass().getName().replace('.', '/'));
                        exceptionTable.add(new ExceptionEntry(tryStart, tryEnd, catchStart, exTypeIdx));
                    }
                }

                if (anyNonTerm) {
                    curStack = stackAtEntry + valWords;
                    markLabel(done);
                    return false;
                } else {
                    curStack = stackAtEntry;
                    return true;
                }
            }

            int savedTemp = curTempLocals;
            int resLocal = allocTempLocal(valWords);
            int exLocal = allocTempLocal(1);

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label catchAllStart = new Label();
            Label done = new Label();
            boolean anyNonTerm = false;

            markLabel(tryStart);
            boolean bodyTerm = emitTyped(tn.body, targetT);
            markLabel(tryEnd);
            if (!bodyTerm) {
                storeJvmLocal(resLocal, targetT);
                boolean fTerm = emitDiscard(tn.finallyNode);
                if (!fTerm) {
                    loadJvmLocal(resLocal, targetT);
                    anyNonTerm = true;
                    gotoLabel(done);
                }
            }

            List<ExceptionEntry> pendingCatchAll = new ArrayList<>();
            if (tryEnd.pos > tryStart.pos) {
                pendingCatchAll.add(new ExceptionEntry(tryStart, tryEnd, catchAllStart, 0));
            }

            for (CatchClause cc : tn.catches) {
                Label catchStart = new Label();
                Label catchEnd = new Label();
                curStack = stackAtEntry + 1;
                if (curStack > maxStack) maxStack = curStack;
                markLabel(catchStart);
                astore(layout.jvmSlot[cc.slot()]);
                boolean hTerm = emitTyped(cc.handler(), targetT);
                markLabel(catchEnd);
                if (!hTerm) {
                    storeJvmLocal(resLocal, targetT);
                    boolean fTerm = emitDiscard(tn.finallyNode);
                    if (!fTerm) {
                        loadJvmLocal(resLocal, targetT);
                        anyNonTerm = true;
                        gotoLabel(done);
                    }
                }
                if (tryEnd.pos > tryStart.pos) {
                    int exTypeIdx = ce.cp.addClass(cc.exClass().getName().replace('.', '/'));
                    exceptionTable.add(new ExceptionEntry(tryStart, tryEnd, catchStart, exTypeIdx));
                }
                if (catchEnd.pos > catchStart.pos) {
                    pendingCatchAll.add(new ExceptionEntry(catchStart, catchEnd, catchAllStart, 0));
                }
            }

            if (!pendingCatchAll.isEmpty()) {
                exceptionTable.addAll(pendingCatchAll);
                curStack = stackAtEntry + 1;
                if (curStack > maxStack) maxStack = curStack;
                markLabel(catchAllStart);
                astore(exLocal);
                boolean fTerm = emitDiscard(tn.finallyNode);
                if (!fTerm) {
                    aload(exLocal);
                    athrow();
                }
            }

            curTempLocals = savedTemp;
            if (anyNonTerm) {
                curStack = stackAtEntry + valWords;
                markLabel(done);
                return false;
            } else {
                curStack = stackAtEntry;
                return true;
            }
        }

        private boolean emitCaseTyped(CaseNode cn, ValType targetT) {
            int valWords = (targetT == ValType.LONG || targetT == ValType.DOUBLE) ? 2 : 1;
            List<Node> branches = new ArrayList<>();
            IdentityHashMap<Node, Integer> nodeToIdx = new IdentityHashMap<>();
            Map<RT.EquivKey, Integer> keyToIdx = new HashMap<>(cn.table.size() * 2 + 1);
            boolean allLongs = true;
            boolean allInterned = true;
            boolean anyNumber = false;
            long minKey = Long.MAX_VALUE;
            long maxKey = Long.MIN_VALUE;

            for (Map.Entry<RT.EquivKey, Node> e : cn.table.entrySet()) {
                Integer idx = nodeToIdx.get(e.getValue());
                if (idx == null) {
                    idx = branches.size();
                    nodeToIdx.put(e.getValue(), idx);
                    branches.add(e.getValue());
                }
                RT.EquivKey ek = e.getKey();
                keyToIdx.put(ek, idx);
                Object kv = ek.val;
                if (kv instanceof Long || kv instanceof Integer || kv instanceof Short || kv instanceof Byte) {
                    long lv = ((Number) kv).longValue();
                    if (lv < Integer.MIN_VALUE || lv > Integer.MAX_VALUE) allLongs = false;
                    if (lv < minKey) minKey = lv;
                    if (lv > maxKey) maxKey = lv;
                } else {
                    allLongs = false;
                }
                if (!(kv instanceof Keyword || kv instanceof Symbol)) {
                    allInterned = false;
                }
                if (kv instanceof Number) {
                    anyNumber = true;
                }
            }

            if (branches.isEmpty()) {
                emitBoxed(cn.expr);
                if (cn.hasDefault) {
                    pop();
                    return emitTyped(cn.defaultNode, targetT);
                }
                invokestatic("cljl/BytecodeJIT", "throwNoMatchingClause", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", 1, 1);
                athrow();
                return true;
            }

            int stackAtEntry = curStack;
            int n = branches.size();
            Label[] branchLabels = new Label[n];
            for (int i = 0; i < n; i++) branchLabels[i] = new Label();
            Label defaultLabel = new Label();
            Label endLabel = new Label();

            long span = allLongs ? (maxKey - minKey) : Long.MAX_VALUE;
            if (allLongs && typeOf(cn.expr) == ValType.LONG
                    && span >= 0 && span <= 256 && span <= 4L * cn.table.size() + 8) {
                int range = (int) span + 1;
                Label[] denseTargets = new Label[range];
                Arrays.fill(denseTargets, defaultLabel);
                for (Map.Entry<RT.EquivKey, Integer> e : keyToIdx.entrySet()) {
                    long lv = ((Number) e.getKey().val).longValue();
                    denseTargets[(int) (lv - minKey)] = branchLabels[e.getValue()];
                }
                int savedTemp = curTempLocals;
                int valLocal = allocTempLocal(2);
                emitLong(cn.expr);
                lstore(valLocal);
                lload(valLocal);
                ldcLong(minKey);
                lcmp();
                iflt(defaultLabel);
                lload(valLocal);
                ldcLong(maxKey);
                lcmp();
                ifgt(defaultLabel);
                lload(valLocal);
                ldcLong(minKey);
                lsub();
                l2i();
                curTempLocals = savedTemp;
                tableswitch(0, range - 1, defaultLabel, denseTargets);
            } else {
                emitBoxed(cn.expr);
                if (allInterned) {
                    IdentityHashMap<Object, Integer> idMap = new IdentityHashMap<>(keyToIdx.size() * 2);
                    for (Map.Entry<RT.EquivKey, Integer> e : keyToIdx.entrySet()) {
                        idMap.put(e.getKey().val, e.getValue());
                    }
                    loadConstObj(idMap);
                    checkcast("java/util/IdentityHashMap");
                    invokestatic("cljl/BytecodeJIT",
                            cn.hasDefault ? "caseIdentityLookup" : "caseIdentityLookupOrThrow",
                            "(Ljava/lang/Object;Ljava/util/IdentityHashMap;)I", 2, 1);
                } else if (!anyNumber) {
                    HashMap<Object, Integer> directMap = new HashMap<>(keyToIdx.size() * 2);
                    for (Map.Entry<RT.EquivKey, Integer> e : keyToIdx.entrySet()) {
                        directMap.put(e.getKey().val, e.getValue());
                    }
                    loadConstObj(directMap);
                    checkcast("java/util/Map");
                    invokestatic("cljl/BytecodeJIT",
                            cn.hasDefault ? "caseDirectLookup" : "caseDirectLookupOrThrow",
                            "(Ljava/lang/Object;Ljava/util/Map;)I", 2, 1);
                } else {
                    loadConstObj(keyToIdx);
                    checkcast("java/util/Map");
                    invokestatic("cljl/BytecodeJIT",
                            cn.hasDefault ? "caseLookup" : "caseLookupOrThrow",
                            "(Ljava/lang/Object;Ljava/util/Map;)I", 2, 1);
                }
                tableswitch(0, n - 1, defaultLabel, branchLabels);
            }

            boolean anyNonTerm = false;
            for (int i = 0; i < n; i++) {
                curStack = stackAtEntry;
                markLabel(branchLabels[i]);
                boolean bTerm = emitTyped(branches.get(i), targetT);
                if (!bTerm) {
                    anyNonTerm = true;
                    gotoLabel(endLabel);
                }
            }

            curStack = stackAtEntry;
            markLabel(defaultLabel);
            if (cn.hasDefault) {
                boolean dTerm = emitTyped(cn.defaultNode, targetT);
                if (!dTerm) {
                    anyNonTerm = true;
                }
            } else {
                aconstNull();
                invokestatic("cljl/BytecodeJIT", "throwNoMatchingClause", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", 1, 1);
                athrow();
            }

            if (anyNonTerm) {
                curStack = stackAtEntry + valWords;
                markLabel(endLabel);
                return false;
            } else {
                curStack = stackAtEntry;
                return true;
            }
        }

        private boolean emitSlotInit(int targetLogicalSlot, Node expr) {
            ValType slotT = layout.slotTypes[targetLogicalSlot];
            return emitTyped(expr, slotT);
        }

        private boolean emitRecur(Node node) {
            LoopTarget target = loopTargets.peek();
            if (node instanceof Recur0Node) {
                gotoLabel(target.start);
                return true;
            }
            if (node instanceof Recur1Node r1) {
                if (analysis == null || !analysis.isScalarBoxSlot(target.slots[0])) {
                    if (emitSlotInit(target.slots[0], r1.expr0)) return true;
                    storeLogicalSlot(target.slots[0]);
                }
                gotoLabel(target.start);
                return true;
            }
            if (node instanceof Recur2Node r2) {
                boolean emit0 = analysis == null || !analysis.isScalarBoxSlot(target.slots[0]);
                boolean emit1 = analysis == null || !analysis.isScalarBoxSlot(target.slots[1]);
                if (emit0 && emitSlotInit(target.slots[0], r2.expr0)) return true;
                if (emit1 && emitSlotInit(target.slots[1], r2.expr1)) return true;
                if (emit1) storeLogicalSlot(target.slots[1]);
                if (emit0) storeLogicalSlot(target.slots[0]);
                gotoLabel(target.start);
                return true;
            }
            RecurNNode rn = (RecurNNode) node;
            boolean[] emitMask = new boolean[rn.exprs.length];
            for (int i = 0; i < rn.exprs.length; i++) {
                emitMask[i] = analysis == null || !analysis.isScalarBoxSlot(target.slots[i]);
                if (emitMask[i] && emitSlotInit(target.slots[i], rn.exprs[i])) return true;
            }
            for (int i = rn.slots.length - 1; i >= 0; i--) {
                if (emitMask[i]) storeLogicalSlot(target.slots[i]);
            }
            gotoLabel(target.start);
            return true;
        }

        private boolean emitThrow(ThrowNode tn) {
            emitBoxed(tn.expr);
            invokestatic("cljl/BytecodeJIT", "throwObj", "(Ljava/lang/Object;)Ljava/lang/RuntimeException;", 1, 1);
            athrow();
            return true;
        }

        // ====================================================================
        // Self-Call Emission (Direct & Guarded OwnerVar)
        // ====================================================================

        private boolean tryEmitSelfCall(Node node, ValType desiredT) {
            Node fnNode = null;
            Var var = null;
            Node[] args = null;

            if (node instanceof Call1Node c) { fnNode = c.fn; args = new Node[]{c.a}; }
            else if (node instanceof Call2Node c) { fnNode = c.fn; args = new Node[]{c.a, c.b}; }
            else if (node instanceof Call3Node c) { fnNode = c.fn; args = new Node[]{c.a, c.b, c.c}; }
            else if (node instanceof VarCall1Node c) { var = c.v; args = new Node[]{c.a}; }
            else if (node instanceof VarCall2Node c) { var = c.v; args = new Node[]{c.a, c.b}; }
            else if (node instanceof VarCall3Node c) { var = c.v; args = new Node[]{c.a, c.b, c.c}; }
            else return false;

            int arity = args.length;
            PrimSpec ps = findMatchingPrimSpec(arity, args);

            if (staticFastSelfSpec != null && ps == staticFastSelfSpec
                    && ((fnNode != null && isDirectSelf(fnNode, arity)) || (var != null && isOwnerVarCall(var, arity)))
                    && (desiredT == ValType.ANY || ps.analysis.returnType == desiredT)) {
                emitStaticFastSelfCall(ps, args);
                if (desiredT == ValType.ANY) boxValue(ps.analysis.returnType);
                return true;
            }

            if (fnNode != null && isDirectSelf(fnNode, arity)) {
                if (ps != null && (desiredT == ValType.ANY || ps.analysis.returnType == desiredT)) {
                    emitDirectPrimSelfCall(ps, args);
                    if (desiredT == ValType.ANY) boxValue(ps.analysis.returnType);
                    return true;
                }
                if (desiredT == ValType.ANY) {
                    aload(0);
                    for (Node a : args) emitBoxed(a);
                    invokevirtual(ce.className, "invoke", boxedInvokeDesc(arity), 1 + arity, 1);
                    return true;
                }
                return false;
            }

            if (var != null && isOwnerVarCall(var, arity)) {
                if (ps != null && (desiredT == ValType.ANY || ps.analysis.returnType == desiredT)) {
                    emitGuardedVarPrimSelfCall(var, ps, args, desiredT);
                    return true;
                }
                if (desiredT == ValType.ANY) {
                    emitGuardedVarBoxedSelfCall(var, args);
                    return true;
                }
            }
            return false;
        }

        private void emitStaticFastCallWithCurrentParams(PrimSpec ps) {
            int argWords = 0;
            for (int i = 0; i < ps.paramTypes.length; i++) {
                ValType pt = ps.paramTypes[i];
                loadLogicalSlot(currentMethod.paramSlots[i]);
                argWords += (pt == ValType.LONG || pt == ValType.DOUBLE) ? 2 : 1;
            }
            ValType retT = ps.analysis.returnType;
            int retWords = (retT == ValType.LONG || retT == ValType.DOUBLE) ? 2 : 1;
            invokestatic(ce.className, staticFastSelfMethodName, ps.descriptor, argWords, retWords);
        }

        private void emitStaticFastSelfCall(PrimSpec ps, Node[] args) {
            int arity = args.length;
            ValType retT = ps.analysis.returnType;
            int retWords = (retT == ValType.LONG || retT == ValType.DOUBLE) ? 2 : 1;

            // Depth 0: inline full small body once; Depth 1: inline base-case short-circuit check
            boolean doFullInline = staticFastSelfCanInline && staticFastSelfInlineDepth == 0;
            boolean doLeafInline = false;
            boolean thenIsBase = false;
            if (!doFullInline && staticFastSelfCanInline && staticFastSelfInlineDepth == 1
                    && currentMethod.body instanceof IfNode iff) {
                boolean thenHasSelf = containsSelfCall(iff.thenNode, ce.ownerVar, selfSlot, arity);
                boolean elseHasSelf = containsSelfCall(iff.elseNode, ce.ownerVar, selfSlot, arity);
                if (!thenHasSelf && elseHasSelf) {
                    doLeafInline = true;
                    thenIsBase = true;
                } else if (thenHasSelf && !elseHasSelf) {
                    doLeafInline = true;
                    thenIsBase = false;
                }
            }

            if (!doFullInline && !doLeafInline) {
                int argWords = 0;
                for (int i = 0; i < arity; i++) {
                    ValType pt = ps.paramTypes[i];
                    emitTyped(args[i], pt);
                    argWords += (pt == ValType.LONG || pt == ValType.DOUBLE) ? 2 : 1;
                }
                invokestatic(ce.className, staticFastSelfMethodName, ps.descriptor, argWords, retWords);
                return;
            }

            // Evaluate call arguments under caller's slot layout first
            for (int i = 0; i < arity; i++) {
                emitTyped(args[i], ps.paramTypes[i]);
            }

            // Remap all logical slots to fresh temporary JVM locals for the inlined activation
            int savedTemp = curTempLocals;
            int[] savedJvmSlot = layout.jvmSlot.clone();
            Set<Integer> savedBelow = new HashSet<>(boundedBelowMaxLong);
            Set<Integer> savedAbove = new HashSet<>(boundedAboveMinLong);
            Set<Integer> savedNonNeg = new HashSet<>(nonNegativeLong);
            boundedBelowMaxLong.clear();
            boundedAboveMinLong.clear();
            nonNegativeLong.clear();

            for (int i = 0; i < currentMethod.maxSlots; i++) {
                if (i == selfSlot) continue;
                ValType st = layout.slotTypes[i];
                int w = (st == ValType.LONG || st == ValType.DOUBLE) ? 2 : 1;
                layout.jvmSlot[i] = allocTempLocal(w);
            }
            for (int i = arity - 1; i >= 0; i--) {
                storeLogicalSlot(currentMethod.paramSlots[i]);
            }

            staticFastSelfInlineDepth++;
            if (doFullInline) {
                emitTyped(currentMethod.body, retT);
            } else {
                IfNode iff = (IfNode) currentMethod.body;
                Label secondLabel = new Label();
                Label doneLabel = new Label();
                emitBranchIfFalse(iff.test, secondLabel);
                int stackAtIf = curStack;
                if (thenIsBase) {
                    emitTyped(iff.thenNode, retT);
                    gotoLabel(doneLabel);
                    curStack = stackAtIf;
                    markLabel(secondLabel);
                    emitStaticFastCallWithCurrentParams(ps);
                    markLabel(doneLabel);
                } else {
                    emitStaticFastCallWithCurrentParams(ps);
                    gotoLabel(doneLabel);
                    curStack = stackAtIf;
                    markLabel(secondLabel);
                    emitTyped(iff.elseNode, retT);
                    markLabel(doneLabel);
                }
                curStack = stackAtIf + retWords;
            }
            staticFastSelfInlineDepth--;

            System.arraycopy(savedJvmSlot, 0, layout.jvmSlot, 0, savedJvmSlot.length);
            boundedBelowMaxLong.clear();
            boundedBelowMaxLong.addAll(savedBelow);
            boundedAboveMinLong.clear();
            boundedAboveMinLong.addAll(savedAbove);
            nonNegativeLong.clear();
            nonNegativeLong.addAll(savedNonNeg);
            curTempLocals = savedTemp;
        }

        private void emitDirectPrimSelfCall(PrimSpec ps, Node[] args) {
            aload(0);
            int argWords = 1;
            for (int i = 0; i < args.length; i++) {
                ValType pt = ps.paramTypes[i];
                emitTyped(args[i], pt);
                argWords += (pt == ValType.LONG || pt == ValType.DOUBLE) ? 2 : 1;
            }
            ValType retT = ps.analysis.returnType;
            int retWords = (retT == ValType.LONG || retT == ValType.DOUBLE) ? 2 : 1;
            invokevirtual(ce.className, ps.methodName, ps.descriptor, argWords, retWords);
        }

        private void emitGuardedVarPrimSelfCall(Var var, PrimSpec ps, Node[] args, ValType desiredT) {
            int arity = args.length;
            int stackAtStart = curStack;
            Label slowLabel = new Label();
            Label doneLabel = new Label();

            loadConstObj(var);
            checkcast("cljl/Var");
            invokevirtual("cljl/Var", "get", "()Ljava/lang/Object;", 1, 1);
            dup();
            aload(0);
            getfield(ce.className, "outerFn", "Lcljl/Compiler$CompiledFn;", 1);
            ifAcmpNe(slowLabel);
            pop();

            emitDirectPrimSelfCall(ps, args);
            if (desiredT == ValType.ANY) {
                boxValue(ps.analysis.returnType);
            }
            gotoLabel(doneLabel);

            curStack = stackAtStart + 1;
            markLabel(slowLabel);
            checkcast("cljl/Fn");
            if (arity <= 4) {
                for (Node a : args) emitBoxed(a);
                invokevirtual("cljl/Fn", "invoke", boxedInvokeDesc(arity), 1 + arity, 1);
            } else {
                emitObjectArray(args);
                invokevirtual("cljl/Fn", "invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
            }
            if (desiredT != ValType.ANY) {
                unboxFromObject(desiredT);
            }
            markLabel(doneLabel);
            int outWords = (desiredT == ValType.LONG || desiredT == ValType.DOUBLE) ? 2 : 1;
            curStack = stackAtStart + outWords;
            if (curStack > maxStack) maxStack = curStack;
        }

        private void emitGuardedVarBoxedSelfCall(Var var, Node[] args) {
            int arity = args.length;
            int stackAtStart = curStack;
            Label slowLabel = new Label();
            Label doneLabel = new Label();

            loadConstObj(var);
            checkcast("cljl/Var");
            invokevirtual("cljl/Var", "get", "()Ljava/lang/Object;", 1, 1);
            dup();
            aload(0);
            getfield(ce.className, "outerFn", "Lcljl/Compiler$CompiledFn;", 1);
            ifAcmpNe(slowLabel);
            pop();

            aload(0);
            for (Node a : args) emitBoxed(a);
            invokevirtual(ce.className, "invoke", boxedInvokeDesc(arity), 1 + arity, 1);
            gotoLabel(doneLabel);

            curStack = stackAtStart + 1;
            markLabel(slowLabel);
            checkcast("cljl/Fn");
            if (arity <= 4) {
                for (Node a : args) emitBoxed(a);
                invokevirtual("cljl/Fn", "invoke", boxedInvokeDesc(arity), 1 + arity, 1);
            } else {
                emitObjectArray(args);
                invokevirtual("cljl/Fn", "invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
            }
            markLabel(doneLabel);
            curStack = stackAtStart + 1;
            if (curStack > maxStack) maxStack = curStack;
        }

        private static String boxedInvokeDesc(int arity) {
            return switch (arity) {
                case 0 -> "()Ljava/lang/Object;";
                case 1 -> "(Ljava/lang/Object;)Ljava/lang/Object;";
                case 2 -> "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                case 3 -> "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                case 4 -> "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
                default -> "([Ljava/lang/Object;)Ljava/lang/Object;";
            };
        }

        // ====================================================================
        // Resolved Java Interop Helpers
        // ====================================================================

        private static String typeDesc(Class<?> cls) {
            if (cls == void.class) return "V";
            if (cls == boolean.class) return "Z";
            if (cls == byte.class) return "B";
            if (cls == short.class) return "S";
            if (cls == char.class) return "C";
            if (cls == int.class) return "I";
            if (cls == long.class) return "J";
            if (cls == float.class) return "F";
            if (cls == double.class) return "D";
            if (cls.isArray()) return cls.getName().replace('.', '/');
            return "L" + cls.getName().replace('.', '/') + ";";
        }

        private static String methodDesc(Class<?> ret, Class<?>[] params) {
            StringBuilder sb = new StringBuilder("(");
            for (Class<?> p : params) sb.append(typeDesc(p));
            sb.append(')').append(typeDesc(ret));
            return sb.toString();
        }

        private static int slotWidth(Class<?> cls) {
            if (cls == void.class) return 0;
            return (cls == long.class || cls == double.class) ? 2 : 1;
        }

        private int emitJavaArgs(Node[] args, Class<?>[] paramTypes) {
            int words = 0;
            for (int i = 0; i < paramTypes.length; i++) {
                words += emitJavaArg(args[i], paramTypes[i]);
            }
            return words;
        }

        private int emitJavaArg(Node arg, Class<?> pt) {
            if (pt == long.class) { emitLong(arg); return 2; }
            if (pt == int.class) { emitLong(arg); l2i(); return 1; }
            if (pt == short.class) { emitLong(arg); l2i(); i2s(); return 1; }
            if (pt == byte.class) { emitLong(arg); l2i(); i2b(); return 1; }
            if (pt == double.class) { emitDouble(arg); return 2; }
            if (pt == float.class) { emitDouble(arg); d2f(); return 1; }
            if (pt == boolean.class) { emitBoolean(arg); return 1; }
            if (pt == char.class) {
                if (arg instanceof ConstNode cn && cn.val instanceof Character c) {
                    iconst((int) c);
                } else {
                    emitBoxed(arg);
                    invokestatic("cljl/BytecodeJIT", "toChar", "(Ljava/lang/Object;)C", 1, 1);
                }
                return 1;
            }
            if (pt == Long.class) {
                if (typeOf(arg) == ValType.NIL) aconstNull();
                else { emitLong(arg); boxValue(ValType.LONG); }
                return 1;
            }
            if (pt == Integer.class) {
                if (typeOf(arg) == ValType.NIL) aconstNull();
                else { emitLong(arg); l2i(); invokestatic("java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", 1, 1); }
                return 1;
            }
            if (pt == Double.class) {
                if (typeOf(arg) == ValType.NIL) aconstNull();
                else { emitDouble(arg); boxValue(ValType.DOUBLE); }
                return 1;
            }
            if (pt == Float.class) {
                if (typeOf(arg) == ValType.NIL) aconstNull();
                else { emitDouble(arg); d2f(); invokestatic("java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", 1, 1); }
                return 1;
            }
            if (pt == Boolean.class) {
                if (typeOf(arg) == ValType.NIL) aconstNull();
                else { emitBoolean(arg); boxValue(ValType.BOOL); }
                return 1;
            }
            if (pt == Comparator.class && Fn.class.isAssignableFrom(typeOf(arg).boxClass())) {
                emitBoxed(arg);
                checkcast("cljl/Fn");
                invokevirtual("cljl/Fn", "asComparator", "()Ljava/util/Comparator;", 1, 1);
                return 1;
            }
            if (Interop.isFunctionalInterface(pt) && !pt.isAssignableFrom(typeOf(arg).boxClass())) {
                loadConstObj(pt);
                checkcast("java/lang/Class");
                emitBoxed(arg);
                invokestatic("cljl/Interop", "coerceArg", "(Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                checkcast(pt.getName().replace('.', '/'));
                return 1;
            }
            emitRef(arg, pt);
            return 1;
        }

        private void emitResolvedStaticCallRaw(StaticMethodNode sm, Method m) {
            if (Interop.isConcurrencyClass(m.getDeclaringClass())) {
                invokestatic("cljl/Atom", "markMultiThreaded", "()V", 0, 0);
            }
            String owner = m.getDeclaringClass().getName().replace('.', '/');
            Class<?>[] pts = m.getParameterTypes();
            Class<?> ret = m.getReturnType();
            int argWords = emitJavaArgs(sm.args, pts);
            int retWords = slotWidth(ret);
            invokestatic(owner, m.getName(), methodDesc(ret, pts), argWords, retWords);
        }

        private void emitResolvedInstanceCallRaw(InstanceMethodNode im, Method m) {
            Class<?> ownerCls = m.getDeclaringClass();
            if (Interop.isConcurrencyClass(ownerCls)) {
                invokestatic("cljl/Atom", "markMultiThreaded", "()V", 0, 0);
            }
            String owner = ownerCls.getName().replace('.', '/');
            Class<?>[] pts = m.getParameterTypes();
            Class<?> ret = m.getReturnType();
            emitRef(im.target, im.declaredClass != null ? im.declaredClass : ownerCls);
            int argWords = 1 + emitJavaArgs(im.args, pts);
            int retWords = slotWidth(ret);
            if (ownerCls.isInterface()) {
                invokeinterface(owner, m.getName(), methodDesc(ret, pts), argWords, retWords);
            } else {
                invokevirtual(owner, m.getName(), methodDesc(ret, pts), argWords, retWords);
            }
        }

        private void emitResolvedStaticFieldRaw(Field f) {
            String owner = f.getDeclaringClass().getName().replace('.', '/');
            Class<?> ft = f.getType();
            getstatic(owner, f.getName(), typeDesc(ft), slotWidth(ft));
        }

        private void emitResolvedInstanceFieldRaw(InstanceFieldNode inf, Field f) {
            Class<?> ownerCls = f.getDeclaringClass();
            String owner = ownerCls.getName().replace('.', '/');
            Class<?> ft = f.getType();
            emitRef(inf.target, ownerCls);
            getfield(owner, f.getName(), typeDesc(ft), slotWidth(ft));
        }

        private void coerceJavaReturnToStack(Class<?> retType, ValType desiredT) {
            if (retType == void.class) {
                aconstNull();
                return;
            }
            if (desiredT == ValType.LONG) {
                if (retType == int.class || retType == short.class || retType == byte.class) i2l();
                return;
            }
            if (desiredT == ValType.DOUBLE) {
                if (retType == float.class) f2d();
                return;
            }
            if (desiredT == ValType.BOOL) {
                return;
            }
            // Boxed Object desired
            if (retType == long.class) {
                boxValue(ValType.LONG);
            } else if (retType == int.class || retType == short.class || retType == byte.class) {
                i2l();
                boxValue(ValType.LONG);
            } else if (retType == double.class) {
                boxValue(ValType.DOUBLE);
            } else if (retType == float.class) {
                f2d();
                boxValue(ValType.DOUBLE);
            } else if (retType == boolean.class) {
                boxValue(ValType.BOOL);
            } else if (retType == char.class) {
                invokestatic("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", 1, 1);
            }
        }

        // ====================================================================
        // Boxed Object Emission (returns true if control terminated)
        // ====================================================================

        boolean emitBoxed(Node node) {
            ValType nt = typeOf(node);
            if (nt == ValType.LONG && !(node instanceof ConstNode)) {
                if (emitLong(node)) return true;
                boxValue(ValType.LONG);
                return false;
            }
            if (nt == ValType.DOUBLE && !(node instanceof ConstNode)) {
                if (emitDouble(node)) return true;
                boxValue(ValType.DOUBLE);
                return false;
            }
            if (nt == ValType.BOOL && !(node instanceof ConstNode)) {
                if (emitBoolean(node)) return true;
                boxValue(ValType.BOOL);
                return false;
            }
            return emitBoxedImpl(node);
        }

        boolean emitBoxedImpl(Node node) {
            if (node instanceof ConstNode cn) {
                Object v = cn.val;
                if (v == null) {
                    aconstNull();
                } else if (v == Boolean.TRUE) {
                    getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;", 1);
                } else if (v == Boolean.FALSE) {
                    getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;", 1);
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
                int vroot = scalarVolatileRoot(ln);
                if (vroot != -1) {
                    emitMaterializeScalarVolatile(vroot);
                    return false;
                }
                int aroot = scalarAtomRoot(ln);
                if (aroot != -1) {
                    emitMaterializeScalarAtom(aroot);
                    return false;
                }
                ValType st = layout.slotTypes[ln.slot];
                loadLogicalSlot(ln.slot);
                boxValue(st);
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
                    emitBoxed(dn.init);
                    invokevirtual("cljl/Var", "bindRoot", "(Ljava/lang/Object;)Lcljl/Var;", 2, 1);
                    pop();
                }
                return false;
            }
            if (node instanceof DefMacroNode dm) {
                loadConstObj(dm.var);
                checkcast("cljl/Var");
                emitBoxed(dm.fnNode);
                invokevirtual("cljl/Var", "bindRoot", "(Ljava/lang/Object;)Lcljl/Var;", 2, 1);
                invokevirtual("cljl/Var", "setMacro", "()Lcljl/Var;", 1, 1);
                return false;
            }
            if (node instanceof IfNode iff) return emitIfTyped(iff, ValType.ANY);
            if (node instanceof DoNode dn) return emitDoTyped(dn, ValType.ANY);
            if (node instanceof LetNode ln) return emitLetTyped(ln, ValType.ANY);
            if (node instanceof LoopNode lp) return emitLoopTyped(lp, ValType.ANY);
            if (node instanceof LockingNode ln) return emitLockingTyped(ln, ValType.ANY);
            if (node instanceof TryNode tn) return emitTryTyped(tn, ValType.ANY);
            if (node instanceof CaseNode cn) return emitCaseTyped(cn, ValType.ANY);
            if (node instanceof Recur0Node || node instanceof Recur1Node
                    || node instanceof Recur2Node || node instanceof RecurNNode) {
                return emitRecur(node);
            }
            if (node instanceof ThrowNode tn) return emitThrow(tn);

            if (node instanceof VectorNode vn) {
                emitObjectArray(vn.elems);
                invokestatic("cljl/lang/PersistentVector", "createOwning",
                        "([Ljava/lang/Object;)Lcljl/lang/PersistentVector;", 1, 1);
                return false;
            }
            if (node instanceof MapNode mn) {
                emitObjectArray(mn.kvs);
                invokestatic("cljl/lang/PersistentHashMap", "create",
                        "([Ljava/lang/Object;)Lcljl/lang/PersistentHashMap;", 1, 1);
                return false;
            }
            if (node instanceof SetNode sn) {
                emitObjectArray(sn.elems);
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
                        emitCapturedSlots(m.capturedParentSlots);
                        aastore();
                    }
                }
                if (fn.variadicMethod != null && fn.variadicMethod.capturedParentSlots.length > 0) {
                    dup();
                    iconst(fn.fixedMethods.length);
                    emitCapturedSlots(fn.variadicMethod.capturedParentSlots);
                    aastore();
                }
                invokestatic("cljl/BytecodeJIT", "instantiateFn",
                        "(Lcljl/Compiler$FnNode;[[Ljava/lang/Object;)Lcljl/Fn;", 2, 1);
                return false;
            }

            // Self-calls (local & guarded ownerVar)
            if (tryEmitSelfCall(node, ValType.ANY)) {
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
                emitBoxed(c.a);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof VarCall2Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a);
                emitBoxed(c.b);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof VarCall3Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a);
                emitBoxed(c.b);
                emitBoxed(c.c);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                return false;
            }
            if (node instanceof VarCall4Node c) {
                emitVarFn(c.v);
                emitBoxed(c.a);
                emitBoxed(c.b);
                emitBoxed(c.c);
                emitBoxed(c.d);
                invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                return false;
            }
            if (node instanceof VarCallNNode c) {
                emitVarFn(c.v);
                emitObjectArray(c.args);
                invokevirtual("cljl/Fn", "invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }

            // General fn calls (supports both cljl.Fn and any external clojure.lang.IFn)
            if (node instanceof Call0Node c) {
                if (isDirectSelf(c.fn, 0)) {
                    aload(0);
                    invokevirtual(ce.className, "invoke", "()Ljava/lang/Object;", 1, 1);
                } else if (Fn.class.isAssignableFrom(typeOf(c.fn).boxClass())) {
                    emitBoxed(c.fn);
                    checkcast("cljl/Fn");
                    invokevirtual("cljl/Fn", "invoke", "()Ljava/lang/Object;", 1, 1);
                } else {
                    emitBoxed(c.fn);
                    checkcast("clojure/lang/IFn");
                    invokeinterface("clojure/lang/IFn", "invoke", "()Ljava/lang/Object;", 1, 1);
                }
                return false;
            }
            if (node instanceof Call1Node c) {
                if (Fn.class.isAssignableFrom(typeOf(c.fn).boxClass())) {
                    emitBoxed(c.fn);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                } else {
                    emitBoxed(c.fn);
                    checkcast("clojure/lang/IFn");
                    emitBoxed(c.a);
                    invokeinterface("clojure/lang/IFn", "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return false;
            }
            if (node instanceof Call2Node c) {
                if (Fn.class.isAssignableFrom(typeOf(c.fn).boxClass())) {
                    emitBoxed(c.fn);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a);
                    emitBoxed(c.b);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                } else {
                    emitBoxed(c.fn);
                    checkcast("clojure/lang/IFn");
                    emitBoxed(c.a);
                    emitBoxed(c.b);
                    invokeinterface("clojure/lang/IFn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                }
                return false;
            }
            if (node instanceof Call3Node c) {
                if (Fn.class.isAssignableFrom(typeOf(c.fn).boxClass())) {
                    emitBoxed(c.fn);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a);
                    emitBoxed(c.b);
                    emitBoxed(c.c);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                } else {
                    emitBoxed(c.fn);
                    checkcast("clojure/lang/IFn");
                    emitBoxed(c.a);
                    emitBoxed(c.b);
                    emitBoxed(c.c);
                    invokeinterface("clojure/lang/IFn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                }
                return false;
            }
            if (node instanceof Call4Node c) {
                if (isDirectSelf(c.fn, 4)) {
                    aload(0);
                    emitBoxed(c.a); emitBoxed(c.b); emitBoxed(c.c); emitBoxed(c.d);
                    invokevirtual(ce.className, "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                } else if (Fn.class.isAssignableFrom(typeOf(c.fn).boxClass())) {
                    emitBoxed(c.fn);
                    checkcast("cljl/Fn");
                    emitBoxed(c.a); emitBoxed(c.b); emitBoxed(c.c); emitBoxed(c.d);
                    invokevirtual("cljl/Fn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                } else {
                    emitBoxed(c.fn);
                    checkcast("clojure/lang/IFn");
                    emitBoxed(c.a); emitBoxed(c.b); emitBoxed(c.c); emitBoxed(c.d);
                    invokeinterface("clojure/lang/IFn", "invoke", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                }
                return false;
            }
            if (node instanceof CallNNode c) {
                emitBoxed(c.fn);
                checkcast("cljl/Fn");
                emitObjectArray(c.args);
                invokevirtual("cljl/Fn", "invokeVarargs", "([Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }

            // Arithmetic intrinsics (polymorphic fallback when not purely LONG/DOUBLE)
            if (node instanceof Add2Node a) { emitBinaryNumOp(a.a, a.b, "add"); return false; }
            if (node instanceof Sub2Node a) { emitBinaryNumOp(a.a, a.b, "minus"); return false; }
            if (node instanceof Mul2Node a) { emitBinaryNumOp(a.a, a.b, "multiply"); return false; }
            if (node instanceof Div2Node a) {
                if (typeOf(a.a) == ValType.DOUBLE) {
                    emitDouble(a.a);
                    emitBoxed(a.b);
                    invokestatic("cljl/Numbers", "divide", "(DLjava/lang/Object;)Ljava/lang/Number;", 3, 1);
                    return false;
                }
                if (typeOf(a.b) == ValType.DOUBLE) {
                    emitBoxed(a.a);
                    emitDouble(a.b);
                    invokestatic("cljl/Numbers", "divide", "(Ljava/lang/Object;D)Ljava/lang/Number;", 3, 1);
                    return false;
                }
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "divide", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof NegateNode a) {
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "minus", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof IncNode a) {
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "inc", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof DecNode a) {
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "dec", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof Rem2Node a) {
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "remainder", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof Mod2Node a) {
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "mod", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof Quot2Node a) {
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "quotient", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof UncheckedAdd2Node a) {
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "uncheckedAdd", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof UncheckedSub2Node a) {
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "uncheckedSubtract", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof UncheckedMul2Node a) {
                emitBoxed(a.a);
                emitBoxed(a.b);
                invokestatic("cljl/Numbers", "uncheckedMultiply", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
                return false;
            }
            if (node instanceof UncheckedIncNode a) {
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "uncheckedInc", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof UncheckedDecNode a) {
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "uncheckedDec", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }
            if (node instanceof UncheckedNegateNode a) {
                emitBoxed(a.a);
                invokestatic("cljl/Numbers", "uncheckedNegate", "(Ljava/lang/Object;)Ljava/lang/Number;", 1, 1);
                return false;
            }

            // Boolean nodes boxed fallback
            if (isBooleanNode(node)) {
                emitBoolean(node);
                boxValue(ValType.BOOL);
                return false;
            }

            // Collection & sequence intrinsics
            if (node instanceof AgetNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls == char[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    caload();
                    invokestatic("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", 1, 1);
                    return false;
                }
                if (arrCls != null && !arrCls.getComponentType().isPrimitive()) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    aaload();
                    return false;
                }
                emitBoxed(a.arr);
                emitLong(a.idx);
                l2i();
                invokestatic("cljl/RT", "aget", "(Ljava/lang/Object;I)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof AsetNode a) {
                Class<?> arrCls = arrayClassOf(a.arr);
                if (arrCls == char[].class) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    if (a.val instanceof ConstNode cn && cn.val instanceof Character c) {
                        iconst((int) c);
                    } else {
                        emitBoxed(a.val);
                        invokestatic("cljl/BytecodeJIT", "toChar", "(Ljava/lang/Object;)C", 1, 1);
                    }
                    dupX2();
                    castore();
                    invokestatic("java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", 1, 1);
                    return false;
                }
                if (arrCls != null && !arrCls.getComponentType().isPrimitive()) {
                    emitArrayAndIndex(a.arr, arrCls, a.idx);
                    emitRef(a.val, arrCls.getComponentType());
                    dupX2();
                    aastore();
                    return false;
                }
                emitBoxed(a.arr);
                emitLong(a.idx);
                l2i();
                emitBoxed(a.val);
                invokestatic("cljl/RT", "aset", "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof NthNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentVector.class) {
                        emitRef(a.coll, PersistentVector.class);
                        emitLong(a.idx);
                        l2i();
                        if (a.notFound == null) {
                            invokevirtual("cljl/lang/PersistentVector", "nth", "(I)Ljava/lang/Object;", 2, 1);
                        } else {
                            emitBoxed(a.notFound);
                            invokevirtual("cljl/lang/PersistentVector", "nth", "(ILjava/lang/Object;)Ljava/lang/Object;", 3, 1);
                        }
                        return false;
                    }
                    if (ct.refClass == PersistentVector.SubVec.class) {
                        emitRef(a.coll, PersistentVector.SubVec.class);
                        emitLong(a.idx);
                        l2i();
                        if (a.notFound == null) {
                            invokevirtual("cljl/lang/PersistentVector$SubVec", "nth", "(I)Ljava/lang/Object;", 2, 1);
                        } else {
                            emitBoxed(a.notFound);
                            invokevirtual("cljl/lang/PersistentVector$SubVec", "nth", "(ILjava/lang/Object;)Ljava/lang/Object;", 3, 1);
                        }
                        return false;
                    }
                }
                emitBoxed(a.coll);
                emitLong(a.idx);
                l2i();
                if (a.notFound == null) {
                    invokestatic("cljl/RT", "nth", "(Ljava/lang/Object;I)Ljava/lang/Object;", 2, 1);
                } else {
                    emitBoxed(a.notFound);
                    invokestatic("cljl/RT", "nth", "(Ljava/lang/Object;ILjava/lang/Object;)Ljava/lang/Object;", 3, 1);
                }
                return false;
            }
            if (node instanceof GetNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentHashMap.class) {
                        emitRef(a.coll, PersistentHashMap.class);
                        emitBoxed(a.key);
                        if (a.notFound == null) {
                            invokevirtual("cljl/lang/PersistentHashMap", "valAt", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                        } else {
                            emitBoxed(a.notFound);
                            invokevirtual("cljl/lang/PersistentHashMap", "valAt", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                        }
                        return false;
                    }
                    if (ct.refClass == PersistentHashSet.class) {
                        emitRef(a.coll, PersistentHashSet.class);
                        emitBoxed(a.key);
                        if (a.notFound == null) {
                            invokevirtual("cljl/lang/PersistentHashSet", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                        } else {
                            emitBoxed(a.notFound);
                            invokevirtual("cljl/lang/PersistentHashSet", "get", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                        }
                        return false;
                    }
                }
                emitBoxed(a.coll);
                emitBoxed(a.key);
                if (a.notFound == null) {
                    invokestatic("cljl/RT", "get", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                } else {
                    emitBoxed(a.notFound);
                    invokestatic("cljl/RT", "get", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                }
                return false;
            }
            if (node instanceof TransientNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentVector.class) {
                        emitRef(a.coll, PersistentVector.class);
                        invokevirtual("cljl/lang/PersistentVector", "asTransient",
                                "()Lcljl/lang/PersistentVector$TransientVector;", 1, 1);
                        return false;
                    }
                    if (ct.refClass == PersistentHashMap.class) {
                        emitRef(a.coll, PersistentHashMap.class);
                        invokevirtual("cljl/lang/PersistentHashMap", "asTransient",
                                "()Lcljl/lang/PersistentHashMap$TransientHashMap;", 1, 1);
                        return false;
                    }
                    if (ct.refClass == PersistentHashSet.class) {
                        emitRef(a.coll, PersistentHashSet.class);
                        invokevirtual("cljl/lang/PersistentHashSet", "asTransient",
                                "()Lcljl/lang/PersistentHashSet$TransientHashSet;", 1, 1);
                        return false;
                    }
                }
                emitBoxed(a.coll);
                invokestatic("cljl/RT", "transientColl", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
                return false;
            }
            if (node instanceof PersistentBangNode a) {
                ValType tt = typeOf(a.target);
                if (tt.isRef() && tt.nonNull) {
                    if (tt.refClass == PersistentVector.TransientVector.class) {
                        emitRef(a.target, PersistentVector.TransientVector.class);
                        invokevirtual("cljl/lang/PersistentVector$TransientVector", "persistent",
                                "()Lcljl/lang/PersistentVector;", 1, 1);
                        return false;
                    }
                    if (tt.refClass == PersistentHashMap.TransientHashMap.class) {
                        emitRef(a.target, PersistentHashMap.TransientHashMap.class);
                        invokevirtual("cljl/lang/PersistentHashMap$TransientHashMap", "persistent",
                                "()Lcljl/lang/PersistentHashMap;", 1, 1);
                        return false;
                    }
                    if (tt.refClass == PersistentHashSet.TransientHashSet.class) {
                        emitRef(a.target, PersistentHashSet.TransientHashSet.class);
                        invokevirtual("cljl/lang/PersistentHashSet$TransientHashSet", "persistent",
                                "()Lcljl/lang/PersistentHashSet;", 1, 1);
                        return false;
                    }
                }
                emitBoxed(a.target);
                invokestatic("cljl/RT", "persistentBang", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
                return false;
            }
            if (node instanceof ConjBangNode a) {
                ValType tt = typeOf(a.target);
                if (tt.isRef() && tt.nonNull) {
                    if (tt.refClass == PersistentVector.TransientVector.class) {
                        emitRef(a.target, PersistentVector.TransientVector.class);
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentVector$TransientVector", "conj",
                                "(Ljava/lang/Object;)Lcljl/lang/PersistentVector$TransientVector;", 2, 1);
                        return false;
                    }
                    if (tt.refClass == PersistentHashSet.TransientHashSet.class) {
                        emitRef(a.target, PersistentHashSet.TransientHashSet.class);
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentHashSet$TransientHashSet", "conj",
                                "(Ljava/lang/Object;)Lcljl/lang/PersistentHashSet$TransientHashSet;", 2, 1);
                        return false;
                    }
                }
                emitBoxed(a.target);
                emitBoxed(a.val);
                invokestatic("cljl/RT", "conjBang", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof AssocBangNode a) {
                ValType tt = typeOf(a.target);
                if (tt.isRef() && tt.nonNull && tt.refClass == PersistentHashMap.TransientHashMap.class) {
                    emitRef(a.target, PersistentHashMap.TransientHashMap.class);
                    emitBoxed(a.key);
                    emitBoxed(a.val);
                    invokevirtual("cljl/lang/PersistentHashMap$TransientHashMap", "assoc",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Lcljl/lang/PersistentHashMap$TransientHashMap;", 3, 1);
                    return false;
                }
                emitBoxed(a.target);
                emitBoxed(a.key);
                emitBoxed(a.val);
                invokestatic("cljl/RT", "assocBang", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof ConjNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentVector.class) {
                        emitRef(a.coll, PersistentVector.class);
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentVector", "conj",
                                "(Ljava/lang/Object;)Lcljl/lang/PersistentVector;", 2, 1);
                        return false;
                    }
                    if (ct.refClass == PersistentList.class) {
                        emitRef(a.coll, PersistentList.class);
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentList", "cons",
                                "(Ljava/lang/Object;)Lcljl/lang/PersistentList;", 2, 1);
                        return false;
                    }
                    if (ct.refClass == PersistentHashSet.class) {
                        emitRef(a.coll, PersistentHashSet.class);
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentHashSet", "conj",
                                "(Ljava/lang/Object;)Lcljl/lang/PersistentHashSet;", 2, 1);
                        return false;
                    }
                    if (IPersistentList.class.isAssignableFrom(ct.refClass)) {
                        emitRef(a.coll, IPersistentList.class);
                        emitBoxed(a.val);
                        invokeinterface("cljl/lang/IPersistentList", "cons",
                                "(Ljava/lang/Object;)Lcljl/lang/PersistentList;", 2, 1);
                        return false;
                    }
                }
                emitBoxed(a.coll);
                emitBoxed(a.val);
                invokestatic("cljl/RT", "conj", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof AssocNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull && ct.refClass == PersistentHashMap.class) {
                    emitRef(a.coll, PersistentHashMap.class);
                    emitBoxed(a.key);
                    emitBoxed(a.val);
                    invokevirtual("cljl/lang/PersistentHashMap", "assoc",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Lcljl/lang/PersistentHashMap;", 3, 1);
                    return false;
                }
                if (ct.isRef() && ct.nonNull && ct.refClass == PersistentVector.class) {
                    emitRef(a.coll, PersistentVector.class);
                    if (typeOf(a.key).isLong()) {
                        emitLong(a.key);
                        l2i();
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentVector", "assocN",
                                "(ILjava/lang/Object;)Lcljl/lang/PersistentVector;", 3, 1);
                    } else {
                        emitBoxed(a.key);
                        emitBoxed(a.val);
                        invokevirtual("cljl/lang/PersistentVector", "assoc",
                                "(Ljava/lang/Object;Ljava/lang/Object;)Lcljl/lang/PersistentVector;", 3, 1);
                    }
                    return false;
                }
                emitBoxed(a.coll);
                emitBoxed(a.key);
                emitBoxed(a.val);
                invokestatic("cljl/RT", "assoc",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Lcljl/lang/Associative;", 3, 1);
                return false;
            }
            if (node instanceof DissocNode d) {
                ValType ct = typeOf(d.coll);
                if (ct.isRef() && ct.nonNull && ct.refClass == PersistentHashMap.class) {
                    emitRef(d.coll, PersistentHashMap.class);
                    emitBoxed(d.key);
                    invokevirtual("cljl/lang/PersistentHashMap", "without",
                            "(Ljava/lang/Object;)Lcljl/lang/PersistentHashMap;", 2, 1);
                    return false;
                }
                emitBoxed(d.coll);
                emitBoxed(d.key);
                invokestatic("cljl/RT", "dissoc", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof FirstNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentList.class) {
                        emitRef(a.coll, PersistentList.class);
                        getfield("cljl/lang/PersistentList", "first", "Ljava/lang/Object;", 1);
                        return false;
                    }
                    if (ISeq.class.isAssignableFrom(ct.refClass)) {
                        emitRef(a.coll, ISeq.class);
                        invokeinterface("cljl/lang/ISeq", "first", "()Ljava/lang/Object;", 1, 1);
                        return false;
                    }
                }
                emitBoxed(a.coll);
                invokestatic("cljl/RT", "first", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
                return false;
            }
            if (node instanceof NextNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentList.class) {
                        emitRef(a.coll, PersistentList.class);
                        invokevirtual("cljl/lang/PersistentList", "next", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                    if (ct.refClass == Cons.class) {
                        emitRef(a.coll, Cons.class);
                        invokevirtual("cljl/lang/Cons", "next", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                    if (ISeq.class.isAssignableFrom(ct.refClass)) {
                        emitRef(a.coll, ISeq.class);
                        invokeinterface("cljl/lang/ISeq", "next", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                }
                emitBoxed(a.coll);
                invokestatic("cljl/RT", "next", "(Ljava/lang/Object;)Lcljl/lang/ISeq;", 1, 1);
                return false;
            }
            if (node instanceof RestNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentList.class || ct.refClass == PersistentList.EmptyList.class) {
                        emitRef(a.coll, ASeq.class);
                        invokevirtual("cljl/lang/ASeq", "more", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                    if (ct.refClass == Cons.class) {
                        emitRef(a.coll, Cons.class);
                        invokevirtual("cljl/lang/Cons", "more", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                    if (ISeq.class.isAssignableFrom(ct.refClass)) {
                        emitRef(a.coll, ISeq.class);
                        invokeinterface("cljl/lang/ISeq", "more", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                }
                emitBoxed(a.coll);
                invokestatic("cljl/RT", "more", "(Ljava/lang/Object;)Lcljl/lang/ISeq;", 1, 1);
                return false;
            }
            if (node instanceof VolatileNode vn) {
                ValType it = typeOf(vn.init);
                newInst("cljl/Atom$Volatile");
                dup();
                if (it == ValType.LONG) {
                    if (emitLong(vn.init)) return true;
                    invokespecial("cljl/Atom$Volatile", "<init>", "(J)V", 3, 0);
                } else if (it == ValType.DOUBLE) {
                    if (emitDouble(vn.init)) return true;
                    invokespecial("cljl/Atom$Volatile", "<init>", "(D)V", 3, 0);
                } else {
                    if (emitBoxed(vn.init)) return true;
                    invokespecial("cljl/Atom$Volatile", "<init>", "(Ljava/lang/Object;)V", 2, 0);
                }
                return false;
            }
            if (node instanceof DerefNode dn) {
                int root = scalarBoxRoot(dn.target);
                if (root != -1) {
                    emitScalarSlotAs(root, ValType.ANY);
                    return false;
                }
                ValType tt = typeOf(dn.target);
                if (tt.isRef() && tt.refClass == Atom.Volatile.class) {
                    emitRef(dn.target, Atom.Volatile.class);
                    invokevirtual("cljl/Atom$Volatile", "deref", "()Ljava/lang/Object;", 1, 1);
                    return false;
                }
                if (tt.isRef() && tt.refClass == Atom.class) {
                    emitRef(dn.target, Atom.class);
                    invokevirtual("cljl/Atom", "deref", "()Ljava/lang/Object;", 1, 1);
                    return false;
                }
                emitBoxed(dn.target);
                checkcast("cljl/lang/IDeref");
                invokeinterface("cljl/lang/IDeref", "deref", "()Ljava/lang/Object;", 1, 1);
                return false;
            }
            if (node instanceof VresetNode vr) {
                int root = scalarVolatileRoot(vr.target);
                if (root != -1) {
                    if (emitSlotInit(root, vr.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.ANY);
                    return false;
                }
                int fused = tryEmitFusedVreset(vr, ValType.ANY);
                if (fused != 0) return fused == 2;
                emitRef(vr.target, Atom.Volatile.class);
                ValType vt = typeOf(vr.val);
                if (vt == ValType.LONG) {
                    if (emitLong(vr.val)) return true;
                    invokevirtual("cljl/Atom$Volatile", "resetLong", "(J)J", 3, 2);
                    boxValue(ValType.LONG);
                } else if (vt == ValType.DOUBLE) {
                    if (emitDouble(vr.val)) return true;
                    invokevirtual("cljl/Atom$Volatile", "resetDouble", "(D)D", 3, 2);
                    boxValue(ValType.DOUBLE);
                } else {
                    if (emitBoxed(vr.val)) return true;
                    invokevirtual("cljl/Atom$Volatile", "reset", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return false;
            }
            if (node instanceof AtomNode an) {
                newInst("cljl/Atom");
                dup();
                if (typeOf(an.init) == ValType.LONG) {
                    if (emitLong(an.init)) return true;
                    invokespecial("cljl/Atom", "<init>", "(J)V", 3, 0);
                } else {
                    if (emitBoxed(an.init)) return true;
                    invokespecial("cljl/Atom", "<init>", "(Ljava/lang/Object;)V", 2, 0);
                }
                return false;
            }
            if (node instanceof ResetBangNode rb) {
                int root = scalarAtomRoot(rb.target);
                if (root != -1) {
                    if (emitSlotInit(root, rb.val)) return true;
                    storeLogicalSlot(root);
                    invalidateVolatileBounds(root);
                    emitScalarSlotAs(root, ValType.ANY);
                    return false;
                }
                emitRef(rb.target, Atom.class);
                if (typeOf(rb.val) == ValType.LONG) {
                    if (emitLong(rb.val)) return true;
                    invokevirtual("cljl/Atom", "resetLong", "(J)J", 3, 2);
                    boxValue(ValType.LONG);
                } else {
                    if (emitBoxed(rb.val)) return true;
                    invokevirtual("cljl/Atom", "reset", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return false;
            }
            if (node instanceof SwapBangNode sb) {
                int root = scalarAtomRoot(sb.target);
                if (root != -1) {
                    return emitScalarAtomSwap(sb, root, ValType.ANY);
                }
                emitRef(sb.target, Atom.class);
                if (sb.fn instanceof VarRefNode vr) {
                    emitVarFn(vr.var);
                } else {
                    emitRef(sb.fn, Fn.class);
                }
                switch (sb.extraArgs.length) {
                    case 0 -> invokevirtual("cljl/Atom", "swap", "(Lcljl/Fn;)Ljava/lang/Object;", 2, 1);
                    case 1 -> {
                        if (emitBoxed(sb.extraArgs[0])) return true;
                        invokevirtual("cljl/Atom", "swap", "(Lcljl/Fn;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                    }
                    case 2 -> {
                        if (emitBoxed(sb.extraArgs[0])) return true;
                        if (emitBoxed(sb.extraArgs[1])) return true;
                        invokevirtual("cljl/Atom", "swap", "(Lcljl/Fn;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                    }
                    default -> {
                        if (emitBoxed(sb.extraArgs[0])) return true;
                        if (emitBoxed(sb.extraArgs[1])) return true;
                        Node[] rest = Arrays.copyOfRange(sb.extraArgs, 2, sb.extraArgs.length);
                        emitObjectArray(rest);
                        invokevirtual("cljl/Atom", "swap", "(Lcljl/Fn;Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                    }
                }
                return false;
            }
            if (node instanceof SeqNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull) {
                    if (ct.refClass == PersistentList.class || ct.refClass == Cons.class) {
                        emitRef(a.coll, ct.refClass);
                        return false;
                    }
                    if (Seqable.class.isAssignableFrom(ct.refClass)) {
                        emitRef(a.coll, Seqable.class);
                        invokeinterface("cljl/lang/Seqable", "seq", "()Lcljl/lang/ISeq;", 1, 1);
                        return false;
                    }
                }
                emitBoxed(a.coll);
                invokestatic("cljl/RT", "seq", "(Ljava/lang/Object;)Lcljl/lang/ISeq;", 1, 1);
                return false;
            }
            if (node instanceof PopNode a) {
                ValType ct = typeOf(a.coll);
                if (ct.isRef() && ct.nonNull && ct.refClass == PersistentVector.class) {
                    emitRef(a.coll, PersistentVector.class);
                    invokevirtual("cljl/lang/PersistentVector", "pop", "()Lcljl/lang/PersistentVector;", 1, 1);
                    return false;
                }
                emitBoxed(a.coll);
                invokestatic("cljl/RT", "pop", "(Ljava/lang/Object;)Ljava/lang/Object;", 1, 1);
                return false;
            }

            // Java interop (direct typed when resolved, otherwise cached reflection fallback)
            if (node instanceof NewNode nn) {
                Constructor<?> ctor = analysis != null ? analysis.resolvedCtors.get(nn) : null;
                if (ctor != null) {
                    if (Interop.isConcurrencyClass(nn.clazz)) {
                        invokestatic("cljl/Atom", "markMultiThreaded", "()V", 0, 0);
                    }
                    String owner = nn.clazz.getName().replace('.', '/');
                    Class<?>[] pts = ctor.getParameterTypes();
                    newInst(owner);
                    dup();
                    int argWords = emitJavaArgs(nn.args, pts);
                    invokespecial(owner, "<init>", methodDesc(void.class, pts), 1 + argWords, 0);
                    return false;
                }
                loadConstObj(nn.clazz);
                checkcast("java/lang/Class");
                if (nn.paramTags != null) {
                    loadConstObj(nn.paramTags);
                    checkcast("[Ljava/lang/Class;");
                    emitObjectArrayOrEmpty(nn.args);
                    invokestatic("cljl/Interop", "invokeConstructorWithTags", "(Ljava/lang/Class;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                } else {
                    emitObjectArrayOrEmpty(nn.args);
                    invokestatic("cljl/Interop", "invokeConstructor", "(Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                }
                return false;
            }
            if (node instanceof StaticFieldNode sf) {
                Field f = analysis != null ? analysis.resolvedFields.get(sf) : null;
                if (f != null) {
                    emitResolvedStaticFieldRaw(f);
                    coerceJavaReturnToStack(f.getType(), ValType.ANY);
                    return false;
                }
                loadConstObj(sf.clazz);
                checkcast("java/lang/Class");
                ldcString(sf.fieldName);
                invokestatic("cljl/Interop", "getStaticField", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof StaticMethodNode sm) {
                Method m = analysis != null ? analysis.resolvedMethods.get(sm) : null;
                if (m != null) {
                    emitResolvedStaticCallRaw(sm, m);
                    coerceJavaReturnToStack(m.getReturnType(), ValType.ANY);
                    return false;
                }
                loadConstObj(sm.clazz);
                checkcast("java/lang/Class");
                ldcString(sm.methodName);
                if (sm.paramTags != null) {
                    loadConstObj(sm.paramTags);
                    checkcast("[Ljava/lang/Class;");
                    emitObjectArrayOrEmpty(sm.args);
                    invokestatic("cljl/Interop", "invokeStaticMethodWithTags", "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Object;", 4, 1);
                } else {
                    emitObjectArrayOrEmpty(sm.args);
                    invokestatic("cljl/Interop", "invokeStaticMethod", "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                }
                return false;
            }
            if (node instanceof InstanceFieldNode inf) {
                Field f = analysis != null ? analysis.resolvedFields.get(inf) : null;
                if (f != null) {
                    emitResolvedInstanceFieldRaw(inf, f);
                    coerceJavaReturnToStack(f.getType(), ValType.ANY);
                    return false;
                }
                emitBoxed(inf.target);
                ldcString(inf.fieldName);
                invokestatic("cljl/Interop", "getInstanceField", "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof InstanceMethodNode im) {
                Method m = analysis != null ? analysis.resolvedMethods.get(im) : null;
                if (m != null) {
                    emitResolvedInstanceCallRaw(im, m);
                    coerceJavaReturnToStack(m.getReturnType(), ValType.ANY);
                    return false;
                }
                if (im.declaredClass != null) {
                    loadConstObj(im.declaredClass);
                    checkcast("java/lang/Class");
                    ldcString(im.methodName);
                    if (im.paramTags != null) {
                        loadConstObj(im.paramTags);
                        checkcast("[Ljava/lang/Class;");
                    } else {
                        aconstNull();
                    }
                    emitBoxed(im.target);
                    emitObjectArrayOrEmpty(im.args);
                    invokestatic("cljl/Interop", "invokeQualifiedInstanceMethod", "(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", 5, 1);
                } else {
                    emitBoxed(im.target);
                    ldcString(im.methodName);
                    emitObjectArrayOrEmpty(im.args);
                    invokestatic("cljl/Interop", "invokeInstanceMethod", "(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                }
                return false;
            }
            if (node instanceof SetVarNode sv) {
                loadConstObj(sv.var);
                checkcast("cljl/Var");
                emitBoxed(sv.val);
                invokevirtual("cljl/Var", "set", "(Ljava/lang/Object;)Ljava/lang/Object;", 2, 1);
                return false;
            }
            if (node instanceof SetFieldNode sf) {
                emitBoxed(sf.target);
                ldcString(sf.fieldName);
                emitBoxed(sf.val);
                invokestatic("cljl/Interop", "setInstanceField", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof SetStaticFieldNode ssf) {
                loadConstObj(ssf.clazz);
                checkcast("java/lang/Class");
                ldcString(ssf.fieldName);
                emitBoxed(ssf.val);
                invokestatic("cljl/Interop", "setStaticField", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", 3, 1);
                return false;
            }
            if (node instanceof MonitorNode mn) {
                emitBoxed(mn.target);
                iconst(mn.enter ? 1 : 0);
                invokestatic("cljl/Compiler", "monitorOp", "(Ljava/lang/Object;Z)Ljava/lang/Object;", 2, 1);
                return false;
            }

            throw new IllegalStateException("Unsupported node in BytecodeJIT: " + node.getClass().getName());
        }

        private void emitBinaryNumOp(Node a, Node b, String methodName) {
            if (typeOf(b) == ValType.LONG || (b instanceof ConstNode cn && cn.isLong)) {
                emitBoxed(a);
                emitLong(b);
                invokestatic("cljl/Numbers", methodName, "(Ljava/lang/Object;J)Ljava/lang/Number;", 3, 1);
            } else if (typeOf(a) == ValType.LONG || (a instanceof ConstNode cn && cn.isLong)) {
                emitLong(a);
                emitBoxed(b);
                invokestatic("cljl/Numbers", methodName, "(JLjava/lang/Object;)Ljava/lang/Number;", 3, 1);
            } else {
                emitBoxed(a);
                emitBoxed(b);
                invokestatic("cljl/Numbers", methodName, "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Number;", 2, 1);
            }
        }

        private static boolean isBooleanNode(Node n) {
            return n instanceof Lt2Node || n instanceof Lte2Node || n instanceof Gt2Node || n instanceof Gte2Node
                    || n instanceof Equiv2Node || n instanceof NumEq2Node || n instanceof ZeroPNode
                    || n instanceof PosPNode || n instanceof NegPNode || n instanceof NilPNode
                    || n instanceof NotNode || n instanceof IdenticalNode
                    || n instanceof EvenPNode || n instanceof OddPNode || n instanceof TypePredNode;
        }

        private void emitVarFn(Var v) {
            loadConstObj(v);
            checkcast("cljl/Var");
            invokevirtual("cljl/Var", "get", "()Ljava/lang/Object;", 1, 1);
            checkcast("cljl/Fn");
        }

        private void emitObjectArray(Node[] nodes) {
            iconst(nodes.length);
            anewarray("java/lang/Object");
            for (int i = 0; i < nodes.length; i++) {
                dup();
                iconst(i);
                emitBoxed(nodes[i]);
                aastore();
            }
        }

        private void emitObjectArrayOrEmpty(Node[] nodes) {
            if (nodes.length == 0) {
                invokestatic("cljl/BytecodeJIT", "emptyArray", "()[Ljava/lang/Object;", 0, 1);
            } else {
                emitObjectArray(nodes);
            }
        }

        private void emitCapturedSlots(int[] parentSlots) {
            iconst(parentSlots.length);
            anewarray("java/lang/Object");
            for (int i = 0; i < parentSlots.length; i++) {
                dup();
                iconst(i);
                ValType st = layout.slotTypes[parentSlots[i]];
                loadLogicalSlot(parentSlots[i]);
                boxValue(st);
                aastore();
            }
        }

        void finish() {
            byte[] codeBytes = code.toByteArray();
            for (LoopTarget lt : loopTargets) {
                patchLabel(codeBytes, lt.start);
            }
            for (Label l : allLabels) {
                patchLabel(codeBytes, l);
            }

            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(codeBytes.length + 32 + exceptionTable.size() * 8);
                DataOutputStream out = new DataOutputStream(baos);
                out.writeShort(accessFlags);
                out.writeShort(nameIdx);
                out.writeShort(descIdx);
                out.writeShort(1);

                out.writeShort(ce.codeAttrNameIdx);
                out.writeInt(12 + codeBytes.length + exceptionTable.size() * 8);
                out.writeShort(Math.max(maxStack, 6));
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
                out.writeShort(0);
                out.flush();
                ce.methodInfos.add(baos.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

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
