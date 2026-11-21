package cljl;

import cljl.lang.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Pure-Java Clojure CLI tools / deps.edn / basis / exec (-X / -T) engine for Clojol.
 * Implements standard `clj` & `deps.clj` flags, `.cpcache` caching, `:deps` alias tools,
 * local/Maven/gitlib dependency resolution, and dynamic classpath/JVM execution.
 */
public final class Deps {

    public static final String CLJ_TOOLS_VERSION = "1.12.6.1673";

    private Deps() {}

    public enum Mode {
        DEFAULT_MAIN,
        REPL_ALIAS,
        MAIN,
        EXEC,
        TOOL
    }

    public static final class CliOptions {
        public boolean help;
        public boolean version;
        public final List<String> jvmOpts = new ArrayList<>();
        public String sdeps;
        public boolean spath;
        public boolean stree;
        public String scp;
        public boolean srepro;
        public boolean sforce;
        public boolean sverbose;
        public int sthreads = 4;
        public boolean strace;
        public String sdepsFile;
        public String scommand;
        public boolean prepareOnly;
        public Mode mode = Mode.DEFAULT_MAIN;
        public final List<Keyword> aliases = new ArrayList<>();
        public String toolName;
        public boolean forceReplMode = false;
        public boolean sawExplicitCljOpt = false;
        public final List<String> remainingArgs = new ArrayList<>();
    }

    public static final class TreeNode {
        public final Symbol lib;
        public final Object coord;
        public final String versionLabel;
        public final String sourceInfo;
        public final List<String> licenses = new ArrayList<>();
        public final List<TreeNode> children = new ArrayList<>();
        public final Map<String, Object> prepLib = new LinkedHashMap<>();

        TreeNode(Symbol lib, Object coord, String versionLabel, String sourceInfo) {
            this.lib = lib;
            this.coord = coord;
            this.versionLabel = versionLabel;
            this.sourceInfo = sourceInfo;
        }
    }

    public static final class BasisResult {
        public final String classpath;
        public final List<String> classpathRoots;
        public final List<String> jvmOpts;
        public final List<String> mainOpts;
        private volatile PersistentHashMap basisMap;
        public final PersistentHashMap mergedDeps;
        public final PersistentHashMap argmap;
        public final List<String> configFiles;
        public final Path cacheDir;
        public final Path cpFile;
        public final Path basisFile;
        public final Path jvmFile;
        public final List<TreeNode> roots;
        public final Map<Symbol, TreeNode> libsBySymbol;
        public final Map<Object, Object> traceLog;
        public final Map<Keyword, String> aliasSources;

        BasisResult(
                String classpath,
                List<String> classpathRoots,
                List<String> jvmOpts,
                List<String> mainOpts,
                PersistentHashMap basisMap,
                PersistentHashMap mergedDeps,
                PersistentHashMap argmap,
                List<String> configFiles,
                Path cacheDir,
                Path cpFile,
                Path basisFile,
                Path jvmFile,
                List<TreeNode> roots,
                Map<Symbol, TreeNode> libsBySymbol,
                Map<Object, Object> traceLog,
                Map<Keyword, String> aliasSources) {
            this.classpath = classpath;
            this.classpathRoots = classpathRoots;
            this.jvmOpts = jvmOpts;
            this.mainOpts = mainOpts;
            this.basisMap = basisMap;
            this.mergedDeps = mergedDeps;
            this.argmap = argmap;
            this.configFiles = configFiles;
            this.cacheDir = cacheDir;
            this.cpFile = cpFile;
            this.basisFile = basisFile;
            this.jvmFile = jvmFile;
            this.roots = roots;
            this.libsBySymbol = libsBySymbol;
            this.traceLog = traceLog;
            this.aliasSources = aliasSources;
        }

        public PersistentHashMap getBasisMap() {
            PersistentHashMap m = this.basisMap;
            if (m == null && basisFile != null) {
                synchronized (this) {
                    m = this.basisMap;
                    if (m == null) {
                        try {
                            Object parsed = readEdn(Files.readString(basisFile, StandardCharsets.UTF_8));
                            m = (parsed instanceof PersistentHashMap pm) ? pm : PersistentHashMap.EMPTY;
                        } catch (Exception e) {
                            m = PersistentHashMap.EMPTY;
                        }
                        this.basisMap = m;
                    }
                }
            }
            return m != null ? m : PersistentHashMap.EMPTY;
        }
    }

    // ========================================================================
    // CLI Argument Parsing (matching `clj` / `clojure` / `deps.clj`)
    // ========================================================================

