package cljl;

import cljl.Compiler.*;
import cljl.lang.*;
import java.util.*;
import java.util.function.Function;

/**
 * ANF-style AST pre-pass for {@link BytecodeJIT} that lifts any {@link TryNode} or
 * {@link LockingNode} occurring in a non-empty-operand-stack expression position
 * (e.g. `(+ 1 (try ...))`, `[(try ...) (locking ...)]`, `(f a (try ...))`) into
 * fresh {@link LetNode} temporary local bindings, preserving strict left-to-right
 * evaluation order while guaranteeing an empty JVM operand stack at every try/locking
 * boundary.
 */
final class TryLifter {

    private int nextSlot;

    private TryLifter(int initialSlots) {
        this.nextSlot = initialSlots;
    }

    static FnMethod liftIfNeeded(FnMethod m) {
        if (m == null || m.body == null || !hasNonEmptyTryOrLocking(m.body, true)) {
            return m;
        }
        TryLifter lifter = new TryLifter(m.maxSlots);
        Node liftedBody = lifter.liftEmpty(m.body);
        if (liftedBody == m.body && lifter.nextSlot == m.maxSlots) {
            return m;
        }
        ValType[] newHints = m.slotHints;
        if (lifter.nextSlot > m.maxSlots) {
            newHints = new ValType[lifter.nextSlot];
            if (m.slotHints != null) {
                System.arraycopy(m.slotHints, 0, newHints, 0, Math.min(m.slotHints.length, newHints.length));
            }
            for (int i = m.maxSlots; i < lifter.nextSlot; i++) {
                newHints[i] = ValType.ANY;
            }
        }
        return new FnMethod(
                m.fixedArity, m.isVariadic, m.selfSlot, m.paramSlots,
                m.capturedParentSlots, m.capturedLocalSlots,
                lifter.nextSlot, m.hasRecur, liftedBody,
                m.paramHints, newHints, m.returnHint);
    }

    static boolean containsTryOrLocking(Node n) {
        return hasNonEmptyTryOrLocking(n, false);
    }

    private static boolean hasNonEmptyTryOrLocking(Node n, boolean stackEmpty) {
        if (n == null) return false;
        if (n instanceof TryNode tn) {
            if (!stackEmpty) return true;
            if (hasNonEmptyTryOrLocking(tn.body, true)) return true;
            for (CatchClause cc : tn.catches) {
                if (hasNonEmptyTryOrLocking(cc.handler(), true)) return true;
            }
            return hasNonEmptyTryOrLocking(tn.finallyNode, true);
        }
        if (n instanceof LockingNode ln) {
            if (!stackEmpty) return true;
            return hasNonEmptyTryOrLocking(ln.target, true) || hasNonEmptyTryOrLocking(ln.body, true);
        }
        if (n instanceof IfNode iff) {
            return hasNonEmptyTryOrLocking(iff.test, stackEmpty)
                    || hasNonEmptyTryOrLocking(iff.thenNode, stackEmpty)
                    || hasNonEmptyTryOrLocking(iff.elseNode, stackEmpty);
        }
        if (n instanceof DoNode dn) {
            for (Node c : dn.nodes) if (hasNonEmptyTryOrLocking(c, stackEmpty)) return true;
            return false;
        }
        if (n instanceof LetNode ln) {
            for (Node i : ln.inits) if (hasNonEmptyTryOrLocking(i, stackEmpty)) return true;
            return hasNonEmptyTryOrLocking(ln.body, stackEmpty);
        }
        if (n instanceof LoopNode lp) {
            for (Node i : lp.inits) if (hasNonEmptyTryOrLocking(i, stackEmpty)) return true;
            return hasNonEmptyTryOrLocking(lp.body, stackEmpty);
        }
        if (n instanceof CaseNode cn) {
            if (hasNonEmptyTryOrLocking(cn.expr, stackEmpty)) return true;
            if (cn.hasDefault && hasNonEmptyTryOrLocking(cn.defaultNode, stackEmpty)) return true;
            for (Node b : (cn.hashMode ? cn.bucketThens.values() : cn.table.values())) {
                if (hasNonEmptyTryOrLocking(b, stackEmpty)) return true;
            }
            return false;
        }
        if (n instanceof ConstNode || n instanceof LocalNode || n instanceof VarRefNode
                || n instanceof Recur0Node || n instanceof StaticFieldNode
                || n instanceof FnNode || n instanceof VarCall0Node) {
            return false;
        }
        for (Node child : childrenOf(n)) {
            if (hasNonEmptyTryOrLocking(child, false)) return true;
        }
        return false;
    }

