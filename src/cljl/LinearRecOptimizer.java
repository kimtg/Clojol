package cljl;

import cljl.BytecodeJIT.ClassEmitter;
import cljl.BytecodeJIT.CodeGen;
import cljl.BytecodeJIT.Label;
import cljl.BytecodeJIT.SlotLayout;
import cljl.Compiler.*;
import cljl.TypeAnalyzer.PrimSpec;

/**
 * General affine linear-decrement recursion optimizer for {@link BytecodeJIT}.
 *
 * Matches strictly pure unary long->long self-recursive IfNodes, applies 2-step frontier
 * expansion + pure-call CSE, proves a conservative overflow-free input upper bound, and
 * emits branch-pruned static workers with an exact checked Math.addExact fallback.
 */
final class LinearRecOptimizer {

    private LinearRecOptimizer() {}

    static final class LinearRecSpec {
        final Node baseBranch;
        final Node recBranch;
        final int paramSlot;
        final Var ownerVar;
        final int selfSlot;
        final long baseThreshold; // strict '<' threshold B (>= 0)
        final long cMin;
        final long cMax;
        final long t1;            // B + cMax
        final long safeMaxInput;  // provably overflow-free upper bound
        final int[] expOffsets;   // frontier-expanded + CSE'd distinct self-call offsets
        final long[] expWeights;  // corresponding signed coefficients
        final long expConst;      // expanded constant addend

        LinearRecSpec(Node baseBranch, Node recBranch, int paramSlot, Var ownerVar, int selfSlot,
                      long baseThreshold, long cMin, long cMax, long t1, long safeMaxInput,
                      int[] expOffsets, long[] expWeights, long expConst) {
            this.baseBranch = baseBranch;
            this.recBranch = recBranch;
            this.paramSlot = paramSlot;
            this.ownerVar = ownerVar;
            this.selfSlot = selfSlot;
            this.baseThreshold = baseThreshold;
            this.cMin = cMin;
            this.cMax = cMax;
            this.t1 = t1;
            this.safeMaxInput = safeMaxInput;
            this.expOffsets = expOffsets;
            this.expWeights = expWeights;
            this.expConst = expConst;
        }
    }

    private static Node getLinearSelfCallArg(Node n, Var ownerVar, int selfSlot) {
        if (n instanceof Call1Node c && c.fn instanceof LocalNode ln && ln.slot == selfSlot) {
            return c.a;
        }
        if (n instanceof VarCall1Node vc && ownerVar != null && vc.v == ownerVar) {
            return vc.a;
        }
        return null;
    }

    private static long extractLinearDecrement(Node arg, int paramSlot) {
        if (arg instanceof DecNode d && d.a instanceof LocalNode ln && ln.slot == paramSlot) return 1L;
        if (arg instanceof UncheckedDecNode d && d.a instanceof LocalNode ln && ln.slot == paramSlot) return 1L;
        if (arg instanceof Sub2Node s && s.a instanceof LocalNode ln && ln.slot == paramSlot
                && s.b instanceof ConstNode cn && cn.val instanceof Long l) {
            long c = l.longValue();
            return (c >= 1L && c <= 4L) ? c : -1L;
        }
        if (arg instanceof UncheckedSub2Node s && s.a instanceof LocalNode ln && ln.slot == paramSlot
                && s.b instanceof ConstNode cn && cn.val instanceof Long l) {
            long c = l.longValue();
            return (c >= 1L && c <= 4L) ? c : -1L;
        }
        if (arg instanceof Add2Node a) {
            if (a.a instanceof LocalNode ln && ln.slot == paramSlot
                    && a.b instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = -l.longValue();
                return (c >= 1L && c <= 4L) ? c : -1L;
            }
            if (a.b instanceof LocalNode ln && ln.slot == paramSlot
                    && a.a instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = -l.longValue();
                return (c >= 1L && c <= 4L) ? c : -1L;
            }
        }
        if (arg instanceof UncheckedAdd2Node a) {
            if (a.a instanceof LocalNode ln && ln.slot == paramSlot
                    && a.b instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = -l.longValue();
                return (c >= 1L && c <= 4L) ? c : -1L;
            }
            if (a.b instanceof LocalNode ln && ln.slot == paramSlot
                    && a.a instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = -l.longValue();
                return (c >= 1L && c <= 4L) ? c : -1L;
            }
        }
        return -1L;
    }

