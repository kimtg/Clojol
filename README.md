# Clojol (`cljl`)

A high-performance, fast-startup implementation of Clojure 1.12 in pure Java (Java 21+), with zero external dependencies, real `clojure.lang.*` JVM binary ABI compatibility, AOT compilation (`compile` & `gen-class`), full `clojure.core` public API parity, and **98.1%** pass rate across runnable official Clojure 1.12.0 conformance tests.

Clojol is an independent, clean-room implementation of the Clojure language and is not affiliated with or endorsed by Rich Hickey or Nubank.

## Why Clojol?

- Faster Startup
- Faster Numeric Computation
- Easier Build

## Performance

### `fib(40)` vs. C (`gcc -O3` / `clang -O3`) & Clojure 1.12.6

Recursive `fib(40)` benchmark ([`test/fib_bench.clj`](test/fib_bench.clj)) measured back-to-back on Windows (median of 5 trials, OpenJDK 25.0.4.1 LTS Temurin, `gcc 14.2.0 -O3`, `clang 19.1.7 -O3`, Clojure 1.12.6), comparing **Clojol** (`-XX:AOTCache=build/cljl.aot -jar build/cljl.jar test/fib_bench.clj`) against standard recursive `int64_t fib(int64_t n)` in C (with `volatile int64_t arg = 40` so C compilers cannot hoist `fib(40)` out of the timing loop) and **Clojure 1.12.6** (`clojure.main test/fib_bench.clj`):

| Implementation | Run 1 | Run 2 | Run 3 | Total Wall Time (`×3` + startup) | vs. `gcc -O3` (Run 3) | vs. Clojure 1.12.6 (Run 3) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **Clojol (`cljl`, Auto-Memo)** | **4.72 ms** | **0.009 ms** | **0.003 ms** | **0.13 s** | **~44,000x faster** | **~204,000x faster** |
| **Clojol (`cljl`, `-Dcljl.jit.automemo=false`, Frontier CSE)** | **4.28 ms** | **0.042 ms** | **0.019 ms** | **0.13 s** | **~6,900x faster** | **~32,000x faster** |
| C (`gcc 14.2.0 -O3`) | 140.3 ms | 142.0 ms | 131.9 ms | 0.41 s | 1.00x | 4.6x faster |
| C (`clang 19.1.7 -O3`) | 190.7 ms | 191.5 ms | 191.7 ms | 0.58 s | 0.69x | 3.2x faster |
| **Clojol (`cljl`, `-Dcljl.jit.automemo=false -Dcljl.jit.cse=false`)** | **204.3 ms** | **195.6 ms** | **194.7 ms** | **0.72 s** | **0.68x** | **3.1x faster** |
| Clojure 1.12.6 | 627.4 ms | 612.4 ms | 611.8 ms | 2.45 s | 0.22x | 1.00x |

