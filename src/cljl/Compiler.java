package cljl;

import cljl.lang.*;
import java.util.*;

/**
 * Optimizing Analyzer, Macroexpander, Destructuring Compiler, and Specialized AST
 * Execution Engine for ClojureLite.
 *
 * Key optimizations:
 * 1. Flat lexical slot addressing: all fn/let/loop/catch locals are mapped to integer
 *    indices in a single Object[] frame per function activation (zero HashMap lookups).
 * 2. Zero-allocation loop/recur & tail-recursive fn/recur: compiled to a direct Java
 *    while(true) loop mutating frame slots in place.
 * 3. Arity-specialized call nodes (0..4 args) avoiding Object[] varargs allocation.
 * 4. Primitive-specialized arithmetic & comparison AST nodes with unboxed evalLong/evalBoolean.
 * 5. Direct Var resolution at compile time.
 */
public final class Compiler {

    public static final Object RECUR_SENTINEL = new Object();

    private static final Set<Symbol> SPECIALS = Set.of(
            Symbol.intern("quote"),
            Symbol.intern("def"),
            Symbol.intern("if"),
            Symbol.intern("do"),
            Symbol.intern("let*"),
            Symbol.intern("loop*"),
            Symbol.intern("recur"),
            Symbol.intern("fn*"),
            Symbol.intern("defmacro"),
            Symbol.intern("var"),
            Symbol.intern("try"),
            Symbol.intern("catch"),
            Symbol.intern("finally"),
            Symbol.intern("throw"),
            Symbol.intern("new"),
            Symbol.intern("."),
            Symbol.intern("set!"),
            Symbol.intern("case*")
    );

    public static boolean isSpecial(Symbol sym) {
        return SPECIALS.contains(sym);
    }

    // ========================================================================
    // Public Entry Points
    // ========================================================================

    public static Object eval(Object form) {
        Node node = compile(form, null);
        return node.eval(Fn.EMPTY_ARGS);
    }

    public static Object load(String src, String filename) {
        List<Object> forms = Reader.readAll(src, filename);
        Object last = null;
        for (Object form : forms) {
            last = eval(form);
        }
        return last;
    }

    // ========================================================================
    // Macroexpansion
    // ========================================================================

    public static Object macroexpand1(Object form) {
        return macroexpand1(form, null);
    }

    private static Object macroexpand1(Object form, Scope scope) {
        if (!(form instanceof ISeq seq)) return form;
        ISeq s = seq.seq();
        if (s == null) return form;
        Object op = s.first();
        if (!(op instanceof Symbol sym)) return form;
        if (isSpecial(sym)) return form;
        if (scope != null && scope.resolveLocal(sym) != -1) {
            return form;
        }

        // Check for macro Var
        Var v = lookupVar(RT.currentNS(), sym, false);
        if (v != null && v.isMacro() && v.isBound()) {
            Fn macroFn = (Fn) v.deref();
            ISeq args = s.next();
            Object[] argArr = RT.toArray(args);
            return macroFn.applyTo(argArr);
        }

        // Interop sugar macroexpansion:
        // 1. (ClassName. arg1 arg2) -> (new ClassName arg1 arg2)
        if (sym.ns == null && sym.name.endsWith(".") && sym.name.length() > 1 && !sym.name.startsWith(".")) {
            Symbol classSym = Symbol.intern(sym.name.substring(0, sym.name.length() - 1));
            return RT.cons(Symbol.intern("new"), RT.cons(classSym, s.next()));
        }
        // 2. (.method target arg1 arg2) -> (. target method arg1 arg2)
        if (sym.ns == null && sym.name.startsWith(".") && sym.name.length() > 1 && !sym.name.equals("..")) {
            ISeq rest = s.next();
            if (rest == null) throw new IllegalArgumentException("Malformed member expression: " + form);
            Object target = rest.first();
            Symbol memberSym = Symbol.intern(sym.name.substring(1));
            return RT.cons(Symbol.intern("."), RT.cons(target, RT.cons(memberSym, rest.next())));
        }

        return form;
    }

    public static Object macroexpand(Object form) {
        return macroexpand(form, null);
    }

    private static Object macroexpand(Object form, Scope scope) {
        Object ex = macroexpand1(form, scope);
        while (ex != form) {
            form = ex;
            ex = macroexpand1(form, scope);
        }
        return ex;
    }

    // ========================================================================
    // Destructuring Compiler
    // ========================================================================

    /**
     * Expands destructuring binding pairs [pattern1 init1 pattern2 init2 ...]
     * into flat [sym1 expr1 sym2 expr2 ...] pairs.
     */
    public static PersistentVector destructure(PersistentVector bindings) {
        if ((bindings.count() & 1) != 0) {
            throw new IllegalArgumentException("Bindings must contain an even number of forms");
        }
        boolean hasComplex = false;
        for (int i = 0; i < bindings.count(); i += 2) {
            if (!(bindings.nth(i) instanceof Symbol)) {
                hasComplex = true;
                break;
            }
        }
        if (!hasComplex) return bindings;

        List<Object> out = new ArrayList<>();
        for (int i = 0; i < bindings.count(); i += 2) {
            destructurePair(bindings.nth(i), bindings.nth(i + 1), out);
        }
        return PersistentVector.create(out);
    }

