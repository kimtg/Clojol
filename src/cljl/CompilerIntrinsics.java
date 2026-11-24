package cljl;

import cljl.Compiler.*;
import java.util.Arrays;

/**
 * Intrinsic AST node constructors split by category so JVM bytecode verification
 * of Compiler does not eagerly load ~75 unused AST node subclasses on startup.
 */
final class CompilerIntrinsics {

    private CompilerIntrinsics() {}

    static boolean isIntrinsicName(String name) {
        return switch (name) {
            case "+", "-", "*", "/", "inc", "dec", "rem", "mod", "quot",
                 "unchecked-add", "unchecked-subtract", "unchecked-multiply",
                 "unchecked-inc", "unchecked-inc-long", "unchecked-dec", "unchecked-negate",
                 "bit-and", "bit-or", "bit-xor", "bit-and-not",
                 "bit-shift-left", "bit-shift-right", "unsigned-bit-shift-right", "bit-not",
                 "long", "unchecked-long", "double",
                 "<", "<=", ">", ">=", "=", "==", "not=", "identical?",
                 "zero?", "pos?", "neg?", "nil?", "some?", "not",
                 "even?", "odd?", "string?", "vector?", "map?", "set?",
                 "seq?", "number?", "int?", "keyword?", "symbol?",
                 "alength", "aget", "aset", "count", "nth", "get",
                 "transient", "persistent!", "conj!", "assoc!",
                 "conj", "pop", "assoc", "dissoc",
                 "first", "next", "rest", "seq",
                 "volatile!", "deref", "vreset!", "atom", "reset!", "swap!" -> true;
            default -> false;
        };
    }

    static Node tryCreate(String name, Node[] args) {
        int arity = args.length;
        return switch (name) {
            case "+", "-", "*", "/", "inc", "dec", "rem", "mod", "quot",
                 "unchecked-add", "unchecked-subtract", "unchecked-multiply",
                 "unchecked-inc", "unchecked-inc-long", "unchecked-dec", "unchecked-negate",
                 "bit-and", "bit-or", "bit-xor", "bit-and-not",
                 "bit-shift-left", "bit-shift-right", "unsigned-bit-shift-right", "bit-not",
                 "long", "unchecked-long", "double" -> MathOps.tryCreate(name, args, arity);

            case "<", "<=", ">", ">=", "=", "==", "not=", "identical?",
                 "zero?", "pos?", "neg?", "nil?", "some?", "not",
                 "even?", "odd?", "string?", "vector?", "map?", "set?",
                 "seq?", "number?", "int?", "keyword?", "symbol?" -> CompareAndPredOps.tryCreate(name, args, arity);

            case "alength", "aget", "aset", "count", "nth", "get",
                 "transient", "persistent!", "conj!", "assoc!",
                 "conj", "pop", "assoc", "dissoc",
                 "first", "next", "rest", "seq" -> CollAndArrayOps.tryCreate(name, args, arity);

            case "volatile!", "deref", "vreset!", "atom", "reset!", "swap!" -> StateOps.tryCreate(name, args, arity);

            default -> null;
        };
    }

    private static boolean isStaticallyPrimitive(Node n) {
        if (n instanceof ConstNode cn) return cn.isLong || cn.isDouble;
        if (n instanceof LocalNode ln) return ln.hint == ValType.LONG || ln.hint == ValType.DOUBLE || ln.staticDouble;
        return n instanceof LongCastNode || n instanceof DoubleCastNode
                || n instanceof UncheckedAdd2Node || n instanceof UncheckedSub2Node
                || n instanceof UncheckedMul2Node || n instanceof UncheckedIncNode
                || n instanceof UncheckedDecNode || n instanceof UncheckedNegateNode
                || n instanceof BitOp2Node || n instanceof BitNotNode
                || n instanceof AlengthNode || n instanceof CountNode;
    }

    private static void checkWarnBoxed(String name, Node[] args) {
        if (!Compiler.isWarnOnBoxed()) return;
        for (Node a : args) {
            if (!isStaticallyPrimitive(a)) {
                Compiler.warnBoxedMath(name);
                return;
            }
        }
    }