    private Node liftEmpty(Node n) {
        if (n == null || !hasNonEmptyTryOrLocking(n, true)) return n;

        if (n instanceof TryNode tn) {
            Node nb = liftEmpty(tn.body);
            CatchClause[] ncs = new CatchClause[tn.catches.length];
            for (int i = 0; i < tn.catches.length; i++) {
                CatchClause cc = tn.catches[i];
                ncs[i] = new CatchClause(cc.exClass(), cc.slot(), liftEmpty(cc.handler()));
            }
            Node nf = liftEmpty(tn.finallyNode);
            return new TryNode(nb, ncs, nf);
        }
        if (n instanceof LockingNode ln) {
            return new LockingNode(liftEmpty(ln.target), liftEmpty(ln.body));
        }
        if (n instanceof IfNode iff) {
            return new IfNode(liftEmpty(iff.test), liftEmpty(iff.thenNode), liftEmpty(iff.elseNode));
        }
        if (n instanceof DoNode dn) {
            Node[] arr = new Node[dn.nodes.length];
            for (int i = 0; i < dn.nodes.length; i++) arr[i] = liftEmpty(dn.nodes[i]);
            return new DoNode(arr);
        }
        if (n instanceof LetNode ln) {
            Node[] inits = new Node[ln.inits.length];
            for (int i = 0; i < ln.inits.length; i++) inits[i] = liftEmpty(ln.inits[i]);
            return new LetNode(ln.slots, inits, liftEmpty(ln.body), ln.isLetfn);
        }
        if (n instanceof LoopNode lp) {
            Node[] inits = new Node[lp.inits.length];
            for (int i = 0; i < lp.inits.length; i++) inits[i] = liftEmpty(lp.inits[i]);
            return new LoopNode(lp.slots, inits, liftEmpty(lp.body));
        }
        if (n instanceof CaseNode cn) {
            Node expr = liftEmpty(cn.expr);
            Node def = cn.hasDefault ? liftEmpty(cn.defaultNode) : cn.defaultNode;
            if (cn.hashMode) {
                Map<Integer, Node> nbt = new LinkedHashMap<>();
                for (Map.Entry<Integer, Node> e : cn.bucketThens.entrySet()) {
                    nbt.put(e.getKey(), liftEmpty(e.getValue()));
                }
                return new CaseNode(expr, cn.shift, cn.mask, cn.testType, cn.bucketTests, nbt, cn.skipCheck, def);
            } else {
                Map<RT.EquivKey, Node> nt = new LinkedHashMap<>();
                for (Map.Entry<RT.EquivKey, Node> e : cn.table.entrySet()) {
                    nt.put(e.getKey(), liftEmpty(e.getValue()));
                }
                return new CaseNode(expr, nt, cn.hasDefault, def);
            }
        }

        // General composite expression node: lift all children up through the last child containing Try/Locking
        Node[] children = childrenOf(n);
        int lastIdx = -1;
        for (int i = 0; i < children.length; i++) {
            if (children[i] != null && containsTryOrLocking(children[i])) {
                lastIdx = i;
            }
        }
        if (lastIdx < 0) return n;

        List<Integer> slots = new ArrayList<>();
        List<Node> inits = new ArrayList<>();
        Node[] newChildren = children.clone();
        for (int i = 0; i <= lastIdx; i++) {
            Node c = children[i];
            if (c == null || c instanceof ConstNode || c instanceof LocalNode) continue;
            int slot = nextSlot++;
            slots.add(slot);
            inits.add(liftEmpty(c));
            newChildren[i] = new LocalNode(slot);
        }
        Node rebuilt = rebuildWithChildren(n, newChildren);
        if (slots.isEmpty()) return rebuilt;
        int[] slotArr = new int[slots.size()];
        for (int i = 0; i < slots.size(); i++) slotArr[i] = slots.get(i);
        return new LetNode(slotArr, inits.toArray(new Node[0]), rebuilt);
    }

