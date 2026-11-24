package clojure.lang;

/**
 * Clean-room clojure.lang.AFn abstract base class for Clojure functions and callable data structures.
 */
public abstract class AFn implements IFn {

    @Override public Object invoke() { return throwArity(0); }
    @Override public Object invoke(Object arg1) { return throwArity(1); }
    @Override public Object invoke(Object arg1, Object arg2) { return throwArity(2); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3) { return throwArity(3); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) { return throwArity(4); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) { return throwArity(5); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) { return throwArity(6); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7) { return throwArity(7); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) { return throwArity(8); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9) { return throwArity(9); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10) { return throwArity(10); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11) { return throwArity(11); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) { return throwArity(12); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) { return throwArity(13); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14) { return throwArity(14); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15) { return throwArity(15); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16) { return throwArity(16); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17) { return throwArity(17); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18) { return throwArity(18); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) { return throwArity(19); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20) { return throwArity(20); }
    @Override public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20, Object... args) {
        return throwArity(20 + (args != null ? args.length : 0));
    }

    @Override
    public Object applyTo(ISeq arglist) {
        return applyToHelper(this, arglist);
    }

    public static Object applyToHelper(IFn ifn, ISeq arglist) {
        Object[] args = cljl.RT.toArray(arglist);
        return switch (args.length) {
            case 0 -> ifn.invoke();
            case 1 -> ifn.invoke(args[0]);
            case 2 -> ifn.invoke(args[0], args[1]);
            case 3 -> ifn.invoke(args[0], args[1], args[2]);
            case 4 -> ifn.invoke(args[0], args[1], args[2], args[3]);
            case 5 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4]);
            case 6 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            case 9 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8]);
            case 10 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9]);
            case 11 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10]);
            case 12 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11]);
            case 13 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12]);
            case 14 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13]);
            case 15 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14]);
            case 16 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15]);
            case 17 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16]);
            case 18 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17]);
            case 19 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18]);
            case 20 -> ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18], args[19]);
            default -> {
                Object[] rest = new Object[args.length - 20];
                System.arraycopy(args, 20, rest, 0, rest.length);
                yield ifn.invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9],
                        args[10], args[11], args[12], args[13], args[14], args[15], args[16], args[17], args[18], args[19], rest);
            }
        };
    }

    public Object throwArity(int n) {
        throw new ArityException(n, getClass().getSimpleName());
    }

    @Override
    public Object call() {
        return invoke();
    }

    @Override
    public void run() {
        invoke();
    }
}