    private static final class MathOps {
        static Node tryCreate(String name, Node[] args, int arity) {
            boolean unchecked = RT.truthy(RT.UNCHECKED_MATH.get());
            if (Compiler.isWarnOnBoxed()) {
                switch (name) {
                    case "+", "-", "*", "/", "inc", "dec", "rem", "mod", "quot" -> checkWarnBoxed(name, args);
                    default -> {}
                }
            }
            return switch (name) {
                case "+" -> switch (arity) {
                    case 0 -> new ConstNode(0L);
                    case 1 -> args[0];
                    case 2 -> unchecked ? new UncheckedAdd2Node(args[0], args[1]) : new Add2Node(args[0], args[1]);
                    case 3 -> unchecked
                            ? new UncheckedAdd2Node(new UncheckedAdd2Node(args[0], args[1]), args[2])
                            : new Add2Node(new Add2Node(args[0], args[1]), args[2]);
                    default -> null;
                };
                case "-" -> switch (arity) {
                    case 1 -> unchecked ? new UncheckedNegateNode(args[0]) : new NegateNode(args[0]);
                    case 2 -> unchecked ? new UncheckedSub2Node(args[0], args[1]) : new Sub2Node(args[0], args[1]);
                    case 3 -> unchecked
                            ? new UncheckedSub2Node(new UncheckedSub2Node(args[0], args[1]), args[2])
                            : new Sub2Node(new Sub2Node(args[0], args[1]), args[2]);
                    default -> null;
                };
                case "*" -> switch (arity) {
                    case 0 -> new ConstNode(1L);
                    case 1 -> args[0];
                    case 2 -> unchecked ? new UncheckedMul2Node(args[0], args[1]) : new Mul2Node(args[0], args[1]);
                    case 3 -> unchecked
                            ? new UncheckedMul2Node(new UncheckedMul2Node(args[0], args[1]), args[2])
                            : new Mul2Node(new Mul2Node(args[0], args[1]), args[2]);
                    default -> null;
                };
                case "/" -> arity == 2 ? new Div2Node(args[0], args[1]) : null;
                case "inc" -> arity == 1 ? (unchecked ? new UncheckedIncNode(args[0]) : new IncNode(args[0])) : null;
                case "dec" -> arity == 1 ? (unchecked ? new UncheckedDecNode(args[0]) : new DecNode(args[0])) : null;
                case "rem" -> arity == 2 ? new Rem2Node(args[0], args[1]) : null;
                case "mod" -> arity == 2 ? new Mod2Node(args[0], args[1]) : null;
                case "quot" -> arity == 2 ? new Quot2Node(args[0], args[1]) : null;
                case "unchecked-add" -> arity == 2 ? new UncheckedAdd2Node(args[0], args[1]) : null;
                case "unchecked-subtract" -> arity == 2 ? new UncheckedSub2Node(args[0], args[1]) : null;
                case "unchecked-multiply" -> arity == 2 ? new UncheckedMul2Node(args[0], args[1]) : null;
                case "unchecked-inc", "unchecked-inc-long" -> arity == 1 ? new UncheckedIncNode(args[0]) : null;
                case "unchecked-dec" -> arity == 1 ? new UncheckedDecNode(args[0]) : null;
                case "unchecked-negate" -> arity == 1 ? new UncheckedNegateNode(args[0]) : null;
                case "bit-and" -> arity == 2 ? new BitOp2Node(BitOp.AND, args[0], args[1]) : null;
                case "bit-or" -> arity == 2 ? new BitOp2Node(BitOp.OR, args[0], args[1]) : null;
                case "bit-xor" -> arity == 2 ? new BitOp2Node(BitOp.XOR, args[0], args[1]) : null;
                case "bit-and-not" -> arity == 2 ? new BitOp2Node(BitOp.AND_NOT, args[0], args[1]) : null;
                case "bit-shift-left" -> arity == 2 ? new BitOp2Node(BitOp.SHL, args[0], args[1]) : null;
                case "bit-shift-right" -> arity == 2 ? new BitOp2Node(BitOp.SHR, args[0], args[1]) : null;
                case "unsigned-bit-shift-right" -> arity == 2 ? new BitOp2Node(BitOp.USHR, args[0], args[1]) : null;
                case "bit-not" -> arity == 1 ? new BitNotNode(args[0]) : null;
                case "long" -> arity == 1 ? new LongCastNode(args[0], false) : null;
                case "unchecked-long" -> arity == 1 ? new LongCastNode(args[0], true) : null;
                case "double" -> arity == 1 ? new DoubleCastNode(args[0]) : null;
                default -> null;
            };
        }
    }

