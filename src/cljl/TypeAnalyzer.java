package cljl;

import cljl.Compiler.*;
import cljl.lang.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Sound forward dataflow and fixpoint type analyzer for ClojureLite ASTs.
 *
 * Infers ValType for every AST node and local slot, propagates :tag hints,
 * resolves static/instance Java interop targets when types are statically known,
 * and discovers profitable unboxed primitive method specializations.
 */
public final class TypeAnalyzer {

    public static final class AnalysisResult {
        public final ValType[] entryParamTypes;
        public final ValType[] slotTypes;
        public final ValType returnType;
        public final boolean recurIncompatible;
        public final IdentityHashMap<Node, ValType> nodeTypes = new IdentityHashMap<>();
        public final IdentityHashMap<Node, Method> resolvedMethods = new IdentityHashMap<>();
        public final IdentityHashMap<Node, Constructor<?>> resolvedCtors = new IdentityHashMap<>();
        public final IdentityHashMap<Node, Field> resolvedFields = new IdentityHashMap<>();

        AnalysisResult(ValType[] entryParamTypes, ValType[] slotTypes, ValType returnType, boolean recurIncompatible) {
            this.entryParamTypes = entryParamTypes;
            this.slotTypes = slotTypes;
            this.returnType = returnType;
            this.recurIncompatible = recurIncompatible;
        }

        public ValType typeOf(Node n) {
            ValType t = nodeTypes.get(n);
            return t != null ? t : ValType.ANY;
        }
    }

    public static final class PrimSpec {
        public final ValType[] paramTypes;
        public final AnalysisResult analysis;
        public final String methodName;
        public final String descriptor;

        PrimSpec(ValType[] paramTypes, AnalysisResult analysis) {
            this.paramTypes = paramTypes;
            this.analysis = analysis;
            StringBuilder pChars = new StringBuilder();
            StringBuilder desc = new StringBuilder("(");
            for (ValType pt : paramTypes) {
                char c = pt.sigChar();
                pChars.append(c);
                desc.append(jvmTypeDesc(c));
            }
            char rChar = analysis.returnType.sigChar();
            desc.append(')').append(jvmTypeDesc(rChar));
            this.methodName = "invokePrim_" + pChars + "_" + rChar;
            this.descriptor = desc.toString();
        }

        private static String jvmTypeDesc(char sigChar) {
            return switch (sigChar) {
                case 'J' -> "J";
                case 'D' -> "D";
                case 'Z' -> "Z";
                default -> "Ljava/lang/Object;";
            };
        }
    }

    private final FnMethod method;
    private final CompiledFn ownerFn;
    private final Var ownerVar;
    private final ValType[] entryParamTypes;
    private final ValType[] slotTypes;
    private final IdentityHashMap<Node, ValType> nodeTypes = new IdentityHashMap<>();
    private final IdentityHashMap<Node, Method> resolvedMethods = new IdentityHashMap<>();
    private final IdentityHashMap<Node, Constructor<?>> resolvedCtors = new IdentityHashMap<>();
    private final IdentityHashMap<Node, Field> resolvedFields = new IdentityHashMap<>();

    private final Deque<RecurFrame> recurStack = new ArrayDeque<>();
    private ValType selfReturnType = ValType.BOTTOM;
    private boolean selfCallObserved = false;
    private boolean changed = false;
    private boolean recurIncompatible = false;

    private static final class RecurFrame {
        final int[] slots;
        final boolean isFnFrame;
        final ValType[] accumTypes;

        RecurFrame(int[] slots, boolean isFnFrame) {
            this.slots = slots;
            this.isFnFrame = isFnFrame;
            this.accumTypes = new ValType[slots.length];
            Arrays.fill(this.accumTypes, ValType.BOTTOM);
        }
    }

    private TypeAnalyzer(FnMethod method, CompiledFn ownerFn, ValType[] entryParamTypes) {
        this.method = method;
        this.ownerFn = ownerFn;
        this.ownerVar = ownerFn != null ? ownerFn.getOwnerVar() : null;
        this.entryParamTypes = entryParamTypes;
        this.slotTypes = new ValType[method.maxSlots];
        Arrays.fill(this.slotTypes, ValType.BOTTOM);

        if (method.selfSlot != -1) {
            this.slotTypes[method.selfSlot] = ValType.ref(Fn.class, true);
        }
        for (int s : method.capturedLocalSlots) {
            ValType hint = hintForSlot(s);
            this.slotTypes[s] = hint != ValType.ANY ? hint : ValType.ANY;
        }
        for (int i = 0; i < method.paramSlots.length; i++) {
            int s = method.paramSlots[i];
            ValType ep = (entryParamTypes != null && i < entryParamTypes.length) ? entryParamTypes[i] : ValType.ANY;
            ValType hint = hintForSlot(s);
            this.slotTypes[s] = ValType.narrow(ep, hint);
        }
    }

    private ValType hintForSlot(int slot) {
        if (method.slotHints != null && slot >= 0 && slot < method.slotHints.length) {
            return method.slotHints[slot];
        }
        return ValType.ANY;
    }

    public static AnalysisResult analyze(FnMethod method, CompiledFn ownerFn, ValType[] entryParamTypes) {
        TypeAnalyzer ta = new TypeAnalyzer(method, ownerFn, entryParamTypes);
        return ta.solve();
    }

    public static AnalysisResult analyzeGeneric(FnMethod method, CompiledFn ownerFn) {
        int n = method.paramSlots.length;
        ValType[] entry = new ValType[n];
        for (int i = 0; i < n; i++) {
            ValType h = (method.paramHints != null && i < method.paramHints.length) ? method.paramHints[i] : ValType.ANY;
            entry[i] = h.isPrimitive() ? h : ValType.ANY;
        }
        return analyze(method, ownerFn, entry);
    }

