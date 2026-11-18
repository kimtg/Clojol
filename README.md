# Clojol (`cljl`)

A high-performance, fast-startup implementation of Clojure in pure Java (Java 21+), with zero external dependencies, full Clojure 1.12 `clojure.core` public API parity (`697` public vars), and broad [Clojure Cheatsheet](https://clojure.org/api/cheatsheet) compatibility.

Clojol is an independent, clean-room implementation of the Clojure language and is not affiliated with or endorsed by Rich Hickey or Nubank.

## Why Clojol?

Standard Clojure on the JVM parses and compiles thousands of lines of `clojure/core.clj` on every cold start. **Clojol** eliminates that bootstrap penalty while preserving idiomatic Clojure semantics:

- **Direct Java-compiled `clojure.core`**: Core functions, macros, and dynamic vars (`Core.java`, `CoreExtras.java`) are implemented directly in Java with full Clojure 1.12 `clojure.core` public API coverage (`697` public vars) and registered without parsing bootstrap `.clj` files at startup.
- **Lazy Standard Library Loading**: Standard and contrib namespaces (`StdLib.java`, `StdLibMore.java`) initialize only when required or referenced so unused namespaces add zero startup cost.
- **AppCDS Support**: The launchers (`cljl.bat` / `cljl.sh`) automatically generate a JVM AppCDS shared class archive (`build/cljl.jsa`) on first build (~159 ms cold JVM startup + evaluation).
- **Tier-0 AST Interpreter + Type-Inferred Tier-1 JVM Bytecode JIT**: `fn*`, `let*`, `loop*`, and `catch` locals compile to fixed integer slot indices in a flat `Object[]` frame, `loop`/`recur` compiles to in-place `while (true)` loops, `0`–`4` argument calls avoid varargs array allocation, and hot functions are compiled at runtime directly to JVM classfile bytecode (`BytecodeJIT.java`) backed by forward dataflow/fixpoint type inference & `^tag` propagation (`ValType.java`, `TypeAnalyzer.java`), unboxed `long`/`double`/`boolean` locals and operations, primitive-specialized method overloads (`invokePrim_*`) with guarded self-Var recursion (`with-redefs`-safe), direct typed Java interop, and collection/bitwise intrinsics.
- **Virtual Thread Concurrency, Agents & STM**: `future`, `pmap`, `pcalls`, `pvalues`, and `clojure.core.async` run on Java Virtual Threads alongside asynchronous `agent` queues and Software Transactional Memory (`ref` / `dosync`).
- **Runtime JVM Class Generation**: `defprotocol`, `defrecord`, `deftype`, `reify`, `definterface`, `gen-interface`, and `proxy` generate real JVM classes and interfaces dynamically (`TypeFactory.java`).
- **Standard `clj` CLI & `deps.edn` Engine (`1.12.6.1673`)**: Pure-Java `deps.edn` resolver (`Deps.java`) with `.cpcache` caching, transitive `:local/root` / `:mvn/version` / `:git/url` dependencies, `-A`/`-M`/`-X`/`-T`/`-P`, `-J`/`-S*` flags (`-Sdeps-file`, `-Scommand`), `clojure.main` init/main options, and built-in `:deps` CLI programs.

## Performance (`fib(40)` vs. Official Clojure)

Recursive `fib(40)` benchmark ([`test/fib_bench.clj`](test/fib_bench.clj)) measured on OpenJDK 21.0.13 (Temurin, Linux x86_64) comparing **Clojol** (`./cljl.sh test/fib_bench.clj`, AppCDS + type-inferred Tier-1 `BytecodeJIT`) against **Clojure 1.12.0** (`clojure.main test/fib_bench.clj`):

| Metric | Clojol (`cljl`) | Clojure 1.12.0 | Speedup |
| :--- | :---: | :---: | :---: |
| `fib(40)` Run 1 (first call) | **358.2 ms** | 738.5 ms | 2.06x faster |
| `fib(40)` Run 2 (warm) | **328.3 ms** | 618.2 ms | 1.88x faster |
| `fib(40)` Run 3 (warm) | **326.8 ms** | 634.0 ms | 1.94x faster |
| Total wall time (3 runs + JVM startup) | **1.15 s** | 3.03 s | 2.63x faster |
| Cold startup + eval (`-e "(+ 1 2)"`) | **0.11 s** | 0.54 s | 4.9x faster |

With type inference (`TypeAnalyzer.java`) and primitive-specialized Tier-1 bytecode compilation (`BytecodeJIT.java`), Clojol infers unboxed `long` parameters/returns and dispatches guarded self-recursive calls directly to `invokePrim_J_J` with no user type hints required—running `fib(40)` ~1.9–2.1x faster than official Clojure (matching raw Java `Math.addExact`/`subtractExact` speed) while reducing total wall-clock execution time by ~62%. Tight unboxed `loop`/`recur` microbenchmarks (`sum-loop(1,000,000)` in [`test/bench.clj`](test/bench.clj)) also drop from ~8.74 ms to ~0.56–0.76 ms.

### AppCDS Cold Startup Impact

Cold startup + eval (`java ... -jar build/cljl.jar -Srepro -Sdeps "{}" -e "(+ 1 2)"`) measured across 8 runs on OpenJDK 25.0.4.1 (Windows):

| Mode | Min | Median | Average |
| :--- | :---: | :---: | :---: |
| **With AppCDS (`-XX:SharedArchiveFile=build/cljl.jsa`)** | 143.0 ms | **148.6 ms** | 149.5 ms |
| **Without AppCDS (default JDK CDS only)** | 259.5 ms | **275.9 ms** | 274.4 ms |
| **No CDS (`-Xshare:off`)** | 275.2 ms | **287.5 ms** | 301.8 ms |

AppCDS cuts cold startup + eval time by **~127 ms (~46% / ~1.85x faster)** by memory-mapping all **1,828** startup classes (`Main`, `RT`, `Core`, `CoreExtras`, `Compiler`, `Reader`, `Deps`, `cljl.lang.*`, and JDK classes) directly from `build/cljl.jsa` with **0** classes loaded from `cljl.jar`. Note that `-J` / `:jvm-opts` relaunches and lazily loaded `StdLib` namespaces fall back to loading from `cljl.jar`.

## Requirements

- **JDK 21 or newer** (`javac`, `java`, `jar` on `PATH`) — tested on OpenJDK 25.0.4.1.

## Quick Start

Both `cljl.bat` (Windows) and `cljl.sh` (Linux/macOS) automatically compile `src/` into `build/cljl.jar` and generate `build/cljl.jsa` on first run.

### Interactive REPL

```bash
# Run the jar file without AppCDS
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

## Project Structure

```text
Clojol/
├── cljl.bat                 # Windows launcher (auto-builds & uses AppCDS)
├── cljl.sh                  # Unix/macOS launcher (auto-builds & uses AppCDS + -J pre-parsing)
├── make.bat                 # Windows build script (build, rebuild, clean, test, bench)
├── make.sh                  # Unix/macOS build script (build, rebuild, clean, test, bench)
├── .gitignore
├── README.md
├── src/cljl/
│   ├── Main.java            # clj/clojure.main CLI & REPL entry point
│   ├── Deps.java            # deps.edn resolution, .cpcache, -X/-T execution, :deps API
│   ├── RT.java              # Runtime utilities, dynamic vars, equivalence, printing
│   ├── Core.java            # Core clojure.core functions & macros
│   ├── CoreExtras.java      # Cheatsheet extensions, transducers, hierarchies, protocols, types
│   ├── StdLib.java          # Lazy stdlib (string, set, walk, edn, io, test, repl)
│   ├── StdLibMore.java      # Lazy stdlib (shell, browse, javadoc, main, data, datafy, pprint, xml, zip, spec, async, etc.)
│   ├── Reader.java          # Clojure reader, tagged literals, syntax-quote expander
│   ├── Compiler.java        # Macroexpander, destructuring, ^tag hints, lexical slots & AST engine
│   ├── ValType.java         # Type lattice for primitive/reference inference & ^tag parsing
│   ├── TypeAnalyzer.java    # Forward dataflow & fixpoint type inference and interop resolution
│   ├── BytecodeJIT.java     # Type-inferred Tier-1 JVM bytecode JIT & primitive specialization
│   ├── TypeFactory.java     # Runtime class generation (defrecord, deftype, reify, proxy, interface)
│   ├── Protocol.java        # Protocol dispatch table
│   ├── Interop.java         # Typed & cached-reflection Java interop
│   ├── Numbers.java         # Numeric tower (Long, Double, BigInteger, BigDecimal, Ratio)
│   ├── Fn.java              # Arity-specialized callable base class
│   ├── Symbol.java          # Symbols
│   ├── Keyword.java         # Interned keywords
│   ├── Var.java             # Vars & thread-local dynamic binding frames
│   ├── Namespace.java       # Namespaces, aliases, imports, refers
│   ├── Atom.java            # Atom, Volatile, Delay, Promise, Future (Virtual Threads)
│   ├── Agent.java           # Agents & async action queues
│   ├── Ref.java             # STM mutable reference
│   ├── LockingTransaction.java # MVCC Software Transactional Memory engine
│   ├── MultiFn.java         # Multimethods
│   └── lang/                # Persistent data structures, chunks & sequence interfaces
│       ├── ASeq.java, Cons.java, ISeq.java, IPersistentList.java, Seqable.java, StringSeq.java
│       ├── Counted.java, ILookup.java, Associative.java, Sorted.java, Reversible.java, PersistentColl.java
│       ├── IChunk.java, IChunkedSeq.java, ArrayChunk.java, ChunkBuffer.java, ChunkedCons.java
│       ├── IMeta.java, IObj.java, IReference.java, IDeref.java, Reduced.java, Ratio.java
│       ├── TaggedLiteral.java, ReaderConditional.java
│       ├── PersistentList.java, LazySeq.java
│       ├── PersistentVector.java, Vec.java
│       ├── PersistentHashMap.java, PersistentHashSet.java
│       ├── PersistentTreeMap.java, PersistentTreeSet.java
│       └── PersistentRecord.java, PersistentStructMap.java
└── test/
    ├── core_test.clj        # Core language & runtime test suite (clojure.test)
    ├── cheatsheet_test.clj  # Clojure Cheatsheet & stdlib test suite (clojure.test)
    ├── cli_test.clj         # clj / deps.edn / clojure.main / :deps CLI test suite (clojure.test)
    ├── bench.clj            # Microbenchmarks
    └── fib_bench.clj        # Recursive fib(40) benchmark
```

## Building, Tests & Benchmarks

```bash
# Windows
.\make.bat            # Compile, package build\cljl.jar, generate AppCDS archive
.\make.bat rebuild    # Clean and build from scratch
.\make.bat test       # Build and run core_test.clj, cheatsheet_test.clj & cli_test.clj
.\make.bat bench      # Build and run test\bench.clj
.\cljl.bat test\fib_bench.clj  # Run recursive fib(40) benchmark
.\make.bat clean      # Remove build\

# Linux / macOS
chmod +x make.sh cljl.sh
./make.sh             # Compile, package build/cljl.jar, generate AppCDS archive
./make.sh rebuild     # Clean and build from scratch
./make.sh test        # Build and run core_test.clj, cheatsheet_test.clj & cli_test.clj
./make.sh bench       # Build and run test/bench.clj
./cljl.sh test/fib_bench.clj   # Run recursive fib(40) benchmark
./make.sh clean       # Remove build/
```