    private static long[] analyzeLinearRecExpr(Node expr, int paramSlot, Var ownerVar, int selfSlot, long[] bounds) {
        Node selfArg = getLinearSelfCallArg(expr, ownerVar, selfSlot);
        if (selfArg != null) {
            long dec = extractLinearDecrement(selfArg, paramSlot);
            if (dec < 1L) return null;
            bounds[0]++;
            if (dec < bounds[1]) bounds[1] = dec;
            if (dec > bounds[2]) bounds[2] = dec;
            return new long[]{1L, 0L};
        }
        if (expr instanceof ConstNode cn && cn.val instanceof Long l) {
            long v = l.longValue();
            if (v < -1000L || v > 1000L) return null;
            return new long[]{0L, Math.abs(v)};
        }
        if (expr instanceof Add2Node a) {
            long[] la = analyzeLinearRecExpr(a.a, paramSlot, ownerVar, selfSlot, bounds);
            long[] lb = analyzeLinearRecExpr(a.b, paramSlot, ownerVar, selfSlot, bounds);
            if (la == null || lb == null) return null;
            long mult = la[0] + lb[0], add = la[1] + lb[1];
            return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
        }
        if (expr instanceof UncheckedAdd2Node a) {
            long[] la = analyzeLinearRecExpr(a.a, paramSlot, ownerVar, selfSlot, bounds);
            long[] lb = analyzeLinearRecExpr(a.b, paramSlot, ownerVar, selfSlot, bounds);
            if (la == null || lb == null) return null;
            long mult = la[0] + lb[0], add = la[1] + lb[1];
            return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
        }
        if (expr instanceof Sub2Node a) {
            long[] la = analyzeLinearRecExpr(a.a, paramSlot, ownerVar, selfSlot, bounds);
            long[] lb = analyzeLinearRecExpr(a.b, paramSlot, ownerVar, selfSlot, bounds);
            if (la == null || lb == null) return null;
            long mult = la[0] + lb[0], add = la[1] + lb[1];
            return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
        }
        if (expr instanceof UncheckedSub2Node a) {
            long[] la = analyzeLinearRecExpr(a.a, paramSlot, ownerVar, selfSlot, bounds);
            long[] lb = analyzeLinearRecExpr(a.b, paramSlot, ownerVar, selfSlot, bounds);
            if (la == null || lb == null) return null;
            long mult = la[0] + lb[0], add = la[1] + lb[1];
            return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
        }
        if (expr instanceof Mul2Node m) {
            if (m.a instanceof ConstNode cn && cn.val instanceof Long l) {
                long k = Math.abs(l.longValue());
                if (k < 1L || k > 16L) return null;
                long[] lb = analyzeLinearRecExpr(m.b, paramSlot, ownerVar, selfSlot, bounds);
                if (lb == null) return null;
                long mult = k * lb[0], add = k * lb[1];
                return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
            }
            if (m.b instanceof ConstNode cn && cn.val instanceof Long l) {
                long k = Math.abs(l.longValue());
                if (k < 1L || k > 16L) return null;
                long[] la = analyzeLinearRecExpr(m.a, paramSlot, ownerVar, selfSlot, bounds);
                if (la == null) return null;
                long mult = k * la[0], add = k * la[1];
                return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
            }
            return null;
        }
        if (expr instanceof UncheckedMul2Node m) {
            if (m.a instanceof ConstNode cn && cn.val instanceof Long l) {
                long k = Math.abs(l.longValue());
                if (k < 1L || k > 16L) return null;
                long[] lb = analyzeLinearRecExpr(m.b, paramSlot, ownerVar, selfSlot, bounds);
                if (lb == null) return null;
                long mult = k * lb[0], add = k * lb[1];
                return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
            }
            if (m.b instanceof ConstNode cn && cn.val instanceof Long l) {
                long k = Math.abs(l.longValue());
                if (k < 1L || k > 16L) return null;
                long[] la = analyzeLinearRecExpr(m.a, paramSlot, ownerVar, selfSlot, bounds);
                if (la == null) return null;
                long mult = k * la[0], add = k * la[1];
                return (mult <= 64L && add <= 10000L) ? new long[]{mult, add} : null;
            }
            return null;
        }
        if (expr instanceof IncNode u) {
            long[] la = analyzeLinearRecExpr(u.a, paramSlot, ownerVar, selfSlot, bounds);
            return (la != null && la[1] + 1L <= 10000L) ? new long[]{la[0], la[1] + 1L} : null;
        }
        if (expr instanceof UncheckedIncNode u) {
            long[] la = analyzeLinearRecExpr(u.a, paramSlot, ownerVar, selfSlot, bounds);
            return (la != null && la[1] + 1L <= 10000L) ? new long[]{la[0], la[1] + 1L} : null;
        }
        if (expr instanceof DecNode u) {
            long[] la = analyzeLinearRecExpr(u.a, paramSlot, ownerVar, selfSlot, bounds);
            return (la != null && la[1] + 1L <= 10000L) ? new long[]{la[0], la[1] + 1L} : null;
        }
        if (expr instanceof UncheckedDecNode u) {
            long[] la = analyzeLinearRecExpr(u.a, paramSlot, ownerVar, selfSlot, bounds);
            return (la != null && la[1] + 1L <= 10000L) ? new long[]{la[0], la[1] + 1L} : null;
        }
        if (expr instanceof NegateNode u) {
            return analyzeLinearRecExpr(u.a, paramSlot, ownerVar, selfSlot, bounds);
        }
        if (expr instanceof UncheckedNegateNode u) {
            return analyzeLinearRecExpr(u.a, paramSlot, ownerVar, selfSlot, bounds);
        }
        return null;
    }