    public static List<PrimSpec> discoverPrimSpecs(FnMethod method, CompiledFn ownerFn) {
        if (method.isVariadic || method.fixedArity == 0 || method.fixedArity > 3) {
            return Collections.emptyList();
        }
        int arity = method.fixedArity;
        boolean[] numericUse = new boolean[arity];
        boolean[] doubleUse = new boolean[arity];
        Map<Integer, Integer> paramIndexBySlot = new HashMap<>();
        for (int i = 0; i < arity; i++) {
            paramIndexBySlot.put(method.paramSlots[i], i);
        }
        scanParamUses(method.body, paramIndexBySlot, numericUse, doubleUse);

        boolean anyHint = false;
        boolean anyNumeric = false;
        boolean anyDouble = false;
        for (int i = 0; i < arity; i++) {
            ValType ph = (method.paramHints != null && i < method.paramHints.length) ? method.paramHints[i] : ValType.ANY;
            if (ph.isPrimitive()) anyHint = true;
            if (ph == ValType.DOUBLE) anyDouble = true;
            if (numericUse[i]) anyNumeric = true;
            if (doubleUse[i]) anyDouble = true;
        }
        if (!anyHint && !anyNumeric && !anyDouble) {
            return Collections.emptyList();
        }

        List<ValType[]> candidates = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();

        // Candidate 1: exact hints (if any hint is primitive)
        if (anyHint) {
            ValType[] cand = new ValType[arity];
            for (int i = 0; i < arity; i++) {
                ValType ph = method.paramHints[i];
                cand[i] = ph.isPrimitive() ? ph : ValType.ANY;
            }
            addCandidate(candidates, seenKeys, cand);
        }

        // Candidate 2: LONG for numeric params (respecting explicit hints)
        if (anyNumeric) {
            ValType[] cand = new ValType[arity];
            for (int i = 0; i < arity; i++) {
                ValType ph = (method.paramHints != null && i < method.paramHints.length) ? method.paramHints[i] : ValType.ANY;
                if (ph.isPrimitive()) {
                    cand[i] = ph;
                } else if (numericUse[i]) {
                    cand[i] = ValType.LONG;
                } else {
                    cand[i] = ValType.ANY;
                }
            }
            addCandidate(candidates, seenKeys, cand);
        }

        // Candidate 3: DOUBLE for double-used params (or all numeric params if double ops present)
        if (anyDouble) {
            ValType[] cand = new ValType[arity];
            for (int i = 0; i < arity; i++) {
                ValType ph = (method.paramHints != null && i < method.paramHints.length) ? method.paramHints[i] : ValType.ANY;
                if (ph.isPrimitive()) {
                    cand[i] = ph;
                } else if (doubleUse[i] || numericUse[i]) {
                    cand[i] = ValType.DOUBLE;
                } else {
                    cand[i] = ValType.ANY;
                }
            }
            addCandidate(candidates, seenKeys, cand);
        }

        List<PrimSpec> accepted = new ArrayList<>();
        for (ValType[] cand : candidates) {
            boolean hasPrim = false;
            for (ValType t : cand) if (t.isPrimitive()) hasPrim = true;
            if (!hasPrim) continue;

            AnalysisResult res = analyze(method, ownerFn, cand);
            if (res.recurIncompatible) continue;
            if (res.returnType.isPrimitive() || hasUnboxedWork(method.body, res, cand)) {
                accepted.add(new PrimSpec(cand, res));
            }
        }
        return accepted;
    }

    private static void addCandidate(List<ValType[]> list, Set<String> seen, ValType[] cand) {
        StringBuilder sb = new StringBuilder();
        for (ValType t : cand) sb.append(t.sigChar());
        if (seen.add(sb.toString())) {
            list.add(cand);
        }
    }

    private static boolean hasUnboxedWork(Node n, AnalysisResult res, ValType[] cand) {
        if (res.returnType.isPrimitive()) return true;
        return containsUnboxedWork(n, res);
    }

