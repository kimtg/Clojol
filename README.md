# ClojureLite (`cljl`)

A high-performance, fast-startup implementation of Clojure in pure Java (Java 21+), with zero external dependencies.

## Why ClojureLite?

Standard Clojure on the JVM parses and compiles thousands of lines of `clojure/core.clj` on every cold start. **ClojureLite** eliminates that bootstrap penalty while preserving idiomatic Clojure semantics:

- **Direct Java-compiled `clojure.core`**: All core functions and macros are implemented directly in Java and registered without parsing bootstrap `.clj` files at startup.
- **Lazy Standard Library Loading**: `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.java.io`, and `clojure.test` initialize only when required or referenced.
- **AppCDS Support**: The launchers (`cljl.bat` / `cljl.sh`) automatically generate a JVM AppCDS shared class archive (`build/cljl.jsa`) on first build for ~115 ms cold JVM startup + evaluation.
- **Flat Lexical Slot Frames**: `fn*`, `let*`, `loop*`, and `catch` locals compile to fixed integer slot indices in a single `Object[]` frame per activation—zero map lookups for local variables.
- **Zero-Allocation `loop`/`recur` & Tail `recur`**: Compiled into direct Java `while (true)` loops mutating frame slots in place.
- **Arity-Specialized Dispatch & Intrinsic AST Nodes**: `0`–`4` argument calls avoid `Object[]` allocation, and hot core operations (`+`, `-`, `*`, `inc`, `dec`, `<`, `<=`, `>`, `>=`, `=`, `==`, `zero?`, `pos?`, `neg?`, `nil?`, `not`, `rem`, `quot`, `identical?`) compile to specialized AST nodes with primitive fast-paths.
- **Virtual Thread Concurrency**: `future` and `pmap` execute on Java Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

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

- **Reader**: Lists, vectors, maps, sets, metadata (`^`), quote (`'`), syntax-quote (`` ` ``), unquote (`~`), unquote-splicing (`~@`), auto-gensyms (`x#`), anonymous fn literals (`#(...)`), regexes (`#"..."`), discard (`#_`), var-quote (`#'`), deref (`@`), namespaced maps (`#:ns{}` / `#::{}`), reader conditionals (`#?()` / `#?@()`), ratios (`3/2`), BigInts (`42N`), BigDecimals (`3.5M`), hex/octal/binary/radix numbers.
- **Persistent Data Structures**:
  - `PersistentVector` (32-way bit-partitioned trie + tail buffer + `TransientVector`)
  - `PersistentHashMap` (32-way HAMT + `MapEntry` + `TransientHashMap`)
  - `PersistentHashSet` (backed by `PersistentHashMap` + `TransientHashSet`)
  - `PersistentList` & `LazySeq` (iterative unwrapping to prevent stack overflows)
- **Special Forms & Macros**: `def`, `defn`, `defn-`, `defmacro`, `defonce`, `declare`, `fn`, `let`, `loop`, `recur`, `if`, `if-not`, `if-let`, `if-some`, `when`, `when-not`, `when-let`, `when-some`, `when-first`, `cond`, `condp`, `case`, `and`, `or`, `->`, `->>`, `as->`, `some->`, `some->>`, `cond->`, `cond->>`, `doto`, `for`, `doseq`, `dotimes`, `while`, `letfn`, `try`/`catch`/`finally`/`throw`, `with-open`, `with-out-str`, `with-in-str`, `binding`, `lazy-seq`, `delay`, `future`, `comment`, `ns`, `time`, `assert`, `defmulti`, `defmethod`.
- **State & Concurrency**: `atom` (`swap!`, `reset!`, `compare-and-set!`, watches, validators), `volatile!`, `delay`/`force`, `promise`/`deliver`, `future`, `pmap`.
- **Java Interop**: `(ClassName. args...)`, `(new ClassName args...)`, `(.method obj args...)`, `(.-field obj)`, `(ClassName/staticMethod args...)`, `ClassName/STATIC_FIELD`, `(set! ...)`, `(.. a b c)`, `(import ...)`.
- **Standard Library Namespaces**: `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn`, `clojure.java.io`, `clojure.test`.

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
│   ├── RT.java              # Runtime utilities, truthiness, equivalence, printing
│   ├── Core.java            # Direct Java-compiled clojure.core functions & macros
│   ├── StdLib.java          # Lazy stdlib namespaces (string, set, walk, edn, io, test)
│   ├── Reader.java          # Clojure reader & syntax-quote expander
│   ├── Compiler.java        # Macroexpander, destructuring, lexical slots & AST engine
│   ├── Interop.java         # Cached reflection-based Java interop
│   ├── Numbers.java         # Numeric tower (Long, Double, BigInteger, BigDecimal, Ratio)
│   ├── Fn.java              # Arity-specialized callable base class
│   ├── Symbol.java          # Symbols
│   ├── Keyword.java         # Interned keywords
│   ├── Var.java             # Vars & thread-local dynamic binding frames
│   ├── Namespace.java       # Namespaces, aliases, imports, refers
│   ├── Atom.java            # Atom, Volatile, Delay, Promise, Future (Virtual Threads)
│   ├── MultiFn.java         # Multimethods
│   └── lang/                # Persistent data structures & sequence interfaces
│       ├── ASeq.java, ISeq.java, Seqable.java, Counted.java, Associative.java
│       ├── IMeta.java, IDeref.java, Reduced.java, Ratio.java
│       ├── PersistentList.java, LazySeq.java
│       ├── PersistentVector.java
│       ├── PersistentHashMap.java
│       └── PersistentHashSet.java
└── test/
    ├── core_test.clj        # Unit test suite (clojure.test)
    └── bench.clj            # Microbenchmarks
```

## Building, Tests & Benchmarks

```bash
# Windows
.\make.bat            # Compile, package build\cljl.jar, generate AppCDS archive
.\make.bat rebuild    # Clean and build from scratch
.\make.bat test       # Build and run test\core_test.clj
.\make.bat bench      # Build and run test\bench.clj
.\make.bat clean      # Remove build\

# Linux / macOS
chmod +x make.sh cljl.sh
./make.sh             # Compile, package build/cljl.jar, generate AppCDS archive
./make.sh rebuild     # Clean and build from scratch
./make.sh test        # Build and run test/core_test.clj
./make.sh bench       # Build and run test/bench.clj
./make.sh clean       # Remove build/
```