    static LinearRecSpec matchLinearDecrementRec(ClassEmitter ce, FnMethod m, int arity, PrimSpec ps) {
        if (arity != 1) return null;
        if (ps.paramTypes[0] != ValType.LONG || ps.analysis.returnType != ValType.LONG) return null;
        if (!(m.body instanceof IfNode iff)) return null;
        int paramSlot = m.paramSlots[0];

        Node baseBranch = null;
        Node recBranch = null;
        long baseThreshold = -1L;

        if (iff.test instanceof Lt2Node cmp) {
            if (cmp.a instanceof LocalNode ln && ln.slot == paramSlot && cmp.b instanceof ConstNode cn && cn.val instanceof Long l) {
                baseThreshold = l.longValue();
                baseBranch = iff.thenNode;
                recBranch = iff.elseNode;
            } else if (cmp.b instanceof LocalNode ln && ln.slot == paramSlot && cmp.a instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = l.longValue();
                if (c < Long.MAX_VALUE) {
                    baseThreshold = c + 1L;
                    baseBranch = iff.elseNode;
                    recBranch = iff.thenNode;
                }
            }
        } else if (iff.test instanceof Lte2Node cmp) {
            if (cmp.a instanceof LocalNode ln && ln.slot == paramSlot && cmp.b instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = l.longValue();
                if (c < Long.MAX_VALUE) {
                    baseThreshold = c + 1L;
                    baseBranch = iff.thenNode;
                    recBranch = iff.elseNode;
                }
            } else if (cmp.b instanceof LocalNode ln && ln.slot == paramSlot && cmp.a instanceof ConstNode cn && cn.val instanceof Long l) {
                baseThreshold = l.longValue();
                baseBranch = iff.elseNode;
                recBranch = iff.thenNode;
            }
        } else if (iff.test instanceof Gte2Node cmp) {
            if (cmp.a instanceof LocalNode ln && ln.slot == paramSlot && cmp.b instanceof ConstNode cn && cn.val instanceof Long l) {
                baseThreshold = l.longValue();
                baseBranch = iff.elseNode;
                recBranch = iff.thenNode;
            } else if (cmp.b instanceof LocalNode ln && ln.slot == paramSlot && cmp.a instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = l.longValue();
                if (c < Long.MAX_VALUE) {
                    baseThreshold = c + 1L;
                    baseBranch = iff.thenNode;
                    recBranch = iff.elseNode;
                }
            }
        } else if (iff.test instanceof Gt2Node cmp) {
            if (cmp.a instanceof LocalNode ln && ln.slot == paramSlot && cmp.b instanceof ConstNode cn && cn.val instanceof Long l) {
                long c = l.longValue();
                if (c < Long.MAX_VALUE) {
                    baseThreshold = c + 1L;
                    baseBranch = iff.elseNode;
                    recBranch = iff.thenNode;
                }
            } else if (cmp.b instanceof LocalNode ln && ln.slot == paramSlot && cmp.a instanceof ConstNode cn && cn.val instanceof Long l) {
                baseThreshold = l.longValue();
                baseBranch = iff.thenNode;
                recBranch = iff.elseNode;
            }
        }

        if (baseBranch == null || baseThreshold < 0L || baseThreshold > 16L) return null;

        long[] bounds = new long[]{0L, Long.MAX_VALUE, 0L};
        long[] growth = analyzeLinearRecExpr(recBranch, paramSlot, ce.ownerVar, m.selfSlot, bounds);
        if (growth == null || bounds[0] != 2L) return null;
        long cMin = bounds[1];
        long cMax = bounds[2];
        if (cMin < 1L || cMax > 4L) return null;

        long baseBound;
        if (baseBranch instanceof LocalNode ln && ln.slot == paramSlot) {
            baseBound = Math.max(1L, Math.max(Math.abs(baseThreshold - 1L), Math.abs(baseThreshold - cMax)));
        } else if (baseBranch instanceof ConstNode cn && cn.val instanceof Long l) {
            long k = l.longValue();
            if (k < -1_000_000L || k > 1_000_000L) return null;
            baseBound = Math.max(1L, Math.abs(k));
        } else {
            return null;
        }

        long t1 = baseThreshold + cMax;
        long origMult = Math.max(2L, growth[0]);
        long origConstAdd = growth[1];

        long[] origCoeff = new long[16];
        long[] origConstOut = new long[1];
        extractAffineRec(recBranch, paramSlot, ce.ownerVar, m.selfSlot, origCoeff, origConstOut, 1L);
        long origConst = origConstOut[0];

        long[] expCoeff = origCoeff.clone();
        long expConst = origConst;
        for (int step = 0; step < 2; step++) {
            int dMin = -1;
            for (int d = 1; d < expCoeff.length; d++) {
                if (expCoeff[d] != 0L) { dMin = d; break; }
            }
            if (dMin < 0 || dMin > cMax) break;
            long w = expCoeff[dMin];
            long[] nextCoeff = expCoeff.clone();
            nextCoeff[dMin] = 0L;
            boolean ok = true;
            for (int c = 1; c <= 4; c++) {
                long oc = origCoeff[c];
                if (oc == 0L) continue;
                int nd = dMin + c;
                if (nd >= nextCoeff.length) { ok = false; break; }
                nextCoeff[nd] += w * oc;
            }
            if (!ok) break;
            long nextConst = expConst + w * origConst;
            int distinct = 0;
            long sumAbsW = 0L;
            for (int d = 1; d < nextCoeff.length; d++) {
                if (nextCoeff[d] != 0L) {
                    distinct++;
                    sumAbsW += Math.abs(nextCoeff[d]);
                }
            }
            if (distinct > 3 || sumAbsW > 64L || Math.abs(nextConst) > 100_000L) break;
            expCoeff = nextCoeff;
            expConst = nextConst;
        }

        int distinct = 0;
        long expSumAbsW = 0L;
        for (int d = 1; d < expCoeff.length; d++) {
            if (expCoeff[d] != 0L) {
                distinct++;
                expSumAbsW += Math.abs(expCoeff[d]);
            }
        }
        int[] expOffsets = new int[distinct];
        long[] expWeights = new long[distinct];
        int idx = 0;
        for (int d = 1; d < expCoeff.length; d++) {
            if (expCoeff[d] != 0L) {
                expOffsets[idx] = d;
                expWeights[idx] = expCoeff[d];
                idx++;
            }
        }

        long denom = Math.max(1L, Math.max(1L, expSumAbsW) * (baseBound + origConstAdd) + Math.abs(expConst));
        long limit = (Long.MAX_VALUE / 4L) / denom;
        long prod = 1L;
        int maxSteps = 0;
        while (prod <= limit / origMult) {
            prod *= origMult;
            maxSteps++;
        }
        long safeMaxInput = (maxSteps >= 2) ? (baseThreshold + (long) (maxSteps - 1) * cMin) : -1L;
        if (safeMaxInput < t1) return null;

        return new LinearRecSpec(baseBranch, recBranch, paramSlot, ce.ownerVar, m.selfSlot,
                baseThreshold, cMin, cMax, t1, safeMaxInput, expOffsets, expWeights, expConst);
    }

