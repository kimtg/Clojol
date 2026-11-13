package cljl;

import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Base functional class for all callable objects in ClojureLite (functions, macros,
 * keywords, symbols, vectors, maps, sets, vars, multimethods).
 *
 * Provides arity-specialized invoke() overloads (0 to 4 args) to eliminate Object[]
 * allocation on the vast majority of calls, plus applyTo(Object[]) for variable arity.
 */
public abstract class Fn implements Callable<Object>, Runnable,
        Function<Object, Object>, Predicate<Object> {

    public static final Object[] EMPTY_ARGS = new Object[0];

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

    public Object invokeVarargs(Object[] args) {
        throw arityError(args.length);
    }

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
        if (res instanceof Boolean bool) {
            if (bool) return -1;
            Object rev = invoke(b, a);
            return RT.truthy(rev) ? 1 : 0;
        }
        if (res instanceof Number n) {
            return n.intValue();
        }
        return RT.compare(a, b);
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

    public abstract static class NamedFn extends Fn {
        private final String name;
        public NamedFn(String name) { this.name = name; }
        @Override public String getName() { return name; }
        @Override public String toString() { return "#<fn " + name + ">"; }
    }

    @FunctionalInterface public interface S0 { Object call(); }
    @FunctionalInterface public interface S1 { Object call(Object a); }
    @FunctionalInterface public interface S2 { Object call(Object a, Object b); }
    @FunctionalInterface public interface S3 { Object call(Object a, Object b, Object c); }
    @FunctionalInterface public interface SVar { Object call(Object[] args); }
}
