package cljl;

import cljl.lang.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lazily initialized standard library namespaces:
 * clojure.string, clojure.set, clojure.walk, clojure.edn, clojure.java.io, clojure.test.
 * Loading happens only on demand so cold startup never pays for unused namespaces.
 */
public final class StdLib {

    private static final Set<String> LOADED = ConcurrentHashMap.newKeySet();

    private StdLib() {}

    public static boolean ensureLoaded(String nsName) {
        if (nsName == null) return false;
        if (LOADED.contains(nsName)) return true;
        return switch (nsName) {
            case "clojure.string" -> { initString(); yield true; }
            case "clojure.set" -> { initSet(); yield true; }
            case "clojure.walk" -> { initWalk(); yield true; }
            case "clojure.edn" -> { initEdn(); yield true; }
            case "clojure.java.io" -> { initJavaIo(); yield true; }
            case "clojure.test" -> { initTest(); yield true; }
            default -> false;
        };
    }

    private static synchronized void initString() {
        if (LOADED.contains("clojure.string")) return;
        Namespace ns = Namespace.findOrCreate("clojure.string");

        ns.intern("blank?", Fn.of1("blank?", s -> s == null || ((String) s).isBlank()));
        ns.intern("capitalize", Fn.of1("capitalize", s -> {
            String str = (String) s;
            if (str.isEmpty()) return str;
            return Character.toUpperCase(str.charAt(0)) + str.substring(1).toLowerCase(Locale.ROOT);
        }));
        ns.intern("ends-with?", Fn.of2("ends-with?", (s, suf) -> ((String) s).endsWith((String) suf)));
        ns.intern("starts-with?", Fn.of2("starts-with?", (s, pre) -> ((String) s).startsWith((String) pre)));
        ns.intern("includes?", Fn.of2("includes?", (s, sub) -> ((String) s).contains((CharSequence) sub)));
        ns.intern("index-of", Fn.of23("index-of",
                (s, val) -> {
                    int idx = val instanceof Character c ? ((String) s).indexOf(c) : ((String) s).indexOf((String) val);
                    return idx < 0 ? null : (long) idx;
                },
                (s, val, from) -> {
                    int f = (int) Numbers.toLong(from);
                    int idx = val instanceof Character c ? ((String) s).indexOf(c, f) : ((String) s).indexOf((String) val, f);
                    return idx < 0 ? null : (long) idx;
                }));
        ns.intern("last-index-of", Fn.of23("last-index-of",
                (s, val) -> {
                    int idx = val instanceof Character c ? ((String) s).lastIndexOf(c) : ((String) s).lastIndexOf((String) val);
                    return idx < 0 ? null : (long) idx;
                },
                (s, val, from) -> {
                    int f = (int) Numbers.toLong(from);
                    int idx = val instanceof Character c ? ((String) s).lastIndexOf(c, f) : ((String) s).lastIndexOf((String) val, f);
                    return idx < 0 ? null : (long) idx;
                }));
        ns.intern("join", Fn.of12("join",
                coll -> joinStrings("", coll),
                (sep, coll) -> joinStrings(sep.toString(), coll)));
        ns.intern("lower-case", Fn.of1("lower-case", s -> ((String) s).toLowerCase(Locale.ROOT)));
        ns.intern("upper-case", Fn.of1("upper-case", s -> ((String) s).toUpperCase(Locale.ROOT)));
        ns.intern("reverse", Fn.of1("reverse", s -> new StringBuilder((String) s).reverse().toString()));
        ns.intern("trim", Fn.of1("trim", s -> ((String) s).trim()));
        ns.intern("triml", Fn.of1("triml", s -> ((String) s).stripLeading()));
        ns.intern("trimr", Fn.of1("trimr", s -> ((String) s).stripTrailing()));
        ns.intern("trim-newline", Fn.of1("trim-newline", s -> {
            String str = (String) s;
            int end = str.length();
            while (end > 0 && (str.charAt(end - 1) == '\n' || str.charAt(end - 1) == '\r')) end--;
            return str.substring(0, end);
        }));
        ns.intern("split", Fn.of23("split",
                (s, re) -> PersistentVector.createOwning((Object[]) ((Pattern) re).split((CharSequence) s)),
                (s, re, limit) -> PersistentVector.createOwning((Object[]) ((Pattern) re).split((CharSequence) s, (int) Numbers.toLong(limit)))));
        ns.intern("split-lines", Fn.of1("split-lines", s ->
                PersistentVector.createOwning((Object[]) ((String) s).split("\\r?\\n", -1))));
        ns.intern("replace", Fn.of3("replace", (s, match, replacement) -> {
            String str = (String) s;
            if (match instanceof Character c && replacement instanceof Character rc) {
                return str.replace(c, rc);
            }
            if (match instanceof CharSequence cs && replacement instanceof CharSequence rcs) {
                return str.replace(cs, rcs);
            }
            if (match instanceof Pattern p) {
                if (replacement instanceof Fn fn) {
                    Matcher m = p.matcher(str);
                    StringBuilder sb = new StringBuilder();
                    while (m.find()) {
                        int gc = m.groupCount();
                        Object matchArg;
                        if (gc == 0) {
                            matchArg = m.group();
                        } else {
                            Object[] groups = new Object[gc + 1];
                            for (int i = 0; i <= gc; i++) groups[i] = m.group(i);
                            matchArg = PersistentVector.createOwning(groups);
                        }
                        m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(fn.invoke(matchArg))));
                    }
                    m.appendTail(sb);
                    return sb.toString();
                }
                return p.matcher(str).replaceAll(replacement.toString());
            }
            throw new IllegalArgumentException("Invalid match type for replace: " + match);
        }));
        ns.intern("replace-first", Fn.of3("replace-first", (s, match, replacement) -> {
            String str = (String) s;
            if (match instanceof CharSequence cs) {
                return Pattern.compile(Pattern.quote(cs.toString())).matcher(str).replaceFirst(Matcher.quoteReplacement(replacement.toString()));
            }
            if (match instanceof Pattern p) {
                return p.matcher(str).replaceFirst(replacement.toString());
            }
            throw new IllegalArgumentException("Invalid match type for replace-first: " + match);
        }));

        LOADED.add("clojure.string");
    }

    private static String joinStrings(String sep, Object coll) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (ISeq s = RT.seq(coll); s != null; s = s.next()) {
            if (!first) sb.append(sep);
            first = false;
            Object item = s.first();
            if (item != null) sb.append(item);
        }
        return sb.toString();
    }

    private static synchronized void initSet() {
        if (LOADED.contains("clojure.set")) return;
        Namespace ns = Namespace.findOrCreate("clojure.set");

        ns.intern("union", Fn.ofVarargs("union", args -> {
            if (args.length == 0) return PersistentHashSet.EMPTY;
            PersistentHashSet.TransientHashSet t = ((PersistentHashSet) args[0]).asTransient();
            for (int i = 1; i < args.length; i++) {
                for (ISeq s = RT.seq(args[i]); s != null; s = s.next()) t = t.conj(s.first());
            }
            return t.persistent();
        }));
        ns.intern("intersection", Fn.ofVarargs("intersection", args -> {
            if (args.length == 0) return PersistentHashSet.EMPTY;
            PersistentHashSet first = (PersistentHashSet) args[0];
            PersistentHashSet.TransientHashSet t = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = first.seq(); s != null; s = s.next()) {
                Object elem = s.first();
                boolean inAll = true;
                for (int i = 1; i < args.length; i++) {
                    if (!((PersistentHashSet) args[i]).contains(elem)) {
                        inAll = false;
                        break;
                    }
                }
                if (inAll) t = t.conj(elem);
            }
            return t.persistent();
        }));
        ns.intern("difference", Fn.ofVarargs("difference", args -> {
            if (args.length == 0) return PersistentHashSet.EMPTY;
            PersistentHashSet result = (PersistentHashSet) args[0];
            for (int i = 1; i < args.length; i++) {
                for (ISeq s = RT.seq(args[i]); s != null; s = s.next()) {
                    result = result.disjoin(s.first());
                }
            }
            return result;
        }));
        ns.intern("select", Fn.of2("select", (pred, xset) -> {
            Fn p = (Fn) pred;
            PersistentHashSet.TransientHashSet t = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = RT.seq(xset); s != null; s = s.next()) {
                Object elem = s.first();
                if (RT.truthy(p.invoke(elem))) t = t.conj(elem);
            }
            return t.persistent();
        }));
        ns.intern("subset?", Fn.of2("subset?", (s1, s2) -> {
            PersistentHashSet a = (PersistentHashSet) s1;
            PersistentHashSet b = (PersistentHashSet) s2;
            if (a.count() > b.count()) return Boolean.FALSE;
            for (ISeq s = a.seq(); s != null; s = s.next()) {
                if (!b.contains(s.first())) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));
        ns.intern("superset?", Fn.of2("superset?", (s1, s2) -> {
            PersistentHashSet a = (PersistentHashSet) s1;
            PersistentHashSet b = (PersistentHashSet) s2;
            if (a.count() < b.count()) return Boolean.FALSE;
            for (ISeq s = b.seq(); s != null; s = s.next()) {
                if (!a.contains(s.first())) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));
        ns.intern("map-invert", Fn.of1("map-invert", m -> {
            PersistentHashMap.TransientHashMap t = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = RT.seq(m); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                t = t.assoc(e.getValue(), e.getKey());
            }
            return t.persistent();
        }));
        ns.intern("rename-keys", Fn.of2("rename-keys", (m, kmap) -> {
            PersistentHashMap ret = (PersistentHashMap) m;
            for (ISeq s = RT.seq(kmap); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                Object oldk = e.getKey();
                Object newk = e.getValue();
                if (ret.containsKey(oldk)) {
                    Object val = ret.valAt(oldk);
                    ret = ret.without(oldk).assoc(newk, val);
                }
            }
            return ret;
        }));

        LOADED.add("clojure.set");
    }

    private static synchronized void initWalk() {
        if (LOADED.contains("clojure.walk")) return;
        Namespace ns = Namespace.findOrCreate("clojure.walk");

        Fn walk = Fn.of3("walk", (inner, outer, form) -> walkImpl((Fn) inner, (Fn) outer, form));
        ns.intern("walk", walk);
        ns.intern("postwalk", Fn.of2("postwalk", (f, form) -> postwalkImpl((Fn) f, form)));
        ns.intern("prewalk", Fn.of2("prewalk", (f, form) -> prewalkImpl((Fn) f, form)));
        ns.intern("keywordize-keys", Fn.of1("keywordize-keys", m -> postwalkImpl(Fn.of1("kw-keys", x -> {
            if (x instanceof PersistentHashMap pm) {
                PersistentHashMap.TransientHashMap t = PersistentHashMap.EMPTY.asTransient();
                for (ISeq s = pm.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry e = (PersistentHashMap.MapEntry) s.first();
                    Object k = e.key instanceof String str ? Keyword.intern(str) : e.key;
                    t = t.assoc(k, e.val);
                }
                return t.persistent();
            }
            return x;
        }), m)));
        ns.intern("stringify-keys", Fn.of1("stringify-keys", m -> postwalkImpl(Fn.of1("str-keys", x -> {
            if (x instanceof PersistentHashMap pm) {
                PersistentHashMap.TransientHashMap t = PersistentHashMap.EMPTY.asTransient();
                for (ISeq s = pm.seq(); s != null; s = s.next()) {
                    PersistentHashMap.MapEntry e = (PersistentHashMap.MapEntry) s.first();
                    Object k = e.key instanceof Keyword kw ? kw.getName() : e.key;
                    t = t.assoc(k, e.val);
                }
                return t.persistent();
            }
            return x;
        }), m)));

        LOADED.add("clojure.walk");
    }

    private static Object walkImpl(Fn inner, Fn outer, Object form) {
        if (form instanceof PersistentVector pv) {
            PersistentVector.TransientVector tv = PersistentVector.EMPTY.asTransient();
            for (int i = 0; i < pv.cnt; i++) tv = tv.conj(inner.invoke(pv.nth(i)));
            return outer.invoke(tv.persistent());
        }
        if (form instanceof PersistentHashMap pm) {
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                Object walkedEntry = inner.invoke(s.first());
                if (walkedEntry instanceof Map.Entry<?, ?> e) {
                    tm = tm.assoc(e.getKey(), e.getValue());
                } else if (walkedEntry instanceof PersistentVector pv && pv.count() == 2) {
                    tm = tm.assoc(pv.nth(0), pv.nth(1));
                }
            }
            return outer.invoke(tm.persistent());
        }
        if (form instanceof PersistentHashSet ps) {
            PersistentHashSet.TransientHashSet ts = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = ps.seq(); s != null; s = s.next()) ts = ts.conj(inner.invoke(s.first()));
            return outer.invoke(ts.persistent());
        }
        if (form instanceof PersistentHashMap.MapEntry me) {
            return outer.invoke(new PersistentHashMap.MapEntry(inner.invoke(me.key), inner.invoke(me.val)));
        }
        if (form instanceof ISeq seq) {
            List<Object> list = new ArrayList<>();
            for (ISeq s = seq.seq(); s != null; s = s.next()) list.add(inner.invoke(s.first()));
            return outer.invoke(PersistentList.create(list));
        }
        return outer.invoke(form);
    }

    private static Object postwalkImpl(Fn f, Object form) {
        return walkImpl(Fn.of1("postwalk-inner", x -> postwalkImpl(f, x)), f, form);
    }

    private static Object prewalkImpl(Fn f, Object form) {
        Object res = f.invoke(form);
        return walkImpl(Fn.of1("prewalk-inner", x -> prewalkImpl(f, x)), Fn.of1("id", x -> x), res);
    }

    private static synchronized void initEdn() {
        if (LOADED.contains("clojure.edn")) return;
        Namespace ns = Namespace.findOrCreate("clojure.edn");
        ns.intern("read-string", Fn.of12("read-string",
                s -> s == null ? null : Reader.readString((String) s, false, null),
                (opts, s) -> {
                    Object eof = opts instanceof PersistentHashMap m ? m.valAt(Keyword.intern("eof"), null) : null;
                    return s == null ? eof : Reader.readString((String) s, false, eof);
                }));
        LOADED.add("clojure.edn");
    }

    private static synchronized void initJavaIo() {
        if (LOADED.contains("clojure.java.io")) return;
        Namespace ns = Namespace.findOrCreate("clojure.java.io");
        ns.intern("file", Fn.ofVarargs("file", args -> {
            if (args.length == 1) return new File(args[0].toString());
            File f = args[0] instanceof File file ? file : new File(args[0].toString());
            for (int i = 1; i < args.length; i++) f = new File(f, args[i].toString());
            return f;
        }));
        ns.intern("reader", Fn.of1("reader", x -> {
            try {
                if (x instanceof File f) return new BufferedReader(new FileReader(f));
                return Files.newBufferedReader(Path.of(x.toString()));
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("writer", Fn.of1("writer", x -> {
            try {
                if (x instanceof File f) return new BufferedWriter(new FileWriter(f));
                return Files.newBufferedWriter(Path.of(x.toString()));
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("delete-file", Fn.of1("delete-file", f -> {
            File file = f instanceof File fl ? fl : new File(f.toString());
            return file.delete();
        }));
        LOADED.add("clojure.java.io");
    }

    private static synchronized void initTest() {
        if (LOADED.contains("clojure.test")) return;
        Namespace ns = Namespace.findOrCreate("clojure.test");
        Var counters = ns.intern(Symbol.intern("*report-counters*")).bindRoot(new Atom(PersistentHashMap.EMPTY)).setDynamic();
        Var currentTest = ns.intern(Symbol.intern("*testing-vars*")).bindRoot(PersistentList.EMPTY).setDynamic();

        ns.intern("inc-report-counter", Fn.of1("inc-report-counter", name -> {
            Atom a = (Atom) counters.deref();
            a.swap(Fn.of1("inc-c", m -> {
                PersistentHashMap pm = (PersistentHashMap) m;
                long cur = Numbers.toLong(pm.valAt(name, 0L));
                return pm.assoc(name, cur + 1L);
            }));
            return null;
        }));

        ns.internMacro("deftest", Fn.ofVarargs("deftest", args -> {
            Symbol name = (Symbol) args[0];
            Symbol named = name.withMeta(PersistentHashMap.EMPTY.assoc(Keyword.intern("test"), Boolean.TRUE));
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("defn"), named, PersistentVector.EMPTY, PersistentList.create(body));
        }));

        ns.internMacro("testing", Fn.ofVarargs("testing", args -> {
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        }));

        ns.intern("do-report", Fn.of1("do-report", m -> {
            PersistentHashMap pm = (PersistentHashMap) m;
            Object type = pm.valAt(Keyword.intern("type"));
            Atom a = (Atom) counters.deref();
            a.swap(Fn.of1("inc-c", cm -> {
                PersistentHashMap pcm = (PersistentHashMap) cm;
                long cur = Numbers.toLong(pcm.valAt(type, 0L));
                return pcm.assoc(type, cur + 1L);
            }));
            if (Keyword.intern("fail").equals(type) || Keyword.intern("error").equals(type)) {
                System.err.println("FAIL/ERROR: " + pm.valAt(Keyword.intern("message"), "")
                        + " expected: " + RT.printString(pm.valAt(Keyword.intern("expected")))
                        + " actual: " + RT.printString(pm.valAt(Keyword.intern("actual"))));
            }
            return null;
        }));

        ns.internMacro("is", Fn.ofVarargs("is", args -> {
            Object form = args[0];
            Object msg = args.length > 1 ? args[1] : null;
            return PersistentList.createFromArray(
                    Symbol.intern("let"),
                    PersistentVector.createOwning(Symbol.intern("res__"), form),
                    PersistentList.createFromArray(
                            Symbol.intern("if"), Symbol.intern("res__"),
                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("pass"),
                                            Keyword.intern("message"), msg)),
                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("fail"),
                                            Keyword.intern("message"), msg,
                                            Keyword.intern("expected"), PersistentList.createFromArray(Symbol.intern("quote"), form),
                                            Keyword.intern("actual"), Symbol.intern("res__")))),
                    Symbol.intern("res__"));
        }));

        ns.intern("run-tests", Fn.ofVarargs("run-tests", args -> {
            Namespace targetNs = args.length == 0 ? RT.currentNS()
                    : (args[0] instanceof Namespace n ? n : Namespace.find((Symbol) args[0]));
            Atom report = new Atom(PersistentHashMap.create(
                    Keyword.intern("test"), 0L,
                    Keyword.intern("pass"), 0L,
                    Keyword.intern("fail"), 0L,
                    Keyword.intern("error"), 0L));
            Map<Var, Object> bindings = Map.of(counters, report);
            Var.pushThreadBindings(bindings);
            try {
                System.out.println("\nTesting " + targetNs.name);
                for (Map.Entry<Symbol, Object> e : targetNs.getMappings().entrySet()) {
                    if (e.getValue() instanceof Var v && v.ns == targetNs
                            && RT.truthy(v.meta().valAt(Keyword.intern("test")))) {
                        report.swap(Fn.of1("inc-test", m -> {
                            PersistentHashMap pm = (PersistentHashMap) m;
                            return pm.assoc(Keyword.intern("test"), Numbers.toLong(pm.valAt(Keyword.intern("test"), 0L)) + 1L);
                        }));
                        try {
                            v.invoke();
                        } catch (Throwable t) {
                            report.swap(Fn.of1("inc-err", m -> {
                                PersistentHashMap pm = (PersistentHashMap) m;
                                return pm.assoc(Keyword.intern("error"), Numbers.toLong(pm.valAt(Keyword.intern("error"), 0L)) + 1L);
                            }));
                            System.err.println("ERROR in (" + v.sym.name + "): " + t);
                        }
                    }
                }
                PersistentHashMap summary = (PersistentHashMap) report.deref();
                System.out.println("Ran " + summary.valAt(Keyword.intern("test"), 0L) + " tests containing "
                        + (Numbers.toLong(summary.valAt(Keyword.intern("pass"), 0L))
                        + Numbers.toLong(summary.valAt(Keyword.intern("fail"), 0L))
                        + Numbers.toLong(summary.valAt(Keyword.intern("error"), 0L)))
                        + " assertions.");
                System.out.println(summary.valAt(Keyword.intern("fail"), 0L) + " failures, "
                        + summary.valAt(Keyword.intern("error"), 0L) + " errors.");
                return summary;
            } finally {
                Var.popThreadBindings();
            }
        }));

        LOADED.add("clojure.test");
    }
}