    private static final class CompareAndPredOps {
        static Node tryCreate(String name, Node[] args, int arity) {
            return switch (name) {
                case "<" -> arity == 2 ? new Lt2Node(args[0], args[1]) : null;
                case "<=" -> arity == 2 ? new Lte2Node(args[0], args[1]) : null;
                case ">" -> arity == 2 ? new Gt2Node(args[0], args[1]) : null;
                case ">=" -> arity == 2 ? new Gte2Node(args[0], args[1]) : null;
                case "=" -> arity == 2 ? new Equiv2Node(args[0], args[1]) : null;
                case "==" -> arity == 2 ? new NumEq2Node(args[0], args[1]) : null;
                case "not=" -> arity == 2 ? new NotNode(new Equiv2Node(args[0], args[1])) : null;
                case "zero?" -> arity == 1 ? new ZeroPNode(args[0]) : null;
                case "pos?" -> arity == 1 ? new PosPNode(args[0]) : null;
                case "neg?" -> arity == 1 ? new NegPNode(args[0]) : null;
                case "nil?" -> arity == 1 ? new NilPNode(args[0]) : null;
                case "some?" -> arity == 1 ? new NotNode(new NilPNode(args[0])) : null;
                case "not" -> arity == 1 ? new NotNode(args[0]) : null;
                case "identical?" -> arity == 2 ? new IdenticalNode(args[0], args[1]) : null;
                case "even?" -> arity == 1 ? new EvenPNode(args[0]) : null;
                case "odd?" -> arity == 1 ? new OddPNode(args[0]) : null;
                case "string?" -> arity == 1 ? new TypePredNode(TypePred.STRING, args[0]) : null;
                case "vector?" -> arity == 1 ? new TypePredNode(TypePred.VECTOR, args[0]) : null;
                case "map?" -> arity == 1 ? new TypePredNode(TypePred.MAP, args[0]) : null;
                case "set?" -> arity == 1 ? new TypePredNode(TypePred.SET, args[0]) : null;
                case "seq?" -> arity == 1 ? new TypePredNode(TypePred.SEQ, args[0]) : null;
                case "number?" -> arity == 1 ? new TypePredNode(TypePred.NUMBER, args[0]) : null;
                case "int?" -> arity == 1 ? new TypePredNode(TypePred.INT, args[0]) : null;
                case "keyword?" -> arity == 1 ? new TypePredNode(TypePred.KEYWORD, args[0]) : null;
                case "symbol?" -> arity == 1 ? new TypePredNode(TypePred.SYMBOL, args[0]) : null;
                default -> null;
            };
        }
    }

    private static final class CollAndArrayOps {
        static Node tryCreate(String name, Node[] args, int arity) {
            return switch (name) {
                case "alength" -> arity == 1 ? new AlengthNode(args[0]) : null;
                case "aget" -> arity == 2 ? new AgetNode(args[0], args[1]) : null;
                case "aset" -> arity == 3 ? new AsetNode(args[0], args[1], args[2]) : null;
                case "count" -> arity == 1 ? new CountNode(args[0]) : null;
                case "nth" -> (arity == 2 || arity == 3) ? new NthNode(args[0], args[1], arity == 3 ? args[2] : null) : null;
                case "get" -> (arity == 2 || arity == 3) ? new GetNode(args[0], args[1], arity == 3 ? args[2] : null) : null;
                case "transient" -> arity == 1 ? new TransientNode(args[0]) : null;
                case "persistent!" -> arity == 1 ? new PersistentBangNode(args[0]) : null;
                case "conj!" -> arity == 2 ? new ConjBangNode(args[0], args[1]) : null;
                case "assoc!" -> arity == 3 ? new AssocBangNode(args[0], args[1], args[2]) : null;
                case "conj" -> arity == 2 ? new ConjNode(args[0], args[1]) : null;
                case "pop" -> arity == 1 ? new PopNode(args[0]) : null;
                case "assoc" -> arity == 3 ? new AssocNode(args[0], args[1], args[2]) : null;
                case "dissoc" -> arity == 2 ? new DissocNode(args[0], args[1]) : null;
                case "first" -> arity == 1 ? new FirstNode(args[0]) : null;
                case "next" -> arity == 1 ? new NextNode(args[0]) : null;
                case "rest" -> arity == 1 ? new RestNode(args[0]) : null;
                case "seq" -> arity == 1 ? new SeqNode(args[0]) : null;
                default -> null;
            };
        }
    }

    private static final class StateOps {
        static Node tryCreate(String name, Node[] args, int arity) {
            return switch (name) {
                case "volatile!" -> arity == 1 ? new VolatileNode(args[0]) : null;
                case "deref" -> arity == 1 ? new DerefNode(args[0]) : null;
                case "vreset!" -> arity == 2 ? new VresetNode(args[0], args[1]) : null;
                case "atom" -> arity == 1 ? new AtomNode(args[0]) : null;
                case "reset!" -> arity == 2 ? new ResetBangNode(args[0], args[1]) : null;
                case "swap!" -> (arity >= 2 && arity <= 4)
                        ? new SwapBangNode(args[0], args[1], Arrays.copyOfRange(args, 2, arity)) : null;
                default -> null;
            };
        }
    }
}