    private static void destructurePair(Object bform, Object init, List<Object> out) {
        if (bform instanceof Symbol sym) {
            out.add(sym);
            out.add(init);
        } else if (bform instanceof PersistentVector vec) {
            Symbol gvec = gensym("vec__");
            out.add(gvec);
            out.add(init);
            int n = vec.count();
            boolean seenRest = false;
            for (int i = 0; i < n; i++) {
                Object elem = vec.nth(i);
                if (elem instanceof Symbol s && "&".equals(s.name)) {
                    if (i + 1 < n) {
                        Object restPat = vec.nth(i + 1);
                        Object restExpr = PersistentList.createFromArray(
                                Symbol.intern("clojure.core", "nthnext"), gvec, (long) i);
                        destructurePair(restPat, restExpr, out);
                        i++;
                        seenRest = true;
                    }
                } else if (elem instanceof Keyword kw && "as".equals(kw.getName())) {
                    if (i + 1 < n) {
                        destructurePair(vec.nth(i + 1), gvec, out);
                        i++;
                    }
                } else if (!seenRest) {
                    Object nthExpr = PersistentList.createFromArray(
                            Symbol.intern("clojure.core", "nth"), gvec, (long) i, null);
                    destructurePair(elem, nthExpr, out);
                }
            }
        } else if (bform instanceof PersistentHashMap map) {
            Symbol gmap = gensym("map__");
            out.add(gmap);
            out.add(init);

            Object orMap = map.valAt(Keyword.intern("or"), PersistentHashMap.EMPTY);
            Object asSym = map.valAt(Keyword.intern("as"), null);
            Object keysVec = map.valAt(Keyword.intern("keys"), null);
            Object strsVec = map.valAt(Keyword.intern("strs"), null);
            Object symsVec = map.valAt(Keyword.intern("syms"), null);

            if (asSym != null) {
                destructurePair(asSym, gmap, out);
            }

            for (ISeq s = map.seq(); s != null; s = s.next()) {
                PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                Object k = me.key;
                Object v = me.val;
                if (k instanceof Keyword kw && ("or".equals(kw.getName()) || "as".equals(kw.getName())
                        || "keys".equals(kw.getName()) || "strs".equals(kw.getName()) || "syms".equals(kw.getName()))) {
                    continue;
                }
                // In Clojure map destructuring {target-pat lookup-key}
                Object defaultExpr = getOrDefault(orMap, k);
                Object lookupExpr = makeGetExpr(gmap, v, defaultExpr);
                destructurePair(k, lookupExpr, out);
            }

            if (keysVec != null) {
                for (ISeq s = RT.seq(keysVec); s != null; s = s.next()) {
                    Object item = s.first();
                    Symbol sym = item instanceof Symbol sy ? sy : Symbol.intern(RT.name(item));
                    Symbol localSym = Symbol.intern(sym.name);
                    Keyword kw = Keyword.intern(sym.ns, sym.name);
                    Object defaultExpr = getOrDefault(orMap, localSym);
                    out.add(localSym);
                    out.add(makeGetExpr(gmap, kw, defaultExpr));
                }
            }
            if (strsVec != null) {
                for (ISeq s = RT.seq(strsVec); s != null; s = s.next()) {
                    Symbol sym = (Symbol) s.first();
                    Symbol localSym = Symbol.intern(sym.name);
                    Object defaultExpr = getOrDefault(orMap, localSym);
                    out.add(localSym);
                    out.add(makeGetExpr(gmap, sym.name, defaultExpr));
                }
            }
            if (symsVec != null) {
                for (ISeq s = RT.seq(symsVec); s != null; s = s.next()) {
                    Symbol sym = (Symbol) s.first();
                    Symbol localSym = Symbol.intern(sym.name);
                    Object defaultExpr = getOrDefault(orMap, localSym);
                    out.add(localSym);
                    out.add(makeGetExpr(gmap, PersistentList.createFromArray(Symbol.intern("quote"), sym), defaultExpr));
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported binding form: " + bform);
        }
    }

    private static Object getOrDefault(Object orMap, Object pat) {
        if (orMap instanceof PersistentHashMap m && pat instanceof Symbol sym) {
            return m.valAt(Symbol.intern(sym.name), null);
        }
        return null;
    }

    private static Object makeGetExpr(Symbol gmap, Object keyExpr, Object defaultExpr) {
        if (defaultExpr != null) {
            return PersistentList.createFromArray(Symbol.intern("clojure.core", "get"), gmap, keyExpr, defaultExpr);
        }
        return PersistentList.createFromArray(Symbol.intern("clojure.core", "get"), gmap, keyExpr);
    }

    private static Symbol gensym(String prefix) {
        return Symbol.intern(prefix + RT.nextID());
    }

    // ========================================================================
    // Lexical Scope & Frame Allocation
    // ========================================================================

    private static final class Scope {
        final Scope parent;
        final List<Map<Symbol, Integer>> blockStack = new ArrayList<>();
        final Map<Symbol, Integer> capturedBySymbol = new HashMap<>();
        final List< Integer> capturedParentSlots = new ArrayList<>();
        final Deque<int[]> recurTargets = new ArrayDeque<>();
        int maxSlots = 0;
        boolean fnRecurUsed = false;

        Scope(Scope parent) {
            this.parent = parent;
            this.blockStack.add(new HashMap<>());
        }

        void pushBlock() {
            blockStack.add(new HashMap<>());
        }

        void popBlock() {
            blockStack.remove(blockStack.size() - 1);
        }

        int allocateSlot(Symbol sym) {
            int slot = maxSlots++;
            if (sym != null) {
                blockStack.get(blockStack.size() - 1).put(sym.withMeta(null), slot);
            }
            return slot;
        }

        int resolveLocal(Symbol sym) {
            Symbol key = sym.withMeta(null);
            for (int i = blockStack.size() - 1; i >= 0; i--) {
                Integer slot = blockStack.get(i).get(key);
                if (slot != null) return slot;
            }
            Integer capSlot = capturedBySymbol.get(key);
            if (capSlot != null) return capSlot;

            if (parent != null) {
                int parentSlot = parent.resolveLocal(key);
                if (parentSlot != -1) {
                    int mySlot = maxSlots++;
                    capturedBySymbol.put(key, mySlot);
                    capturedParentSlots.add(parentSlot);
                    return mySlot;
                }
            }
            return -1;
        }
    }

    // ========================================================================
    // Analyzer / Compiler
    // ========================================================================

    public static Node compile(Object form, Scope scope) {
        form = macroexpand(form, scope);

        if (form == null) return ConstNode.NIL;
        if (form == Boolean.TRUE) return ConstNode.TRUE;
        if (form == Boolean.FALSE) return ConstNode.FALSE;
        if (form instanceof Number || form instanceof String || form instanceof Character
                || form instanceof Keyword || form instanceof java.util.regex.Pattern) {
            return new ConstNode(form);
        }

        if (form instanceof Symbol sym) {
            return compileSymbol(sym, scope);
        }

        if (form instanceof PersistentVector pv) {
            if (pv.count() == 0) return new ConstNode(PersistentVector.EMPTY);
            Node[] elems = new Node[pv.count()];
            boolean allConst = true;
            for (int i = 0; i < pv.count(); i++) {
                elems[i] = compile(pv.nth(i), scope);
                if (!(elems[i] instanceof ConstNode)) allConst = false;
            }
            if (allConst) {
                Object[] vals = new Object[elems.length];
                for (int i = 0; i < elems.length; i++) vals[i] = ((ConstNode) elems[i]).val;
                return new ConstNode(PersistentVector.createOwning(vals).withMeta(pv.meta()));
            }
            return new VectorNode(elems);
        }

        if (form instanceof PersistentHashMap pm) {
            if (pm.count() == 0) return new ConstNode(PersistentHashMap.EMPTY);
            Node[] kvs = new Node[pm.count() * 2];
            int i = 0;
            boolean allConst = true;
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                PersistentHashMap.MapEntry me = (PersistentHashMap.MapEntry) s.first();
                kvs[i] = compile(me.key, scope);
                kvs[i + 1] = compile(me.val, scope);
                if (!(kvs[i] instanceof ConstNode) || !(kvs[i + 1] instanceof ConstNode)) allConst = false;
                i += 2;
            }
            if (allConst) {
                Object[] vals = new Object[kvs.length];
                for (int j = 0; j < kvs.length; j++) vals[j] = ((ConstNode) kvs[j]).val;
                return new ConstNode(PersistentHashMap.create(vals).withMeta(pm.meta()));
            }
            return new MapNode(kvs);
        }

        if (form instanceof PersistentHashSet ps) {
            if (ps.count() == 0) return new ConstNode(PersistentHashSet.EMPTY);
            Node[] elems = new Node[ps.count()];
            int i = 0;
            boolean allConst = true;
            for (ISeq s = ps.seq(); s != null; s = s.next()) {
                elems[i] = compile(s.first(), scope);
                if (!(elems[i] instanceof ConstNode)) allConst = false;
                i++;
            }
            if (allConst) {
                Object[] vals = new Object[elems.length];
                for (int j = 0; j < elems.length; j++) vals[j] = ((ConstNode) elems[j]).val;
                return new ConstNode(PersistentHashSet.create(vals).withMeta(ps.meta()));
            }
            return new SetNode(elems);
        }

        if (form instanceof ISeq seq) {
            ISeq s = seq.seq();
            if (s == null) return new ConstNode(PersistentList.EMPTY);
            return compileSeq(s, scope);
        }

        return new ConstNode(form);
    }

    private static Node compileSymbol(Symbol sym, Scope scope) {
        if (sym.ns == null) {
            if (scope != null) {
                int slot = scope.resolveLocal(sym);
                if (slot != -1) {
                    return new LocalNode(slot);
                }
            }
            Namespace ns = RT.currentNS();
            Object mapped = ns.getMapping(sym);
            if (mapped instanceof Var v) {
                return new VarRefNode(v);
            }
            if (mapped instanceof Class<?> c) {
                return new ConstNode(c);
            }
            Class<?> cls = Interop.resolveClass(ns, sym.name);
            if (cls != null) {
                return new ConstNode(cls);
            }
            Var v = lookupVar(ns, sym, false);
            if (v != null) {
                return new VarRefNode(v);
            }
            throw new RuntimeException("Unable to resolve symbol: " + sym + " in this context (" + ns.name + ")");
        } else {
            // Qualified symbol: either ns/var or Class/STATIC_FIELD
            Namespace cur = RT.currentNS();
            Namespace targetNs = cur.lookupAlias(Symbol.intern(sym.ns));
            if (targetNs == null) targetNs = Namespace.find(sym.ns);
            if (targetNs != null) {
                Object mapped = targetNs.getMapping(Symbol.intern(sym.name));
                if (mapped instanceof Var v) {
                    return new VarRefNode(v);
                }
            }
            Class<?> cls = Interop.resolveClass(cur, sym.ns);
            if (cls != null) {
                return new StaticFieldNode(cls, sym.name);
            }
            // Auto-load built-in standard library namespace if not yet initialized
            if (StdLib.ensureLoaded(sym.ns)) {
                targetNs = Namespace.find(sym.ns);
                if (targetNs != null) {
                    Object mapped = targetNs.getMapping(Symbol.intern(sym.name));
                    if (mapped instanceof Var v) {
                        return new VarRefNode(v);
                    }
                }
            }
            throw new RuntimeException("No such var or static field: " + sym);
        }
    }

    public static Var lookupVar(Namespace ns, Symbol sym, boolean internIfMissing) {
        if (sym.ns != null) {
            Namespace targetNs = ns.lookupAlias(Symbol.intern(sym.ns));
            if (targetNs == null) targetNs = Namespace.find(sym.ns);
            if (targetNs == null && StdLib.ensureLoaded(sym.ns)) {
                targetNs = Namespace.find(sym.ns);
            }
            if (targetNs == null) return null;
            Object m = targetNs.getMapping(Symbol.intern(sym.name));
            return (m instanceof Var v) ? v : null;
        }
        Object m = ns.getMapping(sym);
        if (m instanceof Var v) return v;
        Object coreM = RT.CLOJURE_NS.getMapping(sym);
        if (coreM instanceof Var v) return v;
        if (internIfMissing) return ns.intern(sym);
        return null;
    }

    private static Node compileSeq(ISeq s, Scope scope) {
        Object op = s.first();
        ISeq args = s.next();

        if (op instanceof Symbol sym) {
            // If not shadowed by a local variable, check special forms and intrinsics
            boolean shadowed = (sym.ns == null && scope != null && scope.resolveLocal(sym) != -1);
            if (!shadowed && sym.ns == null) {
                switch (sym.name) {
                    case "quote" -> {
                        return new ConstNode(RT.first(args));
                    }
                    case "def" -> {
                        return compileDef(args, scope);
                    }
                    case "if" -> {
                        return compileIf(args, scope);
                    }
                    case "do" -> {
                        return compileDo(args, scope);
                    }
                    case "let*" -> {
                        return compileLet(args, scope);
                    }
                    case "loop*" -> {
                        return compileLoop(args, scope);
                    }
                    case "recur" -> {
                        return compileRecur(args, scope);
                    }
                    case "fn*" -> {
                        return compileFn(args, scope);
                    }
                    case "defmacro" -> {
                        return compileDefmacro(args, scope);
                    }
                    case "var" -> {
                        Symbol target = (Symbol) RT.first(args);
                        Var v = lookupVar(RT.currentNS(), target, false);
                        if (v == null) throw new RuntimeException("Unable to resolve var: " + target);
                        return new ConstNode(v);
                    }
                    case "try" -> {
                        return compileTry(args, scope);
                    }
                    case "throw" -> {
                        Node expr = compile(RT.first(args), scope);
                        return new ThrowNode(expr);
                    }
                    case "new" -> {
                        return compileNew(args, scope);
                    }
                    case "." -> {
                        return compileDot(args, scope);
                    }
                    case "set!" -> {
                        return compileSetBang(args, scope);
                    }
                    case "case*" -> {
                        return compileCase(args, scope);
                    }
                }
            }

            // Check if this is a static method call (ClassName/staticMethod ...)
            if (!shadowed && sym.ns != null) {
                Namespace cur = RT.currentNS();
                Namespace targetNs = cur.lookupAlias(Symbol.intern(sym.ns));
                if (targetNs == null) targetNs = Namespace.find(sym.ns);
                if (targetNs == null && !StdLib.ensureLoaded(sym.ns)) {
                    Class<?> cls = Interop.resolveClass(cur, sym.ns);
                    if (cls != null) {
                        Node[] argNodes = compileArgs(args, scope);
                        return new StaticMethodNode(cls, sym.name, argNodes);
                    }
                }
            }

            // Check if this resolves to a Var at compile time
            if (!shadowed) {
                Var targetVar = lookupVar(RT.currentNS(), sym, false);
                if (targetVar != null) {
                    Node[] argNodes = compileArgs(args, scope);
                    // Emit specialized intrinsic AST nodes for core hot-path operations
                    if (targetVar.ns == RT.CLOJURE_NS && !targetVar.isDynamic()) {
                        Node intrinsic = tryCreateIntrinsic(targetVar.sym.name, argNodes);
                        if (intrinsic != null) return intrinsic;
                    }
                    return createDirectVarCall(targetVar, argNodes);
                }
            }
        }

        // General function call
        Node fnNode = compile(op, scope);
        Node[] argNodes = compileArgs(args, scope);
        return createCallNode(fnNode, argNodes);
    }

    private static Node[] compileArgs(ISeq args, Scope scope) {
        int n = RT.count(args);
        if (n == 0) return EMPTY_NODES;
        Node[] nodes = new Node[n];
        int i = 0;
        for (ISeq s = args; s != null; s = s.next()) {
            nodes[i++] = compile(s.first(), scope);
        }
        return nodes;
    }

    private static Node tryCreateIntrinsic(String name, Node[] args) {
        int arity = args.length;
        return switch (name) {
            case "+" -> switch (arity) {
                case 0 -> new ConstNode(0L);
                case 1 -> args[0];
                case 2 -> new Add2Node(args[0], args[1]);
                case 3 -> new Add2Node(new Add2Node(args[0], args[1]), args[2]);
                default -> null;
            };
            case "-" -> switch (arity) {
                case 1 -> new NegateNode(args[0]);
                case 2 -> new Sub2Node(args[0], args[1]);
                case 3 -> new Sub2Node(new Sub2Node(args[0], args[1]), args[2]);
                default -> null;
            };
            case "*" -> switch (arity) {
                case 0 -> new ConstNode(1L);
                case 1 -> args[0];
                case 2 -> new Mul2Node(args[0], args[1]);
                case 3 -> new Mul2Node(new Mul2Node(args[0], args[1]), args[2]);
                default -> null;
            };
            case "inc" -> arity == 1 ? new IncNode(args[0]) : null;
            case "dec" -> arity == 1 ? new DecNode(args[0]) : null;
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
            case "rem" -> arity == 2 ? new Rem2Node(args[0], args[1]) : null;
            case "quot" -> arity == 2 ? new Quot2Node(args[0], args[1]) : null;
            default -> null;
        };
    }

    private static Node createDirectVarCall(Var v, Node[] args) {
        return switch (args.length) {
            case 0 -> new VarCall0Node(v);
            case 1 -> new VarCall1Node(v, args[0]);
            case 2 -> new VarCall2Node(v, args[0], args[1]);
            case 3 -> new VarCall3Node(v, args[0], args[1], args[2]);
            case 4 -> new VarCall4Node(v, args[0], args[1], args[2], args[3]);
            default -> new VarCallNNode(v, args);
        };
    }

    private static Node createCallNode(Node fn, Node[] args) {
        return switch (args.length) {
            case 0 -> new Call0Node(fn);
            case 1 -> new Call1Node(fn, args[0]);
            case 2 -> new Call2Node(fn, args[0], args[1]);
            case 3 -> new Call3Node(fn, args[0], args[1], args[2]);
            case 4 -> new Call4Node(fn, args[0], args[1], args[2], args[3]);
            default -> new CallNNode(fn, args);
        };
    }

    private static Node compileDef(ISeq args, Scope scope) {
        Symbol sym = (Symbol) args.first();
        ISeq more = args.next();
        String doc = null;
        Node initNode = null;
        if (more != null) {
            if (more.next() != null && more.first() instanceof String docStr) {
                doc = docStr;
                more = more.next();
            }
            initNode = compile(more.first(), scope);
        }
        Namespace ns = RT.currentNS();
        Var v = ns.intern(sym);
        if (doc != null) {
            v.setMeta(v.meta().assoc(Keyword.intern("doc"), doc));
        }
        return new DefNode(v, initNode);
    }

    private static Node compileIf(ISeq args, Scope scope) {
        int c = RT.count(args);
        if (c < 2 || c > 3) throw new IllegalArgumentException("if requires 2 or 3 arguments");
        Node testNode = compile(args.first(), scope);
        ISeq s2 = args.next();
        Node thenNode = compile(s2.first(), scope);
        Node elseNode = (s2.next() != null) ? compile(s2.next().first(), scope) : ConstNode.NIL;
        return new IfNode(testNode, thenNode, elseNode);
    }

    private static Node compileDo(ISeq args, Scope scope) {
        if (args == null) return ConstNode.NIL;
        Node[] nodes = compileArgs(args, scope);
        if (nodes.length == 1) return nodes[0];
        return new DoNode(nodes);
    }

    private static Node compileLet(ISeq args, Scope scope) {
        PersistentVector rawBindings = (PersistentVector) args.first();
        PersistentVector bindings = destructure(rawBindings);
        ISeq bodyForms = args.next();

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);
        scope.pushBlock();
        try {
            int pairCount = bindings.count() / 2;
            int[] slots = new int[pairCount];
            Node[] inits = new Node[pairCount];
            for (int i = 0; i < pairCount; i++) {
                Symbol sym = (Symbol) bindings.nth(2 * i);
                Node init = compile(bindings.nth(2 * i + 1), scope);
                int slot = scope.allocateSlot(sym);
                slots[i] = slot;
                inits[i] = init;
            }
            Node body = compileDo(bodyForms, scope);
            Node letNode = (pairCount == 0) ? body : new LetNode(slots, inits, body);
            if (isTopLevel) {
                return new TopFrameNode(scope.maxSlots, letNode);
            }
            return letNode;
        } finally {
            scope.popBlock();
        }
    }