    public static CliOptions parseCli(String[] args) {
        CliOptions opts = new CliOptions();
        int i = 0;
        while (i < args.length) {
            String arg = args[i];

            if ("--".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                for (int j = i + 1; j < args.length; j++) {
                    opts.remainingArgs.add(args[j]);
                }
                break;
            }
            if ("-version".equals(arg) || "--version".equals(arg)) {
                opts.version = true;
                return opts;
            }
            if ("-h".equals(arg) || "-?".equals(arg) || "--help".equals(arg)) {
                opts.help = true;
                return opts;
            }
            if ("-J".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Option -J requires a JVM option argument");
                }
                opts.jvmOpts.add(args[++i]);
                i++;
                continue;
            }
            if (arg.startsWith("-J")) {
                opts.sawExplicitCljOpt = true;
                opts.jvmOpts.add(arg.substring(2));
                i++;
                continue;
            }
            if ("-Sdeps".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                if (i + 1 >= args.length) throw new IllegalArgumentException("-Sdeps requires an EDN or file argument");
                opts.sdeps = args[++i];
                i++;
                continue;
            }
            if ("-Spath".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.spath = true;
                i++;
                continue;
            }
            if ("-Stree".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.stree = true;
                i++;
                continue;
            }
            if ("-Scp".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                if (i + 1 >= args.length) throw new IllegalArgumentException("-Scp requires a classpath argument");
                opts.scp = args[++i];
                i++;
                continue;
            }
            if ("-Srepro".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.srepro = true;
                i++;
                continue;
            }
            if ("-Sforce".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.sforce = true;
                i++;
                continue;
            }
            if ("-Sverbose".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.sverbose = true;
                i++;
                continue;
            }
            if ("-Sthreads".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                if (i + 1 >= args.length) throw new IllegalArgumentException("-Sthreads requires an integer argument");
                opts.sthreads = Math.max(1, Integer.parseInt(args[++i]));
                i++;
                continue;
            }
            if ("-Strace".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.strace = true;
                i++;
                continue;
            }
            if ("-Sdeps-file".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                if (i + 1 >= args.length) throw new IllegalArgumentException("-Sdeps-file requires a file path");
                opts.sdepsFile = args[++i];
                i++;
                continue;
            }
            if ("-Scommand".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                if (i + 1 >= args.length) throw new IllegalArgumentException("-Scommand requires a command string");
                opts.scommand = args[++i];
                i++;
                continue;
            }
            if ("-P".equals(arg)) {
                opts.sawExplicitCljOpt = true;
                opts.prepareOnly = true;
                i++;
                continue;
            }
            if (arg.startsWith("-A")) {
                opts.sawExplicitCljOpt = true;
                opts.mode = Mode.REPL_ALIAS;
                parseAliasesInto(arg.substring(2), opts.aliases);
                for (int j = i + 1; j < args.length; j++) {
                    opts.remainingArgs.add(args[j]);
                }
                break;
            }
            if (arg.startsWith("-M")) {
                opts.sawExplicitCljOpt = true;
                opts.mode = Mode.MAIN;
                parseAliasesInto(arg.substring(2), opts.aliases);
                for (int j = i + 1; j < args.length; j++) {
                    opts.remainingArgs.add(args[j]);
                }
                break;
            }
            if (arg.startsWith("-X")) {
                opts.sawExplicitCljOpt = true;
                opts.mode = Mode.EXEC;
                parseAliasesInto(arg.substring(2), opts.aliases);
                for (int j = i + 1; j < args.length; j++) {
                    opts.remainingArgs.add(args[j]);
                }
                break;
            }
            if (arg.startsWith("-T")) {
                opts.sawExplicitCljOpt = true;
                opts.mode = Mode.TOOL;
                String suffix = arg.substring(2);
                if (!suffix.isEmpty()) {
                    if (suffix.startsWith(":")) {
                        parseAliasesInto(suffix, opts.aliases);
                    } else {
                        opts.toolName = suffix;
                    }
                }
                for (int j = i + 1; j < args.length; j++) {
                    opts.remainingArgs.add(args[j]);
                }
                break;
            }
            if (arg.startsWith("-S")) {
                throw new IllegalArgumentException("Unknown clj option: " + arg);
            }

            // Reached init-opt / main-opt (clojure.main arguments)
            for (int j = i; j < args.length; j++) {
                opts.remainingArgs.add(args[j]);
            }
            break;
        }
        return opts;
    }

    public static void parseAliasesInto(String spec, List<Keyword> out) {
        if (spec == null || spec.isEmpty()) return;
        int start = 0;
        if (spec.charAt(0) == ':') start = 1;
        String[] parts = spec.substring(start).split(":");
        for (String p : parts) {
            if (!p.isEmpty()) {
                out.add(Keyword.intern(p));
            }
        }
    }

    // ========================================================================
    // Paths & Config Directories
    // ========================================================================

    public static Path getConfigDir() {
        String env = System.getenv("CLJ_CONFIG");
        if (env != null && !env.isEmpty()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".clojure");
    }

    public static Path getM2RepoDir() {
        String env = System.getenv("M2_REPO");
        if (env != null && !env.isEmpty()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    public static Path getGitLibsDir() {
        String env = System.getenv("GITLIBS");
        if (env != null && !env.isEmpty()) return Path.of(env);
        return Path.of(System.getProperty("user.home"), ".gitlibs");
    }

    public static Path getCacheDir(Path projectDir, Path projectDepsFile) {
        String env = System.getenv("CLJ_CACHE");
        if (env != null && !env.isEmpty()) return Path.of(env);
        if (projectDepsFile != null && Files.exists(projectDepsFile)) {
            return projectDir.resolve(".cpcache");
        }
        return getConfigDir().resolve(".cpcache");
    }

    public static boolean needsDepsResolution(CliOptions opts) {
        if (opts.sawExplicitCljOpt) return true;
        Path projDeps = opts.sdepsFile != null ? Path.of(opts.sdepsFile) : Path.of("deps.edn");
        if (Files.isRegularFile(projDeps)) return true;
        if (!opts.srepro && Files.isRegularFile(getConfigDir().resolve("deps.edn"))) return true;
        return false;
    }

    // ========================================================================
    // Deps Map Merging & Basis Computation
    // ========================================================================

    @SuppressWarnings("unchecked")
    public static BasisResult resolveBasis(CliOptions opts) {
        Path projectDir = Path.of("").toAbsolutePath().normalize();
        Path configDir = getConfigDir();
        Path userDepsPath = configDir.resolve("deps.edn");
        Path projectDepsPath = opts.sdepsFile != null
                ? Path.of(opts.sdepsFile).toAbsolutePath().normalize()
                : projectDir.resolve("deps.edn");
        Path cacheDir = getCacheDir(projectDir, projectDepsPath);

        List<String> configFiles = new ArrayList<>();
        Map<Object, Object> merged = new LinkedHashMap<>();
        Map<Keyword, String> aliasSources = new LinkedHashMap<>();

        // Default Maven repos & built-in :deps alias
        Map<Object, Object> rootDefaults = defaultRootDepsMap();
        recordAliasSources(rootDefaults, "root", aliasSources);
        merged = mergeDepsMaps(merged, rootDefaults);

        // User ~/.clojure/deps.edn (unless -Srepro)
        if (!opts.srepro && Files.isRegularFile(userDepsPath)) {
            configFiles.add(userDepsPath.toString());
            Map<Object, Object> userMap = readEdnMap(userDepsPath);
            recordAliasSources(userMap, userDepsPath.toString(), aliasSources);
            merged = mergeDepsMaps(merged, userMap);
        }

        // Named tool -Tname from ~/.clojure/tools/<name>.edn
        Map<Object, Object> namedToolData = null;
        if (opts.mode == Mode.TOOL && opts.toolName != null) {
            Path toolPath = configDir.resolve("tools").resolve(opts.toolName + ".edn");
            if (!Files.isRegularFile(toolPath)) {
                throw new IllegalArgumentException("Tool not installed: " + opts.toolName + " (expected " + toolPath + ")");
            }
            configFiles.add(toolPath.toString());
            namedToolData = readEdnMap(toolPath);
        }

        // Project deps.edn (note: -T:alias ignores project :paths/:deps, but still reads project :aliases)
        Map<Object, Object> projectMap = null;
        if (Files.isRegularFile(projectDepsPath)) {
            configFiles.add(projectDepsPath.toString());
            projectMap = readEdnMap(projectDepsPath);
            recordAliasSources(projectMap, projectDepsPath.toString(), aliasSources);
            if (opts.mode == Mode.TOOL) {
                Object projAliases = projectMap.get(Keyword.intern("aliases"));
                if (projAliases != null) {
                    Map<Object, Object> aliasesOnly = new LinkedHashMap<>();
                    aliasesOnly.put(Keyword.intern("aliases"), projAliases);
                    merged = mergeDepsMaps(merged, aliasesOnly);
                }
            } else {
                merged = mergeDepsMaps(merged, projectMap);
            }
        } else if (opts.sdepsFile != null) {
            throw new IllegalArgumentException("Specified deps file not found: " + opts.sdepsFile);
        }

        if (namedToolData != null) {
            // Tool install edn behaves like a standalone deps map with optional :ns-default/:ns-aliases/:exec-args
            merged.remove(Keyword.intern("paths"));
            merged.remove(Keyword.intern("deps"));
            merged = mergeDepsMaps(merged, namedToolData);
        }

        // -Sdeps EDN
        if (opts.sdeps != null) {
            Map<Object, Object> cliDepsMap = parseSdeps(opts.sdeps);
            recordAliasSources(cliDepsMap, "-Sdeps", aliasSources);
            merged = mergeDepsMaps(merged, cliDepsMap);
        }

        // Default :paths ["src"] if not in tool mode and no :paths specified
        if (opts.mode != Mode.TOOL && namedToolData == null && !merged.containsKey(Keyword.intern("paths"))) {
            merged.put(Keyword.intern("paths"), Collections.singletonList("src"));
        }

        // Combine selected aliases
        Map<Object, Object> aliasesMap = toJavaMap(merged.get(Keyword.intern("aliases")));
        Map<Object, Object> argmap = new LinkedHashMap<>();
        if (namedToolData != null) {
            for (String k : new String[]{"ns-default", "ns-aliases", "exec-fn", "exec-args", "jvm-opts", "main-opts"}) {
                Keyword kw = Keyword.intern(k);
                if (namedToolData.containsKey(kw)) argmap.put(kw, namedToolData.get(kw));
            }
        }
        for (Keyword alias : opts.aliases) {
            Object rawAlias = aliasesMap.get(alias);
            if (rawAlias == null) {
                if (opts.sverbose) {
                    System.err.println("WARNING: Unknown alias: " + alias);
                }
                continue;
            }
            Map<Object, Object> am = toJavaMap(rawAlias);
            argmap = combineArgMaps(argmap, am);
        }

        // Cache key
        String cacheKey = computeCacheKey(opts, configFiles, merged, argmap, projectDir);
        Path cpFile = cacheDir.resolve(cacheKey + ".cp");
        Path basisFile = cacheDir.resolve(cacheKey + ".basis");
        Path jvmFile = cacheDir.resolve(cacheKey + ".jvm");

        // Fast cache hit if not forcing, not tracing/tree, and not :deps inspection commands
        boolean isDepsTool = (opts.mode == Mode.EXEC && opts.aliases.contains(Keyword.intern("deps")));
        if (opts.scp == null && !opts.sforce && !opts.stree && !opts.strace
                && !opts.prepareOnly && !isDepsTool
                && Files.isRegularFile(cpFile) && Files.isRegularFile(basisFile)) {
            if (isCacheFresh(cpFile, configFiles)) {
                try {
                    String cachedCp = Files.readString(cpFile, StandardCharsets.UTF_8).trim();
                    List<String> cachedRoots = splitClasspath(cachedCp);
                    List<String> cachedJvm = Files.isRegularFile(jvmFile)
                            ? readLinesNonEmpty(jvmFile) : extractJvmOpts(argmap);
                    List<String> mainOpts = extractMainOpts(opts, argmap);
                    return new BasisResult(
                            cachedCp, cachedRoots, cachedJvm, mainOpts, null,
                            toPersistentMap(merged), toPersistentMap(argmap), configFiles,
                            cacheDir, cpFile, basisFile, jvmFile,
                            Collections.emptyList(), Collections.emptyMap(), null, aliasSources);
                } catch (Exception ignored) {
                    // Fall through to recompute
                }
            }
        }

        // Full dependency resolution
        Map<Object, Object> traceLog = opts.strace ? new LinkedHashMap<>() : null;
        List<Object> traceSteps = opts.strace ? new ArrayList<>() : null;
        if (traceLog != null) traceLog.put(Keyword.intern("log"), traceSteps);

        List<String> resolvedPaths = resolvePathsList(merged, argmap, projectDir);
        Map<Symbol, Map<Object, Object>> resolvedTopDeps = resolveTopDepsMap(merged, argmap);
        Map<Symbol, Map<Object, Object>> overrideDeps = toSymbolCoordMap(argmap.get(Keyword.intern("override-deps")));
        Map<Symbol, Map<Object, Object>> defaultDeps = toSymbolCoordMap(argmap.get(Keyword.intern("default-deps")));
        Map<Symbol, String> cpOverrides = toSymbolStringMap(argmap.get(Keyword.intern("classpath-overrides")));
        Map<String, String> repos = extractRepos(merged);

        List<TreeNode> treeRoots = new ArrayList<>();
        Map<Symbol, TreeNode> libsBySymbol = new LinkedHashMap<>();

        if (opts.scp == null && !resolvedTopDeps.isEmpty()) {
            ExecutorService pool = Executors.newFixedThreadPool(opts.sthreads, r -> {
                Thread t = new Thread(r, "cljl-deps-worker");
                t.setDaemon(true);
                return t;
            });
            try {
                Set<Symbol> globalExclusions = new HashSet<>();
                for (Map.Entry<Symbol, Map<Object, Object>> e : resolvedTopDeps.entrySet()) {
                    Symbol lib = e.getKey();
                    Map<Object, Object> coord = applyOverridesAndDefaults(lib, e.getValue(), overrideDeps, defaultDeps);
                    if (coord == null) continue;
                    TreeNode node = resolveDepRecursive(
                            lib, coord, projectDir, repos, overrideDeps, defaultDeps,
                            globalExclusions, libsBySymbol, new HashSet<>(), pool, traceSteps, "root");
                    if (node != null) {
                        treeRoots.add(node);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }

        // Collect all classpath roots cleanly (paths first, then resolved lib roots)
        List<String> allRoots = new ArrayList<>();
        LinkedHashSet<String> seenRoots = new LinkedHashSet<>();
        if (opts.scp != null) {
            for (String p : splitClasspath(opts.scp)) {
                if (seenRoots.add(p)) allRoots.add(p);
            }
        } else {
            for (String p : resolvedPaths) {
                if (seenRoots.add(p)) allRoots.add(p);
            }
            for (Map.Entry<Symbol, TreeNode> e : libsBySymbol.entrySet()) {
                Symbol lib = e.getKey();
                if (cpOverrides.containsKey(lib)) {
                    String ov = resolveLocalPath(cpOverrides.get(lib), projectDir).toString();
                    if (seenRoots.add(ov)) allRoots.add(ov);
                    continue;
                }
                List<String> libPaths = extractNodePaths(e.getValue());
                for (String lp : libPaths) {
                    if (seenRoots.add(lp)) allRoots.add(lp);
                }
            }
        }

        String cpString = String.join(File.pathSeparator, allRoots);
        List<String> jvmOpts = extractJvmOpts(argmap);
        List<String> mainOpts = extractMainOpts(opts, argmap);

        PersistentHashMap basisMap = buildBasisMap(
                opts, configFiles, merged, argmap, resolvedPaths, allRoots, libsBySymbol);

        if (opts.strace && traceLog != null) {
            if (traceSteps != null) {
                traceLog.put(Keyword.intern("log"), PersistentVector.create(traceSteps));
            }
            traceLog.put(Keyword.intern("basis"), basisMap);
            writeTraceFile(Path.of("trace.edn"), traceLog);
        }

        // Save cache files if no -Scp
        if (opts.scp == null) {
            try {
                Files.createDirectories(cacheDir);
                Files.writeString(cpFile, cpString + "\n", StandardCharsets.UTF_8);
                Files.writeString(jvmFile, String.join("\n", jvmOpts) + (jvmOpts.isEmpty() ? "" : "\n"), StandardCharsets.UTF_8);
                Files.writeString(basisFile, RT.printString(basisMap, true) + "\n", StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Non-fatal if cache dir is read-only
            }
        }

        return new BasisResult(
                cpString, allRoots, jvmOpts, mainOpts, basisMap,
                toPersistentMap(merged), toPersistentMap(argmap), configFiles,
                cacheDir, cpFile, basisFile, jvmFile,
                treeRoots, libsBySymbol, traceLog, aliasSources);
    }

    public static void applyClasspath(BasisResult basis) {
        applyClasspath(basis, null);
    }

    public static void applyClasspath(BasisResult basis, CliOptions opts) {
        if (basis == null) return;
        System.setProperty("clojure.basis", basis.basisFile != null ? basis.basisFile.toString() : "");
        if (basis.classpath != null && !basis.classpath.isEmpty()) {
            String baseCp = System.getProperty("java.class.path", "");
            if (!baseCp.isEmpty() && !basis.classpath.contains(baseCp)) {
                System.setProperty("java.class.path", basis.classpath + File.pathSeparator + baseCp);
            } else {
                System.setProperty("java.class.path", basis.classpath);
            }
        }
        boolean strict = (opts != null && (opts.scp != null || opts.mode == Mode.TOOL
                || opts.sdeps != null || opts.sdepsFile != null || !opts.aliases.isEmpty()))
                || Files.isRegularFile(Path.of("deps.edn"));
        boolean anyExists = false;
        for (String root : basis.classpathRoots) {
            if (root != null && !root.isEmpty() && Files.exists(Path.of(root))) {
                anyExists = true;
                break;
            }
        }
        if (!anyExists && !strict) {
            return;
        }
        List<URL> urls = new ArrayList<>(basis.classpathRoots.size());
        for (String root : basis.classpathRoots) {
            if (root == null || root.isEmpty()) continue;
            try {
                urls.add(Path.of(root).toUri().toURL());
            } catch (Exception ignored) {}
        }
        if (!urls.isEmpty() || strict) {
            URLClassLoader ucl = new URLClassLoader(urls.toArray(new URL[0]), RT.class.getClassLoader());
            RT.setRuntimeClassLoader(ucl, strict);
        }
    }

    public static void printVerbose(BasisResult basis, CliOptions opts) {
        System.out.println("version      = Clojol " + Main.VERSION + " (clj " + CLJ_TOOLS_VERSION + ")");
        System.out.println("install_dir  = " + getInstallDir());
        System.out.println("config_dir   = " + getConfigDir());
        System.out.println("config_paths = " + String.join(" ", basis.configFiles));
        System.out.println("cache_dir    = " + basis.cacheDir);
        System.out.println("cp_file      = " + basis.cpFile);
        System.out.println("basis_file   = " + basis.basisFile);
        System.out.println("jvm_file     = " + basis.jvmFile);
        System.out.println("force        = " + opts.sforce);
        System.out.println("repro        = " + opts.srepro);
        System.out.println("threads      = " + opts.sthreads);
        System.out.println();
    }

    private static String getInstallDir() {
        try {
            URI loc = Deps.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(loc).getParent().toString();
        } catch (Exception e) {
            return Path.of("").toAbsolutePath().toString();
        }
    }

    public static void printTree(BasisResult basis) {
        for (TreeNode root : basis.roots) {
            printTreeNode(root, 0);
        }
    }

    private static void printTreeNode(TreeNode node, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        if (depth > 0) sb.append(". ");
        sb.append(node.lib).append(" ").append(node.versionLabel);
        System.out.println(sb);
        for (TreeNode ch : node.children) {
            printTreeNode(ch, depth + 1);
        }
    }

    // ========================================================================
    // -X / -T Function Execution (`clojure.exec`)
    // ========================================================================

    @SuppressWarnings("unchecked")
    public static void runExec(CliOptions opts, BasisResult basis) {
        initDepsCliApiNamespace(basis, opts);

        List<String> rawArgs = opts.remainingArgs;
        int idx = 0;
        Symbol cliFnSym = null;

        if (!rawArgs.isEmpty()) {
            Object firstEdn = parseEdnArg(rawArgs.get(0));
            if (firstEdn instanceof Symbol sym) {
                cliFnSym = sym;
                idx = 1;
            }
        }

        // Determine exec-fn
        Symbol execFnSym = cliFnSym;
        if (execFnSym == null) {
            Object aliasExecFn = basis.argmap.valAt(Keyword.intern("exec-fn"));
            if (aliasExecFn instanceof Symbol sym) {
                execFnSym = sym;
            }
        }
        if (execFnSym == null) {
            throw new IllegalArgumentException("No exec function specified (provide a/fn argument or :exec-fn in alias)");
        }

        // Resolve function symbol using :ns-default and :ns-aliases
        Symbol resolvedSym = resolveExecFnSymbol(execFnSym, basis.argmap);
        String nsName = resolvedSym.getNamespace();
        String fnName = resolvedSym.getName();
        if (nsName == null || nsName.isEmpty()) {
            throw new IllegalArgumentException("Exec function is not namespace-qualified and no :ns-default is configured: " + execFnSym);
        }

        // Build exec args map
        Object execArgsObj = basis.argmap.valAt(Keyword.intern("exec-args"));
        Object argsMap = (execArgsObj instanceof PersistentHashMap) ? execArgsObj : PersistentHashMap.EMPTY;

        while (idx < rawArgs.size()) {
            if (idx + 1 < rawArgs.size()) {
                Object kpath = parseEdnArg(rawArgs.get(idx));
                Object val = parseEdnArg(rawArgs.get(idx + 1));
                argsMap = assocInPath(argsMap, kpath, val);
                idx += 2;
            } else {
                // Optional trailing kv-map?
                Object tail = parseEdnArg(rawArgs.get(idx));
                if (tail instanceof PersistentHashMap tm) {
                    argsMap = mergeMaps((PersistentHashMap) argsMap, tm);
                    idx++;
                } else {
                    throw new IllegalArgumentException("Expected key/value pairs or trailing map, got orphan arg: " + rawArgs.get(idx));
                }
            }
        }

        // Load namespace & invoke
        Core.loadLib(nsName);
        Namespace targetNs = Namespace.find(nsName);
        if (targetNs == null) {
            throw new RuntimeException("Could not find namespace: " + nsName);
        }
        Var v = targetNs.findInternedVar(Symbol.intern(fnName));
        if (v == null || !v.isBound()) {
            throw new RuntimeException("Could not resolve exec function: " + resolvedSym);
        }
        Object fnObj = v.deref();
        if (!(fnObj instanceof Fn fn)) {
            throw new RuntimeException("Var " + resolvedSym + " is not a function: " + fnObj);
        }
        fn.invoke(argsMap);
    }

    private static Symbol resolveExecFnSymbol(Symbol fnSym, PersistentHashMap argmap) {
        if (fnSym.getNamespace() != null) {
            Object nsAliasesObj = argmap.valAt(Keyword.intern("ns-aliases"));
            if (nsAliasesObj instanceof PersistentHashMap am) {
                Symbol aliasKey = Symbol.intern(fnSym.getNamespace());
                Object targetNs = am.valAt(aliasKey);
                if (targetNs instanceof Symbol tsym) {
                    return Symbol.intern(tsym.name, fnSym.getName());
                }
            }
            return fnSym;
        }
        Object nsDefObj = argmap.valAt(Keyword.intern("ns-default"));
        if (nsDefObj instanceof Symbol ndSym) {
            return Symbol.intern(ndSym.name, fnSym.getName());
        }
        return fnSym;
    }

    private static Object parseEdnArg(String s) {
        try {
            return Reader.readString(s);
        } catch (Throwable t) {
            throw new IllegalArgumentException("Invalid EDN CLI argument '" + s + "': " + t.getMessage(), t);
        }
    }

    private static Object assocInPath(Object mapObj, Object kpath, Object val) {
        if (kpath instanceof PersistentVector pv) {
            return assocInVec(mapObj, pv, 0, val);
        }
        if (mapObj instanceof cljl.lang.Associative a) {
            return a.assoc(kpath, val);
        }
        return PersistentHashMap.create(kpath, val);
    }

    private static Object assocInVec(Object m, PersistentVector path, int idx, Object val) {
        Object k = path.nth(idx);
        if (idx == path.count() - 1) {
            return (m instanceof cljl.lang.Associative a) ? a.assoc(k, val) : PersistentHashMap.create(k, val);
        }
        Object cur = (m instanceof cljl.lang.Associative) ? RT.get(m, k) : null;
        Object updated = assocInVec(cur != null ? cur : PersistentHashMap.EMPTY, path, idx + 1, val);
        return (m instanceof cljl.lang.Associative a) ? a.assoc(k, updated) : PersistentHashMap.create(k, updated);
    }

    private static PersistentHashMap mergeMaps(PersistentHashMap a, PersistentHashMap b) {
        PersistentHashMap res = a;
        for (Map.Entry<?, ?> me : b.entrySet()) {
            res = res.assoc(me.getKey(), me.getValue());
        }
        return res;
    }

    // ========================================================================
    // Built-in `:deps` Alias Programs (`clojure.tools.deps.cli.api`)
    // ========================================================================

    public static synchronized void ensureDepsCliApiLoaded() {
        if (Namespace.find("clojure.tools.deps.cli.api") == null) {
            CliOptions opts = new CliOptions();
            opts.mode = Mode.EXEC;
            opts.aliases.add(Keyword.intern("deps"));
            BasisResult basis = resolveBasis(opts);
            initDepsCliApiNamespace(basis, opts);
        }
    }

    public static synchronized void initDepsCliApiNamespace(BasisResult basis, CliOptions opts) {
        Namespace ns = Namespace.findOrCreate("clojure.tools.deps.cli.api");
        ns.intern("aliases", Fn.of01("aliases",
                () -> apiAliases(basis, PersistentHashMap.EMPTY),
                arg -> apiAliases(basis, asMap(arg))));
        ns.intern("list", Fn.of01("list",
                () -> apiList(basis, PersistentHashMap.EMPTY),
                arg -> apiList(basis, asMap(arg))));
        ns.intern("tree", Fn.of01("tree",
                () -> apiTree(basis, PersistentHashMap.EMPTY),
                arg -> apiTree(basis, asMap(arg))));
        ns.intern("basis", Fn.of01("basis",
                () -> apiBasis(basis, PersistentHashMap.EMPTY),
                arg -> apiBasis(basis, asMap(arg))));
        ns.intern("find-versions", Fn.of1("find-versions",
                arg -> apiFindVersions(basis, asMap(arg))));
        ns.intern("prep", Fn.of01("prep",
                () -> apiPrep(basis, PersistentHashMap.EMPTY),
                arg -> apiPrep(basis, asMap(arg))));
        ns.intern("mvn-pom", Fn.of01("mvn-pom",
                () -> apiMvnPom(basis, PersistentHashMap.EMPTY),
                arg -> apiMvnPom(basis, asMap(arg))));
        ns.intern("mvn-install", Fn.of1("mvn-install",
                arg -> apiMvnInstall(asMap(arg))));
        ns.intern("git-resolve-tags", Fn.of01("git-resolve-tags",
                () -> apiGitResolveTags(opts, PersistentHashMap.EMPTY),
                arg -> apiGitResolveTags(opts, asMap(arg))));
        Core.markLibLoaded("clojure.tools.deps.cli.api");
    }

    private static PersistentHashMap asMap(Object o) {
        return (o instanceof PersistentHashMap m) ? m : PersistentHashMap.EMPTY;
    }

    private static Object apiAliases(BasisResult basis, PersistentHashMap args) {
        Object aliasesObj = basis.mergedDeps.valAt(Keyword.intern("aliases"));
        if (!(aliasesObj instanceof PersistentHashMap am) || am.count() == 0) {
            System.out.println("No aliases defined.");
            return null;
        }
        List<Keyword> keys = new ArrayList<>();
        for (Map.Entry<?, ?> me : am.entrySet()) {
            if (me.getKey() instanceof Keyword kw) keys.add(kw);
        }
        keys.sort(Comparator.comparing(Keyword::toString));
        for (Keyword k : keys) {
            String src = basis.aliasSources.getOrDefault(k, "unknown");
            Object val = am.valAt(k);
            System.out.println(k + " (" + src + ")");
            if (val instanceof PersistentHashMap vm) {
                Object doc = vm.valAt(Keyword.intern("doc"));
                if (doc != null) {
                    System.out.println("  " + doc);
                }
            }
        }
        return null;
    }

    private static Object apiList(BasisResult basis, PersistentHashMap args) {
        if (basis.libsBySymbol.isEmpty()) {
            System.out.println("(no external libraries)");
            return null;
        }
        List<Symbol> libs = new ArrayList<>(basis.libsBySymbol.keySet());
        libs.sort(Comparator.comparing(Symbol::toString));
        for (Symbol lib : libs) {
            TreeNode node = basis.libsBySymbol.get(lib);
            String lic = node.licenses.isEmpty() ? "" : " (" + String.join(", ", node.licenses) + ")";
            System.out.println(lib + " " + node.versionLabel + lic);
        }
        return null;
    }

    private static Object apiTree(BasisResult basis, PersistentHashMap args) {
        Object fmt = args.valAt(Keyword.intern("format"));
        if (Keyword.intern("edn").equals(fmt)) {
            List<Object> items = new ArrayList<>();
            for (TreeNode n : basis.roots) items.add(treeNodeToEdn(n));
            System.out.println(RT.printString(PersistentVector.create(items), true));
            return null;
        }
        printTree(basis);
        return null;
    }

    private static PersistentHashMap treeNodeToEdn(TreeNode n) {
        List<Object> ch = new ArrayList<>();
        for (TreeNode c : n.children) ch.add(treeNodeToEdn(c));
        return PersistentHashMap.create(
                Keyword.intern("lib"), n.lib,
                Keyword.intern("coord"), n.coord,
                Keyword.intern("children"), PersistentVector.create(ch));
    }

    private static Object apiBasis(BasisResult basis, PersistentHashMap args) {
        System.out.println(RT.printString(basis.getBasisMap(), true));
        return null;
    }

    private static Object apiFindVersions(BasisResult basis, PersistentHashMap args) {
        Object libObj = args.valAt(Keyword.intern("lib"));
        if (!(libObj instanceof Symbol lib)) {
            throw new IllegalArgumentException(":deps find-versions requires :lib <group/artifact>");
        }
        int limit = 10;
        Object nObj = args.valAt(Keyword.intern("n"));
        if (nObj instanceof Number num) limit = num.intValue();

        Object gitUrl = args.valAt(Keyword.intern("git/url"));
        if (gitUrl != null) {
            List<String> tags = listGitTags(gitUrl.toString());
            int start = Math.max(0, tags.size() - limit);
            for (int i = start; i < tags.size(); i++) {
                System.out.println("{:git/tag \"" + tags.get(i) + "\"}");
            }
            return null;
        }

        String group = lib.getNamespace();
        String artifact = lib.getName();
        if (group == null) group = artifact;
        Map<String, String> repos = extractRepos(toJavaMap(basis.mergedDeps));
        List<String> versions = fetchMavenVersions(group, artifact, repos);
        int start = Math.max(0, versions.size() - limit);
        for (int i = start; i < versions.size(); i++) {
            System.out.println("{:mvn/version \"" + versions.get(i) + "\"}");
        }
        return null;
    }

    private static Object apiPrep(BasisResult basis, PersistentHashMap args) {
        boolean force = RT.truthy(args.valAt(Keyword.intern("force")));
        for (Map.Entry<Symbol, TreeNode> e : basis.libsBySymbol.entrySet()) {
            TreeNode node = e.getValue();
            if (node.prepLib.isEmpty()) continue;
            Object ensure = node.prepLib.get("ensure");
            if (!force && ensure != null && Files.exists(Path.of(ensure.toString()))) {
                continue;
            }
            Object fnObj = node.prepLib.get("fn");
            if (fnObj instanceof Symbol fnSym) {
                System.out.println("Prepping " + e.getKey() + " via " + fnSym);
                String nsName = fnSym.getNamespace();
                if (nsName != null) {
                    Core.loadLib(nsName);
                    Var v = Namespace.find(nsName).findInternedVar(Symbol.intern(fnSym.getName()));
                    if (v != null && v.deref() instanceof Fn fn) {
                        fn.invoke(PersistentHashMap.EMPTY);
                    }
                }
            }
        }
        return null;
    }

    private static Object apiMvnPom(BasisResult basis, PersistentHashMap args) {
        Path pomPath = Path.of("pom.xml");
        String groupId = stringOpt(args, "group", "cljl.app");
        String artifactId = stringOpt(args, "artifact", Path.of("").toAbsolutePath().getFileName().toString());
        String version = stringOpt(args, "version", "0.1.0");
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        sb.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        sb.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        sb.append("  <modelVersion>4.0.0</modelVersion>\n");
        sb.append("  <groupId>").append(xmlEscape(groupId)).append("</groupId>\n");
        sb.append("  <artifactId>").append(xmlEscape(artifactId)).append("</artifactId>\n");
        sb.append("  <version>").append(xmlEscape(version)).append("</version>\n");
        sb.append("  <dependencies>\n");
        for (Map.Entry<Symbol, TreeNode> e : basis.libsBySymbol.entrySet()) {
            Symbol lib = e.getKey();
            TreeNode node = e.getValue();
            Map<Object, Object> coord = toJavaMap(node.coord);
            Object mvnVer = coord.get(Keyword.intern("mvn/version"));
            if (mvnVer != null) {
                String g = lib.getNamespace() != null ? lib.getNamespace() : lib.getName();
                String a = lib.getName();
                sb.append("    <dependency>\n");
                sb.append("      <groupId>").append(xmlEscape(g)).append("</groupId>\n");
                sb.append("      <artifactId>").append(xmlEscape(a)).append("</artifactId>\n");
                sb.append("      <version>").append(xmlEscape(mvnVer.toString())).append("</version>\n");
                sb.append("    </dependency>\n");
            }
        }
        sb.append("  </dependencies>\n");
        sb.append("</project>\n");
        try {
            Files.writeString(pomPath, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("Wrote " + pomPath.toAbsolutePath());
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
        return null;
    }

    private static Object apiMvnInstall(PersistentHashMap args) {
        Object jarObj = args.valAt(Keyword.intern("jar"));
        if (jarObj == null) {
            throw new IllegalArgumentException(":deps mvn-install requires :jar path");
        }
        Path jarPath = Path.of(jarObj.toString());
        if (!Files.isRegularFile(jarPath)) {
            throw new IllegalArgumentException("JAR file not found: " + jarPath);
        }
        Object pomObj = args.valAt(Keyword.intern("pom"));
        Path pomPath = pomObj != null ? Path.of(pomObj.toString()) : null;
        String libStr = args.valAt(Keyword.intern("lib")) != null ? args.valAt(Keyword.intern("lib")).toString() : null;
        String verStr = args.valAt(Keyword.intern("version")) != null ? args.valAt(Keyword.intern("version")).toString() : null;

        // Inspect META-INF/maven/pom.properties inside jar if lib/version omitted
        if ((libStr == null || verStr == null)) {
            try (JarFile jf = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry je = en.nextElement();
                    if (je.getName().endsWith("pom.properties") && je.getName().startsWith("META-INF/maven/")) {
                        Properties props = new Properties();
                        try (InputStream is = jf.getInputStream(je)) {
                            props.load(is);
                        }
                        if (libStr == null && props.getProperty("groupId") != null && props.getProperty("artifactId") != null) {
                            libStr = props.getProperty("groupId") + "/" + props.getProperty("artifactId");
                        }
                        if (verStr == null && props.getProperty("version") != null) {
                            verStr = props.getProperty("version");
                        }
                        break;
                    }
                }
            } catch (IOException ignored) {}
        }

        if (libStr == null || verStr == null) {
            throw new IllegalArgumentException("Could not infer :lib and :version from jar; specify :lib and :version explicitly");
        }
        String[] parts = libStr.contains("/") ? libStr.split("/", 2) : new String[]{libStr, libStr};
        String group = parts[0];
        String artifact = parts[1];

        String localRepoStr = args.valAt(Keyword.intern("local-repo")) != null
                ? args.valAt(Keyword.intern("local-repo")).toString() : getM2RepoDir().toString();
        Path targetDir = Path.of(localRepoStr).resolve(group.replace('.', '/')).resolve(artifact).resolve(verStr);
        try {
            Files.createDirectories(targetDir);
            Path targetJar = targetDir.resolve(artifact + "-" + verStr + ".jar");
            Files.copy(jarPath, targetJar, StandardCopyOption.REPLACE_EXISTING);
            Path targetPom = targetDir.resolve(artifact + "-" + verStr + ".pom");
            if (pomPath != null && Files.isRegularFile(pomPath)) {
                Files.copy(pomPath, targetPom, StandardCopyOption.REPLACE_EXISTING);
            } else {
                String minPom = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>" + xmlEscape(group) + "</groupId>\n"
                        + "  <artifactId>" + xmlEscape(artifact) + "</artifactId>\n"
                        + "  <version>" + xmlEscape(verStr) + "</version>\n"
                        + "</project>\n";
                Files.writeString(targetPom, minPom, StandardCharsets.UTF_8);
            }
            System.out.println("Installed " + targetJar);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
        return null;
    }

    private static Object apiGitResolveTags(CliOptions opts, PersistentHashMap args) {
        Object fileArg = args.valAt(Keyword.intern("deps-file"));
        Path depsPath = fileArg != null ? Path.of(fileArg.toString())
                : (opts != null && opts.sdepsFile != null ? Path.of(opts.sdepsFile) : Path.of("deps.edn"));
        if (!Files.isRegularFile(depsPath)) {
            throw new IllegalArgumentException("deps.edn not found: " + depsPath);
        }
        try {
            String content = Files.readString(depsPath, StandardCharsets.UTF_8);
            // Resolve any map block containing :git/url and :git/tag that is missing :git/sha
            Pattern blockPat = Pattern.compile("(\\{[^{}]*\\})");
            Pattern urlPat = Pattern.compile(":git/url\\s+\"([^\"]+)\"");
            Pattern tagPat = Pattern.compile(":git/tag\\s+\"([^\"]+)\"");
            Matcher m = blockPat.matcher(content);
            StringBuilder out = new StringBuilder();
            int last = 0;
            int updated = 0;
            while (m.find()) {
                out.append(content, last, m.start());
                String block = m.group(1);
                Matcher um = urlPat.matcher(block);
                Matcher tm = tagPat.matcher(block);
                if (um.find() && tm.find() && !block.contains(":git/sha")) {
                    String sha = resolveGitTagSha(um.group(1), tm.group(1));
                    if (sha != null) {
                        block = block.substring(0, block.length() - 1) + " :git/sha \"" + sha + "\"}";
                        updated++;
                    }
                }
                out.append(block);
                last = m.end();
            }
            out.append(content, last, content.length());
            if (updated > 0) {
                Files.writeString(depsPath, out.toString(), StandardCharsets.UTF_8);
            }
            System.out.println("Updated " + updated + " git tag coord(s) in " + depsPath);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
        return null;
    }

    private static String stringOpt(PersistentHashMap args, String kw, String def) {
        Object v = args.valAt(Keyword.intern(kw));
        return v != null ? v.toString() : def;
    }

    // ========================================================================
    // Dependency Coordinate Resolution (Local, Maven, Git)
    // ========================================================================

    private static TreeNode resolveDepRecursive(
            Symbol lib,
            Map<Object, Object> coord,
            Path baseDir,
            Map<String, String> repos,
            Map<Symbol, Map<Object, Object>> overrideDeps,
            Map<Symbol, Map<Object, Object>> defaultDeps,
            Set<Symbol> parentExclusions,
            Map<Symbol, TreeNode> libsBySymbol,
            Set<Symbol> visiting,
            ExecutorService pool,
            List<Object> traceSteps,
            String reason) {

        if (parentExclusions.contains(lib) || visiting.contains(lib)) {
            return null;
        }
        if (libsBySymbol.containsKey(lib)) {
            return libsBySymbol.get(lib);
        }

        visiting.add(lib);
        try {
            Object localRootObj = coord.get(Keyword.intern("local/root"));
            Object mvnVerObj = coord.get(Keyword.intern("mvn/version"));
            Object gitUrlObj = coord.get(Keyword.intern("git/url"));
            Object gitShaObj = coord.get(Keyword.intern("git/sha"));
            if (gitShaObj == null) gitShaObj = coord.get(Keyword.intern("sha"));

            TreeNode node;
            Set<Symbol> childExclusions = new HashSet<>(parentExclusions);
            Object exObj = coord.get(Keyword.intern("exclusions"));
            if (exObj instanceof Iterable<?> it) {
                for (Object ex : it) {
                    if (ex instanceof Symbol exSym) childExclusions.add(exSym);
                }
            }

            if (localRootObj != null) {
                Path localPath = resolveLocalPath(localRootObj.toString(), baseDir);
                node = new TreeNode(lib, toPersistentMap(coord), localRootObj.toString(), localPath.toString());
                libsBySymbol.put(lib, node);
                recordTrace(traceSteps, lib, coord, reason);

                if (Files.isDirectory(localPath)) {
                    Path childDepsEdn = localPath.resolve("deps.edn");
                    if (Files.isRegularFile(childDepsEdn)) {
                        Map<Object, Object> subMap = readEdnMap(childDepsEdn);
                        extractPrepLib(subMap, node);
                        Map<Symbol, Map<Object, Object>> subDeps = toSymbolCoordMap(subMap.get(Keyword.intern("deps")));
                        for (Map.Entry<Symbol, Map<Object, Object>> se : subDeps.entrySet()) {
                            Map<Object, Object> sc = applyOverridesAndDefaults(se.getKey(), se.getValue(), overrideDeps, defaultDeps);
                            if (sc == null) continue;
                            TreeNode child = resolveDepRecursive(
                                    se.getKey(), sc, localPath, repos, overrideDeps, defaultDeps,
                                    childExclusions, libsBySymbol, visiting, pool, traceSteps, lib.toString());
                            if (child != null) node.children.add(child);
                        }
                    }
                }
                return node;
            }

            if (mvnVerObj != null) {
                String ver = mvnVerObj.toString();
                String group = lib.getNamespace() != null ? lib.getNamespace() : lib.getName();
                String artifact = lib.getName();
                if ("RELEASE".equals(ver) || "LATEST".equals(ver)) {
                    List<String> vers = fetchMavenVersions(group, artifact, repos);
                    if (!vers.isEmpty()) ver = vers.get(vers.size() - 1);
                }
                String classifier = coord.get(Keyword.intern("classifier")) != null
                        ? coord.get(Keyword.intern("classifier")).toString() : null;
                String ext = coord.get(Keyword.intern("extension")) != null
                        ? coord.get(Keyword.intern("extension")).toString() : "jar";

                Path m2 = getM2RepoDir();
                String relBase = group.replace('.', '/') + "/" + artifact + "/" + ver + "/" + artifact + "-" + ver;
                Path pomPath = m2.resolve(relBase + ".pom");
                Path jarPath = m2.resolve(relBase + (classifier != null ? "-" + classifier : "") + "." + ext);

                if (!Files.isRegularFile(pomPath)) {
                    downloadFromRepos(relBase + ".pom", pomPath, repos);
                }
                if (!"pom".equals(ext) && !Files.isRegularFile(jarPath)) {
                    downloadFromRepos(relBase + (classifier != null ? "-" + classifier : "") + "." + ext, jarPath, repos);
                }

                node = new TreeNode(lib, toPersistentMap(coord), ver, jarPath.toString());
                libsBySymbol.put(lib, node);
                recordTrace(traceSteps, lib, coord, reason);

                if (Files.isRegularFile(pomPath)) {
                    PomInfo pom = parsePom(pomPath, repos, m2);
                    node.licenses.addAll(pom.licenses);
                    for (PomDep pd : pom.deps) {
                        if (pd.optional || "test".equals(pd.scope) || "provided".equals(pd.scope) || "system".equals(pd.scope)) {
                            continue;
                        }
                        Symbol childSym = Symbol.intern(pd.groupId, pd.artifactId);
                        if (childExclusions.contains(childSym)) continue;
                        Map<Object, Object> cc = new LinkedHashMap<>();
                        cc.put(Keyword.intern("mvn/version"), pd.version);
                        if (pd.exclusions != null && !pd.exclusions.isEmpty()) {
                            cc.put(Keyword.intern("exclusions"), new ArrayList<>(pd.exclusions));
                        }
                        cc = applyOverridesAndDefaults(childSym, cc, overrideDeps, defaultDeps);
                        if (cc == null) continue;
                        TreeNode child = resolveDepRecursive(
                                childSym, cc, baseDir, repos, overrideDeps, defaultDeps,
                                childExclusions, libsBySymbol, visiting, pool, traceSteps, lib.toString());
                        if (child != null) node.children.add(child);
                    }
                }
                return node;
            }

            if (gitUrlObj != null && gitShaObj != null) {
                String url = gitUrlObj.toString();
                String sha = gitShaObj.toString();
                String group = lib.getNamespace() != null ? lib.getNamespace() : lib.getName();
                String artifact = lib.getName();
                Path gitDir = getGitLibsDir().resolve("libs").resolve(group).resolve(artifact).resolve(sha);
                ensureGitCheckout(url, sha, gitDir);

                Path rootSub = gitDir;
                Object depsRootObj = coord.get(Keyword.intern("deps/root"));
                if (depsRootObj != null) {
                    rootSub = gitDir.resolve(depsRootObj.toString()).normalize();
                }

                String shortSha = sha.length() > 7 ? sha.substring(0, 7) : sha;
                node = new TreeNode(lib, toPersistentMap(coord), shortSha, rootSub.toString());
                libsBySymbol.put(lib, node);
                recordTrace(traceSteps, lib, coord, reason);

                Path subDepsFile = rootSub.resolve("deps.edn");
                if (Files.isRegularFile(subDepsFile)) {
                    Map<Object, Object> subMap = readEdnMap(subDepsFile);
                    extractPrepLib(subMap, node);
                    Map<Symbol, Map<Object, Object>> subDeps = toSymbolCoordMap(subMap.get(Keyword.intern("deps")));
                    for (Map.Entry<Symbol, Map<Object, Object>> se : subDeps.entrySet()) {
                        Map<Object, Object> sc = applyOverridesAndDefaults(se.getKey(), se.getValue(), overrideDeps, defaultDeps);
                        if (sc == null) continue;
                        TreeNode child = resolveDepRecursive(
                                se.getKey(), sc, rootSub, repos, overrideDeps, defaultDeps,
                                childExclusions, libsBySymbol, visiting, pool, traceSteps, lib.toString());
                        if (child != null) node.children.add(child);
                    }
                }
                return node;
            }

            return null;
        } finally {
            visiting.remove(lib);
        }
    }

    private static void extractPrepLib(Map<Object, Object> subMap, TreeNode node) {
        Object pl = subMap.get(Keyword.intern("deps/prep-lib"));
        if (pl != null) {
            Map<Object, Object> pm = toJavaMap(pl);
            for (Map.Entry<Object, Object> e : pm.entrySet()) {
                if (e.getKey() instanceof Keyword kw) {
                    node.prepLib.put(kw.getName(), e.getValue());
                }
            }
        }
    }

    private static void recordTrace(List<Object> traceSteps, Symbol lib, Map<Object, Object> coord, String reason) {
        if (traceSteps == null) return;
        traceSteps.add(PersistentHashMap.create(
                Keyword.intern("lib"), lib,
                Keyword.intern("coord"), toPersistentMap(coord),
                Keyword.intern("reason"), reason));
    }

    private static List<String> extractNodePaths(TreeNode node) {
        List<String> out = new ArrayList<>();
        Map<Object, Object> coord = toJavaMap(node.coord);
        if (coord.containsKey(Keyword.intern("mvn/version"))) {
            String ext = coord.get(Keyword.intern("extension")) != null
                    ? coord.get(Keyword.intern("extension")).toString() : "jar";
            if (!"pom".equals(ext) && node.sourceInfo != null) {
                out.add(node.sourceInfo);
            }
            return out;
        }
        Path p = Path.of(node.sourceInfo);
        if (Files.isRegularFile(p)) {
            out.add(p.toString());
        } else if (Files.isDirectory(p)) {
            Path subDeps = p.resolve("deps.edn");
            if (Files.isRegularFile(subDeps)) {
                Map<Object, Object> m = readEdnMap(subDeps);
                Object ps = m.get(Keyword.intern("paths"));
                if (ps instanceof Iterable<?> it) {
                    for (Object item : it) {
                        out.add(p.resolve(item.toString()).normalize().toString());
                    }
                } else {
                    out.add(p.resolve("src").toString());
                }
            } else {
                out.add(p.toString());
            }
        }
        return out;
    }

    // ========================================================================
    // Maven HTTP + POM Parsing Helpers
    // ========================================================================

    private static final class PomDep {
        String groupId;
        String artifactId;
        String version;
        String scope = "compile";
        boolean optional = false;
        Set<Symbol> exclusions = new HashSet<>();
    }

    private static final class PomInfo {
        final Map<String, String> properties = new LinkedHashMap<>();
        final Map<String, String> managedVersions = new LinkedHashMap<>();
        final List<PomDep> deps = new ArrayList<>();
        final List<String> licenses = new ArrayList<>();
    }

    private static PomInfo parsePom(Path pomPath, Map<String, String> repos, Path m2) {
        PomInfo info = new PomInfo();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(pomPath.toFile());
            Element root = doc.getDocumentElement();

            String groupId = childText(root, "groupId");
            String artifactId = childText(root, "artifactId");
            String version = childText(root, "version");

            // Parent inheritance
            Element parentEl = childElement(root, "parent");
            if (parentEl != null) {
                String pg = childText(parentEl, "groupId");
                String pa = childText(parentEl, "artifactId");
                String pv = childText(parentEl, "version");
                if (groupId == null) groupId = pg;
                if (version == null) version = pv;
                if (pg != null && pa != null && pv != null) {
                    String pRel = pg.replace('.', '/') + "/" + pa + "/" + pv + "/" + pa + "-" + pv + ".pom";
                    Path pPom = m2.resolve(pRel);
                    if (!Files.isRegularFile(pPom)) {
                        downloadFromRepos(pRel, pPom, repos);
                    }
                    if (Files.isRegularFile(pPom)) {
                        PomInfo parentInfo = parsePom(pPom, repos, m2);
                        info.properties.putAll(parentInfo.properties);
                        info.managedVersions.putAll(parentInfo.managedVersions);
                    }
                }
            }

            if (groupId != null) info.properties.put("project.groupId", groupId);
            if (artifactId != null) info.properties.put("project.artifactId", artifactId);
            if (version != null) info.properties.put("project.version", version);

            Element propsEl = childElement(root, "properties");
            if (propsEl != null) {
                NodeList nl = propsEl.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    if (nl.item(i) instanceof Element pe) {
                        info.properties.put(pe.getTagName(), pe.getTextContent().trim());
                    }
                }
            }

            Element licEl = childElement(root, "licenses");
            if (licEl != null) {
                NodeList nl = licEl.getElementsByTagName("license");
                for (int i = 0; i < nl.getLength(); i++) {
                    if (nl.item(i) instanceof Element le) {
                        String ln = childText(le, "name");
                        if (ln != null) info.licenses.add(ln);
                    }
                }
            }

            Element dmEl = childElement(root, "dependencyManagement");
            if (dmEl != null) {
                Element dmDeps = childElement(dmEl, "dependencies");
                if (dmDeps != null) {
                    for (Element de : childElements(dmDeps, "dependency")) {
                        String g = resolveProps(childText(de, "groupId"), info.properties);
                        String a = resolveProps(childText(de, "artifactId"), info.properties);
                        String v = resolveProps(childText(de, "version"), info.properties);
                        if (g != null && a != null && v != null) {
                            info.managedVersions.put(g + ":" + a, v);
                        }
                    }
                }
            }

            Element depsEl = childElement(root, "dependencies");
            if (depsEl != null) {
                for (Element de : childElements(depsEl, "dependency")) {
                    PomDep pd = new PomDep();
                    pd.groupId = resolveProps(childText(de, "groupId"), info.properties);
                    pd.artifactId = resolveProps(childText(de, "artifactId"), info.properties);
                    pd.version = resolveProps(childText(de, "version"), info.properties);
                    if (pd.version == null && pd.groupId != null && pd.artifactId != null) {
                        pd.version = info.managedVersions.get(pd.groupId + ":" + pd.artifactId);
                    }
                    String sc = childText(de, "scope");
                    if (sc != null) pd.scope = sc;
                    String opt = childText(de, "optional");
                    pd.optional = "true".equalsIgnoreCase(opt);
                    Element exsEl = childElement(de, "exclusions");
                    if (exsEl != null) {
                        for (Element exEl : childElements(exsEl, "exclusion")) {
                            String eg = childText(exEl, "groupId");
                            String ea = childText(exEl, "artifactId");
                            if (eg != null && ea != null) pd.exclusions.add(Symbol.intern(eg, ea));
                        }
                    }
                    if (pd.groupId != null && pd.artifactId != null && pd.version != null) {
                        info.deps.add(pd);
                    }
                }
            }
        } catch (Exception ignored) {
            // If POM cannot be parsed, return what we collected
        }
        return info;
    }

    private static String resolveProps(String s, Map<String, String> props) {
        if (s == null || !s.contains("${")) return s;
        String cur = s;
        for (int iter = 0; iter < 5 && cur.contains("${"); iter++) {
            Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            while (m.find()) {
                String repl = props.get(m.group(1));
                if (repl != null) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(repl));
                    any = true;
                }
            }
            m.appendTail(sb);
            cur = sb.toString();
            if (!any) break;
        }
        return cur;
    }

    private static Element childElement(Element parent, String tag) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element el && tag.equals(el.getTagName())) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element el && tag.equals(el.getTagName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static String childText(Element parent, String tag) {
        Element el = childElement(parent, tag);
        return el != null ? el.getTextContent().trim() : null;
    }

    private static void downloadFromRepos(String relPath, Path target, Map<String, String> repos) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException ignored) {}
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String baseUrl : repos.values()) {
            String url = baseUrl.endsWith("/") ? baseUrl + relPath : baseUrl + "/" + relPath;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    Files.write(target, resp.body());
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    private static List<String> fetchMavenVersions(String group, String artifact, Map<String, String> repos) {
        List<String> out = new ArrayList<>();
        String rel = group.replace('.', '/') + "/" + artifact + "/maven-metadata.xml";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        for (String baseUrl : repos.values()) {
            String url = baseUrl.endsWith("/") ? baseUrl + rel : baseUrl + "/" + rel;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(resp.body());
                    while (m.find()) {
                        out.add(m.group(1).trim());
                    }
                    if (!out.isEmpty()) return out;
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static void ensureGitCheckout(String url, String sha, Path targetDir) {
        if (Files.isDirectory(targetDir)) return;
        try {
            Files.createDirectories(targetDir.getParent());
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--quiet", url, targetDir.toString());
            Process p = pb.start();
            if (p.waitFor() == 0) {
                new ProcessBuilder("git", "-C", targetDir.toString(), "checkout", "--quiet", sha).start().waitFor();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch git dep " + url + " @ " + sha + ": " + e.getMessage(), e);
        }
    }

    private static List<String> listGitTags(String url) {
        List<String> tags = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("git", "ls-remote", "--tags", "--refs", url).start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    int idx = line.indexOf("refs/tags/");
                    if (idx >= 0) tags.add(line.substring(idx + "refs/tags/".length()).trim());
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return tags;
    }

    private static String resolveGitTagSha(String url, String tag) {
        try {
            Process p = new ProcessBuilder("git", "ls-remote", "--tags", url, "refs/tags/" + tag, "refs/tags/" + tag + "^{}").start();
            String lastSha = null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) lastSha = parts[0];
                }
            }
            p.waitFor();
            return lastSha;
        } catch (Exception e) {
            return null;
        }
    }

    // ========================================================================
    // EDN & Basis Map Construction Helpers
    // ========================================================================

    private static Map<Object, Object> defaultRootDepsMap() {
        Map<Object, Object> repos = new LinkedHashMap<>();
        repos.put("central", PersistentHashMap.create(Keyword.intern("url"), "https://repo1.maven.org/maven2/"));
        repos.put("clojars", PersistentHashMap.create(Keyword.intern("url"), "https://repo.clojars.org/"));

        Map<Object, Object> depsAlias = new LinkedHashMap<>();
        depsAlias.put(Keyword.intern("ns-default"), Symbol.intern("clojure.tools.deps.cli.api"));
        depsAlias.put(Keyword.intern("doc"), "Clojol built-in :deps CLI tools");

        Map<Object, Object> aliases = new LinkedHashMap<>();
        aliases.put(Keyword.intern("deps"), toPersistentMap(depsAlias));

        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(Keyword.intern("mvn/repos"), toPersistentMap(repos));
        m.put(Keyword.intern("aliases"), toPersistentMap(aliases));
        return m;
    }

    private static Map<Object, Object> parseSdeps(String sdeps) {
        String trimmed = sdeps.trim();
        if (trimmed.startsWith("{")) {
            Object v = readEdn(trimmed);
            if (!(v instanceof PersistentHashMap)) {
                throw new IllegalArgumentException("-Sdeps EDN must be a map");
            }
            return toJavaMap(v);
        }
        Path p = Path.of(sdeps);
        if (Files.isRegularFile(p)) {
            return readEdnMap(p);
        }
        throw new IllegalArgumentException("-Sdeps argument is neither an EDN map nor an existing file: " + sdeps);
    }

    private static Map<Object, Object> readEdnMap(Path p) {
        try {
            String src = Files.readString(p, StandardCharsets.UTF_8);
            Object v = readEdn(src);
            if (v == null) return new LinkedHashMap<>();
            if (!(v instanceof PersistentHashMap)) {
                throw new IllegalArgumentException("Expected map in " + p);
            }
            return toJavaMap(v);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static Object readEdn(String src) {
        if (src == null || src.isBlank()) return null;
        return Reader.readEdnString(null, src);
    }

    private static void recordAliasSources(Map<Object, Object> depsMap, String source, Map<Keyword, String> out) {
        Object aliasesObj = depsMap.get(Keyword.intern("aliases"));
        if (aliasesObj == null) return;
        for (Map.Entry<Object, Object> e : toJavaMap(aliasesObj).entrySet()) {
            if (e.getKey() instanceof Keyword kw) {
                out.put(kw, source);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> mergeDepsMaps(Map<Object, Object> base, Map<Object, Object> overlay) {
        Map<Object, Object> res = new LinkedHashMap<>(base);
        for (Map.Entry<Object, Object> e : overlay.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            Object cur = res.get(k);
            if (Keyword.intern("paths").equals(k)) {
                res.put(k, v);
            } else if (Keyword.intern("deps").equals(k)
                    || Keyword.intern("aliases").equals(k)
                    || Keyword.intern("mvn/repos").equals(k)) {
                Map<Object, Object> mergedSub = toJavaMap(cur);
                mergedSub.putAll(toJavaMap(v));
                res.put(k, toPersistentMap(mergedSub));
            } else {
                res.put(k, v);
            }
        }
        return res;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> combineArgMaps(Map<Object, Object> base, Map<Object, Object> over) {
        Map<Object, Object> res = new LinkedHashMap<>(base);
        for (Map.Entry<Object, Object> e : over.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            Object cur = res.get(k);
            if (Keyword.intern("extra-paths").equals(k) || Keyword.intern("jvm-opts").equals(k)) {
                List<Object> list = new ArrayList<>();
                if (cur instanceof Iterable<?> it) for (Object x : it) list.add(x);
                if (v instanceof Iterable<?> it) for (Object x : it) list.add(x);
                res.put(k, PersistentVector.create(list));
            } else if (Keyword.intern("extra-deps").equals(k)
                    || Keyword.intern("override-deps").equals(k) || Keyword.intern("default-deps").equals(k)
                    || Keyword.intern("exec-args").equals(k) || Keyword.intern("ns-aliases").equals(k)
                    || Keyword.intern("classpath-overrides").equals(k)) {
                Map<Object, Object> m = toJavaMap(cur);
                m.putAll(toJavaMap(v));
                res.put(k, toPersistentMap(m));
            } else {
                res.put(k, v);
            }
        }
        return res;
    }

    private static List<String> resolvePathsList(Map<Object, Object> merged, Map<Object, Object> argmap, Path projectDir) {
        List<String> out = new ArrayList<>();
        Object pathsObj = argmap.containsKey(Keyword.intern("replace-paths"))
                ? argmap.get(Keyword.intern("replace-paths"))
                : merged.get(Keyword.intern("paths"));
        if (pathsObj instanceof Iterable<?> it) {
            for (Object item : it) {
                out.add(resolveLocalPath(item.toString(), projectDir).toString());
            }
        }
        Object extraPaths = argmap.get(Keyword.intern("extra-paths"));
        if (extraPaths instanceof Iterable<?> it) {
            for (Object item : it) {
                out.add(resolveLocalPath(item.toString(), projectDir).toString());
            }
        }
        return out;
    }

    private static Map<Symbol, Map<Object, Object>> resolveTopDepsMap(Map<Object, Object> merged, Map<Object, Object> argmap) {
        Map<Symbol, Map<Object, Object>> res = new LinkedHashMap<>();
        Object baseObj = argmap.containsKey(Keyword.intern("replace-deps"))
                ? argmap.get(Keyword.intern("replace-deps"))
                : merged.get(Keyword.intern("deps"));
        res.putAll(toSymbolCoordMap(baseObj));
        res.putAll(toSymbolCoordMap(argmap.get(Keyword.intern("extra-deps"))));
        return res;
    }

    private static Map<Object, Object> applyOverridesAndDefaults(
            Symbol lib,
            Map<Object, Object> coord,
            Map<Symbol, Map<Object, Object>> overrides,
            Map<Symbol, Map<Object, Object>> defaults) {
        if (overrides.containsKey(lib)) {
            return overrides.get(lib);
        }
        if ((coord == null || coord.isEmpty()) && defaults.containsKey(lib)) {
            return defaults.get(lib);
        }
        return coord;
    }

    private static List<String> extractJvmOpts(Map<Object, Object> argmap) {
        List<String> out = new ArrayList<>();
        Object jo = argmap.get(Keyword.intern("jvm-opts"));
        if (jo instanceof Iterable<?> it) {
            for (Object x : it) {
                if (x != null) out.add(x.toString());
            }
        }
        return out;
    }

    private static List<String> extractMainOpts(CliOptions opts, Map<Object, Object> argmap) {
        List<String> out = new ArrayList<>();
        if (opts.mode == Mode.MAIN) {
            Object mo = argmap.get(Keyword.intern("main-opts"));
            if (mo instanceof Iterable<?> it) {
                for (Object x : it) {
                    if (x != null) out.add(x.toString());
                }
            }
        }
        return out;
    }

    private static Map<String, String> extractRepos(Map<Object, Object> merged) {
        Map<String, String> repos = new LinkedHashMap<>();
        Object rObj = merged.get(Keyword.intern("mvn/repos"));
        if (rObj != null) {
            for (Map.Entry<Object, Object> e : toJavaMap(rObj).entrySet()) {
                Map<Object, Object> rm = toJavaMap(e.getValue());
                Object url = rm.get(Keyword.intern("url"));
                if (url != null) repos.put(e.getKey().toString(), url.toString());
            }
        }
        if (!repos.containsKey("central")) repos.put("central", "https://repo1.maven.org/maven2/");
        if (!repos.containsKey("clojars")) repos.put("clojars", "https://repo.clojars.org/");
        return repos;
    }

    private static PersistentHashMap buildBasisMap(
            CliOptions opts,
            List<String> configFiles,
            Map<Object, Object> merged,
            Map<Object, Object> argmap,
            List<String> resolvedPaths,
            List<String> allRoots,
            Map<Symbol, TreeNode> libsBySymbol) {
        List<Object> cfVec = new ArrayList<>();
        for (String cf : configFiles) cfVec.add(cf);

        Map<Object, Object> basisConfig = new LinkedHashMap<>();
        basisConfig.put(Keyword.intern("root"), PersistentHashMap.EMPTY);
        basisConfig.put(Keyword.intern("project"), merged.getOrDefault(Keyword.intern("deps"), PersistentHashMap.EMPTY));
        basisConfig.put(Keyword.intern("aliases"), PersistentVector.create(new ArrayList<>(opts.aliases)));
        basisConfig.put(Keyword.intern("config-files"), PersistentVector.create(cfVec));

        Map<Object, Object> libsMap = new LinkedHashMap<>();
        for (Map.Entry<Symbol, TreeNode> e : libsBySymbol.entrySet()) {
            TreeNode tn = e.getValue();
            List<Object> pVec = new ArrayList<>(extractNodePaths(tn));
            Map<Object, Object> lm = toJavaMap(tn.coord);
            lm.put(Keyword.intern("paths"), PersistentVector.create(pVec));
            libsMap.put(e.getKey(), toPersistentMap(lm));
        }

        Map<Object, Object> cpMap = new LinkedHashMap<>();
        for (String p : resolvedPaths) {
            cpMap.put(p, PersistentHashMap.create(Keyword.intern("path-key"), Keyword.intern("paths")));
        }
        for (Map.Entry<Symbol, TreeNode> e : libsBySymbol.entrySet()) {
            for (String lp : extractNodePaths(e.getValue())) {
                cpMap.put(lp, PersistentHashMap.create(Keyword.intern("lib-name"), e.getKey()));
            }
        }

        Map<Object, Object> basis = new LinkedHashMap<>(merged);
        basis.put(Keyword.intern("basis-config"), toPersistentMap(basisConfig));
        basis.put(Keyword.intern("libs"), toPersistentMap(libsMap));
        basis.put(Keyword.intern("classpath"), toPersistentMap(cpMap));
        basis.put(Keyword.intern("classpath-roots"), PersistentVector.create(new ArrayList<>(allRoots)));
        basis.put(Keyword.intern("argmap"), toPersistentMap(argmap));
        return toPersistentMap(basis);
    }

    private static Path resolveLocalPath(String p, Path baseDir) {
        Path path = Path.of(p);
        if (path.isAbsolute()) return path.normalize();
        return baseDir.resolve(path).normalize();
    }

    private static Map<Object, Object> toJavaMap(Object o) {
        Map<Object, Object> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            out.putAll(m);
        }
        return out;
    }

    private static PersistentHashMap toPersistentMap(Map<Object, Object> m) {
        if (m == null || m.isEmpty()) return PersistentHashMap.EMPTY;
        Object[] kvs = new Object[m.size() * 2];
        int i = 0;
        for (Map.Entry<Object, Object> e : m.entrySet()) {
            kvs[i++] = e.getKey();
            kvs[i++] = e.getValue();
        }
        return PersistentHashMap.create(kvs);
    }

    private static Map<Symbol, Map<Object, Object>> toSymbolCoordMap(Object o) {
        Map<Symbol, Map<Object, Object>> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : toJavaMap(o).entrySet()) {
            if (e.getKey() instanceof Symbol sym) {
                out.put(sym, toJavaMap(e.getValue()));
            }
        }
        return out;
    }

    private static Map<Symbol, String> toSymbolStringMap(Object o) {
        Map<Symbol, String> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : toJavaMap(o).entrySet()) {
            if (e.getKey() instanceof Symbol sym && e.getValue() != null) {
                out.put(sym, e.getValue().toString());
            }
        }
        return out;
    }

    private static List<String> splitClasspath(String cp) {
        if (cp == null || cp.isEmpty()) return Collections.emptyList();
        char sep = File.pathSeparatorChar;
        List<String> out = new ArrayList<>();
        int start = 0;
        int len = cp.length();
        while (start <= len) {
            int idx = cp.indexOf(sep, start);
            int end = (idx < 0) ? len : idx;
            if (end > start) {
                out.add(cp.substring(start, end));
            }
            if (idx < 0) break;
            start = idx + 1;
        }
        return out;
    }

    private static List<String> readLinesNonEmpty(Path p) throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (!l.isEmpty()) out.add(l);
        }
        return out;
    }

    private static boolean isCacheFresh(Path cpFile, List<String> configFiles) {
        try {
            long cacheMtime = Files.getLastModifiedTime(cpFile).toMillis();
            for (String cf : configFiles) {
                Path p = Path.of(cf);
                if (Files.exists(p) && Files.getLastModifiedTime(p).toMillis() > cacheMtime) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static final int[] SHA256_K = {
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
            0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
            0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
            0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    /**
     * Pure-Java FIPS 180-4 SHA-256 returning the first 16 bytes (32 hex chars).
     * Avoids booting java.security.Security / JCA providers on CLI startup.
     */
    private static String sha256Hex16(byte[] msg) {
        int h0 = 0x6a09e667, h1 = 0xbb67ae85, h2 = 0x3c6ef372, h3 = 0xa54ff53a;
        int h4 = 0x510e527f, h5 = 0x9b05688c, h6 = 0x1f83d9ab, h7 = 0x5be0cd19;

        int msgLen = msg.length;
        int paddedLen = ((msgLen + 9 + 63) / 64) * 64;
        byte[] padded = new byte[paddedLen];
        System.arraycopy(msg, 0, padded, 0, msgLen);
        padded[msgLen] = (byte) 0x80;
        long bitLen = ((long) msgLen) * 8L;
        for (int i = 0; i < 8; i++) {
            padded[paddedLen - 1 - i] = (byte) (bitLen >>> (i * 8));
        }

        int[] w = new int[64];
        for (int off = 0; off < paddedLen; off += 64) {
            for (int i = 0; i < 16; i++) {
                int j = off + i * 4;
                w[i] = ((padded[j] & 0xff) << 24)
                        | ((padded[j + 1] & 0xff) << 16)
                        | ((padded[j + 2] & 0xff) << 8)
                        | (padded[j + 3] & 0xff);
            }
            for (int i = 16; i < 64; i++) {
                int s0 = Integer.rotateRight(w[i - 15], 7) ^ Integer.rotateRight(w[i - 15], 18) ^ (w[i - 15] >>> 3);
                int s1 = Integer.rotateRight(w[i - 2], 17) ^ Integer.rotateRight(w[i - 2], 19) ^ (w[i - 2] >>> 10);
                w[i] = w[i - 16] + s0 + w[i - 7] + s1;
            }
            int a = h0, b = h1, c = h2, d = h3, e = h4, f = h5, g = h6, h = h7;
            for (int i = 0; i < 64; i++) {
                int s1 = Integer.rotateRight(e, 6) ^ Integer.rotateRight(e, 11) ^ Integer.rotateRight(e, 25);
                int ch = (e & f) ^ (~e & g);
                int t1 = h + s1 + ch + SHA256_K[i] + w[i];
                int s0 = Integer.rotateRight(a, 2) ^ Integer.rotateRight(a, 13) ^ Integer.rotateRight(a, 22);
                int maj = (a & b) ^ (a & c) ^ (b & c);
                int t2 = s0 + maj;
                h = g; g = f; f = e; e = d + t1;
                d = c; c = b; b = a; a = t1 + t2;
            }
            h0 += a; h1 += b; h2 += c; h3 += d;
            h4 += e; h5 += f; h6 += g; h7 += h;
        }

        int[] hs = {h0, h1, h2, h3};
        char[] hex = new char[32];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < 4; i++) {
            int v = hs[i];
            int base = i * 8;
            for (int j = 7; j >= 0; j--) {
                hex[base + j] = digits[v & 0xf];
                v >>>= 4;
            }
        }
        return new String(hex);
    }

    private static String computeCacheKey(
            CliOptions opts,
            List<String> configFiles,
            Map<Object, Object> merged,
            Map<Object, Object> argmap,
            Path projectDir) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(projectDir).append('|').append(opts.mode).append('|')
          .append(opts.srepro).append('|').append(opts.toolName).append('|')
          .append(opts.aliases).append('|').append(configFiles).append('|')
          .append(RT.printString(toPersistentMap(merged), true))
          .append(RT.printString(toPersistentMap(argmap), true));
        return sha256Hex16(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void writeTraceFile(Path path, Map<Object, Object> traceLog) {
        try {
            Files.writeString(path, RT.printString(toPersistentMap(traceLog), true) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
