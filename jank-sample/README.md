# Jank Sample Project

A simple project demonstrating [jank](https://jank-lang.org/) — a Clojure dialect
that compiles to native code via LLVM/Clang instead of targeting the JVM.

## What Is Jank?

Jank is a Clojure dialect with:
- **Native compilation** via LLVM (no JVM required)
- **C++ interop** instead of Java interop
- **Near-instant startup** (~50ms AOT vs. 1-3s JVM cold start)
- **Same functional style** as Clojure — most code is identical

As of early 2026, jank is in **alpha**. Most core Clojure idioms work, but the
standard library is still growing and some features (protocols, spec, core.async)
are not yet available.

---

## Project Structure

```
jank-sample/
├── project.clj                  # Leiningen build config (same tool as Clojure)
├── src/
│   └── jank_sample/
│       ├── core.jank            # Hello world + basic Clojure idioms
│       ├── data_structures.jank # Maps, vectors, sequences — works just like Clojure
│       └── cpp_interop.jank     # C++ interop (the big difference from Clojure)
└── README.md
```

---

## Installation

### Prerequisites

```bash
# Install LLVM/Clang 19+ (jank's compiler backend)
# Ubuntu/Debian:
sudo apt install clang-19 llvm-19

# Install Leiningen (same as Clojure ecosystem)
curl https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein > ~/bin/lein
chmod +x ~/bin/lein
lein version

# Install jank itself (from source or pre-built binary)
# See: https://jank-lang.org/
```

---

## Compiling and Running

### JIT Mode (development — fastest iteration)

```bash
# Run directly — jank JIT-compiles on first load
lein run

# Or invoke the jank CLI directly on a file
jank run src/jank_sample/core.jank
```

### AOT Mode (production — fastest startup)

```bash
# Compile to native binary (outputs ./a.out — naming not yet configurable)
lein compile

# Run the native executable (~50ms startup vs. 1-3s for Clojure JVM)
./a.out
```

### Interactive REPL

```bash
# nREPL server (written in jank itself as of 2026)
lein repl

# Or via the jank CLI
jank repl
```

---

## How It Differs from Clojure

| Aspect              | Clojure                          | Jank                                      |
|---------------------|----------------------------------|-------------------------------------------|
| **Runtime**         | JVM (Java 11+)                   | Native binary (LLVM/Clang)                |
| **Startup time**    | 1–3 seconds cold start           | ~50ms AOT, ~86ms JIT                      |
| **Build config**    | `deps.edn` or `project.clj`      | `project.clj` (Leiningen only for now)    |
| **File extension**  | `.clj`                           | `.jank`                                   |
| **Host interop**    | Java via `.` and `new`           | C++ via `cpp/` namespace                  |
| **Imports**         | `(:import java.util.Date)`       | `(cpp/include "<ctime>")`                 |
| **Calling host**    | `(.now (java.time.Instant.))`    | `(cpp/call std::time nullptr)`            |
| **Type hints**      | `^String`, `^long`, etc.         | Largely inferred; C++ types via DSL       |
| **Dependency mgmt** | Maven/Clojars via `deps.edn`     | Maven/Clojars via `project.clj`           |
| **Protocols**       | Full support                     | Not yet implemented (alpha)               |
| **`clojure.spec`**  | Full support                     | Not yet available                         |
| **`core.async`**    | Full support                     | Not yet available                         |
| **Test framework**  | `clojure.test`                   | No standard yet (alpha)                   |

### What's the Same

Most of the language is **identical** to Clojure:
- All core data structures: maps `{}`, vectors `[]`, sets `#{}`, lists `()`
- Sequence functions: `map`, `filter`, `reduce`, `for`, `doseq`, etc.
- `def`, `defn`, `let`, `loop`/`recur`, `cond`, `when`, `if`
- Namespaces and `require`/`use`
- Destructuring (positional and map-based)
- Persistent/immutable data by default
- Macros (still maturing in jank, but the model is the same)

---

## Key Syntax: C++ Interop

The most visible difference from Clojure is how you talk to the host platform.

**Clojure (Java interop):**
```clojure
(import java.util.Date)
(.toString (Date.))           ; Call instance method
(System/currentTimeMillis)    ; Call static method
```

**Jank (C++ interop):**
```clojure
(cpp/include "<ctime>")
(cpp/call std::time nullptr)  ; Call C++ function
(cpp/.-tv_sec timespec-val)   ; Access struct member
```

See `src/jank_sample/cpp_interop.jank` for working examples.

---

## Further Reading

- [jank official site](https://jank-lang.org/)
- [The jank Book](https://book.jank-lang.org/) — official guide for Clojure developers
- [GitHub: jank-lang/jank](https://github.com/jank-lang/jank)
- [C++ Interop deep dive](https://jank-lang.org/blog/2025-06-06-next-phase-of-interop/)
