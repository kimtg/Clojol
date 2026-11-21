package cljl;

import cljl.lang.*;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
            case "clojure.repl" -> { initRepl(); yield true; }
            default -> StdLibMore.ensureLoaded(nsName);
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
            if (match instanceof Character c && replacement instanceof Character rc) {
                int idx = str.indexOf(c);
                return idx < 0 ? str : str.substring(0, idx) + rc + str.substring(idx + 1);
            }
            if (match instanceof CharSequence cs) {
                return Pattern.compile(Pattern.quote(cs.toString())).matcher(str)
                        .replaceFirst(Matcher.quoteReplacement(replacement.toString()));
            }
            if (match instanceof Pattern p) {
                if (replacement instanceof Fn fn) {
                    Matcher m = p.matcher(str);
                    if (!m.find()) return str;
                    int gc = m.groupCount();
                    Object matchArg;
                    if (gc == 0) {
                        matchArg = m.group();
                    } else {
                        Object[] groups = new Object[gc + 1];
                        for (int i = 0; i <= gc; i++) groups[i] = m.group(i);
                        matchArg = PersistentVector.createOwning(groups);
                    }
                    StringBuilder sb = new StringBuilder();
                    m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(fn.invoke(matchArg))));
                    m.appendTail(sb);
                    return sb.toString();
                }
                return p.matcher(str).replaceFirst(replacement.toString());
            }
            throw new IllegalArgumentException("Invalid match type for replace-first: " + match);
        }));
        ns.intern("escape", Fn.of2("escape", (s, cmap) -> {
            String str = s.toString();
            StringBuilder sb = new StringBuilder(str.length());
            Fn fn = cmap instanceof Fn f ? f : null;
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                Object rep = fn != null ? fn.invoke(c) : RT.get(cmap, c);
                if (rep != null) sb.append(rep);
                else sb.append(c);
            }
            return sb.toString();
        }));
        ns.intern("re-quote-replacement", Fn.of1("re-quote-replacement",
                s -> Matcher.quoteReplacement(s.toString())));

        LOADED.add("clojure.string");
    }

    private static String joinStrings(String sep, Object coll) {
        if (coll instanceof LongRange lr) {
            int n = lr.count();
            StringBuilder sb = new StringBuilder(n * (sep.length() + 4));
            long v = lr.start;
            long step = lr.step;
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(sep);
                sb.append(v);
                v += step;
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        ISeq s = RT.seq(coll);
        while (s != null) {
            if (s instanceof IChunkedSeq cs) {
                IChunk c = cs.chunkedFirst();
                int n = c.count();
                if (c instanceof ArrayChunk ac && ac.array instanceof Object[] arr) {
                    int off = ac.off;
                    for (int i = 0; i < n; i++) {
                        if (!first) sb.append(sep);
                        first = false;
                        Object item = arr[off + i];
                        if (item != null) sb.append(item);
                    }
                } else {
                    for (int i = 0; i < n; i++) {
                        if (!first) sb.append(sep);
                        first = false;
                        Object item = c.nth(i);
                        if (item != null) sb.append(item);
                    }
                }
                s = cs.chunkedNext();
            } else {
                if (!first) sb.append(sep);
                first = false;
                Object item = s.first();
                if (item != null) sb.append(item);
                s = s.next();
            }
        }
        return sb.toString();
    }

    private static synchronized void initSet() {
        if (LOADED.contains("clojure.set")) return;
        Namespace ns = Namespace.findOrCreate("clojure.set");

        ns.intern("union", Fn.ofVarargs("union", args -> {
            if (args.length == 0) return PersistentHashSet.EMPTY;
            if (args.length == 1) return args[0];
            PersistentHashSet.TransientHashSet t;
            int startIdx = 0;
            if (args[0] instanceof PersistentHashSet phs) {
                t = phs.asTransient();
                startIdx = 1;
            } else {
                t = PersistentHashSet.EMPTY.asTransient();
            }
            for (int i = startIdx; i < args.length; i++) {
                for (ISeq s = RT.seq(args[i]); s != null; s = s.next()) t = t.conj(s.first());
            }
            return t.persistent();
        }));
        ns.intern("intersection", Fn.ofVarargs("intersection", args -> {
            if (args.length == 0) return PersistentHashSet.EMPTY;
            if (args.length == 1) return args[0];
            if (args[0] == null) return PersistentHashSet.EMPTY;
            for (int i = 1; i < args.length; i++) {
                if (args[i] == null) return PersistentHashSet.EMPTY;
            }
            PersistentHashSet.TransientHashSet t = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = RT.seq(args[0]); s != null; s = s.next()) {
                Object elem = s.first();
                boolean inAll = true;
                for (int i = 1; i < args.length; i++) {
                    boolean has = (args[i] instanceof Set<?> set) ? set.contains(elem) : RT.contains(args[i], elem);
                    if (!has) {
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
            if (args[0] == null) return null;
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                for (ISeq s = RT.seq(args[i]); s != null; s = s.next()) {
                    if (result instanceof PersistentHashSet phs) {
                        result = phs.disjoin(s.first());
                    } else if (result instanceof PersistentTreeSet pts) {
                        result = pts.disjoin(s.first());
                    } else {
                        break;
                    }
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
            if (s1 == null) return Boolean.TRUE;
            if (s2 == null) return RT.count(s1) == 0 ? Boolean.TRUE : Boolean.FALSE;
            if (RT.count(s1) > RT.count(s2)) return Boolean.FALSE;
            for (ISeq s = RT.seq(s1); s != null; s = s.next()) {
                boolean has = (s2 instanceof Set<?> set) ? set.contains(s.first()) : RT.contains(s2, s.first());
                if (!has) return Boolean.FALSE;
            }
            return Boolean.TRUE;
        }));
        ns.intern("superset?", Fn.of2("superset?", (s1, s2) -> {
            if (s2 == null) return Boolean.TRUE;
            if (s1 == null) return RT.count(s2) == 0 ? Boolean.TRUE : Boolean.FALSE;
            if (RT.count(s1) < RT.count(s2)) return Boolean.FALSE;
            for (ISeq s = RT.seq(s2); s != null; s = s.next()) {
                boolean has = (s1 instanceof Set<?> set) ? set.contains(s.first()) : RT.contains(s1, s.first());
                if (!has) return Boolean.FALSE;
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
        ns.intern("rename-keys", Fn.of2("rename-keys", (m, kmap) -> renameKeysImpl(m, kmap)));
        ns.intern("project", Fn.of2("project", (xrel, ks) -> {
            Set<Object> keySet = toKeySet(ks);
            PersistentHashSet.TransientHashSet t = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = RT.seq(xrel); s != null; s = s.next()) {
                t = t.conj(selectKeysSet(s.first(), keySet));
            }
            return t.persistent();
        }));
        ns.intern("rename", Fn.of2("rename", (xrel, kmap) -> {
            PersistentHashSet.TransientHashSet t = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = RT.seq(xrel); s != null; s = s.next()) {
                t = t.conj(renameKeysImpl(s.first(), kmap));
            }
            return t.persistent();
        }));
        ns.intern("index", Fn.of2("index", (xrel, ks) -> {
            Set<Object> keySet = toKeySet(ks);
            Map<Object, PersistentHashSet.TransientHashSet> groups = new LinkedHashMap<>();
            for (ISeq s = RT.seq(xrel); s != null; s = s.next()) {
                Object row = s.first();
                Object ik = selectKeysSet(row, keySet);
                groups.computeIfAbsent(ik, k -> PersistentHashSet.EMPTY.asTransient()).conj(row);
            }
            PersistentHashMap.TransientHashMap ret = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Object, PersistentHashSet.TransientHashSet> e : groups.entrySet()) {
                ret = ret.assoc(e.getKey(), e.getValue().persistent());
            }
            return ret.persistent();
        }));
        ns.intern("join", Fn.of23("join",
                (xrel, yrel) -> joinImpl(xrel, yrel, null),
                (xrel, yrel, km) -> joinImpl(xrel, yrel, km)));

        LOADED.add("clojure.set");
    }

    private static Object joinImpl(Object xrel, Object yrel, Object km) {
        if (RT.seq(xrel) == null || RT.seq(yrel) == null) return PersistentHashSet.EMPTY;
        Set<Object> xKeys = new LinkedHashSet<>();
        for (ISeq s = RT.keys(RT.first(xrel)); s != null; s = s.next()) xKeys.add(s.first());
        Set<Object> yKeys = new LinkedHashSet<>();
        for (ISeq s = RT.keys(RT.first(yrel)); s != null; s = s.next()) yKeys.add(s.first());
        Map<Object, Object> keyMap = new LinkedHashMap<>();
        if (km != null) {
            for (ISeq s = RT.seq(km); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                keyMap.put(e.getKey(), e.getValue());
            }
        } else {
            for (Object k : xKeys) if (yKeys.contains(k)) keyMap.put(k, k);
        }
        Set<Object> xJoinKeys = keyMap.keySet();
        Set<Object> yJoinKeys = new LinkedHashSet<>(keyMap.values());
        Map<Object, List<Object>> idx = new LinkedHashMap<>();
        for (ISeq s = RT.seq(yrel); s != null; s = s.next()) {
            Object yr = s.first();
            Object yk = selectKeysSet(yr, yJoinKeys);
            idx.computeIfAbsent(yk, k -> new ArrayList<>()).add(yr);
        }
        PersistentHashSet.TransientHashSet out = PersistentHashSet.EMPTY.asTransient();
        for (ISeq s = RT.seq(xrel); s != null; s = s.next()) {
            Object xr = s.first();
            PersistentHashMap.TransientHashMap lookup = PersistentHashMap.EMPTY.asTransient();
            for (Map.Entry<Object, Object> e : keyMap.entrySet()) {
                if (RT.contains(xr, e.getKey())) lookup = lookup.assoc(e.getValue(), RT.get(xr, e.getKey()));
            }
            List<Object> matches = idx.get(lookup.persistent());
            if (matches != null) {
                for (Object yr : matches) {
                    Object merged = xr;
                    for (ISeq ys = RT.seq(yr); ys != null; ys = ys.next()) {
                        Map.Entry<?, ?> me = (Map.Entry<?, ?>) ys.first();
                        merged = RT.assoc(merged, me.getKey(), me.getValue());
                    }
                    out = out.conj(merged);
                }
            }
        }
        return out.persistent();
    }

    private static Set<Object> toKeySet(Object ks) {
        Set<Object> set = new LinkedHashSet<>();
        for (ISeq s = RT.seq(ks); s != null; s = s.next()) set.add(s.first());
        return set;
    }

    private static Object selectKeysSet(Object m, Set<Object> ks) {
        PersistentHashMap.TransientHashMap t = PersistentHashMap.EMPTY.asTransient();
        for (Object k : ks) {
            if (RT.contains(m, k)) t = t.assoc(k, RT.get(m, k));
        }
        return t.persistent();
    }

    private static Object renameKeysImpl(Object m, Object kmap) {
        Object ret = m;
        for (ISeq s = RT.seq(kmap); s != null; s = s.next()) {
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
            Object oldk = e.getKey();
            Object newk = e.getValue();
            if (RT.contains(ret, oldk)) {
                Object val = RT.get(ret, oldk);
                ret = RT.assoc(RT.dissoc(ret, oldk), newk, val);
            }
        }
        return ret;
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
        ns.intern("postwalk-demo", Fn.of1("postwalk-demo", form -> postwalkImpl(Fn.of1("postwalk-demo-fn", x -> {
            System.out.println("Walked: " + RT.printString(x));
            return x;
        }), form)));
        ns.intern("prewalk-demo", Fn.of1("prewalk-demo", form -> prewalkImpl(Fn.of1("prewalk-demo-fn", x -> {
            System.out.println("Walked: " + RT.printString(x));
            return x;
        }), form)));
        Object notFound = new Object();
        ns.intern("postwalk-replace", Fn.of2("postwalk-replace", (smap, form) ->
                postwalkImpl(Fn.of1("postwalk-replace-fn", x -> {
                    Object v = RT.get(smap, x, notFound);
                    return v == notFound ? x : v;
                }), form)));
        ns.intern("prewalk-replace", Fn.of2("prewalk-replace", (smap, form) ->
                prewalkImpl(Fn.of1("prewalk-replace-fn", x -> {
                    Object v = RT.get(smap, x, notFound);
                    return v == notFound ? x : v;
                }), form)));
        ns.intern("macroexpand-all", Fn.of1("macroexpand-all", form -> {
            Var mx = RT.CLOJURE_NS.findInternedVar(Symbol.intern("macroexpand"));
            Fn mxFn = mx != null ? (Fn) mx.deref() : null;
            return prewalkImpl(Fn.of1("mx-all", x -> x instanceof ISeq && mxFn != null ? mxFn.invoke(x) : x), form);
        }));

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
        ns.intern("read", new Fn.NamedFn("read") {
            @Override public Object invoke() {
                return Reader.readEdnFromStream((java.io.Reader) RT.IN.get(), true, null, null);
            }
            @Override public Object invoke(Object stream) {
                return Reader.readEdnFromStream((java.io.Reader) stream, true, null, null);
            }
            @Override public Object invoke(Object opts, Object stream) {
                return Reader.readEdnFromStream(opts instanceof Map<?, ?> m ? m : null, (java.io.Reader) stream);
            }
        });
        ns.intern("read-string", Fn.of12("read-string",
                s -> Reader.readEdnString(null, (String) s),
                (opts, s) -> Reader.readEdnString(opts instanceof Map<?, ?> m ? m : null, (String) s)));
        LOADED.add("clojure.edn");
    }

    private static synchronized void initJavaIo() {
        if (LOADED.contains("clojure.java.io")) return;
        Namespace ns = Namespace.findOrCreate("clojure.java.io");
        ns.intern("as-file", Fn.of1("as-file", StdLib::asFileImpl));
        ns.intern("as-url", Fn.of1("as-url", StdLib::asUrlImpl));
        ns.intern("as-relative-path", Fn.of1("as-relative-path", x -> {
            File f = asFileImpl(x);
            if (f == null) throw new IllegalArgumentException("Cannot coerce to File: null");
            if (f.isAbsolute()) throw new IllegalArgumentException(f + " is not a relative path");
            return f.getPath();
        }));
        ns.intern("resource", Fn.of12("resource",
                n -> RT.baseLoader().getResource(n.toString()),
                (n, loader) -> ((ClassLoader) loader).getResource(n.toString())));
        ns.intern("file", Fn.ofVarargs("file", args -> {
            if (args.length == 1) return asFileImpl(args[0]);
            File f = asFileImpl(args[0]);
            for (int i = 1; i < args.length; i++) f = new File(f, args[i].toString());
            return f;
        }));
        ns.intern("input-stream", Fn.ofVarargs("input-stream", args -> {
            try {
                return openInputStream(args[0]);
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("output-stream", Fn.ofVarargs("output-stream", args -> {
            try {
                boolean append = hasAppendOpt(args, 1);
                return openOutputStream(args[0], append);
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("reader", Fn.ofVarargs("reader", args -> {
            try {
                Object x = args[0];
                if (x instanceof java.io.Reader r) return r instanceof BufferedReader ? r : new BufferedReader(r);
                return new BufferedReader(new InputStreamReader(openInputStream(x), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("writer", Fn.ofVarargs("writer", args -> {
            try {
                Object x = args[0];
                if (x instanceof Writer w) return w instanceof BufferedWriter ? w : new BufferedWriter(w);
                boolean append = hasAppendOpt(args, 1);
                return new BufferedWriter(new OutputStreamWriter(openOutputStream(x, append), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("copy", Fn.ofVarargs("copy", args -> {
            try {
                Object in = args[0];
                Object out = args[1];
                int bufSize = 8192;
                if (in instanceof InputStream is) {
                    if (out instanceof OutputStream os) {
                        is.transferTo(os);
                    } else if (out instanceof File || out instanceof Path || out instanceof String || out instanceof URI || out instanceof URL) {
                        try (OutputStream os = openOutputStream(out, false)) { is.transferTo(os); }
                    } else if (out instanceof Writer w) {
                        new InputStreamReader(is, StandardCharsets.UTF_8).transferTo(w);
                        w.flush();
                    }
                    return null;
                }
                if (in instanceof java.io.Reader r) {
                    if (out instanceof Writer w) {
                        r.transferTo(w);
                        w.flush();
                    } else if (out instanceof OutputStream os) {
                        Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8);
                        r.transferTo(w);
                        w.flush();
                    } else {
                        try (Writer w = new BufferedWriter(new OutputStreamWriter(openOutputStream(out, false), StandardCharsets.UTF_8))) {
                            r.transferTo(w);
                        }
                    }
                    return null;
                }
                if (in instanceof byte[] bytes) {
                    if (out instanceof OutputStream os) {
                        os.write(bytes);
                    } else {
                        try (OutputStream os = openOutputStream(out, false)) { os.write(bytes); }
                    }
                    return null;
                }
                if (in instanceof File f) {
                    if (out instanceof OutputStream os) {
                        Files.copy(f.toPath(), os);
                    } else if (out instanceof File of) {
                        Files.copy(f.toPath(), of.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else if (out instanceof Writer w) {
                        try (java.io.Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) { r.transferTo(w); w.flush(); }
                    } else {
                        try (OutputStream os = openOutputStream(out, false)) { Files.copy(f.toPath(), os); }
                    }
                    return null;
                }
                if (in instanceof CharSequence cs) {
                    if (out instanceof Writer w) {
                        w.write(cs.toString());
                        w.flush();
                    } else if (out instanceof OutputStream os) {
                        os.write(cs.toString().getBytes(StandardCharsets.UTF_8));
                    } else {
                        try (Writer w = new BufferedWriter(new OutputStreamWriter(openOutputStream(out, false), StandardCharsets.UTF_8))) {
                            w.write(cs.toString());
                        }
                    }
                    return null;
                }
                try (InputStream is = openInputStream(in); OutputStream os = openOutputStream(out, false)) {
                    byte[] buf = new byte[bufSize];
                    int n;
                    while ((n = is.read(buf)) >= 0) os.write(buf, 0, n);
                }
                return null;
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));
        ns.intern("delete-file", Fn.of12("delete-file",
                f -> {
                    File file = asFileImpl(f);
                    if (file != null && !file.delete()) {
                        throw new RuntimeException(new IOException("Couldn't delete " + file));
                    }
                    return Boolean.TRUE;
                },
                (f, silently) -> {
                    File file = asFileImpl(f);
                    if (file == null) return Boolean.FALSE;
                    if (!file.delete()) {
                        if (RT.truthy(silently)) return Boolean.FALSE;
                        throw new RuntimeException(new IOException("Couldn't delete " + file));
                    }
                    return Boolean.TRUE;
                }));
        LOADED.add("clojure.java.io");
    }

    private static File asFileImpl(Object x) {
        if (x == null) return null;
        if (x instanceof File f) return f;
        if (x instanceof Path p) return p.toFile();
        if (x instanceof URI u) return new File(u);
        if (x instanceof URL url) {
            try {
                return new File(url.toURI());
            } catch (Exception e) {
                return new File(url.getPath());
            }
        }
        String s = x.toString();
        if (s.startsWith("file:")) {
            try { return new File(URI.create(s)); } catch (Exception ignored) {}
        }
        return new File(s);
    }

    private static URL asUrlImpl(Object x) {
        if (x == null) return null;
        try {
            if (x instanceof URL u) return u;
            if (x instanceof URI u) return u.toURL();
            if (x instanceof File f) return f.toURI().toURL();
            if (x instanceof Path p) return p.toUri().toURL();
            return URI.create(x.toString()).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw RT.sneakyThrow(e);
        }
    }

    private static boolean hasAppendOpt(Object[] args, int startIdx) {
        Keyword appendKw = Keyword.intern("append");
        for (int i = startIdx; i + 1 < args.length; i += 2) {
            if (appendKw.equals(args[i])) return RT.truthy(args[i + 1]);
        }
        return false;
    }

    private static InputStream openInputStream(Object x) throws IOException {
        if (x instanceof InputStream is) return is instanceof BufferedInputStream ? is : new BufferedInputStream(is);
        if (x instanceof java.net.Socket sock) return new BufferedInputStream(sock.getInputStream());
        if (x instanceof java.nio.channels.ReadableByteChannel ch) return new BufferedInputStream(java.nio.channels.Channels.newInputStream(ch));
        if (x instanceof byte[] bytes) return new ByteArrayInputStream(bytes);
        if (x instanceof File f) return new BufferedInputStream(new FileInputStream(f));
        if (x instanceof Path p) return new BufferedInputStream(Files.newInputStream(p));
        if (x instanceof URL u) return new BufferedInputStream(u.openStream());
        if (x instanceof URI u) return new BufferedInputStream(u.toURL().openStream());
        String s = x.toString();
        try {
            if (s.contains("://") || s.startsWith("file:") || s.startsWith("jar:")) {
                return new BufferedInputStream(URI.create(s).toURL().openStream());
            }
        } catch (Exception ignored) {}
        return new BufferedInputStream(new FileInputStream(s));
    }

    private static OutputStream openOutputStream(Object x, boolean append) throws IOException {
        if (x instanceof OutputStream os) return os instanceof BufferedOutputStream ? os : new BufferedOutputStream(os);
        if (x instanceof java.net.Socket sock) return new BufferedOutputStream(sock.getOutputStream());
        if (x instanceof java.nio.channels.WritableByteChannel ch) return new BufferedOutputStream(java.nio.channels.Channels.newOutputStream(ch));
        if (x instanceof File f) return new BufferedOutputStream(new FileOutputStream(f, append));
        if (x instanceof Path p) return new BufferedOutputStream(new FileOutputStream(p.toFile(), append));
        File f = asFileImpl(x);
        return new BufferedOutputStream(new FileOutputStream(f, append));
    }

    private static synchronized void initTest() {
        if (LOADED.contains("clojure.test")) return;
        Namespace ns = Namespace.findOrCreate("clojure.test");
        Var counters = ns.intern(Symbol.intern("*report-counters*")).bindRoot(new Atom(PersistentHashMap.EMPTY)).setDynamic();
        Var testingVars = ns.intern(Symbol.intern("*testing-vars*")).bindRoot(PersistentList.EMPTY).setDynamic();
        Var testingContexts = ns.intern(Symbol.intern("*testing-contexts*")).bindRoot(PersistentList.EMPTY).setDynamic();
        Var loadTests = ns.intern(Symbol.intern("*load-tests*")).bindRoot(Boolean.TRUE).setDynamic();
        Var stackTraceDepth = ns.intern(Symbol.intern("*stack-trace-depth*")).bindRoot(null).setDynamic();
        ns.intern(Symbol.intern("*test-out*")).bindRoot(RT.OUT.deref()).setDynamic();

        Keyword kTest = Keyword.intern("test");
        Keyword kPass = Keyword.intern("pass");
        Keyword kFail = Keyword.intern("fail");
        Keyword kError = Keyword.intern("error");
        Keyword kType = Keyword.intern("type");
        Keyword kOnce = Keyword.intern("once");
        Keyword kEach = Keyword.intern("each");
        Keyword kOnceFixtures = Keyword.intern("clojure.test", "once-fixtures");
        Keyword kEachFixtures = Keyword.intern("clojure.test", "each-fixtures");

        ns.intern("inc-report-counter", Fn.of1("inc-report-counter", name -> {
            Object aObj = counters.deref();
            if (aObj instanceof Atom a) {
                a.swap(Fn.of1("inc-c", m -> {
                    PersistentHashMap pm = (PersistentHashMap) m;
                    long cur = Numbers.toLong(pm.valAt(name, 0L));
                    return pm.assoc(name, cur + 1L);
                }));
            }
            return null;
        }));

        ns.intern("testing-vars-str", Fn.of1("testing-vars-str", m -> {
            StringBuilder sb = new StringBuilder();
            for (ISeq s = RT.seq(testingVars.deref()); s != null; s = s.next()) {
                if (!sb.isEmpty()) sb.append(' ');
                Object v = s.first();
                sb.append(v instanceof Var vr ? vr.sym.name : String.valueOf(v));
            }
            return sb.toString();
        }));

        ns.intern("testing-contexts-str", Fn.of0("testing-contexts-str", () -> {
            List<String> parts = new ArrayList<>();
            for (ISeq s = RT.seq(testingContexts.deref()); s != null; s = s.next()) {
                parts.add(String.valueOf(s.first()));
            }
            Collections.reverse(parts);
            return String.join(" ", parts);
        }));

        ns.intern("function?", Fn.of1("function?", x -> x instanceof Fn));

        ns.intern("successful?", Fn.of1("successful?", summary -> {
            if (!(summary instanceof Map<?, ?> m)) return Boolean.FALSE;
            long fails = Numbers.toLong(RT.get(summary, kFail, 0L));
            long errs = Numbers.toLong(RT.get(summary, kError, 0L));
            return fails == 0L && errs == 0L;
        }));

        MultiFn reportMulti = new MultiFn("report", Fn.of1("report-dispatch", m -> RT.get(m, kType)), Keyword.intern("default"));
        reportMulti.addMethod(Keyword.intern("default"), Fn.of1("report-default", m -> null));
        reportMulti.addMethod(kPass, Fn.of1("report-pass", m -> {
            ((Fn) ns.findInternedVar(Symbol.intern("inc-report-counter")).deref()).invoke(kPass);
            return null;
        }));
        reportMulti.addMethod(kFail, Fn.of1("report-fail", m -> {
            ((Fn) ns.findInternedVar(Symbol.intern("inc-report-counter")).deref()).invoke(kFail);
            String ctx = String.valueOf(((Fn) ns.findInternedVar(Symbol.intern("testing-contexts-str")).deref()).invoke());
            String vars = String.valueOf(((Fn) ns.findInternedVar(Symbol.intern("testing-vars-str")).deref()).invoke(m));
            System.err.println("FAIL in (" + vars + ")" + (ctx.isEmpty() ? "" : " " + ctx)
                    + ": " + RT.get(m, Keyword.intern("message"), "")
                    + " expected: " + RT.printString(RT.get(m, Keyword.intern("expected")))
                    + " actual: " + RT.printString(RT.get(m, Keyword.intern("actual"))));
            return null;
        }));
        reportMulti.addMethod(kError, Fn.of1("report-error", m -> {
            ((Fn) ns.findInternedVar(Symbol.intern("inc-report-counter")).deref()).invoke(kError);
            String ctx = String.valueOf(((Fn) ns.findInternedVar(Symbol.intern("testing-contexts-str")).deref()).invoke());
            String vars = String.valueOf(((Fn) ns.findInternedVar(Symbol.intern("testing-vars-str")).deref()).invoke(m));
            System.err.println("ERROR in (" + vars + ")" + (ctx.isEmpty() ? "" : " " + ctx)
                    + ": " + RT.get(m, Keyword.intern("message"), "")
                    + " expected: " + RT.printString(RT.get(m, Keyword.intern("expected")))
                    + " actual: " + RT.printString(RT.get(m, Keyword.intern("actual"))));
            return null;
        }));
        Var reportVar = ns.intern("report", reportMulti);

        ns.intern("do-report", Fn.of1("do-report", m -> ((Fn) reportVar.deref()).invoke(m)));

        ns.internMacro("deftest", Fn.ofVarargs("deftest", args -> {
            Symbol name = (Symbol) args[0];
            Symbol named = name.withMeta(PersistentHashMap.EMPTY.assoc(kTest, Boolean.TRUE));
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("defn"), named, PersistentVector.EMPTY, PersistentList.create(body));
        }));

        ns.internMacro("set-test", Fn.ofVarargs("set-test", args -> {
            Symbol name = (Symbol) args[0];
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("fn"));
            body.add(PersistentVector.EMPTY);
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("clojure.core", "alter-meta!"),
                    PersistentList.createFromArray(Symbol.intern("var"), name),
                    Symbol.intern("clojure.core", "assoc"),
                    kTest,
                    PersistentList.create(body));
        }));

        ns.internMacro("with-test", Fn.ofVarargs("with-test", args -> {
            Object defForm = args[0];
            Object defName = RT.second(defForm);
            List<Object> setTestArgs = new ArrayList<>();
            setTestArgs.add(Symbol.intern("clojure.test", "set-test"));
            setTestArgs.add(defName);
            for (int i = 1; i < args.length; i++) setTestArgs.add(args[i]);
            return PersistentList.createFromArray(
                    Symbol.intern("do"),
                    defForm,
                    PersistentList.create(setTestArgs));
        }));

        ns.internMacro("testing", Fn.ofVarargs("testing", args -> {
            Object desc = args[0];
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("clojure.core", "binding"));
            body.add(PersistentVector.createOwning(
                    Symbol.intern("clojure.test", "*testing-contexts*"),
                    PersistentList.createFromArray(
                            Symbol.intern("clojure.core", "conj"),
                            Symbol.intern("clojure.test", "*testing-contexts*"),
                            desc)));
            for (int i = 1; i < args.length; i++) body.add(args[i]);
            return PersistentList.create(body);
        }));

        Fn composeFixtures = Fn.of2("compose-fixtures", (f1, f2) ->
                Fn.of1("composed-fixture", g -> ((Fn) f1).invoke(Fn.of0("inner-fixture", () -> ((Fn) f2).invoke(g)))));
        ns.intern("compose-fixtures", composeFixtures);

        Fn joinFixtures = Fn.of1("join-fixtures", fixtures -> {
            Fn combined = Fn.of1("default-fixture", f -> ((Fn) f).invoke());
            List<Fn> list = new ArrayList<>();
            for (ISeq s = RT.seq(fixtures); s != null; s = s.next()) {
                list.add((Fn) s.first());
            }
            for (int i = list.size() - 1; i >= 0; i--) {
                combined = (Fn) composeFixtures.invoke(list.get(i), combined);
            }
            return combined;
        });
        ns.intern("join-fixtures", joinFixtures);

        ns.intern("use-fixtures", Fn.ofVarargs("use-fixtures", args -> {
            Object fixtureType = args[0];
            List<Object> fns = new ArrayList<>();
            for (int i = 1; i < args.length; i++) fns.add(args[i]);
            Keyword metaKey;
            if (kOnce.equals(fixtureType)) metaKey = kOnceFixtures;
            else if (kEach.equals(fixtureType)) metaKey = kEachFixtures;
            else throw new IllegalArgumentException("use-fixtures expects :once or :each, got: " + fixtureType);
            Namespace cur = RT.currentNS();
            PersistentHashMap curMeta = cur.meta() != null ? cur.meta() : PersistentHashMap.EMPTY;
            cur.resetMeta(curMeta.assoc(metaKey, PersistentVector.create(fns)));
            return null;
        }));

        ns.internMacro("is", Fn.ofVarargs("is", args -> {
            Object form = args[0];
            Object msg = args.length > 1 ? args[1] : null;
            Object quotedForm = PersistentList.createFromArray(Symbol.intern("quote"), form);
            if (form instanceof ISeq fseq && fseq.first() instanceof Symbol head
                    && (head.ns == null || "clojure.test".equals(head.ns))) {
                if ("thrown?".equals(head.name)) {
                    ISeq rest = fseq.next();
                    if (rest == null || rest.next() == null) {
                        throw new IllegalArgumentException("thrown? requires an exception class and body expression");
                    }
                    Object exCls = rest.first();
                    List<Object> tryBody = new ArrayList<>();
                    tryBody.add(Symbol.intern("do"));
                    for (ISeq b = rest.next(); b != null; b = b.next()) tryBody.add(b.first());
                    Symbol errSym = Symbol.intern("e__" + RT.nextID());
                    return PersistentList.createFromArray(
                            Symbol.intern("try"),
                            PersistentList.create(tryBody),
                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("fail"),
                                            Keyword.intern("message"), msg,
                                            Keyword.intern("expected"), quotedForm,
                                            Keyword.intern("actual"), null)),
                            null,
                            PersistentList.createFromArray(
                                    Symbol.intern("catch"), exCls, errSym,
                                    PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                            PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("pass"),
                                                    Keyword.intern("message"), msg,
                                                    Keyword.intern("expected"), quotedForm,
                                                    Keyword.intern("actual"), errSym)),
                                    errSym));
                }
                if ("thrown-with-msg?".equals(head.name)) {
                    ISeq rest = fseq.next();
                    ISeq afterCls = rest != null ? rest.next() : null;
                    if (rest == null || afterCls == null || afterCls.next() == null) {
                        throw new IllegalArgumentException("thrown-with-msg? requires an exception class, regex, and body expression");
                    }
                    Object exCls = rest.first();
                    Object reForm = afterCls.first();
                    List<Object> tryBody = new ArrayList<>();
                    tryBody.add(Symbol.intern("do"));
                    for (ISeq b = afterCls.next(); b != null; b = b.next()) tryBody.add(b.first());
                    Symbol errSym = Symbol.intern("e__" + RT.nextID());
                    Symbol msgSym = Symbol.intern("m__" + RT.nextID());
                    return PersistentList.createFromArray(
                            Symbol.intern("try"),
                            PersistentList.create(tryBody),
                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("fail"),
                                            Keyword.intern("message"), msg,
                                            Keyword.intern("expected"), quotedForm,
                                            Keyword.intern("actual"), null)),
                            null,
                            PersistentList.createFromArray(
                                    Symbol.intern("catch"), exCls, errSym,
                                    PersistentList.createFromArray(
                                            Symbol.intern("let"),
                                            PersistentVector.createOwning(msgSym,
                                                    PersistentList.createFromArray(Symbol.intern("."), errSym, Symbol.intern("getMessage"))),
                                            PersistentList.createFromArray(
                                                    Symbol.intern("if"),
                                                    PersistentList.createFromArray(Symbol.intern("clojure.core", "and"),
                                                            msgSym,
                                                            PersistentList.createFromArray(Symbol.intern("clojure.core", "re-find"), reForm, msgSym)),
                                                    PersistentList.createFromArray(Symbol.intern("do"),
                                                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("pass"),
                                                                            Keyword.intern("message"), msg,
                                                                            Keyword.intern("expected"), quotedForm,
                                                                            Keyword.intern("actual"), errSym)),
                                                            errSym),
                                                    PersistentList.createFromArray(Symbol.intern("do"),
                                                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("fail"),
                                                                            Keyword.intern("message"), msg,
                                                                            Keyword.intern("expected"), quotedForm,
                                                                            Keyword.intern("actual"), errSym)),
                                                            null)))));
                }
            }
            Symbol resSym = Symbol.intern("res__" + RT.nextID());
            return PersistentList.createFromArray(
                    Symbol.intern("let"),
                    PersistentVector.createOwning(resSym, form),
                    PersistentList.createFromArray(
                            Symbol.intern("if"), resSym,
                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("pass"),
                                            Keyword.intern("message"), msg)),
                            PersistentList.createFromArray(Symbol.intern("clojure.test", "do-report"),
                                    PersistentHashMap.create(Keyword.intern("type"), Keyword.intern("fail"),
                                            Keyword.intern("message"), msg,
                                            Keyword.intern("expected"), quotedForm,
                                            Keyword.intern("actual"), resSym))),
                    resSym);
        }));

        ns.internMacro("are", Fn.ofVarargs("are", args -> {
            if (args.length < 2 || !(args[0] instanceof PersistentVector argv)) {
                throw new IllegalArgumentException("are requires a parameter vector and template expression");
            }
            Object expr = args[1];
            int arity = argv.count();
            if (arity == 0) {
                return args.length == 2
                        ? PersistentList.createFromArray(Symbol.intern("clojure.test", "is"), expr)
                        : Boolean.TRUE;
            }
            if (((args.length - 2) % arity) != 0) {
                throw new IllegalArgumentException("The number of args doesn't match are's argv.");
            }
            List<Object> body = new ArrayList<>();
            body.add(Symbol.intern("do"));
            for (int i = 2; i < args.length; i += arity) {
                Map<Symbol, Object> subs = new HashMap<>();
                for (int j = 0; j < arity; j++) {
                    subs.put(((Symbol) argv.nth(j)).withMeta(null), args[i + j]);
                }
                body.add(PersistentList.createFromArray(
                        Symbol.intern("clojure.test", "is"),
                        substituteAreTemplate(expr, subs)));
            }
            return PersistentList.create(body);
        }));

        Fn testVarFn = Fn.of1("test-var", vObj -> {
            Var v = (Var) vObj;
            Object tMeta = v.meta().valAt(kTest);
            if (!RT.truthy(tMeta)) return null;
            ((Fn) ns.findInternedVar(Symbol.intern("inc-report-counter")).deref()).invoke(kTest);
            Var.pushThreadBindings(Map.of(testingVars, RT.conj(testingVars.deref(), v)));
            try {
                if (tMeta instanceof Fn tf) tf.invoke();
                else v.invoke();
            } catch (Throwable t) {
                ((Fn) reportVar.deref()).invoke(PersistentHashMap.create(
                        kType, kError,
                        Keyword.intern("message"), "Uncaught exception, not in assertion.",
                        Keyword.intern("expected"), null,
                        Keyword.intern("actual"), t));
            } finally {
                Var.popThreadBindings();
            }
            return null;
        });
        ns.intern("test-var", testVarFn);

        Fn testVarsFn = Fn.of1("test-vars", varsObj -> {
            Map<Namespace, List<Var>> byNs = new LinkedHashMap<>();
            for (ISeq s = RT.seq(varsObj); s != null; s = s.next()) {
                if (s.first() instanceof Var v) {
                    byNs.computeIfAbsent(v.ns, k -> new ArrayList<>()).add(v);
                }
            }
            for (Map.Entry<Namespace, List<Var>> entry : byNs.entrySet()) {
                Namespace targetNs = entry.getKey();
                PersistentHashMap nsMeta = targetNs.meta();
                Fn onceFixture = (Fn) joinFixtures.invoke(nsMeta != null ? nsMeta.valAt(kOnceFixtures) : null);
                Fn eachFixture = (Fn) joinFixtures.invoke(nsMeta != null ? nsMeta.valAt(kEachFixtures) : null);
                onceFixture.invoke(Fn.of0("run-ns-vars", () -> {
                    for (Var v : entry.getValue()) {
                        if (RT.truthy(v.meta().valAt(kTest))) {
                            eachFixture.invoke(Fn.of0("run-one-var", () -> testVarFn.invoke(v)));
                        }
                    }
                    return null;
                }));
            }
            return null;
        });
        ns.intern("test-vars", testVarsFn);

        Fn testAllVarsFn = Fn.of1("test-all-vars", nsObj -> {
            Namespace targetNs = nsObj instanceof Namespace n ? n : Namespace.find((Symbol) nsObj);
            if (targetNs == null) return null;
            List<Var> vars = new ArrayList<>();
            for (Map.Entry<Symbol, Object> e : targetNs.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns == targetNs && RT.truthy(v.meta().valAt(kTest))) {
                    vars.add(v);
                }
            }
            return testVarsFn.invoke(PersistentList.create(vars));
        });
        ns.intern("test-all-vars", testAllVarsFn);

        Fn testNsFn = Fn.of1("test-ns", nsObj -> {
            Namespace targetNs = nsObj instanceof Namespace n ? n : Namespace.find((Symbol) nsObj);
            if (targetNs == null) throw new IllegalArgumentException("No namespace: " + nsObj);
            Atom report = new Atom(PersistentHashMap.create(
                    kTest, 0L, kPass, 0L, kFail, 0L, kError, 0L));
            Var.pushThreadBindings(Map.of(counters, report));
            try {
                System.out.println("\nTesting " + targetNs.name);
                Var hook = targetNs.findInternedVar(Symbol.intern("test-ns-hook"));
                if (hook != null && hook.isBound() && hook.deref() instanceof Fn hookFn) {
                    hookFn.invoke();
                } else {
                    testAllVarsFn.invoke(targetNs);
                }
                return report.deref();
            } finally {
                Var.popThreadBindings();
            }
        });
        ns.intern("test-ns", testNsFn);

        ns.intern("run-tests", Fn.ofVarargs("run-tests", args -> {
            List<Namespace> targets = new ArrayList<>();
            if (args.length == 0) {
                targets.add(RT.currentNS());
            } else {
                for (Object a : args) {
                    Namespace t = a instanceof Namespace n ? n : Namespace.find((Symbol) a);
                    if (t == null) throw new IllegalArgumentException("No namespace: " + a);
                    targets.add(t);
                }
            }
            long totalTest = 0, totalPass = 0, totalFail = 0, totalErr = 0;
            for (Namespace t : targets) {
                PersistentHashMap m = (PersistentHashMap) testNsFn.invoke(t);
                totalTest += Numbers.toLong(m.valAt(kTest, 0L));
                totalPass += Numbers.toLong(m.valAt(kPass, 0L));
                totalFail += Numbers.toLong(m.valAt(kFail, 0L));
                totalErr += Numbers.toLong(m.valAt(kError, 0L));
            }
            PersistentHashMap summary = PersistentHashMap.create(
                    kTest, totalTest, kPass, totalPass, kFail, totalFail, kError, totalErr,
                    kType, Keyword.intern("summary"));
            System.out.println("Ran " + totalTest + " tests containing "
                    + (totalPass + totalFail + totalErr) + " assertions.");
            System.out.println(totalFail + " failures, " + totalErr + " errors.");
            return summary;
        }));

        ns.intern("run-all-tests", Fn.of01("run-all-tests",
                () -> {
                    List<Object> all = new ArrayList<>(Namespace.all());
                    return ((Fn) ns.findInternedVar(Symbol.intern("run-tests")).deref()).applyTo(all.toArray());
                },
                reObj -> {
                    Pattern pat = reObj instanceof Pattern p ? p : Pattern.compile(String.valueOf(reObj));
                    List<Object> matched = new ArrayList<>();
                    for (Namespace n : Namespace.all()) {
                        if (pat.matcher(n.name.name).find()) matched.add(n);
                    }
                    return ((Fn) ns.findInternedVar(Symbol.intern("run-tests")).deref()).applyTo(matched.toArray());
                }));

        LOADED.add("clojure.test");
    }

    private static synchronized void initRepl() {
        if (LOADED.contains("clojure.repl")) return;
        Namespace ns = Namespace.findOrCreate("clojure.repl");
        Var docVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("doc"));
        if (docVar != null) ns.refer(Symbol.intern("doc"), docVar);
        Var printDocVar = RT.CLOJURE_NS.findInternedVar(Symbol.intern("print-doc"));
        if (printDocVar != null) ns.refer(Symbol.intern("print-doc"), printDocVar);

        ns.intern("apropos", Fn.of1("apropos", strOrPat -> {
            Pattern p = strOrPat instanceof Pattern pat ? pat : Pattern.compile(Pattern.quote(strOrPat.toString()));
            boolean strMatch = !(strOrPat instanceof Pattern);
            List<Symbol> found = new ArrayList<>();
            for (Namespace n : Namespace.all()) {
                for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                    if (e.getValue() instanceof Var v && v.ns == n) {
                        String symName = e.getKey().name;
                        boolean matches = strMatch ? symName.contains(strOrPat.toString()) : p.matcher(symName).find();
                        if (matches) found.add(Symbol.intern(n.name.name, symName));
                    }
                }
            }
            found.sort(Comparator.comparing(s -> s.ns + "/" + s.name));
            return PersistentList.create(found);
        }));

        ns.intern("dir-fn", Fn.of1("dir-fn", nsSym -> {
            Namespace target = nsSym instanceof Namespace n ? n : Namespace.find((Symbol) nsSym);
            if (target == null) return PersistentList.EMPTY;
            List<Symbol> syms = new ArrayList<>();
            for (Map.Entry<Symbol, Object> e : target.getMappings().entrySet()) {
                if (e.getValue() instanceof Var v && v.ns == target) {
                    syms.add(e.getKey());
                }
            }
            syms.sort(Comparator.comparing(s -> s.name));
            return PersistentList.create(syms);
        }));

        ns.internMacro("dir", Fn.of1("dir", nsSym ->
                PersistentList.createFromArray(
                        Symbol.intern("doseq"),
                        PersistentVector.createOwning(
                                Symbol.intern("v__"),
                                PersistentList.createFromArray(Symbol.intern("clojure.repl", "dir-fn"),
                                        PersistentList.createFromArray(Symbol.intern("quote"), nsSym))),
                        PersistentList.createFromArray(Symbol.intern("println"), Symbol.intern("v__")))));

        ns.intern("find-doc", Fn.of1("find-doc", reOrStr -> {
            Pattern p = reOrStr instanceof Pattern pat ? pat : Pattern.compile(reOrStr.toString(), Pattern.CASE_INSENSITIVE);
            PrintWriter out = outWriter();
            Keyword docKw = Keyword.intern("doc");
            for (Namespace n : Namespace.all()) {
                List<Var> matched = new ArrayList<>();
                for (Map.Entry<Symbol, Object> e : n.getMappings().entrySet()) {
                    if (e.getValue() instanceof Var v && v.ns == n) {
                        String name = v.sym.name;
                        Object d = v.meta().valAt(docKw);
                        String doc = d == null ? "" : d.toString();
                        if (p.matcher(name).find() || (!doc.isEmpty() && p.matcher(doc).find())) {
                            matched.add(v);
                        }
                    }
                }
                matched.sort(Comparator.comparing(v -> v.sym.name));
                for (Var v : matched) {
                    if (printDocVar != null && printDocVar.deref() instanceof Fn pd) {
                        pd.invoke(v);
                    } else {
                        out.println("-------------------------");
                        out.println(v.ns.name.name + "/" + v.sym.name);
                        Object d = v.meta().valAt(docKw);
                        if (d != null) out.println("  " + d);
                    }
                }
            }
            out.flush();
            return null;
        }));

        ns.intern("pst", new Fn.NamedFn("pst") {
            @Override public Object invoke() { return pstImpl(RT.STAR_E.deref(), 12); }
            @Override public Object invoke(Object eOrDepth) {
                if (eOrDepth instanceof Number n) return pstImpl(RT.STAR_E.deref(), n.intValue());
                return pstImpl(eOrDepth, 12);
            }
            @Override public Object invoke(Object e, Object depth) { return pstImpl(e, toInt(depth)); }
        });

        ns.intern("source-fn", Fn.of1("source-fn", sym -> {
            Var v = resolveVarForRepl(sym);
            if (v == null) return null;
            Object fileObj = v.meta().valAt(Keyword.intern("file"));
            Object lineObj = v.meta().valAt(Keyword.intern("line"));
            if (fileObj == null || lineObj == null) return null;
            int line = toInt(lineObj);
            if (line <= 0) return null;
            String file = fileObj.toString();
            try (InputStream is = Files.exists(Path.of(file)) ? Files.newInputStream(Path.of(file))
                    : RT.baseLoader().getResourceAsStream(file)) {
                if (is == null) return null;
                List<String> lines = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)).lines().toList();
                if (line > lines.size()) return null;
                StringBuilder sb = new StringBuilder();
                int depth = 0;
                boolean started = false;
                for (int i = line - 1; i < lines.size(); i++) {
                    String l = lines.get(i);
                    if (i > line - 1) sb.append('\n');
                    sb.append(l);
                    boolean inStr = false;
                    for (int j = 0; j < l.length(); j++) {
                        char c = l.charAt(j);
                        if (c == '"' && (j == 0 || l.charAt(j - 1) != '\\')) inStr = !inStr;
                        if (inStr) continue;
                        if (c == ';') break;
                        if (c == '(' || c == '[' || c == '{') { depth++; started = true; }
                        else if (c == ')' || c == ']' || c == '}') depth--;
                    }
                    if (started && depth <= 0) break;
                }
                return sb.toString();
            } catch (IOException e) {
                return null;
            }
        }));

        ns.internMacro("source", Fn.of1("source", n ->
                PersistentList.createFromArray(
                        Symbol.intern("println"),
                        PersistentList.createFromArray(
                                Symbol.intern("or"),
                                PersistentList.createFromArray(Symbol.intern("clojure.repl", "source-fn"),
                                        PersistentList.createFromArray(Symbol.intern("quote"), n)),
                                "Source not found"))));

        LOADED.add("clojure.repl");
    }

    private static Var resolveVarForRepl(Object sym) {
        if (sym instanceof Var v) return v;
        if (sym instanceof Symbol s) {
            Namespace cur = RT.currentNS();
            if (s.ns != null) {
                Namespace target = cur.lookupAlias(Symbol.intern(s.ns));
                if (target == null) target = Namespace.find(Symbol.intern(s.ns));
                return target != null ? target.findInternedVar(Symbol.intern(s.name)) : null;
            }
            Object mapped = cur.getMappings().get(s);
            if (mapped instanceof Var v) return v;
            return RT.CLOJURE_NS.findInternedVar(s);
        }
        return null;
    }

    static PrintWriter outWriter() {
        return toPrintWriter(RT.OUT.get());
    }

    static PrintWriter errWriter() {
        return toPrintWriter(RT.ERR.get());
    }

    static PrintWriter toPrintWriter(Object w) {
        if (w instanceof PrintWriter pw) return pw;
        if (w instanceof Writer wr) return new PrintWriter(wr, true);
        return new PrintWriter(System.out, true);
    }

    static int toInt(Object o) {
        return (int) Numbers.toLong(o);
    }

    private static Object pstImpl(Object tObj, int depth) {
        if (!(tObj instanceof Throwable t)) return null;
        PrintWriter err = errWriter();
        err.println(t.getClass().getName() + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        StackTraceElement[] st = t.getStackTrace();
        int lim = Math.min(depth, st.length);
        for (int i = 0; i < lim; i++) {
            err.println("\t" + st[i]);
        }
        if (t.getCause() != null) {
            err.println("Caused by:");
            pstImpl(t.getCause(), depth);
        }
        err.flush();
        return null;
    }

    private static Object substituteAreTemplate(Object form, Map<Symbol, Object> subs) {
        if (form instanceof Symbol s && s.ns == null) {
            Symbol key = s.withMeta(null);
            if (subs.containsKey(key)) return subs.get(key);
            return form;
        }
        if (form instanceof ISeq seq) {
            List<Object> items = new ArrayList<>();
            for (ISeq s = seq.seq(); s != null; s = s.next()) {
                items.add(substituteAreTemplate(s.first(), subs));
            }
            Object res = PersistentList.create(items);
            if (form instanceof IMeta im && im.meta() != null && res instanceof IObj io) {
                return io.withMeta(im.meta());
            }
            return res;
        }
        if (form instanceof PersistentVector pv) {
            List<Object> items = new ArrayList<>(pv.count());
            for (int i = 0; i < pv.count(); i++) {
                items.add(substituteAreTemplate(pv.nth(i), subs));
            }
            PersistentVector res = PersistentVector.create(items);
            return pv.meta() != null ? res.withMeta(pv.meta()) : res;
        }
        if (form instanceof PersistentHashMap pm) {
            PersistentHashMap.TransientHashMap tm = PersistentHashMap.EMPTY.asTransient();
            for (ISeq s = pm.seq(); s != null; s = s.next()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) s.first();
                tm = tm.assoc(substituteAreTemplate(e.getKey(), subs), substituteAreTemplate(e.getValue(), subs));
            }
            PersistentHashMap res = tm.persistent();
            return pm.meta() != null ? res.withMeta(pm.meta()) : res;
        }
        if (form instanceof PersistentHashSet ps) {
            PersistentHashSet.TransientHashSet ts = PersistentHashSet.EMPTY.asTransient();
            for (ISeq s = ps.seq(); s != null; s = s.next()) {
                ts = ts.conj(substituteAreTemplate(s.first(), subs));
            }
            PersistentHashSet res = ts.persistent();
            return ps.meta() != null ? res.withMeta(ps.meta()) : res;
        }
        return form;
    }
}
