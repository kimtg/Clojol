# Clojol (`cljl`)

A high-performance, fast-startup implementation of Clojure in pure Java (Java 21+), with zero external dependencies, full Clojure 1.12 `clojure.core` public API parity, and broad [Clojure Cheatsheet](https://clojure.org/api/cheatsheet) compatibility.

Clojol is an independent, clean-room implementation of the Clojure language and is not affiliated with or endorsed by Rich Hickey or Nubank.

## Why Clojol?

- Faster Startup
- Faster Numeric Computation with Type Inference
- Easier Build

## Performance (`fib(40)` vs. Official Clojure)

Recursive `fib(40)` benchmark ([`test/fib_bench.clj`](test/fib_bench.clj)) measured on OpenJDK 25.0.4.1 LTS (Temurin, Windows) comparing **Clojol** (`-XX:AOTCache=build/cljl.aot -jar build/cljl.jar test/fib_bench.clj`) against **Clojure 1.12.6** (`clojure.main test/fib_bench.clj`):

| Metric | Clojol (`cljl`) | Clojure 1.12.6 | Speedup |
| :--- | :---: | :---: | :---: |
| `fib(40)` Run 1 (first call) | **51.2 ms** | 631.2 ms | **12.3x faster** |
| `fib(40)` Run 2 (warm) | **45.4 ms** | 621.7 ms | **13.7x faster** |
| `fib(40)` Run 3 (warm) | **45.6 ms** | 621.6 ms | **13.6x faster** |
| Total wall time (3 runs + JVM startup) | **0.26 s** | 2.49 s | **9.4x faster** |
| Cold startup + eval (`-e "(+ 1 2)"`) | **70.2 ms** | 445.0 ms | **6.3x faster** |

With type inference (`TypeAnalyzer.java`) and primitive-specialized Tier-1 bytecode compilation (`BytecodeJIT.java`), Clojol infers unboxed `long` parameters/returns with no user type hints required, compiles pure zero-capture self-recursive functions into `private static` `invokestatic` workers with a hoisted entry `Var` guard (`with-redefs`-safe), derives a provable static overflow-free input range with an exact `Math.addExact` fallback for larger inputs, and emits branch-pruned 2-level unrolled workers for linear-decrement recurrences (with zero memoization, zero lookup tables, and strict `O(φ^n)` tree recursion)—running `fib(40)` **~12–13.7x faster** than official Clojure and reducing total wall-clock execution time by **~89%**. Tight unboxed `loop`/`recur` microbenchmarks (`sum-loop(1,000,000)` in [`test/bench.clj`](test/bench.clj)) run in **~0.43 ms**.

### `loop/recur`, `volatile!`, and `atom` (vs. Clojure 1.12.6)

`1,000,000` iterations incrementing a counter (median of 7 runs after warmup, OpenJDK 25.0.4.1 on Windows):

| Benchmark (`N = 1,000,000`) | Clojol (`cljl`) | Clojure 1.12.6 | Speedup |
| :--- | :---: | :---: | :---: |
| `loop/recur` | **0.13 ms** | 0.18 ms | **1.35x faster** |
| `volatile!` (local `vswap!`) | **0.13 ms** | 8.22 ms | **61.8x faster** |
| `volatile!` (returned/tail-escaping `vswap!`) | **0.14 ms** | 6.39 ms | **46.6x faster** |
| `volatile!` (escaping param `vswap!`) | **0.75 ms** | 6.50 ms | **8.6x faster** |
| `atom` (`swap!`) | **10.44 ms** | 13.06 ms | **1.25x faster** |

`BytecodeJIT.java` eliminates redundant overflow checks on range-bounded `long` loop counters (`(< i n)` -> `(inc i)`), scalar-replaces local and tail-escaping `volatile!` bindings into unboxed JVM locals (deferring allocation until return when needed), compiles escaping `vswap!`/`vreset!` into fused unboxed RMW ops backed by a single-field 63-bit fast-long `VarHandle` acquire/release path in `Atom.Volatile` (pass `-Dcljl.volatile.strict=true` for full `volatile` fence semantics), and uses a lock-free 63-bit fast-long `VarHandle` CAS path in `Atom` to eliminate `Long` allocation and GC write barriers on numeric `swap!`/`reset!`.

### Cold Startup & AOT Cache Impact

Cold startup + eval (`java ... -jar build/cljl.jar -Srepro -Sdeps "{}" -e "(+ 1 2)"`) measured across 8 runs on OpenJDK 25.0.4.1 (Windows):

| Mode | Min | Median | Average |
| :--- | :---: | :---: | :---: |
| **Clojol — AOT Cache (`-XX:AOTCache=build/cljl.aot`, JDK 24+)** | 68.9 ms | **70.2 ms** | 70.8 ms |
| **Clojol — Default JDK CDS (`java -jar build/cljl.jar`)** | 108.2 ms | **109.8 ms** | 110.2 ms |
| **Clojol — No CDS (`-Xshare:off`)** | 143.9 ms | **150.1 ms** | 150.4 ms |
| **Clojure 1.12.6 (`clojure.main -e "(+ 1 2)"`)** | 438.6 ms | **445.0 ms** | 449.4 ms |

Through lazy on-demand `Core`/`CoreExtras` subsystem initialization, `tableswitch` dispatchers, zero-JCA inline SHA-256, and JDK 24+ AOT Cache (`build/cljl.aot`, with automatic `build/cljl.jsa` AppCDS fallback on JDK 21–23), Clojol achieves **~70 ms** cold startup (**6.3x faster** than Clojure 1.12.6 and **~36% faster** than default JDK CDS). Startup loads only **1,125** classes (**1,123** memory-mapped directly from `build/cljl.aot` and **0** loaded from `cljl.jar`). Note that `-J` / `:jvm-opts` relaunches and lazily loaded `StdLib` namespaces fall back to loading from `cljl.jar`.

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

Supports multi-line balanced forms, `*1`, `*2`, `*3`, `*e`, and EOF.

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

Environment variables `$CLJ_CONFIG`, `$CLJ_CACHE`, `$M2_REPO`, `$GITLIBS`, and `CLJL_OPTS` (extra JVM flags) are also supported.

## Language Features

- **Reader**: Lists, vectors, maps, sets, metadata (`^`), quote (`'`), syntax-quote (`` ` ``), unquote (`~`), unquote-splicing (`~@`), auto-gensyms (`x#`), anonymous fn literals (`#(...)`), regexes (`#"..."`), discard (`#_`), var-quote (`#'`), deref (`@`), namespaced maps (`#:ns{}` / `#::{}`), reader conditionals (`#?()` / `#?@()`, `{:read-cond :preserve}` / `reader-conditional`), tagged literals (`#uuid`, `#inst`, `tagged-literal`, `*data-readers*`, `*default-data-reader-fn*`), `read+string`, `*read-eval*`, `*suppress-read*`, `*reader-resolver*`, symbolic values (`##Inf`, `##-Inf`, `##NaN`), ratios (`3/2`), BigInts (`42N`), BigDecimals (`3.5M`), hex/octal/binary/radix numbers.
- **Persistent Data Structures & Chunks**:
  - `PersistentVector` & `Vec` (32-way bit-partitioned trie + tail buffer + `TransientVector`, `Reversible`, `vector-of`, `->Vec`, `->VecNode`, `->VecSeq`)
  - Chunked sequences (`chunk-buffer`, `chunk-append`, `chunk`, `chunk-cons`, `chunk-first`, `chunk-next`, `chunk-rest`, `chunked-seq?`, `->ArrayChunk`)
  - `PersistentHashMap` & `PersistentHashSet` (32-way HAMT + `MapEntry` + transients + `EMPTY-NODE`)
  - `PersistentTreeMap` & `PersistentTreeSet` (`sorted-map`, `sorted-map-by`, `sorted-set`, `sorted-set-by`, `subseq`, `rsubseq`, `rseq`, `comparator`)
  - `PersistentRecord` (`defrecord`), `PersistentStructMap` (`create-struct`, `defstruct`, `struct`, `struct-map`, `accessor`), and `PersistentList` / `LazySeq`
  - Contrib structures: `priority-map`, `ordered-map`, `ordered-set`, `ordering-map`, `int-map`, `int-set`, `dense-int-set`
- **Transducers, Sequences & Streams**: `transduce`, `completing`, `cat`, `halt-when`, `eduction` / `->Eduction`, `sequence`, transducer arities (`map`, `filter`, `remove`, `take`, `take-while`, `take-nth`, `drop`, `drop-while`, `keep`, `keep-indexed`, `map-indexed`, `mapcat`, `distinct`, `dedupe`, `interpose`, `partition-all`, `partitionv-all`, `partition-by`, `replace`, `random-sample`), Clojure 1.12 vector/stream ops (`partitionv`, `partitionv-all`, `splitv-at`, `replicate`, `stream-seq!`, `stream-reduce!`, `stream-transduce!`, `stream-into!`), `lazy-cat`, `not-empty`, `bounded-count`, `line-seq`, `file-seq`, `xml-seq`, `resultset-seq`, `enumeration-seq`, `iterator-seq`, `seque`, `iteration`, `update-keys`, `update-vals`.
- **Special Forms, Binding & Macros**: `def`, `defn`, `defn-`, `defmacro`, `definline`, `defonce`, `declare`, `special-symbol?`, `destructure`, `seq-to-map-for-destructuring`, `refer-clojure`, `fn`, `let`, `loop`, `recur`, `if`, `if-not`, `if-let`, `if-some`, `when`, `when-not`, `when-let`, `when-some`, `when-first`, `cond`, `condp`, `case`, `and`, `or`, `->`, `->>`, `as->`, `some->`, `some->>`, `cond->`, `cond->>`, `doto`, `for`, `doseq`, `dotimes`, `while`, `letfn`, `try`/`catch`/`finally`/`throw`, `monitor-enter`/`monitor-exit`/`locking`, `with-open`, `with-out-str`, `with-in-str`, `binding`, `with-bindings`, `with-bindings*`, `with-loading-context`, `bound-fn`, `bound-fn*`, `with-local-vars`, `with-redefs`, `with-redefs-fn`, `lazy-seq`, `delay`, `future`, `comment`, `ns`, `time`, `assert` (`*assert*`), `memfn`, `io!`.
- **Protocols, Types, Proxy, Printing & Multimethods**: `defprotocol`, `extend`, `extend-type`, `extend-protocol`, `extends?`, `extenders`, `satisfies?`, `find-protocol-impl`, `find-protocol-method`, `-cache-protocol-fn`, `-reset-methods`, `defrecord`, `deftype`, `reify`, `definterface`, `gen-interface`, `proxy` (`get-proxy-class`, `construct-proxy`, `init-proxy`, `update-proxy`, `proxy-mappings`, `proxy-super`, `proxy-name`, `proxy-call-with-super`, `method-sig`), hierarchies (`make-hierarchy`, `derive`, `underive`, `parents`, `ancestors`, `descendants`, `isa?`), multimethods (`defmulti`, `defmethod`, `prefer-method`, `prefers`, `remove-all-methods`), and printing extensible hooks (`print-method`, `print-dup`, `print-simple`, `print-ctor`, `PrintWriter-on`, `*flush-on-newline*`, `*print-namespace-maps*`).
- **State & Concurrency**: `atom` (`swap!`, `reset!`, `compare-and-set!`, watches, validators, metadata), `agent` (`send`, `send-off`, `send-via`, `await`, `await1`, `await-for`, `agent-error`, `agent-errors`, `clear-agent-errors`, `restart-agent`, `set-error-handler!`, `set-error-mode!`, `release-pending-sends`, `shutdown-agents`), STM `ref` (`dosync`, `sync`, `alter`, `commute`, `ref-set`, `ensure`, history controls), `tap>`/`add-tap`/`remove-tap`, `volatile!`, `delay`/`force`, `promise`/`deliver`, `future`, `pmap`, `pcalls`, `pvalues`.
- **Numerics, Hashing, Arrays & Parsing**: `parse-boolean`, `parse-long`, `parse-double`, `parse-uuid`, `random-uuid`, `numerator`, `denominator`, `rationalize`, `biginteger`, `byte`, `short`, `num`, `unchecked-add`/`-int`, `unchecked-subtract`/`-int`, `unchecked-multiply`/`-int`, `unchecked-divide-int`, `unchecked-remainder-int`, `unchecked-inc`/`-int`, `unchecked-dec`/`-int`, `unchecked-negate`/`-int`, `with-precision` (`*math-context*`), `max-key`, `min-key`, `Inst`/`inst-ms`/`inst-ms*`, `hash-combine`, `mix-collection-hash`, `hash-ordered-coll`, `hash-unordered-coll`, `munge`, `namespace-munge`, typed arrays (`booleans`..`doubles`, `boolean-array`..`double-array`, `make-array`, `into-array`, `to-array-2d`, `aset-*`, `amap`, `areduce`, `primitives-classnames`).
- **Java Interop & Introspection**: `(ClassName. args...)`, `(new ClassName args...)`, `(.method obj args...)`, `(.-field obj)`, `(ClassName/staticMethod args...)`, `ClassName/STATIC_FIELD`, `(set! ...)`, `(.. a b c)`, `(import ...)`, `add-classpath`, `bases`, `supers`, `bean`, `StackTraceElement->vec`, `Throwable->map`, `ex-cause`, `ex-message`, `clojure.lang.*` class aliases.
- **Standard Library & Contrib Namespaces**:
  - Core & JDK: `clojure.core`, `clojure.math`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.java.io`, `clojure.java.shell`, `clojure.java.browse`, `clojure.java.javadoc`, `clojure.main`, `clojure.tools.deps.cli.api`, `clojure.data`, `clojure.datafy`, `clojure.pprint`, `clojure.repl` (`doc`, `find-doc`, `apropos`, `dir`, `source`, `pst`), `clojure.test`, `clojure.xml`, `clojure.zip`, `clojure.spec.alpha`
  - Contrib-compatible: `clojure.core.async`, `clojure.data.priority-map`, `clojure.data.int-map`, `clojure.data.avl`, `flatland.ordered.map`, `flatland.ordered.set`, `flatland.useful.map`

## Building, Tests & Benchmarks

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