    private static Node[] childrenOf(Node n) {
        if (n instanceof MonitorNode m) return new Node[]{m.target};
        if (n instanceof Recur1Node r) return new Node[]{r.expr0};
        if (n instanceof Recur2Node r) return new Node[]{r.expr0, r.expr1};
        if (n instanceof RecurNNode r) return r.exprs;
        if (n instanceof VectorNode v) return v.elems;
        if (n instanceof MapNode m) return m.kvs;
        if (n instanceof SetNode s) return s.elems;
        if (n instanceof DefNode d) return new Node[]{d.init, d.metaNode};
        if (n instanceof DefMacroNode d) return new Node[]{d.fnNode};
        if (n instanceof VarCall1Node c) return new Node[]{c.a};
        if (n instanceof VarCall2Node c) return new Node[]{c.a, c.b};
        if (n instanceof VarCall3Node c) return new Node[]{c.a, c.b, c.c};
        if (n instanceof VarCall4Node c) return new Node[]{c.a, c.b, c.c, c.d};
        if (n instanceof VarCallNNode c) return c.args;
        if (n instanceof Call0Node c) return new Node[]{c.fn};
        if (n instanceof Call1Node c) return new Node[]{c.fn, c.a};
        if (n instanceof Call2Node c) return new Node[]{c.fn, c.a, c.b};
        if (n instanceof Call3Node c) return new Node[]{c.fn, c.a, c.b, c.c};
        if (n instanceof Call4Node c) return new Node[]{c.fn, c.a, c.b, c.c, c.d};
        if (n instanceof CallNNode c) {
            Node[] all = new Node[1 + c.args.length];
            all[0] = c.fn;
            System.arraycopy(c.args, 0, all, 1, c.args.length);
            return all;
        }
        if (n instanceof Add2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Sub2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Mul2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Div2Node a) return new Node[]{a.a, a.b};
        if (n instanceof NegateNode a) return new Node[]{a.a};
        if (n instanceof IncNode a) return new Node[]{a.a};
        if (n instanceof DecNode a) return new Node[]{a.a};
        if (n instanceof Rem2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Mod2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Quot2Node a) return new Node[]{a.a, a.b};
        if (n instanceof UncheckedAdd2Node a) return new Node[]{a.a, a.b};
        if (n instanceof UncheckedSub2Node a) return new Node[]{a.a, a.b};
        if (n instanceof UncheckedMul2Node a) return new Node[]{a.a, a.b};
        if (n instanceof UncheckedIncNode a) return new Node[]{a.a};
        if (n instanceof UncheckedDecNode a) return new Node[]{a.a};
        if (n instanceof UncheckedNegateNode a) return new Node[]{a.a};
        if (n instanceof BitOp2Node a) return new Node[]{a.a, a.b};
        if (n instanceof BitNotNode a) return new Node[]{a.a};
        if (n instanceof LongCastNode a) return new Node[]{a.a};
        if (n instanceof DoubleCastNode a) return new Node[]{a.a};
        if (n instanceof AlengthNode a) return new Node[]{a.arr};
        if (n instanceof AgetNode a) return new Node[]{a.arr, a.idx};
        if (n instanceof AsetNode a) return new Node[]{a.arr, a.idx, a.val};
        if (n instanceof CountNode a) return new Node[]{a.coll};
        if (n instanceof NthNode a) return new Node[]{a.coll, a.idx, a.notFound};
        if (n instanceof GetNode a) return new Node[]{a.coll, a.key, a.notFound};
        if (n instanceof TransientNode a) return new Node[]{a.coll};
        if (n instanceof PersistentBangNode a) return new Node[]{a.target};
        if (n instanceof ConjBangNode a) return new Node[]{a.target, a.val};
        if (n instanceof AssocBangNode a) return new Node[]{a.target, a.key, a.val};
        if (n instanceof ConjNode a) return new Node[]{a.coll, a.val};
        if (n instanceof AssocNode a) return new Node[]{a.coll, a.key, a.val};
        if (n instanceof DissocNode a) return new Node[]{a.coll, a.key};
        if (n instanceof FirstNode a) return new Node[]{a.coll};
        if (n instanceof NextNode a) return new Node[]{a.coll};
        if (n instanceof RestNode a) return new Node[]{a.coll};
        if (n instanceof SeqNode a) return new Node[]{a.coll};
        if (n instanceof PopNode a) return new Node[]{a.coll};
        if (n instanceof VolatileNode a) return new Node[]{a.init};
        if (n instanceof DerefNode a) return new Node[]{a.target};
        if (n instanceof VresetNode a) return new Node[]{a.target, a.val};
        if (n instanceof AtomNode a) return new Node[]{a.init};
        if (n instanceof ResetBangNode a) return new Node[]{a.target, a.val};
        if (n instanceof SwapBangNode a) {
            Node[] all = new Node[2 + a.extraArgs.length];
            all[0] = a.target;
            all[1] = a.fn;
            System.arraycopy(a.extraArgs, 0, all, 2, a.extraArgs.length);
            return all;
        }
        if (n instanceof EvenPNode a) return new Node[]{a.a};
        if (n instanceof OddPNode a) return new Node[]{a.a};
        if (n instanceof TypePredNode a) return new Node[]{a.a};
        if (n instanceof Lt2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Lte2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Gt2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Gte2Node a) return new Node[]{a.a, a.b};
        if (n instanceof Equiv2Node a) return new Node[]{a.a, a.b};
        if (n instanceof NumEq2Node a) return new Node[]{a.a, a.b};
        if (n instanceof ZeroPNode a) return new Node[]{a.a};
        if (n instanceof PosPNode a) return new Node[]{a.a};
        if (n instanceof NegPNode a) return new Node[]{a.a};
        if (n instanceof NilPNode a) return new Node[]{a.a};
        if (n instanceof NotNode a) return new Node[]{a.a};
        if (n instanceof IdenticalNode a) return new Node[]{a.a, a.b};
        if (n instanceof ThrowNode a) return new Node[]{a.expr};
        if (n instanceof NewNode a) return a.args;
        if (n instanceof StaticMethodNode a) return a.args;
        if (n instanceof InstanceFieldNode a) return new Node[]{a.target};
        if (n instanceof InstanceMethodNode a) {
            Node[] all = new Node[1 + a.args.length];
            all[0] = a.target;
            System.arraycopy(a.args, 0, all, 1, a.args.length);
            return all;
        }
        if (n instanceof SetVarNode a) return new Node[]{a.val};
        if (n instanceof SetFieldNode a) return new Node[]{a.target, a.val};
        if (n instanceof SetStaticFieldNode a) return new Node[]{a.val};
        return Compiler.EMPTY_NODES;
    }

