package cljl;

/**
 * Zero-allocation O(1) lookup mapping all clojure.core symbol names to their
 * Core (0..10) or CoreExtras (11..21) subsystem ID for on-demand initialization.
 */
final class CoreExtrasIndex {
    static final int CORE_SUBSYSTEM_COUNT = 11;
    static final int SUBSYSTEM_COUNT = 11;

    static final Namespace.LazyLoader LOADER = new Namespace.LazyLoader() {
        @Override
        public boolean loadSymbol(Symbol sym) {
            int sub = subsystemOf(sym.name);
            if (sub < 0) return false;
            if (sub < CORE_SUBSYSTEM_COUNT) {
                Core.initSubsystem(sub);
            } else {
                CoreExtras.initSubsystem(sub - CORE_SUBSYSTEM_COUNT);
            }
            return true;
        }

        @Override
        public void loadAll() {
            Core.initAll(RT.CLOJURE_NS);
            CoreExtras.initAll(RT.CLOJURE_NS);
        }
    };

    private CoreExtrasIndex() {}

    static int subsystemOf(String name) {
        return switch (name) {
            // 0: Core.initPredicates (57 vars)
            case "nil?", "some?", "true?", "false?", "boolean?" -> 0;
            case "boolean", "not", "identical?", "=", "not=" -> 0;
            case "==", "compare", "hash", "number?", "integer?" -> 0;
            case "int?", "pos-int?", "nat-int?", "neg-int?", "float?" -> 0;
            case "double?", "ratio?", "decimal?", "rational?", "zero?" -> 0;
            case "pos?", "neg?", "even?", "odd?", "string?" -> 0;
            case "char?", "symbol?", "keyword?", "simple-symbol?", "qualified-symbol?" -> 0;
            case "simple-keyword?", "qualified-keyword?", "list?", "vector?", "map?" -> 0;
            case "set?", "seq?", "seqable?", "coll?", "associative?" -> 0;
            case "sequential?", "counted?", "ifn?", "fn?", "var?" -> 0;
            case "class?", "instance?", "isa?", "empty?", "reduced?" -> 0;
            case "realized?", "distinct?" -> 0;
            // 1: Core.initMath (24 vars)
            case "+", "+'", "-", "-'", "*" -> 1;
            case "*'", "/", "quot", "rem", "mod" -> 1;
            case "inc", "inc'", "dec", "dec'", "abs" -> 1;
            case "<", "<=", ">", ">=", "max" -> 1;
            case "min", "rand", "rand-int", "rand-nth" -> 1;
            // 2: Core.initBitwiseAndCasts (19 vars)
            case "bit-and", "bit-or", "bit-xor", "bit-not", "bit-and-not" -> 2;
            case "bit-shift-left", "bit-shift-right", "unsigned-bit-shift-right", "bit-test", "bit-set" -> 2;
            case "bit-clear", "bit-flip", "int", "long", "double" -> 2;
            case "float", "bigint", "bigdec", "char" -> 2;
            // 3: Core.initSequences (70 vars)
            case "seq", "first", "ffirst", "next", "nfirst" -> 3;
            case "fnext", "nnext", "rest", "second", "last" -> 3;
            case "butlast", "cons", "conj", "count", "empty" -> 3;
            case "nth", "nthnext", "nthrest", "range", "repeat" -> 3;
            case "replicate", "repeatedly", "iterate", "cycle", "take" -> 3;
            case "take-while", "take-nth", "take-last", "drop", "drop-while" -> 3;
            case "drop-last", "map", "mapv", "mapcat", "map-indexed" -> 3;
            case "keep", "keep-indexed", "filter", "remove", "filterv" -> 3;
            case "reduce", "reduce-kv", "reductions", "reduced", "unreduced" -> 3;
            case "ensure-reduced", "some", "every?", "not-every?", "not-any?" -> 3;
            case "concat", "interleave", "interpose", "partition", "partition-all" -> 3;
            case "partitionv", "partitionv-all", "partition-by", "split-at", "splitv-at" -> 3;
            case "split-with", "group-by", "frequencies", "distinct", "dedupe" -> 3;
            case "flatten", "tree-seq", "doall", "dorun", "run!" -> 3;
            // 4: Core.initCollections (43 vars)
            case "list", "list*", "vector", "vec", "hash-map" -> 4;
            case "array-map", "hash-set", "set", "into", "get" -> 4;
            case "get-in", "assoc", "assoc-in", "dissoc", "update" -> 4;
            case "update-in", "find", "contains?", "keys", "vals" -> 4;
            case "key", "val", "select-keys", "merge", "merge-with" -> 4;
            case "zipmap", "peek", "pop", "subvec", "disj" -> 4;
            case "reverse", "rseq", "sort", "sort-by", "shuffle" -> 4;
            case "transient", "persistent!", "__destructure-map", "conj!", "assoc!" -> 4;
            case "dissoc!", "pop!", "disj!" -> 4;
            // 5: Core.initStringsAndIO (32 vars)
            case "str", "subs", "format", "name", "namespace" -> 5;
            case "symbol", "keyword", "find-keyword", "gensym", "re-pattern" -> 5;
            case "re-matcher", "re-groups", "re-find", "re-matches", "re-seq" -> 5;
            case "pr-str", "prn-str", "print-str", "println-str", "pr" -> 5;
            case "prn", "print", "println", "printf", "newline" -> 5;
            case "flush", "read-line", "read", "read-string", "read+string" -> 5;
            case "slurp", "spit" -> 5;
            // 6: Core.initFunctional (12 vars)
            case "identity", "constantly", "complement", "apply", "partial" -> 6;
            case "comp", "juxt", "fnil", "memoize", "trampoline" -> 6;
            case "every-pred", "some-fn" -> 6;
            // 7: Core.initConcurrencyAndVars (33 vars)
            case "atom", "deref", "reset!", "reset-vals!", "compare-and-set!" -> 7;
            case "swap!", "swap-vals!", "volatile!", "vreset!", "vswap!" -> 7;
            case "volatile?", "delay?", "force", "promise", "deliver" -> 7;
            case "future-call", "future?", "future-done?", "future-cancel", "future-cancelled?" -> 7;
            case "pmap", "add-watch", "remove-watch", "set-validator!", "get-validator" -> 7;
            case "meta", "with-meta", "vary-meta", "var-get", "var-set" -> 7;
            case "alter-var-root", "bound?", "find-var" -> 7;
            // 8: Core.initNamespacesAndEval (30 vars)
            case "in-ns", "find-ns", "create-ns", "remove-ns", "all-ns" -> 8;
            case "ns-name", "ns-map", "ns-publics", "ns-aliases", "alias" -> 8;
            case "resolve", "refer", "import", "require", "use" -> 8;
            case "eval", "load-string", "load-file", "macroexpand-1", "macroexpand" -> 8;
            case "special-symbol?", "destructure", "seq-to-map-for-destructuring", "add-classpath", "unquote" -> 8;
            case "unquote-splicing", "methods", "get-method", "remove-method", "print-doc" -> 8;
            // 9: Core.initArraysAndExceptions (18 vars)
            case "to-array", "object-array", "int-array", "long-array", "double-array" -> 9;
            case "byte-array", "char-array", "boolean-array", "alength", "aget" -> 9;
            case "aset", "aclone", "class", "type", "ex-info" -> 9;
            case "ex-data", "ex-message", "ex-cause" -> 9;
            // 10: Core.initMacros (60 vars)
            case "defn", "defn-", "fn", "defonce", "declare" -> 10;
            case "let", "loop", "when", "when-not", "if-not" -> 10;
            case "when-let", "if-let", "when-some", "if-some", "when-first" -> 10;
            case "and", "or", "cond", "condp", "case" -> 10;
            case "->", "->>", "as->", "some->", "some->>" -> 10;
            case "cond->", "cond->>", "doto", "..", "lazy-seq*" -> 10;
            case "lazy-seq", "delay*", "delay", "future", "comment" -> 10;
            case "dotimes", "while", "doseq", "for", "push-thread-bindings" -> 10;
            case "pop-thread-bindings", "defmacro", "refer-clojure", "with-bindings*", "with-bindings" -> 10;
            case "with-loading-context*", "with-loading-context", "binding", "with-out-str", "with-in-str" -> 10;
            case "with-open", "time", "assert", "ns", "create-multi" -> 10;
            case "add-method!", "defmulti", "defmethod", "letfn", "doc" -> 10;
            // 11: CoreExtras.initDynamicVarsAndTables (3 vars)
            case "clojure-version", "char-escape-string", "char-name-string" -> 11;
            // 12: CoreExtras.initPredicatesAndCasts (21 vars)
            case "any?", "bytes?", "ident?", "simple-ident?", "qualified-ident?" -> 12;
            case "indexed?", "infinite?", "NaN?", "inst?", "uuid?" -> 12;
            case "uri?", "map-entry?", "record?", "reversible?", "sorted?" -> 12;
            case "cast", "bases", "supers", "bean", "StackTraceElement->vec" -> 12;
            case "Throwable->map" -> 12;
            // 13: CoreExtras.initMathAndParsing (50 vars)
            case "byte", "short", "unchecked-byte", "unchecked-short", "unchecked-int" -> 13;
            case "unchecked-long", "unchecked-char", "unchecked-float", "unchecked-double", "unchecked-add-int" -> 13;
            case "unchecked-subtract-int", "unchecked-multiply-int", "unchecked-inc-int", "unchecked-dec-int", "unchecked-negate-int" -> 13;
            case "unchecked-divide-int", "unchecked-remainder-int", "numerator", "denominator", "hash-combine" -> 13;
            case "mix-collection-hash", "hash-ordered-coll", "hash-unordered-coll", "munge", "namespace-munge" -> 13;
            case "Inst", "inst-ms*", "inst-ms", "num", "biginteger" -> 13;
            case "rationalize", "tagged-literal", "tagged-literal?", "reader-conditional", "reader-conditional?" -> 13;
            case "parse-boolean", "parse-long", "parse-double", "parse-uuid", "random-uuid" -> 13;
            case "unchecked-add", "unchecked-subtract", "unchecked-multiply", "unchecked-inc", "unchecked-dec" -> 13;
            case "unchecked-negate", "max-key", "min-key", "with-precision*", "with-precision" -> 13;
            // 14: CoreExtras.initArrays (23 vars)
            case "booleans", "bytes", "chars", "doubles", "floats" -> 14;
            case "ints", "longs", "shorts", "float-array", "short-array" -> 14;
            case "make-array", "into-array", "to-array-2d", "aset-boolean", "aset-byte" -> 14;
            case "aset-char", "aset-short", "aset-int", "aset-long", "aset-float" -> 14;
            case "aset-double", "amap", "areduce" -> 14;
            // 15: CoreExtras.initSortedCollections (28 vars)
            case "comparator", "sorted-map", "sorted-map-by", "sorted-set", "sorted-set-by" -> 15;
            case "subseq", "rsubseq", "vector-of", "create-struct", "defstruct" -> 15;
            case "struct", "struct-map", "accessor", "chunk-buffer", "chunk-append" -> 15;
            case "chunk", "chunk-cons", "chunk-first", "chunk-next", "chunk-rest" -> 15;
            case "chunked-seq?", "->ArrayChunk", "->VecNode", "->Vec", "->VecSeq" -> 15;
            case "->Eduction", "EMPTY-NODE", "ams", "mk-am", "__make-array-manager", "primitives-classnames" -> 15;
            // 16: CoreExtras.initSeqsAndMisc (21 vars)
            case "not-empty", "bounded-count", "lazy-cat", "line-seq", "file-seq" -> 16;
            case "enumeration-seq", "iterator-seq", "xml-seq", "resultset-seq", "seque" -> 16;
            case "iteration", "update-keys", "update-vals", "pcalls", "pvalues" -> 16;
            case "test", "memfn", "definline", "locking", "io!" -> 16;
            case "io!*" -> 16;
            // 17: CoreExtras.initTransducers (12 vars)
            case "completing", "transduce", "cat", "halt-when", "eduction" -> 17;
            case "sequence", "stream-seq!", "stream-reduce!", "stream-transduce!", "stream-into!" -> 17;
            case "replace", "random-sample" -> 17;
            // 18: CoreExtras.initHierarchiesAndMulti (10 vars)
            case "global-hierarchy", "make-hierarchy", "derive", "underive", "parents" -> 18;
            case "ancestors", "descendants", "prefer-method", "prefers", "remove-all-methods" -> 18;
            // 19: CoreExtras.initNamespacesAndVars (22 vars)
            case "the-ns", "ns-interns", "ns-refers", "ns-imports", "ns-resolve" -> 19;
            case "ns-unalias", "ns-unmap", "intern", "requiring-resolve", "loaded-libs" -> 19;
            case "load", "load-reader", "compile", "alter-meta!", "reset-meta!" -> 19;
            case "get-thread-bindings", "thread-bound?", "bound-fn*", "bound-fn", "with-local-vars" -> 19;
            case "with-redefs-fn", "with-redefs" -> 19;
            // 20: CoreExtras.initConcurrency (33 vars)
            case "add-tap", "remove-tap", "tap>", "agent", "send" -> 20;
            case "send-off", "send-via", "await", "await-for", "agent-error" -> 20;
            case "agent-errors", "clear-agent-errors", "await1", "restart-agent", "set-error-handler!" -> 20;
            case "error-handler", "set-error-mode!", "error-mode", "release-pending-sends", "shutdown-agents" -> 20;
            case "set-agent-send-executor!", "set-agent-send-off-executor!", "ref", "dosync*", "dosync" -> 20;
            case "sync", "ref-set", "alter", "commute", "ensure" -> 20;
            case "ref-history-count", "ref-min-history", "ref-max-history" -> 20;
            // 21: CoreExtras.initProtocolsAndTypes (38 vars)
            case "satisfies?", "extends?", "extenders", "find-protocol-impl", "find-protocol-method" -> 21;
            case "-cache-protocol-fn", "-reset-methods", "extend", "register-protocol*", "protocol-dispatch*", "make-protocol-fn*" -> 21;
            case "defprotocol", "extend-type", "extend-protocol", "register-record*", "register-record-class*", "register-record-impls*", "defrecord" -> 21;
            case "register-type*", "register-type-class*", "register-type-impls*", "deftype", "reify*", "reify", "definterface*" -> 21;
            case "definterface", "gen-interface", "gen-class", "get-proxy-class", "construct-proxy" -> 21;
            case "init-proxy", "update-proxy", "proxy-mappings", "proxy-name", "proxy-call-with-super" -> 21;
            case "method-sig", "print-method", "print-dup", "print-simple", "print-ctor" -> 21;
            case "PrintWriter-on", "proxy", "proxy-super" -> 21;
            default -> -1;
        };
    }
}
