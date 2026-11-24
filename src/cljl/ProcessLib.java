package cljl;

import cljl.lang.IDeref;
import cljl.lang.IPersistentMap;
import cljl.lang.ISeq;
import cljl.lang.PersistentHashMap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Clean-room implementation of Clojure 1.12's clojure.java.process namespace.
 * Lazy-loaded on demand via StdLibMore so cold startup pays zero cost.
 */
public final class ProcessLib {

    private ProcessLib() {}

    public static void initNamespace() {
        Namespace ns = Namespace.findOrCreate("clojure.java.process");

        Keyword kDir = Keyword.intern("dir");
        Keyword kEnv = Keyword.intern("env");
        Keyword kClearEnv = Keyword.intern("clear-env");
        Keyword kIn = Keyword.intern("in");
        Keyword kOut = Keyword.intern("out");
        Keyword kErr = Keyword.intern("err");
        Keyword kPipe = Keyword.intern("pipe");
        Keyword kInherit = Keyword.intern("inherit");
        Keyword kDiscard = Keyword.intern("discard");
        Keyword kStdout = Keyword.intern("stdout");
        Keyword kAppend = Keyword.intern("append");

        ns.intern("to-file", new Fn.NamedFn("to-file") {
            @Override public Object invoke(Object f) {
                File file = toFileObj(f);
                return ProcessBuilder.Redirect.to(file);
            }
            @Override public Object invokeVarargs(Object[] args) {
                if (args.length == 0) throw arityError(0);
                File file = toFileObj(args[0]);
                boolean append = false;
                for (int i = 1; i + 1 < args.length; i += 2) {
                    if (kAppend.equals(args[i])) append = RT.truthy(args[i + 1]);
                }
                return append ? ProcessBuilder.Redirect.appendTo(file) : ProcessBuilder.Redirect.to(file);
            }
        });

        ns.intern("from-file", Fn.of1("from-file", f -> ProcessBuilder.Redirect.from(toFileObj(f))));

        ns.intern("start", Fn.ofVarargs("start", args -> {
            if (args.length == 0) throw new IllegalArgumentException("start requires at least one argument");
            IPersistentMap opts = PersistentHashMap.EMPTY;
            int idx = 0;
            if (args[0] instanceof IPersistentMap m) {
                opts = m;
                idx = 1;
            }
            List<String> cmd = new ArrayList<>(args.length - idx);
            for (int i = idx; i < args.length; i++) {
                cmd.add(String.valueOf(args[i]));
            }
            if (cmd.isEmpty()) throw new IllegalArgumentException("start requires a command");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Object dir = opts.valAt(kDir);
            if (dir != null) pb.directory(toFileObj(dir));
            if (RT.truthy(opts.valAt(kClearEnv))) pb.environment().clear();
            Object env = opts.valAt(kEnv);
            if (env instanceof Map<?, ?> em) {
                Map<String, String> penv = pb.environment();
                for (Map.Entry<?, ?> e : em.entrySet()) {
                    if (e.getValue() == null) penv.remove(String.valueOf(e.getKey()));
                    else penv.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
            Object inOpt = opts.valAt(kIn);
            if (inOpt != null) pb.redirectInput(toRedirect(inOpt, true, kPipe, kInherit, kDiscard));
            Object outOpt = opts.valAt(kOut);
            if (outOpt != null) pb.redirectOutput(toRedirect(outOpt, false, kPipe, kInherit, kDiscard));
            Object errOpt = opts.valAt(kErr);
            if (errOpt != null) {
                if (kStdout.equals(errOpt)) pb.redirectErrorStream(true);
                else pb.redirectError(toRedirect(errOpt, false, kPipe, kInherit, kDiscard));
            }
            try {
                return pb.start();
            } catch (IOException e) {
                throw RT.sneakyThrow(e);
            }
        }));

        ns.intern("stdout", Fn.of1("stdout", p -> ((Process) p).getInputStream()));
        ns.intern("stderr", Fn.of1("stderr", p -> ((Process) p).getErrorStream()));
        ns.intern("stdin", Fn.of1("stdin", p -> ((Process) p).getOutputStream()));

        ns.intern("exit-ref", Fn.of1("exit-ref", p -> new ExitRef((Process) p)));

        ns.intern("io-task", Fn.of1("io-task", f -> {
            Atom.markMultiThreaded();
            Fn fn = (Fn) f;
            Map<Var, Object> bindings = Var.cloneThreadBindings();
            CompletableFuture<Object> cf = new CompletableFuture<>();
            Thread.startVirtualThread(() -> {
                if (!bindings.isEmpty()) Var.pushThreadBindings(bindings);
                try {
                    cf.complete(fn.invoke());
                } catch (Throwable t) {
                    cf.completeExceptionally(t);
                } finally {
                    if (!bindings.isEmpty()) Var.popThreadBindings();
                }
            });
            return new FutureDeref(cf);
        }));

        ns.intern("exec", Fn.ofVarargs("exec", args -> {
            if (args.length == 0) throw new IllegalArgumentException("exec requires at least one argument");
            IPersistentMap opts = PersistentHashMap.EMPTY;
            int idx = 0;
            if (args[0] instanceof IPersistentMap m) {
                opts = m;
                idx = 1;
            }
            // Default :err to :inherit unless explicitly specified, :out to :pipe
            if (!opts.containsKey(kOut)) opts = opts.assoc(kOut, kPipe);
            Object[] startArgs = new Object[1 + (args.length - idx)];
            startArgs[0] = opts;
            System.arraycopy(args, idx, startArgs, 1, args.length - idx);
            Process proc = (Process) ((Fn) ns.findInternedVar(Symbol.intern("start")).deref()).applyTo(startArgs);
            try {
                byte[] outBytes = proc.getInputStream().readAllBytes();
                int exit = proc.waitFor();
                if (exit != 0) {
                    throw new RuntimeException("Process failed with exit=" + exit);
                }
                return new String(outBytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw RT.sneakyThrow(e);
            }
        }));

        Core.markLibLoaded("clojure.java.process");
    }

    private static File toFileObj(Object f) {
        if (f instanceof File file) return file;
        if (f instanceof java.nio.file.Path p) return p.toFile();
        return new File(String.valueOf(f));
    }

    private static ProcessBuilder.Redirect toRedirect(
            Object opt, boolean isInput, Keyword kPipe, Keyword kInherit, Keyword kDiscard) {
        if (opt instanceof ProcessBuilder.Redirect r) return r;
        if (kPipe.equals(opt)) return ProcessBuilder.Redirect.PIPE;
        if (kInherit.equals(opt)) return ProcessBuilder.Redirect.INHERIT;
        if (kDiscard.equals(opt)) return ProcessBuilder.Redirect.DISCARD;
        if (opt instanceof File f) {
            return isInput ? ProcessBuilder.Redirect.from(f) : ProcessBuilder.Redirect.to(f);
        }
        throw new IllegalArgumentException("Invalid redirect option: " + opt);
    }

    private static final class ExitRef implements IDeref {
        private final Process proc;

        ExitRef(Process proc) {
            this.proc = proc;
        }

        @Override
        public Object deref() {
            try {
                return Long.valueOf(proc.waitFor());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        @Override
        public Object deref(long timeoutMs, Object timeoutVal) {
            try {
                if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return Long.valueOf(proc.exitValue());
                }
                return timeoutVal;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        public boolean isRealized() {
            return !proc.isAlive();
        }
    }

    private static final class FutureDeref implements IDeref, Future<Object> {
        private final CompletableFuture<Object> cf;

        FutureDeref(CompletableFuture<Object> cf) {
            this.cf = cf;
        }

        @Override
        public Object deref() {
            try {
                return cf.get();
            } catch (ExecutionException e) {
                throw RT.sneakyThrow(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        @Override
        public Object deref(long timeoutMs, Object timeoutVal) {
            try {
                return cf.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return timeoutVal;
            } catch (ExecutionException e) {
                throw RT.sneakyThrow(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw RT.sneakyThrow(e);
            }
        }

        public boolean isRealized() { return cf.isDone(); }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return cf.cancel(mayInterruptIfRunning); }
        @Override public boolean isCancelled() { return cf.isCancelled(); }
        @Override public boolean isDone() { return cf.isDone(); }
        @Override public Object get() throws InterruptedException, ExecutionException { return cf.get(); }
        @Override public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException { return cf.get(timeout, unit); }
    }
}
