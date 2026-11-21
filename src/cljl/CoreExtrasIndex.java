package cljl;

/**
 * Zero-allocation O(1) lookup mapping clojure.core extended symbol names to
 * their CoreExtras subsystem ID for on-demand initialization.
 */
final class CoreExtrasIndex {
    static final int SUBSYSTEM_COUNT = 11;

    private CoreExtrasIndex() {}

    static int subsystemOf(String name) {
        return switch (name) {
            // 0: initDynamicVarsAndTables (3 vars)
            case "clojure-version", "char-escape-string", "char-name-string" -> 0;
            // 1: initPredicatesAndCasts (21 vars)
            case "any?", "bytes?", "ident?", "simple-ident?", "qualified-ident?" -> 1;
            case "indexed?", "infinite?", "NaN?", "inst?", "uuid?" -> 1;
            case "uri?", "map-entry?", "record?", "reversible?", "sorted?" -> 1;
            case "cast", "bases", "supers", "bean", "StackTraceElement->vec" -> 1;
            case "Throwable->map" -> 1;
            // 2: initMathAndParsing (50 vars)
            case "byte", "short", "unchecked-byte", "unchecked-short", "unchecked-int" -> 2;
            case "unchecked-long", "unchecked-char", "unchecked-float", "unchecked-double", "unchecked-add-int" -> 2;
            case "unchecked-subtract-int", "unchecked-multiply-int", "unchecked-inc-int", "unchecked-dec-int", "unchecked-negate-int" -> 2;
            case "unchecked-divide-int", "unchecked-remainder-int", "numerator", "denominator", "hash-combine" -> 2;
            case "mix-collection-hash", "hash-ordered-coll", "hash-unordered-coll", "munge", "namespace-munge" -> 2;
            case "Inst", "inst-ms*", "inst-ms", "num", "biginteger" -> 2;
            case "rationalize", "tagged-literal", "tagged-literal?", "reader-conditional", "reader-conditional?" -> 2;
            case "parse-boolean", "parse-long", "parse-double", "parse-uuid", "random-uuid" -> 2;
            case "unchecked-add", "unchecked-subtract", "unchecked-multiply", "unchecked-inc", "unchecked-dec" -> 2;
            case "unchecked-negate", "max-key", "min-key", "with-precision*", "with-precision" -> 2;
            // 3: initArrays (23 vars)
            case "booleans", "bytes", "chars", "doubles", "floats" -> 3;
            case "ints", "longs", "shorts", "float-array", "short-array" -> 3;
            case "make-array", "into-array", "to-array-2d", "aset-boolean", "aset-byte" -> 3;
            case "aset-char", "aset-short", "aset-int", "aset-long", "aset-float" -> 3;
            case "aset-double", "amap", "areduce" -> 3;
            // 4: initSortedCollections (28 vars)
            case "comparator", "sorted-map", "sorted-map-by", "sorted-set", "sorted-set-by" -> 4;
            case "subseq", "rsubseq", "vector-of", "create-struct", "defstruct" -> 4;
            case "struct", "struct-map", "accessor", "chunk-buffer", "chunk-append" -> 4;
            case "chunk", "chunk-cons", "chunk-first", "chunk-next", "chunk-rest" -> 4;
            case "chunked-seq?", "->ArrayChunk", "->VecNode", "->Vec", "->VecSeq" -> 4;
            case "->Eduction", "EMPTY-NODE", "primitives-classnames" -> 4;
            // 5: initSeqsAndMisc (21 vars)
            case "not-empty", "bounded-count", "lazy-cat", "line-seq", "file-seq" -> 5;
            case "enumeration-seq", "iterator-seq", "xml-seq", "resultset-seq", "seque" -> 5;
            case "iteration", "update-keys", "update-vals", "pcalls", "pvalues" -> 5;
            case "test", "memfn", "definline", "locking", "io!" -> 5;
            case "io!*" -> 5;
            // 6: initTransducers (12 vars)
            case "completing", "transduce", "cat", "halt-when", "eduction" -> 6;
            case "sequence", "stream-seq!", "stream-reduce!", "stream-transduce!", "stream-into!" -> 6;
            case "replace", "random-sample" -> 6;
            // 7: initHierarchiesAndMulti (10 vars)
            case "global-hierarchy", "make-hierarchy", "derive", "underive", "parents" -> 7;
            case "ancestors", "descendants", "prefer-method", "prefers", "remove-all-methods" -> 7;
            // 8: initNamespacesAndVars (22 vars)
            case "the-ns", "ns-interns", "ns-refers", "ns-imports", "ns-resolve" -> 8;
            case "ns-unalias", "ns-unmap", "intern", "requiring-resolve", "loaded-libs" -> 8;
            case "load", "load-reader", "compile", "alter-meta!", "reset-meta!" -> 8;
            case "get-thread-bindings", "thread-bound?", "bound-fn*", "bound-fn", "with-local-vars" -> 8;
            case "with-redefs-fn", "with-redefs" -> 8;
            // 9: initConcurrency (33 vars)
            case "add-tap", "remove-tap", "tap>", "agent", "send" -> 9;
            case "send-off", "send-via", "await", "await-for", "agent-error" -> 9;
            case "agent-errors", "clear-agent-errors", "await1", "restart-agent", "set-error-handler!" -> 9;
            case "error-handler", "set-error-mode!", "error-mode", "release-pending-sends", "shutdown-agents" -> 9;
            case "set-agent-send-executor!", "set-agent-send-off-executor!", "ref", "dosync*", "dosync" -> 9;
            case "sync", "ref-set", "alter", "commute", "ensure" -> 9;
            case "ref-history-count", "ref-min-history", "ref-max-history" -> 9;
            // 10: initProtocolsAndTypes (38 vars)
            case "satisfies?", "extends?", "extenders", "find-protocol-impl", "find-protocol-method" -> 10;
            case "-cache-protocol-fn", "-reset-methods", "extend", "register-protocol*", "protocol-dispatch*" -> 10;
            case "defprotocol", "extend-type", "extend-protocol", "register-record*", "defrecord" -> 10;
            case "register-type*", "deftype", "reify*", "reify", "definterface*" -> 10;
            case "definterface", "gen-interface", "gen-class", "get-proxy-class", "construct-proxy" -> 10;
            case "init-proxy", "update-proxy", "proxy-mappings", "proxy-name", "proxy-call-with-super" -> 10;
            case "method-sig", "print-method", "print-dup", "print-simple", "print-ctor" -> 10;
            case "PrintWriter-on", "proxy", "proxy-super" -> 10;
            default -> -1;
        };
    }
}
