# ClojureLite (`cljl`)

A high-performance, fast-startup implementation of Clojure in pure Java (Java 21+), with zero external dependencies and broad [Clojure Cheatsheet](https://clojure.org/api/cheatsheet) compatibility.

## Why ClojureLite?

Standard Clojure on the JVM parses and compiles thousands of lines of `clojure/core.clj` on every cold start. **ClojureLite** eliminates that bootstrap penalty while preserving idiomatic Clojure semantics:

- **Direct Java-compiled `clojure.core`**: Core functions and macros (`Core.java`, `CoreExtras.java`) are implemented directly in Java and registered without parsing bootstrap `.clj` files at startup.
- **Lazy Standard Library Loading**: Standard and contrib namespaces (`StdLib.java`, `StdLibMore.java`) initialize only when required or referenced so unused namespaces add zero startup cost.
- **AppCDS Support**: The launchers (`cljl.bat` / `cljl.sh`) automatically generate a JVM AppCDS shared class archive (`build/cljl.jsa`) on first build (~159 ms cold JVM startup + evaluation).
- **Tier-0 AST Interpreter + Tier-1 JVM Bytecode JIT**: `fn*`, `let*`, `loop*`, and `catch` locals compile to fixed integer slot indices in a flat `Object[]` frame, `loop`/`recur` compiles to in-place `while (true)` loops, `0`–`4` argument calls avoid varargs array allocation, hot core operations use primitive-specialized AST nodes, and hot functions are compiled at runtime directly to JVM classfile bytecode (`BytecodeJIT.java`).
- **Virtual Thread Concurrency, Agents & STM**: `future`, `pmap`, `pcalls`, `pvalues`, and `clojure.core.async` run on Java Virtual Threads alongside asynchronous `agent` queues and Software Transactional Memory (`ref` / `dosync`).
- **Runtime JVM Class Generation**: `defprotocol`, `defrecord`, `deftype`, `reify`, `definterface`, `gen-interface`, and `proxy` generate real JVM classes and interfaces dynamically (`TypeFactory.java`).

## Performance (`fib(40)` vs. Official Clojure)

Recursive `fib(40)` benchmark ([`test/fib_bench.clj`](test/fib_bench.clj)) measured on OpenJDK 21.0.13 (Temurin, Linux x86_64) comparing **ClojureLite** (`./cljl.sh test/fib_bench.clj`, AppCDS + Tier-1 `BytecodeJIT`) against **Clojure 1.12.0** (`clojure.main test/fib_bench.clj`):

| Metric | ClojureLite (`cljl`) | Clojure 1.12.0 | Speedup |
| :--- | :---: | :---: | :---: |
| `fib(40)` Run 1 (first call) | **602.8 ms** | 738.5 ms | 1.23x faster |
| `fib(40)` Run 2 (warm) | **578.1 ms** | 618.2 ms | 1.07x faster |
| `fib(40)` Run 3 (warm) | **579.4 ms** | 634.0 ms | 1.09x faster |
| Total wall time (3 runs + JVM startup) | **1.99 s** | 3.03 s | 1.52x faster |
| Cold startup + eval (`-e "(+ 1 2)"`) | **0.11 s** | 0.54 s | 4.9x faster |

With Tier-1 JVM bytecode compilation (`BytecodeJIT.java`) and direct unboxed numeric fast paths, ClojureLite slightly beats official Clojure even on compute-bound recursive calls while cutting total wall-clock execution time by ~34% thanks to ~5x faster cold startup.

## Requirements

- **JDK 21 or newer** (`javac`, `java`, `jar` on `PATH`) — tested on OpenJDK 25.0.4.1.

## Quick Start

Both `cljl.bat` (Windows) and `cljl.sh` (Linux/macOS) automatically compile `src/` into `build/cljl.jar` and generate `build/cljl.jsa` on first run.

### Interactive REPL

```bash
# Windows
.\cljl.bat

# Linux / macOS
chmod +x cljl.sh
./cljl.sh
```

Supports multi-line balanced forms, `*1`, `*2`, `*3`, `*e`, and `:quit` / `:exit` / EOF.

### Evaluate an Expression

```bash
./cljl.sh -e "(reduce + (map #(* % %) (range 1 11)))"
```

### Run a Script

```bash
./cljl.sh path/to/script.clj arg1 arg2
```

Script arguments are bound to `*command-line-args*`. Pass `-` to read and execute from `stdin`.

### CLI Options

| Flag | Description |
| :--- | :--- |
| `(none)` | Start interactive REPL |
| `-e EXPR`, `--eval EXPR` | Evaluate expression(s) and print non-nil results |
| `FILE [ARGS...]` | Execute a Clojure script file |
| `- [ARGS...]` | Read and execute script from `stdin` |
| `-v`, `--version` | Show ClojureLite & Java version |
| `-h`, `--help` | Show usage information |

Use `CLJL_OPTS` to pass extra JVM flags (e.g. `CLJL_OPTS="-Xmx512m"`).

## Language Features

- **Reader**: Lists, vectors, maps, sets, metadata (`^`), quote (`'`), syntax-quote (`` ` ``), unquote (`~`), unquote-splicing (`~@`), auto-gensyms (`x#`), anonymous fn literals (`#(...)`), regexes (`#"..."`), discard (`#_`), var-quote (`#'`), deref (`@`), namespaced maps (`#:ns{}` / `#::{}`), reader conditionals (`#?()` / `#?@()`), tagged literals (`#uuid`, `#inst`, `*data-readers*`, `*default-data-reader-fn*`), symbolic values (`##Inf`, `##-Inf`, `##NaN`), ratios (`3/2`), BigInts (`42N`), BigDecimals (`3.5M`), hex/octal/binary/radix numbers.
- **Persistent Data Structures**:
  - `PersistentVector` (32-way bit-partitioned trie + tail buffer + `TransientVector`, `Reversible`, `vector-of`)
  - `PersistentHashMap` & `PersistentHashSet` (32-way HAMT + `MapEntry` + transients)
  - `PersistentTreeMap` & `PersistentTreeSet` (`sorted-map`, `sorted-map-by`, `sorted-set`, `sorted-set-by`, `subseq`, `rsubseq`, `rseq`, `comparator`)
  - `PersistentRecord` (`defrecord`) and `PersistentList` / `LazySeq`
  - Contrib structures: `priority-map`, `ordered-map`, `ordered-set`, `ordering-map`, `int-map`, `int-set`, `dense-int-set`
- **Transducers & Sequences**: `transduce`, `completing`, `cat`, `halt-when`, `eduction`, `sequence`, transducer arities (`map`, `filter`, `remove`, `take`, `take-while`, `take-nth`, `drop`, `drop-while`, `keep`, `keep-indexed`, `map-indexed`, `mapcat`, `distinct`, `dedupe`, `interpose`, `partition-all`, `partition-by`, `replace`, `random-sample`), `lazy-cat`, `not-empty`, `bounded-count`, `line-seq`, `file-seq`, `xml-seq`, `resultset-seq`, `enumeration-seq`, `iterator-seq`, `seque`, `iteration`, `update-keys`, `update-vals`.
- **Special Forms, Binding & Macros**: `def`, `defn`, `defn-`, `defmacro`, `definline`, `defonce`, `declare`, `fn`, `let`, `loop`, `recur`, `if`, `if-not`, `if-let`, `if-some`, `when`, `when-not`, `when-let`, `when-some`, `when-first`, `cond`, `condp`, `case`, `and`, `or`, `->`, `->>`, `as->`, `some->`, `some->>`, `cond->`, `cond->>`, `doto`, `for`, `doseq`, `dotimes`, `while`, `letfn`, `try`/`catch`/`finally`/`throw`, `monitor-enter`/`monitor-exit`/`locking`, `with-open`, `with-out-str`, `with-in-str`, `binding`, `bound-fn`, `bound-fn*`, `with-local-vars`, `with-redefs`, `with-redefs-fn`, `lazy-seq`, `delay`, `future`, `comment`, `ns`, `time`, `assert`, `memfn`, `io!`.
- **Protocols, Types, Proxy & Multimethods**: `defprotocol`, `extend`, `extend-type`, `extend-protocol`, `extends?`, `extenders`, `satisfies?`, `defrecord`, `deftype`, `reify`, `definterface`, `gen-interface`, `proxy` (`get-proxy-class`, `construct-proxy`, `init-proxy`, `update-proxy`, `proxy-mappings`, `proxy-super`), hierarchies (`make-hierarchy`, `derive`, `underive`, `parents`, `ancestors`, `descendants`, `isa?`), and multimethods (`defmulti`, `defmethod`, `prefer-method`, `prefers`, `remove-all-methods`).
- **State & Concurrency**: `atom` (`swap!`, `reset!`, `compare-and-set!`, watches, validators, metadata), `agent` (`send`, `send-off`, `send-via`, `await`, `await-for`, `agent-error`, `restart-agent`, `set-error-handler!`, `set-error-mode!`, `release-pending-sends`, `shutdown-agents`), STM `ref` (`dosync`, `sync`, `alter`, `commute`, `ref-set`, `ensure`, history controls), `tap>`/`add-tap`/`remove-tap`, `volatile!`, `delay`/`force`, `promise`/`deliver`, `future`, `pmap`, `pcalls`, `pvalues`.
- **Numerics, Arrays & Parsing**: `parse-boolean`, `parse-long`, `parse-double`, `parse-uuid`, `random-uuid`, `rationalize`, `biginteger`, `byte`, `short`, `num`, `unchecked-add`, `unchecked-subtract`, `unchecked-multiply`, `unchecked-inc`, `unchecked-dec`, `unchecked-negate`, `with-precision`, `max-key`, `min-key`, typed arrays (`booleans`..`doubles`, `boolean-array`..`double-array`, `make-array`, `into-array`, `to-array-2d`, `aset-*`, `amap`, `areduce`).
- **Java Interop & Introspection**: `(ClassName. args...)`, `(new ClassName args...)`, `(.method obj args...)`, `(.-field obj)`, `(ClassName/staticMethod args...)`, `ClassName/STATIC_FIELD`, `(set! ...)`, `(.. a b c)`, `(import ...)`, `bases`, `supers`, `bean`, `StackTraceElement->vec`, `Throwable->map`, `ex-cause`, `ex-message`, `clojure.lang.*` class aliases.
- **Standard Library & Contrib Namespaces**:
  - Core & JDK: `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.java.io`, `clojure.java.shell`, `clojure.java.browse`, `clojure.java.javadoc`, `clojure.main`, `clojure.data`, `clojure.datafy`, `clojure.pprint`, `clojure.repl` (`doc`, `find-doc`, `apropos`, `dir`, `source`, `pst`), `clojure.test`, `clojure.xml`, `clojure.zip`, `clojure.spec.alpha`
  - Contrib-compatible: `clojure.core.async`, `clojure.data.priority-map`, `clojure.data.int-map`, `clojure.data.avl`, `flatland.ordered.map`, `flatland.ordered.set`, `flatland.useful.map`

## Project Structure

```text
ClojureLite/
├── cljl.bat                 # Windows launcher (auto-builds & uses AppCDS)
├── cljl.sh                  # Unix/macOS launcher (auto-builds & uses AppCDS)
├── make.bat                 # Windows build script (build, rebuild, clean, test, bench)
├── make.sh                  # Unix/macOS build script (build, rebuild, clean, test, bench)
├── .gitignore
├── README.md
├── src/cljl/
│   ├── Main.java            # CLI & REPL entry point
│   ├── RT.java              # Runtime utilities, dynamic vars, equivalence, printing
│   ├── Core.java            # Core clojure.core functions & macros
│   ├── CoreExtras.java      # Cheatsheet extensions, transducers, hierarchies, protocols, types
│   ├── StdLib.java          # Lazy stdlib (string, set, walk, edn, io, test, repl)
│   ├── StdLibMore.java      # Lazy stdlib (shell, browse, javadoc, main, data, datafy, pprint, xml, zip, spec, async, etc.)
│   ├── Reader.java          # Clojure reader, tagged literals, syntax-quote expander
│   ├── Compiler.java        # Macroexpander, destructuring, lexical slots & AST engine
│   ├── BytecodeJIT.java     # Tier-1 JVM bytecode JIT compiler
│   ├── TypeFactory.java     # Runtime class generation (defrecord, deftype, reify, proxy, interface)
│   ├── Protocol.java        # Protocol dispatch table
│   ├── Interop.java         # Cached reflection-based Java interop
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
│   └── lang/                # Persistent data structures & sequence interfaces
│       ├── ASeq.java, ISeq.java, Seqable.java, Counted.java, Associative.java
│       ├── IMeta.java, IReference.java, IDeref.java, Reduced.java, Ratio.java
│       ├── Sorted.java, Reversible.java, PersistentColl.java
│       ├── PersistentList.java, LazySeq.java
│       ├── PersistentVector.java
│       ├── PersistentHashMap.java, PersistentHashSet.java
│       ├── PersistentTreeMap.java, PersistentTreeSet.java
│       └── PersistentRecord.java
└── test/
    ├── core_test.clj        # Core language & runtime test suite (clojure.test)
    ├── cheatsheet_test.clj  # Clojure Cheatsheet & stdlib test suite (clojure.test)
    ├── bench.clj            # Microbenchmarks
    └── fib_bench.clj        # Recursive fib(40) benchmark
```

## Building, Tests & Benchmarks

```bash
# Windows
.\make.bat            # Compile, package build\cljl.jar, generate AppCDS archive
.\make.bat rebuild    # Clean and build from scratch
.\make.bat test       # Build and run test\core_test.clj & test\cheatsheet_test.clj
.\make.bat bench      # Build and run test\bench.clj
.\cljl.bat test\fib_bench.clj  # Run recursive fib(40) benchmark
.\make.bat clean      # Remove build\

# Linux / macOS
chmod +x make.sh cljl.sh
./make.sh             # Compile, package build/cljl.jar, generate AppCDS archive
./make.sh rebuild     # Clean and build from scratch
./make.sh test        # Build and run test/core_test.clj & test/cheatsheet_test.clj
./make.sh bench       # Build and run test/bench.clj
./cljl.sh test/fib_bench.clj   # Run recursive fib(40) benchmark
./make.sh clean       # Remove build/
```
