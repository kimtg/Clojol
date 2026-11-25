package cljl;

import cljl.lang.*;
import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Pattern;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Lazy loaders for additional standard library and Cheatsheet namespaces:
 * clojure.java.shell, clojure.java.browse, clojure.java.javadoc, clojure.main,
 * clojure.data, clojure.datafy, clojure.pprint, clojure.xml, clojure.zip,
 * clojure.spec.alpha, clojure.data.priority-map, clojure.data.int-map,
 * clojure.data.avl, flatland.ordered.map, flatland.ordered.set,
 * flatland.useful.map, and clojure.core.async.
 */
public final class StdLibMore {

    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private StdLibMore() {}

    public static boolean ensureLoaded(String nsName) {
        if (nsName == null) return false;
        if (LOADED.contains(nsName)) return true;
        return switch (nsName) {
            case "clojure.java.shell" -> { initShell(); yield true; }
            case "clojure.java.browse" -> { initBrowse(); yield true; }
            case "clojure.java.javadoc" -> { initJavadoc(); yield true; }
            case "clojure.main" -> { initMain(); yield true; }
            case "clojure.data" -> { initData(); yield true; }
            case "clojure.datafy" -> { initDatafy(); yield true; }
            case "clojure.pprint" -> { initPprint(); yield true; }
            case "clojure.xml" -> { initXml(); yield true; }
            case "clojure.zip" -> { initZip(); yield true; }
            case "clojure.spec.alpha" -> { initSpecAlpha(); yield true; }
            case "clojure.math" -> { initMath(); yield true; }
            case "clojure.data.priority-map" -> { initDataPriorityMap(); yield true; }
            case "clojure.data.int-map" -> { initDataIntMap(); yield true; }
            case "clojure.data.avl" -> { initDataAvl(); yield true; }
            case "flatland.ordered.map" -> { initFlatlandOrderedMap(); yield true; }
            case "flatland.ordered.set" -> { initFlatlandOrderedSet(); yield true; }
            case "flatland.useful.map" -> { initFlatlandUsefulMap(); yield true; }
            case "clojure.core.async" -> { initCoreAsync(); yield true; }
            case "clojure.core.reducers" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.core.reducers")) {
                        Reducers.initNamespace();
                        LOADED.add("clojure.core.reducers");
                    }
                }
                yield true;
            }
            case "clojure.java.process" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.java.process")) {
                        ProcessLib.initNamespace();
                        LOADED.add("clojure.java.process");
                    }
                }
                yield true;
            }
            case "clojure.repl.deps" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.repl.deps")) {
                        Deps.initReplDepsNamespace();
                        LOADED.add("clojure.repl.deps");
                    }
                }
                yield true;
            }
            case "clojure.core.protocols" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.core.protocols")) {
                        StdLibExtra.initCoreProtocols();
                        LOADED.add("clojure.core.protocols");
                    }
                }
                yield true;
            }
            case "clojure.core.server" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.core.server")) {
                        ServerLib.initNamespace();
                        LOADED.add("clojure.core.server");
                    }
                }
                yield true;
            }
            case "clojure.stacktrace" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.stacktrace")) {
                        StdLibExtra.initStacktrace();
                        LOADED.add("clojure.stacktrace");
                    }
                }
                yield true;
            }
            case "clojure.template" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.template")) {
                        StdLibExtra.initTemplate();
                        LOADED.add("clojure.template");
                    }
                }
                yield true;
            }
            case "clojure.instant" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.instant")) {
                        StdLibExtra.initInstant();
                        LOADED.add("clojure.instant");
                    }
                }
                yield true;
            }
            case "clojure.reflect" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.reflect")) {
                        StdLibExtra.initReflect();
                        LOADED.add("clojure.reflect");
                    }
                }
                yield true;
            }
            case "clojure.uuid" -> {
                synchronized (StdLibMore.class) {
                    if (!LOADED.contains("clojure.uuid")) {
                        StdLibExtra.initUuid();
                        LOADED.add("clojure.uuid");
                    }
                }
                yield true;
            }
            case "clojure.tools.deps.cli.api" -> { Deps.ensureDepsCliApiLoaded(); yield true; }
            default -> StdLib112.ensureLoaded(nsName);
        };
    }

    // ========================================================================
    // clojure.java.shell
    // ========================================================================

    private static synchronized void initShell() {
        if (LOADED.contains("clojure.java.shell")) return;
        Namespace ns = Namespace.findOrCreate("clojure.java.shell");
        Var shDir = ns.intern(Symbol.intern("*sh-dir*")).bindRoot(null).setDynamic();
        Var shEnv = ns.intern(Symbol.intern("*sh-env*")).bindRoot(null).setDynamic();

        ns.internMacro("with-sh-dir", Fn.ofVarargs("with-sh-dir", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("binding"));
            body.add(PersistentVector.createOwning(Symbol.intern("clojure.java.shell", "*sh-dir*"), args[0]));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        }));
        ns.internMacro("with-sh-env", Fn.ofVarargs("with-sh-env", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("binding"));
            body.add(PersistentVector.createOwning(Symbol.intern("clojure.java.shell", "*sh-env*"), args[0]));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        }));

        Keyword kIn = Keyword.intern("in");
        Keyword kInEnc = Keyword.intern("in-enc");
        Keyword kOutEnc = Keyword.intern("out-enc");
        Keyword kEnv = Keyword.intern("env");
        Keyword kDir = Keyword.intern("dir");
        Keyword kBytes = Keyword.intern("bytes");

        ns.intern("parse-args", Fn.of1("parse-args", argsObj -> {
            List<Object> cmd = new ArrayList<>();
            PersistentHashMap opts = PersistentHashMap.create(
                    kInEnc, StandardCharsets.UTF_8.name(),
                    kOutEnc, StandardCharsets.UTF_8.name(),
                    kDir, shDir.deref(),
                    kEnv, shEnv.deref());
            List<Object> items = toList(argsObj);
            int i = 0;
            while (i < items.size()) {
                Object a = items.get(i);
                if (a instanceof Keyword kw && i + 1 < items.size()) {
                    opts = opts.assoc(kw, items.get(i + 1));
                    i += 2;
                } else {
                    cmd.add(String.valueOf(a));
                    i++;
                }
            }
            return PersistentVector.createOwning(PersistentVector.create(cmd), opts);
        }));

        ns.intern("sh", Fn.ofVarargs("sh", args -> {
            List<String> cmd = new ArrayList<>();
            Map<Keyword, Object> opts = new HashMap<>();
            opts.put(kInEnc, StandardCharsets.UTF_8.name());
            opts.put(kOutEnc, StandardCharsets.UTF_8.name());
            opts.put(kDir, shDir.deref());
            opts.put(kEnv, shEnv.deref());
            int i = 0;
            while (i < args.length) {
                Object a = args[i];
                if (a instanceof Keyword kw && i + 1 < args.length) {
                    opts.put(kw, args[i + 1]);
                    i += 2;
                } else {
                    cmd.add(String.valueOf(a));
                    i++;
                }
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                Object dir = opts.get(kDir);
                if (dir instanceof File f) pb.directory(f);
                else if (dir != null) pb.directory(new File(dir.toString()));
                Object env = opts.get(kEnv);
                if (env instanceof Map<?, ?> em) {
                    Map<String, String> penv = pb.environment();
                    for (Map.Entry<?, ?> e : em.entrySet()) {
                        if (e.getValue() == null) penv.remove(String.valueOf(e.getKey()));
                        else penv.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                }
                Process proc = pb.start();
                Object inVal = opts.get(kIn);
                if (inVal != null) {
                    try (OutputStream os = proc.getOutputStream()) {
                        if (inVal instanceof byte[] b) os.write(b);
                        else {
                            Charset cs = Charset.forName(String.valueOf(opts.get(kInEnc)));
                            os.write(inVal.toString().getBytes(cs));
                        }
                    }
                } else {
                    proc.getOutputStream().close();
                }
                ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
                ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
                Thread tOut = Thread.startVirtualThread(() -> {
                    try { proc.getInputStream().transferTo(outBuf); } catch (IOException ignored) {}
                });
                Thread tErr = Thread.startVirtualThread(() -> {
                    try { proc.getErrorStream().transferTo(errBuf); } catch (IOException ignored) {}
                });
                int exit = proc.waitFor();
                tOut.join();
                tErr.join();
                Object outEnc = opts.get(kOutEnc);
                Object outObj = kBytes.equals(outEnc)
                        ? outBuf.toByteArray()
                        : new String(outBuf.toByteArray(), Charset.forName(String.valueOf(outEnc)));
                String errStr = new String(errBuf.toByteArray(), StandardCharsets.UTF_8);
                return PersistentHashMap.create(
                        Keyword.intern("exit"), (long) exit,
                        Keyword.intern("out"), outObj,
                        Keyword.intern("err"), errStr);
            } catch (Exception e) {
                throw RT.sneakyThrow(e);
            }
        }));
        LOADED.add("clojure.java.shell");
    }

    // ========================================================================
    // clojure.java.browse & clojure.java.javadoc
    // ========================================================================

    private static void openUrlInBrowser(URI uri) throws Exception {
        String s = uri.toString();
        if (s.indexOf('\0') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid URI: " + s);
        }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", s).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", s).start();
        } else {
            new ProcessBuilder("xdg-open", s).start();
        }
    }

    private static synchronized void initBrowse() {
        if (LOADED.contains("clojure.java.browse")) return;
        Namespace ns = Namespace.findOrCreate("clojure.java.browse");
        ns.intern("browse-url", Fn.of1("browse-url", url -> {
            try {
                openUrlInBrowser(url instanceof URI u ? u : URI.create(url.toString()));
                return null;
            } catch (Exception e) {
                throw RT.sneakyThrow(e);
            }
        }));
        LOADED.add("clojure.java.browse");
    }

    private static synchronized void initJavadoc() {
        if (LOADED.contains("clojure.java.javadoc")) return;
        Namespace ns = Namespace.findOrCreate("clojure.java.javadoc");
        String coreApi = "https://docs.oracle.com/en/java/javase/21/docs/api/";
        Var coreVar = ns.intern(Symbol.intern("*core-java-api*")).bindRoot(coreApi).setDynamic();
        Atom localDocs = new Atom(PersistentList.EMPTY);
        Atom remoteDocs = new Atom(PersistentHashMap.create("java.", coreApi, "javax.", coreApi));
        ns.intern(Symbol.intern("*local-javadocs*")).bindRoot(localDocs).setDynamic();
        ns.intern(Symbol.intern("*remote-javadocs*")).bindRoot(remoteDocs).setDynamic();
        ns.intern(Symbol.intern("*feeling-lucky*")).bindRoot(Boolean.TRUE).setDynamic();
        ns.intern(Symbol.intern("*feeling-lucky-url*")).bindRoot("http://www.google.com/search?btnI=I%27m%20Feeling%20Lucky&q=allinurl:").setDynamic();
        ns.intern("add-local-javadoc", Fn.of1("add-local-javadoc", path ->
                localDocs.swap(Fn.of1("add-local", l -> RT.conj(l, path)))));
        ns.intern("add-remote-javadoc", Fn.of2("add-remote-javadoc", (prefix, url) ->
                remoteDocs.swap(Fn.of1("add-remote", m -> RT.assoc(m, prefix, url)))));
        ns.intern("javadoc", Fn.of1("javadoc", classOrObj -> {
            Class<?> c = classOrObj instanceof Class<?> cl ? cl : classOrObj.getClass();
            String cname = c.getName();
            String path = cname.replace('.', '/').replace('$', '.') + ".html";
            String url = coreVar.deref() + "java.base/" + path;
            for (ISeq s = RT.seq(remoteDocs.deref()); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                if (cname.startsWith(String.valueOf(e.getKey()))) {
                    url = e.getValue() + "java.base/" + path;
                    break;
                }
            }
            try {
                openUrlInBrowser(URI.create(url));
            } catch (Exception ignored) {
                StdLib.outWriter().println(url);
                StdLib.outWriter().flush();
            }
            return Boolean.TRUE;
        }));
        LOADED.add("clojure.java.javadoc");
    }

    // ========================================================================
    // clojure.main
    // ========================================================================

    private static synchronized void initMain() {
        if (LOADED.contains("clojure.main")) return;
        Namespace ns = Namespace.findOrCreate("clojure.main");
        ns.intern("demunge", Fn.of1("demunge", fnName -> {
            String s = String.valueOf(fnName);
            int dollar = s.indexOf('$');
            if (dollar >= 0) {
                return Compiler.demunge(s.substring(0, dollar)) + "/" + Compiler.demunge(s.substring(dollar + 1).replace('$', '/'));
            }
            return Compiler.demunge(s);
        }));
        ns.intern("ex-triage", Fn.of1("ex-triage", data -> {
            Object m = data instanceof Throwable t
                    ? ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("Throwable->map")).deref()).invoke(t)
                    : data;
            String cause = RT.get(m, Keyword.intern("cause")) != null ? String.valueOf(RT.get(m, Keyword.intern("cause"))) : "";
            Object via = RT.get(m, Keyword.intern("via"));
            Object firstVia = via != null ? RT.first(via) : null;
            Object exData = RT.get(m, Keyword.intern("data"));
            if (exData == null && firstVia != null) exData = RT.get(firstVia, Keyword.intern("data"));
            Object phase = exData != null ? RT.get(exData, Keyword.intern("clojure.error", "phase")) : null;
            if (phase == null) phase = Keyword.intern("execution");
            Object cls = firstVia != null ? RT.get(firstVia, Keyword.intern("type")) : null;
            Object msg = firstVia != null ? RT.get(firstVia, Keyword.intern("message")) : cause;
            PersistentHashMap out = PersistentHashMap.create(
                    Keyword.intern("clojure.error", "phase"), phase,
                    Keyword.intern("clojure.error", "class"), cls,
                    Keyword.intern("clojure.error", "cause"), msg != null ? msg : cause);
            if (exData instanceof IPersistentMap dm) {
                for (String kname : new String[]{"source", "line", "column", "symbol"}) {
                    Keyword k = Keyword.intern("clojure.error", kname);
                    Object v = dm.valAt(k);
                    if (v != null) out = out.assoc(k, v);
                }
                Object probs = dm.valAt(Keyword.intern("clojure.spec.alpha", "problems"));
                if (probs != null) out = out.assoc(Keyword.intern("clojure.spec.alpha", "problems"), probs);
            }
            return out;
        }));
        ns.intern("ex-str", Fn.of1("ex-str", triage -> {
            Object phase = RT.get(triage, Keyword.intern("clojure.error", "phase"), Keyword.intern("execution"));
            Object cls = RT.get(triage, Keyword.intern("clojure.error", "class"));
            Object cause = RT.get(triage, Keyword.intern("clojure.error", "cause"));
            Object sym = RT.get(triage, Keyword.intern("clojure.error", "symbol"));
            Object src = RT.get(triage, Keyword.intern("clojure.error", "source"));
            Object line = RT.get(triage, Keyword.intern("clojure.error", "line"));
            String prefix = switch (phase instanceof Keyword kw ? kw.getName() : "execution") {
                case "read-source" -> "Syntax error reading source";
                case "macro-syntax-check" -> "Syntax error macroexpanding";
                case "macroexpansion" -> "Unexpected error macroexpanding";
                case "compile-syntax-check" -> "Syntax error compiling";
                case "compilation" -> "Unexpected error compiling";
                case "print-eval-result" -> "Error printing return value";
                default -> "Execution error";
            };
            StringBuilder sb = new StringBuilder(prefix);
            if (cls != null) sb.append(" (").append(cls).append(")");
            if (sym != null) sb.append(" ").append(sym);
            if (src != null) {
                sb.append(" at (").append(src);
                if (line != null) sb.append(":").append(line);
                sb.append(")");
            }
            if (cause != null && !String.valueOf(cause).isEmpty()) {
                sb.append(": ").append(cause);
            }
            return sb.toString();
        }));
        ns.intern("err->msg", Fn.of1("err->msg", e -> {
            Fn triageFn = (Fn) ns.findInternedVar(Symbol.intern("ex-triage")).deref();
            Fn strFn = (Fn) ns.findInternedVar(Symbol.intern("ex-str")).deref();
            Fn thFn = (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("Throwable->map")).deref();
            return strFn.invoke(triageFn.invoke(thFn.invoke(e)));
        }));
        ns.intern("report-error", Fn.of12("report-error",
                t -> reportErrorImpl(t, Keyword.intern("target"), Keyword.intern("file")),
                (t, opts) -> reportErrorImpl(t, opts, Keyword.intern("file"))));
        ns.intern("repl-exception", Fn.of1("repl-exception", t -> t));
        ns.intern("repl-prompt", Fn.of0("repl-prompt", () -> {
            PrintWriter pw = StdLib.outWriter();
            pw.print(RT.currentNS().name.name + "=> ");
            pw.flush();
            return null;
        }));
        ns.intern("repl-caught", Fn.of1("repl-caught", e -> {
            StdLib.errWriter().println(e);
            StdLib.errWriter().flush();
            return null;
        }));
        ns.intern("root-cause", Fn.of1("root-cause", tObj -> {
            Throwable t = (Throwable) tObj;
            while (t != null && t.getCause() != null && t.getCause() != t) {
                t = t.getCause();
            }
            return t;
        }));
        ns.intern("skip-if-eol", Fn.of1("skip-if-eol", s -> {
            try {
                java.io.PushbackReader pr = (java.io.PushbackReader) s;
                int c = pr.read();
                if (c == '\n') return Keyword.intern("line-start");
                if (c == -1) return Keyword.intern("stream-end");
                pr.unread(c);
                return Keyword.intern("body");
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("skip-whitespace", Fn.of1("skip-whitespace", s -> {
            try {
                java.io.PushbackReader pr = (java.io.PushbackReader) s;
                while (true) {
                    int c = pr.read();
                    if (c == '\n') return Keyword.intern("line-start");
                    if (c == -1) return Keyword.intern("stream-end");
                    if (c == ';') {
                        while (c != -1 && c != '\n') c = pr.read();
                        return c == -1 ? Keyword.intern("stream-end") : Keyword.intern("line-start");
                    }
                    if (!Character.isWhitespace(c) && c != ',') {
                        pr.unread(c);
                        return Keyword.intern("body");
                    }
                }
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("repl-read", Fn.of2("repl-read", (requestPrompt, requestExit) -> {
            Object inObj = RT.IN.deref();
            java.io.PushbackReader pr = inObj instanceof java.io.PushbackReader p
                    ? p
                    : new cljl.lang.LineNumberingPushbackReader(
                            inObj instanceof java.io.Reader r ? r : new InputStreamReader(System.in));
            Fn skipWs = (Fn) ns.findInternedVar(Symbol.intern("skip-whitespace")).deref();
            Fn skipEol = (Fn) ns.findInternedVar(Symbol.intern("skip-if-eol")).deref();
            Object ws = skipWs.invoke(pr);
            if (Keyword.intern("line-start").equals(ws)) return requestPrompt;
            if (Keyword.intern("stream-end").equals(ws)) return requestExit;
            Object form = Reader.readFromStream(pr, false, requestExit, null);
            skipEol.invoke(pr);
            return form;
        }));
        ns.intern("repl", Fn.ofVarargs("repl", args -> {
            Map<Object, Object> opts = new HashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                opts.put(args[i], args[i + 1]);
            }
            Fn initFn = opts.get(Keyword.intern("init")) instanceof Fn f ? f : Fn.of0("noop", () -> null);
            Fn needPromptFn = opts.get(Keyword.intern("need-prompt")) instanceof Fn f ? f
                    : Fn.of0("need-prompt", () -> RT.IN.deref() instanceof clojure.lang.LineNumberingPushbackReader lnr && lnr.atLineStart());
            Fn promptFn = opts.get(Keyword.intern("prompt")) instanceof Fn f ? f
                    : (Fn) ns.findInternedVar(Symbol.intern("repl-prompt")).deref();
            Fn flushFn = opts.get(Keyword.intern("flush")) instanceof Fn f ? f
                    : (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("flush")).deref();
            Fn readFn = opts.get(Keyword.intern("read")) instanceof Fn f ? f
                    : (Fn) ns.findInternedVar(Symbol.intern("repl-read")).deref();
            Fn evalFn = opts.get(Keyword.intern("eval")) instanceof Fn f ? f
                    : (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("eval")).deref();
            Fn printFn = opts.get(Keyword.intern("print")) instanceof Fn f ? f
                    : (Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("prn")).deref();
            Fn caughtFn = opts.get(Keyword.intern("caught")) instanceof Fn f ? f
                    : (Fn) ns.findInternedVar(Symbol.intern("repl-caught")).deref();

            Object requestPrompt = new Object();
            Object requestExit = new Object();

            Map<Var, Object> bindings = new HashMap<>();
            bindings.put(RT.CURRENT_NS, RT.CURRENT_NS.deref());
            bindings.put(RT.WARN_ON_REFLECTION, RT.WARN_ON_REFLECTION.deref());
            bindings.put(RT.UNCHECKED_MATH, RT.UNCHECKED_MATH.deref());
            bindings.put(RT.DATA_READERS, RT.DATA_READERS.deref());
            bindings.put(RT.DEFAULT_DATA_READER_FN, RT.DEFAULT_DATA_READER_FN.deref());
            bindings.put(RT.READ_EVAL, RT.READ_EVAL.deref());
            bindings.put(RT.PRINT_META, RT.PRINT_META.deref());
            bindings.put(RT.PRINT_LENGTH, RT.PRINT_LENGTH.deref());
            bindings.put(RT.PRINT_LEVEL, RT.PRINT_LEVEL.deref());
            bindings.put(RT.PRINT_NAMESPACE_MAPS, RT.PRINT_NAMESPACE_MAPS.deref());
            bindings.put(RT.STAR_1, null);
            bindings.put(RT.STAR_2, null);
            bindings.put(RT.STAR_3, null);
            bindings.put(RT.STAR_E, null);
            Var.pushThreadBindings(bindings);
            try {
                initFn.invoke();
                while (true) {
                    try {
                        if (RT.truthy(needPromptFn.invoke())) {
                            promptFn.invoke();
                            flushFn.invoke();
                        }
                        Object input = readFn.invoke(requestPrompt, requestExit);
                        if (input == requestExit) break;
                        if (input == requestPrompt) continue;
                        Object value = evalFn.invoke(input);
                        RT.STAR_3.set(RT.STAR_2.deref());
                        RT.STAR_2.set(RT.STAR_1.deref());
                        RT.STAR_1.set(value);
                        printFn.invoke(value);
                    } catch (Throwable e) {
                        RT.STAR_E.set(e);
                        caughtFn.invoke(e);
                    }
                }
            } finally {
                Var.popThreadBindings();
            }
            return null;
        }));
        ns.intern("with-read-known", Fn.ofVarargs("with-read-known", args -> {
            // (binding [*read-eval* (if (= :unknown *read-eval*) true *read-eval*)] & body)
            Symbol re = Symbol.intern("clojure.core", "*read-eval*");
            Object valExpr = PersistentList.createFromArray(
                    Symbol.intern("if"),
                    PersistentList.createFromArray(Symbol.intern("clojure.core", "="), Keyword.intern("unknown"), re),
                    Boolean.TRUE,
                    re);
            List<Object> form = new ArrayList<>();
            form.add(Symbol.intern("clojure.core", "binding"));
            form.add(PersistentVector.createOwning(re, valExpr));
            for (int i = 2; i < args.length; i++) form.add(args[i]);
            return PersistentList.create(form);
        })).setMacro();
        ns.intern("with-bindings", Fn.ofVarargs("with-bindings", args -> {
            List<Object> bvec = new ArrayList<>();
            String[] dynVars = {"*ns*", "*warn-on-reflection*", "*unchecked-math*", "*data-readers*",
                    "*default-data-reader-fn*", "*read-eval*", "*print-meta*", "*print-length*",
                    "*print-level*", "*print-namespace-maps*", "*1", "*2", "*3", "*e"};
            for (String v : dynVars) {
                Symbol sym = Symbol.intern("clojure.core", v);
                bvec.add(sym);
                bvec.add(sym);
            }
            List<Object> form = new ArrayList<>();
            form.add(Symbol.intern("clojure.core", "binding"));
            form.add(PersistentVector.create(bvec));
            for (int i = 2; i < args.length; i++) form.add(args[i]);
            return PersistentList.create(form);
        })).setMacro();
        ns.intern("load-script", Fn.of1("load-script", path -> {
            try {
                Main.loadScriptOrResource(String.valueOf(path));
                return null;
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        Fn mainFn = Fn.ofVarargs("main", args -> {
            String[] strArgs = new String[args.length];
            for (int i = 0; i < args.length; i++) {
                strArgs[i] = args[i] == null ? null : args[i].toString();
            }
            Main.runMain(strArgs);
            return null;
        });
        ns.intern("main", mainFn);
        ns.intern("-main", mainFn);
        LOADED.add("clojure.main");
    }

    private static Object reportErrorImpl(Object tObj, Object opts, Object defTarget) {
        if (!(tObj instanceof Throwable t)) return null;
        Object targetObj = defTarget;
        if (opts instanceof cljl.lang.Associative a) {
            Object tVal = a.valAt(Keyword.intern("target"));
            if (tVal != null) targetObj = tVal;
        } else if (opts != null) {
            targetObj = opts;
        }
        String target = targetObj instanceof Keyword kw ? kw.getName() : String.valueOf(targetObj);
        Main.printError(t, target);
        return null;
    }

    // ========================================================================
    // clojure.data (diff)
    // ========================================================================

    private static synchronized void initData() {
        if (LOADED.contains("clojure.data")) return;
        Namespace ns = Namespace.findOrCreate("clojure.data");
        LOADED.add("clojure.data");
        ns.intern("__diff-impl", Fn.of2("__diff-impl", StdLibMore::diffImpl));
        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, ns));
        try {
            Compiler.load("""
                (defprotocol EqualityPartition
                  (equality-partition [x] "Implementation detail. Subject to change."))
                (defprotocol Diff
                  (diff-similar [a b] "Implementation detail. Subject to change."))
                (defn- atom-diff [a b]
                  (if (= a b) [nil nil a] [a b nil]))
                (extend-protocol EqualityPartition
                  nil
                  (equality-partition [_] :atom)
                  Object
                  (equality-partition [x] (if (.isArray (class x)) :sequential :atom))
                  java.util.Set
                  (equality-partition [_] :set)
                  clojure.lang.IPersistentSet
                  (equality-partition [_] :set)
                  java.util.List
                  (equality-partition [_] :sequential)
                  clojure.lang.Sequential
                  (equality-partition [_] :sequential)
                  java.util.Map
                  (equality-partition [_] :map)
                  clojure.lang.IPersistentMap
                  (equality-partition [_] :map))
                (extend-protocol Diff
                  nil
                  (diff-similar [a b] (atom-diff a b))
                  Object
                  (diff-similar [a b] (if (or (.isArray (class a)) (.isArray (class b))) (__diff-impl a b) (atom-diff a b)))
                  java.util.Set
                  (diff-similar [a b] (__diff-impl a b))
                  clojure.lang.IPersistentSet
                  (diff-similar [a b] (__diff-impl a b))
                  java.util.List
                  (diff-similar [a b] (__diff-impl a b))
                  clojure.lang.Sequential
                  (diff-similar [a b] (__diff-impl a b))
                  java.util.Map
                  (diff-similar [a b] (__diff-impl a b))
                  clojure.lang.IPersistentMap
                  (diff-similar [a b] (__diff-impl a b)))
                (defn diff [a b]
                  (if (= a b)
                    [nil nil a]
                    (if (= (equality-partition a) (equality-partition b))
                      (diff-similar a b)
                      (atom-diff a b))))
                """, "clojure/data.clj");
        } finally {
            Var.popThreadBindings();
        }
    }

    private static PersistentVector diffImpl(Object a, Object b) {
        if (RT.equiv(a, b)) return PersistentVector.createOwning(null, null, a);
        if (a == null || b == null) return PersistentVector.createOwning(a, b, null);
        if (a instanceof Set<?> sa && b instanceof Set<?> sb) {
            PersistentHashSet.TransientHashSet onlyA = PersistentHashSet.EMPTY.asTransient();
            PersistentHashSet.TransientHashSet onlyB = PersistentHashSet.EMPTY.asTransient();
            PersistentHashSet.TransientHashSet both = PersistentHashSet.EMPTY.asTransient();
            for (Object x : sa) {
                if (sb.contains(x)) both = both.conj(x);
                else onlyA = onlyA.conj(x);
            }
            for (Object x : sb) {
                if (!sa.contains(x)) onlyB = onlyB.conj(x);
            }
            PersistentHashSet ra = onlyA.persistent();
            PersistentHashSet rb = onlyB.persistent();
            PersistentHashSet rc = both.persistent();
            return PersistentVector.createOwning(
                    ra.count() == 0 ? null : ra,
                    rb.count() == 0 ? null : rb,
                    rc.count() == 0 ? null : rc);
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            PersistentHashMap.TransientHashMap onlyA = PersistentHashMap.EMPTY.asTransient();
            PersistentHashMap.TransientHashMap onlyB = PersistentHashMap.EMPTY.asTransient();
            PersistentHashMap.TransientHashMap both = PersistentHashMap.EMPTY.asTransient();
            Set<Object> keys = new LinkedHashSet<>();
            keys.addAll(ma.keySet());
            keys.addAll(mb.keySet());
            for (Object k : keys) {
                boolean hasA = ma.containsKey(k);
                boolean hasB = mb.containsKey(k);
                if (hasA && hasB) {
                    Object va = ma.get(k);
                    Object vb = mb.get(k);
                    if (RT.equiv(va, vb)) {
                        both = both.assoc(k, va);
                    } else if (va == null || vb == null) {
                        onlyA = onlyA.assoc(k, va);
                        onlyB = onlyB.assoc(k, vb);
                    } else {
                        PersistentVector sub = diffImpl(va, vb);
                        if (sub.nth(0) != null) onlyA = onlyA.assoc(k, sub.nth(0));
                        if (sub.nth(1) != null) onlyB = onlyB.assoc(k, sub.nth(1));
                        if (sub.nth(2) != null) both = both.assoc(k, sub.nth(2));
                    }
                } else if (hasA) {
                    onlyA = onlyA.assoc(k, ma.get(k));
                } else {
                    onlyB = onlyB.assoc(k, mb.get(k));
                }
            }
            PersistentHashMap ra = onlyA.persistent();
            PersistentHashMap rb = onlyB.persistent();
            PersistentHashMap rc = both.persistent();
            return PersistentVector.createOwning(
                    ra.count() == 0 ? null : ra,
                    rb.count() == 0 ? null : rb,
                    rc.count() == 0 ? null : rc);
        }
        if (isSequentialColl(a) && isSequentialColl(b)) {
            List<Object> la = toList(a);
            List<Object> lb = toList(b);
            int max = Math.max(la.size(), lb.size());
            Object[] oa = new Object[max];
            Object[] ob = new Object[max];
            Object[] oc = new Object[max];
            int lastA = -1, lastB = -1, lastC = -1;
            for (int i = 0; i < max; i++) {
                boolean inA = i < la.size();
                boolean inB = i < lb.size();
                if (inA && inB) {
                    Object va = la.get(i);
                    Object vb = lb.get(i);
                    if (RT.equiv(va, vb)) {
                        oc[i] = va;
                        lastC = i;
                    } else if (va == null || vb == null) {
                        oa[i] = va; lastA = i;
                        ob[i] = vb; lastB = i;
                    } else {
                        PersistentVector sub = diffImpl(va, vb);
                        if (sub.nth(0) != null) { oa[i] = sub.nth(0); lastA = i; }
                        if (sub.nth(1) != null) { ob[i] = sub.nth(1); lastB = i; }
                        if (sub.nth(2) != null) { oc[i] = sub.nth(2); lastC = i; }
                    }
                } else if (inA) {
                    oa[i] = la.get(i);
                    lastA = i;
                } else {
                    ob[i] = lb.get(i);
                    lastB = i;
                }
            }
            return PersistentVector.createOwning(
                    lastA >= 0 ? PersistentVector.createOwning(Arrays.copyOf(oa, lastA + 1)) : null,
                    lastB >= 0 ? PersistentVector.createOwning(Arrays.copyOf(ob, lastB + 1)) : null,
                    lastC >= 0 ? PersistentVector.createOwning(Arrays.copyOf(oc, lastC + 1)) : null);
        }
        return PersistentVector.createOwning(a, b, null);
    }

    private static boolean isSequentialColl(Object x) {
        return x != null && (x instanceof PersistentVector || x instanceof ISeq || x instanceof List<?> || x.getClass().isArray());
    }

    private static List<Object> toList(Object coll) {
        List<Object> out = new ArrayList<>();
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) out.add(s.first());
        return out;
    }

    // ========================================================================
    // clojure.datafy
    // ========================================================================

    private static synchronized void initDatafy() {
        if (LOADED.contains("clojure.datafy")) return;
        ensureLoaded("clojure.core.protocols");
        Namespace ns = Namespace.findOrCreate("clojure.datafy");
        Namespace protoNs = Namespace.findOrCreate("clojure.core.protocols");
        Symbol datafyMeta = Symbol.intern("clojure.core.protocols", "datafy");
        Symbol navMeta = Symbol.intern("clojure.core.protocols", "nav");
        Keyword kDatafyObj = Keyword.intern("clojure.datafy", "obj");
        Keyword kDatafyClass = Keyword.intern("clojure.datafy", "class");
        Protocol datafiableProto = (Protocol) protoNs.findInternedVar(Symbol.intern("Datafiable")).deref();
        Protocol navigableProto = (Protocol) protoNs.findInternedVar(Symbol.intern("Navigable")).deref();

        ns.intern("datafy", Fn.of1("datafy", x -> {
            if (x == null) return null;
            Object res = x;
            if (x instanceof IMeta im && im.meta() != null) {
                Object fn = im.meta().valAt(datafyMeta);
                if (fn == null) fn = im.meta().valAt(Keyword.intern("clojure.core.protocols", "datafy"));
                if (fn instanceof Fn f) {
                    res = f.invoke(x);
                } else {
                    Fn impl = datafiableProto.resolveMethodOrNull(Keyword.intern("datafy"), x);
                    if (impl != null) res = impl.invoke(x);
                }
            } else {
                Fn impl = datafiableProto.resolveMethodOrNull(Keyword.intern("datafy"), x);
                if (impl != null) res = impl.invoke(x);
            }
            if (res == x) {
                if (x instanceof Throwable t) {
                    res = ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("Throwable->map")).deref()).invoke(t);
                } else if (x instanceof Class<?> cls) {
                    ensureLoaded("clojure.reflect");
                    Namespace reflNs = Namespace.find("clojure.reflect");
                    Object reflected = ((Fn) reflNs.findInternedVar(Symbol.intern("reflect")).deref()).invoke(cls);
                    if (reflected instanceof IPersistentMap pm) {
                        res = pm.assoc(Keyword.intern("name"), Symbol.intern(cls.getName()));
                    } else {
                        res = reflected;
                    }
                } else if (x instanceof Namespace n) {
                    res = PersistentHashMap.create(
                            Keyword.intern("name"), n.name,
                            Keyword.intern("publics"), ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("ns-publics")).deref()).invoke(n),
                            Keyword.intern("imports"), ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("ns-imports")).deref()).invoke(n),
                            Keyword.intern("interns"), ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("ns-interns")).deref()).invoke(n));
                } else if (x instanceof clojure.lang.IDeref d) {
                    res = PersistentVector.createOwning(d.deref());
                }
            }
            if (res != x && res instanceof cljl.lang.IObj iobj) {
                PersistentHashMap m = iobj.meta() != null ? iobj.meta() : PersistentHashMap.EMPTY;
                res = iobj.withMeta(m.assoc(kDatafyObj, x).assoc(kDatafyClass, x.getClass()));
            }
            return res;
        }));
        ns.intern("nav", Fn.of3("nav", (coll, k, v) -> {
            if (coll instanceof IMeta im && im.meta() != null) {
                Object fn = im.meta().valAt(navMeta);
                if (fn == null) fn = im.meta().valAt(Keyword.intern("clojure.core.protocols", "nav"));
                if (fn instanceof Fn f) return f.invoke(coll, k, v);
            }
            Fn impl = navigableProto.resolveMethodOrNull(Keyword.intern("nav"), coll);
            if (impl != null) return impl.invoke(coll, k, v);
            return v;
        }));
        LOADED.add("clojure.datafy");
    }

    // ========================================================================
    // clojure.pprint
    // ========================================================================

    private static synchronized void initPprint() {
        if (LOADED.contains("clojure.pprint")) return;
        Namespace ns = Namespace.findOrCreate("clojure.pprint");
        Var rightMargin = ns.intern(Symbol.intern("*print-right-margin*")).bindRoot(72L).setDynamic();
        Var miserWidth = ns.intern(Symbol.intern("*print-miser-width*")).bindRoot(40L).setDynamic();
        Var suppressNs = ns.intern(Symbol.intern("*print-suppress-namespaces*")).bindRoot(Boolean.FALSE).setDynamic();
        Var printPretty = ns.intern(Symbol.intern("*print-pretty*")).bindRoot(Boolean.TRUE).setDynamic();
        Var printBase = ns.intern(Symbol.intern("*print-base*")).bindRoot(10L).setDynamic();
        Var printRadix = ns.intern(Symbol.intern("*print-radix*")).bindRoot(Boolean.FALSE).setDynamic();

        ns.intern("pprint", Fn.of12("pprint",
                obj -> pprintToWriter(obj, StdLib.outWriter(), StdLib.toInt(rightMargin.deref())),
                (obj, writer) -> pprintToWriter(obj, toPrintWriter(writer), StdLib.toInt(rightMargin.deref()))));

        ns.intern("print-table", Fn.of12("print-table",
                rows -> printTableImpl(null, rows),
                (ks, rows) -> printTableImpl(ks, rows)));

        ns.intern("cl-format", Fn.ofVarargs("cl-format", args -> {
            Object writer = args[0];
            String fmt = String.valueOf(args[1]);
            Object[] fmtArgs = Arrays.copyOfRange(args, 2, args.length);
            String formatted = clFormatImpl(fmt, fmtArgs);
            if (writer == null) return formatted;
            PrintWriter pw = Boolean.TRUE.equals(writer) ? StdLib.outWriter() : toPrintWriter(writer);
            pw.print(formatted);
            pw.flush();
            return null;
        }));

        Fn defaultPprint = Fn.of1("simple-dispatch-default", obj ->
                pprintToWriter(obj, StdLib.outWriter(), StdLib.toInt(rightMargin.deref())));
        Fn classDispatch = Fn.of1("class-dispatch", obj -> obj == null ? null : obj.getClass());
        MultiFn simpleDispatch = new MultiFn("simple-dispatch", classDispatch, Keyword.intern("default"));
        simpleDispatch.addMethod(Keyword.intern("default"), defaultPprint);
        MultiFn codeDispatch = new MultiFn("code-dispatch", classDispatch, Keyword.intern("default"));
        codeDispatch.addMethod(Keyword.intern("default"), defaultPprint);
        ns.intern("simple-dispatch", simpleDispatch);
        ns.intern("code-dispatch", codeDispatch);
        Var dispatchVar = ns.intern(Symbol.intern("*print-pprint-dispatch*")).bindRoot(simpleDispatch).setDynamic();

        ns.intern("set-pprint-dispatch", Fn.of1("set-pprint-dispatch", fn -> {
            dispatchVar.bindRoot(fn);
            return null;
        }));

        ns.intern("write-out", Fn.of1("write-out", obj -> {
            Fn df = (Fn) dispatchVar.deref();
            return df.invoke(obj);
        }));

        ns.intern("get-pretty-writer", Fn.of1("get-pretty-writer", w -> toPrintWriter(w)));

        ns.intern("pprint-newline", Fn.of1("pprint-newline", kind -> {
            PrintWriter pw = StdLib.outWriter();
            if (Keyword.intern("mandatory").equals(kind) || Keyword.intern("linear").equals(kind)) {
                pw.println();
            } else {
                pw.print(' ');
            }
            return null;
        }));

        ns.intern("pprint-indent", Fn.of2("pprint-indent", (relTo, n) -> null));
        ns.intern("pprint-tab", Fn.of3("pprint-tab", (kind, colnum, colinc) -> {
            PrintWriter pw = StdLib.outWriter();
            pw.print(' ');
            return null;
        }));

        ns.internMacro("pprint-logical-block", Fn.ofVarargs("pprint-logical-block", args -> {
            List<Object> opts = new ArrayList<>();
            int bodyStart = 0;
            if (args.length > 0 && args[0] instanceof PersistentVector optVec) {
                for (int i = 0; i < optVec.count(); i++) opts.add(optVec.nth(i));
                bodyStart = 1;
            }
            Object prefix = null, suffix = null;
            for (int i = 0; i + 1 < opts.size(); i += 2) {
                Object k = opts.get(i);
                if (Keyword.intern("prefix").equals(k)) prefix = opts.get(i + 1);
                else if (Keyword.intern("suffix").equals(k)) suffix = opts.get(i + 1);
            }
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            if (prefix != null) {
                body.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "print"), prefix));
            }
            for (int i = bodyStart; i < args.length; i++) body.add(args[i]);
            if (suffix != null) {
                body.add(PersistentList.createFromArray(Symbol.intern("clojure.core", "print"), suffix));
            }
            return PersistentList.create(body);
        }));

        ns.internMacro("with-pprint-dispatch", Fn.ofVarargs("with-pprint-dispatch", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("binding"));
            body.add(PersistentVector.createOwning(Symbol.intern("clojure.pprint", "*print-pprint-dispatch*"), args[0]));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        }));

        ns.intern("write", Fn.ofVarargs("write", args -> {
            Object obj = args[0];
            Object stream = Boolean.TRUE;
            boolean pretty = RT.truthy(printPretty.deref());
            int margin = StdLib.toInt(rightMargin.deref());
            int base = StdLib.toInt(printBase.deref());
            boolean radix = RT.truthy(printRadix.deref());
            boolean readably = RT.truthy(RT.PRINT_READABLY.deref());
            Object length = RT.PRINT_LENGTH.deref();
            Object level = RT.PRINT_LEVEL.deref();
            Object disp = null;
            for (int i = 1; i + 1 < args.length; i += 2) {
                Object k = args[i];
                Object v = args[i + 1];
                if (Keyword.intern("stream").equals(k)) stream = v;
                else if (Keyword.intern("pretty").equals(k)) pretty = RT.truthy(v);
                else if (Keyword.intern("right-margin").equals(k) && v != null) margin = StdLib.toInt(v);
                else if (Keyword.intern("base").equals(k) && v != null) base = StdLib.toInt(v);
                else if (Keyword.intern("radix").equals(k)) radix = RT.truthy(v);
                else if (Keyword.intern("readably").equals(k)) readably = RT.truthy(v);
                else if (Keyword.intern("length").equals(k)) length = v;
                else if (Keyword.intern("level").equals(k)) level = v;
                else if (Keyword.intern("dispatch").equals(k)) disp = v;
            }
            String out;
            if ((obj instanceof Long || obj instanceof Integer || obj instanceof cljl.lang.BigInt) && base != 10 && base >= 2 && base <= 36) {
                String numStr = obj instanceof cljl.lang.BigInt bi
                        ? bi.toBigInteger().toString(base)
                        : Long.toString(((Number) obj).longValue(), base);
                if (radix) {
                    String pfx = base == 2 ? "#b" : base == 8 ? "#o" : base == 16 ? "#x" : ("#" + base + "r");
                    out = pfx + numStr;
                } else {
                    out = numStr;
                }
            } else if (disp instanceof Fn dispFn) {
                StringWriter sw = new StringWriter();
                Var.pushThreadBindings(Map.of(RT.OUT, sw, rightMargin, (long) margin));
                try {
                    dispFn.invoke(obj);
                } finally {
                    Var.popThreadBindings();
                }
                out = sw.toString();
            } else if (pretty) {
                StringBuilder sb = new StringBuilder();
                pprintFormat(obj, sb, 0, margin, 0);
                out = sb.toString();
            } else {
                Map<Var, Object> b = new HashMap<>();
                b.put(RT.PRINT_READABLY, readably);
                b.put(RT.PRINT_LENGTH, length);
                b.put(RT.PRINT_LEVEL, level);
                Var.pushThreadBindings(b);
                try {
                    out = RT.printString(obj, readably);
                } finally {
                    Var.popThreadBindings();
                }
            }
            if (stream == null) return out;
            PrintWriter pw = Boolean.TRUE.equals(stream) ? StdLib.outWriter() : toPrintWriter(stream);
            pw.print(out);
            pw.flush();
            return null;
        }));

        ns.internMacro("formatter", Fn.of1("formatter", fmt ->
                PersistentList.createFromArray(
                        Symbol.intern("fn"),
                        PersistentVector.createOwning(Symbol.intern("stream__"), Symbol.intern("&"), Symbol.intern("args__")),
                        PersistentList.createFromArray(
                                Symbol.intern("clojure.core", "apply"),
                                Symbol.intern("clojure.pprint", "cl-format"),
                                Symbol.intern("stream__"),
                                fmt,
                                Symbol.intern("args__")))));

        ns.internMacro("formatter-out", Fn.of1("formatter-out", fmt ->
                PersistentList.createFromArray(
                        Symbol.intern("fn"),
                        PersistentVector.createOwning(Symbol.intern("&"), Symbol.intern("args__")),
                        PersistentList.createFromArray(
                                Symbol.intern("clojure.core", "apply"),
                                Symbol.intern("clojure.pprint", "cl-format"),
                                Boolean.TRUE,
                                fmt,
                                Symbol.intern("args__")))));

        ns.intern("fresh-line", Fn.of0("fresh-line", () -> {
            PrintWriter pw = StdLib.outWriter();
            pw.println();
            pw.flush();
            return null;
        }));

        ns.internMacro("pp", Fn.of0("pp", () ->
                PersistentList.createFromArray(Symbol.intern("clojure.pprint", "pprint"), Symbol.intern("*1"))));

        LOADED.add("clojure.pprint");
    }

    private static PrintWriter toPrintWriter(Object w) {
        if (w instanceof PrintWriter pw) return pw;
        if (w instanceof Writer wr) return new PrintWriter(wr, true);
        return StdLib.outWriter();
    }

    private static Object pprintToWriter(Object obj, PrintWriter pw, int margin) {
        StringBuilder sb = new StringBuilder();
        pprintFormat(obj, sb, 0, margin, 0);
        pw.println(sb.toString());
        pw.flush();
        return null;
    }

    private static void pprintFormat(Object obj, StringBuilder sb, int indent, int margin, int level) {
        int maxLevel = RT.PRINT_LEVEL.get() instanceof Number n ? n.intValue() : -1;
        int maxLen = RT.PRINT_LENGTH.get() instanceof Number n ? n.intValue() : -1;
        if (obj == null || !(obj instanceof PersistentVector || obj instanceof Map<?, ?> || obj instanceof Set<?> || obj instanceof ISeq || obj instanceof List<?>)) {
            RT.print(obj, sb, true);
            return;
        }
        if (maxLevel >= 0 && level >= maxLevel) {
            sb.append('#');
            return;
        }
        String singleLine = RT.printString(obj, true);
        if (singleLine.length() <= Math.max(20, margin - indent) && !singleLine.contains("\n")) {
            sb.append(singleLine);
            return;
        }
        if (obj instanceof PersistentVector v) {
            sb.append('[');
            int lim = maxLen >= 0 ? Math.min(maxLen, v.cnt) : v.cnt;
            for (int i = 0; i < lim; i++) {
                if (i > 0) { sb.append('\n'); appendSpaces(sb, indent + 1); }
                pprintFormat(v.nth(i), sb, indent + 1, margin, level + 1);
            }
            if (maxLen >= 0 && v.cnt > maxLen) { sb.append('\n'); appendSpaces(sb, indent + 1); sb.append("..."); }
            sb.append(']');
        } else if (obj instanceof Map<?, ?> m) {
            sb.append('{');
            int idx = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (maxLen >= 0 && idx >= maxLen) { sb.append('\n'); appendSpaces(sb, indent + 1); sb.append("..."); break; }
                if (idx > 0) { sb.append(",\n"); appendSpaces(sb, indent + 1); }
                int beforeKey = sb.length();
                pprintFormat(e.getKey(), sb, indent + 1, margin, level + 1);
                sb.append(' ');
                int valIndent = indent + 1 + Math.min(20, sb.length() - beforeKey);
                pprintFormat(e.getValue(), sb, valIndent, margin, level + 1);
                idx++;
            }
            sb.append('}');
        } else if (obj instanceof Set<?> set) {
            sb.append("#{");
            int idx = 0;
            for (Object item : set) {
                if (maxLen >= 0 && idx >= maxLen) { sb.append('\n'); appendSpaces(sb, indent + 2); sb.append("..."); break; }
                if (idx > 0) { sb.append('\n'); appendSpaces(sb, indent + 2); }
                pprintFormat(item, sb, indent + 2, margin, level + 1);
                idx++;
            }
            sb.append('}');
        } else {
            sb.append('(');
            int idx = 0;
            for (ISeq s = RT.seq(obj); s != null; s = s.next()) {
                if (maxLen >= 0 && idx >= maxLen) { sb.append('\n'); appendSpaces(sb, indent + 1); sb.append("..."); break; }
                if (idx > 0) { sb.append('\n'); appendSpaces(sb, indent + 1); }
                pprintFormat(s.first(), sb, indent + 1, margin, level + 1);
                idx++;
            }
            sb.append(')');
        }
    }

    private static void appendSpaces(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append(' ');
    }

    private static Object printTableImpl(Object ksObj, Object rowsObj) {
        List<Object> rows = toList(rowsObj);
        if (rows.isEmpty()) return null;
        List<Object> ks;
        if (ksObj != null) {
            ks = toList(ksObj);
        } else {
            ks = new ArrayList<>();
            Object first = rows.get(0);
            for (ISeq s = RT.keys(first); s != null; s = s.next()) ks.add(s.first());
        }
        int cols = ks.size();
        int[] widths = new int[cols];
        String[] headers = new String[cols];
        for (int c = 0; c < cols; c++) {
            headers[c] = String.valueOf(ks.get(c));
            widths[c] = headers[c].length();
        }
        List<String[]> table = new ArrayList<>(rows.size());
        for (Object r : rows) {
            String[] line = new String[cols];
            for (int c = 0; c < cols; c++) {
                Object val = RT.get(r, ks.get(c));
                line[c] = val == null ? "" : String.valueOf(val);
                if (line[c].length() > widths[c]) widths[c] = line[c].length();
            }
            table.add(line);
        }
        PrintWriter out = StdLib.outWriter();
        out.println();
        StringBuilder sep = new StringBuilder("|");
        StringBuilder hdr = new StringBuilder("|");
        for (int c = 0; c < cols; c++) {
            hdr.append(' ').append(padRight(headers[c], widths[c])).append(" |");
            sep.append("-".repeat(widths[c] + 2)).append(c == cols - 1 ? "|" : "+");
        }
        out.println(hdr);
        out.println(sep);
        for (String[] line : table) {
            StringBuilder rowSb = new StringBuilder("|");
            for (int c = 0; c < cols; c++) {
                rowSb.append(' ').append(padLeft(line[c], widths[c])).append(" |");
            }
            out.println(rowSb);
        }
        out.flush();
        return null;
    }

    private static String padRight(String s, int w) {
        return s.length() >= w ? s : s + " ".repeat(w - s.length());
    }

    private static String padLeft(String s, int w) {
        return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
    }

    private static String clFormatImpl(String fmt, Object[] args) {
        StringBuilder sb = new StringBuilder();
        int argIdx = 0;
        int len = fmt.length();
        for (int i = 0; i < len; i++) {
            char c = fmt.charAt(i);
            if (c != '~') { sb.append(c); continue; }
            i++;
            if (i >= len) break;
            StringBuilder prefix = new StringBuilder();
            while (i < len && (Character.isDigit(fmt.charAt(i)) || fmt.charAt(i) == ',' || fmt.charAt(i) == '@' || fmt.charAt(i) == ':' || fmt.charAt(i) == '-')) {
                prefix.append(fmt.charAt(i++));
            }
            if (i >= len) break;
            char dir = fmt.charAt(i);
            char lower = Character.toLowerCase(dir);
            String pstr = prefix.toString();
            int repeat = (!pstr.isEmpty() && Character.isDigit(pstr.charAt(0)))
                    ? Integer.parseInt(pstr.replaceAll("[^0-9].*", "")) : 1;
            switch (lower) {
                case '%' -> sb.append("\n".repeat(Math.max(1, repeat)));
                case '&' -> { if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n'); }
                case '~' -> sb.append("~".repeat(Math.max(1, repeat)));
                case '|' -> sb.append('\f');
                case '\n' -> { while (i + 1 < len && Character.isWhitespace(fmt.charAt(i + 1))) i++; }
                case 'a' -> {
                    Object v = argIdx < args.length ? args[argIdx++] : null;
                    sb.append(v == null ? "nil" : v.toString());
                }
                case 's' -> {
                    Object v = argIdx < args.length ? args[argIdx++] : null;
                    sb.append(RT.printString(v, true));
                }
                case 'd', 'x', 'o', 'b' -> {
                    Object v = argIdx < args.length ? args[argIdx++] : 0L;
                    long lv = Numbers.toLong(v);
                    String str = switch (lower) {
                        case 'x' -> Long.toHexString(lv);
                        case 'o' -> Long.toOctalString(lv);
                        case 'b' -> Long.toBinaryString(lv);
                        default -> Long.toString(lv);
                    };
                    if (dir == 'X') str = str.toUpperCase(Locale.ROOT);
                    if (!pstr.isEmpty() && Character.isDigit(pstr.charAt(0))) {
                        sb.append(padLeft(str, repeat));
                    } else sb.append(str);
                }
                case 'f', 'e', 'g' -> {
                    Object v = argIdx < args.length ? args[argIdx++] : 0.0;
                    double dv = Numbers.toDouble(v);
                    sb.append(String.format(Locale.ROOT, lower == 'e' ? "%e" : (lower == 'g' ? "%g" : "%f"), dv));
                }
                case 'c' -> {
                    Object v = argIdx < args.length ? args[argIdx++] : ' ';
                    sb.append(v instanceof Character ch ? ch : String.valueOf(v));
                }
                case '*' -> {
                    if (pstr.contains(":")) argIdx = Math.max(0, argIdx - repeat);
                    else if (pstr.contains("@")) argIdx = repeat;
                    else argIdx += repeat;
                }
                case '{' -> {
                    int end = fmt.indexOf("~}", i);
                    if (end < 0) break;
                    String inner = fmt.substring(i + 1, end);
                    Object seqObj = argIdx < args.length ? args[argIdx++] : null;
                    for (ISeq s = RT.seq(seqObj); s != null; s = s.next()) {
                        Object item = s.first();
                        Object[] subArgs = (item instanceof PersistentVector || item instanceof ISeq)
                                ? toList(item).toArray()
                                : new Object[]{item};
                        sb.append(clFormatImpl(inner, subArgs));
                    }
                    i = end + 1;
                }
                default -> sb.append('~').append(pstr).append(dir);
            }
        }
        return sb.toString();
    }

    // ========================================================================
    // clojure.xml
    // ========================================================================

    private static synchronized void initXml() {
        if (LOADED.contains("clojure.xml")) return;
        Namespace ns = Namespace.findOrCreate("clojure.xml");
        Keyword kTag = Keyword.intern("tag");
        Keyword kAttrs = Keyword.intern("attrs");
        Keyword kContent = Keyword.intern("content");

        ns.intern("parse", Fn.of12("parse",
                src -> xmlParseImpl(src, null),
                (src, startparse) -> xmlParseImpl(src, (Fn) startparse)));
        LOADED.add("clojure.xml");
    }

    private static Object xmlParseImpl(Object src, Fn startparse) {
        try {
            InputSource is;
            if (src instanceof InputSource ins) is = ins;
            else if (src instanceof InputStream ins) is = new InputSource(ins);
            else if (src instanceof java.io.Reader r) is = new InputSource(r);
            else if (src instanceof File f) is = new InputSource(new FileInputStream(f));
            else is = new InputSource(src.toString());

            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            spf.setXIncludeAware(false);
            spf.setNamespaceAware(false);
            javax.xml.parsers.SAXParser parser = spf.newSAXParser();
            Deque<Keyword> tagStack = new ArrayDeque<>();
            Deque<Object> attrsStack = new ArrayDeque<>();
            Deque<List<Object>> contentStack = new ArrayDeque<>();
            contentStack.push(new ArrayList<>());
            StringBuilder textBuf = new StringBuilder();
            Keyword kTag = Keyword.intern("tag");
            Keyword kAttrs = Keyword.intern("attrs");
            Keyword kContent = Keyword.intern("content");

            parser.parse(is, new DefaultHandler() {
                private void flushText() {
                    if (textBuf.length() > 0) {
                        String s = textBuf.toString();
                        textBuf.setLength(0);
                        if (!s.trim().isEmpty()) contentStack.peek().add(s);
                    }
                }
                @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    flushText();
                    tagStack.push(Keyword.intern(qName.isEmpty() ? localName : qName));
                    PersistentHashMap.TransientHashMap am = PersistentHashMap.EMPTY.asTransient();
                    for (int i = 0; i < attributes.getLength(); i++) {
                        am = am.assoc(Keyword.intern(attributes.getQName(i)), attributes.getValue(i));
                    }
                    PersistentHashMap attrsMap = am.persistent();
                    attrsStack.push(attrsMap);
                    contentStack.push(new ArrayList<>());
                }
                @Override public void endElement(String uri, String localName, String qName) {
                    flushText();
                    Keyword tag = tagStack.pop();
                    PersistentHashMap attrsMap = (PersistentHashMap) attrsStack.pop();
                    Object attrs = attrsMap.count() == 0 ? null : attrsMap;
                    List<Object> content = contentStack.pop();
                    Object contentVec = content.isEmpty() ? null : PersistentVector.create(content);
                    PersistentHashMap elem = PersistentHashMap.create(kTag, tag, kAttrs, attrs, kContent, contentVec);
                    contentStack.peek().add(elem);
                }
                @Override public void characters(char[] ch, int start, int length) {
                    textBuf.append(ch, start, length);
                }
            });
            List<Object> root = contentStack.peek();
            return root.isEmpty() ? null : root.get(0);
        } catch (Exception e) {
            throw RT.sneakyThrow(e);
        }
    }

    // ========================================================================
    // clojure.zip
    // ========================================================================

    private static synchronized void initZip() {
        if (LOADED.contains("clojure.zip")) return;
        Namespace ns = Namespace.findOrCreate("clojure.zip");
        Keyword kBranch = Keyword.intern("zip", "branch?");
        Keyword kChildren = Keyword.intern("zip", "children");
        Keyword kMakeNode = Keyword.intern("zip", "make-node");
        Keyword kL = Keyword.intern("l");
        Keyword kR = Keyword.intern("r");
        Keyword kPnodes = Keyword.intern("pnodes");
        Keyword kPpath = Keyword.intern("ppath");
        Keyword kChanged = Keyword.intern("changed?");
        Keyword kEnd = Keyword.intern("end");

        Fn zipperFn = new Fn.NamedFn("zipper") {
            @Override public Object invoke(Object branchFn, Object childrenFn, Object makeNodeFn, Object root) {
                PersistentHashMap meta = PersistentHashMap.create(
                        kBranch, branchFn,
                        kChildren, childrenFn,
                        kMakeNode, makeNodeFn);
                return PersistentVector.createOwning(root, null).withMeta(meta);
            }
        };
        ns.intern("zipper", zipperFn);

        Fn seqZip = Fn.of1("seq-zip", root -> zipperFn.invoke(
                Fn.of1("seq?", x -> x instanceof ISeq || x instanceof Seqable),
                RT.CLOJURE_NS.findInternedVar(Symbol.intern("seq")).deref(),
                Fn.of2("seq-make", (node, children) -> {
                    Object m = node instanceof IMeta im ? im.meta() : null;
                    Object s = children == null ? PersistentList.EMPTY : children;
                    return m instanceof IPersistentMap pm && s instanceof IObj sm ? sm.withMeta(PersistentHashMap.fromMap(pm)) : s;
                }),
                root));
        ns.intern("seq-zip", seqZip);

        Fn vecZip = Fn.of1("vector-zip", root -> zipperFn.invoke(
                Fn.of1("vec?", x -> x instanceof PersistentVector),
                RT.CLOJURE_NS.findInternedVar(Symbol.intern("seq")).deref(),
                Fn.of2("vec-make", (node, children) -> PersistentVector.create(toList(children))),
                root));
        ns.intern("vector-zip", vecZip);

        Keyword kTag = Keyword.intern("tag");
        Keyword kContent = Keyword.intern("content");
        Fn xmlZip = Fn.of1("xml-zip", root -> zipperFn.invoke(
                Fn.of1("xml-branch?", x -> !(x instanceof String)),
                Fn.of1("xml-children", x -> RT.seq(RT.get(x, kContent))),
                Fn.of2("xml-make", (node, children) ->
                        RT.assoc(node, kContent, children == null ? null : PersistentVector.create(toList(children)))),
                root));
        ns.intern("xml-zip", xmlZip);

        Fn nodeFn = Fn.of1("node", loc -> loc == null ? null : ((PersistentVector) loc).nth(0));
        Fn rawPathFn = Fn.of1("raw-path", loc -> loc == null ? null : ((PersistentVector) loc).nth(1));
        Fn pathFn = Fn.of1("path", loc -> {
            Object p = rawPathFn.invoke(loc);
            return p == null ? null : RT.get(p, kPnodes);
        });
        Fn branchFn = Fn.of1("branch?", loc -> {
            if (loc == null) return Boolean.FALSE;
            Fn f = (Fn) ((IMeta) loc).meta().valAt(kBranch);
            return f.invoke(nodeFn.invoke(loc));
        });
        Fn childrenFn = Fn.of1("children", loc -> {
            if (loc == null) return null;
            if (!RT.truthy(branchFn.invoke(loc))) throw new IllegalStateException("called children on a leaf node");
            Fn f = (Fn) ((IMeta) loc).meta().valAt(kChildren);
            return f.invoke(nodeFn.invoke(loc));
        });
        Fn makeNodeFn = Fn.of3("make-node", (loc, node, children) -> {
            Fn f = (Fn) ((IMeta) loc).meta().valAt(kMakeNode);
            return f.invoke(node, children);
        });
        Fn leftsFn = Fn.of1("lefts", loc -> {
            Object p = rawPathFn.invoke(loc);
            return p == null ? null : RT.seq(RT.get(p, kL));
        });
        Fn rightsFn = Fn.of1("rights", loc -> {
            Object p = rawPathFn.invoke(loc);
            return p == null ? null : RT.get(p, kR);
        });
        Fn downFn = Fn.of1("down", loc -> {
            if (loc == null || !RT.truthy(branchFn.invoke(loc))) return null;
            Object cs = RT.seq(childrenFn.invoke(loc));
            if (cs == null) return null;
            Object n = nodeFn.invoke(loc);
            Object p = rawPathFn.invoke(loc);
            Object pnodes = p != null ? RT.conj(RT.get(p, kPnodes), n) : PersistentVector.createOwning(n);
            PersistentHashMap newPath = PersistentHashMap.create(
                    kL, PersistentVector.EMPTY,
                    kPnodes, pnodes,
                    kPpath, p,
                    kR, RT.next(cs));
            return PersistentVector.createOwning(RT.first(cs), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn upFn = Fn.of1("up", loc -> {
            if (loc == null) return null;
            Object n = nodeFn.invoke(loc);
            Object p = rawPathFn.invoke(loc);
            if (p == null) return null;
            Object ppath = RT.get(p, kPpath);
            Object pnodes = RT.get(p, kPnodes);
            if (RT.truthy(RT.get(p, kChanged))) {
                Object parentNode = ((PersistentVector) pnodes).peek();
                List<Object> all = new ArrayList<>(toList(RT.get(p, kL)));
                all.add(n);
                all.addAll(toList(RT.get(p, kR)));
                Object updated = makeNodeFn.invoke(loc, parentNode, PersistentList.create(all));
                Object updatedPpath = ppath != null ? RT.assoc(ppath, kChanged, Boolean.TRUE) : null;
                return PersistentVector.createOwning(updated, updatedPpath).withMeta(((IMeta) loc).meta());
            } else {
                return PersistentVector.createOwning(((PersistentVector) pnodes).peek(), ppath).withMeta(((IMeta) loc).meta());
            }
        });
        Fn rootFn = Fn.of1("root", loc -> {
            if (loc == null) return null;
            Object cur = loc;
            while (true) {
                if (kEnd.equals(rawPathFn.invoke(cur))) return nodeFn.invoke(cur);
                Object u = upFn.invoke(cur);
                if (u == null) return nodeFn.invoke(cur);
                cur = u;
            }
        });
        Fn rightFn = Fn.of1("right", loc -> {
            if (loc == null) return null;
            Object p = rawPathFn.invoke(loc);
            Object r = p == null ? null : RT.seq(RT.get(p, kR));
            if (r == null) return null;
            Object newPath = RT.assoc(RT.assoc(p, kL, RT.conj(RT.get(p, kL), nodeFn.invoke(loc))),
                    kR, RT.next(r));
            return PersistentVector.createOwning(RT.first(r), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn rightmostFn = Fn.of1("rightmost", loc -> {
            if (loc == null) return null;
            Object cur = loc;
            while (true) {
                Object r = rightFn.invoke(cur);
                if (r == null) return cur;
                cur = r;
            }
        });
        Fn leftFn = Fn.of1("left", loc -> {
            if (loc == null) return null;
            Object p = rawPathFn.invoke(loc);
            Object l = p == null ? null : RT.get(p, kL);
            if (l == null || RT.count(l) == 0) return null;
            PersistentVector lv = (PersistentVector) l;
            Object newPath = RT.assoc(RT.assoc(p, kL, lv.pop()),
                    kR, RT.cons(nodeFn.invoke(loc), RT.get(p, kR)));
            return PersistentVector.createOwning(lv.peek(), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn leftmostFn = Fn.of1("leftmost", loc -> {
            if (loc == null) return null;
            Object p = rawPathFn.invoke(loc);
            if (p == null) return loc;
            PersistentVector lv = (PersistentVector) RT.get(p, kL);
            if (lv == null || lv.count() == 0) return loc;
            List<Object> newR = new ArrayList<>();
            for (int i = 1; i < lv.count(); i++) newR.add(lv.nth(i));
            newR.add(nodeFn.invoke(loc));
            newR.addAll(toList(RT.get(p, kR)));
            Object newPath = RT.assoc(RT.assoc(p, kL, PersistentVector.EMPTY),
                    kR, PersistentList.create(newR));
            return PersistentVector.createOwning(lv.nth(0), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn replaceFn = Fn.of2("replace", (loc, node) -> {
            Object p = rawPathFn.invoke(loc);
            Object np = p != null ? RT.assoc(p, kChanged, Boolean.TRUE) : PersistentHashMap.create(kChanged, Boolean.TRUE);
            return PersistentVector.createOwning(node, np).withMeta(((IMeta) loc).meta());
        });
        Fn editFn = Fn.ofVarargs("edit", args -> {
            Object loc = args[0];
            Fn f = (Fn) args[1];
            Object[] fargs = new Object[args.length - 1];
            fargs[0] = nodeFn.invoke(loc);
            System.arraycopy(args, 2, fargs, 1, args.length - 2);
            return replaceFn.invoke(loc, f.applyTo(fargs));
        });
        Fn insertLeftFn = Fn.of2("insert-left", (loc, item) -> {
            Object p = rawPathFn.invoke(loc);
            if (p == null) throw new IllegalStateException("Insert at top");
            Object np = RT.assoc(RT.assoc(p, kL, RT.conj(RT.get(p, kL), item)),
                    kChanged, Boolean.TRUE);
            return PersistentVector.createOwning(nodeFn.invoke(loc), np).withMeta(((IMeta) loc).meta());
        });
        Fn insertRightFn = Fn.of2("insert-right", (loc, item) -> {
            Object p = rawPathFn.invoke(loc);
            if (p == null) throw new IllegalStateException("Insert at top");
            Object np = RT.assoc(RT.assoc(p, kR, RT.cons(item, RT.get(p, kR))),
                    kChanged, Boolean.TRUE);
            return PersistentVector.createOwning(nodeFn.invoke(loc), np).withMeta(((IMeta) loc).meta());
        });
        Fn insertChildFn = Fn.of2("insert-child", (loc, item) ->
                replaceFn.invoke(loc, makeNodeFn.invoke(loc, nodeFn.invoke(loc), RT.cons(item, childrenFn.invoke(loc)))));
        Fn appendChildFn = Fn.of2("append-child", (loc, item) -> {
            List<Object> cs = new ArrayList<>(toList(childrenFn.invoke(loc)));
            cs.add(item);
            return replaceFn.invoke(loc, makeNodeFn.invoke(loc, nodeFn.invoke(loc), PersistentList.create(cs)));
        });
        Fn endPFn = Fn.of1("end?", loc -> loc != null && kEnd.equals(rawPathFn.invoke(loc)));
        Fn nextFn = Fn.of1("next", loc -> {
            if (loc == null) return null;
            if (kEnd.equals(rawPathFn.invoke(loc))) return loc;
            Object d = RT.truthy(branchFn.invoke(loc)) ? downFn.invoke(loc) : null;
            if (d != null) return d;
            Object r = rightFn.invoke(loc);
            if (r != null) return r;
            Object cur = loc;
            while (true) {
                Object u = upFn.invoke(cur);
                if (u == null) return PersistentVector.createOwning(nodeFn.invoke(cur), kEnd).withMeta(((IMeta) cur).meta());
                Object ur = rightFn.invoke(u);
                if (ur != null) return ur;
                cur = u;
            }
        });
        Fn prevFn = Fn.of1("prev", loc -> {
            if (loc == null) return null;
            Object l = leftFn.invoke(loc);
            if (l != null) {
                Object cur = l;
                while (RT.truthy(branchFn.invoke(cur))) {
                    Object d = downFn.invoke(cur);
                    if (d == null) break;
                    cur = rightmostFn.invoke(d);
                }
                return cur;
            }
            return upFn.invoke(loc);
        });
        Fn removeFn = Fn.of1("remove", loc -> {
            Object p = rawPathFn.invoke(loc);
            if (p == null) throw new IllegalStateException("Remove at top");
            PersistentVector lv = (PersistentVector) RT.get(p, kL);
            if (lv.count() > 0) {
                Object np = RT.assoc(RT.assoc(p, kL, lv.pop()), kChanged, Boolean.TRUE);
                Object cur = PersistentVector.createOwning(lv.peek(), np).withMeta(((IMeta) loc).meta());
                while (RT.truthy(branchFn.invoke(cur))) {
                    Object d = downFn.invoke(cur);
                    if (d == null) break;
                    cur = rightmostFn.invoke(d);
                }
                return cur;
            } else {
                Object pnodes = RT.get(p, kPnodes);
                Object ppath = RT.get(p, kPpath);
                Object updated = makeNodeFn.invoke(loc, ((PersistentVector) pnodes).peek(), RT.get(p, kR));
                Object upPath = ppath != null ? RT.assoc(ppath, kChanged, Boolean.TRUE) : null;
                return PersistentVector.createOwning(updated, upPath).withMeta(((IMeta) loc).meta());
            }
        });

        ns.intern("node", nodeFn);
        ns.intern("branch?", branchFn);
        ns.intern("children", childrenFn);
        ns.intern("make-node", makeNodeFn);
        ns.intern("path", pathFn);
        ns.intern("lefts", leftsFn);
        ns.intern("rights", rightsFn);
        ns.intern("down", downFn);
        ns.intern("up", upFn);
        ns.intern("root", rootFn);
        ns.intern("right", rightFn);
        ns.intern("rightmost", rightmostFn);
        ns.intern("left", leftFn);
        ns.intern("leftmost", leftmostFn);
        ns.intern("insert-left", insertLeftFn);
        ns.intern("insert-right", insertRightFn);
        ns.intern("replace", replaceFn);
        ns.intern("edit", editFn);
        ns.intern("insert-child", insertChildFn);
        ns.intern("append-child", appendChildFn);
        ns.intern("next", nextFn);
        ns.intern("prev", prevFn);
        ns.intern("end?", endPFn);
        ns.intern("remove", removeFn);

        LOADED.add("clojure.zip");
    }

    // ========================================================================
    // clojure.spec.alpha
    // ========================================================================

    private static final Keyword SPEC_INVALID = Keyword.intern("clojure.spec.alpha", "invalid");

    private interface SpecObj {
        Object conform(Object x);
        Object unform(Object x);
        List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x);
        Object describe();
        default boolean regex() { return false; }
        default MatchRes regexConform(List<Object> items, int pos) { throw new UnsupportedOperationException(); }
    }

    private record MatchRes(boolean ok, Object value, int nextPos, List<PersistentHashMap> problems) {}

    private static final class SimpleSpec extends Fn.NamedFn implements SpecObj {
        final Object form;
        final Fn pred;
        final Fn unformer;
        final boolean conformer;
        final Fn genOverride;

        SimpleSpec(Object form, Fn pred, Fn unformer, boolean conformer, Fn genOverride) {
            super("spec");
            this.form = form;
            this.pred = pred;
            this.unformer = unformer;
            this.conformer = conformer;
            this.genOverride = genOverride;
        }
        @Override public Object invoke(Object a) { return conform(a) != SPEC_INVALID; }
        @Override public Object conform(Object x) {
            if (conformer) return pred.invoke(x);
            return RT.truthy(pred.invoke(x)) ? x : SPEC_INVALID;
        }
        @Override public Object unform(Object x) {
            return unformer != null ? unformer.invoke(x) : x;
        }
        @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
            if (conform(x) != SPEC_INVALID) return List.of();
            return List.of(PersistentHashMap.create(
                    Keyword.intern("path"), path,
                    Keyword.intern("pred"), form,
                    Keyword.intern("val"), x,
                    Keyword.intern("via"), via,
                    Keyword.intern("in"), in));
        }
        @Override public Object describe() { return form; }
    }

    private static synchronized void initSpecAlpha() {
        if (LOADED.contains("clojure.spec.alpha")) return;
        Namespace ns = Namespace.findOrCreate("clojure.spec.alpha");
        Atom reg = new Atom(PersistentHashMap.EMPTY);
        final boolean[] assertsEnabled = {false};

        Fn explainPrinter = Fn.of1("explain-printer", ed -> {
            PrintWriter out = StdLib.outWriter();
            if (ed == null) { out.println("Success!"); out.flush(); return null; }
            for (ISeq s = RT.seq(RT.get(ed, Keyword.intern("clojure.spec.alpha/problems"))); s != null; s = s.next()) {
                Object p = s.first();
                out.println("val: " + RT.printString(RT.get(p, Keyword.intern("val")))
                        + " fails predicate: " + RT.printString(RT.get(p, Keyword.intern("pred"))));
            }
            out.flush();
            return null;
        });
        Var explainOutVar = ns.intern(Symbol.intern("*explain-out*")).bindRoot(explainPrinter).setDynamic();

        Fn resolveSpec = Fn.of1("resolve-spec", s -> {
            if (s instanceof SpecObj) return s;
            if (s instanceof Keyword || s instanceof Symbol) {
                Object found = ((IPersistentMap) reg.deref()).valAt(s);
                return found;
            }
            if (s instanceof PersistentHashSet || s instanceof Set<?>) {
                Set<?> set = (Set<?>) s;
                return new SimpleSpec(s, Fn.of1("set-spec", set::contains), null, false, null);
            }
            if (s instanceof Fn f) return new SimpleSpec(s, f, null, false, null);
            return null;
        });

        ns.intern("registry", Fn.of0("registry", reg::deref));
        ns.intern("get-spec", Fn.of1("get-spec", k -> resolveSpec.invoke(k)));
        ns.intern("spec?", Fn.of1("spec?", x -> x instanceof SpecObj));
        ns.intern("def-impl", Fn.of2("def-impl", (k, s) -> {
            Object spec = resolveSpec.invoke(s);
            if (spec == null) throw new IllegalArgumentException("Unable to resolve spec: " + s);
            reg.swap(Fn.of1("reg-put", m -> ((IPersistentMap) m).assoc(k, spec)));
            return k;
        }));
        ns.internMacro("def", Fn.of2("def", (k, form) ->
                PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "def-impl"),
                        PersistentList.createFromArray(Symbol.intern("quote"), k),
                        specFormOrExpr(form))));

        ns.intern("spec-impl", Fn.of1("spec-impl", f -> resolveSpec.invoke(f)));
        ns.internMacro("spec", Fn.ofVarargs("spec", args -> specFormOrExpr(args[0])));

        ns.intern("conformer", Fn.of12("conformer",
                f -> new SimpleSpec(f, (Fn) f, null, true, null),
                (f, unf) -> new SimpleSpec(f, (Fn) f, (Fn) unf, true, null)));

        Fn conformFn = Fn.of2("conform", (s, x) -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            if (sp == null) throw new IllegalArgumentException("Unable to resolve spec: " + s);
            return sp.conform(x);
        });
        ns.intern("conform", conformFn);
        ns.intern("unform", Fn.of2("unform", (s, x) -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return sp == null ? x : sp.unform(x);
        }));
        ns.intern("valid?", Fn.of2("valid?", (s, x) -> conformFn.invoke(s, x) != SPEC_INVALID));

        Fn explainDataFn = Fn.of2("explain-data", (s, x) -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            if (sp == null) throw new IllegalArgumentException("Unable to resolve spec: " + s);
            List<PersistentHashMap> probs = sp.explain(PersistentVector.EMPTY,
                    s instanceof Keyword ? PersistentVector.createOwning(s) : PersistentVector.EMPTY,
                    PersistentVector.EMPTY, x);
            if (probs.isEmpty()) return null;
            return PersistentHashMap.create(
                    Keyword.intern("clojure.spec.alpha", "problems"), PersistentVector.create(probs),
                    Keyword.intern("clojure.spec.alpha", "spec"), s,
                    Keyword.intern("clojure.spec.alpha", "value"), x);
        });
        ns.intern("explain-data", explainDataFn);
        ns.intern("explain-out", Fn.of1("explain-out", ed -> ((Fn) explainOutVar.deref()).invoke(ed)));
        ns.intern("explain-printer", explainPrinter);
        ns.intern("explain", Fn.of2("explain", (s, x) -> ((Fn) explainOutVar.deref()).invoke(explainDataFn.invoke(s, x))));
        ns.intern("explain-str", Fn.of2("explain-str", (s, x) -> {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            Var.pushThreadBindings(Map.of(RT.OUT, pw));
            try {
                ((Fn) explainOutVar.deref()).invoke(explainDataFn.invoke(s, x));
                pw.flush();
                return sw.toString();
            } finally {
                Var.popThreadBindings();
            }
        }));
        ns.intern("describe", Fn.of1("describe", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return sp != null ? sp.describe() : s;
        }));
        ns.intern("form", Fn.of1("form", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return sp != null ? sp.describe() : s;
        }));
        ns.intern("check-asserts", Fn.of1("check-asserts", v -> assertsEnabled[0] = RT.truthy(v)));
        ns.intern("check-asserts?", Fn.of0("check-asserts?", () -> assertsEnabled[0]));
        ns.intern("assert*", Fn.of2("assert*", (s, x) -> {
            if (!assertsEnabled[0]) return x;
            if (conformFn.invoke(s, x) != SPEC_INVALID) return x;
            throw new IllegalStateException("Spec assertion failed: " + ((Fn) ns.findInternedVar(Symbol.intern("explain-str")).deref()).invoke(s, x));
        }));
        ns.internMacro("assert", Fn.of2("assert", (s, x) ->
                PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "assert*"),
                        specFormOrExpr(s), x)));

        // Combinators: nilable, tuple, coll-of, every, every-kv, map-of, int-in, double-in, inst-in
        ns.intern("nilable", Fn.of1("nilable", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return new SpecObj() {
                @Override public Object conform(Object x) { return x == null ? null : sp.conform(x); }
                @Override public Object unform(Object x) { return x == null ? null : sp.unform(x); }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    return x == null ? List.of() : sp.explain(path, via, in, x);
                }
                @Override public Object describe() { return PersistentList.createFromArray(Symbol.intern("nilable"), sp.describe()); }
            };
        }));
        ns.intern("nonconforming", Fn.of1("nonconforming", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return new SpecObj() {
                @Override public Object conform(Object x) { return sp.conform(x) == SPEC_INVALID ? SPEC_INVALID : x; }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) { return sp.explain(path, via, in, x); }
                @Override public Object describe() { return PersistentList.createFromArray(Symbol.intern("nonconforming"), sp.describe()); }
            };
        }));
        ns.intern("tuple", Fn.ofVarargs("tuple", specs -> {
            List<SpecObj> sps = new ArrayList<>();
            for (Object s : specs) sps.add((SpecObj) resolveSpec.invoke(s));
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    if (!(x instanceof PersistentVector v) || v.cnt != sps.size()) return SPEC_INVALID;
                    Object[] res = new Object[sps.size()];
                    for (int i = 0; i < sps.size(); i++) {
                        Object c = sps.get(i).conform(v.nth(i));
                        if (c == SPEC_INVALID) return SPEC_INVALID;
                        res[i] = c;
                    }
                    return PersistentVector.createOwning(res);
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    if (!(x instanceof PersistentVector v) || v.cnt != sps.size()) {
                        return List.of(PersistentHashMap.create(Keyword.intern("path"), path, Keyword.intern("pred"), Symbol.intern("vector?"), Keyword.intern("val"), x));
                    }
                    List<PersistentHashMap> probs = new ArrayList<>();
                    for (int i = 0; i < sps.size(); i++) probs.addAll(sps.get(i).explain(path, via, in, v.nth(i)));
                    return probs;
                }
                @Override public Object describe() { return PersistentList.createFromArray(Symbol.intern("tuple"), sps.size()); }
            };
        }));

        Fn collSpec = Fn.ofVarargs("coll-of-impl", args -> {
            SpecObj elem = (SpecObj) resolveSpec.invoke(args[0]);
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    if (x == null || !(x instanceof Collection<?> || x instanceof Seqable || x instanceof ISeq)) return SPEC_INVALID;
                    List<Object> out = new ArrayList<>();
                    for (ISeq s = RT.seq(x); s != null; s = s.next()) {
                        Object c = elem.conform(s.first());
                        if (c == SPEC_INVALID) return SPEC_INVALID;
                        out.add(c);
                    }
                    return x instanceof PersistentVector ? PersistentVector.create(out)
                            : (x instanceof Set<?> ? PersistentHashSet.create(out) : PersistentList.create(out));
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    if (x == null || !(x instanceof Collection<?> || x instanceof Seqable || x instanceof ISeq)) {
                        return List.of(PersistentHashMap.create(Keyword.intern("path"), path, Keyword.intern("pred"), Symbol.intern("coll?"), Keyword.intern("val"), x));
                    }
                    List<PersistentHashMap> probs = new ArrayList<>();
                    for (ISeq s = RT.seq(x); s != null; s = s.next()) probs.addAll(elem.explain(path, via, in, s.first()));
                    return probs;
                }
                @Override public Object describe() { return PersistentList.createFromArray(Symbol.intern("coll-of"), elem.describe()); }
            };
        });
        ns.intern("coll-of", collSpec);
        ns.intern("every", collSpec);

        Fn mapSpec = Fn.ofVarargs("map-of-impl", args -> {
            SpecObj ks = (SpecObj) resolveSpec.invoke(args[0]);
            SpecObj vs = (SpecObj) resolveSpec.invoke(args[1]);
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    if (!(x instanceof Map<?, ?> m)) return SPEC_INVALID;
                    PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        Object ck = ks.conform(e.getKey());
                        Object cv = vs.conform(e.getValue());
                        if (ck == SPEC_INVALID || cv == SPEC_INVALID) return SPEC_INVALID;
                        tm = tm.assoc(ck, cv);
                    }
                    return tm.persistent();
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    if (!(x instanceof Map<?, ?> m)) {
                        return List.of(PersistentHashMap.create(Keyword.intern("path"), path, Keyword.intern("pred"), Symbol.intern("map?"), Keyword.intern("val"), x));
                    }
                    List<PersistentHashMap> probs = new ArrayList<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        probs.addAll(ks.explain(path, via, in, e.getKey()));
                        probs.addAll(vs.explain(path, via, in, e.getValue()));
                    }
                    return probs;
                }
                @Override public Object describe() { return PersistentList.createFromArray(Symbol.intern("map-of"), ks.describe(), vs.describe()); }
            };
        });
        ns.intern("map-of", mapSpec);
        ns.intern("every-kv", mapSpec);

        ns.intern("int-in-range?", Fn.of3("int-in-range?", (start, end, val) ->
                (val instanceof Long || val instanceof Integer)
                        && Numbers.toLong(val) >= Numbers.toLong(start)
                        && Numbers.toLong(val) < Numbers.toLong(end)));
        ns.intern("int-in", Fn.of2("int-in", (start, end) -> {
            long lo = Numbers.toLong(start), hi = Numbers.toLong(end);
            return new SimpleSpec(PersistentList.createFromArray(Symbol.intern("int-in"), lo, hi),
                    Fn.of1("int-in?", x -> (x instanceof Long || x instanceof Integer) && Numbers.toLong(x) >= lo && Numbers.toLong(x) < hi),
                    null, false, null);
        }));
        ns.intern("inst-in-range?", Fn.of3("inst-in-range?", (start, end, val) -> {
            long ms = ((Date) val).getTime();
            return ms >= ((Date) start).getTime() && ms < ((Date) end).getTime();
        }));
        ns.intern("inst-in", Fn.of2("inst-in", (start, end) -> {
            long lo = ((Date) start).getTime(), hi = ((Date) end).getTime();
            return new SimpleSpec(PersistentList.createFromArray(Symbol.intern("inst-in"), start, end),
                    Fn.of1("inst-in?", x -> x instanceof Date d && d.getTime() >= lo && d.getTime() < hi),
                    null, false, null);
        }));
        ns.intern("double-in", Fn.ofVarargs("double-in", args -> {
            Map<Object, Object> opts = new HashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) opts.put(args[i], args[i + 1]);
            Double min = opts.get(Keyword.intern("min")) instanceof Number n ? n.doubleValue() : null;
            Double max = opts.get(Keyword.intern("max")) instanceof Number n ? n.doubleValue() : null;
            boolean allowInf = RT.truthy(opts.getOrDefault(Keyword.intern("infinite?"), Boolean.TRUE));
            boolean allowNan = RT.truthy(opts.getOrDefault(Keyword.intern("NaN?"), Boolean.TRUE));
            return new SimpleSpec(Symbol.intern("double-in"), Fn.of1("double-in?", x -> {
                if (!(x instanceof Double || x instanceof Float)) return false;
                double d = ((Number) x).doubleValue();
                if (!allowInf && Double.isInfinite(d)) return false;
                if (!allowNan && Double.isNaN(d)) return false;
                if (min != null && d < min) return false;
                if (max != null && d > max) return false;
                return true;
            }), null, false, null);
        }));

        // and / or / keys / keys* / merge / cat / alt / * / + / ? / & / multi-spec / with-gen / gen / exercise / fdef / fspec
        ns.intern("and-spec-impl", Fn.ofVarargs("and-spec-impl", specs -> {
            List<SpecObj> sps = new ArrayList<>();
            for (Object s : specs) sps.add((SpecObj) resolveSpec.invoke(s));
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    Object cur = x;
                    for (SpecObj sp : sps) {
                        cur = sp.conform(cur);
                        if (cur == SPEC_INVALID) return SPEC_INVALID;
                    }
                    return cur;
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    Object cur = x;
                    for (SpecObj sp : sps) {
                        List<PersistentHashMap> probs = sp.explain(path, via, in, cur);
                        if (!probs.isEmpty()) return probs;
                        cur = sp.conform(cur);
                    }
                    return List.of();
                }
                @Override public Object describe() { return Symbol.intern("and"); }
            };
        }));
        ns.internMacro("and", Fn.ofVarargs("and", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "and-spec-impl"));
            for (Object a : args) call.add(specFormOrExpr(a));
            return PersistentList.create(call);
        }));

        ns.intern("or-spec-impl", Fn.ofVarargs("or-spec-impl", args -> {
            List<Keyword> tags = new ArrayList<>();
            List<SpecObj> sps = new ArrayList<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                tags.add((Keyword) args[i]);
                sps.add((SpecObj) resolveSpec.invoke(args[i + 1]));
            }
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    for (int i = 0; i < sps.size(); i++) {
                        Object c = sps.get(i).conform(x);
                        if (c != SPEC_INVALID) return PersistentVector.createOwning(tags.get(i), c);
                    }
                    return SPEC_INVALID;
                }
                @Override public Object unform(Object x) {
                    if (x instanceof PersistentVector pv && pv.cnt == 2) {
                        int idx = tags.indexOf(pv.nth(0));
                        if (idx >= 0) return sps.get(idx).unform(pv.nth(1));
                    }
                    return x;
                }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    if (conform(x) != SPEC_INVALID) return List.of();
                    List<PersistentHashMap> probs = new ArrayList<>();
                    for (int i = 0; i < sps.size(); i++) probs.addAll(sps.get(i).explain((PersistentVector) RT.conj(path, tags.get(i)), via, in, x));
                    return probs;
                }
                @Override public Object describe() { return Symbol.intern("or"); }
            };
        }));
        ns.internMacro("or", Fn.ofVarargs("or", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "or-spec-impl"));
            for (int i = 0; i + 1 < args.length; i += 2) {
                call.add(args[i]);
                call.add(specFormOrExpr(args[i + 1]));
            }
            return PersistentList.create(call);
        }));

        ns.intern("keys-impl", Fn.ofVarargs("keys-impl", args -> {
            List<Keyword> req = new ArrayList<>();
            List<Keyword> opt = new ArrayList<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                Keyword optK = (Keyword) args[i];
                if ("req".equals(optK.getName())) {
                    for (ISeq s = RT.seq(args[i + 1]); s != null; s = s.next()) req.add((Keyword) s.first());
                } else if ("req-un".equals(optK.getName())) {
                    for (ISeq s = RT.seq(args[i + 1]); s != null; s = s.next()) {
                        Keyword qk = (Keyword) s.first();
                        req.add(Keyword.intern(qk.getName()));
                    }
                } else if ("opt".equals(optK.getName())) {
                    for (ISeq s = RT.seq(args[i + 1]); s != null; s = s.next()) opt.add((Keyword) s.first());
                } else if ("opt-un".equals(optK.getName())) {
                    for (ISeq s = RT.seq(args[i + 1]); s != null; s = s.next()) {
                        Keyword qk = (Keyword) s.first();
                        opt.add(Keyword.intern(qk.getName()));
                    }
                }
            }
            Map<Keyword, Keyword> unqualToSpec = new HashMap<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                Keyword optK = (Keyword) args[i];
                if ("req-un".equals(optK.getName()) || "opt-un".equals(optK.getName())) {
                    for (ISeq s = RT.seq(args[i + 1]); s != null; s = s.next()) {
                        Keyword qk = (Keyword) s.first();
                        unqualToSpec.put(Keyword.intern(qk.getName()), qk);
                    }
                }
            }
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    if (!(x instanceof Map<?, ?> m)) return SPEC_INVALID;
                    for (Keyword rk : req) if (!m.containsKey(rk)) return SPEC_INVALID;
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() instanceof Keyword k) {
                            Keyword specKey = unqualToSpec.getOrDefault(k, k);
                            SpecObj sp = (SpecObj) resolveSpec.invoke(specKey);
                            if (sp != null && sp.conform(e.getValue()) == SPEC_INVALID) return SPEC_INVALID;
                        }
                    }
                    return x;
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    if (!(x instanceof Map<?, ?> m)) {
                        return List.of(PersistentHashMap.create(Keyword.intern("path"), path, Keyword.intern("pred"), Symbol.intern("map?"), Keyword.intern("val"), x));
                    }
                    List<PersistentHashMap> probs = new ArrayList<>();
                    for (Keyword rk : req) {
                        if (!m.containsKey(rk)) probs.add(PersistentHashMap.create(Keyword.intern("path"), path, Keyword.intern("pred"), rk, Keyword.intern("val"), m));
                    }
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() instanceof Keyword k) {
                            Keyword specKey = unqualToSpec.getOrDefault(k, k);
                            SpecObj sp = (SpecObj) resolveSpec.invoke(specKey);
                            if (sp != null) probs.addAll(sp.explain(path, via, in, e.getValue()));
                        }
                    }
                    return probs;
                }
                @Override public Object describe() { return Symbol.intern("keys"); }
            };
        }));
        ns.internMacro("keys", Fn.ofVarargs("keys", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "keys-impl"));
            for (Object a : args) call.add(a instanceof Keyword ? a : PersistentList.createFromArray(Symbol.intern("quote"), a));
            return PersistentList.create(call);
        }));
        ns.internMacro("keys*", Fn.ofVarargs("keys*", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "keys-impl"));
            for (Object a : args) call.add(a instanceof Keyword ? a : PersistentList.createFromArray(Symbol.intern("quote"), a));
            return PersistentList.create(call);
        }));
        ns.intern("specize", Fn.of1("specize", s -> resolveSpec.invoke(s)));
        ns.intern("merge", Fn.ofVarargs("merge", specs -> {
            List<SpecObj> sps = new ArrayList<>();
            for (Object s : specs) sps.add((SpecObj) resolveSpec.invoke(s));
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    if (!(x instanceof Map<?, ?>)) return SPEC_INVALID;
                    Object merged = x;
                    for (SpecObj sp : sps) {
                        Object c = sp.conform(x);
                        if (c == SPEC_INVALID) return SPEC_INVALID;
                        if (c instanceof IPersistentMap cm && merged instanceof IPersistentMap mm) {
                            for (ISeq s = cm.seq(); s != null; s = s.next()) {
                                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                                mm = mm.assoc(e.getKey(), e.getValue());
                            }
                            merged = mm;
                        }
                    }
                    return merged;
                }
                @Override public Object unform(Object x) {
                    Object cur = x;
                    for (int i = sps.size() - 1; i >= 0; i--) {
                        cur = sps.get(i).unform(cur);
                    }
                    return cur;
                }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    List<PersistentHashMap> probs = new ArrayList<>();
                    for (SpecObj sp : sps) probs.addAll(sp.explain(path, via, in, x));
                    return probs;
                }
                @Override public Object describe() { return Symbol.intern("merge"); }
            };
        }));

        // Regex specs: cat, alt, *, +, ?, &
        ns.intern("cat-impl", Fn.ofVarargs("cat-impl", args -> {
            List<Keyword> tags = new ArrayList<>();
            List<SpecObj> sps = new ArrayList<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                tags.add((Keyword) args[i]);
                sps.add((SpecObj) resolveSpec.invoke(args[i + 1]));
            }
            return makeSeqRegexSpec(Symbol.intern("cat"), (items, pos) -> {
                PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
                int p = pos;
                for (int i = 0; i < sps.size(); i++) {
                    SpecObj sp = sps.get(i);
                    if (sp.regex()) {
                        MatchRes mr = sp.regexConform(items, p);
                        if (!mr.ok()) return mr;
                        if (mr.value() != SPEC_INVALID) tm = tm.assoc(tags.get(i), mr.value());
                        p = mr.nextPos();
                    } else {
                        if (p >= items.size()) return new MatchRes(false, SPEC_INVALID, p, List.of());
                        Object c = sp.conform(items.get(p));
                        if (c == SPEC_INVALID) return new MatchRes(false, SPEC_INVALID, p, List.of());
                        tm = tm.assoc(tags.get(i), c);
                        p++;
                    }
                }
                return new MatchRes(true, tm.persistent(), p, List.of());
            });
        }));
        ns.internMacro("cat", Fn.ofVarargs("cat", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "cat-impl"));
            for (int i = 0; i + 1 < args.length; i += 2) {
                call.add(args[i]);
                call.add(specFormOrExpr(args[i + 1]));
            }
            return PersistentList.create(call);
        }));
        ns.intern("alt-impl", Fn.ofVarargs("alt-impl", args -> {
            List<Keyword> tags = new ArrayList<>();
            List<SpecObj> sps = new ArrayList<>();
            for (int i = 0; i + 1 < args.length; i += 2) {
                tags.add((Keyword) args[i]);
                sps.add((SpecObj) resolveSpec.invoke(args[i + 1]));
            }
            return makeSeqRegexSpec(Symbol.intern("alt"), (items, pos) -> {
                for (int i = 0; i < sps.size(); i++) {
                    SpecObj sp = sps.get(i);
                    if (sp.regex()) {
                        MatchRes mr = sp.regexConform(items, pos);
                        if (mr.ok()) return new MatchRes(true, PersistentVector.createOwning(tags.get(i), mr.value()), mr.nextPos(), List.of());
                    } else if (pos < items.size()) {
                        Object c = sp.conform(items.get(pos));
                        if (c != SPEC_INVALID) return new MatchRes(true, PersistentVector.createOwning(tags.get(i), c), pos + 1, List.of());
                    }
                }
                return new MatchRes(false, SPEC_INVALID, pos, List.of());
            });
        }));
        ns.internMacro("alt", Fn.ofVarargs("alt", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "alt-impl"));
            for (int i = 0; i + 1 < args.length; i += 2) {
                call.add(args[i]);
                call.add(specFormOrExpr(args[i + 1]));
            }
            return PersistentList.create(call);
        }));
        ns.intern("rep-impl", Fn.of1("rep-impl", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return makeSeqRegexSpec(Symbol.intern("*"), (items, pos) -> matchRep(sp, items, pos, 0));
        }));
        ns.internMacro("*", Fn.of1("*", s -> PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "rep-impl"), specFormOrExpr(s))));
        ns.intern("rep+-impl", Fn.of1("rep+-impl", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return makeSeqRegexSpec(Symbol.intern("+"), (items, pos) -> matchRep(sp, items, pos, 1));
        }));
        ns.internMacro("+", Fn.of1("+", s -> PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "rep+-impl"), specFormOrExpr(s))));
        ns.intern("qmark-impl", Fn.of1("qmark-impl", s -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return makeSeqRegexSpec(Symbol.intern("?"), (items, pos) -> {
                if (sp.regex()) {
                    MatchRes mr = sp.regexConform(items, pos);
                    return mr.ok() ? mr : new MatchRes(true, SPEC_INVALID, pos, List.of());
                }
                if (pos < items.size()) {
                    Object c = sp.conform(items.get(pos));
                    if (c != SPEC_INVALID) return new MatchRes(true, c, pos + 1, List.of());
                }
                return new MatchRes(true, SPEC_INVALID, pos, List.of());
            });
        }));
        ns.internMacro("?", Fn.of1("?", s -> PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "qmark-impl"), specFormOrExpr(s))));
        ns.intern("amp-impl", Fn.ofVarargs("amp-impl", args -> {
            SpecObj re = (SpecObj) resolveSpec.invoke(args[0]);
            List<SpecObj> preds = new ArrayList<>();
            for (int i = 1; i < args.length; i++) preds.add((SpecObj) resolveSpec.invoke(args[i]));
            return makeSeqRegexSpec(Symbol.intern("&"), (items, pos) -> {
                MatchRes mr = re.regex() ? re.regexConform(items, pos)
                        : (pos < items.size() && re.conform(items.get(pos)) != SPEC_INVALID
                        ? new MatchRes(true, re.conform(items.get(pos)), pos + 1, List.of())
                        : new MatchRes(false, SPEC_INVALID, pos, List.of()));
                if (!mr.ok()) return mr;
                for (SpecObj p : preds) if (p.conform(mr.value()) == SPEC_INVALID) return new MatchRes(false, SPEC_INVALID, pos, List.of());
                return mr;
            });
        }));
        ns.internMacro("&", Fn.ofVarargs("&", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "amp-impl"));
            for (Object a : args) call.add(specFormOrExpr(a));
            return PersistentList.create(call);
        }));

        // multi-spec, with-gen, gen, exercise, exercise-fn, fdef, fspec
        ns.internMacro("multi-spec", Fn.of2("multi-spec", (mm, retag) ->
                PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "multi-spec-impl"), mm,
                        retag instanceof Keyword ? retag : PersistentList.createFromArray(Symbol.intern("quote"), retag))));
        ns.intern("multi-spec-impl", Fn.of2("multi-spec-impl", (mmObj, retag) -> {
            MultiFn mm = (MultiFn) mmObj;
            return new SpecObj() {
                @Override public Object conform(Object x) {
                    SpecObj sp = (SpecObj) resolveSpec.invoke(mm.invoke(x));
                    return sp == null ? SPEC_INVALID : sp.conform(x);
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    SpecObj sp = (SpecObj) resolveSpec.invoke(mm.invoke(x));
                    return sp == null ? List.of(PersistentHashMap.create(Keyword.intern("val"), x)) : sp.explain(path, via, in, x);
                }
                @Override public Object describe() { return Symbol.intern("multi-spec"); }
            };
        }));
        ns.intern("with-gen", Fn.of2("with-gen", (s, genFn) -> {
            SpecObj sp = (SpecObj) resolveSpec.invoke(s);
            return sp instanceof SimpleSpec ss ? new SimpleSpec(ss.form, ss.pred, ss.unformer, ss.conformer, (Fn) genFn) : sp;
        }));
        ns.intern("gen", Fn.of12("gen",
                s -> RT.CLOJURE_NS.findInternedVar(Symbol.intern("constantly")).invoke(null),
                (s, overrides) -> RT.CLOJURE_NS.findInternedVar(Symbol.intern("constantly")).invoke(null)));
        ns.intern("exercise", Fn.of123("exercise",
                s -> PersistentList.EMPTY,
                (s, n) -> PersistentList.EMPTY,
                (s, n, over) -> PersistentList.EMPTY));
        ns.intern("exercise-fn", Fn.of12("exercise-fn",
                sym -> PersistentList.EMPTY,
                (sym, n) -> PersistentList.EMPTY));
        ns.intern("fspec-impl", Fn.ofVarargs("fspec-impl", args -> {
            SpecObj argsSpec = null, retSpec = null, fnSpec = null;
            for (int i = 0; i + 1 < args.length; i += 2) {
                Object k = args[i];
                Object v = args[i + 1];
                if (v == null) continue;
                if (Keyword.intern("args").equals(k)) argsSpec = (SpecObj) resolveSpec.invoke(v);
                else if (Keyword.intern("ret").equals(k)) retSpec = (SpecObj) resolveSpec.invoke(v);
                else if (Keyword.intern("fn").equals(k)) fnSpec = (SpecObj) resolveSpec.invoke(v);
            }
            final SpecObj fArgs = argsSpec, fRet = retSpec, fFn = fnSpec;
            return new SpecObj() {
                @Override public Object conform(Object f) {
                    return (f instanceof clojure.lang.IFn) ? f : SPEC_INVALID;
                }
                @Override public Object unform(Object x) { return x; }
                @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                    if (x instanceof clojure.lang.IFn) return List.of();
                    return List.of(PersistentHashMap.create(
                            Keyword.intern("path"), path, Keyword.intern("pred"), Symbol.intern("ifn?"), Keyword.intern("val"), x));
                }
                @Override public Object describe() {
                    List<Object> form = new ArrayList<>();
                    form.add(Symbol.intern("fspec"));
                    form.add(Keyword.intern("args")); form.add(fArgs != null ? fArgs.describe() : null);
                    form.add(Keyword.intern("ret")); form.add(fRet != null ? fRet.describe() : null);
                    form.add(Keyword.intern("fn")); form.add(fFn != null ? fFn.describe() : null);
                    return PersistentList.create(form);
                }
            };
        }));
        ns.internMacro("fspec", Fn.ofVarargs("fspec", args -> {
            List<Object> call = new ArrayList<>();
            call.add(Symbol.intern("clojure.spec.alpha", "fspec-impl"));
            for (int i = 0; i + 1 < args.length; i += 2) {
                call.add(args[i]);
                call.add(specFormOrExpr(args[i + 1]));
            }
            return PersistentList.create(call);
        }));
        ns.internMacro("fdef", Fn.ofVarargs("fdef", args -> {
            Symbol rawSym = (Symbol) args[0];
            Symbol resolved = rawSym;
            if (rawSym.ns == null) {
                Object mapped = RT.currentNS().getMapping(rawSym);
                if (mapped instanceof Var v) {
                    resolved = Symbol.intern(v.ns.name.name, v.sym.name);
                } else {
                    resolved = Symbol.intern(RT.currentNS().name.name, rawSym.name);
                }
            }
            List<Object> fspecCall = new ArrayList<>();
            fspecCall.add(Symbol.intern("clojure.spec.alpha", "fspec"));
            for (int i = 1; i < args.length; i++) fspecCall.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.spec.alpha", "def-impl"),
                    PersistentList.createFromArray(Symbol.intern("quote"), resolved),
                    PersistentList.create(fspecCall));
        }));

        LOADED.add("clojure.spec.alpha");
    }

    private interface RegexMatcher { MatchRes match(List<Object> items, int pos); }

    private static SpecObj makeSeqRegexSpec(Symbol desc, RegexMatcher matcher) {
        return new SpecObj() {
            @Override public boolean regex() { return true; }
            @Override public MatchRes regexConform(List<Object> items, int pos) { return matcher.match(items, pos); }
            @Override public Object conform(Object x) {
                if (x != null && !(x instanceof ISeq || x instanceof Seqable || x instanceof Collection<?>)) return SPEC_INVALID;
                List<Object> items = x == null ? List.of() : toList(x);
                MatchRes mr = matcher.match(items, 0);
                return (mr.ok() && mr.nextPos() == items.size()) ? mr.value() : SPEC_INVALID;
            }
            @Override public Object unform(Object x) { return x; }
            @Override public List<PersistentHashMap> explain(PersistentVector path, PersistentVector via, Object in, Object x) {
                if (conform(x) != SPEC_INVALID) return List.of();
                return List.of(PersistentHashMap.create(Keyword.intern("path"), path, Keyword.intern("pred"), desc, Keyword.intern("val"), x));
            }
            @Override public Object describe() { return desc; }
        };
    }

    private static MatchRes matchRep(SpecObj sp, List<Object> items, int pos, int min) {
        List<Object> out = new ArrayList<>();
        int p = pos;
        while (p < items.size()) {
            if (sp.regex()) {
                MatchRes mr = sp.regexConform(items, p);
                if (!mr.ok() || mr.nextPos() == p) break;
                out.add(mr.value());
                p = mr.nextPos();
            } else {
                Object c = sp.conform(items.get(p));
                if (c == SPEC_INVALID) break;
                out.add(c);
                p++;
            }
        }
        if (out.size() < min) return new MatchRes(false, SPEC_INVALID, pos, List.of());
        return new MatchRes(true, PersistentVector.create(out), p, List.of());
    }

    private static Object specFormOrExpr(Object form) {
        if (form instanceof Symbol s) {
            return s;
        }
        if (form instanceof Keyword || form instanceof PersistentHashSet) {
            return PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "spec-impl"), form);
        }
        return PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "spec-impl"), form);
    }

    // ========================================================================
    // clojure.data.priority-map, int-map, avl & flatland.ordered.*
    // ========================================================================

    private static final class PriorityMap extends Fn implements Associative, Map<Object, Object>, PersistentColl, IObj, Reversible {
        final PersistentHashMap map;
        final PersistentTreeMap ordered; // priorityKey -> val, sorted by (priority, key)
        final Comparator<Object> priCmp;
        final PersistentHashMap meta;

        private record PriKey(Object pri, Object k) {}

        PriorityMap(PersistentHashMap map, PersistentTreeMap ordered, Comparator<Object> priCmp, PersistentHashMap meta) {
            this.map = map;
            this.ordered = ordered;
            this.priCmp = priCmp;
            this.meta = meta;
        }

        static PriorityMap createEmpty(Comparator<Object> priCmp) {
            Comparator<Object> comp = (a, b) -> {
                PriKey pa = (PriKey) a;
                PriKey pb = (PriKey) b;
                int c = priCmp.compare(pa.pri, pb.pri);
                if (c != 0) return c;
                if (RT.equiv(pa.k, pb.k)) return 0;
                return PersistentTreeMap.DEFAULT_COMPARATOR.compare(pa.k, pb.k);
            };
            return new PriorityMap(PersistentHashMap.EMPTY, PersistentTreeMap.create(comp), priCmp, null);
        }

        @Override public int count() { return map.count(); }
        @Override public int size() { return map.count(); }
        @Override public boolean isEmpty() { return map.count() == 0; }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Object get(Object key) { return map.valAt(key); }
        @Override public Object valAt(Object key) { return map.valAt(key); }
        @Override public Object valAt(Object key, Object notFound) { return map.valAt(key, notFound); }
        @Override public Object invoke(Object key) { return map.valAt(key); }
        @Override public Object invoke(Object key, Object notFound) { return map.valAt(key, notFound); }
        @Override public PersistentHashMap meta() { return meta; }
        @Override public IObj withMeta(PersistentHashMap m) { return new PriorityMap(map, ordered, priCmp, m); }

        @Override public PriorityMap assoc(Object k, Object pri) {
            PersistentHashMap m2 = map.assoc(k, pri);
            PersistentTreeMap o2 = map.containsKey(k)
                    ? ordered.without(new PriKey(map.valAt(k), k))
                    : ordered;
            o2 = o2.assoc(new PriKey(pri, k), pri);
            return new PriorityMap(m2, o2, priCmp, meta);
        }

        @Override public PriorityMap cons(Object x) {
            if (x instanceof Map.Entry<?, ?> e) return assoc(e.getKey(), e.getValue());
            if (x instanceof IPersistentVector v && v.count() == 2) return assoc(v.nth(0), v.nth(1));
            if (x instanceof Map<?, ?> m) {
                PriorityMap res = this;
                for (Map.Entry<?, ?> e : m.entrySet()) res = res.assoc(e.getKey(), e.getValue());
                return res;
            }
            throw new IllegalArgumentException("Cannot conj onto priority-map: " + x);
        }

        @Override public PriorityMap without(Object k) {
            if (!map.containsKey(k)) return this;
            Object oldPri = map.valAt(k);
            return new PriorityMap(map.without(k), ordered.without(new PriKey(oldPri, k)), priCmp, meta);
        }

        @Override public PriorityMap empty() { return new PriorityMap(PersistentHashMap.EMPTY, (PersistentTreeMap) ordered.empty(), priCmp, meta); }

        @Override public Object peek() {
            ISeq s = seq();
            return s == null ? null : s.first();
        }

        @Override public PriorityMap pop() {
            Object p = peek();
            if (p == null) return this;
            return without(((Map.Entry<?, ?>) p).getKey());
        }

        @Override public ISeq seq() {
            ISeq os = ordered.seq();
            if (os == null) return null;
            List<Object> entries = new ArrayList<>(map.count());
            for (ISeq s = os; s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                PriKey pk = (PriKey) e.getKey();
                entries.add(new PersistentHashMap.MapEntry(pk.k, e.getValue()));
            }
            return PersistentList.create(entries);
        }

        @Override public ISeq rseq() {
            ISeq os = ordered.rseq();
            if (os == null) return null;
            List<Object> entries = new ArrayList<>(map.count());
            for (ISeq s = os; s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                PriKey pk = (PriKey) e.getKey();
                entries.add(new PersistentHashMap.MapEntry(pk.k, e.getValue()));
            }
            return PersistentList.create(entries);
        }

        @Override public Set<Object> keySet() {
            Set<Object> ks = new LinkedHashSet<>();
            for (ISeq s = seq(); s != null; s = s.next()) ks.add(((Map.Entry<?, ?>) s.first()).getKey());
            return Collections.unmodifiableSet(ks);
        }
        @Override public Collection<Object> values() {
            List<Object> vs = new ArrayList<>();
            for (ISeq s = seq(); s != null; s = s.next()) vs.add(((Map.Entry<?, ?>) s.first()).getValue());
            return Collections.unmodifiableList(vs);
        }
        @Override public Set<Entry<Object, Object>> entrySet() {
            Set<Entry<Object, Object>> es = new LinkedHashSet<>();
            for (ISeq s = seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                es.add(new PersistentHashMap.MapEntry(e.getKey(), e.getValue()));
            }
            return Collections.unmodifiableSet(es);
        }
        @Override public Object put(Object k, Object v) { throw new UnsupportedOperationException(); }
        @Override public Object remove(Object k) { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<?, ?> m) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public boolean equals(Object o) { return map.equals(o); }
        @Override public int hashCode() { return map.hashCode(); }
        @Override public String toString() { return RT.printString(this); }
    }

    private static final class OrderedMap extends Fn implements Associative, Map<Object, Object>, PersistentColl, IObj {
        final PersistentHashMap map;
        final PersistentVector order;
        final PersistentHashMap meta;

        static final OrderedMap EMPTY = new OrderedMap(PersistentHashMap.EMPTY, PersistentVector.EMPTY, null);

        OrderedMap(PersistentHashMap map, PersistentVector order, PersistentHashMap meta) {
            this.map = map;
            this.order = order;
            this.meta = meta;
        }
        @Override public int count() { return map.count(); }
        @Override public int size() { return map.count(); }
        @Override public boolean isEmpty() { return map.count() == 0; }
        @Override public boolean containsKey(Object key) { return map.containsKey(key); }
        @Override public boolean containsValue(Object value) { return map.containsValue(value); }
        @Override public Object get(Object key) { return map.valAt(key); }
        @Override public Object valAt(Object key) { return map.valAt(key); }
        @Override public Object valAt(Object key, Object notFound) { return map.valAt(key, notFound); }
        @Override public Object invoke(Object key) { return map.valAt(key); }
        @Override public Object invoke(Object key, Object notFound) { return map.valAt(key, notFound); }
        @Override public PersistentHashMap meta() { return meta; }
        @Override public IObj withMeta(PersistentHashMap m) { return new OrderedMap(map, order, m); }

        @Override public OrderedMap assoc(Object k, Object v) {
            boolean existed = map.containsKey(k);
            return new OrderedMap(map.assoc(k, v), existed ? order : order.conj(k), meta);
        }
        @Override public OrderedMap cons(Object x) {
            if (x instanceof Map.Entry<?, ?> e) return assoc(e.getKey(), e.getValue());
            if (x instanceof IPersistentVector v && v.count() == 2) return assoc(v.nth(0), v.nth(1));
            if (x instanceof Map<?, ?> m) {
                OrderedMap res = this;
                for (Map.Entry<?, ?> e : m.entrySet()) res = res.assoc(e.getKey(), e.getValue());
                return res;
            }
            throw new IllegalArgumentException("Cannot conj onto ordered-map: " + x);
        }
        @Override public OrderedMap without(Object k) {
            if (!map.containsKey(k)) return this;
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            for (int i = 0; i < order.cnt; i++) {
                Object item = order.nth(i);
                if (!RT.equiv(item, k)) tv = tv.conj(item);
            }
            return new OrderedMap(map.without(k), tv.persistent(), meta);
        }
        @Override public OrderedMap empty() { return new OrderedMap(PersistentHashMap.EMPTY, PersistentVector.EMPTY, meta); }

        @Override public ISeq seq() {
            if (map.count() == 0) return null;
            List<Object> entries = new ArrayList<>(order.cnt);
            for (int i = 0; i < order.cnt; i++) {
                Object k = order.nth(i);
                entries.add(new PersistentHashMap.MapEntry(k, map.valAt(k)));
            }
            return PersistentList.create(entries);
        }
        @Override public Set<Object> keySet() {
            Set<Object> ks = new LinkedHashSet<>();
            for (int i = 0; i < order.cnt; i++) ks.add(order.nth(i));
            return Collections.unmodifiableSet(ks);
        }
        @Override public Collection<Object> values() {
            List<Object> vs = new ArrayList<>();
            for (int i = 0; i < order.cnt; i++) vs.add(map.valAt(order.nth(i)));
            return Collections.unmodifiableList(vs);
        }
        @Override public Set<Entry<Object, Object>> entrySet() {
            Set<Entry<Object, Object>> es = new LinkedHashSet<>();
            for (int i = 0; i < order.cnt; i++) {
                Object k = order.nth(i);
                es.add(new PersistentHashMap.MapEntry(k, map.valAt(k)));
            }
            return Collections.unmodifiableSet(es);
        }
        @Override public Object put(Object k, Object v) { throw new UnsupportedOperationException(); }
        @Override public Object remove(Object k) { throw new UnsupportedOperationException(); }
        @Override public void putAll(Map<?, ?> m) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public boolean equals(Object o) { return map.equals(o); }
        @Override public int hashCode() { return map.hashCode(); }
        @Override public String toString() { return RT.printString(this); }
    }

    private static final class OrderedSet extends Fn implements Set<Object>, Seqable, Counted, PersistentColl, IObj {
        final PersistentHashSet set;
        final PersistentVector order;
        final PersistentHashMap meta;

        static final OrderedSet EMPTY = new OrderedSet(PersistentHashSet.EMPTY, PersistentVector.EMPTY, null);

        OrderedSet(PersistentHashSet set, PersistentVector order, PersistentHashMap meta) {
            this.set = set;
            this.order = order;
            this.meta = meta;
        }
        @Override public int count() { return set.count(); }
        @Override public int size() { return set.count(); }
        @Override public boolean isEmpty() { return set.count() == 0; }
        @Override public boolean contains(Object o) { return set.contains(o); }
        @Override public Object invoke(Object key) { return set.contains(key) ? key : null; }
        @Override public Object invoke(Object key, Object notFound) { return set.contains(key) ? key : notFound; }
        @Override public PersistentHashMap meta() { return meta; }
        @Override public IObj withMeta(PersistentHashMap m) { return new OrderedSet(set, order, m); }

        @Override public Object cons(Object x) {
            if (set.contains(x)) return this;
            return new OrderedSet(set.conj(x), order.conj(x), meta);
        }
        @Override public Object without(Object x) {
            if (!set.contains(x)) return this;
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            for (int i = 0; i < order.cnt; i++) {
                Object item = order.nth(i);
                if (!RT.equiv(item, x)) tv = tv.conj(item);
            }
            return new OrderedSet(set.disjoin(x), tv.persistent(), meta);
        }
        @Override public Object empty() { return new OrderedSet(PersistentHashSet.EMPTY, PersistentVector.EMPTY, meta); }
        @Override public ISeq seq() {
            if (order.cnt == 0) return null;
            List<Object> list = new ArrayList<>(order.cnt);
            for (int i = 0; i < order.cnt; i++) list.add(order.nth(i));
            return PersistentList.create(list);
        }
        @Override public Iterator<Object> iterator() {
            List<Object> list = new ArrayList<>(order.cnt);
            for (int i = 0; i < order.cnt; i++) list.add(order.nth(i));
            return Collections.unmodifiableList(list).iterator();
        }
        @Override public Object[] toArray() { return order.toArray(); }
        @Override public <T> T[] toArray(T[] a) { return order.toArray(a); }
        @Override public boolean add(Object e) { throw new UnsupportedOperationException(); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException(); }
        @Override public boolean containsAll(Collection<?> c) { return set.containsAll(c); }
        @Override public boolean addAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean retainAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public boolean removeAll(Collection<?> c) { throw new UnsupportedOperationException(); }
        @Override public void clear() { throw new UnsupportedOperationException(); }
        @Override public boolean equals(Object o) { return set.equals(o); }
        @Override public int hashCode() { return set.hashCode(); }
        @Override public String toString() { return RT.printString(this); }
    }

    private static synchronized void initDataPriorityMap() {
        if (LOADED.contains("clojure.data.priority-map")) return;
        Namespace ns = Namespace.findOrCreate("clojure.data.priority-map");
        ns.intern("priority-map", Fn.ofVarargs("priority-map", args -> {
            PriorityMap pm = PriorityMap.createEmpty(PersistentTreeMap.DEFAULT_COMPARATOR);
            for (int i = 0; i + 1 < args.length; i += 2) pm = pm.assoc(args[i], args[i + 1]);
            return pm;
        }));
        ns.intern("priority-map-by", Fn.ofVarargs("priority-map-by", args -> {
            Comparator<Object> cmp = args[0] instanceof Fn f ? f.asComparator() : PersistentTreeMap.DEFAULT_COMPARATOR;
            PriorityMap pm = PriorityMap.createEmpty(cmp);
            for (int i = 1; i + 1 < args.length; i += 2) pm = pm.assoc(args[i], args[i + 1]);
            return pm;
        }));
        ns.intern("priority-map-keyfn", Fn.ofVarargs("priority-map-keyfn", args -> {
            Fn kfn = (Fn) args[0];
            Comparator<Object> cmp = (a, b) -> PersistentTreeMap.DEFAULT_COMPARATOR.compare(kfn.invoke(a), kfn.invoke(b));
            PriorityMap pm = PriorityMap.createEmpty(cmp);
            for (int i = 1; i + 1 < args.length; i += 2) pm = pm.assoc(args[i], args[i + 1]);
            return pm;
        }));
        LOADED.add("clojure.data.priority-map");
    }

    private static synchronized void initDataIntMap() {
        if (LOADED.contains("clojure.data.int-map")) return;
        Namespace ns = Namespace.findOrCreate("clojure.data.int-map");
        Comparator<Object> numCmp = (a, b) -> Long.compare(Numbers.toLong(a), Numbers.toLong(b));
        ns.intern("int-map", Fn.ofVarargs("int-map", args -> {
            PersistentTreeMap tm = PersistentTreeMap.create(numCmp);
            for (int i = 0; i + 1 < args.length; i += 2) tm = tm.assoc(Numbers.toLong(args[i]), args[i + 1]);
            return tm;
        }));
        ns.intern("int-set", Fn.ofVarargs("int-set", args -> {
            PersistentTreeSet ts = PersistentTreeSet.create(numCmp, null);
            for (Object a : args) {
                if (a instanceof Number) ts = ts.conj(Numbers.toLong(a));
                else for (ISeq s = RT.seq(a); s != null; s = s.next()) ts = ts.conj(Numbers.toLong(s.first()));
            }
            return ts;
        }));
        ns.intern("dense-int-set", ns.findInternedVar(Symbol.intern("int-set")).deref());
        LOADED.add("clojure.data.int-map");
    }

    private static synchronized void initDataAvl() {
        if (LOADED.contains("clojure.data.avl")) return;
        Namespace ns = Namespace.findOrCreate("clojure.data.avl");
        ns.intern("sorted-map", RT.CLOJURE_NS.findInternedVar(Symbol.intern("sorted-map")).deref());
        ns.intern("sorted-map-by", RT.CLOJURE_NS.findInternedVar(Symbol.intern("sorted-map-by")).deref());
        ns.intern("sorted-set", RT.CLOJURE_NS.findInternedVar(Symbol.intern("sorted-set")).deref());
        ns.intern("sorted-set-by", RT.CLOJURE_NS.findInternedVar(Symbol.intern("sorted-set-by")).deref());
        LOADED.add("clojure.data.avl");
    }

    private static synchronized void initFlatlandOrderedMap() {
        if (LOADED.contains("flatland.ordered.map")) return;
        Namespace ns = Namespace.findOrCreate("flatland.ordered.map");
        ns.intern("ordered-map", Fn.ofVarargs("ordered-map", args -> {
            OrderedMap om = OrderedMap.EMPTY;
            if (args.length == 1 && args[0] instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) om = om.assoc(e.getKey(), e.getValue());
                return om;
            }
            for (int i = 0; i + 1 < args.length; i += 2) om = om.assoc(args[i], args[i + 1]);
            return om;
        }));
        LOADED.add("flatland.ordered.map");
    }

    private static synchronized void initFlatlandOrderedSet() {
        if (LOADED.contains("flatland.ordered.set")) return;
        Namespace ns = Namespace.findOrCreate("flatland.ordered.set");
        ns.intern("ordered-set", Fn.ofVarargs("ordered-set", args -> {
            OrderedSet os = OrderedSet.EMPTY;
            for (Object a : args) os = (OrderedSet) os.cons(a);
            return os;
        }));
        LOADED.add("flatland.ordered.set");
    }

    private static synchronized void initFlatlandUsefulMap() {
        if (LOADED.contains("flatland.useful.map")) return;
        Namespace ns = Namespace.findOrCreate("flatland.useful.map");
        ns.intern("ordering-map", Fn.of12("ordering-map",
                ks -> orderingComparator(ks, PersistentTreeMap.DEFAULT_COMPARATOR),
                (ks, cmp) -> orderingComparator(ks, cmp instanceof Fn f ? f.asComparator() : PersistentTreeMap.DEFAULT_COMPARATOR)));
        LOADED.add("flatland.useful.map");
    }

    private static Fn orderingComparator(Object ks, Comparator<Object> fallback) {
        Map<Object, Integer> rank = new HashMap<>();
        int idx = 0;
        for (ISeq s = RT.seq(ks); s != null; s = s.next()) rank.putIfAbsent(s.first(), idx++);
        return Fn.of2("ordering-map", (a, b) -> {
            Integer ra = rank.get(a), rb = rank.get(b);
            if (ra != null && rb != null) return (long) Integer.compare(ra, rb);
            if (ra != null) return -1L;
            if (rb != null) return 1L;
            return (long) fallback.compare(a, b);
        });
    }

    // ========================================================================
    // clojure.core.async
    // ========================================================================

    private static final ScheduledExecutorService ASYNC_TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cljl-async-timeout");
        t.setDaemon(true);
        return t;
    });

    private interface ChannelBuffer {
        boolean isFull();
        void add(Object val);
        Object remove();
        int count();
    }

    private static final class FixedBuffer implements ChannelBuffer {
        final int n;
        final Deque<Object> q = new ArrayDeque<>();
        FixedBuffer(int n) { this.n = n; }
        @Override public boolean isFull() { return q.size() >= n; }
        @Override public void add(Object val) { q.addLast(val); }
        @Override public Object remove() { return q.pollFirst(); }
        @Override public int count() { return q.size(); }
    }

    private static final class DroppingBuffer implements ChannelBuffer {
        final int n;
        final Deque<Object> q = new ArrayDeque<>();
        DroppingBuffer(int n) { this.n = n; }
        @Override public boolean isFull() { return false; }
        @Override public void add(Object val) { if (q.size() < n) q.addLast(val); }
        @Override public Object remove() { return q.pollFirst(); }
        @Override public int count() { return q.size(); }
    }

    private static final class SlidingBuffer implements ChannelBuffer {
        final int n;
        final Deque<Object> q = new ArrayDeque<>();
        SlidingBuffer(int n) { this.n = n; }
        @Override public boolean isFull() { return false; }
        @Override public void add(Object val) {
            if (n <= 0) return;
            if (q.size() >= n) q.pollFirst();
            q.addLast(val);
        }
        @Override public Object remove() { return q.pollFirst(); }
        @Override public int count() { return q.size(); }
    }

    private static final class PromiseBuffer implements ChannelBuffer {
        boolean delivered = false;
        Object val = null;
        @Override public boolean isFull() { return false; }
        @Override public void add(Object v) {
            if (!delivered) {
                delivered = true;
                val = v;
            }
        }
        @Override public Object remove() { return val; }
        @Override public int count() { return delivered ? 1 : 0; }
    }

    private static final class PutWaiter {
        final Object val;
        final CompletableFuture<Boolean> done = new CompletableFuture<>();
        final Fn callback;
        final Runnable onSelect;
        volatile boolean cancelled = false;
        PutWaiter(Object val, Fn callback) { this(val, callback, null); }
        PutWaiter(Object val, Fn callback, Runnable onSelect) {
            this.val = val;
            this.callback = callback;
            this.onSelect = onSelect;
        }
        void complete(boolean ok) {
            done.complete(ok);
            if (callback != null) {
                Atom.markMultiThreaded();
                Thread.startVirtualThread(() -> callback.invoke(ok));
            }
            if (onSelect != null) onSelect.run();
        }
    }

    private static final class TakeWaiter {
        final CompletableFuture<Object> done = new CompletableFuture<>();
        final Fn callback;
        final Runnable onSelect;
        volatile boolean cancelled = false;
        TakeWaiter(Fn callback, Runnable onSelect) { this.callback = callback; this.onSelect = onSelect; }
        void complete(Object val) {
            done.complete(val);
            if (callback != null) {
                Atom.markMultiThreaded();
                Thread.startVirtualThread(() -> callback.invoke(val));
            }
            if (onSelect != null) onSelect.run();
        }
    }

    private static final class Channel {
        final ChannelBuffer buf;
        final Fn xf;
        final Fn exHandler;
        final Deque<PutWaiter> puts = new ArrayDeque<>();
        final Deque<TakeWaiter> takes = new ArrayDeque<>();
        boolean closed = false;
        final Fn xformStep;

        Channel(ChannelBuffer buf, Fn xform, Fn exHandler) {
            this.buf = buf;
            this.exHandler = exHandler;
            if (xform != null) {
                Fn baseStep = new Fn.NamedFn("chan-xf-base") {
                    @Override public Object invoke() { return null; }
                    @Override public Object invoke(Object acc) { return acc; }
                    @Override public Object invoke(Object acc, Object x) {
                        buf.add(x);
                        return acc;
                    }
                };
                this.xf = (Fn) xform.invoke(baseStep);
                this.xformStep = xf;
            } else {
                this.xf = null;
                this.xformStep = null;
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            if (xformStep != null) {
                try { xformStep.invoke((Object) null); } catch (Throwable t) { handleEx(t); }
            }
            while (!takes.isEmpty()) {
                TakeWaiter tw = takes.pollFirst();
                if (tw.cancelled) continue;
                Object val = buf != null && buf.count() > 0 ? buf.remove() : null;
                tw.complete(val);
            }
            while (!puts.isEmpty()) {
                PutWaiter pw = puts.pollFirst();
                if (!pw.cancelled) pw.complete(false);
            }
        }

        private void handleEx(Throwable t) {
            if (exHandler != null) {
                Object r = exHandler.invoke(t);
                if (r != null && buf != null) buf.add(r);
            }
        }

        synchronized boolean offer(Object val) {
            if (val == null) throw new IllegalArgumentException("Can't put nil on channel");
            if (closed) return false;
            PutWaiter pw = new PutWaiter(val, null);
            return putAsync(val, null, pw);
        }

        synchronized boolean putAsync(Object val, Fn cb, PutWaiter pw) {
            if (val == null) throw new IllegalArgumentException("Can't put nil on channel");
            if (closed) {
                pw.complete(false);
                return false;
            }
            if (xformStep != null && buf != null && !buf.isFull()) {
                try {
                    Object res = xformStep.invoke(null, val);
                    drainBufToTakes();
                    if (res instanceof Reduced) close();
                    pw.complete(true);
                    return true;
                } catch (Throwable t) {
                    handleEx(t);
                    drainBufToTakes();
                    pw.complete(true);
                    return true;
                }
            }
            if (buf instanceof PromiseBuffer) {
                buf.add(val);
                drainBufToTakes();
                pw.complete(true);
                return true;
            }
            while (!takes.isEmpty()) {
                TakeWaiter tw = takes.pollFirst();
                if (!tw.cancelled) {
                    tw.complete(val);
                    pw.complete(true);
                    return true;
                }
            }
            if (buf != null && !buf.isFull()) {
                buf.add(val);
                pw.complete(true);
                return true;
            }
            return false;
        }

        private void drainBufToTakes() {
            while (buf != null && buf.count() > 0 && !takes.isEmpty()) {
                TakeWaiter tw = takes.pollFirst();
                if (!tw.cancelled) tw.complete(buf.remove());
            }
        }

        CompletableFuture<Boolean> put(Object val, Fn cb, boolean blockIfFull) {
            PutWaiter pw = new PutWaiter(val, cb);
            synchronized (this) {
                if (closed) {
                    pw.complete(false);
                    return pw.done;
                }
                if (putAsync(val, cb, pw)) return pw.done;
                if (blockIfFull) puts.addLast(pw);
                else pw.complete(false);
            }
            return pw.done;
        }

        synchronized Object poll() {
            if (buf != null && buf.count() > 0) {
                Object val = buf.remove();
                while (!puts.isEmpty()) {
                    PutWaiter pw = puts.pollFirst();
                    if (pw.cancelled) continue;
                    if (xformStep != null) xformStep.invoke(null, pw.val);
                    else buf.add(pw.val);
                    pw.complete(true);
                    break;
                }
                return val;
            }
            while (!puts.isEmpty()) {
                PutWaiter pw = puts.pollFirst();
                if (pw.cancelled) continue;
                pw.complete(true);
                return pw.val;
            }
            return null;
        }

        CompletableFuture<Object> take(Fn cb) {
            TakeWaiter tw = new TakeWaiter(cb, null);
            synchronized (this) {
                Object imm = poll();
                if (imm != null) {
                    tw.complete(imm);
                    return tw.done;
                }
                if (closed) {
                    tw.complete(null);
                    return tw.done;
                }
                takes.addLast(tw);
            }
            return tw.done;
        }

        synchronized boolean registerSelectTake(TakeWaiter tw) {
            Object imm = poll();
            if (imm != null) {
                tw.complete(imm);
                return true;
            }
            if (closed) {
                tw.complete(null);
                return true;
            }
            takes.addLast(tw);
            return false;
        }

        synchronized boolean registerSelectPut(PutWaiter pw) {
            if (closed) {
                pw.complete(false);
                return true;
            }
            if (putAsync(pw.val, pw.callback, pw)) return true;
            puts.addLast(pw);
            return false;
        }
    }

    private static synchronized void initCoreAsync() {
        if (LOADED.contains("clojure.core.async")) return;
        Namespace ns = Namespace.findOrCreate("clojure.core.async");

        ns.intern("buffer", Fn.of1("buffer", n -> new FixedBuffer(StdLib.toInt(n))));
        ns.intern("dropping-buffer", Fn.of1("dropping-buffer", n -> new DroppingBuffer(StdLib.toInt(n))));
        ns.intern("sliding-buffer", Fn.of1("sliding-buffer", n -> new SlidingBuffer(StdLib.toInt(n))));

        ns.intern("chan", new Fn.NamedFn("chan") {
            @Override public Object invoke() { return new Channel(null, null, null); }
            @Override public Object invoke(Object bufOrN) {
                if (bufOrN == null) return new Channel(null, null, null);
                if (bufOrN instanceof ChannelBuffer cb) return new Channel(cb, null, null);
                int n = StdLib.toInt(bufOrN);
                return new Channel(n > 0 ? new FixedBuffer(n) : null, null, null);
            }
            @Override public Object invoke(Object bufOrN, Object xf) {
                ChannelBuffer cb = bufOrN == null ? new FixedBuffer(1)
                        : (bufOrN instanceof ChannelBuffer c ? c : new FixedBuffer(Math.max(1, StdLib.toInt(bufOrN))));
                return new Channel(cb, (Fn) xf, null);
            }
            @Override public Object invoke(Object bufOrN, Object xf, Object exh) {
                ChannelBuffer cb = bufOrN == null ? new FixedBuffer(1)
                        : (bufOrN instanceof ChannelBuffer c ? c : new FixedBuffer(Math.max(1, StdLib.toInt(bufOrN))));
                return new Channel(cb, (Fn) xf, (Fn) exh);
            }
        });

        ns.intern("close!", Fn.of1("close!", ch -> { ((Channel) ch).close(); return null; }));
        ns.intern("offer!", Fn.of2("offer!", (ch, val) -> ((Channel) ch).offer(val)));
        ns.intern("poll!", Fn.of1("poll!", ch -> ((Channel) ch).poll()));
        ns.intern("put!", Fn.of23("put!",
                (ch, val) -> {
                    CompletableFuture<Boolean> fut = ((Channel) ch).put(val, null, true);
                    return fut.isDone() ? fut.getNow(true) : Boolean.TRUE;
                },
                (ch, val, fn) -> {
                    CompletableFuture<Boolean> fut = ((Channel) ch).put(val, (Fn) fn, true);
                    return fut.isDone() ? fut.getNow(true) : Boolean.TRUE;
                }));
        ns.intern("take!", Fn.of2("take!", (ch, fn) -> { ((Channel) ch).take((Fn) fn); return null; }));

        Fn putBlock = Fn.of2(">!!", (ch, val) -> {
            try { return ((Channel) ch).put(val, null, true).get(); }
            catch (Exception e) { throw RT.sneakyThrow(e); }
        });
        Fn takeBlock = Fn.of1("<!!", ch -> {
            try { return ((Channel) ch).take(null).get(); }
            catch (Exception e) { throw RT.sneakyThrow(e); }
        });
        ns.intern(">!!", putBlock);
        ns.intern("<!!", takeBlock);
        ns.intern(">!", putBlock);
        ns.intern("<!", takeBlock);

        ns.intern("timeout", Fn.of1("timeout", ms -> {
            Channel ch = new Channel(null, null, null);
            ASYNC_TIMER.schedule(ch::close, Math.max(0L, Numbers.toLong(ms)), TimeUnit.MILLISECONDS);
            return ch;
        }));

        Fn goCall = Fn.of1("go-call", f -> {
            Channel res = new Channel(new FixedBuffer(1), null, null);
            Fn fn = (Fn) f;
            Map<Var, Object> bindings = Var.cloneThreadBindings();
            Atom.markMultiThreaded();
            Thread.startVirtualThread(() -> {
                if (!bindings.isEmpty()) Var.pushThreadBindings(bindings);
                try {
                    Object v = fn.invoke();
                    if (v != null) res.put(v, null, true);
                } finally {
                    if (!bindings.isEmpty()) Var.popThreadBindings();
                    res.close();
                }
            });
            return res;
        });
        ns.intern("go-call", goCall);
        ns.internMacro("go", Fn.ofVarargs("go", body -> {
            List<Object> fnForm = new ArrayList<>();
            fnForm.add(Symbol.intern("fn"));
            fnForm.add(PersistentVector.EMPTY);
            for (Object b : body) fnForm.add(b);
            return PersistentList.createFromArray(Symbol.intern("clojure.core.async", "go-call"), PersistentList.create(fnForm));
        }));

        ns.intern("thread-call", goCall);
        ns.internMacro("thread", Fn.ofVarargs("thread", body -> {
            List<Object> fnForm = new ArrayList<>();
            fnForm.add(Symbol.intern("fn"));
            fnForm.add(PersistentVector.EMPTY);
            for (Object b : body) fnForm.add(b);
            return PersistentList.createFromArray(Symbol.intern("clojure.core.async", "thread-call"), PersistentList.create(fnForm));
        }));

        Fn altsBlock = Fn.ofVarargs("alts!!", args -> {
            if (args.length == 0) throw new IllegalArgumentException("alts!! requires ports");
            Object ports = args[0];
            Map<Object, Object> opts = new HashMap<>();
            if (args.length == 2 && args[1] instanceof Map<?, ?> m) {
                opts.putAll(m);
            } else {
                for (int i = 1; i + 1 < args.length; i += 2) {
                    opts.put(args[i], args[i + 1]);
                }
            }
            return altsImpl(ports, opts);
        });
        ns.intern("alts!!", altsBlock);
        ns.intern("alts!", altsBlock);

        ns.intern("promise-chan", new Fn.NamedFn("promise-chan") {
            @Override public Object invoke() { return new Channel(new PromiseBuffer(), null, null); }
            @Override public Object invoke(Object xf) { return new Channel(new PromiseBuffer(), (Fn) xf, null); }
            @Override public Object invoke(Object xf, Object exh) { return new Channel(new PromiseBuffer(), (Fn) xf, (Fn) exh); }
        });

        LOADED.add("clojure.core.async");
        Var.pushThreadBindings(Map.of(RT.CURRENT_NS, ns));
        try {
            Compiler.load("""
                (clojure.core/refer-clojure :exclude '[map into merge reduce])
                (defmacro go-loop [bindings & body]
                  `(go (clojure.core/loop ~bindings ~@body)))

                (defmacro alt!! [& clauses]
                  (let [pairs (partition 2 clauses)
                        opt-map (clojure.core/into {} (clojure.core/map vec (filter (fn [[k _]] (keyword? k)) pairs)))
                        port-clauses (vec (remove (fn [[k _]] (keyword? k)) pairs))
                        res-sym (gensym "res__")
                        val-sym (gensym "val__")
                        ch-sym (gensym "ch__")
                        flat-ports (vec (mapcat (fn [[p _]]
                                                  (if (and (vector? p) (not= 2 (count p)))
                                                    p
                                                    (if (and (vector? p) (vector? (first p)))
                                                      p
                                                      [p])))
                                                port-clauses))
                        opt-args (mapcat identity opt-map)]
                    `(let [~res-sym (alts!! ~flat-ports ~@opt-args)
                           ~val-sym (first ~res-sym)
                           ~ch-sym (second ~res-sym)]
                       (cond
                         ~@(mapcat (fn [[p expr]]
                                     (let [match-expr
                                           (cond
                                             (= p :default) `(= ~ch-sym :default)
                                             (vector? p) `(= ~ch-sym ~(first p))
                                             :else `(= ~ch-sym ~p))
                                           body-expr
                                           (if (and (list? expr) (vector? (first expr)))
                                             `(let [~(first expr) ~res-sym] ~@(rest expr))
                                             expr)]
                                       [match-expr body-expr]))
                                   port-clauses)
                         ~@(when (contains? opt-map :default)
                             [`(= ~ch-sym :default) (:default opt-map)])
                         :else nil))))

                (defmacro alt! [& clauses]
                  `(alt!! ~@clauses))

                (defn to-chan!! [coll]
                  (let [ch (chan (max 1 (if (counted? coll) (count coll) 32)))]
                    (go
                      (doseq [v coll]
                        (>! ch v))
                      (close! ch))
                    ch))

                (def to-chan! to-chan!!)
                (def to-chan to-chan!!)

                (defn onto-chan!!
                  ([ch coll] (onto-chan!! ch coll true))
                  ([ch coll close?]
                   (go
                     (doseq [v coll]
                       (>! ch v))
                     (when close? (close! ch)))))

                (def onto-chan! onto-chan!!)
                (def onto-chan onto-chan!!)

                (defn pipe
                  ([from to] (pipe from to true))
                  ([from to close?]
                   (go-loop []
                     (let [v (<! from)]
                       (if (nil? v)
                         (when close? (close! to))
                         (when (>! to v)
                           (recur)))))
                   to))

                (defn merge
                  ([chs] (merge chs nil))
                  ([chs buf-or-n]
                   (let [out (chan buf-or-n)]
                     (go-loop [cs (vec chs)]
                       (if (empty? cs)
                         (close! out)
                         (let [[v c] (alts! cs)]
                           (if (nil? v)
                             (recur (vec (remove #(= % c) cs)))
                             (do (>! out v) (recur cs))))))
                     out)))

                (defn split
                  ([p ch] (split p ch nil nil))
                  ([p ch t-buf-or-n f-buf-or-n]
                   (let [tc (chan t-buf-or-n)
                         fc (chan f-buf-or-n)]
                     (go-loop []
                       (let [v (<! ch)]
                         (if (nil? v)
                           (do (close! tc) (close! fc))
                           (do
                             (>! (if (p v) tc fc) v)
                             (recur)))))
                     [tc fc])))

                (defn reduce [f init ch]
                  (go-loop [acc init]
                    (let [v (<! ch)]
                      (if (nil? v)
                        acc
                        (let [acc' (f acc v)]
                          (if (reduced? acc')
                            @acc'
                            (recur acc')))))))

                (defn into [coll ch]
                  (reduce conj coll ch))

                (defn transduce [xform f init ch]
                  (let [rf (xform f)]
                    (go
                      (let [res (<! (reduce rf init ch))]
                        (rf res)))))

                (defn map
                  ([f chs] (map f chs nil))
                  ([f chs buf-or-n]
                   (let [out (chan buf-or-n)
                         cs (vec chs)]
                     (go-loop []
                       (let [vals (mapv <!! cs)]
                         (if (some nil? vals)
                           (close! out)
                           (do (>! out (apply f vals)) (recur)))))
                     out)))

                (defn pipeline-blocking
                  ([n to xf from] (pipeline-blocking n to xf from true nil))
                  ([n to xf from close?] (pipeline-blocking n to xf from close? nil))
                  ([n to xf from close? ex-handler]
                   (let [jobs (chan n)
                         results (chan n)
                         process (fn [v p]
                                   (let [res (chan 1 xf ex-handler)]
                                     (>!! res v)
                                     (close! res)
                                     (let [items (loop [acc []]
                                                   (if-let [x (<!! res)]
                                                     (recur (conj acc x))
                                                     acc))]
                                       (>!! p items)
                                       (close! p))))]
                     (dotimes [_ (max 1 n)]
                       (thread
                         (loop []
                           (when-let [[v p] (<!! jobs)]
                             (process v p)
                             (recur)))))
                     (go-loop []
                       (if-let [v (<! from)]
                         (let [p (chan 1)]
                           (>! jobs [v p])
                           (>! results p)
                           (recur))
                         (do (close! jobs) (close! results))))
                     (go-loop []
                       (if-let [p (<! results)]
                         (do
                           (when-let [items (<! p)]
                             (doseq [x items] (>! to x)))
                           (recur))
                         (when close? (close! to)))))
                   nil))

                (def pipeline pipeline-blocking)

                (defn pipeline-async
                  ([n to af from] (pipeline-async n to af from true))
                  ([n to af from close?]
                   (let [results (chan n)]
                     (go-loop []
                       (if-let [v (<! from)]
                         (let [res (chan 1)]
                           (af v res)
                           (>! results res)
                           (recur))
                         (close! results)))
                     (go-loop []
                       (if-let [res (<! results)]
                         (do
                           (loop []
                             (when-let [x (<! res)]
                               (>! to x)
                               (recur)))
                           (recur))
                         (when close? (close! to)))))
                   nil))

                (defprotocol Mult
                  (tap* [m ch close?])
                  (untap* [m ch])
                  (untap-all* [m]))

                (defn mult [ch]
                  (let [taps (atom {})
                        m (reify Mult
                            (tap* [_ out close?] (swap! taps assoc out close?) out)
                            (untap* [_ out] (swap! taps dissoc out) nil)
                            (untap-all* [_] (reset! taps {}) nil))]
                    (go-loop []
                      (let [v (<! ch)]
                        (if (nil? v)
                          (doseq [[out close?] @taps]
                            (when close? (close! out)))
                          (do
                            (doseq [[out _] @taps]
                              (>! out v))
                            (recur)))))
                    m))

                (defn tap
                  ([m ch] (tap* m ch true))
                  ([m ch close?] (tap* m ch close?)))
                (defn untap [m ch] (untap* m ch))
                (defn untap-all [m] (untap-all* m))

                (defprotocol Pub
                  (sub* [p v ch close?])
                  (unsub* [p v ch])
                  (unsub-all* [p] [p v]))

                (defn pub
                  ([ch topic-fn] (pub ch topic-fn (constantly nil)))
                  ([ch topic-fn buf-fn]
                   (let [mults (atom {})
                         p (reify Pub
                             (sub* [_ topic out close?]
                               (let [[_ m] (or (get @mults topic)
                                               (let [c (chan (buf-fn topic))
                                                     m (mult c)]
                                                 (swap! mults assoc topic [c m])
                                                 [c m]))]
                                 (tap m out close?))
                               out)
                             (unsub* [_ topic out]
                               (when-let [[_ m] (get @mults topic)]
                                 (untap m out))
                               nil)
                             (unsub-all* [_]
                               (doseq [[_ [_ m]] @mults] (untap-all m))
                               (reset! mults {})
                               nil)
                             (unsub-all* [_ topic]
                               (when-let [[_ m] (get @mults topic)]
                                 (untap-all m)
                                 (swap! mults dissoc topic))
                               nil))]
                     (go-loop []
                       (let [v (<! ch)]
                         (if (nil? v)
                           (doseq [[_ [c _]] @mults] (close! c))
                           (let [topic (topic-fn v)]
                             (when-let [[c _] (get @mults topic)]
                               (>! c v))
                             (recur)))))
                     p)))

                (defn sub
                  ([p topic ch] (sub* p topic ch true))
                  ([p topic ch close?] (sub* p topic ch close?)))
                (defn unsub [p topic ch] (unsub* p topic ch))
                (defn unsub-all
                  ([p] (unsub-all* p))
                  ([p topic] (unsub-all* p topic)))

                (defprotocol Mix
                  (admix* [m ch])
                  (unmix* [m ch])
                  (unmix-all* [m])
                  (toggle* [m state-map])
                  (solo-mode* [m mode]))

                (defn mix [out]
                  (let [inputs (atom {})
                        mode (atom :mute)
                        changed (chan (sliding-buffer 1))
                        notify! (fn [] (offer! changed true))
                        m (reify Mix
                            (admix* [_ ch] (swap! inputs assoc ch {:mute false :pause false :solo false}) (notify!) nil)
                            (unmix* [_ ch] (swap! inputs dissoc ch) (notify!) nil)
                            (unmix-all* [_] (reset! inputs {}) (notify!) nil)
                            (toggle* [_ sm]
                              (swap! inputs (fn [cur]
                                              (reduce-kv (fn [acc ch st]
                                                           (assoc acc ch (clojure.core/merge (get acc ch {:mute false :pause false :solo false}) st)))
                                                         cur sm)))
                              (notify!)
                              nil)
                            (solo-mode* [_ md] (reset! mode md) (notify!) nil))]
                    (go-loop []
                      (let [ins @inputs
                            has-solo? (some :solo (vals ins))
                            active (vec (keep (fn [[ch st]]
                                                (let [paused? (or (:pause st)
                                                                  (and has-solo? (not (:solo st)) (= @mode :pause)))]
                                                  (when-not paused? ch)))
                                              ins))
                            [v c] (alts! (conj active changed))]
                        (cond
                          (= c changed) (recur)
                          (nil? v) (do (swap! inputs dissoc c) (recur))
                          :else
                          (let [st (get @inputs c)
                                muted? (or (:mute st)
                                           (and has-solo? (not (:solo st)) (= @mode :mute)))]
                            (when-not muted? (>! out v))
                            (recur)))))
                    m))

                (defn admix [m ch] (admix* m ch))
                (defn unmix [m ch] (unmix* m ch))
                (defn unmix-all [m] (unmix-all* m))
                (defn toggle [m state-map] (toggle* m state-map))
                (defn solo-mode [m mode] (solo-mode* m mode))
                """, "clojure/core/async.clj");
        } finally {
            Var.popThreadBindings();
        }
    }

    private static Object altsImpl(Object portsObj, Map<?, ?> opts) {
        List<Object> ports = new ArrayList<>(toList(portsObj));
        boolean priority = opts != null && RT.truthy(opts.get(Keyword.intern("priority")));
        if (!priority && ports.size() > 1) {
            Collections.shuffle(ports);
        }
        Keyword defaultKw = Keyword.intern("default");
        boolean hasDefault = opts != null && opts.containsKey(defaultKw);
        Object defaultVal = hasDefault ? opts.get(defaultKw) : null;

        CompletableFuture<PersistentVector> winner = new CompletableFuture<>();
        List<TakeWaiter> takeWaiters = new ArrayList<>();
        List<PutWaiter> putWaiters = new ArrayList<>();
        Runnable cancelAll = () -> {
            for (TakeWaiter tw : takeWaiters) tw.cancelled = true;
            for (PutWaiter pw : putWaiters) pw.cancelled = true;
        };

        for (Object p : ports) {
            if (p instanceof Channel ch) {
                TakeWaiter tw = new TakeWaiter(null, null);
                takeWaiters.add(tw);
                tw.done.thenAccept(v -> {
                    if (winner.complete(PersistentVector.createOwning(v, ch))) {
                        for (TakeWaiter o : takeWaiters) if (o != tw) o.cancelled = true;
                        for (PutWaiter o : putWaiters) o.cancelled = true;
                    }
                });
                ch.registerSelectTake(tw);
                if (winner.isDone()) break;
            } else if (RT.count(p) == 2) {
                Object p0 = RT.nth(p, 0);
                Object p1 = RT.nth(p, 1);
                if (p0 instanceof Channel ch) {
                    PutWaiter pw = new PutWaiter(p1, null);
                    putWaiters.add(pw);
                    pw.done.thenAccept(ok -> {
                        if (winner.complete(PersistentVector.createOwning(ok, ch))) {
                            for (TakeWaiter o : takeWaiters) o.cancelled = true;
                            for (PutWaiter o : putWaiters) if (o != pw) o.cancelled = true;
                        }
                    });
                    ch.registerSelectPut(pw);
                    if (winner.isDone()) break;
                }
            }
        }
        if (!winner.isDone() && hasDefault) {
            if (winner.complete(PersistentVector.createOwning(defaultVal, defaultKw))) {
                cancelAll.run();
            }
        }
        try {
            return winner.get();
        } catch (Exception e) {
            throw RT.sneakyThrow(e);
        }
    }

    // ========================================================================
    // clojure.math
    // ========================================================================

    private static synchronized void initMath() {
        if (LOADED.contains("clojure.math")) return;
        Namespace ns = Namespace.findOrCreate("clojure.math");
        Keyword kwDoc = Keyword.intern("doc");
        Keyword kwAdded = Keyword.intern("added");
        Keyword kwArglists = Keyword.intern("arglists");
        Keyword kwConst = Keyword.intern("const");

        ns.intern(Symbol.intern("E")).bindRoot(Math.E).setMeta(PersistentHashMap.EMPTY
                .assoc(kwDoc, "Constant for e, the base of the natural logarithms.")
                .assoc(kwConst, Boolean.TRUE)
                .assoc(kwAdded, "1.11"));
        ns.intern(Symbol.intern("PI")).bindRoot(Math.PI).setMeta(PersistentHashMap.EMPTY
                .assoc(kwDoc, "Constant for pi, the ratio of the circumference of a circle to its diameter.")
                .assoc(kwConst, Boolean.TRUE)
                .assoc(kwAdded, "1.11"));

        defMathFn(ns, "random", Fn.of0("random", Math::random),
                PersistentList.createFromArray(PersistentVector.EMPTY),
                "Returns a double in the range [0.0, 1.0).", kwDoc, kwAdded, kwArglists);

        // 1-arg double -> double
        defMathD1(ns, "sin", "a", Math::sin, "Returns the sine of an angle in radians.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "cos", "a", Math::cos, "Returns the cosine of an angle in radians.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "tan", "a", Math::tan, "Returns the tangent of an angle in radians.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "asin", "a", Math::asin, "Returns the arc sine of a value in [-pi/2, pi/2].", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "acos", "a", Math::acos, "Returns the arc cosine of a value in [0.0, pi].", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "atan", "a", Math::atan, "Returns the arc tangent of a value in [-pi/2, pi/2].", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "to-radians", "deg", Math::toRadians, "Converts an angle measured in degrees to radians.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "to-degrees", "r", Math::toDegrees, "Converts an angle measured in radians to degrees.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "exp", "a", Math::exp, "Returns Euler's number e raised to the power of a.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "log", "a", Math::log, "Returns the natural logarithm (base e) of a.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "log10", "a", Math::log10, "Returns the base 10 logarithm of a.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "sqrt", "a", Math::sqrt, "Returns the correctly rounded positive square root of a.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "cbrt", "a", Math::cbrt, "Returns the cube root of a.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "ceil", "a", Math::ceil, "Returns the smallest double >= a that is equal to a mathematical integer.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "floor", "a", Math::floor, "Returns the largest double <= a that is equal to a mathematical integer.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "rint", "a", Math::rint, "Returns the double closest in value to a that is equal to a mathematical integer.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "ulp", "d", Math::ulp, "Returns the size of an ulp of the argument.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "signum", "d", Math::signum, "Returns the signum function of the argument (-1.0, 0.0, 1.0, or NaN).", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "sinh", "x", Math::sinh, "Returns the hyperbolic sine of x.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "cosh", "x", Math::cosh, "Returns the hyperbolic cosine of x.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "tanh", "x", Math::tanh, "Returns the hyperbolic tangent of x.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "expm1", "x", Math::expm1, "Returns e^x - 1.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "log1p", "x", Math::log1p, "Returns the natural logarithm of x + 1.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "next-up", "d", Math::nextUp, "Returns the floating-point value adjacent to d in the direction of positive infinity.", kwDoc, kwAdded, kwArglists);
        defMathD1(ns, "next-down", "d", Math::nextDown, "Returns the floating-point value adjacent to d in the direction of negative infinity.", kwDoc, kwAdded, kwArglists);

        // 1-arg double -> long
        defMathFn(ns, "round", Fn.of1("round", a -> Math.round(Numbers.toDouble(a))),
                arglist1("a"), "Returns the closest long to the argument, rounding ties to positive infinity.",
                kwDoc, kwAdded, kwArglists);
        defMathFn(ns, "get-exponent", Fn.of1("get-exponent", d -> (long) Math.getExponent(Numbers.toDouble(d))),
                arglist1("d"), "Returns the unbiased exponent used in the representation of a double.",
                kwDoc, kwAdded, kwArglists);

        // 2-arg double, double -> double
        defMathD2(ns, "IEEE-remainder", "dividend", "divisor", Math::IEEEremainder,
                "Computes the remainder operation as prescribed by IEEE 754.", kwDoc, kwAdded, kwArglists);
        defMathD2(ns, "atan2", "y", "x", Math::atan2,
                "Returns the angle theta from converting rectangular (x, y) to polar coordinates (r, theta).",
                kwDoc, kwAdded, kwArglists);
        defMathD2(ns, "pow", "a", "b", Math::pow,
                "Returns the value of a raised to the power of b.", kwDoc, kwAdded, kwArglists);
        defMathD2(ns, "hypot", "x", "y", Math::hypot,
                "Returns sqrt(x^2 + y^2) without intermediate overflow or underflow.", kwDoc, kwAdded, kwArglists);
        defMathD2(ns, "copy-sign", "magnitude", "sign", Math::copySign,
                "Returns the first floating-point argument with the sign of the second floating-point argument.",
                kwDoc, kwAdded, kwArglists);
        defMathD2(ns, "next-after", "start", "direction", Math::nextAfter,
                "Returns the floating-point number adjacent to start in the direction of direction.",
                kwDoc, kwAdded, kwArglists);

        // 2-arg double, int -> double
        defMathFn(ns, "scalb",
                Fn.of2("scalb", (d, scaleFactor) -> Math.scalb(Numbers.toDouble(d), Numbers.intCast(scaleFactor))),
                arglist2("d", "scale-factor"),
                "Returns d * 2^scale-factor rounded as a single floating-point multiply.",
                kwDoc, kwAdded, kwArglists);

        // Exact & floor long operations
        defMathL1(ns, "increment-exact", "a", Math::incrementExact,
                "Returns a incremented by one, throwing ArithmeticException on long overflow.", kwDoc, kwAdded, kwArglists);
        defMathL1(ns, "decrement-exact", "a", Math::decrementExact,
                "Returns a decremented by one, throwing ArithmeticException on long overflow.", kwDoc, kwAdded, kwArglists);
        defMathL1(ns, "negate-exact", "a", Math::negateExact,
                "Returns the negation of a, throwing ArithmeticException on long overflow.", kwDoc, kwAdded, kwArglists);
        defMathL1(ns, "to-int-exact", "a", a -> (long) Math.toIntExact(a),
                "Returns the value of a long as an int, throwing ArithmeticException on int overflow.", kwDoc, kwAdded, kwArglists);

        defMathL2(ns, "add-exact", "x", "y", Math::addExact,
                "Returns the sum of x and y, throwing ArithmeticException on long overflow.", kwDoc, kwAdded, kwArglists);
        defMathL2(ns, "subtract-exact", "x", "y", Math::subtractExact,
                "Returns the difference x - y, throwing ArithmeticException on long overflow.", kwDoc, kwAdded, kwArglists);
        defMathL2(ns, "multiply-exact", "x", "y", Math::multiplyExact,
                "Returns the product of x and y, throwing ArithmeticException on long overflow.", kwDoc, kwAdded, kwArglists);
        defMathL2(ns, "floor-div", "x", "y", Math::floorDiv,
                "Returns the largest long value <= mathematical quotient x / y.", kwDoc, kwAdded, kwArglists);
        defMathL2(ns, "floor-mod", "x", "y", Math::floorMod,
                "Returns the floor modulus of the long arguments.", kwDoc, kwAdded, kwArglists);

        LOADED.add("clojure.math");
    }

    private static ISeq arglist1(String a) {
        return PersistentList.createFromArray(PersistentVector.createOwning(Symbol.intern(a)));
    }

    private static ISeq arglist2(String a, String b) {
        return PersistentList.createFromArray(PersistentVector.createOwning(Symbol.intern(a), Symbol.intern(b)));
    }

    private static void defMathFn(Namespace ns, String name, Fn fn, ISeq arglists, String doc,
                                  Keyword kwDoc, Keyword kwAdded, Keyword kwArglists) {
        ns.intern(Symbol.intern(name)).bindRoot(fn).setMeta(PersistentHashMap.EMPTY
                .assoc(kwArglists, arglists)
                .assoc(kwDoc, doc)
                .assoc(kwAdded, "1.11"));
    }

    private static void defMathD1(Namespace ns, String name, String p, DoubleUnaryOperator op, String doc,
                                  Keyword kwDoc, Keyword kwAdded, Keyword kwArglists) {
        defMathFn(ns, name, Fn.of1(name, a -> op.applyAsDouble(Numbers.toDouble(a))),
                arglist1(p), doc, kwDoc, kwAdded, kwArglists);
    }

    private static void defMathD2(Namespace ns, String name, String p1, String p2, DoubleBinaryOperator op, String doc,
                                  Keyword kwDoc, Keyword kwAdded, Keyword kwArglists) {
        defMathFn(ns, name, Fn.of2(name, (a, b) -> op.applyAsDouble(Numbers.toDouble(a), Numbers.toDouble(b))),
                arglist2(p1, p2), doc, kwDoc, kwAdded, kwArglists);
    }

    private static void defMathL1(Namespace ns, String name, String p, LongUnaryOperator op, String doc,
                                  Keyword kwDoc, Keyword kwAdded, Keyword kwArglists) {
        defMathFn(ns, name, Fn.of1(name, a -> op.applyAsLong(Numbers.longCast(a))),
                arglist1(p), doc, kwDoc, kwAdded, kwArglists);
    }

    private static void defMathL2(Namespace ns, String name, String p1, String p2, LongBinaryOperator op, String doc,
                                  Keyword kwDoc, Keyword kwAdded, Keyword kwArglists) {
        defMathFn(ns, name, Fn.of2(name, (a, b) -> op.applyAsLong(Numbers.longCast(a), Numbers.longCast(b))),
                arglist2(p1, p2), doc, kwDoc, kwAdded, kwArglists);
    }
}
