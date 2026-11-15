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
import java.util.function.Function;
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
            case "clojure.data.priority-map" -> { initDataPriorityMap(); yield true; }
            case "clojure.data.int-map" -> { initDataIntMap(); yield true; }
            case "clojure.data.avl" -> { initDataAvl(); yield true; }
            case "flatland.ordered.map" -> { initFlatlandOrderedMap(); yield true; }
            case "flatland.ordered.set" -> { initFlatlandOrderedSet(); yield true; }
            case "flatland.useful.map" -> { initFlatlandUsefulMap(); yield true; }
            case "clojure.core.async" -> { initCoreAsync(); yield true; }
            case "clojure.tools.deps.cli.api" -> { Deps.ensureDepsCliApiLoaded(); yield true; }
            default -> false;
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
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            new ProcessBuilder("cmd", "/c", "start", "", uri.toString()).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", uri.toString()).start();
        } else {
            new ProcessBuilder("xdg-open", uri.toString()).start();
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
        ns.intern("demunge", Fn.of1("demunge", fnName -> String.valueOf(fnName).replace("_BANG_", "!").replace("_QMARK_", "?").replace("_", "-")));
        ns.intern("ex-triage", Fn.of1("ex-triage", data -> {
            Object m = data instanceof Throwable t
                    ? ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("Throwable->map")).deref()).invoke(t)
                    : data;
            String cause = String.valueOf(RT.get(m, Keyword.intern("cause"), ""));
            Object via = RT.get(m, Keyword.intern("via"));
            Object firstVia = via != null ? RT.first(via) : null;
            Object cls = firstVia != null ? RT.get(firstVia, Keyword.intern("type")) : null;
            Object msg = firstVia != null ? RT.get(firstVia, Keyword.intern("message")) : cause;
            return PersistentHashMap.create(
                    Keyword.intern("clojure.error/phase"), Keyword.intern("execution"),
                    Keyword.intern("clojure.error/class"), cls,
                    Keyword.intern("clojure.error/cause"), msg != null ? msg : cause);
        }));
        ns.intern("ex-str", Fn.of1("ex-str", triage -> {
            Object cls = RT.get(triage, Keyword.intern("clojure.error/class"));
            Object cause = RT.get(triage, Keyword.intern("clojure.error/cause"));
            return "Execution error" + (cls != null ? " (" + cls + ")" : "") + (cause != null ? ": " + cause : "");
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
        ns.intern("repl-caught", Fn.of1("repl-caught", e -> {
            StdLib.errWriter().println(e);
            StdLib.errWriter().flush();
            return null;
        }));
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
        ns.intern("diff", Fn.of2("diff", StdLibMore::diffImpl));
        LOADED.add("clojure.data");
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
                    PersistentVector sub = diffImpl(ma.get(k), mb.get(k));
                    if (sub.nth(0) != null) onlyA = onlyA.assoc(k, sub.nth(0));
                    if (sub.nth(1) != null) onlyB = onlyB.assoc(k, sub.nth(1));
                    if (sub.nth(2) != null || (ma.get(k) == null && mb.get(k) == null)) both = both.assoc(k, sub.nth(2));
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
            boolean hasA = false, hasB = false, hasC = false;
            for (int i = 0; i < max; i++) {
                boolean inA = i < la.size();
                boolean inB = i < lb.size();
                if (inA && inB) {
                    PersistentVector sub = diffImpl(la.get(i), lb.get(i));
                    oa[i] = sub.nth(0); if (oa[i] != null) hasA = true;
                    ob[i] = sub.nth(1); if (ob[i] != null) hasB = true;
                    oc[i] = sub.nth(2); if (oc[i] != null || (la.get(i) == null && lb.get(i) == null)) hasC = true;
                } else if (inA) {
                    oa[i] = la.get(i);
                    hasA = true;
                } else {
                    ob[i] = lb.get(i);
                    hasB = true;
                }
            }
            return PersistentVector.createOwning(
                    hasA ? PersistentVector.createOwning(oa) : null,
                    hasB ? PersistentVector.createOwning(ob) : null,
                    hasC ? PersistentVector.createOwning(oc) : null);
        }
        return PersistentVector.createOwning(a, b, null);
    }

    private static boolean isSequentialColl(Object x) {
        return x instanceof PersistentVector || x instanceof ISeq || x instanceof List<?>;
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
        Namespace ns = Namespace.findOrCreate("clojure.datafy");
        Symbol datafyMeta = Symbol.intern("clojure.core.protocols", "datafy");
        Symbol navMeta = Symbol.intern("clojure.core.protocols", "nav");

        ns.intern("datafy", Fn.of1("datafy", x -> {
            if (x == null) return null;
            if (x instanceof IMeta im && im.meta() != null) {
                Object fn = im.meta().valAt(datafyMeta);
                if (fn instanceof Fn f) return f.invoke(x);
            }
            if (x instanceof Throwable t) {
                return ((Fn) RT.CLOJURE_NS.findInternedVar(Symbol.intern("Throwable->map")).deref()).invoke(t);
            }
            if (x instanceof Namespace n) {
                return PersistentHashMap.create(
                        Keyword.intern("name"), n.name,
                        Keyword.intern("publics"), n.getMappings().size());
            }
            if (x instanceof Var v) {
                return PersistentHashMap.create(
                        Keyword.intern("name"), v.sym,
                        Keyword.intern("ns"), v.ns.name,
                        Keyword.intern("val"), v.deref());
            }
            return x;
        }));
        ns.intern("nav", Fn.of3("nav", (coll, k, v) -> {
            if (coll instanceof IMeta im && im.meta() != null) {
                Object fn = im.meta().valAt(navMeta);
                if (fn instanceof Fn f) return f.invoke(coll, k, v);
            }
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
                    return m instanceof PersistentHashMap pm && s instanceof IObj sm ? sm.withMeta(pm) : s;
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

        Fn nodeFn = Fn.of1("node", loc -> ((PersistentVector) loc).nth(0));
        Fn pathFn = Fn.of1("path", loc -> ((PersistentVector) loc).nth(1));
        Fn branchFn = Fn.of1("branch?", loc -> {
            Fn f = (Fn) ((IMeta) loc).meta().valAt(kBranch);
            return f.invoke(nodeFn.invoke(loc));
        });
        Fn childrenFn = Fn.of1("children", loc -> {
            if (!RT.truthy(branchFn.invoke(loc))) throw new IllegalStateException("called children on a leaf node");
            Fn f = (Fn) ((IMeta) loc).meta().valAt(kChildren);
            return f.invoke(nodeFn.invoke(loc));
        });
        Fn makeNodeFn = Fn.of3("make-node", (loc, node, children) -> {
            Fn f = (Fn) ((IMeta) loc).meta().valAt(kMakeNode);
            return f.invoke(node, children);
        });
        Fn leftsFn = Fn.of1("lefts", loc -> {
            Object p = pathFn.invoke(loc);
            return p == null ? null : RT.seq(RT.get(p, kL));
        });
        Fn rightsFn = Fn.of1("rights", loc -> {
            Object p = pathFn.invoke(loc);
            return p == null ? null : RT.get(p, kR);
        });
        Fn downFn = Fn.of1("down", loc -> {
            if (!RT.truthy(branchFn.invoke(loc))) return null;
            Object cs = RT.seq(childrenFn.invoke(loc));
            if (cs == null) return null;
            Object n = nodeFn.invoke(loc);
            Object p = pathFn.invoke(loc);
            Object pnodes = p != null ? RT.conj(RT.get(p, kPnodes), n) : PersistentVector.createOwning(n);
            PersistentHashMap newPath = PersistentHashMap.create(
                    kL, PersistentVector.EMPTY,
                    kPnodes, pnodes,
                    kPpath, p,
                    kR, RT.next(cs));
            return PersistentVector.createOwning(RT.first(cs), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn upFn = Fn.of1("up", loc -> {
            Object n = nodeFn.invoke(loc);
            Object p = pathFn.invoke(loc);
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
            Object cur = loc;
            while (true) {
                if (kEnd.equals(pathFn.invoke(cur))) return nodeFn.invoke(cur);
                Object u = upFn.invoke(cur);
                if (u == null) return nodeFn.invoke(cur);
                cur = u;
            }
        });
        Fn rightFn = Fn.of1("right", loc -> {
            Object p = pathFn.invoke(loc);
            Object r = p == null ? null : RT.seq(RT.get(p, kR));
            if (r == null) return null;
            PersistentHashMap newPath = (PersistentHashMap) RT.assoc(RT.assoc(p, kL, RT.conj(RT.get(p, kL), nodeFn.invoke(loc))),
                    kR, RT.next(r));
            return PersistentVector.createOwning(RT.first(r), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn rightmostFn = Fn.of1("rightmost", loc -> {
            Object cur = loc;
            while (true) {
                Object r = rightFn.invoke(cur);
                if (r == null) return cur;
                cur = r;
            }
        });
        Fn leftFn = Fn.of1("left", loc -> {
            Object p = pathFn.invoke(loc);
            Object l = p == null ? null : RT.get(p, kL);
            if (l == null || RT.count(l) == 0) return null;
            PersistentVector lv = (PersistentVector) l;
            PersistentHashMap newPath = (PersistentHashMap) RT.assoc(RT.assoc(p, kL, lv.pop()),
                    kR, RT.cons(nodeFn.invoke(loc), RT.get(p, kR)));
            return PersistentVector.createOwning(lv.peek(), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn leftmostFn = Fn.of1("leftmost", loc -> {
            Object p = pathFn.invoke(loc);
            if (p == null) return loc;
            PersistentVector lv = (PersistentVector) RT.get(p, kL);
            if (lv.count() == 0) return loc;
            List<Object> newR = new ArrayList<>();
            for (int i = 1; i < lv.count(); i++) newR.add(lv.nth(i));
            newR.add(nodeFn.invoke(loc));
            newR.addAll(toList(RT.get(p, kR)));
            PersistentHashMap newPath = (PersistentHashMap) RT.assoc(RT.assoc(p, kL, PersistentVector.EMPTY),
                    kR, PersistentList.create(newR));
            return PersistentVector.createOwning(lv.nth(0), newPath).withMeta(((IMeta) loc).meta());
        });
        Fn replaceFn = Fn.of2("replace", (loc, node) -> {
            Object p = pathFn.invoke(loc);
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
            Object p = pathFn.invoke(loc);
            if (p == null) throw new IllegalStateException("Insert at top");
            PersistentHashMap np = (PersistentHashMap) RT.assoc(RT.assoc(p, kL, RT.conj(RT.get(p, kL), item)),
                    kChanged, Boolean.TRUE);
            return PersistentVector.createOwning(nodeFn.invoke(loc), np).withMeta(((IMeta) loc).meta());
        });
        Fn insertRightFn = Fn.of2("insert-right", (loc, item) -> {
            Object p = pathFn.invoke(loc);
            if (p == null) throw new IllegalStateException("Insert at top");
            PersistentHashMap np = (PersistentHashMap) RT.assoc(RT.assoc(p, kR, RT.cons(item, RT.get(p, kR))),
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
        Fn endPFn = Fn.of1("end?", loc -> kEnd.equals(pathFn.invoke(loc)));
        Fn nextFn = Fn.of1("next", loc -> {
            if (kEnd.equals(pathFn.invoke(loc))) return loc;
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
            Object p = pathFn.invoke(loc);
            if (p == null) throw new IllegalStateException("Remove at top");
            PersistentVector lv = (PersistentVector) RT.get(p, kL);
            if (lv.count() > 0) {
                PersistentHashMap np = (PersistentHashMap) RT.assoc(RT.assoc(p, kL, lv.pop()), kChanged, Boolean.TRUE);
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
                Object found = ((PersistentHashMap) reg.deref()).valAt(s);
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
            reg.swap(Fn.of1("reg-put", m -> ((PersistentHashMap) m).assoc(k, spec)));
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
        ns.intern("merge", Fn.ofVarargs("merge", specs -> ((Fn) ns.findInternedVar(Symbol.intern("and-spec-impl")).deref()).applyTo(specs)));

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
        ns.internMacro("fdef", Fn.ofVarargs("fdef", args -> PersistentList.createFromArray(Symbol.intern("quote"), args[0])));
        ns.internMacro("fspec", Fn.ofVarargs("fspec", args -> PersistentList.createFromArray(Symbol.intern("clojure.spec.alpha", "spec"), Symbol.intern("fn?"))));

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

        @Override public Object cons(Object x) {
            if (x instanceof Map.Entry<?, ?> e) return assoc(e.getKey(), e.getValue());
            if (x instanceof PersistentVector v && v.cnt == 2) return assoc(v.nth(0), v.nth(1));
            if (x instanceof PersistentVector.SubVec sv && sv.count() == 2) return assoc(sv.nth(0), sv.nth(1));
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

        @Override public Object empty() { return new PriorityMap(PersistentHashMap.EMPTY, (PersistentTreeMap) ordered.empty(), priCmp, meta); }

        @Override public Object peek() {
            ISeq s = seq();
            return s == null ? null : s.first();
        }

        @Override public Object pop() {
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
        @Override public Object cons(Object x) {
            if (x instanceof Map.Entry<?, ?> e) return assoc(e.getKey(), e.getValue());
            if (x instanceof PersistentVector v && v.cnt == 2) return assoc(v.nth(0), v.nth(1));
            if (x instanceof PersistentVector.SubVec sv && sv.count() == 2) return assoc(sv.nth(0), sv.nth(1));
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
        @Override public Object empty() { return new OrderedMap(PersistentHashMap.EMPTY, PersistentVector.EMPTY, meta); }

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
            if (callback != null) Thread.startVirtualThread(() -> callback.invoke(ok));
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
            if (callback != null) Thread.startVirtualThread(() -> callback.invoke(val));
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

        LOADED.add("clojure.core.async");
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
}
