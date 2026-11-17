package cljl;

import cljl.Deps.BasisResult;
import cljl.Deps.CliOptions;
import cljl.Deps.Mode;
import cljl.lang.PersistentHashMap;
import cljl.lang.PersistentList;
import cljl.lang.PersistentVector;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Clojol CLI, `clj`/`clojure` options engine, `clojure.main`, and Interactive REPL Entry Point.
 */
public final class Main {

    public static final String VERSION = "0.1.0";

    private Main() {}

    public static void main(String[] args) {
        RT.ensureInit();

        CliOptions opts;
        try {
            opts = Deps.parseCli(args);
        } catch (Throwable t) {
            System.err.println("Error: " + t.getMessage());
            System.exit(1);
            return;
        }

        if (opts.version) {
            System.out.println("Clojol " + VERSION + " (Clojure CLI " + Deps.CLJ_TOOLS_VERSION
                    + ", Java " + System.getProperty("java.version") + ")");
            return;
        }
        if (opts.help) {
            printHelp();
            return;
        }

        BasisResult basis = null;
        if (Deps.needsDepsResolution(opts)) {
            try {
                basis = Deps.resolveBasis(opts);
            } catch (Throwable t) {
                printError(t, "stderr");
                System.exit(1);
                return;
            }
            if (opts.sverbose) {
                Deps.printVerbose(basis, opts);
            }
        }

        List<String> combinedMainArgs = new ArrayList<>();
        if (basis != null && opts.mode == Mode.MAIN && !basis.mainOpts.isEmpty()) {
            combinedMainArgs.addAll(basis.mainOpts);
        }
        combinedMainArgs.addAll(opts.remainingArgs);

        // -Scommand custom execution
        if (opts.scommand != null) {
            String cp = basis != null ? basis.classpath : System.getProperty("java.class.path", "");
            StringBuilder mainOptsStr = new StringBuilder();
            for (int i = 0; i < combinedMainArgs.size(); i++) {
                if (i > 0) mainOptsStr.append(' ');
                mainOptsStr.append(shellQuote(combinedMainArgs.get(i)));
            }
            String cmd = opts.scommand
                    .replace("{{classpath}}", cp)
                    .replace("{{main-opts}}", mainOptsStr.toString());
            int code = execShellCommand(cmd);
            System.exit(code);
            return;
        }

        // -Spath / -Stree / -P exit without running code
        if (opts.spath) {
            System.out.println(basis != null ? basis.classpath : "");
            return;
        }
        if (opts.stree) {
            if (basis != null) Deps.printTree(basis);
            return;
        }
        if (opts.prepareOnly) {
            return;
        }

        // Collect JVM options (-J... and alias :jvm-opts)
        List<String> jvmOpts = new ArrayList<>(opts.jvmOpts);
        if (basis != null && !basis.jvmOpts.isEmpty()) {
            jvmOpts.addAll(basis.jvmOpts);
        }

        if (!jvmOpts.isEmpty()) {
            List<String> nonPropOpts = new ArrayList<>();
            for (String jo : jvmOpts) {
                if (jo.startsWith("-D")) {
                    int eq = jo.indexOf('=');
                    if (eq > 2) {
                        System.setProperty(jo.substring(2, eq), jo.substring(eq + 1));
                    } else if (jo.length() > 2) {
                        System.setProperty(jo.substring(2), "");
                    }
                } else {
                    nonPropOpts.add(jo);
                }
            }
            if (!nonPropOpts.isEmpty() && !Boolean.getBoolean("cljl.relaunched")) {
                int exitCode = relaunchWithJvmOpts(jvmOpts, basis, args);
                System.exit(exitCode);
                return;
            }
        }

        if (basis != null) {
            Deps.applyClasspath(basis, opts);
        }

        if (opts.mode == Mode.EXEC || opts.mode == Mode.TOOL) {
            try {
                Deps.runExec(opts, basis);
            } catch (Throwable t) {
                printError(t, "stderr");
                System.exit(1);
            }
            return;
        }

        // Default / -A / -M -> clojure.main semantics
        try {
            runMain(combinedMainArgs.toArray(new String[0]));
        } catch (Throwable t) {
            printError(t, "file");
            System.exit(1);
        }
    }

    // ========================================================================
    // `clojure.main` Runner (init-opts + main-opt + REPL)
    // ========================================================================

