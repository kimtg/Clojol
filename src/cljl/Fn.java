package cljl;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Base functional class for all callable objects in Clojol (functions, macros,
 * keywords, symbols, vectors, maps, sets, vars, multimethods).
 *
 * Intentionally does NOT implement java.io.Serializable (GHSA-x4c4-68gp-vrhc / CLJ-2993)
 * and guards serialization hooks unless -Dclojure.serializable.fns=true is explicitly set.
 *
 * Provides arity-specialized invoke() overloads (0 to 4 args) to eliminate Object[]
 * allocation on the vast majority of calls, plus applyTo(Object[]) for variable arity.
 */
public abstract class Fn implements clojure.lang.IFn, Callable<Object>, Runnable,
        Function<Object, Object>, Predicate<Object> {

    public static final Object[] EMPTY_ARGS = new Object[0];

    private void writeObject(ObjectOutputStream out) throws IOException {
        if (!Boolean.getBoolean("clojure.serializable.fns")) {
            throw new NotSerializableException(getClass().getName());
        }
        out.defaultWriteObject();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        if (!Boolean.getBoolean("clojure.serializable.fns")) {
            throw new NotSerializableException(getClass().getName());
        }
        in.defaultReadObject();
    }

    private void readObjectNoData() throws ObjectStreamException {
        throw new NotSerializableException(getClass().getName());
    }

    public Object invoke() {
        return invokeVarargs(EMPTY_ARGS);
    }

    public Object invoke(Object a) {
        return invokeVarargs(new Object[]{a});
    }

    public Object invoke(Object a, Object b) {
        return invokeVarargs(new Object[]{a, b});
    }

    public Object invoke(Object a, Object b, Object c) {
        return invokeVarargs(new Object[]{a, b, c});
    }

    public Object invoke(Object a, Object b, Object c, Object d) {
        return invokeVarargs(new Object[]{a, b, c, d});
    }

    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19, Object a20) { return invokeVarargs(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20}); }
    @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19, Object a20, Object... more) {
        Object[] all = new Object[20 + (more != null ? more.length : 0)];
        all[0]=a1; all[1]=a2; all[2]=a3; all[3]=a4; all[4]=a5; all[5]=a6; all[6]=a7; all[7]=a8; all[8]=a9; all[9]=a10;
        all[10]=a11; all[11]=a12; all[12]=a13; all[13]=a14; all[14]=a15; all[15]=a16; all[16]=a17; all[17]=a18; all[18]=a19; all[19]=a20;
        if (more != null && more.length > 0) System.arraycopy(more, 0, all, 20, more.length);
        return invokeVarargs(all);
    }
    @Override public Object applyTo(cljl.lang.ISeq arglist) { return applyTo(RT.toArray(arglist)); }

    public Object invokeVarargs(Object[] args) {
        throw arityError(args.length);
    }

    // Primitive-specialized dispatch hooks overridden by BytecodeJIT, core ops, Protocol.MethodFn, and transducers.
    public boolean isUnboxedLong1() { return false; }
    public boolean isUnboxedLong2() { return false; }
    public long invokePrim_J_J(long a) { return Numbers.longCast(invoke(Long.valueOf(a))); }
    public double invokePrim_D_D(double a) { return Numbers.toDouble(invoke(Double.valueOf(a))); }
    public boolean invokePrim_J_Z(long a) { return RT.truthy(invoke(Long.valueOf(a))); }
    public long invokePrim_JJ_J(long a, long b) { return Numbers.longCast(invoke(Long.valueOf(a), Long.valueOf(b))); }
    public double invokePrim_DD_D(double a, double b) { return Numbers.toDouble(invoke(Double.valueOf(a), Double.valueOf(b))); }
    public long invokePrim_OJ_J(Object a, long b) { return Numbers.longCast(invoke(a, Long.valueOf(b))); }
    public Object invokePrim_OJ_O(Object a, long b) { return invoke(a, Long.valueOf(b)); }

    public Object applyTo(Object[] args) {
        if (args == null || args.length == 0) return invoke();
        return switch (args.length) {
            case 1 -> invoke(args[0]);
            case 2 -> invoke(args[0], args[1]);
            case 3 -> invoke(args[0], args[1], args[2]);
            case 4 -> invoke(args[0], args[1], args[2], args[3]);
            default -> invokeVarargs(args);
        };
    }

    protected RuntimeException arityError(int actual) {
        return new IllegalArgumentException(
                "Wrong number of args (" + actual + ") passed to: " + getName());
    }

    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public Object call() {
        return invoke();
    }

    @Override
    public void run() {
        invoke();
    }

    @Override
    public Object apply(Object a) {
        return invoke(a);
    }

    @Override
    public boolean test(Object a) {
        return RT.truthy(invoke(a));
    }

    public Comparator<Object> asComparator() {
        return this::compare;
    }

    public int compare(Object a, Object b) {
        Object res = invoke(a, b);
        if (res instanceof Number n) {
            if (n instanceof Long l) return Long.signum(l);
            if (n instanceof Integer i) return Integer.compare(i, 0);
            if (Numbers.isPos(n)) return 1;
            if (Numbers.isNeg(n)) return -1;
            return 0;
        }
        if (res instanceof Boolean bool) {
            if (bool) return -1;
            return RT.truthy(invoke(b, a)) ? 1 : 0;
        }
        if (res == null || !RT.truthy(res)) {
            return RT.truthy(invoke(b, a)) ? 1 : 0;
        }
        return -1;
    }

    // Convenience factory helpers for fast core function registration
    public static Fn of0(String name, S0 f) {
        return new NamedFn(name) {
            @Override public Object invoke() { return f.call(); }
        };
    }

    public static Fn of1(String name, S1 f) {
        return new NamedFn(name) {
            @Override public Object invoke(Object a) { return f.call(a); }
        };
    }

    public static Fn of2(String name, S2 f) {
        return new NamedFn(name) {
            @Override public Object invoke(Object a, Object b) { return f.call(a, b); }
        };
    }

    public static Fn of3(String name, S3 f) {
        return new NamedFn(name) {
            @Override public Object invoke(Object a, Object b, Object c) { return f.call(a, b, c); }
        };
    }

    public static Fn of01(String name, S0 f0, S1 f1) {
        return new NamedFn(name) {
            @Override public Object invoke() { return f0.call(); }
            @Override public Object invoke(Object a) { return f1.call(a); }
        };
    }

    public static Fn of12(String name, S1 f1, S2 f2) {
        return new NamedFn(name) {
            @Override public Object invoke(Object a) { return f1.call(a); }
            @Override public Object invoke(Object a, Object b) { return f2.call(a, b); }
        };
    }

    public static Fn of23(String name, S2 f2, S3 f3) {
        return new NamedFn(name) {
            @Override public Object invoke(Object a, Object b) { return f2.call(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return f3.call(a, b, c); }
        };
    }

    public static Fn of123(String name, S1 f1, S2 f2, S3 f3) {
        return new NamedFn(name) {
            @Override public Object invoke(Object a) { return f1.call(a); }
            @Override public Object invoke(Object a, Object b) { return f2.call(a, b); }
            @Override public Object invoke(Object a, Object b, Object c) { return f3.call(a, b, c); }
        };
    }

    public static Fn ofVarargs(String name, SVar f) {
        return new NamedFn(name) {
            @Override public Object invoke() { return f.call(EMPTY_ARGS); }
            @Override public Object invoke(Object a) { return f.call(new Object[]{a}); }
            @Override public Object invoke(Object a, Object b) { return f.call(new Object[]{a, b}); }
            @Override public Object invoke(Object a, Object b, Object c) { return f.call(new Object[]{a, b, c}); }
            @Override public Object invoke(Object a, Object b, Object c, Object d) { return f.call(new Object[]{a, b, c, d}); }
            @Override public Object invokeVarargs(Object[] args) { return f.call(args); }
        };
    }

    public abstract static class NamedFn extends Fn implements cljl.lang.IObj {
        private final String name;
        public NamedFn(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public cljl.lang.PersistentHashMap meta() { return null; }
        @Override public Object withMeta(cljl.lang.PersistentHashMap m) {
            return m == null ? this : new MetaFn(this, m);
        }
        @Override public String toString() { return "#<fn " + name + ">"; }
    }

    public static final class MetaFn extends Fn implements cljl.lang.IObj {
        private final Fn fn;
        private final cljl.lang.PersistentHashMap meta;

        public MetaFn(Fn fn, cljl.lang.PersistentHashMap meta) {
            this.fn = fn;
            this.meta = meta;
        }

        @Override public Object invoke() { return fn.invoke(); }
        @Override public Object invoke(Object a) { return fn.invoke(a); }
        @Override public Object invoke(Object a, Object b) { return fn.invoke(a, b); }
        @Override public Object invoke(Object a, Object b, Object c) { return fn.invoke(a, b, c); }
        @Override public Object invoke(Object a, Object b, Object c, Object d) { return fn.invoke(a, b, c, d); }
        @Override public Object invokeVarargs(Object[] args) { return fn.invokeVarargs(args); }
        @Override public Object applyTo(Object[] args) { return fn.applyTo(args); }
        @Override public boolean isUnboxedLong1() { return fn.isUnboxedLong1(); }
        @Override public boolean isUnboxedLong2() { return fn.isUnboxedLong2(); }
        @Override public long invokePrim_J_J(long a) { return fn.invokePrim_J_J(a); }
        @Override public double invokePrim_D_D(double a) { return fn.invokePrim_D_D(a); }
        @Override public boolean invokePrim_J_Z(long a) { return fn.invokePrim_J_Z(a); }
        @Override public long invokePrim_JJ_J(long a, long b) { return fn.invokePrim_JJ_J(a, b); }
        @Override public double invokePrim_DD_D(double a, double b) { return fn.invokePrim_DD_D(a, b); }
        @Override public long invokePrim_OJ_J(Object a, long b) { return fn.invokePrim_OJ_J(a, b); }
        @Override public Object invokePrim_OJ_O(Object a, long b) { return fn.invokePrim_OJ_O(a, b); }
        @Override public String getName() { return fn.getName(); }
        @Override public cljl.lang.PersistentHashMap meta() { return meta; }
        @Override public Object withMeta(cljl.lang.PersistentHashMap m) {
            return m == this.meta ? this : new MetaFn(fn, m);
        }
        @Override public String toString() { return fn.toString(); }
    }

    @FunctionalInterface public interface S0 { Object call(); }
    @FunctionalInterface public interface S1 { Object call(Object a); }
    @FunctionalInterface public interface S2 { Object call(Object a, Object b); }
    @FunctionalInterface public interface S3 { Object call(Object a, Object b, Object c); }
    @FunctionalInterface public interface SVar { Object call(Object[] args); }
}