    private static Node compileLoop(ISeq args, Scope scope) {
        PersistentVector rawBindings = (PersistentVector) args.first();
        if ((rawBindings.count() & 1) != 0) {
            throw new IllegalArgumentException("loop requires an even number of binding forms");
        }
        ISeq bodyForms = args.next();

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);
        scope.pushBlock();
        try {
            int pairCount = rawBindings.count() / 2;
            int[] slots = new int[pairCount];
            Node[] inits = new Node[pairCount];
            List<Object> destructurePairs = new ArrayList<>();

            for (int i = 0; i < pairCount; i++) {
                Object pat = rawBindings.nth(2 * i);
                Object initForm = rawBindings.nth(2 * i + 1);
                Node init = compile(initForm, scope);
                if (pat instanceof Symbol sym) {
                    slots[i] = scope.allocateSlot(sym);
                } else {
                    Symbol tempSym = gensym("loop__");
                    slots[i] = scope.allocateSlot(tempSym);
                    destructurePairs.add(pat);
                    destructurePairs.add(tempSym);
                }
                inits[i] = init;
            }

            scope.recurTargets.push(slots);
            Node body;
            try {
                if (!destructurePairs.isEmpty()) {
                    PersistentVector dvec = PersistentVector.create(destructurePairs);
                    Object wrappedBody = RT.cons(Symbol.intern("let*"), RT.cons(dvec, bodyForms));
                    body = compile(wrappedBody, scope);
                } else {
                    body = compileDo(bodyForms, scope);
                }
            } finally {
                scope.recurTargets.pop();
            }

            Node loopNode = new LoopNode(slots, inits, body);
            if (isTopLevel) {
                return new TopFrameNode(scope.maxSlots, loopNode);
            }
            return loopNode;
        } finally {
            scope.popBlock();
        }
    }

    private static Node compileRecur(ISeq args, Scope scope) {
        if (scope == null || scope.recurTargets.isEmpty()) {
            throw new IllegalArgumentException("Can only recur from inside fn or loop");
        }
        int[] targetSlots = scope.recurTargets.peek();
        Node[] exprs = compileArgs(args, scope);
        if (exprs.length != targetSlots.length) {
            throw new IllegalArgumentException(
                    "Mismatched argument count to recur, expected: " + targetSlots.length + " args, got: " + exprs.length);
        }
        if (scope.recurTargets.size() == 1) {
            scope.fnRecurUsed = true;
        }
        return switch (targetSlots.length) {
            case 0 -> Recur0Node.INSTANCE;
            case 1 -> new Recur1Node(targetSlots[0], exprs[0]);
            case 2 -> new Recur2Node(targetSlots[0], targetSlots[1], exprs[0], exprs[1]);
            default -> new RecurNNode(targetSlots, exprs);
        };
    }

    private static Node compileFn(ISeq args, Scope parentScope) {
        Symbol fnName = null;
        ISeq s = args;
        if (s.first() instanceof Symbol sym) {
            fnName = sym;
            s = s.next();
        }

        List<ISeq> methodForms = new ArrayList<>();
        if (s.first() instanceof PersistentVector) {
            methodForms.add(s);
        } else {
            for (ISeq ms = s; ms != null; ms = ms.next()) {
                methodForms.add((ISeq) ms.first());
            }
        }

        FnMethod[] fixedMethods = new FnMethod[21];
        FnMethod variadicMethod = null;
        boolean anyCaptures = false;

        for (ISeq mform : methodForms) {
            FnMethod method = compileFnMethod(fnName, mform, parentScope);
            if (method.capturedParentSlots.length > 0) {
                anyCaptures = true;
            }
            if (method.isVariadic) {
                variadicMethod = method;
            } else if (method.fixedArity < fixedMethods.length) {
                fixedMethods[method.fixedArity] = method;
            }
        }

        String displayName = fnName != null ? fnName.name : "fn";
        if (!anyCaptures) {
            // Pre-allocate stateless CompiledFn at compile time!
            return new ConstNode(new CompiledFn(displayName, fnName != null, fixedMethods, variadicMethod, null));
        }
        return new FnNode(displayName, fnName != null, fixedMethods, variadicMethod);
    }

    private static FnMethod compileFnMethod(Symbol fnName, ISeq mform, Scope parentScope) {
        PersistentVector rawParams = (PersistentVector) mform.first();
        ISeq bodyForms = mform.next();

        Scope fnScope = new Scope(parentScope);
        int selfSlot = -1;
        if (fnName != null) {
            selfSlot = fnScope.allocateSlot(fnName);
        }

        List<Symbol> paramSyms = new ArrayList<>();
        List<Object> destructurePairs = new ArrayList<>();
        boolean isVariadic = false;
        Symbol restSym = null;

        for (int i = 0; i < rawParams.count(); i++) {
            Object p = rawParams.nth(i);
            if (p instanceof Symbol s && "&".equals(s.name)) {
                isVariadic = true;
                Object restPat = rawParams.nth(i + 1);
                if (restPat instanceof Symbol rs) {
                    restSym = rs;
                } else {
                    restSym = gensym("rest__");
                    destructurePairs.add(restPat);
                    destructurePairs.add(restSym);
                }
                break;
            } else if (p instanceof Symbol s) {
                paramSyms.add(s);
            } else {
                Symbol gs = gensym("p__");
                paramSyms.add(gs);
                destructurePairs.add(p);
                destructurePairs.add(gs);
            }
        }

        int fixedArity = paramSyms.size();
        int totalParamSlots = fixedArity + (isVariadic ? 1 : 0);
        int[] paramSlots = new int[totalParamSlots];
        for (int i = 0; i < fixedArity; i++) {
            paramSlots[i] = fnScope.allocateSlot(paramSyms.get(i));
        }
        if (isVariadic) {
            paramSlots[fixedArity] = fnScope.allocateSlot(restSym);
        }

        fnScope.recurTargets.push(paramSlots);
        Node bodyNode;
        try {
            if (!destructurePairs.isEmpty()) {
                PersistentVector dvec = PersistentVector.create(destructurePairs);
                Object wrapped = RT.cons(Symbol.intern("let*"), RT.cons(dvec, bodyForms));
                bodyNode = compile(wrapped, fnScope);
            } else {
                bodyNode = compileDo(bodyForms, fnScope);
            }
        } finally {
            fnScope.recurTargets.pop();
        }

        int[] capParentSlots = new int[fnScope.capturedParentSlots.size()];
        int[] capLocalSlots = new int[fnScope.capturedParentSlots.size()];
        int idx = 0;
        for (Map.Entry<Symbol, Integer> e : fnScope.capturedBySymbol.entrySet()) {
            capLocalSlots[idx] = e.getValue();
            capParentSlots[idx] = parentScope.resolveLocal(e.getKey());
            idx++;
        }

        return new FnMethod(
                fixedArity,
                isVariadic,
                selfSlot,
                paramSlots,
                capParentSlots,
                capLocalSlots,
                fnScope.maxSlots,
                fnScope.fnRecurUsed,
                bodyNode);
    }

    private static Node compileDefmacro(ISeq args, Scope scope) {
        Symbol name = (Symbol) args.first();
        ISeq rest = args.next();
        String doc = null;
        if (rest.first() instanceof String s && rest.next() != null) {
            doc = s;
            rest = rest.next();
        }
        Object fnForm = RT.cons(Symbol.intern("fn*"), RT.cons(name, rest));
        Node fnNode = compile(fnForm, scope);
        Var v = RT.currentNS().intern(name);
        v.setMacro();
        if (doc != null) {
            v.setMeta(v.meta().assoc(Keyword.intern("doc"), doc));
        }
        return new DefMacroNode(v, fnNode);
    }

    private static Node compileTry(ISeq args, Scope scope) {
        List<Object> bodyForms = new ArrayList<>();
        List<CatchClause> catches = new ArrayList<>();
        ISeq finallyForms = null;

        boolean isTopLevel = (scope == null);
        if (isTopLevel) scope = new Scope(null);

        for (ISeq s = args; s != null; s = s.next()) {
            Object f = s.first();
            if (f instanceof ISeq clause && clause.seq() != null) {
                Object head = clause.first();
                if (Symbol.intern("catch").equals(head)) {
                    ISeq cargs = clause.next();
                    Symbol classSym = (Symbol) cargs.first();
                    Class<?> exClass = Interop.resolveClass(RT.currentNS(), classSym.toString());
                    if (exClass == null) throw new RuntimeException("Unable to resolve exception class: " + classSym);
                    Symbol exVar = (Symbol) cargs.next().first();
                    ISeq cbody = cargs.next().next();
                    scope.pushBlock();
                    int slot = scope.allocateSlot(exVar);
                    Node handler = compileDo(cbody, scope);
                    scope.popBlock();
                    catches.add(new CatchClause(exClass, slot, handler));
                    continue;
                } else if (Symbol.intern("finally").equals(head)) {
                    finallyForms = clause.next();
                    continue;
                }
            }
            bodyForms.add(f);
        }

        Node bodyNode = compileDo(PersistentList.create(bodyForms), scope);
        Node finallyNode = finallyForms != null ? compileDo(finallyForms, scope) : null;
        Node tryNode = new TryNode(bodyNode, catches.toArray(new CatchClause[0]), finallyNode);
        if (isTopLevel && scope.maxSlots > 0) {
            return new TopFrameNode(scope.maxSlots, tryNode);
        }
        return tryNode;
    }

    private static Node compileNew(ISeq args, Scope scope) {
        Object classSpec = args.first();
        Class<?> cls;
        if (classSpec instanceof Symbol sym) {
            cls = Interop.resolveClass(RT.currentNS(), sym.toString());
            if (cls == null) throw new RuntimeException("Unable to resolve class: " + sym);
        } else if (classSpec instanceof Class<?> c) {
            cls = c;
        } else {
            throw new IllegalArgumentException("new requires a Class symbol");
        }
        Node[] argNodes = compileArgs(args.next(), scope);
        return new NewNode(cls, argNodes);
    }

    private static Node compileDot(ISeq args, Scope scope) {
        Object targetForm = args.first();
        ISeq rest = args.next();
        Object memberPart = rest.first();
        String memberName;
        ISeq argForms;

        if (memberPart instanceof ISeq mseq) {
            memberName = ((Symbol) mseq.first()).name;
            argForms = mseq.next();
        } else if (memberPart instanceof Symbol msym) {
            memberName = msym.name;
            argForms = rest.next();
        } else {
            throw new IllegalArgumentException("Malformed . member expression");
        }

        boolean isField = memberName.startsWith("-");
        if (isField) {
            memberName = memberName.substring(1);
        }

        Node[] argNodes = compileArgs(argForms, scope);

        // Check if targetForm is a static Class symbol
        if (targetForm instanceof Symbol sym && sym.ns == null && (scope == null || scope.resolveLocal(sym) == -1)) {
            Var v = lookupVar(RT.currentNS(), sym, false);
            if (v == null) {
                Class<?> cls = Interop.resolveClass(RT.currentNS(), sym.name);
                if (cls != null) {
                    if (isField && argNodes.length == 0) {
                        return new StaticFieldNode(cls, memberName);
                    }
                    return new StaticMethodNode(cls, memberName, argNodes);
                }
            }
        }

        Node targetNode = compile(targetForm, scope);
        if (isField && argNodes.length == 0) {
            return new InstanceFieldNode(targetNode, memberName);
        }
        return new InstanceMethodNode(targetNode, memberName, argNodes);
    }

    private static Node compileSetBang(ISeq args, Scope scope) {
        Object target = args.first();
        Node valNode = compile(RT.second(args), scope);
        if (target instanceof Symbol sym) {
            Var v = lookupVar(RT.currentNS(), sym, false);
            if (v == null) throw new RuntimeException("Unable to resolve var in set!: " + sym);
            return new SetVarNode(v, valNode);
        }
        if (target instanceof ISeq seq) {
            Object expanded = macroexpand(seq, scope);
            if (expanded instanceof ISeq dotSeq && Symbol.intern(".").equals(dotSeq.first())) {
                Object objForm = RT.second(dotSeq);
                Object memForm = RT.nth(dotSeq, 2);
                String fieldName = memForm.toString();
                if (fieldName.startsWith("-")) fieldName = fieldName.substring(1);
                Node objNode = compile(objForm, scope);
                return new SetFieldNode(objNode, fieldName, valNode);
            }
        }
        throw new IllegalArgumentException("Invalid assignment target for set!: " + target);
    }

    private static Node compileCase(ISeq args, Scope scope) {
        // (case* expr [k1 v1 k2 v2 ...] default-opt)
        Node exprNode = compile(args.first(), scope);
        PersistentVector pairs = (PersistentVector) RT.second(args);
        boolean hasDefault = RT.count(args) >= 3;
        Node defaultNode = hasDefault ? compile(RT.nth(args, 2), scope) : null;

        Map<Object, Node> table = new HashMap<>();
        for (int i = 0; i < pairs.count(); i += 2) {
            Object k = pairs.nth(i);
            Node vNode = compile(pairs.nth(i + 1), scope);
            if (k instanceof ISeq ks && !Symbol.intern("quote").equals(ks.first())) {
                for (ISeq s = ks; s != null; s = s.next()) {
                    table.put(s.first(), vNode);
                }
            } else {
                table.put(k, vNode);
            }
        }
        return new CaseNode(exprNode, table, hasDefault, defaultNode);
    }

    // ========================================================================
    // AST Nodes
    // ========================================================================

    public static final Node[] EMPTY_NODES = new Node[0];

    public abstract static class Node {
        public abstract Object eval(Object[] frame);

        public long evalLong(Object[] frame) {
            return Numbers.toLong(eval(frame));
        }

        public boolean evalBoolean(Object[] frame) {
            return RT.truthy(eval(frame));
        }
    }

    public static final class ConstNode extends Node {
        public static final ConstNode NIL = new ConstNode(null);
        public static final ConstNode TRUE = new ConstNode(Boolean.TRUE);
        public static final ConstNode FALSE = new ConstNode(Boolean.FALSE);

        public final Object val;
        public final long longVal;
        public final boolean isLong;
        public final boolean boolVal;

        public ConstNode(Object val) {
            this.val = val;
            this.isLong = val instanceof Long;
            this.longVal = isLong ? (Long) val : 0L;
            this.boolVal = RT.truthy(val);
        }

        @Override public Object eval(Object[] frame) { return val; }
        @Override public long evalLong(Object[] frame) { return isLong ? longVal : Numbers.toLong(val); }
        @Override public boolean evalBoolean(Object[] frame) { return boolVal; }
    }

    public static final class LocalNode extends Node {
        public final int slot;
        public LocalNode(int slot) { this.slot = slot; }
        @Override public Object eval(Object[] frame) { return frame[slot]; }
    }

    public static final class VarRefNode extends Node {
        public final Var var;
        public VarRefNode(Var var) { this.var = var; }
        @Override public Object eval(Object[] frame) { return var.deref(); }
    }

    public static final class DefNode extends Node {
        public final Var var;
        public final Node init;
        public DefNode(Var var, Node init) { this.var = var; this.init = init; }
        @Override
        public Object eval(Object[] frame) {
            if (init != null) {
                var.bindRoot(init.eval(frame));
            }
            return var;
        }
    }

    public static final class DefMacroNode extends Node {
        public final Var var;
        public final Node fnNode;
        public DefMacroNode(Var var, Node fnNode) { this.var = var; this.fnNode = fnNode; }
        @Override
        public Object eval(Object[] frame) {
            var.bindRoot(fnNode.eval(frame));
            var.setMacro();
            return var;
        }
    }

    public static final class IfNode extends Node {
        public final Node test;
        public final Node thenNode;
        public final Node elseNode;

        public IfNode(Node test, Node thenNode, Node elseNode) {
            this.test = test;
            this.thenNode = thenNode;
            this.elseNode = elseNode;
        }

        @Override
        public Object eval(Object[] frame) {
            return test.evalBoolean(frame) ? thenNode.eval(frame) : elseNode.eval(frame);
        }

        @Override
        public long evalLong(Object[] frame) {
            return test.evalBoolean(frame) ? thenNode.evalLong(frame) : elseNode.evalLong(frame);
        }

        @Override
        public boolean evalBoolean(Object[] frame) {
            return test.evalBoolean(frame) ? thenNode.evalBoolean(frame) : elseNode.evalBoolean(frame);
        }
    }

    public static final class DoNode extends Node {
        public final Node[] nodes;
        public DoNode(Node[] nodes) { this.nodes = nodes; }
        @Override
        public Object eval(Object[] frame) {
            int last = nodes.length - 1;
            for (int i = 0; i < last; i++) {
                nodes[i].eval(frame);
            }
            return nodes[last].eval(frame);
        }
    }

    public static final class TopFrameNode extends Node {
        public final int maxSlots;
        public final Node body;
        public TopFrameNode(int maxSlots, Node body) {
            this.maxSlots = maxSlots;
            this.body = body;
        }
        @Override
        public Object eval(Object[] ignored) {
            return body.eval(new Object[maxSlots]);
        }
    }

    public static final class LetNode extends Node {
        public final int[] slots;
        public final Node[] inits;
        public final Node body;

        public LetNode(int[] slots, Node[] inits, Node body) {
            this.slots = slots;
            this.inits = inits;
            this.body = body;
        }

        @Override
        public Object eval(Object[] frame) {
            for (int i = 0; i < slots.length; i++) {
                frame[slots[i]] = inits[i].eval(frame);
            }
            return body.eval(frame);
        }
    }

    public static final class LoopNode extends Node {
        public final int[] slots;
        public final Node[] inits;
        public final Node body;

        public LoopNode(int[] slots, Node[] inits, Node body) {
            this.slots = slots;
            this.inits = inits;
            this.body = body;
        }

        @Override
        public Object eval(Object[] frame) {
            for (int i = 0; i < slots.length; i++) {
                frame[slots[i]] = inits[i].eval(frame);
            }
            while (true) {
                Object res = body.eval(frame);
                if (res != RECUR_SENTINEL) return res;
            }
        }
    }

    public static final class Recur0Node extends Node {
        public static final Recur0Node INSTANCE = new Recur0Node();
        @Override public Object eval(Object[] frame) { return RECUR_SENTINEL; }
    }

    public static final class Recur1Node extends Node {
        public final int slot0;
        public final Node expr0;
        public Recur1Node(int slot0, Node expr0) {
            this.slot0 = slot0;
            this.expr0 = expr0;
        }
        @Override
        public Object eval(Object[] frame) {
            frame[slot0] = expr0.eval(frame);
            return RECUR_SENTINEL;
        }
    }

    public static final class Recur2Node extends Node {
        public final int slot0, slot1;
        public final Node expr0, expr1;
        public Recur2Node(int slot0, int slot1, Node expr0, Node expr1) {
            this.slot0 = slot0;
            this.slot1 = slot1;
            this.expr0 = expr0;
            this.expr1 = expr1;
        }
        @Override
        public Object eval(Object[] frame) {
            Object v0 = expr0.eval(frame);
            Object v1 = expr1.eval(frame);
            frame[slot0] = v0;
            frame[slot1] = v1;
            return RECUR_SENTINEL;
        }
    }

    public static final class RecurNNode extends Node {
        public final int[] slots;
        public final Node[] exprs;
        public RecurNNode(int[] slots, Node[] exprs) {
            this.slots = slots;
            this.exprs = exprs;
        }
        @Override
        public Object eval(Object[] frame) {
            int n = slots.length;
            Object[] temp = new Object[n];
            for (int i = 0; i < n; i++) {
                temp[i] = exprs[i].eval(frame);
            }
            for (int i = 0; i < n; i++) {
                frame[slots[i]] = temp[i];
            }
            return RECUR_SENTINEL;
        }
    }

    public static final class VectorNode extends Node {
        public final Node[] elems;
        public VectorNode(Node[] elems) { this.elems = elems; }
        @Override
        public Object eval(Object[] frame) {
            Object[] arr = new Object[elems.length];
            for (int i = 0; i < elems.length; i++) arr[i] = elems[i].eval(frame);
            return PersistentVector.createOwning(arr);
        }
    }

    public static final class MapNode extends Node {
        public final Node[] kvs;
        public MapNode(Node[] kvs) { this.kvs = kvs; }
        @Override
        public Object eval(Object[] frame) {
            Object[] arr = new Object[kvs.length];
            for (int i = 0; i < kvs.length; i++) arr[i] = kvs[i].eval(frame);
            return PersistentHashMap.create(arr);
        }
    }

    public static final class SetNode extends Node {
        public final Node[] elems;
        public SetNode(Node[] elems) { this.elems = elems; }
        @Override
        public Object eval(Object[] frame) {
            Object[] arr = new Object[elems.length];
            for (int i = 0; i < elems.length; i++) arr[i] = elems[i].eval(frame);
            return PersistentHashSet.create(arr);
        }
    }

    // ========================================================================
    // Function & Closure Compilation
    // ========================================================================

    public static final class FnMethod {
        public final int fixedArity;
        public final boolean isVariadic;
        public final int selfSlot;
        public final int[] paramSlots;
        public final int[] capturedParentSlots;
        public final int[] capturedLocalSlots;
        public final int maxSlots;
        public final boolean hasRecur;
        public final Node body;

        public FnMethod(
                int fixedArity,
                boolean isVariadic,
                int selfSlot,
                int[] paramSlots,
                int[] capturedParentSlots,
                int[] capturedLocalSlots,
                int maxSlots,
                boolean hasRecur,
                Node body) {
            this.fixedArity = fixedArity;
            this.isVariadic = isVariadic;
            this.selfSlot = selfSlot;
            this.paramSlots = paramSlots;
            this.capturedParentSlots = capturedParentSlots;
            this.capturedLocalSlots = capturedLocalSlots;
            this.maxSlots = maxSlots;
            this.hasRecur = hasRecur;
            this.body = body;
        }

        public Object[] initFrame(Fn self, Object[][] capturedByMethod, int methodIdx) {
            if (maxSlots == 0) return Fn.EMPTY_ARGS;
            Object[] frame = new Object[maxSlots];
            if (selfSlot != -1) frame[selfSlot] = self;
            if (capturedByMethod != null) {
                Object[] caps = capturedByMethod[methodIdx];
                if (caps != null) {
                    for (int i = 0; i < capturedLocalSlots.length; i++) {
                        frame[capturedLocalSlots[i]] = caps[i];
                    }
                }
            }
            return frame;
        }

        public Object runBody(Object[] frame) {
            if (!hasRecur) {
                return body.eval(frame);
            }
            while (true) {
                Object res = body.eval(frame);
                if (res != RECUR_SENTINEL) return res;
            }
        }
    }

    public static final class FnNode extends Node {
        public final String name;
        public final boolean isNamed;
        public final FnMethod[] fixedMethods;
        public final FnMethod variadicMethod;

        public FnNode(String name, boolean isNamed, FnMethod[] fixedMethods, FnMethod variadicMethod) {
            this.name = name;
            this.isNamed = isNamed;
            this.fixedMethods = fixedMethods;
            this.variadicMethod = variadicMethod;
        }

        @Override
        public Object eval(Object[] outerFrame) {
            Object[][] captured = new Object[fixedMethods.length + 1][];
            for (int i = 0; i < fixedMethods.length; i++) {
                FnMethod m = fixedMethods[i];
                if (m != null && m.capturedParentSlots.length > 0) {
                    Object[] caps = new Object[m.capturedParentSlots.length];
                    for (int j = 0; j < caps.length; j++) {
                        caps[j] = outerFrame[m.capturedParentSlots[j]];
                    }
                    captured[i] = caps;
                }
            }
            if (variadicMethod != null && variadicMethod.capturedParentSlots.length > 0) {
                Object[] caps = new Object[variadicMethod.capturedParentSlots.length];
                for (int j = 0; j < caps.length; j++) {
                    caps[j] = outerFrame[variadicMethod.capturedParentSlots[j]];
                }
                captured[fixedMethods.length] = caps;
            }
            return new CompiledFn(name, isNamed, fixedMethods, variadicMethod, captured);
        }
    }

    public static final class CompiledFn extends Fn implements IMeta {
        private static final int JIT_THRESHOLD = 16;

        private final String name;
        private final boolean isNamed;
        private final FnMethod[] fixedMethods;
        private final FnMethod variadicMethod;
        private final Object[][] captured;
        private PersistentHashMap meta;
        private volatile Fn jitFn;
        private int calls;
        private boolean jitAttempted;

        public CompiledFn(
                String name,
                boolean isNamed,
                FnMethod[] fixedMethods,
                FnMethod variadicMethod,
                Object[][] captured) {
            this.name = name;
            this.isNamed = isNamed;
            this.fixedMethods = fixedMethods;
            this.variadicMethod = variadicMethod;
            this.captured = captured;
        }

        public FnMethod[] getFixedMethods() { return fixedMethods; }
        public FnMethod getVariadicMethod() { return variadicMethod; }
        public Object[][] getCaptured() { return captured; }

        private void maybeCompileJit() {
            if (jitAttempted) return;
            int c = ++calls;
            if (c < JIT_THRESHOLD && (c != 1 || !hasLoop())) {
                return;
            }
            synchronized (this) {
                if (jitAttempted) return;
                jitAttempted = true;
                Fn compiled = BytecodeJIT.compile(this);
                if (compiled != null) {
                    jitFn = compiled;
                }
            }
        }

        private boolean hasLoop() {
            for (FnMethod m : fixedMethods) {
                if (m != null && containsLoop(m.body)) return true;
            }
            return variadicMethod != null && containsLoop(variadicMethod.body);
        }

        private static boolean containsLoop(Node n) {
            if (n instanceof LoopNode) return true;
            if (n instanceof IfNode iff) {
                return containsLoop(iff.test) || containsLoop(iff.thenNode) || containsLoop(iff.elseNode);
            }
            if (n instanceof DoNode dn) {
                for (Node c : dn.nodes) if (containsLoop(c)) return true;
            }
            if (n instanceof LetNode ln) {
                for (Node init : ln.inits) if (containsLoop(init)) return true;
                return containsLoop(ln.body);
            }
            return false;
        }

        @Override
        public Object invoke() {
            Fn j = jitFn;
            if (j != null) return j.invoke();
            return invokeSlow0();
        }

        private Object invokeSlow0() {
            maybeCompileJit();
            Fn j = jitFn;
            if (j != null) return j.invoke();
            FnMethod m = fixedMethods[0];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 0);
                return m.runBody(frame);
            }
            if (variadicMethod != null && variadicMethod.fixedArity == 0) {
                Object[] frame = variadicMethod.initFrame(this, captured, fixedMethods.length);
                frame[variadicMethod.paramSlots[0]] = null;
                return variadicMethod.runBody(frame);
            }
            throw arityError(0);
        }

        @Override
        public Object invoke(Object a) {
            Fn j = jitFn;
            if (j != null) return j.invoke(a);
            return invokeSlow1(a);
        }

        private Object invokeSlow1(Object a) {
            maybeCompileJit();
            Fn j = jitFn;
            if (j != null) return j.invoke(a);
            FnMethod m = fixedMethods[1];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 1);
                frame[m.paramSlots[0]] = a;
                return m.runBody(frame);
            }
            return invokeVarargs(new Object[]{a});
        }

        @Override
        public Object invoke(Object a, Object b) {
            Fn j = jitFn;
            if (j != null) return j.invoke(a, b);
            return invokeSlow2(a, b);
        }

        private Object invokeSlow2(Object a, Object b) {
            maybeCompileJit();
            Fn j = jitFn;
            if (j != null) return j.invoke(a, b);
            FnMethod m = fixedMethods[2];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 2);
                frame[m.paramSlots[0]] = a;
                frame[m.paramSlots[1]] = b;
                return m.runBody(frame);
            }
            return invokeVarargs(new Object[]{a, b});
        }

        @Override
        public Object invoke(Object a, Object b, Object c) {
            Fn j = jitFn;
            if (j != null) return j.invoke(a, b, c);
            return invokeSlow3(a, b, c);
        }

        private Object invokeSlow3(Object a, Object b, Object c) {
            maybeCompileJit();
            Fn j = jitFn;
            if (j != null) return j.invoke(a, b, c);
            FnMethod m = fixedMethods[3];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 3);
                frame[m.paramSlots[0]] = a;
                frame[m.paramSlots[1]] = b;
                frame[m.paramSlots[2]] = c;
                return m.runBody(frame);
            }
            return invokeVarargs(new Object[]{a, b, c});
        }

        @Override
        public Object invoke(Object a, Object b, Object c, Object d) {
            Fn j = jitFn;
            if (j != null) return j.invoke(a, b, c, d);
            return invokeSlow4(a, b, c, d);
        }

        private Object invokeSlow4(Object a, Object b, Object c, Object d) {
            maybeCompileJit();
            Fn j = jitFn;
            if (j != null) return j.invoke(a, b, c, d);
            FnMethod m = fixedMethods[4];
            if (m != null) {
                Object[] frame = m.initFrame(this, captured, 4);
                frame[m.paramSlots[0]] = a;
                frame[m.paramSlots[1]] = b;
                frame[m.paramSlots[2]] = c;
                frame[m.paramSlots[3]] = d;
                return m.runBody(frame);
            }
            return invokeVarargs(new Object[]{a, b, c, d});
        }

        @Override
        public Object invokeVarargs(Object[] args) {
            Fn j = jitFn;
            if (j != null) return j.invokeVarargs(args);
            return invokeSlowVarargs(args);
        }

        private Object invokeSlowVarargs(Object[] args) {
            maybeCompileJit();
            Fn j = jitFn;
            if (j != null) return j.invokeVarargs(args);
            int n = args.length;
            if (n < fixedMethods.length) {
                FnMethod m = fixedMethods[n];
                if (m != null) {
                    Object[] frame = m.initFrame(this, captured, n);
                    for (int i = 0; i < n; i++) {
                        frame[m.paramSlots[i]] = args[i];
                    }
                    return m.runBody(frame);
                }
            }
            if (variadicMethod != null && n >= variadicMethod.fixedArity) {
                Object[] frame = variadicMethod.initFrame(this, captured, fixedMethods.length);
                int fixed = variadicMethod.fixedArity;
                for (int i = 0; i < fixed; i++) {
                    frame[variadicMethod.paramSlots[i]] = args[i];
                }
                ISeq restSeq = (n > fixed) ? PersistentList.createFromArraySlice(args, fixed) : null;
                frame[variadicMethod.paramSlots[fixed]] = restSeq;
                return variadicMethod.runBody(frame);
            }
            throw arityError(n);
        }

        @Override public String getName() { return name; }
        @Override public PersistentHashMap meta() { return meta; }
        @Override public CompiledFn withMeta(PersistentHashMap m) { this.meta = m; return this; }
        @Override public String toString() { return "#<fn " + name + ">"; }
    }

    // ========================================================================
    // Arity-Specialized Call Nodes
    // ========================================================================

    public static final class VarCall0Node extends Node {
        public final Var v;
        public VarCall0Node(Var v) { this.v = v; }
        @Override public Object eval(Object[] frame) { return ((Fn) v.get()).invoke(); }
    }

    public static final class VarCall1Node extends Node {
        public final Var v;
        public final Node a;
        public VarCall1Node(Var v, Node a) { this.v = v; this.a = a; }
        @Override public Object eval(Object[] frame) { return ((Fn) v.get()).invoke(a.eval(frame)); }
    }

    public static final class VarCall2Node extends Node {
        public final Var v;
        public final Node a, b;
        public VarCall2Node(Var v, Node a, Node b) { this.v = v; this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return ((Fn) v.get()).invoke(a.eval(frame), b.eval(frame)); }
    }

    public static final class VarCall3Node extends Node {
        public final Var v;
        public final Node a, b, c;
        public VarCall3Node(Var v, Node a, Node b, Node c) { this.v = v; this.a = a; this.b = b; this.c = c; }
        @Override public Object eval(Object[] frame) { return ((Fn) v.get()).invoke(a.eval(frame), b.eval(frame), c.eval(frame)); }
    }

    public static final class VarCall4Node extends Node {
        public final Var v;
        public final Node a, b, c, d;
        public VarCall4Node(Var v, Node a, Node b, Node c, Node d) { this.v = v; this.a = a; this.b = b; this.c = c; this.d = d; }
        @Override public Object eval(Object[] frame) { return ((Fn) v.get()).invoke(a.eval(frame), b.eval(frame), c.eval(frame), d.eval(frame)); }
    }

    public static final class VarCallNNode extends Node {
        public final Var v;
        public final Node[] args;
        public VarCallNNode(Var v, Node[] args) { this.v = v; this.args = args; }
        @Override
        public Object eval(Object[] frame) {
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return ((Fn) v.get()).invokeVarargs(evaluated);
        }
    }

    public static final class Call0Node extends Node {
        public final Node fn;
        public Call0Node(Node fn) { this.fn = fn; }
        @Override public Object eval(Object[] frame) { return ((Fn) fn.eval(frame)).invoke(); }
    }

    public static final class Call1Node extends Node {
        public final Node fn, a;
        public Call1Node(Node fn, Node a) { this.fn = fn; this.a = a; }
        @Override public Object eval(Object[] frame) { return ((Fn) fn.eval(frame)).invoke(a.eval(frame)); }
    }

    public static final class Call2Node extends Node {
        public final Node fn, a, b;
        public Call2Node(Node fn, Node a, Node b) { this.fn = fn; this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return ((Fn) fn.eval(frame)).invoke(a.eval(frame), b.eval(frame)); }
    }

    public static final class Call3Node extends Node {
        public final Node fn, a, b, c;
        public Call3Node(Node fn, Node a, Node b, Node c) { this.fn = fn; this.a = a; this.b = b; this.c = c; }
        @Override public Object eval(Object[] frame) { return ((Fn) fn.eval(frame)).invoke(a.eval(frame), b.eval(frame), c.eval(frame)); }
    }

    public static final class Call4Node extends Node {
        public final Node fn, a, b, c, d;
        public Call4Node(Node fn, Node a, Node b, Node c, Node d) { this.fn = fn; this.a = a; this.b = b; this.c = c; this.d = d; }
        @Override public Object eval(Object[] frame) { return ((Fn) fn.eval(frame)).invoke(a.eval(frame), b.eval(frame), c.eval(frame), d.eval(frame)); }
    }

    public static final class CallNNode extends Node {
        public final Node fn;
        public final Node[] args;
        public CallNNode(Node fn, Node[] args) { this.fn = fn; this.args = args; }
        @Override
        public Object eval(Object[] frame) {
            Fn f = (Fn) fn.eval(frame);
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return f.invokeVarargs(evaluated);
        }
    }

    // ========================================================================
    // Primitive-Specialized Arithmetic & Comparison Intrinsics
    // ========================================================================

    public static final class Add2Node extends Node {
        public final Node a, b;
        public Add2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) {
                return Math.addExact(la, lb);
            }
            return Numbers.add(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            return Math.addExact(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class Sub2Node extends Node {
        public final Node a, b;
        public Sub2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) {
                return Math.subtractExact(la, lb);
            }
            return Numbers.minus(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            return Math.subtractExact(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class Mul2Node extends Node {
        public final Node a, b;
        public Mul2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) {
                return Math.multiplyExact(la, lb);
            }
            return Numbers.multiply(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            return Math.multiplyExact(a.evalLong(frame), b.evalLong(frame));
        }
    }

    public static final class NegateNode extends Node {
        public final Node a;
        public NegateNode(Node a) { this.a = a; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            if (va instanceof Long la) return Math.negateExact(la);
            return Numbers.minus(va);
        }
        @Override
        public long evalLong(Object[] frame) {
            return Math.negateExact(a.evalLong(frame));
        }
    }

    public static final class IncNode extends Node {
        public final Node a;
        public IncNode(Node a) { this.a = a; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            if (va instanceof Long la) return Math.incrementExact(la);
            return Numbers.inc(va);
        }
        @Override
        public long evalLong(Object[] frame) {
            return Math.incrementExact(a.evalLong(frame));
        }
    }

    public static final class DecNode extends Node {
        public final Node a;
        public DecNode(Node a) { this.a = a; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            if (va instanceof Long la) return Math.decrementExact(la);
            return Numbers.dec(va);
        }
        @Override
        public long evalLong(Object[] frame) {
            return Math.decrementExact(a.evalLong(frame));
        }
    }

    public static final class Rem2Node extends Node {
        public final Node a, b;
        public Rem2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la % lb;
            return Numbers.remainder(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            return a.evalLong(frame) % b.evalLong(frame);
        }
    }

    public static final class Quot2Node extends Node {
        public final Node a, b;
        public Quot2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override
        public Object eval(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la / lb;
            return Numbers.quotient(va, vb);
        }
        @Override
        public long evalLong(Object[] frame) {
            return a.evalLong(frame) / b.evalLong(frame);
        }
    }

    public static final class Lt2Node extends Node {
        public final Node a, b;
        public Lt2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la < lb;
            return Numbers.lt(va, vb);
        }
    }

    public static final class Lte2Node extends Node {
        public final Node a, b;
        public Lte2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la <= lb;
            return Numbers.lte(va, vb);
        }
    }

    public static final class Gt2Node extends Node {
        public final Node a, b;
        public Gt2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la > lb;
            return Numbers.gt(va, vb);
        }
    }

    public static final class Gte2Node extends Node {
        public final Node a, b;
        public Gte2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            Object vb = b.eval(frame);
            if (va instanceof Long la && vb instanceof Long lb) return la >= lb;
            return Numbers.gte(va, vb);
        }
    }

    public static final class Equiv2Node extends Node {
        public final Node a, b;
        public Equiv2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override public boolean evalBoolean(Object[] frame) { return RT.equiv(a.eval(frame), b.eval(frame)); }
    }

    public static final class NumEq2Node extends Node {
        public final Node a, b;
        public NumEq2Node(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override public boolean evalBoolean(Object[] frame) { return Numbers.equal(a.eval(frame), b.eval(frame)); }
    }

    public static final class ZeroPNode extends Node {
        public final Node a;
        public ZeroPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            if (va instanceof Long la) return la == 0L;
            return Numbers.isZero(va);
        }
    }

    public static final class PosPNode extends Node {
        public final Node a;
        public PosPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            if (va instanceof Long la) return la > 0L;
            return Numbers.isPos(va);
        }
    }

    public static final class NegPNode extends Node {
        public final Node a;
        public NegPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override
        public boolean evalBoolean(Object[] frame) {
            Object va = a.eval(frame);
            if (va instanceof Long la) return la < 0L;
            return Numbers.isNeg(va);
        }
    }

    public static final class NilPNode extends Node {
        public final Node a;
        public NilPNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override public boolean evalBoolean(Object[] frame) { return a.eval(frame) == null; }
    }

    public static final class NotNode extends Node {
        public final Node a;
        public NotNode(Node a) { this.a = a; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override public boolean evalBoolean(Object[] frame) { return !a.evalBoolean(frame); }
    }

    public static final class IdenticalNode extends Node {
        public final Node a, b;
        public IdenticalNode(Node a, Node b) { this.a = a; this.b = b; }
        @Override public Object eval(Object[] frame) { return evalBoolean(frame); }
        @Override public boolean evalBoolean(Object[] frame) { return a.eval(frame) == b.eval(frame); }
    }

    // ========================================================================
    // Exceptions, Interop & Dispatch Nodes
    // ========================================================================

    public record CatchClause(Class<?> exClass, int slot, Node handler) {}

    public static final class TryNode extends Node {
        public final Node body;
        public final CatchClause[] catches;
        public final Node finallyNode;

        public TryNode(Node body, CatchClause[] catches, Node finallyNode) {
            this.body = body;
            this.catches = catches;
            this.finallyNode = finallyNode;
        }

        @Override
        public Object eval(Object[] frame) {
            try {
                return body.eval(frame);
            } catch (Throwable t) {
                for (CatchClause cc : catches) {
                    if (cc.exClass.isInstance(t)) {
                        frame[cc.slot] = t;
                        return cc.handler.eval(frame);
                    }
                }
                throw RT.sneakyThrow(t);
            } finally {
                if (finallyNode != null) {
                    finallyNode.eval(frame);
                }
            }
        }
    }

    public static final class ThrowNode extends Node {
        public final Node expr;
        public ThrowNode(Node expr) { this.expr = expr; }
        @Override
        public Object eval(Object[] frame) {
            Object ex = expr.eval(frame);
            if (ex instanceof Throwable t) throw RT.sneakyThrow(t);
            throw new RuntimeException(String.valueOf(ex));
        }
    }

    public static final class NewNode extends Node {
        public final Class<?> clazz;
        public final Node[] args;
        public NewNode(Class<?> clazz, Node[] args) { this.clazz = clazz; this.args = args; }
        @Override
        public Object eval(Object[] frame) {
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return Interop.invokeConstructor(clazz, evaluated);
        }
    }

    public static final class StaticFieldNode extends Node {
        public final Class<?> clazz;
        public final String fieldName;
        public StaticFieldNode(Class<?> clazz, String fieldName) {
            this.clazz = clazz;
            this.fieldName = fieldName;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.getStaticField(clazz, fieldName);
        }
    }

    public static final class StaticMethodNode extends Node {
        public final Class<?> clazz;
        public final String methodName;
        public final Node[] args;
        public StaticMethodNode(Class<?> clazz, String methodName, Node[] args) {
            this.clazz = clazz;
            this.methodName = methodName;
            this.args = args;
        }
        @Override
        public Object eval(Object[] frame) {
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return Interop.invokeStaticMethod(clazz, methodName, evaluated);
        }
    }

    public static final class InstanceFieldNode extends Node {
        public final Node target;
        public final String fieldName;
        public InstanceFieldNode(Node target, String fieldName) {
            this.target = target;
            this.fieldName = fieldName;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.getInstanceField(target.eval(frame), fieldName);
        }
    }

    public static final class InstanceMethodNode extends Node {
        public final Node target;
        public final String methodName;
        public final Node[] args;
        public InstanceMethodNode(Node target, String methodName, Node[] args) {
            this.target = target;
            this.methodName = methodName;
            this.args = args;
        }
        @Override
        public Object eval(Object[] frame) {
            Object t = target.eval(frame);
            Object[] evaluated = new Object[args.length];
            for (int i = 0; i < args.length; i++) evaluated[i] = args[i].eval(frame);
            return Interop.invokeInstanceMethod(t, methodName, evaluated);
        }
    }

    public static final class SetVarNode extends Node {
        public final Var var;
        public final Node val;
        public SetVarNode(Var var, Node val) { this.var = var; this.val = val; }
        @Override
        public Object eval(Object[] frame) {
            return var.set(val.eval(frame));
        }
    }

    public static final class SetFieldNode extends Node {
        public final Node target;
        public final String fieldName;
        public final Node val;
        public SetFieldNode(Node target, String fieldName, Node val) {
            this.target = target;
            this.fieldName = fieldName;
            this.val = val;
        }
        @Override
        public Object eval(Object[] frame) {
            return Interop.setInstanceField(target.eval(frame), fieldName, val.eval(frame));
        }
    }

    public static final class CaseNode extends Node {
        public final Node expr;
        public final Map<Object, Node> table;
        public final boolean hasDefault;
        public final Node defaultNode;

        public CaseNode(Node expr, Map<Object, Node> table, boolean hasDefault, Node defaultNode) {
            this.expr = expr;
            this.table = table;
            this.hasDefault = hasDefault;
            this.defaultNode = defaultNode;
        }

        @Override
        public Object eval(Object[] frame) {
            Object v = expr.eval(frame);
            Node match = table.get(v);
            if (match == null) {
                for (Map.Entry<Object, Node> e : table.entrySet()) {
                    if (RT.equiv(v, e.getKey())) {
                        match = e.getValue();
                        break;
                    }
                }
            }
            if (match != null) return match.eval(frame);
            if (hasDefault) return defaultNode.eval(frame);
            throw new IllegalArgumentException("No matching clause: " + v);
        }
    }
}