    public static void runMain(String[] args) {
        String reportTarget = "file";
        boolean hadInit = false;
        int i = 0;

        // Pass 1: consume init-opts in order
        while (i < args.length) {
            String arg = args[i];
            if ("-i".equals(arg) || "--init".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: " + arg + " requires a path argument");
                    System.exit(1);
                }
                String path = args[++i];
                hadInit = true;
                try {
                    loadScriptOrResource(path);
                } catch (Throwable t) {
                    printError(t, reportTarget);
                    System.exit(1);
                }
                i++;
                continue;
            }
            if ("-e".equals(arg) || "--eval".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: " + arg + " requires an expression argument");
                    System.exit(1);
                }
                String expr = args[++i];
                hadInit = true;
                evalAndPrint(expr, "<eval>", reportTarget);
                i++;
                continue;
            }
            if ("--report".equals(arg)) {
                if (i + 1 >= args.length) {
                    System.err.println("Error: --report requires target (file, stderr, or none)");
                    System.exit(1);
                }
                reportTarget = args[++i];
                if (!"file".equals(reportTarget) && !"stderr".equals(reportTarget) && !"none".equals(reportTarget)) {
                    System.err.println("Error: Unknown --report target: " + reportTarget);
                    System.exit(1);
                }
                i++;
                continue;
            }
            break;
        }

        // Pass 2: main-opt (or default exit/repl)
        if (i >= args.length) {
            if (!hadInit) {
                runRepl(reportTarget);
            }
            return;
        }

        String mainOpt = args[i];
        if ("-h".equals(mainOpt) || "-?".equals(mainOpt) || "--help".equals(mainOpt)) {
            printHelp();
            return;
        }
        if ("-v".equals(mainOpt) || "-version".equals(mainOpt) || "--version".equals(mainOpt)) {
            System.out.println("Clojol " + VERSION + " (Clojure CLI " + Deps.CLJ_TOOLS_VERSION
                    + ", Java " + System.getProperty("java.version") + ")");
            return;
        }
        if ("-r".equals(mainOpt) || "--repl".equals(mainOpt)) {
            bindCommandLineArgs(args, i + 1);
            runRepl(reportTarget);
            return;
        }
        if ("-m".equals(mainOpt) || "--main".equals(mainOpt)) {
            if (i + 1 >= args.length) {
                System.err.println("Error: " + mainOpt + " requires a namespace name");
                System.exit(1);
            }
            String nsName = args[i + 1];
            bindCommandLineArgs(args, i + 2);
            String[] mainCallArgs = Arrays.copyOfRange(args, i + 2, args.length);
            try {
                Namespace targetNs = Namespace.find(nsName);
                Var existingMain = targetNs != null ? targetNs.findInternedVar(Symbol.intern("-main")) : null;
                if (existingMain == null || !existingMain.isBound()) {
                    Core.loadLib(nsName);
                    targetNs = Namespace.find(nsName);
                }
                if (targetNs == null) {
                    throw new RuntimeException("Namespace not found: " + nsName);
                }
                Var mainVar = targetNs.findInternedVar(Symbol.intern("-main"));
                if (mainVar == null || !mainVar.isBound()) {
                    throw new RuntimeException("Var " + nsName + "/-main not found or unbound");
                }
                Object fnObj = mainVar.deref();
                if (!(fnObj instanceof Fn fn)) {
                    throw new RuntimeException(nsName + "/-main is not a function");
                }
                fn.applyTo(mainCallArgs);
            } catch (Throwable t) {
                printError(t, reportTarget);
                System.exit(1);
            }
            return;
        }
        if ("-".equals(mainOpt)) {
            bindCommandLineArgs(args, i + 1);
            runStdin(reportTarget);
            return;
        }

        // Script file or resource path
        Path scriptPath = Path.of(mainOpt);
        if (mainOpt.startsWith("-") && !Files.exists(scriptPath)) {
            System.err.println("Unknown option: " + mainOpt);
            System.exit(1);
        }
        bindCommandLineArgs(args, i + 1);
        try {
            loadScriptOrResource(mainOpt);
        } catch (Throwable t) {
            printError(t, reportTarget);
            System.exit(1);
        }
    }

    private static void bindCommandLineArgs(String[] args, int fromIdx) {
        if (fromIdx < args.length) {
            RT.COMMAND_LINE_ARGS.bindRoot(PersistentList.createFromArraySlice(args, fromIdx));
        } else {
            RT.COMMAND_LINE_ARGS.bindRoot(null);
        }
    }

    public static void loadScriptOrResource(String pathStr) throws IOException {
        if (pathStr.startsWith("@") && pathStr.length() > 1) {
            String resName = pathStr.substring(1);
            loadResource(resName, pathStr);
            return;
        }
        Path p = Path.of(pathStr);
        if (Files.isRegularFile(p)) {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            Compiler.load(src, pathStr);
            return;
        }
        String resName = pathStr.startsWith("/") ? pathStr.substring(1) : pathStr;
        try (InputStream is = RT.baseLoader().getResourceAsStream(resName)) {
            if (is != null) {
                String src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                Compiler.load(src, pathStr);
                return;
            }
        }
        throw new FileNotFoundException("File or resource not found: " + pathStr);
    }

    private static void loadResource(String resName, String display) throws IOException {
        String clean = resName.startsWith("/") ? resName.substring(1) : resName;
        try (InputStream is = RT.baseLoader().getResourceAsStream(clean)) {
            if (is == null) {
                throw new FileNotFoundException("Resource not found: " + display);
            }
            String src = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Compiler.load(src, display);
        }
    }

    private static void evalAndPrint(String src, String filename, String reportTarget) {
        try {
            PrintWriter out = StdLib.outWriter();
            new Reader(src, filename).consumeForms(form -> {
                Object res = Compiler.eval(form);
                if (res != null) {
                    out.println(RT.printString(res, true));
                    out.flush();
                }
            });
        } catch (Throwable t) {
            printError(t, reportTarget);
            System.exit(1);
        }
    }

    private static void runStdin(String reportTarget) {
        try {
            Compiler.load(new InputStreamReader(System.in, StandardCharsets.UTF_8), "<stdin>");
        } catch (Throwable t) {
            printError(t, reportTarget);
            System.exit(1);
        }
    }

    private static void runRepl(String reportTarget) {
        Var v1 = RT.STAR_1;
        Var v2 = RT.STAR_2;
        Var v3 = RT.STAR_3;
        Var ve = RT.STAR_E;

        System.out.println("Clojol " + VERSION + " (Java " + System.getProperty("java.version") + ")");

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder buf = new StringBuilder();

        while (true) {
            try {
                if (buf.isEmpty()) {
                    System.out.print(RT.currentNS().name.name + "=> ");
                } else {
                    System.out.print("...=> ");
                }
                System.out.flush();

                String line = reader.readLine();
                if (line == null) {
                    if (!buf.isEmpty()) {
                        processReplBuffer(buf.toString(), v1, v2, v3, ve, reportTarget);
                    }
                    System.out.println();
                    break;
                }

                String trimmed = line.trim();
                if (buf.isEmpty() && trimmed.isEmpty()) {
                    continue;
                }

                buf.append(line).append('\n');
                if (isBalanced(buf)) {
                    String code = buf.toString();
                    buf.setLength(0);
                    processReplBuffer(code, v1, v2, v3, ve, reportTarget);
                }
            } catch (Throwable t) {
                buf.setLength(0);
                ve.bindRoot(t);
                printError(t, reportTarget);
            }
        }
    }

    private static void processReplBuffer(String code, Var v1, Var v2, Var v3, Var ve, String reportTarget) {
        try {
            new Reader(code, "<repl>").consumeForms(form -> {
                Object res = Compiler.eval(form);
                v3.bindRoot(v2.get());
                v2.bindRoot(v1.get());
                v1.bindRoot(res);
                System.out.println(RT.printString(res, true));
            });
        } catch (Throwable t) {
            ve.bindRoot(t);
            printError(t, reportTarget);
        }
    }

    private static boolean isBalanced(CharSequence s) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        boolean inComment = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inComment) {
                if (c == '\n') inComment = false;
                continue;
            }
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == ';') {
                inComment = true;
            } else if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            }
        }
        return !inString && depth <= 0;
    }

    // ========================================================================
    // Process Helpers (-J / :jvm-opts relaunch & -Scommand)
    // ========================================================================

    private static int relaunchWithJvmOpts(List<String> jvmOpts, BasisResult basis, String[] origArgs) {
        try {
            Path javaExe = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            String javaBin = Files.isRegularFile(javaExe)
                    ? javaExe.toString()
                    : Path.of(System.getProperty("java.home"), "bin", "java").toString();
            String selfCp = System.getProperty("java.class.path", "");
            String fullCp = (basis != null && basis.classpath != null && !basis.classpath.isEmpty())
                    ? selfCp + File.pathSeparator + basis.classpath
                    : selfCp;

            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-Dcljl.relaunched=true");
            if (Boolean.getBoolean("cljl.jit.strict")) {
                cmd.add("-Dcljl.jit.strict=true");
            }
            cmd.addAll(jvmOpts);
            cmd.add("-cp");
            cmd.add(fullCp);
            cmd.add("cljl.Main");
            // Strip -J* from origArgs since already applied to child JVM
            for (int i = 0; i < origArgs.length; i++) {
                String a = origArgs[i];
                if ("-J".equals(a)) {
                    i++;
                } else if (a.startsWith("-J")) {
                    // skip
                } else if ("-Sdeps".equals(a) || "-Scp".equals(a) || "-Sdeps-file".equals(a)
                        || "-Scommand".equals(a) || "-Sthreads".equals(a)) {
                    cmd.add(a);
                    if (i + 1 < origArgs.length) {
                        cmd.add(origArgs[++i]);
                    }
                } else {
                    cmd.add(a);
                    if ("--".equals(a) || a.startsWith("-M") || a.startsWith("-A") || a.startsWith("-X") || a.startsWith("-T")) {
                        for (int j = i + 1; j < origArgs.length; j++) cmd.add(origArgs[j]);
                        break;
                    }
                }
            }
            if (isWindows()) {
                for (int i = 1; i < cmd.size(); i++) {
                    cmd.set(i, escapeWindowsArg(cmd.get(i)));
                }
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (Exception e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Escapes embedded double quotes (and preceding backslashes) for Java's ProcessBuilder
     * -> CreateProcessW -> Microsoft CRT CommandLineToArgvW parsing on Windows.
     */
    private static String escapeWindowsArg(String s) {
        if (s.indexOf('"') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        int backslashes = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                for (int j = 0; j < backslashes * 2 + 1; j++) sb.append('\\');
                sb.append('"');
                backslashes = 0;
            } else {
                for (int j = 0; j < backslashes; j++) sb.append('\\');
                sb.append(c);
                backslashes = 0;
            }
        }
        for (int j = 0; j < backslashes; j++) sb.append('\\');
        return sb.toString();
    }

    private static int execShellCommand(String command) {
        try {
            boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.inheritIO();
            return pb.start().waitFor();
        } catch (Exception e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static String shellQuote(String s) {
        if (s.matches("[a-zA-Z0-9_./:-]+")) return s;
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (win) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ========================================================================
    // Error Reporting (`file`, `stderr`, `none`)
    // ========================================================================

    public static void printError(Throwable t, String reportTarget) {
        String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
        if ("none".equals(reportTarget)) {
            System.err.println("Execution error (" + t.getClass().getSimpleName() + "): " + msg);
            return;
        }
        if ("stderr".equals(reportTarget)) {
            System.err.println("Execution error (" + t.getClass().getSimpleName() + "): " + msg);
            if (t.getCause() != null) {
                System.err.println("  Caused by: " + t.getCause());
            }
            t.printStackTrace(System.err);
            return;
        }

        // "file" target (clojure.main default)
        System.err.println("Execution error (" + t.getClass().getSimpleName() + "): " + msg);
        if (t.getCause() != null) {
            System.err.println("  Caused by: " + t.getCause());
        }
        try {
            Path reportPath = Files.createTempFile("clojure-", ".edn");
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            List<Object> traceVec = new ArrayList<>();
            for (StackTraceElement ste : t.getStackTrace()) {
                traceVec.add(PersistentVector.create(Arrays.asList(
                        Symbol.intern(ste.getClassName()),
                        Symbol.intern(ste.getMethodName()),
                        ste.getFileName(),
                        (long) ste.getLineNumber())));
            }
            PersistentHashMap reportMap = PersistentHashMap.create(
                    Keyword.intern("clojure.main/error"), msg,
                    Keyword.intern("clojure.main/type"), Symbol.intern(t.getClass().getName()),
                    Keyword.intern("clojure.main/trace"), PersistentVector.create(traceVec));
            Files.writeString(reportPath, RT.printString(reportMap, true) + "\n", StandardCharsets.UTF_8);
            System.err.println("Full report at:\n" + reportPath);
        } catch (IOException ignored) {
            // Non-fatal if temp directory is unavailable
        }
    }

    private static void printHelp() {
        System.out.println("Version: " + Deps.CLJ_TOOLS_VERSION + " (Clojol " + VERSION + ")");
        System.out.println();
        System.out.println("You use the Clojol tools ('cljl') to run Clojure programs");
        System.out.println("on the JVM, e.g. to start a REPL or invoke a specific function with data.");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  Start a REPL  cljl    [clj-opt*] [-Aaliases]");
        System.out.println("  Exec fn(s)    cljl    [clj-opt*] -X[aliases] [a/fn*] [kpath v]*");
        System.out.println("  Run main      cljl    [clj-opt*] -M[aliases] [init-opt*] [main-opt] [arg*]");
        System.out.println("  Run tool      cljl    [clj-opt*] -T[name|aliases] a/fn [kpath v] kv-map?");
        System.out.println("  Prepare       cljl    [clj-opt*] -P [other exec opts]");
        System.out.println();
        System.out.println("exec-opts:");
        System.out.println(" -Aaliases      Use concatenated aliases to modify classpath");
        System.out.println(" -X[aliases]    Use concatenated aliases to modify classpath or supply exec fn/args");
        System.out.println(" -M[aliases]    Use concatenated aliases to modify classpath or supply main opts");
        System.out.println(" -P             Prepare deps - download libs, cache classpath, but don't exec");
        System.out.println();
        System.out.println("clj-opts:");
        System.out.println(" -Jopt          Pass opt through in java_opts, ex: -J-Xmx512m");
        System.out.println(" -Sdeps EDN     Deps data or file to use as the last deps file to be merged");
        System.out.println(" -Spath         Compute classpath and echo to stdout only");
        System.out.println(" -Stree         Print dependency tree");
        System.out.println(" -Scp CP        Do NOT compute or cache classpath, use this one instead");
        System.out.println(" -Srepro        Ignore the ~/.clojure/deps.edn config file");
        System.out.println(" -Sforce        Force recomputation of the classpath (don't use the cache)");
        System.out.println(" -Sverbose      Print important path info to console");
        System.out.println(" -Sthreads      Set specific number of download threads");
        System.out.println(" -Strace        Write a trace.edn file that traces deps expansion");
        System.out.println(" --             Stop parsing dep options and pass remaining arguments to clojure.main");
        System.out.println(" --version      Print the version to stdout and exit");
        System.out.println(" -version       Print the version to stdout and exit");
        System.out.println();
        System.out.println("The following non-standard options are available only in deps.clj:");
        System.out.println();
        System.out.println(" -Sdeps-file    Use this file instead of deps.edn");
        System.out.println(" -Scommand      A custom command that will be invoked. Substitutions: {{classpath}}, {{main-opts}}.");
        System.out.println();
        System.out.println("init-opt:");
        System.out.println(" -i, --init path     Load a file or resource");
        System.out.println(" -e, --eval string   Eval exprs in string; print non-nil values");
        System.out.println(" --report target     Report uncaught exception to \"file\" (default), \"stderr\", or \"none\"");
        System.out.println();
        System.out.println("main-opt:");
        System.out.println(" -m, --main ns-name  Call the -main function from namespace w/args");
        System.out.println(" -r, --repl          Run a repl");
        System.out.println(" path                Run a script from a file or resource");
        System.out.println(" -                   Run a script from standard input");
        System.out.println(" -h, -?, --help      Print this help message and exit");
        System.out.println();
        System.out.println("Programs provided by :deps alias:");
        System.out.println(" -X:deps aliases           List available aliases and their source");
        System.out.println(" -X:deps list              List full transitive deps set and licenses");
        System.out.println(" -X:deps tree              Print deps tree");
        System.out.println(" -X:deps basis             Print runtime basis");
        System.out.println(" -X:deps find-versions     Find available versions of a library");
        System.out.println(" -X:deps prep              Prepare all unprepped libs in the dep tree");
        System.out.println(" -X:deps mvn-pom           Generate (or update) pom.xml with deps and paths");
        System.out.println(" -X:deps mvn-install       Install a maven jar to the local repository cache");
        System.out.println(" -X:deps git-resolve-tags  Resolve git coord tags to shas and update deps.edn");
    }
}