    private static void extractAffineRec(
            Node expr, int paramSlot, Var ownerVar, int selfSlot,
            long[] coeff, long[] constOut, long scale) {
        Node selfArg = getLinearSelfCallArg(expr, ownerVar, selfSlot);
        if (selfArg != null) {
            int dec = (int) extractLinearDecrement(selfArg, paramSlot);
            coeff[dec] += scale;
            return;
        }
        if (expr instanceof ConstNode cn) {
            constOut[0] += scale * ((Long) cn.val).longValue();
            return;
        }
        if (expr instanceof Add2Node a) {
            extractAffineRec(a.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            extractAffineRec(a.b, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            return;
        }
        if (expr instanceof UncheckedAdd2Node a) {
            extractAffineRec(a.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            extractAffineRec(a.b, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            return;
        }
        if (expr instanceof Sub2Node a) {
            extractAffineRec(a.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            extractAffineRec(a.b, paramSlot, ownerVar, selfSlot, coeff, constOut, -scale);
            return;
        }
        if (expr instanceof UncheckedSub2Node a) {
            extractAffineRec(a.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            extractAffineRec(a.b, paramSlot, ownerVar, selfSlot, coeff, constOut, -scale);
            return;
        }
        if (expr instanceof Mul2Node m) {
            if (m.a instanceof ConstNode cn) {
                extractAffineRec(m.b, paramSlot, ownerVar, selfSlot, coeff, constOut, scale * ((Long) cn.val).longValue());
            } else {
                extractAffineRec(m.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale * ((Long) ((ConstNode) m.b).val).longValue());
            }
            return;
        }
        if (expr instanceof UncheckedMul2Node m) {
            if (m.a instanceof ConstNode cn) {
                extractAffineRec(m.b, paramSlot, ownerVar, selfSlot, coeff, constOut, scale * ((Long) cn.val).longValue());
            } else {
                extractAffineRec(m.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale * ((Long) ((ConstNode) m.b).val).longValue());
            }
            return;
        }
        if (expr instanceof IncNode u) {
            extractAffineRec(u.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            constOut[0] += scale;
            return;
        }
        if (expr instanceof UncheckedIncNode u) {
            extractAffineRec(u.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            constOut[0] += scale;
            return;
        }
        if (expr instanceof DecNode u) {
            extractAffineRec(u.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            constOut[0] -= scale;
            return;
        }
        if (expr instanceof UncheckedDecNode u) {
            extractAffineRec(u.a, paramSlot, ownerVar, selfSlot, coeff, constOut, scale);
            constOut[0] -= scale;
            return;
        }
        if (expr instanceof NegateNode u) {
            extractAffineRec(u.a, paramSlot, ownerVar, selfSlot, coeff, constOut, -scale);
            return;
        }
        if (expr instanceof UncheckedNegateNode u) {
            extractAffineRec(u.a, paramSlot, ownerVar, selfSlot, coeff, constOut, -scale);
        }
    }

    private static final int LINEAR_MODE_ALL_BASE = 0;
    private static final int LINEAR_MODE_SMALL1 = 1;

    private static void emitLinearParamMinus(CodeGen cg, int paramJvmSlot, long offset) {
        cg.lload(paramJvmSlot);
        if (offset != 0L) {
            cg.ldcLong(offset);
            cg.lsub();
        }
    }

    private static void emitLinearBase(CodeGen cg, LinearRecSpec spec, int paramJvmSlot, long paramOffset) {
        if (spec.baseBranch instanceof LocalNode) {
            emitLinearParamMinus(cg, paramJvmSlot, paramOffset);
        } else {
            cg.ldcLong(((Long) ((ConstNode) spec.baseBranch).val).longValue());
        }
    }

    private static void emitLinearExpr(
            CodeGen cg, Node expr, LinearRecSpec spec, int paramJvmSlot, long paramOffset, int tmpSlot,
            int mode, String className, String s1Name) {
        Node selfArg = getLinearSelfCallArg(expr, spec.ownerVar, spec.selfSlot);
        if (selfArg != null) {
            long dec = extractLinearDecrement(selfArg, spec.paramSlot);
            long totalOff = paramOffset + dec;
            if (mode == LINEAR_MODE_ALL_BASE) {
                emitLinearBase(cg, spec, paramJvmSlot, totalOff);
            } else if (spec.t1 - totalOff <= spec.baseThreshold) {
                emitLinearBase(cg, spec, paramJvmSlot, totalOff);
            } else {
                int stackAtBranch = cg.curStack;
                emitLinearParamMinus(cg, paramJvmSlot, totalOff);
                cg.lstore(tmpSlot);
                Label recLbl = new Label();
                Label doneLbl = new Label();
                cg.lload(tmpSlot);
                cg.ldcLong(spec.baseThreshold);
                cg.lcmp();
                cg.ifge(recLbl);
                emitLinearBase(cg, spec, tmpSlot, 0L);
                cg.gotoLabel(doneLbl);
                cg.curStack = stackAtBranch;
                cg.markLabel(recLbl);
                if (spec.t1 - totalOff - spec.cMin <= spec.baseThreshold) {
                    emitLinearExpr(cg, spec.recBranch, spec, tmpSlot, 0L, tmpSlot,
                            LINEAR_MODE_ALL_BASE, className, s1Name);
                } else {
                    cg.lload(tmpSlot);
                    cg.invokestatic(className, s1Name, "(J)J", 2, 2);
                }
                cg.markLabel(doneLbl);
                cg.curStack = stackAtBranch + 2;
            }
            return;
        }
        if (expr instanceof ConstNode cn) { cg.ldcLong(((Long) cn.val).longValue()); return; }
        if (expr instanceof Add2Node a) {
            emitLinearExpr(cg, a.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            emitLinearExpr(cg, a.b, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.ladd();
            return;
        }
        if (expr instanceof UncheckedAdd2Node a) {
            emitLinearExpr(cg, a.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            emitLinearExpr(cg, a.b, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.ladd();
            return;
        }
        if (expr instanceof Sub2Node a) {
            emitLinearExpr(cg, a.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            emitLinearExpr(cg, a.b, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.lsub();
            return;
        }
        if (expr instanceof UncheckedSub2Node a) {
            emitLinearExpr(cg, a.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            emitLinearExpr(cg, a.b, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.lsub();
            return;
        }
        if (expr instanceof Mul2Node a) {
            emitLinearExpr(cg, a.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            emitLinearExpr(cg, a.b, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.lmul();
            return;
        }
        if (expr instanceof UncheckedMul2Node a) {
            emitLinearExpr(cg, a.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            emitLinearExpr(cg, a.b, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.lmul();
            return;
        }
        if (expr instanceof IncNode u) {
            emitLinearExpr(cg, u.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.ldcLong(1L);
            cg.ladd();
            return;
        }
        if (expr instanceof UncheckedIncNode u) {
            emitLinearExpr(cg, u.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.ldcLong(1L);
            cg.ladd();
            return;
        }
        if (expr instanceof DecNode u) {
            emitLinearExpr(cg, u.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.ldcLong(1L);
            cg.lsub();
            return;
        }
        if (expr instanceof UncheckedDecNode u) {
            emitLinearExpr(cg, u.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.ldcLong(1L);
            cg.lsub();
            return;
        }
        if (expr instanceof NegateNode u) {
            emitLinearExpr(cg, u.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.lneg();
            return;
        }
        if (expr instanceof UncheckedNegateNode u) {
            emitLinearExpr(cg, u.a, spec, paramJvmSlot, paramOffset, tmpSlot, mode, className, s1Name);
            cg.lneg();
        }
    }

    static void emitLinearDecrementWorkers(
            ClassEmitter ce, FnMethod m, PrimSpec ps, String fastName, LinearRecSpec spec) {
        String s1Name = fastName + "_s1";
        String coreName = fastName + "_core";

        // 1. s1(long n): handles n < T1 with dead-branch pruning
        {
            CodeGen cg = ce.beginStaticMethod(s1Name, "(J)J", 4);
            Label recLbl = new Label();
            cg.lload(0);
            cg.ldcLong(spec.baseThreshold);
            cg.lcmp();
            cg.ifge(recLbl);
            emitLinearBase(cg, spec, 0, 0L);
            cg.emitReturnInstruction(ValType.LONG);
            cg.curStack = 0;
            cg.markLabel(recLbl);
            emitLinearExpr(cg, spec.recBranch, spec, 0, 0L, 2, LINEAR_MODE_SMALL1, ce.className, s1Name);
            cg.emitReturnInstruction(ValType.LONG);
            cg.finish();
        }

        // 2. core(long n): precondition T1 <= n <= safeMaxInput; frontier-expanded + CSE'd pure calls
        {
            CodeGen cg = ce.beginStaticMethod(coreName, "(J)J", 4);
            if (spec.expOffsets.length == 0) {
                cg.ldcLong(spec.expConst);
            } else {
                for (int i = 0; i < spec.expOffsets.length; i++) {
                    int off = spec.expOffsets[i];
                    long w = spec.expWeights[i];
                    int stackAtBranch = cg.curStack;
                    emitLinearParamMinus(cg, 0, off);
                    cg.lstore(2);
                    Label recLbl = new Label();
                    Label doneLbl = new Label();
                    cg.lload(2);
                    cg.ldcLong(spec.t1);
                    cg.lcmp();
                    cg.ifge(recLbl);
                    cg.lload(2);
                    cg.invokestatic(ce.className, s1Name, "(J)J", 2, 2);
                    cg.gotoLabel(doneLbl);
                    cg.curStack = stackAtBranch;
                    cg.markLabel(recLbl);
                    cg.lload(2);
                    cg.invokestatic(ce.className, coreName, "(J)J", 2, 2);
                    cg.markLabel(doneLbl);
                    cg.curStack = stackAtBranch + 2;
                    if (w == -1L) {
                        cg.lneg();
                    } else if (w == 2L) {
                        cg.dup2();
                        cg.ladd();
                    } else if (w != 1L) {
                        cg.ldcLong(w);
                        cg.lmul();
                    }
                    if (i > 0) {
                        cg.ladd();
                    }
                }
                if (spec.expConst != 0L) {
                    cg.ldcLong(spec.expConst);
                    cg.ladd();
                }
            }
            cg.emitReturnInstruction(ValType.LONG);
            cg.finish();
        }

        // 3. fast(long n): provably overflow-free range dispatch + exact checked fallback for n > safeMaxInput
        {
            SlotLayout layout = new SlotLayout(m, ps.analysis.slotTypes, 2);
            layout.jvmSlot[m.paramSlots[0]] = 0;
            CodeGen cg = ce.beginStaticMethod(fastName, "(J)J", layout.totalLocals);
            cg.setContext(m, ps.analysis, layout);
            cg.staticFastSelfSpec = ps;
            cg.staticFastSelfMethodName = fastName;
            cg.staticFastSelfCanInline = false;
            cg.staticFastSelfInlineDepth = 0;

            Label checkedFallback = new Label();
            cg.lload(0);
            cg.ldcLong(spec.safeMaxInput);
            cg.lcmp();
            cg.ifgt(checkedFallback);

            Label coreLbl = new Label();
            cg.lload(0);
            cg.ldcLong(spec.t1);
            cg.lcmp();
            cg.ifge(coreLbl);
            cg.lload(0);
            cg.invokestatic(ce.className, s1Name, "(J)J", 2, 2);
            cg.emitReturnInstruction(ValType.LONG);

            cg.curStack = 0;
            cg.markLabel(coreLbl);
            cg.lload(0);
            cg.invokestatic(ce.className, coreName, "(J)J", 2, 2);
            cg.emitReturnInstruction(ValType.LONG);

            cg.curStack = 0;
            cg.markLabel(checkedFallback);
            boolean terminated = cg.emitReturnTyped(m.body, ValType.LONG);
            if (!terminated) {
                cg.emitReturnInstruction(ValType.LONG);
            }
            cg.finish();
        }
    }
}
