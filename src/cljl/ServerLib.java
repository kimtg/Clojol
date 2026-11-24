package cljl;

import cljl.lang.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Clean-room implementation of clojure.core.server:
 * Socket REPL and prepl/io-prepl server for editor/tooling integration.
 * Lazy-loaded on demand (or when -Dclojure.server.<name>=... is specified).
 */
public final class ServerLib {

    private static final ConcurrentHashMap<String, ServerSocket> SERVERS = new ConcurrentHashMap<>();

    private ServerLib() {}

    public static void startServersFromSystemProps() {
        Properties props = System.getProperties();
        boolean any = false;
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("clojure.server.")) {
                any = true;
                break;
            }
        }
        if (!any) return;
        RT.ensureInit();
        StdLib.ensureLoaded("clojure.core.server");
        Namespace ns = Namespace.find("clojure.core.server");
        Fn startFn = (Fn) ns.findInternedVar(Symbol.intern("start-server")).deref();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("clojure.server.")) {
                String serverName = name.substring("clojure.server.".length());
                String val = props.getProperty(name);
                Object optsObj = Reader.readEdnString(null, val);
                if (optsObj instanceof PersistentHashMap pm) {
                    if (!pm.containsKey(Keyword.intern("name"))) {
                        pm = pm.assoc(Keyword.intern("name"), serverName);
                    }
                    startFn.invoke(pm);
                }
            }
        }
    }

    public static void initNamespace() {
        Namespace ns = Namespace.findOrCreate("clojure.core.server");

        Keyword kName = Keyword.intern("name");
        Keyword kPort = Keyword.intern("port");
        Keyword kAddress = Keyword.intern("address");
        Keyword kAccept = Keyword.intern("accept");
        Keyword kArgs = Keyword.intern("args");
        Keyword kDaemon = Keyword.intern("server-daemon");

        ns.intern("resolve-fn", Fn.of1("resolve-fn", sym -> {
            if (!(sym instanceof Symbol s)) return sym;
            if (s.ns != null) Core.loadLib(s.ns);
            Var v = Compiler.lookupVar(RT.currentNS(), s, false);
            if (v == null) throw new IllegalArgumentException("Unable to resolve accept fn: " + s);
            return v.deref();
        }));

        ns.intern("repl", Fn.of0("repl", ServerLib::runSocketRepl));

        ns.intern("prepl", new Fn.NamedFn("prepl") {
            @Override public Object invoke(Object inReader, Object outFn) {
                return runPrepl(inReader, (Fn) outFn, null);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length < 2) throw arityError(args.length);
                return runPrepl(args[0], (Fn) args[1], null);
            }
        });

        ns.intern("io-prepl", Fn.ofVarargs("io-prepl", args -> {
            Fn valf = Fn.of1("pr-str", o -> RT.printString(o, true));
            for (int i = 0; i + 1 < args.length; i += 2) {
                if (Keyword.intern("valf").equals(args[i]) && args[i + 1] instanceof Fn f) {
                    valf = f;
                }
            }
            final Fn serializer = valf;
            PrintWriter out = RT.outWriter();
            Fn outFn = Fn.of1("io-prepl-out", m -> {
                if (m instanceof PersistentHashMap pm) {
                    if (Keyword.intern("ret").equals(pm.valAt(Keyword.intern("tag")))
                            && !RT.truthy(pm.valAt(Keyword.intern("exception")))) {
                        pm = pm.assoc(Keyword.intern("val"), serializer.invoke(pm.valAt(Keyword.intern("val"))));
                    }
                    out.println(RT.printString(pm, true));
                    out.flush();
                }
                return null;
            });
            return runPrepl(RT.IN.deref(), outFn, null);
        }));

        ns.intern("start-server", Fn.of1("start-server", optsObj -> {
            PersistentHashMap opts = (PersistentHashMap) optsObj;
            String name = String.valueOf(opts.valAt(kName, "server-" + RT.nextID()));
            int port = (int) Numbers.toLong(opts.valAt(kPort, 0L));
            String address = opts.valAt(kAddress) != null ? String.valueOf(opts.valAt(kAddress)) : "127.0.0.1";
            Object acceptSym = opts.valAt(kAccept, Symbol.intern("clojure.core.server", "repl"));
            Fn resolveFn = (Fn) ns.findInternedVar(Symbol.intern("resolve-fn")).deref();
            Fn acceptFn = (Fn) resolveFn.invoke(acceptSym);
            Object acceptArgsObj = opts.valAt(kArgs);
            Object[] acceptArgs = acceptArgsObj != null ? RT.toArray(acceptArgsObj) : Fn.EMPTY_ARGS;
            boolean daemon = RT.truthy(opts.valAt(kDaemon, Boolean.TRUE));
            try {
                InetAddress addr = InetAddress.getByName(address);
                ServerSocket ss = new ServerSocket(port, 50, addr);
                SERVERS.put(name, ss);
                Thread acceptThread = new Thread(() -> {
                    while (!ss.isClosed()) {
                        try {
                            Socket sock = ss.accept();
                            Thread.startVirtualThread(() -> handleClient(sock, acceptFn, acceptArgs));
                        } catch (IOException e) {
                            if (ss.isClosed()) break;
                        }
                    }
                }, "Clojol-Server-" + name);
                acceptThread.setDaemon(daemon);
                acceptThread.start();
                return ss;
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));

        ns.intern("stop-server", Fn.of01("stop-server",
                () -> stopServerImpl(null),
                name -> stopServerImpl(String.valueOf(name))));

        ns.intern("stop-servers", Fn.of0("stop-servers", () -> {
            for (String name : new ArrayList<>(SERVERS.keySet())) {
                stopServerImpl(name);
            }
            return null;
        }));

        ns.intern("remote-prepl", Fn.ofVarargs("remote-prepl", args -> {
            String host = String.valueOf(args[0]);
            int port = (int) Numbers.toLong(args[1]);
            Object inReader = args[2];
            Fn outFn = (Fn) args[3];
            try (Socket sock = new Socket(host, port);
                 BufferedReader sockIn = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter sockOut = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {
                BufferedReader in = toBufferedReader(inReader);
                String line;
                while ((line = in.readLine()) != null) {
                    sockOut.println(line);
                    String resp = sockIn.readLine();
                    if (resp == null) break;
                    outFn.invoke(Reader.readEdnString(null, resp));
                }
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
            return null;
        }));

        Core.markLibLoaded("clojure.core.server");
    }

    private static Object stopServerImpl(String name) {
        if (name == null) {
            if (SERVERS.isEmpty()) return Boolean.FALSE;
            name = SERVERS.keys().nextElement();
        }
        ServerSocket ss = SERVERS.remove(name);
        if (ss == null) return Boolean.FALSE;
        try {
            ss.close();
        } catch (IOException ignored) {}
        return Boolean.TRUE;
    }

    private static void handleClient(Socket sock, Fn acceptFn, Object[] acceptArgs) {
        try (Socket s = sock;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true)) {
            Map<Var, Object> bindings = new HashMap<>();
            bindings.put(RT.IN, in);
            bindings.put(RT.OUT, out);
            bindings.put(RT.ERR, out);
            bindings.put(RT.CURRENT_NS, RT.USER_NS);
            bindings.put(RT.REPL, Boolean.TRUE);
            bindings.put(RT.STAR_1, null);
            bindings.put(RT.STAR_2, null);
            bindings.put(RT.STAR_3, null);
            bindings.put(RT.STAR_E, null);
            Var.pushThreadBindings(bindings);
            try {
                acceptFn.applyTo(acceptArgs);
            } finally {
                Var.popThreadBindings();
            }
        } catch (IOException ignored) {
        } catch (Throwable t) {
            System.err.println("clojure.core.server error: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static BufferedReader toBufferedReader(Object r) {
        if (r instanceof BufferedReader br) return br;
        if (r instanceof java.io.Reader rd) return new BufferedReader(rd);
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    private static Object runSocketRepl() {
        BufferedReader in = toBufferedReader(RT.IN.deref());
        PrintWriter out = RT.outWriter();
        Object eof = new Object();
        StringBuilder buf = new StringBuilder();
        while (true) {
            try {
                if (buf.isEmpty()) {
                    out.print(RT.currentNS().name + "=> ");
                    out.flush();
                }
                String line = in.readLine();
                if (line == null) break;
                buf.append(line).append('\n');
                String src = buf.toString();
                if (src.trim().isEmpty()) {
                    buf.setLength(0);
                    continue;
                }
                List<Object> forms;
                try {
                    forms = Reader.readAll(src, "socket-repl");
                } catch (RuntimeException re) {
                    if (re.getMessage() != null && re.getMessage().contains("EOF while reading")) {
                        continue;
                    }
                    throw re;
                }
                buf.setLength(0);
                for (Object form : forms) {
                    if (form == eof) return null;
                    if (Symbol.intern("repl/quit").equals(form)) return null;
                    Object res = Compiler.eval(form);
                    RT.STAR_3.set(RT.STAR_2.deref());
                    RT.STAR_2.set(RT.STAR_1.deref());
                    RT.STAR_1.set(res);
                    out.println(RT.printString(res, true));
                    out.flush();
                }
            } catch (Throwable t) {
                buf.setLength(0);
                RT.STAR_E.set(t);
                out.println("Error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
                out.flush();
            }
        }
        return null;
    }

    private static Object runPrepl(Object inReader, Fn outFn, Object eofVal) {
        BufferedReader in = toBufferedReader(inReader);
        Keyword kTag = Keyword.intern("tag");
        Keyword kVal = Keyword.intern("val");
        Keyword kNs = Keyword.intern("ns");
        Keyword kMs = Keyword.intern("ms");
        Keyword kForm = Keyword.intern("form");
        Keyword kException = Keyword.intern("exception");
        Keyword kRet = Keyword.intern("ret");
        Keyword kOut = Keyword.intern("out");

        StringBuilder buf = new StringBuilder();
        while (true) {
            try {
                String line = in.readLine();
                if (line == null) break;
                buf.append(line).append('\n');
                String src = buf.toString();
                if (src.trim().isEmpty()) {
                    buf.setLength(0);
                    continue;
                }
                List<Object> forms;
                try {
                    forms = Reader.readAll(src, "prepl");
                } catch (RuntimeException re) {
                    if (re.getMessage() != null && re.getMessage().contains("EOF while reading")) {
                        continue;
                    }
                    throw re;
                }
                String formStr = src.trim();
                buf.setLength(0);
                for (Object form : forms) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw, true);
                    Var.pushThreadBindings(Map.of(RT.OUT, pw));
                    long t0 = System.nanoTime();
                    try {
                        Object res = Compiler.eval(form);
                        long ms = (System.nanoTime() - t0) / 1_000_000L;
                        pw.flush();
                        String printed = sw.toString();
                        if (!printed.isEmpty()) {
                            outFn.invoke(PersistentHashMap.create(kTag, kOut, kVal, printed));
                        }
                        outFn.invoke(PersistentHashMap.create(
                                kTag, kRet,
                                kVal, res,
                                kNs, RT.currentNS().name.name,
                                kMs, ms,
                                kForm, formStr));
                    } catch (Throwable t) {
                        long ms = (System.nanoTime() - t0) / 1_000_000L;
                        pw.flush();
                        String printed = sw.toString();
                        if (!printed.isEmpty()) {
                            outFn.invoke(PersistentHashMap.create(kTag, kOut, kVal, printed));
                        }
                        outFn.invoke(PersistentHashMap.create(
                                kTag, kRet,
                                kVal, PersistentHashMap.create(
                                        Keyword.intern("cause"), t.getMessage() != null ? t.getMessage() : t.getClass().getName(),
                                        Keyword.intern("via"), PersistentVector.createOwning(
                                                PersistentHashMap.create(
                                                        Keyword.intern("type"), Symbol.intern(t.getClass().getName()),
                                                        Keyword.intern("message"), t.getMessage()))),
                                kNs, RT.currentNS().name.name,
                                kMs, ms,
                                kForm, formStr,
                                kException, Boolean.TRUE));
                    } finally {
                        Var.popThreadBindings();
                    }
                }
            } catch (IOException e) {
                break;
            } catch (Throwable t) {
                buf.setLength(0);
                outFn.invoke(PersistentHashMap.create(
                        kTag, kRet,
                        kVal, t.getMessage(),
                        kNs, RT.currentNS().name.name,
                        kException, Boolean.TRUE));
            }
        }
        return null;
    }
}