    private static Node rebuildWithChildren(Node n, Node[] c) {
        if (n instanceof MonitorNode m) return new MonitorNode(c[0], m.enter);
        if (n instanceof Recur1Node r) return new Recur1Node(r.slot0, c[0]);
        if (n instanceof Recur2Node r) return new Recur2Node(r.slot0, r.slot1, c[0], c[1]);
        if (n instanceof RecurNNode r) return new RecurNNode(r.slots, c);
        if (n instanceof VectorNode) return new VectorNode(c);
        if (n instanceof MapNode) return new MapNode(c);
        if (n instanceof SetNode) return new SetNode(c);
        if (n instanceof DefNode d) return new DefNode(d.var, c[0], c[1]);
        if (n instanceof DefMacroNode d) return new DefMacroNode(d.var, c[0]);
        if (n instanceof VarCall1Node v) return new VarCall1Node(v.v, c[0]);
        if (n instanceof VarCall2Node v) return new VarCall2Node(v.v, c[0], c[1]);
        if (n instanceof VarCall3Node v) return new VarCall3Node(v.v, c[0], c[1], c[2]);
        if (n instanceof VarCall4Node v) return new VarCall4Node(v.v, c[0], c[1], c[2], c[3]);
        if (n instanceof VarCallNNode v) return new VarCallNNode(v.v, c);
        if (n instanceof Call0Node) return new Call0Node(c[0]);
        if (n instanceof Call1Node) return new Call1Node(c[0], c[1]);
        if (n instanceof Call2Node) return new Call2Node(c[0], c[1], c[2]);
        if (n instanceof Call3Node) return new Call3Node(c[0], c[1], c[2], c[3]);
        if (n instanceof Call4Node) return new Call4Node(c[0], c[1], c[2], c[3], c[4]);
        if (n instanceof CallNNode) {
            Node[] args = Arrays.copyOfRange(c, 1, c.length);
            return new CallNNode(c[0], args);
        }
        if (n instanceof Add2Node) return new Add2Node(c[0], c[1]);
        if (n instanceof Sub2Node) return new Sub2Node(c[0], c[1]);
        if (n instanceof Mul2Node) return new Mul2Node(c[0], c[1]);
        if (n instanceof Div2Node) return new Div2Node(c[0], c[1]);
        if (n instanceof NegateNode) return new NegateNode(c[0]);
        if (n instanceof IncNode) return new IncNode(c[0]);
        if (n instanceof DecNode) return new DecNode(c[0]);
        if (n instanceof Rem2Node) return new Rem2Node(c[0], c[1]);
        if (n instanceof Mod2Node) return new Mod2Node(c[0], c[1]);
        if (n instanceof Quot2Node) return new Quot2Node(c[0], c[1]);
        if (n instanceof UncheckedAdd2Node) return new UncheckedAdd2Node(c[0], c[1]);
        if (n instanceof UncheckedSub2Node) return new UncheckedSub2Node(c[0], c[1]);
        if (n instanceof UncheckedMul2Node) return new UncheckedMul2Node(c[0], c[1]);
        if (n instanceof UncheckedIncNode) return new UncheckedIncNode(c[0]);
        if (n instanceof UncheckedDecNode) return new UncheckedDecNode(c[0]);
        if (n instanceof UncheckedNegateNode) return new UncheckedNegateNode(c[0]);
        if (n instanceof BitOp2Node b) return new BitOp2Node(b.op, c[0], c[1]);
        if (n instanceof BitNotNode) return new BitNotNode(c[0]);
        if (n instanceof LongCastNode l) return new LongCastNode(c[0], l.unchecked);
        if (n instanceof DoubleCastNode) return new DoubleCastNode(c[0]);
        if (n instanceof AlengthNode) return new AlengthNode(c[0]);
        if (n instanceof AgetNode) return new AgetNode(c[0], c[1]);
        if (n instanceof AsetNode) return new AsetNode(c[0], c[1], c[2]);
        if (n instanceof CountNode) return new CountNode(c[0]);
        if (n instanceof NthNode) return new NthNode(c[0], c[1], c[2]);
        if (n instanceof GetNode) return new GetNode(c[0], c[1], c[2]);
        if (n instanceof TransientNode) return new TransientNode(c[0]);
        if (n instanceof PersistentBangNode) return new PersistentBangNode(c[0]);
        if (n instanceof ConjBangNode) return new ConjBangNode(c[0], c[1]);
        if (n instanceof AssocBangNode) return new AssocBangNode(c[0], c[1], c[2]);
        if (n instanceof ConjNode) return new ConjNode(c[0], c[1]);
        if (n instanceof AssocNode) return new AssocNode(c[0], c[1], c[2]);
        if (n instanceof DissocNode) return new DissocNode(c[0], c[1]);
        if (n instanceof FirstNode) return new FirstNode(c[0]);
        if (n instanceof NextNode) return new NextNode(c[0]);
        if (n instanceof RestNode) return new RestNode(c[0]);
        if (n instanceof SeqNode) return new SeqNode(c[0]);
        if (n instanceof PopNode) return new PopNode(c[0]);
        if (n instanceof VolatileNode) return new VolatileNode(c[0]);
        if (n instanceof DerefNode) return new DerefNode(c[0]);
        if (n instanceof VresetNode) return new VresetNode(c[0], c[1]);
        if (n instanceof AtomNode) return new AtomNode(c[0]);
        if (n instanceof ResetBangNode) return new ResetBangNode(c[0], c[1]);
        if (n instanceof SwapBangNode) {
            Node[] extra = Arrays.copyOfRange(c, 2, c.length);
            return new SwapBangNode(c[0], c[1], extra);
        }
        if (n instanceof EvenPNode) return new EvenPNode(c[0]);
        if (n instanceof OddPNode) return new OddPNode(c[0]);
        if (n instanceof TypePredNode tp) return new TypePredNode(tp.pred, c[0]);
        if (n instanceof Lt2Node) return new Lt2Node(c[0], c[1]);
        if (n instanceof Lte2Node) return new Lte2Node(c[0], c[1]);
        if (n instanceof Gt2Node) return new Gt2Node(c[0], c[1]);
        if (n instanceof Gte2Node) return new Gte2Node(c[0], c[1]);
        if (n instanceof Equiv2Node) return new Equiv2Node(c[0], c[1]);
        if (n instanceof NumEq2Node) return new NumEq2Node(c[0], c[1]);
        if (n instanceof ZeroPNode) return new ZeroPNode(c[0]);
        if (n instanceof PosPNode) return new PosPNode(c[0]);
        if (n instanceof NegPNode) return new NegPNode(c[0]);
        if (n instanceof NilPNode) return new NilPNode(c[0]);
        if (n instanceof NotNode) return new NotNode(c[0]);
        if (n instanceof IdenticalNode) return new IdenticalNode(c[0], c[1]);
        if (n instanceof ThrowNode) return new ThrowNode(c[0]);
        if (n instanceof NewNode nn) return new NewNode(nn.clazz, c, nn.paramTags);
        if (n instanceof StaticMethodNode sm) return new StaticMethodNode(sm.clazz, sm.methodName, c, sm.paramTags);
        if (n instanceof InstanceFieldNode f) return new InstanceFieldNode(c[0], f.fieldName);
        if (n instanceof InstanceMethodNode im) {
            Node[] args = Arrays.copyOfRange(c, 1, c.length);
            return new InstanceMethodNode(c[0], im.methodName, args, im.declaredClass, im.paramTags);
        }
        if (n instanceof SetVarNode sv) return new SetVarNode(sv.var, c[0]);
        if (n instanceof SetFieldNode sf) return new SetFieldNode(c[0], sf.fieldName, c[1]);
        if (n instanceof SetStaticFieldNode ssf) return new SetStaticFieldNode(ssf.clazz, ssf.fieldName, c[0]);
        return n;
    }
}