With type inference (`TypeAnalyzer.java`) and primitive-specialized Tier-1 bytecode compilation (`BytecodeJIT.java`), Clojol infers unboxed `long` parameters/returns with no user type hints required, compiles pure zero-capture self-recursive functions into `private static` `invokestatic` workers with a hoisted entry `Var` guard (`with-redefs`-safe), transforms single-recursion associative `+`/`*` functions into `O(1)`-stack accumulator loops (disable via `-Dcljl.jit.tco=false`), and applies **compiler-verified auto-memoization** (`PureMemo.java`, JMM-sound lock-free bounded `AtomicLongArray` / `SoftReference`-bounded caches; disable via `-Dcljl.jit.automemo=false` to fall back to 2-step frontier CSE, and disable frontier CSE via `-Dcljl.jit.cse=false`) to strictly pure multi-branch self-recursive functions (`fib(77)` = `5527939700884757` in **0.005 ms** warm / **22.6 ms** cold, `fib(92)` in **0.005 ms**, `fib(93)` exact `ArithmeticException`, auto-promoting `+'` `fib-big(200)` in **2.7 ms**, `trib(60)` / `choose(60, 30)` / `tak(24, 16, 8)` in **2.0–3.8 ms**). Auto-memoization uses a strict allowlist of pure AST nodes, is guarded against core `with-redefs`, and never affects functions with side effects (e.g. [`test/fib_impure_bench.clj`](test/fib_impure_bench.clj)). End-to-end (`fib(40)` × 3 + JVM startup), Clojol finishes in **0.13 s** (**18.7x faster** than Clojure 1.12.6's **2.45 s**). Note that `gcc -O3` and `clang -O3` do not perform recursive auto-memoization or pure-call CSE (converting the right branch into an accumulator loop and still executing `O(φ^n)` calls); with both auto-memoization and frontier CSE disabled (`-Dcljl.jit.automemo=false -Dcljl.jit.cse=false`), Clojol hoists its proven overflow-free range guard once at entry (falling back to exact `Math.addExact` outside the safe bound) and executes the full unboxed `O(φ^40)` recursive tree with zero user type hints at **0.68x** of `gcc -O3`, matching `clang -O3` (**0.98x**), and **3.1x faster** than Clojure 1.12.6. Tight unboxed `loop`/`recur` microbenchmarks (`sum-loop(1,000,000)` in [`test/bench.clj`](test/bench.clj)) run in **~0.42 ms**.

### `loop/recur`, `volatile!`, and `atom` (vs. Clojure 1.12.6)

`1,000,000` iterations incrementing a counter (median of 7 runs after warmup, OpenJDK 25.0.4.1 on Windows):

| Benchmark (`N = 1,000,000`) | Clojol (`cljl`) | Clojure 1.12.6 | Speedup |
| :--- | :---: | :---: | :---: |
| `loop/recur` | **0.13 ms** | 0.18 ms | **1.36x faster** |
| `volatile!` (local `vswap!`) | **0.13 ms** | 8.00 ms | **59.7x faster** |
| `volatile!` (returned/tail-escaping `vswap!`) | **0.13 ms** | 6.46 ms | **48.6x faster** |
| `volatile!` (escaping param `vswap!`) | **0.55 ms** | 8.67 ms | **15.8x faster** |
| `atom` (local `while` / `loop` `swap!`) | **0.35 ms** | 11.51 ms | **32.9x faster** |
| `atom` (returned/tail-escaping `swap!`) | **0.30 ms** | 11.40 ms | **37.6x faster** |
| `atom` (escaping param `swap!`, lock-free atomic) | **8.08 ms** | 13.55 ms | **1.68x faster** |
| `atom` (4-thread contended `4×250k` `swap!`) | **33.91 ms** | 104.54 ms | **3.08x faster** |

`BytecodeJIT.java` eliminates redundant overflow checks on range-bounded `long` loop counters (`(< i n)` -> `(inc i)`), scalar-replaces non-escaping and tail-escaping local `volatile!` and `atom` bindings into unboxed JVM locals (deferring allocation until return when needed), compiles escaping `vswap!`/`vreset!` into fused unboxed RMW ops backed by a single-field 63-bit fast-long `VarHandle` acquire/release path in `Atom.Volatile` (pass `-Dcljl.volatile.strict=true` for full `volatile` fence semantics), and uses a JMM-sound wait-free 63-bit `VarHandle.getAndAdd` (`lock xadd`) and CAS path in `Atom` that eliminates `Long` allocation and GC write barriers on numeric `swap!`/`reset!` across all threads.

### Collections, Transducers, Protocols, Strings & `Math` (vs. Clojure 1.12.6)

Median of 5 runs after warmup on OpenJDK 25.0.4.1 (Windows):

| Benchmark | Clojol (`cljl`) | Clojure 1.12.6 | Relative |
| :--- | :---: | :---: | :---: |
| `(Math/sin 0)` (`1M` iterations) | **0.005 ms** | 2.09 ms | **~420x faster** |
| `sort-by :k` (`50k` maps) | **1.31 ms** | 8.92 ms | **6.8x faster** |
| `(->> (range 100k) (map inc) (filter even?) (reduce + 0))` | **0.38 ms** | 1.42 ms | **3.8x faster** |
| `str/join "," (range 20k)` | **0.37 ms** | 1.11 ms | **3.0x faster** |
| `sort` (`50k` reversed vec) | **0.69 ms** | 1.48 ms | **2.2x faster** |
| `vec` transient `conj!` (`100k`) | **0.43 ms** | 0.87 ms | **2.0x faster** |
| `into [] (comp (map inc) (filter even?)) (range 100k)` | **0.51 ms** | 0.95 ms | **1.9x faster** |
| `str/split` (`20k` CSV) | **0.98 ms** | 1.79 ms | **1.8x faster** |
| `transduce (comp (map inc) (filter even?)) + 0 (range 100k)` | **0.21 ms** | 0.34 ms | **1.6x faster** |
| `defprotocol` bimorphic dispatch (`500k`, 2 records) | **1.35 ms** | 1.90 ms | **1.4x faster** |
| `frequencies` (`50k`) | **1.83 ms** | 2.21 ms | **1.2x faster** |
| `map` `get` (`50k` keywords) | **1.66 ms** | 1.97 ms | **1.2x faster** |
| `defprotocol` dispatch (`500k`, `defrecord` var) | **1.16 ms** | 1.24 ms | **1.1x faster** |

Driven by compile-time pure `Math`/`StrictMath` constant folding, `LongRange` + `IReduceInit` + chunked lazy pipelines, unboxed `Fn` primitive dispatch hooks (`invokePrim_*`), adaptive radix/Schwartzian `FastSort` with `LongArraySeq`/`ArraySeq`, `private static final` JIT constant fields + single-load `Var.fastRoot`, 32-way `ArrayNode` + iterative HAMT traversal and C2-inlineable `RT.get`/`RT.getKeyword` + O(1) `String` hashing, primitive `defrecord` fields, and generated JVM protocol interfaces with out-of-line 2-entry PIC fallbacks.

### Cold Startup & AOT Cache Impact

Cold startup + eval (`java ... -jar build/cljl.jar -Srepro -Sdeps "{}" -e "(+ 1 2)"`) measured across 8 runs on OpenJDK 25.0.4.1 (Windows):

| Mode | Min | Median | Average |
| :--- | :---: | :---: | :---: |
| **Clojol — AOT Cache (`-XX:AOTCache=build/cljl.aot`, JDK 24+)** | 68.2 ms | **69.5 ms** | 69.4 ms |
| **Clojol — Default JDK CDS (`java -jar build/cljl.jar`)** | 125.3 ms | **127.4 ms** | 127.4 ms |
| **Clojol — No CDS (`-Xshare:off`)** | 161.6 ms | **164.5 ms** | 166.4 ms |
| **Clojure 1.12.6 (`clojure.main -e "(+ 1 2)"`)** | 434.7 ms | **439.0 ms** | 441.6 ms |

Through lazy on-demand `Core`/`CoreExtras` subsystem initialization, lazy `HttpClient` initialization in `Deps`, `tableswitch` dispatchers, zero-JCA inline SHA-256, and JDK 24+ AOT Cache (`build/cljl.aot`, with automatic `build/cljl.jsa` AppCDS fallback on JDK 21–23), Clojol achieves **~70 ms** cold startup (**6.3x faster** than Clojure 1.12.6 and **~45% faster** than default JDK CDS). Startup loads only **1,283** classes (**1,281** memory-mapped directly from `build/cljl.aot` and **0** loaded from `cljl.jar`). Note that `-J` / `:jvm-opts` relaunches and lazily loaded `StdLib` namespaces fall back to loading from `cljl.jar`.

## Requirements

- **JDK 21 or newer** (`javac`, `java`, `jar` on `PATH`) — tested on OpenJDK 25.0.4.1.

## Quick Start

Both `cljl.bat` (Windows) and `cljl.sh` (Linux/macOS) automatically compile `src/` into `build/cljl.jar` and generate `build/cljl.aot` (JDK 24+ AOT cache) or `build/cljl.jsa` (JDK 21–23 AppCDS archive) on first run.

### Interactive REPL

```bash
# Run the jar file without AOT cache / AppCDS
java -jar build/cljl.jar

# Windows
.\cljl.bat

# Linux / macOS
chmod +x cljl.sh
./cljl.sh
```

Supports multi-line balanced forms, thread-local `*1`, `*2`, `*3`, `*e`, `*ns*`, SIGINT handling, command history, symbol completion, and EOF.

### Evaluate an Expression

```bash
./cljl.sh -e "(reduce + (map #(* % %) (range 1 11)))"
```

### Run a Script

```bash
./cljl.sh path/to/script.clj arg1 arg2
```

Script arguments are bound to `*command-line-args*`. Pass `-` to read and execute from `stdin`.

### CLI Options (`clj` 1.12.6.1673 Compatible)

`cljl` supports the standard Clojure CLI modes (`-A`, `-X`, `-M`, `-T`, `-P`), `clj-opts`, `deps.clj` flags (`-Sdeps-file`, `-Scommand`), `clojure.main` init/main flags, and `:deps` alias tools:

```bash
./cljl.sh [clj-opt*] [-Aaliases]                       # Start REPL
./cljl.sh [clj-opt*] -X[aliases] [a/fn*] [kpath v]*    # Exec function(s)
./cljl.sh [clj-opt*] -M[aliases] [init-opt*] [main-opt] [arg*]  # Run main
./cljl.sh [clj-opt*] -T[name|aliases] a/fn [kpath v] kv-map?    # Run tool
./cljl.sh [clj-opt*] -P [other exec opts]              # Prepare deps only
```

| Category | Flag | Description |
| :--- | :--- | :--- |
| **Exec** | `-Aaliases` | Use concatenated aliases to modify classpath |
| | `-X[aliases]` | Modify classpath and invoke `a/fn` (or `:exec-fn`) with EDN key/value args |
| | `-M[aliases]` | Modify classpath and supply `:main-opts` to `clojure.main` |
| | `-T[name\|aliases]` | Run tool (isolates project `:paths`/`:deps`; loads `~/.clojure/tools/<name>.edn` if named) |
| | `-P` | Prepare deps — resolve/download libs and cache classpath without executing |
| **JVM / Deps** | `-Jopt` | Pass JVM option through (e.g. `-J-Xmx512m`, `-J-Dprop=val`) |
| | `-Sdeps EDN` | EDN map string or file merged last into deps config |
| | `-Sdeps-file FILE` | Use custom file instead of `deps.edn` (`deps.clj` option) |
| | `-Spath` | Compute classpath and print to stdout only |
| | `-Stree` | Print dependency tree and exit |
| | `-Scp CP` | Use given classpath directly without computing or caching |
| | `-Srepro` | Ignore `~/.clojure/deps.edn` config file |
| | `-Sforce` | Force classpath recomputation (bypass `.cpcache`) |
| | `-Sverbose` | Print resolved config/cache paths and settings |
| | `-Sthreads N` | Set number of download/resolution threads |
| | `-Strace` | Write `trace.edn` tracing dependency expansion and basis |
| | `-Scommand CMD` | Invoke custom shell command with `{{classpath}}` and `{{main-opts}}` substitutions |
| | `--` | Stop parsing `clj` options and pass remaining args to `clojure.main` |
| | `-version`, `--version` | Print Clojol & Clojure CLI version and exit |
| **Init (`clojure.main`)** | `-i`, `--init PATH` | Load a Clojure file or classpath resource |
| | `-e`, `--eval EXPR` | Evaluate exprs in string and print non-nil values |
| | `--report TARGET` | Report uncaught exceptions to `"file"` (default), `"stderr"`, or `"none"` |
| **Main (`clojure.main`)** | `-m`, `--main NS` | Call `-main` in namespace `NS` with remaining args |
| | `-r`, `--repl` | Run an interactive REPL |
| | `PATH` | Run a script from a file or classpath resource |
| | `-` | Run a script from standard input (`stdin`) |
| | `-h`, `-?`, `--help` | Print full CLI help message and exit |

**Built-in `:deps` alias programs** (`clojure.tools.deps.cli.api`):

- `./cljl.sh -X:deps aliases` — list available aliases and their sources
- `./cljl.sh -X:deps list` — list full transitive deps set and licenses
- `./cljl.sh -X:deps tree` (supports `:format :edn`) — print dependency tree
- `./cljl.sh -X:deps basis` — print runtime basis EDN
- `./cljl.sh -X:deps find-versions :lib group/art` (or `:git/url ...`) — list available Maven/Git versions
- `./cljl.sh -X:deps prep` — run `:deps/prep-lib` steps for unprepped dependencies
- `./cljl.sh -X:deps mvn-pom` — generate `pom.xml` with resolved Maven deps
- `./cljl.sh -X:deps mvn-install :jar '"path/to/lib.jar"'` — install jar/pom into local Maven repo (`$M2_REPO` / `~/.m2/repository`)
- `./cljl.sh -X:deps git-resolve-tags` — resolve `:git/tag` coords to `:git/sha` in `deps.edn`

Dependency resolution implements BFS nearest-then-newest Maven mediation, Maven version ranges, BOM imports (`<scope>import</scope>`), parent POM inheritance, `~/.m2/settings.xml` mirrors/credentials, and live basis refresh (`clojure.java.basis` + `clojure.repl.deps/add-libs`). Environment variables `$CLJ_CONFIG`, `$CLJ_CACHE`, `$M2_REPO`, `$GITLIBS`, and `CLJL_OPTS` (extra JVM flags) are also supported.

## Language Features

- **Reader**: Lists, vectors, maps, sets, metadata (`^` / `#^`), quote (`'`), syntax-quote (`` ` ``), unquote (`~`), unquote-splicing (`~@`), auto-gensyms (`x#`), anonymous fn literals (`#(...)`), regexes (`#"..."`), discard (`#_`), var-quote (`#'`), deref (`@`), read-eval (`#=(...)`), namespaced maps (`#:ns{}` / `#::{}`), reader conditionals (`#?()` / `#?@()`, `{:read-cond :preserve}` / `reader-conditional`), tagged literals (`#uuid`, `#inst`, `tagged-literal`, `*data-readers*`, `*default-data-reader-fn*`), `read+string`, `*read-eval*`, `*suppress-read*`, `*reader-resolver*`, symbolic values (`##Inf`, `##-Inf`, `##NaN`), ratios (`3/2`), BigInts (`42N`), BigDecimals (`3.5M`), hex/octal/binary/radix numbers.
- **Persistent Data Structures & Chunks**:
  - `PersistentVector` & primitive-backed `clojure.core.Vec` (32-way bit-partitioned trie + tail buffer + `TransientVector`, `APersistentVector$RSeq`, `Reversible`, `vector-of`, `->Vec`, `->VecNode`, `->VecSeq`)
  - Chunked sequences (`chunk-buffer`, `chunk-append`, `chunk`, `chunk-cons`, `chunk-first`, `chunk-next`, `chunk-rest`, `chunked-seq?`, `->ArrayChunk`)
  - `PersistentHashMap` & `PersistentHashSet` (32-way HAMT + `MapEntry` + transients + `EMPTY-NODE`)
  - `PersistentTreeMap` & `PersistentTreeSet` (`sorted-map`, `sorted-map-by`, `sorted-set`, `sorted-set-by`, `subseq`, `rsubseq`, `rseq`, `comparator`)
  - `PersistentRecord` (`defrecord`), `PersistentStructMap` (`create-struct`, `defstruct`, `struct`, `struct-map`, `accessor`), `PersistentQueue`, and `PersistentList` / `LazySeq`
  - Contrib structures: `priority-map`, `ordered-map`, `ordered-set`, `ordering-map`, `int-map`, `int-set`, `dense-int-set`
- **Transducers, Sequences & Streams**: `transduce`, `completing`, `cat`, `halt-when`, `eduction` / `->Eduction`, `sequence`, transducer arities (`map`, `filter`, `remove`, `take`, `take-while`, `take-nth`, `drop`, `drop-while`, `keep`, `keep-indexed`, `map-indexed`, `mapcat`, `distinct`, `dedupe`, `interpose`, `partition-all`, `partitionv-all`, `partition-by`, `replace`, `random-sample`), Clojure 1.12 vector/stream ops (`partitionv`, `partitionv-all`, `splitv-at`, `replicate`, `stream-seq!`, `stream-reduce!`, `stream-transduce!`, `stream-into!`), `lazy-cat`, `not-empty`, `bounded-count`, `line-seq`, `file-seq`, `xml-seq`, `resultset-seq`, `enumeration-seq`, `iterator-seq`, `seque`, `iteration`, `update-keys`, `update-vals`.
- **Special Forms, Binding & Macros**: `def`, `defn`, `defn-`, `defmacro`, `definline`, `defonce`, `declare`, `special-symbol?`, `destructure`, `seq-to-map-for-destructuring`, `refer-clojure`, `fn`, `let`, `loop`, `recur`, `if`, `if-not`, `if-let`, `if-some`, `when`, `when-not`, `when-let`, `when-some`, `when-first`, `cond`, `condp`, `case`, `and`, `or`, `->`, `->>`, `as->`, `some->`, `some->>`, `cond->`, `cond->>`, `doto`, `for`, `doseq`, `dotimes`, `while`, `letfn`, `try`/`catch`/`finally`/`throw`, `monitor-enter`/`monitor-exit`/`locking`, `with-open`, `with-out-str`, `with-in-str`, `binding`, `with-bindings`, `with-bindings*`, `with-loading-context`, `bound-fn`, `bound-fn*`, `with-local-vars`, `with-redefs`, `with-redefs-fn`, `lazy-seq`, `delay`, `future`, `comment`, `ns`, `time`, `assert` (`*assert*`), `memfn`, `io!`.
- **Protocols, Types, AOT, Proxy, Printing & Multimethods**: `defprotocol`, `extend`, `extend-type`, `extend-protocol`, `extends?`, `extenders`, `satisfies?`, `find-protocol-impl`, `find-protocol-method`, `-cache-protocol-fn`, `-reset-methods`, `defrecord`, `deftype`, `reify`, `definterface`, `gen-interface`, full AOT compilation (`compile` emitting `<ns>__init.class` + complete `gen-class`), `SourceFile`/`LineNumberTable` JVM bytecode debug info, `proxy` (`get-proxy-class`, `construct-proxy`, `init-proxy`, `update-proxy`, `proxy-mappings`, `proxy-super`, `proxy-name`, `proxy-call-with-super`, `method-sig`), hierarchies (`make-hierarchy`, `derive`, `underive`, `parents`, `ancestors`, `descendants`, `isa?`), multimethods (`defmulti`, `defmethod`, `prefer-method`, `prefers`, `remove-all-methods`), and printing extensible hooks (`print-method`, `print-dup`, `print-simple`, `print-ctor`, `PrintWriter-on`, `*flush-on-newline*`, `*print-namespace-maps*`).
- **State & Concurrency**: `atom` (`swap!`, `reset!`, `compare-and-set!`, watches, validators, metadata), `agent` (`send`, `send-off`, `send-via`, `await`, `await1`, `await-for`, `agent-error`, `agent-errors`, `clear-agent-errors`, `restart-agent`, `set-error-handler!`, `set-error-mode!`, `release-pending-sends`, `shutdown-agents`), STM `ref` (`dosync`, `sync`, `alter`, `commute`, `ref-set`, `ensure`, barging & livelock prevention, history controls), `tap>`/`add-tap`/`remove-tap`, `volatile!`, `delay`/`force`, `promise`/`deliver`, `future`, `pmap`, `pcalls`, `pvalues`.
- **Numerics, Hashing, Arrays & Parsing**: `parse-boolean`, `parse-long`, `parse-double`, `parse-uuid`, `random-uuid`, `numerator`, `denominator`, `rationalize`, `biginteger`, `byte`, `short`, `num`, `unchecked-add`/`-int`, `unchecked-subtract`/`-int`, `unchecked-multiply`/`-int`, `unchecked-divide-int`, `unchecked-remainder-int`, `unchecked-inc`/`-int`, `unchecked-dec`/`-int`, `unchecked-negate`/`-int`, `with-precision` (`*math-context*`), `max-key`, `min-key`, `Inst`/`inst-ms`/`inst-ms*`, `hash-combine`, `mix-collection-hash`, `hash-ordered-coll`, `hash-unordered-coll`, `munge`, `namespace-munge`, typed arrays (`booleans`..`doubles`, `boolean-array`..`double-array`, `make-array`, `into-array`, `to-array-2d`, `aset-*`, `amap`, `areduce`, `primitives-classnames`).
- **Java Interop & JVM Binary ABI**: `(ClassName. args...)`, `(new ClassName args...)`, `(.method obj args...)`, `(.-field obj)`, `(ClassName/staticMethod args...)`, `ClassName/STATIC_FIELD`, Clojure 1.12 qualified method values (`Class/method`, `Class/.method`, `Class/new`, `:param-tags`), array class syntax & printing (`String/1`, `long/2`), SAM functional-interface adaptation, `IDeref` implementing Java `Supplier` interfaces, real `clojure.lang.*` JVM classes & interfaces (`RT`, `Var`, `IFn` + `IFn$*`, `AFn`, `AFunction`, `RestFn`, `ISeq`, `IPersistent*`, `ExceptionInfo`, `ArityException`, `DynamicClassLoader`, `Reflector`, `Compiler`, etc.), `clojure.java.api.Clojure`, `(set! ...)`, `(.. a b c)`, `(import ...)`, `add-classpath`, `bases`, `supers`, `bean`, `StackTraceElement->vec`, `Throwable->map`, `ex-cause`, `ex-message`.
- **Standard Library & Contrib Namespaces**:
  - Core & JDK: `clojure.core`, `clojure.core.protocols`, `clojure.core.reducers`, `clojure.core.server` (Socket REPL & `prepl`), `clojure.math`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.instant`, `clojure.inspector`, `clojure.reflect`, `clojure.stacktrace`, `clojure.template`, `clojure.uuid`, `clojure.java.basis`, `clojure.java.io`, `clojure.java.process`, `clojure.java.shell`, `clojure.java.browse`, `clojure.java.javadoc`, `clojure.main`, `clojure.tools.deps.cli.api`, `clojure.repl.deps`, `clojure.data`, `clojure.datafy`, `clojure.pprint`, `clojure.repl` (`doc`, `find-doc`, `apropos`, `dir`, `source`, `pst`), `clojure.test` (+ `clojure.test.junit`, `clojure.test.tap`), `clojure.xml`, `clojure.zip`, `clojure.spec.alpha`
  - Contrib-compatible: `clojure.core.async`, `clojure.data.priority-map`, `clojure.data.int-map`, `clojure.data.avl`, `flatland.ordered.map`, `flatland.ordered.set`, `flatland.useful.map`

## Building, Tests & Benchmarks

The strict-JIT test suite runs **11,444 assertions** across `core_test.clj`, `cheatsheet_test.clj`, and `cli_test.clj` (0 failures, 0 errors), and Clojol passes **98.1%** (4,460 / 4,545 assertions) across 47 runnable namespaces from the official Clojure 1.12.0 test suite (`clojure.test-clojure.*`).

```bash
# Windows
.\make.bat            # Compile, package build\cljl.jar, generate AOT cache / AppCDS archive
.\make.bat rebuild    # Clean and build from scratch
.\make.bat test       # Build and run core_test.clj, cheatsheet_test.clj & cli_test.clj
.\make.bat bench      # Build and run test\bench.clj
.\cljl.bat test\fib_bench.clj  # Run recursive fib(40) benchmark
.\make.bat clean      # Remove build\

# Linux / macOS
chmod +x make.sh cljl.sh
./make.sh             # Compile, package build/cljl.jar, generate AOT cache / AppCDS archive
./make.sh rebuild     # Clean and build from scratch
./make.sh test        # Build and run core_test.clj, cheatsheet_test.clj & cli_test.clj
./make.sh bench       # Build and run test/bench.clj
./cljl.sh test/fib_bench.clj   # Run recursive fib(40) benchmark
./make.sh clean       # Remove build/
```