    private static boolean containsUnboxedWork(Node n, AnalysisResult res) {
        if (n == null) return false;
        if (n instanceof LoopNode || n instanceof BitOp2Node || n instanceof BitNotNode
                || n instanceof UncheckedAdd2Node || n instanceof UncheckedSub2Node || n instanceof UncheckedMul2Node
                || n instanceof UncheckedIncNode || n instanceof UncheckedDecNode || n instanceof UncheckedNegateNode) {
            return true;
        }
        if (n instanceof Add2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive()
                || containsUnboxedWork(a.a, res) || containsUnboxedWork(a.b, res);
        if (n instanceof Sub2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive()
                || containsUnboxedWork(a.a, res) || containsUnboxedWork(a.b, res);
        if (n instanceof Mul2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive()
                || containsUnboxedWork(a.a, res) || containsUnboxedWork(a.b, res);
        if (n instanceof Div2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive()
                || containsUnboxedWork(a.a, res) || containsUnboxedWork(a.b, res);
        if (n instanceof IncNode a) return res.typeOf(a.a).isPrimitive() || containsUnboxedWork(a.a, res);
        if (n instanceof DecNode a) return res.typeOf(a.a).isPrimitive() || containsUnboxedWork(a.a, res);
        if (n instanceof NegateNode a) return res.typeOf(a.a).isPrimitive() || containsUnboxedWork(a.a, res);
        if (n instanceof Lt2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive();
        if (n instanceof Lte2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive();
        if (n instanceof Gt2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive();
        if (n instanceof Gte2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive();
        if (n instanceof NumEq2Node a) return res.typeOf(a.a).isPrimitive() || res.typeOf(a.b).isPrimitive();
        if (n instanceof NthNode a) return res.typeOf(a.idx).isPrimitive();
        if (n instanceof AlengthNode a) return res.typeOf(a.arr).isRef() || containsUnboxedWork(a.arr, res);
        if (n instanceof AgetNode a) return res.typeOf(a).isPrimitive()
                || containsUnboxedWork(a.arr, res) || containsUnboxedWork(a.idx, res);
        if (n instanceof AsetNode a) return res.typeOf(a.val).isPrimitive()
                || containsUnboxedWork(a.arr, res) || containsUnboxedWork(a.idx, res)
                || containsUnboxedWork(a.val, res);
        if (n instanceof IfNode iff) return containsUnboxedWork(iff.test, res)
                || containsUnboxedWork(iff.thenNode, res) || containsUnboxedWork(iff.elseNode, res);
        if (n instanceof DoNode dn) {
            for (Node c : dn.nodes) if (containsUnboxedWork(c, res)) return true;
            return false;
        }
        if (n instanceof LetNode ln) {
            for (Node init : ln.inits) if (containsUnboxedWork(init, res)) return true;
            return containsUnboxedWork(ln.body, res);
        }
        if (n instanceof LockingNode ln) return containsUnboxedWork(ln.body, res);
        if (n instanceof CaseNode cn) {
            for (Node b : cn.table.values()) if (containsUnboxedWork(b, res)) return true;
            return containsUnboxedWork(cn.defaultNode, res);
        }
        if (n instanceof TryNode tn) {
            if (containsUnboxedWork(tn.body, res)) return true;
            for (CatchClause cc : tn.catches) if (containsUnboxedWork(cc.handler(), res)) return true;
            return containsUnboxedWork(tn.finallyNode, res);
        }
        if (n instanceof StaticMethodNode sm) {
            Method m = res.resolvedMethods.get(sm);
            if (m != null) {
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt.isPrimitive()) return true;
                }
            }
            for (Node a : sm.args) if (containsUnboxedWork(a, res)) return true;
            return false;
        }
        if (n instanceof InstanceMethodNode im) {
            Method m = res.resolvedMethods.get(im);
            if (m != null) {
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt.isPrimitive()) return true;
                }
            }
            if (containsUnboxedWork(im.target, res)) return true;
            for (Node a : im.args) if (containsUnboxedWork(a, res)) return true;
            return false;
        }
        return false;
    }

    private static void scanParamUses(Node n, Map<Integer, Integer> paramIdx, boolean[] numericUse, boolean[] doubleUse) {
        scanParamUses(n, paramIdx, numericUse, doubleUse, false);
    }

    private static void scanParamUses(Node n, Map<Integer, Integer> paramIdx, boolean[] numericUse, boolean[] doubleUse, boolean inDoubleCtx) {
        if (n == null) return;
        if (n instanceof Add2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Sub2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Mul2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Div2Node a) {
            markParamIfLocal(a.a, paramIdx, doubleUse);
            markParamIfLocal(a.b, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, true);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, true);
        } else if (n instanceof UncheckedAdd2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof UncheckedSub2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof UncheckedMul2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof IncNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            if (inDoubleCtx) markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof DecNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            if (inDoubleCtx) markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof NegateNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            if (inDoubleCtx) markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof UncheckedIncNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof UncheckedDecNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof UncheckedNegateNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof Rem2Node a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            markParamIfLocal(a.b, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Quot2Node a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            markParamIfLocal(a.b, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof BitOp2Node a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            markParamIfLocal(a.b, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof BitNotNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof Lt2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Lte2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Gt2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Gte2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof NumEq2Node a) {
            markNumericExpr(a.a, a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Equiv2Node a) {
            if (a.a instanceof ConstNode c && c.isLong) markParamIfLocal(a.b, paramIdx, numericUse);
            if (a.b instanceof ConstNode c && c.isLong) markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
            scanParamUses(a.b, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof ZeroPNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            if (inDoubleCtx) markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof PosPNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            if (inDoubleCtx) markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof NegPNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            if (inDoubleCtx) markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof EvenPNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof OddPNode a) {
            markParamIfLocal(a.a, paramIdx, numericUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof NthNode a) {
            markParamIfLocal(a.idx, paramIdx, numericUse);
            scanParamUses(a.coll, paramIdx, numericUse, doubleUse, false);
            scanParamUses(a.idx, paramIdx, numericUse, doubleUse, false);
            scanParamUses(a.notFound, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof StaticMethodNode sm) {
            boolean dbl = sm.clazz == Math.class && sm.args.length > 0;
            if (dbl) {
                for (Node arg : sm.args) {
                    markParamIfLocal(arg, paramIdx, doubleUse);
                }
            }
            for (Node arg : sm.args) scanParamUses(arg, paramIdx, numericUse, doubleUse, inDoubleCtx || dbl);
        } else if (n instanceof IfNode iff) {
            scanParamUses(iff.test, paramIdx, numericUse, doubleUse, false);
            scanParamUses(iff.thenNode, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(iff.elseNode, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof DoNode dn) {
            for (int i = 0; i < dn.nodes.length; i++) {
                scanParamUses(dn.nodes[i], paramIdx, numericUse, doubleUse, (i == dn.nodes.length - 1) && inDoubleCtx);
            }
        } else if (n instanceof LetNode ln) {
            for (int i = 0; i < ln.inits.length; i++) {
                scanParamUses(ln.inits[i], paramIdx, numericUse, doubleUse, inDoubleCtx);
                if (ln.inits[i] instanceof LocalNode lnode && paramIdx.containsKey(lnode.slot)) {
                    paramIdx.putIfAbsent(ln.slots[i], paramIdx.get(lnode.slot));
                }
            }
            scanParamUses(ln.body, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof LoopNode lp) {
            for (int i = 0; i < lp.inits.length; i++) {
                scanParamUses(lp.inits[i], paramIdx, numericUse, doubleUse, inDoubleCtx);
                if (lp.inits[i] instanceof LocalNode lnode && paramIdx.containsKey(lnode.slot)) {
                    paramIdx.putIfAbsent(lp.slots[i], paramIdx.get(lnode.slot));
                }
            }
            scanParamUses(lp.body, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Recur1Node r1) {
            scanParamUses(r1.expr0, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Recur2Node r2) {
            scanParamUses(r2.expr0, paramIdx, numericUse, doubleUse, inDoubleCtx);
            scanParamUses(r2.expr1, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof RecurNNode rn) {
            for (Node e : rn.exprs) scanParamUses(e, paramIdx, numericUse, doubleUse, inDoubleCtx);
        } else if (n instanceof Call1Node c) {
            scanParamUses(c.fn, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof Call2Node c) {
            scanParamUses(c.fn, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.a, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.b, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof Call3Node c) {
            scanParamUses(c.fn, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.a, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.b, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.c, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof NotNode a) {
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof LongCastNode a) {
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof DoubleCastNode a) {
            markParamIfLocal(a.a, paramIdx, doubleUse);
            scanParamUses(a.a, paramIdx, numericUse, doubleUse, true);
        } else if (n instanceof AlengthNode a) {
            scanParamUses(a.arr, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof AgetNode a) {
            scanParamUses(a.arr, paramIdx, numericUse, doubleUse, false);
            markParamIfLocal(a.idx, paramIdx, numericUse);
            scanParamUses(a.idx, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof AsetNode a) {
            scanParamUses(a.arr, paramIdx, numericUse, doubleUse, false);
            markParamIfLocal(a.idx, paramIdx, numericUse);
            scanParamUses(a.idx, paramIdx, numericUse, doubleUse, false);
            scanParamUses(a.val, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof VarCall1Node c) {
            scanParamUses(c.a, paramIdx, numericUse, doubleUse, false);
        } else if (n instanceof VarCall2Node c) {
            scanParamUses(c.a, paramIdx, numericUse, doubleUse, false);
            scanParamUses(c.b, paramIdx, numericUse, doubleUse, false);
        }
    }

    private static void markNumericExpr(Node a, Node b, Map<Integer, Integer> paramIdx, boolean[] numericUse, boolean[] doubleUse, boolean inDoubleCtx) {
        markParamIfLocal(a, paramIdx, numericUse);
        markParamIfLocal(b, paramIdx, numericUse);
        boolean dbl = inDoubleCtx || (a instanceof ConstNode c1 && c1.isDouble) || (b instanceof ConstNode c2 && c2.isDouble);
        if (dbl) {
            markParamIfLocal(a, paramIdx, doubleUse);
            markParamIfLocal(b, paramIdx, doubleUse);
        }
    }

    private static void markParamIfLocal(Node n, Map<Integer, Integer> paramIdx, boolean[] flags) {
        if (n instanceof LocalNode ln) {
            Integer idx = paramIdx.get(ln.slot);
            if (idx != null) flags[idx] = true;
        }
    }

    private AnalysisResult solve() {
        ValType ret = ValType.ANY;
        for (int iter = 0; iter < 16; iter++) {
            changed = false;
            recurIncompatible = false;
            recurStack.clear();
            RecurFrame fnFrame = new RecurFrame(method.paramSlots, true);
            for (int i = 0; i < method.paramSlots.length; i++) {
                fnFrame.accumTypes[i] = slotTypes[method.paramSlots[i]];
            }
            recurStack.push(fnFrame);
            ret = analyzeNode(method.body);
            recurStack.pop();

            if (method.returnHint != null && method.returnHint != ValType.ANY) {
                ret = ValType.narrow(ret, method.returnHint);
            }
            if (!ret.equals(selfReturnType)) {
                selfReturnType = ret;
                changed = true;
            }
            if (!changed) break;
        }

        // If self-recursion never reached a base case (e.g. infinite recursion), avoid leaving BOTTOM
        if (selfCallObserved && selfReturnType == ValType.BOTTOM) {
            selfReturnType = ValType.ANY;
            recurStack.clear();
            RecurFrame fnFrame = new RecurFrame(method.paramSlots, true);
            for (int i = 0; i < method.paramSlots.length; i++) {
                fnFrame.accumTypes[i] = slotTypes[method.paramSlots[i]];
            }
            recurStack.push(fnFrame);
            ret = analyzeNode(method.body);
            recurStack.pop();
            if (method.returnHint != null && method.returnHint != ValType.ANY) {
                ret = ValType.narrow(ret, method.returnHint);
            }
        }

        // Clamp any remaining unreachable/unvisited slots to ANY for safety
        for (int i = 0; i < slotTypes.length; i++) {
            if (slotTypes[i] == ValType.BOTTOM) {
                slotTypes[i] = ValType.ANY;
            }
        }
        if (ret == ValType.BOTTOM) {
            ret = ValType.ANY;
        }

        AnalysisResult res = new AnalysisResult(entryParamTypes, slotTypes, ret, recurIncompatible);
        res.nodeTypes.putAll(nodeTypes);
        res.resolvedMethods.putAll(resolvedMethods);
        res.resolvedCtors.putAll(resolvedCtors);
        res.resolvedFields.putAll(resolvedFields);
        return res;
    }

    private ValType record(Node n, ValType t) {
        nodeTypes.put(n, t);
        return t;
    }

    private ValType analyzeNode(Node n) {
        if (n == null) return ValType.NIL;
        if (n instanceof ConstNode cn) {
            return record(n, ValType.fromConstant(cn.val));
        }
        if (n instanceof LocalNode ln) {
            ValType base = slotTypes[ln.slot];
            ValType narrowed = ValType.narrow(base, ln.hint);
            return record(n, narrowed);
        }
        if (n instanceof VarRefNode vr) {
            return record(n, vr.hint != null ? vr.hint : ValType.ANY);
        }
        if (n instanceof DefNode dn) {
            analyzeNode(dn.init);
            return record(n, ValType.ref(Var.class, true));
        }
        if (n instanceof DefMacroNode dm) {
            analyzeNode(dm.fnNode);
            return record(n, ValType.ref(Var.class, true));
        }
        if (n instanceof FnNode) {
            return record(n, ValType.ref(CompiledFn.class, true));
        }
        if (n instanceof IfNode iff) {
            analyzeNode(iff.test);
            ValType tThen = analyzeNode(iff.thenNode);
            ValType tElse = analyzeNode(iff.elseNode);
            return record(n, ValType.join(tThen, tElse));
        }
        if (n instanceof DoNode dn) {
            if (dn.nodes.length == 0) return record(n, ValType.NIL);
            ValType last = ValType.NIL;
            boolean sawBottom = false;
            for (Node c : dn.nodes) {
                last = analyzeNode(c);
                if (last == ValType.BOTTOM) sawBottom = true;
            }
            return record(n, sawBottom ? ValType.BOTTOM : last);
        }
        if (n instanceof LetNode ln) {
            for (int i = 0; i < ln.slots.length; i++) {
                ValType initT = analyzeNode(ln.inits[i]);
                ValType narrowed = ValType.narrow(initT, hintForSlot(ln.slots[i]));
                if (!narrowed.equals(slotTypes[ln.slots[i]])) {
                    slotTypes[ln.slots[i]] = narrowed;
                    changed = true;
                }
            }
            return record(n, analyzeNode(ln.body));
        }
        if (n instanceof LoopNode lp) {
            ValType[] initTypes = new ValType[lp.slots.length];
            for (int i = 0; i < lp.slots.length; i++) {
                initTypes[i] = ValType.narrow(analyzeNode(lp.inits[i]), hintForSlot(lp.slots[i]));
                if (slotTypes[lp.slots[i]] == ValType.BOTTOM) {
                    slotTypes[lp.slots[i]] = initTypes[i];
                    changed = true;
                }
            }
            RecurFrame lf = new RecurFrame(lp.slots, false);
            for (int i = 0; i < lp.slots.length; i++) {
                lf.accumTypes[i] = initTypes[i];
            }
            recurStack.push(lf);
            ValType bodyT = analyzeNode(lp.body);
            recurStack.pop();

            for (int i = 0; i < lp.slots.length; i++) {
                ValType merged = ValType.narrow(lf.accumTypes[i], hintForSlot(lp.slots[i]));
                if (!merged.equals(slotTypes[lp.slots[i]])) {
                    slotTypes[lp.slots[i]] = merged;
                    changed = true;
                }
            }
            return record(n, bodyT);
        }
        if (n instanceof Recur0Node) {
            return record(n, ValType.BOTTOM);
        }
        if (n instanceof Recur1Node r1) {
            ValType t0 = analyzeNode(r1.expr0);
            handleRecurArgs(new ValType[]{t0});
            return record(n, ValType.BOTTOM);
        }
        if (n instanceof Recur2Node r2) {
            ValType t0 = analyzeNode(r2.expr0);
            ValType t1 = analyzeNode(r2.expr1);
            handleRecurArgs(new ValType[]{t0, t1});
            return record(n, ValType.BOTTOM);
        }
        if (n instanceof RecurNNode rn) {
            ValType[] ts = new ValType[rn.exprs.length];
            for (int i = 0; i < rn.exprs.length; i++) {
                ts[i] = analyzeNode(rn.exprs[i]);
            }
            handleRecurArgs(ts);
            return record(n, ValType.BOTTOM);
        }
        if (n instanceof ThrowNode tn) {
            analyzeNode(tn.expr);
            return record(n, ValType.BOTTOM);
        }
        if (n instanceof VectorNode vn) {
            for (Node e : vn.elems) analyzeNode(e);
            return record(n, ValType.ref(PersistentVector.class, true));
        }
        if (n instanceof MapNode mn) {
            for (Node e : mn.kvs) analyzeNode(e);
            return record(n, ValType.ref(PersistentHashMap.class, true));
        }
        if (n instanceof SetNode sn) {
            for (Node e : sn.elems) analyzeNode(e);
            return record(n, ValType.ref(PersistentHashSet.class, true));
        }
        if (n instanceof CaseNode cn) {
            analyzeNode(cn.expr);
            ValType joined = ValType.BOTTOM;
            for (Node b : cn.table.values()) {
                joined = ValType.join(joined, analyzeNode(b));
            }
            if (cn.hasDefault) {
                joined = ValType.join(joined, analyzeNode(cn.defaultNode));
            }
            return record(n, joined);
        }
        if (n instanceof MonitorNode mn) {
            analyzeNode(mn.target);
            return record(n, ValType.NIL);
        }
        if (n instanceof LockingNode ln) {
            analyzeNode(ln.target);
            return record(n, analyzeNode(ln.body));
        }
        if (n instanceof TryNode tn) {
            ValType bt = analyzeNode(tn.body);
            for (CatchClause cc : tn.catches) {
                slotTypes[cc.slot()] = ValType.ref(cc.exClass(), true);
                bt = ValType.join(bt, analyzeNode(cc.handler()));
            }
            if (tn.finallyNode != null) analyzeNode(tn.finallyNode);
            return record(n, bt);
        }
        if (n instanceof SetVarNode sv) {
            analyzeNode(sv.val);
            return record(n, ValType.ANY);
        }
        if (n instanceof SetFieldNode sf) {
            analyzeNode(sf.target);
            analyzeNode(sf.val);
            return record(n, ValType.ANY);
        }
        if (n instanceof SetStaticFieldNode ssf) {
            analyzeNode(ssf.val);
            return record(n, ValType.ANY);
        }

        // Calls
        if (n instanceof VarCall0Node c) {
            return record(n, checkSelfVarCall(c.v, EMPTY_VALS));
        }
        if (n instanceof VarCall1Node c) {
            ValType t0 = analyzeNode(c.a);
            ValType resT = checkSelfVarCall(c.v, new ValType[]{t0});
            if (resT == ValType.ANY) resT = coreArrayVarType(c.v);
            return record(n, resT);
        }
        if (n instanceof VarCall2Node c) {
            ValType t0 = analyzeNode(c.a);
            ValType t1 = analyzeNode(c.b);
            ValType resT = checkSelfVarCall(c.v, new ValType[]{t0, t1});
            if (resT == ValType.ANY) resT = coreArrayFactoryVarType(c.v);
            return record(n, resT);
        }
        if (n instanceof VarCall3Node c) {
            ValType t0 = analyzeNode(c.a);
            ValType t1 = analyzeNode(c.b);
            ValType t2 = analyzeNode(c.c);
            return record(n, checkSelfVarCall(c.v, new ValType[]{t0, t1, t2}));
        }
        if (n instanceof VarCall4Node c) {
            ValType t0 = analyzeNode(c.a);
            ValType t1 = analyzeNode(c.b);
            ValType t2 = analyzeNode(c.c);
            ValType t3 = analyzeNode(c.d);
            return record(n, checkSelfVarCall(c.v, new ValType[]{t0, t1, t2, t3}));
        }
        if (n instanceof VarCallNNode c) {
            for (Node a : c.args) analyzeNode(a);
            return record(n, ValType.ANY);
        }
        if (n instanceof Call0Node c) {
            analyzeNode(c.fn);
            return record(n, checkSelfLocalCall(c.fn, EMPTY_VALS));
        }
        if (n instanceof Call1Node c) {
            analyzeNode(c.fn);
            ValType t0 = analyzeNode(c.a);
            return record(n, checkSelfLocalCall(c.fn, new ValType[]{t0}));
        }
        if (n instanceof Call2Node c) {
            analyzeNode(c.fn);
            ValType t0 = analyzeNode(c.a);
            ValType t1 = analyzeNode(c.b);
            return record(n, checkSelfLocalCall(c.fn, new ValType[]{t0, t1}));
        }
        if (n instanceof Call3Node c) {
            analyzeNode(c.fn);
            ValType t0 = analyzeNode(c.a);
            ValType t1 = analyzeNode(c.b);
            ValType t2 = analyzeNode(c.c);
            return record(n, checkSelfLocalCall(c.fn, new ValType[]{t0, t1, t2}));
        }
        if (n instanceof Call4Node c) {
            analyzeNode(c.fn);
            ValType t0 = analyzeNode(c.a);
            ValType t1 = analyzeNode(c.b);
            ValType t2 = analyzeNode(c.c);
            ValType t3 = analyzeNode(c.d);
            return record(n, checkSelfLocalCall(c.fn, new ValType[]{t0, t1, t2, t3}));
        }
        if (n instanceof CallNNode c) {
            analyzeNode(c.fn);
            for (Node a : c.args) analyzeNode(a);
            return record(n, ValType.ANY);
        }

        // Arithmetic intrinsics
        if (n instanceof Add2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof Sub2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof Mul2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof Div2Node a) {
            ValType ta = analyzeNode(a.a);
            ValType tb = analyzeNode(a.b);
            if (ta == ValType.BOTTOM || tb == ValType.BOTTOM) return record(n, ValType.BOTTOM);
            if ((ta == ValType.DOUBLE || ta == ValType.LONG) && (tb == ValType.DOUBLE || tb == ValType.LONG)
                    && (ta == ValType.DOUBLE || tb == ValType.DOUBLE)) {
                return record(n, ValType.DOUBLE);
            }
            return record(n, ValType.NUMBER);
        }
        if (n instanceof UncheckedAdd2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof UncheckedSub2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof UncheckedMul2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof Rem2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));
        if (n instanceof Quot2Node a) return record(n, arithBinary(analyzeNode(a.a), analyzeNode(a.b)));

        if (n instanceof NegateNode a) return record(n, arithUnary(analyzeNode(a.a)));
        if (n instanceof IncNode a) return record(n, arithUnary(analyzeNode(a.a)));
        if (n instanceof DecNode a) return record(n, arithUnary(analyzeNode(a.a)));
        if (n instanceof UncheckedIncNode a) return record(n, arithUnary(analyzeNode(a.a)));
        if (n instanceof UncheckedDecNode a) return record(n, arithUnary(analyzeNode(a.a)));
        if (n instanceof UncheckedNegateNode a) return record(n, arithUnary(analyzeNode(a.a)));

        if (n instanceof BitOp2Node a) {
            analyzeNode(a.a);
            analyzeNode(a.b);
            return record(n, ValType.LONG);
        }
        if (n instanceof BitNotNode a) {
            analyzeNode(a.a);
            return record(n, ValType.LONG);
        }
        if (n instanceof LongCastNode a) {
            analyzeNode(a.a);
            return record(n, ValType.LONG);
        }
        if (n instanceof DoubleCastNode a) {
            analyzeNode(a.a);
            return record(n, ValType.DOUBLE);
        }
        if (n instanceof CountNode a) {
            analyzeNode(a.coll);
            return record(n, ValType.LONG);
        }
        if (n instanceof AlengthNode a) {
            analyzeNode(a.arr);
            return record(n, ValType.LONG);
        }
        if (n instanceof AgetNode a) {
            ValType at = analyzeNode(a.arr);
            analyzeNode(a.idx);
            return record(n, arrayElemType(at, ValType.ANY));
        }
        if (n instanceof AsetNode a) {
            ValType at = analyzeNode(a.arr);
            analyzeNode(a.idx);
            ValType vt = analyzeNode(a.val);
            return record(n, arrayElemType(at, vt));
        }

        // Boolean intrinsics
        if (n instanceof Lt2Node a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof Lte2Node a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof Gt2Node a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof Gte2Node a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof Equiv2Node a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof NumEq2Node a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof IdenticalNode a) { analyzeNode(a.a); analyzeNode(a.b); return record(n, ValType.BOOL); }
        if (n instanceof ZeroPNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof PosPNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof NegPNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof NilPNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof NotNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof EvenPNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof OddPNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }
        if (n instanceof TypePredNode a) { analyzeNode(a.a); return record(n, ValType.BOOL); }

        // Collection intrinsics
        if (n instanceof NthNode a) {
            analyzeNode(a.coll);
            analyzeNode(a.idx);
            if (a.notFound != null) analyzeNode(a.notFound);
            return record(n, ValType.ANY);
        }
        if (n instanceof GetNode a) {
            analyzeNode(a.coll);
            analyzeNode(a.key);
            if (a.notFound != null) analyzeNode(a.notFound);
            return record(n, ValType.ANY);
        }
        if (n instanceof TransientNode a) {
            ValType ct = analyzeNode(a.coll);
            if (ct.isRef()) {
                if (ct.refClass == PersistentVector.class) {
                    return record(n, ValType.ref(PersistentVector.TransientVector.class, true));
                }
                if (ct.refClass == PersistentHashMap.class) {
                    return record(n, ValType.ref(PersistentHashMap.TransientHashMap.class, true));
                }
                if (ct.refClass == PersistentHashSet.class) {
                    return record(n, ValType.ref(PersistentHashSet.TransientHashSet.class, true));
                }
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof PersistentBangNode a) {
            ValType tt = analyzeNode(a.target);
            if (tt.isRef()) {
                if (tt.refClass == PersistentVector.TransientVector.class) {
                    return record(n, ValType.ref(PersistentVector.class, true));
                }
                if (tt.refClass == PersistentHashMap.TransientHashMap.class) {
                    return record(n, ValType.ref(PersistentHashMap.class, true));
                }
                if (tt.refClass == PersistentHashSet.TransientHashSet.class) {
                    return record(n, ValType.ref(PersistentHashSet.class, true));
                }
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof ConjBangNode a) {
            ValType tt = analyzeNode(a.target);
            analyzeNode(a.val);
            if (tt.isRef() && (tt.refClass == PersistentVector.TransientVector.class
                    || tt.refClass == PersistentHashSet.TransientHashSet.class)) {
                return record(n, ValType.ref(tt.refClass, true));
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof AssocBangNode a) {
            ValType tt = analyzeNode(a.target);
            analyzeNode(a.key);
            analyzeNode(a.val);
            if (tt.isRef() && (tt.refClass == PersistentHashMap.TransientHashMap.class
                    || tt.refClass == PersistentVector.TransientVector.class)) {
                return record(n, ValType.ref(tt.refClass, true));
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof ConjNode a) {
            ValType ct = analyzeNode(a.coll);
            analyzeNode(a.val);
            if (ct.isRef() && (ct.refClass == PersistentVector.class
                    || ct.refClass == PersistentHashSet.class)) {
                return record(n, ValType.ref(ct.refClass, true));
            }
            if (ct.isRef() && IPersistentList.class.isAssignableFrom(ct.refClass)) {
                return record(n, ValType.ref(PersistentList.class, true));
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof PopNode a) {
            ValType ct = analyzeNode(a.coll);
            if (ct.isRef() && ct.refClass == PersistentVector.class) {
                return record(n, ValType.ref(PersistentVector.class, true));
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof AssocNode a) {
            ValType ct = analyzeNode(a.coll);
            analyzeNode(a.key);
            analyzeNode(a.val);
            if (ct.isRef() && (ct.refClass == PersistentHashMap.class
                    || ct.refClass == PersistentVector.class
                    || ct.refClass == PersistentVector.SubVec.class)) {
                return record(n, ValType.ref(ct.refClass, true));
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof DissocNode a) {
            ValType ct = analyzeNode(a.coll);
            analyzeNode(a.key);
            if (ct.isRef() && (ct.refClass == PersistentHashMap.class
                    || ct.refClass == PersistentTreeMap.class)) {
                return record(n, ValType.ref(ct.refClass, true));
            }
            return record(n, ValType.ANY);
        }
        if (n instanceof FirstNode a) {
            analyzeNode(a.coll);
            return record(n, ValType.ANY);
        }
        if (n instanceof NextNode a) {
            analyzeNode(a.coll);
            return record(n, ValType.ref(ISeq.class, false));
        }
        if (n instanceof RestNode a) {
            analyzeNode(a.coll);
            return record(n, ValType.ref(ISeq.class, true));
        }
        if (n instanceof SeqNode a) {
            ValType ct = analyzeNode(a.coll);
            if (ct.isRef() && ct.nonNull && (ct.refClass == PersistentList.class || ct.refClass == Cons.class)) {
                return record(n, ValType.ref(ct.refClass, true));
            }
            return record(n, ValType.ref(ISeq.class, false));
        }

        // Java interop
        if (n instanceof NewNode nn) {
            ValType[] argTypes = analyzeArray(nn.args);
            Constructor<?> ctor = Interop.findPublicConstructorForTypes(nn.clazz, argTypes);
            if (ctor != null) resolvedCtors.put(nn, ctor);
            else resolvedCtors.remove(nn);
            return record(n, ValType.ref(nn.clazz, true));
        }
        if (n instanceof StaticFieldNode sf) {
            Field f = Interop.findPublicField(sf.clazz, sf.fieldName, true);
            if (f != null) {
                resolvedFields.put(sf, f);
                return record(n, ValType.fromJavaReturn(f.getType()));
            }
            resolvedFields.remove(sf);
            return record(n, ValType.ANY);
        }
        if (n instanceof StaticMethodNode sm) {
            ValType[] argTypes = analyzeArray(sm.args);
            Method m = Interop.findPublicMethodForTypes(sm.clazz, sm.methodName, argTypes, true);
            if (m != null) {
                resolvedMethods.put(sm, m);
                return record(n, ValType.fromJavaReturn(m.getReturnType()));
            }
            resolvedMethods.remove(sm);
            return record(n, ValType.ANY);
        }
        if (n instanceof InstanceFieldNode inf) {
            ValType tt = analyzeNode(inf.target);
            if (tt.isRef() && !PersistentHashMap.class.isAssignableFrom(tt.refClass)) {
                Field f = Interop.findPublicField(tt.refClass, inf.fieldName, false);
                if (f != null) {
                    resolvedFields.put(inf, f);
                    return record(n, ValType.fromJavaReturn(f.getType()));
                }
            }
            resolvedFields.remove(inf);
            return record(n, ValType.ANY);
        }
        if (n instanceof InstanceMethodNode im) {
            ValType tt = analyzeNode(im.target);
            ValType[] argTypes = analyzeArray(im.args);
            if (tt.isRef()) {
                Method m = Interop.findPublicMethodForTypes(tt.refClass, im.methodName, argTypes, false);
                if (m != null) {
                    resolvedMethods.put(im, m);
                    return record(n, ValType.fromJavaReturn(m.getReturnType()));
                }
            }
            resolvedMethods.remove(im);
            return record(n, ValType.ANY);
        }

        return record(n, ValType.ANY);
    }

    private static final ValType[] EMPTY_VALS = new ValType[0];

    private ValType[] analyzeArray(Node[] args) {
        if (args.length == 0) return EMPTY_VALS;
        ValType[] ts = new ValType[args.length];
        for (int i = 0; i < args.length; i++) {
            ts[i] = analyzeNode(args[i]);
        }
        return ts;
    }

    private void handleRecurArgs(ValType[] argTypes) {
        if (recurStack.isEmpty()) return;
        RecurFrame frame = recurStack.peek();
        if (frame.isFnFrame) {
            for (int i = 0; i < frame.slots.length && i < argTypes.length; i++) {
                int slot = frame.slots[i];
                ValType hint = hintForSlot(slot);
                ValType effArg = ValType.narrow(argTypes[i], hint);
                ValType cur = slotTypes[slot];
                ValType joined = ValType.narrow(ValType.join(cur, effArg), hint);
                if (!joined.equals(cur)) {
                    if (entryParamTypes != null && i < entryParamTypes.length && entryParamTypes[i].isPrimitive()) {
                        recurIncompatible = true;
                    }
                    slotTypes[slot] = joined;
                    changed = true;
                }
            }
        } else {
            for (int i = 0; i < frame.slots.length && i < argTypes.length; i++) {
                frame.accumTypes[i] = ValType.join(frame.accumTypes[i], argTypes[i]);
            }
        }
    }

    private ValType checkSelfLocalCall(Node fnNode, ValType[] argTypes) {
        if (method.selfSlot != -1 && fnNode instanceof LocalNode ln && ln.slot == method.selfSlot) {
            return matchSelfCall(argTypes);
        }
        return ValType.ANY;
    }

    private ValType checkSelfVarCall(Var v, ValType[] argTypes) {
        if (ownerVar != null && v == ownerVar) {
            return matchSelfCall(argTypes);
        }
        return ValType.ANY;
    }

    private ValType matchSelfCall(ValType[] argTypes) {
        if (method.isVariadic || argTypes.length != method.fixedArity) return ValType.ANY;
        boolean sawBottom = false;
        for (int i = 0; i < argTypes.length; i++) {
            ValType ep = (entryParamTypes != null && i < entryParamTypes.length) ? entryParamTypes[i] : ValType.ANY;
            if (ep == ValType.ANY) continue;
            ValType at = argTypes[i];
            if (at == ValType.BOTTOM) {
                sawBottom = true;
                continue;
            }
            if (!ep.equals(at)) return ValType.ANY;
        }
        selfCallObserved = true;
        return sawBottom ? ValType.BOTTOM : selfReturnType;
    }

    public boolean matchesEntryParams(ValType[] argTypes) {
        if (argTypes.length != method.fixedArity) return false;
        for (int i = 0; i < argTypes.length; i++) {
            ValType ep = (entryParamTypes != null && i < entryParamTypes.length) ? entryParamTypes[i] : ValType.ANY;
            if (ep == ValType.ANY) continue;
            if (!ep.equals(argTypes[i])) return false;
        }
        return true;
    }

    private static ValType arrayElemType(ValType arrType, ValType fallback) {
        if (arrType.kind == ValType.Kind.REF && arrType.refClass != null && arrType.refClass.isArray()) {
            Class<?> comp = arrType.refClass.getComponentType();
            if (comp == double.class || comp == float.class) return ValType.DOUBLE;
            if (comp == long.class || comp == int.class || comp == short.class || comp == byte.class) return ValType.LONG;
            if (comp == boolean.class) return ValType.BOOL;
            if (comp == char.class) return ValType.ref(Character.class, true);
            return ValType.ref(comp, false);
        }
        return fallback;
    }

    private static ValType coreArrayVarType(Var v) {
        if (v == null || v.ns != RT.CLOJURE_NS) return ValType.ANY;
        return switch (v.sym.name) {
            case "doubles", "double-array" -> ValType.ref(double[].class, true);
            case "longs", "long-array" -> ValType.ref(long[].class, true);
            case "ints", "int-array" -> ValType.ref(int[].class, true);
            case "bytes", "byte-array" -> ValType.ref(byte[].class, true);
            case "shorts", "short-array" -> ValType.ref(short[].class, true);
            case "floats", "float-array" -> ValType.ref(float[].class, true);
            case "chars", "char-array" -> ValType.ref(char[].class, true);
            case "booleans", "boolean-array" -> ValType.ref(boolean[].class, true);
            case "object-array", "to-array" -> ValType.ref(Object[].class, true);
            default -> ValType.ANY;
        };
    }

    private static ValType coreArrayFactoryVarType(Var v) {
        if (v == null || v.ns != RT.CLOJURE_NS) return ValType.ANY;
        return switch (v.sym.name) {
            case "double-array" -> ValType.ref(double[].class, true);
            case "long-array" -> ValType.ref(long[].class, true);
            case "int-array" -> ValType.ref(int[].class, true);
            case "byte-array" -> ValType.ref(byte[].class, true);
            case "short-array" -> ValType.ref(short[].class, true);
            case "float-array" -> ValType.ref(float[].class, true);
            case "char-array" -> ValType.ref(char[].class, true);
            case "boolean-array" -> ValType.ref(boolean[].class, true);
            default -> ValType.ANY;
        };
    }

    private static ValType arithUnary(ValType t) {
        if (t == ValType.BOTTOM) return ValType.BOTTOM;
        if (t == ValType.LONG) return ValType.LONG;
        if (t == ValType.DOUBLE) return ValType.DOUBLE;
        return ValType.NUMBER;
    }

    private static ValType arithBinary(ValType ta, ValType tb) {
        if (ta == ValType.BOTTOM || tb == ValType.BOTTOM) return ValType.BOTTOM;
        if (ta == ValType.LONG && tb == ValType.LONG) return ValType.LONG;
        if ((ta == ValType.DOUBLE || ta == ValType.LONG) && (tb == ValType.DOUBLE || tb == ValType.LONG)
                && (ta == ValType.DOUBLE || tb == ValType.DOUBLE)) {
            return ValType.DOUBLE;
        }
        return ValType.NUMBER;
    }
}
